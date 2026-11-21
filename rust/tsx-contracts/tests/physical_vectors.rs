use serde_json::Value;
use std::fs;
use std::path::PathBuf;
use tsx_contracts::{physical, sha256};

#[test]
fn shared_physical_deployment_vectors() {
    let path = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("../../docs/datamodel-2026/testvectors/physical-deployment.v1.json");
    let fixture: Value = serde_json::from_str(&fs::read_to_string(path).unwrap()).unwrap();
    assert_eq!(fixture["schema"], "opentsx.physical-deployment-vectors.v1");
    let cases = fixture["cases"].as_array().unwrap();
    for case in cases {
        let name = case["name"].as_str().unwrap();
        match case["kind"].as_str().unwrap() {
            "canonical" | "computation" => {
                let actual = if case["kind"] == "canonical" {
                    physical::canonical(&case["input"]).unwrap()
                } else {
                    physical::encode_computation(&case["input"]).unwrap()
                };
                assert_eq!(actual, case["canonical"].as_str().unwrap(), "{name}");
                assert_eq!(sha256(&actual), case["sha256"], "{name}");
                if case["kind"] == "computation" {
                    assert_eq!(
                        physical::encode_computation(
                            &physical::decode_computation(&actual).unwrap()
                        )
                        .unwrap(),
                        actual,
                        "{name}"
                    );
                }
            }
            "physical" => {
                let actual = physical::admit(
                    case["input"].as_str().unwrap(),
                    &case["facts"],
                    case["nowMicros"].as_str().unwrap(),
                )
                .err()
                .map(|error| error.value())
                .unwrap_or(Value::Null);
                assert_eq!(actual, case["refusal"], "{name}");
            }
            kind => panic!("unknown vector kind {kind}"),
        }
    }
    println!("checked {} shared physical deployment cases", cases.len());
}

#[test]
fn computation_refuses_unrepresentable_integer_and_nonfinite_tag() {
    assert!(physical::encode_computation(&serde_json::json!(9_007_199_254_740_993u64)).is_err());
    assert!(physical::decode_computation(
        "[\"opentsx.computation.v1\",[\"f64\",\"7ff0000000000000\"]]"
    )
    .is_err());
    assert!(physical::decode_computation(
        "[\"opentsx.computation.v1\",[\"f64\",\"7ff8000000000001\"]]"
    )
    .is_err());
    assert!(physical::decode_computation(
        "[\"opentsx.computation.v1\",[\"object\",[[\"x\",[\"null\"]],[\"x\",[\"null\"]]]]]"
    )
    .is_err());
}

#[test]
fn computation_identity_has_its_own_version_frame() {
    let encoded = physical::encode_computation(&serde_json::json!(-0.0)).unwrap();
    assert_eq!(
        encoded,
        "[\"opentsx.computation.v1\",[\"f64\",\"8000000000000000\"]]"
    );
    for input in [
        "[\"f64\",\"8000000000000000\"]",
        "[\"opentsx.computation.v2\",[\"null\"]]",
        "[\"opentsx.computation.v1\"]",
        "[\"opentsx.computation.v1\",[\"null\"],null]",
    ] {
        let error = physical::decode_computation(input).unwrap_err();
        assert_eq!(error.code, "deployment.computation");
        assert_eq!(error.field, "/computationText");
    }
}

#[test]
fn physical_contract_refuses_input_above_byte_ceiling() {
    let text = " ".repeat(physical::MAX_PHYSICAL_BYTES + 1);
    assert_eq!(
        physical::validate(&text).unwrap_err().code,
        "deployment.limit"
    );
}

#[test]
fn raw_fragment_integer_literal_cannot_round_before_computation_identity() {
    let path = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("../../docs/datamodel-2026/testvectors/physical-deployment.v1.json");
    let fixture: Value = serde_json::from_str(&fs::read_to_string(path).unwrap()).unwrap();
    let case = fixture["cases"]
        .as_array()
        .unwrap()
        .iter()
        .find(|case| case["name"] == "complete-bounded-deployment")
        .unwrap();
    let mut plan: Value = serde_json::from_str(case["input"].as_str().unwrap()).unwrap();
    let source = plan["sourceDagText"]
        .as_str()
        .unwrap()
        .replace("\"version\":1", "\"version\":18446744073709551617");
    let rounded: Value = serde_json::from_str(&source).unwrap();
    plan["sourceDagText"] = serde_json::json!(source);
    plan["specHash"] = serde_json::json!(sha256(&source));
    plan["computationText"] = serde_json::json!(physical::encode_computation(&rounded).unwrap());
    plan["computationHash"] = serde_json::json!(sha256(plan["computationText"].as_str().unwrap()));
    let mut assignment: Value =
        serde_json::from_str(plan["assignmentTexts"][0].as_str().unwrap()).unwrap();
    assignment["sourceDagText"] = plan["sourceDagText"].clone();
    assignment["specHash"] = plan["specHash"].clone();
    assignment["fragmentText"] = plan["sourceDagText"].clone();
    assignment["fragmentHash"] = plan["specHash"].clone();
    plan["assignmentTexts"][0] = serde_json::json!(assignment.to_string());
    plan["planHash"] = serde_json::json!(physical::plan_hash(&plan).unwrap());
    assert_eq!(
        physical::validate(&plan.to_string()).unwrap_err().code,
        "deployment.computation"
    );
}

#[test]
fn decimal_literals_preserve_effective_binary64_bits() {
    let mut state = 0x1234_5678_9abc_def0u64;
    for _ in 0..10_000 {
        state = state
            .wrapping_mul(6_364_136_223_846_793_005)
            .wrapping_add(1);
        let expected = f64::from_bits(state);
        if expected.is_finite() {
            let text = serde_json::to_string(&expected).unwrap();
            let parsed: Value = serde_json::from_str(&text).unwrap();
            assert_eq!(parsed.as_f64().unwrap().to_bits(), state, "{text}");
        }
    }
}
