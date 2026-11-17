import importlib
import json

import numpy as np
import pytest


def test_rolling_origins_have_analytic_drift_and_naive_errors():
    api = importlib.import_module("opentsx.algorithms.evaluation")
    record = api.rolling_backtest(
        np.arange(20), initial=8, horizon=3, step=4, methods=["naive", "drift"]
    )
    result = record["result"]
    assert [f["training_stop"] for f in result["folds"]] == [8, 12, 16]
    naive = result["metrics"]["naive"]
    assert naive["mae"] == 2
    assert naive["rmse"] == pytest.approx(np.sqrt(14 / 3))
    assert naive["mase"] == 2
    assert naive["rmsse"] == pytest.approx(np.sqrt(14 / 3))
    assert result["metrics"]["drift"]["mae"] == 0
    assert result["metrics"]["drift"]["coverage"] == 1
    assert len(result["cases"]["naive"]) == 9
    assert result["by_horizon"]["naive"]["3"]["mae"] == 3
    json.dumps(record, allow_nan=False)


def test_seasonal_naive_and_zero_training_scales_are_explicit():
    api = importlib.import_module("opentsx.algorithms.evaluation")
    result = api.rolling_backtest(
        np.tile([10, 20, 30, 40], 8),
        initial=12,
        horizon=4,
        methods=["seasonal_naive"],
        seasonal_period=4,
    )["result"]
    scores = result["metrics"]["seasonal_naive"]
    assert scores["mae"] == 0
    assert scores["mase"] is None
    assert scores["rmsse"] is None
    assert scores["undefined_scaled_cases"] == scores["cases"]
    assert scores["scaled_error_status"] == "undefined_training_scale"


def test_sliding_windows_and_arima_receive_only_training_samples(monkeypatch):
    api = importlib.import_module("opentsx.algorithms.evaluation")
    seen = []

    def forecast(values, *, steps, **kwargs):
        seen.append(np.array(values))
        return {
            "result": {
                "mean": [values[-1]] * steps,
                "lower": [values[-1] - 1] * steps,
                "upper": [values[-1] + 1] * steps,
            },
            "warnings": [],
        }

    monkeypatch.setattr(api, "forecast_arima", forecast)
    result = api.rolling_backtest(
        np.arange(24), initial=12, horizon=2, step=5, window=8, methods=["arima"]
    )["result"]
    assert len(seen) == 3
    for data, fold in zip(seen, result["folds"]):
        np.testing.assert_array_equal(
            data, np.arange(24)[fold["training_start"] : fold["training_stop"]]
        )
        assert len(data) == 8


def test_interval_score_and_coverage_match_closed_form(monkeypatch):
    api = importlib.import_module("opentsx.algorithms.evaluation")
    monkeypatch.setattr(
        api,
        "forecast_arima",
        lambda *args, **kwargs: {
            "result": {"mean": [0], "lower": [-1], "upper": [1]},
            "warnings": [],
        },
    )
    scores = api.rolling_backtest(np.arange(12), initial=8, methods=["arima"])["result"]["metrics"][
        "arima"
    ]
    assert scores["coverage"] == 0
    assert scores["mean_interval_width"] == 2
    assert scores["mean_interval_score"] == 342


def test_future_changes_cannot_change_first_training_transform_or_predictions():
    api = importlib.import_module("opentsx.algorithms.evaluation")
    values = np.exp(np.linspace(0, 1, 48))
    changed = values.copy()
    changed[27:] *= 1.2
    options = dict(initial=24, horizon=3, step=6, methods=["naive"], transform="boxcox")
    first = api.rolling_backtest(values, **options)["result"]
    second = api.rolling_backtest(changed, **options)["result"]
    assert first["folds"][0] == second["folds"][0]
    assert first["cases"]["naive"][:3] == second["cases"]["naive"][:3]


def test_real_arima_and_naive_random_walk_forecasts_agree():
    api = importlib.import_module("opentsx.algorithms.evaluation")
    values = np.cumsum(np.random.default_rng(42).normal(size=120))
    result = api.rolling_backtest(
        values,
        initial=60,
        horizon=3,
        step=30,
        methods=["naive", "arima"],
        arima_options={"order": (0, 1, 0)},
    )["result"]
    np.testing.assert_allclose(
        [r["predicted"] for r in result["cases"]["naive"]],
        [r["predicted"] for r in result["cases"]["arima"]],
        atol=1e-8,
    )


