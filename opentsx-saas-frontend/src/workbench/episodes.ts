/**
 * Episode ranges of a bucket (`Bucket.episodes`): the [start, end) of every loaded episode in the x unit of the
 * bucket. TASK-015 pairs the episodes of two series by these ranges; this file only reads them.
 */

import { Bucket, EpisodeRange } from './types';

/** The episodes of one series, sorted by start; empty when the bucket carries none for it. */
export function episodesOf(b: Bucket, seriesId: string): EpisodeRange[] {
  return (b.episodes ?? []).filter((e) => e.seriesId === seriesId);
}

function seconds(s: number): string {
  if (s >= 3600 && s % 3600 === 0) return `${s / 3600} h`;
  if (s >= 60 && s % 60 === 0) return `${s / 60} min`;
  return `${Number(s.toPrecision(6))} s`;
}

/** One line for the properties tab, e.g. "24 in 2 series, 10 min each"; null when the bucket carries none. */
export function episodeSummary(b: Bucket): string | null {
  const list = b.episodes ?? [];
  if (list.length === 0) return null;
  const series = new Set(list.map((e) => e.seriesId)).size;
  const lengths = list.map((e) => e.end - e.start);
  const min = Math.min(...lengths);
  const max = Math.max(...lengths);
  const length = Math.abs(max - min) < 1e-6 ? `${seconds(min)} each` : `${seconds(min)} to ${seconds(max)}`;
  return `${list.length} in ${series} series, ${length}`;
}
