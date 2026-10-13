import { describe, expect, it } from 'vitest';
import {
  Inventory,
  InventorySeries,
  NO_FILTER,
  bucketName,
  clock,
  durationSeconds,
  episodesQuery,
  estimatePoints,
  filterSeries,
  marksOf,
  metricFacets,
  parseInventory,
  relabel,
  scenarioEvents,
  shortLabels,
  tagFacets,
  topicOf,
  windowError,
} from './inventory';
import { parseEpisodes } from './loaders';

const FROM = '2026-10-01T00:00:00Z';
const TO = '2026-10-01T06:00:00Z';
const CURTAILMENT = { kind: 'operation', type: 'CURTAILMENT', from: '2026-10-01T01:00:00Z', to: '2026-10-01T01:40:00Z' };
const STUCK = { kind: 'sensor_fault', type: 'STUCK', from: '2026-10-01T01:30:00Z', to: '2026-10-01T01:50:00Z' };

function series(metric: string, turbine: string, unit: string, marks = [CURTAILMENT]): InventorySeries {
  return {
    seriesId: `${metric}-${turbine}`,
    metric,
    tags: { park: 'ost-01', turbine },
    unit,
    episodes: 36,
    samples: 21600,
    from: FROM,
    to: TO,
    intervalMicros: 1_000_000,
    min: 0,
    max: 25,
    mean: 9,
    missing: 0,
    labels: { scenario: ['ostsee-demo'] },
    marks,
  };
}

/** Three turbines with two channels each, the shape of the wind-farm demo. */
function inventory(): Inventory {
  const list: InventorySeries[] = [];
  for (const t of ['T10', 'T02', 'T05']) {
    list.push(series('wind.met.wind_speed', t, 'm/s', t === 'T05' ? [CURTAILMENT, STUCK] : [CURTAILMENT]));
    list.push(series('wind.turbine.power_active', t, 'kW'));
  }
  return parseInventory({
    schema: 'opentsx.ts-inventory.v1',
    generatedAt: TO,
    source: { kind: 'kafscale', domain: 'wind', topics: { episodes: 'tsx.wind.episodes.v2' } },
    totals: { series: list.length, episodes: 216, samples: 129600, from: FROM, to: TO, scenarios: ['ostsee-demo'] },
    channels: [
      { metric: 'wind.met.wind_speed', description: 'Horizontal wind speed', unit: 'm/s', iec: { logicalNode: 'WMET' } },
    ],
    series: list,
  });
}

