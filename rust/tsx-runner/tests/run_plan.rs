#[path = "common/run_profile.rs"]
mod run_profile;

use base64::{engine::general_purpose::STANDARD, Engine};
use run_profile::{Fixture, ARTIFACT_DIGEST, INPUT_TOPIC, OUTPUT_TOPIC, STATUS_TOPIC};
use serde_json::{json, Value};
use std::fs;
use tsx_runner::admission::{self, Position};
use tsx_runner::run_plan::{RunConfig, SignedPlan, MAX_COMPACT_BYTES, MAX_PAYLOAD_BYTES};

fn config(fixture: &Fixture) -> RunConfig {
    RunConfig::load(&fixture.config_path).unwrap()
}

fn plan(fixture: &Fixture) -> SignedPlan {
    SignedPlan::verify(
        &fixture.sign(&fixture.plan_document()),
        &config(fixture),
        ARTIFACT_DIGEST,
    )
    .unwrap_or_else(|error| panic!("{}: {}", error.code, error.message))
}

fn reject(fixture: &Fixture, value: &Value, code: &str) {
    let error = SignedPlan::verify(&fixture.sign(value), &config(fixture), ARTIFACT_DIGEST)
        .err()
        .expect("mutated plan must reject");
    assert_eq!(error.code, code, "{}", error.message);
}

fn cut() -> [Position; 2] {
    [
        Position {
            partition: 0,
            from: 0,
            to: 300,
        },
        Position {
            partition: 1,
            from: 0,
            to: 0,
        },
    ]
}

fn assignment(value: &mut Value, change: impl FnOnce(&mut Value)) {
    let mut document: Value =
        serde_json::from_str(value["assignmentText"].as_str().unwrap()).unwrap();
    change(&mut document);
    value["assignmentText"] = json!(document.to_string());
}

fn dag(value: &mut Value, change: impl FnOnce(&mut Value)) {
    let mut document: Value =
        serde_json::from_str(value["assignmentText"].as_str().unwrap()).unwrap();
    let mut fragment: Value =
        serde_json::from_str(document["fragmentText"].as_str().unwrap()).unwrap();
    change(&mut fragment);
    let text = fragment.to_string();
    let hash = tsx_contracts::sha256(&text);
    document["sourceDagText"] = json!(text);
    document["fragmentText"] = json!(text);
    document["specHash"] = json!(hash);
    document["fragmentHash"] = json!(hash);
    value["specHash"] = json!(hash);
    value["assignmentText"] = json!(document.to_string());
}

#[test]
fn verifies_signed_continuous_identity_and_derives_only_a_complete_bounded_cut() {
    let fixture = Fixture::new();
    let config = config(&fixture);
    let compact = fixture.sign(&fixture.plan_document());
    let plan = SignedPlan::verify(&compact, &config, ARTIFACT_DIGEST).unwrap();
    assert_eq!(plan.command_hash, tsx_contracts::sha256(&compact));
    assert_eq!(plan.job, fixture.job);
    assert_eq!(plan.plan_version, 1);
    assert_eq!(plan.input_topic, INPUT_TOPIC);
    assert_eq!(plan.output_topic, OUTPUT_TOPIC);
    assert_eq!(plan.status_topic, STATUS_TOPIC);
    assert_eq!(plan.assignment["mode"], "continuous");
    plan.require_fresh(plan.issued_micros).unwrap();
    plan.require_fresh(plan.expires_micros - 1).unwrap();
    let admitted = plan.admit_cut(&config, &cut()).unwrap();
    assert!(admitted.pipeline.is_passthrough());
    assert_eq!(admitted.assignment["mode"], "bounded");
    assert_eq!(
        admitted.assignment["sourceDagText"],
        plan.assignment["sourceDagText"]
    );
    assert_eq!(
        admitted.assignment["fragmentText"],
        plan.assignment["fragmentText"]
    );
    assert_eq!(admitted.cut.positions.len(), 2);
    assert_eq!(admitted.cut.positions[0].to, 300);
    assert_eq!(admitted.cut.positions[1].to, 0);
    assert_eq!(admitted.request.bindings["local"].outputs, [OUTPUT_TOPIC]);
    assert_eq!(admitted.request.limits.series, 1_024);
    assert_eq!(admitted.request.limits.samples, 1_000_000);
}

