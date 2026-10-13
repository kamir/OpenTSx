/**
 * Binding and simulating a signal (stage L4): choose the series that carry it, after confirming that they mean the
 * same, or bind it to a demo generator preset from its expectation and run that over a period. What is set here
 * goes into the working copy of the user, not into the system document.
 */

import { useEffect, useMemo, useState } from 'react';
import axios from 'axios';
import { systemsAPI } from '../services/api';
import { clock } from '../workbench/inventory';
import { Candidate, GENERATORS, Signal, SystemStatus, dataExtent, expectText, guessOrigin, presetGenerator, suggestSeries, topicSource } from './signals';

const ORIGINS = ['MEASURED', 'REPORTED', 'FORECAST', 'ESTIMATED', 'SYNTHETIC'];

interface Props {
  mode: 'bind' | 'simulate' | 'generate';
  status: SystemStatus;
  signal: Signal;
  /** Retrieval server the inventory is read from. */
  base: string;
  /** Called after the working copy or the data changed. */
  onChanged: (message: string) => void;
  onClose: () => void;
}

function reasonOf(e: unknown): string {
  if (axios.isAxiosError(e)) {
    if (e.response?.status === 401) return 'your session has expired, sign in again';
    const detail = e.response?.data?.detail;
    if (typeof detail === 'string') return detail;
  }
  return e instanceof Error ? e.message : String(e);
}

