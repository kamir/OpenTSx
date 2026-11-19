mod common;

use common::{episode, request, ARTIFACT_DIGEST};
use serde_json::{json, Value};
use std::collections::BTreeSet;
use std::fs;
use std::sync::atomic::{AtomicU64, Ordering};
use tsx_runner::admission::{admit, Admitted};
use tsx_runner::broker::{Broker, InputRecord, OutputRecord};
use tsx_runner::{runner, wire};

static NEXT: AtomicU64 = AtomicU64::new(0);

#[derive(Default)]
struct MemoryBroker {
    input: Vec<InputRecord>,
    output: Vec<OutputRecord>,
    reads: usize,
    sends: usize,
    fail_ack: bool,
}
impl Broker for MemoryBroker {
    fn read(&mut self, _: &Admitted) -> Result<Vec<InputRecord>, String> {
        self.reads += 1;
        Ok(self.input.clone())
    }
    fn publish(&mut self, _: &Admitted, records: &[OutputRecord]) -> Result<(), String> {
        self.sends += 1;
        self.output.extend_from_slice(records);
        if self.fail_ack {
            Err("lost acknowledgement".into())
        } else {
            Ok(())
        }
    }
}

fn catalog_request(signal: bool) -> Value {
    let mut request = request();
    request["context"] = json!({});
    let mut assignment: Value =
        serde_json::from_str(request["assignmentText"].as_str().unwrap()).unwrap();
    let mut dag: Value =
        serde_json::from_str(assignment["fragmentText"].as_str().unwrap()).unwrap();
    dag["nodes"] = json!([{"id":"decode","op":"decode.avro-soe"},
        {"id":"stats","op":"stats.welford-ddsketch","in":["decode"],"params":{"windows":["PT10S"],"alpha":0.01}}]);
    dag["sinks"][0]["from"] = json!("stats");
    dag["sinks"][0]["schema"] = json!("org.opentsx.model.v2.SeriesStats");
    dag["sinks"][0]["name"] = json!("plant-sensor");
    if signal {
        dag["nodes"].as_array_mut().unwrap().push(json!({"id":"dq","op":"dq.result","in":["stats"],
            "params":{"checks":["COMPLETENESS","PLAUSIBLE_RANGE","GAPS"],"min":0.0,"max":10.0,"expectedInterval":"PT1S"}}));
        dag["sinks"][0]["from"] = json!("dq");
        dag["sinks"][0]["schema"] = json!("opentsx.signal.v1");
    }
    for field in ["sourceDagText", "fragmentText"] {
        assignment[field] = json!(dag.to_string());
    }
    for field in ["specHash", "fragmentHash"] {
        assignment[field] = json!(tsx_contracts::sha256(&dag.to_string()));
    }
    assignment["inputs"][0]["partitionCount"] = json!("1");
    assignment["inputs"][0]["positions"] = json!([{"partition":"0","from":"0","to":"2"}]);
    request["assignmentText"] = json!(assignment.to_string());
    request
}

fn input() -> Vec<InputRecord> {
    [
        episode(vec![1.0, 2.0, 3.0], 10_000_000),
        episode(vec![4.0, 5.0, 6.0], 13_000_000),
    ]
    .iter()
    .enumerate()
    .map(|(i, e)| InputRecord {
        partition: 0,
        offset: i as i64,
        key: e.series.series_id.as_bytes().to_vec(),
        payload: wire::encode(e).unwrap(),
    })
    .collect()
}

#[test]
fn b2_stats_and_dq_use_the_real_admission_execution_and_acknowledged_checkpoint_path() {
    for signal in [false, true] {
        let directory = std::env::temp_dir().join(format!(
            "tsx-b2-catalog-{}-{}",
            std::process::id(),
            NEXT.fetch_add(1, Ordering::SeqCst)
        ));
        fs::create_dir(&directory).unwrap();
        let checkpoint = directory.join("checkpoint.json");
        let text = catalog_request(signal).to_string();
        let mut failed = MemoryBroker {
            input: input(),
            fail_ack: true,
            ..MemoryBroker::default()
        };
        let result = runner::execute(
            admit(&text, ARTIFACT_DIGEST).unwrap(),
            &mut failed,
            &checkpoint,
        );
        assert_eq!(result.unwrap_err(), "lost acknowledgement");
        assert!(!checkpoint.exists());
        let mut broker = MemoryBroker {
            input: input(),
            ..MemoryBroker::default()
        };
        let receipt = runner::execute(
            admit(&text, ARTIFACT_DIGEST).unwrap(),
            &mut broker,
            &checkpoint,
        )
        .unwrap();
        assert_eq!(receipt.input_records, 2);
        assert_eq!(receipt.output_records, if signal { 6 } else { 2 });
        assert_eq!(failed.output.len(), broker.output.len());
        for (a, b) in failed.output.iter().zip(&broker.output) {
            assert_eq!(
                (&a.key, a.timestamp, &a.payload),
                (&b.key, b.timestamp, &b.payload)
            );
        }
        if signal {
            let mut ids = BTreeSet::new();
            for record in &broker.output {
                let value: Value = serde_json::from_slice(&record.payload).unwrap();
                assert_eq!(value["schema"], "opentsx.signal.v1");
                assert_eq!(value["seriesId"], record.key);
                assert!(ids.insert(value["id"].as_str().unwrap().to_string()));
            }
        } else {
            assert_eq!(
                &broker.output[0].payload[..10],
                &[0xc3, 1, 6, 0x66, 0xaa, 0x8b, 0x18, 0x48, 0x1b, 0xe5]
            );
        }
        let mut unavailable = MemoryBroker::default();
        let repeated = runner::execute(
            admit(&text, ARTIFACT_DIGEST).unwrap(),
            &mut unavailable,
            &checkpoint,
        )
        .unwrap();
        assert_eq!(receipt, repeated);
        assert_eq!((unavailable.reads, unavailable.sends), (0, 0));
        fs::remove_dir_all(directory).unwrap();
    }
}
