# OpenTSx data model v2: target design (draft)

> Status: **draft**. The data model (section 2) has been implemented since 2026-10-09;
> [04-MODELL-V2-SPEC.md](04-MODELL-V2-SPEC.md) is binding; the other sections are still a plan. Open decisions are
> marked with **[DECISION]** and collected in `EVOLUTION/TASK-011-datamodel-refresher-2026.md`.

## 1. Guiding principles

1. **One contract, many projections**: Avro schemas in one module (`opentsx-model`) are the only source. Java, Python (fastavro), Flink, ksqlDB and the stores derive from them.
2. **SeriesKey is the identity**: never again free-form label strings as keys.
3. **The episode is the unit of exchange**: point events only for live streams; archive, replay and retrieval work on episodes.
4. **Immutability**: episodes are not edited, they are written anew (revision). This makes Kafka storage, Iceberg snapshots and pattern indexes trivially consistent.
5. **Compatibility is checked, not hoped for**: CI checks every schema change against the previous version (BACKWARD_TRANSITIVE).

## 2. Core entities

```
SeriesKey ──< Observation          (live stream, one point)
SeriesKey ──< Episode              (piece; REGULAR | IRREGULAR encoding; summary stats)
Bucket    ──< BucketManifest ──< EpisodeRef
SeriesKey ──< SeriesStats          (running, from the streaming processor)
Episode   ──< Annotation / PatternMatch
```

### 2.1 Schema drafts (close to Avro IDL, namespace `org.opentsx.model.v2`)

```avro
record SeriesKey {
  string metric;                       // for example "sensor.temperature"
  map<string> tags = {};               // canonical: keys sorted when hashing
  union{null,string} unit = null;      // UCUM, for example "Cel", "ms"
  string seriesId;                     // stable: hex(xxHash64(metric + sorted tags))  [DECISION hash]
}

record Observation {                   // live point
  string seriesId;
  timestamp_micros ts;                 // [DECISION precision: millis vs micros]
  double value;
  union{null,int} quality = null;      // bit mask: 0=ok, 1=interpolated, 2=estimated, ...
}

enum TimeEncoding { REGULAR, IRREGULAR_DELTA }

record Episode {
  string episodeId;                    // ULID; sortable by creation
  SeriesKey series;
  timestamp_micros tStart;
  timestamp_micros tEnd;               // exclusive
  int count;
  TimeEncoding timeEncoding;
  union{null,long} intervalMicros = null;      // REGULAR only
  union{null,bytes} timeDeltas = null;         // IRREGULAR only: zigzag varint delta-of-delta
  array<double> values;                // phase 1 uncompressed; compression through the Kafka/Parquet codec
  union{null,bytes} quality = null;            // optional, bitpacked
  EpisodeSummary summary;              // min/max/mean/m2/nanCount -> prefilter without a payload scan
  map<string> labels = {};             // domain labels (experiment, regime, ...)
  union{null,string} bucketId = null;
  int revision = 0;
  Provenance provenance;
}

record EpisodeSummary { double min; double max; double mean; double m2; long nanCount;
                        union{null,string} sax = null; }   // SAX word for the pattern prefilter

record Provenance { string source; string producer; timestamp_millis createdAt;
                    union{null,string} parentEpisodeId = null; union{null,string} pipeline = null; }

record BucketManifest {                // bucket = logical collection, not a file format
  string bucketId;
  string name;
  map<string> descriptor = {};         // successor of ExperimentDescriptor (author, topics, tags, ...)
  timestamp_micros tMin; timestamp_micros tMax;
  long episodeCount;
  enum BucketState { OPEN, SEALED } state;
  union{null,string} checksum = null;  // over sorted episodeIds -> replay verification
}

record SeriesStats {                   // output of the streaming processor (compacted topic)
  string seriesId; timestamp_micros windowStart; timestamp_micros windowEnd; string windowType;
  long count; double min; double max; double mean; double m2;   // Welford -> var = m2/(n-1)
  union{null,bytes} quantileSketch = null;                       // t-digest / KLL serialized
  long gapCount; double meanRatePerSec; timestamp_micros lastSeen;
}

record PatternMatch { string queryId; string episodeId; int offset; int length;
                      double distance; string metric; }   // "zED" | "DTW(w=..)"
```

