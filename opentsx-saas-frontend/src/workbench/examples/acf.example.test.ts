/** Operator example acf (docs/user-manual/17-operators.md): the autocorrelation of an AR(1) series with phi 0.7 is 0.7^k, that of white noise is 0 for every lag above 0. */
import { describe, expect, it } from 'vitest';
import { OP_BY_ID, mulberry32 } from '../ops';
import { Bucket } from '../types';
import { ExampleChart } from './chart';

const T0 = Date.parse('2026-10-01T00:00:00Z');
const PHI = 0.7;
const N = 50000;

function gauss(rnd: () => number): number {
  return Math.sqrt(-2 * Math.log(1 - rnd())) * Math.cos(2 * Math.PI * rnd());
}

describe('acf example', () => {
  it('gives C(k) = 0.7^k for AR(1) with phi 0.7 and C(k) near 0 for white noise', () => {
    const rnd = mulberry32(1);
    const seconds = Float64Array.from({ length: N }, (_, i) => i);
    const ar = new Float64Array(N);
    ar[0] = gauss(rnd);
    for (let i = 1; i < N; i++) ar[i] = PHI * ar[i - 1] + Math.sqrt(1 - PHI * PHI) * gauss(rnd);
    const noise = Float64Array.from(seconds, () => gauss(rnd));

    // usage:begin
    const raw: Bucket = {
      name: 'signals',
      xKind: 'time',
      xLabel: 't [s]',
      yLabel: 'y',
      t0Ms: T0,
      series: [
        { id: 'ar1', label: 'AR(1) phi 0.7', x: seconds, y: ar },
        { id: 'noise', label: 'white noise', x: seconds, y: noise },
      ],
    };
    const acf = OP_BY_ID['acf'];
    const [c, w] = acf.apply(raw, { maxLag: 20 }).series;
    // usage:end

    const lags = Array.from(c.x);
    expect(lags).toEqual(Array.from({ length: 20 }, (_, k) => k));
    expect(c.y[0]).toBeCloseTo(1, 12);
    expect(w.y[0]).toBeCloseTo(1, 12);
    const arError = Math.max(...lags.map((k) => Math.abs(c.y[k] - PHI ** k)));
    const noiseError = Math.max(...lags.slice(1).map((k) => Math.abs(w.y[k])));
    expect(arError).toBeLessThan(0.03);
    expect(noiseError).toBeLessThan(4 / Math.sqrt(N));

    const shown = Array.from({ length: 100 }, (_, i) => i);
    new ExampleChart('acf', 'acf: C(k) = 0.7^k for AR(1), C(k) = 0 for white noise')
      .panel('Input, first 100 s', 't [s]', 'value, white noise shifted by -5')
      .add('AR(1) phi 0.7', 'line', shown, shown.map((i) => ar[i]))
      .add('white noise', 'line', shown, shown.map((i) => noise[i] - 5))
      .panel('Autocorrelation', 'lag k [sample]', 'C(k)')
      .add('AR(1) phi 0.7', 'point', lags, c.y)
      .add('white noise', 'point', lags, w.y)
      .add('0.7^k', 'line', lags, lags.map((k) => PHI ** k))
      .panel('Measured against expected', 'expected C(k)', 'measured C(k)')
      .add('AR(1) phi 0.7', 'point', lags.map((k) => PHI ** k), c.y)
      .add('white noise', 'point', lags.map((k) => (k === 0 ? 1 : 0)), w.y)
      .note('expected: 0.7^k, and 0 for noise')
      .note(`AR(1): max difference ${arError.toFixed(3)}`)
      .note(`noise: |C(k)| up to ${noiseError.toFixed(3)}`)
      .write();
  });
});
