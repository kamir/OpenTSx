import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, within } from "@testing-library/react";
import { DataPreview, DiagnosticBench, RecipeNotebook } from "./DeviceViews";
import { defaultParams, OP_BY_ID } from "./ops";
import { prepareDiagnostic } from "./DeviceDiagnostics";
import { useFlow } from "./flow";
import type { Bucket } from "./types";

function fixture(count = 32): Bucket {
  return {
    name: "Measured input",
    xKind: "time",
    xLabel: "Time [s]",
    yLabel: "Temperature",
    t0Ms: 1_700_000_000_000,
    series: [
      {
        id: "temperature",
        label: "Temperature",
        unit: "K",
        x: Float64Array.from({ length: count }, (_, i) => i / 32),
        y: Float64Array.from({ length: count }, (_, i) =>
          Math.sin((2 * Math.PI * 4 * i) / 32),
        ),
        quality: new Uint8Array(count),
      },
    ],
    episodes: [
      { episodeId: "first", seriesId: "temperature", start: 0, end: 0.5 },
      { episodeId: "second", seriesId: "temperature", start: 0.5, end: 1 },
    ],
  };
}

beforeEach(() => useFlow.getState().clear());

describe("diagnostic selection boundaries", () => {
  it("retains an exact episode, quality slice and source identity for existing kernels", () => {
    const bucket = fixture();
    bucket.series[0].quality![31] = 2;
    const selected = prepareDiagnostic(bucket, "temperature", "first", false);
    expect(selected.series[0].y).toHaveLength(16);
    expect(selected.series[0].quality).toHaveLength(16);
    expect(selected.episodes?.map((episode) => episode.episodeId)).toEqual([
      "first",
    ]);
    expect(selected.t0Ms).toBe(bucket.t0Ms);
    expect(selected.series[0].y).toEqual(bucket.series[0].y.slice(0, 16));
    expect(bucket.series[0].y).toHaveLength(32);
  });

  it("rejects gaps, irregular clocks, flagged samples, unavailable episodes and oversized selections", () => {
    const gap = fixture();
    gap.series[0].y[2] = NaN;
    expect(() => prepareDiagnostic(gap, "temperature", "", false)).toThrow(
      "gap-free episode",
    );
    const irregular = fixture();
    irregular.series[0].x[2] += 0.01;
    expect(() =>
      prepareDiagnostic(irregular, "temperature", "", false),
    ).toThrow("regular increasing clock");
    const flagged = fixture();
    flagged.series[0].quality![2] = 1;
    expect(() => prepareDiagnostic(flagged, "temperature", "", false)).toThrow(
      "flagged samples",
    );
    expect(() =>
      prepareDiagnostic(fixture(), "temperature", "missing", false),
    ).toThrow("unavailable");
    expect(() =>
      prepareDiagnostic(fixture(2049), "temperature", "", false),
    ).toThrow("at most 2048");
  });

  it("requires acknowledgement for unknown quality and retains its unknown state", () => {
    const bucket = fixture();
    delete bucket.series[0].quality;
    expect(() => prepareDiagnostic(bucket, "temperature", "", false)).toThrow(
      "Acknowledge unknown quality",
    );
    expect(
      prepareDiagnostic(bucket, "temperature", "", true).series[0].quality,
    ).toBeUndefined();
    bucket.series[0].quality = new Uint8Array(32);
    bucket.series[0].qualityKnown = new Uint8Array(32).fill(1);
    bucket.series[0].qualityKnown[17] = 0;
    expect(
      prepareDiagnostic(bucket, "temperature", "first", false).series[0]
        .qualityKnown,
    ).toEqual(new Uint8Array(16).fill(1));
    expect(() =>
      prepareDiagnostic(bucket, "temperature", "second", false),
    ).toThrow("Acknowledge unknown quality");
  });

  it("keeps the actual FFT amplitude and ACF normalization from existing operators", () => {
    const input = prepareDiagnostic(fixture(), "temperature", "", false);
    const fft = OP_BY_ID.fft.apply(input, defaultParams(OP_BY_ID.fft))
      .series[0];
    const acf = OP_BY_ID.acf.apply(input, { maxLag: 8 }).series[0];
    expect(fft.x[4]).toBe(4);
    expect(fft.y[4]).toBeCloseTo(1, 12);
    expect(acf.y[0]).toBe(1);
    expect(acf.y).toHaveLength(8);
  });
});

