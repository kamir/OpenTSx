from email.message import Message
from io import BytesIO
import json
from pathlib import Path

import pytest

from opentsx.client import research


EXAMPLE = Path(__file__).parent.parent / "examples" / "finite_modelling_request.json"


def request(
    method,
    path,
    body=b"",
    host="127.0.0.1:18081",
    origin=True,
    length=None,
    content_type="application/json",
    stream=None,
):
    handler = research.handler(None, None, {})
    message = object.__new__(handler)
    message.path = path
    message.headers = Message()
    message.headers["Host"] = host
    message.headers["Content-Type"] = content_type
    message.headers["Content-Length"] = str(len(body) if length is None else length)
    if origin:
        message.headers["Origin"] = "http://127.0.0.1:18081" if origin is True else origin
    message.rfile = BytesIO(body) if stream is None else stream
    replies = []
    message.respond = lambda status, payload: replies.append((status, payload))
    getattr(message, "do_" + method)()
    assert len(replies) == 1
    return replies[0]


def test_finite_health_requires_no_static_root_or_connected_services():
    status, body = request("GET", "/analysis/v1/health")
    assert status == 200
    assert body["status"] == "ready"
    assert body["version"] == research.__version__


@pytest.mark.parametrize(
    "path", ["/", "/workbench", "/index.html", "/package.json", "/../README.md"]
)
def test_api_only_mode_never_serves_the_current_directory(tmp_path, monkeypatch, path):
    (tmp_path / "index.html").write_text("Private local file", encoding="utf-8")
    (tmp_path / "package.json").write_text("{}", encoding="utf-8")
    monkeypatch.chdir(tmp_path)
    status, body = request("GET", path)
    assert status == 404
    assert isinstance(body["error"], str)
    assert "Private local file" not in json.dumps(body)


@pytest.mark.parametrize(
    "method,path,body",
    [
        ("GET", "/analysis/v1/topics", b""),
        ("GET", "/analysis/v1/results", b""),
        ("POST", "/analysis/v1/runs", b"{}"),
        ("GET", "/analysis/v1/modelling/models", b""),
        ("GET", "/analysis/v1/modelling/deployments", b""),
        ("POST", "/analysis/v1/modelling/models", b"{}"),
        ("POST", "/analysis/v1/modelling/deployments", b"{}"),
    ],
)
def test_connected_routes_report_unavailable_in_finite_mode(method, path, body):
    status, result = request(method, path, body)
    assert status == 503
    assert isinstance(result["error"], str) and result["error"]


@pytest.mark.parametrize(
    "host,origin",
    [
        ("remote.example:18081", True),
        ("127.0.0.1:18081", False),
        ("127.0.0.1:18081", "https://remote.example"),
        ("127.0.0.1:18081", "http://127.0.0.1:18082"),
    ],
)
def test_finite_post_preserves_host_and_origin_validation(monkeypatch, host, origin):
    calls = []
    monkeypatch.setattr(research, "run_modelling", lambda document: calls.append(document))
    assert request("POST", "/analysis/v1/modelling/runs", b"{}", host=host, origin=origin)[0] == 403
    assert calls == []


@pytest.mark.parametrize(
    "body", [b"{", b'{"value":NaN}', b'{"value":Infinity}', b'{"value":-Infinity}', b"{} {}"]
)
def test_invalid_json_is_rejected_before_numerical_execution(monkeypatch, body):
    calls = []
    monkeypatch.setattr(research, "run_modelling", lambda document: calls.append(document))
    assert request("POST", "/analysis/v1/modelling/runs", body)[0] == 400
    assert calls == []


@pytest.mark.parametrize(
    "length,content_type",
    [(0, "application/json"), (1048577, "application/json"), (2, "text/plain")],
)
def test_invalid_body_boundaries_do_not_read_or_execute(monkeypatch, length, content_type):
    stream = BytesIO(b"{}")
    calls = []
    monkeypatch.setattr(research, "run_modelling", lambda document: calls.append(document))
    assert (
        request(
            "POST",
            "/analysis/v1/modelling/runs",
            b"{}",
            length=length,
            content_type=content_type,
            stream=stream,
        )[0]
        == 400
    )
    assert stream.tell() == 0
    assert calls == []


@pytest.mark.parametrize("status", [422, 503, 504])
def test_finite_endpoint_preserves_numerical_failure_status(monkeypatch, status):
    monkeypatch.setattr(
        research, "run_modelling", lambda document: (status, {"error": "Numerical run refused"})
    )
    assert request("POST", "/analysis/v1/modelling/runs", b"{}") == (
        status,
        {"error": "Numerical run refused"},
    )


def test_finite_request_uses_real_validation():
    document = json.loads(EXAMPLE.read_text(encoding="utf-8"))
    document["configuration"]["steps"] = 97
    status, body = request("POST", "/analysis/v1/modelling/runs", json.dumps(document).encode())
    assert status == 400
    assert "steps" in body["error"]


def test_example_executes_a_real_random_walk_forecast_and_two_held_out_folds():
    status, result = request("POST", "/analysis/v1/modelling/runs", EXAMPLE.read_bytes())
    assert status == 200, result
    assert result["schema"] == "opentsx.modelling-result.v1"
    assert result["source"]["series_id"] == "synthetic-voltage"
    assert result["source"]["unit"] == "V"
    assert result["source"]["time_kind"] == "relative"
    assert result["source"]["episode_ids"] == ["synthetic-episode-1"]
    assert result["forecast"]["result"]["mean"] == pytest.approx([16, 16, 16, 16], rel=0, abs=1e-8)
    assert result["forecast"]["result"]["timestamps_micros"] == [
        16000000,
        17000000,
        18000000,
        19000000,
    ]
    assert result["baselines"]["naive"]["result"]["mean"] == [16, 16, 16, 16]
    folds = result["backtest"]["result"]["folds"]
    assert len(folds) == 2
    assert [fold["training_stop"] for fold in folds] == [12, 14]
    assert all(fold["training_stop"] == fold["test_start"] for fold in folds)
    assert result["backtest"]["result"]["metrics"]["naive"]["mae"] == pytest.approx(
        2.5, rel=0, abs=1e-8
    )
    assert result["runtime"]["statsmodels"] == "0.14.6"
