mod common;

use common::{episode, request, ARTIFACT_DIGEST};
use serde_json::{json, Value};
use tsx_runner::admission::{admit, Admitted};
use tsx_runner::model::Episode;
use tsx_runner::wire;

fn clean_request(nodes: Vec<Value>) -> Value {
    let mut request = request();
    request["context"] = json!({});
    request["limits"]["payloadBytes"] = json!(4096);
    request["limits"]["stateBytes"] = json!(65_536);
    let mut assignment: Value =
        serde_json::from_str(request["assignmentText"].as_str().unwrap()).unwrap();
    let mut dag: Value =
        serde_json::from_str(assignment["fragmentText"].as_str().unwrap()).unwrap();
    let sink = nodes.last().unwrap()["id"].clone();
    let mut all = vec![json!({"id":"decode","op":"decode.avro-soe"})];
    all.extend(nodes);
    dag["nodes"] = json!(all);
    dag["sinks"][0]["from"] = sink;
    dag["sinks"][0]["name"] = json!("plant-sensor");
    replace_dag(&mut request, &mut assignment, &dag);
    request
}

fn replace_dag(request: &mut Value, assignment: &mut Value, dag: &Value) {
    let text = dag.to_string();
    assignment["sourceDagText"] = json!(text);
    assignment["fragmentText"] = json!(text);
    assignment["specHash"] = json!(tsx_contracts::sha256(&text));
    assignment["fragmentHash"] = json!(tsx_contracts::sha256(&text));
    assignment["inputs"][0]["partitionCount"] = json!("1");
    assignment["inputs"][0]["positions"] = json!([{"partition":"0","from":"0","to":"1"}]);
    request["assignmentText"] = json!(assignment.to_string());
}

fn admitted(request: &Value) -> Admitted {
    admit(&request.to_string(), ARTIFACT_DIGEST).unwrap()
}

fn outputs(run: &mut Admitted, episodes: &[Episode]) -> Vec<Episode> {
    run.pipeline
        .process_records(episodes, false)
        .unwrap()
        .iter()
        .map(|record| wire::decode(&record.payload, 4096, 1000).unwrap())
        .collect()
}

fn samples(episodes: &[Episode]) -> Vec<(i64, u64)> {
    episodes
        .iter()
        .flat_map(|e| {
            e.timestamps()
                .unwrap()
                .into_iter()
                .zip(e.values.iter().map(|v| v.to_bits()))
        })
        .collect()
}

#[test]
fn multiple_resample_steps_have_independent_windows_and_restore_on_every_chunk() {
    let request = clean_request(vec![
        json!({"id":"first","op":"resample","in":["decode"],"params":{"every":"PT2S"}}),
        json!({"id":"convert","op":"convert.unit","in":["first"],"params":{"factor":2,"offset":1}}),
        json!({"id":"second","op":"resample","in":["convert"],"params":{"every":"PT4S"}}),
    ]);
    let values = vec![1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0];
    let whole = outputs(
        &mut admitted(&request),
        &[episode(values.clone(), 12_000_000)],
    );
    assert_eq!(
        samples(&whole),
        vec![
            (12_000_000, 6.0f64.to_bits()),
            (16_000_000, 14.0f64.to_bits())
        ]
    );
    assert!(whole.iter().all(|e| e.interval_micros == Some(4_000_000)));
    for chunk in [1, 3, 7, 8] {
        let mut run = admitted(&request);
        let mut actual = Vec::new();
        for (i, part) in values.chunks(chunk).enumerate() {
            actual.extend(outputs(
                &mut run,
                &[episode(
                    part.to_vec(),
                    12_000_000 + (i * chunk) as i64 * 1_000_000,
                )],
            ));
            let snapshot = run.pipeline.snapshot().unwrap();
            let encoded = serde_json::to_vec(&snapshot).unwrap();
            let mut restored = admitted(&request);
            restored
                .pipeline
                .restore(&serde_json::from_slice(&encoded).unwrap())
                .unwrap();
            assert_eq!(snapshot, restored.pipeline.snapshot().unwrap());
            run = restored;
        }
        assert_eq!(samples(&actual), samples(&whole), "chunk {chunk}");
        assert!(actual.iter().all(|e| e.interval_micros == Some(4_000_000)));
    }
}

