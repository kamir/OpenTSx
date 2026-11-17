import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, within } from "@testing-library/react";
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
  it("replaces the legacy modelling placeholders with an entry to the finite workflow", () => {
    render(
      <MemoryRouter>
        <Workbench />
      </MemoryRouter>,
    );
    const operations = within(
      screen.getByRole("region", { name: "Operations" }),
    );
    fireEvent.click(
      operations.getByRole("tab", { name: /^(TS-Model|Modelling)$/ }),
    );
    expect(
      screen.queryByText(/These buttons have no action in the Swing workbench/),
    ).not.toBeInTheDocument();
    for (const name of ["AR", "MA", "ARMA", "ARIMA", "SARIMA", "CARIMA"])
      expect(
        operations.queryByRole("button", { name }),
      ).not.toBeInTheDocument();
    fireEvent.click(operations.getByRole("button", { name: "Open modelling" }));
    expect(
      screen.getByRole("region", { name: "Modelling and prediction" }),
    ).toBeVisible();
    expect(screen.getByLabelText("Forecast family")).toHaveValue("arima");
    expect(screen.getByLabelText("Naive baseline")).toBeChecked();
  });
  it("retains the modelling editor when switching between analysis views", () => {
    render(
      <MemoryRouter>
        <Workbench />
      </MemoryRouter>,
    );
    const views = within(
      screen.getByRole("tablist", { name: "Analysis views" }),
    );
    fireEvent.click(views.getByRole("tab", { name: "Modelling" }));
    fireEvent.change(screen.getByLabelText("Forecast steps"), {
      target: { value: "9" },
    });
    fireEvent.click(views.getByRole("tab", { name: "Scope" }));
    expect(
      screen.queryByRole("region", { name: "Modelling and prediction" }),
    ).not.toBeInTheDocument();
    fireEvent.click(views.getByRole("tab", { name: "Modelling" }));
    expect(screen.getByLabelText("Forecast steps")).toHaveValue(9);
    expect(
      screen.getAllByRole("region", { name: "Modelling and prediction" }),
    ).toHaveLength(1);
  });
});
