"""Mergeable Welford summaries of finite values; NaN is counted separately (same as Java Summaries)."""

from __future__ import annotations

import math
from typing import Iterable, Mapping


def summarize(values: Iterable[float]) -> dict:
    n = nan = 0
    mean = m2 = 0.0
    lo = hi = math.nan
    for v in values:
        v = float(v)
        if math.isnan(v):
            nan += 1
            continue
        n += 1
        delta = v - mean
        mean += delta / n
        m2 += delta * (v - mean)
        lo = v if n == 1 else min(lo, v)
        hi = v if n == 1 else max(hi, v)
    return {
        "validCount": n, "nanCount": nan, "min": lo, "max": hi,
        "mean": mean if n else math.nan, "m2": m2 if n else math.nan, "sax": None,
    }


def merge_summaries(a: Mapping, b: Mapping) -> dict:
    na, nb = a["validCount"], b["validCount"]
    nan = a["nanCount"] + b["nanCount"]
    if na == 0:
        return {**b, "nanCount": nan, "sax": None}
    if nb == 0:
        return {**a, "nanCount": nan, "sax": None}
    n = na + nb
    delta = b["mean"] - a["mean"]
    return {
        "validCount": n, "nanCount": nan,
        "min": min(a["min"], b["min"]), "max": max(a["max"], b["max"]),
        "mean": a["mean"] + delta * nb / n,
        "m2": a["m2"] + b["m2"] + delta * delta * (na * nb / n),
        "sax": None,
    }


def variance(summary: Mapping) -> float:
    n = summary["validCount"]
    return summary["m2"] / (n - 1) if n >= 2 else math.nan
