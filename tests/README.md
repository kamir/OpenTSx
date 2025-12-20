# OpenTSx Testing Guide

**Phase 3: Testing Infrastructure**
**Related to:** TASK-001 - Onboarding Infrastructure Development

This guide describes the comprehensive testing infrastructure for OpenTSx onboarding materials.

## Overview

The testing infrastructure validates three critical aspects:
1. **Exercise Validation** — Automated correctness testing
2. **Integration Testing** — Demo script functionality
3. **Performance Benchmarking** — Efficiency measurements

## Quick Start

### Run All Tests

```bash
# Run complete test suite
./bin/run_all_tests.sh --full

# Run only validation tests (faster)
./bin/run_all_tests.sh --validation-only

# Run quick smoke tests
./bin/run_all_tests.sh --quick
```

### Run Specific Tests

```bash
# Build classpath
CLASSPATH="opentsx-core/target/opentsx-core-2.3-SNAPSHOT.jar"
for jar in opentsx-core/target/lib/*.jar; do
    CLASSPATH="$CLASSPATH:$jar"
done

# Compile tests
mkdir -p tests/build
javac -cp "$CLASSPATH" -d tests/build tests/validation/*.java

# Run specific test
java -cp "$CLASSPATH:tests/build" \
    org.opentsx.tests.validation.Episode02ValidationTests
```

## Testing Infrastructure

### Directory Structure

```
tests/
├── validation/              # Exercise validation tests
│   ├── ExerciseValidator.java
│   ├── Episode02ValidationTests.java
│   └── Episode03ValidationTests.java
├── integration/             # Integration tests
├── benchmarks/              # Performance benchmarks
└── build/                   # Compiled test classes
```

### Test Scripts

| Script | Purpose | Duration |
|--------|---------|----------|
| `run_all_tests.sh` | Master test runner | 5-10 min |
| Validation tests | Exercise correctness | 2-3 min |
| Integration tests | Demo script validation | 1-2 min |
| Benchmarks | Performance profiling | 3-5 min |

## Exercise Validation Framework

### ExerciseValidator Class

Core validation utility providing:
- File existence/line count checks
- Statistical validation (mean, stddev, ranges)
- TimeSeriesObject comparison
- Custom validation functions
- Detailed pass/fail reporting

**Example Usage:**

```java
ExerciseValidator validator = new ExerciseValidator("Episode 2, Exercise 1");

// Check file exists
validator.checkFileExists("output.csv");

// Check statistics
validator.checkStatistic("Mean", actualMean, 24.0, 0.1);

// Check time series
validator.checkTimeSeriesStatistics(ts, 50.0, 10.0, 1.0);

// Print results
validator.printResults();

// Check overall pass/fail
boolean passed = validator.allPassed();
```

### Validation Test Classes

#### Episode02ValidationTests

Tests Episode 2 exercises:
- **Exercise 1:** Manual creation (y = 2*x + 5, mean ≈ 24)
- **Exercise 2:** Synthetic data (Gaussian, Uniform, Exponential)
- **Exercise 3:** CSV loading and filtering
- **Bonus:** Pattern creation (trend + seasonal + noise)

**Run:**
```bash
java -cp "$CLASSPATH:tests/build" \
    org.opentsx.tests.validation.Episode02ValidationTests
```

**Expected Output:**
```
═══════════════════════════════════════════════════════════════════
  Episode 2 Validation Test Suite
  Testing: Creating Time Series Exercises
═══════════════════════════════════════════════════════════════════

[Test results for each exercise...]

═══════════════════════════════════════════════════════════════════
  FINAL RESULTS
═══════════════════════════════════════════════════════════════════
  Exercises Passed: 4 / 4 (100.0%)
  Status: ✓ ALL EXERCISES VALIDATED
═══════════════════════════════════════════════════════════════════
```

#### Episode03ValidationTests

