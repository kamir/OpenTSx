/** Operator example fft (docs/user-manual/17-operators.md): a sum of three sines with noise gives three peaks at the frequencies and with the amplitudes of the sines. */
import { describe, expect, it } from 'vitest';
import { OP_BY_ID, mulberry32 } from '../ops';
import { Bucket } from '../types';
import { ExampleChart } from './chart';

const T0 = Date.parse('2026-10-01T00:00:00Z');
const RATE = 128;
const N = 2048;
const WAVES = [
  { hz: 2, amplitude: 0.5 },
  { hz: 5, amplitude: 1 },
  { hz: 12.5, amplitude: 2 },
];

function gauss(rnd: () => number): number {
  return Math.sqrt(-2 * Math.log(1 - rnd())) * Math.cos(2 * Math.PI * rnd());
}

describe('fft example', () => {
  it('finds the peaks of sines at 2, 5 and 12.5 Hz with the amplitudes 0.5, 1 and 2', () => {
    const rnd = mulberry32(1);
    const seconds = Float64Array.from({ length: N }, (_, i) => i / RATE);
    const y = Float64Array.from(seconds, (t) => {
      const waves = WAVES.reduce((sum, w, k) => sum + w.amplitude * Math.sin(2 * Math.PI * w.hz * t + k), 0);
      return waves + 0.5 * gauss(rnd);
    });

    // usage:begin
    const raw: Bucket = {
      name: 'vibration',
      xKind: 'time',
      xLabel: 't [s]',
      yLabel: 'acceleration',
      t0Ms: T0,
      series: [{ id: 'vibration', label: 'vibration', x: seconds, y }],
    };
    const fft = OP_BY_ID['fft'];
    const spectrum = fft.apply(raw, {}).series[0];
    // usage:end

    expect(spectrum.x).toHaveLength(N / 2);
    expect(spectrum.x[1]).toBe(RATE / N);
    const peaks = Array.from(spectrum.y.keys())
      .sort((a, b) => spectrum.y[b] - spectrum.y[a])
      .slice(0, 3)
      .sort((a, b) => a - b);
    expect(peaks.map((k) => spectrum.x[k])).toEqual(WAVES.map((w) => w.hz));
    peaks.forEach((k, j) => expect(Math.abs(spectrum.y[k] - WAVES[j].amplitude)).toBeLessThan(0.06));
    const floor = Math.max(...Array.from(spectrum.y).filter((_, k) => !peaks.includes(k)));
    expect(floor).toBeLessThan(0.1);

    const shown = Array.from({ length: RATE }, (_, i) => i);
    const band = Array.from({ length: 16 * (N / RATE) + 1 }, (_, k) => k);
    new ExampleChart('fft', 'fft: three sines give three peaks at their frequencies and amplitudes')
      .panel(`Input, first 1 s of ${N / RATE} s`, 't [s]', 'value')
      .add('input', 'line', shown.map((i) => seconds[i]), shown.map((i) => y[i]))
      .panel('Amplitude spectrum up to 16 Hz', 'f [Hz]', 'amplitude')
      .add('spectrum', 'line', band.map((k) => spectrum.x[k]), band.map((k) => spectrum.y[k]))
      .add('expected', 'point', WAVES.map((w) => w.hz), WAVES.map((w) => w.amplitude))
      .note('expected: 0.5, 1, 2 at 2, 5, 12.5 Hz')
      .note(`measured: ${peaks.map((k) => spectrum.y[k].toFixed(3)).join(', ')}`)
      .note(`largest other bin: ${floor.toFixed(3)}`)
      .write();
  });
});
