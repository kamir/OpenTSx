mod common;

use common::{episode, request, ARTIFACT_DIGEST, INPUT_TOPIC, OUTPUT_TOPIC};
use serde_json::{json, Value};
use std::collections::BTreeMap;
use std::fs;
use std::os::unix::fs::PermissionsExt;
use std::path::PathBuf;
use std::sync::atomic::{AtomicU64, Ordering};
use tsx_runner::admission::Admitted;
use tsx_runner::broker::{Broker, InputRecord, OutputRecord};
use tsx_runner::publication::{output_digest, HttpFencedBroker, PublicationGrant};
use tsx_runner::wire;
use tsx_runner::worker::{Config, Worker};

static NEXT: AtomicU64 = AtomicU64::new(0);

struct Directory(PathBuf);

impl Directory {
    fn new() -> Self {
        let path = std::env::temp_dir().canonicalize().unwrap().join(format!(
            "tsx-publication-{}-{}",
            std::process::id(),
            NEXT.fetch_add(1, Ordering::SeqCst)
        ));
        fs::create_dir(&path).unwrap();
        Self(path)
    }

    fn worker(&self) -> Worker {
        Worker::open(Config {
            node: "worker-1".into(),
            site: "local".into(),
            bootstrap: "localhost:9092".into(),
            artifact_digest: ARTIFACT_DIGEST.into(),
            state: self.0.clone(),
        })
        .unwrap()
    }

    fn checkpoint(&self) -> PathBuf {
        self.0.join("increments/checkpoint.json")
    }

    fn checkpoint_bytes(&self) -> Vec<u8> {
        fs::read(self.checkpoint()).unwrap()
    }
}

impl Drop for Directory {
    fn drop(&mut self) {
        fs::remove_dir_all(&self.0).unwrap();
    }
}

fn installation(operation: &str, epoch: i64, expected: Option<&str>, reset: bool) -> Value {
    let unique = NEXT.fetch_add(1, Ordering::SeqCst);
    let credential = tsx_contracts::sha256(&format!("{}:{unique}", std::process::id()));
    json!({"grant":{"schema":"opentsx.publication-grant.v1","operation":operation,
        "deployment":"learning-deployment","epoch":epoch.to_string(),"node":"worker-1",
        "inputTopic":INPUT_TOPIC,"outputTopic":OUTPUT_TOPIC,"token":credential},
        "reset":reset,"expectedHead":expected})
}

fn grant(value: &Value) -> PublicationGrant {
    serde_json::from_value(value["grant"].clone()).unwrap()
}

fn increment(sequence: i64, previous: Option<&str>, from: i64, to: i64, resample: bool) -> String {
    let mut value = request();
    value["schema"] = json!("opentsx.local-increment.v1");
    value["sequence"] = json!(sequence.to_string());
    value["previousRequestHash"] = json!(previous);
    let mut assignment: Value =
        serde_json::from_str(value["assignmentText"].as_str().unwrap()).unwrap();
    assignment["inputs"][0]["partitionCount"] = json!("1");
    assignment["inputs"][0]["positions"] =
        json!([{"partition":"0","from":from.to_string(),"to":to.to_string()}]);
    if resample {
        let mut fragment: Value =
            serde_json::from_str(assignment["fragmentText"].as_str().unwrap()).unwrap();
        fragment["nodes"][1] = json!({"id":"convert","op":"resample","in":["decode"],"params":{"every":"PT3S","agg":"MEAN"}});
        let fragment = fragment.to_string();
        assignment["fragmentText"] = json!(fragment);
        assignment["fragmentHash"] = json!(tsx_contracts::sha256(&fragment));
    }
    value["assignmentText"] = json!(assignment.to_string());
    value.to_string()
}

