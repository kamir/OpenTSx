"""The notebooks shipped with the package are English, their code runs against a retrieval server, and they write
nothing to the broker."""
import ast
import json
import pathlib
import re
import shutil
import sys
import threading
import types
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, HTTPServer

import numpy as np
import pandas as pd
import pytest

from opentsx import model as m
from opentsx.model.pandas_io import series_to_episode

DIR = pathlib.Path(__file__).parent.parent / "notebooks"
NOTEBOOKS = sorted(DIR.glob("*.ipynb"))
SAMPLE = DIR / "beispiel-windmessung.csv"

HEADINGS = {
    "01-zeitreihen-inventar.ipynb": [
        "# Notebook 1: The time series inventory", "## 1. The inventory",
        "## 2. What is measured and what is reported", "## 3. One series as a curve", "## 4. Coverage and gaps",
        "## 5. The electricity price of 2025", "## Where to go next"],
    "02-ein-signal.ipynb": [
        "# Notebook 2: A signal next to its expectation", "## 1. The signals of the system", "## 2. Choose a signal",
        "## 3. Raw and clean, one over the other", "## 4. What the cleaning changed",
        "## 5. What the decision says about its inputs"],
    "03-verproben.ipynb": [
        "# Notebook 3: Check a file of your own", "## 1. Read the file", "## 2. What the system expects",
        "## 3. The checks", "## 4. Next to the series of the system", "## 5. The verdict and the binding"],
    "04-den-zwilling-fragen.ipynb": [
        "# Notebook 4: Ask the twin", "## 1. What can be asked", "## 2. One question",
        "## 3. The same question at many times of day", "## What these answers support"],
}
GERMAN = re.compile(r"[äöüÄÖÜß]|\b(?:und|oder|der|den|dem|des|das|ein|eine|einen|ist|sind|nicht|wird|werden|"
                    r"nach|auf|mit|von|bis|für|über|noch|nur|sich|zum|zur|Heft|Reihe|Reihen|Werte|Lauf|Datei|roh|"
                    r"bereinigt|Urteil|Grund|warnung|fehler|Einheit|Takt|Weg)\b")


def _cells(path):
    cells = json.loads(path.read_text(encoding="utf-8"))["cells"]
    return ["".join(c["source"]) for c in cells if c["cell_type"] == "code"]


def _run(path, ns):
    for i, code in enumerate(_cells(path)):
        exec(compile(code, f"{path.name}[{i}]", "exec"), ns)


def test_there_are_notebooks():
    assert [p.name for p in NOTEBOOKS] == list(HEADINGS)
    # the example notebook 3 tests against ships with it, and says what it is
    text = SAMPLE.read_text(encoding="utf-8")
    assert "Not a measurement of an operator." in text
    assert [line for line in text.splitlines() if not line.startswith("#")][0] == "date;time;wind_kmh"


def test_the_headings_are_english():
    for p in NOTEBOOKS:
        nb = json.loads(p.read_text(encoding="utf-8"))
        headings = [line for c in nb["cells"] if c["cell_type"] == "markdown"
                    for line in "".join(c["source"]).splitlines() if line.startswith("#")]
        assert headings == HEADINGS[p.name], p.name


def test_no_german_text_is_left():
    for p in NOTEBOOKS:
        for i, cell in enumerate(json.loads(p.read_text(encoding="utf-8"))["cells"]):
            found = GERMAN.search("".join(cell["source"]))
            assert found is None, f"{p.name}[{i}]: {found.group(0)!r}"
    header = "\n".join(line for line in SAMPLE.read_text(encoding="utf-8").splitlines()[:5] if not line[:1].isdigit())
    assert GERMAN.search(header) is None, header


def test_every_code_cell_compiles_and_no_output_is_shipped():
    for p in NOTEBOOKS:
        nb = json.loads(p.read_text(encoding="utf-8"))
        assert nb["nbformat"] == 4
        for i, cell in enumerate(nb["cells"]):
            if cell["cell_type"] == "code":
                ast.parse("".join(cell["source"]), filename=f"{p.name}[{i}]")
                assert cell["outputs"] == [], f"{p.name}[{i}] ships output"


def test_the_notebooks_write_nothing():
    for p in NOTEBOOKS:
        text = p.read_text(encoding="utf-8")
        # a run computes on the server and is kept there; nothing goes to the broker
        for forbidden in ("Producer(", ".produce(", "KafkaProducer"):
            assert forbidden not in text, f"{p.name} contains {forbidden}"


@pytest.fixture()
def without_matplotlib(monkeypatch):
    """Reading the file plots nothing, so the import of pyplot in the first cell needs no matplotlib."""
    plt = types.ModuleType("matplotlib.pyplot")
    monkeypatch.setitem(sys.modules, "matplotlib", types.SimpleNamespace(pyplot=plt))
    monkeypatch.setitem(sys.modules, "matplotlib.pyplot", plt)


