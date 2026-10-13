/**
 * "Publish as clean signal": makes the selected step of the flow the source of the clean grade of the signal the
 * workbench was opened for. One step instead of three (publish, save, deploy): it says what will happen, what the
 * processor can run, and where the data goes. Concept: docs/datamodel-2026/14-ENGINEERING-FLOW.md, section 8.3.
 */

import { useEffect, useState } from 'react';
import axios from 'axios';
import { dagsAPI, flowsAPI } from '../services/api';
import { DagList, topicConflict } from './deployments';
import { cleanChecks, cleanTopic, dagName, isCleanSink, toDagYaml, useFlow } from './flow';
import { summarize } from './flows';

interface Props {
  /** Retrieval server, asked for what is deployed. */
  base: string;
  /** Stores the flow in the account and gives its number, or null when that failed. */
  onSave: () => Promise<number | null>;
  /** Called when the flow is published; `started` says whether the processor runs it. */
  onDone: (started: boolean) => void;
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

export default function PublishClean({ base, onSave, onDone, onClose }: Props) {
  const flow = useFlow();
  const [access, setAccess] = useState<{ canRun: boolean; reason: string | null } | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const signedIn = !!localStorage.getItem('access_token');
  const context = flow.context;
  const node = flow.nodes.find((n) => n.id === flow.selectedId);

  useEffect(() => {
    if (!signedIn) return;
    dagsAPI.capabilities().then(setAccess).catch(() => setAccess({ canRun: false, reason: 'The backend does not say whether this account may deploy.' }));
  }, [signedIn]);

  if (!context || !node) return null;
  const checks = cleanChecks(flow.nodes, flow.sourceSeries, context, node.id);
  const ready = checks.every((c) => c.ok);
  const topic = cleanTopic(flow.domain);

  const publish = async (start: boolean) => {
    setBusy(start ? 'Publishing and starting' : 'Saving');
    setError(null);
    try {
      // one signal has one clean source: the selected step, and no other
      for (const n of useFlow.getState().nodes) if (isCleanSink(n) && n.id !== node.id) flow.setSink(n.id, null);
      flow.setSink(node.id, topic);
      const id = await onSave();
      if (id === null) throw new Error('the flow could not be saved to your account');
      if (start) {
        const now = useFlow.getState();
        const res = await fetch(`${base.replace(/\/$/, '')}/v1/dags`, { signal: AbortSignal.timeout(10_000) }).catch(() => null);
        const dags = res?.ok ? ((await res.json()) as DagList).dags : [];
        const conflict = topicConflict(dags, dagName(now.domain, now.track, id), [topic], (await flowsAPI.list()).map(summarize));
        if (conflict) throw new Error(conflict);
        await dagsAPI.deploy(toDagYaml(now));
        await flowsAPI.patch(id, { status: 'active' });
      }
      onDone(start);
    } catch (e) {
      setError(`${start ? 'Publishing' : 'Saving'} failed: ${reasonOf(e)}.`);
      setBusy(null);
    }
  };

  return (
    <div className="wb-publish" role="dialog" aria-label="Publish as clean signal">
      <h2>Publish as clean signal</h2>
      <p>
        <b>{node.label}</b> becomes the clean data of <b>{context.signal}</b> ({context.systemTitle}).
      </p>
      <ul className="wb-findings" aria-label="What the processor needs">
        {checks.map((c, i) => (
          <li key={i} className={c.ok ? 'ok' : 'error'}>
            <b>{c.ok ? 'Ready' : 'Missing'}</b> {c.text}
          </li>
        ))}
        <li className={signedIn ? (access?.canRun ? 'ok' : 'warning') : 'error'}>
          <b>{!signedIn ? 'Missing' : access?.canRun ? 'Ready' : 'Note'}</b>{' '}
          {!signedIn
            ? 'Sign in: a published flow is stored in an account.'
            : access?.canRun
              ? 'You may start flows on this installation.'
              : `You can save the flow; starting it is not open to this account (${access?.reason ?? 'checking'}).`}
        </li>
      </ul>
      <p className="wb-muted">
        Started, the processor computes the clean series from the beginning of the raw data and follows it. The series goes
        to {topic} with the tags of the raw series plus system and signal{context.origin ? `, and keeps the origin ${context.origin.toLowerCase()}` : ''}.
        The page "Signals" finds it by those tags. The system document in the repository is not changed.
      </p>
      {error && <div className="wb-error" role="alert">{error}</div>}
      <div className="wb-oprow">
        <button className="wb-primary" disabled={!ready || !signedIn || !access?.canRun || !!busy} onClick={() => publish(true)}>
          {busy === 'Publishing and starting' ? 'Publishing' : 'Publish and start'}
        </button>
        <button disabled={!ready || !signedIn || !!busy} onClick={() => publish(false)} title="Stores the flow as the clean source of the signal; start it later under Flows">
          Save without starting
        </button>
        <button disabled={!!busy} onClick={onClose}>Cancel</button>
      </div>
    </div>
  );
}
