# Local Development Guide

This guide helps you run the OpenTSx SaaS backend **locally on your machine** without Docker, which is useful for rapid development and debugging.

## Prerequisites

Before starting, ensure you have:

- **Python 3.11+** installed
- **PostgreSQL** installed and running locally
- **Redis** installed and running (optional, but recommended)
- **Git** for version control

### Installing Prerequisites

#### macOS (using Homebrew)

```bash
# Install PostgreSQL
brew install postgresql@15
brew services start postgresql

# Install Redis
brew install redis
brew services start redis

# Install Python 3.11+
brew install python@3.11
```

#### Linux (Ubuntu/Debian)

```bash
# Install PostgreSQL
sudo apt-get update
sudo apt-get install postgresql postgresql-contrib
sudo systemctl start postgresql

# Install Redis
sudo apt-get install redis-server
sudo systemctl start redis

# Install Python 3.11+
sudo apt-get install python3.11 python3.11-venv python3.11-dev
```

## Quick Start

### 1. Initial Setup

Run the setup script to configure your local environment:

```bash
cd opentsx-saas-backend
./local-setup.sh
```

This script will:
- ✅ Check if PostgreSQL and Redis are running
- ✅ Create a Python virtual environment
- ✅ Install all Python dependencies
- ✅ Create a local database (`opentsx_local`)
- ✅ Generate a `.env.local` configuration file with secure secrets

**Database Credentials Created:**
- Database: `opentsx_local`
- User: `opentsx_dev`
- Password: `dev_password_123`
- Host: `localhost:5432`

### 2. Run the Development Server

```bash
./local-run.sh
```

This starts the FastAPI server with hot-reload enabled. The server will automatically restart when you change code files.

**Available at:**
- 📍 API Docs: http://localhost:8000/docs
- 📍 Health Check: http://localhost:8000/health
- 📍 API Endpoints: http://localhost:8000/api/v1

### 3. Test Your Setup

In a **new terminal**, run the test script:

```bash
./local-test.sh
```

This will verify that all API endpoints are working correctly.

## Manual Setup (Alternative)

If you prefer to set up manually:

### 1. Create Virtual Environment

```bash
python3 -m venv venv
source venv/bin/activate
pip install -r requirements.txt
```

### 2. Create Local Database

```bash
# Connect to PostgreSQL
psql -U postgres

# In PostgreSQL shell:
CREATE DATABASE opentsx_local;
CREATE USER opentsx_dev WITH PASSWORD 'dev_password_123';
GRANT ALL PRIVILEGES ON DATABASE opentsx_local TO opentsx_dev;
\q
```

### 3. Create .env.local File

```bash
cp .env.example .env.local
```

Edit `.env.local` and set:

```env
POSTGRES_SERVER=localhost
POSTGRES_USER=opentsx_dev
POSTGRES_PASSWORD=dev_password_123
POSTGRES_DB=opentsx_local
POSTGRES_PORT=5432

REDIS_HOST=localhost
REDIS_PORT=6379
REDIS_DB=0

SECRET_KEY=<generate with: python -c "import secrets; print(secrets.token_urlsafe(32))">
```

### 4. Run the Server

```bash
source venv/bin/activate
uvicorn app.main:app --reload --env-file .env.local
```

## Development Workflow

### Typical Development Session

```bash
# Terminal 1: Start the server
./local-run.sh

# Terminal 2: Run tests or interact with API
curl http://localhost:8000/health
curl http://localhost:8000/api/v1/flows

# Or run automated tests
./local-test.sh
```

### Hot Reload

The development server uses `--reload` which automatically restarts when you modify Python files. Just save your changes and the server will reload.

### Database Migrations

When you modify database models:

```bash
source venv/bin/activate

# The server automatically creates tables on startup
# Just restart the server with Ctrl+C and ./local-run.sh
```

### Debugging

To enable detailed debugging:

1. Edit `.env.local` and add:
   ```env
   LOG_LEVEL=debug
   ```

2. Or run with explicit log level:
   ```bash
   uvicorn app.main:app --reload --log-level debug --env-file .env.local
   ```

## Troubleshooting

### PostgreSQL Connection Error