fn replacement(text: &str) -> String {
    let mut value: Value = serde_json::from_str(text).unwrap();
    let mut assignment: Value =
        serde_json::from_str(value["assignmentText"].as_str().unwrap()).unwrap();
    assignment["deployment"] = json!("replacement-deployment");
    let mut fragment: Value =
        serde_json::from_str(assignment["fragmentText"].as_str().unwrap()).unwrap();
    fragment["nodes"][1]["params"]["factor"] = json!(3);
    fragment["sinks"][0]["topic"] = json!("tsx.learning.replacement.v2");
    let fragment = fragment.to_string();
    assignment["fragmentText"] = json!(fragment);
    assignment["sourceDagText"] = json!(fragment);
    assignment["fragmentHash"] = json!(tsx_contracts::sha256(&fragment));
    assignment["specHash"] = json!(tsx_contracts::sha256(&fragment));
    value["assignmentText"] = json!(assignment.to_string());
    value["sink"]["topic"] = json!("tsx.learning.replacement.v2");
    value["bindings"]["local"]["outputs"] = json!(["tsx.learning.replacement.v2"]);
    value.to_string()
}

struct AuthorityBroker {
    current: PublicationGrant,
    records: Vec<InputRecord>,
    output: Vec<OutputRecord>,
    reads: usize,
    attempts: usize,
    denied: usize,
    completed: BTreeMap<String, String>,
    lose_response: bool,
    block_commit: Option<PathBuf>,
}

impl AuthorityBroker {
    fn new(current: PublicationGrant) -> Self {
        let records = [
            episode(vec![1.0], 10_000_000),
            episode(vec![3.0], 11_000_000),
        ]
        .iter()
        .enumerate()
        .map(|(offset, value)| InputRecord {
            partition: 0,
            offset: offset as i64,
            key: value.series.series_id.as_bytes().to_vec(),
            payload: wire::encode(value).unwrap(),
        })
        .collect();
        Self {
            current,
            records,
            output: Vec::new(),
            reads: 0,
            attempts: 0,
            denied: 0,
            completed: BTreeMap::new(),
            lose_response: false,
            block_commit: None,
        }
    }
}

impl Broker for AuthorityBroker {
    fn read(&mut self, run: &Admitted) -> Result<Vec<InputRecord>, String> {
        self.reads += 1;
        Ok(self
            .records
            .iter()
            .filter(|record| {
                run.cut.positions.iter().any(|position| {
                    position.partition == record.partition
                        && record.offset >= position.from
                        && record.offset < position.to
                })
            })
            .cloned()
            .collect())
    }

    fn publish(&mut self, _: &Admitted, _: &[OutputRecord]) -> Result<(), String> {
        Err("uncaptured publication is forbidden".into())
    }

    fn fenced(&self) -> bool {
        true
    }

    fn capture_grant(
        &mut self,
        run: &Admitted,
        value: Option<&PublicationGrant>,
    ) -> Result<Option<PublicationGrant>, String> {
        let value = value.ok_or("publication authority is missing")?;
        value.validate_run(run)?;
        Ok(Some(value.clone()))
    }

    fn publish_captured(
        &mut self,
        run: &Admitted,
        records: &[OutputRecord],
        value: Option<&PublicationGrant>,
    ) -> Result<(), String> {
        self.attempts += 1;
        let value = value.ok_or("captured grant is missing")?;
        value.validate_run(run)?;
        if value != &self.current {
            self.denied += 1;
            return Err("publication authority refused the captured grant or request".into());
        }
        let digest = output_digest(records)?;
        if let Some(completed) = self.completed.get(&run.request_hash) {
            if completed != &digest {
                return Err("registered output changed".into());
            }
        } else {
            self.output.extend_from_slice(records);
            self.completed.insert(run.request_hash.clone(), digest);
        }
        if let Some(path) = self.block_commit.take() {
            fs::create_dir(path).unwrap();
        }
        if std::mem::take(&mut self.lose_response) {
            return Err("publication outcome requires reconciliation".into());
        }
        Ok(())
    }
}

