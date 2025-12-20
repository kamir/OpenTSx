#!/bin/bash

###############################################################################
# Episode 10: Production Configuration Patterns
#
# Demonstrates best practices for production OpenTSx deployments:
# - Configuration management from files and environment variables
# - Structured logging to console and file
# - Resource pooling and cleanup
# - Error handling with retries
# - Thread pool management for parallel processing
#
# Target Audience: SWE Track (production engineering)
#
# Prerequisites:
# - Project built (run 010_build.sh first)
# - Sample data in sample_data/ directory (optional)
#
# Usage:
#   ./bin/episode_10_production_config.sh
#
# Environment Variables (optional):
#   OPENTSX_THREADS_POOL_SIZE - Number of threads for parallel processing
#   OPENTSX_RETRY_ATTEMPTS - Number of retry attempts
#   OPENTSX_LOG_LEVEL - Logging level (INFO, WARNING, SEVERE, etc.)
###############################################################################

set -e  # Exit on error

# Script directory
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

# Colors for output
GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m' # No Color

echo -e "${BLUE}╔════════════════════════════════════════════════════════════════╗${NC}"
echo -e "${BLUE}║     Episode 10: Production Configuration Patterns             ║${NC}"
echo -e "${BLUE}║     OpenTSx Onboarding - SWE Track                             ║${NC}"
echo -e "${BLUE}╚════════════════════════════════════════════════════════════════╝${NC}"
echo

# Navigate to project root
cd "$PROJECT_ROOT"

# Check if project is built
if [ ! -f "opentsx-core/target/opentsx-core-2.3-SNAPSHOT.jar" ]; then
    echo -e "${YELLOW}⚠ Project not built. Building now...${NC}"
    ./bin/010_build.sh
    echo
fi

# Setup example configuration if not exists
if [ ! -f "opentsx.properties" ]; then
    echo -e "${YELLOW}📝 Creating opentsx.properties from example...${NC}"
    cp opentsx.properties.example opentsx.properties
    echo -e "${GREEN}✓ Configuration file created${NC}"
    echo -e "  Edit opentsx.properties to customize settings"
    echo
fi

# Check for sample data
if [ ! -d "sample_data" ]; then
    echo -e "${YELLOW}⚠ No sample_data/ directory found${NC}"
    echo -e "  Demo will run with limited functionality"
    echo -e "  To test with data: create sample_data/ and add CSV files"
    echo
fi

# Display environment configuration
echo -e "${BLUE}Environment Configuration:${NC}"
echo "  Thread Pool Size: ${OPENTSX_THREADS_POOL_SIZE:-default (4)}"
echo "  Retry Attempts: ${OPENTSX_RETRY_ATTEMPTS:-default (3)}"
echo "  Log Level: ${OPENTSX_LOG_LEVEL:-default (INFO)}"
echo

# Build classpath
CLASSPATH="opentsx-core/target/opentsx-core-2.3-SNAPSHOT.jar"
for jar in opentsx-core/target/lib/*.jar; do
    CLASSPATH="$CLASSPATH:$jar"
done

# Set Java options for production
JAVA_OPTS="${JAVA_OPTS:--Xmx2g -Xms512m}"

echo -e "${GREEN}▶ Running Production Configuration Demo...${NC}"
echo

# Run the demo
java $JAVA_OPTS -cp "$CLASSPATH" org.opentsx.demo.onboarding.ProductionConfig

echo
echo -e "${GREEN}✓ Demo completed${NC}"
echo

# Show log file if created
if [ -f "opentsx.log" ]; then
    echo -e "${BLUE}Log File Summary:${NC}"
    echo "  Location: $PROJECT_ROOT/opentsx.log"
    echo "  Last 10 lines:"
    echo
    tail -10 opentsx.log | sed 's/^/    /'
    echo
fi

# Show processed files if any
if ls processed_*.csv 1> /dev/null 2>&1; then
    echo -e "${BLUE}Processed Files:${NC}"
    ls -lh processed_*.csv | awk '{print "  " $9 " (" $5 ")"}'
    echo
fi

echo -e "${GREEN}════════════════════════════════════════════════════════════════${NC}"
echo -e "${GREEN}Key Learnings:${NC}"
echo
echo "1. Configuration Hierarchy:"
echo "   Environment Variables > Config File > Defaults"
echo
echo "2. Structured Logging:"
echo "   - Console for INFO and above"
echo "   - File (opentsx.log) for ALL levels"
echo "   - Configurable log levels per environment"
echo
echo "3. Error Handling:"
echo "   - Retry with exponential backoff"
echo "   - Graceful degradation on failures"
echo "   - Comprehensive error logging"
echo
echo "4. Resource Management:"
echo "   - Thread pool for parallel processing"
echo "   - Graceful shutdown with timeout"
echo "   - Proper cleanup in finally blocks"
echo
echo "5. Production Best Practices:"
echo "   - Externalized configuration"
echo "   - Environment-specific settings"
echo "   - Monitoring via structured logs"
echo "   - Resilient to transient failures"
echo
echo -e "${BLUE}Next Steps:${NC}"
echo "  • Review opentsx.properties and customize settings"
echo "  • Check opentsx.log for detailed execution logs"
echo "  • Experiment with environment variables:"
echo "    OPENTSX_THREADS_POOL_SIZE=8 ./bin/episode_10_production_config.sh"
echo "  • Add sample CSV files to sample_data/ for parallel processing"
echo "  • Review production deployment checklist in docs/manual/"
echo
echo -e "${GREEN}════════════════════════════════════════════════════════════════${NC}"
