/**
 * What a page does when the Flow Composer opens, creates or deletes flows. The Flow Composer page, the systems page and
 * the workbench share it.
 */

import { useFlow } from './flow';

/**
 * Opens the others in tabs of their own and the first flow through `here`; the stored flow replaces what the workbench
 * holds of it. With `stayWhenKept`, a page that stays when the browser kept a tab from opening, the first one is not
 * opened either and the workbench keeps what it holds. Returns how many of the other flows the browser kept from opening.
 */
export function openFlows(ids: number[], here: (id: number) => void, stayWhenKept = false): number {
  const [first, ...others] = ids;
  const blocked = others.filter((id) => !window.open(`/workbench?flow=${id}`, '_blank')).length;
  if (blocked > 0 && stayWhenKept) return blocked;
  if (useFlow.getState().savedId === first) useFlow.getState().setSavedId(null);
  here(first);
  return blocked;
}

/** What a page says when the browser kept `blocked` of the flows from opening; `openedFirst` when it opened the first one. */
export function keptNotice(blocked: number, openedFirst: boolean): string {
  return `The browser kept ${blocked} of the flows from opening in tabs of their own${
    openedFirst ? '' : ', so this page stayed and did not open the first one either'
  }. Allow pop-ups for this page, or open them one at a time.`;
}

/** Empties the workbench for a new flow. */
export function newFlow(): void {
  useFlow.getState().clear();
}

/**
 * Lets go of deleted flows. When the workbench holds one of them, `keep` leaves its nodes there as an unsaved flow and
 * without it the workbench is emptied. Returns the id of the flow the workbench held, null when it held none of them.
 */
export function forgetFlows(ids: number[], keep: boolean): number | null {
  const id = useFlow.getState().savedId;
  if (id === null || !ids.includes(id)) return null;
  if (keep) useFlow.getState().setSavedId(null);
  else useFlow.getState().clear();
  return id;
}
