# OpenTSx: Smart Topics (draft)

> Status: **draft, decisions taken (2026-10-12, section 9)**. Builds on P2 (episode log on KafScale), P4
> (`opentsx-stats`, `opentsx-openmetadata`), P5 (segmentation, retrieval) and P6 (resampling/alignment).
> Implementation as **P7** after the analytics gaps.

## 1. Idea

A **Smart Topic** is a *derived topic plus the processor that fills it*:

* A Smart Topic receives as streams
  * the **raw data** of a selection of series (episodes/observations),
  * their **statistics** (`series-stats.v2`),
  * optionally **context data** from any other topics (weather forecast, setpoints of the grid operator,
    neighboring turbines, maintenance events, …).
* A **declarative specification** defines
  * which features are built from them,
  * which training routine runs and when,
  * which prediction models (one or more) are trained and compared.
* The processor continuously delivers **smart metrics**: forecasts with an interval, residuals, anomaly scores, model
  drift, feature health.
* Through a **prediction API** you can predict with the learned model at any time:
  * *with* context data (passed in or from the stream),
  * or *without* context data (for this there is a context-free model variant).

```
 tsx.wind.episodes.v2 ──┐
 tsx.wind.series-stats.v2 ─┼──► Smart Topic processor ──► tsx.wind.smart.<name>.metrics.v2   (smart metrics)
 weather.forecast.v1 ───┘   │  Features · Training · Scoring   └► tsx.wind.smart.<name>.models.v2    (model registry)
 (context, declared)         │
                             └──► prediction API: predict(series, horizon[, context])
```

## 2. Terms

| Term | Meaning |
|---------|-----------|
| **Target** | The series to be forecast (one or more), for example `wind.turbine.power_active{park=ostsee-1,turbine=*}`; one specification applies to all matching series (one model per series or one global model, section 5) |
| **Context** | Additional input series/topics with a join rule (same turbine, same park, as-of by time) |
| **Feature pipeline** | Deterministic derivation: resampling → lags → rolling windows → stats fields → calendar → context joins |
| **Training routine** | When (schedule, data volume, drift), on which window, with which split, which model candidates, which selection rule |
| **Model** | Trained candidate with version, training window, feature schema hash, quality measures; champion per target and horizon |
| **Smart metric** | Continuously emitted derived value (forecast, interval, residual, score, drift) |

## 3. Specification (declarative, versioned)

YAML, versioned like the data point catalog. The hash of the specification goes into every smart metric and every
model (lineage, reproducibility).

```yaml
smartTopic: ostsee-power-forecast
version: 1.0.0
domain: wind
description: Leistungsprognose je Turbine, 10 min bis 6 h, mit und ohne Wetterprognose

target:
  metric: wind.turbine.power_active
  tags: { park: ostsee-1 }               # all turbines of the park
  source: episodes                       # episodes | observations
  resample: { every: PT10M, agg: MEAN }  # base step of the models

stats:                                   # from tsx.wind.series-stats.v2 (opentsx-stats)
  window: PT10M
  fields: [std, q05, q95, nanShare, gapCount]

context:
  - name: wind_speed
    metric: wind.met.wind_speed
    match: { turbine: same }             # same turbine
    resample: { every: PT10M, agg: MEAN }
  - name: forecast_ws
    topic: tsx.weather.forecast.v1       # any other topic (Avro/JSON), field path + time field
    key: { park: same }
    value: $.windSpeed100m
    time: $.validTime
    join: as-of                          # last known value ≤ target time
    maxAge: PT3H
    optional: true                       # the model must also work without it

features:
  lags: [1, 2, 3, 6, 12, 144]            # in base steps
  rolling: [ { window: PT1H, fn: [mean, std] }, { window: PT6H, fn: [mean] } ]
  calendar: [hourOfDay, dayOfYear]       # cyclically encoded (sin/cos)
  derived:
    - name: ws_cubed                     # physics prior: P ~ v^3
      expr: "pow(wind_speed, 3)"

training:
  schedule: { every: P1D, at: "02:00Z" } # and/or:
  retrainOn: { driftScore: "> 3.0", minNewSamples: 1000 }
  window: { sliding: P60D }
  split: { holdout: P7D, gap: PT6H }     # by time, no leakage
  horizons: [PT10M, PT1H, PT6H]
  models:
    - { name: persistence,    type: PERSISTENCE }                      # reference/baseline
    - { name: seasonal-naive, type: SEASONAL_NAIVE, params: { season: P1D } }
    - { name: ets,            type: HOLT_WINTERS,   params: { season: P1D } }
    - { name: ridge-ar,       type: LINEAR,         params: { alpha: 1.0 }, uses: [lags, rolling, stats, calendar, context] }
    - { name: gbm,            type: EXTERNAL,       runtime: python, entrypoint: "opentsx_models.lightgbm:train" }
  select: { by: MAE, per: [target, horizon], mustBeat: persistence }
  intervals: { method: CONFORMAL, levels: [0.8, 0.95] }   # from holdout residuals

outputs:
  metrics: [FORECAST, INTERVAL, RESIDUAL, ANOMALY_SCORE, DRIFT, FEATURE_HEALTH]
  emitEvery: PT10M
```