#[test]
fn missing_authority_is_rejected_before_input_is_read() {
    let directory = Directory::new();
    let mut worker = directory.worker();
    let mut broker = AuthorityBroker::new(grant(&installation("install-first", 1, None, false)));
    assert!(worker
        .prepare(&increment(1, None, 0, 1, false), &mut broker)
        .is_err());
    assert_eq!((broker.reads, broker.attempts), (0, 0));
}

#[test]
fn captured_grant_survives_restart_and_cannot_publish_after_external_revocation() {
    let directory = Directory::new();
    let mut worker = directory.worker();
    let first = installation("install-first", 1, None, false);
    worker.install_publication(&first.to_string()).unwrap();
    let mut broker = AuthorityBroker::new(grant(&first));
    let candidate = worker
        .prepare(&increment(1, None, 0, 1, false), &mut broker)
        .unwrap();
    let prepared_snapshot = directory.checkpoint_bytes();
    let next = installation("install-second", 2, None, false);
    broker.current = grant(&next);
    assert!(worker.install_publication(&next.to_string()).is_err());
    assert!(prepared_snapshot == directory.checkpoint_bytes());
    drop(worker);
    let mut worker = directory.worker();
    let failed = worker
        .activate(&candidate.request_hash, &mut broker)
        .unwrap();
    assert_eq!(failed.state, "failed");
    assert_eq!(failed.publication_epoch.as_deref(), Some("1"));
    assert!(failed.activation_retryable);
    assert_eq!(
        (
            broker.reads,
            broker.attempts,
            broker.denied,
            broker.output.len()
        ),
        (1, 1, 1, 0)
    );
    drop(worker);
    fs::write(directory.checkpoint(), prepared_snapshot).unwrap();
    let mut restored = directory.worker();
    assert_eq!(
        restored
            .activate(&candidate.request_hash, &mut broker)
            .unwrap()
            .state,
        "failed"
    );
    assert_eq!(
        (
            broker.reads,
            broker.attempts,
            broker.denied,
            broker.output.len()
        ),
        (1, 2, 2, 0)
    );
}

#[test]
fn resume_preserves_open_window_positions_clock_and_logical_generation() {
    let directory = Directory::new();
    let mut worker = directory.worker();
    let first = installation("install-first", 1, None, false);
    worker.install_publication(&first.to_string()).unwrap();
    let mut broker = AuthorityBroker::new(grant(&first));
    let text = increment(1, None, 0, 1, true);
    let candidate = worker.prepare(&text, &mut broker).unwrap();
    assert_eq!(candidate.prepared_output_records.as_deref(), Some("0"));
    let completed = worker
        .activate(&candidate.request_hash, &mut broker)
        .unwrap();
    let before: Value = serde_json::from_slice(&directory.checkpoint_bytes()).unwrap();
    let next = installation("install-second", 2, Some(&completed.request_hash), false);
    broker.current = grant(&next);
    worker.install_publication(&next.to_string()).unwrap();
    let after: Value = serde_json::from_slice(&directory.checkpoint_bytes()).unwrap();
    assert_eq!(before["payload"]["head"], after["payload"]["head"]);
    assert_eq!(
        worker
            .status(&completed.request_hash)
            .unwrap()
            .publication_epoch
            .as_deref(),
        Some("1")
    );
    drop(worker);
    let mut worker = directory.worker();
    let next_text = increment(2, Some(&completed.request_hash), 1, 2, true);
    let candidate = worker.prepare(&next_text, &mut broker).unwrap();
    assert_eq!(candidate.publication_epoch.as_deref(), Some("2"));
    let completed = worker
        .activate(&candidate.request_hash, &mut broker)
        .unwrap();
    let receipt = completed.receipt.unwrap();
    assert_eq!(receipt.generation, "1");
    assert_eq!(receipt.positions[0].next, "2");
    assert_eq!(receipt.output_records, 1);
    let output = wire::decode(&broker.output[0].payload, 1024, 10).unwrap();
    assert_eq!(output.values, vec![2.0]);
    assert_eq!(output.provenance.created_at, 1_700_000_000_000_000);
}

