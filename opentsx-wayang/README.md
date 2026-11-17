# opentsx-wayang

OpenTSx time-series operators as Apache Wayang 1.1.1 operators on Wayang's Java platform (Track A of
[concept 20](../docs/datamodel-2026/20-WAYANG-TS-OPERATORS.md), specification
[S1 to S7](../docs/datamodel-2026/20-WAYANG-TS-OPERATORS-SPEC.md), tasks in
[TASK-020](../EVOLUTION/TASK-020-wayang-ts-operators.md)). Verified with JDK 21 on 2026-11-17.

Status: phase A2 and the retrieval operators of A3. The module reads and writes
KafScale topics from Wayang plans, runs checkpointed increments, and executes
the clean chain and window statistics. It also contains the pure Java
`TsxPairXcorr` and `TsxPairEs` kernels. Their APIs have no Wayang types and do
not depend on `opentsx-core`. Align and cross-correlation mappings are
registered separately from the retrieval mappings.
The statistics and retrieval modules check the shared references. Track A
also checks all five statistics cases through Wayang plans.

`segment.threshold`, `segment.extremum`, `sax.word`, and `dq.result` have native
Java mappings in `TsxRetrievalMappings`. All 26 shared cases execute through
Wayang; stream/batch checks cover all six chunk and series-order combinations
per case. These operators are available through the Java API.

## Compute pair cross-correlation

`org.opentsx.wayang.ops.TsxPairXcorr` computes lagged cross-correlation, the
signed absolute peak, link strength, and centred pair windows. It follows
`xcorr.ts` and `windowsOf` in `pairs.ts` in the web workbench. Positive output
lag means that B follows A. Non-finite samples contribute zero after centring;
constant inputs produce NaN. Invalid lags are rejected with the workbench's
error message.

`compute` consumes equal-length arrays already aligned to the epoch clock;
the first sample must lie on that clock. Aggregation, filling, and episode
pairing happen before this call. `pairSettings` parses the workbench settings,
including the default coverage `0.8`, episode windows, and width-sized steps.
Off-grid starts fail with
`The first aligned sample must start on the epoch clock.` Align the first
sample to a clock tick before calling `compute`.

The regression tests load the repository's shared
`docs/datamodel-2026/testvectors/pair-xcorr.v1.json` directly. All five cases
use an absolute error below `5e-13`.

## Compute pair event synchronisation

`org.opentsx.wayang.ops.TsxPairEs` detects local maxima above a population
standard-deviation threshold, computes directional event synchronisation,
and calculates a surrogate baseline by shuffling inter-event intervals.
`Mulberry32` supplies the repeatable shuffle sequence. The implementation
follows `event_sync.py` and the web workbench's `es.ts`.

The regression tests load the repository's shared
`docs/datamodel-2026/testvectors/pair-es.v1.json` directly. All eight cases
require identical event lists and an absolute error below `5e-13` for
`q_total`, `q_ab`, `q_ba`, and `q_rand`. The random sequence is compared
exactly.

The [unified delivery plan](../docs/devguide/integration/wayang-unified-plan.md) covers UI DAGs deployed through
Wayang to Java and Rust nodes, plus the bounded storage profile. The implemented
[shared contract foundation](../docs/devguide/integration/wayang-shared-contracts.md) lives in `opentsx-model`
and `rust/tsx-contracts`; it validates offline assignments and does not deploy a Rust runtime.
The separate [`tsx-runner` 0.1.0](../rust/tsx-runner/README.md) executes a capped bounded topic from trusted local
configuration. It supports pass-through and three cleaning operations; this module does not dispatch that binary
through a remote Wayang platform.

## Use the plugin

Register `TsxJavaPlugin`, not `Java.basicPlugin()`:

```java
new WayangContext(new Configuration()).with(new TsxJavaPlugin()).execute(new WayangPlan(sink));
```