def _read_sample(tmp_path, monkeypatch, header=None, **settings):
    lines = SAMPLE.read_text(encoding="utf-8").splitlines(keepends=True)
    at = next(i for i, line in enumerate(lines) if not line.startswith("#"))
    if header is not None:
        lines[at] = header + "\n"
    (tmp_path / SAMPLE.name).write_text("".join(lines), encoding="utf-8")
    monkeypatch.chdir(tmp_path)
    configure, read = _cells(DIR / "03-verproben.ipynb")[:2]
    ns = {}
    exec(compile(configure, "configure", "exec"), ns)
    ns.update(settings)
    exec(compile(read, "read", "exec"), ns)
    return ns["data"]


def test_notebook_3_reads_the_english_and_the_german_header(tmp_path, monkeypatch, without_matplotlib):
    english = _read_sample(tmp_path, monkeypatch, header="date;time;wind_kmh")
    german = _read_sample(tmp_path, monkeypatch, header="Datum;Uhrzeit;Wind_kmh")
    assert len(english) == 739
    assert english.index[0] == pd.Timestamp("2025-10-01T00:00:00Z") and english.max() == 999.0
    pd.testing.assert_series_equal(english, german)
    # the columns a user names for a German file are found as written
    named = _read_sample(tmp_path, monkeypatch, header="Datum;Uhrzeit;Wind_kmh", TIME_COLUMNS=["Datum", "Uhrzeit"],
                         VALUE="Wind_kmh")
    pd.testing.assert_series_equal(english, named)


def test_notebook_3_refuses_a_missing_column_and_a_file_without_values(tmp_path, monkeypatch, without_matplotlib):
    with pytest.raises(ValueError) as e:
        _read_sample(tmp_path, monkeypatch, VALUE="speed")
    assert str(e.value) == 'VALUE: the file has no column "speed"; expected one of date, time, wind_kmh'
    with pytest.raises(ValueError) as e:
        _read_sample(tmp_path, monkeypatch, TIME_FORMAT="%Y-%m-%d %H:%M")
    assert str(e.value) == "FILE: no readable values; check SEP, DECIMAL, TIME_COLUMNS, TIME_FORMAT and VALUE"


T0 = pd.Timestamp("2025-10-01T00:00:00Z")
WIND = m.series_key("weather.wind_speed_10min", {"station": "01759"}, "m/s")
PRICE = m.series_key("market.price.day_ahead", {"area": "DE-LU"}, "EUR/MWh")
SIGNAL = "park/DWD-01759/wind_speed"


