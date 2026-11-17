import importlib
import json

import numpy as np
import pytest
from scipy.stats import norm

from opentsx import model


def test_random_walk_forecast_matches_analytic_mean_and_variance():
    api = importlib.import_module("opentsx.algorithms.forecasting")
    values = np.cumsum(np.random.default_rng(42).normal(size=200))
    record = api.forecast_arima(values, steps=6, order=(0, 1, 0))
    result = record["result"]
    np.testing.assert_allclose(result["mean"], values[-1], atol=1e-8)
    widths = np.array(result["upper"]) - np.array(result["lower"])
    np.testing.assert_allclose(widths / widths[0], np.sqrt(np.arange(1, 7)), rtol=1e-6)
    assert record["method"] == "arima"
    json.dumps(record, allow_nan=False)


@pytest.mark.parametrize("scale", [1e-3, 1e-4, 1e-5])
def test_small_amplitude_white_noise_matches_analytic_gaussian_likelihood(scale):
    api = importlib.import_module("opentsx.algorithms.forecasting")
    values = np.tile([-2.0, -1.0, 1.0, 2.0], 16) * scale
    record = api.forecast_arima(values, steps=3, order=(0, 0, 0))
    result = record["result"]
    variance = 2.5 * scale**2
    assert result["parameters"]["sigma2"] == pytest.approx(variance, rel=1e-8, abs=0)
    np.testing.assert_array_equal(result["mean"], np.zeros(3))
    bound = norm.ppf(0.975) * np.sqrt(variance)
    np.testing.assert_allclose(result["lower"], np.full(3, -bound), rtol=1e-8, atol=0)
    np.testing.assert_allclose(result["upper"], np.full(3, bound), rtol=1e-8, atol=0)
    likelihood = -len(values) / 2 * (np.log(2 * np.pi * variance) + 1)
    assert result["log_likelihood"] == pytest.approx(likelihood, rel=1e-8, abs=0)
    json.dumps(record, allow_nan=False)


def test_seasonal_random_walk_repeats_last_season():
    api = importlib.import_module("opentsx.algorithms.forecasting")
    values = np.random.default_rng(42).normal(size=240)
    for i in range(4, len(values)):
        values[i] += values[i - 4]
    record = api.forecast_arima(values, steps=8, order=(0, 0, 0), seasonal_order=(0, 1, 0, 4))
    np.testing.assert_allclose(record["result"]["mean"], np.tile(values[-4:], 2), atol=1e-8)
    assert record["method"] == "sarima"


def test_exogenous_forecast_matches_regression_and_cannot_change_training_fit():
    api = importlib.import_module("opentsx.algorithms.forecasting")
    x = np.linspace(-2, 2, 200).reshape(-1, 1)
    values = 2.5 * x[:, 0] + np.random.default_rng(42).normal(scale=0.1, size=200)
    future = np.array([[3.0], [4.0], [5.0]])
    first = api.forecast_arima(values, steps=3, order=(0, 0, 0), exog=x, future_exog=future)
    second = api.forecast_arima(values, steps=3, order=(0, 0, 0), exog=x, future_exog=future + 2)
    coefficient = np.dot(x[:, 0], values) / np.dot(x[:, 0], x[:, 0])
    np.testing.assert_allclose(first["result"]["mean"], coefficient * future[:, 0], atol=1e-3)
    assert first["result"]["parameters"] == second["result"]["parameters"]
    assert first["input"]["training_exog_sha256"] == second["input"]["training_exog_sha256"]
    assert first["input"]["future_exog_sha256"] != second["input"]["future_exog_sha256"]
    assert first["method"] == "sarimax"


def test_episode_forecast_preserves_unit_and_emits_next_sample_timestamps():
    api = importlib.import_module("opentsx.algorithms.forecasting")
    values = np.cumsum(np.random.default_rng(42).normal(size=100))
    episode = model.regular(model.series_key("power", {"asset": "A"}, "W"), 1000, 10, values)
    record = api.forecast_arima(episode, steps=3, order=(0, 1, 0))
    assert record["result"]["timestamps_micros"] == [2000, 2010, 2020]
    assert record["input"]["unit"] == "W"
    assert record["input"]["episode_id"] == episode["episodeId"]
    assert record["parameters"]["alpha"] == 0.05
    assert len(record["result"]["lower"]) == 3


