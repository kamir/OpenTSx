use serde_json::Value;
use tsx_runner::model::{bucket_checksum, xxh64_hex};

#[test]
fn all_shared_xxh64_cases_match_exactly() {
    let cases: Value = serde_json::from_str(include_str!(
        "../../../opentsx-model/src/test/resources/testvectors/xxh64.json"
    ))
    .unwrap();
    let cases = cases.as_array().unwrap();
    assert_eq!(cases.len(), 7);
    for case in cases {
        assert_eq!(
            xxh64_hex(case["input"].as_str().unwrap().as_bytes()),
            case["hex"].as_str().unwrap()
        );
    }
}

#[test]
fn all_shared_bucket_checksum_cases_match_exactly() {
    let cases: Value = serde_json::from_str(include_str!(
        "../../../opentsx-model/src/test/resources/testvectors/bucket-checksum.json"
    ))
    .unwrap();
    let cases = cases.as_array().unwrap();
    assert_eq!(cases.len(), 3);
    for case in cases {
        let episode_ids: Vec<_> = case["episodeIds"]
            .as_array()
            .unwrap()
            .iter()
            .map(|value| value.as_str().unwrap().to_owned())
            .collect();
        assert_eq!(
            bucket_checksum(&episode_ids),
            case["checksum"].as_str().unwrap()
        );
    }
}
