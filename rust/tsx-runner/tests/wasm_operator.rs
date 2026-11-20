mod common;

use common::{episode, request, ARTIFACT_DIGEST};
use serde_json::{json, Value};
use std::fs;
use std::path::Path;
use std::sync::atomic::{AtomicU64, Ordering};
use tsx_runner::admission::{admit, Admitted};
use tsx_runner::broker::{Broker, InputRecord, OutputRecord};
use tsx_runner::model::Episode;
use tsx_runner::{runner, wire};

static NEXT: AtomicU64 = AtomicU64::new(0);

fn clean_request(nodes: &[Value]) -> Value {
    let mut request = request();
    request["context"] = json!({});
    request["limits"]["payloadBytes"] = json!(4096);
    request["limits"]["stateBytes"] = json!(65_536);
    let mut assignment: Value =
        serde_json::from_str(request["assignmentText"].as_str().unwrap()).unwrap();
    let mut dag: Value =
        serde_json::from_str(assignment["fragmentText"].as_str().unwrap()).unwrap();
    let mut previous = "decode".to_owned();
    let mut chain = vec![json!({"id":"decode","op":"decode.avro-soe"})];
    for node in nodes {
        let mut node = node.clone();
        node["in"] = json!([previous]);
        previous = node["id"].as_str().unwrap().to_owned();
        chain.push(node);
    }
    dag["nodes"] = json!(chain);
    dag["sinks"][0]["from"] = json!(previous);
    dag["sinks"][0]["name"] = json!("plant-sensor");
    let text = dag.to_string();
    assignment["sourceDagText"] = json!(text);
    assignment["fragmentText"] = json!(text);
    assignment["specHash"] = json!(tsx_contracts::sha256(&text));
    assignment["fragmentHash"] = json!(tsx_contracts::sha256(&text));
    assignment["inputs"][0]["partitionCount"] = json!("1");
    assignment["inputs"][0]["positions"] = json!([{"partition":"0","from":"0","to":"1"}]);
    request["assignmentText"] = json!(assignment.to_string());
    request
}

fn admitted(request: &Value) -> Admitted {
    admit(&request.to_string(), ARTIFACT_DIGEST).unwrap()
}

fn outputs(run: &mut Admitted, input: &[Episode]) -> Vec<Episode> {
    run.pipeline
        .process_records(input, false)
        .unwrap()
        .iter()
        .map(|record| {
            let episode = wire::decode(&record.payload, 4096, 1000).unwrap();
            assert_eq!(record.key, episode.series.series_id);
            assert_eq!(record.timestamp, episode.t_start / 1000);
            episode
        })
        .collect()
}

fn conversion_cases() -> Vec<Value> {
    let path = Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../../docs/datamodel-2026/testvectors/clean-ops.v1.json");
    let vectors: Value = serde_json::from_slice(&fs::read(path).unwrap()).unwrap();
    assert_eq!(vectors["schema"], "opentsx.testvectors.clean-ops.v1");
    let cases: Vec<_> = vectors["cases"]
        .as_array()
        .unwrap()
        .iter()
        .filter(|case| {
            case["nodes"]
                .as_array()
                .unwrap()
                .iter()
                .any(|node| node["op"] == "convert.unit")
        })
        .cloned()
        .collect();
    assert_eq!(cases.len(), 2);
    cases
}

fn start(case: &Value) -> i64 {
    time::OffsetDateTime::parse(
        case["t0"].as_str().unwrap(),
        &time::format_description::well_known::Rfc3339,
    )
    .unwrap()
    .unix_timestamp()
        * 1_000_000
}

fn input(case: &Value, from: usize, to: usize) -> Episode {
    let values = case["values"].as_array().unwrap()[from..to]
        .iter()
        .map(|value| {
            if value.is_null() {
                f64::NAN
            } else {
                value.as_f64().unwrap()
            }
        })
        .collect();
    let every = case["intervalSeconds"].as_i64().unwrap() * 1_000_000;
    let mut episode = episode(values, start(case) + from as i64 * every);
    episode.interval_micros = Some(every);
    episode.t_end = episode.t_start + (to - from) as i64 * every;
    episode
}

fn samples(episodes: &[Episode]) -> Vec<(i64, u64)> {
    episodes
        .iter()
        .flat_map(|episode| {
            episode
                .timestamps()
                .unwrap()
                .into_iter()
                .zip(episode.values.iter().map(|value| value.to_bits()))
        })
        .collect()
}

