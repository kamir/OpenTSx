use serde_json::{json, Value};
use std::collections::{BTreeMap, BTreeSet};
use std::path::Path;
use time::{format_description::well_known::Rfc3339, OffsetDateTime};
use tsx_runner::model::{self, Episode, Provenance, SeriesKey, TimeEncoding};
use tsx_runner::pairs::{
    cross_correlation, lags_of, peak_of, xcorr_windows, PairParams, PairStream, VectorParams,
    VectorStream,
};

fn vectors(name: &str) -> Value {
    let root = Path::new(env!("CARGO_MANIFEST_DIR")).join("../..");
    serde_json::from_slice(
        &std::fs::read(root.join("docs/datamodel-2026/testvectors").join(name)).unwrap(),
    )
    .unwrap()
}

fn values(node: &Value) -> Vec<f64> {
    node.as_array()
        .unwrap()
        .iter()
        .map(|x| x.as_f64().unwrap_or(f64::NAN))
        .collect()
}

fn close(actual: f64, expected: &Value) {
    if expected.is_null() {
        assert!(actual.is_nan());
    } else {
        let expected = expected.as_f64().unwrap();
        assert!(
            (actual - expected).abs() <= 5e-13,
            "{actual:.17} != {expected:.17}"
        );
    }
}

fn vector_case(index: usize) {
    let all = vectors("align-vector.v1.json");
    assert_eq!(all["cases"].as_array().unwrap().len(), 6);
    let case = &all["cases"][index];
    let inputs = case["inputs"].as_array().unwrap();
    let expected: Vec<_> = inputs
        .iter()
        .map(|input| {
            (
                input["name"].as_str().unwrap().to_owned(),
                input["series"]
                    .as_array()
                    .unwrap()
                    .iter()
                    .map(|s| s["id"].as_str().unwrap().to_owned())
                    .collect::<BTreeSet<_>>(),
            )
        })
        .collect();
    let mut variants = vec![(case["params"].clone(), case["expect"].clone())];
    if let Some(extra) = case["also"].as_object() {
        for (agg, result) in extra {
            let mut params = case["params"].clone();
            params["agg"] = json!(agg);
            let mut expected = case["expect"].clone();
            expected[0]["values"] = result.clone();
            variants.push((params, expected));
        }
    }
    for (params, wanted) in variants {
        for chunk in [1usize, 7, usize::MAX] {
            for reverse in [false, true] {
                let p = VectorParams::parse(&params).unwrap();
                let mut stream = VectorStream::new(expected.clone(), p.clone(), 100_000).unwrap();
                let mut blocks = Vec::new();
                for input in inputs {
                    for series in input["series"].as_array().unwrap() {
                        let t: Vec<_> = series["t"]
                            .as_array()
                            .unwrap()
                            .iter()
                            .map(|x| (x.as_f64().unwrap() * 1e6).round() as i64)
                            .collect();
                        let v = values(&series["v"]);
                        let tags: BTreeMap<String, String> =
                            serde_json::from_value(series["tags"].clone()).unwrap();
                        blocks.push((
                            input["name"].as_str().unwrap(),
                            series["id"].as_str().unwrap(),
                            series["metric"].as_str().unwrap(),
                            tags,
                            t,
                            v,
                        ));
                    }
                }
                if reverse {
                    blocks.reverse();
                }
                let mut output = Vec::new();
                for (input, id, metric, tags, t, v) in blocks {
                    for start in (0..v.len()).step_by(chunk.min(v.len()).max(1)) {
                        let end = start.saturating_add(chunk).min(v.len());
                        let progress = t[end - 1] + 1;
                        output.extend(
                            stream
                                .add(
                                    input,
                                    id,
                                    metric,
                                    &tags,
                                    &t[start..end],
                                    &v[start..end],
                                    progress,
                                )
                                .unwrap(),
                        );
                        let state: Value = serde_json::from_slice(
                            &serde_json::to_vec(&stream.snapshot().unwrap()).unwrap(),
                        )
                        .unwrap();
                        let mut restored =
                            VectorStream::new(expected.clone(), p.clone(), 100_000).unwrap();
                        restored.restore(&state).unwrap();
                        stream = restored;
                    }
                }
                output.extend(stream.flush().unwrap());
                assert_eq!(
                    stream.retained_series_by_input().unwrap(),
                    expected.iter().cloned().collect()
                );
                let actual_groups: BTreeSet<_> = output
                    .iter()
                    .map(|point| model::xxh64_hex(tsx_runner::pairs::vector_key(point).as_bytes()))
                    .collect();
                assert_eq!(stream.possible_output_series().unwrap(), actual_groups);
                let mut actual: Vec<_> = output.iter().map(|p| json!({"key":p.key,"from":p.from_micros as f64/1e6,"components":p.components,"values":p.values})).collect();
                actual.sort_by_key(|x| (x["key"].to_string(), x["from"].as_f64().unwrap() as i64));
                assert_eq!(
                    json!(actual),
                    numeric_json(&wanted),
                    "{} chunk={chunk} reverse={reverse}",
                    case["name"]
                );
            }
        }
    }
}

