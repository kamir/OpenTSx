import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { beforeEach, describe, expect, it } from 'vitest';
import { SOURCE_ID, deployChecks, evaluate, parseNodes, runBlocker, toDagYaml, toDefinition, toJavaCode, toPfdJson, useFlow, validate, vectorComponents } from './flow';
import { OP_BY_ID, defaultParams } from './ops';
import { Bucket, Series } from './types';
import { VECTOR_OP, VECTOR_SCHEMA, alignVector, componentNames, suggestKey, vectorRecords } from './vector';

const T0 = Date.parse('2026-10-01T00:00:00Z');

function series(id: string, x: number[], y: number[], extra: Partial<Series> = {}): Series {
  return { id, label: id, x: Float64Array.from(x), y: Float64Array.from(y), ...extra };
}

function timeBucket(name: string, list: Series[], t0Ms: number | undefined = T0): Bucket {
  return { name, xKind: 'time', xLabel: 't [s]', yLabel: 'y', t0Ms, series: list };
}

function align(first: Bucket, others: Bucket[], params = {}) {
  return alignVector(first, { ...defaultParams(OP_BY_ID[VECTOR_OP]), ...params }, others);
}

describe('component names', () => {
  it('drops what the bucket names share at the end', () => {
    expect(componentNames(['wind_T05', 'Standardized_wind_T05', 'Stats_Standardized_wind_T05'])).toEqual([
      'raw',
      'standardized',
      'stats_standardized',
    ]);
  });
  it('keeps the name of a single input, makes names unique and takes custom names from the left', () => {
    expect(componentNames(['wind_T05'])).toEqual(['wind_t05']);
    expect(componentNames(['A_x', 'A_x'])).toEqual(['raw', 'raw_2']);
    expect(componentNames(['A_x', 'B_x', 'C_x'], 'Speed, ,level')).toEqual(['speed', 'b', 'level']);
  });
});

