# Generating Synthetic Data

Synthetic time series are invaluable for testing, benchmarking, and understanding algorithmic behavior. OpenTSx provides built-in generators for common statistical distributions.

## Why Synthetic Data?

### Testing and Validation

Known distributions enable verification:
```java
TimeSeriesObject ts = TimeSeriesObject.getGaussianDistribution(10000, 100.0, 15.0);
double mean = ts.getAvarage();
double stddev = ts.getStddev();
// Verify: mean ≈ 100.0, stddev ≈ 15.0
```

If your algorithm claims to preserve mean during transformation, test it with synthetic data where the true mean is known.

### Performance Benchmarking

Generate large datasets on demand:
```java
// 1 million points for performance testing
TimeSeriesObject huge = TimeSeriesObject.getGaussianDistribution(1000000, 0.0, 1.0);
```

### Algorithm Development

Understand behavior on idealized data before confronting messy real-world series.

## Built-in Distributions

### Gaussian (Normal) Distribution

The most commonly used distribution:

```java
int length = 1000;
double mean = 50.0;
double stddev = 10.0;
TimeSeriesObject ts = TimeSeriesObject.getGaussianDistribution(length, mean, stddev);
```

**Use cases:**
- Natural phenomena (measurement errors, human heights)
- Financial returns (short-term)
- Sensor noise

**Characteristics:**
- Symmetric bell curve
- 68% of values within ±1 stddev
- 95% within ±2 stddev
- Tails extend to infinity

### Uniform Distribution

Equal probability across a range:

```java
TimeSeriesObject ts = TimeSeriesObject.getUniformDistribution(1000, 0.0, 100.0);
```

Values uniformly distributed between 0.0 and 100.0.

**Use cases:**
- Random sampling
- Initialization values
- Unbiased test data

### Exponential Distribution

Models time between events:

```java
double lambda = 0.5;  // Mean = 1/lambda = 2.0
TimeSeriesObject ts = TimeSeriesObject.getExpDistribution(1000, lambda);
```

**Use cases:**
- Time between arrivals (Poisson process)
- Radioactive decay
- Service times

**Characteristics:**
- Always positive
- Right-skewed (long tail)
- Memoryless property

### Pareto Distribution

Power-law heavy-tailed distribution:

```java
double alpha = 1.5;
TimeSeriesObject ts = TimeSeriesObject.getParetoDistribution(1000, alpha);
```

**Use cases:**
- Wealth distribution
- File sizes
- City populations
- "80-20 rule" phenomena

**Characteristics:**
- Very heavy right tail
- No finite mean for α ≤ 1
- Models rare but extreme events

### Geometric Distribution

Number of trials until first success:

```java
double p = 0.3;  // Probability of success
TimeSeriesObject ts = TimeSeriesObject.getGeometricDistribution(1000, p);
```

**Use cases:**
- Failure analysis
- Retry mechanisms
- Discrete waiting times

## The RNGWrapper

For custom generation, use the RNGWrapper directly:

### Initialization

```java
RNGWrapper.init();  // Seed with default value (1)
```

### Gaussian Noise

```java
double noise = RNGWrapper.getStdRandomGaussian();           // N(0, 1)
double noise = RNGWrapper.getStdRandomGaussian(5.0, 2.0);  // N(5, 2)
```

### Uniform Random

```java
double value = RNGWrapper.getStdRandomUniform(0.0, 100.0);
```

### Other Distributions

```java
double exp = RNGWrapper.getStdRandomExp(lambda);
double pareto = RNGWrapper.getStdRandomPareto(alpha);
double geometric = RNGWrapper.getStdRandomGeometric(p);
boolean coin = RNGWrapper.getStdRandomBernoulli();
double cauchy = RNGWrapper.getStdRandomCauchy();
double poisson = RNGWrapper.getStdPoisson(lambda);
```

## Creating Complex Patterns

### Trend + Noise

Linear trend with Gaussian noise:

```java
RNGWrapper.init();
TimeSeriesObject ts = new TimeSeriesObject();
ts.setLabel("trend_with_noise");

for (int i = 0; i < 500; i++) {
    double trend = 10.0 + 0.05 * i;  // Linear trend
    double noise = RNGWrapper.getStdRandomGaussian(0.0, 2.0);
    ts.addValuePair(i, trend + noise);
}
```

### Seasonal Pattern

Sinusoidal with noise:

```java
int period = 24;  // Daily cycle
for (int i = 0; i < 500; i++) {
    double seasonal = 10.0 + 5.0 * Math.sin(2 * Math.PI * i / period);
    double noise = RNGWrapper.getStdRandomGaussian(0.0, 1.0);
    ts.addValuePair(i, seasonal + noise);
}
```

### Regime Change

Different statistics in different time windows:

```java
TimeSeriesObject ts = new TimeSeriesObject();
for (int i = 0; i < 300; i++) {
    double value;
    if (i < 150) {
        value = RNGWrapper.getStdRandomGaussian(10.0, 2.0);  // Regime 1
    } else {
        value = RNGWrapper.getStdRandomGaussian(20.0, 2.0);  // Regime 2
    }
    ts.addValuePair(i, value);
}
```

