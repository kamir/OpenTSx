# TASK-003: Apache Flink Integration

## Task Metadata
- **Task ID**: TASK-003-flink-integration
- **Created**: 2025-12-20
- **Status**: Planning
- **Priority**: High
- **Assigned To**: OpenTSx Core Team
- **Epic**: Stream Processing Modernization
- **Estimated Effort**: 4-6 weeks

## Objective

Provide Apache Flink as a modern alternative to Kafka Streams for real-time time series analysis, enabling OpenTSx to leverage Flink's advanced stream processing capabilities while maintaining API compatibility and feature parity with existing Kafka Streams implementations.

## Business Justification

### Why Apache Flink?

1. **Superior State Management**
   - Exactly-once semantics with chandy-lamport checkpointing
   - RocksDB state backend for large state
   - Incremental checkpointing for efficiency
   - Better fault tolerance guarantees

2. **Advanced Stream Processing**
   - True event-time processing with watermarks
   - Sophisticated windowing (session, sliding, tumbling, custom)
   - Native support for late data handling
   - Complex event processing (CEP) library

3. **Better Performance**
   - Lower latency for complex operations
   - Higher throughput for large-scale processing
   - More efficient resource utilization
   - Better backpressure handling

4. **Richer Ecosystem**
   - Table API and SQL for stream processing
   - Native support for batch + stream (unified processing)
   - Better integration with machine learning (PyFlink, FlinkML)
   - Kubernetes-native deployment

5. **Industry Adoption**
   - Growing enterprise adoption (Alibaba, Uber, Netflix)
   - Active development and community
   - Better documentation and examples
   - Cloud-native features (AWS Kinesis Data Analytics, Azure Stream Analytics)

### Current Limitations with Kafka Streams

- Tightly coupled to Kafka (cannot process from other sources easily)
- Limited windowing capabilities
- State management challenges at scale
- No native batch processing support
- Limited out-of-order event handling

## Current State Analysis

### Existing Kafka Streams Integration

**Module**: `opentsx-kafka-streams-tsa` (currently commented out in pom.xml)

**Key Components**:
```
opentsx-kafka-streams-tsa/
├── src/main/java/org/opentsx/streams/
│   ├── TimeSeriesProcessor.java      # KStreams processor
│   ├── TimeSeriesTransformer.java    # KStreams transformer
│   ├── TimeSeriesAggregator.java     # KStreams aggregator
│   └── serdes/
│       ├── TimeSeriesObjectSerde.java
│       └── ObservationSerde.java
└── pom.xml
```

**Integration Pattern**:
```java
// Current Kafka Streams approach
StreamsBuilder builder = new StreamsBuilder();
KStream<String, Observation> observations = builder.stream("observations");

KStream<String, TimeSeriesObject> timeSeries = observations
    .groupByKey()
    .windowedBy(TimeWindows.of(Duration.ofMinutes(5)))
    .aggregate(
        () -> new TimeSeriesObject(),
        (key, obs, ts) -> {
            ts.addValuePair(obs.getTimestamp(), obs.getValue());
            return ts;
        },
        Materialized.with(Serdes.String(), new TimeSeriesObjectSerde())
    )
    .toStream()
    .map((windowedKey, ts) -> new KeyValue<>(windowedKey.key(), ts));
```

**Challenges**:
- Custom serdes required for complex objects
- Windowing limited to time-based windows
- Difficult to implement complex analytics (DFA, RIS)
- State stores cumbersome for large time series

## Target Architecture

### Flink Integration Modules

#### Module 1: opentsx-flink-core

Core Flink integration providing fundamental stream processing capabilities.

