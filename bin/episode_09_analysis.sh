#!/bin/bash

###############################################################################
# Episode 9: Statistical Analysis
#
# Demonstrates statistical analysis patterns in OpenTSx:
# - Moving averages and smoothing
# - Trend detection
# - Simple autocorrelation
# - Basic anomaly detection
# - Seasonal pattern recognition
#
# Target Audience: TSx Track (time series experts)
#
# Prerequisites:
# - Project built (run 010_build.sh first)
# - Episodes 2 and 3 completed (recommended)
#
# Usage:
#   ./bin/episode_09_analysis.sh
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
echo -e "${BLUE}║     Episode 9: Statistical Analysis                           ║${NC}"
echo -e "${BLUE}║     OpenTSx Onboarding - TSx Track                             ║${NC}"
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

# Create output directory
mkdir -p output

# Build classpath
CLASSPATH="opentsx-core/target/opentsx-core-2.3-SNAPSHOT.jar"
for jar in opentsx-core/target/lib/*.jar; do
    CLASSPATH="$CLASSPATH:$jar"
done

# Set Java options
JAVA_OPTS="${JAVA_OPTS:--Xmx1g -Xms256m}"

echo -e "${GREEN}▶ Running Episode 9: Statistical Analysis...${NC}"
echo

# Run the demo
java $JAVA_OPTS -cp "$CLASSPATH" org.opentsx.demo.onboarding.TimeSeriesAnalysis

echo
echo -e "${GREEN}✓ Demo completed${NC}"
echo

# Show created files
if ls *_analysis*.csv 1> /dev/null 2>&1; then
    echo -e "${BLUE}Generated Files:${NC}"
    ls -lh *_analysis*.csv | awk '{print "  " $9 " (" $5 ")"}'
    echo
fi

echo -e "${GREEN}════════════════════════════════════════════════════════════════${NC}"
echo -e "${GREEN}Key Learnings:${NC}"
echo
echo "1. Moving Average (Smoothing):"
echo "   - Simple moving average (SMA) implementation"
echo "   - Window-based aggregation"
echo "   - Noise reduction while preserving trends"
echo
echo "2. Trend Detection:"
echo "   - Linear trend calculation"
echo "   - Slope and intercept estimation"
echo "   - Detrending for residual analysis"
echo
echo "3. Autocorrelation:"
echo "   - Lag-based correlation"
echo "   - Identifying temporal dependencies"
echo "   - Detecting periodic patterns"
echo
echo "4. Anomaly Detection:"
echo "   - Z-score based outlier detection"
echo "   - Threshold-based filtering"
echo "   - Statistical deviation analysis"
echo
echo "5. Seasonal Patterns:"
echo "   - Creating synthetic seasonal data"
echo "   - Period detection"
echo "   - Seasonal decomposition concepts"
echo
echo -e "${BLUE}From R/Python Perspective:${NC}"
echo
echo "  R/Python                    OpenTSx Equivalent"
echo "  ─────────────────────────   ──────────────────────────────"
echo "  mean(x)                  →  ts.getAvarage()"
echo "  sd(x)                    →  ts.getStddev()"
echo "  length(x)                →  ts.yValues.size()"
echo "  x[i]                     →  (Double)ts.yValues.elementAt(i)"
echo "  movavg(x, n)             →  movingAverage(ts, window)"
echo "  acf(x, lag)              →  simpleAutocorrelation(ts, lag)"
echo
echo -e "${BLUE}Next Steps:${NC}"
echo "  • Review generated CSV files with analysis results"
echo "  • Compare smoothed vs. raw data in plotting tool"
echo "  • Experiment with different window sizes"
echo "  • Explore advanced algorithms in opentsx-algorithms module"
echo "  • Review DFA (Detrended Fluctuation Analysis)"
echo
echo -e "${BLUE}Advanced Topics:${NC}"
echo "  For production-ready implementations, see:"
echo "  • opentsx-algorithms module — DFA, MFDFA, Event Sync"
echo "  • Episode 10: Production Configuration"
echo "    ./bin/episode_10_production_config.sh"
echo
echo -e "${BLUE}Documentation:${NC}"
echo "  • Statistical Analysis: docs/manual/statistical-analysis/README.md"
echo "  • R/Python Translation: docs/manual/appendix/r-python-translation.md"
echo "  • Algorithms: FEATURES.md (advanced methods)"
echo
echo -e "${GREEN}════════════════════════════════════════════════════════════════${NC}"
