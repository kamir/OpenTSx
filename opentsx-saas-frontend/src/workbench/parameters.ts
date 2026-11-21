import type { OpDef, ParamDef, Params } from "./types";

const VECTOR_LABELS: Record<string, string> = {
  every: "Sample interval [s]",
  agg: "Aggregation",
  key: "Vector grouping",
  fill: "Missing samples",
  maxAge: "Maximum age [s]",
  emit: "Emit rule",
  names: "Component names",
  lateness: "Allowed lateness [s]",
};

export function parameterLabel(op: OpDef, field: ParamDef): string {
  return op.id === "align.vector"
    ? (VECTOR_LABELS[field.key] ?? field.label)
    : field.label;
}

export function validateParameters(op: OpDef, params: Params): void {
  if (op.id === "select") {
    try {
      RegExp(String(params.pattern), "i");
    } catch {
      throw new Error("Pattern is not a valid regular expression.");
    }
  }
  for (const field of op.params) {
    const value = params[field.key];
    const label = parameterLabel(op, field);
    if (field.type === "number") {
      if (typeof value !== "number" || !Number.isFinite(value))
        throw new Error(`${label} requires a finite number.`);
      if (field.min !== undefined && value < field.min)
        throw new Error(`${label} must be at least ${field.min}.`);
    } else if (
      typeof value !== "string" ||
      (field.type === "select" && !field.options?.includes(value))
    ) {
      throw new Error(`${label} has an unsupported value.`);
    }
  }
}
