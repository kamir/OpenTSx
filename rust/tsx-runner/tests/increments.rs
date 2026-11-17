mod common;

use common::{episode, request, ARTIFACT_DIGEST};
use serde_json::{json, Value};
use std::fs;
use std::path::PathBuf;
use std::sync::atomic::{AtomicU64, Ordering};
use tsx_runner::admission::Admitted;
use tsx_runner::broker::{Broker, InputRecord, OutputRecord};
use tsx_runner::wire;
use tsx_runner::worker::{Config, Worker};

static NEXT: AtomicU64 = AtomicU64::new(0);

struct Directory(PathBuf);

impl Directory {
    fn new() -> Self {
        let path = std::env::temp_dir().canonicalize().unwrap().join(format!(
            "tsx-increment-{}-{}",
            std::process::id(),
            NEXT.fetch_add(1, Ordering::SeqCst)
        ));
        fs::create_dir(&path).unwrap();
        Self(path)
    }

    fn config(&self) -> Config {
        Config {
            node: "worker-1".into(),
            site: "local".into(),
            bootstrap: "localhost:9092".into(),
            artifact_digest: ARTIFACT_DIGEST.into(),
            state: self.0.clone(),
        }
    }

    fn worker(&self) -> Worker {
        Worker::open(self.config()).unwrap()
    }
}

impl Drop for Directory {
    fn drop(&mut self) {
        fs::remove_dir_all(&self.0).unwrap();
    }
}

#[derive(Default)]
struct FakeBroker {
    records: Vec<InputRecord>,
    output: Vec<OutputRecord>,
    reads: usize,
    sends: usize,
    fail_ack: bool,
    block_commit: Option<PathBuf>,
}

impl FakeBroker {
    fn with_records() -> Self {
        Self {
            records: [
                episode(vec![1.0], 10_000_000),
                episode(vec![3.0], 11_000_000),
            ]
            .iter()
            .enumerate()
            .map(|(offset, e)| InputRecord {
                partition: 0,
                offset: offset as i64,
                key: e.series.series_id.as_bytes().to_vec(),
                payload: wire::encode(e).unwrap(),
            })
            .collect(),
            ..Self::default()
        }
    }
}

impl Broker for FakeBroker {
    fn read(&mut self, run: &Admitted) -> Result<Vec<InputRecord>, String> {
        self.reads += 1;
        Ok(self
            .records
            .iter()
            .filter(|r| {
                run.cut
                    .positions
                    .iter()
                    .any(|p| p.partition == r.partition && r.offset >= p.from && r.offset < p.to)
            })
            .cloned()
            .collect())
    }

    fn publish(&mut self, _: &Admitted, records: &[OutputRecord]) -> Result<(), String> {
        self.sends += 1;
        self.output.extend_from_slice(records);
        if let Some(path) = &self.block_commit {
            fs::create_dir(path).unwrap();
        }
        if self.fail_ack {
            Err("delivery acknowledgement was lost".into())
        } else {
            Ok(())
        }
    }
}

fn increment(sequence: i64, previous: Option<&str>, from: i64, to: i64, resample: bool) -> String {
    let mut request = request();
    request["schema"] = json!("opentsx.local-increment.v1");
    request["sequence"] = json!(sequence.to_string());
    request["previousRequestHash"] = json!(previous);
    let mut assignment: Value =
        serde_json::from_str(request["assignmentText"].as_str().unwrap()).unwrap();
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
    request["assignmentText"] = json!(assignment.to_string());
    request.to_string()
}

#[test]
fn incremental_windows_continue_after_restart_and_latest_retry_has_no_broker_io() {
    let directory = Directory::new();
    let mut worker = directory.worker();
    let mut broker = FakeBroker::with_records();
    assert_eq!(
        worker.capabilities()["incrementalRequestSchema"],
        "opentsx.local-increment.v1"
    );
    assert_eq!(worker.capabilities()["limits"]["incrementalChains"], 1);
    let first_text = increment(1, None, 0, 1, true);
    let first = worker.prepare(&first_text, &mut broker).unwrap();
    assert_eq!(first.state, "prepared");
    let first_status = serde_json::to_value(&first).unwrap();
    assert_eq!(first_status["sequence"], "1");
    assert!(first_status["previousRequestHash"].is_null());
    assert_eq!(first.output_series.len(), 1);
    let first = worker.activate(&first.request_hash, &mut broker).unwrap();
    assert_eq!(first.receipt.unwrap().output_records, 0);
    drop(worker);
    let mut worker = directory.worker();
    let next_text = increment(2, Some(&first.request_hash), 1, 2, true);
    let next = worker.prepare(&next_text, &mut broker).unwrap();
    assert_eq!(next.state, "prepared");
    let next_status = serde_json::to_value(&next).unwrap();
    assert_eq!(next_status["sequence"], "2");
    assert_eq!(next_status["previousRequestHash"], first.request_hash);
    let completed = worker.activate(&next.request_hash, &mut broker).unwrap();
    assert_eq!(completed.receipt.as_ref().unwrap().positions[0].next, "2");
    assert_eq!(
        wire::decode(&broker.output[0].payload, 1024, 10)
            .unwrap()
            .values,
        vec![2.0]
    );
    assert_eq!(
        wire::decode(&broker.output[0].payload, 1024, 10)
            .unwrap()
            .provenance
            .created_at,
        1_700_000_000_000_000
    );
    let counts = (broker.reads, broker.sends);
    assert_eq!(worker.prepare(&next_text, &mut broker).unwrap(), completed);
    assert!(worker.prepare(&first_text, &mut broker).is_err());
    assert_eq!((broker.reads, broker.sends), counts);
}

