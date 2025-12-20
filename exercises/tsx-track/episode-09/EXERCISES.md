# Episode 9 Exercises: Statistical Analysis

**Learning Track:** TSx (Time Series Experts from R/Python/MATLAB)
**Difficulty:** Intermediate
**Estimated Time:** 75 minutes
**Prerequisites:** Episodes 2-3, Statistical analysis background

## Learning Objectives

By completing these exercises, you will:
- Implement common statistical methods in Java/OpenTSx
- Translate R/Python concepts to OpenTSx idioms
- Perform moving averages and smoothing
- Detect trends and seasonality
- Calculate autocorrelation
- Identify anomalies using statistical methods

## Setup

Before starting, ensure:
1. Project built: `./bin/010_build.sh`
2. Episodes 2-3 completed
3. Episode 9 demo reviewed: `./bin/episode_09_analysis.sh`

## R/Python to OpenTSx Quick Reference

| R/Python | OpenTSx | Notes |
|----------|---------|-------|
| `mean(x)` | `ts.getAvarage()` | Note API typo |
| `sd(x)` | `ts.getStddev()` | Standard deviation |
| `length(x)` | `ts.yValues.size()` | Number of points |
| `x[i]` | `(Double)ts.yValues.elementAt(i)` | Type cast required |
| `min(x)`, `max(x)` | `ts.getMinY()`, `ts.getMaxY()` | |
| `sum(x)` | `ts.summeY()` | Note German naming |

## Exercise 1: Moving Average Implementation (20 minutes)

**Goal:** Implement simple and weighted moving averages (like R's `filter()` or Python's `rolling()`).

**Background:**
In R: `filter(x, rep(1/n, n), sides=2)`
In Python: `df.rolling(window=n).mean()`

**Task:**
Implement three moving average functions:
1. Simple Moving Average (SMA)
2. Weighted Moving Average (WMA) - more weight to recent values
3. Exponential Moving Average (EMA)

**Starter Code:**
```java
package org.opentsx.exercises.tsx;

import org.opentsx.data.series.TimeSeriesObject;

public class Exercise1_MovingAverages {

    /**
     * Simple Moving Average
     * R equivalent: filter(x, rep(1/window, window), sides=1)
     */
    public static TimeSeriesObject sma(TimeSeriesObject input, int window) {
        TimeSeriesObject result = new TimeSeriesObject();
        result.setLabel(input.getLabel() + "_SMA" + window);

        // TODO: Implement SMA
        // For each point i where i >= window-1:
        //   Calculate mean of [i-window+1, ..., i]

        return result;
    }

    /**
     * Weighted Moving Average
     * More recent values get higher weight
     */
    public static TimeSeriesObject wma(TimeSeriesObject input, int window) {
        TimeSeriesObject result = new TimeSeriesObject();
        result.setLabel(input.getLabel() + "_WMA" + window);

        // TODO: Implement WMA
        // Weights: 1, 2, 3, ..., window
        // Normalized by sum of weights

        return result;
    }

    /**
     * Exponential Moving Average
     * Python equivalent: df.ewm(alpha=alpha).mean()
     */
    public static TimeSeriesObject ema(TimeSeriesObject input, double alpha) {
        TimeSeriesObject result = new TimeSeriesObject();
        result.setLabel(input.getLabel() + "_EMA");

        // TODO: Implement EMA
        // EMA[t] = alpha * Y[t] + (1-alpha) * EMA[t-1]
        // Start with EMA[0] = Y[0]

        return result;
    }

    public static void main(String[] args) {
        // Create noisy sine wave (like R: x <- sin(seq(0, 4*pi, length=200)) + rnorm(200, 0, 0.3))
        TimeSeriesObject noisy = createNoisySineWave(200, 0.3);

        // Apply different smoothing methods
        TimeSeriesObject sma5 = sma(noisy, 5);
        TimeSeriesObject sma20 = sma(noisy, 20);
        TimeSeriesObject wma10 = wma(noisy, 10);
        TimeSeriesObject ema10 = ema(noisy, 0.2);

        // Save for comparison
        // Compare smoothness vs. lag trade-off
    }

    private static TimeSeriesObject createNoisySineWave(int n, double noiseLevel) {
        // TODO: Implement helper
        return null;
    }
}
```

**Expected Behavior:**
- SMA with larger window = smoother but more lag
- WMA = less lag than SMA, still smooth
- EMA = responsive to recent changes