**Structure**:
```
opentsx-flink-core/
├── pom.xml
└── src/main/java/org/opentsx/flink/
    ├── functions/
    │   ├── TimeSeriesMapFunction.java
    │   ├── TimeSeriesFilterFunction.java
    │   ├── TimeSeriesAggregateFunction.java
    │   └── TimeSeriesProcessFunction.java
    ├── sources/
    │   ├── KafkaObservationSource.java
    │   ├── FileObservationSource.java
    │   └── GeneratorObservationSource.java
    ├── sinks/
    │   ├── KafkaTimeSeriesSink.java
    │   ├── HBaseSink.java
    │   └── ParquetSink.java
    ├── serdes/
    │   ├── ObservationSchema.java          # Avro serialization
    │   ├── TimeSeriesObjectSchema.java     # Custom serialization
    │   └── EpisodeSchema.java              # Avro serialization
    ├── state/
    │   ├── TimeSeriesState.java            # Flink state abstraction
    │   └── TimeSeriesStateDescriptor.java
    └── windows/
        ├── ObservationWindowAssigner.java
        └── TimeSeriesWindowFunction.java
```

**Key Classes**:

1. **TimeSeriesAggregateFunction**
```java
public class TimeSeriesAggregateFunction
    implements AggregateFunction<Observation, TimeSeriesObject, TimeSeriesObject> {

    @Override
    public TimeSeriesObject createAccumulator() {
        return new TimeSeriesObject();
    }

    @Override
    public TimeSeriesObject add(Observation obs, TimeSeriesObject accumulator) {
        accumulator.addValuePair(obs.getTimestamp(), obs.getValue());
        return accumulator;
    }

    @Override
    public TimeSeriesObject getResult(TimeSeriesObject accumulator) {
        return accumulator;
    }

    @Override
    public TimeSeriesObject merge(TimeSeriesObject a, TimeSeriesObject b) {
        // Merge two time series
        return TimeSeriesObject.merge(a, b);
    }
}
```

2. **TimeSeriesProcessFunction**
```java
public class TimeSeriesProcessFunction
    extends KeyedProcessFunction<String, TimeSeriesObject, AnalysisResult> {

    private transient ValueState<TimeSeriesObject> state;

    @Override
    public void open(Configuration parameters) {
        ValueStateDescriptor<TimeSeriesObject> descriptor =
            new ValueStateDescriptor<>("timeseries-state", TimeSeriesObject.class);
        state = getRuntimeContext().getState(descriptor);
    }

    @Override
    public void processElement(
        TimeSeriesObject ts,
        Context ctx,
        Collector<AnalysisResult> out
    ) throws Exception {
        // Apply OpenTSx analytics
        DFA dfa = new DFA();
        dfa.setTimeSeries(ts);

        DFAParameter params = new DFAParameter();
        TimeSeriesObject fluctuation = dfa.execute(params);

        AnalysisResult result = new AnalysisResult();
        result.setScalingExponent(dfa.getScalingExponent());
        result.setFluctuation(fluctuation);

        out.collect(result);
    }
}
```

#### Module 2: opentsx-flink-analytics

Advanced analytics operators built on Flink.

**Structure**:
```
opentsx-flink-analytics/
└── src/main/java/org/opentsx/flink/analytics/
    ├── operators/
    │   ├── DFAOperator.java                # DFA analysis
    │   ├── MFDFAOperator.java              # Multi-fractal DFA
    │   ├── RISOperator.java                # Random Interval Sampling
    │   ├── EventSyncOperator.java          # Event synchronisation
    │   └── AnomalyDetectionOperator.java   # Anomaly detection
    ├── windowing/
    │   ├── SlidingTimeSeriesWindow.java
    │   ├── SessionTimeSeriesWindow.java
    │   └── CustomBucketWindow.java
    └── cep/
        ├── TimeSeriesPattern.java          # CEP patterns
        └── AnomalyPatternDetector.java     # Pattern-based anomalies
```

**Example Operators**:

```java
public class DFAOperator extends RichMapFunction<TimeSeriesObject, DFAResult> {

    private final DFAParameter params;

    public DFAOperator(DFAParameter params) {
        this.params = params;
    }

    @Override
    public DFAResult map(TimeSeriesObject ts) throws Exception {
        DFA dfa = new DFA();
        dfa.setTimeSeries(ts);

        TimeSeriesObject fluctuation = dfa.execute(params);

        return new DFAResult(
            ts.getLabel(),
            dfa.getScalingExponent(),
            fluctuation
        );
    }
}
```

