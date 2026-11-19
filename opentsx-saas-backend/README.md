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
`opentsx-saas-backend/.env`, which git ignores, and fill in the three required values. Names are case-sensitive.

| Variable | Default | Allowed |
|----------|---------|---------|
| `SECRET_KEY` | none, required | at least 32 characters; signs the login tokens |
| `POSTGRES_PASSWORD` | none, required | at least 12 characters |
| `FIRST_SUPERUSER_PASSWORD` | none, required | at least 12 characters and at most 72 bytes in UTF-8; used only when the first administrator is created |
| `POSTGRES_SERVER` | `localhost` | host name |
| `POSTGRES_PORT` | `5432` | port |
| `POSTGRES_USER` | `opentsx` | user name |
| `POSTGRES_DB` | `opentsx` | database name |
| `FIRST_SUPERUSER_EMAIL` | `admin@opentsx.com` | the first administrator, created at start when no administrator exists (see [First administrator](#first-administrator)) |
| `ALGORITHM` | `HS256` | `HS256`, `HS384` or `HS512`; signs the login tokens |
| `ACCESS_TOKEN_EXPIRE_MINUTES` | `30` | minutes an access token is valid |
| `BACKEND_CORS_ORIGINS` | `["http://localhost:3000","http://localhost:8000"]` | JSON list of origins; `"*"` is refused, because the backend accepts credentials from these origins |
| `RETRIEVAL_URL` | none | `http` or `https` address of the retrieval server; unset or empty: no DAG deployments and no generators (see [DAG deployments](#dag-deployments)) |
| `DAG_RUN_TOKEN` | none | the run token of the retrieval server |
| `DAG_RUN_ROLE` | `superuser` | `superuser` or `user` |
| `TEST_LOGIN` | `false` | `true` or `false` (see [Test operation](#test-operation)) |
| `ENABLE_SIGNUP` | `true` | `false` closes `POST /api/v1/auth/register` (see [Sign-up](#sign-up)) |
| `LOG_LEVEL` | `INFO` | `DEBUG`, `INFO`, `WARNING` or `ERROR`, in any case (see [Logs](#logs)) |
| `PROJECT_NAME`, `VERSION`, `DESCRIPTION` | `OpenTSx SaaS`, `1.0.0`, `Visual Time Series Analysis Platform` | the title, version and description of `/docs` |
| `API_V1_STR` | `/api/v1` | the path prefix of the API |

The database URL is built from the `POSTGRES_*` variables. The backend reads no `DATABASE_URL`.

A key in `.env` that is not in this table stops the start, and the message lists the settings:

```text
  Value error, DATABASE_URL: not a setting of the backend; remove it from .env, the settings are API_V1_STR, PROJECT_NAME, VERSION, DESCRIPTION, BACKEND_CORS_ORIGINS, SECRET_KEY, ALGORITHM, ACCESS_TOKEN_EXPIRE_MINUTES, POSTGRES_SERVER, POSTGRES_USER, POSTGRES_PASSWORD, POSTGRES_DB, POSTGRES_PORT, FIRST_SUPERUSER_EMAIL, FIRST_SUPERUSER_PASSWORD, RETRIEVAL_URL, DAG_RUN_TOKEN, DAG_RUN_ROLE, TEST_LOGIN, ENABLE_SIGNUP, LOG_LEVEL [type=value_error]
```

A variable in the environment that is not a setting is not read and stops nothing: the environment also holds the
variables of other programs. `uvicorn --env-file <file>` puts the whole file into the environment, so this check does
not apply to such a file.

Upgrade note: delete these keys from an existing `.env`, they are not settings and stop the start: `DATABASE_URL`,
`REDIS_*`, `SMTP_*`, `EMAILS_FROM_*`, `STRIPE_*`, `ENABLE_INVITATIONS`, `ENABLE_PAYMENTS`,
`REFRESH_TOKEN_EXPIRE_DAYS`, `DEBUG` and `BACKEND_PORT`. `BACKEND_PORT` is read by docker compose only: set it in
the shell, as in [DOCKER-SETUP.md](DOCKER-SETUP.md#port-conflicts).

A value the backend cannot use stops the start too, for example `RETRIEVAL_URL=opentsx-retrieval:8080`:

```text
RETRIEVAL_URL
  Value error, RETRIEVAL_URL: not an http or https address; set the address of the retrieval server such as http://opentsx-retrieval:8080 [type=value_error]
```

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
opentsx-saas-backend/
├── app/
│   ├── main.py                 # FastAPI application, error answers, logging setup, start and stop
│   ├── logging.conf            # log format and the levels of uvicorn, sqlalchemy and passlib
│   ├── api/
│   │   ├── auth.py             # sign-up, sign-in, account
│   │   ├── dags.py             # DAG deployments through the retrieval server
│   │   ├── flows.py            # stored flows
│   │   └── systems.py          # working copies of signal bindings, demo generators
│   ├── core/
│   │   ├── config.py           # Settings
│   │   ├── security.py         # JWT and password utilities
│   │   └── deps.py             # the signed-in user of a request
│   ├── db/
│   │   ├── base.py             # every model, for create_all
│   │   ├── base_class.py       # Base model
│   │   ├── session.py          # database engine and sessions
│   │   ├── utils.py            # waits for the database at start
│   │   └── init_db.py          # the first administrator
│   ├── models/                 # user.py (users, organizations, teams, invitations), flow.py, system.py
│   ├── schemas/                # user.py, flow.py
│   └── services/
│       └── auth_service.py     # accounts, organizations, sign-in
├── static/index.html           # the page at /
├── tests/                      # pytest
├── requirements.txt
├── requirements-dev.txt
├── .env.example
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

`GET /` answers a page (`static/index.html`) that links `/docs`, the OpenAPI document, `/health` and the web
application at `http://localhost:3000`. It has no sign-in.

### Authentication

| Route | What |
|-------|------|
| `POST /api/v1/auth/register` | registers a user; body `email`, `password`, `full_name`; 403 while `ENABLE_SIGNUP=false` |
| `POST /api/v1/auth/login` | signs in; body `email`, `password`; answers `access_token`, `token_type`, `expires_in` (seconds, `ACCESS_TOKEN_EXPIRE_MINUTES` times 60) and `user` |
| `POST /api/v1/auth/logout` | answers success; the access token stays valid until it expires, so the client discards it |
| `GET /api/v1/auth/me` | the signed-in user |
| `PATCH /api/v1/auth/me` | changes `email`, `full_name`, `bio` or `avatar_url` of the signed-in user |
| `POST /api/v1/auth/change-password` | body `current_password`, `new_password` |

Passwords for register and change-password take 8 characters to 72 bytes in UTF-8, the most bcrypt reads. A longer
one answers 422 with the message `at most 72 bytes` for the field.

There is no refresh token and no refresh route. When the access token expires, sign in again.

`register` takes only an address with a domain that has a dot. The response schemas carry the address as stored, so
the first administrator signs in and reads `GET /api/v1/auth/me` with any `FIRST_SUPERUSER_EMAIL`, such as
`admin@localhost`.

### Sign-up

`ENABLE_SIGNUP=true` (the default) lets everyone who reaches the backend register an account. With
`ENABLE_SIGNUP=false`, `POST /api/v1/auth/register` creates no account and answers 403:

```json
{"error":"sign-up is closed; ask an administrator of this installation for an account","type":"forbidden"}
```

### First administrator

At start the backend creates the tables that do not exist yet (`create_all` of SQLAlchemy; there are no migrations).
When no administrator exists, it creates one with `FIRST_SUPERUSER_EMAIL` and `FIRST_SUPERUSER_PASSWORD` and the
organization `admin-org`, and logs `first administrator created account=<address>`. When an administrator exists,
it creates none and changes no password. If `FIRST_SUPERUSER_EMAIL` then names another address, it logs:

```text
first administrator exists account=<address of the administrator>; FIRST_SUPERUSER_EMAIL=<configured address> is not used
```

When no administrator exists and `FIRST_SUPERUSER_EMAIL` belongs to an account that is not an administrator, the
backend creates none and logs at WARNING:

```text
no administrator exists and FIRST_SUPERUSER_EMAIL=<address> belongs to an account that is not one; no administrator created
```

A column added to a model does not reach an existing table: `create_all` creates missing tables only.

### Test operation

With `TEST_LOGIN=true` the name `admin` with the password `admin` signs in as the first administrator
(the administrator with the lowest id; the backend created it with `FIRST_SUPERUSER_EMAIL`). Without an administrator
it answers 401. `GET /api/v1/auth/mode` tells the login page, which then shows the hint. The stored
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
(`superuser`, the default: administrators only; `user`: every account). With sign-up open (`ENABLE_SIGNUP=true`, the
default), `user` lets everyone who can register deploy and stop.

### Flows

| Route | What |
|-------|------|
| `GET /api/v1/flows` | the flows of the signed-in user, most recently changed first |
| `POST /api/v1/flows` | stores a new flow as draft; body `name`, `definition` and optional fields |
| `GET /api/v1/flows/{flow_id}` | one flow with its definition |
| `PUT /api/v1/flows/{flow_id}` | changes the given fields of a flow |
| `DELETE /api/v1/flows/{flow_id}` | deletes a flow and the execution rows an earlier version stored for it |

The backend stores flows and does not run them. A flow runs when its DAG is deployed through
`POST /api/v1/dags/deploy`.

### Systems

| Route | What |
|-------|------|
| `GET /api/v1/systems/{system}/bindings` | the working copy of the signed-in user for this system |
| `PUT /api/v1/systems/{system}/bindings` | sets the source of one grade of one signal; body `signal` (at most 200 characters), `grade`, `source`, `meaningConfirmed`, `baseHash` |
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
| `401` | `unauthorized` | a sign-in with a wrong address or password; no token, a token that is not valid or has expired, or a token whose account no longer exists; the header `WWW-Authenticate: Bearer` comes along |
| `403` | `forbidden` | `register` while `ENABLE_SIGNUP=false`; an inactive account; a new flow for an organization the account is not a member of; an account that may not deploy, stop or generate under `DAG_RUN_ROLE` |
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

A value longer than its limit is refused without repeating it, for example a `signal` of a binding:

```json
{"error":"signal: String should have at most 200 characters","type":"bad_request"}
```

The texts of the account routes, the token check and the flow routes:

| Status | `error` |
|--------|---------|
| `400` | `current_password: not correct; enter the password you sign in with` |
| `400` | `this account: belongs to no organization; a flow needs one` |
| `401` | `email or password: not correct; check both and sign in again` |
| `401` | `Authorization: no bearer token; sign in and send Authorization: Bearer <access token>` |
| `401` | `token: not valid or expired; sign in again` |
| `401` | `token: the account no longer exists; sign in with an existing account` |
| `403` | `sign-up is closed; ask an administrator of this installation for an account` |
| `403` | `this account: deactivated; ask an administrator to activate it` |
| `403` | `organization_id: <id> is not an organization of this account; leave it out to use the first one` |
| `404` | `flow <id>: not found; use the id of one of your flows` |
| `409` | `email: already registered; sign in or use another address` |
| `422` | `status: Input should be 'draft', 'active', 'paused' or 'archived'` |

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
| `503` | `retrieval server: cannot reach the broker; try again` (the retrieval server answered 503: the broker did not take a deploy, a stop or a generate) |
| `503` | `RETRIEVAL_URL: not set; this installation deploys no DAGs` |
| `503` | `RETRIEVAL_URL: not set; this installation runs no generators` |

The text of an upstream error other than 400 stays in the log of the backend (logger `app.api.dags`, level
WARNING):

```text
dag deploy by user 1 refused by the retrieval server: HTTP 500 error='boom'
```

A 500 writes one line at ERROR (logger `app.main`) with the method, the path and the exception with its causes; the
stack trace follows at `LOG_LEVEL=DEBUG`:

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

The backend creates its tables at start (see [First administrator](#first-administrator)). There are no migrations
and no Alembic.

## Logs

The backend writes one line per event to stdout: time, level, logger and the message with `key=value` fields.
`app/main.py` applies `app/logging.conf` when it is imported, also when uvicorn starts without `--log-config`.
`LOG_LEVEL` sets the level of the root logger, so `LOG_LEVEL=WARNING` drops the start line. `app/logging.conf` keeps
uvicorn at `INFO`, sqlalchemy at `WARNING` and the bcrypt handler of passlib at `ERROR`; `uvicorn --log-level` does
not change them. Stack traces appear only at `LOG_LEVEL=DEBUG`, except for a failed start: uvicorn logs its traceback
at ERROR.

The start of a backend that cannot reach its database (`POSTGRES_SERVER=127.0.0.1`, `POSTGRES_PORT=1`):

```text
2026-11-19T05:23:33+0000 INFO uvicorn.error Started server process [14122]
2026-11-19T05:23:33+0000 INFO uvicorn.error Waiting for application startup.
2026-11-19T05:23:33+0000 INFO app.main starting backend db=127.0.0.1:1/opentsx retrieval_url=unset dag_run_role=superuser dag_run_token=unset signup=true test_login=false cors_origins=http://localhost:3000,http://localhost:8000 log_level=INFO
2026-11-19T05:23:33+0000 INFO app.db.utils waiting for the database db=127.0.0.1:1/opentsx
2026-11-19T05:23:33+0000 WARNING app.db.utils database not ready db=127.0.0.1:1/opentsx attempt=1/30 retry_in=1.0s cause=[Errno 111] Connection refused
```

A stop signal (SIGTERM or SIGINT) during this wait ends the start at once:
`stop requested while waiting for the database db=<host>:<port>/<database> attempts=<n>` at WARNING, then uvicorn
logs the traceback at ERROR, ending in `RuntimeError: database <host>:<port>/<database>: not reachable when the stop
came; start the backend again once the database answers`, then `Application startup failed. Exiting.`; the process
exits with code 3.

The start line names the settings an operator checks first; `DAG_RUN_TOKEN` appears only as `set` or `unset`, and
no password or key appears. With `TEST_LOGIN=true` a WARNING follows:

```text
TEST_LOGIN=true: admin/admin signs in as administrator; do not run this outside a lab network
```

Once the database answers, `Database connection established db=<host>:<port>/<database>` and `backend ready`
follow. A refused sign-in writes one INFO line with the address that was given, never the password; each sign-in
through test operation writes a WARNING:

```text
2026-11-19T05:22:20+0000 INFO app.services.auth_service sign-in refused email='a@example.com'
test login used name='admin' account=<address of the first administrator, or none>
```

## Running Tests

```bash
pip install -r requirements.txt -r requirements-dev.txt
python -m pytest tests
```

`tests/test_dags.py` covers the DAG routes without a database or a network: the user comes from a dependency
override, the retrieval server from an httpx mock transport. `tests/test_accounts.py` runs sign-up and the first
administrator against SQLite in memory with the tables of the backend (fixture `database` in `tests/conftest.py`).
Accounts and stored flows are checked against a running instance with `smoke-flows.sh`.

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

`Dockerfile` in this directory builds an image that runs `uvicorn app.main:app` on port 8000 with
`PYTHONUNBUFFERED=1`, so every log line reaches `docker logs` at once.

The image holds no `.env` (`.dockerignore` leaves it out). `--env-file .env` passes the keys of `.env` as
environment variables. The run below needs Docker Engine on Linux and a PostgreSQL that listens on the host, such as
the one `docker compose up -d postgres` publishes on 127.0.0.1:

```bash
docker build -t opentsx-backend .
docker run --network host --env-file .env opentsx-backend uvicorn app.main:app --host 127.0.0.1 --port 8000
```

`--network host` puts the container on the network of the host, so `POSTGRES_SERVER=localhost` of `.env` reaches
that PostgreSQL. Without it, `localhost` is the container itself: the backend logs
`database not ready db=localhost:5432/opentsx` and stops after 30 attempts. The command replaces the `CMD` of the
image so that uvicorn listens on 127.0.0.1 only; the `CMD` listens on `0.0.0.0`, which with `--network host` is
every interface of the host. The backend answers at http://localhost:8000.

### Docker Compose (Full Stack)

This starts the backend, the frontend and PostgreSQL. Set `SECRET_KEY`, `POSTGRES_PASSWORD` and
`FIRST_SUPERUSER_PASSWORD` in `.env` first (see [DOCKER-SETUP.md](DOCKER-SETUP.md)):

```bash
docker compose up -d --build
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
  │                    Generate JWT token
  │                         │
  │ {access_token,          │
  │  expires_in, user}      │
  │←────────────────────────┤
  │                         │
Store access token          │
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

- **JWT Authentication**: access tokens valid for `ACCESS_TOKEN_EXPIRE_MINUTES` (30 by default); no refresh tokens
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

- **API reference**: the running backend describes every route in `/api/v1/openapi.json`; `/docs` (Swagger UI) and
  `/redoc` show the same document. With the backend on port 8000:

  ```bash
  curl http://localhost:8000/api/v1/openapi.json
  ```

- **Docker Compose**: [DOCKER-SETUP.md](DOCKER-SETUP.md)
- **Local development without Docker**: [LOCAL-DEVELOPMENT.md](LOCAL-DEVELOPMENT.md)

## License

Apache License 2.0
