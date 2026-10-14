/**
 * The place "Simulate": ask the twin of the system a question and see the answer next to the reference. A question
 * is a set of decisions (a control set at a time, a policy for the electrolyser, optionally a forecast from a time
 * on). The answer is a run with an identity: its events, the KPIs of the scenario with their difference to the
 * reference, and the series the scenario compares. Every answer says how far it may be trusted.
 */

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Ask, CompareDoc, RunDoc, RunListEntry, ScenarioDoc, chartLines, deltaText, eventText, kpiText, runRequest } from './runs';

const W = 560;
const H = 150;

function Chart({ title, base, run, limits, steps }: { title: string; base: (number | null)[]; run: (number | null)[]; limits: { label: string; value: number }[]; steps: number }) {
  const c = useMemo(() => chartLines([base, run], limits.map((l) => l.value), W, H), [base, run, limits]);
  const hours = Math.floor(steps / 60);
  return (
    <figure className="wb-run-chart">
      <figcaption>
        {title} <span className="wb-muted">{c.min.toFixed(0)} to {c.max.toFixed(0)}</span>
      </figcaption>
      <svg viewBox={`0 0 ${W} ${H + 16}`} role="img" aria-label={`${title}: reference and this run over the time of the run`}>
        {Array.from({ length: hours + 1 }, (_, h) => (
          <g key={h}>
            <line x1={(h * 60 * W) / steps} x2={(h * 60 * W) / steps} y1={0} y2={H} className="grid" />
            <text x={(h * 60 * W) / steps + 2} y={H + 12}>{String(h).padStart(2, '0')}:00</text>
          </g>
        ))}
        {limits.map((l) => (
          <g key={l.label}>
            <line x1={0} x2={W} y1={c.y(l.value)} y2={c.y(l.value)} className="limit" />
            <text x={W - 4} y={c.y(l.value) - 3} textAnchor="end">{l.label} {l.value}</text>
          </g>
        ))}
        {c.paths[0].map((p, i) => <polyline key={`b${i}`} points={p} className="base" />)}
        {c.paths[1].map((p, i) => <polyline key={`r${i}`} points={p} className="run" />)}
      </svg>
    </figure>
  );
}

