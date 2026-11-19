"""
OpenTSx SaaS Backend API
~~~~~~~~~~~~~~~~~~~~~~~~

FastAPI application: accounts with JWT login, flows stored per user, DAG deployments and demo generators forwarded
to the retrieval server, and the working copy of signal bindings per user. Every error answers
{"error": text, "type": t}.
"""

import logging
from contextlib import asynccontextmanager
from http import HTTPStatus
from pathlib import Path

import uvicorn
from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, JSONResponse
from fastapi.staticfiles import StaticFiles
from starlette.exceptions import HTTPException

from app.api import auth, dags, flows, systems
from app.core.config import settings
from app.db.base import Base  # Import from base.py to ensure all models are loaded
from app.db.init_db import init_db
from app.db.session import AsyncSessionLocal, async_engine
from app.db.utils import wait_for_db

log = logging.getLogger("uvicorn.error").getChild("errors")

# the types the retrieval server and the event bridge answer too; a status without a type fails as internal error
ERROR_TYPES = {
    400: "bad_request",
    401: "unauthorized",
    403: "forbidden",
    404: "not_found",
    405: "method_not_allowed",
    409: "bad_request",
    413: "too_large",
    422: "bad_request",
    500: "internal",
    502: "unavailable",
    503: "unavailable",
}


# ==================== Lifespan ====================

@asynccontextmanager
async def lifespan(app: FastAPI):
    """
    Lifespan events for FastAPI application.

    Handles startup and shutdown events.
    """
    # Wait for database to be ready
    print("⏳ Waiting for database connection...")
    await wait_for_db(async_engine)

    # Startup: Create tables and initialize database
    async with async_engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)

    async with AsyncSessionLocal() as session:
        await init_db(session)

    print("✅ Database initialized")
    if settings.TEST_LOGIN:
        print("TEST OPERATION: admin/admin signs in as administrator. Do not run this outside a lab network.")

    yield

    # Shutdown
    await async_engine.dispose()
    print("👋 Shutting down")


# Create FastAPI app
app = FastAPI(
    title=settings.PROJECT_NAME,
    version=settings.VERSION,
    description=settings.DESCRIPTION,
    openapi_url=f"{settings.API_V1_STR}/openapi.json",
    docs_url="/docs",
    redoc_url="/redoc",
    lifespan=lifespan,
)


def error_answer(status_code: int, text: str, headers=None) -> JSONResponse:
    return JSONResponse({"error": text, "type": ERROR_TYPES[status_code]}, status_code=status_code, headers=headers)


def causes(exc: BaseException) -> str:
    """The exception and its explicit causes (raise ... from); the implicit context holds middleware internals."""
    seen = []
    while exc is not None and exc not in seen:
        seen.append(exc)
        exc = exc.__cause__
    return "; caused by ".join(f"{type(e).__name__}: {e}".replace("\n", " ") for e in seen)


# A middleware, not an exception handler for Exception: Starlette re-raises after such a handler and uvicorn then logs
# the stack trace at ERROR. Added before CORS, so the CORS headers reach the 500 answer too.
@app.middleware("http")
async def internal_error(request: Request, call_next):
    try:
        return await call_next(request)
    except Exception as exc:
        log.error("request failed method=%s path=%s status=500 cause=%s", request.method, request.url.path, causes(exc))
        log.debug("stack trace of the failed request", exc_info=exc)
        return error_answer(500, "internal error")


# CORS middleware
app.add_middleware(
    CORSMiddleware,
    allow_origins=settings.BACKEND_CORS_ORIGINS,
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


@app.exception_handler(HTTPException)
async def http_error(request: Request, exc: HTTPException) -> JSONResponse:
    text = exc.detail
    where = f"{request.method} {request.url.path}"
    if exc.status_code == 404 and text == HTTPStatus.NOT_FOUND.phrase:
        text = f"{where}: no such route; see /docs for the routes"
    elif exc.status_code == 405 and text == HTTPStatus.METHOD_NOT_ALLOWED.phrase:
        allowed = (exc.headers or {}).get("Allow")
        text = f"{where}: method not allowed; " + (f"expected {allowed}" if allowed else "see /docs for the routes")
    return error_answer(exc.status_code, text, exc.headers)


def validation_text(errors) -> str:
    """The refused fields as `<field>: <problem>`, joined by "; ". The input is left out: it may hold a password."""
    parts = []
    for e in errors:
        loc = [str(p) for p in e["loc"]]
        if e["type"] == "json_invalid":
            parser = e.get("ctx", {}).get("error")
            parts.append(f"body: not valid JSON at character {loc[-1]} ({parser}); send a JSON object")
        else:
            parts.append(f"{'.'.join(loc[1:]) or loc[0]}: {e['msg'].rstrip('.')}")
    return "; ".join(parts)


@app.exception_handler(RequestValidationError)
async def validation_error(request: Request, exc: RequestValidationError) -> JSONResponse:
    return error_answer(422, validation_text(exc.errors()))


# Mount static files
static_path = Path(__file__).parent.parent / "static"
if static_path.exists():
    app.mount("/static", StaticFiles(directory=str(static_path)), name="static")


# ==================== Include Routers ====================

# Real authentication routes (with JWT and database)
app.include_router(auth.router, prefix=settings.API_V1_STR)


# ==================== Root Endpoint ====================

@app.get("/")
async def root():
    """Serve the web UI."""
    index_path = Path(__file__).parent.parent / "static" / "index.html"
    if index_path.exists():
        return FileResponse(str(index_path))
    else:
        # Fallback to API info if no UI
        return {
            "name": settings.PROJECT_NAME,
            "version": settings.VERSION,
            "description": settings.DESCRIPTION,
            "docs_url": "/docs",
            "health_url": "/health",
        }


@app.get("/health")
async def health_check():
    """Health check endpoint."""
    return {
        "status": "healthy",
        "version": settings.VERSION,
    }


# ==================== API Routes ====================

# Flows: stored per user (app/api/flows.py)
app.include_router(flows.router, prefix=settings.API_V1_STR)

# DAG runs: forwarded to the retrieval server for signed-in users (app/api/dags.py)
app.include_router(dags.router, prefix=settings.API_V1_STR)

# Systems: the working copy of signal bindings per user, and demo generators (app/api/systems.py)
app.include_router(systems.router, prefix=settings.API_V1_STR)


# ==================== Run Server ====================

if __name__ == "__main__":
    uvicorn.run(
        "app.main:app",
        host="0.0.0.0",
        port=8000,
        reload=True,
        log_level="info",
    )
