import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import Workbench from "../pages/Workbench";
import { useFlow } from "./flow";
import { parseEpisodes } from "./loaders";

vi.mock("./Scope", () => ({ default: () => <div>Scope preview</div> }));
vi.mock("./FlowGraph", () => ({ default: () => <div>Flow preview</div> }));

beforeEach(() => {
  useFlow.getState().clear();
  useFlow.getState().loadBucket(
    parseEpisodes(
      [
        {
          seriesId: "a",
          episodeId: "a0",
          timestampsMicros: [0, 1e6, 2e6, 3e6],
          values: [0, 2, 0, 1],
          unit: "MW",
          quality: [0, 0, 0, 0],
        },
        {
          seriesId: "b",
          episodeId: "b0",
          timestampsMicros: [0, 1e6, 2e6, 3e6],
          values: [1, 0, 2, 0],
          unit: "MW",
          quality: [0, 0, 0, 0],
        },
      ],
      "input",
    ),
    { kind: "file", detail: "test fixture" },
  );
});

describe("workbench readiness views", () => {
  it("mounts episode evidence and the reproducible analysis bundle controls", () => {
    render(
      <MemoryRouter>
        <Workbench />
      </MemoryRouter>,
    );
    fireEvent.click(screen.getByRole("tab", { name: "Episodes and quality" }));
    expect(
      screen.getByRole("region", { name: "Episode quality and provenance" }),
    ).toBeInTheDocument();
    fireEvent.click(screen.getByRole("tab", { name: "Snapshot" }));
    expect(
      screen.getByRole("region", { name: "Local analysis snapshot" }),
    ).toBeInTheDocument();
  });
  it("mounts pair results and truthful runtime capability beside a pair operator", () => {
    useFlow.getState().applyOp("pair.es");
    render(
      <MemoryRouter>
        <Workbench />
      </MemoryRouter>,
    );
    fireEvent.click(screen.getByRole("tab", { name: "Pair results" }));
    expect(
      screen.getByRole("region", { name: "Pair analysis results" }),
    ).toBeInTheDocument();
    expect(screen.getByText("Kernel only")).toBeInTheDocument();
  });
});
