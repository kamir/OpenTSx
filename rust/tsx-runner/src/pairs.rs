use crate::model::{self, Episode, Provenance, SeriesKey, TimeEncoding};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::collections::{BTreeMap, BTreeSet};

const TOL: f64 = 5e-7;

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct VectorParams {
    pub every_micros: i64,
    pub key: String,
    pub agg: String,
    pub as_of: bool,
    pub max_age_micros: i64,
    pub complete: bool,
    pub lateness_micros: i64,
}

fn fields(value: &Value, allowed: &[&str]) -> Result<(), String> {
    let object = value
        .as_object()
        .ok_or("operator parameters must be an object")?;
    if object.keys().any(|key| !allowed.contains(&key.as_str())) {
        return Err("unsupported operator parameter".into());
    }
    Ok(())
}

fn string<'a>(value: Option<&'a Value>, default: &'a str) -> Result<&'a str, String> {
    match value {
        None | Some(Value::Null) => Ok(default),
        Some(Value::String(text)) => Ok(text),
        _ => Err("operator parameter must be a string".into()),
    }
}

fn number(value: Option<&Value>, default: f64) -> Result<f64, String> {
    match value {
        None | Some(Value::Null) => Ok(default),
        Some(Value::Number(n)) => n
            .as_f64()
            .ok_or_else(|| "operator number is out of range".into()),
        Some(Value::String(text)) => {
            let text = js_trim(text);
            if text.is_empty() {
                return Ok(0.0);
            }
            for (prefix, radix) in [
                ("0x", 16),
                ("0X", 16),
                ("0b", 2),
                ("0B", 2),
                ("0o", 8),
                ("0O", 8),
            ] {
                if let Some(digits) = text.strip_prefix(prefix) {
                    return u64::from_str_radix(digits, radix)
                        .map(|n| n as f64)
                        .map_err(|_| "invalid operator number".into());
                }
            }
            text.parse::<f64>()
                .map_err(|_| "invalid operator number".into())
        }
        _ => Err("Pair parameters must be numbers or strings.".into()),
    }
}

fn text_number(value: &Value) -> String {
    match value {
        Value::String(text) => text.clone(),
        Value::Number(n) => n.as_f64().map(number_text).unwrap_or_else(|| n.to_string()),
        _ => value.to_string(),
    }
}

fn js_trim(value: &str) -> &str {
    value.trim_matches(|c: char| c.is_whitespace() || c == '\u{feff}')
}

fn number_text(value: f64) -> String {
    if value == 0.0 {
        return "0".into();
    }
    if value.is_finite() && (value.abs() < 1e-6 || value.abs() >= 1e21) {
        let raw = format!("{value:e}");
        if let Some((mantissa, exponent)) = raw.split_once('e') {
            return format!(
                "{mantissa}e{}{exponent}",
                if exponent.starts_with('-') { "" } else { "+" }
            );
        }
    }
    value.to_string()
}

fn duration_micros(value: &Value) -> Result<i64, String> {
    let seconds = if let Some(text) = value.as_str() {
        if let Some(body) = text.strip_prefix('P') {
            let mut total = 0.0;
            let mut digits = String::new();
            let mut time = false;
            let mut rank = 0;
            let mut terms = 0;
            for c in body.chars() {
                if c == 'T' && !time && digits.is_empty() {
                    time = true;
                    continue;
                }
                if c.is_ascii_digit() || c == '.' {
                    digits.push(c);
                    continue;
                }
                let (next, scale) = match (time, c) {
                    (false, 'D') => (1, 86_400.0),
                    (true, 'H') => (2, 3600.0),
                    (true, 'M') => (3, 60.0),
                    (true, 'S') => (4, 1.0),
                    _ => return Err("invalid operator duration".into()),
                };
                if next <= rank || digits.is_empty() {
                    return Err("invalid operator duration".into());
                }
                let amount = digits
                    .parse::<f64>()
                    .map_err(|_| "invalid operator duration")?;
                if c != 'S' && digits.contains('.') {
                    return Err("invalid operator duration".into());
                }
                total += amount * scale;
                digits.clear();
                rank = next;
                terms += 1;
            }
            if !digits.is_empty() || terms == 0 || body.ends_with('T') {
                return Err("invalid operator duration".into());
            }
            total
        } else {
            number(Some(value), 0.0)?
        }
    } else {
        number(Some(value), 0.0)?
    };
    let micros = seconds * 1e6;
    if !micros.is_finite() || micros < 0.0 || micros >= i64::MAX as f64 {
        return Err("operator duration is outside the microsecond range".into());
    }
    Ok(micros.round() as i64)
}

fn aggregate(agg: &str) -> Result<(), String> {
    if !["MEAN", "LAST", "FIRST", "MIN", "MAX", "SUM", "COUNT"].contains(&agg) {
        return Err(format!("Aggregate \"{agg}\" is unknown."));
    }
    Ok(())
}

