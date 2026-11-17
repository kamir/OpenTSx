import { afterEach, describe, expect, it, vi } from "vitest";
import {
  captureTrainingSeries,
  loadScoringResults,
  registerReviewedFit,
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
