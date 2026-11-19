"""Multifractal Detrended Fluctuation Analysis (MFDFA) implementation."""

from typing import Optional, Tuple, Union, Dict, Any
import numpy as np
import numpy.typing as npt
from opentsx.algorithms._input import finite_values
from opentsx.algorithms.dfa import _exact_fit, _segment_variances, _series_and_scales


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
            q_range: Array of q values that holds 2 up to 1e-10, the q of h_2 (default: -10 to
                10.5 in steps of 0.5, 42 values with 0)

        Raises:
            ValueError: q_range has no value within 1e-10 of 2
        """
        self.polynom_order = polynom_order

        if q_range is None:
            self.q_range = np.concatenate([
                np.arange(-10, 0, 0.5),
                np.array([0.0]),
                np.arange(0.5, 11, 0.5)
            ])
        else:
            self.q_range = np.asarray(q_range, dtype=np.float64)
        if not np.any(np.abs(self.q_range - 2.0) < 1e-10):
            raise ValueError(
                "q_range: no value is 2; expected q values that include 2, the q of h_2"
            )

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

        Raises:
            ValueError: as DFA.calculate
        """
        data, scales = _series_and_scales(
            time_series, self.polynom_order, scales, min_scale, max_scale, num_scales
        )

        # Compute cumulative sum (integrated series)
        mean = np.mean(data)
        cumsum = np.cumsum(data - mean)
        rounding = 1e-10 * np.abs(cumsum).max()

        Fq = np.zeros((len(self.q_range), len(scales)))

        for s_idx, scale in enumerate(scales):
            variances = _segment_variances(cumsum, scale, self.polynom_order)
            variances = variances[variances > rounding ** 2]
            if len(variances) == 0:
                raise _exact_fit(scale)

            for q_idx, q in enumerate(self.q_range):
                Fq[q_idx, s_idx] = self._calculate_Fq(variances, q)

        return scales, self.q_range, Fq

    @staticmethod
    def _calculate_Fq(variances: npt.NDArray[np.float64], q: float) -> float:
        valid_variances = variances[variances > 0]

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

        Raises:
            ValueError: a fluctuation that is not positive
        """
        bad = np.argwhere(~(Fq > 0))
        if len(bad):
            q_idx, s_idx = bad[0]
            raise ValueError(
                f"Fq: F_q(s) is {Fq[q_idx, s_idx]} at q {self.q_range[q_idx]} and window size "
                f"{scales[s_idx]}; expected positive values"
            )
        log_scales = np.log10(scales)
        h_q = np.zeros(len(self.q_range))

        for q_idx in range(len(self.q_range)):
            log_Fq = np.log10(Fq[q_idx, :])

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
        surrogates: int = 19,
        seed: int = 1,
        **kwargs: Any
    ) -> Dict[str, Any]:
        """
        Perform complete MFDFA analysis.

        Args:
            time_series: Input time series
            surrogates: Number of phase-randomized copies that delta_h is compared with
            seed: Seed of the generator that draws the phases
            **kwargs: Additional arguments for calculate()

        Returns:
            Dictionary with complete MFDFA results including:
            - q_values: Array of q values used
            - Fq: Fluctuation functions for each q and scale
            - h_q: Generalized Hurst exponent
            - tau_q: Mass exponent
            - alpha: Hölder exponent (singularity strength)
            - f_alpha: Singularity spectrum
            - delta_h: Width of h(q), h at the first q minus h at the last q
            - delta_alpha: Width of singularity spectrum
            - delta_h_surrogates: Largest delta_h of the phase-randomized copies, which keep the
              power spectrum, and so the linear correlations, and have close to Gaussian values
            - is_multifractal: delta_h is above delta_h_surrogates. A Gaussian series with linear
              correlations passes in about 1 of surrogates + 1 cases; a multifractal cascade and
              uncorrelated values with a broad or skewed distribution pass
            - h_2: h(2), the Hurst exponent that DFA estimates

        Raises:
            ValueError: surrogates is below 1, or the input fails the checks of calculate
        """
        if surrogates < 1:
            raise ValueError(
                f"surrogates: {surrogates} is below 1; expected at least 1 phase-randomized copy"
            )
        data = finite_values(time_series, "time_series")
        scales, q_values, Fq = self.calculate(data, **kwargs)

        h_q = self.generalized_hurst_exponent(scales, Fq)

        tau_q = self.mass_exponent(h_q)

        alpha, f_alpha = self.singularity_spectrum(tau_q)

        delta_h = float(h_q[0] - h_q[-1])
        delta_alpha = float(alpha.max() - alpha.min())

        rng = np.random.default_rng(seed)
        coefficients = np.fft.rfft(data)
        delta_h_surrogates = -np.inf
        for _ in range(surrogates):
            phases = np.exp(2j * np.pi * rng.random(len(coefficients)))
            # c_0 and, for an even length, the coefficient at the Nyquist frequency are real
            phases[0] = 1
            if len(data) % 2 == 0:
                phases[-1] = 1
            copy = np.fft.irfft(coefficients * phases, len(data))
            h_copy = self.generalized_hurst_exponent(scales, self.calculate(copy, scales=scales)[2])
            delta_h_surrogates = max(delta_h_surrogates, float(h_copy[0] - h_copy[-1]))

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
            'delta_h_surrogates': delta_h_surrogates,
            'is_multifractal': delta_h > delta_h_surrogates,
            'h_2': float(h_q[np.flatnonzero(np.abs(q_values - 2.0) < 1e-10)[0]]),
        }

        return results

    def plot_results(self, results: Dict[str, Any], show: bool = True) -> Any:
        """
        Plot h(q), tau(q), the singularity spectrum and Fq(s) of the results.

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

        ax = axes[0, 0]
        ax.plot(results['q_values'], results['h_q'], 'o-', linewidth=2, markersize=6)
        ax.axhline(y=0.5, color='r', linestyle='--', alpha=0.5, label='H=0.5 (white noise)')
        ax.set_xlabel('q', fontsize=11)
        ax.set_ylabel('h(q)', fontsize=11)
        ax.set_title('Generalized Hurst Exponent', fontsize=12, fontweight='bold')
        ax.grid(True, alpha=0.3)
        ax.legend()

        ax = axes[0, 1]
        ax.plot(results['q_values'], results['tau_q'], 'o-', linewidth=2, markersize=6, color='green')
        ax.set_xlabel('q', fontsize=11)
        ax.set_ylabel('τ(q)', fontsize=11)
        ax.set_title('Mass Exponent', fontsize=12, fontweight='bold')
        ax.grid(True, alpha=0.3)

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
