# OpenTSx Flink Integration

Apache Flink integration for OpenTSx time series analysis framework.

## Overview

This module provides a comprehensive integration between OpenTSx and Apache Flink, enabling scalable, distributed time series analysis with exactly-once processing guarantees.

**Key Features:**

- **Stream Processing**: Real-time analysis of time series streams from Kafka and other sources
- **Windowed Aggregation**: Automatic conversion of observation streams to TimeSeriesObject windows
- **Custom Serialization**: Efficient Flink serialization for TimeSeriesObject with RocksDB support
- **Exactly-Once Semantics**: Fault-tolerant processing with Flink checkpointing
- **Event-Time Processing**: Proper handling of out-of-order events with watermarks

## Architecture

```
opentsx-flink-core/
├── functions/           # Flink processing functions
│   └── TimeSeriesAggregateFunction.java
├── serdes/             # Serialization and deserialization
│   ├── ObservationSchema.java
│   ├── TimeSeriesObjectTypeInfo.java
│   └── TimeSeriesObjectSerializer.java
└── examples/           # Example Flink jobs
    └── TimeSeriesAnalysisJob.java
```

## Quick Start

### Dependencies

Add to your `pom.xml`:

```xml
<dependency>
    <groupId>org.opentsx</groupId>
    <artifactId>opentsx-flink-core</artifactId>
    <version>3.0.0</version>
</dependency>
```

### Basic Example

```java
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.windowing.assigners.TumblingEventTimeWindows;
import org.apache.flink.streaming.api.windowing.time.Time;
import org.opentsx.flink.functions.TimeSeriesAggregateFunction;
import org.opentsx.flink.serdes.TimeSeriesObjectTypeInfo;

StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

// Read observations from Kafka
DataStream<Observation> observations = env
    .fromSource(kafkaSource, watermarkStrategy, "Observations");

// Aggregate into time series windows
DataStream<TimeSeriesObject> timeSeries = observations
    .keyBy(Observation::getLabel)
    .window(TumblingEventTimeWindows.of(Time.minutes(5)))
    .aggregate(new TimeSeriesAggregateFunction())
    .returns(new TimeSeriesObjectTypeInfo());

// Apply OpenTSx analysis
timeSeries.map(ts -> {
    ts.normalize_zScore();
    ts.calcAverage();
    return ts;
});

env.execute("Time Series Analysis");
```

## Core Components

### ObservationSchema

Deserializes Avro-encoded Observation objects from Kafka topics with automatic watermark extraction.

```java
KafkaSource<Observation> source = KafkaSource.<Observation>builder()
    .setBootstrapServers("localhost:9092")
    .setTopics("observations")
    .setDeserializer(new ObservationSchema())
    .build();
```

### TimeSeriesAggregateFunction

Accumulates observations into TimeSeriesObject instances within Flink windows.

```java
DataStream<TimeSeriesObject> timeSeries = observations
    .keyBy(Observation::getLabel)
    .window(TumblingEventTimeWindows.of(Time.minutes(5)))
    .aggregate(new TimeSeriesAggregateFunction())
    .returns(new TimeSeriesObjectTypeInfo());
```

### TimeSeriesObjectSerializer

Efficient binary serialization for TimeSeriesObject with support for large state in RocksDB.

**Performance:**
- ~16 bytes per data point (2 doubles)
- O(n) serialization/deserialization
- Works with state larger than memory

## Running the Example Job

### Prerequisites

1. **Flink Cluster**: Local or remote Flink installation
2. **Kafka Broker**: Running on localhost:9092
3. **Input Topic**: Kafka topic "observations" with Avro-serialized Observation records

### Build

```bash
cd opentsx-flink-core
mvn clean package
```

### Run Locally

```bash
flink run -c org.opentsx.flink.examples.TimeSeriesAnalysisJob \
  target/opentsx-flink-core-3.0.0.jar
```

### Run with Custom Configuration

```bash
flink run -c org.opentsx.flink.examples.TimeSeriesAnalysisJob \
  target/opentsx-flink-core-3.0.0.jar \
  --kafka-brokers localhost:9092 \
  --input-topic my-observations \
  --window-size 300000
```

## Configuration

### Checkpointing

Enable checkpointing for fault tolerance:

```java
env.enableCheckpointing(60000); // Every 60 seconds
env.getCheckpointConfig().setCheckpointingMode(CheckpointingMode.EXACTLY_ONCE);
```

