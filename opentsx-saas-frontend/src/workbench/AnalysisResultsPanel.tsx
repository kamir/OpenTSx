import { useState } from "react";
import {
  inspectAnalysisJson,
  type AnalysisCurve,
  type InspectedAnalysis,
} from "./analysisResults";
import "./analysisResults.css";

function value(n: number): string {
  return Number.isNaN(n)
    ? "Undefined"
    : Number.isFinite(n)
      ? String(Number(n.toPrecision(8)))
      : String(n);
}

function Curve({ curve }: { curve: AnalysisCurve }) {
  const points = curve.x.map((x, i) => [x, curve.y[i]]);
  const finite = points.filter(
    ([x, y]) => Number.isFinite(x) && Number.isFinite(y),
  );
  const xs = finite.map(([x]) => x);
  const ys = finite.map(([, y]) => y);
  const minX = Math.min(...xs),
    maxX = Math.max(...xs),
    minY = Math.min(...ys),
    maxY = Math.max(...ys);
  const axis = `${curve.xName}${curve.xUnit ? ` [${curve.xUnit}]` : ""}`;
  const groups: string[][] = [[]];
  for (const [x, y] of points) {
    if (!Number.isFinite(x) || !Number.isFinite(y)) {
      if (groups[groups.length - 1].length) groups.push([]);
      continue;
    }
    groups[groups.length - 1].push(
      `${45 + (maxX === minX ? 0.5 : (x - minX) / (maxX - minX)) * 420},${170 - (maxY === minY ? 0.5 : (y - minY) / (maxY - minY)) * 140}`,
    );
  }
  return (
    <section>
      <h3>{curve.name}</h3>
      {finite.length ? (
        <svg
          viewBox="0 0 500 225"
          role="img"
          aria-label={`${curve.name} by ${axis}`}
        >
          <line x1="45" y1="170" x2="465" y2="170" stroke="currentColor" />
          <line x1="45" y1="30" x2="45" y2="170" stroke="currentColor" />
          {groups
            .filter((g) => g.length)
            .map((g, i) => (
              <g key={i}>
                <polyline
                  points={g.join(" ")}
                  fill="none"
                  stroke="#14779c"
                  strokeWidth="2"
                />
                {g.length === 1 && (
                  <circle
                    cx={g[0].split(",")[0]}
                    cy={g[0].split(",")[1]}
                    r="3"
                    fill="#14779c"
                  />
                )}
              </g>
            ))}
          <text x="45" y="190">
            {value(minX)}
          </text>
          <text x="410" y="190">
            {value(maxX)}
          </text>
          <text x="4" y="30">
            {value(maxY)}
          </text>
          <text x="4" y="170">
            {value(minY)}
          </text>
          <text x="160" y="216">
            {axis}
          </text>
          <text x="45" y="18">
            {curve.name}
            {curve.unit ? ` [${curve.unit}]` : ""}
          </text>
        </svg>
      ) : (
        <p>No finite curve points.</p>
      )}
      <p>
        The x axis is {axis}, not a time axis. Non-finite values leave gaps.
      </p>
    </section>
  );
}

