import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, fireEvent, render, screen, within } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import Workbench from "../pages/Workbench";
import { useFlow } from "./flow";
import { parseEpisodes } from "./loaders";

vi.mock("./Scope", async () => {
  const { useState } = await import("react");
  function ScopePreview() {
    const [offset, setOffset] = useState("0");
    return (
      <label>
        Scope cursor offset
        <input
          value={offset}
          onChange={(event) => setOffset(event.target.value)}
        />
      </label>
    );
  }
  return { default: ScopePreview };
});
vi.mock("./FlowGraph", () => ({ default: () => <div>Flow preview</div> }));

const preferenceKey = "opentsx.workbench.presentation.v1";

function mount(path = "/workbench") {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Workbench />
    </MemoryRouter>,
  );
}

function chooseLayout(name: string) {
  const selector = screen.getByLabelText("Workspace layout");
  const option = within(selector).getByRole("option", {
    name,
  }) as HTMLOptionElement;
  fireEvent.change(selector, { target: { value: option.value } });
}

function selectedLayout() {
  const selector = screen.getByLabelText(
    "Workspace layout",
  ) as HTMLSelectElement;
  return selector.selectedOptions[0].textContent;
}

function openSignal(interval?: string) {
  act(() => {
    useFlow.getState().setContext({
      system: "demo-plant",
      systemTitle: "Demo plant",
      signal: "market.price",
      grade: "raw",
      expect: {
        unit: "EUR/MWh",
        min: -500,
        max: 3000,
        ...(interval === undefined ? {} : { interval }),
      },
      origin: "REPORTED",
      synthetic: false,
    });
  });
}

function configure(name: string) {
  fireEvent.click(screen.getByRole("button", { name: `Configure ${name}` }));
  return within(
    screen.getByRole("complementary", { name: "Operator inspector" }),
  );
}

function flowSnapshot() {
  const state = useFlow.getState();
  return JSON.stringify({
    source: state.source,
    sourceInfo: state.sourceInfo,
    sourceSeries: state.sourceSeries,
    nodes: state.nodes,
    selectedId: state.selectedId,
    track: state.track,
    domain: state.domain,
    context: state.context,
    wayang: state.wayang,
    savedId: state.savedId,
  });
}

beforeEach(() => {
  localStorage.clear();
  useFlow.getState().clear();
  useFlow.getState().setTrack("Mode regression");
  useFlow.getState().loadBucket(
    parseEpisodes(
      [
        {
          seriesId: "temperature",
          episodeId: "temperature-0",
          timestampsMicros: Array.from(
            { length: 64 },
            (_, index) => 1795003200000000 + index * 1e6,
          ),
          values: Array.from({ length: 64 }, (_, index) => 21 + index / 64),
          unit: "C",
          quality: Array(64).fill(0),
        },
        {
          seriesId: "heater",
          episodeId: "heater-0",
          timestampsMicros: Array.from(
            { length: 64 },
            (_, index) => 1795003200000000 + index * 1e6,
          ),
          values: Array.from({ length: 64 }, (_, index) =>
            index < 32 ? 0 : 40,
          ),
          unit: "%",
          quality: Array(64).fill(0),
        },
      ],
      "Mode fixture",
    ),
    { kind: "file", detail: "mode-regression.json" },
  );
});

afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