#[test]
fn catalog_cleaning_accepts_quality_and_revisions_without_copying_them_to_derived_output() {
    let request = clean_request(vec![
        json!({"id":"convert","op":"convert.unit","in":["decode"],"params":{"factor":2,"offset":1,"unit":"MW"}}),
    ]);
    let clean = episode(vec![1.0, 2.0, 3.0], 10_000_000);
    let mut annotated = clean.clone();
    annotated.quality = Some(vec![1, 2, 4]);
    annotated.revision = 7;
    annotated
        .labels
        .insert("raw-only".into(), "synthetic".into());
    let expected = outputs(&mut admitted(&request), &[clean]);
    let actual = outputs(&mut admitted(&request), &[annotated.clone()]);
    assert_eq!(actual.len(), 1);
    assert_eq!(actual[0].values, vec![3.0, 5.0, 7.0]);
    assert_eq!(actual[0].quality, None);
    assert_eq!(actual[0].revision, 0);
    assert_eq!(actual[0].series.unit.as_deref(), Some("MW"));
    assert!(!actual[0].labels.contains_key("raw-only"));
    assert_eq!(
        wire::encode(&actual[0]).unwrap(),
        wire::encode(&expected[0]).unwrap()
    );
    let mut legacy = request;
    legacy.as_object_mut().unwrap().remove("context");
    assert_eq!(
        admitted(&legacy)
            .pipeline
            .process_records(&[annotated], false)
            .unwrap_err(),
        "cleaning rejects revisions and per-value quality"
    );
}

#[test]
fn feature_grade_keeps_the_target_node_in_the_derived_series_identity() {
    let mut request = clean_request(vec![
        json!({"id":"lag","op":"feature.lag","in":["decode"],"params":{"steps":1}}),
    ]);
    let mut assignment: Value =
        serde_json::from_str(request["assignmentText"].as_str().unwrap()).unwrap();
    let mut dag: Value =
        serde_json::from_str(assignment["fragmentText"].as_str().unwrap()).unwrap();
    dag["sinks"][0]["signal"]["grade"] = json!("features");
    dag["sinks"][0]["signal"]["metric"] = json!("learning.lag");
    dag["sinks"][0]["madeOf"] = json!("source-signal");
    replace_dag(&mut request, &mut assignment, &dag);
    let mut run = admitted(&request);
    let actual = outputs(&mut run, &[episode(vec![10.0, 20.0, 30.0], 10_000_000)]);
    assert_eq!(actual.len(), 1);
    let out = &actual[0];
    assert!(out.values[0].is_nan());
    assert_eq!(&out.values[1..], &[10.0, 20.0]);
    assert_eq!(out.series.metric, "learning.lag");
    assert_eq!(out.series.tags["grade"], "features");
    assert_eq!(out.series.tags["feature"], "lag");
    assert_eq!(out.series.tags["system"], "learning");
    assert_eq!(out.series.tags["signal"], "plant/sensor");
    assert_eq!(out.series.tags["flow"], "learning-flow");
    assert!(!out.series.tags.contains_key("sensor"));
    assert_eq!(out.labels["grade"], "features");
    assert_eq!(out.labels["madeOf"], "source-signal");
    let expected_id = tsx_runner::model::series_id(&out.series.metric, &out.series.tags).unwrap();
    assert_eq!(out.series.series_id, expected_id);
    let snapshot = run.pipeline.snapshot().unwrap();
    let mut restored = admitted(&request);
    restored.pipeline.restore(&snapshot).unwrap();
    let resumed = outputs(&mut restored, &[episode(vec![40.0], 13_000_000)]);
    assert_eq!(resumed[0].values, vec![30.0]);
    assert_eq!(resumed[0].series.series_id, expected_id);
}