`Java.basicPlugin()` also registers Wayang's Amazon S3, Google Cloud Storage and Azure mappings. OpenTSx excludes
those SDKs, so a plan built with `Java.basicPlugin()` fails with
`NoClassDefFoundError: software/amazon/awssdk/core/ResponseInputStream`. `TsxJavaPlugin` registers the Java platform,
its channel conversions and the mappings OpenTSx uses: collection source,
map, local callback sink, the OpenTSx topic source and sink, clean chains,
window statistics, and the retrieval operators.

## Compile a local clean DAG

You need a valid series DAG, its SHA-256 hash, the domain's `TopicLayout`, and the
processor descriptions in `systems/processors/`.
`DagToWayang.compile(dag, layout, specHash, catalogDirectory)` validates structure
through `DagPlan`, validates parameters against those descriptions, and applies
optional defaults to a copy. Required parameters remain required when a UI default
exists. Type, range, and enum errors use messages 13 to 16 of specification S2.5.

Read `series()` to select a sink, then pass `plan(bootstrap, sinkName)` to
`TsxIncrement.run` or `TsxIncrement.loop`. Each consecutive clean chain becomes
one `TsxSeriesChain`, preserving the final series key and episode identity. This
compiler accepts local episode sinks; it does not compile statistics sinks or
federated plans. `DagToWayang.parameters` also validates statistics parameters
without building a sink.

## Compute window statistics

`new TsxStatsWindow(node, config, state)` accepts `Episode` and `Observation`
records and retains open `StatsEngine` windows across increments. Use this mode
for a continuing input. `TsxStatsWindow.bounded(node, config, state)` flushes the
engine after a complete input, including `GLOBAL` output. Empty engine input
produces no records.

`TsxStatsWindow.window(node, config, state, seriesId, startMicros)` requests one
explicit window per configured duration for a known series. The start must be
epoch-aligned for every duration. This bounded mode emits an empty
`StatsAccumulator` window when no samples arrived, with NaN moments and an empty
sketch. It rejects other series, samples outside the requested windows, and
incremental saved state. These Java execution modes do not add DAG parameters.

## Align series into vectors

`TsxAlignVector` runs `align.vector` through `VectorStream` of `opentsx-retrieval`. A Wayang operator has at most two
inputs, so mark every input with its name (`TsxTagged`) and join the inputs with `UnionAllOperator`. Give the operator
the series ids of every input. Series of an input the operator does not know are ignored. The operator keeps open ticks
in `TsxState`; set `flush` only for a complete input. `TsxVectorSink` writes the `opentsx.vector.v1` records to a vector
topic, key = canonical text of the vector key, and returns after every record is acknowledged.

```java
MapOperator<SpecificRecord, TsxTagged> tag = new MapOperator<>(r -> new TsxTagged("raw", (Episode) r),
        SpecificRecord.class, TsxTagged.class);
TsxAlignVector align = new TsxAlignVector("v", "turbines", dag, specHash, params, Map.of("raw", ids), 0, false, state);
TsxVectorSink sink = new TsxVectorSink(bootstrap, layout.smartVectors("turbines"));
source.connectTo(0, tag, 0);
tag.connectTo(0, align, 0);
align.connectTo(0, sink, 0);
```

## Compare two series in windows

`TsxPair` runs `pair.xcorr` and `pair.es` by the rules of `pairs.ts` of the workbench. Mark the episodes of A with
input `a` and those of B with input `b` (`TsxTagged`). Every series of `a` is compared with every series of `b`:
`pairing` `same` pairs episodes with equal ranges, `intersect` overlapping episodes on their intersection. Each pair
range gives one episode per field, series `pair.<method>.<field>` with the tags `a`, `b`, `window` and `step`, parent
the episode of A. An episode waits in `TsxState` until every series of the other input has passed its end. Without
`every` the clock is the larger sampling interval of the two paired episodes.

```java
TsxPair xcorr = new TsxPair("x", "pair.xcorr", Map.of("lags", 5, "window", 32, "step", 8),
        Set.of(windSpeedId), Set.of(powerId), specHash, state);
```

