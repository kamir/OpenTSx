/**
 * TS inventory dialog: browse the series of a retrieval server by the metadata of their scenario (tags, channel
 * catalog, ground-truth events), pick series and a time window, and load them as the bucket of the workbench.
 */

import { useEffect, useMemo, useRef, useState } from 'react';
import {
  AGGREGATIONS,
  Inventory as InventoryDoc,
  InventoryFilter,
  InventorySeries,
  LoadOptions,
  MANY_POINTS,
  NO_FILTER,
  RESAMPLE_STEPS,
  ScenarioEvent,
  clock,
  estimatePoints,
  filterSeries,
  metricFacets,
  scenarioEvents,
  tagFacets,
  windowError,
} from './inventory';
import { InventoryLoad, fetchBucket, fetchInventory } from './inventoryLoad';
import { fmt } from './ops';

export type { InventoryLoad };

interface Props {
  base: string;
  onBase: (base: string) => void;
  onLoad: (load: InventoryLoad) => void;
  onClose: () => void;
}

function text(v: unknown): string {
  return typeof v === 'string' || typeof v === 'number' ? String(v) : '';
}

/** One line about the scenario file, from the fields the wind-farm scenarios carry. */
function scenarioLine(scenario: Record<string, unknown> | undefined): string {
  if (!scenario) return '';
  const park = (scenario.park ?? {}) as Record<string, unknown>;
  const parts = [
    text(scenario.name),
    text(scenario.seed) && `seed ${text(scenario.seed)}`,
    text(park.name),
    text(park.rows) && text(park.columns) && `${text(park.rows)} x ${text(park.columns)} ${text(park.turbineModel)}`.trim(),
    text(scenario.sampleInterval) && `sampled ${text(scenario.sampleInterval)}`,
    text(scenario.episodeDuration) && `episodes ${text(scenario.episodeDuration)}`,
  ];
  return parts.filter(Boolean).join(', ');
}

function Timeline({ series, options, onPick }: { series: InventorySeries; options: LoadOptions; onPick: (from: string, to: string) => void }) {
  const t0 = Date.parse(series.from ?? '');
  const t1 = Date.parse(series.to ?? '');
  if (!(t1 > t0)) return null;
  const pct = (iso: string) => Math.max(0, Math.min(100, ((Date.parse(iso) - t0) / (t1 - t0)) * 100));
  const w0 = pct(options.from);
  const w1 = pct(options.to);
  return (
    <div className="wb-inv-timeline" aria-label="Time range of the series with its ground-truth marks">
      <div className="wb-inv-track">
        {!Number.isNaN(w0) && !Number.isNaN(w1) && w1 > w0 && <span className="window" style={{ left: `${w0}%`, width: `${w1 - w0}%` }} />}
        {series.marks.map((m, i) => (
          <button
            key={i}
            className={`mark ${m.kind}`}
            style={{ left: `${pct(m.from)}%`, width: `${Math.max(1, pct(m.to) - pct(m.from))}%` }}
            title={`${m.type} ${clock(m.from)} to ${clock(m.to)}: use as window`}
            onClick={() => onPick(m.from, m.to)}
          />
        ))}
      </div>
      <div className="wb-inv-axis"><span>{clock(series.from, true)}</span><span>{clock(series.to, true)} UTC</span></div>
    </div>
  );
}