#[test]
fn metadata_failures_and_sample_caps_leave_catalog_clean_state_unchanged() {
    let request = clean_request(vec![
        json!({"id":"convert","op":"convert.unit","in":["decode"],"params":{"unit":"MW"}}),
    ]);
    let mut run = admitted(&request);
    outputs(&mut run, &[episode(vec![1.0], 10_000_000)]);
    let before = run.pipeline.snapshot().unwrap();
    let mut changed = episode(vec![2.0], 11_000_000);
    changed.series.unit = Some("kWh".into());
    assert_eq!(
        run.pipeline.process_records(&[changed], false).unwrap_err(),
        "source series metadata changed"
    );
    assert_eq!(run.pipeline.snapshot().unwrap(), before);
    let mut capped = request;
    capped["limits"]["samples"] = json!(2);
    let mut run = admitted(&capped);
    let empty = run.pipeline.snapshot().unwrap();
    assert!(run
        .pipeline
        .process_records(&[episode(vec![1.0, 2.0, 3.0], 10_000_000)], false)
        .is_err());
    assert_eq!(run.pipeline.snapshot().unwrap(), empty);
}

#[test]
fn malformed_retained_feature_queues_are_refused_transactionally() {
    let request = clean_request(vec![
        json!({"id":"lag","op":"feature.lag","in":["decode"],"params":{"steps":2}}),
    ]);
    let mut run = admitted(&request);
    let input = episode(vec![1.0, 2.0, 3.0], 10_000_000);
    let id = input.series.series_id.clone();
    outputs(&mut run, &[input]);
    let before = run.pipeline.snapshot().unwrap();
    let mut bad_step = before.clone();
    let retained = bad_step["series"][&id]["kept"].as_object_mut().unwrap();
    let values = retained.remove("0").unwrap();
    retained.insert("999".into(), values);
    let mut bad_bits = before.clone();
    bad_bits["series"][&id]["kept"]["0"][0][1] = json!("not-float-bits");
    let mut bad_order = before.clone();
    bad_order["series"][&id]["kept"]["0"][1][0] = before["series"][&id]["kept"]["0"][0][0].clone();
    let mut too_many = before.clone();
    too_many["series"][&id]["kept"]["0"]
        .as_array_mut()
        .unwrap()
        .push(json!(["13000000", "4010000000000000"]));
    for invalid in [bad_step, bad_bits, bad_order, too_many] {
        assert!(run.pipeline.restore(&invalid).is_err());
        assert_eq!(run.pipeline.snapshot().unwrap(), before);
    }
}

#[test]
fn rolling_history_respects_the_declared_sample_cap_across_successive_cuts() {
    let mut request = clean_request(vec![
        json!({"id":"rolling","op":"feature.rolling","in":["decode"],"params":{"window":"PT10S"}}),
    ]);
    request["limits"]["samples"] = json!(2);
    let mut run = admitted(&request);
    outputs(&mut run, &[episode(vec![1.0, 2.0], 10_000_000)]);
    let before = run.pipeline.snapshot().unwrap();
    assert!(run
        .pipeline
        .process_records(&[episode(vec![3.0], 12_000_000)], false)
        .is_err());
    assert_eq!(before, run.pipeline.snapshot().unwrap());
    let mut restored = admitted(&request);
    restored.pipeline.restore(&before).unwrap();
    assert_eq!(before, restored.pipeline.snapshot().unwrap());
}

#[test]
fn context_resource_admission_is_explicit_and_does_not_change_legacy_caps() {
    let mut request = clean_request(vec![
        json!({"id":"convert","op":"convert.unit","in":["decode"]}),
    ]);
    request["limits"]["series"] = json!(1024);
    request["limits"]["samples"] = json!(1_000_000);
    assert!(admit(&request.to_string(), ARTIFACT_DIGEST).is_ok());
    let mut legacy = request.clone();
    legacy.as_object_mut().unwrap().remove("context");
    assert!(admit(&legacy.to_string(), ARTIFACT_DIGEST).is_err());
    for context in [
        json!({"unknown":1}),
        json!([]),
        json!({"xcorrProductLimit":0}),
    ] {
        let mut invalid = request.clone();
        invalid["context"] = context;
        assert!(
            admit(&invalid.to_string(), ARTIFACT_DIGEST).is_err(),
            "context {}",
            invalid["context"]
        );
    }
    request["limits"]["series"] = json!(1025);
    assert!(admit(&request.to_string(), ARTIFACT_DIGEST).is_err());
}

