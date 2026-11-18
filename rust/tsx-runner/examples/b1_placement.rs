#[path = "support/b1_native_read.rs"]
mod native_read;

use rdkafka::config::ClientConfig;
use rdkafka::consumer::{BaseConsumer, Consumer};
use serde::Deserialize;
use serde_json::json;
use std::collections::{BTreeMap, BTreeSet};
use std::fs::File;
use std::io::Read;
use std::path::Path;
use std::time::Duration;
use tsx_runner::admission;
use tsx_runner::broker::{Broker, KafkaBroker, OutputRecord};

const KEYS: usize = 10_000;
const PARTITIONS: i32 = 12;
const JAVA_TOPIC: &str = "tsx.b1placement.java.v2";
const RUST_TOPIC: &str = "tsx.b1placement.rust.v2";

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Fixture {
    schema: String,
    count: usize,
    partitions: i32,
    java_topic: String,
    rust_topic: String,
    records: Vec<Row>,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Row {
    key: String,
    index: i32,
    partition: i32,
}

fn main() {
    if let Err(error) = execute() {
        eprintln!("{error}");
        std::process::exit(3);
    }
}

fn fixture(bytes: &[u8]) -> Result<Fixture, String> {
    let text = std::str::from_utf8(bytes).map_err(|_| "the placement fixture is not UTF-8")?;
    let value = tsx_contracts::parse_json(text, 2_097_152).map_err(str::to_string)?;
    let fixture: Fixture = serde_json::from_value(value)
        .map_err(|e| format!("invalid placement fixture fields: {e}"))?;
    if fixture.schema != "opentsx.rust-b1-placement.v1"
        || fixture.count != KEYS
        || fixture.partitions != PARTITIONS
        || fixture.java_topic != JAVA_TOPIC
        || fixture.rust_topic != RUST_TOPIC
        || fixture.records.len() != KEYS
    {
        return Err("the placement fixture inventory differs".into());
    }
    let mut keys = BTreeSet::new();
    for (index, row) in fixture.records.iter().enumerate() {
        if row.index != index as i32
            || !(0..PARTITIONS).contains(&row.partition)
            || row.key.len() != 32
            || !row
                .key
                .bytes()
                .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
            || !keys.insert(row.key.as_str())
        {
            return Err("invalid or duplicate placement row".into());
        }
    }
    Ok(fixture)
}

fn execute() -> Result<(), String> {
    let args: Vec<_> = std::env::args().skip(1).collect();
    if args.len() != 1 {
        return Err("expected the Java placement fixture path".into());
    }
    let mut bytes = Vec::new();
    File::open(Path::new(&args[0]))
        .map_err(|e| format!("cannot open the placement fixture: {e}"))?
        .take(2_097_153)
        .read_to_end(&mut bytes)
        .map_err(|e| format!("cannot read the placement fixture: {e}"))?;
    let fixture = fixture(&bytes)?;
    let bootstrap = std::env::var("KAFSCALE_BOOTSTRAP")
        .map_err(|_| "KAFSCALE_BOOTSTRAP must identify the disposable Proxy")?;
    let executable =
        std::env::current_exe().map_err(|e| format!("cannot locate the probe: {e}"))?;
    let artifact = format!(
        "sha256:{}",
        tsx_contracts::sha256_bytes(
            &std::fs::read(executable).map_err(|e| format!("cannot hash the probe: {e}"))?
        )
    );
    let dag = json!({"schema":"opentsx.dag.v2","dag":"b1-placement",
        "source":{"kind":"kafscale-broker","topics":[JAVA_TOPIC],"series":[]},
        "nodes":[{"id":"decode","op":"decode.avro-soe"}],
        "sinks":[{"name":"out","from":"decode","schema":"opentsx.episode.v2","topic":RUST_TOPIC}]})
    .to_string();
    let positions: Vec<_> = (0..PARTITIONS)
        .map(|partition| json!({"partition":partition.to_string(),"from":"0","to":"0"}))
        .collect();
    let hash = tsx_contracts::sha256(&dag);
    let assignment = json!({"schema":"opentsx.execution-assignment.v1","deployment":"b1-placement",
        "generation":"1","assignment":"placement","attempt":"attempt-1","fence":"1",
        "fragment":"fragment-1","site":"local","node":"probe","runtime":"rust","catalog":"tsx-catalog/1",
        "artifactDigest":artifact,"specHash":hash,"sourceDagText":dag,"fragmentHash":hash,"fragmentText":dag,
        "mode":"bounded","inputs":[{"binding":"local","topic":JAVA_TOPIC,"partitionCount":"12","positions":positions}]}).to_string();
    let request = json!({"schema":"opentsx.local-bounded-run.v1","assignmentText":assignment,
        "bindings":{"local":{"bootstrap":bootstrap,"inputs":[JAVA_TOPIC],"outputs":[RUST_TOPIC]}},
        "sink":{"name":"out","binding":"local","topic":RUST_TOPIC,"partitionCount":"12"},
        "createdAtMicros":"1790812800000000","limits":{"records":10000,"payloadBytes":1048576,
            "inputBytes":16777216,"samples":100000,"outputBytes":16777216,"series":64,"stateBytes":1048576}}).to_string();
    let admitted = admission::admit(&request, &artifact)?;
    let records: Vec<_> = fixture
        .records
        .iter()
        .map(|row| OutputRecord {
            key: row.key.clone(),
            timestamp: 1_790_812_800_000,
            payload: row.index.to_be_bytes().to_vec(),
        })
        .collect();
    let reader: BaseConsumer = ClientConfig::new()
        .set("bootstrap.servers", &bootstrap)
        .set("enable.auto.commit", "false")
        .set("enable.auto.offset.store", "false")
        .set("auto.offset.reset", "error")
        .set("enable.partition.eof", "true")
        .set("allow.auto.create.topics", "false")
        .create()
        .map_err(|e| format!("cannot create the placement reader: {e}"))?;
    for partition in 0..PARTITIONS {
        if reader
            .fetch_watermarks(RUST_TOPIC, partition, Duration::from_secs(30))
            .map_err(|e| format!("cannot inspect the placement output: {e}"))?
            != (0, 0)
        {
            return Err("the Rust placement output is not empty".into());
        }
    }
    KafkaBroker.publish(&admitted, &records)?;
    let expected: BTreeMap<_, _> = fixture
        .records
        .iter()
        .map(|row| (row.key.as_bytes(), row))
        .collect();
    let mut seen = BTreeSet::new();
    for partition in 0..PARTITIONS {
        let (low, high) = reader
            .fetch_watermarks(RUST_TOPIC, partition, Duration::from_secs(30))
            .map_err(|e| format!("cannot read placement watermarks: {e}"))?;
        if low != 0 || !(0..=KEYS as i64).contains(&high) {
            return Err("the placement output range differs".into());
        }
        for row in native_read::read(&reader, RUST_TOPIC, partition, 0, high as usize)? {
            let reference = expected
                .get(row.key.as_slice())
                .ok_or("an unknown placement key was produced")?;
            if row.partition != reference.partition
                || row.offset < 0
                || row.payload != reference.index.to_be_bytes()
                || !seen.insert(row.key)
            {
                return Err("the actual Rust producer differs from Java placement".into());
            }
        }
    }
    if seen.len() != KEYS {
        return Err("the placement probe did not observe all 10000 keys".into());
    }
    println!(
        "{}",
        json!({"schema":"opentsx.rust-b1-placement-result.v1","keys":KEYS,
        "partitions":PARTITIONS,"matched":seen.len(),"fixtureSha256":tsx_contracts::sha256_bytes(&bytes)})
    );
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn valid() -> serde_json::Value {
        json!({"schema":"opentsx.rust-b1-placement.v1","count":KEYS,"partitions":PARTITIONS,
            "javaTopic":JAVA_TOPIC,"rustTopic":RUST_TOPIC,"records":(0..KEYS).map(|index|
                json!({"key":format!("{index:032x}"),"index":index,"partition":index%12})).collect::<Vec<_>>()})
    }

    #[test]
    fn placement_evidence_requires_every_exact_key_and_partition() {
        assert_eq!(
            fixture(&serde_json::to_vec(&valid()).unwrap())
                .unwrap()
                .records
                .len(),
            KEYS
        );
        for changed in [json!(-1), json!(12), json!("0"), json!(0.5), json!(null)] {
            let mut value = valid();
            value["records"][0]["partition"] = changed;
            assert!(fixture(&serde_json::to_vec(&value).unwrap()).is_err());
        }
    }

    #[test]
    fn placement_evidence_refuses_missing_duplicate_or_unknown_rows() {
        let mut value = valid();
        value["records"].as_array_mut().unwrap().pop();
        assert!(fixture(&serde_json::to_vec(&value).unwrap()).is_err());
        let mut value = valid();
        value["records"][1]["key"] = value["records"][0]["key"].clone();
        assert!(fixture(&serde_json::to_vec(&value).unwrap()).is_err());
        let mut value = valid();
        value["records"][0].as_object_mut().unwrap().remove("index");
        assert!(fixture(&serde_json::to_vec(&value).unwrap()).is_err());
        let mut value = valid();
        value["ignored"] = json!(true);
        assert!(fixture(&serde_json::to_vec(&value).unwrap()).is_err());
        assert!(fixture(br#"{"schema":"one","schema":"two"}"#).is_err());
    }
}
