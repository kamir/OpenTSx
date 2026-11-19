use serde_json::Value;
use tsx_runner::stats::{evaluate, Expectation, QuantileSketch, StatsAccumulator, StatsEngine};

fn vectors(name: &str) -> Value {
    let path = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../../docs/datamodel-2026/testvectors")
        .join(format!("{name}.v1.json"));
    serde_json::from_slice(&std::fs::read(path).expect("shared vector file"))
        .expect("shared vectors")
}

fn number(value: &Value) -> f64 {
    value
        .as_f64()
        .unwrap_or_else(|| match value.as_str().expect("number or nonfinite") {
            "NaN" => f64::NAN,
            "Infinity" => f64::INFINITY,
            "-Infinity" => f64::NEG_INFINITY,
            value => panic!("invalid shared number {value}"),
        })
}

fn duration(text: &str) -> i64 {
    match text {
        "PT10M" => 600_000_000,
        "PT1H" => 3_600_000_000,
        _ => panic!("unexpected fixture duration {text}"),
    }
}

#[test]
fn all_five_stats_window_vectors_are_exact() {
    let document = vectors("stats-window");
    let cases = document["cases"].as_array().unwrap();
    assert_eq!(cases.len(), 5);
    for case in cases {
        let alpha = number(&case["alpha"]);
        let windows: Vec<_> = case["windows"]
            .as_array()
            .unwrap()
            .iter()
            .map(|value| {
                let name = value.as_str().unwrap();
                (name.to_owned(), duration(name))
            })
            .collect();
        let mut records = Vec::new();
        if case["mode"] == "accumulator" {
            let mut accumulator = StatsAccumulator::new(alpha).unwrap();
            for sample in case["samples"].as_array().unwrap() {
                accumulator
                    .add(
                        sample["tsMicros"].as_i64().unwrap(),
                        number(&sample["value"]),
                    )
                    .unwrap();
            }
            for (name, end) in windows {
                records.push(accumulator.to_record("synthetic-series", &name, 0, end));
            }
        } else {
            let mut engine = StatsEngine::new(
                windows,
                alpha,
                Some(case["expectedIntervalMicros"].as_i64().unwrap()),
                0,
            )
            .unwrap();
            for sample in case["samples"].as_array().unwrap() {
                records.extend(
                    engine
                        .on_sample(
                            "synthetic-series",
                            sample["tsMicros"].as_i64().unwrap(),
                            number(&sample["value"]),
                        )
                        .unwrap(),
                );
            }
            records.extend(engine.flush().unwrap());
        }
        let actual: Vec<_> = records
            .iter()
            .map(|record| record.vector_json().unwrap())
            .collect();
        assert_eq!(Value::Array(actual), case["expect"], "{}", case["name"]);
    }
}

fn dq_record(case: &Value, series_id: &str) -> tsx_runner::stats::StatsRecord {
    let mut accumulator = StatsAccumulator::new(number(&case["sketchAlpha"])).unwrap();
    for (index, value) in case["values"].as_array().unwrap().iter().enumerate() {
        accumulator
            .add(index as i64 * 1_000_000, number(value))
            .unwrap();
    }
    for _ in 0..case["gapCount"].as_i64().unwrap() {
        accumulator.add_gap().unwrap();
    }
    accumulator.to_record(
        series_id,
        "PT10M",
        0,
        case["windowEndMicros"].as_i64().unwrap(),
    )
}

#[test]
fn all_seven_dq_result_vectors_are_exact() {
    let document = vectors("dq-result");
    let cases = document["cases"].as_array().unwrap();
    assert_eq!(cases.len(), 7);
    for case in cases {
        let record = dq_record(case, "synthetic-series");
        let expectation = Expectation::from_json(&case["expectation"]).unwrap();
        let actual: Vec<_> = evaluate(&record, &expectation, case["nowMicros"].as_i64())
            .unwrap()
            .iter()
            .map(|result| result.vector_json())
            .collect();
        assert_eq!(Value::Array(actual), case["expect"], "{}", case["name"]);
    }
}