#[test]
fn signature_checks_reject_changed_bytes_unknown_keys_padding_and_other_headers() {
    let fixture = Fixture::new();
    let config = config(&fixture);
    let value = fixture.plan_document();
    let bytes = serde_json::to_vec(&value).unwrap();
    let compact = fixture.sign(&value);
    let parts: Vec<_> = compact.split('.').collect();
    let mut changed_payload = parts[1].to_owned();
    changed_payload.replace_range(
        ..1,
        if changed_payload.starts_with('A') {
            "B"
        } else {
            "A"
        },
    );
    let mut changed_signature = parts[2].to_owned();
    changed_signature.replace_range(
        ..1,
        if changed_signature.starts_with('A') {
            "B"
        } else {
            "A"
        },
    );
    let alphabet = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
    let last = alphabet
        .iter()
        .position(|byte| Some(byte) == parts[2].as_bytes().last())
        .unwrap();
    let mut noncanonical_signature = parts[2].to_owned();
    noncanonical_signature.replace_range(
        parts[2].len() - 1..,
        &char::from(alphabet[last + 1]).to_string(),
    );
    let other = Fixture::new();
    let invalid = [
        String::new(),
        "a.b".into(),
        format!("{compact}.extra"),
        format!("{}=.{}.{}", parts[0], parts[1], parts[2]),
        format!("{}.{}=.{}", parts[0], parts[1], parts[2]),
        format!("{}.{}.{}=", parts[0], parts[1], parts[2]),
        format!("{}.{}.{}", parts[0], changed_payload, parts[2]),
        format!("{}.{}.{}", parts[0], parts[1], changed_signature),
        format!("{}.{}.{}", parts[0], parts[1], noncanonical_signature),
        other.sign(&value),
        fixture.sign_header(&bytes, "{\"alg\":\"none\"}"),
        fixture.sign_header(&bytes, "{\"alg\":\"ES256\"}"),
        fixture.sign_header(&bytes, "{ \"alg\":\"EdDSA\"}"),
        fixture.sign_header(&bytes, "{\"alg\":\"EdDSA\",\"kid\":\"other\"}"),
        fixture.sign_header(&bytes, "{\"alg\":\"EdDSA\",\"alg\":\"EdDSA\"}"),
    ];
    for text in invalid {
        let error = SignedPlan::verify(&text, &config, ARTIFACT_DIGEST)
            .err()
            .unwrap();
        assert_eq!(error.code, "plan.signature");
    }
}

#[test]
fn signature_and_payload_limits_are_enforced_before_plan_parsing() {
    let fixture = Fixture::new();
    let config = config(&fixture);
    for text in [
        "a".repeat(MAX_COMPACT_BYTES + 1),
        fixture.sign_raw(&vec![b' '; MAX_PAYLOAD_BYTES + 1]),
    ] {
        assert_eq!(
            SignedPlan::verify(&text, &config, ARTIFACT_DIGEST)
                .err()
                .unwrap()
                .code,
            "plan.signature"
        );
    }
    for bytes in [
        &b"\xff"[..],
        &b"[]"[..],
        &b"{\"job\":\"one\",\"job\":\"two\"}"[..],
    ] {
        assert_eq!(
            SignedPlan::verify(&fixture.sign_raw(bytes), &config, ARTIFACT_DIGEST)
                .err()
                .unwrap()
                .code,
            "plan.invalid"
        );
    }
}

#[test]
fn outer_identity_contract_and_policy_mutations_are_refused() {
    let fixture = Fixture::new();
    for (pointer, replacement, code) in [
        ("/schema", json!("opentsx.runner-plan.v2"), "plan.invalid"),
        ("/job", json!("other"), "plan.invalid"),
        ("/planVersion", json!(0), "plan.invalid"),
        ("/planVersion", json!(2), "plan.invalid"),
        ("/planVersion", json!(1.0), "plan.invalid"),
        ("/site", json!("other"), "plan.site"),
        ("/catalog", json!("tsx-catalog/2"), "plan.catalog"),
        ("/specHash", json!("b".repeat(64)), "plan.invalid"),
        ("/issued", json!("invalid"), "plan.invalid"),
        ("/expires", json!("2026-11-17T00:00:00Z"), "plan.invalid"),
        (
            "/expires",
            json!("2026-11-19T00:00:00+00:00"),
            "plan.invalid",
        ),
        ("/inputContracts/0/binding", json!("other"), "plan.invalid"),
        (
            "/inputContracts/0/topic",
            json!(OUTPUT_TOPIC),
            "plan.invalid",
        ),
        (
            "/inputContracts/0/fingerprints",
            json!(["e51b48188baa6606"]),
            "plan.invalid",
        ),
        ("/outputs/0/cluster", json!("signal"), "plan.policy"),
        ("/outputs/0/topic", json!("tsx.b1.other.v2"), "plan.policy"),
        ("/outputs/0/sink", json!("other"), "plan.invalid"),
        ("/outputs/0/partitionCount", json!("02"), "plan.invalid"),
        ("/outputs/0/partitionCount", json!("129"), "plan.invalid"),
        (
            "/outputs/0/fingerprints",
            json!(["e51b48188baa6606"]),
            "plan.invalid",
        ),
    ] {
        let mut value = fixture.plan_document();
        *value.pointer_mut(pointer).unwrap() = replacement;
        reject(&fixture, &value, code);
    }
}

