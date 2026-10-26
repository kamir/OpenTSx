"""Event Synchronization algorithm implementation."""

from typing import Union, Dict, Any, Optional, Tuple
import numpy as np
import numpy.typing as npt


class Mulberry32:
    """
    Seeded generator of uniform numbers in [0, 1), bit for bit the generator of
    the web workbench (``mulberry32`` in opentsx-saas-frontend/src/workbench/random.ts),
    so a surrogate drawn here and one drawn in the browser are the same.
    """

    def __init__(self, seed: int):
        self._a = int(seed) & 0xFFFFFFFF

    def __call__(self) -> float:
        self._a = (self._a + 0x6D2B79F5) & 0xFFFFFFFF
        t = self._a
        t = ((t ^ (t >> 15)) * (t | 1)) & 0xFFFFFFFF
        t ^= (t + (((t ^ (t >> 7)) * (t | 61)) & 0xFFFFFFFF)) & 0xFFFFFFFF
        return ((t ^ (t >> 14)) & 0xFFFFFFFF) / 4294967296


def isi_shuffle(events: npt.NDArray[np.float64], rand: Mulberry32) -> npt.NDArray[np.float64]:
    """
    The event times with their inter-event intervals in random order (Fisher-Yates,
    from the last interval down). First and last event, event count and the set of
    intervals stay; only the order of the intervals changes.
    """
    x = np.asarray(events, dtype=np.float64)
    if len(x) < 3:
        return x.copy()
    gaps = list(np.diff(x))
    for i in range(len(gaps) - 1, 0, -1):
        j = int(rand() * (i + 1))
        gaps[i], gaps[j] = gaps[j], gaps[i]
    return x[0] + np.concatenate(([0.0], np.cumsum(gaps)))


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
            tau_max: Optional fixed cap for the event-pair specific window.
                If None, the purely local window of Quiroga et al. is used.
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

        Follows Quiroga, Kreuz & Grassberger (Phys. Rev. E 66, 041904, 2002).
        For every event pair (t_i of x, s_j of y) a *local* time window is used::

            tau_ij = min(t_{i+1} - t_i, t_i - t_{i-1},
                         s_{j+1} - s_j, s_j - s_{j-1}) / 2

        (missing neighbours at the series boundaries are ignored), optionally
        capped by a fixed ``tau_max``. A pair contributes J = 1 to c(y|x) if
        0 < s_j - t_i < tau_ij, J = 1 to c(x|y) if 0 < t_i - s_j < tau_ij, and
        J = 1/2 to both if t_i == s_j.

        The strict inequality guarantees that every event is matched with at
        most one event of the other series, hence

        - Q   = (c(y|x) + c(x|y)) / sqrt(m_x m_y)  in [0, 1]
        - q   = (c(y|x) - c(x|y)) / sqrt(m_x m_y)  in [-1, 1]

        Args:
            events_x: Event times/indices from first series
            events_y: Event times/indices from second series
            tau_max: Optional fixed upper bound for the local window
                (uses instance default if None; no cap if both are None)

        Returns:
            (Q, q_xy, q_yx): Overall sync, x→y sync (x leads, i.e.
            c(y|x)/sqrt(m_x m_y)), y→x sync (y leads). ``q_xy - q_yx`` is
            Quiroga's delay measure q.
        """
        if tau_max is None:
            tau_max = self.tau_max

        x = np.unique(np.asarray(events_x, dtype=np.float64))
        y = np.unique(np.asarray(events_y, dtype=np.float64))
        m_x = len(x)
        m_y = len(y)

        if m_x == 0 or m_y == 0:
            return 0.0, 0.0, 0.0

        c_yx, c_xy = self._count_pairs(x, y, tau_max)

        normalization = np.sqrt(m_x * m_y)
        q_xy = c_yx / normalization   # x leads y
        q_yx = c_xy / normalization   # y leads x
        Q = (c_yx + c_xy) / normalization

        return float(Q), float(q_xy), float(q_yx)

    def surrogate_sync(
        self,
        events_x: npt.NDArray[np.int32],
        events_y: npt.NDArray[np.int32],
        shuffles: int = 10,
        seed: int = 1,
        tau_max: Optional[float] = None
    ) -> float:
        """
        Q_rand, the baseline against chance: the mean Q of x against ``shuffles``
        copies of y whose inter-event intervals are shuffled (``isi_shuffle``).
        One ``Mulberry32(seed)`` draws all copies in turn, so the value is the
        same on every run and in the web workbench (``pair.es``).

        A Q close to Q_rand says the coincidences are what the event rates and
        intervals give by themselves.

        Args:
            events_x: Event times/indices from first series
            events_y: Event times/indices from second series
            shuffles: Number of shuffled copies
            seed: Seed of the generator
            tau_max: As in ``calculate_synchronization``

        Returns:
            Mean Q over the shuffled copies; 0 when a series has no events.
        """
        if shuffles < 1:
            raise ValueError("shuffles must be at least 1")
        y = np.unique(np.asarray(events_y, dtype=np.float64))
        rand = Mulberry32(seed)
        total = 0.0
        for _ in range(shuffles):
            total += self.calculate_synchronization(events_x, isi_shuffle(y, rand), tau_max)[0]
        return total / shuffles

    @staticmethod
    def _half_min_gap(events: npt.NDArray[np.float64]) -> npt.NDArray[np.float64]:
        """Half of the smaller distance to the neighbouring events (inf if none)."""
        gaps = np.diff(events)
        left = np.concatenate(([np.inf], gaps))
        right = np.concatenate((gaps, [np.inf]))
        return 0.5 * np.minimum(left, right)

    @classmethod
    def _count_pairs(
        cls,
        x: npt.NDArray[np.float64],
        y: npt.NDArray[np.float64],
        tau_max: Optional[float] = None
    ) -> Tuple[float, float]:
        """
        Count synchronized event pairs with event-pair specific windows.

        Args:
            x: Sorted, unique event times of the first series
            y: Sorted, unique event times of the second series
            tau_max: Optional fixed cap for the local window

        Returns:
            (c(y|x), c(x|y)): weighted counts of y-events shortly after
            x-events (x leads) and of x-events shortly after y-events (y leads)
        """
        half_x = cls._half_min_gap(x)
        half_y = cls._half_min_gap(y)

        # With the local window only the nearest y-event on each side of an
        # x-event can qualify, so it is enough to look at those two candidates.
        idx = np.searchsorted(y, x, side='left')
        c_yx = 0.0
        c_xy = 0.0
        for i, j_next in enumerate(idx):
            for j in (j_next - 1, j_next):
                if j < 0 or j >= len(y):
                    continue
                tau = min(half_x[i], half_y[j])
                if tau_max is not None:
                    tau = min(tau, float(tau_max))
                d = y[j] - x[i]
                if d == 0:
                    c_yx += 0.5
                    c_xy += 0.5
                elif 0 < d < tau:
                    c_yx += 1.0
                elif 0 < -d < tau:
                    c_xy += 1.0

        return c_yx, c_xy

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
            - delay (q): Quiroga's delay measure q_12 - q_21 in [-1, 1]
              (positive if series 1 leads)
            - tau_max: Fixed cap on the local window (inf if none was set)
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

        # Fixed cap on the local window (inf = purely local window)
        tau_max_used = np.inf if self.tau_max is None else self.tau_max

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
            'delay': float(q_12 - q_21),
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
