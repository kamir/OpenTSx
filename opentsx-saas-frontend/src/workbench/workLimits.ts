import { esSettings } from './es';
import { MAX_PAIRS, pairClocks, pairRanges, pairSettings, rangesOf } from './pairs';
import { Bucket, Params, Series } from './types';
import { lagsOf } from './xcorr';

const MAX_CELLS = 1_000_000;
const MAX_WINDOWS = 100_000;
const MAX_WORK = 100_000_000;
const TOL = 5e-7;

function refuse(op: string): never {
  throw new Error(`${op}: browser work limit exceeded. Select fewer series, a shorter range or fewer lags/shuffles; use a larger clock or window step.`);
}
function bounded(op: string, value: number, limit: number): void {
  if (!Number.isFinite(value) || value < 0 || value > limit) refuse(op);
}
function tick(op: string, time: number, every: number, ceil: boolean): number {
  const value = ceil ? Math.ceil((time - TOL) / every) : Math.floor((time + TOL) / every);
  if (!Number.isSafeInteger(value)) refuse(op);
  return value;
}
function parameter(p: Params, key: string, fallback: number): number {
  const value = Number(p[key]);
  return Number.isFinite(value) ? value : fallback;
}

function pairWork(op: string, inputs: Bucket[], p: Params): void {
  const first = inputs[0];
  const st = pairSettings(p);
  const lags = op === 'pair.xcorr' ? lagsOf(p) : 0;
  const shuffles = op === 'pair.es' ? esSettings(p).shuffles : 0;
  bounded(op, 2 * lags + 1, MAX_CELLS);
  bounded(op, shuffles, MAX_WORK);
  const pairs: [Series, Series, Bucket][] = [];
  if (inputs.length === 1) {
    for (let a = 0; a < first.series.length; a++) {
      for (let b = a + 1; b < first.series.length; b++) pairs.push([first.series[a], first.series[b], first]);
    }
  } else {
    for (const a of first.series) for (const b of inputs[1].series) pairs.push([a, b, inputs[1]]);
  }
  let cells = 0;
  let windows = 0;
  let work = 0;
  const origin = (first.t0Ms ?? 0) / 1000;
  const intervals = new Map<Series, Map<string, number>>();
  for (const [a, b, other] of pairs) {
    const shift = ((other.t0Ms ?? first.t0Ms ?? 0) - (first.t0Ms ?? 0)) / 1000;
    const ranges = pairRanges(rangesOf(first, a), rangesOf(other, b).map((r) => ({ ...r, start: r.start + shift, end: r.end + shift })), st.pairing, st.window ?? 0);
    bounded(op, ranges.length, MAX_WINDOWS);
    const clocks = pairClocks(st.every, ranges, { series: a, shift: 0 }, { series: b, shift }, first.t0Ms ?? 0, intervals);
    for (const [k, r] of ranges.entries()) {
      const every = clocks[k];
      const length = r.end - r.start;
      const count = st.window === null ? (length > TOL ? 1 : 0) : length < st.window - TOL || !(st.step > 0) ? 0 : Math.floor((length - st.window) / st.step + 1e-9) + 1;
      windows += count;
      bounded(op, windows, MAX_WINDOWS);
      if (count === 0) continue;
      const ticks = tick(op, r.end + origin, every, false) - tick(op, r.start + origin, every, true);
      cells += 2 * Math.max(0, ticks);
      bounded(op, cells, MAX_CELLS);
      const perWindow = Math.min(Math.max(0, ticks), st.window === null ? ticks : Math.ceil(st.window / every) + 2);
      const products = op === 'pair.es' ? Math.max(1, Math.ceil(perWindow / 2) ** 2) * (shuffles + 1) : op === 'pair.xcorr' ? perWindow * (2 * lags + 1) : perWindow;
      work += count * (products + perWindow);
      bounded(op, work, MAX_WORK);
    }
  }
}

