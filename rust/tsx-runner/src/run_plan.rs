use crate::admission::{self, Admitted, Binding, Limits, Position};
use crate::operators::{Limits as OperatorLimits, Pipeline};
use crate::wire::FINGERPRINT;
use base64::{engine::general_purpose::URL_SAFE_NO_PAD, Engine};
use ed25519_dalek::{pkcs8::DecodePublicKey, Signature, VerifyingKey};
use serde::Deserialize;
use serde_json::{json, Value};
use std::collections::{BTreeMap, BTreeSet};
use std::fmt;
use std::fs::File;
use std::io::Read;
use std::path::{Path, PathBuf};
use time::{format_description::well_known::Rfc3339, OffsetDateTime};

pub const MAX_COMPACT_BYTES: usize = 176 * 1_024;
pub const MAX_PAYLOAD_BYTES: usize = 128 * 1_024;
const CONFIG_BYTES: usize = 65_536;
const HEADER: &str = "{\"alg\":\"EdDSA\"}";

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Refusal {
    pub code: &'static str,
    pub message: String,
}

impl Refusal {
    pub fn new(code: &'static str, message: impl Into<String>) -> Self {
        Self {
            code,
            message: message.into(),
        }
    }

    fn invalid(message: impl Into<String>) -> Self {
        Self::new("plan.invalid", message)
    }

    fn signature() -> Self {
        Self::new("plan.signature", "plan signature is invalid")
    }

    fn policy() -> Self {
        Self::new(
            "plan.policy",
            "plan output or input violates local site policy",
        )
    }
}

impl fmt::Display for Refusal {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter.write_str(&self.message)
    }
}

impl std::error::Error for Refusal {}

#[derive(Clone, Debug, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Policy {
    pub domains: Vec<String>,
    pub catalogs: Vec<String>,
}

#[derive(Clone, Debug)]
pub struct RunConfig {
    pub site: String,
    pub node: String,
    pub bindings: BTreeMap<String, Binding>,
    pub policy: Policy,
    pub checkpoint: PathBuf,
    pub limits: Limits,
    pub identity_hash: String,
    driver_key: VerifyingKey,
    validated_view: Value,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct ConfigDocument {
    schema: String,
    site: String,
    node: String,
    driver_public_key: PathBuf,
    bindings: BTreeMap<String, Binding>,
    policy: Policy,
    checkpoint: PathBuf,
    limits: Limits,
}

impl RunConfig {
    pub fn load(path: &Path) -> Result<Self, Refusal> {
        let bytes = read_file(path, CONFIG_BYTES, "run configuration")?;
        let text = std::str::from_utf8(&bytes)
            .map_err(|_| Refusal::invalid("run configuration is not UTF-8"))?;
        let value = tsx_contracts::parse_json(text, CONFIG_BYTES)
            .map_err(|_| Refusal::invalid("run configuration JSON is invalid"))?;
        let document: ConfigDocument = serde_json::from_value(value)
            .map_err(|_| Refusal::invalid("run configuration fields are invalid"))?;
        if document.schema != "opentsx.rust-run-config.v1"
            || !identifier(&document.site)
            || !identifier(&document.node)
            || document.driver_public_key.as_os_str().is_empty()
            || document.checkpoint.as_os_str().is_empty()
        {
            return Err(Refusal::invalid("run configuration identity is invalid"));
        }
        admission::validate_run_limits(&document.limits).map_err(Refusal::invalid)?;
        if document.bindings.len() != 1 {
            return Err(Refusal::invalid(
                "run configuration needs exactly one binding",
            ));
        }
        for (name, binding) in &document.bindings {
            if !identifier(name)
                || binding.inputs.len() != 1
                || binding.outputs.len() != 2
                || !unique(&binding.outputs)
                || !binding
                    .inputs
                    .iter()
                    .chain(&binding.outputs)
                    .all(|topic| tsx_contracts::valid_topic(topic))
            {
                return Err(Refusal::invalid(
                    "run configuration topic binding is invalid",
                ));
            }
            let status = status_topic(&binding.inputs[0])?;
            if !binding.inputs[0].ends_with(".v2")
                || !binding.outputs.contains(&status)
                || binding.outputs.iter().any(|topic| {
                    topic == &binding.inputs[0] || topic != &status && !topic.ends_with(".v2")
                })
            {
                return Err(Refusal::invalid(
                    "run binding must allow the Episode and derived status topics",
                ));
            }
            admission::validate_bootstrap(&binding.bootstrap).map_err(Refusal::invalid)?;
        }
        if !unique(&document.policy.domains)
            || !document.policy.domains.iter().all(|domain| {
                !domain.contains('.')
                    && tsx_contracts::valid_topic(&format!("tsx.{domain}.dag-status.v1"))
            })
            || !unique(&document.policy.catalogs)
            || !document.policy.catalogs.iter().all(|catalog| {
                catalog
                    .strip_prefix("tsx-catalog/")
                    .and_then(|version| admission::canonical_i64(version, "catalog").ok())
                    .is_some_and(|version| version > 0)
            })
        {
            return Err(Refusal::invalid("run configuration policy is invalid"));
        }
        let absolute = std::fs::canonicalize(path).map_err(|error| {
            Refusal::invalid(format!("cannot locate run configuration: {error}"))
        })?;
        let directory = absolute
            .parent()
            .ok_or_else(|| Refusal::invalid("run configuration has no parent directory"))?;
        let key_path = directory.join(&document.driver_public_key);
        let key_bytes = read_file(&key_path, 16_384, "driver public key")?;
        let key_text = std::str::from_utf8(&key_bytes)
            .map_err(|_| Refusal::invalid("driver public key is not UTF-8 PEM"))?;
        let driver_key = VerifyingKey::from_public_key_pem(key_text)
            .map_err(|_| Refusal::invalid("driver public key must be Ed25519 SPKI PEM"))?;
        if driver_key.is_weak() {
            return Err(Refusal::invalid("driver public key is weak"));
        }
        let checkpoint = directory.join(document.checkpoint);
        let checkpoint_bytes = checkpoint.as_os_str().as_encoded_bytes();
        let mut identity = b"opentsx.rust-run-config.v1\0".to_vec();
        identity.extend_from_slice(&(bytes.len() as u64).to_be_bytes());
        identity.extend_from_slice(&bytes);
        identity.extend_from_slice(driver_key.as_bytes());
        identity.extend_from_slice(&(checkpoint_bytes.len() as u64).to_be_bytes());
        identity.extend_from_slice(checkpoint_bytes);
        let config = Self {
            site: document.site,
            node: document.node,
            bindings: document.bindings,
            policy: document.policy,
            checkpoint,
            limits: document.limits,
            identity_hash: tsx_contracts::sha256_bytes(&identity),
            driver_key,
            validated_view: Value::Null,
        };
        let validated_view = config.view()?;
        Ok(Self {
            validated_view,
            ..config
        })
    }

