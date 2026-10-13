/**
 * The place "Signals" of a system: every quantity the system expects, where its data comes from, how far it has
 * come and what to do next. The state is computed by the retrieval server from the system document and the TS
 * inventory. Concept: docs/datamodel-2026/14-ENGINEERING-FLOW.md, delivery stage L1.
 */

import { useEffect, useMemo, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { useFlow } from '../workbench/flow';
import {
  GradeName,
  NEXT,
  NO_SIGNAL_FILTER,
  Signal,
  SignalFilter,
  SystemEntry,
  SystemStatus,
  countsText,
  dataText,
  expectText,
  filterSignals,
  groupSignals,
  isSynthetic,
  originText,
  parseStatus,
  scopeGrade,
  scopeLink,
  sourceText,
  stateText,
} from '../systems/signals';
import '../workbench/workbench.css';

const BASE = '/retrieval';
const GRADES: GradeName[] = ['raw', 'clean', 'features'];

export default function Systems() {
  const { system } = useParams();
  const navigate = useNavigate();
  const [systems, setSystems] = useState<SystemEntry[] | null>(null);
  const [status, setStatus] = useState<SystemStatus | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [filter, setFilter] = useState<SignalFilter>(NO_SIGNAL_FILTER);
  const [focus, setFocus] = useState<string | null>(null);
  const [reload, setReload] = useState(0);
  /** A jump to the scope that waits for a yes, because the workbench holds unsaved work. */
  const [pending, setPending] = useState<string | null>(null);
  const signedIn = !!localStorage.getItem('access_token');

  useEffect(() => {
    let cancelled = false;
    fetch(`${BASE}/v1/systems`)
      .then(async (res) => {
        if (res.status === 404) throw new Error('the retrieval server has no system documents (it needs --systems)');
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        return (await res.json()).systems as SystemEntry[];
      })
      .then((list) => {
        if (cancelled) return;
        setSystems(list);
        if (!system && list.length > 0) navigate(`/systems/${list[0].system}/signals`, { replace: true });
      })
      .catch((e) => !cancelled && setError(`The systems could not be read: ${e instanceof Error ? e.message : e}.`));
    return () => {
      cancelled = true;
    };
  }, [system, navigate]);

  useEffect(() => {
    if (!system) return;
    let cancelled = false;
    setStatus(null);
    fetch(`${BASE}/v1/systems/${encodeURIComponent(system)}/signals`)
      .then(async (res) => {
        if (!res.ok) throw new Error(res.status === 404 ? `the server knows no system "${system}"` : `HTTP ${res.status}`);
        return parseStatus(await res.json());
      })
      .then((doc) => {
        if (cancelled) return;
        setStatus(doc);
        setError(null);
      })
      .catch((e) => !cancelled && setError(`The signals could not be read: ${e instanceof Error ? e.message : e}.`));
    return () => {
      cancelled = true;
    };
  }, [system, reload]);

  const rows = useMemo(() => filterSignals(status?.signals ?? [], filter), [status, filter]);
  const groups = useMemo(() => groupSignals(rows), [rows]);
  const focused = status?.signals.find((s) => s.id === focus) ?? null;
  const filtered = filter.text !== '' || filter.state !== null || filter.via !== null || filter.todo;
  const states = Object.entries(status?.states ?? {});
  const vias = Object.entries(status?.counts ?? {}).filter(([k]) => k !== 'signals');

  /** Opens the series of a grade on the scope; asks first when that would throw away an unsaved flow. */
  const openOnScope = (s: Signal, grade: GradeName) => {
    const link = scopeLink(status!.system, s, grade);
    const flow = useFlow.getState();
    if (flow.nodes.length > 1 && flow.savedId === null) setPending(link);
    else navigate(link);
  };

  const step = (s: Signal, n: Signal['next'][number]) => {
    const def = NEXT[n];
    const grade = scopeGrade(s);
    const live = def.live && grade !== null;
    return (
      <button
        key={n}
        disabled={!live}
        title={def.hint}
        onClick={(e) => {
          e.stopPropagation();
          if (grade) openOnScope(s, n === 'prepare' ? 'raw' : grade);
        }}
      >
        {def.label}
      </button>
    );
  };

  return (
    <div className="wb wb-page">
      <header className="wb-toolbar">
        <h1>OpenTSx Signals</h1>
        <label className="wb-param">
          System
          <select value={system ?? ''} onChange={(e) => navigate(`/systems/${e.target.value}/signals`)}>
            {(systems ?? []).map((s) => <option key={s.system} value={s.system}>{s.title}</option>)}
          </select>
        </label>
        <span className="wb-muted">{status ? countsText(status.counts) : ''}</span>
        <span className="wb-grow" />
        <Link to="/workbench">Workbench</Link>
        {signedIn ? <Link to="/dashboard">Flows</Link> : <Link to="/login">Sign in</Link>}
      </header>

      <main className="wb-inv wb-inv-page" aria-label="Signals of the system">
        <header className="wb-inv-head">
          <h2>{status?.title ?? system ?? 'Signals'}</h2>
          <button onClick={() => setReload((n) => n + 1)}>Reload</button>
          <span className="wb-grow" />
          {status && <span className="wb-muted">{status.example ? 'example data, ' : ''}system hash {status.hash.slice(0, 8)}, version {status.version}</span>}
        </header>

        <div className="wb-inv-source">
          <span>
            A signal is a quantity the system expects, whether data exists for it or not. Each row says where its data
            comes from, how far it has come, and the next step.
          </span>
          {(status?.problems.length ?? 0) > 0 && (
            <span className="warn">The system document has {status!.problems.length} problems: {status!.problems.join('; ')}</span>
          )}
        </div>
        {error && <div className="wb-error" role="alert">{error}</div>}
        {!error && pending && (
          <div className="wb-error" role="alert">
            The workbench holds a flow that is not saved. Opening the signal there replaces it.{' '}
            <button className="wb-danger" onClick={() => navigate(pending)}>Open and discard the flow</button>{' '}
            <button onClick={() => setPending(null)}>Keep it</button>
          </div>
        )}

        <div className="wb-inv-body">
          <nav className="wb-inv-facets" aria-label="Filters">
            <section>
              <h3>state</h3>
              <ul>
                {states.map(([st, n]) => (
                  <li key={st}>
                    <button aria-pressed={filter.state === st} onClick={() => setFilter({ ...filter, state: filter.state === st ? null : (st as Signal['state']) })}>
                      <span className="name">{st.replace(/-/g, ' ')}</span>
                      <span className="unit">{n}</span>
                    </button>
                  </li>
                ))}
              </ul>
            </section>
            <section>
              <h3>comes from</h3>
              <ul>
                {vias.map(([v, n]) => (
                  <li key={v}>
                    <button aria-pressed={filter.via === v} onClick={() => setFilter({ ...filter, via: filter.via === v ? null : v })}>
                      <span className="name">{v}</span>
                      <span className="unit">{n}</span>
                    </button>
                  </li>
                ))}
              </ul>
            </section>
            <section>
              <h3>show only</h3>
              <ul>
                <li>
                  <button aria-pressed={filter.todo} onClick={() => setFilter({ ...filter, todo: !filter.todo })}>
                    <span className="name">Signals with a next step</span>
                  </button>
                </li>
              </ul>
            </section>
          </nav>

          <section className="wb-inv-list" aria-label="Signals">
            <div className="wb-inv-search">
              <input
                type="search"
                placeholder="Filter: signal, unit, origin, state, flow"
                aria-label="Filter the signals"
                value={filter.text}
                onChange={(e) => setFilter({ ...filter, text: e.target.value })}
              />
              <span className="wb-muted">{rows.length} of {status?.signals.length ?? 0} shown</span>
              <button disabled={!filtered} onClick={() => setFilter(NO_SIGNAL_FILTER)}>Clear filters</button>
            </div>
            <div className="wb-inv-scroll">
              <table className="wb-table wb-inv-table wb-signals">
                <thead>
                  <tr>
                    <th>signal</th>
                    <th>expected</th>
                    <th>origin</th>
                    <th>state</th>
                    <th>next</th>
                  </tr>
                </thead>
                {groups.map((g) => (
                  <tbody key={g.title}>
                    <tr className="group"><th colSpan={5} scope="rowgroup">{g.title}</th></tr>
                    {g.signals.map((s) => (
                      <tr key={s.id} className={focus === s.id ? 'picked focused' : ''} onClick={() => setFocus(s.id)}>
                        <td className="l" title={s.title}>{s.name}</td>
                        <td className="l">{expectText(s.expect)}</td>
                        <td className="l">
                          <span className={`wb-origin ${isSynthetic(s) ? 'synthetic' : ''} ${s.via === 'simulated' ? 'simulated' : ''}`}>{originText(s)}</span>
                        </td>
                        <td className="l">
                          <span className={`wb-flow-status ${s.state}`}>{stateText(s)}</span>
                          {s.outOfRange && <span className="wb-flow-status failed" title={`Seen ${s.outOfRange.min} to ${s.outOfRange.max}`}>out of range</span>}
                        </td>
                        <td className="l wb-flow-run">{s.next.length === 0 ? 'n/a' : s.next.map((n) => step(s, n))}</td>
                      </tr>
                    ))}
                  </tbody>
                ))}
              </table>
              {status && status.signals.length === 0 && <p className="wb-muted pad">The system document names no signals.</p>}
              {status && status.signals.length > 0 && rows.length === 0 && <p className="wb-muted pad">No signal matches the filters.</p>}
            </div>
          </section>

          <aside className="wb-inv-detail" aria-label="Signal details">
            {focused ? (
              <>
                <h3>{focused.id}</h3>
                <p>{focused.title}</p>
                <dl className="wb-readout wide">
                  <dt>Expected</dt><dd>{expectText(focused.expect)}{focused.expect.kind ? `, ${focused.expect.kind.toLowerCase()}` : ''}</dd>
                  {focused.channel && [<dt key="ck">Channel</dt>, <dd key="cv">{focused.channel}</dd>]}
                  <dt>Origin</dt><dd>{originText(focused)}{focused.expect.origin ? ` (expected: ${focused.expect.origin.toLowerCase()})` : ''}</dd>
                  <dt>State</dt><dd>{stateText(focused)}</dd>
                  {focused.outOfRange && [
                    <dt key="ok">Seen</dt>,
                    <dd key="ov" className="warn">{focused.outOfRange.min} to {focused.outOfRange.max}: outside the expected range</dd>,
                  ]}
                  {focused.usedBy.length > 0 && [<dt key="uk">Used by</dt>, <dd key="uv">{focused.usedBy.join(', ')}</dd>]}
                </dl>
                {focused.note && <p className="wb-muted">{focused.note}</p>}
                {GRADES.map((g) => {
                  const grade = focused.grades[g];
                  if (!grade && g === 'features') return null;
                  return (
                    <div key={g}>
                      <h3>{g}</h3>
                      <div className="wb-flow-run-box">
                        <p>{sourceText(grade)}</p>
                        {grade && grade.source.kind !== 'manual' && <p className="wb-muted">{dataText(grade.data)}{grade.data?.from ? `, ${grade.data.from.slice(0, 16).replace('T', ' ')} to ${grade.data.to?.slice(0, 16).replace('T', ' ')} UTC` : ''}</p>}
                        {(grade?.data?.series.length ?? 0) > 0 && g !== 'features' && (
                          <button onClick={() => openOnScope(focused, g)}>Open on scope</button>
                        )}
                      </div>
                    </div>
                  );
                })}
                {focused.next.length > 0 && (
                  <>
                    <h3>Next</h3>
                    <ul className="wb-findings">
                      {focused.next.map((n) => (
                        <li key={n} className={NEXT[n].live ? 'ok' : 'warning'}><b>{NEXT[n].label}</b> {NEXT[n].hint}</li>
                      ))}
                    </ul>
                  </>
                )}
              </>
            ) : (
              <p className="wb-muted pad">Select a signal to see its expectation, its sources and what the server found for it.</p>
            )}
          </aside>
        </div>

        <footer className="wb-inv-foot">
          <span className="wb-muted">
            {(status?.notes.length ?? 0) > 0 ? `Notes of the check: ${status!.notes.join('; ')}` : 'The state is computed from the system document and the TS inventory on every reload.'}
          </span>
        </footer>
      </main>
    </div>
  );
}
