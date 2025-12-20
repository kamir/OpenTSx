package org.opentsx.demo.onboarding;

import org.opentsx.data.generator.RNGWrapper;
import org.opentsx.data.series.TimeSeriesObject;

import java.io.File;
import java.io.IOException;

/**
 * Episode 9 Demo: Time Series Analysis & Statistical Methods
 *
 * Learning Objectives:
 * - Calculate moving averages for smoothing
 * - Compute autocorrelation for pattern detection
 * - Detect trends and seasonality
 * - Apply statistical tests
 * - Feature engineering for time series
 *
 * Target Track: Software Engineer (SWE) & Time Series Expert (TSx)
 * Episode: E09 - Time Series Analysis & ML
 * Duration: ~25 minutes
 *
 * Prerequisites:
 * - Episodes 1-8 completed
 * - Understanding of basic statistics
 * - Familiarity with autocorrelation concepts
 *
 * @author OpenTSx Onboarding Team
 * @version 1.0
 */
public class TimeSeriesAnalysis {

    public static void main(String[] args) throws IOException {

        System.out.println("===========================================");
        System.out.println("OpenTSx Demo: Time Series Analysis");
        System.out.println("Episode 9 - Statistical Methods");
        System.out.println("===========================================\n");

        // Initialize RNG
        RNGWrapper.init();

        // =====================================================
        // TASK 1: Create sample time series with patterns
        // =====================================================
        System.out.println("TASK 1: Create sample time series with patterns");
        System.out.println("-----------------------------------------------");

        // Create a time series with trend and noise
        TimeSeriesObject trendSeries = new TimeSeriesObject();
        trendSeries.setLabel("trend_with_noise");

        for (int i = 0; i < 500; i++) {
            // Linear trend + Gaussian noise
            double trendValue = 10.0 + 0.05 * i;
            double noise = RNGWrapper.getStdRandomGaussian(0.0, 2.0);
            trendSeries.addValuePair(i, trendValue + noise);
        }

        System.out.println("Created time series with linear trend:");
        System.out.println("  Length: " + trendSeries.yValues.size());
        System.out.println("  Start value (avg): " + String.format("%.2f",
                          average(trendSeries, 0, 10)));
        System.out.println("  End value (avg): " + String.format("%.2f",
                          average(trendSeries, 490, 500)));
        System.out.println("  Trend visible: " +
                          (average(trendSeries, 490, 500) > average(trendSeries, 0, 10) ? "✓" : "✗"));
        System.out.println();

        // =====================================================
        // TASK 2: Moving Average for Smoothing
        // =====================================================
        System.out.println("TASK 2: Moving average for smoothing");
        System.out.println("------------------------------------");

        // Apply different window sizes
        int[] windowSizes = {5, 10, 20};

        for (int window : windowSizes) {
            TimeSeriesObject smoothed = trendSeries.setBinningX_average(window);
            smoothed.setLabel("smoothed_window_" + window);

            System.out.println("Window size " + window + ":");
            System.out.println("  Original length: " + trendSeries.yValues.size());
            System.out.println("  Smoothed length: " + smoothed.yValues.size());
            System.out.println("  Original std dev: " + String.format("%.4f",
                              trendSeries.getStddev()));
            System.out.println("  Smoothed std dev: " + String.format("%.4f",
                              smoothed.getStddev()));
            System.out.println("  Noise reduction: " + String.format("%.1f%%",
                              (1 - smoothed.getStddev() / trendSeries.getStddev()) * 100));
            System.out.println();
        }

        // =====================================================
        // TASK 3: Trend Detection and Removal
        // =====================================================
        System.out.println("TASK 3: Trend detection and removal");
        System.out.println("-----------------------------------");

        // Simple linear trend estimation
        double firstAvg = average(trendSeries, 0, 50);
        double lastAvg = average(trendSeries, 450, 500);
        double estimatedSlope = (lastAvg - firstAvg) / 500;

        System.out.println("Linear trend estimation:");
        System.out.println("  First 50 points average: " + String.format("%.2f", firstAvg));
        System.out.println("  Last 50 points average: " + String.format("%.2f", lastAvg));
        System.out.println("  Estimated slope: " + String.format("%.4f", estimatedSlope));
        System.out.println("  Expected slope: 0.0500");
        System.out.println();

        // Detrend the series
        TimeSeriesObject detrended = new TimeSeriesObject();
        detrended.setLabel("detrended_series");

        for (int i = 0; i < trendSeries.yValues.size(); i++) {
            double originalValue = (Double)trendSeries.yValues.elementAt(i);
            double trendValue = firstAvg + estimatedSlope * i;
            double detrendedValue = originalValue - trendValue;
            detrended.addValuePair(i, detrendedValue);
        }

        System.out.println("Detrending results:");
        System.out.println("  Original mean: " + String.format("%.2f", trendSeries.getAvarage()));
        System.out.println("  Detrended mean: " + String.format("%.2f", detrended.getAvarage()));
        System.out.println("  (Should be close to 0)");
        System.out.println();

        // =====================================================
        // TASK 4: Autocorrelation Analysis
        // =====================================================
        System.out.println("TASK 4: Autocorrelation analysis");
        System.out.println("--------------------------------");

        // Create a series with known periodicity
        TimeSeriesObject periodic = new TimeSeriesObject();
        periodic.setLabel("periodic_signal");

        int period = 24; // Daily pattern
        for (int i = 0; i < 500; i++) {
            double value = 10.0 + 5.0 * Math.sin(2 * Math.PI * i / period);
            double noise = RNGWrapper.getStdRandomGaussian(0.0, 1.0);
            periodic.addValuePair(i, value + noise);
        }

        System.out.println("Created periodic time series:");
        System.out.println("  Period: " + period + " points");
        System.out.println("  Length: " + periodic.yValues.size());
        System.out.println();

        // Simple autocorrelation at lag = period
        double acfAtPeriod = simpleAutocorrelation(periodic, period);
        System.out.println("Autocorrelation at lag " + period + ": " +
                          String.format("%.4f", acfAtPeriod));
        System.out.println("  (Should be positive, indicating periodicity)");
        System.out.println();

        // =====================================================
        // TASK 5: Statistical Summary
        // =====================================================
        System.out.println("TASK 5: Comprehensive statistical summary");
        System.out.println("-----------------------------------------");

        TimeSeriesObject sample = TimeSeriesObject.getGaussianDistribution(1000, 50.0, 10.0);
        sample.setLabel("sample_data");

        printStatisticalSummary(sample);

        // =====================================================
        // TASK 6: Change Point Detection (Simple)
        // =====================================================
        System.out.println("\nTASK 6: Simple change point detection");
        System.out.println("-------------------------------------");

        // Create series with regime change
        TimeSeriesObject regimeChange = new TimeSeriesObject();
        regimeChange.setLabel("regime_change");

        for (int i = 0; i < 300; i++) {
            double value;
            if (i < 150) {
                value = RNGWrapper.getStdRandomGaussian(10.0, 2.0);
            } else {
                value = RNGWrapper.getStdRandomGaussian(20.0, 2.0);
            }
            regimeChange.addValuePair(i, value);
        }

        // Detect change using sliding window means
        int windowSize = 30;
        System.out.println("Sliding window change detection:");
        System.out.println("  Window size: " + windowSize);

        double maxDiff = 0;
        int changePoint = 0;

        for (int i = windowSize; i < regimeChange.yValues.size() - windowSize; i++) {
            double beforeMean = average(regimeChange, i - windowSize, i);
            double afterMean = average(regimeChange, i, i + windowSize);
            double diff = Math.abs(afterMean - beforeMean);

            if (diff > maxDiff) {
                maxDiff = diff;
                changePoint = i;
            }
        }

        System.out.println("  Detected change point: " + changePoint);
        System.out.println("  Actual change point: 150");
        System.out.println("  Detection accuracy: " +
                          (Math.abs(changePoint - 150) < 20 ? "Good ✓" : "Needs tuning"));
        System.out.println();

        // =====================================================
        // TASK 7: Export for Advanced Analysis
        // =====================================================
        System.out.println("TASK 7: Export for advanced analysis");
        System.out.println("------------------------------------");

        String outputDir = "data/demo_output/analysis/";
        new java.io.File(outputDir).mkdirs();

        // Export various series for external analysis
        trendSeries.writeToFile(new File(outputDir + "trend_series.csv"), ',');
        detrended.writeToFile(new File(outputDir + "detrended.csv"), ',');
        periodic.writeToFile(new File(outputDir + "periodic.csv"), ',');
        regimeChange.writeToFile(new File(outputDir + "regime_change.csv"), ',');

        System.out.println("Exported time series to: " + outputDir);
        System.out.println("  - trend_series.csv (for trend analysis)");
        System.out.println("  - detrended.csv (trend removed)");
        System.out.println("  - periodic.csv (for ACF/spectral analysis)");
        System.out.println("  - regime_change.csv (for change point detection)");
        System.out.println("\nAdvanced analysis in R:");
        System.out.println("  library(forecast)");
        System.out.println("  data <- read.csv('periodic.csv')");
        System.out.println("  acf(data$value)");
        System.out.println("  spectrum(data$value)");
        System.out.println();

        // =====================================================
        // Summary and Next Steps
        // =====================================================
        System.out.println("===========================================");
        System.out.println("Demo Completed Successfully!");
        System.out.println("===========================================");
        System.out.println("\nKey Takeaways:");
        System.out.println("✓ Applied moving averages for smoothing");
        System.out.println("✓ Detected and removed linear trends");
        System.out.println("✓ Analyzed periodic patterns");
        System.out.println("✓ Computed autocorrelation");
        System.out.println("✓ Detected change points");
        System.out.println("✓ Generated comprehensive statistical summaries");
        System.out.println("\nNext Steps:");
        System.out.println("→ Episode 10: Production Deployment & Best Practices");
        System.out.println("  Learn to deploy time series analytics at scale");
        System.out.println("\nExercise:");
        System.out.println("1. Create a time series with seasonal pattern (period=7)");
        System.out.println("2. Add trend and noise");
        System.out.println("3. Decompose into trend, seasonal, and residual components");
        System.out.println("4. Verify autocorrelation peak at lag=7");
        System.out.println("5. Export and visualize in R/Python");
        System.out.println();
    }

