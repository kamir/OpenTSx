use crate::model::{xxh64_hex, Episode, TimeEncoding};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::collections::{BTreeMap, BTreeSet, VecDeque};

const MAX_BINS: usize = 4096;
const MIN_INDEXABLE: f64 = 1e-9;

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct QuantileSketch {
    #[serde(with = "bits")]
    alpha: f64,
    positive: BTreeMap<i32, u64>,
    negative: BTreeMap<i32, u64>,
    zero_count: u64,
    count: u64,
}

impl QuantileSketch {
    pub fn new(alpha: f64) -> Result<Self, String> {
        if !(alpha > 0.0 && alpha < 1.0) {
            return Err("alpha must be in (0, 1)".into());
        }
        Ok(Self {
            alpha,
            positive: BTreeMap::new(),
            negative: BTreeMap::new(),
            zero_count: 0,
            count: 0,
        })
    }

    pub fn alpha(&self) -> f64 {
        self.alpha
    }
    pub fn count(&self) -> u64 {
        self.count
    }

    fn index(&self, value: f64) -> i32 {
        (value.ln() / ((1.0 + self.alpha) / (1.0 - self.alpha)).ln()).ceil() as i32
    }

    fn value(&self, index: i32) -> f64 {
        let gamma = (1.0 + self.alpha) / (1.0 - self.alpha);
        2.0 * sketch_power(gamma, index) / (gamma + 1.0)
    }

    pub fn add(&mut self, value: f64) -> Result<(), String> {
        if !value.is_finite() {
            return Ok(());
        }
        let count = self.count.checked_add(1).ok_or("sketch count overflow")?;
        if value.abs() < MIN_INDEXABLE {
            self.zero_count = self
                .zero_count
                .checked_add(1)
                .ok_or("sketch count overflow")?;
        } else {
            let index = self.index(value.abs());
            let store = if value > 0.0 {
                &mut self.positive
            } else {
                &mut self.negative
            };
            let at = store.entry(index).or_default();
            *at = at.checked_add(1).ok_or("sketch bin count overflow")?;
            collapse(store)?;
        }
        self.count = count;
        Ok(())
    }

    pub fn merge(&mut self, other: &Self) -> Result<(), String> {
        if self.alpha.to_bits() != other.alpha.to_bits() {
            return Err("cannot merge sketches with different alpha".into());
        }
        let mut next = self.clone();
        for (target, source) in [
            (&mut next.positive, &other.positive),
            (&mut next.negative, &other.negative),
        ] {
            for (&index, &count) in source {
                let at = target.entry(index).or_default();
                *at = at.checked_add(count).ok_or("sketch bin count overflow")?;
            }
            collapse(target)?;
        }
        next.zero_count = next
            .zero_count
            .checked_add(other.zero_count)
            .ok_or("sketch zero count overflow")?;
        next.count = next
            .count
            .checked_add(other.count)
            .ok_or("sketch count overflow")?;
        *self = next;
        Ok(())
    }

    pub fn quantile(&self, q: f64) -> Result<f64, String> {
        if !(0.0..=1.0).contains(&q) {
            return Err("q must be in [0, 1]".into());
        }
        if self.count == 0 {
            return Ok(f64::NAN);
        }
        let rank = q * (self.count - 1) as f64;
        let mut seen = 0u64;
        for (&index, &count) in self.negative.iter().rev() {
            seen += count;
            if seen as f64 > rank {
                return Ok(-self.value(index));
            }
        }
        seen += self.zero_count;
        if seen as f64 > rank {
            return Ok(0.0);
        }
        for (&index, &count) in &self.positive {
            seen += count;
            if seen as f64 > rank {
                return Ok(self.value(index));
            }
        }
        Err("sketch stores do not match the sample count".into())
    }

    pub fn fraction_below(&self, x: f64) -> f64 {
        if self.count == 0 {
            return f64::NAN;
        }
        let below: u64 = if x > 0.0 {
            self.negative.values().sum::<u64>()
                + self.zero_count
                + if x >= MIN_INDEXABLE {
                    self.positive.range(..self.index(x)).map(|(_, n)| *n).sum()
                } else {
                    0
                }
        } else if x < 0.0 {
            if -x < MIN_INDEXABLE {
                self.negative.values().sum()
            } else {
                self.negative
                    .range((
                        std::ops::Bound::Excluded(self.index(-x)),
                        std::ops::Bound::Unbounded,
                    ))
                    .map(|(_, n)| *n)
                    .sum()
            }
        } else {
            self.negative.values().sum()
        };
        below as f64 / self.count as f64
    }

    pub fn fraction_above(&self, x: f64) -> f64 {
        if self.count == 0 {
            return f64::NAN;
        }
        let above: u64 = if x < 0.0 {
            self.positive.values().sum::<u64>()
                + self.zero_count
                + if -x >= MIN_INDEXABLE {
                    self.negative.range(..self.index(-x)).map(|(_, n)| *n).sum()
                } else {
                    0
                }
        } else if x > 0.0 {
            if x < MIN_INDEXABLE {
                self.positive.values().sum()
            } else {
                self.positive
                    .range((
                        std::ops::Bound::Excluded(self.index(x)),
                        std::ops::Bound::Unbounded,
                    ))
                    .map(|(_, n)| *n)
                    .sum()
            }
        } else {
            self.positive.values().sum()
        };
        above as f64 / self.count as f64
    }

    pub fn to_bytes(&self) -> Vec<u8> {
        let mut bytes = vec![1];
        bytes.extend_from_slice(&self.alpha.to_le_bytes());
        write_varint(self.zero_count, &mut bytes);
        for store in [&self.positive, &self.negative] {
            write_varint(store.len() as u64, &mut bytes);
            let mut previous = 0i64;
            for (&index, &count) in store {
                let delta = i64::from(index) - previous;
                write_varint(((delta as u64) << 1) ^ ((delta >> 63) as u64), &mut bytes);
                write_varint(count, &mut bytes);
                previous = i64::from(index);
            }
        }
        bytes
    }

    pub fn from_bytes(bytes: &[u8]) -> Result<Self, String> {
        if bytes.len() < 12 || bytes[0] != 1 {
            return Err("unsupported or truncated sketch format".into());
        }
        let alpha = f64::from_le_bytes(bytes[1..9].try_into().map_err(|_| "invalid sketch alpha")?);
        let mut out = Self::new(alpha)?;
        let mut position = 9;
        out.zero_count = read_varint(bytes, &mut position)?;
        out.count = out.zero_count;
        for store in [&mut out.positive, &mut out.negative] {
            let size = read_varint(bytes, &mut position)?;
            if size > MAX_BINS as u64 {
                return Err("sketch bin limit exceeded".into());
            }
            let mut previous = 0i64;
            for index_at in 0..size {
                let raw = read_varint(bytes, &mut position)?;
                let delta = ((raw >> 1) as i64) ^ -((raw & 1) as i64);
                let index = previous.checked_add(delta).ok_or("sketch index overflow")?;
                if index_at > 0 && index <= previous {
                    return Err("sketch bins must be strictly ordered".into());
                }
                let index_i32 = i32::try_from(index).map_err(|_| "sketch index overflow")?;
                let count = read_varint(bytes, &mut position)?;
                if count == 0 {
                    return Err("sketch bin count must be positive".into());
                }
                out.count = out
                    .count
                    .checked_add(count)
                    .ok_or("sketch count overflow")?;
                store.insert(index_i32, count);
                previous = index;
            }
        }
        if position != bytes.len() {
            return Err("trailing sketch bytes".into());
        }
        out.validate()?;
        Ok(out)
    }
}

