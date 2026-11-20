import { resultId, type ResultParam } from "./resultIds";

export interface AnalysisScalar {
  name: string;
  value: number;
  stdError: number | null;
  unit: string | null;
  fitFrom: number | null;
  fitTo: number | null;
}
export interface AnalysisCurve {
  name: string;
  xName: string;
  xUnit: string | null;
  x: number[];
  y: number[];
  unit: string | null;
}
export interface AnalysisMatrix {
  name: string;
  rowKeys: string[];
  colKeys: string[];
  values: number[];
  unit: string | null;
}
export interface InspectedAnalysis {
  resultId: string;
  method: string;
  methodVersion: string;
  inputEpisodeIds: string[];
  inputSeriesIds: string[];
  inputDigests: string[];
  tStart: string;
  tEnd: string;
  scalars: AnalysisScalar[];
  curves: AnalysisCurve[];
  matrices: AnalysisMatrix[];
  labels: Record<string, string>;
  provenance: {
    source: string;
    producer: string;
    createdAt: string;
    pipeline: string | null;
    parentEpisodeId: string | null;
  };
  json: string;
}

function object(v: unknown, field: string): Record<string, unknown> {
  if (v === null || typeof v !== "object" || Array.isArray(v))
    throw new Error(`${field} must be an object.`);
  return v as Record<string, unknown>;
}
function text(v: unknown, field: string): string {
  if (typeof v !== "string" || !v || v.length > 4096)
    throw new Error(
      `${field} must be a nonempty string of at most 4096 characters.`,
    );
  return v;
}
function optionalText(v: unknown, field: string): string | null {
  return v === undefined || v === null ? null : text(v, field);
}
function list(v: unknown, field: string, max: number): unknown[] {
  if (!Array.isArray(v) || v.length > max)
    throw new Error(`${field} must be an array with at most ${max} items.`);
  return v;
}
function strings(v: unknown, field: string, max: number): string[] {
  return list(v, field, max).map((x) => text(x, field));
}
function number(v: unknown, field: string): number {
  if (typeof v === "number" && Number.isFinite(v)) return v;
  if (v === "NaN") return NaN;
  if (v === "Infinity") return Infinity;
  if (v === "-Infinity") return -Infinity;
  throw new Error(
    `${field} must be a number or an explicit NaN/Infinity string.`,
  );
}
function optionalNumber(v: unknown, field: string): number | null {
  return v === undefined || v === null ? null : number(v, field);
}
function numbers(v: unknown, field: string, max: number): number[] {
  return list(v, field, max).map((x) => number(x, field));
}
function integer(v: unknown, field: string): bigint {
  if (typeof v === "number" && !Number.isSafeInteger(v))
    throw new Error(`${field} must be a safe integer or a decimal string.`);
  if (
    (typeof v !== "number" && typeof v !== "string") ||
    !/^-?\d+$/.test(String(v))
  )
    throw new Error(`${field} must be an integer or a decimal string.`);
  const out = BigInt(v);
  if (out < -(1n << 63n) || out > (1n << 63n) - 1n)
    throw new Error(`${field} is outside the signed 64-bit range.`);
  return out;
}
function params(v: unknown): Record<string, ResultParam> {
  const entries = Object.entries(object(v, "params"));
  if (entries.length > 100)
    throw new Error("params has more than 100 entries.");
  return Object.fromEntries(
    entries.map(([key, raw]) => {
      text(key, "parameter name");
      if (typeof raw === "string") return [key, raw];
      if (Array.isArray(raw)) return [key, numbers(raw, key, 2000)];
      if (typeof raw === "number")
        throw new Error(
          `Use a typed long or double for parameter ${key}; a JSON number does not retain its Avro type.`,
        );
      const tagged = object(raw, `parameter ${key}`);
      const keys = Object.keys(tagged);
      if (keys.length !== 1)
        throw new Error(
          `Parameter ${key} needs one type tag: long, double, string or array.`,
        );
      switch (keys[0]) {
        case "long":
          return [key, integer(tagged.long, key)];
        case "double":
          return [key, number(tagged.double, key)];
        case "string":
          return [key, text(tagged.string, key)];
        case "array":
          return [key, numbers(tagged.array, key, 2000)];
        default:
          throw new Error(`Parameter ${key} has an unknown type tag.`);
      }
    }),
  );
}
function labels(v: unknown): Record<string, string> {
  const entries = Object.entries(object(v ?? {}, "labels"));
  if (entries.length > 100)
    throw new Error("labels has more than 100 entries.");
  return Object.fromEntries(
    entries.map(([k, v]) => [text(k, "label name"), text(v, "label value")]),
  );
}

