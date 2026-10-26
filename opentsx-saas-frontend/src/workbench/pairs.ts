/**
 * Pair windows: a pair method compares two series A and B window by window and gives one result item per window,
 * stamped at the window centre. Design: EVOLUTION/TASK-015-multivariate-analysis.md, section "Pair windows".
 *
 * - Pair range: `same` pairs an episode of A with the episode of B that has the same start and end; `intersect`
 *   pairs overlapping episodes on their intersection and drops one shorter than the window. A series without
 *   episode ranges counts as one range from its first sample to its last plus one sampling interval.
 * - Windows: `window: episode` is one window over the pair range, its item at the centre of the range. A width W
 *   with step S gives n = floor((L - W) / S) + 1 windows over a range of length L; window k covers
 *   [t0 + kS, t0 + kS + W) and its item is stamped at t0 + kS + W/2. A window that would end after the range is not
 *   computed.
 * - Alignment: A and B go onto one clock by the rules of align.vector (vector.ts): ticks of `every` seconds since the
 *   epoch, one aggregate per tick, fill as-of up to `maxAge`. Only ticks that lie inside the window count, and the
 *   fill does not reach back across the start of the pair range. The default clock is the larger of the two
 *   sampling intervals.
 * - Coverage: the share of ticks of a window in which both A and B have a value. Below `minCoverage` the item is NaN
 *   with quality 1, so the time index stays regular.
 * - Result: one series per pair and field, metric `pair.<method>.<field>`, tags a, b, window and step, the series id
 *   derived from them as for any series (seriesKeys.ts). Each pair range gives one result episode, labelled with both
 *   input episodes and the settings, its parent the episode of A.
 */

import { episodesOf } from './episodes';
import { seriesIdOf } from './seriesKeys';
import { Bucket, EpisodeRange, Params, Series } from './types';
import { Cell, VECTOR_AGGREGATIONS, addSamples, fillColumn } from './vector';

/** Half a microsecond: the v2 model keeps times to the microsecond, so closer times are the same time. */
const TOL = 5e-7;
/** A pair operator stops above this many pairs; the browser would take minutes. */
export const MAX_PAIRS = 200;

export interface PairWindow {
  /** Aggregates of A and B per tick of the window, NaN where one has no value. */
  a: Float64Array;
  b: Float64Array;
  /** Clock in seconds. */
  every: number;
  /** Share of ticks in which both have a value. */
  coverage: number;
}

export interface PairMethod {
  /** `pair.<name>`: the prefix of the metric of every field and the label `pair.method`. */
  id: string;
  /** Prefix of the result bucket name. */
  prefix: string;
  fields: string[];
  yLabel: string;
  /** One value per field, in the order of `fields`; `p` are the parameters of the operator. */
  compute: (w: PairWindow, p: Params) => number[];
  /**
   * Settings of the method beyond the window, checked once per run: they are written into the result episodes as
   * labels `pair.<key>` and into the note. Throws when a setting is invalid.
   */
  settings?: (p: Params) => Record<string, string>;
}

export interface PairSettings {
  pairing: 'same' | 'intersect';
  /** Window width in seconds; null for `window: episode`. */
  window: number | null;
  step: number;
  /** Clock in seconds; null for the larger sampling interval of the two series. */
  every: number | null;
  agg: string;
  /** Fill as-of up to this age in seconds; null without fill. */
  maxAge: number | null;
  minCoverage: number;
}

export const PAIRINGS = ['same', 'intersect'];

