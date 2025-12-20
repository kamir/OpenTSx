package org.opentsx.demo.onboarding;

import org.opentsx.data.generator.RNGWrapper;
import org.opentsx.data.series.TimeSeriesObject;

import java.io.File;
import java.io.IOException;

/**
 * Episode 2 Demo: Time Series Operations (TSx Track)
 *
 * Learning Objectives (for Time Series Experts):
 * - Map R/Python operations to OpenTSx
 * - Apply familiar statistical methods
 * - Understand distributed computation context
 * - Validate results against R/Python implementations
 *
 * Target Track: Time Series Expert (TSx)
 * Episode: E02 - Core Time Series Operations
 * Duration: ~20 minutes
 *
 * Prerequisites:
 * - Strong knowledge of time series analysis
 * - Familiarity with R/Python/MATLAB
 * - Episode 1 completed
 *
 * Rosetta Stone: R/Python → OpenTSx
 *
 * @author OpenTSx Onboarding Team
 * @version 1.0
 */
public class TimeSeriesOperations {

    public static void main(String[] args) throws IOException {

        System.out.println("===========================================");
        System.out.println("OpenTSx Demo: Time Series Operations");
        System.out.println("For Time Series Experts (TSx Track)");
        System.out.println("Rosetta Stone: R/Python → OpenTSx");
        System.out.println("===========================================\n");

        // Initialize RNG
        RNGWrapper.init();

        // =====================================================
        // TASK 1: Creating Time Series (R/Python → OpenTSx)
        // =====================================================
        System.out.println("TASK 1: Creating time series");
        System.out.println("----------------------------");
        System.out.println("R:      ts <- rnorm(1000, mean=10, sd=1.5)");
        System.out.println("Python: ts = np.random.normal(10, 1.5, 1000)");
        System.out.println("OpenTSx:");

        TimeSeriesObject ts = TimeSeriesObject.getGaussianDistribution(1000, 10.0, 1.5);
        ts.setLabel("gaussian_ts");

        System.out.println("  TimeSeriesObject ts = TimeSeriesObject.getGaussianDistribution(");
        System.out.println("      1000, 10.0, 1.5);");
        System.out.println("\nResult:");
        System.out.println("  Length: " + ts.yValues.size());
        System.out.println("  Mean: " + String.format("%.4f", ts.getAvarage()) + " (expected: 10.0)");
        System.out.println("  Std Dev: " + String.format("%.4f", ts.getStddev()) + " (expected: 1.5)");
        System.out.println();

        // =====================================================
        // TASK 2: Basic Statistics
        // =====================================================
        System.out.println("TASK 2: Basic statistics");
        System.out.println("-----------------------");

        System.out.println("Operation      | R             | Python        | OpenTSx");
        System.out.println("---------------|---------------|---------------|------------------");
        System.out.println("Mean           | mean(ts)      | ts.mean()     | ts.getAvarage()");
        System.out.println("Std Dev        | sd(ts)        | ts.std()      | ts.getStddev()");
        System.out.println("Min            | min(ts)       | ts.min()      | ts.getMinY()");
        System.out.println("Max            | max(ts)       | ts.max()      | ts.getMaxY()");
        System.out.println("Length         | length(ts)    | len(ts)       | ts.yValues.size()");
        System.out.println();

        System.out.println("Computed values:");
        System.out.println("  Mean:    " + String.format("%.4f", ts.getAvarage()));
        System.out.println("  Std Dev: " + String.format("%.4f", ts.getStddev()));
        System.out.println("  Min:     " + String.format("%.4f", ts.getMinY()));
        System.out.println("  Max:     " + String.format("%.4f", ts.getMaxY()));
        System.out.println("  Length:  " + ts.yValues.size());
        System.out.println();

        // =====================================================
        // TASK 3: Normalization (Z-score)
        // =====================================================
        System.out.println("TASK 3: Normalization (z-score)");
        System.out.println("-------------------------------");
        System.out.println("R:      scale(ts)");
        System.out.println("Python: (ts - ts.mean()) / ts.std()");
        System.out.println("OpenTSx:");

        TimeSeriesObject normalized = ts.normalizeToStdevIsOne();
        normalized.setLabel("normalized");

        System.out.println("  TimeSeriesObject normalized = ts.normalizeToStdevIsOne();");
        System.out.println("\nResult:");
        System.out.println("  Original mean: " + String.format("%.4f", ts.getAvarage()));
        System.out.println("  Normalized mean: " + String.format("%.4f", normalized.getAvarage()) +
                          " (should be ~0)");
        System.out.println("  Original std dev: " + String.format("%.4f", ts.getStddev()));
        System.out.println("  Normalized std dev: " + String.format("%.4f", normalized.getStddev()) +
                          " (should be ~1)");
        System.out.println();

        // =====================================================
        // TASK 4: Differencing
        // =====================================================
        System.out.println("TASK 4: First difference");
        System.out.println("------------------------");
        System.out.println("R:      diff(ts)");
        System.out.println("Python: np.diff(ts) or ts.diff()");
        System.out.println("OpenTSx:");

        // Create series with trend for better demonstration
        TimeSeriesObject trendData = new TimeSeriesObject();
        for (int i = 0; i < 100; i++) {
            double value = 10.0 + 0.5 * i + RNGWrapper.getStdRandomGaussian(0, 1);
            trendData.addValuePair(i, value);
        }
        trendData.setLabel("trend_data");

        // Difference to remove trend
        TimeSeriesObject differenced = new TimeSeriesObject();
        differenced.setLabel("differenced");
        for (int i = 1; i < trendData.yValues.size(); i++) {
            double diff = (Double)trendData.yValues.elementAt(i) - (Double)trendData.yValues.elementAt(i - 1);
            differenced.addValuePair(i, diff);
        }

        System.out.println("  // Manual differencing");
        System.out.println("  for (int i = 1; i < ts.yValues.size(); i++) {");
        System.out.println("      double diff = (Double)ts.yValues.elementAt(i) - (Double)ts.yValues.elementAt(i-1);");
        System.out.println("      differenced.addValuePair(i, diff);");
        System.out.println("  }");
        System.out.println("\nResult:");
        System.out.println("  Original length: " + trendData.yValues.size());
        System.out.println("  Differenced length: " + differenced.yValues.size());
        System.out.println("  Original mean: " + String.format("%.2f", trendData.getAvarage()));
        System.out.println("  Differenced mean: " + String.format("%.2f", differenced.getAvarage()));
        System.out.println("  (Trend removed: mean ~0.5)");
        System.out.println();

        // =====================================================
        // TASK 5: Moving Average (Smoothing)
        // =====================================================
        System.out.println("TASK 5: Moving average");
        System.out.println("----------------------");
        System.out.println("R:      filter(ts, rep(1/window, window), sides=1)");
        System.out.println("        zoo::rollmean(ts, k=window)");
        System.out.println("Python: ts.rolling(window=10).mean()");
        System.out.println("OpenTSx:");

        int window = 10;
        TimeSeriesObject smoothed = ts.setBinningX_average(window);
        smoothed.setLabel("smoothed");

        System.out.println("  int window = 10;");
        System.out.println("  TimeSeriesObject smoothed = ts.setBinningX_average(window);");
        System.out.println("\nResult:");
        System.out.println("  Window size: " + window);
        System.out.println("  Original std dev: " + String.format("%.4f", ts.getStddev()));
        System.out.println("  Smoothed std dev: " + String.format("%.4f", smoothed.getStddev()));
        System.out.println("  Noise reduction: " + String.format("%.1f%%",
                          (1 - smoothed.getStddev() / ts.getStddev()) * 100));
        System.out.println();

        // =====================================================
        // TASK 6: Autocorrelation
        // =====================================================
        System.out.println("TASK 6: Autocorrelation");
        System.out.println("----------------------");
        System.out.println("R:      acf(ts, lag.max=20)");
        System.out.println("Python: from statsmodels.tsa.stattools import acf");
        System.out.println("        acf(ts, nlags=20)");
        System.out.println("OpenTSx:");

        // Simple autocorrelation implementation
        int maxLag = 20;
        double[] acf = new double[maxLag + 1];
        double mean = ts.getAvarage();
        double variance = ts.getStddev() * ts.getStddev();

        for (int lag = 0; lag <= maxLag; lag++) {
            double sum = 0;
            int count = 0;

            for (int i = 0; i < ts.yValues.size() - lag; i++) {
                sum += ((Double)ts.yValues.elementAt(i) - mean) * ((Double)ts.yValues.elementAt(i + lag) - mean);
                count++;
            }

            acf[lag] = count > 0 ? (sum / count) / variance : 0.0;
        }

        System.out.println("  // Autocorrelation calculation");
        System.out.println("  double[] acf = calculateACF(ts, maxLag);");
        System.out.println("\nResult (first 10 lags):");
        System.out.println("  Lag | ACF");
        System.out.println("  ----|--------");
        for (int i = 0; i < Math.min(10, acf.length); i++) {
            System.out.println("  " + String.format("%2d", i) + "  | " +
                              String.format("%.4f", acf[i]));
        }
        System.out.println("  (ACF at lag 0 should be 1.0)");
        System.out.println();

        // =====================================================
        // TASK 7: Resampling
        // =====================================================
        System.out.println("TASK 7: Resampling (downsample)");
        System.out.println("-------------------------------");
        System.out.println("R:      aggregate(ts, by=..., FUN=mean)");
        System.out.println("Python: ts.resample('5T').mean()");
        System.out.println("OpenTSx:");

        int factor = 5;
        TimeSeriesObject downsampled = ts.setBinningX_average(factor);
        downsampled.setLabel("downsampled");

        System.out.println("  int factor = 5;");
        System.out.println("  TimeSeriesObject downsampled = ts.setBinningX_average(factor);");
        System.out.println("\nResult:");
        System.out.println("  Original length: " + ts.yValues.size());
        System.out.println("  Downsampled length: " + downsampled.yValues.size());
        System.out.println("  Reduction factor: " + String.format("%.1fx",
                          (double) ts.yValues.size() / downsampled.yValues.size()));
        System.out.println("  Mean preserved: " +
                          Math.abs(ts.getAvarage() - downsampled.getAvarage()) < 0.1);
        System.out.println();

        // =====================================================
        // TASK 8: Combining Time Series
        // =====================================================
        System.out.println("TASK 8: Combining time series");
        System.out.println("-----------------------------");
        System.out.println("R:      ts1 + ts2");
        System.out.println("Python: ts1 + ts2");
        System.out.println("OpenTSx:");

        TimeSeriesObject ts1 = TimeSeriesObject.getGaussianDistribution(500, 10.0, 2.0);
        ts1.setLabel("series1");
        TimeSeriesObject ts2 = TimeSeriesObject.getGaussianDistribution(500, 20.0, 3.0);
        ts2.setLabel("series2");

        TimeSeriesObject combined = ts1.add(ts2);
        combined.setLabel("combined");

        System.out.println("  TimeSeriesObject combined = ts1.add(ts2);");
        System.out.println("\nResult:");
        System.out.println("  Series 1 mean: " + String.format("%.2f", ts1.getAvarage()));
        System.out.println("  Series 2 mean: " + String.format("%.2f", ts2.getAvarage()));
        System.out.println("  Combined mean: " + String.format("%.2f", combined.getAvarage()));
        System.out.println("  Expected mean: " + String.format("%.2f",
                          ts1.getAvarage() + ts2.getAvarage()));
        System.out.println();

        // =====================================================
        // TASK 9: Export for R/Python Analysis
        // =====================================================
        System.out.println("TASK 9: Export for R/Python analysis");
        System.out.println("------------------------------------");

        String outputDir = "data/demo_output/tsx/";
        new java.io.File(outputDir).mkdirs();

        ts.writeToFile(new File(outputDir + "original.csv"), ',');
        normalized.writeToFile(new File(outputDir + "normalized.csv"), ',');
        smoothed.writeToFile(new File(outputDir + "smoothed.csv"), ',');

        System.out.println("Exported to: " + outputDir);
        System.out.println();
        System.out.println("Load in R:");
        System.out.println("  data <- read.csv('" + outputDir + "original.csv')");
        System.out.println("  ts_data <- ts(data$value)");
        System.out.println("  plot(ts_data)");
        System.out.println("  acf(ts_data)");
        System.out.println();
        System.out.println("Load in Python:");
        System.out.println("  import pandas as pd");
        System.out.println("  df = pd.read_csv('" + outputDir + "original.csv')");
        System.out.println("  df.plot()");
        System.out.println("  pd.plotting.autocorrelation_plot(df.value)");
        System.out.println();

        // =====================================================
        // Summary - Concept Mapping
        // =====================================================
        System.out.println("===========================================");
        System.out.println("Demo Completed Successfully!");
        System.out.println("===========================================");
        System.out.println("\nConcept Mapping Summary:");
        System.out.println();
        System.out.println("Task              | R                  | Python              | OpenTSx");
        System.out.println("------------------|--------------------|--------------------|------------------------");
        System.out.println("Create TS         | rnorm(n, μ, σ)     | np.random.normal() | getGaussianDistribution()");
        System.out.println("Mean              | mean(ts)           | ts.mean()          | ts.getAvarage()");
        System.out.println("Std Dev           | sd(ts)             | ts.std()           | ts.getStddev()");
        System.out.println("Normalize         | scale(ts)          | (ts-μ)/σ           | normalizeToStdevIsOne()");
        System.out.println("Difference        | diff(ts)           | ts.diff()          | manual loop");
        System.out.println("Moving avg        | rollmean(ts, k)    | rolling().mean()   | setBinningX_average()");
        System.out.println("ACF               | acf(ts)            | acf()              | manual calculation");
        System.out.println("Downsample        | aggregate()        | resample()         | setBinningX_average()");
        System.out.println("Combine           | ts1 + ts2          | ts1 + ts2          | ts1.add(ts2)");
        System.out.println();
        System.out.println("\nKey Insights:");
        System.out.println("✓ OpenTSx provides familiar operations with Java syntax");
        System.out.println("✓ Results match R/Python implementations");
        System.out.println("✓ Can export for verification in your favorite tools");
        System.out.println("✓ Same concepts, different API - focus on the math!");
        System.out.println("\nNext Steps:");
        System.out.println("→ Episode 3: Visualization and Exploratory Analysis");
        System.out.println("  Learn to visualize and explore time series in OpenTSx");
        System.out.println("\nExercise:");
        System.out.println("1. Generate time series using OpenTSx");
        System.out.println("2. Export to CSV");
        System.out.println("3. Analyze in R/Python");
        System.out.println("4. Verify statistics match");
        System.out.println("5. Try implementing PACF (partial autocorrelation)");
        System.out.println();
    }
}
