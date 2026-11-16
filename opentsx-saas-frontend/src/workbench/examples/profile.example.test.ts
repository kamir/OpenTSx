/** Operator example profile (docs/user-manual/17-operators.md): the profile of a constant is 0, the profile of a step is a V that ends at 0. */
import { describe, expect, it } from 'vitest';
import { OP_BY_ID } from '../ops';
import { Bucket } from '../types';
import { ExampleChart } from './chart';

const T0 = Date.parse('2026-10-01T00:00:00Z');

describe('profile example', () => {
  it('sums the deviations from the mean: 0 for a constant, down to -50 and back to 0 for a step from 4 to 6', () => {
    const seconds = Float64Array.from({ length: 100 }, (_, i) => i);
    const constant = Float64Array.from(seconds, () => 7.3);
    const step = Float64Array.from(seconds, (_, i) => (i < 50 ? 4 : 6));

    // usage:begin
    const raw: Bucket = {
      name: 'levels',
      xKind: 'time',
      xLabel: 't [s]',
      yLabel: 'y',
      t0Ms: T0,
      series: [
        { id: 'constant', label: 'constant 7.3', x: seconds, y: constant },
        { id: 'step', label: 'step 4 to 6', x: seconds, y: step },
      ],
    };
    const profile = OP_BY_ID['profile'];
    const [flat, v] = profile.apply(raw, {}).series;
    // usage:end

    expect(Array.from(flat.y)).toEqual(Array(100).fill(0));
    expect(Array.from(v.x)).toEqual(Array.from(seconds));
    v.y.forEach((p, i) => expect(p).toBeCloseTo(i < 50 ? -(i + 1) : i - 99, 12));
    const low = Math.min(...v.y);
    const lowAt = v.y.indexOf(low);

    new ExampleChart('profile', 'profile: 0 for a constant, a V down to -50 for a step from 4 to 6')
      .panel('Input', 't [s]', 'value')
      .add('constant 7.3', 'line', seconds, constant)
      .add('step 4 to 6', 'line', seconds, step)
      .panel('profile of the constant', 't [s]', 'running sum of y − mean')
      .add('constant 7.3', 'line', seconds, flat.y)
      .note('expected: constant 0')
      .note(`measured: constant ${Math.max(...flat.y.map(Math.abs))}`)
      .note('expected: step -50 at 49 s, 0 at the end')
      .note(`measured: step ${low.toFixed(3)} at ${lowAt} s, ${v.y[99].toExponential(0)} at the end`)
      .panel('profile of the step', 't [s]', 'running sum of y − mean')
      .add('step 4 to 6', 'line', seconds, v.y)
      .write();
  });
});
