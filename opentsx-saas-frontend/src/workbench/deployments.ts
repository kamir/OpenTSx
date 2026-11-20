/** What the workbench knows about deployed flows: the answer of GET /v1/dags of the retrieval server. */

/** What the vector processor reports for one sink (opentsx.dag-status.v1). */
export interface SinkStatus {
  vector: string;
  /** vector: measuring points; series: episodes of a clean signal. */
  kind?: 'vector' | 'series' | string;
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

/** What a Track A or Track B job reports (opentsx.dag-status.v1 with `job`), the latest record per key, as written. */
export interface JobStatus {
  dag: string;
  job: string;
  track: 'A' | 'B' | string;
  planVersion: number;
  state: 'running' | 'idle' | 'stopped' | 'failed' | string;
  /** Input records read, not records written. */
  records: number;
  watermark: string | null;
  waitingFor: string[];
  skippedBlocks: number;
  error?: string;
  at: string;
  site?: string;
  artifact?: { version: string; digest: string };
  /** The input topic, not an output topic. */
  topic?: string;
}

export interface DagList {
  now: string;
  writes: boolean;
  dags: DagState[];
  /** Missing on retrieval servers that do not list job reports. */
  jobs?: JobStatus[];
}

/** A report older than this has nobody behind it: the processor and both job tracks report at least every 30 s. */
const STALE_SECONDS = 120;

/** One line about a sink, in the words of a person watching it. */
export function describeSink(s: SinkStatus): string {
  const what = s.kind === 'series' ? 'clean episodes' : 'measuring points';
  const upTo = s.watermark ? `, data up to ${s.watermark.slice(0, 16).replace('T', ' ')} UTC` : '';
  switch (s.state) {
    case 'running':
      return `running: ${s.records.toLocaleString('en')} ${what} written${upTo}`;
    case 'waiting':
      return `waiting for ${s.waitingForCount ?? 'some'} series that have not sent data yet`;
    case 'failed':
      return `failed, trying again: ${s.error ?? 'no reason given'}`;
    case 'stopped':
      return `stopped after ${s.records.toLocaleString('en')} ${what}`;
    default:
      return s.state;
  }
}

/** Whether a deployed DAG has a processor that reports: its newest report is recent, or the deployment itself is. */
export function hasProcessor(d: DagState, now: string): boolean {
  const newest = Math.max(Date.parse(d.at), ...d.sinks.map((s) => Date.parse(s.at)));
  return (Date.parse(now) - newest) / 1000 <= STALE_SECONDS;
}

/** One line about a job: its state and how many input records it read. */
export function describeJob(j: JobStatus): string {
  const read = `${j.records.toLocaleString('en')} input records`;
  switch (j.state) {
    case 'running':
      return `running: ${read} read`;
    case 'idle':
      return `idle, no new input: ${read} read`;
    case 'stopped':
      return `stopped after ${read}`;
    case 'failed':
      return `failed after ${read}: ${j.error ?? 'no reason given'}`;
    default:
      return `${j.state}: ${read} read`;
  }
}

/** Whether a job that should still report has not done so for two minutes. Stopped and failed are its last reports. */
export function jobIsStale(j: JobStatus, now: string): boolean {
  return j.state !== 'stopped' && j.state !== 'failed' && (Date.parse(now) - Date.parse(j.at)) / 1000 > STALE_SECONDS;
}

/** How long before `now` the report was written. */
export function reportAge(at: string, now: string): string {
  const seconds = Math.round((Date.parse(now) - Date.parse(at)) / 1000);
  if (seconds < 0) return `${-seconds} s ahead of the server clock`;
  if (seconds < 120) return `${seconds} s ago`;
  if (seconds < 7200) return `${Math.floor(seconds / 60)} min ago`;
  if (seconds < 172800) return `${Math.floor(seconds / 3600)} h ago`;
  return `${Math.floor(seconds / 86400)} d ago`;
}

/** Topics several flows write on purpose. */
const SHARED = /^tsx\.[a-z0-9_-]+\.(clean|features)\.v2$/;

/** A flow of the account, as far as its deployment goes. */
export interface DeployedFlow {
  dag: string;
  published: string[];
  /** System and signal the flow writes the clean grade of. */
  system?: string;
  signal?: string;
  status?: string;
  /** True when activating the flow deploys it. */
  runnable?: boolean;
}

/**
 * Says which other running deployment already writes one of the topics. Two deployments on one topic would
 * write every measuring point twice. The topics of a deployment come from the reports of the processor and,
 * before it has reported, from the flow the deployment belongs to.
 */
export function topicConflict(dags: DagState[], own: string, all: string[], flows: DeployedFlow[] = []): string | null {
  // the topic of the clean series is shared by every flow of the domain: there one signal has one writer
  const topics = all.filter((t) => !SHARED.test(t));
  const mine = flows.find((f) => f.dag === own);
  if (mine?.signal && all.some((t) => SHARED.test(t))) {
    const other = flows.find(
      (f) => f.dag !== own && f.system === mine.system && f.signal === mine.signal && f.published.some((t) => SHARED.test(t))
        && (dags.some((d) => d.dag === f.dag && d.desired === 'deployed') || (f.status === 'active' && f.runnable))
    );
    if (other) return `the clean data of ${mine.signal} is already written by the deployment ${other.dag}; stop that flow first`;
  }
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
