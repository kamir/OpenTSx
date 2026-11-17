import copy
import hashlib
import importlib
import json
from functools import lru_cache

import numpy as np
import pytest
from statsmodels.tsa.statespace.sarimax import SARIMAX

from opentsx.client.modelling import execute_modelling


def api():
    return importlib.import_module("opentsx.algorithms.fitted")


@lru_cache(maxsize=None)
def finite_case(kind="integrated"):
    t = np.arange(104)
    values = 3 + 0.04 * t + np.sin(t * 0.2) + np.random.default_rng(442).normal(0, 0.12, 104)
    context = np.column_stack([np.sin(t * 0.2), np.cos(t * 0.31)])
    settings = {
        "integrated": ("arima", [0, 1, 0], [0, 0, 0, 0], "n"),
        "seasonal": ("sarima", [0, 0, 0], [0, 1, 0, 4], "n"),
        "trend": ("arima", [0, 0, 0], [0, 0, 0, 0], "ct"),
        "sarimax": ("sarimax", [0, 0, 0], [0, 0, 0, 0], "ct"),
    }
    family, order, seasonal, trend = settings[kind]
    origin = 1_700_000_000_000_000
    interval = 900_000_000
    request = {
        "schema": "opentsx.modelling-request.v1",
        "target": {
            "series_id": "signal-1",
            "label": "Measured load",
            "unit": "MW",
            "time_kind": "absolute",
            "values": values[:80].tolist(),
            "timestamps_micros": [origin + i * interval for i in range(80)],
            "episode_ids": ["training-episode"],
            "quality": [0] * 80,
            "quality_known": [1] * 80,
        },
        "configuration": {
            "family": family,
            "order": order,
            "seasonal_order": seasonal,
            "trend": trend,
            "steps": 4,
            "alpha": 0.05,
            "maxiter": 200,
        },
        "backtest": {
            "initial": 72,
            "horizon": 4,
            "step": 4,
            "window": None,
            "seasonal_period": 4,
            "methods": ["naive"],
        },
        "context": None,
    }
    if family == "sarimax":
        request["context"] = {
            "names": ["known-cycle", "known-calendar"],
            "historical": context[:80].tolist(),
            "historical_available_at_micros": [[origin - 1] * 2 for _ in range(80)],
            "future": context[80:84].tolist(),
            "future_available_at_micros": [[origin - 1] * 2 for _ in range(4)],
        }
    return request, execute_modelling(request), values, context


def identity(artifact):
    content = copy.deepcopy(artifact)
    content.pop("artifact_id", None)
    content["initial_checkpoint"].pop("artifact_id", None)
    return hashlib.sha256(
        json.dumps(content, sort_keys=True, separators=(",", ":"), allow_nan=False).encode()
    ).hexdigest()


def reidentify(artifact):
    artifact["artifact_id"] = identity(artifact)
    artifact["initial_checkpoint"]["artifact_id"] = artifact["artifact_id"]
    return artifact


def make_artifact(kind="integrated"):
    request, result, _, _ = finite_case(kind)
    return api().create_artifact(copy.deepcopy(request), copy.deepcopy(result))


def test_artifact_is_json_data_and_initial_prediction_matches_the_finite_fit():
    request, result, _, _ = finite_case()
    before = copy.deepcopy((request, result))
    artifact = make_artifact()
    assert artifact["schema"] == "opentsx.fitted-arima.v1"
    assert artifact["artifact_id"] == identity(artifact)
    assert artifact["initial_checkpoint"]["artifact_id"] == artifact["artifact_id"]
    assert artifact["source"] == result["source"]
    assert artifact["training_values"] == request["target"]["values"]
    assert artifact["initial_checkpoint"]["total_nobs"] == 80
    assert (
        artifact["initial_checkpoint"]["next_timestamp_micros"] == result["source"]["t_end_micros"]
    )
    restored = json.loads(json.dumps(artifact, allow_nan=False))
    forecast = api().predict_artifact(restored)
    for key in ("mean", "lower", "upper", "timestamps_micros"):
        np.testing.assert_allclose(
            forecast[key], result["forecast"]["result"][key], rtol=0, atol=1e-10
        )
    assert forecast["interval_status"] == "conditional_gaussian"
    assert (request, result) == before


