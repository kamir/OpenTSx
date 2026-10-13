# opentsx-retrieval: segmentation, pattern search, retrieval API

This module finds episodes, cuts them into new episodes, and searches them for patterns. It works over any
`SeriesStore`: in-memory, Iceberg, or OpenTSDB.

## Retrieval API

```java
EpisodeService svc = new EpisodeService(icebergStore);

List<Episode> eps = svc.episodes().metric("wind.met.wind_speed").tag("turbine", "T07")
        .between(from, to).resample(Duration.ofMinutes(1), Resampling.Agg.MEAN).limit(100).fetch();
Optional<Episode> one = svc.episode("01JA...");                       // SeriesStore.episode(id)

List<PatternMatch> hits = svc.similarTo(gust).metric("wind.met.wind_speed").between(from, to)
        .distance(Distance.dtw(0.1)).topK(20).fetch();                  // subsequences, across episode boundaries
List<PatternMatch> same = svc.similarTo("01JA...", 100, 120).metric("wind.met.wind_speed").fetch();
List<PatternMatch> like = svc.similarEpisodes("01JA...").topK(10).fetch();   // whole episodes

List<Episode> gusts = svc.segments(Segmenter.extremum(Feature.MAX, 2.0, PT4M, PT5M, PT30S, PT60S))
        .metric("wind.met.wind_speed").writeTo(derivedStore).fetch();
```

The service reads from the first store that keeps episodes (capability `EPISODES`).

## Segmentation

The four strategies of the model (`Episode.segmentation`):

| Strategy | Factory | Example (wind farm) |
|----------|---------|---------------------|
| `FIXED_DURATION` | `Segmenter.fixedDuration(PT10M[, offset])` | 10-minute SCADA blocks, daily episodes |
| `FIXED_COUNT` | `Segmenter.fixedCount(4096, 2048)` | FFT blocks with 50 % overlap |
| `FEATURE_EXTREMUM` | `Segmenter.extremum(MAX, prominence, window, distance, pre, post)` | gusts, power peaks |
| `FEATURE_THRESHOLD` | `Segmenter.threshold(ABOVE, enter, exit, pre, post, minDuration, maxDuration)` | storm phases (> 20 m/s until < 18 m/s), curtailment |

### How segments are decided

* **Detection.** The detectors are incremental. A segment is decided only when no later sample can change it.
* **Streaming equals batch.** `StreamingSegmenter` uses bounded memory and still gives the same result as
  `Segmentations.segment` (batch). A test checks this for all strategies, with and without trimming.
* **Extremum peaks.** A peak qualifies when it is a local maximum, has a prominence of at least *p* within
  ±window/2, and is the highest value within ±distance. On ties, the earliest wins.
* **Threshold hysteresis.** A phase starts at `enter` and ends only below `exit`. Short phases are dropped,
  and long phases are cut off at `maxDuration`.

### What a segment episode contains

Segments are deterministic. The id is derived from series + strategy + parameters + start, so running the same
segmentation again writes nothing new.

Each segment carries:
* `segmentation`: strategy, params, anchor and source episode
* the parent episode
* the source labels, except ground truth `gt.*`

## Pattern search

The pattern engine provides these building blocks:

| Building block | Purpose |
|----------------|---------|
| `Mass` | z-normalized Euclidean distance to every subsequence, O(n log n) via FFT |
| `Paa`, `Sax` | Symbolic representation. `Sax.mindist` is a lower bound of the Euclidean distance, used as an exact prefilter. The word is stored in `EpisodeSummary.sax` as `"16x8:abcd…"`. |
| `Dtw` | DTW with a Sakoe-Chiba band, early abandoning, LB_Kim and LB_Keogh (the UCR-suite cascade) |
| `PatternSearch` | Exact top-k search for **non-overlapping** subsequences over `Run`s (series assembled across episode boundaries, split at gaps) |
| `EpisodeSimilarity` | Whole episodes. The representation is a z-normalized PAA (64 points). Exact best-first search with SAX bounds verifies only a fraction of the candidates. |

Windows that contain NaN are skipped. A constant window has distance 0 to a constant query and √m to any other
query.

### Benchmark (`PatternBenchmarkTest`, writes `target/pattern-benchmark.md`)

Setup: 4 turbines × 8 h at 1 Hz (115,200 samples). A 90 s gust shape is planted 12 times, with a scale of
1.5–3 m/s, optional time warping and noise.

