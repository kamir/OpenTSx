"""Episode time axis (see opentsx.model.v2.Episode).

REGULAR: t_i = tStart + i * intervalMicros.
IRREGULAR_DELTA: t_0 = tStart; timeDeltas = count-1 zig-zag varints: first delta, then delta-of-delta.
"""

from __future__ import annotations

from datetime import datetime, timedelta, timezone
from typing import Mapping, Sequence

import numpy as np

_EPOCH = datetime(1970, 1, 1, tzinfo=timezone.utc)
_ONE_US = timedelta(microseconds=1)
_LONG_MIN = -(1 << 63)
_LONG_MAX = (1 << 63) - 1


def to_us(t, field: str = "timestamp") -> int:
    """Epoch microseconds from an int or a timezone-aware datetime (fastavro decodes timestamp-micros as datetime);
    ValueError naming ``field`` for a datetime without a time zone."""
    if isinstance(t, datetime):
        if t.tzinfo is None:
            raise ValueError(f"{field}: {t.isoformat()} has no time zone; "
                             "expected a timezone-aware datetime or epoch microseconds")
        return (t - _EPOCH) // _ONE_US
    return int(t)


def _write_zigzag(out: bytearray, v: int) -> None:
    z = ((v << 1) ^ (v >> 63)) & 0xFFFFFFFFFFFFFFFF
    while z & ~0x7F:
        out.append((z & 0x7F) | 0x80)
        z >>= 7
    out.append(z)


def encode_deltas(timestamps_us: Sequence[int]) -> bytes:
    for i, t in enumerate(timestamps_us):
        if not _LONG_MIN <= int(t) <= _LONG_MAX:
            raise ValueError(f"timestamps: timestamp {i} is {int(t)}; expected timestamps from {_LONG_MIN} to {_LONG_MAX}")
    out = bytearray()
    prev_delta = 0
    for i in range(1, len(timestamps_us)):
        delta = int(timestamps_us[i]) - int(timestamps_us[i - 1])
        if delta <= 0:
            raise ValueError(f"timestamps: timestamp {i} is not after timestamp {i - 1}; "
                             "expected strictly increasing timestamps")
        if delta > _LONG_MAX:
            raise ValueError(f"timestamps: timestamp {i} is {delta} after timestamp {i - 1}; "
                             f"expected steps of at most {_LONG_MAX} microseconds")
        _write_zigzag(out, delta if i == 1 else delta - prev_delta)
        prev_delta = delta
    return bytes(out)


def decode_deltas(t_start_us: int, count: int, deltas: bytes) -> np.ndarray:
    ts = np.empty(count, dtype=np.int64)
    if count == 0:
        return ts
    ts[0] = t_start_us
    t = int(t_start_us)
    pos = 0
    delta = 0
    for i in range(1, count):
        z = 0
        shift = 0
        while True:
            if pos >= len(deltas):
                raise ValueError("timeDeltas: input ends inside a varint; expected complete varints")
            b = deltas[pos]
            pos += 1
            if shift == 63 and b & 0x7E:
                raise ValueError("timeDeltas: varint exceeds 64 bits; expected at most 64 bits per varint")
            z |= (b & 0x7F) << shift
            if not b & 0x80:
                break
            shift += 7
            if shift > 63:
                raise ValueError(
                    "timeDeltas: varint longer than 10 bytes; expected at most 10 bytes per varint")
        v = (z >> 1) ^ -(z & 1)
        delta = v if i == 1 else delta + v
        t += delta
        if not (_LONG_MIN <= delta <= _LONG_MAX and _LONG_MIN <= t <= _LONG_MAX):
            raise ValueError(f"timeDeltas: timestamp {i} leaves the 64-bit range; expected timestamps and deltas "
                             f"from {_LONG_MIN} to {_LONG_MAX}")
        ts[i] = t
    if pos != len(deltas):
        raise ValueError(f"timeDeltas: {len(deltas) - pos} trailing bytes; expected exactly {count - 1} "
                         f"varints for {count} timestamps")
    return ts


def timestamps_us(episode: Mapping) -> np.ndarray:
    t_start = to_us(episode["tStart"], "tStart")
    n = episode["count"]
    if episode["timeEncoding"] == "REGULAR":
        return t_start + np.arange(n, dtype=np.int64) * int(episode["intervalMicros"])
    return decode_deltas(t_start, n, episode["timeDeltas"])
