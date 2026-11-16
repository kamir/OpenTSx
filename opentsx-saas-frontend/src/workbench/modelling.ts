import type { Bucket, Series } from "./types";

export type ForecastFamily = "arima" | "sarima" | "sarimax";
export type Baseline = "mean" | "naive" | "seasonal_naive" | "drift";
export interface ModellingOptions {
  family: ForecastFamily;
  start: number;
  stop: number;
  steps: number;
  order: [number, number, number];
  seasonalOrder: [number, number, number, number];
  trend: string;
  alpha: number;
  maxiter: number;
  initial: number;
  horizon: number;
  step: number;
  window: number;
  seasonalPeriod: number;
  baselines: Baseline[];
  contextText: string;
}

export interface PreparedTarget {
  seriesId: string;
  label: string;
  unit: string | null;
  values: number[];
  x: number[];
  xKind: "time" | "index";
  interval: number;
  t0Ms: number | null;
  episodeIds: string[];
  quality: number[] | null;
  qualityKnown: number[] | null;
}

function integer(value: number, name: string, min: number, max: number): void {
  if (!Number.isSafeInteger(value) || value < min || value > max)
    throw new Error(`${name} must be an integer from ${min} to ${max}.`);
}

export function prepareTarget(
  bucket: Bucket,
  series: Series,
  start: number,
  stop: number,
): PreparedTarget {
  if (bucket.xKind !== "time" && bucket.xKind !== "index")
    throw new Error("Modelling needs a time or sample-index axis.");
  integer(start, "Training range start", 0, series.y.length);
  integer(stop, "Training range end", start + 8, series.y.length);
  if (stop - start > 2048)
    throw new Error("Select at most 2048 samples for a finite modelling run.");
  const x = Array.from(series.x.slice(start, stop));
  const values = Array.from(series.y.slice(start, stop));
  if (
    series.x.length !== series.y.length ||
    values.some((v) => !Number.isFinite(v)) ||
    x.some((v) => !Number.isFinite(v))
  )
    throw new Error(
      "Prepare missing or non-finite samples explicitly before fitting.",
    );
  const interval = x[1] - x[0];
  if (
    !(interval > 0) ||
    x.some(
      (v, i) =>
        i > 0 &&
        Math.abs(v - x[i - 1] - interval) > Math.max(1e-9, interval * 1e-8),
    )
  )
    throw new Error(
      "Modelling requires a regular increasing clock. Resample explicitly first.",
    );
  if (
    series.quality &&
    (series.quality.length !== series.y.length ||
      Array.from(series.quality.slice(start, stop)).some((v) => v !== 0))
  )
    throw new Error(
      "Prepare flagged quality values explicitly before fitting.",
    );
  const last = x[x.length - 1] + interval;
  const episodeIds = [
    ...new Set(
      (bucket.episodes ?? [])
        .filter(
          (e) => e.seriesId === series.id && e.start < last && e.end > x[0],
        )
        .map((e) => e.episodeId),
    ),
  ];
  return {
    seriesId: series.id,
    label: series.label,
    unit: series.unit ?? null,
    values,
    x,
    xKind: bucket.xKind,
    interval,
    t0Ms: bucket.t0Ms ?? null,
    episodeIds,
    quality: series.quality
      ? Array.from(series.quality.slice(start, stop))
      : null,
    qualityKnown: series.qualityKnown
      ? Array.from(series.qualityKnown.slice(start, stop))
      : null,
  };
}

