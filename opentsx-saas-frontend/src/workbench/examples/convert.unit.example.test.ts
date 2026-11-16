/** Operator example convert.unit (docs/user-manual/17-operators.md): degrees Celsius become degrees Fahrenheit with factor 1.8 and offset 32. */
import { describe, expect, it } from 'vitest';
import { OP_BY_ID, linFit, mulberry32 } from '../ops';
import { Bucket } from '../types';
import { ExampleChart } from './chart';

const T0 = Date.parse('2026-10-01T00:00:00Z');

describe('convert.unit example', () => {
  it('converts degrees Celsius to degrees Fahrenheit: y * 1.8 + 32', () => {
    const rnd = mulberry32(3);
    const seconds = Float64Array.from({ length: 145 }, (_, i) => i * 600);
    const celsius = Float64Array.from(seconds, (t) => 12 - 8 * Math.cos((2 * Math.PI * (t / 3600 - 3)) / 24) + rnd() - 0.5);

    // usage:begin
    const raw: Bucket = {
      name: 'air',
      xKind: 'time',
      xLabel: 't [s]',
      yLabel: 'Cel',
      t0Ms: T0,
      series: [{ id: 'air', label: 'air temperature', unit: 'Cel', x: seconds, y: celsius }],
    };
    const convert = OP_BY_ID['convert.unit'];
    const out = convert.apply(raw, { factor: 1.8, offset: 32, unit: '[degF]' }).series[0];
    // usage:end

    expect(out.unit).toBe('[degF]');
    expect(Array.from(out.x)).toEqual(Array.from(seconds));
    celsius.forEach((c, i) => expect(out.y[i]).toBeCloseTo(c * 1.8 + 32, 12));
    const fit = linFit(celsius, out.y);
    expect(fit.slope).toBeCloseTo(1.8, 12);
    expect(fit.intercept).toBeCloseTo(32, 10);

    new ExampleChart('convert.unit', 'Unit conversion: degrees Celsius times 1.8 plus 32 give degrees Fahrenheit')
      .panel('Input over one day', 't [h]', 'air temperature, Cel')
      .add('input', 'line', seconds.map((t) => t / 3600), celsius)
      .panel('Output over input', 'input, Cel', 'output, [degF]')
      .add('output', 'point', celsius, out.y)
      .note('expected: slope 1.8, intercept 32')
      .note(`measured: slope ${fit.slope.toFixed(4)}, intercept ${fit.intercept.toFixed(4)}`)
      .write();
  });
});
