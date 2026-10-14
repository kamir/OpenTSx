"""The parts of the package the notebooks use: inventory, systems, signals and runs over REST, and the topic reader."""
import json
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer
from types import SimpleNamespace

import numpy as np
import pandas as pd
import pytest

from opentsx import model as m
from opentsx.client import RetrievalClient, TopicLimitReached, TopicReader

T0 = 1_735_689_600_000_000  # 2025-01-01T00:00:00Z

INVENTORY = {"source": {"kind": "kafscale"}, "series": [
    {"seriesId": "b", "metric": "weather.wind_speed_10min", "unit": "m/s", "from": "2025-01-01T00:00:00Z",
     "to": "2026-01-01T00:00:00Z", "samples": 52360, "missing": 0, "min": 0, "max": 27.4, "intervalMicros": 600000000,
     "tags": {"station": "01759", "source": "dwd-cdc"}, "labels": {"origin": ["MEASURED"]}},
    {"seriesId": "a", "metric": "market.price.day_ahead", "unit": "EUR/MWh", "from": "2025-01-01T00:00:00Z",
     "to": "2026-01-01T00:00:00Z", "samples": 15390, "missing": 0, "min": -250, "max": 580,
     "tags": {"area": "DE-LU"}, "labels": {"origin": ["REPORTED"]}},
]}
SIGNALS = {"signals": [{"id": "market.price_eur_mwh", "title": "Strompreis", "role": "exogenous", "state": "raw-only",
                        "origin": "REPORTED", "via": "topic", "expect": {"unit": "EUR/MWh", "min": -500, "max": 3000},
                        "grades": {"raw": {"data": {"series": ["a"]}}}, "next": ["prepare"]}]}


@pytest.fixture()
def server():
    seen = {}

    class H(BaseHTTPRequestHandler):
        def log_message(self, *a):
            pass

        def _json(self, body, status=200):
            raw = json.dumps(body).encode()
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(raw)))
            self.end_headers()
            self.wfile.write(raw)

        def do_GET(self):
            seen.setdefault("get", []).append(self.path)
            if self.path == "/v1/inventory":
                self._json(INVENTORY)
            elif self.path == "/v1/systems":
                self._json({"systems": [{"system": "h2-chain", "title": "Wasserstoffkette"}]})
            elif self.path == "/v1/systems/h2-chain/signals":
                self._json(SIGNALS)
            elif self.path.startswith("/v1/systems/h2-chain/runs/r1/series"):
                self._json({"start": "2025-10-01T00:00:00Z", "stepSeconds": 60, "series": {
                    "buffer.p_bar": {"origin": "REPORTED", "values": [120.0, None, 121.0]}}})
            elif self.path == "/v1/systems/h2-chain/runs":
                self._json({"runs": [{"run": "r1", "kind": "what-if", "label": "x", "createdAt": "2026-10-14T00:00:00Z"}]})
            else:
                self._json({"error": "no"}, 404)

        def do_POST(self):
            seen["post"] = (self.path, json.loads(self.rfile.read(int(self.headers["Content-Length"]))))
            self._json({"run": "r1", "status": {"usability": "restricted"}})

    srv = HTTPServer(("127.0.0.1", 0), H)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    yield RetrievalClient(f"http://127.0.0.1:{srv.server_port}"), seen
    srv.shutdown()


def test_the_inventory_is_a_table_with_tags_and_origin(server):
    c, _ = server
    inv = c.inventory()
    assert list(inv["metric"]) == ["market.price.day_ahead", "weather.wind_speed_10min"]
    assert inv.loc[1, "tag.station"] == "01759"
    assert inv.loc[1, "origin"] == "MEASURED"
    assert inv.loc[1, "interval_s"] == 600
    assert inv["from"].dt.tz is not None
    assert inv.attrs["source"]["kind"] == "kafscale"


