"""Own and control local scoring workers bound to reviewed registry versions."""

import fcntl
import multiprocessing
import re
import threading

from opentsx.client.lifecycle import blockers, identifier
from opentsx.client.modelling import fields, integer


def public_deployment(record):
    checkpoint = record.get("checkpoint")
    if checkpoint is not None:
        checkpoint = {
            key: value
            for key, value in checkpoint.items()
            if key in {"artifact_id", "next_offset", "counters", "last_source"}
        }
    return {**record, "checkpoint": checkpoint}


class SmartTopics:
    def __init__(self, store, registry, catalogue, bootstrap, prefix):
        if not isinstance(prefix, str) or not re.fullmatch(
            r"[A-Za-z0-9_-][A-Za-z0-9._-]{0,180}\.", prefix
        ):
            raise ValueError("topic prefix is invalid")
        self.store, self.registry, self.catalogue = store, registry, catalogue
        self.bootstrap, self.prefix = bootstrap, prefix
        self.lock = threading.RLock()
        self.children = {}
        self.context = multiprocessing.get_context("spawn")
        self.owner = open(store.path + ".owner", "a+")
        try:
            fcntl.flock(self.owner.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError:
            self.owner.close()
            raise RuntimeError("another workbench process owns the scoring state")

    def _open(self):
        if self.owner.closed:
            raise RuntimeError("the scoring manager is closed")

    def _model(self, model_id):
        from opentsx.algorithms.fitted import validate_artifact

        if not isinstance(model_id, str) or not re.fullmatch(r"[a-f0-9]{64}", model_id):
            raise ValueError("model identifier must be a lowercase SHA-256 digest")
        model = self.store.get_model(model_id)
        review = model.get("review")
        if (
            model.get("status") != "registered"
            or not isinstance(review, dict)
            or review.get("accepted") is not True
            or not isinstance(review.get("note"), str)
            or not review["note"].strip()
        ):
            raise ValueError("continuous scoring requires an explicitly reviewed registered model")
        validate_artifact(model["artifact"])
        if (
            model["id"] != model_id
            or model["artifact_id"] != model["artifact"]["artifact_id"]
            or model["source"] != model["artifact"]["source"]
        ):
            raise ValueError("registered model identity does not match its fitted artifact")
        reasons = blockers(model["artifact"])
        if reasons:
            raise ValueError(" ".join(reasons))
        self.registry.verify(model)
        return model

    def recover(self):
        self._open()
        for record in self.store.list_deployments():
            if record["desired_state"] == "running":
                try:
                    self.start(record["id"])
                except Exception as error:
                    self.store.set_worker_status(record["id"], "failed", str(error))
                    print(
                        f"scoring recovery failed: {record['id']} {type(error).__name__}",
                        flush=True,
                    )

    def ensure_output(self, record, allow_existing):
        from confluent_kafka.admin import AdminClient, NewTopic

        admin = AdminClient({"bootstrap.servers": self.bootstrap})
        metadata = admin.list_topics(timeout=10)
        topic = record["output_topic"]
        if topic in metadata.topics:
            if metadata.topics[topic].error is not None or not allow_existing:
                raise ValueError(
                    "output topic already exists outside this deployment or is unavailable"
                )
        self.store.add_deployment(record)
        if topic in metadata.topics:
            return
        futures = admin.create_topics(
            [
                NewTopic(
                    topic,
                    num_partitions=1,
                    replication_factor=1,
                    config={"min.insync.replicas": "1"},
                )
            ],
            request_timeout=10,
        )
        for future in futures.values():
            future.result(timeout=10)

    def deploy(self, document):
        self._open()
        fields(document, {"model_id", "input_topic", "input_partition", "output_topic"})
        topic = document["output_topic"]
        pattern = re.escape(self.prefix) + r"smart\.([a-z0-9][a-z0-9_-]{0,63})\.predictions\.v1"
        match = re.fullmatch(pattern, topic) if isinstance(topic, str) else None
        if match is None or len(topic) > 249:
            raise ValueError("output must be <prefix>smart.<deployment>.predictions.v1")
        deployment_id = match[1]
        partition = integer(document["input_partition"], "input partition", 0, 2**31 - 1)
        input_topic = document["input_topic"]
        if (
            not isinstance(input_topic, str)
            or not re.fullmatch(r"[A-Za-z0-9._-]{1,249}", input_topic)
            or not input_topic.startswith(self.prefix)
            or not input_topic.endswith(".episodes.v2")
        ):
            raise ValueError("input topic is outside the configured episode namespace")
        model = self._model(document["model_id"])
        receipt = self.catalogue.verify_binding(model, document["input_topic"], partition)
        record = {
            "id": deployment_id,
            "model_id": model["id"],
            "artifact_id": model["artifact_id"],
            "model_version": model["version"],
            "input_topic": document["input_topic"],
            "input_partition": partition,
            "output_topic": topic,
            "series_id": model["source"]["series_id"],
            "start_policy": "replay_after_training",
            "delivery": "at_least_once",
            "binding": receipt,
            "desired_state": "stopped",
            "status": "stopped",
            "error": None,
        }
        with self.lock:
            self._open()
            try:
                existing = self.store.get_deployment(deployment_id)
            except KeyError:
                existing = None
            if existing is not None and any(
                existing[key] != record[key]
                for key in (
                    "model_id",
                    "input_topic",
                    "input_partition",
                    "output_topic",
                    "series_id",
                )
            ):
                raise ValueError(
                    "deployment identifier conflicts with a different immutable configuration"
                )
            self.ensure_output(record, allow_existing=existing is not None)
            self.registry.promote(model)
            return self.start(deployment_id)

    def start(self, deployment_id):
        from opentsx.client.scoring import run_worker

        identifier(deployment_id, "deployment identifier")
        with self.lock:
            self._open()
            self._reap_children()
            record = self.store.get_deployment(deployment_id)
            current = self.children.get(deployment_id)
            if current is not None and current[0].is_alive():
                return public_deployment(record)
            if sum(process.is_alive() for process, event in self.children.values()) >= 4:
                raise ValueError("the local profile allows at most four active scoring deployments")
            self._model(record["model_id"])
            event = self.context.Event()
            process = self.context.Process(
                target=run_worker,
                args=(self.store.path, deployment_id, self.bootstrap, self.prefix, event),
                daemon=True,
            )
            self.store.update_deployment(
                deployment_id, desired_state="running", status="starting", error=None
            )
            self.children[deployment_id] = (process, event)
            try:
                process.start()
            except Exception as error:
                self.store.set_worker_status(
                    deployment_id, "failed", f"worker start failed: {type(error).__name__}"
                )
                self._stop_child(deployment_id)
                raise
            return public_deployment(self.store.get_deployment(deployment_id))

    def stop(self, deployment_id):
        identifier(deployment_id, "deployment identifier")
        with self.lock:
            self._open()
            self.store.update_deployment(
                deployment_id, desired_state="stopped", status="stopping", error=None
            )
            try:
                self._stop_child(deployment_id)
            except Exception as error:
                self.store.set_worker_status(deployment_id, "failed", str(error))
                raise
            self.store.set_worker_status(deployment_id, "stopped")
            return public_deployment(self.store.get_deployment(deployment_id))

    def _stop_child(self, deployment_id):
        child = self.children.get(deployment_id)
        if child is None:
            return
        process, event = child
        event.set()
        if process.pid is not None:
            process.join(timeout=5)
            if process.is_alive():
                process.terminate()
                process.join(timeout=5)
        if process.is_alive():
            raise RuntimeError("scoring worker did not stop")
        process.close()
        del self.children[deployment_id]

    def _reap_children(self):
        for deployment_id, (process, event) in list(self.children.items()):
            if process.is_alive():
                continue
            record = self.store.get_deployment(deployment_id)
            if record["desired_state"] == "running" and record["status"] != "failed":
                self.store.set_worker_status(
                    deployment_id, "failed", f"scoring process exited with code {process.exitcode}"
                )
            self._stop_child(deployment_id)

    def deployments(self):
        with self.lock:
            self._open()
            self._reap_children()
            return {
                "schema": "opentsx.scoring-deployments.v1",
                "deployments": [
                    public_deployment(record) for record in self.store.list_deployments()
                ],
            }

    def close(self):
        with self.lock:
            if self.owner.closed:
                return
            for deployment_id in list(self.children):
                self._stop_child(deployment_id)
            fcntl.flock(self.owner.fileno(), fcntl.LOCK_UN)
            self.owner.close()
