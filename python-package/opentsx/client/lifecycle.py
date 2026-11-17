"""Durable local fit review, registration and scoring deployment state."""

import hashlib
import json
from pathlib import Path
import re
import sqlite3
import threading
from contextlib import contextmanager

from opentsx.client.modelling import fields, real, validate_modelling


def encoded(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"), allow_nan=False)


def identity(value):
    return hashlib.sha256(encoded(value).encode()).hexdigest()


def identifier(value, name="identifier"):
    if not isinstance(value, str) or not re.fullmatch(r"[a-z0-9][a-z0-9_-]{0,63}", value):
        raise ValueError(
            f"{name} must contain 1 to 64 lowercase letters, digits, underscores or hyphens"
        )
    return value


def blockers(artifact, context_schema=None):
    result = []
    if artifact["source"]["time_kind"] != "absolute":
        result.append("Continuous scoring requires an absolute UTC clock.")
    if not artifact["source"]["unit"]:
        result.append("Continuous scoring requires a declared target unit.")
    if artifact["context_names"] and context_schema is None:
        result.append(
            "SARIMAX continuous scoring requires declared context units and a verified live context binding; capture and review the context again."
        )
    return result


class LifecycleStore:
    def __init__(self, database_path):
        self.path = str(Path(database_path).resolve())
        Path(self.path).parent.mkdir(parents=True, exist_ok=True)
        self.registration_lock = threading.Lock()
        with self.connection() as connection:
            connection.executescript(
                """
                CREATE TABLE IF NOT EXISTS runs(id TEXT PRIMARY KEY, request TEXT NOT NULL, result TEXT NOT NULL);
                CREATE TABLE IF NOT EXISTS models(id TEXT PRIMARY KEY, record TEXT NOT NULL);
                CREATE TABLE IF NOT EXISTS deployments(id TEXT PRIMARY KEY, record TEXT NOT NULL, checkpoint TEXT);
                CREATE TABLE IF NOT EXISTS outputs(seq INTEGER PRIMARY KEY AUTOINCREMENT, deployment_id TEXT NOT NULL, key TEXT NOT NULL, payload TEXT NOT NULL, broker_partition INTEGER NOT NULL, broker_offset INTEGER NOT NULL, UNIQUE(deployment_id,key));
            """
            )

    @contextmanager
    def connection(self):
        connection = sqlite3.connect(self.path, timeout=10)
        try:
            connection.execute("PRAGMA synchronous=FULL")
            connection.execute("PRAGMA foreign_keys=ON")
            with connection:
                yield connection
        finally:
            connection.close()

    def save_run(self, request, result):
        validate_modelling(request)
        expected = identity({"request": request, "runtime": result["runtime"]})
        if (
            result.get("schema") != "opentsx.modelling-result.v1"
            or result.get("run_id") != expected
        ):
            raise ValueError("run identity does not match the server request")
        with self.connection() as connection:
            existing = connection.execute(
                "SELECT request,result FROM runs WHERE id=?", (expected,)
            ).fetchone()
            values = (encoded(request), encoded(result))
            if existing is not None and existing != values:
                raise ValueError("stored run identity conflicts with different contents")
            connection.execute("INSERT OR IGNORE INTO runs VALUES(?,?,?)", (expected, *values))

    def get_run(self, run_id):
        with self.connection() as connection:
            row = connection.execute(
                "SELECT request,result FROM runs WHERE id=?", (run_id,)
            ).fetchone()
        if row is None:
            raise KeyError("completed server run was not found; fit again before registration")
        return tuple(json.loads(value) for value in row)

    def register(self, document, registry):
        from opentsx.algorithms.fitted import create_artifact

        fields(document, {"run_id", "name", "review"})
        identifier(document["name"], "registered name")
        review = document["review"]
        fields(review, {"accepted", "note", "minimum_improvement"})
        if (
            review["accepted"] is not True
            or not isinstance(review["note"], str)
            or not review["note"].strip()
            or len(review["note"]) > 2000
        ):
            raise ValueError(
                "registration requires explicit review and a note of 1 to 2000 characters"
            )
        minimum = real(review["minimum_improvement"], "minimum improvement")
        if not -10 <= minimum <= 1:
            raise ValueError("minimum improvement must be from -10 to 1")
        request, result = self.get_run(document["run_id"])
        measures = result["backtest"]["result"]["metrics"]
        candidate, baseline = measures["arima"]["mae"], measures["naive"]["mae"]
        if baseline == 0 and candidate != 0:
            raise ValueError("candidate cannot meet a threshold against a perfect baseline")
        improvement = 0 if baseline == 0 else 1 - candidate / baseline
        if improvement < minimum:
            raise ValueError(
                f"candidate improvement {improvement:.6g} is below the review threshold {minimum:.6g}"
            )
        model_id = identity(document)
        with self.registration_lock:
            with self.connection() as connection:
                existing = connection.execute(
                    "SELECT record FROM models WHERE id=?", (model_id,)
                ).fetchone()
            if existing is not None:
                return json.loads(existing[0])
            artifact = create_artifact(request, result)
            context_schema = None
            if request["context"] is not None and "units" in request["context"]:
                from opentsx.client.context_join import schema_descriptor

                context_schema = schema_descriptor(
                    request["target"]["series_id"],
                    request["context"]["names"],
                    request["context"]["units"],
                    artifact["source"]["interval_micros"],
                )
                if result.get("context_schema") != context_schema:
                    raise ValueError("completed context schema does not match the retained request")
            remote = registry.register(document["name"], model_id, artifact, result, review)
            record = {
                "id": model_id,
                "name": document["name"],
                "version": str(remote["version"]),
                "run_id": result["run_id"],
                "artifact_id": artifact["artifact_id"],
                "artifact": artifact,
                "family": request["configuration"]["family"],
                "source": result["source"],
                "review": review,
                "metrics": {
                    "candidate_mae": candidate,
                    "baseline_mae": baseline,
                    "improvement": improvement,
                },
                "mlflow_run_id": remote["run_id"],
                "mlflow_version": str(remote["version"]),
                "status": "registered",
                "context_schema": context_schema,
                "deployment_blockers": blockers(artifact, context_schema),
            }
            with self.connection() as connection:
                connection.execute("INSERT INTO models VALUES(?,?)", (model_id, encoded(record)))
            return record

    def get_model(self, model_id):
        with self.connection() as connection:
            row = connection.execute("SELECT record FROM models WHERE id=?", (model_id,)).fetchone()
        if row is None:
            raise KeyError("registered model was not found")
        return json.loads(row[0])

    def list_models(self):
        with self.connection() as connection:
            rows = connection.execute(
                "SELECT record FROM models ORDER BY rowid DESC LIMIT 100"
            ).fetchall()
        return [
            {key: value for key, value in json.loads(row[0]).items() if key != "artifact"}
            for row in rows
        ]

    def add_deployment(self, record):
        with self.connection() as connection:
            existing = connection.execute(
                "SELECT record FROM deployments WHERE id=?", (record["id"],)
            ).fetchone()
            if existing is not None:
                old = json.loads(existing[0])
                if any(
                    old.get(key) != record.get(key)
                    for key in (
                        "model_id",
                        "input_topic",
                        "input_partition",
                        "output_topic",
                        "series_id",
                        "context",
                        "context_schema",
                    )
                ):
                    raise ValueError(
                        "deployment identifier conflicts with a different immutable configuration"
                    )
                return old
            connection.execute(
                "INSERT INTO deployments VALUES(?,?,NULL)",
                (record["id"], encoded(record)),
            )
        return record

    def get_deployment(self, deployment_id):
        with self.connection() as connection:
            row = connection.execute(
                "SELECT record,checkpoint FROM deployments WHERE id=?", (deployment_id,)
            ).fetchone()
        if row is None:
            raise KeyError("scoring deployment was not found")
        return {
            **json.loads(row[0]),
            "checkpoint": None if row[1] is None else json.loads(row[1]),
        }

    def list_deployments(self):
        with self.connection() as connection:
            ids = connection.execute("SELECT id FROM deployments ORDER BY rowid DESC").fetchall()
        return [self.get_deployment(row[0]) for row in ids]

    def update_deployment(self, deployment_id, **changes):
        if set(changes) - {"status", "desired_state", "error", "context_status"}:
            raise ValueError("unsupported deployment update")
        with self.connection() as connection:
            connection.execute("BEGIN IMMEDIATE")
            row = connection.execute(
                "SELECT record FROM deployments WHERE id=?", (deployment_id,)
            ).fetchone()
            if row is None:
                raise KeyError("scoring deployment was not found")
            record = json.loads(row[0])
            record.update(changes)
            connection.execute(
                "UPDATE deployments SET record=? WHERE id=?",
                (encoded(record), deployment_id),
            )

    def set_worker_status(self, deployment_id, status, error=None):
        if status not in {
            "starting",
            "running",
            "waiting_context",
            "stopping",
            "stopped",
            "failed",
        }:
            raise ValueError("invalid worker state")
        changes = {"status": status, "error": error}
        if status == "failed":
            changes["desired_state"] = "stopped"
        self.update_deployment(deployment_id, **changes)

    def set_context_status(self, deployment_id, detail):
        if detail is not None and not isinstance(detail, dict):
            raise ValueError("context status must be an object or null")
        self.update_deployment(deployment_id, context_status=detail)

    def get_checkpoint(self, deployment_id):
        return self.get_deployment(deployment_id)["checkpoint"]

    def save_checkpoint(self, deployment_id, checkpoint):
        with self.connection() as connection:
            changed = connection.execute(
                "UPDATE deployments SET checkpoint=? WHERE id=?",
                (encoded(checkpoint), deployment_id),
            ).rowcount
            if changed != 1:
                raise KeyError("scoring deployment was not found")

    def record_output(
        self, deployment_id, key, payload, broker_partition, broker_offset, checkpoint
    ):
        with self.connection() as connection:
            connection.execute("BEGIN IMMEDIATE")
            existing = connection.execute(
                "SELECT payload FROM outputs WHERE deployment_id=? AND key=?",
                (deployment_id, key),
            ).fetchone()
            body = encoded(payload)
            if existing is not None and existing[0] != body:
                raise ValueError("prediction identity conflicts with different content")
            connection.execute(
                "INSERT OR IGNORE INTO outputs(deployment_id,key,payload,broker_partition,broker_offset) VALUES(?,?,?,?,?)",
                (deployment_id, key, body, broker_partition, broker_offset),
            )
            changed = connection.execute(
                "UPDATE deployments SET checkpoint=? WHERE id=?",
                (encoded(checkpoint), deployment_id),
            ).rowcount
            if changed != 1:
                raise KeyError("scoring deployment was not found")
            connection.execute(
                "DELETE FROM outputs WHERE deployment_id=? AND seq NOT IN (SELECT seq FROM outputs WHERE deployment_id=? ORDER BY seq DESC LIMIT 100)",
                (deployment_id, deployment_id),
            )

    def results(self, deployment_id):
        self.get_deployment(deployment_id)
        with self.connection() as connection:
            rows = connection.execute(
                "SELECT key,payload,broker_partition,broker_offset FROM outputs WHERE deployment_id=? ORDER BY seq DESC LIMIT 100",
                (deployment_id,),
            ).fetchall()
        records, size = [], 0
        for key, payload, partition, offset in rows:
            if size + len(payload) > 6 * 1024 * 1024:
                break
            size += len(payload)
            records.append(
                {
                    "key": key,
                    "payload": json.loads(payload),
                    "broker_partition": partition,
                    "broker_offset": offset,
                    "broker_offset_text": str(offset),
                }
            )
        return {
            "schema": "opentsx.smart-predictions.v1",
            "deployment_id": deployment_id,
            "results": records,
            "cache_limit": 100,
            "has_more": len(records) < len(rows),
        }
