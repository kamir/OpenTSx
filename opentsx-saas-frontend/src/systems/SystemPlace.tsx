/**
 * The place "System": the atoms of the plan as a picture with their wires, and below it as a table with their ports.
 * Every input says what feeds it: an output it is wired to inside the plan, a signal with its state, or nothing. The
 * table is the way from a port to its signal.
 */

import { useMemo } from 'react';
import { Link } from 'react-router-dom';
import ReactFlow, { Background, Edge, MarkerType, Node, Position } from 'reactflow';
import 'reactflow/dist/style.css';
import { Carrier, PlanDoc, feedOf, planGraph } from './signals';

const MARK = {
  ok: 'every input is fed',
  todo: 'a signal has data that is not clean yet',
  open: 'a signal has no source or no data',
  missing: 'an input has no signal',
} as const;

const COLOR: Record<Carrier, string> = { power: '#c98a00', hydrogen: '#2f6fb3', info: '#8a949e' };
const COLUMN = 250;
const ROW = 74;

/**
 * The atoms in the phases of a time step from left to right, with their wires: power, hydrogen and information in
 * three colours. A dashed wire carries the value of the step before.
 */
function PlanPicture({ plan }: { plan: PlanDoc }) {
  const { nodes, edges, columns } = useMemo(() => {
    const g = planGraph(plan);
    const top = g.columns.length > 0 ? 34 : 14;
    const n: Node[] = g.nodes.map((a) => ({
      id: a.id,
      position: { x: 30 + a.layer * COLUMN, y: top + a.row * ROW },
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
    g.columns.forEach((title, i) =>
      n.push({
        id: `column-${i}`,
        position: { x: 30 + i * COLUMN, y: 0 },
        data: { label: title },
        className: 'wb-plan-column',
        draggable: false,
        connectable: false,
        selectable: false,
      }),
    );
    const e: Edge[] = g.edges.map((w) => ({
      id: w.id,
      source: w.from,
      target: w.to,
      type: 'smoothstep',
      markerEnd: { type: MarkerType.ArrowClosed, color: COLOR[w.carrier] },
      style: { stroke: COLOR[w.carrier], strokeWidth: w.carrier === 'info' ? 1 : 2, strokeDasharray: w.back ? '5 4' : undefined },
      zIndex: w.carrier === 'info' ? 0 : 1,
    }));
    return { nodes: n, edges: e, columns: g.columns };
  }, [plan]);
  const wires = edges.length;
  return (
    <div className="wb-plan-picture" role="img" aria-label={`Picture of the plan: ${plan.atoms.length} atoms, ${wires} wires${columns.length ? `, in ${columns.length} phases` : ''}`}>
      <ReactFlow nodes={nodes} edges={edges} fitView nodesConnectable={false} elementsSelectable={false} proOptions={{ hideAttribution: true }} minZoom={0.3}>
        <Background gap={24} />
      </ReactFlow>
      <ul className="wb-plan-legend" aria-label="What the wires carry">
        <li><i style={{ background: COLOR.power }} /> power</li>
        <li><i style={{ background: COLOR.hydrogen }} /> hydrogen</li>
        <li><i style={{ background: COLOR.info }} /> information</li>
        <li><i className="dashed" /> arrives one step later</li>
      </ul>
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
        <a className="wb-head-link" href="/studio/" target="_blank" rel="noreferrer" title="The same plan with its runs over time: values at every port, events, what-if and forecast">
          Open in the Studio
        </a>
      </header>
      <div className="wb-inv-source">
        <span>
          An atom is a component of the plant. An input is fed by an output of another atom, or it comes from outside:
          then it needs a signal. Select a signal to see its state and what to do next.
        </span>
        {(plan.assumptions ?? []).length > 0 && (
          <details className="wb-assumptions" open>
            <summary>Assumed, not measured ({plan.assumptions!.length})</summary>
            <ul>
              {plan.assumptions!.map((a) => (
                <li key={a}>{a}</li>
              ))}
            </ul>
          </details>
        )}
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
