"""Immutable regular context records and bounded causal joins."""

import copy
import hashlib
import json
import math

SCHEMA = "opentsx.forecast-context.v1"
POLICY = "immutable_known_ahead"
MAX_INTEGER = 2**53 - 1
MAX_ROWS = 2048
MAX_BUFFER_ROWS = 4096
MAX_BYTES = 4 * 1024 * 1024
FIELDS = {
    "schema",
    "record_id",
    "target_series_id",
    "context_names",
    "context_units",
    "t_start_micros",
    "interval_micros",
    "issued_at_micros",
    "values",
    "available_at_micros",
    "revision",
}


def canonical(value):
    try:
        return json.dumps(value, sort_keys=True, separators=(",", ":"), allow_nan=False).encode()
    except (ValueError, TypeError, OverflowError) as error:
        raise ValueError("context must contain finite JSON data only") from error


def integer(value, name, low=-MAX_INTEGER, high=MAX_INTEGER):
    if type(value) is not int or not low <= value <= high:
        raise ValueError(f"{name} must be an exact bounded integer")
    return value


def _text(value, name):
    if not isinstance(value, str) or not value or len(value) > 512:
        raise ValueError(f"{name} must be a nonempty string of at most 512 characters")


def schema_descriptor(target_series_id, names, units, interval):
    _text(target_series_id, "context target series")
    if (
        not isinstance(names, list)
        or not 1 <= len(names) <= 8
        or not isinstance(units, list)
        or len(units) != len(names)
    ):
        raise ValueError("context schema requires one to eight named columns and matching units")
    for name in names:
        _text(name, "context name")
    for unit in units:
        _text(unit, "context unit")
    if len(set(names)) != len(names):
        raise ValueError("context names must be unique")
    integer(interval, "context interval", 1)
    value = {
        "policy": POLICY,
        "target_series_id": target_series_id,
        "names": list(names),
        "units": list(units),
        "interval_micros": interval,
    }
    return {"schema_id": hashlib.sha256(canonical(value)).hexdigest(), **value}


def record_id(record):
    if not isinstance(record, dict):
        raise ValueError("context record must be an object")
    return hashlib.sha256(
        canonical({key: value for key, value in record.items() if key != "record_id"})
    ).hexdigest()


def validate_record(record):
    if not isinstance(record, dict) or set(record) != FIELDS:
        raise ValueError("context record has missing or unsupported fields")
    if record["schema"] != SCHEMA:
        raise ValueError("unsupported context record schema")
    if record["record_id"] != record_id(record):
        raise ValueError("context record integrity digest does not match")
    if len(canonical(record)) > MAX_BYTES:
        raise ValueError("context record exceeds the four MiB limit")
    if type(record["revision"]) is not int or record["revision"] != 0:
        raise ValueError("context revisions require a new evaluated deployment")
    schema_descriptor(
        record["target_series_id"],
        record["context_names"],
        record["context_units"],
        record["interval_micros"],
    )
    start = integer(record["t_start_micros"], "context start")
    issued = integer(record["issued_at_micros"], "context issue time")
    values, available = record["values"], record["available_at_micros"]
    if (
        not isinstance(values, list)
        or not 1 <= len(values) <= MAX_ROWS
        or not isinstance(available, list)
        or len(available) != len(values)
    ):
        raise ValueError("context requires one to 2048 matching value and availability rows")
    columns = len(record["context_names"])
    for row, times in zip(values, available):
        if (
            not isinstance(row, list)
            or len(row) != columns
            or not isinstance(times, list)
            or len(times) != columns
        ):
            raise ValueError("context column dimensions differ from its schema")
        for value, stamp in zip(row, times):
            try:
                finite = type(value) in (int, float) and math.isfinite(value)
            except OverflowError as error:
                raise ValueError("context values must be finite real numbers") from error
            if not finite:
                raise ValueError("context values must be finite real numbers")
            if integer(stamp, "context availability") < issued:
                raise ValueError("context issue time must not exceed cell availability")
    integer(start + len(values) * record["interval_micros"], "context exclusive end")
    return record