/** Reads and checks the parameters of a pair operator. */
export function pairSettings(p: Params): PairSettings {
  const pairing = String(p.pairing ?? 'same');
  if (!PAIRINGS.includes(pairing)) throw new Error(`Pairing "${pairing}" is unknown. Use same or intersect.`);
  const w = String(p.window ?? 'episode').trim();
  const window = w === 'episode' || w === '' ? null : Number(w);
  if (window !== null && !(window > 0)) throw new Error(`The window "${w}" is neither episode nor a width in seconds greater than 0.`);
  const step = window === null ? 0 : Number(p.step) > 0 ? Number(p.step) : window;
  const every = Number(p.every) > 0 ? Number(p.every) : null;
  const agg = String(p.agg ?? 'MEAN');
  if (!VECTOR_AGGREGATIONS.includes(agg)) throw new Error(`Aggregate "${agg}" is unknown.`);
  const maxAge = String(p.fill ?? 'none') === 'as-of' ? Math.max(0, Number(p.maxAge) || 0) : null;
  const minCoverage = Number.isFinite(Number(p.minCoverage)) ? Number(p.minCoverage) : 0.8;
  if (minCoverage < 0 || minCoverage > 1) throw new Error('The minimum coverage is a share between 0 and 1.');
  return { pairing: pairing as PairSettings['pairing'], window, step, every, agg, maxAge, minCoverage };
}

/** Tag value of a duration: an ISO-8601 duration in seconds, as the DAG writes them. */
function durationTag(seconds: number | null): string {
  return seconds === null ? 'episode' : `PT${Number(seconds.toPrecision(12))}S`;
}

/** Median spacing of the x values: the sampling interval of a series. */
export function samplingInterval(x: ArrayLike<number>): number {
  if (x.length < 2) return 1;
  const d: number[] = [];
  for (let i = 1; i < x.length; i++) d.push(x[i] - x[i - 1]);
  d.sort((p, q) => p - q);
  const m = d[Math.floor(d.length / 2)];
  return m > 0 ? m : 1;
}

/** The episode ranges of a series, or one range over the whole series when the bucket carries none for it. */
export function rangesOf(b: Bucket, s: Series): EpisodeRange[] {
  const own = episodesOf(b, s.id);
  if (own.length > 0 || s.x.length === 0) return own;
  const start = s.x[0];
  return [{ seriesId: s.id, episodeId: `${s.id}@${start}`, start, end: s.x[s.x.length - 1] + samplingInterval(s.x) }];
}

export interface PairRange {
  start: number;
  end: number;
  a: EpisodeRange;
  b: EpisodeRange;
}

/**
 * The pair ranges of two series from their episode ranges, both on the same x axis and sorted by start.
 * `minLength` is the window width: an intersection shorter than it is dropped.
 */
export function pairRanges(as: EpisodeRange[], bs: EpisodeRange[], pairing: PairSettings['pairing'], minLength = 0): PairRange[] {
  const out: PairRange[] = [];
  if (pairing === 'same') {
    const key = (r: EpisodeRange) => `${Math.round(r.start / (2 * TOL))}:${Math.round(r.end / (2 * TOL))}`;
    const byKey = new Map(bs.map((r) => [key(r), r]));
    for (const a of as) {
      const b = byKey.get(key(a));
      if (b) out.push({ start: a.start, end: a.end, a, b });
    }
    return out;
  }
  let i = 0;
  let j = 0;
  while (i < as.length && j < bs.length) {
    const a = as[i];
    const b = bs[j];
    const start = Math.max(a.start, b.start);
    const end = Math.min(a.end, b.end);
    if (end - start > TOL && end - start >= minLength - TOL) out.push({ start, end, a, b });
    if (a.end <= b.end) i++;
    else j++;
  }
  return out;
}

export interface WindowSpan {
  start: number;
  end: number;
  centre: number;
}

/** The windows of a pair range; `width` null is the whole range. */
export function windowsOf(start: number, end: number, width: number | null, step: number): WindowSpan[] {
  const length = end - start;
  if (width === null) return length > TOL ? [{ start, end, centre: start + length / 2 }] : [];
  if (length < width - TOL || !(step > 0)) return [];
  const n = Math.floor((length - width) / step + 1e-9) + 1;
  return Array.from({ length: n }, (_, k) => {
    const s = start + k * step;
    return { start: s, end: s + width, centre: s + width / 2 };
  });
}

/** First tick that starts at or after `t` (epoch seconds). */
function tickFrom(t: number, every: number): number {
  return Math.ceil((t - TOL) / every);
}