#[test]
fn statistics_and_dq_apply_the_declared_processor_defaults() {
    let mut request = clean_request(vec![
        json!({"id":"stats","op":"stats.welford-ddsketch","in":["decode"]}),
    ]);
    let mut assignment: Value =
        serde_json::from_str(request["assignmentText"].as_str().unwrap()).unwrap();
    let mut dag: Value =
        serde_json::from_str(assignment["fragmentText"].as_str().unwrap()).unwrap();
    dag["sinks"][0]["schema"] = json!("org.opentsx.model.v2.SeriesStats");
    replace_dag(&mut request, &mut assignment, &dag);
    let input = episode(vec![1.0, 2.0, 3.0, 4.0], 600_000_000);
    let records = admitted(&request)
        .pipeline
        .process_records(std::slice::from_ref(&input), true)
        .unwrap();
    let mut types = std::collections::BTreeSet::new();
    assert_eq!(records.len(), 3);
    for output in records {
        let stats = tsx_runner::stats::StatsRecord::decode_soe(&output.payload, 4096).unwrap();
        assert_eq!(stats.valid_count, 4);
        assert_eq!(stats.sketch.as_ref().unwrap().alpha(), 0.01);
        types.insert(stats.window_type);
    }
    assert_eq!(
        types,
        ["GLOBAL", "PT10M", "PT1H"]
            .into_iter()
            .map(str::to_owned)
            .collect()
    );
    dag["nodes"].as_array_mut().unwrap().push(json!({"id":"dq","op":"dq.result","in":["stats"],"params":{"min":0,"max":10,"expectedInterval":"PT1S"}}));
    dag["sinks"][0]["from"] = json!("dq");
    dag["sinks"][0]["schema"] = json!("opentsx.signal.v1");
    replace_dag(&mut request, &mut assignment, &dag);
    let records = admitted(&request)
        .pipeline
        .process_records(&[input], true)
        .unwrap();
    assert_eq!(records.len(), 9);
    let mut checks = std::collections::BTreeSet::new();
    let mut ids = std::collections::BTreeSet::new();
    for record in records {
        let signal: Value = serde_json::from_slice(&record.payload).unwrap();
        assert_eq!(signal["schema"], "opentsx.signal.v1");
        checks.insert(signal["check"].as_str().unwrap().to_owned());
        assert!(ids.insert(signal["id"].as_str().unwrap().to_owned()));
    }
    assert_eq!(
        checks,
        ["COMPLETENESS", "GAPS", "PLAUSIBLE_RANGE"]
            .into_iter()
            .map(str::to_owned)
            .collect()
    );
}