#[test]
fn successor_cas_rejects_forks_skips_gaps_and_clock_changes_without_input_reads() {
    let directory = Directory::new();
    let mut worker = directory.worker();
    let mut broker = FakeBroker::with_records();
    let first = worker
        .prepare(&increment(1, None, 0, 1, false), &mut broker)
        .unwrap();
    worker.activate(&first.request_hash, &mut broker).unwrap();
    let mut changed_clock: Value =
        serde_json::from_str(&increment(2, Some(&first.request_hash), 1, 2, false)).unwrap();
    changed_clock["createdAtMicros"] = json!("1700000000000001");
    for text in [
        increment(2, Some(&"a".repeat(64)), 1, 2, false),
        increment(3, Some(&first.request_hash), 1, 2, false),
        increment(2, Some(&first.request_hash), 0, 2, false),
        changed_clock.to_string(),
    ] {
        assert!(worker.prepare(&text, &mut broker).is_err());
    }
    assert_eq!((broker.reads, broker.sends), (1, 1));
}

#[test]
fn uncertain_increment_retries_the_saved_candidate_after_restart_and_blocks_successors() {
    let directory = Directory::new();
    let mut worker = directory.worker();
    let mut broker = FakeBroker {
        fail_ack: true,
        ..FakeBroker::with_records()
    };
    let text = increment(1, None, 0, 1, false);
    let first = worker.prepare(&text, &mut broker).unwrap();
    let failed = worker.activate(&first.request_hash, &mut broker).unwrap();
    assert_eq!(failed.state, "failed");
    assert!(failed.activation_retryable);
    assert!(worker.abort(&first.request_hash).is_err());
    drop(worker);
    let mut worker = directory.worker();
    assert!(worker
        .prepare(
            &increment(2, Some(&first.request_hash), 1, 2, false),
            &mut broker
        )
        .is_err());
    broker.fail_ack = false;
    let completed = worker.activate(&first.request_hash, &mut broker).unwrap();
    assert_eq!(completed.state, "completed");
    assert_eq!((broker.reads, broker.sends), (1, 2));
    assert_eq!(broker.output[0].payload, broker.output[1].payload);
}

fn request_with_partitions(text: &str, positions: Value) -> String {
    let mut request: Value = serde_json::from_str(text).unwrap();
    let mut assignment: Value =
        serde_json::from_str(request["assignmentText"].as_str().unwrap()).unwrap();
    assignment["inputs"][0]["partitionCount"] =
        json!(positions.as_array().unwrap().len().to_string());
    assignment["inputs"][0]["positions"] = positions;
    request["assignmentText"] = json!(assignment.to_string());
    request.to_string()
}

#[test]
fn cumulative_partition_ownership_rejects_a_series_move_in_a_later_increment() {
    let directory = Directory::new();
    let mut worker = directory.worker();
    let mut broker = FakeBroker::with_records();
    broker.records[1].partition = 1;
    broker.records[1].offset = 0;
    let first_text = request_with_partitions(
        &increment(1, None, 0, 1, false),
        json!([
        {"partition":"0","from":"0","to":"1"},{"partition":"1","from":"0","to":"0"}]),
    );
    let first = worker.prepare(&first_text, &mut broker).unwrap();
    worker.activate(&first.request_hash, &mut broker).unwrap();
    drop(worker);
    let mut worker = directory.worker();
    let next_text = request_with_partitions(
        &increment(2, Some(&first.request_hash), 1, 1, false),
        json!([
        {"partition":"0","from":"1","to":"1"},{"partition":"1","from":"0","to":"1"}]),
    );
    let failed = worker.prepare(&next_text, &mut broker).unwrap();
    assert_eq!(failed.state, "failed");
    assert!(failed
        .error
        .unwrap()
        .contains("multiple internal partitions"));
    assert!(!failed.activation_retryable);
    assert_eq!(broker.sends, 1);
}

