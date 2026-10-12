"""Avro single-object encoding of v2 records (``C3 01`` + 8-byte LE CRC-64-AVRO fingerprint + body).

Byte-identical with the Java ``OpenTsxAvro`` (checked by the shared test vectors).
"""

from __future__ import annotations

import io
import json
import struct
from importlib import resources
from typing import Dict, Mapping, Tuple

import fastavro
from fastavro.schema import expand_schema, parse_schema, to_parsing_canonical_form

_MAGIC = b"\xc3\x01"
_ORDER = ["SeriesKey", "Segmentation", "EpisodeSummary", "Provenance",
          "Episode", "Observation", "BucketManifest", "SeriesStats", "PatternMatch"]
_TOP_LEVEL = ["Episode", "Observation", "SeriesKey", "BucketManifest", "SeriesStats", "PatternMatch"]
_NAMESPACE = "org.opentsx.model.v2"

_named: Dict[str, dict] = {}
_parsed: Dict[str, dict] = {}
_by_fp: Dict[int, Tuple[str, dict]] = {}

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


def _load() -> None:
    if _parsed:
        return
    base = resources.files("opentsx.model") / "schemas"
    for name in _ORDER:
        raw = json.loads((base / f"{name}.avsc").read_text(encoding="utf-8"))
        _parsed[name] = parse_schema(raw, named_schemas=_named)
    for name in _TOP_LEVEL:
        fp = fingerprint(name)
        _by_fp[fp] = (name, _parsed[name])


def schema(name: str) -> dict:
    """Parsed fastavro schema of a v2 record, e.g. ``schema("Episode")``."""
    _load()
    return _parsed[name]


def fingerprint(name: str) -> int:
    """CRC-64-AVRO fingerprint of the parsing canonical form (same as Java SchemaNormalization)."""
    _load()
    pcf = to_parsing_canonical_form(expand_schema(_parsed[name]))
    return _crc64_avro(pcf.encode("utf-8"))


def encode(name: str, record: Mapping) -> bytes:
    _load()
    buf = io.BytesIO()
    buf.write(_MAGIC)
    buf.write(struct.pack("<Q", fingerprint(name)))
    fastavro.schemaless_writer(buf, _parsed[name], record)
    return buf.getvalue()


def decode(payload: bytes) -> Tuple[str, dict]:
    """Returns (record name, record). Timestamps are decoded as timezone-aware datetimes (UTC)."""
    _load()
    if len(payload) < 10 or payload[:2] != _MAGIC:
        raise ValueError("not an Avro single-object payload")
    fp = struct.unpack("<Q", payload[2:10])[0]
    if fp not in _by_fp:
        raise ValueError(f"unknown schema fingerprint {fp:016x}")
    name, sch = _by_fp[fp]
    return name, fastavro.schemaless_reader(io.BytesIO(payload[10:]), sch, sch)