describe('align.vector', () => {
  it('aggregates every input into the ticks of one clock, aligned to the epoch', () => {
    // 1 Hz for two minutes, and a second input every 30 s
    const fast = timeBucket('fast_src', [series('s', Array.from({ length: 120 }, (_, i) => i), Array.from({ length: 120 }, (_, i) => i))]);
    const slow = timeBucket('slow_src', [series('s', [0, 30, 60, 90], [10, 20, 30, 40])]);
    const v = align(fast, [slow], { every: 60 });
    expect(v.vector).toEqual({ clock: 60, components: ['fast', 'slow'], keyBy: 'series' });
    expect(v.series.map((s) => s.label)).toEqual(['fast', 'slow']);
    expect([...v.series[0].x]).toEqual([0, 60]);
    expect([...v.series[0].y]).toEqual([29.5, 89.5]);
    expect([...v.series[1].y]).toEqual([15, 35]);
    expect(v.series[1].x).toBe(v.series[0].x);
    expect(v.note).toMatch(/2 components on a clock of 60 s: fast, slow/);
  });

  it('places ticks at multiples of the clock since the epoch, whatever the first sample is', () => {
    const t0 = T0 + 25_000;      // the bucket starts at 00:00:25
    const b = timeBucket('a', [series('s', [0, 10, 40, 100], [1, 2, 3, 4])], t0);
    const v = align(b, [], { every: 60, agg: 'LAST' });
    // absolute ticks 00:00, 00:01, 00:02 -> relative to t0: -25, 35, 95
    expect([...v.series[0].x]).toEqual([-25, 35, 95]);
    expect([...v.series[0].y]).toEqual([2, 3, 4]);
    const records = vectorRecords(v, 'vec');
    expect(records.map((r) => r.window.from)).toEqual(['2026-10-01T00:00:00.000Z', '2026-10-01T00:01:00.000Z', '2026-10-01T00:02:00.000Z']);
    expect(records[0].ts).toBe('2026-10-01T00:01:00.000Z');
  });

  it('aligns inputs with different time origins on absolute time', () => {
    const a = timeBucket('a_s', [series('s', [0, 60], [1, 2])], T0);
    const b = timeBucket('b_s', [series('s', [0], [9])], T0 + 60_000);
    const v = align(a, [b], { every: 60 });
    expect([...v.series[0].y]).toEqual([1, 2]);
    expect([...v.series[1].y]).toEqual([NaN, 9]);
  });

  it('supports the aggregations', () => {
    const b = timeBucket('a', [series('s', [0, 1, 2, 3], [4, 1, 7, NaN])]);
    const value = (agg: string) => align(b, [], { every: 10, agg }).series[0].y[0];
    expect(value('MEAN')).toBe(4);
    expect(value('MIN')).toBe(1);
    expect(value('MAX')).toBe(7);
    expect(value('FIRST')).toBe(4);
    expect(value('LAST')).toBe(7);
    expect(value('SUM')).toBe(12);
    expect(value('COUNT')).toBe(3);
  });

  it('leaves a missing component empty, fills it as-of up to the maximum age, or drops incomplete ticks', () => {
    const a = timeBucket('a_s', [series('s', [0, 60, 120, 180, 240], [1, 2, 3, 4, 5])]);
    const b = timeBucket('b_s', [series('s', [0, 240], [10, 50])]);
    expect([...align(a, [b], { every: 60 }).series[1].y]).toEqual([10, NaN, NaN, NaN, 50]);
    expect([...align(a, [b], { every: 60, fill: 'as-of', maxAge: 120 }).series[1].y]).toEqual([10, 10, 10, NaN, 50]);
    expect([...align(a, [b], { every: 60, fill: 'as-of', maxAge: 30 }).series[1].y]).toEqual([10, NaN, NaN, NaN, 50]);
    const complete = align(a, [b], { every: 60, emit: 'complete' });
    expect([...complete.series[0].x]).toEqual([0, 240]);
    expect([...complete.series[0].y]).toEqual([1, 5]);
    expect(complete.note).toMatch(/2 measuring points, 0 missing/);
  });

  it('forms one vector per series id and labels the components by key', () => {
    const tags = (t: string) => ({ tags: { park: 'ost-01', turbine: t } });
    const speed = timeBucket('speed_src', [series('t1', [0], [5], { label: 'T01', ...tags('T01') }), series('t2', [0], [7], { label: 'T02', ...tags('T02') })]);
    const power = timeBucket('power_src', [series('t2', [0], [900], { label: 'T02' }), series('t1', [0], [400], { label: 'T01' })]);
    const v = align(speed, [power], { every: 60 });
    expect(v.series.map((s) => `${s.key}:${s.component}:${s.label}:${s.y[0]}`)).toEqual([
      't1:speed:speed T01:5',
      't1:power:power T01:400',
      't2:speed:speed T02:7',
      't2:power:power T02:900',
    ]);
    const records = vectorRecords(v, 'park_vector');
    expect(records).toHaveLength(2);
    expect(records[0]).toEqual({
      schema: VECTOR_SCHEMA,
      vector: 'park_vector',
      key: { park: 'ost-01', turbine: 'T01', series: 't1' },
      ts: '2026-10-01T00:01:00.000Z',
      window: { from: '2026-10-01T00:00:00.000Z', to: '2026-10-01T00:01:00.000Z' },
      clock: 'PT60S',
      components: ['speed', 'power'],
      values: [5, 400],
      missing: 0,
    });
  });

  it('puts different series of one unit into one vector: by tag, or all together', () => {
    const s = (id: string, metric: string, turbine: string, y: number) =>
      series(id, [0], [y], { label: `${metric} ${turbine}`, metric: `wind.x.${metric}`, tags: { park: 'p', turbine } });
    const raw = timeBucket('2metrics_4x', [s('a', 'wind_speed', 'T01', 5), s('b', 'power_active', 'T01', 400), s('c', 'wind_speed', 'T02', 7), s('d', 'power_active', 'T02', 900)]);
    const byTurbine = align(raw, [], { every: 60, key: 'turbine' });
    expect(byTurbine.vector).toEqual({ clock: 60, components: ['power_active', 'wind_speed'], keyBy: 'turbine' });
    expect(vectorRecords(byTurbine, 'v').map((r) => [r.key, r.values])).toEqual([
      [{ turbine: 'T01' }, [400, 5]],
      [{ turbine: 'T02' }, [900, 7]],
    ]);
    expect(byTurbine.series.map((x) => x.label)).toEqual(['power_active T01', 'wind_speed T01', 'power_active T02', 'wind_speed T02']);

    // a second input adds its own components, prefixed with the name of the input
    const doubled: Bucket = { ...raw, name: 'Double_2metrics_4x', series: raw.series.map((x) => ({ ...x, y: x.y.map((v) => 2 * v) })) };
    const two = align(raw, [doubled], { every: 60, key: 'turbine' });
    expect(two.vector?.components).toEqual(['raw.power_active', 'raw.wind_speed', 'double.power_active', 'double.wind_speed']);
    expect(vectorRecords(two, 'v')[1].values).toEqual([900, 7, 1800, 14]);

    const t01 = timeBucket('t01', raw.series.slice(0, 2));
    const all = align(t01, [], { every: 60, key: 'all' });
    expect(vectorRecords(all, 'v')).toHaveLength(1);
    expect(vectorRecords(all, 'v')[0]).toMatchObject({ key: {}, components: ['power_active', 'wind_speed'], values: [400, 5] });
    expect(all.series.map((x) => x.label)).toEqual(['power_active', 'wind_speed']);

    expect(() => align(raw, [], { key: 'rotor' })).toThrow(/No series carries the tag "rotor". Use series, all, or one of: park, turbine/);
  });

  it('writes records with the fields of the JSON schema in docs/datamodel-2026/schemas', () => {
    // vitest runs in opentsx-saas-frontend; the schema lives with the design documents
    const schema = JSON.parse(readFileSync(resolve(process.cwd(), '../docs/datamodel-2026/schemas/opentsx.vector.v1.schema.json'), 'utf8'));
    const a = timeBucket('a_s', [series('s', [0, 60], [1, 2], { tags: { turbine: 'T01' } })]);
    const b = timeBucket('b_s', [series('s', [60], [9])]);
    for (const r of vectorRecords(align(a, [b], { every: 60 }), 'v')) {
      expect(Object.keys(r).sort()).toEqual([...schema.required].sort());
      expect(r.schema).toBe(schema.properties.schema.const);
      expect(r.clock).toMatch(new RegExp(schema.properties.clock.pattern));
      for (const c of r.components) expect(c).toMatch(new RegExp(schema.properties.components.items.pattern));
      expect(r.values).toHaveLength(r.components.length);
      expect(r.missing).toBe(r.values.filter((v) => v === null).length);
    }
  });

  it('writes null for a missing value and seconds when the data has no absolute time', () => {
    const a = { ...timeBucket('a_s', [series('s', [0, 60], [1, 2])]), t0Ms: undefined };
    const b = { ...timeBucket('b_s', [series('s', [60], [9])]), t0Ms: undefined };
    const records = vectorRecords(align(a, [b], { every: 60 }), 'v');
    expect(records.map((r) => [r.ts, r.values, r.missing])).toEqual([[60, [1, null], 1], [120, [2, 9], 0]]);
    expect(vectorRecords(align(a, [b], { every: 60 }), 'v', 1)).toHaveLength(1);
    expect(vectorRecords(a, 'v')).toEqual([]);
  });

  it('hands the ground truth of its first input on', () => {
    const marks = [{ kind: 'sensor_fault', type: 'STUCK', from: 60, to: 120, scope: '' }];
    const a = { ...timeBucket('a_s', [series('s', [0, 60], [1, 2])]), marks };
    expect(align(a, [timeBucket('b_s', [series('s', [0], [9])])], { every: 60 }).marks).toEqual(marks);
  });

  it('refuses inputs without a time axis and a clock of zero', () => {
    const a = timeBucket('a', [series('s', [0], [1])]);
    const spectrum: Bucket = { ...timeBucket('FFT_a', [series('s', [0], [1])]), xKind: 'frequency' };
    expect(() => align(a, [spectrum])).toThrow(/FFT_a has a frequency axis/);
    expect(() => align(a, [], { every: 0 })).toThrow(/greater than 0/);
  });
});

