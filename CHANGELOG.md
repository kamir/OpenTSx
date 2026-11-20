# Changelog

All notable changes to OpenTSx are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/). Releases are tagged `v<version>`;
entries before 3.0.0 are grouped by date. The detailed progress log is in
[`EVOLUTION/TASK-011-datamodel-refresher-2026.md`](EVOLUTION/TASK-011-datamodel-refresher-2026.md).

## [Unreleased]

- Serialize pinned local KafScale metadata snapshots and refresh durable metadata before public reads. Proxy metadata
  responses retain acknowledged topic changes without waiting for watchers and propagate durable read failures.

### Changed

- Resolve Central dependencies and plugins in CI through a Google Cloud-hosted
  mirror. Verify endpoint selection and permanent errors offline, including a
  Central 404 response with a long Retry-After value.
- Retain Maven execution exception details, selected transport and sanitized
  cache reports before and after every Maven CI job. Check missing-plugin
  failure diagnostics offline and distinguish fresh HTTP failures from cached
  failures in the pinned Resolver tests. Historical statusless download
  failures remain undiagnosed.
- Record actual Wayang and BOM HTTP response statuses and sanitized metadata
  in CI before retry handling. Verify capture without requests or sleeps and
  preserve permanent-error handling and response content.
- Suppress native cookie-parser logging that can include complete cookie values.
  Exercise the bundled handler with a malformed-cookie fixture without network
  requests or sleeps.
- Scope Maven dependency-cache keys by workload and runtime inputs so a smaller
  job cannot freeze another job's dependency subset under an immutable key.
- Execute Rust CLI prefix fixtures through the installed shell without temporary files. Regressions rerun the exact
  fixture with an unusable temporary-directory path and retain runner-prefix and spaced-argument checks.
- Reuse separate verified caches for locked Python dependencies, the application
  wheel and the Workbench frontend. Record their input keys with current source
  provenance; execute package tests on every test invocation.
- Pin all Maven CI jobs to checksum-verified Maven 3.9.8 and check the existing download retry policy against its
  bundled HTTP transport before builds. Retain Java build and classpath log files in Wayang Compose artifacts
  after failed builds.
- Select pull request CI jobs from Maven dependencies and shared runtime/test inputs. Keep full validation on
  `master` and manual runs, run documentation links with learning checks, and require a final result that rejects
  selection failures and unexpected job skips. Unmapped paths and shared build, contract or manual changes select
  all jobs. Frontend changes also select the Python launcher checks.
- Disable the unimplemented native `persist TSB` control with an explanatory tooltip. Both panel constructors
  prevent click dispatch from this control.
- Prepare PFD JSON before writing and export it as strict UTF-8 through atomic replacement. Failed preparation,
  encoding or replacement preserves existing files; destination symbolic links are rejected and temporary files
  are cleaned up. Relative destinations and missing parent directories are supported; existing POSIX permissions
  are retained where supported.
- Stage and close SequenceFile bucket exports before replacement. Preparation failures preserve existing data
  and checksums; primary failures retain additional close and cleanup errors. Local data and checksum replacement
  remain separate atomic operations, with explicit diagnostics when checksum publication or staging cleanup fails
  after data is saved. Unsupported filesystems fail before writing; the label/Y format is retained.
- Exclude the public Proxy port and already selected private ports from isolated KafScale fixture allocation.
  The pinned build runs deterministic launcher regressions and records their source hash.
- Keep committed offsets monotonic in the pinned local KafScale metadata overlay.
  Conditional updates retain newer flush progress and propagate storage failures.
- Route local KafScale producers and test fixtures through the v1.6.0 Proxy. Build the pinned broker, Proxy and
  isolated metadata launcher without building an image. Compose exposes the Proxy endpoint; the development
  MinIO stack requires private `MINIO_ROOT_USER` and `MINIO_ROOT_PASSWORD` environment values.
- Route Track A evaluation clients through the same guarded Proxy bundle and record its transport provenance.
  Archived measurement samples retain their recorded source and runtime hashes.
- Require a verified Proxy bundle and private MinIO environment before the k3s probe starts local KafScale.

### Added

- Artifact-embedded WASM `convert.unit` in the local Rust cleaning pipeline,
  with Wasmtime 50.0.1/Pulley, fixed fuel, memory and stack bounds, no host
  imports and no native conversion fallback. Resource-limit and shared-vector
  regressions retain existing numerical, record and checkpoint contracts.
  Manual and generated conversion lessons distinguish this local binding
  from signed fleet admission and open physical ARM64/ARMv7 acceptance.

- Catalog `tsx-catalog/1` records the SHA-256 of each operator's vector file, with the new schema
  `opentsx.catalog.v1`. `SystemDocTest` fails when a vector file changes without a new operator version and a new
  catalog (S1.1.3). No operator, version or vector file changes.

- Demo entry page linking local thermal, finite forecast and Windpark
  journeys to prerequisites, acceptance checks and a recording tied to its
  tested revision.

- Record the finite Workbench browser journey as WebM video and a Playwright
  trace. Require matching clean source and runtime identities before and after
  capture, verify numerical exports and hash the evidence in a recording manifest.

- Bounded mixed W3 acceptance harness with two Java sites and an actual
  qualified Rust CLI site, runtime-bound aggregate admission, complete
  partial/window evidence and six canonical-merge arrival orders. Separate
  exact-binary64 sketch cases retain shared vectors and identify boundary,
  zero-bucket and collapsed-tail accuracy domains without widening the
  moments criterion. Board evaluation tooling prepares the fixed 54-condition
  matrix and validates supplied evidence integrity. Physical ARM64 and ARMv7
  B4.2 measurements and ARMv7 B1.2 acceptance remain open. Manual and learning
  metadata keep these execution and hardware scopes separate.

- Check tools-image identity, packaged Windpark startup and numerical replay
  before publication. Pull published digests on native AMD64 and ARM64 runners
  and retain receipts and cleanup evidence. Supply a named Hadoop account while
  retaining runtime UID and GID 10001.

- Add a deterministic local windpark demo with healthy, fault and curtailed
  turbines. Persist real Iceberg episodes through a SQLite catalog and verify
  replay against full payload hashes and recalculated underperformance results.

- Standalone analysis package through the existing launcher with
  `--profile finite`, its own Compose project, network and runtime volume.
  Locked artifacts serve the Workbench and finite numerical API without CP
  or MLflow. Browser acceptance fits and exports a checked synthetic result.

- Owner decision D-T13 in
  [`EVOLUTION/TASK-020-wayang-ts-operators.md`](EVOLUTION/TASK-020-wayang-ts-operators.md), accepted on 2026-11-20:
  FPGA kernels on raw sensor data run as Tier 3, behind the Rust runner and for data reduction only. The record states
  the concept 09 boundary it changes, the partial and finish numerics, the `runtimes.fpga` key, the `raw` port, signed
  kernel packages, the first kernel pack, the admission gate per kernel, and that the contract changes ship with the
  first kernel.
- Study on running OpenTSx operators in orbit
  ([`docs/research/02-WAYANG-FPGA-OPERATORS-FOR-SPACE.md`](docs/research/02-WAYANG-FPGA-OPERATORS-FOR-SPACE.md)):
  no tool compiles the Python operators to FPGA logic; a bounded streaming subset can be reimplemented as FPGA kernels
  behind the Rust runner, with Wayang planning and signing on the ground. Covers toolchains, space-grade hardware,
  in-orbit platform services, onboard logs, space-ground transport, pre-flight test environments and a roadmap.
- Study on earth-plus-orbit data product architecture
  ([`docs/research/03-EARTH-ORBIT-DATA-PRODUCT-ARCHITECTURE.md`](docs/research/03-EARTH-ORBIT-DATA-PRODUCT-ARCHITECTURE.md)):
  ground service APIs (SQL, graphs, key-value, Flink or Spark, Kubernetes, S3) are not a cross-tier standard for orbit;
  standardize a contract-first boundary of sealed, signed log segments and signed plans, and allow small embedded
  engines inside the payload partition. Includes a two-sided debate with fact checks, six options and a decision guide.
- Standalone finite numerical service through `serve-finite`, with optional
  built Workbench assets and no broker or model-registry clients at startup.
  API requests retain bounded numerical execution and explicit errors;
  unavailable connected functions return HTTP 503.

- Local frontend type, lint and related-test checks through `check:local`.
  Empty selections and failing stages return an error without a fallback run.

