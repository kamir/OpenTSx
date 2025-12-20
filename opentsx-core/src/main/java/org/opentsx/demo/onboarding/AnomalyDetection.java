package org.opentsx.demo.onboarding;

import org.opentsx.data.generator.RNGWrapper;
import org.opentsx.data.series.TimeSeriesObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Demo: Anomaly Detection in Time Series
 *
 * Learning Objectives:
 * - Implement z-score based anomaly detection
 * - Use moving statistics for dynamic thresholding
 * - Detect different types of anomalies (point, contextual)
 * - Handle false positives
 * - Visualize anomaly detection results
 *
 * Target Track: Software Engineer (SWE) & Time Series Expert (TSx)
 * Episode: E09 - Time Series Analysis & ML
 * Duration: ~20 minutes
 *
 * Prerequisites:
 * - Understanding of statistical distributions
 * - Knowledge of mean and standard deviation
 * - Completed basic operations episodes
 *
 * @author OpenTSx Onboarding Team
 * @version 1.0
 */
public class AnomalyDetection {

    /**
     * Simple anomaly representation
     */
    static class Anomaly {
        int index;
        double value;
        double zScore;
        String type;

        Anomaly(int index, double value, double zScore, String type) {
            this.index = index;
            this.value = value;
            this.zScore = zScore;
            this.type = type;
        }

        @Override
        public String toString() {
            return String.format("Anomaly at index %d: value=%.2f, z-score=%.2f, type=%s",
                    index, value, zScore, type);
        }
    }