export default function SourcePanel({ mode, status, signal, base, onChanged, onClose }: Props) {
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const signedIn = !!localStorage.getItem('access_token');

  // ---- bind
  const [inventory, setInventory] = useState<{ topic: string; series: Candidate[] } | null>(null);
  const [text, setText] = useState('');
  const [chosen, setChosen] = useState<string | null>(null);
  const [origin, setOrigin] = useState('');
  const [confirmed, setConfirmed] = useState(false);
  useEffect(() => {
    if (mode !== 'bind') return;
    let cancelled = false;
    fetch(`${base}/v1/inventory`)
      .then(async (res) => {
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        return res.json();
      })
      .then((doc) => !cancelled && setInventory({ topic: doc.source?.topics?.episodes ?? '', series: doc.series ?? [] }))
      .catch((e) => !cancelled && setError(`The TS inventory could not be read: ${reasonOf(e)}.`));
    return () => {
      cancelled = true;
    };
  }, [mode, base]);
  const candidates = useMemo(() => suggestSeries(signal, inventory?.series ?? [], text), [signal, inventory, text]);
  const series = candidates.find((c) => c.seriesId === chosen) ?? null;
  useEffect(() => {
    setConfirmed(false);
    if (series) setOrigin(guessOrigin(series));
  }, [series]);

  // ---- simulate
  const [processor, setProcessor] = useState<string>(signal.grades.raw?.source.processor ?? 'gen.ou');
  const [params, setParams] = useState<Record<string, number | string>>(() => presetGenerator('gen.ou', signal.expect));
  const [seed, setSeed] = useState(signal.grades.raw?.source.seed ?? 1);
  useEffect(() => setParams(presetGenerator(processor, signal.expect)), [processor, signal]);

  // ---- generate
  const extent = dataExtent(status);
  const [from, setFrom] = useState(extent?.from ?? '');
  const [to, setTo] = useState(extent?.to ?? '');

  const run = async (what: () => Promise<string>) => {
    setBusy(true);
    setError(null);
    try {
      onChanged(await what());
    } catch (e) {
      setError(`${reasonOf(e)}.`);
      setBusy(false);
    }
  };

  const bind = () =>
    run(async () => {
      await systemsAPI.bind(status.system, {
        signal: signal.id,
        grade: 'raw',
        source: topicSource(inventory!.topic, series!, origin),
        meaningConfirmed: confirmed,
        baseHash: status.hash,
      });
      return `${signal.id} is bound to ${series!.metric} in your working copy.`;
    });

  const simulate = () =>
    run(async () => {
      await systemsAPI.bind(status.system, {
        signal: signal.id,
        grade: 'raw',
        source: { kind: 'generator', processor, seed, params },
        baseHash: status.hash,
      });
      return `${signal.id} is bound to the demo generator ${processor} in your working copy. Generate it to get data.`;
    });

  const generate = () =>
    run(async () => {
      const answer = await systemsAPI.generate(status.system, signal.id, new Date(from).toISOString(), new Date(to).toISOString());
      return `${answer.samples.toLocaleString('en')} values generated for ${signal.id} and written to ${answer.topic}. Press Reload if they do not show yet.`;
    });

  if (!signedIn) {
    return (
      <div className="wb-flow-run-box">
        <p>A binding is stored in your account. Sign in to bind or simulate a signal.</p>
        <button onClick={onClose}>Close</button>
      </div>
    );
  }

  return (
    <div className="wb-flow-run-box wb-source-panel" role="group" aria-label={mode === 'bind' ? 'Bind the signal' : mode === 'simulate' ? 'Simulate the signal' : 'Generate the signal'}>
      {mode === 'bind' && (
        <>
          <p>
            Choose the series that carries <b>{signal.id}</b>: {signal.meaning ?? signal.title}. Expected {expectText(signal.expect)}.
          </p>
          <input type="search" placeholder="Filter: metric, tag, unit" aria-label="Filter the series" value={text} onChange={(e) => setText(e.target.value)} />
          <div className="wb-source-list">
            {inventory === null && !error && <p className="wb-muted">Reading the TS inventory</p>}
            {candidates.slice(0, 60).map((c) => (
              <label key={c.seriesId} className={c.seriesId === chosen ? 'picked' : ''}>
                <input type="radio" name="series" checked={c.seriesId === chosen} onChange={() => setChosen(c.seriesId)} />
                <span>
                  <b>{c.metric}</b> {Object.entries(c.tags).map(([k, v]) => `${k}=${v}`).join(' ')}
                  <br />
                  <span className="wb-muted">
                    {c.unit ?? 'no unit'}
                    {c.min !== undefined ? `, ${Number(c.min.toPrecision(4))}..${Number(c.max!.toPrecision(4))}` : ''}, {c.samples.toLocaleString('en')} values
                    {c.from ? `, ${clock(c.from, true)} to ${clock(c.to, true)}` : ''}
                    {signal.expect.unit && c.unit !== signal.expect.unit ? ', another unit than expected' : ''}
                  </span>
                </span>
              </label>
            ))}
            {inventory && candidates.length === 0 && <p className="wb-muted">No series matches.</p>}
          </div>
          {series && (
            <>
              <label className="wb-param">
                origin of this data
                <select value={origin} onChange={(e) => setOrigin(e.target.value)}>
                  <option value="">choose</option>
                  {ORIGINS.map((o) => <option key={o} value={o}>{o.toLowerCase()}</option>)}
                </select>
              </label>
              <label className="wb-check">
                <input type="checkbox" checked={confirmed} onChange={(e) => setConfirmed(e.target.checked)} />
                I looked at this series, and it is the quantity the signal means. The same name or unit would not be enough.
              </label>
            </>
          )}
          <div className="wb-oprow">
            <button className="wb-primary" disabled={!series || !origin || !confirmed || busy} onClick={bind}>Bind in my working copy</button>
            <button disabled={busy} onClick={onClose}>Cancel</button>
          </div>
        </>
      )}

      {mode === 'simulate' && (
        <>
          <p>
            A <b>demo generator</b> gives plausible single values for {signal.id}. It knows nothing of the rest of the system: no
            coupling to wind, price or demand, no balance. Use it to try things out; results that rest on it do not
            compare strategies.
          </p>
          <label className="wb-param">
            generator
            <select value={processor} onChange={(e) => setProcessor(e.target.value)}>
              {GENERATORS.map((g) => <option key={g} value={g}>{g === 'gen.ou' ? 'gen.ou: wanders around a mean' : 'gen.sine: a cycle with noise'}</option>)}
            </select>
          </label>
          <div className="wb-oprow">
            {Object.entries(params).map(([k, v]) => (
              <label key={k} className="wb-param">
                {k}
                <input
                  type="text"
                  value={v}
                  onChange={(e) => setParams({ ...params, [k]: typeof v === 'number' && e.target.value.trim() !== '' && !Number.isNaN(Number(e.target.value)) ? Number(e.target.value) : e.target.value })}
                />
              </label>
            ))}
            <label className="wb-param">seed<input type="number" value={seed} onChange={(e) => setSeed(Math.round(Number(e.target.value)))} /></label>
          </div>
          <p className="wb-muted">
            Preset from the expectation ({expectText(signal.expect)}). Values are clipped to the plausible range. The seed makes a
            run repeatable; it does not make it realistic.
          </p>
          <div className="wb-oprow">
            <button className="wb-primary" disabled={busy || !signal.expect.interval} onClick={simulate}>Bind to this generator</button>
            <button disabled={busy} onClick={onClose}>Cancel</button>
          </div>
          {!signal.expect.interval && <p className="warn">The signal has no sampling interval; a generator needs one.</p>}
        </>
      )}

      {mode === 'generate' && (
        <>
          <p>
            Runs the demo generator of {signal.id} over a period and writes the series. Generated again over the same period, it
            replaces itself.
          </p>
          <div className="wb-oprow">
            <label className="wb-param">from<input type="text" className="ts" value={from} onChange={(e) => setFrom(e.target.value)} /></label>
            <label className="wb-param">to<input type="text" className="ts" value={to} onChange={(e) => setTo(e.target.value)} /></label>
          </div>
          <p className="wb-muted">{extent ? 'Preset to the period the system has data for.' : 'The system has no data yet; name a period like 2026-10-01T00:00:00Z.'} Administrators only: it writes to the broker.</p>
          <div className="wb-oprow">
            <button className="wb-primary" disabled={busy || Number.isNaN(Date.parse(from)) || Number.isNaN(Date.parse(to)) || Date.parse(from) >= Date.parse(to)} onClick={generate}>
              {busy ? 'Generating' : 'Generate'}
            </button>
            <button disabled={busy} onClick={onClose}>Cancel</button>
          </div>
        </>
      )}
      {error && <div className="wb-error" role="alert">{error}</div>}
    </div>
  );
}
