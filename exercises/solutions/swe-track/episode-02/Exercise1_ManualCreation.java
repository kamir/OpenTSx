package org.opentsx.exercises.swe.solutions;

import org.opentsx.data.series.TimeSeriesObject;
import java.io.File;

/**
 * Solution for Exercise 1: Manual Time Series Creation
 *
 * Creates a TimeSeriesObject manually with a linear relationship: y = 2*x + 5
 * Demonstrates basic TimeSeriesObject construction and data population.
 */
public class Exercise1_ManualCreation {

    public static void main(String[] args) {
        try {
            // Create TimeSeriesObject with label
            TimeSeriesObject ts = new TimeSeriesObject();
            ts.setLabel("manual_series");

            // Add 20 data points with linear relationship
            for (int x = 0; x < 20; x++) {
                double y = 2 * x + 5;  // Linear formula
                ts.addValuePair(x, y);
            }

            // Print statistics
            System.out.println("Series Label: " + ts.getLabel());
            System.out.println("Number of points: " + ts.yValues.size());
            System.out.println("Mean: " + ts.getAvarage());  // Note: API has typo
            System.out.println("Standard Deviation: " + ts.getStddev());

            // Save to file
            File outputFile = new File("exercise1_output.csv");
            ts.writeToFile(outputFile, ',');
            System.out.println("\nSaved to: " + outputFile.getName());

            // Verification
            System.out.println("\nVerification:");
            System.out.println("  Expected mean: 24.0");
            System.out.println("  Actual mean: " + ts.getAvarage());
            System.out.println("  Match: " + (Math.abs(ts.getAvarage() - 24.0) < 0.01));

        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
