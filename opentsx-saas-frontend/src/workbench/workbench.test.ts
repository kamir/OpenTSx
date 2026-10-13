import { beforeEach, describe, expect, it } from 'vitest';
import { OPS, OP_BY_ID, crossings, defaultParams, dfa, fftRadix2, linFit, mulberry32, profileOf, stats } from './ops';
import { bucketToCsv, generateSineMix, parseBucketText, parseDelimited, parseEpisodes } from './loaders';
import { SOURCE_ID, evaluate, toDagYaml, toDefinition, toJavaCode, toPfdJson, useFlow, validate } from './flow';
import { Bucket } from './types';

function bucketOf(...ys: number[][]): Bucket {
  return {
    name: 'B',
    xKind: 'index',
    xLabel: 'i',
    yLabel: 'y',
    series: ys.map((y, k) => ({
      id: `s${k}`,
      label: `s${k}`,
      x: Float64Array.from(y, (_, i) => i),
      y: Float64Array.from(y),
    })),
  };
}

function run(opId: string, b: Bucket, params = {}) {
  const op = OP_BY_ID[opId];
  return op.apply(b, { ...defaultParams(op), ...params });
}

describe('operators', () => {
  it('computes mean, sample standard deviation, min and max', () => {
    const s = stats([2, 4, 4, 4, 5, 5, 7, 9]);
    expect(s.mean).toBeCloseTo(5);
    expect(s.std).toBeCloseTo(Math.sqrt(32 / 7));
    expect([s.min, s.max, s.n]).toEqual([2, 9, 8]);
  });

  it('matches TimeSeriesObject for diffs, profile and log returns', () => {
    const b = bucketOf([1, 2, 4, 8]);
    expect(Array.from(run('diff', b).series[0].y)).toEqual([0, 1, 2, 4]);
    expect(Array.from(profileOf([1, 2, 3]))).toEqual([-1, -1, 0]);
    const r = run('logreturn', b).series[0].y;
    expect(r[0]).toBe(0);
    expect(r[1]).toBeCloseTo(Math.log(2));
  });

  it('normalizes to max = 1 and to mean 0, std 1', () => {
    const b = bucketOf([1, 2, 3, 4]);
    expect(Array.from(run('norm.max', b).series[0].y)).toEqual([0.25, 0.5, 0.75, 1]);
    const z = stats(run('norm.zscore', b).series[0].y);
    expect(z.mean).toBeCloseTo(0);
    expect(z.std).toBeCloseTo(1);
  });

  it('drops points without a finite logarithm', () => {
    const out = run('log', bucketOf([1, 0, -1, Math.E])).series[0];
    expect(Array.from(out.x)).toEqual([0, 3]);
    expect(out.y[1]).toBeCloseTo(1);
  });

  it('shuffles reproducibly and keeps the values', () => {
    const b = bucketOf([1, 2, 3, 4, 5, 6, 7, 8]);
    const a = Array.from(run('shuffle', b, { seed: 3 }).series[0].y);
    expect(a).toEqual(Array.from(run('shuffle', b, { seed: 3 }).series[0].y));
    expect(a).not.toEqual([1, 2, 3, 4, 5, 6, 7, 8]);
    expect([...a].sort()).toEqual([1, 2, 3, 4, 5, 6, 7, 8]);
  });

  it('finds the frequency and amplitude of a sine in the FFT', () => {
    const n = 1024;
    const rate = 256;
    const b: Bucket = {
      name: 'sine', xKind: 'time', xLabel: 't [s]', yLabel: 'y',
      series: [{
        id: 's', label: 's',
        x: Float64Array.from({ length: n }, (_, i) => i / rate),
        y: Float64Array.from({ length: n }, (_, i) => 3 * Math.sin((2 * Math.PI * 16 * i) / rate)),
      }],
    };
    const out = run('fft', b).series[0];
    let peak = 0;
    for (let k = 1; k < out.y.length; k++) if (out.y[k] > out.y[peak]) peak = k;
    expect(out.x[peak]).toBeCloseTo(16);
    expect(out.y[peak]).toBeCloseTo(3, 5);
  });

  it('transforms an impulse into a flat spectrum', () => {
    const re = Float64Array.from([1, 0, 0, 0, 0, 0, 0, 0]);
    const im = new Float64Array(8);
    fftRadix2(re, im);
    expect(Array.from(re)).toEqual([1, 1, 1, 1, 1, 1, 1, 1]);
  });

  it('gives a DFA exponent near 0.5 for white noise and near 1.5 for its integral', () => {
    const rnd = mulberry32(42);
    const noise = Float64Array.from({ length: 8192 }, () => rnd() - 0.5);
    expect(dfa(noise, 2, 30).alpha).toBeGreaterThan(0.4);
    expect(dfa(noise, 2, 30).alpha).toBeLessThan(0.6);
    const walk = profileOf(noise);
    expect(dfa(walk, 2, 30).alpha).toBeGreaterThan(1.35);
    expect(dfa(walk, 2, 30).alpha).toBeLessThan(1.65);
  });

  it('starts the autocorrelation at 1 and finds the period of a sine', () => {
    const y = Array.from({ length: 400 }, (_, i) => Math.sin((2 * Math.PI * i) / 50));
    const ac = run('acf', bucketOf(y)).series[0].y;
    expect(ac[0]).toBeCloseTo(1);
    expect(ac[25]).toBeLessThan(-0.8);
    expect(ac[50]).toBeGreaterThan(0.8);
  });

  it('builds histograms that sum to 1 and a CDF that ends at 1', () => {
    const b = bucketOf([0, 1, 2, 3, 4, 5, 6, 7, 8, 9]);
    const h = run('histogram', b, { min: 0, max: 10, bins: 5 }).series[0].y;
    expect(Array.from(h)).toEqual([0.2, 0.2, 0.2, 0.2, 0.2]);
    const c = run('cdf', b, { bins: 5 }).series[0].y;
    expect(c[c.length - 1]).toBeCloseTo(1);
  });

  it('computes the sigma band across series', () => {
    const out = run('sigma.band', bucketOf([1, 2], [3, 6]));
    expect(Array.from(out.series[0].y)).toEqual([2, 4]);
    expect(out.series[1].y[0]).toBeCloseTo(2 + Math.SQRT2);
    expect(out.series[2].y[1]).toBeCloseTo(4 - 2 * Math.SQRT2);
  });

  it('resamples into windows and marks out-of-range values', () => {
    const b = bucketOf([1, 3, 5, 7, 9, 11]);
    const r = run('resample', b, { every: 2, agg: 'MEAN' }).series[0];
    expect(Array.from(r.x)).toEqual([0, 2, 4]);
    expect(Array.from(r.y)).toEqual([2, 6, 10]);
    const gap = run('dq.plausible-range', b, { min: 2, max: 8, mode: 'gap' }).series[0].y;
    expect(Number.isNaN(gap[0])).toBe(true);
    expect(gap[1]).toBe(3);
    expect(Array.from(run('dq.plausible-range', b, { min: 2, max: 8, mode: 'clip' }).series[0].y)).toEqual([2, 3, 5, 7, 8, 8]);
  });

  it('converts units and selects series by label', () => {
    const c = run('convert.unit', bucketOf([0, 100]), { factor: 1.8, offset: 32, unit: 'degF' });
    expect(Array.from(c.series[0].y)).toEqual([32, 212]);
    expect(c.series[0].unit).toBe('degF');
    expect(run('select', bucketOf([1], [2]), { pattern: 's1' }).series.map((s) => s.id)).toEqual(['s1']);
  });

  it('cuts episodes at rising level crossings, aligned on the crossing', () => {
    const y = [0, 0, 5, 5, 0, 0, 5, 5, 0];
    expect(crossings(y, 2, true)).toEqual([2, 6]);
    expect(crossings(y, 2, false)).toEqual([4, 8]);
    const out = run('segment.threshold', bucketOf(y), { enter: 2, pre: 1, post: 2 });
    expect(out.series.length).toBe(2);
    expect(Array.from(out.series[0].x)).toEqual([-1, 0, 1]);
    expect(Array.from(out.series[0].y)).toEqual([0, 5, 5]);
  });

  it('fits a line', () => {
    const f = linFit([0, 1, 2], [1, 3, 5]);
    expect(f.slope).toBeCloseTo(2);
    expect(f.intercept).toBeCloseTo(1);
  });

  it('runs every operator on the generated bucket without NaN-only output', () => {
    const b = generateSineMix();
    for (const op of OPS) {
      const out = op.apply(b, defaultParams(op));
      expect(out.series.length, op.id).toBeGreaterThan(0);
      for (const s of out.series) {
        expect(s.x.length, op.id).toBe(s.y.length);
        expect(Array.from(s.y).some((v) => Number.isFinite(v)), op.id).toBe(true);
      }
    }
  });
});

