/**
 * The processing flow of the workbench: the track of the Swing MacroTrackerFrame
 * as a graph of operator nodes, with evaluation on the loaded bucket and the
 * exports (opentsx.dag.v1 YAML, Processing Flow Descriptor JSON, Java code).
 */

import { create } from 'zustand';
import { OP_BY_ID, defaultParams } from './ops';
import { Bucket, FlowNode, Params, SourceInfo } from './types';
import { VECTOR_OP, VECTOR_SCHEMA, componentNames } from './vector';

export const SOURCE_ID = 'source';
/** `kind` of a flow definition stored in the backend by the workbench. */
export const WORKBENCH_KIND = 'opentsx.workbench.v1';

export interface FlowState {
  track: string;
  domain: string;
  sourceTopic: string;
  /** Ids of the series read from the source topic (set when the bucket comes from the TS inventory); empty for all. */
  sourceSeries: string[];
  source: Bucket | null;
  sourceInfo: SourceInfo | null;
  nodes: FlowNode[];
  selectedId: string | null;
  counter: number;
  /** Id of the flow in the backend once it was saved or loaded from there. */
  savedId: number | null;

  setTrack: (track: string) => void;
  setDomain: (domain: string) => void;
  setSourceTopic: (topic: string) => void;
  setSourceSeries: (ids: string[]) => void;
  loadBucket: (bucket: Bucket, info: SourceInfo) => void;
  select: (id: string) => void;
  applyOp: (opId: string, params?: Params) => string | null;
  updateParams: (id: string, params: Params) => void;
  removeNode: (id: string) => void;
  rewire: (id: string, inputId: string) => boolean;
  addInput: (id: string, inputId: string) => boolean;
  removeInput: (id: string, inputId: string) => void;
  moveNode: (id: string, pos: { x: number; y: number }) => void;
  setSink: (id: string, topic: string | null) => void;
  setSavedId: (id: number | null) => void;
  restore: (definition: Record<string, unknown>, savedId: number) => boolean;
}

export function slug(text: string): string {
  return text
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '') || 'x';
}

/** Default topic of a published node: vectors get their own kind, everything else is a metric series. */
export function defaultSinkTopic(domain: string, label: string, op?: string): string {
  return `tsx.${slug(domain)}.smart.${slug(label)}.${op === VECTOR_OP ? 'vectors.v1' : 'metrics.v2'}`;
}

/** Every input of a node: the first one, then the further inputs of a multi-input operator. */
export function inputsOf(n: FlowNode): string[] {
  return [...(n.input ? [n.input] : []), ...(n.inputs ?? [])];
}

/**
 * All nodes downstream of id, id included. With `everyInput` a node counts as downstream of each of its inputs
 * (what a cycle check needs); without, only of its first one (what the track tree shows and a removal takes along).
 */
export function descendants(nodes: FlowNode[], id: string, everyInput = false): Set<string> {
  const out = new Set<string>([id]);
  let grew = true;
  while (grew) {
    grew = false;
    for (const n of nodes) {
      const ins = everyInput ? inputsOf(n) : n.input ? [n.input] : [];
      if (!out.has(n.id) && ins.some((i) => out.has(i))) {
        out.add(n.id);
        grew = true;
      }
    }
  }
  return out;
}

/** The node and everything it reads from, inputs before their readers. */
export function upstream(nodes: FlowNode[], id: string): FlowNode[] {
  const byId = new Map(nodes.map((n) => [n.id, n]));
  const seen = new Set<string>();
  const out: FlowNode[] = [];
  const visit = (cur: string) => {
    const n = byId.get(cur);
    if (!n || seen.has(cur)) return;
    seen.add(cur);
    for (const i of inputsOf(n)) visit(i);
    out.push(n);
  };
  visit(id);
  return out;
}

