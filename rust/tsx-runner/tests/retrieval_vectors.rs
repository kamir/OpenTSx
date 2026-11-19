use serde_json::{json, Value};
use std::collections::BTreeMap;
use std::path::Path;
use tsx_runner::model::{self, Episode, Provenance, SeriesKey, TimeEncoding};
use tsx_runner::retrieval::{paa, sax_breakpoints, sax_word, z_normalize, Segmenter};

fn cases(name: &str) -> Vec<Value> {
    let path = Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../../docs/datamodel-2026/testvectors")
        .join(format!("{name}.v1.json"));
    let document: Value = serde_json::from_slice(&std::fs::read(path).unwrap()).unwrap();
    assert_eq!(document["schema"], format!("opentsx.testvectors.{name}.v1"));
    document["cases"].as_array().unwrap().clone()
}

fn number(value: &Value) -> f64 {
    match value.as_str() {
        Some("NaN") => f64::NAN,
        Some("Infinity") => f64::INFINITY,
        Some("-Infinity") => f64::NEG_INFINITY,
        Some(value) => panic!("unexpected floating point value {value}"),
        None => value.to_string().parse().unwrap(),
    }
}

fn episode(case: &Value, site: &str, from: usize, to: usize) -> Episode {
    let tags = BTreeMap::from([("site".into(), site.into())]);
    let series = SeriesKey {
        series_id: model::series_id("synthetic.signal", &tags).unwrap(),
        metric: "synthetic.signal".into(),
        tags,
        unit: Some("1".into()),
    };
    let times: Vec<i64> = (from..to)
        .map(|i| case["timestampsMicros"][i].as_i64().unwrap())
        .collect();
    let values: Vec<_> = case["values"].as_array().unwrap()[from..to]
        .iter()
        .map(number)
        .collect();
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
    Episode {
        episode_id: format!("input-{site}"),
        series,
        t_start: times[0],
        t_end: times.last().unwrap() + 1,
        count: values.len() as i32,
        time_encoding: TimeEncoding::IrregularDelta,
        interval_micros: None,
        time_deltas: Some(deltas),
        summary: model::summary(&values),
        values,
        quality: None,
        segmentation: None,
        labels: BTreeMap::from([
            ("scenario".into(), "synthetic".into()),
            ("gt.phase".into(), "discard".into()),
        ]),
        bucket_id: None,
        revision: 0,
        provenance: Provenance {
            source: "synthetic-vectors".into(),
            producer: "synthetic-test".into(),
            created_at: 1,
            pipeline: None,
            parent_episode_id: None,
        },
    }
}

fn float_json(value: f64) -> Value {
    if value.is_nan() {
        json!("NaN")
    } else if value == f64::INFINITY {
        json!("Infinity")
    } else if value == f64::NEG_INFINITY {
        json!("-Infinity")
    } else {
        json!(value)
    }
}

fn records(output: &[Episode]) -> Value {
    let mut output: Vec<_> = output.iter().collect();
    output.sort_by_key(|e| (&e.series.series_id, e.t_start, &e.episode_id));
    json!(output
        .iter()
        .map(|e| {
            e.validate(100_000).unwrap();
            let segmentation = e.segmentation.as_ref().unwrap();
            json!({
                "episodeId":e.episode_id,"seriesId":e.series.series_id,
                "tStartMicros":e.t_start,"tEndMicros":e.t_end,
                "timeEncoding":e.time_encoding,"count":e.count,
                "anchorMicros":segmentation.anchor_ts,"strategy":segmentation.strategy,
                "params":segmentation.params,"timestampsMicros":e.timestamps().unwrap(),
                "values":e.values.iter().copied().map(float_json).collect::<Vec<_>>()
            })
        })
        .collect::<Vec<_>>())
}

