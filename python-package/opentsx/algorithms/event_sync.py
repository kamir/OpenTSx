"""Event Synchronization algorithm implementation."""

from typing import Union, Dict, Any, Optional, Tuple
import numpy as np
import numpy.typing as npt


class EventSynchronization:
    """
    Event Synchronization for detecting synchronized events across time series.

    Detects and quantifies synchronization between event sequences in time series,
    including directional information (which series leads/lags).

    Reference:
        Quiroga et al. (2002) "Event synchronization: A simple and fast method
        to measure synchronicity and time delay patterns"

    Examples:
        >>> from opentsx import TimeSeriesObject, EventSynchronization
        >>> ts1 = TimeSeriesObject(data=np.random.randn(1000))
        >>> ts2 = TimeSeriesObject(data=np.random.randn(1000))
        >>> es = EventSynchronization()
        >>> results = es.analyze(ts1, ts2)
        >>> print(f"Overall sync: {results['overall_sync']:.3f}")
        >>> print(f"Leader: {results['leader']}")
    """

    def __init__(self, tau_max: Optional[float] = None):
        """
        Initialize Event Synchronization.

        Args:
            tau_max: Maximum time lag to consider (auto-calculated if None)
        """
        self.tau_max = tau_max

    def detect_events(
        self,
        time_series: Union[npt.NDArray[np.float64], Any],
        threshold: Optional[float] = None,
        method: str = 'std'
    ) -> npt.NDArray[np.int32]:
        """
        Detect events (extreme values) in time series.

        Events are defined as local maxima exceeding a threshold.

        Args:
            time_series: Input time series
            threshold: Threshold for event detection (auto-calculated if None)
            method: Threshold method ('std', 'percentile', 'absolute')

        Returns:
            Array of event indices
        """
        # Extract data
        if hasattr(time_series, 'values'):
            data = time_series.values
        else:
            data = np.asarray(time_series, dtype=np.float64)

        # Determine threshold
        if threshold is None:
            if method == 'std':
                threshold = np.mean(data) + 2 * np.std(data)
            elif method == 'percentile':
                threshold = np.percentile(data, 95)
            elif method == 'absolute':
                threshold = 0.0  # Will be set by user
            else:
                raise ValueError(f"Unknown threshold method: {method}")

        # Find local maxima above threshold
        events = []
        for i in range(1, len(data) - 1):
            if data[i] > threshold and data[i] > data[i-1] and data[i] > data[i+1]:
                events.append(i)

        return np.array(events, dtype=np.int32)

    def calculate_synchronization(
        self,
        events_x: npt.NDArray[np.int32],
        events_y: npt.NDArray[np.int32],
        tau_max: Optional[float] = None
    ) -> Tuple[float, float, float]:
        """
        Calculate event synchronization between two event series.

        Computes directional synchronization measures:
        - Q: Overall synchronization
        - q_xy: Synchronization when x leads y
        - q_yx: Synchronization when y leads x

        Args:
            events_x: Event times/indices from first series
            events_y: Event times/indices from second series
            tau_max: Maximum time lag (uses instance default if None)

        Returns:
            (Q, q_xy, q_yx): Overall sync, x→y sync, y→x sync
        """
        # Use instance tau_max if not specified
        if tau_max is None:
            tau_max = self.tau_max

        # Calculate adaptive tau_max if still None
        if tau_max is None:
            tau_max = self._calculate_adaptive_tau(events_x, events_y)

        # Number of events
        m_x = len(events_x)
        m_y = len(events_y)

        if m_x == 0 or m_y == 0:
            return 0.0, 0.0, 0.0

        # Count synchronized events
        c_xy = self._count_synchronized(events_x, events_y, tau_max)  # x leads y
        c_yx = self._count_synchronized(events_y, events_x, tau_max)  # y leads x

        # Normalize
        normalization = np.sqrt(m_x * m_y)

        # Directional synchronization
        q_xy = c_xy / normalization
        q_yx = c_yx / normalization

        # Overall synchronization
        Q = (c_xy + c_yx) / normalization

        return float(Q), float(q_xy), float(q_yx)

    @staticmethod
    def _calculate_adaptive_tau(
        events_x: npt.NDArray[np.int32],
        events_y: npt.NDArray[np.int32]
    ) -> float:
        """
        Calculate adaptive time lag based on mean inter-event intervals.

        Args:
            events_x: Event times from first series
            events_y: Event times from second series

        Returns:
            Adaptive tau_max
        """
        if len(events_x) < 2 or len(events_y) < 2:
            return 1.0

        # Mean inter-event interval for each series
        tau_x = np.mean(np.diff(events_x))
        tau_y = np.mean(np.diff(events_y))

        # Return average
        return float(0.5 * (tau_x + tau_y))

    @staticmethod
    def _count_synchronized(
        events_lead: npt.NDArray[np.int32],
        events_lag: npt.NDArray[np.int32],
        tau_max: float
    ) -> int:
        """
        Count how many events in lead series have matching events in lag series.

        For each event in the leading series, checks if there's a corresponding
        event in the lagging series within the time window [t, t + tau_max].

        Args:
            events_lead: Leading event times
            events_lag: Lagging event times
            tau_max: Maximum time lag

        Returns:
            Count of synchronized events
        """
        count = 0

        for t_lead in events_lead:
            # Check if any lag event falls within [t_lead, t_lead + tau_max]
            synchronized = np.any(
                (events_lag >= t_lead) & (events_lag <= t_lead + tau_max)
            )
            if synchronized:
                count += 1

        return count

    def analyze(
        self,
        ts1: Union[npt.NDArray[np.float64], Any],
        ts2: Union[npt.NDArray[np.float64], Any],
        threshold1: Optional[float] = None,
        threshold2: Optional[float] = None,
        method: str = 'std'
    ) -> Dict[str, Any]:
        """
        Perform complete event synchronization analysis.

        Args:
            ts1: First time series
            ts2: Second time series
            threshold1: Event threshold for ts1
            threshold2: Event threshold for ts2
            method: Threshold method ('std', 'percentile')

        Returns:
            Dictionary with synchronization results including:
            - overall_sync (Q): Overall synchronization measure
            - sync_1_to_2 (q_12): Series 1 leading series 2
            - sync_2_to_1 (q_21): Series 2 leading series 1
            - num_events_1: Number of events in series 1
            - num_events_2: Number of events in series 2
            - events_1: Event indices for series 1
            - events_2: Event indices for series 2
            - leader: Which series leads ('Series 1', 'Series 2', or 'None')
            - lead_lag_strength: Magnitude of lead-lag relationship
            - tau_max: Time window used
        """
        # Detect events in both series
        events1 = self.detect_events(ts1, threshold=threshold1, method=method)
        events2 = self.detect_events(ts2, threshold=threshold2, method=method)

        # Calculate synchronization measures
        Q, q_12, q_21 = self.calculate_synchronization(events1, events2)

        # Determine lead-lag relationship
        if q_12 > q_21 + 0.05:  # Threshold to avoid noise
            leader = "Series 1"
            lag_strength = q_12 - q_21
        elif q_21 > q_12 + 0.05:
            leader = "Series 2"
            lag_strength = q_21 - q_12
        else:
            leader = "None (symmetric)"
            lag_strength = 0.0

        # Calculate tau_max used
        tau_max_used = self.tau_max
        if tau_max_used is None:
            tau_max_used = self._calculate_adaptive_tau(events1, events2)

        results = {
            'overall_sync': Q,
            'sync_1_to_2': q_12,
            'sync_2_to_1': q_21,
            'num_events_1': len(events1),
            'num_events_2': len(events2),
            'events_1': events1,
            'events_2': events2,
            'leader': leader,
            'lead_lag_strength': float(lag_strength),
            'tau_max': float(tau_max_used),
        }

        return results

    def plot_results(
        self,
        ts1: Union[npt.NDArray[np.float64], Any],
        ts2: Union[npt.NDArray[np.float64], Any],
        results: Dict[str, Any],
        show: bool = True
    ) -> Any:
        """
        Visualize event synchronization analysis.

        Args:
            ts1: First time series
            ts2: Second time series
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
        if hasattr(ts1, 'values'):
            data1, t1 = ts1.values, ts1.timestamps
            label1 = ts1.label or "Series 1"
        else:
            data1 = np.asarray(ts1)
            t1 = np.arange(len(data1))
            label1 = "Series 1"

        if hasattr(ts2, 'values'):
            data2, t2 = ts2.values, ts2.timestamps
            label2 = ts2.label or "Series 2"
        else:
            data2 = np.asarray(ts2)
            t2 = np.arange(len(data2))
            label2 = "Series 2"

        # Create figure
        fig, axes = plt.subplots(2, 1, figsize=(14, 8), sharex=True)

        # Plot series 1 with events
        ax = axes[0]
        ax.plot(t1, data1, linewidth=1, alpha=0.7, label=label1)

        # Mark events
        events_1 = results['events_1']
        if len(events_1) > 0:
            ax.scatter(
                t1[events_1], data1[events_1],
                color='red', s=100, marker='v',
                label=f"Events (n={results['num_events_1']})",
                zorder=5
            )

        ax.set_ylabel(label1, fontsize=11)
        ax.legend(loc='upper right')
        ax.grid(True, alpha=0.3)

        # Plot series 2 with events
        ax = axes[1]
        ax.plot(t2, data2, linewidth=1, alpha=0.7, color='green', label=label2)

        # Mark events
        events_2 = results['events_2']
        if len(events_2) > 0:
            ax.scatter(
                t2[events_2], data2[events_2],
                color='red', s=100, marker='v',
                label=f"Events (n={results['num_events_2']})",
                zorder=5
            )

        ax.set_ylabel(label2, fontsize=11)
        ax.set_xlabel('Time', fontsize=11)
        ax.legend(loc='upper right')
        ax.grid(True, alpha=0.3)

        # Overall title with results
        fig.suptitle(
            f"Event Synchronization Analysis\n"
            f"Q={results['overall_sync']:.3f} | "
            f"Leader: {results['leader']} | "
            f"Strength: {results['lead_lag_strength']:.3f} | "
            f"τ_max={results['tau_max']:.1f}",
            fontsize=13,
            fontweight='bold'
        )

        plt.tight_layout()

        if show:
            plt.show()

        return fig
