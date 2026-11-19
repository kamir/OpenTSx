# OpenTSx Backend

FastAPI backend with JWT authentication, async SQLAlchemy, and PostgreSQL.

## Quick Start

```bash
# Create virtual environment
python3 -m venv venv
source venv/bin/activate  # On Windows: venv\Scripts\activate

# Install dependencies
pip install -r requirements.txt

# Create .env file (see below)
cp .env.example .env
# Edit .env with your configuration

# Run development server
uvicorn app.main:app --reload
```

Backend will be available at: **http://localhost:8000**

API Documentation: **http://localhost:8000/docs**

## Environment Variables

The backend reads its settings from the environment and from `.env` in the directory it starts in
(`app/core/config.py`). A variable in the environment wins over the same key in `.env`. Copy `.env.example` to
`opentsx-saas-backend/.env`, which git ignores, and fill in the three required values.

| Variable | Default | Allowed |
|----------|---------|---------|
| `SECRET_KEY` | none, required | at least 32 characters; signs the login tokens |
| `POSTGRES_PASSWORD` | none, required | at least 12 characters |
| `FIRST_SUPERUSER_PASSWORD` | none, required | at least 12 characters and at most 72 bytes in UTF-8; used only when the first administrator is created |
| `POSTGRES_SERVER` | `localhost` | host name |
| `POSTGRES_PORT` | `5432` | port |
| `POSTGRES_USER` | `opentsx` | user name |
| `POSTGRES_DB` | `opentsx` | database name |
| `FIRST_SUPERUSER_EMAIL` | `admin@opentsx.com` | the first administrator, created at start when no account with this address exists |
| `ALGORITHM` | `HS256` | JWT signing algorithm |
| `ACCESS_TOKEN_EXPIRE_MINUTES` | `30` | minutes |
| `BACKEND_CORS_ORIGINS` | `["http://localhost:3000","http://localhost:8000"]` | JSON list of origins |

The database URL is built from the `POSTGRES_*` variables. The backend reads no `DATABASE_URL`.

Generate each required value with:

```bash
python3 -c "import secrets; print(secrets.token_urlsafe(32))"
```

A required value that is missing, shorter than its minimum, or one of the example values published in this
repository stops the start. The message names the variable and does not show the value:

```text
SECRET_KEY
  Field required [type=missing]
```

```text
SECRET_KEY
  Value error, SECRET_KEY: shorter than 32 characters; set at least 32 random characters [type=value_error]
```

