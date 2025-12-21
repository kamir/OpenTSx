# OpenTSx Onboarding Path: Software Engineer Track

## Track Overview

**Target Audience**: Experienced software engineers with strong Java/Scala skills and distributed systems knowledge, but limited time series analysis experience.

**Duration**: 15-20 hours (10 episodes × 1.5-2 hours each)

**Prerequisites**:
- Java 8+ proficiency
- Basic Scala knowledge (helpful but not required)
- Understanding of distributed systems concepts
- Familiarity with Apache Spark and/or Kafka (recommended)
- Docker basics

**Learning Outcomes**: By completing this track, you will:
1. Understand fundamental time series concepts
2. Work with time series data structures in OpenTSx
3. Implement common time series operations
4. Build streaming and batch time series pipelines
5. Deploy time series applications to production

---

## Episode Guide

### 🎯 Foundation Phase (Episodes 1-3)

#### Episode 1: Environment Setup & First Run
**Duration**: 90 minutes
**Focus**: Get OpenTSx running locally with all dependencies

**Theory (10 min)**:
- OpenTSx architecture overview
- Component diagram: Core, Connectors, Storage, Streaming
- Development vs. production environments

**Demo Scripts**:
1. `bin/010_build.sh` - Build the project
2. `bin/110_run_demo_services.sh` - Start Kudu and OpenTSDB
3. `bin/120_run_demo.sh` - Run MacroRecorder demo

**Hands-On Exercise** (60 min):
```bash
# Task 1: Build OpenTSx
cd /path/to/OpenTSx
./bin/010_build.sh

# Task 2: Verify build artifacts
ls -l target/

# Task 3: Start local services
./bin/110_run_demo_services.sh

# Task 4: Verify services are running
# Kudu: http://localhost:8050
# OpenTSDB: http://localhost:4242

# Task 5: Run the demo
./bin/120_run_demo.sh
```

**Validation Checkpoint**:
- [ ] Project builds successfully
- [ ] All tests pass
- [ ] Kudu UI accessible at localhost:8050
- [ ] OpenTSDB UI accessible at localhost:4242
- [ ] MacroRecorder demo runs and displays charts

**Troubleshooting Guide**:
- If build fails: Check JAVA_HOME environment variable
- If Docker services fail: Ensure Docker daemon is running
- If ports are in use: Stop conflicting services or change ports

**Next Steps**: Episode 2 - Understanding Time Series Data Structures

---

#### Episode 2: Time Series Data Structures
**Duration**: 90 minutes
**Focus**: Learn core data structures (TimeSeriesObject, Messreihe)

**Theory (15 min)**:
- What is a time series?
- Components: timestamps, values, metadata
- OpenTSx data model
  - `TimeSeriesObject` (core data structure)
  - `Messreihe` (measurement series)
  - Metadata and labels

**Demo Scripts**:
- `scala-scripts/run_rng_demo.scala` - Generate synthetic time series
- Create: `demo/SimpleTimeSeriesCreation.java` (to be created)

**Hands-On Exercise** (60 min):
```java
// Exercise: Create and manipulate time series

// Task 1: Create a simple time series
TimeSeriesObject ts = new TimeSeriesObject();
ts.setLabel("temperature_sensor_01");
ts.addValue(System.currentTimeMillis(), 22.5);
ts.addValue(System.currentTimeMillis() + 1000, 23.1);
ts.addValue(System.currentTimeMillis() + 2000, 22.8);

// Task 2: Generate Gaussian distribution
TimeSeriesObject gaussianTS =
    TimeSeriesObject.getGaussianDistribution(1000, 10.0, 1.5);

// Task 3: Access time series properties
System.out.println("Length: " + gaussianTS.getLength());
System.out.println("Mean: " + gaussianTS.getMean());
System.out.println("Std Dev: " + gaussianTS.getStddev());

// Task 4: Export to different formats
gaussianTS.writeToFile("output.tsv", "\t");
gaussianTS.writeToJSON("output.json");

// Task 5: Load time series from file
TimeSeriesObject loaded = TimeSeriesObject.readFromFile("output.tsv");
```

