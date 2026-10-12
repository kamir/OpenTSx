"""Bucket helpers shared with Java (``org.opentsx.model.Buckets``)."""

from typing import Iterable

from opentsx.model.xxh64 import xxh64_hex


def bucket_checksum(episode_ids: Iterable[str]) -> str:
    """xxHash64 hex over the distinct episode ids, sorted by UTF-8 bytes, joined with newlines."""
    ids = sorted(set(episode_ids), key=lambda s: s.encode("utf-8"))
    return xxh64_hex("\n".join(ids).encode("utf-8"))
