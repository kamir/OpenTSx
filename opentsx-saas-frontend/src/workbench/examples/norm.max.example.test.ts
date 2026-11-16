/** Operator example norm.max (docs/user-manual/17-operators.md): three turbines of 2, 3 and 6 MW all peak at 1 after the division by their maximum. */
import { describe, expect, it } from 'vitest';
import { OP_BY_ID, stats } from '../ops';
import { Bucket } from '../types';
import { ExampleChart } from './chart';

const T0 = Date.parse('2026-10-01T00:00:00Z');
const TURBINES = [
  { id: 'WT01', rated: 2000, peakH: 8, widthH: 3 },
  { id: 'WT02', rated: 3000, peakH: 13, widthH: 3 },
  { id: 'WT03', rated: 6000, peakH: 20, widthH: 2.5 },
];

describe('norm.max example', () => {
  it('divides each series by its own maximum, so every series peaks at exactly 1', () => {
    const seconds = Float64Array.from({ length: 288 }, (_, i) => i * 300);
    const power = TURBINES.map((w) => Float64Array.from(seconds, (t) => w.rated * (0.1 + 0.9 * Math.exp(-(((t / 3600 - w.peakH) / w.widthH) ** 2)))));

    // usage:begin
    const raw: Bucket = {
      name: 'power',
      xKind: 'time',
      xLabel: 't [s]',
      yLabel: 'kW',
      t0Ms: T0,
      series: TURBINES.map((w, k) => ({ id: w.id, label: `${w.id} power`, unit: 'kW', x: seconds, y: power[k] })),
    };
    const normMax = OP_BY_ID['norm.max'];
    const out = normMax.apply(raw, {}).series;
    // usage:end

    const inputMax = power.map((y) => stats(y).max);
    const outputMax = out.map((s) => stats(s.y).max);
    expect(inputMax).toEqual(TURBINES.map((w) => w.rated));
    expect(outputMax).toEqual([1, 1, 1]);
    out.forEach((s, k) => {
      expect(Array.from(s.x)).toEqual(Array.from(seconds));
      s.y.forEach((v, i) => expect(v).toBeCloseTo(power[k][i] / TURBINES[k].rated, 15));
    });

    const hours = Array.from(seconds, (t) => t / 3600);
    const chart = new ExampleChart('norm.max', 'norm.max: turbines of 2, 3 and 6 MW all peak at exactly 1')
      .panel('Input: power of three turbines', 't [h]', 'power [kW]');
    TURBINES.forEach((w, k) => chart.add(w.id, 'line', hours, power[k]));
    chart
      .note(`input maxima: ${inputMax.join(', ')} kW`)
      .note('expected output maxima: 1, 1, 1')
      .note(`measured output maxima: ${outputMax.join(', ')}`)
      .panel('norm.max: each series divided by its maximum', 't [h]', 'power / maximum');
    TURBINES.forEach((w, k) => chart.add(w.id, 'line', hours, out[k].y));
    chart.write();
  });
});