## Run a plan in increments

`TsxIncrement.run` reads the end offsets of the input topic, runs the plan over `[checkpoint, end)` and writes the new
checkpoint only after every sink record was acknowledged. A failed plan leaves the checkpoint as it was and throws
`TsxIncrementException` with the root cause as message.

```java
TsxIncrement.Config config = new TsxIncrement.Config(bootstrap, "job", 1, specHash, layout.episodes(),
        Set.of("8985f02b48c77195"), "earliest", Path.of("state/job.checkpoint.json"));
TsxIncrement.run(config, (source, state) -> {
    TsxTopicSink sink = new TsxTopicSink(bootstrap, layout.clean());
    source.connectTo(0, sink, 0);
    return sink;
});
```

| Class | Role |
|---|---|
| `TsxTopicSource`, `JavaTsxTopicSource` | reads fixed offset ranges with manual assignment (no consumer group), checks the schema fingerprint of every record against the plan, decodes Avro single-object records |
| `TsxTopicSink`, `JavaTsxTopicSink` | writes with `KafScaleClients.producer` (no idempotence, `acks=all`), key `seriesId`, record time `tStart`, header `tsx-schema`; maps in UTF-8 key order; returns after every record is acknowledged |
| `TsxCheckpoint` | next offset per partition, job, plan version and `specHash` in a JSON file, written to a temporary file and renamed; a checkpoint of another `specHash` is ignored, an unreadable one fails |
| `TsxIncrement` | one increment as above; `TsxIncrement.loop` runs increments at an interval and reports each |
| `TsxSeriesChain`, `JavaTsxSeriesChain` | a run of clean operators (`dq.checks`, `convert.unit`, `resample`, `model.wind-shear`, `model.power-curve`, `feature.lag`, `feature.rolling`) through `SeriesStream` of `opentsx-retrieval`, with its keys and episode ids |
| `TsxStatsWindow`, `JavaTsxStatsWindow` | `stats.welford-ddsketch` through `StatsEngine` of `opentsx-stats`: `SeriesStats` per series and window |
| `TsxAlignVector`, `JavaTsxAlignVector` | `align.vector` through `VectorStream` with named inputs; input `TsxTagged`, output `TsxVectorRecord` |
| `TsxVectorSink`, `JavaTsxVectorSink` | writes `opentsx.vector.v1` JSON records with `KafScaleClients.producer`; returns after every record is acknowledged |
| `TsxPair`, `JavaTsxPair` | pair methods through `ops/TsxPairWindows`, `ops/TsxPairXcorr` and `ops/TsxPairEs`; output `Episode` |
| `TsxSegment` | Threshold/extremum segments; keyed checkpoint |
| `TsxSaxWord` | SAX words in `EpisodeSummary.sax` |
| `TsxDqResult` | `SeriesStats` to JSON `opentsx.signal.v1` |
| `TsxState` | operator state by DAG node id, read from and written to the checkpoint |
| `TsxStatus` | `opentsx.dag-status.v1` records on `tsx.<domain>.dag-status.v1` with `track` `A`, `job`, `planVersion` |

The source holds the records of one increment in memory. Choose the increment so that one range fits.

The loop reports every state change and sends a heartbeat every 30 seconds while
an increment runs or the loop waits. Status includes `watermark`, `waitingFor`,
and `skippedBlocks` from clean streams. A null watermark means no progress is
known. `records` counts committed input records. A heartbeat failure prevents
the next checkpoint commit. A failed status write is suppressed on an existing
processing exception so that the processing error remains the primary cause.

The checkpoint file is JSON with these fields (`TsxCheckpoint.java`):

