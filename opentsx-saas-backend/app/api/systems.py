"""Systems: the working copy of a system document, and demo generators.

A system document (opentsx.system.v1) lives in the repository and is served by the retrieval server. What a user
binds in the web application is kept here per user and laid over the document when its signals are shown; it is
written into the file by a person (decision D42). Running a demo generator writes to the broker, so it goes through
the backend like a DAG deployment: the backend checks who asks and adds the run token.
"""

import logging
import re
from datetime import datetime
from typing import Any, Dict, List, Optional, Protocol

from fastapi import APIRouter, Depends, HTTPException, Query, status
from pydantic import BaseModel, Field
from sqlalchemy import delete, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.api import dags
from app.core.config import settings
from app.core.deps import get_current_user
from app.db.session import get_db
from app.models.system import SignalBinding
from app.models.user import User

router = APIRouter(prefix="/systems", tags=["Systems"])
log = logging.getLogger("uvicorn.error").getChild("systems")

SYSTEM = re.compile(r"^[a-z0-9][a-z0-9-]*$")
SIGNAL = re.compile(r"^[a-z0-9][a-z0-9_-]*(\.[a-z0-9_]+|(/[A-Za-z0-9_.-]+)+)$")
GRADES = ("raw", "clean")
KINDS = ("topic", "generator", "manual")
ORIGINS = ("MEASURED", "REPORTED", "FORECAST", "ESTIMATED", "SYNTHETIC")
# the demo generators the retrieval server runs
GENERATORS = ("gen.ou", "gen.sine")


class Binding(BaseModel):
    """One source of a working copy."""

    signal: str
    grade: str = "raw"
    source: Dict[str, Any]
    # required for a topic source: the person looked at the data and says it is the quantity the signal means
    meaningConfirmed: bool = False
    baseHash: Optional[str] = Field(default=None, max_length=64)


class GenerateRequest(BaseModel):
    signal: str
    start: str = Field(alias="from")
    end: str = Field(alias="to")

    model_config = {"populate_by_name": True}


class BindingStore(Protocol):
    async def list(self, owner: int, system: str) -> List[dict]: ...

    async def put(self, owner: int, system: str, binding: Binding) -> dict: ...

    async def remove(self, owner: int, system: str, signal: str, grade: str) -> bool: ...


def _out(row: SignalBinding) -> dict:
    return {
        "signal": row.signal,
        "grade": row.grade,
        "source": row.source,
        "meaningConfirmed": bool(row.meaning_confirmed),
        "baseHash": row.base_hash,
        "updatedAt": row.updated_at.isoformat() if row.updated_at else None,
    }


class SqlBindingStore:
    """The working copies in the database of the backend."""

    def __init__(self, db: AsyncSession):
        self.db = db

    async def _row(self, owner: int, system: str, signal: str, grade: str) -> Optional[SignalBinding]:
        found = await self.db.execute(
            select(SignalBinding).where(
                SignalBinding.owner_id == owner,
                SignalBinding.system == system,
                SignalBinding.signal == signal,
                SignalBinding.grade == grade,
            )
        )
        return found.scalar_one_or_none()

    async def list(self, owner: int, system: str) -> List[dict]:
        found = await self.db.execute(
            select(SignalBinding)
            .where(SignalBinding.owner_id == owner, SignalBinding.system == system)
            .order_by(SignalBinding.signal, SignalBinding.grade)
        )
        return [_out(r) for r in found.scalars().all()]

    async def put(self, owner: int, system: str, binding: Binding) -> dict:
        row = await self._row(owner, system, binding.signal, binding.grade)
        if row is None:
            row = SignalBinding(owner_id=owner, system=system, signal=binding.signal, grade=binding.grade)
            self.db.add(row)
        row.source = binding.source
        row.meaning_confirmed = binding.meaningConfirmed
        row.base_hash = binding.baseHash
        row.updated_at = datetime.utcnow()
        await self.db.commit()
        await self.db.refresh(row)
        return _out(row)

    async def remove(self, owner: int, system: str, signal: str, grade: str) -> bool:
        gone = await self.db.execute(
            delete(SignalBinding).where(
                SignalBinding.owner_id == owner,
                SignalBinding.system == system,
                SignalBinding.signal == signal,
                SignalBinding.grade == grade,
            )
        )
        await self.db.commit()
        return gone.rowcount > 0


def get_store(db: AsyncSession = Depends(get_db)) -> BindingStore:
    """The store of working copies. A function, so tests can put one in memory in its place."""
    return SqlBindingStore(db)


