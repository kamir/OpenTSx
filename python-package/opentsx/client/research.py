"""Local research service for finite modelling and bounded Kafka analysis."""

import argparse
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import mimetypes
from pathlib import Path
import re
import signal
from urllib.parse import parse_qs, unquote, urlsplit

import numpy as np

from opentsx import __version__, model
from opentsx.algorithms._analysis import integer, regular_values
from opentsx.algorithms.episode_rms import episode_rms
from opentsx.algorithms.spectral import welch_spectrum
from opentsx.client.topics import TopicLimitReached, TopicReader
from opentsx.client.modelling import MAX_BODY, run_modelling
from opentsx.client.registry import RegistryError, RegistryUnavailable

OPERATORS = {
    "welch": {
        "label": "Welch power density",
        "output": "spectrum",
        "parameters": {"nperseg": {"default": 256, "min": 4, "max": 2048}},
        "function": welch_spectrum,
    },
    "episode_rms": {
        "label": "Episode RMS",
        "output": "scalar",
        "parameters": {},
        "function": episode_rms,
    },
}


def operator_catalog():
    return [
        {"id": key, **{k: v for k, v in spec.items() if k != "function"}}
        for key, spec in OPERATORS.items()
    ]


def encode_result(topic, analysis, preview):
    envelope = {
        "schema": "opentsx.analysis-result.v1",
        "source_topic": topic,
        "analysis": analysis,
        "preview": preview,
    }
    payload = json.dumps(envelope, sort_keys=True, separators=(",", ":"), allow_nan=False).encode()
    return hashlib.sha256(payload).hexdigest(), payload


def decode_result(key, payload):
    if key != hashlib.sha256(payload).hexdigest().encode():
        raise ValueError("result content key does not match payload")
    result = json.loads(payload)
    json.dumps(result, allow_nan=False)
    try:
        if result["schema"] != "opentsx.analysis-result.v1" or not isinstance(
            result["source_topic"], str
        ):
            raise ValueError("unsupported result schema")
        analysis, preview = result["analysis"], result["preview"]
        if not isinstance(analysis["method"], str) or not isinstance(analysis["parameters"], dict):
            raise ValueError("invalid analysis identity or parameters")
        for field in ("name", "version"):
            if not isinstance(analysis["backend"][field], str):
                raise ValueError("invalid analysis backend")
        for field in ("episode_id", "values_sha256"):
            if not isinstance(analysis["input"][field], str):
                raise ValueError("invalid input provenance")
        if analysis["input"]["unit"] is not None and not isinstance(analysis["input"]["unit"], str):
            raise ValueError("invalid measurement unit")
        if not isinstance(analysis["warnings"], list) or any(
            not isinstance(w, str) for w in analysis["warnings"]
        ):
            raise ValueError("invalid analysis warnings")
        samples, _ = regular_values(preview["values"], minimum=1, allow_constant=True)
        if (
            len(samples) != integer(preview["shown"], "shown")
            or len(samples) > 1024
            or preview["total"] != integer(analysis["input"]["nobs"], "nobs")
            or preview["total"] < len(samples)
        ):
            raise ValueError("invalid preview dimensions")
        integer(preview["interval_micros"], "interval_micros")
        if not all(isinstance(preview[field], str) for field in ("metric", "source")):
            raise ValueError("invalid preview provenance")
        values = analysis["result"]
        if "frequency" in values:
            frequency, _ = regular_values(values["frequency"], minimum=1, allow_constant=True)
            density, _ = regular_values(values["density"], minimum=1, allow_constant=True)
            if len(frequency) != len(density) or not isinstance(
                values["density_unit"], (str, type(None))
            ):
                raise ValueError("invalid spectrum dimensions or unit")
        else:
            regular_values([values["value"]], minimum=1, allow_constant=True)
            if not isinstance(values["unit"], (str, type(None))):
                raise ValueError("invalid scalar unit")
    except (KeyError, TypeError) as error:
        raise ValueError("incomplete analysis result record") from error
    return {**result, "key": key.decode("ascii")}