#[test]
fn duplicate_installation_never_rewinds_a_later_candidate_or_completed_head() {
    let directory = Directory::new();
    let mut worker = directory.worker();
    let first = installation("install-first", 1, None, false);
    let expected = worker.install_publication(&first.to_string()).unwrap();
    let mut broker = AuthorityBroker::new(grant(&first));
    let candidate = worker
        .prepare(&increment(1, None, 0, 1, false), &mut broker)
        .unwrap();
    let bytes = directory.checkpoint_bytes();
    assert_eq!(
        expected,
        worker.install_publication(&first.to_string()).unwrap()
    );
    assert!(bytes == directory.checkpoint_bytes());
    worker
        .activate(&candidate.request_hash, &mut broker)
        .unwrap();
    let bytes = directory.checkpoint_bytes();
    assert_eq!(
        expected,
        worker.install_publication(&first.to_string()).unwrap()
    );
    assert!(bytes == directory.checkpoint_bytes());
    assert_eq!((broker.reads, broker.attempts), (1, 1));
}

#[test]
fn conflicting_stale_and_incompatible_installations_leave_the_checkpoint_unchanged() {
    let directory = Directory::new();
    let mut worker = directory.worker();
    let first = installation("install-first", 2, None, false);
    worker.install_publication(&first.to_string()).unwrap();
    let mut conflict = first.clone();
    conflict["reset"] = json!(true);
    let mut wrong_output = installation("install-third", 3, None, false);
    wrong_output["grant"]["outputTopic"] = json!("tsx.another.v2");
    let mut wrong_node = installation("install-third", 3, None, false);
    wrong_node["grant"]["node"] = json!("worker-2");
    let bytes = directory.checkpoint_bytes();
    for value in [
        conflict,
        installation("install-old", 1, None, false),
        installation("install-equal", 2, None, false),
        installation(
            "install-third",
            3,
            Some(&tsx_contracts::sha256("other-head")),
            false,
        ),
        wrong_output,
        wrong_node,
    ] {
        assert!(worker.install_publication(&value.to_string()).is_err());
        assert!(bytes == directory.checkpoint_bytes());
    }
}

#[test]
fn reset_requires_the_completed_head_same_input_and_distinct_deployment_and_output() {
    let directory = Directory::new();
    let mut worker = directory.worker();
    let first = installation("install-first", 1, None, false);
    worker.install_publication(&first.to_string()).unwrap();
    let mut broker = AuthorityBroker::new(grant(&first));
    let candidate = worker
        .prepare(&increment(1, None, 0, 1, false), &mut broker)
        .unwrap();
    let completed = worker
        .activate(&candidate.request_hash, &mut broker)
        .unwrap();
    let mut valid = installation("install-reset", 2, Some(&completed.request_hash), true);
    valid["grant"]["deployment"] = json!("replacement-deployment");
    valid["grant"]["outputTopic"] = json!("tsx.learning.replacement.v2");
    let mut wrong_head = valid.clone();
    wrong_head["expectedHead"] = Value::Null;
    let mut same_deployment = valid.clone();
    same_deployment["grant"]["deployment"] = first["grant"]["deployment"].clone();
    let mut same_output = valid.clone();
    same_output["grant"]["outputTopic"] = first["grant"]["outputTopic"].clone();
    let mut wrong_input = valid.clone();
    wrong_input["grant"]["inputTopic"] = json!("tsx.another.input.v2");
    let bytes = directory.checkpoint_bytes();
    for invalid in [wrong_head, same_deployment, same_output, wrong_input] {
        assert!(worker.install_publication(&invalid.to_string()).is_err());
        assert!(bytes == directory.checkpoint_bytes());
    }
    broker.current = grant(&valid);
    let installed = worker.install_publication(&valid.to_string()).unwrap();
    assert_eq!(installed["publicationEpoch"], "2");
    assert!(worker.status(&completed.request_hash).is_err());
    assert!(worker
        .prepare(&increment(1, None, 0, 1, false), &mut broker)
        .is_err());
    let candidate = worker
        .prepare(&replacement(&increment(1, None, 0, 1, false)), &mut broker)
        .unwrap();
    let reset_bytes = directory.checkpoint_bytes();
    assert_eq!(
        installed,
        worker.install_publication(&valid.to_string()).unwrap()
    );
    assert!(reset_bytes == directory.checkpoint_bytes());
    let completed = worker
        .activate(&candidate.request_hash, &mut broker)
        .unwrap();
    let receipt = completed.receipt.unwrap();
    assert_eq!(receipt.deployment, "replacement-deployment");
    assert_eq!(receipt.positions[0].next, "1");
    assert_eq!(
        wire::decode(&broker.output[1].payload, 1024, 10)
            .unwrap()
            .values,
        vec![4.0]
    );
}

