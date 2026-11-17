import { afterEach, describe, expect, it, vi } from "vitest";
import {
  captureTrainingSeries,
  loadScoringResults,
  registerReviewedFit,
  captureAlignedContext,
  loadContextSchemas,
  matchingContextSchema,
  type ContextSchema,
} from "./lifecycle";

afterEach(() => vi.unstubAllGlobals());
describe("local model lifecycle boundary", () => {
  it("checks the registered name, review limit and explicit threshold before transport", async () => {
    const fetch = vi.fn();
    vi.stubGlobal("fetch", fetch);
    for (const [name, note, threshold] of [
      ["Name with spaces", "review", 0],
      ["valid", "x".repeat(2001), 0],
      ["valid", "review", 1.1],
      ["valid", "review", -11],
    ] as const) {
      await expect(
        registerReviewedFit("run", name, note, threshold),
      ).rejects.toThrow(/registered name/);
    }
    expect(fetch).not.toHaveBeenCalled();
  });
  it("rejects a captured topic mismatch before parsing or loading data", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue({
        ok: true,
        status: 200,
        text: async () =>
          JSON.stringify({
            schema: "opentsx.modelling-capture.v1",
            topic: "different.topic",
            episodes: [],
          }),
      }),
    );
    await expect(
      captureTrainingSeries("tsx.demo.input", "load-a"),
    ).rejects.toThrow(/requested topic/);
  });
  it("rejects prediction records for a different deployment", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue({
        ok: true,
        status: 200,
        text: async () =>
          JSON.stringify({
            schema: "opentsx.smart-predictions.v1",
            deployment_id: "other",
            results: [],
          }),
      }),
    );
    await expect(loadScoringResults("forecast")).rejects.toThrow(
      /selected deployment/,
    );
  });
  it("reports invalid JSON and connection failure explicitly", async () => {
    const fetch = vi
      .fn()
      .mockResolvedValue({ ok: true, status: 200, text: async () => "{" });
    vi.stubGlobal("fetch", fetch);
    await expect(loadScoringResults("forecast")).rejects.toThrow(
      /invalid JSON/,
    );
    fetch.mockRejectedValue(new TypeError("Failed to fetch"));
    await expect(loadScoringResults("forecast")).rejects.toThrow(/unavailable/);
  });
});

const contextSchema: ContextSchema = {
  schema_id: "schema-001",
  policy: "immutable_known_ahead",
  target_series_id: "load-a",
  names: ["schedule"],
  units: ["1"],
  interval_micros: 1000000,
};
function contextRequest(): Parameters<typeof captureAlignedContext>[2] {
  return {
    target: {
      time_kind: "absolute",
      series_id: "load-a",
      label: "Load A",
      unit: "MW",
      values: Array.from({ length: 12 }, (_, i) => i + 1),
      timestamps_micros: Array.from(
        { length: 12 },
        (_, i) => 1700000000000000 + i * 1000000,
      ),
      episode_ids: [],
    },
    configuration: {
      family: "sarimax",
      order: [1, 0, 0],
      seasonal_order: [0, 0, 0, 0],
      trend: "n",
      steps: 2,
      alpha: 0.05,
      maxiter: 100,
    },
    backtest: {
      initial: 8,
      horizon: 2,
      step: 2,
      window: null,
      seasonal_period: 2,
      methods: ["naive"],
    },
  };
}
function capturedContext() {
  return {
    schema: "opentsx.context-capture.v1",
    context_schema: contextSchema,
    receipt_id: "receipt-001",
    binding: {
      topic: "tsx.demo.context.v1",
      partition: 0,
      schema_id: contextSchema.schema_id,
      source_positions: [{ offset: "2", record_id: "record-001" }],
    },
    context: {
      names: ["schedule"],
      units: ["1"],
      historical: Array.from({ length: 12 }, () => [1]),
      historical_available_at_micros: Array.from({ length: 12 }, () => [0]),
      future: [[1], [1]],
      future_available_at_micros: [[0], [0]],
    },
  };
}
describe("known-ahead context boundary", () => {
  it("compares ordered columns and units even when a schema label was reused", () => {
    expect(
      matchingContextSchema(contextSchema, { ...contextSchema, units: ["MW"] }),
    ).toBe(false);
    expect(
      matchingContextSchema(contextSchema, {
        ...contextSchema,
        names: ["different"],
      }),
    ).toBe(false);
  });
  it("rejects unknown availability policies before exposing context bindings", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue({
        ok: true,
        status: 200,
        text: async () =>
          JSON.stringify({
            schema: "opentsx.context-series.v1",
            topic: "tsx.demo.context.v1",
            contexts: [
              {
                partition: 0,
                first_micros: 0,
                last_micros: 1,
                row_count: 2,
                context_schema: {
                  ...contextSchema,
                  policy: "latest_realized",
                },
              },
            ],
          }),
      }),
    );
    await expect(loadContextSchemas("tsx.demo.context.v1")).rejects.toThrow(
      /availability policy/,
    );
  });
  it.each(["units", "availability", "binding", "rows"])(
    "rejects captured context with changed %s",
    async (change) => {
      const capture = capturedContext();
      if (change === "units") capture.context.units = ["MW"];
      if (change === "availability")
        capture.context.historical_available_at_micros[8] = [1700000009000000];
      if (change === "binding") capture.binding.partition = 1;
      if (change === "rows") capture.context.future = [[1]];
      vi.stubGlobal(
        "fetch",
        vi.fn().mockResolvedValue({
          ok: true,
          status: 200,
          text: async () => JSON.stringify(capture),
        }),
      );
      await expect(
        captureAlignedContext(
          "tsx.demo.context.v1",
          0,
          contextRequest(),
          contextSchema,
        ),
      ).rejects.toThrow(/context/i);
    },
  );
  it("retains declared units, availability matrices and source offsets in a valid capture", async () => {
    const capture = capturedContext();
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue({
        ok: true,
        status: 200,
        text: async () => JSON.stringify(capture),
      }),
    );
    const result = await captureAlignedContext(
      "tsx.demo.context.v1",
      0,
      contextRequest(),
      contextSchema,
    );
    expect(result.context.units).toEqual(["1"]);
    expect(result.binding.source_positions).toEqual([
      { offset: "2", record_id: "record-001" },
    ]);
    expect(result.context.historical_available_at_micros).toHaveLength(12);
  });
});
