/** Operator example dq.plausible-range (docs/user-manual/17-operators.md): values outside [0, 60] become gaps or the bounds. */
import { describe, expect, it } from 'vitest';
import { OP_BY_ID, mulberry32 } from '../ops';
import { Bucket } from '../types';
import { ExampleChart } from './chart';

const T0 = Date.parse('2026-10-01T00:00:00Z');

describe('dq.plausible-range example', () => {
  it('turns a spike and a dropout outside [0, 60] m/s into gaps, or clips them to the bounds', () => {
    const rnd = mulberry32(7);
    const seconds = Float64Array.from({ length: 300 }, (_, i) => i);
    const wind = Float64Array.from(seconds, (t) => 8 - 1.5 * Math.cos((2 * Math.PI * t) / 600) + rnd() - 0.5);
    wind.fill(-10, 120, 130);
    wind.fill(95, 230, 235);

    // usage:begin
    const raw: Bucket = {
      name: 'wind',
      xKind: 'time',
      xLabel: 't [s]',
      yLabel: 'm/s',
      t0Ms: T0,
      series: [{ id: 'wind', label: 'wind speed', unit: 'm/s', x: seconds, y: wind }],
    };
    const range = OP_BY_ID['dq.plausible-range'];
    const gaps = range.apply(raw, { min: 0, max: 60, mode: 'gap' }).series[0];
    const clipped = range.apply(raw, { min: 0, max: 60, mode: 'clip' }).series[0];
    // usage:end

    const outside = wind.filter((v) => v < 0 || v > 60).length;
    const gapCount = gaps.y.filter((v) => Number.isNaN(v)).length;
    expect(outside).toBe(15);
    expect(gapCount).toBe(15);
    for (let i = 0; i < wind.length; i++) {
      const inside = wind[i] >= 0 && wind[i] <= 60;
      expect(gaps.y[i]).toBe(inside ? wind[i] : NaN);
      expect(clipped.y[i]).toBe(inside ? wind[i] : wind[i] > 60 ? 60 : 0);
    }
    expect(Array.from(gaps.x)).toEqual(Array.from(seconds));

    new ExampleChart('dq.plausible-range', 'Plausible range [0, 60] m/s: a spike and a dropout become gaps or the bounds')
      .panel('Input with a spike and a dropout', 't [s]', 'wind speed [m/s]')
      .add('raw', 'line', seconds, wind)
      .note(`${outside} values outside [0, 60]`)
      .panel('mode gap', 't [s]', 'wind speed [m/s]')
      .add('gap', 'line', gaps.x, gaps.y)
      .note(`expected 15 gaps, measured ${gapCount}`)
      .panel('mode clip', 't [s]', 'wind speed [m/s]')
      .add('clip', 'line', clipped.x, clipped.y)
      .note(`expected 60 and 0, measured ${clipped.y[231]} and ${clipped.y[125]}`)
      .write();
  });
});
