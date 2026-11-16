import { webcrypto } from "node:crypto";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { FlowDoc } from "./flow";
import type { Bucket } from "./types";

interface BundleModule {
  createRunBundle: (
    source: Bucket,
    result: Bucket | null,
    doc: FlowDoc,
  ) => Promise<string>;
  parseRunBundle: (
    text: string,
  ) => Promise<{ source: Bucket; result: Bucket | null; doc: FlowDoc }>;
}
const modules = import.meta.glob("./runBundle.ts");
async function utility(): Promise<BundleModule> {
  expect(
    modules["./runBundle.ts"],
    "Run bundle utility is available",
  ).toBeTypeOf("function");
  return (await modules["./runBundle.ts"]()) as BundleModule;
}
const doc: FlowDoc = {
  track: "Energy",
  domain: "energy",
  sourceTopic: "tsx.energy.episodes.v2",
  sourceSeries: ["price"],
  nodes: [
    { id: "source", op: "source", input: null, label: "prices", params: {} },
    {
      id: "n1",
      op: "shuffle",
      input: "source",
      label: "shuffled",
      params: { seed: 17 },
    },
  ],
};
const source: Bucket = {
  name: "prices",
  xKind: "time",
  xLabel: "seconds",
  yLabel: "EUR/MWh",
  t0Ms: 1743292800000,
  series: [
    {
      id: "price",
      label: "Day-ahead",
      metric: "market.price",
      unit: "EUR/MWh",
      tags: { area: "DE" },
      x: Float64Array.of(0, 900, 1800, 2700),
      y: Float64Array.of(-0, NaN, Infinity, -Infinity),
      quality: Uint8Array.of(0, 1, 2, 255),
      qualityKnown: Uint8Array.of(1, 0, 1, 1),
    },
  ],
  episodes: [
    {
      seriesId: "price",
      episodeId: "e1",
      start: 0,
      end: 3600,
      strategy: "FIXED_DURATION",
      segmentation: {
        strategy: "FIXED_DURATION",
        params: { duration: "PT1H" },
        anchorTs: "2025-03-30T00:00:00Z",
      },
      labels: { origin: "REPORTED" },
    },
  ],
  marks: [
    { kind: "operation", type: "change", from: 900, to: 1800, scope: "price" },
  ],
};
beforeEach(() => vi.stubGlobal("crypto", webcrypto));

describe("local analysis run bundles", () => {
  it("round trips exact input/output arrays, identities, UTC origin, quality, graph and seed", async () => {
    const u = await utility();
    const text = await u.createRunBundle(source, source, doc);
    const parsed = await u.parseRunBundle(text);
    expect(parsed.source).toEqual(source);
    expect(Object.is(parsed.source.series[0].y[0], -0)).toBe(true);
    expect(parsed.result).toEqual(source);
    expect(parsed.doc).toEqual(doc);
    expect(text).toContain("browser-finite");
    expect(text).toContain("unknown");
    expect(text).toContain("unavailable");
  });
  it("rejects content tampering before restoring a graph", async () => {
    const u = await utility();
    const raw = JSON.parse(await u.createRunBundle(source, null, doc));
    raw.payload.doc.track = "changed";
    await expect(u.parseRunBundle(JSON.stringify(raw))).rejects.toThrow(
      /integrity/i,
    );
  });
  it("rejects an unknown schema and malformed JSON", async () => {
    const u = await utility();
    const raw = JSON.parse(await u.createRunBundle(source, null, doc));
    raw.schema = "other";
    await expect(u.parseRunBundle(JSON.stringify(raw))).rejects.toThrow(
      /schema/i,
    );
    await expect(u.parseRunBundle("{")).rejects.toThrow(/JSON/i);
  });
  it("rejects unequal samples, quality lengths and duplicate series identity at export", async () => {
    const u = await utility();
    await expect(
      u.createRunBundle(
        { ...source, series: [{ ...source.series[0], x: Float64Array.of(0) }] },
        null,
        doc,
      ),
    ).rejects.toThrow(/length/i);
    await expect(
      u.createRunBundle(
        {
          ...source,
          series: [{ ...source.series[0], quality: Uint8Array.of(0) }],
        },
        null,
        doc,
      ),
    ).rejects.toThrow(/quality/i);
    await expect(
      u.createRunBundle(
        { ...source, series: [source.series[0], source.series[0]] },
        null,
        doc,
      ),
    ).rejects.toThrow(/identity/i);
  });
  it("rejects unsupported operators, cycles and invalid parameter types", async () => {
    const u = await utility();
    await expect(
      u.createRunBundle(source, null, {
        ...doc,
        nodes: [{ ...doc.nodes[0], op: "code" }],
      }),
    ).rejects.toThrow(/graph/i);
    await expect(
      u.createRunBundle(source, null, {
        ...doc,
        nodes: [doc.nodes[0], { ...doc.nodes[1], input: "n1" }],
      }),
    ).rejects.toThrow(/graph/i);
    await expect(
      u.createRunBundle(source, null, {
        ...doc,
        nodes: [doc.nodes[0], { ...doc.nodes[1], params: { seed: NaN } }],
      }),
    ).rejects.toThrow(/parameter/i);
  });
  it("rejects oversized imports and invalid episode references", async () => {
    const u = await utility();
    await expect(
      u.parseRunBundle(" ".repeat(20 * 1024 * 1024 + 1)),
    ).rejects.toThrow(/limit/i);
    await expect(
      u.createRunBundle(
        {
          ...source,
          episodes: [
            { seriesId: "missing", episodeId: "e1", start: 0, end: 1 },
          ],
        },
        null,
        doc,
      ),
    ).rejects.toThrow(/episode/i);
  });
});
