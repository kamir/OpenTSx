import type { Bucket } from "./types";

const DIAGNOSTIC_LIMIT = 2048;

export function prepareDiagnostic(
  bucket: Bucket,
  seriesId: string,
  episodeId: string,
  allowUnknown: boolean,
): Bucket {
  if (bucket.xKind !== "time" && bucket.xKind !== "index")
    throw new Error(
      "Select a time-domain or sample-index input for diagnostics.",
    );
  const series = bucket.series.find((item) => item.id === seriesId);
  if (!series || series.x.length !== series.y.length)
    throw new Error(
      "The selected series has incompatible coordinates and values.",
    );
  const episode = episodeId
    ? bucket.episodes?.find(
        (item) => item.episodeId === episodeId && item.seriesId === seriesId,
      )
    : undefined;
  if (episodeId && !episode)
    throw new Error("The selected episode is unavailable.");
  const indices: number[] = [];
  for (let i = 0; i < series.x.length; i++) {
    if (
      !episode ||
      (series.x[i] >= episode.start && series.x[i] < episode.end)
    ) {
      indices.push(i);
      if (indices.length > DIAGNOSTIC_LIMIT)
        throw new Error(
          "Select an episode with at most 2048 samples for diagnostics.",
        );
    }
  }
  if (indices.length < 4)
    throw new Error("Diagnostics require at least four selected samples.");
  if (
    (series.quality && series.quality.length !== series.y.length) ||
    (series.qualityKnown && series.qualityKnown.length !== series.y.length)
  ) {
    throw new Error("Quality and sample arrays have different lengths.");
  }
  const x = Float64Array.from(indices, (i) => series.x[i]);
  const y = Float64Array.from(indices, (i) => series.y[i]);
  if (
    Array.from(x).some((value) => !Number.isFinite(value)) ||
    Array.from(y).some((value) => !Number.isFinite(value))
  ) {
    throw new Error(
      "The selection contains missing or non-finite samples. Choose a gap-free episode.",
    );
  }
  const step = x[1] - x[0];
  if (
    !(step > 0) ||
    Array.from(x).some(
      (value, i) =>
        i > 0 &&
        Math.abs(value - x[i - 1] - step) > Math.max(1e-9, step * 1e-8),
    )
  ) {
    throw new Error(
      "Diagnostics require a regular increasing clock. Prepare the input explicitly.",
    );
  }
  if (
    indices.some(
      (i) =>
        series.quality &&
        series.qualityKnown?.[i] !== 0 &&
        series.quality[i] !== 0,
    )
  ) {
    throw new Error(
      "The selection contains flagged samples. Inspect their source-defined quality codes first.",
    );
  }
  if (
    !allowUnknown &&
    indices.some((i) => !series.quality || series.qualityKnown?.[i] === 0)
  ) {
    throw new Error(
      "Quality is unknown for selected samples. Acknowledge unknown quality before running diagnostics.",
    );
  }
  return {
    ...bucket,
    name: episode?.episodeId ?? bucket.name,
    series: [
      {
        ...series,
        x,
        y,
        quality: series.quality
          ? Uint8Array.from(indices, (i) => series.quality![i])
          : undefined,
        qualityKnown: series.qualityKnown
          ? Uint8Array.from(indices, (i) => series.qualityKnown![i])
          : undefined,
      },
    ],
    episodes: episode ? [episode] : [],
  };
}
