# The Developer's Journey 💻
## From Code to Kafka to Complex Time Series Analysis

Welcome, developer! This journey will transform you from OpenTSx newcomer to production-ready time series application builder. You'll learn the architecture, master the APIs, build real applications, and deploy at scale.

---

## 🎯 Learning Outcomes

By completing this journey, you will:
- ✅ Understand OpenTSx architecture and components
- ✅ Build Kafka Streams applications for time series
- ✅ Integrate OpenTSx into existing systems
- ✅ Deploy multi-region, production-grade systems
- ✅ Optimize for performance and scale
- ✅ Troubleshoot and monitor in production
- ✅ Contribute to the OpenTSx codebase

---

## 📊 Journey Overview

| Level | Topic | Time | Outcome |
|-------|-------|------|---------|
| ⭐ **Level 1** | Setup & Basics | 3-4h | Run your first app, understand arch |
| ⭐⭐ **Level 2** | Core Development | 8-10h | Build streaming apps, master APIs |
| ⭐⭐⭐ **Level 3** | Advanced Patterns | 12-15h | Production patterns, performance |
| ⭐⭐⭐⭐ **Level 4** | Production Mastery | 20h+ | Multi-region, contribute code |

**Total Journey Time**: 43-49+ hours

---

# ⭐ LEVEL 1: Setup & Basics
## Get Up and Running Fast (3-4 hours)

---

## 1.1 Development Environment Setup

### Prerequisites Checklist

```bash
# Java (verify version)
java -version
# Need: Java 11 or higher (Java 17 recommended)

# Maven (verify version)
mvn -version
# Need: Maven 3.6+

# Docker (verify installation)
docker --version
docker-compose --version
# Need: Docker 19.03+, Docker Compose 1.27+

# Git
git --version
# Need: Git 2.20+
```

If any tool is missing, install it:

**Java** (Ubuntu/Debian):
```bash
sudo apt update
sudo apt install openjdk-17-jdk
```

**Maven**:
```bash
sudo apt install maven
```

**Docker**:
```bash
curl -fsSL https://get.docker.com -o get-docker.sh
sudo sh get-docker.sh
sudo usermod -aG docker $USER
# Log out and back in for group change
```

---

## 1.2 Clone and Build OpenTSx

### Step 1: Clone Repository

```bash
# Clone the repository
git clone https://github.com/kamir/OpenTSx.git
cd OpenTSx

# Check out specific branch if needed
git checkout main  # or development branch

# Explore structure
ls -la
```

**Directory Structure:**
```
OpenTSx/
├── opentsx-core/              # Core algorithms
├── opentsx-data/              # Avro schemas
├── opentsx-connectors/        # Kafka connectivity
├── opentsx-lg/                # Data generator
├── opentsx-kafka-streams-tsa/ # Stream processing
├── opentsx-store-cassandra/   # Cassandra integration
├── config/                    # Configuration files
├── bin/                       # Build scripts
└── pom.xml                    # Root POM
```

### Step 2: Build the Project

```bash
# Full build (takes 5-10 minutes first time)
./bin/010_build.sh

# Or use Maven directly
mvn clean install -DskipTests

# Build specific module
cd opentsx-core
mvn clean install
```

**Expected Output:**
```
[INFO] ------------------------------------------------------------------------
[INFO] Reactor Summary:
[INFO]
[INFO] opentsx-data ...................................... SUCCESS
[INFO] opentsx-core ...................................... SUCCESS
[INFO] opentsx-connectors ................................ SUCCESS
[INFO] opentsx-lg ........................................ SUCCESS
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
```

### Step 3: IDE Setup

**IntelliJ IDEA:**
```bash
# Import as Maven project
File → Open → Select OpenTSx/pom.xml

# Configure JDK
File → Project Structure → Project SDK: Java 17

# Enable auto-import for Maven
Preferences → Build → Build Tools → Maven
  ✓ Import Maven projects automatically
```

**VS Code:**
```bash
# Install Java Extension Pack
code --install-extension vscjava.vscode-java-pack

# Open folder
code OpenTSx/

# Configure Java
.vscode/settings.json:
{
  "java.configuration.updateBuildConfiguration": "automatic",
  "java.home": "/path/to/jdk-17"
}
```

---

## 1.3 Understanding OpenTSx Architecture

### The Big Picture

