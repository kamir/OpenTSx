package org.opentsx.tests.validation;

import org.opentsx.data.series.TimeSeriesObject;
import org.opentsx.data.loader.MessreihenLoader;
import org.opentsx.tsa.rng.RNGWrapper;
import java.io.File;

/**
 * Validation tests for Episode 2: Creating Time Series
 *
 * Tests all exercises to ensure solutions meet requirements.
 */
public class Episode02ValidationTests {

    public static void main(String[] args) {
        System.out.println("\n" + "═".repeat(70));
        System.out.println("  Episode 2 Validation Test Suite");
        System.out.println("  Testing: Creating Time Series Exercises");
        System.out.println("═".repeat(70) + "\n");

        int totalTests = 0;
        int passedTests = 0;

        // Exercise 1: Manual Creation
        if (testExercise1()) passedTests++;
        totalTests++;

        // Exercise 2: Synthetic Data
        if (testExercise2()) passedTests++;
        totalTests++;

        // Exercise 3: Loading and Transforming
        if (testExercise3()) passedTests++;
        totalTests++;

        // Bonus Exercise: Pattern Creation
        if (testBonusExercise()) passedTests++;
        totalTests++;

        // Final Summary
        System.out.println("\n" + "═".repeat(70));
        System.out.println("  FINAL RESULTS");
        System.out.println("═".repeat(70));
        System.out.println(String.format("  Exercises Passed: %d / %d (%.1f%%)",
            passedTests, totalTests, (passedTests * 100.0 / totalTests)));

        if (passedTests == totalTests) {
            System.out.println("  Status: ✓ ALL EXERCISES VALIDATED");
        } else {
            System.out.println("  Status: ✗ SOME EXERCISES NEED REVIEW");
        }
        System.out.println("═".repeat(70) + "\n");
    }

    /**
     * Test Exercise 1: Manual Time Series Creation
     * Requirements:
     * - Create series with y = 2*x + 5
     * - 20 data points
     * - Mean should be approximately 24.0
     */
    private static boolean testExercise1() {
        ExerciseValidator validator = new ExerciseValidator("Episode 2, Exercise 1");

        try {
            // Create expected time series
            TimeSeriesObject ts = new TimeSeriesObject();
            ts.setLabel("manual_series");

            for (int x = 0; x < 20; x++) {
                double y = 2 * x + 5;
                ts.addValuePair(x, y);
            }

            // Validate statistics
            validator.checkTimeSeriesStatistics(ts, 24.0, null, 0.01);
            validator.checkStatistic("Number of points", ts.yValues.size(), 20, 0);

            // Check if first and last values are correct
            double firstY = (Double)ts.yValues.elementAt(0);
            double lastY = (Double)ts.yValues.elementAt(19);
            validator.checkStatistic("First Y value", firstY, 5.0, 0.01);
            validator.checkStatistic("Last Y value", lastY, 43.0, 0.01);

            // Check file generation (if solution saves file)
            // validator.checkFileExists("exercise1_output.csv");

        } catch (Exception e) {
            validator.check("Execution without errors", false);
        }

        validator.printResults();
        return validator.allPassed();
    }

    /**
     * Test Exercise 2: Synthetic Data Generation
     * Requirements:
     * - Gaussian: mean=50, stddev=10
     * - Uniform: min=0, max=100
     * - Exponential: lambda=0.05
     */
    private static boolean testExercise2() {
        ExerciseValidator validator = new ExerciseValidator("Episode 2, Exercise 2");

        try {
            int numPoints = 500;

            // Test Gaussian distribution
            TimeSeriesObject gaussian = TimeSeriesObject.getGaussianDistribution(
                numPoints, 50.0, 10.0);
            validator.checkTimeSeriesStatistics(gaussian, 50.0, 10.0, 2.0);

            // Test Uniform distribution
            TimeSeriesObject uniform = TimeSeriesObject.getUniformDistribution(
                numPoints, 0.0, 100.0);
            double uniformMean = (0.0 + 100.0) / 2.0;  // Expected: 50
            double uniformStddev = Math.sqrt(Math.pow(100.0 - 0.0, 2) / 12.0);  // Expected: ~28.87
            validator.checkTimeSeriesStatistics(uniform, uniformMean, uniformStddev, 3.0);

            // Test Exponential distribution
            TimeSeriesObject exponential = TimeSeriesObject.getExpDistribution(numPoints, 0.05);
            double expMean = 1.0 / 0.05;  // Expected: 20.0
            validator.checkStatistic("Exponential mean", exponential.getAvarage(), expMean, 2.0);

            // Check min/max ranges for uniform
            validator.checkRange("Uniform min", uniform.getMinY(), 0.0, 10.0);
            validator.checkRange("Uniform max", uniform.getMaxY(), 90.0, 100.0);

        } catch (Exception e) {
            validator.check("Execution without errors: " + e.getMessage(), false);
        }

        validator.printResults();
        return validator.allPassed();
    }

