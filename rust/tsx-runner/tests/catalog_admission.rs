mod common;
#[path = "common/run_profile.rs"]
mod run_profile;

use common::{episode, request, ARTIFACT_DIGEST};
use serde_json::{json, Value};
use std::collections::BTreeSet;
use tsx_runner::admission::{admit, Admitted};
use tsx_runner::broker::{Broker, InputRecord, OutputRecord};
use tsx_runner::model::{self, Episode};
use tsx_runner::run_plan::{RunConfig, SignedPlan};
use tsx_runner::{runner, wire};

#[derive(Default)]
struct MemoryBroker {
    input: Vec<InputRecord>,
    reads: usize,
    sends: usize,
}
impl Broker for MemoryBroker {
    fn read(&mut self, _: &Admitted) -> Result<Vec<InputRecord>, String> {
        self.reads += 1;
        Ok(self.input.clone())
    }
    fn publish(&mut self, _: &Admitted, _: &[OutputRecord]) -> Result<(), String> {
        self.sends += 1;
        Ok(())
    }
}

fn catalog_request(
    nodes: Value,
    from: &str,
    schema: &str,
    episodes: &[Episode],
    context: Value,
) -> Value {
    let mut request = request();
    let mut assignment: Value =
        serde_json::from_str(request["assignmentText"].as_str().unwrap()).unwrap();
    let mut dag: Value =
        serde_json::from_str(assignment["fragmentText"].as_str().unwrap()).unwrap();
    dag["source"]["series"] = json!(episodes
        .iter()
        .map(|e| e.series.series_id.clone())
        .collect::<BTreeSet<_>>());
    dag["nodes"] = nodes;
    dag["sinks"][0]["from"] = from.into();
    dag["sinks"][0]["schema"] = schema.into();
    dag["sinks"][0]["name"] = "plant-sensor".into();
    for field in ["sourceDagText", "fragmentText"] {
        assignment[field] = dag.to_string().into();
    }
    for field in ["specHash", "fragmentHash"] {
        assignment[field] = tsx_contracts::sha256(&dag.to_string()).into();
    }
    assignment["inputs"][0]["partitionCount"] = "1".into();
    assignment["inputs"][0]["positions"] =
        json!([{"partition":"0","from":"0","to":episodes.len().to_string()}]);
    request["assignmentText"] = assignment.to_string().into();
    request["context"] = context;
    request["limits"]["payloadBytes"] = 8192.into();
    request["limits"]["stateBytes"] = 1_048_576.into();
    request
}

