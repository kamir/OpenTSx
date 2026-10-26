/**
 * pair.es: event synchronisation of A and B in each pair window (pairs.ts), after Quiroga, Kreuz and Grassberger
 * (Phys. Rev. E 66, 041904, 2002). A port of python-package/opentsx/algorithms/event_sync.py, which stays the
 * reference: both run the shared vectors docs/datamodel-2026/testvectors/pair-es.v1.json
 * (tools/pair_es_vectors.py).
 *
 * - Events: local maxima above mean + sigma * std of the window (detect_events, method "std"; population standard
 *   deviation; the first and the last tick are never events). Event times are tick indices.
 * - Every event pair gets its own window: half the smaller distance to a neighbouring event of either series,
 *   optionally capped by tauMax (_count_pairs). B within the window after A counts for c(B|A), A after B for c(A|B),
 *   both at the same tick half for each.
 * - q_total = (c(B|A) + c(A|B)) / sqrt(mA mB) in [0, 1]; q_ab = c(B|A) / sqrt(mA mB), A leads; q_ba likewise, B
 *   leads. q_ab > q_ba is the direction TASK-015 calls lag > 0: B follows A.
 * - q_rand, the baseline against chance (surrogate_sync): the mean q_total of A against `shuffles` copies of B whose
 *   intervals between events are shuffled, drawn by mulberry32(seed) in turn. Rates and intervals stay, the timing
 *   relative to A goes. Strictly periodic events keep their timing under any shuffle, so there q_rand equals
 *   q_total: such a pair cannot be told from chance.
 * - A window without events in A or B gives 0 for every field, as the Python code does.
 */

import type { PairMethod } from './pairs';
import { mulberry32 } from './random';
import { Params } from './types';

/** Tick indices of the local maxima above mean + sigma * std (population) of the finite values. */
export function detectEvents(v: ArrayLike<number>, sigma: number): number[] {
  let n = 0;
  let sum = 0;
  for (let i = 0; i < v.length; i++) {
    if (Number.isFinite(v[i])) {
      n++;
      sum += v[i];
    }
  }
  const mean = sum / n;
  let ss = 0;
  for (let i = 0; i < v.length; i++) if (Number.isFinite(v[i])) ss += (v[i] - mean) * (v[i] - mean);
  const threshold = mean + sigma * Math.sqrt(ss / n);
  const events: number[] = [];
  // a gap next to a value fails the comparison: no event at the edge of a gap
  for (let i = 1; i < v.length - 1; i++) {
    if (v[i] > threshold && v[i] > v[i - 1] && v[i] > v[i + 1]) events.push(i);
  }
  return events;
}

function sortedUnique(events: number[]): number[] {
  return [...new Set(events)].sort((p, q) => p - q);
}

/** Half the smaller distance to the neighbouring events; Infinity where there is none. */
function halfMinGap(e: number[]): number[] {
  return e.map((t, i) => 0.5 * Math.min(i > 0 ? t - e[i - 1] : Infinity, i < e.length - 1 ? e[i + 1] - t : Infinity));
}

export interface Sync {
  q: number;
  qab: number;
  qba: number;
}

/** Q, q_ab and q_ba of two event lists (calculate_synchronization); tauMax in the unit of the events, or null. */
export function eventSync(eventsA: number[], eventsB: number[], tauMax: number | null): Sync {
  const x = sortedUnique(eventsA);
  const y = sortedUnique(eventsB);
  if (x.length === 0 || y.length === 0) return { q: 0, qab: 0, qba: 0 };
  const hx = halfMinGap(x);
  const hy = halfMinGap(y);
  let cyx = 0;
  let cxy = 0;
  let next = 0;
  for (let i = 0; i < x.length; i++) {
    // the first event of B at or after this event of A (searchsorted, side left); only it and the one before can match
    while (next < y.length && y[next] < x[i]) next++;
    for (const j of [next - 1, next]) {
      if (j < 0 || j >= y.length) continue;
      let tau = Math.min(hx[i], hy[j]);
      if (tauMax !== null) tau = Math.min(tau, tauMax);
      const d = y[j] - x[i];
      if (d === 0) {
        cyx += 0.5;
        cxy += 0.5;
      } else if (d > 0 && d < tau) {
        cyx += 1;
      } else if (-d > 0 && -d < tau) {
        cxy += 1;
      }
    }
  }
  const norm = Math.sqrt(x.length * y.length);
  return { q: (cyx + cxy) / norm, qab: cyx / norm, qba: cxy / norm };
}

/** The events with their intervals in random order (Fisher-Yates from the last interval down); first and last stay. */
export function isiShuffle(events: number[], rand: () => number): number[] {
  if (events.length < 3) return [...events];
  const gaps = events.slice(1).map((t, i) => t - events[i]);
  for (let i = gaps.length - 1; i > 0; i--) {
    const j = Math.floor(rand() * (i + 1));
    const t = gaps[i];
    gaps[i] = gaps[j];
    gaps[j] = t;
  }
  const out = [events[0]];
  // the running sum of the gaps, added as numpy's cumsum adds them
  let acc = 0;
  for (const g of gaps) {
    acc += g;
    out.push(events[0] + acc);
  }
  return out;
}

/** q_rand: the mean Q of A against `shuffles` copies of B with shuffled intervals, drawn by mulberry32(seed). */
export function surrogateSync(eventsA: number[], eventsB: number[], shuffles: number, seed: number, tauMax: number | null): number {
  const y = sortedUnique(eventsB);
  const rand = mulberry32(seed);
  let total = 0;
  for (let s = 0; s < shuffles; s++) total += eventSync(eventsA, isiShuffle(y, rand), tauMax).q;
  return total / shuffles;
}

interface EsSettings {
  sigma: number;
  /** Cap of the local window in seconds; null without cap. */
  tauMax: number | null;
  shuffles: number;
  seed: number;
}

export function esSettings(p: Params): EsSettings {
  const sigma = Number(p.sigma ?? 2);
  if (!Number.isFinite(sigma)) throw new Error(`sigma "${p.sigma}" is not a number.`);
  const cap = Number(p.tauMax ?? 0);
  if (!(cap >= 0)) throw new Error(`tauMax "${p.tauMax}" is not a duration in seconds, 0 or more.`);
  const shuffles = Number(p.shuffles ?? 10);
  if (!Number.isInteger(shuffles) || shuffles < 1) throw new Error(`shuffles "${p.shuffles}" is not a whole number of 1 or more.`);
  const seed = Number(p.seed ?? 1);
  if (!Number.isInteger(seed)) throw new Error(`seed "${p.seed}" is not a whole number.`);
  return { sigma, tauMax: cap > 0 ? cap : null, shuffles, seed };
}

export const PAIR_ES: PairMethod = {
  id: 'pair.es',
  prefix: 'ES_',
  fields: ['q_total', 'q_ab', 'q_ba', 'q_rand'],
  yLabel: 'Q / q / Q_rand',
  settings: (p) => {
    const s = esSettings(p);
    return {
      sigma: String(s.sigma),
      tauMax: s.tauMax === null ? 'none' : `PT${s.tauMax}S`,
      shuffles: String(s.shuffles),
      seed: String(s.seed),
    };
  },
  compute: (w, p) => {
    const s = esSettings(p);
    const a = detectEvents(w.a, s.sigma);
    const b = detectEvents(w.b, s.sigma);
    const tau = s.tauMax === null ? null : s.tauMax / w.every;
    const sync = eventSync(a, b, tau);
    return [sync.q, sync.qab, sync.qba, surrogateSync(a, b, s.shuffles, s.seed, tau)];
  },
};
