# Analyzing Time Series

Statistical analysis transforms raw time series into actionable insights. OpenTSx provides both built-in methods and the foundation for custom analytical techniques.

## The Analytical Mindset

Time series analysis differs from traditional statistics in fundamental ways:

### Temporal Dependencies

Values are not independent — today's temperature correlates with yesterday's. This violates the IID (independent and identically distributed) assumption of classical statistics, requiring specialized methods.

### Patterns Over Time

Time series exhibit:
- **Trends** — Long-term increase or decrease
- **Seasonality** — Repeating patterns at fixed intervals
- **Cycles** — Irregular fluctuations
- **Noise** — Random variation

Effective analysis separates signal from noise.

### Context Matters

A value of 100 might be:
- Normal in one context (temperature in Fahrenheit)
- An anomaly in another (human heart rate)

Analysis must consider domain knowledge alongside statistical measures.

## Built-in Statistical Methods

### Descriptive Statistics

Summarize distributions:

```java
double mean = ts.getAvarage();      // Central tendency
double stddev = ts.getStddev();     // Spread
double min = ts.getMinY();          // Range
double max = ts.getMaxY();
double sum = ts.summeY();           // Total
```

These form the foundation for more complex analyses.

### Normalization

Standardize to comparable scales:

```java
// Z-score normalization: (x - μ) / σ
TimeSeriesObject normalized = ts.normalizeToStdevIsOne();
// Result: mean ≈ 0, stddev ≈ 1
```

**Why normalize?**
- Compare time series with different units
- Prepare data for machine learning
- Highlight relative rather than absolute changes

### Centering

Remove mean without scaling:

```java
TimeSeriesObject centered = ts.subtractAverage();
// Result: mean ≈ 0, stddev unchanged
```

Useful for:
- Detecting deviations from baseline
- Correlation analysis
- Removing DC offset

## Custom Analytical Methods

OpenTSx's transparent data model enables custom statistics:

### Moving Statistics

Sliding window calculations:

```java
public static double movingAverage(TimeSeriesObject ts, int windowSize, int position) {
    int start = Math.max(0, position - windowSize/2);
    int end = Math.min(ts.yValues.size(), position + windowSize/2 + 1);

    double sum = 0;
    for (int i = start; i < end; i++) {
        sum += (Double) ts.yValues.elementAt(i);
    }
    return sum / (end - start);
}
```

Apply to every point:

```java
TimeSeriesObject smoothed = new TimeSeriesObject();
for (int i = 0; i < ts.yValues.size(); i++) {
    double avg = movingAverage(ts, 5, i);
    smoothed.addValuePair(i, avg);
}
```

### Autocorrelation

Measure temporal dependencies:

```java
public static double autocorrelation(TimeSeriesObject ts, int lag) {
    double mean = ts.getAvarage();
    double variance = ts.getStddev() * ts.getStddev();

    double sum = 0;
    int count = 0;

    for (int i = 0; i < ts.yValues.size() - lag; i++) {
        double y_i = (Double) ts.yValues.elementAt(i);
        double y_lag = (Double) ts.yValues.elementAt(i + lag);
        sum += (y_i - mean) * (y_lag - mean);
        count++;
    }

    return (sum / count) / variance;
}
```

Returns value in [-1, 1]:
- 1: perfect positive correlation
- 0: no correlation
- -1: perfect negative correlation

### Trend Detection

Simple linear trend estimation:

```java
// Compare first and last portions
double firstAvg = average(ts, 0, 50);
double lastAvg = average(ts, ts.yValues.size() - 50, ts.yValues.size());
double slope = (lastAvg - firstAvg) / ts.yValues.size();

if (Math.abs(slope) > threshold) {
    System.out.println("Trend detected: " + slope);
}
```

### Change Point Detection

Identify regime shifts:

```java
int windowSize = 30;
double maxDiff = 0;
int changePoint = 0;

for (int i = windowSize; i < ts.yValues.size() - windowSize; i++) {
    double before = average(ts, i - windowSize, i);
    double after = average(ts, i, i + windowSize);
    double diff = Math.abs(after - before);

    if (diff > maxDiff) {
        maxDiff = diff;
        changePoint = i;
    }
}

System.out.println("Change detected at position: " + changePoint);
```

## Anomaly Detection

Identify unusual observations:

### Z-Score Method

Flag points beyond threshold standard deviations:

```java
double mean = ts.getAvarage();
double stddev = ts.getStddev();
double threshold = 3.0;  // 3 sigma

List<Integer> anomalies = new ArrayList<>();
for (int i = 0; i < ts.yValues.size(); i++) {
    double y = (Double) ts.yValues.elementAt(i);
    double zScore = Math.abs((y - mean) / stddev);

    if (zScore > threshold) {
        anomalies.add(i);
    }
}
```

