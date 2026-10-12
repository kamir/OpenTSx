/**
 * The processing flow of the workbench: the track of the Swing MacroTrackerFrame
 * as a graph of operator nodes, with evaluation on the loaded bucket and the
 * exports (opentsx.dag.v1 YAML, Processing Flow Descriptor JSON, Java code).
 */

import { create } from 'zustand';
import { OP_BY_ID, defaultParams } from './ops';
import { Bucket, FlowNode, Params, SourceInfo } from './types';

export const SOURCE_ID = 'source';

export interface FlowState {
  track: string;
  domain: string;
  sourceTopic: string;
  source: Bucket | null;
  sourceInfo: SourceInfo | null;
  nodes: FlowNode[];
  selectedId: string | null;
  counter: number;

  setTrack: (track: string) => void;
  setDomain: (domain: string) => void;
  setSourceTopic: (topic: string) => void;
  loadBucket: (bucket: Bucket, info: SourceInfo) => void;
  select: (id: string) => void;
  applyOp: (opId: string, params?: Params) => string | null;
  updateParams: (id: string, params: Params) => void;
  removeNode: (id: string) => void;
  rewire: (id: string, inputId: string) => boolean;
  moveNode: (id: string, pos: { x: number; y: number }) => void;
  setSink: (id: string, topic: string | null) => void;
}

export function slug(text: string): string {
  return text
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '') || 'x';
}

export function defaultSinkTopic(domain: string, label: string): string {
  return `tsx.${slug(domain)}.smart.${slug(label)}.metrics.v2`;
}

/** All nodes downstream of id, id included. */
export function descendants(nodes: FlowNode[], id: string): Set<string> {
  const out = new Set<string>([id]);
  let grew = true;
  while (grew) {
    grew = false;
    for (const n of nodes) {
      if (n.input && out.has(n.input) && !out.has(n.id)) {
        out.add(n.id);
        grew = true;
      }
    }
  }
  return out;
}

/** Nodes from the source down to id, in execution order. */
export function pathTo(nodes: FlowNode[], id: string): FlowNode[] {
  const byId = new Map(nodes.map((n) => [n.id, n]));
  const path: FlowNode[] = [];
  let cur = byId.get(id);
  while (cur) {
    path.unshift(cur);
    cur = cur.input ? byId.get(cur.input) : undefined;
  }
  return path;
}

/** Renames the derived buckets after a parameter or wiring change, parents first. */
function relabel(nodes: FlowNode[]): FlowNode[] {
  const byId = new Map(nodes.map((n) => [n.id, { ...n }]));
  const done = new Set<string>();
  const visit = (id: string): string => {
    const n = byId.get(id)!;
    if (done.has(id)) return n.label;
    done.add(id);
    if (n.input && byId.has(n.input)) {
      const op = OP_BY_ID[n.op];
      const parent = visit(n.input);
      if (op) {
        const old = n.label;
        n.label = op.prefix + parent;
        // A sink topic that still has its default name follows the label.
        if (n.sinkTopic && n.sinkTopic.includes(`.smart.${slug(old)}.`)) {
          n.sinkTopic = n.sinkTopic.replace(`.smart.${slug(old)}.`, `.smart.${slug(n.label)}.`);
        }
      }
    }
    return n.label;
  };
  for (const n of nodes) visit(n.id);
  return nodes.map((n) => byId.get(n.id)!);
}

