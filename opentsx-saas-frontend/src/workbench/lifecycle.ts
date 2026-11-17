import { parseEpisodes } from "./loaders";
import type { ForecastResult } from "./modelling";

const BASE = "/analysis/v1/modelling";
export interface CatalogSeries {
  series_id: string;
  label: string;
  unit: string | null;
  interval_micros: number;
  partitions: number[];
  count: number;
  first_micros: number;
  last_micros: number;
}
export interface RegisteredModel {
  id: string;
  name: string;
  version: string | number;
  run_id: string;
  artifact_id: string;
  family: "arima" | "sarima" | "sarimax";
  source: {
    series_id: string;
    unit: string | null;
    interval_micros: number;
    time_kind: string;
  };
  review: { accepted: true; note: string; minimum_improvement: number };
  metrics: {
    candidate_mae: number;
    baseline_mae: number;
    improvement: number | null;
  };
  mlflow_run_id: string | null;
  mlflow_version: string | number | null;
  status: "registered";
  deployment_blockers: string[];
}
export interface ScoringDeployment {
  id: string;
  model_id: string;
  series_id: string;
  input_topic: string;
  input_partition: number;
  output_topic: string;
  desired_state: "running" | "stopped";
  status: "starting" | "running" | "stopping" | "stopped" | "failed";
  error: string | null;
  checkpoint: Record<string, unknown> | null;
}
export interface SmartPrediction {
  key: string;
  payload: Record<string, unknown>;
  broker_partition: number;
  broker_offset: number;
}
export interface RegisteredPrediction {
  schema: "opentsx.registered-prediction.v1";
  model_id: string;
  artifact_id: string;
  forecast: ForecastResult & {
    timestamps_micros: number[];
    interval_status: "conditional_gaussian";
  };
}
function object(value: unknown, name: string): Record<string, unknown> {
  if (!value || typeof value !== "object" || Array.isArray(value))
    throw new Error(`Invalid ${name} response.`);
  return value as Record<string, unknown>;
}
function text(value: unknown, name: string): asserts value is string {
  if (typeof value !== "string" || !value || value.length > 65536)
    throw new Error(`Invalid ${name} response.`);
}
function number(
  value: unknown,
  name: string,
  integer = false,
  min = -Infinity,
): asserts value is number {
  if (
    typeof value !== "number" ||
    !Number.isFinite(value) ||
    value < min ||
    (integer && !Number.isSafeInteger(value))
  )
    throw new Error(`Invalid ${name} response.`);
}
function strings(value: unknown, name: string): asserts value is string[] {
  if (
    !Array.isArray(value) ||
    value.length > 1000 ||
    value.some((v) => typeof v !== "string")
  )
    throw new Error(`Invalid ${name} response.`);
}
function list(value: unknown, name: string): unknown[] {
  if (!Array.isArray(value) || value.length > 1000)
    throw new Error(`Invalid ${name} response.`);
  return value;
}
async function request(path: string, body?: unknown): Promise<unknown> {
  let response: Response;
  try {
    response = await fetch(
      path,
      body === undefined
        ? undefined
        : {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify(body),
          },
    );
  } catch {
    throw new Error("The local model service is unavailable.");
  }
  let raw: string;
  try {
    raw = await response.text();
  } catch {
    throw new Error("The local model service response could not be read.");
  }
  if (raw.length > 8 * 1024 * 1024)
    throw new Error("The local model response exceeds 8 MiB.");
  let value: unknown;
  try {
    value = JSON.parse(raw);
  } catch {
    throw new Error(
      `The local model service returned invalid JSON (HTTP ${response.status}).`,
    );
  }
  if (!response.ok) {
    const failure = object(value, "model service error");
    throw new Error(
      typeof failure.error === "string"
        ? failure.error
        : `Local model service HTTP ${response.status}.`,
    );
  }
  return value;
}
function model(value: unknown): RegisteredModel {
  const m = object(value, "registered model");
  for (const key of ["id", "name", "run_id", "artifact_id"]) text(m[key], key);
  for (const key of ["version", "mlflow_version"]) {
    if (key === "mlflow_version" && m[key] === null) continue;
    if (typeof m[key] === "number") number(m[key], key, true, 1);
    else text(m[key], key);
  }
  if (m.mlflow_run_id !== null) text(m.mlflow_run_id, "MLflow run");
  if (
    !["arima", "sarima", "sarimax"].includes(String(m.family)) ||
    m.status !== "registered"
  )
    throw new Error("Unsupported registered model state.");
  const source = object(m.source, "registered source");
  text(source.series_id, "registered series");
  if (source.unit !== null) text(source.unit, "registered unit");
  number(source.interval_micros, "registered interval", true, 1);
  if (!["absolute", "relative", "index"].includes(String(source.time_kind)))
    throw new Error("Invalid registered time coordinate.");
  const review = object(m.review, "review");
  if (review.accepted !== true)
    throw new Error("The registered model has no accepted review.");
  text(review.note, "review note");
  number(review.minimum_improvement, "review threshold");
  const metrics = object(m.metrics, "registered metrics");
  number(metrics.candidate_mae, "candidate MAE", false, 0);
  number(metrics.baseline_mae, "baseline MAE", false, 0);
  if (metrics.improvement !== null)
    number(metrics.improvement, "relative improvement");
  strings(m.deployment_blockers, "deployment blockers");
  return m as unknown as RegisteredModel;
}
function deployment(value: unknown): ScoringDeployment {
  const d = object(value, "scoring deployment");
  for (const key of [
    "id",
    "model_id",
    "series_id",
    "input_topic",
    "output_topic",
  ])
    text(d[key], key);
  number(d.input_partition, "input partition", true, 0);
  if (
    !["running", "stopped"].includes(String(d.desired_state)) ||
    !["starting", "running", "stopping", "stopped", "failed"].includes(
      String(d.status),
    )
  )
    throw new Error("Unsupported scoring deployment state.");
  if (d.error !== null) text(d.error, "deployment error");
  if (d.checkpoint !== null) object(d.checkpoint, "deployment checkpoint");
  return d as unknown as ScoringDeployment;
}
export async function loadLifecycleCatalogue() {
  const [topicsValue, modelsValue, deployments] = await Promise.all([
    request("/analysis/v1/topics"),
    request(`${BASE}/models`),
    loadDeployments(),
  ]);
  const topics = object(topicsValue, "topic catalogue"),
    registry = object(modelsValue, "model registry");
  strings(topics.topics, "topics");
  text(topics.output_topic, "analysis output topic");
  if (registry.schema !== "opentsx.model-registry.v1")
    throw new Error("Unsupported model registry schema.");
  text(registry.topic_prefix, "topic prefix");
  return {
    topics: topics.topics,
    topicPrefix: registry.topic_prefix,
    models: list(registry.models, "model registry").map(model),
    deployments,
  };
}
export async function loadTopicSeries(topic: string): Promise<CatalogSeries[]> {
  const d = object(
    await request(`${BASE}/series?topic=${encodeURIComponent(topic)}`),
    "topic series",
  );
  if (d.schema !== "opentsx.modelling-series.v1" || d.topic !== topic)
    throw new Error("The series catalogue does not match the requested topic.");
  return list(d.series, "topic series").map((value) => {
    const s = object(value, "topic series");
    text(s.series_id, "series id");
    text(s.label, "series label");
    if (s.unit !== null) text(s.unit, "series unit");
    for (const key of ["interval_micros", "count"])
      number(s[key], key, true, 1);
    for (const key of ["first_micros", "last_micros"])
      number(s[key], key, true);
    for (const partition of list(s.partitions, "series partitions"))
      number(partition, "series partition", true, 0);
    return s as unknown as CatalogSeries;
  });
}
export async function captureTrainingSeries(topic: string, seriesId: string) {
  const d = object(
    await request(`${BASE}/capture`, {
      topic,
      series_id: seriesId,
      samples: 512,
    }),
    "training capture",
  );
  if (d.schema !== "opentsx.modelling-capture.v1" || d.topic !== topic)
    throw new Error("The training capture does not match the requested topic.");
  const bucket = parseEpisodes(d.episodes, `Kafka ${topic}`);
  if (
    bucket.series.length !== 1 ||
    bucket.series[0].id !== seriesId ||
    bucket.series[0].y.length < 9 ||
    bucket.series[0].y.length > 2048
  )
    throw new Error(
      "The captured training series does not match the requested identity or sample limits.",
    );
  return bucket;
}
export async function registerReviewedFit(
  runId: string,
  name: string,
  note: string,
  minimumImprovement: number,
) {
  if (
    !/^[a-z0-9][a-z0-9_-]{0,63}$/.test(name) ||
    !note.trim() ||
    note.length > 2000 ||
    !Number.isFinite(minimumImprovement) ||
    minimumImprovement < -10 ||
    minimumImprovement > 1
  )
    throw new Error(
      "Supply a lowercase registered name of 1 to 64 letters, digits, underscores or hyphens, starting with a letter or digit; a review note of 1 to 2000 characters; and a minimum improvement from -10 to 1.",
    );
  const result = model(
    await request(`${BASE}/models`, {
      run_id: runId,
      name: name.trim(),
      review: {
        accepted: true,
        note: note.trim(),
        minimum_improvement: minimumImprovement,
      },
    }),
  );
  if (result.run_id !== runId)
    throw new Error("The registered model does not match the reviewed run.");
  return result;
}
export async function loadDeployments() {
  const d = object(await request(`${BASE}/deployments`), "scoring deployments");
  if (d.schema !== "opentsx.scoring-deployments.v1")
    throw new Error("Unsupported scoring deployment schema.");
  return list(d.deployments, "scoring deployments").map(deployment);
}
export async function createScoringDeployment(
  modelId: string,
  topic: string,
  partition: number,
  outputTopic: string,
) {
  const d = deployment(
    await request(`${BASE}/deployments`, {
      model_id: modelId,
      input_topic: topic,
      input_partition: partition,
      output_topic: outputTopic,
    }),
  );
  if (
    d.model_id !== modelId ||
    d.input_topic !== topic ||
    d.input_partition !== partition ||
    d.output_topic !== outputTopic
  )
    throw new Error("The deployment does not match the requested binding.");
  return d;
}
export async function changeScoringState(id: string, action: "start" | "stop") {
  const d = deployment(
    await request(
      `${BASE}/deployments/${encodeURIComponent(id)}/${action}`,
      {},
    ),
  );
  if (d.id !== id)
    throw new Error("The deployment action returned another deployment.");
  return d;
}
export async function loadScoringResults(
  id: string,
): Promise<SmartPrediction[]> {
  const d = object(
    await request(`${BASE}/deployments/${encodeURIComponent(id)}/results`),
    "scoring results",
  );
  if (d.schema !== "opentsx.smart-predictions.v1" || d.deployment_id !== id)
    throw new Error(
      "The scoring results do not match the selected deployment.",
    );
  return list(d.results, "scoring results").map((value) => {
    const row = object(value, "scoring result");
    text(row.key, "prediction key");
    object(row.payload, "prediction payload");
    number(row.broker_partition, "prediction partition", true, 0);
    number(row.broker_offset, "prediction offset", true, 0);
    return row as unknown as SmartPrediction;
  });
}
export async function predictRegisteredModel(
  selected: RegisteredModel,
  steps: number,
): Promise<RegisteredPrediction> {
  if (!Number.isSafeInteger(steps) || steps < 1 || steps > 96)
    throw new Error("Registered prediction steps must be from 1 to 96.");
  const d = object(
    await request(`${BASE}/models/${encodeURIComponent(selected.id)}/predict`, {
      steps,
    }),
    "registered prediction",
  );
  if (
    d.schema !== "opentsx.registered-prediction.v1" ||
    d.model_id !== selected.id ||
    d.artifact_id !== selected.artifact_id
  )
    throw new Error("The prediction does not match the selected artifact.");
  const forecast = object(d.forecast, "registered forecast values");
  for (const key of ["mean", "lower", "upper"]) {
    const values = list(forecast[key], "registered forecast values");
    if (values.length !== steps)
      throw new Error(
        "Registered forecast lengths do not match the requested horizon.",
      );
    values.forEach((v) => number(v, "registered forecast value"));
  }
  const times = list(
    forecast.timestamps_micros,
    "registered forecast coordinates",
  );
  if (
    times.length !== steps ||
    forecast.interval_status !== "conditional_gaussian"
  )
    throw new Error(
      "Invalid registered forecast coordinates or interval status.",
    );
  times.forEach((t, i) => {
    number(t, "registered forecast coordinate", true);
    if (i && t - Number(times[i - 1]) !== selected.source.interval_micros)
      throw new Error(
        "Registered forecast coordinates do not match the training interval.",
      );
  });
  for (let i = 0; i < steps; i++)
    if (
      (forecast.lower as number[])[i] > (forecast.mean as number[])[i] ||
      (forecast.mean as number[])[i] > (forecast.upper as number[])[i]
    )
      throw new Error(
        "Registered forecast intervals do not contain the point forecast.",
      );
  return d as unknown as RegisteredPrediction;
}
