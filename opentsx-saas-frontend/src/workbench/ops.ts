/**
 * Operator library of the web workbench.
 *
 * The operations follow the button handlers of
 * org.opentsx.app.bucketanalyser.TSOperationControlerPanel and the methods of
 * org.opentsx.data.series.TimeSeriesObject they call. Where the web version
 * deviates from the Java code, the operator hint says so.
 */

import { Bucket, OpDef, Params, Series } from './types';
import { VECTOR_AGGREGATIONS, VECTOR_OP, alignVector } from './vector';

// ---------------------------------------------------------------- helpers

/** Deterministic PRNG, so a recorded flow gives the same result on every run. */
export function mulberry32(seed: number): () => number {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

export interface Stats {
  n: number;
  mean: number;
  std: number;
  min: number;
  max: number;
}

/** Mean, sample standard deviation, min and max over the finite values. */
export function stats(y: ArrayLike<number>): Stats {
  let n = 0;
  let mean = 0;
  let m2 = 0;
  let min = Infinity;
  let max = -Infinity;
  for (let i = 0; i < y.length; i++) {
    const v = y[i];
    if (!Number.isFinite(v)) continue;
    n++;
    const d = v - mean;
    mean += d / n;
    m2 += d * (v - mean);
    if (v < min) min = v;
    if (v > max) max = v;
  }
  if (n === 0) return { n: 0, mean: NaN, std: NaN, min: NaN, max: NaN };
  return { n, mean, std: n > 1 ? Math.sqrt(m2 / (n - 1)) : 0, min, max };
}

function num(p: Params, key: string, fallback: number): number {
  const v = Number(p[key]);
  return Number.isFinite(v) ? v : fallback;
}

function indexAxis(n: number): Float64Array {
  const x = new Float64Array(n);
  for (let i = 0; i < n; i++) x[i] = i;
  return x;
}

/** Applies fn to every series and keeps the x axis of the input. */
function mapY(b: Bucket, name: string, fn: (s: Series) => { y: Float64Array; label: string }): Bucket {
  return {
    ...b,
    name,
    note: undefined,
    series: b.series.map((s) => {
      const r = fn(s);
      return { ...s, y: r.y, label: r.label };
    }),
  };
}

/** Median spacing of the x axis. Used as sampling interval. */
export function sampleInterval(x: ArrayLike<number>): number {
  if (x.length < 2) return 1;
  const d: number[] = [];
  const step = Math.max(1, Math.floor(x.length / 512));
  for (let i = step; i < x.length; i += step) d.push((x[i] - x[i - step]) / step);
  d.sort((a, b) => a - b);
  const m = d[Math.floor(d.length / 2)];
  return m > 0 ? m : 1;
}

/** Slope and intercept of the least squares line through (x, y). */
export function linFit(x: ArrayLike<number>, y: ArrayLike<number>): { slope: number; intercept: number } {
  let n = 0;
  let sx = 0;
  let sy = 0;
  let sxx = 0;
  let sxy = 0;
  for (let i = 0; i < x.length; i++) {
    if (!Number.isFinite(x[i]) || !Number.isFinite(y[i])) continue;
    n++;
    sx += x[i];
    sy += y[i];
    sxx += x[i] * x[i];
    sxy += x[i] * y[i];
  }
  const den = n * sxx - sx * sx;
  if (n < 2 || den === 0) return { slope: NaN, intercept: NaN };
  const slope = (n * sxy - sx * sy) / den;
  return { slope, intercept: (sy - slope * sx) / n };
}

// ---------------------------------------------------------------- transformations

function shuffle(b: Bucket, p: Params): Bucket {
  const rounds = Math.max(1, Math.round(num(p, 'rounds', 1)));
  const rnd = mulberry32(Math.round(num(p, 'seed', 1)));
  // shuffled values have left their place in time: the ground truth no longer applies
  return mapY({ ...b, marks: undefined }, `Shuffle_${b.name}`, (s) => {
    const y = Float64Array.from(s.y);
    for (let r = 0; r < rounds; r++) {
      for (let i = y.length - 1; i > 0; i--) {
        const j = Math.floor(rnd() * (i + 1));
        const t = y[i];
        y[i] = y[j];
        y[j] = t;
      }
    }
    return { y, label: `${s.label} (s${rounds})` };
  });
}

function normMax(b: Bucket): Bucket {
  return mapY(b, `NormMAX_${b.name}`, (s) => {
    const max = stats(s.y).max;
    const y = Float64Array.from(s.y, (v) => v / max);
    return { y, label: `${s.label} (max=${fmt(max)})` };
  });
}

function normZ(b: Bucket): Bucket {
  return mapY(b, `Standardized_${b.name}`, (s) => {
    const st = stats(s.y);
    const sd = st.std > 0 ? st.std : 1;
    const y = Float64Array.from(s.y, (v) => (v - st.mean) / sd);
    return { y, label: `${s.label} (stdv=1.0;mw=0.0)` };
  });
}

function logY(b: Bucket): Bucket {
  // TimeSeriesObject.calcMR_Log_for_Y drops points with a non-finite logarithm.
  return {
    ...b,
    name: `LOG_${b.name}`,
    note: undefined,
    series: b.series.map((s) => {
      const xs: number[] = [];
      const ys: number[] = [];
      for (let i = 0; i < s.y.length; i++) {
        const v = Math.log(s.y[i]);
        if (Number.isFinite(v)) {
          xs.push(s.x[i]);
          ys.push(v);
        }
      }
      return { ...s, label: `${s.label}_ln`, x: Float64Array.from(xs), y: Float64Array.from(ys) };
    }),
  };
}

function logReturn(b: Bucket): Bucket {
  return mapY(b, `LogRETURN_${b.name}`, (s) => {
    const y = new Float64Array(s.y.length);
    for (let i = 1; i < y.length; i++) y[i] = Math.log(s.y[i]) - Math.log(s.y[i - 1]);
    return { y, label: `${s.label}_Log(RelDiff)` };
  });
}

function diff(b: Bucket): Bucket {
  return mapY(b, `Diff_${b.name}`, (s) => {
    const y = new Float64Array(s.y.length);
    for (let i = 1; i < y.length; i++) y[i] = s.y[i] - s.y[i - 1];
    return { y, label: `${s.label}_Returns` };
  });
}

/** Cumulated sum of the deviations from the mean (TimeSeriesObject.getProfile). */
export function profileOf(y: ArrayLike<number>): Float64Array {
  const mean = stats(y).mean;
  const out = new Float64Array(y.length);
  let sum = 0;
  for (let i = 0; i < y.length; i++) {
    sum += (Number.isFinite(y[i]) ? y[i] : mean) - mean;
    out[i] = sum;
  }
  return out;
}

function profile(b: Bucket): Bucket {
  return mapY(b, `Profile_${b.name}`, (s) => ({ y: profileOf(s.y), label: `${s.label}_Profil` }));
}

/** In-place radix-2 FFT. The length must be a power of two. */
export function fftRadix2(re: Float64Array, im: Float64Array): void {
  const n = re.length;
  for (let i = 1, j = 0; i < n; i++) {
    let bit = n >> 1;
    for (; j & bit; bit >>= 1) j ^= bit;
    j ^= bit;
    if (i < j) {
      const tr = re[i];
      re[i] = re[j];
      re[j] = tr;
      const ti = im[i];
      im[i] = im[j];
      im[j] = ti;
    }
  }
  for (let len = 2; len <= n; len <<= 1) {
    const ang = (-2 * Math.PI) / len;
    const wr = Math.cos(ang);
    const wi = Math.sin(ang);
    for (let i = 0; i < n; i += len) {
      let cr = 1;
      let ci = 0;
      for (let k = 0; k < len / 2; k++) {
        const a = i + k;
        const c = a + len / 2;
        const xr = re[c] * cr - im[c] * ci;
        const xi = re[c] * ci + im[c] * cr;
        re[c] = re[a] - xr;
        im[c] = im[a] - xi;
        re[a] += xr;
        im[a] += xi;
        const nr = cr * wr - ci * wi;
        ci = cr * wi + ci * wr;
        cr = nr;
      }
    }
  }
}

function fft(b: Bucket): Bucket {
  return {
    name: `FFT_${b.name}`,
    xKind: 'frequency',
    xLabel: b.xKind === 'time' ? 'f [Hz]' : 'f [1/sample]',
    yLabel: 'amplitude',
    series: b.series.map((s) => {
      const n = s.y.length;
      let size = 1;
      while (size < n) size <<= 1;
      const mean = stats(s.y).mean;
      const re = new Float64Array(size);
      const im = new Float64Array(size);
      for (let i = 0; i < n; i++) re[i] = Number.isFinite(s.y[i]) ? s.y[i] - mean : 0;
      fftRadix2(re, im);
      const rate = 1 / sampleInterval(s.x);
      const half = size / 2;
      const x = new Float64Array(half);
      const y = new Float64Array(half);
      for (let k = 0; k < half; k++) {
        x[k] = (k * rate) / size;
        y[k] = (2 * Math.hypot(re[k], im[k])) / Math.max(1, n);
      }
      return { ...s, label: `FFT_${s.label}`, unit: undefined, x, y };
    }),
  };
}

// ---------------------------------------------------------------- univariate analysis

function histogramOf(values: ArrayLike<number>, min: number, max: number, bins: number) {
  const counts = new Float64Array(bins);
  const width = (max - min) / bins || 1;
  let n = 0;
  for (let i = 0; i < values.length; i++) {
    const v = values[i];
    if (!Number.isFinite(v) || v < min || v > max) continue;
    counts[Math.min(bins - 1, Math.floor((v - min) / width))]++;
    n++;
  }
  const x = new Float64Array(bins);
  for (let k = 0; k < bins; k++) {
    x[k] = min + (k + 0.5) * width;
    if (n > 0) counts[k] /= n;
  }
  return { x, y: counts };
}

function histogram(b: Bucket, p: Params): Bucket {
  const bins = Math.max(2, Math.round(num(p, 'bins', 50)));
  const min = num(p, 'min', -10);
  const max = num(p, 'max', 10);
  return {
    name: `PDF_${b.name}`,
    xKind: 'value',
    xLabel: b.yLabel,
    yLabel: 'relative frequency',
    series: b.series.map((s) => ({ ...s, ...histogramOf(s.y, min, max, bins), unit: undefined })),
  };
}

function histogramCrossCut(b: Bucket, p: Params): Bucket {
  const bins = Math.max(2, Math.round(num(p, 'bins', 50)));
  const min = num(p, 'min', -10);
  const max = num(p, 'max', 10);
  const length = Math.max(2, Math.round(num(p, 'window', 100)));
  const n = b.series.length ? b.series[0].y.length : 0;
  const out: Series[] = [];
  // One histogram per time slice over all series. At most 16 slices are drawn.
  for (let pos = 0; pos < n && out.length < 16; pos += length) {
    const pool: number[] = [];
    for (const s of b.series) {
      for (let i = pos; i < Math.min(pos + length, s.y.length); i++) pool.push(s.y[i]);
    }
    out.push({ id: `slice-${pos}`, label: `${pos}_${pos + length}`, ...histogramOf(pool, min, max, bins) });
  }
  return { name: `PDF_cross_${b.name}`, xKind: 'value', xLabel: b.yLabel, yLabel: 'relative frequency', series: out };
}

function cdf(b: Bucket, p: Params): Bucket {
  const bins = Math.max(2, Math.round(num(p, 'bins', 30)));
  return {
    name: `CDF_${b.name}`,
    xKind: 'value',
    xLabel: b.yLabel,
    yLabel: 'cumulated frequency',
    series: b.series.map((s) => {
      const st = stats(s.y);
      const h = histogramOf(s.y, st.min, st.max, bins);
      let sum = 0;
      for (let k = 0; k < bins; k++) {
        sum += h.y[k];
        h.y[k] = sum;
      }
      return { ...s, ...h, unit: undefined };
    }),
  };
}

function shannonEntropy(values: ArrayLike<number>, from: number, to: number, min: number, max: number, bins: number) {
  const counts = new Float64Array(bins);
  const width = (max - min) / bins || 1;
  let n = 0;
  for (let i = from; i < to; i++) {
    const v = values[i];
    if (!Number.isFinite(v)) continue;
    counts[Math.min(bins - 1, Math.max(0, Math.floor((v - min) / width)))]++;
    n++;
  }
  let h = 0;
  for (let k = 0; k < bins; k++) {
    if (counts[k] > 0) {
      const q = counts[k] / n;
      h -= q * Math.log2(q);
    }
  }
  return h;
}

function entropyWindow(b: Bucket, p: Params): Bucket {
  const length = Math.max(4, Math.round(num(p, 'length', 20)));
  const bins = Math.max(2, Math.round(num(p, 'bins', 16)));
  const step = Math.max(1, Math.floor(length * 0.25));
  return {
    name: `H_${b.name}`,
    xKind: 'index',
    xLabel: 'window start [sample]',
    yLabel: 'H [bit]',
    series: b.series.map((s) => {
      const st = stats(s.y);
      const xs: number[] = [];
      const ys: number[] = [];
      for (let i = 0; i + 1 < s.y.length; i += step) {
        xs.push(i);
        ys.push(shannonEntropy(s.y, i, Math.min(i + length, s.y.length), st.min, st.max, bins));
      }
      return { ...s, label: `${s.label}H(l=${length})`, unit: 'bit', x: Float64Array.from(xs), y: Float64Array.from(ys) };
    }),
  };
}

function autoCorrelation(b: Bucket, p: Params): Bucket {
  const cap = Math.max(1, Math.round(num(p, 'maxLag', 1000)));
  return {
    name: `AC_${b.name}`,
    xKind: 'lag',
    xLabel: 'lag [sample]',
    yLabel: 'C(lag)',
    series: b.series.map((s) => {
      const n = s.y.length;
      const st = stats(s.y);
      const lags = Math.min(Math.floor(n / 2), cap);
      let c0 = 0;
      for (let i = 0; i < n; i++) c0 += (s.y[i] - st.mean) ** 2;
      const y = new Float64Array(lags);
      for (let k = 0; k < lags; k++) {
        let c = 0;
        for (let i = 0; i + k < n; i++) c += (s.y[i] - st.mean) * (s.y[i + k] - st.mean);
        y[k] = c0 > 0 ? c / c0 : 0;
      }
      return { ...s, label: `AC_${s.label}`, unit: undefined, x: indexAxis(lags), y };
    }),
  };
}

/** Sum of squared residuals of a least squares polynomial of the given order (1 or 2) over y[from, from + s). */
function detrendedVariance(y: Float64Array, from: number, s: number, order: number): number {
  // Centered abscissa keeps the normal equations well conditioned.
  const c = (s - 1) / 2;
  let s0 = 0, s1 = 0, s2 = 0, s3 = 0, s4 = 0, t0 = 0, t1 = 0, t2 = 0;
  for (let i = 0; i < s; i++) {
    const x = i - c;
    const x2 = x * x;
    const v = y[from + i];
    s0 += 1;
    s1 += x;
    s2 += x2;
    s3 += x2 * x;
    s4 += x2 * x2;
    t0 += v;
    t1 += x * v;
    t2 += x2 * v;
  }
  let a = 0, b1 = 0, c2 = 0;
  if (order >= 2) {
    // Solve the symmetric 3x3 system by Cramer's rule.
    const det = s0 * (s2 * s4 - s3 * s3) - s1 * (s1 * s4 - s3 * s2) + s2 * (s1 * s3 - s2 * s2);
    a = (t0 * (s2 * s4 - s3 * s3) - s1 * (t1 * s4 - s3 * t2) + s2 * (t1 * s3 - s2 * t2)) / det;
    b1 = (s0 * (t1 * s4 - s3 * t2) - t0 * (s1 * s4 - s3 * s2) + s2 * (s1 * t2 - t1 * s2)) / det;
    c2 = (s0 * (s2 * t2 - t1 * s3) - s1 * (s1 * t2 - t1 * s2) + t0 * (s1 * s3 - s2 * s2)) / det;
  } else {
    const det = s0 * s2 - s1 * s1;
    a = (t0 * s2 - s1 * t1) / det;
    b1 = (s0 * t1 - s1 * t0) / det;
  }
  let res = 0;
  for (let i = 0; i < s; i++) {
    const x = i - c;
    const d = y[from + i] - (a + b1 * x + c2 * x * x);
    res += d * d;
  }
  return res / s;
}

/** Fluctuation function F(s) of detrended fluctuation analysis. Returns log10(s) and log10(F(s)). */
export function dfa(values: ArrayLike<number>, order: number, scales: number) {
  const prof = profileOf(values);
  const n = prof.length;
  const sMin = order + 4;
  const sMax = Math.floor(n / 4);
  const xs: number[] = [];
  const ys: number[] = [];
  let last = 0;
  for (let k = 0; k < scales && sMax > sMin; k++) {
    const s = Math.round(sMin * Math.pow(sMax / sMin, k / Math.max(1, scales - 1)));
    if (s === last) continue;
    last = s;
    const windows = Math.floor(n / s);
    let f2 = 0;
    for (let w = 0; w < windows; w++) f2 += detrendedVariance(prof, w * s, s, order);
    const f = Math.sqrt(f2 / windows);
    if (f > 0) {
      xs.push(Math.log10(s));
      ys.push(Math.log10(f));
    }
  }
  return { x: Float64Array.from(xs), y: Float64Array.from(ys), alpha: linFit(xs, ys).slope };
}

function dfaOp(b: Bucket, p: Params): Bucket {
  const order = num(p, 'order', 2) >= 2 ? 2 : 1;
  const scales = Math.max(4, Math.round(num(p, 'scales', 40)));
  const lines: string[] = [];
  const series = b.series.map((s) => {
    const r = dfa(s.y, order, scales);
    lines.push(`${s.label}\talpha = ${fmt(r.alpha)}`);
    return { ...s, label: `${s.label}_alpha=${fmt(r.alpha)}`, unit: undefined, x: r.x, y: r.y };
  });
  return {
    name: `DFA_${order}${b.name}`,
    xKind: 'scale',
    xLabel: 'log(s)',
    yLabel: 'log(F(s))',
    series,
    note: `fluctuation function F(s) [order:${order}]\n${lines.join('\n')}`,
  };
}

// ---------------------------------------------------------------- group operations

function sigmaBand(b: Bucket): Bucket {
  if (b.series.length === 0) return { ...b, name: `AVG_${b.name}`, series: [] };
  const n = Math.min(...b.series.map((s) => s.y.length));
  const mean = new Float64Array(n);
  const up = new Float64Array(n);
  const down = new Float64Array(n);
  const column = new Float64Array(b.series.length);
  for (let i = 0; i < n; i++) {
    for (let k = 0; k < b.series.length; k++) column[k] = b.series[k].y[i];
    const st = stats(column);
    mean[i] = st.mean;
    up[i] = st.mean + st.std;
    down[i] = st.mean - st.std;
  }
  const x = b.series[0].x.slice(0, n);
  const unit = b.series[0].unit;
  return {
    ...b,
    name: `AVG_${b.name}`,
    yLabel: 'y(t) , sigma(t)',
    note: `mean and sigma band over ${b.series.length} series`,
    series: [
      { id: 'avg', label: `mean of ${b.series.length} series`, unit, x, y: mean },
      { id: 'avg+sigma', label: 'mean + sigma', unit, x, y: up },
      { id: 'avg-sigma', label: 'mean - sigma', unit, x, y: down },
    ],
  };
}

// ---------------------------------------------------------------- filter and conversion (stream operators)

function selectSeries(b: Bucket, p: Params): Bucket {
  const pattern = String(p.pattern ?? '');
  let re: RegExp;
  try {
    re = new RegExp(pattern, 'i');
  } catch {
    re = /$^/;
  }
  return { ...b, name: `Select_${b.name}`, note: undefined, series: b.series.filter((s) => re.test(s.label) || re.test(s.id)) };
}

function plausibleRange(b: Bucket, p: Params): Bucket {
  const min = num(p, 'min', -Infinity);
  const max = num(p, 'max', Infinity);
  const clip = p.mode === 'clip';
  return mapY(b, `Range_${b.name}`, (s) => ({
    label: s.label,
    y: Float64Array.from(s.y, (v) => (v < min ? (clip ? min : NaN) : v > max ? (clip ? max : NaN) : v)),
  }));
}

function convertUnit(b: Bucket, p: Params): Bucket {
  const factor = num(p, 'factor', 1);
  const offset = num(p, 'offset', 0);
  const unit = String(p.unit ?? '');
  return {
    ...b,
    name: `Convert_${b.name}`,
    note: undefined,
    yLabel: unit || b.yLabel,
    series: b.series.map((s) => ({
      ...s,
      unit: unit || s.unit,
      y: Float64Array.from(s.y, (v) => v * factor + offset),
    })),
  };
}

function aggregate(values: number[], agg: string): number {
  if (values.length === 0) return NaN;
  switch (agg) {
    case 'MIN':
      return Math.min(...values);
    case 'MAX':
      return Math.max(...values);
    case 'LAST':
      return values[values.length - 1];
    case 'STD':
      return stats(values).std;
    default:
      return stats(values).mean;
  }
}

/** Buckets x into windows of the given width and aggregates y per window. */
function windowed(b: Bucket, every: number, agg: string, name: string): Bucket {
  return {
    ...b,
    name,
    note: undefined,
    series: b.series.map((s) => {
      const xs: number[] = [];
      const ys: number[] = [];
      if (s.x.length === 0 || !(every > 0)) return { ...s, x: new Float64Array(0), y: new Float64Array(0) };
      // On a time axis with a known start the windows follow the epoch, as in the processor: 00:01:00 is a
      // boundary of a minute whatever the first sample is. x is in seconds from t0.
      const origin = b.xKind === 'time' && b.t0Ms !== undefined ? b.t0Ms / 1000 : 0;
      let start = Math.floor((s.x[0] + origin) / every) * every - origin;
      let pool: number[] = [];
      for (let i = 0; i < s.x.length; i++) {
        while (s.x[i] >= start + every) {
          xs.push(start);
          ys.push(aggregate(pool, agg));
          pool = [];
          start += every;
        }
        if (Number.isFinite(s.y[i])) pool.push(s.y[i]);
      }
      xs.push(start);
      ys.push(aggregate(pool, agg));
      return { ...s, x: Float64Array.from(xs), y: Float64Array.from(ys) };
    }),
  };
}

function resample(b: Bucket, p: Params): Bucket {
  return windowed(b, num(p, 'every', 1), String(p.agg ?? 'MEAN'), `Resample_${b.name}`);
}

function windowStats(b: Bucket, p: Params): Bucket {
  const field = String(p.field ?? 'MEAN');
  const out = windowed(b, num(p, 'window', 1), field, `Stats_${field}_${b.name}`);
  return { ...out, series: out.series.map((s) => ({ ...s, label: `${field.toLowerCase()}(${s.label})` })) };
}

function movingAverage(b: Bucket, p: Params): Bucket {
  const w = Math.max(1, Math.round(num(p, 'window', 5)));
  return mapY(b, `Smooth_${b.name}`, (s) => {
    const y = new Float64Array(s.y.length);
    let sum = 0;
    let n = 0;
    for (let i = 0; i < y.length; i++) {
      if (Number.isFinite(s.y[i])) {
        sum += s.y[i];
        n++;
      }
      if (i >= w && Number.isFinite(s.y[i - w])) {
        sum -= s.y[i - w];
        n--;
      }
      y[i] = n > 0 ? sum / n : NaN;
    }
    return { y, label: `${s.label} (ma${w})` };
  });
}

/** Indices where y crosses the level in the given direction. */
export function crossings(y: ArrayLike<number>, level: number, rising: boolean): number[] {
  const out: number[] = [];
  for (let i = 1; i < y.length; i++) {
    const a = y[i - 1];
    const c = y[i];
    if (rising ? a < level && c >= level : a > level && c <= level) out.push(i);
  }
  return out;
}

function segmentThreshold(b: Bucket, p: Params): Bucket {
  const level = num(p, 'enter', 0);
  const rising = p.direction !== 'BELOW';
  const pre = Math.max(0, Math.round(num(p, 'pre', 20)));
  const post = Math.max(1, Math.round(num(p, 'post', 80)));
  const out: Series[] = [];
  let events = 0;
  let inside = 0;
  const marks = b.xKind === 'time' ? (b.marks ?? []) : [];
  for (const s of b.series) {
    const dt = sampleInterval(s.x);
    for (const i of crossings(s.y, level, rising)) {
      events++;
      if (marks.some((m) => s.x[i] >= m.from && s.x[i] < m.to)) inside++;
      if (out.length >= 24) continue;
      const from = Math.max(0, i - pre);
      const to = Math.min(s.y.length, i + post);
      const x = new Float64Array(to - from);
      for (let k = 0; k < x.length; k++) x[k] = (from + k - i) * dt;
      out.push({ id: `${s.id}@${i}`, label: `${s.label} @${fmt(s.x[i])}`, unit: s.unit, x, y: s.y.slice(from, to) });
    }
  }
  return {
    name: `Segments_${b.name}`,
    xKind: b.xKind === 'time' ? 'time' : 'index',
    xLabel: 'offset from the crossing',
    yLabel: b.yLabel,
    series: out,
    note:
      `${events} episodes at level ${level} (${rising ? 'ABOVE' : 'BELOW'}); the first ${out.length} are drawn, aligned on the crossing` +
      (marks.length > 0
        ? `\n${inside} of ${events} crossings lie inside a ground-truth event (${[...new Set(marks.map((m) => m.type))].join(', ')})`
        : ''),
  };
}

// ---------------------------------------------------------------- catalog

export function fmt(v: number): string {
  if (!Number.isFinite(v)) return String(v);
  const a = Math.abs(v);
  if (a !== 0 && (a < 1e-3 || a >= 1e6)) return v.toExponential(3);
  return String(Math.round(v * 1000) / 1000);
}

const HIST_PARAMS = [
  { key: 'min', label: 'min', type: 'number' as const, default: -10 },
  { key: 'max', label: 'max', type: 'number' as const, default: 10 },
  { key: 'bins', label: 'bins', type: 'number' as const, default: 50, min: 2 },
];

export const OPS: OpDef[] = [
  // Tab "Transformations" of the Swing panel
  { id: 'shuffle', label: 'Shuffle', group: 'transform', prefix: 'Shuffle_', pfdOperation: 'shuffleYValues', inCatalog: false,
    hint: 'Shuffles the y values. Seeded, so the flow is reproducible (Java uses Collections.shuffle).',
    params: [
      { key: 'rounds', label: 'rounds', type: 'number', default: 1, min: 1 },
      { key: 'seed', label: 'seed', type: 'number', default: 1 },
    ], apply: shuffle },
  { id: 'norm.max', label: 'NORM max=1', group: 'transform', prefix: 'NormMAX_', pfdOperation: 'NORMALIZE', inCatalog: false,
    hint: 'Divides each series by its maximum.', params: [], apply: normMax },
  { id: 'norm.zscore', label: 'NORM std=1, avg=0', group: 'transform', prefix: 'Standardized_', pfdOperation: 'NORMALIZE', inCatalog: false,
    hint: 'Subtracts the mean and divides by the standard deviation.', params: [], apply: normZ },
  { id: 'log', label: 'Log', group: 'transform', prefix: 'LOG_', pfdOperation: 'LOG', inCatalog: false,
    hint: 'Natural logarithm. Points without a finite logarithm are dropped.', params: [], apply: logY },
  { id: 'logreturn', label: 'Returns (Rel-Diff)', group: 'transform', prefix: 'LogRETURN_', pfdOperation: 'LOGRETURN', inCatalog: false,
    hint: 'ln(y[i]) minus ln(y[i-1]).', params: [], apply: logReturn },
  { id: 'diff', label: 'Diffs', group: 'transform', prefix: 'Diff_', pfdOperation: 'DIFF', inCatalog: false,
    hint: 'y[i] minus y[i-1].', params: [], apply: diff },
  { id: 'profile', label: 'Profile', group: 'transform', prefix: 'Profile_', pfdOperation: 'PROFILE', inCatalog: false,
    hint: 'Cumulated sum of the deviations from the mean.', params: [], apply: profile },
  { id: 'fft', label: 'FFT', group: 'transform', prefix: 'FFT_', pfdOperation: 'FFT', inCatalog: false,
    hint: 'One-sided amplitude spectrum. Mean removed, zero-padded to a power of two.', params: [], apply: fft },

  // Tab "Uni-variate. Analysis"
  { id: 'histogram', label: 'Histogram per series', group: 'univariate', prefix: 'PDF_', pfdOperation: 'PDF', inCatalog: false,
    hint: 'Relative frequency of the y values per series.', params: HIST_PARAMS, apply: histogram },
  { id: 'histogram.crosscut', label: 'Histogram cross-cut', group: 'univariate', prefix: 'PDF_cross_', pfdOperation: 'PDF_CROSS', inCatalog: false,
    hint: 'One histogram per time slice over all series. At most 16 slices.',
    params: [...HIST_PARAMS, { key: 'window', label: 'window l', type: 'number', default: 100, min: 2 }], apply: histogramCrossCut },
  { id: 'cdf', label: 'CDF', group: 'univariate', prefix: 'CDF_', pfdOperation: 'CDF', inCatalog: false,
    hint: 'Cumulated distribution of the y values between min and max of each series.',
    params: [{ key: 'bins', label: 'bins', type: 'number', default: 30, min: 2 }], apply: cdf },
  { id: 'entropy.window', label: 'ENTROPY', group: 'univariate', prefix: 'H_', pfdOperation: 'H', inCatalog: false,
    hint: 'Shannon entropy in a sliding window, step length/4. Binning over the value range of the series; the Java binning may differ.',
    params: [
      { key: 'length', label: 'length', type: 'number', default: 20, min: 4 },
      { key: 'bins', label: 'bins', type: 'number', default: 16, min: 2 },
    ], apply: entropyWindow },
  { id: 'acf', label: 'Auto-Correlation', group: 'univariate', prefix: 'AC_', pfdOperation: 'AC', inCatalog: false,
    hint: 'Autocorrelation up to half the series length, capped at maxLag.',
    params: [{ key: 'maxLag', label: 'max lag', type: 'number', default: 1000, min: 1 }], apply: autoCorrelation },
  { id: 'dfa', label: 'DFA', group: 'univariate', prefix: 'DFA_', pfdOperation: 'DFA', inCatalog: false,
    hint: 'Detrended fluctuation analysis. The exponent alpha is the slope of log F(s) over log s across all scales.',
    params: [
      { key: 'order', label: 'order', type: 'number', default: 2, min: 1 },
      { key: 'scales', label: 'scales', type: 'number', default: 40, min: 4 },
    ], apply: dfaOp },

  // Tab "Group Operations"
  { id: 'sigma.band', label: 'Sigma Band', group: 'group', prefix: 'AVG_', pfdOperation: 'AVG', inCatalog: false,
    hint: 'Mean over all series with a band of one standard deviation.', params: [], apply: sigmaBand },

  // Filter and conversion, the operators a Smart Topic runs in the stream
  { id: 'select', label: 'Select series', group: 'stream', prefix: 'Select_', pfdOperation: 'FILTER', inCatalog: false,
    hint: 'Keeps the series whose label matches the pattern (regular expression).',
    params: [{ key: 'pattern', label: 'pattern', type: 'text', default: '.*' }], apply: selectSeries },
  { id: 'dq.plausible-range', dagOp: 'dq.checks', label: 'Plausible range', group: 'stream', prefix: 'Range_', pfdOperation: 'FILTER_RANGE', inCatalog: true,
    hint: 'Values outside [min, max] become gaps or are clipped. Catalog operator dq.checks, check PLAUSIBLE_RANGE.',
    params: [
      { key: 'min', label: 'min', type: 'number', default: -10 },
      { key: 'max', label: 'max', type: 'number', default: 10 },
      { key: 'mode', label: 'mode', type: 'select', default: 'gap', options: ['gap', 'clip'] },
    ], apply: plausibleRange },
  { id: 'convert.unit', label: 'Convert unit', group: 'stream', prefix: 'Convert_', pfdOperation: 'CONVERT', inCatalog: false,
    hint: 'y * factor + offset, with a new unit.',
    params: [
      { key: 'factor', label: 'factor', type: 'number', default: 1 },
      { key: 'offset', label: 'offset', type: 'number', default: 0 },
      { key: 'unit', label: 'unit', type: 'text', default: '' },
    ], apply: convertUnit },
  { id: 'resample', label: 'Resample', group: 'stream', prefix: 'Resample_', pfdOperation: 'WINDOW_AGGREGATE', inCatalog: true,
    hint: 'Aggregates into windows of the given width, in units of the x axis.',
    params: [
      { key: 'every', label: 'every', type: 'number', default: 0.05, min: 0 },
      { key: 'agg', label: 'agg', type: 'select', default: 'MEAN', options: ['MEAN', 'MIN', 'MAX', 'LAST'] },
    ], apply: resample },
  { id: 'smooth.movavg', label: 'Moving average', group: 'stream', prefix: 'Smooth_', pfdOperation: 'SMOOTH', inCatalog: false,
    hint: 'Trailing moving average over the given number of samples.',
    params: [{ key: 'window', label: 'samples', type: 'number', default: 25, min: 1 }], apply: movingAverage },
  { id: 'stats.window', dagOp: 'stats.welford-ddsketch', label: 'Window statistics', group: 'stream', prefix: 'Stats_', pfdOperation: 'WINDOW_AGGREGATE', inCatalog: true,
    hint: 'One statistic per window. Catalog operator stats.welford-ddsketch.',
    params: [
      { key: 'window', label: 'window', type: 'number', default: 0.25, min: 0 },
      { key: 'field', label: 'field', type: 'select', default: 'STD', options: ['MEAN', 'STD', 'MIN', 'MAX'] },
    ], apply: windowStats },
  { id: 'segment.threshold', label: 'Segment at threshold', group: 'stream', prefix: 'Segments_', pfdOperation: 'SEGMENT_THRESHOLD', inCatalog: true,
    hint: 'Cuts one episode per level crossing. The scope trigger sets these parameters.',
    params: [
      { key: 'enter', label: 'level', type: 'number', default: 0 },
      { key: 'direction', label: 'direction', type: 'select', default: 'ABOVE', options: ['ABOVE', 'BELOW'] },
      { key: 'pre', label: 'pre', type: 'number', default: 20, min: 0 },
      { key: 'post', label: 'post', type: 'number', default: 80, min: 1 },
    ], apply: segmentThreshold },
  { id: VECTOR_OP, label: 'Align to vector', group: 'stream', prefix: 'Vector_', pfdOperation: 'ALIGN_VECTOR', inCatalog: false, multiInput: true,
    hint: 'Brings this bucket and further inputs onto one clock: one measuring point per tick with one component per input. "vector per" is series (same series in every input), all (everything in one vector) or a tag name such as turbine. Add the inputs in the Properties tab or draw a connection onto the node. "lateness" only matters once the flow is deployed: how long a tick waits for series that lag behind.',
    params: [
      { key: 'every', label: 'clock [s]', type: 'number', default: 60, min: 0 },
      { key: 'key', label: 'vector per', type: 'text', default: 'series' },
      { key: 'agg', label: 'agg', type: 'select', default: 'MEAN', options: VECTOR_AGGREGATIONS },
      { key: 'fill', label: 'fill', type: 'select', default: 'none', options: ['none', 'as-of'] },
      { key: 'maxAge', label: 'max age [s]', type: 'number', default: 60, min: 0 },
      { key: 'emit', label: 'emit', type: 'select', default: 'any', options: ['any', 'complete'] },
      { key: 'names', label: 'names', type: 'text', default: '' },
      { key: 'lateness', label: 'lateness [s]', type: 'number', default: 0, min: 0 },
    ], apply: alignVector },
];

export const OP_BY_ID: Record<string, OpDef> = Object.fromEntries(OPS.map((o) => [o.id, o]));

/** Operations that have a button in the Swing panel but no implementation here. */
export const NOT_PORTED: { label: string; group: 'univariate' | 'model'; reason: string }[] = [
  { label: 'TEST Normal Distribution', group: 'univariate', reason: 'Shapiro-Wilk test is not ported' },
  { label: 'MF-DFA', group: 'univariate', reason: 'MFDFAAnalyzer is not ported' },
  { label: 'AR', group: 'model', reason: 'No handler in the Swing panel either' },
  { label: 'MA', group: 'model', reason: 'No handler in the Swing panel either' },
  { label: 'ARMA', group: 'model', reason: 'No handler in the Swing panel either' },
  { label: 'ARIMA', group: 'model', reason: 'No handler in the Swing panel either' },
  { label: 'SARIMA', group: 'model', reason: 'No handler in the Swing panel either' },
  { label: 'CARIMA', group: 'model', reason: 'No handler in the Swing panel either' },
];

export function defaultParams(op: OpDef): Params {
  return Object.fromEntries(op.params.map((p) => [p.key, p.default]));
}
