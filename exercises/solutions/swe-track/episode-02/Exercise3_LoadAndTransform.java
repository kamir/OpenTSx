package org.opentsx.exercises.swe.solutions;

import org.opentsx.data.series.TimeSeriesObject;
import org.opentsx.data.loader.MessreihenLoader;
import java.io.File;

/**
 * Solution for Exercise 3: Loading and Transforming CSV Data
 *
 * Demonstrates loading time series from CSV files,
 * analyzing the data, and creating filtered subsets.
 */
public class Exercise3_LoadAndTransform {

    public static void main(String[] args) {
        try {
            // Load sensor data from CSV
            File inputFile = new File("sample_data/sensor_data.csv");

            if (!inputFile.exists()) {
                System.err.println("Error: sample_data/sensor_data.csv not found!");
                System.err.println("Please ensure sample data directory exists.");
                return;
            }

            System.out.println("Loading: " + inputFile.getPath());

            MessreihenLoader loader = MessreihenLoader.getLoader();
            loader.delim = ",";

            // Load temperature column (assuming column 1 is timestamp, column 2 is temperature)
            TimeSeriesObject temperatures = loader.loadMessreihe_2(inputFile, 1, 2);
            temperatures.setLabel("sensor_temperatures");

            // Print basic statistics
            System.out.println("\n=== Original Data Statistics ===");
            System.out.println("Number of points: " + temperatures.yValues.size());
            System.out.println("Mean temperature: " + String.format("%.2f°C", temperatures.getAvarage()));
            System.out.println("Std deviation: " + String.format("%.2f°C", temperatures.getStddev()));
            System.out.println("Min temperature: " + String.format("%.2f°C", temperatures.getMinY()));
            System.out.println("Max temperature: " + String.format("%.2f°C", temperatures.getMaxY()));

            // Create filtered series (temperatures > 25°C)
            TimeSeriesObject filtered = new TimeSeriesObject();
            filtered.setLabel("high_temperatures");

            double threshold = 25.0;
            int filteredCount = 0;

            for (int i = 0; i < temperatures.yValues.size(); i++) {
                double temp = (Double)temperatures.yValues.elementAt(i);
                double time = (Double)temperatures.xValues.elementAt(i);

                if (temp > threshold) {
                    filtered.addValuePair(time, temp);
                    filteredCount++;
                }
            }

            // Print filtering results
            System.out.println("\n=== Filtering Results ===");
            System.out.println("Threshold: >" + threshold + "°C");
            System.out.println("Original points: " + temperatures.yValues.size());
            System.out.println("Filtered points: " + filteredCount);
            System.out.println("Percentage filtered: " +
                String.format("%.1f%%", (filteredCount * 100.0 / temperatures.yValues.size())));

            // Statistics of filtered data
            if (filteredCount > 0) {
                System.out.println("\n=== Filtered Data Statistics ===");
                System.out.println("Mean: " + String.format("%.2f°C", filtered.getAvarage()));
                System.out.println("Std deviation: " + String.format("%.2f°C", filtered.getStddev()));
                System.out.println("Min: " + String.format("%.2f°C", filtered.getMinY()));
                System.out.println("Max: " + String.format("%.2f°C", filtered.getMaxY()));

                // Save filtered series
                File outputFile = new File("high_temp_readings.csv");
                filtered.writeToFile(outputFile, ',');
                System.out.println("\nSaved filtered data to: " + outputFile.getName());
            } else {
                System.out.println("No data points above threshold!");
            }

            // Additional analysis: Temperature ranges
            System.out.println("\n=== Temperature Distribution ===");
            int below20 = countInRange(temperatures, Double.NEGATIVE_INFINITY, 20.0);
            int range20to25 = countInRange(temperatures, 20.0, 25.0);
            int range25to30 = countInRange(temperatures, 25.0, 30.0);
            int above30 = countInRange(temperatures, 30.0, Double.POSITIVE_INFINITY);

            System.out.println("Below 20°C: " + below20 + " points");
            System.out.println("20-25°C: " + range20to25 + " points");
            System.out.println("25-30°C: " + range25to30 + " points");
            System.out.println("Above 30°C: " + above30 + " points");

        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Helper method to count values within a range
     */
    private static int countInRange(TimeSeriesObject ts, double min, double max) {
        int count = 0;
        for (int i = 0; i < ts.yValues.size(); i++) {
            double value = (Double)ts.yValues.elementAt(i);
            if (value > min && value <= max) {
                count++;
            }
        }
        return count;
    }
}
