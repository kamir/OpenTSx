"""DAG deployments: the authenticated way to let the vector processor write vector topics.

The retrieval server hands a DAG to the vector processor (POST /v1/dags/deploy writes to its control topic) and has
no accounts. These routes check who asks, then forward with the run token only the backend knows.
"""

import logging

import httpx
from fastapi import APIRouter, Depends, HTTPException, Query, Request, status

from app.core.config import settings
from app.core.deps import get_current_user
from app.models.user import User

router = APIRouter(prefix="/dags", tags=["DAG deployments"])
# under uvicorn's logger, so the lines reach the container log without extra logging setup: who deployed what
log = logging.getLogger("uvicorn.error").getChild("dags")

# A DAG document is small; the retrieval server has the same limit.
MAX_DAG_BYTES = 256 * 1024
RUN_TOKEN_HEADER = "X-OpenTSx-Run-Token"
BY_HEADER = "X-OpenTSx-By"


def http_client() -> httpx.AsyncClient:
    """The client towards the retrieval server. A function, so tests can put a transport in its place."""
    # writing to the control topic retries for up to three minutes while the broker refuses writes
    return httpx.AsyncClient(timeout=httpx.Timeout(240.0, connect=5.0))


def may_run(user: User) -> bool:
    """Who may deploy and stop: administrators, or every signed-in user when DAG_RUN_ROLE is "user"."""
    return bool(user.is_superuser) or settings.DAG_RUN_ROLE == "user"


def _allowed(user: User) -> None:
    if not settings.RETRIEVAL_URL:
        raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE, "This installation has no retrieval server configured for DAGs.")
    if not may_run(user):
        raise HTTPException(status.HTTP_403_FORBIDDEN, "Only administrators may deploy or stop DAGs on this installation.")


async def _forward(user: User, what: str, path: str, body: bytes = b"", params: dict | None = None):
    headers = {"Content-Type": "application/yaml", BY_HEADER: user.email}
    if settings.DAG_RUN_TOKEN:
        headers[RUN_TOKEN_HEADER] = settings.DAG_RUN_TOKEN
    url = settings.RETRIEVAL_URL.rstrip("/") + path
    try:
        async with http_client() as client:
            answer = await client.post(url, content=body, headers=headers, params=params)
    except httpx.HTTPError as e:
        log.warning("%s by user %s failed: retrieval server unreachable (%s)", what, user.id, type(e).__name__)
        raise HTTPException(status.HTTP_502_BAD_GATEWAY, "The retrieval server could not be reached.") from e

    try:
        payload = answer.json()
    except ValueError:
        payload = {}
    if answer.status_code == 200:
        log.info("%s by user %s: %s", what, user.id, payload.get("dag") if isinstance(payload, dict) else "")
        return payload
    reason = payload.get("error") if isinstance(payload, dict) else None
    log.warning("%s by user %s refused by the retrieval server: HTTP %s", what, user.id, answer.status_code)
    if answer.status_code == 400:
        # the request itself cannot be served; the reason is meant for the person who made it
        raise HTTPException(status.HTTP_400_BAD_REQUEST, reason or "The retrieval server refused the request.")
    if answer.status_code == 401:
        raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE,
                            "The retrieval server did not accept the run token of this installation.")
    if answer.status_code == 404:
        raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE,
                            "The retrieval server of this installation does not deploy DAGs.")
    raise HTTPException(status.HTTP_502_BAD_GATEWAY, reason or f"The retrieval server answered HTTP {answer.status_code}.")


@router.get("/capabilities")
async def capabilities(user: User = Depends(get_current_user)):
    """Tells the workbench whether deploy and stop can work for this user."""
    if not settings.RETRIEVAL_URL:
        return {"canRun": False, "reason": "This installation has no retrieval server configured for DAGs."}
    if not may_run(user):
        return {"canRun": False, "reason": "Only administrators may deploy or stop DAGs on this installation."}
    return {"canRun": True, "reason": None}


@router.post("/deploy")
async def deploy(request: Request, user: User = Depends(get_current_user)):
    """Hands the DAG in the body (YAML) to the vector processor. A DAG of the same name is replaced."""
    _allowed(user)
    body = await request.body()
    if not body:
        raise HTTPException(status.HTTP_400_BAD_REQUEST, "The request has no DAG.")
    if len(body) > MAX_DAG_BYTES:
        raise HTTPException(413, f"The DAG is larger than {MAX_DAG_BYTES} bytes.")
    return await _forward(user, "dag deploy", "/v1/dags/deploy", body)


@router.post("/stop")
async def stop(name: str = Query(..., min_length=1, max_length=200), user: User = Depends(get_current_user)):
    """Stops the DAG of this name."""
    _allowed(user)
    return await _forward(user, "dag stop", "/v1/dags/stop", params={"name": name})