@pytest.mark.parametrize(
    "options",
    [
        {"initial": 7},
        {"initial": True},
        {"initial": 100},
        {"horizon": 0},
        {"step": 0},
        {"window": 30},
        {"window": 7},
        {"methods": ["bad"]},
        {"methods": ["naive", "naive"]},
        {"seasonal_period": 0},
        {"methods": ["seasonal_naive"]},
        {"transform": "bad"},
        {"transform_lambda": 0},
        {"arima_options": {"exog": []}},
        {"alpha": 0},
    ],
)
def test_backtest_rejects_invalid_configuration(options):
    api = importlib.import_module("opentsx.algorithms.evaluation")
    with pytest.raises(ValueError):
        api.rolling_backtest(np.arange(40), **{"initial": 12, **options})


def test_mean_baseline_and_fixed_log_transform_are_reproducible():
    api = importlib.import_module("opentsx.algorithms.evaluation")
    values = np.arange(1, 25, dtype=float)
    result = api.rolling_backtest(
        values,
        initial=12,
        horizon=2,
        step=20,
        methods=["mean"],
        transform="boxcox",
        transform_lambda=0,
    )["result"]
    expected = np.exp(np.mean(np.log(values[:12])))
    np.testing.assert_allclose([c["predicted"] for c in result["cases"]["mean"]], expected)
    assert result["folds"][0]["transform"]["estimated"] is False


def test_invalid_backend_intervals_fail_instead_of_returning_partial_scores(monkeypatch):
    api = importlib.import_module("opentsx.algorithms.evaluation")
    monkeypatch.setattr(
        api,
        "forecast_arima",
        lambda *args, **kwargs: {
            "result": {"mean": [0], "lower": [2], "upper": [3]},
            "warnings": [],
        },
    )
    with pytest.raises(RuntimeError, match="interval"):
        api.rolling_backtest(np.arange(20), initial=12, methods=["arima"])


def test_episode_evaluation_preserves_origin_identity_and_target_times():
    api = importlib.import_module("opentsx.algorithms.evaluation")
    from opentsx import model
    import opentsx

    episode = model.regular(model.series_key("load", {}, "W"), 1000, 10, np.arange(20))
    record = api.rolling_backtest(episode, initial=8, horizon=3, step=10, methods=["naive"])
    assert record["input"]["episode_id"] == episode["episodeId"]
    assert record["input"]["unit"] == "W"
    assert [c["timestamp_micros"] for c in record["result"]["cases"]["naive"][:3]] == [
        1080,
        1090,
        1100,
    ]
    assert opentsx.rolling_backtest is api.rolling_backtest


def test_exogenous_backtest_splits_regressors_at_each_origin():
    api = importlib.import_module("opentsx.algorithms.evaluation")
    x = np.linspace(-2, 2, 200).reshape(-1, 1)
    values = 2.5 * x[:, 0] + np.random.default_rng(42).normal(scale=0.1, size=200)
    result = api.rolling_backtest(
        values,
        initial=100,
        horizon=3,
        step=50,
        methods=["arima"],
        exog=x,
        arima_options={"order": (0, 0, 0)},
    )["result"]
    for fold in result["folds"]:
        stop = fold["training_stop"]
        coefficient = np.dot(x[:stop, 0], values[:stop]) / np.dot(x[:stop, 0], x[:stop, 0])
        rows = [c for c in result["cases"]["arima"] if c["origin"] == stop]
        np.testing.assert_allclose(
            [c["predicted"] for c in rows], coefficient * x[stop : stop + 3, 0], atol=1e-3
        )


def test_exogenous_backtest_rejects_misaligned_or_unused_regressors():
    api = importlib.import_module("opentsx.algorithms.evaluation")
    with pytest.raises(ValueError):
        api.rolling_backtest(np.arange(40), initial=12, methods=["arima"], exog=np.ones((39, 1)))
    with pytest.raises(ValueError):
        api.rolling_backtest(np.arange(40), initial=12, methods=["naive"], exog=np.ones((40, 1)))
