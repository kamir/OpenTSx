use serde_json::Value;
use std::collections::HashSet;
use std::fs;
use std::path::PathBuf;
use tsx_contracts::{sha256, validate, validate_set};

#[test]
fn shared_execution_contract_vectors() {
    let path = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("../../docs/datamodel-2026/testvectors/execution-contracts.v1.json");
    let fixture: Value =
        serde_json::from_str(&fs::read_to_string(path).expect("read shared vectors"))
            .expect("parse shared vectors");
    assert_eq!(fixture["schema"], "opentsx.execution-contract-vectors.v1");
    let cases = fixture["cases"].as_array().expect("shared cases array");
    let mut names = HashSet::new();
    let mut counts = [0; 3];
    for case in cases {
        let name = case["name"].as_str().expect("case name");
        assert!(names.insert(name), "duplicate case name: {name}");
        match case["kind"].as_str().expect("case kind") {
            "assignment" => {
                counts[0] += 1;
                let actual = validate(case["input"].as_str().expect("raw assignment JSON"));
                assert_eq!(actual.err(), expected_error(case), "{name}");
            }
            "set" => {
                counts[1] += 1;
                let inputs: Vec<_> = case["inputs"]
                    .as_array()
                    .expect("raw assignment array")
                    .iter()
                    .map(|input| input.as_str().expect("raw assignment JSON"))
                    .collect();
                assert_eq!(validate_set(&inputs).err(), expected_error(case), "{name}");
            }
            "hash" => {
                counts[2] += 1;
                let input = case["text"].as_str().expect("hash text");
                assert_eq!(
                    sha256(input),
                    case["sha256"].as_str().expect("SHA-256"),
                    "{name}"
                );
                let hex: String = input
                    .as_bytes()
                    .iter()
                    .map(|byte| format!("{byte:02x}"))
                    .collect();
                assert_eq!(
                    hex,
                    case["utf8Hex"].as_str().expect("UTF-8 bytes"),
                    "{name}"
                );
            }
            other => panic!("unknown shared case kind: {other}"),
        }
    }
    assert!(counts.iter().all(|count| *count > 0));
    println!(
        "checked {} shared cases: {} assignments, {} sets, {} hashes",
        cases.len(),
        counts[0],
        counts[1],
        counts[2]
    );
}

fn expected_error(case: &Value) -> Option<&str> {
    match case.get("error").expect("expected error field") {
        Value::Null => None,
        Value::String(code) => Some(code),
        _ => panic!("expected error must be null or a code string"),
    }
}