## 4. Smart metrics (output topic)

Topic `tsx.<domain>.smart.<name>.metrics.v2`, key = `seriesId`, new Avro schema `SmartMetric` (model v2.1, additive):

| Field | Content |
|------|--------|
| `smartTopic`, `specHash` | Origin (specification + version) |
| `seriesId`, `ts`, `horizon` | Target series, the point in time the value applies to, forecast horizon (ISO duration; empty for non-forecasts) |
| `kind` | `FORECAST`, `INTERVAL_LOW/HIGH`, `RESIDUAL`, `ANOMALY_SCORE`, `DRIFT`, `FEATURE_HEALTH`, … |
| `value`, `level` | Value; confidence level for intervals |
| `modelId`, `modelVersion`, `contextUsed` | which model; whether context was used as input (or the context-free variant) |
| `issuedAt` | when computed (for forecast evaluation "as issued") |

* **Residual and anomaly score** arise as soon as the actual value arrives: `|ist − prognose| / σ_holdout`.
  From them, events (for example underperformance) can be segmented into episodes again (P5, threshold segmenter).
* **Drift** is the rolling error relative to the holdout error. It can trigger retraining (`retrainOn`).
* **Feature health**: share of missing or stale context features. If a context fails, the scoring switches
  automatically to the context-free variant and records this in `contextUsed`.

KafScale has no compaction. Readers therefore take the newest record per (seriesId, ts, horizon, kind), as with
`series-stats.v2`.

## 5. Models and training

* **Training in Python, scoring in Java:**
  * Training routines run in Python, for example statsmodels, LightGBM, sktime/Darts, scikit-learn.
  * They run as a job (notebook, PySpark or a container started by the processor) and read the data through
    Iceberg/replay (P2/P3), not "whatever is in memory right now".
  * For this, the Python side builds the feature matrix from the same specification. A shared feature definition
    plus test vectors make sure that Java and Python compute the same features (as with model v2).
  * Models are exported in a portable format, preferably **ONNX** (sklearn/LightGBM via `skl2onnx`/`onnxmltools`).
    Statistical models without a path to ONNX (for example ETS) export their parameters into a small JSON format that
    the Java scorer knows.
  * The processor scores in Java (ONNX Runtime for Java), so without Python in the streaming path.
* **Variants per target:**
  * Every target × horizon × candidate is trained.
  * The selection (champion) is declarative (`select`).
  * Every candidate with context features is also trained **without context** ("with or without context data").
* **Granularity: local per series, global federated (Wayang approach):**
  * The default is **one model per series** (for example per turbine).
  * In addition there is a **global model per target**. It is built in a federated way, following the pattern of Apache
    Wayang / Scalytics Federated:
    * Every "system" (park, site, operator) trains locally on its own data.
    * It pays only model updates (parameters/gradients, or boosting statistics) into an aggregation round,
      for example FedAvg/FedProx; for trees, histogram-based.
    * Raw data does not leave the system.
  * The global model serves as fallback and cold start for new series. A local model may be fine-tuned from it.
  * In the specification: `training.federation: { scope: system, aggregate: FEDAVG, rounds: 10, minParticipants: 3 }`.
  * The mapping series → system comes from the tags (for example `park`, `operator`).
