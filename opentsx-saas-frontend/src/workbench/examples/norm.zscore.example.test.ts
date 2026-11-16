/** Operator example norm.zscore (docs/user-manual/17-operators.md): three series of different mean and spread all get mean 0 and standard deviation 1. */
import { describe, expect, it } from 'vitest';
import { OP_BY_ID, mulberry32, stats } from '../ops';
import { Bucket } from '../types';
import { ExampleChart } from './chart';

const T0 = Date.parse('2026-10-01T00:00:00Z');

describe('norm.zscore example', () => {
  it('subtracts the mean and divides by the standard deviation of each series', () => {
    const rnd = mulberry32(5);
    const seconds = Float64Array.from({ length: 240 }, (_, i) => i * 60);
    const u = Array.from(seconds, (_, i) => i / 239);
    const inputs = [
      { id: 'A', label: 'ramp', y: Float64Array.from(u, (v) => 10 + 20 * v + rnd() - 0.5) },
      { id: 'B', label: 'growth', y: Float64Array.from(u, (v) => 40 + Math.exp(3 * v) + rnd() - 0.5) },
      { id: 'C', label: 'step', y: Float64Array.from(u, (v) => (v < 0.6 ? 30 : 45) + rnd() - 0.5) },
    ];

    // usage:begin
    const raw: Bucket = {
      name: 'sensors',
      xKind: 'time',
      xLabel: 't [s]',
      yLabel: 'y',
      t0Ms: T0,
      series: inputs.map((s) => ({ id: s.id, label: s.label, x: seconds, y: s.y })),
    };
    const zscore = OP_BY_ID['norm.zscore'];
    const out = zscore.apply(raw, {}).series;
    // usage:end

    const before = inputs.map((s) => stats(s.y));
    const after = out.map((s) => stats(s.y));
    after.forEach((st) => {
      expect(st.mean).toBeCloseTo(0, 14);
      expect(st.std).toBeCloseTo(1, 14);
    });
    out.forEach((s, k) => {
      expect(Array.from(s.x)).toEqual(Array.from(seconds));
      s.y.forEach((v, i) => expect(v).toBeCloseTo((inputs[k].y[i] - before[k].mean) / before[k].std, 12));
    });
    const worstMean = Math.max(...after.map((st) => Math.abs(st.mean)));
    const worstStd = Math.max(...after.map((st) => Math.abs(st.std - 1)));

    const minutes = Array.from(seconds, (t) => t / 60);
    const chart = new ExampleChart('norm.zscore', 'norm.zscore: every series gets mean 0 and standard deviation 1')
      .panel('Input: three series', 't [min]', 'value');
    inputs.forEach((s) => chart.add(s.label, 'line', minutes, s.y));
    chart
      .note(`input mean: ${before.map((st) => st.mean.toFixed(1)).join(', ')}`)
      .note(`input std: ${before.map((st) => st.std.toFixed(1)).join(', ')}`)
      .panel('norm.zscore: (y − mean) / std', 't [min]', 'z');
    out.forEach((s, k) => chart.add(inputs[k].label, 'line', minutes, s.y));
    chart
      .note('expected: mean 0 and std 1 for every series')
      .note(`measured: |mean| <= ${worstMean.toExponential(0)}, |std − 1| <= ${worstStd.toExponential(0)}`)
      .write();
  });
});