def test_create_predict_and_advance_never_optimize_again(monkeypatch):
    finite_case()

    def forbidden_fit(*args, **kwargs):
        raise AssertionError("a preserved fit must not optimize again")

    monkeypatch.setattr(SARIMAX, "fit", forbidden_fit)
    artifact = make_artifact()
    api().predict_artifact(artifact)
    api().advance_artifact(
        artifact, artifact["initial_checkpoint"], [4.0], artifact["source"]["t_end_micros"]
    )


@pytest.mark.parametrize("kind", ["integrated", "seasonal", "trend", "sarimax"])
def test_whole_chunk_and_json_restart_match_fixed_parameter_filtering(kind):
    request, result, values, context = finite_case(kind)
    artifact = make_artifact(kind)
    original = copy.deepcopy(artifact)
    is_context = kind == "sarimax"
    configuration = request["configuration"]
    parameters = result["forecast"]["result"]["parameters"]
    reference = SARIMAX(
        values[:80],
        exog=context[:80] if is_context else None,
        order=configuration["order"],
        seasonal_order=configuration["seasonal_order"],
        trend=configuration["trend"],
        enforce_stationarity=True,
        enforce_invertibility=True,
        missing="raise",
    )
    reference = reference.filter(
        [parameters[name] for name in reference.param_names], cov_type="none"
    )
    reference = reference.extend(
        values[80:88],
        exog=context[80:88] if is_context else None,
        trend_offset=81,
        tolerance=0.0,
    )
    expected = reference.get_forecast(4, exog=context[88:92] if is_context else None)
    origin = artifact["initial_checkpoint"]["next_timestamp_micros"]
    interval = artifact["source"]["interval_micros"]
    whole = api().advance_artifact(
        artifact,
        artifact["initial_checkpoint"],
        values[80:88].tolist(),
        origin,
        exog=context[80:88].tolist() if is_context else None,
        future_exog=context[88:92].tolist() if is_context else None,
    )
    first = api().advance_artifact(
        artifact,
        artifact["initial_checkpoint"],
        values[80:83].tolist(),
        origin,
        exog=context[80:83].tolist() if is_context else None,
        future_exog=context[83:87].tolist() if is_context else None,
    )
    checkpoint = json.loads(json.dumps(first["checkpoint"], allow_nan=False))
    checkpoint_before = copy.deepcopy(checkpoint)
    second = api().advance_artifact(
        artifact,
        checkpoint,
        values[83:88].tolist(),
        origin + 3 * interval,
        exog=context[83:88].tolist() if is_context else None,
        future_exog=context[88:92].tolist() if is_context else None,
    )
    sequential = artifact["initial_checkpoint"]
    for index in range(80, 88):
        single = api().advance_artifact(
            artifact,
            sequential,
            [float(values[index])],
            origin + (index - 80) * interval,
            exog=context[index : index + 1].tolist() if is_context else None,
            future_exog=context[index + 1 : index + 5].tolist() if is_context else None,
        )
        sequential = json.loads(json.dumps(single["checkpoint"]))
    np.testing.assert_allclose(
        whole["forecast"]["mean"], expected.predicted_mean, rtol=0, atol=1e-10
    )
    np.testing.assert_allclose(
        whole["forecast"]["lower"], np.asarray(expected.conf_int())[:, 0], rtol=0, atol=1e-10
    )
    for key in ("mean", "lower", "upper", "timestamps_micros"):
        np.testing.assert_allclose(
            second["forecast"][key], whole["forecast"][key], rtol=0, atol=1e-10
        )
        np.testing.assert_allclose(
            single["forecast"][key], whole["forecast"][key], rtol=0, atol=1e-10
        )
    observed = reference.get_prediction(start=0, end=7)
    np.testing.assert_allclose(
        whole["observed"]["predicted"], observed.predicted_mean, rtol=0, atol=1e-10
    )
    np.testing.assert_allclose(
        whole["observed"]["residual"], values[80:88] - observed.predicted_mean, rtol=0, atol=1e-10
    )
    assert first["observed"]["actual"] + second["observed"]["actual"] == whole["observed"]["actual"]
    assert second["checkpoint"]["total_nobs"] == 88
    assert set(second["checkpoint"]) == set(artifact["initial_checkpoint"])
    assert np.shape(second["checkpoint"]["predicted_state_cov"]) == np.shape(
        artifact["initial_checkpoint"]["predicted_state_cov"]
    )
    assert artifact == original
    assert checkpoint == checkpoint_before


@pytest.mark.parametrize("field", ["configuration", "params", "source"])
def test_tampering_without_a_matching_integrity_digest_is_rejected(field):
    artifact = make_artifact()
    if field == "configuration":
        artifact[field]["steps"] = 2
    elif field == "params":
        artifact[field][0] += 1
    else:
        artifact[field]["unit"] = "kW"
    with pytest.raises(ValueError, match="integrity"):
        api().validate_artifact(artifact)


