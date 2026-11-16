# Specification 20: time-series operators on Apache Wayang (S1 to S7)

> Status: **draft specification, version 0.1**, 2026-11-16, not implemented. Concept and research:
> [20-WAYANG-TS-OPERATORS.md](20-WAYANG-TS-OPERATORS.md). Tasks and acceptance criteria:
> [TASK-020](../../EVOLUTION/TASK-020-wayang-ts-operators.md). Checked against this repository at `548f316` and
> Apache Wayang tag `wayang-1.1.1`.

The key words MUST, MUST NOT, SHOULD, SHOULD NOT and MAY are used as in
[RFC 2119](https://www.rfc-editor.org/rfc/rfc2119) and [RFC 8174](https://www.rfc-editor.org/rfc/rfc8174) when they
appear in capitals.

Terms:

| Term | Meaning |
|---|---|
| Track A | Java execution operators on Wayang's Java platform, module `opentsx-wayang` |
| Track B | the Rust runner `tsx-runner` |
| runtime | an implementation of the catalog: Track A or Track B |
| site | one KafScale cluster with one runtime that runs site plans |
| driver | the central Wayang process that translates DAGs and sends site plans |
| signal cluster | the central KafScale cluster that receives site aggregates and signals |
| DAG document | the `opentsx.dag.v1` or `opentsx.dag.v2` document the web workbench exports (`opentsx-saas-frontend/src/workbench/flow.ts:684-745`) |
| `specHash` | lower-case hex SHA-256 of the UTF-8 text of the DAG document, as the processor computes it today (`VectorProcessor.java:261`, `:797-803`) |

## S1. Operator catalog

### S1.1 Identity

1. An operator is identified by `id` and `version` of an `opentsx.processor.v1` description
   ([schema](schemas/opentsx.processor.v1.schema.json)). Descriptions live in `systems/processors/<id>.yaml`; the file
   name MUST equal the id (checked by `SystemDocTest.java:49-57`).
2. The DAG names an operator by its `op`. When the description has `dag.op`, that value is the DAG `op`;
   otherwise the `id` is. Example: `dq.plausible-range` is written as `op: dq.checks` with
   `fixed: { checks: [PLAUSIBLE_RANGE] }` (`systems/processors/dq.plausible-range.yaml`).
3. A **catalog version** is a named, immutable set of `(id, version)` pairs: `tsx-catalog/<n>`, `n` an integer from
   1. Catalog 1 is the set in S1.7. A change to an operator's output for any input MUST raise its `version` and
   MUST produce a new catalog version.
4. A runtime MUST NOT run an operator version that is not in the catalog version named by the site plan.

### S1.2 Record types

Ports use the `type` values of `opentsx.processor.v1` (`series`, `vector`, `xy`, `stats`, `events`, `model`). On
Kafka they are:

| Port type | Record | Encoding | Key |
|---|---|---|---|
| `series` | `org.opentsx.model.v2.Episode` | Avro single-object encoding ([04](04-MODELL-V2-SPEC.md), section 5) | `seriesId` |
| `stats` | `org.opentsx.model.v2.SeriesStats` | Avro single-object encoding | `seriesId` (S4.2 for aggregates) |
| `vector` | `opentsx.vector.v1` | JSON ([schema](schemas/opentsx.vector.v1.schema.json)) | canonical text of `key` |
| `events` | `opentsx.signal.v1` | JSON ([schema](schemas/opentsx.signal.v1.schema.json)) | `seriesId` of the series the signal is about |
| `xy`, `model` | none | not allowed on a topic in this specification | |

### S1.3 Parameters

1. Parameters are the `params` of the description: `name`, `type`, `required`, `default`, `min`, `max`, `values`.
2. A runtime MUST reject a plan in which a required parameter is missing, a value has the wrong type, a number lies
   outside `[min, max]`, or an enum value is not in `values`. The messages are in S2.5.
3. A `duration` is an ISO 8601 duration (`PT1M`). It MUST be above zero unless the description says otherwise.

### S1.4 State

Every catalog operator MUST declare `state.kind`:

| `state.kind` | Meaning | Checkpoint content |
|---|---|---|
| `none` | output depends on the current record only | nothing |
| `keyed` | state per key; `state.key` is `series` (by `seriesId`) or a tag name | state per key |
| `windowed` | state per key and per window on the epoch grid: window k covers `[k * w, (k + 1) * w)` microseconds since 1970-01-01T00:00:00Z | open windows per key |

An operator MUST NOT keep state outside the checkpoint (S6.3).

### S1.5 Mergeability

Every operator whose output may leave a site (S4) MUST declare `merge.class`:

| `merge.class` | Meaning | Federation |
|---|---|---|
| `exact` | a merge function exists whose result equals the centralized result in real arithmetic | allowed |
| `approximate` | the merged result equals the centralized result within a stated bound (`merge.bound`) | allowed |
| `none` | no merge function | the operator MUST NOT feed a federated sink |

`merge.function` names one of the functions of S4.3.

### S1.6 Test vectors and tolerances

1. `testvectors` of the description names the shared vector file. A runtime MUST pass every case of that file
   before the operator version enters its catalog.
2. The comparison is the one the existing tests use:

| Vector file | Comparison | Source |
|---|---|---|
| `opentsx-model/src/test/resources/testvectors/wire-format.json` | byte-identical hex | `opentsx-model` tests |
| `series-id.json`, `xxh64.json`, `time-deltas.json`, `bucket-checksum.json` | identical values | same |
| `docs/datamodel-2026/testvectors/clean-ops.v1.json` | `\|expected - actual\| <= 1e-12 * max(1, \|expected\|)`; a gap must be a gap | `CleanChainTest.java:76-85` |
| `align-vector.v1.json` | exact equality | `VectorAlignerTest.java:83` |
| `pair-xcorr.v1.json` | `\|expected - actual\| < 5e-13` (vitest `toBeCloseTo(v, 12)`) | `xcorr.test.ts:57-95` |
| `pair-es.v1.json` | events equal; `q_total`, `q_ab`, `q_ba`, `q_rand` within `5e-13` | `es.test.ts:61-68` |
| sketch bytes of `QuantileSketchTest.testVector` | byte-identical | `QuantileSketchTest.java:105-115`; `test_stats.py:10-17` |
| `stats-window.v1.json` | exact statistics, sketch hex, and quantiles; non-finite strings equal | `StatsWindowVectorsTest` |
| `segment-threshold.v1.json`, `segment-extremum.v1.json` | exact recorded fields for batch and streaming chunks of 1, 7, and all samples | `SegmentThresholdVectorsTest`, `SegmentExtremumVectorsTest` |
| `sax-word.v1.json` | exact normalized values, PAA, breakpoints, letters, and word | `SaxWordVectorsTest` |
| `dq-result.v1.json` | exact check records, including status and messages with a fixed clock and `Locale.ROOT` | `DqResultVectorsTest` |

### S1.7 Catalog 1

`A` is Track A, `B` is Track B. The statistics, segmentation, SAX, and
data-quality reference vectors are generated from the existing Java
implementations by `tools/java_operator_vectors.py`.

| DAG `op` | In → out | Parameters (limits) | State | Merge | Vectors | A | B |
|---|---|---|---|---|---|---|---|
| `decode.avro-soe` | topic → `series` | none | none | n/a | `wire-format.json` | yes | yes |
| `dq.checks` | `series` → `series` | `checks` ⊆ {`PLAUSIBLE_RANGE`} in the streaming path (`CleanChain.java:121`); `min` ≤ `max`; `mode` ∈ {`gap`, `clip`} | none | n/a | `clean-ops.v1.json` | yes | yes |
| `convert.unit` | `series` → `series` | `factor` (default 1), `offset` (default 0), `unit` | none | n/a | `clean-ops.v1.json` | yes | yes |
| `resample` | `series` → `series` | `every` duration > 0; `agg` ∈ `Agg` values (`CleanChain.java:142`) | windowed | n/a | `clean-ops.v1.json` | yes | yes |
| `model.wind-shear` | `series` → `series` | `fromM` > 0, `toM` > 0, `alpha` | none | n/a | `clean-ops.v1.json` | yes | yes |
| `model.power-curve` | `series` → `series` | `ratedKw` > 0, `cutIn` < `ratedWind` < `cutOut`, `count` ≥ 1 | none | n/a | `clean-ops.v1.json` | yes | yes |
| `feature.lag` | `series` → `series` | `steps` in `[1, 100000]` (`MAX_WINDOW_VALUES`, `CleanChain.java:38`, `:168`) | keyed (series) | n/a | `clean-ops.v1.json` | yes | yes |
| `feature.rolling` | `series` → `series` | `window` duration > 0; `fn` ∈ `Fn` values | keyed (series) | n/a | `clean-ops.v1.json` | yes | yes |
| `stats.welford-ddsketch` | `series` → `stats` | `windows` list of durations, each > 0; `alpha` in (0, 1) (`QuantileSketch.java:58-59`); `maxBins` fixed at 4096 in catalog 1 (`QuantileSketch.java:33`) | windowed | `approximate`, `stats.chan-ddsketch` (S4.3) | `stats-window.v1.json`, `QuantileSketchTest.testVector` | yes | yes |
| `align.vector` | `series`* → `vector` | `every`, `key` (`series`, a tag name, `all`), `agg`, `fill`, `maxAge`, `emit`, `names` ([11](11-SMART-TOPIC-PROZESSOR-DEVOPS.md), 5a) | windowed | `none` | `align-vector.v1.json` | yes | yes |
| `segment.threshold` | `series` → `series` | `direction`, `enter`, `exit` (hysteresis: `exit` not beyond `enter`), `pre`, `post`, `minDuration`, `maxDuration` (`Segmenter.java:92-95`) | keyed (series) | `exact` for segment counts, `sum` | `segment-threshold.v1.json` | yes | yes |
| `segment.extremum` | `series` → `series` | `feature`, `prominence` ≥ 0, `window`, `distance`, `pre`, `post` (`Segmenter.java:69-72`) | keyed (series) | `exact` for segment counts, `sum` | `segment-extremum.v1.json` | yes | yes |
| `sax.word` | `series` → `series` (fills `EpisodeSummary.sax`) | `w` ≥ 1, `a` in the range of `Sax.breakpoints` (`Sax.java:16`) | none | `none` | `sax-word.v1.json` | yes | yes |
| `pair.xcorr` | `series`, `series` → `series` | `lags` integer ≥ 0 (`xcorr.ts:95-98`); pair settings `pairing`, `window`, `step`, `every`, `agg`, `maxAge`, `minCoverage` (`pairs.ts:78-88`) | windowed | `none` | `pair-xcorr.v1.json` | yes (port) | short windows only (S1.8) |
| `pair.es` | `series`, `series` → `series` | `sigma`, `tauMax`; surrogates with `shuffles`, `seed` (`es.ts`; `pair-es.v1.json`) | windowed | `none` | `pair-es.v1.json` | yes (port) | no |
| `dq.result` | `stats` → `events` | `checks` ⊆ `DataQuality.Check` values, expectation `min`, `max`, `expectedInterval` (`DataQuality.java:29-38`) | none | `exact` for counts per check and status, `sum` | `dq-result.v1.json` | yes | yes |

The workbench operator `stats.window` maps to `stats.welford-ddsketch` (`ops.ts:905-910`) but has the
parameters `window` (seconds) and `field`, not `windows` and `alpha`. The workbench MUST write the catalog
parameters before a flow with this operator can be deployed.

`sax.word` and `dq.result` are new DAG `op` names: `Sax` and `DataQuality.evaluate` exist (`Sax.java:44`,
`DataQuality.java:66-70`) but have no DAG operator yet. Their descriptions MUST be added to `systems/processors/`
before use.

### S1.8 Limits on Track B

Track B MUST reject `pair.xcorr` when `(2 * lags + 1) * window / every` exceeds the limit set in the site
configuration, with the message of S2.5. Track B MUST NOT offer `pair.es`.

## S2. Plan translation

### S2.1 Input

1. The driver MUST accept exactly the DAG documents the workbench exports: `opentsx.dag.v1`, and `opentsx.dag.v2`
   when the document carries `schema: opentsx.dag.v2`. A second DAG format MUST NOT be introduced.
2. The document is parsed as YAML into a JSON tree, as `VectorProcessor` does (`VectorProcessor.java:281`).
3. `specHash` is computed over the document text before parsing.

### S2.2 Validation order

The driver MUST validate in this order and MUST stop at the first error. Steps 1 to 4 are the checks
`DagPlan.of` makes today and MUST keep its messages.

1. Structure: `nodes` is an array.
2. Nodes: ids, decoder, inputs, cycles (`DagPlan.java:64-118`).
3. Sinks: non-empty array, supported schema.
4. Sink identities unique.
5. Catalog: every `op` is in the catalog version of the job and has a runtime for every site track in the job.
6. Parameters (S1.3).
7. Topics: every source and sink topic follows `tsx.<domain>.<kind>.v<n>` of the job's domain (S3.1).
8. Federation: every node upstream of a federated sink has `merge.class` other than `none`, every windowed stats
   node uses tumbling windows, and the sink kind is `aggregates` or `signals`.

### S2.3 Wayang plan

1. Each DAG node MUST become one Wayang logical operator: `UnarySource`, `UnaryToUnaryOperator`,
   `BinaryToUnaryOperator` or `UnarySink` subclass (package `org.apache.wayang.core.plan.wayangplan`, tag
   `wayang-1.1.1`), chosen by the number of inputs and outputs of the description.
   Exception: a run of consecutive single-input nodes whose operators are all in `CleanChain.OPS`
   (`CleanChain.java:35`) MUST become one operator (`TsxSeriesChain`). These steps share one state per series, and the
   written series gets its key and episode ids once, after the last step (`SeriesStream.java`); separate operators
   would key and label every intermediate series. The state of the run is stored under the id of its last node.
2. `decode.avro-soe` with the DAG `source` MUST become one `TsxTopicSource` per source topic. Each sink MUST become
   one `TsxTopicSink`.
3. The driver MUST NOT use `wayang-api-json`: it compiles Scala source received in the request
   (`SerializableLambda.scala:23-29` at Wayang `main`).
4. On a single site the plan runs with `Java.basicPlugin()` and the Track A plugin.

### S2.4 Site plan

For a federated job the driver MUST cut the DAG at the federated sinks. Everything upstream of them, with those
sinks, is the **site stage**; it goes unchanged to every site. Everything downstream (the merge) runs centrally.

The site plan is an `opentsx.runner-plan.v1` document ([schema](schemas/opentsx.runner-plan.v1.schema.json)):

| Field | Rule |
|---|---|
| `job` | unique per deployment; `[a-z0-9][a-z0-9-]{0,62}` |
| `planVersion` | integer ≥ 1, raised by 1 for every change of the job |
| `issued`, `expires` | RFC 3339 UTC; `expires` after `issued` |
| `site` | the receiving site id |
| `catalog` | catalog version, for example `tsx-catalog/1` |
| `specHash` | as defined above, of the site stage document |
| `dag` | the site stage as a DAG document (JSON form) |
| `inputs[]`, `outputs[]` | topic, allowed fingerprints (16 lower-case hex each), partitions; outputs name the cluster (`site` or `signal`) |
| `start` | `earliest`, `latest`, or an offset map |
| `federation` | `route` (`signal-cluster` or `job-state`), `expectedSites`, `groupBy`, `maxDelay` |

The site plan MUST be signed (S7.1).

### S2.5 Rejection messages

Messages are plain English and MUST be returned exactly as below, with the placeholders filled in. Messages 1 to
10b exist today in `DagPlan.java`; operator messages of `CleanChain.java` (for example
`node <id>: agg must be one of <values>`, `CleanChain.java:142`) are kept as they are.

| # | Message | Origin |
|---|---|---|
| 1 | `the document is no opentsx.dag DAG (no nodes)` | `DagPlan.java:19` |
| 2 | `every node needs a non-empty string id` | `DagPlan.java:71` |
| 3 | `duplicate node id <id>` | `DagPlan.java:74` |
| 4 | `node <id>: unsupported decoder <op>` | `DagPlan.java:78` |
| 5 | `decoder <id> must read the source without node inputs` | `DagPlan.java:82` |
| 6 | `node <id> needs an operator and a non-empty input array` | `DagPlan.java:85` |
| 7 | `node <id>: missing input <input>` | `DagPlan.java:92` |
| 8 | `the DAG contains a cycle` | `DagPlan.java:114` |
| 9 | `sinks must be a non-empty array` | `DagPlan.java:23` |
| 10 | `duplicate sink identity <name>` | `DagPlan.java:58` |
| 10a | `unsupported sink <topic>: schema must be <schemas>`; the driver lists the sink schemas of the catalog | `DagPlan.java:29-30`, extended |
| 10b | `unsupported sink <topic>: a vector sink must read align.vector` | `DagPlan.java:35-36` |
| 11 | `node <id>: operator <op> is not in catalog <catalog>` | new |
| 12 | `node <id>: operator <op> version <version> has no runtime for track <A or B>` | new |
| 13 | `node <id>: parameter <name> is required` | new |
| 14 | `node <id>: parameter <name> must be a <type>` | new |
| 15 | `node <id>: parameter <name> must be between <min> and <max>` | new |
| 16 | `node <id>: parameter <name> must be one of <values>` | new |
| 17 | `topic <topic> does not follow tsx.<domain>.<kind>.v<n> for domain <domain>` | new |
| 18 | `node <id>: operator <op> is not mergeable and cannot feed the federated sink <topic>` | new |
| 19 | `node <id>: a federated statistic needs a tumbling window, not GLOBAL` | new |
| 20 | `node <id>: pair.xcorr exceeds the limit of this site (<n> products per window)` | new |

## S3. Site contract

### S3.1 Topics

1. Every topic name MUST be produced by `TopicLayout`: `"tsx." + domain + "." + kind + ".v2"` for Avro kinds
   (`TopicLayout.java:189-191`) and the JSON kinds `dags.v1`, `dag-status.v1`, `smart.<name>.vectors.v1`
   (`TopicLayout.java:96-125`).
2. This specification adds three kinds, to be added to `TopicLayout` with the code:
   `aggregates` (`tsx.<domain>.aggregates.v2`, Avro `SeriesStats`), `signals` (`tsx.<domain>.signals.v1`, JSON
   `opentsx.signal.v1`), `repartition.<dag>.<node>` (`tsx.<domain>.repartition.<dag>.<node>.v2`, Avro `Episode`).
3. Clients MUST connect to the KafScale proxy only and MUST use the settings of `KafScaleClients`
   (`enable.idempotence=false`, `acks=all`, one request in flight). Track B MUST also set
   `partitioner=murmur2_random`.

### S3.2 Schema fingerprints

1. A fingerprint is the CRC-64-AVRO fingerprint of the writer schema, written as 16 lower-case hex digits
   (`OpenTsxAvro.fingerprint`, `OpenTsxAvro.java:71`).
2. The fingerprints of the catalog are those of `wire-format.json` (`Episode` = `8985f02b48c77195`, `SeriesStats` =
   `e51b48188baa6606`, `SeriesKey` = `ec4b59937d77bd89`, `Observation` = `ca0e771818e53a71`).
3. A runtime MUST stop a task with state `failed` when it reads a record whose fingerprint is not in the site plan,
   with the status `error`
   `record <topic>/<partition>@<offset> has schema fingerprint <fingerprint>, not in plan <job> version <planVersion>`.
   It MUST NOT skip the record. A record without the single-object header (`C3 01` and 8 fingerprint bytes) stops the
   task the same way, with `record <topic>/<partition>@<offset> is not Avro single-object encoded`.

### S3.3 Conformance handshake

A site MUST pass the handshake before it receives a site plan, and again after every change of its runtime
version or catalog.

1. The site runs its conformance checks (Track A: `TsxSiteAgent --conform`; Track B: `tsx-runner conform`) and
   produces an `opentsx.site-conformance.v1` report ([schema](schemas/opentsx.site-conformance.v1.schema.json)).
2. The report MUST contain one result per check below, each `pass` or `fail` with a detail string.
3. The site signs the report (S7.1) and sends it (S5.3, `POST /v1/sites/<site>/conformance`).
4. The driver MUST verify the signature against the site key it has on record, MUST require `pass` for every
   check, and MUST compare the reported values with the job contract. It answers `accepted` or `refused` with the
   first failed check.

| Check id | Pass condition |
|---|---|
| `vectors.wire` | every record of `wire-format.json` encodes byte-identically |
| `vectors.series-id` | `series-id.json` and `xxh64.json` pass |
| `vectors.operators` | every operator of the catalog version passes its vectors (S1.6) |
| `kafscale.required` | the required tests of `opentsx-kafscale-conformance` pass against the site broker |
| `topics.names` | every topic of the job follows S3.1 |
| `topics.partitions` | partition counts equal the job contract |
| `topics.fingerprints` | the first and last record of each input partition carry a contract fingerprint |
| `partitioner.murmur2` | a probe record per partition lands where Java's default partitioner puts its key |
| `series.disjoint` | the site's `seriesId` set does not intersect another site's set; the site sends the sorted set |
| `runtime.identity` | runtime version and artifact digest match a signed release |

## S4. Federation and merge

### S4.1 Grouping

1. `federation.groupBy` is a list of tag names. The **group key** of a series is its `SeriesKey` with `tags`
   restricted to `groupBy`. The **group series id** is `SeriesKeys.seriesId(metric, restrictedTags)`
   (`SeriesKeys.java:37`).
2. A site MUST aggregate all its series with the same group series id into one partial aggregate per window.
3. Each `seriesId` MUST belong to exactly one site (`series.disjoint`).

### S4.2 Aggregate record

A partial or merged aggregate is an Avro `SeriesStats` record, unchanged:

| Field | Value |
|---|---|
| `seriesId` | the group series id |
| `windowType` | the ISO 8601 window duration; `GLOBAL` MUST NOT be used in federation |
| `windowStart`, `windowEnd` | the window on the epoch grid |
| `validCount`, `nanCount`, `gapCount`, `min`, `max`, `mean`, `m2`, `quantileSketch`, `lastSeen` | as `StatsAccumulator` computes them |

Kafka key: `<job>|<site>|<group series id>|<windowStart micros>` for partials, `<job>|<group series id>|<windowStart
micros>` for merged records. Headers (same convention as `tsx-schema`, `TopicLayout.java:23`):

| Header | Value |
|---|---|
| `tsx-schema` | `org.opentsx.model.v2.SeriesStats` |
| `tsx-job`, `tsx-site` | job id; site id (partials only) |
| `tsx-plan-version` | decimal `planVersion` |
| `tsx-revision` | decimal revision, 0 first |
| `tsx-final` | `true` or `false` (merged records only) |
| `tsx-missing-sites` | comma-separated site ids, empty when final (merged records only) |

A site MUST emit a partial for every group and window it covers, also when `validCount` is 0, so that "empty" and
"missing" differ.

### S4.3 Merge functions

`stats.chan-ddsketch` merges two `SeriesStats` of the same group and window:

| Field | Merge |
|---|---|
| `validCount`, `nanCount`, `gapCount` | sum |
| `min`, `max` | minimum, maximum over records with `validCount` > 0 |
| `mean`, `m2` | if one side has `validCount` 0, the other side; else Chan: n = nₐ + n_b, δ = μ_b − μₐ, μ = μₐ + δ·n_b/n, M2 = M2ₐ + M2_b + δ²·nₐ·n_b/n (`StatsAccumulator.java:62-79`) |
| `quantileSketch` | `QuantileSketch.merge` (`QuantileSketch.java:101-110`); both sides MUST have equal `alpha` and `maxBins` |
| `lastSeen` | maximum |

`sum` adds counts per key; it is used for segment counts and `dq.result` counts.

Laws:

1. **Identity.** The record with all counts 0, `min` = +∞, `max` = −∞, `mean` = 0, `m2` = 0 and an empty sketch is the
   identity element.
2. **Associativity and commutativity.** Counts, `min`, `max`, `lastSeen` and the sketch bins are exactly associative
   and commutative (integer addition, min, max). `mean` and `m2` are associative and commutative in real
   arithmetic only. In IEEE 754 doubles they depend on the order.
3. **Canonical order.** The merge MUST fold the partials in ascending order of site id, starting from the identity.
   Two merges of the same partials then give bit-identical results.
4. **Equality with the centralized result.** The merged counts, `min`, `max` and sketch bytes MUST equal those of
   one `StatsAccumulator` over all values, when all sites run one runtime. `mean` and `m2` MUST be within the
   tolerance of the job (decision D-T2 in TASK-020; proposal: relative 1e-12). Quantiles from the merged sketch MUST
   equal those of the centralized sketch and lie within relative error `alpha` of the exact quantile
   ([Masson, Rim, Lee 2019, doi:10.14778/3352063.3352135](https://doi.org/10.14778/3352063.3352135)).
5. **Mixed runtimes.** When sites run different runtimes, a value on a DDSketch bin boundary MAY fall into
   neighbouring bins (Java `Math.log` is within 1 ulp,
   [Java SE 17](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/Math.html#log(double))).
   Rule 4 then holds for counts, `min`, `max` and the `alpha` bound, not for sketch bytes.

### S4.4 Routes

**Signal cluster (route `signal-cluster`).**

1. The site stage writes partials to `tsx.<domain>.aggregates.v2` and signals to `tsx.<domain>.signals.v1` on the
   site cluster.
2. A forwarder copies them to the same topic names on the signal cluster, from its own checkpoint, keys and headers
   unchanged.
3. The central merge reads `tsx.<domain>.aggregates.v2` of the signal cluster and writes merged records to
   `tsx.<domain>.series-stats.v2` of the signal cluster (`TopicLayout.seriesStats()`, `TopicLayout.java:58`).

**Job state (route `job-state`).** For bounded jobs only. The site agent sends its partials of the job to the driver
(S5.3, `POST /v1/sites/<site>/jobs/<job>/aggregates`). The driver merges them with `stats.chan-ddsketch` into the
Wayang plan's `AggregateChannel` and continues the plan.

### S4.5 Completeness

1. `federation.expectedSites` is the list of sites of the job. `federation.maxDelay` is an ISO 8601 duration.
2. A merged window is **final** when a partial of every expected site is present. The merge then writes it with
   `tsx-final: true`.
3. When the signal cluster's clock passes `windowEnd + maxDelay` and a site is missing, the merge MUST write the
   window with `tsx-final: false` and `tsx-missing-sites` set.
4. A partial that arrives later MUST produce a new merged record with `tsx-revision` raised by 1. The merge MUST
   NOT change an earlier record.
5. A duplicate partial (same key, same revision) MUST be ignored.
6. Removing a site from a job MUST go through a new `planVersion`. Windows before the change keep their sites.

### S4.6 Signals

A signal is a discrete finding at a site: a data-quality failure, a segment begin or end, a threshold crossing.
Format: `opentsx.signal.v1` ([schema](schemas/opentsx.signal.v1.schema.json)). Signals are not merged; the signal
cluster collects them. A signal MUST NOT carry raw values beyond the value that triggered it.

## S5. Runner interface

### S5.1 Track A site executor

`TsxSiteAgent` (module `opentsx-wayang`) MUST:

1. hold the site key and the driver's plan-signing public key;
2. run the handshake of S3.3;
3. fetch site plans (S5.3), verify them (S7.1) and refuse them with the codes of S5.5;
4. run an accepted plan in **increments**: read the end offsets of every input partition, build the Wayang plan
   from the site stage (S2.3), run it with `WayangContext` and the Java platform over `[checkpoint, end)`, wait until
   every sink record is acknowledged, then write the checkpoint (S6.3);
5. start the next increment after `increment` (site configuration, ISO 8601 duration);
6. write status records (S5.4).

### S5.2 Track B runner

`tsx-runner` MUST offer:

| Mode | Behaviour |
|---|---|
| `agent` | runs the pull protocol of S5.3; supervises one task per job and partition set |
| `run --plan <file>` | verifies the signed plan in `<file>` and runs it until stopped; used by SSH launch |
| `conform` | runs the checks of S3.3 and writes the signed report to standard output |

SSH launch: the driver connects with an OpenSSH user certificate whose `force-command` is
`tsx-runner run --plan -` and sends the signed plan (compact JWS) on standard input. Exit codes: 0 stopped on
request, 2 plan refused (message on standard error, S5.5), 3 runtime failure.

### S5.3 Plan service API

The driver serves this API over HTTPS with mutual TLS. Sites are clients; they open no inbound port. All bodies are
JSON (`application/json`) unless stated.

| Method and path | Request | Response |
|---|---|---|
| `POST /v1/sites/<site>/conformance` | compact JWS of an `opentsx.site-conformance.v1` report | `200 {"result":"accepted"}`, or `200 {"result":"refused","check":"<id>","message":"<text>"}` |
| `GET /v1/sites/<site>/plans?after=<planVersion>` | none | `200 {"plans":["<compact JWS>", ...]}` with every plan of the site above `after`, oldest first; `204` when none |
| `POST /v1/sites/<site>/jobs/<job>/status` | an `opentsx.dag-status.v1` record (S5.4) | `204` |
| `POST /v1/sites/<site>/jobs/<job>/aggregates` | `application/vnd.opentsx.avro-soe-seq`: a sequence of `SeriesStats` records, each prefixed by its length as a 4-byte big-endian integer | `204` |
| `POST /v1/sites/<site>/jobs/<job>/refusal` | `{"planVersion":<n>,"code":"<code>","message":"<text>"}` | `204` |

Errors: `400 {"error":"bad-request","message":"<text>"}`, `401` when the client certificate is not a known site,
`403` when `<site>` in the path differs from the certificate's site, `404` for an unknown job, `409` when a status or
aggregate names an older `planVersion` than the current one.

The agent SHOULD poll `GET .../plans` every 30 s and MUST back off to at most once per 5 min while the driver is
unreachable.

### S5.4 Control and status topics

1. Control stays as today: `opentsx.dag-control.v1` on `tsx.<domain>.dags.v1`, key = DAG name, the latest record
   counts (`TopicLayout.java:117-121`).
2. Status stays `opentsx.dag-status.v1` on `tsx.<domain>.dag-status.v1` with its fields (`schema`, `dag`, `state`,
   `specHash`, `records`, `watermark`, `waitingFor`, `skippedBlocks`, `error`, `at`; `VectorProcessor.java:298`,
   `:620`). This specification adds the optional fields `site`, `job`, `planVersion`, `track` (`A` or `B`) and
   `artifact` (version and digest). Readers MUST ignore fields they do not know.
3. A runtime MUST write a status record on every state change and at least every 30 s, as the processor does today.

### S5.5 Refusal codes

| Code | When |
|---|---|
| `plan.signature` | signature invalid or signed by an unknown key |
| `plan.expired` | now is after `expires` |
| `plan.version` | `planVersion` not above the highest accepted one of the job |
| `plan.site` | `site` is not this site |
| `plan.catalog` | catalog version or an operator version not available |
| `plan.invalid` | any message of S2.5; the message is passed on |
| `plan.policy` | an output cluster or kind not allowed by the site policy (S7.4) |
| `plan.busy` | another plan of the same job runs on the same partitions |

## S6. Delivery, state and checkpoints on KafScale

### S6.1 Delivery

1. Delivery is at-least-once. A runtime MUST NOT commit offsets to the broker; progress is the checkpoint.
2. A runtime MUST NOT use transactions or the idempotent producer.
3. A runtime MUST retry a send until `delivery.timeout.ms` (180000, `KafScaleClients.DELIVERY_TIMEOUT`) and then
   fail the task.

### S6.2 Deterministic ids

A runtime that writes an episode it has read MUST write its maps (`tags`, `labels`, `params`) in UTF-8 key order
([04](04-MODELL-V2-SPEC.md), section 5). Avro decoding returns hash maps, so an episode that is read and written again
changes its bytes otherwise; the same id would then carry different payloads.

Every output record MUST carry an id that depends only on what it logically is:

| Output | Id |
|---|---|
| derived episode | `"c" + hex(xxHash64(seriesId + "|" + from + "|" + to + "|" + specHash))`, as `SeriesStream.java:152-153` |
| partial aggregate | Kafka key of S4.2 plus `tsx-revision` |
| merged aggregate | Kafka key of S4.2 plus `tsx-revision` |
| signal | `id` of `opentsx.signal.v1`: `hex(xxHash64(job + "|" + site + "|" + seriesId + "|" + kind + "|" + at))` |

Readers MUST keep one record per id; for aggregates the highest revision per key.

Operators that feed a sink MUST be deterministic (`semantics.deterministic: true`). `pair.es` surrogates are
deterministic through the `seed` parameter (`Mulberry32`).

### S6.3 Checkpoints

1. One checkpoint per task: the positions per input partition, the state of every stateful operator, `specHash`,
   catalog version and `planVersion`.
2. A runtime MUST write the checkpoint only after the broker acknowledged every record sent before it.
3. A runtime MUST write it to a temporary file, flush it to disk and rename it over the previous one.
4. On start, a runtime MUST refuse a checkpoint whose `specHash` or catalog differs from the plan and MUST start
   from `start` of the plan instead.
5. An unreadable checkpoint MUST put the task in state `failed`. The runtime MUST NOT start over by itself.
6. The checkpoint format is private to a runtime; it does not travel between tracks.

### S6.4 Reading

1. A runtime MUST use manual partition assignment and seek; it MUST NOT join a consumer group.
2. After a refused fetch it MUST reset to the positions before the fetch and retry, as `Patience.poll` does
   (`Patience.java:75`).
3. A start by time MUST go through the episode index; `offsetsForTimes` MUST NOT be used.

## S7. Security

### S7.1 Signed plans and reports

1. Site plans and conformance reports MUST be signed as compact JWS ([RFC 7515](https://www.rfc-editor.org/rfc/rfc7515.html))
   with `alg` `EdDSA` (Ed25519) or `ES256`. Other algorithms and `none` MUST be refused.
2. The payload is the document's JSON. The signature covers it as sent; no canonicalization is applied.
3. A site MUST pin the driver's public key in its configuration. The driver MUST pin each site's public key.
4. A site MUST refuse a plan whose `planVersion` is not above the highest it accepted for the job, or whose
   `expires` has passed (rollback and freeze protection as in
   [TUF](https://theupdateframework.github.io/specification/latest/)).

### S7.2 Transport

1. The plan service API (S5.3) MUST use TLS 1.3 with client certificates.
2. KafScale v1.6.0 accepts plain TCP only and has no server-side SASL
   ([concept 20, section 11](20-WAYANG-TS-OPERATORS.md#11-security-model)). A site MUST put a TLS-terminating proxy
   with client certificates in front of the KafScale proxy, and that proxy MUST pass the client identity with the
   PROXY protocol (`KAFSCALE_PROXY_PROTOCOL`, `KAFSCALE_PRINCIPAL_SOURCE=proxy_addr`). A site without such a proxy
   MUST keep the broker on a network only the runtime can reach.

### S7.3 Topic permissions

On KafScale, sites MUST enable KafScale ACLs (`KAFSCALE_ACL_ENABLED=true`, `KAFSCALE_ACL_FAIL_OPEN=false`) and give
each runtime principal only:

| Resource | KafScale action |
|---|---|
| input topics of its plans | `fetch` |
| output topics of its plans | `produce` |
| `tsx.<domain>.dag-status.v1` | `produce` |
| `tsx.<domain>.dags.v1` | `fetch` |
| everything else | none (default policy deny) |

A rule name with a trailing `*` (`tsx.<domain>.*`) matches a prefix (`pkg/acl/acl.go:139-147` of KafScale v1.6.0)
and MAY replace the per-topic list. On Apache Kafka the same rights are `READ`, `WRITE` and `DESCRIBE`, with
prefixed ACLs ([KIP-290](https://cwiki.apache.org/confluence/display/KAFKA/KIP-290%3A+Support+for+Prefixed+ACLs)).

### S7.4 Site policy and refusals

Each site has a local policy file that the driver cannot change. It lists:

1. the domains, input topics and output topics the site allows;
2. the clusters outputs may go to (site, signal cluster);
3. which output kinds may leave the site: by default only `aggregates` and `signals`;
4. the catalog versions it accepts.

A site MUST refuse (S5.5) a plan that violates its policy. A site MAY refuse any plan without giving a reason
beyond `plan.policy`.

### S7.5 Process isolation

1. A runtime MUST run as an unprivileged user.
2. On Linux with systemd it SHOULD run with `DynamicUser=yes`, `NoNewPrivileges=yes`, `ProtectSystem=strict`,
   `RestrictAddressFamilies=AF_INET AF_INET6 AF_UNIX`, `IPAddressDeny=any` and `IPAddressAllow=` the KafScale proxy,
   the signal cluster and the driver ([systemd.exec](https://www.freedesktop.org/software/systemd/man/latest/systemd.exec.html),
   [systemd.resource-control](https://www.freedesktop.org/software/systemd/man/latest/systemd.resource-control.html)).
3. Plans MUST NOT carry code. A runtime MUST NOT load operators that are not in its signed artifact.
4. WASM operators (Track B, later) MUST run in `wasmtime` with fuel or an epoch deadline, a memory limit and no
   WASI socket or filesystem access.

### S7.6 Artifacts

1. Track A artifacts and Track B binaries MUST be built in CI only, as release images are today (`.github/workflows/image.yml`; [AGENTS.md](../../AGENTS.md), rule 10).
2. Track B binaries MUST embed their dependency list (`cargo-auditable`) and ship a CycloneDX SBOM and a cosign
   signature.
3. The conformance report MUST carry the artifact digest (`runtime.identity`).