#[test]
fn vector_shared_case_1() {
    vector_case(0);
}
#[test]
fn vector_shared_case_2() {
    vector_case(1);
}
#[test]
fn vector_shared_case_3() {
    vector_case(2);
}
#[test]
fn vector_shared_case_4() {
    vector_case(3);
}
#[test]
fn vector_shared_case_5() {
    vector_case(4);
}
#[test]
fn vector_shared_case_6() {
    vector_case(5);
}

#[test]
fn vector_fill_other_than_none_or_as_of_is_refused() {
    assert_eq!(
        VectorParams::parse(&json!({"every":60,"fill":"asof"})).unwrap_err(),
        "vector fill must be none or as-of"
    );
    assert!(!VectorParams::parse(&json!({"every":60})).unwrap().as_of);
    assert!(
        !VectorParams::parse(&json!({"every":60,"fill":null}))
            .unwrap()
            .as_of
    );
    assert!(
        VectorParams::parse(&json!({"every":60,"fill":"as-of"}))
            .unwrap()
            .as_of
    );
}

fn xcorr_case(index: usize) {
    let all = vectors("pair-xcorr.v1.json");
    assert_eq!(all["cases"].as_array().unwrap().len(), 5);
    let c = &all["cases"][index];
    let params = json!({"lags":c["lags"],"window":if c["window"].is_null(){json!("episode")}else{c["window"].clone()},"step":c["step"],"minCoverage":0.8});
    let params = PairParams::parse(&params).unwrap();
    let actual = xcorr_windows(
        &values(&c["a"]),
        &values(&c["b"]),
        0.0,
        1.0,
        &params,
        "cross",
        10_000,
    )
    .unwrap();
    let expected = c
        .get("windows")
        .or_else(|| c.get("sliding"))
        .unwrap()
        .as_array()
        .unwrap();
    assert_eq!(
        actual.len(),
        expected.len() + usize::from(c.get("sliding").is_some())
    );
    for (w, e) in actual.iter().zip(expected) {
        close(w.centre, &e["centre"]);
        if let Some(cc) = e["cc"].as_array() {
            assert_eq!(w.cc.len(), cc.len());
            for (a, b) in w.cc.iter().zip(cc) {
                close(*a, b);
            }
            let (index, peak, _) = peak_of(&values(&e["cc"]));
            close(w.max, &json!(peak));
            close(w.lag, &json!(params.lags as i64 - index as i64));
            if peak > 0.0 {
                close(w.max, &e["tMax"]);
                close(w.lag, &json!(-e["ttMax"].as_f64().unwrap()));
            }
            close(w.strength, &e["strength"]);
        } else {
            assert_eq!(w.cc.len(), 1);
            close(w.cc[0], &e["cc"]);
        }
    }
    if c.get("sliding").is_some() {
        assert_eq!(actual[48].centre, 56.0);
        close(
            actual[48].max,
            &json!(
                cross_correlation(
                    &values(&c["a"])[48..64],
                    &values(&c["b"])[48..64],
                    0,
                    10_000
                )
                .unwrap()[0]
            ),
        );
        assert!(actual.iter().all(|w| w.strength.is_nan()));
    }
}

fn numeric_json(value: &Value) -> Value {
    match value {
        Value::Number(n) => json!(n.as_f64().unwrap()),
        Value::Array(v) => Value::Array(v.iter().map(numeric_json).collect()),
        Value::Object(v) => Value::Object(
            v.iter()
                .map(|(k, v)| (k.clone(), numeric_json(v)))
                .collect(),
        ),
        other => other.clone(),
    }
}

