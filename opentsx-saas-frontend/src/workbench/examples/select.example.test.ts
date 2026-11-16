/** Operator example select (docs/user-manual/17-operators.md): the pattern wind speed keeps the 2 wind speed series of 4 and drops the power series. */
import { describe, expect, it } from 'vitest';
import { OP_BY_ID, mulberry32 } from '../ops';
import { Bucket } from '../types';
import { ExampleChart } from './chart';

const T0 = Date.parse('2026-10-01T00:00:00Z');

describe('select example', () => {
  it('keeps the series whose label matches the pattern, ignoring case, and leaves their values alone', () => {
    const rnd = mulberry32(8);
    const seconds = Float64Array.from({ length: 144 }, (_, i) => i * 600);
    const wind = (base: number) => Float64Array.from(seconds, (t) => base + 3 * (t / 86400) ** 2 + rnd() - 0.5);
    const power = (w: Float64Array) => Float64Array.from(w, (v) => Math.min(3, 0.003 * v ** 3));
    const w1 = wind(5);
    const w2 = wind(7);

    // usage:begin
    const raw: Bucket = {
      name: 'turbines',
      xKind: 'time',
      xLabel: 't [s]',
      yLabel: 'y',
      t0Ms: T0,
      series: [
        { id: 'wt01.wind', label: 'WT01 Wind speed', unit: 'm/s', x: seconds, y: w1 },
        { id: 'wt01.power', label: 'WT01 Power', unit: 'MW', x: seconds, y: power(w1) },
        { id: 'wt02.wind', label: 'WT02 Wind speed', unit: 'm/s', x: seconds, y: w2 },
        { id: 'wt02.power', label: 'WT02 Power', unit: 'MW', x: seconds, y: power(w2) },
      ],
    };
    const select = OP_BY_ID['select'];
    const kept = select.apply(raw, { pattern: 'wind speed' }).series;
    // usage:end

    expect(kept.map((s) => s.label)).toEqual(['WT01 Wind speed', 'WT02 Wind speed']);
    expect(kept[0].y).toBe(w1);
    expect(kept[1].y).toBe(w2);
    expect(select.apply(raw, { pattern: '^wt02' }).series.map((s) => s.id)).toEqual(['wt02.wind', 'wt02.power']);

    const hours = Array.from(seconds, (t) => t / 3600);
    const name = (s: { label: string }) => s.label.replace(' speed', '');
    const chart = new ExampleChart('select', 'select "wind speed": 2 of 4 series kept');
    chart.panel('Input: 4 series of 2 turbines', 't [h]', 'm/s and MW');
    raw.series.forEach((s) => chart.add(name(s), 'line', hours, s.y));
    chart.panel('select, pattern "wind speed"', 't [h]', 'm/s');
    kept.forEach((s) => chart.add(name(s), 'line', hours, s.y));
    chart
      .note('expected: the 2 wind speed series')
      .note(`measured: ${kept.map((s) => s.label).join(', ')}`)
      .write();
  });
});