def make_record(
    target_series_id,
    context_names,
    context_units,
    t_start_micros,
    interval_micros,
    issued_at_micros,
    values,
    available_at_micros,
    *,
    revision=0,
):
    record = copy.deepcopy(
        {
            "schema": SCHEMA,
            "target_series_id": target_series_id,
            "context_names": context_names,
            "context_units": context_units,
            "t_start_micros": t_start_micros,
            "interval_micros": interval_micros,
            "issued_at_micros": issued_at_micros,
            "values": values,
            "available_at_micros": available_at_micros,
            "revision": revision,
        }
    )
    record["record_id"] = record_id(record)
    return validate_record(record)


def encode_record(record):
    validate_record(record)
    return canonical(record)


def _unique_fields(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("context JSON contains duplicate fields")
        result[key] = value
    return result


def _reject_constant(value):
    raise ValueError("context JSON contains nonfinite constants")


def decode_record(raw):
    if not isinstance(raw, bytes) or not 0 < len(raw) <= MAX_BYTES:
        raise ValueError("context record exceeds the payload limit or is empty")
    try:
        record = json.loads(raw, object_pairs_hook=_unique_fields, parse_constant=_reject_constant)
    except UnicodeError as error:
        raise ValueError("context source is not valid UTF-8 JSON") from error
    return validate_record(record)


def record_rows(record, offset):
    validate_record(record)
    integer(offset, "context source offset", 0, 2**63 - 1)
    return [
        {
            "timestamp_micros": record["t_start_micros"] + index * record["interval_micros"],
            "values": list(values),
            "available_at_micros": list(available),
            "issued_at_micros": record["issued_at_micros"],
            "record_id": record["record_id"],
            "offset": str(offset),
        }
        for index, (values, available) in enumerate(
            zip(record["values"], record["available_at_micros"])
        )
    ]


def append_record(rows, record, offset, descriptor, watermark_micros=None):
    validate_record(record)
    actual = schema_descriptor(
        record["target_series_id"],
        record["context_names"],
        record["context_units"],
        record["interval_micros"],
    )
    if actual != descriptor:
        raise ValueError("context record schema differs from the pinned deployment")
    if watermark_micros is not None and record["t_start_micros"] != watermark_micros:
        raise ValueError("context stream contains a gap or overlap")
    if not isinstance(rows, list) or len(rows) + len(record["values"]) > MAX_BUFFER_ROWS:
        raise ValueError("context lookahead exceeds the 4096-row buffer limit")
    return (
        copy.deepcopy(rows) + record_rows(record, offset),
        record["t_start_micros"] + len(record["values"]) * record["interval_micros"],
    )


class MissingContext(ValueError):
    def __init__(self, start, end, cutoff):
        super().__init__("context required by the pending target has not arrived")
        self.missing_start_micros = start
        self.missing_end_micros = end
        self.cutoff_micros = cutoff


def select_rows(rows, start, count, interval, cutoffs=None):
    integer(start, "context selection start")
    integer(count, "context selection count", 1, MAX_BUFFER_ROWS)
    integer(interval, "context selection interval", 1)
    if isinstance(cutoffs, list) and len(cutoffs) != count:
        raise ValueError("context cutoffs must match selected rows")
    by_time = {row["timestamp_micros"]: row for row in rows}
    if len(by_time) != len(rows):
        raise ValueError("context cache contains duplicate valid times")
    selected = []
    for index in range(count):
        timestamp = integer(start + index * interval, "context valid time")
        cutoff = cutoffs[index] if isinstance(cutoffs, list) else cutoffs
        if cutoff is not None:
            integer(cutoff, "context cutoff")
        if timestamp not in by_time:
            raise MissingContext(timestamp, timestamp + interval, cutoff)
        row = by_time[timestamp]
        if cutoff is not None and any(stamp > cutoff for stamp in row["available_at_micros"]):
            raise ValueError("context was not available by the required forecast cutoff")
        selected.append(row)
    positions = {(row["offset"], row["record_id"]) for row in selected}
    return {
        "values": [list(row["values"]) for row in selected],
        "available_at_micros": [list(row["available_at_micros"]) for row in selected],
        "source_positions": [
            {"offset": offset, "record_id": identity}
            for offset, identity in sorted(positions, key=lambda item: int(item[0]))
        ],
        "rows": copy.deepcopy(selected),
    }
