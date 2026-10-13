/**
 * OpenTSx Flow Composer: the flows stored in the account, with what each one reads, computes and publishes.
 * From here a flow is opened in the editor (the workbench), created, renamed, copied and deleted, and its Smart
 * Topic is started and stopped on the vector processor.
 */

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import axios from 'axios';
import { dagsAPI, flowsAPI } from '../services/api';
import { DagList, DagState, describeSink, topicConflict } from './deployments';
import { deployChecks } from './flow';
import { FlowFilter, FlowSummary, NO_FLOW_FILTER, copyName, domainFacets, filterFlows, runPlan, stamp, summarize } from './flows';

interface Props {
  /** Retrieval server, asked for the state of deployed flows. */
  base: string;
  /** Flow in the editor, if it is stored. */
  currentId: number | null;
  /** True when the editor holds work that opening replaces. */
  replaces: boolean;
  onOpen: (ids: number[]) => void;
  onNew: () => void;
  /** Flows that no longer exist; the editor lets go of them. */
  onDeleted: (ids: number[]) => void;
  /** Closes the window; absent when the composer is a page of its own. */
  onClose?: () => void;
  /** Shows only the flows of this system: those made for one of its signals, and flows of its domain without a system. */
  system?: { id: string; domain: string; title: string };
  /** Reports how many flows the list holds, for the rail of the shell. */
  onCount?: (flows: number, running: number) => void;
}

function deployment(d: DagState | undefined): string {
  if (!d) return '';
  if (d.desired !== 'deployed') return 'stopped';
  return d.sinks.length > 0 && d.sinks.every((s) => s.state === 'running') ? 'running' : d.sinks.some((s) => s.state === 'failed') ? 'failed' : 'starting';
}

function reasonOf(e: unknown): string {
  if (axios.isAxiosError(e)) {
    if (e.response?.status === 401) return 'your session has expired, sign in again';
    const detail = e.response?.data?.detail;
    if (typeof detail === 'string') return detail;
  }
  return e instanceof Error ? e.message : String(e);
}