#[test]
fn canonical_sketch_bytes_match_the_shared_stats_case() {
    let cases = vectors("stats-window");
    let canonical = cases["cases"]
        .as_array()
        .unwrap()
        .iter()
        .find(|case| case["name"] == "canonical sketch values")
        .unwrap();
    let mut sketch = QuantileSketch::new(0.01).unwrap();
    for sample in canonical["samples"].as_array().unwrap() {
        sketch.add(number(&sample["value"])).unwrap();
    }
    let bytes = sketch.to_bytes();
    let hex: String = bytes.iter().map(|byte| format!("{byte:02x}")).collect();
    assert_eq!(
        hex,
        "017b14ae47e17a843f020500015c0278012601ba0301024301ca0101"
    );
    assert_eq!(
        QuantileSketch::from_bytes(&bytes).unwrap().to_bytes(),
        bytes
    );
}

#[test]
fn checkpointed_sample_chunks_match_all_shared_engine_vectors() {
    let document = vectors("stats-window");
    for case in document["cases"]
        .as_array()
        .unwrap()
        .iter()
        .filter(|case| case["mode"] == "engine")
    {
        let windows: Vec<_> = case["windows"]
            .as_array()
            .unwrap()
            .iter()
            .map(|v| {
                let name = v.as_str().unwrap();
                (name.to_owned(), duration(name))
            })
            .collect();
        let samples = case["samples"].as_array().unwrap();
        for chunk in [1, 7, samples.len().max(1)] {
            let mut engine = StatsEngine::new(
                windows.clone(),
                number(&case["alpha"]),
                Some(case["expectedIntervalMicros"].as_i64().unwrap()),
                0,
            )
            .unwrap();
            let mut output = Vec::new();
            for values in samples.chunks(chunk) {
                for sample in values {
                    output.extend(
                        engine
                            .on_sample(
                                "synthetic-series",
                                sample["tsMicros"].as_i64().unwrap(),
                                number(&sample["value"]),
                            )
                            .unwrap(),
                    );
                }
                let saved = engine.snapshot().unwrap();
                let serialized = serde_json::to_vec(&saved).unwrap();
                let mut restored = StatsEngine::new(
                    windows.clone(),
                    number(&case["alpha"]),
                    Some(case["expectedIntervalMicros"].as_i64().unwrap()),
                    0,
                )
                .unwrap();
                restored
                    .restore(&serde_json::from_slice(&serialized).unwrap())
                    .unwrap();
                assert_eq!(saved, restored.snapshot().unwrap());
                engine = restored;
            }
            output.extend(engine.flush().unwrap());
            let actual: Vec<_> = output.iter().map(|v| v.vector_json().unwrap()).collect();
            assert_eq!(
                Value::Array(actual),
                case["expect"],
                "{} chunk{chunk}",
                case["name"]
            );
        }
    }
}

#[test]
fn invalid_checkpoints_cannot_change_configuration_or_candidate_state() {
    let mut engine = StatsEngine::new(
        vec![("PT10M".into(), 600_000_000)],
        0.01,
        Some(1_000_000),
        0,
    )
    .unwrap();
    engine.on_sample("a", 0, 1.0).unwrap();
    let saved = engine.snapshot().unwrap();
    let mut changes = Vec::new();
    let mut config = saved.clone();
    config["engine"]["windows"][0][1] = 1.into();
    changes.push(config);
    let mut alpha = saved.clone();
    alpha["engine"]["alpha"] = "0000000000000000".into();
    changes.push(alpha);
    let mut count = saved.clone();
    count["engine"]["series"]["a"]["global"]["sketch"]["count"] = 2.into();
    changes.push(count);
    let mut bins = saved.clone();
    bins["engine"]["series"]["a"]["global"]["sketch"]["positive"]["0"] = 0.into();
    changes.push(bins);
    let mut closed = saved.clone();
    closed["engine"]["series"]["a"]["closed_up_to"]["1"] = 1.into();
    changes.push(closed);
    for change in changes {
        assert!(engine.restore(&change).is_err());
        assert_eq!(engine.snapshot().unwrap(), saved);
    }
}

