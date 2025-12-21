# OpenTSx Onboarding Path: Time Series Expert Track

## Track Overview

**Target Audience**: Time series analysts and researchers with deep domain knowledge in statistics, signal processing, or physics, but limited experience with distributed systems and Java/Scala.

**Duration**: 15-20 hours (10 episodes × 1.5-2 hours each)

**Prerequisites**:
- Strong understanding of time series analysis concepts
- Experience with R, Python (NumPy, Pandas), or MATLAB
- Basic programming fundamentals
- Willingness to learn Java/Scala syntax (guided learning)
- Basic command-line familiarity

**Learning Outcomes**: By completing this track, you will:
1. Navigate and use the OpenTSx framework effectively
2. Translate time series concepts from R/Python to OpenTSx
3. Work with distributed time series processing
4. Leverage existing algorithms and implement custom analytics
5. Scale time series analysis to large datasets

---

## Episode Guide

### 🎯 Foundation Phase (Episodes 1-3)

#### Episode 1: From Python/R to OpenTSx
**Duration**: 90 minutes
**Focus**: Bridge from familiar tools to OpenTSx framework

**Theory (15 min)**:
- OpenTSx philosophy and design
- Comparison: Pandas/R DataFrames ↔ TimeSeriesObject
- When to use OpenTSx vs. Python/R
- OpenTSx ecosystem overview

**Concept Mapping**:
```
Python/Pandas          →  OpenTSx
─────────────────────────────────────
Series                 →  TimeSeriesObject
DataFrame              →  Messreihe collection
pd.read_csv()          →  TimeSeriesObject.readFromFile()
series.mean()          →  ts.getMean()
series.rolling()       →  ts.rollingWindow()
series.resample()      →  ts.resample()
```

**Demo Scripts**:
- `bin/120_run_demo.sh` - Visual introduction (MacroRecorder)
- `notebooks/Welcome.ipynb` - **NEW**: Interactive Jupyter Notebook for this track

**Hands-On Exercise** (60 min):

**Setup: The Lab**
For this track, we recommend using the provided Jupyter Lab environment.
```bash
docker-compose -f docker-compose.onboarding.yml up -d
open http://localhost:8888
# Login with token: opentsx
# Open notebooks/Welcome.ipynb
```
```java
// Exercise: Translate Python concepts to OpenTSx

// Python equivalent:
// import pandas as pd
// import numpy as np
// ts = pd.Series(np.random.normal(10, 1, 1000))

// OpenTSx equivalent:
import org.opentsx.data.series.TimeSeriesObject;

// Task 1: Create time series (like numpy.random.normal)
TimeSeriesObject ts = TimeSeriesObject.getGaussianDistribution(
    1000,    // length
    10.0,    // mean (mu)
    1.0      // std dev (sigma)
);

// Task 2: Basic statistics (like pandas describe())
System.out.println("Mean: " + ts.getMean());           // ts.mean()
System.out.println("Std: " + ts.getStddev());          // ts.std()
System.out.println("Min: " + ts.getMin());             // ts.min()
System.out.println("Max: " + ts.getMax());             // ts.max()
System.out.println("Length: " + ts.getLength());       // len(ts)

// Task 3: Normalization (like scipy.stats.zscore)
TimeSeriesObject normalized = ts.normalize();          // (ts - ts.mean()) / ts.std()

// Task 4: Element-wise operations
TimeSeriesObject scaled = ts.multiply(2.0);            // ts * 2
TimeSeriesObject offset = ts.add(5.0);                 // ts + 5

// Task 5: Export for visualization in R/Python
ts.writeToFile("output.csv", ",");
// Now load in R: data <- read.csv("output.csv")
// Or Python: df = pd.read_csv("output.csv")
```

**Validation Checkpoint**:
- [ ] Can create synthetic time series (Gaussian, uniform)
- [ ] Can calculate basic statistics
- [ ] Can apply transformations
- [ ] Can export data for R/Python visualization
- [ ] Understand TimeSeriesObject API basics

**Rosetta Stone** (Quick Reference):
| Concept | R | Python | OpenTSx |
|---------|---|--------|---------|
| Create TS | `ts()` | `pd.Series()` | `TimeSeriesObject` |
| Mean | `mean(x)` | `x.mean()` | `x.getMean()` |
| Std Dev | `sd(x)` | `x.std()` | `x.getStddev()` |
| Normalize | `scale(x)` | `(x-x.mean())/x.std()` | `x.normalize()` |
| Filter | `x[x>0]` | `x[x>0]` | `x.filterByValue(v->v>0)` |

**Next Steps**: Episode 2 - Time Series Operations in OpenTSx

---

#### Episode 2: Core Time Series Operations
**Duration**: 90 minutes
**Focus**: Apply your domain knowledge using OpenTSx APIs

**Theory (10 min)**:
- OpenTSx data model deep dive
- Lazy vs. eager evaluation
- Memory management for large time series

**Familiar Concepts in OpenTSx**:
| Operation | Your Knowledge | OpenTSx Implementation |
|-----------|----------------|------------------------|
| Moving Average | Smoothing filter | `ts.simpleMovingAverage(window)` |
| Differentiation | Change detection | `ts.difference(lag)` |
| Autocorrelation | Temporal dependence | `ts.autocorrelation(maxLag)` |
| Resampling | Change frequency | `ts.resample(period, agg)` |
| Interpolation | Fill missing values | `ts.interpolate(method)` |

**Demo Scripts**:
- Create: `demo/TimeSeriesOperations.java` (to be created)
- Reference: `opentsx-core` module

