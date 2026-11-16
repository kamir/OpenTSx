/** Operator example histogram (docs/user-manual/17-operators.md): uniform values fill every bin of their range with the same share, normal values follow the bell curve. */
import { describe, expect, it } from 'vitest';
import { OP_BY_ID, mulberry32 } from '../ops';
import { Bucket } from '../types';
import { ExampleChart } from './chart';

const T0 = Date.parse('2026-10-01T00:00:00Z');
const N = 20000;
const BINS = 40;
const WIDTH = 0.2;

function gauss(rnd: () => number): number {
  return Math.sqrt(-2 * Math.log(1 - rnd())) * Math.cos(2 * Math.PI * rnd());
}

function normalCdf(x: number): number {
  const t = 1 / (1 + (0.3275911 * Math.abs(x)) / Math.SQRT2);
  const erf = 1 - t * (0.254829592 + t * (-0.284496736 + t * (1.421413741 + t * (-1.453152027 + t * 1.061405429)))) * Math.exp((-x * x) / 2);
  return x < 0 ? (1 - erf) / 2 : (1 + erf) / 2;
}

describe('histogram example', () => {
  it('gives 0.05 per bin to uniform values on [0, 4] and the bin probabilities of the normal distribution to normal values', () => {
    const rnd = mulberry32(1);
    const seconds = Float64Array.from({ length: N }, (_, i) => i);
    const uniform = Float64Array.from(seconds, () => 4 * rnd());
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
    const histogram = OP_BY_ID['histogram'];
    const [flat, bell] = histogram.apply(raw, { min: -4, max: 4, bins: 40 }).series;
    // usage:end

    const centres = Array.from({ length: BINS }, (_, k) => -4 + (k + 0.5) * WIDTH);
    centres.forEach((c, k) => expect(flat.x[k]).toBeCloseTo(c, 12));
    const expectFlat = centres.map((c) => (c > 0 && c < 4 ? 0.05 : 0));
    const expectBell = centres.map((c) => normalCdf(c + WIDTH / 2) - normalCdf(c - WIDTH / 2));
    const sum = (y: ArrayLike<number>) => Array.from(y).reduce((a, b) => a + b, 0);
    expect(sum(flat.y)).toBeCloseTo(1, 12);
    expect(sum(bell.y)).toBeCloseTo(1, 12);
    const worst = (y: ArrayLike<number>, e: number[]) => Math.max(...e.map((v, k) => Math.abs(y[k] - v)));
    const flatError = worst(flat.y, expectFlat);
    const bellError = worst(bell.y, expectBell);
    expect(flatError).toBeLessThan(0.007);
    expect(bellError).toBeLessThan(0.007);
    const inside = Array.from(flat.y).filter((_, k) => expectFlat[k] > 0);
    expect(inside).toHaveLength(20);

    const edges = Array.from({ length: BINS + 1 }, (_, k) => -4 + k * WIDTH);
    const steps = (e: number[]) => [...e, e[BINS - 1]];
    new ExampleChart('histogram', 'histogram: uniform values give a flat histogram, normal values a bell')
      .panel('Uniform on [0, 4], 20000 values', 'y', 'relative frequency')
      .add('histogram', 'bar', flat.x, flat.y)
      .add('expected', 'step', edges, steps(expectFlat))
      .note('expected: 0.050 per bin')
      .note(`measured: ${Math.min(...inside).toFixed(3)} to ${Math.max(...inside).toFixed(3)}`)
      .panel('Normal, mean 0, std 1, 20000 values', 'y', 'relative frequency')
      .add('histogram', 'bar', bell.x, bell.y)
      .add('expected', 'step', edges, steps(expectBell))
      .note(`expected peak: ${Math.max(...expectBell).toFixed(4)}`)
      .note(`measured peak: ${Math.max(...bell.y).toFixed(4)}`)
      .note(`max difference: ${bellError.toFixed(4)}`)
      .write();
  });
});
