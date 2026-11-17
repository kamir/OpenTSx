import copy
import importlib
import json
import math

import pytest


def api():
    return importlib.import_module("opentsx.client.lifecycle")


def fit():
    from opentsx.client.modelling import execute_modelling

    document = {
        "schema": "opentsx.modelling-request.v1",
        "target": {
            "series_id": "series-a",
            "label": "Synthetic",
            "unit": "MW",
            "time_kind": "absolute",
            "episode_ids": ["episode-a"],
            "values": [math.sin(i * 0.4) + i * 0.02 for i in range(64)],
            "timestamps_micros": [1_700_000_000_000_000 + i * 1_000_000 for i in range(64)],
        },
        "configuration": {
            "family": "arima",
            "order": [0, 0, 0],
            "seasonal_order": [0, 0, 0, 0],
            "trend": "n",
            "steps": 4,
            "alpha": 0.1,
            "maxiter": 200,
        },
        "backtest": {
            "initial": 48,
            "horizon": 4,
            "step": 4,
            "window": None,
            "seasonal_period": 4,
            "methods": ["naive", "mean"],
        },
        "context": None,
    }
    return document, execute_modelling(document)


class Registry:
    def __init__(self):
        self.calls = []

    def register(self, name, registration_id, artifact, result, review):
        self.calls.append((name, registration_id, artifact, result, review))
        return {"run_id": "mlflow-run", "version": "1"}


def registered(tmp_path):
    store = api().LifecycleStore(tmp_path / "lifecycle.sqlite")
    document, result = fit()
    store.save_run(document, result)
    body = {
        "run_id": result["run_id"],
        "name": "synthetic-demand",
        "review": {
            "accepted": True,
            "note": "Reviewed for local synthetic scoring",
            "minimum_improvement": -10,
        },
    }
    registry = Registry()
    model = store.register(body, registry)
    return store, model, registry, document, result, body


def test_preserves_exact_server_run_across_store_restart(tmp_path):
    document, result = fit()
    store = api().LifecycleStore(tmp_path / "lifecycle.sqlite")
    store.save_run(document, result)
    reopened = api().LifecycleStore(tmp_path / "lifecycle.sqlite")
    assert reopened.get_run(result["run_id"]) == (document, result)


def test_rejects_a_result_whose_request_identity_was_changed(tmp_path):
    document, result = fit()
    document["target"]["values"][0] += 1
    with pytest.raises(ValueError, match="identity"):
        api().LifecycleStore(tmp_path / "lifecycle.sqlite").save_run(document, result)


def test_registration_reuses_server_fit_and_is_idempotent(tmp_path):
    store, model, registry, document, result, body = registered(tmp_path)
    assert model["status"] == "registered"
    assert model["run_id"] == result["run_id"]
    assert model["mlflow_version"] == "1"
    assert model["artifact"]["artifact_id"] == model["artifact_id"]
    assert store.register(body, registry) == model
    assert len(registry.calls) == 1
    assert store.list_models()[0]["id"] == model["id"]
    assert "artifact" not in store.list_models()[0]


@pytest.mark.parametrize(
    "change",
    ["unreviewed", "missing_note", "unknown_run", "name_escape", "extra_field", "nan_threshold"],
)
def test_registration_rejects_invalid_review_before_registry_mutation(tmp_path, change):
    store, model, registry, document, result, body = registered(tmp_path)
    body = copy.deepcopy(body)
    if change == "unreviewed":
        body["review"]["accepted"] = False
    if change == "missing_note":
        body["review"]["note"] = ""
    if change == "unknown_run":
        body["run_id"] = "0" * 64
    if change == "name_escape":
        body["name"] = "../escape"
    if change == "extra_field":
        body["artifact"] = {"pickle": "forbidden"}
    if change == "nan_threshold":
        body["review"]["minimum_improvement"] = float("nan")
    with pytest.raises((ValueError, KeyError)):
        store.register(body, registry)
    assert len(registry.calls) == 1


def test_review_threshold_is_a_measured_gate(tmp_path):
    store, model, registry, document, result, body = registered(tmp_path)
    body["name"] = "strict-candidate"
    body["review"]["minimum_improvement"] = 0.999999
    with pytest.raises(ValueError, match="threshold"):
        store.register(body, registry)
    assert len(registry.calls) == 1


def test_registry_failure_is_not_a_successful_registration(tmp_path):
    store, model, registry, document, result, body = registered(tmp_path)
    body["name"] = "unavailable-registry"

    class Unavailable:
        def register(self, *args):
            raise RuntimeError("registry unavailable")

    with pytest.raises(RuntimeError, match="registry unavailable"):
        store.register(body, Unavailable())
    assert len(store.list_models()) == 1


def test_checkpoint_and_output_are_persisted_together_and_deduplicated(tmp_path):
    store, model, registry, document, result, body = registered(tmp_path)
    deployment = {
        "id": "forecast",
        "model_id": model["id"],
        "input_topic": "tsx.workbench-demo.input.episodes.v2",
        "input_partition": 0,
        "output_topic": "tsx.workbench-demo.smart.forecast.predictions.v1",
        "series_id": "series-a",
        "desired_state": "running",
        "status": "starting",
        "error": None,
    }
    store.add_deployment(deployment)
    checkpoint = {
        "numerical": model["artifact"]["initial_checkpoint"],
        "next_offset": 3,
        "counters": {"read": 3, "scored": 1, "skipped": 2},
    }
    store.record_output("forecast", "a" * 64, {"forecast": {"mean": [2]}}, 0, 7, checkpoint)
    store.record_output("forecast", "a" * 64, {"forecast": {"mean": [2]}}, 0, 8, checkpoint)
    reopened = api().LifecycleStore(tmp_path / "lifecycle.sqlite")
    assert reopened.get_checkpoint("forecast") == checkpoint
    assert len(reopened.results("forecast")["results"]) == 1
    assert reopened.results("forecast")["results"][0]["broker_offset"] == 7


def test_conflicting_deployment_and_changed_output_are_rejected(tmp_path):
    store, model, registry, document, result, body = registered(tmp_path)
    record = {
        "id": "forecast",
        "model_id": model["id"],
        "input_topic": "tsx.workbench-demo.input.episodes.v2",
        "input_partition": 0,
        "output_topic": "tsx.workbench-demo.smart.forecast.predictions.v1",
        "series_id": "series-a",
        "desired_state": "running",
        "status": "starting",
        "error": None,
    }
    store.add_deployment(record)
    assert store.add_deployment(record)["id"] == "forecast"
    changed = {**record, "input_partition": 1}
    with pytest.raises(ValueError, match="conflict"):
        store.add_deployment(changed)


def test_corrupt_persisted_json_does_not_reset_state(tmp_path):
    import sqlite3

    store, model, registry, document, result, body = registered(tmp_path)
    with sqlite3.connect(store.path) as connection:
        connection.execute("UPDATE models SET record=? WHERE id=?", ('{"invalid":', model["id"]))
    with pytest.raises((ValueError, json.JSONDecodeError)):
        store.get_model(model["id"])