#[test]
fn statistics_soe_round_trips_nonfinite_and_signed_zero_without_trailing_bytes() {
    let mut accumulator = StatsAccumulator::new(0.01).unwrap();
    for (index, value) in [f64::NAN, f64::INFINITY, -0.0, 0.0, -1.0, 3.0]
        .into_iter()
        .enumerate()
    {
        accumulator.add(index as i64, value).unwrap();
    }
    let record = accumulator.to_record("0123456789abcdef", "PT10M", 0, 600_000_000);
    let bytes = record.encode_soe().unwrap();
    let decoded = tsx_runner::stats::StatsRecord::decode_soe(&bytes, 1_048_576).unwrap();
    assert_eq!(
        decoded.vector_json().unwrap(),
        record.vector_json().unwrap()
    );
    assert_eq!(decoded.encode_soe().unwrap(), bytes);
    let mut extra = bytes.clone();
    extra.push(0);
    assert!(tsx_runner::stats::StatsRecord::decode_soe(&extra, 1_048_576).is_err());
    assert!(tsx_runner::stats::StatsRecord::decode_soe(&bytes, bytes.len() - 1).is_err());
    for count in 0..bytes.len() {
        assert!(
            tsx_runner::stats::StatsRecord::decode_soe(&bytes[..count], 1_048_576).is_err(),
            "prefix{count}"
        );
    }
    let empty =
        StatsAccumulator::new(0.0)
            .unwrap()
            .to_record("0123456789abcdef", "PT10M", 0, 600_000_000);
    let raw = empty.encode_soe().unwrap();
    assert!(tsx_runner::stats::StatsRecord::decode_soe(&raw, 1_048_576)
        .unwrap()
        .mean
        .is_nan());
}

#[test]
fn dq_signal_ids_preserve_checks_windows_and_java_instant_formatting() {
    let mut accumulator = StatsAccumulator::new(0.01).unwrap();
    accumulator.add(0, 2.0).unwrap();
    let stats = accumulator.to_record("0123456789abcdef", "PT10M", 1_000, 1_000_001);
    let expectation = Expectation::from_json(
        &serde_json::json!({"expectedIntervalMicros":1,"flatlineCheck":false}),
    )
    .unwrap();
    let findings = evaluate(&stats, &expectation, None).unwrap();
    assert_eq!(findings.len(), 2);
    let signals: Vec<_> = findings
        .iter()
        .map(|v| v.signal("job", "site", &"a".repeat(64)).unwrap())
        .collect();
    assert_ne!(signals[0]["id"], signals[1]["id"]);
    assert_eq!(signals[0]["window"]["from"], "1970-01-01T00:00:00.001Z");
    assert_eq!(signals[0]["at"], "1970-01-01T00:00:01.000001Z");
    let identity = "job|site|0123456789abcdef|dq|1970-01-01T00:00:01.000001Z|COMPLETENESS|1970-01-01T00:00:00.001Z|1970-01-01T00:00:01.000001Z";
    assert_eq!(
        signals[0]["id"],
        tsx_runner::model::xxh64_hex(identity.as_bytes())
    );
    let mut different = findings[0].clone();
    different.window_start_micros = 0;
    assert_ne!(
        signals[0]["id"],
        different.signal("job", "site", &"a".repeat(64)).unwrap()["id"]
    );
}

#[test]
fn sketch_fraction_boundaries_merge_and_malformed_input_are_checked() {
    let mut sketch = QuantileSketch::new(0.01).unwrap();
    for value in [
        -10.0,
        -1.0,
        -1e-12,
        0.0,
        1e-12,
        1.0,
        10.0,
        f64::NAN,
        f64::INFINITY,
    ] {
        sketch.add(value).unwrap();
    }
    assert_eq!(sketch.count(), 7);
    assert_eq!(sketch.fraction_below(0.0), 2.0 / 7.0);
    assert_eq!(sketch.fraction_above(0.0), 2.0 / 7.0);
    assert_eq!(sketch.fraction_below(-1.0), 1.0 / 7.0);
    assert_eq!(sketch.fraction_above(1.0), 1.0 / 7.0);
    let mut other = QuantileSketch::new(0.01).unwrap();
    other.add(100.0).unwrap();
    sketch.merge(&other).unwrap();
    assert_eq!(sketch.count(), 8);
    assert_eq!(
        QuantileSketch::from_bytes(&sketch.to_bytes())
            .unwrap()
            .to_bytes(),
        sketch.to_bytes()
    );
    assert!(sketch.merge(&QuantileSketch::new(0.02).unwrap()).is_err());
    assert!(sketch.quantile(f64::NAN).is_err());
    let bytes = sketch.to_bytes();
    for n in 0..bytes.len() {
        assert!(QuantileSketch::from_bytes(&bytes[..n]).is_err());
    }
    let mut extra = bytes;
    extra.push(0);
    assert!(QuantileSketch::from_bytes(&extra).is_err());
}

