package org.opentsx.exercises.swe.solutions;

import org.opentsx.data.series.TimeSeriesObject;
import java.io.File;

/**
 * Solution for Exercise 2: Synthetic Data Generation
 *
 * Demonstrates creating synthetic time series using different distributions
 * and comparing theoretical vs. actual statistics.
 */
public class Exercise2_SyntheticData {

    public static void main(String[] args) {
        try {
            int numPoints = 500;

            // 1. Gaussian Distribution
            System.out.println("=== Gaussian Distribution ===");
            double gaussianMean = 50.0;
            double gaussianStddev = 10.0;

            TimeSeriesObject gaussian = TimeSeriesObject.getGaussianDistribution(
                numPoints, gaussianMean, gaussianStddev
            );
            gaussian.setLabel("gaussian_series");

            System.out.println("Theoretical Mean: " + gaussianMean);
            System.out.println("Actual Mean: " + String.format("%.2f", gaussian.getAvarage()));
            System.out.println("Theoretical StdDev: " + gaussianStddev);
            System.out.println("Actual StdDev: " + String.format("%.2f", gaussian.getStddev()));

            File gaussianFile = new File("exercise2_gaussian.csv");
            gaussian.writeToFile(gaussianFile, ',');
            System.out.println("Saved to: " + gaussianFile.getName());
            System.out.println();

            // 2. Uniform Distribution
            System.out.println("=== Uniform Distribution ===");
            double uniformMin = 0.0;
            double uniformMax = 100.0;
            double uniformMeanTheory = (uniformMin + uniformMax) / 2.0;  // 50.0
            double uniformStddevTheory = Math.sqrt(Math.pow(uniformMax - uniformMin, 2) / 12.0);  // ~28.87

            TimeSeriesObject uniform = TimeSeriesObject.getUniformDistribution(
                numPoints, uniformMin, uniformMax
            );
            uniform.setLabel("uniform_series");

            System.out.println("Theoretical Mean: " + String.format("%.2f", uniformMeanTheory));
            System.out.println("Actual Mean: " + String.format("%.2f", uniform.getAvarage()));
            System.out.println("Theoretical StdDev: " + String.format("%.2f", uniformStddevTheory));
            System.out.println("Actual StdDev: " + String.format("%.2f", uniform.getStddev()));

            File uniformFile = new File("exercise2_uniform.csv");
            uniform.writeToFile(uniformFile, ',');
            System.out.println("Saved to: " + uniformFile.getName());
            System.out.println();

            // 3. Exponential Distribution
            System.out.println("=== Exponential Distribution ===");
            double lambda = 0.05;
            double expMeanTheory = 1.0 / lambda;  // 20.0
            double expStddevTheory = 1.0 / lambda;  // Also 20.0 for exponential

            TimeSeriesObject exponential = TimeSeriesObject.getExpDistribution(numPoints, lambda);
            exponential.setLabel("exponential_series");

            System.out.println("Theoretical Mean: " + String.format("%.2f", expMeanTheory));
            System.out.println("Actual Mean: " + String.format("%.2f", exponential.getAvarage()));
            System.out.println("Theoretical StdDev: " + String.format("%.2f", expStddevTheory));
            System.out.println("Actual StdDev: " + String.format("%.2f", exponential.getStddev()));

            File expFile = new File("exercise2_exponential.csv");
            exponential.writeToFile(expFile, ',');
            System.out.println("Saved to: " + expFile.getName());
            System.out.println();

            // Summary
            System.out.println("=== Summary ===");
            System.out.println("All three distributions generated successfully.");
            System.out.println("Note: Actual values will vary slightly due to random sampling.");
            System.out.println("With " + numPoints + " points, expect close approximation to theoretical values.");

        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