- Signed bounded Rust statistics sites with TLS 1.3 mutual authentication,
  job-bound conformance and explicit local policy. Owner-local qualification
  binds actual executable, vector, broker-protocol and producer-placement
  evidence. Durable delivery outcomes and acknowledged aggregate outboxes
  support offline catch-up with deterministic key/revision deduplication.
  Later job generations wait for every previous site's durable stopped status
  and accepted delivery acknowledgement, preserving recovery after lost responses.
  Certificate-forced SSH launch fixes the executable and local configuration.
  Acceptance failures retain the actual unexpected server exit status.
  OpenSSH authentication-attempt exit 5 is accepted only for verified certificate denials.
  The profile admits decode-to-statistics only; broader B2 operators, mixed
  continuous fleets, production release enrollment and physical ARMv7
  acceptance retain separate gates. Documentation, operational and learning references
  describe these boundaries.

- Isolated local Rust B2 catalog execution with typed SeriesStats, vector and DQ
  output, model and feature chains, segmentation, SAX and bounded XCorr.
  Explicit local context admits the extended resource ceilings; legacy local
  and signed B1 profiles retain their separate contracts. Shared vectors,
  restored-state regressions and actual full-windpark Java/Rust parity cover
  the operators. Manual, learning metadata and generated lessons describe the
  qualified scope. Physical ARMv7 hardware acceptance remains open.

- Standalone thermal Workbench entry through `npm run demo`, with deterministic
  synthetic episodes, local computation and preserved analysis across display
  modes. Explicit demo links reject competing imports and preserve existing
  work. A focused local test command and service-free browser acceptance check
  verify source evidence, exported values and snapshot integrity.

- Isolated signed continuous pass-through in `tsx-runner` 0.1.0: Ed25519 plan
  verification, local site policy, whole-topic authority, explicit cut caps and
  acknowledgement-before-checkpoint recovery. Checkpoint and worker locks
  release in their acquiring process even with inherited descriptors open.
  Trusted-local replay and HTTP
  cleaning workers retain their separate contracts. Production fleet activation
  remains outside this profile.
- Rust shared-vector coverage for xxHash64, bucket checksums and time deltas,
  plus native x86_64/ARM64 and three-target static
  musl CI gates with ELF and test-report validation. ARMv7 QEMU execution is
  supplementary; physical ARMv7 qualification and full TASK-020 B1 closure
  remain open.

- Local publication fencing for three whole-topic Rust workers. A private, single-owner Java gateway serializes
  publication and epoch rotation, validates captured grants and retains unknown broker outcomes without blind
  resend. Segregated KafScale proxies, remote-address broker ACL and controller-peer worker checks enforce the path.
  Explicit resume preserves state/cursors; changed-DAG restart replays complete inputs into a distinct fresh output.
  The gateway is capped at 64 groups, 64 operation receipts, 256 requests and a 4 MiB ledger; it has no automatic failover.

- A rerunnable TASK-020 A5 evaluation harness for bounded centralized and
  federated statistics at 1, 3 and 10 sites, and Java catch-up comparisons
  at 1, 4 and 12 partitions. It records throughput, Produce-clock latency,
  RSS, CPU, artifact sizes and source/runtime provenance without thresholds.
  The [report and raw samples](docs/datamodel-2026/measurements/wayang-track-a-evaluation-2026-11-18/REPORT.md)
  retain all 63 completed conditions over the complete synthetic W scenario.
  Paced increment, recovery and Track B measurements remain separate.

- Qualified bounded W3 JVM federation measurement for TASK-020 A4.9. The
  [report and raw samples](docs/datamodel-2026/measurements/wayang-federation-2026-11-17/REPORT.md)
  retain per-site Kafka, HTTP and TLS counters, 420 window comparisons and
  2,520 exact merge-order checks, with source/runtime hashes and commands.

- Reviewed MLflow registration and immutable fitted-state JSON artifacts, prediction without refitting,
  verified Kafka training capture, and persistent ARIMA/SARIMA Smart Topic scoring on the local CP profile.
  The Workbench exposes registration, measured approval thresholds, start/stop, actual worker state and
  acknowledged predictions. Checkpoints preserve numerical state and source position after publication.
  SARIMAX supports artifact reuse and continuous scoring with verified immutable known-ahead context,
  explicit availability cutoffs, visible context waits and durable context/target cursors.
  Continuous statistical scoring requires whole one-partition input/context topics and rejects topology expansion.

- Selectable Signal Engineer and Data Worker presentations with Instrument
  Console, Diagnostic Bench, Pipeline Studio and Recipe Notebook layouts.
  The Legacy mode retains the classic controls. Switching preserves the
  shared graph, scope and modelling editor. Modern tools provide a searchable
  operator library, staged settings, bounded FFT/ACF diagnostics and actual
  sample previews. A deterministic thermal fixture exposes units and gaps.

- Workbench modelling for finite regular targets: ARIMA, SARIMA and SARIMAX, reference forecasts,
  rolling-origin backtests, prediction intervals and JSON result export. Context values carry availability
  times checked at each origin. The local CP Compose profile serves the UI and bounded Python forecasting
  API from one locked bundle.

- Workbench pair-result tables and matrices, typed analysis-result JSON inspection, episode quality and lineage, and integrity-checked finite input/graph snapshots. Properties distinguish browser, Java processor and Wayang capabilities. Deployment preflight checks every published sink and path; browser-only pair drafts are marked experimental.

- Continuous workbench execution through Wayang on fixed whole-topic Rust workers. Each acknowledged increment
  supplies the next complete partition cursors and retained operator state. PostgreSQL persists plans before
  dispatch, retains ownership through idle and uncertain outcomes, and records durable stop requests. Worker
  head/candidate storage and completed cycle history stay bounded. The continuous Compose/browser profile checks
  live input, data arriving during execution-service downtime, stateful restart, an isolated worker outage, later
  input and Stop against Java.
  Publication remains at least once. Explicit local fenced replacement is specified separately; automatic
  failover, signed fleet activation, broker-restart qualification and durable Iceberg publication remain separate.

- Regression guards for the Wayang/Rust Compose profile's existing required
  credential interpolation and the helper's private per-run environment.
  Mutated fallback and empty-value fixtures reject without printing credentials.

- `pair.es` runs on Wayang through `TsxPair` with `q_total`, `q_ab`, `q_ba`
  and `q_rand` outputs. The four shared series cases execute through real plans.
  Parameters and surrogate seeds follow the workbench implementation.

- Explicit bounded workbench deployment through the authenticated backend, PostgreSQL journal and standalone
  coordinator to Apache Wayang's custom Rust platform. Connected topic sources, unions, a fused clean chain and
  sink execute as one stage; complete topics are assigned statically to one to three HTTP workers. All workers
  prepare privately and expose series ownership before any activation. Exact UI text, source cuts, clock, fragments
  and request bytes survive retry. Uncertain activation retains topic ownership and retries saved output; completed
  requests return stored receipts. A separate three-worker Compose/browser acceptance helper compares the shared
  output with Java. Activation remains at least once and can expose partial output; signed fleet execution,
  continuous processing, automatic failover and durable Iceberg publication remain outside this local profile.

- A rerunnable Wayang increment-latency harness for TASK-020 A1.7: real
  passthrough and `dq.checks` plus `resample PT1M` plans, paced complete simulator
  input, a pinned disposable KafScale broker, private runtime snapshots and
  per-increment/raw output timing samples. The
  [measurement report](docs/datamodel-2026/measurements/wayang-increment-latency-2026-11-17/REPORT.md)
  records its metric definitions, configuration and isolation limits.

- `tsx-runner` 0.1.0 for trusted-local bounded whole-topic Kafka replay. Pass-through and a linear chain of
  `dq.checks` (`PLAUSIBLE_RANGE`), `convert.unit` and an optional trailing `resample` use the shared Avro, identity
  and cleaning vectors. Explicit resource caps and positive Kafka transport timestamps reject before publication.
  Derived-series ownership prevents conflicting packets from distinct source series. Local checkpoints retain
  lossless operator state and commit after every output acknowledgement; exact completed retries return their
  saved receipts. A standalone Compose helper runs three independent topics with one, two and three partitions
  against Java reference output. This standalone replay fixture remains distinct from the UI-to-Wayang rollout
  acceptance helper; continuous processing and production fleet activation remain separate.