#[test]
fn all_shared_clean_cases_match_in_six_checkpointed_chunk_and_series_orders() {
    let path = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../../docs/datamodel-2026/testvectors/clean-ops.v1.json");
    let vectors: Value = serde_json::from_slice(&std::fs::read(path).unwrap()).unwrap();
    assert_eq!(vectors["schema"], "opentsx.testvectors.clean-ops.v1");
    let cases = vectors["cases"].as_array().unwrap();
    assert_eq!(cases.len(), 15);
    let mut comparisons = 0;
    for case in cases {
        let start = time::OffsetDateTime::parse(
            case["t0"].as_str().unwrap(),
            &time::format_description::well_known::Rfc3339,
        )
        .unwrap()
        .unix_timestamp()
            * 1_000_000;
        let every = case["intervalSeconds"].as_i64().unwrap() * 1_000_000;
        let values: Vec<f64> = case["values"]
            .as_array()
            .unwrap()
            .iter()
            .map(|v| {
                if v.is_null() {
                    f64::NAN
                } else {
                    v.as_f64().unwrap()
                }
            })
            .collect();
        let raw = |sensor: &str, from: usize, to: usize| {
            let mut e = episode(values[from..to].to_vec(), start + from as i64 * every);
            e.interval_micros = Some(every);
            e.t_end = e.t_start + (to - from) as i64 * every;
            e.series.tags.insert("sensor".into(), sensor.into());
            e.series.series_id =
                tsx_runner::model::series_id(&e.series.metric, &e.series.tags).unwrap();
            e.series.unit = Some("m/s".into());
            e.episode_id = format!("vector-{sensor}-{from}");
            e
        };
        let mut previous = "decode".to_owned();
        let mut nodes = Vec::new();
        for node in case["nodes"].as_array().unwrap() {
            let mut node = node.clone();
            node["in"] = json!([previous]);
            previous = node["id"].as_str().unwrap().to_owned();
            nodes.push(node);
        }
        let mut request = clean_request(nodes);
        let mut assignment: Value =
            serde_json::from_str(request["assignmentText"].as_str().unwrap()).unwrap();
        let mut dag: Value =
            serde_json::from_str(assignment["fragmentText"].as_str().unwrap()).unwrap();
        dag["source"]["series"] = json!([
            raw("one", 0, 1).series.series_id,
            raw("two", 0, 1).series.series_id
        ]);
        replace_dag(&mut request, &mut assignment, &dag);
        let mut baseline = None;
        for chunk in [1, 7, values.len()] {
            for reverse in [false, true] {
                let mut run = admitted(&request);
                let mut actual: std::collections::BTreeMap<String, Vec<Episode>> =
                    std::collections::BTreeMap::new();
                for from in (0..values.len()).step_by(chunk) {
                    let mut input = vec![
                        raw("one", from, (from + chunk).min(values.len())),
                        raw("two", from, (from + chunk).min(values.len())),
                    ];
                    if reverse {
                        input.reverse();
                    }
                    for out in outputs(&mut run, &input) {
                        actual
                            .entry(out.series.tags["sensor"].clone())
                            .or_default()
                            .push(out);
                    }
                    let snapshot = run.pipeline.snapshot().unwrap();
                    let mut restored = admitted(&request);
                    restored
                        .pipeline
                        .restore(
                            &serde_json::from_slice(&serde_json::to_vec(&snapshot).unwrap())
                                .unwrap(),
                        )
                        .unwrap();
                    assert_eq!(snapshot, restored.pipeline.snapshot().unwrap());
                    run = restored;
                }
                assert_eq!(actual.len(), 2, "{}", case["name"]);
                let mut logical = std::collections::BTreeMap::new();
                for sensor in ["one", "two"] {
                    let episodes = &actual[sensor];
                    let data = samples(episodes);
                    let expected = case["expect"]["values"].as_array().unwrap();
                    assert_eq!(
                        data.len(),
                        expected.len(),
                        "{} sensor {sensor} chunk {chunk}",
                        case["name"]
                    );
                    for (index, (timestamp, bits)) in data.iter().enumerate() {
                        assert_eq!(
                            *timestamp,
                            start + case["expect"]["times"][index].as_i64().unwrap() * 1_000_000,
                            "{}",
                            case["name"]
                        );
                        let actual = f64::from_bits(*bits);
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
                    let expected_unit = case["expect"]["unit"].as_str().unwrap_or("m/s");
                    assert!(episodes
                        .iter()
                        .all(|e| e.series.unit.as_deref() == Some(expected_unit)));
                    logical.insert(sensor, data);
                }
                if let Some(previous) = &baseline {
                    assert_eq!(
                        &logical, previous,
                        "{} chunk {chunk} reverse {reverse}",
                        case["name"]
                    );
                } else {
                    baseline = Some(logical);
                }
                comparisons += 1;
            }
        }
    }
    assert_eq!(comparisons, 90);
}
