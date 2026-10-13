/**
 * Vector alignment: the results of several nodes on one clock, one measuring point per tick.
 *
 * The operator `align.vector` and the record `opentsx.vector.v1` are described in
 * docs/datamodel-2026/11-SMART-TOPIC-PROZESSOR-DEVOPS.md, section 5a. The rules, in short:
 *
 * - Clock: ticks are multiples of `every` seconds since the epoch. Tick k covers [k * every, (k + 1) * every).
 * - Component: one per input node, in the order of the inputs. Its value in a tick is the aggregate of the samples
 *   of that node in the tick. An input with several series per key gives one component per series, in name order.
 * - Key: what forms one vector per tick. `series`: the same series id in every input. `all`: everything in one
 *   vector. A tag name (`turbine`): the series with the same value of that tag.
 * - Missing: a component without a sample in the tick is missing, or (fill as-of) takes its last value when that is
 *   not older than `maxAge`.
 */

import { Bucket, Params, Series } from './types';

export const VECTOR_OP = 'align.vector';
export const VECTOR_SCHEMA = 'opentsx.vector.v1';
export const VECTOR_AGGREGATIONS = ['MEAN', 'LAST', 'FIRST', 'MIN', 'MAX', 'SUM', 'COUNT'];

/** Running aggregate of the samples of one component in one tick. */
interface Cell {
  n: number;
  sum: number;
  min: number;
  max: number;
  first: number;
  last: number;
}

function cellValue(c: Cell | undefined, agg: string): number {
  if (!c) return NaN;
  switch (agg) {
    case 'LAST':
      return c.last;
    case 'FIRST':
      return c.first;
    case 'MIN':
      return c.min;
    case 'MAX':
      return c.max;
    case 'SUM':
      return c.sum;
    case 'COUNT':
      return c.n;
    default:
      return c.sum / c.n;
  }
}

function snake(text: string): string {
  return text.toLowerCase().replace(/[^a-z0-9]+/g, '_').replace(/^_+|_+$/g, '');
}

/**
 * Component names from the bucket names of the inputs: what the names have in common at the end (the source they
 * all derive from) is dropped. `custom` (comma separated) replaces names from the left.
 */
export function componentNames(labels: string[], custom = ''): string[] {
  let common = labels.length > 1 ? labels[0] : '';
  for (const l of labels) {
    while (common && !l.endsWith(common)) common = common.slice(1);
  }
  const given = custom.split(',').map((s) => snake(s));
  const out: string[] = [];
  labels.forEach((l, i) => {
    const base = given[i] || snake(common ? l.slice(0, l.length - common.length) : l) || 'raw';
    let name = base;
    for (let k = 2; out.includes(name); k++) name = `${base}_${k}`;
    out.push(name);
  });
  return out;
}

/** Name of a series inside its input: the metric when the source carried one, otherwise the label. */
function seriesName(s: Series): string {
  return snake(s.metric ? (s.metric.split('.').pop() ?? s.metric) : s.label) || 'value';
}

