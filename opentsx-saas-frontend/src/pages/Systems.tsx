/**
 * The shell around a system: a rail with its four places (System, Signals, Flows, Models) and the workbench as the
 * tool, a line that says where the system stands, and, when switched on, the guide with the seven steps of the
 * engineering method. Concept: docs/datamodel-2026/14-ENGINEERING-FLOW.md, sections 3.2 and 8.
 */

import { useEffect, useState } from 'react';
import { Link, NavLink, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { useAuthStore } from '../store/authStore';
import FlowComposer from '../workbench/FlowComposer';
import { useFlow } from '../workbench/flow';
import ModelsPlace from '../systems/ModelsPlace';
import SignalsPlace from '../systems/SignalsPlace';
import SystemPlace from '../systems/SystemPlace';
import { Place, PlanDoc, SystemEntry, SystemStatus, countsText, guideSteps, parseStatus, railCounts } from '../systems/signals';
import '../workbench/workbench.css';

const BASE = '/retrieval';
const PLACES: { id: Place; title: string; hint: string }[] = [
  { id: 'system', title: 'System', hint: 'What the plant is made of' },
  { id: 'signals', title: 'Signals', hint: 'What the system expects, what is there, what to do next' },
  { id: 'flows', title: 'Flows', hint: 'What prepares the data, and what runs' },
  { id: 'models', title: 'Models', hint: 'What the system predicts' },
];

function remembered(key: string): boolean {
  try {
    return localStorage.getItem(key) === '1';
  } catch {
    return false;
  }
}

export default function Systems() {
  const { system, place } = useParams();
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  const { user, logout } = useAuthStore();
  const [systems, setSystems] = useState<SystemEntry[] | null>(null);
  const [status, setStatus] = useState<SystemStatus | null>(null);
  const [plan, setPlan] = useState<PlanDoc | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [reload, setReload] = useState(0);
  const [guide, setGuide] = useState(() => remembered('opentsx.guide'));
  const [flows, setFlows] = useState<{ all: number; running: number } | null>(null);
  const signedIn = !!localStorage.getItem('access_token');
  const here = (PLACES.find((p) => p.id === place)?.id ?? 'signals') as Place;

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
    const get = async (what: string) => {
      const res = await fetch(`${BASE}/v1/systems/${encodeURIComponent(system)}/${what}`);
      if (!res.ok) throw new Error(res.status === 404 ? `the server knows no system "${system}"` : `HTTP ${res.status}`);
      return res.json();
    };
    Promise.all([get('signals'), get('plan')])
      .then(([signals, planDoc]) => {
        if (cancelled) return;
        setStatus(parseStatus(signals));
        setPlan(planDoc as PlanDoc);
        setError(null);
      })
      .catch((e) => !cancelled && setError(`The system could not be read: ${e instanceof Error ? e.message : e}.`));
    return () => {
      cancelled = true;
    };
  }, [system, reload]);

  const toggleGuide = () => {
    setGuide((g) => {
      try {
        localStorage.setItem('opentsx.guide', g ? '0' : '1');
      } catch {
        // the guide works without being remembered
      }
      return !g;
    });
  };

  // The first flow opens in the workbench of this tab, the others in tabs of their own.
  const openFlows = (ids: number[]) => {
    const [first, ...others] = ids;
    for (const id of others) window.open(`/workbench?flow=${id}`, '_blank');
    if (useFlow.getState().savedId === first) useFlow.getState().setSavedId(null);
    navigate(`/workbench?flow=${first}`);
  };
  const newFlow = () => {
    useFlow.getState().clear();
    navigate('/workbench');
  };
  const forget = (ids: number[]) => {
    const id = useFlow.getState().savedId;
    if (id !== null && ids.includes(id)) useFlow.getState().clear();
  };

  const counts = railCounts(status, plan, signedIn ? flows?.all : null);
  const steps = guideSteps(status, plan, flows?.running ?? null);
  const next = steps.find((s) => s.mark === 'todo');

  return (
    <div className="wb wb-shell">
      <header className="wb-toolbar">
        <h1>OpenTSx</h1>
        <label className="wb-param">
          System
          <select value={system ?? ''} onChange={(e) => navigate(`/systems/${e.target.value}/${here}`)}>
            {(systems ?? []).map((s) => <option key={s.system} value={s.system}>{s.title}</option>)}
          </select>
        </label>
        <span className="wb-muted">{status ? countsText(status.counts) : ''}</span>
        <span className="wb-grow" />
        <button aria-pressed={guide} onClick={toggleGuide} title="Shows the seven steps of the engineering method with the state of this system">
          Guide
        </button>
        {signedIn ? (
          <>
            <span className="wb-muted">{user?.full_name || user?.email}</span>
            <button onClick={() => logout().then(() => navigate('/login'))}>Logout</button>
          </>
        ) : (
          <Link to="/login">Sign in</Link>
        )}
      </header>

      {guide && (
        <ol className="wb-guide" aria-label="The seven steps of the engineering method">
          {steps.map((s) => (
            <li key={s.n} className={`${s.mark} ${next?.n === s.n ? 'next' : ''}`}>
              <Link to={`/systems/${system}/${s.place}`} title={next?.n === s.n ? 'The next sensible step' : undefined}>
                <span className="n">{s.n}</span>
                <span className="t">{s.title}</span>
                <span className="s">{s.state}</span>
              </Link>
            </li>
          ))}
        </ol>
      )}
      {error && <div className="wb-error" role="alert">{error}</div>}

      <div className="wb-shell-body">
        <nav className="wb-rail" aria-label="Places of the system">
          {PLACES.map((p) => (
            <NavLink key={p.id} to={`/systems/${system}/${p.id}`} title={p.hint} className={() => (p.id === here ? 'here' : '')}>
              <span className="t">{p.title}</span>
              <span className="c">{counts[p.id]}</span>
            </NavLink>
          ))}
          <hr />
          <Link to="/workbench" title="The oscilloscope and the operators; open a signal from Signals to work on it">
            <span className="t">Workbench</span>
            <span className="c">tool</span>
          </Link>
        </nav>

        {here === 'system' && <SystemPlace plan={plan} system={system ?? ''} />}
        {here === 'signals' && <SignalsPlace status={status} focusId={searchParams.get('focus')} published={searchParams.get('published')} onReload={() => setReload((n) => n + 1)} />}
        {here === 'flows' &&
          (signedIn && status ? (
            <FlowComposer
              base={BASE}
              currentId={useFlow.getState().savedId}
              replaces={false}
              onOpen={openFlows}
              onNew={newFlow}
              onDeleted={forget}
              system={{ id: status.system, domain: status.domain, title: status.title }}
              onCount={(all, running) => setFlows({ all, running })}
            />
          ) : (
            <main className="wb-place">
              <p className="wb-muted pad">
                Flows are stored per account. <Link to="/login">Sign in</Link> to see the flows of this system, and to start and stop them.
              </p>
            </main>
          ))}
        {here === 'models' && <ModelsPlace status={status} />}
      </div>

      <footer className="wb-status">
        <span>
          {status ? `${status.example ? 'Example data. ' : ''}System ${status.system}, version ${status.version}, hash ${status.hash.slice(0, 8)}.` : 'Reading the system'}
        </span>
        <span>Times in UTC.</span>
      </footer>
    </div>
  );
}
