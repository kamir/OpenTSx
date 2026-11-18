import { expect, it } from "vitest";
import { thermalDemo } from "./deviceSample";

it("retains independent units, an absolute clock and explicit gaps in deterministic thermal episodes", () => {
  const bucket = thermalDemo();
  expect(bucket).toEqual(thermalDemo());
  expect(bucket.t0Ms).toBe(1795003200000);
  expect(bucket.series.map((s) => s.unit)).toEqual(["°C", "%"]);
  expect(bucket.series.map((s) => s.y.length)).toEqual([512, 512]);
  expect(bucket.episodes).toHaveLength(8);
  expect(bucket.episodes?.every((e) => e.end - e.start === 128)).toBe(true);
  expect(
    Array.from(bucket.series[0].y).filter((v) => !Number.isFinite(v)),
  ).toHaveLength(20);
  expect(Array.from(bucket.series[1].y).every(Number.isFinite)).toBe(true);
  expect(bucket.series[0].quality?.[290]).toBe(1);
  expect(bucket.episodes?.every((e) => e.labels?.origin === "SYNTHETIC")).toBe(
    true,
  );
});
