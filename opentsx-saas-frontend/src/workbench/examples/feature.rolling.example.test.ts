/** Operator example feature.rolling (docs/user-manual/17-operators.md): rolling MEAN and STD over 20 s on a step from 0 to 10. */
import { describe, expect, it } from 'vitest';
import { OP_BY_ID } from '../ops';
import { Bucket } from '../types';
import { ExampleChart } from './chart';

const T0 = Date.parse('2026-10-01T00:00:00Z');

describe('feature.rolling example', () => {
  it('ramps the MEAN from 0 to 10 within one window and gives the STD a peak of 5 where half the window is 10', () => {
    const seconds = Float64Array.from({ length: 200 }, (_, i) => i);
    const level = Float64Array.from(seconds, (t) => (t < 100 ? 0 : 10));

    // usage:begin
    const raw: Bucket = {
      name: 'level',
      xKind: 'time',
      xLabel: 't [s]',
      yLabel: 'y',
      t0Ms: T0,
      series: [{ id: 'level', label: 'step', x: seconds, y: level }],
    };
    const rolling = OP_BY_ID['feature.rolling'];
    const mean = rolling.apply(raw, { window: 20, fn: 'MEAN' }).series[0];
    const std = rolling.apply(raw, { window: 20, fn: 'STD' }).series[0];
    // usage:end

    seconds.forEach((t, i) => {
      const share = Math.min(1, Math.max(0, (t - 99) / 20));
      expect(mean.y[i]).toBeCloseTo(10 * share, 12);
      expect(std.y[i]).toBeCloseTo(10 * Math.sqrt(share * (1 - share)), 6);
    });
    const full = seconds[mean.y.findIndex((v) => v === 10)] - 100;
    const top = Math.max(...std.y);
    const topAt = seconds[std.y.indexOf(top)] - 100;
    expect(full).toBe(19);
    expect(top).toBeCloseTo(5, 12);
    expect(topAt).toBe(9);

    const shown = (y: ArrayLike<number>) => Array.from(y).slice(40, 161);
    const t = shown(seconds).map((x) => x - 100);
    new ExampleChart('feature.rolling', 'Rolling 20 s window on a step: MEAN ramps to 10, STD peaks at 5')
      .panel('Step input and rolling MEAN', 't after the step [s]', 'value')
      .add('step input', 'line', t, shown(level))
      .add('MEAN', 'line', t, shown(mean.y))
      .note('expected: MEAN 10 at 19 s')
      .note(`measured: MEAN 10 at ${full} s`)
      .panel('Rolling STD', 't after the step [s]', 'value')
      .add('STD', 'line', t, shown(std.y))
      .note('expected: max 5 at 9 s')
      .note(`measured: max ${top.toFixed(3)} at ${topAt} s`)
      .write();
  });
});
