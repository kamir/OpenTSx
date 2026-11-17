"""Bounded finite forecasting requests on explicit workbench snapshots."""

import hashlib
import json
import multiprocessing
import threading

import numpy as np

SCHEMA = "opentsx.modelling-request.v1"
MAX_BODY = 1_048_576
TIMEOUT_SECONDS = 60
_GATE = threading.BoundedSemaphore(1)
BASELINES = {"mean", "naive", "seasonal_naive", "drift"}


def fields(value, required, optional=()):
    if (
        not isinstance(value, dict)
        or set(value) - set(required) - set(optional)
        or set(required) - set(value)
    ):
        raise ValueError("request contains missing or unsupported fields")


def integer(value, name, low, high):
    if isinstance(value, bool) or not isinstance(value, int) or not low <= value <= high:
        raise ValueError(f"{name} must be an integer from {low} to {high}")
    return value


def real(value, name):
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise ValueError(f"{name} must be a finite real number")
    try:
        finite = np.isfinite(float(value))
    except OverflowError as error:
        raise ValueError(f"{name} must be a finite real number") from error
    if not finite:
        raise ValueError(f"{name} must be a finite real number")
    return value


def text(value, name):
    if not isinstance(value, str) or not value or len(value) > 512:
        raise ValueError(f"{name} must be a nonempty string of at most 512 characters")


def matrix(value, rows, columns, name, timestamps=False):
    if not isinstance(value, list) or len(value) != rows:
        raise ValueError(f"{name} has the wrong row count")
    for row in value:
        if not isinstance(row, list) or len(row) != columns:
            raise ValueError(f"{name} has the wrong column count")
        for cell in row:
            if timestamps:
                integer(cell, name, -(2**53 - 1), 2**53 - 1)
            else:
                real(cell, name)


