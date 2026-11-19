# 11. Classic analytics (DFA, MFDFA, ES, RIS)

Run DFA, MFDFA, event synchronization and return-interval statistics on a series, and find the Java tools of the 2.x
model. The example output comes from the `opentsx` package 1.0.0 on Python 3.13 with NumPy 2.5.5 and SciPy 1.18.1.

OpenTSx provides algorithms for long-range correlations and extreme events:

* in Python, through the `opentsx` top-level API (the recommended way, with the examples below),
* in Java, in `opentsx-core`, as tools on the 2.x `TimeSeriesObject` model.

[Chapter 17](17-operators.md) lists every runtime of each operator, with its parameters and tested examples.

You need the `opentsx` package ([chapter 10](10-python.md)). Each example generates its own input with a fixed seed.

## 11.1 Measure long-range correlation with DFA

DFA estimates the scaling exponent α of the fluctuation function F(s) ~ s^α after removing polynomial trends in
windows of size s.

| α | Interpretation |
|---|----------------|
| < 0.5 | anti-correlated |
| ≈ 0.5 | uncorrelated (white noise) |
| 0.5 to 1 | long-range correlated (persistent) |
| ≈ 1 | 1/f noise |
| > 1 | non-stationary, for example α ≈ 1.5 for Brownian motion |

```python
import numpy as np
from opentsx import DFA, TimeSeriesObject

x = np.random.default_rng(1).standard_normal(8192)
r = DFA(polynom_order=1).analyze(TimeSeriesObject(data=x, label="noise"))
print(round(r["alpha"], 3), round(r["r_squared"], 3), r["interpretation"])
```

Output:

```text
0.52 0.995 Uncorrelated (white noise)
```

`r` also holds `intercept`, `scales` and `fluctuations`.

`r["interpretation"]` names the band of α. The bands around 0.5 and 1 reach 0.05 to each side:

| α | `interpretation` |
|---|------------------|
| below 0.45 | `Anti-correlated (mean-reverting)` |
| 0.45 to 0.55 | `Uncorrelated (white noise)` |
| above 0.55 and below 0.95 | `Correlated (persistent, trending)` |
| 0.95 to 1.05 | `1/f noise (pink noise)` |
| above 1.05 | `Non-stationary (fractional Brownian motion)` |

White noise with an estimate of 0.48 gets `Uncorrelated (white noise)`. 1/f noise with an estimate of 0.97 gets
`1/f noise (pink noise)`. Above 1, α − 1 is the Hurst exponent of a fractional Brownian motion; a random walk has
α ≈ 1.5.

