use crate::model::{series_id, summary, xxh64_hex, Episode, Provenance, SeriesKey, TimeEncoding};
use serde_json::{json, Value};
use std::collections::{BTreeMap, BTreeSet};

pub const SUPPORTED_OPS: &[&str] = &["dq.checks", "convert.unit", "resample"];
const STATE_ABI: &str = "opentsx.rust.clean-state.v1";
const MAX_EMPTY_WINDOWS: i64 = 10_000;

#[derive(Clone, Copy, Debug)]
pub struct Limits {
    pub max_series: usize,
    pub max_samples: usize,
    pub max_output_samples: usize,
    pub max_state_bytes: usize,
}

#[derive(Clone, Debug)]
enum Step {
    Range { min: f64, max: f64, clip: bool },
    Convert { factor: f64, offset: f64 },
    Resample { every: i64, agg: Agg },
}

#[derive(Clone, Copy, Debug)]
enum Agg {
    Mean,
    Min,
    Max,
    Last,
}

#[derive(Clone, Debug)]
struct Target {
    system: String,
    signal: String,
    flow: String,
    origin: Option<String>,
    made_of: Option<String>,
    metric: Option<String>,
    unit: Option<String>,
}

#[derive(Clone, Debug)]
struct Window {
    start: i64,
    sum: f64,
    count: u64,
    min: f64,
    max: f64,
    last: f64,
}

impl Window {
    fn new(start: i64) -> Self {
        Self {
            start,
            sum: 0.0,
            count: 0,
            min: f64::INFINITY,
            max: f64::NEG_INFINITY,
            last: f64::NAN,
        }
    }

    fn value(&self, agg: Agg) -> f64 {
        if self.count == 0 {
            return f64::NAN;
        }
        match agg {
            Agg::Mean => self.sum / self.count as f64,
            Agg::Min => self.min,
            Agg::Max => self.max,
            Agg::Last => self.last,
        }
    }
}

#[derive(Clone, Debug)]
struct Raw {
    key: SeriesKey,
    end: i64,
    written: u64,
    window: Option<Window>,
}

#[derive(Clone, Debug)]
pub struct Pipeline {
    fragment: String,
    spec_hash: String,
    created_at: i64,
    limits: Limits,
    source_topic: String,
    sink_name: String,
    sink_topic: Option<String>,
    expected: BTreeSet<String>,
    steps: Vec<Step>,
    target: Option<Target>,
    series: BTreeMap<String, Raw>,
    output_owners: BTreeMap<String, String>,
    skipped: u64,
}

fn object<'a>(value: &'a Value, name: &str) -> Result<&'a serde_json::Map<String, Value>, String> {
    value
        .as_object()
        .ok_or_else(|| format!("{name} must be an object"))
}

fn text<'a>(value: &'a Value, name: &str) -> Result<&'a str, String> {
    value
        .as_str()
        .filter(|s| !java_blank(s))
        .ok_or_else(|| format!("{name} must be a nonempty string"))
}

fn optional_text(value: &Value, name: &str) -> Result<Option<String>, String> {
    match value.get(name) {
        None => Ok(None),
        Some(value) => value
            .as_str()
            .map(|s| Some(s.to_owned()))
            .ok_or_else(|| format!("{name} must be a string")),
    }
}

fn java_blank(value: &str) -> bool {
    value.chars().all(|c| matches!(c, '\t'..='\r' | '\u{1c}'..=' ' | '\u{1680}' |
        '\u{2000}'..='\u{2006}' | '\u{2008}'..='\u{200a}' | '\u{2028}' | '\u{2029}' | '\u{205f}' | '\u{3000}'))
}

fn number(params: &Value, key: &str, default: f64) -> Result<f64, String> {
    match params.get(key) {
        None => Ok(default),
        Some(value) => value
            .as_f64()
            .filter(|v| v.is_finite())
            .ok_or_else(|| format!("{key} must be a finite number")),
    }
}

fn allowed_keys(value: &Value, keys: &[&str], name: &str) -> Result<(), String> {
    if object(value, name)?
        .keys()
        .any(|k| !keys.contains(&k.as_str()))
    {
        return Err(format!("unsupported {name} field"));
    }
    Ok(())
}

fn duration(value: &Value) -> Result<i64, String> {
    if let Some(seconds) = value.as_f64() {
        let micros = seconds * 1_000_000.0;
        if !micros.is_finite() || micros <= 0.0 || micros >= i64::MAX as f64 {
            return Err("resample every must be a positive microsecond duration".into());
        }
        let result = micros.round() as i64;
        if result <= 0 {
            return Err("resample every must be a positive microsecond duration".into());
        }
        return Ok(result);
    }
    let raw = value
        .as_str()
        .ok_or("resample every must be a duration or number of seconds")?;
    let body = raw
        .strip_prefix('P')
        .ok_or("resample every must be an ISO duration")?;
    let mut total = 0i64;
    let mut field = String::new();
    let mut time = false;
    let mut previous = 0;
    let mut terms = 0;
    for character in body.chars() {
        if character == 'T' && !time && field.is_empty() {
            time = true;
            continue;
        }
        if character.is_ascii_digit() || character == '.' {
            field.push(character);
            continue;
        }
        let (rank, scale) = match (time, character) {
            (false, 'D') => (1, 86_400_000_000i64),
            (true, 'H') => (2, 3_600_000_000),
            (true, 'M') => (3, 60_000_000),
            (true, 'S') => (4, 1_000_000),
            _ => return Err("resample every must be an ISO duration".into()),
        };
        if rank <= previous || field.is_empty() {
            return Err("resample every must be an ISO duration".into());
        }
        let amount = if character == 'S' {
            let parts: Vec<_> = field.split('.').collect();
            if parts.len() > 2 || parts[0].is_empty() {
                return Err("invalid resample duration".into());
            }
            let whole = parts[0]
                .parse::<i64>()
                .map_err(|_| "resample duration overflow")?;
            let mut value = whole
                .checked_mul(scale)
                .ok_or("resample duration overflow")?;
            if parts.len() == 2 {
                let fraction = parts[1];
                if fraction.is_empty()
                    || fraction.len() > 9
                    || !fraction.bytes().all(|c| c.is_ascii_digit())
                {
                    return Err("invalid resample duration fraction".into());
                }
                let padded = format!("{fraction:0<9}");
                let nanos = padded
                    .parse::<i64>()
                    .map_err(|_| "invalid resample duration fraction")?;
                value = value
                    .checked_add(nanos / 1000)
                    .ok_or("resample duration overflow")?;
            }
            value
        } else {
            field
                .parse::<i64>()
                .map_err(|_| "invalid resample duration")?
                .checked_mul(scale)
                .ok_or("resample duration overflow")?
        };
        total = total
            .checked_add(amount)
            .ok_or("resample duration overflow")?;
        previous = rank;
        field.clear();
        terms += 1;
    }
    if !field.is_empty() || terms == 0 || raw.ends_with('T') || total <= 0 {
        return Err("resample every must be a positive duration".into());
    }
    Ok(total)
}

