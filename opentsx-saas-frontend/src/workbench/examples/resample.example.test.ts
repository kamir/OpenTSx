/** Operator example resample (docs/user-manual/17-operators.md): 1 s data with a known level per minute becomes one MEAN and one MAX per minute. */
import { describe, expect, it } from 'vitest';
import { OP_BY_ID, mulberry32 } from '../ops';
import { Bucket } from '../types';
import { ExampleChart } from './chart';

const T0 = Date.parse('2026-10-01T00:00:00Z');
const LEVELS = [6, 8, 7, 10, 9, 11];

describe('resample example', () => {
  it('gives the level of each minute as MEAN and the one gust of each minute as MAX', () => {
    const rnd = mulberry32(1);
    const seconds = Float64Array.from({ length: 360 }, (_, i) => i);
    const wind = Float64Array.from(seconds, (t) => LEVELS[Math.floor(t / 60)] + rnd() - 0.5);
    LEVELS.forEach((level, m) => (wind[m * 60 + Math.floor(rnd() * 60)] = level + 2));

    // usage:begin
    const raw: Bucket = {
      name: 'wind',
      xKind: 'time',
      xLabel: 't [s]',
      yLabel: 'm/s',
      t0Ms: T0,
      series: [{ id: 'wind', label: 'wind speed', unit: 'm/s', x: seconds, y: wind }],
    };
    const resample = OP_BY_ID['resample'];
    const mean = resample.apply(raw, { every: 60, agg: 'MEAN' }).series[0];
    const max = resample.apply(raw, { every: 60, agg: 'MAX' }).series[0];
    // usage:end

    expect(Array.from(mean.x)).toEqual([0, 60, 120, 180, 240, 300]);
    expect(Array.from(max.x)).toEqual([0, 60, 120, 180, 240, 300]);
    expect(Array.from(max.y)).toEqual(LEVELS.map((l) => l + 2));
    const meanError = LEVELS.map((l, m) => mean.y[m] - l);
    meanError.forEach((e) => expect(Math.abs(e)).toBeLessThan(0.2));

    const steps = [0, 1, 2, 3, 4, 5, 6];
    new ExampleChart('resample', 'Resample 1 s to 1 min: MEAN gives the level, MAX the gust')
      .panel('1 s input and 1 min output', 't [min]', 'wind speed [m/s]')
      .add('1 s input', 'line', seconds.map((t) => t / 60), wind)
      .add('MEAN', 'step', steps, [...mean.y, mean.y[5]])
      .add('MAX', 'step', steps, [...max.y, max.y[5]])
      .panel('Output over the planted level', 'planted level [m/s]', 'output [m/s]')
      .add('MEAN', 'point', LEVELS, mean.y)
      .add('MAX', 'point', LEVELS, max.y)
      .note('expected: MAX = level + 2, MEAN = level + 0.03')
      .note(`measured: MAX = level + ${max.y[0] - LEVELS[0]}, MEAN = level + ${(meanError.reduce((a, b) => a + b) / 6).toFixed(2)}`)
      .write();
  });
});