#### Module 3: opentsx-flink-connectors

Flink-specific connectors for various data sources/sinks.

**Structure**:
```
opentsx-flink-connectors/
└── src/main/java/org/opentsx/flink/connectors/
    ├── kafka/
    │   ├── FlinkKafkaObservationSource.java
    │   └── FlinkKafkaTimeSeriesSink.java
    ├── hbase/
    │   ├── HBaseObservationSource.java
    │   └── HBaseTimeSeriesSink.java
    ├── opentsdb/
    │   ├── OpenTSDBSource.java
    │   └── OpenTSDBSink.java
    ├── parquet/
    │   ├── ParquetTimeSeriesSource.java
    │   └── ParquetTimeSeriesSink.java
    └── jdbc/
        ├── JDBCObservationSource.java
        └── JDBCTimeSeriesSink.java
```

### Data Flow Architecture

#### Stream Processing Pipeline

```
Kafka Topics                Flink Job                  Output
────────────────          ─────────────────          ────────────

observations              ┌─────────────┐
  (Avro)         ────────>│   Source    │
                          │  (Kafka)    │
                          └──────┬──────┘
                                 │
                                 v
                          ┌─────────────┐
                          │  Keyby      │
                          │  (sensor)   │
                          └──────┬──────┘
                                 │
                                 v
                          ┌─────────────┐
                          │  Window     │
                          │  (5 min)    │
                          └──────┬──────┘
                                 │
                                 v
                          ┌─────────────┐
                          │  Aggregate  │
                          │  (TSObject) │
                          └──────┬──────┘
                                 │
                          ┌──────┴──────┐
                          │             │
                          v             v
                  ┌──────────┐   ┌──────────┐
                  │   DFA    │   │   RIS    │
                  │ Analysis │   │ Analysis │
                  └─────┬────┘   └─────┬────┘
                        │              │
                        v              v
                  ┌──────────┐   ┌──────────┐
                  │  Sink    │   │  Sink    │
                  │ (Kafka)  │   │ (HBase)  │
                  └──────────┘   └──────────┘
```

#### State Management Strategy

```java
public class TimeSeriesStatefulOperator
    extends KeyedProcessFunction<String, Observation, TimeSeriesObject> {

    // Use Flink's state backend (RocksDB for large state)
    private transient MapState<Long, Double> timeSeriesState;
    private transient ValueState<TimeSeriesMetadata> metadataState;

    @Override
    public void open(Configuration config) {
        MapStateDescriptor<Long, Double> tsDescriptor =
            new MapStateDescriptor<>(
                "timeseries-points",
                Long.class,
                Double.class
            );
        timeSeriesState = getRuntimeContext().getMapState(tsDescriptor);

        ValueStateDescriptor<TimeSeriesMetadata> metaDescriptor =
            new ValueStateDescriptor<>(
                "timeseries-metadata",
                TimeSeriesMetadata.class
            );
        metadataState = getRuntimeContext().getState(metaDescriptor);
    }

    @Override
    public void processElement(
        Observation obs,
        Context ctx,
        Collector<TimeSeriesObject> out
    ) throws Exception {
        // Update state
        timeSeriesState.put(obs.getTimestamp(), obs.getValue());

        // Reconstruct TimeSeriesObject from state
        TimeSeriesObject ts = new TimeSeriesObject();
        for (Map.Entry<Long, Double> entry : timeSeriesState.entries()) {
            ts.addValuePair(entry.getKey(), entry.getValue());
        }

        // Emit if window complete
        if (isWindowComplete(ctx)) {
            out.collect(ts);

            // Clear state for new window
            timeSeriesState.clear();
        }
    }
}
```

## Implementation Comparison

### Kafka Streams vs Apache Flink