#[test]
fn assignment_runtime_identity_hash_and_partition_mutations_are_refused() {
    let fixture = Fixture::new();
    for (pointer, replacement, code) in [
        ("/runtime", json!("java"), "plan.invalid"),
        ("/mode", json!("bounded"), "plan.invalid"),
        ("/node", json!("other"), "plan.site"),
        ("/site", json!("other"), "plan.site"),
        ("/generation", json!("2"), "plan.invalid"),
        (
            "/artifactDigest",
            json!(format!("sha256:{}", "b".repeat(64))),
            "plan.invalid",
        ),
        ("/fragmentHash", json!("b".repeat(64)), "plan.invalid"),
        ("/inputs/0/partitionCount", json!("3"), "plan.invalid"),
        ("/inputs/0/positions/0/from", json!("-1"), "plan.invalid"),
        (
            "/inputs/0/positions/1/partition",
            json!("0"),
            "plan.invalid",
        ),
    ] {
        let mut value = fixture.plan_document();
        assignment(&mut value, |document| {
            *document.pointer_mut(pointer).unwrap() = replacement
        });
        reject(&fixture, &value, code);
    }
}

#[test]
fn unknown_fields_and_hidden_endpoint_injection_are_refused() {
    let fixture = Fixture::new();
    for name in ["unexpected", "federation", "bootstrap", "checkpoint"] {
        let mut value = fixture.plan_document();
        value[name] = json!("unused");
        reject(&fixture, &value, "plan.invalid");
    }
    let mut value = fixture.plan_document();
    assignment(&mut value, |document| {
        document["bootstrap"] = json!("unused:9092")
    });
    reject(&fixture, &value, "plan.invalid");
    for pointer in ["/source", "/nodes/0", "/sinks/0"] {
        let mut value = fixture.plan_document();
        dag(&mut value, |document| {
            document.pointer_mut(pointer).unwrap()["bootstrap"] = json!("unused:9092")
        });
        reject(&fixture, &value, "plan.invalid");
    }
}

#[test]
fn valid_cleaning_graphs_are_outside_the_signed_pass_through_profile() {
    let fixture = Fixture::new();
    let mut value = fixture.plan_document();
    dag(&mut value, |document| {
        document["source"]["series"] = json!(["0123456789abcdef"]);
        document["nodes"].as_array_mut().unwrap().push(json!({
            "id":"convert","op":"convert.unit","in":["decode"],"params":{"factor":2}
        }));
        document["sinks"][0]["from"] = json!("convert");
        document["sinks"][0]["signal"] = json!({"system":"b1","id":"plant/sensor","grade":"clean"});
    });
    reject(&fixture, &value, "plan.invalid");
}

#[test]
fn unsupported_operator_versions_use_the_catalog_refusal_code() {
    let fixture = Fixture::new();
    let mut value = fixture.plan_document();
    dag(&mut value, |document| {
        document["nodes"].as_array_mut().unwrap().push(json!({
            "id":"lag","op":"feature.lag","in":["decode"],"params":{"steps":1}
        }));
        document["sinks"][0]["from"] = json!("lag");
    });
    reject(&fixture, &value, "plan.catalog");
}

#[test]
fn structural_verification_does_not_force_fresh_expiry_on_accepted_resume() {
    let fixture = Fixture::new();
    let plan = plan(&fixture);
    assert_eq!(
        plan.require_fresh(plan.expires_micros).unwrap_err().code,
        "plan.expired"
    );
    assert_eq!(
        plan.require_fresh(plan.issued_micros - 1).unwrap_err().code,
        "plan.invalid"
    );
    plan.validate_authority().unwrap();
    plan.admit_cut(&config(&fixture), &cut()).unwrap();
    let mut value = fixture.plan_document();
    value["issued"] = json!("2026-11-17T00:00:00.000000001Z");
    value["expires"] = json!("2026-11-19T00:00:00.000000999Z");
    let fractional =
        SignedPlan::verify(&fixture.sign(&value), &config(&fixture), ARTIFACT_DIGEST).unwrap();
    assert_eq!(fractional.issued_micros, plan.issued_micros + 1);
    assert_eq!(fractional.expires_micros, plan.expires_micros);
}

