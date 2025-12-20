package org.opentsx.exercises.swe.solutions;

import org.opentsx.data.series.TimeSeriesObject;
import org.opentsx.tsa.rng.RNGWrapper;
import java.io.File;

/**
 * Solution for Bonus Exercise: Pattern Creation
 *
 * Demonstrates creating complex time series by combining multiple patterns:
 * - Linear trend
 * - Seasonal (sinusoidal) pattern
 * - Random Gaussian noise
 *
 * This mimics real-world time series data which often contains
 * multiple superimposed components.
 */
public class BonusExercise_PatternCreation {

    public static void main(String[] args) {
        try {
            int numPoints = 1000;
            int seasonalPeriod = 50;

            // Create combined pattern
            TimeSeriesObject combined = new TimeSeriesObject();
            combined.setLabel("trend_seasonal_noise");

            // Also create individual components for comparison
            TimeSeriesObject trendOnly = new TimeSeriesObject();
            TimeSeriesObject seasonalOnly = new TimeSeriesObject();
            TimeSeriesObject noiseOnly = new TimeSeriesObject();

            trendOnly.setLabel("trend_component");
            seasonalOnly.setLabel("seasonal_component");
            noiseOnly.setLabel("noise_component");

            // Fixed seed for reproducibility
            RNGWrapper.init(42);

            System.out.println("Generating time series with combined patterns...");
            System.out.println("  - Linear trend: y = 0.1 * t");
            System.out.println("  - Seasonal: amplitude=10, period=" + seasonalPeriod);
            System.out.println("  - Noise: Gaussian(mean=0, stddev=2)");
            System.out.println();

            for (int t = 0; t < numPoints; t++) {
                // Components
                double trend = 0.1 * t;
                double seasonal = 10.0 * Math.sin(2.0 * Math.PI * t / seasonalPeriod);
                double noise = RNGWrapper.getStdRandomGaussian(0.0, 2.0);

                // Combined value
                double y = trend + seasonal + noise;

                // Add to series
                combined.addValuePair(t, y);
                trendOnly.addValuePair(t, trend);
                seasonalOnly.addValuePair(t, seasonal);
                noiseOnly.addValuePair(t, noise);
            }

            // Print statistics
            System.out.println("=== Combined Series Statistics ===");
            System.out.println("Number of points: " + combined.yValues.size());
            System.out.println("Mean: " + String.format("%.2f", combined.getAvarage()));
            System.out.println("Std deviation: " + String.format("%.2f", combined.getStddev()));
            System.out.println("Min: " + String.format("%.2f", combined.getMinY()));
            System.out.println("Max: " + String.format("%.2f", combined.getMaxY()));
            System.out.println();

            // Save combined series
            File combinedFile = new File("bonus_combined_pattern.csv");
            combined.writeToFile(combinedFile, ',');
            System.out.println("Saved combined series to: " + combinedFile.getName());

            // Save individual components for comparison
            trendOnly.writeToFile(new File("bonus_trend_component.csv"), ',');
            seasonalOnly.writeToFile(new File("bonus_seasonal_component.csv"), ',');
            noiseOnly.writeToFile(new File("bonus_noise_component.csv"), ',');
            System.out.println("Saved individual components for comparison");
            System.out.println();

            // Analysis of components
            System.out.println("=== Component Analysis ===");
            System.out.println("Trend component:");
            System.out.println("  Mean: " + String.format("%.2f", trendOnly.getAvarage()));
            System.out.println("  (Expected: ~" + (0.1 * numPoints / 2) + ")");
            System.out.println();

            System.out.println("Seasonal component:");
            System.out.println("  Mean: " + String.format("%.2f", seasonalOnly.getAvarage()));
            System.out.println("  (Expected: ~0, since sine wave is symmetric)");
            System.out.println("  Std Dev: " + String.format("%.2f", seasonalOnly.getStddev()));
            System.out.println("  (Expected: ~7.07, for sine with amplitude 10)");
            System.out.println();

            System.out.println("Noise component:");
            System.out.println("  Mean: " + String.format("%.2f", noiseOnly.getAvarage()));
            System.out.println("  (Expected: ~0)");
            System.out.println("  Std Dev: " + String.format("%.2f", noiseOnly.getStddev()));
            System.out.println("  (Expected: ~2.0)");
            System.out.println();

            // Visualization suggestion
            System.out.println("=== Next Steps ===");
            System.out.println("To visualize the patterns:");
            System.out.println("1. Open the CSV files in a plotting tool (Excel, Python, R)");
            System.out.println("2. Plot combined series to see overall pattern");
            System.out.println("3. Compare with individual components to understand composition");
            System.out.println();
            System.out.println("Python example:");
            System.out.println("  import pandas as pd");
            System.out.println("  import matplotlib.pyplot as plt");
            System.out.println("  df = pd.read_csv('bonus_combined_pattern.csv')");
            System.out.println("  df.plot()");
            System.out.println("  plt.show()");

        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