def validate_modelling(document):
    fields(document, {"schema", "target", "configuration", "backtest", "context"})
    if document["schema"] != SCHEMA:
        raise ValueError("unsupported modelling request schema")
    target, config, backtest = (document[k] for k in ("target", "configuration", "backtest"))
    fields(
        target,
        {
            "series_id",
            "label",
            "unit",
            "time_kind",
            "values",
            "timestamps_micros",
            "episode_ids",
        },
        {"quality", "quality_known"},
    )
    text(target["series_id"], "series_id")
    text(target["label"], "label")
    if target["unit"] is not None:
        text(target["unit"], "unit")
    if target["time_kind"] not in ("absolute", "relative", "index"):
        raise ValueError("time_kind must be absolute, relative or index")
    values, times = target["values"], target["timestamps_micros"]
    if not isinstance(values, list) or not 9 <= len(values) <= 2048:
        raise ValueError("target needs 9 to 2048 samples")
    n = len(values)
    if not isinstance(times, list) or len(times) != n:
        raise ValueError("timestamps and values must have the same length")
    for value in values:
        real(value, "target value")
    for stamp in times:
        integer(stamp, "timestamp", -(2**53 - 1), 2**53 - 1)
    interval = times[1] - times[0]
    if interval <= 0 or any(b - a != interval for a, b in zip(times, times[1:])):
        raise ValueError("target must be strictly increasing and regular; prepare it explicitly")
    ids = target["episode_ids"]
    if not isinstance(ids, list) or len(ids) > 1000:
        raise ValueError("episode_ids must contain at most 1000 distinct references")
    for value in ids:
        text(value, "episode id")
    if len(set(ids)) != len(ids):
        raise ValueError("episode_ids must contain at most 1000 distinct references")
    quality = target.get("quality")
    if quality is not None and (
        not isinstance(quality, list)
        or len(quality) != n
        or any(type(q) is not int or q != 0 for q in quality)
    ):
        raise ValueError(
            "target quality must be absent or all zero; prepare flagged samples explicitly"
        )
    known = target.get("quality_known")
    if "quality_known" in target and (
        quality is None
        or not isinstance(known, list)
        or len(known) != n
        or any(type(q) is not int or q not in (0, 1) for q in known)
    ):
        raise ValueError("quality_known must be a byte mask matching quality")
    fields(
        config,
        {"family", "order", "seasonal_order", "trend", "steps", "alpha", "maxiter"},
    )
    family = config["family"]
    if family not in ("arima", "sarima", "sarimax"):
        raise ValueError("unknown forecast family")
    order, seasonal = config["order"], config["seasonal_order"]
    if (
        not isinstance(order, list)
        or len(order) != 3
        or not isinstance(seasonal, list)
        or len(seasonal) != 4
    ):
        raise ValueError("order needs three and seasonal_order four integers")
    for value, high in zip(order, (3, 2, 3)):
        integer(value, "order", 0, high)
    for value, high in zip(seasonal, (2, 1, 2, 96)):
        integer(value, "seasonal order", 0, high)
    if seasonal[3] == 1 or (any(seasonal[:3]) and seasonal[3] < 2):
        raise ValueError("seasonal terms require a period from 2 to 96")
    if family == "arima" and any(seasonal):
        raise ValueError("ARIMA requires zero seasonal orders; select SARIMA")
    if family == "sarima" and not any(seasonal[:3]):
        raise ValueError("SARIMA requires at least one seasonal component")
    states = (
        max(
            order[0] + seasonal[0] * seasonal[3],
            order[2] + seasonal[2] * seasonal[3] + 1,
        )
        + order[1]
        + seasonal[1] * seasonal[3]
    )
    if states > 128:
        raise ValueError("state estimate exceeds the local 128-state limit")
    if config["trend"] not in ("n", "c", "t", "ct"):
        raise ValueError("unsupported trend")
    steps = integer(config["steps"], "steps", 1, 96)
    integer(config["maxiter"], "maxiter", 1, 200)
    if not 0 < real(config["alpha"], "alpha") < 1:
        raise ValueError("alpha must be between zero and one")
    integer(
        times[-1] + steps * interval,
        "last forecast coordinate",
        -(2**53 - 1),
        2**53 - 1,
    )
    fields(backtest, {"initial", "horizon", "step", "window", "seasonal_period", "methods"})
    initial = integer(backtest["initial"], "initial", 8, n - 1)
    horizon = integer(backtest["horizon"], "backtest horizon", 1, min(96, n - initial))
    step = integer(backtest["step"], "backtest step", 1, n)
    window = backtest["window"]
    if window is not None:
        integer(window, "window", 8, initial)
    period = integer(backtest["seasonal_period"], "seasonal_period", 1, (window or initial) - 1)
    methods = backtest["methods"]
    if (
        not isinstance(methods, list)
        or not methods
        or any(not isinstance(m, str) or m not in BASELINES for m in methods)
        or len(set(methods)) != len(methods)
        or "naive" not in methods
    ):
        raise ValueError("backtest methods must be unique supported baselines including naive")
    if "seasonal_naive" in methods and period < 2:
        raise ValueError("seasonal naive needs a period of at least two")
    origins = list(range(initial, n - horizon + 1, step))
    if not 1 <= len(origins) <= 12:
        raise ValueError("backtest requires 1 to 12 origins; increase the step or initial window")
    context = document["context"]
    if (family == "sarimax") != (context is not None):
        raise ValueError("SARIMAX requires context; other families do not accept context")
    if context is not None:
        fields(
            context,
            {
                "names",
                "historical",
                "historical_available_at_micros",
                "future",
                "future_available_at_micros",
            },
            {"units"},
        )
        names = context["names"]
        if (
            not isinstance(names, list)
            or not 1 <= len(names) <= 8
            or any(not isinstance(v, str) for v in names)
            or len(set(names)) != len(names)
        ):
            raise ValueError("context requires 1 to 8 distinct names")
        for name in names:
            text(name, "context name")
        if "units" in context:
            units = context["units"]
            if not isinstance(units, list) or len(units) != len(names):
                raise ValueError("context units must match the ordered context names")
            for unit in units:
                text(unit, "context unit")
        for key, rows, stamps in (
            ("historical", n, False),
            ("historical_available_at_micros", n, True),
            ("future", steps, False),
            ("future_available_at_micros", steps, True),
        ):
            matrix(context[key], rows, len(names), key, stamps)
        for origin in [*origins, n]:
            cutoff = times[origin - 1]
            start = max(0, origin - window) if window else 0
            end = min(n, origin + horizon) if origin < n else n
            if any(
                t > cutoff
                for row in context["historical_available_at_micros"][start:end]
                for t in row
            ):
                raise ValueError(f"context was not available at forecast origin {origin}")
        if any(t > times[-1] for row in context["future_available_at_micros"] for t in row):
            raise ValueError("future context was not available at the final forecast origin")
    return document