#[test]
fn every_cut_retains_complete_ascending_inventory_and_signed_starts() {
    let fixture = Fixture::new();
    let mut value = fixture.plan_document();
    assignment(&mut value, |document| {
        document["inputs"][0]["positions"][0]["from"] = json!("5")
    });
    let config = config(&fixture);
    let plan = SignedPlan::verify(&fixture.sign(&value), &config, ARTIFACT_DIGEST).unwrap();
    for positions in [
        vec![Position {
            partition: 0,
            from: 5,
            to: 5,
        }],
        vec![
            Position {
                partition: 0,
                from: 4,
                to: 5,
            },
            Position {
                partition: 1,
                from: 0,
                to: 0,
            },
        ],
        vec![
            Position {
                partition: 0,
                from: 5,
                to: 4,
            },
            Position {
                partition: 1,
                from: 0,
                to: 0,
            },
        ],
        vec![
            Position {
                partition: 1,
                from: 5,
                to: 5,
            },
            Position {
                partition: 0,
                from: 0,
                to: 0,
            },
        ],
        vec![
            Position {
                partition: 0,
                from: 5,
                to: 10_006,
            },
            Position {
                partition: 1,
                from: 0,
                to: 0,
            },
        ],
    ] {
        assert_eq!(
            plan.admit_cut(&config, &positions).err().unwrap().code,
            "plan.invalid"
        );
    }
    plan.admit_cut(
        &config,
        &[
            Position {
                partition: 0,
                from: 5,
                to: 6,
            },
            Position {
                partition: 1,
                from: 9,
                to: 9,
            },
        ],
    )
    .unwrap();
}

#[test]
fn configuration_limits_and_derived_status_authority_fail_closed() {
    let fixture = Fixture::new();
    for (pointer, replacement) in [
        ("/schema", json!("other")),
        ("/site", json!("UPPER")),
        ("/driverPublicKey", json!("")),
        ("/checkpoint", json!("")),
        ("/bindings/local/bootstrap", json!("user@host:9092")),
        ("/bindings/local/outputs", json!([OUTPUT_TOPIC])),
        (
            "/bindings/local/outputs",
            json!([OUTPUT_TOPIC, OUTPUT_TOPIC]),
        ),
        (
            "/bindings/local/outputs",
            json!([OUTPUT_TOPIC, "tsx.other.dag-status.v1"]),
        ),
        ("/policy/domains", json!([])),
        ("/policy/domains", json!(["b1", "b1"])),
        ("/policy/catalogs", json!(["tsx-catalog/01"])),
    ] {
        let mut document = fixture.config_document();
        *document.pointer_mut(pointer).unwrap() = replacement;
        fixture.write_config(&document);
        assert_eq!(
            RunConfig::load(&fixture.config_path).err().unwrap().code,
            "plan.invalid"
        );
    }
    for (field, maximum) in [
        ("records", 10_000),
        ("payloadBytes", 1_048_576),
        ("inputBytes", 16_777_216),
        ("samples", 1_000_000),
        ("outputBytes", 16_777_216),
        ("series", 1_024),
        ("stateBytes", 1_048_576),
    ] {
        for invalid in [0, maximum + 1] {
            let mut document = fixture.config_document();
            document["limits"][field] = json!(invalid);
            fixture.write_config(&document);
            assert_eq!(
                RunConfig::load(&fixture.config_path).err().unwrap().code,
                "plan.invalid"
            );
        }
    }
}

