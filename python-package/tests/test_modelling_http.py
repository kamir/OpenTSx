import importlib
from email.message import Message
from io import BytesIO

import pytest


def post(
    monkeypatch,
    tmp_path,
    body=b"{}",
    path="/analysis/v1/modelling/runs",
    origin=True,
    length=None,
):
    api = importlib.import_module("opentsx.client.research")
    handler = api.handler(None, tmp_path, {})
    request = object.__new__(handler)
    request.path = path
    request.headers = Message()
    request.headers["Host"] = "127.0.0.1:18081"
    if origin:
        request.headers["Origin"] = "http://127.0.0.1:18081"
    request.headers["Content-Type"] = "application/json"
    request.headers["Content-Length"] = str(len(body) if length is None else length)
    request.rfile = BytesIO(body)
    replies = []
    request.respond = lambda status, payload: replies.append((status, payload))
    request.do_POST()
    assert len(replies) == 1
    return replies[0]


@pytest.mark.parametrize("status", [200, 422, 503, 504])
def test_modelling_route_preserves_service_status(monkeypatch, tmp_path, status):
    api = importlib.import_module("opentsx.client.research")
    calls = []

    def run(document):
        calls.append(document)
        return status, {"status": status}

    monkeypatch.setattr(api, "run_modelling", run, raising=False)
    assert post(monkeypatch, tmp_path) == (status, {"status": status})
    assert calls == [{}]


@pytest.mark.parametrize(
    "body", [b'{"v":NaN}', b'{"v":Infinity}', b'{"v":-Infinity}', b"{"]
)
def test_modelling_route_rejects_invalid_json_before_execution(
    monkeypatch, tmp_path, body
):
    api = importlib.import_module("opentsx.client.research")
    calls = []
    monkeypatch.setattr(
        api, "run_modelling", lambda doc: calls.append(doc), raising=False
    )
    status, error = post(monkeypatch, tmp_path, body)
    assert status == 400
    assert "error" in error
    assert calls == []


def test_modelling_route_rejects_oversized_body_without_reading(monkeypatch, tmp_path):
    assert post(monkeypatch, tmp_path, length=1_048_577)[0] == 400


def test_modelling_route_requires_local_same_origin(monkeypatch, tmp_path):
    assert post(monkeypatch, tmp_path, origin=False)[0] == 403


def test_modelling_route_preserves_validation_error(monkeypatch, tmp_path):
    api = importlib.import_module("opentsx.client.research")

    def reject(document):
        raise ValueError("invalid target")

    monkeypatch.setattr(api, "run_modelling", reject, raising=False)
    assert post(monkeypatch, tmp_path) == (400, {"error": "invalid target"})
