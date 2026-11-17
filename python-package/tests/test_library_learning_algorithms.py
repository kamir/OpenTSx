import json
from pathlib import Path, PurePosixPath

import numpy as np
import pytest

from opentsx import DFA


LIBRARY_MANIFEST = Path(__file__).resolve().parents[2] / "docs/learning/library-operators.v1.json"


def execute_starter(lesson_id):
    lessons = json.loads(LIBRARY_MANIFEST.read_text())["lessons"]
    matches = [lesson for lesson in lessons if lesson["id"] == lesson_id]
    assert len(matches) == 1
    starter = matches[0]["starter"]
    assert starter["language"] == "python"
    namespace = {}
    exec(compile(starter["code"], f"{LIBRARY_MANIFEST}#{lesson_id}", "exec"), namespace)
    return namespace


def test_learning_dfa_preserves_scales_and_rescales_fluctuations(monkeypatch):
    analyze = DFA.analyze
    checked_inputs = []

    def preserving_input(self, values, *args, **kwargs):
        original = values.copy()
        result = analyze(self, values, *args, **kwargs)
        np.testing.assert_array_equal(values, original)
        checked_inputs.append(original)
        return result

    monkeypatch.setattr(DFA, "analyze", preserving_input)
    starter = execute_starter("library.dfa")
    result = starter["result"]
    transformed = starter["changed"]

    assert len(checked_inputs) == 2
    np.testing.assert_array_equal(result["scales"], [8, 16, 32, 64])
    assert np.all(np.isfinite(result["fluctuations"]))
    assert np.all(result["fluctuations"] > 0)
    np.testing.assert_allclose(
        transformed["fluctuations"], 3 * result["fluctuations"], rtol=1e-12, atol=1e-12
    )
    assert transformed["alpha"] == pytest.approx(result["alpha"], abs=1e-12)


def test_learning_mfdfa_q2_fluctuations_match_dfa():
    starter = execute_starter("library.mfdfa")
    result = starter["result"]
    _, dfa_fluctuations = DFA(polynom_order=1).calculate(
        starter["values"], scales=starter["scales"]
    )

    np.testing.assert_array_equal(result["q_values"], [-2.0, 0.0, 2.0])
    np.testing.assert_array_equal(result["scales"], [8, 16, 32, 64])
    assert result["Fq"].shape == (3, 4)
    assert np.all(np.isfinite(result["Fq"]))
    assert starter["q2"] == 2
    np.testing.assert_allclose(starter["fluctuations_q2"], dfa_fluctuations, rtol=1e-12, atol=1e-12)
    assert result["tau_q"][1] == -1.0


def test_learning_event_sync_exposes_direction_and_strict_cap():
    starter = execute_starter("library.event-synchronization")
    result = starter["result"]
    capped = starter["capped"]

    np.testing.assert_array_equal(result["events_1"], [2, 8, 14, 20])
    np.testing.assert_array_equal(result["events_2"], [3, 9, 15, 21])
    assert result["overall_sync"] == 1.0
    assert result["sync_1_to_2"] == 1.0
    assert result["sync_2_to_1"] == 0.0
    assert result["leader"] == "Series 1"
    assert capped["overall_sync"] == 0.0


def test_learning_ris_reports_exact_intervals_and_empirical_survival():
    starter = execute_starter("library.ris")
    result = starter["result"]
    intervals = starter["intervals"]
    survival = starter["survival"]

    np.testing.assert_array_equal(result["event_indices"], [1, 3, 7, 13])
    np.testing.assert_array_equal(result["return_intervals"], [2, 4, 6])
    assert result["threshold"] == 5
    assert result["mean_ri"] == 4
    assert result["median_ri"] == 4
    assert result["risk_parameter"] == pytest.approx(0.408248290463863)
    assert result["gamma"] == result["tau_0"] == 0.0
    np.testing.assert_array_equal(intervals, [2, 4, 6])
    np.testing.assert_allclose(survival, [1, 2 / 3, 1 / 3], rtol=1e-12, atol=1e-12)


def test_learning_covers_every_exported_python_algorithm(tmp_path):
    import opentsx.algorithms as algorithms

    def check_coverage(path):
        lessons = json.loads(path.read_text())["lessons"]
        covered = {
            lesson["operator"].split(".", 1)[0]
            for lesson in lessons
            if any(
                PurePosixPath(source.split("#", 1)[0]).parent
                == PurePosixPath("python-package/opentsx/algorithms")
                and PurePosixPath(source.split("#", 1)[0]).suffix == ".py"
                for source in lesson["references"]["source"]
            )
        }
        assert covered == set(algorithms.__all__)

    manifest = LIBRARY_MANIFEST
    check_coverage(manifest)
    document = json.loads(manifest.read_text())
    exported = set(algorithms.__all__)
    assert exported
    missing_class = sorted(exported)[0]
    missing = dict(document)
    missing["lessons"] = [
        lesson
        for lesson in document["lessons"]
        if lesson["operator"].split(".", 1)[0] != missing_class
    ]
    fixture = tmp_path / "library-lessons.json"
    fixture.write_text(json.dumps(missing))
    with pytest.raises(AssertionError):
        check_coverage(fixture)
    unknown = json.loads(manifest.read_text())
    extra = next(
        lesson.copy()
        for lesson in unknown["lessons"]
        if lesson["operator"].split(".", 1)[0] in exported
    )
    extra["id"] = "library.unexported"
    extra["operator"] = "UnexportedLearningAlgorithm.analyze"
    unknown["lessons"].append(extra)
    fixture.write_text(json.dumps(unknown))
    with pytest.raises(AssertionError):
        check_coverage(fixture)


