/**
 * The shell around a system: a rail with its five places (System, Signals, Flows, Models, Simulate) and the workbench
 * as the tool, a line that says where the system stands, and, when switched on, the guide with the seven steps of the
 * engineering method. Concept: docs/datamodel-2026/14-ENGINEERING-FLOW.md, sections 3.2 and 8.
 */

import { useEffect, useState } from 'react';
import { Link, NavLink, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { RETRIEVAL_BASE, SignalBindingDoc, errorMessage, fetchJson, hasAccessToken, systemsAPI } from '../services/api';
import { useAuthStore } from '../store/authStore';
import FlowComposer from '../workbench/FlowComposer';
import { forgetFlows, newFlow, openFlows } from '../workbench/composerActions';
import { useFlow } from '../workbench/flow';
import ModelsPlace from '../systems/ModelsPlace';
import SimulatePlace from '../systems/SimulatePlace';
import SignalsPlace from '../systems/SignalsPlace';
import SystemPlace from '../systems/SystemPlace';
import { DecisionStatus, Place, PlanDoc, SystemEntry, SystemStatus, countsText, guideSteps, parseStatus, railCounts } from '../systems/signals';
import '../workbench/workbench.css';

const PLACES: { id: Place; title: string; hint: string }[] = [
  { id: 'system', title: 'System', hint: 'What the plant is made of' },
  { id: 'signals', title: 'Signals', hint: 'What the system expects, what is there, what to do next' },
  { id: 'flows', title: 'Flows', hint: 'What prepares the data, and what runs' },
  { id: 'models', title: 'Models', hint: 'What the system predicts' },
  { id: 'simulate', title: 'Simulate', hint: 'Ask the twin what happens when a decision changes' },
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
  const { user, logout, error: accountError } = useAuthStore();
  const [systems, setSystems] = useState<SystemEntry[] | null>(null);
  const [status, setStatus] = useState<SystemStatus | null>(null);
  const [plan, setPlan] = useState<PlanDoc | null>(null);
  const [decisions, setDecisions] = useState<DecisionStatus | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [reload, setReload] = useState(0);
  const [guide, setGuide] = useState(() => remembered('opentsx.guide'));
  const [flows, setFlows] = useState<{ all: number; running: number | null } | null>(null);
  const [bindings, setBindings] = useState<SignalBindingDoc[]>([]);
  const signedIn = hasAccessToken();
  const here = (PLACES.find((p) => p.id === place)?.id ?? 'signals') as Place;

  useEffect(() => setNotice(null), [system, here]);

  useEffect(() => {
    let cancelled = false;
    fetchJson(`${RETRIEVAL_BASE}/v1/systems`)
      .then((doc) => {
        if (cancelled) return;
        const list = (doc as { systems: SystemEntry[] }).systems;
        setSystems(list);
        if (!system && list.length > 0) navigate(`/systems/${list[0].system}/signals`, { replace: true });
      })
      .catch((e) => !cancelled && setError(`The systems could not be read: ${errorMessage(e)}.`));
    return () => {
      cancelled = true;
    };
  }, [system, navigate]);

  useEffect(() => {
    if (!system) return;
    let cancelled = false;
    const get = (what: string) => fetchJson(`${RETRIEVAL_BASE}/v1/systems/${encodeURIComponent(system)}/${what}`);
    /** What could not be read while the page goes on without it. */
    const problems: string[] = [];
    // the working copy of the signed-in user lies over the system document
    const withWorkingCopy = async () => {
      let mine: SignalBindingDoc[] = [];
      if (signedIn) {
        try {
          mine = await systemsAPI.bindings(system);
        } catch (e) {
          problems.push(`your working copy could not be read (${errorMessage(e)}); showing the system document.`);
        }
      }
      if (!cancelled) setBindings(mine);
      const withoutDecisions = (e: unknown) => {
        problems.push(`the decisions could not be read (${errorMessage(e)}).`);
        return null;
      };
      if (mine.length === 0) return Promise.all([get('signals'), get('decisions').catch(withoutDecisions), get('plan')]);
      const post = (what: string) =>
        fetchJson(`${RETRIEVAL_BASE}/v1/systems/${encodeURIComponent(system)}/${what}`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ bindings: mine.map((b) => ({ signal: b.signal, grade: b.grade, source: b.source })) }),
        });
      // the plan with the working copy: a server that cannot lay it over still has the plan of the document
      const plan = post('plan').catch((e) => {
        problems.push(`the plan with your working copy could not be read (${errorMessage(e)}); showing the plan of the system document.`);
        return get('plan');
      });
      return Promise.all([post('signals'), post('decisions').catch(withoutDecisions), plan]);
    };
    withWorkingCopy()
      .then(([signals, decisionDoc, planDoc]) => {
        if (cancelled) return;
        setStatus(parseStatus(signals));
        setDecisions(decisionDoc as DecisionStatus | null);
        setPlan(planDoc as PlanDoc);
        setError(problems.length > 0 ? problems.join(' ') : null);
      })
      .catch((e) => !cancelled && setError(`The system could not be read: ${errorMessage(e)}.`));
    return () => {
      cancelled = true;
    };
  }, [system, reload, signedIn]);

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

  // when the browser kept a tab from opening, the page stays to say so and the workbench keeps the flow it holds
  const open = (ids: number[]) => {
    const held = useFlow.getState().savedId;
    const blocked = openFlows(ids, () => undefined);
    if (blocked === 0) {
      navigate(`/workbench?flow=${ids[0]}`);
      return;
    }
    useFlow.getState().setSavedId(held);
    setNotice(`The browser kept ${blocked} of the flows from opening in tabs of their own, so this page stayed and did not open the first one either. Allow pop-ups for this page, or open them one at a time.`);
  };
  const create = () => {
    newFlow();
    navigate('/workbench');
  };

  const counts = railCounts(status, plan, signedIn ? flows?.all : null, decisions);
  const steps = guideSteps(status, plan, flows?.running ?? null, decisions);
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
      {signedIn && accountError && <div className="wb-error" role="alert">{accountError}</div>}

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
      {notice && <div className="wb-error" role="alert">{notice}</div>}

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
        {here === 'signals' && <SignalsPlace status={status} focusId={searchParams.get('focus')} published={searchParams.get('published')} onReload={() => setReload((n) => n + 1)} base={RETRIEVAL_BASE} bindings={bindings} />}
        {here === 'flows' &&
          (signedIn && status ? (
            <FlowComposer
              base={RETRIEVAL_BASE}
              currentId={useFlow.getState().savedId}
              replaces={false}
              onOpen={open}
              onNew={create}
              onDeleted={(ids) => forgetFlows(ids, false)}
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
        {here === 'models' && <ModelsPlace status={status} decisions={decisions} />}
        {here === 'simulate' && <SimulatePlace system={system ?? ''} base={RETRIEVAL_BASE} />}
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
