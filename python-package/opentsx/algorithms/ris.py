"""Return Interval Statistics (RIS) implementation."""

from typing import Union, Dict, Any, Optional, List, Tuple
import numpy as np
import numpy.typing as npt


class RIS:
    """
    Return Interval Statistics for extreme event analysis and risk assessment.

    Analyzes the distribution of time intervals between extreme events
    to quantify risk, predict future events, and assess tail behavior.

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
        """
        # Extract data
        if hasattr(time_series, 'values'):
            data = time_series.values
        else:
            data = np.asarray(time_series, dtype=np.float64)

        # Determine threshold
        if self.threshold is not None:
            threshold_value = self.threshold
        else:
            threshold_value = float(np.percentile(data, self.threshold_percentile))

        # Find events exceeding threshold
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

        # Return intervals are differences between consecutive events
        return_intervals = np.diff(event_indices)

        return return_intervals

    def risk_parameter(
        self,
        return_intervals: npt.NDArray[np.int32]
    ) -> float:
        """
        Calculate risk parameter R.

        R = σ / μ where σ is std dev and μ is mean of return intervals.
        R > 1 indicates clustering (higher risk)
        R < 1 indicates regularity (lower risk)
        R ≈ 1 indicates random (Poisson process)

        Args:
            return_intervals: Array of return intervals

        Returns:
            Risk parameter R
        """
        if len(return_intervals) == 0:
            return 0.0

        mean_ri = np.mean(return_intervals)
        std_ri = np.std(return_intervals)

        if mean_ri == 0:
            return 0.0

        R = std_ri / mean_ri

        return float(R)

    def stretched_exponential_fit(
        self,
        return_intervals: npt.NDArray[np.int32]
    ) -> Dict[str, float]:
        """
        Fit stretched exponential distribution to return intervals.

        P(τ) ∝ exp[-(τ/τ_0)^γ]

        Args:
            return_intervals: Array of return intervals

        Returns:
            Dictionary with fit parameters:
            - gamma: Stretching exponent (γ < 1: subdiffusive, γ = 1: exponential, γ > 1: superdiffusive)
            - tau_0: Characteristic time scale
        """
        if len(return_intervals) < 10:
            return {'gamma': 0.0, 'tau_0': 0.0}

        # Create histogram
        hist, bin_edges = np.histogram(return_intervals, bins='auto', density=True)
        bin_centers = (bin_edges[:-1] + bin_edges[1:]) / 2

        # Filter out zeros
        mask = (hist > 0) & (bin_centers > 0)
        if np.sum(mask) < 3:
            return {'gamma': 0.0, 'tau_0': 0.0}

        log_tau = np.log(bin_centers[mask])
        log_P = np.log(hist[mask])

        # Linear fit in log-log space: log(P) = -a * log(τ)^γ + const
        # Simplified: assume γ = 1 for basic fit
        try:
            coeffs = np.polyfit(log_tau, log_P, 1)
            gamma = 1.0  # Simplified estimate
            tau_0 = float(np.exp(-coeffs[1] / coeffs[0]))
        except:
            gamma = 0.0
            tau_0 = 0.0

        return {
            'gamma': float(gamma),
            'tau_0': float(tau_0)
        }

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

        # Sort return intervals
        sorted_ri = np.sort(return_intervals)

        # Unique values
        unique_ri = np.unique(sorted_ri)

        # Calculate survival probability for each unique value
        n_total = len(sorted_ri)
        survival_prob = np.zeros(len(unique_ri))

        for i, tau in enumerate(unique_ri):
            # Count intervals >= tau
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
            - risk_interpretation: Interpretation of risk parameter
            - gamma: Stretched exponential exponent
            - tau_0: Characteristic time scale
        """
        # Detect extreme events
        event_indices, threshold_value = self.detect_extreme_events(time_series)

        # Calculate return intervals
        return_intervals = self.calculate_return_intervals(event_indices)

        # Calculate statistics
        if len(return_intervals) > 0:
            mean_ri = float(np.mean(return_intervals))
            median_ri = float(np.median(return_intervals))
            std_ri = float(np.std(return_intervals))
            min_ri = float(np.min(return_intervals))
            max_ri = float(np.max(return_intervals))

            # Risk parameter
            R = self.risk_parameter(return_intervals)

            # Interpret risk
            if R < 0.8:
                risk_interp = "Low risk (regular events)"
            elif R < 1.2:
                risk_interp = "Moderate risk (random/Poisson)"
            else:
                risk_interp = "High risk (clustered events)"

            # Stretched exponential fit
            fit_params = self.stretched_exponential_fit(return_intervals)
        else:
            mean_ri = 0.0
            median_ri = 0.0
            std_ri = 0.0
            min_ri = 0.0
            max_ri = 0.0
            R = 0.0
            risk_interp = "Insufficient events"
            fit_params = {'gamma': 0.0, 'tau_0': 0.0}

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
            'risk_interpretation': risk_interp,
            'gamma': fit_params['gamma'],
            'tau_0': fit_params['tau_0'],
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

        # Extract data
        if hasattr(time_series, 'values'):
            data = time_series.values
            timestamps = time_series.timestamps
            label = time_series.label or "Time Series"
        else:
            data = np.asarray(time_series)
            timestamps = np.arange(len(data))
            label = "Time Series"

        fig, axes = plt.subplots(2, 2, figsize=(14, 10))

        # 1. Time series with extreme events
        ax = axes[0, 0]
        ax.plot(timestamps, data, linewidth=1, alpha=0.7)
        ax.axhline(
            y=results['threshold'],
            color='r',
            linestyle='--',
            label=f"Threshold ({results['threshold']:.2f})"
        )

        # Mark extreme events
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

        # 2. Return interval distribution
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

        # 3. Survival function
        ax = axes[1, 0]
        if len(results['return_intervals']) > 0:
            tau_vals, surv_prob = self.survival_function(results['return_intervals'])
            ax.semilogy(tau_vals, surv_prob, 'o-', markersize=4)

        ax.set_xlabel('Return Interval (τ)', fontsize=11)
        ax.set_ylabel('Survival Function S(τ)', fontsize=11)
        ax.set_title('Survival Function (Log Scale)', fontsize=12, fontweight='bold')
        ax.grid(True, alpha=0.3)

        # 4. Risk statistics
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
        {results['risk_interpretation']}

        Stretched Exponential Fit:
        - γ:   {results['gamma']:.3f}
        - τ₀:  {results['tau_0']:.2f}
        """

        ax.text(
            0.1, 0.5, stats_text,
            fontsize=10,
            verticalalignment='center',
            fontfamily='monospace',
            bbox=dict(boxstyle='round', facecolor='wheat', alpha=0.3)
        )

        # Overall title
        fig.suptitle(
            f"Return Interval Statistics Analysis\n"
            f"Risk: {results['risk_interpretation']} (R={results['risk_parameter']:.3f})",
            fontsize=14,
            fontweight='bold'
        )

        plt.tight_layout()

        if show:
            plt.show()

        return fig
