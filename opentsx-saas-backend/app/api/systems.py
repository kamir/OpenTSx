"""Systems: the working copy of a system document, and demo generators.

A system document (opentsx.system.v1) lives in the repository and is served by the retrieval server. What a user
binds in the web application is kept here per user and laid over the document when its signals are shown; it is
written into the file by a person (decision D42). Running a demo generator writes to the broker, so it goes through
the backend like a DAG deployment: the backend checks who asks and adds the run token.
"""

import json
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
log = logging.getLogger(__name__)

SYSTEM = re.compile(r"^[a-z0-9][a-z0-9-]*$")
SIGNAL = re.compile(r"^[a-z0-9][a-z0-9_-]*(\.[a-z0-9_]+|(/[A-Za-z0-9_.-]+)+)$")
GRADES = ("raw", "clean")
KINDS = ("topic", "generator", "manual")
ORIGINS = ("MEASURED", "REPORTED", "FORECAST", "ESTIMATED", "SYNTHETIC")
# the demo generators the retrieval server runs
GENERATORS = ("gen.ou", "gen.sine")


class Binding(BaseModel):
    """One source of a working copy."""

    signal: str = Field(max_length=200)
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


def _q(value: Any) -> str:
    """A value as the request gave it: a string in double quotes, anything else as JSON."""
    return json.dumps(value, ensure_ascii=False)


def _system(system: str) -> str:
    if not SYSTEM.match(system):
        raise HTTPException(status.HTTP_422_UNPROCESSABLE_ENTITY,
                            f"system: {_q(system)} is not a system id; expected lower-case letters, digits and hyphens")
    return system


def check(binding: Binding) -> None:
    """Refuses a binding that cannot be a source, with a reason for the person who made it."""
    bad = status.HTTP_422_UNPROCESSABLE_ENTITY
    if not SIGNAL.match(binding.signal):
        raise HTTPException(bad, f"signal: {_q(binding.signal)} is not a signal id; "
                                 "expected <atom>.<port> or <atom>/<asset>/<channel>")
    if binding.grade not in GRADES:
        raise HTTPException(bad, f"grade: {_q(binding.grade)} is not allowed; expected one of {', '.join(GRADES)}")
    source = binding.source
    kind = source.get("kind")
    if kind not in KINDS:
        raise HTTPException(bad, f"source.kind: {_q(kind)} is not allowed; expected one of {', '.join(KINDS)} "
                                 "(a flow becomes a source by publishing it)")
    if kind == "topic":
        select_ = source.get("select") or {}
        if not source.get("topic") or not (select_.get("metric") or select_.get("tags") or select_.get("series")):
            raise HTTPException(bad, "source: a topic source needs the topic and what to select from it; "
                                     "set topic and select.metric, select.tags or select.series")
        if source.get("origin") not in ORIGINS:
            raise HTTPException(bad, f"source.origin: {_q(source.get('origin'))} is not allowed; "
                                     f"expected one of {', '.join(ORIGINS)}")
        if not binding.meaningConfirmed:
            # a matching name and unit are a suggestion, not a proof (decision D53)
            raise HTTPException(bad, "meaningConfirmed: false; "
                                     "confirm that the series is the quantity the signal means before binding it")
    elif kind == "generator":
        if source.get("processor") not in GENERATORS:
            raise HTTPException(bad, f"source.processor: {_q(source.get('processor'))} is not allowed; "
                                     f"expected one of {', '.join(GENERATORS)}")
        if not isinstance(source.get("params", {}), dict):
            raise HTTPException(bad, f"source.params: {_q(source['params'])} is not an object; "
                                     "expected an object of parameters")
        if "seed" in source and (not isinstance(source["seed"], int) or isinstance(source["seed"], bool)):
            raise HTTPException(bad, f"source.seed: {_q(source['seed'])} is not a whole number; "
                                     "expected a whole number such as 7")
    else:
        if not isinstance(source.get("value"), (int, float)) or isinstance(source.get("value"), bool):
            raise HTTPException(bad, f"source.value: {_q(source.get('value'))} is not a number; "
                                     "a manual source needs a number")
        if source.get("origin", "REPORTED") not in ORIGINS:
            raise HTTPException(bad, f"source.origin: {_q(source['origin'])} is not allowed; "
                                     f"expected one of {', '.join(ORIGINS)}")


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
        raise HTTPException(status.HTTP_404_NOT_FOUND, f"{signal}@{grade}: not in your working copy; nothing was removed")
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
    if not dags.may_run(user):
        raise HTTPException(status.HTTP_403_FORBIDDEN,
                            "this account: not an administrator; "
                            "only administrators may run generators on this installation")
    if not settings.RETRIEVAL_URL:
        raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE,
                            "RETRIEVAL_URL: not set; this installation runs no generators")
    bindings = [{"signal": b["signal"], "grade": b["grade"], "source": b["source"]} for b in await store.list(user.id, _system(system))]
    body = {"signal": request.signal, "from": request.start, "to": request.end, "bindings": bindings}
    return await dags.forward_json(user, "generate", f"/v1/systems/{system}/generate", body)