fn segment_case(name: &str, index: usize) {
    let vectors = cases(name);
    assert_eq!(vectors.len(), 6);
    let case = &vectors[index];
    let count = case["values"].as_array().unwrap().len();
    let mut whole = Segmenter::new(
        case["op"].as_str().unwrap(),
        &case["params"],
        64,
        100_000,
        1_048_576,
    )
    .unwrap();
    let expected = whole
        .process(&[episode(case, "test", 0, count)], true)
        .unwrap();
    assert_eq!(records(&expected), case["expect"], "{}", case["name"]);
    let mut batch = Segmenter::new(
        case["op"].as_str().unwrap(),
        &case["params"],
        64,
        100_000,
        1_048_576,
    )
    .unwrap();
    let mut both = batch
        .process(
            &[
                episode(case, "test", 0, count),
                episode(case, "other", 0, count),
            ],
            true,
        )
        .unwrap();
    both.sort_by_key(|e| (e.series.series_id.clone(), e.t_start, e.episode_id.clone()));
    let expected_bytes: Vec<_> = both
        .iter()
        .map(|e| tsx_runner::wire::encode(e).unwrap())
        .collect();
    for chunk in [1, 7, count.max(1)] {
        for reverse in [false, true] {
            let mut stream = Segmenter::new(
                case["op"].as_str().unwrap(),
                &case["params"],
                64,
                100_000,
                1_048_576,
            )
            .unwrap();
            let mut output = Vec::new();
            for from in (0..count).step_by(chunk) {
                let mut input: Vec<_> = ["test", "other"]
                    .iter()
                    .map(|site| episode(case, site, from, (from + chunk).min(count)))
                    .collect();
                if reverse {
                    input.reverse();
                }
                output.extend(stream.process(&input, false).unwrap());
                let state = stream.snapshot().unwrap();
                let encoded = serde_json::to_vec(&state).unwrap();
                let mut restored = Segmenter::new(
                    case["op"].as_str().unwrap(),
                    &case["params"],
                    64,
                    100_000,
                    1_048_576,
                )
                .unwrap();
                restored
                    .restore(&serde_json::from_slice(&encoded).unwrap())
                    .unwrap();
                assert_eq!(state, restored.snapshot().unwrap());
                stream = restored;
            }
            output.extend(stream.process(&[], true).unwrap());
            output.sort_by_key(|e| (e.series.series_id.clone(), e.t_start, e.episode_id.clone()));
            assert_eq!(
                output
                    .iter()
                    .map(|e| tsx_runner::wire::encode(e).unwrap())
                    .collect::<Vec<_>>(),
                expected_bytes,
                "{}, chunk {chunk}, reverse {reverse}",
                case["name"]
            );
        }
    }
}

fn assert_numbers(actual: &[f64], expected: &Value) {
    assert_eq!(actual.len(), expected.as_array().unwrap().len());
    for (a, e) in actual.iter().zip(expected.as_array().unwrap()) {
        let expected = number(e);
        assert!(
            a.is_nan() && expected.is_nan() || a.to_bits() == expected.to_bits(),
            "actual {a:?}, expected {expected:?}"
        );
    }
}

