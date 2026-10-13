import { describe, expect, it } from 'vitest';
import { toDefinition } from './flow';
import { NO_FLOW_FILTER, StoredFlow, copyName, domainFacets, filterFlows, runPlan, stamp, summarize } from './flows';
import { InventorySeries, MANY_POINTS, estimatePoints, wholeRange } from './inventory';

const source = { id: 'source', op: 'source', label: 'T01', params: {}, input: null, position: { x: 0, y: 0 } };
const vector = { id: 'n1', op: 'align.vector', label: 'Vector_T01', params: {}, input: 'source', position: { x: 0, y: 0 }, sinkTopic: 'tsx.wind.smart.t01.vectors.v1' };
const log = { id: 'n1', op: 'log', label: 'LOG_T01', params: {}, input: 'source', position: { x: 0, y: 0 } };

function stored(id: number, name: string, definition: object | null): StoredFlow {
  return { id, name, description: '', updated_at: '2026-10-13T10:02:11.123456', definition };
}

const published = stored(
  7,
  'EXP2: T01',
  toDefinition({ track: 'EXP2', domain: 'wind', sourceTopic: 'tsx.wind.episodes.v2', sourceSeries: ['a', 'b'], nodes: [source, vector] })
);
const plain = stored(8, 'LAB: sine', toDefinition({ track: 'LAB', domain: 'lab', sourceTopic: 'tsx.lab.observations.v2', nodes: [source, log] }));
const builder = stored(9, 'Old builder flow', { nodes: [], edges: [] });

describe('summarize', () => {
  it('reads track, series, buckets, topics and the deployment name of a workbench flow', () => {
    const s = summarize(published);
    expect(s).toMatchObject({
      openable: true,
      track: 'EXP2',
      domain: 'wind',
      sourceTopic: 'tsx.wind.episodes.v2',
      sourceSeries: ['a', 'b'],
      steps: ['Vector_T01'],
      published: ['tsx.wind.smart.t01.vectors.v1'],
      vectors: 1,
      dag: 'wind-exp2-smart',
    });
  });

  it('names no deployment for a flow that publishes nothing', () => {
    expect(summarize(plain)).toMatchObject({ openable: true, dag: '', published: [], sourceSeries: [] });
  });

  it('lists a flow of the flow builder, but not as one to open', () => {
    const s = summarize(builder);
    expect(s.openable).toBe(false);
    expect(s.reason).toMatch(/flow builder/);
  });

  it('refuses a workbench flow whose nodes it cannot read', () => {
    const broken = stored(10, 'broken', { kind: 'opentsx.workbench.v1', nodes: [{ id: 'n0', op: 'no.such.operator', label: 'x' }] });
    expect(summarize(broken)).toMatchObject({ openable: false, steps: [] });
    expect(summarize(stored(11, 'empty', null)).openable).toBe(false);
  });
});

describe('filterFlows', () => {
  const all = [published, plain, builder].map(summarize);

  it('filters by words, domain and kind', () => {
    expect(filterFlows(all, NO_FLOW_FILTER)).toHaveLength(3);
    expect(filterFlows(all, { ...NO_FLOW_FILTER, text: 'vector exp2' }).map((f) => f.id)).toEqual([7]);
    expect(filterFlows(all, { ...NO_FLOW_FILTER, text: 'smart.t01' }).map((f) => f.id)).toEqual([7]);
    expect(filterFlows(all, { ...NO_FLOW_FILTER, domain: 'lab' }).map((f) => f.id)).toEqual([8]);
    expect(filterFlows(all, { ...NO_FLOW_FILTER, only: 'published' }).map((f) => f.id)).toEqual([7]);
    expect(filterFlows(all, { ...NO_FLOW_FILTER, only: 'data' }).map((f) => f.id)).toEqual([7]);
  });

  it('offers the domains of the flows', () => {
    expect(domainFacets(all)).toEqual(['lab', 'wind']);
  });

  it('shortens the timestamp of the backend', () => {
    expect(stamp('2026-10-13T10:02:11.123456')).toBe('2026-10-13 10:02');
    expect(stamp('')).toBe('');
  });
});

describe('runPlan', () => {
  it('gives the DAG of a published vector on inventory series', () => {
    const plan = runPlan(summarize(published));
    expect(plan).toMatchObject({ dag: 'wind-exp2-smart' });
    expect('yaml' in plan && plan.yaml).toContain('tsx.wind.smart.t01.vectors.v1');
    expect('yaml' in plan && plan.yaml).toContain('series: [a, b]');
  });

  it('says what keeps the processor from running a flow', () => {
    expect(runPlan(summarize(plain))).toMatchObject({ blocker: expect.stringMatching(/TS inventory/) });
    expect(runPlan(summarize(builder))).toMatchObject({ blocker: expect.stringMatching(/flow builder/) });
    const noData = stored(12, 'x', toDefinition({ track: 'X', domain: 'wind', sourceTopic: 't', nodes: [source, vector] }));
    expect(runPlan(summarize(noData))).toMatchObject({ blocker: expect.stringMatching(/TS inventory/) });
  });
});

describe('status and copies', () => {
  it('reads the status and filters by it', () => {
    const all = [{ ...published, status: 'active' }, plain].map(summarize);
    expect(all.map((f) => f.status)).toEqual(['active', 'draft']);
    expect(filterFlows(all, { ...NO_FLOW_FILTER, status: 'active' }).map((f) => f.id)).toEqual([7]);
  });

  it('names a copy with the next free number', () => {
    expect(copyName('A', ['A'])).toBe('A (copy)');
    expect(copyName('A', ['A', 'A (copy)'])).toBe('A (copy 2)');
    expect(copyName('A (copy)', ['A', 'A (copy)'])).toBe('A (copy 2)');
    expect(copyName('A (copy 2)', ['A (copy)', 'A (copy 2)'])).toBe('A (copy 3)');
  });
});

describe('wholeRange', () => {
  const series = (n: number, days: number): InventorySeries[] =>
    Array.from({ length: n }, (_, i) => ({
      seriesId: `s${i}`,
      metric: 'm',
      tags: {},
      labels: {},
      episodes: 1,
      segments: 0,
      samples: days * 86_400,
      missing: 0,
      intervalMicros: 1_000_000,
      from: '2026-10-01T00:00:00Z',
      to: new Date(Date.parse('2026-10-01T00:00:00Z') + days * 86_400_000).toISOString(),
      marks: [],
    })) as unknown as InventorySeries[];

  it('keeps the stored resolution while the scope stays fast', () => {
    expect(wholeRange(series(2, 1))).toMatchObject({ from: '2026-10-01T00:00:00Z', resample: '' });
  });

  it('picks a coarser resolution for a large selection', () => {
    const big = series(20, 2);
    const o = wholeRange(big)!;
    expect(o.resample).not.toBe('');
    expect(estimatePoints(big, o)).toBeLessThanOrEqual(MANY_POINTS);
  });

  it('gives nothing for series without a range', () => {
    expect(wholeRange([])).toBeNull();
  });
});