**Hands-On Exercise** (70 min):
```java
// Exercise: Apply your time series expertise

// Load real sensor data
TimeSeriesObject sensor = TimeSeriesObject.readFromFile("temperature.csv");

// Task 1: Smoothing (you know this as moving average filter)
int window = 10;
TimeSeriesObject smoothed = sensor.simpleMovingAverage(window);

// Compare with Python: sensor.rolling(window=10).mean()
// Compare with R: filter(sensor, rep(1/window, window), sides=1)

// Task 2: Detect changes (first difference)
TimeSeriesObject changes = sensor.difference(1);

// Python: sensor.diff()
// R: diff(sensor)

// Task 3: Autocorrelation (you know this well!)
double[] acf = sensor.autocorrelation(50);

// This gives you the same result as:
// R: acf(sensor, lag.max=50)
// Python: pd.plotting.autocorrelation_plot(sensor)

// Task 4: Detrending
TimeSeriesObject detrended = sensor.removeLinearTrend();

// Similar to R: residuals(lm(sensor ~ time))

// Task 5: Seasonal decomposition
SeasonalDecomposition decomp = sensor.decompose(
    period,              // seasonal period (e.g., 24 for hourly data with daily seasonality)
    DecompositionMethod.STL
);

TimeSeriesObject trend = decomp.getTrend();
TimeSeriesObject seasonal = decomp.getSeasonal();
TimeSeriesObject residual = decomp.getResidual();

// R equivalent: stl(ts(sensor, frequency=24), s.window="periodic")
// Python: from statsmodels.tsa.seasonal import seasonal_decompose

// Task 6: Stationarity testing
ADFTest adfTest = new ADFTest(sensor);
boolean isStationary = adfTest.isStationary(0.05);  // significance level

// R: adf.test(sensor)
// Python: from statsmodels.tsa.stattools import adfuller

// Task 7: Cross-correlation
TimeSeriesObject sensor2 = TimeSeriesObject.readFromFile("pressure.csv");
double[] ccf = TimeSeriesObject.crossCorrelation(sensor, sensor2, 50);

// R: ccf(sensor, sensor2)
// Python: np.correlate(sensor, sensor2, mode='full')
```

**Validation Checkpoint**:
- [ ] Can apply moving average smoothing
- [ ] Can compute differences for change detection
- [ ] Can calculate autocorrelation function
- [ ] Can decompose time series (trend, seasonal, residual)
- [ ] Can test for stationarity
- [ ] Can compute cross-correlation between series

**Key Concepts**:
- OpenTSx provides distributed versions of familiar algorithms
- Results should match R/Python for identical inputs
- Syntax is different but concepts are the same

**Next Steps**: Episode 3 - Visualization and Exploratory Analysis

---

#### Episode 3: Visualization and Exploratory Analysis
**Duration**: 90 minutes
**Focus**: Understand and visualize your time series data

**Theory (10 min)**:
- OpenTSx visualization tools (MacroRecorder/TSA Workbench)
- Exporting for external visualization (R/Python/Gnuplot)
- Interactive exploration workflow

**Demo Scripts**:
- `bin/120_run_demo.sh` - MacroRecorder visual tool
- `scala-scripts/run_rng_demo.scala` - Includes Gnuplot integration

**Hands-On Exercise** (70 min):
```java
// Exercise: Explore and visualize time series

// Task 1: Load your data
TimeSeriesObject data = TimeSeriesObject.readFromFile("your_data.csv");

// Task 2: Quick statistical summary (like R's summary())
System.out.println("=== Time Series Summary ===");
System.out.println("Length: " + data.getLength());
System.out.println("Mean: " + data.getMean());
System.out.println("Std Dev: " + data.getStddev());
System.out.println("Min: " + data.getMin());
System.out.println("Max: " + data.getMax());
System.out.println("Median: " + data.getMedian());
System.out.println("Variance: " + data.getVariance());

// Task 3: Export for visualization in your favorite tool
data.writeToFile("for_r_analysis.csv", ",");
data.writeToJSON("for_python.json");

// Task 4: Use TSA Workbench (MacroRecorder) for visual exploration
// This is a GUI tool similar to R's plot() or Python's matplotlib
MacroRecorder2 recorder = new MacroRecorder2();
recorder.addTimeSeries(data);
recorder.show();

// Task 5: Create multiple views
TimeSeriesObject original = data;
TimeSeriesObject smoothed = data.simpleMovingAverage(10);
TimeSeriesObject detrended = data.removeLinearTrend();

recorder.addTimeSeries("Original", original);
recorder.addTimeSeries("Smoothed", smoothed);
recorder.addTimeSeries("Detrended", detrended);
recorder.show();

// Task 6: Analyze in R (your comfort zone!)
// Save from OpenTSx:
data.writeToFile("analysis_data.csv", ",");

/* Then in R:
data <- read.csv("analysis_data.csv")
plot(data$value, type="l")
acf(data$value)
spectrum(data$value)
*/

// Task 7: Generate diagnostic plots data
data.autocorrelation(50);  // ACF
data.partialAutocorrelation(50);  // PACF

// Export these for plotting
writeArray(acf, "acf_values.csv");
writeArray(pacf, "pacf_values.csv");

// Task 8: Spectral analysis preparation
FFTResult fft = data.fft();
double[] frequencies = fft.getFrequencies();
double[] power = fft.getPowerSpectrum();

// Export for plotting
writePowerSpectrum("spectrum.csv", frequencies, power);

/* In R:
spectrum_data <- read.csv("spectrum.csv")
plot(spectrum_data$frequency, spectrum_data$power, type="l",
     log="y", xlab="Frequency", ylab="Power")
*/
```

**Validation Checkpoint**:
- [ ] Can run MacroRecorder for visual exploration
- [ ] Can export data for R/Python analysis
- [ ] Can generate diagnostic statistics
- [ ] Can prepare data for spectral analysis
- [ ] Can create multiple views of time series
- [ ] Can round-trip data between OpenTSx and R/Python

**Visualization Workflow**:
1. **Quick exploration**: Use MacroRecorder for initial visual inspection
2. **Detailed analysis**: Export to R/Python for publication-quality plots
3. **Iteration**: Make changes in OpenTSx, re-export, re-visualize

**Next Steps**: Episode 4 - Working with Large Datasets

---

### 🔧 Core Skills Phase (Episodes 4-7)

#### Episode 4: Scaling to Large Datasets
**Duration**: 120 minutes
**Focus**: Process datasets larger than memory using distributed computing

**Theory (20 min)**:
- Why distributed processing?
- Spark fundamentals (from a domain expert perspective)
- Partitioning time series data
- When to use batch vs. streaming

**Conceptual Bridge**:
```
Your Current Workflow      →  OpenTSx Distributed Workflow
────────────────────────────────────────────────────────────
Load all data in RAM       →  Partitioned processing (Spark)
Process sequentially       →  Process in parallel
Single machine limits      →  Cluster of machines
Manual batching            →  Automatic parallelization
```