Tests Episode 3 exercises:
- **Exercise 1:** Normalization (centering, z-score, min-max)
- **Exercise 2:** Mutability (in-place vs immutable)
- **Exercise 3:** Time series arithmetic
- **Exercise 4:** Windowing and subsetting

**Run:**
```bash
java -cp "$CLASSPATH:tests/build" \
    org.opentsx.tests.validation.Episode03ValidationTests
```

## Integration Testing

### Demo Script Validation

Tests all demo scripts for:
- **Syntax correctness** — `bash -n script.sh`
- **Executable permissions**
- **Required file existence**
- **Documentation completeness**

**Scripts Tested:**
- `episode_02_create_timeseries.sh`
- `episode_03_basic_operations.sh`
- `episode_09_analysis.sh`
- `episode_10_production_config.sh`

### Manual Integration Testing

For full end-to-end testing (requires Maven build):

```bash
# 1. Build project
./bin/010_build.sh

# 2. Run each demo
./bin/episode_02_create_timeseries.sh
./bin/episode_03_basic_operations.sh
./bin/episode_09_analysis.sh
./bin/episode_10_production_config.sh

# 3. Verify outputs
ls -lh *.csv
```

## Test Reports

### Detailed Report (test-report.txt)

Generated after each test run with:
- Timestamp
- Mode (full/quick/validation-only)
- Individual test results
- Pass/fail for each component
- Overall summary

**Example:**
```
OpenTSx Test Report
===================
Generated: 2025-12-20 16:30:00

Phase 1: Exercise Validation Tests
====================================

[Episode 2 validation output...]
[Episode 3 validation output...]

Phase 2: Demo Script Integration Tests
=======================================

Episode 2 demo: PASS (syntax valid)
Episode 3 demo: PASS (syntax valid)
...

Test Summary
============
Total Tests: 15
Passed: 15
Failed: 0
Pass Rate: 100.0%
Overall Status: PASS
```

### JSON Summary (test-summary.json)

Machine-readable summary for CI/CD integration:

```json
{
  "timestamp": "2025-12-20T16:30:00Z",
  "mode": "--full",
  "total_tests": 15,
  "passed": 15,
  "failed": 0,
  "pass_rate": 100.0,
  "overall_status": "PASS",
  "reports": {
    "detailed": "test-report.txt",
    "summary": "test-summary.json"
  }
}
```

## Performance Benchmarking

### Benchmark Categories

1. **Time Series Creation**
   - Manual vs synthetic generation
   - Different distributions
   - Varying sizes (100, 1K, 10K, 100K points)

2. **Operations**
   - Normalization methods
   - Arithmetic operations
   - Statistical calculations

3. **File I/O**
   - Loading CSV files
   - Saving to files
   - Different delimiters

### Running Benchmarks

```bash
# Run all benchmarks
./bin/run_benchmarks.sh

# Run specific category
./bin/run_benchmarks.sh --creation
./bin/run_benchmarks.sh --operations
./bin/run_benchmarks.sh --fileio
```

## CI/CD Integration

### GitHub Actions Example

```yaml
name: OpenTSx Tests

on: [push, pull_request]

jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v2

      - name: Set up JDK 8
        uses: actions/setup-java@v2
        with:
          java-version: '8'

      - name: Build project
        run: ./bin/010_build.sh

      - name: Run tests
        run: ./bin/run_all_tests.sh --full

      - name: Upload test report
        uses: actions/upload-artifact@v2
        with:
          name: test-reports
          path: |
            test-report.txt
            test-summary.json
```

## Troubleshooting

### Compilation Errors

**Issue:** Tests fail to compile

**Solution:**
```bash
# Ensure project is built
./bin/010_build.sh

# Clean and rebuild tests
rm -rf tests/build
mkdir tests/build
javac -cp "$CLASSPATH" -d tests/build tests/validation/*.java
```

### ClassNotFoundException