interface VectorCase {
  name: string;
  params: Record<string, string | number>;
  inputs: { name: string; series: { id: string; metric: string; tags: Record<string, string>; t: number[]; v: (number | null)[] }[] }[];
  expect: { key: Record<string, string>; from: number; components: string[]; values: (number | null)[] }[];
  also?: Record<string, number[]>;
}

/** The test vectors shared with the Java operator (opentsx-retrieval, VectorAlignerTest). */
describe('align.vector, shared test vectors', () => {
  const file = resolve(process.cwd(), '../docs/datamodel-2026/testvectors/align-vector.v1.json');
  const cases: VectorCase[] = JSON.parse(readFileSync(file, 'utf8')).cases;

  const run = (c: VectorCase, agg: string) => {
    const buckets = c.inputs.map((input) => ({
      ...timeBucket(input.name, input.series.map((x) => series(x.id, x.t, x.v.map((v) => v ?? NaN), { metric: x.metric, tags: x.tags })), 0),
    }));
    const params = { ...defaultParams(OP_BY_ID[VECTOR_OP]), ...c.params, agg, names: c.inputs.map((i) => i.name).join(',') };
    return vectorRecords(alignVector(buckets[0], params, buckets.slice(1)), 'v');
  };

  it('has cases', () => expect(cases.length).toBeGreaterThanOrEqual(6));

  for (const c of cases) {
    it(c.name, () => {
      const got = run(c, String(c.params.agg)).map((r) => ({
        key: r.key,
        from: Date.parse(String(r.window.from)) / 1000,
        components: r.components,
        values: r.values,
      }));
      expect(got).toEqual(c.expect);
      for (const [agg, values] of Object.entries(c.also ?? {})) expect(run(c, agg)[0].values).toEqual(values);
    });
  }
});