**Demo Scripts**:
- `bin/130_run_demo_in_spark_shell_locally.sh`
- `scala-scripts/run_opentsdb_streaming_demo.scala`

**Hands-On Exercise** (90 min):
```scala
// Exercise: Process large time series with Spark
// Don't worry about Scala syntax - we'll guide you!

// Task 1: Start Spark shell
// Run: ./bin/130_run_demo_in_spark_shell_locally.sh

// Task 2: Load time series data (distributed!)
// This is like read.csv() in R, but data is distributed
val rawData = spark.read
    .option("header", "true")
    .csv("large_dataset.csv")

// Task 3: Apply your domain knowledge at scale
// Think: "I want to compute rolling mean for each sensor"

// In R, you might do:
// data %>% group_by(sensor_id) %>% mutate(rolling_mean = zoo::rollmean(value, k=10))

// In OpenTSx/Spark:
import org.apache.spark.sql.expressions.Window

val windowSpec = Window
    .partitionBy("sensor_id")        // group by sensor
    .orderBy("timestamp")              // sort by time
    .rowsBetween(-9, 0)                // last 10 values

val withRollingMean = rawData
    .withColumn("rolling_mean", avg($"value").over(windowSpec))

// Task 4: Detect anomalies at scale
// Your knowledge: anomaly = |value - mean| > 3 * std_dev

val withStats = rawData
    .withColumn("rolling_mean", avg($"value").over(windowSpec))
    .withColumn("rolling_std", stddev($"value").over(windowSpec))

val anomalies = withStats.filter(
    abs($"value" - $"rolling_mean") > 3 * $"rolling_std"
)

// Task 5: Compute statistics per sensor
// Like R: aggregate(value ~ sensor_id, data, function)

val stats = rawData
    .groupBy("sensor_id")
    .agg(
        mean("value").as("mean"),
        stddev("value").as("std"),
        min("value").as("min"),
        max("value").as("max"),
        count("value").as("count")
    )

stats.show()  // Display results

// Task 6: Export results back to R/Python
anomalies.write
    .option("header", "true")
    .csv("anomalies_output.csv")

// Now you can analyze in R:
// anomalies <- read.csv("anomalies_output.csv")
// summary(anomalies)

// Task 7: Understand what happened
// - Data was partitioned across multiple cores
// - Each partition processed independently
// - Results aggregated automatically
// - This works the same way on 1 node or 100 nodes!
```

**Validation Checkpoint**:
- [ ] Can load large datasets in Spark
- [ ] Can apply window functions (rolling operations)
- [ ] Can detect anomalies at scale
- [ ] Can compute grouped statistics
- [ ] Can export results for R/Python analysis
- [ ] Understand distributed processing basics

**Key Concepts**:
- **Partitioning**: Data is split across workers
- **Lazy evaluation**: Operations build a plan, executed only when needed
- **Window functions**: Your rolling operations, but distributed
- **Actions vs. Transformations**: When computation actually happens

**Common Patterns**:
| Time Series Task | R/Python Approach | OpenTSx/Spark Approach |
|------------------|-------------------|------------------------|
| Group statistics | `aggregate()` / `groupby()` | `.groupBy().agg()` |
| Rolling window | `zoo::rollmean()` / `rolling()` | `Window.rowsBetween()` |
| Filter | `subset()` / `df[df.x > 0]` | `.filter($"x" > 0)` |
| Join series | `merge()` / `pd.merge()` | `.join()` |

**Next Steps**: Episode 5 - Real-Time Time Series Processing

---

#### Episode 5: Streaming Time Series Analysis
**Duration**: 120 minutes
**Focus**: Analyze time series data as it arrives in real-time

**Theory (20 min)**:
- Streaming vs. batch processing
- Real-time analytics use cases
- Kafka fundamentals (simplified for domain experts)
- Window-based aggregations in streams

**Conceptual Understanding**:
```
Traditional Analysis       →  Streaming Analysis
────────────────────────────────────────────────
Load historical data       →  Process data as it arrives
Analyze once               →  Continuous analysis
Batch reports              →  Real-time alerts
Fixed dataset              →  Infinite stream
```

**Demo Scripts**:
- `scala-scripts/run_opentsdb_streaming_demo.scala`
- `opentsx-kstreams-cassandra-state-store/StateStoreExample1.java`

**Hands-On Exercise** (90 min):
```java
// Exercise: Real-time time series analysis
// Imagine: sensor data arriving continuously, you analyze in real-time

// Task 1: Understand the streaming mindset
// Instead of: "I have 1000 data points, compute mean"
// Think: "For every new data point, update running statistics"

// Task 2: Simple streaming aggregation
StreamsBuilder builder = new StreamsBuilder();

// This is like reading from a continuous data source
KStream<String, Double> sensorStream = builder.stream("sensor-data");

// Task 3: Compute running average (your domain: moving average)
// In R: as each point arrives, update rolling mean
KStream<String, Double> runningAverage = sensorStream
    .groupByKey()
    .windowedBy(TimeWindows.of(Duration.ofMinutes(5)))
    .aggregate(
        () -> new RunningStats(),           // Initialize
        (key, value, stats) -> {
            stats.addValue(value);           // Update with new point
            return stats;
        }
    )
    .toStream()
    .mapValues(stats -> stats.getMean());

// Task 4: Anomaly detection in real-time
// Your knowledge: z-score = (x - mean) / std_dev

KStream<String, Anomaly> anomalies = sensorStream
    .groupByKey()
    .windowedBy(TimeWindows.of(Duration.ofMinutes(5)))
    .aggregate(
        () -> new OnlineStats(),             // Track mean & std online
        (key, value, stats) -> {
            stats.update(value);
            return stats;
        }
    )
    .toStream()
    .filter((key, stats) -> {
        double zScore = (stats.getLastValue() - stats.getMean()) / stats.getStd();
        return Math.abs(zScore) > 3.0;       // Anomaly threshold
    });

// Task 5: Pattern detection (your domain: autocorrelation in real-time)
// Maintain sliding window of recent values
KStream<String, Pattern> patterns = sensorStream
    .groupByKey()
    .windowedBy(SlidingWindows.withTimeDifferenceAndGrace(
        Duration.ofMinutes(10),
        Duration.ofSeconds(30)
    ))
    .aggregate(
        () -> new TimeSeriesBuffer(100),     // Keep last 100 points
        (key, value, buffer) -> {
            buffer.add(value);
            if (buffer.isFull()) {
                // Compute autocorrelation on buffer
                double[] acf = buffer.autocorrelation();
                // Detect patterns based on ACF
                if (hasPeriodicPattern(acf)) {
                    return new Pattern("periodic", acf);
                }
            }
            return buffer;
        }
    );

// Task 6: Multiple time scales (like frequency decomposition)
// 1-minute, 5-minute, 1-hour aggregations
KTable<Windowed<String>, Double> oneMinute = sensorStream
    .groupByKey()
    .windowedBy(TimeWindows.of(Duration.ofMinutes(1)))
    .aggregate(/* ... */);

KTable<Windowed<String>, Double> fiveMinute = sensorStream
    .groupByKey()
    .windowedBy(TimeWindows.of(Duration.ofMinutes(5)))
    .aggregate(/* ... */);

KTable<Windowed<String>, Double> oneHour = sensorStream
    .groupByKey()
    .windowedBy(TimeWindows.of(Duration.ofHours(1)))
    .aggregate(/* ... */);

// Task 7: Output results
anomalies.to("anomaly-alerts");
runningAverage.to("statistics-output");

// These can be:
// - Saved to database
// - Sent to alerting system
// - Exported for R/Python visualization
```

