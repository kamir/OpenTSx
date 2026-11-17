"""Shared input and result contracts for finite time-series analysis."""

from collections.abc import Mapping
import hashlib
import json

import numpy as np
import statsmodels

from opentsx.core.time_series import TimeSeriesObject
from opentsx.model.series_key import verify_series_key
from opentsx.model.time_axis import to_us


def integer(value, name, minimum=1):
    if (
        isinstance(value, (bool, np.bool_))
        or not isinstance(value, (int, np.integer))
        or value < minimum
    ):
        raise ValueError(f"{name} must be an integer greater than or equal to {minimum}")
    return int(value)


def probability(value):
    if (
        isinstance(value, (bool, np.bool_))
        or not isinstance(value, (int, float, np.floating))
        or not 0 < value < 1
    ):
        raise ValueError("alpha must be a finite number between zero and one")
    return float(value)


def regular_values(data, *, minimum=8, allow_constant=False):
    source = {}
    if isinstance(data, Mapping):
        required = {
            "values",
            "count",
            "timeEncoding",
            "intervalMicros",
            "tStart",
            "tEnd",
            "episodeId",
            "series",
        }
        if not required.issubset(data):
            raise ValueError("episode is missing required fields")
        if data["timeEncoding"] != "REGULAR":
            raise ValueError("analysis requires a regular episode; resample explicitly")
        interval = integer(data["intervalMicros"], "intervalMicros")
        verify_series_key(data["series"])
        start, end = to_us(data["tStart"]), to_us(data["tEnd"])
        values = np.asarray(data["values"])
        if values.ndim != 1:
            raise ValueError("episode values must be one-dimensional")
        if integer(data["count"], "count") != len(values):
            raise ValueError("episode count does not match values")
        if end <= start + (len(values) - 1) * interval:
            raise ValueError("episode end does not include every sample")
        quality = data.get("quality")
        if quality is not None and (len(quality) != len(values) or any(q != 0 for q in quality)):
            raise ValueError("analysis requires absent or all-zero quality flags")
        source = {
            "episode_id": data["episodeId"],
            "series_id": data["series"]["seriesId"],
            "unit": data["series"]["unit"],
            "t_start_micros": start,
            "t_end_micros": end,
            "interval_micros": interval,
        }
    elif isinstance(data, TimeSeriesObject):
        values = np.asarray(data.values)
        timestamps = np.asarray(data.timestamps)
        if (
            timestamps.ndim != 1
            or len(timestamps) != len(values)
            or not np.all(np.isfinite(timestamps))
        ):
            raise ValueError("timestamps must match the finite sample axis")
        deltas = np.diff(timestamps)
        if len(deltas) and (
            np.any(deltas <= 0) or not np.allclose(deltas, deltas[0], rtol=1e-10, atol=0)
        ):
            raise ValueError("analysis requires a regular time axis; resample explicitly")
        source = {"label": data.label}
    elif isinstance(data, (list, tuple, np.ndarray)):
        values = np.asarray(data)
    else:
        raise ValueError("input must be samples, TimeSeriesObject or a regular v2 episode")
    if values.ndim != 1 or len(values) < minimum:
        raise ValueError(f"analysis requires a one-dimensional array of at least {minimum} samples")
    if not np.issubdtype(values.dtype, np.number) or np.iscomplexobj(values):
        raise ValueError("samples must be real numbers")
    values = values.astype(np.float64)
    if not np.all(np.isfinite(values)) or (not allow_constant and np.all(values == values[0])):
        raise ValueError("samples must be finite and nonconstant")
    source["nobs"] = len(values)
    source["values_sha256"] = hashlib.sha256(values.astype("<f8").tobytes()).hexdigest()
    return values, source


def record(method, parameters, source, result, messages, *, backend=None):
    output = {
        "method": method,
        "backend": (
            backend
            if backend is not None
            else {"name": "statsmodels", "version": statsmodels.__version__}
        ),
        "parameters": parameters,
        "input": source,
        "result": result,
        "warnings": messages,
    }
    json.dumps(output, allow_nan=False)
    return output
