"""Cross-language check, step 2: read the Iceberg warehouse written by Java (IcebergExportForPythonTest).

Runs when OPENTSX_EXPORT_DIR points to that export and pyiceberg is installed (CI job "cross-language").
"""

import json
import os
import pathlib

import pytest

EXPORT = os.environ.get("OPENTSX_EXPORT_DIR")
pytestmark = pytest.mark.skipif(not EXPORT, reason="needs OPENTSX_EXPORT_DIR from the Java export test")


@pytest.fixture(scope="module")
def catalog():
    iceberg = pytest.importorskip("opentsx.store.iceberg")
    d = pathlib.Path(EXPORT).resolve()
    return iceberg.load_catalog("opentsx", type="sql", uri=f"sqlite:///{d / 'catalog.db'}",
                                warehouse=(d / "warehouse").as_uri())


def test_python_reads_java_episodes_byte_identically(catalog):
    from opentsx import model as m
    from opentsx.store.iceberg import read_episodes

    expected = json.loads((pathlib.Path(EXPORT) / "expected.json").read_text())
    episodes = read_episodes(catalog)
    assert {e["episodeId"] for e in episodes} == set(expected)
    for e in episodes:
        assert m.encode("Episode", e).hex() == expected[e["episodeId"]], e["episodeId"]


def test_filters_and_pandas_frame(catalog):
    from opentsx.store.iceberg import read_episodes, read_frame

    t0 = 1_790_000_000_000_000
    wind = read_episodes(catalog, metric="wind.turbine.wind_speed", tags={"turbine": "T02"})
    assert wind and all(e["series"]["tags"]["turbine"] == "T02" for e in wind)

    window = read_episodes(catalog, start=t0 + 70_000_000, end=t0 + 130_000_000, metric="wind.turbine.wind_speed")
    assert all(e["tStart"] < t0 + 130_000_000 and e["tEnd"] > t0 + 70_000_000 for e in window)

    df = read_frame(catalog, metric="wind.turbine.power_active")
    assert str(df["ts"].dt.tz) == "UTC"
    assert df["value"].isna().sum() == 1  # the NaN of the fixture survives Java -> Iceberg -> Python