* **Time split with a gap:** no random splits, so no leakage through lags.
* **Intervals:** conformal from holdout residuals (distribution-free, per horizon).
* **Reproducibility:** A model is uniquely defined by
  * specification hash,
  * training window,
  * feature schema hash,
  * candidate,
  * seed,
  * for federated models: round and list of participants.
* **Model registry: MLflow.**
  * Every run is an MLflow run with parameters, metrics (MAE per horizon, quantiles of the holdout residuals),
    specification hash and feature schema as tags.
  * The artifact is ONNX or parameter JSON.
  * It is registered as a *Registered Model* `<smartTopic>/<target>/<horizon>`, with aliases for champion and
    candidates (MLflow ≥ 2.9: Model Aliases instead of Stages).
  * The processor queries the registry for the alias `champion` and loads new versions without a restart.
  * The KafScale topic `…smart.<name>.models.v2` carries only **events** ("version x is champion for …").
    They serve traceability and replay; the registry itself stays MLflow.

## 5a. Approval of a model for a purpose (draft)

> As of 2026-10-13, from the review briefing in [15](15-ENTSCHEIDUNGSKREIS.md), section 7. Not implemented.

The selection in section 5 (`select: { by: MAE, mustBeat: persistence }`) says which model is the best of the
candidates. It does not say whether it supports a specific decision. For that, the decision card (`opentsx.decision.v1`)
declares per analysis:

* the **inputs** as signals with grade, maximum age and allowed origin. For this the specification gets
  `target.signal` next to the selection by metric and tags; its own `resample` is dropped when the signal is available
  clean,
* the **range of validity**: the range of the inputs in which the model has been checked,
* the **evidence**: period, key figure, comparison with the reference, and whether it rests on generated data,
* the **uncertainty** and the width beyond which a result no longer supports the decision.

From this, every result carries a usability status (usable, restricted, blocked) with reasons. A result outside the
range of validity is shown and marked as not supported. Complete origin of the inputs is a precondition, not proof of
quality.

## 6. Prediction API

```java
SmartTopic st = SmartTopics.open(spec, stores, registry);

Forecast f = st.predict("wind.turbine.power_active{park=ostsee-1,turbine=T07}", Duration.ofHours(1));   // context from the stream
Forecast g = st.predict(seriesId, Duration.ofHours(6), Context.of("forecast_ws", 11.2));            // context passed in
Forecast h = st.predict(seriesId, Duration.ofHours(6), Context.none());                             // deliberately without context
// Forecast: value, intervals, modelId/version, contextUsed, issuedAt, feature health
```

REST mirror (shared with the retrieval REST layer from P5):

```
POST /v1/smart-topics/{name}/predict
{ "seriesId": "…", "horizon": "PT1H", "context": { "forecast_ws": 11.2 }, "useContext": "auto|required|none" }
GET  /v1/smart-topics/{name}/models
GET  /v1/smart-topics/{name}/metrics?seriesId=&from=&to=&kind=
```

Python: the same REST client. For notebooks, `opentsx.smart` loads smart metrics as a DataFrame, like `stats_to_frame`.

## 7. Runtime

The processor follows the pattern of `StatsProcessor`, so it is fit for KafScale:

* manual partition assignment, no consumer groups, no commits,
* a checkpoint file with the positions and the whole state: feature buffers, context as-of states, running
  errors, current champions.

For event time, the following applies:

* Every target series has its own event time.
* Context joins are as-of, with `maxAge`.
* A forecast for `t + h` is produced at time `t` and later evaluated against the actual value.

Training runs either in the processor (Java models, asynchronously in the background) or externally (Python, section 9).
Both ways write `ModelArtifact` records, and the processor picks up new champions without a restart.

## 8. Metadata (OpenMetadata)