#[test]
fn lost_gateway_response_retries_saved_output_without_reading_or_duplicating_it() {
    let directory = Directory::new();
    let mut worker = directory.worker();
    let first = installation("install-first", 1, None, false);
    worker.install_publication(&first.to_string()).unwrap();
    let mut broker = AuthorityBroker::new(grant(&first));
    broker.lose_response = true;
    let candidate = worker
        .prepare(&increment(1, None, 0, 1, false), &mut broker)
        .unwrap();
    assert_eq!(
        worker
            .activate(&candidate.request_hash, &mut broker)
            .unwrap()
            .state,
        "failed"
    );
    drop(worker);
    let mut worker = directory.worker();
    assert_eq!(
        worker
            .activate(&candidate.request_hash, &mut broker)
            .unwrap()
            .state,
        "completed"
    );
    assert_eq!(
        (broker.reads, broker.attempts, broker.output.len()),
        (1, 2, 1)
    );
}

#[test]
fn acknowledged_publication_with_failed_local_commit_blocks_switch_until_exact_reconciliation() {
    let directory = Directory::new();
    let mut worker = directory.worker();
    let first = installation("install-first", 1, None, false);
    worker.install_publication(&first.to_string()).unwrap();
    let mut broker = AuthorityBroker::new(grant(&first));
    let candidate = worker
        .prepare(&increment(1, None, 0, 1, false), &mut broker)
        .unwrap();
    let pending = directory.0.join("increments/.checkpoint.pending");
    broker.block_commit = Some(pending.clone());
    assert!(worker
        .activate(&candidate.request_hash, &mut broker)
        .is_err());
    drop(worker);
    fs::remove_dir(pending).unwrap();
    let mut worker = directory.worker();
    let next = installation("install-next", 2, None, false);
    assert!(worker.install_publication(&next.to_string()).is_err());
    assert_eq!(
        worker
            .activate(&candidate.request_hash, &mut broker)
            .unwrap()
            .state,
        "completed"
    );
    let next = installation("install-next", 2, Some(&candidate.request_hash), false);
    worker.install_publication(&next.to_string()).unwrap();
    assert_eq!(
        (broker.reads, broker.attempts, broker.output.len()),
        (1, 2, 1)
    );
}

#[test]
fn public_status_and_receipts_never_contain_private_credentials() {
    let directory = Directory::new();
    let mut worker = directory.worker();
    let first = installation("install-first", 1, None, false);
    let installed = worker.install_publication(&first.to_string()).unwrap();
    let mut broker = AuthorityBroker::new(grant(&first));
    let prepared = worker
        .prepare(&increment(1, None, 0, 1, false), &mut broker)
        .unwrap();
    let completed = worker
        .activate(&prepared.request_hash, &mut broker)
        .unwrap();
    let credential = first["grant"]["token"].as_str().unwrap();
    for value in [
        installed,
        serde_json::to_value(prepared).unwrap(),
        serde_json::to_value(completed).unwrap(),
        worker.capabilities(),
    ] {
        let text = value.to_string();
        assert!(!text.contains(credential));
        assert!(!text.contains("token"));
    }
    let checkpoint: Value = serde_json::from_slice(&directory.checkpoint_bytes()).unwrap();
    assert!(!checkpoint["payload"]["head"]
        .to_string()
        .contains(credential));
    assert_eq!(
        fs::metadata(directory.checkpoint())
            .unwrap()
            .permissions()
            .mode()
            & 0o777,
        0o600
    );
    assert_eq!(
        fs::metadata(directory.0.join("increments"))
            .unwrap()
            .permissions()
            .mode()
            & 0o777,
        0o700
    );
}

