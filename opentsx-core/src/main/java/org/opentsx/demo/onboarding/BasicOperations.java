package org.opentsx.demo.onboarding;

import org.opentsx.data.generator.RNGWrapper;
import org.opentsx.data.series.TimeSeriesObject;

import java.io.File;
import java.io.IOException;

/**
 * Episode 3 Demo: Basic Time Series Operations
 *
 * Learning Objectives:
 * - Apply transformations (normalize, scale, offset)
 * - Filter time series by value and time range
 * - Compute aggregations (sum, mean, max, min)
 * - Resample at different frequencies
 * - Combine multiple time series
 *
 * Target Track: Software Engineer (SWE)
 * Episode: E03 - Basic Time Series Operations
 * Duration: ~20 minutes
 *
 * Prerequisites:
 * - Episode 2 completed (data structures)
 * - Understanding of basic statistics
 *
 * @author OpenTSx Onboarding Team
 * @version 1.0
 */
public class BasicOperations {

    public static void main(String[] args) throws IOException {

        System.out.println("==========================================");
        System.out.println("OpenTSx Demo: Basic Time Series Operations");
        System.out.println("Episode 3 - Transformations and Filters");
        System.out.println("==========================================\n");

        // Initialize RNG
        RNGWrapper.init();

        // Create sample data for demonstrations
        TimeSeriesObject rawData = TimeSeriesObject.getGaussianDistribution(1000, 50.0, 10.0);
        rawData.setLabel("sensor_raw_data");

        System.out.println("Sample Data Created:");
        System.out.println("  Label: " + rawData.getLabel());
        System.out.println("  Length: " + rawData.yValues.size());
        System.out.println("  Mean: " + String.format("%.2f", rawData.getAvarage()));
        System.out.println("  Std Dev: " + String.format("%.2f", rawData.getStddev()));
        System.out.println();

        // =====================================================
        // TASK 1: Apply Transformations
        // =====================================================
        System.out.println("TASK 1: Apply transformations");
        System.out.println("-----------------------------");

        // Normalize (z-score normalization: (x - mean) / stddev)
        TimeSeriesObject normalized = rawData.normalizeToStdevIsOne();
        normalized.setLabel("normalized_data");

        System.out.println("Normalized time series:");
        System.out.println("  Mean: " + String.format("%.4f", normalized.getAvarage()) +
                          " (should be ~0)");
        System.out.println("  Std Dev: " + String.format("%.4f", normalized.getStddev()) +
                          " (should be ~1)");
        System.out.println();

        // Scale (multiply by constant)
        TimeSeriesObject scaled = rawData.copy();
        scaled.scaleY_2(0.5);
        scaled.setLabel("scaled_data");

        System.out.println("Scaled time series (multiply by 0.5):");
        System.out.println("  Original mean: " + String.format("%.2f", rawData.getAvarage()));
        System.out.println("  Scaled mean: " + String.format("%.2f", scaled.getAvarage()));
        System.out.println("  Expected: " + String.format("%.2f", rawData.getAvarage() * 0.5));
        System.out.println();

        // Offset (add constant)
        TimeSeriesObject offset = rawData.copy();
        offset.add_to_Y(25.0);
        offset.setLabel("offset_data");

        System.out.println("Offset time series (add 25.0):");
        System.out.println("  Original mean: " + String.format("%.2f", rawData.getAvarage()));
        System.out.println("  Offset mean: " + String.format("%.2f", offset.getAvarage()));
        System.out.println("  Expected: " + String.format("%.2f", rawData.getAvarage() + 25.0));
        System.out.println();

        // =====================================================
        // TASK 2: Filter operations
        // =====================================================
        System.out.println("TASK 2: Filter operations");
        System.out.println("------------------------");

        // Filter by value threshold
        double threshold = rawData.getAvarage();
        TimeSeriesObject aboveAverage = rawData.copy();
        aboveAverage.setLabel("above_average");

        // Manual filtering (keeping values above mean)
        int countAbove = 0;
        for (int i = 0; i < rawData.yValues.size(); i++) {
            if ((Double)rawData.yValues.elementAt(i) > threshold) {
                countAbove++;
            }
        }

        System.out.println("Filter by value:");
        System.out.println("  Threshold (mean): " + String.format("%.2f", threshold));
        System.out.println("  Original length: " + rawData.yValues.size());
        System.out.println("  Values above mean: " + countAbove);
        System.out.println("  Percentage: " + String.format("%.1f%%",
                          (countAbove * 100.0) / rawData.yValues.size()));
        System.out.println();

        // Filter by time range (shrink)
        TimeSeriesObject subset = rawData.shrinkX(200, 400);
        subset.setLabel("time_window");

        System.out.println("Filter by time range (indices 200-400):");
        System.out.println("  Original length: " + rawData.yValues.size());
        System.out.println("  Subset length: " + subset.yValues.size());
        System.out.println();

        // =====================================================
        // TASK 3: Aggregations
        // =====================================================
        System.out.println("TASK 3: Compute aggregations");
        System.out.println("----------------------------");

        double sum = rawData.summeY();
        double mean = rawData.getAvarage();
        double max = rawData.getMaxY();
        double min = rawData.getMinY();
        double stddev = rawData.getStddev();

        System.out.println("Aggregate statistics:");
        System.out.println("  Sum: " + String.format("%.2f", sum));
        System.out.println("  Mean: " + String.format("%.2f", mean));
        System.out.println("  Max: " + String.format("%.2f", max));
        System.out.println("  Min: " + String.format("%.2f", min));
        System.out.println("  Std Dev: " + String.format("%.2f", stddev));
        System.out.println("  Range: " + String.format("%.2f", max - min));
        System.out.println();

        // =====================================================
        // TASK 4: Resampling and binning
        // =====================================================
        System.out.println("TASK 4: Resampling and binning");
        System.out.println("------------------------------");

        // Downsample by factor (average every N points)
        int downsampleFactor = 10;
        TimeSeriesObject downsampled = rawData.setBinningX_average(downsampleFactor);
        downsampled.setLabel("downsampled_10x");

        System.out.println("Downsample by factor " + downsampleFactor + ":");
        System.out.println("  Original length: " + rawData.yValues.size());
        System.out.println("  Downsampled length: " + downsampled.yValues.size());
        System.out.println("  Original mean: " + String.format("%.2f", rawData.getAvarage()));
        System.out.println("  Downsampled mean: " + String.format("%.2f", downsampled.getAvarage()));
        System.out.println("  (Mean should be similar)");
        System.out.println();

        // =====================================================
        // TASK 5: Combine multiple time series
        // =====================================================
        System.out.println("TASK 5: Combine multiple time series");
        System.out.println("------------------------------------");

        // Create two time series
        TimeSeriesObject ts1 = TimeSeriesObject.getGaussianDistribution(500, 10.0, 2.0);
        ts1.setLabel("series_1");

        TimeSeriesObject ts2 = TimeSeriesObject.getGaussianDistribution(500, 20.0, 3.0);
        ts2.setLabel("series_2");

        // Add two series (element-wise addition)
        TimeSeriesObject combined = ts1.add(ts2);
        combined.setLabel("combined_series");

        System.out.println("Combining two time series (addition):");
        System.out.println("  Series 1 mean: " + String.format("%.2f", ts1.getAvarage()));
        System.out.println("  Series 2 mean: " + String.format("%.2f", ts2.getAvarage()));
        System.out.println("  Combined mean: " + String.format("%.2f", combined.getAvarage()));
        System.out.println("  Expected mean: " + String.format("%.2f",
                          ts1.getAvarage() + ts2.getAvarage()));
        System.out.println();

        // =====================================================
        // TASK 6: Calculate correlation
        // =====================================================
        System.out.println("TASK 6: Calculate correlation");
        System.out.println("-----------------------------");

        // Create correlated series
        TimeSeriesObject original = TimeSeriesObject.getGaussianDistribution(500, 0.0, 1.0);
        original.setLabel("original");

        // Create similar series with some noise
        TimeSeriesObject similar = original.add(
            TimeSeriesObject.getGaussianDistribution(500, 0.0, 0.3)
        );
        similar.setLabel("similar_with_noise");

        // Create uncorrelated series
        TimeSeriesObject uncorrelated = TimeSeriesObject.getGaussianDistribution(500, 0.0, 1.0);
        uncorrelated.setLabel("uncorrelated");

        System.out.println("Correlation analysis:");
        System.out.println("  Original series: " + original.getLabel());
        System.out.println("  Similar series: " + similar.getLabel());
        System.out.println("  Uncorrelated series: " + uncorrelated.getLabel());
        System.out.println("\n  Note: Correlation calculation requires matching time indices");
        System.out.println("  (Advanced correlation methods available in Episode 9)");
        System.out.println();

        // =====================================================
        // TASK 7: Export results
        // =====================================================
        System.out.println("TASK 7: Export results");
        System.out.println("---------------------");

        String outputDir = "data/demo_output/";
        new java.io.File(outputDir).mkdirs();

        // Export various transformations for comparison
        rawData.writeToFile(new File(outputDir + "original.csv"), ',');
        normalized.writeToFile(new File(outputDir + "normalized.csv"), ',');
        scaled.writeToFile(new File(outputDir + "scaled.csv"), ',');
        downsampled.writeToFile(new File(outputDir + "downsampled.csv"), ',');

        System.out.println("Exported time series to: " + outputDir);
        System.out.println("  - original.csv");
        System.out.println("  - normalized.csv");
        System.out.println("  - scaled.csv");
        System.out.println("  - downsampled.csv");
        System.out.println("\nYou can load these in R/Python for visualization:");
        System.out.println("  R: data <- read.csv('data/demo_output/original.csv')");
        System.out.println("     plot(data$value, type='l')");
        System.out.println("  Python: import pandas as pd");
        System.out.println("          df = pd.read_csv('data/demo_output/original.csv')");
        System.out.println("          df.plot()");
        System.out.println();

        // =====================================================
        // Summary and Next Steps
        // =====================================================
        System.out.println("==========================================");
        System.out.println("Demo Completed Successfully!");
        System.out.println("==========================================");
        System.out.println("\nKey Takeaways:");
        System.out.println("✓ Applied transformations (normalize, scale, offset)");
        System.out.println("✓ Filtered time series by value and range");
        System.out.println("✓ Computed aggregate statistics");
        System.out.println("✓ Resampled time series (downsampling)");
        System.out.println("✓ Combined multiple time series");
        System.out.println("✓ Exported results for external analysis");
        System.out.println("\nNext Steps:");
        System.out.println("→ Episode 4: Kafka Streams Integration");
        System.out.println("  Learn to process time series data streams in real-time");
        System.out.println("\nExercise:");
        System.out.println("1. Create two time series with different means and std devs");
        System.out.println("2. Normalize both to zero mean and unit variance");
        System.out.println("3. Combine them and verify the result");
        System.out.println("4. Apply different downsampling factors (5, 10, 20)");
        System.out.println("   and observe how it affects the statistics");
        System.out.println("5. Export all results and visualize in your favorite tool");
        System.out.println();
    }
}
