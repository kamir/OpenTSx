import json
import math
import os
import threading
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import parse_qs, urlparse

import numpy as np
import pytest

from opentsx import model as m
from opentsx.client import RetrievalClient, RetrievalError
from opentsx.client.retrieval import AVRO_STREAM

T0 = 1_790_000_000_000_000


def _stream(episodes):
    out = b""
    for e in episodes:
        p = m.encode("Episode", e)
        out += len(p).to_bytes(4, "big") + p
    return out


@pytest.fixture()
def stub():
    key = m.series_key("demo.signal", {"n": "1"}, "1")
    eps = [m.regular(key, T0 + i * 600_000_000, 1_000_000, np.arange(600, dtype=float) + i, source="stub")
           for i in range(3)]
    seen = {}

    class H(BaseHTTPRequestHandler):
        def log_message(self, *a):
            pass

        def _send(self, status, ctype, body):
            self.send_response(status)
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def do_GET(self):
            u = urlparse(self.path)
            seen["query"] = parse_qs(u.query)
            seen["accept"] = self.headers.get("Accept")
            if u.path == "/v1/episodes":
                self._send(200, AVRO_STREAM, _stream(eps))
            elif u.path == "/v1/episodes/" + eps[0]["episodeId"]:
                self._send(200, AVRO_STREAM, _stream(eps[:1]))
            else:
                self._send(404, "application/json", b'{"error":"unknown episode"}')

        def do_POST(self):
            seen["body"] = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
            matches = [{"queryId": "q", "episodeId": eps[1]["episodeId"], "seriesId": key["seriesId"],
                        "metric": "zED", "offset": 7, "length": 4, "distance": 0.25}]
            self._send(200, "application/json", json.dumps(matches).encode())

    srv = HTTPServer(("127.0.0.1", 0), H)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    yield RetrievalClient(f"http://127.0.0.1:{srv.server_port}"), eps, seen
    srv.shutdown()


def test_episodes_roundtrip_and_parameters(stub):
    client, eps, seen = stub
    got = client.episodes(metric="demo.signal", tags={"n": "1"}, start=datetime(2026, 9, 21, tzinfo=timezone.utc),
                          end="2026-09-22T00:00:00Z", limit=3, resample="PT1M")
    assert [e["episodeId"] for e in got] == [e["episodeId"] for e in eps]
    assert seen["accept"] == AVRO_STREAM
    assert seen["query"]["tag.n"] == ["1"]
    assert seen["query"]["from"] == ["2026-09-21T00:00:00Z"]
    assert seen["query"]["resample"] == ["PT1M"] and seen["query"]["agg"] == ["MEAN"]
    assert client.episode(eps[0]["episodeId"])["episodeId"] == eps[0]["episodeId"]
    assert client.episode("nope") is None
    df = client.frame(metric="demo.signal")
    assert len(df) == 1800


def test_search_body_and_frame(stub):
    client, eps, seen = stub
    hits = client.search(pattern=[1, 2, 3, 2], metric="demo.signal", distance="DTW", band=0.2, top_k=5)
    assert seen["body"] == {"metric": "demo.signal", "pattern": [1.0, 2.0, 3.0, 2.0], "distance": "DTW",
                            "band": 0.2, "topK": 5}
    assert hits.iloc[0]["offset"] == 7
    with pytest.raises(ValueError):
        client.episodes(start=datetime(2026, 1, 1))


@pytest.mark.skipif("OPENTSX_RETRIEVAL_URL" not in os.environ, reason="needs a running RetrievalServer --demo")
def test_against_the_java_server():
    c = RetrievalClient(os.environ["OPENTSX_RETRIEVAL_URL"])
    eps = c.episodes(metric="demo.signal", tags={"n": "1"})
    assert len(eps) == 36 and all(e["count"] == 600 for e in eps)
    t = np.linspace(0, 1, 120)
    pattern = np.sin(3 * math.pi * t) * np.exp(-3 * t) + 0.5 * t
    hits = c.search(pattern=pattern, metric="demo.signal", top_k=3)
    best = hits.iloc[0]
    planted = next(e for e in eps if e["episodeId"] == best["episodeId"])
    start = (planted["tStart"] - datetime.fromtimestamp(T0 / 1e6, tz=timezone.utc)).total_seconds()
    assert start + best["offset"] == 5000
    seg = c.segments("FIXED_DURATION", {"duration": "PT1H"}, metric="demo.signal", tags={"n": "0"})
    assert sum(e["count"] for e in seg) == 6 * 3600
    with pytest.raises(RetrievalError):
        c.episodes(start="not-a-time")