Set `polynom_order=2` (DFA2) to remove quadratic trends. `analyze` passes `min_scale` (default 10), `max_scale`
(default n // 4) and `num_scales` (default 20) to `calculate`. The scales are log-spaced integers without duplicates.

`calculate` and `analyze` raise `ValueError` naming the argument when the input gives no exponent:

* a value that is not finite, for example a gap stored as NaN,
* a constant series,
* `min_scale` below `polynom_order` + 2, or `num_scales` below 2,
* a `max_scale` not above `min_scale` or above the series length; with the default `max_scale` of n // 4, fewer
  than 4 · (`min_scale` + 1) values,
* a value of `scales` outside `polynom_order` + 2 to the series length, or fewer than 2 distinct window sizes,
* a window size at which every window is fitted exactly by the trend polynomial, so that F(s) is 0 up to
  rounding.

`fit_scaling_exponent` raises `ValueError` with fewer than 2 window sizes in the fit and for a fluctuation that is
not positive.

Drop or fill gaps before the analysis:

```text
ValueError: time_series: 1 of 1000 values are not finite, the first at index 500 is nan; expected finite values only, drop or fill the gaps first
```

A series of 20 values is too short for the default window sizes:

```text
ValueError: time_series: 20 values give max_scale 5, not above min_scale 10; expected at least 44 values, or a smaller min_scale
```

The web workbench has the operator "DFA" (`dfa`, tab "Uni-variate Analysis"). It takes order 1 or 2 (default 2) and
the number of scales (default 40, at least 4), spread from order + 4 to n/4. So the same series can give a different
α in the workbench and in Python, even with the same order.
See [DFA in chapter 17](17-operators.md#detrended-fluctuation-analysis-dfa).

<a id="112-multifractal-dfa-mfdfa"></a>

## 11.2 Measure multifractality with MFDFA

MFDFA generalizes DFA to q-th order moments. Its results are:

* the generalized Hurst exponents h(q),
* the mass exponents τ(q) = q·h(q) − 1,
* the singularity spectrum f(α).

The widths Δh and Δα measure how multifractal a series is. For a monofractal series, h(q) is constant.

```python
import numpy as np
from opentsx import MFDFA

x = np.random.default_rng(1).standard_normal(8192)
r = MFDFA(polynom_order=1).analyze(x)
print(round(r["delta_h"], 3), round(r["delta_h_surrogates"], 3), r["is_multifractal"], round(r["h_2"], 3))
```

Output:

```text
0.117 0.143 False 0.52
```

`r` also holds `q_values`, `scales`, `Fq`, `h_q`, `tau_q`, `alpha`, `f_alpha` and `delta_alpha`.
`h_2` is h(2), the exponent that DFA estimates. So `q_range` must hold 2, up to 1e-10, as `np.arange(-5, 5.1, 0.1)`
does. Without it `MFDFA` raises `ValueError: q_range: no value is 2; expected q values that include 2, the q of h_2`.
The default q runs from -10 to 10.5 in steps of 0.5, 42 values with 0. `analyze` checks its input as DFA does.

`calculate` leaves out the windows that the trend polynomial fits exactly up to rounding: a detrended variance of at
most (1e-10 times the largest absolute value of the profile) squared. Their fluctuation of 0 would dominate F_q(s)
for q < 0.

`delta_h` is h(q) at the first q minus h(q) at the last q, with the default q range h(-10) minus h(10.5).
`analyze` computes `delta_h` again for `surrogates` phase-randomized copies of the series (default 19, phases drawn by
`numpy.random.default_rng(seed)`, default seed 1). A copy multiplies each Fourier coefficient by a random phase
factor. It keeps the power spectrum, and so the linear correlations, and its values are close to Gaussian.
`delta_h_surrogates` is the largest `delta_h` of the copies. `is_multifractal` is `True` when `delta_h` is above
`delta_h_surrogates`. The test asks whether the series is more multifractal than a Gaussian series with the same
linear correlations. Of 100 series of 8192 values each (seeds 5000 to 5099):

* Gaussian series with linear correlations gave `True` in about 1 of `surrogates` + 1 cases: white noise 7 times,
  noise with a 1/f^0.6 spectrum 7 times, with a 1/f^0.9 spectrum 8 times, a random walk 4 times,
* uncorrelated values with a broad or skewed distribution gave `True`: Student-t values with 1.5 degrees of freedom
  100 times, with 5 degrees of freedom 70 times, exponential values 95 times. Uniform values gave `True` 0 times.
  So `True` does not tell multifractal correlations from a broad distribution of the values,
* `delta_alpha` does not decide it.

The white noise above has a `delta_h` of 0.117, below the 0.143 of its copies. `analyze` runs MFDFA once for the
series and once for each copy.

<a id="113-event-synchronization-es"></a>

## 11.3 Test two series for event synchronization

ES measures how often events of two series (local maxima above a threshold) occur close together in time, and which
series leads (after Quiroga et al., 2002).

```python
import numpy as np
from opentsx import EventSynchronization

rng = np.random.default_rng(1)
x = rng.standard_normal(8192)
y = np.roll(x, 3) + 0.3 * rng.standard_normal(8192)
es = EventSynchronization()
r = es.analyze(x, y)   # events: local maxima above mean + 2·std (method="percentile": 95th)
q_rand = es.surrogate_sync(r["events_1"], r["events_2"], shuffles=10, seed=1)
print(round(r["overall_sync"], 3), round(q_rand, 3), r["leader"], round(r["delay"], 3), round(r["delay_surrogates"], 3))
```

Output:

```text
0.694 0.307 Series 1 0.538 0.091
```

`y` repeats `x` 3 samples later with added noise, so series 1 leads. `r` also holds `sync_1_to_2`, `sync_2_to_1`,
`lead_lag_strength`, `num_events_1`, `num_events_2` and `tau_max`. `delay` is positive when series 1 leads.

`analyze` compares `delay` with `surrogates` copies of `y` (default 19) whose intervals between events are shuffled
by `isi_shuffle` with `Mulberry32(seed)` (default seed 1). `delay_surrogates` is the largest |`delay`| of the
copies. `leader` names the series that the sign of `delay` points to when |`delay`| is above `delay_surrogates`, and
is `None (symmetric)` otherwise. `lead_lag_strength` is |`delay`| when `leader` names a series, and 0 otherwise.
An independent pair names a leader in about 1 of `surrogates` + 1 cases. Of 200 independent pairs of spike trains
with about 120 spikes each (`_spike_train` in `python-package/tests/test_event_sync.py`, seeds 1 to 200), 13 named a
leader. Periodic events with equal intervals give copies equal to the original, so they name no leader.

Every event pair gets its own window: half the smallest distance from either event to its neighbouring events (the
local τ of Quiroga et al.). So `overall_sync` stays in [0, 1]. `EventSynchronization(tau_max=...)` caps that window.
Without a cap, `r["tau_max"]` is `inf`.

`analyze` raises `ValueError` for `surrogates` below 1, for a value that is not finite and for a series without
events, such as a constant series:

```text
ValueError: ts1: no event, no local maximum lies above the threshold; expected at least one event, lower the threshold or use another method
```

`method="absolute"` takes `threshold1` and `threshold2` as the event thresholds. Without them it raises
`ValueError: threshold: not set for method 'absolute'; expected the event threshold as a number (threshold1 and
threshold2 in analyze)`. `calculate_synchronization` takes event lists and gives 0 for Q, q_xy and q_yx when a list is
empty, as the web workbench does for a window without events.

`surrogate_sync` gives the level chance reaches: the mean `overall_sync` against copies of `y` whose intervals
between events are shuffled. Only an `overall_sync` clearly above it points to a coupling. `surrogate_sync` makes the
copies with `isi_shuffle` and the seeded generator `Mulberry32`, the same generator as in the web workbench.
The web workbench computes the same numbers per window with the operator "Event synchronisation" (`pair.es`, tab
"Multi-variate Analysis").

<a id="114-return-interval-statistics-ris"></a>

## 11.4 Analyze the return intervals of extreme events

RIS analyzes the time between extreme events: values above a threshold, by default the 95th percentile.
`RIS(threshold=...)` sets an absolute threshold instead. For long-term correlated data, the return intervals cluster:
their distribution is a stretched exponential instead of an exponential, and short intervals follow short intervals.
This matters for risk estimation, for example of storms or load peaks. `stretched_exponential_fit` fits a stretched
exponential to the intervals; it does not test whether one describes them.

```python
import numpy as np
from opentsx import RIS

ris = RIS(threshold_percentile=95)
r = ris.analyze(np.random.default_rng(1).standard_normal(8192))
print(r["num_events"], round(r["mean_ri"], 2), r["median_ri"], round(r["risk_parameter"], 3))
fit = ris.stretched_exponential_fit(r["return_intervals"])
print(round(fit["gamma"], 3), round(fit["tau_0"], 3))
```

Output:

```text
410 19.91 15.0 0.907
1.266 26.23
```

`r` also holds `threshold`, `event_indices`, `return_intervals`, `std_ri`, `min_ri` and `max_ri`.

To judge clustering, read `risk_parameter` R: the standard deviation of the return intervals divided by their mean.
Uncorrelated values give geometric intervals with R = sqrt(1 − p) for an exceedance probability p, 0.97 at the 95th
percentile. Clustered extremes give a larger R. `analyze` returns R without a label.

`analyze` raises `ValueError` for a value that is not finite and with fewer than 3 events, the minimum for 2 return
intervals:

```text
ValueError: time_series: 2 values lie above the threshold 0.961109; expected at least 3 events for 2 return intervals, lower the threshold or pass a longer series
```

Called on its own, `risk_parameter` raises `ValueError` with fewer than 2 intervals and when their mean is not
positive.

`stretched_exponential_fit` is a maximum-likelihood fit of the density P(τ) = γ / (τ₀ Γ(1/γ)) exp[−(τ/τ₀)^γ]. For a
given γ the likelihood is largest at τ₀ = (γ · mean(τ^γ))^(1/γ), so it searches γ alone, from 0.05 to 20. `analyze`
does not call it.

* Uncorrelated values give γ near 1, an exponential. The return intervals are whole numbers from 1 on, which moves
  the estimate above 1: over 40 seeds of the example above, γ has a mean of 1.08 and a standard deviation of 0.10.
  The 1.266 of the example is the largest of them.
* Long-term correlated values give γ below 1. For 1/f^β noise Bunde et al. (2005) predict γ = 1 − β. One series of
  65536 values of 1/f^0.6 noise gives 0.41 at the 95th percentile.

It raises `ValueError` with fewer than 10 intervals, with an interval that is not a positive finite number, when all
intervals are equal, and when the likelihood is largest at a limit of the search, for example
`return_intervals: all 12 intervals are 60; expected intervals that vary, a stretched exponential has no fit to a
constant`.

<a id="115-java-tools-in-opentsx-core-2x-model"></a>

## 11.5 Run the Java tools on 2.x data

The Java implementations work on `org.opentsx.data.series.TimeSeriesObject` and on TSBucket sequence files. Most are
standalone programs with a `main` method:

| Area | Classes |
|------|---------|
| DFA / MFDFA | `org.opentsx.algorithms.detrending.methods.DFA`, `detrending.SingleDFATool`, `detrending.MultiDFATool`, `detrending.MFDFAAnalyzer` |
| return intervals | `org.opentsx.algorithms.ris.RISTool` |
| event synchronization | `org.opentsx.algorithms.eventsynchronisation.ESCalc`, `…experiments.ESMain` |
| statistics | `algorithms.statistics.GrangerCausality`, `EntropyTool`, `DistributionTester`, `ShapiroTester` |
| generators | `org.opentsx.generators.TSGenerator`, `LongTermCorrelationSeriesGenerator`, `FFTPhaseRandomizer` (surrogates) |
| data | `data.series.TimeSeriesObject`, `data.loader.MessreihenLoader`, `data.generator.RNGWrapper`, `core.TSBucket` |
| GUI | `app.bucketanalyser.MacroRecorder2` (start: `bin/000_launch_tsa_workbench.sh`) |
| guided demos | `org.opentsx.demo.onboarding.*`. `bin/episode_02_create_timeseries.sh` starts `SimpleTimeSeriesCreation`, `bin/episode_03_basic_operations.sh` starts `BasicOperations`, `bin/episode_09_analysis.sh` starts `TimeSeriesAnalysis` and `bin/episode_10_production_config.sh` starts `ProductionConfig`. `AnomalyDetection` and `TimeSeriesOperations` have a `main` method and no script. |

`ESCalc` counts only events with two neighbours and normalizes by sqrt((m1 − 1)(m2 − 1)) for m1 and m2 events. So
its Q differs from `overall_sync` in Python. Its delay is positive when the first series follows the second: the
opposite sign of `delay` in Python.

To use them with v2 data, convert episodes with the legacy bridge ([chapter 13](13-migration.md)).

`BucketAnalyserTool` uses the supplied input folder and DFA flags. Optional arguments select a filename
substring and record limit. See [bucket analysis inputs](../../opentsx-core/README.md#supply-bucket-analysis-inputs).

The desktop AR, MA, ARMA, ARIMA, SARIMA and CARIMA actions are disabled because they have no implementation.
The modern tracker also disables Java and Scala export. Both trackers use `Show PFD` to display JSON in the window.
The modern tracker starts without sample tree items.

Local SequenceFile loading reads the actual records, handles empty buckets and respects the file loader's
record limit. Processing errors stop the load with their cause retained. The format stores labels and Y values;
loading reconstructs sequential X values. See [local bucket loading](../../opentsx-core/README.md#reload-local-sequencefile-buckets).

Executing a `MacroRunner` or an exported Java/Scala bucket workflow stops with `UnsupportedOperationException`:
context-based loading and named bucket processing are not implemented. See
[bucket workflow support](../../opentsx-core/README.md#check-bucket-workflow-support) for the exact error messages.

The desktop FFT action derives its sampling rate from uniformly spaced coordinates. For integer index coordinates
with spacing 1, enter the sampling rate in the dialog. Frequencies use the reciprocal of the coordinate unit.
See [FFT coordinates](../../opentsx-core/README.md#inspect-fft-coordinates) for input requirements and output limits.

`FFTPhaseRandomizer` and `TimeSeriesObjectFFT` make phase-randomized surrogates. For a length that is a power of two a
surrogate keeps the amplitude spectrum and has mean 0; other lengths are padded with zeros and the surrogate is cut
back to the input length, so neither holds. The `TimeSeriesObjectFFT` methods take a `java.util.Random` to repeat a
surrogate; `FFTPhaseRandomizer` does not. See
[surrogates](../../opentsx-core/README.md#make-a-phase-randomized-surrogate).

`TimeSeriesObject.writeToFile` writes every pair, and `MessreihenLoader.loadMessreihe_2` reads the file back. Both
stop with an exception that names the file. No `MessreihenLoader` call changes the delimiter or the row limit of
another call, and every method names the file and the line it cannot read. See
[export and load](../../opentsx-core/README.md#export-and-load-a-series).

The logarithm methods of `TimeSeriesObject` give NaN for a value of 0 or below, and the desktop action for the natural
logarithm is labelled `ln`. See [logarithms](../../opentsx-core/README.md#take-logarithms).

`LongTermCorrelationSeriesGenerator.getRandomRow(length, beta, random, false, false)` filters every beta, 0 included,
returns a series with variance 1 and gives the same series for the same seed. Its `main` writes its tables into the
directory of its first argument, `./data/` by default. See
[long-range correlated noise](../../opentsx-core/README.md#generate-long-range-correlated-noise).

`RISTool` opens no window, rejects a NaN probability with `IllegalArgumentException` and stops with English messages.
See [return intervals](../../opentsx-core/README.md#collect-return-intervals).

`TSBucket` throws `IOException` when its file system cannot be opened, writes no CSV file in its place and takes the
folder of local input files from the caller. See [write a bucket](../../opentsx-core/README.md#write-a-bucket).

### Run the Flink jobs (`opentsx-flink-core`, Flink 1.20.5)

* `PFDRecorder.toJSON()` is a preview. File export and the dynamic runtime accept only Kafka input,
  `NORMALIZE` centering and `LOG` output. Topics and broker addresses require explicit configuration.
  See [PFD validation](../devguide/validation/PFD_VALIDATION_PLAN.md).
* `pfd.DynamicTopologyJob` builds a Flink topology from a JSON processing-flow descriptor; the schema is in
  [`docs/devguide/schemas`](../devguide/schemas/).
* `stats.StatsJob` runs the v2 statistics engine (see [chapter 7](07-stats-quality.md), section 7.2).

### Generate load and manage 2.x topics (`opentsx-lg`, `opentsx-connectors`)

* `TSDataSineWaveGenerator` and the request/response latency probes.
* Topic tools `TopicsUP`/`TopicsDOWN`/`TopicsCHECK` (they read `config/topiclist.def`). `TopicsDOWN` deletes every
  topic listed in that file. Do not run it against a shared broker.
* These tools use the 2.x topics and Confluent serializers. For KafScale, use the v2 path ([chapter 4](04-kafscale-storage.md)).