/** First tick that ends after `t`: the end of the ticks that lie before `t`. */
function tickTo(t: number, every: number): number {
  return Math.floor((t + TOL) / every);
}

interface Side {
  bucket: Bucket;
  series: Series;
  /** Seconds to add to x of this series to get x of A's bucket. */
  shift: number;
}

/** The pairs a pair operator compares: every pair of the one input, or each series of A with each of B. */
function pairsOf(first: Bucket, others: Bucket[]): [Side, Side][] {
  if (others.length > 1) throw new Error('A pair method reads two inputs at most: A and B.');
  const origin = first.t0Ms ?? 0;
  const side = (bucket: Bucket, series: Series): Side => ({ bucket, series, shift: ((bucket.t0Ms ?? origin) - origin) / 1000 });
  const pairs: [Side, Side][] = [];
  if (others.length === 0) {
    const list = first.series;
    for (let i = 0; i < list.length; i++) {
      for (let j = i + 1; j < list.length; j++) pairs.push([side(first, list[i]), side(first, list[j])]);
    }
    if (list.length < 2) throw new Error(`${first.name} has ${list.length} series. A pair needs two: add a second input or load more series.`);
  } else {
    for (const a of first.series) for (const b of others[0].series) pairs.push([side(first, a), side(others[0], b)]);
    if (pairs.length === 0) throw new Error('One of the two inputs has no series.');
  }
  if (pairs.length > MAX_PAIRS) {
    throw new Error(`${pairs.length} pairs, more than ${MAX_PAIRS}. Select fewer series first ("Select series").`);
  }
  return pairs;
}

