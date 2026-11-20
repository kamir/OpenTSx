# Exercise the public library operator families

Use these contracts with OpenTSx 3.1.0. The learning examples were checked with
JDK 21.0.8, Maven 3.9.8 and Python 3.12.9.

You need the built Java module for a Java example, or the local `python-package` with its declared dependencies
for a Python example. The [lesson manifest](library-operators.v1.json) contains the inputs, tasks and evidence links.
These families supplement the UI operator lessons. Helper methods belong to their family rather than becoming
independent operators. A library implementation does not imply a deployed DAG, Wayang mapping or Rust kernel.

The current Java Wayang plugin separately registers clean-chain and
window-statistics mappings through
[`TsxOperatorMappings`](../../opentsx-wayang/src/main/java/org/opentsx/wayang/TsxOperatorMappings.java).
`TsxSeriesChain` delegates the seven clean operations to `SeriesStream`;
`TsxStatsWindow` delegates statistics to `StatsEngine`. The [`clean
vectors`](../../opentsx-wayang/src/test/java/org/opentsx/wayang/TsxCleanVectorsTest.java)
check clean values through an actual Wayang plan. The separate
[`TsxRetrievalMappings`](../../opentsx-wayang/src/main/java/org/opentsx/wayang/TsxRetrievalMappings.java)
register threshold/extremum segmentation, SAX annotation and data-quality
findings. The
[`TsxJavaPlugin`](../../opentsx-wayang/src/main/java/org/opentsx/wayang/TsxJavaPlugin.java)
also identifies the implemented vector and pair mappings. A numerical kernel
alone remains insufficient evidence of a mapping.

The local [`DagToWayang`](../../opentsx-wayang/src/main/java/org/opentsx/wayang/DagToWayang.java)
compiler validates the frozen inventory and processor parameters, preserves the
original text hash and constructs all 16 native catalog bindings. Its graph
builder supports episode, statistics, vector and signal sinks. Clean runs remain
fused; metadata and series inventories needed to build a graph are explicit
bindings, rather than guessed identities.
[`DagToWayangCatalogTest`](../../opentsx-wayang/src/test/java/org/opentsx/wayang/DagToWayangCatalogTest.java)
executes those bindings and checks replica/state admission, product budgets and
separate local versus federated statistics edges. Explicit compiler context
does not grant remote execution rights. The separate
[`tsx-runner` no-context profile](../devguide/integration/rust-runner-local.md)
executes pass-through, plausible-range checks, unit conversion and a trailing
resample. The [B2 catalog context](../devguide/integration/rust-runner-b2.md)
adds wind models, lag/rolling features, typed statistics, segmentation, SAX,
alignment, bounded cross-correlation
and DQ findings through admitted local execution. Shared Rust regressions
exercise the same committed vectors alongside the Java proofs. Private state
formats remain distinct. The separate
[local UI rollout](../devguide/integration/wayang-rust-compose.md) uses `RustRollout` with `DagToWayang.parameters`
and a custom Rust platform. Its logical sources, union, fused clean chain and sink form one Wayang stage; complete
fragments run on HTTP workers. The UI rollout continues to admit its own
smaller clean subset; B2 availability does not expand that UI admission.
The [continuous local target](../devguide/integration/wayang-rust-continuous.md) executes successive finite cuts on
the same whole-topic bindings. Source/sink/checkpoint/assignment lessons also reference its durable predecessor,
cumulative ownership and restart tests. Their standalone workloads remain unchanged and do not deploy a service.
Continuous preparation recovery retains private candidates after a transient failure, including a lost response.
Identical retry still requires every preparation and cross-topic ownership check before activation.
The [isolated signed Rust run](../devguide/integration/rust-runner-b1.md) is
another admission profile: continuous Episode pass-through only, with a pinned
Ed25519 driver key and local bindings/policy. It does not expand the cleaning
lessons or the available Rust operators. D-T14 requires no ARMv7 hardware.

