/** Operator example histogram.crosscut (docs/user-manual/17-operators.md): 20 sensors change their distribution half way, and the two time slices give the histograms before and after the change. */
import { describe, expect, it } from 'vitest';
import { OP_BY_ID, mulberry32 } from '../ops';
import { Bucket } from '../types';
import { ExampleChart } from './chart';

const T0 = Date.parse('2026-10-01T00:00:00Z');
const SENSORS = 20;
const N = 400;
const CHANGE = 200;

function gauss(rnd: () => number): number {
  return Math.sqrt(-2 * Math.log(1 - rnd())) * Math.cos(2 * Math.PI * rnd());
}

function moments(x: ArrayLike<number>, p: ArrayLike<number>): { mean: number; std: number } {
  let mean = 0;
  for (let k = 0; k < x.length; k++) mean += x[k] * p[k];
  let variance = 0;
  for (let k = 0; k < x.length; k++) variance += (x[k] - mean) ** 2 * p[k];
  return { mean, std: Math.sqrt(variance) };
}

describe('histogram.crosscut example', () => {
  it('pools all sensors per slice of 200 s: mean 0 and std 1 before the change, mean 4 and std 0.5 after it', () => {
    const rnd = mulberry32(1);
    const seconds = Float64Array.from({ length: N }, (_, i) => i);
    const sensors = Array.from({ length: SENSORS }, () =>
      Float64Array.from(seconds, (_, i) => (i < CHANGE ? gauss(rnd) : 4 + 0.5 * gauss(rnd))),
    );

    // usage:begin
    const raw: Bucket = {
      name: 'sensors',
      xKind: 'time',
      xLabel: 't [s]',
      yLabel: 'y',
      t0Ms: T0,
      series: sensors.map((y, j) => ({ id: `s${j + 1}`, label: `sensor ${j + 1}`, x: seconds, y })),
    };
    const crosscut = OP_BY_ID['histogram.crosscut'];
    const slices = crosscut.apply(raw, { min: -4, max: 8, bins: 48, window: 200 }).series;
    // usage:end

    expect(slices.map((s) => s.label)).toEqual(['0_200', '200_400']);
    const [before, after] = slices.map((s) => moments(s.x, s.y));
    slices.forEach((s) => expect(Array.from(s.y).reduce((a, b) => a + b, 0)).toBeCloseTo(1, 12));
    expect(Math.abs(before.mean)).toBeLessThan(0.06);
    expect(Math.abs(before.std - 1)).toBeLessThan(0.04);
    expect(Math.abs(after.mean - 4)).toBeLessThan(0.03);
    expect(Math.abs(after.std - 0.5)).toBeLessThan(0.025);

    const first = Array.from(seconds.slice(0, CHANGE));
    const second = Array.from(seconds.slice(CHANGE));
    new ExampleChart('histogram.crosscut', 'histogram.crosscut: one histogram per time slice shows the change')
      .panel(`Input: sensor 1 of ${SENSORS}`, 't [s]', 'value')
      .add('slice 0_200', 'line', first, sensors[0].slice(0, CHANGE))
      .add('slice 200_400', 'line', second, sensors[0].slice(CHANGE))
      .panel(`Histogram per slice, ${SENSORS} sensors pooled`, 'value', 'relative frequency')
      .add('slice 0_200', 'bar', slices[0].x, slices[0].y)
      .add('slice 200_400', 'bar', slices[1].x, slices[1].y)
      .note('expected: mean 0, std 1, then 4, 0.5')
      .note(`measured: ${before.mean.toFixed(2)}, ${before.std.toFixed(2)}, then ${after.mean.toFixed(2)}, ${after.std.toFixed(2)}`)
      .write();
  });
});
