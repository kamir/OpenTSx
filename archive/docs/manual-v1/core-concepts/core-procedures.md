# Core Procedures and Algorithms

OpenTSx provides a comprehensive suite of analysis procedures built into the framework. This guide catalogs all core operations available across both opentsx-core and opentsx-data modules.

## Data Generation Procedures

### Synthetic Time Series Creation

Generate time series with known statistical properties for testing and validation.

#### Gaussian Distribution

```java
// Standard normal (μ=0, σ=1)
TimeSeriesObject ts = TimeSeriesObject.getGaussian Distribution(1000);

// Custom parameters
TimeSeriesObject ts = TimeSeriesObject.getGaussianDistribution(
    1000,  // Number of points
    50.0,  // Mean (μ)
    10.0   // Standard deviation (σ)
);
```

**Use Cases:**
- Null hypothesis testing
- Algorithm validation
- Noise injection
- Monte Carlo simulations

#### Uniform Distribution

```java
TimeSeriesObject ts = TimeSeriesObject.getUniformDistribution(
    1000,   // Number of points
    0.0,    // Minimum value
    100.0   // Maximum value
);
```

**Use Cases:**
- Baseline comparisons
- Random sampling
- Initialization values

#### Exponential Distribution

```java
TimeSeriesObject ts = TimeSeriesObject.getExpDistribution(
    1000,  // Number of points
    2.5    // Lambda (rate parameter)
);
```

**Use Cases:**
- Inter-arrival times
- Lifetime/decay processes
- Queue modeling

#### Pareto Distribution

```java
TimeSeriesObject ts = TimeSeriesObject.getParetoDistribution(
    1000,  // Number of points
    1.5    // Alpha (shape parameter)
);
```

**Use Cases:**
- Power-law phenomena
- Wealth distribution
- File size distributions
- Network traffic modeling

#### Geometric Distribution

```java
TimeSeriesObject ts = TimeSeriesObject.getGeometricDistribution(
    1000,  // Number of points
    0.3    // Success probability (p)
);
```

**Use Cases:**
- Trial-to-success counting
- Reliability analysis
- Discrete event modeling

### Pattern Generation

#### Linear Function

```java
TimeSeriesObject ts = TimeSeriesObject.getLinearFunction(
    2.5,    // Slope (m)
    10.0,   // Intercept (n)
    0.1,    // X step (dx)
    0.0,    // X minimum
    100     // Number of points
);
// Generates: y = 2.5*x + 10
```

**Use Cases:**
- Trend injection
- Linear baseline
- Calibration curves

#### Periodic Trends

```java
double[] trend = TimeSeriesObject.calcPeriodeTrend(ts, 24);
// Calculate periodic pattern (e.g., daily cycle)
```

**Use Cases:**
- Seasonal decomposition
- Daily/weekly pattern extraction
- Circadian rhythm analysis

## Statistical Procedures

### Descriptive Statistics

#### Central Tendency

```java
// Mean (average)
ts.calcAverage();  // Must be called first
double mean = ts.getAvarage();

// Median and percentiles
Percentile p = new Percentile();
p.setData(ts.getYData());
double median = p.evaluate(50.0);
double q1 = p.evaluate(25.0);
double q3 = p.evaluate(75.0);
```

#### Dispersion

```java
// Standard deviation
double stddev = ts.getStddev();

// Variance
double variance = stddev * stddev;

// Range
double min = ts.getMinY();
double max = ts.getMaxY();
double range = max - min;

// Sum
double sum = ts.summeY();
```

#### Distribution Shape

```java
// Skewness and kurtosis via Statistical class
double[] stats = Statistical.calcStatistics(ts.getYData());
// Returns: [mean, stddev, skewness, kurtosis, ...]
```

### Statistical Tests

#### Distribution Testing

```java
DistributionTester tester = new DistributionTester();

// Test for normal distribution
boolean isNormal = tester.testNormal(ts, alpha);

// Test for specific distribution type
boolean matches = tester.test(ts, DistributionType.EXPONENTIAL);
```

