# 3. Data model v2

Normative specification: [04-MODELL-V2-SPEC](../datamodel-2026/04-MODELL-V2-SPEC.md) (German). Schemas:
`opentsx-model/src/main/avro/*.avsc` (namespace `org.opentsx.model.v2`). Python uses byte-identical copies.

## 3.1 Records

| Record | Purpose |
|--------|---------|
| `SeriesKey` | identity of a series: `metric`, `tags`, `unit`, `seriesId` |
| `Episode` | immutable piece of one series: time axis, values, quality, summary, segmentation, labels, provenance |
| `EpisodeSummary` | mergeable statistics of the finite values (count, NaN count, min, max, mean, M2), optional SAX word |
| `Segmentation` | how the episode was cut: strategy, parameters, anchor, source episode |
| `Provenance` | source, producer, creation time, pipeline, parent episode |
| `Observation` | a single live value |
| `BucketManifest` | a logical bucket: OPEN → SEALED, episode count, time range, checksum |
| `SeriesStats` | windowed or running statistics (from `opentsx-stats`) |
| `PatternMatch` | hit of a pattern search |

All timestamps are `long` with `logicalType: timestamp-micros`. Java uses `Instant`; Python uses timezone-aware `datetime`.

## 3.2 Series identity

```
canonical = escape(metric) + "{" + join(",", escape(k) + "=" + escape(v) for k in tags sorted by UTF-8 bytes) + "}"
seriesId  = lower-hex( xxHash64(utf8(canonical), seed 0) )        # 16 hex characters
escape: prefix \ { } , = with a backslash
```

Example: `wind.turbine.power_active{park=ber-01,turbine=T07}` → `58280afbd12100b7`.

The unit is **not** part of the identity.

```java
SeriesKey key = SeriesKeys.of("wind.met.wind_speed", Map.of("park", "ost-01", "turbine", "T07"), "m/s");
```

```python
from opentsx import model as m
key = m.series_key("wind.met.wind_speed", {"park": "ost-01", "turbine": "T07"}, "m/s")
```

## 3.3 Episodes

* `tStart` is inclusive and `tEnd` exclusive. All timestamps lie in `[tStart, tEnd)`.
* **Time encodings:**
  * `REGULAR`: `t_i = tStart + i·intervalMicros`, with no per-value timestamps.
  * `IRREGULAR_DELTA`: zig-zag varint delta-of-deltas. With 1 Hz data and small jitter, a timestamp costs 1–2 bytes.
* **Values:**
  * `values` is an array of doubles. NaN marks missing or invalid values.
  * `quality` holds one optional byte per value. 0 means good; the wind-farm catalog defines 1 IMPLAUSIBLE,
    2 MISSING, 3 SUBSTITUTED and 4 QUESTIONABLE.
* **`summary`:**
  * Computed with Welford's method over the finite values.
  * Summaries merge exactly (Chan et al.), so windows and turbines can be combined without the raw data.
* **`episodeId`:** a ULID (sortable by time).
  * Producers may use deterministic ids (`Ulid.deterministic(millis, seed)`) so that re-running a job writes the same
    episodes.
  * Stores deduplicate by id.

```java
Episode e = Episodes.regular(key, tStartMicros, 1_000_000L, values)
        .source("scada:ost-01").label("scenario", "s1").build();
double[] v = Episodes.values(e);
long[] t = Episodes.timestampsMicros(e);
```

```python
ep = m.regular(key, t_start_us, 1_000_000, values, source="scada:ost-01")
from opentsx.model.pandas_io import series_to_episode, episode_to_series, episodes_to_frame
ep2 = series_to_episode(pandas_series_with_utc_index, key, source="notebook")   # REGULAR is detected automatically
```

A naive (timezone-less) pandas index is rejected unless you pass `assume_utc=True`.

## 3.4 Wire format

* **Default: Avro single-object encoding.** The record is `C3 01`, then the 8-byte CRC-64-AVRO fingerprint of the
  schema, then Avro binary. Records are self-describing, so no Schema Registry is needed. This matters because
  KafScale has none.
* Confluent framing (`00` + schema id) is accepted when a resolver is configured.
* Maps (`tags`, `labels`, `params`) are written in UTF-8 key order. Java and Python therefore produce identical bytes,
  which is the basis for deduplication and checksums.
* Kafka: `org.opentsx.model.kafka.OpenTsxSerializer` / `OpenTsxDeserializer`; the key is the `seriesId`.

```java
OpenTsxAvro avro = new OpenTsxAvro();
byte[] bytes = avro.encode(e);
Episode back = avro.decode(bytes, Episode.class);
```

```python
payload = m.encode("Episode", ep)        # identical bytes to Java
name, ep2 = m.decode(payload)
```

## 3.5 Schema evolution

* Every published schema version is frozen in `opentsx-model/src/main/resources/schema-history/<version>/`.
* The build checks **BACKWARD_TRANSITIVE**: the current reader must read all published versions, and new fields need
  defaults.
* Changing a schema requires a new history version.
