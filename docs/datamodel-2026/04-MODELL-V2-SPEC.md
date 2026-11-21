# OpenTSx data model v2: specification (version 2.0)

> Status: **implemented** in `opentsx-model` (Java) and `opentsx.model` (Python), as of 2026-10-09.
> Draft and rationale: [02-ZIELMODELL.md](02-ZIELMODELL.md). This file is the binding specification;
> Java and Python are checked against shared test vectors
> (`opentsx-model/src/test/resources/testvectors`).

## 1. Schemas

Single source: `opentsx-model/src/main/avro/*.avsc`, namespace `org.opentsx.model.v2`.
Python uses byte-identical copies (`python-package/opentsx/model/schemas`, enforced by a test).

| Record | Purpose |
|--------|-------|
| `SeriesKey` | identity of a series: `metric`, `tags`, `unit`, `seriesId` |
| `Episode` | immutable piece of a series (unit of exchange and storage) |
| `EpisodeSummary` | mergeable statistics (Welford) |
| `Segmentation` | how the episode was cut (`FIXED_DURATION`, `FIXED_COUNT`, `FEATURE_EXTREMUM`, `FEATURE_THRESHOLD`, `MANUAL`) |
| `Provenance` | source, producer, time, pipeline, parent episode |
| `Observation` | single live measuring point |
| `BucketManifest` | logical bucket (OPEN → SEALED, checksum) |
| `SeriesStats` | running or windowed statistics of the streaming processor |
| `PatternMatch` | hit of a pattern search |

All timestamps: `long` with `logicalType: timestamp-micros` (epoch microseconds, UTC).
Java: `java.time.Instant`; Python (fastavro): timezone-aware `datetime` when reading, `int` or `datetime` when writing.

## 2. Series identity

```
canonical = escape(metric) + "{" + join(",", escape(k) + "=" + escape(v) for k in sort_utf8(tags)) + "}"
seriesId  = lower_hex_16( xxHash64( utf8(canonical), seed = 0 ) )
escape(s) = s with "\" before each of the characters  \ { } , =
```

* Tag keys are sorted by their **UTF-8 bytes** (unsigned).
* `metric` and tag keys must not be empty; no Unicode normalization (callers deliver NFC).
* `unit` is **not** part of the identity.
* Example: `wind.turbine.power_active{park=ber-01,turbine=T07}` → `58280afbd12100b7`.

## 3. Time axis of an episode

* `tStart` inclusive, `tEnd` exclusive; all timestamps lie in `[tStart, tEnd)`.
* `REGULAR`: `t_i = tStart + i · intervalMicros`, `timeDeltas = null`.
  Default `tEnd = tStart + count · intervalMicros`.
* `IRREGULAR_DELTA`: `t_0 = tStart`; `timeDeltas` holds `count − 1` zigzag varints (like an Avro `long`):
  first `d_1 = t_1 − t_0`, then `d_i − d_(i−1)`. Timestamps are strictly increasing.
  Default `tEnd = t_last + 1 µs`.
* For 1 Hz data with small jitter, a timestamp costs 1 to 2 bytes.

## 4. Values, quality, summary

* `values`: `array<double>`, `count == len(values)`; NaN is allowed (gap/invalid).
* `quality`: optional, exactly one byte per value (0 = ok).
* `summary`: over the **finite** values: `validCount`, `nanCount`, `min`, `max`, `mean`, `m2`;
  variance `= m2 / (validCount − 1)`. Merging follows Chan et al. (exact, independent of order up to rounding).
  Without finite values: `min = max = mean = m2 = NaN`.

## 5. Wire format

* **Default: Avro single-object encoding**, laid out as `C3 01` + an 8-byte CRC-64-AVRO fingerprint of the
  Parsing Canonical Form (little-endian) + Avro binary. Self-describing, no Schema Registry needed.
* **Reading:** also accepts Confluent framing (`00` + 4-byte schema ID) when a resolver (registry client) is configured.
* **Deterministic:** maps (`tags`, `labels`, `params`) are written in UTF-8 key order →
  Java and Python produce the same bytes (the basis for dedup and checksums).
* Fingerprints v2.0: `Episode 8985f02b48c77195`, `Observation ca0e771818e53a71`, `SeriesKey ec4b59937d77bd89`,
  `BucketManifest 68173d1a6b413f3e`, `SeriesStats e51b48188baa6606`, `PatternMatch 057c7af24286a91e`.
* Kafka: `org.opentsx.model.kafka.OpenTsxSerializer` / `OpenTsxDeserializer` (value), key = `seriesId`.

## 6. Schema evolution

* Every published version is stored frozen under `opentsx-model/src/main/resources/schema-history/<version>/`.
* Rule **BACKWARD_TRANSITIVE**: the current reader must read data written with any published version
  (new fields only with a default). The build checks this and requires a new history version for every schema change.
* `OpenTsxAvro` automatically registers all history versions as writer schemas.

## 7. Episode IDs

ULID (26 characters of Crockford Base32, 48 bits of milliseconds + 80 random bits): sortable by time; dedup key,
because KafScale has no idempotent producer.

## 8. Python / pandas

```python
from opentsx import model as m
from opentsx.model.pandas_io import series_to_episode, episode_to_series, episodes_to_frame

key = m.series_key("wind.met.wind_speed", {"park": "ber-01", "turbine": "T07"}, "m/s")
ep = series_to_episode(pandas_series_with_tz_aware_index, key, source="scada:ber-01")  # REGULAR detected
payload = m.encode("Episode", ep)          # identical to Java
name, ep2 = m.decode(payload)
df = episodes_to_frame([ep2])               # series_id, metric, episode_id, ts (UTC), value
```

A naive `DatetimeIndex` is refused (unless `assume_utc=True`).

## 9. Legacy

`opentsx-legacy-bridge`: `TimeSeriesObject ⇄ Episode` (x values as epoch seconds/millis/micros, lossless),
`EpisodesRecord (v1) → Episode`, label `"metric k=v,…"` ⇄ `SeriesKey`. The v1 model (`opentsx-data`) is frozen
and deprecated, see `opentsx-data/README.md`.