| Field | Value |
|---|---|
| `schema` | `opentsx.wayang.checkpoint.v1` |
| `job`, `planVersion` | job id and plan version of the increment |
| `specHash` | SHA-256 of the DAG document; a checkpoint with another value is ignored |
| `topic` | the input topic |
| `next` | partition number (as a string) to the first offset not yet processed |
| `state` | DAG node id to the state of its operator: `SeriesStream.state()` for a clean chain, Base64 of `StatsEngine.writeState` or `StreamingSegmenter.writeState` |

## Segment episodes and annotate SAX words

You need episodes in time order per series. Construct `TsxSegment(node,
segmenter, state)` with a threshold or extremum `Segmenter`. Its checkpoint
keeps the retained sample buffer, source episodes, and detector state per series.
The version and parameters are checked when restoring. Use `TsxSegment.bounded`
only at the end of input to decide pending segments. Segment ids and lineage
follow the existing Java segmenter. The state stores raw floating-point bits,
including NaN payloads, and rejects truncated or incompatible data.

`TsxSaxWord(w, a, specHash)` normalizes each complete episode and fills its SAX
word. `TsxSaxWord.normalized` accepts values already normalized by the caller.
The word length must be positive and the alphabet size must be from 2 to 26.
Raw timestamps and values are preserved. The output has the deterministic
derived-episode id of S6.2 and points to the input episode as its parent.

## Emit data-quality signals

`TsxDqResult(job, site, specHash, expectation, checks, nowMicros)` consumes
`SeriesStats`. It reuses `DataQuality.evaluate` and selects the requested checks.
Supply an explicit clock value in `nowMicros` for freshness checks. Messages use
`Locale.ROOT`. Non-finite result values become JSON null.

Connect its JSON output to `TsxTopicSink`. The sink accepts
`opentsx.signal.v1`, validates the whole JSON batch before sending any record,
and writes canonical JSON with key `seriesId` and header
`tsx-schema: opentsx.signal.v1`. Other JSON schemas are rejected. DQ ids include
the check and both window bounds in addition to the common S6.2 fields, so
different findings remain distinct when their windows end at the same time.
Avro inputs retain their streaming encode-and-send behavior.

## Measure increment latency

Run [`tools/wayang_increment_latency.py`](../tools/wayang_increment_latency.py)
from a clean tracked checkout to measure the real `TsxIncrement` passthrough and
`dq.checks` plus `resample PT1M` plans. The default matrix uses one partition,
intervals of 5, 30 and 60 seconds, 30 increments, and three repetitions. Conditions
run sequentially with a fresh JVM and disposable in-memory KafScale broker.

