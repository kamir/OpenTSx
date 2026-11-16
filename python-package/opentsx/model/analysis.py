"""Analysis results of the v2 model: ``AnalysisResult`` with scalars, curves and matrices, mirroring Java ``AnalysisResults``.

Result identity: ``resultId = hex(xxHash64(utf8(canonical)))`` with::

    canonical = escape(method) + "@" + escape(methodVersion)
              + "(" + "k1=v1,k2=v2" + ")"          keys sorted by their UTF-8 bytes
              + "[" + "e1,e2" + "]"                input episode ids in their given order

Parameter values carry their Avro type: long ``l<decimal>``, double ``d<16 hex digits of the IEEE 754 bits,
big-endian>``, string ``s<escaped>``, array of doubles ``a<hex>;<hex>`` (``a`` when empty).
``\\ { } , = ( ) [ ] @`` are escaped with a backslash. NaN is not allowed in parameters.
"""

from __future__ import annotations

import struct
from typing import Iterable, Mapping, Optional, Sequence

from opentsx.model.episodes import PRODUCER, _provenance
from opentsx.model.series_key import sorted_by_utf8
from opentsx.model.xxh64 import xxh64_hex

_SPECIAL = set("\\{},=()[]@")
_LONG_MIN = -(1 << 63)
_LONG_MAX = (1 << 63) - 1


def _escape(s: str) -> str:
    return "".join("\\" + c if c in _SPECIAL else c for c in s)


def _double_hex(v: float) -> str:
    if v != v:
        raise ValueError("NaN is not allowed in analysis parameters")
    return struct.pack(">d", v).hex()


def param_value(value) -> object:
    """Normalizes a parameter to its Avro value: int -> long, float -> double, str -> string, sequence -> array."""
    if isinstance(value, bool):
        raise TypeError("boolean parameters are not supported; use a long (0 or 1) or a string")
    if isinstance(value, int):
        if not _LONG_MIN <= value <= _LONG_MAX:
            raise ValueError(f"long parameter out of range: {value}")
        return int(value)
    if isinstance(value, float):
        _double_hex(value)
        return float(value)
    if isinstance(value, str):
        return value
    if isinstance(value, (list, tuple)):
        out = [float(v) for v in value]
        for v in out:
            _double_hex(v)
        return out
    raise TypeError(f"unsupported parameter type: {type(value).__name__}")


def _canonical_value(value) -> str:
    if isinstance(value, bool):
        raise TypeError("boolean parameters are not supported")
    if isinstance(value, int):
        return "l" + str(value)
    if isinstance(value, float):
        return "d" + _double_hex(value)
    if isinstance(value, str):
        return "s" + _escape(value)
    if isinstance(value, list):
        return "a" + ";".join(_double_hex(v) for v in value)
    raise TypeError(f"unsupported parameter type: {type(value).__name__}")


def result_canonical(method: str, method_version: str, params: Optional[Mapping] = None,
                     input_episode_ids: Sequence[str] = ()) -> str:
    if not method:
        raise ValueError("method must not be empty")
    if not method_version:
        raise ValueError("methodVersion must not be empty")
    if not input_episode_ids:
        raise ValueError("an analysis result needs at least one input episode")
    normalized = {k: param_value(v) for k, v in (params or {}).items()}
    parts = []
    for key in sorted(normalized, key=lambda k: k.encode("utf-8")):
        if not key:
            raise ValueError("parameter names must not be empty")
        parts.append(_escape(key) + "=" + _canonical_value(normalized[key]))
    for e in input_episode_ids:
        if not e:
            raise ValueError("input episode ids must not be empty")
    return (_escape(method) + "@" + _escape(method_version) + "(" + ",".join(parts) + ")"
            + "[" + ",".join(_escape(e) for e in input_episode_ids) + "]")


def result_id(method: str, method_version: str, params: Optional[Mapping] = None,
              input_episode_ids: Sequence[str] = ()) -> str:
    return xxh64_hex(result_canonical(method, method_version, params, input_episode_ids).encode("utf-8"))


