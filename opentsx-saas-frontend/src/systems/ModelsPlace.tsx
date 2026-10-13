/**
 * The place "Models": the decisions of the system and what each of their analyses may claim today. A decision card
 * says which signals the decision needs and what makes each fit for this purpose; the server puts the state of the
 * signals next to it. What is missing blocks exactly the analyses that need it; clean inputs alone prove nothing,
 * so an analysis without evidence stays restricted (docs/datamodel-2026/15-ENTSCHEIDUNGSKREIS.md, D59).
 */

import { Link } from 'react-router-dom';
import { AnalysisDoc, DecisionDoc, DecisionStatus, NeedDoc, SystemStatus, dataText, durationText, stateText, usability } from './signals';

const WORD = { usable: 'usable', restricted: 'restricted', blocked: 'blocked' } as const;

function range(min: number | undefined, max: number | undefined): string {
  return min === undefined && max === undefined ? '' : `${min ?? ''}..${max ?? ''}`;
}

function Need({ n, system }: { n: NeedDoc; system: string }) {
  return (
    <tr>
      <td className="l">
        <Link to={`/systems/${system}/signals?focus=${encodeURIComponent(n.signal)}`} title="Shows the signal">{n.signal}</Link>
        {n.optional && <span className="wb-muted"> optional</span>}
      </td>
      <td className="l">{n.grade ?? 'clean'}</td>
      <td className="l">{durationText(n.maxAge)}</td>
      <td className="l">{(n.origins ?? []).join(', ').toLowerCase()}</td>
      <td className="l">{n.for ? n.for.join(', ') : 'all'}</td>
      <td className="l">
        {n.ok ? (
          <span className="wb-flow-status usable" title={dataText(n.found?.data)}>
            fit{n.found?.origin ? `, ${n.found.origin.toLowerCase()}` : ''}
          </span>
        ) : (
          <span className={`wb-flow-status ${n.optional ? 'restricted' : 'blocked'}`}>{n.reasons.join('; ')}</span>
        )}
      </td>
    </tr>
  );
}

function Analysis({ a }: { a: AnalysisDoc }) {
  const evidence = a.evidence ?? [];
  return (
    <li className={a.status === 'usable' ? 'ok' : a.status === 'restricted' ? 'warning' : 'error'}>
      <b>{a.title}</b> <span className={`wb-flow-status ${a.status}`}>{WORD[a.status]}</span>
      {a.kind && <span className="wb-muted"> {a.kind}</span>}
      {a.reasons.length > 0 && (
        <ul className="wb-reasons">
          {a.reasons.map((r) => (
            <li key={r}>{r}</li>
          ))}
        </ul>
      )}
      <div className="wb-muted">
        Checked for{' '}
        {a.validity.length === 0
          ? 'any values'
          : a.validity.map((v) => `${v.signal} ${range(v.min, v.max)}${v.observedMax !== undefined ? ` (holds ${range(v.observedMin, v.observedMax)})` : ''}`).join(', ')}
        .{' '}
        {evidence.length === 0
          ? 'No evidence.'
          : `Evidence: ${evidence.map((e) => `${e.what}${e.period ? `, ${e.period}` : ''}${e.synthetic ? ' (generated data)' : ''}`).join('; ')}.`}
      </div>
    </li>
  );
}

function Decision({ d, system }: { d: DecisionDoc; system: string }) {
  return (
    <section className="wb-decision" aria-label={`Decision ${d.id}`}>
      <h3>
        {d.title} <span className="wb-muted">{d.id}</span>
        {d.example && <span className="wb-flow-status restricted" title="Persons, figures and evidence of this card are invented">example</span>}
      </h3>
      {d.question && <p className="wb-question">{d.question}</p>}
      <p className="wb-muted">
        {d.responsibility?.decides && `Decided by ${d.responsibility.decides}. `}
        {d.cadence?.every && `Every ${durationText(d.cadence.every)}, for the next ${durationText(d.cadence.horizon)}. `}
        {(d.levers ?? []).length > 0 && `Levers: ${d.levers!.map((l) => `${l.signal} ${range(l.min, l.max)}`).join(', ')}. `}
        {(d.objectives ?? []).length > 0 && `Objectives: ${d.objectives!.map((o) => o.kpi ?? o.title).join(', ')}.`}
      </p>

      <h4>Signals the decision needs</h4>
      <table className="wb-table">
        <thead>
          <tr><th>signal</th><th>grade</th><th>not older than</th><th>accepted origin</th><th>needed by</th><th>today</th></tr>
        </thead>
        <tbody>
          {d.needs.map((n) => (
            <Need key={n.signal} n={n} system={system} />
          ))}
        </tbody>
      </table>

      <h4>Analyses and what they may claim</h4>
      <ul className="wb-findings">
        {d.analyses.map((a) => (
          <Analysis key={a.id} a={a} />
        ))}
      </ul>

      <h4>Alternatives</h4>
      <p>
        {d.alternatives.map((a, i) => (
          <span key={a.id}>
            {i > 0 && ', '}
            {a.title}
            {a.reference && ' (reference)'} <span className={`wb-flow-status ${a.status}`}>{WORD[a.status]}</span>
          </span>
        ))}
        . <span className="wb-muted">Comparing them over a period is not built yet.</span>
      </p>
    </section>
  );
}

export default function ModelsPlace({ status, decisions }: { status: SystemStatus | null; decisions: DecisionStatus | null }) {
  const signals = (status?.signals ?? []).filter((s) => s.state !== 'constant');
  const ready = signals.filter((s) => s.state === 'clean');
  const missing = signals.filter((s) => s.state !== 'clean');
  const cards = decisions?.decisions ?? [];
  const use = usability(decisions);
  return (
    <main className="wb-place" aria-label="Models of the system">
      <header className="wb-inv-head">
        <h2>Models</h2>
        <span className="wb-muted">
          {cards.length === 0
            ? 'no decision declared'
            : `${cards.length} ${cards.length === 1 ? 'decision' : 'decisions'}, ${use.analyses} analyses: ${use.usable} usable, ${use.restricted} restricted, ${use.blocked} blocked`}
        </span>
      </header>
      <div className="wb-inv-source">
        <span>
          A model is released for a decision, not in general. Usable: its inputs are fit and it was checked against
          measurements. Restricted: it runs, but its result shows a mechanism, not an effect. Blocked: an input is
          missing, too old or of an origin the decision does not accept.
          {decisions?.asOf && ` Age is measured against the newest data of the system (${decisions.asOf.replace('T', ' ').replace('Z', ' UTC')}).`}
        </span>
      </div>
      <div className="wb-place-body wide">
        {cards.map((d) => (
          <Decision key={d.id} d={d} system={status?.system ?? decisions!.system} />
        ))}
        {cards.length === 0 && (
          <>
            <h3>What a model could use today</h3>
            <p>
              No decision card is declared for this system. {ready.length} of {signals.length} signals have clean data.
              {ready.length > 0 ? ` Ready: ${ready.map((s) => s.id).join(', ')}.` : ''}
            </p>
            {missing.length > 0 && (
              <ul className="wb-findings">
                {missing.map((s) => (
                  <li key={s.id} className={s.state === 'open' ? 'error' : 'warning'}>
                    <b>{s.id}</b> {stateText(s)}.{' '}
                    <Link to={`/systems/${status!.system}/signals?focus=${encodeURIComponent(s.id)}`}>Show the signal</Link>
                  </li>
                ))}
              </ul>
            )}
          </>
        )}
      </div>
    </main>
  );
}
