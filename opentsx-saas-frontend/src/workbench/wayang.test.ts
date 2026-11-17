import { beforeEach, describe, expect, it } from 'vitest';
import { FlowDoc, toDagYaml, toDefinition, useFlow } from './flow';
import { WayangConfig } from './types';
import { buildWayangDag } from './wayang';

export const operators = ['dq.checks', 'convert.unit', 'resample'];
export const configuration: WayangConfig = {
  inputTopics: ['tsx.test.one.v2', 'tsx.test.two.v2', 'tsx.test.three.v2'],
  seriesIds: ['0000000000000001', '0000000000000002'],
  nodeId: 'n2', system: 'test-system', signal: 'power', outputTopic: 'tsx.test.clean.v2',
};
export const graph: FlowDoc = { track: 'test', domain: 'test', sourceTopic: 'tsx.test.one.v2', savedId: 7,
  nodes: [
    { id: 'source', op: 'source', label: 'Source', input: null, params: {} },
    { id: 'n1', op: 'convert.unit', label: 'Convert', input: 'source', params: { factor: 2, offset: 1, unit: 'MW' } },
    { id: 'n2', op: 'resample', label: 'Resample', input: 'n1', params: { every: 20, agg: 'MEAN' } },
  ] };

beforeEach(() => useFlow.getState().clear());

it('provides an explicit bounded Wayang target', async () => {
  const target = await import('./wayang');
  expect(typeof target.buildWayangDag).toBe('function');
});

describe('bounded target export', () => {
  it('preserves all three whole topics and original graph IDs in one explicit v2 document', () => {
    const text = buildWayangDag(graph, configuration, operators);
    expect(text).toContain('schema: opentsx.dag.v2');
    expect(text).toContain('topics: [tsx.test.one.v2, tsx.test.two.v2, tsx.test.three.v2]');
    expect(text).toContain('series: ["0000000000000001", "0000000000000002"]');
    expect(text).toContain('id: n1\n    op: convert.unit');
    expect(text).toContain('id: n2\n    op: resample');
    expect(text).toContain('in: [n1]');
    expect(text).toContain('every: PT20S');
    expect(text).toContain('system: test-system, id: power, grade: clean');
    expect(text).not.toContain('partition');
    expect(graph.nodes.some((node) => node.sinkTopic)).toBe(false);
  });

  it('leaves the ordinary continuous export at its original single topic', () => {
    expect(toDagYaml({ ...graph, wayang: configuration })).toContain('topics: [tsx.test.one.v2]');
    expect(toDagYaml({ ...graph, wayang: configuration })).not.toContain('tsx.test.two.v2');
  });

  it('persists explicit target settings with the saved flow', () => {
    const definition = toDefinition({ ...graph, wayang: configuration });
    expect(definition.wayang).toEqual(configuration);
    expect(useFlow.getState().restore(definition, 7)).toBe(true);
    expect(useFlow.getState().wayang).toEqual(configuration);
    expect(useFlow.getState().restore({ ...definition, wayang: false }, 7)).toBe(false);
  });

  it.each([
    { ...configuration, inputTopics: [configuration.inputTopics[0], configuration.inputTopics[0]] },
    { ...configuration, inputTopics: [] },
    { ...configuration, seriesIds: [] },
    { ...configuration, seriesIds: ['ABCDEF0000000000'] },
    { ...configuration, seriesIds: ['0000000000000001', '0000000000000001'] },
    { ...configuration, system: '' },
    { ...configuration, signal: '' },
    { ...configuration, outputTopic: 'tsx.test.one.v2' },
  ])('rejects incomplete or ambiguous explicit configuration %#', (invalid) => {
    expect(() => buildWayangDag(graph, invalid, operators)).toThrow();
  });

  it('refuses unsupported operators without changing target or applying a fallback', () => {
    expect(() => buildWayangDag(graph, configuration, ['dq.checks'])).toThrow('convert.unit');
    expect(() => buildWayangDag(graph, { ...configuration, nodeId: 'source' }, operators)).toThrow('final cleaning step');
  });
});
