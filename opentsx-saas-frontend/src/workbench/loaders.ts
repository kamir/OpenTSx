/**
 * Bucket loaders: generator, delimited text files and episodes as JSON.
 */

import { mulberry32 } from './ops';
import { Bucket, EpisodeRange, Series } from './types';

/**
 * The sine mix of MacroRecorder (track EXP2): six sine components plus their sum,
 * 5 s at 1000 Hz. The noise is seeded, so every load gives the same bucket.
 */
export function generateSineMix(seed = 7): Bucket {
  const f = [2, 2, 2, 6, 12, 24];
  const a = [5, 3, 8, 2, 1, 5];
  const phase = [0.0, 0.5, 0.25, 0.0, 0.0, 0.0];
  const noise = [1.0, 0.5, 20.0, 0.0, 0.0, 0.0];
  const rate = 1000;
  const n = 5 * rate;
  const rnd = mulberry32(seed);
  const gauss = () => {
    // Box-Muller
    const u = Math.max(rnd(), 1e-12);
    return Math.sqrt(-2 * Math.log(u)) * Math.cos(2 * Math.PI * rnd());
  };
  const x = new Float64Array(n);
  for (let i = 0; i < n; i++) x[i] = i / rate;
  const mix = new Float64Array(n);
  const series: Series[] = f.map((freq, k) => {
    const y = new Float64Array(n);
    for (let i = 0; i < n; i++) {
      y[i] = a[k] * Math.sin(2 * Math.PI * freq * x[i] + phase[k] * Math.PI) + noise[k] * gauss();
      mix[i] += y[i];
    }
    return { id: `sine-${k}`, label: `sin f=${freq}Hz a=${a[k]} noise=${noise[k]}`, x, y };
  });
  series.push({ id: 'mix', label: 'mix', x, y: mix });
  return { name: 'Components', xKind: 'time', xLabel: 't [s]', yLabel: 'y(t)', series };
}

function detectDelimiter(line: string): string {
  const counts: [string, number][] = ['\t', ';', ','].map((d) => [d, line.split(d).length - 1]);
  counts.sort((p, q) => q[1] - p[1]);
  return counts[0][1] > 0 ? counts[0][0] : ',';
}

function parseTime(cell: string): number {
  const n = Number(cell);
  if (cell.trim() !== '' && Number.isFinite(n)) return n;
  const ms = Date.parse(cell);
  return Number.isFinite(ms) ? ms : NaN;
}

/**
 * Reads a delimited text file. Two layouts are understood:
 *  wide: first column is the time (ISO timestamp or number), every further numeric column is a series;
 *  long: columns series_id, x, y.
 */
export function parseDelimited(text: string, name: string): Bucket {
  const lines = text.split(/\r?\n/).filter((l) => l.trim() !== '');
  if (lines.length < 2) throw new Error('The file has no data rows.');
  const d = detectDelimiter(lines[0]);
  const header = lines[0].split(d).map((h) => h.trim());
  const rows = lines.slice(1).map((l) => l.split(d));
  const lower = header.map((h) => h.toLowerCase());

  if (lower.includes('series_id') && lower.includes('x') && lower.includes('y')) {
    const ci = lower.indexOf('series_id');
    const xi = lower.indexOf('x');
    const yi = lower.indexOf('y');
    const groups = new Map<string, { x: number[]; y: number[] }>();
    for (const r of rows) {
      const g = groups.get(r[ci]) ?? { x: [], y: [] };
      g.x.push(Number(r[xi]));
      g.y.push(Number(r[yi]));
      groups.set(r[ci], g);
    }
    return {
      name,
      xKind: 'index',
      xLabel: 'x',
      yLabel: 'y',
      series: [...groups].map(([id, g]) => ({
        id,
        label: `series ${id}`,
        x: Float64Array.from(g.x),
        y: Float64Array.from(g.y),
      })),
    };
  }

  const raw = rows.map((r) => parseTime(r[0] ?? ''));
  if (raw.some((v) => !Number.isFinite(v))) {
    throw new Error(`Column "${header[0]}" must hold timestamps or numbers.`);
  }
  // ISO timestamps become seconds relative to the first row.
  const absolute = !Number.isFinite(Number(rows[0][0]));
  const t0Ms = absolute ? raw[0] : undefined;
  const x = Float64Array.from(raw, (v) => (absolute ? (v - raw[0]) / 1000 : v));
  const series: Series[] = [];
  for (let c = 1; c < header.length; c++) {
    const y = Float64Array.from(rows, (r) => (r[c] === undefined || r[c].trim() === '' ? NaN : Number(r[c])));
    if (y.every((v) => Number.isNaN(v))) continue;
    series.push({ id: header[c], label: header[c], x, y });
  }
  if (series.length === 0) throw new Error('No numeric column found.');
  return { name, xKind: absolute ? 'time' : 'index', xLabel: absolute ? 't [s]' : header[0], yLabel: 'y', series, t0Ms };
}

interface EpisodeJson {
  episodeId?: string;
  seriesId?: string;
  metric?: string;
  tags?: Record<string, string>;
  unit?: string;
  /** ISO-8601 instants with up to six fraction digits, as the retrieval server writes them; tEnd is exclusive. */
  tStart?: string;
  tEnd?: string;
  intervalMicros?: number;
  segmentation?: { strategy?: string };
  timestampsMicros?: number[];
  values?: (number | null)[];
}

