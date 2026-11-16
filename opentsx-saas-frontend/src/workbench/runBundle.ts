import packageInfo from "../../package.json";
import { parseNodes } from "./flow";
import type { FlowDoc } from "./flow";
import type { Bucket } from "./types";

export const RUN_BUNDLE_SCHEMA = "opentsx.local-analysis.v1";
export const MAX_BUNDLE_BYTES = 20 * 1024 * 1024;
const MAX_VALUES = 2_000_000;
type RecordValue = Record<string, unknown>;

export interface LocalRun {
  source: Bucket;
  result: Bucket | null;
  doc: FlowDoc;
  software: { frontend: string; buildRevision: string };
  mode: "browser-finite";
  brokerOffsets: "unavailable";
  catalogVersion: "unavailable";
}

function object(value: unknown, name: string): RecordValue {
  if (
    !value ||
    typeof value !== "object" ||
    Array.isArray(value) ||
    ArrayBuffer.isView(value)
  )
    throw new Error(`Invalid ${name} object`);
  return value as RecordValue;
}
function text(value: unknown, name: string): asserts value is string {
  if (typeof value !== "string" || value.length > 65536)
    throw new Error(`Invalid ${name} string`);
}
function number(value: unknown, name: string): asserts value is number {
  if (typeof value !== "number" || !Number.isFinite(value))
    throw new Error(`Invalid ${name} number`);
}
function stringMap(value: unknown, name: string) {
  for (const v of Object.values(object(value, name))) text(v, name);
}
function canonical(value: unknown): string {
  if (Array.isArray(value)) return `[${value.map(canonical).join(",")}]`;
  if (value && typeof value === "object")
    return `{${Object.entries(value)
      .sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0))
      .map(([key, val]) => `${JSON.stringify(key)}:${canonical(val)}`)
      .join(",")}}`;
  return JSON.stringify(value);
}
async function digest(value: unknown): Promise<string> {
  if (!globalThis.crypto?.subtle)
    throw new Error("WebCrypto SHA-256 is unavailable; use localhost or HTTPS");
  return Array.from(
    new Uint8Array(
      await crypto.subtle.digest(
        "SHA-256",
        new TextEncoder().encode(canonical(value)),
      ),
    ),
    (b) => b.toString(16).padStart(2, "0"),
  ).join("");
}
function encode(value: unknown, depth = 0): unknown {
  if (depth > 24) throw new Error("Bundle nesting limit exceeded");
  if (value instanceof Float64Array || value instanceof Uint8Array) {
    return {
      $array: value instanceof Float64Array ? "float64" : "uint8",
      values: Array.from(value, (n) =>
        Number.isNaN(n)
          ? "NaN"
          : n === Infinity
            ? "Infinity"
            : n === -Infinity
              ? "-Infinity"
              : Object.is(n, -0)
                ? "-0"
                : n,
      ),
    };
  }
  if (Array.isArray(value)) return value.map((v) => encode(v, depth + 1));
  if (value && typeof value === "object") {
    const out: RecordValue = {};
    for (const [key, val] of Object.entries(value)) {
      if (["__proto__", "prototype", "constructor", "$array"].includes(key))
        throw new Error("Invalid bundle field");
      if (val !== undefined) out[key] = encode(val, depth + 1);
    }
    return out;
  }
  if (
    value === null ||
    typeof value === "string" ||
    typeof value === "boolean" ||
    (typeof value === "number" && Number.isFinite(value))
  )
    return value;
  throw new Error("Bundle metadata must contain finite JSON values");
}
function decode(value: unknown, count: { values: number }, depth = 0): unknown {
  if (depth > 24) throw new Error("Bundle nesting limit exceeded");
  if (Array.isArray(value))
    return value.map((v) => decode(v, count, depth + 1));
  if (value && typeof value === "object") {
    const row = object(value, "encoded");
    if ("$array" in row) {
      if (
        !["float64", "uint8"].includes(String(row.$array)) ||
        !Array.isArray(row.values) ||
        Object.keys(row).length !== 2
      )
        throw new Error("Invalid encoded array");
      count.values += row.values.length;
      if (count.values > MAX_VALUES)
        throw new Error("Bundle sample limit exceeded");
      const values = row.values.map((v: unknown) => {
        if (typeof v === "number" && Number.isFinite(v)) return v;
        if (row.$array === "float64" && v === "NaN") return NaN;
        if (row.$array === "float64" && v === "Infinity") return Infinity;
        if (row.$array === "float64" && v === "-Infinity") return -Infinity;
        if (row.$array === "float64" && v === "-0") return -0;
        throw new Error("Invalid encoded sample");
      });
      if (row.$array === "uint8") {
        if (values.some((v) => !Number.isInteger(v) || v < 0 || v > 255))
          throw new Error("Invalid quality byte");
        return Uint8Array.from(values);
      }
      return Float64Array.from(values);
    }
    const out: RecordValue = {};
    for (const [key, val] of Object.entries(row)) {
      if (["__proto__", "prototype", "constructor"].includes(key))
        throw new Error("Invalid bundle field");
      out[key] = decode(val, count, depth + 1);
    }
    return out;
  }
  if (
    value === null ||
    typeof value === "string" ||
    typeof value === "boolean" ||
    (typeof value === "number" && Number.isFinite(value))
  )
    return value;
  throw new Error("Invalid bundle value");
}
function bucket(value: unknown): asserts value is Bucket {
  const b = object(value, "bucket");
  for (const key of ["name", "xLabel", "yLabel"]) text(b[key], key);
  if (
    !["time", "index", "frequency", "lag", "value", "scale"].includes(
      String(b.xKind),
    )
  )
    throw new Error("Invalid bucket axis");
  if (b.t0Ms !== undefined) number(b.t0Ms, "time origin");
  if (
    !Array.isArray(b.series) ||
    b.series.length === 0 ||
    b.series.length > 128
  )
    throw new Error("Bucket series limit exceeded");
  const ids = new Set<string>();
  for (const raw of b.series) {
    const s = object(raw, "series");
    text(s.id, "series identity");
    text(s.label, "series label");
    if (!s.id || ids.has(s.id))
      throw new Error("Duplicate or empty series identity");
    ids.add(s.id);
    for (const key of ["unit", "metric", "key", "component"])
      if (s[key] !== undefined) text(s[key], key);
    if (s.tags !== undefined) stringMap(s.tags, "series tags");
    if (
      !(s.x instanceof Float64Array) ||
      !(s.y instanceof Float64Array) ||
      s.x.length !== s.y.length ||
      s.x.length > 500000
    )
      throw new Error("Invalid sample array length");
    if (s.x.some((v) => !Number.isFinite(v)))
      throw new Error("Invalid sample time");
    for (const key of ["quality", "qualityKnown"]) {
      if (
        s[key] !== undefined &&
        (!(s[key] instanceof Uint8Array) ||
          (s[key] as Uint8Array).length !== s.x.length)
      )
        throw new Error("Invalid quality array length");
    }
    if (
      s.qualityKnown !== undefined &&
      (s.qualityKnown as Uint8Array).some((v) => v !== 0 && v !== 1)
    )
      throw new Error("Invalid quality availability");
  }
  if (b.episodes !== undefined) {
    if (!Array.isArray(b.episodes) || b.episodes.length > 100000)
      throw new Error("Episode limit exceeded");
    for (const raw of b.episodes) {
      const e = object(raw, "episode");
      text(e.seriesId, "episode series");
      text(e.episodeId, "episode identity");
      number(e.start, "episode start");
      number(e.end, "episode end");
      if (!ids.has(e.seriesId) || !(e.end > e.start))
        throw new Error("Invalid episode range or series");
      for (const key of ["strategy", "parent"])
        if (e[key] !== undefined) text(e[key], key);
      if (e.labels !== undefined) stringMap(e.labels, "episode labels");
      if (e.segmentation !== undefined) {
        const s = object(e.segmentation, "segmentation");
        if (s.strategy !== undefined) text(s.strategy, "strategy");
        if (s.anchorTs !== undefined) text(s.anchorTs, "anchor timestamp");
        if (s.params !== undefined)
          stringMap(s.params, "segmentation parameters");
      }
    }
  }
  if (b.marks !== undefined) {
    if (!Array.isArray(b.marks) || b.marks.length > 100000)
      throw new Error("Mark limit exceeded");
    for (const raw of b.marks) {
      const m = object(raw, "mark");
      for (const key of ["kind", "type", "scope"]) text(m[key], key);
      number(m.from, "mark start");
      number(m.to, "mark end");
      if (m.to < m.from) throw new Error("Invalid mark range");
    }
  }
  if (b.note !== undefined) text(b.note, "bucket note");
  if (b.vector !== undefined) {
    const v = object(b.vector, "vector");
    number(v.clock, "vector clock");
    text(v.keyBy, "vector key");
    if (
      v.clock <= 0 ||
      !Array.isArray(v.components) ||
      v.components.length > 128
    )
      throw new Error("Invalid vector");
    v.components.forEach((c) => text(c, "component"));
  }
}
function flow(value: unknown): asserts value is FlowDoc {
  const d = object(value, "flow");
  for (const key of ["track", "domain", "sourceTopic"]) text(d[key], key);
  if (!Array.isArray(d.nodes) || d.nodes.length > 500)
    throw new Error("Invalid flow graph");
  for (const raw of d.nodes) {
    const n = object(raw, "node");
    for (const value of Object.values(object(n.params, "parameters")))
      if (
        typeof value !== "string" &&
        (typeof value !== "number" || !Number.isFinite(value))
      )
        throw new Error("Invalid graph parameter");
    if (
      n.inputs !== undefined &&
      (!Array.isArray(n.inputs) || n.inputs.some((v) => typeof v !== "string"))
    )
      throw new Error("Invalid graph inputs");
    if (n.input !== null && typeof n.input !== "string")
      throw new Error("Invalid graph input");
    if (n.pos !== undefined) {
      const p = object(n.pos, "position");
      number(p.x, "position x");
      number(p.y, "position y");
    }
    if (n.sinkTopic !== undefined) text(n.sinkTopic, "sink topic");
  }
  if (!parseNodes(d.nodes))
    throw new Error(
      "Invalid flow graph: unknown operator, missing input or cycle",
    );
  if (d.sourceSeries !== undefined) {
    if (!Array.isArray(d.sourceSeries) || d.sourceSeries.length > 128)
      throw new Error("Invalid source series");
    d.sourceSeries.forEach((s) => text(s, "source series"));
  }
  if (
    d.savedId !== undefined &&
    d.savedId !== null &&
    (!Number.isSafeInteger(d.savedId) || Number(d.savedId) <= 0)
  )
    throw new Error("Invalid saved flow identifier");
  if (d.context !== undefined && d.context !== null) {
    const c = object(d.context, "signal context");
    for (const key of ["system", "systemTitle", "signal", "grade"])
      text(c[key], key);
    if (typeof c.synthetic !== "boolean")
      throw new Error("Invalid synthetic provenance");
    const e = object(c.expect, "signal expectation");
    for (const key of ["min", "max"])
      if (e[key] !== undefined) number(e[key], key);
    for (const key of ["unit", "interval"])
      if (e[key] !== undefined) text(e[key], key);
    if (c.origin !== undefined) text(c.origin, "origin");
    if (c.target !== undefined) {
      const t = object(c.target, "target");
      for (const key of ["signal", "title", "origin"]) text(t[key], key);
      if (t.metric !== undefined) text(t.metric, "metric");
    }
  }
}
function validate(value: unknown): asserts value is LocalRun {
  const run = object(value, "run");
  if (
    run.mode !== "browser-finite" ||
    run.brokerOffsets !== "unavailable" ||
    run.catalogVersion !== "unavailable"
  )
    throw new Error("Unsupported run capture mode");
  const software = object(run.software, "software");
  text(software.frontend, "frontend version");
  text(software.buildRevision, "build revision");
  bucket(run.source);
  if (run.result !== null) bucket(run.result);
  flow(run.doc);
}
export async function createRunBundle(
  source: Bucket,
  result: Bucket | null,
  doc: FlowDoc,
): Promise<string> {
  const run: LocalRun = {
    source,
    result,
    doc,
    software: { frontend: packageInfo.version, buildRevision: "unknown" },
    mode: "browser-finite",
    brokerOffsets: "unavailable",
    catalogVersion: "unavailable",
  };
  validate(run);
  const payload = encode(run);
  decode(payload, { values: 0 });
  const output = JSON.stringify(
    {
      schema: RUN_BUNDLE_SCHEMA,
      payload,
      integrity: { algorithm: "SHA-256", value: await digest(payload) },
    },
    null,
    2,
  );
  if (new TextEncoder().encode(output).length > MAX_BUNDLE_BYTES)
    throw new Error("Bundle size limit exceeded");
  return output;
}
export async function parseRunBundle(input: string): Promise<LocalRun> {
  if (
    input.length > MAX_BUNDLE_BYTES ||
    new TextEncoder().encode(input).length > MAX_BUNDLE_BYTES
  )
    throw new Error("Bundle size limit exceeded");
  let raw: unknown;
  try {
    raw = JSON.parse(input);
  } catch {
    throw new Error("Invalid bundle JSON");
  }
  const record = object(raw, "bundle");
  if (record.schema !== RUN_BUNDLE_SCHEMA)
    throw new Error("Unsupported bundle schema");
  const integrity = object(record.integrity, "integrity");
  if (
    integrity.algorithm !== "SHA-256" ||
    typeof integrity.value !== "string" ||
    !/^[0-9a-f]{64}$/.test(integrity.value) ||
    (await digest(record.payload)) !== integrity.value
  )
    throw new Error("Bundle integrity check failed");
  const run = decode(record.payload, { values: 0 });
  validate(run);
  return run;
}