describe("workbench presentation modes", () => {
  it("offers two engineer layouts, two data layouts and an explicit legacy fallback", () => {
    mount();
    expect(
      screen.getByRole("button", { name: "Signal Engineer" }),
    ).toHaveAttribute("aria-pressed", "true");
    expect(selectedLayout()).toBe("Instrument Console");
    chooseLayout("Diagnostic Bench");
    expect(selectedLayout()).toBe("Diagnostic Bench");
    fireEvent.click(screen.getByRole("button", { name: "Data Worker" }));
    expect(selectedLayout()).toBe("Pipeline Studio");
    chooseLayout("Recipe Notebook");
    expect(selectedLayout()).toBe("Recipe Notebook");
    fireEvent.click(screen.getByRole("button", { name: "Legacy" }));
    const operations = within(
      screen.getByRole("region", { name: "Operations" }),
    );
    fireEvent.click(operations.getByRole("tab", { name: "Transformations" }));
    expect(operations.getByRole("button", { name: "Shuffle" })).toBeEnabled();
  });

  it("keeps source, graph, selection and fitted pipeline settings unchanged without a request", () => {
    useFlow.getState().applyOp("shuffle", { rounds: 2, seed: 37 });
    const before = flowSnapshot();
    const request = vi.fn();
    vi.stubGlobal("fetch", request);
    mount();
    chooseLayout("Diagnostic Bench");
    fireEvent.click(screen.getByRole("button", { name: "Data Worker" }));
    chooseLayout("Recipe Notebook");
    fireEvent.click(screen.getByRole("button", { name: "Legacy" }));
    fireEvent.click(screen.getByRole("button", { name: "Signal Engineer" }));
    expect(flowSnapshot()).toBe(before);
    expect(request).not.toHaveBeenCalled();
  });

  it("retains the same mounted scope and its cursor state across all modes", () => {
    mount();
    const cursor = screen.getByLabelText("Scope cursor offset");
    fireEvent.change(cursor, { target: { value: "17" } });
    chooseLayout("Diagnostic Bench");
    fireEvent.click(screen.getByRole("button", { name: "Data Worker" }));
    chooseLayout("Recipe Notebook");
    fireEvent.click(screen.getByRole("button", { name: "Legacy" }));
    fireEvent.click(screen.getByRole("button", { name: "Signal Engineer" }));
    chooseLayout("Instrument Console");
    expect(screen.getByLabelText("Scope cursor offset")).toBe(cursor);
    expect(cursor).toHaveValue("17");
  });

  it("retains the finite modelling editor and draft forecast horizon across mode changes", () => {
    mount();
    fireEvent.click(
      within(screen.getByRole("tablist", { name: "Analysis views" })).getByRole(
        "tab",
        { name: "Modelling" },
      ),
    );
    const horizon = screen.getByLabelText("Forecast steps");
    fireEvent.change(horizon, { target: { value: "9" } });
    fireEvent.click(screen.getByRole("button", { name: "Data Worker" }));
    chooseLayout("Recipe Notebook");
    fireEvent.click(screen.getByRole("button", { name: "Legacy" }));
    fireEvent.click(screen.getByRole("button", { name: "Signal Engineer" }));
    fireEvent.click(
      within(screen.getByRole("tablist", { name: "Analysis views" })).getByRole(
        "tab",
        { name: "Modelling" },
      ),
    );
    expect(screen.getByLabelText("Forecast steps")).toBe(horizon);
    expect(horizon).toHaveValue(9);
    expect(
      screen.getAllByRole("region", { name: "Modelling and prediction" }),
    ).toHaveLength(1);
  });

  it("remembers each modern layout and the selected mode across a remount", () => {
    const first = mount();
    chooseLayout("Diagnostic Bench");
    fireEvent.click(screen.getByRole("button", { name: "Data Worker" }));
    chooseLayout("Recipe Notebook");
    first.unmount();
    mount();
    expect(screen.getByRole("button", { name: "Data Worker" })).toHaveAttribute(
      "aria-pressed",
      "true",
    );
    expect(selectedLayout()).toBe("Recipe Notebook");
    fireEvent.click(screen.getByRole("button", { name: "Signal Engineer" }));
    expect(selectedLayout()).toBe("Diagnostic Bench");
  });

  it("opens the legacy fallback directly even when a modern preference is saved", () => {
    localStorage.setItem(
      preferenceKey,
      JSON.stringify({
        mode: "data",
        engineer: "diagnostics",
        data: "notebook",
      }),
    );
    mount("/workbench?mode=legacy");
    expect(screen.getByRole("button", { name: "Legacy" })).toHaveAttribute(
      "aria-pressed",
      "true",
    );
    expect(screen.getByRole("region", { name: "Operations" })).toBeVisible();
  });

  it("reports malformed saved preferences without changing the working data", () => {
    localStorage.setItem(preferenceKey, "{malformed");
    const before = flowSnapshot();
    mount();
    expect(screen.getByRole("alert")).toHaveTextContent(
      /presentation|preference/i,
    );
    expect(
      screen.getByRole("button", { name: "Signal Engineer" }),
    ).toHaveAttribute("aria-pressed", "true");
    expect(flowSnapshot()).toBe(before);
  });

  it("retains the workspace and fails closed when browser storage reads are blocked", () => {
    const before = flowSnapshot();
    vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => {
      throw new DOMException("Storage blocked", "SecurityError");
    });
    expect(() => mount()).not.toThrow();
    expect(screen.getByRole("alert")).toHaveTextContent(/could not be read/i);
    expect(
      screen.getByRole("link", { name: "Sign in to save flows" }),
    ).toBeVisible();
    expect(
      screen.queryByRole("button", { name: "Save flow" }),
    ).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Data Worker" }));
    expect(screen.getByRole("button", { name: "Data Worker" })).toHaveAttribute(
      "aria-pressed",
      "true",
    );
    expect(screen.getByRole("alert")).toHaveTextContent(/storage.*read/i);
    expect(flowSnapshot()).toBe(before);
  });

  it("reports a blocked sign-in read even when presentation preferences remain readable", () => {
    vi.spyOn(Storage.prototype, "getItem").mockImplementation((key) => {
      if (key === "access_token")
        throw new DOMException("Storage blocked", "SecurityError");
      return null;
    });
    expect(() => mount()).not.toThrow();
    expect(screen.getByRole("alert")).toHaveTextContent(/storage.*read/i);
    expect(
      screen.getByRole("link", { name: "Sign in to save flows" }),
    ).toBeVisible();
    expect(
      screen.queryByRole("button", { name: "Save flow" }),
    ).not.toBeInTheDocument();
  });

  it("keeps the selected layout and working data when preference writes fail", () => {
    const before = flowSnapshot();
    mount();
    vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
      throw new DOMException("Storage full", "QuotaExceededError");
    });
    fireEvent.click(screen.getByRole("button", { name: "Data Worker" }));
    chooseLayout("Recipe Notebook");
    expect(screen.getByRole("button", { name: "Data Worker" })).toHaveAttribute(
      "aria-pressed",
      "true",
    );
    expect(selectedLayout()).toBe("Recipe Notebook");
    expect(screen.getByRole("alert")).toHaveTextContent(
      /preference could not be saved/i,
    );
    expect(flowSnapshot()).toBe(before);
  });
});

