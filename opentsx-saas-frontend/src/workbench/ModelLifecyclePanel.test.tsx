import {
  cleanup,
  fireEvent,
  render,
  screen,
  within,
} from "@testing-library/react";
import type { ComponentType } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { useFlow } from "./flow";
import type { ModellingResult } from "./modelling";

const run = { run_id: "fit-001" } as ModellingResult;
function model(overrides = {}) {
  return {
    id: "model-001",
    name: "load-forecast",
    version: 1,
    run_id: "fit-001",
    artifact_id: "artifact-001",
    family: "arima",
    source: {
      series_id: "load-a",
      unit: "MW",
      interval_micros: 1000000,
      time_kind: "absolute",
    },
    review: {
      accepted: true,
      note: "Checked held-out origins",
      minimum_improvement: 0,
    },
    metrics: { candidate_mae: 1, baseline_mae: 2, improvement: 0.5 },
    mlflow_run_id: "mlflow-001",
    mlflow_version: 1,
    status: "registered",
    deployment_blockers: [],
    ...overrides,
  };
}
function deployment(overrides = {}) {
  return {
    id: "deploy-001",
    model_id: "model-001",
    series_id: "load-a",
    input_topic: "tsx.demo.input",
    input_partition: 0,
    output_topic: "tsx.demo.smart.forecast.predictions.v1",
    desired_state: "running",
    status: "starting",
    error: null,
    checkpoint: null,
    ...overrides,
  };
}
function fixtures(overrides: Record<string, unknown> = {}) {
  const routes: Record<string, unknown> = {
    "/analysis/v1/topics": {
      topics: ["tsx.demo.input"],
      output_topic: "tsx.demo.results",
    },
    "/analysis/v1/modelling/models": {
      schema: "opentsx.model-registry.v1",
      topic_prefix: "tsx.demo.",
      models: [model()],
    },
    "/analysis/v1/modelling/deployments": {
      schema: "opentsx.scoring-deployments.v1",
      deployments: [],
    },
    "/analysis/v1/modelling/series?topic=tsx.demo.input": {
      schema: "opentsx.modelling-series.v1",
      topic: "tsx.demo.input",
      series: [
        {
          series_id: "load-a",
          label: "Load A",
          unit: "MW",
          interval_micros: 1000000,
          partitions: [0],
          count: 32,
          first_micros: 1700000000000000,
          last_micros: 1700000031000000,
        },
      ],
    },
    "POST /analysis/v1/modelling/capture": {
      schema: "opentsx.modelling-capture.v1",
      topic: "tsx.demo.input",
      episodes: [
        {
          episodeId: "episode-a",
          seriesId: "load-a",
          unit: "MW",
          timestampsMicros: Array.from(
            { length: 32 },
            (_, i) => 1700000000000000 + i * 1000000,
          ),
          values: Array.from({ length: 32 }, (_, i) => 10 + (i % 4)),
          quality: Array(32).fill(0),
        },
      ],
    },
    "POST /analysis/v1/modelling/models": model(),
    "POST /analysis/v1/modelling/deployments": deployment(),
    "POST /analysis/v1/modelling/deployments/deploy-001/stop": deployment({
      desired_state: "stopped",
      status: "stopped",
    }),
    "POST /analysis/v1/modelling/deployments/deploy-001/start": deployment(),
    "/analysis/v1/modelling/deployments/deploy-001/results": {
      schema: "opentsx.smart-predictions.v1",
      deployment_id: "deploy-001",
      results: [
        {
          key: "prediction-001",
          payload: {
            target: {
              series_id: "load-a",
              unit: "MW",
              time_kind: "absolute",
              interval_micros: 1000000,
            },
            forecast: {
              mean: [14, 14.5],
              lower: [12, 12.5],
              upper: [16, 16.5],
              timestamps_micros: [1700000034000000, 1700000035000000],
            },
            observed: {
              actual: [15, 13],
              predicted: [14, 14],
              residual: [1, -1],
            },
            model_id: "model-001",
          },
          broker_partition: 0,
          broker_offset: 7,
        },
      ],
    },
    "POST /analysis/v1/modelling/models/model-001/predict": {
      schema: "opentsx.registered-prediction.v1",
      model_id: "model-001",
      artifact_id: "artifact-001",
      forecast: {
        mean: [14, 14, 14, 14],
        lower: [12, 12, 12, 12],
        upper: [16, 16, 16, 16],
        timestamps_micros: [
          1700000032000000, 1700000033000000, 1700000034000000,
          1700000035000000,
        ],
        interval_status: "conditional_gaussian",
      },
    },
    ...overrides,
  };
  const fetch = vi.fn(async (path: string, options?: RequestInit) => {
    const key = options?.method === "POST" ? `POST ${path}` : path;
    if (!(key in routes)) throw new Error(`Unexpected test route: ${key}`);
    const value = routes[key];
    if (value instanceof Error)
      return {
        ok: false,
        status: 422,
        text: async () => JSON.stringify({ error: value.message }),
      };
    return { ok: true, status: 200, text: async () => JSON.stringify(value) };
  });
  vi.stubGlobal("fetch", fetch);
  return fetch;
}
async function show(current: ModellingResult | null = null) {
  const modules = import.meta.glob("./ModelLifecyclePanel.tsx");
  const load = modules["./ModelLifecyclePanel.tsx"];
  expect(load, "Local model lifecycle panel exists").toBeTypeOf("function");
  const { default: Panel } = (await load()) as {
    default: ComponentType<{ run: ModellingResult | null }>;
  };
  return render(<Panel run={current} />);
}
async function catalogue() {
  fireEvent.click(screen.getByRole("button", { name: "Load local catalogue" }));
  await screen.findByRole("option", { name: /load-forecast/ });
}
async function source() {
  fireEvent.click(screen.getByRole("button", { name: "Read topic series" }));
  await screen.findByRole("option", { name: /Load A/ });
}
beforeEach(() => useFlow.getState().clear());
afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});
describe("local fitting lifecycle", () => {
  it("loads an actual Kafka series into the workbench before a fit exists", async () => {
    const fetch = fixtures();
    await show();
    await catalogue();
    await source();
    fireEvent.click(
      screen.getByRole("button", { name: "Load training series" }),
    );
    await screen.findByText(/Training series loaded from Kafka/);
    const state = useFlow.getState();
    expect(state.source?.series[0].id).toBe("load-a");
    expect(state.source?.t0Ms).toBe(1700000000000);
    expect(state.sourceTopic).toBe("tsx.demo.input");
    expect(state.sourceSeries).toEqual(["load-a"]);
    expect(state.sourceInfo?.detail).toContain("Kafka");
    expect(
      JSON.parse(
        fetch.mock.calls.find(([path]) => path.endsWith("/capture"))![1]!
          .body as string,
      ),
    ).toEqual({ topic: "tsx.demo.input", series_id: "load-a", samples: 512 });
  });
  it("requires explicit review and binds registration to the server run", async () => {
    const fetch = fixtures();
    await show(run);
    const register = screen.getByRole("button", {
      name: "Register reviewed fit",
    });
    expect(register).toBeDisabled();
    fireEvent.change(screen.getByLabelText("Registered model name"), {
      target: { value: "load-forecast" },
    });
    fireEvent.change(screen.getByLabelText("Review note"), {
      target: { value: "Checked held-out origins" },
    });
    fireEvent.change(
      screen.getByLabelText("Minimum relative MAE improvement"),
      { target: { value: "-0.2" } },
    );
    fireEvent.click(
      screen.getByLabelText("I reviewed this fit and its backtests"),
    );
    fireEvent.click(register);
    await screen.findByText(/Registered model model-001/);
    const call = fetch.mock.calls.find(
      ([path, options]) =>
        path.endsWith("/models") && options?.method === "POST",
    )!;
    expect(JSON.parse(call[1]!.body as string)).toEqual({
      run_id: "fit-001",
      name: "load-forecast",
      review: {
        accepted: true,
        note: "Checked held-out origins",
        minimum_improvement: -0.2,
      },
    });
    expect(screen.getByText("artifact-001")).toBeInTheDocument();
    expect(screen.getByText("mlflow-001")).toBeInTheDocument();
  });
  it("shows actual starting state and supports stop, restart and inspecting broker results", async () => {
    const fetch = fixtures();
    await show();
    await catalogue();
    await source();
    fireEvent.change(screen.getByLabelText("Prediction output topic"), {
      target: { value: "tsx.demo.smart.forecast.predictions.v1" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Start Smart Topic" }));
    const status = await screen.findByRole("region", {
      name: "Scoring deployment status",
    });
    expect(status).toHaveTextContent("starting");
    expect(
      within(status).getByText("Actual state").nextElementSibling,
    ).toHaveTextContent("starting");
    const startCall = fetch.mock.calls.find(
      ([path, options]) =>
        path.endsWith("/deployments") && options?.method === "POST",
    )!;
    expect(JSON.parse(startCall[1]!.body as string)).toEqual({
      model_id: "model-001",
      input_topic: "tsx.demo.input",
      input_partition: 0,
      output_topic: "tsx.demo.smart.forecast.predictions.v1",
    });
    fireEvent.click(screen.getByRole("button", { name: "Stop scoring" }));
    await screen.findByText(/Deployment deploy-001 is stopped/);
    fireEvent.click(screen.getByRole("button", { name: "Start scoring" }));
    await screen.findByText(/Deployment deploy-001 is starting/);
    fireEvent.click(
      screen.getByRole("button", { name: "Inspect scoring results" }),
    );
    const results = await screen.findByRole("table", {
      name: "Smart Topic predictions",
    });
    expect(results).toHaveTextContent("prediction-001");
    expect(results).toHaveTextContent("7");
    expect(
      screen.getByText(
        "Scoring results read from the acknowledged local cache.",
      ),
    ).toBeInTheDocument();
    expect(
      screen.queryByText("Scoring results read from the broker."),
    ).not.toBeInTheDocument();
    const forecast = screen.getByRole("table", {
      name: "Latest scoring forecast",
    });
    expect(forecast).toHaveTextContent("2023-11-14T22:13:54.000000Z");
    expect(forecast).toHaveTextContent("14.5");
    expect(forecast).toHaveTextContent("MW");
    expect(
      screen.getByText(/Observed samples: 2; residual MAE: 1 MW/),
    ).toBeInTheDocument();
    const raw = screen
      .getByText(/Full cached prediction JSON/)
      .closest("details");
    expect(raw).not.toHaveAttribute("open");
    expect(raw).toHaveTextContent('"actual"');
    expect(raw).toHaveTextContent("15");
  });
  it("blocks continuous SARIMAX scoring until live context mapping exists", async () => {
    fixtures({
      "/analysis/v1/modelling/models": {
        schema: "opentsx.model-registry.v1",
        topic_prefix: "tsx.demo.",
        models: [
          model({
            family: "sarimax",
            deployment_blockers: ["Live context mapping is unavailable"],
          }),
        ],
      },
    });
    await show();
    await catalogue();
    await source();
    fireEvent.change(screen.getByLabelText("Prediction output topic"), {
      target: { value: "tsx.demo.smart.forecast.predictions.v1" },
    });
    expect(
      screen.getByRole("button", { name: "Start Smart Topic" }),
    ).toBeDisabled();
    expect(
      screen.getByText("Live context mapping is unavailable"),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: "Predict with registered artifact" }),
    ).toBeDisabled();
  });
  it("retains legacy cached JSON without inventing target units or UTC coordinates", async () => {
    fixtures({
      "/analysis/v1/modelling/deployments": {
        schema: "opentsx.scoring-deployments.v1",
        deployments: [deployment()],
      },
      "/analysis/v1/modelling/deployments/deploy-001/results": {
        schema: "opentsx.smart-predictions.v1",
        deployment_id: "deploy-001",
        results: [
          {
            key: "legacy",
            broker_partition: 0,
            broker_offset: 9,
            payload: {
              forecast: {
                mean: [14],
                lower: [12],
                upper: [16],
                timestamps_micros: [1700000034000000],
              },
              observed: { actual: [15, null], residual: [1, 0] },
            },
          },
        ],
      },
    });
    await show();
    await catalogue();
    fireEvent.click(
      screen.getByRole("button", { name: "Inspect scoring results" }),
    );
    await screen.findByRole("table", { name: "Smart Topic predictions" });
    expect(
      screen.queryByRole("table", { name: "Latest scoring forecast" }),
    ).not.toBeInTheDocument();
    expect(
      screen.getByText(/latest forecast preview is unavailable/),
    ).toBeInTheDocument();
    expect(
      screen.getByText(
        /Observed samples: Unavailable; residual MAE: Unavailable/,
      ),
    ).toBeInTheDocument();
    expect(
      screen.getByText(/Full cached prediction JSON/).closest("details"),
    ).toHaveTextContent("legacy");
  });
  it("predicts again from the registered artifact without resubmitting fitting data", async () => {
    const fetch = fixtures();
    await show();
    await catalogue();
    fireEvent.click(
      screen.getByRole("button", { name: "Predict with registered artifact" }),
    );
    const table = await screen.findByRole("table", {
      name: "Registered artifact predictions",
    });
    expect(table).toHaveTextContent("14");
    expect(table).toHaveTextContent("1700000032000000");
    const call = fetch.mock.calls.find(([path]) => path.endsWith("/predict"))!;
    expect(JSON.parse(call[1]!.body as string)).toEqual({ steps: 4 });
  });
  it("reports registry failure rather than claiming an empty registry", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue({
        ok: false,
        status: 503,
        text: async () =>
          JSON.stringify({ error: "Model registry is unavailable" }),
      }),
    );
    await show();
    fireEvent.click(
      screen.getByRole("button", { name: "Load local catalogue" }),
    );
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Model registry is unavailable",
    );
    expect(screen.queryByText("No registered models.")).not.toBeInTheDocument();
  });
  it("shows a rejected metric gate without presenting a registered artifact", async () => {
    fixtures({
      "POST /analysis/v1/modelling/models": new Error(
        "candidate improvement -0.3 is below the review threshold 0",
      ),
    });
    await show(run);
    fireEvent.change(screen.getByLabelText("Registered model name"), {
      target: { value: "load-forecast" },
    });
    fireEvent.change(screen.getByLabelText("Review note"), {
      target: { value: "Reviewed all origins" },
    });
    fireEvent.click(
      screen.getByLabelText("I reviewed this fit and its backtests"),
    );
    fireEvent.click(
      screen.getByRole("button", { name: "Register reviewed fit" }),
    );
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "below the review threshold",
    );
    expect(screen.queryByText("artifact-001")).not.toBeInTheDocument();
  });
  it("refreshes actual worker progress without exposing its numerical checkpoint", async () => {
    const listing = {
      schema: "opentsx.scoring-deployments.v1",
      deployments: [deployment()],
    };
    fixtures({ "/analysis/v1/modelling/deployments": listing });
    await show();
    await catalogue();
    expect(
      screen.getByRole("region", { name: "Scoring deployment status" }),
    ).toHaveTextContent("starting");
    listing.deployments = [
      deployment({
        status: "running",
        checkpoint: {
          counters: { read: 6, scored: 2, skipped: 4 },
          next_offset: 19,
          numerical: { secret_state: "not shown" },
        },
      }),
    ];
    fireEvent.click(
      screen.getByRole("button", { name: "Refresh deployments" }),
    );
    await screen.findByText(
      "Actual deployment states refreshed from the service.",
    );
    const status = screen.getByRole("region", {
      name: "Scoring deployment status",
    });
    expect(
      within(status).getByText("Actual state").nextElementSibling,
    ).toHaveTextContent("running");
    expect(status).toHaveTextContent(
      "Read 6; scored 2; skipped 4; next input offset 19",
    );
    expect(status).not.toHaveTextContent("secret_state");
  });
  it("keeps a multi-partition series and an output outside the namespace from starting", async () => {
    const fetch = fixtures({
      "/analysis/v1/modelling/series?topic=tsx.demo.input": {
        schema: "opentsx.modelling-series.v1",
        topic: "tsx.demo.input",
        series: [
          {
            series_id: "load-a",
            label: "Load A",
            unit: "MW",
            interval_micros: 1000000,
            partitions: [0, 1],
            count: 32,
            first_micros: 1700000000000000,
            last_micros: 1700000031000000,
          },
        ],
      },
    });
    await show();
    await catalogue();
    await source();
    fireEvent.change(screen.getByLabelText("Prediction output topic"), {
      target: { value: "external.forecasts" },
    });
    expect(
      screen.getByRole("button", { name: "Start Smart Topic" }),
    ).toBeDisabled();
    expect(screen.getByText(/exactly one partition/)).toBeInTheDocument();
    expect(screen.getByText(/Use tsx.demo.smart/)).toBeInTheDocument();
    expect(
      fetch.mock.calls.some(
        ([path, options]) =>
          path.endsWith("/deployments") && options?.method === "POST",
      ),
    ).toBe(false);
  });
});