**Validation Checkpoint**:
- [ ] Understand streaming vs. batch processing
- [ ] Can compute running statistics
- [ ] Can detect anomalies in real-time
- [ ] Can use multiple time windows
- [ ] Can maintain sliding windows for pattern detection
- [ ] Can output results to downstream systems

**Domain Expertise Applied**:
| Your Knowledge | Streaming Implementation |
|----------------|--------------------------|
| Moving average | Windowed aggregation with mean |
| Standard deviation | Online variance algorithm |
| Anomaly detection | Z-score on running statistics |
| Autocorrelation | Sliding buffer with ACF computation |
| Spectral analysis | Periodic FFT on windows |

**Next Steps**: Episode 6 - Custom Analytics Implementation

---

#### Episode 6: Implementing Custom Time Series Analytics
**Duration**: 120 minutes
**Focus**: Translate your statistical methods into OpenTSx code

**Theory (15 min)**:
- OpenTSx extensibility model
- User-Defined Functions (UDFs)
- Custom processors for streams
- Integrating external libraries (R, Python via bridge)

**Demo Scripts**:
- `opentsx-ksql-udf/demo-udf/SummaryStatsUdaf.java`
- `opentsx-ksql-udf/demo-udf/EpisodesProcessor.java`

**Hands-On Exercise** (100 min):
```java
// Exercise: Implement your own time series methods

// Task 1: Simple custom function - Median Absolute Deviation (MAD)
// You know: MAD = median(|X - median(X)|)
// This is a robust measure of scale you might use in R

public class MADCalculator {
    public static double calculateMAD(TimeSeriesObject ts) {
        // Step 1: Calculate median
        double median = ts.getMedian();

        // Step 2: Calculate absolute deviations
        TimeSeriesObject deviations = ts.subtract(median).abs();

        // Step 3: Return median of deviations
        return deviations.getMedian();
    }
}

// Usage:
TimeSeriesObject data = loadData();
double mad = MADCalculator.calculateMAD(data);
double robustZScore = (data.getLastValue() - data.getMedian()) / mad;

// Task 2: Implement custom UDF for KSQL
// Your domain: Grubbs' test for outliers

@UdfDescription(
    name = "grubbs_test",
    description = "Performs Grubbs' test for outlier detection"
)
public class GrubbsTestUdf {
    @Udf(description = "Test if value is an outlier")
    public boolean isOutlier(
        @UdfParameter double value,
        @UdfParameter double mean,
        @UdfParameter double stdDev,
        @UdfParameter int sampleSize,
        @UdfParameter double alpha
    ) {
        // Grubbs' test statistic
        double G = Math.abs(value - mean) / stdDev;

        // Critical value from t-distribution
        TDistribution tDist = new TDistribution(sampleSize - 2);
        double tCritical = tDist.inverseCumulativeProbability(1 - alpha / (2 * sampleSize));

        double gCritical = ((sampleSize - 1) / Math.sqrt(sampleSize)) *
                          Math.sqrt(Math.pow(tCritical, 2) / (sampleSize - 2 + Math.pow(tCritical, 2)));

        return G > gCritical;
    }
}

// Use in KSQL:
/*
SELECT sensor_id,
       value,
       grubbs_test(value, avg_value, std_value, count, 0.05) as is_outlier
FROM sensor_statistics;
*/

// Task 3: Implement Seasonal-Trend decomposition using LOESS (STL)
// You know this from R's stl() function

public class STLDecomposition {
    public static Decomposition decompose(
        TimeSeriesObject ts,
        int period,
        int sWindow,
        int tWindow
    ) {
        // Your algorithm expertise:
        // 1. Detrend using LOESS
        TimeSeriesObject trend = loessSmooth(ts, tWindow);

        // 2. Deseasonalize
        TimeSeriesObject detrended = ts.subtract(trend);
        TimeSeriesObject seasonal = extractSeasonal(detrended, period, sWindow);

        // 3. Calculate residual
        TimeSeriesObject residual = ts.subtract(trend).subtract(seasonal);

        return new Decomposition(trend, seasonal, residual);
    }

    private static TimeSeriesObject loessSmooth(TimeSeriesObject ts, int window) {
        // Implement LOESS algorithm (you know this!)
        // ...
    }

    private static TimeSeriesObject extractSeasonal(TimeSeriesObject ts, int period, int window) {
        // Seasonal extraction with moving averages
        // ...
    }
}

// Task 4: Change-point detection (your algorithm!)
// Implement PELT, CUSUM, or your favorite method

public class ChangePointDetector {
    public static List<Integer> detectChanges(
        TimeSeriesObject ts,
        double threshold
    ) {
        // Your domain expertise: CUSUM algorithm
        List<Integer> changePoints = new ArrayList<>();

        double cumSum = 0;
        double mean = ts.getMean();

        for (int i = 0; i < ts.getLength(); i++) {
            cumSum += (ts.getValue(i) - mean);

            if (Math.abs(cumSum) > threshold) {
                changePoints.add(i);
                cumSum = 0;  // Reset
            }
        }

        return changePoints;
    }
}

// Task 5: Forecasting - Implement simple exponential smoothing
// You know: S_t = α * Y_t + (1 - α) * S_{t-1}

public class ExponentialSmoothing {
    public static TimeSeriesObject forecast(
        TimeSeriesObject historical,
        double alpha,
        int horizon
    ) {
        double lastSmoothed = historical.getMean();  // Initialize

        // Smooth historical data
        for (int i = 0; i < historical.getLength(); i++) {
            lastSmoothed = alpha * historical.getValue(i) +
                          (1 - alpha) * lastSmoothed;
        }

        // Forecast: constant value (simple exponential smoothing)
        TimeSeriesObject forecast = new TimeSeriesObject();
        long lastTime = historical.getLastTimestamp();

        for (int h = 1; h <= horizon; h++) {
            forecast.addValue(lastTime + h * 1000, lastSmoothed);
        }

        return forecast;
    }
}

// Task 6: Bridge to R for complex analysis
// When you need R's advanced packages

public class RBridge {
    public static double[] callRFunction(TimeSeriesObject ts, String rScript) {
        // Export to R
        ts.writeToFile("/tmp/ts_data.csv", ",");

        // Call R script
        ProcessBuilder pb = new ProcessBuilder("Rscript", rScript);
        Process process = pb.start();
        process.waitFor();

        // Read results
        double[] results = readResultsFromFile("/tmp/r_output.csv");
        return results;
    }
}

// Your R script (arima_forecast.R):
/*
library(forecast)
data <- read.csv("/tmp/ts_data.csv")
ts_data <- ts(data$value, frequency=24)
model <- auto.arima(ts_data)
forecasts <- forecast(model, h=10)
write.csv(forecasts$mean, "/tmp/r_output.csv")
*/
```