- Bounded JVM federation for TASK-020 A4: complete-catalog local compilation,
  exact rejection messages and Workbench export fixtures; signed immutable site
  assignments, job-bound conformance, TLS 1.3 client certificates, durable
  delivery outcomes and strict continuation identities. Retained acknowledgements
  recover lost requests and responses after conformance expiry without repeating
  execution. Bounded JVM admission rejects missing or unused fragment sources.
  Site statistics carry
  width-aware keys and closed-cut evidence through either acknowledged Kafka
  forwarding or the authenticated aggregate endpoint. Canonical central merges
  retain immutable completeness revisions and an acknowledged publication outbox.
  This qualification uses three site instances in one JVM and four independent
  brokers. Production enrollment, continuous fleet activation and ownership
  takeover remain separate.

- Frozen `tsx-catalog/1` with 16 versioned native Java entries, complete
  processor descriptions and membership/version/metadata regressions.
  Stream/batch parity covers 61 native numerical cases and four explicit
  ES kernel cases in six chunk/order combinations. README, SPEC, manual
  and learning contracts describe runtime scope and comparison boundaries.

- Wayang operators for threshold and extremum segmentation, SAX words, and
  data-quality signals (TASK-020 A3.4 and the retrieval part of A3.5). Segments
  checkpoint their retained samples and detector state per series. All 26
  shared cases run through Wayang and all 156 chunk/order comparisons match.
  The topic sink validates and writes JSON `opentsx.signal.v1` records.
  DQ signal identities include the check and window bounds; result messages
  use `Locale.ROOT`. README, manual, and processor descriptions are updated.

- `opentsx-wayang` A3.2 (TASK-020): `pair.xcorr` runs on Wayang as `TsxPair`, with the pair windows of the workbench
  (`pairs.ts`) ported to `ops/TsxPairWindows`. Episodes of A and B are paired (`same` or `intersect`) as they arrive
  and wait in the checkpoint for their counterparts; each pair range gives one episode per field
  (`pair.xcorr.max`, `lag`, `strength`). All five shared `pair.xcorr` cases pass through a Wayang plan. Specification
  S6.2 gives the id of a pair result episode.
- `opentsx-wayang` A3.1 (TASK-020): `align.vector` runs on Wayang as `TsxAlignVector` through `VectorStream`; inputs
  are marked with `TsxTagged` and joined with a union, open ticks pass from increment to increment in the checkpoint,
  and `TsxVectorSink` writes `opentsx.vector.v1` records to a vector topic. All six shared `align.vector` cases pass
  through a Wayang plan, in one run and in two increments.
- `opentsx-retrieval`: `VectorStream` accepts named inputs that read series of their own
  (`new VectorStream(expectedByInput, params, lateness)`, `add(input, seriesId, ...)`). The state format of a stream
  without named inputs is unchanged.

- `opentsx-wayang` phase A2 (TASK-020): the clean chain (`dq.checks`, `convert.unit`, `resample`,
  `model.wind-shear`, `model.power-curve`, `feature.lag`, `feature.rolling`) runs on Wayang as one operator per run
  of consecutive nodes (`TsxSeriesChain`, through `SeriesStream`), and `stats.welford-ddsketch` as `TsxStatsWindow`
  (through `StatsEngine`). Operator state passes from increment to increment in the checkpoint. `TsxStatus` writes
  `opentsx.dag-status.v1` records with `track`, `job` and `planVersion`; `TsxIncrement.loop` runs increments at an
  interval. Specification S2.3 states why a run of clean operators is one Wayang operator.
  `DagToWayang` validates local clean DAG parameters from processor descriptions.
  Statistics accept episodes and observations, bounded streams, and explicit
  keyed windows including empty input. All five shared statistics cases run
  through Wayang. The parity test deploys the same clean DAG to the existing
  `VectorProcessor` on KafScale and compares episode ids and values. Status
  heartbeats run every 30 seconds during long increments and idle intervals,
  include progress fields, and preserve the original error if reporting fails.

- Five shared Java reference vector files for statistics windows, threshold
  and extremum segments, SAX words, and data-quality results. Owning-module
  tests replay all 31 cases exactly. `tools/java_operator_vectors.py`
  regenerates the files from the existing implementations (TASK-020 A2/A3).

- Pure Java `pair.xcorr` in `opentsx-wayang`: lagged cross-correlation,
  signed absolute peak, link strength, and centred pair windows. The five
  shared vector cases and invalid-lag regression tests cover TASK-020 A3.2.
- Pure Java `pair.es` in `opentsx-wayang`: local-maximum event detection,
  directional synchronisation, interval-shuffle surrogates, and `Mulberry32`.
  The eight shared vector cases and exact random sequence cover TASK-020 A3.3.

- `opentsx-wayang` phase A1 (TASK-020): Wayang source and sink for KafScale topics (`TsxTopicSource`,
  `TsxTopicSink`) with manual assignment, a schema-fingerprint check per record and the KafScale client settings;
  `TsxCheckpoint` and `TsxIncrement` run a plan over the offsets since the last checkpoint and write the new
  checkpoint only after every record was acknowledged. The sink writes maps in UTF-8 key order, so an episode that is
  read and written again keeps its bytes. `LogReader` in `opentsx-kafka-v2` is public. The CI job "KafScale
  conformance" runs the three new integration tests and requires them to execute.

- Shared execution-assignment schema and offline Java/Rust validators for exact source/fragment identities,
  decimal-string offsets, execution modes and whole-topic ownership with complete internal cursor checks.
  Generated fixtures are consumed directly by both runtimes. The unified delivery plan covers
  UI-to-Wayang-to-Rust orchestration and a separate
  bounded storage profile. The validators perform no network deployment or production fencing; the separate
  Rust runner and local Wayang rollout execute only their trusted-local bounded profiles.

- Versioned learning workloads for all 30 executable UI/DAG operators and 25 documented library/infrastructure
  families, including MFDFA and RIS. Tests execute UI workloads and numerical library examples. Student lessons
  are generated with source-content hashes; synchronization rejects missing, edited or stale material.
  Manual chapter 17 links the contracts and verification commands. The project rules require coordinated
  operator, manual, SPEC, exercise and test updates and preserve whole-topic deployment ownership.

- Module `opentsx-wayang` (TASK-020 phase A0, [`opentsx-wayang/README.md`](opentsx-wayang/README.md)): Apache
  Wayang 1.1.1 (`wayang-core`, `wayang-basic`, `wayang-java`) in the root `dependencyManagement` without the cloud
  storage SDKs and the `log4j-slf4j-impl` binding; Hadoop from the managed shaded client 3.5.0; `TsxJavaPlugin`
  registers the Java platform with only the mappings OpenTSx uses; `WayangSmokeTest` runs a plan on the Java
  platform. The owner decisions D-T1 to D-T12 are recorded in TASK-020, and concept 20 documents the checked
  security features of KafScale v1.6.0 (no TLS or SASL on the Kafka listener; KafScale ACLs).

- `ESCalcTest` (`opentsx-core`) covers the event synchronization of `ESCalc` and `ESCalc2`: identical series give
  `(M - 2) / (M - 1)`, a series that follows the first gives a negative delay, an event halfway between two events of
  the other series is not matched twice, and sync and delay stay in [0, 1] and [-1, 1] on random event series.

- Related-work study for the IEEE paper ([`docs/research/01-RELATED-WORK-AND-POSITIONING.md`](docs/research/01-RELATED-WORK-AND-POSITIONING.md)):
  188 candidates in nine clusters, high and medium candidates checked against primary sources on 2026-11-16, a comparison
  matrix over 14 dimensions, a concept mapping to commercial historians and cloud services, a claim check of what
  distinguishes OpenTSx, and the evaluation a federated variant would need before it can be claimed.

- Data model `v2.1`: analysis results as records of their own. `AnalysisResult` holds method, version, typed
  parameters, input episodes and series, and the parts `ResultScalar` (for example a DFA exponent with its fit range),
  `ResultCurve` (for example F(s)) and `ResultMatrix` (for example an event-synchronization matrix over series ids).
  `resultId` is the xxHash64 of a canonical form of method, version, parameters and inputs, computed the same way in
  Java (`AnalysisResults`), Python (`opentsx.model.analysis`) and the workbench (`resultIds.ts`) and checked against
  `testvectors/result-id.json` and the new `analysisResults` entries of `wire-format.json`. Python converts DFA and
  event-synchronization output with `opentsx.algorithms.results`. The `v2.0` records are unchanged. The
  specification [`04-MODELL-V2-SPEC.md`](docs/datamodel-2026/04-MODELL-V2-SPEC.md) is now in English and covers 2.1.