#[test]
fn fresh_configuration_hash_binds_endpoint_policy_caps_and_actual_pinned_key() {
    let fixture = Fixture::new();
    let original = config(&fixture);
    let plan = plan(&fixture);
    for (pointer, replacement) in [
        ("/bindings/local/bootstrap", json!("127.0.0.1:39094")),
        ("/policy/domains", json!(["b1", "other"])),
        ("/limits/series", json!(512)),
    ] {
        let mut document = fixture.config_document();
        *document.pointer_mut(pointer).unwrap() = replacement;
        fixture.write_config(&document);
        let changed = config(&fixture);
        assert_ne!(changed.identity_hash, original.identity_hash);
        assert_eq!(
            plan.admit_cut(&changed, &cut()).err().unwrap().code,
            "plan.policy"
        );
    }
    fixture.write_config(&fixture.config_document());
    let other = Fixture::new();
    fs::copy(
        other.directory.join("driver-public.pem"),
        fixture.directory.join("driver-public.pem"),
    )
    .unwrap();
    assert_ne!(config(&fixture).identity_hash, original.identity_hash);
    assert_eq!(
        SignedPlan::verify(
            &fixture.sign(&fixture.plan_document()),
            &config(&fixture),
            ARTIFACT_DIGEST
        )
        .err()
        .unwrap()
        .code,
        "plan.signature"
    );
}

#[test]
fn identical_config_bytes_cannot_migrate_a_relative_checkpoint_to_another_directory() {
    let fixture = Fixture::new();
    let other = Fixture::new();
    let mut document = fixture.config_document();
    document["checkpoint"] = json!("checkpoint.json");
    fixture.write_config(&document);
    fs::copy(&fixture.config_path, &other.config_path).unwrap();
    fs::copy(
        fixture.directory.join("driver-public.pem"),
        other.directory.join("driver-public.pem"),
    )
    .unwrap();
    let first = config(&fixture);
    let second = config(&other);
    assert_eq!(
        fs::read(&fixture.config_path).unwrap(),
        fs::read(&other.config_path).unwrap()
    );
    assert_ne!(first.checkpoint, second.checkpoint);
    assert_ne!(first.identity_hash, second.identity_hash);
    let signed = SignedPlan::verify(
        &fixture.sign(&fixture.plan_document()),
        &first,
        ARTIFACT_DIGEST,
    )
    .unwrap();
    assert_eq!(
        signed.admit_cut(&second, &cut()).err().unwrap().code,
        "plan.policy"
    );
}

#[test]
fn canonical_configuration_path_aliases_preserve_the_effective_checkpoint_identity() {
    let fixture = Fixture::new();
    let mut document = fixture.config_document();
    document["checkpoint"] = json!("checkpoint.json");
    fixture.write_config(&document);
    let normal = config(&fixture);
    let alias = RunConfig::load(&fixture.directory.join(".").join("run.json")).unwrap();
    assert!(normal.checkpoint.is_absolute());
    assert_eq!(normal.checkpoint, alias.checkpoint);
    assert_eq!(normal.identity_hash, alias.identity_hash);
}

#[test]
fn loaded_local_policy_controls_domains_catalogs_bindings_and_runtime_identity() {
    let fixture = Fixture::new();
    for (pointer, replacement, code) in [
        ("/policy/domains", json!(["other"]), "plan.policy"),
        ("/policy/catalogs", json!(["tsx-catalog/2"]), "plan.catalog"),
        (
            "/bindings/local/outputs",
            json!(["tsx.b1.other.v2", STATUS_TOPIC]),
            "plan.policy",
        ),
        ("/node", json!("other"), "plan.site"),
        ("/site", json!("other"), "plan.site"),
    ] {
        let mut document = fixture.config_document();
        *document.pointer_mut(pointer).unwrap() = replacement;
        fixture.write_config(&document);
        let error = SignedPlan::verify(
            &fixture.sign(&fixture.plan_document()),
            &config(&fixture),
            ARTIFACT_DIGEST,
        )
        .err()
        .unwrap();
        assert_eq!(error.code, code);
    }
}

#[test]
fn domains_use_the_existing_topic_layout_rules_including_underscores() {
    let fixture = Fixture::new();
    let input = "tsx.wind_park.episodes.v2";
    let output = "tsx.wind_park.clean.v2";
    let status = "tsx.wind_park.dag-status.v1";
    let mut document = fixture.config_document();
    document["bindings"]["local"]["inputs"] = json!([input]);
    document["bindings"]["local"]["outputs"] = json!([output, status]);
    document["policy"]["domains"] = json!(["wind_park"]);
    fixture.write_config(&document);
    let config = config(&fixture);
    let mut value = fixture.plan_document();
    dag(&mut value, |document| {
        document["source"]["topics"] = json!([input]);
        document["sinks"][0]["topic"] = json!(output);
    });
    assignment(&mut value, |document| {
        document["inputs"][0]["topic"] = json!(input)
    });
    value["inputContracts"][0]["topic"] = json!(input);
    value["outputs"][0]["topic"] = json!(output);
    let plan = SignedPlan::verify(&fixture.sign(&value), &config, ARTIFACT_DIGEST).unwrap();
    assert_eq!(plan.status_topic, status);
    plan.admit_cut(&config, &cut()).unwrap();
}

