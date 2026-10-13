/** What the "Open flow" dialog shows of the flows stored in the account. */

import { WORKBENCH_KIND, parseNodes, slug } from './flow';
import { VECTOR_OP } from './vector';

/** A flow as the backend lists it (GET /api/v1/flows). */
export interface StoredFlow {
  id: number;
  name: string;
  description?: string | null;
  status?: string;
  updated_at?: string;
  definition?: object | null;
}

export interface FlowSummary {
  id: number;
  name: string;
  description: string;
  updatedAt: string;
  /** False for flows the workbench cannot open; `reason` then says why. */
  openable: boolean;
  reason?: string;
  track: string;
  domain: string;
  sourceTopic: string;
  /** Ids of the series the flow was built on; empty when it was built on a file or the sample bucket. */
  sourceSeries: string[];
  /** Labels of the buckets after the source, in the order they were made. */
  steps: string[];
  published: string[];
  vectors: number;
  /** Name under which the flow is deployed, when it publishes a topic. */
  dag: string;
}

function str(v: unknown): string {
  return typeof v === 'string' ? v : '';
}

export function summarize(flow: StoredFlow): FlowSummary {
  const def = (flow.definition ?? {}) as Record<string, unknown>;
  const base = {
    id: flow.id,
    name: flow.name,
    description: flow.description ?? '',
    updatedAt: flow.updated_at ?? '',
    track: str(def.track),
    domain: str(def.domain),
    sourceTopic: str(def.sourceTopic),
    sourceSeries: Array.isArray(def.sourceSeries) ? def.sourceSeries.filter((i): i is string => typeof i === 'string') : [],
  };
  const none = { steps: [], published: [], vectors: 0, dag: '' };
  if (def.kind !== WORKBENCH_KIND) {
    return { ...base, ...none, openable: false, reason: 'Made in the flow builder, not in the workbench.' };
  }
  const nodes = parseNodes(def.nodes);
  if (!nodes) {
    return { ...base, ...none, openable: false, reason: 'Its node list cannot be read by this version of the workbench.' };
  }
  const published = nodes.filter((n) => n.sinkTopic).map((n) => n.sinkTopic!);
  return {
    ...base,
    openable: true,
    steps: nodes.filter((n) => n.op !== 'source').map((n) => n.label),
    published,
    vectors: nodes.filter((n) => n.op === VECTOR_OP).length,
    dag: published.length > 0 ? `${slug(base.domain)}-${slug(base.track)}-smart` : '',
  };
}

export interface FlowFilter {
  text: string;
  domain: string | null;
  /** 'published': flows with a Smart Topic; 'data': flows that name their series. */
  only: 'published' | 'data' | null;
}

export const NO_FLOW_FILTER: FlowFilter = { text: '', domain: null, only: null };

export function domainFacets(flows: FlowSummary[]): string[] {
  return [...new Set(flows.map((f) => f.domain).filter(Boolean))].sort();
}

export function filterFlows(flows: FlowSummary[], f: FlowFilter): FlowSummary[] {
  const words = f.text.toLowerCase().split(/\s+/).filter(Boolean);
  return flows.filter((s) => {
    if (f.domain && s.domain !== f.domain) return false;
    if (f.only === 'published' && s.published.length === 0) return false;
    if (f.only === 'data' && s.sourceSeries.length === 0) return false;
    if (words.length === 0) return true;
    const hay = `${s.id} ${s.name} ${s.description} ${s.track} ${s.domain} ${s.sourceTopic} ${s.steps.join(' ')} ${s.published.join(' ')}`.toLowerCase();
    return words.every((w) => hay.includes(w));
  });
}

/** "2026-10-13 10:02" of a backend timestamp, which carries no zone and means UTC. */
export function stamp(iso: string): string {
  return iso ? iso.slice(0, 16).replace('T', ' ') : '';
}
