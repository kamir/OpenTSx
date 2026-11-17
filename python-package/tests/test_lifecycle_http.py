from email.message import Message
from io import BytesIO
import importlib
import json
import sys
from types import SimpleNamespace

import pytest


def service():
    return importlib.import_module("opentsx.client.research")


def request(tmp_path, lifecycle, path, body=None, origin=True):
    handler = service().handler(None, tmp_path, {}, lifecycle=lifecycle)
    message = object.__new__(handler)
    message.path = path
    message.headers = Message()
    message.headers["Host"] = "127.0.0.1:18081"
    message.headers["Content-Type"] = "application/json"
    if origin:
        message.headers["Origin"] = "http://127.0.0.1:18081"
    raw = b"" if body is None else json.dumps(body).encode()
    message.headers["Content-Length"] = str(len(raw))
    message.rfile = BytesIO(raw)
    responses = []
    message.respond = lambda status, result: responses.append((status, result))
    if body is None:
        message.do_GET()
    else:
        message.do_POST()
    assert len(responses) == 1
    return responses[0]


def test_successful_fit_is_preserved_server_side(tmp_path, monkeypatch):
    calls = []
    lifecycle = SimpleNamespace(
        store=SimpleNamespace(save_run=lambda doc, result: calls.append((doc, result)))
    )
    monkeypatch.setattr(service(), "run_modelling", lambda doc: (200, {"run_id": "fitted"}))
    assert request(tmp_path, lifecycle, "/analysis/v1/modelling/runs", {"data": 3}) == (
        200,
        {"run_id": "fitted"},
    )
    assert calls == [({"data": 3}, {"run_id": "fitted"})]


def test_failed_fit_never_becomes_a_registrable_run(tmp_path, monkeypatch):
    def forbidden(*args):
        raise AssertionError("failed run must not persist")

    lifecycle = SimpleNamespace(store=SimpleNamespace(save_run=forbidden))
    monkeypatch.setattr(service(), "run_modelling", lambda doc: (422, {"error": "not converged"}))
    assert request(tmp_path, lifecycle, "/analysis/v1/modelling/runs", {"data": 3})[0] == 422


def test_registry_list_and_source_query_use_local_service(tmp_path):
    lifecycle = SimpleNamespace(
        prefix="tsx.workbench-demo.",
        store=SimpleNamespace(list_models=lambda: [{"id": "a"}]),
        catalogue=SimpleNamespace(series=lambda topic: {"topic": topic}),
    )
    status, result = request(tmp_path, lifecycle, "/analysis/v1/modelling/models")
    assert status == 200 and result["models"] == [{"id": "a"}]
    assert result["topic_prefix"] == "tsx.workbench-demo."
    assert request(
        tmp_path,
        lifecycle,
        "/analysis/v1/modelling/series?topic=tsx.workbench-demo.input.episodes.v2",
    ) == (200, {"topic": "tsx.workbench-demo.input.episodes.v2"})


def test_context_catalogue_and_capture_use_the_local_bound_service(tmp_path):
    lifecycle = SimpleNamespace(
        context_catalogue=SimpleNamespace(
            topics=lambda: {"topics": ["tsx.test.context.v1"]},
            series=lambda topic: {"topic": topic},
            capture=lambda document: {"receipt": document},
        )
    )
    assert request(tmp_path, lifecycle, "/analysis/v1/modelling/context-topics") == (
        200,
        {"topics": ["tsx.test.context.v1"]},
    )
    assert request(
        tmp_path,
        lifecycle,
        "/analysis/v1/modelling/context-series?topic=tsx.test.context.v1",
    ) == (200, {"topic": "tsx.test.context.v1"})
    assert request(
        tmp_path, lifecycle, "/analysis/v1/modelling/context-capture", {"topic": "a"}
    ) == (200, {"receipt": {"topic": "a"}})


@pytest.mark.parametrize(
    "path",
    ["context-series", "context-series?topic=a&topic=b", "context-topics?topic=a"],
)
def test_context_queries_reject_ambiguous_input(tmp_path, path):
    assert request(tmp_path, SimpleNamespace(), "/analysis/v1/modelling/" + path)[0] == 400


@pytest.mark.parametrize("query", ["", "?topic=a&topic=b", "?topic=a&other=b"])
def test_catalogue_rejects_ambiguous_queries(tmp_path, query):
    assert request(tmp_path, SimpleNamespace(), "/analysis/v1/modelling/series" + query)[0] == 400