| Feature | Kafka Streams | Apache Flink | Winner |
|---------|--------------|--------------|--------|
| **Deployment** | Embedded library | Standalone cluster | Both |
| **Scalability** | Partition-level | Task-level + slot-level | Flink |
| **State Size** | Limited (local) | Unlimited (RocksDB) | Flink |
| **Exactly-once** | Yes | Yes | Tie |
| **Windowing** | Time, Session | Time, Session, Custom, Count | Flink |
| **Event Time** | Limited | Full watermark support | Flink |
| **Late Data** | Grace period | Side outputs, allowed lateness | Flink |
| **Batch Processing** | No | Yes (DataSet API) | Flink |
| **SQL Support** | ksqlDB (separate) | Native Table API + SQL | Flink |
| **CEP** | Manual | Native library | Flink |
| **Machine Learning** | External | FlinkML, PyFlink | Flink |
| **Ease of Use** | Simpler | Steeper learning curve | KStreams |
| **Resource Needs** | Lower | Higher | KStreams |
| **Operability** | Simpler ops | Complex ops | KStreams |

**Recommendation**: Use both, allow user choice based on requirements.

### API Design Philosophy

**Goal**: Provide unified API that works with both Kafka Streams and Flink.

**Abstraction Layer**:

```java
// Common interface
public interface TimeSeriesStreamProcessor {

    TimeSeriesStream<Observation> createObservationStream(String source);

    TimeSeriesStream<TimeSeriesObject> aggregate(
        TimeSeriesStream<Observation> stream,
        WindowSpec window
    );

    TimeSeriesStream<AnalysisResult> analyze(
        TimeSeriesStream<TimeSeriesObject> stream,
        AnalysisOperator operator
    );

    void sink(TimeSeriesStream<?> stream, String destination);
}

// Kafka Streams implementation
public class KafkaTimeSeriesStreamProcessor implements TimeSeriesStreamProcessor {
    // Implementation using KStreams API
}

// Flink implementation
public class FlinkTimeSeriesStreamProcessor implements TimeSeriesStreamProcessor {
    // Implementation using Flink DataStream API
}
```

**User Code** (framework-agnostic):

```java
// Works with both Kafka Streams and Flink
TimeSeriesStreamProcessor processor =
    TimeSeriesStreamProcessorFactory.create(config);

TimeSeriesStream<Observation> observations =
    processor.createObservationStream("kafka:observations");

TimeSeriesStream<TimeSeriesObject> timeSeries =
    processor.aggregate(observations, WindowSpec.tumbling(Duration.ofMinutes(5)));

TimeSeriesStream<DFAResult> analysis =
    processor.analyze(timeSeries, new DFAOperator(params));

processor.sink(analysis, "kafka:dfa-results");
```

## Detailed Requirements

### Functional Requirements

#### FR-1: Stream Ingestion
- **FR-1.1**: Read Observation records from Kafka topics
- **FR-1.2**: Support Avro, JSON, and custom serialization
- **FR-1.3**: Handle schema evolution gracefully
- **FR-1.4**: Support multiple Kafka topics simultaneously

#### FR-2: Time Series Aggregation
- **FR-2.1**: Aggregate Observations into TimeSeriesObject
- **FR-2.2**: Support tumbling windows (fixed size)
- **FR-2.3**: Support sliding windows (overlapping)
- **FR-2.4**: Support session windows (gap-based)
- **FR-2.5**: Support custom window assigners

#### FR-3: Statistical Analysis
- **FR-3.1**: Apply DFA analysis to windowed time series
- **FR-3.2**: Apply MFDFA for multifractal analysis
- **FR-3.3**: Calculate descriptive statistics (mean, stddev, etc.)
- **FR-3.4**: Detect anomalies using z-score, moving average, etc.

#### FR-4: Advanced Analytics
- **FR-4.1**: RIS (Random Interval Sampling) for uncertainty
- **FR-4.2**: Event synchronisation between series
- **FR-4.3**: Peak detection and counting
- **FR-4.4**: Trend detection and removal

