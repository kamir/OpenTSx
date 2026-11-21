use crate::{parse_json, sha256, validate_set, MAX_ASSIGNMENT_BYTES};
use serde_json::{json, Map, Number, Value};
use std::collections::{BTreeMap, BTreeSet};
use std::fmt;

pub const MAX_PHYSICAL_BYTES: usize = 4_194_304;
pub const COMPUTATION_SCHEMA: &str = "opentsx.computation.v1";

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Refusal {
    pub code: &'static str,
    pub node: Option<String>,
    pub field: String,
}

impl Refusal {
    pub fn new(code: &'static str, node: Option<&str>, field: &str) -> Self {
        Self {
            code,
            node: node.map(str::to_owned),
            field: field.into(),
        }
    }

    pub fn value(&self) -> Value {
        json!({"schema":"opentsx.plan-refusal.v1","code":self.code,
            "node":self.node,"field":self.field,"message":self.message()})
    }

    pub fn message(&self) -> String {
        format!("Physical deployment refused: {}.", self.code)
    }
}

impl fmt::Display for Refusal {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{}", self.value())
    }
}

impl std::error::Error for Refusal {}

pub fn canonical(value: &Value) -> Result<String, Refusal> {
    let mut result = String::new();
    canonical_into(value, &mut result)?;
    Ok(result)
}

fn canonical_into(value: &Value, output: &mut String) -> Result<(), Refusal> {
    match value {
        Value::Null => output.push_str("null"),
        Value::Bool(value) => output.push_str(if *value { "true" } else { "false" }),
        Value::String(value) => string_into(value, output),
        Value::Array(values) => {
            output.push('[');
            for (index, value) in values.iter().enumerate() {
                if index != 0 {
                    output.push(',');
                }
                canonical_into(value, output)?;
            }
            output.push(']');
        }
        Value::Object(values) => {
            output.push('{');
            let mut keys: Vec<_> = values.keys().collect();
            keys.sort();
            for (index, key) in keys.into_iter().enumerate() {
                if index != 0 {
                    output.push(',');
                }
                string_into(key, output);
                output.push(':');
                canonical_into(&values[key], output)?;
            }
            output.push('}');
        }
        Value::Number(_) => return Err(refusal("deployment.fields", "")),
    }
    Ok(())
}

fn string_into(value: &str, output: &mut String) {
    output.push('"');
    for character in value.chars() {
        match character {
            '"' => output.push_str("\\\""),
            '\\' => output.push_str("\\\\"),
            '\u{0000}'..='\u{001f}' => output.push_str(&format!("\\u{:04x}", character as u32)),
            _ => output.push(character),
        }
    }
    output.push('"');
}

pub fn plan_hash(value: &Value) -> Result<String, Refusal> {
    let mut value = value.clone();
    value
        .as_object_mut()
        .ok_or_else(|| refusal("deployment.fields", ""))?
        .remove("planHash");
    Ok(sha256(&canonical(&value)?))
}

pub fn encode_computation(value: &Value) -> Result<String, Refusal> {
    canonical(&json!([COMPUTATION_SCHEMA, tag(value)?]))
}

fn tag(value: &Value) -> Result<Value, Refusal> {
    Ok(match value {
        Value::Null => json!(["null"]),
        Value::Bool(value) => json!(["boolean", value]),
        Value::String(value) => json!(["string", value]),
        Value::Number(value) => {
            let number = value
                .as_f64()
                .filter(|number| number.is_finite())
                .ok_or_else(|| refusal("deployment.computation", "/computationText"))?;
            if value
                .as_i64()
                .is_some_and(|integer| number as i128 != integer as i128)
                || value
                    .as_u64()
                    .is_some_and(|integer| number as u128 != integer as u128)
            {
                return Err(refusal("deployment.computation", "/computationText"));
            }
            json!(["f64", format!("{:016x}", number.to_bits())])
        }
        Value::Array(values) => json!([
            "array",
            values.iter().map(tag).collect::<Result<Vec<_>, _>>()?
        ]),
        Value::Object(values) => {
            let mut keys: Vec<_> = values.keys().collect();
            keys.sort();
            json!([
                "object",
                keys.into_iter()
                    .map(|key| Ok(json!([key, tag(&values[key])?])))
                    .collect::<Result<Vec<_>, Refusal>>()?
            ])
        }
    })
}

