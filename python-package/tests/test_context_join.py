import copy
import importlib
import json

import pytest


def api():
    return importlib.import_module("opentsx.client.context_join")


def record(start=1000, rows=4):
    return api().make_record(
        "series-1",
        ["schedule", "calendar"],
        ["MW", "1"],
        start,
        10,
        900,
        [[float(i), float(i + 2)] for i in range(rows)],
        [[900, 900] for _ in range(rows)],
    )


def descriptor():
    return api().schema_descriptor("series-1", ["schedule", "calendar"], ["MW", "1"], 10)


def test_record_round_trip_and_descriptor_are_immutable_data_only():
    value = record()
    original = copy.deepcopy(value)
    assert value["record_id"] == api().record_id(value)
    assert json.loads(api().encode_record(value)) == value
    assert api().validate_record(value) == value
    schema = descriptor()
    assert schema["policy"] == "immutable_known_ahead"
    assert schema["names"] == value["context_names"]
    assert schema["units"] == value["context_units"]
    assert value == original


@pytest.mark.parametrize(
    "change",
    [
        "tamper",
        "revision",
        "unit",
        "duplicate",
        "nonfinite",
        "shape",
        "availability",
        "issued",
        "clock",
        "unknown",
    ],
)
def test_invalid_context_records_fail_closed(change):
    value = record()
    if change == "tamper":
        value["values"][0][0] += 1
    elif change == "revision":
        value["revision"] = 1
    elif change == "unit":
        value["context_units"][0] = ""
    elif change == "duplicate":
        value["context_names"][1] = value["context_names"][0]
    elif change == "nonfinite":
        value["values"][0][0] = float("inf")
    elif change == "shape":
        value["available_at_micros"].pop()
    elif change == "availability":
        value["available_at_micros"][0][0] = True
    elif change == "issued":
        value["issued_at_micros"] = 901
    elif change == "clock":
        value["t_start_micros"] = 2**53
    else:
        value["executable"] = "unsupported"
    if change not in ("tamper", "nonfinite"):
        value["record_id"] = api().record_id(value)
    with pytest.raises(ValueError):
        api().validate_record(value)


def test_append_and_select_use_exact_valid_times_and_per_cell_cutoffs():
    first = record()
    rows, watermark = api().append_record([], first, 3, descriptor())
    second = record(1040, 4)
    second["available_at_micros"] = [[1030 + i * 10] * 2 for i in range(4)]
    second["record_id"] = api().record_id(second)
    rows, watermark = api().append_record(rows, second, 4, descriptor(), watermark)
    result = api().select_rows(rows, 1030, 4, 10, [1020, 1030, 1040, 1050])
    assert result["values"] == [first["values"][3], *second["values"][:3]]
    assert result["source_positions"] == [
        {"offset": "3", "record_id": first["record_id"]},
        {"offset": "4", "record_id": second["record_id"]},
    ]
    assert watermark == 1080
    with pytest.raises(ValueError, match="available"):
        api().select_rows(rows, 1040, 2, 10, 1029)


def test_missing_tail_has_an_explicit_range_without_changing_rows():
    rows, _ = api().append_record([], record(), 0, descriptor())
    original = copy.deepcopy(rows)
    with pytest.raises(api().MissingContext) as failure:
        api().select_rows(rows, 1030, 3, 10, 1020)
    assert failure.value.missing_start_micros == 1040
    assert failure.value.missing_end_micros == 1050
    assert failure.value.cutoff_micros == 1020
    assert rows == original


@pytest.mark.parametrize("start", [1030, 1050])
def test_overlapping_or_gapped_records_do_not_change_the_context_cache(start):
    rows, watermark = api().append_record([], record(), 0, descriptor())
    original = copy.deepcopy(rows)
    with pytest.raises(ValueError, match="gap|overlap"):
        api().append_record(rows, record(start), 1, descriptor(), watermark)
    assert rows == original


def test_schema_column_and_unit_changes_are_rejected():
    value = record()
    for field in ("context_names", "context_units"):
        changed = copy.deepcopy(value)
        changed[field].reverse()
        changed["record_id"] = api().record_id(changed)
        with pytest.raises(ValueError, match="schema"):
            api().append_record([], changed, 0, descriptor())


@pytest.mark.parametrize("constant", ["NaN", "Infinity", "-Infinity"])
def test_decode_rejects_nonfinite_json_constants_at_the_boundary(constant):
    raw = (
        api().encode_record(record()).replace(b'"revision":0', ('"revision":' + constant).encode())
    )
    with pytest.raises(ValueError, match="nonfinite"):
        api().decode_record(raw)


def test_decode_rejects_duplicate_fields_and_invalid_utf8():
    raw = api().encode_record(record())
    with pytest.raises(ValueError, match="duplicate"):
        api().decode_record(b'{"revision":1,' + raw[1:])
    with pytest.raises(ValueError, match="UTF-8"):
        api().decode_record(b"\xff")
    assert api().decode_record(raw) == record()
