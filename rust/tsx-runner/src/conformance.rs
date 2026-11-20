use crate::admission::{Cut, Limits, Position};
use crate::federation::{FederationIo, KafkaFederationIo};
use crate::site_client::SiteClient;
use crate::site_plan::{fields, instant, text_field, SiteConfig, STATE_ABI};
use serde::Deserialize;
use serde_json::{json, value::RawValue, Value};
use std::collections::{BTreeMap, BTreeSet};
use time::{format_description::well_known::Rfc3339, OffsetDateTime};

const CHECKS: [&str; 10] = [
    "vectors.wire",
    "vectors.series-id",
    "vectors.operators",
    "kafscale.required",
    "topics.names",
    "topics.partitions",
    "topics.fingerprints",
    "partitioner.murmur2",
    "series.disjoint",
    "runtime.identity",
];

pub const UNKNOWN_JOB: &str = "conformance challenge HTTP status 404";

#[derive(Deserialize)]
struct RawChallenge<'a> {
    #[serde(borrow)]
    contract: &'a RawValue,
}

struct Admission {
    challenge: Value,
    binding: String,
    cut: Cut,
    output_topic: String,
    output_partitions: usize,
}

pub fn conform_once(config: &SiteConfig, artifact: &str, job: &str) -> Result<Value, String> {
    let now = now()?;
    config
        .qualification_at(now, artifact)
        .map_err(|e| e.to_string())?;
    config.check_tls_pins().map_err(|e| e.to_string())?;
    if !config.allowed_job(job) {
        return Err("conformance job violates local site policy".into());
    }
    let client = SiteClient::new(
        config.driver_endpoint(),
        config.ca(),
        config.certificate(),
        config.private_key(),
    )?;
    let response = client.request(
        "GET",
        &format!(
            "/v1/sites/{}/conformance-challenge?job={job}",
            config.site()
        ),
        &[],
    )?;
    if response.status == 404 {
        return Err(UNKNOWN_JOB.into());
    }
    if response.status != 200 {
        return Err(format!(
            "conformance challenge HTTP status {}",
            response.status
        ));
    }
    let admission = admit(&response.body, config, artifact, job, now)?;
    let report = report(&admission, config, artifact, now, &mut KafkaFederationIo)?;
    let compact = config.sign_report(&report).map_err(|e| e.to_string())?;
    let response = client.request(
        "POST",
        &format!("/v1/sites/{}/conformance", config.site()),
        compact.as_bytes(),
    )?;
    accepted(response.status, &response.body)?;
    Ok(
        json!({"result":"accepted","challenge":admission.challenge,"report":report,"signed":compact}),
    )
}

fn accepted(status: u16, body: &[u8]) -> Result<(), String> {
    if status != 200 {
        return Err(format!(
            "conformance was not accepted: HTTP status {status}"
        ));
    }
    let result = parse(body)?;
    if result["result"] == "refused" {
        fields(&result, &["result", "check", "message"]).map_err(|e| e.to_string())?;
        let check = text(&result, "check")?;
        let message = text(&result, "message")?;
        if !CHECKS.contains(&check) || message.trim().is_empty() {
            return Err("conformance refusal fields are invalid".into());
        }
        return Err(format!("conformance check {check} refused: {message}"));
    }
    fields(&result, &["result"]).map_err(|e| e.to_string())?;
    if result["result"] != "accepted" {
        return Err("conformance result is invalid".into());
    }
    Ok(())
}

fn parse(bytes: &[u8]) -> Result<Value, String> {
    let text = std::str::from_utf8(bytes).map_err(|_| "conformance JSON is not UTF-8")?;
    tsx_contracts::parse_json(text, crate::site_client::RESPONSE_BYTES)
        .map_err(|_| "conformance JSON is invalid".into())
}

fn challenge(bytes: &[u8]) -> Result<Value, String> {
    let value = parse(bytes)?;
    fields(&value, &["contractHash", "contract"]).map_err(|e| e.to_string())?;
    let raw: RawChallenge<'_> =
        serde_json::from_slice(bytes).map_err(|_| "invalid raw conformance challenge")?;
    if value["contractHash"] != tsx_contracts::sha256_bytes(raw.contract.get().as_bytes()) {
        return Err("conformance contract hash differs from its exact bytes".into());
    }
    Ok(value)
}

