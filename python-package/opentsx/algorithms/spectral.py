"""Welch densities, cross-spectra and magnitude-squared coherence."""

from collections.abc import Mapping
import warnings

import numpy as np
import scipy
from scipy import signal, stats

from opentsx.algorithms._analysis import integer, probability, record, regular_values
from opentsx.core.time_series import TimeSeriesObject


def _options(source, sample_rate, nperseg, noverlap, nfft, window, detrend, alpha):
    nperseg = integer(nperseg, "nperseg", minimum=4)
    noverlap = nperseg // 2 if noverlap is None else integer(noverlap, "noverlap", minimum=0)
    nfft = nperseg if nfft is None else integer(nfft, "nfft")
    alpha = probability(alpha)
    if nperseg > source["nobs"] or noverlap >= nperseg or nfft < nperseg:
        raise ValueError("segment, overlap and FFT lengths are inconsistent with the input")
    if window not in ("hann", "hamming", "boxcar"):
        raise ValueError("window must be hann, hamming or boxcar")
    if detrend is not False and detrend not in ("constant", "linear"):
        raise ValueError("detrend must be constant, linear or False")
    frequency_unit = "cycles/sample"
    if sample_rate is not None:
        if (
            isinstance(sample_rate, (bool, np.bool_))
            or not isinstance(sample_rate, (int, float, np.integer, np.floating))
            or not np.isfinite(sample_rate)
            or sample_rate <= 0
        ):
            raise ValueError("sample rate must be finite and positive")
        frequency_unit = "Hz"
    if "interval_micros" in source:
        derived = 1_000_000 / source["interval_micros"]
        if sample_rate is not None and not np.isclose(sample_rate, derived, rtol=1e-12, atol=0):
            raise ValueError("declared sample rate disagrees with the episode interval")
        sample_rate = derived
        frequency_unit = "Hz"
    elif sample_rate is None:
        sample_rate = 1.0
    sample_rate = float(sample_rate)
    hop = nperseg - noverlap
    segments = 1 + (source["nobs"] - nperseg) // hop
    if segments < 2:
        raise ValueError("Welch estimation requires at least two complete segments")
    weights = signal.get_window(window, nperseg, fftbins=True)
    energy = np.dot(weights, weights)
    correction = 1.0
    for lag in range(1, min(segments, (nperseg - 1) // hop + 1)):
        offset = lag * hop
        rho = np.dot(weights[:-offset], weights[offset:]) / energy
        correction += 2 * (1 - lag / segments) * rho**2
    effective = segments / correction
    if not np.isfinite(effective) or effective <= 1:
        raise ValueError("too few effective spectral averages")
    parameters = {
        "sample_rate": sample_rate,
        "nperseg": nperseg,
        "noverlap": noverlap,
        "nfft": nfft,
        "window": window,
        "detrend": detrend,
        "alpha": alpha,
    }
    kwargs = {
        "fs": sample_rate,
        "window": weights,
        "nperseg": nperseg,
        "noverlap": noverlap,
        "nfft": nfft,
        "detrend": detrend,
        "return_onesided": True,
        "scaling": "density",
        "average": "mean",
    }
    metadata = {
        "frequency_unit": frequency_unit,
        "segments": segments,
        "effective_averages": float(effective),
        "bin_width": sample_rate / nfft,
        "segment_resolution": sample_rate / nperseg,
        "equivalent_noise_bandwidth": float(sample_rate * energy / weights.sum() ** 2),
    }
    return parameters, kwargs, metadata


def _density_limits(density, effective, nfft, alpha):
    degrees = np.full(len(density), 2 * effective)
    degrees[0] /= 2
    if nfft % 2 == 0:
        degrees[-1] /= 2
    lower = degrees * density / stats.chi2.ppf(1 - alpha / 2, degrees)
    upper = degrees * density / stats.chi2.ppf(alpha / 2, degrees)
    return lower.tolist(), upper.tolist()


def welch_spectrum(
    series,
    *,
    sample_rate=None,
    nperseg=256,
    noverlap=None,
    nfft=None,
    window="hann",
    detrend="constant",
    alpha=0.05,
):
    values, source = regular_values(series)
    parameters, kwargs, metadata = _options(
        source, sample_rate, nperseg, noverlap, nfft, window, detrend, alpha
    )
    with warnings.catch_warnings(record=True) as caught:
        warnings.simplefilter("always")
        frequency, density = signal.welch(values, **kwargs)
    lower, upper = _density_limits(
        density, metadata["effective_averages"], parameters["nfft"], parameters["alpha"]
    )
    unit = source.get("unit")
    result = {
        **metadata,
        "frequency": frequency.tolist(),
        "density": density.tolist(),
        "lower": lower,
        "upper": upper,
        "confidence_method": "overlap-adjusted chi-square approximation",
        "density_unit": (None if unit is None else f"{unit}^2/{metadata['frequency_unit']}"),
    }
    return record(
        "welch",
        parameters,
        source,
        result,
        [str(w.message) for w in caught],
        backend={"name": "scipy", "version": scipy.__version__},
    )


def _aligned(first, second, first_source, second_source):
    if first_source["nobs"] != second_source["nobs"]:
        raise ValueError("paired inputs must align and have equal sample counts")
    if isinstance(first, Mapping) and isinstance(second, Mapping):
        if any(first_source[k] != second_source[k] for k in ["t_start_micros", "interval_micros"]):
            raise ValueError("episode time axes do not align")
    elif isinstance(first, TimeSeriesObject) and isinstance(second, TimeSeriesObject):
        if not np.array_equal(first.timestamps, second.timestamps):
            raise ValueError("series time axes do not align")
    elif isinstance(first, (Mapping, TimeSeriesObject)) or isinstance(
        second, (Mapping, TimeSeriesObject)
    ):
        raise ValueError(
            "cannot align a known time axis with a different or unknown representation"
        )


def cross_spectrum(
    first,
    second,
    *,
    sample_rate=None,
    nperseg=256,
    noverlap=None,
    nfft=None,
    window="hann",
    detrend="constant",
    alpha=0.05,
):
    x, source = regular_values(first)
    y, paired = regular_values(second)
    _aligned(first, second, source, paired)
    parameters, kwargs, metadata = _options(
        source, sample_rate, nperseg, noverlap, nfft, window, detrend, alpha
    )
    with warnings.catch_warnings(record=True) as caught:
        warnings.simplefilter("always")
        frequency, pxx = signal.welch(x, **kwargs)
        _, pyy = signal.welch(y, **kwargs)
        _, pxy = signal.csd(x, y, **kwargs)
    with np.errstate(over="raise", invalid="raise", divide="raise"):
        denominator = pxx * pyy
        valid = denominator > 0
        coherence = np.zeros_like(pxx)
        coherence[valid] = np.abs(pxy[valid]) ** 2 / denominator[valid]
    if np.any(coherence > 1 + 1e-10) or not np.all(np.isfinite(coherence)):
        raise RuntimeError("spectral coherence violates its numerical bounds")
    coherence = np.clip(coherence, 0, 1)
    valid_phase = np.abs(pxy) > 0
    phase = np.angle(pxy)
    threshold = 1 - parameters["alpha"] ** (1 / (metadata["effective_averages"] - 1))
    source["paired_input"] = paired
    threshold_valid = np.ones(len(frequency), dtype=bool)
    threshold_valid[0] = False
    if parameters["nfft"] % 2 == 0:
        threshold_valid[-1] = False
    first_unit, second_unit = source.get("unit"), paired.get("unit")
    result = {
        **metadata,
        "frequency": frequency.tolist(),
        "first_density": pxx.tolist(),
        "second_density": pyy.tolist(),
        "cross_real": pxy.real.tolist(),
        "cross_imaginary": pxy.imag.tolist(),
        "phase_radians": [float(v) if ok else None for v, ok in zip(phase, valid_phase)],
        "valid_phase": valid_phase.tolist(),
        "coherence": [float(v) if ok else None for v, ok in zip(coherence, valid)],
        "valid_coherence": valid.tolist(),
        "coherence_null_threshold": float(threshold),
        "coherence_threshold_valid": threshold_valid.tolist(),
        "first_density_unit": (
            None if first_unit is None else f"{first_unit}^2/{metadata['frequency_unit']}"
        ),
        "second_density_unit": (
            None if second_unit is None else f"{second_unit}^2/{metadata['frequency_unit']}"
        ),
        "cross_density_unit": (
            None
            if first_unit is None or second_unit is None
            else f"{first_unit}*{second_unit}/{metadata['frequency_unit']}"
        ),
        "threshold_method": "pointwise independent-process approximation",
        "cross_convention": "conjugate(first) * second",
    }
    return record(
        "cross_spectrum",
        parameters,
        source,
        result,
        [str(w.message) for w in caught],
        backend={"name": "scipy", "version": scipy.__version__},
    )
