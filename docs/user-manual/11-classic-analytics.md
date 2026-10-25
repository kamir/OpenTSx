# 11. Classic analytics (DFA, MFDFA, ES, RIS)

OpenTSx started as a research toolkit for long-range correlations and extreme events in complex systems. Those
algorithms are still available:

* in Python, through the `opentsx` top-level API (the recommended way, with verified examples below),
* in Java, in `opentsx-core`, as tools on the 2.x `TimeSeriesObject` model.

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
r = EventSynchronization().analyze(x, y)   # events: local maxima above mean + 2·std (method="percentile": 95th)
r["overall_sync"], r["sync_1_to_2"], r["sync_2_to_1"], r["leader"], r["lead_lag_strength"], r["tau_max"]
```

> **Known issue.** With dense events and the automatically chosen `tau_max`, `overall_sync` can exceed 1.
> The implementation uses an average inter-event interval rather than the local, pairwise τ of the original method.
> Until this is fixed, set `tau_max` explicitly (smaller than half the typical inter-event interval) and compare
> values only within one configuration.

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

### Flink jobs (`opentsx-flink-core`, Flink 1.18, 2.x model)

* `TimeSeriesAnalysisJob` aggregates observation streams into windowed `TimeSeriesObject`s.
* `PFDRecorder.toJSON()` is a preview. File export and the dynamic runtime accept only Kafka input,
  `NORMALIZE` centering and `LOG` output. Topics and broker addresses require explicit configuration.
  See [PFD validation](../devguide/validation/PFD_VALIDATION_PLAN.md).
* `pfd.DynamicTopologyJob` builds a Flink topology from a JSON processing-flow descriptor; the schema is in
  [`docs/devguide/schemas`](../devguide/schemas/).
* A Flink adapter for the v2 statistics engine is planned (see [chapter 15](15-roadmap.md)).

### Load generation and Kafka tools (`opentsx-lg`, `opentsx-connectors`)

* `TSDataSineWaveGenerator` and the request/response latency probes.
* Topic tools `TopicsUP`/`TopicsDOWN`/`TopicsCHECK` (they read `config/topiclist.def`).
* These tools use the 2.x topics and Confluent serializers. For KafScale, use the v2 path ([chapter 4](04-kafscale-storage.md)).
