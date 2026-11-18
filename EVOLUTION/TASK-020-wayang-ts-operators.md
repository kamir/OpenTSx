# TASK-020: Time-series operators on Apache Wayang (Track A Java, Track B Rust)

## Task Metadata
- **Task ID**: TASK-020-wayang-ts-operators
- **Created**: 2026-11-16
- **Status**: In progress
- **Priority**: Medium
- **Epic**: P7 Smart Topics (federated part, P7d) and P8 federated analysis
- **Concept**: [20-WAYANG-TS-OPERATORS.md](../docs/datamodel-2026/20-WAYANG-TS-OPERATORS.md)
- **Specification**: [20-WAYANG-TS-OPERATORS-SPEC.md](../docs/datamodel-2026/20-WAYANG-TS-OPERATORS-SPEC.md) (S1 to S7)

## Objective
Run the OpenTSx stream operators as native Apache Wayang operators on Kafka topics at many sites under one plan,
with raw data kept at its site and only mergeable aggregates and signals leaving it. Track A implements the
operators on Wayang's Java platform in a new module `opentsx-wayang`. Track B ports the same catalog to a Rust runner
for low-capacity hardware. Both tracks share one catalog, one topic and schema contract and one set of test vectors.

## Shared test data

Every criterion below names its data with one of these labels.

| Label | Data |
|---|---|
| **V** | shared test vectors: `opentsx-model/src/test/resources/testvectors/*.json`, `docs/datamodel-2026/testvectors/*.json`, `QuantileSketchTest.testVector` |
| **W** | wind-park scenario `opentsx-windpark/scenarios/ostsee-demo.yaml`: park `ost-01`, turbines T01 to T10, seed 42, 6 h at 1 Hz from 2026-10-01T00:00:00Z, episodes of 10 min; written to KafScale with `WindparkSim --kafscale` ([`opentsx-windpark/README.md`](../opentsx-windpark/README.md), section CLI) |
| **W3** | W split into three sites by turbine: `s1` = T01 to T03, `s2` = T04 to T06, `s3` = T07 to T10; `groupBy: [park]` |
| **K** | KafScale conformance setup of the CI job "KafScale conformance" (`.github/workflows/ci.yml:145-180`): KafScale v1.6.0 built from source, in-memory S3, `127.0.0.1:39092` |

A criterion that runs against K is accepted only when the surefire report shows the named test as executed, with
zero failures, errors and skips, as the existing streaming acceptance requires (`ci.yml:180`).

## Phases and milestones

The [unified delivery plan](../docs/devguide/integration/wayang-unified-plan.md) defines U00 to U09 for the
UI-to-Wayang-to-multiple-Rust-node target and the separate bounded storage profile.
The small Rust deployment proof follows shared contracts rather than waiting for the full A3 catalog.
Each operator still requires its reference vectors and admission gate before use.

- [x] U00 offline foundation: shared execution-assignment schema, exact source/fragment identities, Java/Rust
  preflight validators and generated cross-runtime cases. This establishes no deployed runner or live fencing.
- [ ] U01 physical deployment, complete ownership, channels, capabilities and structured refusal contract.
- [x] U02 local bounded stage: `RustPlugin` maps connected topic sources, unions, optional fused chain and sink to
  `RustPlatform`; `RustExecutor` invokes one complete stage action with no raw-payload channel or Java fallback.
- [x] U03 local bounded control: authenticated backend PostgreSQL journal and standalone advisory-lock coordinator,
  frozen UI text/cut/clock/plan, whole-topic ownership, private worker preparation and identical activation retry.
  Production migrations, per-site delivery sequence, restore fencing and general retire remain open.
- [x] U05 local bounded acceptance: run the actual UI-to-Wayang-to-three-worker Compose helper, retain public
  deployment/receipt and Java parity evidence after initial execution, exact retry and worker/coordinator restart.
- [x] U03/U04 local continuous control and execution: persist successor plans before dispatch, restore worker-local
  operator state, enforce predecessor/cursor continuity and cumulative ownership, bound history and reconcile Stop.
  Retain private preparations and the exact plan when a preparation outcome remains unresolved.
- [x] U05 local continuous acceptance: verify live input, downtime backlog, stateful restart, single-worker outage,
  later input and Stop through the actual UI and Wayang on three whole-topic Rust workers, with Java parity evidence.
- [x] U04 trusted-local bounded subset: `tsx-runner` 0.1.0 replays one complete topic with pass-through or
  plausible-range checks, unit conversion and a trailing resample. Shared vectors, local failure/retry tests and
  a standalone three-worker Compose fixture are implemented. The separate UI rollout helper exercises the custom
  Wayang stage and journal path; production fleet activation remains open.
