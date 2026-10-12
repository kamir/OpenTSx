"""Python client for the OpenTSx retrieval API.

Episodes are transferred as an Avro single-object stream and decoded with :mod:`opentsx.model`, so they are the same
records Java works with; pattern matches come back as pandas DataFrames.

>>> c = RetrievalClient("http://localhost:8080")
>>> eps = c.episodes(metric="wind.met.wind_speed", tags={"turbine": "T07"}, start="2026-10-01T00:00:00Z", end=...)
>>> df = c.frame(metric="wind.met.wind_speed", tags={"turbine": "T07"}, resample="PT1M")
>>> hits = c.search(pattern=my_gust, metric="wind.met.wind_speed", distance="DTW", band=0.1, top_k=20)
"""
from __future__ import annotations

import json
import struct
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from typing import Any, Dict, Iterable, List, Mapping, Optional, Sequence

import pandas as pd

from opentsx import model as m
from opentsx.model.pandas_io import episodes_to_frame

AVRO_STREAM = "application/x-opentsx-avro-stream"


class RetrievalError(RuntimeError):
    def __init__(self, status: int, message: str):
        super().__init__(f"HTTP {status}: {message}")
        self.status = status


def _iso(t: Any) -> Optional[str]:
    if t is None:
        return None
    if isinstance(t, str):
        return t
    if isinstance(t, pd.Timestamp):
        t = t.to_pydatetime()
    if isinstance(t, datetime):
        if t.tzinfo is None:
            raise ValueError("naive datetime; pass a timezone-aware value (UTC)")
        return t.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")
    raise TypeError(f"unsupported time value {t!r}")


def decode_stream(data: bytes) -> List[dict]:
    """Decodes ``[uint32 BE length][Avro single-object Episode]*``."""
    out, pos = [], 0
    while pos < len(data):
        (n,) = struct.unpack_from(">I", data, pos)
        pos += 4
        name, rec = m.decode(data[pos:pos + n])
        if name != "Episode":
            raise ValueError(f"expected Episode records, got {name}")
        out.append(rec)
        pos += n
    return out


class RetrievalClient:
    def __init__(self, base_url: str, timeout: float = 120.0):
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout

    # ---- transport ---------------------------------------------------------------------------------------------

    def _request(self, method: str, path: str, body: Optional[dict] = None, accept: str = "application/json") -> bytes:
        data = None if body is None else json.dumps({k: v for k, v in body.items() if v is not None}).encode()
        req = urllib.request.Request(self.base_url + path, data=data, method=method,
                                     headers={"Accept": accept, "Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as r:
                return r.read()
        except urllib.error.HTTPError as e:
            raw = e.read()
            try:
                msg = json.loads(raw).get("error", raw.decode())
            except ValueError:
                msg = raw.decode(errors="replace")
            raise RetrievalError(e.code, msg) from None

    @staticmethod
    def _selection(metric, tags, series, start, end) -> Dict[str, Any]:
        return {"metric": metric, "tags": dict(tags) if tags else None,
                "series": ",".join(series) if series else None, "from": _iso(start), "to": _iso(end)}

    # ---- episodes ----------------------------------------------------------------------------------------------

    def episodes(self, metric: Optional[str] = None, tags: Optional[Mapping[str, str]] = None,
                 series: Optional[Sequence[str]] = None, start=None, end=None, limit: Optional[int] = None,
                 clip: bool = False, resample: Optional[str] = None, agg: str = "MEAN") -> List[dict]:
        q: Dict[str, str] = {}
        sel = self._selection(metric, tags, series, start, end)
        for k in ("metric", "series", "from", "to"):
            if sel[k] is not None:
                q[k] = sel[k]
        for k, v in (tags or {}).items():
            q[f"tag.{k}"] = v
        if limit is not None:
            q["limit"] = str(limit)
        if clip:
            q["clip"] = "true"
        if resample:
            q["resample"], q["agg"] = resample, agg
        path = "/v1/episodes" + ("?" + urllib.parse.urlencode(q) if q else "")
        return decode_stream(self._request("GET", path, accept=AVRO_STREAM))

    def episode(self, episode_id: str) -> Optional[dict]:
        try:
            eps = decode_stream(self._request("GET", "/v1/episodes/" + urllib.parse.quote(episode_id), accept=AVRO_STREAM))
        except RetrievalError as e:
            if e.status == 404:
                return None
            raise
        return eps[0] if eps else None

    def frame(self, **kwargs) -> pd.DataFrame:
        """Long DataFrame (series_id, metric, episode_id, ts, value) of :meth:`episodes`."""
        return episodes_to_frame(self.episodes(**kwargs))

    # ---- patterns ----------------------------------------------------------------------------------------------

    def search(self, pattern: Optional[Iterable[float]] = None, episode_id: Optional[str] = None,
               offset: Optional[int] = None, length: Optional[int] = None, metric: Optional[str] = None,
               tags: Optional[Mapping[str, str]] = None, series: Optional[Sequence[str]] = None, start=None, end=None,
               distance: str = "zED", band: float = 0.1, top_k: int = 10) -> pd.DataFrame:
        body = self._selection(metric, tags, series, start, end)
        if pattern is not None:
            body["pattern"] = [float(v) for v in pattern]
        else:
            body.update(episodeId=episode_id, offset=offset, length=length)
        body.update(distance=distance, band=band, topK=top_k)
        return pd.DataFrame(json.loads(self._request("POST", "/v1/patterns/search", body)))

    def similar(self, episode_id: str, metric: Optional[str] = None, tags: Optional[Mapping[str, str]] = None,
                start=None, end=None, distance: str = "zED", band: float = 0.1, top_k: int = 10) -> pd.DataFrame:
        body = self._selection(metric, tags, None, start, end)
        body.update(episodeId=episode_id, distance=distance, band=band, topK=top_k)
        return pd.DataFrame(json.loads(self._request("POST", "/v1/episodes/similar", body)))

    def segments(self, strategy: str, params: Mapping[str, Any], metric: Optional[str] = None,
                 tags: Optional[Mapping[str, str]] = None, start=None, end=None) -> List[dict]:
        body = self._selection(metric, tags, None, start, end)
        body.update(strategy=strategy, params=dict(params))
        return decode_stream(self._request("POST", "/v1/segments", body, accept=AVRO_STREAM))
