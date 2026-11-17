use crate::model::{
    read_long, write_long, Episode, Provenance, Segmentation, SegmentationStrategy, SeriesKey,
    Summary, TimeEncoding,
};
use std::collections::BTreeMap;

pub const FINGERPRINT: &str = "8985f02b48c77195";
pub const SCHEMA_NAME: &str = "org.opentsx.model.v2.Episode";
const HEADER: [u8; 10] = [0xc3, 1, 0x95, 0x71, 0xc7, 0x48, 0x2b, 0xf0, 0x85, 0x89];

pub fn decode(bytes: &[u8], max_bytes: usize, max_samples: usize) -> Result<Episode, String> {
    if bytes.len() > max_bytes {
        return Err("episode payload limit exceeded".into());
    }
    if bytes.get(..2) != Some(&HEADER[..2]) {
        return Err("payload is not Avro single-object encoded".into());
    }
    if bytes.get(..10) != Some(&HEADER[..]) {
        return Err("unsupported Episode schema fingerprint".into());
    }
    let mut input = Decoder {
        bytes,
        position: 10,
    };
    let episode_id = input.string()?;
    let series = SeriesKey {
        metric: input.string()?,
        tags: input.map()?,
        unit: input.optional(Decoder::string)?,
        series_id: input.string()?,
    };
    let t_start = input.long()?;
    let t_end = input.long()?;
    let count = input.int()?;
    if count < 0 || count as usize > max_samples {
        return Err("episode sample limit exceeded".into());
    }
    let time_encoding = match input.int()? {
        0 => TimeEncoding::Regular,
        1 => TimeEncoding::IrregularDelta,
        _ => return Err("unknown episode time encoding".into()),
    };
    let interval_micros = input.optional(Decoder::long)?;
    let time_deltas = input.optional(Decoder::blob)?;
    let values = input.doubles(count as usize)?;
    let quality = input.optional(Decoder::blob)?;
    let summary = Summary {
        valid_count: input.long()?,
        nan_count: input.long()?,
        min: input.double()?,
        max: input.double()?,
        mean: input.double()?,
        m2: input.double()?,
        sax: input.optional(Decoder::string)?,
    };
    let segmentation = input.optional(|input| {
        let strategy = match input.int()? {
            0 => SegmentationStrategy::FixedDuration,
            1 => SegmentationStrategy::FixedCount,
            2 => SegmentationStrategy::FeatureExtremum,
            3 => SegmentationStrategy::FeatureThreshold,
            4 => SegmentationStrategy::Manual,
            _ => return Err("unknown segmentation strategy".into()),
        };
        Ok(Segmentation {
            strategy,
            params: input.map()?,
            anchor_ts: input.optional(Decoder::long)?,
            source_episode_id: input.optional(Decoder::string)?,
        })
    })?;
    let labels = input.map()?;
    let bucket_id = input.optional(Decoder::string)?;
    let revision = input.int()?;
    let provenance = Provenance {
        source: input.string()?,
        producer: input.string()?,
        created_at: input.long()?,
        pipeline: input.optional(Decoder::string)?,
        parent_episode_id: input.optional(Decoder::string)?,
    };
    if input.position != bytes.len() {
        return Err("trailing bytes after Episode".into());
    }
    let episode = Episode {
        episode_id,
        series,
        t_start,
        t_end,
        count,
        time_encoding,
        interval_micros,
        time_deltas,
        values,
        quality,
        summary,
        segmentation,
        labels,
        bucket_id,
        revision,
        provenance,
    };
    episode.validate(max_samples)?;
    Ok(episode)
}

