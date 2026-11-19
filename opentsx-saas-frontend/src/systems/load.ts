/**
 * Reads the state of the signals of a system as the signed-in account sees it: with the sources of its working copy
 * laid over the system document. A signal bound in the working copy has data only in this view.
 */

import { SignalBindingDoc, errorMessage, fetchJson, hasAccessToken, systemsAPI } from '../services/api';

/** The state document, and a note when the working copy could not be read and the system document is shown alone. */
export async function fetchSignalStatus(base: string, system: string): Promise<{ doc: unknown; note: string | null }> {
  const url = `${base.replace(/\/$/, '')}/v1/systems/${encodeURIComponent(system)}/signals`;
  const signedIn = hasAccessToken();
  let mine: SignalBindingDoc[] = [];
  let note: string | null = null;
  if (signedIn) {
    try {
      mine = await systemsAPI.bindings(system);
    } catch (e) {
      note = `your working copy could not be read (${errorMessage(e)}); showing the system document.`;
    }
  }
  const doc =
    mine.length === 0
      ? await fetchJson(url)
      : await fetchJson(url, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ bindings: mine.map((b) => ({ signal: b.signal, grade: b.grade, source: b.source })) }),
        });
  return { doc, note };
}