/** The operator: `first` is the input of the node, `others` its further inputs. */
export function alignVector(first: Bucket, p: Params, others: Bucket[] = []): Bucket {
  const inputs = [first, ...others];
  const every = Number(p.every);
  if (!(every > 0)) throw new Error('The clock "every" must be greater than 0 seconds.');
  for (const b of inputs) {
    if (b.xKind !== 'time') throw new Error(`${b.name} has a ${b.xKind} axis. A vector needs inputs with a time axis.`);
  }
  const agg = String(p.agg ?? 'MEAN');
  const asOf = String(p.fill ?? 'none') === 'as-of';
  const maxAgeTicks = Math.floor((Number(p.maxAge) >= 0 ? Number(p.maxAge) : every) / every);
  const complete = String(p.emit ?? 'any') === 'complete';
  const keyBy = String(p.key ?? 'series').trim() || 'series';
  const names = componentNames(inputs.map((b) => b.name), String(p.names ?? ''));
  const originMs = first.t0Ms ?? 0;
  const originS = originMs / 1000;

  if (keyBy !== 'series' && keyBy !== 'all' && !inputs.some((b) => b.series.some((s) => s.tags?.[keyBy] !== undefined))) {
    const known = [...new Set(inputs.flatMap((b) => b.series.flatMap((s) => Object.keys(s.tags ?? {}))))];
    throw new Error(`No series carries the tag "${keyBy}". Use series, all${known.length ? `, or one of: ${known.join(', ')}` : ''}.`);
  }
  const keyOf = (s: Series): string => (keyBy === 'series' ? s.id : keyBy === 'all' ? '' : (s.tags?.[keyBy] ?? ''));

  // key -> component -> tick -> cell
  const components: string[] = [];
  const keys = new Map<string, { sample: Series; cells: Map<string, Map<number, Cell>>; units: Map<string, string | undefined> }>();
  inputs.forEach((b, c) => {
    const shift = ((b.t0Ms ?? originMs) - originMs) / 1000 + originS;
    // an input with several series per key contributes one component per series, named after the series
    const perKey = new Map<string, number>();
    for (const s of b.series) perKey.set(keyOf(s), (perKey.get(keyOf(s)) ?? 0) + 1);
    const several = [...perKey.values()].some((n) => n > 1);
    const componentOf = (s: Series): string =>
      !several ? names[c] : inputs.length === 1 ? seriesName(s) : `${names[c]}.${seriesName(s)}`;
    // the components of one input in name order, so the vector does not depend on the order the series arrive in
    for (const name of [...new Set(b.series.map(componentOf))].sort()) {
      if (!components.includes(name)) components.push(name);
    }
    for (const s of b.series) {
      const key = keyOf(s);
      const component = componentOf(s);
      let k = keys.get(key);
      if (!k) {
        k = { sample: s, cells: new Map(), units: new Map() };
        keys.set(key, k);
      }
      let cells = k.cells.get(component);
      if (!cells) {
        cells = new Map<number, Cell>();
        k.cells.set(component, cells);
        k.units.set(component, s.unit);
      }
      for (let i = 0; i < s.x.length; i++) {
        const v = s.y[i];
        if (!Number.isFinite(v)) continue;
        const tick = Math.floor((s.x[i] + shift) / every);
        const cell = cells.get(tick);
        if (!cell) cells.set(tick, { n: 1, sum: v, min: v, max: v, first: v, last: v });
        else {
          cell.n++;
          cell.sum += v;
          if (v < cell.min) cell.min = v;
          if (v > cell.max) cell.max = v;
          cell.last = v;
        }
      }
    }
  });

  const single = keys.size === 1;
  const series: Series[] = [];
  let ticksOut = 0;
  let missing = 0;
  for (const [key, k] of [...keys].sort((a, b) => (a[0] < b[0] ? -1 : a[0] > b[0] ? 1 : 0))) {
    let lo = Infinity;
    let hi = -Infinity;
    for (const cells of k.cells.values()) {
      for (const t of cells.keys()) {
        if (t < lo) lo = t;
        if (t > hi) hi = t;
      }
    }
    if (hi < lo) continue;
    const n = hi - lo + 1;
    const columns = components.map((component) => {
      const cells = k.cells.get(component);
      const col = new Float64Array(n);
      let lastTick = -Infinity;
      let lastValue = NaN;
      for (let j = 0; j < n; j++) {
        const v = cellValue(cells?.get(lo + j), agg);
        if (Number.isFinite(v)) {
          lastTick = j;
          lastValue = v;
          col[j] = v;
        } else {
          col[j] = asOf && j - lastTick <= maxAgeTicks ? lastValue : NaN;
        }
      }
      return col;
    });
    const keep: number[] = [];
    for (let j = 0; j < n; j++) {
      let present = 0;
      for (const col of columns) if (Number.isFinite(col[j])) present++;
      if (complete ? present === columns.length : present > 0) {
        keep.push(j);
        missing += columns.length - present;
      }
    }
    ticksOut += keep.length;
    const x = Float64Array.from(keep, (j) => (lo + j) * every - originS);
    const keyLabel = keyBy === 'series' ? k.sample.label : key;
    columns.forEach((col, c) => {
      series.push({
        id: `${key}|${components[c]}`,
        label: single ? components[c] : `${components[c]} ${keyLabel}`,
        unit: k.units.get(components[c]),
        tags: keyBy === 'series' ? k.sample.tags : keyBy === 'all' ? undefined : { [keyBy]: key },
        key,
        component: components[c],
        x,
        y: Float64Array.from(keep, (j) => col[j]),
      });
    });
  }

  const units = new Set(series.map((s) => s.unit ?? ''));
  const unit = units.size === 1 ? [...units][0] : '';
  return {
    name: `Vector_${first.name}`,
    xKind: 'time',
    xLabel: 't [s]',
    yLabel: unit ? `[${unit}]` : 'components',
    t0Ms: first.t0Ms,
    series,
    vector: { clock: every, components, keyBy },
    note:
      `Vector of ${components.length} components on a clock of ${every} s: ${components.join(', ')}\n` +
      `${keys.size} key(s) by ${keyBy}, ${ticksOut} measuring points, ${missing} missing component values` +
      (asOf ? `, gaps filled as-of up to ${maxAgeTicks * every} s` : ''),
  };
}

export interface VectorRecord {
  schema: string;
  vector: string;
  key: Record<string, string>;
  /** End of the tick: the time from which the vector is complete. ISO-8601, or seconds when the data has no absolute time. */
  ts: string | number;
  window: { from: string | number; to: string | number };
  clock: string;
  components: string[];
  values: (number | null)[];
  missing: number;
}

/** The records a vector bucket stands for, as they would be written to the topic. `limit` caps the output. */
export function vectorRecords(bucket: Bucket, name: string, limit = Infinity): VectorRecord[] {
  if (!bucket.vector) return [];
  const { clock, components, keyBy } = bucket.vector;
  const byKey = new Map<string, Series[]>();
  for (const s of bucket.series) {
    if (s.key === undefined) continue;
    byKey.set(s.key, [...(byKey.get(s.key) ?? []), s]);
  }
  const time = (seconds: number): string | number =>
    bucket.t0Ms === undefined ? seconds : new Date(bucket.t0Ms + seconds * 1000).toISOString();
  const out: VectorRecord[] = [];
  for (const [key, list] of byKey) {
    const cols = components.map((c) => list.find((s) => s.component === c));
    const x = list[0].x;
    for (let j = 0; j < x.length && out.length < limit; j++) {
      const values = cols.map((s) => (s && Number.isFinite(s.y[j]) ? s.y[j] : null));
      out.push({
        schema: VECTOR_SCHEMA,
        vector: name,
        key: keyBy === 'series' ? { ...(list[0].tags ?? {}), series: key } : keyBy === 'all' ? {} : { [keyBy]: key },
        ts: time(x[j] + clock),
        window: { from: time(x[j]), to: time(x[j] + clock) },
        clock: `PT${clock}S`,
        components,
        values,
        missing: values.filter((v) => v === null).length,
      });
    }
  }
  return out;
}
