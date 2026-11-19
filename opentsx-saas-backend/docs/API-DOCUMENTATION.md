# OpenTSx API Documentation 📡

**REST API Reference for OpenTSx SaaS Platform**

Version: 1.0.0 | Base URL: `https://api.opentsx.com`

---

## Table of Contents

1. [Authentication](#authentication)
2. [Flows](#flows)
3. [Error Handling](#error-handling)
4. [Rate Limits](#rate-limits)
5. [Webhooks](#webhooks)

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
- `403`: `sign-up is closed; ask an administrator of this installation for an account` while `ENABLE_SIGNUP=false`
- `409`: `email: already registered; sign in or use another address`
- `422`: an invalid address or a password shorter than 8 characters or longer than 72 bytes

---

### POST /api/v1/auth/login

Login and receive a JWT access token. There is no refresh token; sign in again when the access token expires.

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
- `401`: `email or password: not correct; check both and sign in again`

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
- `404`: `flow <id>: not found; use the id of one of your flows`

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

## Error Handling

### Error Response Format

Every error answers this body. [The backend README](../README.md#errors) lists the statuses, types and texts.

```json
{"error": "<text>", "type": "<type>"}
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
