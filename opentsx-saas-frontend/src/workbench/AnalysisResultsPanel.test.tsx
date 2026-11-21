import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import type { ComponentType } from "react";
import { resultId } from "./resultIds";

afterEach(cleanup);
const EPISODES = ["episode-A", "episode-B"];
const DIGESTS = ["a".repeat(64), "b".repeat(64)];
function record() {
  return {
    resultId: resultId("es", "1.0.0", { seed: 7n }, EPISODES, DIGESTS),
    method: "es",
    methodVersion: "1.0.0",
    params: { seed: { long: "7" } },
    inputEpisodeIds: EPISODES,
    inputSeriesIds: ["wind", "load"],
    inputDigests: DIGESTS,
    tStart: 1700000000000000,
    tEnd: 1700000010000000,
    scalars: [
      {
        name: "Q",
        value: 0.8,
        stdError: 0.02,
        unit: null,
        fitFrom: null,
        fitTo: null,
      },
    ],
    curves: [
      {
        name: "fluctuation",
        xName: "scale",
        xUnit: "samples",
        unit: "V",
        x: [16, 32, 64],
        y: [1, 2, 4],
      },
    ],
    matrices: [
      {
        name: "Q_matrix",
        rowKeys: ["wind", "load"],
        colKeys: ["wind", "load"],
        values: [1, 0.8, 0.8, 1],
        unit: null,
      },
    ],
    labels: { experiment: "demo" },
    provenance: {
      source: "notebook:demo",
      producer: "opentsx/1.0.0",
      createdAt: 1700000020000000,
      pipeline: null,
      parentEpisodeId: null,
    },
  };
}
async function show() {
  const files = import.meta.glob("./AnalysisResultsPanel.tsx");
  const load = files["./AnalysisResultsPanel.tsx"];
  if (!load) throw new Error("The typed analysis result viewer is missing.");
  const module = (await load()) as { default: ComponentType };
  return render(<module.default />);
}
function load(value: unknown) {
  fireEvent.change(screen.getByLabelText("Analysis result JSON"), {
    target: {
      value: typeof value === "string" ? value : JSON.stringify(value),
    },
  });
  fireEvent.click(screen.getByRole("button", { name: "Inspect result JSON" }));
}

