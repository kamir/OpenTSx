import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, within } from "@testing-library/react";
import Scope from "./Scope";
import type { Bucket } from "./types";

const context = {
  setTransform: vi.fn(),
  fillRect:
    vi.fn<(x: number, y: number, width: number, height: number) => void>(),
  beginPath: vi.fn(),
  moveTo: vi.fn(),
  lineTo: vi.fn(),
  stroke: vi.fn(),
  fillText: vi.fn<(text: string, x: number, y: number) => void>(),
  setLineDash: vi.fn(),
  measureText: vi.fn(() => ({ width: 20 })),
  arc: vi.fn(),
  fill: vi.fn(),
  save: vi.fn(),
  restore: vi.fn(),
  rect: vi.fn<(x: number, y: number, width: number, height: number) => void>(),
  clip: vi.fn(),
};

const bucket: Bucket = {
  name: "Mixed units",
  xKind: "time",
  xLabel: "Time [s]",
  yLabel: "Mixed quantities",
  t0Ms: 1795003200000,
  series: [
    {
      id: "temperature",
      label: "Panel temperature",
      unit: "C",
      x: Float64Array.from([0, 1, 2, 3]),
      y: Float64Array.from([21, 22, 23, 24]),
      quality: Uint8Array.from([0, 0, 0, 0]),
    },
    {
      id: "heater",
      label: "Heater schedule",
      unit: "%",
      x: Float64Array.from([0, 1, 2, 3]),
      y: Float64Array.from([0, 40, 40, 0]),
      quality: Uint8Array.from([0, 0, 0, 0]),
    },
  ],
};

beforeEach(() => {
  vi.clearAllMocks();
  vi.stubGlobal(
    "ResizeObserver",
    class {
      observe() {}
      disconnect() {}
    },
  );
  vi.spyOn(HTMLCanvasElement.prototype, "getContext").mockReturnValue(
    context as unknown as CanvasRenderingContext2D,
  );
  vi.stubGlobal("PointerEvent", MouseEvent);
});

afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