#[test]
fn xcorr_shared_case_1() {
    xcorr_case(0);
}
#[test]
fn xcorr_shared_case_2() {
    xcorr_case(1);
}
#[test]
fn xcorr_shared_case_3() {
    xcorr_case(2);
}
#[test]
fn xcorr_shared_case_4() {
    xcorr_case(3);
}
#[test]
fn xcorr_shared_case_5() {
    xcorr_case(4);
}

#[test]
fn xcorr_lag_validation_and_site_product_limit_are_exact() {
    for (value, text) in [(json!(-1), "-1"), (json!(0.5), "0.5")] {
        assert_eq!(
            lags_of(Some(&value)).unwrap_err(),
            format!("lags \"{text}\" is not a whole number of clock ticks, 0 or more.")
        );
    }
    let p = PairParams::parse(&json!({"lags":5,"window":100,"every":1})).unwrap();
    assert_eq!(
        xcorr_windows(&vec![1.0; 100], &vec![1.0; 100], 0.0, 1.0, &p, "xc", 1000).unwrap_err(),
        "node xc: pair.xcorr exceeds the limit of this site (1000 products per window)"
    );
    let p = PairParams::parse(&json!({"lags":4,"window":100,"every":1})).unwrap();
    assert_eq!(
        xcorr_windows(&vec![1.0; 100], &vec![1.0; 100], 0.0, 1.0, &p, "xc", 1000)
            .unwrap()
            .len(),
        1
    );
    let p = PairParams::parse(&json!({"lags":2,"window":200,"every":1})).unwrap();
    assert_eq!(
        xcorr_windows(&vec![1.0; 200], &vec![1.0; 200], 0.0, 1.0, &p, "xc", 1000)
            .unwrap()
            .len(),
        1
    );
}

fn episode(name: &str, values: Vec<f64>, start: i64) -> Episode {
    let tags = BTreeMap::from([("sensor".into(), name.into())]);
    let metric = "m.wind".to_owned();
    Episode {
        episode_id: format!("{name}-{start}"),
        series: SeriesKey {
            series_id: model::series_id(&metric, &tags).unwrap(),
            metric,
            tags,
            unit: None,
        },
        t_start: start,
        t_end: start + values.len() as i64 * 1_000_000,
        count: values.len() as i32,
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
            source: "test".into(),
            producer: "test".into(),
            created_at: 1,
            pipeline: None,
            parent_episode_id: None,
        },
    }
}

#[test]
fn pair_episodes_have_stable_ids_and_checkpointed_input_order_parity() {
    let c = &vectors("pair-xcorr.v1.json")["cases"][3];
    let a = episode("a", values(&c["a"]), 1_000_000);
    let b = episode("b", values(&c["b"]), 1_000_000);
    let params =
        PairParams::parse(&json!({"lags":c["lags"],"window":c["window"],"step":c["step"]}))
            .unwrap();
    let expected_a = BTreeSet::from([a.series.series_id.clone()]);
    let expected_b = BTreeSet::from([b.series.series_id.clone()]);
    let make = || {
        PairStream::new(
            "xc".into(),
            params.clone(),
            expected_a.clone(),
            expected_b.clone(),
            false,
            "a".repeat(64),
            123,
            1000,
            100_000,
        )
        .unwrap()
    };
    let mut baseline = None;
    for reverse in [false, true] {
        for chunk in [1usize, 7, usize::MAX] {
            let mut stream = make();
            let entries = if reverse {
                vec![("b", &b), ("a", &a)]
            } else {
                vec![("a", &a), ("b", &b)]
            };
            let mut out = Vec::new();
            for group in entries.chunks(chunk.min(entries.len())) {
                for (input, e) in group {
                    out.extend(stream.add(input, e).unwrap());
                }
                let state: Value = serde_json::from_slice(
                    &serde_json::to_vec(&stream.snapshot().unwrap()).unwrap(),
                )
                .unwrap();
                let mut restored = make();
                restored.restore(&state).unwrap();
                stream = restored;
            }
            assert_eq!(out.len(), 3);
            out.sort_by(|x, y| x.series.metric.cmp(&y.series.metric));
            for e in &out {
                let identity = format!(
                    "{}|{}|{}|{}",
                    e.series.series_id,
                    a.episode_id,
                    b.episode_id,
                    "a".repeat(64)
                );
                assert_eq!(
                    e.episode_id,
                    format!("p{}", model::xxh64_hex(identity.as_bytes()))
                );
                assert_eq!(e.labels["pair.a"], a.episode_id);
                assert_eq!(e.labels["pair.b"], b.episode_id);
                assert_eq!(e.quality.as_ref().unwrap().len(), 5);
                e.validate(100_000).unwrap();
            }
            assert_eq!(
                stream.retained_series_by_input().unwrap(),
                BTreeMap::from([
                    ("a".into(), expected_a.clone()),
                    ("b".into(), expected_b.clone())
                ])
            );
            assert_eq!(
                stream
                    .possible_output_series(&expected_a, &expected_b, 3)
                    .unwrap(),
                out.iter().map(|e| e.series.series_id.clone()).collect()
            );
            assert!(stream
                .possible_output_series(&expected_a, &expected_b, 2)
                .is_err());
            let encoded = serde_json::to_value(&out).unwrap();
            if let Some(first) = &baseline {
                assert_eq!(&encoded, first);
            } else {
                baseline = Some(encoded);
            }
        }
    }
}

