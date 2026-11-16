use serde_json::Value;
use sha2::{Digest, Sha256};
use std::collections::{HashMap, HashSet};

const MAX_ASSIGNMENT_BYTES: usize = 65_536;
const MAX_DAG_BYTES: usize = 32_768;
const MAX_DEPTH: usize = 64;
const MAX_INPUTS: usize = 128;
const FIELDS: [&str; 18] = [
    "schema",
    "deployment",
    "generation",
    "assignment",
    "attempt",
    "fence",
    "fragment",
    "site",
    "node",
    "runtime",
    "catalog",
    "artifactDigest",
    "specHash",
    "sourceDagText",
    "fragmentHash",
    "fragmentText",
    "mode",
    "inputs",
];

pub fn sha256(text: &str) -> String {
    format!("{:x}", Sha256::digest(text.as_bytes()))
}

pub fn validate(input: &str) -> Result<(), &'static str> {
    admit(input).map(|_| ())
}

pub fn validate_set(inputs: &[&str]) -> Result<(), &'static str> {
    let assignments = inputs
        .iter()
        .map(|input| admit(input))
        .collect::<Result<Vec<_>, _>>()?;
    let Some(first) = assignments.first() else {
        return Err("assignment.context");
    };
    let mut fragments = HashMap::new();
    for assignment in &assignments {
        for field in ["deployment", "generation", "specHash", "mode", "catalog"] {
            if assignment[field] != first[field] {
                return Err("assignment.context");
            }
        }
        let fragment = text(assignment, "fragment");
        let hash = text(assignment, "fragmentHash");
        if let Some(previous) = fragments.insert(fragment, hash) {
            if previous != hash {
                return Err("assignment.context");
            }
        }
    }
    let mut identifiers = HashSet::new();
    for assignment in &assignments {
        if !identifiers.insert(text(assignment, "assignment")) {
            return Err("assignment.duplicate");
        }
    }
    let mut owners = HashSet::new();
    for assignment in &assignments {
        for input in assignment["inputs"].as_array().ok_or("assignment.input")? {
            if !owners.insert((
                text(assignment, "fragment"),
                text(input, "binding"),
                text(input, "topic"),
                text(input, "partition"),
            )) {
                return Err("assignment.ownership");
            }
        }
    }
    Ok(())
}

fn admit(input: &str) -> Result<Value, &'static str> {
    if input.len() > MAX_ASSIGNMENT_BYTES {
        return Err("assignment.limit");
    }
    let assignment = strict_json(input).map_err(|_| "assignment.json")?;
    let object = assignment.as_object().ok_or("assignment.json")?;
    if object.len() != FIELDS.len() || !FIELDS.iter().all(|field| object.contains_key(*field)) {
        return Err("assignment.fields");
    }
    if text(&assignment, "schema") != "opentsx.execution-assignment.v1" {
        return Err("assignment.version");
    }
    for field in [
        "deployment",
        "assignment",
        "attempt",
        "fragment",
        "site",
        "node",
    ] {
        if !identifier(text(&assignment, field)) {
            return Err("assignment.identity");
        }
    }
    for field in ["generation", "fence"] {
        decimal(&assignment[field], i64::MAX as u64, true).ok_or("assignment.epoch")?;
    }
    if !matches!(text(&assignment, "runtime"), "java" | "rust")
        || text(&assignment, "catalog") != "tsx-catalog/1"
        || !text(&assignment, "artifactDigest")
            .strip_prefix("sha256:")
            .is_some_and(hex64)
    {
        return Err("assignment.runtime");
    }
    let source = assignment["sourceDagText"]
        .as_str()
        .filter(|value| value.len() <= MAX_DAG_BYTES)
        .ok_or("assignment.dag")?;
    let fragment = assignment["fragmentText"]
        .as_str()
        .filter(|value| value.len() <= MAX_DAG_BYTES)
        .ok_or("assignment.dag")?;
    for (field, content) in [("specHash", source), ("fragmentHash", fragment)] {
        let hash = text(&assignment, field);
        if !hex64(hash) || sha256(content) != hash {
            return Err("assignment.hash");
        }
    }
    let bounded = match text(&assignment, "mode") {
        "bounded" => true,
        "continuous" => false,
        _ => return Err("assignment.mode"),
    };
    let dag = strict_json(fragment).map_err(|_| "assignment.dag")?;
    if !dag.is_object()
        || !dag["source"].is_object()
        || dag["nodes"].as_array().is_none_or(|nodes| nodes.is_empty())
        || dag["sinks"].as_array().is_none_or(|sinks| sinks.is_empty())
    {
        return Err("assignment.dag");
    }
    let inputs = assignment["inputs"]
        .as_array()
        .filter(|inputs| !inputs.is_empty() && inputs.len() <= MAX_INPUTS)
        .ok_or("assignment.input")?;
    for input in inputs {
        let fields = input.as_object().ok_or("assignment.input")?;
        let required = if bounded {
            &["binding", "topic", "partition", "from", "to"][..]
        } else {
            &["binding", "topic", "partition", "from"][..]
        };
        if fields.len() != required.len()
            || !required.iter().all(|field| fields.contains_key(*field))
            || !identifier(text(input, "binding"))
            || !topic(text(input, "topic"))
        {
            return Err("assignment.input");
        }
    }
    for input in inputs {
        decimal(&input["partition"], i32::MAX as u64, false).ok_or("assignment.offset")?;
        let from = decimal(&input["from"], i64::MAX as u64, false).ok_or("assignment.offset")?;
        if bounded {
            let to = decimal(&input["to"], i64::MAX as u64, false).ok_or("assignment.offset")?;
            if to < from {
                return Err("assignment.offset");
            }
        }
    }
    let mut owners = HashSet::new();
    for input in inputs {
        if !owners.insert((
            text(input, "binding"),
            text(input, "topic"),
            text(input, "partition"),
        )) {
            return Err("assignment.ownership");
        }
    }
    Ok(assignment)
}