describe("instrument scope presentation", () => {
  it("clips mixed quantities into separate labelled channel bands", () => {
    render(
      <Scope bucket={bucket} showLabels instrument onUseTrigger={vi.fn()} />,
    );
    expect(context.clip).toHaveBeenCalledTimes(4);
    const regions = context.rect.mock.calls;
    expect(regions).toHaveLength(4);
    for (let index = 0; index < regions.length; index += 2) {
      expect(regions[index][1]).toBeLessThan(regions[index + 1][1]);
      expect(regions[index][1] + regions[index][3]).toBeLessThan(
        regions[index + 1][1],
      );
    }
    const labels = context.fillText.mock.calls.map((call) => call[0]);
    expect(labels).toContain("CH1 Panel temperature [C]");
    expect(labels).toContain("CH2 Heater schedule [%]");
    expect(labels.some((value) => String(value).includes("UTC"))).toBe(true);
  });

  it("retains the unstacked legacy drawing when instrument mode is omitted", () => {
    render(<Scope bucket={bucket} showLabels onUseTrigger={vi.fn()} />);
    expect(context.clip).not.toHaveBeenCalled();
    expect(context.fillText.mock.calls.map((call) => call[0])).toContain(
      "CH1 Panel temperature",
    );
  });

  it("compacts visible bands after a channel is hidden", () => {
    render(
      <Scope bucket={bucket} showLabels instrument onUseTrigger={vi.fn()} />,
    );
    context.clip.mockClear();
    context.rect.mockClear();
    context.fillText.mockClear();
    fireEvent.click(screen.getByLabelText("Show channel 2"));
    expect(context.clip).toHaveBeenCalledTimes(1);
    const labels = context.fillText.mock.calls.map((call) => call[0]);
    expect(labels).toContain("CH1 Panel temperature [C]");
    expect(labels).not.toContain("CH2 Heater schedule [%]");
  });

  it("keeps cursor state mounted when returning through legacy presentation", () => {
    const rendered = render(
      <Scope bucket={bucket} showLabels instrument onUseTrigger={vi.fn()} />,
    );
    fireEvent.click(screen.getByLabelText("Show, drag on the screen"));
    const cursor = screen.getByLabelText("Show, drag on the screen");
    rendered.rerender(
      <Scope bucket={bucket} showLabels onUseTrigger={vi.fn()} />,
    );
    expect(screen.getByLabelText("Show, drag on the screen")).toBe(cursor);
    expect(cursor).toBeChecked();
    rendered.rerender(
      <Scope bucket={bucket} showLabels instrument onUseTrigger={vi.fn()} />,
    );
    expect(screen.getByLabelText("Show, drag on the screen")).toBe(cursor);
    expect(cursor).toBeChecked();
  });

  it("selects the channel under a pointer using the same stacked geometry", () => {
    render(
      <Scope bucket={bucket} showLabels instrument onUseTrigger={vi.fn()} />,
    );
    const canvas = screen.getByLabelText(/Oscilloscope view of Mixed units/);
    Object.defineProperty(canvas, "setPointerCapture", { value: vi.fn() });
    fireEvent.pointerDown(canvas, { clientX: 120, clientY: 122 });
    expect(screen.getByText("Measure CH2, visible window")).toBeVisible();
    fireEvent.pointerUp(canvas);
  });

  it("shows missing data explicitly instead of calling the source good", () => {
    const missing: Bucket = {
      ...bucket,
      series: [
        { ...bucket.series[0], y: Float64Array.from([21, NaN, 23, 24]) },
      ],
    };
    render(
      <Scope bucket={missing} showLabels instrument onUseTrigger={vi.fn()} />,
    );
    expect(context.fillText.mock.calls.map((call) => call[0])).toContain(
      "Missing samples",
    );
  });

  it("focuses an actual episode without changing its values and shows the gap", () => {
    const episodes: Bucket = {
      ...bucket,
      series: [
        { ...bucket.series[0], y: Float64Array.from([21, 22, NaN, 24]) },
      ],
      episodes: [
        { seriesId: "temperature", episodeId: "thermal-1", start: 0, end: 2 },
        { seriesId: "temperature", episodeId: "thermal-2", start: 3, end: 4 },
      ],
    };
    const original = JSON.stringify(episodes);
    render(
      <Scope bucket={episodes} showLabels instrument onUseTrigger={vi.fn()} />,
    );
    const rail = within(
      screen.getByRole("region", { name: "Episode timeline" }),
    );
    expect(rail.getByText("Gap")).toBeVisible();
    fireEvent.click(
      rail.getByRole("button", { name: "Focus episode thermal-2" }),
    );
    const horizontal = screen.getByText("Horizontal").closest("fieldset")!;
    const position = within(horizontal).getByText("Position").parentElement!;
    expect(position.querySelector("output")).toHaveTextContent("3 s");
    expect(JSON.stringify(episodes)).toBe(original);
  });

  it("reports missing episode identities instead of fabricating a ruler", () => {
    render(
      <Scope bucket={bucket} showLabels instrument onUseTrigger={vi.fn()} />,
    );
    const rail = screen.getByRole("region", { name: "Episode timeline" });
    expect(rail).toHaveTextContent("Episode identities not supplied");
  });

  it("keeps an unknown quality byte unknown in the header and episode rail", () => {
    const unknown: Bucket = {
      ...bucket,
      series: [
        {
          ...bucket.series[0],
          quality: Uint8Array.from([7, 0, 0, 0]),
          qualityKnown: Uint8Array.from([0, 1, 1, 1]),
        },
      ],
      episodes: [
        {
          seriesId: "temperature",
          episodeId: "unknown-quality",
          start: 0,
          end: 4,
        },
      ],
    };
    render(
      <Scope bucket={unknown} showLabels instrument onUseTrigger={vi.fn()} />,
    );
    const labels = context.fillText.mock.calls.map((call) => call[0]);
    expect(labels).toContain("Quality not fully declared");
    expect(labels).not.toContain("Flagged samples present");
    const rail = screen.getByRole("region", { name: "Episode timeline" });
    expect(rail).toHaveTextContent("Quality not declared");
    expect(rail).not.toHaveTextContent("Flagged samples");
    expect(context.fillRect.mock.calls.every((call) => call[1] === 0)).toBe(
      true,
    );
  });

  it("does not shade unknown quality bytes when another sample is genuinely flagged", () => {
    const mixed: Bucket = {
      ...bucket,
      series: [
        {
          ...bucket.series[0],
          quality: Uint8Array.from([7, 2, 0, 0]),
          qualityKnown: Uint8Array.from([0, 1, 1, 1]),
        },
      ],
    };
    render(
      <Scope bucket={mixed} showLabels instrument onUseTrigger={vi.fn()} />,
    );
    const shaded = context.fillRect.mock.calls.filter((call) => call[1] > 0);
    expect(shaded.length).toBeGreaterThan(0);
    expect(shaded.every((call) => call[0] > 64)).toBe(true);
    expect(context.fillText.mock.calls.map((call) => call[0])).toContain(
      "Flagged samples present",
    );
  });
});