/** Nodes from the source down to id along the first input of each node. */
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
  sourceSeries: [],
  source: null,
  sourceInfo: null,
  nodes: [],
  selectedId: null,
  counter: 0,
  savedId: null,

  setTrack: (track) => set({ track }),
  setDomain: (domain) =>
    set((s) => ({
      domain,
      sourceTopic:
        s.sourceTopic === `tsx.${slug(s.domain)}.observations.v2` ? `tsx.${slug(domain)}.observations.v2` : s.sourceTopic,
    })),
  setSourceTopic: (sourceTopic) => set({ sourceTopic }),
  setSourceSeries: (sourceSeries) => set({ sourceSeries }),

  loadBucket: (bucket, info) => {
    cache.clear();
    const s = get();
    // A flow restored from the backend waits for its data: the bucket becomes its source.
    if (s.source === null && s.nodes.length > 1) {
      set({
        source: bucket,
        sourceInfo: info,
        nodes: relabel(s.nodes.map((n) => (n.id === SOURCE_ID ? { ...n, label: bucket.name } : n))),
      });
      return;
    }
    set({
      source: bucket,
      sourceInfo: info,
      sourceSeries: [],
      nodes: [{ id: SOURCE_ID, op: 'source', params: {}, input: null, label: bucket.name }],
      selectedId: SOURCE_ID,
      counter: 0,
      savedId: null,
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
      ...(op.multiInput ? { inputs: [] } : {}),
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
        // a multi-input node that read one of the removed nodes as a further input loses that component
        nodes: s.nodes
          .filter((n) => !gone.has(n.id))
          .map((n) => (n.inputs?.some((i) => gone.has(i)) ? { ...n, inputs: n.inputs.filter((i) => !gone.has(i)) } : n)),
        selectedId: s.selectedId && gone.has(s.selectedId) ? (removed?.input ?? SOURCE_ID) : s.selectedId,
      };
    });
  },

  rewire: (id, inputId) => {
    const s = get();
    // The source has no input, and a node may not read from its own downstream.
    if (id === SOURCE_ID || id === inputId || descendants(s.nodes, id, true).has(inputId)) return false;
    set({
      nodes: relabel(
        s.nodes.map((n) => (n.id === id ? { ...n, input: inputId, inputs: n.inputs?.filter((i) => i !== inputId) } : n))
      ),
    });
    return true;
  },

  addInput: (id, inputId) => {
    const s = get();
    const node = s.nodes.find((n) => n.id === id);
    if (!node || !OP_BY_ID[node.op]?.multiInput || !s.nodes.some((n) => n.id === inputId)) return false;
    if (id === inputId || inputsOf(node).includes(inputId) || descendants(s.nodes, id, true).has(inputId)) return false;
    set({ nodes: s.nodes.map((n) => (n.id === id ? { ...n, inputs: [...(n.inputs ?? []), inputId] } : n)) });
    return true;
  },

  removeInput: (id, inputId) =>
    set((s) => ({
      nodes: s.nodes.map((n) => (n.id === id && n.inputs ? { ...n, inputs: n.inputs.filter((i) => i !== inputId) } : n)),
    })),

  moveNode: (id, pos) => set((s) => ({ nodes: s.nodes.map((n) => (n.id === id ? { ...n, pos } : n)) })),

  setSink: (id, topic) =>
    set((s) => ({ nodes: s.nodes.map((n) => (n.id === id ? { ...n, sinkTopic: topic ?? undefined } : n)) })),

  setSavedId: (savedId) => set({ savedId }),

  restore: (definition, savedId) => {
    const nodes = parseNodes(definition.nodes);
    if (definition.kind !== WORKBENCH_KIND || !nodes) return false;
    cache.clear();
    const numbers = nodes.map((n) => Number(/^n(\d+)$/.exec(n.id)?.[1] ?? 0));
    set({
      track: typeof definition.track === 'string' ? definition.track : get().track,
      domain: typeof definition.domain === 'string' ? definition.domain : get().domain,
      sourceTopic: typeof definition.sourceTopic === 'string' ? definition.sourceTopic : get().sourceTopic,
      sourceSeries: Array.isArray(definition.sourceSeries)
        ? definition.sourceSeries.filter((i): i is string => typeof i === 'string' && /^[A-Za-z0-9_.-]+$/.test(i))
        : [],
      source: null,
      sourceInfo: null,
      nodes,
      selectedId: nodes[nodes.length - 1].id,
      counter: Math.max(0, ...numbers),
      savedId,
    });
    return true;
  },
}));

