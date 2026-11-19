import io
import json
import math
import os
import threading
import urllib.error
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import parse_qs, urlparse

import numpy as np
import pandas as pd
import pytest

from opentsx import model as m
from opentsx.client import RetrievalClient, RetrievalError, retrieval
from opentsx.client.retrieval import AVRO_STREAM, decode_stream

T0 = 1_790_000_000_000_000
BASE = "http://retrieval.test:8080"


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
                self._send(404, "application/json", b'{"error":"unknown episode","type":"not_found"}')

        def do_POST(self):
            seen["path"] = self.path
            seen["body"] = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
            if self.path == "/v1/segments":
                self._send(200, AVRO_STREAM, _stream(eps[:2]))
                return
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


def test_similar_and_segments_send_the_series(stub):
    client, eps, seen = stub
    like = client.similar(eps[0]["episodeId"], metric="demo.signal", series=["a", "b"], top_k=3)
    assert seen["path"] == "/v1/episodes/similar"
    assert seen["body"] == {"metric": "demo.signal", "series": "a,b", "episodeId": eps[0]["episodeId"],
                            "distance": "zED", "band": 0.1, "topK": 3}
    assert like.iloc[0]["episodeId"] == eps[1]["episodeId"]
    segs = client.segments("FIXED_DURATION", {"duration": "PT1H"}, series=["a", "b"])
    assert seen["path"] == "/v1/segments"
    assert seen["body"] == {"series": "a,b", "strategy": "FIXED_DURATION", "params": {"duration": "PT1H"}}
    assert [e["episodeId"] for e in segs] == [e["episodeId"] for e in eps[:2]]


class Answer:
    def __init__(self, body, status=200):
        self.body, self.status = body, status

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False

    def read(self):
        return self.body


def answer_with(monkeypatch, outcome):
    sent = []

    def urlopen(request, timeout):
        sent.append(request)
        if isinstance(outcome, BaseException):
            raise outcome
        return outcome

    monkeypatch.setattr(retrieval.urllib.request, "urlopen", urlopen)
    return sent


def http_error(status, body):
    return urllib.error.HTTPError(BASE + "/v1", status, "error", {}, io.BytesIO(body))


@pytest.mark.parametrize("outcome,status,text", [
    (urllib.error.URLError(ConnectionRefusedError(111, "Connection refused")), None, "no answer: [Errno 111] Connection refused"),
    (TimeoutError("timed out"), None, "no answer: timed out"),
    (http_error(500, b'["internal"]'), 500, 'HTTP 500: ["internal"]'),
    (Answer(b"<html>sign in</html>"), 200, "HTTP 200: the answer is not JSON"),
    (Answer(b'["a"]'), 200, "HTTP 200: the answer is not a JSON object"),
])
def test_a_failed_request_raises_retrieval_error_naming_method_and_url(monkeypatch, outcome, status, text):
    answer_with(monkeypatch, outcome)
    with pytest.raises(RetrievalError) as err:
        RetrievalClient(BASE + "/").inventory()
    assert str(err.value).startswith(f"GET {BASE}/v1/inventory: {text}")
    assert (err.value.method, err.value.url, err.value.status) == ("GET", BASE + "/v1/inventory", status)
    assert err.value.__cause__ is not None


def test_an_error_answer_keeps_text_and_type(monkeypatch):
    answer_with(monkeypatch, http_error(400, b'{"error": "limit: \\"x\\" is not an int", "type": "bad_request"}'))
    with pytest.raises(RetrievalError) as err:
        RetrievalClient(BASE).episodes(metric="m")
    assert str(err.value) == f'GET {BASE}/v1/episodes?metric=m: HTTP 400: limit: "x" is not an int'
    assert (err.value.status, err.value.type) == (400, "bad_request")


