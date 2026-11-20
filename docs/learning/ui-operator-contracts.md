# Inspect the workbench operator contracts

This reference describes the 30 executable workbench operators in `OPS` , verified with Node 24.22.2 and Vitest 5.1.0 on
2026-11-16; `operator-learning.test.ts` checks the IDs, parameter defaults and synthetic predictions.

You need the workbench series model: ordered x coordinates, y values and optional units. In the learning JSON, `null`
encodes a missing value and the test reader turns it into NaN. Time coordinates in these lessons are seconds from
`t0Ms = 0` . The DFA starter uses the existing Mulberry32 generator with the recorded seed and count instead of storing
8192 numbers.

The runtime authority is [`OPS`](../../opentsx-saas-frontend/src/workbench/ops.ts#L782) , including entries with
`inCatalog: false` . The [`learning manifest`](ui-operators.v1.json) adds teaching links and concrete workloads, not
another implementation catalog. Each lesson calls the real `apply` function. Expected results are hand-derived small
examples or the existing white-noise DFA reference interval; the numerical algorithms are not reimplemented in the
checker.

All entries below execute in the browser workbench. Deployment is a separate capability. The seven
[`CleanChain`](../../opentsx-retrieval/src/main/java/org/opentsx/retrieval/clean/CleanChain.java#L35) operators and
[`align.vector`](../../systems/processors/align.vector.yaml) have existing Java processor implementations. A pure Java
pair kernel does not establish a Wayang execution mapping. The separate
[Rust no-context profile](../devguide/integration/rust-runner-local.md) accepts
plausible-range checks, conversion and a trailing resample.
The [B2 catalog context](../devguide/integration/rust-runner-b2.md) separately
admits all seven clean operations and typed statistics, retrieval, vector, bounded cross-correlation and DQ
stages. Those runtime parameters follow the processor descriptions; browser
operator names alone do not establish semantic equivalence. The explicit
[bounded Wayang UI target](../devguide/integration/wayang-rust-compose.md)
compiles `dq.checks`, `convert.unit` and trailing `resample`
operations into one connected custom Rust stage and complete per-topic fragments for one to three workers.
Its journal, private prepare, collision checks, activation and receipts are separate from the browser lesson workload.
The browser lessons' zero-based coordinates do not prove Kafka timestamp validity.
The [continuous target](../devguide/integration/wayang-rust-continuous.md) preserves the same whole-topic placement,
source hash and accepted clock across acknowledged increments. Worker-local state and cumulative series ownership
survive service restart. The three browser workloads retain their original local inputs and expected values;
separate increment and Compose regressions establish the continuous runtime behavior.
Transient preparation failures retain private candidates and the exact plan for retry; activation waits for every
preparation and ownership check. This recovery rule does not change the three numerical browser workloads.
The separate [local publication protocol](../datamodel-2026/20-WAYANG-TS-OPERATORS-SPEC.md#s112-fence-local-publication-and-replace-a-continuous-deployment)
fences captured grants through the gateway and segregated Proxy roles. Same-DAG resume preserves state and open
windows; changed-DAG restart replays whole inputs into a distinct fresh output. An unknown broker outcome blocks
replacement rather than permitting blind resend. Those operational checks do not change the operator starters,
expected values or numerical tolerances, and do not establish gateway high availability.

The workbench aliases `dq.plausible-range` to `dq.checks` and `stats.window` to
`stats.welford-ddsketch`. Its statistics deployment exporter writes a catalog
duration and a full statistics sink; the stored flow retains the selected
browser field. Read the [compiler instructions](../../opentsx-wayang/README.md#compile-a-catalog-dag)
for the required metadata bindings and runtime admission context.

Finite predictions use absolute comparison to ten decimal places in the lesson tests; missing-value positions must match
exactly. Existing shared operator vectors retain their own tolerances. Each operation is repeated with a fresh input to
check deterministic output. Source parameter keys, types, options and documented default tokens are checked against
`OPS` .

<a id="shuffle"></a>

## Test a seeded shuffle

Operator: `shuffle`. Defaults: `rounds=1`, `seed=1`

Copies and shuffles each y array with seeded Mulberry32 and Fisher-Yates. The x axis and unit stay attached to the
series; marks are removed. Rounds are rounded and clamped to at least 1. Gaps are shuffled as values. This is a
workbench transform, not a deployed DAG binding.

Source: [ops.ts#shuffle](../../opentsx-saas-frontend/src/workbench/ops.ts#shuffle). Executable exercise: `ui.shuffle`.

The native `Shuffle` action uses a separate [desktop contract](../../opentsx-core/README.md#shuffle-native-series).
It requires labeled nonempty series with matching finite coordinate/value pairs and a positive signed 32-bit pass
count, with at most 10000000 requested sample visits across the selection. Cancellation is quiet, and rejected input
produces no chart or recorder entry. Coordinates, axis labels and Y multiplicities are preserved on copies.
The native action exposes no seed; the public legacy shuffle API is separate from these desktop checks.

<a id="norm-max"></a>

## Scale a series by its maximum

Operator: `norm.max`. Defaults: No parameters.

Divides every y by the maximum of finite values in its own series. The x axis and existing unit metadata are retained.
Missing values remain missing; a zero maximum is not guarded. No Java streaming binding is claimed.

Source: [ops.ts#normMax](../../opentsx-saas-frontend/src/workbench/ops.ts#normMax). Executable exercise: `ui.norm.max`.

The native `NORM max=1` action has a separate
[checked maximum-scaling contract](../../opentsx-core/README.md#scale-native-series-by-a-positive-maximum).
It requires at least one matching finite coordinate/value pair, a positive maximum and finite quotients in every
selected series. It preserves original coordinates and axis labels and rejects invalid input before opening a chart
or recording a transformation. Its rejection of zero maxima differs from this browser workload's unguarded division.
The public legacy `TimeSeriesObject.normalize()` calculation and entropy are separate from these desktop checks.

<a id="norm-zscore"></a>

## Use sample standard deviation in a z-score

Operator: `norm.zscore`. Defaults: No parameters.

Subtracts the mean of finite values and divides by sample standard deviation; uses divisor 1 when standard deviation is
not positive. Coordinates and unit metadata are retained. NaN gaps remain NaN.

Source: [ops.ts#normZ](../../opentsx-saas-frontend/src/workbench/ops.ts#normZ). Executable exercise: `ui.norm.zscore`.

The native `NORM std=1, avg=0` action uses a separate
[checked normalization contract](../../opentsx-core/README.md#normalize-finite-time-series). It preserves original
sample coordinates and axis labels and requires at least two matching finite coordinate/value pairs with a finite,
positive sample deviation and finite reciprocal. Invalid input produces no chart or recorder entry. Native
constant-series rejection differs from this browser workload's zero result.

<a id="log"></a>

## Track coordinates when logarithms remove points

Operator: `log`. Defaults: No parameters.

Computes natural logarithms and removes points whose logarithm is nonfinite, shortening x and y together. It retains the
input axis kind and unit metadata. This differs from filling invalid points with NaN.

Source: [ops.ts#logY](../../opentsx-saas-frontend/src/workbench/ops.ts#logY). Executable exercise: `ui.log`.

<a id="logreturn"></a>

## Measure multiplicative changes with log returns

Operator: `logreturn`. Defaults: No parameters.

Sets y[0] to 0 and y[i] to ln(y[i]) minus ln(y[i-1]). Keeps coordinates, length and unit metadata. Invalid logarithms
propagate nonfinite values instead of dropping points.

Source: [ops.ts#logReturn](../../opentsx-saas-frontend/src/workbench/ops.ts#logReturn) . Executable exercise:
`ui.logreturn` .

<a id="diff"></a>

## Measure additive first differences

Operator: `diff`. Defaults: No parameters.

Initializes the first difference to 0, then subtracts adjacent values. It preserves x, length and unit metadata.
Nonfinite inputs propagate through subtraction. It does not divide by elapsed time.

Source: [ops.ts#diff](../../opentsx-saas-frontend/src/workbench/ops.ts#diff). Executable exercise: `ui.diff`.

<a id="profile"></a>

## Integrate deviations from the mean

Operator: `profile`. Defaults: No parameters.

Computes the mean over finite values, replaces each nonfinite value by that mean, and accumulates deviations. Keeps x
and unit metadata. The same profile construction is used inside DFA.

Source: [ops.ts#profile](../../opentsx-saas-frontend/src/workbench/ops.ts#profile). Executable exercise: `ui.profile`.

<a id="fft"></a>

## Locate a sine in an amplitude spectrum

Operator: `fft`. Defaults: No parameters.

Removes the finite-value mean, substitutes 0 for nonfinite centered samples, and zero-pads to a power of two. Returns
bins k < size/2 with amplitude 2|FFT[k]|/original length. Frequency spacing comes from the median coordinate spacing.
The output axis is frequency; the unit metadata is cleared. This is not the legacy Java real-coefficient FFT API.

Source: [ops.ts#fft](../../opentsx-saas-frontend/src/workbench/ops.ts#fft). Executable exercise: `ui.fft`.

<a id="histogram"></a>

## Count values in a normalized histogram

Operator: `histogram`. Defaults: `min=-10`, `max=10`, `bins=50`

Builds at least two equal-width bins on the requested range for each series. Counts only finite values in [min,max],
includes max in the last bin, then divides by the accepted count. Outputs a value axis at bin centers and clears units.

Source: [ops.ts#histogram](../../opentsx-saas-frontend/src/workbench/ops.ts#histogram) . Executable exercise:
`ui.histogram` .

The native `Histogram per series` action uses a separate
[raw-count contract](../../opentsx-core/README.md#plot-native-histograms-per-series). It validates ordered integer
bounds, a positive integer bin count and at least one finite matching coordinate/value pair in every selected row.
Its legacy bins and raw counts differ from this browser workload's normalized bin centers and frequencies.
Invalid input produces no chart or recorder entry.

<a id="histogram-crosscut"></a>

## Compare distributions in separate time slices

Operator: `histogram.crosscut`. Defaults: `min=-10`, `max=10`, `bins=50`, `window=100`

Pools all input series within consecutive sample slices, producing one normalized histogram per slice with at least two
samples per slice and at most 16 output slices. Bin semantics match histogram. The output axis is value and no physical
unit is assigned.

Source: [ops.ts#histogramCrossCut](../../opentsx-saas-frontend/src/workbench/ops.ts#histogramCrossCut) . Executable
exercise: `ui.histogram.crosscut` .

The native Swing `Histogram cross-cut` action has a separate
[raw-count contract](../../opentsx-core/README.md#compare-native-histogram-slices). It accepts positive integer
window lengths and requires samples in the first selected series. Window starts follow that series, while each
series contributes its available values within each nominal window, including the final partial slice. Selection,
bounds and bin count are validated before output. Its legacy bins and counts do not use this browser lesson's
normalization or 16-slice limit.

<a id="cdf"></a>

## Accumulate histogram probabilities into a CDF

Operator: `cdf`. Defaults: `bins=30`

Uses the finite minimum and maximum of each series, builds at least two normalized histogram bins, then accumulates
their frequencies. Output coordinates are value-bin centers; unit metadata is cleared.

Source: [ops.ts#cdf](../../opentsx-saas-frontend/src/workbench/ops.ts#cdf). Executable exercise: `ui.cdf`.

<a id="entropy-window"></a>

## Predict entropy for balanced and partial windows

Operator: `entropy.window`. Defaults: `length=20`, `bins=16`

Uses at least four samples per requested window and at least two bins over the full finite series range. Advances by
floor(length/4), at least one sample. Evaluates partial tail windows with at least two input positions, ignoring
nonfinite values. Output x is window-start sample index and unit is bit.

Source: [ops.ts#entropyWindow](../../opentsx-saas-frontend/src/workbench/ops.ts#entropyWindow) . Executable exercise:
`ui.entropy.window` .

The native Swing `ENTROPY` action and `TimeSeriesObject.calcEntropyForWindow` use a separate
[native contract](../../opentsx-core/README.md#compute-native-entropy-windows). They accept positive integer lengths,
require at least two finite matching coordinate/value samples, advance starts by at least one sample and clip cuts
to the available data. Native maximum normalization and modulo-100 discretization differ from this browser
workload's binning; its expected values do not establish native output parity.

<a id="acf"></a>

## Find alternating dependence with autocorrelation

Operator: `acf`. Defaults: `maxLag=1000`

Centers by the finite-value mean and divides each lag product sum by the full zero-lag sum of squares. Returns k <
min(floor(n/2),maxLag), including zero. Constant input yields zeros. Nonfinite values propagate through sums. Output
axis is lag in samples, with no unit.

Source: [ops.ts#autoCorrelation](../../opentsx-saas-frontend/src/workbench/ops.ts#autoCorrelation) . Executable
exercise: `ui.acf` .

<a id="dfa"></a>

## Check a white-noise DFA exponent

Operator: `dfa`. Defaults: `order=2`, `scales=40`

Builds a centered profile and detrends complete nonoverlapping windows with order 1 or 2 (values at least 2 select order
2). Uses rounded logarithmic scales between order+4 and floor(n/4), omits duplicate scales and nonpositive fluctuations,
and fits log10(F) against log10(scale). Output axis is scale, unit cleared. Gaps contribute zero to the centered
profile. No deployed DAG binding is claimed.

Source: [ops.ts#dfaOp](../../opentsx-saas-frontend/src/workbench/ops.ts#dfaOp). Executable exercise: `ui.dfa`.

<a id="pair-coverage"></a>

## Measure overlap before interpreting a pair statistic

Operator: `pair.coverage` . Defaults: `pairing="same"` , `window="episode"` , `step=0` , `every=0` , `agg="MEAN"` ,
`fill="none"` , `maxAge=60` , `minCoverage=0`

Uses pair windows and vector alignment. Without explicit episodes, each series range ends one sampling interval after
its final sample. With every=0 each pair range takes the larger sampling interval of its two episodes, on their sample
times in epoch microseconds, as `TsxPair` and the Rust runner do; the shared cases are
[pair-clock.v1.json](../datamodel-2026/testvectors/pair-clock.v1.json). A fill other than none or as-of is refused.
A series whose times are not in increasing order is refused with
`<label> has times out of order. A pair method needs them in increasing order.`, where `<label>` is the label of
the series.
Reports joint finite tick share and total ticks, stamped at the window center. Below minCoverage all method fields
become NaN with quality 1. Output remains a time axis with pair metric metadata.

Source: [pairs.ts#PAIR_COVERAGE](../../opentsx-saas-frontend/src/workbench/pairs.ts#PAIR_COVERAGE) . Executable
exercise: `ui.pair.coverage` .

<a id="pair-xcorr"></a>

## Interpret a signed cross-correlation peak

Operator: `pair.xcorr` . Defaults: `pairing="same"` , `window="episode"` , `step=0` , `every=0` , `agg="MEAN"` ,
`fill="none"` , `maxAge=60` , `minCoverage=0.8` , `lags=10`

Aligns pair windows, centers finite values, and computes lagged products with zero padding divided by (n-1) times both
sample standard deviations. Missing centered values become zero. Selects the signed extreme of absolute correlation.
Positive reported lag means B follows A and is measured in clock seconds. Strength uses the mean and sample standard
deviation across searched lags. Constant windows give gaps. The native `TsxPair`
mapping and frozen `pair.xcorr` version 1 are separate from deployment through
the workbench. Read
[Use frozen catalog 1](../user-manual/17-learn-operators.md#use-frozen-catalog-1)
for membership and the catalog's text representation of pair windows; direct
pair APIs also accept numeric seconds.

With every=0 each pair range takes the larger sampling interval of its two episodes, on their sample times in epoch
microseconds. The workbench, `TsxPair` and the Rust runner run the timestamped cases of
[pair-clock.v1.json](../datamodel-2026/testvectors/pair-clock.v1.json), including a series that changes its sampling
interval between episodes and jittered samples. A fill other than none or as-of is refused with
`pair fill must be none or as-of`.
A series whose times are not in increasing order is refused with
`<label> has times out of order. A pair method needs them in increasing order.`.

Source: [xcorr.ts#PAIR_XCORR](../../opentsx-saas-frontend/src/workbench/xcorr.ts#PAIR_XCORR) . Executable exercise:
`ui.pair.xcorr` .

<a id="pair-es"></a>

## Compare event synchronization with its surrogate baseline

Operator: `pair.es` . Defaults: `pairing="same"` , `window="episode"` , `step=0` , `every=0` , `agg="MEAN"` ,
`fill="none"` , `maxAge=60` , `minCoverage=0.8` , `sigma=2` , `tauMax=0` , `shuffles=10` , `seed=1`

Detects strict interior maxima above mean + sigma times population standard deviation; a neighboring gap prevents a
peak. Uses adaptive neighbor-distance windows and an optional tauMax in seconds (0 means uncapped). Exact simultaneous
events count half in each direction. q_rand averages seeded inter-event-interval shuffles. Output is one series per
field on the pair time grid.

The pure Java `TsxPairEs` kernel checks all eight shared vector cases. The native
Java API `TsxPair` also executes the four sampled-series cases through Wayang,
including B held in `TsxState` and restored before A arrives in a later
increment. The other four cases contain event lists only and are kernel checks.
`pair.es` version 1 belongs to the
[frozen catalog](../../systems/catalogs/tsx-catalog-1.yaml). The local Java
compiler lowers this catalog operator; runtime deployment and its admission
profile remain separate. Read
[Use frozen catalog 1](../user-manual/17-learn-operators.md#use-frozen-catalog-1)
for the catalog boundary and canonical text representation of pair windows.
Whole-number seeds use their value modulo `2^32` for the random state; labels
retain workbench numeric formatting. The native regression methods
`largeSeedsUseTheSameUnsigned32BitStateAsTheWorkbench` and
`settingsLabelsKeepWorkbenchNumberFormatting` cover large seeds and label
boundaries. Read the
[native API instructions](../user-manual/11-classic-analytics.md#run-sampled-event-pairs-through-wayang)
for the distinction between seconds in pair settings and units in event lists.

With every=0 each pair range takes the larger sampling interval of its two episodes, on their sample times in epoch
microseconds. The workbench and `TsxPair` run the timestamped `pair.es` cases of
[pair-clock.v1.json](../datamodel-2026/testvectors/pair-clock.v1.json); the Rust runner has no `pair.es`. A fill
other than none or as-of is refused with `pair fill must be none or as-of`.
A series whose times are not in increasing order is refused with
`<label> has times out of order. A pair method needs them in increasing order.`.

Source: [es.ts#PAIR_ES](../../opentsx-saas-frontend/src/workbench/es.ts#PAIR_ES). Executable exercise: `ui.pair.es`.

<a id="sigma-band"></a>

## Calculate a cross-series sample sigma band

Operator: `sigma.band`. Defaults: No parameters.

At each shared array index, computes finite-value mean and sample standard deviation across series. Truncates to the
shortest y length, uses the first series x and unit, and returns mean plus/minus one standard deviation. It does not
align timestamps.

Source: [ops.ts#sigmaBand](../../opentsx-saas-frontend/src/workbench/ops.ts#sigmaBand) . Executable exercise:
`ui.sigma.band` .

<a id="select"></a>

## Select a series with a case-insensitive pattern

Operator: `select`. Defaults: `pattern=".*"`

Applies a case-insensitive regular expression to each label or series ID and keeps matching whole series unchanged. An
invalid expression becomes a never-matching expression. It does not select individual points and is not one of the
CleanChain DAG operators.

Source: [ops.ts#selectSeries](../../opentsx-saas-frontend/src/workbench/ops.ts#selectSeries) . Executable exercise:
`ui.select` .

<a id="dq-plausible-range"></a>

## Keep the clock while marking implausible values as gaps

Operator: `dq.plausible-range`. Defaults: `min=-10`, `max=10`, `mode="gap"`

For finite values outside [min,max], gap mode writes NaN and clip mode writes the corresponding bound. Endpoints are
accepted; existing NaN values remain NaN. Keeps time, series and units. DAG export aliases this UI operation to
dq.checks with PLAUSIBLE_RANGE; CleanChain implements that bounded check.

Source: [ops.ts#plausibleRange](../../opentsx-saas-frontend/src/workbench/ops.ts#plausibleRange) . Executable exercise:
`ui.dq.plausible-range` .

<a id="convert-unit"></a>

## Convert Celsius to Fahrenheit explicitly

Operator: `convert.unit`. Defaults: `factor=1`, `offset=0`, `unit=""`

Computes y*factor+offset for each point, retaining x and gaps. A nonempty unit parameter replaces the series unit and
bucket y label; an empty unit retains existing metadata. CleanChain provides the same DAG operation.

Source: [ops.ts#convertUnit](../../opentsx-saas-frontend/src/workbench/ops.ts#convertUnit) . Executable exercise:
`ui.convert.unit` .

The local Rust cleaning stage executes the artifact-embedded
[WASM conversion](../devguide/integration/rust-runner-b5.md) with fixed fuel,
memory and stack limits and no imports. The guest performs separate binary64
multiplication and addition; the host retains metadata, gaps, timestamps and
identities and rejects overflow. Its shared-vector and limit tests are
separate from this browser workload. Plans cannot supply code or raise the
limits. Signed B1 and B3 operator admission is unchanged.

<a id="resample"></a>

## Aggregate a regular series into two-second windows

Operator: `resample`. Defaults: `every=0.05`, `agg="MEAN"`

Uses half-open windows of every x-axis units. With a time axis and t0Ms, boundaries are epoch-aligned. Aggregations
offered by the UI are MEAN, MIN, MAX and LAST. Empty windows yield NaN; the batch emits its last partial window. Keeps
the input axis kind and unit. CleanChain has streaming boundary/state semantics tested separately.

Source: [ops.ts#resample](../../opentsx-saas-frontend/src/workbench/ops.ts#resample) . Executable exercise:
`ui.resample` .

<a id="model-wind-shear"></a>

## Estimate wind at a different height

Operator: `model.wind-shear`. Defaults: `fromM=10`, `toM=120`, `alpha=0.11`

Multiplies y by (toM/fromM)^alpha and preserves x and units. NaN gaps propagate. CleanChain supplies this DAG operation
with its own parameter validation. The result is a model estimate; this transform alone does not establish physical
accuracy.

Source: [ops.ts#windShear](../../opentsx-saas-frontend/src/workbench/ops.ts#windShear) . Executable exercise:
`ui.model.wind-shear` .

<a id="model-power-curve"></a>

## Check power-curve boundaries and model limits

Operator: `model.power-curve`. Defaults: `ratedKw=3000`, `cutIn=3`, `ratedWind=12.5`, `cutOut=25`, `count=1`

Below cutIn or strictly above cutOut, outputs 0. At or above ratedWind through cutOut, outputs ratedKw*count. Between
cutIn and ratedWind, uses a cubic ramp in wind speed. NaN remains NaN. Preserves x and changes unit/y label to kW.
CleanChain implements the DAG operation.

Source: [ops.ts#powerCurve](../../opentsx-saas-frontend/src/workbench/ops.ts#powerCurve) . Executable exercise:
`ui.model.power-curve` .

<a id="feature-lag"></a>

## Build a two-sample lag without inventing history

Operator: `feature.lag`. Defaults: `steps=1`

Rounds steps and clamps it to at least 1. Emits NaN for the first steps samples, then y[i-steps], keeping the original x
and units. CleanChain carries per-series lag state across blocks; isolated workbench application covers only its loaded
bucket.

Source: [ops.ts#featureLag](../../opentsx-saas-frontend/src/workbench/ops.ts#featureLag) . Executable exercise:
`ui.feature.lag` .

<a id="feature-rolling"></a>

## Use an open-left trailing time window

Operator: `feature.rolling`. Defaults: `window=3600`, `fn="MEAN"`

For each coordinate, aggregates points with x > current x - window through the current point. Supports MEAN, population
STD, MIN and MAX, ignoring NaN values. Keeps original x and units; no finite member yields a gap. CleanChain maintains
the corresponding keyed history across blocks.

Source: [ops.ts#featureRolling](../../opentsx-saas-frontend/src/workbench/ops.ts#featureRolling) . Executable exercise:
`ui.feature.rolling` .

<a id="smooth-movavg"></a>

## Smooth over a fixed number of samples while ignoring gaps

Operator: `smooth.movavg`. Defaults: `window=25`

Rounds and clamps window to at least one sample. Maintains a trailing sum/count of finite members within that many
positions; returns NaN only when none are finite. Preserves x, axis kind and unit metadata. No CleanChain DAG binding is
claimed.

Source: [ops.ts#movingAverage](../../opentsx-saas-frontend/src/workbench/ops.ts#movingAverage) . Executable exercise:
`ui.smooth.movavg` .

<a id="stats-window"></a>

## Compute sample spread in tumbling windows

Operator: `stats.window`. Defaults: `window=0.25`, `field="STD"`

Reuses the workbench tumbling-window aggregation and returns a selected MEAN, sample STD, MIN or MAX per window. Ignores
nonfinite values, retains input axis kind/units and emits the final partial window. DAG export names
`stats.welford-ddsketch`: it writes the width as a one-entry `windows` duration
list and uses the processor description's alpha default. The sink writes full
`org.opentsx.model.v2.SeriesStats` records. `field` stays in the stored flow as a
browser view; the browser result remains one selected statistic series. A
nonpositive or nonfinite deployed width and an unknown view field reject.

Source: [ops.ts#windowStats](../../opentsx-saas-frontend/src/workbench/ops.ts#windowStats) . Executable exercise:
`ui.stats.window` .

<a id="segment-threshold"></a>

## Extract sample neighborhoods around rising crossings

Operator: `segment.threshold`. Defaults: `enter=0`, `direction="ABOVE"`, `pre=20`, `post=80`

Detects adjacent rising crossings with previous < enter and current >= enter, or the reverse inequalities for BELOW.
Rounds pre/post to sample counts, clamps pre to at least 0 and post to at least 1. Cuts [crossing-pre,crossing+post),
clipping at input edges, and draws at most 24 segments. Relative x uses median sampling interval. NaN comparisons do not
cross. Java Segmenter.threshold uses a separate hysteresis/duration contract.

Source: [ops.ts#segmentThreshold](../../opentsx-saas-frontend/src/workbench/ops.ts#segmentThreshold) . Executable
exercise: `ui.segment.threshold` .

<a id="align-vector"></a>

## Put two components on one clock without filling missing data

Operator: `align.vector` . Defaults: `every=60` , `key="series"` , `agg="MEAN"` , `fill="none"` , `maxAge=60` ,
`emit="any"` , `names=""` , `lateness=0`

Requires time axes and every > 0. Aggregates samples on epoch ticks using MEAN, MIN, MAX, FIRST, LAST, SUM or COUNT.
Groups by series ID, all, or a tag. Fill none leaves missing components; as-of uses an age bounded in whole ticks. Emit
any retains ticks with at least one component, complete requires all. Preserves per-component units, creates vector
metadata, and ignores lateness in batch evaluation. VectorAligner and VectorProcessor provide existing Java
implementations tested with shared vectors.

Source: [vector.ts#alignVector](../../opentsx-saas-frontend/src/workbench/vector.ts#alignVector) . Executable exercise:
`ui.align.vector` .
