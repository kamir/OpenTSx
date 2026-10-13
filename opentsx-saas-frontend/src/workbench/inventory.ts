/**
 * TS inventory: the series a retrieval server holds (GET /v1/inventory, schema opentsx.ts-inventory.v1) and the
 * selection logic of the inventory dialog. No React in here.
 *
 * The inventory is derived from the episodes of a domain, for example the topic tsx.wind.episodes.v2 on KafScale.
 * The labels of the episodes carry the scenario metadata; the ground truth of a scenario (operations, sensor
 * faults) arrives as `marks`, time ranges in steps of one episode.
 */

import { Bucket, Mark } from './types';

export const INVENTORY_SCHEMA = 'opentsx.ts-inventory.v1';

export interface InventoryMark {
  /** 'operation' | 'sensor_fault' */
  kind: string;
  type: string;
  from: string;
  to: string;
}

export interface InventorySeries {
  seriesId: string;
  metric: string;
  tags: Record<string, string>;
  unit?: string;
  episodes: number;
  segments?: number;
  samples: number;
  from?: string;
  to?: string;
  intervalMicros?: number;
  min?: number;
  max?: number;
  mean?: number;
  missing: number;
  labels: Record<string, string[]>;
  marks: InventoryMark[];
}

/** One entry of the channel catalog (opentsx-windpark channel-catalog.yaml), as far as the dialog uses it. */
export interface InventoryChannel {
  id?: string;
  metric: string;
  description?: string;
  component?: string;
  unit?: string;
  min?: number;
  max?: number;
  iec?: { logicalNode?: string; dataObject?: string };
}

export interface Inventory {
  schema: string;
  generatedAt: string;
  source: {
    kind: string;
    domain?: string;
    topics: Record<string, string>;
    truncated?: boolean;
    /** True when the server runs vector DAGs (POST /v1/dags/run). */
    dagRuns?: boolean;
    /** Vector topics of the domain whose components are part of `series`. */
    vectorTopics?: string[];
    /** Vector topics the broker did not hand out at the last try; their series may be out of date. */
    unreadableVectorTopics?: string[];
  };
  totals: { series: number; episodes: number; samples: number; from?: string; to?: string; scenarios: string[] };
  /** The scenario file the server was started with, unchanged. */
  scenario?: Record<string, unknown>;
  channels: InventoryChannel[];
  series: InventorySeries[];
}

/**
 * The topic a selection was read from: the vector topic of its series, otherwise the episodes topic of the source.
 * Undefined when the selection spans several topics or the source is no log.
 */
export function topicOf(inv: Inventory, series: InventorySeries[]): string | undefined {
  const topics = new Set(series.map((s) => s.labels.smartTopic?.[0] ?? inv.source.topics.episodes));
  return topics.size === 1 ? [...topics][0] : undefined;
}

/** Checks the answer of GET /v1/inventory. */
export function parseInventory(json: unknown): Inventory {
  const inv = json as Partial<Inventory> | null;
  if (!inv || inv.schema !== INVENTORY_SCHEMA || !Array.isArray(inv.series) || !inv.source || !inv.totals) {
    throw new Error(`The answer is no ${INVENTORY_SCHEMA} document. The retrieval server may be older than the workbench.`);
  }
  return {
    ...(inv as Inventory),
    channels: Array.isArray(inv.channels) ? inv.channels : [],
    series: inv.series.map((s) => ({ ...s, tags: s.tags ?? {}, labels: s.labels ?? {}, marks: s.marks ?? [] })),
  };
}

const natural = new Intl.Collator('en', { numeric: true });

/** Tag keys with their values, the facets of the left column. */
export function tagFacets(inv: Inventory): { key: string; values: string[] }[] {
  const byKey = new Map<string, Set<string>>();
  for (const s of inv.series) {
    for (const [k, v] of Object.entries(s.tags)) byKey.set(k, (byKey.get(k) ?? new Set()).add(v));
  }
  return [...byKey]
    .map(([key, values]) => ({ key, values: [...values].sort(natural.compare) }))
    .sort((a, b) => a.values.length - b.values.length || natural.compare(a.key, b.key));
}

export interface MetricFacet {
  metric: string;
  /** IEC 61400-25 logical node of the channel, or the metric prefix when the catalog has no entry. */
  group: string;
  description?: string;
  unit?: string;
  series: number;
}

