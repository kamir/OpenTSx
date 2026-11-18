import { useState } from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { act, fireEvent, render, screen, within } from "@testing-library/react";
import { DeviceInspector, DeviceSources } from "./DeviceTools";
import { SOURCE_ID, useFlow } from "./flow";
import { OP_BY_ID } from "./ops";
import type { Bucket } from "./types";

const source: Bucket = {
  name: "Thermal fixture",
  xKind: "time",
  xLabel: "Time [s]",
  yLabel: "Value",
  t0Ms: 1795003200000,
  series: [
    {
      id: "temperature",
      label: "Panel temperature",
      unit: "C",
      x: new Float64Array([0, 1, 2, 3]),
      y: new Float64Array([20, 21, 22, 21]),
    },
    {
      id: "heater",
      label: "Heater schedule",
      unit: "%",
      x: new Float64Array([0, 1, 2, 3]),
      y: new Float64Array([0, 50, 50, 0]),
    },
  ],
};

function Tools() {
  const [draftOp, setDraftOp] = useState<string | null>(null);
  return (
    <>
      <DeviceSources
        bucket={source}
        draftOp={draftOp}
        onChooseOperator={setDraftOp}
        onModelling={vi.fn()}
      />
      <DeviceInspector draftOp={draftOp} onApplied={() => setDraftOp(null)} />
    </>
  );
}

beforeEach(() => {
  useFlow.getState().clear();
  useFlow.getState().loadBucket(source, {
    kind: "generator",
    detail: "Thermal fixture (synthetic)",
  });
});