describe("operator presets of a signal opened on the workbench", () => {
  const intervals: [string | undefined, number][] = [
    ["PT15M", 900],
    ["PT1H", 3600],
    ["P1D", 86400],
    ["PT1S", 60],
    [undefined, 60],
  ];

  it.each(intervals)(
    "presets the inspector of both modern modes for interval %s",
    (interval, every) => {
      mount();
      openSignal(interval);
      for (const mode of ["Signal Engineer", "Data Worker"]) {
        fireEvent.click(screen.getByRole("button", { name: mode }));
        expect(configure("Resample").getByLabelText("every")).toHaveValue(
          every,
        );
        const range = configure("Plausible range");
        expect(range.getByLabelText("min")).toHaveValue(-500);
        expect(range.getByLabelText("max")).toHaveValue(3000);
      }
    },
  );

  it.each(intervals)(
    "presets the legacy operation panel for interval %s",
    (interval, every) => {
      mount("/workbench?mode=legacy");
      openSignal(interval);
      const operations = within(
        screen.getByRole("region", { name: "Operations" }),
      );
      fireEvent.click(
        operations.getByRole("tab", { name: "Filter and conversion" }),
      );
      expect(operations.getByLabelText("every")).toHaveValue(every);
      expect(operations.getByLabelText("min")).toHaveValue(-500);
      expect(operations.getByLabelText("max")).toHaveValue(3000);
    },
  );

  it("leaves the resample width empty when the interval is no duration", () => {
    mount();
    openSignal("15 minutes");
    const inspector = configure("Resample");
    expect(inspector.getByLabelText("every")).toHaveValue(null);
    fireEvent.click(inspector.getByRole("button", { name: "Add operator" }));
    expect(inspector.getByRole("alert")).toHaveTextContent(
      "every requires a finite number.",
    );
    expect(useFlow.getState().nodes).toHaveLength(1);
    fireEvent.click(screen.getByRole("button", { name: "Legacy" }));
    const operations = within(
      screen.getByRole("region", { name: "Operations" }),
    );
    fireEvent.click(
      operations.getByRole("tab", { name: "Filter and conversion" }),
    );
    expect(operations.getByLabelText("every")).toHaveValue(null);
  });
});