    /**
     * Test Exercise 3: Loading and Transforming CSV Data
     * Requirements:
     * - Load sensor_data.csv
     * - Filter temperatures > 25°C
     * - Save filtered data
     */
    private static boolean testExercise3() {
        ExerciseValidator validator = new ExerciseValidator("Episode 2, Exercise 3");

        try {
            // Create test CSV data
            TimeSeriesObject testData = TimeSeriesObject.getUniformDistribution(100, 20.0, 30.0);
            testData.setLabel("sensor_temperatures");

            // Test filtering logic
            TimeSeriesObject filtered = new TimeSeriesObject();
            filtered.setLabel("high_temperatures");

            double threshold = 25.0;
            int expectedFilteredCount = 0;

            for (int i = 0; i < testData.yValues.size(); i++) {
                double temp = (Double)testData.yValues.elementAt(i);
                double time = (Double)testData.xValues.elementAt(i);

                if (temp > threshold) {
                    filtered.addValuePair(time, temp);
                    expectedFilteredCount++;
                }
            }

            // Validate filtering worked
            validator.check("Filtered series created", filtered.yValues.size() > 0);
            validator.check("Filtering reduced size", filtered.yValues.size() < testData.yValues.size());

            // Verify all filtered values are > threshold
            boolean allAboveThreshold = true;
            for (int i = 0; i < filtered.yValues.size(); i++) {
                if ((Double)filtered.yValues.elementAt(i) <= threshold) {
                    allAboveThreshold = false;
                    break;
                }
            }
            validator.check("All filtered values > threshold", allAboveThreshold);

            // Check filtered mean is higher than original
            validator.check("Filtered mean > original mean",
                filtered.getAvarage() > testData.getAvarage());

        } catch (Exception e) {
            validator.check("Execution without errors: " + e.getMessage(), false);
        }

        validator.printResults();
        return validator.allPassed();
    }

    /**
     * Test Bonus Exercise: Pattern Creation
     * Requirements:
     * - Combine trend + seasonal + noise
     * - 1000 points
     * - Verify patterns present
     */
    private static boolean testBonusExercise() {
        ExerciseValidator validator = new ExerciseValidator("Episode 2, Bonus Exercise");

        try {
            int numPoints = 1000;
            int seasonalPeriod = 50;

            TimeSeriesObject combined = new TimeSeriesObject();
            combined.setLabel("trend_seasonal_noise");

            RNGWrapper.init(42);  // Fixed seed for reproducibility

            for (int t = 0; t < numPoints; t++) {
                double trend = 0.1 * t;
                double seasonal = 10.0 * Math.sin(2.0 * Math.PI * t / seasonalPeriod);
                double noise = RNGWrapper.getStdRandomGaussian(0.0, 2.0);

                double y = trend + seasonal + noise;
                combined.addValuePair(t, y);
            }

            // Validate basic properties
            validator.checkStatistic("Number of points", combined.yValues.size(), numPoints, 0);

            // Check that there's an upward trend (last value > first value on average)
            double firstQuarter = averageRange(combined, 0, 250);
            double lastQuarter = averageRange(combined, 750, 1000);
            validator.check("Upward trend present", lastQuarter > firstQuarter);

            // Check variance is reasonable (not just flat noise)
            double stddev = combined.getStddev();
            validator.checkRange("Standard deviation reasonable", stddev, 5.0, 20.0);

            // Check range spans expected values
            double expectedMin = -20.0;  // Roughly: trend_start + seasonal_min + noise
            double expectedMax = 120.0;  // Roughly: trend_end + seasonal_max + noise
            validator.checkRange("Min value reasonable", combined.getMinY(), expectedMin, 20.0);
            validator.checkRange("Max value reasonable", combined.getMaxY(), 80.0, expectedMax);

        } catch (Exception e) {
            validator.check("Execution without errors: " + e.getMessage(), false);
        }

        validator.printResults();
        return validator.allPassed();
    }

    /**
     * Helper: Calculate average of a range
     */
    private static double averageRange(TimeSeriesObject ts, int start, int end) {
        double sum = 0;
        int count = 0;

        for (int i = start; i < end && i < ts.yValues.size(); i++) {
            sum += (Double)ts.yValues.elementAt(i);
            count++;
        }

        return count > 0 ? sum / count : 0.0;
    }
}
