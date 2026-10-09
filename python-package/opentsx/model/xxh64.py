"""xxHash64 with seed 0, via the ``xxhash`` package."""

import xxhash


def xxh64_hex(data: bytes) -> str:
    return xxhash.xxh64_hexdigest(data, seed=0)
