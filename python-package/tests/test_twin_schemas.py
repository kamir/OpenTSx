"""The twin's Avro topic contract (generated in Java, opentsx-twin/src/main/resources/twin/avro) is usable from Python."""
import io
import json
import math
from pathlib import Path

import pytest

fastavro = pytest.importorskip("fastavro")

AVRO_DIR = Path(__file__).resolve().parents[2] / "opentsx-twin" / "src" / "main" / "resources" / "twin" / "avro"


def _schemas():
    if not AVRO_DIR.is_dir():
        pytest.skip("twin schemas not in this checkout")
    return sorted(AVRO_DIR.rglob("*.avsc"))


def test_all_schemas_parse():
    files = _schemas()
    assert len(files) >= 26
    for f in files:
        fastavro.parse_schema(json.loads(f.read_text(encoding="utf-8")))


def test_electrolyzer_snapshot_round_trip():
    _schemas()
    schema = fastavro.parse_schema(json.loads((AVRO_DIR / "atoms" / "electrolyzer.avsc").read_text(encoding="utf-8")))
    rec = {
        "run_id": "h2-chain-0",
        "atom": "ely",
        "k": 7,
        "t": 1_700_000_000_000_000,
        "state": {"p_el_kw": 950.0, "mode": 2.0, "h2_kg": 12.5, "energy_kwh": 300.0, "trips": 0.0},
        "state_extra": {},
        "outputs": {
            "p_el_kw": {"value": 950.0, "origin": "ESTIMATED"},
            "m_h2_kgph": {"value": 17.1, "origin": "ESTIMATED"},
        },
        "controls": {"setpoint_kw": None},
    }
    buf = io.BytesIO()
    fastavro.schemaless_writer(buf, schema, rec)
    buf.seek(0)
    back = fastavro.schemaless_reader(buf, schema)
    assert back["outputs"]["m_h2_kgph"]["origin"] == "ESTIMATED"
    assert math.isclose(back["state"]["h2_kg"], 12.5)
    assert back["controls"]["setpoint_kw"] is None