**Validation Checkpoint**:
- [ ] Can implement custom statistical functions
- [ ] Can create KSQL UDFs for stream analytics
- [ ] Can implement decomposition algorithms
- [ ] Can implement change-point detection
- [ ] Can implement forecasting methods
- [ ] Can bridge to R/Python for advanced analysis

**Your Expertise, Scaled**:
- You bring the algorithms (statistical knowledge)
- OpenTSx provides the infrastructure (distribution, storage, streaming)
- Together: scalable, production-ready time series analytics

**Next Steps**: Episode 7 - Storage and Data Management

---

#### Episode 7: Time Series Storage Strategies
**Duration**: 90 minutes
**Focus**: Store and retrieve large-scale time series efficiently

**Theory (20 min)**:
- Time series database characteristics
- Compression techniques
- Retention policies
- Query patterns for time series

**Storage Options**:
| Backend | Best For | Your Use Case |
|---------|----------|---------------|
| OpenTSDB | Metrics, monitoring | IoT sensor data, system metrics |
| Kudu | Analytics, OLAP | Historical analysis, complex queries |
| Cassandra | High write throughput | Financial ticks, high-frequency data |

**Demo Scripts**:
- `bin/015_create_opentsdb_on_docker.sh`
- `bin/015_create_kudu_on_docker.sh`
- `bin/run_opentsdb_on_docker_locally.sh`

**Hands-On Exercise** (60 min):
```java
// Exercise: Store and query time series data

// Task 1: Write time series to OpenTSDB
// Think: long-term metric storage

OpenTSDBConnector connector = new OpenTSDBConnector();
connector.openSocket();

TimeSeriesObject temperature = loadTemperatureData();

for (int i = 0; i < temperature.getLength(); i++) {
    connector.put(
        "sensor.temperature",              // metric name
        temperature.getTimestamp(i),       // timestamp
        temperature.getValue(i),           // value
        Map.of(
            "sensor_id", "sensor_01",      // tags for filtering
            "location", "warehouse_a"
        )
    );
}

connector.close();

// Task 2: Query from OpenTSDB
// Get last hour of data for all sensors in warehouse_a

String query = "http://localhost:4242/api/query?" +
    "start=1h-ago&" +
    "m=avg:sensor.temperature{location=warehouse_a}";

String response = httpClient.get(query);
TimeSeriesObject retrieved = parseOpenTSDBResponse(response);

// Task 3: Write to Kudu (for analytics)
// Think: fast scans for statistical analysis

KuduClient client = new KuduClient.KuduClientBuilder("localhost:7051")
    .build();

KuduTable table = client.openTable("sensor_analytics");
KuduSession session = client.newSession();

Insert insert = table.newInsert();
PartialRow row = insert.getRow();
row.addString("sensor_id", "sensor_01");
row.addLong("timestamp", System.currentTimeMillis());
row.addDouble("value", 25.3);
row.addDouble("mean", temperature.getMean());
row.addDouble("std_dev", temperature.getStddev());

session.apply(insert);
session.close();

// Task 4: Analytical query on Kudu
// Your use case: "Find all sensors where std_dev increased by 50%"

KuduScanner scanner = client.newScannerBuilder(table)
    .addPredicate(KuduPredicate.newComparisonPredicate(
        schema.getColumn("std_dev"),
        KuduPredicate.ComparisonOp.GREATER,
        previousStdDev * 1.5
    ))
    .build();

// Task 5: Retention policies
// Automatically downsample old data

// High resolution (raw): keep 7 days
// Medium resolution (1-minute avg): keep 30 days
// Low resolution (1-hour avg): keep forever

void applyRetentionPolicy() {
    long now = System.currentTimeMillis();
    long sevenDaysAgo = now - (7 * 24 * 60 * 60 * 1000);

    // Downsample data older than 7 days
    TimeSeriesObject old = queryRange(sevenDaysAgo - 30_DAYS, sevenDaysAgo);
    TimeSeriesObject downsampled = old.resample("1m", AggregationType.MEAN);

    // Save downsampled, delete raw
    save(downsampled);
    deleteRaw(sevenDaysAgo - 30_DAYS, sevenDaysAgo);
}

// Task 6: Export for your analysis in R
// Query specific time range and export

TimeSeriesObject data = queryFromKudu(
    "sensor_01",
    startTime,
    endTime
);

data.writeToFile("for_r_analysis.csv", ",");

/* Then in R:
library(xts)
data <- read.csv("for_r_analysis.csv")
ts_data <- xts(data$value, order.by=as.POSIXct(data$timestamp/1000, origin="1970-01-01"))
plot(ts_data)
acf(ts_data)
*/
```

