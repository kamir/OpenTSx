import { useMemo, useState } from "react";
import type { Bucket, Series } from "./types";
import "./pairResults.css";

interface PairRow {
  key: string;
  a: string;
  b: string;
  method: string;
  centre: number;
  values: Record<string, number>;
  quality: Record<string, number | undefined>;
  labels: Record<string, string>;
}

function rowsOf(bucket: Bucket): PairRow[] {
  const rows = new Map<string, PairRow>();
  for (const series of bucket.series) {
    const match = /^(pair\.[^.]+)\.(.+)$/.exec(series.metric ?? "");
    if (!match || !series.tags?.a || !series.tags.b) continue;
    for (let i = 0; i < series.y.length; i++) {
      const centre = series.x[i];
      if (!Number.isFinite(centre)) continue;
      const key = JSON.stringify([
        match[1],
        series.tags.a,
        series.tags.b,
        centre,
      ]);
      const labels =
        bucket.episodes?.find(
          (r) =>
            r.seriesId === series.id && centre >= r.start && centre < r.end,
        )?.labels ?? {};
      const row = rows.get(key) ?? {
        key,
        a: series.tags.a,
        b: series.tags.b,
        method: match[1],
        centre,
        values: {},
        quality: {},
        labels,
      };
      row.values[match[2]] = series.y[i];
      row.quality[match[2]] =
        series.qualityKnown?.[i] === 0 ? undefined : series.quality?.[i];
      rows.set(key, row);
    }
  }
  return [...rows.values()];
}

function valueText(value: number | undefined): string {
  return value !== undefined && Number.isFinite(value)
    ? String(Number(value.toPrecision(7)))
    : "Unavailable";
}

function status(row: PairRow, field: string): string {
  if (row.quality[field] === undefined) return "Quality not supplied";
  if (row.quality[field] === 1) return "Below minimum coverage";
  if (row.quality[field]) return `Quality ${row.quality[field]}`;
  return Number.isFinite(row.values[field])
    ? "Available"
    : "Undefined measurement";
}

function isPair(series: Series): boolean {
  return (
    /^pair\.[^.]+\./.test(series.metric ?? "") &&
    !!series.tags?.a &&
    !!series.tags.b
  );
}