#### FR-5: Complex Event Processing
- **FR-5.1**: Define patterns over time series streams
- **FR-5.2**: Detect complex anomaly patterns
- **FR-5.3**: Trigger alerts based on CEP patterns

#### FR-6: State Management
- **FR-6.1**: Maintain time series state across windows
- **FR-6.2**: Support incremental updates to time series
- **FR-6.3**: Handle late-arriving data
- **FR-6.4**: Persist state to RocksDB for fault tolerance

#### FR-7: Output and Sinks
- **FR-7.1**: Write results to Kafka topics
- **FR-7.2**: Write to HBase via OpenTSDB
- **FR-7.3**: Write to Parquet for batch analysis
- **FR-7.4**: Write to JDBC databases
- **FR-7.5**: Support custom sinks

### Non-Functional Requirements

#### NFR-1: Performance
- **NFR-1.1**: Process 100K observations/second per task
- **NFR-1.2**: Latency < 1 second for windowed aggregation
- **NFR-1.3**: State access latency < 10ms
- **NFR-1.4**: Checkpoint creation < 5 seconds

#### NFR-2: Scalability
- **NFR-2.1**: Scale horizontally to 100+ parallel tasks
- **NFR-2.2**: Handle state size > 100GB per operator
- **NFR-2.3**: Support millions of unique keys (sensor IDs)

#### NFR-3: Reliability
- **NFR-3.1**: Exactly-once processing semantics
- **NFR-3.2**: Automatic recovery from task failures
- **NFR-3.3**: No data loss during failures
- **NFR-3.4**: Graceful handling of backpressure

#### NFR-4: Operability
- **NFR-4.1**: Comprehensive metrics via Flink metrics system
- **NFR-4.2**: Integration with Prometheus/Grafana
- **NFR-4.3**: Clear error messages and logging
- **NFR-4.4**: Support for Flink's savepoints

#### NFR-5: Compatibility
- **NFR-5.1**: Compatible with Flink 1.18+
- **NFR-5.2**: Java 8+ compatibility
- **NFR-5.3**: Works with existing OpenTSx core library
- **NFR-5.4**: Binary compatibility with Avro schemas

## Implementation Plan

### Phase 1: Foundation (Weeks 1-2)

**Deliverables:**
1. Create `opentsx-flink-core` module
2. Implement basic sources and sinks
3. Create serialization schemas for Avro types
4. Implement basic windowing functions
5. Set up unit test framework

**Tasks:**
- [ ] Create Maven module structure
- [ ] Add Flink dependencies (flink-streaming-java, flink-connector-kafka)
- [ ] Implement `KafkaObservationSource`
- [ ] Implement `KafkaTimeSeriesSink`
- [ ] Create `ObservationSchema` (Avro)
- [ ] Create `TimeSeriesObjectSchema` (custom)
- [ ] Implement `TimeSeriesAggregateFunction`
- [ ] Write unit tests for aggregation
- [ ] Create integration test with embedded Kafka
- [ ] Document API with Javadoc

**Estimated Effort:** 80 hours

### Phase 2: Analytics Integration (Weeks 3-4)

**Deliverables:**
1. Create `opentsx-flink-analytics` module
2. Implement DFA operator
3. Implement statistical analysis operators
4. Implement anomaly detection operators
5. Create comprehensive examples

**Tasks:**
- [ ] Create analytics module structure
- [ ] Implement `DFAOperator` with state management
- [ ] Implement `MFDFAOperator`
- [ ] Implement `StatisticalAnalysisOperator`
- [ ] Implement `AnomalyDetectionOperator`
- [ ] Implement `RISOperator`
- [ ] Create windowing strategies for analytics
- [ ] Write integration tests
- [ ] Create example Flink jobs
- [ ] Performance benchmarking

**Estimated Effort:** 100 hours

### Phase 3: Advanced Features (Weeks 5-6)

**Deliverables:**
1. CEP integration for pattern detection
2. Additional connectors (HBase, Parquet, JDBC)
3. Unified abstraction layer
4. Production deployment guide