impl VectorParams {
    pub fn parse(value: &Value) -> Result<Self, String> {
        fields(
            value,
            &[
                "every", "key", "agg", "fill", "maxAge", "lateness", "emit", "names",
            ],
        )?;
        let every_micros = duration_micros(
            value
                .get("every")
                .ok_or("the clock \"every\" must be greater than 0")?,
        )?;
        if every_micros <= 0 {
            return Err("the clock \"every\" must be greater than 0".into());
        }
        let key = string(value.get("key"), "series")?.trim().to_owned();
        if key.is_empty() {
            return Err("key must be series, all or a tag name".into());
        }
        let agg = string(value.get("agg"), "MEAN")?.to_owned();
        aggregate(&agg)?;
        let fill = string(value.get("fill"), "none")?;
        if !["none", "as-of"].contains(&fill) {
            return Err("vector fill must be none or as-of".into());
        }
        let emit = string(value.get("emit"), "any")?;
        if !["any", "complete"].contains(&emit) {
            return Err("vector emit must be any or complete".into());
        }
        Ok(Self {
            every_micros,
            key,
            agg,
            as_of: fill == "as-of",
            max_age_micros: value
                .get("maxAge")
                .map(duration_micros)
                .transpose()?
                .unwrap_or(every_micros),
            complete: emit == "complete",
            lateness_micros: value
                .get("lateness")
                .map(duration_micros)
                .transpose()?
                .unwrap_or(0),
        })
    }
}

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct VectorPoint {
    pub key: BTreeMap<String, String>,
    pub from_micros: i64,
    pub to_micros: i64,
    pub components: Vec<String>,
    pub values: Vec<Option<f64>>,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct Cell {
    n: u64,
    sum: f64,
    min: f64,
    max: f64,
    first: f64,
    last: f64,
}

impl Cell {
    fn new(value: f64) -> Self {
        Self {
            n: 1,
            sum: value,
            min: value,
            max: value,
            first: value,
            last: value,
        }
    }
    fn add(&mut self, value: f64) -> Result<(), String> {
        self.n = self.n.checked_add(1).ok_or("aggregate count overflow")?;
        self.sum += value;
        if !self.sum.is_finite() {
            return Err("aggregate sum overflow".into());
        }
        self.min = if self.min == 0.0 && value == 0.0 {
            f64::from_bits(self.min.to_bits() | value.to_bits())
        } else {
            self.min.min(value)
        };
        self.max = if self.max == 0.0 && value == 0.0 {
            f64::from_bits(self.max.to_bits() & value.to_bits())
        } else {
            self.max.max(value)
        };
        self.last = value;
        Ok(())
    }
    fn value(&self, agg: &str) -> f64 {
        match agg {
            "LAST" => self.last,
            "FIRST" => self.first,
            "MIN" => self.min,
            "MAX" => self.max,
            "SUM" => self.sum,
            "COUNT" => self.n as f64,
            _ => self.sum / self.n as f64,
        }
    }
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct VectorSeries {
    metric: String,
    tags: BTreeMap<String, String>,
    progress: i64,
    cells: BTreeMap<i64, Cell>,
    last: Option<(i64, f64)>,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct VectorStream {
    schema: String,
    inputs: Vec<(String, BTreeSet<String>)>,
    params: VectorParams,
    max_samples: usize,
    series: BTreeMap<String, VectorSeries>,
    next: BTreeMap<String, i64>,
    skipped: u64,
}

struct Layout {
    key: BTreeMap<String, String>,
    slots: BTreeMap<String, String>,
}

fn slot(input: &str, id: &str) -> String {
    format!("{}:{input}{id}", input.len())
}

fn series_name(metric: &str) -> String {
    let leaf = metric.rsplit('.').next().unwrap_or("");
    let mut out = String::new();
    let mut underscore = false;
    for c in leaf.chars().flat_map(char::to_lowercase) {
        if c.is_ascii_alphanumeric() {
            if underscore && !out.is_empty() {
                out.push('_');
            }
            out.push(c);
            underscore = false;
        } else {
            underscore = true;
        }
    }
    if out.is_empty() {
        "value".into()
    } else {
        out
    }
}

impl VectorStream {
    pub fn new(
        inputs: Vec<(String, BTreeSet<String>)>,
        params: VectorParams,
        max_samples: usize,
    ) -> Result<Self, String> {
        if inputs.is_empty() || inputs.iter().any(|(n, s)| n.is_empty() || s.is_empty()) {
            return Err(
                "a vector that keeps running needs the ids of the series of every input".into(),
            );
        }
        if let Some(clash) = input_name_clash(inputs.iter().map(|(n, _)| n)) {
            return Err(clash);
        }
        if max_samples == 0
            || params.every_micros <= 0
            || params.max_age_micros < 0
            || params.lateness_micros < 0
        {
            return Err("invalid vector bounds".into());
        }
        aggregate(&params.agg)?;
        Ok(Self {
            schema: "opentsx.rust.vector-state.v1".into(),
            inputs,
            params,
            max_samples,
            series: BTreeMap::new(),
            next: BTreeMap::new(),
            skipped: 0,
        })
    }

    #[allow(clippy::too_many_arguments)]
    pub fn add(
        &mut self,
        input: &str,
        id: &str,
        metric: &str,
        tags: &BTreeMap<String, String>,
        times: &[i64],
        values: &[f64],
        end: i64,
    ) -> Result<Vec<VectorPoint>, String> {
        let mut next = self.clone();
        let out = next.add_inner(input, id, metric, tags, times, values, end)?;
        *self = next;
        Ok(out)
    }

    #[allow(clippy::too_many_arguments)]
    fn add_inner(
        &mut self,
        input: &str,
        id: &str,
        metric: &str,
        tags: &BTreeMap<String, String>,
        times: &[i64],
        values: &[f64],
        end: i64,
    ) -> Result<Vec<VectorPoint>, String> {
        let expected = self
            .inputs
            .iter()
            .find(|(n, _)| n == input)
            .ok_or("unknown vector input")?;
        if !expected.1.contains(id) {
            return Ok(Vec::new());
        }
        if times.len() != values.len()
            || times.windows(2).any(|w| w[0] >= w[1])
            || times.last().is_some_and(|t| *t >= end)
        {
            return Err("invalid vector sample block".into());
        }
        let series = self
            .series
            .entry(slot(input, id))
            .or_insert_with(|| VectorSeries {
                metric: metric.into(),
                tags: tags.clone(),
                progress: i64::MIN,
                cells: BTreeMap::new(),
                last: None,
            });
        if series.metric != metric || &series.tags != tags {
            return Err("vector series metadata changed".into());
        }
        if times.first().is_some_and(|t| *t < series.progress) {
            self.skipped = self
                .skipped
                .checked_add(1)
                .ok_or("vector skipped count overflow")?;
            return Ok(Vec::new());
        }
        for (&time, &value) in times.iter().zip(values) {
            if !value.is_finite() {
                continue;
            }
            let tick = time.div_euclid(self.params.every_micros);
            if let Some(cell) = series.cells.get_mut(&tick) {
                cell.add(value)?;
            } else {
                series.cells.insert(tick, Cell::new(value));
            }
        }
        series.progress = series.progress.max(end);
        if self.series.values().map(|s| s.cells.len()).sum::<usize>() > self.max_samples {
            return Err("vector retained sample limit exceeded".into());
        }
        self.emit(false)
    }

    fn layout(&self) -> Result<(Vec<String>, BTreeMap<String, Layout>), String> {
        let key_by = &self.params.key;
        if key_by != "series"
            && key_by != "all"
            && !self.series.values().any(|s| s.tags.contains_key(key_by))
        {
            return Err(format!(
                "no series carries the tag \"{key_by}\"; use series, all or a tag of the series"
            ));
        }
        let key_text = |id: &str, s: &VectorSeries| {
            if key_by == "series" {
                id.to_owned()
            } else if key_by == "all" {
                String::new()
            } else {
                s.tags.get(key_by).cloned().unwrap_or_default()
            }
        };
        let mut components = Vec::new();
        let mut keys: BTreeMap<String, Layout> = BTreeMap::new();
        for (input, ids) in &self.inputs {
            let mut per_key: BTreeMap<String, usize> = BTreeMap::new();
            for id in ids {
                let s = self
                    .series
                    .get(&slot(input, id))
                    .ok_or("vector layout is missing a series")?;
                *per_key.entry(key_text(id, s)).or_default() += 1;
            }
            let several = per_key.values().any(|n| *n > 1);
            let mut of_input = BTreeSet::new();
            for id in ids {
                let name = slot(input, id);
                let s = self
                    .series
                    .get(&name)
                    .ok_or("vector layout is missing a series")?;
                let component = if !several {
                    input.clone()
                } else if self.inputs.len() == 1 {
                    series_name(&s.metric)
                } else {
                    format!("{input}.{}", series_name(&s.metric))
                };
                of_input.insert(component.clone());
                let key_text = key_text(id, s);
                let mut key = if key_by == "series" {
                    s.tags.clone()
                } else {
                    BTreeMap::new()
                };
                if key_by == "series" {
                    key.insert("series".into(), id.clone());
                } else if key_by != "all" {
                    key.insert(
                        key_by.clone(),
                        s.tags.get(key_by).cloned().unwrap_or_default(),
                    );
                }
                let layout = keys.entry(key_text).or_insert_with(|| Layout {
                    key,
                    slots: BTreeMap::new(),
                });
                if let Some(other) = layout.slots.insert(component.clone(), name.clone()) {
                    if other != name {
                        return Err(format!("the series {other} and {id} both give the component \"{component}\" of one vector; choose a key that tells them apart"));
                    }
                }
            }
            for component in of_input {
                if !components.contains(&component) {
                    components.push(component);
                }
            }
        }
        Ok((components, keys))
    }

    fn emit(&mut self, everything: bool) -> Result<Vec<VectorPoint>, String> {
        if self.inputs.iter().any(|(input, ids)| {
            ids.iter()
                .any(|id| !self.series.contains_key(&slot(input, id)))
        }) {
            return Ok(Vec::new());
        }
        let (components, keys) = self.layout()?;
        let limit = if everything {
            i64::MAX
        } else {
            self.series
                .values()
                .map(|s| s.progress)
                .min()
                .ok_or("vector has no series")?
                .checked_sub(self.params.lateness_micros)
                .ok_or("vector watermark overflow")?
        };
        let mut out = Vec::new();
        for (text, key) in keys {
            let mut lo = i64::MAX;
            let mut hi = i64::MIN;
            for id in key.slots.values() {
                let s = self.series.get(id).ok_or("vector layout series missing")?;
                if let Some((t, _)) = s.cells.first_key_value() {
                    lo = lo.min(*t);
                }
                if let Some((t, _)) = s.cells.last_key_value() {
                    hi = hi.max(*t);
                }
            }
            if hi < lo {
                continue;
            }
            let from = self.next.get(&text).copied().unwrap_or(lo);
            let to = if everything {
                hi
            } else {
                hi.min(
                    limit
                        .div_euclid(self.params.every_micros)
                        .checked_sub(1)
                        .ok_or("vector clock overflow")?,
                )
            };
            if to < from {
                continue;
            }
            let count = to
                .checked_sub(from)
                .and_then(|n| n.checked_add(1))
                .ok_or("vector tick range overflow")?;
            if count as u64 > self.max_samples as u64 {
                return Err("vector tick limit exceeded".into());
            }
            for tick in from..=to {
                let mut values = Vec::with_capacity(components.len());
                for component in &components {
                    let value = if let Some(id) = key.slots.get(component) {
                        let s = self.series.get(id).ok_or("vector series missing")?;
                        if let Some(cell) = s.cells.get(&tick) {
                            Some(cell.value(&self.params.agg))
                        } else if self.params.as_of {
                            s.last
                                .filter(|(t, _)| {
                                    *t < tick
                                        && tick.saturating_sub(*t)
                                            <= self
                                                .params
                                                .max_age_micros
                                                .div_euclid(self.params.every_micros)
                                })
                                .map(|(_, v)| v)
                        } else {
                            None
                        }
                    } else {
                        None
                    };
                    values.push(value);
                }
                let present = values.iter().filter(|v| v.is_some()).count();
                if if self.params.complete {
                    present == components.len()
                } else {
                    present > 0
                } {
                    if out.len() >= self.max_samples {
                        return Err("vector output limit exceeded".into());
                    }
                    out.push(VectorPoint {
                        key: key.key.clone(),
                        from_micros: tick
                            .checked_mul(self.params.every_micros)
                            .ok_or("vector timestamp overflow")?,
                        to_micros: tick
                            .checked_add(1)
                            .and_then(|t| t.checked_mul(self.params.every_micros))
                            .ok_or("vector timestamp overflow")?,
                        components: components.clone(),
                        values,
                    });
                }
                for id in key.slots.values().collect::<BTreeSet<_>>() {
                    let s = self.series.get_mut(id).ok_or("vector series missing")?;
                    if let Some(cell) = s.cells.remove(&tick) {
                        s.last = Some((tick, cell.value(&self.params.agg)));
                    }
                }
            }
            self.next
                .insert(text, to.checked_add(1).ok_or("vector tick overflow")?);
        }
        Ok(out)
    }

    pub fn flush(&mut self) -> Result<Vec<VectorPoint>, String> {
        let mut next = self.clone();
        let out = next.emit(true)?;
        *self = next;
        Ok(out)
    }
    pub fn snapshot(&self) -> Result<Value, String> {
        serde_json::to_value(self).map_err(|e| format!("cannot encode vector state: {e}"))
    }

    pub fn retained_series_by_input(&self) -> Result<BTreeMap<String, BTreeSet<String>>, String> {
        let mut known = BTreeSet::new();
        let mut result = BTreeMap::new();
        for (input, ids) in &self.inputs {
            let mut retained = BTreeSet::new();
            for id in ids {
                let name = slot(input, id);
                known.insert(name.clone());
                if self.series.contains_key(&name) {
                    retained.insert(id.clone());
                }
            }
            result.insert(input.clone(), retained);
        }
        if self.series.keys().any(|key| !known.contains(key)) {
            return Err("vector state contains an unassigned input series".into());
        }
        Ok(result)
    }

    pub fn possible_output_series(&self) -> Result<BTreeSet<String>, String> {
        if self.inputs.iter().any(|(input, ids)| {
            ids.iter()
                .any(|id| !self.series.contains_key(&slot(input, id)))
        }) {
            return Ok(BTreeSet::new());
        }
        let (_, layout) = self.layout()?;
        Ok(layout
            .values()
            .map(|group| model::xxh64_hex(key_text(&group.key).as_bytes()))
            .collect())
    }

    pub fn validate_series_identity(&self) -> Result<(), String> {
        for (input, ids) in &self.inputs {
            for id in ids {
                if let Some(series) = self.series.get(&slot(input, id)) {
                    if model::series_id(&series.metric, &series.tags)? != *id {
                        return Err("vector state series identity differs from its metadata".into());
                    }
                }
            }
        }
        Ok(())
    }

    pub fn restore(&mut self, state: &Value) -> Result<(), String> {
        let saved: Self = serde_json::from_value(state.clone())
            .map_err(|e| format!("invalid vector state: {e}"))?;
        if saved.schema != self.schema
            || saved.inputs != self.inputs
            || saved.params != self.params
            || saved.max_samples != self.max_samples
        {
            return Err("vector state context differs".into());
        }
        let expected: BTreeSet<_> = self
            .inputs
            .iter()
            .flat_map(|(input, ids)| ids.iter().map(move |id| slot(input, id)))
            .collect();
        if saved.series.keys().any(|id| !expected.contains(id))
            || saved.series.values().map(|s| s.cells.len()).sum::<usize>() > self.max_samples
        {
            return Err("invalid vector retained series".into());
        }
        for s in saved.series.values() {
            if s.cells.values().any(|c| {
                c.n == 0
                    || ![c.sum, c.min, c.max, c.first, c.last]
                        .iter()
                        .all(|v| v.is_finite())
                    || c.min > c.max
            }) || s.last.is_some_and(|(_, v)| !v.is_finite())
            {
                return Err("invalid vector aggregate state".into());
            }
        }
        if !saved.next.is_empty() {
            let (_, keys) = saved.layout()?;
            if saved.next.keys().any(|key| !keys.contains_key(key)) {
                return Err("invalid vector emitted position".into());
            }
        }
        *self = saved;
        Ok(())
    }
}

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct PairParams {
    pub pairing: String,
    pub window: Option<f64>,
    pub step: f64,
    pub every: Option<f64>,
    pub agg: String,
    pub max_age: Option<f64>,
    pub min_coverage: f64,
    pub lags: usize,
}

pub fn lags_of(value: Option<&Value>) -> Result<usize, String> {
    let invalid = || {
        format!(
            "lags \"{}\" is not a whole number of clock ticks, 0 or more.",
            value.map(text_number).unwrap_or_else(|| "10".into())
        )
    };
    let n = number(value, 10.0).map_err(|_| invalid())?;
    if !n.is_finite() || n.fract() != 0.0 || n < 0.0 {
        return Err(invalid());
    }
    if n > 1_073_741_823.0 {
        return Err("The lag range exceeds the maximum Java array length.".into());
    }
    Ok(n as usize)
}

impl PairParams {
    pub fn parse(value: &Value) -> Result<Self, String> {
        fields(
            value,
            &[
                "pairing",
                "window",
                "step",
                "every",
                "agg",
                "fill",
                "maxAge",
                "minCoverage",
                "lags",
            ],
        )?;
        let pairing = string(value.get("pairing"), "same")?.to_owned();
        if !["same", "intersect"].contains(&pairing.as_str()) {
            return Err(format!(
                "Pairing \"{pairing}\" is unknown. Use same or intersect."
            ));
        }
        let window = match value.get("window") {
            None | Some(Value::Null) => None,
            Some(Value::String(s)) if js_trim(s).is_empty() || js_trim(s) == "episode" => None,
            Some(v) => {
                let invalid = || {
                    format!("The window \"{}\" is neither episode nor a width in seconds greater than 0.",text_number(v))
                };
                let n = number(Some(v), 0.0).map_err(|_| invalid())?;
                if !n.is_finite() || n <= 0.0 {
                    return Err(invalid());
                }
                Some(n)
            }
        };
        let step = number(value.get("step"), 0.0)?;
        let step = window
            .map(|w| if step > 0.0 { step } else { w })
            .unwrap_or(0.0);
        let every = number(value.get("every"), 0.0)?;
        let every = if every > 0.0 { Some(every) } else { None };
        if !step.is_finite() || every.is_some_and(|n| !n.is_finite()) {
            return Err("The clock interval must be finite and greater than 0.".into());
        }
        let agg = string(value.get("agg"), "MEAN")?.to_owned();
        aggregate(&agg)?;
        let fill = string(value.get("fill"), "none")?;
        if !["none", "as-of"].contains(&fill) {
            return Err("pair fill must be none or as-of".into());
        }
        let max_age = if fill == "as-of" {
            let age = number(value.get("maxAge"), 0.0)?;
            if !age.is_finite() {
                return Err("pair maxAge must be finite".into());
            }
            Some(age.max(0.0))
        } else {
            None
        };
        let min_coverage = if value.get("minCoverage").is_some_and(Value::is_null) {
            0.0
        } else {
            number(value.get("minCoverage"), 0.8)?
        };
        if !min_coverage.is_finite() || !(0.0..=1.0).contains(&min_coverage) {
            return Err("The minimum coverage is a share between 0 and 1.".into());
        }
        Ok(Self {
            pairing,
            window,
            step,
            every,
            agg,
            max_age,
            min_coverage,
            lags: lags_of(value.get("lags"))?,
        })
    }

    pub fn check_limit(
        &self,
        node: &str,
        width: f64,
        every: f64,
        limit: usize,
    ) -> Result<(), String> {
        if limit == 0 || !width.is_finite() || width < 0.0 || !every.is_finite() || every <= 0.0 {
            return Err("invalid pair product limit or clock".into());
        }
        let lags = self
            .lags
            .checked_mul(2)
            .and_then(|n| n.checked_add(1))
            .ok_or("pair lag range overflow")?;
        if lags as f64 * width / every > limit as f64 {
            return Err(format!("node {node}: pair.xcorr exceeds the limit of this site ({limit} products per window)"));
        }
        Ok(())
    }
}

#[derive(Clone, Debug)]
pub struct XcorrWindow {
    pub centre: f64,
    pub max: f64,
    pub lag: f64,
    pub strength: f64,
    pub coverage: f64,
    pub quality: u8,
    pub cc: Vec<f64>,
}

fn moments(values: &[f64]) -> (f64, f64) {
    let mut n = 0;
    let mut sum = 0.0;
    for &v in values {
        if v.is_finite() {
            n += 1;
            sum += v;
        }
    }
    let mean = sum / n as f64;
    let mut ss = 0.0;
    for &v in values {
        if v.is_finite() {
            ss += (v - mean) * (v - mean);
        }
    }
    (
        mean,
        if n > 1 {
            (ss / (n - 1) as f64).sqrt()
        } else {
            f64::NAN
        },
    )
}

pub fn cross_correlation(
    a: &[f64],
    b: &[f64],
    lags: usize,
    product_limit: usize,
) -> Result<Vec<f64>, String> {
    if a.len() != b.len() {
        return Err(format!(
            "A has {} values and B {}: a window needs both alike.",
            a.len(),
            b.len()
        ));
    }
    let width = lags
        .checked_mul(2)
        .and_then(|n| n.checked_add(1))
        .ok_or("pair lag range overflow")?;
    if width
        .checked_mul(a.len().max(1))
        .is_none_or(|n| n > product_limit)
    {
        return Err("cross-correlation product limit exceeded".into());
    }
    let (ma, sa) = moments(a);
    let (mb, sb) = moments(b);
    let mut out = vec![f64::NAN; width];
    if sa <= 0.0 || sb <= 0.0 || !sa.is_finite() || !sb.is_finite() {
        return Ok(out);
    }
    let den = (a.len() as f64 - 1.0) * sa * sb;
    for (index, value) in out.iter_mut().enumerate() {
        let lag = index as i64 - lags as i64;
        let start = lag.max(0) as usize;
        let end = if lag > 0 {
            a.len()
        } else {
            a.len().saturating_sub((-lag) as usize)
        };
        let mut sum = 0.0;
        for (t, &value) in a.iter().enumerate().take(end).skip(start) {
            let av = if value.is_finite() { value - ma } else { 0.0 };
            let y = (t as i64 - lag) as usize;
            let bv = if b[y].is_finite() { b[y] - mb } else { 0.0 };
            sum += av * bv;
        }
        *value = sum / den;
    }
    Ok(out)
}

pub fn peak_of(cc: &[f64]) -> (usize, f64, f64) {
    if cc.is_empty() {
        return (0, f64::NAN, f64::NAN);
    }
    let mut hi = 0;
    let mut lo = 0;
    let mut sum = cc[0];
    for i in 1..cc.len() {
        if cc[i] > cc[hi] {
            hi = i;
        }
        if cc[i] < cc[lo] {
            lo = i;
        }
        sum += cc[i];
    }
    let index = if cc[hi].abs() < cc[lo].abs() { lo } else { hi };
    (
        index,
        cc[index],
        (cc[index].abs() - sum / cc.len() as f64) / moments(cc).1,
    )
}

fn windows(
    start: f64,
    end: f64,
    width: Option<f64>,
    step: f64,
    max_windows: usize,
) -> Result<Vec<(f64, f64, f64)>, String> {
    if !start.is_finite() || !end.is_finite() {
        return Err("The pair range must have finite bounds.".into());
    }
    let length = end - start;
    let Some(width) = width else {
        return Ok(if length > TOL {
            vec![(start, end, start + length / 2.0)]
        } else {
            Vec::new()
        });
    };
    if length < width - TOL || step <= 0.0 {
        return Ok(Vec::new());
    }
    let count = (((length - width) / step + 1e-9).floor() + 1.0).max(0.0);
    if !count.is_finite() || count > max_windows as f64 {
        return Err("pair output window limit exceeded".into());
    }
    Ok((0..count as usize)
        .map(|k| {
            let s = start + k as f64 * step;
            (s, s + width, s + width / 2.0)
        })
        .collect())
}

#[allow(clippy::too_many_arguments)]
pub fn xcorr_windows(
    a: &[f64],
    b: &[f64],
    start: f64,
    every: f64,
    params: &PairParams,
    node: &str,
    limit: usize,
) -> Result<Vec<XcorrWindow>, String> {
    if a.len() != b.len() {
        return Err(format!(
            "A has {} values and B {}: a window needs both alike.",
            a.len(),
            b.len()
        ));
    }
    params.check_limit(
        node,
        params.window.unwrap_or(a.len() as f64 * every),
        every,
        limit,
    )?;
    if (start - (start / every).round_ties_even() * every).abs() > TOL {
        return Err("The first aligned sample must start on the epoch clock.".into());
    }
    let lo = ((start - TOL) / every).ceil();
    let mut out = Vec::new();
    for (from, to, centre) in windows(
        start,
        start + a.len() as f64 * every,
        params.window,
        params.step,
        limit,
    )? {
        let j0 = (((from - TOL) / every).ceil() - lo).max(0.0) as usize;
        let j1 = (((to + TOL) / every).floor() - lo)
            .max(0.0)
            .min(a.len() as f64) as usize;
        out.push(window_correlation(
            a, b, j0, j1, centre, every, params, limit,
        )?);
    }
    Ok(out)
}

#[allow(clippy::too_many_arguments)]
fn window_correlation(
    a: &[f64],
    b: &[f64],
    j0: usize,
    j1: usize,
    centre: f64,
    every: f64,
    params: &PairParams,
    limit: usize,
) -> Result<XcorrWindow, String> {
    if j0 > j1 || j1 > a.len() {
        return Err("pair window bounds are invalid".into());
    }
    let both = (j0..j1)
        .filter(|&j| a[j].is_finite() && b[j].is_finite())
        .count();
    let coverage = if j1 > j0 {
        both as f64 / (j1 - j0) as f64
    } else {
        0.0
    };
    if j1 <= j0 || coverage < params.min_coverage - 1e-12 {
        return Ok(XcorrWindow {
            centre,
            max: f64::NAN,
            lag: f64::NAN,
            strength: f64::NAN,
            coverage,
            quality: 1,
            cc: Vec::new(),
        });
    }
    let cc = cross_correlation(&a[j0..j1], &b[j0..j1], params.lags, limit)?;
    let (index, value, strength) = peak_of(&cc);
    let finite = value.is_finite();
    Ok(XcorrWindow {
        centre,
        max: if finite { value } else { f64::NAN },
        lag: if finite {
            (params.lags as i64 - index as i64) as f64 * every
        } else {
            f64::NAN
        },
        strength: if finite { strength } else { f64::NAN },
        coverage,
        quality: 0,
        cc,
    })
}

fn interval(times: &[f64]) -> f64 {
    if times.len() < 2 {
        return 1.0;
    }
    let mut d: Vec<_> = times.windows(2).map(|w| w[1] - w[0]).collect();
    d.sort_by(f64::total_cmp);
    let middle = d[d.len() / 2];
    if middle > 0.0 {
        middle
    } else {
        1.0
    }
}

fn cells(times: &[f64], values: &[f64], every: f64) -> Result<BTreeMap<i64, Cell>, String> {
    let mut out = BTreeMap::new();
    for (&time, &value) in times.iter().zip(values) {
        if !value.is_finite() {
            continue;
        }
        let scaled = (time / every).floor();
        if !scaled.is_finite() || scaled < i64::MIN as f64 || scaled >= i64::MAX as f64 {
            return Err("pair clock range exceeded".into());
        }
        let tick = scaled as i64;
        if let Some(cell) = out.get_mut(&tick) {
            Cell::add(cell, value)?;
        } else {
            out.insert(tick, Cell::new(value));
        }
    }
    Ok(out)
}

fn column(
    cells: &BTreeMap<i64, Cell>,
    lo: i64,
    n: usize,
    params: &PairParams,
    every: f64,
) -> Vec<f64> {
    let max_age = params
        .max_age
        .map(|age| (age / every).floor() as i64)
        .unwrap_or(-1);
    let mut last = None;
    let mut out = Vec::with_capacity(n);
    for j in 0..n {
        let value = cells
            .get(&(lo + j as i64))
            .map(|c| c.value(&params.agg))
            .filter(|v| v.is_finite());
        if let Some(v) = value {
            last = Some((j as i64, v));
            out.push(v);
        } else {
            out.push(
                last.filter(|(tick, _)| j as i64 - tick <= max_age)
                    .map(|(_, v)| v)
                    .unwrap_or(f64::NAN),
            );
        }
    }
    out
}

pub fn duration_tag(seconds: Option<f64>) -> String {
    let Some(seconds) = seconds else {
        return "episode".into();
    };
    if seconds == 0.0 {
        return "PT0S".into();
    }
    let places = (11.0 - seconds.abs().log10().floor()) as i32;
    let rounded = if places >= 0 {
        format!("{:.*}", places.min(30) as usize, seconds)
    } else {
        let scale = 10f64.powi(-places);
        format!("{:.0}", (seconds / scale).round_ties_even() * scale)
    };
    let rounded = if rounded.contains('.') {
        rounded.trim_end_matches('0').trim_end_matches('.')
    } else {
        &rounded
    };
    format!("PT{rounded}S")
}

fn micros(seconds: f64) -> Result<i64, String> {
    let n = seconds * 1e6;
    if !n.is_finite() || n < i64::MIN as f64 || n >= i64::MAX as f64 {
        return Err("pair timestamp overflow".into());
    }
    Ok(n.round() as i64)
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct PairStream {
    schema: String,
    node: String,
    params: PairParams,
    a_series: BTreeSet<String>,
    b_series: BTreeSet<String>,
    a_order: Vec<String>,
    within: bool,
    spec_hash: String,
    created_at: i64,
    product_limit: usize,
    max_samples: usize,
    pieces: BTreeMap<String, BTreeMap<String, Vec<Episode>>>,
    progress: BTreeMap<String, BTreeMap<String, i64>>,
}

impl PairStream {
    #[allow(clippy::too_many_arguments)]
    pub fn new(
        node: String,
        params: PairParams,
        a_series: BTreeSet<String>,
        b_series: BTreeSet<String>,
        within: bool,
        spec_hash: String,
        created_at: i64,
        product_limit: usize,
        max_samples: usize,
    ) -> Result<Self, String> {
        if node.is_empty()
            || a_series.is_empty()
            || b_series.is_empty()
            || product_limit == 0
            || max_samples == 0
            || within && a_series != b_series
        {
            return Err("invalid pair stream context".into());
        }
        if spec_hash.len() != 64
            || !spec_hash
                .bytes()
                .all(|c| c.is_ascii_digit() || (b'a'..=b'f').contains(&c))
        {
            return Err("invalid pair specHash".into());
        }
        if let (Some(width), Some(every)) = (params.window, params.every) {
            params.check_limit(&node, width, every, product_limit)?;
        }
        Ok(Self {
            schema: "opentsx.rust.xcorr-state.v1".into(),
            node,
            params,
            a_order: a_series.iter().cloned().collect(),
            a_series,
            b_series,
            within,
            spec_hash,
            created_at,
            product_limit,
            max_samples,
            pieces: BTreeMap::new(),
            progress: BTreeMap::new(),
        })
    }

    pub fn add(&mut self, input: &str, episode: &Episode) -> Result<Vec<Episode>, String> {
        let mut next = self.clone();
        let out = next.add_inner(input, episode)?;
        *self = next;
        Ok(out)
    }

    pub fn set_within_order(&mut self, order: &[String]) -> Result<(), String> {
        if !self.within
            || !self.progress.is_empty()
            || order.len() != self.a_series.len()
            || order.iter().cloned().collect::<BTreeSet<_>>() != self.a_series
        {
            return Err("within-input pairing needs the same ordered series on both sides".into());
        }
        self.a_order = order.to_vec();
        Ok(())
    }

    fn add_inner(&mut self, input: &str, e: &Episode) -> Result<Vec<Episode>, String> {
        if input != "a" && input != "b" || self.within && input != "a" {
            return Err("unknown pair input".into());
        }
        let id = &e.series.series_id;
        let own = if input == "a" {
            &self.a_series
        } else {
            &self.b_series
        };
        if !own.contains(id) {
            return Ok(Vec::new());
        }
        e.validate(self.max_samples)?;
        if self
            .progress
            .get(input)
            .and_then(|m| m.get(id))
            .is_some_and(|end| e.t_start < *end)
        {
            return Ok(Vec::new());
        }
        let other = if self.within || input == "b" {
            "a"
        } else {
            "b"
        };
        let others = if other == "a" {
            &self.a_series
        } else {
            &self.b_series
        };
        let mut out = Vec::new();
        let order: BTreeMap<_, _> = self
            .a_order
            .iter()
            .enumerate()
            .map(|(index, id)| (id, index))
            .collect();
        for other_id in others {
            if self.within && id == other_id {
                continue;
            }
            if let Some(pieces) = self.pieces.get(other).and_then(|m| m.get(other_id)) {
                for previous in pieces {
                    let first = if self.within {
                        order.get(id).ok_or("pair series order is incomplete")?
                            < order
                                .get(other_id)
                                .ok_or("pair series order is incomplete")?
                    } else {
                        input == "a"
                    };
                    let (a, b) = if first { (e, previous) } else { (previous, e) };
                    out.extend(self.pair(a, b)?);
                    if out.iter().map(|e| e.values.len()).sum::<usize>() > self.max_samples {
                        return Err("pair output sample limit exceeded".into());
                    }
                }
            }
        }
        self.pieces
            .entry(input.into())
            .or_default()
            .entry(id.clone())
            .or_default()
            .push(e.clone());
        self.progress
            .entry(input.into())
            .or_default()
            .insert(id.clone(), e.t_end);
        for (side, by_series) in &mut self.pieces {
            for (own_id, list) in by_series {
                let opposite = if self.within || side == "b" { "a" } else { "b" };
                let ids = if opposite == "a" {
                    &self.a_series
                } else {
                    &self.b_series
                };
                list.retain(|e| {
                    !ids.iter()
                        .filter(|other_id| !self.within || *other_id != own_id)
                        .all(|other_id| {
                            self.progress
                                .get(opposite)
                                .and_then(|m| m.get(other_id))
                                .is_some_and(|end| *end >= e.t_end)
                        })
                });
            }
        }
        if self
            .pieces
            .values()
            .flat_map(|m| m.values())
            .flatten()
            .map(|e| e.values.len())
            .sum::<usize>()
            > self.max_samples
        {
            return Err("pair retained sample limit exceeded".into());
        }
        Ok(out)
    }

    pub fn pair(&self, a: &Episode, b: &Episode) -> Result<Vec<Episode>, String> {
        let start = if self.params.pairing == "same" {
            if a.t_start != b.t_start || a.t_end != b.t_end {
                return Ok(Vec::new());
            }
            a.t_start
        } else {
            a.t_start.max(b.t_start)
        };
        let end = if self.params.pairing == "same" {
            a.t_end
        } else {
            a.t_end.min(b.t_end)
        };
        if end <= start {
            return Ok(Vec::new());
        }
        let start = start as f64 / 1e6;
        let end = end as f64 / 1e6;
        let ax: Vec<_> = a
            .timestamps()?
            .into_iter()
            .map(|t| t as f64 / 1e6)
            .collect();
        let bx: Vec<_> = b
            .timestamps()?
            .into_iter()
            .map(|t| t as f64 / 1e6)
            .collect();
        let every = self
            .params
            .every
            .unwrap_or_else(|| interval(&ax).max(interval(&bx)));
        let spans = windows(
            start,
            end,
            self.params.window,
            self.params.step,
            self.max_samples,
        )?;
        if spans.is_empty() {
            return Ok(Vec::new());
        }
        self.params.check_limit(
            &self.node,
            self.params.window.unwrap_or(end - start),
            every,
            self.product_limit,
        )?;
        let lo = ((start - TOL) / every).ceil();
        let to = ((end + TOL) / every).floor();
        let n = to - lo;
        if !n.is_finite()
            || n < 0.0
            || n > self.max_samples as f64
            || lo < i64::MIN as f64
            || to >= i64::MAX as f64
        {
            return Err("pair aligned sample limit exceeded".into());
        }
        let ca = column(
            &cells(&ax, &a.values, every)?,
            lo as i64,
            n as usize,
            &self.params,
            every,
        );
        let cb = column(
            &cells(&bx, &b.values, every)?,
            lo as i64,
            n as usize,
            &self.params,
            every,
        );
        let mut computed = Vec::with_capacity(spans.len());
        for (from, to, centre) in spans {
            let j0 = (((from - TOL) / every).ceil() - lo).max(0.0) as usize;
            let j1 = (((to + TOL) / every).floor() - lo).max(0.0).min(n) as usize;
            computed.push(window_correlation(
                &ca,
                &cb,
                j0,
                j1,
                centre,
                every,
                &self.params,
                self.product_limit,
            )?);
        }
        let window = duration_tag(self.params.window);
        let step = if self.params.window.is_none() {
            "episode".into()
        } else {
            duration_tag(Some(self.params.step))
        };
        let mut out = Vec::with_capacity(3);
        for (field, index) in [("max", 0), ("lag", 1), ("strength", 2)] {
            let tags = BTreeMap::from([
                ("a".into(), a.series.series_id.clone()),
                ("b".into(), b.series.series_id.clone()),
                ("window".into(), window.clone()),
                ("step".into(), step.clone()),
            ]);
            let metric = format!("pair.xcorr.{field}");
            let series_id = model::series_id(&metric, &tags)?;
            let identity = format!(
                "{series_id}|{}|{}|{}",
                a.episode_id, b.episode_id, self.spec_hash
            );
            let values: Vec<_> = computed
                .iter()
                .map(|w| match index {
                    0 => w.max,
                    1 => w.lag,
                    _ => w.strength,
                })
                .collect();
            let t_start = micros(computed[0].centre)?;
            let interval = if self.params.window.is_some() {
                Some(micros(self.params.step)?)
            } else {
                None
            };
            if interval.is_some_and(|i| i <= 0) {
                return Err("pair output interval must be positive".into());
            }
            let t_end = if let Some(interval) = interval {
                interval
                    .checked_mul(values.len() as i64)
                    .and_then(|d| t_start.checked_add(d))
                    .ok_or("pair output timestamp overflow")?
            } else {
                t_start
                    .checked_add(1)
                    .ok_or("pair output timestamp overflow")?
            };
            let e = Episode {
                episode_id: format!("p{}", model::xxh64_hex(identity.as_bytes())),
                series: SeriesKey {
                    metric,
                    tags,
                    unit: None,
                    series_id,
                },
                t_start,
                t_end,
                count: i32::try_from(values.len()).map_err(|_| "pair output count overflow")?,
                time_encoding: if interval.is_some() {
                    TimeEncoding::Regular
                } else {
                    TimeEncoding::IrregularDelta
                },
                interval_micros: interval,
                time_deltas: if interval.is_none() {
                    Some(Vec::new())
                } else {
                    None
                },
                summary: model::summary(&values),
                values,
                quality: Some(computed.iter().map(|w| w.quality).collect()),
                segmentation: None,
                labels: BTreeMap::from([
                    ("pair.a".into(), a.episode_id.clone()),
                    ("pair.b".into(), b.episode_id.clone()),
                    ("pair.method".into(), "pair.xcorr".into()),
                    ("pair.window".into(), window.clone()),
                    ("pair.step".into(), step.clone()),
                    ("pair.lags".into(), self.params.lags.to_string()),
                    ("specHash".into(), self.spec_hash.clone()),
                ]),
                bucket_id: None,
                revision: 0,
                provenance: Provenance {
                    source: "opentsx-wayang".into(),
                    producer: "opentsx-model/3.1.0".into(),
                    created_at: self.created_at,
                    pipeline: None,
                    parent_episode_id: Some(a.episode_id.clone()),
                },
            };
            e.validate(self.max_samples)?;
            out.push(e);
        }
        Ok(out)
    }

    pub fn snapshot(&self) -> Result<Value, String> {
        serde_json::to_value(self).map_err(|e| format!("cannot encode pair state: {e}"))
    }

    pub fn retained_series_by_input(&self) -> Result<BTreeMap<String, BTreeSet<String>>, String> {
        let mut result: BTreeMap<String, BTreeSet<String>> = BTreeMap::new();
        for (input, series) in &self.pieces {
            result
                .entry(input.clone())
                .or_default()
                .extend(series.keys().cloned());
        }
        for (input, series) in &self.progress {
            result
                .entry(input.clone())
                .or_default()
                .extend(series.keys().cloned());
        }
        for (input, ids) in &result {
            let expected = match input.as_str() {
                "a" => &self.a_series,
                "b" if !self.within => &self.b_series,
                _ => return Err("pair state contains an unknown input".into()),
            };
            if !ids.is_subset(expected) {
                return Err("pair state contains an unassigned input series".into());
            }
        }
        Ok(result)
    }

    pub fn possible_output_series(
        &self,
        a: &BTreeSet<String>,
        b: &BTreeSet<String>,
        cap: usize,
    ) -> Result<BTreeSet<String>, String> {
        if !a.is_subset(&self.a_series) || !b.is_subset(&self.b_series) || cap == 0 {
            return Err("invalid pair output identity context".into());
        }
        let order: BTreeMap<_, _> = self
            .a_order
            .iter()
            .enumerate()
            .map(|(index, id)| (id, index))
            .collect();
        let mut result = BTreeSet::new();
        let window = duration_tag(self.params.window);
        let step = if self.params.window.is_none() {
            "episode".into()
        } else {
            duration_tag(Some(self.params.step))
        };
        for first in a {
            for second in b {
                if self.within
                    && order.get(first).ok_or("pair series order is incomplete")?
                        >= order.get(second).ok_or("pair series order is incomplete")?
                {
                    continue;
                }
                let tags = BTreeMap::from([
                    ("a".into(), first.clone()),
                    ("b".into(), second.clone()),
                    ("window".into(), window.clone()),
                    ("step".into(), step.clone()),
                ]);
                for field in ["max", "lag", "strength"] {
                    result.insert(model::series_id(&format!("pair.xcorr.{field}"), &tags)?);
                    if result.len() > cap {
                        return Err("pair output series limit exceeded".into());
                    }
                }
            }
        }
        Ok(result)
    }

    pub fn restore(&mut self, state: &Value) -> Result<(), String> {
        let saved: Self = serde_json::from_value(state.clone())
            .map_err(|e| format!("invalid pair state: {e}"))?;
        if saved.schema != self.schema
            || saved.node != self.node
            || saved.params != self.params
            || saved.a_series != self.a_series
            || saved.b_series != self.b_series
            || saved.a_order != self.a_order
            || saved.within != self.within
            || saved.spec_hash != self.spec_hash
            || saved.created_at != self.created_at
            || saved.product_limit != self.product_limit
            || saved.max_samples != self.max_samples
        {
            return Err("pair state context differs".into());
        }
        let mut count = 0usize;
        for (input, by_series) in &saved.pieces {
            if input != "a" && input != "b" || self.within && input != "a" {
                return Err("invalid pair retained input".into());
            }
            let ids = if input == "a" {
                &self.a_series
            } else {
                &self.b_series
            };
            for (id, pieces) in by_series {
                if !ids.contains(id) {
                    return Err("invalid pair retained series".into());
                }
                let mut previous = i64::MIN;
                for e in pieces {
                    e.validate(self.max_samples)?;
                    if &e.series.series_id != id || e.t_start < previous {
                        return Err("invalid pair retained episode order".into());
                    }
                    previous = e.t_end;
                    count = count
                        .checked_add(e.values.len())
                        .ok_or("pair retained count overflow")?;
                    if saved
                        .progress
                        .get(input)
                        .and_then(|m| m.get(id))
                        .is_none_or(|end| *end < e.t_end)
                    {
                        return Err("pair retained progress is missing".into());
                    }
                }
            }
        }
        if count > self.max_samples {
            return Err("pair retained sample limit exceeded".into());
        }
        for (input, progress) in &saved.progress {
            let ids = match input.as_str() {
                "a" => &self.a_series,
                "b" if !self.within => &self.b_series,
                _ => return Err("invalid pair progress input".into()),
            };
            if progress.keys().any(|id| !ids.contains(id)) {
                return Err("invalid pair progress series".into());
            }
        }
        *self = saved;
        Ok(())
    }
}

pub fn vector_key(point: &VectorPoint) -> String {
    key_text(&point.key)
}

fn key_text(key: &BTreeMap<String, String>) -> String {
    key.iter()
        .map(|(k, v)| format!("{k}={v}"))
        .collect::<Vec<_>>()
        .join(",")
}

pub fn vector_json(
    point: &VectorPoint,
    vector: &str,
    dag: &str,
    spec_hash: &str,
) -> Result<Value, String> {
    if vector.is_empty()
        || point.from_micros >= point.to_micros
        || point.components.is_empty()
        || point.components.len() != point.values.len()
        || point.values.iter().flatten().any(|v| !v.is_finite())
    {
        return Err("invalid vector output record".into());
    }
    fn instant(micros: i64) -> Result<String, String> {
        let base = time::OffsetDateTime::from_unix_timestamp(micros.div_euclid(1_000_000))
            .map_err(|e| format!("vector timestamp is out of range: {e}"))?
            .format(&time::format_description::well_known::Rfc3339)
            .map_err(|e| format!("cannot format vector timestamp: {e}"))?;
        let fraction = micros.rem_euclid(1_000_000);
        if fraction == 0 {
            return Ok(base);
        }
        let prefix = base
            .strip_suffix('Z')
            .ok_or("vector timestamp is not UTC")?;
        Ok(if fraction % 1000 == 0 {
            format!("{prefix}.{:03}Z", fraction / 1000)
        } else {
            format!("{prefix}.{fraction:06}Z")
        })
    }
    let clock = point
        .to_micros
        .checked_sub(point.from_micros)
        .ok_or("vector clock overflow")?;
    Ok(
        json!({"schema":"opentsx.vector.v1","vector":vector,"key":point.key,"ts":instant(point.to_micros)?,
        "window":{"from":instant(point.from_micros)?,"to":instant(point.to_micros)?},"clock":format!("PT{}S",clock as f64/1e6),
        "components":point.components,"values":point.values,"missing":point.values.iter().filter(|v|v.is_none()).count(),
        "provenance":{"dag":dag,"specHash":spec_hash}}),
    )
}

pub fn input_name_clash<'a>(names: impl IntoIterator<Item = &'a String>) -> Option<String> {
    let mut seen = BTreeSet::new();
    names.into_iter().find(|n| !seen.insert(*n)).map(|n| {
        format!("two inputs of the vector are both named \"{n}\"; give every input its own name")
    })
}
