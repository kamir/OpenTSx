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
| `Paa`, `Sax` | Symbolic representation. `Sax.mindist` is a lower bound of the Euclidean distance, used as an exact prefilter. A word has the form `"16x8:abcd…"`. `EpisodeSimilarity.annotate` stores it in `EpisodeSummary.sax`, and the search takes a stored word with the same parameters; no writer in this repository calls `annotate`, so the search computes the word of every candidate. |
| `Dtw` | DTW with a Sakoe-Chiba band, early abandoning, LB_Kim and LB_Keogh (the UCR-suite cascade) |
| `PatternSearch` | Exact top-k search for **non-overlapping** subsequences over `Run`s (series assembled across episode boundaries, split at gaps). With DTW every window is z-normalized on its own before the cascade. |
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
* **Noise.** zED holds up better. A wide DTW band also bends turbulent wind into false matches.
* **Recommendation.** Use zED by default. Use DTW with a narrow band (0.05–0.1) when time warping is expected.

## REST and Python

`RetrievalServer` is a thin JDK HTTP server with these endpoints. [Appendix B](../docs/user-manual/appendix-b-rest.md)
lists the parameters, bodies and answers of each.

```text
GET  /v1/health            {"status":"ok"}
GET  /v1/episodes?metric=&tag.turbine=T07&from=ISO&to=ISO&limit=&clip=true&resample=PT1M&agg=MEAN
GET  /v1/episodes/{id}
POST /v1/patterns/search   {"pattern":[…] | "episodeId","offset","length", "metric","tags","from","to","distance","band","topK"}
POST /v1/episodes/similar  {"episodeId", …}
POST /v1/segments          {"strategy","params":{…}, "metric","tags","from","to"}
GET  /v1/inventory         every series of the store with its metadata
GET  /v1/dags              the DAGs of the domain: what was deployed or stopped, what the processor reports
POST /v1/dags/deploy       opentsx.dag.v1 (YAML): hands the DAG to the vector processor
POST /v1/dags/stop?name=   stops the DAG of that name
GET  /v1/systems           the systems of --systems, each with the number of its signals by source and by state
GET  /v1/systems/{id}/signals, /plan, /decisions, /scenario
POST /v1/systems/{id}/signals, /plan, /decisions   the same with {"bindings":[…]} laid over the document
POST /v1/systems/{id}/runs                computes a run of the twin and keeps it in memory
GET  /v1/systems/{id}/runs, /runs/{run}, /runs/{run}/series?keys=, /runs/{run}/compare?base=
POST /v1/systems/{id}/generate            writes the series of a demo generator to tsx.<domain>.synthetic.v2
```

Numbers in a POST body are JSON numbers. A string where a number belongs, such as `"topK": "ten"` or
`"topK": "10"`, is a 400 that names the field. `params.prominence` of `FEATURE_EXTREMUM` and `params.enter` of `FEATURE_THRESHOLD` are required.
A JSON body over 262144 bytes (`RetrievalServer.MAX_BODY_BYTES`) and a DAG over 262144 bytes
(`RetrievalServer.MAX_DAG_BYTES`) are a 413. A `resample` step that gives more than
100000 points for one episode (`Resampling.MAX_POINTS`) is a 400. A query parameter the route does not take, such as
the typo `metrc=`, is a 400 that names it, and so is `agg` without `resample`.
`topK` takes 1 to 1000 (`EpisodeService.MAX_TOP_K`), and `pattern` and `length` take 4 to 100000 values
(`EpisodeService.MAX_PATTERN_LENGTH`). `limit` takes 1 to 20000, and a request selects at most 20000 episodes
(`EpisodeService.MAX_EPISODES`); beyond a limit the answer is a 400 that names it. A segment parameter or a piece of
an episode out of range is a 400 that names the field and the range, for example
`params.overlap: 10 is out of range; expected a whole number from 0 to 9, below params.n` or
`offset: 595 is out of range; expected a whole number from 0 to 590, so that 10 values fit into the 600 of episode <id>`.

