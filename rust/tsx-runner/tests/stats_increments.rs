mod common;

use common::{episode, request, ARTIFACT_DIGEST};
use serde_json::{json, Value};
use std::fs;
use std::path::PathBuf;
use std::sync::atomic::{AtomicU64, Ordering};
use tsx_runner::admission::{admit, Admitted};
use tsx_runner::broker::{Broker, InputRecord, OutputRecord};
use tsx_runner::runner;
use tsx_runner::wire;
use tsx_runner::worker::{Config, Worker};

static NEXT: AtomicU64 = AtomicU64::new(0);

struct Directory(PathBuf);

impl Directory {
    fn new() -> Self {
        let path = std::env::temp_dir().canonicalize().unwrap().join(format!(
            "tsx-stats-increment-{}-{}",
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

/// Six episodes of three one-second samples from second 10 to second 28.
fn input() -> Vec<InputRecord> {
    (0..6)
        .map(|i| {
            let start = 10_000_000 + i * 3_000_000;
            let values = (0..3).map(|j| (i * 3 + j) as f64 * 0.5 + 1.0).collect();
            let e = episode(values, start);
            InputRecord {
                partition: 0,
                offset: i,
                key: e.series.series_id.as_bytes().to_vec(),
                payload: wire::encode(&e).unwrap(),
            }
        })
        .collect()
}

/// A B2 statistics request over `[from, to)`: decode, then `stats.welford-ddsketch` with five-second windows.
fn stats_request(
    schema: &str,
    sequence: Option<(i64, Option<&str>)>,
    from: i64,
    to: i64,
) -> String {
    let mut request = request();
    request["schema"] = json!(schema);
    request["context"] = json!({});
    if let Some((sequence, previous)) = sequence {
        request["sequence"] = json!(sequence.to_string());
        request["previousRequestHash"] = json!(previous);
    }
    let mut assignment: Value =
        serde_json::from_str(request["assignmentText"].as_str().unwrap()).unwrap();
    let mut dag: Value =
        serde_json::from_str(assignment["fragmentText"].as_str().unwrap()).unwrap();
    dag["nodes"] = json!([{"id":"decode","op":"decode.avro-soe"},
        {"id":"stats","op":"stats.welford-ddsketch","in":["decode"],"params":{"windows":["PT5S"],"alpha":0.01}}]);
    dag["sinks"][0]["from"] = json!("stats");
    dag["sinks"][0]["schema"] = json!("org.opentsx.model.v2.SeriesStats");
    dag["sinks"][0]["name"] = json!("plant-sensor");
    for field in ["sourceDagText", "fragmentText"] {
        assignment[field] = json!(dag.to_string());
    }
    for field in ["specHash", "fragmentHash"] {
        assignment[field] = json!(tsx_contracts::sha256(&dag.to_string()));
    }
    assignment["inputs"][0]["partitionCount"] = json!("1");
    assignment["inputs"][0]["positions"] =
        json!([{"partition":"0","from":from.to_string(),"to":to.to_string()}]);
    request["assignmentText"] = json!(assignment.to_string());
    request.to_string()
}

fn record(r: &OutputRecord) -> (&String, i64, &Vec<u8>) {
    (&r.key, r.timestamp, &r.payload)
}

#[test]
fn statistics_increments_with_a_restart_equal_one_bounded_run_without_its_final_flush() {
    let directory = Directory::new();
    // the worker owns its state directory exclusively; the bounded reference keeps its checkpoint elsewhere
    let reference = Directory::new();
    let checkpoint = reference.0.join("bounded.json");
    let mut bounded = FakeBroker {
        records: input(),
        ..FakeBroker::default()
    };
    let text = stats_request("opentsx.local-bounded-run.v1", None, 0, 6);
    runner::execute(
        admit(&text, ARTIFACT_DIGEST).unwrap(),
        &mut bounded,
        &checkpoint,
    )
    .unwrap();

    let mut broker = FakeBroker {
        records: input(),
        ..FakeBroker::default()
    };
    let mut worker = directory.worker();
    let first = worker
        .prepare(
            &stats_request("opentsx.local-increment.v1", Some((1, None)), 0, 3),
            &mut broker,
        )
        .unwrap();
    let first = worker.activate(&first.request_hash, &mut broker).unwrap();
    drop(worker);
    let mut worker = directory.worker();
    let second_text = stats_request(
        "opentsx.local-increment.v1",
        Some((2, Some(&first.request_hash))),
        3,
        6,
    );
    let second = worker.prepare(&second_text, &mut broker).unwrap();
    worker.activate(&second.request_hash, &mut broker).unwrap();

    // windows [10,15), [15,20) and [20,25) close; only the bounded run flushes the open [25,30) and GLOBAL
    assert_eq!(bounded.output.len(), 5);
    assert_eq!(broker.output.len(), 3);
    for (incremental, whole) in broker.output.iter().zip(&bounded.output) {
        assert_eq!(record(incremental), record(whole));
    }
}