---

## Exercise 2: Autocorrelation Function (ACF) (20 minutes)

**Goal:** Implement autocorrelation calculation (like R's `acf()` or Python's `autocorr()`).

**Background:**
- R: `acf(x, lag.max=20, plot=FALSE)`
- Python: `pd.Series.autocorr(lag=k)` or `statsmodels.tsa.stattools.acf()`

**Task:**
1. Implement autocorrelation for a given lag
2. Calculate ACF for lags 0 to 30
3. Identify significant lags (> 2/√n threshold)
4. Detect periodicity from ACF peaks

**Starter Code:**
```java
package org.opentsx.exercises.tsx;

import org.opentsx.data.series.TimeSeriesObject;

public class Exercise2_Autocorrelation {

    /**
     * Calculate autocorrelation at specific lag
     * R equivalent: acf(x, lag.max=lag, plot=FALSE)$acf[lag+1]
     */
    public static double acf(TimeSeriesObject ts, int lag) {
        // TODO: Implement ACF
        // Formula: Σ((x[t] - mean) * (x[t+lag] - mean)) / Σ((x[t] - mean)^2)

        return 0.0;
    }

    /**
     * Calculate ACF for multiple lags
     * Returns TimeSeriesObject with lag as X, correlation as Y
     */
    public static TimeSeriesObject acfSeries(TimeSeriesObject ts, int maxLag) {
        TimeSeriesObject result = new TimeSeriesObject();
        result.setLabel("ACF_" + ts.getLabel());

        // TODO: Calculate ACF for lags 0 to maxLag

        return result;
    }

    public static void main(String[] args) {
        // Test 1: White noise (should have no significant autocorrelation)
        TimeSeriesObject noise = TimeSeriesObject.getGaussianDistribution(200, 0.0, 1.0);

        // Test 2: AR(1) process (should show exponential decay)
        TimeSeriesObject ar1 = createAR1(200, 0.7);

        // Test 3: Seasonal data (should show peaks at seasonal lags)
        TimeSeriesObject seasonal = createSeasonalData(200, 12);

        // Calculate and compare ACF
        // Identify patterns
    }

    private static TimeSeriesObject createAR1(int n, double phi) {
        // TODO: Implement AR(1): x[t] = phi * x[t-1] + noise
        return null;
    }

    private static TimeSeriesObject createSeasonalData(int n, int period) {
        // TODO: Implement seasonal pattern
        return null;
    }
}
```

**Expected Results:**
- White noise: ACF ≈ 0 for all lags > 0
- AR(1): Exponential decay
- Seasonal: Peaks at multiples of period

---

## Exercise 3: Trend Detection and Detrending (20 minutes)

**Goal:** Fit linear trends and remove them (like R's `lm()` or Python's `scipy.stats.linregress()`).

**Background:**
- R: `lm(y ~ x)` then `residuals(model)`
- Python: `scipy.stats.linregress()` or `statsmodels.api.OLS()`

**Task:**
1. Fit linear trend to time series
2. Extract trend line
3. Calculate residuals (detrended series)
4. Verify residuals have no trend

**Starter Code:**
```java
package org.opentsx.exercises.tsx;

import org.opentsx.data.series.TimeSeriesObject;

public class Exercise3_TrendDetection {

    public static class LinearFit {
        public double slope;
        public double intercept;
        public double rSquared;

        public double predict(double x) {
            return slope * x + intercept;
        }
    }

    /**
     * Fit linear trend using least squares
     * R equivalent: lm(y ~ x)
     */
    public static LinearFit fitLinearTrend(TimeSeriesObject ts) {
        // TODO: Implement linear regression
        // slope = Σ((x-x̄)(y-ȳ)) / Σ((x-x̄)²)
        // intercept = ȳ - slope*x̄

        return null;
    }

    /**
     * Create detrended series (residuals)
     * R equivalent: residuals(lm(y ~ x))
     */
    public static TimeSeriesObject detrend(TimeSeriesObject ts) {
        TimeSeriesObject residuals = new TimeSeriesObject();
        residuals.setLabel(ts.getLabel() + "_detrended");

        // TODO: Fit trend, subtract from original

        return residuals;
    }

    public static void main(String[] args) {
        // Create series with trend
        TimeSeriesObject withTrend = createTrendPlusNoise(500, 0.1, 10.0, 2.0);

        // Fit and analyze trend
        LinearFit fit = fitLinearTrend(withTrend);

        System.out.println("Trend Analysis:");
        System.out.println("  Slope: " + fit.slope);
        System.out.println("  Intercept: " + fit.intercept);
        System.out.println("  R²: " + fit.rSquared);

        // Detrend
        TimeSeriesObject detrended = detrend(withTrend);

        // Verify no remaining trend
        LinearFit detrendedFit = fitLinearTrend(detrended);
        System.out.println("\nDetrended Slope (should be ~0): " + detrendedFit.slope);
    }

    private static TimeSeriesObject createTrendPlusNoise(int n, double slope,
                                                          double intercept, double noise) {
        // TODO: y = slope*x + intercept + rnorm(0, noise)
        return null;
    }
}
```