**Validation Checkpoint**:
- [ ] Can create TimeSeriesObject programmatically
- [ ] Can generate synthetic time series (Gaussian, uniform)
- [ ] Can calculate basic statistics (mean, std dev)
- [ ] Can export/import time series in multiple formats
- [ ] Understand metadata and labeling

**Key Concepts**:
- Time series representation in memory
- Lazy vs. eager evaluation
- Metadata importance for tracking provenance

**Next Steps**: Episode 3 - Basic Time Series Operations

---

#### Episode 3: Basic Time Series Operations
**Duration**: 120 minutes
**Focus**: Transform, filter, and aggregate time series data

**Theory (15 min)**:
- Time series transformations
- Filtering and windowing
- Aggregation strategies
- Resampling and interpolation

**Demo Scripts**:
- Create: `demo/BasicOperations.java` (to be created)
- Reference: `opentsx-core` source code

**Hands-On Exercise** (90 min):
```java
// Exercise: Apply operations to time series

// Task 1: Load sample data
TimeSeriesObject rawData = TimeSeriesObject.readFromFile("sensor_data.tsv");

// Task 2: Apply transformations
TimeSeriesObject normalized = rawData.normalize();
TimeSeriesObject scaled = rawData.multiply(100);
TimeSeriesObject offset = rawData.add(10);

// Task 3: Filter operations
TimeSeriesObject filtered = rawData.filterByValue(value -> value > 10.0);
TimeSeriesObject windowed = rawData.filterByTimeRange(startTime, endTime);

// Task 4: Aggregations
double sum = rawData.sum();
double mean = rawData.getMean();
double max = rawData.getMax();
double min = rawData.getMin();

// Task 5: Resampling
TimeSeriesObject hourlyAvg = rawData.resample("1H", AggregationType.MEAN);
TimeSeriesObject downsampled = rawData.downsample(10);

// Task 6: Combine multiple time series
TimeSeriesObject combined = TimeSeriesObject.add(ts1, ts2);
TimeSeriesObject correlation = TimeSeriesObject.correlate(ts1, ts2);
```

**Validation Checkpoint**:
- [ ] Can normalize and scale time series
- [ ] Can filter by value and time range
- [ ] Can compute aggregations
- [ ] Can resample at different frequencies
- [ ] Can combine multiple time series

**Key Concepts**:
- Immutability vs. mutation in transformations
- Memory efficiency considerations
- When to use which aggregation method

**Next Steps**: Episode 4 - Kafka Streams Integration

---

### 🔧 Core Skills Phase (Episodes 4-7)

#### Episode 4: Kafka Streams Integration
**Duration**: 120 minutes
**Focus**: Process time series data streams with Kafka Streams

**Theory (20 min)**:
- Stream processing fundamentals
- Kafka Streams topology
- State stores for time series
- Windowing in streams

**Demo Scripts**:
- `opentsx-kstreams-cassandra-state-store/StateStoreExample1.java`
- `opentsx-kstreams-cassandra-state-store/StateStoreExample2.java`
- `opentsx-ksql-udf/demo-udf/` - Custom UDFs

**Hands-On Exercise** (90 min):
```java
// Exercise: Build a time series streaming pipeline

// Task 1: Create a simple Kafka Streams topology
StreamsBuilder builder = new StreamsBuilder();
KStream<String, TimeSeriesObject> input =
    builder.stream("timeseries-input");

// Task 2: Transform time series in stream
KStream<String, TimeSeriesObject> transformed = input
    .mapValues(ts -> ts.normalize())
    .filter((key, ts) -> ts.getMean() > 0);

// Task 3: Aggregate with windowing
KTable<Windowed<String>, Double> windowed = input
    .groupByKey()
    .windowedBy(TimeWindows.of(Duration.ofMinutes(5)))
    .aggregate(
        () -> 0.0,
        (key, ts, agg) -> agg + ts.getMean()
    );

// Task 4: Use state store
StoreBuilder<KeyValueStore<String, TimeSeriesObject>> storeBuilder =
    Stores.keyValueStoreBuilder(
        Stores.persistentKeyValueStore("ts-store"),
        Serdes.String(),
        new TimeSeriesObjectSerde()
    );

// Task 5: Write to output topic
transformed.to("timeseries-output");

// Task 6: Start the stream
KafkaStreams streams = new KafkaStreams(builder.build(), props);
streams.start();
```

