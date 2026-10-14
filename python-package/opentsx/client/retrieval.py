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

    # ---- what the server knows: inventory, systems, signals, decisions -----------------------------------------

    def _json(self, method: str, path: str, body: Optional[dict] = None) -> Any:
        return json.loads(self._request(method, path, body))

    def inventory(self) -> pd.DataFrame:
        """The series the server holds, one row each: identity, quantity, period, values, gaps and origin.

        Tags become columns ``tag.<name>``. ``origin`` comes from the labels of the series (MEASURED, REPORTED,
        ESTIMATED, FORECAST, SYNTHETIC); a series without that label has none.
        """
        doc = self._json("GET", "/v1/inventory")
        rows = []
        for s in doc.get("series", []):
            labels = s.get("labels") or {}
            origin = labels.get("origin") or labels.get("dwd.origin") or labels.get("twin.origin")
            row = {
                "series_id": s.get("seriesId"),
                "metric": s.get("metric"),
                "unit": s.get("unit"),
                "from": pd.to_datetime(s.get("from"), utc=True),
                "to": pd.to_datetime(s.get("to"), utc=True),
                "samples": s.get("samples"),
                "missing": s.get("missing"),
                "min": s.get("min"),
                "max": s.get("max"),
                "interval_s": (s["intervalMicros"] / 1e6) if s.get("intervalMicros") else None,
                "origin": origin[-1] if isinstance(origin, list) and origin else origin,
            }
            for k, v in (s.get("tags") or {}).items():
                row[f"tag.{k}"] = v
            rows.append(row)
        df = pd.DataFrame(rows)
        if not df.empty:
            df = df.sort_values(["metric", "series_id"]).reset_index(drop=True)
        df.attrs["source"] = doc.get("source")
        return df

    def systems(self) -> pd.DataFrame:
        """The systems the server has documents for."""
        return pd.DataFrame(self._json("GET", "/v1/systems").get("systems", []))

    def signals(self, system: str) -> pd.DataFrame:
        """The signals of a system with what is expected, where each grade comes from and the state."""
        doc = self._json("GET", f"/v1/systems/{urllib.parse.quote(system)}/signals")
        rows = []
        for s in doc.get("signals", []):
            e = s.get("expect") or {}
            raw = ((s.get("grades") or {}).get("raw") or {}).get("data") or {}
            clean = ((s.get("grades") or {}).get("clean") or {}).get("data") or {}
            rows.append({"id": s.get("id"), "title": s.get("title"), "role": s.get("role"), "state": s.get("state"),
                         "origin": s.get("origin"), "via": s.get("via"), "unit": e.get("unit"), "min": e.get("min"),
                         "max": e.get("max"), "interval": e.get("interval"),
                         "operating_min": (e.get("operating") or {}).get("min"),
                         "operating_max": (e.get("operating") or {}).get("max"),
                         "meaning": s.get("meaning"),
                         "raw_series": ",".join(raw.get("series", [])) or None,
                         "clean_series": ",".join(clean.get("series", [])) or None,
                         "next": ",".join(s.get("next", [])) or None})
        return pd.DataFrame(rows)

    def decisions(self, system: str) -> dict:
        """The decision cards of a system with what each analysis may claim today (``opentsx.decision-status.v1``)."""
        return self._json("GET", f"/v1/systems/{urllib.parse.quote(system)}/decisions")

    # ---- the twin: scenario and runs ---------------------------------------------------------------------------

    def scenario(self, system: str) -> dict:
        """What can be asked of the twin of a system: controls, policies, reference decisions, KPIs."""
        return self._json("GET", f"/v1/systems/{urllib.parse.quote(system)}/scenario")

    def run(self, system: str, interventions: Sequence[Mapping[str, Any]] = (), policies: Optional[Mapping[str, str]] = None,
            start_minute: Optional[int] = None, on_reference: bool = True, label: Optional[str] = None,
            inputs: str = "scenario") -> dict:
        """Computes a run of the twin; ``interventions`` are ``{at: "HH:MM" | minute, atom, control, value}``.

        ``inputs="signals"`` takes the inputs from the series of the signals of the system instead of the invented
        inputs of its scenario; the run is then compared with the decisions of the scenario on the same inputs
        (``baseline``). A run computes on the server and is kept there in memory; it writes nothing to the broker.

        The answer carries the identity of the run (``run``) and its ``status``: a run on the inputs of the scenario
        is ``restricted``, and the reasons say why.
        """
        body: Dict[str, Any] = {"base": "reference" if on_reference else "empty", "interventions": [dict(i) for i in interventions]}
        if policies:
            body["policies"] = dict(policies)
        if start_minute is not None:
            body["from"] = int(start_minute)
        if label:
            body["label"] = label
        if inputs != "scenario":
            body["inputs"] = inputs
        return self._json("POST", f"/v1/systems/{urllib.parse.quote(system)}/runs", body)

    def runs(self, system: str) -> pd.DataFrame:
        """The runs the server keeps for a system, newest first."""
        doc = self._json("GET", f"/v1/systems/{urllib.parse.quote(system)}/runs")
        return pd.DataFrame([{k: r.get(k) for k in ("run", "kind", "label", "createdAt")} for r in doc.get("runs", [])])

    def run_series(self, system: str, run: str, keys: Sequence[str]) -> pd.DataFrame:
        """Series of a run as a wide frame indexed by UTC time; one column per key, the run id in ``attrs``."""
        doc = self._json("GET", f"/v1/systems/{urllib.parse.quote(system)}/runs/{urllib.parse.quote(run)}/series?keys="
                         + urllib.parse.quote(",".join(keys), safe=","))
        start = pd.Timestamp(doc["start"])
        step = pd.Timedelta(seconds=doc["stepSeconds"])
        cols = {k: [float("nan") if v is None else v for v in s["values"]] for k, s in doc["series"].items()}
        n = max((len(v) for v in cols.values()), default=0)
        df = pd.DataFrame(cols, index=pd.date_range(start, periods=n, freq=step, name="ts"))
        df.attrs["run"] = run
        df.attrs["origin"] = {k: s.get("origin") for k, s in doc["series"].items()}
        return df

    def compare(self, system: str, run: str, base: Optional[str] = None) -> dict:
        """A run next to another (by default the reference): KPIs with difference and verdict, end stocks, series."""
        q = "" if base is None else "?base=" + urllib.parse.quote(base)
        return self._json("GET", f"/v1/systems/{urllib.parse.quote(system)}/runs/{urllib.parse.quote(run)}/compare{q}")