- `pair.es` in the web workbench (TASK-015 phase 1d, operator "Event synchronisation"): event synchronisation after
  Quiroga, Kreuz and Grassberger per pair window, ported from `event_sync.py`, with the fields `q_total`, `q_ab` (B
  after A), `q_ba` and `q_rand`, the mean `q_total` against copies of B with shuffled intervals between its events.
  The Python package gets the same surrogate (`EventSynchronization.surrogate_sync`, `Mulberry32`, `isi_shuffle`);
  both run the shared vectors `docs/datamodel-2026/testvectors/pair-es.v1.json` (`tools/pair_es_vectors.py`).

- Concept and draft specification for OpenTSx time-series operators on Apache Wayang
  ([`docs/datamodel-2026/20-WAYANG-TS-OPERATORS.md`](docs/datamodel-2026/20-WAYANG-TS-OPERATORS.md),
  [`20-WAYANG-TS-OPERATORS-SPEC.md`](docs/datamodel-2026/20-WAYANG-TS-OPERATORS-SPEC.md), proposal, not implemented)
  in two tracks with one operator catalog, one topic and schema contract and one set of test vectors. Track A,
  TSOperators for Java: native Wayang operators on the Java platform in a new module `opentsx-wayang` that reuses
  `opentsx-model`, `opentsx-stats` and `opentsx-retrieval`. Track B: a Rust runner for low-capacity hardware. The
  specification covers the operator catalog (S1), plan translation from the workbench DAG with exact rejection
  messages (S2), the site contract and conformance handshake (S3), federation and merge laws (S4), the runner
  interface (S5), delivery and checkpoints on KafScale (S6) and security (S7). New draft schemas
  `opentsx.runner-plan.v1`, `opentsx.site-conformance.v1` and `opentsx.signal.v1`; `opentsx.processor.v1` gains
  the runtimes `wayang` and `rust` and the optional fields `state`, `merge` and `tolerance`. Tasks with numbered
  acceptance criteria: [`EVOLUTION/TASK-020-wayang-ts-operators.md`](EVOLUTION/TASK-020-wayang-ts-operators.md).

- Concept for graph layers on KafScale and Iceberg
  ([`docs/datamodel-2026/19-GRAPH-LAYERS.md`](docs/datamodel-2026/19-GRAPH-LAYERS.md), proposal, not implemented):
  one node table and one edge table for twin wiring, inventory, measured dependencies, expert influence matrices,
  GeoQB location context on H3 cells and software architecture; immutable layer versions sealed with a checksum and
  Iceberg snapshot IDs; serving to QGIS, ArcGIS and graph tools; an eight-step implementation plan.

- `pair.xcorr` in the web workbench (TASK-015 phase 1c, operator "Cross-correlation"): per pair window the lagged
  cross-correlation CC(k) for k = -lags..lags clock ticks, with the fields `max` (peak of |CC|, sign kept), `lag` (in
  seconds, > 0 when B follows A) and `strength` ((|max| - mean) / std of CC). Ported from `KreuzKorrelation` and
  `CCFunction` of kamir/WikiExplorer.NG (Apache License 2.0); `tools/pair_xcorr_vectors.sh` runs the original code
  for the test vectors `docs/datamodel-2026/testvectors/pair-xcorr.v1.json`, which the port matches to 12 decimals.

- Pair windows in the web workbench (TASK-015 phase 1b, `src/workbench/pairs.ts`): a pair method compares two series
  window by window and stamps each result at the window centre. Episodes of A and B are paired by the same range or
  on their intersection; `window: episode` gives one point per pair range, a width and step give a regular series per
  pair range. A and B go onto one clock by the rules of `align.vector`; a window below the minimum coverage keeps its
  place as NaN with quality 1. Results carry the metric `pair.<method>.<field>`, the tags a, b, window and step, the
  series id from xxHash64 in the browser (`seriesKeys.ts`, checked against the shared vectors) and episodes labelled
  with both inputs. First method: "Pair coverage" in the new tab "Multi-variate Analysis".
- The web workbench keeps the episode ranges of loaded episodes (`Bucket.episodes`): series, episode id, [start, end)
  from `tStart`/`tEnd` to the microsecond, and the segmentation strategy. Operators that keep the time axis hand them
  on; the Properties tab shows how many episodes a bucket holds. First step of the pair windows of TASK-015.

- `WireRoundTripTest` (`opentsx-legacy-bridge`) checks success criterion 2 of TASK-011 in one chain:
  `TimeSeriesObject` to `Episode` to Kafka in single-object and in Confluent framing to `Episode` to
  `TimeSeriesObject`, regular and irregular, through the Kafka client records and, with `KAFSCALE_BOOTSTRAP`,
  through KafScale (now part of the KafScale CI job).
- `PatternBenchmarkTest.recallAtTenMeetsTheSuccessCriterion` asserts recall@10 of at least 0.95 for 10 planted gusts
  with z-normalized Euclidean distance (criterion 7, D87). The recall@12 report stays.

- `StatsJob` in `opentsx-flink-core` (`org.opentsx.flink.stats`) runs the v2 statistics engine as a Flink job:
  `KafkaSource` without offset commits, one `StatsEngine` per series in keyed state with its own state serializer,
  GLOBAL on a timer, all windows closed at the end of a bounded run, `KafkaSink` at least once. It writes the same
  `SeriesStats` as `StatsProcessorApp`. Launcher verb `bin/opentsx stats-flink`; main class of the shaded jar.

- Smoke test against a real OpenMetadata 2.0.5: the workflow `openmetadata-smoke.yml` (nightly, by hand, and on pull
  requests that change `opentsx-openmetadata/`) starts the server from the pinned compose file of the release, gets
  the token of `ingestion-bot`, and `OpenMetadataSmokeTest` publishes the simulated wind farm and reads back topics,
  tables, lineage, the latest profile and data-quality results.

- The KafScale conformance suite probes `Admin.listOffsets` with `OffsetSpec.earliest()` and `OffsetSpec.latest()`,
  which the Flink Kafka source uses for end offsets and bounded runs. Both answer correctly on KafScale v1.6.0.

### Changed

- User manual 11.3 no longer lists `overall_sync` above 1 as a known issue of `EventSynchronization`: the local
  window of Quiroga et al. fixed it (93499604). It describes the local window and `surrogate_sync` instead.
- TASK-011 (data model v2, KafScale, stores, wind farm, statistics, OpenMetadata, retrieval) is completed: all seven
  success criteria are met, criterion 4 by D86 (`docs/datamodel-2026/18-CLOSING-TASK-011.md`, section 9).
- `EVOLUTION/TASK-011-datamodel-refresher-2026.md` is in English. Its phases 6 to 9 and its backlog moved to the new
  tasks TASK-015 to TASK-019 and to TASK-010 (D90 in `docs/datamodel-2026/18-CLOSING-TASK-011.md`). TASK-015 adds
  the design of pair windows for multivariate analysis.

### Removed

- `TimeSeriesAnalysisJob`, the 2.x Flink example job. `StatsJob` is the v2 Flink job. `TimeSeriesAggregateFunction`
  stays, because the PFD runtime `DynamicTopologyJob` uses it.

### Fixed

- Retrieval POST routes `/v1/patterns/search`, `/v1/episodes/similar` and `/v1/segments` require a single JSON object
  and report invalid bodies as HTTP 400. Response exchanges close after success or failure while preserving the
  primary exception and any suppressed close failure.

- The legacy PFD Kafka decoder fails on malformed Avro records with topic, partition and offset context and the
  original cause. Null tombstones emit nothing, and downstream collector failures propagate unchanged.

- Native shuffling validates selections, pass counts, finite samples and a requested-work limit before copying or
  shuffling. Cancellation is quiet, failures are visible, and results preserve original coordinates, axis labels
  and Y multiplicities. The chart opens before recording one transformation; rejected input preserves history.

- Native maximum normalization validates finite matching samples, a positive maximum and finite quotients while
  preserving original coordinates and axis labels. Rejected selections or series produce a visible error without
  opening a chart or recording a transformation. Public legacy normalization and entropy calculations are retained.

- Failed bounded JVM federation tests retain disposable-broker logs for CI diagnostics. KafScale CI artifacts
  include Wayang test reports, retained fixture broker logs and the primary broker log.

- `ConfigManager` rejects unreadable or malformed configuration sources and missing selected files or profiles.
  Each source merges only after loading and closing succeed. Optional absent defaults remain allowed, source
  precedence and live JVM/environment overrides are preserved, and environment mapping is locale-independent.

