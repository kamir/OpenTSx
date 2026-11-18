import { parseEpisodes } from "./loaders";

export function thermalDemo() {
  const epoch = 1795003200000000;
  return parseEpisodes(
    ["temperature", "heater"].flatMap((name, channel) =>
      Array.from({ length: 4 }, (_, episode) => {
        const indices = Array.from(
          { length: 128 },
          (_, i) => episode * 128 + i,
        );
        return {
          seriesId: `thermal-${name}`,
          metric: channel === 0 ? "Panel temperature" : "Heater schedule",
          unit: channel === 0 ? "°C" : "%",
          episodeId: `${name}-E${episode + 1}`,
          intervalMicros: 1000000,
          timestampsMicros: indices.map((i) => epoch + i * 1000000),
          values: indices.map((i) =>
            channel === 0
              ? i >= 290 && i < 310
                ? null
                : 23 +
                  1.3 * Math.sin(i / 55) +
                  0.6 * Math.sin(i / 103) +
                  0.04 * Math.sin(i * 1.7)
              : [0, 40, 80, 20, 0, 40, 100, 60][Math.floor(i / 64)],
          ),
          quality: indices.map((i) =>
            channel === 0 && i >= 290 && i < 310 ? 1 : 0,
          ),
          labels: { origin: "SYNTHETIC", fixture: "thermal-bench-v1" },
        };
      }),
    ),
    "Thermal test / Research 042",
  );
}