def execute(store, document):
    if not isinstance(document, dict) or set(document) != {
        "topic",
        "operator",
        "parameters",
    }:
        raise ValueError("request requires exactly topic, operator and parameters")
    topic, name, parameters = (document[key] for key in ("topic", "operator", "parameters"))
    store.check_topic(topic)
    if not isinstance(name, str) or name not in OPERATORS:
        raise ValueError("unknown operator")
    spec = OPERATORS[name]
    if not isinstance(parameters, dict) or set(parameters) - set(spec["parameters"]):
        raise ValueError("unknown operator parameters")
    options = {}
    for key, limits in spec["parameters"].items():
        value = integer(parameters.get(key, limits["default"]), key, minimum=limits["min"])
        if value > limits["max"]:
            raise ValueError(f"{key} exceeds the local workbench limit")
        options[key] = value
    episodes = store.episodes(topic)
    if len(episodes) > 20:
        raise ValueError("local workbench accepts at most 20 episodes per run")
    prepared = []
    for episode in episodes:
        if episode["count"] > 262_144:
            raise ValueError("episode exceeds the local 262144 sample limit")
        analysis = spec["function"](episode, **options)
        preview = {
            "interval_micros": analysis["input"]["interval_micros"],
            "metric": episode["series"]["metric"],
            "source": episode["provenance"]["source"],
            "values": episode["values"][:1024],
            "shown": min(1024, episode["count"]),
            "total": episode["count"],
        }
        prepared.append(encode_result(topic, analysis, preview))
    store.publish(prepared)
    return {
        "published": len(prepared),
        "keys": [key for key, _ in prepared],
        "output_topic": store.output_topic,
    }


def permitted_request(host, origin, write=False):
    try:
        parsed = urlsplit("http://" + host)
        valid = (
            parsed.hostname in ("localhost", "127.0.0.1")
            and parsed.netloc == host
            and parsed.username is None
            and (parsed.port is None or 1 <= parsed.port <= 65535)
        )
    except ValueError:
        return False
    return valid and (not write or origin == "http://" + host)


