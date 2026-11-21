use crate::catalog::{Context, Pipeline};
use crate::operators::Limits as OperatorLimits;
use serde::Deserialize;
use serde_json::Value;
use std::collections::BTreeMap;

pub const REQUEST_BYTES: usize = 65_536;
pub const MAX_PARTITIONS: usize = 128;
pub const MAX_EPISODE_SAMPLES: usize = 25_000;

/// Admission ceilings of one worker. A request can only lower them through its `limits`, never raise them.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Profile {
    pub name: &'static str,
    pub request_bytes: usize,
    pub assignment_bytes: usize,
    pub fragment_bytes: usize,
    pub records: usize,
    pub payload_bytes: usize,
    pub input_bytes: usize,
    pub output_bytes: usize,
    pub state_bytes: usize,
    /// Series and samples without and with a catalog `context`.
    pub series: usize,
    pub samples: usize,
    pub context_series: usize,
    pub context_samples: usize,
    /// Largest prepared journal and increment checkpoint the worker reads back.
    pub journal_bytes: usize,
}

impl Profile {
    /// The default local profile.
    pub const STANDARD: Profile = Profile {
        name: "standard",
        request_bytes: REQUEST_BYTES,
        assignment_bytes: REQUEST_BYTES,
        fragment_bytes: 32_768,
        records: 10_000,
        payload_bytes: 1_048_576,
        input_bytes: 16_777_216,
        output_bytes: 16_777_216,
        state_bytes: 1_048_576,
        series: 64,
        samples: 100_000,
        context_series: 1_024,
        context_samples: 1_000_000,
        journal_bytes: 85_000_000,
    };

    /// Opt-in profile for the B4.2 board measurement: one source inventory of up to 16,384 series.
    /// The operator selects it with `serve --profile measurement`; a request cannot select it.
    pub const MEASUREMENT: Profile = Profile {
        name: "measurement",
        request_bytes: 2 * tsx_contracts::MAX_ASSIGNMENT_BYTES + 65_536,
        assignment_bytes: tsx_contracts::MAX_ASSIGNMENT_BYTES,
        fragment_bytes: tsx_contracts::MAX_DAG_BYTES,
        records: 100_000,
        payload_bytes: 1_048_576,
        input_bytes: 67_108_864,
        output_bytes: 67_108_864,
        state_bytes: 268_435_456,
        series: 16_384,
        samples: 4_000_000,
        context_series: 16_384,
        context_samples: 4_000_000,
        // a prepared journal stores output payloads as JSON byte arrays, up to five bytes per payload byte
        journal_bytes: 5 * 67_108_864 + 2 * (2 * tsx_contracts::MAX_ASSIGNMENT_BYTES + 65_536),
    };

    pub fn named(name: &str) -> Result<Profile, String> {
        match name {
            "standard" => Ok(Profile::STANDARD),
            "measurement" => Ok(Profile::MEASUREMENT),
            _ => Err(format!("unknown worker profile: {name}")),
        }
    }

    /// Largest increment checkpoint: one journal, one state snapshot and one request.
    pub fn increment_bytes(&self) -> usize {
        self.journal_bytes + self.state_bytes + self.request_bytes
    }
}

#[derive(Clone, Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Limits {
    pub records: usize,
    pub payload_bytes: usize,
    pub input_bytes: usize,
    pub samples: usize,
    pub output_bytes: usize,
    pub series: usize,
    pub state_bytes: usize,
}

#[derive(Clone, Debug, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Binding {
    pub bootstrap: String,
    pub inputs: Vec<String>,
    pub outputs: Vec<String>,
}

#[derive(Clone, Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Sink {
    pub name: String,
    pub binding: String,
    pub topic: String,
    pub partition_count: String,
}

#[derive(Clone, Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct LocalRequest {
    pub schema: String,
    pub assignment_text: String,
    pub bindings: BTreeMap<String, Binding>,
    pub sink: Sink,
    pub created_at_micros: String,
    pub limits: Limits,
    pub sequence: Option<String>,
    pub previous_request_hash: Option<String>,
    pub context: Option<Context>,
}

#[derive(Clone, Debug)]
pub struct Position {
    pub partition: i32,
    pub from: i64,
    pub to: i64,
}

#[derive(Clone, Debug)]
pub struct Cut {
    pub topic: String,
    pub positions: Vec<Position>,
}

pub struct Admitted {
    pub request_text: String,
    pub request: LocalRequest,
    pub assignment: Value,
    pub request_hash: String,
    pub pipeline: Pipeline,
    pub cut: Cut,
    pub bootstrap: String,
    pub output_partitions: i32,
    pub increment: Option<Increment>,
}

#[derive(Clone, Debug)]
pub struct Increment {
    pub sequence: i64,
    pub previous_request_hash: Option<String>,
}

pub fn admit(text: &str, artifact_digest: &str) -> Result<Admitted, String> {
    admit_profiled(text, artifact_digest, &Profile::STANDARD)
}

