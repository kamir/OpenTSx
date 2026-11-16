# TASK-020: Time-series operators on Apache Wayang (Track A Java, Track B Rust)

## Task Metadata
- **Task ID**: TASK-020-wayang-ts-operators
- **Created**: 2026-11-16
- **Status**: Planned
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
- [ ] U02/U03 stage compilation, fake-transport proof and durable control/delivery sequence.
- [ ] U04/U05 minimal Rust runner and actual UI-to-two-node proof with static assignments.
- [ ] U06 production stale-output enforcement, rollout, policy and operating qualification.
- [ ] U08 bounded Iceberg snapshot/seal/publication profile.

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

| # | Criterion | Proof | Data | Pass |
|---|---|---|---|---|
| A1.1 | Avro encoding is byte-identical | `TsxWireVectorsTest` | V: `wire-format.json` | every record's hex and fingerprint equal, 100 % of cases |
| A1.2 | Series ids are identical | `TsxWireVectorsTest` | V: `series-id.json`, `xxh64.json`, `time-deltas.json`, `bucket-checksum.json` | 100 % of cases equal |
| A1.3 | Clients follow the KafScale rules | `TsxClientSettingsTest` reads the properties the source and sink build | none | no `group.id`; `enable.auto.commit=false`; `enable.idempotence=false`; `acks=all`; `max.in.flight.requests.per.connection=1` |
| A1.4 | A 1:1 pass-through plan copies every episode | `TsxPassThroughIntegrationTest` | W on K | output episode ids and payload bytes equal the input set; count equal |
| A1.5 | A crash repeats output only with identical ids | `TsxCrashRecoveryIntegrationTest`: stop the runner between produce and checkpoint at three points, restart | W on K | after deduplication by id, output equals an uninterrupted run; every duplicate has an identical id and payload; 3 of 3 points |
| A1.6 | An unknown fingerprint stops the task | `TsxFingerprintIntegrationTest` writes one record with another schema | K | task state `failed`, status `error` equals the message of S3.2 exactly; no later record processed |
| A1.7 | Increment latency is known | protocol of concept 20, section 14 | W on K | measured and reported with raw samples; no threshold |

Status of A1 on 2026-11-16, run with JDK 21 against KafScale v1.6.0 built from source (in-memory S3, `127.0.0.1:39092`):

| # | Result |
|---|---|
| A1.1 | met: `TsxWireVectorsTest`, both episodes of `wire-format.json` byte-identical after source and sink; fingerprints equal. The first run failed: Avro decodes maps into hash maps, so the sink changed the tag order. The sink now writes maps in UTF-8 key order (S6.2) |
| A1.2 | met: `TsxWireVectorsTest` runs `series-id.json`; `xxh64.json`, `time-deltas.json` and `bucket-checksum.json` are run by `SeriesIdentityTest` and `TimeAxisAndSummaryTest` of `opentsx-model`, whose classes Track A uses unchanged |
| A1.3 | met: `TsxClientSettingsTest`, 2 tests |
| A1.4 | met: `TsxPassThroughIntegrationTest`, all episodes of W with identical bytes; a second increment reads 0 records |
| A1.5 | met: `TsxCrashRecoveryIntegrationTest`, failures after 1, 150 and 299 of 300 episodes; no checkpoint after a failed increment; after the next increment every episode id present, every duplicate byte-identical |
| A1.6 | met: `TsxFingerprintIntegrationTest`, `TsxIncrementException` carries the message of S3.2 exactly, no checkpoint, nothing written. `TsxStatusIntegrationTest` reports the failed increment with the same error |
| A1.7 | open |

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
zero skips. CI requires nine Wayang acceptance classes, all five statistics
vector cases, and the four named parameter-message tests to execute successfully.

### A3: remaining catalog 1, catalog freeze
- [ ] `align.vector`, `segment.threshold`, `segment.extremum`, `sax.word`, `dq.result`; ports of `pair.xcorr` (from kamir/WikiExplorer.NG) and `pair.es` (from `event_sync.py`).
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
- [ ] Catalog `tsx-catalog/1` frozen.

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
| A3.6 | The catalog is complete and valid | `SystemDocTest` | `systems/processors/` | one description per row of S1.7 with `state`, `merge`, `runtimes.wayang.status: available`; schema validation 0 errors |

### A4: translation, signed plans, federation on JVM sites
- [ ] `DagToWayang`, `TsxSitePlatform`, `TsxSiteExecutor`, `TsxSiteAgent`, plan service API (S5.3), forwarder, central merge.