class KafkaStore:
    def __init__(self, bootstrap, prefix):
        if not re.fullmatch(r"[A-Za-z0-9_-][A-Za-z0-9._-]{0,180}\.", prefix):
            raise ValueError("prefix must be a valid Kafka topic prefix ending in a dot")
        self.bootstrap, self.prefix = bootstrap, prefix
        self.output_topic = prefix + "analysis.v1"

    def check_topic(self, topic):
        if (
            not isinstance(topic, str)
            or not topic.startswith(self.prefix)
            or not topic.endswith(".episodes.v2")
            or not re.fullmatch(r"[A-Za-z0-9._-]{1,249}", topic)
        ):
            raise ValueError("topic is outside the configured episode namespace")

    def topics(self):
        from confluent_kafka.admin import AdminClient

        metadata = AdminClient({"bootstrap.servers": self.bootstrap}).list_topics(timeout=10)
        return sorted(
            name
            for name, topic in metadata.topics.items()
            if name.startswith(self.prefix)
            and name.endswith(".episodes.v2")
            and topic.error is None
        )

    def episodes(self, topic):
        from confluent_kafka import Consumer

        self.check_topic(topic)
        reader = TopicReader(
            self.bootstrap,
            consumer_factory=lambda config: Consumer({**config, "allow.auto.create.topics": False}),
            timeout_s=10,
        )
        return reader.episodes(topic, max_records=200)

    def publish(self, records):
        from confluent_kafka import Producer

        producer = Producer(
            {
                "bootstrap.servers": self.bootstrap,
                "acks": "all",
                "enable.idempotence": False,
                "max.in.flight.requests.per.connection": 1,
                "delivery.timeout.ms": 30000,
            }
        )
        errors, delivered = [], []

        def acknowledge(error, message):
            if error is not None:
                errors.append(str(error))
            else:
                delivered.append(message.offset())

        for key, payload in records:
            producer.produce(
                self.output_topic,
                key=key.encode(),
                value=payload,
                on_delivery=acknowledge,
            )
            producer.poll(0)
        if producer.flush(30):
            raise TimeoutError("analysis delivery timed out; replay may append duplicates")
        if errors or len(delivered) != len(records):
            raise RuntimeError("analysis delivery was not acknowledged")

    def results(self):
        from confluent_kafka import Consumer, TopicPartition

        consumer = Consumer(
            {
                "bootstrap.servers": self.bootstrap,
                "group.id": "opentsx-research-reader",
                "enable.auto.commit": False,
                "allow.auto.create.topics": False,
            }
        )
        latest = {}
        try:
            metadata = consumer.list_topics(self.output_topic, timeout=10)
            topic = metadata.topics[self.output_topic]
            if topic.error is not None:
                raise RuntimeError("result topic is unavailable; run the seed command")
            ends, assigned = {}, []
            count = 0
            for partition in sorted(topic.partitions):
                low, high = consumer.get_watermark_offsets(
                    TopicPartition(self.output_topic, partition), timeout=10
                )
                count += high - low
                if high > low:
                    ends[partition] = high
                    assigned.append(TopicPartition(self.output_topic, partition, low))
            if count > 200:
                raise TopicLimitReached("result snapshot exceeds the local 200 record limit")
            consumer.assign(assigned)
            pending = set(ends)
            while pending:
                message = consumer.poll(10)
                if message is None:
                    raise TimeoutError("result snapshot read timed out")
                if message.error():
                    if message.error().code() == -191:
                        pending.discard(message.partition())
                        continue
                    raise RuntimeError("result snapshot broker error")
                partition, offset = message.partition(), message.offset()
                if partition not in pending:
                    continue
                if offset + 1 >= ends[partition]:
                    pending.discard(partition)
                if offset >= ends[partition]:
                    continue
                payload = message.value()
                record = decode_result(message.key(), payload)
                self.check_topic(record["source_topic"])
                latest[record["key"]] = record
        finally:
            consumer.close()
        return list(latest.values())

    def seed(self):
        from confluent_kafka.admin import AdminClient, NewTopic
        from confluent_kafka import Producer

        admin = AdminClient({"bootstrap.servers": self.bootstrap})
        metadata = admin.list_topics(timeout=10)
        names = [self.prefix + "voltage.episodes.v2", self.output_topic]
        missing = [
            NewTopic(
                name,
                num_partitions=3,
                replication_factor=3,
                config={"retention.ms": "86400000", "min.insync.replicas": "2"},
            )
            for name in names
            if name not in metadata.topics
        ]
        for future in admin.create_topics(missing, request_timeout=30).values() if missing else []:
            future.result(timeout=35)
        if names[0] in metadata.topics:
            existing = self.episodes(names[0])
            if existing:
                expected = {f"01ARZ3NDEKTSV4RRFFQ69G5FA{index}" for index in range(2)}
                if {record["episodeId"] for record in existing} != expected:
                    raise ValueError(
                        "existing tutorial topic is incomplete or contains other data; use a new prefix"
                    )
                return {"status": "already_seeded", "topic": names[0]}
        errors, delivered = [], []

        def acknowledge(error, message):
            if error is not None:
                errors.append(str(error))
            else:
                delivered.append(message.offset())

        producer = Producer({"bootstrap.servers": self.bootstrap, "acks": "all"})
        for index, frequency in enumerate((10, 20)):
            episode = model.regular(
                model.series_key("voltage", {"fixture": str(index), "origin": "SYNTHETIC"}, "V"),
                1_792_699_200_000_000 + index * 40_960_000,
                10_000,
                np.sin(2 * np.pi * frequency * np.arange(4096) / 100),
                episode_id=f"01ARZ3NDEKTSV4RRFFQ69G5FA{index}",
                created_at_us=0,
                source="synthetic-workbench-tutorial",
            )
            producer.produce(
                names[0],
                key=episode["series"]["seriesId"],
                value=model.encode("Episode", episode),
                partition=index,
                on_delivery=acknowledge,
            )
        if producer.flush(30) or errors or len(delivered) != 2:
            raise RuntimeError("synthetic episode delivery was not acknowledged")
        return {"status": "seeded", "topic": names[0], "episodes": 2}


def reject_json_constant(value):
    raise ValueError("nonfinite JSON values are not allowed")


def manifest_object(pairs):
    document = {}
    for key, value in pairs:
        if key in document:
            raise ValueError("duplicate manifest key")
        document[key] = value
    return document


