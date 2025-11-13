"""TSBucket - Container for multiple time series."""

from typing import List, Optional, Callable, Any, Dict
from opentsx.core.time_series import TimeSeriesObject


class TSBucket:
    """Container for multiple TimeSeriesObject instances."""

    def __init__(self, time_series: Optional[List[TimeSeriesObject]] = None, label: str = ""):
        """
        Initialize TSBucket.

        Args:
            time_series: List of TimeSeriesObject instances
            label: Label for this bucket
        """
        self._series: List[TimeSeriesObject] = time_series or []
        self.label = label
        self.metadata: Dict[str, Any] = {}

    def add(self, ts: TimeSeriesObject) -> None:
        """Add time series to bucket."""
        self._series.append(ts)

    def __len__(self) -> int:
        """Number of series in bucket."""
        return len(self._series)

    def __getitem__(self, key: int) -> TimeSeriesObject:
        """Get series by index."""
        return self._series[key]

    def __iter__(self):
        """Iterate over series."""
        return iter(self._series)

    def apply(self, func: Callable, parallel: bool = False) -> "TSBucket":
        """
        Apply function to all time series.

        Args:
            func: Function to apply to each TimeSeriesObject
            parallel: Whether to use parallel processing

        Returns:
            New TSBucket with transformed series
        """
        if parallel:
            # Would use multiprocessing/dask in full implementation
            transformed = [func(ts) for ts in self._series]
        else:
            transformed = [func(ts) for ts in self._series]

        return TSBucket(time_series=transformed, label=f"{self.label}_transformed")

    def filter(self, func: Callable) -> "TSBucket":
        """
        Filter time series based on condition.

        Args:
            func: Function returning bool for each TimeSeriesObject

        Returns:
            New TSBucket with filtered series
        """
        filtered = [ts for ts in self._series if func(ts)]
        return TSBucket(time_series=filtered, label=f"{self.label}_filtered")