```
┌─────────────────────────────────────────────────────────────┐
│                     YOUR APPLICATION                         │
│  (Custom analytics, dashboards, ML models)                   │
└──────────────────────────┬──────────────────────────────────┘
                           ↓
┌─────────────────────────────────────────────────────────────┐
│                    OpenTSx Core APIs                         │
│  • TimeSeriesObject  • TSBucket  • Algorithms                │
└──────────────────────────┬──────────────────────────────────┘
                           ↓
        ┌──────────────────┴──────────────────┐
        ↓                                      ↓
┌──────────────────┐                  ┌──────────────────┐
│  Kafka Streams   │                  │   Storage APIs    │
│  • KStreams      │                  │   • Cassandra     │
│  • ksqlDB        │                  │   • OpenTSDB      │
│  • Processors    │                  │   • HDFS          │
└────────┬─────────┘                  └────────┬──────────┘
         ↓                                      ↓
┌──────────────────────────────────────────────────────────────┐
│                      Apache Kafka                             │
│  (Events → Time Series → Analysis → Storage)                 │
└──────────────────────────────────────────────────────────────┘
```

### Key Abstractions

#### 1. TimeSeriesObject (TSO)

**Purpose**: Represents a single time series

```java
import org.opentsx.data.TimeSeriesObject;

// Create time series
TimeSeriesObject tso = new TimeSeriesObject();
tso.setLabel("sensor_temperature");

// Add data points
for (int i = 0; i < 100; i++) {
    long timestamp = System.currentTimeMillis() + i * 1000;
    double value = 20.0 + Math.random() * 10;
    tso.addValue(timestamp, value);
}

// Add metadata
tso.addMetadata("location", "datacenter1");
tso.addMetadata("sensor_id", "temp_01");

// Access data
double[] values = tso.getData();
long[] timestamps = tso.getTimestamps();
String label = tso.getLabel();
```

#### 2. TSBucket

**Purpose**: Container for multiple related time series

```java
import org.opentsx.tsbucket.TSBucket;

// Create bucket
TSBucket bucket = new TSBucket();
bucket.setLabel("temperature_sensors");

// Add multiple time series
bucket.addTS(sensor1);
bucket.addTS(sensor2);
bucket.addTS(sensor3);

// Save to storage
bucket.save("hdfs://path/to/bucket", TSBucket.Format.AVRO);

// Load from storage
TSBucket loaded = TSBucket.load("hdfs://path/to/bucket");

// Iterate over series
for (TimeSeriesObject tso : bucket.getAllTS()) {
    System.out.println("Series: " + tso.getLabel());
}
```

#### 3. TSProcessor

**Purpose**: Processing pipeline abstraction

```java
import org.opentsx.core.TSProcessor;

// Implement custom processor
public class MyAnalysisProcessor implements TSProcessor {

    @Override
    public TSBucket process(TSBucket input) {
        TSBucket output = new TSBucket();

        for (TimeSeriesObject tso : input.getAllTS()) {
            // Apply your analysis
            TimeSeriesObject analyzed = analyzeTimeSeries(tso);
            output.addTS(analyzed);
        }

        return output;
    }

    private TimeSeriesObject analyzeTimeSeries(TimeSeriesObject tso) {
        // Your algorithm here
        return tso;
    }
}

// Use processor
TSProcessor processor = new MyAnalysisProcessor();
TSBucket results = processor.process(inputBucket);
```

---

## 1.4 Your First OpenTSx Application

### 🚀 Quick Start: Hello Time Series

Let's build a complete application in 30 minutes!

**Goal**: Generate data, analyze it, visualize results.

#### Step 1: Create Project Structure

```bash
cd opentsx-core/src/main/java
mkdir -p com/mycompany/timeseries
cd com/mycompany/timeseries
```

#### Step 2: Hello Time Series Application

Create `HelloTimeSeries.java`:

