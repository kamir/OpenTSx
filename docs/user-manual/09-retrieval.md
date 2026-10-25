# 9. Retrieval: segmentation and pattern search

Module: `opentsx-retrieval` ([README](../../opentsx-retrieval/README.md)).

The HTTP server's `--iceberg` mode requires existing `episodes` and `series` tables. It uses the existing-only
store path and does not provision missing namespaces or tables. The writer/ingestion path remains responsible
for provisioning. Existing-only store access does not authenticate HTTP callers or enforce per-user resource grants.

## 9.1 The retrieval API

```java
EpisodeService svc = new EpisodeService(icebergStore);            // reads from the first store with EPISODES

List<Episode> eps = svc.episodes().metric("wind.met.wind_speed").tag("turbine", "T07")
        .between(from, to).resample(Duration.ofMinutes(1), Resampling.Agg.MEAN).limit(100).fetch();
Optional<Episode> one = svc.episode("01JA...");

List<PatternMatch> hits = svc.similarTo(pattern).metric("wind.met.wind_speed").between(from, to)
        .distance(Distance.zNormalizedEuclidean()).topK(20).fetch();
List<PatternMatch> like = svc.similarEpisodes("01JA...").topK(10).fetch();
List<Episode> gusts = svc.segments(Segmenter.extremum(Feature.MAX, 3.0, Duration.ofMinutes(10),
        Duration.ofMinutes(5), Duration.ofMinutes(1), Duration.ofMinutes(2)))
        .metric("wind.met.wind_speed").writeTo(derivedStore).fetch();
```

Segments are **derived episodes**. They are not in the source store unless you write them somewhere with `writeTo`.
To search for a segment's shape, pass its values as the pattern.

## 9.2 Segmentation

| Strategy | Factory | Typical use |
|----------|---------|-------------|
| fixed duration | `Segmenter.fixedDuration(PT10M[, offset])` | SCADA blocks, daily episodes |
| fixed count | `Segmenter.fixedCount(4096, 2048)` | FFT/PSD blocks with overlap |
| extremum | `Segmenter.extremum(MAX\|MIN, prominence, window, distance, pre, post)` | gusts, power peaks |
| threshold | `Segmenter.threshold(ABOVE\|BELOW, enter, exit, pre, post, minDuration, maxDuration)` | storm phases, curtailment, alarms |

Properties of all segmenters:

* **Streaming equals batch.** Detectors are incremental: a segment is emitted only when no later sample can change
  it. `StreamingSegmenter` (bounded memory) and `Segmentations.segment` (batch) therefore produce identical results.
* **Deterministic ids.** Segment ids derive from series, strategy, parameters and start, so re-running is idempotent.
* **Lineage.** Each segment records strategy, parameters, anchor, source episode and parent.

## 9.3 Pattern search

| Search | Method | Exact? |
|--------|--------|--------|
| subsequences, z-normalized Euclidean | MASS (FFT) over runs that span episode boundaries | yes |
| subsequences, DTW | UCR cascade: LB_Kim → LB_Keogh → early-abandoning DTW with a Sakoe-Chiba band | yes |
| whole episodes | z-normalized PAA (64 points) with exact SAX-MINDIST pruning (`EpisodeSummary.sax`) | yes |

Matches never overlap within a series (exclusion zone of half the query length). Windows that contain NaN are skipped.

**Choosing a distance:**

* Use z-normalized Euclidean by default; it is robust to noise.
* Use DTW with a narrow band (0.05–0.1) when the same shape occurs at different speeds.

The benchmark below measures how the two compare. It plants gusts in simulated 1 Hz wind data and reports recall@12.

| | zED | DTW (band 0.25) |
|---|---|---|
| no warp, low noise | 1.00 | 1.00 |
| 25 % time warp | 0.75 | **1.00** |
| noise 0.2 m/s | **0.83** | 0.42 |

## 9.4 REST and Python

Start the server with `bin/opentsx retrieval-server --port 8080 --iceberg …`, or with `--demo true` to serve a
synthetic store. With `--kafscale <host:port> --domain wind` it reads the topic `tsx.wind.episodes.v2` into memory
and follows it. `GET /v1/inventory` lists every series with its metadata; the web workbench uses it to pick series.
The endpoints are listed in [Appendix B](appendix-b-rest.md).

```python
from opentsx import model as m
from opentsx.client import RetrievalClient
c = RetrievalClient("http://localhost:8080")
eps  = c.episodes(metric="wind.met.wind_speed", tags={"turbine": "T07"}, start="2026-10-01T00:00:00Z", end="2026-10-02T00:00:00Z")
df   = c.frame(metric="wind.met.wind_speed", tags={"turbine": "T07"}, resample="PT1M")
segs = c.segments("FEATURE_THRESHOLD", {"direction": "ABOVE", "enter": 20, "exit": 18, "post": "PT1M"}, metric="wind.met.wind_speed")
hits = c.search(pattern=m.values(segs[0]), metric="wind.met.wind_speed", distance="zED", top_k=20)   # DataFrame
like = c.similar(eps[0]["episodeId"], top_k=5)
```

Episodes travel as an Avro single-object stream. The Python records are therefore exactly what Java wrote.