export default function SimulatePlace({ system, base }: { system: string; base: string }) {
  const root = `${base.replace(/\/$/, '')}/v1/systems/${encodeURIComponent(system)}`;
  const [scenario, setScenario] = useState<ScenarioDoc | null>(null);
  const [runs, setRuns] = useState<RunListEntry[]>([]);
  const [asks, setAsks] = useState<Ask[]>([{ at: '02:00', target: 'handover.valve', value: '1' }]);
  const [policy, setPolicy] = useState('follow');
  const [from, setFrom] = useState('');
  const [onReference, setOnReference] = useState(true);
  const [inputsFrom, setInputsFrom] = useState<'scenario' | 'signals'>('scenario');
  const [run, setRun] = useState<RunDoc | null>(null);
  const [compare, setCompare] = useState<CompareDoc | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const get = useCallback(
    async (path: string) => {
      const res = await fetch(`${root}${path}`);
      const json = await res.json().catch(() => ({}));
      if (!res.ok) throw new Error(json.error ?? `HTTP ${res.status}`);
      return json;
    },
    [root],
  );
  const list = useCallback(() => get('/runs').then((d) => setRuns(d.runs ?? [])).catch(() => setRuns([])), [get]);
  const show = useCallback(
    async (id: string) => {
      const doc = (await get(`/runs/${id}`)) as RunDoc;
      // a run is compared with its own reference: on the signals, the decisions of the scenario on the same inputs
      const cmp = await get(`/runs/${id}/compare${doc.baseline ? `?base=${encodeURIComponent(doc.baseline)}` : ''}`);
      setRun(doc);
      setCompare(cmp as CompareDoc);
    },
    [get],
  );

  useEffect(() => {
    let cancelled = false;
    setScenario(null);
    setRun(null);
    setCompare(null);
    setError(null);
    get('/scenario')
      .then((s) => {
        if (cancelled) return;
        setScenario(s as ScenarioDoc);
        list();
        return show((s as ScenarioDoc).reference);
      })
      .catch((e) => !cancelled && setError(`The twin of this system cannot be asked: ${e instanceof Error ? e.message : e}.`));
    return () => {
      cancelled = true;
    };
  }, [get, list, show]);

  const ask = async () => {
    if (!scenario) return;
    const req = runRequest(scenario, asks, policy, from, onReference, inputsFrom);
    if (req.error) {
      setError(req.error);
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const res = await fetch(`${root}/runs`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(req.body) });
      const json = await res.json().catch(() => ({}));
      if (!res.ok) throw new Error(json.error ?? `HTTP ${res.status}`);
      await show(json.run);
      await list();
    } catch (e) {
      setError(`The run was refused: ${e instanceof Error ? e.message : e}.`);
    } finally {
      setBusy(false);
    }
  };
  const change = (i: number, patch: Partial<Ask>) => setAsks((a) => a.map((x, k) => (k === i ? { ...x, ...patch } : x)));

  if (!scenario) {
    return (
      <main className="wb-place" aria-label="Simulate the system">
        <p className="wb-muted pad">{error ?? 'Reading what the twin of this system can be asked.'}</p>
      </main>
    );
  }
  const isReference = run?.kind === 'reference';
  return (
    <main className="wb-place" aria-label="Simulate the system">
      <header className="wb-inv-head">
        <h2>Simulate</h2>
        <span className="wb-muted">{scenario.title}</span>
      </header>
      <div className="wb-inv-source">
        <span>
          {scenario.decision || scenario.description} Ask the twin what happens when a decision changes. Every answer is a run you can
          come back to, shown next to the reference: the night as the scenario tells it.
        </span>
      </div>
      <div className="wb-simulate">
        <section className="wb-ask" aria-label="The question">
          <h3>Inputs</h3>
          <label className="wb-param">
            the run reads
            <select value={inputsFrom} onChange={(e) => setInputsFrom(e.target.value as 'scenario' | 'signals')}>
              <option value="scenario">the invented inputs of the scenario</option>
              <option value="signals">the series of the signals of the system</option>
            </select>
          </label>

          <h3>The night of the scenario</h3>
          <ul className="wb-decisions">
            {scenario.referenceDecisions.map((d) => (
              <li key={`${d.clock}${d.atom}${d.control}`}>
                <b>{d.clock.slice(0, 5)}</b> {d.atom}.{d.control} {d.value === 'release' ? 'released' : `= ${d.value}`} <span className="wb-muted">{d.reason}</span>
              </li>
            ))}
          </ul>
          <label className="wb-check">
            <input type="checkbox" checked={onReference} onChange={(e) => setOnReference(e.target.checked)} /> keep these decisions and add mine
          </label>

          <h3>What if</h3>
          {asks.map((a, i) => (
            <div key={i} className="wb-ask-row">
              <label className="wb-param">at<input type="text" value={a.at} placeholder="02:00" onChange={(e) => change(i, { at: e.target.value })} aria-label="Time of the change" /></label>
              <label className="wb-param grow">
                set
                <select value={a.target} onChange={(e) => change(i, { target: e.target.value })} aria-label="What to set">
                  <option value="">choose</option>
                  {scenario.controls.map((c) => (
                    <option key={`${c.atom}.${c.control}`} value={`${c.atom}.${c.control}`}>
                      {c.atom}.{c.control}{c.unit ? ` [${c.unit}]` : ''}
                    </option>
                  ))}
                </select>
              </label>
              <label className="wb-param">to<input type="text" value={a.value} placeholder="release" onChange={(e) => change(i, { value: e.target.value })} aria-label="Value; empty releases the control" /></label>
              <button onClick={() => setAsks((x) => x.filter((_, k) => k !== i))} title="Takes this change out of the question" aria-label="Remove this change">x</button>
            </div>
          ))}
          <button onClick={() => setAsks((a) => [...a, { at: '', target: '', value: '' }])}>Add a change</button>

          {scenario.policies.length > 0 && (
            <label className="wb-param">
              the electrolyser
              <select value={policy} onChange={(e) => setPolicy(e.target.value)}>
                {scenario.policies.map((p) => <option key={p.policy} value={p.policy}>{p.label}</option>)}
              </select>
            </label>
          )}
          <label className="wb-param" title="With a time, the run continues the reference from there as a forecast; without, the whole night is computed again">
            as a forecast from<input type="text" value={from} placeholder="whole night" onChange={(e) => setFrom(e.target.value)} />
          </label>
          <div className="wb-ask-actions">
            <button className="wb-primary" disabled={busy} onClick={ask}>{busy ? 'Running' : 'Run'}</button>
            <button disabled={busy} onClick={() => show(scenario.reference)}>Show the reference</button>
          </div>
          {error && <div className="wb-error" role="alert">{error}</div>}

          <h3>Runs kept ({runs.length})</h3>
          <ul className="wb-runs" aria-label="Runs kept by the server">
            {runs.map((r) => (
              <li key={r.run} className={r.run === run?.run ? 'here' : ''}>
                <button onClick={() => show(r.run)} title={r.run}>
                  <span className={`wb-flow-status ${r.kind === 'reference' ? 'clean' : 'raw-only'}`}>{r.kind}</span> {r.label}
                </button>
              </li>
            ))}
          </ul>
          <p className="wb-muted">The server keeps the last hundred runs in memory and forgets them when it restarts.</p>
        </section>

        <section className="wb-answer" aria-label="The answer">
          {run && compare && (
            <>
              <h3>
                {run.label} <span className="wb-muted">{run.run}</span>
              </h3>
              <div className={`wb-run-status ${run.status.usability}`} role="note">
                <span className={`wb-flow-status ${run.status.usability}`}>{run.status.usability}</span>
                {run.status.reasons.join('. ')}.
              </div>

              <h4>{isReference ? 'What happens' : 'Against the reference'}</h4>
              <table className="wb-table wb-kpis">
                <thead>
                  <tr><th>figure</th><th>reference</th>{!isReference && <><th>this run</th><th>difference</th></>}</tr>
                </thead>
                <tbody>
                  {compare.kpis.map((k) => (
                    <tr key={k.key}>
                      <td className="l" title={k.key}>{k.label}</td>
                      <td>{kpiText(k.base, k.decimals, k.unit)}</td>
                      {!isReference && (
                        <>
                          <td>{kpiText(k.run, k.decimals, k.unit)}</td>
                          <td className={k.verdict}>{deltaText(k)}</td>
                        </>
                      )}
                    </tr>
                  ))}
                </tbody>
              </table>
              {!isReference && compare.endStocks.length > 0 && (
                <p className="wb-muted">
                  Left at the end: {compare.endStocks.map((s) => `${s.key.replace('.state', '')} ${s.run.toFixed(1)} (reference ${s.base.toFixed(1)})`).join(', ')}. A run must not
                  look better because it emptied a store.
                </p>
              )}

              <div className="wb-run-charts">
                {Object.entries(compare.series).map(([key, s]) => (
                  <Chart key={key} title={s.label} base={s.base} run={s.run} limits={s.limits} steps={scenario.steps} />
                ))}
              </div>
              <p className="wb-legend"><i className="base" /> reference <i className="run" /> this run</p>

              <h4>What happens, in order</h4>
              <ol className="wb-events">
                {run.events.map((e, i) => (
                  <li key={i}>
                    <b>{e.clock}</b> <span className="wb-muted">{e.atom}</span> {eventText(e)}
                  </li>
                ))}
              </ol>

              <details className="wb-run-source">
                <summary>Where the numbers come from</summary>
                <p>
                  Run <code>{run.run}</code>, hash <code>{run.hash}</code>{run.baseline ? <>, compared with <code>{run.baseline}</code></> : null}.
                  {run.kind === 'forecast' ? ` A forecast from step ${run.firstStep}: the minutes before are those of the reference.` : ''}
                </p>
                <table className="wb-table">
                  <thead><tr><th>input</th><th>comes from</th><th>origin</th><th>covers</th></tr></thead>
                  <tbody>
                    {run.inputs.map((i) => (
                      <tr key={i.port}>
                        <td className="l">{i.port}</td>
                        <td className="l">{i.source}</td>
                        <td className="l">{(i.origin ?? '').toLowerCase()}</td>
                        <td>{i.coverage === undefined ? '' : `${Math.round(100 * i.coverage)} %`}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </details>
            </>
          )}
        </section>
      </div>
    </main>
  );
}
