import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { beforeEach, describe, expect, it } from 'vitest';
import { SOURCE_ID, evaluate, keepQuality, useFlow } from './flow';
import { OP_BY_ID, defaultParams } from './ops';
import { MAX_PAIRS, PAIR_COVERAGE, pairRanges, rangesOf, windowsOf } from './pairs';
import { canonicalKey, seriesIdOf, xxh64Hex } from './seriesKeys';
import { Bucket, EpisodeRange, Params, Series } from './types';

const T0 = Date.parse('2026-10-01T00:00:00Z');
const COVERAGE = OP_BY_ID[PAIR_COVERAGE.id];

function series(id: string, step: number, length: number, fn: (x: number) => number): Series {
  const x = Float64Array.from({ length: Math.round(length / step) }, (_, i) => i * step);
  return { id, label: id, x, y: Float64Array.from(x, fn) };
}

function ranges(seriesId: string, bounds: [number, number][]): EpisodeRange[] {
  return bounds.map(([start, end], k) => ({ seriesId, episodeId: `${seriesId.toLowerCase()}-${k}`, start, end }));
}

const TEN_MINUTES: [number, number][] = [[0, 600], [600, 1200], [1200, 1800]];

/** A and B at 1 s for 30 minutes, cut into three 10-minute episodes each (FIXED_DURATION). */
function park(b: Partial<Series> = {}, bBounds = TEN_MINUTES, t0Ms = T0): Bucket {
  return {
    name: 'park',
    xKind: 'time',
    xLabel: 't [s]',
    yLabel: 'y',
    t0Ms,
    series: [series('A', 1, 1800, (x) => Math.sin(x / 10)), { ...series('B', 1, 1800, (x) => Math.cos(x / 10)), ...b }],
    episodes: [...ranges('A', TEN_MINUTES), ...ranges('B', bBounds)],
  };
}

function coverage(first: Bucket, params: Params = {}, others: Bucket[] = []): Bucket {
  return COVERAGE.apply(first, { ...defaultParams(COVERAGE), ...params }, others);
}

const field = (out: Bucket, name: string) => out.series.filter((s) => s.metric === `pair.coverage.${name}`);

describe('series keys', () => {
  it('give the ids of the shared test vectors', () => {
    const file = resolve(process.cwd(), '../opentsx-model/src/test/resources/testvectors/series-id.json');
    const vectors: { metric: string; tags: Record<string, string>; canonical: string; seriesId: string }[] = JSON.parse(readFileSync(file, 'utf8'));
    expect(vectors.length).toBeGreaterThan(5);
    for (const v of vectors) {
      expect(canonicalKey(v.metric, v.tags)).toBe(v.canonical);
      expect(seriesIdOf(v.metric, v.tags)).toBe(v.seriesId);
    }
    // the reference value of xxHash64 for no input, seed 0
    expect(xxh64Hex(new Uint8Array(0))).toBe('ef46db3751d8e999');
  });
});