def test_systems_signals_and_runs(server):
    c, seen = server
    assert c.systems().loc[0, "system"] == "h2-chain"
    sig = c.signals("h2-chain")
    assert sig.loc[0, "raw_series"] == "a" and sig.loc[0, "origin"] == "REPORTED"
    r = c.run("h2-chain", [{"at": "02:00", "atom": "handover", "control": "valve", "value": 1}], policies={"ely": "pressure-aware"})
    assert r["status"]["usability"] == "restricted"
    assert seen["post"][0] == "/v1/systems/h2-chain/runs"
    assert seen["post"][1] == {"base": "reference", "interventions": [{"at": "02:00", "atom": "handover", "control": "valve", "value": 1}],
                               "policies": {"ely": "pressure-aware"}}
    c.run("h2-chain", inputs="signals")
    assert seen["post"][1] == {"base": "reference", "interventions": [], "inputs": "signals"}
    df = c.run_series("h2-chain", "r1", ["buffer.p_bar"])
    assert df.attrs["run"] == "r1"
    assert np.isnan(df["buffer.p_bar"].iloc[1])
    assert df.index[2] == pd.Timestamp("2025-10-01T00:02:00Z")
    assert list(c.runs("h2-chain")["run"]) == ["r1"]


class FakeConsumer:
    """Stands in for confluent_kafka.Consumer over a list of (partition, payload)."""

    def __init__(self, records):
        self.records = records
        self.queue = []
        self.closed = False

    def list_topics(self, topic, timeout):
        parts = {p for p, _ in self.records}
        return SimpleNamespace(topics={topic: SimpleNamespace(error=None, partitions={p: None for p in parts})})

    def get_watermark_offsets(self, tp, timeout):
        return 0, sum(1 for p, _ in self.records if p == tp.partition)

    def assign(self, tps):
        for tp in tps:
            own = [v for p, v in self.records if p == tp.partition]
            self.queue += [SimpleNamespace(error=lambda: None, partition=lambda p=tp.partition: p, offset=lambda o=i: o,
                                           value=lambda v=v: v) for i, v in enumerate(own)]

    def poll(self, timeout):
        return self.queue.pop(0) if self.queue else None

    def close(self):
        self.closed = True


def _episodes():
    wind = m.series_key("weather.wind_speed_10min", {"station": "01759"}, "m/s")
    price = m.series_key("market.price.day_ahead", {"area": "DE-LU"}, "EUR/MWh")
    day = 86_400_000_000
    eps = [m.regular(wind, T0 + d * day, 600_000_000, np.full(144, 5.0 + d), source="t") for d in range(3)]
    eps.append(m.regular(price, T0, 3_600_000_000, np.arange(24, dtype=float), source="t"))
    return eps


def test_the_topic_reader_filters_and_keeps_each_episode_once():
    eps = _episodes()
    records = [(0, m.encode("Episode", e)) for e in eps[:2]] + [(1, m.encode("Episode", eps[2])), (1, m.encode("Episode", eps[3])),
                                                                 (0, m.encode("Episode", eps[0]))]   # written twice
    holder = {}

    def factory(conf):
        holder["c"] = FakeConsumer(records)
        holder["conf"] = conf
        return holder["c"]

    r = TopicReader("broker:9092", consumer_factory=factory, timeout_s=1)
    got = r.episodes("tsx.wind.episodes.v2", metric="weather.wind_speed_10min", tags={"station": "01759"},
                     start="2025-01-02T00:00:00Z")
    assert [m.values(e)[0] for e in got] == [6.0, 7.0]
    assert holder["c"].closed
    assert holder["conf"]["enable.auto.commit"] is False
    every = r.episodes("tsx.wind.episodes.v2", metric="weather.wind_speed_10min")
    assert len(every) == 3, "an episode written twice is kept once"


def test_the_topic_reader_stops_at_its_limit():
    records = [(0, m.encode("Episode", e)) for e in _episodes()]
    r = TopicReader("broker:9092", consumer_factory=lambda conf: FakeConsumer(records), timeout_s=1)
    with pytest.raises(TopicLimitReached):
        r.episodes("tsx.wind.episodes.v2", max_records=2)
