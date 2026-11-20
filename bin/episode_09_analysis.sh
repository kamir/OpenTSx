#!/bin/bash

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

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
NC='\033[0m'

echo -e "${BLUE}Episode 9: Statistical Analysis (OpenTSx onboarding, TSx Track)${NC}"
echo

cd "$PROJECT_ROOT"

if [ ! -d "opentsx-core/target/classes" ]; then
    echo -e "${YELLOW}warn${NC}  Project not built; running ./bin/010_build.sh"
    ./bin/010_build.sh
    echo
fi

CLASSPATH="$(bin/opentsx classpath opentsx-core)"

JAVA_OPTS="${JAVA_OPTS:--Xmx1g -Xms256m}"

echo "Running Episode 9: Statistical Analysis"
echo

read -r -a JAVA_ARGS <<< "$JAVA_OPTS"
java "${JAVA_ARGS[@]}" -cp "$CLASSPATH" org.opentsx.demo.onboarding.TimeSeriesAnalysis

echo
echo -e "${GREEN}ok${NC}    Demo completed"
echo

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
echo "  R/Python                    OpenTSx"
echo "  mean(x)                     ts.getAvarage()"
echo "  sd(x)                       ts.getStddev()"
echo "  length(x)                   ts.yValues.size()"
echo "  x[i]                        (Double)ts.yValues.elementAt(i)"
echo "  movavg(x, n)                ts.setBinningX_average(n)"
echo "  acf(x, lag)                 simpleAutocorrelation(ts, lag) in TimeSeriesAnalysis"
echo
echo -e "${BLUE}Next Steps:${NC}"
echo "  - Experiment with different window sizes"
echo "  - Review DFA (Detrended Fluctuation Analysis)"
echo
echo -e "${BLUE}Advanced Topics:${NC}"
echo "  - DFA, MFDFA and event synchronization in org.opentsx.algorithms of opentsx-core"
echo "  - Episode 10: Production Configuration"
echo "    ./bin/episode_10_production_config.sh"
echo
echo -e "${BLUE}Documentation:${NC}"
echo "  - Guided demos and the 2.x Java tools: docs/user-manual/11-classic-analytics.md"
echo "  - TimeSeriesObject, export and load: opentsx-core/README.md"
echo