```java
package com.mycompany.timeseries;

import org.opentsx.data.TimeSeriesObject;
import org.opentsx.generators.TSGenerator;
import org.opentsx.algorithms.detrending.DFA;
import org.opentsx.chart.simple.SimpleChartPanel;

/**
 * Hello Time Series - Your First OpenTSx Application
 *
 * This app demonstrates:
 * 1. Data generation
 * 2. Time series analysis (DFA)
 * 3. Visualization
 */
public class HelloTimeSeries {

    public static void main(String[] args) {
        System.out.println("=== Hello Time Series! ===\n");

        // STEP 1: Generate data
        TimeSeriesObject data = generateData();
        System.out.println("✓ Generated " + data.getLength() + " data points");

        // STEP 2: Analyze data
        double hurstExponent = analyzeDFA(data);
        System.out.println("✓ Hurst Exponent: " + hurstExponent);
        System.out.println("  Interpretation: " + interpret(hurstExponent));

        // STEP 3: Visualize
        visualize(data);
        System.out.println("✓ Visualization displayed");

        System.out.println("\n=== Complete! ===");
    }

    /**
     * Generate synthetic time series
     */
    private static TimeSeriesObject generateData() {
        TSGenerator generator = new TSGenerator();

        // Generate sine wave with noise
        int n = 1000;
        double[] values = new double[n];

        for (int i = 0; i < n; i++) {
            double t = i * 0.1;
            // Sine wave + noise
            values[i] = Math.sin(t) + 0.2 * (Math.random() - 0.5);
        }

        TimeSeriesObject tso = new TimeSeriesObject();
        tso.setLabel("Noisy Sine Wave");
        tso.setData(values);

        return tso;
    }

    /**
     * Perform DFA analysis
     */
    private static double analyzeDFA(TimeSeriesObject tso) {
        DFA dfa = new DFA();
        dfa.setPolynomOrder(1);  // Linear detrending

        // Define scales
        int[] scales = new int[]{10, 15, 20, 30, 50, 75, 100, 150, 200};

        // Calculate fluctuations
        double[] fluctuations = dfa.calc(tso.getData(), scales);

        // Fit Hurst exponent (slope in log-log plot)
        return fitHurst(scales, fluctuations);
    }

    /**
     * Fit Hurst exponent via linear regression
     */
    private static double fitHurst(int[] scales, double[] fluct) {
        int n = scales.length;

        double sumLogS = 0, sumLogF = 0, sumLogSLogF = 0, sumLogS2 = 0;

        for (int i = 0; i < n; i++) {
            double logS = Math.log(scales[i]);
            double logF = Math.log(fluct[i]);

            sumLogS += logS;
            sumLogF += logF;
            sumLogSLogF += logS * logF;
            sumLogS2 += logS * logS;
        }

        // Slope = Hurst exponent
        return (n * sumLogSLogF - sumLogS * sumLogF) /
               (n * sumLogS2 - sumLogS * sumLogS);
    }

    /**
     * Interpret Hurst exponent
     */
    private static String interpret(double H) {
        if (H < 0.45) {
            return "Anti-persistent (mean-reverting)";
        } else if (H < 0.55) {
            return "Random walk (uncorrelated)";
        } else {
            return "Persistent (trending)";
        }
    }

    /**
     * Visualize time series
     */
    private static void visualize(TimeSeriesObject tso) {
        SimpleChartPanel chart = new SimpleChartPanel();
        chart.setData(tso.getData());
        chart.setTitle(tso.getLabel());
        chart.setXLabel("Time");
        chart.setYLabel("Value");
        chart.display();
    }
}
```

#### Step 3: Run Your Application

```bash
# Navigate to root
cd /path/to/OpenTSx/opentsx-core

# Run
mvn exec:java -Dexec.mainClass="com.mycompany.timeseries.HelloTimeSeries"
```

**Expected Output:**
```
=== Hello Time Series! ===

✓ Generated 1000 data points
✓ Hurst Exponent: 0.523
  Interpretation: Random walk (uncorrelated)
✓ Visualization displayed

=== Complete! ===
```

**Congratulations!** 🎉 You've built your first OpenTSx application!

---

## 1.5 Kafka Integration Basics

### Why Kafka for Time Series?

**Traditional Approach:**
- Poll database every second
- High latency (seconds to minutes)
- Database becomes bottleneck
- Difficult to scale

**Kafka Approach:**
- Real-time event streaming
- Low latency (milliseconds)
- Horizontally scalable
- Decoupled producers/consumers

### Kafka Fundamentals

```
Producer → Topic (Partitioned) → Consumer Group
   ↓                                    ↓
Events                            Time Series
```

**Key Concepts:**
- **Topic**: Category of events (e.g., "sensor_readings")
- **Partition**: Parallel processing unit
- **Consumer Group**: Load balancing
- **Offset**: Position in event log

---