- Legacy SequenceFile selection uses 1-based physical record positions for callbacks and in-memory results,
  preserving file order and scan limits independently of stored labels. Explicit null selections clear stored
  `BucketLoader` filters; empty selections process no records.

- Native standard normalization preserves original sample coordinates and axis labels and reports invalid series.
  Per-series histograms validate selections, integer bounds, positive bin counts and finite matching samples.
  Both desktop actions prepare all results before opening a chart or recording a transformation; rejected input
  leaves the recorder unchanged. Numerical APIs and native histogram binning are retained.

- ARIMA-family fits use L-BFGS with the Harvey likelihood score and complex-step derivatives.
  Small-amplitude Gaussian fits retain their analytic variance and forecast intervals; the requested
  iteration limit and strict nonconvergence rejection remain enforced.

- Native entropy windows validate lengths and finite matching coordinate/value vectors, advance by at least one
  sample and clip partial cuts to the available data. The `ENTROPY` dialog handles cancellation and invalid
  selections or input without opening a chart or recording a transformation. Native discretization is retained.

- Native table, chart and caption saving reports the failed destination and completed outputs in the dialog.
  Each file is prepared and replaced atomically; a failure preserves that destination and stops later outputs.
  Table and caption text use strict UTF-8, while table layouts and 800 by 600 PNG dimensions are retained.
  Configured folder paths are preserved, including spaces, hyphens and Unicode characters.

- Native `Histogram cross-cut` validates selections, positive integer window lengths, ordered integer bounds and
  positive bin counts. Cancelling or rejecting input leaves charts and the recorder unchanged. Cuts stop at each
  series' available samples while preserving nominal window labels, pooled raw counts and input series.

- Native time-series previews handle empty and short series and disabled status recording. Legacy file exports
  write every point, retain subtype formats and metadata, and report errors while preserving existing files
  through atomic replacement.

- Remove the orphaned InfluxDB/Grafana demo gitlink, which has no `.gitmodules` entry and blocks checkout
  credential cleanup. CI checks that every tracked gitlink has a configured path and URL.

- The PFD runtime reads `group.id` through the existing core configuration manager, retaining
  `opentsx-dynamic-job` when absent and rejecting empty or whitespace-only configured values.

- Quantile representatives use `StrictMath.pow` for identical bits across
  platforms, removing a one-ULP ARM/x86 difference in the shared statistics
  vectors. Sketch bytes are unchanged.
- Streaming deployment rejects unsupported sinks, duplicate node and sink identities, missing inputs, unsupported
  decoders and cycles before publishing control records. Supported sinks are accepted together.

- Continuous Wayang increments retain private preparations after a transient preparation failure, including a lost
  response after preparation was persisted. Identical retry keeps the admitted plan, clock and whole-topic placement;
  activation still requires every preparation and the cross-topic ownership checks. Permanent refusals remain failures.

### Added
- Kafka-native streaming acceptance against KafScale: REST deployment, continued vector output, inventory and status,
  stop, rejection before control publication, and a series checkpoint restart. The existing KafScale CI job runs
  retrieval acceptance and retains its reports. See [the operating guide](docs/devops/kafka-native-streaming.md).

- Iceberg-backed Retrieval opens existing episode and series tables without creating missing namespaces or
  tables. Its store rejects writes, including empty batches, and does not expose a mutable episodes table.
  The existing writer path retains provisioning behavior. The new properties-based reader factory closes its
  owned catalog if table loading fails; the catalog-taking overload leaves ownership with the caller.

- The Python topic reader closes consumers on failures, validates time filters before creating a consumer,
  disables automatic offset storage, and shares one finite timeout across metadata, watermarks and polling.
  Assigned reads tolerate at most 32 nonfatal unsupported FindCoordinator notices within that deadline;
  other broker errors propagate. Records outside the initial per-partition offset ranges are not decoded,
  including records arriving on a completed partition while another partition is still being read.

- The PFD runtime accepts `--out-of-orderness` in milliseconds with a ten-second default and permits zero delay.
  It validates both runtime options in either order and rejects invalid values and duplicates before graph creation.

- The PFD runtime accepts `--window-size` in milliseconds, matching `TimeSeriesAnalysisJob`, with a five-minute
  default. Invalid window values and unexpected trailing arguments fail before graph construction.

- CI verifies recorder-exported PFDs through Kafka and Flink, including completed event-time window values.
  The opt-in `pfd-acceptance` profile retains the descriptor, Flink log and verified result as artifacts.

- The exercise runner uses the Maven classpath, propagates failed assertions and rejects invalid modes.
  Seeded exercise checks recalculate means and test the actual scaling and daily-window behavior.
- Unavailable desktop analysis and modern Java/Scala export buttons are disabled with explanatory tooltips.
  Both trackers label their JSON display action `Show PFD`.
- Local SequenceFile loaders stop at EOF, handle empty buckets and close readers. The file loader reads
  at most the configured record limit. Failed processing stops loading and retains the original error cause.
- Classic bucket workflow entry points `loadViaContext` and `processBucket` throw `UnsupportedOperationException`
  instead of reporting success with empty results. `MacroRunner` propagates the unsupported-load error.
- Classic FFT conversion preserves the input coordinates and the frequency coordinates of FFT results.
  `TimeSeriesObjectFFT` headers, diagnostics and error messages use English; averaged series use the `Mean_` prefix.
- Desktop FFT derives sampling rates from uniformly spaced coordinates and requests an explicit rate for integer
  index axes. Invalid data and rates produce an error; cancelling the rate dialog leaves the flow unchanged.
- A Maven download that failed with HTTP 502 failed the build, because Maven retries only 429 and 503 by default.
  `.mvn/maven.config` now retries a download up to five times, 3 seconds apart, on 429, 500, 502, 503 and 504.
- The bucket analysis CLI honors its input arguments, accepts an optional filename filter and record limit,
  and rejects invalid input. `BucketLoader.setLimit` applies to all three load and process entry points.
- PFD file export, schema and runtime accept Kafka input, centering and log output. Unsupported operations,
  normalization methods and sinks fail explicitly. Topics and brokers require configuration; event-time
  watermarks advance from observation timestamps. The missing Flink connector-base dependency is included.
- The modern desktop tracker starts with an empty transformation tree instead of Swing sample items.
- The Flink null-field aggregation test runs with nullable getter fixtures and preserves its result assertions.
- Z-score normalization rejects malformed, non-finite and zero-variance inputs before producing invalid values.
- PFD log output writes JSON coordinates and values for every window, including fewer than five points.
- Hourly solar observations (`FG_LBERG`, `FD_LBERG`) sat up to almost an hour early (56 minutes at Arkona in
  October). `MESS_DATUM` of that product is the UTC moment at which the hour in true solar time ends (`hh:mm`), and
  the parser cut off the minutes. It now rounds to the nearest full hour.

### Added
- Tests for the weather collection: hourly wind, gusts, pressure, precipitation and solar on excerpts of real DWD
  files; every parameter of `market weather-backfill` against its CDC directory, column, unit and time
  convention; the default stations and the years of the daily jobs.
- CI renders and validates `deploy/k3s/history/` (kustomization and the one-off Jobs) with kubeconform.

## [3.0.1] - 2026-10-16

### Fixed
- `bin/opentsx market load --series <path>` refused an archived series that is not in the catalog, such as
  `dwd-cdc/<station>/<parameter>`. It now takes a path from the archive when it is archived there.
- The `opentsx-tools` image staged with `--with-s3` lacked `dev.failsafe:failsafe`, a runtime dependency of
  `iceberg-aws`, so reading an `s3://` table failed with `NoClassDefFoundError: dev/failsafe/FailsafeException`.
  `stage.sh` adds it, and the image workflow checks for it.

## [3.0.0] - 2026-10-16

### Added
- `bin/opentsx market publish --from-day D --to-day D` publishes only the episodes that begin in that range of
  UTC days (`--to-day` exclusive). A daily job publishes the last complete days instead of the whole year again.
- `bin/opentsx market load` takes the same `--from-day` and `--to-day`.
- Workflow `.github/workflows/image.yml` builds `opentsx-tools` for `linux/amd64` and `linux/arm64` with SBOM and
  provenance. On a tag `v<version>` it pushes `ghcr.io/kamir/opentsx-tools:<version>`; the tag must match the
  project version and be a stable version. On a pull request that changes `deploy/docker/` it only builds.

