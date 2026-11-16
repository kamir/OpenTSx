/** Operator example model.wind-shear (docs/user-manual/17-operators.md): the power law carries the wind of 10 m to the hub height. */
import { describe, expect, it } from 'vitest';
import { OP_BY_ID, linFit, mulberry32 } from '../ops';
import { Bucket } from '../types';
import { ExampleChart } from './chart';

const T0 = Date.parse('2026-10-01T00:00:00Z');
const HEIGHTS = [10, 20, 40, 60, 80, 100, 120, 150];

describe('model.wind-shear example', () => {
  it('multiplies every value by (toM / fromM)^alpha, a straight line of slope alpha in log-log', () => {
    const rnd = mulberry32(11);
    const seconds = Float64Array.from({ length: 144 }, (_, i) => i * 600);
    const v10 = Float64Array.from(seconds, (t) => 6 - 2 * Math.cos((2 * Math.PI * t) / 86400) + rnd() - 0.5);

    // usage:begin
    const mast: Bucket = {
      name: 'wind_10m',
      xKind: 'time',
      xLabel: 't [s]',
      yLabel: 'm/s',
      t0Ms: T0,
      series: [{ id: 'wind', label: 'wind speed at 10 m', unit: 'm/s', x: seconds, y: v10 }],
    };
    const shear = OP_BY_ID['model.wind-shear'];
    const hub = shear.apply(mast, { fromM: 10, toM: 120, alpha: 0.14 }).series[0];
    // usage:end

    const factor = Math.pow(120 / 10, 0.14);
    v10.forEach((v, i) => expect(hub.y[i] / v).toBeCloseTo(factor, 12));
    const ratio = HEIGHTS.map((h) => shear.apply(mast, { fromM: 10, toM: h, alpha: 0.14 }).series[0].y[0] / v10[0]);
    const logHeight = HEIGHTS.map((h) => Math.log(h / 10));
    const logRatio = ratio.map(Math.log);
    const fit = linFit(logHeight, logRatio);
    expect(fit.slope).toBeCloseTo(0.14, 12);
    expect(fit.intercept).toBeCloseTo(0, 12);

    const hours = Array.from(seconds, (t) => t / 3600);
    new ExampleChart('model.wind-shear', 'Wind shear: the 10 m wind times (120 / 10)^0.14 gives the 120 m wind')
      .panel('Input at 10 m, output at 120 m', 't [h]', 'wind speed [m/s]')
      .add('10 m', 'line', hours, v10)
      .add('120 m', 'line', hours, hub.y)
      .panel('Output over input for heights 10 m to 150 m', 'ln(toM / fromM)', 'ln(output / input)')
      .add('ratio', 'point', logHeight, logRatio)
      .note(`expected: factor ${factor.toFixed(4)} at 120 m, slope 0.14`)
      .note(`measured: factor ${(hub.y[0] / v10[0]).toFixed(4)}, slope ${fit.slope.toFixed(4)}`)
      .write();
  });
});
