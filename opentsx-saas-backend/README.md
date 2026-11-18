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

See full API documentation at: `/docs` (Swagger UI)

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