**Available Tests:**
- Shapiro-Wilk (normality)
- Kolmogorov-Smirnov (distribution matching)
- Chi-square (goodness of fit)

#### Granger Causality

```java
GrangerCausality gc = new GrangerCausality();

// Test if ts1 Granger-causes ts2
double pValue = gc.test(ts1, ts2, maxLag);

if (pValue < 0.05) {
    System.out.println("ts1 Granger-causes ts2");
}
```

**Use Cases:**
- Identify causal relationships
- Lead-lag analysis
- Predictive feature selection

### Entropy Analysis

```java
EntropyTool entropy = new EntropyTool();

// Shannon entropy
double H = entropy.calculate(ts);

// Conditional entropy
double conditionalH = entropy.calculateConditional(ts, condition);
```

**Use Cases:**
- Complexity measurement
- Information content quantification
- Randomness testing

## Transformation Procedures

### Normalization

#### Z-Score Normalization

```java
// In-place normalization
ts.normalize();

// Create normalized copy
TimeSeriesObject normalized = ts.normalize_zScore();

// Result: mean = 0, stddev = 1
```

#### Min-Max Scaling

```java
DatasetNormalizationTool.normalizeMinMax(ts, 0.0, 1.0);
// Scales to range [0, 1]
```

#### Decimal Scaling

```java
DatasetNormalizationTool.normalizeDecimal(ts);
// Divides by 10^k to move decimal point
```

### Logarithmic Transforms

#### Natural Logarithm

```java
ts.calcLn_for_Y();
// Applies ln(y) to all Y values
```

**Use Cases:**
- Log-normal distributions
- Exponential growth linearization
- Variance stabilization

#### Log Base 10

```java
ts.calcLog10_for_Y();
// Applies log₁₀(y) to all Y values
```

**Use Cases:**
- Orders of magnitude analysis
- Power-law linearization
- dB scale conversions

### Detrending

#### Periodic Trend Removal

```java
TimeSeriesObject detrended = TimeSeriesObject.normalizeByPeriodeTrend(
    ts,
    24  // Period length (e.g., 24 hours)
);
```

**Use Cases:**
- Remove daily cycles
- Isolate residuals
- Seasonal adjustment

#### Linear Detrending

```java
SimpleRegression regression = new SimpleRegression();
for (int i = 0; i < ts.yValues.size(); i++) {
    regression.addData(i, (Double) ts.yValues.elementAt(i));
}

TimeSeriesObject detrended = new TimeSeriesObject();
for (int i = 0; i < ts.yValues.size(); i++) {
    double trend = regression.predict(i);
    double residual = (Double) ts.yValues.elementAt(i) - trend;
    detrended.addValue(residual);
}
```

### Filtering

#### Replace Zeros with Average

```java
TimeSeriesObject filled = ts.replaceZeroWithAverage();
```

**Use Cases:**
- Handle missing data (coded as 0)
- Imputation
- Gap filling

#### Fill Gaps with Value

```java
TimeSeriesObject filled = ts.fillGapWithValue(
    0.0,     // Target value to replace
    100.0    // Length threshold
);
```

#### Value Filtering

```java
// Filter values above threshold
ts.doFilter(thresholdMultiplier);
// Caps values at mean * threshold
```

## Resampling Procedures

### Binning

#### Average Binning

```java
int binSize = 10;
TimeSeriesObject binned = ts.setBinningX_average(binSize);
// Averages every 10 consecutive points
```

**Use Cases:**
- Downsampling
- Noise reduction
- Data compression

#### Other Binning Strategies

```java
// Maximum in each bin
TimeSeriesObject binned = ts.setBinningX_max(binSize);

// Minimum in each bin
TimeSeriesObject binned = ts.setBinningX_min(binSize);

// Median in each bin
TimeSeriesObject binned = ts.setBinningX_median(binSize);
```

### Windowing

#### Extract Time Window

```java
TimeSeriesObject window = ts.shrinkX(startTime, endTime);
// Returns series with startTime ≤ x ≤ endTime
```

