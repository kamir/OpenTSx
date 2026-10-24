#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
MODE="${1:---full}"
if [ "$#" -gt 1 ]; then
    echo "Usage: $0 [--quick|--full|--validation-only|--demo-only]" >&2
    exit 2
fi
case "$MODE" in
    --quick|--full|--validation-only|--demo-only) ;;
    *) echo "Unknown mode: $MODE" >&2; exit 2 ;;
esac

cd "$PROJECT_ROOT"
REPORT_FILE="$PROJECT_ROOT/test-report.txt"
SUMMARY_FILE="$PROJECT_ROOT/test-summary.json"
TOTAL_TESTS=0
FAILED_TESTS=0
printf 'OpenTSx validation report\nMode: %s\n' "$MODE" > "$REPORT_FILE"

record() {
    TOTAL_TESTS=$((TOTAL_TESTS + 1))
    if [ "$1" -eq 0 ]; then
        printf 'PASS: %s\n' "$2" | tee -a "$REPORT_FILE"
    else
        FAILED_TESTS=$((FAILED_TESTS + 1))
        printf 'FAIL: %s\n' "$2" | tee -a "$REPORT_FILE"
    fi
}

if [ "$MODE" != "--demo-only" ]; then
    if mvn -B -ntp -pl opentsx-core -am test -Dtest=ExerciseValidationTest \
            -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | tee -a "$REPORT_FILE"; then
        record 0 "Maven validation"
    else
        record 1 "Maven validation failed"
    fi
fi

if [ "$MODE" = "--full" ] || [ "$MODE" = "--demo-only" ]; then
    for script in bin/episode_02_create_timeseries.sh bin/episode_03_basic_operations.sh \
            bin/episode_09_analysis.sh bin/episode_10_production_config.sh; do
        if bash -n "$script" >> "$REPORT_FILE" 2>&1; then
            record 0 "$script"
        else
            record 1 "$script"
        fi
    done
fi

if [ "$MODE" = "--full" ]; then
    for doc in README.md docs/user-manual/README.md docs/devguide/infrastructure/local-development.md \
            exercises/README.md bin/README.md; do
        if [ -f "$doc" ]; then
            record 0 "$doc"
        else
            record 1 "$doc"
        fi
    done
fi

PASSED_TESTS=$((TOTAL_TESTS - FAILED_TESTS))
OVERALL_STATUS=PASS
if [ "$FAILED_TESTS" -ne 0 ]; then
    OVERALL_STATUS=FAIL
fi
printf 'Checks: %d, passed: %d, failed: %d\n' "$TOTAL_TESTS" "$PASSED_TESTS" "$FAILED_TESTS" \
    | tee -a "$REPORT_FILE"
cat > "$SUMMARY_FILE" <<EOF_JSON
{
  "mode": "$MODE",
  "total_tests": $TOTAL_TESTS,
  "passed": $PASSED_TESTS,
  "failed": $FAILED_TESTS,
  "overall_status": "$OVERALL_STATUS"
}
EOF_JSON
[ "$FAILED_TESTS" -eq 0 ]