/** The metrics of the inventory with their catalog entry, grouped the way the catalog groups them. */
export function metricFacets(inv: Inventory): MetricFacet[] {
  const channels = new Map(inv.channels.map((c) => [c.metric, c]));
  const count = new Map<string, InventorySeries[]>();
  for (const s of inv.series) count.set(s.metric, [...(count.get(s.metric) ?? []), s]);
  return [...count]
    .map(([metric, series]) => {
      const c = channels.get(metric);
      return {
        metric,
        group: c?.iec?.logicalNode ?? (metric.split('.').slice(0, -1).join('.') || metric),
        description: c?.description,
        unit: c?.unit ?? series[0].unit,
        series: series.length,
      };
    })
    .sort((a, b) => natural.compare(a.group, b.group) || natural.compare(a.metric, b.metric));
}

export interface ScenarioEvent {
  id: string;
  kind: string;
  type: string;
  from: string;
  to: string;
  seriesIds: string[];
  /** Short text of what is affected, for example "T05 wind.met.wind_speed" or "10 turbines". */
  scope: string;
}

/**
 * The ground truth of the inventory as events: marks with the same kind, type and time range are one event.
 * An operation marks every channel of its turbines; a sensor fault marks one channel.
 */
export function scenarioEvents(inv: Inventory): ScenarioEvent[] {
  const groups = new Map<string, { mark: InventoryMark; series: InventorySeries[] }>();
  for (const s of inv.series) {
    for (const m of s.marks) {
      const id = `${m.kind}|${m.type}|${m.from}|${m.to}`;
      const g = groups.get(id) ?? { mark: m, series: [] };
      g.series.push(s);
      groups.set(id, g);
    }
  }
  const facets = tagFacets(inv);
  const unitKey = facets[facets.length - 1]?.key;
  return [...groups]
    .map(([id, g]) => {
      const units = [...new Set(g.series.map((s) => (unitKey ? s.tags[unitKey] : undefined)).filter(Boolean))] as string[];
      const metrics = [...new Set(g.series.map((s) => s.metric))];
      const where = units.length === 0 ? '' : units.length <= 3 ? units.sort(natural.compare).join(', ') : `${units.length} x ${unitKey}`;
      const what = metrics.length === 1 ? metrics[0] : '';
      return {
        id,
        kind: g.mark.kind,
        type: g.mark.type,
        from: g.mark.from,
        to: g.mark.to,
        seriesIds: g.series.map((s) => s.seriesId),
        scope: [where, what].filter(Boolean).join(' '),
      };
    })
    .sort((a, b) => a.from.localeCompare(b.from) || a.kind.localeCompare(b.kind) || a.type.localeCompare(b.type));
}

export interface InventoryFilter {
  text: string;
  tags: Record<string, string>;
  metric: string | null;
  eventId: string | null;
}

export const NO_FILTER: InventoryFilter = { text: '', tags: {}, metric: null, eventId: null };

export function filterSeries(inv: Inventory, f: InventoryFilter, events: ScenarioEvent[]): InventorySeries[] {
  const inEvent = f.eventId ? new Set(events.find((e) => e.id === f.eventId)?.seriesIds ?? []) : null;
  const words = f.text.toLowerCase().split(/\s+/).filter(Boolean);
  return inv.series.filter((s) => {
    if (f.metric && s.metric !== f.metric) return false;
    if (inEvent && !inEvent.has(s.seriesId)) return false;
    for (const [k, v] of Object.entries(f.tags)) if (s.tags[k] !== v) return false;
    if (words.length === 0) return true;
    const hay = `${s.metric} ${Object.values(s.tags).join(' ')} ${s.unit ?? ''} ${s.seriesId} ${s.marks.map((m) => m.type).join(' ')}`.toLowerCase();
    return words.every((w) => hay.includes(w));
  });
}

export interface LoadOptions {
  from: string;
  to: string;
  /** ISO-8601 duration, empty for the stored resolution. */
  resample: string;
  agg: string;
}

export const RESAMPLE_STEPS = ['', 'PT10S', 'PT1M', 'PT10M', 'PT1H'];
export const AGGREGATIONS = ['MEAN', 'MIN', 'MAX', 'FIRST', 'LAST', 'SUM', 'COUNT'];

/** Seconds of the plain durations the dialog offers (PT10S, PT1M, PT1H). */
export function durationSeconds(iso: string): number {
  const m = /^PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?$/.exec(iso);
  if (!m || (!m[1] && !m[2] && !m[3])) return NaN;
  return Number(m[1] ?? 0) * 3600 + Number(m[2] ?? 0) * 60 + Number(m[3] ?? 0);
}

/** Checks the window; returns the reason it cannot be used, or null. */
export function windowError(o: LoadOptions): string | null {
  const from = Date.parse(o.from);
  const to = Date.parse(o.to);
  if (Number.isNaN(from) || Number.isNaN(to)) return 'From and to must be timestamps like 2026-10-01T00:00:00Z.';
  if (from >= to) return 'From must be before to.';
  return null;
}

