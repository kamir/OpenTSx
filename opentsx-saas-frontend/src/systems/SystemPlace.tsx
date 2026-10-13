/**
 * The place "System": the atoms of the plan as a picture with their wires, and below it as a table with their ports.
 * Every input says what feeds it: an output it is wired to inside the plan, a signal with its state, or nothing. The
 * table is the way from a port to its signal.
 */

import { useMemo } from 'react';
import { Link } from 'react-router-dom';
import ReactFlow, { Background, Edge, MarkerType, Node, Position } from 'reactflow';
import 'reactflow/dist/style.css';
import { PlanDoc, feedOf, planGraph } from './signals';

const MARK = { ok: 'every input is fed', todo: 'a signal is not clean yet', missing: 'an input has no signal' } as const;

/** The atoms from left to right with their wires; a dashed wire carries the value of the step before. */
function PlanPicture({ plan }: { plan: PlanDoc }) {
  const { nodes, edges } = useMemo(() => {
    const g = planGraph(plan);
    const n: Node[] = g.nodes.map((a) => ({
      id: a.id,
      position: { x: 30 + a.layer * 250, y: 20 + a.row * 84 },
      data: {
        label: (
          <div title={MARK[a.mark]}>
            <b>{a.id}</b>
            <span>{a.type}</span>
          </div>
        ),
      },
      className: `wb-plan-node ${a.mark}`,
      sourcePosition: Position.Right,
      targetPosition: Position.Left,
      draggable: false,
      connectable: false,
    }));
    const e: Edge[] = g.edges.map((w) => ({
      id: w.id,
      source: w.from,
      target: w.to,
      label: w.label,
      type: 'smoothstep',
      markerEnd: { type: MarkerType.ArrowClosed },
      style: w.back ? { strokeDasharray: '5 4' } : undefined,
    }));
    return { nodes: n, edges: e };
  }, [plan]);
  return (
    <div className="wb-plan-picture" role="img" aria-label={`Picture of the plan: ${nodes.length} atoms, ${edges.length} wires`}>
      <ReactFlow nodes={nodes} edges={edges} fitView nodesConnectable={false} elementsSelectable={false} proOptions={{ hideAttribution: true }} minZoom={0.3}>
        <Background gap={24} />
      </ReactFlow>
    </div>
  );
}

export default function SystemPlace({ plan, system }: { plan: PlanDoc | null; system: string }) {
  if (!plan) return <main className="wb-place"><p className="wb-muted pad">Reading the plan of the system.</p></main>;
  return (
    <main className="wb-place" aria-label="Plan of the system">
      <header className="wb-inv-head">
        <h2>System</h2>
        <span className="wb-muted">
          {plan.counts.atoms} atoms, {plan.counts.inputsFromOutside} inputs from outside
          {plan.counts.inputsWithoutSignal > 0 ? `, ${plan.counts.inputsWithoutSignal} of them without a signal` : ', each with a signal'}
        </span>
      </header>
      <div className="wb-inv-source">
        <span>
          An atom is a component of the plant. An input is fed by an output of another atom, or it comes from outside:
          then it needs a signal. Select a signal to see its state and what to do next.
        </span>
      </div>
      <div className="wb-plan">
      <PlanPicture plan={plan} />
      <div className="wb-atoms">
        {plan.atoms.map((a) => (
          <section key={a.id} className="wb-atom" aria-label={`Atom ${a.id}`}>
            <h3>
              {a.id} <span className="wb-muted">{a.type}</span>
            </h3>
            {a.inputs.length > 0 && (
              <table className="wb-table">
                <thead>
                  <tr><th>input</th><th>unit</th><th>fed by</th></tr>
                </thead>
                <tbody>
                  {a.inputs.map((p) => {
                    const feed = feedOf(p);
                    return (
                      <tr key={p.name}>
                        <td className="l">{p.name}</td>
                        <td className="l">{p.unit ?? ''}</td>
                        <td className="l">
                          {feed.kind === 'wired' && <span className="wb-muted">{feed.text}</span>}
                          {feed.kind === 'signal' && (
                            <Link to={`/systems/${system}/signals?focus=${encodeURIComponent(p.signal!)}`} title="Shows the signal">
                              <span className={`wb-flow-status ${p.state}`}>{feed.text}</span>
                            </Link>
                          )}
                          {feed.kind === 'missing' && <span className="wb-flow-status open" title="Add a signal for this port to the system document">{feed.text}</span>}
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            )}
            <p className="wb-muted">
              gives {a.outputs.map((p) => `${p.name}${p.unit ? ` [${p.unit}]` : ''}`).join(', ') || 'nothing'}
              {a.channels > 0 && (
                <>
                  ; <Link to={`/systems/${system}/signals`}>{a.channels} sensor channels</Link> below it
                </>
              )}
            </p>
          </section>
        ))}
      </div>
      </div>
    </main>
  );
}
