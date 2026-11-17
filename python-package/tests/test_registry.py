import copy
import hashlib
import io
import json
import math
from functools import lru_cache
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode, urlsplit

import pytest

from opentsx.algorithms.fitted import create_artifact
from opentsx.client import registry
from opentsx.client.modelling import execute_modelling


class Response:
    def __init__(self, value):
        self.body = value if isinstance(value, bytes) else json.dumps(value).encode()

    def read(self, limit):
        return self.body[:limit]

    def __enter__(self):
        return self

    def __exit__(self, *args):
        return False


class Opener:
    def __init__(self, steps):
        self.steps = list(steps)
        self.calls = []

    def __call__(self, request, timeout):
        assert timeout == 15
        assert self.steps, f"unexpected request: {request.method} {request.full_url}"
        method, path, answer = self.steps.pop(0)
        assert request.method == method
        assert request.full_url == "http://mlflow:5000/api/" + path
        self.calls.append(request)
        if isinstance(answer, Exception):
            raise answer
        return Response(answer)


def http_error(status=404, body=...):
    body = {"error_code": "RESOURCE_DOES_NOT_EXIST"} if body is ... else body
    return HTTPError(
        "http://mlflow:5000/api/resource",
        status,
        "rejected",
        {},
        io.BytesIO(json.dumps(body).encode()),
    )


@lru_cache(maxsize=1)
def documents():
    request = {
        "schema": "opentsx.modelling-request.v1",
        "target": {
            "series_id": "load-1",
            "label": "Load",
            "unit": "MW",
            "time_kind": "absolute",
            "values": [math.sin(i * 0.2) + i * 0.01 for i in range(64)],
            "timestamps_micros": [1_700_000_000_000_000 + i * 1_000_000 for i in range(64)],
            "episode_ids": ["training-episode"],
        },
        "configuration": {
            "family": "arima",
            "order": [0, 0, 0],
            "seasonal_order": [0, 0, 0, 0],
            "trend": "n",
            "steps": 4,
            "alpha": 0.05,
            "maxiter": 200,
        },
        "backtest": {
            "initial": 48,
            "horizon": 4,
            "step": 4,
            "window": None,
            "seasonal_period": 4,
            "methods": ["naive"],
        },
        "context": None,
    }
    result = execute_modelling(request)
    artifact = create_artifact(request, result)
    review = {
        "accepted": True,
        "note": "Reviewed for local synthetic scoring",
        "minimum_improvement": -10,
    }
    return artifact, result, review


def version(artifact, status="READY", registration_id="registration-1"):
    return {
        "name": "opentsx-demand",
        "version": "2",
        "run_id": "run-1",
        "status": status,
        "tags": [
            {"key": "opentsx.registration_id", "value": registration_id},
            {"key": "opentsx.artifact_id", "value": artifact["artifact_id"]},
        ],
    }


def model_record():
    artifact, _, _ = documents()
    return {
        "name": "demand",
        "mlflow_version": "2",
        "mlflow_run_id": "run-1",
        "artifact_id": artifact["artifact_id"],
        "artifact": artifact,
    }


def verify_steps(artifact=None, metadata=None):
    artifact = documents()[0] if artifact is None else artifact
    query = urlencode({"name": "opentsx-demand", "version": "2"})
    return [
        (
            "GET",
            "2.0/mlflow/model-versions/get?" + query,
            {"model_version": version(documents()[0]) if metadata is None else metadata},
        ),
        (
            "GET",
            "2.0/mlflow/model-versions/get-download-uri?" + query,
            {"artifact_uri": "mlflow-artifacts:/17/run-1/artifacts/model"},
        ),
        ("GET", "2.0/mlflow-artifacts/artifacts/17/run-1/artifacts/model/fitted.json", artifact),
    ]


def registration_steps(status="READY"):
    artifact, _, _ = documents()
    return [
        ("GET", "2.0/mlflow/registered-models/get?name=opentsx-demand", http_error()),
        ("POST", "2.0/mlflow/registered-models/create", {}),
        (
            "GET",
            "2.0/mlflow/model-versions/search?"
            + urlencode({"filter": "name = 'opentsx-demand'", "max_results": 1000}),
            {},
        ),
        (
            "GET",
            "2.0/mlflow/experiments/get-by-name?experiment_name=OpenTSx+Workbench",
            http_error(),
        ),
        ("POST", "2.0/mlflow/experiments/create", {"experiment_id": "17"}),
        (
            "POST",
            "2.0/mlflow/runs/create",
            {
                "run": {
                    "info": {
                        "run_id": "run-1",
                        "artifact_uri": "mlflow-artifacts:/17/run-1/artifacts",
                    }
                }
            },
        ),
        ("POST", "2.0/mlflow/runs/log-batch", {}),
        ("PUT", "2.0/mlflow-artifacts/artifacts/17/run-1/artifacts/model/fitted.json", b""),
        ("PUT", "2.0/mlflow-artifacts/artifacts/17/run-1/artifacts/model/evaluation.json", b""),
        ("PUT", "2.0/mlflow-artifacts/artifacts/17/run-1/artifacts/model/review.json", b""),
        ("POST", "2.0/mlflow/model-versions/create", {"model_version": version(artifact, status)}),
        ("POST", "2.0/mlflow/registered-models/alias", {}),
        ("POST", "2.0/mlflow/runs/update", {}),
    ]