* **Smart Topic entry:** topic plus `MlModel` entities (OM 2.0 supports `MlModel` with `mlFeatures`,
  `mlHyperParameters`, `target`, `algorithm`).
* **Lineage:** The feature sources point to the columns of the signal view and to the context topics. This way the
  lineage runs from the raw data through the model to the smart metrics.
* **Profiles and DQ:** The model quality (MAE per horizon, drift) is kept as a profile/DQ result, as in P4.

## 9. Decisions (2026-10-12)

| # | Question | Decision |
|---|-------|--------------|
| 1 | Model runtime | **Python training first**; Java scores exported models (ONNX or parameter JSON) |
| 2 | Model registry | **MLflow** (Registered Models, Aliases); KafScale topic only for champion events |
| 3 | Granularity | **Model per series**; plus a **global model, federated following the Wayang approach**: local training runs per system pay into the global model without moving raw data |
| 4 | Roadmap | **After P6** (analytics gaps) → Smart Topics are **P7** |

Still open, for the implementation:
* Which federation runtime: Apache Wayang directly (Java, cross-platform) or Scalytics Federated.
* How the training job is started: the processor calls MLflow Projects/containers, or an external scheduler.

## 10. Expansion stages (P7)

* **P7a: specification and features**
  * Spec parser and validation in Java and Python.
  * Feature pipeline (resampling, lags, rolling, stats fields, calendar, context as-of) in both languages, with
    shared test vectors.
  * Schema v2.1 with `SmartMetric`, additive, with a BACKWARD_TRANSITIVE check.
* **P7b: training (Python)**
  * `opentsx.smart.train(spec)` reads from Iceberg, makes a time split, trains the candidates with and without
    context and computes conformal intervals.
  * After that, export to ONNX or JSON and logging to MLflow (run, Registered Model, alias `champion`).
  * The baselines Persistence and Seasonal Naive are always included.
* **P7c: scoring processor (Java)**
  * `opentsx-smart`, fit for KafScale, with a checkpoint.
  * Loads the champion from MLflow and scores with ONNX Runtime.
  * Delivers the smart metrics (forecast, interval, residual, anomaly score, drift, feature health) and the
    prediction API (Java + REST).
* **P7d: federation**
  * Local training runs per system.
  * Aggregation (FedAvg for linear/NN models, histogram aggregation for trees) following the Wayang approach.
  * The global model lands as its own Registered Model; cold start rules.
* **Test against the wind farm simulator:**
  * Power forecast 10 min to 6 h.
  * Goal: clearly beat persistence with context (wind speed), at least match it without context.
  * Anomaly scores must hit the injected events.
  * A federated global model over two simulated parks must be better for a new park than local models without
    history.

Operation in the stream (processor, DAG, DevOps): see [11-SMART-TOPIC-PROZESSOR-DEVOPS](11-SMART-TOPIC-PROZESSOR-DEVOPS.md).

## 11. Personal Service Layer (proposal, 2026-10-12)

The existing SaaS stack (`opentsx-saas-backend`: FastAPI, JWT, users, organizations, teams; `opentsx-saas-frontend`:
React) becomes a user's or team's **control plane for their own smart series**. It holds no time series data (the
data plane stays KafScale/Iceberg/retrieval REST/MLflow), only references and rights:

| Today | Target |
|-------|------|
| `Flow` (JSON definition, owner, status) | Smart series specification (YAML, section 3), versioned, per user or team |
| `FlowExecution` | Training runs and deployments (MLflow run IDs, champion, processor status) |
| `FlinkService` | general runner: statistics processor, Smart Topic scoring, Python training (MLflow Project) |
| Dashboard | "My series": smart metrics, model quality/drift, DQ, prediction with or without context |
| Users and teams | Ownership of topic prefixes, Iceberg namespaces, OpenMetadata domains |

A personal instance is also the "system" of the federation (section 5): train locally, pay in only model updates.
Extension with system reconstruction, simulation and optimization: see the concept for network reconstruction
([`06-SYSTEMREKONSTRUKTION-OPTIMIERUNG.md`](06-SYSTEMREKONSTRUKTION-OPTIMIERUNG.md), phase P8).