#[test]
fn vector_restore_and_invalid_blocks_leave_state_unchanged() {
    let p = VectorParams::parse(&json!({"every":1,"key":"series"})).unwrap();
    let expected = vec![("a".into(), BTreeSet::from(["s".into()]))];
    let mut s = VectorStream::new(expected, p, 100).unwrap();
    let before = s.snapshot().unwrap();
    assert!(s
        .add("a", "s", "m", &BTreeMap::new(), &[1, 0], &[1.0, 2.0], 2)
        .is_err());
    assert_eq!(before, s.snapshot().unwrap());
    let mut bad = before.clone();
    bad["maxSamples"] = json!(101);
    assert!(s.restore(&bad).is_err());
    assert_eq!(before, s.snapshot().unwrap());
}

#[test]
fn vector_wire_record_uses_the_java_key_and_timestamp_contract() {
    use tsx_runner::pairs::{vector_json, vector_key, VectorPoint};
    let point = VectorPoint {
        key: BTreeMap::from([("z".into(), "a=b".into()), ("a".into(), "x,y".into())]),
        from_micros: -1,
        to_micros: 1_000,
        components: vec!["a".into(), "b".into()],
        values: vec![Some(1.25), None],
    };
    assert_eq!(vector_key(&point), "a=x,y,z=a=b");
    assert_eq!(
        vector_json(&point, "v", "dag", &"a".repeat(64)).unwrap(),
        json!({
        "schema":"opentsx.vector.v1","vector":"v","key":{"a":"x,y","z":"a=b"},"ts":"1970-01-01T00:00:00.001Z",
        "window":{"from":"1969-12-31T23:59:59.999999Z","to":"1970-01-01T00:00:00.001Z"},"clock":"PT0.001001S",
        "components":["a","b"],"values":[1.25,null],"missing":1,"provenance":{"dag":"dag","specHash":"a".repeat(64)}})
    );
}

#[test]
fn lag_error_number_formatting_matches_the_workbench() {
    for (raw, text) in [
        (json!(1e-7), "1e-7"),
        (json!(-1e21), "-1e+21"),
        (json!(-1e20), "-100000000000000000000"),
        (json!("ten"), "ten"),
        (json!("2.50"), "2.50"),
    ] {
        assert_eq!(
            lags_of(Some(&raw)).unwrap_err(),
            format!("lags \"{text}\" is not a whole number of clock ticks, 0 or more.")
        );
    }
    assert_eq!(
        lags_of(Some(&json!("\u{00a0}\u{feff}2\u{202f}"))).unwrap(),
        2
    );
}