export default function PairResults({ bucket }: { bucket: Bucket }) {
  const rows = useMemo(() => rowsOf(bucket), [bucket]);
  const fields = [...new Set(rows.flatMap((r) => Object.keys(r.values)))];
  const centres = [...new Set(rows.map((r) => r.centre))].sort((a, b) => a - b);
  const [fieldChoice, setField] = useState("");
  const [windowChoice, setWindow] = useState("");
  const [filter, setFilter] = useState("");
  const [sort, setSort] = useState("descending");
  const [selectedKey, setSelected] = useState("");
  const [page, setPage] = useState(0);
  const field = fields.includes(fieldChoice) ? fieldChoice : (fields[0] ?? "");
  const window =
    windowChoice === "all" || centres.some((x) => String(x) === windowChoice)
      ? windowChoice
      : String(centres[0] ?? "");
  if (!bucket.series.some(isPair)) return null;
  const words = filter.toLowerCase().trim();
  const filtered = rows.filter(
    (r) =>
      field in r.values &&
      (window === "all" || r.centre === Number(window)) &&
      `${r.a} ${r.b}`.toLowerCase().includes(words),
  );
  const sorted = [...filtered].sort((a, b) => {
    const av = a.values[field];
    const bv = b.values[field];
    const af = Number.isFinite(av);
    const bf = Number.isFinite(bv);
    if (af !== bf) return af ? -1 : 1;
    if (sort !== "pair" && af && bf && av !== bv)
      return sort === "ascending" ? av - bv : bv - av;
    return (
      a.a.localeCompare(b.a) || a.b.localeCompare(b.b) || a.centre - b.centre
    );
  });
  const currentPage = Math.min(
    page,
    Math.max(0, Math.ceil(sorted.length / 100) - 1),
  );
  const visible = sorted.slice(currentPage * 100, (currentPage + 1) * 100);
  const selected = filtered.find((r) => r.key === selectedKey) ?? sorted[0];
  const axisA = [...new Set(filtered.map((r) => r.a))].sort();
  const axisB = [...new Set(filtered.map((r) => r.b))].sort();
  const cells = new Map(filtered.map((r) => [JSON.stringify([r.a, r.b]), r]));
  const finite = filtered.map((r) => r.values[field]).filter(Number.isFinite);
  const magnitude = finite.reduce((m, v) => Math.max(m, Math.abs(v)), 0);
  const stamp = (centre: number) => {
    if (bucket.t0Ms === undefined) return `${centre} s from the bucket origin`;
    const date = new Date(bucket.t0Ms + centre * 1000);
    return Number.isFinite(date.getTime())
      ? date.toISOString()
      : "Absolute time is outside the supported range";
  };
  const chooseField = (v: string) => {
    setField(v);
    setPage(0);
  };
  const chooseWindow = (v: string) => {
    setWindow(v);
    setPage(0);
  };

  return (
    <section className="wb-pairs" aria-label="Pair analysis results">
      <h2>Pair analysis results</h2>
      <p>
        Rows are A, columns are B. The matrix shows computed directions only; an
        absent cell is not a zero.
      </p>
      {rows.some((r) => r.method === "pair.xcorr") && (
        <p>
          Positive lag means B follows A. Statistical dependence does not
          establish causality.
        </p>
      )}
      {rows.some((r) => r.method === "pair.es") && (
        <p>
          q_ab: B follows A; q_ba: A follows B. Statistical dependence does not
          establish causality.
        </p>
      )}
      <div className="wb-pair-controls">
        <label>
          Result field
          <select value={field} onChange={(e) => chooseField(e.target.value)}>
            {fields.map((f) => (
              <option key={f}>{f}</option>
            ))}
          </select>
        </label>
        <label>
          Window centre
          <select value={window} onChange={(e) => chooseWindow(e.target.value)}>
            <option value="all">All windows (table)</option>
            {centres.map((c) => (
              <option key={c} value={String(c)}>
                {c} s
              </option>
            ))}
          </select>
        </label>
        <label>
          Filter pairs
          <input
            value={filter}
            onChange={(e) => {
              setFilter(e.target.value);
              setPage(0);
            }}
          />
        </label>
        <label>
          Sort pairs
          <select
            value={sort}
            onChange={(e) => {
              setSort(e.target.value);
              setPage(0);
            }}
          >
            <option value="descending">Value descending</option>
            <option value="ascending">Value ascending</option>
            <option value="pair">A then B</option>
          </select>
        </label>
      </div>
      <p>{filtered.length} pair windows</p>
      {filtered.length === 0 ? (
        <p>No pair windows match these filters.</p>
      ) : (
        <>
          <div className="wb-pair-scroll">
            <table className="wb-table" aria-label="Pair result values">
              <thead>
                <tr>
                  <th>A</th>
                  <th>B</th>
                  <th>Centre [s]</th>
                  <th>
                    {field}
                    {field === "lag" ? " [s]" : ""}
                  </th>
                  <th>Quality</th>
                  <th>Details</th>
                </tr>
              </thead>
              <tbody>
                {visible.map((row) => (
                  <tr key={row.key}>
                    <td>{row.a}</td>
                    <td>{row.b}</td>
                    <td title={stamp(row.centre)}>{row.centre}</td>
                    <td>{valueText(row.values[field])}</td>
                    <td>{status(row, field)}</td>
                    <td>
                      <button
                        onClick={() => setSelected(row.key)}
                        aria-label={`Inspect ${row.a} and ${row.b} at ${row.centre} s`}
                      >
                        Inspect
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {sorted.length > 100 && (
            <div className="wb-pair-controls">
              <button
                disabled={currentPage === 0}
                onClick={() => setPage(currentPage - 1)}
              >
                Previous pairs
              </button>
              <span>
                Page {currentPage + 1} of {Math.ceil(sorted.length / 100)}
              </span>
              <button
                disabled={(currentPage + 1) * 100 >= sorted.length}
                onClick={() => setPage(currentPage + 1)}
              >
                Next pairs
              </button>
            </div>
          )}
          {window === "all" ? (
            <p>Select one window centre to inspect its matrix.</p>
          ) : (
            <div className="wb-pair-scroll">
              <table className="wb-pair-matrix" aria-label="Pair matrix">
                <caption>
                  {field} at {window} s ({stamp(Number(window))})
                </caption>
                <thead>
                  <tr>
                    <th>A / B</th>
                    {axisB.map((b) => (
                      <th key={b}>{b}</th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {axisA.map((a) => (
                    <tr key={a}>
                      <th>{a}</th>
                      {axisB.map((b) => {
                        const row = cells.get(JSON.stringify([a, b]));
                        const v = row?.values[field];
                        const available = v !== undefined && Number.isFinite(v);
                        const intensity =
                          available && magnitude > 0
                            ? Math.min(1, Math.abs(v) / magnitude)
                            : 0;
                        return (
                          <td key={b}>
                            {row ? (
                              <button
                                aria-label={`${a} to ${b}: ${valueText(v)}`}
                                title={status(row, field)}
                                onClick={() => setSelected(row.key)}
                                style={{
                                  background: available
                                    ? `hsl(${v! < 0 ? 25 : 205} 55% ${96 - intensity * 40}%)`
                                    : "#eee",
                                }}
                              >
                                {valueText(v)}
                              </button>
                            ) : (
                              <span title="Pair not computed">
                                Not computed
                              </span>
                            )}
                          </td>
                        );
                      })}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </>
      )}
      {selected && (
        <section className="wb-pair-detail" aria-label="Selected pair window">
          <h3>
            {selected.a} → {selected.b}
          </h3>
          <p>
            {selected.method}, centre {selected.centre} s (
            {stamp(selected.centre)})
          </p>
          <dl>
            <dt>Input episode A</dt>
            <dd>{selected.labels["pair.a"] ?? "Not recorded"}</dd>
            <dt>Input episode B</dt>
            <dd>{selected.labels["pair.b"] ?? "Not recorded"}</dd>
          </dl>
          <ul>
            {Object.entries(selected.values).map(([f, v]) => (
              <li key={f}>
                {f}: {valueText(v)}
                {f === "lag" ? " s" : ""} ({status(selected, f)})
              </li>
            ))}
          </ul>
          {selected.method === "pair.es" && (
            <>
              <p>A surrogate mean is a baseline, not a significance test.</p>
              <p>q_ab: B follows A; q_ba: A follows B.</p>
            </>
          )}
          <p>
            {Object.entries(selected.labels)
              .filter(([k]) => !["pair.a", "pair.b", "pair.method"].includes(k))
              .map(([k, v]) => `${k.replace(/^pair\./, "")}: ${v}`)
              .join(", ")}
          </p>
        </section>
      )}
    </section>
  );
}
