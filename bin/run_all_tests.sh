#!/bin/bash

###############################################################################
# OpenTSx Comprehensive Test Suite Runner
#
# Executes all validation tests, integration tests, and generates reports.
#
# Usage:
#   ./bin/run_all_tests.sh [--quick|--full|--validation-only]
#
# Options:
#   --quick           Run only fast validation tests
#   --full            Run all tests including performance benchmarks
#   --validation-only Run only exercise validation tests
#
# Output:
#   - Console output with test results
#   - test-report.txt with detailed results
#   - test-summary.json with structured data
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

# Default mode
MODE="${1:---full}"

# Test counters
TOTAL_TESTS=0
PASSED_TESTS=0
FAILED_TESTS=0

# Output files
REPORT_FILE="$PROJECT_ROOT/test-report.txt"
SUMMARY_FILE="$PROJECT_ROOT/test-summary.json"

echo -e "${BLUE}╔════════════════════════════════════════════════════════════════╗${NC}"
echo -e "${BLUE}║     OpenTSx Comprehensive Test Suite                          ║${NC}"
echo -e "${BLUE}║     TASK-001 Phase 3: Testing Infrastructure                  ║${NC}"
echo -e "${BLUE}╚════════════════════════════════════════════════════════════════╝${NC}"
echo
echo -e "${BLUE}Mode: $MODE${NC}"
echo -e "${BLUE}Start Time: $(date)${NC}"
echo

# Navigate to project root
cd "$PROJECT_ROOT"

# Initialize report
cat > "$REPORT_FILE" << EOF
OpenTSx Test Report
===================
Generated: $(date)
Mode: $MODE

EOF

# Check if project is built
if [ ! -f "opentsx-core/target/opentsx-core-2.3-SNAPSHOT.jar" ]; then
    echo -e "${YELLOW}⚠ Project not built. Building now...${NC}"
    ./bin/010_build.sh || {
        echo -e "${RED}✗ Build failed. Cannot run tests.${NC}"
        exit 1
    }
    echo
fi

