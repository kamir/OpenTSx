/**
 * pair.xcorr: the lagged cross-correlation of A and B in each pair window (pairs.ts), its peak and the link strength.
 *
 * Port of kamir/WikiExplorer.NG at 7b56e3d (Apache License 2.0), paths below src/main/java/experiments/:
 * - CC(k) as crosscorrelation/KreuzKorrelation.java computes it in calcKRCoeffizient (:254 to 337): both series minus
 *   their mean, B shifted by k and filled with 0 (shiftAndFillUpWithZero, :250 and :286), the products summed and
 *   divided by (N - 1) sA sB, with N the window length and sA, sB the sample standard deviations. CC(0) is the
 *   Pearson correlation.
 * - The function over k = -lags..lags as calcKR builds it (:168 to 237, the range set at :620).
 * - One item per window, stamped at the window centre as calcKR_SLIDINGWINDOW does (:455 to 512, the stamp at :490).
 *   The windows themselves come from the pair framework.
 * - strength as linkstrength/CCFunction.java computes it in calcStrength_VERSION_A (:76 to 97): the larger of the
 *   maximum and the absolute minimum of CC over the lags (calcAbsoluteMaxY, :109 to 127), minus the mean of CC,
 *   divided by the sample standard deviation of CC.
 *
 * Where the port differs, and why:
 * - Lag sign: CC(k) pairs A at t with B at t - k, so B following A by d peaks at k = -d. The field `lag` is -k times
 *   the clock: lag > 0 means B follows A, the convention of TASK-015.
 * - `max` and `lag` belong to the extreme that `strength` measures, with its sign. The old t_max (:230 to 233) only
 *   looks at values above 0, so for an anti-correlated pair it named a small positive side peak while the strength
 *   described the negative one.
 * - Gaps (the old code had none): a tick without a value counts as the mean of its series, 0 after centring, the
 *   value the old code shifts in; mean and standard deviation come from the ticks that have a value.
 * - calcKR_SLIDINGWINDOW stops one window early (`while (bis < size)`, :465); the pair framework computes every
 *   window that fits.
 *
 * Test vectors from the original code: docs/datamodel-2026/testvectors/pair-xcorr.v1.json, written by
 * tools/pair_xcorr_vectors.sh.
 */

import type { PairMethod } from './pairs';
import { Params } from './types';

/** Mean and sample standard deviation over the finite values; the standard deviation is NaN below two values. */
function moments(v: ArrayLike<number>): { mean: number; std: number } {
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
  return { mean, std: n > 1 ? Math.sqrt(ss / (n - 1)) : NaN };
}

/** CC(k) for k = -lags..lags, at index k + lags. NaN everywhere when A or B does not vary in the window. */
export function crossCorrelation(a: ArrayLike<number>, b: ArrayLike<number>, lags: number): Float64Array {
  if (a.length !== b.length) throw new Error(`A has ${a.length} values and B ${b.length}: a window needs both alike.`);
  const n = a.length;
  const out = new Float64Array(2 * lags + 1);
  const ma = moments(a);
  const mb = moments(b);
  if (!(ma.std > 0) || !(mb.std > 0)) return out.fill(NaN);
  const x = Float64Array.from({ length: n }, (_, t) => (Number.isFinite(a[t]) ? a[t] - ma.mean : 0));
  const y = Float64Array.from({ length: n }, (_, t) => (Number.isFinite(b[t]) ? b[t] - mb.mean : 0));
  const den = (n - 1) * ma.std * mb.std;
  for (let k = -lags; k <= lags; k++) {
    let sum = 0;
    for (let t = Math.max(0, k); t < Math.min(n, n + k); t++) sum += x[t] * y[t - k];
    out[k + lags] = sum / den;
  }
  return out;
}

export interface Peak {
  /** Index into the CC function: k + lags. */
  index: number;
  /** CC at the peak, with its sign. */
  value: number;
  /** (|peak| - mean) / standard deviation of the function; NaN without spread (a single lag). */
  strength: number;
}

/** The extreme of |CC| and the link strength of calcStrength_VERSION_A. */
export function peakOf(cc: ArrayLike<number>): Peak {
  let hi = 0;
  let lo = 0;
  for (let i = 1; i < cc.length; i++) {
    if (cc[i] > cc[hi]) hi = i;
    if (cc[i] < cc[lo]) lo = i;
  }
  // calcAbsoluteMaxY: the minimum only when its absolute value is larger
  const index = Math.abs(cc[hi]) < Math.abs(cc[lo]) ? lo : hi;
  let sum = 0;
  for (let i = 0; i < cc.length; i++) sum += cc[i];
  const { std } = moments(cc);
  return { index, value: cc[index], strength: (Math.abs(cc[index]) - sum / cc.length) / std };
}

/** The lag range in clock ticks. */
export function lagsOf(p: Params): number {
  const lags = Number(p.lags ?? 10);
  if (!Number.isInteger(lags) || lags < 0) throw new Error(`lags "${p.lags}" is not a whole number of clock ticks, 0 or more.`);
  return lags;
}

export const PAIR_XCORR: PairMethod = {
  id: 'pair.xcorr',
  prefix: 'XCorr_',
  fields: ['max', 'lag', 'strength'],
  yLabel: 'CC / lag [s] / strength',
  settings: (p) => ({ lags: String(lagsOf(p)) }),
  compute: (w, p) => {
    const lags = lagsOf(p);
    const peak = peakOf(crossCorrelation(w.a, w.b, lags));
    if (!Number.isFinite(peak.value)) return [NaN, NaN, NaN];
    // (lags - index), not -(index - lags): no negative zero at lag 0
    return [peak.value, (lags - peak.index) * w.every, peak.strength];
  },
};
