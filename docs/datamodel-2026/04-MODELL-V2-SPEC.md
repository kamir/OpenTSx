# OpenTSx data model v2: specification (version 2.2)

> Status: **implemented** in `opentsx-model` (Java) and `opentsx.model` (Python); the result identity also in the web
> workbench (`opentsx-saas-frontend/src/workbench/resultIds.ts`).
> Design and rationale: 02-ZIELMODELL.md (German). This file is the normative specification.
> Java, Python and TypeScript are checked against shared test vectors
> (`opentsx-model/src/test/resources/testvectors`).

## 1. Schemas

Single source: `opentsx-model/src/main/avro/*.avsc`, namespace `org.opentsx.model.v2`.
Python uses byte-identical copies (`python-package/opentsx/model/schemas`, enforced by a test).

| Record | Purpose |
|--------|---------|
| `SeriesKey` | identity of a series: `metric`, `tags`, `unit`, `seriesId` |
| `Episode` | immutable section of one series (unit of exchange and storage) |
| `EpisodeSummary` | mergeable statistics (Welford) |
| `Segmentation` | how the episode was cut (`FIXED_DURATION`, `FIXED_COUNT`, `FEATURE_EXTREMUM`, `FEATURE_THRESHOLD`, `MANUAL`) |
| `Provenance` | source, producer, creation time, pipeline, parent episode |
| `Observation` | a single live value |
| `BucketManifest` | logical bucket (OPEN → SEALED, checksum) |
| `SeriesStats` | running or windowed statistics of the streaming processor |
| `PatternMatch` | hit of a pattern search |
| `AnalysisResult` | result of one analysis run on fixed input episodes (since 2.1) |
| `ResultScalar`, `ResultCurve`, `ResultMatrix` | parts of an `AnalysisResult` (since 2.1) |

All timestamps: `long` with `logicalType: timestamp-micros` (epoch microseconds, UTC).
Java: `java.time.Instant`; Python (fastavro): timezone-aware `datetime` on read, `int` or `datetime` on write.

## 2. Series identity

```
canonical = escape(metric) + "{" + join(",", escape(k) + "=" + escape(v) for k in sort_utf8(tags)) + "}"
seriesId  = lower_hex_16( xxHash64( utf8(canonical), seed = 0 ) )
escape(s) = s with "\" before each of the characters  \ { } , =
```

* Tag keys are sorted by their **UTF-8 bytes** (unsigned).
* `metric` and tag keys must not be empty. No Unicode normalization is applied; callers pass NFC.
* `unit` is **not** part of the identity.
* Example: `wind.turbine.power_active{park=ber-01,turbine=T07}` → `58280afbd12100b7`.

## 3. Time axis of an episode

* `tStart` is inclusive, `tEnd` exclusive. All timestamps lie in `[tStart, tEnd)`.
* `REGULAR`: `t_i = tStart + i · intervalMicros`, `timeDeltas = null`.
  Default `tEnd = tStart + count · intervalMicros`.
* `IRREGULAR_DELTA`: `t_0 = tStart`. `timeDeltas` holds `count − 1` zig-zag varints (as Avro `long`):
  first `d_1 = t_1 − t_0`, then `d_i − d_(i−1)`. Timestamps are strictly increasing.
  Default `tEnd = t_last + 1 µs`.
* With 1 Hz data and small jitter, a timestamp costs 1 to 2 bytes.

## 4. Values, quality, summary

* `values`: `array<double>`, `count == len(values)`. NaN is allowed (gap or invalid value).
* `quality`: optional, exactly one byte per value (0 = good).
* `summary`: over the **finite** values: `validCount`, `nanCount`, `min`, `max`, `mean`, `m2`.
  Variance `= m2 / (validCount − 1)`. Merge after Chan et al. (exact, independent of order up to rounding).
  Without finite values: `min = max = mean = m2 = NaN`.
* `contentDigest`: SHA-256 over what an analysis method reads from an episode. It is computed, not stored in the
  episode. All integers are signed 64-bit little-endian.