Every error answer is `{"error": "<text>", "type": "<type>"}`. A malformed parameter names itself and what it takes,
for example `limit: "abc" is not an int; expected a whole number from 1 to 20000`. A body that is not valid JSON is a
400 `the body is not valid JSON: <parser message>`, a DAG that is not valid YAML a 400
`the DAG is not valid YAML: <parser message>`. An unknown episode is a 404 on `GET /v1/episodes/{id}`,
`POST /v1/episodes/similar` and `POST /v1/patterns/search`. A known path with a method it does not take is a 405 with
an `Allow` header; any other path is a 404 `no such endpoint: <path>`. A fault in the server is a 500
`internal error` and one ERROR line `request failed method=<method> path=<path> status=500 cause=<causes>` in the log;
the stack trace is logged at DEBUG. A write the broker does not take (`POST /v1/dags/deploy`, `POST /v1/dags/stop`,
`POST /v1/systems/{id}/generate`) is a 503 `cannot reach the broker; try again` with type `unavailable`; the answer
names no broker address, and the ERROR line `request failed ... status=503 cause=<causes>` names the topic and the
error of the Kafka client. The statuses and types are in [Appendix B](../docs/user-manual/appendix-b-rest.md).

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
* **Run token.** `--dag-runs true` needs a token in the environment variable `OPENTSX_DAG_RUN_TOKEN`
  (`read -rs OPENTSX_DAG_RUN_TOKEN && export OPENTSX_DAG_RUN_TOKEN` before the start); without one the server does not
  start (exit code 2). The two write routes then need the token in the header `X-OpenTSx-Run-Token`; anything else
  gets 401. The command line refuses `--dag-run-token`. `--open-dag-runs` accepts runs without a token for a local
  run: the server then listens on 127.0.0.1 only. The SaaS backend holds the token and forwards for its users
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

`opentsx vectors <run|deploy|stop> --help` lists the keys of each command. The options, the DAG name and the DAG file
are checked before the first call to the broker: a missing `--name` or `--dag`, an unknown option, a misspelled command
or a DAG the processor would refuse exits with code 2 and writes nothing. A runtime failure exits with code 1.

* Deployments live in the control topic `tsx.<domain>.dags.v1` (latest record per DAG name), so they survive a
  restart. The processor reports per sink to `tsx.<domain>.dag-status.v1`.
* A tick is written when every series named in `source.series` has passed its end, plus `lateness` of the node.
  Until every named series was seen once, the sink waits; the status says for which.
* Per series, episodes must arrive in time order. One that does not continue its series is skipped and counted.
* A record of the source topic that does not decode is skipped and counted in `undecodable` of the status of the sink.
  The first one of each partition is logged at WARN:
  `undecodable record skipped and counted in undecodable; later ones of this partition are only counted dag=<dag> sink=<vector> topic=<topic> partition=<n> offset=<offset> cause=<causes>`.
* A checkpoint per sink in `--state` holds positions and state. It is written after the broker confirmed the
  records, so a crash repeats at most the records since the last checkpoint, with the same values.
* A checkpoint that cannot be read, or holds no JSON object, fails the sink: the status says `failed` and names the
  file in `error`, and the file stays as it is. Repair it, or delete it to start the sink over.
* A changed DAG starts over from the beginning of the topic (`source.start: latest`: from now).
* Numbers in a node of a series sink, such as `factor` of `convert.unit` or `min` of `dq.checks`, are YAML numbers.
  Text there, also a number in quotes such as `"1.5"`, refuses the DAG with the node and the field, for example
  `node n3: factor: "1,5" is not a number; expected a number without quotes, such as 0.01 or 1e-3`.
  `POST /v1/dags/deploy` answers 400. `vectors deploy` stops with that error, prefixed with the file, and exit code 2
  and writes nothing to the control topic. A DAG already in the control topic gets the status `failed` with that error.
* A `feature.rolling` window after a `resample` step may span at most 100000 of its intervals. A longer one refuses
  the DAG the same way, for example `node r: window: PT2H holds 720000 values at the interval PT0.01S of its input;
  expected a window of at most PT16M40S (100000 values) or a longer resample interval before it`. Without a `resample`
  step before it, a window that holds more than 100000 values fails the sink with the node in `error`.
* `every` of `resample` and `align.vector` and `window` of `feature.rolling` take PT0.000001S, the microsecond of the
  time axis, to PT2562047H47M16.854775807S (about 292 years); `maxAge` and `lateness` take at most as much. A duration
  outside its range refuses the DAG the same way, for example `node n1: every: "PT0.0000001S" is out of range;
  expected an ISO-8601 duration from PT0.000001S to PT2562047H47M16.854775807S, such as PT60S`.
* An error while the processor applies a deployment or a stop, such as a status record it cannot send, ends the
  processor with that error. Started again, it reads the control topic from the beginning.
