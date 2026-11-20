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
                let actual = validate(&input(case));
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
    let expanded: Vec<_> = cases.iter().filter(|case| case.get("expand").is_some()).collect();
    assert_eq!(expanded.len(), 5, "the five size-boundary cases use expand");
    println!(
        "checked {} shared cases: {} assignments, {} sets, {} hashes",
        cases.len(),
        counts[0],
        counts[1],
        counts[2]
    );
}

/// The raw assignment of a case, with its `expand` marker replaced: boundary cases near the size limits keep
/// their padding out of the file.
fn input(case: &Value) -> String {
    let raw = case["input"].as_str().expect("raw assignment JSON");
    let Some(expand) = case.get("expand") else {
        return raw.to_string();
    };
    let marker = expand["marker"].as_str().expect("expand marker");
    let text = expand["text"].as_str().expect("expand text");
    let count = usize::try_from(expand["count"].as_u64().expect("expand count")).unwrap();
    assert_eq!(raw.matches(marker).count(), 1, "one expand marker per input");
    raw.replace(marker, &text.repeat(count))
}

fn expected_error(case: &Value) -> Option<&str> {
    match case.get("error").expect("expected error field") {
        Value::Null => None,
        Value::String(code) => Some(code),
        _ => panic!("expected error must be null or a code string"),
    }
}