def lifecycle_get(lifecycle, path, query):
    if lifecycle is None:
        return 503, {"error": "Model lifecycle state is unavailable in this service."}
    if path == "/analysis/v1/modelling/models":
        return 200, {
            "schema": "opentsx.model-registry.v1",
            "topic_prefix": lifecycle.prefix,
            "models": lifecycle.store.list_models(),
        }
    if path == "/analysis/v1/modelling/context-topics":
        if query:
            raise ValueError("context topics do not accept query parameters")
        return 200, lifecycle.context_catalogue.topics()
    if path == "/analysis/v1/modelling/context-series":
        if set(query) != {"topic"} or len(query["topic"]) != 1:
            raise ValueError("context catalogue requires one topic query parameter")
        return 200, lifecycle.context_catalogue.series(query["topic"][0])
    if path == "/analysis/v1/modelling/series":
        if set(query) != {"topic"} or len(query["topic"]) != 1:
            raise ValueError("series catalogue requires one topic query parameter")
        return 200, lifecycle.catalogue.series(query["topic"][0])
    if path == "/analysis/v1/modelling/deployments":
        return 200, lifecycle.deployments()
    match = re.fullmatch(
        r"/analysis/v1/modelling/deployments/([a-z0-9][a-z0-9_-]{0,63})/results", path
    )
    if match:
        return 200, lifecycle.store.results(match[1])
    return 404, {"error": "unknown lifecycle route"}


def lifecycle_post(lifecycle, path, document):
    if lifecycle is None:
        return 503, {"error": "Model lifecycle state is unavailable in this service."}
    if path == "/analysis/v1/modelling/capture":
        return 200, lifecycle.catalogue.capture(document)
    if path == "/analysis/v1/modelling/context-capture":
        return 200, lifecycle.context_catalogue.capture(document)
    if path == "/analysis/v1/modelling/models":
        model = lifecycle.store.register(document, lifecycle.registry)
        return 200, {key: value for key, value in model.items() if key != "artifact"}
    if path == "/analysis/v1/modelling/deployments":
        return 200, lifecycle.deploy(document)
    match = re.fullmatch(
        r"/analysis/v1/modelling/deployments/([a-z0-9][a-z0-9_-]{0,63})/(start|stop)",
        path,
    )
    if match:
        from opentsx.client.modelling import fields

        fields(document, set())
        return 200, getattr(lifecycle, match[2])(match[1])
    match = re.fullmatch(r"/analysis/v1/modelling/models/([a-f0-9]{64})/predict", path)
    if match:
        from opentsx.algorithms.fitted import predict_artifact
        from opentsx.client.modelling import fields, integer, matrix

        fields(document, {"steps"}, {"future_exog", "future_available_at_micros"})
        integer(document["steps"], "prediction steps", 1, 96)
        model = lifecycle.store.get_model(match[1])
        artifact = model["artifact"]
        names = artifact.get("context_names", [])
        if names:
            if "future_exog" not in document or "future_available_at_micros" not in document:
                raise ValueError(
                    "registered context requires future values and availability coordinates"
                )
            matrix(document["future_exog"], document["steps"], len(names), "future context")
            matrix(
                document["future_available_at_micros"],
                document["steps"],
                len(names),
                "future context availability",
                True,
            )
            cutoff = (
                artifact["initial_checkpoint"]["next_timestamp_micros"]
                - artifact["source"]["interval_micros"]
            )
            if any(
                stamp > cutoff for row in document["future_available_at_micros"] for stamp in row
            ):
                raise ValueError(
                    "future context was not available at the registered forecast origin"
                )
        elif "future_exog" in document or "future_available_at_micros" in document:
            raise ValueError("this registered model does not accept external context")
        lifecycle.registry.verify(model)
        forecast = predict_artifact(
            model["artifact"],
            steps=document["steps"],
            future_exog=document.get("future_exog"),
        )
        return 200, {
            "schema": "opentsx.registered-prediction.v1",
            "model_id": model["id"],
            "artifact_id": model["artifact_id"],
            "forecast": forecast,
        }
    return 404, {"error": "unknown lifecycle route"}


