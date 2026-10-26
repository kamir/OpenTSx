import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { OP_BY_ID, defaultParams } from './ops';
import { Bucket, Params } from './types';
import { PAIR_XCORR, crossCorrelation, peakOf } from './xcorr';

/** What the original KreuzKorrelation and CCFunction returned (tools/pair_xcorr_vectors.sh). */
interface OldWindow {
  centre: number;
  cc: number[];
  tMax: number;
  ttMax: number;
  strength: number;
}
interface XcorrCase {
  name: string;
  lags: number;
  window: number | null;
  step: number | null;
  a: number[];
  b: number[];
  windows?: OldWindow[];
  sliding?: { centre: number; cc: number }[];
}

const FILE = resolve(process.cwd(), '../docs/datamodel-2026/testvectors/pair-xcorr.v1.json');
const VECTORS: { schema: string; source: string; cases: XcorrCase[] } = JSON.parse(readFileSync(FILE, 'utf8'));
const byName = (prefix: string) => VECTORS.cases.find((c) => c.name.startsWith(prefix))!;

const T0 = Date.parse('2026-10-01T00:00:00Z');
const XCORR = OP_BY_ID[PAIR_XCORR.id];

function bucket(a: number[], b: number[], step = 1): Bucket {
  const x = Float64Array.from(a, (_, i) => i * step);
  return {
    name: 'pair',
    xKind: 'time',
    xLabel: 't [s]',
    yLabel: 'y',
    t0Ms: T0,
    series: [
      { id: 'A', label: 'A', x, y: Float64Array.from(a) },
      { id: 'B', label: 'B', x, y: Float64Array.from(b) },
    ],
  };
}

function xcorr(b: Bucket, params: Params) {
  const out = XCORR.apply(b, { ...defaultParams(XCORR), ...params });
  const field = (name: string) => out.series.find((s) => s.metric === `pair.xcorr.${name}`)!;
  return { out, max: field('max'), lag: field('lag'), strength: field('strength') };
}

function expectClose(actual: ArrayLike<number>, expected: number[]) {
  expect(actual).toHaveLength(expected.length);
  expected.forEach((v, i) => expect(actual[i], `index ${i}`).toBeCloseTo(v, 12));
}

describe('test vectors of the original implementation', () => {
  it('name their source', () => {
    expect(VECTORS.schema).toBe('opentsx.testvectors.pair-xcorr.v1');
    expect(VECTORS.source).toMatch(/^kamir\/WikiExplorer\.NG@7b56e3d/);
    expect(VECTORS.cases).toHaveLength(5);
  });

  it('give the same CC function and strength as KreuzKorrelation.calcKR and calcStrength_VERSION_A', () => {
    for (const c of VECTORS.cases.filter((v) => v.window === null)) {
      const [old] = c.windows!;
      const cc = crossCorrelation(c.a, c.b, c.lags);
      expectClose(cc, old.cc);
      expect(peakOf(cc).strength, c.name).toBeCloseTo(old.strength, 12);
    }
  });

  it('find the peak the old code finds when it is positive', () => {
    const c = byName('B follows A by 3');
    const old = c.windows![0];
    const peak = peakOf(crossCorrelation(c.a, c.b, c.lags));
    expect(peak.value).toBeCloseTo(old.tMax, 12);
    expect(peak.index - c.lags).toBe(old.ttMax);
    expect(old.ttMax).toBe(-3);
  });

  it('take max and lag at the extreme the strength measures, also when it is negative', () => {
    const c = byName('B is A inverted');
    const old = c.windows![0];
    const peak = peakOf(crossCorrelation(c.a, c.b, c.lags));
    // the old t_max looks at positive values only: a side peak of 0.19 at k = 5
    expect(old.tMax).toBeLessThan(0.2);
    expect(old.ttMax).toBe(5);
    expect(peak.value).toBeCloseTo(Math.min(...old.cc), 12);
    expect(peak.value).toBeLessThan(-0.9);
    // the old strength already measured this extreme
    expect(peak.strength).toBeCloseTo(old.strength, 12);
  });
});