### State Backend

For large state, use RocksDB:

```java
env.setStateBackend(new EmbeddedRocksDBStateBackend());
env.getCheckpointConfig().setCheckpointStorage("hdfs:///flink/checkpoints");
```

### Watermarks

Configure watermark generation for event-time processing:

```java
WatermarkStrategy<Observation> strategy = WatermarkStrategy
    .<Observation>forBoundedOutOfOrderness(Duration.ofSeconds(10))
    .withTimestampAssigner((obs, ts) -> obs.getTimestamp());
```

## Performance Tuning

### Parallelism

Set operator parallelism for throughput:

```java
env.setParallelism(4); // Global parallelism

timeSeries
    .map(...)
    .setParallelism(8); // Operator-level parallelism
```

### Window Size

Balance latency vs. throughput:

- **Small windows** (seconds): Low latency, more overhead
- **Large windows** (minutes/hours): Higher latency, better throughput

### State TTL

Clean up old state to prevent memory growth:

```java
StateTtlConfig ttlConfig = StateTtlConfig
    .newBuilder(Time.hours(24))
    .setUpdateType(StateTtlConfig.UpdateType.OnCreateAndWrite)
    .setStateVisibility(StateTtlConfig.StateVisibility.NeverReturnExpired)
    .build();
```

## Testing

Run unit tests:

```bash
mvn test
```

Run integration tests (requires Kafka):

```bash
mvn verify
```

## Deployment

### Standalone Cluster

```bash
# Submit to remote cluster
flink run -m localhost:8081 \
  -c org.opentsx.flink.examples.TimeSeriesAnalysisJob \
  target/opentsx-flink-core-3.0.0.jar
```

### Kubernetes

```bash
# Deploy to Kubernetes via Flink Kubernetes Operator
kubectl create -f flink-deployment.yaml
```

### YARN

```bash
# Submit to YARN cluster
flink run -m yarn-cluster \
  -c org.opentsx.flink.examples.TimeSeriesAnalysisJob \
  target/opentsx-flink-core-3.0.0.jar
```

## Comparison with Kafka Streams

| Feature | Flink | Kafka Streams |
|---------|-------|---------------|
| **Deployment** | Requires cluster | Embedded library |
| **Scalability** | Excellent (thousands of cores) | Good (hundreds of cores) |
| **State Size** | Unlimited (RocksDB) | Limited by disk |
| **Exactly-Once** | Yes (across sources) | Yes (Kafka only) |
| **Event Time** | Native support | Requires configuration |
| **SQL Support** | Yes (Flink SQL) | No |
| **Batch Processing** | Yes (unified API) | No |

**When to Use Flink:**
- Large-scale deployments (100+ nodes)
- Complex event processing (CEP)
- Need for SQL or batch processing
- Multiple data sources (not just Kafka)
- Advanced windowing requirements

**When to Use Kafka Streams:**
- Simple deployments
- Kafka-centric architecture
- Embedded processing in microservices
- Lower operational overhead

## Troubleshooting

### ClassNotFoundException

Ensure all dependencies are included in the fat JAR:

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-shade-plugin</artifactId>
    <configuration>
        <transformers>
            <transformer implementation="org.apache.maven.plugins.shade.resource.ManifestResourceTransformer">
                <mainClass>org.opentsx.flink.examples.TimeSeriesAnalysisJob</mainClass>
            </transformer>
        </transformers>
    </configuration>
</plugin>
```

### Serialization Issues

If you see serialization errors, ensure you're using `.returns()`:

```java
timeSeries
    .map(...)
    .returns(new TimeSeriesObjectTypeInfo())
```

### Out of Memory

For large time series, increase memory:

```bash
flink run -m localhost:8081 \
  -ytm 4096 \  # Task manager memory
  -yjm 2048 \  # Job manager memory
  target/opentsx-flink-core-3.0.0.jar
```

## Contributing

See [CONTRIBUTING.md](../CONTRIBUTING.md) for development guidelines.

## License

Apache License 2.0 - See [LICENSE](../LICENSE) for details.

## Related Documentation

- [OpenTSx Manual](../docs/manual/)
- [TASK-003: Flink Integration Plan](../EVOLUTION/TASK-003/)
- [Apache Flink Documentation](https://flink.apache.org/docs/)

---

**Version:** 3.0.0
**Last Updated:** 2025-12-20
