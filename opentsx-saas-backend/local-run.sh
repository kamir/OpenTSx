#!/bin/bash

# Colors for output
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m' # No Color

echo "🚀 Starting OpenTSx SaaS Backend (Local Development)"
echo "===================================================="

# Check if .env.local exists
if [ ! -f ".env.local" ]; then
    echo -e "${RED}✗ .env.local not found${NC}"
    echo -e "${YELLOW}  Run ./local-setup.sh first${NC}"
    exit 1
fi

# Check if virtual environment exists
if [ ! -d "venv" ]; then
    echo -e "${RED}✗ Virtual environment not found${NC}"
    echo -e "${YELLOW}  Run ./local-setup.sh first${NC}"
    exit 1
fi

# Activate virtual environment
source venv/bin/activate

# Check if PostgreSQL is running
echo ""
echo "📊 Checking PostgreSQL..."
if pg_isready -h localhost -p 5432 &> /dev/null; then
    echo -e "${GREEN}✓ PostgreSQL is running${NC}"
else
    echo -e "${RED}✗ PostgreSQL is not running${NC}"
    echo -e "${YELLOW}  Start with: brew services start postgresql (macOS)${NC}"
    echo -e "${YELLOW}  Or: sudo systemctl start postgresql (Linux)${NC}"
    exit 1
fi

# Check if Redis is running (optional warning)
echo ""
echo "🔴 Checking Redis..."
if command -v redis-cli &> /dev/null && redis-cli ping &> /dev/null; then
    echo -e "${GREEN}✓ Redis is running${NC}"
else
    echo -e "${YELLOW}⚠ Redis is not running (some features may be limited)${NC}"
fi

echo ""
echo "========================================="
echo -e "${GREEN}Starting FastAPI server...${NC}"
echo "========================================="
echo ""
echo "📍 API Docs: http://localhost:8000/docs"
echo "📍 Health: http://localhost:8000/health"
echo "📍 API: http://localhost:8000/api/v1"
echo ""
echo "Press Ctrl+C to stop the server"
echo ""

# Run the application with hot reload
export ENV_FILE=.env.local
uvicorn app.main:app \
    --reload \
    --host 0.0.0.0 \
    --port 8000 \
    --log-level info \
    --env-file .env.local
