# Learn and verify the implemented operators

Use the versioned operator lessons with the implemented UI/DAG operators and public library algorithms;
the runtime entry points and executable checks are recorded in the learning metadata for this checkout.

You need a matching OpenTSx checkout and student-material checkout, the dependencies of the runtime used by
your lesson, and synthetic input data. UI workloads use the existing frontend test setup; Java workloads use
JDK 17 or 21 and Maven; Python workloads use the project's installed package and pytest.

## Choose the actual execution surface

Read the lesson's source, manual, SPEC and test references before choosing its runtime.

| Surface | Meaning |
| --- | --- |
| UI operator | An executable `OPS` entry in the workbench. Membership in an older streaming catalog is a separate property. |
| Java or Python library | A public numerical entry point. A library implementation alone does not supply a DAG binding or a Wayang mapping. |
| Processor DAG | An operator accepted by the current series/vector processor, with that runtime's parameters and state rules. |
| Wayang | An implemented mapping or infrastructure entry point, separately identified by the lesson. A pure Java kernel alone does not establish a mapping. |
| Rust | `tsx-contracts` validates offline assignments. `tsx-runner` 0.1.0 executes trusted-local pass-through and three cleaning operations. Explicit bounded and continuous UI targets use a custom Wayang stage and one to three whole-topic HTTP workers; signed production fleet execution remains separate. |

The [UI contracts](../learning/ui-operator-contracts.md) and
[library contracts](../learning/library-operator-contracts.md) identify the actual algorithm behavior.
The [unified Wayang plan](../devguide/integration/wayang-unified-plan.md) records deployment work and its gates.
For Rust execution, use the [bounded local runner guide](../devguide/integration/rust-runner-local.md).
It accepts `dq.checks` with `PLAUSIBLE_RANGE`, `convert.unit` and an optional trailing `resample`.
The [bounded UI rollout guide](../devguide/integration/wayang-rust-compose.md) connects one actual workbench DAG
to the backend PostgreSQL journal, standalone coordinator, custom Wayang Rust platform and three HTTP workers.
The three UI lessons retain their browser workloads and link the separate Rust vector, state and rollout tests.
The [continuous guide](../devguide/integration/wayang-rust-continuous.md) adds live-input, predecessor/cursor,
retained-window and restart cases. Increment manifests reserve cumulative series ownership, including unselected
input series. A finite increment is not a new deployment, and a partition cursor is not a placement unit.
The source/sink/checkpoint/assignment lessons trace the infrastructure without introducing another operator catalog.
Private preparation precedes every activation; completed retry reuses receipts, while uncertain activation retains
ownership and retries saved bytes. These are at-least-once bounded writes, without atomic publication across workers.
Their zero-based coordinates do not establish a Kafka timestamp policy: published Rust records require
`tStart / 1000` rounded down to be strictly positive milliseconds.
Open resample tails remain in retained state at the cut; source series cannot merge into one derived identity.

<a id="use-frozen-catalog-1"></a>

## Use frozen catalog 1

Use the [catalog inventory](../../systems/catalogs/tsx-catalog-1.yaml) to
identify the 16 frozen processor versions. Every entry is version 1. Its
processor description supplies parameters, state, merge metadata, shared
vectors and the available Wayang class. Read those definitions instead of
copying parameters into a lesson or treating the learning metadata as another
runtime catalog.

| DAG operator | Version |
| --- | --- |
| `decode.avro-soe` | 1 |
| `dq.checks` | 1 |
| `convert.unit` | 1 |
| `resample` | 1 |
| `model.wind-shear` | 1 |
| `model.power-curve` | 1 |
| `feature.lag` | 1 |
| `feature.rolling` | 1 |
| `stats.welford-ddsketch` | 1 |
| `align.vector` | 1 |
| `segment.threshold` | 1 |
| `segment.extremum` | 1 |
| `sax.word` | 1 |
| `pair.xcorr` | 1 |
| `pair.es` | 1 |
| `dq.result` | 1 |