#### Interval Cutting

```java
SingleTsIntervallCutTool cutter = new SingleTsIntervallCutTool();
Vector<TimeSeriesObject> segments = cutter.cut(ts, intervalLength);
```

**Use Cases:**
- Segmentation
- Event isolation
- Batch processing

## Detrended Fluctuation Analysis (DFA)

### Standard DFA

```java
import org.opentsx.algorithms.detrending.methods.DFA;

DFA dfa = new DFA();
dfa.setTimeSeries(ts);

// Configure parameters
DFA Parameter params = new DFAParameter();
params.setMinBox(10);
params.setMaxBox(ts.yValues.size() / 4);
params.setDegree(1);  // Linear detrending

// Execute analysis
TimeSeriesObject fluctuation = dfa.execute(params);

// Get scaling exponent (Hurst exponent)
double alpha = dfa.getScalingExponent();
```

**Interpretation:**
- `α < 0.5` — Anti-correlation (mean-reverting)
- `α = 0.5` — Random walk (no correlation)
- `α > 0.5` — Persistent correlation (trending)
- `α = 1.0` — 1/f noise (pink noise)
- `α > 1.0` — Strong long-range correlation

**Use Cases:**
- Detect long-range correlations
- Analyze self-similarity
- Characterize fractality
- Distinguish signal types

### Multi-Fractal DFA (MFDFA)

```java
import org.opentsx.algorithms.detrending.methods.MFDFA;

MFDFA mfdfa = new MFDFA();
mfdfa.setTimeSeries(ts);

// Configure moments
double[] qValues = {-5, -3, -1, 0, 1, 2, 3, 5};
mfdfa.setMoments(qValues);

// Execute
Vector<TimeSeriesObject> spectra = mfdfa.execute();

// Analyze multifractal spectrum
TimeSeriesObject tauQ = spectra.get(0);   // τ(q)
TimeSeriesObject alphaF = spectra.get(1); // α
TimeSeriesObject fAlpha = spectra.get(2); // f(α)
```

**Interpretation:**
- **Spectrum width** — Degree of multifractality
- **Asymmetry** — Dominance of large/small fluctuations
- **Single point** — Monofractal (uniform scaling)

**Use Cases:**
- Detect multiscale patterns
- Characterize complexity
- Financial market analysis
- Physiological signal analysis

### DFA Tools

```java
// Single time series DFA
SingleTsDFATool tool = new SingleTsDFATool();
TimeSeriesObject result = tool.analyze(ts);

// Multi-series DFA
MultiDFATool multiTool = new MultiDFATool();
TSBucket results = multiTool.analyze(bucket);
```

## Random Interval Sampling (RIS)

### RIS Analysis

```java
import org.opentsx.algorithms.ris.RISTool;

RISTool ris = new RISTool();

// Configure sampling
ris.setNumSamples(1000);
ris.setMinInterval(10);
ris.setMaxInterval(100);

// Execute RIS
Vector<TimeSeriesObject> distributions = ris.analyze(ts);

// Get uncertainty bounds
TimeSeriesObject mean = distributions.get(0);
TimeSeriesObject lower = distributions.get(1);
TimeSeriesObject upper = distributions.get(2);
```

**Use Cases:**
- Uncertainty quantification
- Robust statistics
- Outlier detection
- Validation of scaling laws

### Property Testing

```java
TSPropertyTester tester = new TSPropertyTester();

// Test specific property across RIS samples
boolean isRobust = tester.testProperty(ts, propertyFunction);
```

## Event Synchronisation

### Event Synchronisation Calculation

```java
import org.opentsx.algorithms.eventsynchronisation.ESCalc;

ESCalc esCalc = new ESCalc();

// Define event sequences
TimeSeriesObject events1 = extractEvents(ts1);
TimeSeriesObject events2 = extractEvents(ts2);

// Calculate synchronisation
double Q = esCalc.calculate(events1, events2, tau);
// Q ∈ [0, 1]: 0 = no sync, 1 = perfect sync
```

