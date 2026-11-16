"""Offline seasonal decomposition and invertible power transformations."""

import warnings

import numpy as np
import scipy
from scipy import special, stats
from statsmodels.tsa.seasonal import MSTL, STL

from opentsx.algorithms._analysis import integer, record, regular_values


def seasonal_decomposition(series, *, periods, windows=None, robust=False):
    values, source = regular_values(series)
    if isinstance(periods, (int, np.integer)):
        periods = [periods]
    if not isinstance(periods, (list, tuple)) or not periods:
        raise ValueError("periods must be a nonempty sequence of sample counts")
    periods = [integer(p, "period", minimum=2) for p in periods]
    if len(set(periods)) != len(periods) or max(periods) * 2 >= len(values):
        raise ValueError("periods must be unique and strictly smaller than half the sample count")
    if not isinstance(robust, bool):
        raise ValueError("robust must be boolean")
    if windows is None:
        periods = sorted(periods)
        windows = [7] if len(periods) == 1 else [11 + 4 * i for i in range(len(periods))]
    else:
        if not isinstance(windows, (list, tuple)) or len(windows) != len(periods):
            raise ValueError("windows must match the number of periods")
        windows = [integer(w, "window", minimum=7) for w in windows]
        if any(w % 2 == 0 for w in windows):
            raise ValueError("seasonal windows must be odd")
        pairs = sorted(zip(periods, windows))
        periods, windows = map(list, zip(*pairs))
    with warnings.catch_warnings(record=True) as caught:
        warnings.simplefilter("always")
        if len(periods) == 1:
            fitted = STL(values, period=periods[0], seasonal=windows[0], robust=robust).fit()
        else:
            fitted = MSTL(
                values,
                periods=periods,
                windows=windows,
                lmbda=None,
                iterate=2,
                stl_kwargs={"robust": robust},
            ).fit()
    seasonal = np.asarray(fitted.seasonal).reshape(len(values), len(periods))
    return record(
        "stl" if len(periods) == 1 else "mstl",
        {
            "periods": periods,
            "windows": windows,
            "robust": robust,
            "causal": False,
            "iterations": 2 if len(periods) > 1 else None,
        },
        source,
        {
            "trend": np.asarray(fitted.trend).tolist(),
            "seasonal": {str(p): seasonal[:, i].tolist() for i, p in enumerate(periods)},
            "residual": np.asarray(fitted.resid).tolist(),
            "seasonally_adjusted": (values - seasonal.sum(axis=1)).tolist(),
        },
        [str(w.message) for w in caught],
    )


def _lambda(value):
    if (
        isinstance(value, (bool, np.bool_))
        or not isinstance(value, (int, float, np.integer, np.floating))
        or not np.isfinite(value)
    ):
        raise ValueError("lambda must be a finite real number")
    return float(value)


def power_transform(series, *, method="boxcox", lmbda=None):
    if method not in ("boxcox", "yeojohnson"):
        raise ValueError("method must be boxcox or yeojohnson")
    estimated = lmbda is None
    if not estimated:
        lmbda = _lambda(lmbda)
    values, source = regular_values(
        series, minimum=8 if estimated else 1, allow_constant=not estimated
    )
    if method == "boxcox" and np.any(values <= 0):
        raise ValueError("Box-Cox input must be strictly positive")
    with warnings.catch_warnings(record=True) as caught:
        warnings.simplefilter("always")
        function = stats.boxcox if method == "boxcox" else stats.yeojohnson
        if estimated:
            transformed, lmbda = function(values)
        else:
            transformed = function(values, lmbda=lmbda)
    if not np.all(np.isfinite(transformed)):
        raise ValueError("power transformation produced non-finite values")
    return record(
        method,
        {"lambda": float(lmbda), "estimated": estimated},
        source,
        {"values": transformed.tolist(), "value_space": "power_transformed"},
        [str(w.message) for w in caught],
        backend={"name": "scipy", "version": scipy.__version__},
    )


def inverse_power_transform(series, *, method, lmbda):
    if method not in ("boxcox", "yeojohnson"):
        raise ValueError("method must be boxcox or yeojohnson")
    lmbda = _lambda(lmbda)
    values, source = regular_values(series, minimum=1, allow_constant=True)
    with np.errstate(over="raise", invalid="raise", divide="raise"):
        if method == "boxcox":
            if lmbda != 0 and np.any(1 + lmbda * values <= 0):
                raise ValueError("values lie outside the inverse Box-Cox domain")
            restored = special.inv_boxcox(values, lmbda)
        else:
            restored = np.empty_like(values)
            positive = values >= 0
            for mask, exponent, direction in [(positive, lmbda, 1), (~positive, 2 - lmbda, -1)]:
                part = direction * values[mask]
                if exponent == 0:
                    restored[mask] = direction * np.expm1(part)
                else:
                    if np.any(1 + exponent * part <= 0):
                        raise ValueError("values lie outside the inverse Yeo-Johnson domain")
                    restored[mask] = direction * np.expm1(np.log1p(exponent * part) / exponent)
    if not np.all(np.isfinite(restored)):
        raise ValueError("inverse power transformation produced non-finite values")
    return record(
        "inverse_" + method,
        {"lambda": lmbda},
        source,
        {"values": restored.tolist(), "value_space": "original"},
        [],
        backend={"name": "scipy", "version": scipy.__version__},
    )
