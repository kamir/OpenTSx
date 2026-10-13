/**
 * The place "Models". Models are a later delivery stage; until then the place says what a model of this system
 * could use today: the signals with clean data, and which ones are still missing.
 */

import { Link } from 'react-router-dom';
import { SystemStatus, stateText } from './signals';

export default function ModelsPlace({ status }: { status: SystemStatus | null }) {
  const signals = (status?.signals ?? []).filter((s) => s.state !== 'constant');
  const ready = signals.filter((s) => s.state === 'clean');
  const missing = signals.filter((s) => s.state !== 'clean');
  return (
    <main className="wb-place" aria-label="Models of the system">
      <header className="wb-inv-head">
        <h2>Models</h2>
        <span className="wb-muted">none declared</span>
      </header>
      <div className="wb-inv-source">
        <span>
          A model takes clean signals only, or features computed from them. No model is declared for this system yet;
          declaring and running models is a later delivery stage (L5).
        </span>
      </div>
      <div className="wb-place-body">
        <h3>What a model could use today</h3>
        <p>
          {ready.length} of {signals.length} signals have clean data.
          {ready.length > 0 ? ` Ready: ${ready.map((s) => s.id).join(', ')}.` : ''}
        </p>
        {missing.length > 0 && (
          <>
            <h3>Still missing</h3>
            <ul className="wb-findings">
              {missing.map((s) => (
                <li key={s.id} className={s.state === 'open' ? 'error' : 'warning'}>
                  <b>{s.id}</b> {stateText(s)}.{' '}
                  <Link to={`/systems/${status!.system}/signals?focus=${encodeURIComponent(s.id)}`}>Show the signal</Link>
                </li>
              ))}
            </ul>
          </>
        )}
      </div>
    </main>
  );
}