**Parameters:**
- `tau` — Time lag tolerance for event matching

**Use Cases:**
- Climate event coupling
- Neuronal spike synchrony
- Market event correlation
- System interaction analysis

## Peak Detection

### Peak Detector

```java
import org.opentsx.algorithms.analysis.SingleTSToolPeakDetector;

SingleTSToolPeakDetector detector = new SingleTSToolPeakDetector();

// Configure sensitivity
detector.setThreshold(mean + 2 * stddev);
detector.setMinDistance(10);

// Detect peaks
TimeSeriesObject peaks = detector.findPeaks(ts);
```

**Use Cases:**
- Anomaly detection
- Event extraction
- Cycle counting
- Extreme value analysis

### Peak Filtering

```java
SingleTsPeakFilterTool filter = new SingleTsPeakFilterTool();

// Remove outlier peaks
TimeSeriesObject filtered = filter.apply(ts, maxHeight);
```

## Data Quality Procedures

### Influence of Single Peaks

```java
import org.opentsx.algorithms.dataqualitytest.InfluenceOfSinglePeakTester;

InfluenceOfSinglePeakTester tester = new InfluenceOfSinglePeakTester();

// Test impact of individual peaks on statistics
double influence = tester.test(ts, peakIndex);

if (influence > threshold) {
    System.out.println("Peak at " + peakIndex + " is influential");
}
```

**Use Cases:**
- Outlier impact assessment
- Robustness testing
- Data cleaning validation

## Frequency Analysis

### Frequency Counting

```java
import org.opentsx.algorithms.statistics.HaeufigkeitsZaehlerDouble;

HaeufigkeitsZaehlerDouble counter = new HaeufigkeitsZaehlerDouble();
counter.setData(ts.getYData());

// Count unique values
Hashtable<Double, Integer> frequencies = counter.getFrequencies();

// Get top N most frequent values
Vector<Double> topValues = counter.getTopN(10);
```

**Use Cases:**
- Mode detection
- Distribution analysis
- Pattern frequency
- Value occurrence counting

### Top Frequencies

```java
// Get top K frequency components
Hashtable topFreq = ts.getTopFrequencies(k);

Double frequency = (Double) topFreq.get("topFrequency");
Double amplitude = (Double) topFreq.get("topFrequency_real");
```

## Aggregation Procedures

### Cross-Series Statistics

#### Average Across Series

```java
Vector<TimeSeriesObject> series = /* collection of aligned series */;

TimeSeriesObject average = TimeSeriesObject.averageForAll(series);
// Point-wise mean across all series
```

**Use Cases:**
- Ensemble averaging
- Noise reduction
- Consensus signal
- Template creation

#### Standard Deviation Across Series

```java
TimeSeriesObject sigma = TimeSeriesObject.sigmaForAll(series);
// Point-wise standard deviation
```

**Use Cases:**
- Uncertainty bands
- Variability measurement
- Consensus quality

#### Median Across Series

```java
TimeSeriesObject median = TimeSeriesObject.medianForAll(series);
// Robust central tendency
```

### Combining Series

#### Concatenation

```java
TimeSeriesObject combined = ts1.plus(ts2);
// Append ts2 after ts1
```

#### Arithmetic Operations

```java
// Element-wise addition
TimeSeriesObject sum = ts1.add(ts2);

// Element-wise subtraction
TimeSeriesObject diff = ts1.minus(ts2);

// Element-wise multiplication
TimeSeriesObject product = ts1.multiply(ts2);

// Element-wise division
TimeSeriesObject quotient = ts1.divide(ts2);
```

**Requirements:**
- Series must have equal length
- X values should align

#### Scalar Operations

```java
// Add constant to all Y values
TimeSeriesObject offset = ts.addToY(10.0);

// Multiply all Y values by constant
TimeSeriesObject scaled = ts.multiplyY(2.5);

// Scale and offset X values
TimeSeriesObject adjusted = ts.scaleX_And_Y(xScale, yScale, xOffset, yOffset);
```

