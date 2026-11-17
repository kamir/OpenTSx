use tsx_contracts::{parse_json, sha256_bytes};

#[test]
fn local_runner_documents_share_strict_json_admission() {
    assert!(parse_json(
        r#"{"bindings":{"local":{"bootstrap":"localhost:9092"}}}"#,
        65_536
    )
    .is_ok());
    assert_eq!(
        parse_json(r#"{"name":1,"name":2}"#, 65_536),
        Err("json.invalid")
    );
    assert_eq!(
        parse_json(r#"{"value":1e999}"#, 65_536),
        Err("json.invalid")
    );
    assert_eq!(parse_json("{} {}", 65_536), Err("json.invalid"));
    assert_eq!(parse_json(&" ".repeat(65_537), 65_536), Err("json.limit"));
}

#[test]
fn executable_digests_hash_arbitrary_binary_bytes() {
    assert_eq!(
        sha256_bytes(&[0xff, 0, 0xc3]),
        "27685550a0acef2414b78a2407b13c2e893dc1b4d8fb22c5f9cf1de9e79b57f7"
    );
}
