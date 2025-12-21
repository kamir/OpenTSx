/**
 * Flow Builder page component
 */

import { useState, useCallback } from 'react';
import ReactFlow, {
  Node,
  Edge,
  addEdge,
  Connection,
  useNodesState,
  useEdgesState,
  Controls,
  Background,
  Panel,
} from 'reactflow';
import 'reactflow/dist/style.css';
import { useNavigate } from 'react-router-dom';
import { flowsAPI } from '../services/api';

// Initial nodes for demo
const initialNodes: Node[] = [
  {
    id: '1',
    type: 'input',
    data: { label: 'Data Generator' },
    position: { x: 100, y: 100 },
  },
  {
    id: '2',
    data: { label: 'DFA Analysis' },
    position: { x: 300, y: 100 },
  },
  {
    id: '3',
    type: 'output',
    data: { label: 'Chart' },
    position: { x: 500, y: 100 },
  },
];

const initialEdges: Edge[] = [
  { id: 'e1-2', source: '1', target: '2' },
  { id: 'e2-3', source: '2', target: '3' },
];

export default function FlowBuilder() {
  const navigate = useNavigate();
  const [nodes, , onNodesChange] = useNodesState(initialNodes);
  const [edges, setEdges, onEdgesChange] = useEdgesState(initialEdges);
  const [flowName, setFlowName] = useState('My New Flow');
  const [isSaving, setIsSaving] = useState(false);

  const onConnect = useCallback(
    (params: Connection) => setEdges((eds) => addEdge(params, eds)),
    [setEdges]
  );

  const handleSave = async () => {
    setIsSaving(true);
    try {
      const flowData = {
        name: flowName,
        description: 'Created with visual flow builder',
        definition: {
          nodes: nodes.map((node) => ({
            id: node.id,
            type: node.type || 'default',
            label: (node.data as { label: string }).label,
            position: node.position,
            config: {},
          })),
          edges: edges.map((edge) => ({
            id: edge.id,
            source: edge.source,
            target: edge.target,
          })),
        },
        organization_id: 1, // This should come from auth context
      };

      await flowsAPI.create(flowData);
      alert('Flow saved successfully!');
      navigate('/dashboard');
    } catch (error) {
      console.error('Failed to save flow:', error);
      alert('Failed to save flow. Please try again.');
    } finally {
      setIsSaving(false);
    }
  };

  return (
    <div className="h-screen flex flex-col">
      {/* Header */}
      <div className="bg-white shadow-sm border-b">
        <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8">
          <div className="flex justify-between items-center h-16">
            <div className="flex items-center space-x-4">
              <button
                onClick={() => navigate('/dashboard')}
                className="text-gray-600 hover:text-gray-900"
              >
                ← Back
              </button>
              <input
                type="text"
                value={flowName}
                onChange={(e) => setFlowName(e.target.value)}
                className="text-lg font-medium border-none focus:outline-none focus:ring-2 focus:ring-indigo-500 rounded px-2"
              />
            </div>
            <div className="flex items-center space-x-2">
              <button
                onClick={handleSave}
                disabled={isSaving}
                className="px-4 py-2 bg-indigo-600 text-white rounded-md hover:bg-indigo-700 focus:outline-none focus:ring-2 focus:ring-indigo-500 disabled:opacity-50"
              >
                {isSaving ? 'Saving...' : 'Save Flow'}
              </button>
            </div>
          </div>
        </div>
      </div>

      {/* Flow Builder Canvas */}
      <div className="flex-1">
        <ReactFlow
          nodes={nodes}
          edges={edges}
          onNodesChange={onNodesChange}
          onEdgesChange={onEdgesChange}
          onConnect={onConnect}
          fitView
        >
          <Controls />
          <Background />
          <Panel position="top-left" className="bg-white p-4 rounded shadow">
            <div className="text-sm">
              <h3 className="font-medium mb-2">Visual Flow Builder</h3>
              <p className="text-gray-600 text-xs">
                Drag nodes to reposition.
                <br />
                Connect nodes by dragging from output to input.
              </p>
            </div>
          </Panel>

          <Panel position="top-right" className="bg-white p-4 rounded shadow">
            <div className="text-sm space-y-2">
              <div>
                <div className="font-medium mb-1">Node Types:</div>
                <div className="space-y-1 text-xs text-gray-600">
                  <div>📥 Data Generator - Create synthetic data</div>
                  <div>📊 DFA Analysis - Analyze correlations</div>
                  <div>📈 Chart - Visualize results</div>
                </div>
              </div>
            </div>
          </Panel>
        </ReactFlow>
      </div>

      {/* Instructions Panel */}
      <div className="bg-gray-50 border-t p-4">
        <div className="max-w-7xl mx-auto">
          <div className="text-sm text-gray-600">
            <strong>Getting Started:</strong> This is a basic flow builder.
            Connect nodes to create your analysis pipeline. In production,
            you'll have access to 15+ node types including Kafka sources, DFA,
            MFDFA, and more. Click "Save Flow" when ready.
          </div>
        </div>
      </div>
    </div>
  );
}
