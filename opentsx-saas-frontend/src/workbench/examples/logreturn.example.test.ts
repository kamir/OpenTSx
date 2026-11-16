/** Operator example logreturn (docs/user-manual/17-operators.md): the log returns of a price built from planted returns are those returns. */
import { describe, expect, it } from 'vitest';
import { OP_BY_ID, mulberry32 } from '../ops';
import { Bucket } from '../types';
import { ExampleChart } from './chart';

const T0 = Date.parse('2026-10-01T00:00:00Z');

function gauss(rnd: () => number): number {
  return Math.sqrt(-2 * Math.log(1 - rnd())) * Math.cos(2 * Math.PI * rnd());
}

describe('logreturn example', () => {
  it('gives back the planted return of every step and 0 at the first sample', () => {
    const rnd = mulberry32(4);
    const seconds = Float64Array.from({ length: 300 }, (_, i) => i * 3600);
    const planted = Float64Array.from(seconds, (_, i) => (i === 0 ? 0 : 0.0005 + 0.01 * gauss(rnd)));
    const price = new Float64Array(300);
    price[0] = 80;
    for (let i = 1; i < price.length; i++) price[i] = price[i - 1] * Math.exp(planted[i]);

    // usage:begin
    const raw: Bucket = {
      name: 'price',
      xKind: 'time',
      xLabel: 't [s]',
      yLabel: 'EUR/MWh',
      t0Ms: T0,
      series: [{ id: 'price', label: 'price', unit: 'EUR/MWh', x: seconds, y: price }],
    };
    const logreturn = OP_BY_ID['logreturn'];
    const out = logreturn.apply(raw, {}).series[0];
    // usage:end

    expect(Array.from(out.x)).toEqual(Array.from(seconds));
    expect(out.y[0]).toBe(0);
    const error = Math.max(...Array.from(out.y, (v, i) => Math.abs(v - planted[i])));
    expect(error).toBeLessThan(1e-12);

    const hours = Array.from(seconds, (t) => t / 3600);
    new ExampleChart('logreturn', 'logreturn: ln(y[i]) − ln(y[i−1]) gives back the planted return of every hour')
      .panel('Input: price built from the planted returns', 't [h]', 'price [EUR/MWh]')
      .add('price', 'line', hours, price)
      .panel('Output against the planted return', 'planted return', 'logreturn')
      .add('logreturn', 'point', Array.from(planted.slice(1)), Array.from(out.y.slice(1)))
      .note('expected: output = planted return')
      .note(`measured: largest difference ${error.toExponential(1)}`)
      .write();
  });
});
