use crate::operators::{Limits as OperatorLimits, Pipeline};
use serde::Deserialize;
use serde_json::Value;
use std::collections::BTreeMap;

pub const REQUEST_BYTES: usize = 65_536;
pub const MAX_PARTITIONS: usize = 128;
pub const MAX_EPISODE_SAMPLES: usize = 25_000;

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
    let value = tsx_contracts::parse_json(text, REQUEST_BYTES).map_err(str::to_string)?;
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
    validate_limits(&request.limits)?;
    if !tsx_contracts::valid_topic(&request.sink.topic) {
        return Err("invalid output topic name".into());
    }
    tsx_contracts::validate(&request.assignment_text).map_err(str::to_string)?;
    let assignment = tsx_contracts::parse_json(&request.assignment_text, REQUEST_BYTES)
        .map_err(str::to_string)?;
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
    let fragment = tsx_contracts::parse_json(fragment_text, 32_768).map_err(str::to_string)?;
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

pub(crate) fn increment_context(text: &str) -> Result<String, String> {
    let mut value = tsx_contracts::parse_json(text, REQUEST_BYTES).map_err(str::to_string)?;
    let mut assignment = tsx_contracts::parse_json(
        value["assignmentText"]
            .as_str()
            .ok_or("assignmentText missing")?,
        REQUEST_BYTES,
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

fn validate_limits(limits: &Limits) -> Result<(), String> {
    for (name, value, ceiling) in [
        ("records", limits.records, 10_000),
        ("payloadBytes", limits.payload_bytes, 1_048_576),
        ("inputBytes", limits.input_bytes, 16_777_216),
        ("samples", limits.samples, 100_000),
        ("outputBytes", limits.output_bytes, 16_777_216),
        ("series", limits.series, 64),
        ("stateBytes", limits.state_bytes, 1_048_576),
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
