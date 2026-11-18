import { useMemo, useState } from "react";
import { toDagYaml, useFlow } from "./flow";
import { defaultParams, fmt, OP_BY_ID } from "./ops";
import type { Bucket, Series } from "./types";
import { prepareDiagnostic } from "./DeviceDiagnostics";
import "./device-views.css";

function coordinate(bucket: Bucket, value: number): string {
  if (!Number.isFinite(value)) return "Invalid coordinate";
  if (bucket.xKind === "time" && bucket.t0Ms !== undefined) {
    const date = new Date(bucket.t0Ms + value * 1000);
    return Number.isFinite(date.getTime())
      ? date.toISOString()
      : "Invalid UTC coordinate";
  }
  return fmt(value);
}

function coordinateLabel(bucket: Bucket): string {
  if (bucket.xKind === "time")
    return bucket.t0Ms === undefined ? "Relative time [s]" : "Time [UTC]";
  return bucket.xLabel || bucket.xKind;
}

function qualityAt(series: Series, index: number): string {
  if (!Number.isFinite(series.y[index])) return "Missing";
  if (
    !series.quality ||
    series.quality.length !== series.y.length ||
    series.qualityKnown?.[index] === 0
  )
    return "Unknown";
  return series.quality[index] === 0
    ? "Good"
    : `Flagged (${series.quality[index]})`;
}

function Plot({
  series,
  title,
  xLabel,
  yLabel,
  bucket,
  bars = false,
}: {
  series: Series;
  title: string;
  xLabel: string;
  yLabel: string;
  bucket?: Bucket;
  bars?: boolean;
}) {
  const domain = useMemo(() => {
    let xmin = Infinity,
      xmax = -Infinity,
      ymin = Infinity,
      ymax = -Infinity;
    for (let i = 0; i < series.x.length; i++) {
      if (!Number.isFinite(series.x[i]) || !Number.isFinite(series.y[i]))
        continue;
      xmin = Math.min(xmin, series.x[i]);
      xmax = Math.max(xmax, series.x[i]);
      ymin = Math.min(ymin, series.y[i]);
      ymax = Math.max(ymax, series.y[i]);
    }
    return {
      xmin,
      xmax,
      ymin: bars ? Math.min(0, ymin) : ymin,
      ymax: bars ? Math.max(0, ymax) : ymax,
    };
  }, [series, bars]);
  if (!Number.isFinite(domain.xmin))
    return <p>No finite values are available for {title}.</p>;
  const { xmin, xmax, ymin, ymax } = domain;
  const X = (value: number) => 65 + ((value - xmin) / (xmax - xmin || 1)) * 610;
  const Y = (value: number) =>
    215 - ((value - ymin) / (ymax - ymin || 1)) * 165;
  let path = "";
  let drawing = false;
  for (let i = 0; i < series.x.length; i++) {
    if (!Number.isFinite(series.x[i]) || !Number.isFinite(series.y[i])) {
      drawing = false;
      continue;
    }
    path += `${drawing ? "L" : "M"}${X(series.x[i])},${Y(series.y[i])} `;
    drawing = true;
  }
  return (
    <section className="device-plot">
      <h3>{title}</h3>
      <svg viewBox="0 0 710 280" role="img" aria-label={title}>
        <title>
          {title}: {xLabel}; {yLabel}
        </title>
        {[0, 1, 2, 3, 4].map((i) => {
          const x = xmin + ((xmax - xmin) * i) / 4;
          const y = ymin + ((ymax - ymin) * i) / 4;
          const label =
            bucket?.xKind === "time" && bucket.t0Ms !== undefined
              ? coordinate(bucket, x).slice(11, 19)
              : fmt(x);
          return (
            <g key={i}>
              <line
                x1={X(x)}
                x2={X(x)}
                y1="50"
                y2="215"
                className="device-gridline"
              />
              <line
                x1="65"
                x2="675"
                y1={Y(y)}
                y2={Y(y)}
                className="device-gridline"
              />
              <text x={X(x)} y="238" textAnchor="middle">
                {label}
              </text>
              <text x="55" y={Y(y) + 4} textAnchor="end">
                {fmt(y)}
              </text>
            </g>
          );
        })}
        {bars ? (
          Array.from(series.x, (x, i) => (
            <line
              key={i}
              x1={X(x)}
              x2={X(x)}
              y1={Y(0)}
              y2={Y(series.y[i])}
              className="device-trace device-bar"
            />
          ))
        ) : (
          <path d={path} className="device-trace" />
        )}
        <text x="365" y="267" textAnchor="middle">
          {xLabel}
        </text>
        <text x="16" y="135" textAnchor="middle" transform="rotate(-90 16 135)">
          {yLabel}
        </text>
      </svg>
    </section>
  );
}

