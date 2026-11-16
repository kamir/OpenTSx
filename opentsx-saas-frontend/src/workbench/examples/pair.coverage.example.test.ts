/** Operator example pair.coverage (docs/user-manual/17-operators.md): a 5 min outage of B halves the coverage of the 10 min windows that hold it. */
import { describe, expect, it } from 'vitest';
import { OP_BY_ID, mulberry32 } from '../ops';
import { Bucket } from '../types';
import { ExampleChart } from './chart';

const T0 = Date.parse('2026-10-01T00:00:00Z');

describe('pair.coverage example', () => {
  it('gives 0.5 in each 10 min window that holds the whole 5 min outage, and 150 of 180 ticks over the whole pair', () => {
    const rnd = mulberry32(2);
    const seconds = Float64Array.from({ length: 180 }, (_, i) => i * 10);
    const level = (t: number) => 8 + 2 * Math.sin((2 * Math.PI * t) / 1800);
    const a = Float64Array.from(seconds, (t) => level(t) + rnd() - 0.5);
    const b = Float64Array.from(seconds, (t) => (t >= 420 && t < 720 ? NaN : level(t) + 1 + rnd() - 0.5));

    // usage:begin
    const pair: Bucket = {
      name: 'wind', xKind: 'time', xLabel: 't [s]', yLabel: 'm/s', t0Ms: T0,
      series: [
        { id: 'A', label: 'mast', unit: 'm/s', x: seconds, y: a },
        { id: 'B', label: 'nacelle', unit: 'm/s', x: seconds, y: b },
      ],
    };
    const coverage = OP_BY_ID['pair.coverage'];
    const sliding = coverage.apply(pair, { window: 600, step: 60, minCoverage: 0 });
    const whole = coverage.apply(pair, { window: 'episode', minCoverage: 0 });
    const perWindow = sliding.series.find((s) => s.metric === 'pair.coverage.coverage')!;
    const overall = whole.series.find((s) => s.metric === 'pair.coverage.coverage')!.y[0];
    // usage:end

    const starts = Array.from({ length: 21 }, (_, k) => 60 * k);
    const expected = starts.map((s) => 1 - Math.max(0, Math.min(s + 600, 720) - Math.max(s, 420)) / 600);
    expect(Array.from(perWindow.x)).toEqual(starts.map((s) => s + 300));
    expected.forEach((v, k) => expect(perWindow.y[k]).toBeCloseTo(v, 12));
    expect(Array.from(sliding.series.find((s) => s.metric === 'pair.coverage.ticks')!.y).every((v) => v === 60)).toBe(true);
    expect(overall).toBeCloseTo(150 / 180, 12);
    const lowest = Math.min(...perWindow.y);
    const halved = Array.from(perWindow.y).filter((v) => Math.abs(v - 0.5) < 1e-12).length;
    expect(lowest).toBeCloseTo(0.5, 12);
    expect(halved).toBe(6);

    const minutes = (x: ArrayLike<number>) => Array.from(x, (t) => t / 60);
    new ExampleChart('pair.coverage', 'Pair coverage: a 5 min outage of B leaves 10 min windows half covered')
      .panel('Input, B is out from 7 to 12 min', 't [min]', 'wind speed [m/s]')
      .add('A', 'line', minutes(seconds), a)
      .add('B', 'line', minutes(seconds), b)
      .panel('Coverage per 10 min window, step 1 min', 'window centre [min]', 'share of ticks with A and B')
      .add('coverage', 'line', minutes(perWindow.x), perWindow.y)
      .note('expected: 0.5 in 6 windows')
      .note(`measured: ${lowest.toFixed(3)} in ${halved} windows`)
      .note('whole pair: expected 0.833')
      .note(`whole pair: measured ${overall.toFixed(3)}`)
      .write();
  });
});
