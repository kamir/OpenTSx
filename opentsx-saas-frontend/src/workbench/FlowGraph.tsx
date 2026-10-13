/**
 * Graph view of the processing flow. Nodes are buckets, edges are operators
 * feeding the next bucket. Dragging a connection onto a node changes its input; onto a multi-input node (a vector)
 * it adds a further input, drawn dashed.
 */

import { useEffect, useMemo, useState } from 'react';
import ReactFlow, {
  Background,
  Connection,
  Controls,
  Edge,
  Handle,
  Node,
  NodeChange,
  NodeProps,
  Position,
  ReactFlowInstance,
} from 'reactflow';
import 'reactflow/dist/style.css';
import { OP_BY_ID } from './ops';
import { SOURCE_ID, inputsOf, useFlow } from './flow';
import { FlowNode } from './types';

interface NodeData {
  node: FlowNode;
  group: string;
  opLabel: string;
}

function BucketNode({ data, selected }: NodeProps<NodeData>) {
  const { node, group, opLabel } = data;
  return (
    <div className={`wb-node wb-g-${group}${selected ? ' selected' : ''}`}>
      {node.op !== 'source' && <Handle type="target" position={Position.Left} />}
      <div className="wb-node-op">{opLabel}</div>
      <div className="wb-node-label" title={node.label}>{node.label}</div>
      {node.sinkTopic && <div className="wb-node-sink" title={node.sinkTopic}>Smart Topic: {node.sinkTopic}</div>}
      <Handle type="source" position={Position.Right} />
    </div>
  );
}

const nodeTypes = { bucket: BucketNode };

/** Tree layout: depth gives the column, leaves take one row each. */
function layout(nodes: FlowNode[]): Map<string, { x: number; y: number }> {
  const children = new Map<string, FlowNode[]>();
  for (const n of nodes) {
    if (n.input) children.set(n.input, [...(children.get(n.input) ?? []), n]);
  }
  const pos = new Map<string, { x: number; y: number }>();
  let row = 0;
  const place = (id: string, depth: number) => {
    const kids = children.get(id) ?? [];
    const first = row;
    if (kids.length === 0) row++;
    for (const k of kids) place(k.id, depth + 1);
    pos.set(id, { x: 16 + depth * 230, y: 14 + first * 76 });
  };
  for (const n of nodes) if (!n.input) place(n.id, 0);
  return pos;
}

export default function FlowGraph() {
  const nodes = useFlow((s) => s.nodes);
  const selectedId = useFlow((s) => s.selectedId);
  const select = useFlow((s) => s.select);
  const rewire = useFlow((s) => s.rewire);
  const addInput = useFlow((s) => s.addInput);
  const moveNode = useFlow((s) => s.moveNode);
  const removeNode = useFlow((s) => s.removeNode);

  const [instance, setInstance] = useState<ReactFlowInstance | null>(null);

  // Keep the whole flow in view when a node is added or removed.
  useEffect(() => {
    if (!instance) return;
    const t = setTimeout(() => instance.fitView({ maxZoom: 1, padding: 0.15, duration: 200 }), 30);
    return () => clearTimeout(t);
  }, [instance, nodes.length]);

  const rfNodes: Node<NodeData>[] = useMemo(() => {
    const auto = layout(nodes);
    return nodes.map((n) => {
      const op = OP_BY_ID[n.op];
      return {
        id: n.id,
        type: 'bucket',
        position: n.pos ?? auto.get(n.id) ?? { x: 0, y: 0 },
        selected: n.id === selectedId,
        deletable: n.id !== SOURCE_ID,
        data: { node: n, group: op?.group ?? 'source', opLabel: op?.label ?? 'Bucket (source)' },
      };
    });
  }, [nodes, selectedId]);

  const rfEdges: Edge[] = useMemo(
    () =>
      nodes.flatMap((n) =>
        inputsOf(n).map((i, k) => ({
          id: `${i}-${n.id}`,
          source: i,
          target: n.id,
          deletable: false,
          ...(k > 0 ? { style: { strokeDasharray: '5 4' } } : {}),
        }))
      ),
    [nodes]
  );

  const onNodesChange = (changes: NodeChange[]) => {
    for (const c of changes) {
      if (c.type === 'position' && c.position) moveNode(c.id, c.position);
      if (c.type === 'remove') removeNode(c.id);
    }
  };

  const onConnect = (c: Connection) => {
    if (!c.source || !c.target) return;
    const target = nodes.find((n) => n.id === c.target);
    if (target && OP_BY_ID[target.op]?.multiInput) addInput(c.target, c.source);
    else rewire(c.target, c.source);
  };

  return (
    <ReactFlow
      nodes={rfNodes}
      edges={rfEdges}
      nodeTypes={nodeTypes}
      onNodesChange={onNodesChange}
      onConnect={onConnect}
      onNodeClick={(_, n) => select(n.id)}
      onInit={setInstance}
      fitView
      fitViewOptions={{ maxZoom: 1, padding: 0.15 }}
      minZoom={0.3}
      proOptions={{ hideAttribution: false }}
    >
      <Background gap={16} color="#b9bec7" />
      <Controls showInteractive={false} />
    </ReactFlow>
  );
}