| # | Criterion | Proof | Data | Pass |
|---|---|---|---|---|
| A4.1 | Every rejection message is exact | `DagToWayangErrorsTest`, one fixture per message of S2.5 | fixtures | 22 of 22 messages equal character for character |
| A4.2 | Workbench DAGs translate | `DagToWayangWorkbenchTest` over the DAG documents of `opentsx-saas-frontend/src/workbench/workbench.test.ts` and `clean.test.ts` | those fixtures | every document whose operators are in catalog 1 translates; every other fails with message 11 |
| A4.3 | Bad plans are refused; accepted retry is idempotent | `TsxSiteAgentPlanTest`: tampered payload, `alg: none`, expired new activation, older version, conflicting bytes for an accepted version, wrong site, identical accepted retry before and after expiry | fixtures | six refusals with their exact S5.5 codes; both identical retries return the recorded outcome without reactivation |
| A4.4 | Bad sites are refused | `ConformanceHandshakeTest`: one site with a wrong input fingerprint, one with a `seriesId` of another site | W3 on K | responses `{"result":"refused","check":"topics.fingerprints",...}` and `{"result":"refused","check":"series.disjoint",...}`; 2 of 2 |
| A4.5 | Federated equals centralized | `FederatedStatsIntegrationTest`: `stats.welford-ddsketch` with `windows: [PT10M, PT1H]`, `alpha: 0.01`, over three sites, against one `StatsAccumulator` per group and window over all values | W3 on K | for every window: exact counts/min/max; sketch bytes equal in the one-runtime/no-collapse domain; mean/m2 within D-T2; quantile accuracy qualified by S4.3, with explicit zero-bucket and collapsed-tail cases |
| A4.6 | The merge is order-independent | `MergeOrderTest`: the three partials of every window of A4.5 in all 6 orders | W3 partials | all 6 merged records bit-identical |
| A4.7 | Late and missing sites follow S4.5 | `CompletenessIntegrationTest`: stop `s3`, wait past `maxDelay`, restart | W3 on K, `maxDelay: PT1M` | first record `tsx-final: false`, `tsx-missing-sites: s3`; after restart revision 1 with `tsx-final: true`; revision 0 unchanged in the topic |
| A4.8 | Route J equals route S | `JobStateRouteIntegrationTest`: a bounded job over W3 | W3 on K | merged records bit-identical to A4.5 |
| A4.9 | Data moved is known | protocol of concept 20, section 14, baseline (a) | W3 on K | bytes leaving each site and bytes of the centralized run measured and reported |

### A5: evaluation of Track A
| # | Criterion | Proof | Data | Pass |
|---|---|---|---|---|
| A5.1 | Baseline (a), centralized against federated, is reproducible | script under `tools/` that runs A4.5 and A4.9 at 1, 3 and 10 sites | W split by turbine | script runs from a clean checkout; report with raw samples committed |
| A5.2 | Baseline (b), Java streaming path, is measured | same script, chains of concept 20, section 14 | W on K | throughput and latency of Track A and of `VectorProcessor` measured with the protocol and reported; no threshold unless the owner sets one |

### B1: Rust runner foundation
- [ ] `tsx-runner run`: decode, encode, pass-through, checkpoint; cross builds.

| # | Criterion | Proof | Data | Pass |
|---|---|---|---|---|
| B1.1 | Cross builds exist | CI job builds `aarch64-unknown-linux-musl`, `armv7-unknown-linux-musleabihf`, `x86_64-unknown-linux-musl` | none | three artifacts; `file` reports "statically linked" for each |
| B1.2 | Avro and series ids are identical | `cargo test` on x86_64 in CI and on arm64 and armv7 hardware | V: as A1.1 and A1.2 | 100 % of cases on all three targets |
| B1.3 | KafScale conformance holds | Rust port of the six required tests of `opentsx-kafscale-conformance` | K | 6 of 6 pass |
| B1.4 | Partitioning equals Java | `placement_probe` test: 10,000 random keys, 12 partitions, Rust producer against Java `DefaultPartitioner` | generated keys | 10,000 of 10,000 equal |
| B1.5 | Crash recovery as A1.5 | `crash_recovery` integration test | W on K | as A1.5 |
| B1.6 | Unknown fingerprint as A1.6 | `fingerprint` integration test | K | as A1.6 |

### B2: edge operators
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
| D-T2 | Tolerance for federated `mean` and `m2` | fixed relative 1e-12; bound measured per data set in A4.5 | fixed 1e-12, revisited with the measured error of A4.5 | as recommended, owner, 2026-11-16 |
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

## Non-goals
- A Kafka Streams-like runtime: no RocksDB state stores, no changelog topics, no automatic repartition topics, no
  exactly-once ([concept 20](../docs/datamodel-2026/20-WAYANG-TS-OPERATORS.md), section 3.4).
- Training in the stream; joins over raw data of two sites; S3 segment input.