pub fn decode_computation(text: &str) -> Result<Value, Refusal> {
    let value = parse_json(text, MAX_PHYSICAL_BYTES)
        .map_err(|_| refusal("deployment.computation", "/computationText"))?;
    let frame = value
        .as_array()
        .filter(|frame| frame.len() == 2 && frame[0] == COMPUTATION_SCHEMA)
        .ok_or_else(|| refusal("deployment.computation", "/computationText"))?;
    let decoded = untag(&frame[1])?;
    if canonical(&value)? != text {
        return Err(refusal("deployment.computation", "/computationText"));
    }
    Ok(decoded)
}

fn untag(value: &Value) -> Result<Value, Refusal> {
    let invalid = || refusal("deployment.computation", "/computationText");
    let parts = value.as_array().ok_or_else(invalid)?;
    match parts.first().and_then(Value::as_str) {
        Some("null") if parts.len() == 1 => Ok(Value::Null),
        Some("boolean") if parts.len() == 2 && parts[1].is_boolean() => Ok(parts[1].clone()),
        Some("string") if parts.len() == 2 && parts[1].is_string() => Ok(parts[1].clone()),
        Some("f64") if parts.len() == 2 => {
            let bits = parts[1]
                .as_str()
                .filter(|bits| bits.len() == 16 && hex(bits))
                .ok_or_else(invalid)?;
            let number = f64::from_bits(u64::from_str_radix(bits, 16).map_err(|_| invalid())?);
            Ok(Value::Number(Number::from_f64(number).ok_or_else(invalid)?))
        }
        Some("array") if parts.len() == 2 => Ok(Value::Array(
            parts[1]
                .as_array()
                .ok_or_else(invalid)?
                .iter()
                .map(untag)
                .collect::<Result<Vec<_>, _>>()?,
        )),
        Some("object") if parts.len() == 2 => {
            let mut result = Map::new();
            let mut previous: Option<&str> = None;
            for entry in parts[1].as_array().ok_or_else(invalid)? {
                let pair = entry
                    .as_array()
                    .filter(|pair| pair.len() == 2)
                    .ok_or_else(invalid)?;
                let key = pair[0].as_str().ok_or_else(invalid)?;
                if previous.is_some_and(|previous| previous >= key) {
                    return Err(invalid());
                }
                result.insert(key.into(), untag(&pair[1])?);
                previous = Some(key);
            }
            Ok(Value::Object(result))
        }
        _ => Err(invalid()),
    }
}

