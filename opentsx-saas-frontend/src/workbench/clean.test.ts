import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { chainTo, cleanChecks, cleanTopic, contextOf, deployChecks, isCleanSink, runBlocker, toDagYaml, toDefinition } from './flow';
import { topicConflict } from './deployments';
import { summarize, runPlan } from './flows';
import { OP_BY_ID } from './ops';
import { Bucket, FlowNode, Params, SignalContext } from './types';

// The same cases run in Java: opentsx-retrieval, CleanChainTest.
const VECTORS = JSON.parse(readFileSync(resolve(process.cwd(), '../docs/datamodel-2026/testvectors/clean-ops.v1.json'), 'utf8')) as {
  cases: {
    name: string;
    t0: string;
    intervalSeconds: number;
    workbench: { op: string; params: Params }[];
    values: (number | null)[];
    expect: { times: number[]; values: (number | null)[]; unit?: string };
  }[];
};

describe('cleaning operators against the test vectors shared with the processor', () => {
  for (const c of VECTORS.cases) {
    it(c.name, () => {
      let bucket: Bucket = {
        name: 'raw',
        xKind: 'time',
        xLabel: 't [s]',
        yLabel: 'kW',
        t0Ms: Date.parse(c.t0),
        series: [{ id: 's', label: 's', unit: 'kW', x: Float64Array.from(c.values, (_, i) => i * c.intervalSeconds), y: Float64Array.from(c.values, (v) => v ?? NaN) }],
      };
      for (const step of c.workbench) bucket = OP_BY_ID[step.op].apply(bucket, step.params);
      const s = bucket.series[0];
      expect(Array.from(s.x)).toEqual(c.expect.times);
      expect(Array.from(s.y, (v) => (Number.isNaN(v) ? null : Number(v.toPrecision(12))))).toEqual(c.expect.values);
      if (c.expect.unit) expect(s.unit).toBe(c.expect.unit);
    });
  }
});

const context: SignalContext = {
  system: 'h2-chain',
  systemTitle: 'Wasserstoffkette am Windpark',
  signal: 'park/T02/WTUR.W',
  grade: 'raw',
  expect: { unit: 'kW', min: -100, max: 10000, interval: 'PT1S' },
  origin: 'SYNTHETIC',
  synthetic: true,
};
const source: FlowNode = { id: 'source', op: 'source', label: 'power_active_T02', params: {}, input: null };
const range: FlowNode = { id: 'n1', op: 'dq.plausible-range', label: 'Range_power', params: { min: -100, max: 10000, mode: 'gap' }, input: 'source' };
const minute: FlowNode = { id: 'n2', op: 'resample', label: 'Resample_Range_power', params: { every: 60, agg: 'MEAN' }, input: 'n1', sinkTopic: cleanTopic('wind') };
const zscore: FlowNode = { id: 'n3', op: 'norm.zscore', label: 'Norm_power', params: {}, input: 'n2' };
const doc = { track: 'EXP2', domain: 'wind', sourceTopic: 'tsx.wind.episodes.v2', sourceSeries: ['abc123'], nodes: [source, range, minute], savedId: 23, context };

