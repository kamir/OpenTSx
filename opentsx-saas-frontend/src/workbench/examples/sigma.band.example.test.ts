/** Operator example sigma.band (docs/user-manual/17-operators.md): across 8 series with a planted mean and spread, the band is that mean plus and minus that spread. */
import { describe, expect, it } from 'vitest';
import { OP_BY_ID, mulberry32, stats } from '../ops';
import { Bucket } from '../types';
import { ExampleChart } from './chart';

const T0 = Date.parse('2026-10-01T00:00:00Z');
const SERIES = 8;
const STEPS = 60;

function gauss(rnd: () => number): number {
  return Math.sqrt(-2 * Math.log(1 - rnd())) * Math.cos(2 * Math.PI * rnd());
}

describe('sigma.band example', () => {
  it('gives the planted mean and the planted standard deviation across the series at every time', () => {
    const rnd = mulberry32(6);
    const seconds = Float64Array.from({ length: STEPS }, (_, i) => i * 600);
    const mean = Array.from(seconds, (t) => 6 + 0.4 * (t / 3600) + 0.5 * Math.sin((2 * Math.PI * t) / 14400));
    const sigma = Array.from(seconds, (_, i) => 0.3 + (1.2 * i) / (STEPS - 1));
    const wind = Array.from({ length: SERIES }, () => new Float64Array(STEPS));
    for (let i = 0; i < STEPS; i++) {
      const z = Array.from({ length: SERIES }, () => gauss(rnd));
      const st = stats(z);
      z.forEach((v, k) => (wind[k][i] = mean[i] + (sigma[i] * (v - st.mean)) / st.std));
    }

    // usage:begin
    const raw: Bucket = {
      name: 'park',
      xKind: 'time',
      xLabel: 't [s]',
      yLabel: 'm/s',
      t0Ms: T0,
      series: wind.map((y, k) => ({ id: `WT0${k + 1}`, label: `WT0${k + 1} wind speed`, unit: 'm/s', x: seconds, y })),
    };
    const band = OP_BY_ID['sigma.band'];
    const [avg, upper, lower] = band.apply(raw, {}).series;
    // usage:end

    expect([avg.id, upper.id, lower.id]).toEqual(['avg', 'avg+sigma', 'avg-sigma']);
    expect(Array.from(avg.x)).toEqual(Array.from(seconds));
    const meanError = Math.max(...Array.from(avg.y, (v, i) => Math.abs(v - mean[i])));
    const sigmaError = Math.max(...Array.from(upper.y, (v, i) => Math.abs(v - avg.y[i] - sigma[i])));
    expect(meanError).toBeLessThan(1e-12);
    expect(sigmaError).toBeLessThan(1e-12);
    upper.y.forEach((v, i) => expect(avg.y[i] - lower.y[i]).toBeCloseTo(v - avg.y[i], 12));

    const hours = Array.from(seconds, (t) => t / 3600);
    new ExampleChart('sigma.band', 'sigma.band: mean ± sigma across 8 turbines')
      .panel('Input: wind speed of 8 turbines', 't [h]', 'wind speed [m/s]')
      .add('8 turbines', 'line', wind.flatMap(() => [...hours, NaN]), wind.flatMap((y) => [...y, NaN]))
      .panel('sigma.band', 't [h]', 'wind speed [m/s]')
      .add('mean + sigma', 'line', hours, upper.y)
      .add('mean', 'line', hours, avg.y)
      .add('mean − sigma', 'line', hours, lower.y)
      .note('expected: planted mean, sigma 0.3 to 1.5 m/s')
      .note(`measured: sigma ${(upper.y[0] - avg.y[0]).toFixed(3)} to ${(upper.y[STEPS - 1] - avg.y[STEPS - 1]).toFixed(3)} m/s`)
      .note(`largest error: mean ${meanError.toExponential(0)}, sigma ${sigmaError.toExponential(0)}`)
      .write();
  });
});