def test_registration_response_excludes_internal_fit_state(tmp_path):
    lifecycle = SimpleNamespace(
        registry=object(),
        store=SimpleNamespace(
            register=lambda document, registry: {
                "id": "a",
                "artifact": {"private": "state"},
                "status": "registered",
            }
        ),
    )
    assert request(tmp_path, lifecycle, "/analysis/v1/modelling/models", {"run_id": "run"}) == (
        200,
        {"id": "a", "status": "registered"},
    )


@pytest.mark.parametrize("action", ["start", "stop"])
def test_deployment_controls_call_concrete_worker_manager(tmp_path, action):
    lifecycle = SimpleNamespace(**{action: lambda identifier: {"id": identifier, "status": action}})
    assert request(
        tmp_path, lifecycle, f"/analysis/v1/modelling/deployments/forecast/{action}", {}
    ) == (200, {"id": "forecast", "status": action})
    assert (
        request(
            tmp_path,
            lifecycle,
            f"/analysis/v1/modelling/deployments/forecast/{action}",
            {"command": "forbidden"},
        )[0]
        == 400
    )


def test_lifecycle_changes_still_require_local_same_origin(tmp_path):
    assert (
        request(
            tmp_path,
            SimpleNamespace(),
            "/analysis/v1/modelling/models",
            {},
            origin=False,
        )[0]
        == 403
    )


def test_registry_outage_is_explicitly_unavailable(tmp_path):
    from opentsx.client.registry import RegistryUnavailable

    def unavailable(*args):
        raise RegistryUnavailable("registry unavailable")

    lifecycle = SimpleNamespace(registry=object(), store=SimpleNamespace(register=unavailable))
    assert request(tmp_path, lifecycle, "/analysis/v1/modelling/models", {}) == (
        503,
        {"error": "registry unavailable"},
    )


def test_disabled_lifecycle_does_not_return_a_success(tmp_path):
    assert request(tmp_path, None, "/analysis/v1/modelling/models")[0] == 503


def test_prediction_reuses_only_the_named_registered_artifact(tmp_path, monkeypatch):
    import opentsx.algorithms.fitted as fitted

    calls = []
    model = {
        "id": "a" * 64,
        "artifact_id": "b" * 64,
        "artifact": {"state": "registered"},
    }
    lifecycle = SimpleNamespace(
        store=SimpleNamespace(get_model=lambda model_id: model),
        registry=SimpleNamespace(verify=lambda record: calls.append(record)),
    )
    monkeypatch.setattr(
        fitted,
        "predict_artifact",
        lambda artifact, **kwargs: {"mean": [3], "artifact": artifact, **kwargs},
    )
    status, result = request(
        tmp_path,
        lifecycle,
        "/analysis/v1/modelling/models/" + "a" * 64 + "/predict",
        {"steps": 1},
    )
    assert status == 200 and result["forecast"]["mean"] == [3]
    assert result["forecast"]["artifact"] == model["artifact"] and calls == [model]


@pytest.mark.parametrize("command,action", [("demo-seed", "seed"), ("demo-append", "append")])
def test_demo_cli_uses_the_same_configured_broker_namespace(monkeypatch, command, action):
    calls = []
    monkeypatch.setitem(
        sys.modules,
        "opentsx.client.scoring_demo",
        SimpleNamespace(main=lambda argv: calls.append(argv)),
    )
    service().main([command, "--bootstrap", "broker:29092", "--prefix", "tsx.example."])
    assert calls == [[action, "--bootstrap", "broker:29092", "--prefix", "tsx.example."]]


@pytest.mark.parametrize("available,expected", [(None, 400), ([[91]], 400), ([[90]], 200)])
def test_registered_context_requires_availability_by_the_preserved_origin(
    tmp_path, monkeypatch, available, expected
):
    import opentsx.algorithms.fitted as fitted

    artifact = {
        "context_names": ["calendar"],
        "source": {"interval_micros": 10},
        "initial_checkpoint": {"next_timestamp_micros": 100},
    }
    model = {"id": "a" * 64, "artifact_id": "b" * 64, "artifact": artifact}
    lifecycle = SimpleNamespace(
        store=SimpleNamespace(get_model=lambda model_id: model),
        registry=SimpleNamespace(verify=lambda record: None),
    )
    monkeypatch.setattr(fitted, "predict_artifact", lambda *args, **kwargs: {"mean": [3]})
    body = {"steps": 1, "future_exog": [[1]]}
    if available is not None:
        body["future_available_at_micros"] = available
    assert (
        request(
            tmp_path,
            lifecycle,
            "/analysis/v1/modelling/models/" + "a" * 64 + "/predict",
            body,
        )[0]
        == expected
    )
