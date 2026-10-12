# OpenTSx Onboarding Path: Apache Flink Stream Processing Track

## Track Overview

**Target Audience**: Software engineers and data engineers with experience in stream processing or real-time analytics who want to leverage Apache Flink for large-scale time series analysis with OpenTSx.

**Duration**: 12-15 hours (8 episodes × 1.5-2 hours each)

**Prerequisites**:
- Java 8+ proficiency
- Basic understanding of stream processing concepts
- Familiarity with Apache Kafka (recommended)
- Basic time series analysis concepts
- Docker basics for local development

**Learning Outcomes**: By completing this track, you will:
1. Understand Flink-based time series stream processing
2. Build real-time time series analysis pipelines with Flink
3. Leverage OpenTSx algorithms in Flink DataStream applications
4. Deploy stateful time series applications with exactly-once semantics
5. Scale time series processing to enterprise workloads

---

## Episode Guide

### 🎯 Foundation Phase (Episodes 1-2)

#### Episode 1: Flink + OpenTSx Architecture & Setup
**Duration**: 90 minutes
**Focus**: Understand the Flink integration and set up your development environment

**Theory (20 min)**:
- Why Flink for Time Series? (vs. Kafka Streams, Spark Streaming)
- OpenTSx Flink architecture overview
- Core components: Sources, Serialization, Aggregation, Analysis
- Event-time vs. processing-time for time series

**Architecture Diagram**:
```
Kafka (Observations)
   ↓
ObservationSchema (Avro Deserialization)
   ↓
Watermarks (Event-Time Processing)
   ↓
Key By Label
   ↓
Windowed Aggregation (TimeSeriesObject)
   ↓
OpenTSx Analysis (DFA, Statistics, etc.)
   ↓
Sinks (Kafka, Files, Databases)
```

**Environment Setup** (40 min):
```bash
# 1. Build OpenTSx with Flink module
cd /path/to/OpenTSx
mvn clean install -DskipTests

# 2. Start The Lab Environment
# This brings up Kafka, Zookeeper, Flink, and more
docker-compose -f docker-compose.onboarding.yml up -d

# 3. Verify Flink module build
ls -l opentsx-flink-core/target/opentsx-flink-core-3.0.0.jar

# 4. Verify Flink UI
open http://localhost:8082
```

**Hands-On Exercise** (30 min):
- Review the `opentsx-flink-core` module structure
- Examine `TimeSeriesAnalysisJob.java` example
- Understand the three key components:
  1. `ObservationSchema` - How data enters Flink
  2. `TimeSeriesAggregateFunction` - How observations become time series
  3. `TimeSeriesObjectSerializer` - How state is managed

**Key Takeaways**:
- Flink provides exactly-once semantics for time series processing
- Event-time processing ensures correct results for out-of-order data
- OpenTSx algorithms integrate seamlessly with Flink operators

---

#### Episode 2: Your First Flink Time Series Job
**Duration**: 2 hours
**Focus**: Build and run a complete time series analysis job with Flink

**Theory (15 min)**:
- Flink DataStream API basics
- Windowing strategies for time series (tumbling, sliding, session)
- Watermarks and late data handling
- State backends (Memory vs. RocksDB)

**Reading the Example** (30 min):

Examine `TimeSeriesAnalysisJob.java`:

```java
// 1. Create Flink environment
StreamExecutionEnvironment env =
    StreamExecutionEnvironment.getExecutionEnvironment();
env.enableCheckpointing(60000); // Exactly-once semantics

// 2. Configure Kafka source
KafkaSource<Observation> source = KafkaSource.<Observation>builder()
    .setBootstrapServers("localhost:9092")
    .setTopics("observations")
    .setGroupId("opentsx-timeseries-analysis")
    .setStartingOffsets(OffsetsInitializer.earliest())
    .setDeserializer(new ObservationSchema())
    .build();

// 3. Create data stream with watermarks
DataStream<Observation> observations = env
    .fromSource(source, watermarkStrategy, "Observations");

// 4. Aggregate into time series windows
DataStream<TimeSeriesObject> timeSeries = observations
    .keyBy(obs -> obs.getLabel())
    .window(TumblingEventTimeWindows.of(Time.minutes(5)))
    .aggregate(new TimeSeriesAggregateFunction())
    .returns(new TimeSeriesObjectTypeInfo());

// 5. Apply OpenTSx analysis
DataStream<TimeSeriesObject> analyzed = timeSeries
    .map(ts -> {
        ts.normalize_zScore();
        ts.calcAverage();
        return ts;
    });
```