describe('flow with a vector node', () => {
  const state = () => useFlow.getState();
  let z: string;
  let sm: string;
  let v: string;

  beforeEach(() => {
    // an empty flow first: loadBucket fills a flow that was restored without data instead of replacing it
    useFlow.setState({ track: 'EXP2', domain: 'wind', sourceTopic: 'tsx.wind.episodes.v2', nodes: [], source: null });
    const x = Array.from({ length: 240 }, (_, i) => i);
    state().loadBucket(timeBucket('ws_T05', [series('s', x, x.map((i) => Math.sin(i / 10)))]), { kind: 'test', detail: '' });
    z = state().applyOp('norm.zscore')!;
    state().select(SOURCE_ID);
    sm = state().applyOp('smooth.movavg', { window: 5 })!;
    state().select(SOURCE_ID);
    v = state().applyOp(VECTOR_OP, { every: 60 })!;
  });

  it('adds inputs, refuses duplicates, itself and its own downstream', () => {
    expect(state().addInput(v, z)).toBe(true);
    expect(state().addInput(v, sm)).toBe(true);
    expect(state().addInput(v, z)).toBe(false);
    expect(state().addInput(v, SOURCE_ID)).toBe(false);
    expect(state().addInput(v, v)).toBe(false);
    const after = state().applyOp('diff')!;
    expect(state().addInput(v, after)).toBe(false);
    expect(state().addInput(z, sm)).toBe(false);      // a single-input operator takes no further input
    expect(state().nodes.find((n) => n.id === v)!.inputs).toEqual([z, sm]);
    expect(state().rewire(z, v)).toBe(false);          // would close a cycle through the further input
  });

  it('evaluates the vector from all inputs and follows parameter changes upstream', () => {
    state().addInput(v, z);
    state().addInput(v, sm);
    const node = state().nodes.find((n) => n.id === v)!;
    expect(vectorComponents(state().nodes, node)).toEqual(['raw', 'standardized', 'smooth']);
    const out = evaluate(state().source, state().nodes, v)!;
    expect(out.vector?.components).toEqual(['raw', 'standardized', 'smooth']);
    expect(out.series).toHaveLength(3);
    expect(out.series[0].y).toHaveLength(4);
    const before = out.series[2].y[1];
    state().updateParams(sm, { window: 50 });
    expect(evaluate(state().source, state().nodes, v)!.series[2].y[1]).not.toBe(before);
  });

  it('loses a component when its node is removed and goes when its first input goes', () => {
    state().addInput(v, z);
    state().addInput(v, sm);
    state().removeNode(z);
    expect(state().nodes.find((n) => n.id === v)!.inputs).toEqual([sm]);
    state().removeInput(v, sm);
    expect(state().nodes.find((n) => n.id === v)!.inputs).toEqual([]);
    expect(validate(state().nodes).some((f) => /one input only/.test(f.text))).toBe(true);
  });

  it('writes the vector node with all inputs and a vector sink into the DAG', () => {
    state().addInput(v, z);
    state().addInput(v, sm);
    state().select(SOURCE_ID);
    state().applyOp('fft');
    const label = state().nodes.find((n) => n.id === v)!.label;
    expect(label).toBe('Vector_ws_T05');
    state().setSink(v, 'tsx.wind.smart.vector-ws-t05.vectors.v1');
    const yaml = toDagYaml(state());
    expect(yaml).toContain(`  - id: ${v}\n    op: align.vector   # not in the stream operator catalog\n    in: [decode, ${z}, ${sm}]\n`);
    expect(yaml).toContain('params: { every: PT60S, key: series, agg: MEAN, emit: any, names: [raw, standardized, smooth] }');
    expect(yaml).toContain(`  - { from: ${v}, topic: tsx.wind.smart.vector-ws-t05.vectors.v1, schema: opentsx.vector.v1 }`);
    expect(yaml).toContain(`  - id: ${z}\n`);
    expect(yaml).toContain(`  - id: ${sm}\n`);
    expect(yaml).not.toContain('op: fft');
    state().updateParams(v, { fill: 'as-of', maxAge: 120 });
    expect(toDagYaml(state())).toContain('agg: MEAN, fill: as-of, maxAge: PT120S, emit: any');
    expect(validate(state().nodes).filter((f) => f.level === 'error')).toEqual([]);
  });

  it('names the source series in the DAG and says whether the retrieval server can run it', () => {
    expect(runBlocker(state().nodes)).toMatch(/Publish an "Align to vector" node first/);
    state().setSink(v, 'tsx.wind.smart.turbine-state.vectors.v1');
    expect(runBlocker(state().nodes)).toBeNull();
    expect(runBlocker(state().nodes, [])).toMatch(/Load the series from the TS inventory/);
    expect(runBlocker(state().nodes, ['6e803ad522510511'])).toBeNull();
    state().updateParams(v, { lateness: 120 });
    expect(toDagYaml(state())).toContain('lateness: PT120S');
    state().updateParams(v, { lateness: 0 });
    expect(toDagYaml(state())).not.toContain('lateness');
    expect(toDagYaml(state())).not.toContain('  series:');
    state().setSourceSeries(['6e803ad522510511', 'bf9fb54cca74b30a']);
    expect(toDagYaml(state())).toContain('  topics: [tsx.wind.episodes.v2]\n  series: ["6e803ad522510511", bf9fb54cca74b30a]\n');
    expect(toDefinition(state()).sourceSeries).toEqual(['6e803ad522510511', 'bf9fb54cca74b30a']);

    state().setSink(v, 'tsx.wind.smart.Turbine_State.vectors.v1');
    expect(runBlocker(state().nodes)).toMatch(/must have the form/);
    state().setSink(v, 'tsx.wind.smart.turbine-state.vectors.v1');
    state().addInput(v, z);
    expect(runBlocker(state().nodes)).toMatch(/reads Standardized_ws_T05. The processor executes vectors built from the source bucket only/);

    // a new bucket forgets the series of the old one; a stored flow brings its own back
    const stored = JSON.parse(JSON.stringify(toDefinition(state())));
    state().loadBucket(timeBucket('other', [series('s', [0], [1])]), { kind: 'test', detail: '' });
    expect(state().sourceSeries).toEqual([]);
    expect(state().restore(stored, 3)).toBe(true);
    expect(state().sourceSeries).toEqual(['6e803ad522510511', 'bf9fb54cca74b30a']);
  });

  it('lists what a deployment needs, step by step', () => {
    const marks = (series: string[]) => deployChecks(state().nodes, series).map((c) => (c.ok ? 'ok' : 'missing'));
    expect(marks([])).toEqual(['missing', 'missing', 'missing', 'missing']);
    expect(marks(['a'])).toEqual(['ok', 'missing', 'missing', 'missing']);
    state().setSink(v, 'tsx.wind.smart.turbine-state.vectors.v1');
    expect(marks(['a'])).toEqual(['ok', 'ok', 'ok', 'ok']);
    expect(runBlocker(state().nodes, ['a'])).toBeNull();
    state().setSink(v, 'tsx.wind.smart.Turbine_State.vectors.v1');
    expect(marks(['a'])).toEqual(['ok', 'ok', 'ok', 'missing']);
    state().setSink(v, 'tsx.wind.smart.turbine-state.vectors.v1');
    state().addInput(v, z);
    const checks = deployChecks(state().nodes, ['a']);
    expect(checks.map((c) => c.ok)).toEqual([true, true, false, true]);
    expect(checks[2].text).toMatch(/Operators before it .* run in the workbench only/);
    // a published operator alone is not a vector
    state().removeInput(v, z);
    state().setSink(v, null);
    state().setSink(z, 'tsx.wind.smart.z.metrics.v2');
    expect(deployChecks(state().nodes, ['a'])[1].text).toMatch(/other published nodes are export only/);
  });

  it('suggests the key that makes one vector per unit', () => {
    const s = (id: string, metric: string, turbine: string) => series(id, [0], [1], { metric, tags: { park: 'p', turbine } });
    const twoChannels = timeBucket('b', [s('a', 'ws', 'T01'), s('b', 'pa', 'T01'), s('c', 'ws', 'T02'), s('d', 'pa', 'T02')]);
    expect(suggestKey(twoChannels)).toBe('turbine');
    // one channel of several turbines: nothing to put together
    expect(suggestKey(timeBucket('b', [s('a', 'ws', 'T01'), s('c', 'ws', 'T02')]))).toBe('series');
    // several channels of one turbine: one vector of that turbine
    expect(suggestKey(timeBucket('b', [s('a', 'ws', 'T01'), s('b', 'pa', 'T01')]))).toMatch(/^(turbine|park)$/);
    expect(suggestKey(timeBucket('b', [series('x', [0], [1]), series('y', [0], [1])]))).toBe('series');
  });

  it('names the further inputs in the descriptor and the Java export', () => {
    state().addInput(v, z);
    const pfd = JSON.parse(toPfdJson(state(), v));
    expect(pfd.steps).toEqual([expect.objectContaining({ id: v, operation: 'ALIGN_VECTOR', inputs: ['ws_T05', 'Standardized_ws_T05'] })]);
    expect(toJavaCode(state())).toContain('// Vector_ws_T05 = Align to vector( ws_T05, Standardized_ws_T05 ): no counterpart in the Swing workbench');
  });

  it('stores and restores the further inputs and rejects broken ones', () => {
    state().addInput(v, z);
    const def = toDefinition(state());
    expect((def.edges as { id: string }[]).map((e) => e.id)).toContain(`${z}-${v}`);
    const stored = JSON.parse(JSON.stringify(def));
    expect(state().restore(stored, 7)).toBe(true);
    expect(state().nodes.find((n) => n.id === v)!.inputs).toEqual([z]);

    const nodes = stored.nodes as { id: string; inputs?: string[] }[];
    const withInputs = (id: string, inputs: string[]) => nodes.map((n) => (n.id === id ? { ...n, inputs } : n));
    expect(parseNodes(withInputs(v, ['nope']))).toBeNull();          // unknown node
    expect(parseNodes(withInputs(v, [z, z]))).toBeNull();            // twice the same
    expect(parseNodes(withInputs(z, [sm]))).toBeNull();              // operator takes one input
    const cyc = nodes.map((n) => (n.id === z ? { ...n, input: v } : n.id === v ? { ...n, inputs: [z] } : n));
    expect(parseNodes(cyc)).toBeNull();                              // v reads z, z reads v
  });
});