**Validation Checkpoint**:
- [ ] Can create Kafka Streams topology
- [ ] Can transform time series in streams
- [ ] Can use windowing for aggregations
- [ ] Can work with state stores
- [ ] Can write custom serdes for TimeSeriesObject

**Key Concepts**:
- Stream vs. table semantics
- Windowing strategies (tumbling, hopping, session)
- State store backends (RocksDB, Cassandra)
- Exactly-once semantics

**Next Steps**: Episode 5 - KSQL and UDFs

---

#### Episode 5: KSQL and Custom UDFs
**Duration**: 90 minutes
**Focus**: Write SQL-like queries on time series streams

**Theory (15 min)**:
- KSQL fundamentals
- User-Defined Functions (UDFs)
- User-Defined Aggregate Functions (UDAFs)
- Time series specific operations in SQL

**Demo Scripts**:
- `opentsx-ksql-udf/demo-udf/ReverseUdf.java`
- `opentsx-ksql-udf/demo-udf/SummaryStatsUdaf.java`
- `opentsx-ksql-udf/demo-udf/EpisodesProcessor.java`

**Hands-On Exercise** (60 min):
```sql
-- Exercise: Query time series with KSQL

-- Task 1: Create a stream from Kafka topic
CREATE STREAM timeseries_raw (
    sensor_id VARCHAR KEY,
    timestamp BIGINT,
    value DOUBLE,
    metadata MAP<VARCHAR, VARCHAR>
) WITH (
    KAFKA_TOPIC='sensor-data',
    VALUE_FORMAT='JSON'
);

-- Task 2: Filter and transform
CREATE STREAM timeseries_filtered AS
    SELECT sensor_id,
           timestamp,
           value,
           metadata
    FROM timeseries_raw
    WHERE value > 0 AND value < 100;

-- Task 3: Use custom UDF for normalization
CREATE STREAM timeseries_normalized AS
    SELECT sensor_id,
           timestamp,
           NORMALIZE(value) as normalized_value,
           metadata
    FROM timeseries_filtered;

-- Task 4: Windowed aggregation with UDAF
CREATE TABLE timeseries_stats AS
    SELECT sensor_id,
           WINDOWSTART as window_start,
           WINDOWEND as window_end,
           SUMMARY_STATS(value) as stats
    FROM timeseries_filtered
    WINDOW TUMBLING (SIZE 5 MINUTES)
    GROUP BY sensor_id;

-- Task 5: Extract episodes metadata
CREATE STREAM episodes AS
    SELECT sensor_id,
           EXTRACT_EPISODES_METADATA(value) as episodes
    FROM timeseries_filtered;
```

**Java UDF Implementation**:
```java
// Task 6: Implement a custom UDF
@UdfDescription(
    name = "normalize",
    description = "Normalizes time series values"
)
public class NormalizeUdf {
    @Udf(description = "Normalize a value using z-score")
    public double normalize(
        @UdfParameter double value,
        @UdfParameter double mean,
        @UdfParameter double stddev
    ) {
        return (value - mean) / stddev;
    }
}
```

**Validation Checkpoint**:
- [ ] Can write KSQL queries for time series
- [ ] Can create and use custom UDFs
- [ ] Can create and use custom UDAFs
- [ ] Can perform windowed aggregations in KSQL
- [ ] Can deploy UDFs to KSQL server

**Key Concepts**:
- UDF vs. UDAF vs. UDTF
- Stateful vs. stateless functions
- Performance implications of custom functions

**Next Steps**: Episode 6 - Spark Integration

---

#### Episode 6: Apache Spark Integration
**Duration**: 120 minutes
**Focus**: Batch processing of time series with Spark

**Theory (15 min)**:
- Spark fundamentals for time series
- RDD vs. DataFrame vs. Dataset
- Distributed time series operations
- Partitioning strategies