#[test]
fn every_shared_statistics_case_matches_in_all_six_chunk_and_series_orders() {
    let document = vectors("stats-window");
    for case in document["cases"].as_array().unwrap() {
        let samples = case["samples"].as_array().unwrap();
        let windows: Vec<_> = case["windows"]
            .as_array()
            .unwrap()
            .iter()
            .map(|v| {
                let name = v.as_str().unwrap();
                (name.to_owned(), duration(name))
            })
            .collect();
        let mut expected = Vec::new();
        for id in ["a", "b"] {
            for record in case["expect"].as_array().unwrap() {
                let mut record = record.clone();
                record["seriesId"] = id.into();
                expected.push(record);
            }
        }
        let key = |value: &Value| {
            (
                value["seriesId"].as_str().unwrap().to_owned(),
                value["windowType"].as_str().unwrap().to_owned(),
                value["windowStartMicros"].as_i64().unwrap(),
            )
        };
        expected.sort_by_key(&key);
        for order in [["a", "b"], ["b", "a"]] {
            for chunk in [1, 7, samples.len().max(1)] {
                let mut actual = Vec::new();
                if case["mode"] == "accumulator" {
                    for id in order {
                        let mut accumulator =
                            StatsAccumulator::new(number(&case["alpha"])).unwrap();
                        for input in samples.chunks(chunk) {
                            for sample in input {
                                accumulator
                                    .add(
                                        sample["tsMicros"].as_i64().unwrap(),
                                        number(&sample["value"]),
                                    )
                                    .unwrap();
                            }
                            accumulator =
                                serde_json::from_slice(&serde_json::to_vec(&accumulator).unwrap())
                                    .unwrap();
                        }
                        for (name, end) in &windows {
                            actual.push(
                                accumulator
                                    .to_record(id, name, 0, *end)
                                    .vector_json()
                                    .unwrap(),
                            );
                        }
                    }
                } else {
                    let mut engine = StatsEngine::new(
                        windows.clone(),
                        number(&case["alpha"]),
                        Some(case["expectedIntervalMicros"].as_i64().unwrap()),
                        0,
                    )
                    .unwrap();
                    for id in order {
                        for input in samples.chunks(chunk) {
                            for sample in input {
                                actual.extend(
                                    engine
                                        .on_sample(
                                            id,
                                            sample["tsMicros"].as_i64().unwrap(),
                                            number(&sample["value"]),
                                        )
                                        .unwrap()
                                        .iter()
                                        .map(|r| r.vector_json().unwrap()),
                                );
                            }
                            let saved = engine.snapshot().unwrap();
                            let mut restored = StatsEngine::new(
                                windows.clone(),
                                number(&case["alpha"]),
                                Some(case["expectedIntervalMicros"].as_i64().unwrap()),
                                0,
                            )
                            .unwrap();
                            restored
                                .restore(
                                    &serde_json::from_slice(&serde_json::to_vec(&saved).unwrap())
                                        .unwrap(),
                                )
                                .unwrap();
                            engine = restored;
                        }
                    }
                    actual.extend(
                        engine
                            .flush()
                            .unwrap()
                            .iter()
                            .map(|r| r.vector_json().unwrap()),
                    );
                }
                actual.sort_by_key(&key);
                assert_eq!(
                    actual, expected,
                    "{} chunk{chunk} order{order:?}",
                    case["name"]
                );
            }
        }
    }
}

#[test]
fn episode_deduplication_late_values_and_learned_gaps_survive_restore() {
    use std::collections::BTreeMap;
    use tsx_runner::model::{self, Episode, Provenance, SeriesKey, TimeEncoding};
    let values = vec![1.0, 2.0, 3.0];
    let tags = BTreeMap::new();
    let series = SeriesKey {
        metric: "fixture.value".into(),
        series_id: model::series_id("fixture.value", &tags).unwrap(),
        tags,
        unit: None,
    };
    let mut episode = Episode {
        episode_id: "episode-one".into(),
        series,
        t_start: 0,
        t_end: 3_000_000,
        count: 3,
        time_encoding: TimeEncoding::Regular,
        interval_micros: Some(1_000_000),
        time_deltas: None,
        summary: model::summary(&values),
        values,
        quality: None,
        segmentation: None,
        labels: BTreeMap::new(),
        bucket_id: None,
        revision: 0,
        provenance: Provenance {
            source: "fixture".into(),
            producer: "fixture".into(),
            created_at: 0,
            pipeline: None,
            parent_episode_id: None,
        },
    };
    let mut engine = StatsEngine::new(vec![("PT2S".into(), 2_000_000)], 0.01, None, 0).unwrap();
    assert_eq!(engine.on_episode(&episode).unwrap().len(), 1);
    let saved = engine.snapshot().unwrap();
    let mut restored = StatsEngine::new(vec![("PT2S".into(), 2_000_000)], 0.01, None, 0).unwrap();
    restored.restore(&saved).unwrap();
    assert!(restored.on_episode(&episode).unwrap().is_empty());
    assert_eq!(restored.snapshot().unwrap(), saved);
    episode.episode_id = "episode-two".into();
    episode.t_start = 5_000_000;
    episode.t_end = 8_000_000;
    let closed = restored.on_episode(&episode).unwrap();
    assert_eq!(closed.len(), 3);
    let mut global = restored.emit_global().unwrap().remove(0);
    assert_eq!(global.valid_count, 6);
    assert_eq!(global.gap_count, 1);
    assert!(restored
        .on_sample(&episode.series.series_id, 0, 9.0)
        .unwrap()
        .is_empty());
    global = restored.emit_global().unwrap().remove(0);
    assert_eq!(global.valid_count, 7);
    assert_eq!(global.gap_count, 1);
}

