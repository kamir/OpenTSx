import { describe, expect, it } from "vitest";
import { render, screen } from "@testing-library/react";
import { bucketToCsv, parseDelimited, parseEpisodes } from "./loaders";

const episode = {
  episodeId: "episode-a",
  seriesId: "series-a",
  metric: "price",
  unit: "EUR/MWh",
  timestampsMicros: [1e6, 2e6],
  values: [-10, 150],
  quality: [0, 2],
  labels: { source: "fixture" },
  parentEpisodeId: "parent-a",
  segmentation: { strategy: "FIXED_DURATION", params: { duration: "PT2S" } },
};

describe("episode quality and source identity", () => {
  it("retains sorted quality, episode labels, parent and segmentation settings", () => {
    const b = parseEpisodes(
      [
        { ...episode, timestampsMicros: [3e6, 4e6] },
        { ...episode, episodeId: "earlier" },
      ],
      "input",
    );
    expect(Array.from(b.series[0].quality ?? [])).toEqual([0, 2, 0, 2]);
    expect(b.episodes?.[0]).toMatchObject({
      labels: { source: "fixture" },
      parent: "parent-a",
      segmentation: episode.segmentation,
    });
  });
  it("keeps missing quality explicitly unknown when episodes share a series", () => {
    const b = parseEpisodes(
      [
        episode,
        {
          ...episode,
          episodeId: "unknown",
          timestampsMicros: [3e6, 4e6],
          quality: undefined,
        },
      ],
      "input",
    );
    expect(
      Array.from(
        (b.series[0] as unknown as { qualityKnown: Uint8Array }).qualityKnown ??
          [],
      ),
    ).toEqual([1, 1, 0, 0]);
  });
  it.each([
    { ...episode, quality: [0] },
    { ...episode, timestampsMicros: [1e6] },
    { ...episode, quality: [0, 256] },
    { ...episode, timestampsMicros: [1e6, NaN] },
    { ...episode, values: [1, "wrong"] },
    { ...episode, labels: { source: 4 } },
  ])("rejects malformed episode arrays and metadata", (raw) => {
    expect(() => parseEpisodes([raw], "input")).toThrow();
  });
  it("rejects conflicting units for episodes of the same series", () => {
    expect(() =>
      parseEpisodes([episode, { ...episode, unit: "MW" }], "input"),
    ).toThrow(/unit/i);
  });
  it("exports the stable series id rather than the display label", () => {
    const restored = parseDelimited(
      bucketToCsv(parseEpisodes([episode], "input")),
      "csv",
    );
    expect(restored.series[0].id).toBe("series-a");
  });
  it("shows quality counts and retained episode provenance without calling unknown data good", async () => {
    const modules = import.meta.glob<{
      default: (props: {
        bucket: ReturnType<typeof parseEpisodes>;
      }) => JSX.Element;
    }>("./EpisodeInspector.tsx");
    expect(modules["./EpisodeInspector.tsx"]).toBeDefined();
    const { default: EpisodeInspector } =
      await modules["./EpisodeInspector.tsx"]();
    render(<EpisodeInspector bucket={parseEpisodes([episode], "input")} />);
    expect(
      screen.getByRole("region", { name: "Episode quality and provenance" }),
    ).toBeInTheDocument();
    expect(screen.getByText("parent-a")).toBeInTheDocument();
    expect(screen.getByText("EUR/MWh")).toBeInTheDocument();
    expect(screen.getByText(/Quality codes are retained/)).toBeInTheDocument();
  });
});
