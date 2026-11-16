/** Operator example pair.es (docs/user-manual/17-operators.md): B fires independently of A for one hour, then 3 s after A. */
import { describe, expect, it } from 'vitest';
import { OP_BY_ID, mulberry32 } from '../ops';
import { Bucket } from '../types';
import { ExampleChart } from './chart';

const T0 = Date.parse('2026-10-01T00:00:00Z');
const N = 7200;
const HOUR = 3600;
const DELAY = 3;

function eventTimes(rnd: () => number): number[] {
  const times: number[] = [];
  for (let t = 5 + Math.floor(rnd() * 40); t < N - 5; t += 7 + Math.floor(-23 * Math.log(1 - rnd()))) times.push(t);
  return times;
}

function spikes(rnd: () => number, times: number[]): Float64Array {
  const y = Float64Array.from({ length: N }, () => 0.3 * (rnd() - 0.5));
  for (const t of times) y[t] = 4 + rnd();
  return y;
}

const mean = (v: number[]) => v.reduce((s, x) => s + x, 0) / v.length;

describe('pair.es example', () => {
  it('gives q_total near q_rand while B is independent, and q_total near 1 with q_ab above q_ba while B follows A', () => {
    const rnd = mulberry32(1);
    const seconds = Float64Array.from({ length: N }, (_, i) => i);
    const timesA = eventTimes(rnd);
    const timesB = [...eventTimes(rnd).filter((t) => t < HOUR), ...timesA.filter((t) => t >= HOUR).map((t) => t + DELAY)];
    const spikesA = spikes(rnd, timesA);
    const spikesB = spikes(rnd, timesB);

    // usage:begin
    const pair: Bucket = {
      name: 'events', xKind: 'time', xLabel: 't [s]', yLabel: 'y', t0Ms: T0,
      series: [
        { id: 'A', label: 'A', x: seconds, y: spikesA },
        { id: 'B', label: 'B', x: seconds, y: spikesB },
      ],
    };
    const es = OP_BY_ID['pair.es'];
    const out = es.apply(pair, { window: 600, sigma: 2, shuffles: 10, seed: 1 });
    const field = (name: string) => out.series.find((s) => s.metric === `pair.es.${name}`)!;
    const [qTotal, qAb, qBa, qRand] = ['q_total', 'q_ab', 'q_ba', 'q_rand'].map(field);
    // usage:end

    const centres = Array.from(qTotal.x);
    expect(centres).toEqual(Array.from({ length: 12 }, (_, k) => 300 + 600 * k));
    const first = (s: { y: Float64Array }) => Array.from(s.y.slice(0, 6));
    const second = (s: { y: Float64Array }) => Array.from(s.y.slice(6));
    first(qTotal).forEach((q, k) => expect(Math.abs(q - first(qRand)[k])).toBeLessThan(0.3));
    expect(Math.abs(mean(first(qTotal)) - mean(first(qRand)))).toBeLessThan(0.08);
    expect(Math.abs(mean(first(qAb)) - mean(first(qBa)))).toBeLessThan(0.2);
    second(qTotal).forEach((q, k) => {
      expect(q).toBeGreaterThan(0.95);
      expect(q - second(qRand)[k]).toBeGreaterThan(0.25);
      expect(second(qAb)[k]).toBeGreaterThan(0.95);
      expect(second(qBa)[k]).toBe(0);
    });

    const minutes = centres.map((t) => t / 60);
    const pairOf = (p: { y: Float64Array }, q: { y: Float64Array }, part: (s: { y: Float64Array }) => number[]) =>
      `${mean(part(p)).toFixed(2)} and ${mean(part(q)).toFixed(2)}`;
    new ExampleChart('pair.es', 'Event synchronisation: B follows A by 3 s only in hour 2')
      .panel('Q and its chance level per 10 min window', 'window centre [min]', 'Q')
      .add('q_total', 'line', minutes, qTotal.y)
      .add('q_rand', 'line', minutes, qRand.y)
      .note('expected: equal, then 1')
      .note(`1st hour: ${pairOf(qTotal, qRand, first)}`)
      .note(`2nd hour: ${pairOf(qTotal, qRand, second)}`)
      .panel('Direction per 10 min window', 'window centre [min]', 'q')
      .add('q_ab', 'line', minutes, qAb.y)
      .add('q_ba', 'line', minutes, qBa.y)
      .note('expected: equal, then 1, 0')
      .note(`1st hour: ${pairOf(qAb, qBa, first)}`)
      .note(`2nd hour: ${pairOf(qAb, qBa, second)}`)
      .write();
  });
});
