package org.opentsx.exercises.swe.solutions;

import org.opentsx.data.series.TimeSeriesObject;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * Solution for Exercise 3: Loading and Transforming CSV Data
 *
 * Demonstrates loading time series from CSV files,
 * analyzing the data, and creating filtered subsets.
 *
 * sample_data/sensor_data.csv starts with a header line and has an ISO-8601 time in column 1, which
 * MessreihenLoader cannot parse, so the solution reads the file itself.
 */
public class Exercise3_LoadAndTransform {

    public static void main(String[] args) {
        try {
            File inputFile = new File("sample_data/sensor_data.csv");

            System.out.println("Loading: " + inputFile.getPath());

            TimeSeriesObject temperatures = load(inputFile);
            temperatures.setLabel("sensor_temperatures");

            // Print basic statistics
            System.out.println("\n=== Original Data Statistics ===");
            System.out.println("Number of points: " + temperatures.yValues.size());
            System.out.println("Mean temperature: " + String.format("%.2f degC", temperatures.getAvarage()));
            System.out.println("Std deviation: " + String.format("%.2f degC", temperatures.getStddev()));
            System.out.println("Min temperature: " + String.format("%.2f degC", temperatures.getMinY()));
            System.out.println("Max temperature: " + String.format("%.2f degC", temperatures.getMaxY()));

            // Create filtered series (temperatures > 25 degC)
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
            System.out.println("Threshold: >" + threshold + " degC");
            System.out.println("Original points: " + temperatures.yValues.size());
            System.out.println("Filtered points: " + filteredCount);
            System.out.println("Percentage filtered: " +
                String.format("%.1f%%", (filteredCount * 100.0 / temperatures.yValues.size())));

            // Statistics of filtered data
            if (filteredCount > 0) {
                System.out.println("\n=== Filtered Data Statistics ===");
                System.out.println("Mean: " + String.format("%.2f degC", filtered.getAvarage()));
                System.out.println("Std deviation: " + String.format("%.2f degC", filtered.getStddev()));
                System.out.println("Min: " + String.format("%.2f degC", filtered.getMinY()));
                System.out.println("Max: " + String.format("%.2f degC", filtered.getMaxY()));

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

            System.out.println("Below 20 degC: " + below20 + " points");
            System.out.println("20-25 degC: " + range20to25 + " points");
            System.out.println("25-30 degC: " + range25to30 + " points");
            System.out.println("Above 30 degC: " + above30 + " points");

        } catch (IOException | IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.exit(1);
        }
    }

    /**
     * Reads column 1 as the time and column 2 as the temperature; line 1 is the header.
     */
    private static TimeSeriesObject load(File file) throws IOException {
        if (!file.isFile()) {
            throw new IOException(file + ": no such file; run the solution from the project root");
        }
        List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
        TimeSeriesObject series = new TimeSeriesObject();
        for (int i = 1; i < lines.size(); i++) {
            int lineNumber = i + 1;
            String[] fields = lines.get(i).split(",");
            if (fields.length < 2) {
                throw new IllegalArgumentException(file + ":" + lineNumber
                        + ": 1 column; expected at least 2 columns, timestamp,temperature_c");
            }
            double time;
            try {
                time = Instant.parse(fields[0].trim()).toEpochMilli();
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException(file + ":" + lineNumber + ": \"" + fields[0]
                        + "\" is not a time; expected an ISO-8601 time such as 2024-01-01T00:00:00Z", e);
            }
            double temperature;
            try {
                temperature = Double.parseDouble(fields[1].trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(file + ":" + lineNumber + ": \"" + fields[1]
                        + "\" is not a number; expected a decimal number such as 21.1", e);
            }
            series.addValuePair(time, temperature);
        }
        if (series.yValues.isEmpty()) {
            throw new IllegalArgumentException(file + ": no data lines; expected a header and at least one line"
                    + " timestamp,temperature_c");
        }
        return series;
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