fn inputs(episodes: &[Episode]) -> Vec<InputRecord> {
    episodes
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

fn prepare(value: &Value, episodes: &[Episode]) -> (Admitted, runner::Prepared, MemoryBroker) {
    let mut admitted = admit(&value.to_string(), ARTIFACT_DIGEST).unwrap();
    let mut broker = MemoryBroker {
        input: inputs(episodes),
        ..MemoryBroker::default()
    };
    let prepared = runner::prepare(&mut admitted, &mut broker).unwrap();
    runner::validate_prepared(&admitted, &prepared).unwrap();
    assert_eq!((broker.reads, broker.sends), (1, 0));
    (admitted, prepared, broker)
}

fn pair_input() -> Vec<Episode> {
    let a = episode(vec![1.0, 2.0, 3.0, 4.0], 10_000_000);
    let mut b = a.clone();
    b.episode_id = "second-series".into();
    b.series.tags.insert("sensor".into(), "two".into());
    b.series.series_id = model::series_id(&b.series.metric, &b.series.tags).unwrap();
    b.values = vec![4.0, 3.0, 2.0, 1.0];
    b.summary = model::summary(&b.values);
    vec![a, b]
}

fn paired_request(op: &str, params: Value, schema: &str, episodes: &[Episode]) -> Value {
    catalog_request(
        json!([{"id":"a","op":"decode.avro-soe"},{"id":"b","op":"decode.avro-soe"},
        {"id":"result","op":op,"in":["a","b"],"params":params}]),
        "result",
        schema,
        episodes,
        json!({"seriesByNode":{"a":[episodes[0].series.series_id],"b":[episodes[1].series.series_id]},"xcorrProductLimit":1000}),
    )
}

#[test]
fn sax_uses_real_admission_and_prepare_and_preserves_the_summary_word() {
    let episodes = [episode(vec![1.0, 2.0, 3.0, 4.0], 10_000_000)];
    let value = catalog_request(
        json!([{"id":"decode","op":"decode.avro-soe"},
        {"id":"sax","op":"sax.word","in":["decode"],"params":{"w":2,"a":4}}]),
        "sax",
        "opentsx.episode.v2",
        &episodes,
        json!({}),
    );
    let (_, prepared, _) = prepare(&value, &episodes);
    assert_eq!(prepared.records.len(), 1);
    let output = wire::decode(&prepared.records[0].payload, 8192, 1000).unwrap();
    assert_eq!(output.summary.sax.as_deref(), Some("2x4:ad"));
    assert_eq!(output.values, episodes[0].values);
    assert_eq!(output.series.series_id, episodes[0].series.series_id);
}

#[test]
fn both_segment_operators_execute_through_real_preparation() {
    let episodes = [episode(
        vec![0.0, 1.0, 5.0, 4.0, 3.0, 2.0, 1.0, 0.0, 0.0],
        10_000_000,
    )];
    for (op, params, strategy) in [
        (
            "segment.threshold",
            json!({"direction":"ABOVE","enter":5.0,"exit":3.0,"pre":"PT1S","post":"PT2S","minDuration":"PT2S","maxDuration":"PT0S"}),
            model::SegmentationStrategy::FeatureThreshold,
        ),
        (
            "segment.extremum",
            json!({"feature":"MAX","prominence":1.0,"window":"PT2S","distance":"PT0S","pre":"PT1S","post":"PT2S"}),
            model::SegmentationStrategy::FeatureExtremum,
        ),
    ] {
        let value = catalog_request(
            json!([{"id":"decode","op":"decode.avro-soe"},
            {"id":"segment","op":op,"in":["decode"],"params":params}]),
            "segment",
            "opentsx.episode.v2",
            &episodes,
            json!({}),
        );
        let (_, prepared, _) = prepare(&value, &episodes);
        assert!(!prepared.records.is_empty(), "{op}");
        for record in &prepared.records {
            let output = wire::decode(&record.payload, 8192, 1000).unwrap();
            assert_eq!(output.segmentation.unwrap().strategy, strategy);
            assert_eq!(output.series.series_id, episodes[0].series.series_id);
        }
    }
}

#[test]
fn alignment_emits_typed_vectors_from_distinct_whole_topic_series() {
    let episodes = pair_input();
    let value = paired_request(
        "align.vector",
        json!({"every":1,"key":"all","names":["left","right"]}),
        "opentsx.vector.v1",
        &episodes,
    );
    let (_, prepared, _) = prepare(&value, &episodes);
    assert_eq!(prepared.records.len(), 4);
    let first: Value = serde_json::from_slice(&prepared.records[0].payload).unwrap();
    assert_eq!(first["schema"], "opentsx.vector.v1");
    assert_eq!(first["components"], json!(["left", "right"]));
    assert_eq!(first["values"], json!([1.0, 4.0]));
    assert_eq!(first["missing"], 0);
}

#[test]
fn cross_correlation_executes_and_enforces_the_exact_product_limit() {
    let episodes = pair_input();
    let value = paired_request(
        "pair.xcorr",
        json!({"lags":1}),
        "opentsx.episode.v2",
        &episodes,
    );
    let (_, prepared, _) = prepare(&value, &episodes);
    assert_eq!(prepared.records.len(), 3);
    for record in &prepared.records {
        let output = wire::decode(&record.payload, 8192, 1000).unwrap();
        assert!(output.labels.contains_key("pair.method"));
    }
    let mut limited = paired_request(
        "pair.xcorr",
        json!({"lags":2,"window":"100","every":0.1}),
        "opentsx.episode.v2",
        &episodes,
    );
    limited["context"]["xcorrProductLimit"] = 1000.into();
    assert_eq!(
        admit(&limited.to_string(), ARTIFACT_DIGEST).err().unwrap(),
        "node result: pair.xcorr exceeds the limit of this site (1000 products per window)"
    );
}

#[test]
fn b2_context_preserves_legacy_caps_and_quality_policy() {
    let mut input = episode(vec![1.0, 2.0], 10_000_000);
    input.revision = 1;
    input.quality = Some(vec![1, 2]);
    let episodes = [input];
    let nodes = json!([{"id":"decode","op":"decode.avro-soe"},{"id":"convert","op":"convert.unit","in":["decode"],"params":{"factor":2}}]);
    let value = catalog_request(nodes, "convert", "opentsx.episode.v2", &episodes, json!({}));
    let (_, prepared, _) = prepare(&value, &episodes);
    assert_eq!(
        wire::decode(&prepared.records[0].payload, 8192, 1000)
            .unwrap()
            .values,
        vec![2.0, 4.0]
    );
    let mut legacy = value.clone();
    legacy.as_object_mut().unwrap().remove("context");
    let mut admitted = admit(&legacy.to_string(), ARTIFACT_DIGEST).unwrap();
    let mut broker = MemoryBroker {
        input: inputs(&episodes),
        ..MemoryBroker::default()
    };
    assert_eq!(
        runner::prepare(&mut admitted, &mut broker).unwrap_err(),
        "cleaning rejects revisions and per-value quality"
    );
    assert_eq!(broker.sends, 0);
    for (name, at, above) in [("series", 1024, 1025), ("samples", 1_000_000, 1_000_001)] {
        let mut bounded = value.clone();
        bounded["limits"][name] = at.into();
        assert!(
            admit(&bounded.to_string(), ARTIFACT_DIGEST).is_ok(),
            "{name}"
        );
        bounded["limits"][name] = above.into();
        assert!(admit(&bounded.to_string(), ARTIFACT_DIGEST).is_err());
        let mut old = value.clone();
        old.as_object_mut().unwrap().remove("context");
        old["limits"][name] = at.into();
        assert!(admit(&old.to_string(), ARTIFACT_DIGEST).is_err());
    }
}

#[test]
fn invalid_b2_parameters_and_budgets_refuse_before_preparation() {
    let episodes = [episode(vec![1.0, 2.0], 10_000_000)];
    for (op, params, schema) in [
        ("sax.word", json!({"w":0,"a":4}), "opentsx.episode.v2"),
        ("sax.word", json!({"w":2,"a":99}), "opentsx.episode.v2"),
        (
            "stats.welford-ddsketch",
            json!({"windows":["PT0S"],"alpha":0.01}),
            "org.opentsx.model.v2.SeriesStats",
        ),
        (
            "stats.welford-ddsketch",
            json!({"windows":["PT1S"],"alpha":1.0}),
            "org.opentsx.model.v2.SeriesStats",
        ),
        ("pair.es", json!({}), "opentsx.episode.v2"),
    ] {
        let value = catalog_request(
            json!([{"id":"decode","op":"decode.avro-soe"},
            {"id":"bad","op":op,"in":["decode"],"params":params}]),
            "bad",
            schema,
            &episodes,
            json!({}),
        );
        assert!(admit(&value.to_string(), ARTIFACT_DIGEST).is_err(), "{op}");
    }
    let mut value = catalog_request(
        json!([{"id":"decode","op":"decode.avro-soe"},
        {"id":"sax","op":"sax.word","in":["decode"],"params":{"w":2,"a":4}}]),
        "sax",
        "opentsx.episode.v2",
        &episodes,
        json!({}),
    );
    value["limits"]["stateBytes"] = 1.into();
    assert!(admit(&value.to_string(), ARTIFACT_DIGEST).is_err());
}

#[test]
fn malformed_input_and_catalog_state_never_advance_a_candidate() {
    let episodes = pair_input();
    let value = paired_request(
        "align.vector",
        json!({"every":1,"key":"all","names":["left","right"]}),
        "opentsx.vector.v1",
        &episodes,
    );
    let mut admitted = admit(&value.to_string(), ARTIFACT_DIGEST).unwrap();
    let saved = admitted.pipeline.snapshot().unwrap();
    let mut broker = MemoryBroker {
        input: inputs(&episodes),
        ..MemoryBroker::default()
    };
    broker.input[0].payload[2] ^= 1;
    assert!(runner::prepare(&mut admitted, &mut broker).is_err());
    assert_eq!(admitted.pipeline.snapshot().unwrap(), saved);
    assert_eq!(broker.sends, 0);
    let mut wrong = saved.clone();
    wrong["context"]["xcorrProductLimit"] = 999.into();
    assert!(admitted.pipeline.restore(&wrong).is_err());
    assert_eq!(admitted.pipeline.snapshot().unwrap(), saved);
    let mut unknown = saved.clone();
    unknown["nodes"]["forged"] = json!({});
    assert!(admitted.pipeline.restore(&unknown).is_err());
    assert_eq!(admitted.pipeline.snapshot().unwrap(), saved);
}

#[test]
fn signed_b1_admission_still_refuses_catalog_execution() {
    let fixture = run_profile::Fixture::new();
    let config = RunConfig::load(&fixture.config_path).unwrap();
    let document = fixture.plan_document();
    assert!(SignedPlan::verify(
        &fixture.sign(&document),
        &config,
        run_profile::ARTIFACT_DIGEST
    )
    .is_ok());
    let mut document = document;
    let mut assignment: Value =
        serde_json::from_str(document["assignmentText"].as_str().unwrap()).unwrap();
    let mut dag: Value =
        serde_json::from_str(assignment["fragmentText"].as_str().unwrap()).unwrap();
    dag["nodes"]
        .as_array_mut()
        .unwrap()
        .push(json!({"id":"sax","op":"sax.word","in":["decode"],"params":{"w":2,"a":4}}));
    dag["sinks"][0]["from"] = "sax".into();
    for field in ["sourceDagText", "fragmentText"] {
        assignment[field] = dag.to_string().into();
    }
    for field in ["specHash", "fragmentHash"] {
        assignment[field] = tsx_contracts::sha256(&dag.to_string()).into();
    }
    document["specHash"] = assignment["specHash"].clone();
    document["assignmentText"] = assignment.to_string().into();
    let error = SignedPlan::verify(
        &fixture.sign(&document),
        &config,
        run_profile::ARTIFACT_DIGEST,
    )
    .err()
    .unwrap();
    assert_eq!(error.code, "plan.catalog");
}

fn refresh_output_digest(prepared: &mut runner::Prepared) {
    let mut framed = Vec::new();
    for record in &prepared.records {
        framed.extend_from_slice(&(record.key.len() as u64).to_be_bytes());
        framed.extend_from_slice(record.key.as_bytes());
        framed.extend_from_slice(&record.timestamp.to_be_bytes());
        framed.extend_from_slice(&(record.payload.len() as u64).to_be_bytes());
        framed.extend_from_slice(&record.payload);
    }
    prepared.completion.receipt.output_digest = tsx_contracts::sha256_bytes(&framed);
}

#[test]
fn prepared_signal_validation_rejects_malformed_fields_even_with_a_matching_digest() {
    let episodes = [episode(vec![1.0, 2.0, 3.0, 4.0], 10_000_000)];
    let value = catalog_request(
        json!([{"id":"decode","op":"decode.avro-soe"},
        {"id":"stats","op":"stats.welford-ddsketch","in":["decode"],"params":{"windows":["PT2S"],"alpha":0.01}},
        {"id":"dq","op":"dq.result","in":["stats"],"params":{"checks":["GAPS"],"expectedInterval":"PT1S"}}]),
        "dq",
        "opentsx.signal.v1",
        &episodes,
        json!({}),
    );
    let (admitted, prepared, _) = prepare(&value, &episodes);
    assert_eq!(prepared.records.len(), 3);
    for mutation in [
        "status",
        "check",
        "value",
        "specHash",
        "threshold",
        "message",
        "window",
    ] {
        let mut broken = prepared.clone();
        let mut record: Value = serde_json::from_slice(&broken.records[0].payload).unwrap();
        match mutation {
            "status" => record["status"] = "INVALID".into(),
            "check" => record["check"] = "UNKNOWN".into(),
            "value" => record["value"] = "not a number".into(),
            "specHash" => {
                record.as_object_mut().unwrap().remove("specHash");
            }
            "threshold" => record["threshold"] = false.into(),
            "message" => record["message"] = 7.into(),
            "window" => record["window"]["unexpected"] = 1.into(),
            _ => unreachable!(),
        }
        broken.records[0].payload = serde_json::to_vec(&record).unwrap();
        refresh_output_digest(&mut broken);
        assert!(
            runner::validate_prepared(&admitted, &broken).is_err(),
            "{mutation}"
        );
    }
}

#[test]
fn restored_statistics_cannot_invent_a_series_outside_the_recorded_node_input() {
    let episodes = [episode(vec![1.0, 2.0], 10_000_000)];
    let value = catalog_request(
        json!([{"id":"decode","op":"decode.avro-soe"},
        {"id":"stats","op":"stats.welford-ddsketch","in":["decode"],"params":{"windows":["PT10S"],"alpha":0.01}}]),
        "stats",
        "org.opentsx.model.v2.SeriesStats",
        &episodes,
        json!({}),
    );
    let (mut admitted, _, _) = prepare(&value, &episodes);
    let saved = admitted.pipeline.snapshot().unwrap();
    let mut forged = saved.clone();
    let state = forged["nodes"]["stats"]["engine"]["series"]
        .as_object_mut()
        .unwrap();
    let previous = state.remove(&episodes[0].series.series_id).unwrap();
    state.insert("ffffffffffffffff".into(), previous);
    assert!(admitted.pipeline.restore(&forged).is_err());
    assert_eq!(admitted.pipeline.snapshot().unwrap(), saved);
}

#[test]
fn derived_clean_series_statistics_restore_against_the_actual_upstream_inventory() {
    let episodes = [episode(vec![1.0, 2.0, 3.0, 4.0], 10_000_000)];
    let value = catalog_request(
        json!([{"id":"decode","op":"decode.avro-soe"},
        {"id":"convert","op":"convert.unit","in":["decode"],"params":{"factor":2,"unit":"MW"}},
        {"id":"stats","op":"stats.welford-ddsketch","in":["convert"],"params":{"windows":["PT2S"],"alpha":0.01}}]),
        "stats",
        "org.opentsx.model.v2.SeriesStats",
        &episodes,
        json!({}),
    );
    let (mut admitted, prepared, _) = prepare(&value, &episodes);
    assert_eq!(prepared.records.len(), 3);
    for record in &prepared.records {
        let stats = tsx_runner::stats::StatsRecord::decode_soe(&record.payload, 8192).unwrap();
        assert_ne!(stats.series_id, episodes[0].series.series_id);
    }
    let saved = admitted.pipeline.snapshot().unwrap();
    let mut restored = admit(&value.to_string(), ARTIFACT_DIGEST).unwrap();
    restored
        .pipeline
        .restore(&serde_json::from_slice(&serde_json::to_vec(&saved).unwrap()).unwrap())
        .unwrap();
    assert_eq!(restored.pipeline.snapshot().unwrap(), saved);
    let mut forged = saved.clone();
    forged["nodes"]["stats"]["engine"]["series"]
        .as_object_mut()
        .unwrap()
        .insert(
            episodes[0].series.series_id.clone(),
            saved["nodes"]["stats"]["engine"]["series"]
                .as_object()
                .unwrap()
                .values()
                .next()
                .unwrap()
                .clone(),
        );
    assert!(admitted.pipeline.restore(&forged).is_err());
    assert_eq!(admitted.pipeline.snapshot().unwrap(), saved);
}

#[test]
fn vector_checkpoint_metadata_must_still_hash_to_its_canonical_input_identity() {
    let episodes = pair_input();
    let value = paired_request(
        "align.vector",
        json!({"every":1,"key":"all","names":["left","right"]}),
        "opentsx.vector.v1",
        &episodes,
    );
    let mut admitted = admit(&value.to_string(), ARTIFACT_DIGEST).unwrap();
    let output = admitted
        .pipeline
        .process_records(&episodes[..1], false)
        .unwrap();
    assert!(output.is_empty());
    let saved = admitted.pipeline.snapshot().unwrap();
    for field in ["metric", "tags"] {
        let mut forged = saved.clone();
        let series = forged["nodes"]["result"]["series"]
            .as_object_mut()
            .unwrap()
            .values_mut()
            .next()
            .unwrap();
        if field == "metric" {
            series["metric"] = "forged.metric".into();
        } else {
            series["tags"]["sensor"] = "forged-sensor".into();
        }
        assert!(admitted.pipeline.restore(&forged).is_err(), "{field}");
        assert_eq!(admitted.pipeline.snapshot().unwrap(), saved);
    }
}
