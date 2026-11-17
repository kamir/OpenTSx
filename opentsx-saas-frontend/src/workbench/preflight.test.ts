import { describe, expect, it } from 'vitest';
import { deployChecks, keepQuality, runBlocker, toDagYaml, toDefinition } from './flow';
import { runPlan, summarize } from './flows';
import { defaultParams, OP_BY_ID } from './ops';
import { Bucket, FlowNode, SignalContext } from './types';

const source: FlowNode = { id: 'source', op: 'source', label: 'Input', params: {}, input: null };
const vector: FlowNode = { id: 'v', op: 'align.vector', label: 'Vector', params: {}, input: 'source', sinkTopic: 'tsx.demo.smart.vector.vectors.v1' };
const pair: FlowNode = { id: 'p', op: 'pair.xcorr', label: 'Pair', params: {}, input: 'source', sinkTopic: 'tsx.demo.smart.pair.metrics.v2' };
const clean: FlowNode = { id: 'c', op: 'convert.unit', label: 'Converted', params: { factor: 1000, unit: 'W' }, input: 'source', sinkTopic: 'tsx.demo.clean.v2' };
const context: SignalContext = { system: 'demo', systemTitle: 'Demo', signal: 'power', grade: 'raw', expect: {}, synthetic: false };
const doc = (nodes: FlowNode[]) => ({ track: 'demo', domain: 'demo', sourceTopic: 'tsx.demo.episodes.v2', sourceSeries: ['a', 'b'], context, nodes });

function blocked(nodes: FlowNode[], text: RegExp) {
  const reason = runBlocker(nodes, ['a', 'b'], context);
  expect(reason).toMatch(text);
  expect(deployChecks(nodes, ['a', 'b'], context).some((c) => !c.ok && c.text === reason)).toBe(true);
  const summary = summarize({ id: 7, name: 'Demo', definition: toDefinition(doc(nodes)) });
  expect(summary.runnable).toBe(false);
  expect(runPlan(summary)).toEqual({ blocker: reason });
}

describe('deployment preflight for every published path', () => {
  it('rejects a pair sink beside a valid vector sink', () => blocked([source, vector, pair], /Pair.*pair.xcorr.*browser/));
  it('rejects a pair sink beside a valid clean sink', () => blocked([source, clean, { ...pair, op: 'pair.es' }], /Pair.*pair.es.*browser/));
  it('checks every clean sink instead of accepting the first one', () => blocked([source, clean, { ...pair, sinkTopic: 'tsx.other.clean.v2' }], /workbench only/));
  it('checks a vector path beside a valid clean sink', () => blocked([source, clean, { ...vector, input: clean.id }], /source bucket directly/));
  it('rejects duplicate output topics', () => blocked([source, vector, { ...vector, id: 'v2' }], /same topic/));
  it('refuses statistics outside the deployed processor profile', () => blocked([source, vector, { ...pair, op: 'stats.window', label: 'Statistics', params: defaultParams(OP_BY_ID['stats.window']) }], /Statistics.*catalog parameters/));
  it('rejects statistics with missing export parameters', () => {
    const input = doc([source, { ...pair, op: 'stats.window', label: 'Statistics' }]);
    const message = 'A deployed statistics window must be a positive number of seconds.';
    expect(() => toDagYaml(input)).toThrow(message);
    expect(() => toDefinition(input)).toThrow(message);
  });
  it('does not deploy an unpublished pair preview', () => {
    const nodes = [source, vector, { ...pair, sinkTopic: undefined }];
    expect(runBlocker(nodes, ['a', 'b'], context)).toBeNull();
    expect(toDagYaml(doc(nodes))).not.toContain('op: pair.xcorr');
  });
  it('marks exported pair definitions experimental without marking supported nodes', () => {
    const yaml = toDagYaml(doc([source, vector, pair]));
    expect(yaml).toMatch(/id: p\n[\s\S]*?op: pair.xcorr[^\n]*\n {4}experimental: true\n/);
    expect(yaml.match(/experimental: true/g)).toHaveLength(1);
  });
});

const modules = import.meta.glob('./capabilities.ts');
describe('operator capability labels', () => {
  it('separates a deployable processor from a numerical kernel', async () => {
    const load = modules['./capabilities.ts'];
    expect(load).toBeDefined();
    const { capabilityOf } = await load() as { capabilityOf: (id: string) => { browser: string; processor: string; wayang: string; reason: string } };
    expect(capabilityOf('align.vector')).toMatchObject({ browser: 'Available', processor: 'Available', wayang: 'Not deployable' });
    for (const id of ['pair.xcorr', 'pair.es', 'stats.window']) {
      expect(capabilityOf(id)).toMatchObject({ browser: 'Available', processor: 'Unavailable', wayang: 'Java API only' });
      expect(capabilityOf(id).reason).toMatch(/This workbench deployment path does not execute/);
    }
    expect(capabilityOf('stats.window').reason).toMatch(/catalog parameters/);
    expect(capabilityOf('missing')).toMatchObject({ browser: 'Unavailable', processor: 'Unavailable', wayang: 'Not deployable' });
  });
});


describe('quality knowledge after an axis change', () => {
  const x = Float64Array.of(0, 1);
  const bucket: Bucket = { name: 'input', xKind: 'time', xLabel: 'seconds', yLabel: 'V', series: [{ id: 'a', label: 'A', x, y: Float64Array.of(1, 2), quality: Uint8Array.of(0, 1), qualityKnown: Uint8Array.of(1, 0) }] };
  it('drops the imported knowledge mask with inherited quality on a new axis', () => {
    const changed = { ...bucket, series: [{ ...bucket.series[0], x: Float64Array.of(0, 2) }] };
    expect(keepQuality(changed, [bucket]).series[0].quality).toBeUndefined();
    expect(keepQuality(changed, [bucket]).series[0].qualityKnown).toBeUndefined();
  });
  it('keeps quality and its knowledge mask when the axis is retained', () => {
    const out = keepQuality(bucket, [bucket]).series[0];
    expect(out.quality).toBe(bucket.series[0].quality);
    expect(out.qualityKnown).toBe(bucket.series[0].qualityKnown);
  });
});