**Tasks:**
- [ ] Implement CEP patterns for time series
- [ ] Create `HBaseTimeSeriesSink`
- [ ] Create `ParquetTimeSeriesSink`
- [ ] Design unified abstraction API
- [ ] Implement factory pattern for processor selection
- [ ] Create Kubernetes deployment manifests
- [ ] Write deployment guide
- [ ] Create monitoring dashboards
- [ ] Write user documentation
- [ ] Create migration guide from Kafka Streams

**Estimated Effort:** 120 hours

**Total Estimated Effort:** 300 hours (~6 weeks with 2 developers)

## Technical Design Details

### Serialization Strategy

**Challenge**: TimeSeriesObject uses Vectors, not easily serializable.

**Solution**: Custom TypeInformation and Serializer

```java
public class TimeSeriesObjectTypeInfo extends TypeInformation<TimeSeriesObject> {

    @Override
    public TypeSerializer<TimeSeriesObject> createSerializer(ExecutionConfig config) {
        return new TimeSeriesObjectSerializer();
    }

    @Override
    public boolean isBasicType() {
        return false;
    }

    @Override
    public boolean isTupleType() {
        return false;
    }

    @Override
    public int getArity() {
        return 1;
    }

    @Override
    public Class<TimeSeriesObject> getTypeClass() {
        return TimeSeriesObject.class;
    }
}

public class TimeSeriesObjectSerializer extends TypeSerializer<TimeSeriesObject> {

    @Override
    public void serialize(TimeSeriesObject ts, DataOutputView target) throws IOException {
        // Serialize label
        target.writeUTF(ts.getLabel());

        // Serialize x values
        target.writeInt(ts.xValues.size());
        for (int i = 0; i < ts.xValues.size(); i++) {
            target.writeDouble((Double) ts.xValues.elementAt(i));
        }

        // Serialize y values
        target.writeInt(ts.yValues.size());
        for (int i = 0; i < ts.yValues.size(); i++) {
            target.writeDouble((Double) ts.yValues.elementAt(i));
        }
    }

    @Override
    public TimeSeriesObject deserialize(DataInputView source) throws IOException {
        TimeSeriesObject ts = new TimeSeriesObject();

        // Deserialize label
        ts.setLabel(source.readUTF());

        // Deserialize x values
        int xSize = source.readInt();
        for (int i = 0; i < xSize; i++) {
            ts.xValues.add(source.readDouble());
        }

        // Deserialize y values
        int ySize = source.readInt();
        for (int i = 0; i < ySize; i++) {
            ts.yValues.add(source.readDouble());
        }

        return ts;
    }

    // Other required methods...
}
```

### Windowing Strategy

**Tumbling Windows** (non-overlapping):

```java
DataStream<Observation> observations = /* source */;

DataStream<TimeSeriesObject> timeSeries = observations
    .keyBy(obs -> obs.getUri())
    .window(TumblingEventTimeWindows.of(Time.minutes(5)))
    .aggregate(new TimeSeriesAggregateFunction());
```

**Sliding Windows** (overlapping):

```java
DataStream<TimeSeriesObject> timeSeries = observations
    .keyBy(obs -> obs.getUri())
    .window(SlidingEventTimeWindows.of(Time.minutes(5), Time.minutes(1)))
    .aggregate(new TimeSeriesAggregateFunction());
```

**Session Windows** (gap-based):

```java
DataStream<TimeSeriesObject> timeSeries = observations
    .keyBy(obs -> obs.getUri())
    .window(EventTimeSessionWindows.withGap(Time.minutes(10)))
    .aggregate(new TimeSeriesAggregateFunction());
```

### Watermark Strategy

For handling event time and late data:

```java
WatermarkStrategy<Observation> watermarkStrategy =
    WatermarkStrategy
        .<Observation>forBoundedOutOfOrderness(Duration.ofSeconds(10))
        .withTimestampAssigner((obs, timestamp) -> obs.getTimestamp());

DataStream<Observation> observations = env
    .fromSource(kafkaSource, watermarkStrategy, "Kafka Source");
```

