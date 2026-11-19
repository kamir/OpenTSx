# Docker Compose Setup for OpenTSx SaaS Backend

## 🚀 Quick Start

This runs the backend, PostgreSQL and the frontend with Docker Compose.

```bash
# 1. Navigate to backend directory
cd opentsx-saas-backend

# 2. Create environment file
cp .env.example .env

# 3. Set SECRET_KEY, POSTGRES_PASSWORD and FIRST_SUPERUSER_PASSWORD in .env.
#    Generate each value with:
python3 -c "import secrets; print(secrets.token_urlsafe(32))"
nano .env

# 4. Start all services
docker-compose up -d

# 5. Check status
docker-compose ps

# 6. View logs
docker-compose logs -f backend
```

## Services

The docker-compose.yml starts three services: the backend, PostgreSQL and the frontend. The backend and
PostgreSQL are published on 127.0.0.1 only, so other hosts cannot reach them.

### 🐍 Backend (FastAPI)
- **URL**: http://localhost:8000
- **API Docs**: http://localhost:8000/docs
- **Container**: opentsx-backend

### 🗄️ PostgreSQL Database
- **Port**: 5432 on 127.0.0.1 (`POSTGRES_PORT`)
- **Database**: opentsx
- **User**: opentsx_user (configurable in .env)
- **Container**: opentsx-postgres

### Frontend
- **URL**: http://localhost:3000
- **Container**: opentsx-frontend

## Configuration

### Environment Variables (.env)

docker compose reads `.env` in this directory. These three values have no default:

```bash
SECRET_KEY=<at least 32 random characters>
POSTGRES_PASSWORD=<at least 12 characters>
FIRST_SUPERUSER_PASSWORD=<at least 12 characters, at most 72 bytes>
```

`<...>` stands for the value you generate. The other keys are optional:

```bash
# PostgreSQL
POSTGRES_DB=opentsx
POSTGRES_USER=opentsx_user
POSTGRES_PORT=5432

# First Admin User
FIRST_SUPERUSER_EMAIL=admin@opentsx.com

# Sign-up and logs of the backend
ENABLE_SIGNUP=true
LOG_LEVEL=INFO
```

