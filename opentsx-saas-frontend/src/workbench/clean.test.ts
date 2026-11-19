import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { chainTo, cleanChecks, cleanTopic, contextOf, deployChecks, isCleanSink, runBlocker, toDagYaml, toDefinition } from './flow';
import { topicConflict } from './deployments';
import { summarize, runPlan } from './flows';
import { parseEpisodes } from './loaders';
import { OP_BY_ID, defaultParams } from './ops';
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
      // both sides to ten digits: the processor and the browser may differ in the last bit
      const round = (v: number | null) => (v === null || Number.isNaN(v) ? null : Number(v.toPrecision(10)));
      expect(Array.from(s.y, round)).toEqual(c.expect.values.map(round));
      if (c.expect.unit) expect(s.unit).toBe(c.expect.unit);
    });
  }
});

describe('parameters the processor refuses', () => {
  const wind: Bucket = {
    name: 'wind',
    xKind: 'time',
    xLabel: 't [s]',
    yLabel: 'm/s',
    t0Ms: Date.parse('2026-10-01T00:00:00Z'),
    series: [{ id: 's', label: 's', unit: 'm/s', x: Float64Array.from([0, 60, 120]), y: Float64Array.from([4, 8, 13]) }],
  };
  const apply = (op: string, params: Params) => () => OP_BY_ID[op].apply(wind, params);
  const shear = { fromM: 10, toM: 120, alpha: 0.11 };
  const curve = { ratedKw: 3000, cutIn: 3, ratedWind: 12.5, cutOut: 25 };

  it('are refused in the workbench too, with the parameter named', () => {
    const refused: [string, Params, string][] = [
      ['model.wind-shear', {}, 'fromM: missing; expected a number above 0'],
      ['model.wind-shear', { ...shear, fromM: 0 }, 'fromM: "0" is out of range; expected a number above 0'],
      ['model.wind-shear', { ...shear, toM: -1 }, 'toM: "-1" is out of range; expected a number above 0'],
      ['model.wind-shear', { ...shear, toM: '' }, 'toM: empty value; expected a number above 0'],
      ['model.wind-shear', { fromM: 10, toM: 120 }, 'alpha: missing; expected a number from 0 to 0.6'],
      ['model.wind-shear', { ...shear, alpha: 0.7 }, 'alpha: "0.7" is out of range; expected a number from 0 to 0.6'],
      ['model.wind-shear', { ...shear, alpha: -0.1 }, 'alpha: "-0.1" is out of range; expected a number from 0 to 0.6'],
      ['model.wind-shear', { ...shear, alpha: '0.1' }, 'alpha: "0.1" is not a number; expected a number from 0 to 0.6'],
      ['model.wind-shear', { ...shear, alpha: ' 0.1 ' }, 'alpha: "0.1" is not a number; expected a number from 0 to 0.6'],
      ['model.wind-shear', { ...shear, alpha: ' ' }, 'alpha: empty value; expected a number from 0 to 0.6'],
      ['model.power-curve', {}, 'ratedKw: missing; expected a number above 0'],
      ['model.power-curve', { ...curve, ratedKw: 0 }, 'ratedKw: "0" is out of range; expected a number above 0'],
      ['model.power-curve', { ...curve, cutIn: -1 }, 'cutIn: "-1" is out of range; expected a number of 0 or more'],
      ['model.power-curve', { ...curve, ratedWind: 3 }, 'ratedWind: "3" is out of range; expected a number above cutIn 3'],
      ['model.power-curve', { ...curve, cutOut: 12 }, 'cutOut: "12" is out of range; expected a number of at least ratedWind 12.5'],
      ['model.power-curve', { ...curve, count: 0 }, 'count: "0" is out of range; expected a number above 0'],
      ['feature.lag', {}, 'steps: missing; expected a whole number from 1 to 100000'],
      ['feature.lag', { steps: 0 }, 'steps: "0" is out of range; expected a whole number from 1 to 100000'],
      ['feature.lag', { steps: 2.5 }, 'steps: "2.5" is out of range; expected a whole number from 1 to 100000'],
      ['feature.lag', { steps: 100001 }, 'steps: "100001" is out of range; expected a whole number from 1 to 100000'],
      ['resample', { every: 0, agg: 'MEAN' }, 'every: "0" is out of range; expected a number above 0'],
      ['resample', { every: -60, agg: 'MEAN' }, 'every: "-60" is out of range; expected a number above 0'],
      ['resample', { every: '', agg: 'MEAN' }, 'every: empty value; expected a number above 0'],
      ['feature.rolling', { window: 0, fn: 'MEAN' }, 'window: "0" is out of range; expected a number above 0'],
      ['feature.rolling', { fn: 'MEAN' }, 'window: missing; expected a number above 0'],
      ['dq.plausible-range', { min: 5, max: 1, mode: 'gap' }, 'max: "1" is out of range; expected a number of at least min 5'],
    ];
    for (const [op, params, message] of refused) expect(apply(op, params), `${op} ${JSON.stringify(params)}`).toThrow(message);
  });

  it('take what the processor takes', () => {
    for (const op of ['model.wind-shear', 'model.power-curve', 'feature.lag', 'resample', 'feature.rolling', 'dq.plausible-range']) {
      expect(apply(op, defaultParams(OP_BY_ID[op]))).not.toThrow();
    }
    expect(apply('dq.plausible-range', { min: 5, max: 5, mode: 'gap' })).not.toThrow();
    expect(Array.from(OP_BY_ID['model.wind-shear'].apply(wind, { fromM: 10, toM: 10, alpha: 0.6 }).series[0].y)).toEqual([4, 8, 13]);
    expect(Array.from(OP_BY_ID['model.power-curve'].apply(wind, curve).series[0].y)).toEqual(Array.from(OP_BY_ID['model.power-curve'].apply(wind, { ...curve, count: 1 }).series[0].y));
    expect(Array.from(OP_BY_ID['model.power-curve'].apply(wind, { ...curve, count: '' }).series[0].y)).toEqual(Array.from(OP_BY_ID['model.power-curve'].apply(wind, { ...curve, count: 1 }).series[0].y));
    expect(Array.from(OP_BY_ID['feature.lag'].apply(wind, { steps: 100000 }).series[0].y)).toEqual([NaN, NaN, NaN]);
  });

  it('resample hands out a window once the data has passed its end, as the processor does', () => {
    // 90 s of 10 s samples: the minute from 60 s is still open when the data ends at 90 s
    const ninety: Bucket = { ...wind, series: [{ id: 's', label: 's', x: Float64Array.from({ length: 9 }, (_, i) => i * 10), y: Float64Array.from({ length: 9 }, (_, i) => i) }] };
    const out = OP_BY_ID.resample.apply(ninety, { every: 60, agg: 'MEAN' }).series[0];
    expect(Array.from(out.x)).toEqual([0]);
    expect(Array.from(out.y)).toEqual([2.5]);
    // a window between the last sample and the end of the data is a gap, as in the processor
    const halves = OP_BY_ID.resample.apply(ninety, { every: 5, agg: 'MEAN' }).series[0];
    expect(Array.from(halves.x).slice(-2)).toEqual([80, 85]);
    expect(Array.from(halves.y).slice(-2)).toEqual([8, NaN]);
    // an episode ends where it says: 2 s after its last sample here, so the minute from 60 s stays open
    const t0 = Date.parse('2026-10-01T00:00:00Z') * 1000;
    const episode = parseEpisodes([{ seriesId: 'e', tStart: '2026-10-01T00:00:00Z', tEnd: '2026-10-01T00:01:12Z', timestampsMicros: [t0, t0 + 50e6, t0 + 70e6], values: [1, 2, 3] }], 'x');
    expect(Array.from(OP_BY_ID.resample.apply(episode, { every: 60, agg: 'MEAN' }).series[0].x)).toEqual([0]);
    expect(Array.from(OP_BY_ID.resample.apply(episode, { every: 36, agg: 'MEAN' }).series[0].x)).toEqual([0, 36]);
    // an end more than 10000 windows after the open window closes nothing (CleanChain.MAX_EMPTY_WINDOWS)
    const week = parseEpisodes([{ seriesId: 'e', tStart: '2026-10-01T00:00:00Z', tEnd: '2026-10-08T00:00:00Z', timestampsMicros: [t0, t0 + 1e6], values: [1, 2] }], 'x');
    expect(Array.from(OP_BY_ID.resample.apply(week, { every: 1, agg: 'MEAN' }).series[0].y)).toEqual([1]);
  });

  it('resample closes the windows at the end of each episode and starts the grid again after a long silence, as the processor does', () => {
    // CleanChainTest.aLongSilenceStartsANewRunInsteadOfFillingIt: one block per episode, the second 10005 minutes later
    const t0 = Date.parse('2026-10-01T00:00:00Z') * 1000;
    const later = t0 + 10_005 * 60e6;
    const iso = (micros: number) => new Date(micros / 1000).toISOString();
    const two = parseEpisodes(
      [
        { seriesId: 'e', tStart: iso(t0), tEnd: iso(t0 + 120e6), timestampsMicros: [t0, t0 + 30e6, t0 + 60e6, t0 + 90e6], values: [1, 2, 3, 4] },
        { seriesId: 'e', tStart: iso(later), tEnd: iso(later + 60e6), timestampsMicros: [later], values: [7] },
      ],
      'x'
    );
    const out = OP_BY_ID.resample.apply(two, { every: 60, agg: 'MEAN' }).series[0];
    // the end of the first episode closes the minute from 60 s before the silence drops what is open
    expect(Array.from(out.x)).toEqual([0, 60, 10_005 * 60]);
    expect(Array.from(out.y)).toEqual([1.5, 3.5, 7]);
  });
});

