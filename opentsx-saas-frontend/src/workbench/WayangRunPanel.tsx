import { useEffect, useRef, useState } from 'react';
import axios from 'axios';
import { WayangCapabilities, WayangDeployment, wayangAPI } from '../services/api';
import { cleanTopic, useFlow } from './flow';
import { WayangConfig } from './types';
import { buildWayangDag, splitEntries } from './wayang';

function reason(error: unknown): string {
  const detail = axios.isAxiosError(error) ? error.response?.data?.detail : null;
  return typeof detail === 'string' ? detail : error instanceof Error ? error.message : String(error);
}

export default function WayangRunPanel({ onSave, profile = 'bounded' }: {
  onSave: () => Promise<number | null>; profile?: 'bounded' | 'continuous';
}) {
  const flow = useFlow();
  const saved = flow.wayang;
  const localConfiguration = useRef(saved);
  const [fields, setFields] = useState(() => ({
    topics: (saved?.inputTopics ?? [flow.sourceTopic]).join('\n'),
    series: (saved?.seriesIds ?? flow.sourceSeries).join('\n'),
    nodeId: saved?.nodeId ?? flow.selectedId ?? '',
    system: saved?.system ?? flow.context?.system ?? '',
    signal: saved?.signal ?? flow.context?.signal ?? '',
    outputTopic: saved?.outputTopic ?? cleanTopic(flow.domain),
  }));
  const [capabilities, setCapabilities] = useState<WayangCapabilities | null>(null);
  const [deployments, setDeployments] = useState<WayangDeployment[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const signedIn = !!localStorage.getItem('access_token');
  const configuration = (value = fields): WayangConfig => ({ inputTopics: splitEntries(value.topics),
    seriesIds: splitEntries(value.series), nodeId: value.nodeId, system: value.system.trim(),
    signal: value.signal.trim(), outputTopic: value.outputTopic.trim(), ...(profile === 'continuous' ? { profile } : {}) });
  const update = (key: keyof typeof fields, value: string) => {
    const next = { ...fields, [key]: value };
    setFields(next);
    const valueToSave = configuration(next);
    localConfiguration.current = valueToSave;
    flow.setWayang(valueToSave);
  };
  useEffect(() => {
    if (saved && saved !== localConfiguration.current) {
      setFields({ topics: saved.inputTopics.join('\n'), series: saved.seriesIds.join('\n'), nodeId: saved.nodeId,
        system: saved.system, signal: saved.signal, outputTopic: saved.outputTopic });
    }
    localConfiguration.current = saved;
  }, [saved]);
  useEffect(() => {
    if (!signedIn) return;
    let active = true;
    wayangAPI.capabilities().then((value) => { if (active) setCapabilities(value); })
      .catch((failure) => { if (active) setCapabilities({ canRun: false, reason: reason(failure) }); });
    wayangAPI.list().then((value) => { if (active) setDeployments(value); })
      .catch((failure) => { if (active) setError(reason(failure)); });
    return () => { active = false; };
  }, [signedIn]);
  const visibleDeployments = deployments.filter((deployment) => (deployment.profile ?? 'bounded') === profile);
  const pending = visibleDeployments.some((deployment) => ['queued', 'running', 'idle', 'stopping'].includes(deployment.state));
  useEffect(() => {
    if (!signedIn || !pending) return;
    let active = true;
    const timer = setInterval(() => {
      wayangAPI.list().then((value) => { if (active) setDeployments(value); })
        .catch((failure) => { if (active) setError(reason(failure)); });
    }, 2000);
    return () => { active = false; clearInterval(timer); };
  }, [signedIn, pending]);
  const available = capabilities?.canRun && (profile === 'bounded' || capabilities.profiles?.includes('continuous'));
  const stopped = profile === 'continuous' && visibleDeployments.some((deployment) =>
    deployment.flowId === flow.savedId && deployment.state === 'stopped');
  const title = profile === 'continuous' ? 'Continuous' : 'Bounded';
  let dagText = '';
  let blocker: string | null = null;
  try {
    dagText = buildWayangDag(flow, configuration(), capabilities?.operators ?? []);
  } catch (failure) {
    blocker = reason(failure);
  }
  const run = async () => {
    setBusy(true);
    setError(null);
    try {
      flow.setWayang(configuration());
      const id = await onSave();
      if (id === null) throw new Error('Save the flow to your account before running it.');
      const exactText = buildWayangDag(useFlow.getState(), configuration(), capabilities?.operators ?? []);
      const deployment = profile === 'continuous' ? await wayangAPI.deploy(id, exactText, profile)
        : await wayangAPI.deploy(id, exactText);
      setDeployments((current) => [deployment, ...current.filter((row) => row.id !== deployment.id)]);
    } catch (failure) {
      setError(reason(failure));
    } finally {
      setBusy(false);
    }
  };
  const stop = async (id: string) => {
    setBusy(true);
    setError(null);
    try {
      const deployment = await wayangAPI.stop(id);
      setDeployments((current) => current.map((item) => item.id === id ? deployment : item));
    } catch (failure) {
      setError(reason(failure));
    } finally {
      setBusy(false);
    }
  };
  return (
    <fieldset className="wb-wayang-run">
      <legend>{title} Wayang on Rust</legend>
      {profile === 'continuous' ? <p>Keep reading each whole topic from its last verified positions.
        Operator state continues between capped cycles. Stop waits for any in-flight cycle to reconcile.
        This local worker profile retains one continuous chain; a replacement requires resetting its disposable state.</p>
        : <p>Run the selected cleaning chain to a frozen Kafka cut. Each selected topic stays whole on one worker.
          This target is separate from the continuous Java processor and Flink execution.</p>}
      {error && <p role="alert">{error}</p>}
      <ul aria-label={`${title} Wayang deployments`}>
        {visibleDeployments.map((deployment) => <li key={deployment.id}>
          <p role="status"><strong>{title} deployment {deployment.id}: {deployment.state}</strong></p>
          {profile === 'continuous' && <>
            <p>Cycle {deployment.currentCycle}; {deployment.completedCycles} completed cycles</p>
            <p>{deployment.inputRecords} total input records; {deployment.outputRecords} total output records</p>
            {deployment.state !== 'stopped' && <button disabled={busy || deployment.stopRequested}
              onClick={() => stop(deployment.id)}>Stop deployment {deployment.id}</button>}
          </>}
          {deployment.error && <p>{deployment.error}</p>}
          <ul>{deployment.assignments.map((assignment) => <li key={assignment.node}>
            <code>{assignment.inputTopic}</code>: {assignment.partitionCount} internal partitions on {assignment.node};{' '}
            {assignment.state ?? deployment.state}; output <code>{assignment.outputTopic}</code>
            <p>{typeof assignment.receipt?.inputRecords === 'number' && typeof assignment.receipt?.outputRecords === 'number'
              ? `${assignment.receipt.inputRecords} input records; ${assignment.receipt.outputRecords} output records`
              : 'Record counts await a verified receipt.'}</p>
            {assignment.receipt && <details><summary>Worker receipt</summary>
              <pre>{JSON.stringify(assignment.receipt, null, 2)}</pre></details>}
            {profile === 'continuous' && <>
              <p>{assignment.cumulativeInputRecords} cumulative input records; {assignment.cumulativeOutputRecords} cumulative output records</p>
              <p>Completed positions: {assignment.positions?.map((position) => `${position.partition}: ${position.next}`).join(', ')}</p>
            </>}
            {assignment.error && <p>{assignment.error}</p>}
          </li>)}</ul>
          <details><summary>Run audit</summary><dl>
            <dt>Source DAG SHA-256</dt><dd><code>{deployment.specHash}</code></dd>
            <dt>Physical plan SHA-256</dt><dd><code>{deployment.planHash}</code></dd>
            <dt>Provenance clock in epoch microseconds</dt><dd>{deployment.createdAtMicros}</dd>
          </dl></details>
        </li>)}
      </ul>
      <label className="wb-param">Whole input topics
        <textarea aria-label="Whole input topics" rows={4} value={fields.topics} onChange={(e) => update('topics', e.target.value)} />
      </label>
      <label className="wb-param">Selected series IDs
        <textarea aria-label="Selected series IDs" rows={4} value={fields.series} onChange={(e) => update('series', e.target.value)} />
      </label>
      <p className="wb-muted">Enter one to three distinct topics and the canonical series IDs selected across them.
        Partition positions come from the broker inventory.</p>
      <label className="wb-param">Output step
        <select aria-label="Rust output step" value={fields.nodeId} onChange={(e) => update('nodeId', e.target.value)}>
          <option value="">Choose the final cleaning step</option>
          {flow.nodes.filter((node) => node.op !== 'source').map((node) => <option key={node.id} value={node.id}>{node.label}</option>)}
        </select>
      </label>
      <label className="wb-param">Output system
        <input aria-label="Output system" value={fields.system} onChange={(e) => update('system', e.target.value)} />
      </label>
      <label className="wb-param">Output signal
        <input aria-label="Output signal" value={fields.signal} onChange={(e) => update('signal', e.target.value)} />
      </label>
      <label className="wb-param">Output topic
        <input aria-label="Rust output topic" value={fields.outputTopic} onChange={(e) => update('outputTopic', e.target.value)} />
      </label>
      {!signedIn && <p>Sign in before running a {profile} deployment.</p>}
      {signedIn && !capabilities?.canRun && <p>{capabilities?.reason ?? `Checking the ${profile} target.`}</p>}
      {signedIn && capabilities?.canRun && !available && <p>This installation does not advertise the continuous Rust profile.</p>}
      {blocker && <p className="wb-muted">{blocker}</p>}
      {stopped ? <p>This deployment is stopped. Reset disposable worker state before deploying a replacement.</p>
        : <button disabled={!signedIn || !available || !!blocker || busy} onClick={run}>
          {busy ? `Queuing ${profile} run` : profile === 'continuous' ? 'Run continuously with Wayang' : 'Run bounded with Wayang'}
        </button>}
      {dagText && <details><summary>Review {profile} DAG</summary><pre data-testid="wayang-dag-preview">{dagText}</pre></details>}
    </fieldset>
  );
}
