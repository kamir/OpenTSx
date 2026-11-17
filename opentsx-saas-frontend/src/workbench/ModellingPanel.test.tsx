import {
  cleanup,
  fireEvent,
  render,
  screen,
  within,
} from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { ComponentType } from "react";
import type { Bucket } from "./types";

const bucket: Bucket = {
  name: "load",
  xKind: "time",
  xLabel: "t [s]",
  yLabel: "MW",
  t0Ms: 1700000000000,
  series: [
    {
      id: "load-a",
      label: "Load A",
      unit: "MW",
      x: Float64Array.from({ length: 32 }, (_, i) => i * 900),
      y: Float64Array.from({ length: 32 }, (_, i) => 10 + (i % 4)),
      quality: new Uint8Array(32),
    },
  ],
  episodes: [
    { seriesId: "load-a", episodeId: "episode-a", start: 0, end: 28800 },
  ],
};
async function show(input: Bucket | null = bucket) {
  const modules = import.meta.glob("./ModellingPanel.tsx");
  const load = modules["./ModellingPanel.tsx"];
  expect(load, "Modelling workflow exists").toBeTypeOf("function");
  const { default: Panel } = (await load()) as {
    default: ComponentType<{ bucket: Bucket | null }>;
  };
  return render(<Panel bucket={input} />);
}
afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});
describe("finite modelling workflow", () => {
  it("captures aligned known-ahead context for the current training window and invalidates it after an input change", async () => {
    const schema = {
      schema_id: "schema-001",
      policy: "immutable_known_ahead",
      target_series_id: "load-a",
      names: ["schedule_sin", "schedule_cos"],
      units: ["1", "1"],
      interval_micros: 900000000,
    };
    const context = {
      names: schema.names,
      units: schema.units,
      historical: Array.from({ length: 32 }, (_, i) => [
        Math.sin(i),
        Math.cos(i),
      ]),
      historical_available_at_micros: Array.from({ length: 32 }, () => [0, 0]),
      future: Array.from({ length: 4 }, (_, i) => [
        Math.sin(i + 32),
        Math.cos(i + 32),
      ]),
      future_available_at_micros: Array.from({ length: 4 }, () => [0, 0]),
    };
    const fetch = vi.fn(async (path: string, options?: RequestInit) => {
      if (path.endsWith("context-capture"))
        expect(options?.method).toBe("POST");
      return {
        ok: true,
        status: 200,
        text: async () =>
          JSON.stringify(
            path.endsWith("context-topics")
              ? {
                  schema: "opentsx.context-topics.v1",
                  topics: ["tsx.demo.sarimax.context.v1"],
                }
              : path.includes("context-series?")
                ? {
                    schema: "opentsx.context-series.v1",
                    topic: "tsx.demo.sarimax.context.v1",
                    contexts: [
                      {
                        partition: 0,
                        context_schema: schema,
                        first_micros: 1700000000000000,
                        last_micros: 1700031500000000,
                        row_count: 36,
                      },
                    ],
                    capture_policy: "immutable_known_ahead",
                  }
                : {
                    schema: "opentsx.context-capture.v1",
                    context,
                    context_schema: schema,
                    binding: {
                      topic: "tsx.demo.sarimax.context.v1",
                      partition: 0,
                      schema_id: schema.schema_id,
                      source_positions: [
                        { offset: "2", record_id: "context-row" },
                      ],
                    },
                    receipt_id: "receipt-001",
                  },
          ),
      };
    });
    vi.stubGlobal("fetch", fetch);
    await show();
    fireEvent.click(
      screen.getByRole("button", { name: "Load context topics" }),
    );
    await screen.findByRole("option", { name: "tsx.demo.sarimax.context.v1" });
    fireEvent.click(
      screen.getByRole("button", { name: "Read context schemas" }),
    );
    await screen.findByRole("option", { name: /schema-001/ });
    fireEvent.click(
      screen.getByRole("button", { name: "Load aligned SARIMAX context" }),
    );
    await screen.findByText(/Aligned context receipt receipt-001/);
    expect(screen.getByLabelText("Forecast family")).toHaveValue("sarimax");
    expect(
      JSON.parse(
        (screen.getByLabelText("External context JSON") as HTMLTextAreaElement)
          .value,
      ),
    ).toEqual(context);
    const call = fetch.mock.calls.find(([path]) =>
      path.endsWith("context-capture"),
    )!;
    const submitted = JSON.parse((call[1] as RequestInit).body as string);
    expect(submitted.target.timestamps_micros[0]).toBe(1700000000000000);
    expect(submitted.configuration.family).toBe("sarimax");
    expect(submitted.backtest.initial).toBe(22);
    fireEvent.change(screen.getByLabelText("Forecast steps"), {
      target: { value: "5" },
    });
    expect(
      screen.queryByText(/Aligned context receipt receipt-001/),
    ).not.toBeInTheDocument();
    expect(screen.getByLabelText("External context JSON")).toHaveValue("");
    expect(
      screen.getByText(/Captured context was invalidated/),
    ).toBeInTheDocument();
  });
  it("offers the Kafka capture and model lifecycle before a bucket is loaded", async () => {
    await show(null);
    expect(
      screen.getByRole("region", { name: "Model registry and Smart Topics" }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: "Load local catalogue" }),
    ).toBeEnabled();
    expect(
      screen.getByRole("button", { name: "Register reviewed fit" }),
    ).toBeDisabled();
  });
  it("offers one configured family editor, baselines and chronological backtest controls", async () => {
    await show();
    expect(
      screen.getByRole("region", { name: "Modelling and prediction" }),
    ).toBeInTheDocument();
    expect(screen.getByLabelText("Target series")).toHaveValue("load-a");
    expect(screen.getByLabelText("Forecast family")).toHaveValue("arima");
    expect(screen.getByLabelText("Naive baseline")).toBeChecked();
    expect(
      screen.getByLabelText("Initial training samples"),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: "Fit, backtest and forecast" }),
    ).toBeEnabled();
  });
  it("requires actual data rather than showing disabled algorithm placeholders", async () => {
    await show(null);
    expect(screen.getByText(/Load a time-series bucket/)).toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "ARIMA" }),
    ).not.toBeInTheDocument();
  });
  it("rejects flagged samples before transmitting a request", async () => {
    const invalid = {
      ...bucket,
      series: [
        {
          ...bucket.series[0],
          quality: Uint8Array.from({ length: 32 }, (_, i) => (i === 3 ? 1 : 0)),
        },
      ],
    };
    const fetch = vi.fn();
    vi.stubGlobal("fetch", fetch);
    await show(invalid);
    fireEvent.click(
      screen.getByRole("button", { name: "Fit, backtest and forecast" }),
    );
    expect(screen.getByRole("alert")).toHaveTextContent(/quality/i);
    expect(fetch).not.toHaveBeenCalled();
  });
  it("rejects gaps and an irregular clock before a numerical fit", async () => {
    const invalid = {
      ...bucket,
      series: [
        {
          ...bucket.series[0],
          x: Float64Array.from([
            0,
            1,
            4,
            ...Array.from({ length: 29 }, (_, i) => i + 5),
          ]),
        },
      ],
    };
    const fetch = vi.fn();
    vi.stubGlobal("fetch", fetch);
    await show(invalid);
    fireEvent.click(
      screen.getByRole("button", { name: "Fit, backtest and forecast" }),
    );
    expect(screen.getByRole("alert")).toHaveTextContent(/regular/i);
    expect(fetch).not.toHaveBeenCalled();
  });
  it("requires explicit future context for SARIMAX", async () => {
    const fetch = vi.fn();
    vi.stubGlobal("fetch", fetch);
    await show();
    fireEvent.change(screen.getByLabelText("Forecast family"), {
      target: { value: "sarimax" },
    });
    fireEvent.click(
      screen.getByRole("button", { name: "Fit, backtest and forecast" }),
    );
    expect(screen.getByRole("alert")).toHaveTextContent(/context/i);
    expect(fetch).not.toHaveBeenCalled();
  });
  it("reports service unavailability without substituting a forecast", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockRejectedValue(new TypeError("Failed to fetch")),
    );
    await show();
    fireEvent.click(
      screen.getByRole("button", { name: "Fit, backtest and forecast" }),
    );
    expect(await screen.findByRole("alert")).toHaveTextContent(/service/i);
    expect(
      screen.queryByRole("img", { name: /forecast/i }),
    ).not.toBeInTheDocument();
  });
});

