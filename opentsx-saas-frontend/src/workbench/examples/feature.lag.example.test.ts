/** Operator example feature.lag (docs/user-manual/17-operators.md): a lag of 5 samples shifts the series by exactly 5 samples. */
import { describe, expect, it } from 'vitest';
import { OP_BY_ID, mulberry32 } from '../ops';
import { Bucket } from '../types';
import { ExampleChart } from './chart';

const T0 = Date.parse('2026-10-01T00:00:00Z');
const SHIFTS = Array.from({ length: 21 }, (_, i) => i - 10);

function correlation(a: ArrayLike<number>, b: ArrayLike<number>): number {
  let n = 0;
  let sa = 0;
  let sb = 0;
  let saa = 0;
  let sbb = 0;
  let sab = 0;
  for (let i = 0; i < a.length; i++) {
    if (!Number.isFinite(a[i]) || !Number.isFinite(b[i])) continue;
    n++;
    sa += a[i];
    sb += b[i];
    saa += a[i] * a[i];
    sbb += b[i] * b[i];
    sab += a[i] * b[i];
  }
  return (n * sab - sa * sb) / Math.sqrt((n * saa - sa * sa) * (n * sbb - sb * sb));
}

describe('feature.lag example', () => {
  it('gives the value 5 samples earlier: the output correlates with the input shifted by 5, and with no other shift', () => {
    const rnd = mulberry32(1);
    const seconds = Float64Array.from({ length: 300 }, (_, i) => i);
    const noise = Float64Array.from(seconds, () => rnd() - 0.5);

    // usage:begin
    const raw: Bucket = {
      name: 'noise',
      xKind: 'time',
      xLabel: 't [s]',
      yLabel: 'y',
      t0Ms: T0,
      series: [{ id: 'noise', label: 'white noise', x: seconds, y: noise }],
    };
    const lag = OP_BY_ID['feature.lag'];
    const lagged = lag.apply(raw, { steps: 5 }).series[0];
    // usage:end

    expect(Array.from(lagged.x)).toEqual(Array.from(seconds));
    expect(Array.from(lagged.y.slice(0, 5)).every((v) => Number.isNaN(v))).toBe(true);
    expect(Array.from(lagged.y.slice(5))).toEqual(Array.from(noise.slice(0, 295)));
    const r = SHIFTS.map((k) => correlation(lagged.y, Float64Array.from(noise, (_, i) => (i - k < 0 || i - k >= noise.length ? NaN : noise[i - k]))));
    const peak = r[SHIFTS.indexOf(5)];
    const others = r.filter((_, j) => SHIFTS[j] !== 5).map(Math.abs);
    expect(peak).toBeCloseTo(1, 12);
    others.forEach((v) => expect(v).toBeLessThan(0.25));

    const first = 40;
    new ExampleChart('feature.lag', 'Lag of 5 samples: the output is the input shifted by exactly 5 s')
      .panel('Input and output, first 40 s', 't [s]', 'value')
      .add('input', 'line', seconds.slice(0, first), noise.slice(0, first))
      .add('lag 5', 'line', seconds.slice(0, first), lagged.y.slice(0, first))
      .panel('Output against the input shifted by k', 'shift k [samples]', 'correlation')
      .add('correlation', 'point', SHIFTS, r)
      .note('expected: 1 at k = 5, 0 elsewhere')
      .note(`measured: ${peak.toFixed(3)} at k = 5`)
      .note(`at most ${Math.max(...others).toFixed(2)} elsewhere`)
      .write();
  });
});