def body(request):
    return json.loads(request.data)


def test_register_uses_exact_metadata_and_proxy_artifact_requests(monkeypatch):
    monkeypatch.setattr(registry.time, "time", lambda: 1_700_000_000.125)
    artifact, result, review = documents()
    opener = Opener(registration_steps())
    value = registry.MLflowRegistry("http://mlflow:5000", opener=opener).register(
        "demand", "registration-1", artifact, result, review
    )
    assert value == {"run_id": "run-1", "version": "2"}
    assert not opener.steps
    assert body(opener.calls[1]) == {"name": "opentsx-demand"}
    assert body(opener.calls[4]) == {"name": "OpenTSx Workbench"}
    created = body(opener.calls[5])
    assert created == {
        "experiment_id": "17",
        "run_name": "registration-1",
        "tags": [
            {"key": "opentsx.run_id", "value": result["run_id"]},
            {"key": "opentsx.artifact_id", "value": artifact["artifact_id"]},
            {"key": "opentsx.registration_id", "value": "registration-1"},
        ],
    }
    logged = body(opener.calls[6])
    assert logged["run_id"] == "run-1"
    assert logged["metrics"] == [
        {
            "key": "candidate_mae",
            "value": result["backtest"]["result"]["metrics"]["arima"]["mae"],
            "timestamp": 1_700_000_000_125,
            "step": 0,
        },
        {
            "key": "baseline_mae",
            "value": result["backtest"]["result"]["metrics"]["naive"]["mae"],
            "timestamp": 1_700_000_000_125,
            "step": 0,
        },
    ]
    assert {item["key"]: json.loads(item["value"]) for item in logged["params"]} == artifact[
        "configuration"
    ]
    assert json.loads(logged["tags"][0]["value"]) == review
    for request, expected in zip(opener.calls[7:10], [artifact, result, review]):
        assert request.get_header("Content-type") == "application/octet-stream"
        assert body(request) == expected
    registered = body(opener.calls[10])
    assert registered["source"] == "mlflow-artifacts:/17/run-1/artifacts/model"
    assert registered["run_id"] == "run-1"
    assert registered["tags"] == [
        {"key": "opentsx.artifact_id", "value": artifact["artifact_id"]},
        {"key": "opentsx.registration_id", "value": "registration-1"},
    ]
    assert body(opener.calls[11]) == {
        "name": "opentsx-demand",
        "alias": "candidate",
        "version": "2",
    }
    assert body(opener.calls[12]) == {
        "run_id": "run-1",
        "status": "FINISHED",
        "end_time": 1_700_000_000_125,
    }


@pytest.mark.parametrize("status", ["PENDING_REGISTRATION", "FAILED_REGISTRATION", None])
def test_a_version_that_is_not_ready_never_gets_an_alias_or_success(status):
    artifact, result, review = documents()
    opener = Opener(registration_steps(status)[:11])
    with pytest.raises(registry.RegistryUnavailable, match="not ready"):
        registry.MLflowRegistry("http://mlflow:5000", opener=opener).register(
            "demand", "registration-1", artifact, result, review
        )
    assert not opener.steps
    assert all(not urlsplit(call.full_url).path.endswith("/alias") for call in opener.calls)


def test_a_failed_artifact_upload_never_creates_a_ready_version():
    artifact, result, review = documents()
    steps = registration_steps()[:8]
    steps[-1] = (*steps[-1][:2], URLError("connection failed"))
    opener = Opener(steps)
    with pytest.raises(registry.RegistryUnavailable):
        registry.MLflowRegistry("http://mlflow:5000", opener=opener).register(
            "demand", "registration-1", artifact, result, review
        )
    assert not opener.steps
    assert not any(call.full_url.endswith("/model-versions/create") for call in opener.calls)


def test_verify_and_promote_check_the_exact_artifact_before_changing_the_alias():
    opener = Opener(verify_steps() + [("POST", "2.0/mlflow/registered-models/alias", {})])
    registry.MLflowRegistry("http://mlflow:5000", opener=opener).promote(model_record())
    assert not opener.steps
    assert body(opener.calls[-1]) == {"name": "opentsx-demand", "alias": "champion", "version": "2"}


