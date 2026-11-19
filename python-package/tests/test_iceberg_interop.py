"""Cross-language check, step 2: read the Iceberg warehouse written by Java (IcebergExportForPythonTest).

Every test needs pyiceberg; the export tests also need OPENTSX_EXPORT_DIR pointing to that export (CI job
"cross-language").
"""

import json
import os
import pathlib
from datetime import datetime, timezone

import pytest

EXPORT = os.environ.get("OPENTSX_EXPORT_DIR")
needs_export = pytest.mark.skipif(not EXPORT, reason="needs OPENTSX_EXPORT_DIR from the Java export test")


@pytest.fixture(scope="module")
def catalog():
    iceberg = pytest.importorskip("opentsx.store.iceberg")
    d = pathlib.Path(EXPORT).resolve()
    return iceberg.load_catalog("opentsx", type="sql", uri=f"sqlite:///{d / 'catalog.db'}",
                                warehouse=(d / "warehouse").as_uri())


@needs_export
def test_python_reads_java_episodes_byte_identically(catalog):
    from opentsx import model as m
    from opentsx.store.iceberg import read_episodes

    expected = json.loads((pathlib.Path(EXPORT) / "expected.json").read_text())
    episodes = read_episodes(catalog)
    assert {e["episodeId"] for e in episodes} == set(expected)
    for e in episodes:
        assert m.encode("Episode", e).hex() == expected[e["episodeId"]], e["episodeId"]


@needs_export
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


class _CountingCatalog:
    def __init__(self):
        self.loads = 0

    def load_table(self, name):
        self.loads += 1
        raise AssertionError(f"load_table({name!r}) before the bounds were checked")


@pytest.mark.parametrize(
    "bounds, message",
    [
        ({"start": datetime(2026, 10, 1)}, "start: 2026-10-01T00:00:00 has no time zone"),
        ({"end": "2026-10-02T00:00:00"}, "end: '2026-10-02T00:00:00' has no time zone"),
        ({"end": "yesterday"}, "end: 'yesterday' is not an ISO 8601 time"),
    ],
)
def test_read_episodes_checks_the_bounds_before_it_loads_the_table(bounds, message):
    pytest.importorskip("pyiceberg")
    from opentsx.store.iceberg import read_episodes

    catalog = _CountingCatalog()
    with pytest.raises(ValueError) as e:
        read_episodes(catalog, **{"start": datetime(2026, 10, 1, tzinfo=timezone.utc), **bounds})
    assert str(e.value) == (
        f"{message}; expected a timezone-aware datetime, an ISO 8601 time with an offset such as "
        "2026-10-01T00:00:00Z, or epoch microseconds"
    )
    assert catalog.loads == 0


def test_read_episodes_filters_on_the_same_instant_in_every_form():
    pytest.importorskip("pyiceberg")
    from opentsx.store.iceberg import read_episodes

    filters = []

    class Table:
        def scan(self, row_filter):
            filters.append(repr(row_filter))
            return self

        def to_arrow(self):
            return self

        def to_pylist(self):
            return []

    class Catalog:
        def load_table(self, name):
            return Table()

    t = datetime(2026, 10, 1, tzinfo=timezone.utc)
    for start in (t, "2026-10-01T00:00:00Z", "2026-10-01T02:00:00+02:00", 1_790_812_800_000_000):
        assert read_episodes(Catalog(), start=start, end=1_790_899_200_000_000) == []
    assert len(set(filters)) == 1, filters