pub fn admit_profiled(
    text: &str,
    artifact_digest: &str,
    profile: &Profile,
) -> Result<Admitted, String> {
    admit_with_caps(text, artifact_digest, profile, profile.request_bytes)
}

pub(crate) fn admit_run(text: &str, artifact_digest: &str) -> Result<Admitted, String> {
    let profile = Profile {
        series: 1_024,
        samples: 1_000_000,
        ..Profile::STANDARD
    };
    admit_with_caps(text, artifact_digest, &profile, 262_144)
}

fn admit_with_caps(
    text: &str,
    artifact_digest: &str,
    profile: &Profile,
    request_bytes: usize,
) -> Result<Admitted, String> {
    let value = tsx_contracts::parse_json(text, request_bytes).map_err(str::to_string)?;
    let increment = match value["schema"].as_str() {
        Some("opentsx.local-bounded-run.v1") => {
            if value.get("sequence").is_some() || value.get("previousRequestHash").is_some() {
                return Err("bounded requests do not carry continuation fields".into());
            }
            None
        }
        Some("opentsx.local-increment.v1") => {
            let sequence = canonical_i64(
                value["sequence"]
                    .as_str()
                    .ok_or("sequence must be a decimal string")?,
                "sequence",
            )?;
            if sequence <= 0 {
                return Err("sequence must be positive".into());
            }
            let previous = value
                .get("previousRequestHash")
                .ok_or("previousRequestHash is required")?;
            let previous_request_hash = if previous.is_null() {
                None
            } else {
                let hash = previous
                    .as_str()
                    .ok_or("previousRequestHash must be null or a digest")?;
                if hash.len() != 64
                    || !hash
                        .bytes()
                        .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
                {
                    return Err("previousRequestHash must be a lower-case SHA-256 digest".into());
                }
                Some(hash.to_string())
            };
            if (sequence == 1) != previous_request_hash.is_none() {
                return Err("only the first increment has a null predecessor".into());
            }
            Some(Increment {
                sequence,
                previous_request_hash,
            })
        }
        _ => return Err("unsupported local request schema".into()),
    };
    let request: LocalRequest =
        serde_json::from_value(value).map_err(|e| format!("local request fields: {e}"))?;
    let (max_series, max_samples) = if request.context.is_some() {
        (profile.context_series, profile.context_samples)
    } else {
        (profile.series, profile.samples)
    };
    validate_limits(&request.limits, profile, max_series, max_samples)?;
    if !tsx_contracts::valid_topic(&request.sink.topic) {
        return Err("invalid output topic name".into());
    }
    tsx_contracts::validate(&request.assignment_text).map_err(str::to_string)?;
    let assignment = tsx_contracts::parse_json(&request.assignment_text, profile.assignment_bytes)
        .map_err(str::to_string)?;
    if assignment.get("outputs").is_some() {
        return Err("assignment outputs are not executed by this runtime yet (D-T21)".into());
    }
    if assignment["runtime"] != "rust" || assignment["mode"] != "bounded" {
        return Err("local replay requires a bounded Rust assignment".into());
    }
    if assignment["artifactDigest"].as_str() != Some(artifact_digest) {
        return Err("assignment artifactDigest does not identify this executable".into());
    }
    let inputs = assignment["inputs"]
        .as_array()
        .ok_or("assignment inputs missing")?;
    if inputs.len() != 1 || request.bindings.len() != 1 {
        return Err("local replay requires one whole input topic and one broker binding".into());
    }
    let input = &inputs[0];
    let binding_name = input["binding"].as_str().ok_or("input binding missing")?;
    let binding = request
        .bindings
        .get(binding_name)
        .ok_or("input binding is not configured")?;
    let topic = input["topic"].as_str().ok_or("input topic missing")?;
    if request.sink.binding != binding_name
        || binding.inputs != [topic]
        || binding.outputs != [request.sink.topic.as_str()]
        || topic == request.sink.topic
    {
        return Err(
            "local input/output bindings do not match the permitted distinct topics".into(),
        );
    }
    validate_bootstrap(&binding.bootstrap)?;
    let bootstrap = binding.bootstrap.clone();
    let created_at = canonical_i64(&request.created_at_micros, "createdAtMicros")?;
    let output_partitions = canonical_i64(&request.sink.partition_count, "sink.partitionCount")?;
    if !(1..=MAX_PARTITIONS as i64).contains(&output_partitions) {
        return Err("output partition count exceeds the local profile".into());
    }
    let fragment_text = assignment["fragmentText"]
        .as_str()
        .ok_or("fragment text missing")?;
    let fragment =
        tsx_contracts::parse_json(fragment_text, profile.fragment_bytes).map_err(str::to_string)?;
    let pipeline = Pipeline::compile(
        &fragment,
        assignment["specHash"].as_str().ok_or("specHash missing")?,
        created_at,
        OperatorLimits {
            max_series: request.limits.series,
            max_samples: request.limits.samples,
            max_output_samples: request.limits.samples,
            max_state_bytes: request.limits.state_bytes,
        },
        request.context.as_ref(),
        assignment["site"]
            .as_str()
            .ok_or("assignment site missing")?,
    )?;
    if pipeline.source_topic() != topic
        || pipeline.sink_name() != request.sink.name
        || pipeline.sink_topic() != Some(request.sink.topic.as_str())
    {
        return Err("fragment channels do not match the admitted input and output".into());
    }
    let values = input["positions"]
        .as_array()
        .ok_or("input positions missing")?;
    if values.len() > MAX_PARTITIONS {
        return Err("whole topic exceeds the local partition capacity".into());
    }
    let mut positions = Vec::with_capacity(values.len());
    let mut records = 0i64;
    for value in values {
        let partition = canonical_i64(
            value["partition"].as_str().ok_or("partition missing")?,
            "partition",
        )?;
        let from = canonical_i64(value["from"].as_str().ok_or("from missing")?, "from")?;
        let to = canonical_i64(value["to"].as_str().ok_or("to missing")?, "to")?;
        records = records
            .checked_add(to - from)
            .ok_or("input cut size overflow")?;
        positions.push(Position {
            partition: i32::try_from(partition).map_err(|_| "partition overflow")?,
            from,
            to,
        });
    }
    if records > request.limits.records as i64 {
        return Err("whole input cut exceeds the record limit".into());
    }
    Ok(Admitted {
        request_text: text.into(),
        cut: Cut {
            topic: topic.to_string(),
            positions,
        },
        request,
        assignment,
        request_hash: tsx_contracts::sha256(text),
        pipeline,
        bootstrap,
        output_partitions: output_partitions as i32,
        increment,
    })
}