describe('a flow published as the clean grade of a signal', () => {
  it('is written as an opentsx.dag.v2 with a series sink that names the signal', () => {
    const yaml = toDagYaml(doc);
    expect(yaml).toContain('schema: opentsx.dag.v2');
    expect(yaml).toContain('dag: wind-flow-23');
    expect(yaml).toContain('series: [abc123]');
    expect(yaml).toContain('op: dq.checks');
    expect(yaml).toContain('params: { checks: [PLAUSIBLE_RANGE], min: -100, max: 10000, mode: gap }');
    // the width of a window is seconds in the workbench and a duration in the document
    expect(yaml).toContain('params: { every: PT60S, agg: MEAN }');
    expect(yaml).toContain('- { from: n2, topic: tsx.wind.clean.v2, schema: opentsx.episode.v2, signal: { system: h2-chain, id: park/T02/WTUR.W, grade: clean }, origin: SYNTHETIC }');
  });

  it('stays an opentsx.dag.v1 without a signal, and for a vector', () => {
    expect(toDagYaml({ ...doc, context: null })).not.toContain('opentsx.dag.v2');
    expect(isCleanSink(minute)).toBe(true);
    expect(isCleanSink({ ...minute, sinkTopic: 'tsx.wind.smart.x.vectors.v1' })).toBe(false);
  });

  it('can run when it is one chain of operators the processor has', () => {
    expect(chainTo(doc.nodes, 'n2')?.map((n) => n.id)).toEqual(['n1', 'n2']);
    expect(cleanChecks(doc.nodes, ['abc123'], context, 'n2').every((c) => c.ok)).toBe(true);
    expect(deployChecks(doc.nodes, ['abc123'], context).every((c) => c.ok)).toBe(true);
    expect(runBlocker(doc.nodes, ['abc123'], context)).toBeNull();
  });

  it('says which step keeps it in the workbench', () => {
    const nodes = [source, range, { ...minute, sinkTopic: undefined }, { ...zscore, sinkTopic: cleanTopic('wind') }];
    const checks = cleanChecks(nodes, ['abc123'], context, 'n3');
    expect(checks.filter((c) => !c.ok).map((c) => c.text)).toEqual([expect.stringMatching(/workbench only: NORM std=1, avg=0/)]);
    expect(runBlocker(nodes, ['abc123'], context)).toMatch(/workbench only/);
    expect(cleanChecks(doc.nodes, [], context, 'n2').find((c) => !c.ok)?.text).toMatch(/Load the series/);
    expect(cleanChecks(doc.nodes, ['abc123'], null, 'n2').find((c) => !c.ok)?.text).toMatch(/Open a signal/);
    expect(cleanChecks(doc.nodes, ['abc123'], context, 'source').find((c) => !c.ok)?.text).toMatch(/at least one operator/);
  });

  it('comes back from the account with its signal, and can be started from the composer', () => {
    const definition = toDefinition(doc);
    expect(definition).toMatchObject({ system: 'h2-chain', signal: 'park/T02/WTUR.W' });
    expect(contextOf(definition)).toEqual(context);
    expect(contextOf({})).toBeNull();
    const summary = summarize({ id: 23, name: 'Clean park/T02/WTUR.W', definition });
    expect(summary).toMatchObject({ runnable: true, system: 'h2-chain', signal: 'park/T02/WTUR.W', published: ['tsx.wind.clean.v2'], dag: 'wind-flow-23' });
    const plan = runPlan(summary);
    expect('yaml' in plan && plan.yaml).toContain('schema: opentsx.episode.v2');
  });

  it('shares the clean topic with other signals, but one signal has one writer', () => {
    const topic = 'tsx.wind.clean.v2';
    const mine = { dag: 'wind-flow-23', published: [topic], system: 'h2-chain', signal: 'park/T02/WTUR.W', status: 'draft', runnable: true };
    const otherSignal = { dag: 'wind-flow-24', published: [topic], system: 'h2-chain', signal: 'park/T02/WMET.HorWdSpd', status: 'active', runnable: true };
    const sameSignal = { dag: 'wind-flow-25', published: [topic], system: 'h2-chain', signal: 'park/T02/WTUR.W', status: 'active', runnable: true };
    const running = { dag: 'wind-flow-24', desired: 'deployed', by: 'a', at: '2026-10-13T10:00:00Z', sinks: [{ vector: 'x', topic, state: 'running', records: 1, at: '2026-10-13T10:00:30Z' }] };
    expect(topicConflict([running], 'wind-flow-23', [topic], [mine, otherSignal])).toBeNull();
    expect(topicConflict([], 'wind-flow-23', [topic], [mine, otherSignal, sameSignal])).toMatch(/already written by the deployment wind-flow-25/);
    expect(topicConflict([], 'wind-flow-23', [topic], [mine, { ...sameSignal, status: 'paused' }])).toBeNull();
  });
});