describe("AnalysisResultsPanel", () => {
  it("reads scalar, non-time curve and row-major matrix with verified identity", async () => {
    await show();
    load(record());
    expect(screen.getByText(/and their content digests/)).toBeInTheDocument();
    expect(screen.getByText(DIGESTS[1])).toBeInTheDocument();
    expect(screen.getByRole("region", { name: "Scalar Q" })).toHaveTextContent(
      "0.8",
    );
    expect(screen.getByRole("region", { name: "Scalar Q" })).toHaveTextContent(
      "Standard error: 0.02",
    );
    expect(
      screen.getByRole("img", { name: "fluctuation by scale [samples]" }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("table", { name: "Matrix Q_matrix" }),
    ).toHaveTextContent("0.8");
    expect(screen.getByText("notebook:demo")).toBeInTheDocument();
    expect(screen.getByText("episode-A")).toBeInTheDocument();
    expect(
      screen.getByRole("link", { name: "Download inspected result JSON" }),
    ).toBeInTheDocument();
  });

  it("reads the shared v2.1 and v2.2 AnalysisResult vector records with their existing identities", async () => {
    await show();
    const vectorDir = resolve(
      process.cwd(),
      "../opentsx-model/src/test/resources/testvectors",
    );
    const wire = readFileSync(resolve(vectorDir, "wire-format.json"), "utf8");
    const section = wire.indexOf('"analysisResults"');
    expect(section).toBeGreaterThan(0);
    const current = JSON.parse(
      readFileSync(resolve(vectorDir, "analysis-result-wire.json"), "utf8"),
    );
    const vectors = [
      ...JSON.parse("{" + wire.slice(section)).analysisResults,
      ...current.analysisResults,
    ];
    expect(vectors).toHaveLength(4);
    for (const vector of vectors) {
      const s = vector.spec;
      load({
        ...s,
        resultId: vector.resultId,
        tStart: s.tStartUs,
        tEnd: s.tEndUs,
        provenance: {
          source: s.source,
          producer: s.producer,
          createdAt: s.createdAtUs,
          pipeline: s.pipeline,
          parentEpisodeId: null,
        },
      });
      expect(screen.queryByRole("alert")).not.toBeInTheDocument();
      expect(screen.getByText(vector.resultId)).toBeInTheDocument();
    }
    expect(
      screen.getByRole("table", { name: "Matrix q_directed" }),
    ).toHaveTextContent("Undefined");
  });

  it("reads a record written before v2.2 without content digests", async () => {
    await show();
    const r: Record<string, unknown> = {
      ...record(),
      resultId: resultId("es", "1.0.0", { seed: 7n }, EPISODES, []),
    };
    delete r.inputDigests;
    load(r);
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(
      screen.getByText(/does not bind the input contents/),
    ).toBeInTheDocument();
    expect(
      screen.getAllByText("Not recorded (written before v2.2)"),
    ).toHaveLength(2);
  });

  it("rejects a record whose input digests changed after its id was computed", async () => {
    await show();
    load({ ...record(), inputDigests: ["c".repeat(64), DIGESTS[1]] });
    expect(screen.getByRole("alert")).toHaveTextContent(
      "resultId does not match",
    );
    load({ ...record(), inputDigests: [DIGESTS[0]] });
    expect(screen.getByRole("alert")).toHaveTextContent(
      "1 inputDigests for 2 inputEpisodeIds.",
    );
  });

  it("rejects an identity mismatch and clears an earlier result", async () => {
    await show();
    load(record());
    load({ ...record(), resultId: "0000000000000000" });
    expect(screen.getByRole("alert")).toHaveTextContent(
      "resultId does not match",
    );
    expect(
      screen.queryByRole("region", { name: "Scalar Q" }),
    ).not.toBeInTheDocument();
  });

  it("rejects ambiguous numeric parameter types", async () => {
    await show();
    load({ ...record(), params: { seed: 7 } });
    expect(screen.getByRole("alert")).toHaveTextContent(
      "Use a typed long or double",
    );
  });

  it("rejects malformed matrix dimensions and mismatched curve axes", async () => {
    await show();
    const r = record();
    r.matrices[0].values = [1];
    load(r);
    expect(screen.getByRole("alert")).toHaveTextContent("matrix values");
    const c = record();
    c.curves[0].y = [1];
    load(c);
    expect(screen.getByRole("alert")).toHaveTextContent("curve x and y");
  });

  it("rejects invalid input lists, reversed time ranges and unsafe timestamps", async () => {
    await show();
    load({ ...record(), inputSeriesIds: ["wind"] });
    expect(screen.getByRole("alert")).toHaveTextContent("same length");
    load({ ...record(), tEnd: 1700000000000000 });
    expect(screen.getByRole("alert")).toHaveTextContent(
      "tStart must be before tEnd",
    );
    load({ ...record(), tStart: 9007199254740992 });
    expect(screen.getByRole("alert")).toHaveTextContent("safe integer");
  });

  it("rejects oversized and invalid JSON explicitly", async () => {
    await show();
    load(" ".repeat(1048577));
    expect(screen.getByRole("alert")).toHaveTextContent("1 MiB");
    load("{");
    expect(screen.getByRole("alert")).toHaveTextContent("Invalid JSON");
  });

  it("preserves explicit undefined matrix values instead of showing zeros", async () => {
    await show();
    const r = record() as unknown as { matrices: { values: unknown[] }[] };
    r.matrices[0].values[1] = "NaN";
    load(r);
    expect(
      screen.getByRole("table", { name: "Matrix Q_matrix" }),
    ).toHaveTextContent("Undefined");
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });
});