#[test]
fn grant_boundary_rejects_unknown_coerced_and_noncanonical_fields() {
    let directory = Directory::new();
    let mut worker = directory.worker();
    let valid = installation("install-first", 1, None, false);
    for epoch in [
        json!(1),
        json!("01"),
        json!("0"),
        json!("-1"),
        json!("9223372036854775808"),
    ] {
        let mut invalid = valid.clone();
        invalid["grant"]["epoch"] = epoch;
        assert!(worker.install_publication(&invalid.to_string()).is_err());
    }
    let mut unknown = valid.clone();
    unknown["grant"]["extra"] = json!(true);
    assert!(worker.install_publication(&unknown.to_string()).is_err());
    let mut missing = valid.clone();
    missing.as_object_mut().unwrap().remove("expectedHead");
    assert!(worker.install_publication(&missing.to_string()).is_err());
    let mut unknown = valid.clone();
    unknown["extra"] = json!(false);
    assert!(worker.install_publication(&unknown.to_string()).is_err());
    assert!(worker
        .install_publication(
            "{\"grant\":null,\"grant\":null,\"reset\":false,\"expectedHead\":null}"
        )
        .is_err());
    let mut initial_reset = valid;
    initial_reset["reset"] = json!(true);
    assert!(worker
        .install_publication(&initial_reset.to_string())
        .is_err());
    assert!(!directory.checkpoint().exists());
}

#[test]
fn fenced_broker_rejects_uncaptured_publication_and_unsafe_origins_without_network_io() {
    let run =
        tsx_runner::admission::admit(&increment(1, None, 0, 0, false), ARTIFACT_DIGEST).unwrap();
    for origin in [
        "https://publisher:8080",
        "http://publisher",
        "http://publisher:0",
        "http://publisher:8080/path",
        "http://publisher:8080/?next=other",
        "http://user:password@publisher:8080",
        "http://publisher:8080#fragment",
    ] {
        assert!(HttpFencedBroker::new(origin).is_err());
    }
    let mut broker = HttpFencedBroker::new("http://publisher:8080/").unwrap();
    assert!(broker.fenced());
    assert!(broker.capture_grant(&run, None).is_err());
    assert!(broker.publish(&run, &[]).is_err());
    assert!(broker.publish_captured(&run, &[], None).is_err());
}

#[test]
fn authority_covers_the_whole_topic_with_one_two_or_three_internal_partitions() {
    for count in 1..=3 {
        let directory = Directory::new();
        let mut worker = directory.worker();
        let first = installation("install-first", 1, None, false);
        worker.install_publication(&first.to_string()).unwrap();
        let mut broker = AuthorityBroker::new(grant(&first));
        let mut value: Value = serde_json::from_str(&increment(1, None, 0, 1, false)).unwrap();
        let mut assignment: Value =
            serde_json::from_str(value["assignmentText"].as_str().unwrap()).unwrap();
        assignment["inputs"][0]["partitionCount"] = json!(count.to_string());
        let positions: Vec<_> = (0..count)
            .map(|partition| {
                json!({"partition":partition.to_string(),
            "from":"0","to":if partition == 0 {"1"} else {"0"}})
            })
            .collect();
        assignment["inputs"][0]["positions"] = json!(positions);
        value["assignmentText"] = json!(assignment.to_string());
        if count > 1 {
            let mut incomplete = value.clone();
            assignment["inputs"][0]["positions"]
                .as_array_mut()
                .unwrap()
                .pop();
            incomplete["assignmentText"] = json!(assignment.to_string());
            assert!(worker
                .prepare(&incomplete.to_string(), &mut broker)
                .is_err());
            assert_eq!(broker.reads, 0);
        }
        let candidate = worker.prepare(&value.to_string(), &mut broker).unwrap();
        assert_eq!(candidate.input_topic, INPUT_TOPIC);
        let completed = worker
            .activate(&candidate.request_hash, &mut broker)
            .unwrap();
        assert_eq!(completed.receipt.unwrap().positions.len(), count);
        assert_eq!(
            (broker.reads, broker.attempts, broker.output.len()),
            (1, 1, 1)
        );
    }
}

