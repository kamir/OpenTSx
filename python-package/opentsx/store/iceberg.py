"""Read OpenTSx episodes from Iceberg tables written by ``opentsx-store-iceberg`` (Java) with PyIceberg.

    from opentsx.store.iceberg import load_catalog, read_episodes, read_frame
    catalog = load_catalog(type="rest", uri="http://iceberg-rest:8181", warehouse="s3://lake/")
    df = read_frame(catalog, metric="wind.turbine.wind_speed", tags={"park": "ber-01"},
                    start="2026-10-01T00:00:00Z", end="2026-10-02T00:00:00Z")

Episodes come back as v2 dicts (same shape as ``opentsx.model``), so they encode to the same bytes as in Java.
"""

from __future__ import annotations

from datetime import datetime, timezone
from typing import Iterable, List, Mapping, Optional, Union

from opentsx.model.series_key import sorted_by_utf8
from opentsx.model.time_axis import to_us

try:
    from pyiceberg.catalog import Catalog
    from pyiceberg.catalog import load_catalog as _load_catalog
    from pyiceberg.expressions import And, EqualTo, GreaterThan, In, LessThan
except ImportError as e:  # pragma: no cover - optional dependency
    raise ImportError("install the iceberg extra: pip install 'opentsx[iceberg]'") from e

Instant = Union[int, str, datetime]


def load_catalog(name: str = "opentsx", **properties) -> Catalog:
    """PyIceberg catalog; use the same name as the Java side (default ``opentsx``) for SQL catalogs."""
    return _load_catalog(name, **properties)


def _micros(t: Instant) -> int:
    if isinstance(t, str):
        t = datetime.fromisoformat(t.replace("Z", "+00:00"))
    return to_us(t)


def _iso(us: int) -> str:
    return datetime.fromtimestamp(us / 1_000_000, tz=timezone.utc).isoformat()


def _as_dict(m) -> dict:
    if m is None:
        return {}
    return sorted_by_utf8(dict(m))


def _row_to_episode(r: Mapping) -> dict:
    seg = None
    if r["seg_strategy"] is not None:
        seg = {
            "strategy": r["seg_strategy"],
            "params": _as_dict(r["seg_params"]),
            "anchorTs": None if r["seg_anchor_ts"] is None else to_us(r["seg_anchor_ts"]),
            "sourceEpisodeId": r["seg_source_episode_id"],
        }
    return {
        "episodeId": r["episode_id"],
        "series": {"metric": r["metric"], "tags": _as_dict(r["tags"]), "unit": r["unit"], "seriesId": r["series_id"]},
        "tStart": to_us(r["t_start"]),
        "tEnd": to_us(r["t_end"]),
        "count": r["count"],
        "timeEncoding": r["time_encoding"],
        "intervalMicros": r["interval_us"],
        "timeDeltas": r["time_deltas"],
        "values": list(r["values"]),
        "quality": r["quality"],
        "summary": {
            "validCount": r["s_valid_count"], "nanCount": r["s_nan_count"], "min": r["s_min"], "max": r["s_max"],
            "mean": r["s_mean"], "m2": r["s_m2"], "sax": r["s_sax"],
        },
        "segmentation": seg,
        "labels": _as_dict(r["labels"]),
        "bucketId": r["bucket_id"],
        "revision": r["revision"],
        "provenance": {
            "source": r["source"], "producer": r["producer"], "createdAt": to_us(r["created_at"]),
            "pipeline": r["pipeline"], "parentEpisodeId": r["parent_episode_id"],
        },
    }


def read_episodes(catalog: Catalog, namespace: str = "tsx", *, metric: Optional[str] = None,
                  series_ids: Optional[Iterable[str]] = None, tags: Optional[Mapping[str, str]] = None,
                  start: Optional[Instant] = None, end: Optional[Instant] = None) -> List[dict]:
    """Episodes overlapping [start, end), ordered like the Java store (tStart, seriesId, episodeId)."""
    table = catalog.load_table(f"{namespace}.episodes")
    expr = None

    def add(e):
        nonlocal expr
        expr = e if expr is None else And(expr, e)

    if end is not None:
        add(LessThan("t_start", _iso(_micros(end))))
    if start is not None:
        add(GreaterThan("t_end", _iso(_micros(start))))
    if metric is not None:
        add(EqualTo("metric", metric))
    if series_ids is not None:
        add(In("series_id", set(series_ids)))
    scan = table.scan(row_filter=expr) if expr is not None else table.scan()
    rows = scan.to_arrow().to_pylist()
    episodes = {}
    for r in rows:
        ep = _row_to_episode(r)
        if tags and any(ep["series"]["tags"].get(k) != v for k, v in tags.items()):
            continue
        old = episodes.get(ep["episodeId"])
        if old is None or ep["revision"] > old["revision"]:
            episodes[ep["episodeId"]] = ep
    return sorted(episodes.values(), key=lambda e: (e["tStart"], e["series"]["seriesId"], e["episodeId"]))


def read_frame(catalog: Catalog, namespace: str = "tsx", **filters):
    """Long-format pandas DataFrame (series_id, metric, episode_id, ts UTC, value) of the selected episodes."""
    from opentsx.model.pandas_io import episodes_to_frame

    return episodes_to_frame(read_episodes(catalog, namespace, **filters))
