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


def to_us(t) -> int:
    """Epoch microseconds from an int or a timezone-aware datetime (fastavro decodes timestamp-micros as datetime)."""
    if isinstance(t, datetime):
        if t.tzinfo is None:
            raise ValueError("naive datetime; timestamps must be timezone-aware (UTC)")
        return (t - _EPOCH) // _ONE_US
    return int(t)


def _write_zigzag(out: bytearray, v: int) -> None:
    z = ((v << 1) ^ (v >> 63)) & 0xFFFFFFFFFFFFFFFF
    while z & ~0x7F:
        out.append((z & 0x7F) | 0x80)
        z >>= 7
    out.append(z)


def encode_deltas(timestamps_us: Sequence[int]) -> bytes:
    out = bytearray()
    prev_delta = 0
    for i in range(1, len(timestamps_us)):
        delta = int(timestamps_us[i]) - int(timestamps_us[i - 1])
        if delta <= 0:
            raise ValueError(f"timestamps must be strictly increasing at index {i}")
        _write_zigzag(out, delta if i == 1 else delta - prev_delta)
        prev_delta = delta
    return bytes(out)


def decode_deltas(t_start_us: int, count: int, deltas: bytes) -> np.ndarray:
    ts = np.empty(count, dtype=np.int64)
    if count == 0:
        return ts
    ts[0] = t_start_us
    pos = 0
    delta = 0
    for i in range(1, count):
        z = 0
        shift = 0
        while True:
            if pos >= len(deltas):
                raise ValueError("truncated varint in timeDeltas")
            b = deltas[pos]
            pos += 1
            z |= (b & 0x7F) << shift
            if not b & 0x80:
                break
            shift += 7
        v = (z >> 1) ^ -(z & 1)
        delta = v if i == 1 else delta + v
        ts[i] = ts[i - 1] + delta
    if pos != len(deltas):
        raise ValueError("trailing bytes in timeDeltas")
    return ts


def timestamps_us(episode: Mapping) -> np.ndarray:
    t_start = to_us(episode["tStart"])
    n = episode["count"]
    if episode["timeEncoding"] == "REGULAR":
        return t_start + np.arange(n, dtype=np.int64) * int(episode["intervalMicros"])
    return decode_deltas(t_start, n, episode["timeDeltas"])