**Hands-On Exercise** (60 min):

**Task 1: Generate Sample Data**
```bash
# Create a simple observation producer
cd opentsx-data
# Review Observation.avsc Avro schema
cat src/main/resources/avro/Observation.avsc

# Use the provided data generator (or create observations manually)
# This sends observations to Kafka topic "observations"
```

**Task 2: Run the Example Job**
```bash
# Submit job to local Flink cluster
flink run \
  -c org.opentsx.flink.examples.TimeSeriesAnalysisJob \
  opentsx-flink-core/target/opentsx-flink-core-3.0.0.jar

# Monitor in Flink UI
open http://localhost:8081
```

**Task 3: Observe the Output**
- Watch the Flink UI for job metrics
- Check the console output for time series statistics
- Understand the flow: Observation → Aggregation → Analysis

**Verification**:
- ✅ Job running in Flink UI
- ✅ Observations being consumed from Kafka
- ✅ Time series windows being created
- ✅ Statistics printed to console

**Key Takeaways**:
- Flink jobs process continuous streams of observations
- Windowing converts observation streams to time series objects
- OpenTSx methods work directly on aggregated time series

---

### 🔧 Core Concepts Phase (Episodes 3-5)

#### Episode 3: Custom Serialization and State Management
**Duration**: 90 minutes
**Focus**: Understand how OpenTSx objects are serialized in Flink

**Theory (20 min)**:
- Why custom serialization matters for performance
- Flink TypeInformation system
- State backends: Memory, RocksDB, Heap
- Checkpointing and savepoints

**Deep Dive: Serialization** (30 min):

**TimeSeriesObjectSerializer Structure**:
```
[label (UTF-8 string)]
[label (UTF-8 string)]
[xValues.size (int)]
[xValues data (size × double)]
[yValues.size (int)]
[yValues data (size × double)]
[metadata (int)]
```

**Why This Matters**:
- ~16 bytes per data point (2 doubles)
- Supports time series with millions of points
- Works with RocksDB for state larger than memory
- Efficient for checkpointing

**Hands-On Exercise** (40 min):

**Task 1: Review the Serializer**
```bash
# Open and study the serializer
cat opentsx-flink-core/src/main/java/org/opentsx/flink/serdes/TimeSeriesObjectSerializer.java

# Key methods:
# - serialize(): Writes TimeSeriesObject to binary
# - deserialize(): Reads from binary
# - copy(): Creates deep copy for state operations
```

**Task 2: Run Serialization Tests**
```bash
cd opentsx-flink-core
mvn test -Dtest=TimeSeriesObjectSerializerTest

# Study the test cases:
# - Simple time series
# - Empty time series
# - Large time series (10,000 points)
# - Null handling
```

**Task 3: Configure State Backend**

Modify your job to use RocksDB for large state:
```java
// Add to your job setup
import org.apache.flink.contrib.streaming.state.EmbeddedRocksDBStateBackend;

env.setStateBackend(new EmbeddedRocksDBStateBackend());
env.getCheckpointConfig()
   .setCheckpointStorage("file:///tmp/flink-checkpoints");
```

**Key Takeaways**:
- Custom serialization enables efficient state management
- RocksDB allows state larger than available memory
- Checkpointing provides fault tolerance for stateful operations

---

#### Episode 4: Windowing Strategies for Time Series
**Duration**: 2 hours
**Focus**: Master different windowing approaches for time series aggregation

**Theory (25 min)**:
- Tumbling windows: Non-overlapping fixed intervals
- Sliding windows: Overlapping intervals for moving statistics
- Session windows: Activity-based grouping
- Global windows: Custom trigger logic
- Window triggers and evictors

**Windowing Patterns**:

**Pattern 1: Tumbling Windows (Standard Intervals)**
```java
// Aggregate observations every 5 minutes
observations
    .keyBy(Observation::getLabel)
    .window(TumblingEventTimeWindows.of(Time.minutes(5)))
    .aggregate(new TimeSeriesAggregateFunction());
```
**Use Case**: Regular interval reporting, hourly/daily aggregations

**Pattern 2: Sliding Windows (Moving Analysis)**
```java
// 10-minute windows, sliding every 1 minute
observations
    .keyBy(Observation::getLabel)
    .window(SlidingEventTimeWindows.of(
        Time.minutes(10),
        Time.minutes(1)))
    .aggregate(new TimeSeriesAggregateFunction());
```
**Use Case**: Moving averages, rolling statistics, trend detection

**Pattern 3: Session Windows (Event-Driven)**
```java
// Group observations with max 30-second gap
observations
    .keyBy(Observation::getLabel)
    .window(EventTimeSessionWindows.withGap(Time.seconds(30)))
    .aggregate(new TimeSeriesAggregateFunction());
```
**Use Case**: User sessions, burst detection, irregular sampling

**Hands-On Exercise** (80 min):

**Task 1: Implement Sliding Window Job**
Create a new job that calculates rolling statistics:

```java
public class SlidingWindowAnalysisJob {
    public static void main(String[] args) throws Exception {
        StreamExecutionEnvironment env =
            StreamExecutionEnvironment.getExecutionEnvironment();

        // TODO: Set up Kafka source (from Episode 2)

        // TODO: Create sliding window (10 min, slide 1 min)
        DataStream<TimeSeriesObject> rollingTimeSeries = observations
            .keyBy(Observation::getLabel)
            .window(SlidingEventTimeWindows.of(
                Time.minutes(10),
                Time.minutes(1)))
            .aggregate(new TimeSeriesAggregateFunction())
            .returns(new TimeSeriesObjectTypeInfo());

        // TODO: Calculate rolling statistics
        rollingTimeSeries.map(ts -> {
            ts.calcAverage();
            ts.calcStddev();
            return String.format(
                "Window[%s]: Mean=%.2f, StdDev=%.2f, Points=%d",
                ts.getLabel(),
                ts.getAvarage(),
                ts.getStddev(),
                ts.yValues.size()
            );
        }).print();

        env.execute("Sliding Window Analysis");
    }
}
```

**Task 2: Test with Real Data**
- Generate observations with varying patterns
- Observe how sliding windows overlap
- Compare with tumbling window output

**Task 3: Session Window Experiment**
- Implement session-based aggregation
- Send observations in bursts with gaps
- Observe session boundary detection

**Verification**:
- ✅ Multiple window types working correctly
- ✅ Understanding window overlap behavior
- ✅ Correct handling of late data

**Key Takeaways**:
- Window choice depends on analysis requirements
- Sliding windows enable moving statistics
- Session windows handle irregular data patterns

---

#### Episode 5: Advanced Time Series Analysis with OpenTSx Algorithms
**Duration**: 2 hours
**Focus**: Apply DFA, statistics, and custom algorithms in Flink streams

**Theory (20 min)**:
- OpenTSx algorithm categories: DFA, MFDFA, RIS, Event Sync
- Computational complexity considerations in streaming
- When to use ProcessFunction vs. MapFunction
- Managing algorithm state

**Available OpenTSx Algorithms in Flink**:

| Algorithm | Complexity | Streaming Suitability | Use Case |
|-----------|------------|----------------------|----------|
| Z-Score Normalization | O(n) | ✅ Excellent | Preprocessing |
| Basic Statistics | O(n) | ✅ Excellent | Descriptive analysis |
| DFA | O(n²) | ⚠️ Use on windows | Correlation analysis |
| MFDFA | O(n² log n) | ⚠️ Use on windows | Multifractal analysis |
| Peak Detection | O(n) | ✅ Excellent | Event detection |
| Resampling | O(n) | ✅ Excellent | Data alignment |

**Hands-On Exercise** (90 min):

**Task 1: Implement DFA Analysis Pipeline**