describe('TS inventory', () => {
  it('rejects an answer that is no inventory', () => {
    expect(() => parseInventory({ status: 'ok' })).toThrow(/opentsx.ts-inventory.v1/);
    expect(() => parseInventory(null)).toThrow();
  });

  it('builds tag facets in natural order, the key with the most values last', () => {
    expect(tagFacets(inventory())).toEqual([
      { key: 'park', values: ['ost-01'] },
      { key: 'turbine', values: ['T02', 'T05', 'T10'] },
    ]);
  });

  it('groups metrics by the logical node of the catalog and falls back to the metric prefix', () => {
    expect(metricFacets(inventory())).toEqual([
      { metric: 'wind.turbine.power_active', group: 'wind.turbine', description: undefined, unit: 'kW', series: 3 },
      { metric: 'wind.met.wind_speed', group: 'WMET', description: 'Horizontal wind speed', unit: 'm/s', series: 3 },
    ]);
  });

  it('joins equal marks into events and names what they affect', () => {
    const events = scenarioEvents(inventory());
    expect(events.map((e) => `${e.type}:${e.seriesIds.length}:${e.scope}`)).toEqual([
      'CURTAILMENT:6:T02, T05, T10',
      'STUCK:1:T05 wind.met.wind_speed',
    ]);
  });

  it('filters by tag, metric, event and text', () => {
    const inv = inventory();
    const events = scenarioEvents(inv);
    const ids = (f: Partial<typeof NO_FILTER>) => filterSeries(inv, { ...NO_FILTER, ...f }, events).map((s) => s.seriesId);
    expect(ids({})).toHaveLength(6);
    expect(ids({ tags: { turbine: 'T05' } })).toEqual(['wind.met.wind_speed-T05', 'wind.turbine.power_active-T05']);
    expect(ids({ metric: 'wind.turbine.power_active', tags: { turbine: 'T10' } })).toEqual(['wind.turbine.power_active-T10']);
    expect(ids({ eventId: events[1].id })).toEqual(['wind.met.wind_speed-T05']);
    expect(ids({ text: 'stuck' })).toEqual(['wind.met.wind_speed-T05']);
    expect(ids({ text: 'power kw t02' })).toEqual(['wind.turbine.power_active-T02']);
    expect(ids({ text: 'nothing-like-this' })).toEqual([]);
  });

  it('writes the episodes query of a selection', () => {
    const q = new URLSearchParams(episodesQuery(['a', 'b'], { from: FROM, to: '2026-10-01T01:00:00Z', resample: '', agg: 'MEAN' }));
    expect(Object.fromEntries(q)).toEqual({ series: 'a,b', from: '2026-10-01T00:00:00.000Z', to: '2026-10-01T01:00:00.000Z', clip: 'true' });
    const r = new URLSearchParams(episodesQuery(['a'], { from: FROM, to: TO, resample: 'PT1M', agg: 'MAX' }));
    expect(r.get('resample')).toBe('PT1M');
    expect(r.get('agg')).toBe('MAX');
  });

  it('checks the window', () => {
    expect(windowError({ from: FROM, to: TO, resample: '', agg: 'MEAN' })).toBeNull();
    expect(windowError({ from: TO, to: FROM, resample: '', agg: 'MEAN' })).toMatch(/before/);
    expect(windowError({ from: 'yesterday', to: TO, resample: '', agg: 'MEAN' })).toMatch(/timestamps/);
  });

  it('estimates the points of a load', () => {
    const two = inventory().series.slice(0, 2);
    expect(estimatePoints(two, { from: FROM, to: TO, resample: '', agg: 'MEAN' })).toBe(43200);
    expect(estimatePoints(two, { from: FROM, to: TO, resample: 'PT1M', agg: 'MEAN' })).toBe(720);
    expect(estimatePoints(two, { from: TO, to: FROM, resample: '', agg: 'MEAN' })).toBe(0);
    expect(durationSeconds('PT1H')).toBe(3600);
    expect(durationSeconds('PT10S')).toBe(10);
    expect(durationSeconds('P1D')).toBeNaN();
  });

  it('labels a selection by what tells its series apart', () => {
    const inv = inventory();
    const speed = inv.series.filter((s) => s.metric === 'wind.met.wind_speed');
    expect([...shortLabels(speed).values()]).toEqual(['T10', 'T02', 'T05']);
    expect(bucketName(speed)).toBe('wind_speed_3x');
    const t05 = inv.series.filter((s) => s.tags.turbine === 'T05');
    expect([...shortLabels(t05).values()]).toEqual(['wind_speed', 'power_active']);
    expect(bucketName(t05)).toBe('2metrics_2x');
    expect([...shortLabels([speed[2]]).values()]).toEqual(['wind_speed T05']);
    expect(bucketName([speed[2]])).toBe('wind_speed_T05');
    expect([...shortLabels(inv.series).values()][0]).toBe('wind_speed T10');
  });

  it('relabels the bucket parsed from the episodes of the selection', () => {
    const picked = inventory().series.filter((s) => s.metric === 'wind.met.wind_speed').slice(0, 2);
    const episodes = picked.map((s, i) => ({
      seriesId: s.seriesId,
      metric: s.metric,
      tags: s.tags,
      unit: s.unit,
      timestampsMicros: [1_790_812_800_000_000, 1_790_812_801_000_000],
      values: [i, i + 1],
    }));
    const bucket = relabel(parseEpisodes(episodes, 'x'), picked, bucketName(picked));
    expect(bucket.name).toBe('wind_speed_2x');
    expect(bucket.series.map((s) => s.label)).toEqual(['T10', 'T02']);
    expect(bucket.yLabel).toBe('[m/s]');
  });

  it('turns the ground truth of a selection into marks on the time axis of its bucket', () => {
    const inv = inventory();
    const t0 = Date.parse(FROM);
    const speed = inv.series.filter((s) => s.metric === 'wind.met.wind_speed');     // T10, T02, T05; T05 is also stuck
    expect(marksOf(speed, t0, 0, 6 * 3600)).toEqual([
      { kind: 'operation', type: 'CURTAILMENT', from: 3600, to: 6000, scope: '' },
      { kind: 'sensor_fault', type: 'STUCK', from: 5400, to: 6600, scope: 'T05' },
    ]);
    // a bucket that starts later counts from its own origin, and events are cut to the loaded window
    const later = Date.parse('2026-10-01T01:30:00Z');
    expect(marksOf(speed, later, 0, 600)).toEqual([
      { kind: 'operation', type: 'CURTAILMENT', from: 0, to: 600, scope: '' },
      { kind: 'sensor_fault', type: 'STUCK', from: 0, to: 600, scope: 'T05' },
    ]);
    expect(marksOf(speed, Date.parse('2026-10-01T03:00:00Z'), 0, 600)).toEqual([]);
    // of two channels of one turbine only the anemometer is stuck
    const t05 = inv.series.filter((s) => s.tags.turbine === 'T05');
    expect(marksOf(t05, t0, 0, 6 * 3600).map((m) => `${m.type}:${m.scope}`)).toEqual(['CURTAILMENT:', 'STUCK:wind_speed']);
  });

  it('names the topic a selection comes from', () => {
    const inv = inventory();
    expect(topicOf(inv, inv.series.slice(0, 2))).toBe('tsx.wind.episodes.v2');
    const vector = { ...inv.series[0], seriesId: 'v', labels: { smartTopic: ['tsx.wind.smart.state.vectors.v1'] } };
    expect(topicOf(inv, [vector])).toBe('tsx.wind.smart.state.vectors.v1');
    expect(topicOf(inv, [vector, inv.series[0]])).toBeUndefined();
  });

  it('formats clock times in UTC', () => {
    expect(clock('2026-10-01T01:30:00Z')).toBe('01:30');
    expect(clock('2026-10-01T01:30:00Z', true)).toBe('2026-10-01 01:30');
    expect(clock(undefined)).toBe('');
  });
});
