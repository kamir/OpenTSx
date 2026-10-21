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

```text
GET  /v1/episodes?metric=&tag.turbine=T07&from=ISO&to=ISO&limit=&clip=true&resample=PT1M&agg=MEAN
GET  /v1/episodes/{id}
POST /v1/patterns/search   {"pattern":[…] | "episodeId","offset","length", "metric","tags","from","to","distance","band","topK"}
POST /v1/episodes/similar  {"episodeId", …}
POST /v1/segments          {"strategy","params":{…}, "metric","tags","from","to"}
GET  /v1/inventory         every series of the store with its metadata
GET  /v1/dags              the DAGs of the domain: what was deployed or stopped, what the processor reports
POST /v1/dags/deploy       opentsx.dag.v1 (YAML): hands the DAG to the vector processor
POST /v1/dags/stop?name=   stops the DAG of that name
```

### Vector topics

With `--kafscale` the server also reads the vector topics of the domain (`tsx.<domain>.smart.<name>.vectors.v1`,
records `opentsx.vector.v1`). Every component of a vector is offered as a series `smart.<name>.<component>` with the
tags of the vector key plus `vector=<name>`; the inventory lists the topics under `source.vectorTopics`.

Vector topics are written by the vector processor (below). The server is its front door:

* `GET /v1/dags` lists the DAGs of the domain: what was asked for (deployed or stopped, by whom, when) and what
  the processor reports per sink (state, records, how far the data goes, what it waits for). It needs no token.
* `POST /v1/dags/deploy` (body: the DAG as YAML) checks that the processor can run the DAG and appends it to the
  control topic. `POST /v1/dags/stop?name=<dag>` appends the stop. Both exist only with `--dag-runs true`.
* The processor runs `align.vector` on the series of the source topic only (`in: [decode]`); any other operator
  before the vector is refused with the id of the node. `source.series` must name the series.
* **Run token.** With `--dag-run-token <token>` or the environment variable `OPENTSX_DAG_RUN_TOKEN` the two write
  routes need the token in the header `X-OpenTSx-Run-Token`; anything else gets 401. Without a token, whoever
  reaches the port can deploy and stop. The SaaS backend holds the token and forwards for its users
  (`POST /api/v1/dags/deploy`, `/stop`); it names the user in `X-OpenTSx-By`, which ends up in the control record.

The operator is `org.opentsx.retrieval.vector.VectorAligner`. It passes the test vectors shared with the web
workbench (`docs/datamodel-2026/testvectors/align-vector.v1.json`); the rules are in
`docs/datamodel-2026/11-SMART-TOPIC-PROZESSOR-DEVOPS.md`, section 5a.

See the [Kafka-native streaming operating guide](../docs/devops/kafka-native-streaming.md) for the supported
series and vector operators, strict deployment checks, restart semantics and broker acceptance.

### Vector processor

`org.opentsx.retrieval.vector.VectorProcessorApp` (`opentsx vectors`) keeps vector sinks up to date. It follows the
episodes topic and appends measuring points as they become final, where the run above is one pass.

```bash
opentsx vectors --kafscale host:9092 --domain wind --state /state              # the processor, exactly one per domain
opentsx vectors deploy --kafscale host:9092 --domain wind --dag flow.dag.yaml  # deploy a DAG, or replace it
opentsx vectors stop   --kafscale host:9092 --domain wind --name <dag name>
```

* Deployments live in the control topic `tsx.<domain>.dags.v1` (latest record per DAG name), so they survive a
  restart. The processor reports per sink to `tsx.<domain>.dag-status.v1`.
* A tick is written when every series named in `source.series` has passed its end, plus `lateness` of the node.
  Until every named series was seen once, the sink waits; the status says for which.
* Per series, episodes must arrive in time order. One that does not continue its series is skipped and counted.
* A checkpoint per sink in `--state` holds positions and state. It is written after the broker confirmed the
  records, so a crash repeats at most the records since the last checkpoint, with the same values.
* A changed DAG starts over from the beginning of the topic (`source.start: latest`: from now).

Limits: one processor per domain, no idle timeout for a series that falls silent, vectors on source series only.
The rules are in `docs/datamodel-2026/11-SMART-TOPIC-PROZESSOR-DEVOPS.md`, section 5b.

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
     [--refresh PT30S] [--max-episodes 20000] [--dag-runs true]                       # episodes topic on KafScale
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