### 2.2 Deliberate design decisions

* **No `uri` per point**: provenance is stated once in `Provenance`, the identity in `SeriesKey`.
* **`SeriesKey` embedded in every episode** (instead of only `seriesId`): it costs a few bytes and makes every record self-describing, which is important for replay without a lookup service. A live `Observation` carries only `seriesId`; the dimension is kept separately in the compacted topic `…series.v2`.
* **Summary in the episode**: allows filters and prefilters (retrieval, pattern matching, OpenMetadata profile) without decoding the values.
* **Mergeable statistics (count/mean/m2, sketch)** instead of a finished variance: episode, window and bucket statistics can be merged without loss.

## 3. Serialization and storage in KafScale

> **Correction after the KafScale research (v1.6.0, see [03-GAP-ANALYSE, section 5](03-GAP-ANALYSE.md)):**
> no compaction → the topics marked `compact` below become plain append-only logs, the "latest" state lives in Iceberg;
> no idempotent producer → `enable.idempotence=false`, dedup by `episodeId`;
> **ListOffsets ignores timestamps** → time-based replay through a separate episode index instead of `offsetsForTimes`;
> no Schema Registry → default wire format `SELF_DESCRIBING`; headers not load-bearing until a conformance test confirms them.

### 3.1 Wire format: two levels

| Mode | When | Format |
|-------|------|--------|
| `REGISTRY` | Schema Registry available | Confluent wire format (magic 0 + schema ID) |
| `SELF_DESCRIBING` | no registry (cheapest operation, archives, offline replay) | **Avro single-object encoding** (`C3 01` + 8-byte fingerprint); schemas from the classpath / schema topic `_opentsx_schemas` |

An `OpenTsxSerde` facade recognizes both formats by the first byte when reading → consumers (Java, Flink, Python) do not need to know how the data was written. This also fixes today's Flink break.

### 3.2 Topic layout

| Topic | Key | Value | Policy |
|-------|-----|-------|--------|
| `tsx.<domain>.observations.v2` | seriesId | `Observation` | delete, short retention (live) |
| `tsx.<domain>.episodes.v2` | seriesId | `Episode` | delete, long or infinite retention (archive, tiered) |
| `tsx.<domain>.series.v2` | seriesId | `SeriesKey` | compact |
| `tsx.<domain>.buckets.v2` | bucketId | `BucketManifest` | compact |
| `tsx.<domain>.series-stats.v2` | seriesId + windowType | `SeriesStats` | compact |

Headers per record: `tsx-schema` (full name + version), `tsx-bucket` (bucketId), `tsx-encoding`. **Record timestamp = `tStart` of the episode** (CreateTime), so that `offsetsForTimes()` allows time-based replay.

### 3.3 "Cheap": cost levers

* Episodes instead of points: typically 1 record per 10³ to 10⁴ values → the overhead per record (key, headers, batch header) is amortized.
* Producer: `compression.type=zstd`, `linger.ms` 50 to 200, `batch.size` 256 KB to 1 MB, `enable.idempotence=false` (KafScale), **asynchronous** (no `send().get()`).
* Delta-of-delta for irregular time axes; `REGULAR` needs no timestamps at all.
* Optional phase 2: value compression (Gorilla XOR) as `bytes`, only after a measurement with zstd; otherwise it is needless complexity.

### 3.4 Bucket write and replay

```java
try (BucketWriter w = tsx.buckets().open("exp-2026-10-ffmlrc", descriptor)) {   // writes manifest OPEN
    for (TimeSeriesObject tso : legacyBucket) w.append(Episodes.from(tso, seriesKeyOf(tso)));
}                                                                                  // writes manifest SEALED + checksum

ReplayRequest r = ReplayRequest.bucket("exp-2026-10-ffmlrc")          // or: .series(selector).between(t0, t1)
                     .pace(Pace.ASAP | Pace.realtime(10.0));            // 10x real time
tsx.replay(r).into(Sink.topic("tsx.lab.episodes.replay") | Sink.callback(ep -> ...) | Sink.tsBucket());
```

