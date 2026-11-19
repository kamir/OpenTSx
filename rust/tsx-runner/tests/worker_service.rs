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

#[test]
fn capability_discovery_preserves_legacy_admission_and_reports_b2_separately() {
    let directory = Directory::new();
    let worker = Worker::open(directory.config()).unwrap();
    let capabilities = worker.capabilities();
    assert_eq!(
        capabilities["operators"],
        json!(["decode.avro-soe", "dq.checks", "convert.unit", "resample"])
    );
    let mut catalog = vec!["decode.avro-soe"];
    catalog.extend_from_slice(tsx_runner::operators::SUPPORTED_OPS);
    catalog.extend_from_slice(tsx_runner::catalog::CATALOG_OPS);
    assert_eq!(catalog.len(), 15);
    assert_eq!(
        capabilities["catalogExecution"]["operators"],
        json!(catalog)
    );
    assert_eq!(capabilities["limits"]["series"], 64);
    assert_eq!(capabilities["catalogExecution"]["limits"]["series"], 1024);
    assert_eq!(capabilities["stateAbi"], "opentsx.rust.clean-state.v1");
    assert_eq!(
        capabilities["catalogExecution"]["stateAbi"],
        "opentsx.rust.catalog-state.v1"
    );
}

struct Directory(PathBuf);