The [qualified bounded Rust site profile](../devguide/integration/rust-runner-b3.md)
adds signed decode-to-statistics admission with its separate local
configuration and explicit qualified runtime pin. It binds exact assignment
text, artifact, catalog/state ABI, membership, scopes and complete input cuts.
Owner-local actual test/protocol/placement evidence precedes fresh job
metadata, retained-cut, fingerprint and complete-series checks. Ordinary site
processes have no admin or arbitrary probe-topic rights. The infrastructure
and statistics lessons trace durable delivery acknowledgement, site outboxes,
offline forwarding, final revisions and certificate-forced SSH launch. Their
starters, numerical values and processor defaults stay unchanged. Signed B3
does not admit the remaining B2 operations or qualify production/mixed continuous fleets.
The [bounded mixed W3 guide](../devguide/integration/wayang-mixed-federation.md)
adds traceability from two Java sites and one actual qualified Rust CLI to
conformance, runtime-bound inventory admission and full record comparisons.
The separate exact-bit sketch harness records boundary, zero-bucket and
collapsed-tail domains. Its execution evidence retains the existing moments
criterion; the harness alone does not establish a new numerical bound.
The physical ARM64 B4.2 measurement remains open (D-T14). The board tool
prepares 81 unassigned conditions across pass-through, clean and statistics
chains (D-T15) and validates supplied evidence without running device
measurements. The
[synchronization regression](../../tools/test_operator_exercise_sync.py)
compares the statistics lesson's condition count with the executable plan.

