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

Open `http://127.0.0.1:18081/workbench` and select **Modelling**. Under
**Train, review and run on Kafka**, select **Load local catalogue**, choose the
forecast episode topic, select **Read topic series**, choose its series, and
select **Load training series**.

Capture loads the latest 512 samples of the selected series. It preserves the
absolute clock, unit, episode lineage and quality. The catalogue reads a bounded
tail of at most 200 records across at most 16 partitions. It rejects ambiguous
multi-partition series, conflicting metadata and overlapping revisions.
Capture is not an unbounded historical archive query.

The captured bucket becomes the finite fitting target above. Inspect its range
and select **Fit, backtest and forecast**. The initial ARIMA `(1,0,0)` setting
is an AR(1) candidate. Review its held-out errors, naive reference, fold bounds,
interval coverage and warnings before registration.

## Register the reviewed fit

Enter a **Registered model name**, such as `forecast-demo`, and a **Review
note** stating the intended local use. Set **Minimum relative MAE improvement**
and confirm **I reviewed this fit and its backtests**. Select **Register reviewed
fit**.

The server accepts only a completed run it retained itself. A browser cannot
upload executable artifacts or replace the evaluated parameters. The gate is
`1 - candidate_MAE / naive_MAE`, measured on the recorded evaluation origins.
The default threshold 0 requires performance at least equal to naive. A negative
threshold explicitly permits degradation; it is recorded with the review.
A perfect baseline rejects any candidate with nonzero error.

Registration stores the version, metrics, review and immutable JSON fit in
MLflow. The `candidate` alias identifies the reviewed registration. The artifact
contains fixed coefficients, Kalman state/covariance, cumulative trend position,
runtime versions and source identity. It contains no pickle or executable import.

Use **Predict with registered artifact** to forecast from its preserved training
checkpoint without refitting. This does not advance a running scorer. Registered
SARIMAX prediction requires a future context matrix and its
`future_available_at_micros` matrix through the numerical API. Every context
cell must have been available by the preserved last observation;
the interface states that requirement instead of fabricating context.

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
supported. SARIMAX continuous deployment is blocked because live context joins
and their availability evidence are not implemented.

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
source topic/partition/offset, target unit/clock, observed one-step residuals and
future conditional Gaussian forecasts. `origin_micros` is the exclusive end of
the assimilated observations and the first unobserved coordinate. The first
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
automatic retraining, drift approval, live SARIMAX joins or automatic version
replacement. Qualification on real ENTSO-E data is a separate acceptance step.