### Example Complete Flink Job

```java
public class TimeSeriesAnalysisJob {

    public static void main(String[] args) throws Exception {
        // Set up execution environment
        StreamExecutionEnvironment env =
            StreamExecutionEnvironment.getExecutionEnvironment();

        env.setStreamTimeCharacteristic(TimeCharacteristic.EventTime);
        env.enableCheckpointing(60000); // Checkpoint every minute

        // Configure Kafka source
        KafkaSource<Observation> kafkaSource = KafkaSource
            .<Observation>builder()
            .setBootstrapServers("localhost:9092")
            .setTopics("sensor-observations")
            .setGroupId("timeseries-analysis")
            .setValueOnlyDeserializer(new ObservationSchema())
            .setStartingOffsets(OffsetsInitializer.earliest())
            .build();

        // Configure watermarks
        WatermarkStrategy<Observation> watermarkStrategy =
            WatermarkStrategy
                .<Observation>forBoundedOutOfOrderness(Duration.ofSeconds(10))
                .withTimestampAssigner((obs, ts) -> obs.getTimestamp());

        // Create observation stream
        DataStream<Observation> observations = env
            .fromSource(kafkaSource, watermarkStrategy, "Observations");

        // Aggregate into time series (5-minute tumbling windows)
        DataStream<TimeSeriesObject> timeSeries = observations
            .keyBy(obs -> obs.getUri().toString())
            .window(TumblingEventTimeWindows.of(Time.minutes(5)))
            .aggregate(new TimeSeriesAggregateFunction());

        // Apply DFA analysis
        DataStream<DFAResult> dfaResults = timeSeries
            .map(new DFAOperator(new DFAParameter()))
            .name("DFA Analysis");

        // Apply anomaly detection
        DataStream<AnomalyResult> anomalies = timeSeries
            .keyBy(ts -> ts.getLabel())
            .process(new AnomalyDetectionFunction(3.0)) // 3-sigma threshold
            .name("Anomaly Detection");

        // Sink DFA results to Kafka
        KafkaSink<DFAResult> dfaSink = KafkaSink
            .<DFAResult>builder()
            .setBootstrapServers("localhost:9092")
            .setRecordSerializer(new DFAResultSerializer("dfa-results"))
            .build();

        dfaResults.sinkTo(dfaSink).name("DFA Results Sink");

        // Sink anomalies to Kafka
        KafkaSink<AnomalyResult> anomalySink = KafkaSink
            .<AnomalyResult>builder()
            .setBootstrapServers("localhost:9092")
            .setRecordSerializer(new AnomalyResultSerializer("anomalies"))
            .build();

        anomalies.sinkTo(anomalySink).name("Anomaly Results Sink");

        // Execute job
        env.execute("Time Series Analysis Job");
    }
}
```

## Testing Strategy

### Unit Tests

```java
@Test
public void testTimeSeriesAggregation() {
    TimeSeriesAggregateFunction aggregator = new TimeSeriesAggregateFunction();

    TimeSeriesObject ts = aggregator.createAccumulator();

    Observation obs1 = new Observation(1000L, "sensor1", 25.5);
    Observation obs2 = new Observation(2000L, "sensor1", 26.0);

    ts = aggregator.add(obs1, ts);
    ts = aggregator.add(obs2, ts);

    TimeSeriesObject result = aggregator.getResult(ts);

    assertEquals(2, result.yValues.size());
    assertEquals(25.5, (Double) result.yValues.elementAt(0), 0.01);
    assertEquals(26.0, (Double) result.yValues.elementAt(1), 0.01);
}
```

### Integration Tests

