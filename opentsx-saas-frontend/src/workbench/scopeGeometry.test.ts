import { describe, expect, it } from "vitest";
import {
  scopeBands,
  scopeChannelAtY,
  scopeDragDelta,
  scopeValueAtY,
  scopeX,
  scopeY,
} from "./scopeGeometry";

describe("scope coordinate geometry", () => {
  it("retains full-canvas legacy coordinates for every visible channel", () => {
    const bands = scopeBands(1000, 200, [true, false, true], false);
    expect(bands).toEqual([
      { channel: 0, left: 0, top: 0, width: 1000, height: 200 },
      { channel: 2, left: 0, top: 0, width: 1000, height: 200 },
    ]);
    expect(scopeY(12, 10, 2, bands[0])).toBe(75);
    expect(scopeX(2, 0, 1, bands[0])).toBe(200);
    expect(scopeDragDelta(100, 25, 1, 2, bands[0])).toEqual({ x: 1, y: -2 });
  });

  it("separates temperature and percentage bands without mixing their scales", () => {
    const [temperature, heater] = scopeBands(1000, 420, [true, true], true);
    expect(temperature.top + temperature.height).toBeLessThan(heater.top);
    expect(scopeY(23, 23, 0.5, temperature)).toBe(107);
    expect(scopeY(25, 23, 0.5, temperature)).toBe(temperature.top);
    expect(scopeY(100, 50, 12.5, heater)).toBe(heater.top);
    expect(scopeY(0, 50, 12.5, heater)).toBe(heater.top + heater.height);
  });

  it("inverts cursor coordinates in each independently scaled channel", () => {
    const [temperature, heater] = scopeBands(1000, 420, [true, true], true);
    expect(
      scopeValueAtY(scopeY(23.75, 23, 0.5, temperature), 23, 0.5, temperature),
    ).toBeCloseTo(23.75);
    expect(
      scopeValueAtY(scopeY(42, 50, 12.5, heater), 50, 12.5, heater),
    ).toBeCloseTo(42);
    expect(scopeDragDelta(92, 21.25, 1, 0.5, temperature)).toEqual({
      x: 1,
      y: -0.5,
    });
  });

  it("retains channel identity and compacts hidden channels", () => {
    const bands = scopeBands(1000, 420, [false, true, false, true], true);
    expect(bands.map((band) => band.channel)).toEqual([1, 3]);
    expect(scopeChannelAtY(50, bands)).toBe(1);
    expect(scopeChannelAtY(250, bands)).toBe(3);
    expect(scopeChannelAtY(5, bands)).toBeNull();
    expect(scopeChannelAtY(210, bands)).toBeNull();
    expect(scopeBands(1000, 420, [false, false], true)).toEqual([]);
  });

  it("includes the instrument axis margin in the shared horizontal coordinate", () => {
    const [band] = scopeBands(1000, 420, [true], true);
    expect(scopeX(0, 0, 1, band)).toBe(64);
    expect(scopeX(10, 0, 1, band)).toBe(984);
  });

  it.each([
    [0, 200],
    [1000, 0],
    [NaN, 200],
    [1000, Infinity],
  ])("rejects invalid dimensions %s by %s", (width, height) => {
    expect(() => scopeBands(width, height, [true], true)).toThrow(
      "positive finite",
    );
  });
});
