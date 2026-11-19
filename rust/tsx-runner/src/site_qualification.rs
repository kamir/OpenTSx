use crate::run_plan::Refusal;
use crate::site_plan::{fields, instant, text_field, STATE_ABI};
use serde_json::{json, Value};
use std::collections::{BTreeMap, BTreeSet};

const VECTORS: [(&str, &[u8]); 14] = [
    (
        "opentsx-model/src/test/resources/testvectors/wire-format.json",
        include_bytes!("../../../opentsx-model/src/test/resources/testvectors/wire-format.json"),
    ),
    (
        "opentsx-model/src/test/resources/testvectors/series-id.json",
        include_bytes!("../../../opentsx-model/src/test/resources/testvectors/series-id.json"),
    ),
    (
        "opentsx-model/src/test/resources/testvectors/xxh64.json",
        include_bytes!("../../../opentsx-model/src/test/resources/testvectors/xxh64.json"),
    ),
    (
        "opentsx-model/src/test/resources/testvectors/bucket-checksum.json",
        include_bytes!(
            "../../../opentsx-model/src/test/resources/testvectors/bucket-checksum.json"
        ),
    ),
    (
        "opentsx-model/src/test/resources/testvectors/time-deltas.json",
        include_bytes!("../../../opentsx-model/src/test/resources/testvectors/time-deltas.json"),
    ),
    (
        "docs/datamodel-2026/testvectors/clean-ops.v1.json",
        include_bytes!("../../../docs/datamodel-2026/testvectors/clean-ops.v1.json"),
    ),
    (
        "docs/datamodel-2026/testvectors/stats-window.v1.json",
        include_bytes!("../../../docs/datamodel-2026/testvectors/stats-window.v1.json"),
    ),
    (
        "docs/datamodel-2026/testvectors/align-vector.v1.json",
        include_bytes!("../../../docs/datamodel-2026/testvectors/align-vector.v1.json"),
    ),
    (
        "docs/datamodel-2026/testvectors/segment-threshold.v1.json",
        include_bytes!("../../../docs/datamodel-2026/testvectors/segment-threshold.v1.json"),
    ),
    (
        "docs/datamodel-2026/testvectors/segment-extremum.v1.json",
        include_bytes!("../../../docs/datamodel-2026/testvectors/segment-extremum.v1.json"),
    ),
    (
        "docs/datamodel-2026/testvectors/sax-word.v1.json",
        include_bytes!("../../../docs/datamodel-2026/testvectors/sax-word.v1.json"),
    ),
    (
        "docs/datamodel-2026/testvectors/dq-result.v1.json",
        include_bytes!("../../../docs/datamodel-2026/testvectors/dq-result.v1.json"),
    ),
    (
        "docs/datamodel-2026/testvectors/pair-xcorr.v1.json",
        include_bytes!("../../../docs/datamodel-2026/testvectors/pair-xcorr.v1.json"),
    ),
    (
        "docs/datamodel-2026/testvectors/execution-contracts.v1.json",
        include_bytes!("../../../docs/datamodel-2026/testvectors/execution-contracts.v1.json"),
    ),
];

pub(crate) const SUITES: [&str; 6] = [
    "clean_catalog",
    "stats_vectors",
    "retrieval_vectors",
    "pair_vector_vectors",
    "catalog_admission",
    "catalog_flow",
];

pub(crate) struct Qualification {
    value: Value,
    issued: i64,
    expires: i64,
    identity: Value,
}