---

## Exercise 4: Anomaly Detection (Z-Score Method) (15 minutes)

**Goal:** Implement outlier detection using Z-scores (like R's `scale()` or Python's `scipy.stats.zscore()`).

**Task:**
1. Calculate Z-scores for all points
2. Flag points with |Z| > threshold (typically 2 or 3)
3. Create filtered series excluding anomalies
4. Report anomaly statistics

**Starter Code:**
```java
package org.opentsx.exercises.tsx;

import org.opentsx.data.series.TimeSeriesObject;

public class Exercise4_AnomalyDetection {

    /**
     * Detect anomalies using Z-score method
     * R equivalent: which(abs(scale(x)) > threshold)
     */
    public static int[] detectAnomalies(TimeSeriesObject ts, double threshold) {
        // TODO: Calculate Z-scores, find indices where |Z| > threshold
        return null;
    }

    /**
     * Remove anomalies from series
     */
    public static TimeSeriesObject removeAnomalies(TimeSeriesObject ts, double threshold) {
        // TODO: Filter out anomalous points
        return null;
    }

    public static void main(String[] args) {
        // Create series with injected anomalies
        TimeSeriesObject data = TimeSeriesObject.getGaussianDistribution(200, 50.0, 10.0);

        // Inject 5 anomalies
        injectAnomalies(data, new int[]{20, 50, 100, 150, 180}, 50.0);

        // Detect
        int[] anomalies = detectAnomalies(data, 3.0);

        System.out.println("Detected " + anomalies.length + " anomalies");
        System.out.println("Expected: 5 anomalies");

        // Remove and compare statistics
    }

    private static void injectAnomalies(TimeSeriesObject ts, int[] indices, double magnitude) {
        // TODO: Add magnitude to specified indices
    }
}
```

---

## Bonus Exercise: Seasonal Decomposition (Optional, 25 minutes)

**Goal:** Decompose series into trend + seasonal + residual (like R's `decompose()` or Python's `seasonal_decompose()`).

**Starter Code:**
```java
public class BonusExercise_SeasonalDecomposition {
    // Implement additive decomposition: Y = Trend + Seasonal + Residual
    // 1. Extract trend using moving average
    // 2. Detrend to get seasonal + residual
    // 3. Average seasonal + residual by period to get seasonal component
    // 4. Subtract trend and seasonal to get residual
}
```

---

## Validation Checklist

- [ ] Moving averages smooth data effectively
- [ ] ACF correctly identifies correlations
- [ ] Trend fitting produces reasonable R²
- [ ] Detrended series has near-zero slope
- [ ] Anomaly detection finds injected outliers
- [ ] All statistics match expected values

## Common Translation Issues

1. **Indexing:**
   - R/Python: 0-based (Python) or 1-based (R)
   - Java/OpenTSx: Always 0-based

2. **Vectorization:**
   - R/Python: Vectorized operations
   - Java: Explicit loops required

3. **NA/NaN Handling:**
   - R: `na.omit()`, `na.rm=TRUE`
   - OpenTSx: Manual null checks

## Next Steps

1. Review solutions in `exercises/solutions/tsx-track/episode-09/`
2. Compare with your R/Python implementations
3. Try the bonus seasonal decomposition
4. Move to production patterns (Episode 10)

## Resources

- [Statistical Analysis](../../docs/manual/statistical-analysis/README.md)
- [R/Python Translation Guide](../../docs/manual/appendix/r-python-translation.md)
- [Advanced Algorithms](../../FEATURES.md)

---

**Need Help?**
- Review Episode 9 demo
- Check R/Python documentation for algorithm details
- Consult solution files after attempting exercises