def handler(store, web_root, manifest, lifecycle=None):
    root = Path(web_root).resolve() if web_root is not None else None

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, format, *args):
            print(
                f"research HTTP {self.command} {self.path.split('?')[0]} {args[1]}",
                flush=True,
            )

        def respond(self, status, payload):
            raw = json.dumps(payload, allow_nan=False).encode()
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(raw)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(raw)

        def do_GET(self):
            if not permitted_request(self.headers.get("Host", ""), None):
                return self.respond(403, {"error": "local host required"})
            try:
                parsed = urlsplit(self.path)
                path = unquote(parsed.path)
                if path.startswith("/analysis/v1/modelling/"):
                    status, result = lifecycle_get(lifecycle, path, parse_qs(parsed.query))
                    return self.respond(status, result)
                if path == "/analysis/v1/health":
                    return self.respond(
                        200, {"status": "ready", "version": __version__, **manifest}
                    )
                if path == "/analysis/v1/operators":
                    return self.respond(200, operator_catalog())
                if path == "/analysis/v1/topics":
                    if store is None:
                        return self.respond(
                            503, {"error": "Broker access is unavailable in this service."}
                        )
                    return self.respond(
                        200,
                        {"topics": store.topics(), "output_topic": store.output_topic},
                    )
                if path == "/analysis/v1/results":
                    if store is None:
                        return self.respond(
                            503, {"error": "Broker access is unavailable in this service."}
                        )
                    return self.respond(200, store.results())
                if path.startswith("/analysis/"):
                    return self.respond(404, {"error": "unknown API route"})
                if root is None:
                    return self.respond(404, {"error": "Static UI is unavailable in this service."})
                target = (root / path.lstrip("/")).resolve()
                if not target.is_relative_to(root):
                    return self.respond(403, {"error": "path outside web root"})
                if not target.is_file():
                    if "." in Path(path).name:
                        return self.respond(404, {"error": "asset not found"})
                    target = root / "index.html"
                raw = target.read_bytes()
                self.send_response(200)
                self.send_header(
                    "Content-Type",
                    mimetypes.guess_type(target.name)[0] or "application/octet-stream",
                )
                self.send_header("Content-Length", str(len(raw)))
                self.send_header("Cache-Control", "no-cache")
                self.send_header("X-Content-Type-Options", "nosniff")
                self.end_headers()
                self.wfile.write(raw)
            except (RegistryError, RegistryUnavailable) as error:
                self.respond(503, {"error": str(error)})
            except KeyError:
                self.respond(404, {"error": "requested lifecycle record was not found"})
            except (ValueError, TopicLimitReached) as error:
                self.respond(400, {"error": str(error)})
            except Exception as error:
                print(f"research read failed: {type(error).__name__}", flush=True)
                self.respond(
                    502,
                    {"error": "broker or artifact unavailable; inspect service logs"},
                )

        def do_POST(self):
            if not permitted_request(
                self.headers.get("Host", ""), self.headers.get("Origin"), write=True
            ):
                return self.respond(403, {"error": "same-origin local request required"})
            modelling = self.path.startswith("/analysis/v1/modelling/")
            if self.path != "/analysis/v1/runs" and not modelling:
                return self.respond(404, {"error": "unknown API route"})
            try:
                length = int(self.headers.get("Content-Length", "0"))
                limit = MAX_BODY if modelling else 16384
                if not 0 < length <= limit or self.headers.get_content_type() != "application/json":
                    return self.respond(
                        400, {"error": f"request must be JSON of at most {limit} bytes"}
                    )
                document = json.loads(self.rfile.read(length), parse_constant=reject_json_constant)
                if self.path == "/analysis/v1/modelling/runs":
                    status, result = run_modelling(document)
                    if status == 200 and lifecycle is not None:
                        lifecycle.store.save_run(document, result)
                    return self.respond(status, result)
                if modelling:
                    status, result = lifecycle_post(lifecycle, self.path, document)
                    return self.respond(status, result)
                if store is None:
                    return self.respond(
                        503, {"error": "Broker access is unavailable in this service."}
                    )
                result = execute(store, document)
                self.respond(200, result)
            except (RegistryError, RegistryUnavailable) as error:
                self.respond(503, {"error": str(error)})
            except KeyError:
                self.respond(404, {"error": "requested lifecycle record was not found"})
            except (ValueError, TopicLimitReached, FloatingPointError) as error:
                self.respond(400, {"error": str(error)})
            except Exception as error:
                print(f"research run failed: {type(error).__name__}", flush=True)
                self.respond(
                    502,
                    {"error": "analysis failed; inspect service logs before replaying"},
                )

    return Handler


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "command",
        choices=(
            "serve",
            "serve-finite",
            "seed",
            "demo-seed",
            "demo-append",
            "demo-target",
            "demo-context",
        ),
    )
    parser.add_argument("--scenario", choices=("ar1", "sarimax"), default="ar1")
    parser.add_argument("--bootstrap")
    parser.add_argument("--prefix", default="tsx.workbench-demo.")
    parser.add_argument("--web-root")
    parser.add_argument("--manifest")
    parser.add_argument("--port", type=int, default=8080)
    parser.add_argument("--state-dir")
    parser.add_argument("--registry-url")
    args = parser.parse_args(argv)
    finite = args.command == "serve-finite"
    if finite:
        if any(value is not None for value in (args.bootstrap, args.registry_url, args.state_dir)):
            parser.error("serve-finite does not accept --bootstrap, --registry-url or --state-dir")
    elif not args.bootstrap:
        parser.error(f"{args.command} requires --bootstrap")
    if args.command.startswith("demo-"):
        from opentsx.client.scoring_demo import main as scoring_demo

        scoring_demo(
            [
                args.command.removeprefix("demo-"),
                "--bootstrap",
                args.bootstrap,
                "--prefix",
                args.prefix,
            ]
            + (["--scenario", args.scenario] if args.scenario != "ar1" else [])
        )
        return
    if args.command == "seed":
        store = KafkaStore(args.bootstrap, args.prefix)
        print(json.dumps(store.seed()))
        return
    if not finite:
        if not args.web_root or not args.manifest:
            parser.error("serve requires --web-root and --manifest")
        if not args.state_dir or not args.registry_url:
            parser.error("serve requires --state-dir and --registry-url")
    if args.web_root is not None and (
        not args.web_root or not (Path(args.web_root) / "index.html").is_file()
    ):
        parser.error("web root has no built index.html")
    manifest = {}
    if args.manifest is not None:
        try:
            manifest = json.loads(
                Path(args.manifest).read_text(encoding="utf-8"),
                parse_constant=reject_json_constant,
                object_pairs_hook=manifest_object,
            )
            if not isinstance(manifest, dict):
                raise ValueError("manifest must be a JSON object")
            json.dumps(manifest, allow_nan=False)
        except (OSError, ValueError) as error:
            parser.error(f"invalid manifest: {error}")
    store = None
    lifecycle = None
    if not finite:
        from opentsx.client.lifecycle import LifecycleStore
        from opentsx.client.registry import MLflowRegistry
        from opentsx.client.smart_topics import SmartTopics
        from opentsx.client.topic_catalogue import TopicCatalogue

        store = KafkaStore(args.bootstrap, args.prefix)
        lifecycle = SmartTopics(
            LifecycleStore(Path(args.state_dir) / "lifecycle.sqlite"),
            MLflowRegistry(args.registry_url),
            TopicCatalogue(args.bootstrap, args.prefix),
            args.bootstrap,
            args.prefix,
        )

    def terminate(signum, frame):
        raise SystemExit(0)

    server = None
    try:
        if lifecycle is not None:
            lifecycle.recover()
        server = ThreadingHTTPServer(
            ("127.0.0.1" if finite else "0.0.0.0", args.port),
            handler(store, args.web_root, manifest, lifecycle),
        )
        print(
            json.dumps({"service": "opentsx-research", "version": __version__, **manifest}),
            flush=True,
        )
        signal.signal(signal.SIGTERM, terminate)
        server.serve_forever()
    finally:
        try:
            if lifecycle is not None:
                lifecycle.close()
        finally:
            if server is not None:
                server.server_close()


if __name__ == "__main__":
    main()