describe('ground truth', () => {
  const marked = (): Bucket => ({
    name: 'B',
    xKind: 'time',
    xLabel: 't [s]',
    yLabel: 'y',
    series: [{ id: 's', label: 's', x: Float64Array.from({ length: 100 }, (_, i) => i), y: Float64Array.from({ length: 100 }, (_, i) => (i % 20 === 10 ? 5 : 0)) }],
    marks: [{ kind: 'sensor_fault', type: 'SPIKES', from: 40, to: 80, scope: '' }],
  });

  it('stays with operators that keep the time axis and goes where the axis changes', () => {
    expect(run('norm.zscore', marked()).marks).toHaveLength(1);
    expect(run('resample', marked(), { every: 10 }).marks).toHaveLength(1);
    expect(run('smooth.movavg', marked(), { window: 3 }).marks).toHaveLength(1);
    expect(run('shuffle', marked()).marks).toBeUndefined();
    expect(run('fft', marked()).marks).toBeUndefined();
    expect(run('histogram', marked()).marks).toBeUndefined();
  });

  it('counts the threshold crossings that lie inside an event', () => {
    // spikes at 10, 30, 50, 70, 90; the event covers 40 to 80, so two of five
    const out = run('segment.threshold', marked(), { enter: 2, direction: 'ABOVE', pre: 2, post: 2 });
    expect(out.note).toContain('5 episodes at level 2');
    expect(out.note).toContain('2 of 5 crossings lie inside a ground-truth event (SPIKES)');
    const plain = { ...marked(), marks: undefined };
    expect(run('segment.threshold', plain, { enter: 2, direction: 'ABOVE', pre: 2, post: 2 }).note).not.toContain('ground-truth');
  });
});