def test_an_existing_ready_version_is_retrieved_and_verified_without_creating_another_run():
    artifact, result, review = documents()
    steps = [
        ("GET", "2.0/mlflow/registered-models/get?name=opentsx-demand", {}),
        (
            "GET",
            "2.0/mlflow/model-versions/search?"
            + urlencode({"filter": "name = 'opentsx-demand'", "max_results": 1000}),
            {"model_versions": [version(artifact)]},
        ),
    ] + verify_steps()
    opener = Opener(steps)
    actual = registry.MLflowRegistry("http://mlflow:5000", opener=opener).register(
        "demand", "registration-1", artifact, result, review
    )
    assert actual == {"run_id": "run-1", "version": "2"}
    assert not opener.steps
    assert all(call.method == "GET" for call in opener.calls)


def test_an_existing_registration_with_a_different_artifact_identity_is_rejected():
    artifact, result, review = documents()
    metadata = version(artifact)
    metadata["tags"][1]["value"] = "0" * 64
    steps = [
        ("GET", "2.0/mlflow/registered-models/get?name=opentsx-demand", {}),
        (
            "GET",
            "2.0/mlflow/model-versions/search?"
            + urlencode({"filter": "name = 'opentsx-demand'", "max_results": 1000}),
            {"model_versions": [metadata]},
        ),
    ]
    opener = Opener(steps)
    with pytest.raises(registry.RegistryUnavailable, match="incompatible"):
        registry.MLflowRegistry("http://mlflow:5000", opener=opener).register(
            "demand", "registration-1", artifact, result, review
        )
    assert not opener.steps


@pytest.mark.parametrize("remote", [b"invalid JSON", b"null", b"[]", b'{"schema":"unsupported"}'])
def test_invalid_remote_artifacts_are_registry_failures_and_never_promoted(remote):
    steps = verify_steps()
    steps[-1] = (*steps[-1][:2], remote)
    opener = Opener(steps)
    with pytest.raises(registry.RegistryUnavailable, match="artifact"):
        registry.MLflowRegistry("http://mlflow:5000", opener=opener).promote(model_record())
    assert not opener.steps
    assert all(call.method == "GET" for call in opener.calls)


def test_a_tampered_remote_artifact_is_never_promoted():
    artifact = copy.deepcopy(documents()[0])
    artifact["params"][0] += 1
    opener = Opener(verify_steps(artifact=artifact))
    with pytest.raises(registry.RegistryUnavailable, match="artifact"):
        registry.MLflowRegistry("http://mlflow:5000", opener=opener).promote(model_record())
    assert all(call.method == "GET" for call in opener.calls)


def test_a_valid_but_different_remote_artifact_is_never_promoted():
    artifact = copy.deepcopy(documents()[0])
    artifact["source"]["label"] = "Different load"
    content = copy.deepcopy(artifact)
    content.pop("artifact_id")
    content["initial_checkpoint"].pop("artifact_id")
    artifact["artifact_id"] = hashlib.sha256(
        json.dumps(content, sort_keys=True, separators=(",", ":"), allow_nan=False).encode()
    ).hexdigest()
    artifact["initial_checkpoint"]["artifact_id"] = artifact["artifact_id"]
    opener = Opener(verify_steps(artifact=artifact))
    with pytest.raises(registry.RegistryUnavailable, match="differs"):
        registry.MLflowRegistry("http://mlflow:5000", opener=opener).promote(model_record())
    assert all(call.method == "GET" for call in opener.calls)


@pytest.mark.parametrize("payload", [[], None, "rejected"])
def test_non_object_http_error_json_keeps_the_registry_error_category(payload):
    opener = Opener([("GET", "resource", http_error(403, payload))])
    with pytest.raises(registry.RegistryError) as failure:
        registry.MLflowRegistry("http://mlflow:5000", opener=opener).request("GET", "resource")
    assert failure.value.status == 403
    assert failure.value.code == "UNKNOWN_ERROR"


@pytest.mark.parametrize("error", [URLError("offline"), TimeoutError("timeout"), OSError("closed")])
def test_transport_failures_are_explicit_registry_unavailability(error):
    opener = Opener([("GET", "resource", error)])
    with pytest.raises(registry.RegistryUnavailable, match="unavailable"):
        registry.MLflowRegistry("http://mlflow:5000", opener=opener).request("GET", "resource")


@pytest.mark.parametrize("payload", [b"invalid", b"[]", b"x" * (8 * 1024 * 1024 + 1)])
def test_invalid_or_oversized_success_responses_fail_closed(payload):
    opener = Opener([("GET", "resource", payload)])
    with pytest.raises(registry.RegistryUnavailable):
        registry.MLflowRegistry("http://mlflow:5000", opener=opener).request("GET", "resource")


@pytest.mark.parametrize(
    "url",
    [
        "https://mlflow:5000",
        "http://user:password@mlflow:5000",
        "http://mlflow:5000/?query=value",
        "http://mlflow:5000/#fragment",
        "/relative",
    ],
)
def test_registry_url_rejects_credentials_and_unsupported_transports(url):
    with pytest.raises(ValueError, match="URL"):
        registry.MLflowRegistry(url, opener=Opener([]))
