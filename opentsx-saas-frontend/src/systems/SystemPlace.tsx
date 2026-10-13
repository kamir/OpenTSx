/**
 * The place "System": the atoms of the plan with their ports. Every input says what feeds it: an output it is wired
 * to inside the plan, a signal with its state, or nothing. The picture with the wires is the Smart Topics Studio;
 * this is its table, and the way from a port to its signal.
 */

import { Link } from 'react-router-dom';
import { PlanDoc, feedOf } from './signals';

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
    </main>
  );
}
