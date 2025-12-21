# OpenTSx Features Overview

## Table of Contents
- [Introduction](#introduction)
- [Time Series Analysis Algorithms](#time-series-analysis-algorithms)
- [Data Generation & Acquisition](#data-generation--acquisition)
- [Stream Processing](#stream-processing)
- [Storage & Persistence](#storage--persistence)
- [Query & Analytics](#query--analytics)
- [Machine Learning & Prediction](#machine-learning--prediction)
- [Visualization](#visualization)
- [Multi-Region & High Availability](#multi-region--high-availability)
- [Developer Tools](#developer-tools)
- [Integration Capabilities](#integration-capabilities)

---

## Introduction

OpenTSx provides a comprehensive suite of features for time series analysis, from data ingestion through complex algorithmic analysis to visualization and prediction. All features are designed to work in both real-time streaming and batch processing contexts.

---

## Time Series Analysis Algorithms

### Detrended Fluctuation Analysis (DFA)

**Purpose:** Detect long-range correlations in non-stationary time series

**Features:**
- Multiple polynomial orders (1-5)
- Configurable scale ranges
- Fluctuation function calculation
- Scaling exponent (Hurst exponent) estimation
- Visual output of fluctuation functions

**Use Cases:**
- Financial market analysis
- Climate data analysis
- Physiological signal processing
- Network traffic analysis

**Example:**
```java
DFA dfa = new DFA();
dfa.setPolynomOrder(1);  // Linear detrending
double[] scales = LogBinningTool.createScales(10, 1000, 20);
double[] fluctuations = dfa.calc(timeSeries, scales);
double hurstExponent = FitTool.fitSlope(scales, fluctuations);
```

**Parameters:**
- `polynomOrder`: 1 (linear), 2 (quadratic), 3 (cubic), etc.
- `scales`: Array of box sizes for analysis
- `overlapping`: Boolean for overlapping boxes

**Output:**
- Fluctuation function F(n)
- Scaling exponent α (Hurst exponent)
- Goodness of fit metrics

**Location:** `opentsx-core/src/main/java/org/opentsx/algorithms/detrending/DFA.java`

---

### Multifractal Detrended Fluctuation Analysis (MFDFA)

**Purpose:** Analyze multifractal properties of time series

**Features:**
- Multifractal spectrum calculation
- Generalized Hurst exponent h(q)
- Singularity spectrum f(α)
- Multiple moment orders (q values)
- Visualization of multifractal spectra

**Use Cases:**
- Financial market complexity
- Turbulence analysis
- Fractal dimension estimation
- Risk assessment

**Example:**
```java
MFDFA mfdfa = new MFDFA();
mfdfa.setPolynomOrder(2);
double[] qValues = {-5, -3, -1, 0, 1, 2, 3, 5};
MultifractalSpectrum spectrum = mfdfa.calculate(timeSeries, scales, qValues);
```

**Parameters:**
- `qValues`: Moment orders (negative, zero, positive)
- `scales`: Range of scales for analysis
- `polynomOrder`: Detrending polynomial degree

**Output:**
- Generalized Hurst exponent h(q)
- Scaling exponent τ(q)
- Singularity spectrum f(α)
- Multifractal width

**Location:** `opentsx-core/src/main/java/org/opentsx/algorithms/detrending/MFDFA.java`

---

### Event Synchronization

**Purpose:** Detect and quantify synchronized events between time series

**Features:**
- Event detection with configurable thresholds
- Synchronization index calculation
- Delay estimation
- Statistical significance testing
- Directionality detection

**Use Cases:**
- Climate teleconnections
- Neural spike synchronization
- Financial market correlations
- Social network dynamics

**Example:**
```java
ESCalc esCalc = new ESCalc();
esCalc.setThreshold(2.0);  // 2 standard deviations
SyncResult result = esCalc.calculate(series1, series2);
double syncIndex = result.getSynchronizationIndex();
double delay = result.getDelay();
```

**Parameters:**
- `threshold`: Event detection threshold
- `tau`: Maximum delay window
- `method`: Synchronization metric (Q, q, delay)

**Output:**
- Synchronization index (0-1)
- Delay time (in samples)
- Statistical significance (p-value)
- Directionality indicator

**Location:** `opentsx-core/src/main/java/org/opentsx/algorithms/eventsynchronisation/`

**Classes:**
- `ESCalc.java` - Main calculator
- `ESCalc2.java` - Enhanced version

---

### Return Interval Statistics (RIS)

**Purpose:** Analyze intervals between threshold-crossing events

**Features:**
- Threshold-based event detection
- Return interval distribution
- Risk metrics calculation
- Stretched exponential fitting
- Memory effects analysis

**Use Cases:**
- Extreme event analysis
- Risk assessment
- Earthquake recurrence
- Financial drawdown analysis

**Example:**
```java
RISTool risTool = new RISTool();
risTool.setThreshold(percentile(data, 95));  // 95th percentile
RISResult result = risTool.analyze(timeSeries);
double[] intervals = result.getReturnIntervals();
Distribution dist = result.getDistribution();
```

**Parameters:**
- `threshold`: Event definition threshold
- `binning`: Histogram binning method
- `fitMethod`: Distribution fitting approach

**Output:**
- Return interval distribution
- Mean return time
- Risk function
- Stretched exponential parameters
- Memory coefficient

**Location:** `opentsx-core/src/main/java/org/opentsx/algorithms/ris/RISTool.java`

---

### Statistical Analysis

#### Entropy Calculation

**Features:**
- Shannon entropy
- Approximate entropy
- Sample entropy
- Permutation entropy

**Example:**
```java
Entropy entropy = new Entropy();
double shannon = entropy.calculateShannon(timeSeries);
double approximate = entropy.approximateEntropy(timeSeries, m, r);
```

**Use Cases:**
- Complexity measurement
- Predictability assessment
- System state classification

#### Distribution Testing

**Features:**
- Shapiro-Wilk normality test
- Kolmogorov-Smirnov test
- Anderson-Darling test
- Q-Q plot generation

**Example:**
```java
DistributionTesting dt = new DistributionTesting();
boolean isNormal = dt.shapiroWilkTest(data, alpha);
double[] qqValues = dt.generateQQPlot(data);
```

#### Granger Causality

**Purpose:** Test for causal relationships between time series

**Features:**
- Lag selection (AIC, BIC)
- F-test for causality
- Bidirectional testing
- Vector autoregression (VAR)

**Example:**
```java
GrangerCausality gc = new GrangerCausality();
boolean xCausesY = gc.test(seriesX, seriesY, maxLag);
```

#### Data Normalization

**Methods:**
- Z-score normalization
- Min-max scaling
- Robust scaling (median, IQR)
- Decimal scaling

**Example:**
```java
Normalization norm = new Normalization();
double[] normalized = norm.zScore(data);
double[] scaled = norm.minMax(data, 0, 1);
```

**Location:** `opentsx-core/src/main/java/org/opentsx/algorithms/statistics/`

---

### Signal Processing

#### FFT Phase Randomization

**Purpose:** Generate surrogate time series preserving power spectrum

**Features:**
- Fast Fourier Transform
- Phase randomization
- Inverse FFT
- Amplitude preservation

**Example:**
```java
FFTPhaseRandomizer fft = new FFTPhaseRandomizer();
double[] surrogate = fft.randomize(timeSeries, seed);
```

**Use Cases:**
- Null hypothesis testing
- Significance testing
- Statistical robustness

#### Peak Detection

**Features:**
- Adaptive threshold
- Peak filtering (width, height)
- Valley detection
- Peak prominence calculation

**Example:**
```java
PeakDetector detector = new PeakDetector();
detector.setMinHeight(threshold);
detector.setMinDistance(10);
List<Peak> peaks = detector.findPeaks(data);
```

#### Trend Calculation

**Methods:**
- Moving average
- Polynomial fitting
- LOWESS smoothing
- Seasonal decomposition

**Example:**
```java
TrendCalculator tc = new TrendCalculator();
double[] trend = tc.movingAverage(data, windowSize);
double[] polynomial = tc.polynomialFit(data, degree);
```

**Location:** `opentsx-core/src/main/java/org/opentsx/dsp/`

---

### Univariate Analysis Tools

#### DFA Interval Cut Detrended
**Purpose:** DFA on segmented intervals

#### Peak Entropy Tool
**Purpose:** Entropy of peak distributions

#### Time Series Segmentation
**Purpose:** Automatic segmentation of time series

**Location:** `opentsx-core/src/main/java/org/opentsx/algorithms/univariate/`

---

## Data Generation & Acquisition

### Synthetic Data Generation

#### Sine Wave Generator

**Features:**
- Configurable amplitude, frequency, phase
- Multiple simultaneous series
- Noise injection (Gaussian, uniform)
- Drift simulation

**Example:**
```java
TSDataSineWaveGenerator gen = new TSDataSineWaveGenerator();
gen.setAmplitude(10.0);
gen.setFrequency(0.1);
gen.setPhase(0.0);
gen.setNoise(0.5);
TimeSeriesObject tso = gen.generate(1000);
```

**Use Cases:**
- Algorithm testing
- Performance benchmarking
- Demo applications

**Location:** `opentsx-lg/src/main/java/org/opentsx/lg/TSDataSineWaveGenerator.java`

#### Long-Term Correlation Generator

**Purpose:** Generate time series with specific correlation properties

**Features:**
- Configurable Hurst exponent
- Power-law correlations
- Fractal Brownian motion
- ARFIMA processes

**Example:**
```java
LongTermCorrelationSeriesGenerator ltcGen =
    new LongTermCorrelationSeriesGenerator();
ltcGen.setHurstExponent(0.7);
double[] series = ltcGen.generate(10000);
```

**Use Cases:**
- Testing long-range correlation algorithms
- Validating DFA/MFDFA implementations
- Generating realistic test data

**Location:** `opentsx-core/src/main/java/org/opentsx/generators/`

### External Data Sources

#### Yahoo Finance Integration

**Features:**
- Historical stock prices
- Multiple symbols
- Adjustable time ranges
- Automatic data cleaning

**Example:**
```java
// Configuration in external data source connectors
// Fetch AAPL, GOOGL, MSFT historical data
```

**Data Types:**
- Open, High, Low, Close prices
- Trading volume
- Adjusted close

#### Wikipedia Click Data

**Features:**
- Page view statistics
- Trending topics
- Temporal patterns

**Use Cases:**
- Social dynamics analysis
- Trend detection
- Event impact analysis

**Location:** Data connectors and examples in `opentsx-connectors/`

---

## Stream Processing

### Kafka Streams Integration

#### Event Aggregation

**Features:**
- Session windowing (gap-based)
- Tumbling windows (fixed size)
- Hopping windows (overlapping)
- Sliding windows (continuous)

**Example:**
```java
StreamsBuilder builder = new StreamsBuilder();
KStream<String, EventRecord> events = builder.stream("events");

events.groupByKey()
    .windowedBy(SessionWindows.with(Duration.ofMinutes(5)))
    .aggregate(
        () -> new EpisodesRecord(),
        (key, event, episode) -> aggregateEvent(event, episode),
        Materialized.as("episodes-store")
    )
    .toStream()
    .to("episodes");
```

**Windowing Strategies:**
- **Session Windows**: Events with gaps > threshold → new window
- **Tumbling Windows**: Fixed-size, non-overlapping
- **Hopping Windows**: Fixed-size, overlapping
- **Sliding Windows**: Continuous, time-based

**Location:** `opentsx-kafka-streams-tsa/`

#### State Store Management

**Features:**
- RocksDB (embedded)
- Cassandra (distributed)
- Changelog topics (backup)
- Standby replicas (failover)

**Custom State Store:**
```java
StoreBuilder<KeyValueStore<String, Long>> storeBuilder =
    Stores.keyValueStoreBuilder(
        new CassandraKeyValueBytesStoreSupplier("state"),
        Serdes.String(),
        Serdes.Long()
    );
```

**Location:** `opentsx-kstreams-cassandra-state-store/`

---

### ksqlDB Streaming SQL

#### Continuous Queries

**Features:**
- SQL syntax for streams
- Real-time aggregations
- Join operations
- Window functions

**Example:**
```sql
CREATE STREAM sensor_readings (
    sensor_id VARCHAR KEY,
    value DOUBLE,
    timestamp BIGINT
) WITH (
    KAFKA_TOPIC='sensor_data',
    VALUE_FORMAT='AVRO'
);

CREATE TABLE sensor_stats AS
SELECT
    sensor_id,
    AVG(value) AS avg_value,
    STDDEV(value) AS stddev,
    COUNT(*) AS count
FROM sensor_readings
WINDOW TUMBLING (SIZE 1 HOUR)
GROUP BY sensor_id;
```

#### Custom UDFs/UDAFs

**Available Functions:**
- `EPISODES_PROCESSOR()` - Process episode records
- `SUMMARY_STATS()` - Statistical aggregations
- `EXTRACT_METADATA()` - Metadata extraction

**Creating Custom UDF:**
```java
@UdfDescription(name = "my_function", description = "Custom analysis")
public class MyUdf {
    @Udf(description = "Process value")
    public double process(double value) {
        // Custom logic
        return result;
    }
}
```

**Location:** `opentsx-ksql-udf/demo-udf/`

---

## Storage & Persistence

### Apache Cassandra Support

**Features:**
- Distributed time series storage
- Horizontal scalability
- Multi-datacenter replication
- Tunable consistency

**Schema Design:**
```sql
CREATE TABLE time_series (
    series_id text,
    bucket timestamp,
    timestamp bigint,
    value double,
    metadata map<text, text>,
    PRIMARY KEY ((series_id, bucket), timestamp)
) WITH CLUSTERING ORDER BY (timestamp DESC)
  AND compaction = {'class': 'TimeWindowCompactionStrategy'}
  AND default_time_to_live = 2592000;  -- 30 days
```

**Features:**
- Time-based partitioning
- Bucketing for performance
- TTL support
- Compression (LZ4, Snappy)

**API:**
```java
TSOWriter4Cassandra writer = new TSOWriter4Cassandra(session);
writer.write(tso);

TSOReader4Cassandra reader = new TSOReader4Cassandra(session);
List<TimeSeriesObject> data = reader.read("sensor_1", start, end);
```

**Location:** `opentsx-store-cassandra/`

---

### OpenTSDB Support

**Features:**
- Metrics-focused time series DB
- HBase backend
- Tag-based querying
- Downsampling
- Aggregation functions

**Data Model:**
```
Metric: sensor.temperature
Tags: {sensor_id=s1, location=dc1}
Timestamp: 1234567890
Value: 42.5
```

**API:**
```java
OpenTSDBWriter writer = new OpenTSDBWriter("http://localhost:4242");
writer.write("sensor.temperature", timestamp, value, tags);

OpenTSDBClient client = new OpenTSDBClient("http://localhost:4242");
QueryResult result = client.query(
    "sensor.temperature",
    "1h-ago",
    Aggregator.AVG,
    tags
);
```

**Location:** `opentsx-store-opentsdb/`

---

### HDFS Support

**Features:**
- Distributed file storage
- Multiple formats (SequenceFile, Avro, Parquet)
- Batch processing
- Hadoop ecosystem integration

**Formats:**
- **SequenceFile**: Key-value pairs
- **Avro**: Schema-based
- **Parquet**: Columnar

**API:**
```java
TSBucket bucket = new TSBucket();
bucket.addTS(tso);

// Write to HDFS
SequenceFileWriter writer = new SequenceFileWriter();
writer.write("hdfs://namenode/path", bucket);

// Read from HDFS
TSBucketLoader loader = new TSBucketLoader();
TSBucket loaded = loader.load("hdfs://namenode/path");
```

**Location:** `opentsx-core/src/main/java/org/opentsx/tsbucket/`

---

## Query & Analytics

### Apache Hive Integration

**Features:**
- SQL queries on HDFS time series data
- Custom UDFs for time series operations
- Batch analytics
- Integration with Hadoop ecosystem

**Example:**
```sql
-- Using custom UDFs
SELECT
    sensor_id,
    TS_MEAN(value) AS avg,
    TS_DFA(value) AS hurst_exponent
FROM sensor_data
WHERE date >= '2024-01-01'
GROUP BY sensor_id;
```

**Location:** `opentsx-hive-udf/`

---

### Spark Integration

**Features:**
- Batch processing of time series
- RDD/DataFrame support
- Distributed algorithm execution
- MLlib integration

**Example:**
```scala
val tsBuckets = sc.sequenceFile[String, TSBucket]("hdfs://path")

val results = tsBuckets.map { case (key, bucket) =>
    val dfa = new DFA()
    dfa.setPolynomOrder(1)
    val hurst = dfa.calculate(bucket.getTS(0))
    (key, hurst)
}

results.saveAsTextFile("hdfs://output")
```

**Location:** Demo scripts in `bin/` and `opentsx-app-demos/`

---

## Machine Learning & Prediction

### TensorFlow Integration

**Features:**
- Pre-trained model loading
- Real-time inference
- GPU acceleration
- Batch prediction

**Supported Models:**
- LSTM for time series forecasting
- CNN for pattern recognition
- Autoencoder for anomaly detection
- Custom TensorFlow models

**Example:**
```java
TensorFlowPredictor predictor = new TensorFlowPredictor("model.pb");

// Single prediction
double prediction = predictor.predict(timeSeriesData);

// Batch prediction
double[] predictions = predictor.predictBatch(multipleSeriesData);
```

**Dependencies:**
- TensorFlow 1.15.0
- TensorFlow JNI
- TensorFlow JNI-GPU (optional)

**Location:** `opentsx-predict/`

---

### Statistical ML (Smile)

**Features:**
- Classification algorithms
- Regression models
- Clustering methods
- Feature engineering

**Available Algorithms:**
- Random Forest
- SVM
- K-Means
- PCA

**Example:**
```java
// Using Smile for classification
RandomForest rf = new RandomForest(...);
rf.train(features, labels);
int prediction = rf.predict(newFeatures);
```

**Location:** Integrated in `opentsx-core/` dependencies

---

## Visualization

### JFreeChart Integration

**Features:**
- Time series plots
- Multi-series charts
- Dynamic updating
- Export to PNG, SVG

**Chart Types:**
- Line charts
- Scatter plots
- Histogram
- Box plots
- Heatmaps

**Example:**
```java
SimpleChartPanel chart = new SimpleChartPanel();
chart.addSeries(timeSeriesObject);
chart.setTitle("Sensor Data");
chart.display();
```

**Location:** `opentsx-core/src/main/java/org/opentsx/chart/`

---

### Grafana Dashboards

**Features:**
- Real-time monitoring
- Pre-built dashboards
- Custom panels
- Alerting

**Available Dashboards:**
- Kafka Consumer metrics
- Kafka Producer metrics
- Broker metrics
- Zookeeper metrics
- Custom application metrics

**Data Sources:**
- Prometheus
- InfluxDB
- Cassandra
- OpenTSDB

**Location:** `opentsx-clusters/*/grafana/`

---

### Gnuplot Integration

**Features:**
- Publication-quality plots
- Batch plot generation
- Scripting support

**Example:**
```java
GnuplotChart gnuplot = new GnuplotChart();
gnuplot.addData(timeSeries);
gnuplot.setTitle("DFA Fluctuation Function");
gnuplot.plot("output.png");
```

**Location:** `opentsx-core/src/main/java/org/opentsx/chart/gnuplot/`

---

## Multi-Region & High Availability

### Multi-Region Deployments

**Supported Patterns:**
- Active-active
- Active-passive
- Multi-master replication

**Features:**
- Cross-region replication
- Locality-aware routing
- Conflict resolution
- Geo-redundancy

**Configurations:**
- Confluent Platform multi-region
- In-house multi-datacenter
- Cloud-native multi-region

**Location:** `opentsx-clusters/`

---

### High Availability Features

**Kafka:**
- Broker replication (configurable factor)
- In-sync replicas (ISR)
- Leader election
- Rack awareness

**Cassandra:**
- Multi-datacenter replication
- Tunable consistency (ONE, QUORUM, ALL)
- Gossip protocol
- Hinted handoff

**KStreams:**
- Standby replicas
- State store changelog
- Automatic failover
- Partition rebalancing

---

## Developer Tools

### Latency Measurement (kping/klatency)

**Features:**
- Round-trip latency measurement
- Kafka-based ping/pong
- Latency histograms
- Percentile calculations

**Usage:**
```bash
# Start latency benchmark
java -jar klatency.jar \
  --bootstrap-servers localhost:9092 \
  --request-topic latency_request \
  --response-topic latency_response
```

**Metrics:**
- Min/max/avg latency
- P50, P95, P99 percentiles
- Latency distribution

**Location:** `opentsx-connectors/src/main/java/org/opentsx/connectors/klatency/`

---

### Topic Management Tools

**Features:**
- Create/delete topics
- Update configurations
- Partition management
- Batch operations

**Example:**
```java
TopicsManagerTool manager = new TopicsManagerTool(adminProps);

// Create topic
manager.createTopic("new_topic", 10, (short) 3);

// List topics
Set<String> topics = manager.listTopics();

// Delete topic
manager.deleteTopic("old_topic");
```

**Location:** `opentsx-connectors/src/main/java/org/opentsx/connectors/topicmanager/`

---

### Docker Support

**Features:**
- Pre-built container images
- Docker Compose configurations
- Multi-container orchestration
- Volume management

**Available Images:**
- `opentsx/time-series-generator:3.0.0`
- Custom application containers

**Example:**
```bash
# Build container
cd opentsx-lg
mvn clean package -PDocker

# Run container
docker run -d \
  -e OPENTSX_SHOW_GUI=true \
  -v $(pwd)/config:/config \
  opentsx/time-series-generator:3.0.0
```

**Location:** Docker configurations in individual module POMs

---

### Build & Testing Tools

**Maven Features:**
- Multi-module builds
- Profile-based builds
- Shade plugin for fat JARs
- Assembly plugin for distributions

**Testing:**
- JUnit integration
- Jacoco code coverage
- Integration tests
- Performance benchmarks

**Build Commands:**
```bash
# Build all active modules
mvn clean install

# Build with tests
mvn clean test

# Build specific profile
mvn clean package -PSimpleTimeSeriesProducer

# Skip tests
mvn clean install -DskipTests

# Generate coverage report
mvn clean test jacoco:report
```

---

## Integration Capabilities

### Schema Registry

**Features:**
- Avro schema management
- Schema evolution (forward/backward compatible)
- Schema versioning
- Centralized schema storage

**Usage:**
```java
// Automatic schema registration with Avro serializer
Properties props = new Properties();
props.put("schema.registry.url", "http://localhost:8081");
props.put("value.serializer", KafkaAvroSerializer.class);

Producer<String, EpisodesRecord> producer = new KafkaProducer<>(props);
```

---

### Confluent Cloud

**Features:**
- Managed Kafka clusters
- Schema Registry
- ksqlDB
- Connectors

**Configuration:**
```properties
# ccloud.props
bootstrap.servers=pkc-xxxxx.us-east-1.aws.confluent.cloud:9092
security.protocol=SASL_SSL
sasl.mechanism=PLAIN
sasl.jaas.config=...
```

**Location:** `config/ccloud.props`, `opentsx-app-demos/ccloud-demo-09-2020/`

---

### Apache Superset

**Features:**
- Interactive dashboards
- SQL Lab
- Chart builder
- Dashboard sharing

**Integration:**
- Connect to Cassandra, Hive, Presto
- Visualize time series data
- Create custom dashboards

**Location:** `opentsx-app-demos/meetup-Q4-2020/superset-frontend/` (submodule)

---

## Feature Matrix

| Feature Category | Real-time | Batch | GUI | API | Cloud-Ready |
|-----------------|-----------|-------|-----|-----|-------------|
| DFA/MFDFA | ✓ | ✓ | ✓ | ✓ | ✓ |
| Event Sync | ✓ | ✓ | ✓ | ✓ | ✓ |
| RIS | ✓ | ✓ | ✓ | ✓ | ✓ |
| Statistical Analysis | ✓ | ✓ | ✓ | ✓ | ✓ |
| Data Generation | ✓ | ✓ | ✓ | ✓ | ✓ |
| Kafka Streams | ✓ | - | - | ✓ | ✓ |
| ksqlDB | ✓ | - | ✓ | ✓ | ✓ |
| Cassandra Storage | ✓ | ✓ | - | ✓ | ✓ |
| OpenTSDB Storage | ✓ | ✓ | - | ✓ | ✓ |
| HDFS Storage | - | ✓ | - | ✓ | ✓ |
| TensorFlow Predict | ✓ | ✓ | - | ✓ | ✓ |
| Visualization | ✓ | ✓ | ✓ | - | ✓ |
| Multi-Region | ✓ | ✓ | ✓ | ✓ | ✓ |

---

## Performance Characteristics

### Algorithm Performance

| Algorithm | Time Complexity | Space Complexity | Typical Runtime (10K points) |
|-----------|----------------|------------------|------------------------------|
| DFA | O(N × S) | O(N) | ~100ms |
| MFDFA | O(N × S × Q) | O(N × Q) | ~500ms |
| Event Sync | O(N²) | O(N) | ~200ms |
| RIS | O(N log N) | O(N) | ~50ms |
| FFT | O(N log N) | O(N) | ~10ms |

Where:
- N = number of data points
- S = number of scales
- Q = number of moment orders

### Stream Processing Performance

| Operation | Throughput | Latency (P99) |
|-----------|-----------|---------------|
| Event Ingestion | 100K events/sec | <10ms |
| Session Windowing | 50K windows/sec | <50ms |
| State Store Read | 1M reads/sec | <1ms |
| State Store Write | 100K writes/sec | <5ms |

### Storage Performance

| Storage | Write Throughput | Read Throughput | Latency |
|---------|-----------------|-----------------|---------|
| Cassandra | 50K writes/sec | 100K reads/sec | <5ms |
| OpenTSDB | 10K writes/sec | 50K reads/sec | <10ms |
| HDFS | 100 MB/sec | 200 MB/sec | Variable |

---

## Conclusion

OpenTSx provides enterprise-grade features for:
- **Advanced Analysis**: DFA, MFDFA, Event Sync, RIS, and more
- **Real-time Processing**: Kafka Streams, ksqlDB
- **Flexible Storage**: Cassandra, OpenTSDB, HDFS
- **Machine Learning**: TensorFlow integration
- **Visualization**: JFreeChart, Grafana, Gnuplot
- **Cloud-Native**: Docker, Kubernetes, multi-region
- **Developer Friendly**: Comprehensive APIs, tools, examples

All features are production-ready and battle-tested in real-world deployments.

---

**For architecture details, see [ARCHITECTURE.md](ARCHITECTURE.md)**
**For module documentation, see [MODULES.md](MODULES.md)**
**For deployment procedures, see [DEPLOYMENT.md](DEPLOYMENT.md)**
