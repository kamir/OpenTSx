# 11. Classic analytics (DFA, MFDFA, ES, RIS)

Run DFA, MFDFA, event synchronization and return-interval statistics on a series, and find the Java tools of the 2.x
model. The example output comes from the `opentsx` package 1.0.0 on Python 3.13 with NumPy 2.5.5.

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

`r["interpretation"]` does not follow the ≈ rows of the table. It uses one-sided bands:

| α | `interpretation` |
|---|------------------|
| below 0.5 | `Anti-correlated (mean-reverting)` |
| 0.5 to below 0.55 | `Uncorrelated (white noise)` |
| 0.55 to below 1.0 | `Correlated (persistent, trending)` |
| 1.0 to below 1.05 | `1/f noise (pink noise)` |
| from 1.05 | `Non-stationary (Brownian motion)` |

White noise with an estimate of 0.48 gets `Anti-correlated (mean-reverting)`. 1/f noise with an estimate of 0.97
gets `Correlated (persistent, trending)`.

Set `polynom_order=2` (DFA2) to remove quadratic trends. `analyze` passes `min_scale` (default 10), `max_scale`
(default n // 4) and `num_scales` (default 20) to `calculate`. The scales are log-spaced integers without duplicates.

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
print(round(r["delta_h"], 3), round(r["delta_alpha"], 3), r["is_multifractal"])
```

Output:

```text
0.117 0.213 True
```

`r` also holds `q_values`, `scales`, `Fq`, `h_q`, `tau_q`, `alpha`, `f_alpha` and `h_2`.
`h_2` is h(0.5), the value in the middle of the q range, not h(2).

`is_multifractal` is `True` when `delta_h` is above 0.1. `delta_h` is h(q) at the first q minus h(q) at the last q.
The default q runs from -10 to 10.5 in steps of 0.5, so `delta_h` is h(-10) minus h(10.5). `delta_alpha` does not
decide it. The threshold is a heuristic: the white noise above is monofractal, and it gives `True`.

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
print(round(r["overall_sync"], 3), round(q_rand, 3), r["leader"], round(r["delay"], 3))
```

Output:

```text
0.694 0.307 Series 1 0.538
```

`y` repeats `x` 3 samples later with added noise, so series 1 leads. `r` also holds `sync_1_to_2`, `sync_2_to_1`,
`lead_lag_strength`, `num_events_1`, `num_events_2` and `tau_max`. `delay` is positive when series 1 leads.
`leader` is `None (symmetric)` unless `sync_1_to_2` and `sync_2_to_1` differ by more than 0.05.

Every event pair gets its own window: half the smallest distance from either event to its neighbouring events (the
local τ of Quiroga et al.). So `overall_sync` stays in [0, 1]. `EventSynchronization(tau_max=...)` caps that window.
Without a cap, `r["tau_max"]` is `inf`.

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
This matters for risk estimation, for example of storms or load peaks. `RIS` does not test for a stretched
exponential.

```python
import numpy as np
from opentsx import RIS

x = np.random.default_rng(1).standard_normal(8192)
r = RIS(threshold_percentile=95).analyze(x)
print(r["num_events"], round(r["mean_ri"], 2), r["median_ri"], round(r["risk_parameter"], 3), r["risk_interpretation"])
print(r["gamma"], round(r["tau_0"], 3))
```

Output:

```text
410 19.91 15.0 0.907 Moderate risk (random/Poisson)
1.0 1.04
```

`r` also holds `threshold`, `event_indices`, `return_intervals`, `std_ri`, `min_ri` and `max_ri`.

To judge clustering, read `risk_parameter`: the standard deviation of the return intervals divided by their mean.
`risk_interpretation` is:

* `Low risk (regular events)` below 0.8,
* `Moderate risk (random/Poisson)` from 0.8 to below 1.2,
* `High risk (clustered events)` from 1.2,
* `Insufficient events` with fewer than two events.

`RIS` does not fit a stretched exponential. `gamma` is 1.0 whenever the fit runs. `gamma` and `tau_0` are 0.0 with
fewer than 10 return intervals, with fewer than 3 non-empty histogram bins, or when the line fit fails. `tau_0` is
exp(−b/a) of a straight line log P(τ) = a·log τ + b through the histogram of the intervals.

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