export function DiagnosticBench({ bucket }: { bucket: Bucket | null }) {
  const [seriesId, setSeriesId] = useState("");
  const [episodeId, setEpisodeId] = useState("");
  const [maxLag, setMaxLag] = useState(
    Number(defaultParams(OP_BY_ID.acf).maxLag),
  );
  const [allowUnknown, setAllowUnknown] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [run, setRun] = useState<{
    source: Bucket;
    seriesId: string;
    episodeId: string;
    lag: number;
    input: Bucket;
    fft: Bucket;
    acf: Bucket;
    qualityUnknown: boolean;
    acknowledgedUnknown: boolean;
  } | null>(null);
  const selected =
    bucket?.series.find((item) => item.id === seriesId) ?? bucket?.series[0];
  const episodes =
    bucket?.episodes?.filter((item) => item.seriesId === selected?.id) ?? [];
  const selectedEpisode = episodes.some((item) => item.episodeId === episodeId)
    ? episodeId
    : "";
  const current =
    run &&
    run.source === bucket &&
    run.seriesId === selected?.id &&
    run.episodeId === selectedEpisode &&
    run.lag === maxLag &&
    run.acknowledgedUnknown === allowUnknown;
  function execute() {
    setError(null);
    try {
      if (!bucket || !selected)
        throw new Error("Load a series before running diagnostics.");
      if (!Number.isSafeInteger(maxLag) || maxLag < 1 || maxLag > 1024)
        throw new Error(
          "Maximum lag must be an integer from 1 to 1024 samples.",
        );
      const input = prepareDiagnostic(
        bucket,
        selected.id,
        selectedEpisode,
        allowUnknown,
      );
      const fft = OP_BY_ID.fft.apply(input, defaultParams(OP_BY_ID.fft));
      const acf = OP_BY_ID.acf.apply(input, { maxLag });
      if (
        [fft, acf].some((output) =>
          output.series.some((item) =>
            Array.from(item.y).some((value) => !Number.isFinite(value)),
          ),
        )
      )
        throw new Error("The diagnostic kernel returned a non-finite result.");
      setRun({
        source: bucket,
        seriesId: selected.id,
        episodeId: selectedEpisode,
        lag: maxLag,
        input,
        fft,
        acf,
        qualityUnknown:
          input.series[0].quality === undefined ||
          input.series[0].qualityKnown?.some((value) => value === 0) === true,
        acknowledgedUnknown: allowUnknown,
      });
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : String(failure));
    }
  }
  if (!bucket || !selected)
    return (
      <section className="device-diagnostics" aria-label="Diagnostic bench">
        <h2>Diagnostic bench</h2>
        <p>
          Load source episodes to inspect time, frequency and autocorrelation
          together.
        </p>
      </section>
    );
  return (
    <section className="device-diagnostics" aria-label="Diagnostic bench">
      <header className="device-view-heading">
        <div>
          <span className="device-eyebrow">E2 / Diagnostic bench</span>
          <h2>Inspect a clean episode</h2>
        </div>
        <span>Finite analysis</span>
      </header>
      <div className="device-diagnostic-controls">
        <label>
          Signal
          <select
            aria-label="Diagnostic signal"
            value={selected.id}
            onChange={(event) => {
              setSeriesId(event.target.value);
              setEpisodeId("");
            }}
          >
            {bucket.series.map((item) => (
              <option key={item.id} value={item.id}>
                {item.label} [{item.unit ?? "unit not supplied"}]
              </option>
            ))}
          </select>
        </label>
        <label>
          Episode
          <select
            aria-label="Diagnostic episode"
            value={selectedEpisode}
            onChange={(event) => setEpisodeId(event.target.value)}
          >
            <option value="">Whole selected series</option>
            {episodes.map((item, i) => (
              <option key={`${item.episodeId}-${i}`} value={item.episodeId}>
                {item.episodeId}
              </option>
            ))}
          </select>
        </label>
        <label>
          Maximum lag [samples]
          <input
            aria-label="Diagnostic maximum lag"
            type="number"
            min="1"
            max="1024"
            value={maxLag}
            onChange={(event) => setMaxLag(Number(event.target.value))}
          />
        </label>
        <button className="device-primary" onClick={execute}>
          Run diagnostics
        </button>
      </div>
      <div className="device-diagnostic-policy">
        <span>
          FFT: mean removed, zero-padded to a power of two. ACF: capped at half
          the selected length.
        </span>
        <label>
          <input
            type="checkbox"
            checked={allowUnknown}
            onChange={(event) => setAllowUnknown(event.target.checked)}
          />
          Acknowledge unknown quality
        </label>
      </div>
      {error && (
        <p role="alert" className="device-view-error">
          {error}
        </p>
      )}
      {!current ? (
        <p className="device-view-empty">
          Select a gap-free regular episode of 4 to 2048 samples, then run
          diagnostics. No graph nodes or deployed processors are changed.
        </p>
      ) : (
        <>
          <p role="status">
            Completed on {run.input.series[0].y.length} samples. Quality:{" "}
            {run.qualityUnknown
              ? "unknown, explicitly acknowledged"
              : "supplied codes are zero"}
            .
          </p>
          <Plot
            title="Time domain"
            series={run.input.series[0]}
            bucket={run.input}
            xLabel={coordinateLabel(run.input)}
            yLabel={`${selected.label} [${selected.unit ?? "unit not supplied"}]`}
          />
          <div className="device-diagnostic-plots">
            <Plot
              title="FFT amplitude"
              series={run.fft.series[0]}
              xLabel={run.fft.xLabel}
              yLabel={`Amplitude [${selected.unit ?? "unit not supplied"}]`}
            />
            <Plot
              title="Autocorrelation"
              series={run.acf.series[0]}
              xLabel="Lag [samples]"
              yLabel="ACF [dimensionless]"
              bars
            />
          </div>
          <details>
            <summary>Numerical results</summary>
            <table className="device-data-table">
              <thead>
                <tr>
                  <th>Frequency</th>
                  <th>Amplitude</th>
                </tr>
              </thead>
              <tbody>
                {Array.from(run.fft.series[0].x.slice(0, 100), (x, i) => (
                  <tr key={i}>
                    <td>{fmt(x)}</td>
                    <td>{fmt(run.fft.series[0].y[i])}</td>
                  </tr>
                ))}
              </tbody>
            </table>
            <p>
              Showing at most 100 FFT bins. Full plots use every returned bin.
            </p>
          </details>
        </>
      )}
      <details>
        <summary>Episode ranges and source identity</summary>
        <table className="device-data-table">
          <thead>
            <tr>
              <th>Episode</th>
              <th>Start</th>
              <th>End, exclusive</th>
            </tr>
          </thead>
          <tbody>
            {episodes.map((item, i) => (
              <tr key={`${item.episodeId}-${i}`}>
                <td>{item.episodeId}</td>
                <td>{coordinate(bucket, item.start)}</td>
                <td>{coordinate(bucket, item.end)}</td>
              </tr>
            ))}
          </tbody>
        </table>
        <p>Source series: {selected.id}. Missing quality remains unknown.</p>
      </details>
    </section>
  );
}

