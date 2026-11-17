"""Input checks shared by the classic algorithms."""

from typing import Any

import numpy as np
import numpy.typing as npt


def finite_values(time_series: Any, name: str) -> npt.NDArray[np.float64]:
    """Values of an array or TimeSeriesObject; ValueError naming ``name`` unless all are finite."""
    values = time_series.values if hasattr(time_series, "values") else time_series
    data = np.asarray(values, dtype=np.float64)
    if data.size == 0:
        raise ValueError(f"{name}: no values; expected a non-empty series")
    bad = np.flatnonzero(~np.isfinite(data))
    if len(bad):
        raise ValueError(
            f"{name}: {len(bad)} of {len(data)} values are not finite, the first at index "
            f"{bad[0]} is {data[bad[0]]}; expected finite values only, drop or fill the gaps first"
        )
    return data