| distance | warp | noise | recall@12 | latency |
|----------|------|-------|-----------|---------|
| zED | 0 % | 0.05 | 1.00 | ~50 ms |
| DTW (band 0.25) | 0 % | 0.05 | 1.00 | ~0.6 s (≈ 75 % of windows pruned) |
| zED | 25 % | 0.05 | 0.75 | |
| DTW (band 0.25) | 25 % | 0.05 | **1.00** | |
| zED | 0 % | 0.20 | 0.83 | |
| DTW (band 0.25) | 0 % | 0.20 | 0.42 | |

Reading the table:
* **Time warping.** DTW handles it; zED does not.
* **Noise.** zED is more robust. A wide DTW band also bends turbulent wind into false matches.
* **Recommendation.** Use zED by default. Use DTW with a narrow band (0.05–0.1) when time warping is expected.

## REST and Python

`RetrievalServer` is a thin JDK HTTP server with these endpoints:

```
GET  /v1/episodes?metric=&tag.turbine=T07&from=ISO&to=ISO&limit=&clip=true&resample=PT1M&agg=MEAN
GET  /v1/episodes/{id}
POST /v1/patterns/search   {"pattern":[…] | "episodeId","offset","length", "metric","tags","from","to","distance","band","topK"}
POST /v1/episodes/similar  {"episodeId", …}
POST /v1/segments          {"strategy","params":{…}, "metric","tags","from","to"}
GET  /v1/inventory         every series of the store with its metadata
```

### Series inventory

`GET /v1/inventory` answers with an `opentsx.ts-inventory.v1` document:

| Part | Content |
|------|---------|
| `source` | `kind` (`kafscale`, `iceberg`, `demo`), `domain`, topic names by role, `truncated` when the store holds only a part |
| `totals` | series, episodes, samples, time range, scenario names found in the labels |
| `series[]` | `seriesId`, `metric`, `tags`, `unit`, episodes, samples, `from`, `to`, `intervalMicros`, `min`, `mean`, `max`, `missing`, `labels` (distinct values per episode label, at most 8), `marks` |
| `scenario`, `channels` | the files given with `--scenario` and `--catalog`, unchanged |

`marks` are the ground-truth labels of the episodes (`gt.operation`, `gt.sensor_fault`) as time ranges; adjacent
episodes with the same value are joined. They are as fine as the episodes are long. Episodes cut out of other
episodes (with a parent or source episode) count as `segments`, not as samples.

The inventory is derived by reading every episode, so it is cached (60 s; with `--kafscale` until the feed adds
episodes). On a large Iceberg table the first request after that is slow.

Episodes come back as JSON by default.

With `Accept: application/x-opentsx-avro-stream` they come back as an Avro single-object stream. This gives
identical objects in Java and Python.

Start the server:

```bash
java -cp … org.opentsx.retrieval.server.RetrievalServer --port 8080 --iceberg type=rest,uri=…,warehouse=… [--namespace tsx]
java -cp … org.opentsx.retrieval.server.RetrievalServer --port 8080 --demo true      # synthetic demo store
java -cp … org.opentsx.retrieval.server.RetrievalServer --port 8080 --kafscale host:9092 --domain wind \
     [--refresh PT30S] [--max-episodes 20000]                                         # episodes topic on KafScale
```

With `--kafscale` the server reads `tsx.<domain>.episodes.v2` into memory and follows the topic. It uses manual
assignment without a consumer group and writes nothing. Loading stops at `--max-episodes`. `--scenario <file>` and
`--catalog <file>` (YAML) work with every source and are handed out in the inventory.

Use it from Python:

```python
from opentsx.client import RetrievalClient
c = RetrievalClient("http://localhost:8080")
df   = c.frame(metric="wind.met.wind_speed", tags={"turbine": "T07"}, start="2026-10-01T00:00:00Z", end="2026-10-02T00:00:00Z")
hits = c.search(pattern=gust, metric="wind.met.wind_speed", distance="DTW", band=0.1, top_k=20)   # DataFrame
segs = c.segments("FEATURE_THRESHOLD", {"direction": "ABOVE", "enter": 20, "exit": 18}, metric="wind.met.wind_speed")
```

CI starts the server in demo mode, and the Python client queries it (job "Java → Iceberg → Python").

## Related

* **Power-curve analysis:** method of bins, performance index and underperformance periods. It lives in
  `opentsx-windpark` (`analysis.PowerCurveAnalysis`).
* **Smart Topics (P7):** they build on segmentation, resampling and statistics. See
  [`docs/datamodel-2026/05-SMART-TOPICS.md`](../docs/datamodel-2026/05-SMART-TOPICS.md).
