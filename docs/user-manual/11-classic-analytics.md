# 11. Classic analytics (DFA, MFDFA, ES, RIS)

OpenTSx started as a research toolkit for long-range correlations and extreme events in complex systems. Those
algorithms are still available:

* in Python, through the `opentsx` top-level API (the recommended way, with verified examples below),
* in Java, in `opentsx-core`, as tools on the 2.x `TimeSeriesObject` model,
* for `pair.es`, in `opentsx-wayang`, as pure Java methods over arrays of samples
  or event times, and as a Wayang operator over sampled episodes through the
  native Java API.

The pure Java pair cross-correlation kernel is in `opentsx-wayang`; see
[section 11.6](#116-compute-pair-cross-correlation-in-java).

## 11.1 Detrended Fluctuation Analysis (DFA)

DFA estimates the scaling exponent α of the fluctuation function F(s) ~ s^α after removing polynomial trends in
windows of size s.

| α | Interpretation |
|---|----------------|
| < 0.5 | anti-correlated |
| ≈ 0.5 | uncorrelated (white noise) |
| 0.5 – 1 | long-range correlated (persistent) |
| ≈ 1 | 1/f noise |
| > 1 | non-stationary, e.g. α ≈ 1.5 for Brownian motion |

```python
from opentsx import DFA, TimeSeriesObject
r = DFA(polynom_order=1).analyze(TimeSeriesObject(data=x, label="wind"))
r["alpha"], r["r_squared"], r["interpretation"]          # also r["scales"], r["fluctuations"]
```

Use `polynom_order=2` (DFA2) when quadratic trends are expected.

## 11.2 Multifractal DFA (MFDFA)

MFDFA generalizes DFA to q-th order moments. Its results are:

* the generalized Hurst exponents h(q),
* the mass exponents τ(q) = q·h(q) − 1,
* the singularity spectrum f(α).

The width Δα measures how multifractal a series is. For a monofractal series, h(q) is constant.

```python
from opentsx import MFDFA
r = MFDFA(polynom_order=1).analyze(x)
r["h_q"], r["delta_h"], r["alpha"], r["f_alpha"], r["delta_alpha"], r["is_multifractal"]
```

## 11.3 Event synchronization (ES)

ES measures how often events of two series (threshold crossings) occur close together in time, and which series
leads (after Quiroga et al., 2002).

```python
from opentsx import EventSynchronization
es = EventSynchronization()
r = es.analyze(x, y)   # events: local maxima above mean + 2·std (method="percentile": 95th)
r["overall_sync"], r["sync_1_to_2"], r["sync_2_to_1"], r["leader"], r["lead_lag_strength"], r["tau_max"]
q_rand = es.surrogate_sync(r["events_1"], r["events_2"], shuffles=10, seed=1)
```

Every event pair gets its own window, half the smaller distance to a neighbouring event (the local τ of Quiroga et
al.), so `overall_sync` stays in [0, 1]; `tau_max` caps that window when set. `surrogate_sync` gives the level chance
reaches: the mean `overall_sync` against copies of `y` whose intervals between events are shuffled. Only an
`overall_sync` clearly above it points to a coupling. The web workbench computes the same numbers per window with the
operator "Event synchronisation" (`pair.es`, tab "Multi-variate Analysis").

### Compute pair.es with Java

You need JDK 17 or 21 and the `opentsx-wayang` module. The Java kernel is
verified with JDK 21 on 2026-11-16. Import
`org.opentsx.wayang.ops.TsxPairEs`. Call `detectEvents(values, sigma)` with a
`double[]` of samples to get event tick indices. It uses the population
standard deviation, excludes the first and last tick, and rejects a peak
adjacent to a NaN gap.

Call `eventSync(eventsA, eventsB, tauMax)` with two `double[]` event lists.
Event times must be finite; the method sorts and deduplicates copies of the
inputs. Set `tauMax` to `null` for uncapped local windows, or supply a
nonnegative cap in the same units as the event times. A nonzero distance must
be strictly below the local window and the cap.

| `TsxPairEs.Sync` accessor | Meaning |
| --- | --- |
| `qTotal()` | Overall synchronization, `q_total` |
| `qAb()` | A leads B, `q_ab` |
| `qBa()` | B leads A, `q_ba` |

Call `surrogateSync(eventsA, eventsB, shuffles, seed, tauMax)` for `q_rand`.
Use at least one shuffle and a fixed `long` seed for repeatable results.
For direct interval shuffling, import `org.opentsx.wayang.ops.Mulberry32`
and pass a seeded instance to `isiShuffle(events, rand)`.

`TsxPairEsVectorsTest` reads the shared `pair-es.v1.json` file directly. Its
eight cases require equal event lists and an absolute error below `5e-13`
for all four synchronization fields. All ten shared Mulberry32 values are
compared exactly. See the
[module instructions](../../opentsx-wayang/README.md#compute-pair-event-synchronisation)
for the kernel and test entry point.

### Run sampled event pairs through Wayang

Use `org.opentsx.wayang.TsxPair` with method `pair.es`, a parameter map, the
expected series ids for inputs `a` and `b`, the specification hash, and
`TsxState`. Tag each input episode with `TsxTagged`, combine the inputs, and
register `TsxJavaPlugin`; the
[pair operator guide](../../opentsx-wayang/README.md#compare-two-series-in-windows)
describes this native Java API. `pair.es` belongs to frozen Catalog 1;
deployment admission remains separate from this Java entry point.

The pair settings select episode or sliding windows and align their sample
clocks. `sigma` controls event detection, `tauMax` is a cap in seconds with zero
meaning uncapped, and `shuffles` and `seed` select the surrogate control.
Output fields are `pair.es.q_total`, `pair.es.q_ab`, `pair.es.q_ba`, and
`pair.es.q_rand`. The direct kernel's event-time cap uses the units of its
event lists; the sampled operator converts seconds to clock ticks.

Keep waiting episodes in `TsxState` and restore that state between increments
when the other input arrives later. `TsxPairEsWayangTest` covers all four
sampled-series cases in `pair-es.v1.json`, both in one plan and with B retained
before A arrives in a later increment. The other four cases contain event lists
only and remain `TsxPairEsVectorsTest` kernel checks. All eight kernel cases
check the four synchronization fields within `5e-13`.

The native settings reduce whole-number seeds modulo `2^32` for the Mulberry32
state while preserving the original seed's workbench number formatting in
labels. `largeSeedsUseTheSameUnsigned32BitStateAsTheWorkbench` compares seed
`1e20` with its unsigned 32-bit state. `settingsLabelsKeepWorkbenchNumberFormatting`
checks small sigma, large duration and seed labels, including scientific
notation where the workbench uses it.

## 11.4 Return-interval statistics (RIS)

RIS analyzes the time between extreme events (values above a threshold, by default the 95th percentile). For
long-term correlated data, the return intervals cluster: the distribution is a stretched exponential instead of an
exponential, and short intervals follow short intervals. This is relevant for risk estimation, for example of storms
or load peaks.

```python
from opentsx import RIS
r = RIS(threshold_percentile=95).analyze(x)
r["mean_ri"], r["median_ri"], r["risk_parameter"], r["risk_interpretation"], r["gamma"], r["tau_0"]
```

`gamma` and `tau_0` are the parameters of the stretched exponential fit. For uncorrelated data, `gamma` is ≈ 1.

## 11.5 Java tools in `opentsx-core` (2.x model)

The base time-series text preview shows at most five points; legacy `writeToFile` exports every point and
preserves existing files on preparation or I/O failure. See the
[preview and export contract](../../opentsx-core/README.md#preview-and-export-a-time-series) for formats,
exceptions and regression checks.

The native `save PN` action writes the table, PNG and optional caption in order, replacing each file atomically.
A failed step stops the export and displays its destination plus the files saved in that attempt. Earlier outputs
remain available; the failed destination is preserved. See
[native table and chart saving](../../opentsx-core/README.md#save-native-tables-and-charts) for formats, errors
and regression checks.

The Java implementations work on `org.opentsx.data.series.TimeSeriesObject` and on TSBucket sequence files. Most are
standalone programs with a `main` method:

| Area | Classes |
|------|---------|
| DFA / MFDFA | `org.opentsx.algorithms.detrending.methods.DFA`, `detrending.SingleDFATool`, `detrending.MultiDFATool`, `detrending.MFDFAAnalyzer` |
| return intervals | `org.opentsx.algorithms.ris.RISTool` |
| event synchronization | `org.opentsx.algorithms.eventsynchronisation.ESCalc`, `…experiments.ESMain` |
| statistics | `algorithms.statistics.GrangerCausality`, `EntropyTool`, distribution tests |
| generators | `org.opentsx.generators.TSGenerator`, `LongTermCorrelationSeriesGenerator`, `FFTPhaseRandomizer` (surrogates) |
| data | `data.series.TimeSeriesObject`, `data.loader.MessreihenLoader`, `data.generator.RNGWrapper`, `core.TSBucket` |
| GUI | `app.bucketanalyser.MacroRecorder2` (start: `bin/000_launch_tsa_workbench.sh`) |
| guided demos | `org.opentsx.demo.onboarding.*` (BasicOperations, TimeSeriesAnalysis, AnomalyDetection, …), started by `bin/episode_*.sh` |

To use them with v2 data, convert episodes with the legacy bridge ([chapter 13](13-migration.md)).

`BucketAnalyserTool` uses the supplied input folder and DFA flags. Optional arguments select a filename
substring and record limit. See [bucket analysis inputs](../../opentsx-core/README.md#supply-bucket-analysis-inputs).

The desktop AR, MA, ARMA, ARIMA, SARIMA, CARIMA and `persist TSB` actions are disabled because they have no
implementation. Their tooltip reads `This operation is not implemented`.
The modern tracker also disables Java and Scala export. Both trackers use `Show PFD` to display JSON in the window.
The modern tracker starts without sample tree items.

Local SequenceFile loading reads the actual records, handles empty buckets and respects the file loader's
record limit. Integer selection IDs identify 1-based file positions independently of labels; only selected records
are processed or retained, in file order. An explicit null selection clears a `BucketLoader` filter, and an empty
selection loads none. The scan limit includes excluded records. Processing errors stop the load with their cause
retained. The format stores labels and Y values;
loading reconstructs sequential X values. See [local bucket loading](../../opentsx-core/README.md#reload-local-sequencefile-buckets).

`createBucketFromVectorOfSeries` prepares and closes a staged SequenceFile before replacing the destination.
Preparation failures preserve existing data and checksum files. Local data and checksum publication are separate
atomic replacements; a later checksum or cleanup error identifies the already saved data and the failed path.
See [bucket saving](../../opentsx-core/README.md#save-sequencefile-buckets-with-atomic-replacement) for filesystem
limits, failure handling and regression checks. This does not change the label/Y format or add metadata preservation.

Executing a `MacroRunner` or an exported Java/Scala bucket workflow stops with `UnsupportedOperationException`:
context-based loading and named bucket processing are not implemented. See
[bucket workflow support](../../opentsx-core/README.md#check-bucket-workflow-support) for the exact error messages.

The desktop FFT action derives its sampling rate from uniformly spaced coordinates. For integer index coordinates
with spacing 1, enter the sampling rate in the dialog. Frequencies use the reciprocal of the coordinate unit.
See [FFT coordinates](../../opentsx-core/README.md#inspect-fft-coordinates) for input requirements and output limits.

The native `Histogram cross-cut` action pools raw counts within consecutive sample windows. Enter a positive
integer window length; cancellation leaves the chart and recorder unchanged. The first selected series must
contain samples; later series may be empty. Invalid selections, window lengths, bounds or bin counts produce a
validation message. Window starts follow the first series; every series contributes its available samples within
each nominal window, including the final partial window. See the
[native histogram contract](../../opentsx-core/README.md#compare-native-histogram-slices) for limits and regression checks.

The native `Shuffle` action validates selection shape and labels before prompting for a positive integer pass count.
Cancellation returns quietly. Requested work is limited to 10000000 sample visits, without an elapsed-time guarantee.
Finite cells are checked before copying or shuffling. Results preserve coordinates, axis labels and each Y multiset;
invalid input leaves the selected series and recorder history unchanged. See
[native shuffling](../../opentsx-core/README.md#shuffle-native-series) for errors and regression checks.

The native `NORM max=1` action requires matching finite coordinate/value pairs and a positive maximum in each
selected series. It preserves original coordinates and axis labels and rejects zero maxima or nonfinite quotients
without opening a chart or recording a transformation. See
[native maximum scaling](../../opentsx-core/README.md#scale-native-series-by-a-positive-maximum)
for errors and regression checks.

`Histogram per series` validates integer bounds, positive bin counts and nonempty finite matching coordinate/value
pairs in every selected row. It retains native raw-count binning. The native `NORM std=1, avg=0` action preserves
original sample coordinates and axis labels while rejecting short, malformed, nonfinite or constant series.
Both actions display validation errors and produce no chart or recorder entry for rejected input, including a bad
later row. See [native per-series histograms](../../opentsx-core/README.md#plot-native-histograms-per-series) and
[standard normalization](../../opentsx-core/README.md#normalize-finite-time-series) for contracts and regression checks.

The native `ENTROPY` action accepts positive integer window lengths and finite series with matching coordinates.
Cancelling or rejecting an input produces no chart or recorder entry. Window starts advance by at least one sample,
partial cuts stop at the available data, and output coordinates identify sample-start indices. The native maximum
normalization and modulo-100 discretization remain distinct from browser entropy binning. See
[native entropy windows](../../opentsx-core/README.md#compute-native-entropy-windows) for the exact input requirements,
error messages and regression checks.

### Flink jobs (`opentsx-flink-core`, Flink 1.20.5)

* `PFDRecorder.toJSON()` is a preview. File export and the dynamic runtime accept only Kafka input,
  `NORMALIZE` centering and `LOG` output. Topics and broker addresses require explicit configuration.
  File export uses strict UTF-8 and atomic replacement; preparation or I/O failures preserve an existing file.
  See [PFD validation](../devguide/validation/PFD_VALIDATION_PLAN.md) for destination rules and regression checks.
* The PFD Kafka source decodes raw legacy Avro observations and skips null tombstones. Malformed records fail
  with Kafka record coordinates and the original cause; downstream collector failures propagate unchanged.
* `pfd.DynamicTopologyJob` builds a Flink topology from a JSON processing-flow descriptor; the schema is in
  [`docs/devguide/schemas`](../devguide/schemas/).
* `stats.StatsJob` runs the v2 statistics engine (see [chapter 7](07-stats-quality.md), section 7.2).

### Load generation and Kafka tools (`opentsx-lg`, `opentsx-connectors`)

* `TSDataSineWaveGenerator` and the request/response latency probes.
* Topic tools `TopicsUP`/`TopicsDOWN`/`TopicsCHECK` (they read `config/topiclist.def`).
* These tools use the 2.x topics and Confluent serializers. For KafScale, use the v2 path ([chapter 4](04-kafscale-storage.md)).

## 11.6 Compute pair cross-correlation in Java

Use `org.opentsx.wayang.ops.TsxPairXcorr` for pair-window cross-correlation.
Verified with JDK 21 on 2026-11-16. You need equal-length arrays aligned to
the same epoch clock, with the first sample on a clock tick. Aggregation,
filling, and episode pairing happen before this kernel call.

`crossCorrelation` returns CC(k) for `k = -lags` through `+lags`.
`peakOf` returns the signed absolute peak and its link strength.
`compute` returns the window centre, peak, lag in seconds, strength,
coverage, quality, and CC values. A positive lag means that B follows A.
`pairSettings` parses the workbench defaults, including coverage `0.8`.

Episode windows cover the whole input range. Sliding windows include every
window that fits. Non-finite samples contribute zero after centring;
constant inputs give NaN. A window below the minimum coverage retains its
centre with NaN result fields and quality 1.

`TsxPairXcorrVectorsTest` reads all five cases from the shared repository
vector file and requires an absolute error below `5e-13`. It also checks
invalid lags, gaps, constant inputs, peak signs, and window boundaries.
See the [module guide](../../opentsx-wayang/README.md) for the test command
and [TASK-020](../../EVOLUTION/TASK-020-wayang-ts-operators.md) for A3.2.