    public static void main(String[] args) throws IOException {

        System.out.println("===========================================");
        System.out.println("OpenTSx Demo: Anomaly Detection");
        System.out.println("Statistical and Pattern-Based Methods");
        System.out.println("===========================================\n");

        // Initialize RNG
        RNGWrapper.init();

        // =====================================================
        // TASK 1: Create time series with anomalies
        // =====================================================
        System.out.println("TASK 1: Create time series with anomalies");
        System.out.println("-----------------------------------------");

        TimeSeriesObject normalData = TimeSeriesObject.getGaussianDistribution(500, 100.0, 10.0);
        TimeSeriesObject dataWithAnomalies = new TimeSeriesObject();
        dataWithAnomalies.setLabel("sensor_data_with_anomalies");

        // Inject known anomalies
        List<Integer> injectedAnomalies = new ArrayList<>();

        for (int i = 0; i < normalData.yValues.size(); i++) {
            double value = normalData(Double).yValues.elementAt(i);

            // Inject point anomalies (10 total)
            if (i == 50 || i == 150 || i == 250 || i == 350 || i == 450) {
                value = 150.0; // High anomaly
                injectedAnomalies.add(i);
            } else if (i == 100 || i == 200 || i == 300 || i == 400) {
                value = 50.0; // Low anomaly
                injectedAnomalies.add(i);
            } else if (i == 175) {
                value = 170.0; // Extreme anomaly
                injectedAnomalies.add(i);
            }

            dataWithAnomalies.addValuePair(i, value);
        }

        System.out.println("Created time series:");
        System.out.println("  Total points: " + dataWithAnomalies.yValues.size());
        System.out.println("  Injected anomalies: " + injectedAnomalies.size());
        System.out.println("  Anomaly rate: " + String.format("%.2f%%",
                (injectedAnomalies.size() * 100.0) / dataWithAnomalies.yValues.size()));
        System.out.println();

        // =====================================================
        // TASK 2: Z-Score Based Detection (Global)
        // =====================================================
        System.out.println("TASK 2: Z-score based anomaly detection (global)");
        System.out.println("------------------------------------------------");

        double threshold = 3.0; // Standard 3-sigma rule
        List<Anomaly> detectedAnomalies = zScoreDetection(dataWithAnomalies, threshold);

        System.out.println("Z-score detection (threshold = " + threshold + "):");
        System.out.println("  Detected anomalies: " + detectedAnomalies.size());
        System.out.println("  Expected anomalies: " + injectedAnomalies.size());

        // Calculate precision and recall
        int truePositives = 0;
        for (Anomaly anomaly : detectedAnomalies) {
            if (injectedAnomalies.contains(anomaly.index)) {
                truePositives++;
            }
        }

        double precision = detectedAnomalies.size() > 0 ?
                (truePositives * 100.0) / detectedAnomalies.size() : 0;
        double recall = injectedAnomalies.size() > 0 ?
                (truePositives * 100.0) / injectedAnomalies.size() : 0;

        System.out.println("  True positives: " + truePositives);
        System.out.println("  Precision: " + String.format("%.1f%%", precision));
        System.out.println("  Recall: " + String.format("%.1f%%", recall));
        System.out.println("\nSample detections:");
        for (int i = 0; i < Math.min(5, detectedAnomalies.size()); i++) {
            System.out.println("  " + detectedAnomalies.get(i));
        }
        System.out.println();

        // =====================================================
        // TASK 3: Moving Window Detection (Local)
        // =====================================================
        System.out.println("TASK 3: Moving window anomaly detection (local)");
        System.out.println("-----------------------------------------------");

        int windowSize = 50;
        List<Anomaly> movingWindowAnomalies =
                movingWindowDetection(dataWithAnomalies, windowSize, threshold);

        System.out.println("Moving window detection (window = " + windowSize + "):");
        System.out.println("  Detected anomalies: " + movingWindowAnomalies.size());

        // Calculate metrics
        truePositives = 0;
        for (Anomaly anomaly : movingWindowAnomalies) {
            if (injectedAnomalies.contains(anomaly.index)) {
                truePositives++;
            }
        }

        precision = movingWindowAnomalies.size() > 0 ?
                (truePositives * 100.0) / movingWindowAnomalies.size() : 0;
        recall = injectedAnomalies.size() > 0 ?
                (truePositives * 100.0) / injectedAnomalies.size() : 0;

        System.out.println("  True positives: " + truePositives);
        System.out.println("  Precision: " + String.format("%.1f%%", precision));
        System.out.println("  Recall: " + String.format("%.1f%%", recall));
        System.out.println("  (Better for non-stationary data)");
        System.out.println();

        // =====================================================
        // TASK 4: Seasonal Pattern-Based Detection
        // =====================================================
        System.out.println("TASK 4: Seasonal pattern-based detection");
        System.out.println("----------------------------------------");

        // Create seasonal data with anomalies
        TimeSeriesObject seasonal = createSeasonalData(365, 7, 50.0, 10.0, 5.0);
        seasonal.setLabel("daily_pattern_with_anomalies");

        // Inject seasonal anomalies
        for (int i = 0; i < 5; i++) {
            int anomalyIndex = 50 + i * 50;
            if (anomalyIndex < seasonal.yValues.size()) {
                seasonal.yValues.setElementAt((Double)seasonal.yValues.elementAt(anomalyIndex) + 30.0, anomalyIndex);
            }
        }

        // Detect using seasonal decomposition approach
        List<Anomaly> seasonalAnomalies =
                seasonalAnomalyDetection(seasonal, 7, threshold);

        System.out.println("Seasonal anomaly detection:");
        System.out.println("  Period: 7 days (weekly pattern)");
        System.out.println("  Detected anomalies: " + seasonalAnomalies.size());
        System.out.println("  (Anomalies violate expected weekly pattern)");
        System.out.println();

        // =====================================================
        // TASK 5: Consecutive Anomalies (Burst Detection)
        // =====================================================
        System.out.println("TASK 5: Burst detection (consecutive anomalies)");
        System.out.println("-----------------------------------------------");

        // Create data with burst
        TimeSeriesObject burstData = TimeSeriesObject.getGaussianDistribution(300, 100.0, 10.0);
        burstData.setLabel("data_with_burst");

        // Inject burst (20 consecutive high values)
        for (int i = 150; i < 170; i++) {
            burstData.yValues.setElementAt(130.0 + RNGWrapper.getStdRandomGaussian(0, 3), i);
        }

        // Detect bursts
        List<int[]> bursts = detectBursts(burstData, threshold, 5);

        System.out.println("Burst detection:");
        System.out.println("  Minimum burst length: 5 points");
        System.out.println("  Detected bursts: " + bursts.size());
        for (int i = 0; i < bursts.size(); i++) {
            int[] burst = bursts.get(i);
            System.out.println("  Burst " + (i + 1) + ": indices " +
                    burst[0] + "-" + burst[1] + " (length: " + (burst[1] - burst[0] + 1) + ")");
        }
        System.out.println();

        // =====================================================
        // TASK 6: Export Results for Visualization
        // =====================================================
        System.out.println("TASK 6: Export results for visualization");
        System.out.println("----------------------------------------");

        String outputDir = "data/demo_output/anomaly/";
        new java.io.File(outputDir).mkdirs();

        // Export data with anomaly markers
        exportWithAnomalies(dataWithAnomalies, detectedAnomalies,
                outputDir + "anomalies_detected.csv");

        System.out.println("Exported anomaly detection results to: " + outputDir);
        System.out.println("\nVisualization in Python:");
        System.out.println("  import pandas as pd");
        System.out.println("  import matplotlib.pyplot as plt");
        System.out.println("  df = pd.read_csv('anomalies_detected.csv')");
        System.out.println("  plt.plot(df.index, df.value, label='Data')");
        System.out.println("  plt.scatter(df[df.anomaly==1].index,");
        System.out.println("              df[df.anomaly==1].value,");
        System.out.println("              color='red', label='Anomalies')");
        System.out.println("  plt.legend()");
        System.out.println("  plt.show()");
        System.out.println();

        // =====================================================
        // Summary
        // =====================================================
        System.out.println("===========================================");
        System.out.println("Demo Completed Successfully!");
        System.out.println("===========================================");
        System.out.println("\nKey Takeaways:");
        System.out.println("✓ Implemented z-score based detection (global)");
        System.out.println("✓ Used moving windows for local anomaly detection");
        System.out.println("✓ Detected seasonal pattern violations");
        System.out.println("✓ Identified anomaly bursts");
        System.out.println("✓ Evaluated detection performance (precision/recall)");
        System.out.println("\nBest Practices:");
        System.out.println("• Use local (moving window) detection for non-stationary data");
        System.out.println("• Consider domain knowledge when setting thresholds");
        System.out.println("• Account for seasonality in periodic data");
        System.out.println("• Validate detections to reduce false positives");
        System.out.println("\nExercise:");
        System.out.println("1. Adjust the z-score threshold (try 2.5, 3.0, 3.5)");
        System.out.println("2. Observe effect on precision and recall");
        System.out.println("3. Try different window sizes for moving detection");
        System.out.println("4. Create your own time series with known anomalies");
        System.out.println("5. Implement MAD-based detection (more robust)");
        System.out.println();
    }