#[test]
fn invalid_and_weak_spki_public_keys_are_rejected_without_private_key_fixtures() {
    let fixture = Fixture::new();
    let original = fs::read_to_string(fixture.directory.join("driver-public.pem")).unwrap();
    let body: String = original
        .lines()
        .filter(|line| !line.starts_with("-----"))
        .collect();
    let mut der = STANDARD.decode(body).unwrap();
    let offset = der.len() - 32;
    der[offset..].fill(0);
    der[offset] = 1;
    let weak = format!(
        "-----BEGIN PUBLIC KEY-----\n{}\n-----END PUBLIC KEY-----\n",
        STANDARD.encode(der)
    );
    for (text, message) in [
        (weak, "driver public key is weak"),
        (
            "invalid".into(),
            "driver public key must be Ed25519 SPKI PEM",
        ),
    ] {
        fs::write(fixture.directory.join("driver-public.pem"), text).unwrap();
        assert_eq!(
            RunConfig::load(&fixture.config_path).err().unwrap().message,
            message
        );
    }
}

#[test]
fn mutating_public_plan_views_cannot_change_verified_authority() {
    let fixture = Fixture::new();
    let config = config(&fixture);
    let changes: [fn(&mut SignedPlan); 8] = [
        |p| p.command_hash = "b".repeat(64),
        |p| p.job = "other".into(),
        |p| p.plan_version = 2,
        |p| p.expires_micros = i64::MAX,
        |p| p.status_topic = "tsx.other.dag-status.v1".into(),
        |p| p.output_partitions = 12,
        |p| p.assignment["inputs"][0]["positions"][0]["from"] = json!("9"),
        |p| p.positions[0].from = -1,
    ];
    for change in changes {
        let mut plan = plan(&fixture);
        change(&mut plan);
        assert_eq!(plan.validate_authority().unwrap_err().code, "plan.invalid");
        assert_eq!(plan.require_fresh(0).unwrap_err().code, "plan.invalid");
        assert_eq!(
            plan.admit_cut(&config, &cut()).err().unwrap().code,
            "plan.invalid"
        );
    }
}

#[test]
fn mutating_public_configuration_views_cannot_broaden_loaded_authority() {
    let fixture = Fixture::new();
    let changes: [fn(&mut RunConfig); 5] = [
        |c| c.identity_hash = "b".repeat(64),
        |c| c.site = "other".into(),
        |c| c.bindings.get_mut("local").unwrap().bootstrap = "127.0.0.1:39094".into(),
        |c| c.policy.domains.push("other".into()),
        |c| c.limits.series = 512,
    ];
    for change in changes {
        let mut config = config(&fixture);
        change(&mut config);
        assert_eq!(config.require_validated().unwrap_err().code, "plan.invalid");
        assert_eq!(
            SignedPlan::verify(
                &fixture.sign(&fixture.plan_document()),
                &config,
                ARTIFACT_DIGEST
            )
            .err()
            .unwrap()
            .code,
            "plan.invalid"
        );
    }
}

#[test]
fn expanded_signed_run_limits_do_not_expand_unsigned_replay_limits() {
    let fixture = Fixture::new();
    let admitted = plan(&fixture).admit_cut(&config(&fixture), &cut()).unwrap();
    let mut request = json!({
        "schema":"opentsx.local-bounded-run.v1","assignmentText":admitted.request.assignment_text,
        "bindings":{"local":{"bootstrap":"127.0.0.1:39093","inputs":[INPUT_TOPIC],"outputs":[OUTPUT_TOPIC]}},
        "sink":{"name":"pass","binding":"local","topic":OUTPUT_TOPIC,"partitionCount":"2"},
        "createdAtMicros":admitted.request.created_at_micros,"limits":fixture.config_document()["limits"]
    });
    assert_eq!(
        admission::admit(&request.to_string(), ARTIFACT_DIGEST)
            .err()
            .unwrap(),
        "samples must be within 1..=100000"
    );
    request["limits"]["samples"] = json!(100_000);
    assert_eq!(
        admission::admit(&request.to_string(), ARTIFACT_DIGEST)
            .err()
            .unwrap(),
        "series must be within 1..=64"
    );
}
