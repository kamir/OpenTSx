use serde_json::{json, Value};
use tsx_contracts::physical::{self, Refusal};

pub const REQUEST_SCHEMA: &str = "opentsx.local-physical-bounded-run.v1";

pub struct Observation {
    pub facts: Value,
    pub now_micros: String,
}

pub trait AdmissionFacts: Send {
    fn observe(&mut self, physical_plan: &Value) -> Result<Observation, Refusal>;
}

pub struct UnavailableFacts;

impl AdmissionFacts for UnavailableFacts {
    fn observe(&mut self, physical_plan: &Value) -> Result<Observation, Refusal> {
        let channels = physical_plan["channels"].as_array();
        let channel = channels.and_then(|channels| {
            channels
                .iter()
                .enumerate()
                .find(|(_, channel)| channel["transport"] == "topic")
        });
        let (node, field) = match channel {
            Some((index, channel)) => (
                ["to", "from"].iter().find_map(|field| {
                    channel[field]
                        .as_str()
                        .filter(|value| !value.starts_with('$'))
                }),
                format!("/channels/{index}/incarnation"),
            ),
            None => (None, "/channels".into()),
        };
        Err(Refusal::new("deployment.incarnation", node, &field))
    }
}

pub struct Request {
    pub physical_text: String,
    pub local_text: String,
    pub plan: Value,
}

pub fn request(text: &str, limit: usize) -> Result<Option<Request>, String> {
    let value = tsx_contracts::parse_json(text, limit).map_err(str::to_owned)?;
    if value["schema"] != REQUEST_SCHEMA {
        return Ok(None);
    }
    let fields = value
        .as_object()
        .ok_or_else(|| refused("deployment.fields", ""))?;
    if fields.len() != 3
        || !["schema", "physicalText", "requestText"]
            .iter()
            .all(|field| fields.contains_key(*field))
    {
        return Err(refused("deployment.fields", ""));
    }
    let physical_text = value["physicalText"]
        .as_str()
        .ok_or_else(|| refused("deployment.fields", "/physicalText"))?;
    let local_text = value["requestText"]
        .as_str()
        .ok_or_else(|| refused("deployment.fields", "/requestText"))?;
    let plan = physical::validate(physical_text).map_err(|error| error.to_string())?;
    let local = tsx_contracts::parse_json(local_text, limit)
        .map_err(|_| refused("deployment.assignment", "/requestText"))?;
    if local["schema"] != "opentsx.local-bounded-run.v1" {
        return Err(refused("deployment.assignment", "/requestText/schema"));
    }
    if !plan["assignmentTexts"]
        .as_array()
        .is_some_and(|texts| texts.contains(&local["assignmentText"]))
        || local["createdAtMicros"] != plan["createdAtMicros"]
    {
        return Err(refused(
            "deployment.assignment",
            "/requestText/assignmentText",
        ));
    }
    Ok(Some(Request {
        physical_text: physical_text.into(),
        local_text: local_text.into(),
        plan,
    }))
}

pub fn wrapper(physical_text: &str, local_text: &str) -> String {
    json!({"schema":REQUEST_SCHEMA,"physicalText":physical_text,"requestText":local_text})
        .to_string()
}

pub fn refusal_value(error: &str) -> Option<Value> {
    let value: Value = serde_json::from_str(error).ok()?;
    if value["schema"] == "opentsx.plan-refusal.v1"
        && value["code"]
            .as_str()
            .is_some_and(|code| code.starts_with("deployment."))
    {
        Some(value)
    } else {
        None
    }
}

pub fn refused(code: &'static str, field: &str) -> String {
    Refusal::new(code, None, field).to_string()
}