### Fixed
- `bin/opentsx market load` wrote every episode again on each run, because each run gave it a new random id. It now
  uses the identity of `publish` (series, start, value count), so the Iceberg store skips what it already holds.
- Web workbench in `opentsx-saas-frontend` (route `/workbench`): the Swing bucket analyser in the browser. Load a
  bucket (sample, CSV/TSV, episode JSON, retrieval server), apply the time series operations, inspect every result
  on an oscilloscope view (time base, vertical scale, cursors, trigger, measurements), build the processing flow as
  a graph and export it as `opentsx.dag.v1` Smart Topic definition, Processing Flow Descriptor or Java code. See
  [`EVOLUTION/TASK-013-web-workbench.md`](EVOLUTION/TASK-013-web-workbench.md) for the editor context. The Java processor executes the supported vector and series subset.
- TS inventory: the retrieval server reads the episodes topic of a domain from KafScale (`--kafscale <host:port>
  --domain <d>`) and lists every series with extent, value range, episode labels and the ground truth of the
  scenario as time ranges (`GET /v1/inventory`, `opentsx.ts-inventory.v1`). The workbench has a "TS Inventory"
  dialog: filter by tag, channel, scenario event or text, choose window and resolution, load the selection as the
  bucket. `deploy/k3s`: `RETRIEVAL_SOURCE=kafscale`.
- Vector measuring points (draft): operator `align.vector` brings the results of several nodes onto one clock and
  gives one record per tick and key with one value per component (`opentsx.vector.v1`, JSON schema in
  `docs/datamodel-2026/schemas/`, rules in `docs/datamodel-2026/11`, section 5a). The workbench has the operator as
  "Align to vector": nodes can have several inputs, the scope shows the components on one time base, and the DAG
  export writes the node with all inputs and a vector sink. The Java processor executes source-vector alignment.
- Vector topics are written and read back. The vector processor (`opentsx vectors`, `deploy/k3s`:
  `VECTOR_PROCESSOR=true`) keeps them up to date: it follows the episodes topic, writes a measuring point to
  `tsx.<domain>.smart.<name>.vectors.v1` when every series has passed its tick, checkpoints per sink and reports
  its status. DAGs are deployed and stopped through the control topic `tsx.<domain>.dags.v1`. The retrieval server
  reads the vector topics, so their components show in the TS inventory as series, and it lists the deployments
  (`GET /v1/dags`). The Java operator and the workbench pass the same test vectors
  (`docs/datamodel-2026/testvectors/`).
- Deploying needs an account: the workbench has "Deploy" and "Stop" in the tab "Smart Topic" with the state the
  processor reports, a list of what a deployment needs (ready or missing, step by step) and a button that turns
  the loaded series into a published vector. The SaaS backend checks the account (`POST /api/v1/dags/deploy`, `/stop`; administrators only
  unless `DAG_RUN_ROLE=user`) and forwards with the run token to the retrieval server (`POST /v1/dags/deploy`,
  `/stop`, only with `--kafscale`, `--dag-runs true` and the token `OPENTSX_DAG_RUN_TOKEN`). The frontend image
  does not proxy those write routes. `deploy/k3s`: `RETRIEVAL_DAG_RUNS`, `RETRIEVAL_DAG_RUN_TOKEN` (generated),
  `SAAS_DAG_RUN_ROLE`.
- Workbench: the ground truth of the scenario shows on the scope. Series loaded from the TS inventory bring their
  events (operations, sensor faults) along as bands on the time axis; operators that keep the time axis hand them
  on, and "Segment at threshold" says how many crossings lie inside an event.
- SaaS module, test operation: `TEST_LOGIN=true` (`deploy/k3s`: `SAAS_TEST_LOGIN=true`) lets `admin` with the
  password `admin` sign in as the first administrator; the login page shows it. Off by default.
- Workbench: the "OpenTSx Flow Composer" lists the flows stored in the account in a selection window like the TS
  inventory: filter by text, domain, status or kind, see what a flow reads, computes and publishes and what the
  processor does with it. From there a flow is opened in the editor (several: the first on the workbench, the
  others in tabs of their own), created, renamed, copied, deleted, activated and paused. Activating a flow the
  vector processor can run deploys it, pausing stops it; any other flow is only marked. An opened flow loads the
  series it was built on from the TS inventory, over their whole range.
- Workbench: a deployment belongs to its flow. It is named `<domain>-flow-<id>` (was `<domain>-<track>-smart`),
  so flows of one track no longer replace each other. "Deploy" saves the flow first and marks it active, "Stop"
  marks it paused. A deployment is refused while another running one writes the same topic. Deployments under
  the old name keep running and belong to no flow.
- Workbench: the left side has two tabs. "Input bucket" is the tree of the loaded bucket and the buckets made
  of it; "Smart Topic" (moved there from the inspector) holds publishing, the DAG download and the deployments.
- SaaS frontend: the page after sign-in (`/dashboard`) is the "OpenTSx Flow Composer". It replaces the cards of
  the dashboard by the composer as a whole page: select flows, open them in the editor, create, rename, copy and
  delete them. "Start" and "Stop" (in each row, in the details and for a selection; they replace "Activate" and
  "Pause") run a flow as a Smart Topic on the vector processor. A flow the processor cannot run shows what it
  needs and is no longer marked active.
- Concept and draft for the engineering workflow from system to model
  ([`docs/datamodel-2026/14-ENGINEERING-FLOW.md`](docs/datamodel-2026/14-ENGINEERING-FLOW.md), German): the
  signal as the key between Studio, workbench and Flow Composer, and two draft schemas, `opentsx.system.v1` (a
  whole system: which signal comes from where) and `opentsx.processor.v1` (generators, transformers, models),
  with examples for the system `h2-chain`. Signed off on 2026-10-13 with three grades of a signal (raw, clean,
  features) and an optional guided mode; the implementation follows in delivery stages.
- Signals of a system (TASK-014, stage L1). System documents (`opentsx.system.v1`) live in `systems/`; the
  retrieval server reads them (`--systems`), checks every reference against the twin plan, the channel catalog and
  the inventory, and hands out the state of each signal computed from the TS inventory (`GET /v1/systems`,
  `/v1/systems/<id>/signals`). The web application has the page "Signals" (`/systems`): expectation, origin, state
  and next step per signal. "Prepare" opens the raw series of a signal in the workbench, with a context line and
  the expected range on the scope. `deploy/k3s`: the retrieval server on KafScale serves the documents of the image.
- Shell around a system (TASK-014, stage L2): `/systems/<id>/<place>` with a rail for the four places System,
  Signals, Flows and Models and the workbench as the tool. "System" shows the atoms of the plan with what feeds each
  input (`GET /v1/systems/<id>/plan`); "Flows" is the Flow Composer for the flows of the system; "Models" previews
  which signals are ready for a model. The optional guide shows the seven steps of the engineering method with the
  state of the system in each. A flow built from a signal remembers its system and signal.
- Clean signals (TASK-014, stage L3). The vector processor also runs series sinks (`opentsx.dag.v2`): a chain of
  plausible range, unit conversion and resampling from a raw series to the clean grade of a signal, written as v2
  episodes to `tsx.<domain>.clean.v2` with the tags `system` and `signal`. The workbench has "Publish as clean
  signal" (check, save and start in one step) when it was opened for a signal; the page "Signals" then shows the
  signal as clean. Shared test vectors for the cleaning operators in Java and TypeScript
  (`docs/datamodel-2026/testvectors/clean-ops.v1.json`).
- Plan revised after an external review ([`docs/datamodel-2026/15-ENTSCHEIDUNGSKREIS.md`](docs/datamodel-2026/15-ENTSCHEIDUNGSKREIS.md),
  German, decided on 2026-10-13: stages L4 and L5 are built first, then E1 to E3): the decision as the central unit, with draft schemas `opentsx.decision.v1` (decision
  card) and `opentsx.decision-record.v1` (recommendation, approval, action, outcome) and an example; a streaming
  contract with what holds today and what is the aim (11, section 7a); release of a model for a purpose (05, section
  5a). Signals carry a role, a meaning and an operating range next to the plausible range. Clean episodes carry an
  identity, so a restart is not counted twice. The user manual's chapter 16 is rewritten as eight steps from a
  decision to its effect.