@pytest.mark.parametrize(
    "kwargs",
    [
        {"steps": 0},
        {"steps": True},
        {"order": (1, -1, 0)},
        {"order": (1, 0)},
        {"seasonal_order": (1, 0, 0, 0)},
        {"seasonal_order": (0, 1, 0, 1)},
        {"alpha": float("nan")},
        {"trend": "bad"},
        {"maxiter": 0},
        {"future_exog": [[1.0]]},
        {"exog": np.ones((100, 1))},
        {"exog": np.ones((99, 1)), "future_exog": [[1.0]]},
        {"exog": np.ones((100, 1)), "future_exog": [[float("nan")]]},
    ],
)
def test_forecast_rejects_invalid_configuration(kwargs):
    api = importlib.import_module("opentsx.algorithms.forecasting")
    options = {"steps": 1, **kwargs}
    with pytest.raises(ValueError):
        api.forecast_arima(np.arange(100), **options)


def test_forecast_rejects_nonconverged_fit(monkeypatch):
    api = importlib.import_module("opentsx.algorithms.forecasting")

    class FailedFit:
        mle_retvals = {"converged": False}

    monkeypatch.setattr(api.SARIMAX, "fit", lambda *args, **kwargs: FailedFit())
    with pytest.raises(RuntimeError, match="converge"):
        api.forecast_arima(np.arange(100), steps=1)


def test_forecast_is_public():
    api = importlib.import_module("opentsx.algorithms.forecasting")
    import opentsx

    assert opentsx.forecast_arima is api.forecast_arima


def test_integrated_arma_recovers_known_short_memory_dynamics():
    api = importlib.import_module("opentsx.algorithms.forecasting")
    innovations = np.random.default_rng(42).normal(size=2000)
    increments = innovations.copy()
    for i in range(1, len(increments)):
        increments[i] += 0.5 * increments[i - 1] + 0.3 * innovations[i - 1]
    record = api.forecast_arima(np.cumsum(increments), steps=5, order=(1, 1, 1))
    result = record["result"]
    assert result["parameters"]["ar.L1"] == pytest.approx(0.5, abs=0.1)
    assert result["parameters"]["ma.L1"] == pytest.approx(0.3, abs=0.1)
    assert np.all(np.array(result["lower"]) < result["mean"])
    assert np.all(np.array(result["upper"]) > result["mean"])


def test_seasonal_exogenous_forecast_respects_both_components():
    api = importlib.import_module("opentsx.algorithms.forecasting")
    x = np.sin(np.arange(240) * 1.7).reshape(-1, 1)
    residual = np.random.default_rng(42).normal(scale=0.1, size=240)
    for i in range(4, len(residual)):
        residual[i] += residual[i - 4]
    values = 2.0 * x[:, 0] + residual
    future = np.sin(np.arange(240, 248) * 1.7).reshape(-1, 1)
    record = api.forecast_arima(
        values, steps=8, order=(0, 0, 0), seasonal_order=(0, 1, 0, 4), exog=x, future_exog=future
    )
    coefficient = record["result"]["parameters"]["x1"]
    assert coefficient == pytest.approx(2.0, abs=0.1)
    expected = np.tile(values[-4:] - coefficient * x[-4:, 0], 2) + coefficient * future[:, 0]
    np.testing.assert_allclose(record["result"]["mean"], expected, atol=1e-6)


def test_forecast_rejects_oversized_order_and_mismatched_future_columns():
    api = importlib.import_module("opentsx.algorithms.forecasting")
    with pytest.raises(ValueError, match="insufficient"):
        api.forecast_arima(np.arange(100), steps=2, order=(1000, 0, 0))
    with pytest.raises(ValueError, match="columns"):
        api.forecast_arima(
            np.arange(100), steps=2, exog=np.ones((100, 1)), future_exog=np.ones((2, 2))
        )