/** Runs a pair method over the pairs of the inputs. `first` is A, `others[0]` B when given. */
export function pairWindows(method: PairMethod, first: Bucket, p: Params, others: Bucket[] = []): Bucket {
  const st = pairSettings(p);
  const own = method.settings?.(p) ?? {};
  for (const b of [first, ...others]) {
    if (b.xKind !== 'time') throw new Error(`${b.name} has a ${b.xKind} axis. A pair method needs inputs with a time axis.`);
  }
  const originS = (first.t0Ms ?? 0) / 1000;
  const windowTag = durationTag(st.window);
  const stepTag = st.window === null ? 'episode' : durationTag(st.step);
  // a series takes part in several pairs: its ticks are filled once per clock
  const cellCache = new Map<Series, Map<number, Map<number, Cell>>>();
  const cellsOf = (s: Side, every: number): Map<number, Cell> => {
    const byClock = cellCache.get(s.series) ?? new Map<number, Map<number, Cell>>();
    cellCache.set(s.series, byClock);
    let cells = byClock.get(every);
    if (!cells) {
      cells = new Map();
      addSamples(cells, s.series, s.shift + originS, every);
      byClock.set(every, cells);
    }
    return cells;
  };

  const series: Series[] = [];
  const episodes: EpisodeRange[] = [];
  const clocks = new Set<number>();
  let rangesUsed = 0;
  let rangesShort = 0;
  let items = 0;
  let low = 0;
  for (const [A, B] of pairsOf(first, others)) {
    const every = st.every ?? Math.max(samplingInterval(A.series.x), samplingInterval(B.series.x));
    clocks.add(every);
    const maxAgeTicks = st.maxAge === null ? -1 : Math.floor(st.maxAge / every);
    const shifted = (r: EpisodeRange, shift: number): EpisodeRange => (shift === 0 ? r : { ...r, start: r.start + shift, end: r.end + shift });
    const ranges = pairRanges(
      rangesOf(A.bucket, A.series).map((r) => shifted(r, A.shift)),
      rangesOf(B.bucket, B.series).map((r) => shifted(r, B.shift)),
      st.pairing,
      st.window ?? 0,
    );
    const xs: number[] = [];
    const ys: number[][] = method.fields.map(() => []);
    const qs: number[] = [];
    const spans: { start: number; end: number; range: PairRange }[] = [];
    for (const range of ranges) {
      const windows = windowsOf(range.start, range.end, st.window, st.step);
      if (windows.length === 0) {
        rangesShort++;
        continue;
      }
      rangesUsed++;
      const lo = tickFrom(range.start + originS, every);
      const n = tickTo(range.end + originS, every) - lo;
      const colA = fillColumn(cellsOf(A, every), lo, n, st.agg, maxAgeTicks);
      const colB = fillColumn(cellsOf(B, every), lo, n, st.agg, maxAgeTicks);
      for (const w of windows) {
        const j0 = Math.max(0, tickFrom(w.start + originS, every) - lo);
        const j1 = Math.min(n, tickTo(w.end + originS, every) - lo);
        let both = 0;
        for (let j = j0; j < j1; j++) if (Number.isFinite(colA[j]) && Number.isFinite(colB[j])) both++;
        const coverage = j1 > j0 ? both / (j1 - j0) : 0;
        const ok = j1 > j0 && coverage >= st.minCoverage - 1e-12;
        const values = ok
          ? method.compute({ a: colA.subarray(j0, j1), b: colB.subarray(j0, j1), every, coverage }, p)
          : method.fields.map(() => NaN);
        xs.push(w.centre);
        values.forEach((v, f) => ys[f].push(v));
        qs.push(ok ? 0 : 1);
        items++;
        if (!ok) low++;
      }
      const centre = windows[0].centre;
      spans.push({
        start: centre,
        // sliding: n values at an interval of S; episode: a single value, which ends one microsecond later
        end: st.window === null ? centre + 2 * TOL : centre + windows.length * st.step,
        range,
      });
    }
    const x = Float64Array.from(xs);
    const quality = Uint8Array.from(qs);
    method.fields.forEach((field, f) => {
      const metric = `${method.id}.${field}`;
      const tags = { a: A.series.id, b: B.series.id, window: windowTag, step: stepTag };
      const id = seriesIdOf(metric, tags);
      series.push({ id, label: `${field}(${A.series.label}, ${B.series.label})`, metric, tags, x, y: Float64Array.from(ys[f]), quality });
      for (const s of spans) {
        episodes.push({
          seriesId: id,
          episodeId: `${id}@${s.start}`,
          start: s.start,
          end: s.end,
          parent: s.range.a.episodeId,
          labels: {
            'pair.a': s.range.a.episodeId,
            'pair.b': s.range.b.episodeId,
            'pair.method': method.id,
            'pair.window': windowTag,
            'pair.step': stepTag,
            ...Object.fromEntries(Object.entries(own).map(([k, v]) => [`pair.${k}`, v])),
          },
        });
      }
    });
  }

  const pairs = series.length / method.fields.length;
  return {
    name: `${method.prefix}${first.name}`,
    xKind: 'time',
    xLabel: 't [s]',
    yLabel: method.yLabel,
    t0Ms: first.t0Ms,
    series,
    marks: first.marks,
    episodes: episodes.sort((p, q) => (p.seriesId === q.seriesId ? p.start - q.start : p.seriesId < q.seriesId ? -1 : 1)),
    note:
      `${method.id}${Object.entries(own).map(([k, v]) => `, ${k} ${v}`).join('')} over ${pairs} pair(s), pairing ${st.pairing}, window ${windowTag}, step ${stepTag}, clock ${[...clocks].map((c) => Number(c.toPrecision(6))).join(', ')} s\n` +
      `${rangesUsed} pair range(s), ${items} item(s) per field, ${low} below the minimum coverage ${st.minCoverage}` +
      (rangesShort > 0 ? `; ${rangesShort} pair range(s) shorter than the window` : ''),
  };
}

/** Coverage of the windows themselves: shows what the other methods will see. */
export const PAIR_COVERAGE: PairMethod = {
  id: 'pair.coverage',
  prefix: 'Coverage_',
  fields: ['coverage', 'ticks'],
  yLabel: 'coverage / ticks',
  compute: (w) => [w.coverage, w.a.length],
};
