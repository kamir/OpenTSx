from types import SimpleNamespace

import pytest

from opentsx.client import TopicReader
from opentsx.client import topics


class Clock:
    now = 0.0

    def monotonic(self):
        return self.now


class Consumer:
    def __init__(self, clock, events=(), durations=()):
        self.clock = clock
        self.events = iter(events)
        self.durations = iter(durations)
        self.calls = []
        self.closed = 0
        self.assigned = False

    def advance(self, name, timeout):
        self.calls.append((name, timeout))
        self.clock.now += next(self.durations, 0)

    def list_topics(self, topic, timeout):
        self.advance("metadata", timeout)
        return SimpleNamespace(topics={topic: SimpleNamespace(error=None, partitions={0: None})})

    def get_watermark_offsets(self, partition, timeout):
        self.advance("watermark", timeout)
        return 0, 1

    def assign(self, partitions):
        self.assigned = True

    def poll(self, timeout):
        self.advance("poll", timeout)
        return next(self.events, None)

    def close(self):
        self.closed += 1


def message(error):
    return SimpleNamespace(error=lambda: error, partition=lambda: 0)


def coordinator_error(
    fatal=False,
    code=-165,
    text="FindCoordinator response error: Local: Required feature not supported by broker",
):
    return SimpleNamespace(fatal=lambda: fatal, code=lambda: code, str=lambda: text)


@pytest.fixture
def clock(monkeypatch):
    value = Clock()
    monkeypatch.setattr(topics, "monotonic", value.monotonic, raising=False)
    return value


@pytest.mark.parametrize("field,value", [("start", "2026-01-01"), ("end", "invalid")])
def test_invalid_time_does_not_leave_a_consumer_open(clock, field, value):
    consumer = Consumer(clock)
    created = []
    reader = TopicReader(
        "broker:9092", consumer_factory=lambda conf: created.append(conf) or consumer
    )
    with pytest.raises(ValueError):
        reader.episodes("episodes", **{field: value})
    assert not created or consumer.closed == 1


def test_one_deadline_reduces_each_broker_call_timeout(clock):
    consumer = Consumer(
        clock, [message(coordinator_error(code=-191, text="End of partition"))], [2, 3, 1]
    )
    reader = TopicReader("broker:9092", consumer_factory=lambda conf: consumer, timeout_s=10)
    assert reader.episodes("episodes") == []
    assert consumer.calls == [("metadata", 10), ("watermark", 8), ("poll", 5)]
    assert consumer.closed == 1


@pytest.mark.parametrize("durations,expected_calls", [([10], 1), ([2, 8], 2), ([2, 3, 5], 3)])
def test_expired_deadline_rejects_even_a_successful_last_reply(clock, durations, expected_calls):
    consumer = Consumer(
        clock, [message(coordinator_error(code=-191, text="End of partition"))], durations
    )
    reader = TopicReader("broker:9092", consumer_factory=lambda conf: consumer, timeout_s=10)
    with pytest.raises(TimeoutError):
        reader.episodes("episodes")
    assert len(consumer.calls) == expected_calls
    assert consumer.closed == 1


def test_assigned_reader_ignores_only_the_nonfatal_coordinator_notice(clock):
    consumer = Consumer(
        clock,
        [
            message(coordinator_error()),
            message(coordinator_error(code=-191, text="End of partition")),
        ],
    )
    confs = []
    reader = TopicReader(
        "broker:9092", consumer_factory=lambda conf: confs.append(conf) or consumer
    )
    assert reader.episodes("episodes") == []
    assert consumer.assigned and consumer.closed == 1
    assert confs[0]["group.id"] == "opentsx-notebook-reader"
    assert confs[0]["enable.auto.commit"] is False
    assert confs[0]["enable.auto.offset.store"] is False


@pytest.mark.parametrize(
    "error",
    [
        coordinator_error(fatal=True),
        coordinator_error(code=1),
        coordinator_error(text="Unsupported unrelated operation"),
    ],
)
def test_other_errors_still_fail_and_close(clock, error):
    consumer = Consumer(clock, [message(error)])
    with pytest.raises(RuntimeError, match="reading episodes"):
        TopicReader("broker:9092", consumer_factory=lambda conf: consumer).episodes("episodes")
    assert consumer.closed == 1


def test_coordinator_events_have_a_separate_finite_cap(clock):
    consumer = Consumer(clock, [message(coordinator_error())] * 33)
    with pytest.raises(RuntimeError, match="coordinator event limit"):
        TopicReader("broker:9092", consumer_factory=lambda conf: consumer).episodes("episodes")
    assert consumer.closed == 1


def test_coordinator_notice_cannot_extend_the_deadline(clock):
    consumer = Consumer(clock, [message(coordinator_error())] * 5, [1, 1, 4, 4])
    with pytest.raises(TimeoutError):
        TopicReader("broker:9092", consumer_factory=lambda conf: consumer, timeout_s=10).episodes(
            "episodes"
        )
    assert consumer.closed == 1


@pytest.mark.parametrize("timeout", [0, -1, float("inf"), float("nan")])
def test_timeout_must_be_finite_and_positive(timeout):
    with pytest.raises(ValueError, match="timeout_s must be finite and positive"):
        TopicReader("broker:9092", timeout_s=timeout)


def test_metadata_error_before_assignment_is_not_filtered(clock):
    consumer = Consumer(clock)

    def fail(topic, timeout):
        raise RuntimeError("FindCoordinator response error: unsupported")

    consumer.list_topics = fail
    with pytest.raises(RuntimeError, match="FindCoordinator response error"):
        TopicReader("broker:9092", consumer_factory=lambda conf: consumer).episodes("episodes")
    assert not consumer.assigned
    assert consumer.closed == 1


@pytest.mark.parametrize(
    "offsets", [[(0, 5), (0, 6), (1, 5)], [(0, 4), (0, 5), (1, 5)], [(0, 6), (1, 5)]]
)
def test_two_partitions_keep_only_the_initial_watermark_range(clock, monkeypatch, offsets):
    events = [
        SimpleNamespace(
            error=lambda: None,
            partition=lambda p=p: p,
            offset=lambda o=o: o,
            value=lambda p=p, o=o: (p, o),
        )
        for p, o in offsets
    ]
    consumer = Consumer(clock, events)
    consumer.list_topics = lambda topic, timeout: SimpleNamespace(
        topics={topic: SimpleNamespace(error=None, partitions={0: None, 1: None})}
    )
    consumer.get_watermark_offsets = lambda partition, timeout: (5, 6)
    decoded = []

    def decode(payload):
        decoded.append(payload)
        return "Episode", {
            "episodeId": str(payload),
            "series": {"seriesId": "synthetic"},
            "tStart": 0,
            "tEnd": 1,
        }

    monkeypatch.setattr(topics.m, "decode", decode)
    got = TopicReader("broker:9092", consumer_factory=lambda conf: consumer).episodes(
        "episodes", max_records=2
    )
    expected = [(p, o) for p, o in offsets if o == 5]
    assert decoded == expected
    assert {e["episodeId"] for e in got} == {str(payload) for payload in expected}
    assert len(got) <= 2
    assert consumer.closed == 1
