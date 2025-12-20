#!/bin/bash

###############################################################################
# Episode 3: Basic Operations
#
# Demonstrates fundamental time series operations in OpenTSx:
# - Normalization and z-score transformation
# - Scaling and offsetting
# - Copying and subsetting
# - Combining series (addition, division)
# - Statistical calculations
# - Iteration patterns
#
# Target Audience: SWE Track + TSx Track (foundational)
#
# Prerequisites:
# - Project built (run 010_build.sh first)
# - Episode 2 completed (recommended)
#
# Usage:
#   ./bin/episode_03_basic_operations.sh
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
echo -e "${BLUE}║     Episode 3: Basic Operations                               ║${NC}"
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

echo -e "${GREEN}▶ Running Episode 3: Basic Operations...${NC}"
echo

# Run the demo
java $JAVA_OPTS -cp "$CLASSPATH" org.opentsx.demo.onboarding.BasicOperations

echo
echo -e "${GREEN}✓ Demo completed${NC}"
echo

# Show created files
if ls *_operations*.csv 1> /dev/null 2>&1; then
    echo -e "${BLUE}Generated Files:${NC}"
    ls -lh *_operations*.csv | awk '{print "  " $9 " (" $5 ")"}'
    echo
fi

echo -e "${GREEN}════════════════════════════════════════════════════════════════${NC}"
echo -e "${GREEN}Key Learnings:${NC}"
echo
echo "1. Normalization Operations:"
echo "   - normalize() — In-place: subtracts mean"
echo "   - normalizeToStdevIsOne() — Returns new: z-score normalization"
echo "   - subtractAverage() — Returns new: centers to mean=0"
echo
echo "2. Scaling and Transformation:"
echo "   - scaleY_2(factor) — In-place: multiply Y values"
echo "   - add_to_Y(offset) — In-place: add to Y values"
echo "   - divide_Y_by(divisor) — In-place: divide Y values"
echo "   - scaleX(newMaxX) — Returns new: scale X-axis"
echo
echo "3. Copying and Subsetting:"
echo "   - copy() — Full deep copy"
echo "   - copy(limit) — Copy first N points"
echo "   - shrinkX(minX, maxX) — Extract time window"
echo
echo "4. Combining Series:"
echo "   - add(otherTS) — Element-wise addition"
echo "   - divide_by(otherTS) — Element-wise division"
echo "   - addValues(otherTS) — Concatenate values"
echo
echo "5. Statistical Operations:"
echo "   - getAvarage() — Mean value"
echo "   - getStddev() — Standard deviation"
echo "   - getMinY(), getMaxY() — Range"
echo "   - summeY() — Sum of values"
echo
echo "6. Iteration Pattern:"
echo "   for (int i = 0; i < ts.yValues.size(); i++) {"
echo "       double y = (Double)ts.yValues.elementAt(i);"
echo "       // Process value"
echo "   }"
echo
echo -e "${BLUE}Mutability Warning:${NC}"
echo "  Some methods modify the original (in-place):"
echo "    - normalize(), scaleY_2(), add_to_Y()"
echo "  Others return new objects (immutable):"
echo "    - normalizeToStdevIsOne(), copy(), shrinkX()"
echo "  Always check documentation!"
echo
echo -e "${BLUE}Next Steps:${NC}"
echo "  • Review generated CSV files to see transformations"
echo "  • Experiment with different scaling factors"
echo "  • Try combining your own time series"
echo "  • Continue to Episode 9: Statistical Analysis"
echo "    ./bin/episode_09_analysis.sh"
echo
echo -e "${BLUE}Documentation:${NC}"
echo "  • Data Operations: docs/manual/data-operations/transformations.md"
echo "  • Best Practices: docs/manual/best-practices/README.md"
echo "  • API Reference: docs/manual/appendix/api-reference.md"
echo
echo -e "${GREEN}════════════════════════════════════════════════════════════════${NC}"
