"""Series identity: seriesId = hex(xxHash64(utf8(canonical(metric, tags)))).

Canonical form: ``escape(metric) + "{" + "k1=v1,k2=v2" + "}"`` with keys sorted by their UTF-8 bytes and
``\\ { } , =`` escaped with a backslash. The unit is not part of the identity.
"""

from __future__ import annotations

from typing import Mapping, Optional

from opentsx.model.xxh64 import xxh64_hex

_SPECIAL = set("\\{},=")


def _escape(s: str) -> str:
    return "".join("\\" + c if c in _SPECIAL else c for c in s)


def canonical(metric: str, tags: Optional[Mapping[str, str]] = None) -> str:
    if not metric:
        raise ValueError("metric must not be empty")
    tags = tags or {}
    parts = []
    for key in sorted(tags, key=lambda k: k.encode("utf-8")):
        if not key:
            raise ValueError("tag keys must not be empty")
        value = tags[key]
        if value is None:
            raise ValueError(f"tag value must not be None: {key}")
        parts.append(f"{_escape(key)}={_escape(value)}")
    return f"{_escape(metric)}{{{','.join(parts)}}}"


def series_id(metric: str, tags: Optional[Mapping[str, str]] = None) -> str:
    return xxh64_hex(canonical(metric, tags).encode("utf-8"))


def sorted_by_utf8(mapping: Optional[Mapping[str, str]]) -> dict:
    """Copy with keys in UTF-8 byte order; makes Avro map encoding deterministic (same bytes as Java)."""
    mapping = mapping or {}
    return {k: mapping[k] for k in sorted(mapping, key=lambda k: k.encode("utf-8"))}


def series_key(metric: str, tags: Optional[Mapping[str, str]] = None, unit: Optional[str] = None) -> dict:
    tags = sorted_by_utf8(tags)
    return {"metric": metric, "tags": tags, "unit": unit, "seriesId": series_id(metric, tags)}


def verify_series_key(key: Mapping) -> None:
    expected = series_id(key["metric"], key.get("tags") or {})
    if key["seriesId"] != expected:
        raise ValueError(f"seriesId {key['seriesId']} does not match {expected}")