pub fn validate(text: &str) -> Result<Value, Refusal> {
    let plan = parse_json(text, MAX_PHYSICAL_BYTES).map_err(|code| {
        refusal(
            if code == "json.limit" {
                "deployment.limit"
            } else {
                "deployment.json"
            },
            "/",
        )
    })?;
    fields(
        &plan,
        &[
            "schema",
            "deployment",
            "generation",
            "sourceDagText",
            "specHash",
            "computationText",
            "computationHash",
            "createdAtMicros",
            "expiresAtMicros",
            "assignmentTexts",
            "placements",
            "ownership",
            "channels",
            "capabilities",
            "planHash",
        ],
        "/",
    )?;
    if plan["schema"] != "opentsx.physical-deployment.v1" {
        return Err(refusal("deployment.version", "/schema"));
    }
    no_numbers(&plan, "")?;
    if !crate::identifier(string(&plan, "deployment"))
        || decimal(&plan["generation"]).is_none_or(|value| value == 0)
    {
        return Err(refusal("deployment.identity", "/deployment"));
    }
    let created = number(&plan, "createdAtMicros", "deployment.freshness", "")?;
    let expires = number(&plan, "expiresAtMicros", "deployment.freshness", "")?;
    if expires < created {
        return Err(refusal("deployment.freshness", "/expiresAtMicros"));
    }
    for (field, content) in [
        ("specHash", "sourceDagText"),
        ("computationHash", "computationText"),
    ] {
        if !digest(string(&plan, field)) || sha256(string(&plan, content)) != plan[field] {
            return Err(refusal("deployment.hash", &format!("/{field}")));
        }
    }
    if !digest(string(&plan, "planHash")) || plan_hash(&plan)? != plan["planHash"] {
        return Err(refusal("deployment.hash", "/planHash"));
    }
    let computation = decode_computation(string(&plan, "computationText"))?;
    let original = nodes(&computation, "/computationText")?;
    let logical_topics = computation["source"]["topics"]
        .as_array()
        .filter(|topics| !topics.is_empty())
        .ok_or_else(|| refusal("deployment.computation", "/computationText"))?;
    let logical_sinks = computation["sinks"]
        .as_array()
        .filter(|sinks| !sinks.is_empty())
        .ok_or_else(|| refusal("deployment.computation", "/computationText"))?;
    let texts = array(&plan, "assignmentTexts", "deployment.assignment")?;
    if texts.is_empty() || texts.len() > 128 {
        return Err(refusal("deployment.assignment", "/assignmentTexts"));
    }
    let raw: Vec<_> = texts
        .iter()
        .map(|value| {
            value
                .as_str()
                .ok_or_else(|| refusal("deployment.assignment", "/assignmentTexts"))
        })
        .collect::<Result<_, _>>()?;
    validate_set(&raw).map_err(|_| refusal("deployment.assignment", "/assignmentTexts"))?;
    let assignments = raw
        .iter()
        .map(|text| {
            parse_json(text, MAX_ASSIGNMENT_BYTES)
                .map_err(|_| refusal("deployment.assignment", "/assignmentTexts"))
        })
        .collect::<Result<Vec<_>, _>>()?;
    let mut by_id = BTreeMap::new();
    for assignment in &assignments {
        for key in ["deployment", "generation", "sourceDagText", "specHash"] {
            if assignment[key] != plan[key] {
                return Err(refusal("deployment.assignment", "/assignmentTexts"));
            }
        }
        if assignment["mode"] != "bounded" || assignment["runtime"] != "rust" {
            return Err(refusal("deployment.assignment", "/assignmentTexts"));
        }
        by_id.insert(string(assignment, "assignment"), assignment);
    }
    let placements = array(&plan, "placements", "deployment.coverage")?;
    let mut placed = BTreeSet::new();
    let mut placed_assignments = BTreeSet::new();
    let mut fragments = BTreeMap::new();
    for (index, placement) in placements.iter().enumerate() {
        let path = format!("/placements/{index}");
        fields(placement, &["assignment", "nodes"], &path)?;
        let id = string(placement, "assignment");
        let assignment = by_id
            .get(id)
            .ok_or_else(|| refusal("deployment.coverage", &path))?;
        if !placed_assignments.insert(id) {
            return Err(refusal("deployment.coverage", "/placements/nodes"));
        }
        let fragment = parse_json(string(assignment, "fragmentText"), crate::MAX_DAG_BYTES)
            .map_err(|_| refusal("deployment.assignment", "/assignmentTexts"))?;
        let fragment_nodes = nodes(&fragment, &path)?;
        let ids: Vec<_> = fragment_nodes
            .iter()
            .map(|node| node["id"].clone())
            .collect();
        if placement["nodes"] != json!(ids) {
            return Err(refusal("deployment.coverage", "/placements/nodes"));
        }
        for node in fragment_nodes {
            let id = string(node, "id");
            let source = original
                .iter()
                .find(|node| string(node, "id") == id)
                .ok_or_else(|| Refusal::new("deployment.coverage", Some(id), &path))?;
            if encode_computation(node)? != encode_computation(source)? {
                return Err(Refusal::new(
                    "deployment.coverage",
                    Some(id),
                    "/assignmentTexts/fragmentText/nodes",
                ));
            }
            placed.insert(id.to_owned());
        }
        exact_integers(string(assignment, "fragmentText"))?;
        let mut source = fragment["source"].clone();
        let mut logical_source = computation["source"].clone();
        source
            .as_object_mut()
            .ok_or_else(|| {
                refusal(
                    "deployment.coverage",
                    "/assignmentTexts/fragmentText/source",
                )
            })?
            .remove("topics");
        logical_source
            .as_object_mut()
            .ok_or_else(|| {
                refusal(
                    "deployment.coverage",
                    "/assignmentTexts/fragmentText/source",
                )
            })?
            .remove("topics");
        let input_topics: Vec<_> = assignment["inputs"]
            .as_array()
            .ok_or_else(|| refusal("deployment.assignment", "/assignmentTexts"))?
            .iter()
            .map(|input| input["topic"].clone())
            .collect();
        if encode_computation(&source)? != encode_computation(&logical_source)?
            || fragment["source"]["topics"] != json!(input_topics)
        {
            return Err(refusal(
                "deployment.coverage",
                "/assignmentTexts/fragmentText/source",
            ));
        }
        let expected_sinks: Vec<_> = logical_sinks
            .iter()
            .filter(|sink| ids.iter().any(|id| *id == sink["from"]))
            .cloned()
            .collect();
        if encode_computation(&fragment["sinks"])? != encode_computation(&json!(expected_sinks))? {
            return Err(refusal(
                "deployment.coverage",
                "/assignmentTexts/fragmentText/sinks",
            ));
        }
        fragments.insert(id, fragment);
    }
    if placed_assignments.len() != assignments.len() || placed.len() != original.len() {
        return Err(refusal("deployment.coverage", "/placements/nodes"));
    }
    let mut owners = BTreeSet::new();
    let mut owned_inputs = BTreeSet::new();
    let ownership = array(&plan, "ownership", "deployment.coverage")?;
    for owner in ownership {
        let path = "/ownership";
        fields(
            owner,
            &[
                "assignment",
                "binding",
                "cluster",
                "topic",
                "incarnation",
                "partitionCount",
            ],
            path,
        )?;
        let assignment = by_id
            .get(string(owner, "assignment"))
            .ok_or_else(|| refusal("deployment.coverage", path))?;
        identity(owner, path)?;
        let inputs = assignment["inputs"]
            .as_array()
            .ok_or_else(|| refusal("deployment.assignment", "/assignmentTexts"))?;
        if !inputs.iter().any(|input| {
            input["binding"] == owner["binding"]
                && input["topic"] == owner["topic"]
                && input["partitionCount"] == owner["partitionCount"]
        }) || !logical_topics.contains(&owner["topic"])
            || !owned_inputs.insert((
                string(owner, "assignment"),
                string(owner, "binding"),
                string(owner, "topic"),
            ))
            || !owners.insert((
                string(assignment, "fragment"),
                string(owner, "cluster"),
                string(owner, "incarnation"),
                string(owner, "topic"),
            ))
        {
            return Err(refusal("deployment.coverage", path));
        }
    }
    let expected_inputs: usize = assignments
        .iter()
        .map(|value| value["inputs"].as_array().map_or(0, Vec::len))
        .sum();
    if owned_inputs.len() != expected_inputs
        || logical_topics
            .iter()
            .any(|topic| !ownership.iter().any(|owner| owner["topic"] == *topic))
    {
        return Err(refusal("deployment.coverage", "/ownership"));
    }
    let capabilities = array(&plan, "capabilities", "deployment.capability")?;
    let mut capability_ids = BTreeSet::new();
    let mut record_limits = BTreeMap::new();
    for (index, capability) in capabilities.iter().enumerate() {
        let path = format!("/capabilities/{index}");
        validate_capability(capability, &path)?;
        let id = string(capability, "assignment");
        let assignment = by_id
            .get(id)
            .ok_or_else(|| refusal("deployment.capability", &path))?;
        let report = parse_json(string(capability, "reportText"), MAX_ASSIGNMENT_BYTES)
            .map_err(|_| refusal("deployment.capability", &path))?;
        if !capability_ids.insert(id)
            || report["node"] != assignment["node"]
            || report["site"] != assignment["site"]
            || report["artifactDigest"] != assignment["artifactDigest"]
            || report["catalog"] != assignment["catalog"]
            || string(&report, "stateAbi").is_empty()
            || number(
                capability,
                "observedAtMicros",
                "deployment.freshness",
                &path,
            )? > created
            || number(
                capability,
                "validUntilMicros",
                "deployment.freshness",
                &path,
            )? < expires
        {
            return Err(refusal("deployment.capability", &path));
        }
        record_limits.insert(
            id,
            report["limits"]["records"]
                .as_u64()
                .filter(|number| *number > 0 && *number <= i32::MAX as u64)
                .ok_or_else(|| refusal("deployment.capability", &path))?,
        );
    }
    if capability_ids.len() != assignments.len() {
        return Err(refusal("deployment.capability", "/capabilities"));
    }
    validate_channels(
        &plan,
        &by_id,
        &fragments,
        logical_topics,
        logical_sinks,
        &record_limits,
    )?;
    Ok(plan)
}

