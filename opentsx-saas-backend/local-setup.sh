#!/bin/bash
set -e

echo "🚀 OpenTSx SaaS Backend - Local Development Setup"
echo "=================================================="

# Colors for output
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m' # No Color

# Check if PostgreSQL is running locally
echo ""
echo "📊 Checking PostgreSQL..."
if command -v psql &> /dev/null; then
    if pg_isready -h localhost -p 5432 &> /dev/null; then
        echo -e "${GREEN}✓ PostgreSQL is running${NC}"
    else
        echo -e "${RED}✗ PostgreSQL is not running${NC}"
        echo -e "${YELLOW}  Start PostgreSQL with: brew services start postgresql (macOS)${NC}"
        echo -e "${YELLOW}  Or: sudo systemctl start postgresql (Linux)${NC}"
        exit 1
    fi
else
    echo -e "${RED}✗ PostgreSQL is not installed${NC}"
    echo -e "${YELLOW}  Install with: brew install postgresql (macOS)${NC}"
    echo -e "${YELLOW}  Or: sudo apt-get install postgresql (Linux)${NC}"
    exit 1
fi

# Check if Redis is running locally (optional)
echo ""
echo "🔴 Checking Redis..."
if command -v redis-cli &> /dev/null; then
    if redis-cli ping &> /dev/null; then
        echo -e "${GREEN}✓ Redis is running${NC}"
    else
        echo -e "${YELLOW}⚠ Redis is not running (optional for basic testing)${NC}"
        echo -e "${YELLOW}  Start Redis with: brew services start redis (macOS)${NC}"
        echo -e "${YELLOW}  Or: sudo systemctl start redis (Linux)${NC}"
    fi
else
    echo -e "${YELLOW}⚠ Redis is not installed (optional for basic testing)${NC}"
fi

# Check Python version
echo ""
echo "🐍 Checking Python..."
if command -v python3 &> /dev/null; then
    PYTHON_VERSION=$(python3 --version | cut -d' ' -f2)
    echo -e "${GREEN}✓ Python $PYTHON_VERSION installed${NC}"

    # Check if version is 3.11 or higher
    REQUIRED_VERSION="3.11"
    if [ "$(printf '%s\n' "$REQUIRED_VERSION" "$PYTHON_VERSION" | sort -V | head -n1)" = "$REQUIRED_VERSION" ]; then
        echo -e "${GREEN}✓ Python version is compatible${NC}"
    else
        echo -e "${YELLOW}⚠ Python 3.11+ recommended, you have $PYTHON_VERSION${NC}"
    fi
else
    echo -e "${RED}✗ Python 3 is not installed${NC}"
    exit 1
fi

# Create virtual environment
echo ""
echo "📦 Setting up Python virtual environment..."
if [ ! -d "venv" ]; then
    python3 -m venv venv
    echo -e "${GREEN}✓ Virtual environment created${NC}"
else
    echo -e "${YELLOW}⚠ Virtual environment already exists${NC}"
fi

# Activate virtual environment
source venv/bin/activate

# Upgrade pip
echo ""
echo "⬆️  Upgrading pip..."
pip install --upgrade pip > /dev/null 2>&1
echo -e "${GREEN}✓ pip upgraded${NC}"

# Install dependencies
echo ""
echo "📚 Installing dependencies..."
pip install -r requirements.txt
echo -e "${GREEN}✓ Dependencies installed${NC}"

# Create local database
echo ""
echo "🗄️  Setting up local database..."
DB_NAME="opentsx_local"
DB_USER="opentsx_dev"
DB_PASSWORD="dev_password_123"

# Check if database exists
if psql -h localhost -U $USER -lqt | cut -d \| -f 1 | grep -qw $DB_NAME; then
    echo -e "${YELLOW}⚠ Database '$DB_NAME' already exists${NC}"
else
    # Create database and user
    psql -h localhost -U $USER postgres <<EOF
CREATE DATABASE $DB_NAME;
CREATE USER $DB_USER WITH PASSWORD '$DB_PASSWORD';
GRANT ALL PRIVILEGES ON DATABASE $DB_NAME TO $DB_USER;
EOF
    echo -e "${GREEN}✓ Database '$DB_NAME' created${NC}"
fi

# Create .env.local file
echo ""
echo "⚙️  Creating .env.local configuration..."
cat > .env.local <<EOF
# Local Development Environment
# =============================

# Application
PROJECT_NAME=OpenTSx SaaS (Local Dev)
API_V1_STR=/api/v1
VERSION=1.0.0
DESCRIPTION=Visual Time Series Analysis Platform

# CORS - Allow localhost origins
BACKEND_CORS_ORIGINS=["http://localhost:3000","http://localhost:8000","http://localhost:8080"]

# Security
SECRET_KEY=$(python3 -c "import secrets; print(secrets.token_urlsafe(32))")
ALGORITHM=HS256
ACCESS_TOKEN_EXPIRE_MINUTES=30
REFRESH_TOKEN_EXPIRE_DAYS=7

# Database - Local PostgreSQL
POSTGRES_SERVER=localhost
POSTGRES_USER=$DB_USER
POSTGRES_PASSWORD=$DB_PASSWORD
POSTGRES_DB=$DB_NAME
POSTGRES_PORT=5432

# Redis - Local Redis (optional)
REDIS_HOST=localhost
REDIS_PORT=6379
REDIS_DB=0

# Email (stub for local development)
SMTP_TLS=True
SMTP_PORT=587
SMTP_HOST=smtp.gmail.com
SMTP_USER=noreply@opentsx.com
SMTP_PASSWORD=stub
EMAILS_FROM_EMAIL=noreply@opentsx.com
EMAILS_FROM_NAME=OpenTSx

# Stripe (stub for local development)
STRIPE_SECRET_KEY=sk_test_stub_local
STRIPE_WEBHOOK_SECRET=whsec_stub_local

# First Superuser
FIRST_SUPERUSER_EMAIL=admin@localhost
FIRST_SUPERUSER_PASSWORD=admin123

# Features
ENABLE_SIGNUP=True
ENABLE_INVITATIONS=True
ENABLE_PAYMENTS=False
EOF

echo -e "${GREEN}✓ .env.local created${NC}"

echo ""
echo "========================================="
echo -e "${GREEN}✅ Setup Complete!${NC}"
echo "========================================="
echo ""
echo "Next steps:"
echo "  1. Activate the virtual environment:"
echo "     source venv/bin/activate"
echo ""
echo "  2. Run the development server:"
echo "     ./local-run.sh"
echo ""
echo "  Or manually run:"
echo "     uvicorn app.main:app --reload --env-file .env.local"
echo ""
echo "Database credentials:"
echo "  Database: $DB_NAME"
echo "  User: $DB_USER"
echo "  Password: $DB_PASSWORD"
echo "  Host: localhost:5432"
echo ""
