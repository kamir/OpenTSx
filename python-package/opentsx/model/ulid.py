"""ULID: 48-bit millisecond timestamp + 80 random bits, Crockford base32."""

import os
import time

_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"


def new_ulid(epoch_millis: int = None, randomness: bytes = None) -> str:
    t = int(time.time() * 1000) if epoch_millis is None else epoch_millis
    r = os.urandom(10) if randomness is None else randomness
    value = (t << 80) | int.from_bytes(r, "big")
    return "".join(_ALPHABET[(value >> (5 * (25 - i))) & 31] for i in range(26))