pub fn admit(text: &str, facts: &Value, now_micros: &str) -> Result<Value, Refusal> {
    let plan = validate(text)?;
    fields(
        facts,
        &[
            "observedAtMicros",
            "validUntilMicros",
            "topics",
            "capabilities",
            "residency",
        ],
        "/facts",
    )
    .map_err(|_| refusal("deployment.facts", "/facts"))?;
    no_numbers(facts, "/facts")?;
    let now = decimal(&json!(now_micros)).ok_or_else(|| refusal("deployment.freshness", ""))?;
    let observed = number(facts, "observedAtMicros", "deployment.freshness", "")?;
    let valid = number(facts, "validUntilMicros", "deployment.freshness", "")?;
    let created = number(&plan, "createdAtMicros", "deployment.freshness", "")?;
    let expires = number(&plan, "expiresAtMicros", "deployment.freshness", "")?;
    if observed > now
        || valid < now
        || created > now
        || expires < now
        || observed > created
        || valid < expires
    {
        return Err(refusal("deployment.freshness", "/facts/validUntilMicros"));
    }
    let topics = array(facts, "topics", "deployment.facts")?;
    if topics.len() > 256 {
        return Err(refusal("deployment.facts", "/facts/topics"));
    }
    let mut topic_keys = BTreeSet::new();
    for topic in topics {
        fields(
            topic,
            &[
                "binding",
                "cluster",
                "topic",
                "incarnation",
                "partitionCount",
            ],
            "/facts/topics",
        )?;
        identity(topic, "/facts/topics")?;
        if !topic_keys.insert((string(topic, "binding"), string(topic, "topic"))) {
            return Err(refusal("deployment.facts", "/facts/topics"));
        }
    }
    let capabilities = array(facts, "capabilities", "deployment.facts")?;
    if capabilities != array(&plan, "capabilities", "deployment.capability")? {
        return Err(refusal("deployment.capability", "/facts/capabilities"));
    }
    let residency = array(facts, "residency", "deployment.facts")?;
    let mut residency_ids = BTreeSet::new();
    for policy in residency {
        fields(policy, &["assignment", "sites"], "/residency")?;
        sites(&policy["sites"], "/residency")?;
        if !residency_ids.insert(string(policy, "assignment")) {
            return Err(refusal("deployment.facts", "/facts/residency"));
        }
    }
    if residency_ids.len() != array(&plan, "assignmentTexts", "deployment.assignment")?.len() {
        return Err(refusal("deployment.facts", "/facts/residency"));
    }
    let mut used = BTreeSet::new();
    for channel in array(&plan, "channels", "deployment.channel")? {
        let node = located(channel);
        if channel["transport"] == "topic" {
            let fact = topics
                .iter()
                .find(|topic| {
                    topic["binding"] == channel["binding"] && topic["topic"] == channel["topic"]
                })
                .ok_or_else(|| Refusal::new("deployment.incarnation", node, "/channels"))?;
            for field in [
                "binding",
                "cluster",
                "topic",
                "incarnation",
                "partitionCount",
            ] {
                if fact[field] != channel[field] {
                    return Err(Refusal::new(
                        if field == "incarnation" {
                            "deployment.incarnation"
                        } else {
                            "deployment.facts"
                        },
                        node,
                        &format!("/channels/{field}"),
                    ));
                }
            }
            used.insert((string(channel, "binding"), string(channel, "topic")));
        }
        let expected = json!({"assignment":channel["assignment"],"sites":channel["residency"]});
        if !residency.contains(&expected) {
            return Err(Refusal::new(
                "deployment.facts",
                node,
                "/channels/residency",
            ));
        }
    }
    if used.len() != topics.len() {
        return Err(refusal("deployment.facts", "/facts/topics"));
    }
    Ok(plan)
}

