"""JSON fitted ARIMA artifacts and fixed-parameter state updates."""

import copy
import hashlib
import json
import re

import numpy as np
import scipy
import statsmodels
from statsmodels.tsa.statespace.sarimax import SARIMAX

from opentsx import __version__

SCHEMA = "opentsx.fitted-arima.v1"
MAX_INTEGER = 2**53 - 1
ARTIFACT_FIELDS = {
    "schema",
    "artifact_id",
    "runtime",
    "configuration",
    "source",
    "context_names",
    "param_names",
    "params",
    "training_values",
    "initial_checkpoint",
}
CHECKPOINT_FIELDS = {
    "artifact_id",
    "predicted_state",
    "predicted_state_cov",
    "total_nobs",
    "next_timestamp_micros",
}


def _canonical(value):
    try:
        return json.dumps(value, sort_keys=True, separators=(",", ":"), allow_nan=False).encode()
    except (TypeError, ValueError, OverflowError) as error:
        raise ValueError("artifact must contain finite JSON data only") from error


def _runtime():
    return {
        "opentsx": __version__,
        "statsmodels": statsmodels.__version__,
        "numpy": np.__version__,
        "scipy": scipy.__version__,
    }


def _keys(value, expected, name):
    if not isinstance(value, dict) or set(value) != expected:
        raise ValueError(f"{name} has missing or unsupported fields")


def _integer(value, low, high, name):
    if type(value) is not int or not low <= value <= high:
        raise ValueError(f"{name} must be an integer from {low} to {high}")
    return value


def _text(value, name):
    if not isinstance(value, str) or not value or len(value) > 512:
        raise ValueError(f"{name} must be a nonempty string of at most 512 characters")


def _array(value, shape, name):
    if not isinstance(value, list):
        raise ValueError(f"{name} must be a finite numerical array")
    pending = [value]
    while pending:
        item = pending.pop()
        if isinstance(item, list):
            pending.extend(item)
        elif isinstance(item, bool) or not isinstance(item, (int, float)):
            raise ValueError(f"{name} must contain finite real numbers")
    try:
        values = np.asarray(value, dtype=np.float64)
    except (ValueError, TypeError, OverflowError) as error:
        raise ValueError(f"{name} must contain finite real numbers") from error
    if values.shape != shape or not np.all(np.isfinite(values)):
        raise ValueError(f"{name} has an invalid shape or nonfinite values")
    return values


def _identity(artifact):
    content = copy.deepcopy(artifact)
    content.pop("artifact_id", None)
    content["initial_checkpoint"].pop("artifact_id", None)
    return hashlib.sha256(_canonical(content)).hexdigest()


def _configuration(configuration, context_names):
    _keys(
        configuration,
        {"family", "order", "seasonal_order", "trend", "steps", "alpha", "maxiter"},
        "configuration",
    )
    family = configuration["family"]
    if family not in ("arima", "sarima", "sarimax"):
        raise ValueError("unsupported fitted family")
    for key, limits in (("order", (3, 2, 3)), ("seasonal_order", (2, 1, 2, 96))):
        order = configuration[key]
        if not isinstance(order, list) or len(order) != len(limits):
            raise ValueError(f"{key} has an invalid shape")
        for value, limit in zip(order, limits):
            _integer(value, 0, limit, key)
    p, d, q = configuration["order"]
    sp, sd, sq, period = configuration["seasonal_order"]
    if period == 1 or (any((sp, sd, sq)) and period < 2):
        raise ValueError("seasonal terms require a period from 2 to 96")
    if family == "arima" and any(configuration["seasonal_order"]):
        raise ValueError("ARIMA cannot contain seasonal terms")
    if family == "sarima" and not any((sp, sd, sq)):
        raise ValueError("SARIMA requires a seasonal component")
    if not isinstance(context_names, list) or len(context_names) > 8:
        raise ValueError("context_names must contain at most eight names")
    for name in context_names:
        _text(name, "context name")
    if len(set(context_names)) != len(context_names) or (family == "sarimax") != bool(
        context_names
    ):
        raise ValueError("context names must be unique and present exactly for SARIMAX")
    if configuration["trend"] not in ("n", "c", "t", "ct"):
        raise ValueError("unsupported fitted trend")
    _integer(configuration["steps"], 1, 96, "steps")
    _integer(configuration["maxiter"], 1, 200, "maxiter")
    alpha = _array([configuration["alpha"]], (1,), "alpha")[0]
    if not 0 < alpha < 1:
        raise ValueError("alpha must be between zero and one")
    states = max(p + sp * period, q + sq * period + 1) + d + sd * period
    if states > 128:
        raise ValueError("fitted state exceeds the 128-state limit")
    return states


