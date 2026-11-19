"""Return Interval Statistics (RIS) implementation."""

from typing import Union, Dict, Any, Optional, List, Tuple
import numpy as np
import numpy.typing as npt

from opentsx.algorithms._input import finite_values, plot_input


class RIS:
    """
    Return Interval Statistics of extreme events.

    Analyzes the distribution of the time intervals between extreme events. The ratio
    R = std / mean of the intervals measures how the events cluster.

    References:
        - Bunde et al. (2005) "Long-term memory: A natural mechanism for the clustering of extreme events"
        - Altmann & Kantz (2005) "Recurrence time analysis"

    Examples:
        >>> from opentsx import TimeSeriesObject, RIS
        >>> ts = TimeSeriesObject(data=np.random.randn(10000))
        >>> ris = RIS(threshold_percentile=95)
        >>> results = ris.analyze(ts)
        >>> print(f"Mean return interval: {results['mean_ri']:.2f}")
        >>> print(f"Risk parameter: {results['risk_parameter']:.3f}")
    """

    def __init__(
        self,
        threshold: Optional[float] = None,
        threshold_percentile: float = 95.0
    ):
        """
        Initialize RIS.

        Args:
            threshold: Absolute threshold for extreme events (overrides percentile)
            threshold_percentile: Percentile threshold (default: 95th percentile)
        """
        self.threshold = threshold
        self.threshold_percentile = threshold_percentile

    def detect_extreme_events(
        self,
        time_series: Union[npt.NDArray[np.float64], Any]
    ) -> Tuple[npt.NDArray[np.int32], float]:
        """
        Detect extreme events exceeding threshold.

        Args:
            time_series: Input time series

        Returns:
            (event_indices, threshold_used): Indices of extreme events and threshold value

        Raises:
            ValueError: an empty series or a value that is not finite
        """
        data = finite_values(time_series, "time_series")

        if self.threshold is not None:
            threshold_value = self.threshold
        else:
            threshold_value = float(np.percentile(data, self.threshold_percentile))

        event_indices = np.where(data > threshold_value)[0].astype(np.int32)

        return event_indices, threshold_value

    def calculate_return_intervals(
        self,
        event_indices: npt.NDArray[np.int32]
    ) -> npt.NDArray[np.int32]:
        """
        Calculate time intervals between consecutive extreme events.

        Args:
            event_indices: Indices of extreme events

        Returns:
            Array of return intervals
        """
        if len(event_indices) < 2:
            return np.array([], dtype=np.int32)

        return_intervals = np.diff(event_indices)

        return return_intervals

    def risk_parameter(
        self,
        return_intervals: npt.NDArray[np.int32]
    ) -> float:
        """
        Calculate risk parameter R.

        R = σ / μ where σ is std dev and μ is mean of return intervals.
        Geometric intervals of uncorrelated values with exceedance probability p give
        R = sqrt(1 - p); clustered events give more, regular events less.

        Args:
            return_intervals: Array of return intervals

        Returns:
            Risk parameter R

        Raises:
            ValueError: fewer than 2 return intervals, or a mean that is not positive
        """
        if len(return_intervals) < 2:
            raise ValueError(
                f"return_intervals: {len(return_intervals)} given; expected at least 2 intervals"
            )

        mean_ri = np.mean(return_intervals)
        std_ri = np.std(return_intervals)

        if not mean_ri > 0:
            raise ValueError(f"return_intervals: mean is {mean_ri:g}; expected positive intervals")

        R = std_ri / mean_ri

        return float(R)

    def stretched_exponential_fit(
        self,
        return_intervals: npt.NDArray[np.int32]
    ) -> Dict[str, float]:
        """
        Maximum-likelihood fit of the stretched exponential density

        P(τ) = γ / (τ_0 Γ(1/γ)) exp[-(τ/τ_0)^γ]

        to the return intervals. For a given γ the likelihood is largest at
        τ_0 = (γ mean(τ^γ))^(1/γ), so only γ is searched, from 0.05 to 20.

        Args:
            return_intervals: Array of return intervals

        Returns:
            Dictionary with fit parameters:
            - gamma: Stretching exponent. Uncorrelated values give about 1, an exponential;
              long-term correlated values give γ < 1, the correlation exponent (Bunde et al. 2005)
            - tau_0: Characteristic time scale

        Raises:
            ValueError: fewer than 10 intervals, an interval that is not positive and finite, all
                intervals equal, or the largest likelihood at a limit of the search
        """
        from scipy import optimize, special

        r = np.asarray(return_intervals, dtype=np.float64)
        if len(r) < 10:
            raise ValueError(f"return_intervals: {len(r)} given; expected at least 10 intervals")
        bad = np.flatnonzero(~(np.isfinite(r) & (r > 0)))
        if len(bad):
            raise ValueError(
                f"return_intervals: interval {r[bad[0]]:g} at index {bad[0]} is not a positive "
                "finite number; expected positive finite intervals"
            )
        if r.min() == r.max():
            raise ValueError(
                f"return_intervals: all {len(r)} intervals are {r[0]:g}; "
                "expected intervals that vary, a stretched exponential has no fit to a constant"
            )
        mean = r.mean()
        x = r / mean

        def tau_0(gamma: float) -> float:
            return float((gamma * np.mean(x ** gamma)) ** (1.0 / gamma))

        def negative_log_likelihood(log_gamma: float) -> float:
            gamma = np.exp(log_gamma)
            t = tau_0(gamma)
            log_norm = np.log(t) + special.gammaln(1.0 / gamma) - log_gamma
            return len(x) * log_norm + np.sum((x / t) ** gamma)

        lo, hi = np.log(0.05), np.log(20.0)
        best = optimize.minimize_scalar(
            negative_log_likelihood, bounds=(lo, hi), method="bounded", options={"xatol": 1e-8}
        )
        if not best.success or min(best.x - lo, hi - best.x) < 1e-4:
            raise ValueError(
                f"return_intervals: the likelihood is largest at gamma {np.exp(best.x):.3g}, "
                "a limit of the search; expected intervals that a stretched exponential with "
                "0.05 < gamma < 20 describes"
            )
        gamma = float(np.exp(best.x))
        return {'gamma': gamma, 'tau_0': tau_0(gamma) * float(mean)}

    def survival_function(
        self,
        return_intervals: npt.NDArray[np.int32]
    ) -> Tuple[npt.NDArray[np.int32], npt.NDArray[np.float64]]:
        """
        Calculate empirical survival function S(τ).

        S(τ) = P(return interval ≥ τ)

        Args:
            return_intervals: Array of return intervals

        Returns:
            (tau_values, survival_prob): Return interval values and survival probabilities
        """
        if len(return_intervals) == 0:
            return np.array([]), np.array([])

        sorted_ri = np.sort(return_intervals)

        unique_ri = np.unique(sorted_ri)

        n_total = len(sorted_ri)
        survival_prob = np.zeros(len(unique_ri))

        for i, tau in enumerate(unique_ri):
            n_geq_tau = np.sum(sorted_ri >= tau)
            survival_prob[i] = n_geq_tau / n_total

        return unique_ri, survival_prob

    def analyze(
        self,
        time_series: Union[npt.NDArray[np.float64], Any]
    ) -> Dict[str, Any]:
        """
        Perform complete RIS analysis.

        Args:
            time_series: Input time series

        Returns:
            Dictionary with RIS results including:
            - num_events: Number of extreme events detected
            - threshold: Threshold value used
            - return_intervals: Array of return intervals
            - mean_ri: Mean return interval
            - median_ri: Median return interval
            - std_ri: Standard deviation of return intervals
            - min_ri: Minimum return interval
            - max_ri: Maximum return interval
            - risk_parameter: Risk parameter R (σ/μ)
            - event_indices: Indices of the extreme events

        Raises:
            ValueError: a value that is not finite, or fewer than 3 events above the threshold
        """
        event_indices, threshold_value = self.detect_extreme_events(time_series)

        return_intervals = self.calculate_return_intervals(event_indices)
        if len(return_intervals) < 2:
            raise ValueError(
                f"time_series: {len(event_indices)} values lie above the threshold "
                f"{threshold_value:g}; expected at least 3 events for 2 return intervals, "
                "lower the threshold or pass a longer series"
            )

        mean_ri = float(np.mean(return_intervals))
        median_ri = float(np.median(return_intervals))
        std_ri = float(np.std(return_intervals))
        min_ri = float(np.min(return_intervals))
        max_ri = float(np.max(return_intervals))

        R = self.risk_parameter(return_intervals)

        results = {
            'num_events': len(event_indices),
            'threshold': threshold_value,
            'return_intervals': return_intervals,
            'mean_ri': mean_ri,
            'median_ri': median_ri,
            'std_ri': std_ri,
            'min_ri': min_ri,
            'max_ri': max_ri,
            'risk_parameter': R,
            'event_indices': event_indices,
        }

        return results

    def plot_results(
        self,
        time_series: Union[npt.NDArray[np.float64], Any],
        results: Dict[str, Any],
        show: bool = True
    ) -> Any:
        """
        Visualize RIS analysis.

        Args:
            time_series: Input time series
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

        data, timestamps, label = plot_input(time_series, "Time Series")

        fig, axes = plt.subplots(2, 2, figsize=(14, 10))

        ax = axes[0, 0]
        ax.plot(timestamps, data, linewidth=1, alpha=0.7)
        ax.axhline(
            y=results['threshold'],
            color='r',
            linestyle='--',
            label=f"Threshold ({results['threshold']:.2f})"
        )

        event_indices = results['event_indices']
        if len(event_indices) > 0:
            ax.scatter(
                timestamps[event_indices],
                data[event_indices],
                color='red',
                s=50,
                marker='o',
                label=f"Extreme Events (n={results['num_events']})",
                zorder=5
            )

        ax.set_xlabel('Time', fontsize=11)
        ax.set_ylabel('Value', fontsize=11)
        ax.set_title(f'{label} with Extreme Events', fontsize=12, fontweight='bold')
        ax.legend()
        ax.grid(True, alpha=0.3)

        ax = axes[0, 1]
        if len(results['return_intervals']) > 0:
            ax.hist(results['return_intervals'], bins='auto', density=True, alpha=0.7, edgecolor='black')
            ax.axvline(
                results['mean_ri'],
                color='r',
                linestyle='--',
                linewidth=2,
                label=f"Mean = {results['mean_ri']:.1f}"
            )
            ax.axvline(
                results['median_ri'],
                color='g',
                linestyle='--',
                linewidth=2,
                label=f"Median = {results['median_ri']:.1f}"
            )

        ax.set_xlabel('Return Interval (τ)', fontsize=11)
        ax.set_ylabel('Probability Density', fontsize=11)
        ax.set_title('Return Interval Distribution', fontsize=12, fontweight='bold')
        ax.legend()
        ax.grid(True, alpha=0.3)

        ax = axes[1, 0]
        if len(results['return_intervals']) > 0:
            tau_vals, surv_prob = self.survival_function(results['return_intervals'])
            ax.semilogy(tau_vals, surv_prob, 'o-', markersize=4)

        ax.set_xlabel('Return Interval (τ)', fontsize=11)
        ax.set_ylabel('Survival Function S(τ)', fontsize=11)
        ax.set_title('Survival Function (Log Scale)', fontsize=12, fontweight='bold')
        ax.grid(True, alpha=0.3)

        ax = axes[1, 1]
        ax.axis('off')

        stats_text = f"""
        Risk Analysis Summary
        {'='*40}

        Number of Events: {results['num_events']}
        Threshold: {results['threshold']:.3f}

        Return Interval Statistics:
        - Mean:   {results['mean_ri']:.2f}
        - Median: {results['median_ri']:.2f}
        - Std:    {results['std_ri']:.2f}
        - Min:    {results['min_ri']:.0f}
        - Max:    {results['max_ri']:.0f}

        Risk Parameter R = σ/μ:
        R = {results['risk_parameter']:.3f}
        """

        ax.text(
            0.1, 0.5, stats_text,
            fontsize=10,
            verticalalignment='center',
            fontfamily='monospace',
            bbox=dict(boxstyle='round', facecolor='wheat', alpha=0.3)
        )

        fig.suptitle(
            f"Return Interval Statistics Analysis\n"
            f"R={results['risk_parameter']:.3f}",
            fontsize=14,
            fontweight='bold'
        )

        plt.tight_layout()

        if show:
            plt.show()

        return fig