#[test]
fn changing_the_installed_secret_cannot_upgrade_an_existing_prepared_candidate() {
    let directory = Directory::new();
    let mut worker = directory.worker();
    let first = installation("install-first", 1, None, false);
    worker.install_publication(&first.to_string()).unwrap();
    let mut broker = AuthorityBroker::new(grant(&first));
    worker
        .prepare(&increment(1, None, 0, 1, false), &mut broker)
        .unwrap();
    drop(worker);
    let mut envelope: Value = serde_json::from_slice(&directory.checkpoint_bytes()).unwrap();
    envelope["payload"]["publication"]["grant"] =
        installation("install-next", 2, None, false)["grant"].clone();
    envelope["checksum"] = json!(tsx_contracts::sha256_bytes(
        &serde_json::to_vec(&envelope["payload"]).unwrap()
    ));
    fs::write(
        directory.checkpoint(),
        serde_json::to_vec(&envelope).unwrap(),
    )
    .unwrap();
    assert!(Worker::open(Config {
        node: "worker-1".into(),
        site: "local".into(),
        bootstrap: "localhost:9092".into(),
        artifact_digest: ARTIFACT_DIGEST.into(),
        state: directory.0.clone()
    })
    .is_err());
    assert_eq!(
        (broker.reads, broker.attempts, broker.output.len()),
        (1, 0, 0)
    );
}

#[test]
fn forged_current_epoch_with_an_old_secret_is_denied_before_publication() {
    let first = installation("install-first", 1, None, false);
    let next = installation("install-next", 2, None, false);
    let mut broker = AuthorityBroker::new(grant(&next));
    let mut forged = grant(&first);
    forged.epoch = "2".into();
    let run =
        tsx_runner::admission::admit(&increment(1, None, 0, 0, false), ARTIFACT_DIGEST).unwrap();
    assert!(broker.publish_captured(&run, &[], Some(&forged)).is_err());
    assert_eq!(
        (
            broker.reads,
            broker.attempts,
            broker.denied,
            broker.output.len()
        ),
        (0, 1, 1, 0)
    );
}

#[test]
fn bounded_fenced_execution_uses_saved_authority_and_keeps_the_completed_epoch() {
    let directory = Directory::new();
    let mut worker = directory.worker();
    assert_eq!(
        worker.publication_status().unwrap(),
        json!({"publicationEpoch":null,"deployment":null,"operation":null})
    );
    let first = installation("install-first", 1, None, false);
    worker.install_publication(&first.to_string()).unwrap();
    let mut broker = AuthorityBroker::new(grant(&first));
    let mut bounded: Value = serde_json::from_str(&increment(1, None, 0, 1, false)).unwrap();
    bounded["schema"] = json!("opentsx.local-bounded-run.v1");
    bounded.as_object_mut().unwrap().remove("sequence");
    bounded
        .as_object_mut()
        .unwrap()
        .remove("previousRequestHash");
    let candidate = worker.prepare(&bounded.to_string(), &mut broker).unwrap();
    assert!(worker
        .install_publication(&installation("install-next", 2, None, false).to_string())
        .is_err());
    drop(worker);
    let mut worker = directory.worker();
    let completed = worker
        .activate(&candidate.request_hash, &mut broker)
        .unwrap();
    assert_eq!(completed.state, "completed");
    let next = installation("install-next", 2, None, false);
    worker.install_publication(&next.to_string()).unwrap();
    assert_eq!(
        worker.publication_status().unwrap(),
        json!({"publicationEpoch":"2",
        "deployment":"learning-deployment","operation":"install-next"})
    );
    assert_eq!(
        worker
            .status(&completed.request_hash)
            .unwrap()
            .publication_epoch
            .as_deref(),
        Some("1")
    );
    let reads = broker.reads;
    let attempts = broker.attempts;
    assert_eq!(
        worker
            .activate(&completed.request_hash, &mut broker)
            .unwrap(),
        completed
    );
    assert_eq!((broker.reads, broker.attempts), (reads, attempts));
}

