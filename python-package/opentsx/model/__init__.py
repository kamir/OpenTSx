"""OpenTSx data model v2 (Python side).

Same Avro schemas, series identity, time encodings and wire format as the Java module ``opentsx-model``.
Cross-language equality is checked against the shared test vectors in
``opentsx-model/src/test/resources/testvectors``.
"""

from opentsx.model.analysis import (analysis_result, curve, matrix, result_canonical, result_id, scalar,
                                     verify_analysis_result)
from opentsx.model.buckets import bucket_checksum
from opentsx.model.series_key import canonical, series_id, series_key, verify_series_key
from opentsx.model.time_axis import decode_deltas, encode_deltas, timestamps_us
from opentsx.model.summary import merge_summaries, summarize, variance
from opentsx.model.episodes import content_digest, irregular, regular, values
from opentsx.model.serde import decode, encode, fingerprint, schema
from opentsx.model.ulid import new_ulid
from opentsx.model.xxh64 import xxh64_hex

__all__ = [
    "canonical", "series_id", "series_key", "verify_series_key",
    "encode_deltas", "decode_deltas", "timestamps_us",
    "summarize", "merge_summaries", "variance",
    "regular", "irregular", "values", "content_digest",
    "encode", "decode", "fingerprint", "schema",
    "new_ulid", "xxh64_hex", "bucket_checksum",
    "analysis_result", "scalar", "curve", "matrix", "result_canonical", "result_id", "verify_analysis_result",
]