describe('pair.xcorr over pair windows', () => {
  it('gives max, lag and strength at the centre of the whole range', () => {
    for (const c of VECTORS.cases.filter((v) => v.window === null)) {
      const old = c.windows![0];
      const peak = peakOf(old.cc);
      const r = xcorr(bucket(c.a, c.b), { lags: c.lags, every: 1 });
      expect(Array.from(r.max.x), c.name).toEqual([old.centre]);
      expect(r.max.y[0], c.name).toBeCloseTo(peak.value, 12);
      expect(r.lag.y[0], c.name).toBe(c.lags - peak.index);
      expect(r.strength.y[0], c.name).toBeCloseTo(old.strength, 12);
    }
  });

  it('reports B following A by 3 s as lag +3 s', () => {
    const c = byName('B follows A by 3');
    expect(xcorr(bucket(c.a, c.b), { lags: 5 }).lag.y[0]).toBe(3);
  });

  it('gives each sliding window the CC function of the old code on that window', () => {
    const c = byName('B follows A by 2 samples, sliding');
    const r = xcorr(bucket(c.a, c.b), { lags: c.lags, window: c.window!, step: c.step!, every: 1 });
    expect(Array.from(r.max.x)).toEqual(c.windows!.map((w) => w.centre));
    c.windows!.forEach((w, i) => {
      expect(r.max.y[i]).toBeCloseTo(w.tMax, 12);
      expect(r.lag.y[i]).toBe(-w.ttMax);
      expect(r.strength.y[i]).toBeCloseTo(w.strength, 12);
    });
  });

  it('with lags 0 is calcKR_SLIDINGWINDOW, plus the last window the old loop leaves out', () => {
    const c = byName('calcKR_SLIDINGWINDOW');
    const r = xcorr(bucket(c.a, c.b), { lags: 0, window: c.window!, step: c.step!, every: 1 });
    const old = c.sliding!;
    expect(old).toHaveLength(48);
    expect(r.max.x).toHaveLength(49);
    expect(Array.from(r.max.x.slice(0, 48))).toEqual(old.map((p) => p.centre));
    old.forEach((p, i) => expect(r.max.y[i], `window ${i}`).toBeCloseTo(p.cc, 12));
    expect(r.max.x[48]).toBe(56);
    // one value has no spread
    expect(Number.isNaN(r.strength.y[0])).toBe(true);
  });

  it('measures the lag in seconds of the clock', () => {
    const c = byName('B follows A by 3');
    // samples 10 s apart: B follows A by 30 s
    expect(xcorr(bucket(c.a, c.b, 10), { lags: 5 }).lag.y[0]).toBe(30);
  });

  it('labels the result episodes with the lag range', () => {
    const c = byName('unrelated');
    const { out } = xcorr(bucket(c.a, c.b), { lags: 7 });
    expect(out.episodes![0].labels!['pair.lags']).toBe('7');
    expect(out.note).toMatch(/^pair\.xcorr, lags 7 over 1 pair/);
  });
});

describe('gaps and edge cases', () => {
  const c = VECTORS.cases[0];

  it('counts a missing tick as the mean of its series', () => {
    const b = [...c.b];
    b[20] = NaN;
    const finite = b.filter(Number.isFinite);
    const filled = b.map((v) => (Number.isFinite(v) ? v : finite.reduce((s, x) => s + x, 0) / finite.length));
    const gap = crossCorrelation(c.a, b, 5);
    // filled with the mean, the products are the same; only the standard deviation of B differs, by its divisor
    const sd = (v: number[]) => {
      const m = v.reduce((s, x) => s + x, 0) / v.length;
      return Math.sqrt(v.reduce((s, x) => s + (x - m) * (x - m), 0) / (v.length - 1));
    };
    const scale = sd(filled) / sd(finite);
    expectClose(gap, Array.from(crossCorrelation(c.a, filled, 5), (v) => v * scale));
  });

  it('gives NaN when a series does not vary in the window', () => {
    const flat = c.a.map(() => 1);
    expect(Array.from(crossCorrelation(c.a, flat, 2)).every(Number.isNaN)).toBe(true);
    const r = xcorr(bucket(c.a, flat), { lags: 2 });
    expect([r.max.y[0], r.lag.y[0], r.strength.y[0]].every(Number.isNaN)).toBe(true);
    // the window itself was covered: NaN here is not a quality problem
    expect(r.max.quality![0]).toBe(0);
  });

  it('refuses a lag range that is not a whole number of ticks', () => {
    expect(() => xcorr(bucket(c.a, c.b), { lags: 2.5 })).toThrow(/whole number of clock ticks/);
    expect(() => xcorr(bucket(c.a, c.b), { lags: -1 })).toThrow(/whole number of clock ticks/);
  });
});
