"""Mergeable quantile sketch with relative accuracy (DDSketch), byte-compatible with
``org.opentsx.stats.QuantileSketch`` (format v1, stored in ``SeriesStats.quantileSketch``)."""
from __future__ import annotations

import math
import struct
from decimal import ROUND_HALF_EVEN, Context, Decimal
from typing import Dict, Tuple

DEFAULT_ALPHA = 0.01
MIN_INDEXABLE = 1e-9
_FORMAT_V1 = 1
_LONG_MAX = (1 << 63) - 1
_INT_MIN = -(1 << 31)
_INT_MAX = (1 << 31) - 1
_COUNT_ABOVE_LONG = (f"sketch bytes: zeroCount and the bin counts add up to more than {_LONG_MAX};"
                     f" expected a total count of at most {_LONG_MAX}")


def _java_double(x: float) -> str:
    """``x`` as Java's ``Double.toString`` writes it, so both languages raise the same texts."""
    try:
        x = float(x)
    except OverflowError:
        return str(x)
    if math.isnan(x):
        return "NaN"
    if math.isinf(x):
        return "Infinity" if x > 0 else "-Infinity"
    sign = "-" if math.copysign(1.0, x) < 0 else ""
    if x == 0:
        return sign + "0.0"
    shortest = Decimal(repr(abs(x)))
    if len(shortest.normalize().as_tuple().digits) == 1:
        # Java picks the closest decimal of one or two digits: 4.9E-324 where repr gives 5e-324
        two = Context(prec=2, rounding=ROUND_HALF_EVEN).plus(Decimal(abs(x)))
        if float(two) == abs(x):
            shortest = two
    _, digits, exponent = shortest.normalize().as_tuple()
    text = "".join(map(str, digits))
    point = len(text) + exponent
    if -2 <= point <= 7:
        if point <= 0:
            return sign + "0." + "0" * -point + text
        return sign + text[:point].ljust(point, "0") + "." + (text[point:] or "0")
    return sign + text[0] + "." + (text[1:] or "0") + "E" + str(point - 1)


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
        if pos >= len(buf):
            raise ValueError(
                "sketch bytes: input ends inside a varint; expected complete varints")
        b = buf[pos]
        pos += 1
        if shift == 63 and b & 0x7E:
            raise ValueError(
                "sketch bytes: varint exceeds 64 bits; expected at most 64 bits per varint")
        result |= (b & 0x7F) << shift
        if not b & 0x80:
            return result, pos
        shift += 7
        if shift > 63:
            raise ValueError(
                "sketch bytes: varint longer than 10 bytes; expected at most 10 bytes per varint")


def _read_unsigned(buf: bytes, pos: int) -> Tuple[int, int]:
    v, pos = _read_varint(buf, pos)
    if v > _LONG_MAX:
        raise ValueError(f"sketch bytes: varint exceeds {_LONG_MAX}; "
                         f"expected a whole number from 0 to {_LONG_MAX}")
    return v, pos


def _zigzag(v: int) -> int:
    return (v << 1) ^ (v >> 63) if v >= 0 else ((-v) << 1) - 1


def _unzigzag(v: int) -> int:
    return (v >> 1) ^ -(v & 1)


class QuantileSketch:
    """Values fall into bin ``ceil(log_gamma |x|)``, ``gamma = (1 + alpha) / (1 - alpha)``; every quantile has a
    relative error of at most ``alpha``. Sketches with the same alpha merge exactly."""

    def __init__(self, alpha: float = DEFAULT_ALPHA):
        if not 0 < alpha < 1:
            raise ValueError(f"alpha: {_java_double(alpha)} is out of range;"
                             " expected a number above 0 and below 1")
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
            raise ValueError(f"alpha: cannot merge a sketch with alpha {_java_double(other.alpha)}"
                             f" into one with alpha {_java_double(self.alpha)};"
                             " expected the same alpha")
        for src, dst in ((other.positive, self.positive), (other.negative, self.negative)):
            for k, v in src.items():
                dst[k] = dst.get(k, 0) + v
        self.zero_count += other.zero_count
        return self

    def quantile(self, q: float) -> float:
        if not 0 <= q <= 1:
            raise ValueError(f"q: {_java_double(q)} is out of range; expected a number from 0 to 1")
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
        if not data:
            raise ValueError("sketch bytes: truncated at 0 of 0")
        (version,) = struct.unpack_from("<b", data)
        if version != _FORMAT_V1:
            raise ValueError(f"sketch bytes: format version {version} is not supported;"
                             f" expected {_FORMAT_V1}")
        if len(data) < 9:
            raise ValueError(f"sketch bytes: truncated at 1 of {len(data)}")
        (alpha,) = struct.unpack_from("<d", data, 1)
        s = cls(alpha)
        s.zero_count, pos = _read_unsigned(data, 9)
        count = s.zero_count
        for store in (s.positive, s.negative):
            bins, pos = _read_unsigned(data, pos)
            prev = 0
            for i in range(bins):
                d, pos = _read_varint(data, pos)
                delta = _unzigzag(d)
                index = prev + delta
                if not _INT_MIN <= index <= _INT_MAX:
                    raise ValueError(
                        f"sketch bytes: bin index {index} out of range; expected an int")
                if i > 0 and delta <= 0:
                    raise ValueError(f"sketch bytes: bin index {index} is not above the previous"
                                     f" index {prev}; expected bins in ascending index order")
                n, pos = _read_unsigned(data, pos)
                count += n
                if count > _LONG_MAX:
                    raise ValueError(_COUNT_ABOVE_LONG)
                store[index] = n
                prev = index
        if pos != len(data):
            raise ValueError(f"sketch bytes: {len(data) - pos} trailing bytes; expected exactly {pos}")
        return s

    def __eq__(self, other: object) -> bool:
        return (isinstance(other, QuantileSketch) and self.alpha == other.alpha
                and self.zero_count == other.zero_count and self.positive == other.positive
                and self.negative == other.negative)