describe('an emptied or null parameter', () => {
  const raw: Bucket = {
    name: 'raw',
    xKind: 'time',
    xLabel: 't [s]',
    yLabel: 'kW',
    series: [{ id: 's', label: 's', unit: 'kW', x: Float64Array.from([0, 60, 120]), y: Float64Array.from([-5, 3, 12]) }],
  };
  const src: FlowNode = { id: 'source', op: 'source', label: 'raw', params: {}, input: null };
  const dagOf = (node: FlowNode) => toDagYaml({ track: 'EXP2', domain: 'lab', sourceTopic: 'tsx.lab.observations.v2', nodes: [src, { ...node, sinkTopic: 'tsx.lab.smart.x.metrics.v2' }] });

  it('takes the default in the preview and is left out of the DAG, so the processor takes its default too', () => {
    const convert: FlowNode = { id: 'n1', op: 'convert.unit', label: 'Convert_raw', params: { factor: '', offset: 32, unit: 'degF' }, input: 'source' };
    expect(Array.from(OP_BY_ID['convert.unit'].apply(raw, convert.params).series[0].y)).toEqual([27, 35, 44]);
    expect(dagOf(convert)).toContain('    params: { offset: 32, unit: degF }\n');
    expect(dagOf(convert)).not.toContain('factor');
  });

  it('gives a plausible range without that bound', () => {
    const range: FlowNode = { id: 'n1', op: 'dq.plausible-range', label: 'Range_raw', params: { min: '', max: 10, mode: 'gap' }, input: 'source' };
    expect(Array.from(OP_BY_ID['dq.plausible-range'].apply(raw, range.params).series[0].y)).toEqual([-5, 3, NaN]);
    expect(Array.from(OP_BY_ID['dq.plausible-range'].apply(raw, { ...range.params, max: ' ' }).series[0].y)).toEqual([-5, 3, 12]);
    expect(dagOf(range)).toContain('    params: { checks: [PLAUSIBLE_RANGE], max: 10, mode: gap }\n');
    expect(dagOf({ ...range, params: { min: -1, max: 1e4, mode: 'clip' } })).toContain('    params: { checks: [PLAUSIBLE_RANGE], min: -1, max: 10000, mode: clip }\n');
  });

  it('null is left out of the DAG instead of written as the text "null"', () => {
    const curve: FlowNode = { id: 'n1', op: 'model.power-curve', label: 'Potential_raw', params: { ratedKw: 3000, cutIn: 3, ratedWind: 12.5, cutOut: 25, count: null } as unknown as Params, input: 'source' };
    expect(dagOf(curve)).toContain('    params: { ratedKw: 3000, cutIn: 3, ratedWind: 12.5, cutOut: 25 }\n');
    expect(dagOf(curve)).not.toContain('count');
  });
});