- [ ] U06 production stale-output enforcement, rollout, policy and operating qualification.
- [ ] U08 bounded Iceberg snapshot/seal/publication profile.

### Local bounded UI rollout evidence

The [local guide](../docs/devguide/integration/wayang-rust-compose.md) records the helper and evidence directory.
`RustPlatformTest` checks actual Wayang Rust stage selection, original node retention and refusal without Java fallback.
`RustRolloutTest` checks one original UI document lowered to complete distinct-topic fragments, all-private preparation,
series collision refusal, receipt validation and same-request uncertain-publication retry. Backend tests check normal
role/flow ownership, installation-wide source identity, persisted-plan reuse and ownership retention on uncertainty.
Worker lifecycle tests check prepare, activation, checkpoint recovery and exact completed retry.

The separate browser/Compose acceptance submits one actual UI DAG over topics with one, two and three partitions.
The local acceptance on 2026-11-17 passed all three phases: initial execution, exact UI retry and worker/driver/
coordinator restart. Each phase retained twelve output records and twelve distinct episode identities.
`RustRolloutComposeFixture` computes reference output from that submitted DAG and requires twelve unchanged canonical
output records through retry and restart. Run-generated `result.json` and per-phase views supply actual execution
results; unit tests alone do not satisfy that acceptance. Activation remains at least once and is not atomic publication.

### Local continuous UI rollout evidence

The [continuous guide](../docs/devguide/integration/wayang-rust-continuous.md) records the separate acceptance profile.
The actual Compose run on 2026-11-18 passed all nine phases, including new input during execution-service downtime,
later input, an isolated `runner-2` outage and restart, and UI Stop. Seven acknowledged increments consumed
42 input records and produced 42 distinct output episodes. Every final position was seven, across topics with one,
two and three partitions on their original workers. While `runner-2` was down, the eight other services stayed
running and confirmed progress remained at five increments and 30 records; catch-up needed no deployment request.
`RustContinuousComposeFixture` compares canonical wire records with continuing Java `SeriesStream` state, including
open resample tails. Exact retry and Stop leave the confirmed output unchanged. Broker restart and production
stale-writer fencing are not established by this run.
`RustContinuousRolloutTest` separately injects lost preparation responses, transient HTTP failures and mixed
unresolved/permanent refusals. It verifies retained candidates and exact retry before activation, while conclusive
refusals and ownership collisions still reject. The Compose outage starts while the deployment is idle.