```
bytes         = ascii("opentsx.episode.content.v1\n")
              + str(canonical(metric, tags)) + optStr(unit)
              + tStart + tEnd + count
              + t_0 ... t_(count-1)                     decoded timestamps
              + f64(v_0) ... f64(v_(count-1))
              + optBytes(quality)
str(s)        = len(utf8(s)) + utf8(s)
optStr(s)     = byte 0 for null, else byte 1 + str(s)
optBytes(b)   = byte 0 for null, else byte 1 + b       b has count bytes
f64(v)        = IEEE 754 bits, little-endian; every NaN as 7ff8000000000000
contentDigest = lower_hex_64( SHA-256(bytes) )
```

* The same series, unit, time range, timestamps, values and quality give the same digest. Episode id, time encoding,
  labels, bucket, revision, summary, segmentation and provenance do not change it.
* `-0.0` and `0.0` give different digests. NaN payloads do not.
* Vectors: `episode-digest.json`. Implementations: Java `Episodes.contentDigest`, Python
  `opentsx.model.content_digest`.

## 5. Wire format

* **Default: Avro single-object encoding.** `C3 01`, then the 8-byte CRC-64-AVRO fingerprint of the parsing
  canonical form (little-endian), then Avro binary. Self-describing; no schema registry is needed.
* **Read:** Confluent framing (`00` + 4-byte schema id) is also accepted when a resolver (registry client) is
  configured.
* **Deterministic:** maps (`tags`, `labels`, `params`) are written in UTF-8 key order. Java and Python therefore
  produce the same bytes, which is the basis for deduplication and checksums.
* Fingerprints: `Episode 8985f02b48c77195`, `Observation ca0e771818e53a71`, `SeriesKey ec4b59937d77bd89`,
  `BucketManifest 68173d1a6b413f3e`, `SeriesStats e51b48188baa6606`, `PatternMatch 057c7af24286a91e` (unchanged
  since 2.0), `AnalysisResult 6738c1dcde344482` (since 2.2, in `analysis-result-wire.json`). Readers also accept the
  2.1 `AnalysisResult` fingerprint `8ec3c79515e8cc57`, which `wire-format.json` lists.
* Kafka: `org.opentsx.model.kafka.OpenTsxSerializer` / `OpenTsxDeserializer` (value), key = `seriesId`.

## 6. Schema evolution

* Every published version is frozen in `opentsx-model/src/main/resources/schema-history/<version>/`.
  Published versions: `v2.0`, `v2.1`, `v2.2`. `v2.2` adds `AnalysisResult.inputDigests`.
* Rule **BACKWARD_TRANSITIVE**: the current reader must read data of all published versions (new fields only with a
  default). The build checks this and requires a new history version for every schema change.
* `OpenTsxAvro` registers all history versions as writer schemas.
* Python keeps the released writer schemas whose fingerprint differs from the current one in
  `python-package/opentsx/model/schemas/<version>/` (today `v2.1/AnalysisResult.avsc`). A test requires them to equal
  the Java history and every released top-level fingerprint to be decodable.

## 7. Episode ids

ULID (26 characters Crockford Base32, 48 bits milliseconds + 80 bits randomness), sortable by time. It is the
deduplication key, because KafScale has no idempotent producer.

## 8. Analysis results

An `AnalysisResult` holds what one analysis method computed on fixed input episodes. It carries results that are not
time series. Results that are time series again (for example a pair method per window) stay episodes.

| Field | Rule |
|-------|------|
| `resultId` | identity, see below |
| `method`, `methodVersion` | not empty, for example `dfa` and `1.0.0` |
| `params` | map of long, double, string or array of doubles, written in UTF-8 key order; NaN is not allowed |
| `inputEpisodeIds` | at least one, ordered, part of the identity |
| `inputSeriesIds` | same length and order as `inputEpisodeIds` |
| `inputDigests` | `contentDigest` of each input episode, same length and order as `inputEpisodeIds`, part of the identity; empty only in records written before 2.2 |
| `tStart`, `tEnd` | earliest `tStart` and latest `tEnd` of the inputs; `tStart < tEnd` |
| `scalars` | `ResultScalar`: `name`, `value`, optional `stdError`, `unit`, `fitFrom`, `fitTo` (fit range of the independent variable) |
| `curves` | `ResultCurve`: `name`, `xName`, optional `xUnit`, `x`, `y` (same length), optional `unit` of y |
| `matrices` | `ResultMatrix`: `name`, `rowKeys`, `colKeys`, `values` row-major with `rows · cols` entries, optional `unit`; NaN marks an undefined entry |
| `labels`, `provenance` | as for episodes; `provenance.parentEpisodeId` stays null, the inputs are in `inputEpisodeIds` |