#[test]
fn fenced_cli_requires_paired_options_and_rejects_unsafe_configuration_before_state_creation() {
    use std::process::Command;
    let directory = Directory::new();
    let state = directory.0.join("never-created");
    let base = [
        "serve",
        "--listen",
        "127.0.0.1:0",
        "--node",
        "worker-1",
        "--site",
        "local",
        "--bootstrap",
        "localhost:9092",
        "--state",
        state.to_str().unwrap(),
    ];
    for extra in [
        vec!["--publisher", "http://publisher:8080"],
        vec!["--controller", "172.31.0.10"],
        vec![
            "--publisher",
            "http://publisher:8080/path",
            "--controller",
            "172.31.0.10",
        ],
        vec![
            "--publisher",
            "http://publisher:8080",
            "--controller",
            "0.0.0.0",
        ],
        vec![
            "--publisher",
            "http://publisher:8080",
            "--controller",
            "controller-host",
        ],
    ] {
        let output = Command::new(env!("CARGO_BIN_EXE_tsx-runner"))
            .args(base)
            .args(extra)
            .output()
            .unwrap();
        assert_eq!(output.status.code(), Some(2));
        assert!(!state.exists());
    }
    let help = Command::new(env!("CARGO_BIN_EXE_tsx-runner"))
        .arg("--help")
        .output()
        .unwrap();
    assert!(help.status.success());
    let help = String::from_utf8(help.stdout).unwrap();
    assert!(help.contains("--publisher <http-origin> --controller <IP>"));
}

#[test]
fn new_authority_discards_only_an_explicitly_cancelled_candidate_and_never_a_failed_publication() {
    let directory = Directory::new();
    let mut worker = directory.worker();
    let first = installation("install-first", 1, None, false);
    worker.install_publication(&first.to_string()).unwrap();
    let mut broker = AuthorityBroker::new(grant(&first));
    let text = increment(1, None, 0, 1, false);
    let candidate = worker.prepare(&text, &mut broker).unwrap();
    let next = installation("install-next", 2, None, false);
    assert!(worker.install_publication(&next.to_string()).is_err());
    assert_eq!(
        worker.abort(&candidate.request_hash).unwrap().state,
        "cancelled"
    );
    assert_eq!(
        worker.status(&candidate.request_hash).unwrap().state,
        "cancelled"
    );
    worker.install_publication(&next.to_string()).unwrap();
    broker.current = grant(&next);
    let candidate = worker.prepare(&text, &mut broker).unwrap();
    assert_eq!(candidate.publication_epoch.as_deref(), Some("2"));
    let third = installation("install-third", 3, None, false);
    broker.current = grant(&third);
    let failed = worker
        .activate(&candidate.request_hash, &mut broker)
        .unwrap();
    assert_eq!(failed.state, "failed");
    assert!(failed.activation_retryable);
    let bytes = directory.checkpoint_bytes();
    assert!(worker.install_publication(&third.to_string()).is_err());
    assert!(bytes == directory.checkpoint_bytes());
    assert_eq!(
        (broker.reads, broker.attempts, broker.output.len()),
        (2, 1, 0)
    );
}