### A0: foundation and decisions
- [x] Owner decisions D-T1 to D-T12 recorded in the decision table below (2026-11-16: every recommendation accepted).
- [x] Module `opentsx-wayang` with Wayang 1.1.1 and the exclusions of concept 20, section 3.3 (2026-11-16): versions and exclusions in the root `dependencyManagement`, Hadoop from the managed shaded client 3.5.0, `TsxJavaPlugin` instead of `Java.basicPlugin()`, `WayangSmokeTest`.
- [x] KafScale transport security checked (2026-11-16, KafScale v1.6.0): no TLS and no SASL on the Kafka listener, KafScale ACLs with prefix rules, principal from `client.id` or the PROXY protocol ([concept 20, section 11](../docs/datamodel-2026/20-WAYANG-TS-OPERATORS.md#11-security-model)).

| # | Criterion | Proof | Data | Pass |
|---|---|---|---|---|
| A0.1 | The dependency tree has no excluded artifact | `mvn -B -ntp -pl opentsx-wayang dependency:tree`, output attached to the pull request | none | zero lines matching `log4j-slf4j-impl`, `google-cloud-storage`, `azure-`, `aws-java-sdk-s3` |
| A0.2 | Hadoop resolves to the OpenTSx version only | same output | none | `hadoop-client-api:jar:3.5.0` present; no Hadoop artifact in version 3.3.6 |
| A0.3 | A Wayang plan runs inside the OpenTSx build on both JDKs | `WayangSmokeTest` (collection source, map, local callback sink with `TsxJavaPlugin`), CI job "Java 17" and "Java 21" | fixed list of 1,000 integers | 1 test executed per JDK, output equals the input mapped, 0 failures |
| A0.4 | KafScale security is documented | concept 20, section 11, states for TLS, SASL and ACLs whether KafScale v1.6.0 supports them, each with `file:line` or command output | KafScale source at tag v1.6.0 | three answers, each with evidence |
| A0.5 | Decisions are recorded | decision table of this file | none | D-T1 to D-T12 each have a decision and a date |

Status of A0 on 2026-11-16, measured on the branch `docs/rust-kafka-runner-plan`:

| # | Result |
|---|---|
| A0.1 | met: `mvn -B -ntp -pl opentsx-wayang dependency:tree` lists 0 lines matching the excluded artifacts |
| A0.2 | met: `hadoop-client-api:jar:3.5.0` (compile), `hadoop-client-runtime:jar:3.5.0` (runtime), no other Hadoop artifact |
| A0.3 | met on Java 21 locally (`Tests run: 1, Failures: 0, Errors: 0, Skipped: 0`); Java 17 runs in the CI job of the pull request |
| A0.4 | met: concept 20, section 11, with `file:line` evidence from KafScale v1.6.0 |
| A0.5 | met: decision table below |

### A1: KafScale source, sink, checkpoint, increments
- [x] `TsxTopicSource`, `TsxTopicSink`, `TsxCheckpoint` and the increment runner `TsxIncrement` (S5.1, S6), 2026-11-16.
- [x] A1.7: both increment chains measured at 5, 30 and 60 seconds, with
  30 increments per condition and three repetitions. The
  [report and raw samples](../docs/datamodel-2026/measurements/wayang-increment-latency-2026-11-17/REPORT.md)
  record all 18 conditions and 540 increments, without a threshold.

| # | Criterion | Proof | Data | Pass |
|---|---|---|---|---|
| A1.1 | Avro encoding is byte-identical | `TsxWireVectorsTest` | V: `wire-format.json` | every record's hex and fingerprint equal, 100 % of cases |
| A1.2 | Series ids are identical | `TsxWireVectorsTest` | V: `series-id.json`, `xxh64.json`, `time-deltas.json`, `bucket-checksum.json` | 100 % of cases equal |
| A1.3 | Clients follow the KafScale rules | `TsxClientSettingsTest` reads the properties the source and sink build | none | no `group.id`; `enable.auto.commit=false`; `enable.idempotence=false`; `acks=all`; `max.in.flight.requests.per.connection=1` |
| A1.4 | A 1:1 pass-through plan copies every episode | `TsxPassThroughIntegrationTest` | W on K | output episode ids and payload bytes equal the input set; count equal |
| A1.5 | A crash repeats output only with identical ids | `TsxCrashRecoveryIntegrationTest`: stop the runner between produce and checkpoint at three points, restart | W on K | after deduplication by id, output equals an uninterrupted run; every duplicate has an identical id and payload; 3 of 3 points |
| A1.6 | An unknown fingerprint stops the task | `TsxFingerprintIntegrationTest` writes one record with another schema | K | task state `failed`, status `error` equals the message of S3.2 exactly; no later record processed |
| A1.7 | Increment latency is known | protocol of concept 20, section 14 | W on K | measured and reported with raw samples; no threshold |

Status of A1.1 to A1.6 on 2026-11-16, run with JDK 21 against KafScale v1.6.0
built from source (in-memory S3, `127.0.0.1:39092`). A1.7 was measured on
2026-11-17 on the host recorded in its report:

| # | Result |
|---|---|
| A1.1 | met: `TsxWireVectorsTest`, both episodes of `wire-format.json` byte-identical after source and sink; fingerprints equal. The first run failed: Avro decodes maps into hash maps, so the sink changed the tag order. The sink now writes maps in UTF-8 key order (S6.2) |
| A1.2 | met: `TsxWireVectorsTest` runs `series-id.json`; `xxh64.json`, `time-deltas.json` and `bucket-checksum.json` are run by `SeriesIdentityTest` and `TimeAxisAndSummaryTest` of `opentsx-model`, whose classes Track A uses unchanged |
| A1.3 | met: `TsxClientSettingsTest`, 2 tests |
| A1.4 | met: `TsxPassThroughIntegrationTest`, all episodes of W with identical bytes; a second increment reads 0 records |
| A1.5 | met: `TsxCrashRecoveryIntegrationTest`, failures after 1, 150 and 299 of 300 episodes; no checkpoint after a failed increment; after the next increment every episode id present, every duplicate byte-identical |
| A1.6 | met: `TsxFingerprintIntegrationTest`, `TsxIncrementException` carries the message of S3.2 exactly, no checkpoint, nothing written. `TsxStatusIntegrationTest` reports the failed increment with the same error |
| A1.7 | met on 2026-11-17; report and raw samples linked above |

A1.7 records 18 conditions, 540 increments, 64,800 input and output records and
0 Produce errors. All outputs match the reference. The report records
per-output p50/p95/p99 and first-Fetch/wall samples for both chains.

The CI job "KafScale conformance" runs the three integration tests and fails when one of them is missing, skipped or
failed.

### A2: clean chain, statistics, status
- [x] Java execution operators, mappings and cost functions for `dq.checks`, `convert.unit`, `resample`, `model.wind-shear`, `model.power-curve`, `feature.lag`, `feature.rolling` (one operator `TsxSeriesChain`, S2.3) and `stats.welford-ddsketch` (`TsxStatsWindow`); operator state in the checkpoint; status writer `TsxStatus` and loop `TsxIncrement.loop` (2026-11-16).
- [x] New vector file `docs/datamodel-2026/testvectors/stats-window.v1.json`
  with its generator, `tools/java_operator_vectors.py`; five exact cases in
  `StatsWindowVectorsTest`.
- [x] All six acceptance criteria: five shared statistics cases through Wayang,
  deployed processor parity, S5.4 progress and heartbeats, and four exact
  catalog parameter messages. README, manual, and regression tests are updated.

| # | Criterion | Proof | Data | Pass |
|---|---|---|---|---|
| A2.1 | Clean operators match the vectors | `TsxCleanVectorsTest` | V: `clean-ops.v1.json` | 15 of 15 cases; every value within `1e-12 * max(1, |expected|)`; every gap a gap |
| A2.2 | Statistics match the vectors | `TsxStatsVectorsTest` | V: `stats-window.v1.json` | 100 % of cases bit-identical |
| A2.3 | Sketch bytes match Java and Python | `TsxStatsVectorsTest` | V: `QuantileSketchTest.testVector` | bytes equal `017b14ae47e17a843f020500015c0278012601ba0301024301ca0101` |
| A2.4 | Track A equals the existing Java processor | `TsxCleanParityIntegrationTest` runs one clean DAG through Track A and through `VectorProcessor` | W on K | identical sets of episode ids and identical values; 0 differences |
| A2.5 | Status records follow S5.4 | `TsxStatusIntegrationTest` during A2.4 | W on K | every record valid against S5.4 with `track` = `A`; largest gap between two records of one task ≤ 31 s |
| A2.6 | Parameter errors are exact | `DagToWayangErrorsTest`, one DAG per message 13 to 16 of S2.5 | fixtures in the test | 4 of 4 messages equal character for character |

Status of A2 on 2026-11-16, JDK 21, KafScale v1.6.0 built from source:

| # | Result |
|---|---|
| A2.1 | met: `TsxCleanVectorsTest`, 15 of 15 cases through the Java execution operator and 15 of 15 through a Wayang plan |
| A2.2 | met: `TsxStatsVectorsTest`, all five shared cases through real Wayang plans, with exact fields, non-finite values, quantiles, and sketch bytes. Input includes observations; explicit keyed windows cover empty input |
| A2.3 | met: `TsxStatsVectorsTest` compares the canonical sketch hex exactly. `TsxStatsWindowTest` also checks a closed window across two increments with restored state |
| A2.4 | met: `TsxCleanParityIntegrationTest` runs one clean DAG on W through Track A, then deploys the same document to `VectorProcessor` through KafScale's control topic. Separate output ranges have identical episode ids, timestamps, and values, with zero differences or duplicates. The three-increment state test also passes |
| A2.5 | met: parity uses the S5.4 assertions of `TsxStatusIntegrationTest`, including progress fields, track A, terminal states, and gaps at most 31 s. Seven deterministic loop tests cover 125 s increments, 95 s idle intervals, heartbeat failures before checkpoints, and preservation of processing errors |
| A2.6 | met: `DagToWayangErrorsTest` checks messages 13 to 16 exactly against processor descriptions. It also checks optional defaults, required fields, statistics limits, and catalog validation before parameter validation |

Local verification on JDK 21: the KafScale reactor runs 402 tests with zero
failures or errors and one conditional skip. All 86 Wayang tests execute with
zero skips. CI requires the A1/A2 acceptance classes, all five statistics
vector cases, and the four named parameter-message tests to execute successfully.

### A3: remaining catalog 1, catalog freeze

- [x] `align.vector`, `segment.threshold`, `segment.extremum`, `sax.word`,
  `dq.result`; ports of `pair.xcorr` (from kamir/WikiExplorer.NG) and `pair.es`
  (from `event_sync.py`).
- [x] Pure Java `pair.xcorr` kernel: `TsxPairXcorrVectorsTest` loads all five
  shared cases and checks absolute error below `5e-13` (A3.2).
- [x] Pure Java `pair.es` kernel: `TsxPairEsVectorsTest` loads all eight shared
  cases, checks event lists and scores, and compares `Mulberry32` exactly
  (A3.3).
- [x] New vector files `segment-threshold.v1.json`, `segment-extremum.v1.json`,
  `sax-word.v1.json`, `dq-result.v1.json`, generated over the existing Java
  code by `tools/java_operator_vectors.py`; 26 exact cases in the owning
  modules.
- [x] `align.vector` on Wayang: `TsxAlignVector` over `VectorStream` with named
  inputs (each input is marked with `TsxTagged` and joined with a union),
  state in `TsxState`, records `opentsx.vector.v1` through `TsxVectorSink`
  (A3.1).
- [x] `pair.xcorr` on Wayang: `TsxPair` compares every series of input `a`
  with every series of input `b`, episode by episode, by the rules of
  `pairs.ts` (pairing, windows, clock, coverage); one result episode per
  field and pair range, id of S6.2 (A3.2).
- [x] `pair.es` on Wayang: the second method of `TsxPair`, over the existing
  `ops/TsxPairEs` kernel (A3.3).
- [x] Native Wayang mappings for `segment.threshold`, `segment.extremum`,
  `sax.word`, and `dq.result`: 26 exact shared cases in the three A3.4 test
  classes. Segment detectors and retained samples are checkpointed per series.
- [x] Stream/batch parity across all 65 numerical vector cases: 61 native
  cases and four explicit ES kernel cases, six chunk/order runs each.
- [x] Catalog `tsx-catalog/1` frozen: 16 version-1 identities in
  `systems/catalogs/tsx-catalog-1.yaml`, with complete validated descriptions.

Status of A3.1 on 2026-11-16, JDK 21, KafScale v1.6.0 built from source:
`TsxVectorVectorsTest` runs all 6 shared cases through a Wayang plan with exact
equality, the same 6 cases in two increments with the state carried between
them, and an input the vector does not read (13 tests, 0 failures).
`TsxVectorIntegrationTest` runs three increments between the episode topic and
a vector topic: 9 points, then 0, then the tenth, each written once.
`VectorStream` in `opentsx-retrieval` gained named inputs for this:
`VectorStreamTest` compares them with `VectorAligner.align` for all 6 cases in
blocks of 1, 2 and 3 samples and in two series orders.

Status of A3.2 on 2026-11-16, JDK 21: `TsxPairVectorsTest` runs all 5 shared
`pair.xcorr` cases through a Wayang plan with an absolute error below `5e-13`
(peak of largest magnitude, as `xcorr.ts`), the same 5 with B in a later
increment, a repeated delivery, and `intersect` pairing (12 tests, 0
failures). An episode waits in `TsxState` until every series of the other
input has passed its end. One difference to the workbench: without `every`
the clock is the larger sampling interval of the two paired episodes, not of
the two whole series, because a continuing input has no whole series.


| # | Criterion | Proof | Data | Pass |
|---|---|---|---|---|
| A3.1 | `align.vector` matches | `TsxVectorVectorsTest` | V: `align-vector.v1.json` | 6 of 6 cases, exact equality |
| A3.2 | `pair.xcorr` matches | `TsxPairXcorrVectorsTest` | V: `pair-xcorr.v1.json` | 5 of 5 cases; every value within 5e-13 |
| A3.3 | `pair.es` matches | `TsxPairEsVectorsTest` | V: `pair-es.v1.json` | 8 of 8 cases; events equal; `q_total`, `q_ab`, `q_ba`, `q_rand` within 5e-13 |
| A3.4 | New operators match their new vectors | `TsxSegmentVectorsTest`, `TsxSaxVectorsTest`, `TsxDqVectorsTest` | V: the four new files | 100 % of cases exact |
| A3.5 | Stream equals batch | `TsxStreamEqualsBatchTest` feeds every vector input in chunks of 1, 7 and all records, in two series orders | V | identical output in all 6 runs per case |
| A3.6 | The catalog is complete and valid | `SystemDocTest`, `TsxCatalogBindingsTest` | `systems/catalogs/tsx-catalog-1.yaml`, `systems/processors/` | one description per row of S1.7 with `state`, `merge`, `runtimes.wayang.status: available`; schema validation 0 errors |

Status of A3.3 on 2026-11-17, JDK 21: `TsxPairEsWayangTest` executes the four
sampled-series cases through Wayang, both together and with restored state
between increments. The four event-list cases remain kernel tests.
All 11 native ES tests pass, including unsigned 32-bit seed conversion beyond
the signed long range and Workbench-compatible numeric labels.

Status of the completed catalog on 2026-11-17:

| #    | Result                                                     |
| ---- | ---------------------------------------------------------- |
| A3.4 | met: all 12 segment, seven SAX, and seven DQ cases         |
| A3.5 | met: 61 native cases and four explicit ES kernel cases     |
| A3.6 | met: 16 version-1 descriptions, schema validation 0 errors |

`TsxStreamEqualsBatchTest` executes 390 comparisons with chunks of 1, 7 and
all records, in two series orders. Stateful helpers restore serialized state
between chunks. Pair/SAX episodes, DQ statistics and event-list inputs remain
atomic. Clean comparisons use logical samples because envelope grouping and
ids follow fragment boundaries; pair comparisons exclude only execution
`createdAt`. Segment payloads, vector JSON, SAX, DQ and statistics/sketch
records retain their exact comparisons. Wire and identity suites provide the
separate transport proof; event-only ES vectors are not native episode plans.

`SystemDocTest` freezes membership, versions, aliases and required metadata
against S1.7 and rejects mutations. `TsxCatalogBindingsTest` couples every
description to its compiled logical and Java execution classes. Local compiler
and fleet admission work remain in A4; catalog availability does not claim
those deployment capabilities.

`TsxSignalSinkIntegrationTest` checks JSON signal writes and rejection of an
invalid batch before any records are sent. DQ ids include the check and both
window bounds, preserving distinct findings under the S6.2 deduplication rule.

### A4: translation, signed plans, federation on JVM sites

- [x] `DagToWayang`, `TsxSitePlatform`, `TsxSiteExecutor`, `TsxSiteAgent`,
  plan service API (S5.3), forwarder and central merge, qualified for the
  bounded JVM profile on 2026-11-17.

The owner-approved A4 qualification uses bounded replicated JVM assignments,
three site instances in one execution JVM and four independent disposable
KafScale v1.6.0 brokers. It preserves complete source cuts and exact
source/fragment identity, and admits one statistics output per site.
Signed member and scope inventories establish finality for those bounded cuts.
General continuous fleet activation, production PKI/ACL enrollment and ownership
takeover remain separate gates. The owner approved the wire/security contract
and the measured D-T2 revision on 2026-11-17.

| # | Criterion | Proof | Data | Pass |
|---|---|---|---|---|
| A4.1 | Every rejection message is exact | `DagToWayangErrorsTest`, one fixture per message of S2.5 | fixtures | 22 of 22 messages equal character for character |
| A4.2 | Workbench DAGs translate | `DagToWayangWorkbenchTest` over the DAG documents of `opentsx-saas-frontend/src/workbench/workbench.test.ts` and `clean.test.ts` | those fixtures | every document whose operators are in catalog 1 translates; every other fails with message 11 |
| A4.3 | Bad plans are refused; accepted retry is idempotent | `TsxSiteAgentPlanTest`: tampered payload, `alg: none`, expired new activation, older version, conflicting bytes for an accepted version, wrong site, identical accepted retry before and after expiry | fixtures | six refusals with their exact S5.5 codes; both identical retries return the recorded outcome without reactivation |
| A4.4 | Bad sites are refused | `ConformanceHandshakeTest`: one site with a wrong input fingerprint, one with a `seriesId` of another site | W3 on K | responses `{"result":"refused","check":"topics.fingerprints",...}` and `{"result":"refused","check":"series.disjoint",...}`; 2 of 2 |
| A4.5 | Federated equals centralized | `FederatedStatsIntegrationTest`: `stats.welford-ddsketch` with `windows: [PT10M, PT1H]`, `alpha: 0.01`, over three sites, against one `StatsAccumulator` per group and window over all values | W3 on K | for every window: exact counts/min/max; sketch bytes equal in the one-runtime/no-collapse domain; mean/m2 within D-T2; quantile accuracy qualified by S4.3, with explicit zero-bucket and collapsed-tail cases |
| A4.6 | The merge is order-independent | See the proof below | W3 partials | See the proof below |
| A4.7 | Late and missing sites follow S4.5 | `CompletenessIntegrationTest`: stop `s3`, wait past `maxDelay`, restart | W3 on K, `maxDelay: PT1M` | first record `tsx-final: false`, `tsx-missing-sites: s3`; after restart revision 1 with `tsx-final: true`; revision 0 unchanged in the topic |
| A4.8 | Route J equals route S | `JobStateRouteIntegrationTest`: a bounded job over W3 | W3 on K | merged records bit-identical to A4.5 |
| A4.9 | Data moved is known | protocol of concept 20, section 14, baseline (a) | W3 on K | bytes leaving each site and bytes of the centralized run measured and reported |

For A4.6, `MergeOrderTest`, `W3StatsMergeTest` and
`FederatedStatsIntegrationTest` merge the three partials of every A4.5 window
in all six orders. All six merged records are bit-identical, including
comparison with the actual published record.

Qualification results on JDK 21 and source-built KafScale v1.6.0, 2026-11-17:

| # | Result |
| --- | --- |
| A4.1 | met: `DagToWayangErrorsTest`, 35 tests including all 22 exact rejection vectors |
| A4.2 | met: `DagToWayangWorkbenchTest`, 6 tests over 5 exported YAML files; all 16 catalog bindings execute |
| A4.3 | met: `TsxSiteAgentPlanTest`, 14 tests, including both lost-ACK paths and exact input-topic coverage |
| A4.4 | met: `ConformanceHandshakeTest`, 3 real transport cases, including both specified site refusals |
| A4.5 | met: `FederatedStatsIntegrationTest`, all 420 windows; exact fields/sketches and approved D-T2 bound |
| A4.6 | met: `MergeOrderTest`, 8 tests; `W3StatsMergeTest`, 2 tests; 2,520 published-record comparisons |
| A4.7 | met: `CompletenessIntegrationTest`, 420 incomplete records, then 420 final revisions; old bytes unchanged |
| A4.8 | met: `JobStateRouteIntegrationTest`, identical 1,260 partials and 420 merged records across both routes |
| A4.9 | met: 9 measured site/route samples; report and raw CSV files linked below |

The [measurement report and raw samples](../docs/datamodel-2026/measurements/wayang-federation-2026-11-17/REPORT.md)
record the clean source commit, hardware, OS, JDK, broker source and commands.
The measured counters are Kafka Produce request frames, aggregate HTTP bodies
and encrypted client-stream bytes. These are separate protocol layers, not a
total network-egress counter or a latency comparison. Maximum relative mean
error is `1.7308017671758118e-14`; maximum relative M2 error is
`2.7235050829345894e-12`, below the owner-approved `3e-12` bound.

### A5: evaluation of Track A
| # | Criterion | Proof | Data | Pass |
|---|---|---|---|---|
| A5.1 | Baseline (a), centralized against federated, is reproducible | script under `tools/` that runs A4.5 and A4.9 at 1, 3 and 10 sites | W split by turbine | script runs from a clean checkout; report with raw samples committed |
| A5.2 | Baseline (b), Java streaming path, is measured | same script, chains of concept 20, section 14 | W on K | throughput and latency of Track A and of `VectorProcessor` measured with the protocol and reported; no threshold unless the owner sets one |

### B1: Rust runner foundation
- [ ] `tsx-runner run`: decode, encode, pass-through, checkpoint; cross builds.

The implemented command is `tsx-runner replay`, using the
[trusted-local bounded profile](../docs/devguide/integration/rust-runner-local.md).
It provides Avro episode I/O, series identity, explicit caps, retained operator state and a completed receipt
after all output acknowledgements. The Compose fixture assigns three separate topics with one, two and three
partitions and compares their output to Java. The fleet command, full cross-target matrix and all B1 criteria
remain separate qualification work.

| # | Criterion | Proof | Data | Pass |
|---|---|---|---|---|
| B1.1 | Cross builds exist | CI job builds `aarch64-unknown-linux-musl`, `armv7-unknown-linux-musleabihf`, `x86_64-unknown-linux-musl` | none | three artifacts; `file` reports "statically linked" for each |
| B1.2 | Avro and series ids are identical | `cargo test` on x86_64 in CI and on arm64 and armv7 hardware | V: as A1.1 and A1.2 | 100 % of cases on all three targets |
| B1.3 | KafScale conformance holds | Rust port of the six required tests of `opentsx-kafscale-conformance` | K | 6 of 6 pass |
| B1.4 | Partitioning equals Java | `placement_probe` test: 10,000 random keys, 12 partitions, Rust producer against Java `DefaultPartitioner` | generated keys | 10,000 of 10,000 equal |
| B1.5 | Crash recovery as A1.5 | `crash_recovery` integration test | W on K | as A1.5 |
| B1.6 | Unknown fingerprint as A1.6 | `fingerprint` integration test | K | as A1.6 |

### B2: edge operators

The executable local subset is `dq.checks` with `PLAUSIBLE_RANGE`, `convert.unit` and optional trailing `resample`.
The operator tests consume the eight supported `clean-ops.v1.json` cases and refuse the seven unsupported cases.
They check all chunk sizes and state restores, including exclusive derived-series ownership.
The broader B2 acceptance table is not a claim that the remaining target operators execute in Rust.

| # | Criterion | Proof | Data | Pass |
|---|---|---|---|---|
| B2.1 | Operator vectors pass | `cargo test` | V: every file for the operators with B = yes in S1.7 | the thresholds of A2.1 to A3.5 |
| B2.2 | Track B equals Track A | `TrackParityIntegrationTest` runs one DAG on both tracks | W on K | identical episode ids and values; DDSketch bin differences only for values listed by the test as boundary values (S4.3, law 5) |
| B2.3 | The `pair.xcorr` limit is exact | test with a limit of 1,000 products | fixture | message 20 of S2.5 character for character |

### B3: agent, offline operation, SSH launch
| # | Criterion | Proof | Data | Pass |
|---|---|---|---|---|
| B3.1 | The pull protocol follows S5.3 | `agent_api` tests against a test driver | fixtures | each endpoint answers as specified; 401, 403, 404, 409 cases each produced once |
| B3.2 | Refusal codes are exact | `agent_refusals` test | fixtures | 8 of 8 codes of S5.5 |
| B3.3 | Offline catch-up loses nothing | `offline_catchup` test: signal cluster unreachable for the whole W run, then reachable | W on two K brokers (site and signal) | every partial reaches the signal cluster; all windows reach `tsx-final: true`; 0 missing, 0 duplicates after deduplication by key and revision |
| B3.4 | SSH launch exit codes | `ssh_launch` test | fixtures | exit 0 on stop, 2 on refused plan with the refusal message on standard error, 3 on runtime failure |

### B4: mixed federation and evaluation on small hardware
| # | Criterion | Proof | Data | Pass |
|---|---|---|---|---|
| B4.1 | Mixed federation equals centralized | `FederatedStatsIntegrationTest` with `s1`, `s2` on Track A and `s3` on Track B | W3 on K | counts, `min`, `max` bit-identical; `mean`, `m2` within D-T2; quantile accuracy qualified by S4.3; listed boundary, zero-bucket and collapsed-tail cases explain sketch differences |
| B4.2 | Baseline (c) is measured | protocol of concept 20, section 14, on one arm64 and one armv7 board | W, 100, 1,000 and 10,000 series | RSS, CPU, start time, throughput of both tracks measured and reported with raw samples; no threshold unless the owner sets one |

### B5: WASM operators
| # | Criterion | Proof | Data | Pass |
|---|---|---|---|---|
| B5.1 | Fuel stops a runaway operator | `wasm_limits` test with an endless loop | fixture | the task fails with a fuel error within the configured fuel |
| B5.2 | Memory is capped | `wasm_limits` test that allocates past the limit | fixture | allocation refused; task fails |
| B5.3 | A WASM operator matches its vectors | `wasm_operator` test with `convert.unit` compiled to WASM | V: `clean-ops.v1.json` (unit cases) | as A2.1 |

Criteria per milestone: A0 5, A1 7, A2 6, A3 6, A4 9, A5 2, B1 6, B2 3, B3 4, B4 2, B5 3.

## Decisions (owner)

| Id | Question | Options | Recommendation | Decision |
|---|---|---|---|---|
| D-T1 | Edge runtime | Rust (Track B); Go (concept 11, D25); none, JVM only | Rust: `apache-avro` supports single-object encoding, `wasmtime` for later user operators; one non-JVM runtime only | as recommended, owner, 2026-11-16 |
| D-T2 | Tolerance for federated moments | See qualification below | See qualification below | Owner, 2026-11-17 |
| D-T3 | Default federation route | signal cluster; job state; both | signal cluster: works with offline sites and continuous jobs | as recommended, owner, 2026-11-16 |
| D-T4 | Plan delivery | pull agent (S5.3); push agent with an inbound port; SSH only | pull agent; SSH for sites that require it | as recommended, owner, 2026-11-16 |
| D-T5 | Signature algorithm | EdDSA (Ed25519); ES256 | EdDSA: small keys, deterministic signatures | as recommended, owner, 2026-11-16 |
| D-T6 | Track A increment interval | one fixed interval; per DAG; a long-running Java operator outside Wayang's model | per DAG, default PT1M, decided again after A1.7 | as recommended, owner, 2026-11-16 |
| D-T7 | Default `maxDelay` | PT10M; PT1H; per job only | per job, no default: offline periods differ by site | as recommended, owner, 2026-11-16 |
| D-T8 | New `TopicLayout` kinds | `aggregates`, `signals`, `repartition.<dag>.<node>`; other names | as proposed | as recommended, owner, 2026-11-16 |
| D-T9 | Wayang dependencies | exclusions; contribute a slimmer module upstream; accept the full footprint | exclusions now, upstream contribution later | as recommended, owner, 2026-11-16 |
| D-T10 | S3 segment input | not needed; Go KafScale template; Rust segment decoder | not needed: both tracks read the Kafka protocol | as recommended, owner, 2026-11-16 |
| D-T11 | Kafka transport security until KafScale is checked | TLS-terminating proxy; isolated network; wait for KafScale support | TLS-terminating proxy | as recommended, owner, 2026-11-16 |
| D-T12 | Where `opentsx-wayang` lives | module in this repository; separate repository | module in this repository: it reuses five OpenTSx modules | as recommended, owner, 2026-11-16 |

D-T2 compares a fixed relative bound with per-data-set measurement in A4.5.
The owner approved relative `3e-12` for bounded W3 `mean` and `m2` on
2026-11-17, retaining exact counts, extrema and uncollapsed sketch bytes.
The measured maximum mean error is `1.7308017671758118e-14`; maximum M2 error
is `2.7235050829345894e-12` over all 420 windows and six merge orders.
No bound is established for arbitrary data or mixed runtimes.

## Non-goals
- A Kafka Streams-like runtime: no RocksDB state stores, no changelog topics, no automatic repartition topics, no
  exactly-once ([concept 20](../docs/datamodel-2026/20-WAYANG-TS-OPERATORS.md), section 3.4).
- Training in the stream; joins over raw data of two sites; S3 segment input.
