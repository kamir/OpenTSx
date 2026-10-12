"""Flow API routes: flows are stored per user in the database."""

from datetime import datetime
from typing import Optional

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.deps import get_current_user
from app.db.session import get_db
from app.models.flow import ExecutionStatus, Flow, FlowExecution, FlowStatus
from app.models.user import User, user_organization
from app.schemas.flow import Execution as ExecutionSchema
from app.schemas.flow import Flow as FlowSchema
from app.schemas.flow import FlowCreate, FlowUpdate

router = APIRouter(prefix="/flows", tags=["Flows"])


async def _organization_of(db: AsyncSession, user: User, organization_id: Optional[int]) -> int:
    """Returns the organization a new flow belongs to: the requested one if the user is a member, else the first."""
    query = select(user_organization.c.organization_id).where(user_organization.c.user_id == user.id)
    if organization_id is not None:
        query = query.where(user_organization.c.organization_id == organization_id)
    found = (await db.execute(query.order_by(user_organization.c.organization_id))).scalars().first()
    if found is None:
        if organization_id is not None:
            raise HTTPException(status.HTTP_403_FORBIDDEN, "Not a member of this organization")
        raise HTTPException(status.HTTP_400_BAD_REQUEST, "The user belongs to no organization")
    return found


async def _own_flow(db: AsyncSession, user: User, flow_id: int) -> Flow:
    """Loads a flow of the current user. A flow of somebody else answers like a missing one."""
    result = await db.execute(select(Flow).where(Flow.id == flow_id, Flow.owner_id == user.id))
    flow = result.scalar_one_or_none()
    if flow is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Flow not found")
    return flow


def _out(flow: Flow) -> dict:
    return FlowSchema.model_validate(flow).model_dump(mode="json")


@router.get("")
async def list_flows(current_user: User = Depends(get_current_user), db: AsyncSession = Depends(get_db)):
    """Flows of the current user, most recently changed first."""
    result = await db.execute(
        select(Flow).where(Flow.owner_id == current_user.id).order_by(Flow.updated_at.desc(), Flow.id.desc())
    )
    return {"flows": [_out(f) for f in result.scalars().all()]}


@router.post("", status_code=status.HTTP_201_CREATED)
async def create_flow(
    data: FlowCreate,
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    """Stores a new flow as draft."""
    flow = Flow(
        name=data.name,
        description=data.description,
        definition=data.definition,
        owner_id=current_user.id,
        organization_id=await _organization_of(db, current_user, data.organization_id),
        team_id=data.team_id,
        status=FlowStatus.DRAFT,
        is_public=False,
        is_template=False,
        tags=data.tags,
        category=data.category,
        execution_count=0,
    )
    db.add(flow)
    await db.commit()
    await db.refresh(flow)
    return {"message": "Flow created successfully", "flow": _out(flow)}


@router.get("/{flow_id}")
async def get_flow(flow_id: int, current_user: User = Depends(get_current_user), db: AsyncSession = Depends(get_db)):
    """One flow with its definition."""
    return _out(await _own_flow(db, current_user, flow_id))


@router.put("/{flow_id}")
async def update_flow(
    flow_id: int,
    data: FlowUpdate,
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    """Changes the given fields of a flow."""
    flow = await _own_flow(db, current_user, flow_id)
    changes = data.model_dump(exclude_unset=True)
    if "status" in changes:
        try:
            changes["status"] = FlowStatus(changes["status"])
        except ValueError:
            allowed = ", ".join(s.value for s in FlowStatus)
            raise HTTPException(status.HTTP_422_UNPROCESSABLE_ENTITY, f"status must be one of: {allowed}")
    for key, value in changes.items():
        setattr(flow, key, value)
    flow.updated_at = datetime.utcnow()
    await db.commit()
    await db.refresh(flow)
    return {"message": "Flow updated successfully", "flow": _out(flow)}


@router.delete("/{flow_id}")
async def delete_flow(flow_id: int, current_user: User = Depends(get_current_user), db: AsyncSession = Depends(get_db)):
    """Deletes a flow and its execution history."""
    flow = await _own_flow(db, current_user, flow_id)
    executions = await db.execute(select(FlowExecution).where(FlowExecution.flow_id == flow.id))
    for execution in executions.scalars().all():
        await db.delete(execution)
    await db.delete(flow)
    await db.commit()
    return {"message": "Flow deleted successfully"}


@router.post("/{flow_id}/execute")
async def execute_flow(flow_id: int, current_user: User = Depends(get_current_user), db: AsyncSession = Depends(get_db)):
    """Submits the flow definition to Flink and records the execution."""
    from app.services.flink_service import flink_service

    flow = await _own_flow(db, current_user, flow_id)
    try:
        flink_job_id = flink_service.submit_job(flow.definition)
    except Exception as e:
        raise HTTPException(status.HTTP_502_BAD_GATEWAY, f"Failed to submit Flink job: {e}")

    execution = FlowExecution(
        flow_id=flow.id,
        status=ExecutionStatus.RUNNING,
        started_at=datetime.utcnow(),
        result={"flink_job_id": flink_job_id},
        node_results={},
        events_processed=0,
    )
    db.add(execution)
    flow.execution_count = (flow.execution_count or 0) + 1
    flow.last_executed_at = datetime.utcnow()
    await db.commit()
    await db.refresh(execution)
    return {
        "message": "Flow execution started",
        "execution": {
            "id": execution.id,
            "flow_id": flow.id,
            "status": execution.status,
            "flink_job_id": flink_job_id,
            "started_at": execution.started_at,
        },
    }


@router.get("/{flow_id}/executions")
async def list_flow_executions(
    flow_id: int,
    limit: int = 20,
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    """Execution history of a flow, newest first."""
    flow = await _own_flow(db, current_user, flow_id)
    result = await db.execute(
        select(FlowExecution)
        .where(FlowExecution.flow_id == flow.id)
        .order_by(FlowExecution.id.desc())
        .limit(max(1, min(limit, 200)))
    )
    return {
        "executions": [ExecutionSchema.model_validate(e).model_dump(mode="json") for e in result.scalars().all()]
    }
