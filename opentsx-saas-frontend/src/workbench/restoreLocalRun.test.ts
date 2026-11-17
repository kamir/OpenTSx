import { beforeEach, describe, expect, it } from "vitest";
import { useFlow } from "./flow";
import type { FlowDoc } from "./flow";
import type { Bucket } from "./types";
const source: Bucket = {
  name: "Original data",
  xKind: "time",
  xLabel: "t [s]",
  yLabel: "MW",
  t0Ms: 0,
  series: [
    {
      id: "a",
      label: "A",
      unit: "MW",
      x: Float64Array.from([0, 1]),
      y: Float64Array.from([1, 2]),
    },
  ],
};
function doc(): FlowDoc {
  return {
    track: "demo",
    domain: "lab",
    sourceTopic: "tsx.lab.episodes.v2",
    sourceSeries: ["a"],
    savedId: 42,
    nodes: [
      {
        id: "source",
        op: "source",
        params: {},
        input: null,
        label: "Custom source name",
      },
    ],
    context: {
      system: "energy",
      systemTitle: "Energy",
      signal: "power",
      grade: "raw",
      expect: { unit: "MW" },
      synthetic: true,
    },
  };
}
async function restore() {
  const modules = import.meta.glob<{
    restoreLocalRun: (source: Bucket, doc: FlowDoc) => void;
  }>("./restoreLocalRun.ts");
  expect(modules["./restoreLocalRun.ts"]).toBeDefined();
  return (await modules["./restoreLocalRun.ts"]()).restoreLocalRun;
}
beforeEach(() => useFlow.getState().clear());
describe("restore a finite local run", () => {
  it("restores a source-only graph with its context and exact node label as unsaved local work", async () => {
    (await restore())(source, doc());
    const s = useFlow.getState();
    expect(s.nodes).toEqual(doc().nodes);
    expect(s.context).toEqual(doc().context);
    expect(s.savedId).toBeNull();
    expect(s.sourceSeries).toEqual(["a"]);
    expect(s.source).toBe(source);
  });
  it("opens the source, without evaluating an imported operator or deploying it", async () => {
    const d = doc();
    d.nodes.push({
      id: "n1",
      op: "pair.es",
      params: { shuffles: 1e15 },
      input: "source",
      label: "Review this step",
    });
    (await restore())(source, d);
    expect(useFlow.getState().selectedId).toBe("source");
    expect(useFlow.getState().nodes).toEqual(d.nodes);
    expect(useFlow.getState().applyOp("diff")).toBe("n2");
  });
  it("rejects an invalid graph before discarding current work", async () => {
    useFlow.getState().loadBucket(source, { kind: "file", detail: "current" });
    const d = doc();
    d.nodes[0].input = "missing";
    const fn = await restore();
    expect(() => fn(source, d)).toThrow();
    expect(useFlow.getState().source).toBe(source);
  });
});