fn validate_channels(
    plan: &Value,
    assignments: &BTreeMap<&str, &Value>,
    fragments: &BTreeMap<&str, Value>,
    topics: &[Value],
    sinks: &[Value],
    record_limits: &BTreeMap<&str, u64>,
) -> Result<(), Refusal> {
    let mut expected = BTreeSet::new();
    for (assignment, fragment) in fragments {
        let fragment_nodes = nodes(fragment, "/placements")?;
        let decoder = fragment_nodes
            .iter()
            .find(|node| node["op"] == "decode.avro-soe")
            .ok_or_else(|| refusal("deployment.channel", "/channels"))?;
        for node in fragment_nodes {
            for (port, input) in incoming(node).iter().enumerate() {
                expected.insert((
                    assignment.to_string(),
                    input.as_str().unwrap_or("").to_string(),
                    "0".into(),
                    string(node, "id").to_string(),
                    port.to_string(),
                ));
            }
        }
        for input in assignments[assignment]["inputs"]
            .as_array()
            .ok_or_else(|| refusal("deployment.assignment", "/assignmentTexts"))?
        {
            let port = topics
                .iter()
                .position(|topic| *topic == input["topic"])
                .ok_or_else(|| refusal("deployment.channel", "/channels"))?;
            expected.insert((
                assignment.to_string(),
                "$source".into(),
                port.to_string(),
                string(decoder, "id").to_string(),
                "0".into(),
            ));
        }
        for sink in fragment["sinks"]
            .as_array()
            .ok_or_else(|| refusal("deployment.channel", "/channels"))?
        {
            let port = sinks
                .iter()
                .position(|original| {
                    original["from"] == sink["from"] && original["topic"] == sink["topic"]
                })
                .ok_or_else(|| refusal("deployment.channel", "/channels"))?;
            expected.insert((
                assignment.to_string(),
                string(sink, "from").to_string(),
                "0".into(),
                "$sink".into(),
                port.to_string(),
            ));
        }
    }
    let mut actual = BTreeSet::new();
    for (index, channel) in array(plan, "channels", "deployment.channel")?
        .iter()
        .enumerate()
    {
        let path = format!("/channels/{index}");
        let id = string(channel, "assignment");
        let node = ["to", "from"].iter().find_map(|field| {
            channel[field].as_str().filter(|node| {
                fragments
                    .get(id)
                    .and_then(|fragment| fragment["nodes"].as_array())
                    .is_some_and(|nodes| nodes.iter().any(|candidate| candidate["id"] == *node))
            })
        });
        let invalid = || Refusal::new("deployment.channel", node, &path);
        fields(
            channel,
            &[
                "assignment",
                "from",
                "fromPort",
                "to",
                "toPort",
                "dataType",
                "transport",
                "binding",
                "cluster",
                "topic",
                "incarnation",
                "partitionCount",
                "residency",
                "schema",
                "key",
                "partitioner",
                "bufferRecords",
                "backpressure",
                "completion",
            ],
            &path,
        )?;
        let assignment = assignments.get(id).ok_or_else(invalid)?;
        let key = (
            id.to_owned(),
            string(channel, "from").to_owned(),
            string(channel, "fromPort").to_owned(),
            string(channel, "to").to_owned(),
            string(channel, "toPort").to_owned(),
        );
        if !expected.contains(&key)
            || !actual.insert(key)
            || decimal(&channel["fromPort"]).is_none()
            || decimal(&channel["toPort"]).is_none()
            || channel["key"] != "seriesId"
            || channel["backpressure"] != "block"
            || channel["completion"] != "closed-cut"
        {
            return Err(invalid());
        }
        if decimal(&channel["bufferRecords"])
            .is_none_or(|number| number == 0 || number > record_limits[id])
        {
            return Err(Refusal::new(
                "deployment.channel",
                located(channel),
                &format!("{path}/bufferRecords"),
            ));
        }
        sites(&channel["residency"], &format!("{path}/residency"))?;
        if !channel["residency"]
            .as_array()
            .is_some_and(|sites| sites.contains(&assignment["site"]))
        {
            return Err(invalid());
        }
        let external = channel["from"] == "$source" || channel["to"] == "$sink";
        if external {
            identity(channel, &path)?;
            if channel["transport"] != "topic"
                || channel["dataType"] != "episode"
                || channel["schema"] != "opentsx.episode.v2"
                || channel["partitioner"] != "murmur2"
            {
                return Err(invalid());
            }
            if channel["from"] == "$source" {
                if !array(plan, "ownership", "deployment.coverage")?
                    .iter()
                    .any(|owner| {
                        [
                            "assignment",
                            "binding",
                            "cluster",
                            "topic",
                            "incarnation",
                            "partitionCount",
                        ]
                        .iter()
                        .all(|field| owner[field] == channel[field])
                    })
                {
                    return Err(invalid());
                }
            } else {
                let port = decimal(&channel["toPort"]).ok_or_else(invalid)? as usize;
                if sinks
                    .get(port)
                    .is_none_or(|sink| sink["topic"] != channel["topic"])
                {
                    return Err(invalid());
                }
            }
        } else if channel["transport"] != "memory"
            || channel["dataType"] != "series"
            || channel["schema"] != "opentsx.series.v1"
            || channel["partitioner"] != "none"
            || channel["partitionCount"] != "0"
            || ["binding", "cluster", "topic", "incarnation"]
                .iter()
                .any(|field| !channel[field].is_null())
        {
            return Err(invalid());
        }
    }
    if actual != expected {
        let missing = expected
            .difference(&actual)
            .min_by_key(|edge| format!("{}/{}/{}", edge.0, edge.3, edge.4));
        let node = missing.map(|edge| {
            if edge.3 == "$sink" {
                edge.1.as_str()
            } else {
                edge.3.as_str()
            }
        });
        return Err(Refusal::new("deployment.channel", node, "/channels"));
    }
    Ok(())
}