def _source(source, context_names):
    _keys(
        source,
        {
            "series_id",
            "label",
            "unit",
            "episode_ids",
            "time_kind",
            "nobs",
            "interval_micros",
            "t_start_micros",
            "t_end_micros",
            "values_sha256",
            "context_names",
            "reference_kind",
        },
        "source",
    )
    _text(source["series_id"], "series_id")
    _text(source["label"], "label")
    if source["unit"] is not None:
        _text(source["unit"], "unit")
    if source["time_kind"] not in ("absolute", "relative", "index"):
        raise ValueError("unsupported source coordinate kind")
    _integer(source["nobs"], 9, 2048, "source nobs")
    _integer(source["interval_micros"], 1, MAX_INTEGER, "source interval")
    for key in ("t_start_micros", "t_end_micros"):
        _integer(source[key], -MAX_INTEGER, MAX_INTEGER, key)
    if (
        source["t_end_micros"]
        != source["t_start_micros"] + source["nobs"] * source["interval_micros"]
    ):
        raise ValueError("source bounds do not match its regular clock")
    ids = source["episode_ids"]
    if not isinstance(ids, list) or len(ids) > 1000:
        raise ValueError("source episode_ids must contain at most 1000 references")
    for value in ids:
        _text(value, "episode id")
    if len(set(ids)) != len(ids):
        raise ValueError("source episode ids must be unique")
    if not isinstance(source["values_sha256"], str) or not re.fullmatch(
        "[0-9a-f]{64}", source["values_sha256"]
    ):
        raise ValueError("source value digest is invalid")
    if source["context_names"] != context_names or source["reference_kind"] != "browser-snapshot":
        raise ValueError("source context or reference kind is incompatible")


def _process(
    artifact, values, exog=None, trend_offset=1, missing="raise", validate_specification=True
):
    configuration = artifact["configuration"]
    return SARIMAX(
        values,
        exog=exog,
        order=configuration["order"],
        seasonal_order=configuration["seasonal_order"],
        trend=configuration["trend"],
        trend_offset=trend_offset,
        enforce_stationarity=True,
        enforce_invertibility=True,
        simple_differencing=False,
        use_exact_diffuse=False,
        concentrate_scale=False,
        measurement_error=False,
        time_varying_regression=False,
        mle_regression=True,
        hamilton_representation=False,
        missing=missing,
        validate_specification=validate_specification,
    )


def _checkpoint(artifact, checkpoint, states, initial=False):
    _keys(checkpoint, CHECKPOINT_FIELDS, "checkpoint")
    if checkpoint["artifact_id"] != artifact["artifact_id"]:
        raise ValueError("checkpoint belongs to another artifact")
    nobs = _integer(checkpoint["total_nobs"], artifact["source"]["nobs"], MAX_INTEGER, "total_nobs")
    if initial and nobs != artifact["source"]["nobs"]:
        raise ValueError("initial checkpoint must end at the training boundary")
    timestamp = _integer(
        checkpoint["next_timestamp_micros"], -MAX_INTEGER, MAX_INTEGER, "next timestamp"
    )
    if (
        timestamp
        != artifact["source"]["t_start_micros"] + nobs * artifact["source"]["interval_micros"]
    ):
        raise ValueError("checkpoint does not follow the source regular clock")
    state = _array(checkpoint["predicted_state"], (states,), "predicted_state")
    covariance = _array(checkpoint["predicted_state_cov"], (states, states), "predicted_state_cov")
    tolerance = 1e-9 * max(1.0, float(np.max(np.abs(covariance))))
    if not np.allclose(covariance, covariance.T, rtol=0, atol=tolerance):
        raise ValueError("state covariance must be symmetric")
    try:
        minimum = float(np.min(np.linalg.eigvalsh(covariance)))
    except np.linalg.LinAlgError as error:
        raise ValueError("state covariance decomposition failed") from error
    if minimum < -tolerance:
        raise ValueError("state covariance must be positive semidefinite")
    return state, covariance