**Demo Scripts**:
- `bin/130_run_demo_in_spark_shell_locally.sh`
- `scala-scripts/run_opentsdb_streaming_demo.scala`

**Hands-On Exercise** (90 min):
```scala
// Exercise: Process time series with Spark

// Task 1: Load time series data
val spark = SparkSession.builder()
    .appName("TimeSeriesProcessing")
    .master("local[*]")
    .getOrCreate()

import spark.implicits._

val tsDF = spark.read
    .option("header", "true")
    .csv("timeseries_data.csv")
    .withColumn("timestamp", $"timestamp".cast("long"))
    .withColumn("value", $"value".cast("double"))

// Task 2: Partition by time range
val partitioned = tsDF.repartition($"sensor_id")

// Task 3: Window-based aggregation
import org.apache.spark.sql.expressions.Window

val windowSpec = Window
    .partitionBy("sensor_id")
    .orderBy("timestamp")
    .rowsBetween(-10, 10)

val withMovingAvg = tsDF
    .withColumn("moving_avg", avg($"value").over(windowSpec))
    .withColumn("moving_std", stddev($"value").over(windowSpec))

// Task 4: Detect anomalies
val anomalies = withMovingAvg.filter(
    abs($"value" - $"moving_avg") > 3 * $"moving_std"
)

// Task 5: Save to Kudu
anomalies.write
    .format("org.apache.kudu.spark.kudu")
    .option("kudu.master", "localhost:7051")
    .option("kudu.table", "anomalies")
    .mode("append")
    .save()

// Task 6: Write to OpenTSDB
tsDF.foreachPartition { partition =>
    val connector = new OpenTSDBConnector()
    connector.openSocket()

    partition.foreach { row =>
        connector.put(
            row.getAs[String]("sensor_id"),
            row.getAs[Long]("timestamp"),
            row.getAs[Double]("value")
        )
    }

    connector.close()
}
```

**Validation Checkpoint**:
- [ ] Can load time series data into Spark
- [ ] Can use window functions for time series
- [ ] Can perform distributed aggregations
- [ ] Can write to Kudu and OpenTSDB
- [ ] Can optimize Spark jobs for time series workloads

**Key Concepts**:
- Partitioning strategies for time series
- Window functions vs. groupBy aggregations
- Connector patterns for time series databases
- Memory management for large time series

**Next Steps**: Episode 7 - Storage Backends

---

#### Episode 7: Storage Backends Deep Dive
**Duration**: 120 minutes
**Focus**: Work with Kudu, OpenTSDB, and Cassandra

**Theory (20 min)**:
- Time series storage patterns
- Kudu: columnar storage for analytics
- OpenTSDB: metrics and monitoring
- Cassandra: wide-column store for high write throughput
- Choosing the right backend

**Demo Scripts**:
- `bin/015_create_kudu_on_docker.sh`
- `bin/015_create_opentsdb_on_docker.sh`
- `bin/run_kudu_on_docker_locally.sh`
- `bin/run_opentsdb_on_docker_locally.sh`