**Error:** `connection refused` or `could not connect to server`

**Solution:**
```bash
# Check if PostgreSQL is running
pg_isready

# Start PostgreSQL
# macOS:
brew services start postgresql

# Linux:
sudo systemctl start postgresql
```

### Redis Connection Error

**Error:** `Error connecting to Redis`

**Solution:**
```bash
# Check if Redis is running
redis-cli ping

# Start Redis
# macOS:
brew services start redis

# Linux:
sudo systemctl start redis
```

### Port Already in Use

**Error:** `Address already in use: 8000`

**Solution:**
```bash
# Find process using port 8000
lsof -ti:8000

# Kill the process
kill -9 $(lsof -ti:8000)

# Or run on a different port
uvicorn app.main:app --reload --port 8001 --env-file .env.local
```

### Database Already Exists

**Error:** `database "opentsx_local" already exists`

This is normal if you've run the setup before. The script will skip database creation.

To reset the database:

```bash
psql -U postgres -c "DROP DATABASE opentsx_local;"
./local-setup.sh
```

### Python Version Issues

**Error:** `Python version must be 3.11 or higher`

**Solution:**
```bash
# Install Python 3.11+
# macOS:
brew install python@3.11

# Linux:
sudo apt-get install python3.11

# Use specific Python version
python3.11 -m venv venv
```

## API Testing

### Using the Interactive Docs

1. Open http://localhost:8000/docs in your browser
2. Click "Try it out" on any endpoint
3. Fill in parameters and click "Execute"
4. View the response

### Using curl

```bash
# Health check
curl http://localhost:8000/health

# List organizations
curl http://localhost:8000/api/v1/organizations

# List flows
curl http://localhost:8000/api/v1/flows

# Get demo flows
curl http://localhost:8000/api/v1/flows/demo

# Get node types
curl http://localhost:8000/api/v1/node-types
```

### Using Python

```python
import requests

# Health check
response = requests.get("http://localhost:8000/health")
print(response.json())

# List flows
response = requests.get("http://localhost:8000/api/v1/flows")
flows = response.json()
print(f"Found {len(flows['flows'])} flows")
```

## Comparing with Docker Setup

| Feature | Local Development | Docker Compose |
|---------|------------------|----------------|
| **Setup Time** | ~5 minutes | ~10 minutes (first build) |
| **Hot Reload** | ✅ Instant | ✅ Via volume mount |
| **Database** | Local PostgreSQL | Containerized |
| **Isolation** | Shares host resources | Fully isolated |
| **Debugging** | ✅ Direct access | Requires docker exec |
| **Best For** | Active development | Testing, deployment |

## Environment Variables Reference

### Application Settings

- `PROJECT_NAME` - Display name for the application
- `API_V1_STR` - API version prefix (default: `/api/v1`)
- `VERSION` - Application version

### Database Settings

- `POSTGRES_SERVER` - PostgreSQL host (use `localhost` for local, `postgres` for Docker)
- `POSTGRES_USER` - Database username
- `POSTGRES_PASSWORD` - Database password
- `POSTGRES_DB` - Database name
- `POSTGRES_PORT` - Database port (default: 5432)

### Redis Settings

- `REDIS_HOST` - Redis host (use `localhost` for local, `redis` for Docker)
- `REDIS_PORT` - Redis port (default: 6379)
- `REDIS_DB` - Redis database number (default: 0)

### Security Settings

- `SECRET_KEY` - JWT signing key (generate with `python -c "import secrets; print(secrets.token_urlsafe(32))"`)
- `ALGORITHM` - JWT algorithm (default: HS256)
- `ACCESS_TOKEN_EXPIRE_MINUTES` - Token expiration time

## Next Steps

- [ ] Explore the API documentation at http://localhost:8000/docs
- [ ] Review the database schema in `app/models/`
- [ ] Test API endpoints with the interactive docs
- [ ] Start building your frontend application
- [ ] Read the main [README.md](README.md) for deployment instructions

## Getting Help

If you encounter issues:

1. Check the troubleshooting section above
2. Review server logs in the terminal
3. Test individual components (PostgreSQL, Redis, Python)
4. Create an issue in the GitHub repository

Happy coding! 🚀
