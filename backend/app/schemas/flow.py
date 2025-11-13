"""Pydantic schemas for flow-related requests and responses."""

from typing import Optional, Dict, Any, List
from datetime import datetime
from pydantic import BaseModel


# ==================== Flow Schemas ====================

class FlowBase(BaseModel):
    """Base flow schema."""
    name: str
    description: Optional[str] = None


class FlowCreate(BaseModel):
    """Schema for creating flow."""
    name: str
    description: Optional[str] = None
    definition: Dict[str, Any]
    organization_id: int
    team_id: Optional[int] = None
    tags: List[str] = []
    category: Optional[str] = None


class FlowUpdate(BaseModel):
    """Schema for updating flow."""
    name: Optional[str] = None
    description: Optional[str] = None
    definition: Optional[Dict[str, Any]] = None
    status: Optional[str] = None
    tags: Optional[List[str]] = None
    category: Optional[str] = None


class Flow(FlowBase):
    """Flow response schema."""
    id: int
    definition: Dict[str, Any]
    owner_id: int
    organization_id: int
    team_id: Optional[int] = None
    status: str
    is_public: bool
    is_template: bool
    tags: List[str]
    category: Optional[str] = None
    execution_count: int
    last_executed_at: Optional[datetime] = None
    created_at: datetime
    updated_at: datetime

    class Config:
        from_attributes = True


# ==================== Execution Schemas ====================

class ExecutionCreate(BaseModel):
    """Schema for creating execution."""
    flow_id: int
    input_data: Optional[Dict[str, Any]] = None


class Execution(BaseModel):
    """Execution response schema."""
    id: int
    flow_id: int
    status: str
    started_at: Optional[datetime] = None
    completed_at: Optional[datetime] = None
    duration_ms: Optional[int] = None
    result: Optional[Dict[str, Any]] = None
    error_message: Optional[str] = None
    node_results: Dict[str, Any]
    events_processed: int
    created_at: datetime

    class Config:
        from_attributes = True