fn collapse(store: &mut BTreeMap<i32, u64>) -> Result<(), String> {
    while store.len() > MAX_BINS {
        let (_, count) = store.pop_first().ok_or("cannot collapse an empty sketch")?;
        let next = store
            .first_entry()
            .ok_or("cannot collapse an empty sketch")?
            .into_mut();
        *next = next
            .checked_add(count)
            .ok_or("sketch collapsed count overflow")?;
    }
    Ok(())
}

fn write_varint(mut value: u64, bytes: &mut Vec<u8>) {
    while value > 127 {
        bytes.push(value as u8 | 128);
        value >>= 7;
    }
    bytes.push(value as u8);
}

fn read_varint(bytes: &[u8], position: &mut usize) -> Result<u64, String> {
    let mut value = 0u64;
    for shift in (0..70).step_by(7) {
        let byte = *bytes.get(*position).ok_or("truncated sketch varint")?;
        *position += 1;
        if shift == 63 && byte > 1 {
            return Err("sketch varint overflow".into());
        }
        value |= u64::from(byte & 127) << shift;
        if byte & 128 == 0 {
            if shift > 0 && byte == 0 {
                return Err("noncanonical sketch varint".into());
            }
            return Ok(value);
        }
    }
    Err("sketch varint overflow".into())
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct StatsAccumulator {
    pub valid_count: u64,
    pub nan_count: u64,
    #[serde(with = "bits")]
    pub min: f64,
    #[serde(with = "bits")]
    pub max: f64,
    #[serde(with = "bits")]
    pub mean: f64,
    #[serde(with = "bits")]
    pub m2: f64,
    pub gap_count: u64,
    pub first_micros: i64,
    pub last_micros: i64,
    pub sketch: Option<QuantileSketch>,
}

impl StatsAccumulator {
    pub fn new(alpha: f64) -> Result<Self, String> {
        if !alpha.is_finite() {
            return Err("sketch alpha must be finite".into());
        }
        Ok(Self {
            valid_count: 0,
            nan_count: 0,
            min: f64::NAN,
            max: f64::NAN,
            mean: 0.0,
            m2: 0.0,
            gap_count: 0,
            first_micros: i64::MAX,
            last_micros: i64::MIN,
            sketch: if alpha > 0.0 {
                Some(QuantileSketch::new(alpha)?)
            } else {
                None
            },
        })
    }

    pub fn add(&mut self, timestamp: i64, value: f64) -> Result<(), String> {
        self.first_micros = self.first_micros.min(timestamp);
        self.last_micros = self.last_micros.max(timestamp);
        if !value.is_finite() {
            self.nan_count = self
                .nan_count
                .checked_add(1)
                .ok_or("statistics count overflow")?;
            return Ok(());
        }
        self.valid_count = self
            .valid_count
            .checked_add(1)
            .ok_or("statistics count overflow")?;
        let delta = value - self.mean;
        self.mean += delta / self.valid_count as f64;
        self.m2 += delta * (value - self.mean);
        self.min = if self.valid_count == 1 {
            value
        } else {
            java_min(self.min, value)
        };
        self.max = if self.valid_count == 1 {
            value
        } else {
            java_max(self.max, value)
        };
        if let Some(sketch) = &mut self.sketch {
            sketch.add(value)?;
        }
        Ok(())
    }

    pub fn add_gap(&mut self) -> Result<(), String> {
        self.gap_count = self
            .gap_count
            .checked_add(1)
            .ok_or("statistics gap count overflow")?;
        Ok(())
    }

    pub fn merge(&mut self, other: &Self) -> Result<(), String> {
        let mut next = self.clone();
        let total = next
            .valid_count
            .checked_add(other.valid_count)
            .ok_or("statistics count overflow")?;
        if other.valid_count > 0 {
            if next.valid_count == 0 {
                next.mean = other.mean;
                next.m2 = other.m2;
                next.min = other.min;
                next.max = other.max;
            } else {
                let delta = other.mean - next.mean;
                next.mean += delta * other.valid_count as f64 / total as f64;
                next.m2 += other.m2
                    + delta
                        * delta
                        * (next.valid_count as f64 * other.valid_count as f64 / total as f64);
                next.min = java_min(next.min, other.min);
                next.max = java_max(next.max, other.max);
            }
        }
        next.valid_count = total;
        next.nan_count = next
            .nan_count
            .checked_add(other.nan_count)
            .ok_or("statistics count overflow")?;
        next.gap_count = next
            .gap_count
            .checked_add(other.gap_count)
            .ok_or("statistics gap count overflow")?;
        next.first_micros = next.first_micros.min(other.first_micros);
        next.last_micros = next.last_micros.max(other.last_micros);
        if let (Some(target), Some(source)) = (&mut next.sketch, &other.sketch) {
            target.merge(source)?;
        }
        *self = next;
        Ok(())
    }

    pub fn to_record(&self, series_id: &str, window_type: &str, from: i64, to: i64) -> StatsRecord {
        StatsRecord {
            series_id: series_id.into(),
            window_type: window_type.into(),
            window_start_micros: from,
            window_end_micros: to,
            valid_count: self.valid_count,
            nan_count: self.nan_count,
            gap_count: self.gap_count,
            min: if self.valid_count == 0 {
                f64::NAN
            } else {
                self.min
            },
            max: if self.valid_count == 0 {
                f64::NAN
            } else {
                self.max
            },
            mean: if self.valid_count == 0 {
                f64::NAN
            } else {
                self.mean
            },
            m2: if self.valid_count == 0 {
                f64::NAN
            } else {
                self.m2
            },
            last_seen_micros: (self.last_micros != i64::MIN).then_some(self.last_micros),
            sketch: self.sketch.clone(),
        }
    }
}

fn java_min(left: f64, right: f64) -> f64 {
    if left == 0.0 && right == 0.0 {
        f64::from_bits(left.to_bits() | right.to_bits())
    } else {
        left.min(right)
    }
}
fn java_max(left: f64, right: f64) -> f64 {
    if left == 0.0 && right == 0.0 {
        f64::from_bits(left.to_bits() & right.to_bits())
    } else {
        left.max(right)
    }
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct StatsRecord {
    pub series_id: String,
    pub window_type: String,
    pub window_start_micros: i64,
    pub window_end_micros: i64,
    pub valid_count: u64,
    pub nan_count: u64,
    pub gap_count: u64,
    #[serde(with = "bits")]
    pub min: f64,
    #[serde(with = "bits")]
    pub max: f64,
    #[serde(with = "bits")]
    pub mean: f64,
    #[serde(with = "bits")]
    pub m2: f64,
    pub last_seen_micros: Option<i64>,
    pub sketch: Option<QuantileSketch>,
}

impl StatsRecord {
    pub fn vector_json(&self) -> Result<Value, String> {
        let mut out = json!({"seriesId":self.series_id,"windowType":self.window_type,
            "windowStartMicros":self.window_start_micros,"windowEndMicros":self.window_end_micros,
            "validCount":self.valid_count,"nanCount":self.nan_count,"gapCount":self.gap_count,
            "min":vector_number(self.min),"max":vector_number(self.max),"mean":vector_number(self.mean),"m2":vector_number(self.m2),
            "lastSeenMicros":self.last_seen_micros,"sketchHex":Value::Null});
        if let Some(sketch) = &self.sketch {
            out["sketchHex"] = sketch
                .to_bytes()
                .iter()
                .map(|byte| format!("{byte:02x}"))
                .collect::<String>()
                .into();
            let mut quantiles = Vec::new();
            for q in [0.01, 0.5, 0.99] {
                quantiles.push(json!({"q":q,"value":vector_number(sketch.quantile(q)?)}));
            }
            out["quantiles"] = quantiles.into();
        }
        Ok(out)
    }
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct SeriesState {
    last_ts: i64,
    progress: i64,
    learned_interval: i64,
    global: StatsAccumulator,
    global_dirty: bool,
    open: BTreeMap<i64, BTreeMap<i64, StatsAccumulator>>,
    closed_up_to: BTreeMap<i64, i64>,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct StatsEngine {
    windows: Vec<(String, i64)>,
    #[serde(with = "bits")]
    alpha: f64,
    expected_interval: Option<i64>,
    lateness_micros: i64,
    series: BTreeMap<String, SeriesState>,
    recent: VecDeque<String>,
    #[serde(skip)]
    recent_ids: BTreeSet<String>,
}

impl StatsEngine {
    pub fn new(
        windows: Vec<(String, i64)>,
        alpha: f64,
        expected_interval: Option<i64>,
        lateness_micros: i64,
    ) -> Result<Self, String> {
        StatsAccumulator::new(alpha)?;
        if windows.iter().any(|(_, size)| *size <= 0)
            || lateness_micros < 0
            || expected_interval.is_some_and(|x| x <= 0)
        {
            return Err("statistics durations must be positive and lateness nonnegative".into());
        }
        if windows
            .iter()
            .map(|(_, size)| size)
            .collect::<std::collections::BTreeSet<_>>()
            .len()
            != windows.len()
        {
            return Err("statistics window widths must be distinct".into());
        }
        Ok(Self {
            windows,
            alpha,
            expected_interval,
            lateness_micros,
            series: BTreeMap::new(),
            recent: VecDeque::new(),
            recent_ids: BTreeSet::new(),
        })
    }

    fn state(&mut self, id: &str) -> Result<&mut SeriesState, String> {
        if !self.series.contains_key(id) {
            let global = StatsAccumulator::new(self.alpha)?;
            self.series.insert(
                id.into(),
                SeriesState {
                    last_ts: i64::MIN,
                    progress: i64::MIN,
                    learned_interval: 0,
                    global,
                    global_dirty: false,
                    open: BTreeMap::new(),
                    closed_up_to: BTreeMap::new(),
                },
            );
        }
        self.series
            .get_mut(id)
            .ok_or_else(|| "missing statistics series state".into())
    }

    fn add(&mut self, id: &str, timestamp: i64, value: f64) -> Result<(), String> {
        let windows = self.windows.clone();
        let expected = self.expected_interval;
        let alpha = self.alpha;
        let state = self.state(id)?;
        let interval = expected.unwrap_or(state.learned_interval);
        let gap = state.last_ts != i64::MIN
            && timestamp > state.last_ts
            && interval > 0
            && (i128::from(timestamp) - i128::from(state.last_ts)) as f64 > 1.5 * interval as f64;
        state.last_ts = state.last_ts.max(timestamp);
        state.global.add(timestamp, value)?;
        state.global_dirty = true;
        if gap {
            state.global.add_gap()?;
        }
        for (_, size) in windows {
            let start = timestamp
                .div_euclid(size)
                .checked_mul(size)
                .ok_or("statistics window overflow")?;
            let end = start
                .checked_add(size)
                .ok_or("statistics window overflow")?;
            if end <= *state.closed_up_to.get(&size).unwrap_or(&i64::MIN) {
                continue;
            }
            let open = state.open.entry(size).or_default();
            if let std::collections::btree_map::Entry::Vacant(entry) = open.entry(start) {
                entry.insert(StatsAccumulator::new(alpha)?);
            }
            let acc = open.get_mut(&start).ok_or("missing statistics window")?;
            acc.add(timestamp, value)?;
            if gap {
                acc.add_gap()?;
            }
        }
        Ok(())
    }

    fn close(&mut self, id: &str) -> Result<Vec<StatsRecord>, String> {
        let windows = self.windows.clone();
        let lateness = self.lateness_micros;
        let state = self.state(id)?;
        let mut out = Vec::new();
        for (name, size) in windows {
            if let Some(open) = state.open.get_mut(&size) {
                while let Some((&start, _)) = open.first_key_value() {
                    let end = start
                        .checked_add(size)
                        .ok_or("statistics window overflow")?;
                    if i128::from(end) + i128::from(lateness) > i128::from(state.progress) {
                        break;
                    }
                    let (_, acc) = open.pop_first().ok_or("missing statistics window")?;
                    out.push(acc.to_record(id, &name, start, end));
                    let closed = state.closed_up_to.entry(size).or_insert(i64::MIN);
                    *closed = (*closed).max(end);
                }
            }
        }
        Ok(out)
    }

    pub fn on_sample(
        &mut self,
        id: &str,
        timestamp: i64,
        value: f64,
    ) -> Result<Vec<StatsRecord>, String> {
        self.add(id, timestamp, value)?;
        let state = self.state(id)?;
        state.progress = state.progress.max(timestamp);
        self.close(id)
    }

    pub fn on_episode(&mut self, episode: &Episode) -> Result<Vec<StatsRecord>, String> {
        if self.recent_ids.contains(&episode.episode_id) {
            return Ok(Vec::new());
        }
        let times = episode.timestamps()?;
        if episode.time_encoding == TimeEncoding::Regular {
            self.state(&episode.series.series_id)?.learned_interval = episode
                .interval_micros
                .ok_or("regular episode interval is missing")?;
        }
        for (&timestamp, &value) in times.iter().zip(&episode.values) {
            self.add(&episode.series.series_id, timestamp, value)?;
        }
        let state = self.state(&episode.series.series_id)?;
        state.progress = state.progress.max(episode.t_end);
        self.recent_ids.insert(episode.episode_id.clone());
        self.recent.push_back(episode.episode_id.clone());
        if self.recent.len() > 100_000 {
            let oldest = self
                .recent
                .pop_front()
                .ok_or("statistics deduplication queue is empty")?;
            self.recent_ids.remove(&oldest);
        }
        self.close(&episode.series.series_id)
    }

    pub fn emit_global(&mut self) -> Result<Vec<StatsRecord>, String> {
        let mut out = Vec::new();
        for (id, state) in &mut self.series {
            if state.global_dirty && state.global.valid_count + state.global.nan_count > 0 {
                let end = state
                    .global
                    .last_micros
                    .checked_add(1)
                    .ok_or("statistics global end overflow")?;
                out.push(
                    state
                        .global
                        .to_record(id, "GLOBAL", state.global.first_micros, end),
                );
                state.global_dirty = false;
            }
        }
        Ok(out)
    }

    pub fn flush(&mut self) -> Result<Vec<StatsRecord>, String> {
        let mut out = Vec::new();
        for (id, state) in &mut self.series {
            for (name, size) in &self.windows {
                if let Some(open) = state.open.get_mut(size) {
                    while let Some((start, acc)) = open.pop_first() {
                        let end = start
                            .checked_add(*size)
                            .ok_or("statistics window overflow")?;
                        out.push(acc.to_record(id, name, start, end));
                        let closed = state.closed_up_to.entry(*size).or_insert(i64::MIN);
                        *closed = (*closed).max(end);
                    }
                }
            }
        }
        out.extend(self.emit_global()?);
        Ok(out)
    }
}

pub fn vector_number(value: f64) -> Value {
    if value.is_finite() {
        json!(value)
    } else if value.is_nan() {
        "NaN".into()
    } else if value.is_sign_positive() {
        "Infinity".into()
    } else {
        "-Infinity".into()
    }
}

mod bits {
    use serde::{Deserialize, Deserializer, Serializer};
    pub fn serialize<S: Serializer>(value: &f64, serializer: S) -> Result<S::Ok, S::Error> {
        serializer.serialize_str(&format!("{:016x}", value.to_bits()))
    }
    pub fn deserialize<'de, D: Deserializer<'de>>(deserializer: D) -> Result<f64, D::Error> {
        let value = String::deserialize(deserializer)?;
        if value.len() != 16
            || !value
                .bytes()
                .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
        {
            return Err(serde::de::Error::custom(
                "floating point state must contain 16 lowercase hexadecimal digits",
            ));
        }
        u64::from_str_radix(&value, 16)
            .map(f64::from_bits)
            .map_err(serde::de::Error::custom)
    }
}

#[derive(Clone, Debug)]
pub struct Expectation {
    pub min: Option<f64>,
    pub max: Option<f64>,
    pub expected_interval_micros: i64,
    pub min_completeness: f64,
    pub max_out_of_range: f64,
    pub flatline_check: bool,
    pub flatline_min_count: u64,
    pub flatline_epsilon: f64,
    pub max_gaps: u64,
    pub max_age_micros: Option<i64>,
    pub max_age: Option<String>,
}

impl Expectation {
    pub fn from_json(value: &Value) -> Result<Self, String> {
        let fields = value
            .as_object()
            .ok_or("DQ expectation must be an object")?;
        if fields.keys().any(|key| {
            ![
                "min",
                "max",
                "expectedIntervalMicros",
                "expectedInterval",
                "minCompleteness",
                "maxOutOfRange",
                "flatlineCheck",
                "flatlineMinCount",
                "flatlineEpsilon",
                "maxGaps",
                "maxAge",
                "checks",
            ]
            .contains(&key.as_str())
        }) {
            return Err("unsupported DQ expectation field".into());
        }
        fn optional_number(value: &Value, name: &str) -> Result<Option<f64>, String> {
            match value.get(name) {
                None | Some(Value::Null) => Ok(None),
                Some(v) => v
                    .as_f64()
                    .filter(|v| v.is_finite())
                    .map(Some)
                    .ok_or_else(|| format!("{name} must be finite")),
            }
        }
        fn integer(value: &Value, name: &str, default: u64) -> Result<u64, String> {
            value.get(name).map_or(Ok(default), |v| {
                v.as_u64()
                    .ok_or_else(|| format!("{name} must be a nonnegative integer"))
            })
        }
        if let Some(checks) = value.get("checks") {
            let values = checks.as_array().ok_or("DQ checks must be an array")?;
            let mut seen = std::collections::BTreeSet::new();
            for check in values {
                let check = check.as_str().ok_or("DQ checks must contain strings")?;
                if ![
                    "COMPLETENESS",
                    "PLAUSIBLE_RANGE",
                    "FLATLINE",
                    "GAPS",
                    "FRESHNESS",
                ]
                .contains(&check)
                    || !seen.insert(check)
                {
                    return Err("DQ checks must be known and distinct".into());
                }
            }
        }
        let min = optional_number(value, "min")?;
        let max = optional_number(value, "max")?;
        if min.zip(max).is_some_and(|(a, b)| a > b) {
            return Err("DQ minimum exceeds maximum".into());
        }
        let expected_interval_micros = match (
            value.get("expectedIntervalMicros"),
            value.get("expectedInterval"),
        ) {
            (Some(_), Some(_)) => return Err("DQ interval must be specified once".into()),
            (Some(v), None) => v
                .as_i64()
                .filter(|v| *v >= 0)
                .ok_or("expectedIntervalMicros must be nonnegative")?,
            (None, Some(v)) => {
                parse_duration(v.as_str().ok_or("expectedInterval must be a duration")?)?
            }
            (None, None) => 0,
        };
        let max_age = match value.get("maxAge") {
            None | Some(Value::Null) => None,
            Some(v) => Some(canonical_duration(parse_duration(
                v.as_str().ok_or("maxAge must be a duration")?,
            )?)),
        };
        let max_age_micros = max_age.as_ref().map(|v| parse_duration(v)).transpose()?;
        let min_completeness = optional_number(value, "minCompleteness")?.unwrap_or(0.95);
        let max_out_of_range = optional_number(value, "maxOutOfRange")?.unwrap_or(0.001);
        let flatline_epsilon = optional_number(value, "flatlineEpsilon")?.unwrap_or(1e-9);
        if !(0.0..=1.0).contains(&min_completeness)
            || !(0.0..=1.0).contains(&max_out_of_range)
            || flatline_epsilon < 0.0
        {
            return Err("invalid DQ probability or flatline threshold".into());
        }
        Ok(Self {
            min,
            max,
            expected_interval_micros,
            min_completeness,
            max_out_of_range,
            flatline_check: value.get("flatlineCheck").map_or(Ok(true), |v| {
                v.as_bool().ok_or("flatlineCheck must be boolean")
            })?,
            flatline_min_count: integer(value, "flatlineMinCount", 60)?,
            flatline_epsilon,
            max_gaps: integer(value, "maxGaps", 0)?,
            max_age,
            max_age_micros,
        })
    }
}

pub fn parse_duration(text: &str) -> Result<i64, String> {
    let mut body = text
        .strip_prefix('P')
        .ok_or("duration must use ISO-8601 syntax")?;
    let mut time = false;
    let mut total = 0i64;
    let mut rank = 0;
    let mut terms = 0;
    while !body.is_empty() {
        if let Some(tail) = body.strip_prefix('T') {
            if time {
                return Err("invalid duration".into());
            }
            time = true;
            body = tail;
            if body.is_empty() {
                return Err("invalid duration".into());
            }
            continue;
        }
        let end = body
            .find(|c: char| !c.is_ascii_digit() && c != '.')
            .ok_or("invalid duration")?;
        let (number, suffix) = body.split_at(end);
        let unit = suffix.as_bytes()[0] as char;
        let (next_rank, scale) = match (time, unit) {
            (false, 'D') => (1, 86_400_000_000i64),
            (true, 'H') => (2, 3_600_000_000),
            (true, 'M') => (3, 60_000_000),
            (true, 'S') => (4, 1_000_000),
            _ => return Err("invalid duration unit".into()),
        };
        if next_rank <= rank {
            return Err("duration units must be ordered and distinct".into());
        }
        rank = next_rank;
        let (whole, fraction) = number
            .split_once('.')
            .map_or((number, None), |(a, b)| (a, Some(b)));
        let whole = whole
            .parse::<i64>()
            .map_err(|_| "invalid duration number")?;
        let mut amount = whole.checked_mul(scale).ok_or("duration overflow")?;
        if let Some(fraction) = fraction {
            if unit != 'S'
                || fraction.is_empty()
                || fraction.len() > 9
                || !fraction.bytes().all(|b| b.is_ascii_digit())
            {
                return Err("invalid duration fraction".into());
            }
            let nanos = format!("{fraction:0<9}")
                .parse::<i64>()
                .map_err(|_| "invalid duration fraction")?;
            if nanos % 1000 != 0 {
                return Err("duration must contain whole microseconds".into());
            }
            amount = amount
                .checked_add(nanos / 1000)
                .ok_or("duration overflow")?;
        }
        total = total.checked_add(amount).ok_or("duration overflow")?;
        terms += 1;
        body = &suffix[1..];
    }
    if terms == 0 {
        return Err("duration needs at least one component".into());
    }
    Ok(total)
}

#[derive(Clone, Debug)]
pub struct DqResult {
    pub series_id: String,
    pub window_type: String,
    pub window_start_micros: i64,
    pub window_end_micros: i64,
    pub check: String,
    pub status: String,
    pub value: f64,
    pub threshold: String,
    pub message: String,
}

impl DqResult {
    pub fn vector_json(&self) -> Value {
        json!({"seriesId":self.series_id,"windowType":self.window_type,"windowStartMicros":self.window_start_micros,
            "windowEndMicros":self.window_end_micros,"check":self.check,"status":self.status,
            "value":vector_number(self.value),"threshold":self.threshold,"message":self.message})
    }

    pub fn signal(&self, job: &str, site: &str, spec_hash: &str) -> Result<Value, String> {
        if job.trim().is_empty()
            || site.trim().is_empty()
            || self.series_id.len() != 16
            || !self
                .series_id
                .bytes()
                .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
            || spec_hash.len() != 64
            || !spec_hash
                .bytes()
                .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
        {
            return Err(
                "DQ signal needs canonical job, site, series and specification identities".into(),
            );
        }
        let from = instant(self.window_start_micros)?;
        let to = instant(self.window_end_micros)?;
        let id = xxh64_hex(
            format!(
                "{job}|{site}|{}|dq|{to}|{}|{from}|{to}",
                self.series_id, self.check
            )
            .as_bytes(),
        );
        Ok(
            json!({"schema":"opentsx.signal.v1","id":id,"job":job,"site":site,"seriesId":self.series_id,
            "kind":"dq","at":to,"window":{"from":from,"to":to},"check":self.check,"status":self.status,
            "value":if self.value.is_finite() { json!(self.value) } else { Value::Null },
            "threshold":self.threshold,"message":self.message,"specHash":spec_hash}),
        )
    }
}

fn instant(micros: i64) -> Result<String, String> {
    let time = time::OffsetDateTime::from_unix_timestamp_nanos(i128::from(micros) * 1000)
        .map_err(|_| "signal timestamp out of range")?;
    let base = format!(
        "{:04}-{:02}-{:02}T{:02}:{:02}:{:02}",
        time.year(),
        u8::from(time.month()),
        time.day(),
        time.hour(),
        time.minute(),
        time.second()
    );
    let fraction = time.microsecond();
    Ok(if fraction == 0 {
        format!("{base}Z")
    } else if fraction % 1000 == 0 {
        format!("{base}.{:03}Z", fraction / 1000)
    } else {
        format!("{base}.{fraction:06}Z")
    })
}

pub fn evaluate(
    stats: &StatsRecord,
    expectation: &Expectation,
    now_micros: Option<i64>,
) -> Result<Vec<DqResult>, String> {
    let mut out = Vec::new();
    let e = expectation;
    let mut result =
        |check: &str, success: bool, value: f64, threshold: String, message: String| {
            out.push(DqResult {
                series_id: stats.series_id.clone(),
                window_type: stats.window_type.clone(),
                window_start_micros: stats.window_start_micros,
                window_end_micros: stats.window_end_micros,
                check: check.into(),
                status: if success { "SUCCESS" } else { "FAILED" }.into(),
                value,
                threshold,
                message,
            });
        };
    if e.expected_interval_micros > 0 && stats.window_end_micros > stats.window_start_micros {
        let width = stats
            .window_end_micros
            .checked_sub(stats.window_start_micros)
            .ok_or("DQ window overflow")?;
        let expected = (width as f64 / e.expected_interval_micros as f64).floor();
        let ratio = if expected <= 0.0 {
            1.0
        } else {
            (stats.valid_count as f64 / expected).min(1.0)
        };
        result(
            "COMPLETENESS",
            ratio >= e.min_completeness,
            ratio,
            format!(">= {}", java_double(e.min_completeness)),
            format!(
                "{} of {:.0} expected values valid ({} NaN)",
                stats.valid_count, expected, stats.nan_count
            ),
        );
    }
    if (e.min.is_some() || e.max.is_some()) && stats.valid_count > 0 {
        let (outside, how) = if let Some(sketch) = &stats.sketch {
            (
                e.min.map_or(0.0, |v| sketch.fraction_below(v))
                    + e.max.map_or(0.0, |v| sketch.fraction_above(v)),
                "sketch",
            )
        } else {
            (
                if e.min.is_some_and(|v| stats.min < v) || e.max.is_some_and(|v| stats.max > v) {
                    1.0
                } else {
                    0.0
                },
                "min/max only",
            )
        };
        result(
            "PLAUSIBLE_RANGE",
            outside <= e.max_out_of_range,
            outside,
            format!("<= {}", java_double(e.max_out_of_range)),
            format!(
                "range [{}, {}], observed [{}, {}], share outside {:.4} ({how})",
                e.min.map_or_else(|| "null".into(), java_double),
                e.max.map_or_else(|| "null".into(), java_double),
                java_general(stats.min, 4),
                java_general(stats.max, 4),
                outside
            ),
        );
    }
    if e.flatline_check && stats.valid_count >= e.flatline_min_count {
        let sd = if stats.valid_count < 2 {
            f64::NAN
        } else {
            (stats.m2 / (stats.valid_count - 1) as f64).sqrt()
        };
        let flat = sd <= e.flatline_epsilon;
        result(
            "FLATLINE",
            !flat,
            sd,
            format!("> {}", java_double(e.flatline_epsilon)),
            if flat {
                format!(
                    "constant value {} over {} values",
                    java_general(stats.mean, 6),
                    stats.valid_count
                )
            } else {
                "values vary".into()
            },
        );
    }
    if e.expected_interval_micros > 0 || stats.gap_count > 0 {
        result(
            "GAPS",
            stats.gap_count <= e.max_gaps,
            stats.gap_count as f64,
            format!("<= {}", e.max_gaps),
            format!("{} gaps", stats.gap_count),
        );
    }
    if let (Some(max_age), Some(now)) = (e.max_age_micros, now_micros) {
        let text = e
            .max_age
            .as_deref()
            .ok_or("maxAge duration text is missing")?;
        match stats.last_seen_micros {
            None => {
                result(
                    "FRESHNESS",
                    false,
                    f64::NAN,
                    format!("<= {text}"),
                    "never seen".into(),
                );
                out.last_mut().ok_or("freshness result is missing")?.status = "ABORTED".into();
            }
            Some(last) => {
                let age = now.checked_sub(last).ok_or("DQ age overflow")? as f64 / 1e6;
                result(
                    "FRESHNESS",
                    age <= max_age as f64 / 1e6,
                    age,
                    format!("<= {text}"),
                    format!("last value {:.0} s ago", age),
                );
            }
        }
    }
    Ok(out)
}

fn java_double(value: f64) -> String {
    if value == 0.0 {
        return if value.is_sign_negative() {
            "-0.0"
        } else {
            "0.0"
        }
        .into();
    }
    if value.abs() < 1e-3 || value.abs() >= 1e7 {
        let raw = format!("{value:e}");
        let (mantissa, exponent) = raw
            .split_once('e')
            .expect("scientific float formatting contains an exponent");
        let mantissa = if mantissa.contains('.') {
            mantissa.to_owned()
        } else {
            format!("{mantissa}.0")
        };
        format!(
            "{mantissa}E{}",
            exponent
                .parse::<i32>()
                .expect("formatted exponent is an integer")
        )
    } else {
        let mut out = value.to_string();
        if !out.contains('.') {
            out.push_str(".0");
        }
        out
    }
}

fn java_general(value: f64, precision: usize) -> String {
    if value == 0.0 {
        return format!("{value:.digits$}", digits = precision - 1);
    }
    let scientific = format!("{value:.digits$e}", digits = precision - 1);
    let (mantissa, exponent) = scientific
        .split_once('e')
        .expect("scientific float formatting contains an exponent");
    let exponent = exponent
        .parse::<i32>()
        .expect("formatted exponent is an integer");
    if exponent < -4 || exponent >= precision as i32 {
        format!(
            "{mantissa}e{}{abs:02}",
            if exponent >= 0 { '+' } else { '-' },
            abs = exponent.abs()
        )
    } else {
        format!(
            "{value:.digits$}",
            digits = (precision as i32 - 1 - exponent).max(0) as usize
        )
    }
}

pub const STATS_FINGERPRINT: &str = "e51b48188baa6606";
const STATS_HEADER: [u8; 10] = [0xc3, 1, 0x06, 0x66, 0xaa, 0x8b, 0x18, 0x48, 0x1b, 0xe5];

impl StatsRecord {
    pub fn encode_soe(&self) -> Result<Vec<u8>, String> {
        self.validate()?;
        if self.window_start_micros >= self.window_end_micros {
            return Err("statistics window start must precede its end".into());
        }
        let mut bytes = STATS_HEADER.to_vec();
        for value in [&self.series_id, &self.window_type] {
            crate::model::write_long(
                i64::try_from(value.len()).map_err(|_| "statistics string length overflow")?,
                &mut bytes,
            );
            bytes.extend_from_slice(value.as_bytes());
        }
        for value in [
            self.window_start_micros,
            self.window_end_micros,
            i64::try_from(self.valid_count).map_err(|_| "statistics count overflow")?,
            i64::try_from(self.nan_count).map_err(|_| "statistics count overflow")?,
        ] {
            crate::model::write_long(value, &mut bytes);
        }
        for value in [self.min, self.max, self.mean, self.m2] {
            let bits = if value.is_nan() {
                0x7ff8000000000000
            } else {
                value.to_bits()
            };
            bytes.extend_from_slice(&bits.to_le_bytes());
        }
        match &self.sketch {
            None => crate::model::write_long(0, &mut bytes),
            Some(sketch) => {
                crate::model::write_long(1, &mut bytes);
                let raw = sketch.to_bytes();
                crate::model::write_long(raw.len() as i64, &mut bytes);
                bytes.extend(raw);
            }
        }
        crate::model::write_long(
            i64::try_from(self.gap_count).map_err(|_| "statistics gap count overflow")?,
            &mut bytes,
        );
        match self.last_seen_micros {
            None => crate::model::write_long(0, &mut bytes),
            Some(last) => {
                crate::model::write_long(1, &mut bytes);
                crate::model::write_long(last, &mut bytes);
            }
        }
        Ok(bytes)
    }

    pub fn decode_soe(bytes: &[u8], max_bytes: usize) -> Result<Self, String> {
        if bytes.len() > max_bytes {
            return Err("statistics payload limit exceeded".into());
        }
        if bytes.get(..10) != Some(&STATS_HEADER) {
            return Err("unsupported SeriesStats schema fingerprint".into());
        }
        fn string(bytes: &[u8], position: &mut usize) -> Result<String, String> {
            let size = usize::try_from(crate::model::read_long(bytes, position)?)
                .map_err(|_| "negative statistics string length")?;
            let end = position
                .checked_add(size)
                .ok_or("statistics string length overflow")?;
            let text = std::str::from_utf8(
                bytes
                    .get(*position..end)
                    .ok_or("truncated statistics string")?,
            )
            .map_err(|_| "invalid statistics UTF-8")?
            .to_owned();
            *position = end;
            Ok(text)
        }
        fn count(bytes: &[u8], position: &mut usize) -> Result<u64, String> {
            u64::try_from(crate::model::read_long(bytes, position)?)
                .map_err(|_| "negative statistics count".into())
        }
        let mut position = 10;
        let series_id = string(bytes, &mut position)?;
        let window_type = string(bytes, &mut position)?;
        let window_start_micros = crate::model::read_long(bytes, &mut position)?;
        let window_end_micros = crate::model::read_long(bytes, &mut position)?;
        if window_start_micros >= window_end_micros {
            return Err("statistics window start must precede its end".into());
        }
        let valid_count = count(bytes, &mut position)?;
        let nan_count = count(bytes, &mut position)?;
        let mut moments = [0.0; 4];
        for moment in &mut moments {
            let end = position
                .checked_add(8)
                .ok_or("statistics payload overflow")?;
            *moment = f64::from_le_bytes(
                bytes
                    .get(position..end)
                    .ok_or("truncated statistics moments")?
                    .try_into()
                    .map_err(|_| "invalid statistics moment")?,
            );
            position = end;
        }
        let sketch = match crate::model::read_long(bytes, &mut position)? {
            0 => None,
            1 => {
                let size = usize::try_from(crate::model::read_long(bytes, &mut position)?)
                    .map_err(|_| "negative statistics sketch length")?;
                let end = position
                    .checked_add(size)
                    .ok_or("statistics sketch length overflow")?;
                let sketch = QuantileSketch::from_bytes(
                    bytes
                        .get(position..end)
                        .ok_or("truncated statistics sketch")?,
                )?;
                position = end;
                Some(sketch)
            }
            _ => return Err("invalid statistics sketch union".into()),
        };
        let gap_count = count(bytes, &mut position)?;
        let last_seen_micros = match crate::model::read_long(bytes, &mut position)? {
            0 => None,
            1 => Some(crate::model::read_long(bytes, &mut position)?),
            _ => return Err("invalid statistics last-seen union".into()),
        };
        if position != bytes.len() {
            return Err("trailing SeriesStats bytes".into());
        }
        let record = Self {
            series_id,
            window_type,
            window_start_micros,
            window_end_micros,
            valid_count,
            nan_count,
            gap_count,
            min: moments[0],
            max: moments[1],
            mean: moments[2],
            m2: moments[3],
            last_seen_micros,
            sketch,
        };
        record.validate()?;
        Ok(record)
    }
}

// Copyright (C) 2004 by Sun Microsystems, Inc. All rights reserved.
// Permission to use, copy, modify, and distribute this software is freely granted,
// provided that this notice is preserved.
// Positive-base integer-exponent specialization of fdlibm e_pow.c:
// https://github.com/JuliaMath/openlibm/blob/v0.8.7/src/e_pow.c
fn sketch_power(base: f64, exponent: i32) -> f64 {
    if exponent == 0 {
        return 1.0;
    }
    if exponent == 1 {
        return base;
    }
    if exponent == -1 {
        return 1.0 / base;
    }
    if exponent == 2 {
        return base * base;
    }
    fn high(value: f64) -> i32 {
        (value.to_bits() >> 32) as i32
    }
    fn with_high(value: f64, word: i32) -> f64 {
        f64::from_bits(((word as u32 as u64) << 32) | (value.to_bits() & 0xffff_ffff))
    }
    fn high_only(value: f64) -> f64 {
        f64::from_bits(value.to_bits() & 0xffff_ffff_0000_0000)
    }
    let cp = f64::from_bits(0x3feec709dc3a03fd);
    let cp_h = f64::from_bits(0x3feec709e0000000);
    let cp_l = f64::from_bits(0xbe3e2fe0145b01f5);
    let log2 = f64::from_bits(0x3fe62e42fefa39ef);
    let log2_h = f64::from_bits(0x3fe62e4300000000);
    let log2_l = f64::from_bits(0xbe205c610ca86c39);
    let l = [
        0x3fe3333333333303,
        0x3fdb6db6db6fabff,
        0x3fd55555518f264d,
        0x3fd17460a91d4101,
        0x3fcd864a93c9db65,
        0x3fca7e284a454eef,
    ]
    .map(f64::from_bits);
    let p = [
        0x3fc555555555553e,
        0xbf66c16c16bebd93,
        0x3f11566aaf25de2c,
        0xbebbbd41c5d26bf1,
        0x3e66376972bea4d0,
    ]
    .map(f64::from_bits);
    let mut ix = high(base);
    let mut n = (ix >> 20) - 0x3ff;
    let j = ix & 0x000f_ffff;
    ix = j | 0x3ff00000;
    let k: usize;
    if j <= 0x3988e {
        k = 0;
    } else if j < 0xbb67a {
        k = 1;
    } else {
        k = 0;
        n += 1;
        ix -= 0x00100000;
    }
    let ax = with_high(base, ix);
    let bp = [1.0, 1.5];
    let dp_h = [0.0, f64::from_bits(0x3fe2b80340000000)];
    let dp_l = [0.0, f64::from_bits(0x3e4cfdeb43cfd006)];
    let mut u = ax - bp[k];
    let mut v = 1.0 / (ax + bp[k]);
    let ss = u * v;
    let s_h = high_only(ss);
    let mut t_h = with_high(
        0.0,
        ((ix >> 1) | 0x20000000) + 0x00080000 + ((k as i32) << 18),
    );
    let mut t_l = ax - (t_h - bp[k]);
    let s_l = v * ((u - s_h * t_h) - s_h * t_l);
    let mut s2 = ss * ss;
    let mut r =
        s2 * s2 * (l[0] + s2 * (l[1] + s2 * (l[2] + s2 * (l[3] + s2 * (l[4] + s2 * l[5])))));
    r += s_l * (s_h + ss);
    s2 = s_h * s_h;
    t_h = high_only(3.0 + s2 + r);
    t_l = r - ((t_h - 3.0) - s2);
    u = s_h * t_h;
    v = s_l * t_h + t_l * ss;
    let high_p = high_only(u + v);
    let low_p = v - (high_p - u);
    let z_h = cp_h * high_p;
    let z_l = cp_l * high_p + low_p * cp + dp_l[k];
    let mut t = n as f64;
    let t1 = high_only(((z_h + z_l) + dp_h[k]) + t);
    let t2 = z_l - (((t1 - t) - dp_h[k]) - z_h);
    let y = f64::from(exponent);
    let y1 = high_only(y);
    let p_l = (y - y1) * t1 + y * t2;
    let mut p_h = y1 * t1;
    let mut z = p_l + p_h;
    let mut j = high(z);
    let low = z.to_bits() as u32;
    if j >= 0x40900000 {
        if j != 0x40900000 || low != 0 || p_l + 8.008_566_259_537_294e-17 > z - p_h {
            return f64::INFINITY;
        }
    } else if (j & 0x7fff_ffff) >= 0x4090cc00
        && (j as u32 != 0xc090cc00 || low != 0 || p_l <= z - p_h)
    {
        return 0.0;
    }
    let i = j & 0x7fff_ffff;
    let mut k = (i >> 20) - 0x3ff;
    n = 0;
    if i > 0x3fe00000 {
        n = j + (0x00100000 >> (k + 1));
        k = ((n & 0x7fff_ffff) >> 20) - 0x3ff;
        t = with_high(0.0, n & !(0x000fffff >> k));
        n = ((n & 0x000fffff) | 0x00100000) >> (20 - k);
        if j < 0 {
            n = -n;
        }
        p_h -= t;
    }
    t = high_only(p_l + p_h);
    u = t * log2_h;
    v = (p_l - (t - p_h)) * log2 + t * log2_l;
    z = u + v;
    let w = v - (z - u);
    t = z * z;
    let t1 = z - t * (p[0] + t * (p[1] + t * (p[2] + t * (p[3] + t * p[4]))));
    r = (z * t1) / (t1 - 2.0) - (w + z * w);
    z = 1.0 - (r - z);
    j = high(z) + (n << 20);
    if j >> 20 <= 0 {
        let scaled = with_high(z, high(z) + ((n + 54) << 20));
        scaled * f64::from_bits(0x3c90000000000000)
    } else {
        with_high(z, j)
    }
}

impl QuantileSketch {
    fn validate(&self) -> Result<(), String> {
        Self::new(self.alpha)?;
        let mut count = self.zero_count;
        for store in [&self.positive, &self.negative] {
            if store.len() > MAX_BINS {
                return Err("sketch bin limit exceeded".into());
            }
            for &value in store.values() {
                if value == 0 {
                    return Err("sketch bin count must be positive".into());
                }
                count = count.checked_add(value).ok_or("sketch count overflow")?;
            }
        }
        if count != self.count || count > i64::MAX as u64 {
            return Err("sketch sample count differs from its stores".into());
        }
        Ok(())
    }
}

impl StatsAccumulator {
    fn validate(&self, alpha: f64) -> Result<(), String> {
        let count = self
            .valid_count
            .checked_add(self.nan_count)
            .ok_or("statistics count overflow")?;
        if count > i64::MAX as u64 || self.gap_count > i64::MAX as u64 {
            return Err("statistics count exceeds signed long range".into());
        }
        if (count == 0 && (self.first_micros != i64::MAX || self.last_micros != i64::MIN))
            || (count > 0 && self.first_micros > self.last_micros)
        {
            return Err("invalid statistics timestamp bounds".into());
        }
        if self.valid_count == 0 {
            if !self.min.is_nan()
                || !self.max.is_nan()
                || self.mean.to_bits() != 0
                || self.m2.to_bits() != 0
            {
                return Err("empty statistics moments are inconsistent".into());
            }
        } else if !self.min.is_finite() || !self.max.is_finite() || self.min > self.max {
            return Err("invalid statistics extrema".into());
        }
        match &self.sketch {
            Some(sketch) => {
                sketch.validate()?;
                if sketch.alpha.to_bits() != alpha.to_bits() || sketch.count != self.valid_count {
                    return Err("statistics sketch differs from its accumulator".into());
                }
            }
            None if alpha > 0.0 => return Err("statistics sketch is missing".into()),
            None => (),
        }
        Ok(())
    }
}

impl StatsEngine {
    pub fn snapshot(&self) -> Result<Value, String> {
        self.validate()?;
        serde_json::to_value(self)
            .map(|engine| json!({"schema":"opentsx.rust.stats-state.v1","engine":engine}))
            .map_err(|e| format!("cannot encode statistics state: {e}"))
    }

    pub fn restore(&mut self, value: &Value) -> Result<(), String> {
        let object = value
            .as_object()
            .ok_or("statistics state must be an object")?;
        if object.len() != 2
            || value["schema"] != "opentsx.rust.stats-state.v1"
            || !object.contains_key("engine")
        {
            return Err("unsupported statistics state schema".into());
        }
        let mut candidate: Self = serde_json::from_value(value["engine"].clone())
            .map_err(|e| format!("invalid statistics state: {e}"))?;
        if candidate.windows != self.windows
            || candidate.alpha.to_bits() != self.alpha.to_bits()
            || candidate.expected_interval != self.expected_interval
            || candidate.lateness_micros != self.lateness_micros
        {
            return Err("statistics state configuration differs from the plan".into());
        }
        candidate.recent_ids = candidate.recent.iter().cloned().collect();
        candidate.validate()?;
        *self = candidate;
        Ok(())
    }

    fn validate(&self) -> Result<(), String> {
        Self::new(
            self.windows.clone(),
            self.alpha,
            self.expected_interval,
            self.lateness_micros,
        )?;
        let widths: std::collections::BTreeSet<_> =
            self.windows.iter().map(|(_, size)| *size).collect();
        if self.recent.len() > 100_000
            || self
                .recent
                .iter()
                .collect::<std::collections::BTreeSet<_>>()
                .len()
                != self.recent.len()
        {
            return Err("invalid statistics deduplication state".into());
        }
        if self.recent_ids != self.recent.iter().cloned().collect() {
            return Err("statistics deduplication index differs from its queue".into());
        }
        for (id, state) in &self.series {
            if id.is_empty() {
                return Err("statistics series identity must not be empty".into());
            }
            state.global.validate(self.alpha)?;
            if state.learned_interval < 0 || state.progress < state.last_ts {
                return Err("invalid statistics progress".into());
            }
            if state.global.last_micros != state.last_ts {
                return Err("statistics last timestamp differs from global state".into());
            }
            for (&size, &end) in &state.closed_up_to {
                if !widths.contains(&size) || end.rem_euclid(size) != 0 {
                    return Err("invalid closed statistics window".into());
                }
            }
            for (&size, open) in &state.open {
                if !widths.contains(&size) {
                    return Err("statistics state has an unknown window width".into());
                }
                for (&start, accumulator) in open {
                    let end = start
                        .checked_add(size)
                        .ok_or("statistics window overflow")?;
                    if start.rem_euclid(size) != 0
                        || end <= *state.closed_up_to.get(&size).unwrap_or(&i64::MIN)
                    {
                        return Err("invalid open statistics window".into());
                    }
                    accumulator.validate(self.alpha)?;
                    if accumulator.first_micros < start || accumulator.last_micros >= end {
                        return Err("statistics samples lie outside their window".into());
                    }
                }
            }
        }
        Ok(())
    }
}

pub fn canonical_duration(micros: i64) -> String {
    let hours = micros / 3_600_000_000;
    let minutes = (micros / 60_000_000) % 60;
    let seconds = (micros / 1_000_000) % 60;
    let fraction = micros % 1_000_000;
    let mut out = "PT".to_owned();
    if hours != 0 {
        out.push_str(&format!("{hours}H"));
    }
    if minutes != 0 {
        out.push_str(&format!("{minutes}M"));
    }
    if seconds != 0 || fraction != 0 || (hours == 0 && minutes == 0) {
        out.push_str(&seconds.to_string());
        if fraction != 0 {
            out.push('.');
            out.push_str(format!("{fraction:06}").trim_end_matches('0'));
        }
        out.push('S');
    }
    out
}

impl StatsRecord {
    pub fn validate(&self) -> Result<(), String> {
        if self.series_id.is_empty()
            || self.window_type.is_empty()
            || self.window_start_micros >= self.window_end_micros
        {
            return Err("statistics identity or window is invalid".into());
        }
        let count = self
            .valid_count
            .checked_add(self.nan_count)
            .ok_or("statistics count overflow")?;
        if count > i64::MAX as u64 || self.gap_count > i64::MAX as u64 {
            return Err("statistics count exceeds signed long range".into());
        }
        if self.valid_count == 0 {
            if [self.min, self.max, self.mean, self.m2]
                .iter()
                .any(|value| !value.is_nan())
            {
                return Err("empty statistics must contain NaN moments".into());
            }
        } else if !self.min.is_finite() || !self.max.is_finite() || self.min > self.max {
            return Err("invalid statistics extrema".into());
        }
        if (count == 0) != self.last_seen_micros.is_none()
            || self.last_seen_micros.is_some_and(|last| {
                last < self.window_start_micros || last >= self.window_end_micros
            })
        {
            return Err("statistics last-seen timestamp differs from its window".into());
        }
        if let Some(sketch) = &self.sketch {
            sketch.validate()?;
            if sketch.count != self.valid_count {
                return Err("statistics sketch count differs from validCount".into());
            }
        }
        Ok(())
    }
}

impl StatsEngine {
    pub fn series_ids(&self) -> BTreeSet<String> {
        self.series.keys().cloned().collect()
    }
}
