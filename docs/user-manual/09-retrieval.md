# 9. Retrieval: segmentation and pattern search

Module: `opentsx-retrieval` ([README](../../opentsx-retrieval/README.md)).

`bin/opentsx retrieval-server --iceberg` needs existing `episodes` and `series` tables. It opens them with
`IcebergSeriesStore.openExisting` and creates no namespace and no table ([chapter 5](05-stores.md#52-choose-a-store)).
Create them first with a writer, for example `bin/opentsx windpark-sim --iceberg` ([chapter 2](02-getting-started.md)).
The read routes have no authentication ([chapter 12](12-operations.md#125-secure-a-deployment)).

## 9.1 Query episodes in Java

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

<a id="92-segmentation"></a>

## 9.2 Cut a series into segments

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

<a id="93-pattern-search"></a>

## 9.3 Search for a pattern

| Search | Method | Exact? |
|--------|--------|--------|
| subsequences, z-normalized Euclidean | MASS (FFT) over runs that span episode boundaries | yes |
| subsequences, DTW | UCR cascade: LB_Kim → LB_Keogh → early-abandoning DTW with a Sakoe-Chiba band | yes |
| whole episodes | z-normalized PAA (64 points); with zED, exact pruning by SAX MINDIST; with DTW, every candidate is compared | yes |

* **Overlap:** two matches in the same run start at least half the query length apart (rounded down, at least one
  sample). A run is the samples of one series across consecutive episodes; it breaks at a time gap or a change of the
  sampling interval. A better match replaces worse ones that start closer.
* **NaN:** windows that contain NaN are skipped.
* **Whole episodes:** the query episode needs at least 64 values, and episodes with fewer than 64 values are skipped.
  The SAX word is computed at query time unless the episode carries one in `EpisodeSummary.sax`.

**Choosing a distance:**

* Use z-normalized Euclidean by default. In the benchmark below it keeps more recall than DTW on noisy data.
* Use DTW with a narrow band (0.05 to 0.1) when the same shape occurs at different speeds.

The benchmark below measures how the two compare. It plants gusts in simulated 1 Hz wind data and reports recall@12.

| | zED | DTW (band 0.25) |
|---|---|---|
| no warp, low noise | 1.00 | 1.00 |
| 25 % time warp | 0.75 | **1.00** |
| noise 0.2 m/s | **0.83** | 0.42 |

## 9.4 Query over REST and from Python

Start the server with `bin/opentsx retrieval-server --port 8080 --iceberg …`. Without `--iceberg` and `--kafscale`
it serves a synthetic demo store: three series `demo.signal` (tags `n=0`, `n=1`, `n=2`), each 6 h at 1 Hz in
10-minute episodes. The server ignores options it does not know, so `--demo true` changes nothing.

With `--kafscale <host:port> --domain wind` it reads the topic `tsx.wind.episodes.v2` into memory and follows it:

* It reads new records every `--refresh` (default `PT30S`).
* It holds at most `--max-episodes` episodes (default `20000`). At that limit it stops reading the episodes topic,
  and `GET /v1/inventory` shows `"truncated": true` under `source`.
* It also reads `tsx.wind.clean.v2` and `tsx.wind.synthetic.v2` once they exist, without that limit.
* It serves each component of a vector topic `tsx.wind.smart.<name>.vectors.v1` as a series with the metric
  `smart.<name>.<component>` and the tag `vector=<name>`.

`GET /v1/inventory` lists every series with its metadata; the web workbench uses it to pick series. The endpoints,
the run token and the error codes are in [Appendix B](appendix-b-rest.md).

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

An error answer of the server raises `RetrievalError`; its `status` holds the HTTP status. `c.episode(<id>)` returns
`None` for an unknown episode.