fn pass_through(text: &str, all_series: bool) -> String {
    let mut request: Value = serde_json::from_str(text).unwrap();
    request["sink"]["name"] = json!("decode");
    let mut assignment: Value =
        serde_json::from_str(request["assignmentText"].as_str().unwrap()).unwrap();
    let mut fragment: Value =
        serde_json::from_str(assignment["fragmentText"].as_str().unwrap()).unwrap();
    fragment["nodes"] = json!([{"id":"decode","op":"decode.avro-soe"}]);
    fragment["sinks"][0]["from"] = json!("decode");
    if all_series {
        fragment["source"]["series"] = json!([]);
    }
    let fragment = fragment.to_string();
    assignment["fragmentText"] = json!(fragment);
    assignment["fragmentHash"] = json!(tsx_contracts::sha256(&fragment));
    request["assignmentText"] = json!(assignment.to_string());
    request.to_string()
}

#[test]
fn idle_pass_through_increment_retains_all_prior_output_owners() {
    let directory = Directory::new();
    let mut worker = directory.worker();
    let mut broker = FakeBroker::with_records();
    let first = worker
        .prepare(
            &pass_through(&increment(1, None, 0, 1, false), true),
            &mut broker,
        )
        .unwrap();
    worker.activate(&first.request_hash, &mut broker).unwrap();
    let next = worker
        .prepare(
            &pass_through(&increment(2, Some(&first.request_hash), 1, 1, false), true),
            &mut broker,
        )
        .unwrap();
    assert_eq!(next.input_series, first.input_series);
    assert_eq!(next.output_series, first.output_series);
    assert_eq!(
        worker
            .activate(&next.request_hash, &mut broker)
            .unwrap()
            .receipt
            .unwrap()
            .output_records,
        0
    );
}

#[test]
fn unselected_series_still_count_toward_the_lifetime_ownership_cap() {
    let directory = Directory::new();
    let mut worker = directory.worker();
    let mut broker = FakeBroker::with_records();
    let mut other = episode(vec![3.0], 11_000_000);
    other.series.tags.insert("sensor".into(), "two".into());
    other.series.series_id =
        tsx_runner::model::series_id(&other.series.metric, &other.series.tags).unwrap();
    broker.records[1].key = other.series.series_id.as_bytes().to_vec();
    broker.records[1].payload = wire::encode(&other).unwrap();
    let cap = |text: String| {
        let mut request: Value = serde_json::from_str(&text).unwrap();
        request["limits"]["series"] = json!(1);
        request.to_string()
    };
    let first = worker
        .prepare(&cap(increment(1, None, 0, 1, false)), &mut broker)
        .unwrap();
    worker.activate(&first.request_hash, &mut broker).unwrap();
    let next = worker
        .prepare(
            &cap(increment(2, Some(&first.request_hash), 1, 2, false)),
            &mut broker,
        )
        .unwrap();
    assert_eq!(next.state, "failed");
    assert!(next.error.unwrap().contains("input series limit"));
    assert_eq!(broker.sends, 1);
}

#[test]
fn acknowledgement_before_failed_head_commit_replays_the_identical_candidate() {
    let directory = Directory::new();
    let mut worker = directory.worker();
    let mut broker = FakeBroker::with_records();
    let text = increment(1, None, 0, 1, false);
    let first = worker.prepare(&text, &mut broker).unwrap();
    let pending = directory.0.join("increments/.checkpoint.pending");
    broker.block_commit = Some(pending.clone());
    assert!(worker.activate(&first.request_hash, &mut broker).is_err());
    assert!(
        worker
            .status(&first.request_hash)
            .unwrap()
            .activation_retryable
    );
    drop(worker);
    fs::remove_dir(pending).unwrap();
    broker.block_commit = None;
    let mut worker = directory.worker();
    assert_eq!(
        worker
            .activate(&first.request_hash, &mut broker)
            .unwrap()
            .state,
        "completed"
    );
    assert_eq!((broker.reads, broker.sends), (1, 2));
    assert_eq!(broker.output[0].payload, broker.output[1].payload);
}

