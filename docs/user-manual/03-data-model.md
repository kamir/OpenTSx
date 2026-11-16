# 3. Data model v2

Normative specification: [04-MODELL-V2-SPEC](../datamodel-2026/04-MODELL-V2-SPEC.md). Schemas:
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
| `AnalysisResult` | result of one analysis run on fixed input episodes: method, typed parameters, inputs, scalars, curves, matrices |
| `ResultScalar`, `ResultCurve`, `ResultMatrix` | parts of an `AnalysisResult`, for example a DFA exponent, the fluctuation function F(s), an event-synchronization matrix |

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
* Released versions: `v2.0` and `v2.1`. `v2.1` adds `AnalysisResult`, `ResultScalar`, `ResultCurve` and
  `ResultMatrix`; the records of `v2.0` are unchanged.

## 3.6 Analysis results

An `AnalysisResult` holds what one analysis method computed on fixed input episodes. It covers results that are not
time series: the DFA exponent of a series, the fluctuation function F(s), or the event-synchronization matrix of
n series.

| Field | Content |
|-------|---------|
| `resultId` | identity, see below |
| `method`, `methodVersion` | for example `dfa` and `1.0.0` |
| `params` | typed parameters: long, double, string or array of doubles |
| `inputEpisodeIds`, `inputSeriesIds` | the inputs in a fixed order, one series id per episode |
| `tStart`, `tEnd` | earliest start and latest end of the inputs |
| `scalars` | `ResultScalar`: name, value, optional standard error, unit and fit range (`fitFrom`, `fitTo`) |
| `curves` | `ResultCurve`: name, x name and unit, `x[]`, `y[]` of equal length, unit of y |
| `matrices` | `ResultMatrix`: name, row and column keys (usually series ids), row-major values, unit; NaN marks an undefined entry |
| `labels`, `provenance` | as for episodes |

**Result identity.** The same method with the same parameters on the same episodes gets the same id, in Java,
Python and TypeScript:

```
canonical = escape(method) + "@" + escape(methodVersion)
          + "(" + join(",", escape(k) + "=" + value(v) for k in params sorted by UTF-8 bytes) + ")"
          + "[" + join(",", escape(e) for e in inputEpisodeIds) + "]"
value:    long "l<decimal>", double "d<16 hex digits of the IEEE 754 bits, big-endian>",
          string "s<escaped>", array of doubles "a<hex>;<hex>" ("a" when empty)
escape:   prefix \ { } , = ( ) [ ] @ with a backslash
resultId = lower-hex( xxHash64(utf8(canonical), seed 0) )
```

* Parameter order does not change the id. Input order does: `es` over (A, B) and over (B, A) are different results.
* The type is part of the id: the long `5` and the double `5.0` give different ids.
* NaN is not allowed in parameters. Boolean parameters are not supported.
* Shared vectors: `opentsx-model/src/test/resources/testvectors/result-id.json` (identity) and the
  `analysisResults` entries of `wire-format.json` (bytes). Java (`AnalysisResults`), Python (`opentsx.model.analysis`)
  and the workbench (`resultIds.ts`) check against them.

```python
from opentsx.algorithms.dfa import DFA
from opentsx.algorithms.event_sync import EventSynchronization
from opentsx.algorithms.results import dfa_result, event_sync_result

r = dfa_result(DFA(polynom_order=1), episode, source="notebook")        # scalars alpha, intercept, r_squared; curve F(s)
q = event_sync_result(EventSynchronization(tau_max=5.0), [e1, e2, e3], source="notebook")  # matrices Q and q_directed
payload = m.encode("AnalysisResult", q)
```

`event_sync_result` writes `Q` (symmetric) and `q_directed` (q_ij minus q_ji, positive when row i leads column j);
the diagonal is NaN.