describe('pair windows', () => {
  it('counts n = floor((L - W) / S) + 1 windows and stamps each at its centre', () => {
    expect(windowsOf(0, 600, 120, 60).map((w) => w.centre)).toEqual([60, 120, 180, 240, 300, 360, 420, 480, 540]);
    // the window that would start at 500 ends after the range and is not computed
    const w = windowsOf(0, 600, 120, 50);
    expect(w).toHaveLength(10);
    expect(w[9]).toEqual({ start: 450, end: 570, centre: 510 });
    expect(windowsOf(100, 700, null, 0)).toEqual([{ start: 100, end: 700, centre: 400 }]);
    expect(windowsOf(0, 100, 120, 60)).toEqual([]);
  });

  it('pairs episodes with the same range, or overlapping ones on their intersection', () => {
    const a = ranges('A', TEN_MINUTES);
    const b = ranges('B', [[0, 600], [630, 1200], [1200, 1800]]);
    expect(pairRanges(a, b, 'same').map((r) => [r.start, r.end, r.a.episodeId, r.b.episodeId])).toEqual([
      [0, 600, 'a-0', 'b-0'],
      [1200, 1800, 'a-2', 'b-2'],
    ]);
    expect(pairRanges(a, b, 'intersect', 120).map((r) => [r.start, r.end])).toEqual([[0, 600], [630, 1200], [1200, 1800]]);
    // an intersection shorter than the window is dropped
    expect(pairRanges(a, b, 'intersect', 600).map((r) => r.start)).toEqual([0, 1200]);
    // times that differ by less than half a microsecond are the same time
    expect(pairRanges(a, ranges('B', [[1e-7, 600 - 1e-7]]), 'same')).toHaveLength(1);
  });

  it('counts a series without episodes as one range to its last sample plus one interval', () => {
    const b = park();
    expect(rangesOf({ ...b, episodes: undefined }, b.series[0])).toEqual([{ seriesId: 'A', episodeId: 'A@0', start: 0, end: 1800 }]);
  });
});

describe('pair coverage over sliding windows', () => {
  const out = coverage(park(), { window: 120, step: 60 });
  const [cov] = field(out, 'coverage');

  it('gives one regular episode per pair range on the window centres', () => {
    expect(out.xKind).toBe('time');
    expect(out.t0Ms).toBe(T0);
    expect(cov.x).toHaveLength(27);
    expect(Array.from(cov.x.slice(0, 10))).toEqual([60, 120, 180, 240, 300, 360, 420, 480, 540, 660]);
    expect(Array.from(cov.y).every((v) => v === 1)).toBe(true);
    expect(Array.from(field(out, 'ticks')[0].y.slice(0, 3))).toEqual([120, 120, 120]);
    const own = out.episodes!.filter((e) => e.seriesId === cov.id);
    // REGULAR: tStart = t0 + W/2, interval S, n = 9 values, end exclusive
    expect(own.map((e) => [e.start, e.end])).toEqual([[60, 600], [660, 1200], [1260, 1800]]);
    for (const e of own) expect(Array.from(cov.x).filter((x) => x >= e.start && x < e.end)).toHaveLength(9);
  });

  it('keys each field by metric and tags and labels the episodes with both inputs', () => {
    const tags = { a: 'A', b: 'B', window: 'PT120S', step: 'PT60S' };
    expect(cov.metric).toBe('pair.coverage.coverage');
    expect(cov.tags).toEqual(tags);
    expect(cov.id).toBe(seriesIdOf('pair.coverage.coverage', tags));
    expect(field(out, 'ticks')[0].id).toBe(seriesIdOf('pair.coverage.ticks', tags));
    const e = out.episodes!.find((r) => r.seriesId === cov.id && r.start === 660)!;
    expect(e.parent).toBe('a-1');
    expect(e.labels).toEqual({ 'pair.a': 'a-1', 'pair.b': 'b-1', 'pair.method': 'pair.coverage', 'pair.window': 'PT120S', 'pair.step': 'PT60S' });
  });

  it('puts the windows side by side when the step is 0', () => {
    const side = field(coverage(park(), { window: 120 }), 'coverage')[0];
    expect(side.x).toHaveLength(15);
    expect(side.tags?.step).toBe('PT120S');
  });
});