/** Checks a stored node list: known operators, one source, every input present, no cycles. */
export function parseNodes(raw: unknown): FlowNode[] | null {
  if (!Array.isArray(raw) || raw.length === 0 || raw.length > 500) return null;
  const nodes: FlowNode[] = [];
  for (const r of raw as Partial<FlowNode>[]) {
    if (!r || typeof r.id !== 'string' || typeof r.op !== 'string' || typeof r.label !== 'string') return null;
    if (r.op !== 'source' && !OP_BY_ID[r.op]) return null;
    nodes.push({
      id: r.id,
      op: r.op,
      params: r.params && typeof r.params === 'object' ? { ...r.params } : {},
      input: typeof r.input === 'string' ? r.input : null,
      ...(Array.isArray(r.inputs) ? { inputs: r.inputs.filter((i): i is string => typeof i === 'string') } : {}),
      label: r.label,
      pos: r.pos && typeof r.pos.x === 'number' && typeof r.pos.y === 'number' ? { x: r.pos.x, y: r.pos.y } : undefined,
      sinkTopic: typeof r.sinkTopic === 'string' ? r.sinkTopic : undefined,
    });
  }
  const ids = new Set(nodes.map((n) => n.id));
  if (ids.size !== nodes.length) return null;
  const sources = nodes.filter((n) => n.op === 'source');
  if (sources.length !== 1 || sources[0].id !== SOURCE_ID || sources[0].input !== null) return null;
  for (const n of nodes) {
    if (n.op === 'source') continue;
    if (!n.input || !ids.has(n.input)) return null;
    if (n.inputs) {
      if (!OP_BY_ID[n.op]?.multiInput || n.inputs.some((i) => !ids.has(i) || i === n.id)) return null;
      if (new Set(inputsOf(n)).size !== inputsOf(n).length) return null;
    }
  }
  // Every node must reach the source; a cycle never does.
  const byId = new Map(nodes.map((n) => [n.id, n]));
  for (const n of nodes) {
    let cur: FlowNode | undefined = n;
    for (let steps = 0; cur && cur.input && steps <= nodes.length; steps++) cur = byId.get(cur.input);
    if (!cur || cur.id !== SOURCE_ID) return null;
  }
  // The further inputs must not close a cycle either.
  for (const n of nodes) {
    if (n.inputs?.some((i) => descendants(nodes, n.id, true).has(i))) return null;
  }
  return nodes;
}

/** What the exports read of the flow. */
export type FlowDoc = Pick<FlowState, 'track' | 'domain' | 'sourceTopic' | 'nodes'> & { sourceSeries?: string[] };

/** The flow as stored in the backend: the node list plus the rendered DAG for readers without the workbench. */
export function toDefinition(s: FlowDoc): Record<string, unknown> {
  return {
    kind: WORKBENCH_KIND,
    track: s.track,
    domain: s.domain,
    sourceTopic: s.sourceTopic,
    sourceSeries: s.sourceSeries ?? [],
    nodes: s.nodes,
    edges: s.nodes.flatMap((n) => inputsOf(n).map((i) => ({ id: `${i}-${n.id}`, source: i, target: n.id }))),
    dag: toDagYaml(s),
  };
}

// ---------------------------------------------------------------- evaluation

const cache = new Map<string, Bucket>();

/**
 * Runs the flow from the source down to the node. Results are cached per operator tree: the key of a node is its
 * operator, its parameters and the keys of its inputs.
 */
