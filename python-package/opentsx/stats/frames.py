"""pandas helpers for ``SeriesStats`` records (as decoded by :func:`opentsx.model.decode`)."""
from __future__ import annotations

import math
from typing import Iterable, Mapping, Sequence

import pandas as pd

from .sketch import QuantileSketch


def stats_to_frame(records: Iterable[Mapping], quantiles: Sequence[float] = (0.05, 0.25, 0.5, 0.75, 0.95)) -> pd.DataFrame:
    """One row per statistics record: counts, min/max/mean/std, gaps and quantiles from the sketch.

    Windows are ``[window_start, window_end)`` in UTC. Duplicate keys (KafScale has no compaction) are reduced to the
    record with the most values per ``(series_id, window_type, window_start)``.
    """
    rows = []
    for r in records:
        n = r["validCount"]
        row = {
            "series_id": r["seriesId"],
            "window_type": r["windowType"],
            "window_start": pd.Timestamp(r["windowStart"]),
            "window_end": pd.Timestamp(r["windowEnd"]),
            "valid_count": n,
            "nan_count": r.get("nanCount", 0),
            "min": r["min"],
            "max": r["max"],
            "mean": r["mean"],
            "std": math.sqrt(r["m2"] / (n - 1)) if n > 1 else math.nan,
            "gap_count": r.get("gapCount", 0),
            "last_seen": pd.Timestamp(r["lastSeen"]) if r.get("lastSeen") is not None else pd.NaT,
        }
        sketch = r.get("quantileSketch")
        q = QuantileSketch.from_bytes(sketch) if sketch else None
        for p in quantiles:
            row[f"q{round(p * 100):02d}"] = q.quantile(p) if q is not None else math.nan
        rows.append(row)
    df = pd.DataFrame(rows)
    if df.empty:
        return df
    df["_total"] = df["valid_count"] + df["nan_count"]
    df = (df.sort_values("_total").groupby(["series_id", "window_type", "window_start"], as_index=False).last()
          .drop(columns="_total").sort_values(["series_id", "window_type", "window_start"]).reset_index(drop=True))
    return df