#[test]
fn within_pairing_retains_the_declared_series_order_after_restore() {
    let a = episode("a", vec![1.0, 2.0, 3.0, 4.0], 0);
    let b = episode("b", vec![2.0, 3.0, 4.0, 5.0], 0);
    let ids = BTreeSet::from([a.series.series_id.clone(), b.series.series_id.clone()]);
    let mut order: Vec<_> = ids.iter().cloned().collect();
    order.reverse();
    let make = || {
        let mut p = PairStream::new(
            "xc".into(),
            PairParams::parse(&json!({"lags":0})).unwrap(),
            ids.clone(),
            ids.clone(),
            true,
            "a".repeat(64),
            123,
            1000,
            100,
        )
        .unwrap();
        p.set_within_order(&order).unwrap();
        p
    };
    for inputs in [[&a, &b], [&b, &a]] {
        let mut p = make();
        assert!(p.add("a", inputs[0]).unwrap().is_empty());
        let state: Value =
            serde_json::from_slice(&serde_json::to_vec(&p.snapshot().unwrap()).unwrap()).unwrap();
        p = make();
        p.restore(&state).unwrap();
        let output = p.add("a", inputs[1]).unwrap();
        assert_eq!(output.len(), 3);
        for e in output {
            assert_eq!(&e.series.tags["a"], &order[0]);
            assert_eq!(&e.series.tags["b"], &order[1]);
        }
    }
}

#[test]
fn all_five_xcorr_vectors_match_in_six_atomic_episode_chunk_and_series_orders() {
    let vectors = vectors("pair-xcorr.v1.json");
    assert_eq!(vectors["cases"].as_array().unwrap().len(), 5);
    for case in vectors["cases"].as_array().unwrap() {
        let av = values(&case["a"]);
        let bv = values(&case["b"]);
        let params = PairParams::parse(
            &json!({"lags":case["lags"],"window":case["window"],"step":case["step"]}),
        )
        .unwrap();
        let a: Vec<_> = (0..8)
            .map(|index| {
                episode(
                    "a",
                    av.clone(),
                    1_000_000 + index * (av.len() as i64 + 1) * 1_000_000,
                )
            })
            .collect();
        let b: Vec<_> = (0..8)
            .map(|index| {
                episode(
                    "b",
                    bv.clone(),
                    1_000_000 + index * (bv.len() as i64 + 1) * 1_000_000,
                )
            })
            .collect();
        let make = || {
            PairStream::new(
                "xc".into(),
                params.clone(),
                BTreeSet::from([a[0].series.series_id.clone()]),
                BTreeSet::from([b[0].series.series_id.clone()]),
                false,
                "a".repeat(64),
                123,
                10_000,
                100_000,
            )
            .unwrap()
        };
        let mut batch = make();
        let mut baseline = Vec::new();
        for (side, episodes) in [("a", &a), ("b", &b)] {
            for episode in episodes {
                baseline.extend(batch.add(side, episode).unwrap());
            }
        }
        baseline.sort_by(|a, b| a.episode_id.cmp(&b.episode_id));
        let baseline = serde_json::to_value(&baseline).unwrap();
        for reverse in [false, true] {
            let first = if reverse { ("b", &b) } else { ("a", &a) };
            let second = if reverse { ("a", &a) } else { ("b", &b) };
            let input: Vec<_> = [first, second]
                .into_iter()
                .flat_map(|(side, episodes)| episodes.iter().map(move |episode| (side, episode)))
                .collect();
            for chunk in [1usize, 7, input.len()] {
                let mut stream = make();
                let mut output = Vec::new();
                for cut in input.chunks(chunk) {
                    for (side, episode) in cut {
                        output.extend(stream.add(side, episode).unwrap());
                    }
                    let state: Value = serde_json::from_slice(
                        &serde_json::to_vec(&stream.snapshot().unwrap()).unwrap(),
                    )
                    .unwrap();
                    let mut restored = make();
                    restored.restore(&state).unwrap();
                    stream = restored;
                }
                assert_eq!(
                    output.len(),
                    24,
                    "{} chunk={chunk} reverse={reverse}",
                    case["name"]
                );
                output.sort_by(|a, b| a.episode_id.cmp(&b.episode_id));
                let actual = serde_json::to_value(&output).unwrap();
                assert_eq!(
                    actual, baseline,
                    "{} chunk={chunk} reverse={reverse}",
                    case["name"]
                );
            }
        }
    }
}

