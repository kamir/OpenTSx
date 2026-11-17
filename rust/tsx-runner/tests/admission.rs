mod common;

use common::{request, ARTIFACT_DIGEST, INPUT_TOPIC, OUTPUT_TOPIC};
use serde_json::{json, Value};
use tsx_runner::admission::{admit, MAX_PARTITIONS, REQUEST_BYTES};

fn assignment(request: &Value) -> Value {
    serde_json::from_str(request["assignmentText"].as_str().unwrap()).unwrap()
}

fn update_assignment(request: &mut Value, change: impl FnOnce(&mut Value)) {
    let mut value = assignment(request);
    change(&mut value);
    request["assignmentText"] = json!(value.to_string());
}

fn update_fragment(request: &mut Value, change: impl FnOnce(&mut Value)) {
    update_assignment(request, |assignment| {
        let mut fragment: Value =
            serde_json::from_str(assignment["fragmentText"].as_str().unwrap()).unwrap();
        change(&mut fragment);
        let text = fragment.to_string();
        assignment["fragmentHash"] = json!(tsx_contracts::sha256(&text));
        assignment["fragmentText"] = json!(text);
    });
}

fn error(request: &Value) -> String {
    admit(&request.to_string(), ARTIFACT_DIGEST)
        .err()
        .expect("request must be refused")
}

#[test]
fn admits_one_complete_three_partition_topic_without_broker_io() {
    let value = request();
    let text = value.to_string();
    let run = admit(&text, ARTIFACT_DIGEST).unwrap();
    assert_eq!(run.cut.topic, INPUT_TOPIC);
    assert_eq!(
        run.cut
            .positions
            .iter()
            .map(|p| (p.partition, p.from, p.to))
            .collect::<Vec<_>>(),
        vec![(0, 0, 2), (1, 0, 3), (2, 2, 3)]
    );
    assert_eq!(run.output_partitions, 2);
    assert_eq!(run.bootstrap, "localhost:9092");
    assert_eq!(run.pipeline.source_topic(), INPUT_TOPIC);
    assert_eq!(run.pipeline.sink_topic(), Some(OUTPUT_TOPIC));
    assert_eq!(run.pipeline.sink_name(), "plant-sensor");
    assert_eq!(run.request_hash, tsx_contracts::sha256(&text));
}