@pytest.mark.parametrize(
    "corruption", ["runtime", "dimension", "covariance", "nonfinite", "parameters", "schema"]
)
def test_invalid_rehashed_artifacts_are_rejected(corruption):
    artifact = make_artifact()
    if corruption == "runtime":
        artifact["runtime"]["statsmodels"] = "0.0.0"
    elif corruption == "dimension":
        artifact["initial_checkpoint"]["predicted_state"].append(0.0)
    elif corruption == "covariance":
        artifact["initial_checkpoint"]["predicted_state_cov"][0][0] = -10.0
    elif corruption == "nonfinite":
        artifact["params"][0] = float("nan")
    elif corruption == "parameters":
        artifact["param_names"][0] = "unsupported"
    else:
        artifact["executable"] = "unsupported"
    if corruption != "nonfinite":
        reidentify(artifact)
    with pytest.raises(ValueError):
        api().validate_artifact(artifact)


@pytest.mark.parametrize("offset", [-1, 1])
def test_advance_rejects_overlaps_and_gaps(offset):
    artifact = make_artifact()
    with pytest.raises(ValueError, match="contiguous"):
        api().advance_artifact(
            artifact,
            artifact["initial_checkpoint"],
            [4.0],
            artifact["source"]["t_end_micros"] + offset,
        )


def test_advance_rejects_nonfinite_observations_and_foreign_checkpoints():
    artifact = make_artifact()
    origin = artifact["source"]["t_end_micros"]
    with pytest.raises(ValueError, match="finite"):
        api().advance_artifact(artifact, artifact["initial_checkpoint"], [float("inf")], origin)
    checkpoint = copy.deepcopy(artifact["initial_checkpoint"])
    checkpoint["artifact_id"] = "0" * 64
    with pytest.raises(ValueError, match="artifact"):
        api().predict_artifact(artifact, checkpoint)


def test_sarimax_requires_context_for_every_requested_coordinate():
    artifact = make_artifact("sarimax")
    with pytest.raises(ValueError, match="context"):
        api().predict_artifact(artifact)
    with pytest.raises(ValueError, match="context"):
        api().predict_artifact(artifact, future_exog=[[1.0]])
    with pytest.raises(ValueError, match="context"):
        api().advance_artifact(
            artifact, artifact["initial_checkpoint"], [4.0], artifact["source"]["t_end_micros"]
        )


@pytest.mark.parametrize("corruption", ["document", "source", "forecast"])
def test_malformed_finite_results_fail_with_explicit_boundary_errors(corruption):
    request, result, _, _ = finite_case()
    result = copy.deepcopy(result)
    if corruption == "document":
        result = None
    elif corruption == "source":
        result.pop("source")
    else:
        result["forecast"] = None
    with pytest.raises(ValueError):
        api().create_artifact(request, result)


def test_training_values_cannot_be_substituted_under_a_rehashed_artifact():
    artifact = make_artifact()
    artifact["training_values"][0] += 1
    reidentify(artifact)
    with pytest.raises(ValueError, match="training"):
        api().validate_artifact(artifact)


@pytest.mark.parametrize("corruption", ["state", "clock", "count", "foreign"])
def test_public_checkpoint_validation_rejects_corrupt_state_before_consuming(corruption):
    artifact = make_artifact()
    checkpoint = copy.deepcopy(artifact["initial_checkpoint"])
    assert api().validate_checkpoint(artifact, checkpoint) == checkpoint
    if corruption == "state":
        checkpoint["predicted_state"].append(0.0)
    elif corruption == "clock":
        checkpoint["next_timestamp_micros"] += 1
    elif corruption == "count":
        checkpoint["total_nobs"] = True
    else:
        checkpoint["artifact_id"] = "0" * 64
    with pytest.raises(ValueError):
        api().validate_checkpoint(artifact, checkpoint)


def test_one_step_sarimax_prediction_uses_only_the_one_supplied_context_row():
    _, result, _, context = finite_case("sarimax")
    artifact = make_artifact("sarimax")
    forecast = api().predict_artifact(artifact, steps=1, future_exog=context[80:81].tolist())
    for key in ("mean", "lower", "upper"):
        np.testing.assert_allclose(
            forecast[key], result["forecast"]["result"][key][:1], rtol=0, atol=1e-10
        )
