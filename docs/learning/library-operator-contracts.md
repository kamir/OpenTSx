# Exercise the public library operator families

Use these contracts with OpenTSx 3.0.1; the learning examples were checked with JDK 21.0.8, Maven 3.9.8
and Python 3.12.9.

You need the built Java module for a Java example, or the local `python-package` with its declared dependencies
for a Python example. The [lesson manifest](library-operators.v1.json) contains the inputs, tasks and evidence links.
These families supplement the UI operator lessons. Helper methods belong to their family rather than becoming
independent operators. A library implementation does not imply a deployed DAG, Wayang mapping or Rust kernel.

The current Java Wayang plugin separately registers clean-chain and window-statistics mappings through
[`TsxOperatorMappings`](../../opentsx-wayang/src/main/java/org/opentsx/wayang/TsxOperatorMappings.java).
`TsxSeriesChain` delegates the seven clean operations to `SeriesStream`; `TsxStatsWindow` delegates statistics
to `StatsEngine`. The
[`clean vectors`](../../opentsx-wayang/src/test/java/org/opentsx/wayang/TsxCleanVectorsTest.java)
check clean values through an actual Wayang plan. Pair kernels and the SAX, segmentation and data-quality
reference implementations remain separate from registered execution mappings.

The local [`DagToWayang`](../../opentsx-wayang/src/main/java/org/opentsx/wayang/DagToWayang.java)
compiler validates the current catalog and constructs fused clean-series plans with topic sinks. Its current
physical compilation accepts clean series sinks, not statistics sinks or federated plans. Statistics parameter
validation and the separately constructible `TsxStatsWindow` operator do not expand that compiler scope.
Remote deployment, authorization and Rust numerical execution remain outside this local API.

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

## Segment threshold phases

`Segmenter.threshold(direction, enter, exit, pre, post, minDuration, maxDuration)` applies hysteresis.
For `ABOVE`, exit cannot exceed enter. Zero maximum duration means unlimited.
State crosses source episode boundaries. The learning test feeds a phase in two episodes and verifies exact
agreement with bounded segmentation, including anchor and [0,5,5,2] output values.
[`segment-threshold.v1.json`](../datamodel-2026/testvectors/segment-threshold.v1.json) covers the Java hysteresis
contract and is replayed by
[`threshold vectors`](../../opentsx-retrieval/src/test/java/org/opentsx/retrieval/SegmentThresholdVectorsTest.java).
It does not define the browser's simpler sample-neighborhood threshold operator.

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
state. The direct library starter calls `emitGlobal()` explicitly. The browser's `stats.window` projection still
has its own `window`/`field` parameter contract; these Java modes do not resolve that UI export translation. See
[`TsxStatsWindowTest`](../../opentsx-wayang/src/test/java/org/opentsx/wayang/TsxStatsWindowTest.java)
for sketch-byte and cross-increment state checks. The
[`Wayang statistics vectors`](../../opentsx-wayang/src/test/java/org/opentsx/wayang/TsxStatsVectorsTest.java)
replay all five shared cases through actual plans, including bounded and explicit-window modes.

## Evaluate data quality

`DataQuality.evaluate(stats, expectation)` evaluates statistics without reading raw samples.
Checks include completeness, plausible range, flatline and gaps. Freshness additionally requires a maximum age
and an explicit `nowMicros` in the three-argument overload. Use fixed time in examples and tests.
Distinguish an implausible measurement from a valid measurement outside the system's operating range.
[`dq-result.v1.json`](../datamodel-2026/testvectors/dq-result.v1.json) is replayed by
[`DqResultVectorsTest`](../../opentsx-stats/src/test/java/org/opentsx/stats/DqResultVectorsTest.java).
These checks evaluate `SeriesStats`; they are distinct from the raw-value `dq.checks` clean-chain transform.

## Decode Avro records

`OpenTsxAvro.encode(record)` and `decode(bytes, Episode.class)` preserve the v2 record contract.
The default wire frame is Avro single-object encoding, including its schema fingerprint.
Shared wire vectors check bytes, not only decoded values. Register or resolve an older writer schema explicitly;
an unknown fingerprint is not permission to decode with an arbitrary current schema.

## Construct a whole-topic source

`TsxTopicSource(new TsxRead(...))` represents one topic and its internal bounded offset ranges.
Constructing the logical source performs no broker replay. The Java execution operator uses manual assignment,
schema-fingerprint checks and no consumer-group offset commits. The physical learning assignment owns the whole
topic; internal ranges never authorize placing its partitions on different nodes.
Offline wire/settings tests and disposable-broker integration are separate checks.

## Construct a topic sink

`TsxTopicSink(bootstrap, topic)` represents an output topic in a Wayang plan.
The Java sink uses the KafScale producer settings, series-ID keys and acknowledgements.
Constructing this object is not a successful write. Wire conversion and client-setting unit tests run offline;
end-to-end production and crash tests need a disposable broker. Acknowledgements do not provide exactly-once delivery.

## Persist an increment checkpoint

`TsxCheckpoint(job, planVersion, specHash, topic, next, state).write(path)` persists next offsets and per-node
operator state to a temporary file,
forces it, and atomically renames it. `load(path, hash)` returns empty for absence or a different hash, and rejects
an unreadable file. These are the current local increment semantics.
The starter passes `Map.of()` as its sixth argument. Nonempty state is covered by
[`TsxCheckpointTest`](../../opentsx-wayang/src/test/java/org/opentsx/wayang/TsxCheckpointTest.java): clean chains
store `SeriesStream.state()` JSON and statistics store Base64 engine state in the per-node map.
They do not implement the proposed orchestration state-ABI migration, fenced ownership or deployment grant policy.
Each assigned whole topic retains all of its internal positions; the checkpoint file is not a partition scheduler.

## Validate a whole-topic execution assignment

`ExecutionAssignments.validate(text)` and `validateSet(texts)` perform offline preflight.
The Rust `tsx-contracts` library implements the same contract checks; it does not execute numerical operators.
Every input owns one whole `(fragment,binding,topic)`. Its positive `partitionCount` has complete ordered
`positions` for `0..N-1`; offsets are checked decimal strings. Duplicate owners and incomplete positions reject.
Schema shape checks, exact hashes, complete internal positions and set consistency are separate gates.
Passing these gates does not authorize deployment, prove broker metadata or enforce live fencing.

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