pub fn encode(episode: &Episode) -> Result<Vec<u8>, String> {
    episode.validate(usize::MAX)?;
    let mut out = Encoder {
        bytes: HEADER.to_vec(),
    };
    out.string(&episode.episode_id)?;
    out.string(&episode.series.metric)?;
    out.map(&episode.series.tags)?;
    out.optional(episode.series.unit.as_ref(), |out, value| out.string(value))?;
    out.string(&episode.series.series_id)?;
    out.long(episode.t_start);
    out.long(episode.t_end);
    out.long(i64::from(episode.count));
    out.long(match episode.time_encoding {
        TimeEncoding::Regular => 0,
        TimeEncoding::IrregularDelta => 1,
    });
    out.optional(episode.interval_micros.as_ref(), |out, value| {
        out.long(*value);
        Ok(())
    })?;
    out.optional(episode.time_deltas.as_ref(), |out, value| out.blob(value))?;
    out.length(episode.values.len())?;
    for value in &episode.values {
        out.double(*value);
    }
    if !episode.values.is_empty() {
        out.long(0);
    }
    out.optional(episode.quality.as_ref(), |out, value| out.blob(value))?;
    out.long(episode.summary.valid_count);
    out.long(episode.summary.nan_count);
    out.double(episode.summary.min);
    out.double(episode.summary.max);
    out.double(episode.summary.mean);
    out.double(episode.summary.m2);
    out.optional(episode.summary.sax.as_ref(), |out, value| out.string(value))?;
    out.optional(episode.segmentation.as_ref(), |out, value| {
        out.long(match value.strategy {
            SegmentationStrategy::FixedDuration => 0,
            SegmentationStrategy::FixedCount => 1,
            SegmentationStrategy::FeatureExtremum => 2,
            SegmentationStrategy::FeatureThreshold => 3,
            SegmentationStrategy::Manual => 4,
        });
        out.map(&value.params)?;
        out.optional(value.anchor_ts.as_ref(), |out, value| {
            out.long(*value);
            Ok(())
        })?;
        out.optional(value.source_episode_id.as_ref(), |out, value| {
            out.string(value)
        })
    })?;
    out.map(&episode.labels)?;
    out.optional(episode.bucket_id.as_ref(), |out, value| out.string(value))?;
    out.long(i64::from(episode.revision));
    out.string(&episode.provenance.source)?;
    out.string(&episode.provenance.producer)?;
    out.long(episode.provenance.created_at);
    out.optional(episode.provenance.pipeline.as_ref(), |out, value| {
        out.string(value)
    })?;
    out.optional(
        episode.provenance.parent_episode_id.as_ref(),
        |out, value| out.string(value),
    )?;
    Ok(out.bytes)
}

struct Decoder<'a> {
    bytes: &'a [u8],
    position: usize,
}

impl Decoder<'_> {
    fn long(&mut self) -> Result<i64, String> {
        read_long(self.bytes, &mut self.position)
    }

    fn int(&mut self) -> Result<i32, String> {
        i32::try_from(self.long()?).map_err(|_| "Avro integer exceeds int32".into())
    }

    fn take(&mut self, length: usize) -> Result<&[u8], String> {
        let end = self
            .position
            .checked_add(length)
            .ok_or("Avro length overflow")?;
        let result = self
            .bytes
            .get(self.position..end)
            .ok_or("truncated Avro value")?;
        self.position = end;
        Ok(result)
    }

    fn length(&mut self) -> Result<usize, String> {
        usize::try_from(self.long()?).map_err(|_| "negative or overflowing Avro length".into())
    }

    fn blob(&mut self) -> Result<Vec<u8>, String> {
        let length = self.length()?;
        Ok(self.take(length)?.to_vec())
    }

    fn string(&mut self) -> Result<String, String> {
        String::from_utf8(self.blob()?).map_err(|_| "invalid UTF-8 in Avro string".into())
    }

    fn double(&mut self) -> Result<f64, String> {
        let bytes = self.take(8)?;
        Ok(f64::from_le_bytes(
            bytes.try_into().map_err(|_| "truncated Avro double")?,
        ))
    }

    fn optional<T>(
        &mut self,
        read: impl FnOnce(&mut Self) -> Result<T, String>,
    ) -> Result<Option<T>, String> {
        match self.long()? {
            0 => Ok(None),
            1 => read(self).map(Some),
            _ => Err("unknown Avro union branch".into()),
        }
    }

    fn block(&mut self) -> Result<(usize, Option<usize>), String> {
        let count = self.long()?;
        if count >= 0 {
            return Ok((
                usize::try_from(count).map_err(|_| "Avro block count overflow")?,
                None,
            ));
        }
        let count = usize::try_from(count.checked_neg().ok_or("Avro block count overflow")?)
            .map_err(|_| "Avro block count overflow")?;
        let length = self.length()?;
        let end = self
            .position
            .checked_add(length)
            .filter(|end| *end <= self.bytes.len())
            .ok_or("Avro block size exceeds payload")?;
        Ok((count, Some(end)))
    }

    fn end_block(&self, end: Option<usize>) -> Result<(), String> {
        if end.is_some_and(|end| self.position != end) {
            return Err("Avro block size differs from contents".into());
        }
        Ok(())
    }

    fn doubles(&mut self, expected: usize) -> Result<Vec<f64>, String> {
        let mut values = Vec::new();
        loop {
            let (count, end) = self.block()?;
            if count == 0 {
                break;
            }
            if count > expected.saturating_sub(values.len())
                || count > (self.bytes.len() - self.position) / 8
            {
                return Err("Avro values exceed declared sample count or payload".into());
            }
            values.reserve(count);
            for _ in 0..count {
                values.push(self.double()?);
            }
            self.end_block(end)?;
        }
        if values.len() != expected {
            return Err("episode count differs from values".into());
        }
        Ok(values)
    }

    fn map(&mut self) -> Result<BTreeMap<String, String>, String> {
        let mut values = BTreeMap::new();
        loop {
            let (count, end) = self.block()?;
            if count == 0 {
                break;
            }
            if count > (self.bytes.len() - self.position) / 2 {
                return Err("Avro map count exceeds payload".into());
            }
            for _ in 0..count {
                let key = self.string()?;
                let value = self.string()?;
                if values.insert(key, value).is_some() {
                    return Err("duplicate Avro map key".into());
                }
            }
            self.end_block(end)?;
        }
        Ok(values)
    }
}