**Hands-On Exercise** (90 min):
```java
// Exercise: Work with multiple storage backends

// Task 1: Write to Kudu
KuduClient client = new KuduClient.KuduClientBuilder("localhost:7051")
    .build();

KuduTable table = client.openTable("timeseries");
KuduSession session = client.newSession();

for (TimeSeriesObject ts : timeSeriesList) {
    Insert insert = table.newInsert();
    PartialRow row = insert.getRow();
    row.addString("sensor_id", ts.getLabel());
    row.addLong("timestamp", ts.getTimestamp(0));
    row.addDouble("value", ts.getValue(0));
    session.apply(insert);
}
session.close();

// Task 2: Query from Kudu
KuduScanner scanner = client.newScannerBuilder(table)
    .addPredicate(KuduPredicate.newComparisonPredicate(
        schema.getColumn("timestamp"),
        KuduPredicate.ComparisonOp.GREATER_EQUAL,
        startTime
    ))
    .build();

while (scanner.hasMoreRows()) {
    RowResultIterator results = scanner.nextRows();
    while (results.hasNext()) {
        RowResult row = results.next();
        // Process row
    }
}

// Task 3: Write to OpenTSDB
OpenTSDBConnector connector = new OpenTSDBConnector();
connector.openSocket();

for (TimeSeriesObject ts : timeSeriesList) {
    connector.put(
        "sensor.temperature",
        System.currentTimeMillis(),
        ts.getMean(),
        Map.of("sensor_id", ts.getLabel())
    );
}
connector.close();

// Task 4: Query from OpenTSDB via HTTP API
String query = "http://localhost:4242/api/query?" +
    "start=1h-ago&" +
    "m=avg:sensor.temperature{sensor_id=*}";

String response = httpClient.get(query);

// Task 5: Write to Cassandra (via state store)
CassandraStateStore store = new CassandraStateStore(
    "localhost",
    9042,
    "timeseries",
    "sensor_data"
);

store.put("sensor_01", timeSeriesObject);

// Task 6: Compare performance
// Benchmark write throughput for each backend
```

**Validation Checkpoint**:
- [ ] Can write time series to Kudu
- [ ] Can query time series from Kudu
- [ ] Can write metrics to OpenTSDB
- [ ] Can query metrics from OpenTSDB
- [ ] Can use Cassandra state store
- [ ] Understand trade-offs between backends

**Key Concepts**:
- OLAP (Kudu) vs. OLTP (Cassandra) vs. Metrics (OpenTSDB)
- Write amplification and compaction
- Query patterns and indexing
- Data retention and TTL strategies

**Next Steps**: Episode 8 - Advanced Streaming Patterns

---

### 🚀 Advanced Phase (Episodes 8-10)

#### Episode 8: Advanced Streaming Patterns
**Duration**: 120 minutes
**Focus**: Complex event processing, pattern detection, stateful processing

**Theory (20 min)**:
- Complex Event Processing (CEP)
- Pattern detection in streams
- Stateful stream processing
- Late-arriving data and watermarks

**Demo Scripts**:
- `opentsx-kstreams-cassandra-state-store/StateStoreExample3.java`
- `opentsx-ksql-udf/EpisodesProcessor.java`

**Hands-On Exercise** (90 min):
```java
// Exercise: Implement advanced streaming patterns

// Task 1: Pattern detection - find sequences
Pattern<Event, ?> pattern = Pattern.<Event>begin("start")
    .where(evt -> evt.getValue() > threshold)
    .next("middle")
    .where(evt -> evt.getValue() < threshold)
    .times(3)
    .next("end")
    .where(evt -> evt.getValue() > threshold);

// Task 2: Stateful processing with custom processor
class TimeSeriesAggregator implements Processor<String, TimeSeriesObject> {
    private KeyValueStore<String, AggregateState> stateStore;

    @Override
    public void process(String key, TimeSeriesObject value) {
        AggregateState current = stateStore.get(key);
        if (current == null) {
            current = new AggregateState();
        }

        current.update(value);
        stateStore.put(key, current);

        if (current.shouldEmit()) {
            context().forward(key, current.getResult());
        }
    }
}

// Task 3: Handle late-arriving data
KStream<String, TimeSeriesObject> withWatermarks = input
    .transform(() -> new WatermarkTransformer<>())
    .filter((key, value) -> !value.isLate());

// Task 4: Session windows for burst detection
KTable<Windowed<String>, Long> sessions = input
    .groupByKey()
    .windowedBy(SessionWindows.with(Duration.ofMinutes(5)))
    .count();

// Task 5: Join streams with time-based constraints
KStream<String, Combined> joined = stream1.join(
    stream2,
    (ts1, ts2) -> combine(ts1, ts2),
    JoinWindows.of(Duration.ofSeconds(10))
);

// Task 6: Exactly-once processing
Properties props = new Properties();
props.put(StreamsConfig.PROCESSING_GUARANTEE_CONFIG,
          StreamsConfig.EXACTLY_ONCE_V2);
```

