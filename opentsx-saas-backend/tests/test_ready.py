"""Readiness: GET /ready of the backend runs SELECT 1 and answers 503 while the database does not answer, and the k3s
Deployments probe readiness and liveness on the routes and files meant for it. No database, no network, no cluster."""

import asyncio
import subprocess
from pathlib import Path

import pytest
import yaml
from fastapi.testclient import TestClient

from app import main
from app.core.config import settings

K3S = Path(__file__).resolve().parents[2] / "deploy" / "k3s"
NOT_READY = {"error": "database: no answer to SELECT 1; check that the database runs and accepts connections",
             "type": "unavailable"}


class Session:
    """What /ready uses of an AsyncSession; ``failure`` is raised by execute, or ``hang`` keeps it from returning."""

    def __init__(self, failure=None, hang=False):
        self.failure = failure
        self.hang = hang
        self.statements = []

    async def __aenter__(self):
        return self

    async def __aexit__(self, *exc):
        return False

    async def execute(self, statement):
        self.statements.append(str(statement))
        if self.failure is not None:
            raise self.failure
        if self.hang:
            await asyncio.Event().wait()


def ready(monkeypatch, session):
    monkeypatch.setattr(main, "AsyncSessionLocal", lambda: session)
    return TestClient(main.app).get("/ready")


def test_ready_answers_200_when_the_database_answers_select_1(monkeypatch):
    session = Session()
    answer = ready(monkeypatch, session)
    assert (answer.status_code, answer.json()) == (200, {"status": "ready"})
    assert session.statements == ["SELECT 1"]


def test_ready_answers_503_when_the_session_raises_and_logs_the_cause(monkeypatch, caplog):
    from sqlalchemy.exc import OperationalError

    answer = ready(monkeypatch, Session(failure=OperationalError("SELECT 1", {}, ConnectionRefusedError("refused"))))
    assert (answer.status_code, answer.json()) == (503, NOT_READY)
    lines = [(r.levelname, r.getMessage()) for r in caplog.records if r.name == "app.main"]
    assert len(lines) == 1, lines
    level, message = lines[0]
    assert level == "WARNING"
    where = f"{settings.POSTGRES_SERVER}:{settings.POSTGRES_PORT}/{settings.POSTGRES_DB}"
    assert message.startswith(f"not ready db={where} cause=OperationalError: "), message
    assert "refused" in message, message


def test_ready_answers_503_when_the_database_does_not_answer_in_time(monkeypatch):
    monkeypatch.setattr(main, "READY_TIMEOUT_SECONDS", 0.01)
    answer = ready(monkeypatch, Session(hang=True))
    assert (answer.status_code, answer.json()) == (503, NOT_READY)


def containers(path):
    """name of the Deployment -> its first container."""
    found = {}
    for doc in yaml.safe_load_all((K3S / path).read_text()):
        if doc and doc["kind"] == "Deployment":
            found[doc["metadata"]["name"]] = doc["spec"]["template"]["spec"]["containers"][0]
    return found


HTTP = {
    ("base/retrieval.yaml", "opentsx-retrieval"): {"readinessProbe": "/v1/ready", "livenessProbe": "/v1/health"},
    ("components/event-bridge/event-bridge.yaml", "opentsx-event-bridge"): {"readinessProbe": "/v1/ready",
                                                                           "livenessProbe": "/v1/health"},
    ("components/saas/saas-backend.yaml", "opentsx-saas-backend"): {"startupProbe": "/health", "readinessProbe": "/ready",
                                                                   "livenessProbe": "/health"},
}

# the progress file each processor writes, and the age in seconds above which a probe fails
EXEC = {
    ("base/stats.yaml", "opentsx-stats"): ("/state/stats.ckpt.progress",
                                           {"startupProbe": 120, "readinessProbe": 120, "livenessProbe": 600}),
    ("components/vector-processor/vector-processor.yaml", "opentsx-vectors"): (
        "/state/progress", {"startupProbe": 120, "readinessProbe": 120, "livenessProbe": 600}),
}


@pytest.mark.parametrize("where", sorted(HTTP), ids=lambda w: w[1])
def test_the_http_probes_check_readiness_and_liveness_on_their_own_routes(where):
    path, name = where
    container = containers(path)[name]
    probes = {kind: container[kind]["httpGet"]["path"] for kind in ("startupProbe", "readinessProbe", "livenessProbe")
              if kind in container}
    assert probes == HTTP[where]


# the HTTP readiness checks that ask a database or a broker, and how long the kubelet waits for their answer
TIMEOUTS = {
    ("components/event-bridge/event-bridge.yaml", "opentsx-event-bridge"): 3,
    ("components/saas/saas-backend.yaml", "opentsx-saas-backend"): 3,
}


@pytest.mark.parametrize("where", sorted(TIMEOUTS), ids=lambda w: w[1])
def test_a_readiness_check_that_asks_another_service_gets_more_than_the_default_second(where):
    path, name = where
    assert containers(path)[name]["readinessProbe"].get("timeoutSeconds") == TIMEOUTS[where]


NOW = 1767225600


def probe_exit(script, progress, file, age):
    """Exit code of the probe script at the time NOW against ``file`` instead of ``progress``, with the file ``age``
    seconds old."""
    assert "$(date +%s)" in script, script
    if age is not None:
        file.write_text(f"{NOW - age}\n")
    elif file.exists():
        file.unlink()
    return subprocess.run(["/bin/sh", "-c", script.replace(progress, str(file)).replace("$(date +%s)", str(NOW))],
                          capture_output=True).returncode


@pytest.mark.parametrize("where", sorted(EXEC), ids=lambda w: w[1])
def test_the_exec_probes_fail_on_a_missing_or_old_progress_file(where, tmp_path):
    path, name = where
    progress, limits = EXEC[where]
    container = containers(path)[name]
    assert sorted(k for k in ("startupProbe", "readinessProbe", "livenessProbe") if k in container) == sorted(limits)
    for kind, limit in limits.items():
        command = container[kind]["exec"]["command"]
        assert command[:2] == ["/bin/sh", "-c"], command
        assert progress in command[2], command
        file = tmp_path / "progress"
        assert probe_exit(command[2], progress, file, limit - 1) == 0, f"{kind}: a file {limit - 1} s old is fresh"
        assert probe_exit(command[2], progress, file, limit) != 0, f"{kind}: a file {limit} s old is stale"
        assert probe_exit(command[2], progress, file, None) != 0, f"{kind}: no file is no progress"
