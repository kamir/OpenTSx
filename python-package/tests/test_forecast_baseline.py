import importlib
import json

import numpy as np
import pytest

from opentsx import model


@pytest.mark.parametrize("method", ["mean", "naive", "drift", "seasonal_naive"])
def test_baseline_forecasts_match_analytic_values_without_mutating_input(method):
    api = importlib.import_module("opentsx.algorithms.evaluation")
    values = np.arange(12, dtype=float)
    original = values.copy()
    record = api.forecast_baseline(values, steps=4, method=method, seasonal_period=4)
    expected = {
        "mean": [5.5] * 4,
        "naive": [11] * 4,
        "drift": [12, 13, 14, 15],
        "seasonal_naive": [8, 9, 10, 11],
    }
    result = record["result"]
    np.testing.assert_allclose(result["mean"], expected[method])
    np.testing.assert_array_equal(values, original)
    assert record["method"] == "baseline_forecast"
    assert record["parameters"]["method"] == method
    assert np.all(np.array(result["lower"]) <= result["mean"])
    assert np.all(np.array(result["upper"]) >= result["mean"])
    json.dumps(record, allow_nan=False)


def test_naive_interval_growth_and_constant_baseline_are_explicit():
    api = importlib.import_module("opentsx.algorithms.evaluation")
    result = api.forecast_baseline(np.arange(12), steps=4)["result"]
    width = np.array(result["upper"]) - np.array(result["lower"])
    np.testing.assert_allclose(width / width[0], np.sqrt(np.arange(1, 5)))
    constant = api.forecast_baseline(np.full(12, 7.0), steps=2)["result"]
    assert constant["mean"] == [7.0, 7.0]
    assert constant["interval_status"] == ["degenerate", "degenerate"]


def test_baseline_episode_preserves_identity_unit_and_next_timestamps():
    api = importlib.import_module("opentsx.algorithms.evaluation")
    episode = model.regular(model.series_key("power", {"asset": "A"}, "W"), 1000, 10, np.arange(12))
    record = api.forecast_baseline(episode, steps=3)
    assert record["input"]["episode_id"] == episode["episodeId"]
    assert record["input"]["unit"] == "W"
    assert record["result"]["timestamps_micros"] == [1120, 1130, 1140]


@pytest.mark.parametrize(
    "options",
    [
        {"steps": 0},
        {"steps": True},
        {"method": "unknown"},
        {"method": "seasonal_naive", "seasonal_period": 1},
        {"method": "seasonal_naive", "seasonal_period": 12},
        {"alpha": 0},
        {"seasonal_period": True},
    ],
)
def test_baseline_rejects_invalid_configuration(options):
    api = importlib.import_module("opentsx.algorithms.evaluation")
    with pytest.raises(ValueError):
        api.forecast_baseline(np.arange(12), **{"steps": 2, **options})


def test_baseline_is_exported_without_replacing_the_numerical_function():
    api = importlib.import_module("opentsx.algorithms.evaluation")
    import opentsx
    from opentsx import algorithms

    assert opentsx.forecast_baseline is api.forecast_baseline
    assert algorithms.forecast_baseline is api.forecast_baseline