function response() {
  const metrics = {
    mae: 1,
    rmse: 1.2,
    mase: 0.7,
    rmsse: 0.8,
    coverage: 0.75,
    mean_interval_width: 3,
    mean_interval_score: 4,
  };
  const forecast = {
    method: "arima",
    result: {
      mean: [11, 12, 11, 10],
      lower: [9, 10, 9, 8],
      upper: [13, 14, 13, 12],
      parameters: { "ar.L1": 0.5 },
      aic: 12,
      bic: 14,
      log_likelihood: -5,
    },
  };
  const cases = [
    {
      origin: 22,
      target_index: 22,
      horizon: 1,
      actual: 12,
      predicted: 11,
      lower: 9,
      upper: 13,
    },
  ];
  return {
    schema: "opentsx.modelling-result.v1",
    run_id: "run-fixture",
    source: {
      time_kind: "absolute",
      series_id: "load-a",
      label: "Load A",
      unit: "MW",
      episode_ids: ["episode-a"],
      nobs: 32,
      interval_micros: 900000000,
      t_start_micros: 1700000000000000,
      t_end_micros: 1700028800000000,
      values_sha256: "fixturehash",
    },
    configuration: { family: "arima", alpha: 0.05 },
    forecast,
    baselines: {
      naive: { ...forecast, method: "naive" },
      drift: { ...forecast, method: "drift" },
    },
    backtest: {
      result: {
        folds: [
          {
            training_start: 0,
            training_stop: 22,
            test_start: 22,
            test_stop: 26,
          },
        ],
        cases: { arima: cases, naive: cases, drift: cases },
        metrics: {
          arima: metrics,
          naive: { ...metrics, mae: 2 },
          drift: metrics,
        },
        by_horizon: {
          arima: { "1": metrics },
          naive: { "1": metrics },
          drift: { "1": metrics },
        },
      },
    },
    warnings: ["Fixture warning"],
    runtime: {
      opentsx: "1.0.0",
      statsmodels: "0.14.6",
      numpy: "2.5.3",
      scipy: "1.18.1",
    },
  };
}
describe("numerical service results", () => {
  it.each(["sarima", "sarimax"])(
    "labels the %s candidate in aggregate and horizon metrics and the chart legend",
    async (family) => {
      const record = response();
      record.configuration.family = family;
      record.forecast.method = family;
      const fetch = vi.fn().mockResolvedValue({
        ok: true,
        status: 200,
        text: async () => JSON.stringify(record),
      });
      vi.stubGlobal("fetch", fetch);
      vi.stubGlobal(
        "URL",
        class extends URL {
          static createObjectURL() {
            return "blob:test";
          }
          static revokeObjectURL() {}
        },
      );
      await show();
      fireEvent.change(screen.getByLabelText("Forecast family"), {
        target: { value: family },
      });
      if (family === "sarimax")
        fireEvent.change(screen.getByLabelText("External context JSON"), {
          target: {
            value: JSON.stringify({
              names: ["calendar"],
              historical: Array.from({ length: 32 }, () => [1]),
              historical_available_at_micros: Array.from({ length: 32 }, () => [
                0,
              ]),
              future: [[1], [1], [1], [1]],
              future_available_at_micros: [[0], [0], [0], [0]],
            }),
          },
        });
      fireEvent.click(
        screen.getByRole("button", { name: "Fit, backtest and forecast" }),
      );
      const table = await screen.findByRole("table", {
        name: "Backtest metrics",
      });
      expect(
        within(table).getByRole("columnheader", { name: family.toUpperCase() }),
      ).toBeInTheDocument();
      expect(
        within(table).queryByRole("columnheader", { name: "arima" }),
      ).not.toBeInTheDocument();
      expect(
        screen.getByText(
          new RegExp(`Orange: ${family.toUpperCase()} forecast`),
        ),
      ).toBeInTheDocument();
      fireEvent.change(screen.getByLabelText("Metrics horizon"), {
        target: { value: "1" },
      });
      expect(
        within(table).getByRole("columnheader", { name: family.toUpperCase() }),
      ).toBeInTheDocument();
      expect(JSON.parse(fetch.mock.calls[0][1].body).configuration.family).toBe(
        family,
      );
    },
  );
  it("submits the selected target and displays actual service forecasts, intervals and backtests", async () => {
    const fetch = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      text: async () => JSON.stringify(response()),
    });
    vi.stubGlobal("fetch", fetch);
    vi.stubGlobal(
      "URL",
      class extends URL {
        static createObjectURL() {
          return "blob:test";
        }
        static revokeObjectURL() {}
      },
    );
    await show();
    fireEvent.click(
      screen.getByRole("button", { name: "Fit, backtest and forecast" }),
    );
    expect(
      await screen.findByRole("img", {
        name: "Observed target and forecast with prediction interval",
      }),
    ).toBeInTheDocument();
    expect(
      within(screen.getByRole("table", { name: "Backtest metrics" })).getByRole(
        "columnheader",
        { name: "ARIMA" },
      ),
    ).toBeInTheDocument();
    expect(screen.getByText(/Candidate MAE is lower/)).toBeInTheDocument();
    expect(screen.getByText("Fixture warning")).toBeInTheDocument();
    expect(
      screen.getByLabelText("I reviewed this fit and its backtests"),
    ).toBeEnabled();
    expect(
      screen.getByRole("link", { name: "Download modelling result JSON" }),
    ).toBeInTheDocument();
    const request = JSON.parse(fetch.mock.calls[0][1].body);
    expect(fetch.mock.calls[0][0]).toBe("/analysis/v1/modelling/runs");
    expect(request.target.series_id).toBe("load-a");
    expect(request.target.time_kind).toBe("absolute");
    expect(request.target.timestamps_micros[0]).toBe(1700000000000000);
    expect(request.target.unit).toBe("MW");
    expect(request.target.episode_ids).toEqual(["episode-a"]);
    expect(request.backtest.methods).toContain("naive");
  });
  it("shows explicit optimizer errors without presenting successful output", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue({
        ok: false,
        status: 422,
        text: async () =>
          JSON.stringify({ error: "ARIMA optimization did not converge" }),
      }),
    );
    await show();
    fireEvent.click(
      screen.getByRole("button", { name: "Fit, backtest and forecast" }),
    );
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "ARIMA optimization did not converge",
    );
    expect(
      screen.queryByRole("img", {
        name: "Observed target and forecast with prediction interval",
      }),
    ).not.toBeInTheDocument();
  });
  it("rejects invalid response intervals rather than drawing them", async () => {
    const record = response();
    record.forecast.result.lower = [20, 20, 20, 20];
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue({
        ok: true,
        status: 200,
        text: async () => JSON.stringify(record),
      }),
    );
    await show();
    fireEvent.click(
      screen.getByRole("button", { name: "Fit, backtest and forecast" }),
    );
    expect(await screen.findByRole("alert")).toHaveTextContent(/intervals/);
  });
});