    pub fn require_validated(&self) -> Result<(), Refusal> {
        if self.view()? != self.validated_view {
            return Err(Refusal::invalid(
                "run configuration changed after validation",
            ));
        }
        Ok(())
    }

    fn view(&self) -> Result<Value, Refusal> {
        let checkpoint = self
            .checkpoint
            .to_str()
            .ok_or_else(|| Refusal::invalid("checkpoint path is not UTF-8"))?;
        let bindings: serde_json::Map<_, _> = self.bindings.iter().map(|(name, binding)| {
            (name.clone(), json!({"bootstrap":binding.bootstrap,"inputs":binding.inputs,"outputs":binding.outputs}))
        }).collect();
        let limits = &self.limits;
        Ok(
            json!({"site":self.site,"node":self.node,"bindings":bindings,"checkpoint":checkpoint,
            "policy":{"domains":self.policy.domains,"catalogs":self.policy.catalogs},
            "identityHash":self.identity_hash,
            "limits":{"records":limits.records,"payloadBytes":limits.payload_bytes,"inputBytes":limits.input_bytes,
                "samples":limits.samples,"outputBytes":limits.output_bytes,"series":limits.series,"stateBytes":limits.state_bytes}}),
        )
    }
}

pub fn configuration_schema(path: &Path) -> Result<Option<String>, Refusal> {
    let bytes = read_file(path, CONFIG_BYTES, "run configuration")?;
    let text = std::str::from_utf8(&bytes)
        .map_err(|_| Refusal::invalid("run configuration is not UTF-8"))?;
    let value = tsx_contracts::parse_json(text, CONFIG_BYTES)
        .map_err(|_| Refusal::invalid("run configuration JSON is invalid"))?;
    Ok(value
        .get("schema")
        .and_then(Value::as_str)
        .map(str::to_string))
}

pub struct SignedPlan {
    pub document: Value,
    pub assignment: Value,
    pub job: String,
    pub plan_version: i32,
    pub command_hash: String,
    pub issued_micros: i64,
    pub expires_micros: i64,
    pub binding: String,
    pub input_topic: String,
    pub output_topic: String,
    pub status_topic: String,
    pub output_partitions: i32,
    pub positions: Vec<Position>,
    artifact_digest: String,
    sink: String,
    config_identity: String,
    verified_assignment: Value,
    signed_positions: Vec<Position>,
    signed_issued_micros: i64,
    signed_expires_micros: i64,
    signed_output_partitions: i32,
    validated_view: Value,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct PlanDocument {
    schema: String,
    job: String,
    plan_version: i32,
    issued: String,
    expires: String,
    site: String,
    catalog: String,
    spec_hash: String,
    assignment_text: String,
    input_contracts: Vec<InputContract>,
    outputs: Vec<Output>,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct InputContract {
    binding: String,
    topic: String,
    fingerprints: Vec<String>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Output {
    sink: String,
    cluster: String,
    topic: String,
    partition_count: String,
    fingerprints: Vec<String>,
}

impl SignedPlan {
    pub fn verify(
        compact: &str,
        config: &RunConfig,
        artifact_digest: &str,
    ) -> Result<Self, Refusal> {
        config.require_validated()?;
        let payload = verify_jws(compact, &config.driver_key)?;
        let text = std::str::from_utf8(&payload)
            .map_err(|_| Refusal::invalid("runner plan is not UTF-8"))?;
        let value = tsx_contracts::parse_json(text, MAX_PAYLOAD_BYTES)
            .map_err(|_| Refusal::invalid("runner plan JSON is invalid"))?;
        let document: PlanDocument = serde_json::from_value(value.clone())
            .map_err(|_| Refusal::invalid("runner plan fields are invalid"))?;
        if document.schema != "opentsx.runner-plan.v1"
            || !identifier(&document.job)
            || !identifier(&document.site)
            || document.plan_version < 1
        {
            return Err(Refusal::invalid("runner plan identity is invalid"));
        }
        if document.site != config.site {
            return Err(Refusal::new("plan.site", "plan site is not this site"));
        }
        if document.catalog != "tsx-catalog/1"
            || !config.policy.catalogs.contains(&document.catalog)
        {
            return Err(Refusal::new("plan.catalog", "plan catalog is unavailable"));
        }
        let issued = instant(&document.issued)?;
        let expires = instant(&document.expires)?;
        if expires <= issued {
            return Err(Refusal::invalid("expires must follow issued"));
        }
        let issued_micros = i64::try_from((issued + 999).div_euclid(1_000))
            .map_err(|_| Refusal::invalid("issued time is outside the supported range"))?;
        let expires_micros = i64::try_from(expires.div_euclid(1_000))
            .map_err(|_| Refusal::invalid("expires time is outside the supported range"))?;
        tsx_contracts::validate(&document.assignment_text)
            .map_err(|code| Refusal::invalid(format!("invalid execution assignment: {code}")))?;
        let assignment = tsx_contracts::parse_json(&document.assignment_text, 65_536)
            .map_err(|_| Refusal::invalid("execution assignment JSON is invalid"))?;
        if assignment.get("outputs").is_some() {
            return Err(Refusal::invalid(
                "assignment outputs are not executed by this runtime yet (D-T21)",
            ));
        }
        if assignment["runtime"] != "rust" || assignment["mode"] != "continuous" {
            return Err(Refusal::invalid(
                "signed run requires a continuous Rust assignment",
            ));
        }
        if assignment["node"] != config.node || assignment["site"] != config.site {
            return Err(Refusal::new(
                "plan.site",
                "plan site or node is not this runtime",
            ));
        }
        let generation = document.plan_version.to_string();
        if assignment["deployment"] != document.job
            || assignment["generation"].as_str() != Some(generation.as_str())
            || assignment["catalog"] != document.catalog
            || assignment["specHash"] != document.spec_hash
        {
            return Err(Refusal::invalid("runner and assignment identities differ"));
        }
        if assignment["artifactDigest"].as_str() != Some(artifact_digest) {
            return Err(Refusal::invalid(
                "assignment artifactDigest does not identify this executable",
            ));
        }
        let inputs = assignment["inputs"]
            .as_array()
            .ok_or_else(|| Refusal::invalid("assignment inputs are missing"))?;
        if inputs.len() != 1 || document.input_contracts.len() != 1 || document.outputs.len() != 1 {
            return Err(Refusal::invalid(
                "signed run requires one whole input topic and one Episode output",
            ));
        }
        let input = &inputs[0];
        let binding_name = text_field(input, "binding")?;
        let topic = text_field(input, "topic")?;
        let binding = config
            .bindings
            .get(binding_name)
            .ok_or_else(Refusal::policy)?;
        let contract = &document.input_contracts[0];
        if contract.binding != binding_name
            || contract.topic != topic
            || contract.fingerprints != [FINGERPRINT]
        {
            return Err(Refusal::invalid(
                "input contract differs from the Episode assignment",
            ));
        }
        let output = &document.outputs[0];
        if output.cluster != "site" {
            return Err(Refusal::policy());
        }
        let status_topic = status_topic(topic)?;
        let output_inventory = BTreeSet::from([output.topic.as_str(), status_topic.as_str()]);
        if binding.inputs != [topic]
            || binding
                .outputs
                .iter()
                .map(String::as_str)
                .collect::<BTreeSet<_>>()
                != output_inventory
            || topic == output.topic
            || !allowed_topic(topic, config)
            || !allowed_topic(&output.topic, config)
        {
            return Err(Refusal::policy());
        }
        let output_partitions =
            admission::canonical_i64(&output.partition_count, "output partitionCount")
                .map_err(Refusal::invalid)?;
        if !(1..=admission::MAX_PARTITIONS as i64).contains(&output_partitions)
            || output.fingerprints != [FINGERPRINT]
            || output.sink.is_empty()
            || output.sink.chars().count() > 249
        {
            return Err(Refusal::invalid("Episode output contract is invalid"));
        }
        for field in ["sourceDagText", "fragmentText"] {
            let dag = tsx_contracts::parse_json(text_field(&assignment, field)?, 32_768)
                .map_err(|_| Refusal::invalid("DAG JSON is invalid"))?;
            validate_dag_fields(&dag)?;
            if dag["nodes"]
                .as_array()
                .is_some_and(|nodes| nodes.iter().any(|node| node["op"] != "decode.avro-soe"))
            {
                return Err(Refusal::new(
                    "plan.catalog",
                    "signed run supports pass-through only",
                ));
            }
            let pipeline = Pipeline::compile(
                &dag,
                &document.spec_hash,
                issued_micros,
                OperatorLimits {
                    max_series: config.limits.series,
                    max_samples: config.limits.samples,
                    max_output_samples: config.limits.samples,
                    max_state_bytes: config.limits.state_bytes,
                },
            )
            .map_err(|message| {
                if message.starts_with("unsupported operator") {
                    Refusal::new("plan.catalog", message)
                } else {
                    Refusal::invalid(message)
                }
            })?;
            if !pipeline.is_passthrough() {
                return Err(Refusal::new(
                    "plan.catalog",
                    "signed run supports pass-through only",
                ));
            }
            if pipeline.source_topic() != topic
                || pipeline.sink_topic() != Some(output.topic.as_str())
                || pipeline.sink_name() != output.sink
            {
                return Err(Refusal::invalid(
                    "DAG channels differ from the signed contracts",
                ));
            }
        }
        let positions = input["positions"]
            .as_array()
            .ok_or_else(|| Refusal::invalid("input positions are missing"))?;
        if positions.len() > admission::MAX_PARTITIONS {
            return Err(Refusal::invalid(
                "whole topic exceeds the run partition capacity",
            ));
        }
        let positions = positions
            .iter()
            .map(|position| {
                Ok(Position {
                    partition: i32::try_from(decimal(position, "partition")?)
                        .map_err(|_| Refusal::invalid("partition overflow"))?,
                    from: decimal(position, "from")?,
                    to: decimal(position, "from")?,
                })
            })
            .collect::<Result<Vec<_>, Refusal>>()?;
        let plan = Self {
            document: value,
            assignment: assignment.clone(),
            job: document.job,
            plan_version: document.plan_version,
            command_hash: tsx_contracts::sha256(compact),
            issued_micros,
            expires_micros,
            binding: binding_name.to_owned(),
            input_topic: topic.to_owned(),
            output_topic: output.topic.clone(),
            status_topic,
            output_partitions: output_partitions as i32,
            positions: positions.clone(),
            artifact_digest: artifact_digest.to_owned(),
            sink: output.sink.clone(),
            config_identity: config.identity_hash.clone(),
            verified_assignment: assignment,
            signed_positions: positions,
            signed_issued_micros: issued_micros,
            signed_expires_micros: expires_micros,
            signed_output_partitions: output_partitions as i32,
            validated_view: Value::Null,
        };
        let validated_view = plan.view();
        Ok(Self {
            validated_view,
            ..plan
        })
    }

    pub fn validate_authority(&self) -> Result<(), Refusal> {
        if self.view() != self.validated_view {
            return Err(Refusal::invalid(
                "runner plan changed after signature verification",
            ));
        }
        Ok(())
    }

    fn view(&self) -> Value {
        let positions: Vec<_> = self.positions.iter().map(|position| {
            json!({"partition":position.partition,"from":position.from,"to":position.to})
        }).collect();
        json!({"document":self.document,"assignment":self.assignment,"job":self.job,
            "planVersion":self.plan_version,"commandHash":self.command_hash,"issuedMicros":self.issued_micros,
            "expiresMicros":self.expires_micros,"binding":self.binding,"inputTopic":self.input_topic,
            "outputTopic":self.output_topic,"statusTopic":self.status_topic,"outputPartitions":self.output_partitions,
            "positions":positions})
    }

    pub fn require_fresh(&self, now_micros: i64) -> Result<(), Refusal> {
        self.validate_authority()?;
        if now_micros >= self.signed_expires_micros {
            return Err(Refusal::new("plan.expired", "new activation has expired"));
        }
        if now_micros < self.signed_issued_micros {
            return Err(Refusal::invalid("plan issued time is in the future"));
        }
        Ok(())
    }

    pub fn admit_cut(
        &self,
        config: &RunConfig,
        positions: &[Position],
    ) -> Result<Admitted, Refusal> {
        self.validate_authority()?;
        config.require_validated()?;
        if config.identity_hash != self.config_identity {
            return Err(Refusal::policy());
        }
        if positions.len() != self.signed_positions.len()
            || positions
                .iter()
                .zip(&self.signed_positions)
                .any(|(cut, initial)| {
                    cut.partition != initial.partition
                        || cut.from < initial.from
                        || cut.to < cut.from
                })
        {
            return Err(Refusal::invalid(
                "run cut differs from the signed whole-topic inventory",
            ));
        }
        let mut assignment = self.verified_assignment.clone();
        assignment["mode"] = json!("bounded");
        assignment["inputs"][0]["positions"] = Value::Array(
            positions
                .iter()
                .map(|position| {
                    json!({"partition":position.partition.to_string(),
                    "from":position.from.to_string(),"to":position.to.to_string()})
                })
                .collect(),
        );
        let binding = config
            .bindings
            .get(&self.binding)
            .ok_or_else(Refusal::policy)?;
        let limits = &config.limits;
        let request = json!({
            "schema":"opentsx.local-bounded-run.v1",
            "assignmentText":serde_json::to_string(&assignment).map_err(|_| Refusal::invalid("cannot encode run assignment"))?,
            "bindings":{self.binding.clone():{"bootstrap":binding.bootstrap,"inputs":binding.inputs,"outputs":[self.output_topic]}},
            "sink":{"name":self.sink,"binding":self.binding,"topic":self.output_topic,
                "partitionCount":self.signed_output_partitions.to_string()},
            "createdAtMicros":self.signed_issued_micros.to_string(),
            "limits":{"records":limits.records,"payloadBytes":limits.payload_bytes,"inputBytes":limits.input_bytes,
                "samples":limits.samples,"outputBytes":limits.output_bytes,"series":limits.series,"stateBytes":limits.state_bytes}
        });
        let text = serde_json::to_string(&request)
            .map_err(|_| Refusal::invalid("cannot encode run cut"))?;
        let admitted =
            admission::admit_run(&text, &self.artifact_digest).map_err(Refusal::invalid)?;
        if !admitted.pipeline.is_passthrough() {
            return Err(Refusal::new(
                "plan.catalog",
                "signed run supports pass-through only",
            ));
        }
        Ok(admitted)
    }
}

pub(crate) fn verify_jws(compact: &str, key: &VerifyingKey) -> Result<Vec<u8>, Refusal> {
    if compact.len() > MAX_COMPACT_BYTES {
        return Err(Refusal::signature());
    }
    let parts: Vec<_> = compact.splitn(4, '.').collect();
    if parts.len() != 3 || parts[0] != URL_SAFE_NO_PAD.encode(HEADER) {
        return Err(Refusal::signature());
    }
    let payload = decode_base64(parts[1])?;
    let signature = decode_base64(parts[2])?;
    if payload.len() > MAX_PAYLOAD_BYTES || signature.len() != 64 {
        return Err(Refusal::signature());
    }
    let signature = Signature::from_slice(&signature).map_err(|_| Refusal::signature())?;
    key.verify_strict(format!("{}.{}", parts[0], parts[1]).as_bytes(), &signature)
        .map_err(|_| Refusal::signature())?;
    Ok(payload)
}

fn decode_base64(value: &str) -> Result<Vec<u8>, Refusal> {
    if value.is_empty() {
        return Err(Refusal::signature());
    }
    let bytes = URL_SAFE_NO_PAD
        .decode(value)
        .map_err(|_| Refusal::signature())?;
    if URL_SAFE_NO_PAD.encode(&bytes) != value {
        return Err(Refusal::signature());
    }
    Ok(bytes)
}

fn instant(value: &str) -> Result<i128, Refusal> {
    if !value.ends_with('Z') {
        return Err(Refusal::invalid("plan times must be UTC"));
    }
    OffsetDateTime::parse(value, &Rfc3339)
        .map(|instant| instant.unix_timestamp_nanos())
        .map_err(|_| Refusal::invalid("plan time is invalid"))
}

fn text_field<'a>(value: &'a Value, field: &str) -> Result<&'a str, Refusal> {
    value[field]
        .as_str()
        .ok_or_else(|| Refusal::invalid(format!("missing {field}")))
}

fn decimal(value: &Value, field: &str) -> Result<i64, Refusal> {
    admission::canonical_i64(text_field(value, field)?, field).map_err(Refusal::invalid)
}

fn identifier(value: &str) -> bool {
    !value.is_empty()
        && value.len() <= 63
        && value.as_bytes()[0].is_ascii_alphanumeric()
        && value
            .bytes()
            .all(|byte| byte.is_ascii_lowercase() || byte.is_ascii_digit() || byte == b'-')
}

fn unique(values: &[String]) -> bool {
    !values.is_empty() && values.iter().collect::<BTreeSet<_>>().len() == values.len()
}

fn allowed_topic(topic: &str, config: &RunConfig) -> bool {
    tsx_contracts::valid_topic(topic)
        && topic.ends_with(".v2")
        && topic.split('.').nth(1).is_some_and(|domain| {
            config
                .policy
                .domains
                .iter()
                .any(|allowed| allowed == domain)
        })
}

fn status_topic(topic: &str) -> Result<String, Refusal> {
    let domain = topic
        .split('.')
        .nth(1)
        .ok_or_else(|| Refusal::invalid("input domain is missing"))?;
    Ok(format!("tsx.{domain}.dag-status.v1"))
}

fn validate_dag_fields(dag: &Value) -> Result<(), Refusal> {
    allowed_fields(dag, &["schema", "dag", "source", "nodes", "sinks"])?;
    allowed_fields(&dag["source"], &["kind", "topics", "series"])?;
    for node in dag["nodes"]
        .as_array()
        .ok_or_else(|| Refusal::invalid("DAG nodes are missing"))?
    {
        allowed_fields(node, &["id", "op", "in", "params"])?;
    }
    for sink in dag["sinks"]
        .as_array()
        .ok_or_else(|| Refusal::invalid("DAG sinks are missing"))?
    {
        allowed_fields(sink, &["name", "from", "schema", "topic"])?;
    }
    Ok(())
}

fn allowed_fields(value: &Value, fields: &[&str]) -> Result<(), Refusal> {
    if value
        .as_object()
        .is_none_or(|object| object.keys().any(|name| !fields.contains(&name.as_str())))
    {
        return Err(Refusal::invalid("unsupported signed-run DAG fields"));
    }
    Ok(())
}

fn read_file(path: &Path, limit: usize, purpose: &str) -> Result<Vec<u8>, Refusal> {
    let file = File::open(path)
        .map_err(|error| Refusal::invalid(format!("cannot open {purpose}: {error}")))?;
    let mut bytes = Vec::new();
    file.take(limit as u64 + 1)
        .read_to_end(&mut bytes)
        .map_err(|error| Refusal::invalid(format!("cannot read {purpose}: {error}")))?;
    if bytes.len() > limit {
        return Err(Refusal::invalid(format!(
            "{purpose} exceeds its byte limit"
        )));
    }
    Ok(bytes)
}