Read the [operations instructions](../docs/user-manual/12-operations.md#127-measure-wayang-increment-latency)
for the command, metric definitions and isolation limits. The generated
[measurement report](../docs/datamodel-2026/measurements/wayang-increment-latency-2026-11-17/REPORT.md)
identifies the hardware, versions, exact commands and raw samples. Produce ingress
clocks measure transport latency; Kafka record timestamps retain event time.
These measurements cover A1.7, not the broader throughput or Track B matrix.

## Dependencies

The root `pom.xml` pins `wayang.version` and manages `wayang-core`, `wayang-basic` and `wayang-java` with these
exclusions (decision D-T9 in TASK-020):

| Excluded | Pulled in by |
| --- | --- |
| `com.amazonaws:aws-java-sdk-s3` | `wayang-core` |
| `com.google.cloud:google-cloud-storage`, `google-cloud-storage-control` | `wayang-basic` |
| `software.amazon.awssdk:s3` | `wayang-basic` |
| `com.azure:azure-storage-blob`, `azure-security-keyvault-secrets`, `azure-identity` | `wayang-basic` |
| `org.apache.logging.log4j:log4j-slf4j-impl` | `wayang-java` |

Wayang declares Hadoop as `provided`, but its Java platform loads `org.apache.hadoop.io.Writable` at run time. The
module adds the shaded `hadoop-client-api` and `hadoop-client-runtime` in the version the root `pom.xml` manages.

## Test

You need JDK 17 or 21 and Maven 3.8 or later.

```bash
mvn -B -ntp -pl opentsx-wayang -am verify
```

`WayangSmokeTest` maps 1,000 integers through a Wayang plan on the Java platform. `TsxWireVectorsTest`,
`TsxClientSettingsTest` and `TsxCheckpointTest` run without a broker.

The integration tests need a KafScale broker and run only when `KAFSCALE_BOOTSTRAP` is set. They create random test
domains and do not remove their topics; use a disposable broker.

```bash
KAFSCALE_BOOTSTRAP=127.0.0.1:39092 mvn -B -ntp -pl opentsx-wayang -am test
```

Read `opentsx-wayang/target/surefire-reports/`: `TsxPassThroughIntegrationTest`, `TsxCrashRecoveryIntegrationTest`,
`TsxFingerprintIntegrationTest`, `TsxCleanIncrementsIntegrationTest`, `TsxCleanParityIntegrationTest`,
`TsxStatusIntegrationTest` and `TsxVectorIntegrationTest` must show
executed tests with zero failures, errors and skips.
`TsxSignalSinkIntegrationTest` also executes both JSON signal paths with no skips.

`TsxCleanParityIntegrationTest` runs one clean DAG over W, then deploys the same
document to the real `VectorProcessor` through the control topic. It compares
the two disjoint output ranges for identical episode ids, timestamps, and values.
It also checks the Track A status records with the S5.4 assertions of
`TsxStatusIntegrationTest`. `TsxIncrementLoopTest` advances a virtual clock and
scheduler to cover long increments and idle intervals without sleeping.

`TsxCleanVectorsTest` runs all 15 shared clean cases directly and through Wayang.
`TsxStatsVectorsTest` runs all five shared statistics cases through Wayang with
exact field and sketch-byte comparisons. `TsxVectorVectorsTest` runs all six
shared `align.vector` cases through Wayang, once in one run and once in two
increments with the state in between. `TsxPairVectorsTest` runs all five
shared `pair.xcorr` cases through Wayang, also with B in a later increment.
`TsxPairEsWayangTest` runs all four `pair.es` series cases through Wayang,
including a later increment and restored state. The four event-list cases
remain numerical kernel tests because they contain no sampled series.
`DagToWayangErrorsTest` checks the
four parameter messages exactly. CI rejects missing or skipped acceptance tests.

`TsxSegmentVectorsTest`, `TsxSaxVectorsTest`, and `TsxDqVectorsTest` execute all
12 segment, seven SAX, and seven DQ cases from the repository files.
`TsxStreamEqualsBatchTest` compares each case in chunks of 1, 7, and all records
in both series orders, for 156 comparisons. Segment state is checkpointed and
restored between chunks, with a full Avro payload comparison against batch.
SAX treats a complete episode as one record; DQ treats one statistics window as
one record. Their parity fixtures contain 16 records per case. CI requires the
26 vector cases, all 26 parity cases, and both JSON broker tests to execute.

## Check the shared Java references

The shared files in `docs/datamodel-2026/testvectors/` capture existing
statistics and retrieval behavior for TASK-020 A2 and A3:

| Vector file | Owning-module test | Cases |
| --- | --- | ---: |
| `stats-window.v1.json` | `StatsWindowVectorsTest` | 5 |
| `segment-threshold.v1.json` | `SegmentThresholdVectorsTest` | 6 |
| `segment-extremum.v1.json` | `SegmentExtremumVectorsTest` | 6 |
| `sax-word.v1.json` | `SaxWordVectorsTest` | 7 |
| `dq-result.v1.json` | `DqResultVectorsTest` | 7 |

These tests replay every case exactly in `opentsx-stats` and
`opentsx-retrieval`. They read the repository files directly. Run
`python tools/java_operator_vectors.py` from the repository root to
regenerate all five files. You need JDK 17 or 21, Maven, and Python.
The generator is verified with JDK 21 and Python 3.12 on 2026-11-16.