export const useFlow = create<FlowState>((set, get) => ({
  track: 'EXP2',
  domain: 'lab',
  sourceTopic: 'tsx.lab.observations.v2',
  source: null,
  sourceInfo: null,
  nodes: [],
  selectedId: null,
  counter: 0,

  setTrack: (track) => set({ track }),
  setDomain: (domain) =>
    set((s) => ({
      domain,
      sourceTopic:
        s.sourceTopic === `tsx.${slug(s.domain)}.observations.v2` ? `tsx.${slug(domain)}.observations.v2` : s.sourceTopic,
    })),
  setSourceTopic: (sourceTopic) => set({ sourceTopic }),

  loadBucket: (bucket, info) => {
    cache.clear();
    set({
      source: bucket,
      sourceInfo: info,
      nodes: [{ id: SOURCE_ID, op: 'source', params: {}, input: null, label: bucket.name }],
      selectedId: SOURCE_ID,
      counter: 0,
    });
  },

  select: (id) => set({ selectedId: id }),

  applyOp: (opId, params) => {
    const s = get();
    const op = OP_BY_ID[opId];
    const parent = s.nodes.find((n) => n.id === s.selectedId);
    if (!op || !parent) return null;
    const counter = s.counter + 1;
    const id = `n${counter}`;
    // The same operation applied twice to one bucket needs distinct bucket names.
    let label = op.prefix + parent.label;
    for (let k = 2; s.nodes.some((n) => n.label === label); k++) label = `${op.prefix}${parent.label}_${k}`;
    const node: FlowNode = {
      id,
      op: opId,
      params: { ...defaultParams(op), ...(params ?? {}) },
      input: parent.id,
      label,
    };
    set({ nodes: [...s.nodes, node], selectedId: id, counter });
    return id;
  },

  updateParams: (id, params) =>
    set((s) => ({ nodes: s.nodes.map((n) => (n.id === id ? { ...n, params: { ...n.params, ...params } } : n)) })),

  removeNode: (id) => {
    if (id === SOURCE_ID) return;
    set((s) => {
      const gone = descendants(s.nodes, id);
      const removed = s.nodes.find((n) => n.id === id);
      return {
        nodes: s.nodes.filter((n) => !gone.has(n.id)),
        selectedId: s.selectedId && gone.has(s.selectedId) ? (removed?.input ?? SOURCE_ID) : s.selectedId,
      };
    });
  },

  rewire: (id, inputId) => {
    const s = get();
    // The source has no input, and a node may not read from its own downstream.
    if (id === SOURCE_ID || id === inputId || descendants(s.nodes, id).has(inputId)) return false;
    set({ nodes: relabel(s.nodes.map((n) => (n.id === id ? { ...n, input: inputId } : n))) });
    return true;
  },

  moveNode: (id, pos) => set((s) => ({ nodes: s.nodes.map((n) => (n.id === id ? { ...n, pos } : n)) })),

  setSink: (id, topic) =>
    set((s) => ({ nodes: s.nodes.map((n) => (n.id === id ? { ...n, sinkTopic: topic ?? undefined } : n)) })),
}));

// ---------------------------------------------------------------- evaluation

const cache = new Map<string, Bucket>();

function signature(nodes: FlowNode[], id: string): string {
  return pathTo(nodes, id)
    .map((n) => `${n.op}(${JSON.stringify(n.params)})`)
    .join('>');
}

/** Runs the flow from the source down to the node. Results are cached per operator chain. */
export function evaluate(source: Bucket | null, nodes: FlowNode[], id: string): Bucket | null {
  if (!source) return null;
  const path = pathTo(nodes, id);
  if (path.length === 0 || path[0].op !== 'source') return null;
  let bucket = source;
  for (let i = 1; i < path.length; i++) {
    const key = signature(nodes, path[i].id);
    let next = cache.get(key);
    if (!next) {
      const op = OP_BY_ID[path[i].op];
      if (!op) return null;
      next = op.apply(bucket, path[i].params);
      if (cache.size > 200) cache.clear();
      cache.set(key, next);
    }
    bucket = next;
  }
  return bucket;
}

// ---------------------------------------------------------------- exports

function yamlScalar(v: unknown): string {
  if (typeof v === 'number') return String(v);
  const s = String(v);
  return /^[A-Za-z0-9_./-]+$/.test(s) && !/^(true|false|null|yes|no|on|off|~)$/i.test(s) && !/^[0-9.-]/.test(s)
    ? s
    : JSON.stringify(s);
}

function yamlParams(params: Params): string {
  const parts = Object.entries(params).map(([k, v]) => `${k}: ${yamlScalar(v)}`);
  return `{ ${parts.join(', ')} }`;
}

/** Parameters as written into the DAG. Catalog operators get the shape of the catalog. */
function dagParams(node: FlowNode): Params | string {
  if (node.op === 'dq.plausible-range') {
    return `{ checks: [PLAUSIBLE_RANGE], min: ${node.params.min}, max: ${node.params.max}, mode: ${node.params.mode} }`;
  }
  return node.params;
}

export interface Finding {
  level: 'error' | 'warning' | 'info';
  text: string;
}

/** Checks whether the flow can be handed over as a Smart Topic definition. */
export function validate(nodes: FlowNode[]): Finding[] {
  const out: Finding[] = [];
  const sinks = nodes.filter((n) => n.sinkTopic);
  if (nodes.length <= 1) out.push({ level: 'info', text: 'The flow has no operators yet. Apply an operation to the bucket.' });
  if (sinks.length === 0) {
    out.push({ level: 'error', text: 'No node is published. Select a node and choose "Publish as Smart Topic".' });
  }
  const topics = new Map<string, string>();
  for (const s of sinks) {
    if (!/^[A-Za-z0-9._-]+$/.test(s.sinkTopic!)) {
      out.push({ level: 'error', text: `Topic "${s.sinkTopic}" of ${s.label} has characters a topic name may not hold.` });
    }
    const other = topics.get(s.sinkTopic!);
    if (other) out.push({ level: 'error', text: `${other} and ${s.label} write to the same topic ${s.sinkTopic}.` });
    topics.set(s.sinkTopic!, s.label);
    const missing = pathTo(nodes, s.id).filter((n) => n.op !== 'source' && !OP_BY_ID[n.op]?.inCatalog);
    if (missing.length > 0) {
      out.push({
        level: 'warning',
        text: `${s.label} uses ${[...new Set(missing.map((n) => n.op))].join(', ')}: not in the stream operator catalog, a runtime must provide them.`,
      });
    }
  }
  const used = new Set<string>();
  for (const s of sinks) for (const n of pathTo(nodes, s.id)) used.add(n.id);
  const unused = nodes.filter((n) => n.op !== 'source' && !used.has(n.id));
  if (sinks.length > 0 && unused.length > 0) {
    out.push({ level: 'info', text: `${unused.length} node(s) feed no published topic and are left out of the DAG.` });
  }
  return out;
}