### Autocorrelated Series

Values depend on previous values:

```java
TimeSeriesObject ar1 = new TimeSeriesObject();
double phi = 0.7;  // Autocorrelation coefficient
double prevValue = 0.0;

for (int i = 0; i < 1000; i++) {
    double noise = RNGWrapper.getStdRandomGaussian(0.0, 1.0);
    double value = phi * prevValue + noise;  // AR(1) process
    ar1.addValuePair(i, value);
    prevValue = value;
}
```

## Injecting Anomalies

### Point Anomalies

Occasional outliers:

```java
TimeSeriesObject ts = TimeSeriesObject.getGaussianDistribution(500, 100.0, 10.0);
TimeSeriesObject withAnomalies = ts.copy();

// Inject 10 outliers
int[] anomalyIndices = {50, 100, 150, 200, 250, 300, 350, 400, 450, 475};
for (int idx : anomalyIndices) {
    withAnomalies.yValues.setElementAt(150.0, idx);  // Spike
}
```

### Burst Anomalies

Consecutive unusual values:

```java
// Create normal data
TimeSeriesObject ts = TimeSeriesObject.getGaussianDistribution(300, 100.0, 10.0);

// Inject burst (20 consecutive high values)
for (int i = 150; i < 170; i++) {
    ts.yValues.setElementAt(130.0 + RNGWrapper.getStdRandomGaussian(0, 3), i);
}
```

## Reproducibility

### Seeded Generation

For repeatable tests:

```java
RNGWrapper.init();  // Always seeds with 1
// Subsequent calls produce identical sequences
```

**Note:** RNGWrapper currently uses a fixed seed. For varied test data, manually vary parameters:

```java
// Run 1
TimeSeriesObject ts1 = TimeSeriesObject.getGaussianDistribution(1000, 100.0, 10.0);

// Run 2 (identical to run 1 due to fixed seed)
TimeSeriesObject ts2 = TimeSeriesObject.getGaussianDistribution(1000, 100.0, 10.0);

// To get different data, change parameters
TimeSeriesObject ts3 = TimeSeriesObject.getGaussianDistribution(1000, 100.0, 15.0);
```

## Validation Techniques

### Statistical Tests

Verify generated data matches specifications:

```java
TimeSeriesObject ts = TimeSeriesObject.getGaussianDistribution(10000, 50.0, 10.0);

double observedMean = ts.getAvarage();
double observedStddev = ts.getStddev();

double meanError = Math.abs(observedMean - 50.0);
double stddevError = Math.abs(observedStddev - 10.0);

System.out.println("Mean error: " + meanError);      // Should be < 0.5
System.out.println("Stddev error: " + stddevError);  // Should be < 0.5
```

### Visual Inspection

Export and plot in R or Python:

```java
ts.writeToFile(new File("synthetic.csv"), ',');
```

Then in R:
```r
data <- read.csv("synthetic.csv")
hist(data$y, breaks=50)  # Should look Gaussian
```

## Best Practices

1. **Start simple** — Begin with basic distributions before adding complexity
2. **Know your parameters** — Understand what mean/stddev values are realistic
3. **Verify generation** — Always check statistics match expectations
4. **Document intent** — Label synthetic series clearly (e.g., "test_gaussian_mean100_sd10")
5. **Isolate randomness** — Understand that current RNGWrapper uses fixed seeds

## Common Pitfalls

### Assuming Normality

Not all phenomena are Gaussian:
- Financial returns often have heavy tails → use Pareto
- Event counts → use Poisson or geometric
- Non-negative values → use exponential or log-normal

### Ignoring Autocorrelation

Real time series often exhibit dependencies. Pure random distributions lack this:

```java
// Unrealistic: IID Gaussian noise
TimeSeriesObject unrealistic = TimeSeriesObject.getGaussianDistribution(1000, 0, 1);

// More realistic: AR(1) process (shown above)
```

### Scale Mismatch

Ensure synthetic data matches your domain's typical scales:
```java
// Wrong: Temperature in Kelvin using N(100, 10)
TimeSeriesObject wrong = TimeSeriesObject.getGaussianDistribution(1000, 100, 10);

// Right: Room temperature in Celsius
TimeSeriesObject right = TimeSeriesObject.getGaussianDistribution(1000, 22.0, 2.0);
```

## Next Steps

- **[Transformations](transformations.md)** — Apply operations to synthetic data
- **[Statistical Analysis](../statistical-analysis/descriptive-stats.md)** — Verify synthetic data properties
- **[Anomaly Detection](../statistical-analysis/anomaly-detection.md)** — Test on data with known anomalies

---

**Related Topics:**
- [Creating Time Series](creating-timeseries.md)
- [The RNGWrapper API](../appendix/api-reference.md#rngwrapper)
- [Testing Best Practices](../best-practices/testing.md)