impl Pipeline {
    pub fn compile(
        fragment: &Value,
        spec_hash: &str,
        created_at: i64,
        limits: Limits,
    ) -> Result<Self, String> {
        if [
            limits.max_series,
            limits.max_samples,
            limits.max_output_samples,
            limits.max_state_bytes,
        ]
        .contains(&0)
        {
            return Err("operator limits must be positive".into());
        }
        if spec_hash.len() != 64
            || !spec_hash
                .bytes()
                .all(|c| c.is_ascii_digit() || (b'a'..=b'f').contains(&c))
        {
            return Err("invalid pipeline specHash".into());
        }
        object(fragment, "fragment")?;
        if let Some(schema) = fragment.get("schema") {
            if !matches!(schema.as_str(), Some("opentsx.dag.v1" | "opentsx.dag.v2")) {
                return Err("unsupported DAG schema".into());
            }
        }
        let source = &fragment["source"];
        object(source, "source")?;
        if let Some(kind) = source.get("kind") {
            if kind.as_str() != Some("kafscale-broker") {
                return Err("unsupported source kind".into());
            }
        }
        let topics = source["topics"]
            .as_array()
            .ok_or("source topics must be an array")?;
        if topics.len() != 1 {
            return Err("a pipeline reads exactly one whole topic".into());
        }
        let source_topic = text(&topics[0], "source topic")?.to_owned();
        let mut expected = BTreeSet::new();
        if let Some(ids) = source.get("series") {
            for id in ids.as_array().ok_or("source series must be an array")? {
                let id = text(id, "source series id")?;
                if id.len() != 16
                    || !id
                        .bytes()
                        .all(|c| c.is_ascii_digit() || (b'a'..=b'f').contains(&c))
                    || !expected.insert(id.to_owned())
                {
                    return Err("source series must contain unique canonical series IDs".into());
                }
            }
        }
        if expected.len() > limits.max_series {
            return Err("source series limit exceeded".into());
        }
        let sinks = fragment["sinks"]
            .as_array()
            .ok_or("sinks must be an array")?;
        if sinks.len() != 1 {
            return Err("a pipeline needs exactly one sink".into());
        }
        let sink = &sinks[0];
        object(sink, "sink")?;
        if sink["schema"].as_str() != Some("opentsx.episode.v2") {
            return Err("unsupported sink schema".into());
        }
        let sink_topic = optional_text(sink, "topic")?;
        if sink_topic.as_ref().is_some_and(|s| java_blank(s)) {
            return Err("sink topic must not be blank".into());
        }
        let node_values = fragment["nodes"]
            .as_array()
            .ok_or("nodes must be an array")?;
        let mut nodes = BTreeMap::new();
        for node in node_values {
            object(node, "node")?;
            let id = text(&node["id"], "node id")?;
            if nodes.insert(id, node).is_some() {
                return Err("duplicate node id".into());
            }
            text(&node["op"], "node operator")?;
        }
        let mut at = text(&sink["from"], "sink input")?;
        let mut visited = BTreeSet::new();
        let mut chain = Vec::new();
        loop {
            if !visited.insert(at) {
                return Err("pipeline graph contains a cycle".into());
            }
            let node = nodes
                .get(at)
                .ok_or("pipeline graph references a missing node")?;
            let op = text(&node["op"], "operator")?;
            if op == "decode.avro-soe" {
                if let Some(params) = node.get("params") {
                    allowed_keys(params, &[], "decoder parameter")?;
                }
                if node
                    .get("in")
                    .is_some_and(|v| v.as_array().is_none_or(|a| !a.is_empty()))
                {
                    return Err("decoder must read the source directly".into());
                }
                break;
            }
            if !SUPPORTED_OPS.contains(&op) {
                return Err(format!("unsupported operator {op}"));
            }
            let inputs = node["in"]
                .as_array()
                .ok_or("operator inputs must be an array")?;
            if inputs.len() != 1 {
                return Err("a cleaning operator needs exactly one input".into());
            }
            chain.push(*node);
            at = text(&inputs[0], "operator input")?;
        }
        if visited.len() != nodes.len() {
            return Err("pipeline graph contains unused nodes".into());
        }
        chain.reverse();
        let mut steps = Vec::new();
        let mut unit = None;
        for (index, node) in chain.iter().enumerate() {
            let empty = json!({});
            let params = node.get("params").unwrap_or(&empty);
            match node["op"].as_str().ok_or("invalid operator")? {
                "dq.checks" => {
                    allowed_keys(
                        params,
                        &["checks", "min", "max", "mode"],
                        "dq.checks parameter",
                    )?;
                    if let Some(checks) = params.get("checks") {
                        if checks
                            .as_array()
                            .is_none_or(|a| a.iter().any(|v| v.as_str() != Some("PLAUSIBLE_RANGE")))
                        {
                            return Err("dq.checks supports PLAUSIBLE_RANGE only".into());
                        }
                    }
                    let min = number(params, "min", f64::NEG_INFINITY)?;
                    let max = number(params, "max", f64::INFINITY)?;
                    if min > max {
                        return Err("dq.checks min is above max".into());
                    }
                    let mode = optional_text(params, "mode")?.unwrap_or_else(|| "gap".into());
                    if mode != "gap" && mode != "clip" {
                        return Err("dq.checks mode must be gap or clip".into());
                    }
                    steps.push(Step::Range {
                        min,
                        max,
                        clip: mode == "clip",
                    });
                }
                "convert.unit" => {
                    allowed_keys(
                        params,
                        &["factor", "offset", "unit"],
                        "convert.unit parameter",
                    )?;
                    let factor = number(params, "factor", 1.0)?;
                    let offset = number(params, "offset", 0.0)?;
                    if let Some(value) = optional_text(params, "unit")? {
                        if !java_blank(&value) {
                            unit = Some(value);
                        }
                    }
                    steps.push(Step::Convert { factor, offset });
                }
                "resample" => {
                    allowed_keys(params, &["every", "agg"], "resample parameter")?;
                    if index + 1 != chain.len() {
                        return Err("resample is supported only as the trailing operator".into());
                    }
                    let every = duration(&params["every"])?;
                    every
                        .checked_mul(MAX_EMPTY_WINDOWS)
                        .ok_or("resample duration exceeds arithmetic bounds")?;
                    let agg = match optional_text(params, "agg")?
                        .unwrap_or_else(|| "MEAN".into())
                        .to_ascii_uppercase()
                        .as_str()
                    {
                        "MEAN" => Agg::Mean,
                        "MIN" => Agg::Min,
                        "MAX" => Agg::Max,
                        "LAST" => Agg::Last,
                        _ => return Err("unsupported resample aggregation".into()),
                    };
                    steps.push(Step::Resample { every, agg });
                }
                _ => return Err("unsupported operator".into()),
            }
        }
        let target = if steps.is_empty() {
            None
        } else {
            if expected.is_empty() {
                return Err("a cleaning pipeline needs source series IDs".into());
            }
            let signal = &sink["signal"];
            object(signal, "sink signal")?;
            if optional_text(signal, "grade")?.unwrap_or_else(|| "clean".into()) != "clean" {
                return Err("this runtime supports clean grade only".into());
            }
            let metric = optional_text(signal, "metric")?;
            if metric.as_ref().is_some_and(|s| java_blank(s)) {
                return Err("sink metric must not be blank".into());
            }
            Some(Target {
                system: text(&signal["system"], "signal system")?.into(),
                signal: text(&signal["id"], "signal id")?.into(),
                flow: optional_text(fragment, "dag")?.unwrap_or_else(|| "dag".into()),
                origin: optional_text(sink, "origin")?,
                made_of: optional_text(sink, "madeOf")?,
                metric,
                unit,
            })
        };
        let sink_name = match optional_text(sink, "name")? {
            Some(name) if !java_blank(&name) => name,
            Some(_) => return Err("sink name must not be blank".into()),
            None => target
                .as_ref()
                .map(|t| {
                    t.signal
                        .chars()
                        .map(|c| {
                            if c.is_ascii_alphanumeric() || "._-".contains(c) {
                                c
                            } else {
                                '-'
                            }
                        })
                        .collect()
                })
                .unwrap_or_else(|| sink["from"].as_str().unwrap_or("").into()),
        };
        let out = Self {
            fragment: serde_json::to_string(fragment)
                .map_err(|_| "fragment serialization failed")?,
            spec_hash: spec_hash.into(),
            created_at,
            limits,
            source_topic,
            sink_name,
            sink_topic,
            expected,
            steps,
            target,
            series: BTreeMap::new(),
            output_owners: BTreeMap::new(),
            skipped: 0,
        };
        out.snapshot()?;
        Ok(out)
    }

