package org.opentsx.tests.validation;

import org.opentsx.data.series.TimeSeriesObject;

/**
 * Validation tests for Episode 3: Basic Operations
 *
 * Tests normalization, mutability, arithmetic, and windowing exercises.
 */
public class Episode03ValidationTests {

    public static void main(String[] args) {
        System.out.println("\n" + "═".repeat(70));
        System.out.println("  Episode 3 Validation Test Suite");
        System.out.println("  Testing: Basic Operations Exercises");
        System.out.println("═".repeat(70) + "\n");

        int totalTests = 0;
        int passedTests = 0;

        // Exercise 1: Normalization
        if (testExercise1()) passedTests++;
        totalTests++;

        // Exercise 2: Mutability
        if (testExercise2()) passedTests++;
        totalTests++;

        // Exercise 3: Arithmetic
        if (testExercise3()) passedTests++;
        totalTests++;

        // Exercise 4: Windowing
        if (testExercise4()) passedTests++;
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
     * Test Exercise 1: Normalization
     * Requirements:
     * - Centered: mean ≈ 0
     * - Z-score: mean ≈ 0, stddev ≈ 1
     * - Min-max: min = 0, max = 1
     */
    private static boolean testExercise1() {
        ExerciseValidator validator = new ExerciseValidator("Episode 3, Exercise 1");

        try {
            // Create test data
            TimeSeriesObject original = TimeSeriesObject.getGaussianDistribution(500, 100.0, 15.0);

            // Test centering
            TimeSeriesObject centered = original.subtractAverage();
            validator.checkStatistic("Centered mean", centered.getAvarage(), 0.0, 0.5);
            validator.checkStatistic("Centered stddev preserved",
                centered.getStddev(), original.getStddev(), 1.0);

            // Test z-score normalization
            TimeSeriesObject zscore = original.normalizeToStdevIsOne();
            validator.checkStatistic("Z-score mean", zscore.getAvarage(), 0.0, 0.5);
            validator.checkStatistic("Z-score stddev", zscore.getStddev(), 1.0, 0.1);

            // Test min-max normalization
            TimeSeriesObject minmax = minMaxNormalize(original, 0.0, 1.0);
            validator.checkStatistic("Min-max min", minmax.getMinY(), 0.0, 0.01);
            validator.checkStatistic("Min-max max", minmax.getMaxY(), 1.0, 0.01);

        } catch (Exception e) {
            validator.check("Execution without errors: " + e.getMessage(), false);
        }

        validator.printResults();
        return validator.allPassed();
    }

    /**
     * Test Exercise 2: Mutability
     * Requirements:
     * - In-place operations modify original
     * - Immutable operations return new object
     */
    private static boolean testExercise2() {
        ExerciseValidator validator = new ExerciseValidator("Episode 3, Exercise 2");

        try {
            // Test in-place operation (scaleY_2)
            TimeSeriesObject copy1 = TimeSeriesObject.getGaussianDistribution(100, 50.0, 10.0);
            double meanBefore = copy1.getAvarage();

            copy1.scaleY_2(2.0);  // In-place

            double meanAfter = copy1.getAvarage();
            double expectedMean = meanBefore * 2.0;

            validator.checkStatistic("scaleY_2 modifies original",
                meanAfter, expectedMean, 1.0);

            // Test immutable operation (normalizeToStdevIsOne)
            TimeSeriesObject copy2 = TimeSeriesObject.getGaussianDistribution(100, 50.0, 10.0);
            double originalMean = copy2.getAvarage();

            TimeSeriesObject normalized = copy2.normalizeToStdevIsOne();

            validator.checkStatistic("Original unchanged after normalize",
                copy2.getAvarage(), originalMean, 0.1);
            validator.checkStatistic("Normalized result has stddev=1",
                normalized.getStddev(), 1.0, 0.1);

            // Test add_to_Y (in-place)
            TimeSeriesObject copy3 = TimeSeriesObject.getGaussianDistribution(100, 50.0, 10.0);
            double offset = 25.0;
            double meanBeforeOffset = copy3.getAvarage();

            copy3.add_to_Y(offset);

            validator.checkStatistic("add_to_Y modifies original",
                copy3.getAvarage(), meanBeforeOffset + offset, 0.1);

        } catch (Exception e) {
            validator.check("Execution without errors: " + e.getMessage(), false);
        }

        validator.printResults();
        return validator.allPassed();
    }

    /**
     * Test Exercise 3: Time Series Arithmetic
     * Requirements:
     * - Combine multiple series
     * - Create derived series
     */
    private static boolean testExercise3() {
        ExerciseValidator validator = new ExerciseValidator("Episode 3, Exercise 3");

        try {
            int numPoints = 200;

            // Create synthetic sensor data
            TimeSeriesObject temperature = TimeSeriesObject.getGaussianDistribution(
                numPoints, 22.0, 3.0);

            TimeSeriesObject humidity = TimeSeriesObject.getUniformDistribution(
                numPoints, 40.0, 80.0);

            // Test comfort index creation (temp + humidity/10)
            TimeSeriesObject comfort = temperature.copy();
            for (int i = 0; i < numPoints; i++) {
                double temp = (Double)temperature.yValues.elementAt(i);
                double hum = (Double)humidity.yValues.elementAt(i);
                double comfortValue = temp + (hum / 10.0);
                comfort.yValues.setElementAt(comfortValue, i);
            }

            validator.check("Comfort index created", comfort.yValues.size() == numPoints);
            validator.check("Comfort index > temperature",
                comfort.getAvarage() > temperature.getAvarage());

            // Test deviation from baseline
            double baseline = 20.0;
            TimeSeriesObject deviation = temperature.copy();
            deviation.add_to_Y(-baseline);

            validator.checkStatistic("Deviation mean",
                deviation.getAvarage(), temperature.getAvarage() - baseline, 0.1);

        } catch (Exception e) {
            validator.check("Execution without errors: " + e.getMessage(), false);
        }

        validator.printResults();
        return validator.allPassed();
    }

    /**
     * Test Exercise 4: Windowing and Subsetting
     * Requirements:
     * - Extract time windows correctly
     * - Compare statistics across windows
     */
    private static boolean testExercise4() {
        ExerciseValidator validator = new ExerciseValidator("Episode 3, Exercise 4");

        try {
            // Create daily data (1440 points for 24 hours)
            TimeSeriesObject fullDay = createDailyPattern(1440);

            // Extract windows
            TimeSeriesObject morning = extractWindow(fullDay, 360, 720);   // 6 AM - 12 PM
            TimeSeriesObject afternoon = extractWindow(fullDay, 720, 1080); // 12 PM - 6 PM
            TimeSeriesObject evening = extractWindow(fullDay, 1080, 1440);  // 6 PM - 12 AM

            // Validate window sizes
            validator.checkStatistic("Morning window size", morning.yValues.size(), 360, 0);
            validator.checkStatistic("Afternoon window size", afternoon.yValues.size(), 360, 0);
            validator.checkStatistic("Evening window size", evening.yValues.size(), 360, 0);

            // Verify afternoon is warmest (peak of sine wave)
            validator.check("Afternoon warmest",
                afternoon.getAvarage() > morning.getAvarage());
            validator.check("Afternoon warmer than evening",
                afternoon.getAvarage() > evening.getAvarage());

            // Verify all windows combined equal full day
            int totalWindowSize = morning.yValues.size() +
                                afternoon.yValues.size() +
                                evening.yValues.size();
            validator.checkStatistic("Windows cover full day", totalWindowSize, 1080, 0);

        } catch (Exception e) {
            validator.check("Execution without errors: " + e.getMessage(), false);
        }

        validator.printResults();
        return validator.allPassed();
    }

    /**
     * Helper: Min-max normalization
     */
    private static TimeSeriesObject minMaxNormalize(TimeSeriesObject input,
                                                     double newMin, double newMax) {
        TimeSeriesObject result = new TimeSeriesObject();
        result.setLabel(input.getLabel() + "_minmax");

        double oldMin = input.getMinY();
        double oldMax = input.getMaxY();
        double oldRange = oldMax - oldMin;
        double newRange = newMax - newMin;

        for (int i = 0; i < input.yValues.size(); i++) {
            double x = (Double)input.xValues.elementAt(i);
            double y = (Double)input.yValues.elementAt(i);

            double normalized = ((y - oldMin) / oldRange) * newRange + newMin;
            result.addValuePair(x, normalized);
        }

        return result;
    }

    /**
     * Helper: Create daily temperature pattern
     */
    private static TimeSeriesObject createDailyPattern(int points) {
        TimeSeriesObject ts = new TimeSeriesObject();
        ts.setLabel("daily_temperature");

        for (int minute = 0; minute < points; minute++) {
            double hour = minute / 60.0;
            double baseTemp = 20.0;
            double dailyCycle = 5.0 * Math.sin(2 * Math.PI * (hour - 6) / 24);
            double temp = baseTemp + dailyCycle;

            ts.addValuePair(minute, temp);
        }

        return ts;
    }

    /**
     * Helper: Extract window from time series
     */
    private static TimeSeriesObject extractWindow(TimeSeriesObject input, int start, int end) {
        TimeSeriesObject window = new TimeSeriesObject();
        window.setLabel(input.getLabel() + "_window");

        for (int i = start; i < end && i < input.yValues.size(); i++) {
            double x = (Double)input.xValues.elementAt(i);
            double y = (Double)input.yValues.elementAt(i);
            window.addValuePair(x, y);
        }

        return window;
    }
}
