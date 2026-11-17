import { useEffect, useState } from "react";
import { useFlow } from "./flow";
import type { ModellingResult } from "./modelling";
import {
  captureTrainingSeries,
  changeScoringState,
  createScoringDeployment,
  loadDeployments,
  loadLifecycleCatalogue,
  loadScoringResults,
  loadTopicSeries,
  loadContextTopics,
  loadContextSchemas,
  matchingContextSchema,
  predictRegisteredModel,
  registerReviewedFit,
  type CatalogSeries,
  type RegisteredModel,
  type RegisteredPrediction,
  type ScoringDeployment,
  type SmartPrediction,
  type CatalogContext,
} from "./lifecycle";
import "./lifecycle.css";

function value(number: number | null) {
  return number === null ? "Undefined" : String(Number(number.toPrecision(7)));
}
function outputError(topic: string, prefix: string): string | null {
  if (!prefix)
    return "Load the local catalogue to verify the output namespace.";
  const head = `${prefix}smart.`,
    tail = ".predictions.v1";
  if (
    !topic.startsWith(head) ||
    !topic.endsWith(tail) ||
    !/^[a-z0-9][a-z0-9_-]{0,63}$/.test(topic.slice(head.length, -tail.length))
  )
    return `Use ${head}<name>${tail}; the name needs 1 to 64 lowercase letters, digits, underscores or hyphens, starting with a letter or digit.`;
  return null;
}
function checkpointText(deployment: ScoringDeployment) {
  const checkpoint = deployment.checkpoint;
  if (!checkpoint) return "No checkpoint reported.";
  const counters = checkpoint.counters;
  const c =
    counters && typeof counters === "object" && !Array.isArray(counters)
      ? (counters as Record<string, unknown>)
      : null;
  const display = (n: unknown) =>
    typeof n === "number" && Number.isSafeInteger(n)
      ? String(n)
      : "Not reported";
  return `Read ${display(c?.read)}; scored ${display(c?.scored)}; skipped ${display(c?.skipped)}; next input offset ${display(checkpoint.next_offset)}.`;
}
function dataObject(value: unknown): Record<string, unknown> | null {
  return value && typeof value === "object" && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : null;
}
function numericArray(value: unknown): number[] | null {
  return Array.isArray(value) &&
    value.length > 0 &&
    value.length <= 262144 &&
    value.every((n) => typeof n === "number" && Number.isFinite(n))
    ? value
    : null;
}
function utcMicros(stamp: number): string | null {
  if (!Number.isSafeInteger(stamp)) return null;
  const second = Math.floor(stamp / 1e6),
    date = new Date(second * 1000);
  return Number.isNaN(date.getTime())
    ? null
    : date
        .toISOString()
        .replace(".000Z", `.${String(stamp - second * 1e6).padStart(6, "0")}Z`);
}
function LatestPrediction({ record }: { record: SmartPrediction }) {
  const target = dataObject(record.payload.target),
    forecast = dataObject(record.payload.forecast),
    observed = dataObject(record.payload.observed);
  const mean = numericArray(forecast?.mean),
    lower = numericArray(forecast?.lower),
    upper = numericArray(forecast?.upper),
    times = numericArray(forecast?.timestamps_micros);
  const unit =
    typeof target?.unit === "string" && target.unit ? target.unit : null;
  const interval =
    typeof target?.interval_micros === "number" ? target.interval_micros : NaN;
  const actual = numericArray(observed?.actual),
    residual = numericArray(observed?.residual);
  const context = dataObject(record.payload.context);
  const absoluteError =
    actual && residual && actual.length === residual.length
      ? residual.reduce((sum, n) => sum + Math.abs(n), 0) / residual.length
      : NaN;
  const available = !!(
    target &&
    typeof target.series_id === "string" &&
    target.series_id &&
    unit &&
    target.time_kind === "absolute" &&
    Number.isSafeInteger(interval) &&
    interval > 0 &&
    mean &&
    mean.length <= 96 &&
    lower &&
    upper &&
    times &&
    lower.length === mean.length &&
    upper.length === mean.length &&
    times.length === mean.length &&
    times.every(
      (t, i) => utcMicros(t) !== null && (!i || t - times[i - 1] === interval),
    ) &&
    mean.every((m, i) => lower[i] <= m && m <= upper[i])
  );
  return (
    <section aria-label="Latest cached scoring prediction">
      <h4>Latest cached prediction</h4>
      <p>
        Observed samples: {actual ? actual.length : "Unavailable"}; residual
        MAE:{" "}
        {Number.isFinite(absoluteError) ? value(absoluteError) : "Unavailable"}
        {unit ? ` ${unit}` : ""}.
      </p>
      {available && mean && lower && upper && times ? (
        <table aria-label="Latest scoring forecast">
          <caption>
            Forecast for {String(target?.series_id)} [{unit}]
          </caption>
          <thead>
            <tr>
              <th>Target time (UTC)</th>
              <th>Mean [{unit}]</th>
              <th>Lower [{unit}]</th>
              <th>Upper [{unit}]</th>
            </tr>
          </thead>
          <tbody>
            {mean.map((m, i) => (
              <tr key={i}>
                <td>{utcMicros(times[i])}</td>
                <td>{value(m)}</td>
                <td>{value(lower[i])}</td>
                <td>{value(upper[i])}</td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : (
        <p>
          The latest forecast preview is unavailable: it requires target
          identity, a declared unit, an absolute clock and aligned finite
          forecast values and bounds. Inspect the full cached JSON.
        </p>
      )}
      {context && (
        <section aria-label="Prediction context provenance">
          <h5>Context provenance</h5>
          <p>
            Policy{" "}
            {typeof context.policy === "string"
              ? context.policy
              : "Not reported"}
            ; schema{" "}
            {typeof context.schema_id === "string"
              ? context.schema_id
              : "Not reported"}
            ; topic{" "}
            {typeof context.topic === "string" ? context.topic : "Not reported"}
            , partition{" "}
            {typeof context.partition === "number"
              ? context.partition
              : "Not reported"}
            .{" "}
            {Array.isArray(context.source_positions)
              ? context.source_positions.length
              : "Unknown"}{" "}
            source records; latest availability{" "}
            {typeof context.available_at_max_micros === "number"
              ? utcMicros(context.available_at_max_micros)
              : "Not reported"}
            .
          </p>
          <p>
            Observed context range{" "}
            {typeof context.observed_start_micros === "number"
              ? utcMicros(context.observed_start_micros)
              : "Not reported"}{" "}
            to{" "}
            {typeof context.observed_end_micros === "number"
              ? utcMicros(context.observed_end_micros)
              : "Not reported"}
            ; forecast context range{" "}
            {typeof context.forecast_start_micros === "number"
              ? utcMicros(context.forecast_start_micros)
              : "Not reported"}{" "}
            to{" "}
            {typeof context.forecast_end_micros === "number"
              ? utcMicros(context.forecast_end_micros)
              : "Not reported"}
            . Exact record identities, offsets and integrity digests remain in
            the full cached JSON.
          </p>
        </section>
      )}
    </section>
  );
}
export default function ModelLifecyclePanel({
  run,
}: {
  run: ModellingResult | null;
}) {
  const [topics, setTopics] = useState<string[]>([]),
    [topicPrefix, setTopicPrefix] = useState("");
  const [models, setModels] = useState<RegisteredModel[]>([]),
    [deployments, setDeployments] = useState<ScoringDeployment[]>([]);
  const [catalogueRead, setCatalogueRead] = useState(false),
    [busy, setBusy] = useState("");
  const [error, setError] = useState(""),
    [notice, setNotice] = useState("");
  const [topic, setTopic] = useState(""),
    [series, setSeries] = useState<CatalogSeries[]>([]),
    [seriesId, setSeriesId] = useState("");
  const [modelId, setModelId] = useState(""),
    [deploymentId, setDeploymentId] = useState("");
  const [outputTopic, setOutputTopic] = useState(""),
    [name, setName] = useState("");
  const [note, setNote] = useState(""),
    [accepted, setAccepted] = useState(false),
    [threshold, setThreshold] = useState(0);
  const [steps, setSteps] = useState(4),
    [prediction, setPrediction] = useState<RegisteredPrediction | null>(null);
  const [results, setResults] = useState<SmartPrediction[] | null>(null);
  const [contextTopics, setContextTopics] = useState<string[]>([]),
    [contextTopic, setContextTopic] = useState("");
  const [contextSchemas, setContextSchemas] = useState<CatalogContext[]>([]),
    [contextChoice, setContextChoice] = useState("");
  const selectedContext = contextSchemas.find(
    (c) => `${c.partition}:${c.context_schema.schema_id}` === contextChoice,
  );
  const selectedModel = models.find((m) => m.id === modelId) ?? null;
  const selectedSeries = series.find((s) => s.series_id === seriesId) ?? null;
  const selectedDeployment =
    deployments.find((d) => d.id === deploymentId) ?? null;
  useEffect(() => {
    setContextChoice("");
    setContextSchemas([]);
  }, [modelId]);
  useEffect(() => {
    setAccepted(false);
    setNote("");
    setName("");
  }, [run?.run_id]);
  async function act(label: string, action: () => Promise<void>) {
    setBusy(label);
    setError("");
    setNotice("");
    try {
      await action();
    } catch (e) {
      setError(
        e instanceof Error
          ? e.message
          : "The local lifecycle operation failed.",
      );
    } finally {
      setBusy("");
    }
  }
  const catalogue = () =>
    act("Reading catalogue", async () => {
      const answer = await loadLifecycleCatalogue();
      setTopics(answer.topics);
      setTopicPrefix(answer.topicPrefix);
      setModels(answer.models);
      setDeployments(answer.deployments);
      setCatalogueRead(true);
      const nextTopic = answer.topics.includes(topic)
        ? topic
        : (answer.topics[0] ?? "");
      setTopic(nextTopic);
      if (nextTopic !== topic) {
        setSeries([]);
        setSeriesId("");
      }
      setModelId((old) =>
        answer.models.some((m) => m.id === old)
          ? old
          : (answer.models[0]?.id ?? ""),
      );
      setDeploymentId((old) =>
        answer.deployments.some((d) => d.id === old)
          ? old
          : (answer.deployments[0]?.id ?? ""),
      );
      setOutputTopic(
        (old) => old || `${answer.topicPrefix}smart.forecast.predictions.v1`,
      );
      setNotice("Local catalogue refreshed.");
    });
  const readSeries = () =>
    act("Reading topic series", async () => {
      const answer = await loadTopicSeries(topic);
      setSeries(answer);
      setSeriesId(
        answer.find((s) => s.series_id === selectedModel?.source.series_id)
          ?.series_id ??
          answer[0]?.series_id ??
          "",
      );
      setNotice(
        answer.length
          ? "Topic series loaded from the local broker."
          : "The topic contains no readable episode series.",
      );
    });
  const capture = () =>
    act("Loading training series", async () => {
      const bucket = await captureTrainingSeries(topic, seriesId);
      const state = useFlow.getState();
      state.loadBucket(bucket, {
        kind: "inventory",
        detail: `Kafka topic ${topic}; series ${seriesId}; bounded training snapshot`,
      });
      state.setSourceTopic(topic);
      state.setSourceSeries([seriesId]);
      setNotice(
        `Training series loaded from Kafka topic ${topic}. Fit and review it above.`,
      );
    });
  const register = () =>
    act("Registering reviewed fit", async () => {
      if (!run || !accepted)
        throw new Error("Review a successful fit before registration.");
      const registered = await registerReviewedFit(
        run.run_id,
        name,
        note,
        threshold,
      );
      setModels((old) => [
        ...old.filter((m) => m.id !== registered.id),
        registered,
      ]);
      setModelId(registered.id);
      setPrediction(null);
      setNotice(
        `Registered model ${registered.id}, version ${registered.version}.`,
      );
    });
  function updateDeployment(record: ScoringDeployment) {
    setDeployments((old) => [...old.filter((d) => d.id !== record.id), record]);
    setDeploymentId(record.id);
    setResults(null);
  }
  const blockers = selectedModel
    ? [...selectedModel.deployment_blockers]
    : ["Select a registered model."];
  if (selectedModel?.family === "sarimax") {
    if (!selectedModel.context_schema)
      blockers.push(
        "The registered context schema and units are unavailable. Register an evaluated fit with declared context units before live deployment.",
      );
    else if (
      !selectedContext ||
      !matchingContextSchema(
        selectedContext.context_schema,
        selectedModel.context_schema,
      )
    )
      blockers.push(
        "Select a context topic and partition matching the registered ordered context schema.",
      );
  }
  if (selectedModel && selectedModel.source.time_kind !== "absolute")
    blockers.push("Continuous scoring requires an absolute training clock.");
  if (!selectedSeries)
    blockers.push("Read the input topic and select its episode series.");
  if (selectedSeries && selectedModel) {
    if (selectedSeries.series_id !== selectedModel.source.series_id)
      blockers.push(
        "The input series must match the registered training series.",
      );
    if (
      selectedSeries.unit !== selectedModel.source.unit ||
      selectedSeries.interval_micros !== selectedModel.source.interval_micros
    )
      blockers.push(
        "Input unit and interval must match the registered training data.",
      );
    if (selectedSeries.partitions.length !== 1)
      blockers.push(
        "Continuous scoring requires the series in exactly one partition.",
      );
  }
  const badOutput = outputError(outputTopic, topicPrefix);
  if (badOutput) blockers.push(badOutput);
  const start = () =>
    act("Starting Smart Topic", async () => {
      if (!selectedModel || !selectedSeries || blockers.length)
        throw new Error("Resolve the deployment requirements before starting.");
      const record = await createScoringDeployment(
        selectedModel.id,
        topic,
        selectedSeries.partitions[0],
        outputTopic,
        selectedModel.family === "sarimax" && selectedContext
          ? { topic: contextTopic, partition: selectedContext.partition }
          : undefined,
      );
      updateDeployment(record);
      setNotice(`Deployment ${record.id} is ${record.status}.`);
    });
  const change = (action: "start" | "stop") =>
    act(`${action === "start" ? "Starting" : "Stopping"} scoring`, async () => {
      if (!selectedDeployment) throw new Error("Select a scoring deployment.");
      const record = await changeScoringState(selectedDeployment.id, action);
      updateDeployment(record);
      setNotice(`Deployment ${record.id} is ${record.status}.`);
    });
  const refreshDeployments = () =>
    act("Refreshing deployments", async () => {
      const answer = await loadDeployments();
      setDeployments(answer);
      setDeploymentId((old) =>
        answer.some((d) => d.id === old) ? old : (answer[0]?.id ?? ""),
      );
      setNotice("Actual deployment states refreshed from the service.");
    });
  return (
    <section
      className="wb-model-lifecycle"
      aria-label="Model registry and Smart Topics"
    >
      <h3>Train, review and run on Kafka</h3>
      <p>
        Load an episode series from the local CP cluster, fit and evaluate it
        above, then register an explicitly reviewed artifact. Smart Topics
        append new observations with fixed fitted parameters. Starting is a
        request; the actual processor state is shown below.
      </p>
      <button disabled={!!busy} onClick={() => void catalogue()}>
        Load local catalogue
      </button>
      <fieldset>
        <legend>Kafka training source</legend>
        <div className="wb-lifecycle-grid">
          <label>
            Input episode topic
            <select
              value={topic}
              onChange={(e) => {
                setTopic(e.target.value);
                setSeries([]);
                setSeriesId("");
              }}
            >
              <option value="">Select a topic</option>
              {topics.map((t) => (
                <option key={t}>{t}</option>
              ))}
            </select>
          </label>
          <button disabled={!!busy || !topic} onClick={() => void readSeries()}>
            Read topic series
          </button>
          <label>
            Kafka training series
            <select
              value={seriesId}
              onChange={(e) => setSeriesId(e.target.value)}
            >
              <option value="">Select a series</option>
              {series.map((s) => (
                <option key={s.series_id} value={s.series_id}>
                  {s.label} ({s.series_id})
                </option>
              ))}
            </select>
          </label>
          <button
            disabled={!!busy || !selectedSeries}
            onClick={() => void capture()}
          >
            Load training series
          </button>
        </div>
        {selectedSeries && (
          <p>
            {selectedSeries.count} samples in partition(s){" "}
            {selectedSeries.partitions.join(", ")}; unit{" "}
            {selectedSeries.unit ?? "not supplied"}; interval{" "}
            {selectedSeries.interval_micros} microseconds. Loading takes the
            latest 512 samples available for this series.
          </p>
        )}
      </fieldset>
      <fieldset>
        <legend>Review and register the successful fit</legend>
        {run ? (
          <p>
            Review run <code>{run.run_id}</code> using its held-out errors,
            baselines, intervals and warnings above. Registration is subject to
            the server's metric gate.
          </p>
        ) : (
          <p>Complete a successful fit and backtest to enable registration.</p>
        )}
        <div className="wb-lifecycle-grid">
          <label>
            Registered model name
            <input
              value={name}
              maxLength={64}
              onChange={(e) => setName(e.target.value)}
            />
          </label>
          <label>
            Minimum relative MAE improvement
            <input
              type="number"
              min={-10}
              max={1}
              step="0.01"
              value={Number.isNaN(threshold) ? "" : threshold}
              onChange={(e) =>
                setThreshold(
                  e.target.value === "" ? NaN : Number(e.target.value),
                )
              }
            />
          </label>
        </div>
        <p>
          Use 1 to 64 lowercase letters, digits, underscores or hyphens for the
          registered name, starting with a letter or digit.
        </p>
        <p>
          The threshold is a fraction: 0.1 requires 10% lower MAE than naive; 0
          accepts equal MAE. A negative threshold explicitly permits a weaker
          candidate.
        </p>
        <label>
          Review note
          <textarea
            value={note}
            maxLength={2000}
            onChange={(e) => setNote(e.target.value)}
          />
        </label>
        <label className="wb-lifecycle-check">
          <input
            type="checkbox"
            checked={accepted}
            disabled={!run}
            onChange={(e) => setAccepted(e.target.checked)}
          />
          I reviewed this fit and its backtests
        </label>
        <button
          disabled={
            !!busy ||
            !run ||
            !accepted ||
            !/^[a-z0-9][a-z0-9_-]{0,63}$/.test(name) ||
            !note.trim() ||
            !Number.isFinite(threshold) ||
            threshold < -10 ||
            threshold > 1
          }
          onClick={() => void register()}
        >
          Register reviewed fit
        </button>
      </fieldset>
      <fieldset>
        <legend>Registered artifacts</legend>
        <label>
          Registered model
          <select
            value={modelId}
            onChange={(e) => {
              setModelId(e.target.value);
              setPrediction(null);
            }}
          >
            <option value="">Select a registered model</option>
            {models.map((m) => (
              <option key={m.id} value={m.id}>
                {m.name}, v{m.version} ({m.id})
              </option>
            ))}
          </select>
        </label>
        {catalogueRead && models.length === 0 && <p>No registered models.</p>}
        {selectedModel && (
          <>
            <dl>
              <dt>Model</dt>
              <dd>
                {selectedModel.id}, version {selectedModel.version};{" "}
                {selectedModel.family.toUpperCase()}
              </dd>
              <dt>Artifact</dt>
              <dd>{selectedModel.artifact_id}</dd>
              <dt>Review</dt>
              <dd>
                {selectedModel.review.note}; minimum relative improvement{" "}
                {value(selectedModel.review.minimum_improvement)}
              </dd>
              <dt>Held-out MAE</dt>
              <dd>
                Candidate {value(selectedModel.metrics.candidate_mae)}; naive{" "}
                {value(selectedModel.metrics.baseline_mae)}; relative
                improvement {value(selectedModel.metrics.improvement)}
              </dd>
              <dt>MLflow run</dt>
              <dd>{selectedModel.mlflow_run_id ?? "Not supplied"}</dd>
              <dt>MLflow version</dt>
              <dd>{selectedModel.mlflow_version ?? "Not supplied"}</dd>
            </dl>
            <div className="wb-lifecycle-grid">
              <label>
                Registered prediction steps
                <input
                  type="number"
                  min={1}
                  max={96}
                  value={Number.isNaN(steps) ? "" : steps}
                  onChange={(e) => {
                    setPrediction(null);
                    setSteps(
                      e.target.value === "" ? NaN : Number(e.target.value),
                    );
                  }}
                />
              </label>
              <button
                disabled={!!busy || selectedModel.family === "sarimax"}
                onClick={() =>
                  void act("Predicting from artifact", async () => {
                    const answer = await predictRegisteredModel(
                      selectedModel,
                      steps,
                    );
                    setPrediction(answer);
                    setNotice(
                      `Prediction reused artifact ${answer.artifact_id}.`,
                    );
                  })
                }
              >
                Predict with registered artifact
              </button>
            </div>
            {selectedModel.family === "sarimax" && (
              <p>
                Registered SARIMAX prediction requires future external context.
                Use the numerical API with aligned future_exog and
                future_available_at_micros matrices.
              </p>
            )}
          </>
        )}
        {prediction && (
          <>
            <p>
              Prediction coordinates: {selectedModel?.source.time_kind}; target
              unit {selectedModel?.source.unit ?? "not supplied"}. This
              prediction uses the artifact's registered training checkpoint.
            </p>
            <table aria-label="Registered artifact predictions">
              <thead>
                <tr>
                  <th>Step</th>
                  <th>Target coordinate (microseconds)</th>
                  <th>Mean</th>
                  <th>Lower</th>
                  <th>Upper</th>
                </tr>
              </thead>
              <tbody>
                {prediction.forecast.mean.map((mean, i) => (
                  <tr key={i}>
                    <td>{i + 1}</td>
                    <td>{prediction.forecast.timestamps_micros[i]}</td>
                    <td>{value(mean)}</td>
                    <td>{value(prediction.forecast.lower[i])}</td>
                    <td>{value(prediction.forecast.upper[i])}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </>
        )}
      </fieldset>
      <fieldset>
        <legend>Continuous Smart Topic</legend>
        {selectedModel?.family === "sarimax" && (
          <fieldset>
            <legend>Registered live context binding</legend>
            {selectedModel.context_schema ? (
              <p>
                Registered schema {selectedModel.context_schema.schema_id};
                policy {selectedModel.context_schema.policy}; ordered columns{" "}
                {selectedModel.context_schema.names
                  .map(
                    (name, i) =>
                      `${name} [${selectedModel.context_schema!.units[i]}]`,
                  )
                  .join(", ")}
                . Source bindings must match this schema.
              </p>
            ) : (
              <p>
                Registered context metadata is unknown. No units or availability
                policy are inferred.
              </p>
            )}
            <div className="wb-lifecycle-grid">
              <button
                disabled={!!busy}
                onClick={() =>
                  void act("Reading scoring context topics", async () => {
                    const topics = await loadContextTopics();
                    setContextTopics(topics);
                    if (!topics.includes(contextTopic)) {
                      setContextTopic(topics[0] ?? "");
                      setContextSchemas([]);
                      setContextChoice("");
                    }
                  })
                }
              >
                Load scoring context topics
              </button>
              <label>
                Scoring context topic
                <select
                  value={contextTopic}
                  onChange={(e) => {
                    setContextTopic(e.target.value);
                    setContextSchemas([]);
                    setContextChoice("");
                  }}
                >
                  <option value="">Select a context topic</option>
                  {contextTopics.map((topic) => (
                    <option key={topic}>{topic}</option>
                  ))}
                </select>
              </label>
              <button
                disabled={!!busy || !contextTopic}
                onClick={() =>
                  void act("Reading scoring context schemas", async () => {
                    const contexts = await loadContextSchemas(contextTopic);
                    setContextSchemas(contexts);
                    const match =
                      contexts.find(
                        (c) =>
                          selectedModel.context_schema &&
                          matchingContextSchema(
                            c.context_schema,
                            selectedModel.context_schema,
                          ),
                      ) ?? contexts[0];
                    setContextChoice(
                      match
                        ? `${match.partition}:${match.context_schema.schema_id}`
                        : "",
                    );
                  })
                }
              >
                Read scoring context schemas
              </button>
              <label>
                Scoring context schema and partition
                <select
                  value={contextChoice}
                  onChange={(e) => setContextChoice(e.target.value)}
                >
                  <option value="">Select a context partition</option>
                  {contextSchemas.map((c) => (
                    <option
                      key={`${c.partition}:${c.context_schema.schema_id}`}
                      value={`${c.partition}:${c.context_schema.schema_id}`}
                    >
                      Partition {c.partition}: {c.context_schema.schema_id}
                    </option>
                  ))}
                </select>
              </label>
            </div>
            {selectedContext && (
              <p>
                Selected policy {selectedContext.context_schema.policy}; schema{" "}
                {selectedContext.context_schema.schema_id};{" "}
                {selectedContext.row_count} context rows. Missing or unavailable
                context makes the processor wait; it does not produce a
                context-free fallback.
              </p>
            )}
          </fieldset>
        )}
        <label>
          Prediction output topic
          <input
            value={outputTopic}
            onChange={(e) => setOutputTopic(e.target.value)}
          />
        </label>
        <p>
          The processor verifies the training timestamps and values in the
          selected input partition. It replays after the registered training
          window, skips the training prefix and publishes forecasts for new
          observations. It does not refit parameters.
        </p>
        {blockers.length > 0 && (
          <ul aria-label="Scoring deployment requirements">
            {[...new Set(blockers)].map((b) => (
              <li key={b}>{b}</li>
            ))}
          </ul>
        )}
        <button
          disabled={!!busy || blockers.length > 0}
          onClick={() => void start()}
        >
          Start Smart Topic
        </button>{" "}
        <button disabled={!!busy} onClick={() => void refreshDeployments()}>
          Refresh deployments
        </button>
        <label>
          Scoring deployment
          <select
            value={deploymentId}
            onChange={(e) => {
              setDeploymentId(e.target.value);
              setResults(null);
            }}
          >
            <option value="">Select a deployment</option>
            {deployments.map((d) => (
              <option key={d.id} value={d.id}>
                {d.id}: {d.status}
              </option>
            ))}
          </select>
        </label>
        {selectedDeployment && (
          <section aria-label="Scoring deployment status">
            <dl>
              <dt>Deployment</dt>
              <dd>{selectedDeployment.id}</dd>
              <dt>Registered model</dt>
              <dd>{selectedDeployment.model_id}</dd>
              <dt>Desired state</dt>
              <dd>{selectedDeployment.desired_state}</dd>
              <dt>Actual state</dt>
              <dd>{selectedDeployment.status}</dd>
              <dt>Input</dt>
              <dd>
                {selectedDeployment.input_topic}, partition{" "}
                {selectedDeployment.input_partition};{" "}
                {selectedDeployment.series_id}
              </dd>
              <dt>Output</dt>
              <dd>{selectedDeployment.output_topic}</dd>
              <dt>Progress</dt>
              <dd>{checkpointText(selectedDeployment)}</dd>
            </dl>
            {selectedDeployment.context_status && (
              <section aria-label="Waiting for live context">
                <p>{selectedDeployment.context_status.reason}</p>
                <dl>
                  <dt>Missing valid-time range (UTC)</dt>
                  <dd>
                    {selectedDeployment.context_status.missing_start_micros ===
                    null
                      ? "Not reported"
                      : utcMicros(
                          selectedDeployment.context_status
                            .missing_start_micros,
                        )}{" "}
                    to{" "}
                    {selectedDeployment.context_status.missing_end_micros ===
                    null
                      ? "Not reported"
                      : utcMicros(
                          selectedDeployment.context_status.missing_end_micros,
                        )}
                  </dd>
                  <dt>Origin availability cutoff (UTC)</dt>
                  <dd>
                    {selectedDeployment.context_status.cutoff_micros === null
                      ? "Not reported"
                      : utcMicros(
                          selectedDeployment.context_status.cutoff_micros,
                        )}
                  </dd>
                  <dt>Context source</dt>
                  <dd>
                    {selectedDeployment.context_status.topic}, partition{" "}
                    {selectedDeployment.context_status.partition}
                  </dd>
                  <dt>Context progress</dt>
                  <dd>
                    Context next offset{" "}
                    {selectedDeployment.context_status.next_offset ??
                      "Not reported"}
                    ; watermark{" "}
                    {selectedDeployment.context_status.watermark_micros === null
                      ? "Not reported"
                      : utcMicros(
                          selectedDeployment.context_status.watermark_micros,
                        )}
                    ; buffered rows{" "}
                    {selectedDeployment.context_status.buffered_rows}.
                  </dd>
                </dl>
              </section>
            )}
            {dataObject(selectedDeployment.checkpoint?.context) && (
              <p>
                Context checkpoint:{" "}
                {JSON.stringify(selectedDeployment.checkpoint!.context)}
              </p>
            )}
            {selectedDeployment.error && (
              <p role="alert">{selectedDeployment.error}</p>
            )}
            <button
              disabled={
                !!busy || selectedDeployment.desired_state !== "running"
              }
              onClick={() => void change("stop")}
            >
              Stop scoring
            </button>{" "}
            <button
              disabled={
                !!busy || selectedDeployment.desired_state !== "stopped"
              }
              onClick={() => void change("start")}
            >
              Start scoring
            </button>{" "}
            <button
              disabled={!!busy}
              onClick={() =>
                void act("Reading scoring results", async () => {
                  setResults(await loadScoringResults(selectedDeployment.id));
                  setNotice(
                    "Scoring results read from the acknowledged local cache.",
                  );
                })
              }
            >
              Inspect scoring results
            </button>
          </section>
        )}
        {results &&
          (results.length ? (
            <section aria-label="Acknowledged scoring results">
              <p>
                This is the local cache of broker-acknowledged outputs, with
                their actual broker positions. It retains at most 100 records
                and returns at most 6 MiB per response. Refreshing reads this
                cache rather than issuing a fresh Kafka query.
              </p>
              <LatestPrediction record={results[0]} />
              <table aria-label="Smart Topic predictions">
                <thead>
                  <tr>
                    <th>Prediction key</th>
                    <th>Broker partition</th>
                    <th>Broker offset</th>
                  </tr>
                </thead>
                <tbody>
                  {results.map((r) => (
                    <tr key={`${r.broker_partition}:${r.broker_offset}`}>
                      <td>{r.key}</td>
                      <td>{r.broker_partition}</td>
                      <td>{r.broker_offset}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
              <details>
                <summary>
                  Full cached prediction JSON ({results.length} records)
                </summary>
                <pre>{JSON.stringify(results, null, 2)}</pre>
              </details>
            </section>
          ) : (
            <p>No prediction records have been read for this deployment.</p>
          ))}
      </fieldset>
      {busy && <p role="status">{busy}.</p>}
      {notice && <p role="status">{notice}</p>}
      {error && <p role="alert">{error}</p>}
    </section>
  );
}
