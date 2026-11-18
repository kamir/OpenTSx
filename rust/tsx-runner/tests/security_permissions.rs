mod common;

use common::{episode, request, ARTIFACT_DIGEST};
use serde_json::{json, Value};
use std::fs;
use std::os::unix::fs::PermissionsExt;
use std::path::PathBuf;
use std::sync::atomic::{AtomicU64, Ordering};
use tsx_runner::admission::Admitted;
use tsx_runner::broker::{Broker, InputRecord, OutputRecord};
use tsx_runner::worker::{Config, Worker};

static NEXT: AtomicU64 = AtomicU64::new(0);

struct Directory(PathBuf);

impl Directory {
    fn new() -> Self {
        let path = std::env::temp_dir().canonicalize().unwrap().join(format!(
            "tsx-private-worker-{}-{}",
            std::process::id(),
            NEXT.fetch_add(1, Ordering::SeqCst)
        ));
        fs::create_dir(&path).unwrap();
        fs::set_permissions(&path, fs::Permissions::from_mode(0o755)).unwrap();
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
}

impl Drop for Directory {
    fn drop(&mut self) {
        fs::remove_dir_all(&self.0).unwrap();
    }
}

struct EmptyBroker;

impl Broker for EmptyBroker {
    fn read(&mut self, _: &Admitted) -> Result<Vec<InputRecord>, String> {
        let value = episode(vec![1.0], 10_000_000);
        Ok(vec![InputRecord {
            partition: 0,
            offset: 0,
            key: value.series.series_id.as_bytes().to_vec(),
            payload: tsx_runner::wire::encode(&value).unwrap(),
        }])
    }
    fn publish(&mut self, _: &Admitted, _: &[OutputRecord]) -> Result<(), String> {
        Err("this test does not publish".into())
    }
}

#[test]
fn worker_state_is_private_even_when_the_existing_directory_was_public() {
    let directory = Directory::new();
    let _worker = directory.worker();
    assert_eq!(
        0o700,
        fs::metadata(&directory.0).unwrap().permissions().mode() & 0o777
    );
    assert_eq!(
        0o600,
        fs::metadata(directory.0.join(".worker.lock"))
            .unwrap()
            .permissions()
            .mode()
            & 0o777
    );
}

#[test]
fn prepared_journals_are_private_even_after_replacing_an_existing_public_file() {
    let directory = Directory::new();
    let mut worker = directory.worker();
    let mut value = request();
    let mut assignment: Value =
        serde_json::from_str(value["assignmentText"].as_str().unwrap()).unwrap();
    assignment["inputs"][0]["partitionCount"] = json!("1");
    assignment["inputs"][0]["positions"] = json!([{"partition":"0","from":"0","to":"1"}]);
    value["assignmentText"] = json!(assignment.to_string());
    let text = value.to_string();
    let result = worker.prepare(&text, &mut EmptyBroker).unwrap();
    let path = directory.0.join(&result.request_hash).join("prepared.json");
    assert_eq!(
        0o600,
        fs::metadata(&path).unwrap().permissions().mode() & 0o777
    );
    fs::set_permissions(&path, fs::Permissions::from_mode(0o644)).unwrap();
    worker.abort(&result.request_hash).unwrap();
    assert_eq!(
        0o600,
        fs::metadata(path).unwrap().permissions().mode() & 0o777
    );
}
