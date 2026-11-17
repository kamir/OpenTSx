import { describe, expect, it, vi, afterEach } from "vitest";
import {
  modellingRequest,
  prepareTarget,
  validateOptions,
  type ModellingOptions,
} from "./modelling";
import type { Bucket } from "./types";
const bucket: Bucket = {
  name: "fixture",
  xKind: "time",
  xLabel: "t [s]",
  yLabel: "kW",
  t0Ms: 1700000000000,
  series: [
    {
      id: "stable",
      label: "Readable label",
      unit: "kW",
      x: Float64Array.from({ length: 48 }, (_, i) => i * 60),
      y: Float64Array.from({ length: 48 }, (_, i) => (i % 7) + 1),
      quality: new Uint8Array(48),
    },
  ],
  episodes: [
    { seriesId: "stable", episodeId: "ep1", start: 0, end: 1440 },
    { seriesId: "stable", episodeId: "ep2", start: 1440, end: 2880 },
  ],
};
function options(): ModellingOptions {
  return {
    family: "arima",
    start: 0,
    stop: 48,
    steps: 4,
    order: [1, 0, 0],
    seasonalOrder: [0, 0, 0, 0],
    trend: "n",
    alpha: 0.05,
    maxiter: 100,
    initial: 24,
    horizon: 4,
    step: 4,
    window: 0,
    seasonalPeriod: 4,
    baselines: ["naive", "mean"],
    contextText: "",
  };
}
afterEach(() => vi.unstubAllGlobals());
describe("modelling request boundary", () => {
  it("keeps the selected regular window, identity, quality, unit and absolute timestamps", () => {
    const o = { ...options(), start: 8, stop: 40 };
    const result = modellingRequest(bucket, bucket.series[0], o);
    expect(result.target.series_id).toBe("stable");
    expect(result.target.values).toHaveLength(32);
    expect(result.target.timestamps_micros[0]).toBe(1700000480000000);
    expect(result.target.time_kind).toBe("absolute");
    expect(result.target.episode_ids).toEqual(["ep1", "ep2"]);
    expect(result.target.unit).toBe("kW");
    expect(result.target.quality).toHaveLength(32);
    expect(result.backtest.window).toBeNull();
  });
  it("marks relative and sample-index axes instead of inventing absolute provenance", () => {
    const relative = { ...bucket, t0Ms: undefined };
    expect(
      modellingRequest(relative, relative.series[0], options()).target
        .time_kind,
    ).toBe("relative");
    const index = { ...relative, xKind: "index" as const };
    const result = modellingRequest(index, index.series[0], options());
    expect(result.target.time_kind).toBe("index");
    expect(result.target.timestamps_micros[1]).toBe(60e6);
  });
  it("does not add a retained absolute origin to a sample-index axis", () => {
    const index = { ...bucket, xKind: "index" as const };
    const result = modellingRequest(index, index.series[0], options());
    expect(result.target.time_kind).toBe("index");
    expect(result.target.timestamps_micros.slice(0, 2)).toEqual([0, 60e6]);
  });
  it("refuses an axis that is not time or sample index", () => {
    expect(() =>
      prepareTarget({ ...bucket, xKind: "frequency" }, bucket.series[0], 0, 48),
    ).toThrow(/time or sample/);
  });
  it("does not fill missing target values silently", () => {
    const series = {
      ...bucket.series[0],
      y: Float64Array.from(bucket.series[0].y, (x, i) => (i === 10 ? NaN : x)),
    };
    expect(() => prepareTarget(bucket, series, 0, 48)).toThrow(/missing/);
  });
  it("preserves availability evidence and both regressor matrices for SARIMAX", () => {
    const context = {
      names: ["calendar"],
      historical: Array.from({ length: 48 }, () => [1]),
      historical_available_at_micros: Array.from({ length: 48 }, () => [0]),
      future: [[1], [1], [1], [1]],
      future_available_at_micros: [[0], [0], [0], [0]],
    };
    const result = modellingRequest(bucket, bucket.series[0], {
      ...options(),
      family: "sarimax",
      contextText: JSON.stringify(context),
    });
    expect(result.context).toEqual(context);
    expect(result.configuration.family).toBe("sarimax");
  });
  it("rejects excessive forecast origins before the numerical service", () => {
    expect(() =>
      validateOptions({ ...options(), initial: 8, horizon: 1, step: 1 }, 48),
    ).toThrow(/12 origins/);
  });
  it("requires a naive reference and bounds both forecast horizon and ARIMA orders", () => {
    expect(() =>
      validateOptions({ ...options(), baselines: ["mean"] }, 48),
    ).toThrow(/naive baseline/);
    expect(() => validateOptions({ ...options(), steps: 97 }, 48)).toThrow(
      /Forecast horizon/,
    );
    expect(() =>
      validateOptions({ ...options(), order: [4, 0, 0] }, 48),
    ).toThrow(/ARIMA order/);
  });
  it("does not send hidden external regressors when a non-SARIMAX family is chosen", () => {
    const request = modellingRequest(bucket, bucket.series[0], {
      ...options(),
      contextText: '{"unused":"context"}',
    });
    expect(request.context).toBeNull();
  });
});