def _daily(key, s):
    return [series_to_episode(day, key, episode_id=f"{key['metric']}-{n}", source="test",
                              created_at_us=T0.value // 1000)
            for n, (_, day) in enumerate(s.groupby(s.index.floor("1D"))) if len(day)]


def _stream(episodes):
    return b"".join(len(raw).to_bytes(4, "big") + raw for raw in (m.encode("Episode", e) for e in episodes))


def _wind():
    idx = pd.date_range(T0, "2025-11-01T00:00:00Z", freq="10min", inclusive="left")
    raw = pd.Series(6 + 4 * np.sin(np.arange(len(idx)) / 40.0), index=idx)
    raw = raw.drop(raw["2025-10-10T04:00:00Z":"2025-10-10T04:50:00Z"].index)
    raw["2025-10-20T13:00:00Z"] = 99.0
    return _daily(WIND, raw), _daily(WIND, raw.drop(pd.Timestamp("2025-10-20T13:00:00Z")))


def _price():
    hourly = pd.Series(np.linspace(80, -5, 46), index=pd.date_range("2025-09-29T00:00:00Z", periods=46, freq="1h"))
    quarter = pd.Series(np.linspace(60, 90, 104),
                        index=pd.date_range("2025-09-30T22:00:00Z", periods=104, freq="15min"))
    return [series_to_episode(hourly, PRICE, episode_id="price-h", source="test", created_at_us=T0.value // 1000),
            series_to_episode(quarter, PRICE, episode_id="price-q", source="test", created_at_us=T0.value // 1000)]


INVENTORY = {"source": {"kind": "kafscale"}, "series": [
    {"seriesId": WIND["seriesId"], "metric": WIND["metric"], "unit": "m/s", "from": "2025-10-01T00:00:00Z",
     "to": "2025-11-01T00:00:00Z", "samples": 4458, "missing": 6, "tags": {"station": "01759"},
     "labels": {"origin": ["MEASURED"]}},
    {"seriesId": PRICE["seriesId"], "metric": PRICE["metric"], "unit": "EUR/MWh", "from": "2025-09-29T00:00:00Z",
     "to": "2025-10-02T00:00:00Z", "samples": 150, "missing": 0, "tags": {"area": "DE-LU"},
     "labels": {"origin": ["REPORTED"]}},
    {"seriesId": "t07", "metric": "wind.turbine.power", "unit": "kW", "from": "2025-10-01T00:00:00Z",
     "to": "2025-10-02T00:00:00Z", "samples": 1440, "missing": 0, "tags": {"turbine": "T07"},
     "labels": {"origin": ["SYNTHETIC"]}},
]}
SIGNALS = {"signals": [{
    "id": SIGNAL, "title": "Wind at the park", "role": "exogenous", "state": "clean", "origin": "MEASURED",
    "via": "topic", "meaning": "wind speed 10 m above ground at the DWD station next to the park",
    "expect": {"unit": "m/s", "min": 0, "max": 75, "interval": "PT10M", "operating": {"min": 3, "max": 25}},
    "grades": {"raw": {"data": {"series": ["w-raw"]}}, "clean": {"data": {"series": ["w-clean"]}}}, "next": ["bind"]}]}
DECISIONS = {"decisions": [{
    "title": "Run the electrolyser tonight", "asOf": "2025-10-31T23:50:00Z",
    "needs": [{"signal": SIGNAL, "grade": "clean", "ok": True, "reasons": []},
              {"signal": "offtake.demand_kgph", "ok": False, "reasons": ["origin SYNTHETIC is not allowed"]}],
    "analyses": [{"title": "Night plan", "status": "restricted",
                  "reasons": ["offtake.demand_kgph: origin SYNTHETIC is not allowed"]}]}]}
SCENARIO = {"title": "Hydrogen chain, one night", "decision": "When must the valve be open again?",
            "description": "", "start": "2025-10-01T00:00:00Z", "steps": 360, "stepSeconds": 60,
            "referenceDecisions": [{"clock": "01:00:00", "atom": "handover", "control": "valve", "value": 0,
                                    "reason": "the offtaker closes for maintenance"}],
            "controls": [{"atom": "handover", "control": "valve", "default": 1, "unit": ""}]}


def _answer_run(body):
    at = body["interventions"][0]["at"]
    minute = int(at[:2]) * 60 + int(at[3:])
    late = max(0, minute - 60) / 2
    return {"run": f"r{minute}", "label": f"valve open at {at}", "baseline": "reference-on-signals",
            "status": {"usability": "restricted", "reasons": ["the offtake demand is a demo generator"]},
            "inputs": [{"port": "park.wind", "source": SIGNAL, "origin": "MEASURED", "coverage": 1.0}],
            "kpis": {"ely.trips": 0 if minute <= 120 else 1, "buffer.p_max_bar": 150 + late,
                     "handover.delivered_kg": 100 - late, "handover.shortfall_kg": late}}


COMPARE = {"start": "2025-10-01T00:00:00Z", "stepSeconds": 60,
           "kpis": [{"label": "delivered", "base": 100.0, "run": 70.0, "delta": -30.0, "unit": "kg",
                     "verdict": "worse"}],
           "series": {"buffer.p_bar": {"label": "buffer pressure", "base": [120, None, 121], "run": [120, 130, 140],
                                       "limits": [{"value": 200}]},
                      "handover.flow_kgph": {"label": "handover flow", "base": [5, 5, 5], "run": [0, 0, 5],
                                             "limits": []}}}


@pytest.fixture()
def retrieval(monkeypatch, tmp_path):
    raw, clean = _wind()
    episodes = {("metric", WIND["metric"]): raw, ("metric", PRICE["metric"]): _price(),
                ("series", "w-raw"): raw, ("series", "w-clean"): clean}
    answers = {"/v1/health": {"status": "ok"}, "/v1/inventory": INVENTORY,
               "/v1/systems/h2-chain/signals": SIGNALS, "/v1/systems/h2-chain/decisions": DECISIONS,
               "/v1/systems/h2-chain/scenario": SCENARIO,
               "/v1/systems/h2-chain/runs/r120/compare": COMPARE}

    class H(BaseHTTPRequestHandler):
        def log_message(self, *a):
            pass

        def _send(self, raw, status=200, kind="application/json"):
            self.send_response(status)
            self.send_header("Content-Type", kind)
            self.send_header("Content-Length", str(len(raw)))
            self.end_headers()
            self.wfile.write(raw)

        def do_GET(self):
            url = urllib.parse.urlsplit(self.path)
            q = dict(urllib.parse.parse_qsl(url.query))
            if url.path == "/v1/episodes":
                found = [eps for (k, v), eps in episodes.items() if q.get(k) == v]
                if found:
                    return self._send(_stream(found[0]), kind="application/x-opentsx-avro-stream")
            elif url.path in answers:
                return self._send(json.dumps(answers[url.path]).encode())
            self._send(json.dumps({"error": f"{self.path} is unknown", "type": "not_found"}).encode(), 404)

        def do_POST(self):
            body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
            self._send(json.dumps(_answer_run(body)).encode())

    srv = HTTPServer(("127.0.0.1", 0), H)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    monkeypatch.setenv("OPENTSX_RETRIEVAL", f"http://127.0.0.1:{srv.server_port}")
    monkeypatch.delenv("KAFKA_BOOTSTRAP", raising=False)
    monkeypatch.delenv("OPENTSX_DOMAIN", raising=False)
    shutil.copy(SAMPLE, tmp_path / SAMPLE.name)
    monkeypatch.chdir(tmp_path)
    yield f"http://127.0.0.1:{srv.server_port}"
    srv.shutdown()
    srv.server_close()


def test_notebook_1_stops_in_its_first_cell_without_a_server_or_a_broker(monkeypatch, without_matplotlib):
    def refused(url, timeout):
        raise OSError("connection refused")

    monkeypatch.setattr(urllib.request, "urlopen", refused)
    monkeypatch.setenv("OPENTSX_RETRIEVAL", "http://127.0.0.1:9")
    monkeypatch.delenv("KAFKA_BOOTSTRAP", raising=False)
    first = _cells(DIR / "01-zeitreihen-inventar.ipynb")[0]
    with pytest.raises(RuntimeError) as e:
        exec(compile(first, "01-zeitreihen-inventar.ipynb[0]", "exec"), {"__name__": "__main__"})
    assert str(e.value) == ("OPENTSX_RETRIEVAL: the retrieval server http://127.0.0.1:9 cannot be reached and "
                            "KAFKA_BOOTSTRAP is not set; start the retrieval server or set KAFKA_BOOTSTRAP to a broker")


def test_notebook_3_shifts_a_copy_and_leaves_the_series_of_the_file_as_read(retrieval, without_matplotlib):
    configure, read, signal, checks = _cells(DIR / "03-verproben.ipynb")[:4]
    ns = {"__name__": "__main__", "display": print}
    exec(compile(configure, "configure", "exec"), ns)
    ns.update(FILE_UNIT="mph", TIME_MEANS="end")
    exec(compile(read, "read", "exec"), ns)
    exec(compile(signal, "signal", "exec"), ns)
    as_read = ns["data"].index.copy()
    exec(compile(checks, "checks", "exec"), ns)
    assert {"check": "Unit", "verdict": "error", "reason": "no conversion from mph to m/s is known"} in ns["checks"]
    pd.testing.assert_index_equal(ns["data"].index, as_read)
    pd.testing.assert_index_equal(ns["converted"].index, as_read - ns["step"])


@pytest.mark.filterwarnings("ignore:FigureCanvasAgg is non-interactive")
@pytest.mark.parametrize("name, says", [
    ("01-zeitreihen-inventar.ipynb", ["Path A: retrieval server http://127.0.0.1:", "3 series",
                                      "4458 values", "6 missing values in 1 gaps", "negative prices: 3 intervals"]),
    ("02-ein-signal.ipynb", ["raw: 4458 values, clean: 4457 values", "changed into a gap: 1",
                             "Run the electrolyser tonight (data as of 2025-10-31T23:50:00Z)",
                             "Night plan: restricted"]),
    ("03-verproben.ipynb", ["739 rows read, 0 times unreadable, 0 values unreadable",
                            "Verdict: bindable, with warnings", "warning: Plausible range: 1 values outside 0 to 75",
                            "prepared: ", "note: From a file of the operator, checked on "]),
    ("04-den-zwilling-fragen.ipynb", ["Hydrogen chain, one night", "Run r120 | valve open at 02:00",
                                      "compared with reference-on-signals", "No protective trip up to: 02:00"]),
])
def test_the_notebook_runs_and_speaks_english(name, says, retrieval, tmp_path, capsys):
    matplotlib = pytest.importorskip("matplotlib")
    matplotlib.use("Agg", force=True)
    import matplotlib.pyplot as plt

    try:
        _run(DIR / name, {"__name__": "__main__", "display": print})
    finally:
        plt.close("all")
    out = capsys.readouterr().out
    for text in says:
        assert text in out, f"{name} does not say {text!r}:\n{out}"
    found = GERMAN.search(out)
    assert found is None, f"{name} says {found.group(0)!r}:\n{out}"
    if name == "03-verproben.ipynb":
        assert (tmp_path / "checked-park_DWD-01759_wind_speed.csv").read_text().startswith("start,value\n")
