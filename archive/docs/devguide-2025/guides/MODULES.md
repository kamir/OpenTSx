# OpenTSx Modules Documentation

## Table of Contents
- [Overview](#overview)
- [Module Dependency Graph](#module-dependency-graph)
- [Core Modules](#core-modules)
- [Data & Connectivity Modules](#data--connectivity-modules)
- [Stream Processing Modules](#stream-processing-modules)
- [Storage Modules](#storage-modules)
- [Application & Demo Modules](#application--demo-modules)
- [Specialized Modules](#specialized-modules)
- [Module Build Status](#module-build-status)

---

## Overview

OpenTSx is organized as a Maven multi-module project with clear separation of concerns. Each module is independently buildable and has well-defined dependencies.

**Total Modules:** 25+
**Active Build Modules:** 6 core modules
**Optional Modules:** 19+ specialized modules

---

## Module Dependency Graph

```
opentsx-data (base schemas)
     ↓
opentsx-core (algorithms & abstractions)
     ↓
     ├─> opentsx-connectors (Kafka integration)
     │        ↓
     │        └─> opentsx-lg (data generator)
     │
     ├─> opentsx-store-cassandra (persistence)
     ├─> opentsx-store-opentsdb (persistence)
     │
     ├─> opentsx-kafka-streams-tsa (stream processing)
     ├─> opentsx-ksql (SQL streaming)
     ├─> opentsx-ksql-udf (custom functions)
     │
     ├─> opentsx-predict (ML inference)
     ├─> opentsx-processors (stream processors)
     │
     └─> opentsx-app-demos (applications)
```

---

## Core Modules

### opentsx-core
**Location:** `opentsx-core/`
**Purpose:** Core time series analysis algorithms and data structures
**Status:** Active (in root POM)

#### Key Packages

##### 1. org.opentsx.core
**Purpose:** Core abstractions and data structures

**Classes:**
- `TSBucket` - Container for grouped time series
- `TSData` - Time series data wrapper
- `TSProcessor` - Processing interface
- `TSOperation` - Operation abstraction

**Usage Example:**
```java
TSBucket bucket = new TSBucket();
TimeSeriesObject tso = new TimeSeriesObject();
bucket.addTS(tso);
```

##### 2. org.opentsx.algorithms
**Purpose:** Time series analysis algorithms

**Sub-packages:**

###### algorithms/detrending
**Classes:**
- `DFA.java` - Detrended Fluctuation Analysis
- `MFDFA.java` - Multifractal DFA
- `DFAParameter.java` - Configuration
- `FitTool.java` - Polynomial fitting utilities

**Key Features:**
- Multiple detrending orders (1-5)
- Fluctuation function calculation
- Scaling exponent estimation
- Multifractal spectrum analysis

**Example:**
```java
DFA dfa = new DFA();
dfa.setPolynomOrder(1);
double[] fluctuations = dfa.calc(timeSeries, scales);
```

###### algorithms/eventsynchronisation
**Classes:**
- `ESCalc.java` - Event synchronization calculator
- `ESCalc2.java` - Enhanced version
- Experimental variants

**Purpose:**
- Detect synchronized events between time series
- Calculate delay and correlation metrics
- Identify causal relationships

###### algorithms/ris
**Classes:**
- `RISTool.java` - Return Interval Statistics

**Purpose:**
- Analyze intervals between threshold-crossing events
- Calculate distribution of return intervals
- Risk analysis and extreme event detection

###### algorithms/statistics
**Classes:**
- `Entropy.java` - Shannon entropy calculation
- `DistributionTesting.java` - Shapiro-Wilk, normality tests
- `Normalization.java` - Data normalization methods
- `GrangerCausality.java` - Causal relationship detection
- `LogBinning.java` - Logarithmic binning for distributions

###### algorithms/univariate
**Classes:**
- `DFAIntervalCutDetrended.java` - Interval-based DFA
- `PeakEntropyTool.java` - Peak detection entropy
- `TrendCalculator.java` - Trend extraction

##### 3. org.opentsx.analysistools
**Purpose:** Higher-level analysis tools built on algorithms

**Classes:**
- `FluctuationFunctionTools.java` - DFA fluctuation analysis
- `HurstSurface.java` - 2D Hurst exponent visualization
- `LogBinningTool.java` - Distribution binning

##### 4. org.opentsx.tsbucket
**Purpose:** TSBucket persistence and loading

**Classes:**
- `BucketFolderStore.java` - File-based storage
- `TSBucketLoader.java` - Loading abstraction
- `SequenceFileWriter.java` - Hadoop SequenceFile support

**Supported Formats:**
- Hadoop SequenceFile
- Apache Avro
- Apache Parquet
- CSV (import/export)

##### 5. org.opentsx.data
**Purpose:** Data models and generators

**Classes:**
- `TimeSeriesObject.java` - Core time series class
- `TSGenerator.java` - Data generation utilities
- `Episode.java` - Episode abstraction

##### 6. org.opentsx.generators
**Purpose:** Synthetic data generation

**Classes:**
- `LongTermCorrelationSeriesGenerator.java` - Generate LTC series
- `FFTPhaseRandomizer.java` - Phase randomization via FFT
- `RNGWrapper.java` - Random number generation

**Features:**
- Configurable correlation properties
- Multiple distribution types
- Deterministic seeding for reproducibility

##### 7. org.opentsx.chart
**Purpose:** Visualization components

**Sub-packages:**
- `simple/` - Basic charts
- `dynamic/` - Real-time updating charts
- `panels/` - Chart panels for GUI
- `gnuplot/` - Gnuplot integration

**Technologies:** JFreeChart 1.0.19

##### 8. org.opentsx.app
**Purpose:** Application frameworks and tools

**Classes:**
- `BucketAnalyser.java` - TSBucket analysis tool
- GUI utilities and context recording

**Dependencies:**
- JFreeChart for visualization
- Apache Commons for utilities

#### Maven Configuration
```xml
<artifactId>opentsx-core</artifactId>
<packaging>jar</packaging>

<dependencies>
    <dependency>
        <groupId>org.apache.commons</groupId>
        <artifactId>commons-math3</artifactId>
        <version>3.6.1</version>
    </dependency>
    <dependency>
        <groupId>org.jfree</groupId>
        <artifactId>jfreechart</artifactId>
        <version>1.0.19</version>
    </dependency>
    <!-- + many more -->
</dependencies>
```

#### Size & Complexity
- **Packages:** 16
- **Source Files:** 100+ Java classes
- **Lines of Code:** ~15,000+
- **Test Coverage:** Jacoco configured

---

### opentsx-data
**Location:** `opentsx-data/`
**Purpose:** Avro schema definitions and data models
**Status:** Active (in root POM)

#### Avro Schemas

##### episode.avsc
```json
{
  "type": "record",
  "name": "EpisodesRecord",
  "namespace": "org.opentsx.data.avro",
  "fields": [
    {"name": "observations", "type": {"type": "array", "items": "double"}},
    {"name": "timestamps", "type": {"type": "array", "items": "long"}},
    {"name": "label", "type": "string"},
    {"name": "metadata", "type": {"type": "map", "values": "string"}}
  ]
}
```

**Usage:** Event aggregation, time series episodes

##### event.avsc
```json
{
  "type": "record",
  "name": "EventRecord",
  "fields": [
    {"name": "timestamp", "type": "long"},
    {"name": "value", "type": "double"},
    {"name": "eventType", "type": "string"}
  ]
}
```

**Usage:** Individual events in Kafka topics

##### episode2.avsc, episode3.avsc
Alternative episode schemas with different field configurations.

##### event-series.avsc
Schema for series of related events.

#### Maven Configuration
```xml
<artifactId>opentsx-data</artifactId>
<packaging>jar</packaging>

<dependencies>
    <dependency>
        <groupId>org.apache.commons</groupId>
        <artifactId>commons-text</artifactId>
        <version>1.9</version>
    </dependency>
</dependencies>
```

#### Generated Code
Avro Maven plugin generates Java classes from schemas:
- `EpisodesRecord.java`
- `EventRecord.java`
- Builder patterns for object construction
- Serialization/deserialization methods

---

## Data & Connectivity Modules

### opentsx-connectors
**Location:** `opentsx-connectors/`
**Purpose:** Kafka integration and data connectivity
**Status:** Active (in root POM)

#### Key Classes

##### KafkaConnector.java
**Purpose:** Base abstraction for Kafka connectivity

**Features:**
- Producer/consumer factory
- Configuration management
- Topic lifecycle management

##### TSOProducer.java
**Purpose:** Publish TimeSeriesObject to Kafka

**Features:**
- Avro serialization
- Schema Registry integration
- Configurable batching
- Async/sync sending modes

**Example:**
```java
TSOProducer producer = new TSOProducer("config/cpl.props");
producer.send("OpenTSx_Episodes_A", tso);
```

##### TSOConsumer.java
**Purpose:** Consume TimeSeriesObject from Kafka

**Features:**
- Avro deserialization
- Consumer group management
- Offset management
- Error handling

**Example:**
```java
TSOConsumer consumer = new TSOConsumer("config/cpl.props");
consumer.subscribe("OpenTSx_Episodes_A");
while (true) {
    ConsumerRecords<String, TimeSeriesObject> records = consumer.poll();
    // process records
}
```

##### EventFlowStateProducer.java
**Purpose:** Track event flow state across system

**Features:**
- State transitions
- Checkpoint management
- Flow monitoring

##### TopicsManagerTool.java
**Purpose:** Kafka topic administration

**Features:**
- Create/delete topics
- Partition management
- Configuration updates
- Topic listing

**Usage:**
```java
TopicsManagerTool manager = new TopicsManagerTool(adminProps);
manager.createTopic("new_topic", 10, (short) 3);
```

##### OpenTSxClusterLink.java
**Purpose:** Multi-cluster coordination

**Features:**
- Cross-cluster communication
- Data replication coordination
- Cluster health monitoring

#### Sub-packages

##### connectors/klatency/
**Purpose:** Latency measurement tools

**Classes:**
- Latency benchmark producer/consumer
- Round-trip time measurement
- Latency histogram generation

##### connectors/kping/
**Purpose:** Network connectivity testing

**Classes:**
- Kafka-based ping/pong
- Network diagnostics
- Availability monitoring

##### connectors/topicmanager/
**Purpose:** Advanced topic management

**Features:**
- Batch topic operations
- Configuration templates
- Topic migration utilities

#### Maven Dependencies
```xml
<dependencies>
    <dependency>
        <groupId>org.apache.kafka</groupId>
        <artifactId>kafka-clients</artifactId>
        <version>2.3.0</version>
    </dependency>
    <dependency>
        <groupId>io.confluent</groupId>
        <artifactId>kafka-avro-serializer</artifactId>
        <version>7.3.0</version>
    </dependency>
    <dependency>
        <groupId>org.opentsx</groupId>
        <artifactId>opentsx-data</artifactId>
        <version>3.0.0</version>
    </dependency>
</dependencies>
```

---

### opentsx-lg (Large Generator)
**Location:** `opentsx-lg/`
**Purpose:** Time series data generation and injection
**Status:** Active (in root POM)

#### Main Class: TSDataSineWaveGenerator.java

**Size:** ~11KB
**Purpose:** Primary data generator with GUI and headless modes

**Features:**
- Sine wave generation (configurable amplitude, frequency, phase)
- Multiple simultaneous series
- Kafka integration (optional)
- GUI monitoring dashboard
- Metrics collection
- Configurable output topics

**Configuration:**
- `OPENTSX_TOPIC_MAP_FILE_NAME` - Topic definitions
- `OPENTSX_PRIMARY_CLUSTER_CLIENT_CFG_FILE_NAME` - Kafka config
- `OPENTSX_SHOW_GUI` - Enable/disable GUI
- `OPENTSX_USE_KAFKA` - Enable/disable Kafka output

**Usage:**
```bash
export OPENTSX_TOPIC_MAP_FILE_NAME=../config/topiclist.def
export OPENTSX_PRIMARY_CLUSTER_CLIENT_CFG_FILE_NAME=../config/cpl.props
export OPENTSX_SHOW_GUI=true
export OPENTSX_USE_KAFKA=true

mvn exec:java -Dexec.mainClass="org.opentsx.lg.TSDataSineWaveGenerator"
```

#### Sub-packages

##### lg/analyser/
**Purpose:** Real-time analysis of generated data

**Features:**
- Statistical summaries
- Distribution analysis
- Quality metrics

##### lg/metrics/
**Purpose:** Generator performance metrics

**Features:**
- Events per second
- Latency tracking
- Resource utilization

#### Docker Support

**Profile:** Docker
**Image:** `opentsx/time-series-generator:3.0.0`
**Base:** `confluentinc/cp-base`

**Features:**
- VNC support for GUI access (port 5901)
- X11 forwarding
- Environment-driven configuration
- Volume mounts for config files

**Build:**
```bash
mvn clean package -PSimpleTimeSeriesProducer,Docker
```

**Run:**
```bash
docker run -d \
  -e OPENTSX_SHOW_GUI=true \
  -e OPENTSX_USE_KAFKA=true \
  -v $(pwd)/config:/config \
  opentsx/time-series-generator:3.0.0
```

#### Maven Configuration
```xml
<profiles>
    <profile>
        <id>SimpleTimeSeriesProducer</id>
        <build>
            <plugins>
                <plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-shade-plugin</artifactId>
                    <configuration>
                        <transformers>
                            <transformer implementation="...">
                                <mainClass>org.opentsx.lg.TSDataSineWaveGenerator</mainClass>
                            </transformer>
                        </transformers>
                    </configuration>
                </plugin>
            </plugins>
        </build>
    </profile>

    <profile>
        <id>Docker</id>
        <build>
            <plugins>
                <plugin>
                    <groupId>io.fabric8</groupId>
                    <artifactId>docker-maven-plugin</artifactId>
                    <!-- Docker build configuration -->
                </plugin>
            </plugins>
        </build>
    </profile>
</profiles>
```

---

## Stream Processing Modules

### opentsx-kafka-streams-tsa
**Location:** `opentsx-kafka-streams-tsa/`
**Purpose:** KStreams applications for time series aggregation
**Status:** Optional (commented in root POM)

#### Main Class: StateStoreExample4.java

**Purpose:** Aggregate events into TimeSeriesObjects using session windows

**Topology:**
```
Events Topic (Avro)
    ↓
Session Windowing (configurable gap)
    ↓
Grouping & Aggregation
    ↓
State Store (RocksDB or Cassandra)
    ↓
TimeSeriesObjects Topic (Avro)
```

**Code Example:**
```java
StreamsBuilder builder = new StreamsBuilder();

KStream<String, EventRecord> events = builder.stream("OpenTSx_Events");

events
    .groupByKey()
    .windowedBy(SessionWindows.with(Duration.ofMinutes(5)))
    .aggregate(
        () -> new EpisodesRecord(),
        (key, event, episode) -> {
            episode.getObservations().add(event.getValue());
            episode.getTimestamps().add(event.getTimestamp());
            return episode;
        },
        Materialized.as("episodes-store")
    )
    .toStream()
    .to("OpenTSx_Episodes_A");
```

#### Packages

##### org.semanpix.kstreams.quickstart
**Purpose:** Quick start examples and tutorials

##### org.semanpix.kstreams.tsa
**Purpose:** Production-ready TSA stream applications

**Features:**
- Session windowing for event aggregation
- Tumbling windows for fixed intervals
- Hopping windows for overlapping analysis
- State store management
- Error handling and recovery

#### Dependencies
```xml
<dependencies>
    <dependency>
        <groupId>org.apache.kafka</groupId>
        <artifactId>kafka-streams</artifactId>
        <version>2.3.0</version>
    </dependency>
    <dependency>
        <groupId>org.opentsx</groupId>
        <artifactId>opentsx-data</artifactId>
        <version>3.0.0</version>
    </dependency>
</dependencies>
```

---

### opentsx-ksql
**Location:** `opentsx-ksql/`
**Purpose:** ksqlDB integration and streaming SQL
**Status:** Optional

#### opentsx-ksql-udf (demo-udf)
**Location:** `opentsx-ksql-udf/demo-udf/`
**Purpose:** Custom ksqlDB User Defined Functions

##### Key UDFs

###### EpisodesProcessor.java
**Type:** UDF (Scalar function)
**Purpose:** Process episode records in ksqlDB queries

**Example:**
```sql
SELECT
    episode_id,
    EPISODES_PROCESSOR(observations, timestamps) AS processed
FROM episodes_stream;
```

###### SummaryStatsUdaf.java
**Type:** UDAF (Aggregate function)
**Purpose:** Calculate summary statistics over windows

**Returns:**
- Mean
- Standard deviation
- Min/max values
- Count

**Example:**
```sql
SELECT
    sensor_id,
    SUMMARY_STATS(value) AS stats
FROM sensor_readings
WINDOW TUMBLING (SIZE 1 HOUR)
GROUP BY sensor_id;
```

###### EpisodesMetadataExtractor.java
**Type:** UDF
**Purpose:** Extract metadata fields from episodes

**Example:**
```sql
SELECT
    EXTRACT_METADATA(episode, 'source') AS source,
    EXTRACT_METADATA(episode, 'quality') AS quality
FROM episodes_stream;
```

###### ReverseUdf.java
**Type:** UDF (Example)
**Purpose:** String reversal demonstration

#### Deployment

**Installation:**
```bash
# Build UDF JAR
cd opentsx-ksql-udf/demo-udf
mvn clean package

# Copy to ksqlDB extensions directory
cp target/demo-udf-1.0-SNAPSHOT.jar /etc/ksql/ext/

# Restart ksqlDB server
```

**Usage in ksqlDB:**
```sql
-- List available functions
SHOW FUNCTIONS;

-- Use custom UDF
SELECT REVERSE('OpenTSx') FROM stream;
```

#### Maven Configuration
```xml
<dependencies>
    <dependency>
        <groupId>io.confluent.ksql</groupId>
        <artifactId>ksql-udf</artifactId>
        <version>5.4.0</version>
    </dependency>
</dependencies>
```

---

### opentsx-processors
**Location:** `opentsx-processors/`
**Purpose:** Stream processor implementations
**Status:** Active (in root POM)
**Current State:** Minimal implementation

---

## Storage Modules

### opentsx-store-cassandra
**Location:** `opentsx-store-cassandra/`
**Purpose:** Apache Cassandra persistence layer
**Status:** Optional

#### Key Classes

##### CassandraConnector.java
**Purpose:** Manage Cassandra cluster connections

**Features:**
- Cluster configuration
- Session management
- Connection pooling
- SSL/TLS support

**Example:**
```java
CassandraConnector connector = new CassandraConnector();
connector.connect("192.168.3.5", 9042);
Session session = connector.getSession();
```

##### TSOWriter4Cassandra.java
**Purpose:** Write TimeSeriesObject to Cassandra

**Schema:**
```sql
CREATE TABLE time_series (
    series_id text,
    timestamp bigint,
    value double,
    metadata map<text, text>,
    PRIMARY KEY (series_id, timestamp)
) WITH CLUSTERING ORDER BY (timestamp DESC);
```

**Features:**
- Batch writes
- Async writes
- Prepared statements
- TTL support

**Example:**
```java
TSOWriter4Cassandra writer = new TSOWriter4Cassandra(session);
writer.write(tso);
writer.flush();
```

##### TSOReader4Cassandra.java
**Purpose:** Read TimeSeriesObject from Cassandra

**Features:**
- Time range queries
- Series filtering
- Pagination
- Parallel reads

**Example:**
```java
TSOReader4Cassandra reader = new TSOReader4Cassandra(session);
List<TimeSeriesObject> series = reader.read(
    "sensor_1",
    startTime,
    endTime
);
```

##### TSBucketLoader4Cassandra.java
**Purpose:** Load TSBucket collections from Cassandra

**Features:**
- Bulk loading
- Parallel loading
- Memory-efficient streaming
- Configurable batch size

##### CassandraTool.java
**Purpose:** Utility functions for Cassandra operations

**Features:**
- Schema creation
- Table management
- Data migration
- Cluster info

#### Configuration
```properties
cassandra.host=192.168.3.5
cassandra.port=9042
cassandra.keyspace=opentsx
cassandra.replication.factor=3
cassandra.consistency.level=QUORUM
```

#### Dependencies
```xml
<dependencies>
    <dependency>
        <groupId>com.datastax.cassandra</groupId>
        <artifactId>cassandra-driver-core</artifactId>
        <version>3.11.4</version>
    </dependency>
</dependencies>
```

---

### opentsx-store-opentsdb
**Location:** `opentsx-store-opentsdb/`
**Purpose:** OpenTSDB time series database integration
**Status:** Optional

#### Key Classes

##### OpenTSDBConnector.java
**Purpose:** Connect to OpenTSDB HTTP API

**Features:**
- HTTP/HTTPS support
- Connection pooling
- Retry logic
- Health checks

##### OpenTSDBWriter.java
**Purpose:** Write time series data points

**Data Format:**
```json
{
  "metric": "sensor.temperature",
  "timestamp": 1234567890,
  "value": 42.5,
  "tags": {
    "sensor_id": "sensor_1",
    "location": "datacenter1"
  }
}
```

**Features:**
- Batch writes
- Async writes
- Compression
- Error handling

**Example:**
```java
OpenTSDBWriter writer = new OpenTSDBWriter("http://localhost:4242");
writer.write("sensor.temperature", timestamp, value, tags);
```

##### OpenTSDBClient.java
**Purpose:** Full-featured OpenTSDB HTTP client

**Features:**
- Query API
- Aggregation functions
- Downsampling
- Tag filtering

**Query Example:**
```java
OpenTSDBClient client = new OpenTSDBClient("http://localhost:4242");
QueryResult result = client.query(
    "sensor.temperature",
    "1h-ago",
    "now",
    Aggregator.AVG,
    tags
);
```

##### OpenTSDBResponseTransformer.java
**Purpose:** Transform OpenTSDB JSON responses to TSO

**Features:**
- JSON parsing
- Type conversion
- Tag extraction
- Error handling

##### OpenTSDBWorkbenchDemo.java
**Purpose:** Demo application for OpenTSDB integration

**Features:**
- Sample data generation
- Write examples
- Query examples
- Visualization

##### OpenTSDBContainerStarter.java
**Purpose:** Docker container management for OpenTSDB

**Features:**
- Start/stop containers
- Configuration injection
- Health monitoring

#### Docker Deployment
```bash
# Start OpenTSDB container
java -jar opentsx-store-opentsdb.jar --start

# Or use script
bin/015_create_opentsdb_on_docker.sh
```

#### Dependencies
```xml
<dependencies>
    <dependency>
        <groupId>org.apache.httpcomponents</groupId>
        <artifactId>httpclient</artifactId>
        <version>4.5</version>
    </dependency>
    <dependency>
        <groupId>com.google.code.gson</groupId>
        <artifactId>gson</artifactId>
        <version>2.7</version>
    </dependency>
</dependencies>
```

---

### opentsx-kstreams-cassandra-state-store
**Location:** `opentsx-kstreams-cassandra-state-store/`
**Purpose:** Cassandra-backed KStreams state store
**Status:** Optional

#### Purpose
Replace default RocksDB state stores with distributed Cassandra storage.

#### Benefits
- **Persistence**: State survives application restarts
- **Scalability**: Leverage Cassandra's horizontal scaling
- **Multi-region**: State replication across datacenters
- **Large State**: No local disk limitations

#### Implementation
Custom `StateStore` implementation for KStreams.

**Example:**
```java
StreamsBuilder builder = new StreamsBuilder();

StoreBuilder<KeyValueStore<String, Long>> storeBuilder =
    Stores.keyValueStoreBuilder(
        new CassandraKeyValueBytesStoreSupplier("state-store"),
        Serdes.String(),
        Serdes.Long()
    );

builder.addStateStore(storeBuilder);
```

---

## Application & Demo Modules

### opentsx-app-demos
**Location:** `opentsx-app-demos/`
**Purpose:** Real-world application demonstrations
**Status:** Optional

#### 2019-apache-con-berlin/
**Location:** `opentsx-app-demos/2019-apache-con-berlin/`
**Purpose:** ApacheCon 2019 presentation demo

**Content:**
- KStreams TSO generation demo
- Presentation slides
- Sample data
- Documentation

**Main Class:** `org.semanpix.kstreams.StateStoreExample4`

**Demo Flow:**
1. Generate events
2. Aggregate via KStreams
3. Produce TimeSeriesObjects
4. Visualize in Grafana

#### ccloud-demo-09-2020/
**Location:** `opentsx-app-demos/ccloud-demo-09-2020/`
**Purpose:** Confluent Cloud integration demo

**Features:**
- Cloud-native deployment
- Schema Registry integration
- Managed Kafka usage
- Multi-region setup

**Configuration:** `config/ccloud.props`

#### meetup-Q4-2020/
**Location:** `opentsx-app-demos/meetup-Q4-2020/`
**Purpose:** Meetup presentation demo

**Features:**
- Apache Superset integration (submodule)
- Data visualization
- Interactive dashboards
- Real-time updates

**Submodule:** `superset-frontend`

---

### opentsx-cloud-bridge
**Location:** `opentsx-cloud-bridge/`
**Purpose:** Cloud platform integration
**Status:** Optional

**Supported Platforms:**
- Confluent Cloud
- AWS MSK
- Azure Event Hubs
- GCP Pub/Sub (planned)

**Features:**
- Unified connector API
- Cloud-specific optimizations
- Cost tracking
- Multi-cloud deployments

---

## Specialized Modules

### opentsx-predict
**Location:** `opentsx-predict/`
**Purpose:** TensorFlow-based ML prediction
**Status:** Active (in root POM)

#### Dependencies
- TensorFlow 1.15.0 (Core)
- TensorFlow JNI
- TensorFlow JNI-GPU (optional)
- Protocol Buffers

#### Features
- Load pre-trained TensorFlow models
- Real-time inference on time series
- GPU acceleration support
- Batch prediction

#### Usage
```java
TensorFlowPredictor predictor = new TensorFlowPredictor("model.pb");
double prediction = predictor.predict(timeSeriesData);
```

**README:** Minimal documentation currently

---

### opentsx-hive-udf
**Location:** `opentsx-hive-udf/`
**Purpose:** Hive User Defined Functions for SQL on Hadoop
**Status:** Optional

**Features:**
- Time series functions in HiveQL
- Statistical aggregations
- Custom operators

**Example:**
```sql
SELECT
    sensor_id,
    TS_MEAN(value) AS avg_value,
    TS_STDDEV(value) AS stddev
FROM sensor_data
GROUP BY sensor_id;
```

---

### opentsx-clusters
**Location:** `opentsx-clusters/`
**Purpose:** Multi-region and multi-datacenter configurations
**Status:** Optional

#### cp-multiregion/
**Purpose:** Confluent Platform multi-region deployment

**Features:**
- Kafka clusters spanning multiple regions
- Cross-region replication
- Locality-aware routing

**Configuration Files:**
- Docker Compose setups
- Grafana dashboards (Consumer, Producer, Kafka, Zookeeper)
- Network configurations

#### inhouse-mrc/mdc2/
**Purpose:** In-house multi-region, multi-datacenter setup

**Deployment Patterns:**
- Synchronous replication
- Asynchronous replication
- Reverse replication

#### mmdc-mrc/mdc2/
**Purpose:** Mirror multi-datacenter setup

**Features:**
- MirrorMaker 2.0 configuration
- Active-active deployments
- Conflict resolution strategies

---

### ext-algorithms/
**Location:** `ext-algorithms/`
**Purpose:** Extended and experimental algorithms
**Status:** Optional

**Content:**
- Research algorithms
- Experimental features
- Third-party algorithm integrations

---

### modules/
**Location:** `modules/`
**Purpose:** Additional utility modules
**Status:** Optional

---

## Module Build Status

### Active Modules (in root POM)
```xml
<modules>
    <module>opentsx-data</module>
    <module>opentsx-core</module>
    <module>opentsx-connectors</module>
    <module>opentsx-lg</module>
    <module>opentsx-predict</module>
    <module>opentsx-processors</module>
</modules>
```

### Commented/Optional Modules
```xml
<!-- Currently disabled in build -->
<!-- <module>opentsx-ksql-udf/demo-udf</module> -->
<!-- <module>opentsx-kafka-streams-tsa</module> -->
<!-- <module>opentsx-ksql-app</module> -->
<!-- <module>opentsx-spring-example</module> -->
```

**Reason:** Selective builds for faster development cycles

### Build All Modules
```bash
# Build only active modules
mvn clean install

# Build specific module
cd opentsx-core
mvn clean install

# Build with all optional modules
# (uncomment in pom.xml first)
mvn clean install -DskipTests
```

---

## Module Dependencies Table

| Module | Depends On | Dependents |
|--------|-----------|------------|
| opentsx-data | (none) | All modules |
| opentsx-core | opentsx-data | All modules |
| opentsx-connectors | opentsx-core, opentsx-data | opentsx-lg, apps |
| opentsx-lg | opentsx-connectors | (none) |
| opentsx-kafka-streams-tsa | opentsx-data, Kafka Streams | (none) |
| opentsx-ksql-udf | ksqlDB, opentsx-data | (none) |
| opentsx-store-cassandra | opentsx-core | apps |
| opentsx-store-opentsdb | opentsx-core | apps |
| opentsx-predict | opentsx-core, TensorFlow | apps |

---

## Module Size Summary

| Module | Approx. Lines | Classes | Status |
|--------|--------------|---------|--------|
| opentsx-core | 15,000+ | 100+ | Active |
| opentsx-connectors | 3,000+ | 20+ | Active |
| opentsx-data | 500+ | 5+ schemas | Active |
| opentsx-lg | 2,000+ | 10+ | Active |
| opentsx-kafka-streams-tsa | 1,000+ | 5+ | Optional |
| opentsx-ksql-udf | 500+ | 4 | Optional |
| opentsx-store-cassandra | 1,500+ | 5 | Optional |
| opentsx-store-opentsdb | 1,000+ | 6 | Optional |

---

## Module Testing

Each module includes:
- **Unit Tests:** JUnit-based
- **Integration Tests:** Module-specific
- **Code Coverage:** Jacoco reports

**Run tests:**
```bash
# All active modules
mvn test

# Specific module
cd opentsx-core
mvn test

# With coverage
mvn clean test jacoco:report
```

---

## Conclusion

OpenTSx's modular architecture provides:
- **Flexibility**: Use only required modules
- **Maintainability**: Clear module boundaries
- **Extensibility**: Easy to add new modules
- **Testability**: Independent module testing
- **Scalability**: Deploy modules independently

For detailed feature documentation, see [FEATURES.md](FEATURES.md).
For deployment procedures, see [DEPLOYMENT.md](DEPLOYMENT.md).
