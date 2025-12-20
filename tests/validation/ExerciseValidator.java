package org.opentsx.tests.validation;

import org.opentsx.data.series.TimeSeriesObject;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Automated validation framework for OpenTSx exercises.
 *
 * Provides utilities to:
 * - Validate exercise outputs
 * - Check statistical correctness
 * - Verify file generation
 * - Compare with expected results
 *
 * Usage:
 *   ExerciseValidator validator = new ExerciseValidator("Episode 2, Exercise 1");
 *   validator.checkFileExists("exercise1_output.csv");
 *   validator.checkStatistic("Mean", actualMean, 24.0, 0.1);
 *   validator.printResults();
 */
public class ExerciseValidator {

    private final String exerciseName;
    private final List<ValidationResult> results;
    private int passCount;
    private int failCount;

    public ExerciseValidator(String exerciseName) {
        this.exerciseName = exerciseName;
        this.results = new ArrayList<>();
        this.passCount = 0;
        this.failCount = 0;
    }

    /**
     * Validation result for a single check
     */
    public static class ValidationResult {
        public final String testName;
        public final boolean passed;
        public final String message;

        public ValidationResult(String testName, boolean passed, String message) {
            this.testName = testName;
            this.passed = passed;
            this.message = message;
        }
    }

    /**
     * Check if a file exists
     */
    public void checkFileExists(String filePath) {
        File file = new File(filePath);
        boolean exists = file.exists();

        if (exists) {
            pass("File exists: " + filePath);
        } else {
            fail("File not found: " + filePath);
        }
    }

    /**
     * Check if a file has expected number of lines
     */
    public void checkFileLineCount(String filePath, int expectedLines, int tolerance) {
        try {
            File file = new File(filePath);
            if (!file.exists()) {
                fail("File not found: " + filePath);
                return;
            }

            int lineCount = countLines(file);
            boolean inRange = Math.abs(lineCount - expectedLines) <= tolerance;

            if (inRange) {
                pass(String.format("File line count: %d (expected %d ±%d)",
                    lineCount, expectedLines, tolerance));
            } else {
                fail(String.format("File line count: %d (expected %d ±%d)",
                    lineCount, expectedLines, tolerance));
            }
        } catch (Exception e) {
            fail("Error checking file: " + e.getMessage());
        }
    }

    /**
     * Check if a statistic is within expected range
     */
    public void checkStatistic(String name, double actual, double expected, double tolerance) {
        boolean inRange = Math.abs(actual - expected) <= tolerance;

        if (inRange) {
            pass(String.format("%s: %.4f (expected %.4f ±%.4f)",
                name, actual, expected, tolerance));
        } else {
            fail(String.format("%s: %.4f (expected %.4f ±%.4f)",
                name, actual, expected, tolerance));
        }
    }

    /**
     * Check if a value is within a range
     */
    public void checkRange(String name, double value, double min, double max) {
        boolean inRange = value >= min && value <= max;

        if (inRange) {
            pass(String.format("%s: %.4f (within [%.4f, %.4f])",
                name, value, min, max));
        } else {
            fail(String.format("%s: %.4f (outside [%.4f, %.4f])",
                name, value, min, max));
        }
    }

    /**
     * Check TimeSeriesObject statistics
     */
    public void checkTimeSeriesStatistics(TimeSeriesObject ts,
                                         Double expectedMean,
                                         Double expectedStddev,
                                         Double tolerance) {
        if (ts == null) {
            fail("TimeSeriesObject is null");
            return;
        }

        if (ts.yValues.size() == 0) {
            fail("TimeSeriesObject is empty");
            return;
        }

        pass("TimeSeriesObject has " + ts.yValues.size() + " points");

        if (expectedMean != null) {
            checkStatistic("Mean", ts.getAvarage(), expectedMean, tolerance);
        }

        if (expectedStddev != null) {
            checkStatistic("StdDev", ts.getStddev(), expectedStddev, tolerance);
        }
    }

    /**
     * Check if two time series are equal within tolerance
     */
    public void checkTimeSeriesEqual(TimeSeriesObject ts1, TimeSeriesObject ts2, double tolerance) {
        if (ts1 == null || ts2 == null) {
            fail("One or both TimeSeriesObjects are null");
            return;
        }

        if (ts1.yValues.size() != ts2.yValues.size()) {
            fail(String.format("Different sizes: %d vs %d",
                ts1.yValues.size(), ts2.yValues.size()));
            return;
        }

        boolean allEqual = true;
        for (int i = 0; i < ts1.yValues.size(); i++) {
            double v1 = (Double)ts1.yValues.elementAt(i);
            double v2 = (Double)ts2.yValues.elementAt(i);

            if (Math.abs(v1 - v2) > tolerance) {
                allEqual = false;
                break;
            }
        }

        if (allEqual) {
            pass("Time series are equal within tolerance " + tolerance);
        } else {
            fail("Time series differ by more than tolerance " + tolerance);
        }
    }

    /**
     * Check boolean condition
     */
    public void check(String testName, boolean condition) {
        if (condition) {
            pass(testName);
        } else {
            fail(testName);
        }
    }

    /**
     * Custom validation with lambda
     */
    public void validate(String testName, ValidationFunction function) {
        try {
            boolean result = function.validate();
            if (result) {
                pass(testName);
            } else {
                fail(testName);
            }
        } catch (Exception e) {
            fail(testName + " - Exception: " + e.getMessage());
        }
    }

    @FunctionalInterface
    public interface ValidationFunction {
        boolean validate() throws Exception;
    }

    /**
     * Record a passing test
     */
    private void pass(String message) {
        results.add(new ValidationResult(message, true, "PASS"));
        passCount++;
    }

    /**
     * Record a failing test
     */
    private void fail(String message) {
        results.add(new ValidationResult(message, false, "FAIL"));
        failCount++;
    }

    /**
     * Print validation results
     */
    public void printResults() {
        System.out.println("\n" + "=".repeat(70));
        System.out.println("Validation Results: " + exerciseName);
        System.out.println("=".repeat(70));

        for (ValidationResult result : results) {
            String status = result.passed ? "✓ PASS" : "✗ FAIL";
            System.out.println(String.format("  [%s] %s", status, result.testName));
        }

        System.out.println("=".repeat(70));
        System.out.println(String.format("Total: %d tests, %d passed, %d failed",
            results.size(), passCount, failCount));

        if (failCount == 0) {
            System.out.println("✓ ALL TESTS PASSED");
        } else {
            System.out.println("✗ SOME TESTS FAILED");
        }
        System.out.println("=".repeat(70) + "\n");
    }

    /**
     * Return true if all tests passed
     */
    public boolean allPassed() {
        return failCount == 0;
    }

    /**
     * Get pass rate as percentage
     */
    public double getPassRate() {
        if (results.isEmpty()) return 0.0;
        return (passCount * 100.0) / results.size();
    }

    /**
     * Helper: Count lines in file
     */
    private int countLines(File file) throws Exception {
        java.io.BufferedReader reader = new java.io.BufferedReader(
            new java.io.FileReader(file));
        int lines = 0;
        while (reader.readLine() != null) lines++;
        reader.close();
        return lines;
    }
}