fn admit(
    bytes: &[u8],
    config: &SiteConfig,
    artifact: &str,
    job: &str,
    now: i64,
) -> Result<Admission, String> {
    config
        .qualification_at(now, artifact)
        .map_err(|e| e.to_string())?;
    let challenge = challenge(bytes)?;
    let c = &challenge["contract"];
    fields(
        c,
        &[
            "schema",
            "job",
            "planVersion",
            "site",
            "node",
            "catalog",
            "specHash",
            "commandHash",
            "generation",
            "issued",
            "expires",
            "artifact",
            "inputs",
            "inputContracts",
            "expectedAssignments",
            "scopes",
            "outputs",
        ],
    )
    .map_err(|e| e.to_string())?;
    let version = c["planVersion"]
        .as_i64()
        .filter(|v| (1..=i32::MAX as i64).contains(v))
        .ok_or("invalid conformance plan version")?;
    let generation = version.to_string();
    if c["schema"] != "opentsx.site-admission.v1"
        || c["site"] != config.site()
        || c["node"] != config.node()
        || c["job"] != job
        || !config.allowed_job(job)
        || c["generation"].as_str() != Some(generation.as_str())
        || c["catalog"] != "tsx-catalog/1"
        || !contains(&config.policy()["catalogs"], "tsx-catalog/1")
    {
        return Err("conformance identity or catalog violates local site policy".into());
    }
    fields(&c["artifact"], &["version", "digest", "stateAbi"]).map_err(|e| e.to_string())?;
    if c["artifact"]["version"] != env!("CARGO_PKG_VERSION")
        || c["artifact"]["digest"] != artifact
        || c["artifact"]["stateAbi"] != STATE_ABI
        || !hex(text(c, "specHash")?, 64)
        || !hex(text(c, "commandHash")?, 64)
    {
        return Err("conformance runtime pin differs from this executable".into());
    }
    let issued = instant(c, "issued").map_err(|e| e.to_string())?;
    let expires = instant(c, "expires").map_err(|e| e.to_string())?;
    if issued > now || expires <= now || expires <= issued {
        return Err("conformance admission is expired or not yet active".into());
    }
    let inputs = array(c, "inputs")?;
    let outputs = array(c, "outputs")?;
    let contracts = array(c, "inputContracts")?;
    if inputs.len() != 1 || outputs.len() != 1 || contracts.len() != 1 {
        return Err("conformance requires one whole input and one statistics output".into());
    }
    let input = &inputs[0];
    let output = &outputs[0];
    let contract = &contracts[0];
    fields(input, &["binding", "topic", "partitionCount", "positions"])
        .map_err(|e| e.to_string())?;
    fields(contract, &["binding", "topic", "fingerprints"]).map_err(|e| e.to_string())?;
    fields(
        output,
        &["sink", "cluster", "topic", "partitionCount", "fingerprints"],
    )
    .map_err(|e| e.to_string())?;
    let binding = text(input, "binding")?.to_string();
    let input_topic = text(input, "topic")?;
    let output_topic = text(output, "topic")?.to_string();
    let source = config.binding(&binding).map_err(|e| e.to_string())?;
    let site = config.binding("site").map_err(|e| e.to_string())?;
    let signal = config.binding("signal").map_err(|e| e.to_string())?;
    let domain = input_topic
        .split('.')
        .nth(1)
        .ok_or("invalid input topic domain")?;
    if !tsx_contracts::valid_topic(input_topic)
        || !tsx_contracts::valid_topic(&output_topic)
        || !contains(&config.policy()["domains"], domain)
        || source.bootstrap != site.bootstrap
        || !source.inputs.iter().any(|v| v == input_topic)
        || !contains(&config.policy()["inputs"][&binding], input_topic)
        || output["cluster"] != "site"
        || output["sink"] != output_topic
        || output_topic != format!("tsx.{domain}.aggregates.v2")
        || !site.outputs.contains(&output_topic)
        || !signal.outputs.contains(&output_topic)
        || !site
            .outputs
            .contains(&format!("tsx.{domain}.dag-status.v1"))
        || !contains(&config.policy()["outputs"]["site"], "aggregates")
        || !contains(&config.policy()["outputs"]["site"], "dag-status")
        || !contains(&config.policy()["outputs"]["signal"], "aggregates")
    {
        return Err("conformance topics violate local site policy".into());
    }
    if contract["binding"] != input["binding"]
        || contract["topic"] != input["topic"]
        || contract["fingerprints"] != json!([crate::wire::FINGERPRINT])
        || output["fingerprints"] != json!([crate::stats::STATS_FINGERPRINT])
    {
        return Err("conformance schema fingerprints differ from the local codecs".into());
    }
    let count = partitions(input)?;
    let output_partitions = partitions(output)?;
    let positions = array(input, "positions")?;
    if positions.len() != count {
        return Err("conformance input must contain every partition".into());
    }
    let mut cut = Cut {
        topic: input_topic.into(),
        positions: Vec::with_capacity(count),
    };
    for (index, p) in positions.iter().enumerate() {
        fields(p, &["partition", "from", "to"]).map_err(|e| e.to_string())?;
        let partition = decimal(p, "partition")?;
        let from = decimal(p, "from")?;
        let to = decimal(p, "to")?;
        if partition != index as i64 || from < 0 || to < from {
            return Err("conformance input cut is not a canonical whole topic".into());
        }
        cut.positions.push(Position {
            partition: index as i32,
            from,
            to,
        });
    }
    let expected = array(c, "expectedAssignments")?;
    let mut members = BTreeSet::new();
    let mut local = 0;
    for member in expected {
        fields(
            member,
            &[
                "assignment",
                "site",
                "node",
                "generation",
                "fence",
                "fragmentHash",
            ],
        )
        .map_err(|e| e.to_string())?;
        if !members.insert(text(member, "assignment")?)
            || text(member, "fence")?
                .parse::<u64>()
                .ok()
                .filter(|v| *v > 0)
                .is_none()
            || member["generation"] != c["generation"]
            || !hex(text(member, "fragmentHash")?, 64)
        {
            return Err("conformance expected membership is invalid".into());
        }
        if member["site"] == config.site() && member["node"] == config.node() {
            local += 1;
        }
    }
    if local != 1 || expected.is_empty() || array(c, "scopes")?.is_empty() {
        return Err("conformance membership or scopes are absent".into());
    }
    for scope in array(c, "scopes")? {
        fields(
            scope,
            &["groupId", "windowType", "startMicros", "endMicros"],
        )
        .map_err(|e| e.to_string())?;
        if !hex(text(scope, "groupId")?, 16)
            || text(scope, "windowType")?.is_empty()
            || decimal_signed(scope, "endMicros")? <= decimal_signed(scope, "startMicros")?
        {
            return Err("conformance statistic scope is invalid".into());
        }
    }
    Ok(Admission {
        challenge,
        binding,
        cut,
        output_topic,
        output_partitions,
    })
}