## 1.6 Your First Kafka Producer

### 🚀 Sending Time Series to Kafka

**Goal**: Stream generated data to Kafka topic.

#### Step 1: Start Local Kafka

```bash
# Using Docker Compose
cd OpenTSx
cat > docker-compose-dev.yml <<EOF
version: '3.8'
services:
  zookeeper:
    image: confluentinc/cp-zookeeper:7.3.0
    environment:
      ZOOKEEPER_CLIENT_PORT: 2181
    ports:
      - "2181:2181"

  kafka:
    image: confluentinc/cp-kafka:7.3.0
    depends_on:
      - zookeeper
    environment:
      KAFKA_BROKER_ID: 1
      KAFKA_ZOOKEEPER_CONNECT: zookeeper:2181
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://localhost:9092
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1
    ports:
      - "9092:9092"

  schema-registry:
    image: confluentinc/cp-schema-registry:7.3.0
    depends_on:
      - kafka
    environment:
      SCHEMA_REGISTRY_HOST_NAME: schema-registry
      SCHEMA_REGISTRY_KAFKASTORE_BOOTSTRAP_SERVERS: kafka:9092
    ports:
      - "8081:8081"
EOF

# Start services
docker-compose -f docker-compose-dev.yml up -d

# Verify
docker-compose -f docker-compose-dev.yml ps
```

#### Step 2: Create Kafka Producer

Create `KafkaTimeSeriesProducer.java`:

```java
package com.mycompany.timeseries;

import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.StringSerializer;
import io.confluent.kafka.serializers.KafkaAvroSerializer;

import org.opentsx.data.TimeSeriesObject;
import org.opentsx.data.avro.EpisodesRecord;

import java.util.Properties;
import java.util.concurrent.ExecutionException;

/**
 * Kafka Producer for Time Series Data
 *
 * Demonstrates:
 * 1. Producer configuration
 * 2. Avro serialization
 * 3. Error handling
 * 4. Performance monitoring
 */
public class KafkaTimeSeriesProducer {

    private final Producer<String, EpisodesRecord> producer;
    private final String topic;

    public KafkaTimeSeriesProducer(String bootstrapServers,
                                  String schemaRegistryUrl,
                                  String topic) {
        this.topic = topic;
        this.producer = createProducer(bootstrapServers, schemaRegistryUrl);
    }

    /**
     * Configure Kafka producer
     */
    private Producer<String, EpisodesRecord> createProducer(
            String bootstrapServers, String schemaRegistryUrl) {

        Properties props = new Properties();

        // Kafka cluster
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);

        // Serializers
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                 StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                 KafkaAvroSerializer.class.getName());

        // Schema Registry
        props.put("schema.registry.url", schemaRegistryUrl);

        // Performance tuning
        props.put(ProducerConfig.ACKS_CONFIG, "all");  // Durability
        props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "snappy");
        props.put(ProducerConfig.BATCH_SIZE_CONFIG, 16384);
        props.put(ProducerConfig.LINGER_MS_CONFIG, 10);

        return new KafkaProducer<>(props);
    }

    /**
     * Send time series data
     */
    public void send(TimeSeriesObject tso) throws ExecutionException, InterruptedException {

        // Convert to Avro
        EpisodesRecord record = convertToAvro(tso);

        // Create Kafka record
        ProducerRecord<String, EpisodesRecord> kafkaRecord =
            new ProducerRecord<>(topic, tso.getLabel(), record);

        // Send asynchronously with callback
        producer.send(kafkaRecord, new Callback() {
            @Override
            public void onCompletion(RecordMetadata metadata, Exception exception) {
                if (exception != null) {
                    System.err.println("Error sending message: " + exception.getMessage());
                } else {
                    System.out.println("Sent: " + tso.getLabel() +
                                     " to partition " + metadata.partition() +
                                     " at offset " + metadata.offset());
                }
            }
        });
    }

    /**
     * Convert TimeSeriesObject to Avro EpisodesRecord
     */
    private EpisodesRecord convertToAvro(TimeSeriesObject tso) {
        EpisodesRecord.Builder builder = EpisodesRecord.newBuilder();

        // Copy data
        builder.setLabel(tso.getLabel());

        // Convert double[] to List<Double>
        java.util.List<Double> observations = new java.util.ArrayList<>();
        for (double value : tso.getData()) {
            observations.add(value);
        }
        builder.setObservations(observations);

        // Timestamps
        java.util.List<Long> timestamps = new java.util.ArrayList<>();
        for (long ts : tso.getTimestamps()) {
            timestamps.add(ts);
        }
        builder.setTimestamps(timestamps);

        // Metadata
        java.util.Map<String, String> metadata = new java.util.HashMap<>();
        if (tso.getMetadata() != null) {
            metadata.putAll(tso.getMetadata());
        }
        builder.setMetadata(metadata);

        return builder.build();
    }

    /**
     * Flush and close producer
     */
    public void close() {
        producer.flush();
        producer.close();
    }

    /**
     * Example usage
     */
    public static void main(String[] args) throws Exception {
        System.out.println("=== Kafka Time Series Producer ===\n");

        // Configuration
        String bootstrapServers = "localhost:9092";
        String schemaRegistryUrl = "http://localhost:8081";
        String topic = "timeseries_data";

        // Create producer
        KafkaTimeSeriesProducer producer =
            new KafkaTimeSeriesProducer(bootstrapServers, schemaRegistryUrl, topic);

        // Generate and send data
        for (int i = 0; i < 10; i++) {
            TimeSeriesObject tso = generateSampleData(i);
            producer.send(tso);

            Thread.sleep(1000);  // Send every second
        }

        // Clean up
        producer.close();

        System.out.println("\n=== Complete! ===");
    }

    /**
     * Generate sample time series
     */
    private static TimeSeriesObject generateSampleData(int seriesId) {
        TimeSeriesObject tso = new TimeSeriesObject();
        tso.setLabel("sensor_" + seriesId);

        int n = 100;
        double[] values = new double[n];
        long[] timestamps = new long[n];
        long now = System.currentTimeMillis();

        for (int i = 0; i < n; i++) {
            timestamps[i] = now + i * 1000;
            values[i] = 20.0 + 5.0 * Math.sin(i * 0.1) + Math.random();
        }

        tso.setData(values);
        tso.setTimestamps(timestamps);

        return tso;
    }
}
```