    pub fn source_topic(&self) -> &str {
        &self.source_topic
    }
    pub fn sink_name(&self) -> &str {
        &self.sink_name
    }
    pub fn sink_topic(&self) -> Option<&str> {
        self.sink_topic.as_deref()
    }
    pub fn expected_series(&self) -> &BTreeSet<String> {
        &self.expected
    }
    pub fn is_passthrough(&self) -> bool {
        self.target.is_none()
    }

    pub fn process(&mut self, episodes: &[Episode]) -> Result<Vec<Episode>, String> {
        let mut candidate = self.clone();
        let out = candidate.process_inner(episodes)?;
        candidate.snapshot()?;
        *self = candidate;
        Ok(out)
    }

    fn process_inner(&mut self, episodes: &[Episode]) -> Result<Vec<Episode>, String> {
        let mut input_count = 0usize;
        let mut output_count = 0usize;
        let mut seen = BTreeSet::new();
        let mut out = Vec::new();
        for episode in episodes {
            episode.validate(self.limits.max_samples)?;
            input_count = input_count
                .checked_add(episode.values.len())
                .ok_or("input sample count overflow")?;
            if input_count > self.limits.max_samples {
                return Err("input sample limit exceeded".into());
            }
            if episode.values.iter().any(|v| v.is_infinite()) {
                return Err("infinite input values are unsupported".into());
            }
            if !self.expected.is_empty() && !self.expected.contains(&episode.series.series_id) {
                continue;
            }
            seen.insert(episode.series.series_id.as_str());
            if seen.len() > self.limits.max_series {
                return Err("operator series limit exceeded".into());
            }
            if self.is_passthrough() {
                output_count = output_count
                    .checked_add(episode.values.len())
                    .ok_or("output sample count overflow")?;
                if output_count > self.limits.max_output_samples {
                    return Err("output sample limit exceeded".into());
                }
                out.push(episode.clone());
                continue;
            }
            if episode.revision != 0 || episode.quality.is_some() {
                return Err("cleaning rejects revisions and per-value quality".into());
            }
            let mut times = episode.timestamps()?;
            if times.is_empty() {
                continue;
            }
            if !self.series.contains_key(&episode.series.series_id)
                && self.series.len() >= self.limits.max_series
            {
                return Err("operator series limit exceeded".into());
            }
            let raw = self
                .series
                .entry(episode.series.series_id.clone())
                .or_insert_with(|| Raw {
                    key: episode.series.clone(),
                    end: i64::MIN,
                    written: 0,
                    window: None,
                });
            if raw.key.metric != episode.series.metric
                || raw.key.tags != episode.series.tags
                || raw.key.unit != episode.series.unit
            {
                return Err("source series metadata changed".into());
            }
            let target = self.target.as_ref().ok_or("missing clean target")?;
            let key = output_key(target, &episode.series)?;
            if self
                .output_owners
                .insert(key.series_id.clone(), episode.series.series_id.clone())
                .is_some_and(|owner| owner != episode.series.series_id)
            {
                return Err("distinct source series map to one derived series".into());
            }
            if times[0] < raw.end {
                self.skipped = self
                    .skipped
                    .checked_add(1)
                    .ok_or("skipped block count overflow")?;
                continue;
            }
            raw.end = episode.t_end;
            let mut values = episode.values.clone();
            for step in &self.steps {
                match *step {
                    Step::Range { min, max, clip } => {
                        for value in &mut values {
                            if *value < min {
                                *value = if clip { min } else { f64::NAN };
                            } else if *value > max {
                                *value = if clip { max } else { f64::NAN };
                            }
                        }
                    }
                    Step::Convert { factor, offset } => {
                        for value in &mut values {
                            *value = *value * factor + offset;
                            if value.is_infinite() {
                                return Err("unit conversion overflow".into());
                            }
                        }
                    }
                    Step::Resample { every, agg } => {
                        (times, values) = resample(
                            &mut raw.window,
                            (&times, &values),
                            episode.t_end,
                            every,
                            agg,
                            self.limits.max_samples,
                            self.limits.max_output_samples.saturating_sub(output_count),
                        )?;
                    }
                }
            }
            output_count = output_count
                .checked_add(values.len())
                .ok_or("output sample count overflow")?;
            if output_count > self.limits.max_output_samples {
                return Err("output sample limit exceeded".into());
            }
            raw.written = raw
                .written
                .checked_add(values.len() as u64)
                .ok_or("written count overflow")?;
            if values.is_empty() {
                continue;
            }
            if let Some(Step::Resample { every, .. }) = self.steps.last() {
                let mut from = 0;
                for to in 1..=times.len() {
                    if to == times.len() || times[to].checked_sub(times[to - 1]) != Some(*every) {
                        let end = times[to - 1]
                            .checked_add(*every)
                            .ok_or("output episode extent overflow")?;
                        out.push(clean_episode(
                            target,
                            &self.spec_hash,
                            self.created_at,
                            &key,
                            times[from],
                            end,
                            TimeEncoding::Regular,
                            Some(*every),
                            None,
                            values[from..to].to_vec(),
                        )?);
                        from = to;
                    }
                }
            } else {
                let deltas = if episode.time_encoding == TimeEncoding::IrregularDelta {
                    Some(encode_deltas(&times)?)
                } else {
                    None
                };
                out.push(clean_episode(
                    target,
                    &self.spec_hash,
                    self.created_at,
                    &key,
                    times[0],
                    episode.t_end,
                    episode.time_encoding,
                    episode.interval_micros,
                    deltas,
                    values,
                )?);
            }
        }
        Ok(out)
    }

    pub fn snapshot(&self) -> Result<Value, String> {
        let mut records = serde_json::Map::new();
        for (id, raw) in &self.series {
            let window = raw.window.as_ref().map(|w| {
                json!({"start":w.start.to_string(),"sum":bits(w.sum),"count":w.count.to_string(),
                "min":bits(w.min),"max":bits(w.max),"last":bits(w.last)})
            });
            records.insert(id.clone(), json!({"key":raw.key,"end":raw.end.to_string(),"written":raw.written.to_string(),"window":window}));
        }
        let state = json!({"schema":STATE_ABI,"specHash":self.spec_hash,"createdAt":self.created_at.to_string(),
            "fragment":self.fragment,"skippedBlocks":self.skipped.to_string(),"series":records,
            "outputOwners":self.output_owners});
        check_state_size(&state, self.limits.max_state_bytes)?;
        Ok(state)
    }