def validate_artifact(artifact):
    _keys(artifact, ARTIFACT_FIELDS, "artifact")
    if len(_canonical(artifact)) > 1_048_576:
        raise ValueError("artifact exceeds the one MiB limit")
    if artifact["schema"] != SCHEMA:
        raise ValueError("unsupported fitted artifact schema")
    _keys(artifact["initial_checkpoint"], CHECKPOINT_FIELDS, "initial checkpoint")
    if artifact["artifact_id"] != _identity(artifact):
        raise ValueError("artifact integrity digest does not match its content")
    if artifact["runtime"] != _runtime():
        raise ValueError("artifact runtime is incompatible with this numerical runtime")
    states = _configuration(artifact["configuration"], artifact["context_names"])
    _source(artifact["source"], artifact["context_names"])
    training = _array(artifact["training_values"], (artifact["source"]["nobs"],), "training_values")
    if (
        hashlib.sha256(training.astype("<f8").tobytes()).hexdigest()
        != artifact["source"]["values_sha256"]
    ):
        raise ValueError("training values do not match the source integrity digest")
    _checkpoint(artifact, artifact["initial_checkpoint"], states, initial=True)
    columns = len(artifact["context_names"])
    dummy = (
        None if not columns else np.column_stack([np.sin(np.arange(9) + i) for i in range(columns)])
    )
    process = _process(artifact, np.arange(9, dtype=float), dummy)
    if process.k_states != states or artifact["param_names"] != process.param_names:
        raise ValueError("artifact parameter names or state dimensions are incompatible")
    parameters = _array(artifact["params"], (len(process.param_names),), "params")
    if parameters[-1] <= 0:
        raise ValueError("fitted innovation variance must be positive")
    try:
        with np.errstate(over="raise", invalid="raise", divide="raise"):
            unconstrained = process.untransform_params(parameters)
        if not np.all(np.isfinite(unconstrained)):
            raise ValueError("fitted parameters violate stationarity or invertibility")
    except (FloatingPointError, np.linalg.LinAlgError) as error:
        raise ValueError("fitted parameters violate numerical constraints") from error
    return artifact


def validate_checkpoint(artifact, checkpoint):
    validate_artifact(artifact)
    _checkpoint(artifact, checkpoint, len(artifact["initial_checkpoint"]["predicted_state"]))
    return checkpoint


def create_artifact(request, result):
    from opentsx.client.modelling import validate_modelling

    request = json.loads(_canonical(request))
    result = json.loads(_canonical(result))
    validate_modelling(request)
    if not isinstance(result, dict):
        raise ValueError("finite result must be a JSON object")
    runtime = _runtime()
    if (
        result.get("schema") != "opentsx.modelling-result.v1"
        or result.get("runtime") != runtime
        or result.get("configuration") != request["configuration"]
    ):
        raise ValueError("finite result is incompatible with the request or runtime")
    if (
        result.get("run_id")
        != hashlib.sha256(_canonical({"request": request, "runtime": runtime})).hexdigest()
    ):
        raise ValueError("finite result identity does not match the request")
    target = request["target"]
    source = result.get("source")
    context = request["context"]
    names = [] if context is None else context["names"]
    expected = {
        key: target[key] for key in ("series_id", "label", "unit", "episode_ids", "time_kind")
    }
    expected.update(
        nobs=len(target["values"]),
        interval_micros=target["timestamps_micros"][1] - target["timestamps_micros"][0],
        t_start_micros=target["timestamps_micros"][0],
        values_sha256=hashlib.sha256(
            np.asarray(target["values"], dtype="<f8").tobytes()
        ).hexdigest(),
        context_names=names,
        reference_kind="browser-snapshot",
    )
    expected["t_end_micros"] = target["timestamps_micros"][-1] + expected["interval_micros"]
    if source != expected:
        raise ValueError("finite result source does not match the supplied training snapshot")
    artifact = {
        "schema": SCHEMA,
        "artifact_id": "",
        "runtime": runtime,
        "configuration": request["configuration"],
        "source": source,
        "context_names": names,
        "training_values": target["values"],
    }
    process = _process(
        artifact, target["values"], None if context is None else context["historical"]
    )
    forecast = result.get("forecast")
    if not isinstance(forecast, dict) or not isinstance(forecast.get("result"), dict):
        raise ValueError("finite result forecast is missing or invalid")
    parameters = forecast["result"].get("parameters")
    if not isinstance(parameters, dict) or set(parameters) != set(process.param_names):
        raise ValueError("finite result parameter names are incompatible")
    artifact["param_names"] = process.param_names
    artifact["params"] = _array(
        [parameters[name] for name in process.param_names], (len(process.param_names),), "params"
    ).tolist()
    filtered = process.filter(artifact["params"], cov_type="none")
    artifact["initial_checkpoint"] = {
        "artifact_id": "",
        "predicted_state": filtered.predicted_state[:, -1].tolist(),
        "predicted_state_cov": filtered.predicted_state_cov[:, :, -1].tolist(),
        "total_nobs": source["nobs"],
        "next_timestamp_micros": source["t_end_micros"],
    }
    artifact["artifact_id"] = _identity(artifact)
    artifact["initial_checkpoint"]["artifact_id"] = artifact["artifact_id"]
    validate_artifact(artifact)
    return artifact