describe("diagnostic bench execution", () => {
  it("runs explicitly, labels frequency and lag correctly, and never mutates the processing graph", () => {
    const bucket = fixture();
    useFlow.getState().loadBucket(bucket, { kind: "file", detail: "fixture" });
    const original = useFlow.getState().nodes;
    render(<DiagnosticBench bucket={bucket} />);
    expect(
      screen.queryByRole("img", { name: "FFT amplitude" }),
    ).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Run diagnostics" }));
    expect(screen.getByRole("status")).toHaveTextContent(
      "Completed on 32 samples",
    );
    expect(
      screen.getByRole("img", { name: "FFT amplitude" }),
    ).toHaveTextContent("f [Hz]");
    expect(
      screen.getByRole("img", { name: "Autocorrelation" }),
    ).toHaveTextContent("Lag [samples]");
    expect(screen.getByRole("img", { name: "Time domain" })).toHaveTextContent(
      "Time [UTC]",
    );
    expect(useFlow.getState().nodes).toBe(original);
  });

  it("shows failures and does not draw successful results from a missing-data selection", () => {
    const bucket = fixture();
    bucket.series[0].y[4] = NaN;
    render(<DiagnosticBench bucket={bucket} />);
    fireEvent.click(screen.getByRole("button", { name: "Run diagnostics" }));
    expect(screen.getByRole("alert")).toHaveTextContent("gap-free episode");
    expect(screen.queryByRole("img")).not.toBeInTheDocument();
  });

  it("requires an explicit unknown-quality acknowledgement and invalidates it when withdrawn", () => {
    const bucket = fixture();
    delete bucket.series[0].quality;
    render(<DiagnosticBench bucket={bucket} />);
    fireEvent.click(screen.getByRole("button", { name: "Run diagnostics" }));
    expect(screen.getByRole("alert")).toHaveTextContent(
      "Acknowledge unknown quality",
    );
    fireEvent.click(
      screen.getByRole("checkbox", { name: "Acknowledge unknown quality" }),
    );
    fireEvent.click(screen.getByRole("button", { name: "Run diagnostics" }));
    expect(screen.getByRole("status")).toHaveTextContent(
      "unknown, explicitly acknowledged",
    );
    fireEvent.click(
      screen.getByRole("checkbox", { name: "Acknowledge unknown quality" }),
    );
    expect(screen.queryByRole("img")).not.toBeInTheDocument();
  });

  it("marks results stale when the selected episode changes and computes the actual new sample count", () => {
    render(<DiagnosticBench bucket={fixture()} />);
    fireEvent.click(screen.getByRole("button", { name: "Run diagnostics" }));
    fireEvent.change(screen.getByLabelText("Diagnostic episode"), {
      target: { value: "first" },
    });
    expect(screen.queryByRole("img")).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Run diagnostics" }));
    expect(screen.getByRole("status")).toHaveTextContent(
      "Completed on 16 samples",
    );
  });
});

describe("data and recipe views", () => {
  it("shows actual UTC samples and units while retaining missing and unknown quality", () => {
    const bucket = fixture();
    bucket.series[0].y[1] = NaN;
    delete bucket.series[0].quality;
    bucket.series.push({
      id: "current",
      label: "Current",
      unit: "A",
      x: new Float64Array([0]),
      y: new Float64Array([2]),
      quality: new Uint8Array([0]),
    });
    render(<DataPreview bucket={bucket} />);
    const table = screen.getAllByRole("table")[0];
    expect(
      within(table).getByRole("columnheader", { name: "Time [UTC]" }),
    ).toBeInTheDocument();
    expect(within(table).getAllByText("2023-11-14T22:13:20.000Z")).toHaveLength(
      2,
    );
    expect(within(table).getByText("null")).toBeInTheDocument();
    expect(within(table).getByText("Missing")).toBeInTheDocument();
    expect(within(table).getAllByText("Unknown").length).toBeGreaterThan(0);
    expect(within(table).getByText("A")).toBeInTheDocument();
  });

  it("bounds preview rows and does not invent an absolute clock for relative input", () => {
    const bucket = fixture(400);
    delete bucket.t0Ms;
    render(<DataPreview bucket={bucket} />);
    const table = screen.getAllByRole("table")[0];
    expect(within(table).getAllByRole("row")).toHaveLength(101);
    expect(
      within(table).getByRole("columnheader", { name: "Relative time [s]" }),
    ).toBeInTheDocument();
  });

  it("opens shared modelling and snapshot workflows without fitting or changing the graph", () => {
    const bucket = fixture();
    useFlow.getState().loadBucket(bucket, { kind: "file", detail: "fixture" });
    const original = useFlow.getState().nodes;
    const onModelling = vi.fn(),
      onSnapshot = vi.fn();
    render(
      <RecipeNotebook
        bucket={bucket}
        onModelling={onModelling}
        onSnapshot={onSnapshot}
      />,
    );
    fireEvent.click(
      screen.getByRole("button", { name: "Open modelling and backtests" }),
    );
    fireEvent.click(
      screen.getByRole("button", { name: "Open snapshot export" }),
    );
    expect(onModelling).toHaveBeenCalledOnce();
    expect(onSnapshot).toHaveBeenCalledOnce();
    expect(useFlow.getState().nodes).toBe(original);
    expect(
      screen.getByText(/Continuous scoring is configured separately/),
    ).toBeInTheDocument();
    expect(screen.queryByText("Kernel ready")).not.toBeInTheDocument();
  });
});
