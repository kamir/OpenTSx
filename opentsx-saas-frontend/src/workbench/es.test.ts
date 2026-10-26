import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { PAIR_ES, detectEvents, eventSync, isiShuffle, surrogateSync } from './es';
import { OP_BY_ID, defaultParams } from './ops';
import { mulberry32 } from './random';
import { Bucket, Params } from './types';

/** What python-package/opentsx/algorithms/event_sync.py returned (tools/pair_es_vectors.py). */
interface EsCase {
  name: string;
  a?: number[];
  b?: number[];
  sigma?: number;
  tauMax: number | null;
  eventsA: number[];
  eventsB: number[];
  expect: { q_total: number; q_ab: number; q_ba: number; q_rand: number };
}

const FILE = resolve(process.cwd(), '../docs/datamodel-2026/testvectors/pair-es.v1.json');
const VECTORS: { schema: string; shuffles: number; seed: number; mulberry32: Record<string, number[]>; cases: EsCase[] } =
  JSON.parse(readFileSync(FILE, 'utf8'));
const byName = (name: string) => VECTORS.cases.find((c) => c.name === name)!;

const T0 = Date.parse('2026-10-01T00:00:00Z');
const ES = OP_BY_ID[PAIR_ES.id];

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

function es(b: Bucket, params: Params = {}) {
  const out = ES.apply(b, { ...defaultParams(ES), every: 1, ...params });
  const field = (name: string) => out.series.find((s) => s.metric === `pair.es.${name}`)!;
  return { out, q: field('q_total'), qab: field('q_ab'), qba: field('q_ba'), qrand: field('q_rand') };
}

describe('shared test vectors with the Python package', () => {
  it('draw the same random numbers', () => {
    expect(VECTORS.schema).toBe('opentsx.testvectors.pair-es.v1');
    for (const [seed, expected] of Object.entries(VECTORS.mulberry32)) {
      const rand = mulberry32(Number(seed));
      expect(expected.map(() => rand())).toEqual(expected);
    }
  });

  it.each(VECTORS.cases.map((c) => [c.name, c] as const))('%s', (_, c) => {
    if (c.a && c.b) {
      expect(detectEvents(c.a, c.sigma!)).toEqual(c.eventsA);
      expect(detectEvents(c.b, c.sigma!)).toEqual(c.eventsB);
    }
    const sync = eventSync(c.eventsA, c.eventsB, c.tauMax);
    expect(sync.q).toBeCloseTo(c.expect.q_total, 12);
    expect(sync.qab).toBeCloseTo(c.expect.q_ab, 12);
    expect(sync.qba).toBeCloseTo(c.expect.q_ba, 12);
    expect(surrogateSync(c.eventsA, c.eventsB, VECTORS.shuffles, VECTORS.seed, c.tauMax)).toBeCloseTo(c.expect.q_rand, 12);
  });
});

describe('pair.es over pair windows', () => {
  it('gives the four fields of the Python reference for the whole range', () => {
    for (const c of VECTORS.cases.filter((v) => v.a)) {
      const r = es(bucket(c.a!, c.b!), { shuffles: VECTORS.shuffles, seed: VECTORS.seed });
      expect(Array.from(r.q.x), c.name).toEqual([c.a!.length / 2]);
      expect(r.q.y[0], c.name).toBeCloseTo(c.expect.q_total, 12);
      expect(r.qab.y[0], c.name).toBeCloseTo(c.expect.q_ab, 12);
      expect(r.qba.y[0], c.name).toBeCloseTo(c.expect.q_ba, 12);
      expect(r.qrand.y[0], c.name).toBeCloseTo(c.expect.q_rand, 12);
    }
  });

  it('shows B following A as q_ab above q_ba, and above the chance level', () => {
    const c = byName('B follows A by 2 ticks');
    const r = es(bucket(c.a!, c.b!));
    expect(r.qab.y[0]).toBeGreaterThan(r.qba.y[0]);
    expect(r.q.y[0]).toBeGreaterThan(r.qrand.y[0] + 0.3);
    // swapped inputs swap the direction
    const back = es(bucket(c.b!, c.a!));
    expect(back.qab.y[0]).toBeCloseTo(r.qba.y[0], 12);
    expect(back.qba.y[0]).toBeCloseTo(r.qab.y[0], 12);
  });

  it('converts tauMax from seconds to clock ticks', () => {
    // samples every 2 s, so the clock is 2 s; B peaks 5 ticks (10 s) after A
    const a = Array.from({ length: 200 }, (_, i) => (i % 50 === 10 ? 5 : 0));
    const b = Array.from({ length: 200 }, (_, i) => (i % 50 === 15 ? 5 : 0));
    const run = (tauMax: number) => es(bucket(a, b, 2), { every: 0, tauMax });
    expect(run(0).q.y[0]).toBe(1);
    // 12 s are 6 ticks: B within; 10 s are 5 ticks: the window is open, B just outside
    expect(run(12).q.y[0]).toBe(1);
    expect(run(10).q.y[0]).toBe(0);
    expect(run(10).out.episodes![0].labels!['pair.tauMax']).toBe('PT10S');
    expect(run(0).out.episodes![0].labels!['pair.tauMax']).toBe('none');
  });

  it('labels the result episodes with its settings', () => {
    const c = byName('independent series');
    const { out } = es(bucket(c.a!, c.b!), { sigma: 1.5, shuffles: 4, seed: 9 });
    expect(out.episodes![0].labels).toMatchObject({ 'pair.method': 'pair.es', 'pair.sigma': '1.5', 'pair.shuffles': '4', 'pair.seed': '9' });
    expect(out.note).toMatch(/^pair\.es, sigma 1\.5, tauMax none, shuffles 4, seed 9 over 1 pair/);
  });

  it('refuses settings it cannot use', () => {
    const c = byName('independent series');
    expect(() => es(bucket(c.a!, c.b!), { shuffles: 0 })).toThrow(/shuffles/);
    expect(() => es(bucket(c.a!, c.b!), { seed: 1.5 })).toThrow(/seed/);
    expect(() => es(bucket(c.a!, c.b!), { tauMax: -1 })).toThrow(/tauMax/);
  });
});

describe('events and surrogates', () => {
  it('finds no event next to a gap', () => {
    const v = [0, 0, 9, 0, 0, 0, 9, NaN, 0, 0, 0, 0];
    expect(detectEvents(v, 1)).toEqual([2]);
  });

  it('shuffles the intervals and keeps the first and the last event', () => {
    const events = [2, 9, 15, 31, 47, 52, 70];
    const shuffled = isiShuffle(events, mulberry32(7));
    expect(shuffled[0]).toBe(2);
    expect(shuffled[shuffled.length - 1]).toBe(70);
    const gaps = (e: number[]) => e.slice(1).map((t, i) => t - e[i]).sort((p, q) => p - q);
    expect(gaps(shuffled)).toEqual(gaps(events));
    expect(shuffled).not.toEqual(events);
    expect(isiShuffle([4, 9], mulberry32(1))).toEqual([4, 9]);
  });

  it('gives 0 without events', () => {
    expect(eventSync([], [1, 2], null)).toEqual({ q: 0, qab: 0, qba: 0 });
    expect(surrogateSync([3], [], 5, 1, null)).toBe(0);
  });
});