**Validation Checkpoint**:
- [ ] Can detect patterns in time series streams
- [ ] Can implement stateful processors
- [ ] Can handle late-arriving data
- [ ] Can use session windows
- [ ] Can join multiple time series streams
- [ ] Can configure exactly-once semantics

**Key Concepts**:
- Event time vs. processing time
- Watermarks and allowed lateness
- Stateful processing memory management
- Join semantics and window alignment

**Next Steps**: Episode 9 - Machine Learning Integration

---

#### Episode 9: Time Series Analysis & ML
**Duration**: 120 minutes
**Focus**: Apply statistical methods and ML algorithms

**Theory (20 min)**:
- Time series decomposition
- Trend, seasonality, residuals
- Autocorrelation and stationarity
- Forecasting fundamentals
- Anomaly detection approaches

**Demo Scripts**:
- Create: `demo/TimeSeriesAnalysis.java` (to be created)
- Create: `demo/AnomalyDetection.java` (to be created)

**Hands-On Exercise** (90 min):
```java
// Exercise: Apply analytics and ML

// Task 1: Time series decomposition
TimeSeriesObject ts = loadSensorData();
Decomposition decomp = ts.decompose(
    DecompositionMethod.STL,
    seasonalPeriod
);

TimeSeriesObject trend = decomp.getTrend();
TimeSeriesObject seasonal = decomp.getSeasonal();
TimeSeriesObject residual = decomp.getResidual();

// Task 2: Autocorrelation analysis
double[] acf = ts.autocorrelation(maxLag);
double[] pacf = ts.partialAutocorrelation(maxLag);

// Task 3: Stationarity test
ADFTest adfTest = new ADFTest(ts);
boolean isStationary = adfTest.isStationary();

// Task 4: Simple forecasting (moving average)
TimeSeriesObject forecast = ts.simpleMovingAverage(window)
    .forecast(horizonSteps);

// Task 5: Anomaly detection (statistical)
AnomalyDetector detector = new AnomalyDetector.Builder()
    .method(AnomalyMethod.Z_SCORE)
    .threshold(3.0)
    .build();

List<Anomaly> anomalies = detector.detect(ts);

// Task 6: Anomaly detection (ML-based)
IsolationForest isolationForest = new IsolationForest(
    numTrees,
    sampleSize
);
isolationForest.fit(ts);
List<Anomaly> mlAnomalies = isolationForest.predict(ts);

// Task 7: Feature engineering
FeatureExtractor extractor = new FeatureExtractor();
Features features = extractor.extract(ts)
    .addRollingStatistics(window)
    .addLagFeatures(lags)
    .addSeasonalFeatures(period)
    .build();
```

**Validation Checkpoint**:
- [ ] Can decompose time series
- [ ] Can calculate autocorrelation
- [ ] Can test for stationarity
- [ ] Can generate simple forecasts
- [ ] Can detect anomalies using statistical methods
- [ ] Can detect anomalies using ML

**Key Concepts**:
- Decomposition methods (classical, STL)
- ACF/PACF interpretation
- Statistical vs. ML-based detection
- Feature engineering for time series

**Next Steps**: Episode 10 - Production Deployment

---

#### Episode 10: Production Deployment & Best Practices
**Duration**: 120 minutes
**Focus**: Deploy, monitor, and maintain time series applications

**Theory (20 min)**:
- Production architecture patterns
- Monitoring and alerting
- Performance optimization
- Testing time series pipelines
- Disaster recovery

**Demo Scripts**:
- `bin/020_deploy_to_cc_cluster.sh`
- Create: `demo/ProductionConfig.java` (to be created)