impl Qualification {
    pub(crate) fn parse(value: Value, endpoint: &str) -> Result<Self, Refusal> {
        fields(
            &value,
            &[
                "schema",
                "artifact",
                "endpoint",
                "issued",
                "expires",
                "vectors",
                "tests",
                "protocol",
                "placement",
                "logs",
            ],
        )?;
        if value["schema"] != "opentsx.rust-b3-qualification.v1" || value["endpoint"] != endpoint {
            return Err(invalid("qualification endpoint or schema differs"));
        }
        let artifact = &value["artifact"];
        fields(artifact, &["version", "digest", "stateAbi", "catalog"])?;
        if artifact["version"] != env!("CARGO_PKG_VERSION")
            || artifact["catalog"] != "tsx-catalog/1"
            || artifact["stateAbi"] != STATE_ABI
            || !text_field(artifact, "digest")?
                .strip_prefix("sha256:")
                .is_some_and(hash)
            || value["vectors"] != vector_hashes()
        {
            return Err(invalid(
                "qualification runtime, catalog, state ABI or vectors differ",
            ));
        }
        let issued = instant(&value, "issued")?;
        let expires = instant(&value, "expires")?;
        if expires <= issued {
            return Err(invalid("qualification validity interval is invalid"));
        }
        let tests = &value["tests"];
        fields(
            tests,
            &[
                "passed",
                "failed",
                "ignored",
                "measured",
                "filtered",
                "sharedVectorTests",
                "acceptanceExampleTests",
                "vectorSuites",
            ],
        )?;
        if tests["passed"].as_u64().is_none_or(|v| v < 259)
            || ["failed", "ignored", "measured", "filtered"]
                .iter()
                .any(|name| tests[*name].as_u64() != Some(0))
        {
            return Err(invalid(
                "qualification tests did not all execute successfully",
            ));
        }
        for field in ["sharedVectorTests", "acceptanceExampleTests"] {
            let names = tests[field]
                .as_array()
                .filter(|v| !v.is_empty())
                .ok_or_else(|| invalid("qualification executed test names are missing"))?;
            let unique = names
                .iter()
                .map(|v| {
                    v.as_str()
                        .filter(|name| !name.trim().is_empty())
                        .ok_or_else(|| invalid("qualification executed test name is invalid"))
                })
                .collect::<Result<BTreeSet<_>, _>>()?;
            if unique.len() != names.len() {
                return Err(invalid("qualification executed test names are duplicated"));
            }
        }
        let suites = tests["vectorSuites"]
            .as_array()
            .ok_or_else(|| invalid("qualification vector suites are missing"))?;
        let actual = suites
            .iter()
            .map(|v| {
                v.as_str()
                    .ok_or_else(|| invalid("qualification suite is invalid"))
            })
            .collect::<Result<BTreeSet<_>, _>>()?;
        if actual != SUITES.into_iter().collect() || suites.len() != SUITES.len() {
            return Err(invalid("qualification vector suite inventory differs"));
        }
        let logs = value["logs"]
            .as_array()
            .filter(|v| v.len() >= 2 && v.len() <= 16)
            .ok_or_else(|| invalid("qualification log evidence is missing"))?;
        if logs.iter().any(|v| !v.as_str().is_some_and(hash)) {
            return Err(invalid("qualification log digest is invalid"));
        }
        let protocol = &value["protocol"];
        fields(
            protocol,
            &[
                "schema",
                "runnerVersion",
                "endpoint",
                "requiredFamilies",
                "requiredCases",
                "executedFamilies",
                "executedCases",
                "passedCases",
                "failedCases",
                "cases",
            ],
        )?;
        if protocol["schema"] != "opentsx.rust-b1-conformance.v1"
            || protocol["runnerVersion"] != env!("CARGO_PKG_VERSION")
            || protocol["endpoint"] != endpoint
            || ["requiredCases", "executedCases", "passedCases"]
                .iter()
                .any(|name| protocol[*name].as_u64() != Some(10))
            || ["requiredFamilies", "executedFamilies"]
                .iter()
                .any(|name| protocol[*name].as_u64() != Some(7))
            || protocol["failedCases"].as_u64() != Some(0)
        {
            return Err(invalid("qualification protocol report differs or failed"));
        }
        let cases = protocol["cases"]
            .as_array()
            .filter(|v| v.len() == 10)
            .ok_or_else(|| invalid("qualification protocol cases are missing"))?;
        let mut families = BTreeMap::<&str, usize>::new();
        let mut names = BTreeSet::new();
        for case in cases {
            fields(case, &["family", "case", "status", "detail"])?;
            let family = text_field(case, "family")?;
            let name = text_field(case, "case")?;
            if case["status"] != "pass"
                || text_field(case, "detail")?.trim().is_empty()
                || !names.insert((family, name))
            {
                return Err(invalid(
                    "qualification protocol case did not pass exactly once",
                ));
            }
            *families.entry(family).or_default() += 1;
        }
        let expected = BTreeMap::from([
            ("topics.admin", 1),
            ("topics.batch", 1),
            ("records.order", 1),
            ("records.codec", 4),
            ("records.large", 1),
            ("offsets.committed", 1),
            ("offsets.explicit", 1),
        ]);
        if families != expected {
            return Err(invalid("qualification protocol families differ"));
        }
        let placement = &value["placement"];
        fields(placement, &["java", "rust"])?;
        for name in ["java", "rust"] {
            let result = &placement[name];
            if name == "rust" {
                fields(
                    result,
                    &["schema", "keys", "partitions", "matched", "fixtureSha256"],
                )?;
                if !hash(text_field(result, "fixtureSha256")?) {
                    return Err(invalid("qualification placement fixture digest is invalid"));
                }
            } else {
                fields(result, &["schema", "keys", "partitions", "matched"])?;
            }
            if result["schema"] != "opentsx.rust-b1-placement-result.v1"
                || result["keys"].as_u64() != Some(10_000)
                || result["partitions"].as_u64() != Some(12)
                || result["matched"].as_u64() != Some(10_000)
            {
                return Err(invalid("qualification producer placement did not pass"));
            }
        }
        let identity = json!({"artifact":artifact,"endpoint":endpoint,"vectors":value["vectors"]});
        Ok(Self {
            value,
            issued,
            expires,
            identity,
        })
    }
    pub(crate) fn value(&self) -> &Value {
        &self.value
    }
    pub(crate) fn identity(&self) -> &Value {
        &self.identity
    }
    pub(crate) fn issued(&self) -> i64 {
        self.issued
    }
    pub(crate) fn expires(&self) -> i64 {
        self.expires
    }
    pub(crate) fn check_artifact(&self, artifact: &str) -> Result<(), Refusal> {
        if self.value["artifact"]["digest"] != artifact {
            Err(invalid("qualification does not identify this executable"))
        } else {
            Ok(())
        }
    }
}