* A sink whose checkpoint lies before the log start (the processor was down longer than retention) reads on from the
  log start. The status of the sink counts the records it lost in `lostToRetention`, which the checkpoint keeps, and
  the log has one WARN line per partition:
  `retention deleted records before the sink read them; reading on from the log start dag=<dag> sink=<vector> topic=<topic> partition=<n> saved=<offset> logStart=<offset> lost=<n>`.
* A record on the control topic that is no `opentsx.dag-control.v1` record with a `dag`, a record without a value
  included, is ignored and counted in `ignoredControlRecords` of the status of every sink. The first one is logged
  at WARN with topic, partition and offset.
* The processor logs one line per event: `sink deployed`, `dag stopped` and `final checkpoint written` at INFO;
  `dag refused`, `control topic cannot be read now` (and `control topic readable again` at INFO) and
  `status record not sent` (and `status records are sent again` at INFO) at WARN. When a stopped or replaced DAG
  leaves its checkpoint behind, the WARN line `checkpoint not removed; delete it by hand file=<path> cause=<causes>`
  names the file to delete. A failed sink logs
  `sink failed; the next try is in <duration> dag=<dag> sink=<vector> cause=<causes>` at ERROR for every failed try;
  with `OPENTSX_LOG_LEVEL=debug` the stack trace follows once per distinct failure.
* On SIGTERM the processor logs `stopping; waiting for the final checkpoints wait=PT1M`, one
  `final checkpoint written dag=<dag> sink=<vector> records=<n> positions=<topic>-<partition>:<offset>,...` per sink
  with unsaved progress, and `stopped`. When it has not ended after 60 s, the WARN line
  `the processor did not stop in time; the final checkpoints may be missing wait=PT1M` follows, and the next start
  repeats what was read since the last checkpoint.

Limits: one processor per domain, no idle timeout for a series that falls silent, vectors on source series only.
The rules are in `docs/datamodel-2026/11-SMART-TOPIC-PROZESSOR-DEVOPS.md`, section 5b.

### Series inventory

`GET /v1/inventory` answers with an `opentsx.ts-inventory.v1` document:

| Part | Content |
|------|---------|
| `source` | `kind` (`kafscale`, `iceberg`, `demo`), `domain`, topic names by role, `truncated` when the store holds only a part. With `--kafscale` also `dagRuns`, `dagRunAuth`, `vectorTopics`, `unreadableVectorTopics` (vector topics the last read could not get), the counts of skipped records `undecodable`, `lostToRetention`, `droppedVectorRows`, `droppedControlRecords` and `droppedStatusRecords`, and `refreshFailures` (refreshes that failed since the last one that worked) |
| `totals` | series, episodes, samples, time range, scenario names found in the labels |
| `series[]` | `seriesId`, `metric`, `tags`, `unit`, episodes, samples, `from`, `to`, `intervalMicros`, `min`, `mean`, `max`, `missing`, `labels` (distinct values per episode label, at most 8), `marks` |
| `scenario`, `channels` | the files given with `--scenario` and `--catalog`, unchanged |

`marks` are the ground-truth labels of the episodes (`gt.operation`, `gt.sensor_fault`) as time ranges; adjacent
episodes with the same value are joined. They are as fine as the episodes are long. Episodes cut out of other
episodes (with a parent or source episode) count as `segments`, not as samples.

The inventory is derived by reading every episode, so it is cached (60 s; with `--kafscale` until the feed adds
episodes). On a large Iceberg table the first request after that is slow.

Episodes come back as JSON by default.

The `--iceberg` mode opens existing `episodes` and `series` tables through `IcebergSeriesStore.openExisting`.
Missing tables or namespaces fail startup without creating them. The store rejects writes and does not expose
its mutable episodes table. Provision and populate the tables through the existing writer path before starting
Retrieval. This mode does not add user authentication or resource authorization to the HTTP endpoints.

With `Accept: application/x-opentsx-avro-stream` they come back as an Avro single-object stream. This gives
identical objects in Java and Python.

Start the server:

```bash
bin/opentsx retrieval-server --port 8080 --iceberg type=rest,uri=…,warehouse=… [--namespace tsx]
bin/opentsx retrieval-server --port 8080 --source demo                               # synthetic demo store
bin/opentsx retrieval-server --port 8080 --kafscale host:9092 --domain wind \
     [--refresh PT30S] [--max-episodes 20000] [--dag-runs true]                       # episodes topic on KafScale
```