def test_learning_forecast_arima_preserves_clock_and_random_walk_uncertainty():
    from opentsx import forecast_arima

    starter = execute_starter("library.forecast-arima")
    result = starter["result"]
    forecast = result["result"]
    np.testing.assert_allclose(forecast["mean"], starter["values"][-1], atol=1e-8)
    widths = np.asarray(forecast["upper"]) - np.asarray(forecast["lower"])
    np.testing.assert_allclose(widths / widths[0], np.sqrt(np.arange(1, 7)), rtol=1e-6)
    assert result["method"] == "arima"
    assert result["parameters"]["order"] == [0, 1, 0]
    assert result["input"]["unit"] == "MW"
    assert result["input"]["episode_id"] == starter["episode"]["episodeId"]
    assert forecast["timestamps_micros"] == [2000 + index * 10 for index in range(6)]
    np.testing.assert_array_equal(starter["episode"]["values"], starter["values"])
    with pytest.raises(ValueError, match="steps"):
        forecast_arima(starter["values"], steps=0)


def test_learning_forecast_baselines_match_closed_form_and_degenerate_bounds():
    from opentsx import forecast_baseline

    starter = execute_starter("library.forecast-baseline")
    expected = {
        "mean": [5.5] * 4,
        "naive": [11.0] * 4,
        "drift": [12.0, 13.0, 14.0, 15.0],
        "seasonal_naive": [8.0, 9.0, 10.0, 11.0],
    }
    for method, values in expected.items():
        record = starter["result"][method]
        np.testing.assert_allclose(record["result"]["mean"], values, atol=1e-12)
        assert record["parameters"]["method"] == method
        assert np.all(np.asarray(record["result"]["lower"]) <= values)
        assert np.all(np.asarray(record["result"]["upper"]) >= values)
    constant = starter["constant"]["result"]
    assert constant["mean"] == constant["lower"] == constant["upper"] == [7.0] * 4
    assert constant["interval_status"] == ["degenerate"] * 4
    np.testing.assert_array_equal(starter["values"], np.arange(12, dtype=float))
    with pytest.raises(ValueError, match="seasonal naive"):
        forecast_baseline(starter["values"], steps=2, method="seasonal_naive", seasonal_period=12)


def test_learning_rolling_backtest_keeps_origins_training_scales_and_future_isolation():
    from opentsx import rolling_backtest

    starter = execute_starter("library.rolling-backtest")
    result = starter["result"]["result"]
    changed = starter["changed_result"]["result"]
    assert [fold["training_stop"] for fold in result["folds"]] == [8, 12, 16]
    assert all(fold["training_stop"] == fold["test_start"] for fold in result["folds"])
    assert result["metrics"]["naive"]["mae"] == 2
    assert result["metrics"]["naive"]["mase"] == 2
    assert result["metrics"]["naive"]["rmse"] == pytest.approx(np.sqrt(14 / 3))
    assert result["metrics"]["drift"]["mae"] == 0
    assert len(result["cases"]["naive"]) == 9
    assert result["folds"][0] == changed["folds"][0]
    assert result["cases"]["naive"][:3] == changed["cases"]["naive"][:3]
    assert changed["cases"]["naive"][3]["predicted"] != result["cases"]["naive"][3]["predicted"]
    with pytest.raises(ValueError, match="window"):
        rolling_backtest(starter["values"], initial=8, window=9)


def test_learning_seasonal_decomposition_reconstructs_inputs_without_claiming_causality():
    from opentsx import seasonal_decomposition

    starter = execute_starter("library.seasonal-decomposition")
    record = starter["result"]
    result = record["result"]
    seasonal = sum(np.asarray(part) for part in result["seasonal"].values())
    np.testing.assert_allclose(
        np.asarray(result["trend"]) + seasonal + np.asarray(result["residual"]),
        starter["values"],
        rtol=1e-12,
        atol=1e-12,
    )
    np.testing.assert_allclose(
        np.asarray(result["seasonally_adjusted"]) + seasonal,
        starter["values"],
        rtol=1e-12,
        atol=1e-12,
    )
    assert record["method"] == "mstl"
    assert record["parameters"]["periods"] == [8, 16]
    assert record["parameters"]["causal"] is False
    assert record["parameters"]["iterations"] == 2
    assert starter["single"]["method"] == "stl"
    assert all(
        len(part) == 128 and np.all(np.isfinite(part)) for part in result["seasonal"].values()
    )
    with pytest.raises(ValueError, match="half"):
        seasonal_decomposition(starter["values"], periods=64)


def test_learning_power_transform_checks_log_identity_signed_values_and_domain():
    from opentsx import power_transform

    starter = execute_starter("library.power-transform")
    result = starter["result"]
    np.testing.assert_allclose(result["result"]["values"], np.log(starter["values"]), atol=1e-12)
    assert result["parameters"] == {"lambda": 0.0, "estimated": False}
    assert result["result"]["value_space"] == "power_transformed"
    np.testing.assert_allclose(starter["signed"]["result"]["values"], starter["mixed"], atol=1e-12)
    np.testing.assert_array_equal(starter["values"], [1, 2, 4, 8])
    with pytest.raises(ValueError, match="positive"):
        power_transform([0, 1], method="boxcox", lmbda=0)


def test_learning_inverse_power_transform_restores_both_signed_log_limits_and_domain():
    from opentsx import inverse_power_transform

    starter = execute_starter("library.inverse-power-transform")
    for record in starter["result"].values():
        np.testing.assert_allclose(
            record["result"]["values"], starter["mixed"], rtol=1e-12, atol=1e-12
        )
        assert record["result"]["value_space"] == "original"
    np.testing.assert_allclose(starter["positive"]["result"]["values"], [1, 2, 4, 8], atol=1e-12)
    with pytest.raises(ValueError, match="domain"):
        inverse_power_transform([-1], method="boxcox", lmbda=1)
