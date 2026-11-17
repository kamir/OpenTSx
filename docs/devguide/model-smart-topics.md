# Train and deploy a statistical Smart Topic

Use the local CP Workbench delivery to register an evaluated fit in MLflow and
score new Kafka episodes with fixed parameters. You need the running
[local CP profile](local-cp-workbench.md), its external CP network and a regular
v2 episode source in the configured namespace. The profile uses MLflow 3.9.0 and
the numerical versions recorded by its built manifest.

## Create a reproducible training source

From the delivery checkout, seed the dedicated synthetic AR(1) source:

```bash
python3 deploy/local-cp/workbench.py demo-seed
```

This creates `tsx.workbench-demo.forecast.episodes.v2` with one partition and
512 regular one-second observations in MW. It marks them synthetic and supplies
good quality bytes. It leaves CP brokers and existing source topics unchanged.
Repeating the command validates the existing demonstration source instead of
publishing the training episode again.

## Capture and evaluate the target

Open `http://127.0.0.1:18081/workbench` and select **Modelling**. Under **Train,
review and run on Kafka**, select **Load local catalogue**, choose the forecast
episode topic, select **Read topic series**, choose its series, and select
**Load training series**.

Capture loads the latest 512 samples of the selected series. It preserves the
absolute clock, unit, episode lineage and quality. The catalogue reads a bounded
tail of at most 200 records across at most 16 partitions. It rejects ambiguous
multi-partition series, conflicting metadata and overlapping revisions. Capture
is not an unbounded historical archive query.

The captured bucket becomes the finite fitting target above. Inspect its range
and select **Fit, backtest and forecast**. The initial ARIMA `(1,0,0)` setting
is an AR(1) candidate. Review its held-out errors, naive reference, fold bounds,
interval coverage and warnings before registration.

## Register the reviewed fit

Enter a **Registered model name**, such as `forecast-demo`, and a **Review
note** stating the intended local use. Set **Minimum relative MAE improvement**
and confirm **I reviewed this fit and its backtests**. Select **Register
reviewed fit**.

The server accepts only a completed run it retained itself. A browser cannot
upload executable artifacts or replace the evaluated parameters. The gate is
`1 - candidate_MAE / naive_MAE`, measured on the recorded evaluation origins.
The default threshold 0 requires performance at least equal to naive. A negative
threshold explicitly permits degradation; it is recorded with the review. A
perfect baseline rejects any candidate with nonzero error.

Registration stores the version, metrics, review and immutable JSON fit in
MLflow. The `candidate` alias identifies the reviewed registration. The artifact
contains fixed coefficients, Kalman state/covariance, cumulative trend position,
runtime versions and source identity. It contains no pickle or executable
import.

Use **Predict with registered artifact** to forecast from its preserved training
checkpoint without refitting. This does not advance a running scorer. Registered
SARIMAX prediction requires a future context matrix and its
`future_available_at_micros` matrix through the numerical API. Every context
cell must have been available by the preserved last observation; the interface
states that requirement instead of fabricating context.

## Start the Smart Topic

Keep the matching input topic and series selected. Enter an output such as
`tsx.workbench-demo.smart.forecast.predictions.v1` and select **Start Smart
Topic**. Select **Refresh deployments** to read the actual worker state.

The output format is `<prefix>smart.<deployment>.predictions.v1`. The deployment
name uses 1 to 64 lowercase letters, digits, underscores or hyphens and starts
with a letter or digit. The server rejects an unrelated existing output topic.
An existing deployment keeps its immutable model/source/output binding.

Admission verifies that the registered training values occur at the exact
timestamps in the selected source partition, with the same series, interval and
unit. It verifies the MLflow artifact before promoting the selected version to
`champion`. Each deployment pins that exact version; another alias update does
not silently replace its numerical state.

The worker starts at the retained source beginning, verifies/skips the training
prefix, and consumes contiguous observations after the fitting window. It
updates filtering state with the fixed coefficients; it does not run the
optimizer. Absolute time and a declared unit are required. ARIMA and SARIMA are
supported. SARIMAX additionally requires the verified context binding described
below.

## Append observations and inspect predictions

Publish a new block of 64 observations:

```bash
python3 deploy/local-cp/workbench.py demo-append
```

Select **Refresh deployments** and **Inspect scoring results**. The Workbench
shows actual state, read/scored/skipped record counts, next source offset and
acknowledged prediction records. Its recent-result view is a persistent local
cache of at most 100 acknowledged records, with a bounded response size. The
output topic contains the published prediction history.