    // =====================================================
    // Detection Methods
    // =====================================================

    /**
     * Z-score based anomaly detection (global statistics)
     */
    private static List<Anomaly> zScoreDetection(TimeSeriesObject ts, double threshold) {
        List<Anomaly> anomalies = new ArrayList<>();
        double mean = ts.getAvarage();
        double stddev = ts.getStddev();

        for (int i = 0; i < ts.yValues.size(); i++) {
            double value = ts(Double).yValues.elementAt(i);
            double zScore = Math.abs((value - mean) / stddev);

            if (zScore > threshold) {
                anomalies.add(new Anomaly(i, value, zScore, "point"));
            }
        }

        return anomalies;
    }

    /**
     * Moving window anomaly detection (local statistics)
     */
    private static List<Anomaly> movingWindowDetection(TimeSeriesObject ts,
                                                        int windowSize,
                                                        double threshold) {
        List<Anomaly> anomalies = new ArrayList<>();
        int halfWindow = windowSize / 2;

        for (int i = halfWindow; i < ts.yValues.size() - halfWindow; i++) {
            // Calculate local statistics
            double sum = 0;
            double sumSq = 0;
            int count = 0;

            for (int j = i - halfWindow; j < i + halfWindow; j++) {
                if (j != i && j >= 0 && j < ts.yValues.size()) {
                    double val = ts(Double).yValues.elementAt(j);
                    sum += val;
                    sumSq += val * val;
                    count++;
                }
            }

            double localMean = sum / count;
            double localVariance = (sumSq / count) - (localMean * localMean);
            double localStddev = Math.sqrt(localVariance);

            double value = ts(Double).yValues.elementAt(i);
            double zScore = Math.abs((value - localMean) / localStddev);

            if (zScore > threshold) {
                anomalies.add(new Anomaly(i, value, zScore, "local"));
            }
        }

        return anomalies;
    }

