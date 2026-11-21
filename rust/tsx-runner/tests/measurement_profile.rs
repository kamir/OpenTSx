mod common;

use common::{episode, request, ARTIFACT_DIGEST};
use serde_json::{json, Value};
use std::collections::BTreeMap;
use std::fs;
use std::path::PathBuf;
use std::sync::atomic::{AtomicU64, Ordering};
use tsx_runner::admission::{admit, admit_profiled, Admitted, Profile};
use tsx_runner::broker::{Broker, InputRecord, OutputRecord};
use tsx_runner::wire;
use tsx_runner::worker::{Config, Worker};

fn refusal<T>(result: Result<T, String>) -> String {
    match result {
        Ok(_) => panic!("request was admitted"),
        Err(error) => error,
    }
}

static NEXT: AtomicU64 = AtomicU64::new(0);

struct Directory(PathBuf);

impl Directory {
    fn new() -> Self {
        let path = std::env::temp_dir().canonicalize().unwrap().join(format!(
            "tsx-measurement-profile-{}-{}",
            std::process::id(),
            NEXT.fetch_add(1, Ordering::SeqCst)
        ));
        fs::create_dir(&path).unwrap();
        Self(path)
    }

    fn worker(&self, profile: Profile) -> Worker {
        Worker::open_profiled(
            Config {
                node: "worker-1".into(),
                site: "local".into(),
                bootstrap: "localhost:9092".into(),
                artifact_digest: ARTIFACT_DIGEST.into(),
                state: self.0.clone(),
            },
            profile,
        )
        .unwrap()
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
}

impl Broker for FakeBroker {
    fn read(&mut self, run: &Admitted) -> Result<Vec<InputRecord>, String> {
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
        self.output.extend_from_slice(records);
        Ok(())
    }
}

/// The series id of sensor `i`; sensor 0 is the series of `common::episode`.
fn series(i: usize) -> String {
    let sensor = if i == 0 {
        "one".to_string()
    } else {
        format!("s{i:05}")
    };
    tsx_runner::model::series_id(
        "learning.value",
        &BTreeMap::from([("sensor".into(), sensor)]),
    )
    .unwrap()
}

/// The common clean request with a source inventory of `count` series and the given limits.
fn inventory_request(count: usize, limits: Value) -> String {
    let mut request = request();
    let mut assignment: Value =
        serde_json::from_str(request["assignmentText"].as_str().unwrap()).unwrap();
    let mut dag: Value =
        serde_json::from_str(assignment["fragmentText"].as_str().unwrap()).unwrap();
    dag["source"]["series"] = json!((0..count).map(series).collect::<Vec<_>>());
    let text = dag.to_string();
    for field in ["sourceDagText", "fragmentText"] {
        assignment[field] = json!(text);
    }
    for field in ["specHash", "fragmentHash"] {
        assignment[field] = json!(tsx_contracts::sha256(&text));
    }
    assignment["inputs"][0]["partitionCount"] = json!("1");
    assignment["inputs"][0]["positions"] = json!([{"partition":"0","from":"0","to":"1"}]);
    request["assignmentText"] = json!(assignment.to_string());
    request["limits"] = limits;
    request.to_string()
}

fn limits(series: usize) -> Value {
    json!({"records":100000,"payloadBytes":1048576,"inputBytes":67108864,"samples":4000000,
        "outputBytes":67108864,"series":series,"stateBytes":268435456})
}

#[test]
fn only_the_measurement_profile_admits_a_ten_thousand_series_inventory() {
    let text = inventory_request(10_000, limits(10_000));
    assert!(text.len() > Profile::STANDARD.request_bytes);

    let refused = refusal(admit(&text, ARTIFACT_DIGEST));
    assert_eq!(refused, "json.limit");
    let admitted = admit_profiled(&text, ARTIFACT_DIGEST, &Profile::MEASUREMENT)
        .unwrap_or_else(|error| panic!("{error}"));
    assert_eq!(admitted.request.limits.series, 10_000);
}

#[test]
fn a_request_cannot_raise_the_measurement_ceilings() {
    for (name, mut limits) in [
        ("series", limits(16_385)),
        ("records", limits(10_000)),
        ("stateBytes", limits(10_000)),
        ("samples", limits(10_000)),
    ] {
        match name {
            "records" => limits["records"] = json!(100_001),
            "stateBytes" => limits["stateBytes"] = json!(268_435_457),
            "samples" => limits["samples"] = json!(4_000_001),
            _ => {}
        }
        let text = inventory_request(16, limits);
        let error = refusal(admit_profiled(
            &text,
            ARTIFACT_DIGEST,
            &Profile::MEASUREMENT,
        ));
        assert!(error.starts_with(name), "{name}: {error}");
    }
}

#[test]
fn the_standard_profile_keeps_its_ceilings() {
    let error = refusal(admit(&inventory_request(16, limits(64)), ARTIFACT_DIGEST));
    assert_eq!(error, "records must be within 1..=10000");
    let mut small = limits(64);
    for (field, value) in [
        ("records", 10_000),
        ("inputBytes", 16_777_216),
        ("outputBytes", 16_777_216),
        ("samples", 100_000),
        ("stateBytes", 1_048_576),
    ] {
        small[field] = json!(value);
    }
    assert!(admit(&inventory_request(16, small.clone()), ARTIFACT_DIGEST).is_ok());
    small["series"] = json!(65);
    assert_eq!(
        refusal(admit(&inventory_request(16, small), ARTIFACT_DIGEST)),
        "series must be within 1..=64"
    );
}

#[test]
fn a_measurement_worker_runs_and_reports_the_profile() {
    let directory = Directory::new();
    let standard = directory.worker(Profile::STANDARD).capabilities();
    assert_eq!(standard["profile"], "standard");
    assert_eq!(standard["limits"]["series"], 64);
    assert_eq!(standard["limits"]["requestBytes"], 65_536);
    let mut worker = directory.worker(Profile::MEASUREMENT);
    let capabilities = worker.capabilities();
    assert_eq!(capabilities["profile"], "measurement");
    assert_eq!(capabilities["limits"]["series"], 16_384);
    assert_eq!(capabilities["limits"]["records"], 100_000);
    assert_eq!(capabilities["limits"]["stateBytes"], 268_435_456);
    assert_eq!(capabilities["catalogExecution"]["limits"]["series"], 16_384);

    let e = episode(vec![1.0, 2.0, 3.0], 10_000_000);
    let mut broker = FakeBroker {
        records: vec![InputRecord {
            partition: 0,
            offset: 0,
            key: e.series.series_id.as_bytes().to_vec(),
            payload: wire::encode(&e).unwrap(),
        }],
        ..FakeBroker::default()
    };
    let text = inventory_request(10_000, limits(10_000));
    let prepared = worker.prepare(&text, &mut broker).unwrap();
    drop(worker);
    // the prepared journal holds a request larger than the standard ceiling and is read back after a restart
    let mut worker = directory.worker(Profile::MEASUREMENT);
    let status = worker
        .activate(&prepared.request_hash, &mut broker)
        .unwrap();
    assert_eq!(status.state, "completed");
    assert_eq!(broker.output.len(), 1);
}
