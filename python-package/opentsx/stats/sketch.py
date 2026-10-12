"""Mergeable quantile sketch with relative accuracy (DDSketch), byte-compatible with
``org.opentsx.stats.QuantileSketch`` (format v1, stored in ``SeriesStats.quantileSketch``)."""
from __future__ import annotations

import math
import struct
from typing import Dict, Tuple

DEFAULT_ALPHA = 0.01
MIN_INDEXABLE = 1e-9
_FORMAT_V1 = 1


def _write_varint(out: bytearray, v: int) -> None:
    if v < 0:
        raise ValueError(f"negative varint {v}")
    while v & ~0x7F:
        out.append((v & 0x7F) | 0x80)
        v >>= 7
    out.append(v)


def _read_varint(buf: bytes, pos: int) -> Tuple[int, int]:
    result = 0
    shift = 0
    while True:
        b = buf[pos]
        pos += 1
        result |= (b & 0x7F) << shift
        if not b & 0x80:
            return result, pos
        shift += 7
        if shift > 63:
            raise ValueError("varint too long")


def _zigzag(v: int) -> int:
    return (v << 1) ^ (v >> 63) if v >= 0 else ((-v) << 1) - 1


def _unzigzag(v: int) -> int:
    return (v >> 1) ^ -(v & 1)


class QuantileSketch:
    """Values fall into bin ``ceil(log_gamma |x|)``, ``gamma = (1 + alpha) / (1 - alpha)``; every quantile has a
    relative error of at most ``alpha``. Sketches with the same alpha merge exactly."""

    def __init__(self, alpha: float = DEFAULT_ALPHA):
        if not 0 < alpha < 1:
            raise ValueError(f"alpha must be in (0, 1): {alpha}")
        self.alpha = alpha
        self._gamma = (1 + alpha) / (1 - alpha)
        self._log_gamma = math.log(self._gamma)
        self.positive: Dict[int, int] = {}
        self.negative: Dict[int, int] = {}
        self.zero_count = 0

    @property
    def count(self) -> int:
        return self.zero_count + sum(self.positive.values()) + sum(self.negative.values())

    def _index(self, abs_value: float) -> int:
        return math.ceil(math.log(abs_value) / self._log_gamma)

    def _value(self, index: int) -> float:
        return 2 * self._gamma ** index / (self._gamma + 1)

    def add(self, value: float, n: int = 1) -> None:
        if n <= 0 or not math.isfinite(value):
            return
        a = abs(value)
        if a < MIN_INDEXABLE:
            self.zero_count += n
        else:
            store = self.positive if value > 0 else self.negative
            i = self._index(a)
            store[i] = store.get(i, 0) + n

    def update(self, values) -> "QuantileSketch":
        for v in values:
            self.add(float(v))
        return self

    def merge(self, other: "QuantileSketch") -> "QuantileSketch":
        if other.alpha != self.alpha:
            raise ValueError("cannot merge sketches with different alpha")
        for src, dst in ((other.positive, self.positive), (other.negative, self.negative)):
            for k, v in src.items():
                dst[k] = dst.get(k, 0) + v
        self.zero_count += other.zero_count
        return self

    def quantile(self, q: float) -> float:
        if not 0 <= q <= 1:
            raise ValueError(f"q must be in [0, 1]: {q}")
        n = self.count
        if n == 0:
            return math.nan
        rank = q * (n - 1)
        seen = 0
        for k in sorted(self.negative, reverse=True):
            seen += self.negative[k]
            if seen > rank:
                return -self._value(k)
        seen += self.zero_count
        if seen > rank:
            return 0.0
        keys = sorted(self.positive)
        for k in keys:
            seen += self.positive[k]
            if seen > rank:
                return self._value(k)
        return self._value(keys[-1])

    def to_bytes(self) -> bytes:
        out = bytearray([_FORMAT_V1])
        out += struct.pack("<d", self.alpha)
        _write_varint(out, self.zero_count)
        for store in (self.positive, self.negative):
            _write_varint(out, len(store))
            prev = 0
            for k in sorted(store):
                _write_varint(out, _zigzag(k - prev))
                _write_varint(out, store[k])
                prev = k
        return bytes(out)

    @classmethod
    def from_bytes(cls, data: bytes) -> "QuantileSketch":
        data = bytes(data)
        if data[0] != _FORMAT_V1:
            raise ValueError(f"unsupported sketch format version {data[0]}")
        (alpha,) = struct.unpack_from("<d", data, 1)
        s = cls(alpha)
        s.zero_count, pos = _read_varint(data, 9)
        for store in (s.positive, s.negative):
            bins, pos = _read_varint(data, pos)
            prev = 0
            for _ in range(bins):
                d, pos = _read_varint(data, pos)
                n, pos = _read_varint(data, pos)
                prev += _unzigzag(d)
                store[prev] = n
        return s

    def __eq__(self, other: object) -> bool:
        return (isinstance(other, QuantileSketch) and self.alpha == other.alpha
                and self.zero_count == other.zero_count and self.positive == other.positive
                and self.negative == other.negative)
