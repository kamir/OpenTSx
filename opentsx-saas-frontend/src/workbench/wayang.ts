import { chainTo, FlowDoc, SOURCE_ID, toDagYaml } from './flow';
import { OP_BY_ID } from './ops';
import { WayangConfig } from './types';

export function buildWayangDag(flow: FlowDoc, configuration: WayangConfig, operators: string[]): string {
  const topics = configuration.inputTopics;
  const topicPattern = /^tsx\.[a-z0-9][a-z0-9_-]*\.[a-z0-9][a-z0-9-]*(?:\.[a-z0-9][a-z0-9-]*)*\.v[1-9][0-9]*$/;
  if (topics.length < 1 || topics.length > 3 || new Set(topics).size !== topics.length || topics.some((t) => !topicPattern.test(t))) {
    throw new Error('Choose one to three distinct whole input topics.');
  }
  const ids = configuration.seriesIds;
  if (ids.length === 0 || ids.length > 64 || new Set(ids).size !== ids.length || ids.some((id) => !/^[0-9a-f]{16}$/.test(id))) {
    throw new Error('Provide one to 64 unique canonical series IDs for the selected topics.');
  }
  if (!configuration.system.trim() || !configuration.signal.trim()) throw new Error('Provide the output system and signal.');
  if (!/^tsx\.[a-z0-9][a-z0-9_-]*\.clean\.v2$/.test(configuration.outputTopic) || topics.includes(configuration.outputTopic)) {
    throw new Error('Choose a distinct tsx.<domain>.clean.v2 output topic.');
  }
  const chain = chainTo(flow.nodes, configuration.nodeId);
  if (!chain || chain.length === 0) throw new Error('Choose the final cleaning step of one linear source chain.');
  const unsupported = chain.find((node) => !operators.includes(OP_BY_ID[node.op]?.dagOp ?? node.op));
  if (unsupported) throw new Error(`Operator ${unsupported.op} is not available on the bounded Rust target.`);
  const source = flow.nodes.find((node) => node.id === SOURCE_ID);
  if (!source) throw new Error('Load a source bucket before creating the bounded plan.');
  return toDagYaml({
    ...flow,
    sourceTopics: topics,
    sourceSeries: ids,
    context: { system: configuration.system, systemTitle: configuration.system, signal: configuration.signal,
      grade: 'raw', expect: flow.context?.expect ?? {}, synthetic: flow.context?.synthetic ?? false,
      ...(flow.context?.origin ? { origin: flow.context.origin } : {}) },
    nodes: [source, ...chain].map((node) => ({ ...node,
      sinkTopic: node.id === configuration.nodeId ? configuration.outputTopic : undefined })),
  });
}

export function splitEntries(text: string): string[] {
  return text.split(/[\s,]+/).filter(Boolean);
}