/** Query string of GET /v1/episodes for the selection. */
export function episodesQuery(seriesIds: string[], o: LoadOptions): string {
  const q = new URLSearchParams({ series: seriesIds.join(','), from: new Date(o.from).toISOString(), to: new Date(o.to).toISOString(), clip: 'true' });
  if (o.resample) {
    q.set('resample', o.resample);
    q.set('agg', o.agg);
  }
  return q.toString();
}

/** Number of points the selection will bring into the browser, from the sample interval of each series. */
export function estimatePoints(series: InventorySeries[], o: LoadOptions): number {
  const seconds = (Date.parse(o.to) - Date.parse(o.from)) / 1000;
  if (!(seconds > 0)) return 0;
  const step = o.resample ? durationSeconds(o.resample) : NaN;
  let n = 0;
  for (const s of series) {
    const interval = (s.intervalMicros ?? 0) / 1e6;
    const own = interval > 0 ? seconds / interval : Math.min(s.samples, seconds);
    n += Number.isNaN(step) ? own : Math.min(own, seconds / step);
  }
  return Math.round(n);
}

/** Legend labels of a selection: only what tells its series apart. */
export function shortLabels(series: InventorySeries[]): Map<string, string> {
  const sameMetric = new Set(series.map((s) => s.metric)).size === 1;
  const keys = [...new Set(series.flatMap((s) => Object.keys(s.tags)))];
  const varying = keys.filter((k) => new Set(series.map((s) => s.tags[k])).size > 1).sort(natural.compare);
  const out = new Map<string, string>();
  for (const s of series) {
    const tags = varying.map((k) => s.tags[k]).filter(Boolean).join(' ');
    const metric = s.metric.split('.').pop() ?? s.metric;
    out.set(s.seriesId, series.length === 1 ? `${metric} ${Object.values(s.tags).pop() ?? ''}`.trim() : sameMetric ? tags || metric : `${metric} ${tags}`.trim());
  }
  return out;
}

/** Name of the bucket a selection becomes, usable as node label and in topic names. */
export function bucketName(series: InventorySeries[]): string {
  const metrics = [...new Set(series.map((s) => s.metric.split('.').pop() ?? s.metric))];
  const head = metrics.length === 1 ? metrics[0] : `${metrics.length}metrics`;
  const tail = series.length === 1 ? (Object.values(series[0].tags).pop() ?? '') : `${series.length}x`;
  return [head, tail].filter(Boolean).join('_').replace(/[^A-Za-z0-9_.-]/g, '_');
}

/** Applies the short labels to a bucket parsed from the episodes of the selection. */
export function relabel(bucket: Bucket, series: InventorySeries[], name: string): Bucket {
  const labels = shortLabels(series);
  return { ...bucket, name, series: bucket.series.map((s) => ({ ...s, label: labels.get(s.id) ?? s.label })) };
}

/**
 * The ground truth of a selection as marks on the time axis of its bucket: equal events of several series are one
 * mark, events outside the loaded window are left out, and the rest is cut to the window.
 *
 * @param t0Ms epoch milliseconds of x = 0 of the bucket
 * @param fromS, toS the loaded window in the x unit of the bucket (seconds)
 */
export function marksOf(series: InventorySeries[], t0Ms: number, fromS: number, toS: number): Mark[] {
  const labels = shortLabels(series);
  const groups = new Map<string, { mark: InventoryMark; ids: string[] }>();
  for (const s of series) {
    for (const m of s.marks) {
      const id = `${m.kind}|${m.type}|${m.from}|${m.to}`;
      const g = groups.get(id) ?? { mark: m, ids: [] };
      g.ids.push(s.seriesId);
      groups.set(id, g);
    }
  }
  const out: Mark[] = [];
  for (const g of groups.values()) {
    const from = Math.max(fromS, (Date.parse(g.mark.from) - t0Ms) / 1000);
    const to = Math.min(toS, (Date.parse(g.mark.to) - t0Ms) / 1000);
    if (!(to > from)) continue;
    const all = g.ids.length === series.length;
    const names = g.ids.map((id) => labels.get(id) ?? id);
    out.push({
      kind: g.mark.kind,
      type: g.mark.type,
      from,
      to,
      scope: all ? '' : names.length <= 3 ? names.join(', ') : `${names.length} series`,
    });
  }
  return out.sort((a, b) => a.from - b.from || a.kind.localeCompare(b.kind) || a.type.localeCompare(b.type));
}

/** HH:MM of a timestamp in UTC, with the date when the range spans more than one day. */
export function clock(iso: string | undefined, withDate = false): string {
  if (!iso) return '';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  const t = d.toISOString();
  return withDate ? `${t.slice(0, 10)} ${t.slice(11, 16)}` : t.slice(11, 16);
}