The [local publication contract](../datamodel-2026/20-WAYANG-TS-OPERATORS-SPEC.md#s112-fence-local-publication-and-replace-a-continuous-deployment)
adds explicit same-DAG resume and changed-DAG replay into a fresh output after old authority is revoked.
Terminal recovery saves verified results and publication epochs before retiring authority. The checkpoint lesson
traces a lost retirement reply: recovery reuses the saved result and operation without another execute call or
early topic release. The same persistence boundary applies to Stop during the first continuous cycle.
These gateway/worker lifecycle checks do not change `TsxCheckpoint.load`, the offline assignment validator or
any numerical starter, and do not make private Java and Rust state interchangeable.

The [signed bounded JVM profile](../user-manual/17-learn-operators.md#follow-the-signed-bounded-jvm-statistics-profile)
binds an immutable whole-topic assignment to a statistics fragment and one
SeriesStats sink. `TsxPlanService` persists signed deliveries and admits
job-bound conformance; `TsxPlanClient` uses certificate-authenticated TLS 1.3.
`TsxSiteAgent` retains the accepted command, outcome and cursor.
`TsxBoundedSiteExecution` compiles the admitted cut and publishes every declared
aggregate scope before saving its compatible continuation.
`TsxSitePlatform` and `AggregateChannel` connect the complete action to the
driver. The application supplies signing keys, trust material and local policy.
The integration harness executes the shared checks with ephemeral certificates.

The finite forecasting and preparation examples use the scientific runtime
pinned by the [local CP profile](../devguide/local-cp-workbench.md). They need
the Python package's `forecasting` extra; direct library calls do not need
Kafka or MLflow. The separate
[reviewed statistical deployment](../devguide/model-smart-topics.md) has its own
admission, availability and fitted-state contracts.

## Forecast finite ARIMA families

`forecast_arima` fits finite regular, nonconstant real input through statsmodels
SARIMAX with stationarity and invertibility enforcement. ARIMA orders,
seasonal orders, trend, horizon, alpha and optimizer iterations are explicit.
Fitting uses L-BFGS with the Harvey likelihood score and complex-step
derivatives, the requested iteration limit and a strict convergence check.
There is no alternate optimizer retry. The small-amplitude Gaussian regression
checks the fitted variance, conditional intervals and likelihood against their
analytic values at three signal scales.
Inputs need at least eight observations and enough samples for the requested
orders and regressor columns. Exogenous matrices must be finite, aligned and
full-column-rank during training; future rows must match the horizon and column
count. Invalid configuration and nonconvergence fail explicitly.

Output contains forecast means and conditional Gaussian lower/upper bounds,
fitted parameters and likelihood measures, input provenance, runtime version
and warnings. Regular v2 episodes retain their unit/identity and supply the
next-sample microsecond timestamps. The seeded ARIMA(0,1,0) lesson has a
last-value mean and interval widths proportional to the square root of the
horizon. The call fits again on each invocation; preserved-artifact reuse and
context-availability proof are separate contracts.

## Compare finite reference forecasts

`forecast_baseline` supports mean, naive, drift and seasonal naive. Positive
integer horizons, finite input and alpha in (0,1) are required. Constant input
is permitted. Seasonal naive additionally needs a period of at least two and
below the training sample count. Output includes means, conditional Gaussian
bounds and explicit degenerate interval status when lower equals upper.

The ramp lesson derives all four point forecasts analytically. The constant
lesson has zero-width bounds; this describes the training-based estimate and
does not establish certainty about real future values.

## Evaluate rolling forecast origins

`rolling_backtest` uses complete chronological training/test folds. Initial
training and optional sliding windows contain at least eight samples; the
window cannot exceed the initial length. Methods are a nonempty unique subset
of the supported baselines and ARIMA. Seasonal period is smaller than every
training window. Transform lambdas and scaled-error denominators are estimated
inside each training fold. Zero scales are reported as undefined.

Output preserves fold ranges, training digests, held-out cases, aggregate and
per-horizon metrics. The ramp workload has three origins and nine cases per
method. Mutating observations after the first test horizon leaves that origin
and its cases unchanged, while later predictions can change. Input exogenous
matrices alone do not prove origin availability or historical forecast vintages.

## Decompose offline seasonality

`seasonal_decomposition` selects STL for one period and two-iteration MSTL for
multiple periods. Periods are distinct integer sample counts of at least two,
strictly smaller than half the finite, regular, nonconstant input length.
Explicit seasonal windows match the period count and are odd integers of at
least seven. Output periods are sorted with their windows.

Trend, each seasonal component and residual reconstruct the input; seasonally
adjusted values omit the seasonal sum. The method records `causal: false`:
centered offline smoothing must not become an origin-available historical
forecast feature without a separate causal preparation policy.

## Apply finite power transformations

`power_transform` supports positive-only Box-Cox and signed Yeo-Johnson input.
A fixed finite lambda permits one or more finite samples. Estimating lambda
requires at least eight nonconstant samples. The result records lambda,
whether it was estimated, transformed values and `value_space: power_transformed`.

The Box-Cox lambda-zero lesson equals the natural logarithm. Yeo-Johnson at
lambda one preserves signed values. Fit lambda only on training data and carry
the same parameter into future and inverse transformations; do not silently add
an offset to invalid Box-Cox input.

## Invert finite power transformations

`inverse_power_transform` requires the forward method and saved finite lambda.
It validates inverse-domain boundaries and returns finite original-space
values with `value_space: original`. The lesson covers Box-Cox lambda zero and
both Yeo-Johnson logarithmic-limit branches at lambdas zero and two.

Round trips preserve the original samples within the stated numerical
tolerance. Inversion is not a new parameter fit, bias correction, uncertainty
calibration or deployed runtime binding.

## Analyze DFA

Use exported `DFA(polynom_order=1).analyze(values, scales=scales)` or pass a `TimeSeriesObject`.
Choose explicit scales that fit the input. The result contains `scales`, `fluctuations` and the fitted `alpha`.
With the fixed signal in the lesson, multiplying amplitudes by three multiplies fluctuations by three while
preserving the fitted exponent. An offset is removed by detrending. This exercise checks that invariant, not a
claim that one short signal establishes long-range dependence.

## Analyze MFDFA

Use exported `MFDFA(polynom_order=1, q_range=q_values).analyze(values, scales=scales)`.
`Fq` has one row per `q_values` entry and one column per scale. Select q=2 using the explicit q array and compare
that row with DFA fluctuations. `tau_q` at q=0 is -1. The current `h_2` result selects the middle array element;
do not use that field as a q=2 lookup for an arbitrary custom q range.

## Compare event synchronization

Use exported `EventSynchronization(tau_max=2).analyze(first, second, threshold1=5, threshold2=5)`.
Thresholds in this example select planted spike events. Results include both event lists, overall synchronization,
directional synchronization and `leader`. A lag must be strictly below the permitted local window and cap.
For the lesson's one-tick lag, cap two accepts the events and cap one rejects their nonzero separation.
Surrogate helpers and the matching Java `TsxPairEs` kernel belong to this family.
The shared `pair-es.v1.json` has four sampled-series cases and four event-list-only
cases; all eight run on the kernel. The native Java `TsxPair` operator executes
the four sampled cases through Wayang, once together and once with B retained
in `TsxState` before A arrives after state restoration in a later increment.
`pair.es` version 1 belongs to the
[frozen catalog](../../systems/catalogs/tsx-catalog-1.yaml). The local compiler
also lowers that operator. A compiled Java graph remains separate from a
deployed UI runtime binding or remote execution admission.

For sampled pair windows, `tauMax` is in seconds and zero means uncapped; the
operator converts it to aligned tick units before calling the kernel. A
whole-number seed uses its value modulo `2^32` for Mulberry32. Labels retain
the original workbench numeric text. The native regression methods
`largeSeedsUseTheSameUnsigned32BitStateAsTheWorkbench` and
`settingsLabelsKeepWorkbenchNumberFormatting` check large seeds and boundary
number formatting. See the
[native API instructions](../user-manual/11-classic-analytics.md#run-sampled-event-pairs-through-wayang).

## Measure return intervals

Use exported `RIS(threshold=5).analyze(values)` and `survival_function(return_intervals)`.
The planted events at indices 1, 3, 7 and 13 yield intervals 2, 4 and 6, with mean and median four.
The empirical survival values are 1, 2/3 and 1/3. Those sample-index intervals have no physical time unit until
the sample period is supplied. Three intervals are insufficient for the implementation's fitted tail model;
zero fit parameters in this case are not evidence for a scientific tail law.

## Normalize retrieval inputs

`ZNorm.of(values)` returns a new array, using population standard deviation over non-NaN values.
A near-constant input becomes zero; NaN entries also become zero in this normalized representation.
Do not confuse that behavior with preserving missing-data flags or with the UI's sample-standard-deviation scaling.

## Approximate with PAA

`Paa.of(values, width)` computes weighted means at fractional segment boundaries and skips NaNs.
The lesson's five values and width two produce 1.6 and 6.4. Empty inputs and nonpositive widths reject.
Choose a width no larger than the input for the documented dimensionality-reduction exercise.

## Encode SAX words

Pass already normalized values to `Sax.word(values, width, alphabet)`.
The alphabet range is 2 through 26; the stored form includes width and alphabet, such as `2x4:ad`.
`Sax.mindist` requires compatible word formats. Its lower bound supports pruning; it is not a replacement for
the exact final distance. Normalization, PAA and breakpoint helpers are part of this family workflow.
[`sax-word.v1.json`](../datamodel-2026/testvectors/sax-word.v1.json) is replayed by
[`SaxWordVectorsTest`](../../opentsx-retrieval/src/test/java/org/opentsx/retrieval/SaxWordVectorsTest.java).

The registered `TsxSaxWord(w,a,specHash)` normalizes the episode values by
default. `TsxSaxWord.normalized(...)` skips that step for already normalized
input. Both fill `EpisodeSummary.sax`, preserve raw values and timestamps, and
derive an episode ID from the series, time bounds and supplied hash. The source
episode becomes the parent. `TsxSaxVectorsTest` runs all seven shared cases
through actual Wayang plans. An episode is atomic input; splitting its values
into new episodes changes the SAX workload.

## Compute a MASS distance profile

`Mass.distanceProfile(query, series)` returns one z-normalized Euclidean distance per candidate window.
For a valid query and series, the output length is `series.length - query.length + 1`.
Windows containing NaN receive positive infinity. An identical nonconstant shape has distance zero within
floating-point tolerance. The lesson uses fixed arrays and checks known matching windows rather than timing claims.

## Compare DTW distances

`Dtw.distance(query, candidate, band, cutoff)` consumes arrays and an integer band in sample positions.
Band zero fixes the diagonal; a wider band permits time stretching. `Distance.dtw(fraction)` is a separate
search configuration with a fraction in [0,1]. Lower-bound and envelope helpers belong to this family.
Callers performing normalized search supply normalized values or use `PatternSearch`.

## Search across episodes

`Run.of(episodes)` assembles contiguous per-series runs and preserves episode/offset mappings.
`new PatternSearch(query, Distance.zNormalizedEuclidean(), topK).search(runs)` returns `PatternMatch` records.
Queries need at least four finite points. Matches can cross episode boundaries, while reported episode IDs and
offsets identify the starting sample. Exclusion rules suppress overlapping hits; gaps split runs or invalidate windows.

## Segment by duration

`Segmenter.fixedDuration(duration)` creates epoch-aligned windows; the overload accepts an epoch offset.
Use `Segmentations.segment(episodes, strategy)` for bounded input, or `StreamingSegmenter` incrementally.
Batch input flushes the final partial duration window. Four-second windows over seven one-second samples have
four samples followed by three. Segments preserve strategy metadata and deterministic output identity.

## Segment by count

`Segmenter.fixedCount(count, overlap)` requires count at least one and overlap from zero through count minus one.
Four-sample windows with overlap two advance by two samples. Incomplete final count windows are dropped,
unlike partial duration windows. The lesson's seven samples therefore yield two complete windows.

## Segment around extrema

`Segmenter.extremum(feature, prominence, window, distance, pre, post)` selects maxima or minima.
Prominence and distance control admission; `pre` and `post` describe the inclusive neighbourhood of the anchor.
The fixed lesson peak produces values [1,4,1] anchored at three seconds. Streaming output waits for the required
lookahead; an observed high value alone is not yet a finalized segment.
[`segment-extremum.v1.json`](../datamodel-2026/testvectors/segment-extremum.v1.json) is replayed by
[`extremum vectors`](../../opentsx-retrieval/src/test/java/org/opentsx/retrieval/SegmentExtremumVectorsTest.java).

The registered `TsxSegment` mapping also accepts this strategy. Its incremental
constructor saves buffer, source and detector state under the supplied node ID;
`TsxSegment.bounded(...)` flushes completed input. `TsxSegmentVectorsTest` and
`TsxStreamEqualsBatchTest` replay the shared extremum cases through Wayang with
state restored between chunks. The `TsxIncrement` plan callback supplies the
state object.

## Segment threshold phases

`Segmenter.threshold(direction, enter, exit, pre, post, minDuration, maxDuration)` applies hysteresis.
For `ABOVE`, exit cannot exceed enter. Zero maximum duration means unlimited.
State crosses source episode boundaries. The learning test feeds a phase in two episodes and verifies exact
agreement with bounded segmentation, including anchor and [0,5,5,2] output values.
[`segment-threshold.v1.json`](../datamodel-2026/testvectors/segment-threshold.v1.json) covers the Java hysteresis
contract and is replayed by
[`threshold vectors`](../../opentsx-retrieval/src/test/java/org/opentsx/retrieval/SegmentThresholdVectorsTest.java).
It does not define the browser's simpler sample-neighborhood threshold operator.

The same registered `TsxSegment` mapping accepts the threshold strategy.
`StreamingSegmenter.writeState` and `readState` retain active phases, pending
output, retained samples, quality and source references. The checkpoint tests
preserve raw NaN bits and reject incompatible or unreadable state without
replacing live state. Snapshot support for threshold and extremum does not
establish fixed-count or fixed-duration mappings.

## Compute window statistics

`StatsEngine` accepts episodes or observations, closes event-time windows and emits `SeriesStats`.
Configure explicit windows and expected sampling intervals. Duplicate episode IDs retained in the bounded
`Config.dedupCapacity` history do not add values twice;
`flush()` closes open windows for bounded input and also emits changed global statistics.
Counts, NaNs, axes and gaps have exact checks. Floating-point reductions and DDSketch quantiles require their
declared numerical criteria; the zero bucket and collapsed tails do not support an unrestricted relative-error claim.

[`stats-window.v1.json`](../datamodel-2026/testvectors/stats-window.v1.json) is replayed by
[`StatsWindowVectorsTest`](../../opentsx-stats/src/test/java/org/opentsx/stats/StatsWindowVectorsTest.java).
The registered Java Wayang `TsxStatsWindow` uses `StatsEngine.Config` and accepts episodes or observations.
The three-argument constructor is incremental: it emits windows closed by input progress and saves engine state
by node ID in `TsxState`, without final flushing. `TsxStatsWindow.bounded(...)` calls `engine.flush()`, including
changed GLOBAL statistics. `TsxStatsWindow.window(...)` also flushes, then returns only the requested aligned
series windows, constructing an empty record when a requested window has no data; it rejects saved incremental
state. The direct library starter calls `emitGlobal()` explicitly. The browser's
`stats.window` projection keeps its own `window`/`field` view contract. Its
deployment exporter writes the selected width as catalog `windows` and the sink
as full `org.opentsx.model.v2.SeriesStats`; `field` remains in the saved flow.
The compiler rejects legacy `window` or `field` parameters instead of silently
choosing its default windows. See
[`TsxStatsWindowTest`](../../opentsx-wayang/src/test/java/org/opentsx/wayang/TsxStatsWindowTest.java)
for sketch-byte and cross-increment state checks. The
[`Wayang statistics vectors`](../../opentsx-wayang/src/test/java/org/opentsx/wayang/TsxStatsVectorsTest.java)
replay all five shared cases through actual plans, including bounded and explicit-window modes.
The compiler's admitted statistics boundary keeps all configured tumbling widths
and excludes `GLOBAL` only on the outgoing boundary edge. A local sink sharing
that statistics node retains its global record. A signal collector preserves
local DQ findings without merging their upstream statistics.
The bounded federation harness executes the complete W3 workload through both
signal-cluster and authenticated job-state routes. It compares complete records
and all six merge orders on received partials. Missing-site recovery preserves
incomplete records and appends final revisions after coordinator restoration.
Read the approved bounded W3 mean/M2 criterion in
[SPEC S4.3](../datamodel-2026/20-WAYANG-TS-OPERATORS-SPEC.md#s43-merge-functions)
and the actual cases in
[`FederatedStatsIntegrationTest`](../../opentsx-wayang/src/test/java/org/opentsx/wayang/FederatedStatsIntegrationTest.java),
[`JobStateRouteIntegrationTest`](../../opentsx-wayang/src/test/java/org/opentsx/wayang/JobStateRouteIntegrationTest.java)
and [`CompletenessIntegrationTest`](../../opentsx-wayang/src/test/java/org/opentsx/wayang/CompletenessIntegrationTest.java).

## Evaluate data quality

`DataQuality.evaluate(stats, expectation)` evaluates statistics without reading raw samples.
Checks include completeness, plausible range, flatline and gaps. Freshness additionally requires a maximum age
and an explicit `nowMicros` in the three-argument overload. Use fixed time in examples and tests.
Distinguish an implausible measurement from a valid measurement outside the system's operating range.
[`dq-result.v1.json`](../datamodel-2026/testvectors/dq-result.v1.json) is replayed by
[`DqResultVectorsTest`](../../opentsx-stats/src/test/java/org/opentsx/stats/DqResultVectorsTest.java).
These checks evaluate `SeriesStats`; they are distinct from the raw-value `dq.checks` clean-chain transform.

The registered `TsxDqResult` evaluates selected checks with an explicit optional
clock and emits `opentsx.signal.v1` JSON. Messages use `Locale.ROOT`; the
German-default-locale regression checks the same punctuation as the shared
vectors. A non-finite library result becomes JSON `null`. Each finding keeps its
actual window-end event time. Its deterministic ID includes the check and window
bounds in addition to job, site, series, kind and event time, so different
checks and window widths remain distinct. The signal schema and S6.2 of
[specification
20](../datamodel-2026/20-WAYANG-TS-OPERATORS-SPEC.md#s62-deterministic-ids)
define that contract. `TsxDqVectorsTest` replays all seven shared cases through
Wayang. A whole `SeriesStats` record is atomic input. The catalog compiler also
lowers a statistics-to-DQ graph to a JSON signal sink; remote activation requires
its separate admitted deployment profile.

## Decode Avro records

`OpenTsxAvro.encode(record)` and `decode(bytes, Episode.class)` preserve the v2 record contract.
The default wire frame is Avro single-object encoding, including its schema fingerprint.
Shared wire vectors check bytes, not only decoded values. Register or resolve an older writer schema explicitly;
an unknown fingerprint is not permission to decode with an arbitrary current schema.

The separate Rust `wire` module encodes and decodes Episode SOE records only.
Its shared wire cases require byte equality and preserve the complete Episode
fields. `model::series_id`, `xxh64_hex` and `bucket_checksum` consume the same
identity vectors as Java; time-axis tests cover the repository's shared delta
cases. `bucket_checksum` sorts unique episode IDs, joins them with a newline,
and hashes the UTF-8 bytes with xxHash64 seed zero, matching Java
`Buckets.checksum`. These are public identity helpers, not additional DAG
operators. The lesson's Java starter does not execute or authorize a signed Rust
runner.

## Construct a whole-topic source

`TsxTopicSource(new TsxRead(...))` represents one topic and its internal bounded offset ranges.
Constructing the logical source performs no broker replay. The Java execution operator uses manual assignment,
schema-fingerprint checks and no consumer-group offset commits. The physical learning assignment owns the whole
topic; internal ranges never authorize placing its partitions on different nodes.
Offline wire/settings tests and disposable-broker integration are separate checks.
`RustTopicSource` is a separate logical source for the bounded remote stage. Its topology channel does not deliver
raw episodes to Java; the assigned Rust worker reads every internal partition of the frozen whole-topic cut.

## Construct a topic sink

`TsxTopicSink(bootstrap, topic)` represents an output topic in a Wayang plan.
The Java sink uses the KafScale producer settings, series-ID keys and acknowledgements.
Constructing this object is not a successful write. Wire conversion and client-setting unit tests run offline;
end-to-end production and crash tests need a disposable broker. Acknowledgements do not provide exactly-once delivery.
The remote `RustTopicSink` names the shared output contract while HTTP workers publish the records. All workers
prepare privately and the stage checks derived-series ownership before activation. One worker can acknowledge output
before another fails; neither a shared sink nor a successful Wayang stage supplies atomic multi-worker publication.

The sink's `Object` channel accepts supported Avro records or validated
`opentsx.signal.v1` JSON, with a series-ID key and schema header. JSON signal
batches are fully validated before sending any member. Avro records remain
streamed: an upstream failure can leave a written prefix that recovery repeats
identically. `TsxTopicSinkStreamingTest` checks both behaviors without a broker;
`TsxSignalSinkIntegrationTest` verifies JSON publication and an invalid batch
with zero writes on a disposable broker. JSON signals are not Avro records.

## Persist an increment checkpoint

Rust worker and checkpoint owners explicitly unlock when they close in the
acquiring process, including error returns. An inherited descriptor cannot
postpone reacquisition, and a copied owner in another process cannot unlock its
parent. The deterministic owner-drop regressions retain duplicated descriptors
while checking live-owner exclusion, restart and successor exclusion. These
Rust lock lifetimes do not change the Java checkpoint starter or its state
format.

`TsxCheckpoint(job, planVersion, specHash, topic, next, state).write(path)` persists next offsets and per-node
operator state to a temporary file,
forces it, and atomically renames it. `load(path, hash)` returns empty for absence or a different hash, and rejects
an unreadable file. These are the current local increment semantics.
The starter passes `Map.of()` as its sixth argument. Nonempty state is covered by
[`TsxCheckpointTest`](../../opentsx-wayang/src/test/java/org/opentsx/wayang/TsxCheckpointTest.java): clean chains
store `SeriesStream.state()` JSON and statistics store Base64 engine state in the per-node map.
They do not implement the proposed orchestration state-ABI migration, fenced ownership or deployment grant policy.
Each assigned whole topic retains all of its internal positions; the checkpoint file is not a partition scheduler.
The Rust HTTP worker uses its own checksummed completion envelope and retained pipeline state. It commits after all
output acknowledgements. The local fenced mode keeps a captured grant in the prepared journal and verifies a
gateway receipt before commit. A durable gateway acknowledgement supports receipt retry; an unknown broker
outcome remains quarantined and blocks handover. Completed retry returns the saved receipt. This does not change
`TsxCheckpoint.load` or make its state portable.

For threshold and extremum segmentation, the per-node map contains Base64
segmenter state. It includes retained source records, timestamps, raw sample
bits, quality and pending detector work. The direct checkpoint starter remains
an empty-state example; segmentation and Wayang vector tests separately exercise
nonempty snapshots, restoration between chunks and bounded final flushing.

The signed JVM site checkpoint is a separate continuation contract. It binds
the exact admitted grant, generation, artifact, state ABI and source cuts;
`TsxSiteAgent` refuses incompatible or unadmitted access. An identical delivery
uses its saved outcome. A pending acknowledgement is retried before another
plan pull, including after conformance expiry, without another activation.
Explicit execution recovery requires the same grant and a retained compatible
checkpoint. The local `TsxCheckpoint` starter keeps its
existing hash-mismatch behavior. Read
[`TsxSiteAgentPlanTest`](../../opentsx-wayang/src/test/java/org/opentsx/wayang/site/TsxSiteAgentPlanTest.java)
for the admission and recovery regressions.

## Validate a whole-topic execution assignment

`ExecutionAssignments.validate(text)` and `validateSet(texts)` perform offline preflight.
The Rust `tsx-contracts` library implements the same contract checks; it does not execute numerical operators.
The separate local `tsx-runner` adds artifact, binding, graph, capacity and actual broker-inventory checks before
executing its bounded subset. Passing this library lesson does not activate that process.
Every input owns one whole `(fragment,binding,topic)`. Its positive `partitionCount` has complete ordered
`positions` for `0..N-1`; offsets are checked decimal strings. Duplicate owners and incomplete positions reject.
Schema shape checks, exact hashes, complete internal positions and set consistency are separate gates.
Passing these gates does not authorize deployment, prove broker metadata or enforce live fencing.
`RustRollout` adds actual capability, artifact/state-ABI, source inventory and catalog checks. It preserves one original
UI text/hash while giving per-topic fragments distinct identities. The authenticated backend persists its physical
plan, clock and topic ownership before the standalone coordinator executes the Wayang stage. The three-worker
acceptance fixture reads actual output and receipts; the offline assignment starter does not dispatch that workload.

The bounded JVM runner wrapper adds an exact source-topic gate before admission:
every declared fragment source needs an input, and no admitted input may remain
unused. Matching multiple topics may use a different inventory order. This gate
belongs to `RunnerPlan`, not the shared offline assignment validator.

## Generate mean-reverting demo values

`Generators.IDS` is the implemented generator registry: `gen.ou` and `gen.sine`. Each has a learning lesson,
checked by `GeneratorLearningTest`; adding a runtime generator without a lesson fails that coverage check.

`Generators.values("gen.ou", params, seed, intervalMicros, first, count, min, max)` evaluates a discrete
mean-reverting recurrence. `mean` and nonnegative `sigma` are required. `reversion` defaults to 0.1 and must be
in (0,1]; it is a share per sample, not a continuous-time rate. The finite warm start truncates earlier history,
so differently started requests generally agree approximately, not bit for bit. With `reversion=1` each step
has no retained history and split-range agreement is exact. With `mean=10` and `sigma=0`, all starter values are
ten; an optional maximum of nine clips them to nine. A seed repeats a fixed request exactly.

These are bounded demo generators, not continuous processors or physical scenario models.
`Generators.episodes` validates a positive interval, a nonempty range and the `MAX_SAMPLES` budget, cuts on the
600-sample grid, and marks origin `SYNTHETIC` and fidelity `demo`. The learning test uses 601 one-second samples
and checks counts [600,1], exact boundaries and replay-stable episode IDs. A changed requested period changes
the generated series identity. The optional fixed `generatedAt` argument labels the run; it does not turn the
result into a measured observation.

## Generate a bounded sine cycle

`Generators.values("gen.sine", params, seed, intervalMicros, first, count, min, max)` requires `mean` and
`amplitude`; `period` defaults to `P1D`, `phase` to zero and `sigma` to zero. Period is a positive duration,
phase is a share of a cycle, and sigma is nonnegative. Each sample uses its absolute grid index, so the same
request or an overlapping noiseless slice agrees. For mean ten, amplitude two, a four-second period and
one-second sampling, the starter is [10,12,10,8,10]. A quarter-cycle phase shift gives [12,10,8,10,12].
Optional min/max bounds clip the generated values. The result is synthetic demo data; generation does not
establish a Wayang mapping, Rust implementation or continuously running processor.

## Inspect legacy real FFT coefficients

`TimeSeriesObjectFFT.convertToMessreiheFFT(input)` copies the two coordinate vectors. Calling
`getFFT(samplingRate)` zero-pads the values to a power of two, transforms them and returns all real Fourier
coefficient components at frequencies `k * samplingRate / N`, for padded length N. It does not remove the mean,
produce the UI's one-sided amplitude spectrum, or preserve the imaginary components needed for inversion.
An eight-sample impulse at sampling rate eight gives eight coefficients equal to one at frequencies zero
through seven. The sine [0,1,0,-1,0,1,0,-1] has zero real coefficients within roundoff even though its amplitude
is nonzero. The input remains unchanged. The direct method is distinct from the separately validated Swing
FFT action; supply the explicit sampling rate and valid finite input rather than assuming dialog validation.

## Inspect the legacy surrogate facade

`FFTPhaseRandomizer.getPhaseRandomizedRow(input, false, false, 0, mode)` delegates to the existing
`TimeSeriesObjectFFT` methods. Use `MODE_shuffle_phase` or `MODE_multiply_phase_with_random_value`.
The first mode shuffles imaginary coefficient components while retaining real components; the second scales
complex coefficient pairs by random factors. Both remove the constant coefficient, inverse-transform, take
real values and return integer sample-index coordinates up to the original input length.

These implementations do not establish amplitude-preserving random phase rotation. The facade exposes no seed:
its implementations call `Collections.shuffle` or `Math.random`. The learning tests therefore assert invariants
that hold for every random draw. Eight constant samples of seven become eight zeros in both modes without
mutating the input. Shuffle mode leaves [1,0,-1,0,1,0,-1,0] unchanged within roundoff because its Fourier
coefficients are real. The tests make no repeatability claim for arbitrary nonconstant surrogate samples.