pub(crate) fn increment_context(text: &str, profile: &Profile) -> Result<String, String> {
    let mut value =
        tsx_contracts::parse_json(text, profile.request_bytes).map_err(str::to_string)?;
    let mut assignment = tsx_contracts::parse_json(
        value["assignmentText"]
            .as_str()
            .ok_or("assignmentText missing")?,
        profile.assignment_bytes,
    )
    .map_err(str::to_string)?;
    assignment["inputs"][0]
        .as_object_mut()
        .ok_or("input missing")?
        .remove("positions");
    let fields = value.as_object_mut().ok_or("request object missing")?;
    fields.remove("sequence");
    fields.remove("previousRequestHash");
    fields.insert("assignmentText".into(), assignment);
    let canonical =
        serde_json::to_vec(&value).map_err(|e| format!("cannot encode increment context: {e}"))?;
    Ok(tsx_contracts::sha256_bytes(&canonical))
}

pub fn canonical_i64(text: &str, field: &str) -> Result<i64, String> {
    let value = text
        .parse::<i64>()
        .map_err(|_| format!("invalid decimal {field}"))?;
    if value.to_string() != text {
        return Err(format!("noncanonical decimal {field}"));
    }
    Ok(value)
}

pub(crate) fn validate_run_limits(limits: &Limits) -> Result<(), String> {
    validate_limits(limits, &Profile::STANDARD, 1_024, 1_000_000)
}

fn validate_limits(
    limits: &Limits,
    profile: &Profile,
    max_series: usize,
    max_samples: usize,
) -> Result<(), String> {
    for (name, value, ceiling) in [
        ("records", limits.records, profile.records),
        ("payloadBytes", limits.payload_bytes, profile.payload_bytes),
        ("inputBytes", limits.input_bytes, profile.input_bytes),
        ("samples", limits.samples, max_samples),
        ("outputBytes", limits.output_bytes, profile.output_bytes),
        ("series", limits.series, max_series),
        ("stateBytes", limits.state_bytes, profile.state_bytes),
    ] {
        if value == 0 || value > ceiling {
            return Err(format!("{name} must be within 1..={ceiling}"));
        }
    }
    if limits.payload_bytes > limits.input_bytes {
        return Err("payloadBytes exceeds inputBytes".into());
    }
    Ok(())
}

pub(crate) fn validate_bootstrap(bootstrap: &str) -> Result<(), String> {
    if bootstrap.len() > 1_024 || bootstrap.is_empty() {
        return Err("invalid broker address list".into());
    }
    for address in bootstrap.split(',') {
        let (host, port) = address
            .rsplit_once(':')
            .ok_or("broker address needs host:port")?;
        let port = port.parse::<u16>().map_err(|_| "invalid broker port")?;
        if port == 0 || host.is_empty() {
            return Err("invalid broker host or port".into());
        }
        if let Some(ip) = host.strip_prefix('[').and_then(|v| v.strip_suffix(']')) {
            ip.parse::<std::net::Ipv6Addr>()
                .map_err(|_| "invalid IPv6 broker address")?;
        } else if !host
            .bytes()
            .all(|c| c.is_ascii_alphanumeric() || c == b'.' || c == b'-')
        {
            return Err(
                "broker addresses must contain host names and ports without credentials".into(),
            );
        }
    }
    Ok(())
}
