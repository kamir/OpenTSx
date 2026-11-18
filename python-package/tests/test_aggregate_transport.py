"""Decode shared Java aggregate transport bytes without implementing a site runtime."""

import base64
import hashlib
import json
import math
import pathlib
from datetime import datetime, timedelta, timezone

import pytest

from opentsx import model


ROOT = pathlib.Path(__file__).resolve().parents[2]
FILE = ROOT / "docs/datamodel-2026/testvectors/aggregate-transport.v1.json"
DOCUMENT = json.loads(FILE.read_text(encoding="utf-8"))
CASES = DOCUMENT["cases"]
EPOCH = datetime(1970, 1, 1, tzinfo=timezone.utc)


def micros(value):
    return None if value is None else (value - EPOCH) // timedelta(microseconds=1)


@pytest.mark.parametrize("case", CASES, ids=lambda case: case["name"])
def test_series_stats_payload_and_projection_match_java(case):
    payload = bytes.fromhex(case["soeHex"])
    assert base64.b64decode(case["record"]["value"], validate=True) == payload
    name, record = model.decode(payload)
    assert name == "SeriesStats"
    assert model.encode(name, record) == payload
    projection = {key: record[key] for key in ("seriesId", "windowType", "validCount", "nanCount", "gapCount")}
    projection.update(windowStartMicros=micros(record["windowStart"]), windowEndMicros=micros(record["windowEnd"]),
                      lastSeenMicros=micros(record["lastSeen"]), sketchHex=record["quantileSketch"].hex())
    projection.update({key: "NaN" if math.isnan(record[key]) else record[key] for key in ("min", "max", "mean", "m2")})
    assert projection == case["statistics"]
    headers = case["record"]["headers"]
    identity = [headers["tsx-job"]]
    if case["kind"] == "partial":
        identity.append(headers["tsx-site"])
    identity.extend([record["seriesId"], record["windowType"], str(micros(record["windowStart"]))])
    assert case["record"]["key"] == "|".join(identity)


@pytest.mark.parametrize("case", [case for case in CASES if case["kind"] == "partial"], ids=lambda case: case["name"])
def test_partial_delivery_evidence_hash_and_schema_field_projection(case):
    schemas = ROOT / "docs/datamodel-2026/schemas"
    delivery_schema = json.loads((schemas / "opentsx.aggregate-delivery.v1.schema.json").read_text(encoding="utf-8"))
    evidence_schema = json.loads((schemas / "opentsx.aggregate-evidence.v1.schema.json").read_text(encoding="utf-8"))
    delivery, record, evidence = case["delivery"], case["record"], case["evidence"]
    assert delivery == {"schema": "opentsx.aggregate-delivery.v1", "records": [record]}
    assert set(delivery) == set(delivery_schema["required"])
    assert set(record["headers"]) == set(delivery_schema["$defs"]["headers"]["required"])
    assert set(evidence) == set(evidence_schema["required"])
    assert json.loads(record["headers"]["tsx-evidence"]) == evidence
    assert hashlib.sha256(bytes.fromhex(case["soeHex"])).hexdigest() == evidence["payloadSha256"]
    assignment = next(json.loads(text) for text in DOCUMENT["inventory"]["assignmentTexts"]
                      if json.loads(text)["assignment"] == evidence["assignment"])
    assert evidence["inputs"] == assignment["inputs"]
    for key in ("assignment", "site", "generation", "fence", "fragmentHash"):
        assert evidence[key] == assignment[key]
    assert base64.b64encode(bytes.fromhex(case["soeHex"])).decode("ascii") == record["value"]


def test_shared_case_inventory_and_revision_metadata_are_explicit():
    assert DOCUMENT["schema"] == "opentsx.testvectors.aggregate-transport.v1"
    assert len(CASES) == 4
    assert len(DOCUMENT["rejections"]) == 8
    incomplete, complete = [case["record"]["headers"] for case in CASES if case["kind"] == "merged"]
    assert (incomplete["tsx-revision"], incomplete["tsx-final"], incomplete["tsx-missing-sites"]) == ("0", "false", "s3")
    assert (complete["tsx-revision"], complete["tsx-final"], complete["tsx-missing-sites"]) == ("1", "true", "")
