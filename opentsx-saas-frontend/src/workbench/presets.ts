import { durationSeconds } from "./inventory";
import { Params, SignalContext } from "./types";

/**
 * The step of the shipped clean flows of one-second channels
 * (systems/flows/turbine-power-clean.flow.yaml, wind-speed-clean.flow.yaml);
 * a resample preset is never finer.
 */
const MIN_RESAMPLE_SECONDS = 60;

/**
 * Parameters the operator `opId` starts from when the workbench is opened for a
 * signal (docs/datamodel-2026/SYSTEM-DOCUMENT-SPEC.md, section 8.3).
 */
export function signalPresets(
  opId: string,
  context: SignalContext | null,
): Params {
  if (!context) return {};
  const { min, max, interval } = context.expect;
  if (opId === "dq.plausible-range")
    return {
      ...(min !== undefined ? { min } : {}),
      ...(max !== undefined ? { max } : {}),
    };
  if (opId !== "resample") return {};
  if (interval === undefined) return { every: MIN_RESAMPLE_SECONDS };
  const seconds = durationSeconds(interval);
  return {
    every: Number.isNaN(seconds) ? "" : Math.max(MIN_RESAMPLE_SECONDS, seconds),
  };
}