#### Step 3: Run the Producer

```bash
mvn exec:java -Dexec.mainClass="com.mycompany.timeseries.KafkaTimeSeriesProducer"
```

**Expected Output:**
```
=== Kafka Time Series Producer ===

Sent: sensor_0 to partition 0 at offset 0
Sent: sensor_1 to partition 0 at offset 1
Sent: sensor_2 to partition 0 at offset 2
...
Sent: sensor_9 to partition 0 at offset 9

=== Complete! ===
```

#### Step 4: Verify Data in Kafka

```bash
# List topics
kafka-topics --bootstrap-server localhost:9092 --list

# Consume messages
kafka-avro-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic timeseries_data \
  --from-beginning \
  --property schema.registry.url=http://localhost:8081
```

---

## 🎓 Exercise 1.1: Build a Real-Time Dashboard

**Task**: Create a producer that generates live sensor data and a consumer that displays it.

**Requirements:**
1. Generate temperature, humidity, pressure readings
2. Send to Kafka every second
3. Consumer displays latest 100 readings
4. Update JFreeChart in real-time

**Template**:
```java
public class Exercise01_RealtimeDashboard {

    public static void main(String[] args) {
        // TODO: Start producer thread

        // TODO: Start consumer thread

        // TODO: Update chart every second
    }

    static class ProducerThread extends Thread {
        public void run() {
            // TODO: Generate and send data
        }
    }

    static class ConsumerThread extends Thread {
        public void run() {
            // TODO: Consume and buffer data
        }
    }
}
```

**Solution**: See Section 7.1

---

## 🎯 Level 1 Checkpoint

**You should now be able to:**
- ✅ Set up OpenTSx development environment
- ✅ Build the project from source
- ✅ Understand core abstractions (TSO, TSBucket, TSProcessor)
- ✅ Create basic time series applications
- ✅ Produce data to Kafka
- ✅ Work with Avro schemas
- ✅ Use JFreeChart for visualization

**Ready for Level 2?** Let's build streaming applications!

---

# ⭐⭐ LEVEL 2: Core Development
## Build Production Streaming Apps (8-10 hours)

---

## 2.1 Kafka Streams Architecture

### Understanding Kafka Streams

```
Input Topics → Stream Topology → Output Topics
                      ↓
              State Stores (RocksDB)
```

