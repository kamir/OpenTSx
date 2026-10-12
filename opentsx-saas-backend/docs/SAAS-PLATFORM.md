# OpenTSx SaaS Platform Documentation

**Complete guide to the OpenTSx SaaS platform with JWT authentication and React frontend**

Version: 1.0.0 | Last Updated: January 2025

---

## Table of Contents

1. [Overview](#overview)
2. [Architecture](#architecture)
3. [Prerequisites](#prerequisites)
4. [Backend Setup](#backend-setup)
5. [Frontend Setup](#frontend-setup)
6. [Database Setup](#database-setup)
7. [Authentication System](#authentication-system)
8. [API Reference](#api-reference)
9. [Development Workflow](#development-workflow)
10. [Deployment](#deployment)
11. [Security](#security)
12. [Troubleshooting](#troubleshooting)

---

## Overview

The OpenTSx SaaS Platform is a complete web-based application for visual time series analysis with:

- **JWT-based authentication** with access and refresh tokens
- **Password hashing** using bcrypt for secure credential storage
- **React frontend** with visual flow builder
- **FastAPI backend** with async SQLAlchemy
- **PostgreSQL database** for persistent storage
- **Multi-tenant architecture** with organizations and teams

### Key Features

✅ **Secure Authentication**
- JWT tokens (access: 30min, refresh: 7 days)
- Password hashing with bcrypt
- Email verification support
- Password change functionality

✅ **Multi-Tenant SaaS**
- Organizations with multiple users
- Team-based collaboration
- Role-based access control (owner, admin, member)
- Subscription plans (Free, Starter, Professional, Enterprise)

✅ **Visual Flow Builder**
- Drag-and-drop interface
- Real-time data visualization
- Flow execution and monitoring
- Export/import pipelines

---

## Architecture

### System Components

```
┌─────────────────────────────────────────────────────────────┐
│                     Frontend (React)                         │
│                                                               │
│  • React 18 + TypeScript                                     │
│  • Zustand (state management)                                │
│  • Axios (HTTP client)                                       │
│  • React Router (navigation)                                 │
│  • React Flow (visual builder)                               │
│  • Tailwind CSS (styling)                                    │
│                                                               │
│  Pages:                                                       │
│  - Login / Register                                           │
│  - Dashboard                                                  │
│  - Flow Builder                                               │
└────────────────────┬──────────────────────────────────────────┘
                     │ HTTP/JSON
                     ↓
┌─────────────────────────────────────────────────────────────┐
│                   Backend (FastAPI)                          │
│                                                               │
│  • Python 3.11+ with FastAPI                                 │
│  • SQLAlchemy 2.0 (async ORM)                                │
│  • AsyncPG (PostgreSQL driver)                               │
│  • python-jose (JWT)                                         │
│  • passlib (password hashing)                                │
│  • Pydantic v2 (validation)                                  │
│                                                               │
│  Modules:                                                     │
│  - app/api/auth.py (authentication)                          │
│  - app/api/flows.py (flow management)                        │
│  - app/services/ (business logic)                            │
│  - app/core/security.py (JWT & passwords)                    │
│  - app/core/deps.py (dependencies)                           │
└────────────────────┬──────────────────────────────────────────┘
                     │ SQL
                     ↓
┌─────────────────────────────────────────────────────────────┐
│                 Database (PostgreSQL)                        │
│                                                               │
│  Tables:                                                      │
│  - users (authentication & profiles)                         │
│  - organizations (multi-tenant)                              │
│  - organization_members (user-org relationships)             │
│  - teams (collaboration groups)                              │
│  - team_members (user-team relationships)                    │
│  - flows (pipeline definitions)                              │
│  - flow_executions (execution history)                       │
│  - invitations (team invites)                                │
│  - subscriptions (billing)                                   │
└─────────────────────────────────────────────────────────────┘
```

### Technology Stack

| Layer | Technology | Purpose |
|-------|------------|---------|
| **Frontend** | React 18 + TypeScript | UI framework |
| | Zustand | State management |
| | React Router | Navigation |
| | Axios | HTTP client |
| | React Flow | Visual flow builder |
| | Tailwind CSS | Styling |
| **Backend** | FastAPI | Web framework |
| | SQLAlchemy 2.0 | ORM |
| | AsyncPG | PostgreSQL driver |
| | python-jose | JWT handling |
| | passlib | Password hashing |
| | Pydantic v2 | Validation |
| **Database** | PostgreSQL 15+ | Primary datastore |
| **Deployment** | Docker + Docker Compose | Containerization |

---

## Prerequisites

### Software Requirements

- **Python**: 3.11 or higher
- **Node.js**: 18 or higher
- **PostgreSQL**: 15 or higher
- **Docker** (optional): 20.10+ for containerized deployment
- **Git**: For version control

### System Requirements

**Development Environment:**
- CPU: 2+ cores
- RAM: 4 GB minimum
- Disk: 5 GB available space

**Production Environment:**
- CPU: 4+ cores
- RAM: 8+ GB
- Disk: 20+ GB SSD

---

## Backend Setup

### 1. Directory Structure

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
│   │   ├── user.py             # User schemas
│   │   └── flow.py             # Flow schemas
│   └── services/
│       ├── __init__.py
│       └── auth_service.py     # Auth business logic
├── requirements.txt
└── .env
```

### 2. Install Dependencies

```bash
cd backend

# Create virtual environment
python3 -m venv venv
source venv/bin/activate  # On Windows: venv\Scripts\activate

# Install dependencies
pip install -r requirements.txt
```

**requirements.txt:**
```txt
fastapi==0.109.0
uvicorn[standard]==0.27.0
sqlalchemy==2.0.25
asyncpg==0.29.0
python-jose[cryptography]==3.3.0
passlib[bcrypt]==1.7.4
python-multipart==0.0.6
pydantic==2.5.3
pydantic-settings==2.1.0
alembic==1.13.1
```

### 3. Environment Configuration

Create `.env` file in `backend/` directory:

```bash
# Application
PROJECT_NAME=OpenTSx SaaS Platform
API_V1_STR=/api/v1
DEBUG=True

# Security
SECRET_KEY=your-secret-key-here-change-in-production
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

**⚠️ Security Warning:**
- **Change `SECRET_KEY`** in production (use: `openssl rand -hex 32`)
- **Change default superuser password** immediately
- **Never commit `.env` file** to version control

### 4. Generate Secret Key

```bash
# Generate a secure secret key
python -c "import secrets; print(secrets.token_urlsafe(32))"
```

### 5. Run Backend Server

```bash
# Development mode with auto-reload
uvicorn app.main:app --reload --host 0.0.0.0 --port 8000

# Production mode
uvicorn app.main:app --host 0.0.0.0 --port 8000 --workers 4
```

Backend will be available at: **http://localhost:8000**

API Documentation: **http://localhost:8000/docs** (Swagger UI)

---

## Frontend Setup

### 1. Directory Structure

```
frontend/
├── public/
├── src/
│   ├── App.tsx                 # Main app component
│   ├── main.tsx                # Entry point
│   ├── index.css               # Global styles
│   ├── pages/
│   │   ├── Login.tsx           # Login page
│   │   ├── Register.tsx        # Registration page
│   │   ├── Dashboard.tsx       # Dashboard
│   │   └── FlowBuilder.tsx     # Visual flow builder
│   ├── services/
│   │   └── api.ts              # API client
│   └── store/
│       └── authStore.ts        # Auth state management
├── index.html
├── package.json
├── tsconfig.json
├── vite.config.ts
└── tailwind.config.js
```

### 2. Install Dependencies

```bash
cd frontend

# Install dependencies
npm install

# Or with yarn
yarn install
```

**package.json dependencies:**
```json
{
  "dependencies": {
    "react": "^18.2.0",
    "react-dom": "^18.2.0",
    "react-router-dom": "^6.21.0",
    "reactflow": "^11.10.4",
    "plotly.js": "^2.28.0",
    "react-plotly.js": "^2.6.0",
    "zustand": "^4.4.7",
    "axios": "^1.6.5",
    "socket.io-client": "^4.6.1",
    "@tanstack/react-query": "^5.17.9"
  },
  "devDependencies": {
    "@types/react": "^18.2.47",
    "@types/react-dom": "^18.2.18",
    "@vitejs/plugin-react": "^4.2.1",
    "typescript": "^5.3.3",
    "vite": "^5.0.11",
    "tailwindcss": "^3.4.1",
    "autoprefixer": "^10.4.16",
    "postcss": "^8.4.33"
  }
}
```

### 3. Environment Configuration

Create `.env` file in `frontend/` directory:

```bash
VITE_API_BASE_URL=http://localhost:8000
```

### 4. Run Frontend Development Server

```bash
# Development mode with hot reload
npm run dev

# Or with yarn
yarn dev
```

Frontend will be available at: **http://localhost:3000**

### 5. Build for Production

```bash
# Build optimized production bundle
npm run build

# Preview production build
npm run preview
```

---

## Database Setup

### 1. Install PostgreSQL

**Ubuntu/Debian:**
```bash
sudo apt update
sudo apt install postgresql postgresql-contrib
sudo systemctl start postgresql
```

**macOS (via Homebrew):**
```bash
brew install postgresql@15
brew services start postgresql@15
```

**Windows:**
Download and install from: https://www.postgresql.org/download/windows/

### 2. Create Database

```bash
# Connect to PostgreSQL
sudo -u postgres psql

# Create database and user
CREATE DATABASE opentsx;
CREATE USER opentsx_user WITH ENCRYPTED PASSWORD 'your_password';
GRANT ALL PRIVILEGES ON DATABASE opentsx TO opentsx_user;

# Exit
\q
```

### 3. Database Schema

The schema is automatically created on first run via SQLAlchemy models. Tables include:

#### Users Table
```sql
CREATE TABLE users (
    id SERIAL PRIMARY KEY,
    email VARCHAR(255) UNIQUE NOT NULL,
    hashed_password VARCHAR(255) NOT NULL,
    full_name VARCHAR(255),
    bio TEXT,
    avatar_url VARCHAR(500),
    is_verified BOOLEAN DEFAULT FALSE,
    is_active BOOLEAN DEFAULT TRUE,
    is_superuser BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    last_login_at TIMESTAMP
);
```

#### Organizations Table
```sql
CREATE TABLE organizations (
    id SERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    slug VARCHAR(255) UNIQUE NOT NULL,
    description TEXT,
    logo_url VARCHAR(500),
    owner_id INTEGER REFERENCES users(id),
    plan_tier VARCHAR(50) DEFAULT 'free',
    max_flows INTEGER DEFAULT 5,
    max_executions_per_month INTEGER DEFAULT 1000,
    max_team_members INTEGER DEFAULT 3,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
```

#### Flows Table
```sql
CREATE TABLE flows (
    id SERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    definition JSONB NOT NULL,
    owner_id INTEGER REFERENCES users(id),
    organization_id INTEGER REFERENCES organizations(id),
    team_id INTEGER REFERENCES teams(id),
    status VARCHAR(50) DEFAULT 'draft',
    is_public BOOLEAN DEFAULT FALSE,
    tags TEXT[],
    category VARCHAR(100),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
```

### 4. Database Migrations (Optional)

For production, use Alembic for database migrations:

```bash
# Install Alembic
pip install alembic

# Initialize Alembic
alembic init alembic

# Create migration
alembic revision --autogenerate -m "Initial schema"

# Apply migration
alembic upgrade head
```

---

## Authentication System

### JWT Token Architecture

#### Token Types

**Access Token (30 minutes):**
```json
{
  "sub": "1",
  "exp": 1705234567,
  "type": "access"
}
```

**Refresh Token (7 days):**
```json
{
  "sub": "1",
  "exp": 1705838367,
  "type": "refresh"
}
```

#### Token Storage

- **Frontend**: Stored in `localStorage`
  - `access_token`: Used for API requests
  - `refresh_token`: Used to get new access tokens

- **Backend**: Stateless (no token storage)
  - Tokens validated via signature verification

### Authentication Flow

```
┌──────────┐                    ┌──────────┐
│  Client  │                    │  Server  │
└────┬─────┘                    └────┬─────┘
     │                               │
     │ POST /api/v1/auth/login       │
     │ {email, password}             │
     ├──────────────────────────────→│
     │                               │
     │                      Verify credentials
     │                      Hash password check
     │                               │
     │ {access_token, refresh_token} │
     │←──────────────────────────────┤
     │                               │
Store tokens                         │
     │                               │
     │ GET /api/v1/flows             │
     │ Authorization: Bearer <token> │
     ├──────────────────────────────→│
     │                               │
     │                      Validate JWT
     │                      Decode token
     │                      Get user from DB
     │                               │
     │ { flows: [...] }              │
     │←──────────────────────────────┤
     │                               │
```

### Password Security

**Hashing Algorithm: bcrypt**

```python
from passlib.context import CryptContext

pwd_context = CryptContext(schemes=["bcrypt"], deprecated="auto")

# Hash password
hashed = pwd_context.hash("plain_password")

# Verify password
is_valid = pwd_context.verify("plain_password", hashed)
```

**Password Requirements:**
- Minimum 8 characters
- (Optional) Complexity rules can be added

### Protected Routes

**Frontend (React Router):**
```typescript
function ProtectedRoute({ children }) {
  const { isAuthenticated } = useAuthStore();

  if (!isAuthenticated) {
    return <Navigate to="/login" />;
  }

  return <>{children}</>;
}
```

**Backend (FastAPI Dependencies):**
```python
async def get_current_user(
    credentials: HTTPAuthorizationCredentials = Depends(security),
    db: AsyncSession = Depends(get_db)
) -> User:
    token = credentials.credentials
    payload = decode_token(token)
    user = await db.get(User, payload["sub"])
    return user
```

---

## API Reference

See **[docs/API-DOCUMENTATION.md](docs/API-DOCUMENTATION.md)** for complete API reference.

### Quick Reference

#### Authentication Endpoints

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/v1/auth/register` | Register new user |
| POST | `/api/v1/auth/login` | Login and get tokens |
| POST | `/api/v1/auth/refresh` | Refresh access token |
| POST | `/api/v1/auth/logout` | Logout |
| GET | `/api/v1/auth/me` | Get current user |

#### Flow Endpoints

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/v1/flows` | List flows |
| POST | `/api/v1/flows` | Create flow |
| GET | `/api/v1/flows/{id}` | Get flow details |
| PUT | `/api/v1/flows/{id}` | Update flow |
| DELETE | `/api/v1/flows/{id}` | Delete flow |
| POST | `/api/v1/flows/{id}/execute` | Execute flow |

### Example API Calls

**Register User:**
```bash
curl -X POST http://localhost:8000/api/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "email": "user@example.com",
    "password": "SecurePass123!",
    "full_name": "John Doe"
  }'
```

**Login:**
```bash
curl -X POST http://localhost:8000/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{
    "email": "admin@opentsx.com",
    "password": "admin123"
  }'
```

**Get Flows:**
```bash
curl -X GET http://localhost:8000/api/v1/flows \
  -H "Authorization: Bearer <access_token>"
```

---

## Development Workflow

### Starting Development

```bash
# Terminal 1: Start PostgreSQL
sudo systemctl start postgresql

# Terminal 2: Start Backend
cd backend
source venv/bin/activate
uvicorn app.main:app --reload

# Terminal 3: Start Frontend
cd frontend
npm run dev
```

### Code Style

**Backend (Python):**
- Use **Black** for formatting
- Use **Flake8** for linting
- Use **mypy** for type checking

```bash
# Format code
black app/

# Lint code
flake8 app/

# Type check
mypy app/
```

**Frontend (TypeScript):**
- Use **Prettier** for formatting
- Use **ESLint** for linting

```bash
# Format code
npx prettier --write src/

# Lint code
npx eslint src/
```

### Testing

**Backend Tests:**
```bash
# Install pytest
pip install pytest pytest-asyncio

# Run tests
pytest tests/
```

**Frontend Tests:**
```bash
# Install testing libraries
npm install -D @testing-library/react @testing-library/jest-dom

# Run tests
npm test
```

### Git Workflow

```bash
# Create feature branch
git checkout -b feature/user-profile

# Make changes and commit
git add .
git commit -m "Add user profile page"

# Push to remote
git push origin feature/user-profile

# Create pull request on GitHub
```

---

## Deployment

### Docker Deployment

**1. Create docker-compose.yml:**

```yaml
version: '3.8'

services:
  postgres:
    image: postgres:15-alpine
    environment:
      POSTGRES_USER: opentsx
      POSTGRES_PASSWORD: ${DB_PASSWORD}
      POSTGRES_DB: opentsx
    volumes:
      - postgres_data:/var/lib/postgresql/data
    ports:
      - "5432:5432"

  backend:
    build: ./backend
    command: uvicorn app.main:app --host 0.0.0.0 --port 8000
    environment:
      DATABASE_URL: postgresql+asyncpg://opentsx:${DB_PASSWORD}@postgres:5432/opentsx
      SECRET_KEY: ${SECRET_KEY}
    ports:
      - "8000:8000"
    depends_on:
      - postgres

  frontend:
    build: ./frontend
    ports:
      - "3000:3000"
    environment:
      VITE_API_BASE_URL: http://localhost:8000
    depends_on:
      - backend

volumes:
  postgres_data:
```

**2. Create Dockerfiles:**

**backend/Dockerfile:**
```dockerfile
FROM python:3.11-slim

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY . .

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
```

**frontend/Dockerfile:**
```dockerfile
FROM node:18-alpine

WORKDIR /app

COPY package*.json ./
RUN npm install

COPY . .

CMD ["npm", "run", "dev", "--", "--host"]
```

**3. Deploy:**

```bash
# Build and start services
docker-compose up -d

# View logs
docker-compose logs -f

# Stop services
docker-compose down
```

### Production Deployment

**Recommended Stack:**
- **Backend**: Gunicorn + Uvicorn workers
- **Frontend**: Nginx serving static build
- **Database**: Managed PostgreSQL (AWS RDS, GCP Cloud SQL)
- **SSL**: Let's Encrypt via Certbot
- **Reverse Proxy**: Nginx

**Production Backend:**
```bash
# Install gunicorn
pip install gunicorn

# Run with gunicorn
gunicorn app.main:app \
  --workers 4 \
  --worker-class uvicorn.workers.UvicornWorker \
  --bind 0.0.0.0:8000
```

**Production Frontend:**
```bash
# Build
npm run build

# Serve with nginx
# Copy dist/ to /var/www/html/
```

---

## Security

### Best Practices

✅ **Environment Variables**
- Never commit `.env` files
- Use strong `SECRET_KEY` (32+ random characters)
- Rotate secrets regularly

✅ **Password Security**
- Bcrypt hashing with salt
- Minimum 8 characters
- Consider password strength requirements

✅ **JWT Security**
- Short expiration for access tokens (30min)
- Longer expiration for refresh tokens (7 days)
- HTTPS only in production

✅ **Database Security**
- Use connection pooling
- Limit database user permissions
- Regular backups

✅ **API Security**
- Rate limiting (future enhancement)
- Input validation (Pydantic)
- CORS configuration
- SQL injection prevention (SQLAlchemy ORM)

### Security Checklist

- [ ] Change default superuser password
- [ ] Generate strong `SECRET_KEY`
- [ ] Enable HTTPS in production
- [ ] Configure CORS properly
- [ ] Implement rate limiting
- [ ] Set up database backups
- [ ] Enable audit logging
- [ ] Regular security updates

---

## Troubleshooting

### Common Issues

#### Backend Won't Start

**Issue:** `ModuleNotFoundError: No module named 'app'`

**Solution:**
```bash
# Ensure you're in the backend directory
cd backend

# Activate virtual environment
source venv/bin/activate

# Install dependencies
pip install -r requirements.txt
```

#### Database Connection Error

**Issue:** `Connection refused` or `database "opentsx" does not exist`

**Solution:**
```bash
# Check PostgreSQL is running
sudo systemctl status postgresql

# Create database
sudo -u postgres psql
CREATE DATABASE opentsx;
\q
```

#### JWT Token Invalid

**Issue:** `401 Unauthorized - Could not validate credentials`

**Solution:**
- Check `SECRET_KEY` matches between token creation and validation
- Ensure token hasn't expired
- Verify `Authorization: Bearer <token>` header format

#### Frontend Can't Connect to Backend

**Issue:** CORS errors or network errors

**Solution:**
1. Check backend is running on port 8000
2. Verify `VITE_API_BASE_URL` in frontend `.env`
3. Check CORS configuration in backend `main.py`
4. Disable browser extensions that block requests

#### Password Hash Verification Fails

**Issue:** Login fails even with correct password

**Solution:**
- Ensure `passlib[bcrypt]` is installed
- Check password hash in database is valid
- Verify bcrypt configuration

### Debug Mode

**Backend:**
```python
# In app/main.py
app = FastAPI(debug=True)

# Or set in .env
DEBUG=True
```

**Frontend:**
```typescript
// Check API calls in browser console
console.log('API Response:', response);
```

### Logging

**Backend Logging:**
```python
import logging

logging.basicConfig(level=logging.DEBUG)
logger = logging.getLogger(__name__)

logger.debug("Debug message")
logger.info("Info message")
logger.error("Error message")
```

**Frontend Logging:**
```typescript
// Browser console
console.log('Debug:', data);
console.error('Error:', error);
```

---

## Additional Resources

### Documentation

- **API Reference**: [docs/API-DOCUMENTATION.md](docs/API-DOCUMENTATION.md)
- **Visual Flow Builder**: [WEB-UI-VISUAL-FLOW-BUILDER.md](WEB-UI-VISUAL-FLOW-BUILDER.md)
- **Architecture**: [ARCHITECTURE.md](ARCHITECTURE.md)
- **Security**: [SECURITY.md](SECURITY.md)

### External Links

- **FastAPI**: https://fastapi.tiangolo.com/
- **React**: https://react.dev/
- **SQLAlchemy**: https://docs.sqlalchemy.org/
- **Zustand**: https://github.com/pmndrs/zustand
- **React Flow**: https://reactflow.dev/
- **PostgreSQL**: https://www.postgresql.org/docs/

---

## Support

For issues and questions:

- **GitHub Issues**: https://github.com/kamir/OpenTSx/issues
- **Email**: support@opentsx.com
- **Documentation**: https://docs.opentsx.com

---

## License

Apache License 2.0

Copyright (c) 2013-2025 Mirko Kämpf and contributors

---

**Version**: 1.0.0
**Last Updated**: January 2025
**Contributors**: OpenTSx Team
