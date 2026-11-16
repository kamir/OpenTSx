/** Operator example entropy.window (docs/user-manual/17-operators.md): values that use 2 of 16 bins have an entropy of 1 bit, values spread over all 16 bins 4 bit. */
import { describe, expect, it } from 'vitest';
import { OP_BY_ID, mulberry32 } from '../ops';
import { Bucket } from '../types';
import { ExampleChart } from './chart';

const T0 = Date.parse('2026-10-01T00:00:00Z');
const N = 8000;
const CHANGE = 4000;
const LENGTH = 800;
const BINS = 16;

function expectedBits(start: number): number {
  const end = Math.min(start + LENGTH, N);
  const wide = Math.max(0, end - Math.max(start, CHANGE)) / (end - start);
  const q = wide / BINS;
  const p = (1 - wide) / 2 + q;
  return -2 * p * Math.log2(p) - (q > 0 ? (BINS - 2) * q * Math.log2(q) : 0);
}

describe('entropy.window example', () => {
  it('gives 1 bit while the values use 2 of 16 bins and 4 bit once they spread over all 16', () => {
    const rnd = mulberry32(1);
    const seconds = Float64Array.from({ length: N }, (_, i) => i);
    const y = Float64Array.from(seconds, (_, i) => (i < CHANGE ? 0.5 + rnd() : 16 * rnd()));

    // usage:begin
    const raw: Bucket = {
      name: 'sensor',
      xKind: 'time',
      xLabel: 't [s]',
      yLabel: 'y',
      t0Ms: T0,
      series: [{ id: 'sensor', label: 'sensor', x: seconds, y }],
    };
    const entropy = OP_BY_ID['entropy.window'];
    const h = entropy.apply(raw, { length: 800, bins: 16 }).series[0];
    // usage:end

    const starts = Array.from(h.x);
    expect(starts).toEqual(Array.from({ length: 40 }, (_, k) => k * 200));
    const expected = starts.map(expectedBits);
    const error = Math.max(...expected.map((e, k) => Math.abs(h.y[k] - e)));
    expect(error).toBeLessThan(0.15);
    const before = Array.from(h.y).filter((_, k) => starts[k] + LENGTH <= CHANGE);
    const after = Array.from(h.y).filter((_, k) => starts[k] >= CHANGE && starts[k] + LENGTH <= N);
    before.forEach((v) => expect(Math.abs(v - 1)).toBeLessThan(0.015));
    after.forEach((v) => expect(Math.abs(v - 4)).toBeLessThan(0.07));

    const every = Array.from({ length: N / 20 }, (_, j) => j * 20);
    new ExampleChart('entropy.window', 'entropy.window: 1 bit on 2 of 16 bins, 4 bit on all 16')
      .panel('Input, every 20th value', 't [s]', 'value')
      .add('input', 'point', every, every.map((i) => y[i]))
      .panel(`Entropy in windows of ${LENGTH} samples, step ${LENGTH / 4}`, 'window start [sample]', 'H [bit]')
      .add('entropy', 'point', starts, h.y)
      .add('expected', 'line', starts, expected)
      .note('expected: 1 bit, then 4 bit')
      .note(`measured: ${Math.min(...before).toFixed(3)} to ${Math.max(...before).toFixed(3)}`)
      .note(`then ${Math.min(...after).toFixed(3)} to ${Math.max(...after).toFixed(3)}`)
      .write();
  });
});
