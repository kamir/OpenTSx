"""Rolling-origin forecasting with training-only scales and transformations."""

import hashlib

import numpy as np
import scipy
from scipy import stats
import statsmodels

from opentsx import __version__
from opentsx.algorithms._analysis import integer, probability, record, regular_values
from opentsx.algorithms.decomposition import inverse_power_transform, power_transform
from opentsx.algorithms.forecasting import _forecast_timestamps, _regressors, forecast_arima


def _baseline(values, horizon, method, period, alpha):
    count = len(values)
    steps = np.arange(1, horizon + 1)
    if method == "mean":
        predicted = np.full(horizon, np.mean(values))
        variance = np.full(horizon, np.var(values, ddof=1) * (1 + 1 / count))
    elif method == "drift":
        slope = (values[-1] - values[0]) / (count - 1)
        predicted = values[-1] + slope * steps
        variance = np.var(np.diff(values), ddof=1) * steps * (1 + steps / (count - 1))
    else:
        lag = period if method == "seasonal_naive" else 1
        predicted = values[-lag + np.arange(horizon) % lag]
        sigma2 = np.mean((values[lag:] - values[:-lag]) ** 2)
        variance = sigma2 * ((steps - 1) // lag + 1)
    width = stats.norm.ppf(1 - alpha / 2) * np.sqrt(variance)
    return predicted, predicted - width, predicted + width


def _metrics(cases, alpha):
    errors = np.array([c["actual"] - c["predicted"] for c in cases])
    actual = np.array([c["actual"] for c in cases])
    lower = np.array([c["lower"] for c in cases])
    upper = np.array([c["upper"] for c in cases])
    undefined = sum(
        c["scaled_absolute_error"] is None or c["scaled_squared_error"] is None for c in cases
    )
    undefined_absolute = any(c["scaled_absolute_error"] is None for c in cases)
    undefined_squared = any(c["scaled_squared_error"] is None for c in cases)
    score = (
        upper
        - lower
        + 2 / alpha * np.maximum(lower - actual, 0)
        + 2 / alpha * np.maximum(actual - upper, 0)
    )
    return {
        "cases": len(cases),
        "mae": float(np.mean(np.abs(errors))),
        "rmse": float(np.sqrt(np.mean(errors**2))),
        "mase": (
            None
            if undefined_absolute
            else float(np.mean([c["scaled_absolute_error"] for c in cases]))
        ),
        "rmsse": (
            None
            if undefined_squared
            else float(np.sqrt(np.mean([c["scaled_squared_error"] for c in cases])))
        ),
        "undefined_scaled_cases": undefined,
        "scaled_error_status": "undefined_training_scale" if undefined else "defined",
        "coverage": float(np.mean((lower <= actual) & (actual <= upper))),
        "mean_interval_width": float(np.mean(upper - lower)),
        "mean_interval_score": float(np.mean(score)),
    }


def forecast_baseline(series, *, steps, method="naive", seasonal_period=1, alpha=0.05):
    values, source = regular_values(series, allow_constant=True)
    steps = integer(steps, "steps")
    seasonal_period = integer(seasonal_period, "seasonal_period")
    alpha = probability(alpha)
    if method not in ("mean", "naive", "drift", "seasonal_naive"):
        raise ValueError("method must be mean, naive, drift or seasonal_naive")
    if method == "seasonal_naive" and not 2 <= seasonal_period < len(values):
        raise ValueError(
            "seasonal naive requires a period of at least two and below the sample count"
        )
    timestamps = _forecast_timestamps(source, steps)
    with np.errstate(over="raise", invalid="raise", divide="raise"):
        predicted, lower, upper = _baseline(values, steps, method, seasonal_period, alpha)
    if any(v.shape != (steps,) or not np.all(np.isfinite(v)) for v in [predicted, lower, upper]):
        raise RuntimeError("baseline output must have a finite value for every horizon")
    result = {
        "mean": predicted.tolist(),
        "lower": lower.tolist(),
        "upper": upper.tolist(),
        "interval_status": [
            "degenerate" if lo == hi else "conditional_gaussian" for lo, hi in zip(lower, upper)
        ],
    }
    if timestamps is not None:
        result["timestamps_micros"] = timestamps
    return record(
        "baseline_forecast",
        {"method": method, "steps": steps, "seasonal_period": seasonal_period, "alpha": alpha},
        source,
        result,
        [],
        backend={"name": "opentsx", "version": __version__},
    )


def rolling_backtest(
    series,
    *,
    initial,
    horizon=1,
    step=1,
    window=None,
    methods=("naive", "drift"),
    seasonal_period=1,
    alpha=0.05,
    arima_options=None,
    exog=None,
    transform=None,
    transform_lambda=None,
):
    values, source = regular_values(series)
    initial = integer(initial, "initial", minimum=8)
    horizon = integer(horizon, "horizon")
    step = integer(step, "step")
    seasonal_period = integer(seasonal_period, "seasonal_period")
    alpha = probability(alpha)
    if initial + horizon > len(values):
        raise ValueError("initial and horizon leave no complete evaluation fold")
    if window is not None:
        window = integer(window, "window", minimum=8)
        if window > initial:
            raise ValueError("window cannot exceed initial training length")
    if seasonal_period >= (window if window is not None else initial):
        raise ValueError("seasonal period must be smaller than every training window")
    allowed = ("mean", "naive", "seasonal_naive", "drift", "arima")
    if (
        not isinstance(methods, (list, tuple))
        or not methods
        or any(m not in allowed for m in methods)
    ):
        raise ValueError("methods must be a nonempty sequence of supported forecast names")
    if len(set(methods)) != len(methods):
        raise ValueError("forecast methods must be unique")
    if "seasonal_naive" in methods and seasonal_period < 2:
        raise ValueError("seasonal naive requires seasonal_period of at least two")
    if transform not in (None, "boxcox", "yeojohnson") or (
        transform is None and transform_lambda is not None
    ):
        raise ValueError("transform configuration requires boxcox or yeojohnson")
    if arima_options is None:
        arima_options = {}
    if not isinstance(arima_options, dict) or set(arima_options) - {
        "order",
        "seasonal_order",
        "trend",
        "maxiter",
    }:
        raise ValueError("arima_options accepts only order, seasonal_order, trend and maxiter")
    if arima_options and "arima" not in methods:
        raise ValueError("arima_options requires the arima method")
    regressors = None
    if exog is not None:
        if "arima" not in methods:
            raise ValueError("exog requires the arima method")
        regressors = _regressors(exog, len(values))
        source["exog_values_sha256"] = hashlib.sha256(
            regressors.astype("<f8").tobytes()
        ).hexdigest()
    folds, messages = [], []
    cases = {method: [] for method in methods}
    with np.errstate(over="raise", invalid="raise", divide="raise"):
        for origin in range(initial, len(values) - horizon + 1, step):
            start = 0 if window is None else origin - window
            training = values[start:origin].copy()
            differences = training[seasonal_period:] - training[:-seasonal_period]
            absolute_scale = float(np.mean(np.abs(differences)))
            squared_scale = float(np.mean(differences**2))
            fold = {
                "training_start": start,
                "training_stop": origin,
                "test_start": origin,
                "test_stop": origin + horizon,
                "training_values_sha256": hashlib.sha256(
                    training.astype("<f8").tobytes()
                ).hexdigest(),
                "transform": None,
            }
            working = training
            lmbda = None
            if transform is not None:
                fitted = power_transform(training, method=transform, lmbda=transform_lambda)
                working = np.array(fitted["result"]["values"])
                lmbda = fitted["parameters"]["lambda"]
                fold["transform"] = {
                    "method": transform,
                    "lambda": lmbda,
                    "estimated": transform_lambda is None,
                }
                messages.extend(f"origin={origin}: {message}" for message in fitted["warnings"])
            folds.append(fold)
            for method in methods:
                if method == "arima":
                    fitted = forecast_arima(
                        working,
                        steps=horizon,
                        alpha=alpha,
                        exog=None if regressors is None else regressors[start:origin],
                        future_exog=(
                            None if regressors is None else regressors[origin : origin + horizon]
                        ),
                        **arima_options,
                    )
                    predicted, lower, upper = (
                        np.array(fitted["result"][k]) for k in ["mean", "lower", "upper"]
                    )
                    messages.extend(f"origin={origin}: {message}" for message in fitted["warnings"])
                else:
                    predicted, lower, upper = _baseline(
                        working, horizon, method, seasonal_period, alpha
                    )
                if transform is not None:
                    predicted, lower, upper = (
                        np.array(
                            inverse_power_transform(v, method=transform, lmbda=lmbda)["result"][
                                "values"
                            ]
                        )
                        for v in [predicted, lower, upper]
                    )
                if any(
                    v.shape != (horizon,) or not np.all(np.isfinite(v))
                    for v in [predicted, lower, upper]
                ):
                    raise RuntimeError("forecast output must have a finite value for every horizon")
                if np.any(lower > upper) or np.any(lower > predicted) or np.any(predicted > upper):
                    raise RuntimeError("forecast interval bounds do not contain the point forecast")
                for h in range(horizon):
                    actual = float(values[origin + h])
                    error = actual - float(predicted[h])
                    case = {
                        "origin": origin,
                        "target_index": origin + h,
                        "horizon": h + 1,
                        "actual": actual,
                        "predicted": float(predicted[h]),
                        "lower": float(lower[h]),
                        "upper": float(upper[h]),
                        "scaled_absolute_error": (
                            abs(error) / absolute_scale if absolute_scale > 0 else None
                        ),
                        "scaled_squared_error": (
                            error**2 / squared_scale if squared_scale > 0 else None
                        ),
                        "interval_status": (
                            "degenerate"
                            if lower[h] == upper[h]
                            else ("inverse_transformed" if transform else "conditional_gaussian")
                        ),
                    }
                    if "interval_micros" in source:
                        case["timestamp_micros"] = (
                            source["t_start_micros"] + (origin + h) * source["interval_micros"]
                        )
                    cases[method].append(case)
        result = {
            "folds": folds,
            "cases": cases,
            "metrics": {m: _metrics(rows, alpha) for m, rows in cases.items()},
            "by_horizon": {
                m: {
                    str(h): _metrics([c for c in rows if c["horizon"] == h], alpha)
                    for h in range(1, horizon + 1)
                }
                for m, rows in cases.items()
            },
        }
    return record(
        "rolling_backtest",
        {
            "initial": initial,
            "horizon": horizon,
            "step": step,
            "window": window,
            "methods": list(methods),
            "seasonal_period": seasonal_period,
            "alpha": alpha,
            "arima_options": arima_options,
            "exog_columns": 0 if regressors is None else regressors.shape[1],
            "transform": transform,
            "transform_lambda": transform_lambda,
            "point_statistic": "inverse_transformed_point" if transform else "conditional_mean",
        },
        source,
        result,
        messages,
        backend={
            "name": "opentsx",
            "version": __version__,
            "numpy_version": np.__version__,
            "scipy_version": scipy.__version__,
            "statsmodels_version": statsmodels.__version__,
        },
    )
