import { afterEach, describe, expect, it, vi } from 'vitest';
import { evaluate, parseNodes } from './flow';
import { OP_BY_ID } from './ops';
import { checkBrowserWork } from './workLimits';
import { Bucket, FlowNode, Params } from './types';

const source: FlowNode = { id: 'source', op: 'source', params: {}, label: 'Input', input: null };
const bucket: Bucket = { name: 'Input', xKind: 'time', xLabel: 'seconds', yLabel: 'V', series: ['a', 'b'].map((id) => ({ id, label: id, x: Float64Array.of(0, 1, 2, 3), y: Float64Array.of(0, 2, 0, 1) })) };
afterEach(() => vi.restoreAllMocks());

describe('safe restored node counters', () => {
  it.each(['n9007199254740992', `n${'9'.repeat(400)}`, 'n1000001'])('rejects %s', (id) => {
    expect(parseNodes([source, { id, op: 'norm.max', params: {}, label: 'Normalized', input: 'source' }])).toBeNull();
  });
});

describe('finite browser work limits before operator allocation', () => {
  const cases: [string, Params][] = [
    ['pair.es', { shuffles: '1000000000000000' }],
    ['pair.xcorr', { lags: 1e12 }],
    ['pair.coverage', { every: 1e-15 }],
    ['pair.coverage', { window: 1, step: 1e-15 }],
    ['align.vector', { every: 1e-15 }],
    ['resample', { every: 1e-15 }],
    ['stats.window', { window: 1e-15 }],
    ['feature.rolling', { window: 1e300 }],
    ['dfa', { scales: 1e15 }],
    ['histogram', { bins: 1e15 }],
    ['shuffle', { rounds: 1e15 }],
  ];
  it.each(cases)('refuses %s before its implementation executes (%j)', (op, params) => {
    const input = op === 'feature.rolling'
      ? { ...bucket, series: [{ ...bucket.series[0], x: Float64Array.from({ length: 20000 }, (_, i) => i), y: new Float64Array(20000) }] }
      : bucket;
    const apply = vi.spyOn(OP_BY_ID[op], 'apply').mockImplementation(() => { throw new Error('Unsafe computation reached'); });
    const nodes = [source, { id: 'n1', op, params, label: 'Result', input: 'source' }];
    expect(() => evaluate(input, nodes, 'n1')).toThrow(/browser work limit.*Select fewer series.*larger clock/);
    expect(apply).not.toHaveBeenCalled();
  });
  it('keeps a bounded resampling computation available', () => {
    const result = evaluate(bucket, [source, { id: 'n1', op: 'resample', params: { every: 2 }, label: 'Result', input: 'source' }], 'n1');
    expect(Array.from(result!.series[0].x)).toEqual([0, 2]);
  });
});


it('refuses excessive pairs before visiting series or allocating the pair list', () => {
  const series = new Array(1000);
  Object.defineProperty(series, '0', { get: () => { throw new Error('Pair enumeration reached'); } });
  expect(() => checkBrowserWork('pair.coverage', [{ ...bucket, series }], {})).toThrow(/browser work limit/);
});

it('estimates the work of each pair range with the clock of its two episodes', () => {
  // 1000 samples at 1 s, then 1001 at 10 s: 10 s holds the majority of the series, 1 s the whole first episode
  const x = Float64Array.from({ length: 2001 }, (_, i) => (i < 1000 ? i : 1000 + 10 * (i - 1000)));
  const series = ['a', 'b'].map((id) => ({ id, label: id, x, y: Float64Array.from(x, Math.sin) }));
  const episodes = series.flatMap((s) => [
    { seriesId: s.id, episodeId: `${s.id}-0`, start: 0, end: 1000 },
    { seriesId: s.id, episodeId: `${s.id}-1`, start: 1000, end: 11010 },
  ]);
  const input: Bucket = { ...bucket, t0Ms: 0, series, episodes };
  // 1000 + 1001 ticks of 2 * lags + 2 products and values each; one 10 s clock would count 100 + 1001 ticks
  expect(() => checkBrowserWork('pair.xcorr', [input], { lags: 30000 })).toThrow(/browser work limit/);
  expect(() => checkBrowserWork('pair.xcorr', [input], { lags: 20000 })).not.toThrow();
});