describe("device operator tools", () => {
  it("shows a short alignment explanation while retaining complete method details behind a disclosure", () => {
    render(<DeviceInspector draftOp="align.vector" onApplied={vi.fn()} />);
    expect(
      screen.getByText("Map selected signals onto a shared clock."),
    ).toBeVisible();
    const hint = screen.getByText(OP_BY_ID["align.vector"].hint);
    expect(hint).not.toBeVisible();
    fireEvent.click(screen.getByText("Method details"));
    expect(hint).toBeVisible();
    expect(screen.getByLabelText("Sample interval [s]")).toBeVisible();
  });

  it("configures a discovered operator without changing the graph until explicitly added", () => {
    render(<Tools />);
    fireEvent.change(screen.getByLabelText("Find an operator"), {
      target: { value: "Align to vector" },
    });
    fireEvent.click(
      screen.getByRole("button", { name: "Configure Align to vector" }),
    );
    expect(useFlow.getState().nodes).toHaveLength(1);
    const inspector = within(
      screen.getByRole("complementary", { name: "Operator inspector" }),
    );
    fireEvent.change(inspector.getByLabelText("Sample interval [s]"), {
      target: { value: "1" },
    });
    fireEvent.click(inspector.getByRole("button", { name: "Add operator" }));
    expect(useFlow.getState().nodes).toHaveLength(2);
    expect(useFlow.getState().nodes[1]).toMatchObject({
      op: "align.vector",
      params: { every: 1 },
    });
  });

  it("keeps draft parameters when browsing another operator and returning", () => {
    const { rerender } = render(
      <DeviceInspector draftOp="align.vector" onApplied={vi.fn()} />,
    );
    fireEvent.change(screen.getByLabelText("Sample interval [s]"), {
      target: { value: "2" },
    });
    rerender(<DeviceInspector draftOp="resample" onApplied={vi.fn()} />);
    rerender(<DeviceInspector draftOp="align.vector" onApplied={vi.fn()} />);
    expect(screen.getByLabelText("Sample interval [s]")).toHaveValue(2);
    expect(useFlow.getState().nodes).toHaveLength(1);
  });

  it("rejects empty numeric drafts before changing the graph", () => {
    render(<DeviceInspector draftOp="align.vector" onApplied={vi.fn()} />);
    fireEvent.change(screen.getByLabelText("Sample interval [s]"), {
      target: { value: "" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Add operator" }));
    expect(screen.getByRole("alert")).toHaveTextContent(
      "Sample interval [s] requires a finite number",
    );
    expect(useFlow.getState().nodes).toHaveLength(1);
  });

  it("reports invalid operator syntax without adding a broken graph node", () => {
    render(<DeviceInspector draftOp="select" onApplied={vi.fn()} />);
    fireEvent.change(screen.getByLabelText("pattern"), {
      target: { value: "[" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Add operator" }));
    expect(screen.getByRole("alert")).toHaveTextContent(
      "Pattern is not a valid regular expression",
    );
    expect(useFlow.getState().nodes).toHaveLength(1);
  });

  it("reports an unknown operator rather than editing the selected source", () => {
    render(<DeviceInspector draftOp="unknown.operation" onApplied={vi.fn()} />);
    expect(screen.getByRole("alert")).toHaveTextContent(
      "Unknown operator: unknown.operation",
    );
    expect(
      screen.queryByRole("button", { name: "Add operator" }),
    ).not.toBeInTheDocument();
  });

  it("uses the existing runtime capability for the selected operator", () => {
    const { rerender } = render(
      <DeviceInspector draftOp="fft" onApplied={vi.fn()} />,
    );
    const processor = screen.getByTestId("device-processor-capability");
    expect(processor).toHaveTextContent("Unavailable");
    rerender(<DeviceInspector draftOp="align.vector" onApplied={vi.fn()} />);
    expect(screen.getByTestId("device-processor-capability")).toHaveTextContent(
      "Available",
    );
  });

  it("previews edited node parameters before explicitly applying them", () => {
    const id = useFlow.getState().applyOp("resample", { every: 1 });
    render(<DeviceInspector draftOp={null} onApplied={vi.fn()} />);
    fireEvent.change(screen.getByLabelText("every"), {
      target: { value: "2" },
    });
    expect(
      useFlow.getState().nodes.find((n) => n.id === id)?.params.every,
    ).toBe(1);
    fireEvent.click(screen.getByRole("button", { name: "Apply parameters" }));
    expect(
      useFlow.getState().nodes.find((n) => n.id === id)?.params.every,
    ).toBe(2);
  });

  it("does not reuse a staged node edit after loading a different source with recycled node ids", () => {
    const first = useFlow.getState().applyOp("resample", { every: 1 });
    render(<DeviceInspector draftOp={null} onApplied={vi.fn()} />);
    fireEvent.change(screen.getByLabelText("every"), {
      target: { value: "2" },
    });
    expect(screen.getByLabelText("every")).toHaveValue(2);
    act(() => {
      useFlow.getState().clear();
      useFlow
        .getState()
        .loadBucket(
          { ...source, name: "Other source" },
          { kind: "file", detail: "other-source.csv" },
        );
      expect(useFlow.getState().applyOp("resample", { every: 1 })).toBe(first);
    });
    expect(screen.getByLabelText("every")).toHaveValue(1);
    expect(useFlow.getState().nodes[1].params.every).toBe(1);
  });

  it("adds and removes a permitted extra input while excluding descendants", () => {
    const branch = useFlow.getState().applyOp("diff")!;
    useFlow.getState().select(SOURCE_ID);
    const target = useFlow.getState().applyOp("align.vector", { every: 1 })!;
    const downstream = useFlow.getState().applyOp("diff")!;
    useFlow.getState().select(target);
    render(<DeviceInspector draftOp={null} onApplied={vi.fn()} />);
    const input = screen.getByLabelText("Add input");
    expect(
      within(input).queryByRole("option", {
        name: useFlow.getState().nodes.find((n) => n.id === downstream)!.label,
      }),
    ).not.toBeInTheDocument();
    fireEvent.change(input, { target: { value: branch } });
    expect(
      useFlow.getState().nodes.find((n) => n.id === target)?.inputs,
    ).toEqual([branch]);
    fireEvent.click(screen.getByRole("button", { name: "Remove input 2" }));
    expect(
      useFlow.getState().nodes.find((n) => n.id === target)?.inputs,
    ).toEqual([]);
  });

  it("labels synthetic sources without claiming a broker connection", () => {
    render(
      <DeviceSources
        bucket={source}
        draftOp={null}
        onChooseOperator={vi.fn()}
        onModelling={vi.fn()}
      />,
    );
    expect(screen.getByText("Synthetic data")).toBeInTheDocument();
    expect(screen.getByText("Panel temperature")).toBeInTheDocument();
    expect(screen.getByText("%")).toBeInTheDocument();
    expect(screen.queryByText(/connected/i)).not.toBeInTheDocument();
  });

  it("opens statistical forecasting explicitly without adding an operator", () => {
    const onModelling = vi.fn();
    render(
      <DeviceSources
        bucket={source}
        draftOp={null}
        onChooseOperator={vi.fn()}
        onModelling={onModelling}
      />,
    );
    fireEvent.change(screen.getByLabelText("Find an operator"), {
      target: { value: "SARIMAX" },
    });
    fireEvent.click(
      screen.getByRole("button", { name: "Open statistical forecasting" }),
    );
    expect(onModelling).toHaveBeenCalledOnce();
    expect(useFlow.getState().nodes).toHaveLength(1);
  });
});
