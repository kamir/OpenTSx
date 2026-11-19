use crate::model::{
    self, Episode, Provenance, Segmentation, SegmentationStrategy, SeriesKey, TimeEncoding,
};
use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::collections::{BTreeMap, VecDeque};

#[derive(Clone, Debug)]
enum Config {
    Threshold {
        above: bool,
        enter: f64,
        exit: f64,
        pre: i64,
        post: i64,
        min: i64,
        max: i64,
    },
    Extremum {
        sign: f64,
        prominence: f64,
        half_window: i64,
        distance: i64,
        pre: i64,
        post: i64,
    },
}

#[derive(Clone, Copy, Debug, Serialize, Deserialize, PartialEq, Eq)]
enum Phase {
    Idle,
    Active,
    Capped,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct Pending {
    from: u64,
    core_end: i64,
    anchor: u64,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(tag = "kind", deny_unknown_fields)]
enum Detector {
    Threshold {
        phase: Phase,
        enter: u64,
        from: u64,
        pending: Vec<Pending>,
    },
    Extremum {
        candidates: VecDeque<u64>,
    },
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct Source {
    key: SeriesKey,
    episode_id: String,
    source: String,
    labels: BTreeMap<String, String>,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct Sample {
    ts: i64,
    value_bits: u64,
    quality: u8,
    source: usize,
}

#[derive(Clone, Debug, Default, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct Buffer {
    base: u64,
    samples: Vec<Sample>,
    sources: Vec<Source>,
    any_quality: bool,
}

impl Buffer {
    fn end(&self) -> u64 {
        self.base + self.samples.len() as u64
    }
    fn at(&self, index: u64) -> &Sample {
        &self.samples[(index - self.base) as usize]
    }
    fn ts(&self, index: u64) -> i64 {
        self.at(index).ts
    }
    fn value(&self, index: u64) -> f64 {
        f64::from_bits(self.at(index).value_bits)
    }
    fn index_at(&self, timestamp: i64, from: u64) -> u64 {
        let start = (from.max(self.base) - self.base) as usize;
        self.base + (start + self.samples[start..].partition_point(|s| s.ts < timestamp)) as u64
    }
    fn append(&mut self, e: &Episode, max_samples: usize) -> Result<(), String> {
        e.validate(max_samples)?;
        let times = e.timestamps()?;
        let source = self.sources.len();
        let mut added = false;
        for (index, &ts) in times.iter().enumerate() {
            if self.samples.last().is_some_and(|last| ts <= last.ts) {
                continue;
            }
            if self.samples.len() >= max_samples {
                return Err("segmentation retained sample limit exceeded".into());
            }
            let end = self
                .base
                .checked_add(self.samples.len() as u64 + 1)
                .ok_or("segmentation index overflow")?;
            if end > i64::MAX as u64 {
                return Err("segmentation index overflow".into());
            }
            self.samples.push(Sample {
                ts,
                value_bits: e.values[index].to_bits(),
                quality: e.quality.as_ref().map_or(0, |q| q[index]),
                source,
            });
            added = true;
        }
        if added {
            self.sources.push(Source {
                key: e.series.clone(),
                episode_id: e.episode_id.clone(),
                source: e.provenance.source.clone(),
                labels: e.labels.clone(),
            });
        }
        self.any_quality |= e.quality.is_some();
        Ok(())
    }
    fn trim(&mut self, before: u64) {
        let n = (before.saturating_sub(self.base) as usize).min(self.samples.len());
        self.samples.drain(..n);
        self.base += n as u64;
        let mut indices = BTreeMap::new();
        let mut sources = Vec::new();
        for sample in &mut self.samples {
            let index = *indices.entry(sample.source).or_insert_with(|| {
                sources.push(self.sources[sample.source].clone());
                sources.len() - 1
            });
            sample.source = index;
        }
        self.sources = sources;
    }
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct State {
    buffer: Buffer,
    detector: Detector,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Checkpoint {
    schema: String,
    param_key: String,
    series: BTreeMap<String, State>,
}

#[derive(Clone, Debug)]
pub struct Segmenter {
    config: Config,
    strategy: SegmentationStrategy,
    params: BTreeMap<String, String>,
    param_key: String,
    max_series: usize,
    max_samples: usize,
    max_state_bytes: usize,
    series: BTreeMap<String, State>,
}

impl Segmenter {
    pub fn new(
        op: &str,
        params: &Value,
        max_series: usize,
        max_samples: usize,
        max_state_bytes: usize,
    ) -> Result<Self, String> {
        if [max_series, max_samples, max_state_bytes].contains(&0) {
            return Err("segmentation limits must be positive".into());
        }
        let object = params
            .as_object()
            .ok_or("segmentation parameters must be an object")?;
        let allowed = match op {
            "segment.threshold" => &[
                "direction",
                "enter",
                "exit",
                "pre",
                "post",
                "minDuration",
                "maxDuration",
            ][..],
            "segment.extremum" => {
                &["feature", "prominence", "window", "distance", "pre", "post"][..]
            }
            _ => return Err("segmentation supports threshold and extremum only".into()),
        };
        if object.keys().any(|k| !allowed.contains(&k.as_str())) {
            return Err("unsupported segmentation parameter".into());
        }
        let mut retained = BTreeMap::new();
        let mut duration = |name: &str| -> Result<i64, String> {
            let text = match params.get(name) {
                Some(value) => value
                    .as_str()
                    .ok_or_else(|| format!("{name} must be a duration"))?,
                None if ["pre", "post", "minDuration", "maxDuration"].contains(&name) => "PT0S",
                None => return Err(format!("{name} must be a duration")),
            };
            let (micros, canonical) = duration_micros(text)?;
            retained.insert(name.to_owned(), canonical);
            Ok(micros)
        };
        let pre = duration("pre")?;
        let post = duration("post")?;
        let (config, strategy, name) = if op == "segment.threshold" {
            let min = duration("minDuration")?;
            let max = duration("maxDuration")?;
            let direction = params["direction"]
                .as_str()
                .ok_or("direction must be ABOVE or BELOW")?;
            if direction != "ABOVE" && direction != "BELOW" {
                return Err("direction must be ABOVE or BELOW".into());
            }
            let enter = finite(params, "enter")?;
            let exit = finite(params, "exit")?;
            let above = direction == "ABOVE";
            if if above { exit > enter } else { exit < enter } {
                return Err("hysteresis: exit must not lie beyond enter".into());
            }
            retained.insert("direction".into(), direction.into());
            retained.insert("enter".into(), java_double(enter)?);
            retained.insert("exit".into(), java_double(exit)?);
            (
                Config::Threshold {
                    above,
                    enter,
                    exit,
                    pre,
                    post,
                    min,
                    max,
                },
                SegmentationStrategy::FeatureThreshold,
                "FEATURE_THRESHOLD",
            )
        } else {
            let window = duration("window")?;
            let distance = duration("distance")?;
            let feature = params["feature"]
                .as_str()
                .ok_or("feature must be MAX or MIN")?;
            if feature != "MAX" && feature != "MIN" {
                return Err("feature must be MAX or MIN".into());
            }
            let prominence = finite(params, "prominence")?;
            if prominence < 0.0 {
                return Err("prominence must be >= 0".into());
            }
            retained.insert("feature".into(), feature.into());
            retained.insert("prominence".into(), java_double(prominence)?);
            (
                Config::Extremum {
                    sign: if feature == "MAX" { 1.0 } else { -1.0 },
                    prominence,
                    half_window: window / 2,
                    distance,
                    pre,
                    post,
                },
                SegmentationStrategy::FeatureExtremum,
                "FEATURE_EXTREMUM",
            )
        };
        let param_key = format!(
            "{name}{{{}}}",
            retained
                .iter()
                .map(|(k, v)| format!("{k}={v}"))
                .collect::<Vec<_>>()
                .join(", ")
        );
        let out = Self {
            config,
            strategy,
            params: retained,
            param_key,
            max_series,
            max_samples,
            max_state_bytes,
            series: BTreeMap::new(),
        };
        out.snapshot()?;
        Ok(out)
    }

    fn detector(&self) -> Detector {
        match self.config {
            Config::Threshold { .. } => Detector::Threshold {
                phase: Phase::Idle,
                enter: 0,
                from: 0,
                pending: Vec::new(),
            },
            Config::Extremum { .. } => Detector::Extremum {
                candidates: VecDeque::new(),
            },
        }
    }

    pub fn process(
        &mut self,
        episodes: &[Episode],
        complete: bool,
    ) -> Result<Vec<Episode>, String> {
        let mut candidate = self.clone();
        let output = candidate.process_inner(episodes, complete)?;
        candidate.snapshot()?;
        *self = candidate;
        Ok(output)
    }

    fn process_inner(
        &mut self,
        episodes: &[Episode],
        complete: bool,
    ) -> Result<Vec<Episode>, String> {
        let mut output = Vec::new();
        for episode in episodes {
            let id = episode.series.series_id.clone();
            let detector = self.detector();
            if !self.series.contains_key(&id) && self.series.len() >= self.max_series {
                return Err("segmentation series limit exceeded".into());
            }
            let state = self.series.entry(id).or_insert_with(|| State {
                buffer: Buffer::default(),
                detector,
            });
            let before = state.buffer.end();
            state.buffer.append(episode, self.max_samples)?;
            for index in before..state.buffer.end() {
                for cut in on_sample(&self.config, &mut state.detector, &state.buffer, index)? {
                    output.push(materialize(
                        &state.buffer,
                        cut,
                        self.strategy,
                        &self.params,
                        &self.param_key,
                    )?);
                }
            }
            let earliest = earliest_needed(&self.config, &state.detector, &state.buffer)?;
            state.buffer.trim(earliest);
        }
        if complete {
            for state in self.series.values_mut() {
                for cut in flush(&self.config, &mut state.detector, &state.buffer)? {
                    output.push(materialize(
                        &state.buffer,
                        cut,
                        self.strategy,
                        &self.params,
                        &self.param_key,
                    )?);
                }
            }
            self.series.clear();
        }
        let retained = self.series.values().try_fold(0usize, |n, s| {
            n.checked_add(s.buffer.samples.len())
                .ok_or("segmentation retained sample count overflow")
        })?;
        if retained > self.max_samples {
            return Err("segmentation retained sample limit exceeded".into());
        }
        let count = output.iter().try_fold(0usize, |n, e| {
            n.checked_add(e.values.len())
                .ok_or("segmentation output sample count overflow")
        })?;
        if count > self.max_samples {
            return Err("segmentation output sample limit exceeded".into());
        }
        Ok(output)
    }

    pub fn snapshot(&self) -> Result<Value, String> {
        let state = serde_json::to_value(Checkpoint {
            schema: "opentsx.rust.segment-state.v1".into(),
            param_key: self.param_key.clone(),
            series: self.series.clone(),
        })
        .map_err(|e| format!("cannot encode segmentation checkpoint: {e}"))?;
        if serde_json::to_vec(&state)
            .map_err(|e| format!("cannot encode segmentation checkpoint: {e}"))?
            .len()
            > self.max_state_bytes
        {
            return Err("segmentation state byte limit exceeded".into());
        }
        Ok(state)
    }

    pub fn restore(&mut self, state: &Value) -> Result<(), String> {
        if serde_json::to_vec(state)
            .map_err(|e| format!("cannot encode segmentation checkpoint: {e}"))?
            .len()
            > self.max_state_bytes
        {
            return Err("segmentation state byte limit exceeded".into());
        }
        let saved: Checkpoint = serde_json::from_value(state.clone())
            .map_err(|e| format!("invalid segmentation checkpoint: {e}"))?;
        if saved.schema != "opentsx.rust.segment-state.v1" || saved.param_key != self.param_key {
            return Err("segmentation checkpoint has different version or parameters".into());
        }
        if saved.series.len() > self.max_series {
            return Err("segmentation series limit exceeded".into());
        }
        let mut count = 0usize;
        for (id, s) in &saved.series {
            count = count
                .checked_add(s.buffer.samples.len())
                .ok_or("segmentation retained sample count overflow")?;
            if count > self.max_samples {
                return Err("segmentation retained sample limit exceeded".into());
            }
            let end = s
                .buffer
                .base
                .checked_add(s.buffer.samples.len() as u64)
                .ok_or("segmentation index overflow")?;
            if end > i64::MAX as u64 {
                return Err("segmentation index overflow".into());
            }
            if s.buffer.sources.len() > s.buffer.samples.len() {
                return Err("invalid segmentation source count".into());
            }
            for source in &s.buffer.sources {
                if source.key.series_id != *id
                    || model::series_id(&source.key.metric, &source.key.tags)? != *id
                {
                    return Err("segmentation checkpoint contains another series".into());
                }
            }
            for (index, sample) in s.buffer.samples.iter().enumerate() {
                if sample.source >= s.buffer.sources.len()
                    || (index > 0 && sample.ts <= s.buffer.samples[index - 1].ts)
                    || (!s.buffer.any_quality && sample.quality != 0)
                {
                    return Err("invalid segmentation buffer sample".into());
                }
            }
            match (&self.config, &s.detector) {
                (
                    Config::Threshold { .. },
                    Detector::Threshold {
                        phase,
                        enter,
                        from,
                        pending,
                    },
                ) => {
                    if *phase != Phase::Idle
                        && (*from < s.buffer.base || enter < from || *enter >= s.buffer.end())
                    {
                        return Err("invalid threshold detector indices".into());
                    }
                    if pending.len() > s.buffer.samples.len()
                        || pending.iter().any(|p| {
                            p.from < s.buffer.base
                                || p.anchor < p.from
                                || p.anchor >= s.buffer.end()
                                || p.core_end < s.buffer.ts(p.anchor)
                                || s.buffer
                                    .samples
                                    .last()
                                    .is_none_or(|last| p.core_end > last.ts)
                        })
                    {
                        return Err("invalid threshold pending indices".into());
                    }
                }
                (Config::Extremum { .. }, Detector::Extremum { candidates }) => {
                    let mut previous = None;
                    for &candidate in candidates {
                        if candidate < s.buffer.base
                            || candidate >= s.buffer.end()
                            || previous.is_some_and(|p| candidate <= p)
                        {
                            return Err("invalid extremum candidate index".into());
                        }
                        previous = Some(candidate);
                    }
                }
                _ => return Err("segmentation checkpoint has different detector".into()),
            }
        }
        self.series = saved.series;
        Ok(())
    }
}

#[derive(Clone, Copy)]
struct Cut {
    from: u64,
    to: u64,
    anchor: u64,
}

fn add(a: i64, b: i64) -> Result<i64, String> {
    a.checked_add(b)
        .ok_or_else(|| "segmentation timestamp overflow".into())
}
fn sub(a: i64, b: i64) -> Result<i64, String> {
    a.checked_sub(b)
        .ok_or_else(|| "segmentation timestamp overflow".into())
}

fn on_sample(
    config: &Config,
    detector: &mut Detector,
    b: &Buffer,
    index: u64,
) -> Result<Vec<Cut>, String> {
    let mut out = Vec::new();
    let timestamp = b.ts(index);
    match (config, detector) {
        (
            Config::Threshold {
                above,
                enter: threshold,
                exit,
                pre,
                post,
                min,
                max,
            },
            Detector::Threshold {
                phase,
                enter,
                from,
                pending,
            },
        ) => {
            let value = b.value(index);
            if !value.is_nan() {
                let enters = if *above {
                    value >= *threshold
                } else {
                    value <= *threshold
                };
                let exits = if *above { value < *exit } else { value > *exit };
                match phase {
                    Phase::Idle if enters => {
                        *phase = Phase::Active;
                        *enter = index;
                        *from = b.index_at(sub(timestamp, *pre)?, b.base);
                    }
                    Phase::Active => {
                        let active = sub(timestamp, b.ts(*enter))?;
                        if exits {
                            if active >= *min {
                                pending.push(Pending {
                                    from: *from,
                                    core_end: timestamp,
                                    anchor: *enter,
                                });
                            }
                            *phase = Phase::Idle;
                        } else if *max > 0 && active >= *max {
                            pending.push(Pending {
                                from: *from,
                                core_end: timestamp,
                                anchor: *enter,
                            });
                            *phase = Phase::Capped;
                        }
                    }
                    Phase::Capped if exits => *phase = Phase::Idle,
                    _ => {}
                }
            }
            let mut retained = Vec::new();
            for p in pending.drain(..) {
                let end = add(p.core_end, *post)?;
                if timestamp >= end {
                    out.push(Cut {
                        from: p.from,
                        to: b.index_at(end, p.anchor).max(p.anchor + 1),
                        anchor: p.anchor,
                    });
                } else {
                    retained.push(p);
                }
            }
            *pending = retained;
        }
        (
            Config::Extremum {
                sign,
                half_window,
                distance,
                post,
                ..
            },
            Detector::Extremum { candidates },
        ) => {
            if index >= b.base + 2 {
                let center = index - 1;
                let v = signed_value(b, center, *sign);
                if v != f64::NEG_INFINITY
                    && v > signed_value(b, center - 1, *sign)
                    && v >= signed_value(b, index, *sign)
                {
                    candidates.push_back(center);
                }
            }
            let lookahead = (*post).max((*distance).max(*half_window));
            while let Some(&center) = candidates.front() {
                if timestamp < add(b.ts(center), lookahead)? {
                    break;
                }
                candidates.pop_front();
                if let Some(cut) = decide(config, b, center)? {
                    out.push(cut);
                }
            }
        }
        _ => return Err("segmentation detector does not match configuration".into()),
    }
    Ok(out)
}

fn signed_value(b: &Buffer, index: u64, sign: f64) -> f64 {
    let v = b.value(index);
    if v.is_nan() {
        f64::NEG_INFINITY
    } else {
        sign * v
    }
}

fn decide(config: &Config, b: &Buffer, center: u64) -> Result<Option<Cut>, String> {
    let Config::Extremum {
        sign,
        prominence,
        half_window,
        distance,
        pre,
        post,
    } = config
    else {
        return Err("extremum configuration is missing".into());
    };
    let timestamp = b.ts(center);
    let value = signed_value(b, center, *sign);
    let mut left = value;
    let left_edge = sub(timestamp, *half_window)?;
    for index in (b.base..center).rev() {
        if b.ts(index) < left_edge {
            break;
        }
        let v = signed_value(b, index, *sign);
        if v > value {
            break;
        }
        if v != f64::NEG_INFINITY {
            left = left.min(v);
        }
    }
    let mut right = value;
    let right_edge = add(timestamp, *half_window)?;
    for index in center + 1..b.end() {
        if b.ts(index) > right_edge {
            break;
        }
        let v = signed_value(b, index, *sign);
        if v > value {
            break;
        }
        if v != f64::NEG_INFINITY {
            right = right.min(v);
        }
    }
    if value - left.max(right) < *prominence {
        return Ok(None);
    }
    let end = add(timestamp, *distance)?;
    for index in b.index_at(sub(timestamp, *distance)?, b.base)..b.end() {
        if b.ts(index) > end {
            break;
        }
        let v = signed_value(b, index, *sign);
        if index != center && (v > value || (v == value && index < center)) {
            return Ok(None);
        }
    }
    Ok(Some(Cut {
        from: b.index_at(sub(timestamp, *pre)?, b.base),
        to: b.index_at(add(add(timestamp, *post)?, 1)?, center),
        anchor: center,
    }))
}

fn flush(config: &Config, detector: &mut Detector, b: &Buffer) -> Result<Vec<Cut>, String> {
    let mut out = Vec::new();
    match (config, detector) {
        (
            Config::Threshold { min, .. },
            Detector::Threshold {
                phase,
                enter,
                from,
                pending,
            },
        ) => {
            for p in pending.drain(..) {
                out.push(Cut {
                    from: p.from,
                    to: b.end(),
                    anchor: p.anchor,
                });
            }
            if *phase == Phase::Active
                && !b.samples.is_empty()
                && sub(
                    b.samples.last().ok_or("missing last segment sample")?.ts,
                    b.ts(*enter),
                )? >= *min
            {
                out.push(Cut {
                    from: *from,
                    to: b.end(),
                    anchor: *enter,
                });
            }
            *phase = Phase::Idle;
        }
        (Config::Extremum { .. }, Detector::Extremum { candidates }) => {
            while let Some(center) = candidates.pop_front() {
                if let Some(cut) = decide(config, b, center)? {
                    out.push(cut);
                }
            }
        }
        _ => return Err("segmentation detector does not match configuration".into()),
    }
    Ok(out)
}

fn earliest_needed(config: &Config, detector: &Detector, b: &Buffer) -> Result<u64, String> {
    if b.samples.is_empty() {
        return Ok(b.end());
    }
    let last = b.samples.last().ok_or("missing last segment sample")?.ts;
    match (config, detector) {
        (
            Config::Threshold { pre, .. },
            Detector::Threshold {
                phase,
                enter,
                from,
                pending,
            },
        ) => {
            let mut need = b.index_at(sub(last, *pre)?, b.base);
            if *phase != Phase::Idle {
                need = need.min((*from).min(*enter));
            }
            for p in pending {
                need = need.min(p.from);
            }
            Ok(need)
        }
        (
            Config::Extremum {
                pre,
                distance,
                half_window,
                ..
            },
            Detector::Extremum { candidates },
        ) => {
            let lookback = (*pre).max((*distance).max(*half_window));
            let mut need = b.index_at(sub(last, lookback)?, b.base).saturating_sub(2);
            if let Some(&candidate) = candidates.front() {
                need = need.min(b.index_at(sub(b.ts(candidate), lookback)?, b.base));
            }
            Ok(need.max(b.base))
        }
        _ => Err("segmentation detector does not match configuration".into()),
    }
}

fn materialize(
    b: &Buffer,
    cut: Cut,
    strategy: SegmentationStrategy,
    params: &BTreeMap<String, String>,
    param_key: &str,
) -> Result<Episode, String> {
    if cut.from < b.base
        || cut.to > b.end()
        || cut.to <= cut.from
        || cut.anchor < cut.from
        || cut.anchor >= cut.to
    {
        return Err("invalid segment cut".into());
    }
    let samples = &b.samples[(cut.from - b.base) as usize..(cut.to - b.base) as usize];
    let times: Vec<_> = samples.iter().map(|s| s.ts).collect();
    let values: Vec<_> = samples
        .iter()
        .map(|s| f64::from_bits(s.value_bits))
        .collect();
    let interval = if times.len() > 1 {
        Some(sub(times[1], times[0])?)
    } else {
        None
    };
    let regular = times.len() > 1
        && times
            .windows(2)
            .all(|pair| pair[1].checked_sub(pair[0]) == interval);
    let interval = if regular {
        Some(sub(times[1], times[0])?)
    } else {
        None
    };
    let mut deltas = Vec::new();
    let mut previous = 0;
    if !regular {
        for index in 1..times.len() {
            let delta = sub(times[index], times[index - 1])?;
            model::write_long(
                if index == 1 {
                    delta
                } else {
                    sub(delta, previous)?
                },
                &mut deltas,
            );
            previous = delta;
        }
    }
    let source = &b.sources[b.at(cut.anchor).source];
    let start = times[0];
    let last = *times.last().ok_or("empty segment")?;
    let end = add(last, interval.unwrap_or(1))?;
    let identity = format!("{}|{param_key}|{start}", source.key.series_id);
    let seed = xxhash_rust::xxh64::xxh64(identity.as_bytes(), 0);
    let mut labels: BTreeMap<_, _> = source
        .labels
        .iter()
        .filter(|(name, _)| !name.starts_with("gt."))
        .map(|(k, v)| (k.clone(), v.clone()))
        .collect();
    labels.insert(
        "segment.strategy".into(),
        match strategy {
            SegmentationStrategy::FeatureThreshold => "FEATURE_THRESHOLD",
            SegmentationStrategy::FeatureExtremum => "FEATURE_EXTREMUM",
            _ => return Err("unsupported segmentation strategy".into()),
        }
        .into(),
    );
    let out = Episode {
        episode_id: deterministic_ulid(start.div_euclid(1000), seed)?,
        series: source.key.clone(),
        t_start: start,
        t_end: end,
        count: i32::try_from(values.len()).map_err(|_| "segment sample count overflow")?,
        time_encoding: if regular {
            TimeEncoding::Regular
        } else {
            TimeEncoding::IrregularDelta
        },
        interval_micros: interval,
        time_deltas: if regular { None } else { Some(deltas) },
        summary: model::summary(&values),
        values,
        quality: if b.any_quality {
            Some(samples.iter().map(|s| s.quality).collect())
        } else {
            None
        },
        segmentation: Some(Segmentation {
            strategy,
            params: params.clone(),
            anchor_ts: Some(b.ts(cut.anchor)),
            source_episode_id: Some(source.episode_id.clone()),
        }),
        labels,
        bucket_id: None,
        revision: 0,
        provenance: Provenance {
            source: source.source.clone(),
            producer: "opentsx-retrieval/segmenter".into(),
            created_at: add(last, 1)?,
            pipeline: None,
            parent_episode_id: Some(source.episode_id.clone()),
        },
    };
    out.validate(out.values.len())?;
    Ok(out)
}

pub fn deterministic_ulid(epoch_millis: i64, seed: u64) -> Result<String, String> {
    if !(0..1i64 << 48).contains(&epoch_millis) {
        return Err("timestamp out of ULID range".into());
    }
    const ALPHABET: &[u8; 32] = b"0123456789ABCDEFGHJKMNPQRSTVWXYZ";
    let mut output = [0u8; 26];
    let mut time = epoch_millis as u64;
    for i in (0..10).rev() {
        output[i] = ALPHABET[(time & 31) as usize];
        time >>= 5;
    }
    let mut state = (seed ^ 0x5deece66d) & ((1u64 << 48) - 1);
    let mut random = [0u8; 10];
    for chunk in random.chunks_mut(4) {
        state = state.wrapping_mul(0x5deece66d).wrapping_add(11) & ((1u64 << 48) - 1);
        let word = (state >> 16) as u32;
        for (index, byte) in chunk.iter_mut().enumerate() {
            *byte = (word >> (index * 8)) as u8;
        }
    }
    for (part, offset) in [(0usize, 10usize), (5, 18)] {
        let mut value = 0u64;
        for &byte in &random[part..part + 5] {
            value = (value << 8) | u64::from(byte);
        }
        for i in (offset..offset + 8).rev() {
            output[i] = ALPHABET[(value & 31) as usize];
            value >>= 5;
        }
    }
    String::from_utf8(output.to_vec()).map_err(|_| "ULID encoding failed".into())
}

fn finite(params: &Value, name: &str) -> Result<f64, String> {
    params[name]
        .as_f64()
        .filter(|v| v.is_finite())
        .ok_or_else(|| format!("{name} must be a finite number"))
}

fn java_double(value: f64) -> Result<String, String> {
    if value == 0.0 {
        return Ok(if value.is_sign_negative() {
            "-0.0"
        } else {
            "0.0"
        }
        .into());
    }
    if value.abs() >= 1e7 || value.abs() < 1e-3 {
        let formatted = format!("{value:e}");
        let (mantissa, exponent) = formatted
            .split_once('e')
            .ok_or("scientific formatting contains no exponent")?;
        Ok(format!(
            "{}E{}",
            if mantissa.contains('.') {
                mantissa.to_owned()
            } else {
                format!("{mantissa}.0")
            },
            exponent
        ))
    } else {
        let formatted = value.to_string();
        if formatted.contains('.') {
            Ok(formatted)
        } else {
            Ok(format!("{formatted}.0"))
        }
    }
}

pub fn duration_micros(text: &str) -> Result<(i64, String), String> {
    let body = text
        .strip_prefix('P')
        .ok_or("duration must use ISO-8601 notation")?;
    let mut time = false;
    let mut field = String::new();
    let mut nanos = 0i128;
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
            (false, 'D') => (1, 86_400_000_000_000i128),
            (true, 'H') => (2, 3_600_000_000_000),
            (true, 'M') => (3, 60_000_000_000),
            (true, 'S') => (4, 1_000_000_000),
            _ => return Err("invalid ISO duration".into()),
        };
        if rank <= previous || field.is_empty() {
            return Err("invalid ISO duration".into());
        }
        let pieces: Vec<_> = field.split('.').collect();
        if pieces.len() > 2
            || pieces[0].is_empty()
            || pieces.len() == 2
                && (character != 'S' || pieces[1].is_empty() || pieces[1].len() > 9)
        {
            return Err("invalid ISO duration fraction".into());
        }
        let whole = pieces[0].parse::<i128>().map_err(|_| "duration overflow")?;
        let mut amount = whole.checked_mul(scale).ok_or("duration overflow")?;
        if pieces.len() == 2 {
            let fraction = format!("{:0<9}", pieces[1])
                .parse::<i128>()
                .map_err(|_| "invalid ISO duration fraction")?;
            amount = amount.checked_add(fraction).ok_or("duration overflow")?;
        }
        nanos = nanos.checked_add(amount).ok_or("duration overflow")?;
        previous = rank;
        terms += 1;
        field.clear();
    }
    if !field.is_empty() || terms == 0 || text.ends_with('T') {
        return Err("invalid ISO duration".into());
    }
    let micros = i64::try_from(nanos / 1000).map_err(|_| "duration overflow")?;
    let seconds = nanos / 1_000_000_000;
    let fraction = nanos % 1_000_000_000;
    let hours = seconds / 3600;
    let minutes = (seconds % 3600) / 60;
    let remaining = seconds % 60;
    let mut canonical = "PT".to_owned();
    if hours > 0 {
        canonical.push_str(&format!("{hours}H"));
    }
    if minutes > 0 {
        canonical.push_str(&format!("{minutes}M"));
    }
    if remaining > 0 || fraction > 0 || seconds == 0 {
        canonical.push_str(&remaining.to_string());
        if fraction > 0 {
            let digits = format!("{fraction:09}");
            canonical.push('.');
            canonical.push_str(digits.trim_end_matches('0'));
        }
        canonical.push('S');
    }
    Ok((micros, canonical))
}

pub fn z_normalize(values: &[f64]) -> Vec<f64> {
    let mut mean = 0.0;
    let mut n = 0u64;
    for &value in values {
        if !value.is_nan() {
            n += 1;
            mean += (value - mean) / n as f64;
        }
    }
    let mut m2 = 0.0;
    for &value in values {
        if !value.is_nan() {
            m2 += (value - mean) * (value - mean);
        }
    }
    let sd = if n == 0 { 0.0 } else { (m2 / n as f64).sqrt() };
    values
        .iter()
        .map(|&value| {
            if value.is_nan() || sd < 1e-8 {
                0.0
            } else {
                (value - mean) / sd
            }
        })
        .collect()
}

pub fn paa(values: &[f64], w: usize) -> Result<Vec<f64>, String> {
    if w == 0 || values.is_empty() {
        return Err("need w > 0 and a non-empty input".into());
    }
    let n = values.len();
    let mut result = Vec::new();
    result
        .try_reserve_exact(w)
        .map_err(|_| "PAA output allocation exceeds available memory")?;
    for segment in 0..w {
        let from = segment as f64 * n as f64 / w as f64;
        let to = (segment + 1) as f64 * n as f64 / w as f64;
        let mut sum = 0.0;
        let mut weight = 0.0;
        for (index, &value) in values
            .iter()
            .enumerate()
            .take((to.ceil() as usize).min(n))
            .skip(from.floor() as usize)
        {
            let overlap = to.min((index + 1) as f64) - from.max(index as f64);
            if overlap > 0.0 && !value.is_nan() {
                sum += value * overlap;
                weight += overlap;
            }
        }
        result.push(if weight == 0.0 {
            f64::NAN
        } else {
            sum / weight
        });
    }
    Ok(result)
}

pub fn sax_breakpoints(a: usize) -> Result<Vec<f64>, String> {
    if !(2..=26).contains(&a) {
        return Err("alphabet size must be in [2, 26]".into());
    }
    Ok((1..a)
        .map(|i| inverse_normal(i as f64 / a as f64))
        .collect())
}

fn inverse_normal(p: f64) -> f64 {
    let a = [
        -3.969683028665376e1,
        2.209460984245205e2,
        -2.759285104469687e2,
        1.38357751867269e2,
        -3.066479806614716e1,
        2.506628277459239,
    ];
    let b = [
        -5.447609879822406e1,
        1.615858368580409e2,
        -1.556989798598866e2,
        6.680131188771972e1,
        -1.328068155288572e1,
    ];
    let c = [
        -7.784894002430293e-3,
        -3.223964580411365e-1,
        -2.400758277161838,
        -2.549732539343734,
        4.374664141464968,
        2.938163982698783,
    ];
    let d = [
        7.784695709041462e-3,
        3.224671290700398e-1,
        2.445134137142996,
        3.754408661907416,
    ];
    if p < 0.02425 || p > 1.0 - 0.02425 {
        let q = (-2.0 * (if p < 0.02425 { p } else { 1.0 - p }).ln()).sqrt();
        let value = (((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5])
            / ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1.0);
        if p < 0.02425 {
            value
        } else {
            -value
        }
    } else {
        let q = p - 0.5;
        let r = q * q;
        (((((a[0] * r + a[1]) * r + a[2]) * r + a[3]) * r + a[4]) * r + a[5]) * q
            / (((((b[0] * r + b[1]) * r + b[2]) * r + b[3]) * r + b[4]) * r + 1.0)
    }
}

pub fn sax_word(
    episode: &Episode,
    w: usize,
    a: usize,
    normalize: bool,
    spec_hash: &str,
) -> Result<Episode, String> {
    if w == 0 {
        return Err("SAX word length must be positive".into());
    }
    if spec_hash.len() != 64
        || !spec_hash
            .bytes()
            .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
    {
        return Err("SAX needs a 64-character lower-case hex specHash".into());
    }
    episode.validate(episode.values.len())?;
    let breakpoints = sax_breakpoints(a)?;
    let normalized = if normalize {
        z_normalize(&episode.values)
    } else {
        episode.values.clone()
    };
    let mut letters = String::new();
    letters
        .try_reserve(w)
        .map_err(|_| "SAX output allocation exceeds available memory")?;
    for value in paa(&normalized, w)? {
        let value = if value.is_nan() { 0.0 } else { value };
        let index = breakpoints.partition_point(|b| value >= *b);
        letters.push((b'a' + index as u8) as char);
    }
    let mut out = episode.clone();
    out.summary.sax = Some(format!("{w}x{a}:{letters}"));
    out.episode_id = format!(
        "c{}",
        model::xxh64_hex(
            format!(
                "{}|{}|{}|{spec_hash}",
                out.series.series_id, out.t_start, out.t_end
            )
            .as_bytes()
        )
    );
    out.provenance.parent_episode_id = Some(episode.episode_id.clone());
    out.provenance.producer = "opentsx-wayang/sax.word".into();
    out.provenance.created_at = out.t_end;
    Ok(out)
}