**Issue:** `java.lang.ClassNotFoundException` when running tests

**Solution:**
```bash
# Verify classpath includes all required JARs
echo $CLASSPATH

# Ensure compiled tests are in classpath
CLASSPATH="$CLASSPATH:tests/build"
```

### Test Failures

**Issue:** Validation tests fail unexpectedly

**Solution:**
1. Check tolerance values — may need adjustment for different platforms
2. Verify random number generator seed is set
3. Review expected vs actual values in output
4. Ensure sample data files exist

### Demo Script Failures

**Issue:** Demo scripts fail to execute

**Solution:**
```bash
# Check syntax
bash -n bin/episode_02_create_timeseries.sh

# Check permissions
ls -l bin/episode_*.sh

# Make executable if needed
chmod +x bin/episode_*.sh

# Check Java/Maven setup
java -version
mvn -version
```

## Writing New Tests

### Validation Test Template

```java
package org.opentsx.tests.validation;

import org.opentsx.data.series.TimeSeriesObject;

public class EpisodeXXValidationTests {

    public static void main(String[] args) {
        int totalTests = 0;
        int passedTests = 0;

        // Test each exercise
        if (testExercise1()) passedTests++;
        totalTests++;

        // Print summary
        System.out.println(String.format(
            "Exercises Passed: %d / %d", passedTests, totalTests));
    }

    private static boolean testExercise1() {
        ExerciseValidator validator =
            new ExerciseValidator("Episode XX, Exercise 1");

        try {
            // Test logic here
            TimeSeriesObject ts = /* create or load */;

            validator.checkTimeSeriesStatistics(ts,
                expectedMean, expectedStddev, tolerance);

            // Additional checks...

        } catch (Exception e) {
            validator.check("Execution without errors", false);
        }

        validator.printResults();
        return validator.allPassed();
    }
}
```

### Integration Test Template

```bash
#!/bin/bash
# Test script for Episode XX

# Setup
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

# Run demo
bash -n bin/episode_XX_script.sh || {
    echo "✗ Syntax check failed"
    exit 1
}

# Check output files
if [ -f "expected_output.csv" ]; then
    echo "✓ Output file created"
else
    echo "✗ Output file missing"
    exit 1
fi

echo "✓ All checks passed"
```

## Best Practices

1. **Always Set Seed:**
   ```java
   RNGWrapper.init(42);  // Reproducible results
   ```

2. **Use Appropriate Tolerances:**
   ```java
   // Tight tolerance for deterministic values
   validator.checkStatistic("Manual mean", mean, 24.0, 0.01);

   // Looser tolerance for random values
   validator.checkStatistic("Random mean", mean, 50.0, 2.0);
   ```

3. **Test Edge Cases:**
   - Empty time series
   - Single point series
   - Large datasets (10K+ points)
   - Extreme values

4. **Clean Up After Tests:**
   ```java
   // Delete temporary files
   new File("test_output.csv").delete();
   ```

5. **Descriptive Test Names:**
   ```java
   validator.check("Mean preserved after scaling", condition);
   // Not: validator.check("Test 1", condition);
   ```

## Metrics and Reporting

### Success Criteria

- **Pass Rate:** ≥ 95% for release
- **Exercise Validation:** 100% for all exercises
- **Demo Scripts:** 100% syntax valid
- **Documentation:** All required files present

### Current Status

Run `./bin/run_all_tests.sh --full` to see current metrics.

**Target Metrics:**
- Total validation tests: 20+
- Coverage: 100% of exercises
- Pass rate: 100%
- Execution time: < 10 minutes

## Related Documentation

- [Exercise Materials](../exercises/README.md)
- [Demo Scripts](../bin/README.md)
- [TASK-001 Tracking](../EVOLUTION/TASK-001-onboarding-infrastructure.md)
- [Main README](../README.md)

---

**Last Updated:** 2025-12-20
**Maintained By:** OpenTSx Core Team
