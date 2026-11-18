# opentsx-wayang

OpenTSx time-series operators on Apache Wayang 1.1.1, with Java execution and a trusted-local bounded Rust platform.
See [concept 20](../docs/datamodel-2026/20-WAYANG-TS-OPERATORS.md), specification
[S1 to S7](../docs/datamodel-2026/20-WAYANG-TS-OPERATORS-SPEC.md) and
[TASK-020](../EVOLUTION/TASK-020-wayang-ts-operators.md). Verified with JDK 21 on 2026-11-17.

Status: phases A2, A3 and the qualified bounded JVM profile of A4, with all 16 native Java entries in frozen
[`tsx-catalog/1`](../systems/catalogs/tsx-catalog-1.yaml). The module reads and
writes KafScale topics from Wayang plans, runs checkpointed increments, and executes
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

## Use frozen catalog 1

The inventory pins 16 processor identities to version 1. Descriptions in
`systems/processors/` supply their parameters, state, merge class and native
Wayang class; `dq.plausible-range` maps to DAG operator `dq.checks`.
`SystemDocTest` rejects missing members, duplicate aliases, version drift,
invalid metadata and broken implementation/vector references.
`TsxCatalogBindingsTest` checks the compiled logical and Java execution types.
Changing an operator's output requires a new operator version and a new
catalog version.

