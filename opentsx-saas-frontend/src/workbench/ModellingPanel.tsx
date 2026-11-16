import { useEffect, useMemo, useRef, useState } from "react";
import type { Bucket, Series } from "./types";
import {
  modellingRequest,
  runModelling,
  type Baseline,
  type ForecastMetrics,
  type ModellingOptions,
  type ModellingResult,
} from "./modelling";
import "./modelling.css";

function defaults(n: number): ModellingOptions {
  const stop = Math.min(n, 512),
    initial = Math.max(8, Math.min(stop - 1, Math.floor(stop * 0.7)));
  const horizon = Math.max(1, Math.min(4, stop - initial));
  return {
    family: "arima",
    start: 0,
    stop,
    steps: 4,
    order: [1, 0, 0],
    seasonalOrder: [0, 0, 0, 0],
    trend: "n",
    alpha: 0.05,
    maxiter: 100,
    initial,
    horizon,
    step: Math.max(horizon, Math.ceil((stop - initial - horizon + 1) / 8)),
    window: 0,
    seasonalPeriod: Math.max(1, Math.min(24, Math.floor(initial / 3))),
    baselines: ["naive", "drift"],
    contextText: "",
  };
}
function display(value: number | null | undefined): string {
  return value == null ? "Undefined" : String(Number(value.toPrecision(7)));
}
function Field({
  name,
  value,
  onChange,
  min,
  max,
  step = 1,
}: {
  name: string;
  value: number;
  onChange: (value: number) => void;
  min?: number;
  max?: number;
  step?: number;
}) {
  return (
    <label>
      {name}
      <input
        aria-label={name}
        type="number"
        value={Number.isNaN(value) ? "" : value}
        min={min}
        max={max}
        step={step}
        onChange={(e) =>
          onChange(e.target.value === "" ? NaN : Number(e.target.value))
        }
      />
    </label>
  );
}
function ForecastChart({
  series,
  run,
  baseline,
}: {
  series: Series;
  run: ModellingResult;
  baseline: string;
}) {
  const result = run.forecast.result,
    base = run.baselines[baseline]?.result;
  const step = series.x.length > 1 ? series.x[1] - series.x[0] : 1;
  const end = series.x[series.x.length - 1];
  const observed = Array.from(series.y),
    prediction = result.mean;
  const future = prediction.map((_, i) => end + (i + 1) * step);
  const all = [
    ...observed,
    ...result.lower,
    ...result.upper,
    ...(base?.mean ?? []),
  ];
  const lo = Math.min(...all),
    hi = Math.max(...all),
    xmin = series.x[0],
    xmax = future[future.length - 1];
  const X = (x: number) => 45 + ((x - xmin) / (xmax - xmin || 1)) * 790;
  const Y = (y: number) => 240 - ((y - lo) / (hi - lo || 1)) * 205;
  const path = (xs: ArrayLike<number>, ys: number[]) =>
    ys.map((y, i) => `${X(xs[i])},${Y(y)}`).join(" ");
  const band =
    path(future, result.upper) +
    " " +
    path([...future].reverse(), [...result.lower].reverse());
  const time =
    run.source.time_kind === "absolute"
      ? "seconds from the bucket origin (UTC origin retained)"
      : run.source.time_kind === "index"
        ? "sample index"
        : "relative seconds";
  return (
    <section aria-label="Forecast comparison">
      <h3>
        Observed and forecast values{series.unit ? ` [${series.unit}]` : ""}
      </h3>
      <p>
        Blue: observed. Orange: {run.forecast.method.toUpperCase()} forecast.
        Shaded: conditional Gaussian prediction interval. Dashed: {baseline}{" "}
        baseline. No fit to future observations is displayed.
      </p>
      <svg
        viewBox="0 0 880 300"
        role="img"
        aria-label="Observed target and forecast with prediction interval"
      >
        <line x1="45" y1="240" x2="835" y2="240" stroke="currentColor" />
        <line x1="45" y1="35" x2="45" y2="240" stroke="currentColor" />
        <polygon className="interval" points={band} />
        <polyline className="observed" points={path(series.x, observed)} />
        <polyline className="forecast" points={path(future, prediction)} />
        {base && (
          <polyline
            points={path(future, base.mean)}
            fill="none"
            stroke="#476743"
            strokeDasharray="6 4"
            strokeWidth="2"
          />
        )}
        <line
          x1={X(end)}
          y1="35"
          x2={X(end)}
          y2="240"
          stroke="#666"
          strokeDasharray="3 4"
        />
        <text x="3" y="35">
          {display(hi)}
        </text>
        <text x="3" y="240">
          {display(lo)}
        </text>
        <text x="45" y="260">
          {display(xmin)}
        </text>
        <text x="780" y="260">
          {display(xmax)}
        </text>
        <text x="250" y="287">
          {time}
        </text>
      </svg>
    </section>
  );
}
function MetricsTable({
  metrics,
  candidateLabel,
}: {
  metrics: Record<string, ForecastMetrics>;
  candidateLabel: string;
}) {
  return (
    <table aria-label="Backtest metrics">
      <thead>
        <tr>
          <th>Candidate</th>
          <th>MAE</th>
          <th>RMSE</th>
          <th>MASE</th>
          <th>RMSSE</th>
          <th>Coverage</th>
          <th>Interval width</th>
          <th>Interval score</th>
        </tr>
      </thead>
      <tbody>
        {Object.entries(metrics).map(([name, m]) => (
          <tr key={name}>
            <th>{name === "arima" ? candidateLabel : name}</th>
            <td>{display(m.mae)}</td>
            <td>{display(m.rmse)}</td>
            <td>{display(m.mase)}</td>
            <td>{display(m.rmsse)}</td>
            <td>{display(m.coverage)}</td>
            <td>{display(m.mean_interval_width)}</td>
            <td>{display(m.mean_interval_score)}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}
function Output({ run, series }: { run: ModellingResult; series: Series }) {
  const [baselineChoice, setBaseline] = useState("naive");
  const baseline = run.baselines[baselineChoice]
    ? baselineChoice
    : Object.keys(run.baselines)[0];
  const metrics = run.backtest.result.metrics,
    reference = metrics.naive,
    candidate = metrics.arima;
  const [horizon, setHorizon] = useState("all");
  const horizonRows = Object.fromEntries(
    Object.entries(run.backtest.result.by_horizon)
      .map(([name, by]) => [name, by[horizon]])
      .filter(([, m]) => m),
  );
  const errors = run.backtest.result.cases.arima ?? [];
  const content = JSON.stringify(run, null, 2);
  const url = useMemo(
    () =>
      URL.createObjectURL(new Blob([content], { type: "application/json" })),
    [content],
  );
  useEffect(() => () => URL.revokeObjectURL(url), [url]);
  return (
    <div className="wb-modelling-output">
      <h3>Evaluation and prediction</h3>
      <p role="status">Fit and rolling backtest completed. Run {run.run_id}.</p>
      <p>
        {candidate && reference
          ? candidate.mae < reference.mae
            ? "Candidate MAE is lower than the naive reference on these origins."
            : "Candidate MAE does not beat the naive reference on these origins."
          : "Baseline comparison is unavailable."}{" "}
        This comparison does not release a model for a business decision.
      </p>
      <label>
        Forecast reference
        <select value={baseline} onChange={(e) => setBaseline(e.target.value)}>
          {Object.keys(run.baselines).map((name) => (
            <option key={name}>{name}</option>
          ))}
        </select>
      </label>
      <ForecastChart run={run} series={series} baseline={baseline} />
      <p>
        Nominal interval coverage:{" "}
        {display((1 - Number(run.configuration.alpha)) * 100)}%. Measured
        coverage below uses held-out outcomes. Gaussian bounds do not include
        full parameter uncertainty.
      </p>
      <label>
        Metrics horizon
        <select value={horizon} onChange={(e) => setHorizon(e.target.value)}>
          <option value="all">All forecast steps</option>
          {Object.keys(run.backtest.result.by_horizon.arima ?? {}).map((h) => (
            <option key={h} value={h}>
              {h}
            </option>
          ))}
        </select>
      </label>
      <MetricsTable
        candidateLabel={run.forecast.method.toUpperCase()}
        metrics={
          horizon === "all"
            ? metrics
            : (horizonRows as Record<string, ForecastMetrics>)
        }
      />
      <details>
        <summary>
          Chronological training and test origins (
          {run.backtest.result.folds.length})
        </summary>
        <table aria-label="Backtest folds">
          <thead>
            <tr>
              <th>Origin</th>
              <th>Training [start, stop)</th>
              <th>Test [start, stop)</th>
            </tr>
          </thead>
          <tbody>
            {run.backtest.result.folds.map((f, i) => (
              <tr key={i}>
                <td>{f.test_start}</td>
                <td>
                  {f.training_start} to {f.training_stop}
                </td>
                <td>
                  {f.test_start} to {f.test_stop}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </details>
      <details>
        <summary>Held-out forecast errors ({errors.length})</summary>
        <p>
          Residual shown here is actual minus held-out prediction, in the target
          unit.
        </p>
        <table aria-label="Held-out forecast errors">
          <thead>
            <tr>
              <th>Origin</th>
              <th>Target index</th>
              <th>Step</th>
              <th>Actual</th>
              <th>Predicted</th>
              <th>Error</th>
            </tr>
          </thead>
          <tbody>
            {errors.slice(0, 200).map((c, i) => (
              <tr key={i}>
                <td>{c.origin}</td>
                <td>{c.target_index}</td>
                <td>{c.horizon}</td>
                <td>{display(c.actual)}</td>
                <td>{display(c.predicted)}</td>
                <td>{display(c.actual - c.predicted)}</td>
              </tr>
            ))}
          </tbody>
        </table>
        {errors.length > 200 && (
          <p>First 200 errors shown. Download retains all cases.</p>
        )}
      </details>
      <details>
        <summary>Fit parameters and provenance</summary>
        <dl>
          <dt>Series</dt>
          <dd>{run.source.series_id}</dd>
          <dt>Unit</dt>
          <dd>{run.source.unit ?? "Not supplied"}</dd>
          <dt>Input episodes</dt>
          <dd>
            {run.source.episode_ids.join(", ") ||
              "No source episode ids supplied"}
          </dd>
          <dt>Value SHA-256</dt>
          <dd>{run.source.values_sha256}</dd>
          <dt>Time coordinate</dt>
          <dd>
            {run.source.time_kind}; [{run.source.t_start_micros},{" "}
            {run.source.t_end_micros}) microseconds
          </dd>
          <dt>Runtime</dt>
          <dd>
            OpenTSx {run.runtime.opentsx}; statsmodels {run.runtime.statsmodels}
            ; NumPy {run.runtime.numpy}; SciPy {run.runtime.scipy}
          </dd>
        </dl>
        <pre>
          {JSON.stringify(run.forecast.result.parameters ?? {}, null, 2)}
        </pre>
        <p>
          AIC {display(run.forecast.result.aic)}; BIC{" "}
          {display(run.forecast.result.bic)}; log likelihood{" "}
          {display(run.forecast.result.log_likelihood)}.
        </p>
      </details>
      {run.warnings.length > 0 && (
        <section aria-label="Fit warnings">
          <h4>Warnings</h4>
          <ul>
            {run.warnings.map((w, i) => (
              <li key={i}>{w}</li>
            ))}
          </ul>
        </section>
      )}
      <a href={url} download="opentsx-modelling-result.json">
        Download modelling result JSON
      </a>
    </div>
  );
}

export default function ModellingPanel({ bucket }: { bucket: Bucket | null }) {
  const [targetChoice, setTarget] = useState("");
  const series =
    bucket?.series.find((s) => s.id === targetChoice) ?? bucket?.series[0];
  const [options, setOptions] = useState<ModellingOptions>(() =>
    defaults(series?.y.length ?? 0),
  );
  const [error, setError] = useState(""),
    [busy, setBusy] = useState(false);
  const [answer, setAnswer] = useState<{
    run: ModellingResult;
    series: Series;
    signature: string;
  } | null>(null);
  const controller = useRef<AbortController | null>(null),
    sourceRef = useRef<Series | undefined>(series),
    signatureRef = useRef("");
  if (sourceRef.current !== series) {
    sourceRef.current = series;
    setOptions(defaults(series?.y.length ?? 0));
    setAnswer(null);
    setError("");
    controller.current?.abort();
  }
  const signature = JSON.stringify(options) + (series?.id ?? "");
  signatureRef.current = signature;
  useEffect(() => () => controller.current?.abort(), []);
  const change = (next: Partial<ModellingOptions>) => {
    controller.current?.abort();
    setBusy(false);
    setAnswer(null);
    setError("");
    setOptions((p) => ({ ...p, ...next }));
  };
  async function fit() {
    if (!bucket || !series) return;
    controller.current?.abort();
    setError("");
    setAnswer(null);
    let request: ReturnType<typeof modellingRequest>;
    try {
      request = modellingRequest(bucket, series, options);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Invalid modelling request.");
      return;
    }
    const abort = new AbortController();
    controller.current = abort;
    const sent = signature;
    setBusy(true);
    try {
      const run = await runModelling(request, abort.signal);
      if (signatureRef.current === sent && !abort.signal.aborted)
        setAnswer({
          run,
          series: {
            ...series,
            x: series.x.slice(options.start, options.stop),
            y: series.y.slice(options.start, options.stop),
          },
          signature: sent,
        });
    } catch (e) {
      if (!abort.signal.aborted)
        setError(e instanceof Error ? e.message : "Modelling failed.");
    } finally {
      if (controller.current === abort) setBusy(false);
    }
  }
  if (!bucket || !series)
    return (
      <section className="wb-modelling" aria-label="Modelling and prediction">
        <h2>Modelling and prediction</h2>
        <p>
          Load a time-series bucket to choose a target, fit candidates and
          compare held-out forecasts.
        </p>
      </section>
    );
  const family = (value: ModellingOptions["family"]) =>
    change({
      family: value,
      contextText: "",
      seasonalOrder:
        value === "sarima"
          ? [
              1,
              0,
              0,
              Math.max(2, Math.min(24, Math.floor(options.initial / 3))),
            ]
          : [0, 0, 0, 0],
    });
  const baseline = (name: Baseline, enabled: boolean) =>
    change({
      baselines: enabled
        ? [...new Set([...options.baselines, name])]
        : options.baselines.filter((b) => b !== name),
    });
  const axis =
    bucket.xKind === "time"
      ? bucket.t0Ms === undefined
        ? "Relative time; no absolute origin supplied."
        : "Absolute timestamps retained in UTC."
      : "Sample-index coordinates; no physical sample interval is inferred.";
  return (
    <section className="wb-modelling" aria-label="Modelling and prediction">
      <h2>Modelling and prediction</h2>
      <p>
        Fit a finite ARIMA-family candidate, compare chronological held-out
        forecasts against baselines, then inspect prediction intervals. Fitting
        runs in the local Python numerical service.
      </p>
      <fieldset>
        <legend>1. Choose target and training range</legend>
        <div className="wb-modelling-grid">
          <label>
            Target series
            <select
              value={series.id}
              onChange={(e) => setTarget(e.target.value)}
            >
              {bucket.series.map((s) => (
                <option key={s.id} value={s.id}>
                  {s.label}
                </option>
              ))}
            </select>
          </label>
          <Field
            name="Range start index"
            value={options.start}
            min={0}
            max={series.y.length - 8}
            onChange={(start) => change({ start })}
          />
          <Field
            name="Range end index (exclusive)"
            value={options.stop}
            min={8}
            max={series.y.length}
            onChange={(stop) => change({ stop })}
          />
          <Field
            name="Forecast steps"
            value={options.steps}
            min={1}
            max={96}
            onChange={(steps) => change({ steps })}
          />
        </div>
        <p>
          {options.stop - options.start} selected of {series.y.length} samples;
          unit {series.unit ?? "not supplied"}. {axis} The selected range is the
          fitting data; future values are predicted after its end.
        </p>
      </fieldset>
      <fieldset>
        <legend>2. Configure candidate and context</legend>
        <div className="wb-modelling-grid">
          <label>
            Forecast family
            <select
              value={options.family}
              onChange={(e) =>
                family(e.target.value as ModellingOptions["family"])
              }
            >
              <option value="arima">ARIMA</option>
              <option value="sarima">SARIMA</option>
              <option value="sarimax">SARIMAX with external regressors</option>
            </select>
          </label>
          {(["p", "d", "q"] as const).map((name, i) => (
            <Field
              key={name}
              name={name}
              value={options.order[i]}
              min={0}
              max={i === 1 ? 2 : 3}
              onChange={(v) =>
                change({
                  order: options.order.map((x, k) =>
                    k === i ? v : x,
                  ) as ModellingOptions["order"],
                })
              }
            />
          ))}
          <label>
            Trend
            <select
              value={options.trend}
              onChange={(e) => change({ trend: e.target.value })}
            >
              <option value="n">None</option>
              <option value="c">Constant</option>
              <option value="t">Linear</option>
              <option value="ct">Constant and linear</option>
            </select>
          </label>
          <Field
            name="Optimizer iterations"
            value={options.maxiter}
            min={1}
            max={200}
            onChange={(maxiter) => change({ maxiter })}
          />
        </div>
        {options.family !== "arima" && (
          <div className="wb-modelling-grid">
            {["P", "D", "Q", "Season length"].map((name, i) => (
              <Field
                key={name}
                name={name}
                value={options.seasonalOrder[i]}
                min={0}
                max={i === 3 ? 96 : i === 1 ? 1 : 2}
                onChange={(v) =>
                  change({
                    seasonalOrder: options.seasonalOrder.map((x, k) =>
                      k === i ? v : x,
                    ) as ModellingOptions["seasonalOrder"],
                  })
                }
              />
            ))}
          </div>
        )}
        {options.family === "sarimax" && (
          <>
            <label>
              External context JSON
              <textarea
                aria-label="External context JSON"
                value={options.contextText}
                onChange={(e) => change({ contextText: e.target.value })}
                placeholder={
                  '{"names":["calendar"],"historical":[[1]],"historical_available_at_micros":[[0]],"future":[[1]],"future_available_at_micros":[[0]]}'
                }
              />
            </label>
            <p>
              Supply one historical row per selected target sample and one
              future row per forecast step. Each column needs its actual
              availability timestamp. Every backtest forecast row must already
              have been available at that origin. Realized future context is not
              an acceptable substitute for a historical forecast.
            </p>
          </>
        )}
        <p>
          AR, MA and ARMA are configurations of the ARIMA orders. These are
          stochastic forecasts, distinct from moving-average smoothing.
        </p>
      </fieldset>
      <fieldset>
        <legend>3. Compare at chronological forecast origins</legend>
        <div className="wb-modelling-grid">
          <Field
            name="Initial training samples"
            value={options.initial}
            min={8}
            max={options.stop - options.start - 1}
            onChange={(initial) => change({ initial })}
          />
          <Field
            name="Backtest horizon"
            value={options.horizon}
            min={1}
            max={96}
            onChange={(horizon) => change({ horizon })}
          />
          <Field
            name="Backtest step"
            value={options.step}
            min={1}
            onChange={(step) => change({ step })}
          />
          <Field
            name="Rolling training window (0 expands)"
            value={options.window}
            min={0}
            onChange={(window) => change({ window })}
          />
          <Field
            name="Baseline seasonal period"
            value={options.seasonalPeriod}
            min={1}
            max={96}
            onChange={(seasonalPeriod) => change({ seasonalPeriod })}
          />
          <Field
            name="Alpha"
            value={options.alpha}
            min={0.001}
            max={0.999}
            step={0.01}
            onChange={(alpha) => change({ alpha })}
          />
        </div>
        <div className="wb-modelling-grid">
          {(["naive", "mean", "drift", "seasonal_naive"] as Baseline[]).map(
            (name) => (
              <label key={name} className="wb-modelling-check">
                <input
                  aria-label={`${name === "seasonal_naive" ? "Seasonal naive" : name[0].toUpperCase() + name.slice(1)} baseline`}
                  type="checkbox"
                  checked={options.baselines.includes(name)}
                  disabled={name === "naive"}
                  onChange={(e) => baseline(name, e.target.checked)}
                />
                {name === "seasonal_naive" ? "Seasonal naive" : name} baseline
              </label>
            ),
          )}
        </div>
        <p>
          Training always precedes testing. Every origin is fitted
          independently. MAE and RMSE retain the target unit; scaled errors
          remain undefined when the training scale is zero. No random split or
          automatic missing-value filling is applied.
        </p>
      </fieldset>
      <button className="wb-primary" disabled={busy} onClick={() => void fit()}>
        {busy ? "Fitting and evaluating" : "Fit, backtest and forecast"}
      </button>
      {busy && (
        <p role="status">
          The numerical service is fitting bounded chronological runs. A failed
          fit is reported explicitly.
        </p>
      )}
      {error && <p role="alert">{error}</p>}
      {answer && answer.signature === signature && (
        <Output run={answer.run} series={answer.series} />
      )}
    </section>
  );
}
