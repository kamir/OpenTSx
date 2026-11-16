/** Operator example cdf (docs/user-manual/17-operators.md): the cumulated distribution of uniform values is a straight line, that of normal values the S curve of the normal distribution. */
import { describe, expect, it } from 'vitest';
import { OP_BY_ID, mulberry32 } from '../ops';
import { Bucket } from '../types';
import { ExampleChart } from './chart';

const T0 = Date.parse('2026-10-01T00:00:00Z');
const N = 20000;
const BINS = 30;

function gauss(rnd: () => number): number {
  return Math.sqrt(-2 * Math.log(1 - rnd())) * Math.cos(2 * Math.PI * rnd());
}

function normalF(x: number): number {
  const t = 1 / (1 + (0.3275911 * Math.abs(x)) / Math.SQRT2);
  const erf = 1 - t * (0.254829592 + t * (-0.284496736 + t * (1.421413741 + t * (-1.453152027 + t * 1.061405429)))) * Math.exp((-x * x) / 2);
  return x < 0 ? (1 - erf) / 2 : (1 + erf) / 2;
}

describe('cdf example', () => {
  it('follows F(y) = y for uniform values on [0, 1] and the normal distribution function for normal values', () => {
    const rnd = mulberry32(1);
    const seconds = Float64Array.from({ length: N }, (_, i) => i);
    const uniform = Float64Array.from(seconds, () => rnd());
    const normal = Float64Array.from(seconds, () => gauss(rnd));

    // usage:begin
    const raw: Bucket = {
      name: 'samples',
      xKind: 'time',
      xLabel: 't [s]',
      yLabel: 'y',
      t0Ms: T0,
      series: [
        { id: 'uniform', label: 'uniform', x: seconds, y: uniform },
        { id: 'normal', label: 'normal', x: seconds, y: normal },
      ],
    };
    const cdf = OP_BY_ID['cdf'];
    const [uniformCdf, normalCdf] = cdf.apply(raw, { bins: 30 }).series;
    // usage:end

    const upper = (x: ArrayLike<number>) => Array.from(x, (v) => v + (x[1] - x[0]) / 2);
    const expectUniform = upper(uniformCdf.x).map((u) => Math.min(1, Math.max(0, u)));
    const expectNormal = upper(normalCdf.x).map(normalF);
    expect(uniformCdf.y).toHaveLength(BINS);
    expect(uniformCdf.y[BINS - 1]).toBeCloseTo(1, 12);
    expect(normalCdf.y[BINS - 1]).toBeCloseTo(1, 12);
    const worst = (y: ArrayLike<number>, e: number[]) => Math.max(...e.map((v, k) => Math.abs(y[k] - v)));
    const uniformError = worst(uniformCdf.y, expectUniform);
    const normalError = worst(normalCdf.y, expectNormal);
    expect(uniformError).toBeLessThan(0.012);
    expect(normalError).toBeLessThan(0.012);

    new ExampleChart('cdf', 'cdf: a straight line for uniform values, the normal S curve for normal values')
      .panel('Uniform on [0, 1], 20000 values', 'y', 'cumulated frequency')
      .add('cdf', 'point', uniformCdf.x, uniformCdf.y)
      .add('F at upper bin edge', 'line', uniformCdf.x, expectUniform)
      .note('uniform: F(y) = y')
      .note(`max difference: ${uniformError.toFixed(4)}`)
      .panel('Normal, mean 0, std 1, 20000 values', 'y', 'cumulated frequency')
      .add('cdf', 'point', normalCdf.x, normalCdf.y)
      .add('F at upper bin edge', 'line', normalCdf.x, expectNormal)
      .note('normal: F(y) = Phi(y)')
      .note(`max difference: ${normalError.toFixed(4)}`)
      .write();
  });
});
