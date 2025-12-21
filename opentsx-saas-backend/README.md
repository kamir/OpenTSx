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
# Install pytest
pip install pytest pytest-asyncio pytest-cov

# Run tests
pytest

# With coverage
pytest --cov=app tests/
```

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