    pub fn restore(&mut self, state: &Value) -> Result<(), String> {
        check_state_size(state, self.limits.max_state_bytes)?;
        exact_keys(
            state,
            &[
                "schema",
                "specHash",
                "createdAt",
                "fragment",
                "skippedBlocks",
                "series",
                "outputOwners",
            ],
        )?;
        if state["schema"].as_str() != Some(STATE_ABI)
            || state["specHash"].as_str() != Some(&self.spec_hash)
            || state["createdAt"].as_str() != Some(&self.created_at.to_string())
            || state["fragment"].as_str() != Some(self.fragment.as_str())
        {
            return Err("operator checkpoint identity or ABI mismatch".into());
        }
        let skipped = unsigned(&state["skippedBlocks"])?;
        let mut series = BTreeMap::new();
        let mut output_owners = BTreeMap::new();
        let entries = object(&state["series"], "state series")?;
        if entries.len() > self.limits.max_series || (self.is_passthrough() && !entries.is_empty())
        {
            return Err("invalid operator state series inventory".into());
        }
        for (id, record) in entries {
            exact_keys(record, &["key", "end", "written", "window"])?;
            if !self.expected.contains(id) {
                return Err("operator state contains an unexpected series".into());
            }
            let key: SeriesKey = serde_json::from_value(record["key"].clone())
                .map_err(|_| "invalid state series key")?;
            if &key.series_id != id || series_id(&key.metric, &key.tags)? != *id {
                return Err("invalid state series identity".into());
            }
            let derived = output_key(self.target.as_ref().ok_or("missing clean target")?, &key)?;
            if output_owners
                .insert(derived.series_id, id.clone())
                .is_some()
            {
                return Err("distinct source series map to one derived series".into());
            }
            let window = if record["window"].is_null() {
                None
            } else {
                let Some(Step::Resample { every, .. }) = self.steps.last() else {
                    return Err("unexpected resample state".into());
                };
                let w = &record["window"];
                exact_keys(w, &["start", "sum", "count", "min", "max", "last"])?;
                let start = signed(&w["start"])?;
                let count = unsigned(&w["count"])?;
                let window = Window {
                    start,
                    sum: float(&w["sum"])?,
                    count,
                    min: float(&w["min"])?,
                    max: float(&w["max"])?,
                    last: float(&w["last"])?,
                };
                if start.rem_euclid(*every) != 0
                    || count > self.limits.max_samples as u64
                    || !window.sum.is_finite()
                    || (count == 0
                        && (window.sum != 0.0
                            || window.min != f64::INFINITY
                            || window.max != f64::NEG_INFINITY
                            || !window.last.is_nan()))
                    || (count > 0
                        && (!window.min.is_finite()
                            || !window.max.is_finite()
                            || !window.last.is_finite()
                            || window.min > window.max))
                {
                    return Err("invalid resample state".into());
                }
                Some(window)
            };
            series.insert(
                id.clone(),
                Raw {
                    key,
                    end: signed(&record["end"])?,
                    written: unsigned(&record["written"])?,
                    window,
                },
            );
        }
        let saved_owners: BTreeMap<String, String> =
            serde_json::from_value(state["outputOwners"].clone())
                .map_err(|_| "invalid derived series ownership")?;
        if saved_owners != output_owners {
            return Err("derived series ownership does not match retained source keys".into());
        }
        self.series = series;
        self.output_owners = output_owners;
        self.skipped = skipped;
        Ok(())
    }
}

fn output_key(target: &Target, raw: &SeriesKey) -> Result<SeriesKey, String> {
    let mut tags = if target.metric.is_none() {
        raw.tags.clone()
    } else {
        BTreeMap::new()
    };
    tags.insert("system".into(), target.system.clone());
    tags.insert("signal".into(), target.signal.clone());
    tags.insert("grade".into(), "clean".into());
    if !java_blank(&target.flow) {
        tags.insert("flow".into(), target.flow.clone());
    }
    tags.remove("feature");
    let metric = target.metric.as_ref().unwrap_or(&raw.metric).clone();
    Ok(SeriesKey {
        series_id: series_id(&metric, &tags)?,
        metric,
        tags,
        unit: target.unit.clone().or_else(|| raw.unit.clone()),
    })
}

#[allow(clippy::too_many_arguments)]
fn clean_episode(
    target: &Target,
    spec_hash: &str,
    created_at: i64,
    key: &SeriesKey,
    from: i64,
    to: i64,
    time_encoding: TimeEncoding,
    interval_micros: Option<i64>,
    time_deltas: Option<Vec<u8>>,
    values: Vec<f64>,
) -> Result<Episode, String> {
    let mut labels = BTreeMap::from([
        ("grade".into(), "clean".into()),
        ("flow".into(), target.flow.clone()),
        ("specHash".into(), spec_hash.into()),
    ]);
    for (name, value) in [("origin", &target.origin), ("madeOf", &target.made_of)] {
        if let Some(value) = value {
            if !java_blank(value) {
                labels.insert(name.into(), value.clone());
            }
        }
    }
    let result_summary = summary(&values);
    if [
        result_summary.min,
        result_summary.max,
        result_summary.mean,
        result_summary.m2,
    ]
    .iter()
    .any(|v| v.is_infinite())
    {
        return Err("output summary overflow".into());
    }
    let out = Episode {
        episode_id: format!(
            "c{}",
            xxh64_hex(format!("{}|{from}|{to}|{spec_hash}", key.series_id).as_bytes())
        ),
        series: key.clone(),
        t_start: from,
        t_end: to,
        count: i32::try_from(values.len()).map_err(|_| "output count overflow")?,
        time_encoding,
        interval_micros,
        time_deltas,
        values,
        quality: None,
        summary: result_summary,
        segmentation: None,
        labels,
        bucket_id: None,
        revision: 0,
        provenance: Provenance {
            source: "opentsx-processor".into(),
            producer: format!("tsx-runner/{}", env!("CARGO_PKG_VERSION")),
            created_at,
            pipeline: Some(target.flow.clone()),
            parent_episode_id: None,
        },
    };
    out.validate(out.values.len())?;
    Ok(out)
}

fn resample(
    window: &mut Option<Window>,
    input: (&[i64], &[f64]),
    end: i64,
    every: i64,
    agg: Agg,
    max_samples: usize,
    max_output: usize,
) -> Result<(Vec<i64>, Vec<f64>), String> {
    let (times, values) = input;
    let gap = every
        .checked_mul(MAX_EMPTY_WINDOWS)
        .ok_or("resample duration overflow")?;
    let mut out_times = Vec::new();
    let mut out_values = Vec::new();
    for (&time, &value) in times.iter().zip(values) {
        let reset = match window.as_ref() {
            None => true,
            Some(w) => {
                time.checked_sub(w.start)
                    .ok_or("resample timestamp distance overflow")?
                    > gap
            }
        };
        if reset {
            let start = time
                .div_euclid(every)
                .checked_mul(every)
                .ok_or("resample grid overflow")?;
            *window = Some(Window::new(start));
        }
        let current = window.as_mut().ok_or("missing resample window")?;
        while time
            >= current
                .start
                .checked_add(every)
                .ok_or("resample window end overflow")?
        {
            emit_window(
                current,
                every,
                agg,
                max_output,
                &mut out_times,
                &mut out_values,
            )?;
        }
        if value.is_finite() {
            current.sum += value;
            if !current.sum.is_finite() {
                return Err("resample sum overflow".into());
            }
            current.count = current
                .count
                .checked_add(1)
                .ok_or("resample count overflow")?;
            if current.count > max_samples as u64 {
                return Err("resample window sample limit exceeded".into());
            }
            current.min = java_min(current.min, value);
            current.max = java_max(current.max, value);
            current.last = value;
        }
    }
    if let Some(current) = window.as_mut() {
        while end
            >= current
                .start
                .checked_add(every)
                .ok_or("resample window end overflow")?
            && end
                .checked_sub(current.start)
                .ok_or("resample progress distance overflow")?
                <= gap
        {
            emit_window(
                current,
                every,
                agg,
                max_output,
                &mut out_times,
                &mut out_values,
            )?;
        }
    }
    Ok((out_times, out_values))
}