pub(crate) fn vector_hashes() -> Value {
    let mut hashes = serde_json::Map::new();
    for (name, bytes) in VECTORS {
        hashes.insert(
            name.into(),
            Value::String(tsx_contracts::sha256_bytes(bytes)),
        );
    }
    Value::Object(hashes)
}

fn hash(value: &str) -> bool {
    value.len() == 64
        && value
            .bytes()
            .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
}
fn invalid(message: impl Into<String>) -> Refusal {
    Refusal::new("plan.invalid", message)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::federation::tests::Fixture;

    #[test]
    fn qualification_requires_executed_test_names_and_numeric_counters() {
        let fixture = Fixture::new();
        let original = fixture.config.qualification().clone();
        for field in ["sharedVectorTests", "acceptanceExampleTests"] {
            for invalid in [
                Value::Bool(true),
                json!([]),
                json!([true]),
                json!(["same", "same"]),
            ] {
                let mut value = original.clone();
                value["tests"][field] = invalid;
                assert!(
                    Qualification::parse(value, "127.0.0.1:39092").is_err(),
                    "accepted invalid {field}"
                );
            }
        }
        let mut value = original;
        value["tests"]["failed"] = json!(false);
        assert!(Qualification::parse(value, "127.0.0.1:39092").is_err());
    }

    #[test]
    fn qualification_binds_exact_vectors_endpoint_artifact_and_protocol_inventory() {
        let fixture = Fixture::new();
        let original = fixture.config.qualification().clone();
        let parsed = Qualification::parse(original.clone(), "127.0.0.1:39092").unwrap();
        assert!(parsed.check_artifact("sha256:wrong").is_err());
        assert!(Qualification::parse(original.clone(), "127.0.0.1:39093").is_err());
        let mut vectors = original.clone();
        vectors["vectors"]
            .as_object_mut()
            .unwrap()
            .remove("docs/datamodel-2026/testvectors/stats-window.v1.json");
        assert!(Qualification::parse(vectors, "127.0.0.1:39092").is_err());
        let mut cases = original;
        cases["protocol"]["cases"][0] = cases["protocol"]["cases"][1].clone();
        assert!(Qualification::parse(cases, "127.0.0.1:39092").is_err());
    }
}