/** The flow as opentsx.dag.v1 (docs/datamodel-2026/11-SMART-TOPIC-PROZESSOR-DEVOPS.md, section 5). */
export function toDagYaml(s: Pick<FlowState, 'track' | 'domain' | 'sourceTopic' | 'nodes'>): string {
  const sinks = s.nodes.filter((n) => n.sinkTopic);
  const keep = new Set<string>();
  for (const sink of sinks) for (const n of pathTo(s.nodes, sink.id)) keep.add(n.id);
  // Without a sink the whole flow is written, so the draft can be read.
  const nodes = s.nodes.filter((n) => n.op !== 'source' && (sinks.length === 0 || keep.has(n.id)));
  const lines = [
    '# opentsx.dag.v1, written by the OpenTSx web workbench',
    `dag: ${yamlScalar(`${slug(s.domain)}-${slug(s.track)}-smart`)}`,
    'version: 1',
    'source:',
    '  kind: kafscale-broker',
    `  topics: [${yamlScalar(s.sourceTopic)}]`,
    'checkpoint: { backend: file }',
    'nodes:',
    '  - id: decode',
    '    op: decode.avro-soe',
  ];
  for (const n of nodes) {
    const op = OP_BY_ID[n.op];
    const params = dagParams(n);
    lines.push(`  - id: ${n.id}`);
    lines.push(`    op: ${op?.dagOp ?? n.op}${op?.inCatalog ? '' : '   # not in the stream operator catalog'}`);
    lines.push(`    in: [${n.input === SOURCE_ID ? 'decode' : n.input}]`);
    if (typeof params === 'string') lines.push(`    params: ${params}`);
    else if (Object.keys(params).length > 0) lines.push(`    params: ${yamlParams(params)}`);
  }
  if (nodes.length === 0) lines.push('  # no operators yet');
  lines.push(sinks.length === 0 ? 'sinks: []' : 'sinks:');
  for (const sink of sinks) {
    lines.push(`  - { from: ${sink.id === SOURCE_ID ? 'decode' : sink.id}, topic: ${yamlScalar(sink.sinkTopic!)} }`);
  }
  return lines.join('\n') + '\n';
}

/**
 * Processing Flow Descriptor of TASK-005 for the path down to one node.
 * The descriptor is a linear list of steps, so it describes one branch.
 */
export function toPfdJson(s: Pick<FlowState, 'track' | 'sourceTopic' | 'nodes'>, nodeId: string): string {
  const path = pathTo(s.nodes, nodeId);
  const last = path[path.length - 1];
  const pfd = {
    name: last ? `${s.track}: ${last.label}` : s.track,
    version: '1.0.0',
    input: { type: 'KAFKA', topic: s.sourceTopic, schema: 'Observation' },
    steps: path
      .filter((n) => n.op !== 'source')
      .map((n) => ({ id: n.id, operation: OP_BY_ID[n.op]?.pfdOperation ?? n.op, params: n.params })),
    output: { type: 'KAFKA', topic: last?.sinkTopic ?? 'default-output' },
  };
  return JSON.stringify(pfd, null, 2) + '\n';
}

function javaName(label: string): string {
  return label.replace(/[^A-Za-z0-9_]/g, '_');
}

/** Java code in the form of the Swing "Export code (Java)" button and MacroRunner. */
export function toJavaCode(s: Pick<FlowState, 'track' | 'nodes'>): string {
  const byId = new Map(s.nodes.map((n) => [n.id, n]));
  return (
    s.nodes
      .map((n) => {
        if (n.op === 'source') {
          return `TSBucket tsb${javaName(n.label)} = TSBucket.loadViaContext( sc, "/TSBASE/${s.track}/${n.label}.tsb.vec.seq" );`;
        }
        const parent = byId.get(n.input ?? '');
        const op = OP_BY_ID[n.op];
        const arg = Object.values(n.params).join(',');
        const call = `${(op?.pfdOperation ?? n.op).toUpperCase()}${arg ? `(${arg})` : ''}`;
        return `TSBucket tsb${javaName(n.label)} = tsb${javaName(parent?.label ?? '')}.processBucket( "${call}", null );`;
      })
      .join('\n') + '\n'
  );
}
