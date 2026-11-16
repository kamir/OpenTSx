/** Operator example smooth.movavg (docs/user-manual/17-operators.md): a moving average over 25 samples turns a step into a ramp of 25 samples and the noise into noise of a fifth. */
import { describe, expect, it } from 'vitest';
import { OP_BY_ID, mulberry32, stats } from '../ops';
import { Bucket } from '../types';
import { ExampleChart } from './chart';

const T0 = Date.parse('2026-10-01T00:00:00Z');
const WINDOW = 25;
const STEP_AT = 200;

function gauss(rnd: () => number): number {
  return Math.sqrt(-2 * Math.log(1 - rnd())) * Math.cos(2 * Math.PI * rnd());
}

describe('smooth.movavg example', () => {
  it('ramps a step from 0 to 10 over 25 samples and divides the noise std by 5', () => {
    const rnd = mulberry32(1);
    const seconds = Float64Array.from({ length: 5000 }, (_, i) => i);
    const ramp = Float64Array.from(seconds, (_, i) => 10 * Math.min(1, Math.max(0, (i - STEP_AT + 1) / WINDOW)));
    const noise = Float64Array.from(seconds, () => gauss(rnd));
    const level = Float64Array.from(seconds, (_, i) => (i < STEP_AT ? 0 : 10) + noise[i]);

    // usage:begin
    const raw: Bucket = {
      name: 'level',
      xKind: 'time',
      xLabel: 't [s]',
      yLabel: 'y',
      t0Ms: T0,
      series: [{ id: 'level', label: 'step with noise', x: seconds, y: level }],
    };
    const movavg = OP_BY_ID['smooth.movavg'];
    const out = movavg.apply(raw, { window: WINDOW }).series[0];
    // usage:end

    const step = Float64Array.from(seconds, (_, i) => (i < STEP_AT ? 0 : 10));
    const clean = movavg.apply({ ...raw, series: [{ ...raw.series[0], y: step }] }, { window: WINDOW }).series[0];
    const rampError = Math.max(...Array.from(clean.y, (v, i) => Math.abs(v - ramp[i])));
    expect(rampError).toBeLessThan(1e-12);
    const before = stats(noise.slice(WINDOW)).std;
    const after = stats(Array.from(out.y, (v, i) => v - ramp[i]).slice(WINDOW)).std;
    expect(Math.abs(before - 1)).toBeLessThan(0.03);
    expect(Math.abs(after / before - 1 / Math.sqrt(WINDOW))).toBeLessThan(0.04);

    const near = Array.from(seconds.slice(STEP_AT - 60, STEP_AT + 40), (t) => t - STEP_AT);
    const part = (y: ArrayLike<number>) => Array.from(y).slice(STEP_AT - 60, STEP_AT + 40);
    const first = 300;
    new ExampleChart('smooth.movavg', 'Moving average of 25 samples: step to ramp, noise to a fifth')
      .panel(`Input and output, first ${first} of 5000 s`, 't [s]', 'value')
      .add('input', 'line', seconds.slice(0, first), level.slice(0, first))
      .add('moving average', 'line', seconds.slice(0, first), out.y.slice(0, first))
      .note(`noise std, input: ${before.toFixed(3)}`)
      .note(`expected output: input / 5 = ${(before / Math.sqrt(WINDOW)).toFixed(3)}`)
      .note(`measured output: ${after.toFixed(3)}`)
      .panel('Around the step', 't after the step [s]', 'value')
      .add('input', 'line', near, part(level))
      .add('moving average', 'line', near, part(out.y))
      .add('ramp', 'line', near, part(ramp))
      .note('expected without noise: the ramp')
      .note(`measured: largest difference ${rampError.toExponential(0)}`)
      .write();
  });
});