describe('loaders', () => {
  it('generates the MacroRecorder sine mix deterministically', () => {
    const a = generateSineMix();
    expect(a.series.length).toBe(7);
    expect(a.series[0].y.length).toBe(5000);
    expect(a.series[6].y[123]).toBe(generateSineMix().series[6].y[123]);
  });

  it('reads the wide layout with ISO timestamps as seconds from the first row', () => {
    const b = parseDelimited('timestamp,a,b\n2024-01-01T00:00:00Z,1,10\n2024-01-01T01:00:00Z,2,\n', 'x');
    expect(b.xKind).toBe('time');
    expect(Array.from(b.series[0].x)).toEqual([0, 3600]);
    expect(Number.isNaN(b.series[1].y[1])).toBe(true);
    expect(b.t0Ms).toBe(Date.parse('2024-01-01T00:00:00Z'));
  });

  it('reads tab separated files and the long layout', () => {
    expect(parseDelimited('t\topen\n1\t100\n2\t101\n', 'x').series[0].label).toBe('open');
    const long = parseDelimited('series_id,x,y\n0,0.0,1\n0,0.1,2\n1,0.0,3\n', 'x');
    expect(long.series.map((s) => s.y.length)).toEqual([2, 1]);
  });

  it('joins the episodes of one series in time order', () => {
    const b = parseEpisodes({
      episodes: [
        { seriesId: 'a', metric: 'm', tags: { t: '1' }, unit: 'kW', timestampsMicros: [2e6, 3e6], values: [3, null] },
        { seriesId: 'a', metric: 'm', tags: { t: '1' }, unit: 'kW', timestampsMicros: [0, 1e6], values: [1, 2] },
      ],
    }, 'e');
    expect(b.series.length).toBe(1);
    expect(b.series[0].label).toBe('m{t=1}');
    expect(Array.from(b.series[0].x)).toEqual([0, 1, 2, 3]);
    expect(Array.from(b.series[0].y).slice(0, 3)).toEqual([1, 2, 3]);
    expect(Number.isNaN(b.series[0].y[3])).toBe(true);
  });

  it('rejects files without usable data', () => {
    expect(() => parseDelimited('a,b\n', 'x')).toThrow();
    expect(() => parseDelimited('name,v\nfoo,1\n', 'x')).toThrow(/timestamps or numbers/);
    expect(() => parseBucketText('[]', 'x.json')).toThrow(/No episodes/);
  });

  it('round-trips a bucket through the long CSV layout', () => {
    const back = parseDelimited(bucketToCsv(bucketOf([1, 2], [3, 4])), 'x');
    expect(back.series.map((s) => Array.from(s.y))).toEqual([[1, 2], [3, 4]]);
  });
});