    /**
     * Seasonal anomaly detection
     */
    private static List<Anomaly> seasonalAnomalyDetection(TimeSeriesObject ts,
                                                           int period,
                                                           double threshold) {
        List<Anomaly> anomalies = new ArrayList<>();

        // Simple seasonal decomposition
        for (int i = period; i < ts.yValues.size(); i++) {
            // Compare with same position in previous period
            double currentValue = ts(Double).yValues.elementAt(i);
            double previousValue = ts(Double).yValues.elementAt(i - period);
            double diff = Math.abs(currentValue - previousValue);

            // Calculate local mean and stddev for threshold
            double localMean = (currentValue + previousValue) / 2;
            double localStddev = Math.abs(currentValue - previousValue) / Math.sqrt(2);

            if (localStddev > 0) {
                double zScore = diff / localStddev;
                if (zScore > threshold) {
                    anomalies.add(new Anomaly(i, currentValue, zScore, "seasonal"));
                }
            }
        }

        return anomalies;
    }

    /**
     * Detect bursts (consecutive anomalies)
     */
    private static List<int[]> detectBursts(TimeSeriesObject ts,
                                             double threshold,
                                             int minLength) {
        List<int[]> bursts = new ArrayList<>();
        List<Anomaly> anomalies = zScoreDetection(ts, threshold);

        if (anomalies.isEmpty()) return bursts;

        int burstStart = anomalies.get(0).index;
        int burstEnd = burstStart;

        for (int i = 1; i < anomalies.size(); i++) {
            int currentIndex = anomalies.get(i).index;

            if (currentIndex == burstEnd + 1) {
                // Continue burst
                burstEnd = currentIndex;
            } else {
                // Burst ended
                if (burstEnd - burstStart + 1 >= minLength) {
                    bursts.add(new int[]{burstStart, burstEnd});
                }
                burstStart = currentIndex;
                burstEnd = currentIndex;
            }
        }

        // Check last burst
        if (burstEnd - burstStart + 1 >= minLength) {
            bursts.add(new int[]{burstStart, burstEnd});
        }

        return bursts;
    }

    /**
     * Create seasonal time series
     */
    private static TimeSeriesObject createSeasonalData(int length, int period,
                                                        double baseline, double amplitude,
                                                        double noise) {
        TimeSeriesObject ts = new TimeSeriesObject();

        for (int i = 0; i < length; i++) {
            double seasonal = amplitude * Math.sin(2 * Math.PI * i / period);
            double randomNoise = RNGWrapper.getStdRandomGaussian(0, noise);
            double value = baseline + seasonal + randomNoise;
            ts.addValuePair(i, value);
        }

        return ts;
    }

    /**
     * Export time series with anomaly markers
     */
    private static void exportWithAnomalies(TimeSeriesObject ts,
                                            List<Anomaly> anomalies,
                                            String filename) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("index,value,anomaly,z_score\n");

        for (int i = 0; i < ts.yValues.size(); i++) {
            boolean isAnomaly = false;
            double zScore = 0.0;

            for (Anomaly anomaly : anomalies) {
                if (anomaly.index == i) {
                    isAnomaly = true;
                    zScore = anomaly.zScore;
                    break;
                }
            }

            sb.append(i).append(",")
              .append(ts(Double).yValues.elementAt(i)).append(",")
              .append(isAnomaly ? 1 : 0).append(",")
              .append(String.format("%.4f", zScore)).append("\n");
        }

        java.nio.file.Files.write(
            java.nio.file.Paths.get(filename),
            sb.toString().getBytes()
        );
    }
}