/** Epoch microseconds of an ISO-8601 instant such as `2026-10-01T00:00:00.123456Z`, or NaN. */
export function isoMicros(iso: string): number {
  const m = /^(.*T\d{2}:\d{2}:\d{2})(?:\.(\d{1,9}))?(Z|[+-]\d{2}:\d{2})$/.exec(iso);
  if (!m) return NaN;
  const seconds = Date.parse(m[1] + m[3]);
  const fraction = Math.round(Number(`0.${m[2] ?? '0'}`) * 1e6);
  return Number.isFinite(seconds) ? seconds * 1000 + fraction : NaN;
}

/**
 * The [start, end) of an episode in epoch microseconds: tStart and tEnd when it carries them, otherwise as the v2
 * model defines them (end = last sample + interval for a regular episode, + 1 microsecond for an irregular one).
 */
function rangeMicros(e: EpisodeJson, t: number[]): [number, number] {
  const first = Math.min(...t);
  const last = Math.max(...t);
  const start = e.tStart ? isoMicros(e.tStart) : NaN;
  const end = e.tEnd ? isoMicros(e.tEnd) : NaN;
  return [Number.isFinite(start) ? start : first, Number.isFinite(end) ? end : last + (e.intervalMicros ?? 1)];
}

/**
 * Reads episodes in the JSON form of the retrieval server (GET /v1/episodes):
 * an array of episodes or an object with an `episodes` array. Episodes of one
 * series are joined in time order; their ranges are kept in `Bucket.episodes`.
 */
export function parseEpisodes(json: unknown, name: string): Bucket {
  const list: EpisodeJson[] = Array.isArray(json)
    ? json
    : ((json as { episodes?: EpisodeJson[] } | null)?.episodes ?? []);
  if (!Array.isArray(list) || list.length === 0) throw new Error('No episodes found in the JSON.');
  const groups = new Map<string, { e: EpisodeJson; t: number[]; v: number[] }>();
  const ranges: EpisodeRange[] = [];
  for (const e of list) {
    if (!e.timestampsMicros || !e.values) continue;
    const key = e.seriesId ?? e.metric ?? 'series';
    const g = groups.get(key) ?? { e, t: [], v: [] };
    for (let i = 0; i < e.values.length; i++) {
      g.t.push(e.timestampsMicros[i]);
      g.v.push(e.values[i] ?? NaN);
    }
    groups.set(key, g);
    if (e.timestampsMicros.length > 0) {
      const [start, end] = rangeMicros(e, e.timestampsMicros);
      ranges.push({ seriesId: key, episodeId: e.episodeId ?? `${key}@${start}`, start, end, strategy: e.segmentation?.strategy });
    }
  }
  if (groups.size === 0) throw new Error('The episodes carry no timestampsMicros and values.');
  let t0 = Infinity;
  for (const g of groups.values()) for (const t of g.t) if (t < t0) t0 = t;
  for (const r of ranges) if (r.start < t0) t0 = r.start;
  const episodes: EpisodeRange[] = ranges
    .map((r) => ({ ...r, start: (r.start - t0) / 1e6, end: (r.end - t0) / 1e6 }))
    .sort((p, q) => (p.seriesId === q.seriesId ? p.start - q.start : p.seriesId < q.seriesId ? -1 : 1));
  const series: Series[] = [...groups].map(([id, g]) => {
    const order = g.t.map((_, i) => i).sort((p, q) => g.t[p] - g.t[q]);
    const tags = Object.entries(g.e.tags ?? {}).map(([k, v]) => `${k}=${v}`).join(',');
    return {
      id,
      label: g.e.metric ? `${g.e.metric}${tags ? `{${tags}}` : ''}` : id,
      unit: g.e.unit,
      metric: g.e.metric,
      tags: g.e.tags,
      x: Float64Array.from(order, (i) => (g.t[i] - t0) / 1e6),
      y: Float64Array.from(order, (i) => g.v[i]),
    };
  });
  const unit = series[0].unit;
  return { name, xKind: 'time', xLabel: 't [s]', yLabel: unit ? `[${unit}]` : 'y', series, t0Ms: t0 / 1000, episodes };
}

/** Picks the parser from the content: JSON for episodes, otherwise delimited text. */
export function parseBucketText(text: string, fileName: string): Bucket {
  const name = fileName.replace(/\.[^.]+$/, '').replace(/[^A-Za-z0-9_.-]/g, '_') || 'Bucket';
  const head = text.trimStart()[0];
  if (head === '{' || head === '[') return parseEpisodes(JSON.parse(text), name);
  return parseDelimited(text, name);
}

/** Long layout (series_id, x, y) of a bucket, the web form of "persist TSB". */
export function bucketToCsv(b: Bucket): string {
  const out = ['series_id,x,y'];
  for (const s of b.series) {
    const id = s.label.replace(/[",\n]/g, '_');
    for (let i = 0; i < s.y.length; i++) out.push(`${id},${s.x[i]},${s.y[i]}`);
  }
  return out.join('\n') + '\n';
}