describe('pair coverage with window: episode', () => {
  it('gives a single point at the centre of each pair range', () => {
    const out = coverage(park());
    const [cov] = field(out, 'coverage');
    expect(Array.from(cov.x)).toEqual([300, 900, 1500]);
    expect(Array.from(field(out, 'ticks')[0].y)).toEqual([600, 600, 600]);
    expect(cov.tags).toEqual({ a: 'A', b: 'B', window: 'episode', step: 'episode' });
    const own = out.episodes!.filter((e) => e.seriesId === cov.id);
    expect(own.map((e) => e.start)).toEqual([300, 900, 1500]);
    expect(own.every((e) => Math.abs(e.end - e.start - 1e-6) < 1e-9)).toBe(true);
  });

  it('pairs on the intersection when the episodes of B are cut elsewhere', () => {
    const shifted: [number, number][] = [[0, 600], [630, 1200], [1200, 1800]];
    expect(field(coverage(park({}, shifted)), 'coverage')[0].x).toHaveLength(2);
    expect(Array.from(field(coverage(park({}, shifted), { pairing: 'intersect' }), 'coverage')[0].x)).toEqual([300, 915, 1500]);
    const sliding = field(coverage(park({}, shifted), { pairing: 'intersect', window: 120, step: 60 }), 'coverage')[0];
    // the middle range is 570 s long: 8 windows from 630 on
    expect(sliding.x).toHaveLength(9 + 8 + 9);
    expect(sliding.x[9]).toBe(690);
  });
});

describe('alignment and coverage', () => {
  // B has no values from 100 s to 160 s
  const gap = (x: number) => (x >= 100 && x < 160 ? NaN : Math.cos(x / 10));

  it('keeps a window below the minimum coverage as NaN with quality 1', () => {
    const out = coverage(park({ y: Float64Array.from({ length: 1800 }, (_, i) => gap(i)) }), { window: 120, step: 60, minCoverage: 0.8 });
    const [cov] = field(out, 'coverage');
    expect(cov.y[0]).toBeCloseTo(100 / 120, 12);
    expect(Number.isNaN(cov.y[1])).toBe(true);
    expect(Number.isNaN(cov.y[2])).toBe(true);
    expect(Array.from(cov.quality!.slice(0, 5))).toEqual([0, 1, 1, 0, 0]);
    // the time index stays regular
    expect(cov.x).toHaveLength(27);
    expect(out.note).toMatch(/2 below the minimum coverage 0\.8/);
  });

  it('fills gaps as-of up to the maximum age', () => {
    const b = { y: Float64Array.from({ length: 1800 }, (_, i) => gap(i)) };
    const filled = field(coverage(park(b), { window: 120, step: 60, fill: 'as-of', maxAge: 60 }), 'coverage')[0];
    expect(Array.from(filled.quality!).every((q) => q === 0)).toBe(true);
    expect(filled.y[1]).toBe(1);
    const half = field(coverage(park(b), { window: 120, step: 60, fill: 'as-of', maxAge: 30 }), 'coverage')[0];
    expect(half.y[1]).toBeCloseTo(90 / 120, 12);
  });

  it('takes the larger sampling interval as the clock', () => {
    const slow = { ...series('B', 10, 1800, (x) => Math.cos(x / 10)) };
    const out = coverage(park(slow), { window: 120, step: 60 });
    expect(field(out, 'ticks')[0].y[0]).toBe(12);
    expect(field(out, 'coverage')[0].y[0]).toBe(1);
    expect(out.note).toMatch(/clock 10 s/);
  });

  it('prints the clock without the noise of the sample spacing', () => {
    const fast = (id: string) => series(id, 0.001, 1, Math.sin);
    const b: Bucket = { ...park(), series: [fast('A'), fast('B')], episodes: undefined };
    expect(coverage(b).note).toMatch(/clock 0\.001 s\n/);
  });

  it('counts only the clock ticks that lie inside the window, on the epoch', () => {
    // x = 0 is half a second after a full second: the tick of the first sample begins before the window
    const out = coverage(park({}, TEN_MINUTES, T0 + 500), { window: 120, step: 60, every: 1 });
    expect(field(out, 'ticks')[0].y[0]).toBe(119);
    expect(field(out, 'coverage')[0].y[0]).toBe(1);
  });
});

