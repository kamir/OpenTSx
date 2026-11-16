/** Operator example shuffle (docs/user-manual/17-operators.md): shuffling keeps every value of an AR(1) series and destroys its autocorrelation 0.9^k. */
import { describe, expect, it } from 'vitest';
import { OP_BY_ID, mulberry32 } from '../ops';
import { Bucket } from '../types';
import { ExampleChart } from './chart';

const T0 = Date.parse('2026-10-01T00:00:00Z');
const PHI = 0.9;
const LAGS = 30;

function gauss(rnd: () => number): number {
  return Math.sqrt(-2 * Math.log(1 - rnd())) * Math.cos(2 * Math.PI * rnd());
}

describe('shuffle example', () => {
  it('keeps the sorted values and turns the autocorrelation 0.9^k into noise around 0', () => {
    const rnd = mulberry32(3);
    const seconds = Float64Array.from({ length: 2000 }, (_, i) => i);
    const ar = new Float64Array(2000);
    for (let i = 1; i < ar.length; i++) ar[i] = PHI * ar[i - 1] + gauss(rnd);

    // usage:begin
    const raw: Bucket = {
      name: 'ar1',
      xKind: 'time',
      xLabel: 't [s]',
      yLabel: 'y',
      t0Ms: T0,
      series: [{ id: 'ar1', label: 'AR(1) 0.9', x: seconds, y: ar }],
    };
    const shuffle = OP_BY_ID['shuffle'];
    const out = shuffle.apply(raw, { rounds: 1, seed: 1 }).series[0];
    const acf = OP_BY_ID['acf'];
    const before = acf.apply(raw, { maxLag: 30 }).series[0];
    const after = acf.apply({ ...raw, series: [out] }, { maxLag: 30 }).series[0];
    // usage:end

    const sorted = (y: ArrayLike<number>) => Array.from(y).sort((a, b) => a - b);
    expect(sorted(out.y)).toEqual(sorted(ar));
    expect(Array.from(out.x)).toEqual(Array.from(seconds));
    expect(Array.from(out.y)).not.toEqual(Array.from(ar));
    expect(Math.abs(before.y[1] - PHI)).toBeLessThan(0.03);
    const rest = Array.from(after.y.slice(1)).map(Math.abs);
    const worst = Math.max(...rest);
    expect(worst).toBeLessThan(3.5 / Math.sqrt(ar.length));
    const same = sorted(out.y).filter((v, i) => v === sorted(ar)[i]).length;

    const lags = Array.from({ length: LAGS }, (_, k) => k);
    const shown = 100;
    const ranks = (step: number) => Array.from({ length: ar.length / step }, (_, j) => j * step);
    new ExampleChart('shuffle', 'shuffle: the same values, without the autocorrelation 0.9^k')
      .panel(`Input and output, first ${shown} s`, 't [s]', 'value')
      .add('input', 'line', seconds.slice(0, shown), ar.slice(0, shown))
      .add('shuffled', 'line', seconds.slice(0, shown), out.y.slice(0, shown))
      .panel('Sorted values', 'rank', 'value')
      .add('input', 'line', ranks(10), ranks(10).map((r) => sorted(ar)[r]))
      .add('shuffled', 'point', ranks(80), ranks(80).map((r) => sorted(out.y)[r]))
      .note(`sorted values equal: ${same} of ${ar.length}`)
      .note(`C(1) before: expected 0.9, measured ${before.y[1].toFixed(3)}`)
      .note(`|C(k)| after: expected < ${(3.5 / Math.sqrt(ar.length)).toFixed(2)}, max ${worst.toFixed(3)}`)
      .panel('Autocorrelation', 'lag k [s]', 'C(k)')
      .add('input', 'point', lags, before.y)
      .add('shuffled', 'point', lags, after.y)
      .add('0.9^k', 'line', lags, lags.map((k) => PHI ** k))
      .write();
  });
});