fn validate_capability(value: &Value, path: &str) -> Result<(), Refusal> {
    fields(
        value,
        &[
            "assignment",
            "digest",
            "observedAtMicros",
            "validUntilMicros",
            "reportText",
        ],
        path,
    )?;
    if !digest(string(value, "digest"))
        || sha256(string(value, "reportText")) != value["digest"]
        || decimal(&value["observedAtMicros"]).is_none()
        || decimal(&value["validUntilMicros"]).is_none()
    {
        return Err(refusal("deployment.capability", "/capabilities/digest"));
    }
    Ok(())
}

fn nodes<'a>(dag: &'a Value, path: &str) -> Result<&'a [Value], Refusal> {
    let nodes = dag["nodes"]
        .as_array()
        .filter(|nodes| !nodes.is_empty())
        .ok_or_else(|| refusal("deployment.computation", path))?;
    let mut ids = BTreeSet::new();
    for node in nodes {
        if string(node, "id").is_empty()
            || string(node, "op").is_empty()
            || !ids.insert(string(node, "id"))
            || string(node, "id").starts_with('$')
            || node.get("in").is_some_and(|value| {
                value
                    .as_array()
                    .is_none_or(|values| values.iter().any(|value| !value.is_string()))
            })
        {
            return Err(refusal("deployment.computation", "/computationText/nodes"));
        }
    }
    Ok(nodes)
}