export default function AnalysisResultsPanel() {
  const [json, setJson] = useState("");
  const [result, setResult] = useState<InspectedAnalysis | null>(null);
  const [error, setError] = useState("");
  const inspect = (text: string) => {
    setError("");
    setResult(null);
    try {
      setResult(inspectAnalysisJson(text));
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  };
  return (
    <details className="wb-analysis-import">
      <summary>Inspect a typed analysis result</summary>
      <p>
        Import one AnalysisResult JSON record. This reads local JSON, not Avro
        or a server topic. Maximum 1 MiB, 100 scalars, 8 curves of 2000 points
        and 4 matrices of at most 100 × 100 cells.
      </p>
      <p>
        Parameters retain their Avro type:{" "}
        {`{"long":"7"}, {"double":0.5}, {"string":"std"}, {"array":[16,32]}`}.
        Bare numeric parameters are rejected. Explicit strings "NaN", "Infinity"
        and "-Infinity" represent non-finite output values. Timestamps are
        integer microseconds or decimal strings.
      </p>
      <label>
        Analysis result file
        <input
          type="file"
          accept=".json,application/json"
          onChange={async (e) => {
            const file = e.target.files?.[0];
            if (!file) return;
            if (file.size > 1048576) {
              setResult(null);
              setError("Analysis result JSON exceeds 1 MiB.");
              return;
            }
            try {
              const text = await file.text();
              setJson(text);
              inspect(text);
            } catch (e) {
              setResult(null);
              setError(
                `File could not be read: ${e instanceof Error ? e.message : String(e)}`,
              );
            }
          }}
        />
      </label>
      <label>
        Analysis result JSON
        <textarea
          value={json}
          onChange={(e) => setJson(e.target.value)}
          rows={5}
        />
      </label>
      <button onClick={() => inspect(json)}>Inspect result JSON</button>
      {error && <p role="alert">{error}</p>}
      {result && (
        <section aria-label="Inspected analysis result">
          <h2>
            {result.method} {result.methodVersion}
          </h2>
          <p>
            {result.inputDigests.length > 0
              ? "Result identity matches method, version, typed parameters, input episode IDs and their content digests."
              : "Result identity matches method, version, typed parameters and input episode IDs. The record has no content digests, so its identity does not bind the input contents."}{" "}
            This identity check does not authenticate the output values or their
            author.
          </p>
          <dl>
            <dt>Result ID</dt>
            <dd>{result.resultId}</dd>
            <dt>Range [start, end), microseconds</dt>
            <dd>
              {result.tStart} to {result.tEnd}
            </dd>
            <dt>Source</dt>
            <dd>{result.provenance.source}</dd>
            <dt>Producer</dt>
            <dd>{result.provenance.producer}</dd>
            <dt>Created at, microseconds</dt>
            <dd>{result.provenance.createdAt}</dd>
            {result.provenance.pipeline && (
              <>
                <dt>Pipeline</dt>
                <dd>{result.provenance.pipeline}</dd>
              </>
            )}
            {result.provenance.parentEpisodeId && (
              <>
                <dt>Parent episode</dt>
                <dd>{result.provenance.parentEpisodeId}</dd>
              </>
            )}
          </dl>
          <table className="wb-table">
            <thead>
              <tr>
                <th>Input episode</th>
                <th>Input series</th>
                <th>Content digest</th>
              </tr>
            </thead>
            <tbody>
              {result.inputEpisodeIds.map((id, i) => (
                <tr key={i}>
                  <td>{id}</td>
                  <td>{result.inputSeriesIds[i]}</td>
                  <td>
                    {result.inputDigests[i] ??
                      "Not recorded (written before v2.2)"}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          <div className="wb-analysis-scalars">
            {result.scalars.map((s, i) => (
              <section key={i} aria-label={`Scalar ${s.name}`}>
                <h3>{s.name}</h3>
                <strong>
                  {value(s.value)} {s.unit}
                </strong>
                {s.stdError !== null && (
                  <p>Standard error: {value(s.stdError)}</p>
                )}
                {(s.fitFrom !== null || s.fitTo !== null) && (
                  <p>
                    Fit range:{" "}
                    {s.fitFrom === null ? "not supplied" : value(s.fitFrom)} to{" "}
                    {s.fitTo === null ? "not supplied" : value(s.fitTo)}
                  </p>
                )}
              </section>
            ))}
          </div>
          {result.curves.map((curve, i) => (
            <Curve key={i} curve={curve} />
          ))}
          {result.matrices.map((m, i) => (
            <div className="wb-analysis-matrix" key={i}>
              <h3>
                {m.name} {m.unit}
              </h3>
              <p>
                Rows and columns retain the input order. Values are row-major;
                undefined entries are not zero.
              </p>
              <table aria-label={`Matrix ${m.name}`}>
                <thead>
                  <tr>
                    <th>Row / column</th>
                    {m.colKeys.map((key) => (
                      <th key={key}>{key}</th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {m.rowKeys.map((key, row) => (
                    <tr key={key}>
                      <th>{key}</th>
                      {m.colKeys.map((col, c) => {
                        const n = m.values[row * m.colKeys.length + c];
                        return (
                          <td
                            key={col}
                            style={{
                              background: Number.isFinite(n)
                                ? `hsl(${n < 0 ? 25 : 205} 50% ${95 - Math.min(1, Math.abs(n)) * 35}%)`
                                : "#eee",
                            }}
                          >
                            {value(n)}
                          </td>
                        );
                      })}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          ))}
          <dl>
            {Object.entries(result.labels).map(([k, v]) => (
              <div key={k}>
                <dt>{k}</dt>
                <dd>{v}</dd>
              </div>
            ))}
          </dl>
          <a
            download={`${result.resultId}.analysis.json`}
            href={`data:application/json;charset=utf-8,${encodeURIComponent(result.json)}`}
          >
            Download inspected result JSON
          </a>
        </section>
      )}
    </details>
  );
}
