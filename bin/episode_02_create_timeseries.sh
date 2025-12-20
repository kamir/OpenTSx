#!/bin/bash

###############################################################################
# Episode 2: Creating Time Series
#
# Demonstrates fundamental time series creation patterns in OpenTSx:
# - Creating empty TimeSeriesObjects
# - Manual data addition
# - Synthetic data generation
# - Loading from files
# - Saving to files
#
# Target Audience: SWE Track + TSx Track (foundational)
#
# Prerequisites:
# - Project built (run 010_build.sh first)
#
# Usage:
#   ./bin/episode_02_create_timeseries.sh
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
echo -e "${BLUE}║     Episode 2: Creating Time Series                           ║${NC}"
echo -e "${BLUE}║     OpenTSx Onboarding - SWE + TSx Tracks                      ║${NC}"
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

echo -e "${GREEN}▶ Running Episode 2: Creating Time Series...${NC}"
echo

# Run the demo
java $JAVA_OPTS -cp "$CLASSPATH" org.opentsx.demo.onboarding.SimpleTimeSeriesCreation

echo
echo -e "${GREEN}✓ Demo completed${NC}"
echo

# Show created files
if ls *.csv 1> /dev/null 2>&1; then
    echo -e "${BLUE}Generated Files:${NC}"
    ls -lh *.csv | awk '{print "  " $9 " (" $5 ")"}'
    echo
fi

echo -e "${GREEN}════════════════════════════════════════════════════════════════${NC}"
echo -e "${GREEN}Key Learnings:${NC}"
echo
echo "1. TimeSeriesObject Construction:"
echo "   - new TimeSeriesObject() for empty series"
echo "   - new TimeSeriesObject(label) for labeled series"
echo "   - new TimeSeriesObject(double[]) from array"
echo
echo "2. Adding Data:"
echo "   - addValue(y) for Y-only (auto-generates X)"
echo "   - addValuePair(x, y) for explicit X-Y pairs"
echo "   - addValues(TimeSeriesObject) to merge series"
echo
echo "3. Synthetic Data:"
echo "   - TimeSeriesObject.getGaussianDistribution(length, mean, stddev)"
echo "   - TimeSeriesObject.getUniformDistribution(length, min, max)"
echo "   - TimeSeriesObject.getExpDistribution(length, lambda)"
echo
echo "4. File I/O:"
echo "   - writeToFile(new File(path), delimiter)"
echo "   - MessreihenLoader for loading CSV files"
echo "   - Column selection with loadMessreihe_2(file, colX, colY)"
echo
echo "5. Data Access:"
echo "   - ts.yValues.size() for length"
echo "   - (Double)ts.yValues.elementAt(i) for individual values"
echo "   - ts.getAvarage() for mean (note: API typo)"
echo
echo -e "${BLUE}Next Steps:${NC}"
echo "  • Review generated CSV files in current directory"
echo "  • Experiment with different distributions"
echo "  • Try loading your own CSV data"
echo "  • Continue to Episode 3: Basic Operations"
echo "    ./bin/episode_03_basic_operations.sh"
echo
echo -e "${BLUE}Documentation:${NC}"
echo "  • Core Concepts: docs/manual/core-concepts/timeseries-object.md"
echo "  • Data Operations: docs/manual/data-operations/creating-timeseries.md"
echo "  • API Reference: docs/manual/appendix/api-reference.md"
echo
echo -e "${GREEN}════════════════════════════════════════════════════════════════${NC}"