fn incoming(node: &Value) -> &[Value] {
    node["in"].as_array().map_or(&[], Vec::as_slice)
}

fn sites(value: &Value, path: &str) -> Result<(), Refusal> {
    let sites = value
        .as_array()
        .filter(|sites| !sites.is_empty())
        .ok_or_else(|| refusal("deployment.channel", path))?;
    let mut seen = BTreeSet::new();
    for site in sites {
        let site = site
            .as_str()
            .filter(|site| crate::identifier(site))
            .ok_or_else(|| refusal("deployment.channel", path))?;
        if !seen.insert(site) {
            return Err(refusal("deployment.channel", path));
        }
    }
    Ok(())
}

fn identity(value: &Value, path: &str) -> Result<(), Refusal> {
    if !crate::identifier(string(value, "binding")) {
        return Err(refusal("deployment.identity", &format!("{path}/binding")));
    }
    for field in ["cluster", "incarnation"] {
        if string(value, field).trim().is_empty() || string(value, field).len() > 256 {
            return Err(refusal(
                "deployment.incarnation",
                &format!("{path}/{field}"),
            ));
        }
    }
    if !crate::valid_topic(string(value, "topic")) {
        return Err(refusal("deployment.channel", &format!("{path}/topic")));
    }
    if decimal(&value["partitionCount"]).is_none_or(|number| number == 0 || number > 128) {
        return Err(refusal(
            "deployment.channel",
            &format!("{path}/partitionCount"),
        ));
    }
    Ok(())
}