```java
@Test
public void testEndToEndPipeline() throws Exception {
    StreamExecutionEnvironment env =
        StreamExecutionEnvironment.getExecutionEnvironment();

    // Create test data source
    List<Observation> testData = Arrays.asList(
        new Observation(1000L, "sensor1", 25.5),
        new Observation(2000L, "sensor1", 26.0),
        new Observation(3000L, "sensor1", 24.8)
    );

    DataStream<Observation> observations = env.fromCollection(testData);

    // Apply pipeline
    DataStream<TimeSeriesObject> timeSeries = observations
        .keyBy(obs -> obs.getUri().toString())
        .timeWindow(Time.seconds(5))
        .aggregate(new TimeSeriesAggregateFunction());

    // Collect results
    List<TimeSeriesObject> results = new ArrayList<>();
    timeSeries.addSink(new CollectSink(results));

    env.execute();

    // Verify
    assertEquals(1, results.size());
    assertEquals(3, results.get(0).yValues.size());
}
```

### Performance Benchmarks

```java
@Benchmark
public void benchmarkTimeSeriesAggregation() {
    // Benchmark aggregation throughput
    // Target: 100K observations/second
}

@Benchmark
public void benchmarkDFAAnalysis() {
    // Benchmark DFA processing
    // Target: Process 1000-point series in < 100ms
}
```

## Deployment

### Kubernetes Deployment

```yaml
apiVersion: flink.apache.org/v1beta1
kind: FlinkDeployment
metadata:
  name: timeseries-analysis
spec:
  image: opentsx/flink-timeseries:3.0.0
  flinkVersion: v1_18
  flinkConfiguration:
    taskmanager.numberOfTaskSlots: "4"
    state.backend: rocksdb
    state.checkpoints.dir: s3://opentsx/checkpoints
    state.savepoints.dir: s3://opentsx/savepoints
  jobManager:
    resource:
      memory: "2048m"
      cpu: 1
  taskManager:
    resource:
      memory: "4096m"
      cpu: 2
  job:
    jarURI: local:///opt/flink/usrlib/opentsx-flink-analytics.jar
    parallelism: 4
    upgradeMode: savepoint
```

## Success Criteria

### Phase 1 Success Criteria
- [ ] Successfully ingest 100K observations/second from Kafka
- [ ] Aggregate observations into time series with < 1s latency
- [ ] Unit test coverage > 80%
- [ ] Integration tests pass consistently

### Phase 2 Success Criteria
- [ ] DFA analysis completes on 1000-point series in < 100ms
- [ ] Anomaly detection accuracy > 95% (on test dataset)
- [ ] Performance benchmarks meet targets
- [ ] Documentation complete

### Phase 3 Success Criteria
- [ ] Successfully deploy to Kubernetes cluster
- [ ] Exactly-once semantics verified under failures
- [ ] Migration guide from Kafka Streams complete
- [ ] User acceptance testing passed

## Risks and Mitigations

| Risk | Impact | Probability | Mitigation |
|------|--------|-------------|------------|
| TimeSeriesObject serialization overhead | High | Medium | Use optimized custom serializer |
| State size exceeds RocksDB capacity | High | Low | Implement state TTL, compaction strategies |
| Flink learning curve slows development | Medium | High | Training, pair programming, consultant |
| Integration with existing code breaks | High | Low | Comprehensive regression testing |
| Performance targets not met | High | Medium | Early benchmarking, optimization iterations |

## Dependencies

### External Dependencies
- Apache Flink 1.18+
- Flink Kafka Connector
- Flink Avro
- RocksDB (state backend)

### Internal Dependencies
- opentsx-core (algorithms)
- opentsx-data (Avro models)

## Timeline

- **Week 1-2**: Phase 1 - Foundation
- **Week 3-4**: Phase 2 - Analytics
- **Week 5-6**: Phase 3 - Advanced Features
- **Week 7**: Testing, Documentation, Polish

**Total**: 6-7 weeks with 2 developers

## Next Steps

1. **Approval**: Get approval for TASK-003 implementation
2. **Resource Allocation**: Assign 2 developers for 6 weeks
3. **Environment Setup**: Provision Flink cluster for development
4. **Kickoff**: Start Phase 1 implementation

---

**Created**: 2025-12-20
**Last Updated**: 2025-12-20
**Status**: Awaiting Approval
