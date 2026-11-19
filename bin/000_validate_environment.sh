#!/usr/bin/env bash

###############################################################################
# OpenTSx Environment Validation Script
###############################################################################
#
# Script: 000_validate_environment.sh
# Purpose: Validate development environment for OpenTSx onboarding
# Episode: E01 - Environment Setup & First Run
#
# Description:
#   Checks all prerequisites needed for OpenTSx development:
#   - Java installation and version
#   - Maven installation and version
#   - Docker availability (optional)
#   - Git configuration
#   - Disk space
#   - Memory availability
#   - Network connectivity
#
# Usage:
#   ./bin/000_validate_environment.sh [--fix]
#
# Options:
#   --fix       Attempt to fix common issues automatically
#   --help      Show this help message
#
# Exit codes: 0 no check failed (warnings allowed), 1 a check failed, 2 usage error.
# Failed checks go to stderr.
#
###############################################################################

set -euo pipefail

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Configuration
FIX_MODE=false
ERRORS=0
WARNINGS=0
CHECKS_PASSED=0

# Parse arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        --fix)
            FIX_MODE=true
            shift
            ;;
        --help)
            sed -n '3,/^set -euo/{/^#/p}' "$0" | sed 's/^# \{0,1\}//'
            exit 0
            ;;
        *)
            echo "000_validate_environment.sh: $1: unknown option; expected --fix or --help" >&2
            exit 2
            ;;
    esac
done

echo -e "${BLUE}==========================================${NC}"
echo -e "${BLUE}  OpenTSx Environment Validation${NC}"
echo -e "${BLUE}==========================================${NC}"
echo
echo "Running environment checks..."
echo

# Check functions
check_pass() {
    echo -e "${GREEN}✓${NC} $1"
    CHECKS_PASSED=$((CHECKS_PASSED + 1))
}

check_warn() {
    echo -e "${YELLOW}⚠${NC} $1"
    WARNINGS=$((WARNINGS + 1))
}

check_fail() {
    echo -e "${RED}✗${NC} $1" >&2
    ERRORS=$((ERRORS + 1))
}

# =====================================================
# CHECK 1: Java Installation
# =====================================================
echo "1. Checking Java installation..."

if ! command -v java &> /dev/null; then
    check_fail "Java not found in PATH"
    echo "   Install Java 17+ (Temurin) from: https://adoptium.net/" >&2
elif ! JAVA_OUT=$(java -version 2>&1); then
    check_fail "Java: java -version failed: ${JAVA_OUT%%$'\n'*}"
else
    JAVA_VERSION=$(awk -F'"' '/ version "/ && v == "" {v = $2} END {print v}' <<< "$JAVA_OUT")
    JAVA_MAJOR=$(echo "$JAVA_VERSION" | cut -d'.' -f1)

    # Handle Java 9+ version format
    if [[ "$JAVA_MAJOR" == "1" ]]; then
        JAVA_MAJOR=$(echo "$JAVA_VERSION" | cut -d'.' -f2)
    fi

    JAVA_MAJOR=${JAVA_MAJOR%%[!0-9]*}

    if [[ -n "$JAVA_MAJOR" ]] && (( JAVA_MAJOR >= 17 )); then
        check_pass "Java $JAVA_VERSION installed"
    else
        check_fail "Java version too old: $JAVA_VERSION (need 17+)"
    fi
fi

# Check JAVA_HOME
if [[ -n "${JAVA_HOME:-}" ]] && [[ -d "$JAVA_HOME" ]]; then
    check_pass "JAVA_HOME is set: $JAVA_HOME"
else
    check_warn "JAVA_HOME not set (recommended for Maven)"
    if [[ "$FIX_MODE" == true ]]; then
        # Try to set JAVA_HOME
        if command -v java &> /dev/null; then
            JAVA_PATH=$(command -v java)
            JAVA_HOME_CANDIDATE=$(readlink -f "$JAVA_PATH" | sed 's:/bin/java::')
            if [[ -d "$JAVA_HOME_CANDIDATE" ]]; then
                export JAVA_HOME="$JAVA_HOME_CANDIDATE"
                echo "   → Set JAVA_HOME=$JAVA_HOME (for this session)"
            fi
        fi
    fi
fi

echo

# =====================================================
# CHECK 2: Maven Installation
# =====================================================
echo "2. Checking Maven installation..."

if ! command -v mvn &> /dev/null; then
    check_fail "Maven not found in PATH"
    echo "   Install Maven 3.9+ from: https://maven.apache.org/download.cgi" >&2
elif ! MVN_OUT=$(mvn -version 2>&1); then
    check_fail "Maven: mvn -version failed: ${MVN_OUT%%$'\n'*}"