export function inspectAnalysisJson(json: string): InspectedAnalysis {
  if (new TextEncoder().encode(json).length > 1048576)
    throw new Error("Analysis result JSON exceeds 1 MiB.");
  let parsed: unknown;
  try {
    parsed = JSON.parse(json);
  } catch {
    throw new Error("Invalid JSON. Supply one AnalysisResult record.");
  }
  const r = object(parsed, "AnalysisResult");
  const method = text(r.method, "method");
  const methodVersion = text(r.methodVersion, "methodVersion");
  const inputEpisodeIds = strings(r.inputEpisodeIds, "inputEpisodeIds", 1000);
  const inputSeriesIds = strings(r.inputSeriesIds, "inputSeriesIds", 1000);
  if (inputSeriesIds.length !== inputEpisodeIds.length)
    throw new Error(
      "inputEpisodeIds and inputSeriesIds must have the same length.",
    );
  const inputDigests = strings(r.inputDigests ?? [], "inputDigests", 1000);
  const id = resultId(
    method,
    methodVersion,
    params(r.params),
    inputEpisodeIds,
    inputDigests,
  );
  if (r.resultId !== id)
    throw new Error(
      `resultId does not match method, version, typed parameters, input episodes and their digests (expected ${id}).`,
    );
  const tStart = integer(r.tStart, "tStart");
  const tEnd = integer(r.tEnd, "tEnd");
  if (tStart >= tEnd) throw new Error("tStart must be before tEnd.");
  const scalars = list(r.scalars ?? [], "scalars", 100).map((v) => {
    const s = object(v, "scalar");
    return {
      name: text(s.name, "scalar name"),
      value: number(s.value, "scalar value"),
      stdError: optionalNumber(s.stdError, "stdError"),
      unit: optionalText(s.unit, "scalar unit"),
      fitFrom: optionalNumber(s.fitFrom, "fitFrom"),
      fitTo: optionalNumber(s.fitTo, "fitTo"),
    };
  });
  const curves = list(r.curves ?? [], "curves", 8).map((v) => {
    const c = object(v, "curve");
    const x = numbers(c.x, "curve x", 2000);
    const y = numbers(c.y, "curve y", 2000);
    if (x.length !== y.length)
      throw new Error("curve x and y must have the same length.");
    return {
      name: text(c.name, "curve name"),
      xName: text(c.xName, "curve xName"),
      xUnit: optionalText(c.xUnit, "curve xUnit"),
      x,
      y,
      unit: optionalText(c.unit, "curve unit"),
    };
  });
  const matrices = list(r.matrices ?? [], "matrices", 4).map((v) => {
    const m = object(v, "matrix");
    const rowKeys = strings(m.rowKeys, "matrix rowKeys", 100);
    const colKeys = strings(m.colKeys, "matrix colKeys", 100);
    const values = numbers(m.values, "matrix values", 10000);
    if (values.length !== rowKeys.length * colKeys.length)
      throw new Error(
        "matrix values must match rowKeys.length * colKeys.length.",
      );
    if (
      new Set(rowKeys).size !== rowKeys.length ||
      new Set(colKeys).size !== colKeys.length
    )
      throw new Error("Matrix row and column keys must be unique.");
    return {
      name: text(m.name, "matrix name"),
      rowKeys,
      colKeys,
      values,
      unit: optionalText(m.unit, "matrix unit"),
    };
  });
  const p = object(r.provenance, "provenance");
  return {
    resultId: id,
    method,
    methodVersion,
    inputEpisodeIds,
    inputSeriesIds,
    inputDigests,
    tStart: String(tStart),
    tEnd: String(tEnd),
    scalars,
    curves,
    matrices,
    labels: labels(r.labels),
    provenance: {
      source: text(p.source, "provenance source"),
      producer: text(p.producer, "provenance producer"),
      createdAt: String(integer(p.createdAt, "provenance createdAt")),
      pipeline: optionalText(p.pipeline, "provenance pipeline"),
      parentEpisodeId: optionalText(
        p.parentEpisodeId,
        "provenance parentEpisodeId",
      ),
    },
    json,
  };
}
