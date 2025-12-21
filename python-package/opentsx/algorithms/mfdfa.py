"""Multifractal Detrended Fluctuation Analysis (MFDFA) implementation."""

from typing import Optional, Tuple, Union, Dict, Any
import numpy as np
import numpy.typing as npt
from opentsx.algorithms.dfa import DFA


class MFDFA:
    """
    Multifractal Detrended Fluctuation Analysis.

    Extends DFA to detect multifractal scaling properties.

    References:
        Kantelhardt et al. (2002) "Multifractal detrended fluctuation analysis
        of nonstationary time series"

    Examples:
        >>> from opentsx import TimeSeriesObject, MFDFA
        >>> ts = TimeSeriesObject(data=np.random.randn(5000))
        >>> mfdfa = MFDFA(polynom_order=1)
        >>> results = mfdfa.analyze(ts)
        >>> print(f"Multifractal: {results['is_multifractal']}")
        >>> print(f"Δh: {results['delta_h']:.3f}")
    """

    def __init__(
        self,
        polynom_order: int = 1,
        q_range: Optional[npt.NDArray[np.float64]] = None
    ):
        """
        Initialize MFDFA.

        Args:
            polynom_order: Order of detrending polynomial
            q_range: Array of q values (default: -10 to 10)
        """
        self.polynom_order = polynom_order
        self.dfa = DFA(polynom_order=polynom_order)

        if q_range is None:
            # Create q range excluding zero (handled separately)
            self.q_range = np.concatenate([
                np.arange(-10, 0, 0.5),
                np.array([0.0]),
                np.arange(0.5, 11, 0.5)
            ])
        else:
            self.q_range = q_range

    def calculate(
        self,
        time_series: Union[npt.NDArray[np.float64], Any],
        scales: Optional[npt.NDArray[np.int32]] = None,
        min_scale: int = 10,
        max_scale: Optional[int] = None,
        num_scales: int = 20
    ) -> Tuple[npt.NDArray[np.int32], npt.NDArray[np.float64], npt.NDArray[np.float64]]:
        """
        Calculate multifractal fluctuation functions.

        Args:
            time_series: Input time series
            scales: Custom scale array
            min_scale: Minimum scale
            max_scale: Maximum scale
            num_scales: Number of scales

        Returns:
            (scales, q_values, Fq): Scales, q-values, and fluctuation functions
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
            scales = DFA._generate_scales(min_scale, max_scale, num_scales)
        else:
            scales = np.asarray(scales, dtype=np.int32)

        # Compute cumulative sum (integrated series)
        mean = np.mean(data)
        cumsum = np.cumsum(data - mean)

        # Calculate Fq for each q and scale
        Fq = np.zeros((len(self.q_range), len(scales)))

        for s_idx, scale in enumerate(scales):
            # Get segment variances for this scale
            variances = self._calculate_segment_variances(cumsum, scale)

            # Calculate Fq for each q value
            for q_idx, q in enumerate(self.q_range):
                Fq[q_idx, s_idx] = self._calculate_Fq(variances, q)

        return scales, self.q_range, Fq

    def _calculate_segment_variances(
        self,
        cumsum: npt.NDArray[np.float64],
        scale: int
    ) -> npt.NDArray[np.float64]:
        """
        Calculate variances for all segments at given scale.

        Args:
            cumsum: Cumulative sum of series
            scale: Window size

        Returns:
            Array of segment variances
        """
        n = len(cumsum)
        num_segments = n // scale

        # Truncate to fit complete segments
        truncated = cumsum[:num_segments * scale]
        segments = truncated.reshape(num_segments, scale)

        # Calculate variance for each segment
        variances = np.zeros(num_segments)
        for i, segment in enumerate(segments):
            variances[i] = self._detrend_segment(segment)

        return variances

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
    def _calculate_Fq(variances: npt.NDArray[np.float64], q: float) -> float:
        """
        Calculate q-order fluctuation function.

        Args:
            variances: Segment variances
            q: Order parameter

        Returns:
            Fq value
        """
        # Filter out zero or negative variances
        valid_variances = variances[variances > 0]

        if len(valid_variances) == 0:
            return 0.0

        if abs(q) < 1e-10:  # q ≈ 0
            # Special case: q=0 uses geometric mean
            log_variances = np.log(valid_variances)
            Fq = np.exp(0.5 * np.mean(log_variances))
        else:
            # General case: Fq = [mean(variance^(q/2))]^(1/q)
            Fq = np.mean(valid_variances ** (q / 2.0)) ** (1.0 / q)

        return float(Fq)

    def generalized_hurst_exponent(
        self,
        scales: npt.NDArray[np.int32],
        Fq: npt.NDArray[np.float64]
    ) -> npt.NDArray[np.float64]:
        """
        Calculate generalized Hurst exponent h(q).

        h(q) is the slope of log(Fq) vs log(scale).

        Args:
            scales: Scale array
            Fq: Fluctuation functions (q x scales)

        Returns:
            h(q) array
        """
        log_scales = np.log10(scales)
        h_q = np.zeros(len(self.q_range))

        for q_idx in range(len(self.q_range)):
            log_Fq = np.log10(Fq[q_idx, :] + 1e-10)  # Avoid log(0)

            # Linear regression: log(Fq) = h(q) * log(scale) + const
            coeffs = np.polyfit(log_scales, log_Fq, 1)
            h_q[q_idx] = coeffs[0]

        return h_q

    def mass_exponent(self, h_q: npt.NDArray[np.float64]) -> npt.NDArray[np.float64]:
        """
        Calculate mass exponent τ(q).

        τ(q) = q * h(q) - 1

        Args:
            h_q: Generalized Hurst exponent

        Returns:
            τ(q) array
        """
        return self.q_range * h_q - 1.0

    def singularity_spectrum(
        self,
        tau_q: npt.NDArray[np.float64]
    ) -> Tuple[npt.NDArray[np.float64], npt.NDArray[np.float64]]:
        """
        Calculate singularity spectrum f(α) via Legendre transform.

        α(q) = dτ/dq
        f(α) = q * α - τ

        Args:
            tau_q: Mass exponent

        Returns:
            (alpha, f_alpha): Singularity spectrum
        """
        # Numerical derivative: α = dτ/dq
        alpha = np.gradient(tau_q, self.q_range)

        # Legendre transform: f(α) = q*α - τ
        f_alpha = self.q_range * alpha - tau_q

        return alpha, f_alpha

    def analyze(
        self,
        time_series: Union[npt.NDArray[np.float64], Any],
        **kwargs: Any
    ) -> Dict[str, Any]:
        """
        Perform complete MFDFA analysis.

        Args:
            time_series: Input time series
            **kwargs: Additional arguments for calculate()

        Returns:
            Dictionary with complete MFDFA results including:
            - q_values: Array of q values used
            - Fq: Fluctuation functions for each q and scale
            - h_q: Generalized Hurst exponent
            - tau_q: Mass exponent
            - alpha: Hölder exponent (singularity strength)
            - f_alpha: Singularity spectrum
            - delta_h: Width of h(q) (multifractality measure)
            - delta_alpha: Width of singularity spectrum
            - is_multifractal: Boolean indicating multifractality
        """
        # Calculate fluctuation functions
        scales, q_values, Fq = self.calculate(time_series, **kwargs)

        # Calculate generalized Hurst exponent h(q)
        h_q = self.generalized_hurst_exponent(scales, Fq)

        # Calculate mass exponent τ(q)
        tau_q = self.mass_exponent(h_q)

        # Calculate singularity spectrum f(α)
        alpha, f_alpha = self.singularity_spectrum(tau_q)

        # Multifractality measures
        delta_h = float(h_q[0] - h_q[-1])  # Width of h(q)
        delta_alpha = float(alpha.max() - alpha.min())  # Width of spectrum

        # Multifractality threshold (heuristic: Δh > 0.1 indicates multifractality)
        is_multifractal = delta_h > 0.1

        results = {
            'q_values': q_values,
            'scales': scales,
            'Fq': Fq,
            'h_q': h_q,
            'tau_q': tau_q,
            'alpha': alpha,
            'f_alpha': f_alpha,
            'delta_h': delta_h,
            'delta_alpha': delta_alpha,
            'is_multifractal': is_multifractal,
            'h_2': float(h_q[len(h_q)//2]),  # h(q=0) ≈ standard Hurst exponent
        }

        return results

    def plot_results(self, results: Dict[str, Any], show: bool = True) -> Any:
        """
        Create comprehensive MFDFA visualization.

        Args:
            results: Results dictionary from analyze()
            show: Whether to display the plot

        Returns:
            Matplotlib figure
        """
        try:
            import matplotlib.pyplot as plt
        except ImportError:
            raise ImportError(
                "Matplotlib is required for plotting. "
                "Install with: pip install matplotlib"
            )

        fig, axes = plt.subplots(2, 2, figsize=(14, 10))

        # 1. Generalized Hurst exponent h(q)
        ax = axes[0, 0]
        ax.plot(results['q_values'], results['h_q'], 'o-', linewidth=2, markersize=6)
        ax.axhline(y=0.5, color='r', linestyle='--', alpha=0.5, label='H=0.5 (white noise)')
        ax.set_xlabel('q', fontsize=11)
        ax.set_ylabel('h(q)', fontsize=11)
        ax.set_title('Generalized Hurst Exponent', fontsize=12, fontweight='bold')
        ax.grid(True, alpha=0.3)
        ax.legend()

        # 2. Mass exponent τ(q)
        ax = axes[0, 1]
        ax.plot(results['q_values'], results['tau_q'], 'o-', linewidth=2, markersize=6, color='green')
        ax.set_xlabel('q', fontsize=11)
        ax.set_ylabel('τ(q)', fontsize=11)
        ax.set_title('Mass Exponent', fontsize=12, fontweight='bold')
        ax.grid(True, alpha=0.3)

        # 3. Singularity spectrum f(α)
        ax = axes[1, 0]
        ax.plot(results['alpha'], results['f_alpha'], 'o-', linewidth=2, markersize=6, color='orange')
        ax.set_xlabel('α (Hölder exponent)', fontsize=11)
        ax.set_ylabel('f(α)', fontsize=11)
        ax.set_title(
            f"Singularity Spectrum (Δα={results['delta_alpha']:.3f})",
            fontsize=12,
            fontweight='bold'
        )
        ax.grid(True, alpha=0.3)

        # 4. Fluctuation functions Fq(s) for selected q values
        ax = axes[1, 1]
        q_indices = [0, len(results['q_values'])//2, len(results['q_values'])-1]
        for idx in q_indices:
            q_val = results['q_values'][idx]
            ax.loglog(
                results['scales'],
                results['Fq'][idx, :],
                'o-',
                label=f'q={q_val:.1f}',
                markersize=5
            )
        ax.set_xlabel('Scale', fontsize=11)
        ax.set_ylabel('Fq(s)', fontsize=11)
        ax.set_title('Fluctuation Functions', fontsize=12, fontweight='bold')
        ax.legend()
        ax.grid(True, alpha=0.3)

        # Overall title
        fig.suptitle(
            f"MFDFA Analysis - {'Multifractal' if results['is_multifractal'] else 'Monofractal'} "
            f"(Δh={results['delta_h']:.3f})",
            fontsize=14,
            fontweight='bold'
        )

        plt.tight_layout()

        if show:
            plt.show()

        return fig