**Validation Checkpoint**:
- [ ] Can write time series to OpenTSDB
- [ ] Can query time series from OpenTSDB
- [ ] Can use Kudu for analytical queries
- [ ] Can implement retention policies
- [ ] Can export data for R/Python analysis
- [ ] Understand when to use which storage backend

**Storage Decision Guide**:
- **Real-time monitoring** → OpenTSDB
- **Statistical analysis** → Kudu
- **High-frequency trading data** → Cassandra
- **Exploratory analysis** → Export to R/Python

**Next Steps**: Episode 8 - Advanced Statistical Methods

---

### 🚀 Advanced Phase (Episodes 8-10)

#### Episode 8: Advanced Statistical Methods at Scale
**Duration**: 120 minutes
**Focus**: Apply advanced time series methods to large datasets

**Theory (15 min)**:
- Scaling statistical computations
- Distributed hypothesis testing
- Bootstrap and resampling at scale
- Parallel cross-validation

**Hands-On Exercise** (100 min):
```java
// Exercise: Advanced methods on big data

// Task 1: Distributed hypothesis testing
// Test: "Do sensors in warehouse A have different mean than B?"

// In R you would: t.test(warehouseA, warehouseB)
// At scale:

Dataset<Row> warehouseA = spark.sql(
    "SELECT value FROM sensors WHERE location='warehouse_a'"
);
Dataset<Row> warehouseB = spark.sql(
    "SELECT value FROM sensors WHERE location='warehouse_b'"
);

TTestResult result = DistributedTTest.test(
    warehouseA,
    warehouseB,
    alpha = 0.05
);

System.out.println("t-statistic: " + result.getTStatistic());
System.out.println("p-value: " + result.getPValue());
System.out.println("Significant: " + result.isSignificant());

// Task 2: Bootstrap confidence intervals at scale
// You know: resample with replacement, compute statistic

public class DistributedBootstrap {
    public static ConfidenceInterval bootstrap(
        Dataset<Row> data,
        String column,
        Function<Dataset<Row>, Double> statistic,
        int numResamples,
        double alpha
    ) {
        List<Double> bootstrapStats = new ArrayList<>();

        for (int i = 0; i < numResamples; i++) {
            // Resample with replacement (distributed)
            Dataset<Row> sample = data.sample(true, 1.0);
            double stat = statistic.apply(sample);
            bootstrapStats.add(stat);
        }

        Collections.sort(bootstrapStats);

        // Percentile method
        int lowerIdx = (int) (alpha / 2 * numResamples);
        int upperIdx = (int) ((1 - alpha / 2) * numResamples);

        return new ConfidenceInterval(
            bootstrapStats.get(lowerIdx),
            bootstrapStats.get(upperIdx)
        );
    }
}

// Usage: 95% CI for median
ConfidenceInterval ci = DistributedBootstrap.bootstrap(
    sensorData,
    "value",
    df -> df.stat().approxQuantile("value", new double[]{0.5}, 0.01)[0],
    1000,
    0.05
);

// Task 3: Cross-validation for forecasting models
// You know: train/test split with time series

public class TimeSeriesCrossValidation {
    public static List<Double> crossValidate(
        TimeSeriesObject ts,
        ForecastModel model,
        int numFolds
    ) {
        List<Double> errors = new ArrayList<>();
        int foldSize = ts.getLength() / numFolds;

        for (int i = 0; i < numFolds; i++) {
            // Expanding window: train on [0, i*foldSize], test on next fold
            TimeSeriesObject train = ts.slice(0, i * foldSize);
            TimeSeriesObject test = ts.slice(i * foldSize, (i + 1) * foldSize);

            model.fit(train);
            TimeSeriesObject predictions = model.forecast(test.getLength());

            double rmse = calculateRMSE(test, predictions);
            errors.add(rmse);
        }

        return errors;
    }
}

// Task 4: Granger causality test (distributed)
// Your domain: does X predict Y?

public static GrangerResult grangerCausality(
    TimeSeriesObject x,
    TimeSeriesObject y,
    int maxLag
) {
    // Build regression: Y_t = c + Σ(α_i * Y_{t-i}) + Σ(β_i * X_{t-i}) + ε_t

    double[][] designMatrix = buildLaggedMatrix(x, y, maxLag);
    double[] response = y.getValues();

    // Regression with X lags
    OLSRegression fullModel = new OLSRegression(designMatrix, response);

    // Regression without X lags
    double[][] restrictedMatrix = buildLaggedMatrixNoX(y, maxLag);
    OLSRegression restrictedModel = new OLSRegression(restrictedMatrix, response);

    // F-test
    double fStatistic = calculateFStatistic(fullModel, restrictedModel);
    double pValue = fDistribution.cumulativeProbability(fStatistic);

    return new GrangerResult(fStatistic, pValue, pValue < 0.05);
}

// Task 5: Multivariate analysis (PCA on time series)
// You know: reduce dimensionality of correlated sensors

Dataset<Row> sensorMatrix = spark.sql(
    "SELECT timestamp, " +
    "    MAX(CASE WHEN sensor_id='s1' THEN value END) as s1, " +
    "    MAX(CASE WHEN sensor_id='s2' THEN value END) as s2, " +
    "    MAX(CASE WHEN sensor_id='s3' THEN value END) as s3 " +
    "FROM sensors " +
    "GROUP BY timestamp"
);

VectorAssembler assembler = new VectorAssembler()
    .setInputCols(new String[]{"s1", "s2", "s3"})
    .setOutputCol("features");

PCA pca = new PCA()
    .setInputCol("features")
    .setOutputCol("pcaFeatures")
    .setK(2);  // Keep 2 components

PCAModel pcaModel = pca.fit(assembler.transform(sensorMatrix));
Dataset<Row> result = pcaModel.transform(assembler.transform(sensorMatrix));

// Task 6: Wavelet analysis integration
// Call your R wavelet package

public class WaveletAnalysis {
    public static WaveletResult analyze(TimeSeriesObject ts, String wavelet) {
        // Export to R
        ts.writeToFile("/tmp/ts_data.csv", ",");

        // Call R wavelet package
        String rScript = String.format(
            "library(wavelets); " +
            "data <- read.csv('/tmp/ts_data.csv')$value; " +
            "wt <- dwt(data, '%s'); " +
            "write.csv(wt@W, '/tmp/wavelet_output.csv')",
            wavelet
        );

        runRScript(rScript);

        // Import results
        return WaveletResult.fromCSV("/tmp/wavelet_output.csv");
    }
}
```