fn report(
    admission: &Admission,
    config: &SiteConfig,
    artifact: &str,
    now: i64,
    io: &mut impl FederationIo,
) -> Result<Value, String> {
    config
        .qualification_at(now, artifact)
        .map_err(|e| e.to_string())?;
    let source = config
        .binding(&admission.binding)
        .map_err(|e| e.to_string())?;
    let site = config.binding("site").map_err(|e| e.to_string())?;
    validate_watermarks(io, &source.bootstrap, &admission.cut)?;
    let output = io.watermarks(
        &site.bootstrap,
        &admission.output_topic,
        admission.output_partitions,
    )?;
    validate_inventory(&output, admission.output_partitions)?;
    let (series, records) = scan(io, &source.bootstrap, &admission.cut, config.limits())?;
    validate_watermarks(io, &source.bootstrap, &admission.cut)?;
    let c = &admission.challenge["contract"];
    let issued = instant(c, "issued")
        .map_err(|e| e.to_string())?
        .max(instant(config.qualification(), "issued").map_err(|e| e.to_string())?);
    let expires = instant(c, "expires")
        .map_err(|e| e.to_string())?
        .min(instant(config.qualification(), "expires").map_err(|e| e.to_string())?);
    let details=[
        "Previously executed qualified shared wire vectors; exact executable and vector hashes pinned locally".to_string(),
        "Previously executed qualified shared SeriesKey and xxHash64 vectors; exact executable and vector hashes pinned locally".into(),
        "Previously executed qualified shared catalog suites; zero failed, ignored or filtered tests".into(),
        "Previously executed qualified broker protocol report: 10 passing cases in 7 required families on this site endpoint".into(),
        "All challenge topics checked against immutable local input, output, domain and catalog policy".into(),
        "Live input and output partition inventories and input retained cuts checked".into(),
        format!("{records} admitted input records decoded; first and last records included; local statistics encoder fingerprint checked"),
        "Previously executed qualified Java and Rust producer placement: 10000 keys over 12 partitions matched".into(),
        format!("Complete admitted cut scanned; {} sorted unique series with one partition owner each",series.len()),
        "Executable digest, version, state ABI, job, site, node, generation and local qualification validity checked".into(),
    ];
    let checks = CHECKS
        .into_iter()
        .zip(details)
        .map(|(id, detail)| json!({"id":id,"result":"pass","detail":detail}))
        .collect::<Vec<_>>();
    Ok(
        json!({"schema":"opentsx.site-conformance.v1","site":c["site"],"node":c["node"],"job":c["job"],"planVersion":c["planVersion"],"generation":c["generation"],
        "admissionHash":admission.challenge["contractHash"],"at":format_time(now)?,"issued":format_time(issued)?,"expires":format_time(expires)?,"track":"B","artifact":c["artifact"],"catalog":c["catalog"],"checks":checks,
        "topics":[{"role":"input","binding":admission.binding,"topic":admission.cut.topic,"partitions":admission.cut.positions.len(),"fingerprints":[crate::wire::FINGERPRINT]},
            {"role":"output","binding":"site","topic":admission.output_topic,"partitions":admission.output_partitions,"fingerprints":[crate::stats::STATS_FINGERPRINT]}],"series":{"count":series.len(),"ids":series}}),
    )
}

