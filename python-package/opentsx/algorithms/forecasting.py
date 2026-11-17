"""Finite ARIMA, seasonal ARIMA and exogenous forecasts."""

import hashlib
import warnings

import numpy as np
from statsmodels.tsa.statespace.sarimax import SARIMAX

from opentsx.algorithms._analysis import integer, probability, record, regular_values


def _order(value, size, name):
    if not isinstance(value, (tuple, list)) or len(value) != size:
        raise ValueError(f"{name} must contain {size} nonnegative integers")
    return tuple(integer(v, name, minimum=0) for v in value)


def _regressors(data, rows, columns=None):
    values = np.asarray(data)
    if values.ndim != 2 or values.shape[0] != rows or values.shape[1] < 1:
        raise ValueError("exogenous values must be a matrix with the required row count")
    if columns is not None and values.shape[1] != columns:
        raise ValueError("future exogenous columns must match training columns")
    if not np.issubdtype(values.dtype, np.number) or np.iscomplexobj(values):
        raise ValueError("exogenous values must be real numbers")
    values = values.astype(np.float64)
    if not np.all(np.isfinite(values)):
        raise ValueError("exogenous values must be finite")
    return values


def _forecast_timestamps(source, steps):
    if "interval_micros" not in source:
        return None
    start = source["t_start_micros"] + source["nobs"] * source["interval_micros"]
    last = start + (steps - 1) * source["interval_micros"]
    if start < -(2**63) or last >= 2**63:
        raise ValueError("forecast timestamps exceed signed 64-bit microseconds")
    return [start + i * source["interval_micros"] for i in range(steps)]


def forecast_arima(
    series,
    *,
    steps,
    order=(1, 0, 0),
    seasonal_order=(0, 0, 0, 0),
    exog=None,
    future_exog=None,
    trend="n",
    alpha=0.05,
    maxiter=200,
):
    values, source = regular_values(series)
    steps = integer(steps, "steps")
    maxiter = integer(maxiter, "maxiter")
    order = _order(order, 3, "order")
    seasonal_order = _order(seasonal_order, 4, "seasonal_order")
    alpha = probability(alpha)
    if trend not in ("n", "c", "t", "ct"):
        raise ValueError("trend must be n, c, t or ct")
    p, d, q = order
    sp, sd, sq, period = seasonal_order
    if period == 1 or (any((sp, sd, sq)) and period < 2):
        raise ValueError("seasonal components require a period of at least two")
    training = future = None
    columns = 0
    if exog is None:
        if future_exog is not None:
            raise ValueError("future_exog requires training exog")
    else:
        if future_exog is None:
            raise ValueError("exogenous forecasting requires future_exog")
        training = _regressors(exog, len(values))
        columns = training.shape[1]
        future = _regressors(future_exog, steps, columns)
        if np.linalg.matrix_rank(training) < columns:
            raise ValueError("training exogenous columns must be linearly independent")
        source["training_exog_sha256"] = hashlib.sha256(
            training.astype("<f8").tobytes()
        ).hexdigest()
        source["future_exog_sha256"] = hashlib.sha256(future.astype("<f8").tobytes()).hexdigest()
    required = d + sd * period + max(p, sp * period) + max(q, sq * period) + columns + 3
    if len(values) <= required:
        raise ValueError(
            "training samples are insufficient for the requested orders and regressors"
        )
    timestamps = _forecast_timestamps(source, steps)
    with warnings.catch_warnings(record=True) as caught:
        warnings.simplefilter("always")
        process = SARIMAX(
            values,
            exog=training,
            order=order,
            seasonal_order=seasonal_order,
            trend=trend,
            enforce_stationarity=True,
            enforce_invertibility=True,
            missing="raise",
        )
        fitted = process.fit(
            disp=False,
            maxiter=maxiter,
            method="lbfgs",
            optim_score="harvey",
            optim_complex_step=True,
        )
        if not fitted.mle_retvals["converged"]:
            raise RuntimeError("ARIMA optimization did not converge")
        forecast = fitted.get_forecast(steps=steps, exog=future)
        intervals = np.asarray(forecast.conf_int(alpha=alpha))
    result = {
        "mean": np.asarray(forecast.predicted_mean).tolist(),
        "lower": intervals[:, 0].tolist(),
        "upper": intervals[:, 1].tolist(),
        "parameters": {
            name: float(value) for name, value in zip(fitted.param_names, fitted.params)
        },
        "aic": float(fitted.aic),
        "bic": float(fitted.bic),
        "log_likelihood": float(fitted.llf),
    }
    if timestamps is not None:
        result["timestamps_micros"] = timestamps
    method = "sarimax" if training is not None else ("sarima" if any((sp, sd, sq)) else "arima")
    return record(
        method,
        {
            "order": list(order),
            "seasonal_order": list(seasonal_order),
            "steps": steps,
            "trend": trend,
            "alpha": alpha,
            "maxiter": maxiter,
            "enforce_stationarity": True,
            "enforce_invertibility": True,
        },
        source,
        result,
        [str(w.message) for w in caught],
    )