def test_an_unreadable_episode_stream_names_the_request(monkeypatch):
    answer_with(monkeypatch, Answer(b"<html>sign in</html>"))
    with pytest.raises(RetrievalError, match=r"^POST http://retrieval\.test:8080/v1/segments: HTTP 200: "
                                             r"truncated episode stream at byte 20") as err:
        RetrievalClient(BASE).segments("FIXED_COUNT", {"n": 4})
    assert isinstance(err.value.__cause__, ValueError)


@pytest.mark.parametrize("cut", [2, 6])
def test_a_truncated_episode_stream_raises_value_error(cut):
    key = m.series_key("demo.signal", {"n": "1"}, "1")
    data = _stream([m.regular(key, T0, 1_000_000, np.arange(10, dtype=float), source="stub")])
    with pytest.raises(ValueError, match=f"^truncated episode stream at byte {cut}; the record at byte 0 needs "):
        decode_stream(data[:cut])


def test_episode_is_none_only_for_an_unknown_episode(monkeypatch):
    sent = answer_with(monkeypatch, http_error(404, b'{"error": "unknown episode e1", "type": "not_found"}'))
    assert RetrievalClient(BASE).episode("e1") is None
    assert sent[0].full_url == BASE + "/v1/episodes/e1"
    for body, text in ((b'{"error": "no such endpoint"}', "no such endpoint"),
                       (b"<html>\n  Not Found\n</html>", "<html> Not Found </html>; check that the base URL points")):
        answer_with(monkeypatch, http_error(404, body))
        with pytest.raises(RetrievalError) as err:
            RetrievalClient(BASE).episode("e1")
        assert str(err.value).startswith(f"GET {BASE}/v1/episodes/e1: HTTP 404: {text}")
        assert (err.value.status, err.value.type) == (404, None)


@pytest.mark.parametrize("count", [0, 2])
def test_an_answer_without_one_episode_raises(monkeypatch, count):
    key = m.series_key("demo.signal", {"n": "1"}, "1")
    answer_with(monkeypatch, Answer(_stream([m.regular(key, T0, 1_000_000, np.arange(4, dtype=float))] * count)))
    with pytest.raises(RetrievalError, match=rf"^GET {BASE}/v1/episodes/e1: HTTP 200: the answer holds {count} "
                                             r"episodes, expected 1; check that the base URL") as err:
        RetrievalClient(BASE).episode("e1")
    assert isinstance(err.value.__cause__, ValueError)


@pytest.mark.parametrize("call", [lambda c: c.episodes(series="ab"), lambda c: c.search([1, 2], series="ab"),
                                  lambda c: c.similar("e1", series="ab"),
                                  lambda c: c.similar("e1", None, None, "2026-10-01T00:00:00Z"),
                                  lambda c: c.segments("FIXED_COUNT", {"n": 4}, None, None, "2026-10-01T00:00:00Z")])
def test_a_series_string_is_rejected_before_the_request(monkeypatch, call):
    sent = answer_with(monkeypatch, Answer(b"[]"))
    with pytest.raises(ValueError, match=r'^series: "(ab|2026-10-01T00:00:00Z)" is a string; pass a list of series '
                                         r'ids, such as \["(ab|2026-10-01T00:00:00Z)"\]$'):
        call(RetrievalClient(BASE))
    assert sent == []


@pytest.mark.parametrize("field,value", [("start", "yesterday"), ("end", "2026-10-01T00:00:00"), ("start", "2026-10-01"),
                                         ("end", datetime(2026, 10, 1)), ("start", "now"), ("start", ""),
                                         ("end", "01/02/2026 00:00Z"), ("end", "2026-10-01T00:00:00 UTC")])
def test_times_are_checked_before_the_request(monkeypatch, field, value):
    sent = answer_with(monkeypatch, Answer(b""))
    with pytest.raises(ValueError, match=f'^{field}: "?{value}"? '):
        RetrievalClient(BASE).episodes(metric="m", **{field: value})
    assert sent == []


