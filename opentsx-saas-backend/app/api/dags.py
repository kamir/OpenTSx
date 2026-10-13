"""DAG runs: the authenticated way to let the retrieval server write vector topics.

The retrieval server runs the vector sinks of an opentsx.dag.v1 document once (POST /v1/dags/run) and has no
accounts. This route checks who asks, then forwards the document with the run token only the backend knows.
"""

import logging

import httpx
from fastapi import APIRouter, Depends, HTTPException, Request, status

from app.core.config import settings
from app.core.deps import get_current_user
from app.models.user import User

router = APIRouter(prefix="/dags", tags=["DAG runs"])
# under uvicorn's logger, so the lines reach the container log without extra logging setup: who wrote what
log = logging.getLogger("uvicorn.error").getChild("dags")

# A DAG document is small; the retrieval server has the same limit.
MAX_DAG_BYTES = 256 * 1024
RUN_TOKEN_HEADER = "X-OpenTSx-Run-Token"


def http_client() -> httpx.AsyncClient:
    """The client towards the retrieval server. A function, so tests can put a transport in its place."""
    # a run retries for up to three minutes while the broker refuses writes; wait longer than that
    return httpx.AsyncClient(timeout=httpx.Timeout(240.0, connect=5.0))


def may_run(user: User) -> bool:
    """Who may write topics: administrators, or every signed-in user when DAG_RUN_ROLE is "user"."""
    return bool(user.is_superuser) or settings.DAG_RUN_ROLE == "user"


@router.get("/capabilities")
async def capabilities(user: User = Depends(get_current_user)):
    """Tells the workbench whether the run button can work for this user."""
    if not settings.RETRIEVAL_URL:
        return {"canRun": False, "reason": "This installation has no retrieval server configured for DAG runs."}
    if not may_run(user):
        return {"canRun": False, "reason": "Only administrators may write topics on this installation."}
    return {"canRun": True, "reason": None}


@router.post("/run")
async def run(request: Request, user: User = Depends(get_current_user)):
    """Runs the vector sinks of the DAG in the body (YAML) once and returns what the retrieval server wrote."""
    if not settings.RETRIEVAL_URL:
        raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE, "This installation has no retrieval server configured for DAG runs.")
    if not may_run(user):
        raise HTTPException(status.HTTP_403_FORBIDDEN, "Only administrators may write topics on this installation.")
    body = await request.body()
    if not body:
        raise HTTPException(status.HTTP_400_BAD_REQUEST, "The request has no DAG.")
    if len(body) > MAX_DAG_BYTES:
        raise HTTPException(413, f"The DAG is larger than {MAX_DAG_BYTES} bytes.")

    headers = {"Content-Type": "application/yaml"}
    if settings.DAG_RUN_TOKEN:
        headers[RUN_TOKEN_HEADER] = settings.DAG_RUN_TOKEN
    url = settings.RETRIEVAL_URL.rstrip("/") + "/v1/dags/run"
    try:
        async with http_client() as client:
            answer = await client.post(url, content=body, headers=headers)
    except httpx.HTTPError as e:
        log.warning("dag run by user %s failed: retrieval server unreachable (%s)", user.id, type(e).__name__)
        raise HTTPException(status.HTTP_502_BAD_GATEWAY, "The retrieval server could not be reached.") from e

    try:
        payload = answer.json()
    except ValueError:
        payload = {}
    if answer.status_code == 200:
        sinks = payload.get("sinks", []) if isinstance(payload, dict) else []
        log.info("dag run by user %s: %s", user.id,
                 ", ".join(f"{s.get('records')} records to {s.get('topic')}" for s in sinks) or "nothing written")
        return payload
    reason = payload.get("error") if isinstance(payload, dict) else None
    log.warning("dag run by user %s refused by the retrieval server: HTTP %s", user.id, answer.status_code)
    if answer.status_code == 400:
        # the DAG itself cannot be run; the reason is meant for the person who built it
        raise HTTPException(status.HTTP_400_BAD_REQUEST, reason or "The retrieval server refused the DAG.")
    if answer.status_code == 401:
        raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE,
                            "The retrieval server did not accept the run token of this installation.")
    if answer.status_code == 404:
        raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE,
                            "The retrieval server of this installation does not run DAGs.")
    raise HTTPException(status.HTTP_502_BAD_GATEWAY, reason or f"The retrieval server answered HTTP {answer.status_code}.")