docker compose publishes the backend on `127.0.0.1:${BACKEND_PORT:-8000}`. Set `BACKEND_PORT` in the shell, not in
`.env`: a backend started outside a container in this directory reads the same `.env` and refuses every key that is
not one of its settings (see [README.md](README.md#environment-variables)). The container does not read `.env`.
docker compose passes the settings listed under `services.backend.environment` in `docker-compose.yml` as environment
variables. A setting that is not listed there, such as `RETRIEVAL_URL` or `TEST_LOGIN`, has no effect in the
container.

Without one of the three required values, every docker compose command stops and names it:

```text
error while interpolating services.backend.environment.SECRET_KEY: required variable SECRET_KEY is missing a value: set SECRET_KEY, see DOCKER-SETUP.md
```

The backend refuses a value that is too short or is an example value published in this repository, and names the
variable in its log (`docker-compose logs backend`).

PostgreSQL takes `POSTGRES_PASSWORD` only when it creates the volume `opentsx-postgres-data`. An existing volume
keeps the password it was created with, so set `POSTGRES_PASSWORD` to that password. A volume created with an
example password such as `change_this_password` has to be deleted with `docker-compose down -v`, which deletes its
data, or get a new password in PostgreSQL, because the backend refuses that value.

The backend creates the tables and the first administrator at start, the administrator only when no administrator
exists (see [README.md](README.md#first-administrator)). `FIRST_SUPERUSER_PASSWORD` does not change the password of an
existing account. To change it, sign in as that account and call `POST /api/v1/auth/change-password`.

## Docker Commands

### Starting Services

```bash
# Start in background
docker-compose up -d

# Start with logs
docker-compose up

# Rebuild and start
docker-compose up -d --build
```

### Viewing Logs

```bash
# All services
docker-compose logs -f

# Specific service
docker-compose logs -f backend
docker-compose logs -f postgres

# Last 100 lines
docker-compose logs --tail=100 backend
```

### Managing Services

```bash
# List running services
docker-compose ps

# Stop services
docker-compose stop

# Start services
docker-compose start

# Restart a service
docker-compose restart backend

# Stop and remove containers
docker-compose down

# Stop and remove containers + volumes (⚠️ deletes data)
docker-compose down -v
```

### Executing Commands

```bash
# Access backend container
docker-compose exec backend bash

# Access PostgreSQL
docker-compose exec postgres psql -U opentsx_user -d opentsx
```

## Health Checks

The backend and PostgreSQL have health checks in `docker-compose.yml`; the frontend has none:

```bash
# Check backend health
curl http://localhost:8000/health

# Check PostgreSQL
docker-compose exec postgres pg_isready -U opentsx_user
```

## Volumes

Persistent data is stored in Docker volumes:

- `opentsx-postgres-data`: PostgreSQL database files

### Managing Volumes

```bash
# List volumes
docker volume ls | grep opentsx

# Inspect volume
docker volume inspect opentsx-postgres-data

# Backup PostgreSQL volume
docker run --rm \
  -v opentsx-postgres-data:/data \
  -v $(pwd):/backup \
  alpine tar czf /backup/postgres-backup.tar.gz /data

# Restore PostgreSQL volume
docker run --rm \
  -v opentsx-postgres-data:/data \
  -v $(pwd):/backup \
  alpine tar xzf /backup/postgres-backup.tar.gz -C /
```

## Production Deployment

### Security Checklist

1. **Generate the three required values**, one run each:
   ```bash
   python3 -c "import secrets; print(secrets.token_urlsafe(32))"
   ```

2. **Put them into .env:**
   ```bash
   SECRET_KEY=<generated value>
   POSTGRES_PASSWORD=<generated value>
   FIRST_SUPERUSER_PASSWORD=<generated value>
   ```

3. **Configure CORS:**
   ```bash
   BACKEND_CORS_ORIGINS=["https://yourdomain.com"]
   ```

### Reverse Proxy (Nginx)

```nginx
server {
    listen 80;
    server_name api.yourdomain.com;

    location / {
        proxy_pass http://localhost:8000;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

### SSL with Let's Encrypt

```bash
# Install certbot
sudo apt install certbot python3-certbot-nginx

# Get certificate
sudo certbot --nginx -d api.yourdomain.com
```

## Troubleshooting

### Port Conflicts

If ports are already in use:

Publish the backend on another port for one command, and set `POSTGRES_PORT=5433` in `.env` for PostgreSQL:

```bash
BACKEND_PORT=8001 docker-compose up -d
```

Or stop the service that holds the port:

```bash
sudo systemctl stop postgresql
```

### Database Connection Issues

```bash
# Check PostgreSQL is running
docker-compose ps postgres

# Check PostgreSQL logs
docker-compose logs postgres

# Verify the connection settings (the password stays hidden)
docker-compose exec backend env | grep -E '^POSTGRES_(SERVER|PORT|USER|DB)='

# Test manual connection
docker-compose exec postgres psql -U opentsx_user -d opentsx -c "SELECT version();"
```

### Backend Won't Start

```bash
# Check logs for errors
docker-compose logs backend

# Rebuild image
docker-compose build --no-cache backend

# Clean start
docker-compose down -v
docker-compose up -d --build
```

### Reset Everything

```bash
# Stop and remove everything
docker-compose down -v --rmi all

# Start fresh
docker-compose up -d --build
```

## Monitoring

### Resource Usage

```bash
# View resource usage
docker stats

# Specific service
docker stats opentsx-backend
```

### Logs Rotation

Add to `docker-compose.yml` for each service:

```yaml
logging:
  driver: "json-file"
  options:
    max-size: "10m"
    max-file: "3"
```

## Development Workflow

### Code Changes

The backend service mounts the `./app` directory:

```yaml
volumes:
  - ./app:/app/app
```

The image runs uvicorn without `--reload`. Restart the service to load a change:

```bash
docker-compose restart backend
```

### Running Tests

The image holds no test tools. Run the tests on the host as [README.md](README.md#running-tests) describes.

### Database Schema

The backend creates missing tables at start with `create_all` of SQLAlchemy. There are no migrations and no Alembic:
a column added to a model does not reach a table that exists already.

## Scaling

### Multiple Backend Instances

```bash
# Scale backend to 3 instances
docker-compose up -d --scale backend=3
```

Note: You'll need a load balancer (Nginx/Traefik) to distribute traffic.

## Backup Strategy

### Automated Backups

```bash
#!/bin/bash
# backup.sh

DATE=$(date +%Y%m%d_%H%M%S)
BACKUP_DIR="./backups"

mkdir -p $BACKUP_DIR

# Backup PostgreSQL
docker-compose exec -T postgres pg_dump -U opentsx_user opentsx > $BACKUP_DIR/postgres_$DATE.sql

# Compress
tar czf $BACKUP_DIR/backup_$DATE.tar.gz $BACKUP_DIR/*_$DATE.*
rm $BACKUP_DIR/*_$DATE.sql

echo "Backup completed: backup_$DATE.tar.gz"
```

### Restore from Backup

```bash
# Restore PostgreSQL
docker-compose exec -T postgres psql -U opentsx_user opentsx < backup.sql
```

## Additional Resources

- [Docker Compose Documentation](https://docs.docker.com/compose/)
- [FastAPI Documentation](https://fastapi.tiangolo.com/)
- [PostgreSQL Docker Image](https://hub.docker.com/_/postgres)
