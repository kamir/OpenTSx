import importlib
import json

import numpy as np
import pytest

from opentsx import model


def test_stl_recovers_known_trend_and_season_and_reconstructs_signal():
    api = importlib.import_module("opentsx.algorithms.decomposition")
    t = np.arange(240)
    trend = 10 + 0.02 * t
    seasonal = 3 * np.sin(2 * np.pi * t / 12)
    record = api.seasonal_decomposition(trend + seasonal, periods=12)
    result = record["result"]
    np.testing.assert_allclose(result["trend"], trend, atol=1e-8)
    np.testing.assert_allclose(result["seasonal"]["12"], seasonal, atol=1e-8)
    np.testing.assert_allclose(result["residual"], 0, atol=1e-8)
    assert record["parameters"]["causal"] is False
    json.dumps(record, allow_nan=False)


def test_mstl_recovers_two_seasons_with_explicit_ordered_components():
    api = importlib.import_module("opentsx.algorithms.decomposition")
    t = np.arange(840)
    trend = 10 + 0.01 * t
    seasons = {7: np.sin(2 * np.pi * t / 7), 24: 2 * np.cos(2 * np.pi * t / 24)}
    values = trend + seasons[7] + seasons[24]
    result = api.seasonal_decomposition(values, periods=[24, 7], windows=[15, 11])["result"]
    reconstructed = np.array(result["trend"]) + result["residual"]
    for period, expected in seasons.items():
        actual = np.array(result["seasonal"][str(period)])
        assert np.sqrt(np.mean((actual[50:-50] - expected[50:-50]) ** 2)) < 0.03
        reconstructed += actual
    np.testing.assert_allclose(reconstructed, values, atol=1e-10)


@pytest.mark.parametrize(
    "periods,windows",
    [(120, None), ([12, 12], None), (1, None), ([12, 24], [11]), ([12], [6]), (True, None)],
)
def test_decomposition_rejects_invalid_or_silently_removable_periods(periods, windows):
    api = importlib.import_module("opentsx.algorithms.decomposition")
    with pytest.raises(ValueError):
        api.seasonal_decomposition(np.arange(240), periods=periods, windows=windows)


@pytest.mark.parametrize(
    "method,lmbda",
    [
        ("boxcox", 0.0),
        ("boxcox", 0.5),
        ("boxcox", -0.5),
        ("yeojohnson", 0.0),
        ("yeojohnson", 1.0),
        ("yeojohnson", 2.0),
        ("yeojohnson", -0.3),
    ],
)
def test_power_transforms_roundtrip_including_special_lambda_cases(method, lmbda):
    api = importlib.import_module("opentsx.algorithms.decomposition")
    values = np.linspace(0.2, 9, 100) if method == "boxcox" else np.linspace(-9, 9, 101)
    record = api.power_transform(values, method=method, lmbda=lmbda)
    recovered = api.inverse_power_transform(record["result"]["values"], method=method, lmbda=lmbda)
    np.testing.assert_allclose(recovered["result"]["values"], values, atol=1e-10)
    assert record["parameters"]["estimated"] is False
    assert record["backend"]["name"] == "scipy"
    json.dumps(recovered, allow_nan=False)


def test_estimated_transform_can_be_reused_without_refitting_on_short_forecast():
    api = importlib.import_module("opentsx.algorithms.decomposition")
    values = np.exp(np.linspace(-1, 2, 100))
    record = api.power_transform(values, method="boxcox")
    lmbda = record["parameters"]["lambda"]
    assert np.isfinite(lmbda)
    assert record["parameters"]["estimated"] is True
    transformed = api.power_transform([3.0], method="boxcox", lmbda=lmbda)
    inverse = api.inverse_power_transform(
        transformed["result"]["values"], method="boxcox", lmbda=lmbda
    )
    assert inverse["result"]["values"] == pytest.approx([3.0])


@pytest.mark.parametrize(
    "method,data,lmbda,inverse",
    [
        ("boxcox", [-1, 0, 1] * 4, None, False),
        ("boxcox", [-3], 0.5, True),
        ("yeojohnson", [2], -1, True),
        ("yeojohnson", [-2], 3, True),
        ("bad", list(range(1, 12)), None, False),
        ("boxcox", list(range(1, 12)), float("nan"), False),
    ],
)
def test_power_transforms_reject_invalid_domains(method, data, lmbda, inverse):
    api = importlib.import_module("opentsx.algorithms.decomposition")
    function = api.inverse_power_transform if inverse else api.power_transform
    with pytest.raises(ValueError):
        function(data, method=method, lmbda=lmbda)


def test_decomposition_and_transform_preserve_episode_identity():
    api = importlib.import_module("opentsx.algorithms.decomposition")
    values = 10 + np.sin(np.arange(120) * 2 * np.pi / 12)
    episode = model.regular(model.series_key("load", {}, "W"), 1000, 10, values)
    result = api.seasonal_decomposition(episode, periods=12)
    assert result["input"]["episode_id"] == episode["episodeId"]
    assert result["input"]["unit"] == "W"
    import opentsx

    assert opentsx.power_transform is api.power_transform


def test_power_special_cases_match_known_analytic_formulas():
    api = importlib.import_module("opentsx.algorithms.decomposition")
    values = np.linspace(0.1, 5, 100)
    np.testing.assert_allclose(
        api.power_transform(values, method="boxcox", lmbda=0)["result"]["values"], np.log(values)
    )
    signed = np.linspace(-5, 5, 101)
    np.testing.assert_allclose(
        api.power_transform(signed, method="yeojohnson", lmbda=1)["result"]["values"], signed
    )
    fitted = api.power_transform(signed, method="yeojohnson")
    restored = api.inverse_power_transform(
        fitted["result"]["values"], method="yeojohnson", lmbda=fitted["parameters"]["lambda"]
    )
    np.testing.assert_allclose(restored["result"]["values"], signed, atol=1e-10)