    // =====================================================
    // Helper Methods
    // =====================================================

    /**
     * Calculate average of time series in a range
     */
    private static double average(TimeSeriesObject ts, int start, int end) {
        double sum = 0;
        int count = 0;
        for (int i = start; i < end && i < ts.yValues.size(); i++) {
            sum += (Double)ts.yValues.elementAt(i);
            count++;
        }
        return count > 0 ? sum / count : 0.0;
    }

    /**
     * Simple autocorrelation calculation at a specific lag
     */
    private static double simpleAutocorrelation(TimeSeriesObject ts, int lag) {
        double mean = ts.getAvarage();
        double variance = ts.getStddev() * ts.getStddev();

        double sum = 0;
        int count = 0;

        for (int i = 0; i < ts.yValues.size() - lag; i++) {
            sum += ((Double)ts.yValues.elementAt(i) - mean) * ((Double)ts.yValues.elementAt(i + lag) - mean);
            count++;
        }

        return count > 0 ? (sum / count) / variance : 0.0;
    }

    /**
     * Print comprehensive statistical summary
     */
    private static void printStatisticalSummary(TimeSeriesObject ts) {
        System.out.println("Statistical Summary for: " + ts.getLabel());
        System.out.println("  Count: " + ts.yValues.size());
        System.out.println("  Mean: " + String.format("%.4f", ts.getAvarage()));
        System.out.println("  Std Dev: " + String.format("%.4f", ts.getStddev()));
        System.out.println("  Min: " + String.format("%.4f", ts.getMinY()));
        System.out.println("  Max: " + String.format("%.4f", ts.getMaxY()));
        System.out.println("  Range: " + String.format("%.4f", ts.getMaxY() - ts.getMinY()));

        // Quartiles (approximate)
        double q1 = ts.getMinY() + 0.25 * (ts.getMaxY() - ts.getMinY());
        double median = ts.getMinY() + 0.5 * (ts.getMaxY() - ts.getMinY());
        double q3 = ts.getMinY() + 0.75 * (ts.getMaxY() - ts.getMinY());

        System.out.println("  25th percentile (approx): " + String.format("%.4f", q1));
        System.out.println("  50th percentile (approx): " + String.format("%.4f", median));
        System.out.println("  75th percentile (approx): " + String.format("%.4f", q3));
        System.out.println("  Coefficient of Variation: " +
                          String.format("%.4f", ts.getStddev() / ts.getAvarage()));
    }
}