fn fields(value: &Value, expected: &[&str], path: &str) -> Result<(), Refusal> {
    if value.as_object().is_none_or(|fields| {
        fields.len() != expected.len() || expected.iter().any(|field| !fields.contains_key(*field))
    }) {
        return Err(refusal("deployment.fields", path));
    }
    Ok(())
}

fn array<'a>(value: &'a Value, field: &str, code: &'static str) -> Result<&'a [Value], Refusal> {
    value[field]
        .as_array()
        .map(Vec::as_slice)
        .ok_or_else(|| refusal(code, &format!("/{field}")))
}

fn number(value: &Value, field: &str, code: &'static str, path: &str) -> Result<u64, Refusal> {
    decimal(&value[field]).ok_or_else(|| refusal(code, &format!("{path}/{field}")))
}

fn decimal(value: &Value) -> Option<u64> {
    let text = value.as_str()?;
    if text.is_empty()
        || text.len() > 19
        || !text.bytes().all(|byte| byte.is_ascii_digit())
        || (text.len() > 1 && text.starts_with('0'))
    {
        return None;
    }
    text.parse()
        .ok()
        .filter(|number| *number <= i64::MAX as u64)
}

fn string<'a>(value: &'a Value, field: &str) -> &'a str {
    value[field].as_str().unwrap_or("")
}

fn hex(value: &str) -> bool {
    value
        .bytes()
        .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
}

fn digest(value: &str) -> bool {
    value.len() == 64 && hex(value)
}

fn located(channel: &Value) -> Option<&str> {
    ["to", "from"].iter().find_map(|field| {
        channel[field]
            .as_str()
            .filter(|node| !node.starts_with('$'))
    })
}

fn refusal(code: &'static str, field: &str) -> Refusal {
    Refusal::new(code, None, field)
}

fn no_numbers(value: &Value, path: &str) -> Result<(), Refusal> {
    match value {
        Value::Number(_) => return Err(refusal("deployment.fields", path)),
        Value::Object(fields) => {
            for (field, value) in fields {
                no_numbers(
                    value,
                    &format!("{path}/{}", field.replace('~', "~0").replace('/', "~1")),
                )?;
            }
        }
        Value::Array(values) => {
            for (index, value) in values.iter().enumerate() {
                no_numbers(value, &format!("{path}/{index}"))?;
            }
        }
        _ => {}
    }
    Ok(())
}

fn exact_integers(text: &str) -> Result<(), Refusal> {
    let bytes = text.as_bytes();
    let mut index = 0;
    while index < bytes.len() {
        if bytes[index] == b'"' {
            index += 1;
            while index < bytes.len() && bytes[index] != b'"' {
                index += if bytes[index] == b'\\' { 2 } else { 1 };
            }
        } else if bytes[index] == b'-' || bytes[index].is_ascii_digit() {
            let start = index;
            while index < bytes.len()
                && !matches!(
                    bytes[index],
                    b',' | b'}' | b']' | b' ' | b'\r' | b'\n' | b'\t'
                )
            {
                index += 1;
            }
            let token = &text[start..index];
            if !token.contains(['.', 'e', 'E']) {
                let number = token
                    .parse::<f64>()
                    .map_err(|_| refusal("deployment.computation", "/computationText"))?;
                if !number.is_finite() || format!("{number:.0}") != token {
                    return Err(refusal("deployment.computation", "/computationText"));
                }
            }
            continue;
        }
        index += 1;
    }
    Ok(())
}