Replay strategy: time window → `offsetsForTimes` per partition; bucket → header filter `tsx-bucket` (phase 1) or Iceberg index (phase 3, when episodes also live in Iceberg). Final verification through `episodeCount` + `checksum` from the manifest.

## 4. Store abstraction (SPI)

```java
public interface SeriesStore extends AutoCloseable {
    StoreCapabilities capabilities();                 // RAW_POINTS, EPISODES, DOWNSAMPLING, TAG_INDEX, SQL, TIME_TRAVEL
    CompletableFuture<WriteResult> write(Collection<Episode> episodes);
    Stream<Episode> read(EpisodeQuery query);         // Range + SeriesSelector, lazy
    Stream<SeriesKey> listSeries(SeriesSelector selector);
}
```

### 4.1 OpenTSDB adapter (`opentsx-store-opentsdb`, refactored)

| v2 | OpenTSDB |
|----|----------|
| `SeriesKey.metric` | `metric` (character validation `[a-zA-Z0-9-_./]`) |
| `SeriesKey.tags` | `tags` (≤ 8 default limit, validation, `unit` optional as a tag) |
| `Episode` | resolved into data points → HTTP `/api/put?details` batched (instead of Telnet) |
| `EpisodeQuery` | `/api/query` with `start/end` (ms), `m=<agg>:<downsample>:metric{tags}`; response → episodes (chunking by `maxEpisodeLength`) |

Capabilities: `RAW_POINTS, DOWNSAMPLING, TAG_INDEX`; no `EPISODES` → episode boundaries are reconstructed on read, labels and Provenance are lost (documented; if needed, the OpenTSDB annotations API for episode markers).

### 4.2 Iceberg adapter (`opentsx-store-iceberg`, new)

```sql
-- Dimension
CREATE TABLE tsx.series (series_id string, metric string, tags map<string,string>, unit string,
                         first_seen timestamp, last_seen timestamp);

-- Episodes: archive, replay source, retrieval, pattern prefilter
CREATE TABLE tsx.episodes (
  episode_id string, series_id string, t_start timestamp, t_end timestamp, count int,
  time_encoding string, interval_us bigint, time_deltas binary, values array<double>,
  s_min double, s_max double, s_mean double, s_m2 double, nan_count bigint, sax string,
  labels map<string,string>, bucket_id string, revision int, source string, created_at timestamp)
PARTITIONED BY (days(t_start), bucket(32, series_id))
-- WRITE ORDERED BY series_id, t_start

-- Optional: points for SQL analytics (derived from episodes, for example via Flink/Spark)
CREATE TABLE tsx.observations (series_id string, ts timestamp, value double, quality int)
PARTITIONED BY (days(ts), bucket(32, series_id));
```

Write paths: (a) directly through the adapter (batch, Iceberg Java API), (b) Kafka → Iceberg via the Flink Iceberg sink or the Iceberg Kafka Connect sink from `episodes.v2`. **[DECISION]** Catalog (REST / Nessie / Glue / JDBC / Hadoop) and object store.

Capabilities: `EPISODES, SQL, TIME_TRAVEL`; pattern prefilter on `sax`, `s_min/s_max`; partition pruning by time + series.

### 4.3 Legacy bridge

`LegacyBridge`: `TimeSeriesObject ⇄ Episode`, `TSBucket(SequenceFile) → BucketWriter`, `EpisodesRecord(v1) → Episode(v2)`. With it, all existing algorithms run unchanged on v2 data.

## 5. Metadata → OpenMetadata

