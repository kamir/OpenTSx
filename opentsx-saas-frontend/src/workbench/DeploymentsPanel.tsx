/**
 * Deploying the flow to the vector processor and watching what it does: the "Keep running on KafScale" part of the
 * Smart Topic tab. Deploy and stop go through the backend (accounts); the state is read from the retrieval server.
 */

import axios from 'axios';
import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { dagsAPI } from '../services/api';
import { DagList, describeSink, hasProcessor } from './deployments';
import { toDagYaml, useFlow } from './flow';

interface Props {
  /** Retrieval server the state is read from. */
  base: string;
  /** Why the flow cannot be deployed, or null. */
  blocker: string | null;
}

export default function DeploymentsPanel({ base, blocker }: Props) {
  const [access, setAccess] = useState<{ canRun: boolean; reason: string | null } | null>(null);
  const [list, setList] = useState<DagList | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [message, setMessage] = useState<{ ok: boolean; text: string } | null>(null);
  const signedIn = !!localStorage.getItem('access_token');
  const url = `${base.replace(/\/$/, '')}/v1/dags`;

  // Deploying needs an account that is allowed to: the backend says whether this one is.
  useEffect(() => {
    if (!signedIn) return;
    let cancelled = false;
    dagsAPI
      .capabilities()
      .then((c) => !cancelled && setAccess(c))
      .catch(() => !cancelled && setAccess({ canRun: false, reason: 'Your session has expired or the backend cannot be reached. Sign in again.' }));
    return () => {
      cancelled = true;
    };
  }, [signedIn]);

  const refresh = useCallback(async () => {
    try {
      const res = await fetch(url);
      setList(res.ok ? ((await res.json()) as DagList) : null);
    } catch {
      setList(null);
    }
  }, [url]);

  // The state changes without this page doing anything: look again every few seconds while the tab is open.
  useEffect(() => {
    refresh();
    const timer = setInterval(refresh, 5000);
    return () => clearInterval(timer);
  }, [refresh]);

  const act = async (what: string, call: () => Promise<{ dag: string }>, done: (dag: string) => string) => {
    setBusy(what);
    setMessage(null);
    try {
      const answer = await call();
      setMessage({ ok: true, text: done(answer.dag) });
      await refresh();
    } catch (e) {
      const detail = axios.isAxiosError(e) ? e.response?.data?.detail : null;
      const expired = axios.isAxiosError(e) && e.response?.status === 401;
      setMessage({
        ok: false,
        text: expired
          ? 'Your session has expired. Sign in again in a new tab, then try again: the flow stays here.'
          : `${what} failed: ${typeof detail === 'string' ? detail : e instanceof Error ? e.message : e}`,
      });
    } finally {
      setBusy(null);
    }
  };

  const allowed = signedIn && !!access?.canRun;
  return (
    <fieldset>
      <legend>Keep running on KafScale</legend>
      <p className="wb-muted">
        Deploying hands the flow to the vector processor. It computes the vector from the beginning of the source topic and
        then follows it: new episodes give new measuring points. A flow deployed again under the same track replaces the
        earlier one and starts over.
      </p>
      <button
        className="wb-primary"
        disabled={!allowed || !!blocker || !!busy}
        onClick={() => act('Deploy', () => dagsAPI.deploy(toDagYaml(useFlow.getState())), (dag) => `Deployed as ${dag}. The processor takes it up within a few seconds.`)}
      >
        {busy === 'Deploy' ? 'Deploying' : 'Deploy'}
      </button>
      {!signedIn ? (
        <p className="wb-muted">
          Deploying needs an account. <Link to="/login" target="_blank" rel="noopener">Sign in</Link>, then reload this tab.
        </p>
      ) : (
        access && !access.canRun && <p className="wb-muted">{access.reason}</p>
      )}
      {blocker && <p className="wb-muted">{blocker}</p>}
      {message && (
        <div className={message.ok ? 'wb-note' : 'wb-error'} role={message.ok ? 'status' : 'alert'}>{message.text}</div>
      )}

      {list === null ? (
        <p className="wb-muted">The state of the deployments cannot be read from {url}.</p>
      ) : list.dags.length === 0 ? (
        <p className="wb-muted">Nothing is deployed in this domain.</p>
      ) : (
        <ul className="wb-deployments" aria-label="Deployed flows">
          {list.dags.map((d) => (
            <li key={d.dag} className={d.desired}>
              <div className="head">
                <b>{d.dag}</b>
                <span>
                  {d.desired} by {d.by}, {d.at.slice(0, 16).replace('T', ' ')} UTC
                </span>
                {d.desired === 'deployed' && (
                  <button disabled={!allowed || !!busy} onClick={() => act('Stop', () => dagsAPI.stop(d.dag), (dag) => `${dag} is stopped.`)}>
                    Stop
                  </button>
                )}
              </div>
              {d.refused && <p className="bad">The processor refused it: {d.refused}</p>}
              {d.desired === 'deployed' && !d.refused && !hasProcessor(d, list.now) && (
                <p className="bad">No vector processor has reported for two minutes. Is one running for this domain?</p>
              )}
              {d.sinks.map((s) => (
                <p key={s.vector} className={s.state}>
                  <code>{s.topic}</code> {describeSink(s)}
                  {s.skippedBlocks ? `; ${s.skippedBlocks} episodes skipped (duplicates or out of order)` : ''}
                </p>
              ))}
            </li>
          ))}
        </ul>
      )}
    </fieldset>
  );
}