@pytest.mark.parametrize("value", ["2026-10-01T00:00:00.123456789Z", pd.Timestamp("2026-10-01T00:00:00.123456789Z")])
def test_a_time_finer_than_a_microsecond_is_rejected(monkeypatch, value):
    sent = answer_with(monkeypatch, Answer(b""))
    with pytest.raises(ValueError, match=r"^start: 2026-10-01T00:00:00\.123456789\+00:00 is finer than a microsecond; "
                                         r"pass at most six decimal places$"):
        RetrievalClient(BASE).episodes(metric="m", start=value)
    assert sent == []


def test_a_time_outside_the_range_of_pandas_2_names_the_range(monkeypatch):
    sent = answer_with(monkeypatch, Answer(b""))
    if int(pd.__version__.split(".")[0]) >= 3:
        with pytest.raises(ValueError, match=r'^start: "2500-01-01T00:00:00\.123456789Z" is finer than a microsecond; '
                                             r"pass at most six decimal places$"):
            RetrievalClient(BASE).episodes(start="2500-01-01T00:00:00.123456789Z")
        assert sent == []
        RetrievalClient(BASE).episodes(start="2500-01-01T00:00:00Z")
        assert parse_qs(urlparse(sent[0].full_url).query)["from"] == ["2500-01-01T00:00:00Z"]
        return
    for start in ["2500-01-01T00:00:00Z", "2262-04-11T23:47:17Z", "1677-09-21T00:12:43Z"]:
        with pytest.raises(ValueError, match=rf'^start: "{start}" is outside the times pandas {pd.__version__} can hold; '
                                             r"pass a time from 1677-09-21T00:12:44Z to 2262-04-11T23:47:16Z$"):
            RetrievalClient(BASE).episodes(start=start)
    assert sent == []
    for start in ["2262-04-11T23:47:16Z", "1677-09-21T00:12:44Z"]:
        RetrievalClient(BASE).episodes(start=start)
    assert [parse_qs(urlparse(r.full_url).query)["from"] for r in sent] == [["2262-04-11T23:47:16Z"],
                                                                             ["1677-09-21T00:12:44Z"]]


@pytest.mark.parametrize("start,sent_start", [("2026-10-01T02:00:00+02:00", "2026-10-01T00:00:00Z"),
                                               ("2026-10-01T02:00:00+0200", "2026-10-01T00:00:00Z"),
                                               ("2026-10-01T00:00:00.5Z", "2026-10-01T00:00:00.500000Z")])
def test_time_strings_are_sent_in_utc(monkeypatch, start, sent_start):
    sent = answer_with(monkeypatch, Answer(b""))
    RetrievalClient(BASE).episodes(start=start, end="2026-10-02T00:00:00Z")
    q = parse_qs(urlparse(sent[0].full_url).query)
    assert (q["from"], q["to"]) == ([sent_start], ["2026-10-02T00:00:00Z"])


@pytest.mark.parametrize("timeout", [0, -5, float("inf"), float("nan")])
def test_timeout_must_be_finite_and_positive(timeout):
    with pytest.raises(ValueError, match="^timeout: "):
        RetrievalClient(BASE, timeout=timeout)


@pytest.mark.parametrize("call", [lambda c: c.episode("a/b"), lambda c: c.episode(""), lambda c: c.signals("h2/x"),
                                  lambda c: c.run_series("h2-chain", "r/1", ["k"])])
def test_ids_with_a_slash_are_rejected_before_the_request(monkeypatch, call):
    sent = answer_with(monkeypatch, Answer(b"{}"))
    with pytest.raises(ValueError, match=r"is not an id; pass a non-empty id without '/'"):
        call(RetrievalClient(BASE))
    assert sent == []


@pytest.mark.skipif("OPENTSX_RETRIEVAL_URL" not in os.environ, reason="needs a running RetrievalServer --source demo")
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
    with pytest.raises(RetrievalError) as err:
        c.episodes(metric="demo.signal", resample="PT1M", agg="NOPE")
    assert (err.value.status, err.value.type) == (400, "bad_request")