**Hands-On Exercise** (90 min):
```java
// Exercise: Production-ready deployment

// Task 1: Configuration management
public class TimeSeriesConfig {
    @Value("${kafka.bootstrap.servers}")
    private String kafkaServers;

    @Value("${kudu.master.addresses}")
    private String kuduMasters;

    @Value("${processing.parallelism}")
    private int parallelism;

    @Value("${checkpoint.interval.ms}")
    private long checkpointInterval;
}

// Task 2: Health checks and metrics
public class TimeSeriesHealthCheck extends HealthCheck {
    @Override
    protected Result check() {
        // Check Kafka connectivity
        // Check Kudu connectivity
        // Check stream processing lag
        return Result.healthy();
    }
}

// Task 3: Error handling and recovery
KStream<String, TimeSeriesObject> resilientStream = input
    .mapValues(ts -> {
        try {
            return processTimeSeries(ts);
        } catch (Exception e) {
            logger.error("Processing error", e);
            errorTopic.send(key, ts, e);
            return null;
        }
    })
    .filter((k, v) -> v != null);

// Task 4: Testing time series pipelines
@Test
public void testTimeSeriesAggregation() {
    TopologyTestDriver testDriver = new TopologyTestDriver(
        topology,
        config
    );

    TestInputTopic<String, TimeSeriesObject> inputTopic =
        testDriver.createInputTopic("input", stringSerde, tsSerde);

    TestOutputTopic<String, Double> outputTopic =
        testDriver.createOutputTopic("output", stringSerde, doubleSerde);

    inputTopic.pipeInput("key1", createTestTimeSeries());

    KeyValue<String, Double> result = outputTopic.readKeyValue();
    assertEquals(expectedMean, result.value, 0.01);
}

// Task 5: Performance monitoring
MeterRegistry registry = new SimpleMeterRegistry();

Counter processedRecords = registry.counter("ts.processed.records");
Timer processingTime = registry.timer("ts.processing.time");
Gauge.builder("ts.lag", lag::get)
    .register(registry);

// Task 6: Deployment configuration
apiVersion: apps/v1
kind: Deployment
metadata:
  name: timeseries-processor
spec:
  replicas: 3
  template:
    spec:
      containers:
      - name: processor
        image: opentsx/processor:latest
        resources:
          requests:
            memory: "2Gi"
            cpu: "1000m"
          limits:
            memory: "4Gi"
            cpu: "2000m"
        env:
        - name: KAFKA_BOOTSTRAP_SERVERS
          value: "kafka:9092"
```

**Validation Checkpoint**:
- [ ] Can configure applications for production
- [ ] Can implement health checks
- [ ] Can handle errors gracefully
- [ ] Can write tests for time series pipelines
- [ ] Can monitor application metrics
- [ ] Can deploy to Kubernetes/Docker

**Key Concepts**:
- Configuration externalization
- Observability (metrics, logs, traces)
- Graceful degradation
- Testing strategies for streams
- Resource management
- Deployment patterns

**Track Completion**: Congratulations! 🎉

---

## Appendix

### A. Sample Data Sets
1. **sensor_data.csv** - IoT sensor readings
2. **stock_prices.tsv** - Financial time series
3. **network_metrics.json** - Infrastructure monitoring
4. **weather_data.csv** - Environmental measurements

### B. Common Patterns Cheat Sheet
```java
// Pattern 1: Load and process
TimeSeriesObject ts = TimeSeriesObject.readFromFile("data.tsv");
TimeSeriesObject normalized = ts.normalize();

// Pattern 2: Stream transformation
KStream<String, TimeSeriesObject> stream = builder.stream("input");
stream.mapValues(ts -> ts.multiply(2.0)).to("output");

// Pattern 3: Windowed aggregation
stream.groupByKey()
    .windowedBy(TimeWindows.of(Duration.ofMinutes(5)))
    .aggregate(/* ... */);

// Pattern 4: Write to storage
kuduConnector.write(table, timeSeriesObject);
```

### C. Troubleshooting Guide
| Issue | Solution |
|-------|----------|
| OutOfMemory | Increase heap size, reduce batch size |
| High lag | Increase parallelism, optimize processing |
| Data loss | Enable exactly-once, check checkpointing |
| Slow queries | Add indexes, optimize partitioning |

### D. Next Steps After Completion
1. Review OpenTSx source code
2. Contribute to OpenTSx project
3. Build your own time series application
4. Explore advanced topics (custom operators, connectors)
5. Join the community (Slack, mailing list)

---

**Track Version**: 1.0
**Last Updated**: 2025-12-20
**Estimated Completion Time**: 15-20 hours
**Difficulty**: Intermediate to Advanced
