"""Detrended Fluctuation Analysis (DFA) implementation."""

from typing import Optional, Tuple, Union, Dict, Any
import numpy as np
import numpy.typing as npt

from opentsx.algorithms._input import finite_values


def _series_and_scales(
    time_series: Any,
    polynom_order: int,
    scales: Optional[Any],
    min_scale: int,
    max_scale: Optional[int],
    num_scales: int,
) -> Tuple[npt.NDArray[np.float64], npt.NDArray[np.int32]]:
    """The finite, varying values and the window sizes; ValueError naming the argument."""
    data = finite_values(time_series, "time_series")
    n = len(data)
    if data.min() == data.max():
        raise ValueError(
            f"time_series: all {n} values are {data[0]}; expected a series that varies"
        )
    lowest = polynom_order + 2
    if scales is None:
        if min_scale < lowest:
            raise ValueError(
                f"min_scale: {min_scale} is below {lowest}; expected at least polynom_order + 2"
            )
        if num_scales < 2:
            raise ValueError(
                f"num_scales: {num_scales} is below 2; expected at least 2 window sizes"
            )
        if max_scale is None:
            max_scale = n // 4
            if max_scale <= min_scale:
                raise ValueError(
                    f"time_series: {n} values give max_scale {max_scale}, not above min_scale "
                    f"{min_scale}; expected at least {4 * (min_scale + 1)} values, or a smaller "
                    "min_scale"
                )
        elif not min_scale < max_scale <= n:
            raise ValueError(
                f"max_scale: {max_scale} is outside {min_scale + 1} to {n}; "
                "expected a window size above min_scale and at most the series length"
            )
        scales = DFA._generate_scales(min_scale, max_scale, num_scales)
    else:
        scales = np.asarray(scales, dtype=np.int32)
        bad = scales[(scales < lowest) | (scales > n)]
        if len(bad):
            raise ValueError(
                f"scales: window size {bad[0]} is outside {lowest} to {n}; "
                "expected window sizes from polynom_order + 2 to the series length"
            )
    distinct = len(np.unique(scales))
    if distinct < 2:
        raise ValueError(f"scales: {distinct} distinct window size; expected at least 2")
    return data, scales


def _segment_variances(
    cumsum: npt.NDArray[np.float64], scale: int, polynom_order: int
) -> npt.NDArray[np.float64]:
    """Mean squared residual of the trend polynomial in each whole window of size ``scale``."""
    num_segments = len(cumsum) // scale
    segments = cumsum[:num_segments * scale].reshape(num_segments, scale).T
    x = np.arange(scale)
    coeffs = np.polyfit(x, segments, polynom_order)
    trend = np.vander(x, polynom_order + 1) @ coeffs
    return np.mean((segments - trend) ** 2, axis=0)


def _exact_fit(scale: int) -> ValueError:
    return ValueError(
        f"time_series: every window of size {scale} is fitted exactly by the trend polynomial, "
        f"so F({scale}) is 0 up to rounding; expected values that vary within the windows of "
        "every scale"
    )


