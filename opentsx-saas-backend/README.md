# OpenTSx Backend

FastAPI backend with JWT authentication, async SQLAlchemy, and PostgreSQL.

## Admit physical deployments

You need the configured PostgreSQL journal, an account the run role admits (`DAG_RUN_ROLE`: administrators, or
every account with `user`) and an authenticated Wayang driver.
The opt-in `physical` profile uses the existing `/api/v1/dags/wayang-rust/deploy` request with `flowId`, `dagText`
and `profile: "physical"`. The default host provider returns `deployment.incarnation` because authoritative
incarnation facts are unavailable. This API path does not establish production qualification or publication fencing.
The Workbench target selector retains its bounded and continuous local profiles.

The backend verifies the unchanged legacy plan hash or the physical envelope hash before accepting a deployment.
The physical response exposes `assignmentHash`; its opaque worker `requestHash` appears only with a verified receipt.
Physical admission also binds exact assignment bytes, effective computation, original nodes and channels to trusted
capability, residency and topic-incarnation observations. Computation identity uses the required
`["opentsx.computation.v1", taggedValue]` frame; missing or unknown versions are refused.
Retries retain the existing journal's exact plan bytes and revalidate the observations before execution.
Located refusals use `opentsx.plan-refusal.v1` in HTTP `detail`;
messages contain only the fixed code, original node and field location. See the
[physical deployment contract](../docs/datamodel-2026/20-WAYANG-TS-OPERATORS-SPEC.md#s115-admit-a-physical-deployment).

## Upgrade the offline ownership journal

While the API and coordinator are running, complete or stop their deployments and wait for final reconciliation,
retired publication authority and released topic owners. Then stop both services and back up the database using
your existing PostgreSQL procedure. Startup refuses legacy ownership shapes and any partial Wayang journal instead
of recreating missing tables. A new journal is created only when none of the six Wayang journal tables exists.
The sole `wayang_topic_owners` table uses typed legacy rows or the physical
`(fragment, cluster, incarnation, topic)` key for every input and sink topic channel.
Legacy rows contain no trusted physical identity and conservatively
conflict with physical claims on the same topic name.

The offline upgrade requires an empty legacy ownership table, terminal deployments and cycles, retired publication
authority and no pending switch or orphan history. It preserves all deployment, plan, result and history rows.
Active or ambiguous ownership reports
`The Wayang journal has active or ambiguous legacy ownership; upgrade refused.`
Resolve ownership through the running services' stop and reconciliation paths; do not delete reservations or
invent incarnations.
Run from `opentsx-saas-backend` with its pinned requirements installed:

```bash
python -m app.db.upgrade_wayang_ownership --help
python -m pytest -q tests/test_wayang_physical.py -k offline
```

The regression command executes the entry point against a disposable SQLite fixture, proves refusal without mutation
and verifies byte-preserved completed history. It does not change the configured deployment database.

With both services stopped and the backup verified, upgrade the configured journal and repeat the command to check
the installed shape. The required `--offline` option records your confirmation that both services are stopped;
it does not stop or inspect them. The module uses the existing `POSTGRES_*` configuration.

```bash
python -m app.db.upgrade_wayang_ownership --offline
python -m app.db.upgrade_wayang_ownership --offline
```

The first successful upgrade prints `Wayang ownership schema upgraded.` The repeated check prints
`Wayang ownership schema is current.` A partial existing journal requires restoration from a consistent, verified
backup before resolving or upgrading it. Do not recreate missing journal tables as empty replacements.

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

Create `.env` file in `backend/` directory:

```bash
# Application
PROJECT_NAME=OpenTSx SaaS Platform
API_V1_STR=/api/v1
DEBUG=True

# Security (⚠️ CHANGE IN PRODUCTION)
SECRET_KEY=your-secret-key-change-this-in-production
ALGORITHM=HS256
ACCESS_TOKEN_EXPIRE_MINUTES=30

# Database
DATABASE_URL=postgresql+asyncpg://postgres:postgres@localhost:5432/opentsx

# First Superuser
FIRST_SUPERUSER_EMAIL=admin@opentsx.com
FIRST_SUPERUSER_PASSWORD=admin123

# CORS
BACKEND_CORS_ORIGINS=["http://localhost:3000"]
```

## Generate Secret Key

```bash
python -c "import secrets; print(secrets.token_urlsafe(32))"
```

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

- `POST /api/v1/auth/register` - Register new user
- `POST /api/v1/auth/login` - Login and get JWT tokens
- `POST /api/v1/auth/refresh` - Refresh access token
- `GET /api/v1/auth/me` - Get current user

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

`DAG_RUN_ROLE` is the one setting for every run the backend forwards. It also decides who may run the demo
generators (`POST /api/v1/systems/<system>/generate`) and Wayang deployments (`/api/v1/dags/wayang-rust`): with
`user`, every account may do all three. The backend then prints at startup:

```text
DAG_RUN_ROLE=user: every signed-in account may deploy and stop DAGs, run demo generators and run Wayang deployments. Sign-up is open.
```

### System working copies

`PUT /api/v1/systems/<system>/bindings` stores the source of one grade of one signal in the working copy of the
signed-in user. A binding that cannot be a source answers 422 with the reason. A generator source names `gen.ou` or
`gen.sine` and has no `select`. `params` is optional and, when given, an object. `seed` is optional and, when given, a
whole number. The backend does not check the parameters of a generator; the retrieval server reports a broken rule as
a problem of the system document. The routes and rules are in
[SYSTEM-DOCUMENT-SPEC.md, section 7](../docs/datamodel-2026/SYSTEM-DOCUMENT-SPEC.md#s7-working-copy).

See full API documentation at: `/docs` (Swagger UI)

## Database Setup

### Create PostgreSQL Database

```bash
sudo -u postgres psql
CREATE DATABASE opentsx;
CREATE USER opentsx_user WITH ENCRYPTED PASSWORD 'your_password';
GRANT ALL PRIVILEGES ON DATABASE opentsx TO opentsx_user;
\q
```

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
override, the retrieval server from an httpx mock transport. `tests/test_systems.py` covers the working copies and
the generate route the same way. Accounts and stored flows are checked against a running
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

This starts the Backend, Frontend, Postgres, and Redis:

```bash
docker-compose up -d --build
```

- **Frontend**: http://localhost:3000
- **Backend**: http://localhost:8000

## Authentication Flow

```
Client                    Server
  │                         │
  │ POST /auth/login        │
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
  │ GET /flows              │
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
