import json

import numpy as np
import pandas as pd
import pytest

from opentsx import TimeSeriesObject, TSBucket


def test_default_timestamps_are_index(rng):
    ts = TimeSeriesObject(data=rng.normal(size=10), label="x")
    assert len(ts) == 10
    np.testing.assert_array_equal(ts.timestamps, np.arange(10))


def test_dict_json_roundtrip_is_lossless(rng):
    ts = TimeSeriesObject(
        data=rng.normal(size=100),
        timestamps=np.arange(100) * 1000.0,
        label="sensor.temperature",
        metadata={"unit": "Cel", "source": "test"},
    )
    loaded = TimeSeriesObject.from_dict(json.loads(json.dumps(ts.to_dict())))

    assert loaded.label == ts.label
    assert loaded.metadata == ts.metadata
    np.testing.assert_array_equal(loaded.values, ts.values)
    np.testing.assert_array_equal(loaded.timestamps, ts.timestamps)


def test_describe_matches_numpy(rng):
    data = rng.normal(loc=3.0, scale=2.0, size=1000)
    ts = TimeSeriesObject(data=data)
    assert ts.mean() == pytest.approx(np.mean(data))
    assert ts.std() == pytest.approx(np.std(data))
    assert ts.min() == pytest.approx(np.min(data))
    assert ts.max() == pytest.approx(np.max(data))


def test_zscore_normalization(rng):
    ts = TimeSeriesObject(data=rng.normal(loc=5.0, scale=3.0, size=500)).normalize("zscore")
    assert ts.mean() == pytest.approx(0.0, abs=1e-12)
    assert ts.std() == pytest.approx(1.0)
    assert ts.metadata["normalization"] == "zscore"


def test_detrend_removes_linear_trend(rng):
    x = np.arange(1000, dtype=float)
    ts = TimeSeriesObject(data=0.5 * x + 10.0 + rng.normal(scale=0.1, size=1000))
    slope = np.polyfit(x, ts.detrend(order=1).values, 1)[0]
    assert slope == pytest.approx(0.0, abs=1e-9)


def test_diff_shortens_series_and_keeps_alignment():
    ts = TimeSeriesObject(data=[1.0, 4.0, 9.0, 16.0], timestamps=[10, 20, 30, 40])
    d = ts.diff()
    np.testing.assert_array_equal(d.values, [3.0, 5.0, 7.0])
    np.testing.assert_array_equal(d.timestamps, [20, 30, 40])


def test_resample_block_mean():
    ts = TimeSeriesObject(data=np.arange(10, dtype=float))
    np.testing.assert_array_equal(ts.resample(5, "mean").values, [2.0, 7.0])


def test_from_pandas_keeps_values_and_order():
    idx = pd.date_range("2026-01-01", periods=5, freq="10min", tz="UTC")
    s = pd.Series([1.0, 2.0, 3.0, 4.0, 5.0], index=idx, name="wind_speed")
    ts = TimeSeriesObject.from_pandas(s)
    np.testing.assert_array_equal(ts.values, s.to_numpy())
    assert np.all(np.diff(ts.timestamps) > 0)


@pytest.mark.xfail(strict=True, reason="Known gap (TASK-011): pandas timezone/time unit is not preserved")
def test_pandas_roundtrip_preserves_datetime_index():
    idx = pd.date_range("2026-01-01", periods=3, freq="10min", tz="UTC")
    s = pd.Series([1.0, 2.0, 3.0], index=idx)
    back = TimeSeriesObject.from_pandas(s).to_pandas()
    assert isinstance(back.index, pd.DatetimeIndex)
    assert str(back.index.tz) == "UTC"


def test_bucket_apply_and_filter(rng):
    bucket = TSBucket([TimeSeriesObject(data=rng.normal(size=50), label=f"s{i}") for i in range(4)])
    assert len(bucket) == 4
    normalized = bucket.apply(lambda ts: ts.normalize())
    assert all(abs(ts.mean()) < 1e-12 for ts in normalized)
    assert len(bucket.filter(lambda ts: ts.label in ("s0", "s1"))) == 2