# Build classpath
CLASSPATH="opentsx-core/target/opentsx-core-2.3-SNAPSHOT.jar"
CLASSPATH="$CLASSPATH:tests"
for jar in opentsx-core/target/lib/*.jar; do
    CLASSPATH="$CLASSPATH:$jar"
done

# Compile test files
echo -e "${BLUE}Compiling test files...${NC}"
mkdir -p tests/build

javac -cp "$CLASSPATH" -d tests/build \
    tests/validation/*.java 2>&1 | tee -a "$REPORT_FILE"

if [ ${PIPESTATUS[0]} -eq 0 ]; then
    echo -e "${GREEN}✓ Test compilation successful${NC}"
    echo
else
    echo -e "${RED}✗ Test compilation failed${NC}"
    exit 1
fi

# Update classpath to include compiled tests
CLASSPATH="$CLASSPATH:tests/build"

###############################################################################
# Phase 1: Exercise Validation Tests
###############################################################################

if [ "$MODE" != "--demo-only" ]; then
    echo -e "${BLUE}═══════════════════════════════════════════════════════════════${NC}"
    echo -e "${BLUE}Phase 1: Exercise Validation Tests${NC}"
    echo -e "${BLUE}═══════════════════════════════════════════════════════════════${NC}"
    echo

    echo "Phase 1: Exercise Validation Tests" >> "$REPORT_FILE"
    echo "====================================" >> "$REPORT_FILE"
    echo >> "$REPORT_FILE"

    # Episode 2 Validation
    echo -e "${YELLOW}Running Episode 2 validation tests...${NC}"
    java -cp "$CLASSPATH" org.opentsx.tests.validation.Episode02ValidationTests 2>&1 | tee -a "$REPORT_FILE"
    TEST_RESULT=${PIPESTATUS[0]}

    if [ $TEST_RESULT -eq 0 ]; then
        PASSED_TESTS=$((PASSED_TESTS + 1))
        echo -e "${GREEN}✓ Episode 2 validation passed${NC}"
    else
        FAILED_TESTS=$((FAILED_TESTS + 1))
        echo -e "${RED}✗ Episode 2 validation failed${NC}"
    fi
    TOTAL_TESTS=$((TOTAL_TESTS + 1))
    echo

    # Episode 3 Validation
    echo -e "${YELLOW}Running Episode 3 validation tests...${NC}"
    java -cp "$CLASSPATH" org.opentsx.tests.validation.Episode03ValidationTests 2>&1 | tee -a "$REPORT_FILE"
    TEST_RESULT=${PIPESTATUS[0]}

    if [ $TEST_RESULT -eq 0 ]; then
        PASSED_TESTS=$((PASSED_TESTS + 1))
        echo -e "${GREEN}✓ Episode 3 validation passed${NC}"
    else
        FAILED_TESTS=$((FAILED_TESTS + 1))
        echo -e "${RED}✗ Episode 3 validation failed${NC}"
    fi
    TOTAL_TESTS=$((TOTAL_TESTS + 1))
    echo
fi

###############################################################################
# Phase 2: Demo Script Integration Tests
###############################################################################

if [ "$MODE" = "--full" ] || [ "$MODE" = "--demo-only" ]; then
    echo -e "${BLUE}═══════════════════════════════════════════════════════════════${NC}"
    echo -e "${BLUE}Phase 2: Demo Script Integration Tests${NC}"
    echo -e "${BLUE}═══════════════════════════════════════════════════════════════${NC}"
    echo

    echo "Phase 2: Demo Script Integration Tests" >> "$REPORT_FILE"
    echo "=======================================" >> "$REPORT_FILE"
    echo >> "$REPORT_FILE"

    # Test Episode 2 demo
    echo -e "${YELLOW}Testing Episode 2 demo script...${NC}"
    if [ -f "bin/episode_02_create_timeseries.sh" ]; then
        bash -n bin/episode_02_create_timeseries.sh
        if [ $? -eq 0 ]; then
            PASSED_TESTS=$((PASSED_TESTS + 1))
            echo -e "${GREEN}✓ Episode 2 demo syntax valid${NC}"
            echo "Episode 2 demo: PASS (syntax valid)" >> "$REPORT_FILE"
        else
            FAILED_TESTS=$((FAILED_TESTS + 1))
            echo -e "${RED}✗ Episode 2 demo syntax invalid${NC}"
            echo "Episode 2 demo: FAIL (syntax error)" >> "$REPORT_FILE"
        fi
        TOTAL_TESTS=$((TOTAL_TESTS + 1))
    fi
    echo

    # Test Episode 3 demo
    echo -e "${YELLOW}Testing Episode 3 demo script...${NC}"
    if [ -f "bin/episode_03_basic_operations.sh" ]; then
        bash -n bin/episode_03_basic_operations.sh
        if [ $? -eq 0 ]; then
            PASSED_TESTS=$((PASSED_TESTS + 1))
            echo -e "${GREEN}✓ Episode 3 demo syntax valid${NC}"
            echo "Episode 3 demo: PASS (syntax valid)" >> "$REPORT_FILE"
        else
            FAILED_TESTS=$((FAILED_TESTS + 1))
            echo -e "${RED}✗ Episode 3 demo syntax invalid${NC}"
            echo "Episode 3 demo: FAIL (syntax error)" >> "$REPORT_FILE"
        fi
        TOTAL_TESTS=$((TOTAL_TESTS + 1))
    fi
    echo

    # Test Episode 9 demo
    echo -e "${YELLOW}Testing Episode 9 demo script...${NC}"
    if [ -f "bin/episode_09_analysis.sh" ]; then
        bash -n bin/episode_09_analysis.sh
        if [ $? -eq 0 ]; then
            PASSED_TESTS=$((PASSED_TESTS + 1))
            echo -e "${GREEN}✓ Episode 9 demo syntax valid${NC}"
            echo "Episode 9 demo: PASS (syntax valid)" >> "$REPORT_FILE"
        else
            FAILED_TESTS=$((FAILED_TESTS + 1))
            echo -e "${RED}✗ Episode 9 demo syntax invalid${NC}"
            echo "Episode 9 demo: FAIL (syntax error)" >> "$REPORT_FILE"
        fi
        TOTAL_TESTS=$((TOTAL_TESTS + 1))
    fi
    echo

    # Test Episode 10 demo
    echo -e "${YELLOW}Testing Episode 10 demo script...${NC}"
    if [ -f "bin/episode_10_production_config.sh" ]; then
        bash -n bin/episode_10_production_config.sh
        if [ $? -eq 0 ]; then
            PASSED_TESTS=$((PASSED_TESTS + 1))
            echo -e "${GREEN}✓ Episode 10 demo syntax valid${NC}"
            echo "Episode 10 demo: PASS (syntax valid)" >> "$REPORT_FILE"
        else
            FAILED_TESTS=$((FAILED_TESTS + 1))
            echo -e "${RED}✗ Episode 10 demo syntax invalid${NC}"
            echo "Episode 10 demo: FAIL (syntax error)" >> "$REPORT_FILE"
        fi
        TOTAL_TESTS=$((TOTAL_TESTS + 1))
    fi
    echo
fi

###############################################################################
# Phase 3: Documentation Validation
###############################################################################

if [ "$MODE" = "--full" ]; then
    echo -e "${BLUE}═══════════════════════════════════════════════════════════════${NC}"
    echo -e "${BLUE}Phase 3: Documentation Validation${NC}"
    echo -e "${BLUE}═══════════════════════════════════════════════════════════════${NC}"
    echo

    echo "Phase 3: Documentation Validation" >> "$REPORT_FILE"
    echo "==================================" >> "$REPORT_FILE"
    echo >> "$REPORT_FILE"

    # Check for required documentation
    DOCS=(
        "README.md"
        "docs/manual/README.md"
        "docs/devguide/infrastructure/local-development.md"
        "exercises/README.md"
        "bin/README.md"
    )

    for doc in "${DOCS[@]}"; do
        echo -e "${YELLOW}Checking $doc...${NC}"
        if [ -f "$doc" ]; then
            PASSED_TESTS=$((PASSED_TESTS + 1))
            echo -e "${GREEN}✓ $doc exists${NC}"
            echo "$doc: PASS (exists)" >> "$REPORT_FILE"
        else
            FAILED_TESTS=$((FAILED_TESTS + 1))
            echo -e "${RED}✗ $doc missing${NC}"
            echo "$doc: FAIL (missing)" >> "$REPORT_FILE"
        fi
        TOTAL_TESTS=$((TOTAL_TESTS + 1))
    done
    echo
fi

###############################################################################
# Generate Summary
###############################################################################

echo -e "${BLUE}═══════════════════════════════════════════════════════════════${NC}"
echo -e "${BLUE}Test Summary${NC}"
echo -e "${BLUE}═══════════════════════════════════════════════════════════════${NC}"
echo

PASS_RATE=$(awk "BEGIN {printf \"%.1f\", ($PASSED_TESTS * 100.0 / $TOTAL_TESTS)}")

echo "Total Tests: $TOTAL_TESTS"
echo "Passed: $PASSED_TESTS"
echo "Failed: $FAILED_TESTS"
echo "Pass Rate: $PASS_RATE%"
echo

if [ $FAILED_TESTS -eq 0 ]; then
    echo -e "${GREEN}✓ ALL TESTS PASSED${NC}"
    OVERALL_STATUS="PASS"
else
    echo -e "${RED}✗ SOME TESTS FAILED${NC}"
    OVERALL_STATUS="FAIL"
fi

# Write summary to report
cat >> "$REPORT_FILE" << EOF

Test Summary
============
Total Tests: $TOTAL_TESTS
Passed: $PASSED_TESTS
Failed: $FAILED_TESTS
Pass Rate: $PASS_RATE%
Overall Status: $OVERALL_STATUS

End Time: $(date)
EOF

# Generate JSON summary
cat > "$SUMMARY_FILE" << EOF
{
  "timestamp": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "mode": "$MODE",
  "total_tests": $TOTAL_TESTS,
  "passed": $PASSED_TESTS,
  "failed": $FAILED_TESTS,
  "pass_rate": $PASS_RATE,
  "overall_status": "$OVERALL_STATUS",
  "reports": {
    "detailed": "$REPORT_FILE",
    "summary": "$SUMMARY_FILE"
  }
}
EOF

echo
echo -e "${BLUE}Reports generated:${NC}"
echo "  - Detailed report: $REPORT_FILE"
echo "  - JSON summary: $SUMMARY_FILE"
echo

# Exit with appropriate code
if [ $FAILED_TESTS -eq 0 ]; then
    exit 0
else
    exit 1
fi