```java
import org.opentsx.algorithms.detrending.methods.DFA;

public class DFAAnalysisJob {
    public static void main(String[] args) throws Exception {
        // ... setup environment and source ...

        // Aggregate observations into hourly time series
        DataStream<TimeSeriesObject> hourlyTimeSeries = observations
            .keyBy(Observation::getLabel)
            .window(TumblingEventTimeWindows.of(Time.hours(1)))
            .aggregate(new TimeSeriesAggregateFunction())
            .returns(new TimeSeriesObjectTypeInfo());

        // Apply DFA analysis
        DataStream<DFAResult> dfaResults = hourlyTimeSeries
            .map(ts -> {
                // Only analyze if enough data points
                if (ts.yValues.size() < 100) {
                    return null;
                }

                DFA dfa = new DFA();
                dfa.setTimeSeries(ts);

                DFAParameter params = new DFAParameter();
                params.setMinBox(10);
                params.setMaxBox(ts.yValues.size() / 4);
                params.setDegree(1);

                TimeSeriesObject fluctuation = dfa.execute(params);
                double alpha = dfa.getScalingExponent();

                return new DFAResult(
                    ts.getLabel(),
                    alpha,
                    interpretAlpha(alpha)
                );
            })
            .filter(result -> result != null);

        dfaResults.print();
        env.execute("DFA Analysis Job");
    }

    private static String interpretAlpha(double alpha) {
        if (alpha < 0.5) return "Anti-correlated";
        if (alpha > 0.5 && alpha < 0.9) return "Persistent";
        if (alpha > 0.9) return "Strong trend";
        return "Random walk";
    }
}

// Result class
class DFAResult {
    String label;
    double alpha;
    String interpretation;

    // constructor, getters, toString
}
```

**Task 2: Multi-Algorithm Pipeline**

Build a pipeline that applies multiple analyses:

```java
DataStream<AnalysisResults> multiAnalysis = timeSeries
    .map(new RichMapFunction<TimeSeriesObject, AnalysisResults>() {
        @Override
        public AnalysisResults map(TimeSeriesObject ts) throws Exception {
            AnalysisResults results = new AnalysisResults(ts.getLabel());

            // 1. Basic statistics
            ts.calcAverage();
            ts.calcStddev();
            results.setMean(ts.getAvarage());
            results.setStdDev(ts.getStddev());

            // 2. Normalize
            TimeSeriesObject normalized = ts.copy();
            normalized.normalize_zScore();

            // 3. Detect peaks
            // (implement using OpenTSx peak detection)

            // 4. Calculate entropy
            // (use OpenTSx entropy methods)

            return results;
        }
    });
```

**Task 3: Custom ProcessFunction for Stateful Analysis**

Implement a ProcessFunction that maintains running statistics:

```java
public class RunningStatsProcessFunction
    extends KeyedProcessFunction<String, TimeSeriesObject, StatsUpdate> {

    private transient ValueState<RunningStats> statsState;

    @Override
    public void open(Configuration parameters) {
        ValueStateDescriptor<RunningStats> descriptor =
            new ValueStateDescriptor<>("running-stats", RunningStats.class);
        statsState = getRuntimeContext().getState(descriptor);
    }

    @Override
    public void processElement(
            TimeSeriesObject ts,
            Context ctx,
            Collector<StatsUpdate> out) throws Exception {

        RunningStats current = statsState.value();
        if (current == null) {
            current = new RunningStats();
        }

        // Update running statistics
        current.update(ts);
        statsState.update(current);

        // Emit update
        out.collect(new StatsUpdate(ts.getLabel(), current));
    }
}
```

**Verification**:
- ✅ DFA analysis producing scaling exponents
- ✅ Multiple algorithms working in pipeline
- ✅ Stateful processing maintaining history

**Key Takeaways**:
- OpenTSx algorithms integrate seamlessly with Flink
- Window size affects algorithm accuracy
- ProcessFunction enables stateful custom logic

---

### 🚀 Production Phase (Episodes 6-8)

#### Episode 6: Kafka Integration and Data Pipelines
**Duration**: 90 minutes
**Focus**: Build production-ready Kafka sources and sinks

**Theory (15 min)**:
- Kafka connector architecture
- Avro schema registry integration
- Exactly-once Kafka semantics
- Consumer group management

**Hands-On Exercise** (60 min):

**Task 1: Configure Avro Schema Registry**