#[test]
fn completed_increment_history_uses_one_checkpoint_and_no_output_payload_history() {
    let directory = Directory::new();
    let mut worker = directory.worker();
    let mut broker = FakeBroker::with_records();
    let mut previous = None;
    for sequence in 1..=24 {
        let text = increment(
            sequence,
            previous.as_deref(),
            if sequence == 1 { 0 } else { 1 },
            1,
            false,
        );
        let candidate = worker.prepare(&text, &mut broker).unwrap();
        worker
            .activate(&candidate.request_hash, &mut broker)
            .unwrap();
        previous = Some(candidate.request_hash);
    }
    let files = fs::read_dir(directory.0.join("increments"))
        .unwrap()
        .collect::<Result<Vec<_>, _>>()
        .unwrap();
    assert_eq!(files.len(), 1);
    assert_eq!(files[0].file_name(), "checkpoint.json");
    let bytes = fs::read(files[0].path()).unwrap();
    assert!(bytes.len() < 32_768);
    let checkpoint: Value = serde_json::from_slice(&bytes).unwrap();
    assert!(checkpoint["payload"]["candidate"].is_null());
    assert!(checkpoint["payload"]["head"].get("records").is_none());
    assert_eq!(
        checkpoint["payload"]["head"]["inputSeries"]
            .as_array()
            .unwrap()
            .len(),
        1
    );
}

#[test]
fn increment_admission_refuses_missing_coerced_and_noncanonical_predecessors() {
    let directory = Directory::new();
    let mut worker = directory.worker();
    let mut broker = FakeBroker::with_records();
    let valid: Value = serde_json::from_str(&increment(1, None, 0, 1, false)).unwrap();
    for sequence in [
        json!(1),
        json!("01"),
        json!("0"),
        json!("-1"),
        json!("9223372036854775808"),
        Value::Null,
    ] {
        let mut request = valid.clone();
        request["sequence"] = sequence;
        assert!(worker.prepare(&request.to_string(), &mut broker).is_err());
    }
    let mut missing = valid.clone();
    missing
        .as_object_mut()
        .unwrap()
        .remove("previousRequestHash");
    assert!(worker.prepare(&missing.to_string(), &mut broker).is_err());
    let mut invalid = valid;
    invalid["previousRequestHash"] = json!("A".repeat(64));
    assert!(worker.prepare(&invalid.to_string(), &mut broker).is_err());
    assert!(worker
        .prepare(&increment(1, None, 1, 1, false), &mut broker)
        .is_err());
    assert!(worker
        .prepare(&increment(2, None, 0, 1, false), &mut broker)
        .is_err());
    let mut extra: Value = serde_json::from_str(&increment(1, None, 0, 1, false)).unwrap();
    extra["state"] = json!({});
    assert!(worker.prepare(&extra.to_string(), &mut broker).is_err());
    assert_eq!((broker.reads, broker.sends), (0, 0));
}

#[test]
fn legacy_replay_cannot_bypass_the_increment_predecessor_checkpoint() {
    let directory = Directory::new();
    let mut broker = FakeBroker::with_records();
    let run =
        tsx_runner::admission::admit(&increment(1, None, 0, 1, false), ARTIFACT_DIGEST).unwrap();
    assert!(
        tsx_runner::runner::execute(run, &mut broker, &directory.0.join("legacy.json")).is_err()
    );
    assert_eq!((broker.reads, broker.sends), (0, 0));
    assert!(!directory.0.join("legacy.json").exists());
}

#[test]
fn corrupt_increment_head_or_candidate_is_refused_on_restart() {
    for completed in [false, true] {
        let directory = Directory::new();
        let mut worker = directory.worker();
        let mut broker = FakeBroker::with_records();
        let candidate = worker
            .prepare(&increment(1, None, 0, 1, false), &mut broker)
            .unwrap();
        if completed {
            worker
                .activate(&candidate.request_hash, &mut broker)
                .unwrap();
        }
        drop(worker);
        fs::write(directory.0.join("increments/checkpoint.json"), b"{}").unwrap();
        assert!(Worker::open(directory.config()).is_err());
        assert_eq!(broker.sends, usize::from(completed));
    }
}

#[cfg(unix)]
#[test]
fn increment_checkpoint_links_cannot_read_or_overwrite_another_file() {
    use std::os::unix::fs::symlink;
    let directory = Directory::new();
    let mut worker = directory.worker();
    let mut broker = FakeBroker::with_records();
    worker
        .prepare(&increment(1, None, 0, 1, false), &mut broker)
        .unwrap();
    drop(worker);
    let checkpoint = directory.0.join("increments/checkpoint.json");
    fs::remove_file(&checkpoint).unwrap();
    let target = directory.0.join("outside.txt");
    fs::write(&target, b"preserve this file").unwrap();
    symlink(&target, checkpoint).unwrap();
    assert!(Worker::open(directory.config()).is_err());
    assert_eq!(fs::read(target).unwrap(), b"preserve this file");
    assert_eq!(broker.sends, 0);
}