fn sax_case(index: usize) {
    let vectors = cases("sax-word");
    assert_eq!(vectors.len(), 7);
    let case = &vectors[index];
    let values: Vec<_> = case["values"]
        .as_array()
        .unwrap()
        .iter()
        .map(number)
        .collect();
    let normalized = if case["normalize"].as_bool().unwrap() {
        z_normalize(&values)
    } else {
        values.clone()
    };
    let w = case["w"].as_u64().unwrap() as usize;
    let a = case["a"].as_u64().unwrap() as usize;
    assert_numbers(&normalized, &case["expect"]["normalized"]);
    assert_numbers(&paa(&normalized, w).unwrap(), &case["expect"]["paa"]);
    assert_numbers(&sax_breakpoints(a).unwrap(), &case["expect"]["breakpoints"]);
    let mut with_times = case.clone();
    with_times["timestampsMicros"] = json!((0..values.len())
        .map(|i| i as i64 * 1_000_000)
        .collect::<Vec<_>>());
    let source = episode(&with_times, "test", 0, values.len());
    let out = sax_word(
        &source,
        w,
        a,
        case["normalize"].as_bool().unwrap(),
        &"a".repeat(64),
    )
    .unwrap();
    assert_eq!(
        out.summary.sax.as_ref().unwrap(),
        case["expect"]["word"].as_str().unwrap()
    );
    assert_eq!(source.summary.sax, None);
    assert_eq!(
        out.provenance.parent_episode_id.as_deref(),
        Some(source.episode_id.as_str())
    );
    assert_eq!(out.provenance.producer, "opentsx-wayang/sax.word");
    assert_eq!(out.provenance.created_at, out.t_end);
    assert_eq!(
        out.episode_id,
        format!(
            "c{}",
            model::xxh64_hex(
                format!(
                    "{}|{}|{}|{}",
                    source.series.series_id,
                    source.t_start,
                    source.t_end,
                    "a".repeat(64)
                )
                .as_bytes()
            )
        )
    );
    for (a, b) in out.values.iter().zip(&source.values) {
        assert_eq!(a.to_bits(), b.to_bits());
    }
    let mut inputs = Vec::new();
    for site in ["test", "other"] {
        for window in 0..8 {
            let mut c = with_times.clone();
            c["timestampsMicros"] = json!((0..values.len())
                .map(|i| window * 60_000_000 + i as i64 * 1_000_000)
                .collect::<Vec<_>>());
            inputs.push(episode(&c, site, 0, values.len()));
        }
    }
    let convert = |e: &Episode| {
        sax_word(
            e,
            w,
            a,
            case["normalize"].as_bool().unwrap(),
            &"a".repeat(64),
        )
        .unwrap()
    };
    let mut baseline: Vec<_> = inputs.iter().map(convert).collect();
    baseline.sort_by_key(|e| e.episode_id.clone());
    let expected: Vec<_> = baseline
        .iter()
        .map(|e| tsx_runner::wire::encode(e).unwrap())
        .collect();
    for chunk in [1, 7, 16] {
        for reverse in [false, true] {
            let mut input = inputs.clone();
            if reverse {
                input.rotate_left(8);
            }
            let mut output = Vec::new();
            for records in input.chunks(chunk) {
                output.extend(records.iter().map(convert));
            }
            output.sort_by_key(|e| e.episode_id.clone());
            assert_eq!(
                output
                    .iter()
                    .map(|e| tsx_runner::wire::encode(e).unwrap())
                    .collect::<Vec<_>>(),
                expected
            );
        }
    }
}

macro_rules! vector_test {
    ($name:ident,$file:expr,$index:expr) => {
        #[test]
        fn $name() {
            segment_case($file, $index);
        }
    };
}
vector_test!(threshold_hysteresis, "segment-threshold", 0);
vector_test!(threshold_duration_cap, "segment-threshold", 1);
vector_test!(threshold_nan, "segment-threshold", 2);
vector_test!(threshold_min_duration, "segment-threshold", 3);
vector_test!(threshold_open_phase, "segment-threshold", 4);
vector_test!(threshold_irregular, "segment-threshold", 5);
vector_test!(extremum_prominence, "segment-extremum", 0);
vector_test!(extremum_minimum, "segment-extremum", 1);
vector_test!(extremum_ties, "segment-extremum", 2);
vector_test!(extremum_nan, "segment-extremum", 3);
vector_test!(extremum_rejected, "segment-extremum", 4);
vector_test!(extremum_irregular, "segment-extremum", 5);
macro_rules! sax_test {
    ($name:ident,$index:expr) => {
        #[test]
        fn $name() {
            sax_case($index);
        }
    };
}
sax_test!(sax_rising, 0);
sax_test!(sax_fractional, 1);
sax_test!(sax_constant, 2);
sax_test!(sax_nan, 3);
sax_test!(sax_all_nan, 4);
sax_test!(sax_breakpoint_equality, 5);
sax_test!(sax_largest_alphabet, 6);

