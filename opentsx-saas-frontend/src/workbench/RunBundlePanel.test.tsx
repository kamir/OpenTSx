import { webcrypto } from "node:crypto";
import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { ComponentType } from "react";
import type { FlowDoc } from "./flow";
import type { Bucket } from "./types";
import { createRunBundle } from "./runBundle";

const modules = import.meta.glob("./RunBundlePanel.tsx");
const source: Bucket = {
  name: "power",
  xKind: "time",
  xLabel: "s",
  yLabel: "kW",
  series: [
    {
      id: "p",
      label: "power",
      x: Float64Array.of(0, 1),
      y: Float64Array.of(2, 3),
    },
  ],
};
const doc: FlowDoc = {
  track: "power",
  domain: "energy",
  sourceTopic: "tsx.energy.episodes.v2",
  nodes: [
    { id: "source", input: null, op: "source", params: {}, label: "power" },
  ],
};
async function panel() {
  expect(
    modules["./RunBundlePanel.tsx"],
    "Run bundle panel is available",
  ).toBeTypeOf("function");
  return (
    (await modules["./RunBundlePanel.tsx"]()) as {
      default: ComponentType<{
        source: Bucket | null;
        result: Bucket | null;
        doc: FlowDoc;
        onImport: (source: Bucket, doc: FlowDoc) => void;
      }>;
    }
  ).default;
}
beforeEach(() => vi.stubGlobal("crypto", webcrypto));
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe("local snapshot controls", () => {
  it("states capture limits and disables export without data", async () => {
    const Panel = await panel();
    render(<Panel source={null} result={null} doc={doc} onImport={vi.fn()} />);
    expect(
      screen.getByRole("button", { name: "Download local snapshot" }),
    ).toBeDisabled();
    expect(
      screen.getByText(/Broker offsets and catalog version are unavailable/),
    ).toBeInTheDocument();
  });
  it("restores only a verified local source and graph", async () => {
    const Panel = await panel();
    const onImport = vi.fn();
    render(<Panel source={null} result={null} doc={doc} onImport={onImport} />);
    const text = await createRunBundle(source, source, doc);
    const file = new File([text], "run.json", { type: "application/json" });
    Object.defineProperty(file, "text", { value: async () => text });
    fireEvent.change(screen.getByLabelText("Import local snapshot"), {
      target: { files: [file] },
    });
    await waitFor(() => expect(onImport).toHaveBeenCalledWith(source, doc));
    expect(screen.getByRole("status")).toHaveTextContent("restored locally");
  });
  it("shows an integrity error without importing corrupted content", async () => {
    const Panel = await panel();
    const onImport = vi.fn();
    render(
      <Panel source={source} result={null} doc={doc} onImport={onImport} />,
    );
    const text = (await createRunBundle(source, null, doc)).replace(
      "browser-finite",
      "changed",
    );
    const file = new File([text], "run.json");
    Object.defineProperty(file, "text", { value: async () => text });
    fireEvent.change(screen.getByLabelText("Import local snapshot"), {
      target: { files: [file] },
    });
    expect(await screen.findByRole("alert")).toHaveTextContent(/integrity/i);
    expect(onImport).not.toHaveBeenCalled();
  });
});