- Binding and simulating signals (TASK-014, stage L4). On the page "Signals" a signal is bound to a series of the TS
  inventory after its meaning was confirmed, or to a demo generator preset from its expectation (`gen.ou`,
  `gen.sine`), which an administrator runs over a period. Generated series go to `tsx.<domain>.synthetic.v2` and
  carry `origin: SYNTHETIC` and `fidelity: demo`. What is set there is a working copy per account
  (`/api/v1/systems/<id>/bindings`) laid over the system document; the page gives the lines for the file.
- Models (TASK-014, stage L5). The series chain of the vector processor has a first model and features:
  `model.power-curve` (wind speed to power by a curve of points), `feature.lag` and `feature.rolling`. A sink may
  read the clean series of one signal and write another signal, with `origin` and `madeOf` in its labels, or
  features to `tsx.<domain>.features.v2`; the workbench has "Publish as another signal" for that. The retrieval
  server reads the decision cards a system document lists and says per analysis whether it is usable, restricted
  or blocked today, with the reasons (`GET /v1/systems/<id>/decisions`): grade, age against the newest data,
  accepted origin, the range the analysis was checked in, and its evidence. The place "Models" shows the cards; the
  place "System" shows the plan as a graph above the table. Comparing alternatives over a period is not built.
- System plan of the H2 chain in a second form (TASK-014, stage S1, after a second briefing; decisions D64 to D66 in
  [`docs/datamodel-2026/15-ENTSCHEIDUNGSKREIS.md`](docs/datamodel-2026/15-ENTSCHEIDUNGSKREIS.md), section 13). The
  twin has six new atoms: `wind-park` (potential, available and generated power, with the energy not generated
  counted per cause), `h2-compressor`, `h2-buffer` with the regulator that feeds the pipeline, `h2-pipeline` with a
  pressure at each end, `h2-handover` (regulating valve, lowest delivery pressure, shortfall) and `h2-consumer`
  (the demand is information, the delivery is gas). The order compressor, buffer, pipeline is an assumption and
  says so in the plan; every simplification is named in its atom. The system document has a signal for every input
  from outside. The scenario now tells: the handover station closes, the buffer fills, the electrolyzer trips at
  200 bar. The first form of the plan is kept for the tests of the twin, and its atoms stay in the catalog.
- Demo of the H2 chain made showable (TASK-014, after the first walk-through in
  [`docs/datamodel-2026/16-DEMO-UND-SIMULATIONSEXPERTEN.md`](docs/datamodel-2026/16-DEMO-UND-SIMULATIONSEXPERTEN.md),
  section 7). The place "System" draws the plan in the phases of a time step, with power, hydrogen and information in
  three colours and late wires dashed, shows what the system document assumes (`assumptions`), follows the working
  copy (`POST /v1/systems/<id>/plan`) and links to the Smart Topics Studio, which the frontend image now serves
  under `/studio/`. The scenario has a rule window (04:00 to 05:00) and a dispatch (05:00 to 05:30), so potential,
  available and generated power differ and the energy not generated shows per cause.
- Runs of the twin on request (TASK-014, run service and run register). The retrieval server computes a run of the
  scenario of a system for the decisions a person asks for (`POST /v1/systems/<id>/runs`: controls set at a time, a
  policy, optionally a forecast that continues the reference), keeps the last hundred runs in memory and hands out
  a run, its series and a comparison of two runs by their identity. Every run names the origin of its inputs and
  its status, which is `restricted` as long as the inputs are those of the scenario. The web application has the
  place "Simulate": a question on the left, the run next to the reference on the right. Also: a signal bound in the
  working copy opens in the workbench, and a flow may read a generated series as the raw grade of its signal.
- `opentsx-market`, weather backfill: six more DWD quantities per station (relative humidity, wind direction,
  10-minute wind speed, the highest gust of the hour, pressure, precipitation) next to temperature, hourly wind
  speed and global radiation. `opentsx-dwd` knows the variable `weather.wind_gust`.
- The year 2025 in the system (TASK-014, stage J1). `bin/opentsx market publish` writes archived series as raw
  episodes into the episodes topic of a domain, in batches with a pause, and stops at the first refusal of the
  broker. The system document of the H2 chain (version 4) binds the day-ahead price to the published series
  (`REPORTED`) and has three signals for the weather of the site from a DWD station (10-minute wind, temperature,
  pressure); a series below an atom may state its own unit and kind without a channel of a catalog. The demand of
  the customer is declared as a demo generator. The night of the scenario is 2025-10-01. A decision measures the
  age of an input against the newest data among the signals it needs, not against every series of the system.
- Draft: notebooks as a further application
  ([`docs/datamodel-2026/17-NOTEBOOK-ARBEITSPLATZ.md`](docs/datamodel-2026/17-NOTEBOOK-ARBEITSPLATZ.md), German):
  load the series directly, look at them, test a new series against what the system expects. Not built.
