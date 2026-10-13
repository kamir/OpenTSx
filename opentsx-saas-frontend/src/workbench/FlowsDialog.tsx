/**
 * "Open flow" dialog: the flows stored in the account, with what each one reads, computes and publishes. One
 * selected flow opens on the workbench; further ones open in tabs of their own.
 */

import { useEffect, useMemo, useRef, useState } from 'react';
import { DagList, DagState } from './deployments';
import { FlowFilter, FlowSummary, NO_FLOW_FILTER, StoredFlow, domainFacets, filterFlows, stamp, summarize } from './flows';

interface Props {
  /** Retrieval server, asked for the state of deployed flows. */
  base: string;
  /** Flow on the workbench, if it is stored. */
  currentId: number | null;
  /** True when the workbench holds work that opening replaces. */
  replaces: boolean;
  list: () => Promise<StoredFlow[]>;
  onOpen: (ids: number[]) => void;
  onClose: () => void;
}

function deployment(d: DagState | undefined): string {
  if (!d) return '';
  if (d.desired !== 'deployed') return 'stopped';
  return d.sinks.length > 0 && d.sinks.every((s) => s.state === 'running') ? 'running' : d.sinks.some((s) => s.state === 'failed') ? 'failed' : 'deployed';
}

export default function FlowsDialog({ base, currentId, replaces, list, onOpen, onClose }: Props) {
  const [flows, setFlows] = useState<FlowSummary[] | null>(null);
  const [dags, setDags] = useState<Map<string, DagState>>(new Map());
  const [error, setError] = useState<string | null>(null);
  const [filter, setFilter] = useState<FlowFilter>(NO_FLOW_FILTER);
  const [picked, setPicked] = useState<number[]>([]);
  const [focus, setFocus] = useState<number | null>(null);
  const [reload, setReload] = useState(0);
  const dialog = useRef<HTMLDivElement>(null);
  const root = base.replace(/\/$/, '');

  useEffect(() => {
    let cancelled = false;
    setError(null);
    list()
      .then((all) => !cancelled && setFlows(all.map(summarize)))
      .catch((e) => {
        if (cancelled) return;
        setFlows(null);
        setError(`The flows of your account could not be read: ${e instanceof Error ? e.message : e}. Sign in again if your session has expired.`);
      });
    // The deployment column is an extra: without a retrieval server it stays empty.
    fetch(`${root}/v1/dags`)
      .then((res) => (res.ok ? (res.json() as Promise<DagList>) : null))
      .then((doc) => !cancelled && doc && setDags(new Map(doc.dags.map((d) => [d.dag, d]))))
      .catch(() => undefined);
    return () => {
      cancelled = true;
    };
  }, [list, root, reload]);

  useEffect(() => {
    dialog.current?.focus();
  }, []);

  const domains = useMemo(() => domainFacets(flows ?? []), [flows]);
  const rows = useMemo(() => filterFlows(flows ?? [], filter), [flows, filter]);
  const focused = flows?.find((f) => f.id === focus) ?? null;
  const filtered = filter.text !== '' || filter.domain !== null || filter.only !== null;
  const openable = rows.filter((f) => f.openable);
  const allShown = openable.length > 0 && openable.every((f) => picked.includes(f.id));

  const toggle = (f: FlowSummary) => {
    setFocus(f.id);
    if (!f.openable) return;
    setPicked((p) => (p.includes(f.id) ? p.filter((i) => i !== f.id) : [...p, f.id]));
  };
  const toggleAll = () =>
    setPicked((p) => (allShown ? p.filter((i) => !openable.some((f) => f.id === i)) : [...new Set([...p, ...openable.map((f) => f.id)])]));
  const first = flows?.find((f) => f.id === picked[0]);

  return (
    <div className="wb-inv-backdrop" onMouseDown={(e) => e.target === e.currentTarget && onClose()}>
      <div
        className="wb-inv"
        role="dialog"
        aria-modal="true"
        aria-label="Open flow"
        tabIndex={-1}
        ref={dialog}
        onKeyDown={(e) => e.key === 'Escape' && onClose()}
      >
        <header className="wb-inv-head">
          <h2>Open flow</h2>
          <button onClick={() => setReload((n) => n + 1)}>Reload</button>
          <span className="wb-grow" />
          <button onClick={onClose}>Close</button>
        </header>

        <div className="wb-inv-source">
          <span>
            {flows === null && !error
              ? 'Reading the flows of your account'
              : `${flows?.length ?? 0} flows in your account, ${flows?.filter((f) => f.openable).length ?? 0} of them made in the workbench.`}
          </span>
          <span>A flow keeps its operators, its published topics and the series it was built on. Opening it loads those series again.</span>
        </div>
        {error && <div className="wb-error" role="alert">{error}</div>}

        <div className="wb-inv-body">
          <nav className="wb-inv-facets" aria-label="Filters">
            {domains.length > 0 && (
              <section>
                <h3>domain</h3>
                <div className="wb-inv-chips">
                  {domains.map((d) => (
                    <button key={d} aria-pressed={filter.domain === d} onClick={() => setFilter({ ...filter, domain: filter.domain === d ? null : d })}>
                      {d}
                    </button>
                  ))}
                </div>
              </section>
            )}
            <section>
              <h3>show only</h3>
              <ul>
                <li>
                  <button aria-pressed={filter.only === 'published'} onClick={() => setFilter({ ...filter, only: filter.only === 'published' ? null : 'published' })}>
                    <span className="name">Flows with a Smart Topic</span>
                  </button>
                </li>
                <li>
                  <button aria-pressed={filter.only === 'data'} onClick={() => setFilter({ ...filter, only: filter.only === 'data' ? null : 'data' })}>
                    <span className="name">Flows built on inventory series</span>
                  </button>
                </li>
              </ul>
            </section>
          </nav>

          <section className="wb-inv-list" aria-label="Flows">
            <div className="wb-inv-search">
              <input
                type="search"
                placeholder="Filter: name, track, operator, topic"
                aria-label="Filter the flows"
                value={filter.text}
                onChange={(e) => setFilter({ ...filter, text: e.target.value })}
              />
              <span className="wb-muted">{rows.length} of {flows?.length ?? 0} shown, {picked.length} selected</span>
              <button disabled={!filtered} onClick={() => setFilter(NO_FLOW_FILTER)}>Clear filters</button>
              <button disabled={picked.length === 0} onClick={() => setPicked([])}>Clear selection</button>
            </div>
            <div className="wb-inv-scroll">
              <table className="wb-table wb-inv-table">
                <thead>
                  <tr>
                    <th><input type="checkbox" aria-label="Select all shown flows" checked={allShown} onChange={toggleAll} /></th>
                    <th>id</th>
                    <th>name</th>
                    <th>track</th>
                    <th>domain</th>
                    <th>buckets</th>
                    <th>series</th>
                    <th>Smart Topics</th>
                    <th>deployment</th>
                    <th>changed (UTC)</th>
                  </tr>
                </thead>
                <tbody>
                  {rows.map((f) => (
                    <tr
                      key={f.id}
                      className={`${picked.includes(f.id) ? 'picked' : ''} ${focus === f.id ? 'focused' : ''} ${f.openable ? '' : 'off'}`}
                      onClick={() => toggle(f)}
                      onDoubleClick={() => f.openable && onOpen([f.id])}
                    >
                      <td>
                        <input
                          type="checkbox"
                          aria-label={`Flow ${f.id} ${f.name}`}
                          disabled={!f.openable}
                          checked={picked.includes(f.id)}
                          onChange={() => toggle(f)}
                          onClick={(e) => e.stopPropagation()}
                        />
                      </td>
                      <td>{f.id}</td>
                      <td className="l">{f.name}{f.id === currentId ? ' (on the workbench)' : ''}</td>
                      <td className="l">{f.track}</td>
                      <td className="l">{f.domain}</td>
                      <td>{f.openable ? f.steps.length : ''}</td>
                      <td>{f.sourceSeries.length || ''}</td>
                      <td>{f.published.length || ''}</td>
                      <td className="l">{deployment(dags.get(f.dag))}</td>
                      <td className="l">{stamp(f.updatedAt)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
              {flows && flows.length === 0 && (
                <p className="wb-muted pad">No flow is stored yet. Build one on the workbench and press "Save flow".</p>
              )}
              {flows && flows.length > 0 && rows.length === 0 && <p className="wb-muted pad">No flow matches the filters.</p>}
            </div>
          </section>

          <aside className="wb-inv-detail" aria-label="Flow details">
            {focused ? (
              <>
                <h3>{focused.name}</h3>
                {focused.description && <p>{focused.description}</p>}
                {focused.reason && <p className="warn">{focused.reason} It cannot be opened here.</p>}
                <dl className="wb-readout wide">
                  <dt>Flow id</dt><dd>{focused.id}</dd>
                  {focused.track && [<dt key="tk">Track</dt>, <dd key="tv">{focused.track}</dd>]}
                  {focused.sourceTopic && [<dt key="sk">Reads from</dt>, <dd key="sv">{focused.sourceTopic}</dd>]}
                  {focused.openable && [
                    <dt key="dk">Series</dt>,
                    <dd key="dv">{focused.sourceSeries.length > 0 ? `${focused.sourceSeries.length} from the TS inventory` : 'none: load a bucket after opening'}</dd>,
                  ]}
                  {focused.dag && dags.get(focused.dag) && [
                    <dt key="gk">Deployed as</dt>,
                    <dd key="gv">{focused.dag}, {deployment(dags.get(focused.dag))}</dd>,
                  ]}
                  <dt>Changed</dt><dd>{stamp(focused.updatedAt)} UTC</dd>
                </dl>
                {focused.steps.length > 0 && (
                  <>
                    <h3>Buckets</h3>
                    <ol className="wb-flows-steps">
                      {focused.steps.map((s, i) => <li key={i}>{s}</li>)}
                    </ol>
                  </>
                )}
                {focused.published.length > 0 && (
                  <>
                    <h3>Smart Topics</h3>
                    <ul className="wb-flows-steps">
                      {focused.published.map((t) => <li key={t}>{t}</li>)}
                    </ul>
                  </>
                )}
              </>
            ) : (
              <p className="wb-muted pad">Select a flow to see what it reads, computes and publishes.</p>
            )}
          </aside>
        </div>

        <footer className="wb-inv-foot">
          <span className="wb-grow" />
          <span className={`wb-muted ${replaces && picked.length > 0 ? 'warn' : ''}`} role="status">
            {picked.length === 0
              ? 'Select one or more flows. A double click opens a flow at once.'
              : `${replaces ? 'Replaces the flow on the workbench; save it first if you want to keep it. ' : ''}${
                  picked.length > 1 ? `"${first?.name}" opens here, ${picked.length === 2 ? 'the other one in a tab of its own' : `the other ${picked.length - 1} in tabs of their own`}.` : ''
                }`}
          </span>
          <button className="wb-primary" disabled={picked.length === 0} onClick={() => onOpen(picked)}>
            {picked.length > 1 ? `Open ${picked.length} selected flows` : 'Open selected flow'}
          </button>
        </footer>
      </div>
    </div>
  );
}
