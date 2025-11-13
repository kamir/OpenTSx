"""Detrended Fluctuation Analysis (DFA) implementation."""

from typing import Optional, Tuple, Union, Dict, Any
import numpy as np
import numpy.typing as npt


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
        """
        # Extract values
        if hasattr(time_series, 'values'):
            data = time_series.values
        else:
            data = np.asarray(time_series, dtype=np.float64)

        n = len(data)

        # Generate scales
        if scales is None:
            if max_scale is None:
                max_scale = n // 4
            scales = self._generate_scales(min_scale, max_scale, num_scales)
        else:
            scales = np.asarray(scales, dtype=np.int32)

        # Compute cumulative sum (integrated series)
        mean = np.mean(data)
        cumsum = np.cumsum(data - mean)

        # Calculate fluctuations for each scale
        fluctuations = np.zeros(len(scales))

        for i, scale in enumerate(scales):
            fluctuations[i] = self._calculate_fluctuation(cumsum, scale)

        return scales, fluctuations

    def _calculate_fluctuation(
        self,
        cumsum: npt.NDArray[np.float64],
        scale: int
    ) -> float:
        """
        Calculate fluctuation function for a given scale.

        Args:
            cumsum: Cumulative sum of series
            scale: Window size

        Returns:
            Root mean square fluctuation
        """
        n = len(cumsum)
        num_segments = n // scale

        # Truncate to fit complete segments
        truncated_cumsum = cumsum[:num_segments * scale]

        # Reshape into segments
        segments = truncated_cumsum.reshape(num_segments, scale)

        # Calculate variance for each segment
        variances = np.zeros(num_segments)
        for i, segment in enumerate(segments):
            variances[i] = self._detrend_segment(segment)

        # Return RMS fluctuation
        fluctuation = np.sqrt(np.mean(variances))
        return fluctuation

    def _detrend_segment(self, segment: npt.NDArray[np.float64]) -> float:
        """
        Detrend a segment using polynomial fit.

        Args:
            segment: Data segment

        Returns:
            Variance of detrended segment
        """
        n = len(segment)
        x = np.arange(n)

        # Fit polynomial
        coeffs = np.polyfit(x, segment, self.polynom_order)
        trend = np.polyval(coeffs, x)

        # Calculate variance of residuals
        residuals = segment - trend
        variance = np.mean(residuals ** 2)

        return variance

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
        """
        # Select fit range
        if fit_range is not None:
            mask = (scales >= fit_range[0]) & (scales <= fit_range[1])
            scales = scales[mask]
            fluctuations = fluctuations[mask]

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
        """Interpret scaling exponent."""
        if alpha < 0.5:
            return "Anti-correlated (mean-reverting)"
        elif abs(alpha - 0.5) < 0.05:
            return "Uncorrelated (white noise)"
        elif 0.5 < alpha < 1.0:
            return "Correlated (persistent, trending)"
        elif abs(alpha - 1.0) < 0.05:
            return "1/f noise (pink noise)"
        else:
            return "Non-stationary (Brownian motion)"