else
    MVN_VERSION=$(awk '/^Apache Maven / && v == "" {v = $3} END {print v}' <<< "$MVN_OUT")
    IFS=. read -r MVN_MAJOR MVN_MINOR _ <<< "$MVN_VERSION"

    if [[ "$MVN_MAJOR" =~ ^[0-9]+$ && "$MVN_MINOR" =~ ^[0-9]+$ ]] \
            && (( MVN_MAJOR > 3 || (MVN_MAJOR == 3 && MVN_MINOR >= 9) )); then
        check_pass "Maven $MVN_VERSION installed"
    else
        check_fail "Maven version too old: ${MVN_VERSION:-unknown} (need 3.9+)"
    fi
fi

# Check Maven local repository
if [[ -d "$HOME/.m2/repository" ]]; then
    REPO_SIZE=$(du -sh "$HOME/.m2/repository" 2>/dev/null | cut -f1 || true)
    check_pass "Maven local repository exists (~$REPO_SIZE)"
else
    check_warn "Maven local repository not found"
    echo "   Will be created on first Maven build"
fi

echo

# =====================================================
# CHECK 3: Git Installation and Configuration
# =====================================================
echo "3. Checking Git installation..."

if ! command -v git &> /dev/null; then
    check_fail "Git not found in PATH"
    echo "   Install Git from: https://git-scm.com/downloads" >&2
elif ! GIT_OUT=$(git --version 2>&1); then
    check_fail "Git: git --version failed: ${GIT_OUT%%$'\n'*}"
else
    GIT_VERSION=$(awk '{print $3}' <<< "$GIT_OUT")
    check_pass "Git $GIT_VERSION installed"

    # Check Git configuration
    GIT_USER=$(git config --global user.name 2>/dev/null || echo "")
    GIT_EMAIL=$(git config --global user.email 2>/dev/null || echo "")

    if [[ -n "$GIT_USER" ]] && [[ -n "$GIT_EMAIL" ]]; then
        check_pass "Git configured: $GIT_USER <$GIT_EMAIL>"
    else
        check_warn "Git user.name or user.email not configured"
        echo "   Run: git config --global user.name 'Your Name'"
        echo "   Run: git config --global user.email 'you@example.com'"
    fi
fi

echo

# =====================================================
# CHECK 4: Docker (Optional)
# =====================================================
echo "4. Checking Docker (optional for storage backends)..."

if ! command -v docker &> /dev/null; then
    check_warn "Docker not found (optional, needed for E07)"
    echo "   Install from: https://docs.docker.com/get-docker/"
elif ! DOCKER_OUT=$(docker --version 2>&1); then
    check_warn "Docker: docker --version failed: ${DOCKER_OUT%%$'\n'*}"
else
    DOCKER_VERSION=$(awk '{print $3}' <<< "$DOCKER_OUT" | sed 's/,//')
    check_pass "Docker $DOCKER_VERSION installed"

    # Check if Docker daemon is running
    if docker ps &> /dev/null; then
        check_pass "Docker daemon is running"
    else
        check_warn "Docker installed but daemon not running"
        echo "   Start Docker Desktop or run: sudo systemctl start docker"
    fi
fi

echo

# =====================================================
# CHECK 5: System Resources
# =====================================================
echo "5. Checking system resources..."

# Memory check
TOTAL_MEM_GB=""
if [[ "$(uname)" == "Darwin" ]]; then
    # macOS
    TOTAL_MEM_GB=$(( $(sysctl -n hw.memsize) / 1024 / 1024 / 1024 ))
elif command -v free &> /dev/null && FREE_OUT=$(free -g 2>/dev/null); then
    TOTAL_MEM_GB=$(awk '/^Mem:/{print $2}' <<< "$FREE_OUT")
fi

if [[ ! "$TOTAL_MEM_GB" =~ ^[0-9]+$ ]]; then
    check_warn "Memory: free is missing or failed; install procps to check the memory"
elif [[ "$TOTAL_MEM_GB" -ge 8 ]]; then
    check_pass "Memory: ${TOTAL_MEM_GB}GB (recommended: 8GB+)"
elif [[ "$TOTAL_MEM_GB" -ge 4 ]]; then
    check_warn "Memory: ${TOTAL_MEM_GB}GB (minimum, may be slow)"
else
    check_warn "Memory: ${TOTAL_MEM_GB}GB (less than recommended 4GB)"
fi

# Disk space check
if [[ "$(uname)" == "Darwin" ]]; then
    # macOS
    FREE_SPACE_GB=$(df -g . | awk 'NR==2 {print $4}') || FREE_SPACE_GB=""
