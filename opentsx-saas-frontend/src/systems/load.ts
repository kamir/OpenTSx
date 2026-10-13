/**
 * Reads the state of the signals of a system as the signed-in account sees it: with the sources of its working copy
 * laid over the system document. A signal bound in the working copy has data only in this view.
 */

import { systemsAPI } from '../services/api';

export async function fetchSignalStatus(base: string, system: string): Promise<unknown> {
  const url = `${base.replace(/\/$/, '')}/v1/systems/${encodeURIComponent(system)}/signals`;
  const signedIn = !!localStorage.getItem('access_token');
  const mine = signedIn ? await systemsAPI.bindings(system).catch(() => []) : [];
  const res =
    mine.length === 0
      ? await fetch(url)
      : await fetch(url, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ bindings: mine.map((b) => ({ signal: b.signal, grade: b.grade, source: b.source })) }),
        });
  if (!res.ok) throw new Error(res.status === 404 ? `the server knows no system "${system}"` : `HTTP ${res.status}`);
  return res.json();
}