## Regression and Fitting

### Linear Regression

```java
SimpleRegression regression = new SimpleRegression();

for (int i = 0; i < ts.yValues.size(); i++) {
    double x = (Double) ts.xValues.elementAt(i);
    double y = (Double) ts.yValues.elementAt(i);
    regression.addData(x, y);
}

// Get parameters
double slope = regression.getSlope();
double intercept = regression.getIntercept();
double r2 = regression.getRSquare();

// Predict
double predicted = regression.predict(newX);
```

### Polynomial Fitting

```java
import org.opentsx.algorithms.detrending.methods.FitTool;

FitTool fitter = new FitTool();

// Fit polynomial of degree n
double[] coefficients = fitter.fitPolynomial(ts, degree);

// Evaluate fitted function
double fitted = fitter.evaluate(coefficients, x);
```

## Moving Window Operations

### Moving Average

```java
int windowSize = 10;
TimeSeriesObject smoothed = ts.movingAverage(windowSize);
```

**Use Cases:**
- Noise reduction
- Trend extraction
- Smoothing

### Moving Statistics

```java
// Moving standard deviation
TimeSeriesObject volatility = ts.movingStdDev(windowSize);

// Moving min/max
TimeSeriesObject maxEnvelope = ts.movingMax(windowSize);
TimeSeriesObject minEnvelope = ts.movingMin(windowSize);
```

## Export and Visualization Procedures

### Export to File

```java
// CSV export
File output = new File("timeseries.csv");
ts.writeToFile(output, ',');

// TSV export
ts.writeToFile(output, '\t');

// Custom delimiter
ts.writeToFile(output, ';');
```

### JFreeChart Integration

```java
XYSeries xySeries = ts.getXYSeries();
// Use with JFreeChart for plotting
```

### Statistical Summary

```java
// Console output
String summary = ts.getStatisticData("\t");
System.out.println(summary);

// Hashtable format
Hashtable<String, String> stats = ts.getStatisticData(new Hashtable<>());
```

## ARIMA Modeling

```java
import org.opentsx.algorithms.univariate.SingleTsARIMATool;

SingleTsARIMATool arima = new SingleTsARIMATool();

// Configure model
arima.setP(2);  // AR order
arima.setD(1);  // Differencing order
arima.setQ(2);  // MA order

// Fit model
arima.fit(ts);

// Forecast
TimeSeriesObject forecast = arima.forecast(nSteps);
```

**Use Cases:**
- Time series forecasting
- Trend prediction
- Anomaly detection (forecast errors)

## Procedure Selection Guide

### For Data Exploration

1. **Descriptive statistics** — Understand basic properties
2. **Visualization** — Plot raw data
3. **Distribution testing** — Identify underlying distribution
4. **Peak detection** — Find interesting events

### For Data Cleaning

1. **Fill gaps** — Handle missing values
2. **Remove outliers** — Filter extremes
3. **Normalization** — Standardize scale
4. **Resampling** — Adjust resolution

### For Pattern Detection

1. **DFA** — Long-range correlations
2. **Event synchronisation** — Event coupling
3. **Frequency analysis** — Periodic patterns
4. **Autocorrelation** — Self-similarity

### For Forecasting

1. **Detrending** — Remove non-stationary components
2. **ARIMA** — Model and predict
3. **RIS** — Quantify uncertainty
4. **Validation** — Test predictions

### For Comparative Analysis

1. **Normalization** — Common scale
2. **Alignment** — Match timestamps
3. **Aggregation** — Combine series
4. **Granger causality** — Causal relationships

## Next Steps

- **[Algorithm Reference](../algorithms/)** — Detailed algorithm documentation
- **[Examples](../examples/)** — Practical code examples
- **[Best Practices](../best-practices/)** — Usage guidelines

---

**Related Topics:**
- [TimeSeriesObject API](timeseries-object.md)
- [Statistical Analysis](../statistical-analysis/)
- [Data Operations](../data-operations/)