#[test]
fn artifact_and_runtime_are_checked_before_running_any_graph() {
    let value = request();
    assert_eq!(
        admit(
            &value.to_string(),
            "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        )
        .err()
        .unwrap(),
        "assignment artifactDigest does not identify this executable"
    );
    let mut value = request();
    update_assignment(&mut value, |a| a["runtime"] = json!("java"));
    assert_eq!(
        error(&value),
        "local replay requires a bounded Rust assignment"
    );
    let mut value = request();
    update_assignment(&mut value, |a| {
        a["mode"] = json!("continuous");
        for p in a["inputs"][0]["positions"].as_array_mut().unwrap() {
            p.as_object_mut().unwrap().remove("to");
        }
    });
    assert_eq!(
        error(&value),
        "local replay requires a bounded Rust assignment"
    );
}

#[test]
fn requires_whole_topic_coverage_and_refuses_multi_topic_placement() {
    let mut value = request();
    update_assignment(&mut value, |a| {
        a["inputs"][0]["positions"].as_array_mut().unwrap().pop();
    });
    assert_eq!(error(&value), "assignment.coverage");
    let mut value = request();
    update_assignment(&mut value, |a| {
        a["inputs"][0]["positions"][2]["partition"] = json!("1")
    });
    assert_eq!(error(&value), "assignment.coverage");
    let mut value = request();
    update_assignment(&mut value, |a| {
        a["inputs"][0]["positions"]
            .as_array_mut()
            .unwrap()
            .swap(0, 1)
    });
    assert_eq!(error(&value), "assignment.coverage");
    let mut value = request();
    update_assignment(&mut value, |a| {
        let mut second = a["inputs"][0].clone();
        second["topic"] = json!("tsx.learning.other.v2");
        a["inputs"].as_array_mut().unwrap().push(second);
    });
    assert_eq!(
        error(&value),
        "local replay requires one whole input topic and one broker binding"
    );
    let mut value = request();
    value["bindings"]["second"] = value["bindings"]["local"].clone();
    assert_eq!(
        error(&value),
        "local replay requires one whole input topic and one broker binding"
    );
}

#[test]
fn partition_inventory_caps_apply_after_complete_coverage_validation() {
    let mut value = request();
    update_assignment(&mut value, |a| {
        a["inputs"][0]["partitionCount"] = json!((MAX_PARTITIONS + 1).to_string());
        a["inputs"][0]["positions"] = Value::Array(
            (0..=MAX_PARTITIONS)
                .map(|p| json!({"partition":p.to_string(),"from":"0","to":"0"}))
                .collect(),
        );
    });
    assert_eq!(
        error(&value),
        "whole topic exceeds the local partition capacity"
    );
    for count in ["0", "129"] {
        let mut value = request();
        value["sink"]["partitionCount"] = json!(count);
        assert_eq!(
            error(&value),
            "output partition count exceeds the local profile"
        );
    }
    let mut value = request();
    value["sink"]["partitionCount"] = json!("02");
    assert_eq!(error(&value), "noncanonical decimal sink.partitionCount");
}

#[test]
fn bootstrap_accepts_host_port_lists_and_rejects_credentials_or_url_syntax() {
    for bootstrap in [
        "localhost:9092",
        "kafscale-broker:39092",
        "127.0.0.1:9092",
        "[::1]:9092",
        "localhost:9092,other-host:9093",
    ] {
        let mut value = request();
        value["bindings"]["local"]["bootstrap"] = json!(bootstrap);
        assert!(
            admit(&value.to_string(), ARTIFACT_DIGEST).is_ok(),
            "{bootstrap}"
        );
    }
    for (bootstrap, message) in [
        ("", "invalid broker address list"),
        ("localhost", "broker address needs host:port"),
        (":9092", "invalid broker host or port"),
        ("localhost:0", "invalid broker host or port"),
        ("localhost:65536", "invalid broker port"),
        ("localhost:9092/path", "invalid broker port"),
        (
            "http://localhost:9092",
            "broker addresses must contain host names and ports without credentials",
        ),
        (
            "user:example@localhost:9092",
            "broker addresses must contain host names and ports without credentials",
        ),
        (
            "::1:9092",
            "broker addresses must contain host names and ports without credentials",
        ),
        ("[invalid]:9092", "invalid IPv6 broker address"),
        ("localhost:9092,", "broker address needs host:port"),
    ] {
        let mut value = request();
        value["bindings"]["local"]["bootstrap"] = json!(bootstrap);
        assert_eq!(error(&value), message, "{bootstrap}");
    }
}

#[test]
fn binding_allowlists_and_fragment_channels_must_match_distinct_topics() {
    for field in ["inputs", "outputs"] {
        let mut value = request();
        value["bindings"]["local"][field] = json!(["tsx.learning.unpermitted.v2"]);
        assert_eq!(
            error(&value),
            "local input/output bindings do not match the permitted distinct topics"
        );
    }
    let mut value = request();
    value["sink"]["binding"] = json!("unconfigured");
    assert_eq!(
        error(&value),
        "local input/output bindings do not match the permitted distinct topics"
    );
    let mut value = request();
    update_assignment(&mut value, |a| {
        a["inputs"][0]["binding"] = json!("unconfigured")
    });
    assert_eq!(error(&value), "input binding is not configured");
    let mut value = request();
    value["sink"]["topic"] = json!(INPUT_TOPIC);
    value["bindings"]["local"]["outputs"] = json!([INPUT_TOPIC]);
    assert_eq!(
        error(&value),
        "local input/output bindings do not match the permitted distinct topics"
    );
    for field in ["source", "sink", "name"] {
        let mut value = request();
        if field == "source" {
            update_fragment(&mut value, |f| {
                f["source"]["topics"] = json!(["tsx.learning.other.v2"])
            });
        }
        if field == "sink" {
            update_fragment(&mut value, |f| {
                f["sinks"][0]["topic"] = json!("tsx.learning.other.v2")
            });
        }
        if field == "name" {
            value["sink"]["name"] = json!("another-sink");
        }
        assert_eq!(
            error(&value),
            "fragment channels do not match the admitted input and output"
        );
    }
}

#[test]
fn output_topic_uses_the_shared_topic_name_contract() {
    for topic in [
        "other.output",
        "tsx.learning.Clean.v2",
        "tsx.learning.clean.v02",
        "tsx.learning.clean.v2\n",
    ] {
        let mut value = request();
        value["sink"]["topic"] = json!(topic);
        value["bindings"]["local"]["outputs"] = json!([topic]);
        update_fragment(&mut value, |f| f["sinks"][0]["topic"] = json!(topic));
        assert_eq!(error(&value), "invalid output topic name");
    }
}

#[test]
fn graph_semantics_are_admitted_after_shared_envelope_hash_checks() {
    let mut value = request();
    update_fragment(&mut value, |f| f["nodes"][1]["op"] = json!("fft"));
    assert_eq!(error(&value), "unsupported operator fft");
    let mut value = request();
    update_fragment(&mut value, |f| f["nodes"][1]["in"] = json!(["missing"]));
    assert_eq!(error(&value), "pipeline graph references a missing node");
    let mut value = request();
    update_fragment(&mut value, |f| f["nodes"][1]["in"] = json!(["convert"]));
    assert_eq!(error(&value), "pipeline graph contains a cycle");
    let mut value = request();
    update_fragment(&mut value, |f| {
        f["nodes"][1]["params"]["factor"] = json!("2")
    });
    assert_eq!(error(&value), "factor must be a finite number");
    let mut value = request();
    update_assignment(&mut value, |a| a["fragmentHash"] = json!("b".repeat(64)));
    assert_eq!(error(&value), "assignment.hash");
}

#[test]
fn every_limit_is_required_positive_and_within_the_declared_ceiling() {
    for (name, ceiling) in [
        ("records", 10_000),
        ("payloadBytes", 1_048_576),
        ("inputBytes", 16_777_216),
        ("samples", 100_000),
        ("outputBytes", 16_777_216),
        ("series", 64),
        ("stateBytes", 1_048_576),
    ] {
        let mut value = request();
        value["limits"].as_object_mut().unwrap().remove(name);
        assert_eq!(
            error(&value),
            format!("local request fields: missing field `{name}`")
        );
        for invalid in [0, ceiling + 1] {
            let mut value = request();
            value["limits"][name] = json!(invalid);
            assert_eq!(
                error(&value),
                format!("{name} must be within 1..={ceiling}")
            );
        }
    }
    let mut value = request();
    value["limits"]["payloadBytes"] = json!(1024);
    value["limits"]["inputBytes"] = json!(512);
    assert_eq!(error(&value), "payloadBytes exceeds inputBytes");
    let mut value = request();
    value["limits"]["surprise"] = json!(1);
    assert!(error(&value).starts_with("local request fields: unknown field `surprise`"));
}

#[test]
fn admitted_batch_sample_cap_is_distinct_from_the_per_episode_wire_cap() {
    let mut value = request();
    value["limits"]["samples"] = json!(30_000);
    value["limits"]["payloadBytes"] = json!(1_048_576);
    value["limits"]["inputBytes"] = json!(16_777_216);
    value["limits"]["outputBytes"] = json!(16_777_216);
    let mut run = admit(&value.to_string(), ARTIFACT_DIGEST).unwrap();
    let episodes = [
        common::episode(vec![1.0; 15_000], 0),
        common::episode(vec![2.0; 15_000], 15_000_000_000),
    ];
    for episode in &episodes {
        let encoded = tsx_runner::wire::encode(episode).unwrap();
        tsx_runner::wire::decode(
            &encoded,
            run.request.limits.payload_bytes,
            tsx_runner::admission::MAX_EPISODE_SAMPLES,
        )
        .unwrap();
    }
    let out = run.pipeline.process(&episodes).unwrap();
    assert_eq!(out.len(), 2);
    assert_eq!(
        out.iter()
            .map(|episode| episode.values.len())
            .sum::<usize>(),
        30_000
    );
    assert!(out[0].values.iter().all(|value| *value == 3.0));
    assert!(out[1].values.iter().all(|value| *value == 5.0));
}

#[test]
fn exact_offset_spans_are_capped_without_floating_point_rounding() {
    let mut value = request();
    value["limits"]["records"] = json!(5);
    assert_eq!(error(&value), "whole input cut exceeds the record limit");
    value["limits"]["records"] = json!(6);
    assert!(admit(&value.to_string(), ARTIFACT_DIGEST).is_ok());
    update_assignment(&mut value, |a| {
        a["inputs"][0]["positions"][0]["from"] = json!("9007199254740993");
        a["inputs"][0]["positions"][0]["to"] = json!("9007199254740995");
    });
    let run = admit(&value.to_string(), ARTIFACT_DIGEST).unwrap();
    assert_eq!(run.cut.positions[0].from, 9_007_199_254_740_993);
    assert_eq!(run.cut.positions[0].to, 9_007_199_254_740_995);
    update_assignment(&mut value, |a| {
        a["inputs"][0]["positions"][0]["from"] = json!((i64::MAX - 2).to_string());
        a["inputs"][0]["positions"][0]["to"] = json!(i64::MAX.to_string());
    });
    assert_eq!(
        admit(&value.to_string(), ARTIFACT_DIGEST)
            .unwrap()
            .cut
            .positions[0]
            .to,
        i64::MAX
    );
    update_assignment(&mut value, |a| {
        for p in a["inputs"][0]["positions"].as_array_mut().unwrap() {
            p["from"] = json!("0");
            p["to"] = json!(i64::MAX.to_string());
        }
    });
    assert_eq!(error(&value), "input cut size overflow");
}

#[test]
fn malformed_offset_text_rejects_and_empty_ranges_remain_valid() {
    for invalid in [
        json!("01"),
        json!("-1"),
        json!("9223372036854775808"),
        json!("1.0"),
        json!(1),
    ] {
        let mut value = request();
        update_assignment(&mut value, |a| {
            a["inputs"][0]["positions"][0]["from"] = invalid
        });
        assert_eq!(error(&value), "assignment.offset");
    }
    let mut value = request();
    update_assignment(&mut value, |a| {
        a["inputs"][0]["positions"][0]["from"] = json!("3")
    });
    assert_eq!(error(&value), "assignment.offset");
    let mut value = request();
    update_assignment(&mut value, |a| {
        for p in a["inputs"][0]["positions"].as_array_mut().unwrap() {
            p["from"] = json!("17");
            p["to"] = json!("17");
        }
    });
    let run = admit(&value.to_string(), ARTIFACT_DIGEST).unwrap();
    assert!(run.cut.positions.iter().all(|p| p.from == 17 && p.to == 17));
}

#[test]
fn request_identity_changes_with_output_binding_or_frozen_clock() {
    let original = request();
    let admitted = admit(&original.to_string(), ARTIFACT_DIGEST).unwrap();
    assert_eq!(
        admit(&original.to_string(), ARTIFACT_DIGEST)
            .unwrap()
            .request_hash,
        admitted.request_hash
    );
    let mut changed = original.clone();
    changed["createdAtMicros"] = json!("1700000000000001");
    assert_ne!(
        admit(&changed.to_string(), ARTIFACT_DIGEST)
            .unwrap()
            .request_hash,
        admitted.request_hash
    );
    let mut changed = original.clone();
    changed["sink"]["topic"] = json!("tsx.learning.other-clean.v2");
    changed["bindings"]["local"]["outputs"] = json!(["tsx.learning.other-clean.v2"]);
    update_fragment(&mut changed, |f| {
        f["sinks"][0]["topic"] = json!("tsx.learning.other-clean.v2")
    });
    let output_changed = admit(&changed.to_string(), ARTIFACT_DIGEST).unwrap();
    assert_eq!(
        output_changed.assignment["specHash"],
        admitted.assignment["specHash"]
    );
    assert_ne!(output_changed.request_hash, admitted.request_hash);
    let mut changed = original.clone();
    changed["bindings"]["local"]["bootstrap"] = json!("other-host:9092");
    assert_ne!(
        admit(&changed.to_string(), ARTIFACT_DIGEST)
            .unwrap()
            .request_hash,
        admitted.request_hash
    );
    let mut changed = original;
    changed["createdAtMicros"] = json!(i64::MIN.to_string());
    assert!(admit(&changed.to_string(), ARTIFACT_DIGEST).is_ok());
    changed["createdAtMicros"] = json!("00");
    assert_eq!(error(&changed), "noncanonical decimal createdAtMicros");
}

#[test]
fn strict_json_and_local_schema_fields_reject_before_admission() {
    let value = request();
    let text = value.to_string();
    let duplicate = format!(
        "{{\"schema\":\"opentsx.local-bounded-run.v1\",{}",
        text.strip_prefix('{').unwrap()
    );
    assert_eq!(
        admit(&duplicate, ARTIFACT_DIGEST).err().unwrap(),
        "json.invalid"
    );
    assert_eq!(
        admit(&(text.clone() + " {}"), ARTIFACT_DIGEST)
            .err()
            .unwrap(),
        "json.invalid"
    );
    assert_eq!(
        admit(&" ".repeat(REQUEST_BYTES + 1), ARTIFACT_DIGEST)
            .err()
            .unwrap(),
        "json.limit"
    );
    let mut value = request();
    value["schema"] = json!("opentsx.local-bounded-run.v999");
    assert_eq!(error(&value), "unsupported local request schema");
    let mut value = request();
    value["unknown"] = json!(true);
    assert!(error(&value).starts_with("local request fields: unknown field `unknown`"));
    let mut value = request();
    let raw = value["assignmentText"].as_str().unwrap();
    value["assignmentText"] = json!(format!(
        "{{\"schema\":\"opentsx.execution-assignment.v1\",{}",
        raw.strip_prefix('{').unwrap()
    ));
    assert_eq!(error(&value), "assignment.json");
}