Pair `window` is catalog text, either `episode` or positive decimal seconds.
Direct Java APIs also accept numbers. The local catalog compiler lowers these
Java bindings; the admitted Rust subset described below remains separate.
Read the [catalog manual](../docs/user-manual/17-learn-operators.md#use-frozen-catalog-1)
for runtime selection and the [normative inventory](../docs/datamodel-2026/20-WAYANG-TS-OPERATORS-SPEC.md#s17-catalog-1).

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

The native Java API `TsxPair` also executes `pair.es` on sampled episodes.
Four shared sampled-series cases run through Wayang in one plan and with pair
state restored between increments; four event-list-only cases remain kernel
checks. Read the [native ES instructions](../docs/user-manual/11-classic-analytics.md#run-sampled-event-pairs-through-wayang)
for clock units, seed reduction and settings labels. This API is separate from
a deployed UI DAG binding.

The [unified delivery plan](../docs/devguide/integration/wayang-unified-plan.md) covers UI DAGs deployed through
Wayang to Java and Rust nodes, plus the bounded storage profile. The implemented
[shared contract foundation](../docs/devguide/integration/wayang-shared-contracts.md) lives in `opentsx-model`
and `rust/tsx-contracts`; it validates offline assignments and does not deploy a Rust runtime.
The separate [`tsx-runner` 0.1.0](../rust/tsx-runner/README.md) executes a capped bounded topic from trusted local
configuration. It supports pass-through and three cleaning operations. The separate
[bounded UI rollout](../docs/devguide/integration/wayang-rust-compose.md) uses `RustRollout`, `RustPlugin`,
`RustPlatform` and `RustExecutor` to map one connected source/union/clean/sink stage to one to three HTTP workers.
The backend owns the frozen plan and clock in PostgreSQL; the Java driver has no authoritative deployment journal.
All workers prepare privately before cross-topic series ownership checks and parallel activation. Completed retries
reuse stored receipts; uncertain publication retries the same saved requests while topic ownership remains held.
Activation can expose partial at-least-once output. Placeholder load costs do not select worker placement, and this
local profile does not implement the signed continuous fleet contract.

The [continuous local profile](../docs/devguide/integration/wayang-rust-continuous.md) executes successive finite
stages on the same complete-topic bindings. PostgreSQL persists each plan before execution; Rust workers restore
their own preceding state and verify sequence/hash/cursor continuity. Idle keeps topic ownership. New input and
service restart continue without redeployment. Durable Stop reconciles dispatched work before releasing ownership.
Transient preparation failures retain the exact plan and private candidates for retry before any activation.
This profile retains one immutable chain per worker and does not provide production migration or fencing.

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

<a id="compile-a-catalog-dag"></a>

## Compile a catalog DAG

You need the original workbench YAML, the domain's `TopicLayout`, processor
descriptions in `systems/processors/`, and the sibling frozen inventory in
`systems/catalogs/`. `DagToWayang.compile(document, layout, catalogDirectory)`
parses the original text and computes `specHash()` over its UTF-8 bytes before
applying parameter defaults. Both exported DAG versions are accepted, including
version 1 documents without a `schema` field.

The compiler validates structure, sink identities, catalog versions and runtime
availability before parameters, topics and boundary admission. Required
parameters remain required when a description supplies a default. Unknown
parameters fail instead of selecting another operation's defaults.
`DagToWayangErrorsTest` reads 22 exact rejection cases from the shared repository
fixture. `DagToWayangWorkbenchTest` reads the five actual YAML exports generated
by the frontend tests; an exported `diff` branch names its noncatalog operator.

`build(reads, state, bootstrap)` creates one `TsxTopicSource` per source topic
and all declared sinks in one `WayangPlan`. Each read is a `TsxRead` for a whole
topic, with its internal partition ranges. Episode, `org.opentsx.model.v2.SeriesStats`
and JSON signal sinks use `TsxTopicSink`; vector output uses `TsxVectorSink`.
Consecutive clean operations share a `TsxSeriesChain`. Pair and alignment edges
use typed tags and unions. A single pair input compares each unordered series
pair once, preserving the supplied series order.

Use the `TsxCompileContext` overload for explicit node assignments and replicas,
boundary selections, a trusted cross-correlation product estimate and site
budget, ordered series inventories, clean targets, site identity and an optional
fixed clock. Every assigned replica must support the operator version and state
kind. Pair cross-correlation requires both an admitted budget and estimate.
Building an intermediate clean run requires its signal target; building pair or
alignment inputs requires their series inventories. Missing bindings fail.
Ordinary clean sinks derive their target from `sink.signal`.

A direct federated statistics edge keeps all configured tumbling widths by
default and excludes `GLOBAL` on that edge. A shared local statistics sink keeps
its global output. A signal collector preserves local DQ findings; it does not
merge their upstream statistics. These explicit compiler admissions do not
perform network placement or grant remote execution rights.

`DagToWayangCatalogTest` executes all 16 catalog bindings through compiled Java
Wayang plans and checks the admission boundaries.

The workbench exports `resample.every` as an ISO duration. A published
`stats.window` exports its width as `windows` and writes complete `SeriesStats`;
its selected `field` remains a browser view in the stored flow. This is a
deployment representation, not parity with that projected browser output.
The UI threshold segmenter's sample-count context is separate from the catalog's
duration parameters and is not converted by this export path.

## Compile a local clean DAG

The `series()` and `plan(bootstrap, sinkName)` APIs select named clean-series
sinks for `TsxIncrement`. `DagToWayang.parameters` remains the separate parameter
helper used by the bounded Rust rollout. Required parameters and optional
defaults follow the same processor descriptions as the general compiler.

## Execute a signed bounded JVM statistics stage

You need JDK 17 or 21, the built Java modules, explicit local broker bindings,
Ed25519 keys, mutual-TLS contexts, local input/output policy and a durable state
directory. Use the programmatic APIs from an embedding application. The signed
profile binds one complete immutable whole-topic cut to a statistics fragment
with one `org.opentsx.model.v2.SeriesStats` sink. General local catalog
compilation remains the broader API described above.

The order matters:

1. Keep the original DAG text and the exact assignment text. The bounded
   [runner wrapper](../docs/datamodel-2026/schemas/opentsx.runner-plan.v1.schema.json)
   binds their hashes, generation, runtime artifact, inputs, output and admitted
   aggregate scopes. `DagToWayang.sinkIdentity` supplies the existing output
   identity. Every fragment source topic needs an admitted input, and every
   admitted input must appear in the fragment. Broker aliases resolve through
   local configuration.
2. Sign the exact wrapper bytes with
   [`SignedJws.sign`](src/main/java/org/opentsx/wayang/site/SignedJws.java).
   [`TsxPlanService`](src/main/java/org/opentsx/wayang/site/TsxPlanService.java)
   pins qualified runtime identities, driver/site keys and certificate-to-site
   mappings, then durably publishes signed deliveries.
3. Obtain the job-bound challenge with
   [`TsxPlanClient.challenge`](src/main/java/org/opentsx/wayang/site/TsxPlanClient.java).
   The integration harness's
   [`SiteConformanceChecks`](src/test/java/org/opentsx/wayang/SiteConformanceChecks.java)
   executes shared vectors, required broker checks, actual topic inventories,
   fingerprints, series disjointness and loaded runtime identity. Submit the
   signed report before requesting activation.
4. Supply [`TsxBoundedSiteExecution.run`](src/main/java/org/opentsx/wayang/TsxBoundedSiteExecution.java)
   with the admitted complete `TsxCompileContext` as the execution callback to
   [`TsxSiteAgent.pollOnce`](src/main/java/org/opentsx/wayang/site/TsxSiteAgent.java).
   The agent verifies the signed grant and local policy and persists its intent
   before invoking that callback. Execution reads strict bounded cuts, compiles
   the fragment, groups statistics, supplies known empty scopes, publishes
   partials and saves the compatible checkpoint. The agent then persists the
   outcome, acknowledges the delivery and retains the cursor. An identical retry
   reuses the saved outcome. Pending acknowledgements are retried before another
   pull, including after conformance expiry; fresh activation still requires
   conformance. Conflicting bytes under the same identity reject.
5. [`TsxSiteStage`](src/main/java/org/opentsx/wayang/TsxSiteStage.java),
   [`TsxSitePlatform`](src/main/java/org/opentsx/wayang/TsxSitePlatform.java) and
   [`TsxSiteExecutor`](src/main/java/org/opentsx/wayang/TsxSiteExecutor.java)
   connect this complete action to the driver through `AggregateChannel`.

The transport profile uses TLS 1.3 with client certificates and pinned site
identities. Signatures use Ed25519 with the exact protected `EdDSA` header.
The embedding application supplies the keys, trust material and local policy.
Read [SPEC S7](../docs/datamodel-2026/20-WAYANG-TS-OPERATORS-SPEC.md#s7-security)
for that boundary and
[`ConformanceHandshakeTest`](src/test/java/org/opentsx/wayang/ConformanceHandshakeTest.java)
for actual wrong fingerprints, overlapping series and a client without a
certificate being refused.

Route `signal-cluster` preserves aggregate record bytes and headers through
Kafka. Its checkpointed
[`AggregateForwarder`](src/main/java/org/opentsx/wayang/federation/AggregateForwarder.java)
is one adapter; the fixture also exercises direct signal-cluster publication.
Route `job-state` carries the same records in an authenticated
`opentsx.aggregate-delivery.v1` body. `AggregateInventory` binds membership and
scope evidence; `CompletenessCoordinator` persists received partials, revisions
and pending acknowledged output. Missing evidence yields an incomplete
revision; a late compatible site appends a final revision while preserving the
earlier Kafka records.

[`FederatedStatsIntegrationTest`](src/test/java/org/opentsx/wayang/FederatedStatsIntegrationTest.java)
checks the complete W3 workload: 3,600 episodes, 2,160,000 values and 420 windows.
It checks all six merge orders on received partials and exact merged SOE bytes.
[`JobStateRouteIntegrationTest`](src/test/java/org/opentsx/wayang/JobStateRouteIntegrationTest.java)
compares complete partial and merged records across both routes.
[`CompletenessIntegrationTest`](src/test/java/org/opentsx/wayang/CompletenessIntegrationTest.java)
checks 420 incomplete revision-zero records followed by 420 final revision-one
records after the stopped site returns and the coordinator reloads its state.
The bounded W3 numerical qualification uses the approved relative `3e-12`
mean/M2 criterion in
[SPEC S4.3](../docs/datamodel-2026/20-WAYANG-TS-OPERATORS-SPEC.md#s43-merge-functions).
These integration classes use the existing `KAFSCALE_BOOTSTRAP` test switch and
require `KAFSCALE_BROKER_BINARY`, `KAFSCALE_PROXY_BINARY` and `KAFSCALE_CLUSTER_BINARY`
to name the source-built KafScale v1.6.0 broker, Proxy and isolated cluster launcher.
The fixture starts four independent clusters, each with private etcd metadata, a loopback-only
broker and a Proxy endpoint. Producers use the Proxy. Read the
[bundle instructions](../opentsx-kafscale-conformance/README.md#build-the-pinned-transport-bundle).

The bounded integration topology contains three site-agent instances in one JVM
and four Go broker processes, four Proxies and four embedded etcd instances. Execution topology excludes build and transient
conformance-check subprocesses. The measurement harness records that topology,
actual per-route elapsed times, Kafka Produce frames, HTTP bodies and encrypted
TLS client-stream bytes as separate counters. Read its arguments from the
repository root:

```bash
python3 tools/wayang_federation_measurement.py --help
```

The harness requires clean source trees with no untracked runtime, fixture or
tool inputs. It records their hashes and HEAD, builds pinned KafScale v1.6.0
and freezes its Java classpath. The same source guard runs before and after
execution. Its result directory contains `bytes.csv`, `windows.csv`, `run.json`,
`metadata.json`, logs and a generated `REPORT.md`. Results are written from the
executed workload and validated before report generation.

The [A4.9 report and raw samples](../docs/datamodel-2026/measurements/wayang-federation-2026-11-17/REPORT.md)
record nine site/route measurements, 420 reference windows and 2,520 exact
merge-order comparisons. Use the
[measurement instructions](../docs/datamodel-2026/20-WAYANG-TS-OPERATORS.md#143-measure-bounded-jvm-federation)
to rerun the bounded profile. Kafka frames, HTTP bodies and TLS client-stream
bytes describe different layers; their route clocks cover different work.

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
`TsxStreamEqualsBatchTest` runs all 65 numerical cases in chunks of 1, 7 and
all records, in both series orders: 366 native comparisons and 24 explicitly
kernel-only event-list comparisons. Stateful helpers restore serialized state
between chunks. Clean comparisons use logical samples; pair comparisons
exclude only execution `createdAt`. Segment Avro, vector JSON, SAX records,
DQ signals and statistics including sketch bytes keep their exact comparisons.
SAX treats a complete episode as one record; DQ treats one statistics window as
one record. Their parity fixtures contain 16 records per case. CI requires the
26 vector cases, all 65 parity cases, and both JSON broker tests to execute.

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