export function validateOptions(options: ModellingOptions, n: number): void {
  integer(options.steps, "Forecast horizon", 1, 96);
  integer(options.initial, "Initial training samples", 8, n - 1);
  integer(options.horizon, "Backtest horizon", 1, n - options.initial);
  integer(options.step, "Backtest step", 1, n);
  integer(options.maxiter, "Optimizer iterations", 1, 200);
  integer(
    options.seasonalPeriod,
    "Baseline seasonal period",
    1,
    Math.max(1, options.initial - 1),
  );
  if (options.window !== 0)
    integer(options.window, "Rolling training window", 8, options.initial);
  if (
    !Number.isFinite(options.alpha) ||
    !(options.alpha > 0 && options.alpha < 1)
  )
    throw new Error("Alpha must be between zero and one.");
  options.order.forEach((v, i) =>
    integer(v, "ARIMA order", 0, i === 1 ? 2 : 3),
  );
  options.seasonalOrder
    .slice(0, 3)
    .forEach((v, i) => integer(v, "Seasonal order", 0, i === 1 ? 1 : 2));
  integer(options.seasonalOrder[3], "Season length", 0, 96);
  if (options.family === "sarima" && options.seasonalOrder[3] < 2)
    throw new Error(
      "A seasonal family requires a season length of at least two.",
    );
  if (!options.baselines.includes("naive"))
    throw new Error("The naive baseline is required for comparison.");
  if (
    options.baselines.includes("seasonal_naive") &&
    options.seasonalPeriod < 2
  )
    throw new Error("Seasonal naive needs a period of at least two.");
  if (
    Math.floor((n - options.initial - options.horizon) / options.step) + 1 >
    12
  )
    throw new Error(
      "Choose a larger backtest step: at most 12 origins are accepted.",
    );
}

export function readContext(text: string, required: boolean): unknown {
  if (!text.trim()) {
    if (required)
      throw new Error(
        "SARIMAX requires historical and future context with forecast-origin availability.",
      );
    return null;
  }
  if (text.length > 1048576) throw new Error("Context JSON exceeds 1 MiB.");
  let context: unknown;
  try {
    context = JSON.parse(text);
  } catch {
    throw new Error("Context must be valid JSON.");
  }
  if (!context || typeof context !== "object" || Array.isArray(context))
    throw new Error("Context must be a JSON object.");
  return context;
}

export interface ForecastResult {
  mean: number[];
  lower: number[];
  upper: number[];
  timestamps_micros?: number[];
  parameters?: Record<string, number>;
  aic?: number;
  bic?: number;
  log_likelihood?: number;
}
export interface ForecastRecord {
  method: string;
  result: ForecastResult;
  warnings?: string[];
}
export interface ForecastMetrics {
  mae: number;
  rmse: number;
  mase: number | null;
  rmsse: number | null;
  coverage: number;
  mean_interval_width: number;
  mean_interval_score: number;
}
export interface BacktestCase {
  origin: number;
  target_index: number;
  horizon: number;
  actual: number;
  predicted: number;
  lower: number;
  upper: number;
}
export interface ModellingResult {
  schema: "opentsx.modelling-result.v1";
  run_id: string;
  source: {
    time_kind: "absolute" | "relative" | "index";
    series_id: string;
    label: string;
    unit: string | null;
    episode_ids: string[];
    nobs: number;
    interval_micros: number;
    t_start_micros: number;
    t_end_micros: number;
    values_sha256: string;
  };
  configuration: Record<string, unknown>;
  forecast: ForecastRecord;
  baselines: Record<string, ForecastRecord>;
  backtest: {
    result: {
      folds: {
        training_start: number;
        training_stop: number;
        test_start: number;
        test_stop: number;
      }[];
      cases: Record<string, BacktestCase[]>;
      metrics: Record<string, ForecastMetrics>;
      by_horizon: Record<string, Record<string, ForecastMetrics>>;
    };
  };
  warnings: string[];
  runtime: {
    opentsx: string;
    statsmodels: string;
    numpy: string;
    scipy: string;
  };
}

