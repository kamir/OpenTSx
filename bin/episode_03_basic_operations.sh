#!/bin/bash

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

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
NC='\033[0m'

echo -e "${BLUE}Episode 3: Basic Operations (OpenTSx onboarding, SWE + TSx Tracks)${NC}"
echo

cd "$PROJECT_ROOT"

if [ ! -d "opentsx-core/target/classes" ]; then
    echo -e "${YELLOW}warn${NC}  Project not built; running ./bin/010_build.sh"
    ./bin/010_build.sh
    echo
fi

CLASSPATH="$(bin/opentsx classpath opentsx-core)"

JAVA_OPTS="${JAVA_OPTS:--Xmx1g -Xms256m}"

echo "Running Episode 3: Basic Operations"
echo

read -r -a JAVA_ARGS <<< "$JAVA_OPTS"
java "${JAVA_ARGS[@]}" -cp "$CLASSPATH" org.opentsx.demo.onboarding.BasicOperations

echo
echo -e "${GREEN}ok${NC}    Demo completed"
echo

GENERATED=(data/demo_output/original.csv data/demo_output/normalized.csv data/demo_output/scaled.csv data/demo_output/downsampled.csv)
if [ -e "${GENERATED[0]}" ]; then
    echo -e "${BLUE}Generated Files:${NC}"
    for f in "${GENERATED[@]}"; do
        echo "  $f ($(wc -c < "$f") bytes)"
    done
    echo
fi

echo -e "${GREEN}Key Learnings:${NC}"
echo
echo "1. Normalization Operations:"
echo "   - normalize(): In-place: subtracts mean"
echo "   - normalizeToStdevIsOne(): Returns new: z-score normalization"
echo "   - subtractAverage(): Returns new: centers to mean=0"
echo
echo "2. Scaling and Transformation:"
echo "   - scaleY_2(factor): In-place: multiply Y values"
echo "   - add_to_Y(offset): In-place: add to Y values"
echo "   - divide_Y_by(divisor): In-place: divide Y values"
echo "   - scaleX(newMaxX): Returns new: scale X-axis"
echo
echo "3. Copying and Subsetting:"
echo "   - copy(): Full deep copy"
echo "   - copy(limit): Copy first N points"
echo "   - shrinkX(minX, maxX): Extract time window"
echo
echo "4. Combining Series:"
echo "   - add(otherTS): Element-wise addition"
echo "   - divide_by(otherTS): Element-wise division"
echo "   - addValues(otherTS): Concatenate values"
echo
echo "5. Statistical Operations:"
echo "   - getAvarage(): Mean value"
echo "   - getStddev(): Standard deviation"
echo "   - getMinY(), getMaxY(): Range"
echo "   - summeY(): Sum of values"
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
echo "  - Review the CSV files in data/demo_output/ to see the transformations"
echo "  - Experiment with different scaling factors"
echo "  - Try combining your own time series"
echo "  - Continue to Episode 9: Statistical Analysis"
echo "    ./bin/episode_09_analysis.sh"
echo
echo -e "${BLUE}Documentation:${NC}"
echo "  - Guided demos and the 2.x Java tools: docs/user-manual/11-classic-analytics.md"
echo "  - TimeSeriesObject, export and load: opentsx-core/README.md"
echo