**Key Concepts:**
- **KStream**: Unbounded stream of records
- **KTable**: Changelog stream (latest value per key)
- **Processor Topology**: DAG of processing nodes
- **State Store**: Local storage for stateful operations

---

## 2.2 Building Your First KStreams Application

### 🚀 Event Aggregation Pipeline

**Goal**: Aggregate individual events into time series episodes.

**Flow**:
```
Events (100/sec) → Session Windows (5min gap) → Episodes → Kafka
```

Create `EventAggregationApp.java`:

```java
package com.mycompany.timeseries.streams;

import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.kstream.*;
import org.apache.kafka.common.serialization.Serdes;

import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerde;
import org.opentsx.data.avro.EventRecord;
import org.opentsx.data.avro.EpisodesRecord;

import java.time.Duration;
import java.util.Properties;
import java.util.ArrayList;

/**
 * Event Aggregation with Kafka Streams
 *
 * Aggregates individual events into episodes using session windows
 */
public class EventAggregationApp {

    public static void main(String[] args) {
        // Configuration
        Properties props = new Properties();
        props.put(StreamsConfig.APPLICATION_ID_CONFIG, "event-aggregation");
        props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
        props.put("schema.registry.url", "http://localhost:8081");

        // Build topology
        StreamsBuilder builder = new StreamsBuilder();

        // Configure Avro Serdes
        SpecificAvroSerde<EventRecord> eventSerde = createAvroSerde();
        SpecificAvroSerde<EpisodesRecord> episodeSerde = createAvroSerde();

        // Read events stream
        KStream<String, EventRecord> events = builder.stream(
            "events_topic",
            Consumed.with(Serdes.String(), eventSerde)
        );

        // Aggregate into episodes using session windows
        KTable<Windowed<String>, EpisodesRecord> episodes = events
            .groupByKey()
            .windowedBy(SessionWindows.with(Duration.ofMinutes(5)))
            .aggregate(
                // Initializer
                () -> EpisodesRecord.newBuilder()
                        .setLabel("")
                        .setObservations(new ArrayList<>())
                        .setTimestamps(new ArrayList<>())
                        .setMetadata(new java.util.HashMap<>())
                        .build(),

                // Aggregator
                (key, event, episode) -> {
                    // Add observation
                    episode.getObservations().add(event.getValue());
                    episode.getTimestamps().add(event.getTimestamp());
                    episode.setLabel(key);

                    return episode;
                },

                // Merger (for session window merging)
                (key, episode1, episode2) -> {
                    episode1.getObservations().addAll(episode2.getObservations());
                    episode1.getTimestamps().addAll(episode2.getTimestamps());
                    return episode1;
                },

                Materialized.with(Serdes.String(), episodeSerde)
            );

        // Output to new topic
        episodes
            .toStream()
            .map((windowedKey, episode) ->
                new KeyValue<>(windowedKey.key(), episode))
            .to("episodes_topic", Produced.with(Serdes.String(), episodeSerde));

        // Build and start
        KafkaStreams streams = new KafkaStreams(builder.build(), props);

        // Graceful shutdown
        Runtime.getRuntime().addShutdownHook(new Thread(streams::close));

        streams.start();

        System.out.println("Event Aggregation App started!");
        System.out.println("Aggregating events into episodes...");
    }

    /**
     * Create Avro Serde configured with Schema Registry
     */
    private static <T> SpecificAvroSerde<T> createAvroSerde() {
        SpecificAvroSerde<T> serde = new SpecificAvroSerde<>();

        java.util.Map<String, String> config = new java.util.HashMap<>();
        config.put("schema.registry.url", "http://localhost:8081");

        serde.configure(config, false);  // false = value serde

        return serde;
    }
}
```

**Run the Application:**

```bash
mvn exec:java -Dexec.mainClass="com.mycompany.timeseries.streams.EventAggregationApp"
```

---

*This tutorial continues with advanced Kafka Streams patterns, ksqlDB integration, performance optimization, deployment strategies, and more...*

---

# 7. Exercise Solutions

## 7.1 Exercise 1.1 Solution
[Full solution provided here...]

---

**Next**: [Level 3 - Advanced Patterns](02-DEVELOPER-JOURNEY-LEVEL3.md)

---

*Last Updated: 2025-01-13*
*Tutorial Version: 1.0*
*Feedback: tutorials@opentsx.com*