fn assert_vector(case: &Value, episodes: &[Episode]) {
    let actual = samples(episodes);
    let expected = case["expect"]["values"].as_array().unwrap();
    assert_eq!(actual.len(), expected.len(), "{}", case["name"]);
    for (index, &(timestamp, bits)) in actual.iter().enumerate() {
        assert_eq!(
            timestamp,
            start(case) + case["expect"]["times"][index].as_i64().unwrap() * 1_000_000,
            "{} index {index}",
            case["name"]
        );
        let actual = f64::from_bits(bits);
        if expected[index].is_null() {
            assert!(actual.is_nan(), "{} gap {index}", case["name"]);
        } else {
            let expected = expected[index].as_f64().unwrap();
            assert!(
                (actual - expected).abs() <= 1e-12 * expected.abs().max(1.0),
                "{} index {index}: {actual} != {expected}",
                case["name"]
            );
        }
    }
    let unit = case["expect"]["unit"].as_str().unwrap();
    for episode in episodes {
        assert_eq!(episode.series.unit.as_deref(), Some(unit));
        assert_eq!(
            episode.series.series_id,
            tsx_runner::model::series_id(&episode.series.metric, &episode.series.tags).unwrap()
        );
        assert_eq!(episode.provenance.created_at, 1_700_000_000_000_000);
        assert_eq!(episode.provenance.source, "opentsx-processor");
        assert_eq!(
            episode.provenance.producer,
            format!("tsx-runner/{}", env!("CARGO_PKG_VERSION"))
        );
    }
}

#[test]
fn both_shared_conversion_cases_match_through_admitted_wasm_pipeline() {
    for case in conversion_cases() {
        let request = clean_request(case["nodes"].as_array().unwrap());
        let input = input(&case, 0, case["values"].as_array().unwrap().len());
        let actual = outputs(&mut admitted(&request), std::slice::from_ref(&input));
        assert_vector(&case, &actual);
        assert!(actual
            .iter()
            .all(|output| output.series.series_id != input.series.series_id));
        let repeated = outputs(&mut admitted(&request), &[input]);
        assert_eq!(
            actual
                .iter()
                .map(|e| wire::encode(e).unwrap())
                .collect::<Vec<_>>(),
            repeated
                .iter()
                .map(|e| wire::encode(e).unwrap())
                .collect::<Vec<_>>()
        );
    }
}

#[test]
fn every_shared_conversion_partition_restores_exact_host_state_and_samples() {
    let mut comparisons = 0;
    for case in conversion_cases() {
        let request = clean_request(case["nodes"].as_array().unwrap());
        let count = case["values"].as_array().unwrap().len();
        assert_eq!(count, 4);
        let whole = outputs(&mut admitted(&request), &[input(&case, 0, count)]);
        for mask in 0..1usize << (count - 1) {
            let mut run = admitted(&request);
            let mut actual = Vec::new();
            let mut from = 0;
            for to in 1..=count {
                if to != count && mask & (1 << (to - 1)) == 0 {
                    continue;
                }
                actual.extend(outputs(&mut run, &[input(&case, from, to)]));
                let snapshot = run.pipeline.snapshot().unwrap();
                let mut restored = admitted(&request);
                restored
                    .pipeline
                    .restore(
                        &serde_json::from_slice(&serde_json::to_vec(&snapshot).unwrap()).unwrap(),
                    )
                    .unwrap();
                assert_eq!(snapshot, restored.pipeline.snapshot().unwrap());
                run = restored;
                from = to;
            }
            assert_vector(&case, &actual);
            assert_eq!(
                samples(&actual),
                samples(&whole),
                "{} mask {mask}",
                case["name"]
            );
            assert!(actual
                .iter()
                .all(|output| output.series.series_id == whole[0].series.series_id));
            comparisons += 1;
        }
    }
    assert_eq!(comparisons, 16);
}

#[test]
fn signed_zero_gaps_and_separate_multiply_add_preserve_unit_metadata() {
    let request = clean_request(&[json!({"id":"unit","op":"convert.unit","params":{
        "factor":1,"offset":-0.0,"unit":"MW"
    }})]);
    let actual = outputs(
        &mut admitted(&request),
        &[episode(vec![-0.0, 0.0, f64::NAN], 10_000_000)],
    );
    assert_eq!(actual.len(), 1);
    assert_eq!(actual[0].values[0].to_bits(), (-0.0f64).to_bits());
    assert_eq!(actual[0].values[1].to_bits(), 0.0f64.to_bits());
    assert!(actual[0].values[2].is_nan());
    assert_eq!(actual[0].series.unit.as_deref(), Some("MW"));
    assert_eq!(
        actual[0].timestamps().unwrap(),
        vec![10_000_000, 11_000_000, 12_000_000]
    );
    assert_eq!(
        (actual[0].summary.valid_count, actual[0].summary.nan_count),
        (2, 1)
    );
    let request = clean_request(&[json!({"id":"unit","op":"convert.unit","params":{
        "factor":1.0000000000000002,"offset":-1.0000000000000004,"unit":""
    }})]);
    let actual = outputs(
        &mut admitted(&request),
        &[episode(vec![1.0000000000000002], 10_000_000)],
    );
    assert_eq!(actual[0].values[0].to_bits(), 0.0f64.to_bits());
    assert_eq!(actual[0].series.unit.as_deref(), Some("kW"));
}