struct Encoder {
    bytes: Vec<u8>,
}

impl Encoder {
    fn long(&mut self, value: i64) {
        write_long(value, &mut self.bytes);
    }

    fn length(&mut self, value: usize) -> Result<(), String> {
        self.long(i64::try_from(value).map_err(|_| "Avro length overflow")?);
        Ok(())
    }

    fn double(&mut self, value: f64) {
        self.bytes.extend(value.to_le_bytes());
    }

    fn blob(&mut self, bytes: &[u8]) -> Result<(), String> {
        self.length(bytes.len())?;
        self.bytes.extend(bytes);
        Ok(())
    }

    fn string(&mut self, text: &str) -> Result<(), String> {
        self.blob(text.as_bytes())
    }

    fn optional<T>(
        &mut self,
        value: Option<&T>,
        write: impl FnOnce(&mut Self, &T) -> Result<(), String>,
    ) -> Result<(), String> {
        match value {
            None => {
                self.long(0);
                Ok(())
            }
            Some(value) => {
                self.long(1);
                write(self, value)
            }
        }
    }

    fn map(&mut self, values: &BTreeMap<String, String>) -> Result<(), String> {
        self.length(values.len())?;
        for (key, value) in values {
            self.string(key)?;
            self.string(value)?;
        }
        if !values.is_empty() {
            self.long(0);
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::model::{canonical_series, series_id, summary};
    use serde_json::Value;

    fn hex(bytes: &[u8]) -> String {
        bytes.iter().map(|byte| format!("{byte:02x}")).collect()
    }

    fn unhex(text: &str) -> Vec<u8> {
        assert_eq!(text.len() % 2, 0);
        (0..text.len())
            .step_by(2)
            .map(|index| u8::from_str_radix(&text[index..index + 2], 16).unwrap())
            .collect()
    }

    fn vectors() -> Value {
        let text =
            include_str!("../../../opentsx-model/src/test/resources/testvectors/wire-format.json");
        let normalized = text
            .lines()
            .map(|line| {
                if line.trim() == "NaN," {
                    line.replace("NaN", "\"NaN\"")
                } else {
                    line.to_owned()
                }
            })
            .collect::<Vec<_>>()
            .join("\n");
        serde_json::from_str(&normalized).unwrap()
    }

    fn episode() -> Episode {
        let values = vec![
            1.0,
            f64::from_bits(0x7ff8000000000011),
            -0.0,
            std::f64::consts::PI,
        ];
        let tags = BTreeMap::from([("a".into(), "first".into()), ("ä".into(), "last".into())]);
        Episode {
            episode_id: "wire-test".into(),
            series: SeriesKey {
                metric: "learning.value".into(),
                series_id: series_id("learning.value", &tags).unwrap(),
                tags,
                unit: Some("kW".into()),
            },
            t_start: -100,
            t_end: -60,
            count: values.len() as i32,
            time_encoding: TimeEncoding::Regular,
            interval_micros: Some(10),
            time_deltas: None,
            quality: Some(vec![0, 1, 0, 0]),
            summary: summary(&values),
            values,
            segmentation: Some(Segmentation {
                strategy: SegmentationStrategy::Manual,
                params: BTreeMap::from([("duration".into(), "PT1S".into())]),
                anchor_ts: Some(-95),
                source_episode_id: Some("parent-segment".into()),
            }),
            labels: BTreeMap::from([
                ("flow".into(), "test".into()),
                ("grade".into(), "clean".into()),
            ]),
            bucket_id: Some("bucket".into()),
            revision: 7,
            provenance: Provenance {
                source: "learning".into(),
                producer: "wire-test".into(),
                created_at: 9007199254740993,
                pipeline: Some("pipeline".into()),
                parent_episode_id: Some("parent".into()),
            },
        }
    }

    #[test]
    fn episode_wire_vectors_round_trip_byte_for_byte_and_preserve_full_fields() {
        let document = vectors();
        assert_eq!(document["fingerprints"]["Episode"], FINGERPRINT);
        let cases = document["episodes"].as_array().unwrap();
        assert_eq!(cases.len(), 2);
        for case in cases {
            let expected = &case["spec"];
            let payload = unhex(case["hex"].as_str().unwrap());
            let value = decode(&payload, 1_000_000, 100_000).unwrap();
            assert_eq!(hex(&encode(&value).unwrap()), case["hex"].as_str().unwrap());
            assert_eq!(value.episode_id, expected["episodeId"].as_str().unwrap());
            assert_eq!(value.series.metric, expected["metric"].as_str().unwrap());
            assert_eq!(value.series.unit.as_deref(), expected["unit"].as_str());
            assert_eq!(
                serde_json::to_value(&value.series.tags).unwrap(),
                expected["tags"]
            );
            assert_eq!(
                serde_json::to_value(&value.labels).unwrap(),
                expected["labels"]
            );
            assert_eq!(value.bucket_id.as_deref(), expected["bucketId"].as_str());
            assert_eq!(
                value.provenance.source,
                expected["source"].as_str().unwrap()
            );
            assert_eq!(
                value.provenance.producer,
                expected["producer"].as_str().unwrap()
            );
            assert_eq!(
                value.provenance.created_at,
                expected["createdAtUs"].as_i64().unwrap()
            );
            assert_eq!(
                value.count as usize,
                expected["values"].as_array().unwrap().len()
            );
            for (actual, expected) in value
                .values
                .iter()
                .zip(expected["values"].as_array().unwrap())
            {
                if expected.as_str() == Some("NaN") {
                    assert!(actual.is_nan());
                } else {
                    assert_eq!(actual.to_bits(), expected.as_f64().unwrap().to_bits());
                }
            }
            let times = value.timestamps().unwrap();
            if expected["kind"] == "irregular" {
                assert_eq!(
                    times,
                    expected["timestampsUs"]
                        .as_array()
                        .unwrap()
                        .iter()
                        .map(|n| n.as_i64().unwrap())
                        .collect::<Vec<_>>()
                );
                assert_eq!(
                    value.quality,
                    Some(unhex(expected["qualityHex"].as_str().unwrap()))
                );
            } else {
                assert_eq!(
                    times,
                    (0..value.count)
                        .map(|index| expected["tStartUs"].as_i64().unwrap()
                            + i64::from(index) * expected["intervalUs"].as_i64().unwrap())
                        .collect::<Vec<_>>()
                );
                assert!(value.values[4].is_sign_negative());
            }
            let segment = value.segmentation.as_ref().unwrap();
            assert_eq!(
                serde_json::to_value(&segment.params).unwrap(),
                expected["segmentation"]["params"]
            );
            assert_eq!(
                segment.anchor_ts,
                expected["segmentation"]["anchorTs"].as_i64()
            );
            assert_eq!(
                segment.source_episode_id.as_deref(),
                expected["segmentation"]["sourceEpisodeId"].as_str()
            );
        }
    }

    #[test]
    fn all_series_identity_vectors_match_without_unicode_normalization() {
        let cases: Value = serde_json::from_str(include_str!(
            "../../../opentsx-model/src/test/resources/testvectors/series-id.json"
        ))
        .unwrap();
        assert_eq!(cases.as_array().unwrap().len(), 6);
        for case in cases.as_array().unwrap() {
            let tags: BTreeMap<String, String> =
                serde_json::from_value(case["tags"].clone()).unwrap();
            let metric = case["metric"].as_str().unwrap();
            assert_eq!(
                canonical_series(metric, &tags).unwrap(),
                case["canonical"].as_str().unwrap()
            );
            assert_eq!(
                series_id(metric, &tags).unwrap(),
                case["seriesId"].as_str().unwrap()
            );
        }
        assert!(series_id("", &BTreeMap::new()).is_err());
        assert!(series_id("metric", &BTreeMap::from([("".into(), "value".into())])).is_err());
    }

    #[test]
    fn every_episode_field_and_ieee_payload_survives_serde_and_avro() {
        let value = episode();
        let bytes = encode(&value).unwrap();
        let decoded = decode(&bytes, bytes.len(), value.values.len()).unwrap();
        assert_eq!(
            serde_json::to_value(&decoded).unwrap(),
            serde_json::to_value(&value).unwrap()
        );
        assert_eq!(decoded.values[1].to_bits(), 0x7ff8000000000011);
        assert_eq!(decoded.values[2].to_bits(), (-0.0f64).to_bits());
        let serialized = serde_json::to_string(&value).unwrap();
        let restored: Episode = serde_json::from_str(&serialized).unwrap();
        assert_eq!(encode(&restored).unwrap(), bytes);
        let mut damaged: Value = serde_json::from_str(&serialized).unwrap();
        damaged["values"][0] = Value::Null;
        assert!(serde_json::from_value::<Episode>(damaged).is_err());
    }

    #[test]
    fn current_codec_preserves_every_enum_and_optional_field() {
        let mut value = episode();
        for strategy in [
            SegmentationStrategy::FixedDuration,
            SegmentationStrategy::FixedCount,
            SegmentationStrategy::FeatureExtremum,
            SegmentationStrategy::FeatureThreshold,
            SegmentationStrategy::Manual,
        ] {
            value.segmentation.as_mut().unwrap().strategy = strategy;
            let bytes = encode(&value).unwrap();
            assert_eq!(
                decode(&bytes, bytes.len(), 10)
                    .unwrap()
                    .segmentation
                    .unwrap()
                    .strategy,
                strategy
            );
        }
        value.series.unit = None;
        value.segmentation = None;
        value.quality = None;
        value.bucket_id = None;
        value.provenance.pipeline = None;
        value.provenance.parent_episode_id = None;
        value.summary.sax = Some("2x4:ad".into());
        value.labels.clear();
        let bytes = encode(&value).unwrap();
        assert_eq!(
            encode(&decode(&bytes, bytes.len(), 10).unwrap()).unwrap(),
            bytes
        );
    }

    #[test]
    fn malformed_truncated_oversized_and_unknown_payloads_fail_closed() {
        let bytes = encode(&episode()).unwrap();
        for end in 0..bytes.len() {
            assert!(
                decode(&bytes[..end], bytes.len(), 10).is_err(),
                "prefix {end}"
            );
        }
        assert!(decode(&bytes, bytes.len() - 1, 10)
            .unwrap_err()
            .contains("payload limit"));
        assert!(decode(&bytes, bytes.len(), 3)
            .unwrap_err()
            .contains("sample limit"));
        let mut damaged = bytes.clone();
        damaged[2] ^= 1;
        assert!(decode(&damaged, damaged.len(), 10)
            .unwrap_err()
            .contains("fingerprint"));
        let mut trailing = bytes;
        trailing.push(0);
        assert!(decode(&trailing, trailing.len(), 10)
            .unwrap_err()
            .contains("trailing bytes"));
        let mut bad = Decoder {
            bytes: &[0xff; 10],
            position: 0,
        };
        assert!(bad.long().is_err());
        let mut bad = Decoder {
            bytes: &[2, 0xff],
            position: 0,
        };
        assert!(bad.string().is_err());
        let mut bad = Decoder {
            bytes: &[4],
            position: 0,
        };
        assert!(bad.optional(Decoder::long).is_err());
    }

    #[test]
    fn negative_and_multiple_avro_blocks_are_checked_and_canonicalized() {
        let mut body = Encoder { bytes: Vec::new() };
        body.string("z").unwrap();
        body.string("last").unwrap();
        body.string("a").unwrap();
        body.string("first").unwrap();
        let mut encoded = Encoder { bytes: Vec::new() };
        encoded.long(-2);
        encoded.length(body.bytes.len()).unwrap();
        encoded.bytes.extend(body.bytes);
        encoded.long(0);
        let mut input = Decoder {
            bytes: &encoded.bytes,
            position: 0,
        };
        let map = input.map().unwrap();
        assert_eq!(map.keys().cloned().collect::<Vec<_>>(), ["a", "z"]);
        let mut canonical = Encoder { bytes: Vec::new() };
        canonical.map(&map).unwrap();
        assert_eq!(canonical.bytes[0], 4);
        encoded.bytes[1] += 2;
        assert!(Decoder {
            bytes: &encoded.bytes,
            position: 0
        }
        .map()
        .is_err());
        let duplicate = [2, 2, b'a', 2, b'x', 2, 2, b'a', 2, b'y', 0];
        assert!(Decoder {
            bytes: &duplicate,
            position: 0
        }
        .map()
        .is_err());
        let mut encoded = Encoder { bytes: Vec::new() };
        encoded.long(-1);
        encoded.long(8);
        encoded.double(1.5);
        encoded.long(1);
        encoded.double(-0.0);
        encoded.long(0);
        let values = Decoder {
            bytes: &encoded.bytes,
            position: 0,
        }
        .doubles(2)
        .unwrap();
        assert_eq!(values[0], 1.5);
        assert_eq!(values[1].to_bits(), (-0.0f64).to_bits());
        assert!(Decoder {
            bytes: &[1, 127],
            position: 0
        }
        .map()
        .is_err());
    }

    #[test]
    fn irregular_axes_validate_deltas_order_overflow_and_complete_consumption() {
        let mut value = episode();
        value.time_encoding = TimeEncoding::IrregularDelta;
        value.interval_micros = None;
        value.time_deltas = Some(Vec::new());
        for delta in [1, 3, 5] {
            write_long(delta, value.time_deltas.as_mut().unwrap());
        }
        assert_eq!(value.timestamps().unwrap(), [-100, -99, -95, -86]);
        let bytes = encode(&value).unwrap();
        assert_eq!(
            encode(&decode(&bytes, bytes.len(), 10).unwrap()).unwrap(),
            bytes
        );
        value.time_deltas.as_mut().unwrap().push(0);
        assert!(value.timestamps().is_err());
        value.time_deltas = Some(vec![0, 0, 0]);
        assert!(value.timestamps().is_err());
        value.time_deltas = Some(vec![0xff; 10]);
        assert!(value.timestamps().is_err());
        value.time_encoding = TimeEncoding::Regular;
        value.time_deltas = None;
        value.interval_micros = Some(i64::MAX);
        assert!(value.timestamps().is_err());
        value.interval_micros = Some(10);
        value.series.series_id = "wrong".into();
        assert!(encode(&value).is_err());
    }

    #[test]
    fn summary_matches_welford_and_signed_zero_rules() {
        let result = summary(&[1.0, 2.0, f64::NAN, 3.0]);
        assert_eq!((result.valid_count, result.nan_count), (3, 1));
        assert_eq!(
            (result.min, result.max, result.mean, result.m2),
            (1.0, 3.0, 2.0, 2.0)
        );
        let result = summary(&[0.0, -0.0]);
        assert_eq!(result.min.to_bits(), (-0.0f64).to_bits());
        assert_eq!(result.max.to_bits(), 0.0f64.to_bits());
        let result = summary(&[f64::NAN]);
        assert_eq!((result.valid_count, result.nan_count), (0, 1));
        assert!(
            result.min.is_nan()
                && result.max.is_nan()
                && result.mean.is_nan()
                && result.m2.is_nan()
        );
    }

    #[test]
    fn empty_regular_episodes_and_extreme_integer_fields_round_trip() {
        let mut value = episode();
        value.values.clear();
        value.count = 0;
        value.quality = Some(Vec::new());
        value.summary = summary(&[]);
        value.t_start = i64::MIN;
        value.t_end = i64::MIN + 1;
        value.provenance.created_at = i64::MAX;
        let bytes = encode(&value).unwrap();
        let decoded = decode(&bytes, bytes.len(), 0).unwrap();
        assert!(decoded.values.is_empty());
        assert_eq!(decoded.provenance.created_at, i64::MAX);
        assert_eq!(decoded.t_start, i64::MIN);
        assert_eq!(encode(&decoded).unwrap(), bytes);
        value.time_encoding = TimeEncoding::IrregularDelta;
        value.interval_micros = None;
        value.time_deltas = Some(Vec::new());
        assert!(encode(&value).is_err());
    }

    #[test]
    fn inconsistent_axes_counts_quality_and_summary_are_rejected() {
        let mut value = episode();
        value.count -= 1;
        assert!(encode(&value).is_err());
        value = episode();
        value.t_end = value.t_start + 30;
        assert!(encode(&value).is_err());
        value = episode();
        value.quality = Some(vec![0]);
        assert!(encode(&value).is_err());
        value = episode();
        value.summary.nan_count = -1;
        assert!(encode(&value).is_err());
        value = episode();
        value.summary.nan_count = i64::MAX;
        assert!(encode(&value).is_err());
    }
}
