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
TESTS=0
TESTS_FAILED=0
SYNTAX_CHECKS=0
SYNTAX_FAILED=0
FILE_CHECKS=0
FILES_MISSING=0
printf 'OpenTSx validation report\nMode: %s\n' "$MODE" > "$REPORT_FILE"

report() {
    printf '%s\n' "$1" | tee -a "$REPORT_FILE"
}

if [ "$MODE" != "--demo-only" ]; then
    TESTS=1
    if mvn -B -ntp -pl opentsx-core -am test -Dtest=ExerciseValidationTest \
            -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | tee -a "$REPORT_FILE"; then
        report "PASS: Maven validation"
    else
        TESTS_FAILED=1
        report "FAIL: Maven validation failed"
    fi
fi

if [ "$MODE" = "--full" ] || [ "$MODE" = "--demo-only" ]; then
    for script in bin/episode_02_create_timeseries.sh bin/episode_03_basic_operations.sh \
            bin/episode_09_analysis.sh bin/episode_10_production_config.sh; do
        SYNTAX_CHECKS=$((SYNTAX_CHECKS + 1))
        if bash -n "$script" >> "$REPORT_FILE" 2>&1; then
            report "SYNTAX OK: $script"
        else
            SYNTAX_FAILED=$((SYNTAX_FAILED + 1))
            report "SYNTAX FAIL: $script"
        fi
    done
fi

if [ "$MODE" = "--full" ]; then
    for doc in README.md docs/user-manual/README.md docs/devguide/infrastructure/local-development.md \
            exercises/README.md bin/README.md; do
        FILE_CHECKS=$((FILE_CHECKS + 1))
        if [ -f "$doc" ]; then
            report "FILE OK: $doc"
        else
            FILES_MISSING=$((FILES_MISSING + 1))
            report "FILE MISSING: $doc"
        fi
    done
fi

OVERALL_STATUS=PASS
if [ $((TESTS_FAILED + SYNTAX_FAILED + FILES_MISSING)) -ne 0 ]; then
    OVERALL_STATUS=FAIL
fi
report "Tests: $TESTS, passed: $((TESTS - TESTS_FAILED)), failed: $TESTS_FAILED"
report "Syntax checks (bash -n, the demos do not run): $SYNTAX_CHECKS, passed: $((SYNTAX_CHECKS - SYNTAX_FAILED)), failed: $SYNTAX_FAILED"
report "Required files: $FILE_CHECKS, present: $((FILE_CHECKS - FILES_MISSING)), missing: $FILES_MISSING"
cat > "$SUMMARY_FILE" <<EOF_JSON
{
  "mode": "$MODE",
  "tests": {"total": $TESTS, "passed": $((TESTS - TESTS_FAILED)), "failed": $TESTS_FAILED},
  "syntax_checks": {"total": $SYNTAX_CHECKS, "passed": $((SYNTAX_CHECKS - SYNTAX_FAILED)), "failed": $SYNTAX_FAILED},
  "file_checks": {"total": $FILE_CHECKS, "passed": $((FILE_CHECKS - FILES_MISSING)), "failed": $FILES_MISSING},
  "overall_status": "$OVERALL_STATUS"
}
EOF_JSON
[ "$OVERALL_STATUS" = PASS ]