#[test]
fn plans_cannot_supply_wasm_code_or_limits() {
    for (key, value) in [
        ("module", json!("AGFzbQEAAAA")),
        ("code", json!("(module)")),
        ("path", json!("operator.wasm")),
        ("url", json!("https://example.invalid/operator.wasm")),
        ("fuel", json!(u64::MAX)),
        ("memoryLimit", json!(u64::MAX)),
        ("stackBytes", json!(u64::MAX)),
    ] {
        let mut params = json!({"factor":2});
        params[key] = value.clone();
        let request = clean_request(&[json!({
            "id":"unit","op":"convert.unit","params":params
        })]);
        assert!(
            admit(&request.to_string(), ARTIFACT_DIGEST).is_err(),
            "operator parameter {key}"
        );
        let mut request = clean_request(&[json!({
            "id":"unit","op":"convert.unit","params":{"factor":2}
        })]);
        request[key] = value.clone();
        assert!(
            admit(&request.to_string(), ARTIFACT_DIGEST).is_err(),
            "request field {key}"
        );
        for location in ["DAG", "node", "source", "sink", "signal"] {
            let mut request = clean_request(&[json!({
                "id":"unit","op":"convert.unit","params":{"factor":2}
            })]);
            let mut assignment: Value =
                serde_json::from_str(request["assignmentText"].as_str().unwrap()).unwrap();
            let mut dag: Value =
                serde_json::from_str(assignment["fragmentText"].as_str().unwrap()).unwrap();
            match location {
                "DAG" => dag[key] = value.clone(),
                "node" => dag["nodes"][1][key] = value.clone(),
                "source" => dag["source"][key] = value.clone(),
                "sink" => dag["sinks"][0][key] = value.clone(),
                "signal" => dag["sinks"][0]["signal"][key] = value.clone(),
                _ => unreachable!(),
            }
            let text = dag.to_string();
            assignment["sourceDagText"] = json!(text);
            assignment["fragmentText"] = json!(text);
            assignment["specHash"] = json!(tsx_contracts::sha256(&text));
            assignment["fragmentHash"] = json!(tsx_contracts::sha256(&text));
            request["assignmentText"] = json!(assignment.to_string());
            assert!(
                admit(&request.to_string(), ARTIFACT_DIGEST).is_err(),
                "{location} field {key}"
            );
        }
    }
}

#[test]
fn conversion_overflow_cannot_publish_or_commit_candidate_state() {
    struct MemoryBroker {
        input: InputRecord,
        publishes: usize,
    }
    impl Broker for MemoryBroker {
        fn read(&mut self, _: &Admitted) -> Result<Vec<InputRecord>, String> {
            Ok(vec![self.input.clone()])
        }
        fn publish(&mut self, _: &Admitted, _: &[OutputRecord]) -> Result<(), String> {
            self.publishes += 1;
            Ok(())
        }
    }
    let request = clean_request(&[json!({"id":"unit","op":"convert.unit","params":{
        "factor":1e308,"unit":"MW"
    }})]);
    let input = episode(vec![10.0], 10_000_000);
    let mut run = admitted(&request);
    let before = run.pipeline.snapshot().unwrap();
    assert!(run
        .pipeline
        .process_records(std::slice::from_ref(&input), false)
        .unwrap_err()
        .contains("overflow"));
    assert_eq!(run.pipeline.snapshot().unwrap(), before);
    let mut broker = MemoryBroker {
        input: InputRecord {
            partition: 0,
            offset: 0,
            key: input.series.series_id.as_bytes().to_vec(),
            payload: wire::encode(&input).unwrap(),
        },
        publishes: 0,
    };
    let directory = std::env::temp_dir().join(format!(
        "tsx-b5-wasm-overflow-{}-{}",
        std::process::id(),
        NEXT.fetch_add(1, Ordering::Relaxed)
    ));
    fs::create_dir(&directory).unwrap();
    let checkpoint = directory.join("checkpoint.json");
    assert!(
        runner::execute(admitted(&request), &mut broker, &checkpoint)
            .unwrap_err()
            .contains("overflow")
    );
    assert_eq!(broker.publishes, 0);
    assert!(!checkpoint.exists());
    assert!(!directory.join(".checkpoint.json.pending").exists());
    fs::remove_dir_all(directory).unwrap();
}