The inventory uses processor identity `dq.plausible-range`; its
[description](../../systems/processors/dq.plausible-range.yaml) maps it to DAG
operator `dq.checks` and fixes the `PLAUSIBLE_RANGE` check. Resolve this alias
when comparing the inventory with
[SPEC S1.7](../datamodel-2026/20-WAYANG-TS-OPERATORS-SPEC.md#s17-catalog-1).

For pair operators, keep the catalog's `window` value as text. The workbench
text field and YAML exporter preserve that representation, including decimal
text. Direct pair APIs also accept numeric seconds.
The [cross-correlation](../../systems/processors/pair.xcorr.yaml) and
[event-synchronization](../../systems/processors/pair.es.yaml) descriptions
record this boundary.

[SystemDocTest](../../opentsx-retrieval/src/test/java/org/opentsx/retrieval/system/SystemDocTest.java)
checks frozen membership, versions and description metadata. Native execution
and retained-state comparisons are separate checks in
[TsxStreamEqualsBatchTest](../../opentsx-wayang/src/test/java/org/opentsx/wayang/TsxStreamEqualsBatchTest.java).
The local
[`DagToWayang`](../../opentsx-wayang/src/main/java/org/opentsx/wayang/DagToWayang.java)
API compiles all 16 catalog bindings. The inventory and explicit compile context
validate local construction; they do not grant remote execution rights. Rust
execution uses its own admitted operator profile, which the 16 Java bindings do
not expand.

<a id="compile-the-exported-workbench-document"></a>

## Compile the exported workbench document

You need the original YAML text, processor descriptions and their frozen
inventory, plus the topic reads and signal metadata required by the graph.
Use the [compiler instructions](../../opentsx-wayang/README.md#compile-a-catalog-dag)
and read the executable
[catalog cases](../../opentsx-wayang/src/test/java/org/opentsx/wayang/DagToWayangCatalogTest.java)
before binding sources or choosing a boundary profile.

Keep the original text: `specHash()` covers its UTF-8 bytes, including whitespace.
The compiler applies catalog defaults to a parsed copy and rejects unknown
parameters. For an intermediate clean run, supply an explicit target instead of
inventing a system or signal identity. Pair and alignment inputs need ordered
series inventories. A cross-correlation node also needs an admitted site product
budget and a trusted workload estimate.

The actual workbench exports in
[workbench-dags](../datamodel-2026/testvectors/workbench-dags/clean-signal.yaml)
are generated by the frontend tests and read directly by Java.
The exporter writes resampling widths as ISO durations. A statistics branch
writes complete `org.opentsx.model.v2.SeriesStats` records with its explicit
window width; the selected browser `field` stays in the saved flow as a view.
Comparing that projected UI bucket with the deployed record would compare
different representations. The UI threshold segmenter uses sample-count
context; the exporter does not invent a conversion to catalog duration values.

Check the values that cross a boundary. A federated statistics edge selects
tumbling windows and excludes `GLOBAL`, while another local sink from the same
node retains its global record. A signal collector preserves local DQ findings
without merging their upstream statistics. Compiler admission and local Java
execution remain separate from authenticated site activation and its supported
deployment profile.

## Follow the signed bounded JVM statistics profile

You need JDK 17 or 21, the built modules, explicit local broker bindings,
Ed25519 keys, mutual-TLS contexts, local policy and durable state directories.
Use the [bounded JVM instructions](../../opentsx-wayang/README.md#execute-a-signed-bounded-jvm-statistics-stage)
with the programmatic `TsxPlanService`, `TsxPlanClient` and `TsxSiteAgent` APIs.
The signed profile binds a complete immutable cut and one statistics sink;
the local compiler's 16 catalog bindings remain a separate construction API.
The integration cases use the existing `KAFSCALE_BOOTSTRAP` test switch and
require the source-built KafScale v1.6.0 executable in `KAFSCALE_BROKER_BINARY`;
the fixture owns its four disposable brokers.

Keep the original source text, exact assignment and signed wrapper together.
`SignedJws` verifies the pinned Ed25519 signer. `RunnerPlan` binds the input
contracts, existing sink identity and expected assignment/scopes. The plan
service requires job-bound conformance before activation. The harness executes
the shared vectors, broker checks and actual input inventory checks before it
submits the signed report. Keys, TLS contexts and local policy come from the
embedding application; the integration harness uses ephemeral certificates.

Work through these executable boundaries:

1. Inspect
   [signed-plan tests](../../opentsx-wayang/src/test/java/org/opentsx/wayang/site/TsxSiteAgentPlanTest.java)
   and distinguish an identical retry from changed bytes, an expired grant,
   rollback and an incompatible continuation. Trace the durable outcome and
   delivery cursor without treating an unsigned parse as activation. Match the
   admitted input-topic set to the fragment's source-topic set. Follow the
   lost-request and lost-response cases: replay the saved acknowledgement after
   conformance expires, then confirm that a fresh unqualified delivery rejects.
2. Follow the actual
   [conformance refusals](../../opentsx-wayang/src/test/java/org/opentsx/wayang/ConformanceHandshakeTest.java):
   wrong input fingerprint, overlapping site series and a TLS client without
   a certificate. Check the reported failing check instead of changing its
   expected result.
3. Run the complete
   [W3 federation cases](../../opentsx-wayang/src/test/java/org/opentsx/wayang/FederatedStatsIntegrationTest.java)
   and compare all six merge orders on received partials. Compare complete
   records in the
   [route-equivalence case](../../opentsx-wayang/src/test/java/org/opentsx/wayang/JobStateRouteIntegrationTest.java).
   Read the approved mean/M2 qualification criterion in
   [SPEC S4.3](../datamodel-2026/20-WAYANG-TS-OPERATORS-SPEC.md#s43-merge-functions).
4. Stop one site in the
   [completeness case](../../opentsx-wayang/src/test/java/org/opentsx/wayang/CompletenessIntegrationTest.java).
   Inspect 420 incomplete revision-zero records, reload the coordinator and
   return the site. Compare the 420 appended final revision-one records while
   preserving the original Kafka positions and bytes.

`TsxBoundedSiteExecution` runs the admitted fragment, groups its statistics and
publishes every declared scope, including known empty windows, before saving
its compatible checkpoint. `TsxSitePlatform` and `AggregateChannel` connect
the complete site action to the driver. Signal-cluster Kafka delivery and
authenticated job-state delivery carry the same aggregate identity and
evidence; local DQ findings remain signal records rather than merge inputs.

The bounded integration topology uses three site-agent instances inside one
JVM and four Go broker processes. Execution topology excludes build and
transient conformance-check subprocesses. The measurement tool records Kafka
Produce frames, HTTP body bytes, encrypted TLS client-stream bytes and one
elapsed clock per route. Read its arguments with
`python3 tools/wayang_federation_measurement.py --help`; use its executed
`bytes.csv`, `windows.csv`, `run.json` and provenance when assessing a report.

## Run finite Python forecasting and preparation

The public Python lessons cover `forecast_arima`, `forecast_baseline`,
`rolling_backtest`, `seasonal_decomposition`, `power_transform` and
`inverse_power_transform`. Use the package's `forecasting` extra or the pinned
[local CP scientific runtime](../devguide/local-cp-workbench.md). The
[library lesson metadata](../learning/library-operators.v1.json) supplies finite
synthetic workloads and executable checks for every exported Python algorithm.
The [finite ARIMA contract](../learning/library-operator-contracts.md#forecast-finite-arima-families)
records the likelihood-score derivatives, iteration limit and strict convergence
check. Its small-amplitude regression verifies analytic variance and intervals.

Start with analytic reference forecasts and chronological evaluation. Check
the random-walk forecast's mean and square-root interval growth, then prove
that later observations cannot change an earlier backtest origin. Decompose
seasonal signals only as an offline operation; its centered smoothing is
noncausal. Apply Box-Cox only to positive values, retain the chosen lambda, and
verify inverse transformations on the original value space. Inverting a point
forecast does not by itself make it an unbiased conditional mean.

These examples execute the public library calls directly. They do not establish
Java, Wayang or Rust parity, or deploy a processor. Use the separate
[reviewed statistical Smart Topic workflow](../devguide/model-smart-topics.md)
for Workbench training, registration and admitted local Kafka scoring.

## Measure the bounded JVM profile

Use the [federation measurement instructions](../datamodel-2026/20-WAYANG-TS-OPERATORS.md#143-measure-bounded-jvm-federation)
with a clean checkout, JDK 17 or 21, Maven, Git, Python and the pinned Go
toolchain. The harness owns four disposable KafScale brokers and records three
site instances in one execution JVM. Its
[report and raw files](../datamodel-2026/measurements/wayang-federation-2026-11-17/REPORT.md)
include hardware, versions, commands, all 420 reference windows and per-site
transport counters. Kafka frames, HTTP bodies and TLS streams remain separate
metrics. The route clocks cover different work and do not compare latency.

## Work through a lesson

The lesson format follows the existing [exercise structure](../../exercises/README.md#exercise-format):

1. State the learning objective and check prerequisites.
2. Load the supplied synthetic starter input and write down the predicted axes, units, values and gaps.
3. Execute the named operator through its actual runtime entry point.
4. Compare the result with the expected checks and the linked executable regression cases.
5. Change the requested parameter or boundary condition and explain the effect.
6. Record which behavior is shared across runtimes and which remains specific to the implementation.

Keep input timestamps, NaNs, episode boundaries and random seeds exactly as the lesson states.
Do not replace an expected result with the current output merely to make a check pass.
For library algorithms, use the demonstrated public API rather than reimplementing its numerical kernel.

## Check that the student material matches

Learning metadata is maintained with OpenTSx. The student repository contains generated lessons and a source
content manifest. `<student-checkout>` below is the path to that repository's checkout.
Run from the OpenTSx root:

```bash
python3 tools/sync_operator_exercises.py --opentsx-root . --student-root <student-checkout> --check
```

The check rejects missing or edited generated lessons, broken references and source-content mismatches.
The manifest records the source revision and whether its content includes local changes.
Published material requires a published matching source revision.
Regeneration is explicit; checking does not silently repair stale material.

For the workbench lesson workloads, run from its frontend directory:

```bash
cd opentsx-saas-frontend
npx vitest run src/workbench/operator-learning.test.ts
```

The test imports the real `OPS` registry, requires matching lesson coverage and executes the supplied workloads.
Library lessons identify their Java/Python tests and applicable shared vector files.
Run the added Java library examples from the OpenTSx root:

```bash
mvn --batch-mode --no-transfer-progress --projects opentsx-core,opentsx-retrieval --also-make test -Dtest=LibraryAlgorithmsLearningTest,GeneratorLearningTest,LegacyFourierLearningTest -Dsurefire.failIfNoSpecifiedTests=false
```

Confirm that all three named test classes executed. The flag permits dependency modules without those tests.
Run the Python algorithm examples from the package directory in your installed Python environment:

```bash
cd python-package
python -m pytest -q tests/test_library_learning_algorithms.py
```

Reference checks and hashes detect structural/content drift; scientific semantics still require those executed
workloads and review of the expected results.

## Keep topic placement separate from internal positions

For orchestration lessons, a whole topic is the smallest deployable unit regardless of its partition count.
One assignment includes every partition's internal read/cut/checkpoint position. Splitting a topic's partitions
across runner nodes is invalid. A two-node lesson uses different topics or distinct complete graph stages.
If a topic exceeds a node's resource budget, reject or move the whole topic; do not teach partition splitting as
a fallback. See the [shared assignment contract](../devguide/integration/wayang-shared-contracts.md).

## Change an operator with its teaching contract

Update the source behavior, applicable manual/SPEC, learning metadata, starter input and expected tests together.
Regenerate student material from that metadata, then run synchronization, runtime and regression checks.
The durable delivery rule is in [AGENTS.md](../../AGENTS.md#14-operator-learning-and-deployment-contracts).
