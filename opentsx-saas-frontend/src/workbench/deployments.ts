/** What the workbench knows about deployed flows: the answer of GET /v1/dags of the retrieval server. */

/** What the vector processor reports for one sink (opentsx.dag-status.v1). */
export interface SinkStatus {
  vector: string;
  topic: string;
  state: 'starting' | 'waiting' | 'running' | 'failed' | 'stopped' | string;
  records: number;
  watermark?: string;
  waitingForCount?: number;
  skippedBlocks?: number;
  error?: string;
  at: string;
}

export interface DagState {
  dag: string;
  desired: 'deployed' | 'stopped' | string;
  by: string;
  at: string;
  refused?: string;
  sinks: SinkStatus[];
}

export interface DagList {
  now: string;
  writes: boolean;
  dags: DagState[];
}

/** A deployed DAG whose newest report is older than this has no processor behind it. */
const STALE_SECONDS = 120;

/** One line about a sink, in the words of a person watching it. */
export function describeSink(s: SinkStatus): string {
  const upTo = s.watermark ? `, data up to ${s.watermark.slice(0, 16).replace('T', ' ')} UTC` : '';
  switch (s.state) {
    case 'running':
      return `running: ${s.records.toLocaleString('en')} measuring points written${upTo}`;
    case 'waiting':
      return `waiting for ${s.waitingForCount ?? 'some'} series that have not sent data yet`;
    case 'failed':
      return `failed, trying again: ${s.error ?? 'no reason given'}`;
    case 'stopped':
      return `stopped after ${s.records.toLocaleString('en')} measuring points`;
    default:
      return s.state;
  }
}

/** Whether a deployed DAG has a processor that reports: its newest report is recent, or the deployment itself is. */
export function hasProcessor(d: DagState, now: string): boolean {
  const newest = Math.max(Date.parse(d.at), ...d.sinks.map((s) => Date.parse(s.at)));
  return (Date.parse(now) - newest) / 1000 <= STALE_SECONDS;
}

/** A flow of the account, as far as its deployment goes. */
export interface DeployedFlow {
  dag: string;
  published: string[];
  status?: string;
  /** True when activating the flow deploys it. */
  runnable?: boolean;
}

/**
 * Says which other running deployment already writes one of the topics. Two deployments on one topic would
 * write every measuring point twice. The topics of a deployment come from the reports of the processor and,
 * before it has reported, from the flow the deployment belongs to.
 */
export function topicConflict(dags: DagState[], own: string, topics: string[], flows: DeployedFlow[] = []): string | null {
  for (const d of dags) {
    if (d.dag === own || d.desired !== 'deployed') continue;
    const written = [...d.sinks.map((s) => s.topic), ...flows.filter((f) => f.dag === d.dag).flatMap((f) => f.published)];
    const shared = written.find((t) => topics.includes(t));
    if (shared) return `${shared} is already written by the deployment ${d.dag}; stop that one or publish to another topic`;
  }
  // the list of deployments lags a few seconds behind: an active flow counts before its deployment shows there
  for (const f of flows) {
    if (f.dag === own || f.status !== 'active' || !f.runnable) continue;
    const shared = f.published.find((t) => topics.includes(t));
    if (shared) return `${shared} is already written by the deployment ${f.dag}; pause that flow or publish to another topic`;
  }
  return null;
}