Records use `opentsx.smart-prediction.v1`, an immutable model/artifact identity,
source topic/partition/offset, target unit/clock, observed one-step residuals
and future conditional Gaussian forecasts. `origin_micros` is the exclusive end
of the assimilated observations and the first unobserved coordinate. The first
forecast timestamp equals this origin. Source offsets in payloads are decimal
strings to preserve Kafka int64 precision.

## Stop and resume the scorer

Use **Stop scoring** and **Start scoring** for the selected deployment. Desired
state and actual state are separate. A successful start request can initially
show `starting`; refresh before treating it as running.

The persistent checkpoint combines filter state, cumulative sample position and
next source offset. It advances only after confirmed output publication. A
service restart restores deployments whose desired state is running. A stopped
deployment remains stopped. Only one Workbench process can own the state volume.
The local profile permits at most four active scoring workers.

Delivery is at least once. A crash after broker acknowledgement but before the
checkpoint commits can repeat an output. The deterministic prediction key
identifies that repeat. The local cache deduplicates equal keys; downstream
consumers must also deduplicate when needed. This profile does not claim Kafka
transactional exactly-once delivery.

## Resolve explicit failures

Flagged/nonfinite data, clock or unit changes, revised/overlapping observations,
gaps after the origin, unavailable retained offsets, corrupt checkpoints and
incompatible artifacts fail the deployment. The worker does not silently seek
forward, interpolate, change the selected model or refit on future samples.
Inspect the recorded failure and evaluate a corrected deployment deliberately.

Registry outages, failed numerical fits and failed review thresholds are errors.
Registration, promotion and worker execution remain separate operations. This
local profile does not provide production authentication, tenant isolation,
automatic retraining, drift approval or automatic version replacement.
Qualification on real ENTSO-E data is a separate acceptance step.

## Join known-ahead SARIMAX context

Seed the separate two-column synthetic SARIMAX demonstration:

```bash
python3 deploy/local-cp/workbench.py demo-seed --scenario sarimax
```

Load `tsx.workbench-demo.sarimax.episodes.v2` through the training catalogue.
Select SARIMAX. Select **Load context topics**, choose
`tsx.workbench-demo.sarimax.context.v1`, select **Read context schemas**, choose
partition 0, and select **Load aligned SARIMAX context**. The capture preserves
column order, units, values and availability times for the selected target range
and horizon. Changing the target, range or evaluation settings requires a new
capture. Review the backtest and register the fit. Select **Load scoring context
topics**, choose the same context topic, select **Read scoring context schemas**
and choose partition 0 when starting its Smart Topic.

The `opentsx.forecast-context.v1` record carries `record_id`,
`target_series_id`, `context_names`, `context_units`, `t_start_micros`,
`interval_micros`, `issued_at_micros`, `values`, `available_at_micros` and
`revision: 0`. `record_id` is the canonical SHA-256 of all other fields.
Demonstration records use the target series identifier as their Kafka key. The
body digest identifies the context record. Rows have a regular valid-time grid.
Each availability cell must be no earlier than the record's issue time. These
are source-supplied claims; Kafka arrival time does not certify real-world
publication time.

This profile uses `immutable_known_ahead` context. It rejects revisions,
conflicting values, changed schema, units or target, and off-grid rows.
Historical context for a new observation must have been available by the
preceding observation time. All future horizon cells must be available by the
updated last observation time. It never substitutes unavailable values.

Exercise a missing-context wait by publishing the target separately:

```bash
python3 deploy/local-cp/workbench.py demo-target --scenario sarimax
```

Refresh deployments. The worker reports `waiting_context` with the missing range
and cutoff. Target offset, numerical state and output remain unchanged. Publish
its context and refresh again:

```bash
python3 deploy/local-cp/workbench.py demo-context --scenario sarimax
```

The worker resumes the pending target. For normal use, publish both in order:

```bash
python3 deploy/local-cp/workbench.py demo-append --scenario sarimax
```

Context replay starts at the earliest record pinned by the training capture. The
selected context partition must have one ordered schema and contiguous
valid-time rows in the admitted snapshot.

The durable checkpoint preserves both source cursors and bounded context rows,
including lookahead already consumed for a future horizon. Context-only progress
can be saved during a wait; target and numerical progress commit after output
acknowledgement. Prediction records include the actual context provenance.
SARIMAX accepts at most 2,048 observations per target record, eight context
columns, 2,048 rows per context record and 4,096 buffered context rows. Context
records and input/output records are bounded to 4 MiB.

A registered SARIMAX fit without declared context units remains eligible for
finite reuse but cannot deploy continuously. Capture context with declared
units, refit and review it before registration. Forecast vintages and revised
context require a separate evaluated policy; this join does not flatten them
into a historical matrix.