- Notebooks as an OpenTSx application, stage N1 (TASK-014). The Python package reads the inventory, systems,
  signals, decisions and runs of the twin from the retrieval server, and `TopicReader` reads the episodes of a
  topic directly from the broker, once, without a consumer group and with a limit it does not pass silently.
  Notebook 1 shows the time series inventory, a series with its coverage and gaps, and the price of 2025. The image
  `opentsx/notebook-content` carries package and notebooks into the notebook of a stack (`deploy/k3s/notebook/`:
  a patch for the notebook's Deployment and a network rule for the retrieval server).
- The operator `model.wind-shear` (TASK-014, J1): the wind of a measuring height carried to the hub height by the
  power law, in the processor and the workbench, checked by the shared test vectors. The potential of the park in
  the system document of the H2 chain is now made of the measured wind of the DWD station: cleaned, carried from
  10 m to 120 m, then the power curve of four turbines.
- A derived series carries the flow that writes it (tag `flow`, decision D84). Two flows that write the same signal
  one after the other write two series; the state of the signal counts the series of the flow that runs and names
  the others. Before, a stopped flow and its successor wrote into one series and mixed two periods.
- The year run on history (`bin/opentsx twin --history ... --year 2025`) computes the H2 chain in its second form
  (compressor, buffer, pipeline, handover station) at a 15-minute step, with the potential of the park as its wind
  input and the compressor in the schedule. It prints the pressures of buffer and pipeline, the energy of the
  compressor and the hydrogen not delivered.
- A generated series carries the run of its generator (tag `run`, decision D85): the same generator over the same
  period replaces itself, another period gives another series. The state of a signal counts the run generated
  last (label `generatedAt`) and names the others.
- Runs on the signals of a system (TASK-014, J1). A run of the twin may take its inputs from the series of the
  signals instead of the invented inputs of the scenario (`inputs: "signals"`): each input says where its values
  came from, its origin and how much of the run it covers, and the run is compared with the decisions of the
  scenario on the same inputs. The place "Simulate" lets a person choose.
- Notebooks 2 and 4 for the notebook of a stack (TASK-014, N2): "A signal" puts the raw and clean series of a
  signal next to its expectation and operating range, and shows what the decision says about its inputs; "Ask
  the twin" asks the twin a question on the signals of the system and then the same question over many
  times of day. The Python client asks for runs on the signals and gives the operating range of a signal.
- Notebook 3 "Validate" (TASK-014, N2): tests a CSV file of an operator against a signal (unit, step, time zone,
  meaning of the time stamp, duplicates, plausible and operating range, gaps), puts it next to the series of the
  signal, gives a verdict, writes a prepared copy in UTC and the unit of the signal, and names the commands and
  binding lines. An example file derived from DWD data ships with it.
- User manual, chapter 16: the engineering method from the system to the model in seven steps (define the system,
  list the expected signals, take stock, bind or simulate, prepare, run, model), with the rules that hold across
  them, a worked example, a checklist, and for each step what OpenTSx supports today and what is planned.
- CI: jobs "SaaS frontend" (types, lint, tests, build) and "SaaS backend" (pytest).
- SaaS backend: flows are stored per user in the database (`app/api/flows.py`: create, read, update, delete,
  executions), replacing the placeholder routes. The workbench saves a flow to the account and reopens it from the
  dashboard. `opentsx-saas-backend/smoke-flows.sh` checks accounts and flows against a running instance.
- `deploy/k3s`: optional component `saas` (`SAAS=true`): frontend on node port 30311, backend, PostgreSQL; images
  `saas-frontend` and `saas-backend` (`deploy/docker/`).
- `LICENSE` (Apache License 2.0, full text) and `NOTICE`.
- `CONTRIBUTING.md`, `CODE_OF_CONDUCT.md` (Contributor Covenant 2.1), `SECURITY.md` (reporting via GitHub private
  vulnerability reporting), this changelog.
- `config/opentsx-local.props.example`: localhost / KafScale-compatible Kafka client profile for the 2.x tools.
  Without a local `config/opentsx-local.props` (git-ignored), `OpenTSxClusterLink` falls back to this template.

### Fixed
- Example system `h2-chain`: the available power of the wind farm was bound to the sum of the measured turbine power.
  Under curtailment that is the actual generation, not what the wind offers. They are two signals now: an
  observation (`park.p_gen_kw`) and an estimate from the wind speed (`park.p_avail_kw`).
- Readers on KafScale could skip records when the broker refused a fetch: the Kafka client throws the refusal of one
  partition out of the middle of a poll and drops what it had already taken from the others. `Patience` now puts
  the consumer back to where it stood before the refused poll. Seen as a clean series that lacked its first episode.
- Vector processor: the first write to a new sink topic stopped the whole processor. The KafScale proxy answers a
  produce request that mixes a topic nobody has written to yet with another one in a way the Kafka client cannot
  parse, and the producer then sent nothing any more, not even status records. Every runner now has its own
  producer and the status records have another; waiting for the broker's confirmation no longer blocks the loop,
  and a producer that gets no answer is closed and replaced.
- Readers no longer end when KafScale refuses fetches for a minute (`Unexpected error code 7 while fetching`): they
  wait it out (`org.opentsx.kafka.Patience`, used by `LogReader`, the statistics processor and `SeriesStatsLog`).
  Producers retry with pauses for up to three minutes instead of five quick attempts
  (`KafScaleClients.producerProps`). The statistics processor used to restart on every such window.
- Retrieval server: a vector topic the broker does not hand out no longer keeps the feed from reading the other
  topics.
- SaaS frontend: the login page showed the default administrator password of a local backend on every
  installation, also where the deployment generates another one. The hint now appears in the dev server only.
  A wrong password reloaded the page instead of showing the error; the form now reports it.
- SaaS backend: registration failed against a real database (`MissingGreenlet`), every authenticated request
  answered 401 (numeric token subject), and a refresh token was accepted as access token.

### Changed
- Default 2.x client profile is now `./config/opentsx-local.props` (was `./config/cpl.props`); scripts, the
  `opentsx-lg` README and `opentsx-default.properties` updated.
- `opentsx.properties.example` lists only keys the code reads and documents how the v2 modules are configured
  (command-line options and environment variables).

### Removed
- Archived Maven modules (`archive/modules/`, `archive/opentsx-kstreams/`) removed from `master`: not built, but
  their outdated dependencies kept raising security alerts and Dependabot PRs. Last state: commit `315f01d`.
- Four dead submodule links in `archive/demos/` (no URL in `.gitmodules`).
- `config/cpl.props`, `config/cpl_iMac.props`, `config/cpl_local.props`, `config/mdc_c1.props` (client profiles with
  private LAN hosts).

## 2026-10-12

### Added
- Digital twin proof of concept `opentsx-twin` (P9): atom contract with model, views, dynamics and policy; wiring
  plan in YAML; layered bulk-synchronous runner; provenance ladder; shadow, what-if and forecast modes. Scenarios:
  energy system / H2 chain (`bin/opentsx twin`), family house with calibration against bills
  (`bin/opentsx house`, `--simulated`), sailing boat Etap 22 with calibration against logger data
  (`bin/opentsx boat`, `--simulated`).
- Smart Topics Studio prototype (`docs/prototypes/smart-topics-studio.html`) and the LabVIEW / command-loop concept.
- Studio guide (`StudioGuide`, `twin/haus-erzgebirge.guide.yaml`, `twin/h2-chain.guide.yaml`): prose for every atom,
  port, parameter, control and wire of the house twin and the H2 chain, where each value comes from and references to compare against; checked against the plan.
  The node graph explains wires and ports on hover, and each node takes own details (nameplate, serial number, own
  parameter values) with export as inventory (`opentsx.inventory.v1`) and as an own wiring plan.
- Smart Topics Studio redesigned as a product component after reviews from four viewpoints (UI design, UX and
  accessibility, electrical and energy engineering, commercial reader): app bar with scenario switch, scenario summary
  with headline figures and the question behind it, overview, comparison, system and time-series views, inspector
  beside the diagram, connections coloured by energy carrier and routed around the blocks, events in plain language,
  larger units from a threshold on, limit lines in charts, a switch for technical details. Engineering corrections
  in both guides.
- Smart Topics Studio as a tool shell: title bar with menus (File, View, Scenario, Help), icon rail on the left
  that expands and collapses, scenarios as document tabs, detail panel for blocks and connections sliding in from the
  right, status bar. Keyboard: Alt+1 to Alt+4 for the views, Ctrl+B for the rail, Esc closes the detail panel.
  The document tabs belong to the current scenario: the scenario itself, a link to its page in the asset explorer,
  and an event topic view.
- Event topic of a twin run (`opentsx-twin-live`): `EventPublisher` writes the events of a run to
  `twin.<id>.<run>.events` (key: atom, value: JSON `twin.event.v1`), at once or played over a given duration;
  `EventBridge` serves such a topic to browsers as server-sent events; `bin/opentsx twin-live publish|bridge|demo`.
  The Studio follows the topic live through the bridge and keeps a replay that needs no broker. The publisher creates
  the topic before the first event, so a reader can connect while the run has not reported anything yet.
- k3s component `event-bridge` (`EVENT_BRIDGE=true`): the event bridge as Deployment and Service on NodePort 30312;
  `twin-live` is part of the tools image and takes the broker from `KAFSCALE_BOOTSTRAP`.
- Retrieval (`opentsx-retrieval`, P5): segmentation, pattern engine (MASS, SAX, DTW), retrieval API, REST server,
  Python client, power-curve analysis.
- DevOps guide for k3s against an existing KafScale cluster (`docs/devops/`, `deploy/k3s/`, `deploy/docker/`).
- Design documents: Smart Topics (05), interplay & coordination potential (06), digital twin (07), house (08),
  Studio & LabVIEW (09), boat (10), Smart Topic processor & DevOps (11).
- New English user manual (`docs/user-manual/`), documentation inventory, launcher `bin/opentsx`, link check in CI.

### Changed
- 2026 refresh merged into `master` (#102); SaaS frontend/backend dependencies bundled (Vite 7, python-multipart,
  python-socketio).
- README repositioned ("from time series to decisions").

### Removed
- Outdated documentation and modules outside the build moved to `archive/`; `opentsx-app-tools` archived.

## 2026-10-11

### Added
- Streaming statistics and data quality `opentsx-stats` (quantile sketch, DQ, KafScale processor with checkpoints)
  and `opentsx-openmetadata` (metadata pool, validated against the OpenMetadata 2.0.5 schemas); Python
  `opentsx.stats` (P4).
- Wind-farm domain `opentsx-windpark`: IEC 61400-25-2 data-point catalog, normalizer, simulator with ground truth,
  CLI to KafScale / Iceberg.
- Store SPI with contract suite, Iceberg store (index, log sync), OpenTSDB adapter, PyIceberg reader with byte
  equality between Java and Python (P3).
- `opentsx-kafka-v2` (topic layout, bucket writer, episode index, replay, catalog) and `LegacyBucketImport` (P2);
  end-to-end tests against KafScale v1.6.0 and Apache Kafka 3.9.1.

### Changed
- Avro 1.12.1 managed centrally (precision bug in 1.12.0 covered by a test).

## 2026-10-09

### Added
- Data model v2: `opentsx-model` (Java) and `opentsx.model` (Python), byte-identical via shared test vectors;
  `timestamp-micros` (D2) and xxHash64 series identity (D3); legacy bridge; schema history with compatibility check
  (P1).
- KafScale conformance suite (P0): headers and CreateTime are preserved; `offsetsForTimes`, the idempotent producer,
  standalone commits and consumer-group rejoin do not work on KafScale v1.6.0.
- Analysis of the existing model, target model, phase plan and gap analysis (`docs/datamodel-2026/`).

## Before 2026

| Years | Phase |
|-------|-------|
| 2013–2018 | Hadoop.TS: Hadoop, HBase/OpenTSDB, Spark; research on Wikipedia and financial time series |
| 2018–2020 | renamed to OpenTSx; Kafka, Kafka Streams |
| 2020–2025 | Confluent Platform/Cloud, ksqlDB, multi-region demos, SaaS prototype |