else
    # Linux
    FREE_SPACE_GB=$(df -BG . | awk 'NR==2 {gsub(/G/,""); print $4}') || FREE_SPACE_GB=""
fi

if [[ ! "$FREE_SPACE_GB" =~ ^[0-9]+$ ]]; then
    check_warn "Disk space: df failed; check the free space with df -h"
elif [[ "$FREE_SPACE_GB" -ge 10 ]]; then
    check_pass "Disk space: ${FREE_SPACE_GB}GB free (recommended: 10GB+)"
else
    check_warn "Disk space: ${FREE_SPACE_GB}GB free (may need more)"
fi

# CPU check
if [[ "$(uname)" == "Darwin" ]]; then
    CPU_CORES=$(sysctl -n hw.ncpu)
else
    CPU_CORES=$(nproc)
fi

if [[ "$CPU_CORES" -ge 4 ]]; then
    check_pass "CPU: $CPU_CORES cores (recommended: 4+)"
else
    check_warn "CPU: $CPU_CORES cores (minimum, may be slow)"
fi

echo

# =====================================================
# CHECK 6: Network Connectivity
# =====================================================
echo "6. Checking network connectivity..."

# Check Maven Central
if curl -s --connect-timeout 5 https://repo.maven.apache.org/maven2/ > /dev/null; then
    check_pass "Maven Central reachable"
else
    check_warn "Cannot reach Maven Central (network/proxy issue?)"
fi

# Check GitHub
if curl -s --connect-timeout 5 https://github.com > /dev/null; then
    check_pass "GitHub reachable"
else
    check_warn "Cannot reach GitHub (network/proxy issue?)"
fi

echo

# =====================================================
# CHECK 7: OpenTSx Project
# =====================================================
echo "7. Checking OpenTSx project..."

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

if [[ -f "$PROJECT_ROOT/pom.xml" ]]; then
    check_pass "Project pom.xml found"
else
    check_fail "Project pom.xml not found at $PROJECT_ROOT"
fi

if [[ -d "$PROJECT_ROOT/opentsx-core" ]]; then
    check_pass "opentsx-core module exists"
else
    check_fail "opentsx-core module not found"
fi

# Check if already built
if [[ -d "$PROJECT_ROOT/opentsx-core/target" ]]; then
    check_pass "Project appears to be built (target/ exists)"
else
    check_warn "Project not built yet"
    echo "   Run: ./bin/010_build.sh"
fi

echo

# =====================================================
# CHECK 8: Display Settings (for GUI)
# =====================================================
echo "8. Checking display settings (for GUI)..."

if [[ -n "${DISPLAY:-}" ]]; then
    check_pass "DISPLAY is set: $DISPLAY"
else
    check_warn "DISPLAY not set (GUI may not work)"
    echo "   For remote: ssh -X user@host"
    echo "   For macOS: Install XQuartz"
fi

echo

# =====================================================
# Summary
# =====================================================
echo -e "${BLUE}==========================================${NC}"
echo -e "${BLUE}  Validation Summary${NC}"
echo -e "${BLUE}==========================================${NC}"
echo
echo -e "${GREEN}Passed checks: $CHECKS_PASSED${NC}"
echo -e "${YELLOW}Warnings: $WARNINGS${NC}"
echo -e "${RED}Failed checks: $ERRORS${NC}"
echo

if [[ "$ERRORS" -eq 0 ]] && [[ "$WARNINGS" -eq 0 ]]; then
    echo -e "${GREEN}✓ Environment is fully configured!${NC}"
    echo
    echo "Next steps:"
    echo "  1. Build the project: ./bin/010_build.sh"
    echo "  2. Launch GUI: ./bin/000_launch_tsa_workbench.sh"
    echo "  3. Read the user manual: docs/user-manual/02-getting-started.md"
    exit 0
elif [[ "$ERRORS" -eq 0 ]]; then
    echo -e "${YELLOW}⚠ Environment has minor issues (warnings only)${NC}"
    echo
    echo "You can proceed, but consider fixing warnings for better experience."
    echo
    echo "Next steps:"
    echo "  1. Review warnings above"
    echo "  2. Build the project: ./bin/010_build.sh"
    echo "  3. Start onboarding"
    exit 0
else
    {
        echo -e "${RED}✗ Environment has critical issues${NC}"
        echo
        echo "Please fix the failed checks above before proceeding."
        echo "Common fixes:"
        echo "  • Install Java 17+ (Temurin): https://adoptium.net/"
        echo "  • Install Maven 3.9+: https://maven.apache.org/"
        echo "  • Install Git: https://git-scm.com/"
    } >&2
    exit 1
fi