def scalar(name: str, value: float, *, std_error: Optional[float] = None, unit: Optional[str] = None,
           fit_from: Optional[float] = None, fit_to: Optional[float] = None) -> dict:
    return {"name": name, "value": float(value),
            "stdError": None if std_error is None else float(std_error), "unit": unit,
            "fitFrom": None if fit_from is None else float(fit_from),
            "fitTo": None if fit_to is None else float(fit_to)}


def curve(name: str, x_name: str, x: Iterable[float], y: Iterable[float], *, x_unit: Optional[str] = None,
          unit: Optional[str] = None) -> dict:
    xs = [float(v) for v in x]
    ys = [float(v) for v in y]
    if len(xs) != len(ys):
        raise ValueError(f"curve {name}: x has {len(xs)} values, y has {len(ys)}")
    return {"name": name, "xName": x_name, "xUnit": x_unit, "x": xs, "y": ys, "unit": unit}


def matrix(name: str, row_keys: Sequence[str], col_keys: Sequence[str], values: Iterable[float], *,
           unit: Optional[str] = None) -> dict:
    vals = [float(v) for v in values]
    if len(vals) != len(row_keys) * len(col_keys):
        raise ValueError(f"matrix {name}: {len(vals)} values for {len(row_keys)} x {len(col_keys)}")
    return {"name": name, "rowKeys": list(row_keys), "colKeys": list(col_keys), "values": vals, "unit": unit}


def analysis_result(method: str, method_version: str, params: Optional[Mapping], input_episode_ids: Sequence[str],
                    input_series_ids: Sequence[str], t_start_us: int, t_end_us: int, *,
                    scalars: Sequence[dict] = (), curves: Sequence[dict] = (), matrices: Sequence[dict] = (),
                    labels: Optional[Mapping[str, str]] = None, source: str, producer: str = PRODUCER,
                    created_at_us: Optional[int] = None, pipeline: Optional[str] = None) -> dict:
    """Builds a checked ``AnalysisResult`` record with its resultId and maps in UTF-8 key order."""
    normalized = {k: param_value(v) for k, v in (params or {}).items()}
    record = {
        "resultId": result_id(method, method_version, normalized, input_episode_ids),
        "method": method,
        "methodVersion": method_version,
        "params": {k: normalized[k] for k in sorted(normalized, key=lambda k: k.encode("utf-8"))},
        "inputEpisodeIds": list(input_episode_ids),
        "inputSeriesIds": list(input_series_ids),
        "tStart": int(t_start_us),
        "tEnd": int(t_end_us),
        "scalars": list(scalars),
        "curves": list(curves),
        "matrices": list(matrices),
        "labels": sorted_by_utf8(labels),
        "provenance": _provenance(source, producer, created_at_us, pipeline, None),
    }
    verify_analysis_result(record)
    return record


def verify_analysis_result(record: Mapping) -> None:
    """Raises ValueError if resultId, input lists, time range, curves or matrices are inconsistent."""
    expected = result_id(record["method"], record["methodVersion"], record["params"], record["inputEpisodeIds"])
    if record["resultId"] != expected:
        raise ValueError(f"resultId {record['resultId']} does not match {expected}")
    if len(record["inputSeriesIds"]) != len(record["inputEpisodeIds"]):
        raise ValueError(f"{len(record['inputSeriesIds'])} inputSeriesIds for "
                         f"{len(record['inputEpisodeIds'])} inputEpisodeIds")
    if record["tStart"] >= record["tEnd"]:
        raise ValueError(f"tStart {record['tStart']} must be before tEnd {record['tEnd']}")
    for c in record["curves"]:
        if len(c["x"]) != len(c["y"]):
            raise ValueError(f"curve {c['name']}: x has {len(c['x'])} values, y has {len(c['y'])}")
    for m in record["matrices"]:
        if len(m["values"]) != len(m["rowKeys"]) * len(m["colKeys"]):
            raise ValueError(f"matrix {m['name']}: {len(m['values'])} values for "
                             f"{len(m['rowKeys'])} x {len(m['colKeys'])}")