`bin/opentsx retrieval-server --help` lists every key. `--source` (`opentsx.retrieval.source`) chooses the store; the
default `auto` takes `iceberg` when `--iceberg` is on the command line, `kafscale` when `--kafscale` is, else `demo`, so
`KAFSCALE_BOOTSTRAP` in the environment does not switch it. `--iceberg` together with `--kafscale` is an error. The
server listens on every interface; `--bind <address>` chooses one. `--systems <directory>` serves the system documents
of that directory under `/v1/systems`; the files the documents refer to are relative to `--systems-root <directory>`
(default `.`, the working directory). Every value, the scenario, the catalog and the
system documents (`--systems` must name a directory with at least one `*.system.yaml`; when two documents declare one
system id, the first in file-name order keeps it and lists
`duplicate system id <id>: <file> declares it too; give every system document its own id` among its
problems, so it generates nothing) are checked before a store,
a client or the port opens. A usage or configuration error exits with code 2 and one line on stderr, for example
`opentsx: --prot: unknown option of retrieval-server; did you mean --port?`; a failure while the store opens or the
port is bound exits with code 1. The log starts with one `starting retrieval-server: <key>=<value> (<source>), ...`
line, the run token as `******`, and ends the start with one `listening on <address>:<port>, source <source>` line.
With `--systems` it logs one `system loaded system=<id> signals=<n> problems=<n>` line per document and, when a
document has problems, one WARN line
`system documents have problems; GET /v1/systems/<id>/signals lists them systems=<id>,... problems=<n>`; the problems
themselves are logged at DEBUG. On SIGTERM the server logs `stopping port=<port>`, closes the port and the KafScale
feed, and logs `stopped`.

With `--kafscale` the server reads `tsx.<domain>.episodes.v2` into memory and follows the topic. It uses manual
assignment without a consumer group. Without `--dag-runs true` it writes nothing. With `--dag-runs true` it writes on
request only: `POST /v1/dags/deploy` and `POST /v1/dags/stop` create the control topic `tsx.<domain>.dags.v1` and the
status topic `tsx.<domain>.dag-status.v1` when they are missing and append one record to the control topic;
`POST /v1/systems/{id}/generate` creates `tsx.<domain>.synthetic.v2` when it is missing and appends the generated
episodes to it. Loading stops at `--max-episodes`. The first read logs
`feed loaded topic=<topic> episodes=<n> truncated=<true|false> vectorTopics=<topic>,... <counts>`, and a refresh that
added episodes or changed a count logs `feed refreshed episodes=<n> <counts>`, where `<counts>` is
`undecodable=<n> lostToRetention=<n> droppedVectorRows=<n> droppedControlRecords=<n> droppedStatusRecords=<n>`. The
first dropped vector row, control record or status record of a partition is logged at WARN
(`record dropped and counted in <counter>; ...` with topic, partition and offset). A topic that cannot be read is
logged at WARN when the failure starts and at INFO when it ends; a refresh that fails is logged
(`feed refresh failed`, ERROR when the cause is an Error) and counted in `refreshFailures`, and the next refresh tries
again. `--scenario <file>` and
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

## Generate the operator chapter

`org.opentsx.retrieval.system.OperatorCatalog` reads the operator descriptions `systems/processors/*.yaml` (schema
`opentsx.processor.v1`) and writes [`docs/user-manual/17-operators.md`](../docs/user-manual/17-operators.md). It stops
with a list of problems when a runtime marked `available` names a Java class, Python name or workbench operator that
does not exist, when a planned runtime has no plan, or when an example, chart or link is missing.

```bash
java -cp "$(bin/opentsx classpath opentsx-retrieval)" org.opentsx.retrieval.system.OperatorCatalog
```

`OperatorCatalogTest` runs the same checks and fails when the committed chapter differs from the generated one. The
operator examples of this module are in `src/test/java/org/opentsx/retrieval/examples/`.

## Related

* **Power-curve analysis:** method of bins, performance index and underperformance periods. It lives in
  `opentsx-windpark` (`analysis.PowerCurveAnalysis`).
* **Smart Topics (P7):** they build on segmentation, resampling and statistics. See
  [`docs/datamodel-2026/05-SMART-TOPICS.md`](../docs/datamodel-2026/05-SMART-TOPICS.md).
