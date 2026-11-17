import hashlib
import importlib
import json
from types import SimpleNamespace
import sys

import numpy as np
import pytest

from opentsx import model


def episode(values=None):
    return model.regular(
        model.series_key("voltage", {"origin": "SYNTHETIC"}, "V"),
        1000,
        10_000,
        np.sin(2 * np.pi * 10 * np.arange(4096) / 100) if values is None else values,
        episode_id="01ARZ3NDEKTSV4RRFFQ69G5FA0",
        created_at_us=0,
    )


class Store:
    output_topic = "tsx.workbench-demo.analysis.v1"

    def __init__(self, records=None):
        self.records = [episode()] if records is None else records
        self.published = []
        self.reads = 0

    def check_topic(self, topic):
        if topic != "tsx.workbench-demo.voltage.episodes.v2":
            raise ValueError("outside namespace")

    def episodes(self, topic):
        self.reads += 1
        return self.records

    def publish(self, records):
        self.published.extend(records)


def document(operator="welch", parameters=None):
    return {
        "topic": "tsx.workbench-demo.voltage.episodes.v2",
        "operator": operator,
        "parameters": {} if parameters is None else parameters,
    }


def test_registered_operator_publishes_reproducible_results_and_full_provenance():
    api = importlib.import_module("opentsx.client.research")
    store = Store()
    first = api.execute(store, document())
    second = api.execute(store, document())
    assert first == second
    key, payload = store.published[0]
    assert key == hashlib.sha256(payload).hexdigest()
    result = json.loads(payload)
    assert result["analysis"]["input"]["episode_id"] == episode()["episodeId"]
    assert result["analysis"]["result"]["density_unit"] == "V^2/Hz"
    assert result["preview"]["shown"] == 1024
    assert result["preview"]["total"] == 4096
    assert {item["id"] for item in api.operator_catalog()} == {"welch", "episode_rms"}
    assert all("function" not in item for item in api.operator_catalog())


def test_tutorial_operator_matches_closed_form_and_retains_units():
    api = importlib.import_module("opentsx.algorithms.episode_rms")
    result = api.episode_rms(episode([3, 4]))
    assert result["result"]["value"] == pytest.approx(np.sqrt(12.5))
    assert result["result"]["unit"] == "V"
    assert api.episode_rms(episode([0, 0]))["result"]["value"] == 0


def test_tutorial_operator_rejects_invalid_and_overflowing_values():
    api = importlib.import_module("opentsx.algorithms.episode_rms")
    with pytest.raises(ValueError):
        api.episode_rms(episode([float("nan"), 2]))
    with pytest.raises(FloatingPointError):
        api.episode_rms(episode([1e300, 1e300]))


@pytest.mark.parametrize(
    "changes",
    [
        {"operator": "unknown"},
        {"operator": []},
        {"topic": "private.episodes.v2"},
        {"parameters": {"other": 3}},
        {"parameters": {"nperseg": True}},
        {"parameters": {"nperseg": 4096}},
        {"parameters": []},
        {"unexpected": True},
    ],
)
def test_invalid_run_fails_before_broker_read(changes):
    api = importlib.import_module("opentsx.client.research")
    store = Store()
    with pytest.raises(ValueError):
        api.execute(store, {**document(), **changes})
    assert store.reads == 0
    assert store.published == []


def test_no_publication_if_a_later_episode_is_invalid():
    api = importlib.import_module("opentsx.client.research")
    broken = episode()
    broken["values"][0] = float("inf")
    store = Store([episode(), broken])
    with pytest.raises(ValueError):
        api.execute(store, document())
    assert store.published == []


