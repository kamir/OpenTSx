package org.opentsx.demo.onboarding;

import org.opentsx.data.generator.RNGWrapper;
import org.opentsx.data.loader.MessreihenLoader;
import org.opentsx.data.series.TimeSeriesObject;

import java.io.File;
import java.io.IOException;

/**
 * Episode 2 Demo: Time Series Data Structures
 *
 * Learning Objectives:
 * - Create TimeSeriesObject programmatically
 * - Generate synthetic time series (Gaussian, uniform distributions)
 * - Calculate basic statistics (mean, standard deviation, min, max)
 * - Export/import time series in multiple formats
 * - Understand metadata and labeling
 *
 * Target Track: Software Engineer (SWE)
 * Episode: E02 - Time Series Data Structures
 * Duration: ~15 minutes
 *
 * Prerequisites:
 * - Episode 1 completed (environment setup)
 * - Basic Java knowledge
 *
 * @author OpenTSx Onboarding Team
 * @version 1.0
 */
public class SimpleTimeSeriesCreation {

    public static void main(String[] args) throws IOException {

        System.out.println("===========================================");
        System.out.println("OpenTSx Demo: Simple Time Series Creation");
        System.out.println("Episode 2 - Time Series Data Structures");
        System.out.println("===========================================\n");

        // Initialize the random number generator
        RNGWrapper.init();

        // =====================================================
        // TASK 1: Create a simple time series manually
        // =====================================================
        System.out.println("TASK 1: Creating TimeSeriesObject manually");
        System.out.println("------------------------------------------");

        TimeSeriesObject ts = new TimeSeriesObject();
        ts.setLabel("temperature_sensor_01");

        // Add timestamp-value pairs
        long currentTime = System.currentTimeMillis();
        ts.addValuePair(currentTime, 22.5);
        ts.addValuePair(currentTime + 1000, 23.1);
        ts.addValuePair(currentTime + 2000, 22.8);
        ts.addValuePair(currentTime + 3000, 23.5);
        ts.addValuePair(currentTime + 4000, 22.9);

        System.out.println("Created time series: " + ts.getLabel());
        System.out.println("Number of data points: " + ts.yValues.size());
        System.out.println("First value: " + ts.yValues.elementAt(0));
        System.out.println("Last value: " + ts.yValues.elementAt(ts.yValues.size() - 1));
        System.out.println();

        // =====================================================
        // TASK 2: Generate Gaussian distribution time series
        // =====================================================
        System.out.println("TASK 2: Generate Gaussian distribution");
        System.out.println("---------------------------------------");

        // Parameters: length, mean (mu), standard deviation (sigma)
        TimeSeriesObject gaussianTS = TimeSeriesObject.getGaussianDistribution(1000, 10.0, 1.5);
        gaussianTS.setLabel("gaussian_distribution");

        System.out.println("Generated Gaussian time series:");
        System.out.println("  Length: " + gaussianTS.yValues.size());
        System.out.println("  Expected mean: 10.0");
        System.out.println("  Actual mean: " + String.format("%.2f", gaussianTS.getAvarage()));
        System.out.println("  Expected std dev: 1.5");
        System.out.println("  Actual std dev: " + String.format("%.2f", gaussianTS.getStddev()));
        System.out.println();

        // =====================================================
        // TASK 3: Access time series properties
        // =====================================================
        System.out.println("TASK 3: Access time series properties");
        System.out.println("-------------------------------------");

        System.out.println("Statistics for: " + gaussianTS.getLabel());
        System.out.println("  Length: " + gaussianTS.yValues.size());
        System.out.println("  Mean: " + String.format("%.4f", gaussianTS.getAvarage()));
        System.out.println("  Std Dev: " + String.format("%.4f", gaussianTS.getStddev()));
        System.out.println("  Min: " + String.format("%.4f", gaussianTS.getMinY()));
        System.out.println("  Max: " + String.format("%.4f", gaussianTS.getMaxY()));

        // Calculate and display additional statistics
        double range = gaussianTS.getMaxY() - gaussianTS.getMinY();
        System.out.println("  Range: " + String.format("%.4f", range));
        System.out.println();

        // =====================================================
        // TASK 4: Export to different formats
        // =====================================================
        System.out.println("TASK 4: Export to different formats");
        System.out.println("-----------------------------------");

        // Create output directory if it doesn't exist
        String outputDir = "data/demo_output/";
        new java.io.File(outputDir).mkdirs();

        // Export as tab-separated values
        String tsvFile = outputDir + "gaussian_ts.tsv";
        gaussianTS.writeToFile(new File(tsvFile), '\t');
        System.out.println("Exported to TSV: " + tsvFile);

        // Export as comma-separated values
        String csvFile = outputDir + "gaussian_ts.csv";
        gaussianTS.writeToFile(new File(csvFile), ',');
        System.out.println("Exported to CSV: " + csvFile);

        System.out.println();

        // =====================================================
        // TASK 5: Load time series from file
        // =====================================================
        System.out.println("TASK 5: Load time series from file");
        System.out.println("----------------------------------");

        MessreihenLoader loader = MessreihenLoader.getLoader();
        loader.delim = ",";
        TimeSeriesObject loaded = loader.loadMessreihe_2(new File(csvFile), 1, 2);
        loaded.setLabel("loaded_from_csv");

        System.out.println("Loaded time series: " + loaded.getLabel());
        System.out.println("  Length: " + loaded.yValues.size());
        System.out.println("  Mean: " + String.format("%.4f", loaded.getAvarage()));
        System.out.println("  Std Dev: " + String.format("%.4f", loaded.getStddev()));
        System.out.println();

        // Verify data integrity
        boolean dataMatches = Math.abs(gaussianTS.getAvarage() - loaded.getAvarage()) < 0.001;
        System.out.println("Data integrity check: " + (dataMatches ? "PASSED ✓" : "FAILED ✗"));
        System.out.println();

        // =====================================================
        // TASK 6: Create different distributions
        // =====================================================
        System.out.println("TASK 6: Create different distributions");
        System.out.println("--------------------------------------");

        // Uniform distribution
        TimeSeriesObject uniformTS = TimeSeriesObject.getGaussianDistribution(500, 0.0, 1.0);
        uniformTS.setLabel("uniform_distribution");

        // Different Gaussian distributions
        TimeSeriesObject highMean = TimeSeriesObject.getGaussianDistribution(500, 100.0, 10.0);
        highMean.setLabel("high_mean_distribution");

        TimeSeriesObject lowVariance = TimeSeriesObject.getGaussianDistribution(500, 50.0, 2.0);
        lowVariance.setLabel("low_variance_distribution");

        System.out.println("Created multiple distributions:");
        System.out.println("  1. " + uniformTS.getLabel() + " - Mean: " +
                          String.format("%.2f", uniformTS.getAvarage()));
        System.out.println("  2. " + highMean.getLabel() + " - Mean: " +
                          String.format("%.2f", highMean.getAvarage()));
        System.out.println("  3. " + lowVariance.getLabel() + " - Std Dev: " +
                          String.format("%.2f", lowVariance.getStddev()));
        System.out.println();

        // =====================================================
        // TASK 7: Working with metadata and labels
        // =====================================================
        System.out.println("TASK 7: Working with metadata and labels");
        System.out.println("----------------------------------------");

        TimeSeriesObject sensorData = TimeSeriesObject.getGaussianDistribution(100, 25.0, 3.0);
        sensorData.setLabel("warehouse_a.sensor_01.temperature");

        // You can parse the label to extract metadata
        String[] labelParts = sensorData.getLabel().split("\\.");
        System.out.println("Sensor metadata from label:");
        System.out.println("  Location: " + labelParts[0]);
        System.out.println("  Sensor ID: " + labelParts[1]);
        System.out.println("  Measurement: " + labelParts[2]);
        System.out.println();

        // =====================================================
        // Summary and Next Steps
        // =====================================================
        System.out.println("===========================================");
        System.out.println("Demo Completed Successfully!");
        System.out.println("===========================================");
        System.out.println("\nKey Takeaways:");
        System.out.println("✓ Created TimeSeriesObject programmatically");
        System.out.println("✓ Generated synthetic time series (Gaussian distribution)");
        System.out.println("✓ Calculated basic statistics (mean, std dev, min, max)");
        System.out.println("✓ Exported time series in TSV and CSV formats");
        System.out.println("✓ Loaded time series from file");
        System.out.println("✓ Worked with metadata and labels");
        System.out.println("\nNext Steps:");
        System.out.println("→ Episode 3: Basic Time Series Operations");
        System.out.println("  Learn to transform, filter, and aggregate time series data");
        System.out.println("\nExercise:");
        System.out.println("1. Create a time series with your own mean and std dev");
        System.out.println("2. Export it to CSV");
        System.out.println("3. Load it back and verify the statistics match");
        System.out.println("4. Try creating time series with different lengths (10, 100, 10000)");
        System.out.println("   and observe how statistics stabilize with larger samples");
        System.out.println();
    }
}