export function evaluate(source: Bucket | null, nodes: FlowNode[], id: string): Bucket | null {
  if (!source) return null;
  const order = upstream(nodes, id);
  if (order.length === 0 || !order.some((n) => n.op === 'source')) return null;
  const keys = new Map<string, string>();
  const results = new Map<string, Bucket>();
  for (const n of order) {
    if (n.op === 'source') {
      keys.set(n.id, 'source');
      results.set(n.id, source);
      continue;
    }
    const op = OP_BY_ID[n.op];
    const ins = inputsOf(n);
    if (!op || ins.length === 0 || ins.some((i) => !results.has(i))) return null;
    const multi = op.multiInput ? `|${ins.map((i) => nodes.find((m) => m.id === i)?.label).join(',')}` : '';
    const key = `${n.op}(${JSON.stringify(n.params)}${multi})[${ins.map((i) => keys.get(i)).join(';')}]`;
    let next = cache.get(key);
    if (!next) {
      // A multi-input operator names its components after the buckets, so it gets them under their track names.
      const named = (i: string): Bucket => ({ ...results.get(i)!, name: nodes.find((m) => m.id === i)?.label ?? i });
      next = op.multiInput ? op.apply(named(ins[0]), n.params, ins.slice(1).map(named)) : op.apply(results.get(ins[0])!, n.params);
      if (cache.size > 200) cache.clear();
      cache.set(key, next);
    }
    keys.set(n.id, key);
    results.set(n.id, next);
  }
  return results.get(id) ?? null;
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

/** Names of the inputs of a vector node, from their bucket names. An input with one series per key is one component. */
export function vectorComponents(nodes: FlowNode[], node: FlowNode): string[] {
  const labels = inputsOf(node).map((i) => nodes.find((n) => n.id === i)?.label ?? i);
  return componentNames(labels, String(node.params.names ?? ''));
}

/** Parameters as written into the DAG. Catalog operators get the shape of the catalog. */
function dagParams(nodes: FlowNode[], node: FlowNode): Params | string {
  if (node.op === 'dq.plausible-range') {
    return `{ checks: [PLAUSIBLE_RANGE], min: ${node.params.min}, max: ${node.params.max}, mode: ${node.params.mode} }`;
  }
  if (node.op === VECTOR_OP) {
    const p = node.params;
    const fill = (p.fill === 'as-of' ? `, fill: as-of, maxAge: PT${p.maxAge}S` : '') + (Number(p.lateness) > 0 ? `, lateness: PT${p.lateness}S` : '');
    return `{ every: PT${p.every}S, key: ${p.key}, agg: ${p.agg}${fill}, emit: ${p.emit}, names: [${vectorComponents(nodes, node).join(', ')}] }`;
  }
  return node.params;
}

export interface Finding {
  level: 'error' | 'warning' | 'info';
  text: string;
}

/**
 * Why the vector processor cannot run the flow, or null when it can. It executes align.vector on the series of the
 * source topic and nothing else, and it needs their ids (docs/datamodel-2026/11, sections 5a and 5b).
 */
export function runBlocker(nodes: FlowNode[], sourceSeries?: string[]): string | null {
  const vectors = nodes.filter((n) => n.sinkTopic && n.op === VECTOR_OP);
  if (vectors.length === 0) return 'Publish an "Align to vector" node first: the processor writes vector topics only.';
  if (sourceSeries && sourceSeries.length === 0) {
    return 'Load the series from the TS inventory: a vector that keeps running has to name the series it is made of.';
  }
  for (const v of vectors) {
    const other = inputsOf(v).find((i) => i !== SOURCE_ID);
    if (other) {
      const label = nodes.find((n) => n.id === other)?.label ?? other;
      return `${v.label} reads ${label}. The processor executes vectors built from the source bucket only; operators before the vector are not available in it yet.`;
    }
    if (!/^tsx\.[a-z0-9_-]+\.smart\.[a-z0-9][a-z0-9-]*\.vectors\.v1$/.test(v.sinkTopic ?? '')) {
      return `Topic ${v.sinkTopic} must have the form tsx.<domain>.smart.<name>.vectors.v1.`;
    }
  }
  return null;
}

export interface DeployCheck {
  ok: boolean;
  /** What is required, and when it is missing, what to do about it. */
  text: string;
}

/**
 * What the vector processor needs of a flow, one line each, so a person sees which step is still open instead of
 * only the first obstacle ({@link runBlocker}).
 */
export function deployChecks(nodes: FlowNode[], sourceSeries: string[]): DeployCheck[] {
  const vectors = nodes.filter((n) => n.sinkTopic && n.op === VECTOR_OP);
  const fromInventory = sourceSeries.length > 0;
  const onSource = vectors.length > 0 && vectors.every((v) => inputsOf(v).every((i) => i === SOURCE_ID));
  const topicsOk = vectors.length > 0 && vectors.every((v) => /^tsx\.[a-z0-9_-]+\.smart\.[a-z0-9][a-z0-9-]*\.vectors\.v1$/.test(v.sinkTopic ?? ''));
  return [
    {
      ok: fromInventory,
      text: fromInventory
        ? `The bucket comes from a topic: ${sourceSeries.length} series loaded from the TS inventory.`
        : 'Load the series from the TS inventory. A sample bucket or a file has no topic the processor could follow.',
    },
    {
      ok: vectors.length > 0,
      text: vectors.length > 0
        ? `An "Align to vector" node is published: ${vectors.map((v) => v.label).join(', ')}.`
        : 'Publish an "Align to vector" node. The processor writes vector topics; other published nodes are export only.',
    },
    {
      ok: onSource,
      text: onSource
        ? 'The vector reads the source bucket directly.'
        : vectors.length === 0
          ? 'The vector must read the source bucket directly.'
          : 'The vector must read the source bucket directly. Operators before it (normalising, smoothing, resampling) run in the workbench only.',
    },
    {
      ok: topicsOk,
      text: topicsOk
        ? `It writes ${vectors.map((v) => v.sinkTopic).join(', ')}.`
        : 'The topic must have the form tsx.<domain>.smart.<name>.vectors.v1 (lower-case letters, digits and hyphens in the name).',
    },
  ];
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
    const missing = upstream(nodes, s.id).filter((n) => n.op !== 'source' && !OP_BY_ID[n.op]?.inCatalog);
    if (missing.length > 0) {
      out.push({
        level: 'warning',
        text: `${s.label} uses ${[...new Set(missing.map((n) => n.op))].join(', ')}: not in the stream operator catalog, a runtime must provide them.`,
      });
    }
  }
  for (const n of nodes) {
    if (n.op === VECTOR_OP && inputsOf(n).length < 2) {
      out.push({ level: 'warning', text: `${n.label} has one input only. Add further inputs: each becomes a component of the vector.` });
    }
  }
  const used = new Set<string>();
  for (const s of sinks) for (const n of upstream(nodes, s.id)) used.add(n.id);
  const unused = nodes.filter((n) => n.op !== 'source' && !used.has(n.id));
  if (sinks.length > 0 && unused.length > 0) {
    out.push({ level: 'info', text: `${unused.length} node(s) feed no published topic and are left out of the DAG.` });
  }
  return out;
}

