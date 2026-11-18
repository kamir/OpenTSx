export type WorkbenchMode = "engineer" | "data" | "legacy";
export interface Presentation {
  mode: WorkbenchMode;
  engineer: "instrument" | "diagnostics";
  data: "pipeline" | "notebook";
}

export const PRESENTATION_KEY = "opentsx.workbench.presentation.v1";

export function readPresentation(override: string | null): {
  value: Presentation;
  error: string | null;
} {
  let value: Presentation = {
    mode: "engineer",
    engineer: "instrument",
    data: "pipeline",
  };
  let error: string | null = null;
  try {
    const raw = localStorage.getItem(PRESENTATION_KEY);
    if (raw !== null) {
      const stored = JSON.parse(raw);
      if (
        !stored ||
        !["engineer", "data", "legacy"].includes(stored.mode) ||
        !["instrument", "diagnostics"].includes(stored.engineer) ||
        !["pipeline", "notebook"].includes(stored.data)
      ) {
        throw new Error("unsupported presentation preference");
      }
      value = {
        mode: stored.mode,
        engineer: stored.engineer,
        data: stored.data,
      };
    }
  } catch {
    error =
      "The saved presentation preference could not be read. Signal Engineer defaults are active; working data is unchanged.";
  }
  if (override !== null) {
    if (override === "engineer" || override === "data" || override === "legacy")
      value.mode = override;
    else
      error =
        "The requested presentation mode is invalid. Choose Signal Engineer, Data Worker or Legacy.";
  }
  return { value, error };
}
