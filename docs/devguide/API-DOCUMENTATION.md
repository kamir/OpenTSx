# OpenTSx API Documentation 📡

**REST API Reference for OpenTSx SaaS Platform**

Version: 1.0.0 | Base URL: `https://api.opentsx.com`

---

## Table of Contents

1. [Authentication](#authentication)
2. [User Management](#user-management)
3. [Organizations](#organizations)
4. [Teams](#teams)
5. [Invitations](#invitations)
6. [Flows](#flows)
7. [Flow Executions](#flow-executions)
8. [Node Types](#node-types)
9. [Billing](#billing)
10. [Error Handling](#error-handling)
11. [Rate Limits](#rate-limits)
12. [Webhooks](#webhooks)

---

## Authentication

### POST /api/v1/auth/register

Register a new user account.

**Request Body**:
```json
{
  "email": "user@example.com",
  "password": "SecurePassword123!",
  "full_name": "John Doe"
}
```

**Response** (201 Created):
```json
{
  "message": "User registered successfully",
  "user": {
    "id": 1,
    "email": "user@example.com",
    "full_name": "John Doe",
    "is_verified": false
  }
}
```

**Errors**:
- `400`: Invalid email or weak password
- `409`: Email already registered

---

### POST /api/v1/auth/login

Login and receive JWT tokens.

**Request Body**:
```json
{
  "email": "user@example.com",
  "password": "SecurePassword123!"
}
```

**Response** (200 OK):
```json
{
  "access_token": "eyJ0eXAiOiJKV1QiLCJhbGc...",
  "refresh_token": "eyJ0eXAiOiJKV1QiLCJhbGc...",
  "token_type": "bearer",
  "expires_in": 1800,
  "user": {
    "id": 1,
    "email": "user@example.com",
    "full_name": "John Doe"
  }
}
```

**Errors**:
- `401`: Invalid credentials
- `403`: Account not verified

---

### POST /api/v1/auth/refresh

Refresh access token using refresh token.

**Request Body**:
```json
{
  "refresh_token": "eyJ0eXAiOiJKV1QiLCJhbGc..."
}
```

**Response** (200 OK):
```json
{
  "access_token": "eyJ0eXAiOiJKV1QiLCJhbGc...",
  "token_type": "bearer",
  "expires_in": 1800
}
```

---

### POST /api/v1/auth/logout

Logout and invalidate tokens.

**Headers**: `Authorization: Bearer {access_token}`

**Response** (200 OK):
```json
{
  "message": "Logged out successfully"
}
```

---

## User Management

### GET /api/v1/users/me

Get current authenticated user.

**Headers**: `Authorization: Bearer {access_token}`

**Response** (200 OK):
```json
{
  "id": 1,
  "email": "user@example.com",
  "full_name": "John Doe",
  "avatar_url": "https://cdn.opentsx.com/avatars/1.jpg",
  "bio": "Data scientist passionate about time series",
  "is_verified": true,
  "is_superuser": false,
  "created_at": "2025-01-01T00:00:00Z",
  "last_login_at": "2025-01-13T10:30:00Z",
  "organizations": [
    {
      "id": 1,
      "name": "My Organization",
      "slug": "my-org",
      "role": "admin"
    }
  ]
}
```

---

### PATCH /api/v1/users/me

Update current user profile.

**Headers**: `Authorization: Bearer {access_token}`

**Request Body**:
```json
{
  "full_name": "John Smith",
  "bio": "Updated bio",
  "avatar_url": "https://example.com/avatar.jpg"
}
```

**Response** (200 OK):
```json
{
  "message": "Profile updated successfully",
  "user": {
    "id": 1,
    "full_name": "John Smith",
    "bio": "Updated bio"
  }
}
```

---

### POST /api/v1/users/me/change-password

Change password for current user.

**Headers**: `Authorization: Bearer {access_token}`

**Request Body**:
```json
{
  "current_password": "OldPassword123!",
  "new_password": "NewSecurePassword456!"
}
```

**Response** (200 OK):
```json
{
  "message": "Password changed successfully"
}
```

**Errors**:
- `400`: Weak new password
- `401`: Current password incorrect

---

## Organizations

### GET /api/v1/organizations

List organizations user belongs to.

**Headers**: `Authorization: Bearer {access_token}`

**Response** (200 OK):
```json
{
  "organizations": [
    {
      "id": 1,
      "name": "My Organization",
      "slug": "my-org",
      "description": "Our research lab",
      "role": "admin",
      "plan_tier": "professional",
      "member_count": 5,
      "flow_count": 12,
      "created_at": "2025-01-01T00:00:00Z"
    }
  ]
}
```

---

### POST /api/v1/organizations

Create new organization.

**Headers**: `Authorization: Bearer {access_token}`

**Request Body**:
```json
{
  "name": "Data Science Team",
  "slug": "data-science-team",
  "description": "Our analytics workspace"
}
```

**Response** (201 Created):
```json
{
  "message": "Organization created successfully",
  "organization": {
    "id": 2,
    "name": "Data Science Team",
    "slug": "data-science-team",
    "plan_tier": "free",
    "max_flows": 5,
    "max_executions_per_month": 1000,
    "max_team_members": 3
  }
}
```

**Errors**:
- `400`: Invalid slug (must be URL-safe)
- `409`: Slug already taken

---

### GET /api/v1/organizations/{org_id}

Get organization details.

**Headers**: `Authorization: Bearer {access_token}`

**Path Parameters**:
- `org_id` (integer): Organization ID

**Response** (200 OK):
```json
{
  "id": 1,
  "name": "My Organization",
  "slug": "my-org",
  "description": "Our research lab",
  "logo_url": "https://cdn.opentsx.com/logos/1.png",
  "plan_tier": "professional",
  "owner": {
    "id": 1,
    "email": "owner@example.com",
    "full_name": "Organization Owner"
  },
  "subscription": {
    "plan_tier": "professional",
    "max_flows": 100,
    "max_executions_per_month": 500000,
    "max_team_members": 50,
    "current_flows": 12,
    "current_executions_this_month": 45231,
    "billing_cycle": "monthly",
    "next_billing_date": "2025-02-01T00:00:00Z"
  },
  "members": [
    {
      "id": 1,
      "email": "member@example.com",
      "full_name": "Team Member",
      "role": "admin",
      "joined_at": "2025-01-01T00:00:00Z"
    }
  ],
  "teams": [
    {
      "id": 1,
      "name": "Engineering",
      "member_count": 3
    }
  ],
  "created_at": "2025-01-01T00:00:00Z",
  "updated_at": "2025-01-13T10:00:00Z"
}
```

**Errors**:
- `403`: Not a member of organization
- `404`: Organization not found

---

### PATCH /api/v1/organizations/{org_id}

Update organization details.

**Headers**: `Authorization: Bearer {access_token}`

**Path Parameters**:
- `org_id` (integer): Organization ID

**Request Body**:
```json
{
  "name": "Updated Name",
  "description": "Updated description",
  "logo_url": "https://example.com/logo.png"
}
```

**Response** (200 OK):
```json
{
  "message": "Organization updated successfully",
  "organization": {
    "id": 1,
    "name": "Updated Name"
  }
}
```

**Errors**:
- `403`: Must be organization admin

---

### DELETE /api/v1/organizations/{org_id}

Delete organization (admin only).

**Headers**: `Authorization: Bearer {access_token}`

**Path Parameters**:
- `org_id` (integer): Organization ID

**Response** (200 OK):
```json
{
  "message": "Organization deleted successfully"
}
```

**Errors**:
- `403`: Must be organization owner
- `409`: Organization has active subscriptions

---

## Teams

### GET /api/v1/organizations/{org_id}/teams

List teams in organization.

**Headers**: `Authorization: Bearer {access_token}`

**Path Parameters**:
- `org_id` (integer): Organization ID

**Response** (200 OK):
```json
{
  "teams": [
    {
      "id": 1,
      "name": "Engineering",
      "description": "Backend engineers",
      "member_count": 5,
      "created_at": "2025-01-05T00:00:00Z"
    }
  ]
}
```

---

### POST /api/v1/organizations/{org_id}/teams

Create new team.

**Headers**: `Authorization: Bearer {access_token}`

**Path Parameters**:
- `org_id` (integer): Organization ID

**Request Body**:
```json
{
  "name": "Data Science",
  "description": "ML and analytics team"
}
```

**Response** (201 Created):
```json
{
  "message": "Team created successfully",
  "team": {
    "id": 2,
    "name": "Data Science",
    "description": "ML and analytics team"
  }
}
```

---

## Invitations

### POST /api/v1/organizations/{org_id}/invitations

Invite user to organization.

**Headers**: `Authorization: Bearer {access_token}`

**Path Parameters**:
- `org_id` (integer): Organization ID

**Request Body**:
```json
{
  "email": "newmember@example.com",
  "role": "member",
  "team_id": 1
}
```

**Response** (201 Created):
```json
{
  "message": "Invitation sent successfully",
  "invitation": {
    "id": 1,
    "email": "newmember@example.com",
    "role": "member",
    "token": "inv_abc123xyz",
    "expires_at": "2025-01-20T00:00:00Z"
  }
}
```

**Errors**:
- `400`: Invalid email
- `403`: Not authorized to invite
- `409`: User already a member

---

### POST /api/v1/invitations/{token}/accept

Accept invitation and join organization.

**Headers**: `Authorization: Bearer {access_token}`

**Path Parameters**:
- `token` (string): Invitation token

**Response** (200 OK):
```json
{
  "message": "Invitation accepted successfully",
  "organization": {
    "id": 1,
    "name": "Organization Name",
    "role": "member"
  }
}
```

**Errors**:
- `404`: Invalid or expired token
- `409`: Already accepted

---

## Flows

### GET /api/v1/flows

List flows.

**Headers**: `Authorization: Bearer {access_token}`

**Query Parameters**:
- `organization_id` (integer, optional): Filter by organization
- `team_id` (integer, optional): Filter by team
- `status` (string, optional): Filter by status (draft, active, paused, archived)
- `limit` (integer, optional): Max results (default: 20, max: 100)
- `offset` (integer, optional): Pagination offset

**Response** (200 OK):
```json
{
  "flows": [
    {
      "id": 1,
      "name": "Stock Market DFA",
      "description": "Analyze persistence in stock prices",
      "status": "active",
      "is_public": false,
      "owner": {
        "id": 1,
        "full_name": "John Doe"
      },
      "organization": {
        "id": 1,
        "name": "My Organization"
      },
      "execution_count": 147,
      "last_executed_at": "2025-01-13T10:30:00Z",
      "created_at": "2025-01-10T08:00:00Z",
      "updated_at": "2025-01-13T09:00:00Z"
    }
  ],
  "total": 1,
  "limit": 20,
  "offset": 0
}
```

---

### GET /api/v1/flows/{flow_id}

Get flow details.

**Headers**: `Authorization: Bearer {access_token}`

**Path Parameters**:
- `flow_id` (integer): Flow ID

**Response** (200 OK):
```json
{
  "id": 1,
  "name": "Stock Market DFA",
  "description": "Analyze persistence in stock prices",
  "definition": {
    "nodes": [
      {
        "id": "source_1",
        "type": "data.kafka_consumer",
        "label": "Stock Prices",
        "position": {"x": 100, "y": 100},
        "config": {
          "topic": "stock_prices",
          "bootstrap_servers": "localhost:9092"
        }
      },
      {
        "id": "dfa_1",
        "type": "analysis.dfa",
        "label": "DFA Analysis",
        "position": {"x": 300, "y": 100},
        "config": {
          "polynom_order": 1,
          "min_scale": 10,
          "max_scale": 1000
        }
      }
    ],
    "edges": [
      {
        "id": "e1",
        "source": "source_1",
        "target": "dfa_1"
      }
    ]
  },
  "status": "active",
  "tags": ["finance", "dfa"],
  "category": "market-analysis",
  "execution_count": 147,
  "last_executed_at": "2025-01-13T10:30:00Z"
}
```

**Errors**:
- `403`: No access to flow
- `404`: Flow not found

---

### POST /api/v1/flows

Create new flow.

**Headers**: `Authorization: Bearer {access_token}`

**Request Body**:
```json
{
  "name": "My New Flow",
  "description": "Flow description",
  "definition": {
    "nodes": [...],
    "edges": [...]
  },
  "organization_id": 1,
  "team_id": 1,
  "tags": ["tutorial", "dfa"],
  "category": "example"
}
```

**Response** (201 Created):
```json
{
  "message": "Flow created successfully",
  "flow": {
    "id": 2,
    "name": "My New Flow",
    "status": "draft"
  }
}
```

**Errors**:
- `400`: Invalid flow definition
- `403`: Exceeded flow limit for plan

---

### PUT /api/v1/flows/{flow_id}

Update flow.

**Headers**: `Authorization: Bearer {access_token}`

**Path Parameters**:
- `flow_id` (integer): Flow ID

**Request Body**:
```json
{
  "name": "Updated Name",
  "description": "Updated description",
  "definition": {
    "nodes": [...],
    "edges": [...]
  },
  "status": "active"
}
```

**Response** (200 OK):
```json
{
  "message": "Flow updated successfully",
  "flow": {
    "id": 1,
    "name": "Updated Name",
    "status": "active"
  }
}
```

---

### DELETE /api/v1/flows/{flow_id}

Delete flow.

**Headers**: `Authorization: Bearer {access_token}`

**Path Parameters**:
- `flow_id` (integer): Flow ID

**Response** (200 OK):
```json
{
  "message": "Flow deleted successfully"
}
```

---

## Flow Executions

### POST /api/v1/flows/{flow_id}/execute

Execute flow.

**Headers**: `Authorization: Bearer {access_token}`

**Path Parameters**:
- `flow_id` (integer): Flow ID

**Request Body** (optional):
```json
{
  "input_data": {
    "parameter1": "value1"
  }
}
```

**Response** (202 Accepted):
```json
{
  "message": "Flow execution started",
  "execution": {
    "id": 1,
    "flow_id": 1,
    "status": "running",
    "started_at": "2025-01-13T12:00:00Z"
  }
}
```

**Errors**:
- `403`: Exceeded execution limit
- `422`: Invalid flow definition

---

### GET /api/v1/flows/{flow_id}/executions

List flow executions.

**Headers**: `Authorization: Bearer {access_token}`

**Path Parameters**:
- `flow_id` (integer): Flow ID

**Query Parameters**:
- `status` (string, optional): Filter by status
- `limit` (integer, optional): Max results
- `offset` (integer, optional): Pagination offset

**Response** (200 OK):
```json
{
  "executions": [
    {
      "id": 1,
      "flow_id": 1,
      "status": "completed",
      "started_at": "2025-01-13T10:30:00Z",
      "completed_at": "2025-01-13T10:30:45Z",
      "duration_ms": 45000,
      "events_processed": 1523,
      "result": {
        "alpha": 0.745,
        "r_squared": 0.982
      },
      "node_results": {
        "dfa_1": {
          "alpha": 0.745,
          "interpretation": "Correlated (persistent)"
        }
      }
    }
  ],
  "total": 1
}
```

---

### GET /api/v1/executions/{execution_id}

Get execution details.

**Headers**: `Authorization: Bearer {access_token}`

**Path Parameters**:
- `execution_id` (integer): Execution ID

**Response** (200 OK):
```json
{
  "id": 1,
  "flow_id": 1,
  "status": "completed",
  "started_at": "2025-01-13T10:30:00Z",
  "completed_at": "2025-01-13T10:30:45Z",
  "duration_ms": 45000,
  "events_processed": 1523,
  "result": {...},
  "node_results": {...},
  "logs": [
    {
      "timestamp": "2025-01-13T10:30:05Z",
      "level": "INFO",
      "message": "Processing started"
    }
  ]
}
```

---

## Node Types

### GET /api/v1/node-types

List available node types.

**Response** (200 OK):
```json
{
  "node_types": [
    {
      "id": "data.kafka_consumer",
      "name": "Kafka Consumer",
      "category": "data_source",
      "icon": "📥",
      "description": "Subscribe to Kafka topic",
      "inputs": [],
      "outputs": ["time_series"],
      "config_schema": {
        "type": "object",
        "properties": {
          "topic": {
            "type": "string",
            "required": true,
            "description": "Kafka topic name"
          },
          "bootstrap_servers": {
            "type": "string",
            "default": "localhost:9092"
          }
        }
      }
    }
  ]
}
```

---

## Billing

### GET /api/v1/organizations/{org_id}/billing

Get billing information.

**Headers**: `Authorization: Bearer {access_token}`

**Path Parameters**:
- `org_id` (integer): Organization ID

**Response** (200 OK):
```json
{
  "plan_tier": "professional",
  "billing_cycle": "monthly",
  "amount": 99.00,
  "currency": "USD",
  "next_billing_date": "2025-02-01T00:00:00Z",
  "payment_method": {
    "type": "card",
    "last4": "4242",
    "brand": "visa",
    "exp_month": 12,
    "exp_year": 2026
  },
  "usage": {
    "flows": {
      "current": 12,
      "limit": 100
    },
    "executions": {
      "current_month": 45231,
      "limit": 500000
    },
    "team_members": {
      "current": 8,
      "limit": 50
    }
  }
}
```

---

### POST /api/v1/billing/create-checkout-session

Create Stripe checkout session to upgrade plan.

**Headers**: `Authorization: Bearer {access_token}`

**Request Body**:
```json
{
  "organization_id": 1,
  "plan_tier": "professional"
}
```

**Response** (200 OK):
```json
{
  "checkout_url": "https://checkout.stripe.com/session_abc123",
  "session_id": "cs_test_abc123"
}
```

---

## Error Handling

### Error Response Format

All errors follow this format:

```json
{
  "error": {
    "code": "VALIDATION_ERROR",
    "message": "Invalid request parameters",
    "details": {
      "field": "email",
      "issue": "Invalid email format"
    }
  }
}
```

### HTTP Status Codes

| Code | Meaning | Description |
|------|---------|-------------|
| 200 | OK | Request successful |
| 201 | Created | Resource created |
| 202 | Accepted | Request accepted (async) |
| 400 | Bad Request | Invalid parameters |
| 401 | Unauthorized | Missing/invalid auth token |
| 403 | Forbidden | Not authorized |
| 404 | Not Found | Resource not found |
| 409 | Conflict | Resource conflict |
| 422 | Unprocessable Entity | Validation failed |
| 429 | Too Many Requests | Rate limit exceeded |
| 500 | Internal Server Error | Server error |
| 503 | Service Unavailable | Service down |

---

## Rate Limits

### Limits by Plan

| Plan | Requests/minute | Burst |
|------|-----------------|-------|
| Free | 60 | 100 |
| Starter | 300 | 500 |
| Professional | 1000 | 2000 |
| Enterprise | Custom | Custom |

### Rate Limit Headers

```
X-RateLimit-Limit: 60
X-RateLimit-Remaining: 45
X-RateLimit-Reset: 1642089600
```

### Rate Limit Exceeded

**Response** (429 Too Many Requests):
```json
{
  "error": {
    "code": "RATE_LIMIT_EXCEEDED",
    "message": "Too many requests",
    "retry_after": 30
  }
}
```

---

## Webhooks

### Configure Webhooks

Organization → Settings → Webhooks

**Supported Events**:
- `flow.created`
- `flow.updated`
- `flow.deleted`
- `execution.started`
- `execution.completed`
- `execution.failed`
- `member.added`
- `member.removed`

### Webhook Payload

```json
{
  "event": "execution.completed",
  "timestamp": "2025-01-13T12:00:00Z",
  "data": {
    "execution_id": 1,
    "flow_id": 1,
    "status": "completed",
    "duration_ms": 45000,
    "result": {...}
  }
}
```

### Webhook Signature Verification

Verify webhook authenticity:

```python
import hmac
import hashlib

def verify_webhook(payload, signature, secret):
    expected = hmac.new(
        secret.encode(),
        payload.encode(),
        hashlib.sha256
    ).hexdigest()
    return hmac.compare_digest(signature, expected)
```

---

## SDKs & Client Libraries

### Python SDK

```bash
pip install opentsx-client
```

```python
from opentsx_client import OpenTSxClient

client = OpenTSxClient(api_key="your-api-key")
flows = client.flows.list()
```

### JavaScript SDK

```bash
npm install @opentsx/client
```

```javascript
import { OpenTSxClient } from '@opentsx/client';

const client = new OpenTSxClient({ apiKey: 'your-api-key' });
const flows = await client.flows.list();
```

---

## OpenAPI Specification

Download complete OpenAPI 3.0 specification:

**URL**: https://api.opentsx.com/api/v1/openapi.json

**Interactive Docs**: https://api.opentsx.com/docs

---

**Version**: 1.0.0
**Last Updated**: January 2025
**Support**: api@opentsx.com