describe('flow', () => {
  beforeEach(() => {
    useFlow.setState({ track: 'EXP2', domain: 'lab', sourceTopic: 'tsx.lab.observations.v2' });
    useFlow.getState().loadBucket(bucketOf([1, 2, 4, 8, 16, 32, 64, 128]), { kind: 'test', detail: '' });
  });

  it('records operations as child nodes named like the Swing macro recorder', () => {
    const f = useFlow.getState();
    f.applyOp('diff');
    const id = useFlow.getState().applyOp('norm.max')!;
    const nodes = useFlow.getState().nodes;
    expect(nodes.map((n) => n.label)).toEqual(['B', 'Diff_B', 'NormMAX_Diff_B']);
    const out = evaluate(useFlow.getState().source, nodes, id)!;
    expect(out.series[0].y[7]).toBe(1);
    expect(out.series[0].y[1]).toBeCloseTo(1 / 64);
  });

  it('re-evaluates when a parameter changes', () => {
    const id = useFlow.getState().applyOp('resample', { every: 2 })!;
    expect(evaluate(useFlow.getState().source, useFlow.getState().nodes, id)!.series[0].y.length).toBe(4);
    useFlow.getState().updateParams(id, { every: 4 });
    expect(evaluate(useFlow.getState().source, useFlow.getState().nodes, id)!.series[0].y.length).toBe(2);
  });

  it('gives distinct names to repeated operations and removes a node with its downstream', () => {
    const a = useFlow.getState().applyOp('diff')!;
    useFlow.getState().applyOp('fft');
    useFlow.getState().select(SOURCE_ID);
    useFlow.getState().applyOp('diff');
    expect(useFlow.getState().nodes.map((n) => n.label)).toEqual(['B', 'Diff_B', 'FFT_Diff_B', 'Diff_B_2']);
    useFlow.getState().removeNode(a);
    expect(useFlow.getState().nodes.map((n) => n.label)).toEqual(['B', 'Diff_B_2']);
    useFlow.getState().removeNode(SOURCE_ID);
    expect(useFlow.getState().nodes.length).toBe(2);
  });

  it('rewires a node and refuses cycles', () => {
    const a = useFlow.getState().applyOp('diff')!;
    const b = useFlow.getState().applyOp('profile')!;
    expect(useFlow.getState().rewire(a, b)).toBe(false);
    expect(useFlow.getState().rewire(b, SOURCE_ID)).toBe(true);
    expect(useFlow.getState().nodes.find((n) => n.id === b)!.label).toBe('Profile_B');
  });

  it('writes opentsx.dag.v1 with only the published branches', () => {
    const r = useFlow.getState().applyOp('resample', { every: 2, agg: 'MEAN' })!;
    const st = useFlow.getState().applyOp('stats.window', { window: 4, field: 'STD' })!;
    useFlow.getState().select(SOURCE_ID);
    useFlow.getState().applyOp('fft');
    useFlow.getState().setSink(st, 'tsx.lab.smart.vol.metrics.v2');
    const yaml = toDagYaml(useFlow.getState());
    expect(yaml).toContain('dag: lab-exp2-smart');
    expect(yaml).toContain('topics: [tsx.lab.observations.v2]');
    expect(yaml).toContain(`  - id: ${r}\n    op: resample\n    in: [decode]\n    params: { every: 2, agg: MEAN }`);
    expect(yaml).toContain(`op: stats.welford-ddsketch\n    in: [${r}]`);
    expect(yaml).toContain(`  - { from: ${st}, topic: tsx.lab.smart.vol.metrics.v2 }`);
    expect(yaml).not.toContain('op: fft');
    expect(validate(useFlow.getState().nodes).filter((f) => f.level !== 'info')).toEqual([]);
  });

  it('reports missing sinks, operators outside the catalog and duplicate topics', () => {
    expect(validate(useFlow.getState().nodes).some((f) => f.level === 'error')).toBe(true);
    const a = useFlow.getState().applyOp('fft')!;
    useFlow.getState().setSink(a, 't.one');
    useFlow.getState().setSink(SOURCE_ID, 't.one');
    const found = validate(useFlow.getState().nodes);
    expect(found.some((f) => f.level === 'warning' && f.text.includes('fft'))).toBe(true);
    expect(found.some((f) => f.level === 'error' && f.text.includes('same topic'))).toBe(true);
  });

  it('exports the branch as Processing Flow Descriptor and as Java code', () => {
    useFlow.getState().applyOp('norm.zscore');
    const id = useFlow.getState().applyOp('dfa', { order: 2, scales: 10 })!;
    const pfd = JSON.parse(toPfdJson(useFlow.getState(), id));
    expect(pfd.input).toEqual({ type: 'KAFKA', topic: 'tsx.lab.observations.v2', schema: 'Observation' });
    expect(pfd.steps.map((s: { operation: string }) => s.operation)).toEqual(['NORMALIZE', 'DFA']);
    const java = toJavaCode(useFlow.getState());
    expect(java).toContain('TSBucket tsbB = TSBucket.loadViaContext( sc, "/TSBASE/EXP2/B.tsb.vec.seq" );');
    expect(java).toContain('TSBucket tsbDFA_Standardized_B = tsbStandardized_B.processBucket( "DFA(2,10)", null );');
  });

  it('restores a stored flow and binds the next bucket as its source', () => {
    useFlow.getState().applyOp('diff');
    const st = useFlow.getState().applyOp('stats.window', { window: 4, field: 'MAX' })!;
    useFlow.getState().setSink(st, 'tsx.lab.smart.x.metrics.v2');
    const stored = JSON.parse(JSON.stringify(toDefinition(useFlow.getState())));
    expect(stored.kind).toBe('opentsx.workbench.v1');
    expect(stored.dag).toContain('topic: tsx.lab.smart.x.metrics.v2');

    useFlow.getState().loadBucket(bucketOf([0]), { kind: 'test', detail: '' });
    expect(useFlow.getState().restore(stored, 7)).toBe(true);
    let s = useFlow.getState();
    expect([s.savedId, s.source, s.nodes.length, s.counter]).toEqual([7, null, 3, 2]);
    expect(evaluate(s.source, s.nodes, st)).toBeNull();

    s.loadBucket({ ...bucketOf([1, 5, 2, 9, 3, 3, 4, 8]), name: 'Other' }, { kind: 'test', detail: '' });
    s = useFlow.getState();
    expect(s.nodes.map((n) => n.label)).toEqual(['Other', 'Diff_Other', 'Stats_Diff_Other']);
    expect(s.savedId).toBe(7);
    expect(Array.from(evaluate(s.source, s.nodes, st)!.series[0].y)).toEqual([7, 4]);
    expect(useFlow.getState().applyOp('fft')).toBe('n3');
  });

  it('refuses stored definitions that are foreign, cyclic or use unknown operators', () => {
    const node = (id: string, op: string, input: string | null) => ({ id, op, input, label: id, params: {} });
    const src = node('source', 'source', null);
    const restore = (nodes: unknown, kind = 'opentsx.workbench.v1') => useFlow.getState().restore({ kind, nodes }, 1);
    expect(restore([src, node('n1', 'diff', 'source')])).toBe(true);
    expect(restore([src, node('n1', 'diff', 'source')], 'other')).toBe(false);
    expect(restore([src, node('n1', 'rm -rf', 'source')])).toBe(false);
    expect(restore([src, node('n1', 'diff', 'n2'), node('n2', 'diff', 'n1')])).toBe(false);
    expect(restore([src, node('n1', 'diff', 'gone')])).toBe(false);
    expect(restore([node('n1', 'diff', null)])).toBe(false);
    expect(restore('nodes')).toBe(false);
  });
});
