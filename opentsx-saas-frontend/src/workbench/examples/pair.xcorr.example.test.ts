/** Operator example pair.xcorr (docs/user-manual/17-operators.md): B is A delayed by 40 s plus noise, and every window finds the lag +40 s. */
import { describe, expect, it } from 'vitest';
import { OP_BY_ID, mulberry32 } from '../ops';
import { Bucket } from '../types';
import { crossCorrelation, peakOf } from '../xcorr';
import { ExampleChart } from './chart';

const T0 = Date.parse('2026-10-01T00:00:00Z');
const DELAY = 4;

function gauss(rnd: () => number): number {
  return Math.sqrt(-2 * Math.log(1 - rnd())) * Math.cos(2 * Math.PI * rnd());
}

describe('pair.xcorr example', () => {
  it('finds the planted delay of 40 s as lag +40 s in each 10 min window', () => {
    const rnd = mulberry32(1);
    const base = new Float64Array(360 + DELAY);
    for (let i = 0; i < base.length; i++) base[i] = (i > 0 ? 0.5 * base[i - 1] : 0) + gauss(rnd);
    const seconds = Float64Array.from({ length: 360 }, (_, i) => i * 10);
    const a = base.slice(DELAY);
    const b = Float64Array.from(seconds, (_, i) => base[i] + 0.5 * gauss(rnd));

    // usage:begin
    const pair: Bucket = {
      name: 'pair', xKind: 'time', xLabel: 't [s]', yLabel: 'y', t0Ms: T0,
      series: [
        { id: 'A', label: 'A', x: seconds, y: a },
        { id: 'B', label: 'B', x: seconds, y: b },
      ],
    };
    const xcorr = OP_BY_ID['pair.xcorr'];
    const out = xcorr.apply(pair, { window: 600, step: 300, lags: 10 });
    const field = (name: string) => out.series.find((s) => s.metric === `pair.xcorr.${name}`)!;
    const [max, lag, strength] = ['max', 'lag', 'strength'].map(field);
    // usage:end

    expect(Array.from(lag.x)).toEqual(Array.from({ length: 11 }, (_, k) => 300 + 300 * k));
    expect(Array.from(lag.y)).toEqual(Array(11).fill(40));
    const peaks = Array.from(max.y);
    const strengths = Array.from(strength.y);
    const meanPeak = peaks.reduce((s, v) => s + v, 0) / peaks.length;
    expect(meanPeak).toBeCloseTo(0.84, 1);
    peaks.forEach((v) => expect(v).toBeGreaterThan(0.6));
    strengths.forEach((v) => expect(v).toBeGreaterThan(2.5));
    strengths.forEach((v) => expect(v).toBeLessThanOrEqual(20 / Math.sqrt(21)));
    const cc = crossCorrelation(a.subarray(0, 60), b.subarray(0, 60), 10);
    expect(peakOf(cc).value).toBeCloseTo(max.y[0], 12);

    const shown = 30;
    const lagSeconds = Array.from(cc, (_, i) => (10 - i) * 10);
    new ExampleChart('pair.xcorr', 'Cross-correlation: B follows A by 40 s, and each 10 min window finds lag +40 s')
      .panel('Input, first 5 min', 't [min]', 'value')
      .add('A', 'line', Array.from(seconds.slice(0, shown), (t) => t / 60), a.slice(0, shown))
      .add('B', 'line', Array.from(seconds.slice(0, shown), (t) => t / 60), b.slice(0, shown))
      .panel('CC of the first window over the lag', 'lag [s], > 0: B follows A', 'CC')
      .add('CC', 'line', lagSeconds.reverse(), Array.from(cc).reverse())
      .note('expected: +40 s in every window')
      .note(`first window: ${max.y[0].toFixed(2)} at +${lag.y[0]} s`)
      .note(`all windows: +40 s in ${lag.y.filter((v) => v === 40).length} of ${lag.y.length}`)
      .note(`max ${Math.min(...peaks).toFixed(2)} to ${Math.max(...peaks).toFixed(2)}`)
      .note(`strength ${Math.min(...strengths).toFixed(1)} to ${Math.max(...strengths).toFixed(1)}`)
      .panel('Lag of the peak in each window', 'window centre [min]', 'lag [s]')
      .add('lag', 'bar', Array.from(lag.x, (t) => t / 60), lag.y)
      .write();
  });
});