fn emit_window(
    window: &mut Window,
    every: i64,
    agg: Agg,
    cap: usize,
    times: &mut Vec<i64>,
    values: &mut Vec<f64>,
) -> Result<(), String> {
    if times.len() >= cap {
        return Err("output sample limit exceeded".into());
    }
    times.push(window.start);
    values.push(window.value(agg));
    *window = Window::new(
        window
            .start
            .checked_add(every)
            .ok_or("resample window end overflow")?,
    );
    Ok(())
}

fn java_min(a: f64, b: f64) -> f64 {
    if a == 0.0 && b == 0.0 {
        f64::from_bits(a.to_bits() | b.to_bits())
    } else {
        a.min(b)
    }
}
fn java_max(a: f64, b: f64) -> f64 {
    if a == 0.0 && b == 0.0 {
        f64::from_bits(a.to_bits() & b.to_bits())
    } else {
        a.max(b)
    }
}
fn encode_deltas(times: &[i64]) -> Result<Vec<u8>, String> {
    let mut bytes = Vec::new();
    let mut previous = 0i64;
    for index in 1..times.len() {
        let delta = times[index]
            .checked_sub(times[index - 1])
            .ok_or("output timestamp delta overflow")?;
        if delta <= 0 {
            return Err("output timestamps must be strictly increasing".into());
        }
        let value = if index == 1 {
            delta
        } else {
            delta
                .checked_sub(previous)
                .ok_or("output delta-of-delta overflow")?
        };
        crate::model::write_long(value, &mut bytes);
        previous = delta;
    }
    Ok(bytes)
}
fn bits(value: f64) -> String {
    format!("{:016x}", value.to_bits())
}
fn float(value: &Value) -> Result<f64, String> {
    let text = value
        .as_str()
        .ok_or("state float must contain hexadecimal bits")?;
    if text.len() != 16
        || !text
            .bytes()
            .all(|c| c.is_ascii_digit() || (b'a'..=b'f').contains(&c))
    {
        return Err("invalid state float bits".into());
    }
    Ok(f64::from_bits(
        u64::from_str_radix(text, 16).map_err(|_| "invalid state float bits")?,
    ))
}
fn signed(value: &Value) -> Result<i64, String> {
    let text = value.as_str().ok_or("state integer must be decimal text")?;
    let parsed = text.parse::<i64>().map_err(|_| "invalid state integer")?;
    if parsed.to_string() != text {
        return Err("noncanonical state integer".into());
    }
    Ok(parsed)
}
fn unsigned(value: &Value) -> Result<u64, String> {
    let text = value.as_str().ok_or("state count must be decimal text")?;
    let parsed = text.parse::<u64>().map_err(|_| "invalid state count")?;
    if parsed.to_string() != text {
        return Err("noncanonical state count".into());
    }
    Ok(parsed)
}
fn exact_keys(value: &Value, fields: &[&str]) -> Result<(), String> {
    let object = object(value, "state")?;
    if object.len() != fields.len() || fields.iter().any(|key| !object.contains_key(*key)) {
        return Err("unexpected operator state fields".into());
    }
    Ok(())
}
fn check_state_size(state: &Value, max: usize) -> Result<(), String> {
    if serde_json::to_vec(state)
        .map_err(|_| "operator state serialization failed")?
        .len()
        > max
    {
        return Err("operator state byte limit exceeded".into());
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::model;

    fn limits() -> Limits {
        Limits {
            max_series: 64,
            max_samples: 100_000,
            max_output_samples: 100_000,
            max_state_bytes: 1_048_576,
        }
    }

    fn raw(values: Vec<f64>, start: i64, interval: i64) -> Episode {
        let tags = BTreeMap::from([
            ("park".into(), "p1".into()),
            ("sensor".into(), "one".into()),
        ]);
        let series = SeriesKey {
            metric: "raw.metric".into(),
            series_id: series_id("raw.metric", &tags).unwrap(),
            tags,
            unit: Some("kW".into()),
        };
        Episode {
            episode_id: format!("input-{start}"),
            series,
            t_start: start,
            t_end: start + interval * values.len() as i64,
            count: values.len() as i32,
            time_encoding: TimeEncoding::Regular,
            interval_micros: Some(interval),
            time_deltas: None,
            summary: summary(&values),
            values,
            quality: None,
            segmentation: None,
            labels: BTreeMap::new(),
            bucket_id: None,
            revision: 0,
            provenance: Provenance {
                source: "raw".into(),
                producer: "fixture".into(),
                created_at: 0,
                pipeline: None,
                parent_episode_id: None,
            },
        }
    }

    fn fragment(nodes: &[Value]) -> Value {
        let mut all = vec![json!({"id":"decode","op":"decode.avro-soe"})];
        let mut previous = "decode".to_owned();
        for node in nodes {
            let mut node = node.clone();
            node["in"] = json!([previous]);
            previous = node["id"].as_str().unwrap().into();
            all.push(node);
        }
        json!({"schema":"opentsx.dag.v2","dag":"flow-1",
            "source":{"kind":"kafscale-broker","topics":["tsx.test.episodes.v2"],"series":[raw(vec![1.0],0,1).series.series_id]},
            "nodes":all,"sinks":[{"from":previous,"schema":"opentsx.episode.v2","topic":"tsx.test.clean.v2",
                "signal":{"system":"sys","id":"plant/sensor","grade":"clean"},"origin":"MEASURED"}]})
    }

    fn compile(nodes: &[Value]) -> Pipeline {
        Pipeline::compile(&fragment(nodes), &"a".repeat(64), 123_456_789, limits()).unwrap()
    }

    fn assert_values(actual: &[f64], expected: &[f64]) {
        assert_eq!(actual.len(), expected.len());
        for (&actual, &expected) in actual.iter().zip(expected) {
            if expected.is_nan() {
                assert!(actual.is_nan());
            } else {
                assert!(
                    (actual - expected).abs() <= 1e-12 * expected.abs().max(1.0),
                    "expected {expected}, got {actual}"
                );
            }
        }
    }

    fn flattened(episodes: &[Episode]) -> (Vec<i64>, Vec<f64>) {
        (
            episodes
                .iter()
                .flat_map(|e| e.timestamps().unwrap())
                .collect(),
            episodes.iter().flat_map(|e| e.values.clone()).collect(),
        )
    }

    fn clean_cases() -> Value {
        serde_json::from_str(include_str!(
            "../../../docs/datamodel-2026/testvectors/clean-ops.v1.json"
        ))
        .unwrap()
    }

    fn t0(case: &Value) -> i64 {
        match case["t0"].as_str().unwrap() {
            "2025-10-01T00:00:00Z" => 1_759_276_800_000_000,
            "2026-10-01T00:00:00Z" => 1_790_812_800_000_000,
            "2026-10-01T00:00:40Z" => 1_790_812_840_000_000,
            other => panic!("add the shared fixture timestamp {other}"),
        }
    }

    #[test]
    fn eight_shared_clean_cases_match_whole_and_every_checkpointed_split() {
        let vectors = clean_cases();
        let mut admitted = 0;
        let mut refused = 0;
        for case in vectors["cases"].as_array().unwrap() {
            let nodes = case["nodes"].as_array().unwrap();
            if nodes
                .iter()
                .any(|n| !SUPPORTED_OPS.contains(&n["op"].as_str().unwrap()))
            {
                assert!(Pipeline::compile(&fragment(nodes), &"a".repeat(64), 0, limits()).is_err());
                refused += 1;
                continue;
            }
            admitted += 1;
            let values: Vec<_> = case["values"]
                .as_array()
                .unwrap()
                .iter()
                .map(|v| v.as_f64().unwrap_or(f64::NAN))
                .collect();
            let interval = case["intervalSeconds"].as_i64().unwrap() * 1_000_000;
            let times: Vec<_> = case["expect"]["times"]
                .as_array()
                .unwrap()
                .iter()
                .map(|v| t0(case) + v.as_i64().unwrap() * 1_000_000)
                .collect();
            let expected: Vec<_> = case["expect"]["values"]
                .as_array()
                .unwrap()
                .iter()
                .map(|v| v.as_f64().unwrap_or(f64::NAN))
                .collect();
            for piece in 1..=values.len() {
                let graph = fragment(nodes);
                let mut pipeline =
                    Pipeline::compile(&graph, &"a".repeat(64), 123_456_789, limits()).unwrap();
                let mut outputs = Vec::new();
                for start in (0..values.len()).step_by(piece) {
                    let stop = (start + piece).min(values.len());
                    let state = pipeline.snapshot().unwrap();
                    pipeline =
                        Pipeline::compile(&graph, &"a".repeat(64), 123_456_789, limits()).unwrap();
                    pipeline
                        .restore(&serde_json::from_str(&state.to_string()).unwrap())
                        .unwrap();
                    outputs.extend(
                        pipeline
                            .process(&[raw(
                                values[start..stop].to_vec(),
                                t0(case) + start as i64 * interval,
                                interval,
                            )])
                            .unwrap(),
                    );
                }
                let (actual_times, actual_values) = flattened(&outputs);
                assert_eq!(actual_times, times, "{} split {piece}", case["name"]);
                assert_values(&actual_values, &expected);
                if let Some(unit) = case["expect"]["unit"].as_str() {
                    assert!(outputs
                        .iter()
                        .all(|e| e.series.unit.as_deref() == Some(unit)));
                }
            }
        }
        assert_eq!(admitted, 8);
        assert_eq!(refused, 7);
    }

    #[test]
    fn java_derived_identity_labels_summary_and_frozen_clock_match() {
        let mut pipeline = compile(&[
            json!({"id":"n1","op":"convert.unit","params":{"factor":2,"offset":1,"unit":"MW"}}),
        ]);
        let output = pipeline
            .process(&[raw(vec![1.0, f64::NAN, 2.0], 0, 1_000_000)])
            .unwrap()
            .remove(0);
        assert_eq!(output.series.series_id, "4fd16104eaf48588");
        assert_eq!(output.episode_id, "c047ffcf252287a13");
        assert_eq!(output.series.unit.as_deref(), Some("MW"));
        assert_eq!(
            output.labels,
            BTreeMap::from([
                ("flow".into(), "flow-1".into()),
                ("grade".into(), "clean".into()),
                ("origin".into(), "MEASURED".into()),
                ("specHash".into(), "a".repeat(64))
            ])
        );
        assert_values(&output.values, &[3.0, f64::NAN, 5.0]);
        assert_eq!(
            (output.summary.valid_count, output.summary.nan_count),
            (2, 1)
        );
        assert_eq!(
            (
                output.summary.min,
                output.summary.max,
                output.summary.mean,
                output.summary.m2
            ),
            (3.0, 5.0, 4.0, 2.0)
        );
        assert_eq!(output.provenance.created_at, 123_456_789);
        assert_eq!(output.provenance.source, "opentsx-processor");
        assert_eq!(
            output.provenance.producer,
            format!("tsx-runner/{}", env!("CARGO_PKG_VERSION"))
        );
        assert_eq!(pipeline.sink_name(), "plant-sensor");
        assert_eq!(pipeline.source_topic(), "tsx.test.episodes.v2");
        assert_eq!(pipeline.sink_topic(), Some("tsx.test.clean.v2"));
    }

    #[test]
    fn open_resample_tail_survives_restore_and_is_not_flushed_at_batch_end() {
        let nodes = [json!({"id":"r","op":"resample","params":{"every":"PT1M","agg":"MEAN"}})];
        let mut pipeline = compile(&nodes);
        assert!(pipeline
            .process(&[raw(vec![1.0, 3.0], 0, 20_000_000)])
            .unwrap()
            .is_empty());
        let saved = pipeline.snapshot().unwrap();
        let id = raw(vec![0.0], 0, 1).series.series_id;
        assert_eq!(saved["series"][&id]["window"]["count"], "2");
        assert_eq!(saved["series"][&id]["window"]["sum"], bits(4.0));
        let mut restored = compile(&nodes);
        restored.restore(&saved).unwrap();
        let out = restored
            .process(&[raw(vec![5.0, 7.0], 40_000_000, 20_000_000)])
            .unwrap();
        assert_eq!(flattened(&out).0, vec![0]);
        assert_values(&flattened(&out).1, &[3.0]);
        assert_eq!(
            restored.snapshot().unwrap()["series"][&id]["window"]["count"],
            "1"
        );
    }

    #[test]
    fn overlap_is_skipped_and_state_restores_exactly() {
        let nodes = [json!({"id":"u","op":"convert.unit","params":{}})];
        let input = raw(vec![-0.0, f64::NAN, 2.0], 0, 1_000_000);
        let mut pipeline = compile(&nodes);
        let first = pipeline.process(std::slice::from_ref(&input)).unwrap();
        let saved = pipeline.snapshot().unwrap();
        let mut restored = compile(&nodes);
        restored.restore(&saved).unwrap();
        assert!(restored.process(&[input]).unwrap().is_empty());
        assert_eq!(restored.snapshot().unwrap()["skippedBlocks"], "1");
        assert_eq!(first[0].values[0].to_bits(), 0.0f64.to_bits());
    }

    fn colliding_sources(start: i64, unit: &str, resample: bool) -> (Value, Episode, Episode) {
        let first = raw(vec![1.0], 0, 1_000_000);
        let mut second = raw(vec![2.0], start, 1_000_000);
        second.series.metric = "second.metric".into();
        second.series.series_id = series_id(&second.series.metric, &second.series.tags).unwrap();
        second.series.unit = Some(unit.into());
        let nodes = if resample {
            vec![json!({"id":"r","op":"resample","params":{"every":60}})]
        } else {
            vec![json!({"id":"u","op":"convert.unit","params":{"unit":"MW"}})]
        };
        let mut graph = fragment(&nodes);
        graph["source"]["series"] = json!([first.series.series_id, second.series.series_id]);
        graph["sinks"][0]["signal"]["metric"] = json!("one.target.metric");
        (graph, first, second)
    }

    #[test]
    fn derived_series_rejects_distinct_sources_before_returning_conflicting_packets() {
        for (start, unit) in [(0, "kW"), (2_000_000, "kW"), (2_000_000, "V")] {
            let (graph, first, second) = colliding_sources(start, unit, false);
            let mut pipeline = Pipeline::compile(&graph, &"a".repeat(64), 0, limits()).unwrap();
            let before = pipeline.snapshot().unwrap();
            assert_eq!(
                pipeline.process(&[first, second]).unwrap_err(),
                "distinct source series map to one derived series"
            );
            assert_eq!(pipeline.snapshot().unwrap(), before);
        }
    }

    #[test]
    fn derived_owner_survives_restore_and_reserves_an_unfinished_window() {
        for resample in [false, true] {
            let (graph, first, second) = colliding_sources(2_000_000, "V", resample);
            let raw_id = first.series.series_id.clone();
            let mut pipeline = Pipeline::compile(&graph, &"a".repeat(64), 0, limits()).unwrap();
            let output = pipeline.process(&[first]).unwrap();
            assert_eq!(output.is_empty(), resample);
            let state = pipeline.snapshot().unwrap();
            let owners = state["outputOwners"].as_object().unwrap();
            assert_eq!(owners.len(), 1);
            assert_eq!(owners.values().next().unwrap(), &json!(raw_id));
            for restored in [false, true] {
                let mut candidate = pipeline.clone();
                if restored {
                    candidate = Pipeline::compile(&graph, &"a".repeat(64), 0, limits()).unwrap();
                    candidate
                        .restore(&serde_json::from_str(&state.to_string()).unwrap())
                        .unwrap();
                }
                assert_eq!(
                    candidate
                        .process(std::slice::from_ref(&second))
                        .unwrap_err(),
                    "distinct source series map to one derived series"
                );
                assert_eq!(candidate.snapshot().unwrap(), state);
            }
        }
    }

    #[test]
    fn restore_rejects_falsified_or_colliding_derived_ownership_without_state_changes() {
        let (graph, first, second) = colliding_sources(2_000_000, "V", false);
        let mut pipeline = Pipeline::compile(&graph, &"a".repeat(64), 0, limits()).unwrap();
        pipeline.process(&[first]).unwrap();
        let state = pipeline.snapshot().unwrap();
        let derived_id = state["outputOwners"]
            .as_object()
            .unwrap()
            .keys()
            .next()
            .unwrap()
            .clone();
        let mut invalid_states = Vec::new();
        let mut missing = state.clone();
        missing.as_object_mut().unwrap().remove("outputOwners");
        invalid_states.push(missing);
        for owners in [
            json!({}),
            json!({derived_id.clone():second.series.series_id}),
            json!({derived_id.clone():1}),
            json!({"0000000000000000":"0000000000000000"}),
        ] {
            let mut invalid = state.clone();
            invalid["outputOwners"] = owners;
            invalid_states.push(invalid);
        }
        let mut other = Pipeline::compile(&graph, &"a".repeat(64), 0, limits()).unwrap();
        other.process(std::slice::from_ref(&second)).unwrap();
        let mut collision = state.clone();
        collision["series"][&second.series.series_id] =
            other.snapshot().unwrap()["series"][&second.series.series_id].clone();
        invalid_states.push(collision);
        for invalid in invalid_states {
            assert!(pipeline.restore(&invalid).is_err());
            assert_eq!(pipeline.snapshot().unwrap(), state);
        }
        let mut changed_unit = raw(vec![3.0], 2_000_000, 1_000_000);
        changed_unit.series.unit = Some("V".into());
        assert_eq!(
            pipeline.process(&[changed_unit]).unwrap_err(),
            "source series metadata changed"
        );
        assert_eq!(pipeline.snapshot().unwrap(), state);
    }

    #[test]
    fn caps_overflow_and_invalid_inputs_do_not_advance_candidate_state() {
        let graph =
            fragment(&[json!({"id":"r","op":"resample","params":{"every":1,"agg":"MEAN"}})]);
        let mut small = limits();
        small.max_output_samples = 1;
        let mut pipeline = Pipeline::compile(&graph, &"a".repeat(64), 0, small).unwrap();
        let before = pipeline.snapshot().unwrap();
        assert!(pipeline
            .process(&[raw(vec![1.0, 2.0, 3.0], 0, 1_000_000)])
            .unwrap_err()
            .contains("output"));
        assert_eq!(pipeline.snapshot().unwrap(), before);
        let nodes = [json!({"id":"u","op":"convert.unit","params":{"factor":1e308}})];
        let mut pipeline = compile(&nodes);
        let before = pipeline.snapshot().unwrap();
        assert!(pipeline.process(&[raw(vec![10.0], 0, 1)]).is_err());
        assert_eq!(pipeline.snapshot().unwrap(), before);
        let mut pipeline = compile(&[json!({"id":"u","op":"convert.unit","params":{}})]);
        for kind in 0..3 {
            let mut episode = raw(vec![1.0], 0, 1);
            if kind == 0 {
                episode.revision = 1;
            }
            if kind == 1 {
                episode.quality = Some(vec![0]);
            }
            if kind == 2 {
                episode.values[0] = f64::INFINITY;
            }
            assert!(pipeline.process(&[episode]).is_err());
        }
        assert!(pipeline.snapshot().unwrap()["series"]
            .as_object()
            .unwrap()
            .is_empty());
    }

    #[test]
    fn checkpoint_rejects_changed_plan_clock_abi_and_malformed_lossless_bits() {
        let nodes = [json!({"id":"r","op":"resample","params":{"every":60}})];
        let mut pipeline = compile(&nodes);
        pipeline.process(&[raw(vec![1.0], 0, 1_000_000)]).unwrap();
        let state = pipeline.snapshot().unwrap();
        for field in ["schema", "specHash", "createdAt"] {
            let mut invalid = state.clone();
            invalid[field] = json!("wrong");
            assert!(pipeline.restore(&invalid).is_err());
            assert_eq!(pipeline.snapshot().unwrap(), state);
        }
        let id = raw(vec![1.0], 0, 1).series.series_id;
        let mut invalid = state.clone();
        invalid["series"][&id]["window"]["sum"] = json!("NaN");
        assert!(pipeline.restore(&invalid).is_err());
        let mut invalid = state.clone();
        invalid["series"][&id]["window"]["count"] = json!("01");
        assert!(pipeline.restore(&invalid).is_err());
        let mut changed = compile(&[json!({"id":"r","op":"resample","params":{"every":30}})]);
        assert!(changed.restore(&state).is_err());
        let mut negative_zero =
            compile(&[json!({"id":"u","op":"convert.unit","params":{"factor":-0.0}})]);
        let positive_zero =
            compile(&[json!({"id":"u","op":"convert.unit","params":{"factor":0.0}})]);
        assert!(negative_zero
            .restore(&positive_zero.snapshot().unwrap())
            .is_err());
    }

    #[test]
    fn input_series_and_serialized_state_caps_fail_without_state_changes() {
        let graph = fragment(&[json!({"id":"u","op":"convert.unit","params":{}})]);
        let mut small = limits();
        small.max_samples = 1;
        let mut pipeline = Pipeline::compile(&graph, &"a".repeat(64), 0, small).unwrap();
        let before = pipeline.snapshot().unwrap();
        assert!(pipeline.process(&[raw(vec![1.0, 2.0], 0, 1)]).is_err());
        assert_eq!(pipeline.snapshot().unwrap(), before);
        let size = serde_json::to_vec(
            &Pipeline::compile(&graph, &"a".repeat(64), 0, limits())
                .unwrap()
                .snapshot()
                .unwrap(),
        )
        .unwrap()
        .len();
        let mut small = limits();
        small.max_state_bytes = size + 1;
        let mut pipeline = Pipeline::compile(&graph, &"a".repeat(64), 0, small).unwrap();
        let before = pipeline.snapshot().unwrap();
        assert!(pipeline
            .process(&[raw(vec![1.0], 0, 1)])
            .unwrap_err()
            .contains("state byte"));
        assert_eq!(pipeline.snapshot().unwrap(), before);
        let mut graph = fragment(&[]);
        graph["source"]["series"] = json!([]);
        let mut small = limits();
        small.max_series = 1;
        let mut pipeline = Pipeline::compile(&graph, &"a".repeat(64), 0, small).unwrap();
        let mut other = raw(vec![2.0], 0, 1);
        other.series.metric = "other.metric".into();
        other.series.series_id = series_id(&other.series.metric, &other.series.tags).unwrap();
        assert!(pipeline
            .process(&[raw(vec![1.0], 0, 1), other])
            .unwrap_err()
            .contains("series limit"));
    }

    #[test]
    fn graph_and_parameter_admission_refuse_coercion_and_unsupported_shapes() {
        let cases = [
            json!({"id":"n","op":"dq.checks","params":{"checks":"PLAUSIBLE_RANGE"}}),
            json!({"id":"n","op":"dq.checks","params":{"min":"0"}}),
            json!({"id":"n","op":"dq.checks","params":{"min":5,"max":4}}),
            json!({"id":"n","op":"convert.unit","params":{"unit":4}}),
            json!({"id":"n","op":"convert.unit","params":{"factor":"1"}}),
            json!({"id":"n","op":"resample","params":{"every":"PT0S"}}),
            json!({"id":"n","op":"resample","params":{"every":1000000000000i64}}),
            json!({"id":"n","op":"resample","params":{"every":1,"agg":"SUM"}}),
            json!({"id":"n","op":"feature.lag","params":{"steps":1}}),
        ];
        for node in cases {
            assert!(Pipeline::compile(&fragment(&[node]), &"a".repeat(64), 0, limits()).is_err());
        }
        let mut graph = fragment(&[json!({"id":"n","op":"convert.unit","params":{}})]);
        graph["nodes"][1]["in"] = json!(["n"]);
        assert!(Pipeline::compile(&graph, &"a".repeat(64), 0, limits()).is_err());
        let mut graph = fragment(&[]);
        graph["nodes"]
            .as_array_mut()
            .unwrap()
            .push(json!({"id":"unused","op":"convert.unit","in":["decode"]}));
        assert!(Pipeline::compile(&graph, &"a".repeat(64), 0, limits()).is_err());
        assert!(Pipeline::compile(
            &fragment(&[
                json!({"id":"r","op":"resample","params":{"every":1}}),
                json!({"id":"u","op":"convert.unit","params":{}})
            ]),
            &"a".repeat(64),
            0,
            limits()
        )
        .is_err());
        let mut graph = fragment(&[json!({"id":"u","op":"convert.unit","params":{}})]);
        graph["sinks"][0]["signal"]["grade"] = json!(4);
        assert!(Pipeline::compile(&graph, &"a".repeat(64), 0, limits()).is_err());
        let mut graph = fragment(&[]);
        graph["schema"] = json!("opentsx.dag.v999");
        assert!(Pipeline::compile(&graph, &"a".repeat(64), 0, limits()).is_err());
        let mut graph = fragment(&[]);
        graph["nodes"][0]["params"] = json!({"ignored":true});
        assert!(Pipeline::compile(&graph, &"a".repeat(64), 0, limits()).is_err());
    }

    #[test]
    fn pass_through_preserves_every_field_and_irregular_cleaning_preserves_the_axis() {
        let mut input = raw(vec![1.0, f64::NAN, 3.0], 10, 1);
        input.time_encoding = TimeEncoding::IrregularDelta;
        input.interval_micros = None;
        let mut deltas = Vec::new();
        model::write_long(3, &mut deltas);
        model::write_long(2, &mut deltas);
        input.time_deltas = Some(deltas);
        input.t_end = 20;
        let mut pipeline = compile(&[]);
        assert!(pipeline.is_passthrough());
        let out = pipeline.process(std::slice::from_ref(&input)).unwrap();
        assert_eq!(
            serde_json::to_value(&out[0]).unwrap(),
            serde_json::to_value(&input).unwrap()
        );
        let mut pipeline = compile(&[json!({"id":"u","op":"convert.unit","params":{"factor":2}})]);
        let out = pipeline.process(&[input]).unwrap();
        assert_eq!(out[0].timestamps().unwrap(), vec![10, 13, 18]);
        assert_eq!(out[0].t_end, 20);
        assert_values(&out[0].values, &[2.0, f64::NAN, 6.0]);
    }

    #[test]
    fn irregular_cleaning_reencodes_delta_varints_like_java() {
        let mut input = raw(vec![1.0, 2.0, 3.0], 10, 1);
        input.time_encoding = TimeEncoding::IrregularDelta;
        input.interval_micros = None;
        input.time_deltas = Some(vec![6, 0x84, 0]);
        input.t_end = 20;
        assert_eq!(input.timestamps().unwrap(), vec![10, 13, 18]);
        let mut pipeline = compile(&[json!({"id":"u","op":"convert.unit","params":{"factor":2}})]);
        let out = pipeline.process(&[input]).unwrap();
        assert_eq!(out[0].time_deltas, Some(vec![6, 4]));
    }

    #[test]
    fn resample_epoch_floor_long_gap_and_signed_zero_match_java() {
        let mut pipeline =
            compile(&[json!({"id":"r","op":"resample","params":{"every":0.000003,"agg":"MIN"}})]);
        let out = pipeline.process(&[raw(vec![3.0, 1.0], -2, 1)]).unwrap();
        assert_eq!(out[0].t_start, -3);
        assert_eq!(out[0].t_end, 0);
        assert_values(&out[0].values, &[1.0]);
        let mut pipeline =
            compile(&[json!({"id":"r","op":"resample","params":{"every":1,"agg":"MIN"}})]);
        let out = pipeline
            .process(&[raw(vec![0.0, -0.0], 0, 500_000)])
            .unwrap();
        assert_eq!(out[0].values[0].to_bits(), (-0.0f64).to_bits());
        let mut pipeline = compile(&[json!({"id":"r","op":"resample","params":{"every":1}})]);
        let out = pipeline
            .process(&[
                raw(vec![1.0], 0, 1_000_000),
                raw(vec![2.0], 20_000_000_000, 1_000_000),
            ])
            .unwrap();
        assert_eq!(flattened(&out).0, vec![0, 20_000_000_000]);
        assert_values(&flattened(&out).1, &[1.0, 2.0]);
    }
}