```bash
# Start Schema Registry
docker-compose up -d schema-registry

# Register Observation schema
cd opentsx-data/src/main/resources/avro
curl -X POST -H "Content-Type: application/vnd.schemaregistry.v1+json" \
  --data @observation-schema.json \
  http://localhost:8081/subjects/observations-value/versions
```

**Task 2: Implement Kafka Sink**

```java
import org.apache.flink.connector.kafka.sink.KafkaSink;
import org.apache.flink.connector.kafka.sink.KafkaRecordSerializationSchema;

// Create Kafka sink for time series results
KafkaSink<TimeSeriesObject> sink = KafkaSink.<TimeSeriesObject>builder()
    .setBootstrapServers("localhost:9092")
    .setRecordSerializer(
        KafkaRecordSerializationSchema.builder()
            .setTopic("timeseries-results")
            .setValueSerializationSchema(new TimeSeriesObjectSerializer())
            .build()
    )
    .setDeliveryGuarantee(DeliveryGuarantee.EXACTLY_ONCE)
    .build();

timeSeries.sinkTo(sink);
```

**Task 3: End-to-End Pipeline**

Build a complete Kafka → Flink → Kafka pipeline:
- Input: `observations` topic (Avro Observation)
- Processing: Windowing + DFA analysis
- Output: `analysis-results` topic (Avro results)

**Key Takeaways**:
- Schema registry ensures compatibility
- Exactly-once delivery prevents duplicates
- Kafka enables decoupled architecture

---

#### Episode 7: Deployment and Scaling
**Duration**: 2 hours
**Focus**: Deploy Flink jobs to production clusters

**Theory (20 min)**:
- Flink deployment modes: Standalone, YARN, Kubernetes
- Resource configuration: Task managers, slots, parallelism
- Checkpointing strategies
- Monitoring and alerting

**Hands-On Exercise** (90 min):

**Task 1: Configure for Production**

```java
StreamExecutionEnvironment env =
    StreamExecutionEnvironment.getExecutionEnvironment();

// Checkpointing
env.enableCheckpointing(60000); // 1 minute
env.getCheckpointConfig()
   .setCheckpointingMode(CheckpointingMode.EXACTLY_ONCE);
env.getCheckpointConfig()
   .setMinPauseBetweenCheckpoints(30000);
env.getCheckpointConfig()
   .setCheckpointTimeout(300000);
env.getCheckpointConfig()
   .setMaxConcurrentCheckpoints(1);

// State backend
env.setStateBackend(new EmbeddedRocksDBStateBackend());
env.getCheckpointConfig()
   .setCheckpointStorage("hdfs:///flink/checkpoints");

// Parallelism
env.setParallelism(4);
```

**Task 2: Build Deployment Package**

```bash
cd opentsx-flink-core
mvn clean package

# Creates fat JAR with all dependencies
ls -lh target/opentsx-flink-core-3.0.0.jar
```

**Task 3: Deploy to Standalone Cluster**

```bash
# Submit to remote Flink cluster
flink run \
  -m flink-master:8081 \
  -p 4 \
  -c org.opentsx.flink.examples.TimeSeriesAnalysisJob \
  opentsx-flink-core-3.0.0.jar \
  --kafka-brokers kafka:9092 \
  --input-topic observations \
  --window-size 300000
```

**Task 4: Monitor and Scale**

- Access Flink UI metrics
- Adjust parallelism based on throughput
- Configure autoscaling (Kubernetes)
- Set up alerting for backpressure

**Verification**:
- ✅ Job running on cluster
- ✅ Checkpoints completing successfully
- ✅ Metrics showing in dashboard
- ✅ Handling expected throughput

**Key Takeaways**:
- Production configuration is critical
- RocksDB enables large state
- Monitoring prevents issues

---

#### Episode 8: Performance Optimization and Best Practices
**Duration**: 90 minutes
**Focus**: Optimize Flink jobs for maximum performance

**Theory (20 min)**:
- Performance bottlenecks in stream processing
- Operator chaining and task scheduling
- Network buffer tuning
- State size optimization

**Optimization Techniques**:

**1. Operator Chaining**
```java
// Good: Chained operators (single thread)
timeSeries
    .map(ts -> ts.normalize_zScore())
    .map(ts -> { ts.calcAverage(); return ts; })
    .print();

// Sometimes needed: Break chain for parallelism
timeSeries
    .map(ts -> ts.normalize_zScore())
    .disableChaining()
    .map(ts -> { ts.calcAverage(); return ts; })
    .setParallelism(8);
```

**2. State TTL for Cleanup**
```java
StateTtlConfig ttlConfig = StateTtlConfig
    .newBuilder(Time.hours(24))
    .setUpdateType(StateTtlConfig.UpdateType.OnCreateAndWrite)
    .build();

ValueStateDescriptor<TimeSeriesObject> descriptor =
    new ValueStateDescriptor<>("ts-state", TimeSeriesObject.class);
descriptor.enableTimeToLive(ttlConfig);
```

**3. Async I/O for External Lookups**
```java
AsyncDataStream.unorderedWait(
    timeSeries,
    new AsyncDatabaseLookupFunction(),
    5000,
    TimeUnit.MILLISECONDS,
    100
);
```

**Hands-On Exercise** (60 min):

**Task 1: Benchmark Your Job**

Create a load test:
```bash
# Generate high-volume test data
# Measure: throughput, latency, backpressure

# Collect metrics
# - Records/sec processed
# - End-to-end latency
# - Checkpoint duration
# - State size growth
```

**Task 2: Apply Optimizations**

- Tune parallelism for bottlenecks
- Configure network buffers
- Optimize serialization
- Add operator chaining hints

**Task 3: Compare Results**

Document performance improvements:
- Before: X records/sec, Y ms latency
- After: X records/sec, Y ms latency

**Key Takeaways**:
- Measure before optimizing
- Parallelism != performance
- State management is critical

---

## Capstone Project

**Duration**: 4-6 hours
**Project**: Build a Real-Time Anomaly Detection System

**Requirements**:
1. Consume sensor observations from Kafka
2. Aggregate into 5-minute windows
3. Calculate rolling statistics (30-minute sliding window)
4. Apply DFA for correlation analysis
5. Detect anomalies using statistical thresholds
6. Emit alerts to output Kafka topic
7. Deploy to Flink cluster
8. Demonstrate exactly-once processing

**Deliverables**:
- ✅ Working Flink job (source code)
- ✅ Docker Compose setup for local testing
- ✅ README with architecture and deployment
- ✅ Performance benchmarks
- ✅ Monitoring dashboard (optional)

**Success Criteria**:
- Process 10,000 observations/second
- End-to-end latency < 1 second
- Exactly-once delivery verified
- Handles late data correctly
- Recovers from failures via checkpoints

---

## Additional Resources

### Documentation
- [OpenTSx Manual - Core Concepts](../../docs/manual/core-concepts/)
- [TASK-003: Flink Integration Plan](../../EVOLUTION/TASK-003/)
- [Apache Flink Documentation](https://flink.apache.org/docs/)
- [opentsx-flink-core README](../../opentsx-flink-core/README.md)

### Example Code
- `opentsx-flink-core/src/main/java/org/opentsx/flink/examples/`
- `opentsx-flink-core/src/test/java/org/opentsx/flink/`

### Community
- OpenTSx GitHub Issues
- Apache Flink Mailing Lists
- Stack Overflow: `[apache-flink] [time-series]`

---

## Learning Path Completion

After completing this track, you should be able to:

✅ Build production-ready Flink jobs for time series analysis
✅ Integrate OpenTSx algorithms with Flink DataStream API
✅ Configure exactly-once processing with Kafka
✅ Deploy and scale Flink applications
✅ Optimize performance for enterprise workloads
✅ Monitor and troubleshoot streaming jobs

**Next Steps**:
- Explore advanced CEP (Complex Event Processing)
- Integrate with Flink Table API/SQL
- Implement custom time series operators
- Contribute to OpenTSx Flink integration

**Estimated Total Time**: 12-15 hours + 4-6 hours capstone = **16-21 hours**

---

**Track Version**: 1.0
**Last Updated**: 2025-12-20
**Compatible with**: OpenTSx 3.0.0, Flink 1.18.0
