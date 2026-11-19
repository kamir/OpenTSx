/**
 * What a page does when the Flow Composer opens, creates or deletes flows. The Flow Composer page, the systems page and
 * the workbench share it.
 */

import { useFlow } from './flow';

/**
 * Opens the first flow through `here` and the others in tabs of their own. The stored flow replaces what the workbench
 * holds of it. Returns how many of the other flows the browser kept from opening.
 */
export function openFlows(ids: number[], here: (id: number) => void): number {
  const [first, ...others] = ids;
  const blocked = others.filter((id) => !window.open(`/workbench?flow=${id}`, '_blank')).length;
  if (useFlow.getState().savedId === first) useFlow.getState().setSavedId(null);
  here(first);
  return blocked;
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