/** The flow as opentsx.dag.v1 (docs/datamodel-2026/11-SMART-TOPIC-PROZESSOR-DEVOPS.md, section 5). */
export function toDagYaml(s: FlowDoc): string {
  const sinks = s.nodes.filter((n) => n.sinkTopic);
  const keep = new Set<string>();
  for (const sink of sinks) for (const n of upstream(s.nodes, sink.id)) keep.add(n.id);
  // Without a sink the whole flow is written, so the draft can be read.
  const nodes = s.nodes.filter((n) => n.op !== 'source' && (sinks.length === 0 || keep.has(n.id)));
  const lines = [
    '# opentsx.dag.v1, written by the OpenTSx web workbench',
    `dag: ${yamlScalar(`${slug(s.domain)}-${slug(s.track)}-smart`)}`,
    'version: 1',
    'source:',
    '  kind: kafscale-broker',
    `  topics: [${yamlScalar(s.sourceTopic)}]`,
    ...(s.sourceSeries && s.sourceSeries.length > 0 ? [`  series: [${s.sourceSeries.map(yamlScalar).join(', ')}]`] : []),
    'checkpoint: { backend: file }',
    'nodes:',
    '  - id: decode',
    '    op: decode.avro-soe',
  ];
  for (const n of nodes) {
    const op = OP_BY_ID[n.op];
    const params = dagParams(s.nodes, n);
    lines.push(`  - id: ${n.id}`);
    lines.push(`    op: ${op?.dagOp ?? n.op}${op?.inCatalog ? '' : '   # not in the stream operator catalog'}`);
    lines.push(`    in: [${inputsOf(n).map((i) => (i === SOURCE_ID ? 'decode' : i)).join(', ')}]`);
    if (typeof params === 'string') lines.push(`    params: ${params}`);
    else if (Object.keys(params).length > 0) lines.push(`    params: ${yamlParams(params)}`);
  }
  if (nodes.length === 0) lines.push('  # no operators yet');
  lines.push(sinks.length === 0 ? 'sinks: []' : 'sinks:');
  for (const sink of sinks) {
    const schema = sink.op === VECTOR_OP ? `, schema: ${VECTOR_SCHEMA}` : '';
    lines.push(`  - { from: ${sink.id === SOURCE_ID ? 'decode' : sink.id}, topic: ${yamlScalar(sink.sinkTopic!)}${schema} }`);
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
      .map((n) => ({
        id: n.id,
        operation: OP_BY_ID[n.op]?.pfdOperation ?? n.op,
        params: n.params,
        // the descriptor is linear: further inputs are named, their own steps are not part of this branch
        ...(n.inputs && n.inputs.length > 0 ? { inputs: inputsOf(n).map((i) => s.nodes.find((m) => m.id === i)?.label ?? i) } : {}),
      })),
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
        if (op?.multiInput) {
          const ins = inputsOf(n).map((i) => byId.get(i)?.label ?? i).join(', ');
          return `// ${n.label} = ${op.label}( ${ins} ): no counterpart in the Swing workbench`;
        }
        const arg = Object.values(n.params).join(',');
        const call = `${(op?.pfdOperation ?? n.op).toUpperCase()}${arg ? `(${arg})` : ''}`;
        return `TSBucket tsb${javaName(n.label)} = tsb${javaName(parent?.label ?? '')}.processBucket( "${call}", null );`;
      })
      .join('\n') + '\n'
  );
}