export function modellingRequest(
  bucket: Bucket,
  series: Series,
  options: ModellingOptions,
) {
  const target = prepareTarget(bucket, series, options.start, options.stop);
  validateOptions(options, target.values.length);
  const context =
    options.family === "sarimax"
      ? readContext(options.contextText, true)
      : null;
  const scale = 1e6;
  const timeKind =
    bucket.xKind === "index"
      ? "index"
      : bucket.t0Ms === undefined
        ? "relative"
        : "absolute";
  const timestamps = target.x.map((x) =>
    Math.round((timeKind === "absolute" ? bucket.t0Ms! * 1000 : 0) + x * scale),
  );
  if (timestamps.some((t) => !Number.isSafeInteger(t)))
    throw new Error("The target clock exceeds safe integer microseconds.");
  return {
    schema: "opentsx.modelling-request.v1",
    target: {
      time_kind: timeKind,
      series_id: target.seriesId,
      label: target.label,
      unit: target.unit,
      values: target.values,
      timestamps_micros: timestamps,
      episode_ids: target.episodeIds,
      ...(target.quality ? { quality: target.quality } : {}),
      ...(target.qualityKnown ? { quality_known: target.qualityKnown } : {}),
    },
    configuration: {
      family: options.family,
      order: options.order,
      seasonal_order:
        options.family === "arima" ? [0, 0, 0, 0] : options.seasonalOrder,
      trend: options.trend,
      steps: options.steps,
      alpha: options.alpha,
      maxiter: options.maxiter,
    },
    backtest: {
      initial: options.initial,
      horizon: options.horizon,
      step: options.step,
      window: options.window || null,
      seasonal_period: options.seasonalPeriod,
      methods: options.baselines,
    },
    context,
  };
}

function plain(value: unknown, name: string): Record<string, unknown> {
  if (!value || typeof value !== "object" || Array.isArray(value))
    throw new Error(`Invalid modelling response ${name}.`);
  return value as Record<string, unknown>;
}
function finite(value: unknown, name: string): void {
  if (typeof value !== "number" || !Number.isFinite(value))
    throw new Error(`Invalid modelling response ${name}.`);
}
function string(value: unknown, name: string): void {
  if (typeof value !== "string" || value.length > 65536)
    throw new Error(`Invalid modelling response ${name}.`);
}
function metrics(value: unknown): void {
  const m = plain(value, "metrics");
  for (const key of [
    "mae",
    "rmse",
    "coverage",
    "mean_interval_width",
    "mean_interval_score",
  ])
    finite(m[key], key);
  for (const key of ["mase", "rmsse"]) if (m[key] !== null) finite(m[key], key);
}
export function inspectModellingResult(
  value: unknown,
  expectedLength: number,
): ModellingResult {
  const d = plain(value, "record");
  if (d.schema !== "opentsx.modelling-result.v1")
    throw new Error("Unsupported modelling response schema.");
  string(d.run_id, "run id");
  const source = plain(d.source, "source");
  for (const k of ["series_id", "label", "values_sha256"]) string(source[k], k);
  for (const k of ["nobs", "interval_micros", "t_start_micros", "t_end_micros"])
    finite(source[k], k);
  if (source.unit !== null) string(source.unit, "unit");
  if (
    !Array.isArray(source.episode_ids) ||
    source.episode_ids.some((v) => typeof v !== "string")
  )
    throw new Error("Invalid modelling response episode ids.");
  const configuration = plain(d.configuration, "configuration");
  finite(configuration.alpha, "alpha");
  if (Number(configuration.alpha) <= 0 || Number(configuration.alpha) >= 1)
    throw new Error("Invalid modelling response alpha.");
  if (!["absolute", "relative", "index"].includes(String(source.time_kind)))
    throw new Error("Invalid modelling response time coordinate.");
  if (!plain(d.baselines, "baselines").naive)
    throw new Error("The modelling response has no naive reference forecast.");
  const all = [
    plain(d.forecast, "forecast"),
    ...Object.values(plain(d.baselines, "baselines")).map((v) =>
      plain(v, "baseline"),
    ),
  ];
  if (all.length > 5) throw new Error("Too many modelling forecast records.");
  for (const record of all) {
    string(record.method, "method");
    const result = plain(record.result, "forecast result");
    for (const key of ["mean", "lower", "upper"]) {
      if (!Array.isArray(result[key]) || result[key].length !== expectedLength)
        throw new Error(
          "Forecast response lengths do not match the requested horizon.",
        );
      (result[key] as unknown[]).forEach((v) => finite(v, key));
    }
    for (let i = 0; i < expectedLength; i++) {
      if (
        (result.lower as number[])[i] > (result.mean as number[])[i] ||
        (result.mean as number[])[i] > (result.upper as number[])[i]
      )
        throw new Error(
          "Forecast response intervals do not contain the point forecast.",
        );
    }
  }
  const bt = plain(plain(d.backtest, "backtest").result, "backtest result");
  if (!Array.isArray(bt.folds) || bt.folds.length === 0 || bt.folds.length > 12)
    throw new Error("Invalid modelling response fold count.");
  for (const fold of bt.folds) {
    const f = plain(fold, "fold");
    for (const key of [
      "training_start",
      "training_stop",
      "test_start",
      "test_stop",
    ])
      finite(f[key], key);
    if (
      Number(f.training_start) >= Number(f.training_stop) ||
      f.training_stop !== f.test_start ||
      Number(f.test_start) >= Number(f.test_stop)
    )
      throw new Error("Invalid chronological backtest fold.");
  }
  const mm = plain(bt.metrics, "backtest metrics");
  if (!mm.arima || !mm.naive)
    throw new Error(
      "The modelling response omits candidate or naive comparison metrics.",
    );
  Object.values(mm).forEach(metrics);
  const by = plain(bt.by_horizon, "horizon metrics");
  for (const v of Object.values(by))
    Object.values(plain(v, "horizon metrics")).forEach(metrics);
  const cases = plain(bt.cases, "backtest cases");
  for (const v of Object.values(cases)) {
    if (!Array.isArray(v) || v.length > 12 * 96)
      throw new Error("Invalid modelling response cases.");
    for (const row of v) {
      const c = plain(row, "case");
      for (const key of [
        "origin",
        "target_index",
        "horizon",
        "actual",
        "predicted",
        "lower",
        "upper",
      ])
        finite(c[key], key);
    }
  }
  if (
    !Array.isArray(d.warnings) ||
    d.warnings.some((v) => typeof v !== "string")
  )
    throw new Error("Invalid modelling response warnings.");
  const runtime = plain(d.runtime, "runtime");
  for (const k of ["opentsx", "statsmodels", "numpy", "scipy"])
    string(runtime[k], k);
  return d as unknown as ModellingResult;
}

