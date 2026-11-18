# Specification 20: time-series operators on Apache Wayang (S1 to S7)

> Status: **fleet specification draft 0.1; Catalog 1 frozen**, 2026-11-17.
> All 16 native Java entries in S1.7 are available. Implemented Rust profiles
> are qualified separately in S1.9, S1.10, local continuous profile S1.11 and local publication fencing S1.12.
> Signed continuous fleet requirements remain separate contracts. Concept and research:
> [20-WAYANG-TS-OPERATORS.md](20-WAYANG-TS-OPERATORS.md). Tasks and acceptance criteria:
> [TASK-020](../../EVOLUTION/TASK-020-wayang-ts-operators.md). Catalog checks use
> Apache Wayang tag `wayang-1.1.1` and JDK 21.

The key words MUST, MUST NOT, SHOULD, SHOULD NOT and MAY are used as in
[RFC 2119](https://www.rfc-editor.org/rfc/rfc2119) and [RFC 8174](https://www.rfc-editor.org/rfc/rfc8174) when they
appear in capitals.

Terms:

| Term | Meaning |
|---|---|
| Track A | Java execution operators on Wayang's Java platform, module `opentsx-wayang` |
| Track B | the Rust runner `tsx-runner` |
| runtime | an implementation of the catalog: Track A or Track B |
| site | one admitted data/security location with its cluster bindings and one or more runtime nodes |
| node | one registered Java or Rust runtime instance assigned complete fragments and whole source topics |
| driver | the central Wayang process that translates DAGs and sends site plans |
| signal cluster | the central KafScale cluster that receives site aggregates and signals |
| DAG document | the `opentsx.dag.v1` or `opentsx.dag.v2` document the web workbench exports (`opentsx-saas-frontend/src/workbench/flow.ts:684-745`) |
| `specHash` | lower-case hex SHA-256 of the UTF-8 text of the DAG document, as the processor computes it today (`VectorProcessor.java:261`, `:797-803`) |

## S1. Operator catalog

The [unified orchestration plan](../devguide/integration/wayang-unified-plan.md) defines the multi-node deployment
boundary and separate bounded/continuous profiles. The implemented
[assignment contract](../devguide/integration/wayang-shared-contracts.md) preserves original UI text and separate
fragment text; its offline validator is not a complete runner or deployment admission service.
The executable [`tsx-runner` 0.1.0 profile](../devguide/integration/rust-runner-local.md) is described in
[S1.9](#s19-execute-the-trusted-local-bounded-rust-subset). The bounded UI-to-Wayang-to-worker contract is
[S1.10](#s110-orchestrate-the-bounded-ui-rust-profile). The continuous local orchestration contract is
[S1.11](#s111-continue-complete-topics-on-fixed-rust-workers). These local profiles do not implement the complete
S5/S7 signed fleet protocol.

The bounded JVM qualification in S2.4 implements signed replicated statistics stages,
job-bound conformance, authenticated outbound pulls and both aggregate routes.
Its source cuts are immutable and its expected assignments and group/windows are explicit.
The qualification uses three site instances in one JVM and four independent disposable brokers.
General continuous activation, production release qualification and ownership handover remain separate gates.

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
3. A `duration` is an ISO 8601 duration (`PT1M`). It MUST be above zero unless
   the description permits zero with `min: 0`. Negative durations are rejected.

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

The immutable identity/version inventory is
[`systems/catalogs/tsx-catalog-1.yaml`](../../systems/catalogs/tsx-catalog-1.yaml).
It pins all 16 descriptions to version 1; `dq.plausible-range` supplies the
`dq.checks` DAG alias. Parameters and semantics remain in the descriptions.
`SystemDocTest` checks membership against this table, versions, schema validity,
state, merge declarations, Wayang availability and repository references.
`TsxCatalogBindingsTest` checks the compiled logical and Java execution classes.

`A` means an available native Java entry point. The local compiler of S2.3 translates
these entries with explicit runtime, state, output and resource admission context.
The signed bounded JVM profile admits the statistics boundary of S2.4.
`B target` remains intended
membership; use S1.9 for implemented Rust profiles. The statistics,
segmentation, SAX, and data-quality reference vectors use the existing Java
implementations by `tools/java_operator_vectors.py`.

| DAG `op` | In → out | Parameters | State | Merge | Vectors | A | B target |
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
| `align.vector` | `series`* → `vector` | positive `every`; `key` (`series`, a tag name, `all`), `agg`, `fill`, non-negative `maxAge` and `lateness`, `emit`, `names` ([11](11-SMART-TOPIC-PROZESSOR-DEVOPS.md), 5a) | windowed | `none` | `align-vector.v1.json` | yes | yes |
| `segment.threshold` | `series` → `series` | `direction`, `enter`, `exit` (hysteresis: `exit` not beyond `enter`), `pre`, `post`, `minDuration`, `maxDuration` (`Segmenter.java:92-95`) | keyed (series) | `exact` for segment counts, `sum` | `segment-threshold.v1.json` | yes | yes |
| `segment.extremum` | `series` → `series` | `feature`, `prominence` ≥ 0, `window`, `distance`, `pre`, `post` (`Segmenter.java:69-72`) | keyed (series) | `exact` for segment counts, `sum` | `segment-extremum.v1.json` | yes | yes |
| `sax.word` | `series` → `series` (fills `EpisodeSummary.sax`) | `w` ≥ 1, `a` in the range of `Sax.breakpoints` (`Sax.java:16`) | none | `none` | `sax-word.v1.json` | yes | yes |
| `pair.xcorr` | `series`, `series` → `series` | `lags` integer in [0, 1073741823] (Java array bound); pair settings `pairing`, `window`, `step`, `every`, `agg`, `maxAge`, `minCoverage` (`pairs.ts:78-88`) | windowed | `none` | `pair-xcorr.v1.json` | yes | short windows only (S1.8) |
| `pair.es` | `series`, `series` → `series` | finite `sigma`; `tauMax` ≥ 0 seconds, zero uncapped; `shuffles` integer in [1, 2147483647]; finite integer `seed`; shared pair settings (`es.ts`; `pair-es.v1.json`) | windowed | `none` | `pair-es.v1.json` | yes | no |
| `dq.result` | `stats` → `events` | `checks` ⊆ `DataQuality.Check` values, expectation `min`, `max`, `expectedInterval` (`DataQuality.java:29-38`) | none | `exact` for counts per check and status, `sum` | `dq-result.v1.json` | yes | yes |

The workbench operator `stats.window` maps to `stats.welford-ddsketch` (`ops.ts:905-910`) but has the
parameters `window` (seconds) and `field`, not `windows` and `alpha`. The workbench MUST write the catalog
parameters before a flow with this operator can be deployed.

Catalog pair descriptions use text for `window`: `episode` or a positive
decimal width in seconds, matching workbench text-field serialization.
The direct pair APIs also accept numeric widths. `step` and `every` default
to zero, selecting the window width and inferred sample clock respectively.

`TsxStreamEqualsBatchTest` covers 65 shared numerical cases in chunks of 1,
7 and all input records, in both series orders: 61 native cases and four
explicitly kernel-only ES event-list cases, 390 comparisons in total.
Complete pair/SAX episodes, DQ statistics and event lists remain atomic inputs.
Clean parity compares logical samples because fragment boundaries change
envelope grouping and ids. Pair parity retains deterministic payload fields
and excludes only execution `createdAt`. The wire/identity suites separately
check exact transport bytes and identity vectors under S1.6.

`sax.word` and `dq.result` have descriptions in `systems/processors/`. Their
Wayang Java operators reuse `Sax`, `Paa`, and `DataQuality.evaluate` and are
registered through `TsxRetrievalMappings`.

The native Java API `TsxPair` executes `pair.es` through `JavaTsxPair` and
`TsxPairWindows.es`, using the pair-window rules shared with `pair.xcorr`.
The four sampled-series cases in `pair-es.v1.json` run through Wayang in one
plan and across increments with restored `TsxState`. The four event-list-only
cases remain numerical kernel checks in `TsxPairEsVectorsTest`; they are not
sampled Wayang workloads. Native output has fields `q_total`, `q_ab`, `q_ba`,
and `q_rand`. `tauMax` uses seconds before conversion to aligned clock ticks;
zero means uncapped. Whole-number seeds use their value modulo `2^32` for the
random state, and settings labels retain workbench number formatting.
`TsxPairEsWayangTest.largeSeedsUseTheSameUnsigned32BitStateAsTheWorkbench` and
`settingsLabelsKeepWorkbenchNumberFormatting` cover those boundary cases.
Catalog membership does not extend the local DAG compiler or fleet admission.

### S1.8 Limits on Track B

Track B MUST reject `pair.xcorr` when `(2 * lags + 1) * window / every` exceeds the limit set in the site
configuration, with the message of S2.5. Track B MUST NOT offer `pair.es`.

<a id="s19-execute-the-trusted-local-bounded-rust-subset"></a>

### S1.9 Execute the trusted-local bounded Rust subset

`rust/tsx-runner` version 0.1.0 accepts `opentsx.local-bounded-run.v1` with one bounded Rust assignment,
one complete input topic and one distinct episode output topic on a trusted local broker binding.
It executes pass-through or a linear chain containing `dq.checks` (`PLAUSIBLE_RANGE`, gap or clip),
`convert.unit` and an optional trailing `resample` (`MEAN`, `MIN`, `MAX`, `LAST`). Other operators reject.
Cleaning rejects nonzero revisions, per-value quality, infinite inputs and changes to source series metadata.
Two distinct source series cannot own one derived series, even when their episode extents do not overlap.

The implementation validates the capped cut, computes output and checks checkpoint capacity before publication.
Output Kafka timestamps are epoch milliseconds rounded down from `tStart` and must be greater than zero.
Every output acknowledgement precedes checkpoint commit. Interrupted publication can duplicate records;
same-request replay preserves episode identities and a fixed provenance clock. Exact completed retries return
the stored receipt without broker I/O. An unfinished resample window remains in private Rust state at the cut.
The state stores derived-series ownership and validates it against retained source keys on restore.

The [local guide](../devguide/integration/rust-runner-local.md) defines fields, bounds and failure messages.
The [operator tests](../../rust/tsx-runner/src/operators.rs) replay the eight supported cases of
`clean-ops.v1.json` through every chunk size and checkpoint restore; the seven unsupported cases reject.
The [flow tests](../../rust/tsx-runner/tests/runner_flow.rs) exercise acknowledgement and checkpoint failures.
The three-worker Compose fixture assigns separate topics with one, two and three internal partitions.
That standalone replay fixture does not exercise the remote Wayang/UI path in S1.10 or signed fleet activation.

<a id="s110-orchestrate-the-bounded-ui-rust-profile"></a>

### S1.10 Orchestrate the bounded UI Rust profile

The implemented local orchestration path uses `opentsx.dag.v2`, `opentsx.local-rollout.v1`, the shared assignment
envelope and S1.9 requests. It accepts one to three complete source topics, one episode sink and only the S1.9
cleaning subset. The UI queries target capabilities and submits explicit `flowId` and exact UTF-8 `dagText` through
the authenticated backend. Administrator permission is required. Existing continuous deployment routes are unchanged.

| Boundary | Required local behavior and implementation |
| --- | --- |
| Source identity | `specHash` is SHA-256 of the original UI text. The PostgreSQL journal accepts that exact identity once installation-wide. A retry for the same owner/flow reuses the persisted plan and clock; another owner or flow conflicts. |
| Physical plan | `RustRollout` normalizes catalog parameters, freezes zero-based complete partition cuts, and binds one complete topic to each assigned node. Each fragment has a distinct ID/hash while preserving the original source text/hash. Partition positions do not authorize splitting topic ownership. |
| Runtime admission | Worker node/site, supported operators, exact binary digest, Episode fingerprint, state ABI and explicit caps must match. Placement is static by topic order and sorted worker ID, with no partition fallback or cost-based placement claim. |
| Wayang execution | Logical topic sources, optional `UnionAllOperator`, optional fused clean chain and sink map through `RustPlugin` to one connected `RustPlatform` stage. `RustExecutor` invokes the stage action once. Topology channels cannot materialize raw records and unsupported mappings have no Java fallback. |
| Durable control | The backend persists original source, physical plan/hash, accepted clock and whole-topic ownership before execution. The standalone coordinator owns a PostgreSQL advisory lock and reconciles the same persisted plan. The Java driver has no second deployment journal; its separate S1.12 publication ledger enforces Kafka write authority. |
| Prepare | Every worker fully reads and computes the capped cut privately. All preparations must succeed and input/output series ownership must be disjoint across topics before activation. |
| Activate and recover | Fenced workers publish prepared output through the S1.12 gateway and commit the completed checkpoint only after its verified publication receipt. Exact completed retry returns the receipt. `activationRetryable=true` retains saved candidate bytes and request identity; the coordinator retains running state and topic ownership until identical retry is reconciled. |
| Completion | Completion requires verified receipts from every assigned worker. Partial activation can expose at-least-once output; there is no atomic multi-worker publication or transactional rollback. Open resample tails remain in retained state. |
| Local transport | HTTP workers and the driver are internal trusted Compose services. Normal application login remains enabled and `TEST_LOGIN` remains false. The S1.12 local gateway, controller-peer gate, segregated proxies and broker ACL enforce write authority. They do not implement signed S7 fleet activation or transport authentication for a production fleet. |

The [local guide](../devguide/integration/wayang-rust-compose.md) records helper commands and evidence locations.
`RustPlatformTest` exercises real Wayang stage selection without network transport; `RustRolloutTest` checks planning,
private preparation, ownership refusal and uncertain activation. The browser/Compose helper is a separate acceptance
check over three topics with one, two and three internal partitions, using actual Java-derived output and receipts.
Its initial, exact-retry and restart checks require the same twelve output records. A generated result is evidence of
that run, not a throughput benchmark or production qualification. S1.11 adds local continuous input. Dynamic failover,
broad kernels, operational quotas, signed rollout and Iceberg publication remain separate gates.

<a id="s111-continue-complete-topics-on-fixed-rust-workers"></a>

### S1.11 Continue complete topics on fixed Rust workers

The local continuous profile uses the S1.9 operator subset and S1.10 whole-topic stage. It preserves one deployment,
exact source text/hash, accepted clock, fragment text, binary identity, worker placement, channel inventory and limits.
Each finite increment includes every internal partition of each topic. No partition position is a deployment unit.

| Boundary | Exact local contract |
| --- | --- |
| Backend admission | `POST /api/v1/dags/wayang-rust/deploy` accepts optional `profile`, either `bounded` (default) or `continuous`. The same source identity cannot be accepted under another profile, owner or flow. |
| Initial plan | `POST /v1/plan` accepts `deployment`, `dagText`, `createdAtMicros` and optional `profile: continuous`. A continuous plan has schema `opentsx.local-continuous-rollout.v1`, the existing plan fields, `sequence: "1"` and `previousPlanHash: null`. |
| Successor plan | `POST /v1/next` accepts exactly `plan` and its completed `result`. It returns exactly `{state: idle, plan: null}` or `{state: ready, plan: <object>}`. The new sequence is the previous sequence plus one; `previousPlanHash` identifies the preceding plan. |
| Finite cut | Each `from` equals the preceding verified `next`. Broker beginning must be at or before that cursor, and end must be at or after it. Partition and output counts stay fixed. All topics remain in every non-idle wave, including empty spans. |
| Record budget | Per topic, use `min(records, inputBytes / payloadBytes, samples / episodeSamples)` with integer division and rotating partition priority. The current limits admit four records per topic per cycle. Payload, output and retained-state caps still apply independently. |
| Worker request | `opentsx.local-increment.v1` contains the six S1.9 request fields plus `sequence` and `previousRequestHash`. Sequence is a positive canonical signed-64-bit decimal string. Predecessor is null at sequence one, otherwise the previous completed request's 64-character lowercase SHA-256 hash. |
| Assignment envelope | The shared execution assignment remains `mode: bounded`, since each cut is finite. Assignment, deployment, generation, attempt, fence and fragment identities remain unchanged; only complete partition positions advance. The standalone replay command refuses increment requests. |
| Worker continuity | Sequence one starts every partition at zero. A successor names the current completed head and starts at its complete receipt positions. State is restored locally; caller state and paths are forbidden. Stale, forked, skipped and changed-context successors fail before reads. |
| Cumulative ownership | Retain every decoded input identity and its internal partition, including unselected/pass-through input, plus all emitted and pending output identities. Never evict ownership to admit another series. Lifetime series and retained-state caps apply. Every topic prepares before cross-topic checks and activation. |
| Preparation recovery | A transient prepare failure retains the exact plan and every private candidate for identical retry. This includes a response lost after the worker persisted its preparation. No activation occurs until every preparation and cross-topic ownership check succeeds. Permanent refusals and ownership conflicts retain their failure path. |
| Worker status | Increment status adds `sequence` and `previousRequestHash`; the first predecessor is JSON null. Existing receipt record counts remain per increment. `activationRetryable` keeps uncertain saved output under reconciliation. |
| Bounded history | Each worker retains one immutable completed head and at most one candidate in `increments/checkpoint.json`. Latest exact retry returns its receipt. PostgreSQL retains the latest two completed cycle rows plus pending work, with cumulative progress stored separately. Idle creates no increment. |
| Public progress | Continuous views add `profile`, `currentCycle`, `completedCycles`, `stopRequested`, `inputRecords` and `outputRecords`. Each assignment adds `cumulativeInputRecords`, `cumulativeOutputRecords` and latest completed `positions`. Counters, sequences and offsets are canonical decimal strings. |
| Stop | The authenticated owner stop route records durable intent. Re-read it after planning and execution RPCs. Cancel undispatched work; reconcile dispatched work before entering `stopped` and releasing topic ownership. Unknown publication retains `stopping` and ownership. Stop does not flush a resample tail. |

Preparation refusals with HTTP status `400`, `409`, `413` or `422` are conclusive. Other preparation exceptions remain
unresolved. If any preparation remains unresolved, retain all candidates even when another worker has a conclusive
refusal. Resolve the missing outcome before cleanup; a successful retry still validates every status and series owner.

The worker advertises `incrementalRequestSchema: opentsx.local-increment.v1` and `limits.incrementalChains: 1`.
One worker retains one completed continuous chain after Stop. S1.12 permits explicit same-chain resume or
changed-DAG reset into a distinct fresh output after authority revocation. There is no implicit reset, failover or
migration. Open resample windows retain the existing maximum of 100,000 valid samples. Output is at least once
and activation is not atomic across workers.

`RustContinuousRolloutTest`, `tests/increments.rs`, backend continuous tests and the
[continuous Compose acceptance](../devguide/integration/wayang-rust-continuous.md) cover finite successors,
cumulative ownership, idle, exact retry, stateful service restart, single-worker downtime, queued new data and Stop.
Planner regressions distinguish lost preparation responses, unresolved/permanent refusal combinations and ownership
collisions after recovery. S1.12 adds local publication fencing. Broker restart, topic-incarnation qualification, high-availability
publication recovery and the signed S5/S7 fleet contract remain separate gates.
The existing cleaning policy skips a block whose first event timestamp precedes the retained series end, increments
the skipped-block counter and advances its input cursor. Continuous execution does not introduce late-data revisions.

<a id="s112-fence-local-publication-and-replace-a-continuous-deployment"></a>

### S1.12 Fence local publication and replace a continuous deployment

The implemented local profile uses `RustPublicationGateway` as the single output authority. Its scope is the
S1.9 cleaning subset on one to three fixed workers. It does not change numerical kernels, assignment schema,
whole-topic ownership or the signed fleet requirements in S5/S7.

| Boundary | Exact local contract |
| --- | --- |
| Transport | All OpenTSx producers, including fixtures, MUST use Apache Kafka Broker or KafScale Proxy. Workers read through a fetch-only Proxy. The driver and host fixture use the write Proxy; the host and Docker control plane remain trusted. No worker has a broker, write-network or published broker route. Separate non-internal bridges contain only the write Proxy and frontend, respectively, for loopback host publication; they do not share a host bridge. |
| Local fixture source | The single-broker fixture builds pinned KafScale v1.6.0 with the guarded `tools/kafscale_metadata_overlay.py` build overlay. One store-local mutex serializes `CreateTopic`, `CreatePartitions`, `DeleteTopic` and watcher `refreshSnapshot`. Preserve the original source checksum and record overlay/binary provenance. This is a local source overlay, with no multi-broker metadata qualification. |
| Local Proxy protocol | The guarded `tools/kafscale_proxy_protocol_overlay.py` verifies the pinned v1.6.0 Proxy source checksum, removes duplicate frame-length prefixes from re-encoded Produce/Fetch requests and uses protocol constructors for synthetic error-partition defaults. Successful fan-out keeps its backend eligible for subsequent requests. The builder runs its protocol regressions against the overlaid upstream `cmd/proxy` package. Record this overlay and its tests in bundle provenance; the fixture is not an unmodified upstream binary. |
| Broker authorization | Use `remote_addr` principal identity and fixed broker-facing read/write Proxy addresses, default deny and fail-closed ACL loading. Read grants permit topic `fetch`; write grants permit required publication/provisioning. Disable automatic topic creation. A forged `client.id` does not confer write authority. |
| Controller isolation | Fenced `serve` requires `--publisher` and `--controller` together. Worker mutation routes admit only the configured controller's actual TCP peer; forwarded headers are ignored. Workers drop all Linux capabilities, use read-only roots and `no-new-privileges`. |
| Driver interfaces | Bind control and publication HTTP to separate private interfaces. Control requires the existing `X-OpenTSx-Run-Token`; publication requires the captured per-node grant. The backend uses private `DAG_RUN_TOKEN`. No credential appears in DAGs, assignments or public status. |
| Authority identity | `publicationEpoch` is a positive canonical signed-64-bit decimal string separate from immutable assignment `generation` and `fence`. Rotate the whole admitted input-topic group and sink together. Preserve retired topic epochs and refuse reopening a retired deployment identity. |
| Private grant | `opentsx.publication-grant.v1` contains exactly `schema`, `operation`, `deployment`, `epoch`, `node`, `inputTopic`, `outputTopic` and `token`. The token is random 32-byte authority encoded as 64 lowercase hex characters. Each prepared journal captures its grant once. |
| Worker installation | `POST /v1/publication` accepts exactly `grant`, `reset` and `expectedHead`. Require the matching node, a larger epoch, no pending candidate and the expected completed request hash. Identical operation/content is idempotent. Resume preserves the head; reset additionally requires the same whole input, distinct deployment/output and a completed old head. |
| Registration | After every worker prepares and ownership checks succeed, register the exact request text/hash, epoch, node, topic binding, output digest and output count. An unregistered or changed request cannot publish. |
| Publication request | `POST /v1/publish` requires `Authorization: Bearer` with the captured token and exactly `epoch`, `requestText`, `records`. Each ordered record has string `key`, strictly positive signed-64-bit millisecond `timestamp` and byte-array `payload`. Validate admitted count, payload and total-byte limits before Kafka I/O. |
| Digest | SHA-256 covers each ordered record as unsigned-64-bit big-endian UTF-8 key length, key bytes, signed-64-bit big-endian timestamp, unsigned-64-bit big-endian payload length, then payload bytes. |
| Publication receipt | `opentsx.publication-receipt.v1` contains `publicationEpoch`, `requestHash`, `outputDigest` and `outputRecords` alongside `schema`. Epoch and record count are canonical decimal strings. The worker verifies every field before committing its head. |
| Serialization | The configured single-chain pool serializes execute, replace and retire control operations, including fresh-worker preflight and admission. A separate gateway lock spans authority validation, durable send intent, all Kafka acknowledgements and durable completion. Rotation uses that gateway lock. Persist `publishing` before send and `acknowledged` before replying. Kafka retries and idempotent/transactional producers do not provide this fence. |
| Ambiguity | A send error or interruption can leave an unknown broker outcome. Retain `publishing`/`uncertain`, refuse retransmission and block replacement/retirement for that group. A lost HTTP reply after durable `acknowledged` returns the cached receipt without Kafka I/O. Never infer absence from a missing reply. |
| Ledger | Use one checksummed atomic `opentsx.publication-ledger.v1` envelope, fsynced file/directory updates and an exclusive process lock. Directories are `0700`; private files are `0600`. Corruption, missing initialized state, a second owner or storage failure rejects work. Reuse the same private directory after restart. |
| Capacity | Retain at most 64 deployment groups, 64 operation receipts and 256 request entries. The serialized ledger envelope is at most 4,194,304 bytes. Driver control HTTP bodies are capped at 1,048,576 bytes, independently of the 32,768-byte original DAG limit. The publication HTTP body is separately capped at 85,000,000 bytes. Each active group reserves one operation slot for retirement: recorded operations plus active groups MUST NOT exceed 64. Admission/switching preserve that reserve. Capacity is fail-closed, not an eviction or tenant-quota policy. |

The authenticated owner route `POST /api/v1/dags/wayang-rust/deployments/{id}/replace` accepts a canonical
`expectedEpoch` and `mode`, either `resume` or `restart`. Resume accepts no replacement flow/DAG fields.
Restart requires `flowId` and exact `dagText`, verifies flow ownership, changed source hash, unchanged complete
input-topic placement and a distinct fresh output. At least one increment must be completed. Persist switch intent
before remote calls, drain dispatched work and suspend successor planning without releasing topic ownership.

The internal `POST /v1/switch` body is exactly `operation`, `mode`, `expectedEpoch`, `plan`, `result` and
`replacement`. Verify the last completed old boundary. For restart, require a continuous sequence-one replacement
with zero `from` positions and an empty sink. Atomically retire old grants and persist the larger group epoch before
installing worker authority. An installation failure MUST NOT reactivate the old epoch. Repeat the same operation
for reconciliation; conflicting content under its operation identity rejects.

Resume retains deployment/source/clock, state and next positions, including an open resample window. Restart creates
a distinct deployment with changed DAG/hash and its own clock, resets worker heads after revocation and replays every
partition from zero into a fresh output. Keep old output intact. Transfer input-topic ownership only after successful
revocation and installation. Stop revokes before releasing ownership. Unknown publication retains ownership.
Stop is terminal for the retained worker chain and does not make the single-chain pool reusable for an unrelated
deployment; same-DAG resume or changed-DAG restart requires the active completed deployment boundary.

Capabilities expose `publicationFencing` and `switchModes`; public views expose `publicationEpoch` and switch
progress. Public prepared status may expose epoch, prepared digest and count, never the grant token.

The gateway, worker, backend, topology and Compose checks are separate evidence boundaries. A stale-owner acceptance
case MUST attempt activation of an old prepared candidate with its captured old grant after rotation, observe
publication denial and compare actual output end offsets before/after. A completed-head retry is insufficient.
Also exercise read-Proxy Produce with forged client identity and direct broker, write-Proxy and host-gateway bypasses.
This local cap does not establish signed fleet enrollment, gateway high availability, automatic failover,
backup/restore reconciliation, cross-runtime state migration, topic-incarnation handling or durable Iceberg publication.

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
5. Catalog: every `op` belongs to the job catalog. Check runtime, state and resource support at every node
   assigned that operator or fragment. A replicated fragment checks every replica; unrelated fragments need not
   support operators they do not execute.
6. Parameters (S1.3).
7. Topics: every source and sink topic follows `tsx.<domain>.<kind>.v<n>` of the job's domain (S3.1).
8. Federation: validate the value crossing each site boundary and its ownership dependencies. A local cleaning
   chain may precede mergeable statistics. Cross-site raw pairing or alignment requires an explicitly admitted
   movement contract. Federated statistics use tumbling windows and the sink kind is `aggregates` or `signals`.

### S2.3 Wayang plan

1. Each DAG node MUST become its native Wayang logical operator (package
   `org.apache.wayang.core.plan.wayangplan`, tag `wayang-1.1.1`). Tagged unions adapt the named inputs of the
   unary `TsxAlignVector` and `TsxPair` operators. A single pair input uses unordered distinct series pairs.
   Internal tagging, union and output filters preserve the original node identity.
   Exception: a run of consecutive single-input nodes whose operators are all in `CleanChain.OPS`
   (`CleanChain.java:35`) MUST become one operator (`TsxSeriesChain`). These steps share one state per series, and the
   written series gets its key and episode ids once, after the last step (`SeriesStream.java`); separate operators
   would key and label every intermediate series. The state of the run is stored under the id of its last node.
2. `decode.avro-soe` with the DAG `source` MUST become one `TsxTopicSource` per source topic. Each sink MUST become
   one typed sink: `TsxVectorSink` for vectors and `TsxTopicSink` for supported Avro and signal records.
3. The driver MUST NOT use `wayang-api-json`: it compiles Scala source received in the request
   (`SerializableLambda.scala:23-29` at Wayang `main`).
4. On a single site the plan runs with `TsxJavaPlugin`. The plugin supplies the
   Java platform, mappings, and channel conversions without the cloud mappings
   whose SDKs are excluded (`TsxJavaPlugin.java`).

### S2.4 Site plan

For a federated job the driver MUST identify complete executable fragments and their explicit assignments.
Replicating the same local stage at independent sites is one supported physical shape, not a general placement
algorithm. Each assignment owns whole topics, with complete internal partition positions, runtime/node identity,
generation, channel bindings and
resource/capability admission before activation. Central merge is an explicit downstream fragment.

Preserve `specHash` over the original submitted UI text. Carry exact source text separately from compiler-produced
fragment text and its `fragmentHash`; placement or slicing MUST NOT redefine the legacy output identity.
The shared assignment schema implements the identity and whole-topic boundary. The bounded JVM wrapper
embeds that unchanged assignment as exact text. A signature alone is not proof of exclusive live authority.
This profile has no automatic takeover, rescaling or continuous ownership handover.

The site plan is an `opentsx.runner-plan.v1` document ([schema](schemas/opentsx.runner-plan.v1.schema.json)):

| Field | Rule |
|---|---|
| `job` | unique per deployment; `[a-z0-9][a-z0-9-]{0,62}` |
| `planVersion` | integer in `[1, 2147483647]`; equals the assignment's decimal-string `generation` |
| `issued`, `expires` | RFC 3339 UTC; `expires` after `issued` |
| `site` | the receiving site id |
| `catalog` | catalog version, for example `tsx-catalog/1` |
| `specHash` | as defined above, of the original submitted DAG text; a fragment has its own `fragmentHash` |
| `assignmentText` | exact bounded Java assignment text; wrapper identities must match |
| `inputContracts[]` | one `(binding, topic)` per whole input, with allowed writer fingerprints |
| `outputs[]` | exactly one SeriesStats output, with its existing DAG sink identity and full writer contract |
| `federation.route` | `signal-cluster` or `job-state` |
| `federation.expectedAssignments[]` | exact admitted assignment membership; one aggregate-producing member per site |
| `federation.scopes[]` | at most 1024 unique admitted windows on the epoch grid |
| `federation.groupBy`, `federation.maxDelay` | restricted tag names and explicit ISO 8601 duration |

`assignmentText` contains exact `opentsx.execution-assignment.v1` JSON text, with runtime `java` and mode `bounded`.
Input fingerprints are 16-character lower-case hexadecimal strings. An output names `sink`, `cluster`, `topic`,
decimal-string `partitionCount` and the actual writer fingerprint. Its `sink` is the existing compiled DAG identity.
Expected membership binds exact `assignment`, `site`, `node`, `generation`, `fence` and `fragmentHash` values.
Each scope contains `{groupId, windowType, startMicros, endMicros}`; micros are canonical signed int64 decimal strings.

The fragment's single sink MUST consume `stats.welford-ddsketch`. Local compilation remains a separate API
for the complete catalog. Source fingerprints and explicit cleaning targets must match the actual input;
an unbound target is not invented from a Workbench label.
All declared scopes receive a partial, including known empty scopes. Scope membership is not inferred from samples.
The wrapper payload is limited to 128 KiB and the compact JWS to 176 KiB.
Inputs have distinct topic names in this profile; local configuration resolves their binding IDs and the
`site`/`signal` output aliases. Plans carry no broker addresses, credentials or local paths.
The assignment input-topic set MUST equal the fragment's nonempty, distinct
`source.topics` set. Missing or unused sources are refused with `plan.invalid`
before admission.

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
   (`TopicLayout.java:204-206`) and the JSON kinds `dags.v1`, `dag-status.v1`, `smart.<name>.vectors.v1`
   (`TopicLayout.java:111-140`).
2. `TopicLayout.aggregates()`, `signals()` and `repartition(dag, node)` provide:
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

1. The site runs its conformance checks and produces an `opentsx.site-conformance.v1` report
   ([schema](schemas/opentsx.site-conformance.v1.schema.json)). The bounded JVM qualification executes
   `SiteConformanceChecks` over the repository vectors and original required KafScale checks.
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

The bounded JVM report binds `job`, `planVersion`, `generation`, `node`, the admission-contract hash,
catalog, artifact/state ABI and its validity interval. It requires all ten checks exactly once with details,
unique series IDs and a matching count. Structured topic rows use `(role, binding, topic)` and actual partition
counts; the driver compares the exact union of admitted inputs and outputs. Input fingerprints are observed
on the broker; output fingerprints identify the configured encoder, including an empty target.
A locally configured qualified digest identifies the laboratory runtime. That pin is not production
release-signature evidence, and the laboratory acceptance does not claim the production condition above.

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

Kafka key: `<job>|<site>|<group series id>|<windowType>|<windowStart micros>` for partials,
`<job>|<group series id>|<windowType>|<windowStart micros>` for merged records.
The window type distinguishes widths that start at the same instant.
Headers (same convention as `tsx-schema`, `TopicLayout.java:23`):

| Header | Value |
|---|---|
| `tsx-schema` | `org.opentsx.model.v2.SeriesStats` |
| `tsx-job`, `tsx-site` | job id; site id (partials only) |
| `tsx-plan-version` | decimal `planVersion` |
| `tsx-revision` | decimal revision, 0 first |
| `tsx-final` | `true` or `false` (merged records only) |
| `tsx-missing-sites` | comma-separated site ids, empty when final (merged records only) |

Bounded partials also carry `tsx-generation`, `tsx-assignment`, `tsx-fence`, `tsx-spec-hash`,
`tsx-fragment-hash` and `tsx-evidence`. The last is an
[`opentsx.aggregate-evidence.v1`](schemas/opentsx.aggregate-evidence.v1.schema.json) JSON document with
kind `bounded-source-cut`, exact admitted whole-topic cuts and SHA-256 of the raw SOE payload.
It binds the partial to the signed generation, member and scope inventory.
It attests completed consumption/computation of that immutable cut, not prior checkpoint persistence.
Partial and evidence are sent together; output acknowledgements precede the checkpoint.

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

1. **Identity.** The zero-count accumulator is the internal identity. Serialized empty windows preserve
   `StatsAccumulator.toSeriesStats`: NaN extrema and moments, an empty sketch and null `lastSeen`.
2. **Associativity and commutativity.** Counts, `min`, `max`, `lastSeen` and uncollapsed sketch bin maps are exactly
   associative and commutative within their checked numeric bounds (integer addition, min, max). Bounded-bin
   collapse needs separate qualification. `mean` and `m2` are associative and commutative in real
   arithmetic only. In IEEE 754 doubles they depend on the order.
3. **Canonical order.** The merge MUST fold the partials in ascending order of site id, starting from the identity.
   Two merges of the same partials then give bit-identical results.
4. **Equality with the centralized result.** The merged counts, `min` and `max` MUST equal those of one
   `StatsAccumulator` over all values. Sketch-byte equality requires one runtime and no bin collapse at a site
   or merge; collapsed-tail behavior needs separate acceptance vectors. `mean` and `m2` MUST be within the
   tolerance of the job (decision D-T2 in TASK-020: relative 3e-12 for the bounded W3 qualification). This measured
   qualification does not establish that bound for arbitrary data or mixed runtimes. Quantiles from the merged sketch MUST
   equal those of the centralized sketch in that one-runtime/no-collapse domain. The relative error `alpha`
   comparison with the exact quantile is
   qualified to nonzero magnitudes outside the zero bucket and without bounded-bin collapse; fixtures must
   separately cover the zero bucket and collapsed tails of `QuantileSketch`
   ([Masson, Rim, Lee 2019, doi:10.14778/3352063.3352135](https://doi.org/10.14778/3352063.3352135)).
5. **Mixed runtimes.** When sites run different runtimes, a value on a DDSketch bin boundary MAY fall into
   neighbouring bins (Java `Math.log` is within 1 ulp,
   [Java SE 17](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/Math.html#log(double))).
   Rule 4 then holds for counts, `min`, `max` and the qualified `alpha` bound, not for sketch bytes.

### S4.4 Routes

**Signal cluster (route `signal-cluster`).**

1. The site stage writes partials to `tsx.<domain>.aggregates.v2` and signals to `tsx.<domain>.signals.v1` on the
   site cluster.
2. A forwarder copies them to the same topic names on the signal cluster, from its own checkpoint, keys and headers
   unchanged.
3. The central merge reads `tsx.<domain>.aggregates.v2` of the signal cluster and writes merged records to
   `tsx.<domain>.series-stats.v2` of the signal cluster (`TopicLayout.seriesStats()`, `TopicLayout.java:58`).

**Job state (route `job-state`).** For bounded jobs only. The site agent sends its partials to the driver
(S5.3, `POST /v1/sites/<site>/jobs/<job>/aggregates`) as an
[`opentsx.aggregate-delivery.v1`](schemas/opentsx.aggregate-delivery.v1.schema.json) object.
Every entry has the same key, complete text-header set and base64-encoded raw SOE bytes as route S.
The limit is 256 records and 16 MiB per delivery, 1 MiB per record and 128 KiB per evidence document.
The driver validates the same signed membership, scope, generation and cut proof for both routes.
It merges with `stats.chan-ddsketch` into the Wayang plan's `AggregateChannel` and continues on Java.

### S4.5 Completeness

1. The bounded profile derives expected sites from its signed `federation.expectedAssignments`.
   The signed scopes declare every group/window, including scopes missing from all sites.
   `federation.maxDelay` is an explicit ISO 8601 duration.
2. A merged window is **final** only when every expected assignment supplies admitted closed-window or source-cut
   evidence for the generation. An open-window partial from every site is insufficient. The merge then writes
   `tsx-final: true`. The bounded profile validates the evidence and membership of S4.2.
   Open continuous snapshots are not closed evidence; their admission remains a separate U09 gate.
3. When the signal cluster's clock passes `windowEnd + maxDelay` and a site is missing, the merge MUST write the
   window with `tsx-final: false` and `tsx-missing-sites` set.
4. A partial that arrives later MUST produce a new merged record with `tsx-revision` raised by 1. The merge MUST
   NOT change an earlier record.
5. A byte-identical duplicate partial (same key, same revision and generation) MUST be ignored. Conflicting content
   under that identity MUST be refused and surfaced; stale generations MUST be refused.
6. Removing a site from a job MUST go through a new `planVersion`. Windows before the change keep their sites.

The merge persists its revised records in an outbox before publishing. Acknowledged publication removes an
outbox entry. Restart replays an unacknowledged entry with identical identity and bytes.

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

The implemented bounded profile uses `TsxSiteAgent.accept`/`pollOnce`, `TsxBoundedSiteExecution` and
`TsxSitePlatform`/`TsxSiteExecutor`. One accepted immutable cut is a bounded increment.
The executor checks actual retained partition bounds and uses `auto.offset.reset=none`; it cannot widen a
signed cut after retention. It commits the bound private checkpoint only after acknowledged publication.
The general continuous scheduling rules above are separate from this bounded qualification.

### S5.2 Track B runner

`tsx-runner` MUST offer:

| Mode | Behaviour |
|---|---|
| `agent` | runs the pull protocol of S5.3; supervises assigned whole topics per job; partitions remain internal cursor metadata |
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
| `GET /v1/sites/<site>/conformance-challenge?job=<job>` | none | job-bound contract and hash |
| `POST /v1/sites/<site>/conformance` | compact JWS of an `opentsx.site-conformance.v1` report | `200 {"result":"accepted"}`, or `200 {"result":"refused","check":"<id>","message":"<text>"}` |
| `GET /v1/sites/<site>/plans?after=<deliverySequence>` | none | delivery page below; at most one entry |
| `POST /v1/sites/<site>/deliveries/<sequence>/ack` | JSON acknowledgement | `204`; idempotent |
| `POST /v1/sites/<site>/jobs/<job>/status` | an `opentsx.dag-status.v1` record (S5.4) | `204` |
| `POST /v1/sites/<site>/jobs/<job>/aggregates` | `opentsx.aggregate-delivery.v1` | `204` after callback |
| `POST /v1/sites/<site>/jobs/<job>/refusal` | `{"planVersion":<n>,"code":"<code>","message":"<text>"}` | `204` |

The challenge binds the job's input, output, member and scope contract and its hash.
A delivery page has this shape, where `<n>` is a sequence, `<job>` the job identity and `<compact JWS>` the signed plan:

```json
{
  "deliveries": [
    {"sequence": "<n>", "job": "<job>", "planVersion": 1, "plan": "<compact JWS>"}
  ],
  "next": "<n>"
}
```

An acknowledgement contains `job`, `planVersion`, SHA-256 `commandHash` and `result` (`accepted` or `refused`).
Refusals also contain `code` and `message`. An identical acknowledgement is idempotent.
Aggregate delivery preserves the route-S metadata and SOE bytes; `204` follows aggregate callback acknowledgement.

Errors: `400 {"error":"bad-request","message":"<text>"}`, `401` when the client certificate is not a known site,
`403` when `<site>` in the path differs from the certificate's site, `404` for an unknown job, `409` when a status or
aggregate names an older `planVersion` than the current one.

Delivery sequences use canonical int64 decimal strings. A job at version 10 MUST NOT hide another job at version 1.
Repeated delivery of identical accepted bytes is idempotent; changed bytes under the same command identity reject.
The service persists deliveries before returning them. The site persists its outcome before acknowledgement
and advances its cursor after acknowledgement. Before requesting another
delivery, it retries any retained acknowledgement beyond that cursor and returns
the saved outcome, even after conformance expires. This recovery covers an
unsent acknowledgement and a lost response after the service confirmed it. The
acknowledgement still requires the original site identity and exact command
hash. New activation requires valid job-bound conformance.

The agent SHOULD poll `GET .../plans` every 30 s and MUST back off to at most once per 5 min while the driver is
unreachable.

### S5.4 Control and status topics

1. Control stays as today: `opentsx.dag-control.v1` on `tsx.<domain>.dags.v1`, key = DAG name, the latest record
   counts (`TopicLayout.java:134-140`).
2. Status stays `opentsx.dag-status.v1` on `tsx.<domain>.dag-status.v1` with its fields (`schema`, `dag`, `state`,
   `specHash`, `records`, `watermark`, `waitingFor`, `skippedBlocks`, `error`, `at`; `VectorProcessor.java:298`,
   `:620`). This specification adds the optional fields `site`, `job`, `planVersion`, `track` (`A` or `B`) and
   `artifact` (version and digest). Readers MUST ignore fields they do not know.
3. A runtime MUST write a status record on every state change and at least every 30 s, as the processor does today.

### S5.5 Refusal codes

| Code | When |
|---|---|
| `plan.signature` | signature invalid or signed by an unknown key |
| `plan.expired` | new activation arrives after `expires`; accepted identical replay returns its recorded outcome |
| `plan.version` | an older plan version, or changed content for an already accepted version; identical delivery returns its recorded outcome |
| `plan.site` | `site` is not this site |
| `plan.catalog` | catalog version or an operator version not available |
| `plan.invalid` | any message of S2.5; the message is passed on |
| `plan.policy` | an output cluster or kind not allowed by the site policy (S7.4) |
| `plan.busy` | another live assignment owns the same `(fragment, binding, topic)`, regardless of internal positions |

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
| pair result episode | `"p" + hex(xxHash64(seriesId + "|" + episodeId of A + "|" + episodeId of B + "|" + specHash))`, as `JavaTsxPair.episodes` |
| partial aggregate | Kafka key of S4.2 plus `tsx-revision` |
| merged aggregate | Kafka key of S4.2 plus `tsx-revision` |
| signal | `id` of `opentsx.signal.v1`: `hex(xxHash64(job + "|" + site + "|" + seriesId + "|" + kind + "|" + at))` |

Segment episodes keep the deterministic ULID of
`StreamingSegmenter.materialize`: start time plus xxHash64 of series id,
strategy, sorted parameters, and start time. The shared segment vectors
preserve these identities.

For `kind: dq`, the hash input is
`job|site|seriesId|dq|at|check|window.from|window.to`. DQ identities include the
check and window bounds so that distinct checks and overlapping window widths
remain distinct findings. Hash the UTF-8 bytes of the strings exactly as
written in the signal JSON, including ISO timestamps.

Readers MUST keep one record per id; for aggregates the highest revision per key.

Operators that feed a sink MUST be deterministic (`semantics.deterministic: true`). `pair.es` surrogates are
deterministic through the `seed` parameter (`Mulberry32`).

### S6.3 Checkpoints

1. One checkpoint per task: the positions per input partition, the state of every stateful operator, `specHash`,
   catalog version and `planVersion`.
2. A runtime MUST write the checkpoint only after the broker acknowledged every record sent before it.
3. A runtime MUST write it to a temporary file, flush it to disk and rename it over the previous one.
4. On start, a runtime MUST fail closed on an incompatible `specHash`, catalog, state ABI or continuation identity.
   It MUST NOT restart from `start` silently. Reset requires an explicitly admitted new generation; migration
   requires a qualified state transition. Operator parity does not imply cross-runtime checkpoint compatibility.
5. An unreadable checkpoint MUST put the task in state `failed`. The runtime MUST NOT start over by itself.
6. The checkpoint format is private to a runtime; it does not travel between tracks.

The bounded signed JVM profile stores `opentsx.site-checkpoint.v1`: exact continuation identity,
completed positions, operator state and recorded receipt. Identity includes the compact-command hash,
catalog/state ABI, artifact, source/fragment hashes, member, generation/fence and immutable cuts.
The admission intent is durable before execution; a public unsigned parse cannot authorize checkpoint access.
The earlier local `TsxCheckpoint.load` helper is not an admission boundary for this profile.

### S6.4 Reading

1. A runtime MUST use manual partition assignment and seek for every partition of each assigned whole topic;
   it MUST NOT join a consumer group. Partition assignment is internal broker I/O, not cross-node placement.
2. After a refused fetch it MUST reset to the positions before the fetch and retry, as `Patience.poll` does
   (`Patience.java:75`).
3. A start by time MUST go through the episode index; `offsetsForTimes` MUST NOT be used.

## S7. Security

### S7.1 Signed plans and reports

1. Site plans and conformance reports MUST be signed as compact JWS ([RFC 7515](https://www.rfc-editor.org/rfc/rfc7515.html))
   with `alg` `EdDSA` (Ed25519) or `ES256`. Other algorithms and `none` MUST be refused.
2. The payload is the document's JSON. The signature covers it as sent; no canonicalization is applied.
3. A site MUST pin the driver's public key in its configuration. The driver MUST pin each site's public key.
4. A site MUST refuse an older plan version or an expired new activation. Identical delivery of an already
   accepted version MUST return its recorded outcome; different bytes for that version MUST conflict.
   Restart of a persisted active assignment follows its grant and recovery policy, not fresh activation.
   Rollback and freeze protection follow
   [TUF](https://theupdateframework.github.io/specification/latest/).

The bounded JVM profile selects the owner-approved Ed25519 algorithm with the exact protected header
`{"alg":"EdDSA"}`. It rejects padding, noncanonical base64url, extra headers and unsigned algorithms.
The JWS algorithm binding and signing-input rules follow
[RFC 8037 section 3.1](https://www.rfc-editor.org/rfc/rfc8037.html#section-3.1).
Driver and site role keys are pinned separately. It does not implement TUF metadata, key rotation or
production release enrollment.

### S7.2 Transport

1. The plan service API (S5.3) MUST use TLS 1.3 with client certificates.
   The bounded Java service calls `setNeedClientAuth(true)` using the
   [Java 17 SSLParameters API](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/javax/net/ssl/SSLParameters.html#setNeedClientAuth(boolean)).
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
