#!/bin/bash

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

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
NC='\033[0m'

echo -e "${BLUE}Episode 2: Creating Time Series (OpenTSx onboarding, SWE + TSx Tracks)${NC}"
echo

cd "$PROJECT_ROOT"

if [ ! -d "opentsx-core/target/classes" ]; then
    echo -e "${YELLOW}warn${NC}  Project not built; running ./bin/010_build.sh"
    ./bin/010_build.sh
    echo
fi

CLASSPATH="$(bin/opentsx classpath opentsx-core)"

JAVA_OPTS="${JAVA_OPTS:--Xmx1g -Xms256m}"

echo "Running Episode 2: Creating Time Series"
echo

read -r -a JAVA_ARGS <<< "$JAVA_OPTS"
java "${JAVA_ARGS[@]}" -cp "$CLASSPATH" org.opentsx.demo.onboarding.SimpleTimeSeriesCreation

echo
echo -e "${GREEN}ok${NC}    Demo completed"
echo

GENERATED=(data/demo_output/gaussian_ts.*)
if [ -e "${GENERATED[0]}" ]; then
    echo -e "${BLUE}Generated Files:${NC}"
    for f in "${GENERATED[@]}"; do
        echo "  $f ($(wc -c < "$f") bytes)"
    done
    echo
fi

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
echo "  - Review the files in data/demo_output/"
echo "  - Experiment with different distributions"
echo "  - Try loading your own CSV data"
echo "  - Continue to Episode 3: Basic Operations"
echo "    ./bin/episode_03_basic_operations.sh"
echo
echo -e "${BLUE}Documentation:${NC}"
echo "  - Guided demos and the 2.x Java tools: docs/user-manual/11-classic-analytics.md"
echo "  - TimeSeriesObject, export and load: opentsx-core/README.md"
echo
