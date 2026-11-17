use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct SeriesKey {
    pub metric: String,
    pub tags: BTreeMap<String, String>,
    pub unit: Option<String>,
    pub series_id: String,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Summary {
    pub valid_count: i64,
    pub nan_count: i64,
    #[serde(with = "float_bits")]
    pub min: f64,
    #[serde(with = "float_bits")]
    pub max: f64,
    #[serde(with = "float_bits")]
    pub mean: f64,
    #[serde(with = "float_bits")]
    pub m2: f64,
    pub sax: Option<String>,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Provenance {
    pub source: String,
    pub producer: String,
    pub created_at: i64,
    pub pipeline: Option<String>,
    pub parent_episode_id: Option<String>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum TimeEncoding {
    Regular,
    IrregularDelta,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum SegmentationStrategy {
    FixedDuration,
    FixedCount,
    FeatureExtremum,
    FeatureThreshold,
    Manual,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Segmentation {
    pub strategy: SegmentationStrategy,
    pub params: BTreeMap<String, String>,
    pub anchor_ts: Option<i64>,
    pub source_episode_id: Option<String>,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Episode {
    pub episode_id: String,
    pub series: SeriesKey,
    pub t_start: i64,
    pub t_end: i64,
    pub count: i32,
    pub time_encoding: TimeEncoding,
    pub interval_micros: Option<i64>,
    pub time_deltas: Option<Vec<u8>>,
    #[serde(with = "float_vector_bits")]
    pub values: Vec<f64>,
    pub quality: Option<Vec<u8>>,
    pub summary: Summary,
    pub segmentation: Option<Segmentation>,
    pub labels: BTreeMap<String, String>,
    pub bucket_id: Option<String>,
    pub revision: i32,
    pub provenance: Provenance,
}

impl Episode {
    pub fn timestamps(&self) -> Result<Vec<i64>, String> {
        if self.count < 0 || self.count as usize != self.values.len() {
            return Err("episode count differs from values".into());
        }
        match self.time_encoding {
            TimeEncoding::Regular => {
                let interval = self
                    .interval_micros
                    .filter(|v| *v > 0)
                    .ok_or("regular episode needs a positive interval")?;
                if self.time_deltas.is_some() {
                    return Err("regular episode cannot contain time deltas".into());
                }
                (0..self.count)
                    .map(|index| {
                        interval
                            .checked_mul(i64::from(index))
                            .and_then(|offset| self.t_start.checked_add(offset))
                            .ok_or_else(|| "episode timestamp overflow".into())
                    })
                    .collect()
            }
            TimeEncoding::IrregularDelta => {
                if self.interval_micros.is_some() || self.count == 0 {
                    return Err("irregular episode needs values and no regular interval".into());
                }
                let bytes = self
                    .time_deltas
                    .as_deref()
                    .ok_or("irregular episode needs time deltas")?;
                let mut position = 0;
                let mut delta = 0i64;
                let mut times = Vec::with_capacity(self.values.len());
                times.push(self.t_start);
                for index in 1..self.count {
                    let value = read_long(bytes, &mut position)?;
                    delta = if index == 1 {
                        value
                    } else {
                        delta.checked_add(value).ok_or("episode delta overflow")?
                    };
                    if delta <= 0 {
                        return Err("episode timestamps must be strictly increasing".into());
                    }
                    times.push(
                        times
                            .last()
                            .ok_or("missing previous timestamp")?
                            .checked_add(delta)
                            .ok_or("episode timestamp overflow")?,
                    );
                }
                if position != bytes.len() {
                    return Err("trailing bytes in episode time deltas".into());
                }
                Ok(times)
            }
        }
    }

    pub fn validate(&self, max_samples: usize) -> Result<(), String> {
        if self.values.len() > max_samples {
            return Err("episode sample limit exceeded".into());
        }
        if self.series.series_id != series_id(&self.series.metric, &self.series.tags)? {
            return Err("episode series identity does not match its metric and tags".into());
        }
        if self.t_start >= self.t_end {
            return Err("episode start must be before its end".into());
        }
        let times = self.timestamps()?;
        if times.last().is_some_and(|last| *last >= self.t_end) {
            return Err("episode end cuts off samples".into());
        }
        if self
            .quality
            .as_ref()
            .is_some_and(|values| values.len() != self.values.len())
        {
            return Err("episode quality differs from sample count".into());
        }
        if self.summary.valid_count < 0
            || self.summary.nan_count < 0
            || self.summary.valid_count.checked_add(self.summary.nan_count)
                != Some(i64::from(self.count))
        {
            return Err("episode summary counts differ from sample count".into());
        }
        Ok(())
    }
}

pub fn canonical_series(metric: &str, tags: &BTreeMap<String, String>) -> Result<String, String> {
    if metric.is_empty() || tags.keys().any(String::is_empty) {
        return Err("series metric and tag names must not be empty".into());
    }
    fn escape(text: &str) -> String {
        let mut out = String::new();
        for character in text.chars() {
            if matches!(character, '\\' | '{' | '}' | ',' | '=') {
                out.push('\\');
            }
            out.push(character);
        }
        out
    }
    let fields = tags
        .iter()
        .map(|(key, value)| format!("{}={}", escape(key), escape(value)))
        .collect::<Vec<_>>()
        .join(",");
    Ok(format!("{}{{{fields}}}", escape(metric)))
}

pub fn series_id(metric: &str, tags: &BTreeMap<String, String>) -> Result<String, String> {
    Ok(xxh64_hex(canonical_series(metric, tags)?.as_bytes()))
}

pub fn xxh64_hex(bytes: &[u8]) -> String {
    format!("{:016x}", xxhash_rust::xxh64::xxh64(bytes, 0))
}

pub fn summary(values: &[f64]) -> Summary {
    let mut out = Summary {
        valid_count: 0,
        nan_count: 0,
        min: f64::NAN,
        max: f64::NAN,
        mean: 0.0,
        m2: 0.0,
        sax: None,
    };
    for &value in values {
        if value.is_nan() {
            out.nan_count += 1;
            continue;
        }
        out.valid_count += 1;
        let delta = value - out.mean;
        out.mean += delta / out.valid_count as f64;
        out.m2 += delta * (value - out.mean);
        if out.valid_count == 1 {
            out.min = value;
            out.max = value;
        } else {
            out.min = if value == 0.0 && out.min == 0.0 {
                f64::from_bits(value.to_bits() | out.min.to_bits())
            } else {
                out.min.min(value)
            };
            out.max = if value == 0.0 && out.max == 0.0 {
                f64::from_bits(value.to_bits() & out.max.to_bits())
            } else {
                out.max.max(value)
            };
        }
    }
    if out.valid_count == 0 {
        out.mean = f64::NAN;
        out.m2 = f64::NAN;
    }
    out
}

pub(crate) fn read_long(bytes: &[u8], position: &mut usize) -> Result<i64, String> {
    let mut value = 0u64;
    for index in 0..10 {
        let byte = *bytes.get(*position).ok_or("truncated Avro integer")?;
        *position += 1;
        if index == 9 && byte > 1 {
            return Err("Avro integer overflow".into());
        }
        value |= u64::from(byte & 127) << (index * 7);
        if byte & 128 == 0 {
            return Ok((value >> 1) as i64 ^ -((value & 1) as i64));
        }
    }
    Err("Avro integer overflow".into())
}

pub(crate) fn write_long(value: i64, bytes: &mut Vec<u8>) {
    let mut zigzag = ((value as u64) << 1) ^ ((value >> 63) as u64);
    while zigzag > 127 {
        bytes.push((zigzag as u8 & 127) | 128);
        zigzag >>= 7;
    }
    bytes.push(zigzag as u8);
}

mod float_bits {
    use serde::{Deserialize, Deserializer, Serializer};

    pub fn serialize<S: Serializer>(value: &f64, serializer: S) -> Result<S::Ok, S::Error> {
        serializer.serialize_str(&format!("{:016x}", value.to_bits()))
    }

    pub fn deserialize<'de, D: Deserializer<'de>>(deserializer: D) -> Result<f64, D::Error> {
        let text = String::deserialize(deserializer)?;
        parse(&text).map_err(serde::de::Error::custom)
    }

    pub(super) fn parse(text: &str) -> Result<f64, &'static str> {
        if text.len() != 16
            || !text
                .bytes()
                .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
        {
            return Err("floating point state must contain 16 lowercase hexadecimal digits");
        }
        u64::from_str_radix(text, 16)
            .map(f64::from_bits)
            .map_err(|_| "invalid floating point state")
    }
}

mod float_vector_bits {
    use serde::{Deserialize, Deserializer, Serialize, Serializer};

    pub fn serialize<S: Serializer>(values: &[f64], serializer: S) -> Result<S::Ok, S::Error> {
        values
            .iter()
            .map(|value| format!("{:016x}", value.to_bits()))
            .collect::<Vec<_>>()
            .serialize(serializer)
    }

    pub fn deserialize<'de, D: Deserializer<'de>>(deserializer: D) -> Result<Vec<f64>, D::Error> {
        Vec::<String>::deserialize(deserializer)?
            .iter()
            .map(|text| super::float_bits::parse(text).map_err(serde::de::Error::custom))
            .collect()
    }
}