def execute_modelling(document):
    validate_modelling(document)
    from opentsx import __version__
    from opentsx.algorithms.forecasting import forecast_arima
    from opentsx.algorithms.evaluation import forecast_baseline, rolling_backtest
    import scipy
    import statsmodels

    target, config, bt = (document[k] for k in ("target", "configuration", "backtest"))
    context = document["context"]
    values = target["values"]
    options = {k: config[k] for k in ("order", "seasonal_order", "trend", "maxiter")}
    exog = None if context is None else context["historical"]
    forecast = forecast_arima(
        values,
        steps=config["steps"],
        alpha=config["alpha"],
        exog=exog,
        future_exog=None if context is None else context["future"],
        **options,
    )
    baselines = {
        name: forecast_baseline(
            values,
            steps=config["steps"],
            method=name,
            seasonal_period=bt["seasonal_period"],
            alpha=config["alpha"],
        )
        for name in bt["methods"]
    }
    backtest = rolling_backtest(
        values,
        initial=bt["initial"],
        horizon=bt["horizon"],
        step=bt["step"],
        window=bt["window"],
        seasonal_period=bt["seasonal_period"],
        methods=[*bt["methods"], "arima"],
        alpha=config["alpha"],
        arima_options=options,
        exog=exog,
    )
    times = target["timestamps_micros"]
    interval = times[1] - times[0]
    source = {
        "series_id": target["series_id"],
        "label": target["label"],
        "unit": target["unit"],
        "episode_ids": target["episode_ids"],
        "time_kind": target["time_kind"],
        "nobs": len(values),
        "interval_micros": interval,
        "t_start_micros": times[0],
        "t_end_micros": times[-1] + interval,
        "values_sha256": hashlib.sha256(np.asarray(values, dtype="<f8").tobytes()).hexdigest(),
        "context_names": [] if context is None else context["names"],
        "reference_kind": "browser-snapshot",
    }
    for record in [forecast, *baselines.values()]:
        record["input"].update(source)
        record["result"]["timestamps_micros"] = [
            times[-1] + (i + 1) * interval for i in range(config["steps"])
        ]
    forecast["result"]["interval_status"] = "conditional_gaussian"
    backtest["input"].update(source)
    for cases in backtest["result"]["cases"].values():
        for case in cases:
            case["timestamp_micros"] = times[case["target_index"]]
    warnings = [
        "Conditional Gaussian intervals do not include full parameter-estimation uncertainty.",
        "Input identities are client-supplied snapshot references, not verified broker offsets.",
    ]
    if (
        target.get("quality") is None
        or "quality_known" not in target
        or any(q == 0 for q in target["quality_known"])
    ):
        warnings.append("Quality was not supplied or confirmed for every target sample.")
    for record in [forecast, backtest, *baselines.values()]:
        warnings.extend(record["warnings"])
    runtime = {
        "opentsx": __version__,
        "statsmodels": statsmodels.__version__,
        "numpy": np.__version__,
        "scipy": scipy.__version__,
    }
    identity = json.dumps(
        {"request": document, "runtime": runtime},
        sort_keys=True,
        separators=(",", ":"),
        allow_nan=False,
    ).encode()
    result = {
        "schema": "opentsx.modelling-result.v1",
        "run_id": hashlib.sha256(identity).hexdigest(),
        "source": source,
        "configuration": config,
        "forecast": forecast,
        "baselines": baselines,
        "backtest": backtest,
        "warnings": list(dict.fromkeys(warnings)),
        "runtime": runtime,
    }
    if context is not None and "units" in context:
        from opentsx.client.context_join import schema_descriptor

        result["context_schema"] = schema_descriptor(
            target["series_id"], context["names"], context["units"], interval
        )
    json.dumps(result, allow_nan=False)
    return result


def _worker(document, connection):
    try:
        connection.send((200, execute_modelling(document)))
    except ImportError:
        connection.send(
            (
                503,
                {"error": "Forecasting runtime unavailable; install opentsx[forecasting]."},
            )
        )
    except ValueError as error:
        connection.send((400, {"error": str(error)}))
    except (RuntimeError, FloatingPointError, np.linalg.LinAlgError) as error:
        connection.send((422, {"error": str(error)}))
    except Exception as error:
        print(f"modelling failed: {type(error).__name__}", flush=True)
        connection.send((422, {"error": "Numerical fitting failed; inspect service logs."}))
    finally:
        connection.close()


def run_modelling(document):
    validate_modelling(document)
    if not _GATE.acquire(blocking=False):
        return 503, {"error": "A modelling run is active; retry after it completes."}
    receiver = sender = process = None
    try:
        context = multiprocessing.get_context("spawn")
        receiver, sender = context.Pipe(duplex=False)
        process = context.Process(target=_worker, args=(document, sender), daemon=True)
        process.start()
        sender.close()
        if not receiver.poll(TIMEOUT_SECONDS):
            process.terminate()
            return 504, {
                "error": "Modelling exceeded the 60-second budget; reduce orders, training size or folds."
            }
        try:
            return receiver.recv()
        except EOFError:
            return 422, {"error": "The numerical process ended without a result."}
    finally:
        if process is not None:
            if process.is_alive():
                process.terminate()
            process.join()
        if receiver is not None:
            receiver.close()
        if sender is not None:
            sender.close()
        _GATE.release()