export default function InventoryDialog({ base, onBase, onLoad, onClose }: Props) {
  const [inv, setInv] = useState<InventoryDoc | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState<string | null>('Reading the inventory');
  const [filter, setFilter] = useState<InventoryFilter>(NO_FILTER);
  const [picked, setPicked] = useState<Set<string>>(new Set());
  const [focus, setFocus] = useState<string | null>(null);
  const [options, setOptions] = useState<LoadOptions>({ from: '', to: '', resample: '', agg: 'MEAN' });
  const [reload, setReload] = useState(0);
  const dialog = useRef<HTMLDivElement>(null);
  const root = base.replace(/\/$/, '');

  useEffect(() => {
    let cancelled = false;
    setBusy('Reading the inventory');
    setError(null);
    fetchInventory(root)
      .then((doc) => {
        if (cancelled) return;
        setInv(doc);
        setPicked(new Set());
        setFocus(null);
        setFilter(NO_FILTER);
        setOptions((o) => ({ ...o, from: doc.totals.from ?? '', to: doc.totals.to ?? '' }));
      })
      .catch((e) => {
        if (cancelled) return;
        setInv(null);
        setError(`The inventory could not be read from ${root}/v1/inventory: ${e instanceof Error ? e.message : e}.`);
      })
      .finally(() => !cancelled && setBusy(null));
    return () => {
      cancelled = true;
    };
  }, [root, reload]);

  useEffect(() => {
    dialog.current?.focus();
  }, []);

  const events = useMemo(() => (inv ? scenarioEvents(inv) : []), [inv]);
  const tags = useMemo(() => (inv ? tagFacets(inv) : []), [inv]);
  const metrics = useMemo(() => (inv ? metricFacets(inv) : []), [inv]);
  const rows = useMemo(() => (inv ? filterSeries(inv, filter, events) : []), [inv, filter, events]);
  const selection = useMemo(() => (inv ? inv.series.filter((s) => picked.has(s.seriesId)) : []), [inv, picked]);
  const focused = inv?.series.find((s) => s.seriesId === focus) ?? selection[selection.length - 1] ?? null;
  const channel = inv?.channels.find((c) => c.metric === focused?.metric);
  const tagKeys = tags.map((t) => t.key);
  const points = estimatePoints(selection, options);
  const badWindow = inv && selection.length > 0 ? windowError(options) : null;
  const allShown = rows.length > 0 && rows.every((s) => picked.has(s.seriesId));
  const filtered = filter.text !== '' || filter.metric !== null || filter.eventId !== null || Object.keys(filter.tags).length > 0;

  const toggle = (id: string) => {
    setFocus(id);
    setPicked((p) => {
      const next = new Set(p);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  };
  const toggleAll = () =>
    setPicked((p) => {
      const next = new Set(p);
      for (const s of rows) {
        if (allShown) next.delete(s.seriesId);
        else next.add(s.seriesId);
      }
      return next;
    });
  const pickEvent = (e: ScenarioEvent) => {
    const active = filter.eventId === e.id;
    setFilter({ ...filter, eventId: active ? null : e.id });
    if (!active) setOptions((o) => ({ ...o, from: e.from, to: e.to }));
    else if (inv) setOptions((o) => ({ ...o, from: inv.totals.from ?? o.from, to: inv.totals.to ?? o.to }));
  };

  const load = async () => {
    if (!inv || selection.length === 0 || badWindow) return;
    setBusy(`Loading ${selection.length} series`);
    setError(null);
    try {
      onLoad(await fetchBucket(root, inv, selection, options));
    } catch (e) {
      setError(`The series could not be loaded: ${e instanceof Error ? e.message : e}. The window may hold no data.`);
      setBusy(null);
    }
  };

  return (
    <div className="wb-inv-backdrop" onMouseDown={(e) => e.target === e.currentTarget && onClose()}>
      <div
        className="wb-inv"
        role="dialog"
        aria-modal="true"
        aria-label="TS inventory"
        tabIndex={-1}
        ref={dialog}
        onKeyDown={(e) => e.key === 'Escape' && onClose()}
      >
        <header className="wb-inv-head">
          <h2>TS Inventory</h2>
          <label className="wb-param">
            retrieval server
            <input type="text" value={base} onChange={(e) => onBase(e.target.value)} />
          </label>
          <button onClick={() => setReload((n) => n + 1)}>Reload</button>
          <span className="wb-grow" />
          <button onClick={onClose}>Close</button>
        </header>

        {inv && (
          <div className="wb-inv-source">
            <span>
              <b>{inv.source.topics.episodes ?? `${inv.source.kind} store`}</b>
              {inv.source.domain ? `, domain ${inv.source.domain}` : ''}: {inv.totals.series} series, {inv.totals.episodes} episodes,{' '}
              {inv.totals.samples.toLocaleString('en')} samples
              {inv.totals.from ? `, ${clock(inv.totals.from, true)} to ${clock(inv.totals.to, true)} UTC` : ''}
            </span>
            {(scenarioLine(inv.scenario) || inv.totals.scenarios.length > 0) && (
              <span>Scenario: {scenarioLine(inv.scenario) || inv.totals.scenarios.join(', ')}</span>
            )}
            {(inv.source.vectorTopics ?? []).length > 0 && (
              <span>Smart Topics (vectors): {(inv.source.vectorTopics ?? []).join(', ')}. Their components are the series with the tag "vector".</span>
            )}
            {(inv.source.unreadableVectorTopics ?? []).length > 0 && (
              <span className="warn">
                The broker does not hand out {(inv.source.unreadableVectorTopics ?? []).join(', ')} at the moment. Its series may be
                out of date; the server tries again every few minutes.
              </span>
            )}
            {inv.source.truncated && <span className="warn">The server holds only a part of the topic (its episode limit was reached).</span>}
          </div>
        )}
        {error && <div className="wb-error" role="alert">{error}</div>}

        <div className="wb-inv-body">
          <nav className="wb-inv-facets" aria-label="Filters">
            {inv && inv.totals.series === 0 && <p className="wb-muted pad">The source holds no series yet.</p>}
            {tags.map((t) => (
              <section key={t.key}>
                <h3>{t.key}</h3>
                <div className="wb-inv-chips">
                  {t.values.map((v) => (
                    <button
                      key={v}
                      aria-pressed={filter.tags[t.key] === v}
                      onClick={() => {
                        const next = { ...filter.tags };
                        if (next[t.key] === v) delete next[t.key];
                        else next[t.key] = v;
                        setFilter({ ...filter, tags: next });
                      }}
                    >
                      {v}
                    </button>
                  ))}
                </div>
              </section>
            ))}
            {metrics.length > 0 && (
              <section>
                <h3>Channel</h3>
                <ul>
                  {metrics.map((m) => (
                    <li key={m.metric}>
                      <button
                        aria-pressed={filter.metric === m.metric}
                        title={m.description}
                        onClick={() => setFilter({ ...filter, metric: filter.metric === m.metric ? null : m.metric })}
                      >
                        <span className="grp">{m.group}</span>
                        <span className="name">{m.metric.split('.').pop()}</span>
                        <span className="unit">{m.unit}</span>
                      </button>
                    </li>
                  ))}
                </ul>
              </section>
            )}
            {events.length > 0 && (
              <section>
                <h3>Scenario events (ground truth)</h3>
                <ul>
                  {events.map((e) => (
                    <li key={e.id}>
                      <button
                        aria-pressed={filter.eventId === e.id}
                        title="Shows the affected series and sets the window to the event"
                        onClick={() => pickEvent(e)}
                      >
                        <span className={`dot ${e.kind}`} />
                        <span className="name">{e.type}</span>
                        <span className="unit">{clock(e.from)} to {clock(e.to)}</span>
                        <span className="scope">{e.scope}</span>
                      </button>
                    </li>
                  ))}
                </ul>
              </section>
            )}
          </nav>

          <section className="wb-inv-list" aria-label="Series">
            <div className="wb-inv-search">
              <input
                type="search"
                placeholder="Filter: metric, tag, unit, event type"
                aria-label="Filter the series"
                value={filter.text}
                onChange={(e) => setFilter({ ...filter, text: e.target.value })}
              />
              <span className="wb-muted">{rows.length} of {inv?.totals.series ?? 0} shown, {picked.size} selected</span>
              <button disabled={!filtered} onClick={() => setFilter(NO_FILTER)}>Clear filters</button>
              <button disabled={picked.size === 0} onClick={() => setPicked(new Set())}>Clear selection</button>
            </div>
            <div className="wb-inv-scroll">
              <table className="wb-table wb-inv-table">
                <thead>
                  <tr>
                    <th><input type="checkbox" aria-label="Select all shown series" checked={allShown} onChange={toggleAll} /></th>
                    <th>metric</th>
                    {tagKeys.map((k) => <th key={k}>{k}</th>)}
                    <th>unit</th>
                    <th>samples</th>
                    <th>min</th>
                    <th>mean</th>
                    <th>max</th>
                    <th>missing</th>
                    <th>events</th>
                  </tr>
                </thead>
                <tbody>
                  {rows.map((s) => (
                    <tr
                      key={s.seriesId}
                      className={`${picked.has(s.seriesId) ? 'picked' : ''} ${focused?.seriesId === s.seriesId ? 'focused' : ''}`}
                      onClick={() => toggle(s.seriesId)}
                    >
                      <td>
                        <input
                          type="checkbox"
                          aria-label={`${s.metric} ${Object.values(s.tags).join(' ')}`}
                          checked={picked.has(s.seriesId)}
                          onChange={() => toggle(s.seriesId)}
                          onClick={(e) => e.stopPropagation()}
                        />
                      </td>
                      <td className="l">{s.metric}</td>
                      {tagKeys.map((k) => <td key={k} className="l">{s.tags[k] ?? ''}</td>)}
                      <td className="l">{s.unit ?? ''}</td>
                      <td>{s.samples.toLocaleString('en')}</td>
                      <td>{s.min === undefined ? '' : fmt(s.min)}</td>
                      <td>{s.mean === undefined ? '' : fmt(s.mean)}</td>
                      <td>{s.max === undefined ? '' : fmt(s.max)}</td>
                      <td>{s.missing > 0 ? s.missing.toLocaleString('en') : ''}</td>
                      <td className="l">
                        {[...new Set(s.marks.map((m) => `${m.kind}|${m.type}`))].map((m) => (
                          <span key={m} className={`wb-inv-badge ${m.split('|')[0]}`}>{m.split('|')[1]}</span>
                        ))}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
              {inv && rows.length === 0 && inv.totals.series > 0 && <p className="wb-muted pad">No series matches the filters.</p>}
            </div>
          </section>

          <aside className="wb-inv-detail" aria-label="Series details">
            {focused ? (
              <>
                <h3>{focused.metric}</h3>
                {channel?.description && <p>{channel.description}</p>}
                <dl className="wb-readout wide">
                  {Object.entries(focused.tags).map(([k, v]) => [<dt key={`k${k}`}>{k}</dt>, <dd key={`v${k}`}>{v}</dd>])}
                  <dt>Series id</dt><dd>{focused.seriesId}</dd>
                  {channel?.component && [<dt key="ck">Component</dt>, <dd key="cv">{channel.component}</dd>]}
                  {Object.entries(focused.labels).map(([k, v]) => [<dt key={`k${k}`}>{k}</dt>, <dd key={`v${k}`}>{v.join(', ')}</dd>])}
                  <dt>Episodes</dt><dd>{focused.episodes}{focused.segments ? ` (+${focused.segments} segments)` : ''}</dd>
                  {focused.intervalMicros && [<dt key="ik">Interval</dt>, <dd key="iv">{focused.intervalMicros / 1e6} s</dd>]}
                  {channel?.min !== undefined && channel?.max !== undefined && [
                    <dt key="pk">Plausible</dt>,
                    <dd key="pv">{channel.min} to {channel.max} {channel.unit ?? ''}</dd>,
                  ]}
                </dl>
                <Timeline series={focused} options={options} onPick={(from, to) => setOptions({ ...options, from, to })} />
                {focused.marks.length > 0 && (
                  <ul className="wb-inv-marks">
                    {focused.marks.map((m, i) => (
                      <li key={i}>
                        <span className={`wb-inv-badge ${m.kind}`}>{m.type}</span> {clock(m.from)} to {clock(m.to)}
                        <button onClick={() => setOptions({ ...options, from: m.from, to: m.to })}>Use as window</button>
                      </li>
                    ))}
                  </ul>
                )}
              </>
            ) : (
              <p className="wb-muted pad">Select a series to see its channel description, labels and ground-truth marks.</p>
            )}
          </aside>
        </div>

        <footer className="wb-inv-foot">
          <label className="wb-param">from<input type="text" className="ts" value={options.from} onChange={(e) => setOptions({ ...options, from: e.target.value })} /></label>
          <label className="wb-param">to<input type="text" className="ts" value={options.to} onChange={(e) => setOptions({ ...options, to: e.target.value })} /></label>
          <button
            disabled={!inv?.totals.from}
            onClick={() => inv && setOptions({ ...options, from: inv.totals.from ?? '', to: inv.totals.to ?? '' })}
          >
            Whole range
          </button>
          <label className="wb-param">
            resolution
            <select value={options.resample} onChange={(e) => setOptions({ ...options, resample: e.target.value })}>
              {RESAMPLE_STEPS.map((r) => <option key={r} value={r}>{r || 'as stored'}</option>)}
            </select>
          </label>
          {options.resample && (
            <label className="wb-param">
              aggregate
              <select value={options.agg} onChange={(e) => setOptions({ ...options, agg: e.target.value })}>
                {AGGREGATIONS.map((a) => <option key={a}>{a}</option>)}
              </select>
            </label>
          )}
          <span className="wb-grow" />
          <span className={`wb-muted ${points > MANY_POINTS ? 'warn' : ''}`} role="status">
            {busy ??
              badWindow ??
              (selection.length === 0
                ? 'Select one or more series.'
                : `About ${points.toLocaleString('en')} points${points > MANY_POINTS ? ': choose a coarser resolution or a shorter window' : ''}`)}
          </span>
          <button className="wb-primary" disabled={!inv || selection.length === 0 || !!badWindow || !!busy} onClick={load}>
            {selection.length > 1 ? `Load ${selection.length} series` : 'Load series'}
          </button>
        </footer>
      </div>
    </div>
  );
}