def _system(system: str) -> str:
    if not SYSTEM.match(system):
        raise HTTPException(status.HTTP_422_UNPROCESSABLE_ENTITY, "The system id may hold lower-case letters, digits and hyphens.")
    return system


def check(binding: Binding) -> None:
    """Refuses a binding that cannot be a source, with a reason for the person who made it."""
    bad = status.HTTP_422_UNPROCESSABLE_ENTITY
    if not SIGNAL.match(binding.signal):
        raise HTTPException(bad, "The signal id must be <atom>.<port> or <atom>/<asset>/<channel>.")
    if binding.grade not in GRADES:
        raise HTTPException(bad, "A binding is for the grade raw or clean.")
    source = binding.source
    kind = source.get("kind")
    if kind not in KINDS:
        raise HTTPException(bad, "A source set here is a topic, a generator or a manual value. A flow becomes a source by publishing it.")
    if kind == "topic":
        select_ = source.get("select") or {}
        if not source.get("topic") or not (select_.get("metric") or select_.get("tags") or select_.get("series")):
            raise HTTPException(bad, "A topic source needs the topic and what to select from it.")
        if source.get("origin") not in ORIGINS:
            raise HTTPException(bad, f"A topic source says how its data came to be: one of {', '.join(ORIGINS)}.")
        if not binding.meaningConfirmed:
            # a matching name and unit are a suggestion, not a proof (decision D53)
            raise HTTPException(bad, "Confirm that the series is the quantity the signal means before binding it.")
    elif kind == "generator":
        if source.get("processor") not in GENERATORS:
            raise HTTPException(bad, f"The generator must be one of {', '.join(GENERATORS)}.")
        if not isinstance(source.get("params", {}), dict):
            raise HTTPException(bad, "The parameters of a generator are an object.")
        if "seed" in source and not isinstance(source["seed"], int):
            raise HTTPException(bad, "The seed of a generator is a whole number.")
    else:
        if not isinstance(source.get("value"), (int, float)) or isinstance(source.get("value"), bool):
            raise HTTPException(bad, "A manual source needs a value.")
        if source.get("origin", "REPORTED") not in ORIGINS:
            raise HTTPException(bad, f"The origin must be one of {', '.join(ORIGINS)}.")


@router.get("/{system}/bindings")
async def list_bindings(system: str, user: User = Depends(get_current_user), store: BindingStore = Depends(get_store)):
    """The working copy of the user for this system."""
    return {"system": _system(system), "bindings": await store.list(user.id, system)}


@router.put("/{system}/bindings")
async def put_binding(
    system: str, binding: Binding, user: User = Depends(get_current_user), store: BindingStore = Depends(get_store)
):
    """Sets the source of one grade of one signal in the working copy; an earlier one is replaced."""
    check(binding)
    saved = await store.put(user.id, _system(system), binding)
    log.info("binding by user %s: %s %s@%s -> %s", user.id, system, binding.signal, binding.grade, binding.source.get("kind"))
    return {"message": "Binding saved in your working copy", "binding": saved}


@router.delete("/{system}/bindings")
async def delete_binding(
    system: str,
    signal: str = Query(..., min_length=1, max_length=200),
    grade: str = Query("raw"),
    user: User = Depends(get_current_user),
    store: BindingStore = Depends(get_store),
):
    """Takes a source out of the working copy; the system document applies again."""
    if not await store.remove(user.id, _system(system), signal, grade):
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Your working copy has no such binding.")
    return {"message": "Binding removed from your working copy"}


@router.post("/{system}/generate")
async def generate(
    system: str,
    request: GenerateRequest,
    user: User = Depends(get_current_user),
    store: BindingStore = Depends(get_store),
):
    """
    Runs the demo generator the signal is bound to over a period. The retrieval server writes the series to the topic
    of generated series of the domain; the working copy of the user goes along, so a generator bound here counts.
    """
    if not settings.RETRIEVAL_URL:
        raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE, "This installation has no retrieval server configured.")
    if not dags.may_run(user):
        raise HTTPException(status.HTTP_403_FORBIDDEN, "Only administrators may run generators on this installation.")
    bindings = [{"signal": b["signal"], "grade": b["grade"], "source": b["source"]} for b in await store.list(user.id, _system(system))]
    body = {"signal": request.signal, "from": request.start, "to": request.end, "bindings": bindings}
    return await dags.forward_json(user, "generate", f"/v1/systems/{system}/generate", body)