@pytest.mark.parametrize(
    "host,origin,write,expected",
    [
        ("localhost:18081", None, False, True),
        ("127.0.0.1:18081", "http://127.0.0.1:18081", True, True),
        ("localhost:18081", "http://localhost:18081", True, True),
        ("localhost:18081", "https://untrusted.invalid", True, False),
        ("localhost:18081", None, True, False),
        ("untrusted.invalid", "http://untrusted.invalid", True, False),
        ("localhost:18081/path", None, False, False),
    ],
)
def test_local_request_boundary(host, origin, write, expected):
    api = importlib.import_module("opentsx.client.research")
    assert api.permitted_request(host, origin, write) is expected


def test_topic_namespace_cannot_escape_to_other_data_or_result_topics():
    api = importlib.import_module("opentsx.client.research")
    store = api.KafkaStore("unused", "tsx.workbench-demo.")
    store.check_topic(document()["topic"])
    for topic in [
        store.output_topic,
        "private.episodes.v2",
        "tsx.workbench-demo../bad.episodes.v2",
    ]:
        with pytest.raises(ValueError):
            store.check_topic(topic)


def test_tutorial_operator_runs_through_the_same_publication_path():
    api = importlib.import_module("opentsx.client.research")
    store = Store([episode([3, 4])])
    receipt = api.execute(store, document("episode_rms"))
    assert receipt["published"] == 1
    assert json.loads(store.published[0][1])["analysis"]["result"]["value"] == pytest.approx(
        np.sqrt(12.5)
    )


def test_newly_created_seed_topic_does_not_require_immediate_leader_watermarks(
    monkeypatch,
):
    api = importlib.import_module("opentsx.client.research")
    delivered = []

    class Admin:
        def __init__(self, config):
            pass

        def list_topics(self, timeout):
            return SimpleNamespace(topics={})

        def create_topics(self, topics, request_timeout):
            assert len(topics) == 2
            return {str(index): SimpleNamespace(result=lambda timeout: None) for index in range(2)}

    class Producer:
        def __init__(self, config):
            pass

        def produce(self, topic, key, value, partition, on_delivery):
            delivered.append(model.decode(value))
            on_delivery(None, SimpleNamespace(offset=lambda: partition))

        def flush(self, timeout):
            return 0

    monkeypatch.setitem(sys.modules, "confluent_kafka", SimpleNamespace(Producer=Producer))
    monkeypatch.setitem(
        sys.modules,
        "confluent_kafka.admin",
        SimpleNamespace(AdminClient=Admin, NewTopic=lambda *args, **kwargs: args[0]),
    )
    store = api.KafkaStore("unused", "tsx.workbench-demo.")

    def premature_read(topic):
        raise RuntimeError("leader has not propagated yet")

    monkeypatch.setattr(store, "episodes", premature_read)
    assert store.seed()["status"] == "seeded"
    assert len(delivered) == 2


@pytest.mark.parametrize("operator", ["welch", "episode_rms"])
def test_kafka_result_decoder_validates_actual_published_records(operator):
    api = importlib.import_module("opentsx.client.research")
    store = Store()
    api.execute(store, document(operator))
    key, payload = store.published[0]
    assert api.decode_result(key.encode(), payload)["analysis"]["method"] == operator
    with pytest.raises(ValueError, match="content key"):
        api.decode_result(b"invalid", payload)


@pytest.mark.parametrize("field", ["analysis", "preview"])
def test_malformed_broker_result_is_rejected_before_it_can_reach_the_ui(field):
    api = importlib.import_module("opentsx.client.research")
    store = Store()
    api.execute(store, document())
    record = json.loads(store.published[0][1])
    del record[field]
    payload = json.dumps(record).encode()
    with pytest.raises(ValueError, match="incomplete"):
        api.decode_result(hashlib.sha256(payload).hexdigest().encode(), payload)


def test_payload_cannot_override_the_verified_kafka_content_key():
    api = importlib.import_module("opentsx.client.research")
    store = Store()
    api.execute(store, document())
    record = json.loads(store.published[0][1])
    record["key"] = "unverified-payload-key"
    payload = json.dumps(record).encode()
    key = hashlib.sha256(payload).hexdigest().encode()
    assert api.decode_result(key, payload)["key"] == key.decode()
