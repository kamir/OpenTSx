/**
 * Deploying the flow to the vector processor and watching what it does: the "Keep running on KafScale" part of the
 * Smart Topic tab. Deploy and stop go through the backend (accounts); the state is read from the retrieval server.
 */

import axios from 'axios';
import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { dagsAPI, flowsAPI } from '../services/api';
import { DagList, describeSink, hasProcessor, topicConflict } from './deployments';
import { summarize } from './flows';
import { DeployCheck, dagName, flowIdOfDag, toDagYaml, useFlow } from './flow';

interface Props {
  /** Retrieval server the state is read from. */
  base: string;
  /** Why the flow cannot be deployed, or null. */
  blocker: string | null;
  /** What the processor needs of the flow, step by step. */
  checks: DeployCheck[];
  /** Builds and publishes a vector of the loaded series; offered while the flow has none. */
  onMakeVector?: () => void;
  /** Stores the flow in the account and gives its number, or null when that failed. */
  onSave: () => Promise<number | null>;
}

export default function DeploymentsPanel({ base, blocker, checks, onMakeVector, onSave }: Props) {
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

  // A deployment carries the number of its flow, so the flow is stored first; its status follows the deployment.
  const deploy = async () => {
    const id = await onSave();
    if (id === null) throw new Error('the flow could not be saved to your account');
    const now = useFlow.getState();
    const topics = now.nodes.flatMap((n) => (n.sinkTopic ? [n.sinkTopic] : []));
    const stored = (await flowsAPI.list()).map(summarize);
    const conflict = topicConflict(list?.dags ?? [], dagName(now.domain, now.track, id), topics, stored);
    if (conflict) throw new Error(conflict);
    const answer = await dagsAPI.deploy(toDagYaml(now));
    await flowsAPI.patch(id, { status: 'active' });
    return answer;
  };
  const stop = async (dag: string) => {
    const answer = await dagsAPI.stop(dag);
    const id = flowIdOfDag(dag);
    // the flow may belong to another account or be deleted: the deployment is stopped either way
    if (id !== null) await flowsAPI.patch(id, { status: 'paused' }).catch(() => undefined);
    return answer;
  };

  const flow = useFlow();
  const own = flow.savedId === null ? null : dagName(flow.domain, flow.track, flow.savedId);
  const allowed = signedIn && !!access?.canRun;
  return (
    <fieldset>
      <legend>Keep running on KafScale</legend>
      <p className="wb-muted">
        Deploying hands the flow to the vector processor. It computes the vector from the beginning of the source topic and
        then follows it: new episodes give new measuring points. Deploy saves the flow first: the deployment carries its
        number. A flow deployed again replaces its own deployment and starts over.
      </p>
      <ul className="wb-findings" aria-label="What a deployment needs">
        <li className={allowed ? 'ok' : signedIn && !access ? 'info' : 'error'}>
          <b>{allowed ? 'Ready' : signedIn && !access ? 'Wait' : 'Missing'}</b>{' '}
          {allowed
            ? 'You are signed in as an administrator.'
            : !signedIn
              ? 'Sign in as an administrator.'
              : (access?.reason ?? 'Checking your account.')}
        </li>
        {checks.map((c, i) => (
          <li key={i} className={c.ok ? 'ok' : 'error'}>
            <b>{c.ok ? 'Ready' : 'Missing'}</b> {c.text}
          </li>
        ))}
      </ul>
      {onMakeVector && (
        <button onClick={onMakeVector} title="Adds an Align to vector node on the source bucket and publishes it">
          Create a vector of the loaded series
        </button>
      )}{' '}
      <button
        className="wb-primary"
        disabled={!allowed || !!blocker || !!busy}
        onClick={() => act('Deploy', deploy, (dag) => `Saved and deployed as ${dag}. The processor takes it up within a few seconds.`)}
      >
        {busy === 'Deploy' ? 'Deploying' : 'Deploy'}
      </button>
      {!signedIn && (
        <p className="wb-muted">
          <Link to="/login" target="_blank" rel="noopener">Sign in</Link>, then reload this tab. The flow stays in the browser only
          until you save it.
        </p>
      )}
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
                <b>{d.dag}{d.dag === own ? ' (this flow)' : ''}</b>
                <span>
                  {d.desired} by {d.by}, {d.at.slice(0, 16).replace('T', ' ')} UTC
                </span>
                {d.desired === 'deployed' && (
                  <button disabled={!allowed || !!busy} onClick={() => act('Stop', () => stop(d.dag), (dag) => `${dag} is stopped.`)}>
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