fn scan(
    io: &mut impl FederationIo,
    bootstrap: &str,
    cut: &Cut,
    limits: &Limits,
) -> Result<(BTreeSet<String>, usize), String> {
    let mut remaining = cut.clone();
    let mut owners = BTreeMap::new();
    let mut count = 0usize;
    while remaining.positions.iter().any(|p| p.from < p.to) {
        let records = io.read(bootstrap, &remaining, limits)?;
        if records.is_empty() || records.len() > limits.records {
            return Err("conformance input prefix is empty or exceeds its record limit".into());
        }
        let mut bytes = 0usize;
        let mut samples = 0usize;
        for record in records {
            let p = remaining
                .positions
                .iter_mut()
                .find(|p| p.partition == record.partition)
                .ok_or("conformance record has an unassigned partition")?;
            if record.offset != p.from || record.offset >= p.to {
                return Err(
                    "conformance input offsets are missing, duplicated or outside the cut".into(),
                );
            }
            bytes = bytes
                .checked_add(record.record.payload.len())
                .ok_or("conformance input byte count overflow")?;
            if bytes > limits.input_bytes {
                return Err("conformance input prefix exceeds its byte limit".into());
            }
            let episode =
                crate::wire::decode(&record.record.payload, limits.payload_bytes, limits.samples)?;
            samples = samples
                .checked_add(episode.values.len())
                .ok_or("conformance sample count overflow")?;
            if samples > limits.samples {
                return Err("conformance input prefix exceeds its sample limit".into());
            }
            let id = crate::model::series_id(&episode.series.metric, &episode.series.tags)?;
            if id != episode.series.series_id || record.record.key != id {
                return Err("conformance SeriesKey or record key differs".into());
            }
            if let Some(previous) = owners.insert(id, record.partition) {
                if previous != record.partition {
                    return Err("conformance series appears in multiple partitions".into());
                }
            }
            if owners.len() > limits.series {
                return Err("conformance series inventory exceeds its local limit".into());
            }
            p.from = p.from.checked_add(1).ok_or("conformance offset overflow")?;
            count = count
                .checked_add(1)
                .ok_or("conformance input count overflow")?;
        }
    }
    Ok((owners.into_keys().collect(), count))
}