export default function FlowComposer({ base, currentId, replaces, onOpen, onNew, onDeleted, onClose, system, onCount }: Props) {
  const [flows, setFlows] = useState<FlowSummary[] | null>(null);
  const [dags, setDags] = useState<Map<string, DagState>>(new Map());
  const [access, setAccess] = useState<{ canRun: boolean; reason: string | null } | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [message, setMessage] = useState<{ ok: boolean; lines: string[] } | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [filter, setFilter] = useState<FlowFilter>(NO_FLOW_FILTER);
  const [picked, setPicked] = useState<number[]>([]);
  const [focus, setFocus] = useState<number | null>(null);
  const [draft, setDraft] = useState({ name: '', description: '' });
  const [confirmDelete, setConfirmDelete] = useState(false);
  const dialog = useRef<HTMLDivElement>(null);
  const root = base.replace(/\/$/, '');

  const refresh = useCallback(async () => {
    try {
      const all = (await flowsAPI.list()).map(summarize);
      setFlows(all);
      setPicked((p) => p.filter((id) => all.some((f) => f.id === id)));
      setError(null);
    } catch (e) {
      setFlows(null);
      setError(`The flows of your account could not be read: ${reasonOf(e)}.`);
    }
    // The deployment column is an extra: without a retrieval server it stays empty.
    try {
      const res = await fetch(`${root}/v1/dags`, { signal: AbortSignal.timeout(10_000) });
      if (res.ok) setDags(new Map(((await res.json()) as DagList).dags.map((d) => [d.dag, d])));
    } catch {
      // the list works without it
    }
  }, [root]);

  useEffect(() => {
    refresh();
    dagsAPI.capabilities().then(setAccess).catch(() => setAccess({ canRun: false, reason: 'The backend does not say whether this account may deploy.' }));
    dialog.current?.focus();
  }, [refresh]);

  const domains = useMemo(() => domainFacets(flows ?? []), [flows]);
  const own = useMemo(() => filterFlows(flows ?? [], { ...NO_FLOW_FILTER, system: system ?? null }), [flows, system]);
  const rows = useMemo(() => filterFlows(own, filter), [own, filter]);
  const running = own.filter((f) => f.dag && dags.get(f.dag)?.desired === 'deployed').length;
  useEffect(() => {
    if (flows) onCount?.(own.length, running);
    // the callback is not a dependency: only the numbers are
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [flows, own.length, running]);
  const focused = flows?.find((f) => f.id === focus) ?? null;
  const filtered = filter.text !== '' || filter.domain !== null || filter.only !== null || filter.status !== null;
  const allShown = rows.length > 0 && rows.every((f) => picked.includes(f.id));
  const targets = (flows ?? []).filter((f) => (picked.length > 0 ? picked.includes(f.id) : f.id === focus));
  const openable = targets.filter((f) => f.openable);
  const many = targets.length > 1;

  useEffect(() => {
    setDraft({ name: focused?.name ?? '', description: focused?.description ?? '' });
    // only when another flow gets the focus, not on every refresh of the list
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [focus]);
  useEffect(() => setConfirmDelete(false), [picked, focus]);

  const toggle = (f: FlowSummary) => {
    setFocus(f.id);
    setPicked((p) => (p.includes(f.id) ? p.filter((i) => i !== f.id) : [...p, f.id]));
  };
  const toggleAll = () =>
    setPicked((p) => (allShown ? p.filter((i) => !rows.some((f) => f.id === i)) : [...new Set([...p, ...rows.map((f) => f.id)])]));

  /** Runs one step per flow and reports each outcome; a failure of one flow does not stop the others. */
  const forEach = async (what: string, step: (f: FlowSummary) => Promise<string>, list: FlowSummary[] = targets) => {
    setBusy(what);
    setMessage(null);
    const lines: string[] = [];
    let ok = true;
    for (const f of list) {
      try {
        lines.push(await step(f));
      } catch (e) {
        ok = false;
        lines.push(`"${f.name}": ${what.toLowerCase()} failed, ${reasonOf(e)}.`);
      }
    }
    // the outcome first: reading the lists again can take a while when the broker is slow
    setMessage({ ok, lines });
    await refresh();
    setBusy(null);
  };

  /** Starts the Smart Topic of a flow: hands its DAG to the vector processor. */
  const start = (list?: FlowSummary[]) =>
    forEach(
      'Start',
      async (f) => {
        const plan = runPlan(f);
        if ('blocker' in plan) throw new Error(`the processor cannot run this flow yet. ${plan.blocker} Open it in the editor to change that`);
        if (!access?.canRun) throw new Error(access?.reason ?? 'this account may not deploy');
        // read anew: a flow started a moment ago in this run counts too
        const res = await fetch(`${root}/v1/dags`, { signal: AbortSignal.timeout(10_000) }).catch(() => null);
        const running = res?.ok ? ((await res.json()) as DagList).dags : [...dags.values()];
        const conflict = topicConflict(running, plan.dag, f.published, (await flowsAPI.list()).map(summarize));
        if (conflict) throw new Error(conflict);
        await dagsAPI.deploy(plan.yaml);
        await flowsAPI.patch(f.id, { status: 'active' });
        return `"${f.name}" is started as ${plan.dag}: the processor takes it up within a few seconds and writes ${f.published.join(', ')}.`;
      },
      list
    );

  /** Stops the Smart Topic of a flow. What was written stays in the topic. */
  const stop = (list?: FlowSummary[]) =>
    forEach(
      'Stop',
      async (f) => {
        const running = f.dag && dags.get(f.dag)?.desired === 'deployed';
        if (running) {
          if (!access?.canRun) throw new Error(access?.reason ?? 'this account may not stop a deployment');
          await dagsAPI.stop(f.dag);
        }
        if (running || f.status === 'active') await flowsAPI.patch(f.id, { status: 'paused' });
        return running ? `"${f.name}" is stopped. Its topic keeps what was written.` : `"${f.name}" was not running.`;
      },
      list
    );

  const duplicate = () => {
    const taken = (flows ?? []).map((f) => f.name);
    return forEach('Copy', async (f) => {
      const name = copyName(f.name, taken);
      taken.push(name);
      const copy = await flowsAPI.save(null, { name, description: f.description, definition: f.definition });
      return `"${f.name}" copied as flow ${copy.id}, "${name}".`;
    });
  };

  const remove = async () => {
    const gone: number[] = [];
    await forEach('Delete', async (f) => {
      if (f.dag && dags.get(f.dag)?.desired === 'deployed') {
        throw new Error(`it is deployed as ${f.dag}; pause it first`);
      }
      await flowsAPI.delete(f.id);
      gone.push(f.id);
      return `Flow ${f.id}, "${f.name}", is deleted.`;
    });
    setConfirmDelete(false);
    if (gone.length > 0) onDeleted(gone);
  };

  const saveDetails = async () => {
    if (!focused) return;
    setBusy('Save');
    setMessage(null);
    try {
      await flowsAPI.patch(focused.id, { name: draft.name.trim(), description: draft.description });
      await refresh();
      setMessage({ ok: true, lines: [`Flow ${focused.id} is now "${draft.name.trim()}".`] });
    } catch (e) {
      setMessage({ ok: false, lines: [`The changes could not be saved: ${reasonOf(e)}.`] });
    }
    setBusy(null);
  };

  const isOn = (f: FlowSummary) => dags.get(f.dag)?.desired === 'deployed';
  /** What the Smart Topic of a runnable flow is doing. */
  const state = (f: FlowSummary) => (isOn(f) ? deployment(dags.get(f.dag)) : dags.get(f.dag) ? 'stopped' : 'not started');
  const changed = !!focused && (draft.name.trim() !== focused.name || draft.description !== focused.description);
  const plan = focused ? runPlan(focused) : null;
  const none = targets.length === 0;

  const body = (
      <>
        <header className="wb-inv-head">
          <h2>{onClose ? 'OpenTSx Flow Composer' : system ? `Flows of ${system.title}` : 'Your flows'}</h2>
          <button onClick={onNew} title="Opens the editor with an empty flow">New flow</button>
          <button onClick={refresh}>Reload</button>
          <span className="wb-grow" />
          {onClose && <button onClick={onClose}>Close</button>}
        </header>

        <div className="wb-inv-source">
          <span>
            {flows === null && !error
              ? 'Reading the flows of your account'
              : system
                ? `${own.length} of the ${flows?.length ?? 0} flows in your account belong to this system, ${running} running.`
                : `${flows?.length ?? 0} flows in your account, ${running} running.`}{' '}
            Select a flow, then open it in the editor, copy or delete it, or start and stop its Smart Topic.
          </span>
          <span>
            Start runs a flow as a Smart Topic on the vector processor: it writes the topic and follows new data. Stop ends that.{' '}
            {access && !access.canRun ? `Deploying is not open to this account: ${access.reason ?? 'administrators only'}` : ''}
          </span>
        </div>
        {error && <div className="wb-error" role="alert">{error}</div>}
        {!error && message && (
          <div className={message.ok ? 'wb-note' : 'wb-error'} role={message.ok ? 'status' : 'alert'}>
            {message.lines.map((l, i) => <div key={i}>{l}</div>)}
          </div>
        )}

        <div className="wb-inv-body">
          <nav className="wb-inv-facets" aria-label="Filters">
            {!system && domains.length > 0 && (
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
              <span className="wb-muted">{rows.length} of {own.length} shown, {picked.length} selected</span>
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
                    <th>signal</th>
                    <th>track</th>
                    {!system && <th>domain</th>}
                    <th>steps</th>
                    <th>series</th>
                    <th>topics</th>
                    <th>state</th>
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
                          checked={picked.includes(f.id)}
                          onChange={() => toggle(f)}
                          onClick={(e) => e.stopPropagation()}
                        />
                      </td>
                      <td>{f.id}</td>
                      <td className="l">{f.name}{f.id === currentId ? ' (in the editor)' : ''}</td>
                      <td className="l">{f.signal}</td>
                      <td className="l">{f.track}</td>
                      {!system && <td className="l">{f.domain}</td>}
                      <td>{f.openable ? f.steps.length : ''}</td>
                      <td>{f.sourceSeries.length || ''}</td>
                      <td>{f.published.length || ''}</td>
                      <td className="l wb-flow-run">
                        {f.runnable ? (
                          <>
                            <span className={`wb-flow-status ${state(f)}`}>{state(f)}</span>
                            {isOn(f) ? (
                              <button disabled={!!busy} onClick={(e) => { e.stopPropagation(); stop([f]); }} title="Stops writing the Smart Topic">Stop</button>
                            ) : (
                              <button disabled={!!busy} onClick={(e) => { e.stopPropagation(); start([f]); }} title="Starts the vector processor on this flow">Start</button>
                            )}
                          </>
                        ) : (
                          <span className="wb-muted" title="The processor cannot run this flow; select it to see what it needs">{f.openable ? 'workbench only' : 'flow builder'}</span>
                        )}
                      </td>
                      <td className="l">{stamp(f.updatedAt)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
              {flows && flows.length === 0 && (
                <p className="wb-muted pad">No flow is stored yet. Press "New flow", build one in the editor and press "Save flow".</p>
              )}
              {flows && flows.length > 0 && own.length === 0 && (
                <p className="wb-muted pad">No flow belongs to this system yet. Open a signal on the scope from "Signals", prepare it and save the flow.</p>
              )}
              {own.length > 0 && rows.length === 0 && <p className="wb-muted pad">No flow matches the filters.</p>}
            </div>
          </section>

          <aside className="wb-inv-detail" aria-label="Flow details">
            {focused ? (
              <>
                <h3>Flow {focused.id}</h3>
                <div className="wb-flow-edit">
                  <label>
                    name
                    <input type="text" value={draft.name} maxLength={200} onChange={(e) => setDraft({ ...draft, name: e.target.value })} />
                  </label>
                  <label>
                    description
                    <textarea rows={3} value={draft.description} onChange={(e) => setDraft({ ...draft, description: e.target.value })} />
                  </label>
                  <button disabled={!changed || draft.name.trim() === '' || !!busy} onClick={saveDetails}>Save name and description</button>
                </div>
                {focused.reason && (
                  <p className="warn">
                    {focused.reason} The editor cannot open it. <a href={`/flow-builder/${focused.id}`}>Open in the flow builder</a>
                  </p>
                )}
                <dl className="wb-readout wide">
                  {focused.signal && [<dt key="sgk">Signal</dt>, <dd key="sgv">{focused.system} / {focused.signal}</dd>]}
                  {focused.track && [<dt key="tk">Track</dt>, <dd key="tv">{focused.track}</dd>]}
                  {focused.sourceTopic && [<dt key="sk">Reads from</dt>, <dd key="sv">{focused.sourceTopic}</dd>]}
                  {focused.openable && [
                    <dt key="dk">Series</dt>,
                    <dd key="dv">{focused.sourceSeries.length > 0 ? `${focused.sourceSeries.length} from the TS inventory` : 'none: load a bucket after opening'}</dd>,
                  ]}
                  {focused.dag && dags.get(focused.dag) && [
                    <dt key="gk">Processor</dt>,
                    <dd key="gv">{focused.dag}, {deployment(dags.get(focused.dag))}</dd>,
                  ]}
                  <dt>Changed</dt><dd>{stamp(focused.updatedAt)} UTC</dd>
                </dl>
                <h3>Smart Topic</h3>
                {plan && 'yaml' in plan ? (
                  <div className="wb-flow-run-box">
                    <p>
                      <span className={`wb-flow-status ${state(focused)}`}>{state(focused)}</span> as {plan.dag}
                    </p>
                    {(dags.get(focused.dag)?.sinks ?? []).map((sink) => (
                      <p key={sink.topic} className="wb-muted">{sink.topic}: {describeSink(sink)}</p>
                    ))}
                    <button className="wb-primary" disabled={!!busy || isOn(focused)} onClick={() => start([focused])}>Start</button>{' '}
                    <button disabled={!!busy || !isOn(focused)} onClick={() => stop([focused])}>Stop</button>
                    <p className="wb-muted">
                      Start hands the flow to the vector processor: it computes the topic from the beginning of the source and
                      follows it. Stop ends that; the topic keeps what was written.
                    </p>
                  </div>
                ) : focused.doc ? (
                  <div className="wb-flow-run-box">
                    <p>This flow cannot run as a Smart Topic yet. The processor needs:</p>
                    <ul className="wb-findings">
                      {deployChecks(focused.doc.nodes, focused.sourceSeries, focused.doc.context).map((c, i) => (
                        <li key={i} className={c.ok ? 'ok' : 'error'}><b>{c.ok ? 'Ready' : 'Missing'}</b> {c.text}</li>
                      ))}
                    </ul>
                    <button onClick={() => onOpen([focused.id])}>Open in the editor</button>
                  </div>
                ) : (
                  <p className="wb-muted">Only flows made in the editor can run as a Smart Topic.</p>
                )}
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
              <p className="wb-muted pad">Select a flow to see what it reads, computes and publishes, and to change its name.</p>
            )}
          </aside>
        </div>

        <footer className="wb-inv-foot">
          {confirmDelete ? (
            <>
              <span className="warn" role="alert">
                Delete {many ? `${targets.length} flows` : `"${targets[0]?.name}"`} for good? Topics already written stay on the broker.
              </span>
              <button className="wb-danger" disabled={!!busy} onClick={remove}>Delete</button>
              <button onClick={() => setConfirmDelete(false)}>Keep</button>
            </>
          ) : (
            <>
              <span className="wb-muted">Smart Topic:</span>
              <button disabled={none || !!busy} onClick={() => start()} title="Starts the vector processor on the selected flows">Start</button>
              <button disabled={none || !!busy} onClick={() => stop()} title="Stops writing the Smart Topics of the selected flows">Stop</button>
              <span className="wb-sep" />
              <button disabled={none || !!busy} onClick={duplicate}>Copy</button>
              <button disabled={none || !!busy} onClick={() => setConfirmDelete(true)}>Delete</button>
            </>
          )}
          <span className="wb-grow" />
          <span className={`wb-muted ${replaces && openable.length > 0 ? 'warn' : ''}`} role="status">
            {busy
              ? `${busy}: working`
              : none
                ? 'Select one or more flows. A double click opens a flow in the editor at once.'
                : `${many ? `${targets.length} flows selected. ` : ''}${replaces && openable.length > 0 ? 'Opening replaces the flow in the editor; save it first if you want to keep it. ' : ''}${
                    openable.length > 1 ? 'The first opens here, the others in tabs of their own.' : ''
                  }`}
          </span>
          <button className="wb-primary" disabled={openable.length === 0 || !!busy} onClick={() => onOpen(openable.map((f) => f.id))}>
            {openable.length > 1 ? `Open ${openable.length} flows in the editor` : 'Open in the editor'}
          </button>
        </footer>
      </>
  );

  return onClose ? (
    <div className="wb-inv-backdrop" onMouseDown={(e) => e.target === e.currentTarget && onClose()}>
      <div
        className="wb-inv"
        role="dialog"
        aria-modal="true"
        aria-label="OpenTSx Flow Composer"
        tabIndex={-1}
        ref={dialog}
        onKeyDown={(e) => e.key === 'Escape' && onClose()}
      >
        {body}
      </div>
    </div>
  ) : (
    <main className="wb-inv wb-inv-page" aria-label="Flows of your account">
      {body}
    </main>
  );
}
