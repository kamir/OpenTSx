/** Operator example align.vector (docs/user-manual/17-operators.md): two series with different sampling and a gap on one 60 s clock. */
import { describe, expect, it } from 'vitest';
import { OP_BY_ID, mulberry32 } from '../ops';
import { Bucket } from '../types';
import { ExampleChart } from './chart';

const T0 = Date.parse('2026-10-01T00:00:00Z');

describe('align.vector example', () => {
  it('puts a 10 s and a 60 s series on one clock and fills the gap of the slower one as-of for 120 s', () => {
    const rnd = mulberry32(5);
    const level = (t: number) => 7 - 1.5 * Math.cos((2 * Math.PI * t) / 2400);
    const aX = Float64Array.from({ length: 120 }, (_, i) => i * 10);
    const aY = Float64Array.from(aX, (t) => level(t) + rnd() - 0.5);
    const bX = Float64Array.from({ length: 20 }, (_, k) => 30 + 60 * k).filter((t) => t < 420 || t > 780);
    const bY = Float64Array.from(bX, (t) => level(t) + 1);

    // usage:begin
    const a: Bucket = {
      name: 'A', xKind: 'time', xLabel: 't [s]', yLabel: 'm/s', t0Ms: T0,
      series: [{ id: 'wind', label: 'mast every 10 s', unit: 'm/s', x: aX, y: aY }],
    };
    const b: Bucket = {
      name: 'B', xKind: 'time', xLabel: 't [s]', yLabel: 'm/s', t0Ms: T0,
      series: [{ id: 'wind', label: 'nacelle every 60 s', unit: 'm/s', x: bX, y: bY }],
    };
    const align = OP_BY_ID['align.vector'];
    const vector = align.apply(a, { every: 60, key: 'series', agg: 'MEAN', fill: 'as-of', maxAge: 120, emit: 'any' }, [b]);
    const [compA, compB] = vector.series;
    // usage:end

    expect(vector.vector?.components).toEqual(['a', 'b']);
    expect(Array.from(compA.x)).toEqual(Array.from({ length: 20 }, (_, k) => 60 * k));
    for (let k = 0; k < 20; k++) {
      const inTick = aY.slice(6 * k, 6 * k + 6);
      expect(compA.y[k]).toBeCloseTo(inTick.reduce((s, v) => s + v) / 6, 12);
    }
    const measured = Array.from({ length: 20 }, (_, k) => bX.indexOf(30 + 60 * k) >= 0);
    const filled = compB.y.map((v, k) => (!measured[k] && Number.isFinite(v) ? 1 : 0));
    const missing = compB.y.map((v) => (Number.isNaN(v) ? 1 : 0));
    expect(filled.reduce((s, v) => s + v)).toBe(2);
    expect(missing.reduce((s, v) => s + v)).toBe(4);
    expect(compB.y[7]).toBe(bY[6]);
    expect(compB.y[8]).toBe(bY[6]);
    expect(Array.from(compB.y.slice(9, 13)).every((v) => Number.isNaN(v))).toBe(true);
    expect(compB.y[13]).toBe(bY[7]);

    const minutes = (x: ArrayLike<number>) => Array.from(x, (t) => t / 60);
    const ticks = minutes(compB.x);
    new ExampleChart('align.vector', 'Align to vector: 10 s and 60 s data on one 60 s clock, as-of fill for 120 s')
      .panel('Inputs, B misses 6 samples', 't [min]', 'wind speed [m/s]')
      .add('A', 'line', minutes(aX), aY)
      .add('B', 'point', minutes(bX), bY)
      .panel('Vector, one value per tick and component', 'tick start [min]', 'wind speed [m/s]')
      .add('A', 'line', ticks, compA.y)
      .add('B', 'point', ticks.filter((_, k) => measured[k]), compB.y.filter((_, k) => measured[k]))
      .add('B filled as-of', 'point', ticks.filter((_, k) => filled[k] === 1), compB.y.filter((_, k) => filled[k] === 1))
      .note('expected: 2 ticks filled, 4 missing')
      .note(`measured: ${filled.reduce((s, v) => s + v)} filled, ${missing.reduce((s, v) => s + v)} missing`)
      .write();
  });
});
