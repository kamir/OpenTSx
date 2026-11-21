"""Bucket helpers shared with Java (``org.opentsx.model.Buckets``)."""

import hashlib
import re
import struct
from typing import Iterable, Sequence

from opentsx.model.xxh64 import xxh64_hex

CONTENT_PREFIX = "sha256:"
_CONTENT_TAG = b"opentsx.bucket.content.v1\n"
_DIGEST = re.compile(r"[0-9a-f]{64}")


def bucket_checksum(episode_ids: Iterable[str]) -> str:
    """Id-only checksum: xxHash64 hex over the distinct episode ids, sorted by UTF-8 bytes, joined with newlines.

    It does not cover episode contents; readers use it to check manifests that hold no ``sha256:`` prefix.
    """
    ids = sorted(set(episode_ids), key=lambda s: s.encode("utf-8"))
    return xxh64_hex("\n".join(ids).encode("utf-8"))


def bucket_content_checksum(episode_ids: Sequence[str], content_digests: Sequence[str]) -> str:
    """Checksum of a bucket's content, the same as Java ``Buckets.contentChecksum``: ``sha256:`` + SHA-256 over the
    distinct episode ids in UTF-8 byte order, each with its ``content_digest``. One id with two contents raises."""
    if len(episode_ids) != len(content_digests):
        raise ValueError(f"{len(content_digests)} content digests for {len(episode_ids)} episode ids")
    by_id = {}
    for episode_id, digest in zip(episode_ids, content_digests):
        if not episode_id:
            raise ValueError("episode ids must not be empty")
        if not isinstance(digest, str) or not _DIGEST.fullmatch(digest):
            raise ValueError(f"content digest of episode {episode_id} is not 64 lower-case hex characters")
        key = episode_id.encode("utf-8")
        if by_id.setdefault(key, digest) != digest:
            raise ValueError(f"episode {episode_id} appears with two contents")
    h = hashlib.sha256(_CONTENT_TAG)
    h.update(struct.pack("<q", len(by_id)))
    for key in sorted(by_id):
        h.update(struct.pack("<q", len(key)) + key + by_id[key].encode("ascii"))
    return CONTENT_PREFIX + h.hexdigest()
