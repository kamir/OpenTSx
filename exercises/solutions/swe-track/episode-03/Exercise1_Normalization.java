package org.opentsx.exercises.swe.solutions;

import org.opentsx.data.series.TimeSeriesObject;
import java.io.File;

/**
 * Solution for Exercise 1: Normalization and Standardization
 *
 * Demonstrates three common normalization techniques:
 * A) Centering (subtract mean)
 * B) Z-score normalization (mean=0, stddev=1)
 * C) Min-max normalization to [0, 1]
 */
public class Exercise1_Normalization {

    public static void main(String[] args) {
        try {
            // Create test data
            TimeSeriesObject original = TimeSeriesObject.getGaussianDistribution(500, 100.0, 15.0);
            original.setLabel("original");

            System.out.println("=== Original Statistics ===");
            System.out.println("Mean: " + String.format("%.2f", original.getAvarage()));
            System.out.println("StdDev: " + String.format("%.2f", original.getStddev()));
            System.out.println("Min: " + String.format("%.2f", original.getMinY()));
            System.out.println("Max: " + String.format("%.2f", original.getMaxY()));
            System.out.println();

            // Version A: Center to mean=0 (subtract average)
            TimeSeriesObject centered = original.subtractAverage();
            centered.setLabel("centered");

            System.out.println("=== Version A: Centered (Mean=0) ===");
            System.out.println("Mean: " + String.format("%.2f", centered.getAvarage()));
            System.out.println("StdDev: " + String.format("%.2f", centered.getStddev()));
            centered.writeToFile(new File("exercise1_centered.csv"), ',');
            System.out.println();

            // Version B: Z-score normalization (mean=0, stddev=1)
            TimeSeriesObject zscore = original.normalizeToStdevIsOne();
            zscore.setLabel("zscore");

            System.out.println("=== Version B: Z-Score (Mean=0, StdDev=1) ===");
            System.out.println("Mean: " + String.format("%.2f", zscore.getAvarage()));
            System.out.println("StdDev: " + String.format("%.2f", zscore.getStddev()));
            zscore.writeToFile(new File("exercise1_zscore.csv"), ',');
            System.out.println();

            // Version C: Min-max normalization to [0, 1]
            TimeSeriesObject minmax = minMaxNormalize(original, 0.0, 1.0);
            minmax.setLabel("minmax");

            System.out.println("=== Version C: Min-Max [0, 1] ===");
            System.out.println("Mean: " + String.format("%.2f", minmax.getAvarage()));
            System.out.println("StdDev: " + String.format("%.2f", minmax.getStddev()));
            System.out.println("Min: " + String.format("%.2f", minmax.getMinY()));
            System.out.println("Max: " + String.format("%.2f", minmax.getMaxY()));
            minmax.writeToFile(new File("exercise1_minmax.csv"), ',');
            System.out.println();

            System.out.println("All normalized versions saved successfully.");

        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Min-max normalization to specified range
     * Formula: (x - min) / (max - min) * (newMax - newMin) + newMin
     */
    private static TimeSeriesObject minMaxNormalize(TimeSeriesObject input, double newMin, double newMax) {
        TimeSeriesObject result = new TimeSeriesObject();
        result.setLabel(input.getLabel() + "_minmax");

        double oldMin = input.getMinY();
        double oldMax = input.getMaxY();
        double oldRange = oldMax - oldMin;
        double newRange = newMax - newMin;

        for (int i = 0; i < input.yValues.size(); i++) {
            double x = (Double)input.xValues.elementAt(i);
            double y = (Double)input.yValues.elementAt(i);

            // Normalize to [0, 1] then scale to [newMin, newMax]
            double normalized = ((y - oldMin) / oldRange) * newRange + newMin;
            result.addValuePair(x, normalized);
        }

        return result;
    }
}
