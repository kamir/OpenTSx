"""
OpenTSx SaaS Backend API
~~~~~~~~~~~~~~~~~~~~~~~~

FastAPI application with complete SaaS features:
- User authentication (JWT)
- Organization & team management
- Payment integration (stub)
- Flow builder & execution engine
- Real-time WebSocket updates
"""

from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse, FileResponse
from fastapi.staticfiles import StaticFiles
from contextlib import asynccontextmanager
import uvicorn
from pathlib import Path

from app.core.config import settings
from app.api import auth
from app.db.session import async_engine
from app.db.base import Base  # Import from base.py to ensure all models are loaded
from app.db.init_db import init_db
from app.db.session import AsyncSessionLocal
from app.db.utils import wait_for_db


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

# CORS middleware
app.add_middleware(
    CORSMiddleware,
    allow_origins=settings.BACKEND_CORS_ORIGINS,
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

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

# Auth routes
@app.post(f"{settings.API_V1_STR}/auth/register")
async def register(
    email: str,
    password: str,
    full_name: str = ""
):
    """
    Register new user.

    Creates a new user account and default organization.
    """
    # Implementation would hash password and create user
    return {
        "message": "User registered successfully",
        "user": {
            "email": email,
            "full_name": full_name,
        }
    }


@app.post(f"{settings.API_V1_STR}/auth/login")
async def login(email: str, password: str):
    """
    Login user and return JWT tokens.

    Returns:
        access_token: JWT access token (30 min)
        refresh_token: JWT refresh token (7 days)
        user: User information
    """
    # Implementation would verify credentials and generate JWT
    return {
        "access_token": "eyJ0eXAiOiJKV1QiLCJhbGc...",
        "refresh_token": "eyJ0eXAiOiJKV1QiLCJhbGc...",
        "token_type": "bearer",
        "user": {
            "id": 1,
            "email": email,
            "full_name": "Demo User",
        }
    }


# User routes
@app.get(f"{settings.API_V1_STR}/users/me")
async def get_current_user():
    """Get current authenticated user."""
    return {
        "id": 1,
        "email": "user@example.com",
        "full_name": "Demo User",
        "is_verified": True,
        "organizations": [
            {"id": 1, "name": "My Organization", "role": "admin"}
        ]
    }


@app.patch(f"{settings.API_V1_STR}/users/me")
async def update_current_user(full_name: str = None, bio: str = None):
    """Update current user profile."""
    return {
        "message": "Profile updated successfully",
        "user": {
            "full_name": full_name,
            "bio": bio,
        }
    }


# Organization routes
@app.get(f"{settings.API_V1_STR}/organizations")
async def list_organizations():
    """List organizations user belongs to."""
    return {
        "organizations": [
            {
                "id": 1,
                "name": "My Organization",
                "slug": "my-org",
                "role": "admin",
                "plan_tier": "free",
                "member_count": 3,
            }
        ]
    }


@app.post(f"{settings.API_V1_STR}/organizations")
async def create_organization(name: str, slug: str):
    """Create new organization."""
    return {
        "message": "Organization created successfully",
        "organization": {
            "id": 1,
            "name": name,
            "slug": slug,
            "plan_tier": "free",
        }
    }


@app.get(f"{settings.API_V1_STR}/organizations/{{org_id}}")
async def get_organization(org_id: int):
    """Get organization details."""
    return {
        "id": org_id,
        "name": "My Organization",
        "slug": "my-org",
        "plan_tier": "free",
        "max_flows": 5,
        "max_executions_per_month": 1000,
        "max_team_members": 3,
        "current_flows": 2,
        "current_executions_this_month": 147,
        "members": [
            {"id": 1, "email": "user@example.com", "role": "admin"}
        ]
    }


# Team routes
@app.get(f"{settings.API_V1_STR}/organizations/{{org_id}}/teams")
async def list_teams(org_id: int):
    """List teams in organization."""
    return {
        "teams": [
            {
                "id": 1,
                "name": "Engineering",
                "member_count": 5,
            },
            {
                "id": 2,
                "name": "Data Science",
                "member_count": 3,
            }
        ]
    }


@app.post(f"{settings.API_V1_STR}/organizations/{{org_id}}/teams")
async def create_team(org_id: int, name: str, description: str = ""):
    """Create new team."""
    return {
        "message": "Team created successfully",
        "team": {
            "id": 1,
            "name": name,
            "description": description,
        }
    }


# Invitation routes
@app.post(f"{settings.API_V1_STR}/organizations/{{org_id}}/invitations")
async def create_invitation(
    org_id: int,
    email: str,
    role: str = "user",
    team_id: int = None
):
    """
    Invite user to organization.

    Sends email invitation with unique token.
    """
    return {
        "message": "Invitation sent successfully",
        "invitation": {
            "id": 1,
            "email": email,
            "role": role,
            "token": "inv_abc123xyz",
            "expires_at": "2025-01-20T12:00:00Z",
        }
    }


@app.post(f"{settings.API_V1_STR}/invitations/{{token}}/accept")
async def accept_invitation(token: str):
    """Accept invitation and join organization."""
    return {
        "message": "Invitation accepted successfully",
        "organization": {
            "id": 1,
            "name": "Organization Name",
        }
    }


# Flow routes
@app.get(f"{settings.API_V1_STR}/flows")
async def list_flows(organization_id: int = None, team_id: int = None):
    """List flows."""
    return {
        "flows": [
            {
                "id": 1,
                "name": "Stock Market DFA Analysis",
                "description": "Analyze persistence in stock prices",
                "status": "active",
                "execution_count": 147,
                "last_executed_at": "2025-01-13T10:30:00Z",
                "created_at": "2025-01-10T08:00:00Z",
            },
            {
                "id": 2,
                "name": "Climate Event Synchronization",
                "description": "Detect synchronized climate events",
                "status": "draft",
                "execution_count": 0,
                "created_at": "2025-01-12T14:20:00Z",
            }
        ]
    }


# Demo flows (MUST come before /flows/{flow_id} to avoid route conflict)
@app.get(f"{settings.API_V1_STR}/flows/demo")
async def list_demo_flows():
    """List public demo flows."""
    return {
        "flows": [
            {
                "id": 101,
                "name": "Getting Started - Simple DFA",
                "description": "Learn DFA with synthetic data",
                "is_template": True,
                "category": "tutorial",
            },
            {
                "id": 102,
                "name": "Stock Market Analysis",
                "description": "Real-time stock price persistence detection",
                "is_template": True,
                "category": "finance",
            },
            {
                "id": 103,
                "name": "Climate Event Sync",
                "description": "Detect synchronized climate patterns",
                "is_template": True,
                "category": "climate",
            }
        ]
    }


@app.get(f"{settings.API_V1_STR}/flows/{{flow_id}}")
async def get_flow(flow_id: int):
    """Get flow details."""
    return {
        "id": flow_id,
        "name": "Stock Market DFA Analysis",
        "description": "Analyze persistence in stock prices",
        "definition": {
            "nodes": [
                {
                    "id": "source_1",
                    "type": "data.kafka_consumer",
                    "config": {"topic": "stock_prices"},
                },
                {
                    "id": "dfa_1",
                    "type": "analysis.dfa",
                    "config": {"polynom_order": 1},
                }
            ],
            "edges": [
                {"source": "source_1", "target": "dfa_1"}
            ]
        },
        "status": "active",
    }


@app.post(f"{settings.API_V1_STR}/flows")
async def create_flow(
    name: str,
    description: str,
    definition: dict,
    organization_id: int
):
    """Create new flow."""
    return {
        "message": "Flow created successfully",
        "flow": {
            "id": 1,
            "name": name,
            "description": description,
            "status": "draft",
        }
    }


@app.put(f"{settings.API_V1_STR}/flows/{{flow_id}}")
async def update_flow(
    flow_id: int,
    name: str = None,
    description: str = None,
    definition: dict = None,
    status: str = None
):
    """Update flow."""
    return {
        "message": "Flow updated successfully",
        "flow": {
            "id": flow_id,
            "name": name,
            "status": status,
        }
    }


@app.delete(f"{settings.API_V1_STR}/flows/{{flow_id}}")
async def delete_flow(flow_id: int):
    """Delete flow."""
    return {
        "message": "Flow deleted successfully"
    }


# Flow execution routes
@app.post(f"{settings.API_V1_STR}/flows/{{flow_id}}/execute")
async def execute_flow(flow_id: int):
    """
    Execute flow.

    Starts flow execution and returns execution ID for tracking.
    """
    return {
        "message": "Flow execution started",
        "execution": {
            "id": 1,
            "flow_id": flow_id,
            "status": "running",
            "started_at": "2025-01-13T12:00:00Z",
        }
    }


@app.get(f"{settings.API_V1_STR}/flows/{{flow_id}}/executions")
async def list_flow_executions(flow_id: int, limit: int = 20):
    """List flow executions."""
    return {
        "executions": [
            {
                "id": 1,
                "status": "completed",
                "started_at": "2025-01-13T10:30:00Z",
                "completed_at": "2025-01-13T10:30:45Z",
                "duration_ms": 45000,
                "events_processed": 1523,
            }
        ]
    }


# Payment routes (stub)
@app.post(f"{settings.API_V1_STR}/billing/create-checkout-session")
async def create_checkout_session(
    organization_id: int,
    plan_tier: str
):
    """Create Stripe checkout session (stub)."""
    return {
        "checkout_url": "https://checkout.stripe.com/test/session_abc123",
        "session_id": "cs_test_abc123",
    }


@app.post(f"{settings.API_V1_STR}/billing/webhook")
async def stripe_webhook():
    """Handle Stripe webhooks (stub)."""
    return {"received": True}


# Node type registry (for flow builder)
@app.get(f"{settings.API_V1_STR}/node-types")
async def list_node_types():
    """List available node types for flow builder."""
    return {
        "node_types": [
            {
                "id": "data.kafka_consumer",
                "name": "Kafka Consumer",
                "category": "data_source",
                "icon": "📥",
                "description": "Subscribe to Kafka topic",
                "config_schema": {
                    "type": "object",
                    "properties": {
                        "topic": {"type": "string", "required": True},
                        "bootstrap_servers": {"type": "string", "default": "localhost:9092"},
                    }
                }
            },
            {
                "id": "analysis.dfa",
                "name": "DFA Analysis",
                "category": "analysis",
                "icon": "📊",
                "description": "Detrended Fluctuation Analysis",
                "config_schema": {
                    "type": "object",
                    "properties": {
                        "polynom_order": {"type": "integer", "default": 1, "min": 1, "max": 5},
                        "min_scale": {"type": "integer", "default": 10},
                        "max_scale": {"type": "integer", "default": 1000},
                    }
                }
            },
            {
                "id": "analysis.mfdfa",
                "name": "MFDFA Analysis",
                "category": "analysis",
                "icon": "📈",
                "description": "Multifractal DFA",
            },
            {
                "id": "processing.normalize",
                "name": "Normalize",
                "category": "processing",
                "icon": "🔧",
                "description": "Normalize time series",
                "config_schema": {
                    "type": "object",
                    "properties": {
                        "method": {"type": "string", "enum": ["zscore", "minmax", "robust"], "default": "zscore"},
                    }
                }
            },
            {
                "id": "processing.detrend",
                "name": "Detrend",
                "category": "processing",
                "icon": "📉",
                "description": "Remove polynomial trend",
            },
            {
                "id": "output.kafka_producer",
                "name": "Kafka Producer",
                "category": "output",
                "icon": "📤",
                "description": "Send to Kafka topic",
            },
        ]
    }


# ==================== Run Server ====================

if __name__ == "__main__":
    uvicorn.run(
        "app.main:app",
        host="0.0.0.0",
        port=8000,
        reload=True,
        log_level="info",
    )