describe('pairs of the inputs', () => {
  it('compares every pair of one input in bucket order', () => {
    const b = park();
    b.series.push(series('C', 1, 1800, (x) => x));
    b.episodes!.push(...ranges('C', TEN_MINUTES));
    const out = coverage(b);
    expect(field(out, 'coverage').map((s) => `${s.tags!.a}-${s.tags!.b}`)).toEqual(['A-B', 'A-C', 'B-C']);
  });

  it('compares each series of A with each of B on the time axis of A', () => {
    const b: Bucket = {
      name: 'later',
      xKind: 'time',
      xLabel: 't [s]',
      yLabel: 'y',
      t0Ms: T0 + 600_000,
      series: [series('X', 1, 1200, (x) => x)],
      episodes: ranges('X', [[0, 600], [600, 1200]]),
    };
    const out = coverage(park(), {}, [b]);
    const cov = field(out, 'coverage');
    expect(cov.map((s) => `${s.tags!.a}-${s.tags!.b}`)).toEqual(['A-X', 'B-X']);
    // the episodes of X begin 10 minutes after those of A: they pair with the second and third
    expect(Array.from(cov[0].x)).toEqual([900, 1500]);
    expect(out.episodes!.find((e) => e.seriesId === cov[0].id)!.labels!['pair.b']).toBe('x-0');
  });

  it('refuses what it cannot pair', () => {
    const one = { ...park(), series: park().series.slice(0, 1) };
    expect(() => coverage(one)).toThrow(/A pair needs two/);
    const many = { ...park(), series: Array.from({ length: 21 }, (_, i) => series(`S${i}`, 1, 60, Math.sin)) };
    expect(() => coverage(many)).toThrow(new RegExp(`210 pairs, more than ${MAX_PAIRS}`));
    expect(() => coverage({ ...park(), xKind: 'frequency' })).toThrow(/time axis/);
    expect(() => coverage(park(), { window: 'ten' })).toThrow(/neither episode nor a width/);
    expect(() => coverage(park(), {}, [park(), park()])).toThrow(/two inputs at most/);
  });

  it('says which pair ranges were too short for the window', () => {
    const out = coverage(park(), { window: 700 });
    expect(field(out, 'coverage')[0].x).toHaveLength(0);
    expect(out.note).toMatch(/3 pair range\(s\) shorter than the window/);
  });
});

describe('a pair node in the flow', () => {
  const state = () => useFlow.getState();
  let p: string;
  let z: string;
  let sm: string;

  beforeEach(() => {
    useFlow.setState({ track: 'EXP2', domain: 'wind', sourceTopic: 'tsx.wind.episodes.v2', nodes: [], source: null });
    state().loadBucket(park(), { kind: 'test', detail: '' });
    z = state().applyOp('norm.zscore')!;
    state().select(SOURCE_ID);
    sm = state().applyOp('smooth.movavg', { window: 5 })!;
    state().select(SOURCE_ID);
    p = state().applyOp(PAIR_COVERAGE.id, { window: 120, step: 60 })!;
  });

  it('reads A and B and no third input', () => {
    expect(state().addInput(p, z)).toBe(true);
    expect(state().addInput(p, sm)).toBe(false);
    const out = evaluate(state().source, state().nodes, p)!;
    // 2 series of the source times 2 of the standardized bucket, 2 fields each
    expect(out.series).toHaveLength(8);
    expect(out.name).toBe(`Coverage_${state().nodes.find((n) => n.id === SOURCE_ID)!.label}`);
  });

  it('drops the quality bytes where an operator builds a new time axis', () => {
    state().select(p);
    const norm = state().applyOp('norm.max')!;
    state().select(p);
    const resampled = state().applyOp('resample', { every: 600 })!;
    const pair = evaluate(state().source, state().nodes, p)!;
    expect(pair.series[0].quality).toHaveLength(27);
    expect(evaluate(state().source, state().nodes, norm)!.series[0].quality).toBe(pair.series[0].quality);
    expect(evaluate(state().source, state().nodes, resampled)!.series[0].quality).toBeUndefined();
    // bytes an operator made itself are kept
    expect(keepQuality(pair, [state().source!])).toBe(pair);
  });
});