**Validation Checkpoint**:
- [ ] Can perform distributed hypothesis tests
- [ ] Can compute bootstrap confidence intervals at scale
- [ ] Can implement time series cross-validation
- [ ] Can test Granger causality
- [ ] Can apply PCA to multivariate time series
- [ ] Can integrate R packages for advanced methods

**Your Statistical Expertise + Distributed Computing = Production-Ready Analytics**

**Next Steps**: Episode 9 - Domain-Specific Applications

---

#### Episode 9: Domain-Specific Time Series Applications
**Duration**: 120 minutes
**Focus**: Apply OpenTSx to your specific domain (finance, IoT, physics, etc.)

**Theory (10 min)**:
- Domain-specific patterns
- Customizing OpenTSx for your field
- Integration with domain tools

**Hands-On Exercise** (Choose your domain):

**Option A: Financial Time Series**
```java
// Exercise: Financial time series analysis

// Task 1: Load stock price data
TimeSeriesObject prices = TimeSeriesObject.readFromFile("stock_prices.csv");

// Task 2: Calculate returns (you know: log returns)
TimeSeriesObject logReturns = prices.logDifference();

// In R: diff(log(prices))

// Task 3: Volatility (GARCH would go here in R)
TimeSeriesObject rollingVol = logReturns
    .abs()
    .simpleMovingAverage(20);  // Simple volatility estimate

// Task 4: Risk metrics (VaR, CVaR)
double[] returns = logReturns.getValues();
Arrays.sort(returns);
double var95 = returns[(int)(returns.length * 0.05)];  // 5th percentile
double cvar95 = Arrays.stream(returns)
    .limit((int)(returns.length * 0.05))
    .average()
    .getAsDouble();

// Task 5: Correlation matrix of portfolio
List<TimeSeriesObject> stocks = loadPortfolio();
double[][] correlationMatrix = new double[stocks.size()][stocks.size()];

for (int i = 0; i < stocks.size(); i++) {
    for (int j = 0; j < stocks.size(); j++) {
        correlationMatrix[i][j] = TimeSeriesObject.correlation(
            stocks.get(i),
            stocks.get(j)
        );
    }
}
```

**Option B: IoT/Sensor Data**
```java
// Exercise: IoT sensor analytics

// Task 1: Sensor drift detection
TimeSeriesObject calibrationSignal = loadCalibrationData();
TimeSeriesObject sensorReading = loadSensorData();

double drift = calculateDrift(calibrationSignal, sensorReading);

// Task 2: Fault detection (you know: CUSUM)
FaultDetector detector = new FaultDetector.Builder()
    .method(FaultMethod.CUSUM)
    .threshold(5.0)
    .build();

List<Fault> faults = detector.detect(sensorReading);

// Task 3: Predictive maintenance
TimeSeriesObject vibration = loadVibrationData();
double[] spectrum = vibration.fft().getPowerSpectrum();

// Peak detection in frequency domain
List<Peak> peaks = detectPeaks(spectrum);
boolean maintenanceNeeded = peaks.stream()
    .anyMatch(p -> p.frequency > criticalFrequency);
```

**Option C: Physics/Signal Processing**
```java
// Exercise: Signal analysis

// Task 1: Filtering (Butterworth, etc.)
TimeSeriesObject signal = loadSignal();

// Low-pass filter
TimeSeriesObject filtered = signal.butterworth(
    FilterType.LOWPASS,
    cutoffFrequency,
    order
);

// Task 2: Spectral analysis (you know this well!)
FFTResult fft = signal.fft();
double[] frequencies = fft.getFrequencies();
double[] power = fft.getPowerSpectrum();

// Find dominant frequency
int maxIdx = argmax(power);
double dominantFreq = frequencies[maxIdx];

// Task 3: Convolution
TimeSeriesObject impulseResponse = loadImpulseResponse();
TimeSeriesObject output = signal.convolve(impulseResponse);

// Task 4: Phase analysis
double[] phase = fft.getPhase();
double[] unwrappedPhase = unwrapPhase(phase);
```

**Validation Checkpoint**:
- [ ] Can apply OpenTSx to your domain
- [ ] Can implement domain-specific algorithms
- [ ] Can validate results against R/Python
- [ ] Can integrate with domain tools

**Next Steps**: Episode 10 - Building Production Pipelines

---

#### Episode 10: Production Analytics Pipelines
**Duration**: 120 minutes
**Focus**: Put your analytics into production

**Theory (20 min)**:
- From research to production
- Scheduling and automation
- Monitoring analytics pipelines
- Versioning models and data

**Demo Scripts**:
- `bin/020_deploy_to_cc_cluster.sh`
- `bin/110_run_demo_services.sh`

