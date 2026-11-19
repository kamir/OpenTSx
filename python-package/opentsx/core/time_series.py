"""TimeSeriesObject - Core abstraction for time series data."""

from __future__ import annotations
from typing import Optional, Union, List, Dict, Any, Callable
import numpy as np
import numpy.typing as npt


class TimeSeriesObject:
    """
    Core abstraction for univariate time series data.

    Provides a Pythonic wrapper around NumPy arrays with pandas-like API,
    statistical methods, transformations, and serialization.

    Examples:
        >>> # Create from list
        >>> ts = TimeSeriesObject(data=[1, 2, 3, 4, 5], label="example")
        >>>
        >>> # Statistical methods
        >>> print(ts.mean(), ts.std())
        >>>
        >>> # Transformations
        >>> normalized = ts.normalize(method='zscore')
        >>> detrended = ts.detrend(order=1)
        >>>
        >>> # pandas integration
        >>> series = ts.to_pandas()
    """

    def __init__(
        self,
        data: Union[List[float], npt.NDArray[np.float64]],
        timestamps: Optional[Union[List[float], npt.NDArray[np.float64]]] = None,
        label: str = "",
        metadata: Optional[Dict[str, Any]] = None,
    ):
        """
        Initialize TimeSeriesObject.

        Args:
            data: Time series values
            timestamps: Corresponding timestamps (default: sequential integers)
            label: Descriptive label for the series
            metadata: Additional metadata dictionary
        """
        self._values = np.asarray(data, dtype=np.float64)

        if timestamps is None:
            self._timestamps = np.arange(len(self._values), dtype=np.float64)
        else:
            self._timestamps = np.asarray(timestamps, dtype=np.float64)

        if len(self._values) != len(self._timestamps):
            raise ValueError(
                f"Length mismatch: {len(self._values)} values vs "
                f"{len(self._timestamps)} timestamps"
            )

        self.label = label
        self.metadata = metadata or {}

    # ==================== Properties ====================

    @property
    def values(self) -> npt.NDArray[np.float64]:
        """Get time series values."""
        return self._values

    @property
    def timestamps(self) -> npt.NDArray[np.float64]:
        """Get timestamps."""
        return self._timestamps

    @property
    def length(self) -> int:
        """Get length of time series."""
        return len(self._values)

    def __len__(self) -> int:
        """Length of time series."""
        return len(self._values)

    def __repr__(self) -> str:
        """String representation."""
        return (
            f"TimeSeriesObject(label='{self.label}', "
            f"length={len(self)}, "
            f"mean={self.mean():.3f}, "
            f"std={self.std():.3f})"
        )

    def __getitem__(self, key: Union[int, slice]) -> Union[float, TimeSeriesObject]:
        """Index access."""
        if isinstance(key, int):
            return float(self._values[key])
        elif isinstance(key, slice):
            return TimeSeriesObject(
                data=self._values[key],
                timestamps=self._timestamps[key],
                label=self.label,
                metadata=self.metadata.copy()
            )
        else:
            raise TypeError(f"key: {key!r} has type {type(key).__name__}, not one of int, slice")

    # ==================== Statistical Methods ====================

    def mean(self) -> float:
        """Calculate mean value."""
        return float(np.mean(self._values))

    def std(self) -> float:
        """Calculate standard deviation."""
        return float(np.std(self._values))

    def var(self) -> float:
        """Calculate variance."""
        return float(np.var(self._values))

    def min(self) -> float:
        """Get minimum value."""
        return float(np.min(self._values))

    def max(self) -> float:
        """Get maximum value."""
        return float(np.max(self._values))

    def median(self) -> float:
        """Calculate median value."""
        return float(np.median(self._values))

    def quantile(self, q: float) -> float:
        """
        Calculate quantile.

        Args:
            q: Quantile to compute (0 to 1)

        Returns:
            Quantile value
        """
        return float(np.quantile(self._values, q))

    def describe(self) -> Dict[str, float]:
        """
        Get summary statistics.

        Returns:
            Dictionary with statistical measures
        """
        return {
            'count': float(len(self)),
            'mean': self.mean(),
            'std': self.std(),
            'min': self.min(),
            '25%': self.quantile(0.25),
            '50%': self.median(),
            '75%': self.quantile(0.75),
            'max': self.max(),
        }

    # ==================== Transformation Methods ====================

    def normalize(self, method: str = 'zscore') -> TimeSeriesObject:
        """
        Normalize time series.

        Args:
            method: Normalization method ('zscore', 'minmax', 'robust')

        Returns:
            Normalized TimeSeriesObject

        Raises:
            ValueError: an unknown method, or a spread of 0 that the method divides by
        """
        if method == 'zscore':
            center, spread, name = self.mean(), self.std(), "standard deviation"
        elif method == 'minmax':
            center, spread, name = self.min(), self.max() - self.min(), "range"
        elif method == 'robust':
            center, spread = self.median(), self.quantile(0.75) - self.quantile(0.25)
            name = "interquartile range"
        else:
            raise ValueError(f"method: {method!r} is not one of zscore, minmax, robust")
        if spread == 0 or self.max() == self.min():
            raise ValueError(
                f"values: the {name} is 0, and method '{method}' divides by it; "
                f"expected a positive {name}"
            )
        normalized = (self._values - center) / spread

        return TimeSeriesObject(
            data=normalized,
            timestamps=self._timestamps.copy(),
            label=f"{self.label}_normalized",
            metadata={**self.metadata, 'normalization': method}
        )

    def detrend(self, order: int = 1) -> TimeSeriesObject:
        """
        Remove polynomial trend.

        Args:
            order: Polynomial order (1=linear, 2=quadratic, etc.)

        Returns:
            Detrended TimeSeriesObject
        """
        x = np.arange(len(self._values))
        coeffs = np.polyfit(x, self._values, order)
        trend = np.polyval(coeffs, x)
        detrended = self._values - trend

        return TimeSeriesObject(
            data=detrended,
            timestamps=self._timestamps.copy(),
            label=f"{self.label}_detrended",
            metadata={**self.metadata, 'detrend_order': order}
        )

    def diff(self, periods: int = 1) -> TimeSeriesObject:
        """
        Calculate the lag difference x[i] - x[i - periods].

        Args:
            periods: Lag in samples

        Returns:
            Differenced TimeSeriesObject, with the timestamps of x[i]

        Raises:
            ValueError: periods below 1
        """
        if periods < 1:
            raise ValueError(f"periods: {periods} is below 1; expected a lag of at least 1 sample")
        differenced = self._values[periods:] - self._values[:-periods]
        timestamps = self._timestamps[periods:]

        return TimeSeriesObject(
            data=differenced,
            timestamps=timestamps,
            label=f"{self.label}_diff{periods}",
            metadata={**self.metadata, 'diff_periods': periods}
        )

    def resample(self, factor: int, method: str = 'mean') -> TimeSeriesObject:
        """
        Resample time series by downsampling.

        Args:
            factor: Downsampling factor
            method: Aggregation method ('mean', 'median', 'max', 'min')

        Returns:
            Resampled TimeSeriesObject
        """
        n_new = len(self) // factor
        values_reshaped = self._values[:n_new * factor].reshape(n_new, factor)

        if method == 'mean':
            resampled = np.mean(values_reshaped, axis=1)
        elif method == 'median':
            resampled = np.median(values_reshaped, axis=1)
        elif method == 'max':
            resampled = np.max(values_reshaped, axis=1)
        elif method == 'min':
            resampled = np.min(values_reshaped, axis=1)
        else:
            raise ValueError(f"method: {method!r} is not one of mean, median, max, min")

        timestamps = self._timestamps[::factor][:n_new]

        return TimeSeriesObject(
            data=resampled,
            timestamps=timestamps,
            label=f"{self.label}_resampled",
            metadata={**self.metadata, 'resample_factor': factor, 'resample_method': method}
        )

    def apply(self, func: Callable[[npt.NDArray], npt.NDArray]) -> TimeSeriesObject:
        """
        Apply function to values.

        Args:
            func: Function to apply (must accept and return numpy array)

        Returns:
            Transformed TimeSeriesObject
        """
        transformed = func(self._values)

        return TimeSeriesObject(
            data=transformed,
            timestamps=self._timestamps.copy(),
            label=f"{self.label}_transformed",
            metadata=self.metadata.copy()
        )

    # ==================== Serialization ====================

    def to_dict(self) -> Dict[str, Any]:
        """
        Convert to dictionary.

        Returns:
            Dictionary representation
        """
        return {
            'label': self.label,
            'values': self._values.tolist(),
            'timestamps': self._timestamps.tolist(),
            'metadata': self.metadata,
        }

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> TimeSeriesObject:
        """
        Create from dictionary.

        Args:
            data: Dictionary with 'values', 'timestamps', 'label', 'metadata'

        Returns:
            TimeSeriesObject instance
        """
        return cls(
            data=data['values'],
            timestamps=data.get('timestamps'),
            label=data.get('label', ''),
            metadata=data.get('metadata', {})
        )

    def to_pandas(self):
        """
        Convert to pandas Series.

        Returns:
            pandas.Series
        """
        import pandas as pd
        return pd.Series(
            data=self._values,
            index=self._timestamps,
            name=self.label
        )

    @classmethod
    def from_pandas(cls, series, label: Optional[str] = None) -> TimeSeriesObject:
        """
        Create from pandas Series.

        A DatetimeIndex gives epoch microseconds; one without a time zone is read as UTC.

        Args:
            series: pandas.Series
            label: Optional label (uses series.name if not provided)

        Returns:
            TimeSeriesObject instance
        """
        import pandas as pd

        index = series.index
        if isinstance(index, pd.DatetimeIndex):
            if index.tz is not None:
                index = index.tz_convert(None)
            timestamps = index.as_unit("us").asi8
        else:
            timestamps = index.to_numpy()
        return cls(
            data=series.values,
            timestamps=timestamps,
            label=label or (series.name if series.name else ""),
        )

    def copy(self) -> TimeSeriesObject:
        """Create deep copy."""
        return TimeSeriesObject(
            data=self._values.copy(),
            timestamps=self._timestamps.copy(),
            label=self.label,
            metadata=self.metadata.copy()
        )
