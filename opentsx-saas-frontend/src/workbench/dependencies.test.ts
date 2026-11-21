import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";

describe("frontend runtime dependencies", () => {
  it("excludes unsupported MapLibre releases from the production lockfile", () => {
    const lock = JSON.parse(readFileSync("package-lock.json", "utf8")) as {
      packages: Record<string, { version?: string; dev?: boolean }>;
    };
    const unsupported = Object.entries(lock.packages)
      .filter(
        ([path, entry]) =>
          path.endsWith("node_modules/maplibre-gl") && !entry.dev,
      )
      .filter(([, entry]) => {
        if (!entry.version || !/^\d+\.\d+\.\d+$/.test(entry.version))
          return true;
        const [major, minor, patch] = entry.version.split(".").map(Number);
        return (
          major < 6 ||
          (major === 6 && (minor < 4 || (minor === 4 && patch < 1)))
        );
      })
      .map(([path, entry]) => ({ path, version: entry.version }));
    expect(unsupported).toEqual([]);
  });
});
