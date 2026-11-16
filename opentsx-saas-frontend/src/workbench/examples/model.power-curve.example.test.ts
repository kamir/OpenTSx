/** Operator example model.power-curve (docs/user-manual/17-operators.md): a wind ramp from 0 to 30 m/s runs through the whole power curve. */
import { describe, expect, it } from 'vitest';
import { OP_BY_ID } from '../ops';
import { Bucket } from '../types';
import { ExampleChart } from './chart';

const T0 = Date.parse('2026-10-01T00:00:00Z');

describe('model.power-curve example', () => {
  it('gives zero below cut-in and above cut-out, rated power from the rated wind, and the cubic in between', () => {
    const seconds = Float64Array.from({ length: 361 }, (_, i) => i * 60);
    const wind = Float64Array.from(seconds, (t) => t / 720);

    // usage:begin
    const met: Bucket = {
      name: 'wind',
      xKind: 'time',
      xLabel: 't [s]',
      yLabel: 'm/s',
      t0Ms: T0,
      series: [{ id: 'wind', label: 'wind speed at hub height', unit: 'm/s', x: seconds, y: wind }],
    };
    const curve = OP_BY_ID['model.power-curve'];
    const power = curve.apply(met, { ratedKw: 3000, cutIn: 3, ratedWind: 12.5, cutOut: 25, count: 1 }).series[0];
    // usage:end

    expect(power.unit).toBe('kW');
    const at = (v: number) => power.y[wind.indexOf(v)];
    expect(at(8)).toBeCloseTo(755.4026867, 6);
    wind.forEach((v, i) => {
      if (v <= 3 || v > 25) expect(power.y[i]).toBe(0);
      else if (v >= 12.5) expect(power.y[i]).toBe(3000);
      else expect(power.y[i]).toBeGreaterThan(power.y[i - 1]);
    });

    const minutes = Array.from(seconds, (t) => t / 60);
    new ExampleChart('model.power-curve', 'Power curve: 0 below 3 m/s, 3000 kW from 12.5 m/s, 0 above 25 m/s')
      .panel('Input: wind ramp', 't [min]', 'wind speed [m/s]')
      .add('wind', 'line', minutes, wind)
      .note('expected: 0 kW below 3 and above 25 m/s')
      .note('3000 kW from 12.5 m/s, 755.4 kW at 8 m/s')
      .note(`measured: ${at(2.5)}, ${at(8).toFixed(1)}, ${at(20)}, ${at(25.5)} kW`)
      .note('at 2.5, 8, 20 and 25.5 m/s')
      .panel('Output: power the wind offers', 't [min]', 'power [kW]')
      .add('power', 'line', minutes, power.y)
      .write();
  });
});