**Hands-On Exercise** (90 min):
```java
// Exercise: Production-ready analytics pipeline

// Task 1: Parameterized analysis
public class ProductionAnalytics {
    @Configuration
    public static class Config {
        String inputTopic;
        String outputTopic;
        int windowSize;
        double anomalyThreshold;
        String storageBackend;
    }

    public static void main(String[] args) {
        Config config = loadConfig("analytics_config.yaml");

        StreamsBuilder builder = new StreamsBuilder();

        KStream<String, TimeSeriesObject> input =
            builder.stream(config.inputTopic);

        // Your analytics logic
        KStream<String, Anomaly> anomalies = input
            .groupByKey()
            .windowedBy(TimeWindows.of(Duration.ofMinutes(config.windowSize)))
            .aggregate(/* your algorithm */)
            .toStream()
            .filter((k, v) -> v.getScore() > config.anomalyThreshold);

        anomalies.to(config.outputTopic);

        KafkaStreams streams = new KafkaStreams(builder.build(), props);
        streams.start();
    }
}

// Task 2: Automated reporting
// Generate daily statistical reports

@Scheduled(cron = "0 0 0 * * *")  // Daily at midnight
public void generateDailyReport() {
    // Query yesterday's data
    long yesterday = System.currentTimeMillis() - 86400000;
    TimeSeriesObject data = queryFromKudu(yesterday, yesterday + 86400000);

    // Your statistical analysis
    Report report = new Report();
    report.addStatistic("Mean", data.getMean());
    report.addStatistic("Std Dev", data.getStddev());
    report.addStatistic("Anomalies", countAnomalies(data));

    // ACF plot
    double[] acf = data.autocorrelation(50);
    report.addPlot("ACF", plotACF(acf));

    // Spectral density
    double[] spectrum = data.fft().getPowerSpectrum();
    report.addPlot("Spectrum", plotSpectrum(spectrum));

    // Export for R analysis
    data.writeToFile("/reports/" + yesterday + "_data.csv", ",");

    // Generate PDF report
    report.exportToPDF("/reports/" + yesterday + "_report.pdf");

    // Email to stakeholders
    emailReport(report);
}

// Task 3: Model versioning
// Track which model version produced which results

public class ModelVersioning {
    String modelId;
    String version;
    Map<String, Object> parameters;
    String trainingData;
    Instant trainedAt;

    public void saveModel(ForecastModel model) {
        ModelVersioning metadata = new ModelVersioning();
        metadata.modelId = UUID.randomUUID().toString();
        metadata.version = "v1.0";
        metadata.parameters = model.getParameters();
        metadata.trainedAt = Instant.now();

        // Save to registry
        modelRegistry.save(metadata, model);
    }

    public ForecastModel loadModel(String modelId, String version) {
        return modelRegistry.load(modelId, version);
    }
}

// Task 4: Integration test for analytics
@Test
public void testAnomalyDetectionPipeline() {
    // Create test data (you know what anomalies look like)
    TimeSeriesObject normal = TimeSeriesObject.getGaussianDistribution(100, 10, 1);

    // Inject known anomalies
    normal.setValue(50, 50.0);  // Obvious outlier

    // Run detector
    AnomalyDetector detector = new AnomalyDetector(threshold = 3.0);
    List<Anomaly> detected = detector.detect(normal);

    // Validate
    assertEquals(1, detected.size());
    assertEquals(50, detected.get(0).getIndex());
}

// Task 5: Monitoring your analytics
MeterRegistry registry = new SimpleMeterRegistry();

// Track processing metrics
Timer analyticsTimer = registry.timer("analytics.processing.time");
Counter anomalyCounter = registry.counter("analytics.anomalies.detected");
Gauge.builder("analytics.model.accuracy", model::getAccuracy)
    .register(registry);

// Task 6: Alerting on analytical conditions
public void setupAlerts() {
    // Alert if anomaly rate > 5%
    Gauge.builder("analytics.anomaly.rate",
        () -> anomalyCounter.count() / totalRecords.count()
    )
    .tag("threshold", "0.05")
    .register(registry);

    // Alert if model accuracy drops
    Gauge.builder("analytics.model.accuracy",
        model::getAccuracy
    )
    .tag("threshold", "0.90")
    .register(registry);
}
```

**Validation Checkpoint**:
- [ ] Can configure analytics for production
- [ ] Can schedule automated reports
- [ ] Can version models
- [ ] Can test analytics pipelines
- [ ] Can monitor analytics health
- [ ] Can set up alerts

**Production Checklist**:
- [ ] Configuration externalized
- [ ] Error handling implemented
- [ ] Monitoring in place
- [ ] Tests written
- [ ] Documentation complete
- [ ] Alerts configured
- [ ] Backup strategy defined

**Track Completion**: You're now an OpenTSx expert! 🎉

---

## Appendix

### A. Quick Reference: R/Python to OpenTSx

| Task | R | Python | OpenTSx |
|------|---|--------|---------|
| Create TS | `ts(data)` | `pd.Series(data)` | `new TimeSeriesObject()` |
| Mean | `mean(x)` | `x.mean()` | `x.getMean()` |
| Std Dev | `sd(x)` | `x.std()` | `x.getStddev()` |
| Normalize | `scale(x)` | `(x-x.mean())/x.std()` | `x.normalize()` |
| Difference | `diff(x)` | `x.diff()` | `x.difference(1)` |
| ACF | `acf(x)` | `acf()` | `x.autocorrelation()` |
| FFT | `fft(x)` | `np.fft.fft(x)` | `x.fft()` |
| Smooth | `filter(x)` | `x.rolling().mean()` | `x.simpleMovingAverage()` |
| Resample | `aggregate()` | `x.resample().mean()` | `x.resample()` |

### B. When to Use What

**Use R/Python when:**
- Exploratory data analysis
- Publication-quality plots
- Cutting-edge statistical methods
- Small to medium datasets (< 10GB)
- One-off analysis

**Use OpenTSx when:**
- Large-scale data (> 100GB)
- Real-time analytics
- Production deployments
- Integration with Kafka/Spark
- High-throughput requirements

**Best Practice**: Prototype in R/Python, productionize in OpenTSx

### C. Integration Patterns

**Pattern 1: OpenTSx for ETL, R for Analysis**
```
OpenTSx (filter, aggregate) → CSV export → R (advanced analysis) → Report
```

**Pattern 2: R for Model Development, OpenTSx for Deployment**
```
R (develop model) → Export parameters → OpenTSx (apply at scale) → Production
```

**Pattern 3: Hybrid Pipeline**
```
Kafka → OpenTSx (real-time) → Kudu → R (periodic deep analysis) → Dashboard
```

### D. Community and Resources

- **OpenTSx GitHub**: [Repository URL]
- **Documentation**: [Docs URL]
- **Time Series Community**: [Forum/Slack URL]
- **Your Expertise**: Share your implementations!

### E. Next Steps

1. **Apply to your domain**: Use real data from your research
2. **Contribute**: Share your algorithms with the community
3. **Teach**: Help other domain experts onboard
4. **Collaborate**: Work with software engineers on new features

---

**Track Version**: 1.0
**Last Updated**: 2025-12-20
**Estimated Completion Time**: 15-20 hours
**Difficulty**: Intermediate (domain expert friendly)