| OpenTSx | OpenMetadata entity |
|---------|---------------------|
| `tsx.<domain>.*.v2` topics | `Topic` including `messageSchema` (Avro) |
| Iceberg tables | `Table` (through the Iceberg/lakehouse connector or by API) |
| Flink stats job, replay jobs | `Pipeline` + lineage edges Topic → Pipeline → Topic/Table |
| `BucketManifest` | `Container` or custom properties on Table/Topic (experiment descriptor) |
| `SeriesStats` (aggregated) | profiler or custom metrics, or data quality `TestCaseResult` (gaps, NaN rate, value range) |

Ingestion: a small Java module `opentsx-openmetadata` (REST client) that (1) registers schemas, topics and tables once and (2) updates profiles from `series-stats.v2` **rate-limited** (for example hourly per dataset, not per event). **[DECISION]** OpenMetadata version and auth (JWT/bot token); granularity: statistics per topic/table (recommended) vs. per series (only top N / custom entity).

> As of 2026-10-11, implemented in [`opentsx-stats`](../../opentsx-stats/README.md) and [`opentsx-openmetadata`](../../opentsx-openmetadata/README.md): OpenMetadata 2.0.5, bot JWT; granularity **per channel and window across all turbines** (profiles and DQ results on one signal view per park); the detail per series stays in `series-stats.v2`. KafScale has no compaction → `series-stats.v2` is append-only; readers take the newest record per key.

## 6. Streaming statistics processor (Flink)

* Source: `observations.v2` and/or `episodes.v2` (episode = pre-aggregated batch → merge `EpisodeSummary` directly).
* `keyBy(seriesId)`; state: Welford (count/mean/m2), min/max, t-digest or KLL sketch, last timestamp, gap counter (Δt > k·expected interval).
* Outputs: (a) running (global, "since start"), emitted every N seconds, (b) tumbling windows (1 min / 1 h / 1 d, configurable) → `series-stats.v2`; (c) aggregate per topic/bucket for OpenMetadata.
* Event time with watermarks and allowed lateness; late data → side output.
* Replaces today's `TimeSeriesAggregateFunction` approach (which buffers all points) with O(1) state per series.

## 7. Retrieval API and pattern matching

### 7.1 Public, simple API

```java
EpisodeService svc = OpenTsx.connect(config);           // picks store(s) by capabilities

List<Episode> eps = svc.episodes()
    .metric("sensor.temperature").tag("site", "ber-01")
    .between(Instant.parse("2026-09-01T00:00:00Z"), Instant.parse("2026-09-02T00:00:00Z"))
    .resample(Duration.ofSeconds(10), Agg.MEAN)          // optional
    .limit(100)
    .fetch();

Optional<Episode> one = svc.episode("01JA...ULID");

List<PatternMatch> hits = svc.similarTo(queryEpisode)    // or double[] / episodeId + offset/length
    .in(SeriesSelector.metric("sensor.temperature"))
    .distance(Distance.zNormalizedEuclidean())           // or Distance.dtw(band = 0.1)
    .topK(20)
    .fetch();
```

REST mirror (thin, phase 5b): `GET /v1/episodes?metric=&tag.site=&from=&to=`, `GET /v1/episodes/{id}`, `POST /v1/patterns/search`. Python client based on the same Avro schemas.

### 7.2 Internal pattern engine (two stages)

1. **Candidates (index)**: PAA → SAX word per episode or per sliding window of fixed length; stored in `EpisodeSummary.sax` (Iceberg column) or in an in-memory index (iSAX tree) for hot data. In addition, a prefilter on `s_min/s_max/s_mean`.
2. **Exact verification**: MASS (FFT-based z-normalized distance profiles; builds on the existing FFT code) for the Euclidean distance; for DTW the cascade LB_Kim → LB_Keogh → DTW with a Sakoe-Chiba band (UCR suite principle).
3. **Result** as `PatternMatch` (episode + offset + length + distance); optionally as an annotation back to Kafka/Iceberg; this is the continuation of `MarkableTimeSeriesObject`.

Possible later: matrix profiles for motif and discord discovery across buckets; embedding-based ANN search as another `Distance` implementation.
