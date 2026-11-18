"""Avro single-object encoding of v2 records (``C3 01`` + 8-byte LE CRC-64-AVRO fingerprint + body).

Byte-identical with the Java ``OpenTsxAvro`` (checked by the shared test vectors).
"""

from __future__ import annotations

import io
import json
import struct
from importlib import resources
from typing import Dict, Mapping, Optional, Tuple

import fastavro
from fastavro.schema import expand_schema, parse_schema, to_parsing_canonical_form

_MAGIC = b"\xc3\x01"
_ORDER = ["SeriesKey", "Segmentation", "EpisodeSummary", "Provenance",
          "Episode", "Observation", "BucketManifest", "SeriesStats", "PatternMatch"]
_TOP_LEVEL = ["Episode", "Observation", "SeriesKey", "BucketManifest", "SeriesStats", "PatternMatch"]
_NAMESPACE = "org.opentsx.model.v2"

_cache: Optional[Tuple[Dict[str, dict], Dict[int, Tuple[str, dict]]]] = None

_EMPTY64 = 0xC15D213AA4D7A795
_TABLE = []
for _i in range(256):
    _fp = _i
    for _ in range(8):
        _fp = (_fp >> 1) ^ (_EMPTY64 & -(_fp & 1))
    _TABLE.append(_fp)


def _crc64_avro(data: bytes) -> int:
    fp = _EMPTY64
    for b in data:
        fp = (fp >> 8) ^ _TABLE[(fp ^ b) & 0xFF]
    return fp


def _fingerprint(parsed: dict) -> int:
    pcf = to_parsing_canonical_form(expand_schema(parsed))
    return _crc64_avro(pcf.encode("utf-8"))


def _load() -> Tuple[Dict[str, dict], Dict[int, Tuple[str, dict]]]:
    global _cache
    if _cache is None:
        base = resources.files("opentsx.model") / "schemas"
        named: Dict[str, dict] = {}
        parsed: Dict[str, dict] = {}
        for name in _ORDER:
            raw = json.loads((base / f"{name}.avsc").read_text(encoding="utf-8"))
            parsed[name] = parse_schema(raw, named_schemas=named)
        by_fp = {_fingerprint(parsed[name]): (name, parsed[name]) for name in _TOP_LEVEL}
        _cache = (parsed, by_fp)
    return _cache


def schema(name: str) -> dict:
    """Parsed fastavro schema of a v2 record, e.g. ``schema("Episode")``."""
    parsed = _load()[0]
    if name not in parsed:
        raise ValueError(f'schema: "{name}" is unknown; use one of {", ".join(_ORDER)}')
    return parsed[name]


def fingerprint(name: str) -> int:
    """CRC-64-AVRO fingerprint of the parsing canonical form (same as Java SchemaNormalization)."""
    return _fingerprint(schema(name))


def encode(name: str, record: Mapping) -> bytes:
    sch = schema(name)
    buf = io.BytesIO()
    buf.write(_MAGIC)
    buf.write(struct.pack("<Q", _fingerprint(sch)))
    fastavro.schemaless_writer(buf, sch, record)
    return buf.getvalue()


def decode(payload: bytes) -> Tuple[str, dict]:
    """Returns (record name, record). Timestamps are decoded as timezone-aware datetimes (UTC)."""
    by_fp = _load()[1]
    if len(payload) < 10 or payload[:2] != _MAGIC:
        raise ValueError("not an Avro single-object payload")
    fp = struct.unpack("<Q", payload[2:10])[0]
    if fp not in by_fp:
        raise ValueError(f"unknown schema fingerprint {fp:016x}")
    name, sch = by_fp[fp]
    try:
        return name, fastavro.schemaless_reader(io.BytesIO(payload[10:]), sch, sch)
    except (EOFError, IndexError, ValueError, struct.error) as e:
        raise ValueError(f"{name}: unreadable Avro body ({e})") from e
