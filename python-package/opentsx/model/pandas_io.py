"""Episodes <-> pandas with a timezone-aware UTC DatetimeIndex (microsecond resolution)."""

from __future__ import annotations

from typing import Iterable, Mapping, Optional

import numpy as np
import pandas as pd

from opentsx.model.episodes import irregular, regular
from opentsx.model.time_axis import timestamps_us


def episode_to_series(episode: Mapping) -> pd.Series:
    index = pd.to_datetime(timestamps_us(episode), unit="us", utc=True)
    return pd.Series(np.asarray(episode["values"], dtype=np.float64), index=index,
                     name=episode["series"]["seriesId"])


def episodes_to_frame(episodes: Iterable[Mapping]) -> pd.DataFrame:
    """Long format: series_id, metric, ts (UTC), value - the shape pandas and PySpark users expect."""
    frames = []
    for ep in episodes:
        s = episode_to_series(ep)
        frames.append(pd.DataFrame({
            "series_id": ep["series"]["seriesId"],
            "metric": ep["series"]["metric"],
            "episode_id": ep["episodeId"],
            "ts": s.index,
            "value": s.to_numpy(),
        }))
    if not frames:
        return pd.DataFrame(columns=["series_id", "metric", "episode_id", "ts", "value"])
    return pd.concat(frames, ignore_index=True)


def series_to_episode(s: pd.Series, series: Mapping, *, assume_utc: bool = False,
                      t_end_us: Optional[int] = None, **kwargs) -> dict:
    """pandas Series with DatetimeIndex -> episode. Equally spaced indexes become REGULAR episodes."""
    if not isinstance(s.index, pd.DatetimeIndex):
        raise TypeError("series must have a DatetimeIndex")
    idx = s.index
    if idx.tz is None:
        if not assume_utc:
            raise ValueError("naive DatetimeIndex; localize it or pass assume_utc=True")
        idx = idx.tz_localize("UTC")
    ts = idx.tz_convert("UTC").as_unit("us").asi8.astype(np.int64)
    values = s.to_numpy(dtype=np.float64)
    if len(ts) >= 2:
        steps = np.diff(ts)
        if np.all(steps == steps[0]) and steps[0] > 0:
            return regular(series, int(ts[0]), int(steps[0]), values, t_end_us=t_end_us, **kwargs)
    return irregular(series, ts.tolist(), values, t_end_us=t_end_us, **kwargs)
