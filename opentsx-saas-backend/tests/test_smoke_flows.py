"""smoke-flows.sh against the backend on a local port, with the test database (SQLite in memory) and no lifespan:
every check of the script passes. The socket is bound before the server starts, so the script needs no wait. RETRIEVAL_URL
names a port nobody listens on or is unset: the backend refuses the plain account before it forwards anything, and
without a retrieval server an account that may deploy gets 503."""

import os
import socket
import subprocess
import threading
from pathlib import Path

import pytest
import uvicorn

from app.core.config import settings
from app.db.session import get_db
from app.main import app

SCRIPT = Path(__file__).resolve().parents[1] / "smoke-flows.sh"


@pytest.mark.parametrize("retrieval_url, role, says", [
    ("http://127.0.0.1:9", "superuser", "  ok    a plain account may not deploy DAGs here\n"),
    (None, "superuser", "  ok    a plain account may not deploy DAGs here\n"),
    (None, "user", "  ok    without a retrieval server nothing is deployed\n"),
])
def test_every_check_of_the_smoke_test_passes_against_the_backend(database, monkeypatch, retrieval_url, role, says):
    monkeypatch.setattr(settings, "RETRIEVAL_URL", retrieval_url)
    monkeypatch.setattr(settings, "DAG_RUN_ROLE", role)
    sock = socket.socket()
    sock.bind(("127.0.0.1", 0))
    sock.listen(64)
    app.dependency_overrides[get_db] = lambda: database
    server = uvicorn.Server(uvicorn.Config(app, lifespan="off", log_config=None))
    thread = threading.Thread(target=server.run, kwargs={"sockets": [sock]})
    thread.start()
    try:
        env = {name: value for name, value in os.environ.items() if "proxy" not in name.lower()}
        done = subprocess.run(["bash", str(SCRIPT), f"http://127.0.0.1:{sock.getsockname()[1]}"],
                              capture_output=True, text=True, env=env, timeout=120)
    finally:
        server.should_exit = True
        thread.join()
        app.dependency_overrides.clear()
        sock.close()
    assert done.returncode == 0, done.stdout + done.stderr
    assert "  ok    login answers no refresh token\n" in done.stdout
    assert says in done.stdout
    assert "FAIL" not in done.stdout
