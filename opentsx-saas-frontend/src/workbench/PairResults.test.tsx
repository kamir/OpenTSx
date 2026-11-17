import {
  cleanup,
  fireEvent,
  render,
  screen,
  within,
} from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import type { ComponentType } from "react";
import type { Bucket, Series } from "./types";

afterEach(cleanup);

function field(
  a: string,
  b: string,
  name: string,
  values: number[],
  quality?: number[],
): Series {
  return {
    id: `${a}-${b}-${name}`,
    label: `${name}(${a}, ${b})`,
    metric: `pair.es.${name}`,
    tags: { a, b, window: "PT10S", step: "PT10S" },
    x: Float64Array.from([5, 15]),
    y: Float64Array.from(values),
    ...(quality ? { quality: Uint8Array.from(quality) } : {}),
  };
}

function fixture(): Bucket {
  const series = [
    field("wind", "price", "q_total", [0.8, 0.7]),
    field("wind", "price", "q_rand", [0.2, 0.3]),
    field("wind", "price", "q_ab", [0.6, 0.5]),
    field("wind", "price", "q_ba", [0.2, 0.2]),
    field("wind", "load", "q_total", [0.4, NaN], [0, 1]),
    field("wind", "load", "q_rand", [0.1, NaN], [0, 1]),
  ];
  return {
    name: "ES",
    xKind: "time",
    xLabel: "t [s]",
    yLabel: "Q",
    t0Ms: 1790000000000,
    series,
    episodes: series.map((s) => ({
      seriesId: s.id,
      episodeId: `${s.id}-result`,
      start: 5,
      end: 25,
      labels: {
        "pair.a": `${s.tags!.a}-episode`,
        "pair.b": `${s.tags!.b}-episode`,
        "pair.method": "pair.es",
        "pair.seed": "7",
      },
    })),
  };
}

async function show(bucket: Bucket) {
  const files = import.meta.glob("./PairResults.tsx");
  const load = files["./PairResults.tsx"];
  if (!load) throw new Error("The pair result view is missing.");
  const module = (await load()) as {
    default: ComponentType<{ bucket: Bucket }>;
  };
  return render(<module.default bucket={bucket} />);
}

describe("PairResults", () => {
  it("renders nothing for an ordinary time-series bucket", async () => {
    const b = fixture();
    b.series = [{ ...b.series[0], metric: "voltage" }];
    const { container } = await show(b);
    expect(container).toBeEmptyDOMElement();
  });

  it("selects a field and window without mixing the two windows", async () => {
    await show(fixture());
    expect(
      screen.getByRole("table", { name: "Pair result values" }),
    ).toBeInTheDocument();
    expect(screen.getByText("2 pair windows")).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText("Result field"), {
      target: { value: "q_rand" },
    });
    fireEvent.change(screen.getByLabelText("Window centre"), {
      target: { value: "15" },
    });
    expect(
      within(
        screen.getByRole("table", { name: "Pair result values" }),
      ).getByText("0.3"),
    ).toBeInTheDocument();
    expect(
      within(
        screen.getByRole("table", { name: "Pair result values" }),
      ).queryByText("0.8"),
    ).not.toBeInTheDocument();
  });

  it("filters pair identifiers and sorts selected field values", async () => {
    await show(fixture());
    fireEvent.change(screen.getByLabelText("Sort pairs"), {
      target: { value: "ascending" },
    });
    let rows = within(
      screen.getByRole("table", { name: "Pair result values" }),
    ).getAllByRole("row");
    expect(rows[1]).toHaveTextContent("load");
    fireEvent.change(screen.getByLabelText("Filter pairs"), {
      target: { value: "price" },
    });
    rows = within(
      screen.getByRole("table", { name: "Pair result values" }),
    ).getAllByRole("row");
    expect(rows).toHaveLength(2);
    expect(rows[1]).toHaveTextContent("price");
  });

  it("keeps the matrix directional and names absent pairs", async () => {
    await show(fixture());
    const matrix = screen.getByRole("table", { name: "Pair matrix" });
    expect(
      within(matrix).getByRole("button", { name: "wind to price: 0.8" }),
    ).toBeInTheDocument();
    expect(
      within(matrix).queryByRole("button", { name: "price to wind: 0.8" }),
    ).not.toBeInTheDocument();
    expect(screen.getByText(/Rows are A, columns are B/)).toBeInTheDocument();
  });

  it("shows measured synchronization and surrogate baseline with both input episodes", async () => {
    await show(fixture());
    fireEvent.click(
      screen.getByRole("button", { name: "Inspect wind and price at 5 s" }),
    );
    const detail = screen.getByRole("region", { name: "Selected pair window" });
    expect(detail).toHaveTextContent("q_total: 0.8");
    expect(detail).toHaveTextContent("q_rand: 0.2");
    expect(detail).toHaveTextContent("wind-episode");
    expect(detail).toHaveTextContent("price-episode");
    expect(detail).toHaveTextContent("seed: 7");
    expect(detail).toHaveTextContent(
      "A surrogate mean is a baseline, not a significance test.",
    );
    expect(detail).toHaveTextContent("q_ab: B follows A; q_ba: A follows B.");
  });

  it("shows low coverage as unavailable rather than a zero measurement", async () => {
    await show(fixture());
    fireEvent.change(screen.getByLabelText("Window centre"), {
      target: { value: "15" },
    });
    fireEvent.change(screen.getByLabelText("Filter pairs"), {
      target: { value: "load" },
    });
    const table = screen.getByRole("table", { name: "Pair result values" });
    expect(table).toHaveTextContent("Unavailable");
    expect(table).toHaveTextContent("Below minimum coverage");
    expect(
      screen.getByRole("button", { name: "wind to load: Unavailable" }),
    ).toBeInTheDocument();
  });

  it("does not infer supplied quality from a finite imported value", async () => {
    await show(fixture());
    const table = screen.getByRole("table", { name: "Pair result values" });
    expect(table).toHaveTextContent("Quality not supplied");
  });

  it("shows an explicit empty state for a filter with no pairs", async () => {
    await show(fixture());
    fireEvent.change(screen.getByLabelText("Filter pairs"), {
      target: { value: "absent" },
    });
    expect(
      screen.getByText("No pair windows match these filters."),
    ).toBeInTheDocument();
  });

  it("explains positive cross-correlation lag without claiming causality", async () => {
    const b = fixture();
    b.series = ["max", "lag", "strength"].map((name, i) => ({
      ...field(
        "wind",
        "price",
        name,
        [
          [0.9, 0.8],
          [3, 4],
          [2, 2],
        ][i],
      ),
      metric: `pair.xcorr.${name}`,
    }));
    b.episodes = [];
    await show(b);
    expect(
      screen.getByText(
        "Positive lag means B follows A. Statistical dependence does not establish causality.",
      ),
    ).toBeInTheDocument();
  });
});
