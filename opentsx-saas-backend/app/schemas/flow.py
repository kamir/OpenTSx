"""Pydantic schemas for flow-related requests and responses."""

from typing import Optional, Dict, Any, List
from datetime import datetime
from pydantic import BaseModel

from app.models.flow import FlowStatus


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
    organization_id: Optional[int] = None  # default: the first organization of the user
    team_id: Optional[int] = None
    tags: List[str] = []
    category: Optional[str] = None


class FlowUpdate(BaseModel):
    """Schema for updating flow."""
    name: Optional[str] = None
    description: Optional[str] = None
    definition: Optional[Dict[str, Any]] = None
    status: Optional[FlowStatus] = None
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