export function DataPreview({
  bucket,
  limit = 100,
}: {
  bucket: Bucket | null;
  limit?: number;
}) {
  const rows = useMemo(() => {
    if (!bucket) return [];
    const items: { series: Series; index: number }[] = [];
    const longest = bucket.series.reduce(
      (count, item) => Math.max(count, item.y.length),
      0,
    );
    for (
      let index = 0;
      index < longest && items.length < Math.min(limit, 100);
      index++
    ) {
      for (const series of bucket.series) {
        if (index < series.y.length) items.push({ series, index });
        if (items.length >= Math.min(limit, 100)) break;
      }
    }
    return items;
  }, [bucket, limit]);
  if (!bucket)
    return (
      <section className="device-data-preview" aria-label="Data preview">
        <h2>Data preview</h2>
        <p>
          Load source episodes to inspect actual coordinates, values, units and
          quality.
        </p>
      </section>
    );
  return (
    <section className="device-data-preview" aria-label="Data preview">
      <header className="device-view-heading">
        <div>
          <span className="device-eyebrow">D1 / Selected output</span>
          <h2>{bucket.name}</h2>
        </div>
        <span>{bucket.series.length} series</span>
      </header>
      <p>
        First {rows.length} samples, interleaved by series. Missing values are
        displayed without imputation.
      </p>
      <div className="device-table-scroll">
        <table className="device-data-table">
          <thead>
            <tr>
              <th>{coordinateLabel(bucket)}</th>
              <th>Series</th>
              <th>Value</th>
              <th>Unit</th>
              <th>Quality</th>
            </tr>
          </thead>
          <tbody>
            {rows.map(({ series, index }) => (
              <tr key={`${series.id}-${index}`}>
                <td>{coordinate(bucket, series.x[index])}</td>
                <td>{series.label}</td>
                <td
                  className={
                    !Number.isFinite(series.y[index]) ? "device-missing" : ""
                  }
                >
                  {Number.isFinite(series.y[index])
                    ? fmt(series.y[index])
                    : "null"}
                </td>
                <td>{series.unit ?? "Not supplied"}</td>
                <td>{qualityAt(series, index)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {rows.length === 0 && (
        <p>No samples are available in the selected output.</p>
      )}
      <details>
        <summary>Retained series schema and identity</summary>
        <table className="device-data-table">
          <thead>
            <tr>
              <th>Series id</th>
              <th>Label</th>
              <th>Unit</th>
              <th>Samples</th>
            </tr>
          </thead>
          <tbody>
            {bucket.series.map((series) => (
              <tr key={series.id}>
                <td>{series.id}</td>
                <td>{series.label}</td>
                <td>{series.unit ?? "Not supplied"}</td>
                <td>{series.y.length}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </details>
    </section>
  );
}

export function RecipeNotebook({
  bucket,
  onModelling,
  onSnapshot,
}: {
  bucket: Bucket | null;
  onModelling: () => void;
  onSnapshot: () => void;
}) {
  const state = useFlow();
  const recipe = useMemo(() => toDagYaml(state), [state]);
  return (
    <section className="device-notebook" aria-label="Recipe notebook">
      <header className="device-view-heading">
        <div>
          <span className="device-eyebrow">D2 / Recipe notebook</span>
          <h2>{state.track} / Analysis recipe</h2>
        </div>
        <span>Finite research</span>
      </header>
      <article className="device-recipe-cell">
        <header>
          <b>01</b>
          <div>
            <h3>Capture episodes</h3>
            <p>
              Inspect the source snapshot and its declared coordinate system.
            </p>
          </div>
        </header>
        <dl>
          <dt>Source</dt>
          <dd>
            {state.sourceInfo
              ? `${state.sourceInfo.kind}: ${state.sourceInfo.detail}`
              : "No source loaded"}
          </dd>
          <dt>Topic draft</dt>
          <dd>{state.sourceTopic}</dd>
          <dt>Time reference</dt>
          <dd>
            {state.source?.t0Ms === undefined
              ? "No absolute UTC origin supplied"
              : coordinate(state.source, 0)}
          </dd>
          <dt>Episodes</dt>
          <dd>{state.source?.episodes?.length ?? 0} retained identities</dd>
        </dl>
        {state.sourceInfo?.kind === "generator" && (
          <p className="device-synthetic">
            Synthetic source, not a measurement.
          </p>
        )}
      </article>
      <article className="device-recipe-cell">
        <header>
          <b>02</b>
          <div>
            <h3>Prepare and align</h3>
            <p>Select a recorded step to inspect its actual output.</p>
          </div>
        </header>
        <ol className="device-recipe-steps">
          {state.nodes.map((node) => (
            <li key={node.id}>
              <button
                aria-pressed={state.selectedId === node.id}
                onClick={() => state.select(node.id)}
              >
                {OP_BY_ID[node.op]?.label ?? "Source"}
                <span>{node.label}</span>
              </button>
            </li>
          ))}
        </ol>
        <details>
          <summary>Recorded DAG YAML</summary>
          <pre tabIndex={0}>{recipe}</pre>
        </details>
        <DataPreview bucket={bucket} limit={6} />
      </article>
      <article className="device-recipe-cell">
        <header>
          <b>03</b>
          <div>
            <h3>Fit and backtest</h3>
            <p>
              Use the shared finite editor for ARIMA, SARIMA, SARIMAX, baselines
              and intervals.
            </p>
          </div>
        </header>
        <p>
          Fitted artifacts and completed results remain in the modelling editor.
          This recipe does not claim that an unexecuted step succeeded.
        </p>
        <button className="device-primary" onClick={onModelling}>
          Open modelling and backtests
        </button>
      </article>
      <article className="device-recipe-cell">
        <header>
          <b>04</b>
          <div>
            <h3>Review and export</h3>
            <p>
              Inspect source evidence and export the existing reproducible
              snapshot.
            </p>
          </div>
        </header>
        <button disabled={!state.source} onClick={onSnapshot}>
          Open snapshot export
        </button>
        <p>
          Registration requires a reviewed fit. Continuous scoring is configured
          separately in Smart Topics.
        </p>
      </article>
    </section>
  );
}