def _context(artifact, values, rows):
    columns = len(artifact["context_names"])
    if not columns:
        if values is not None:
            raise ValueError("this artifact does not accept context")
        return None
    if values is None:
        raise ValueError("SARIMAX requires context for every requested coordinate")
    return _array(values, (rows, columns), "context")


def _forecast(prediction, artifact, timestamp, steps):
    bounds = np.asarray(prediction.conf_int(alpha=artifact["configuration"]["alpha"]))
    mean = np.asarray(prediction.predicted_mean)
    if (
        mean.shape != (steps,)
        or bounds.shape != (steps, 2)
        or not np.all(np.isfinite(mean))
        or not np.all(np.isfinite(bounds))
    ):
        raise ValueError("fixed-parameter forecast contains invalid numerical output")
    if np.any(bounds[:, 0] > mean) or np.any(bounds[:, 1] < mean):
        raise ValueError("fixed-parameter prediction interval does not contain its mean")
    interval = artifact["source"]["interval_micros"]
    timestamps = [timestamp + i * interval for i in range(steps)]
    for value in timestamps:
        _integer(value, -MAX_INTEGER, MAX_INTEGER, "forecast timestamp")
    return {
        "mean": mean.tolist(),
        "lower": bounds[:, 0].tolist(),
        "upper": bounds[:, 1].tolist(),
        "timestamps_micros": timestamps,
        "interval_status": "conditional_gaussian",
    }


def predict_artifact(artifact, checkpoint=None, steps=None, future_exog=None):
    validate_artifact(artifact)
    checkpoint = artifact["initial_checkpoint"] if checkpoint is None else checkpoint
    state, covariance = _checkpoint(
        artifact, checkpoint, len(artifact["initial_checkpoint"]["predicted_state"])
    )
    steps = artifact["configuration"]["steps"] if steps is None else _integer(steps, 1, 96, "steps")
    context = _context(artifact, future_exog, steps)
    process = _process(
        artifact,
        [np.nan],
        None if context is None else context[:1],
        trend_offset=checkpoint["total_nobs"] + 1,
        missing="none",
        validate_specification=False,
    )
    process.initialize_known(state, covariance)
    process.ssm.tolerance = 0.0
    filtered = process.filter(artifact["params"], cov_type="none")
    prediction = filtered.get_prediction(
        start=0, end=steps - 1, exog=None if context is None or steps == 1 else context[1:]
    )
    return _forecast(prediction, artifact, checkpoint["next_timestamp_micros"], steps)


def advance_artifact(artifact, checkpoint, values, t_start_micros, exog=None, future_exog=None):
    validate_artifact(artifact)
    states = len(artifact["initial_checkpoint"]["predicted_state"])
    state, covariance = _checkpoint(artifact, checkpoint, states)
    _integer(t_start_micros, -MAX_INTEGER, MAX_INTEGER, "observation start")
    if t_start_micros != checkpoint["next_timestamp_micros"]:
        raise ValueError("new observations must be contiguous with the checkpoint")
    if not isinstance(values, list) or not 1 <= len(values) <= 2048:
        raise ValueError("advance requires 1 to 2048 observations")
    observed = _array(values, (len(values),), "observations")
    context = _context(artifact, exog, len(values))
    steps = artifact["configuration"]["steps"]
    future = _context(artifact, future_exog, steps)
    process = _process(
        artifact,
        observed,
        context,
        trend_offset=checkpoint["total_nobs"] + 1,
        validate_specification=False,
    )
    process.initialize_known(state, covariance)
    process.ssm.tolerance = 0.0
    filtered = process.filter(artifact["params"], cov_type="none")
    interval = artifact["source"]["interval_micros"]
    updated = {
        "artifact_id": artifact["artifact_id"],
        "predicted_state": filtered.predicted_state[:, -1].tolist(),
        "predicted_state_cov": filtered.predicted_state_cov[:, :, -1].tolist(),
        "total_nobs": checkpoint["total_nobs"] + len(values),
        "next_timestamp_micros": t_start_micros + len(values) * interval,
    }
    _checkpoint(artifact, updated, states)
    prediction = _forecast(
        filtered.get_prediction(start=0, end=len(values) - 1), artifact, t_start_micros, len(values)
    )
    prediction["actual"] = observed.tolist()
    prediction["predicted"] = prediction.pop("mean")
    prediction["residual"] = (observed - np.asarray(prediction["predicted"])).tolist()
    forecast = _forecast(
        filtered.get_forecast(steps=steps, exog=future),
        artifact,
        updated["next_timestamp_micros"],
        steps,
    )
    return {"checkpoint": updated, "forecast": forecast, "observed": prediction}
