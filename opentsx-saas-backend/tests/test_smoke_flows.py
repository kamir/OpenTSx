"""smoke-flows.sh against the backend on a local port, with the test database (SQLite in memory) and no lifespan:
every check of the script passes. The socket is bound before the server starts, so the script needs no wait. RETRIEVAL_URL
names a port nobody listens on: the backend refuses the plain account before it forwards anything."""

import os
import socket
import subprocess
import threading
from pathlib import Path

import uvicorn

from app.core.config import settings
from app.db.session import get_db
from app.main import app

SCRIPT = Path(__file__).resolve().parents[1] / "smoke-flows.sh"


def test_every_check_of_the_smoke_test_passes_against_the_backend(database, monkeypatch):
    monkeypatch.setattr(settings, "RETRIEVAL_URL", "http://127.0.0.1:9")
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
    assert "FAIL" not in done.stdout
