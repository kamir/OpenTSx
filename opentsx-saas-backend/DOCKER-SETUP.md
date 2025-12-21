# Docker Compose Setup for OpenTSx SaaS Backend

## 🚀 Quick Start

The easiest way to run the entire stack (backend + PostgreSQL + Redis):

```bash
# 1. Navigate to backend directory
cd opentsx-saas-backend

# 2. Create environment file
cp .env.example .env

# 3. (Optional) Edit .env with your configuration
# ⚠️  IMPORTANT: Change SECRET_KEY and passwords in production!
nano .env

# 4. Start all services
docker-compose up -d

# 5. Check status
docker-compose ps

# 6. View logs
docker-compose logs -f backend
```

## Services

The docker-compose.yml starts three services:

### 🐍 Backend (FastAPI)
- **URL**: http://localhost:8000
- **API Docs**: http://localhost:8000/docs
- **Container**: opentsx-backend

### 🗄️ PostgreSQL Database
- **Port**: 5432
- **Database**: opentsx
- **User**: opentsx_user (configurable in .env)
- **Container**: opentsx-postgres

### 🔴 Redis Cache
- **Port**: 6379
- **Container**: opentsx-redis

## Configuration

### Environment Variables (.env)

```bash
# PostgreSQL
POSTGRES_DB=opentsx
POSTGRES_USER=opentsx_user
POSTGRES_PASSWORD=change_this_password
POSTGRES_PORT=5432

# Redis
REDIS_PASSWORD=change_this_redis_password
REDIS_PORT=6379

# Backend
BACKEND_PORT=8000
SECRET_KEY=your-secret-key-change-this-in-production

# First Admin User
FIRST_SUPERUSER_EMAIL=admin@opentsx.com
FIRST_SUPERUSER_PASSWORD=admin123
```

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
docker-compose logs -f redis

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

# Access Redis CLI
docker-compose exec redis redis-cli -a <your-redis-password>

# Run migrations
docker-compose exec backend alembic upgrade head
```

## Health Checks

All services include health checks:

```bash
# Check backend health
curl http://localhost:8000/api/v1/health

# Check PostgreSQL
docker-compose exec postgres pg_isready -U opentsx_user

# Check Redis
docker-compose exec redis redis-cli -a <password> ping
```

## Volumes

Persistent data is stored in Docker volumes:

- `opentsx-postgres-data`: PostgreSQL database files
- `opentsx-redis-data`: Redis persistence files

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

1. **Change all default passwords:**
   ```bash
   # Generate secure passwords
   openssl rand -base64 32  # For POSTGRES_PASSWORD
   openssl rand -base64 32  # For REDIS_PASSWORD
   python -c "import secrets; print(secrets.token_urlsafe(32))"  # For SECRET_KEY
   ```

2. **Update .env:**
   ```bash
   DEBUG=False
   SECRET_KEY=<generated-secret-key>
   POSTGRES_PASSWORD=<strong-password>
   REDIS_PASSWORD=<strong-password>
   FIRST_SUPERUSER_PASSWORD=<strong-password>
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

```bash
# Option 1: Change ports in .env
BACKEND_PORT=8001
POSTGRES_PORT=5433
REDIS_PORT=6380

# Option 2: Stop conflicting services
sudo systemctl stop postgresql
sudo systemctl stop redis
```

### Database Connection Issues

```bash
# Check PostgreSQL is running
docker-compose ps postgres

# Check PostgreSQL logs
docker-compose logs postgres

# Verify connection string
docker-compose exec backend env | grep DATABASE_URL

# Test manual connection
docker-compose exec postgres psql -U opentsx_user -d opentsx -c "SELECT version();"
```

### Redis Connection Issues

```bash
# Check Redis is running
docker-compose ps redis

# Test connection
docker-compose exec redis redis-cli -a <password> ping

# Check Redis logs
docker-compose logs redis
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

### Hot Reload

The backend service mounts the `./app` directory for hot reload:

```yaml
volumes:
  - ./app:/app/app
```

Changes to Python files will automatically reload the server.

### Running Tests

```bash
# Run tests in container
docker-compose exec backend pytest

# Run with coverage
docker-compose exec backend pytest --cov=app tests/
```

### Database Migrations

```bash
# Create migration
docker-compose exec backend alembic revision --autogenerate -m "Add new table"

# Apply migrations
docker-compose exec backend alembic upgrade head

# Rollback
docker-compose exec backend alembic downgrade -1
```

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

# Backup Redis
docker-compose exec -T redis redis-cli -a <password> --rdb /data/dump.rdb SAVE
docker cp opentsx-redis:/data/dump.rdb $BACKUP_DIR/redis_$DATE.rdb

# Compress
tar czf $BACKUP_DIR/backup_$DATE.tar.gz $BACKUP_DIR/*_$DATE.*
rm $BACKUP_DIR/*_$DATE.sql $BACKUP_DIR/*_$DATE.rdb

echo "Backup completed: backup_$DATE.tar.gz"
```

### Restore from Backup

```bash
# Restore PostgreSQL
docker-compose exec -T postgres psql -U opentsx_user opentsx < backup.sql

# Restore Redis
docker cp backup.rdb opentsx-redis:/data/dump.rdb
docker-compose restart redis
```

## Additional Resources

- [Docker Compose Documentation](https://docs.docker.com/compose/)
- [FastAPI Documentation](https://fastapi.tiangolo.com/)
- [PostgreSQL Docker Image](https://hub.docker.com/_/postgres)
- [Redis Docker Image](https://hub.docker.com/_/redis)
