/** Operator example log (docs/user-manual/17-operators.md): growth of 2 % per minute becomes a straight line of slope 0.02, and values of 0 or below are dropped. */
import { describe, expect, it } from 'vitest';
import { OP_BY_ID, linFit, mulberry32 } from '../ops';
import { Bucket } from '../types';
import { ExampleChart } from './chart';

const T0 = Date.parse('2026-10-01T00:00:00Z');
const RATE = 0.02;
const START = 50;

function gauss(rnd: () => number): number {
  return Math.sqrt(-2 * Math.log(1 - rnd())) * Math.cos(2 * Math.PI * rnd());
}

describe('log example', () => {
  it('turns exponential growth into a line with the growth rate as slope and drops the two values without a logarithm', () => {
    const rnd = mulberry32(2);
    const seconds = Float64Array.from({ length: 200 }, (_, i) => i * 60);
    const requests = Float64Array.from(seconds, (_, i) => START * Math.exp(RATE * i + 0.05 * gauss(rnd)));
    requests[60] = 0;
    requests[130] = -1;

    // usage:begin
    const raw: Bucket = {
      name: 'requests',
      xKind: 'time',
      xLabel: 't [s]',
      yLabel: '1/min',
      t0Ms: T0,
      series: [{ id: 'requests', label: 'requests per minute', x: seconds, y: requests }],
    };
    const log = OP_BY_ID['log'];
    const out = log.apply(raw, {}).series[0];
    // usage:end

    const kept = Array.from(seconds).filter((_, i) => i !== 60 && i !== 130);
    expect(Array.from(out.x)).toEqual(kept);
    out.x.forEach((t, j) => expect(out.y[j]).toBe(Math.log(requests[t / 60])));
    const minutes = Array.from(out.x, (t) => t / 60);
    const fit = linFit(minutes, out.y);
    expect(fit.slope).toBeCloseTo(RATE, 3);
    expect(Math.abs(fit.intercept - Math.log(START))).toBeLessThan(0.03);

    new ExampleChart('log', 'log: growth of 2 % per minute becomes a straight line of slope 0.02')
      .panel('Input: requests per minute, one 0 and one -1', 't [min]', 'requests per minute')
      .add('input', 'line', Array.from(seconds, (t) => t / 60), requests)
      .note(`expected log: slope ${RATE}, intercept ${Math.log(START).toFixed(3)}`)
      .note(`measured log: slope ${fit.slope.toFixed(4)}, intercept ${fit.intercept.toFixed(3)}`)
      .note(`points kept: ${out.x.length} of 200, expected 198`)
      .panel('log: natural logarithm', 't [min]', 'ln(requests per minute)')
      .add('log', 'point', minutes, out.y)
      .add('fitted line', 'line', [0, 199], [fit.intercept, fit.intercept + 199 * fit.slope])
      .write();
  });
});