const context: SignalContext = {
  system: 'h2-chain',
  systemTitle: 'Hydrogen chain at the wind park',
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

  it('names a node published beside the clean series that the processor cannot write', () => {
    const fft: FlowNode = { id: 'n3', op: 'fft', label: 'FFT_power', params: {}, input: 'n2', sinkTopic: 'tsx.wind.smart.fft.metrics.v2' };
    const nodes = [source, range, minute, fft];
    const text = 'FFT_power: published, but the processor writes only the clean series and vector topics; take it off "Publish as Smart Topic" to deploy.';
    expect(cleanChecks(nodes, ['abc123'], context, 'n2').filter((c) => !c.ok).map((c) => c.text)).toEqual([text]);
    expect(runBlocker(nodes, ['abc123'], context)).toBe(text);
    expect(deployChecks(nodes, ['abc123'], context).find((c) => !c.ok)?.text).toBe(text);
    const both = [{ ...source, sinkTopic: 'tsx.wind.raw-copy.v2' }, range, minute, fft];
    expect(runBlocker(both, ['abc123'], context)).toBe(
      'power_active_T02, FFT_power: published, but the processor writes only the clean series and vector topics; take them off "Publish as Smart Topic" to deploy.'
    );
    // a vector beside the clean series is written with its schema, and the processor runs both
    const vector: FlowNode = { id: 'n4', op: 'align.vector', label: 'Vector_power', params: defaultParams(OP_BY_ID['align.vector']), input: 'source', inputs: [], sinkTopic: 'tsx.wind.smart.v.vectors.v1' };
    expect(cleanChecks([source, range, minute, vector], ['abc123'], context, 'n2').every((c) => c.ok)).toBe(true);
    expect(toDagYaml({ ...doc, nodes: [source, range, minute, vector] })).toContain('topic: tsx.wind.smart.v.vectors.v1, schema: opentsx.vector.v1 }');
  });

  it('names a second clean sink before the deployment, which publishing the clean series takes off', () => {
    const twice = [source, { ...range, sinkTopic: cleanTopic('wind') }, minute];
    const text = 'Resample_Range_power: published, but the processor writes only the clean series and vector topics; take it off "Publish as Smart Topic" to deploy.';
    expect(runBlocker(twice, ['abc123'], context)).toBe(text);
    expect(deployChecks(twice, ['abc123'], context).filter((c) => !c.ok).map((c) => c.text)).toEqual([text]);
    expect(cleanChecks(twice, ['abc123'], context, 'n2').every((c) => c.ok)).toBe(true);
  });

  it('names a parameter of a step that the processor refuses', () => {
    const shear: FlowNode = { id: 'n1', op: 'model.wind-shear', label: 'Hub_wind', params: { fromM: 10, toM: 120, alpha: 0.7 }, input: 'source', sinkTopic: cleanTopic('wind') };
    const nodes = [source, shear];
    expect(cleanChecks(nodes, ['abc123'], context, 'n1').filter((c) => !c.ok).map((c) => c.text)).toEqual([
      'The processor refuses a parameter of Hub_wind: alpha: "0.7" is out of range; expected a number from 0 to 0.6',
    ]);
    expect(runBlocker(nodes, ['abc123'], context)).toBe('The processor refuses a parameter of Hub_wind: alpha: "0.7" is out of range; expected a number from 0 to 0.6');
    expect(cleanChecks([source, { ...shear, params: { fromM: 10, toM: 120, alpha: 0.6 } }], ['abc123'], context, 'n1').every((c) => c.ok)).toBe(true);
    const wide = [source, { ...range, params: { min: 10, max: -10, mode: 'gap' } }, minute];
    expect(cleanChecks(wide, ['abc123'], context, 'n2').find((c) => !c.ok)?.text).toBe(
      'The processor refuses a parameter of Range_power: max: "-10" is out of range; expected a number of at least min 10'
    );
  });

  it('takes an emptied parameter that the processor gives a default', () => {
    const potential: FlowNode = { id: 'n1', op: 'model.power-curve', label: 'Potential_wind', params: { ratedKw: 3000, cutIn: 3, ratedWind: 12.5, cutOut: 25, count: '' }, input: 'source', sinkTopic: cleanTopic('wind') };
    expect(cleanChecks([source, potential], ['abc123'], context, 'n1').filter((c) => !c.ok)).toEqual([]);
    expect(toDagYaml({ ...doc, nodes: [source, potential] })).toContain('params: { ratedKw: 3000, cutIn: 3, ratedWind: 12.5, cutOut: 25 }\n');
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

  it('can be published as another signal: an estimate computed from the clean data of one', () => {
    const curve: FlowNode = { id: 'n1', op: 'model.power-curve', label: 'Potential_wind', params: { ratedKw: 3000, cutIn: 3, ratedWind: 12.5, cutOut: 25, count: 4 }, input: 'source', sinkTopic: cleanTopic('wind') };
    const fromClean: SignalContext = {
      ...context,
      signal: 'park/T02/WMET.HorWdSpd',
      grade: 'clean',
      target: { signal: 'park.p_avail_kw', title: 'Available power', metric: 'h2-chain.park.p_avail_kw', origin: 'ESTIMATED' },
    };
    const flow = { ...doc, sourceTopic: cleanTopic('wind'), nodes: [source, curve], context: fromClean };
    const yaml = toDagYaml(flow);
    expect(yaml).toContain('topics: [tsx.wind.clean.v2]');
    expect(yaml).toContain('op: model.power-curve');
    expect(yaml).toContain(
      'signal: { system: h2-chain, id: park.p_avail_kw, grade: clean, metric: h2-chain.park.p_avail_kw }, origin: ESTIMATED, madeOf: "park/T02/WMET.HorWdSpd@clean (SYNTHETIC)" }'
    );
    expect(cleanChecks(flow.nodes, ['abc123'], fromClean, 'n1').every((c) => c.ok)).toBe(true);
    // without a target, clean data of a signal is not published as itself again
    expect(cleanChecks(flow.nodes, ['abc123'], { ...fromClean, target: undefined }, 'n1')[0]).toMatchObject({ ok: false, text: expect.stringMatching(/Choose the signal/) });
    // the flow is listed under the signal it writes
    expect(summarize({ id: 30, name: 'x', definition: toDefinition(flow) })).toMatchObject({ signal: 'park.p_avail_kw', runnable: true });
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
