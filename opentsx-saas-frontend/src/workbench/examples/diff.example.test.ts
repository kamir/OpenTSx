/** Operator example diff (docs/user-manual/17-operators.md): the differences of an energy meter reading are the energy of each step. */
import { describe, expect, it } from 'vitest';
import { OP_BY_ID } from '../ops';
import { Bucket } from '../types';
import { ExampleChart } from './chart';

const T0 = Date.parse('2026-10-01T00:00:00Z');
const STEPS = [2, 5, 1];

describe('diff example', () => {
  it('turns a meter reading that grows by 2, 5 and 1 kWh per 10 min into the values 2, 5 and 1', () => {
    const seconds = Float64Array.from({ length: 150 }, (_, i) => i * 600);
    const energy = Float64Array.from(seconds, (_, i) => STEPS[Math.floor(i / 50)]);
    const meter = new Float64Array(150);
    meter[0] = 1000;
    for (let i = 1; i < meter.length; i++) meter[i] = meter[i - 1] + energy[i];

    // usage:begin
    const raw: Bucket = {
      name: 'meter',
      xKind: 'time',
      xLabel: 't [s]',
      yLabel: 'kWh',
      t0Ms: T0,
      series: [{ id: 'meter', label: 'energy meter', unit: 'kWh', x: seconds, y: meter }],
    };
    const diff = OP_BY_ID['diff'];
    const out = diff.apply(raw, {}).series[0];
    // usage:end

    expect(Array.from(out.x)).toEqual(Array.from(seconds));
    expect(out.y[0]).toBe(0);
    expect(Array.from(out.y.slice(1))).toEqual(Array.from(energy.slice(1)));
    const levels = STEPS.map((_, k) => out.y[k * 50 + 25]);
    const exact = out.y.slice(1).filter((v, i) => v === energy[i + 1]).length;

    const hours = Array.from(seconds, (t) => t / 3600);
    new ExampleChart('diff', 'diff: a meter reading that grows by 2, 5 and 1 kWh per step gives 2, 5 and 1')
      .panel('Input: energy meter reading every 10 min', 't [h]', 'meter reading [kWh]')
      .add('meter reading', 'line', hours, meter)
      .note('expected diff: 0 first, then 2, 5 and 1')
      .note(`measured diff: ${out.y[0]} first, then ${levels.join(', ')}`)
      .note(`${exact} of ${out.y.length - 1} differences exact`)
      .panel('diff: energy per 10 min', 't [h]', 'energy [kWh]')
      .add('diff', 'line', hours, out.y)
      .write();
  });
});