#[test]
fn invalid_parameters_fail_closed() {
    let case = &cases("segment-threshold")[0];
    for changes in [
        json!({"unknown":1}),
        json!({"pre":"PT-1S"}),
        json!({"enter":"5"}),
        json!({"exit":99}),
        json!({"direction":"above"}),
    ] {
        let mut params = case["params"].clone();
        for (key, value) in changes.as_object().unwrap() {
            params[key] = value.clone();
        }
        assert!(Segmenter::new("segment.threshold", &params, 64, 100_000, 1_048_576).is_err());
    }
    assert!(Segmenter::new("segment.threshold", &case["params"], 0, 100_000, 1_048_576).is_err());
    assert!(paa(&[], 1).is_err());
    assert!(paa(&[1.0], 0).is_err());
    assert!(paa(&[1.0], usize::MAX).is_err());
    assert!(sax_breakpoints(1).is_err());
    assert!(sax_breakpoints(27).is_err());
    let input = episode(case, "test", 0, 3);
    assert!(sax_word(&input, 1, 2, true, "invalid").is_err());
    assert!(sax_word(&input, 0, 2, true, &"a".repeat(64)).is_err());
    let defaults = Segmenter::new(
        "segment.threshold",
        &json!({"direction":"ABOVE","enter":5,"exit":3}),
        64,
        100_000,
        1_048_576,
    )
    .unwrap();
    let explicit=Segmenter::new("segment.threshold",&json!({"direction":"ABOVE","enter":5,"exit":3,"pre":"PT0S","post":"PT0S","minDuration":"PT0S","maxDuration":"PT0S"}),64,100_000,1_048_576).unwrap();
    assert_eq!(defaults.snapshot().unwrap(), explicit.snapshot().unwrap());
}

#[test]
fn segmentation_caps_and_corrupt_checkpoints_preserve_original_state() {
    let case = &cases("segment-threshold")[0];
    let mut stream = Segmenter::new("segment.threshold", &case["params"], 1, 4, 1_048_576).unwrap();
    stream
        .process(&[episode(case, "test", 0, 3)], false)
        .unwrap();
    let before = stream.snapshot().unwrap();
    assert!(stream
        .process(&[episode(case, "test", 3, 9)], false)
        .is_err());
    assert_eq!(before, stream.snapshot().unwrap());
    assert!(stream
        .process(&[episode(case, "other", 0, 3)], false)
        .is_err());
    assert_eq!(before, stream.snapshot().unwrap());
    for field in ["schema", "paramKey", "series"] {
        let mut corrupt = before.clone();
        corrupt[field] = json!("invalid");
        assert!(stream.restore(&corrupt).is_err());
        assert_eq!(before, stream.snapshot().unwrap());
    }
    let mut corrupt = before.clone();
    let id = episode(case, "test", 0, 1).series.series_id;
    corrupt["series"][&id]["buffer"]["samples"][0]["source"] = json!(99999);
    assert!(stream.restore(&corrupt).is_err());
    assert_eq!(before, stream.snapshot().unwrap());
    let mut corrupt = before.clone();
    corrupt["series"][&id]["detector"]["enter"] = json!(99999);
    assert!(stream.restore(&corrupt).is_err());
    assert_eq!(before, stream.snapshot().unwrap());
}

#[test]
fn segmentation_preserves_quality_labels_and_nan_payload_bits_after_restore() {
    let case = json!({"values":[0,6,"NaN",6,0],"timestampsMicros":[0,1_000_000,2_000_000,3_000_000,4_000_000]});
    let params = json!({"direction":"ABOVE","enter":5,"exit":3,"pre":"PT0S","post":"PT0S","minDuration":"PT0S","maxDuration":"PT0S"});
    let mut first = episode(&case, "test", 0, 3);
    first.values[2] = f64::from_bits(0x7ff8000000000042);
    first.quality = Some(vec![1, 2, 4]);
    let mut last = episode(&case, "test", 3, 5);
    last.quality = Some(vec![8, 16]);
    let mut stream = Segmenter::new("segment.threshold", &params, 64, 100_000, 1_048_576).unwrap();
    assert!(stream.process(&[first], false).unwrap().is_empty());
    let encoded = serde_json::to_vec(&stream.snapshot().unwrap()).unwrap();
    let mut restored =
        Segmenter::new("segment.threshold", &params, 64, 100_000, 1_048_576).unwrap();
    restored
        .restore(&serde_json::from_slice(&encoded).unwrap())
        .unwrap();
    let output = restored.process(&[last], true).unwrap();
    assert_eq!(output.len(), 1);
    assert_eq!(output[0].values[1].to_bits(), 0x7ff8000000000042);
    assert_eq!(output[0].quality, Some(vec![2, 4, 8]));
    assert_eq!(
        output[0].labels,
        BTreeMap::from([
            ("scenario".into(), "synthetic".into()),
            ("segment.strategy".into(), "FEATURE_THRESHOLD".into())
        ])
    );
    assert_eq!(
        output[0].provenance.parent_episode_id.as_deref(),
        Some("input-test")
    );
}