fn text<'a>(value: &'a Value, field: &str) -> &'a str {
    value.get(field).and_then(Value::as_str).unwrap_or("")
}

fn identifier(value: &str) -> bool {
    !value.is_empty()
        && value.len() <= 63
        && value.as_bytes()[0].is_ascii_alphanumeric()
        && value
            .bytes()
            .all(|byte| byte.is_ascii_lowercase() || byte.is_ascii_digit() || byte == b'-')
}

fn hex64(value: &str) -> bool {
    value.len() == 64
        && value
            .bytes()
            .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
}

fn decimal(value: &Value, maximum: u64, positive: bool) -> Option<u64> {
    let value = value.as_str()?;
    if value.is_empty()
        || !value.bytes().all(|byte| byte.is_ascii_digit())
        || (value.len() > 1 && value.starts_with('0'))
    {
        return None;
    }
    let number = value.parse::<u64>().ok()?;
    if number > maximum || (positive && number == 0) {
        return None;
    }
    Some(number)
}

fn topic(value: &str) -> bool {
    if value.len() > 249 || !value.is_ascii() {
        return false;
    }
    let parts: Vec<_> = value.split('.').collect();
    if parts.len() < 4 || parts[0] != "tsx" {
        return false;
    }
    let segment = |part: &str, underscore: bool| {
        !part.is_empty()
            && part.as_bytes()[0].is_ascii_alphanumeric()
            && part.bytes().all(|byte| {
                byte.is_ascii_lowercase()
                    || byte.is_ascii_digit()
                    || byte == b'-'
                    || (underscore && byte == b'_')
            })
    };
    if !segment(parts[1], true)
        || !parts[2..parts.len() - 1]
            .iter()
            .all(|part| segment(part, false))
    {
        return false;
    }
    parts[parts.len() - 1]
        .strip_prefix('v')
        .is_some_and(|version| {
            !version.is_empty()
                && (b'1'..=b'9').contains(&version.as_bytes()[0])
                && version.bytes().all(|byte| byte.is_ascii_digit())
        })
}

fn strict_json(input: &str) -> Result<Value, ()> {
    let bytes = input.as_bytes();
    let mut stack: Vec<(Option<HashSet<String>>, bool)> = Vec::new();
    let mut index = 0;
    while index < bytes.len() {
        match bytes[index] {
            b'"' => {
                let start = index;
                index += 1;
                while index < bytes.len() && bytes[index] != b'"' {
                    index += if bytes[index] == b'\\' { 2 } else { 1 };
                }
                if index >= bytes.len() {
                    return Err(());
                }
                if let Some((Some(keys), true)) = stack.last_mut() {
                    let key: String =
                        serde_json::from_str(&input[start..=index]).map_err(|_| ())?;
                    if !keys.insert(key) {
                        return Err(());
                    }
                }
            }
            b'{' | b'[' => {
                if stack.len() >= MAX_DEPTH {
                    return Err(());
                }
                stack.push((
                    if bytes[index] == b'{' {
                        Some(HashSet::new())
                    } else {
                        None
                    },
                    true,
                ));
            }
            b'}' | b']' => {
                stack.pop().ok_or(())?;
            }
            b':' | b',' => {
                if let Some((_, expecting_key)) = stack.last_mut() {
                    *expecting_key = bytes[index] == b',';
                }
            }
            _ => {}
        }
        index += 1;
    }
    serde_json::from_str(input).map_err(|_| ())
}
