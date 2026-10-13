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