### Moving Window Anomalies

Context-aware detection:

```java
int halfWindow = 15;
for (int i = halfWindow; i < ts.yValues.size() - halfWindow; i++) {
    // Calculate local statistics
    double localMean = average(ts, i - halfWindow, i + halfWindow);
    double localStddev = stddev(ts, i - halfWindow, i + halfWindow);

    double value = (Double) ts.yValues.elementAt(i);
    double localZScore = Math.abs((value - localMean) / localStddev);

    if (localZScore > threshold) {
        System.out.println("Anomaly at " + i + ": " + value);
    }
}
```

Adapts to local context, catching anomalies in non-stationary series.

## Resampling and Aggregation

### Downsampling

Reduce temporal resolution while preserving characteristics:

```java
// Average-based downsampling
int binSize = 10;
TimeSeriesObject downsampled = ts.setBinningX_average(binSize);

// Sum-based (for count data)
TimeSeriesObject summed = ts.setBinningX_sum(binSize);
```

**When to downsample:**
- Visualization of long time series
- Noise reduction
- Storage optimization
- Computational efficiency

### Upsampling

Increase resolution (requires interpolation logic):

```java
// Linear interpolation between points
TimeSeriesObject upsampled = new TimeSeriesObject();
for (int i = 0; i < ts.yValues.size() - 1; i++) {
    double y1 = (Double) ts.yValues.elementAt(i);
    double y2 = (Double) ts.yValues.elementAt(i + 1);

    upsampled.addValue(y1);
    upsampled.addValue((y1 + y2) / 2.0);  // Midpoint
}
upsampled.addValue((Double) ts.yValues.elementAt(ts.yValues.size() - 1));
```

## Comparative Analysis

### Correlation Between Series

Measure similarity:

```java
public static double correlation(TimeSeriesObject ts1, TimeSeriesObject ts2) {
    if (ts1.yValues.size() != ts2.yValues.size()) {
        throw new IllegalArgumentException("Series must have same length");
    }

    double mean1 = ts1.getAvarage();
    double mean2 = ts2.getAvarage();
    double std1 = ts1.getStddev();
    double std2 = ts2.getStddev();

    double sum = 0;
    for (int i = 0; i < ts1.yValues.size(); i++) {
        double y1 = (Double) ts1.yValues.elementAt(i);
        double y2 = (Double) ts2.yValues.elementAt(i);
        sum += ((y1 - mean1) / std1) * ((y2 - mean2) / std2);
    }

    return sum / ts1.yValues.size();
}
```

### Distance Metrics

Quantify dissimilarity:

```java
// Euclidean distance
double distance = 0;
for (int i = 0; i < ts1.yValues.size(); i++) {
    double diff = (Double) ts1.yValues.elementAt(i) -
                  (Double) ts2.yValues.elementAt(i);
    distance += diff * diff;
}
distance = Math.sqrt(distance);
```

## Visualization Integration

OpenTSx integrates with external tools:

### Export for R

```java
ts.writeToFile(new File("analysis.csv"), ',');
```

Then in R:
```r
data <- read.csv("analysis.csv")
plot(data$x, data$y, type='l')
acf(data$y)  # Autocorrelation function
spectrum(data$y)  # Spectral analysis
```

### Export for Python

```python
import pandas as pd
import matplotlib.pyplot as plt

df = pd.read_csv("analysis.csv")
df.plot(x='x', y='y')
plt.show()

# Statistical analysis
from scipy import stats
from statsmodels.tsa.seasonal import seasonal_decompose
```

## Best Practices

1. **Visualize first** — Plot before computing
2. **Check assumptions** — Verify stationarity, independence
3. **Use domain knowledge** — Statistics alone aren't enough
4. **Validate on synthetic data** — Test methods with known ground truth
5. **Report confidence** — Include uncertainty estimates

## Advanced Topics

- **Spectral Analysis** — Frequency domain methods (FFT)
- **State Space Models** — Kalman filtering
- **Wavelet Analysis** — Multi-scale decomposition
- **Machine Learning** — LSTM, forecasting models

These require custom implementation or integration with specialized libraries.

## Learning Path

Explore specific analytical techniques:

1. **[Descriptive Statistics](descriptive-stats.md)** — Basic summaries
2. **[Normalization](normalization.md)** — Standardization methods
3. **[Moving Averages](moving-averages.md)** — Smoothing techniques
4. **[Trends](trends.md)** — Long-term patterns
5. **[Seasonality](seasonality.md)** — Periodic patterns
6. **[Autocorrelation](autocorrelation.md)** — Temporal dependencies
7. **[Anomaly Detection](anomaly-detection.md)** — Outlier identification
8. **[Change Points](change-points.md)** — Regime detection

---

**Continue to:** [Descriptive Statistics →](descriptive-stats.md)