/// An episode of pair-clock.v1.json, given in the JSON form of GET /v1/episodes.
fn clock_episode(e: &Value) -> Episode {
    let micros = |key: &str| {
        let instant = OffsetDateTime::parse(e[key].as_str().unwrap(), &Rfc3339).unwrap();
        i64::try_from(instant.unix_timestamp_nanos() / 1000).unwrap()
    };
    let times: Vec<i64> = e["timestampsMicros"]
        .as_array()
        .unwrap()
        .iter()
        .map(|t| t.as_i64().unwrap())
        .collect();
    let values = values(&e["values"]);
    let interval = e["intervalMicros"].as_i64();
    let mut deltas = Vec::new();
    let mut previous = 0;
    for i in 1..times.len() {
        let delta = times[i] - times[i - 1];
        let value = if i == 1 { delta } else { delta - previous };
        let mut encoded = ((value << 1) ^ (value >> 63)) as u64;
        while encoded >= 128 {
            deltas.push(encoded as u8 | 128);
            encoded >>= 7;
        }
        deltas.push(encoded as u8);
        previous = delta;
    }
    let tags = BTreeMap::new();
    let metric = e["metric"].as_str().unwrap().to_owned();
    let episode = Episode {
        episode_id: e["episodeId"].as_str().unwrap().into(),
        series: SeriesKey {
            series_id: model::series_id(&metric, &tags).unwrap(),
            metric,
            tags,
            unit: None,
        },
        t_start: micros("tStart"),
        t_end: micros("tEnd"),
        count: values.len() as i32,
        time_encoding: if interval.is_some() {
            TimeEncoding::Regular
        } else {
            TimeEncoding::IrregularDelta
        },
        interval_micros: interval,
        time_deltas: interval.is_none().then_some(deltas),
        summary: model::summary(&values),
        values,
        quality: None,
        segmentation: None,
        labels: BTreeMap::new(),
        bucket_id: None,
        revision: 0,
        provenance: Provenance {
            source: "test".into(),
            producer: "test".into(),
            created_at: 1,
            pipeline: None,
            parent_episode_id: None,
        },
    };
    assert_eq!(episode.timestamps().unwrap(), times);
    episode
}

#[test]
fn default_clock_vectors_take_the_clock_of_each_episode_pair() {
    let all = vectors("pair-clock.v1.json");
    assert_eq!(all["schema"], "opentsx.testvectors.pair-clock.v1");
    let cases = all["cases"].as_array().unwrap();
    assert_eq!(cases.len(), 3);
    for c in cases {
        let episodes = |side: &str| -> Vec<Episode> {
            c[side]
                .as_array()
                .unwrap()
                .iter()
                .map(clock_episode)
                .collect()
        };
        let (a, b) = (episodes("a"), episodes("b"));
        let mut stream = PairStream::new(
            "xc".into(),
            PairParams::parse(&c["params"]).unwrap(),
            BTreeSet::from([a[0].series.series_id.clone()]),
            BTreeSet::from([b[0].series.series_id.clone()]),
            false,
            "c".repeat(64),
            1,
            100_000,
            100_000,
        )
        .unwrap();
        let mut out = Vec::new();
        for e in &a {
            out.extend(stream.add("a", e).unwrap());
        }
        for e in &b {
            out.extend(stream.add("b", e).unwrap());
        }
        let ranges = c["ranges"].as_array().unwrap();
        assert_eq!(out.len(), 3 * ranges.len(), "{}", c["name"]);
        for e in &out {
            let r = ranges
                .iter()
                .find(|r| {
                    r["a"] == e.labels["pair.a"].as_str() && r["b"] == e.labels["pair.b"].as_str()
                })
                .unwrap();
            let expected = &r["pair.xcorr"];
            let field = e.series.metric.strip_prefix("pair.xcorr.").unwrap();
            assert_eq!(
                e.timestamps().unwrap(),
                vec![r["centreMicros"].as_i64().unwrap()]
            );
            assert_eq!(
                e.quality.as_deref(),
                Some(&[expected["quality"].as_u64().unwrap() as u8][..])
            );
            close(e.values[0], &expected[field]);
        }
    }
}

#[test]
fn default_clock_vectors_refuse_an_unknown_fill() {
    let all = vectors("pair-clock.v1.json");
    let refusals = all["refusals"].as_array().unwrap();
    assert_eq!(refusals.len(), 2);
    for r in refusals {
        assert_eq!(
            PairParams::parse(&r["params"]).unwrap_err(),
            r["error"].as_str().unwrap(),
            "{}",
            r["name"]
        );
    }
}