impl Directory {
    fn new() -> Self {
        let path = std::env::temp_dir().canonicalize().unwrap().join(format!(
            "tsx-worker-{}-{}",
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
}

impl Drop for Directory {
    fn drop(&mut self) {
        fs::remove_dir_all(&self.0).unwrap();
    }
}

#[derive(Default)]
struct FakeBroker {
    reads: usize,
    sends: usize,
    fail: bool,
    fail_read: bool,
    input: Option<Vec<InputRecord>>,
    output: Vec<OutputRecord>,
}

impl Broker for FakeBroker {
    fn read(&mut self, _: &Admitted) -> Result<Vec<InputRecord>, String> {
        self.reads += 1;
        if self.fail_read {
            return Err("source cut is unavailable".into());
        }
        if let Some(records) = &self.input {
            return Ok(records.clone());
        }
        Ok(vec![InputRecord {
            partition: 0,
            offset: 0,
            key: episode(vec![1.0], 10_000_000).series.series_id.into_bytes(),
            payload: wire::encode(&episode(vec![1.0], 10_000_000)).unwrap(),
        }])
    }

    fn publish(&mut self, _: &Admitted, records: &[OutputRecord]) -> Result<(), String> {
        self.sends += 1;
        self.output.extend_from_slice(records);
        if self.fail {
            Err("lost acknowledgement".into())
        } else {
            Ok(())
        }
    }
}

fn text() -> String {
    let mut r = request();
    let mut a: Value = serde_json::from_str(r["assignmentText"].as_str().unwrap()).unwrap();
    a["inputs"][0]["partitionCount"] = json!("1");
    a["inputs"][0]["positions"] = json!([{"partition":"0","from":"0","to":"1"}]);
    r["assignmentText"] = json!(a.to_string());
    r.to_string()
}

#[test]
fn prepare_is_private_and_completed_retry_survives_restart_without_broker_io() {
    let dir = Directory::new();
    let mut worker = Worker::open(dir.config()).unwrap();
    let mut broker = FakeBroker::default();
    let status = worker.prepare(&text(), &mut broker).unwrap();
    assert_eq!(status.state, "prepared");
    assert_eq!(
        serde_json::to_value(&status).unwrap()["activationRetryable"],
        false
    );
    assert_eq!((broker.reads, broker.sends), (1, 0));
    assert_eq!(status.input_series.len(), 1);
    assert_eq!(status.output_series.len(), 1);
    let completed = worker.activate(&status.request_hash, &mut broker).unwrap();
    assert_eq!(completed.state, "completed");
    assert_eq!(
        serde_json::to_value(&completed).unwrap()["activationRetryable"],
        false
    );
    assert!(completed.receipt.is_some());
    assert_eq!((broker.reads, broker.sends), (1, 1));
    drop(worker);
    let mut worker = Worker::open(dir.config()).unwrap();
    let retry = worker.prepare(&text(), &mut broker).unwrap();
    assert_eq!(retry, completed);
    assert_eq!(
        worker.activate(&status.request_hash, &mut broker).unwrap(),
        completed
    );
    assert_eq!((broker.reads, broker.sends), (1, 1));
}

#[test]
fn lost_ack_replays_saved_bytes_without_reading_source_and_cannot_abort() {
    let dir = Directory::new();
    let mut worker = Worker::open(dir.config()).unwrap();
    let mut broker = FakeBroker {
        fail: true,
        ..FakeBroker::default()
    };
    let prepared = worker.prepare(&text(), &mut broker).unwrap();
    let failed = worker
        .activate(&prepared.request_hash, &mut broker)
        .unwrap();
    assert_eq!(failed.state, "failed");
    assert_eq!(
        serde_json::to_value(&failed).unwrap()["activationRetryable"],
        true
    );
    assert!(worker.abort(&prepared.request_hash).is_err());
    drop(worker);
    let mut worker = Worker::open(dir.config()).unwrap();
    broker.fail = false;
    assert_eq!(
        serde_json::to_value(worker.status(&prepared.request_hash).unwrap()).unwrap()
            ["activationRetryable"],
        true
    );
    assert_eq!(
        worker
            .activate(&prepared.request_hash, &mut broker)
            .unwrap()
            .state,
        "completed"
    );
    assert_eq!((broker.reads, broker.sends), (1, 2));
    assert_eq!(broker.output[0].payload, broker.output[1].payload);
    assert_eq!(broker.output[0].timestamp, broker.output[1].timestamp);
}

#[test]
fn abort_and_busy_are_durable_and_never_publish() {
    let dir = Directory::new();
    let mut worker = Worker::open(dir.config()).unwrap();
    let mut broker = FakeBroker::default();
    let prepared = worker.prepare(&text(), &mut broker).unwrap();
    assert!(worker.prepare(&(text() + " "), &mut broker).is_err());
    assert_eq!(
        worker.abort(&prepared.request_hash).unwrap().state,
        "cancelled"
    );
    drop(worker);
    let mut worker = Worker::open(dir.config()).unwrap();
    assert!(worker
        .activate(&prepared.request_hash, &mut broker)
        .is_err());
    assert_eq!(
        worker.abort(&prepared.request_hash).unwrap().state,
        "cancelled"
    );
    assert_eq!((broker.reads, broker.sends), (1, 0));
}

#[test]
fn worker_identity_bootstrap_lock_and_paths_fail_closed() {
    let dir = Directory::new();
    let mut worker = Worker::open(dir.config()).unwrap();
    assert!(Worker::open(dir.config()).is_err());
    let mut broker = FakeBroker::default();
    let mut r: Value = serde_json::from_str(&text()).unwrap();
    r["bindings"]["local"]["bootstrap"] = json!("localhost:9093");
    assert!(worker.prepare(&r.to_string(), &mut broker).is_err());
    assert!(worker.status("../outside").is_err());
    assert_eq!((broker.reads, broker.sends), (0, 0));
}

#[test]
fn corrupt_prepared_bytes_cannot_publish_after_restart() {
    let dir = Directory::new();
    let mut worker = Worker::open(dir.config()).unwrap();
    let mut broker = FakeBroker::default();
    let prepared = worker.prepare(&text(), &mut broker).unwrap();
    drop(worker);
    fs::write(
        dir.0.join(&prepared.request_hash).join("prepared.json"),
        b"{}",
    )
    .unwrap();
    assert!(Worker::open(dir.config()).is_err());
    assert_eq!(broker.sends, 0);
}

#[test]
fn ordinary_bodyless_get_and_http_limits_do_not_require_network() {
    use tiny_http::{Header, Method, TestRequest};
    let dir = Directory::new();
    let mut worker = Worker::open(dir.config()).unwrap();
    let mut broker = FakeBroker::default();
    let mut request = TestRequest::new().with_path("/v1/capabilities").into();
    let (status, body) = worker.handle(&mut request, &mut broker);
    assert_eq!(status, 200);
    assert_eq!(body["schema"], "opentsx.local-worker.v1");
    assert_eq!(body["limits"]["requestBytes"], 65536);
    let mut oversized = TestRequest::new()
        .with_method(Method::Post)
        .with_path("/v1/prepare")
        .with_header(Header::from_bytes("Content-Length", "65537").unwrap())
        .into();
    assert_eq!(worker.handle(&mut oversized, &mut broker).0, 413);
    let mut duplicate = TestRequest::new()
        .with_method(Method::Post)
        .with_path("/v1/prepare")
        .with_header(Header::from_bytes("Content-Length", "0").unwrap())
        .with_header(Header::from_bytes("Content-Length", "0").unwrap())
        .into();
    assert_eq!(worker.handle(&mut duplicate, &mut broker).0, 400);
    let mut unknown = TestRequest::new().with_path("/v1/jobs/../outside").into();
    assert_eq!(worker.handle(&mut unknown, &mut broker).0, 404);
    assert_eq!((broker.reads, broker.sends), (0, 0));
}

#[test]
fn failed_preparation_is_durable_and_cannot_activate_or_publish() {
    let dir = Directory::new();
    let mut worker = Worker::open(dir.config()).unwrap();
    let mut broker = FakeBroker {
        fail_read: true,
        ..FakeBroker::default()
    };
    let status = worker.prepare(&text(), &mut broker).unwrap();
    assert_eq!(status.state, "failed");
    assert_eq!(
        serde_json::to_value(&status).unwrap()["activationRetryable"],
        false
    );
    assert_eq!(status.error.as_deref(), Some("source cut is unavailable"));
    drop(worker);
    let mut worker = Worker::open(dir.config()).unwrap();
    assert_eq!(worker.prepare(&text(), &mut broker).unwrap(), status);
    assert!(worker.activate(&status.request_hash, &mut broker).is_err());
    assert_eq!((broker.reads, broker.sends), (1, 0));
}

fn rewrite_journal(dir: &Directory, id: &str, edit: impl FnOnce(&mut Value)) {
    let path = dir.0.join(id).join("prepared.json");
    let mut value: Value = serde_json::from_slice(&fs::read(&path).unwrap()).unwrap();
    edit(&mut value["payload"]);
    value["checksum"] = json!(tsx_contracts::sha256_bytes(
        &serde_json::to_vec(&value["payload"]).unwrap()
    ));
    fs::write(path, serde_json::to_vec(&value).unwrap()).unwrap();
}

#[test]
fn interrupted_activation_reconciles_authoritative_checkpoint_without_another_send() {
    let dir = Directory::new();
    let mut worker = Worker::open(dir.config()).unwrap();
    let mut broker = FakeBroker::default();
    let status = worker.prepare(&text(), &mut broker).unwrap();
    worker.activate(&status.request_hash, &mut broker).unwrap();
    drop(worker);
    rewrite_journal(&dir, &status.request_hash, |payload| {
        payload["phase"] = json!("publishing")
    });
    let mut worker = Worker::open(dir.config()).unwrap();
    let recovered = worker.prepare(&text(), &mut broker).unwrap();
    assert_eq!(recovered.state, "completed");
    assert_eq!(
        serde_json::to_value(&recovered).unwrap()["activationRetryable"],
        false
    );
    assert!(worker.abort(&status.request_hash).is_err());
    assert_eq!(
        worker
            .activate(&status.request_hash, &mut broker)
            .unwrap()
            .state,
        "completed"
    );
    assert_eq!((broker.reads, broker.sends), (1, 1));
    assert_eq!(
        worker.prepare(&(text() + " "), &mut broker).unwrap().state,
        "prepared"
    );
}

#[test]
fn semantic_prepared_validation_rejects_tampering_even_with_a_new_envelope_checksum() {
    let dir = Directory::new();
    let mut worker = Worker::open(dir.config()).unwrap();
    let mut broker = FakeBroker::default();
    let prepared = worker.prepare(&text(), &mut broker).unwrap();
    drop(worker);
    rewrite_journal(&dir, &prepared.request_hash, |payload| {
        payload["prepared"]["records"][0]["timestamp"] = json!(999)
    });
    assert!(Worker::open(dir.config()).is_err());
    assert_eq!(broker.sends, 0);
}

fn edit_fragment(text: &str, edit: impl FnOnce(&mut Value)) -> String {
    let mut request: Value = serde_json::from_str(text).unwrap();
    let mut assignment: Value =
        serde_json::from_str(request["assignmentText"].as_str().unwrap()).unwrap();
    let mut fragment: Value =
        serde_json::from_str(assignment["fragmentText"].as_str().unwrap()).unwrap();
    edit(&mut fragment);
    let fragment = fragment.to_string();
    assignment["fragmentText"] = json!(fragment);
    assignment["fragmentHash"] = json!(tsx_contracts::sha256(&fragment));
    request["assignmentText"] = json!(assignment.to_string());
    request.to_string()
}

#[test]
fn pending_resample_owner_is_in_manifest_even_when_no_episode_is_emitted() {
    let dir = Directory::new();
    let mut worker = Worker::open(dir.config()).unwrap();
    let text = edit_fragment(&text(), |fragment| {
        fragment["nodes"][1] = json!({"id":"convert","op":"resample","in":["decode"],"params":{"every":"PT3S","agg":"MEAN"}});
    });
    let mut broker = FakeBroker::default();
    let status = worker.prepare(&text, &mut broker).unwrap();
    assert_eq!(status.state, "prepared");
    assert_eq!(status.output_series.len(), 1);
    let completed = worker.activate(&status.request_hash, &mut broker).unwrap();
    assert_eq!(completed.receipt.unwrap().output_records, 0);
    assert_eq!(broker.output.len(), 0);
}

#[test]
fn manifest_includes_unselected_inputs_and_only_selected_passthrough_outputs() {
    let dir = Directory::new();
    let mut worker = Worker::open(dir.config()).unwrap();
    let mut other = episode(vec![2.0], 11_000_000);
    other.series.tags.insert("sensor".into(), "two".into());
    other.series.series_id =
        tsx_runner::model::series_id(&other.series.metric, &other.series.tags).unwrap();
    let input = [episode(vec![1.0], 10_000_000), other]
        .iter()
        .enumerate()
        .map(|(offset, e)| InputRecord {
            partition: 0,
            offset: offset as i64,
            key: e.series.series_id.as_bytes().to_vec(),
            payload: wire::encode(e).unwrap(),
        })
        .collect();
    let mut text: Value = serde_json::from_str(&edit_fragment(&text(), |fragment| {
        fragment["nodes"] = json!([{"id":"decode","op":"decode.avro-soe"}]);
        fragment["sinks"][0]["from"] = json!("decode");
    }))
    .unwrap();
    text["sink"]["name"] = json!("decode");
    let mut assignment: Value =
        serde_json::from_str(text["assignmentText"].as_str().unwrap()).unwrap();
    assignment["inputs"][0]["positions"][0]["to"] = json!("2");
    text["assignmentText"] = json!(assignment.to_string());
    let mut broker = FakeBroker {
        input: Some(input),
        ..FakeBroker::default()
    };
    let prepared = worker.prepare(&text.to_string(), &mut broker).unwrap();
    assert_eq!(prepared.input_series.len(), 2);
    assert_eq!(
        prepared.output_series,
        vec![episode(vec![1.0], 10_000_000).series.series_id]
    );
    assert_eq!((broker.reads, broker.sends), (1, 0));
}

#[cfg(unix)]
#[test]
fn symlink_state_and_job_paths_are_rejected_without_touching_targets() {
    use std::os::unix::fs::symlink;
    let dir = Directory::new();
    let target = Directory::new();
    let link = dir.0.join("linked-state");
    symlink(&target.0, &link).unwrap();
    let mut config = dir.config();
    config.state = link;
    assert!(Worker::open(config).is_err());
    fs::remove_file(dir.0.join("linked-state")).unwrap();
    let mut worker = Worker::open(dir.config()).unwrap();
    symlink(&target.0, dir.0.join(tsx_contracts::sha256(&text()))).unwrap();
    assert!(worker.prepare(&text(), &mut FakeBroker::default()).is_err());
    assert_eq!(fs::read_dir(&target.0).unwrap().count(), 0);
}

#[test]
fn http_lifecycle_has_exact_status_keys_and_never_exposes_payloads() {
    use tiny_http::{Method, TestRequest};
    let dir = Directory::new();
    let mut worker = Worker::open(dir.config()).unwrap();
    let mut broker = FakeBroker::default();
    let mut request = TestRequest::new()
        .with_method(Method::Post)
        .with_path("/v1/prepare")
        .with_body(Box::leak(text().into_boxed_str()))
        .into();
    let (code, status) = worker.handle(&mut request, &mut broker);
    assert_eq!(code, 200);
    assert_eq!(status["state"], "prepared");
    assert_eq!(status.as_object().unwrap().len(), 10);
    assert!(status.get("receipt").is_none());
    assert!(status.get("error").is_none());
    let id = status["requestHash"].as_str().unwrap();
    let mut request = TestRequest::new()
        .with_method(Method::Post)
        .with_path(&format!("/v1/activate/{id}"))
        .into();
    let (code, completed) = worker.handle(&mut request, &mut broker);
    assert_eq!(code, 200);
    assert_eq!(completed["state"], "completed");
    assert_eq!(completed.as_object().unwrap().len(), 11);
    assert!(completed.get("receipt").is_some());
    let mut request = TestRequest::new()
        .with_method(Method::Post)
        .with_path(&format!("/v1/abort/{id}"))
        .into();
    assert_eq!(worker.handle(&mut request, &mut broker).0, 409);
    assert_eq!((broker.reads, broker.sends), (1, 1));
}