Result identity:

```
canonical = escape(method) + "@" + escape(methodVersion)
          + "(" + join(",", escape(k) + "=" + value(v) for k in sort_utf8(params)) + ")"
          + "[" + join(",", escape(e) for e in inputEpisodeIds) + "]"
          + "{" + join(",", inputDigests) + "}"   only when inputDigests is not empty
value(v)  = "l" + decimal                          for a long
          | "d" + hex16(IEEE 754 bits, big-endian)  for a double
          | "s" + escape(v)                        for a string
          | "a" + join(";", hex16 per element)      for an array of doubles ("a" when empty)
escape(s) = s with "\" before each of the characters  \ { } , = ( ) [ ] @
resultId  = lower_hex_16( xxHash64( utf8(canonical), seed = 0 ) )
```

* Parameter order does not change the id. Input order, parameter types and input contents do.
* The same episode id with other contents, for example a later revision, gives another id.
* A record without `inputDigests` keeps the id it had in 2.1. Its id does not bind the input contents.
* Each digest is 64 lower-case hex characters. A count that differs from `inputEpisodeIds` is rejected.
* The id covers the inputs, not the outputs. `scalars`, `curves` and `matrices` are not part of it.
* xxHash64 is not a cryptographic hash. The id identifies and deduplicates results; it does not protect against
  deliberate manipulation. The content digests detect changed inputs; they do not authenticate who wrote a record.
* Example: `dfa@1.0.0()[01HF7Z1Y4X0000000000000000]` → `85bb1632f41faf72`.
* Boolean parameters are not supported. Producers that need one write a long or a string.
* Vectors: `result-id.json` (identity), `analysis-result-wire.json` (bytes and fingerprint of 2.2 records) and the
  `analysisResults` entries of `wire-format.json` (bytes written with the 2.1 schema, which must stay readable).
  `wire-format.json` holds the 2.1 wire format and does not change: catalog `tsx-catalog/1` pins its SHA-256 as the
  vectors of `decode.avro-soe` (`systems/catalogs/tsx-catalog-1.yaml`).
* Implementations: Java `AnalysisResults`, Python `opentsx.model.analysis`, TypeScript `resultIds.ts`.
  Python converts DFA and event-synchronization output with `opentsx.algorithms.results`, which fills
  `inputDigests`. The Python builder `analysis_result` requires `input_digests`.

## 9. Python / pandas

```python
from opentsx import model as m
from opentsx.model.pandas_io import series_to_episode, episode_to_series, episodes_to_frame

key = m.series_key("wind.met.wind_speed", {"park": "ber-01", "turbine": "T07"}, "m/s")
ep = series_to_episode(pandas_series_with_tz_aware_index, key, source="scada:ber-01")  # REGULAR detected
payload = m.encode("Episode", ep)          # identical to Java
name, ep2 = m.decode(payload)
df = episodes_to_frame([ep2])               # series_id, metric, episode_id, ts (UTC), value
```

A naive `DatetimeIndex` is rejected unless `assume_utc=True` is passed.

## 10. Legacy

`opentsx-legacy-bridge`: `TimeSeriesObject ⇄ Episode` (x values as epoch seconds, milliseconds or microseconds,
lossless), `EpisodesRecord (v1) → Episode`, label `"metric k=v,…"` ⇄ `SeriesKey`. The v1 model (`opentsx-data`) is
frozen and deprecated, see `opentsx-data/README.md`.