`TEST_LOGIN` is the only switch for a lab: it lets `admin` with the password `admin` sign in (see
[Test operation](#test-operation)). It does not replace the required values.

## Project Structure

```
backend/
├── app/
│   ├── __init__.py
│   ├── main.py                 # FastAPI application
│   ├── api/
│   │   ├── __init__.py
│   │   └── auth.py             # Authentication endpoints
│   ├── core/
│   │   ├── __init__.py
│   │   ├── config.py           # Settings
│   │   ├── security.py         # JWT & password utilities
│   │   └── deps.py             # Dependencies
│   ├── db/
│   │   ├── __init__.py
│   │   ├── base_class.py       # Base model
│   │   ├── session.py          # Database session
│   │   └── init_db.py          # DB initialization
│   ├── models/
│   │   ├── __init__.py
│   │   ├── user.py             # User model
│   │   ├── organization.py     # Organization model
│   │   └── flow.py             # Flow model
│   ├── schemas/
│   │   ├── __init__.py
│   │   ├── user.py             # User Pydantic schemas
│   │   └── flow.py             # Flow Pydantic schemas
│   └── services/
│       ├── __init__.py
│       └── auth_service.py     # Auth business logic
├── requirements.txt
├── .env
└── README.md
```

## Dependencies

Key dependencies:

- **FastAPI**: Web framework
- **Uvicorn**: ASGI server
- **SQLAlchemy 2.0**: ORM with async support
- **AsyncPG**: PostgreSQL driver
- **python-jose**: JWT handling
- **passlib[bcrypt]**: Password hashing
- **Pydantic v2**: Data validation

## API Endpoints

### Authentication

| Route | What |
|-------|------|
| `POST /api/v1/auth/register` | registers a user; body `email`, `password`, `full_name` |
| `POST /api/v1/auth/login` | signs in; body `email`, `password`; answers `access_token` and `refresh_token` |
| `POST /api/v1/auth/logout` | answers success; the tokens stay valid until they expire, so the client discards them |
| `GET /api/v1/auth/me` | the signed-in user |
| `PATCH /api/v1/auth/me` | changes `email`, `full_name`, `bio` or `avatar_url` of the signed-in user |
| `POST /api/v1/auth/change-password` | body `current_password`, `new_password` |

Passwords for register and change-password take 8 characters to 72 bytes in UTF-8, the most bcrypt reads. A longer
one answers 422 with the message `at most 72 bytes` for the field.

### Test operation

With `TEST_LOGIN=true` the name `admin` with the password `admin` signs in as the first administrator
(`FIRST_SUPERUSER_EMAIL`). `GET /api/v1/auth/mode` tells the login page, which then shows the hint. The stored
password of the account is not involved and not changed, so switching the setting off ends it. Off by default;
with it on, everyone who reaches the page is an administrator.

### DAG deployments

| Route | What |
|-------|------|
| `GET /api/v1/dags/capabilities` | whether the signed-in user may deploy and stop DAGs here, and if not, why |
| `POST /api/v1/dags/deploy` | body: an `opentsx.dag.v1` document as YAML. The backend checks the account and forwards the document to the retrieval server, which hands it to the vector processor |
| `POST /api/v1/dags/stop?name=<dag>` | stops the DAG of that name |

Settings: `RETRIEVAL_URL` (the retrieval server; unset means no deployments), `DAG_RUN_TOKEN` (sent as
`X-OpenTSx-Run-Token`, must match `OPENTSX_DAG_RUN_TOKEN` of the retrieval server) and `DAG_RUN_ROLE`
(`superuser`, the default: administrators only; `user`: every account). Sign-up is open, so `user` lets everyone
who can register deploy and stop.

### Flows

| Route | What |
|-------|------|
| `GET /api/v1/flows` | the flows of the signed-in user, most recently changed first |
| `POST /api/v1/flows` | stores a new flow as draft; body `name`, `definition` and optional fields |
| `GET /api/v1/flows/{flow_id}` | one flow with its definition |
| `PUT /api/v1/flows/{flow_id}` | changes the given fields of a flow |
| `DELETE /api/v1/flows/{flow_id}` | deletes a flow and its execution history |
| `GET /api/v1/flows/{flow_id}/executions` | the execution history of a flow, newest first |

The backend stores flows and does not run them. A flow runs when its DAG is deployed through
`POST /api/v1/dags/deploy`.

### Systems

| Route | What |
|-------|------|
| `GET /api/v1/systems/{system}/bindings` | the working copy of the signed-in user for this system |
| `PUT /api/v1/systems/{system}/bindings` | sets the source of one grade of one signal; body `signal`, `grade`, `source`, `meaningConfirmed`, `baseHash` |
| `DELETE /api/v1/systems/{system}/bindings?signal=<signal>&grade=<grade>` | takes a source out of the working copy |
| `POST /api/v1/systems/{system}/generate` | runs the demo generator of a signal through the retrieval server; body `signal`, `from`, `to`; same settings and rights as a DAG deployment |

See full API documentation at: `/docs` (Swagger UI)

### Errors

Every error answers a JSON object with two fields, `error` and `type`:

```json
{"error":"GET /api/v1/nothing: no such route; see /docs for the routes","type":"not_found"}
```

`error` is the reason in words, in the form `<subject>: <problem>; <what is allowed>`, without a final period.
`type` is one of the types of the retrieval server: branch on `type`, not on the text.

| Status | `type` | When |
|--------|--------|------|
| `400` | `bad_request` | an empty DAG body; `change-password` with a wrong current password; a new flow of an account without organization; a request the retrieval server refused, with its reason |
| `401` | `unauthorized` | no token, a token that is not valid or has expired, or a token whose account no longer exists; the header `WWW-Authenticate: Bearer` comes along |
| `403` | `forbidden` | an inactive account; a new flow for an organization the account is not a member of; an account that may not deploy, stop or generate under `DAG_RUN_ROLE` |
| `404` | `not_found` | a path the backend does not know; a flow of another account or none; a binding the working copy does not hold |
| `405` | `method_not_allowed` | a known path with a method it does not take; the `Allow` header lists the methods it takes |
| `409` | `bad_request` | `register` with an address that is already registered |
| `413` | `too_large` | a DAG over 262144 bytes |
| `422` | `bad_request` | a field that is missing or malformed, a body that is not valid JSON, a binding that cannot be a source |
| `500` | `internal` | a fault in the backend; the body is always `{"error":"internal error","type":"internal"}` |
| `502` | `unavailable` | the retrieval server cannot be reached, answers an error of its own, or answers 200 with something other than a JSON object |
| `503` | `unavailable` | `RETRIEVAL_URL` is not set; the retrieval server refuses the run token or does not offer the route |

A refused field names the field and the problem; the value of the field is not repeated, so a password does not
come back:

```json
{"error":"email: value is not a valid email address: The part after the @-sign is not valid. It should have a period; password: String should have at least 8 characters","type":"bad_request"}
```

A refused binding names the value it refuses:

```json
{"error":"grade: \"x\" is not allowed; expected one of raw, clean","type":"bad_request"}
```

The texts of the token check and of the flow routes:

| Status | `error` |
|--------|---------|
| `400` | `this account: belongs to no organization; a flow needs one` |
| `401` | `Authorization: no bearer token; sign in and send Authorization: Bearer <access token>` |
| `401` | `token: not valid or expired; sign in again` |
| `401` | `token: the account no longer exists; sign in with an existing account` |
| `403` | `this account: deactivated; ask an administrator to activate it` |
| `403` | `organization_id: <id> is not an organization of this account; leave it out to use the first one` |
| `404` | `flow <id>: not found; use the id of one of your flows` |
| `422` | `status: "<status>" is not allowed; expected one of draft, active, paused, archived` |

The texts towards the retrieval server:

| Status | `error` |
|--------|---------|
| `400` | the `error` of the retrieval server, or `retrieval server: refused the request without a reason; check the request` |
| `502` | `retrieval server: not reachable; try again later` |
| `502` | `retrieval server: answered HTTP <status>; see the log of the backend` |
| `502` | `retrieval server: gave an answer the backend cannot read; check that RETRIEVAL_URL names the retrieval server` |
| `503` | `retrieval server: refused the run token of this installation; DAG_RUN_TOKEN must equal OPENTSX_DAG_RUN_TOKEN of the retrieval server` |
| `503` | `retrieval server: does not deploy DAGs; it needs --kafscale and --dag-runs true` |
| `503` | `retrieval server: does not offer this route or system; see the log of the backend` |
| `503` | `RETRIEVAL_URL: not set; this installation deploys no DAGs` |
| `503` | `RETRIEVAL_URL: not set; this installation runs no generators` |

The text of an upstream error other than 400 stays in the log of the backend (logger `uvicorn.error.dags`, level
WARNING):

```text
dag deploy by user 1 refused by the retrieval server: HTTP 500 error='boom'
```

A 500 writes one line at ERROR (logger `uvicorn.error.errors`) with the method, the path and the exception with its
causes; the stack trace follows at DEBUG:

```text
request failed method=<method> path=<path> status=500 cause=<exception>; caused by <exception>
```

## Database Setup

### Create PostgreSQL Database

```bash
sudo -u postgres psql
CREATE DATABASE opentsx;
CREATE USER opentsx_user WITH ENCRYPTED PASSWORD '<password>';
GRANT ALL PRIVILEGES ON DATABASE opentsx TO opentsx_user;
\q
```

`<password>` is the `POSTGRES_PASSWORD` of your `.env`, at least 12 characters.

### Database Migrations (Optional)

```bash
# Install Alembic
pip install alembic

# Initialize
alembic init alembic

# Create migration
alembic revision --autogenerate -m "Initial schema"

# Apply
alembic upgrade head
```

## Running Tests

```bash
pip install -r requirements.txt -r requirements-dev.txt
python -m pytest tests
```

`tests/test_dags.py` covers the DAG routes without a database or a network: the user comes from a dependency
override, the retrieval server from an httpx mock transport. Accounts and stored flows are checked against a running
instance with `smoke-flows.sh`.

## Production Deployment

```bash
# Install gunicorn
pip install gunicorn

# Run with gunicorn
gunicorn app.main:app \
  --workers 4 \
  --worker-class uvicorn.workers.UvicornWorker \
  --bind 0.0.0.0:8000
```

## Docker

```dockerfile
FROM python:3.11-slim

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY . .

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
```

```bash
# Build
docker build -t opentsx-backend .

# Run
docker run -p 8000:8000 --env-file .env opentsx-backend
```

### Docker Compose (Full Stack)

This starts the backend, the frontend and PostgreSQL. Set `SECRET_KEY`, `POSTGRES_PASSWORD` and
`FIRST_SUPERUSER_PASSWORD` in `.env` first (see [DOCKER-SETUP.md](DOCKER-SETUP.md)):

```bash
docker-compose up -d --build
```

- **Frontend**: http://localhost:3000
- **Backend**: http://localhost:8000

## Authentication Flow

```
Client                    Server
  │                         │
  │ POST /api/v1/auth/login │
  │ {email, password}       │
  ├────────────────────────→│
  │                         │
  │                    Verify credentials
  │                    Generate JWT tokens
  │                         │
  │ {access_token,          │
  │  refresh_token}         │
  │←────────────────────────┤
  │                         │
Store tokens                │
  │                         │
  │ GET /api/v1/flows       │
  │ Authorization: Bearer   │
  ├────────────────────────→│
  │                         │
  │                    Validate JWT
  │                    Get user from DB
  │                         │
  │ {flows: [...]}          │
  │←────────────────────────┤
```

## Security Features

- **JWT Authentication**: Access tokens (30min), Refresh tokens (7 days)
- **Password Hashing**: bcrypt with salt
- **CORS Protection**: Configurable origins
- **Input Validation**: Pydantic schemas
- **SQL Injection Prevention**: SQLAlchemy ORM

## Development

```bash
# Format code
black app/

# Lint
flake8 app/

# Type check
mypy app/
```

## Troubleshooting

### Database Connection Error

```bash
# Check PostgreSQL is running
sudo systemctl status postgresql

# Test connection
psql -U postgres -h localhost -d opentsx
```

### ModuleNotFoundError

```bash
# Activate virtual environment
source venv/bin/activate

# Reinstall dependencies
pip install -r requirements.txt
```

## Documentation

- **Full SaaS Documentation**: [../SAAS-PLATFORM.md](../SAAS-PLATFORM.md)
- **API Reference**: [../docs/API-DOCUMENTATION.md](../docs/API-DOCUMENTATION.md)

## License

Apache License 2.0