#[test]
fn statistics_wire_boundary_rejects_inconsistent_counts_extrema_and_timestamps() {
    let mut accumulator = StatsAccumulator::new(0.01).unwrap();
    accumulator.add(1_000_000, 2.0).unwrap();
    let original = accumulator.to_record("0123456789abcdef", "PT10M", 0, 600_000_000);
    let mut count = original.clone();
    count.valid_count = 2;
    assert!(count.encode_soe().is_err());
    let mut empty = original.clone();
    empty.valid_count = 0;
    assert!(empty.encode_soe().is_err());
    let mut extrema = original.clone();
    extrema.max = 1.0;
    assert!(extrema.encode_soe().is_err());
    let mut last = original.clone();
    last.last_seen_micros = Some(600_000_000);
    assert!(last.encode_soe().is_err());
    let mut missing = original.clone();
    missing.last_seen_micros = None;
    assert!(missing.encode_soe().is_err());
    let mut bytes = original.encode_soe().unwrap();
    fn read(bytes: &[u8], position: &mut usize) -> i64 {
        let mut raw = 0u64;
        let mut shift = 0;
        loop {
            let value = bytes[*position];
            *position += 1;
            raw |= u64::from(value & 127) << shift;
            if value & 128 == 0 {
                break;
            }
            shift += 7;
        }
        ((raw >> 1) as i64) ^ -((raw & 1) as i64)
    }
    let mut position = 10;
    for _ in 0..2 {
        let length = read(&bytes, &mut position) as usize;
        position += length;
    }
    read(&bytes, &mut position);
    read(&bytes, &mut position);
    bytes[position] = 0;
    assert!(tsx_runner::stats::StatsRecord::decode_soe(&bytes, 8192).is_err());
}

#[test]
fn every_shared_dq_case_matches_six_atomic_record_chunk_and_series_orders() {
    let document = vectors("dq-result");
    let cases = document["cases"].as_array().unwrap();
    assert_eq!(cases.len(), 7);
    for case in cases {
        let expectation = Expectation::from_json(&case["expectation"]).unwrap();
        let mut expected = Vec::new();
        for id in ["0123456789abcdef", "fedcba9876543210"] {
            for _ in 0..4 {
                for finding in case["expect"].as_array().unwrap() {
                    let mut finding = finding.clone();
                    finding["seriesId"] = id.into();
                    expected.push(finding);
                }
            }
        }
        expected.sort_by_key(Value::to_string);
        for order in [
            ["0123456789abcdef", "fedcba9876543210"],
            ["fedcba9876543210", "0123456789abcdef"],
        ] {
            let input: Vec<_> = order
                .iter()
                .flat_map(|id| (0..4).map(move |_| dq_record(case, id)))
                .collect();
            assert_eq!(input.len(), 8);
            for chunk in [1, 7, input.len()] {
                let mut actual = Vec::new();
                let mut processed = 0;
                for records in input.chunks(chunk) {
                    for record in records {
                        actual.extend(
                            evaluate(record, &expectation, case["nowMicros"].as_i64())
                                .unwrap()
                                .iter()
                                .map(|finding| finding.vector_json()),
                        );
                        processed += 1;
                    }
                }
                assert_eq!(processed, 8);
                actual.sort_by_key(Value::to_string);
                assert_eq!(
                    actual, expected,
                    "{} chunk{chunk} order{order:?}",
                    case["name"]
                );
            }
        }
    }
}