fn validate_watermarks(
    io: &mut impl FederationIo,
    bootstrap: &str,
    cut: &Cut,
) -> Result<(), String> {
    let marks = io.watermarks(bootstrap, &cut.topic, cut.positions.len())?;
    validate_inventory(&marks, cut.positions.len())?;
    for (p, m) in cut.positions.iter().zip(marks) {
        if p.partition != m.partition || p.from < m.low || p.to > m.high {
            return Err("conformance input cut is no longer retained".into());
        }
    }
    Ok(())
}
fn validate_inventory(
    marks: &[crate::run_loop::Watermark],
    partitions: usize,
) -> Result<(), String> {
    if marks.len() != partitions
        || marks
            .iter()
            .enumerate()
            .any(|(index, m)| m.partition != index as i32 || m.low < 0 || m.high < m.low)
    {
        return Err("conformance live partition inventory differs".into());
    }
    Ok(())
}
fn contains(value: &Value, needle: &str) -> bool {
    value
        .as_array()
        .is_some_and(|a| a.iter().any(|v| v.as_str() == Some(needle)))
}
fn text<'a>(value: &'a Value, name: &str) -> Result<&'a str, String> {
    text_field(value, name).map_err(|e| e.to_string())
}
fn array<'a>(value: &'a Value, name: &str) -> Result<&'a Vec<Value>, String> {
    value[name]
        .as_array()
        .ok_or_else(|| format!("invalid conformance {name} inventory"))
}
fn decimal(value: &Value, name: &str) -> Result<i64, String> {
    let n = decimal_signed(value, name)?;
    if n < 0 {
        return Err(format!("conformance {name} must be nonnegative"));
    }
    Ok(n)
}
fn decimal_signed(value: &Value, name: &str) -> Result<i64, String> {
    let s = text(value, name)?;
    let n = s
        .parse::<i64>()
        .map_err(|_| format!("invalid conformance {name}"))?;
    if n.to_string() != s {
        return Err(format!("noncanonical conformance {name}"));
    }
    Ok(n)
}
fn partitions(value: &Value) -> Result<usize, String> {
    let n = decimal(value, "partitionCount")?;
    if !(1..=128).contains(&n) {
        return Err("invalid conformance partition count".into());
    }
    Ok(n as usize)
}
fn hex(value: &str, length: usize) -> bool {
    value.len() == length
        && value
            .bytes()
            .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
}
fn now() -> Result<i64, String> {
    i64::try_from(OffsetDateTime::now_utc().unix_timestamp_nanos() / 1000)
        .map_err(|_| "system clock exceeds supported microseconds".into())
}
fn format_time(micros: i64) -> Result<String, String> {
    OffsetDateTime::from_unix_timestamp_nanos(i128::from(micros) * 1000)
        .map_err(|_| "conformance timestamp exceeds supported range")?
        .format(&Rfc3339)
        .map_err(|_| "cannot format conformance timestamp".into())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::broker::{FederationInputRecord, FederationRecord};
    use crate::run_loop::Watermark;

    fn limits() -> Limits {
        Limits {
            records: 1,
            payload_bytes: 1048576,
            input_bytes: 16777216,
            samples: 1000000,
            output_bytes: 16777216,
            series: 1024,
            state_bytes: 1048576,
        }
    }

    fn episode(sensor: &str) -> crate::model::Episode {
        let tags = BTreeMap::from([("sensor".into(), sensor.into())]);
        let values = vec![1.0, 2.0];
        crate::model::Episode {
            episode_id: format!("fixture-{sensor}"),
            series: crate::model::SeriesKey {
                metric: "fixture.value".into(),
                series_id: crate::model::series_id("fixture.value", &tags).unwrap(),
                tags,
                unit: None,
            },
            t_start: 0,
            t_end: 2_000_000,
            count: 2,
            time_encoding: crate::model::TimeEncoding::Regular,
            interval_micros: Some(1_000_000),
            time_deltas: None,
            summary: crate::model::summary(&values),
            values,
            quality: None,
            segmentation: None,
            labels: BTreeMap::new(),
            bucket_id: None,
            revision: 0,
            provenance: crate::model::Provenance {
                source: "fixture".into(),
                producer: "fixture".into(),
                created_at: 0,
                pipeline: None,
                parent_episode_id: None,
            },
        }
    }

    fn record(sensor: &str, partition: i32, offset: i64) -> FederationInputRecord {
        let e = episode(sensor);
        FederationInputRecord {
            partition,
            offset,
            record: FederationRecord {
                key: e.series.series_id.clone(),
                timestamp: 0,
                payload: crate::wire::encode(&e).unwrap(),
                headers: vec![],
            },
        }
    }

    #[derive(Default)]
    struct Memory {
        input: Vec<FederationInputRecord>,
        reads: usize,
        marks: Vec<Watermark>,
        broken: bool,
    }
    impl FederationIo for Memory {
        fn read(
            &mut self,
            bootstrap: &str,
            cut: &Cut,
            limits: &Limits,
        ) -> Result<Vec<FederationInputRecord>, String> {
            assert!(["local-site", "127.0.0.1:39092"].contains(&bootstrap));
            self.reads += 1;
            if self.broken {
                return Ok(self.input.clone());
            }
            Ok(self
                .input
                .iter()
                .filter(|r| {
                    cut.positions.iter().any(|p| {
                        r.partition == p.partition && p.from <= r.offset && r.offset < p.to
                    })
                })
                .take(limits.records)
                .cloned()
                .collect())
        }
        fn watermarks(
            &mut self,
            bootstrap: &str,
            _: &str,
            _: usize,
        ) -> Result<Vec<Watermark>, String> {
            assert!(["local-site", "127.0.0.1:39092"].contains(&bootstrap));
            Ok(self.marks.clone())
        }
        fn publish(
            &mut self,
            _: &str,
            _: &str,
            _: usize,
            _: &[FederationRecord],
        ) -> Result<(), String> {
            panic!("conformance must never publish broker records")
        }
    }

    fn cut() -> Cut {
        Cut {
            topic: "tsx.fixture.episodes.v2".into(),
            positions: vec![Position {
                partition: 0,
                from: 0,
                to: 3,
            }],
        }
    }

    #[test]
    fn hashes_exact_raw_contract_bytes_and_rejects_ambiguous_json() {
        let raw = r#"{ "z":1,"a":2 }"#;
        let bytes = format!(
            r#"{{"contract":{raw},"contractHash":"{}"}}"#,
            tsx_contracts::sha256(raw)
        );
        let value = challenge(bytes.as_bytes()).unwrap();
        assert_eq!(value["contract"]["a"], 2);
        assert_ne!(
            tsx_contracts::sha256(raw),
            tsx_contracts::sha256(&value["contract"].to_string())
        );
        assert!(challenge(bytes.replace("\"a\":2", "\"a\":3").as_bytes())
            .err()
            .unwrap()
            .contains("exact bytes"));
        assert!(challenge(
            format!(
                r#"{{"contract":{raw},"contractHash":"{}","contractHash":"{}"}}"#,
                tsx_contracts::sha256(raw),
                tsx_contracts::sha256(raw)
            )
            .as_bytes()
        )
        .is_err());
        assert!(challenge(br#"{"contract":{"a":1,"a":1},"contractHash":"invalid"}"#).is_err());
        assert!(challenge(br#"{"contract":{},"contractHash":"invalid","extra":1}"#).is_err());
    }

    #[test]
    fn preserves_the_driver_refusal_check_and_message() {
        assert_eq!(accepted(200,br#"{"result":"refused","check":"series.disjoint","message":"series inventory overlaps"}"#).unwrap_err(),
            "conformance check series.disjoint refused: series inventory overlaps");
        accepted(200, br#"{"result":"accepted"}"#).unwrap();
        assert!(accepted(
            200,
            br#"{"result":"refused","check":"unknown","message":"refused"}"#
        )
        .is_err());
        assert!(accepted(
            200,
            br#"{"result":"refused","check":"series.disjoint","message":" "}"#
        )
        .is_err());
        assert!(accepted(200, br#"{"result":"accepted","extra":true}"#).is_err());
        assert_eq!(
            accepted(401, b"").unwrap_err(),
            "conformance was not accepted: HTTP status 401"
        );
    }

    #[test]
    fn scans_every_bounded_prefix_and_returns_complete_sorted_series() {
        let mut io = Memory {
            input: vec![
                record("one", 0, 0),
                record("one", 0, 1),
                record("two", 0, 2),
            ],
            ..Memory::default()
        };
        let (series, count) = scan(&mut io, "local-site", &cut(), &limits()).unwrap();
        assert_eq!(count, 3);
        assert_eq!(io.reads, 3);
        assert_eq!(
            series,
            BTreeSet::from([
                episode("one").series.series_id,
                episode("two").series.series_id
            ])
        );
        io.input[2].record.payload[2] ^= 1;
        assert!(scan(&mut io, "local-site", &cut(), &limits())
            .unwrap_err()
            .contains("fingerprint"));
    }

    #[test]
    fn rejects_missing_offsets_wrong_keys_cross_partition_series_and_limits() {
        for change in [
            "empty",
            "offset",
            "key",
            "bytes",
            "samples",
            "series",
            "records",
            "partition",
        ] {
            let mut io = Memory {
                input: vec![
                    record("one", 0, 0),
                    record("two", 0, 1),
                    record("three", 0, 2),
                ],
                ..Memory::default()
            };
            let mut limits = limits();
            let mut cut = cut();
            match change {
                "empty" => io.input.clear(),
                "offset" => {
                    io.input[0].offset = 1;
                    io.broken = true;
                    limits.records = 3;
                }
                "key" => io.input[1].record.key = "wrong".into(),
                "bytes" => limits.input_bytes = 1,
                "samples" => limits.samples = 1,
                "series" => limits.series = 1,
                "records" => io.broken = true,
                "partition" => {
                    io.input = vec![record("one", 0, 0), record("one", 1, 0)];
                    cut.positions = vec![
                        Position {
                            partition: 0,
                            from: 0,
                            to: 1,
                        },
                        Position {
                            partition: 1,
                            from: 0,
                            to: 1,
                        },
                    ];
                }
                _ => unreachable!(),
            }
            assert!(
                scan(&mut io, "local-site", &cut, &limits).is_err(),
                "{change}"
            );
        }
    }

    #[test]
    fn requires_complete_live_metadata_and_retained_input_cuts() {
        let mut io = Memory {
            marks: vec![Watermark {
                partition: 0,
                low: 0,
                high: 3,
            }],
            ..Memory::default()
        };
        validate_watermarks(&mut io, "local-site", &cut()).unwrap();
        io.marks[0].low = 1;
        assert!(validate_watermarks(&mut io, "local-site", &cut()).is_err());
        io.marks[0].low = 0;
        io.marks[0].high = 2;
        assert!(validate_watermarks(&mut io, "local-site", &cut()).is_err());
        io.marks[0].partition = 1;
        assert!(validate_watermarks(&mut io, "local-site", &cut()).is_err());
        io.marks.clear();
        assert!(validate_watermarks(&mut io, "local-site", &cut()).is_err());
    }

    struct Directory(std::path::PathBuf);
    impl Drop for Directory {
        fn drop(&mut self) {
            std::fs::remove_dir_all(&self.0).unwrap();
        }
    }
    struct Fixture {
        _directory: Directory,
        config: SiteConfig,
        contract: Value,
    }
    const ARTIFACT: &str =
        "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    const NOW: i64 = 1_790_812_800_000_000;

    impl Fixture {
        fn new() -> Self {
            use ed25519_dalek::{
                pkcs8::{EncodePrivateKey, EncodePublicKey},
                SigningKey,
            };
            use std::{
                fs,
                io::Read,
                os::unix::fs::PermissionsExt,
                sync::atomic::{AtomicU64, Ordering},
            };
            static NEXT: AtomicU64 = AtomicU64::new(0);
            let directory = Directory(std::env::temp_dir().join(format!(
                "tsx-b3-conform-{}-{}",
                std::process::id(),
                NEXT.fetch_add(1, Ordering::SeqCst)
            )));
            fs::create_dir(&directory.0).unwrap();
            fs::set_permissions(&directory.0, fs::Permissions::from_mode(0o700)).unwrap();
            let mut seed = [0u8; 32];
            let mut random = fs::File::open("/dev/urandom").unwrap();
            random.read_exact(&mut seed).unwrap();
            let driver = SigningKey::from_bytes(&seed);
            random.read_exact(&mut seed).unwrap();
            let key = SigningKey::from_bytes(&seed);
            seed.fill(0);
            for (name, bytes) in [
                (
                    "driver.pem",
                    driver
                        .verifying_key()
                        .to_public_key_pem(Default::default())
                        .unwrap()
                        .into_bytes(),
                ),
                (
                    "site.pem",
                    key.to_pkcs8_pem(Default::default())
                        .unwrap()
                        .as_bytes()
                        .to_vec(),
                ),
                ("ca.crt", b"local parser fixture CA bytes".to_vec()),
                (
                    "client.crt",
                    b"local parser fixture certificate bytes".to_vec(),
                ),
                ("client.key", b"local parser fixture key bytes".to_vec()),
            ] {
                let path = directory.0.join(name);
                fs::write(&path, bytes).unwrap();
                fs::set_permissions(path, fs::Permissions::from_mode(0o600)).unwrap();
            }
            let cases=[("topics.admin","admin"),("topics.batch","batch"),("records.order","order"),("records.codec","codec0"),("records.codec","codec1"),("records.codec","codec2"),("records.codec","codec3"),("records.large","large"),("offsets.committed","committed"),("offsets.explicit","explicit")].into_iter().map(|(family,name)|json!({"family":family,"case":name,"status":"pass","detail":"synthetic parser fixture"})).collect::<Vec<_>>();
            let qualification = json!({"schema":"opentsx.rust-b3-qualification.v1","artifact":{"version":env!("CARGO_PKG_VERSION"),"digest":ARTIFACT,"stateAbi":STATE_ABI,"catalog":"tsx-catalog/1"},
                "endpoint":"127.0.0.1:39092","issued":"2026-01-01T00:00:00Z","expires":"2027-01-01T00:00:00Z","vectors":crate::site_qualification::vector_hashes(),
                "tests":{"passed":259,"failed":0,"ignored":0,"measured":0,"filtered":0,"sharedVectorTests":["synthetic shared vector check"],"acceptanceExampleTests":["synthetic acceptance example check"],"vectorSuites":crate::site_qualification::SUITES},
                "protocol":{"schema":"opentsx.rust-b1-conformance.v1","runnerVersion":env!("CARGO_PKG_VERSION"),"endpoint":"127.0.0.1:39092","requiredCases":10,"executedCases":10,"passedCases":10,"failedCases":0,"requiredFamilies":7,"executedFamilies":7,"cases":cases},
                "placement":{"java":{"schema":"opentsx.rust-b1-placement-result.v1","keys":10000,"partitions":12,"matched":10000},"rust":{"schema":"opentsx.rust-b1-placement-result.v1","keys":10000,"partitions":12,"matched":10000,"fixtureSha256":"2".repeat(64)}},
                "logs":["0".repeat(64),"1".repeat(64)]});
            fs::write(
                directory.0.join("qualification.json"),
                qualification.to_string(),
            )
            .unwrap();
            let doc = json!({"schema":"opentsx.rust-site-config.v1","site":"s1","node":"node-1","driver":{"url":"https://localhost:18443","ca":"ca.crt","certificate":"client.crt","privateKey":"client.key"},
                "driverPublicKey":"driver.pem","sitePrivateKey":"site.pem","qualification":"qualification.json","jobs":["bounded-test"],
                "bindings":{"site":{"bootstrap":"127.0.0.1:39092","inputs":["tsx.fixture.episodes.v2","tsx.fixture.denied.v2"],"outputs":["tsx.fixture.aggregates.v2","tsx.fixture.dag-status.v1"]},"signal":{"bootstrap":"127.0.0.1:39093","inputs":[],"outputs":["tsx.fixture.aggregates.v2"]}},
                "policy":{"domains":["fixture"],"inputs":{"site":["tsx.fixture.episodes.v2"]},"outputs":{"site":["aggregates","dag-status"],"signal":["aggregates"]},"catalogs":["tsx-catalog/1"]},
                "state":"state","limits":{"records":1,"payloadBytes":1048576,"inputBytes":16777216,"samples":1000000,"outputBytes":16777216,"series":1024,"stateBytes":1048576}});
            let path = directory.0.join("config.json");
            fs::write(&path, doc.to_string()).unwrap();
            fs::set_permissions(&path, fs::Permissions::from_mode(0o600)).unwrap();
            let config = SiteConfig::load(&path).unwrap();
            let contract = json!({"schema":"opentsx.site-admission.v1","job":"bounded-test","planVersion":1,"site":"s1","node":"node-1","catalog":"tsx-catalog/1","specHash":"a".repeat(64),"commandHash":"b".repeat(64),"generation":"1","issued":"2026-01-01T00:00:00Z","expires":"2027-01-01T00:00:00Z",
                "artifact":{"version":env!("CARGO_PKG_VERSION"),"digest":ARTIFACT,"stateAbi":STATE_ABI},
                "inputs":[{"binding":"site","topic":"tsx.fixture.episodes.v2","partitionCount":"1","positions":[{"partition":"0","from":"0","to":"3"}]}],"inputContracts":[{"binding":"site","topic":"tsx.fixture.episodes.v2","fingerprints":[crate::wire::FINGERPRINT]}],
                "expectedAssignments":[{"assignment":"a1","site":"s1","node":"node-1","generation":"1","fence":"1","fragmentHash":"a".repeat(64)}],
                "scopes":[{"groupId":"1234567890abcdef","windowType":"PT10M","startMicros":"0","endMicros":"600000000"}],
                "outputs":[{"sink":"tsx.fixture.aggregates.v2","cluster":"site","topic":"tsx.fixture.aggregates.v2","partitionCount":"1","fingerprints":[crate::stats::STATS_FINGERPRINT]}]});
            Self {
                _directory: directory,
                config,
                contract,
            }
        }
        fn bytes(contract: &Value) -> Vec<u8> {
            let raw = contract.to_string();
            format!(
                r#"{{"contractHash":"{}","contract":{raw}}}"#,
                tsx_contracts::sha256(&raw)
            )
            .into_bytes()
        }
        fn admit(&self, contract: &Value, now: i64) -> Result<Admission, String> {
            admit(
                &Self::bytes(contract),
                &self.config,
                ARTIFACT,
                "bounded-test",
                now,
            )
        }
    }

    #[test]
    fn pinned_qualification_and_local_policy_precede_all_broker_access() {
        let fixture = Fixture::new();
        fixture.admit(&fixture.contract, NOW).unwrap();
        for name in [
            "input-policy",
            "output-cluster",
            "artifact",
            "state-abi",
            "version",
            "site",
            "node",
            "catalog",
            "generation",
            "expired",
            "fingerprint",
            "partition",
            "membership",
            "scope",
        ] {
            let mut c = fixture.contract.clone();
            match name {
                "input-policy" => {
                    c["inputs"][0]["topic"] = json!("tsx.fixture.denied.v2");
                    c["inputContracts"][0]["topic"] = json!("tsx.fixture.denied.v2");
                }
                "output-cluster" => c["outputs"][0]["cluster"] = json!("signal"),
                "artifact" => c["artifact"]["digest"] = json!(format!("sha256:{}", "c".repeat(64))),
                "state-abi" => c["artifact"]["stateAbi"] = json!("wrong"),
                "version" => c["artifact"]["version"] = json!("0.0.0"),
                "site" => c["site"] = json!("s2"),
                "node" => c["node"] = json!("node-2"),
                "catalog" => c["catalog"] = json!("tsx-catalog/2"),
                "generation" => c["generation"] = json!("2"),
                "expired" => c["expires"] = json!("2025-01-01T00:00:00Z"),
                "fingerprint" => {
                    c["inputContracts"][0]["fingerprints"] = json!(["0000000000000000"])
                }
                "partition" => c["inputs"][0]["positions"][0]["partition"] = json!("00"),
                "membership" => c["expectedAssignments"][0]["node"] = json!("node-2"),
                "scope" => c["scopes"][0]["endMicros"] = json!("0"),
                _ => unreachable!(),
            }
            assert!(fixture.admit(&c, NOW).is_err(), "{name}");
        }
        assert!(fixture
            .admit(
                &fixture.contract,
                instant(&json!({"at":"2027-01-01T00:00:00Z"}), "at").unwrap()
            )
            .err()
            .unwrap()
            .contains("qualification"));
        assert!(admit(
            &Fixture::bytes(&fixture.contract),
            &fixture.config,
            "sha256:wrong",
            "bounded-test",
            NOW
        )
        .is_err());
    }

    #[test]
    fn report_distinguishes_prior_qualification_from_fresh_read_only_checks() {
        let fixture = Fixture::new();
        let admission = fixture.admit(&fixture.contract, NOW).unwrap();
        let mut io = Memory {
            input: vec![
                record("one", 0, 0),
                record("one", 0, 1),
                record("two", 0, 2),
            ],
            marks: vec![Watermark {
                partition: 0,
                low: 0,
                high: 3,
            }],
            ..Memory::default()
        };
        let result = report(&admission, &fixture.config, ARTIFACT, NOW, &mut io).unwrap();
        assert_eq!(result["track"], "B");
        assert_eq!(result["checks"].as_array().unwrap().len(), 10);
        assert_eq!(result["series"]["count"], 2);
        assert_eq!(io.reads, 3);
        for name in [
            "vectors.wire",
            "vectors.series-id",
            "vectors.operators",
            "kafscale.required",
            "partitioner.murmur2",
        ] {
            let row = result["checks"]
                .as_array()
                .unwrap()
                .iter()
                .find(|r| r["id"] == name)
                .unwrap();
            assert!(row["detail"]
                .as_str()
                .unwrap()
                .starts_with("Previously executed qualified"));
        }
        let compact = fixture.config.sign_report(&result).unwrap();
        assert_eq!(compact.split('.').count(), 3);
    }

    #[test]
    fn refuses_status_publication_without_local_policy_permission() {
        let fixture = Fixture::new();
        let path = fixture._directory.0.join("config.json");
        let mut document: Value = serde_json::from_slice(&std::fs::read(&path).unwrap()).unwrap();
        document["policy"]["outputs"]["site"] = json!(["aggregates"]);
        std::fs::write(&path, document.to_string()).unwrap();
        let config = SiteConfig::load(&path).unwrap();
        assert!(admit(
            &Fixture::bytes(&fixture.contract),
            &config,
            ARTIFACT,
            "bounded-test",
            NOW
        )
        .is_err());
    }
}