class DFA:
    """
    Detrended Fluctuation Analysis for long-range correlation detection.

    Implements the algorithm from:
    Peng et al. (1994) "Mosaic organization of DNA nucleotides"

    Examples:
        >>> from opentsx import TimeSeriesObject, DFA
        >>> ts = TimeSeriesObject(data=np.random.randn(1000))
        >>> dfa = DFA(polynom_order=1)
        >>> results = dfa.analyze(ts)
        >>> print(f"Alpha: {results['alpha']:.3f}")
    """

    def __init__(self, polynom_order: int = 1):
        """
        Initialize DFA.

        Args:
            polynom_order: Order of detrending polynomial (1=linear, 2=quadratic)
        """
        self.polynom_order = polynom_order

    def calculate(
        self,
        time_series: Union[npt.NDArray[np.float64], "TimeSeriesObject"],
        scales: Optional[npt.NDArray[np.int32]] = None,
        min_scale: int = 10,
        max_scale: Optional[int] = None,
        num_scales: int = 20
    ) -> Tuple[npt.NDArray[np.int32], npt.NDArray[np.float64]]:
        """
        Calculate DFA fluctuation function.

        Args:
            time_series: Input time series
            scales: Custom scale array (if None, auto-generated)
            min_scale: Minimum scale (window size)
            max_scale: Maximum scale (default: len(series)/4)
            num_scales: Number of scales to compute

        Returns:
            (scales, fluctuations): Arrays of scales and fluctuations

        Raises:
            ValueError: a value that is not finite, a constant series, window sizes outside
                polynom_order + 2 to the series length, fewer than 2 window sizes, or a window size
                at which every window is fitted exactly up to rounding
        """
        data, scales = _series_and_scales(
            time_series, self.polynom_order, scales, min_scale, max_scale, num_scales
        )

        # Compute cumulative sum (integrated series)
        mean = np.mean(data)
        cumsum = np.cumsum(data - mean)

        # Calculate fluctuations for each scale
        fluctuations = np.zeros(len(scales))
        rounding = 1e-10 * np.abs(cumsum).max()

        for i, scale in enumerate(scales):
            variances = _segment_variances(cumsum, scale, self.polynom_order)
            fluctuations[i] = np.sqrt(np.mean(variances))
            if fluctuations[i] <= rounding:
                raise _exact_fit(scale)

        return scales, fluctuations

    @staticmethod
    def _generate_scales(min_scale: int, max_scale: int, num_scales: int) -> npt.NDArray[np.int32]:
        """Generate logarithmically spaced scales."""
        return np.unique(
            np.logspace(
                np.log10(min_scale),
                np.log10(max_scale),
                num_scales
            ).astype(int)
        )

    def fit_scaling_exponent(
        self,
        scales: npt.NDArray[np.int32],
        fluctuations: npt.NDArray[np.float64],
        fit_range: Optional[Tuple[int, int]] = None
    ) -> Tuple[float, float, float]:
        """
        Fit scaling exponent alpha from log-log plot.

        Args:
            scales: Array of scales
            fluctuations: Array of fluctuations
            fit_range: Optional (min_scale, max_scale) for fitting

        Returns:
            (alpha, intercept, r_squared): Scaling exponent, intercept, R²

        Raises:
            ValueError: fewer than 2 window sizes in the fit, or a fluctuation that is not positive
        """
        # Select fit range
        if fit_range is not None:
            mask = (scales >= fit_range[0]) & (scales <= fit_range[1])
            scales = scales[mask]
            fluctuations = fluctuations[mask]
        if len(scales) < 2:
            kept = "scales:" if fit_range is None else f"fit_range: {fit_range} keeps"
            raise ValueError(f"{kept} {len(scales)} window size; expected at least 2")
        bad = np.flatnonzero(~(fluctuations > 0))
        if len(bad):
            i = bad[0]
            raise ValueError(
                f"fluctuations: F({scales[i]}) is {fluctuations[i]}; expected positive values"
            )

        # Log-log regression
        log_scales = np.log10(scales)
        log_flucts = np.log10(fluctuations)

        # Linear fit
        coeffs = np.polyfit(log_scales, log_flucts, 1)
        alpha = float(coeffs[0])
        intercept = float(coeffs[1])

        # Calculate R²
        fitted = alpha * log_scales + intercept
        ss_res = np.sum((log_flucts - fitted) ** 2)
        ss_tot = np.sum((log_flucts - np.mean(log_flucts)) ** 2)
        r_squared = float(1 - (ss_res / ss_tot))

        return alpha, intercept, r_squared

    def analyze(
        self,
        time_series: Union[npt.NDArray[np.float64], "TimeSeriesObject"],
        **kwargs: Any
    ) -> Dict[str, Any]:
        """
        Perform complete DFA analysis.

        Args:
            time_series: Input time series
            **kwargs: Additional arguments for calculate()

        Returns:
            Dictionary with results
        """
        scales, fluctuations = self.calculate(time_series, **kwargs)
        alpha, intercept, r_squared = self.fit_scaling_exponent(scales, fluctuations)

        results = {
            'alpha': alpha,
            'intercept': intercept,
            'r_squared': r_squared,
            'scales': scales,
            'fluctuations': fluctuations,
            'interpretation': self._interpret_alpha(alpha)
        }

        return results

    @staticmethod
    def _interpret_alpha(alpha: float) -> str:
        """The band of alpha; 0.45 to 0.55 and 0.95 to 1.05 are the bands around 0.5 and 1."""
        if not np.isfinite(alpha):
            raise ValueError(f"alpha: {alpha} is not finite; expected a finite scaling exponent")
        if alpha < 0.45:
            return "Anti-correlated (mean-reverting)"
        if alpha <= 0.55:
            return "Uncorrelated (white noise)"
        if alpha < 0.95:
            return "Correlated (persistent, trending)"
        if alpha <= 1.05:
            return "1/f noise (pink noise)"
        return "Non-stationary (fractional Brownian motion)"