export async function runModelling(
  document: ReturnType<typeof modellingRequest>,
  signal?: AbortSignal,
): Promise<ModellingResult> {
  let response: Response;
  try {
    response = await fetch("/analysis/v1/modelling/runs", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(document),
      signal,
    });
  } catch (e) {
    if (e instanceof Error && e.name === "AbortError") throw e;
    throw new Error(
      "The modelling service is unavailable. Start the local numerical service and retry.",
    );
  }
  let text: string;
  try {
    text = await response.text();
  } catch {
    throw new Error("The modelling service response could not be read.");
  }
  if (text.length > 8 * 1024 * 1024)
    throw new Error("The modelling response exceeds 8 MiB.");
  let value: unknown;
  try {
    value = JSON.parse(text);
  } catch {
    throw new Error(
      `The modelling service returned invalid JSON (HTTP ${response.status}).`,
    );
  }
  if (!response.ok) {
    const error = plain(value, "error");
    throw new Error(
      typeof error.error === "string"
        ? error.error
        : `Modelling service HTTP ${response.status}.`,
    );
  }
  const result = inspectModellingResult(value, document.configuration.steps);
  if (
    result.source.series_id !== document.target.series_id ||
    result.source.nobs !== document.target.values.length ||
    result.source.time_kind !== document.target.time_kind
  )
    throw new Error(
      "The modelling response does not match the submitted target.",
    );
  return result;
}