function vectorWork(op: string, inputs: Bucket[], p: Params): void {
  const every = Number(p.every);
  if (!(every > 0)) return;
  const keyBy = String(p.key ?? 'series').trim() || 'series';
  const groups = new Map<string, { lo: number; hi: number }>();
  let components = 0;
  for (const b of inputs) {
    const origin = (b.t0Ms ?? inputs[0].t0Ms ?? 0) / 1000;
    components += b.series.length;
    for (const s of b.series) {
      const key = keyBy === 'series' ? s.id : keyBy === 'all' ? '' : (s.tags?.[keyBy] ?? '');
      const range = groups.get(key) ?? { lo: Infinity, hi: -Infinity };
      for (let i = 0; i < s.x.length; i++) {
        if (!Number.isFinite(s.y[i])) continue;
        const value = Math.floor((s.x[i] + origin) / every);
        if (!Number.isSafeInteger(value)) refuse(op);
        range.lo = Math.min(range.lo, value);
        range.hi = Math.max(range.hi, value);
      }
      groups.set(key, range);
    }
  }
  let cells = 0;
  for (const range of groups.values()) {
    if (range.hi < range.lo) continue;
    cells += (range.hi - range.lo + 1) * components;
    bounded(op, cells, MAX_CELLS);
  }
}

export function checkBrowserWork(op: string, inputs: Bucket[], p: Params): void {
  const first = inputs[0];
  if (!first) return;
  if (op.startsWith('pair.')) bounded(op, inputs.length === 1 ? first.series.length * (first.series.length - 1) / 2 : first.series.length * inputs[1].series.length, MAX_PAIRS);
  bounded(op, inputs.reduce((n, b) => n + b.series.reduce((m, s) => m + s.x.length, 0), 0), MAX_CELLS);
  if (op.startsWith('pair.')) return pairWork(op, inputs, p);
  if (op === 'align.vector') return vectorWork(op, inputs, p);
  let work = 0;
  let cells = 0;
  for (const s of first.series) {
    const n = s.x.length;
    if (op === 'resample' || op === 'stats.window') {
      const every = parameter(p, op === 'resample' ? 'every' : 'window', 1);
      if (n === 0 || !(every > 0)) continue;
      const origin = first.xKind === 'time' ? (first.t0Ms ?? 0) / 1000 : 0;
      let lo = Infinity;
      let hi = -Infinity;
      for (const x of s.x) {
        const t = Math.floor((x + origin) / every);
        if (!Number.isSafeInteger(t)) refuse(op);
        lo = Math.min(lo, t);
        hi = Math.max(hi, t);
      }
      cells += hi - lo + 1;
    } else if (op === 'feature.rolling') {
      const window = parameter(p, 'window', 60);
      let start = 0;
      for (let i = 0; i < n; i++) {
        while (start <= i && s.x[start] <= s.x[i] - window) start++;
        work += i - start + 1;
        bounded(op, work, MAX_WORK);
      }
    } else if (op === 'shuffle') {
      work += Math.max(1, n) * Math.max(1, Math.round(parameter(p, 'rounds', 1)));
    } else if (op === 'acf') {
      work += n * Math.min(Math.floor(n / 2), Math.max(1, Math.round(parameter(p, 'maxLag', 1000))));
    } else if (op === 'dfa') {
      work += Math.max(1, n) * Math.max(4, Math.round(parameter(p, 'scales', 40)));
    } else if (['histogram', 'histogram.crosscut', 'cdf', 'entropy.window'].includes(op)) {
      const bins = Math.max(2, Math.round(parameter(p, 'bins', op === 'cdf' ? 30 : op === 'entropy.window' ? 16 : 50)));
      const length = Math.max(4, Math.round(parameter(p, 'length', 20)));
      const repeats = op === 'histogram.crosscut' ? 16 : op === 'entropy.window' ? Math.ceil(n / Math.max(1, Math.floor(length / 4))) : 1;
      cells += bins * repeats;
      work += cells + n;
    }
    bounded(op, cells, MAX_CELLS);
    bounded(op, work, MAX_WORK);
  }
}
