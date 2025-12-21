# OpenTSx - Cloud-Native Time Series Analysis Platform

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-1.8+-orange.svg)](https://www.java.com)
[![Maven](https://img.shields.io/badge/Maven-3.6+-green.svg)](https://maven.apache.org)
[![Kafka](https://img.shields.io/badge/Kafka-2.3.0-black.svg)](https://kafka.apache.org)

OpenTSx is an enterprise-grade, cloud-native Java platform for sophisticated time series analysis. Built on Apache Kafka and the Confluent ecosystem, it provides advanced algorithms, flexible storage backends, and seamless integration with modern data infrastructure.

![Simplified Architecture Overview](https://github.com/kamir/OpenTSx/blob/master/docs/charts-and-sketches/Generic%20TSA%20Use%20Case/Simplified%20Architecture%20Overview.png?raw=true "Simplified Architecture Overview")

---

## Related Work: Application of the OpenTSx toolbox:
- IJCS :
  *Hadoop.TS:* The initial paper.
  https://www.ijcaonline.org/archives/volume74/number17/12974-0233

- PLOS ONE :
  *The Detection of Emerging Trends Using Wikipedia Traffic Data and Context Networks.*
  https://journals.plos.org/plosone/article?id=10.1371/journal.pone.0141892
  
- (old) WIKI : 
  https://github.com/kamir/Hadoop.TS.NG/wiki

- DPG 2014 : 
  *Context Sensitive and Time Dependent Relevance of Wikipedia Articles*
  https://www.slideshare.net/mirkokaempf/dpg-2014-time-05-1

- Wikimedia Foundation : 
  *Comparing_the_usage_of_global_and_local_Wikipedias_with_focus_on_Swedish_Wikipedia*
  https://www.researchgate.net/publication/255704719_Comparing_the_usage_of_global_and_local_Wikipedias_with_focus_on_Swedish_Wikipedia

---

## Table of Contents
- [Key Features](#key-features)
- [Quick Start](#quick-start)
- [Getting Started & Onboarding](#getting-started--onboarding)
- [Architecture](#architecture)
- [Core Concepts](#core-concepts)
- [Algorithms](#algorithms)
- [Use Cases](#use-cases)
- [Documentation](#documentation)
- [Building from Source](#building-from-source)
- [Examples](#examples)
- [Contributing](#contributing)
- [Related Work](#related-work)
- [History](#history)
- [License](#license)

---

## Key Features

### Advanced Time Series Algorithms
- **Detrended Fluctuation Analysis (DFA/MFDFA)** - Long-range correlation detection
- **Event Synchronization** - Identify synchronized events across multiple time series
- **Return Interval Statistics (RIS)** - Extreme event and risk analysis
- **Statistical Analysis** - Entropy, Granger causality, distribution testing
- **Signal Processing** - FFT, peak detection, trend extraction
- **Dynamic Flows** - Configurable processing pipelines (PFD)


### Cloud-Native Architecture
- **Apache Kafka** - Event streaming backbone
- **KStreams** - Stream processing topologies
- **Apache Flink** - Dynamic job execution engine
- **ksqlDB** - SQL streaming queries with custom UDFs

- **Multi-Region Support** - Active-active and active-passive deployments
- **Containerized** - Docker images and Kubernetes ready

### Flexible Storage
- **Apache Cassandra** - Distributed, scalable time series storage
- **OpenTSDB** - Metrics-focused time series database
- **HDFS** - Batch processing and archival
- **Multiple Formats** - Avro, Parquet, SequenceFile

### Machine Learning Integration
- **TensorFlow** - Deep learning inference and prediction
- **Smile ML** - Statistical machine learning algorithms
- **Custom Models** - Pluggable ML model framework

### Developer Friendly
- **Modular Design** - Use only what you need
- **Rich APIs** - Java APIs for all components
- **Extensive Examples** - Demo applications and tutorials
- **Monitoring** - Grafana dashboards and Prometheus metrics

---

## Quick Start

### Prerequisites
- Java 1.8 or higher
- Maven 3.6+
- Apache Kafka 2.3+ (or Confluent Platform 7.3+)
- (Optional) Docker for containerized deployment

### Installation

#### 1. Clone the Repository
```bash
git clone https://github.com/kamir/OpenTSx.git
cd OpenTSx
```

#### 2. Build All Modules
```bash
# Build all active modules
./bin/010_build.sh

# Or use Maven directly
mvn clean install
```

#### 3. Configure Kafka Connection
```bash
# Edit Kafka configuration
cp config/cpl.props config/my-cluster.props
# Update bootstrap.servers and schema.registry.url
```

#### 4. Run Data Generator Example
```bash
cd opentsx-lg

# Set environment variables
export OPENTSX_TOPIC_MAP_FILE_NAME=../config/topiclist.def
export OPENTSX_PRIMARY_CLUSTER_CLIENT_CFG_FILE_NAME=../config/my-cluster.props
export OPENTSX_SHOW_GUI=true
export OPENTSX_USE_KAFKA=false  # Set to true for Kafka output

# Run the generator
mvn exec:java -Dexec.mainClass="org.opentsx.lg.TSDataSineWaveGenerator"
```

#### 5. Run Time Series Analysis
```java
import org.opentsx.algorithms.detrending.DFA;
import org.opentsx.data.TimeSeriesObject;

// Create or load time series data
TimeSeriesObject tso = ... // your data

// Perform DFA analysis
DFA dfa = new DFA();
dfa.setPolynomOrder(1);  // Linear detrending
double[] scales = {10, 20, 50, 100, 200};
double[] fluctuations = dfa.calc(tso.getData(), scales);

// Extract Hurst exponent (scaling exponent)
double hurstExponent = FitTool.fitSlope(scales, fluctuations);
System.out.println("Hurst Exponent: " + hurstExponent);
```

### Docker Quick Start

```bash
# Build Docker image for data generator
cd opentsx-lg
mvn clean package -PSimpleTimeSeriesProducer,Docker

# Run in container
docker run -d \
  --name opentsx-generator \
  -e OPENTSX_SHOW_GUI=true \
  -e OPENTSX_USE_KAFKA=true \
  -v $(pwd)/../config:/config \
  opentsx/time-series-generator:3.0.0
```

### SaaS Platform Quick Start

```bash
# Start full stack (Backend, Frontend, Flink integration, DBs)
# Note: Ensure frontend is built or removed from docker-compose if not needed/built
cd opentsx-saas-backend
docker-compose up -d --build

# Access UI
# Frontend: http://localhost:3000
# Backend API: http://localhost:8000/docs
```

---

## Getting Started & Onboarding

New to OpenTSx? We provide structured onboarding paths tailored to your background:

### 📚 Conceptual Documentation (GitBook Manual)

**Start here for conceptual understanding:**

→ **[OpenTSx Manual](docs/manual/README.md)** — Comprehensive conceptual guide

The manual covers:
- **[Introduction](docs/manual/introduction/README.md)** — What makes OpenTSx different and when to use it
- **[Core Concepts](docs/manual/core-concepts/README.md)** — TimeSeriesObject, data model, and design philosophy
- **[Data Operations](docs/manual/data-operations/README.md)** — Creating, loading, transforming time series
- **[Statistical Analysis](docs/manual/statistical-analysis/README.md)** — Built-in analytics and algorithms
- **[Best Practices](docs/manual/best-practices/README.md)** — Production patterns, error handling, testing
- **[API Reference](docs/manual/appendix/api-reference.md)** — Quick reference for common operations
- **[Glossary](docs/manual/appendix/glossary.md)** — Time series terminology and OpenTSx-specific terms

### 🛤️ Onboarding Paths

Choose your learning track based on your background:

#### 👨‍💻 Software Engineers (SWE Track)

**For developers new to time series analysis:**

```bash
# Episode 1: Environment Setup
./bin/010_build.sh                        # Build project
./bin/000_launch_tsa_workbench.sh        # Explore GUI

# Episode 2: Creating Time Series
./bin/episode_02_create_timeseries.sh

# Episode 3: Basic Operations
./bin/episode_03_basic_operations.sh

# Episode 10: Production Configuration
./bin/episode_10_production_config.sh
```

**Learning Path:**
1. [Core Concepts](docs/manual/core-concepts/README.md) — Understand TimeSeriesObject
2. [Data Operations](docs/manual/data-operations/README.md) — Learn creation and transformation
3. [Statistical Analysis](docs/manual/statistical-analysis/README.md) — Explore built-in analytics
4. [Best Practices](docs/manual/best-practices/README.md) — Production patterns

#### 📊 Time Series Experts (TSx Track)

**For data scientists coming from R/Python/MATLAB:**

```bash
# Episode 1: From Python/R to OpenTSx
./bin/010_build.sh                        # Build project
./bin/000_launch_tsa_workbench.sh        # Visual exploration

# Episode 9: Statistical Analysis Patterns
./bin/episode_09_analysis.sh
```

**Learning Path:**
1. [Translation Guide](docs/manual/appendix/r-python-translation.md) — Map R/Python concepts to OpenTSx
2. [Core Concepts](docs/manual/core-concepts/README.md) — Java-specific patterns
3. [Statistical Analysis](docs/manual/statistical-analysis/README.md) — Built-in methods
4. [Integration](docs/manual/integration/README.md) — Export to your preferred tools

### 🚀 Local Development Environment

**Start the complete infrastructure for testing:**

```bash
# Start Kafka, OpenTSDB, and databases
docker-compose -f docker-compose.local.yml up -d

# Validate environment
./bin/000_validate_environment.sh --strict

# Build project
./bin/010_build.sh
```

See **[Local Development Guide](docs/infrastructure/local-development.md)** for:
- CP-ALL-IN-ONE Kafka setup (KRaft mode, no Zookeeper)
- OpenTSDB and HBase configuration
- Kafka integration examples
- Troubleshooting and best practices

### 📖 Demo Scripts

All demo scripts are located in `bin/` with comprehensive documentation:

| Episode | Script | Topic | Audience |
|---------|--------|-------|----------|
| E02 | `episode_02_create_timeseries.sh` | Creating time series | SWE + TSx |
| E03 | `episode_03_basic_operations.sh` | Transformations & operations | SWE + TSx |
| E09 | `episode_09_analysis.sh` | Statistical analysis | TSx |
| E10 | `episode_10_production_config.sh` | Production patterns | SWE |

See **[Script Reference](bin/README.md)** for complete documentation.

### 📂 Sample Data

Ready-to-use datasets for hands-on learning:

```
sample_data/
├── sensor_data.csv       # IoT warehouse monitoring (24h)
├── stock_prices.tsv      # Financial OHLCV data
├── weather_data.csv      # Environmental metrics
└── network_metrics.csv   # Infrastructure monitoring
```

### 🎯 Quick Learning Paths

**60 Minutes to Productive:**
1. Read [Introduction](docs/manual/introduction/README.md) (10 min)
2. Run [Episode 2](bin/episode_02_create_timeseries.sh) (15 min)
3. Run [Episode 3](bin/episode_03_basic_operations.sh) (15 min)
4. Review [API Reference](docs/manual/appendix/api-reference.md) (20 min)

**4 Hours to Proficient:**
1. Complete 60-minute path
2. Read [Core Concepts](docs/manual/core-concepts/README.md) (45 min)
3. Read [Data Operations](docs/manual/data-operations/README.md) (45 min)
4. Run all demo scripts (60 min)
5. Review [Best Practices](docs/manual/best-practices/README.md) (30 min)

**2 Days to Expert:**
1. Complete 4-hour path
2. Read entire [GitBook Manual](docs/manual/README.md) (4 hours)
3. Set up local infrastructure (1 hour)
4. Build a custom demo application (3 hours)
5. Review advanced topics and production patterns (remaining time)

---

## Architecture

OpenTSx implements a **layered, event-driven architecture** optimized for both real-time streaming and batch processing:

```
┌─────────────────────────────────────────────────────────────┐
│         Applications & Demos                                 │
│  (Custom Apps, Visualization, ML Models)                     │
└──────────────────────────┬──────────────────────────────────┘
                           ↓
┌─────────────────────────────────────────────────────────────┐
│         Stream Processing & Query Layer                      │
│  (KStreams, ksqlDB, Apache Flink Dynamic Jobs)               │
└──────────────────────────┬──────────────────────────────────┘
                           ↓
┌─────────────────────────────────────────────────────────────┐
│         Analysis & Algorithm Layer                           │
│  (DFA, MFDFA, Event Sync, RIS, Statistics)                  │
└──────────────────────────┬──────────────────────────────────┘
                           ↓
┌─────────────────────────────────────────────────────────────┐
│         Data Model & Serialization                           │
│  (Avro Schemas, TSBucket, TimeSeriesObject)                 │
└──────────────────────────┬──────────────────────────────────┘
                           ↓
┌─────────────────────────────────────────────────────────────┐
│         Connectivity Layer                                   │
│  (Kafka Producers/Consumers, Connectors)                     │
└──────────────────────────┬──────────────────────────────────┘
                           ↓
┌─────────────────────────────────────────────────────────────┐
│         Persistence Layer                                    │
│  (Cassandra, OpenTSDB, HDFS, HBase, Kudu)                   │
└─────────────────────────────────────────────────────────────┘
```

### Data Flow

```
Events → Kafka Topics
         ↓
    KStreams Aggregation (Session Windows)
         ↓
    Time Series Objects (TSOs)
         ↓
    Analysis Algorithms (DFA, RIS, Event Sync, etc.)
         ↓
    Storage (Cassandra / OpenTSDB / HDFS)
         ↓
    Visualization (Grafana / JFreeChart)
```

**For detailed architecture documentation, see [ARCHITECTURE.md](ARCHITECTURE.md)**

---

## Core Concepts

### The Abstraction Layer

OpenTSx provides a unified abstraction layer for time series analysis in cloud-native applications. The essential building blocks are:

#### 1. TimeSeriesObject (TSO)
Individual time series with metadata:
```java
TimeSeriesObject tso = new TimeSeriesObject();
tso.setLabel("sensor_1");
tso.addValue(timestamp, value);
tso.addMetadata("location", "datacenter1");
```

#### 2. TSBucket
Container for grouped time series:
```java
TSBucket bucket = new TSBucket();
bucket.addTS(tso1);
bucket.addTS(tso2);

// Save to storage
bucket.save("hdfs://path", TSBucket.Format.AVRO);
```

#### 3. TSProcessor
Processing abstraction for algorithms:
```java
public interface TSProcessor {
    TSBucket process(TSBucket input);
}

// Implement custom processing
class MyProcessor implements TSProcessor {
    public TSBucket process(TSBucket input) {
        // Custom analysis logic
        return output;
    }
}
```

#### 4. Episodes (Avro Schema)
Schema-based event aggregation:
```json
{
  "type": "record",
  "name": "EpisodesRecord",
  "fields": [
    {"name": "observations", "type": {"type": "array", "items": "double"}},
    {"name": "timestamps", "type": {"type": "array", "items": "long"}},
    {"name": "label", "type": "string"},
    {"name": "metadata", "type": {"type": "map", "values": "string"}}
  ]
}
```

---

## Algorithms

OpenTSx includes state-of-the-art time series analysis algorithms:

### Detrended Fluctuation Analysis (DFA)
Detect long-range correlations in non-stationary time series.

**Applications:** Financial markets, climate data, physiological signals

```java
DFA dfa = new DFA();
dfa.setPolynomOrder(1);
double hurstExponent = dfa.calculate(timeSeries);
// H > 0.5: persistent (trending)
// H = 0.5: random walk
// H < 0.5: anti-persistent (mean-reverting)
```

### Multifractal DFA (MFDFA)
Analyze multifractal properties and complexity.

**Applications:** Market microstructure, turbulence, network traffic

```java
MFDFA mfdfa = new MFDFA();
MultifractalSpectrum spectrum = mfdfa.calculate(timeSeries, qValues);
```

### Event Synchronization
Quantify synchronized events between time series.

**Applications:** Climate teleconnections, neural networks, social dynamics

```java
ESCalc esCalc = new ESCalc();
SyncResult result = esCalc.calculate(series1, series2);
double syncIndex = result.getSynchronizationIndex();
```

### Return Interval Statistics (RIS)
Analyze extreme events and risk.

**Applications:** Earthquake analysis, financial risk, extreme weather

```java
RISTool risTool = new RISTool();
RISResult result = risTool.analyze(timeSeries, threshold);
```

### Statistical Analysis
- **Entropy**: Shannon, approximate, sample, permutation
- **Distribution Testing**: Shapiro-Wilk, Kolmogorov-Smirnov
- **Granger Causality**: Causal relationship detection
- **Normalization**: Z-score, min-max, robust scaling

**For complete algorithm documentation, see [FEATURES.md](FEATURES.md)**

---

## Use Cases

### 1. Financial Market Analysis
- High-frequency trading signal analysis
- Market regime detection
- Volatility clustering
- Risk assessment with RIS

### 2. IoT & Sensor Data Processing
- Real-time anomaly detection
- Predictive maintenance
- Energy consumption optimization
- Environmental monitoring

### 3. Climate & Weather Analysis
- Teleconnection detection
- Extreme event analysis
- Long-term trend identification
- Multi-variate correlation analysis

### 4. Network Operations
- Traffic pattern analysis
- Capacity planning
- Anomaly detection
- Performance monitoring

### 5. Healthcare & Bioinformatics
- ECG/EEG signal analysis
- Patient monitoring
- Drug efficacy studies
- Epidemiological analysis

### 6. Social Media & Web Analytics
- Trend detection (Wikipedia clickstream)
- Event impact analysis
- User behavior patterns
- Content recommendation

---

## Documentation

OpenTSx provides comprehensive documentation for all experience levels:

### 🎓 Getting Started (Recommended)

- **[OpenTSx Manual](docs/manual/README.md)** — Complete conceptual guide (GitBook style)
  - [Introduction](docs/manual/introduction/README.md) — Philosophy and when to use OpenTSx
  - [Core Concepts](docs/manual/core-concepts/README.md) — TimeSeriesObject and data model
  - [Data Operations](docs/manual/data-operations/README.md) — Creating and transforming data
  - [Statistical Analysis](docs/manual/statistical-analysis/README.md) — Built-in analytics
  - [Best Practices](docs/manual/best-practices/README.md) — Production patterns
  - [API Reference](docs/manual/appendix/api-reference.md) — Quick reference guide
  - [Glossary](docs/manual/appendix/glossary.md) — Terminology reference
- **[Local Development Guide](docs/infrastructure/local-development.md)** — Docker Compose setup
- **[Demo Scripts Reference](bin/README.md)** — Executable onboarding examples

### 🏗️ Architecture & Technical Documentation

- **[ARCHITECTURE.md](ARCHITECTURE.md)** — System architecture, design patterns, technology stack
- **[MODULES.md](MODULES.md)** — Complete module documentation with APIs
- **[FEATURES.md](FEATURES.md)** — Feature overview and algorithms
- **[DEPLOYMENT.md](DEPLOYMENT.md)** — Deployment procedures and configuration
- **[SECURITY.md](SECURITY.md)** — OWASP security analysis and recommendations

### 📚 Tutorials & Examples

- **[Demo Scripts](bin/)** — Structured onboarding episodes (E02, E03, E09, E10)
- **Application Demos** — `opentsx-app-demos/` — Real-world examples
- **Algorithm Tutorials** — `docs/manuals/drafts/` — Algorithm guides
- **Core Demos** — `opentsx-core/demo/` — Algorithm demonstrations

---

## Building from Source

### Full Build

```bash
# Clone repository
git clone https://github.com/kamir/OpenTSx.git
cd OpenTSx

# Build all active modules
mvn clean install

# Build with tests
mvn clean test

# Build without tests (faster)
mvn clean install -DskipTests

# Generate code coverage report
mvn clean test jacoco:report
```

### Module-Specific Build

```bash
# Build only core module
cd opentsx-core
mvn clean install

# Build data generator
cd opentsx-lg
mvn clean package -PSimpleTimeSeriesProducer

# Build with Docker image
cd opentsx-lg
mvn clean package -PSimpleTimeSeriesProducer,Docker
```

### Build Scripts

```bash
# Use convenience scripts
./bin/010_build.sh                    # Build all modules
./bin/001_build_containers.sh         # Build Docker containers
./bin/110_run_demo_services.sh        # Start demo services
```

---

## Examples

### Example 1: DFA Analysis

```java
import org.opentsx.algorithms.detrending.DFA;
import org.opentsx.data.TimeSeriesObject;
import org.opentsx.analysistools.LogBinningTool;

public class DFAExample {
    public static void main(String[] args) {
        // Load or generate time series
        TimeSeriesObject tso = loadTimeSeries("data.csv");

        // Configure DFA
        DFA dfa = new DFA();
        dfa.setPolynomOrder(1);  // Linear detrending

        // Create logarithmic scales
        double[] scales = LogBinningTool.createScales(10, 1000, 20);

        // Calculate fluctuation function
        double[] fluctuations = dfa.calc(tso.getData(), scales);

        // Fit and extract Hurst exponent
        double alpha = FitTool.fitSlope(
            Math.log(scales),
            Math.log(fluctuations)
        );

        System.out.println("Scaling exponent (α): " + alpha);

        // Interpret results
        if (alpha > 0.5) {
            System.out.println("Persistent (trending) behavior");
        } else if (alpha < 0.5) {
            System.out.println("Anti-persistent (mean-reverting)");
        } else {
            System.out.println("Random walk behavior");
        }
    }
}
```

### Example 2: KStreams Event Aggregation

```java
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.kstream.*;
import org.opentsx.data.avro.EventRecord;
import org.opentsx.data.avro.EpisodesRecord;

public class EventAggregationExample {
    public static void main(String[] args) {
        StreamsBuilder builder = new StreamsBuilder();

        // Stream of individual events
        KStream<String, EventRecord> events =
            builder.stream("OpenTSx_Events");

        // Aggregate events into episodes using session windows
        events
            .groupByKey()
            .windowedBy(SessionWindows.with(Duration.ofMinutes(5)))
            .aggregate(
                () -> new EpisodesRecord(),
                (key, event, episode) -> {
                    episode.getObservations().add(event.getValue());
                    episode.getTimestamps().add(event.getTimestamp());
                    episode.setLabel(event.getEventType());
                    return episode;
                },
                Materialized.as("episodes-store")
            )
            .toStream()
            .to("OpenTSx_Episodes");

        // Start the stream
        KafkaStreams streams = new KafkaStreams(builder.build(), config);
        streams.start();
    }
}
```

### Example 3: ksqlDB Custom UDF

```sql
-- Create stream from Kafka topic
CREATE STREAM sensor_data (
    sensor_id VARCHAR KEY,
    value DOUBLE,
    timestamp BIGINT
) WITH (
    KAFKA_TOPIC='sensor_readings',
    VALUE_FORMAT='AVRO',
    TIMESTAMP='timestamp'
);

-- Calculate summary statistics using custom UDAF
CREATE TABLE sensor_stats AS
SELECT
    sensor_id,
    SUMMARY_STATS(value) AS stats,
    COUNT(*) AS reading_count
FROM sensor_data
WINDOW TUMBLING (SIZE 1 HOUR)
GROUP BY sensor_id;

-- Query the materialized table
SELECT * FROM sensor_stats
WHERE sensor_id = 'sensor_1';
```

### Example 4: Multi-Storage Backend

```java
import org.opentsx.store.cassandra.TSOWriter4Cassandra;
import org.opentsx.store.opentsdb.OpenTSDBWriter;

public class MultiStorageExample {
    public static void main(String[] args) {
        TimeSeriesObject tso = generateData();

        // Write to Cassandra
        TSOWriter4Cassandra cassandraWriter =
            new TSOWriter4Cassandra(cassandraSession);
        cassandraWriter.write(tso);

        // Simultaneously write to OpenTSDB for monitoring
        OpenTSDBWriter opentsdbWriter =
            new OpenTSDBWriter("http://opentsdb:4242");
        Map<String, String> tags = new HashMap<>();
        tags.put("sensor_id", tso.getLabel());
        opentsdbWriter.write("sensor.value",
            tso.getTimestamps(),
            tso.getValues(),
            tags);
    }
}
```

**More examples:** See `opentsx-app-demos/` directory

---

## Project Structure

```
OpenTSx/
├── opentsx-core/              # Core algorithms & data structures
├── opentsx-data/              # Avro schemas & data models
├── opentsx-connectors/        # Kafka integration
├── opentsx-lg/                # Time series data generator
├── opentsx-predict/           # TensorFlow ML integration
├── opentsx-flink-core/        # Flink dynamic execution engine
├── opentsx-saas-backend/      # FastAPI SaaS Control Plane
├── opentsx-saas-frontend/     # React/Vite User Interface
├── opentsx-kafka-streams-tsa/ # KStreams applications
├── opentsx-ksql-udf/          # ksqlDB custom functions
├── opentsx-store-cassandra/   # Cassandra persistence
├── opentsx-store-opentsdb/    # OpenTSDB persistence
├── opentsx-clusters/          # Multi-region configs
├── opentsx-app-demos/         # Demo applications
├── config/                    # Configuration files
├── bin/                       # Build & deployment scripts
├── docs/                      # Documentation & tutorials
└── pom.xml                    # Root Maven POM
```

**For complete module documentation, see [MODULES.md](MODULES.md)**

---

## Why Use OpenTSx?

### Rapid Prototyping
- Pre-built algorithms and data structures
- Rich examples and demos
- Flexible architecture

### Production Ready
- Battle-tested in real-world deployments
- Enterprise-grade scalability
- Multi-region support
- Comprehensive monitoring

### Integration Ecosystem
- **Data Ingestion**: Kafka Connect, custom producers
- **Processing**: KStreams, ksqlDB, Spark, Flink
- **Storage**: Cassandra, OpenTSDB, HDFS, cloud storage
- **ML**: TensorFlow, Smile, Mahout
- **Visualization**: Grafana, JFreeChart, Superset
- **Metadata**: ElasticSearch, Neo4J, Apache Jena

### Cloud Native
- Docker containerization
- Kubernetes orchestration
- Multi-cloud support (AWS, Azure, GCP)
- Confluent Cloud integration

---

## Why Contribute to OpenTSx?

### Benefits
- **Shorten Development Cycles**: Reusable components and patterns
- **Learn Best Practices**: Cloud-native architecture and design patterns
- **Community**: Collaborate with time series analysis experts
- **Impact**: Contribute to open-source scientific computing

### Contribution Areas
- New time series algorithms
- Additional storage backends
- Cloud platform integrations
- Performance optimizations
- Documentation and tutorials
- Bug fixes and testing

**See [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines**

---

## Related Work

### Publications Using OpenTSx

#### IJCS: Hadoop.TS
*Initial paper on Hadoop-based time series analysis*
- [https://www.ijcaonline.org/archives/volume74/number17/12974-0233](https://www.ijcaonline.org/archives/volume74/number17/12974-0233)

#### PLOS ONE: Wikipedia Traffic Analysis
*The Detection of Emerging Trends Using Wikipedia Traffic Data and Context Networks*
- [https://journals.plos.org/plosone/article?id=10.1371/journal.pone.0141892](https://journals.plos.org/plosone/article?id=10.1371/journal.pone.0141892)

#### DPG 2014: Context Sensitive Analysis
*Context Sensitive and Time Dependent Relevance of Wikipedia Articles*
- [https://www.slideshare.net/mirkokaempf/dpg-2014-time-05-1](https://www.slideshare.net/mirkokaempf/dpg-2014-time-05-1)

#### Wikimedia Foundation Research
*Comparing the usage of global and local Wikipedias with focus on Swedish Wikipedia*
- [https://www.researchgate.net/publication/255704719](https://www.researchgate.net/publication/255704719_Comparing_the_usage_of_global_and_local_Wikipedias_with_focus_on_Swedish_Wikipedia)

### Related Projects
- **Hadoop.TS.NG**: Original Hadoop-based version (legacy)
  - [https://github.com/kamir/Hadoop.TS.NG/wiki](https://github.com/kamir/Hadoop.TS.NG/wiki)

---

## History

### Evolution from Hadoop.TS.NG to OpenTSx

**Phase 1: Hadoop-Based (2013-2018)**
- Built on Apache Hadoop ecosystem
- HBase via OpenTSDB for storage
- Spark for batch processing
- HDFS for data lake

**Phase 2: Kafka Migration (2018-2020)**
- Shifted from batch to streaming
- Apache Kafka as central nervous system
- KStreams for stream processing
- Real-time analysis capabilities

**Phase 3: Cloud-Native (2020-Present)**
- Docker containerization
- Kubernetes orchestration
- Multi-region deployments
- Confluent Cloud integration
- ksqlDB streaming SQL

### Key Milestones
- **2013**: Initial Hadoop.TS release
- **2015**: PLOS ONE publication on Wikipedia analysis
- **2018**: Renamed to OpenTSx, Kafka migration
- **2019**: ApacheCon Berlin presentation
- **2020**: ksqlDB integration, multi-region support
- **2024**: Version 3.0.0, comprehensive cloud-native features

### The Journey
OpenTSx embodies lessons learned from deploying large-scale time series analysis in production. The shift from Hadoop/Spark to Kafka enabled:
- **Real-time Processing**: Analyze data as it arrives
- **Simplified Data Wrangling**: Standardized data flows
- **Reduced Complexity**: Fewer moving parts
- **Better Scalability**: Kafka's distributed architecture

---

## Roadmap

### Version 3.1 (Q2 2025)
- [ ] Enhanced TensorFlow 2.x support
- [ ] Additional storage backends (InfluxDB, TimescaleDB)
- [ ] Improved Kubernetes deployment templates
- [ ] Performance optimizations for DFA/MFDFA
- [ ] Expanded ksqlDB UDF library

### Version 3.2 (Q4 2025)
- [ ] Python API wrapper
- [ ] REST API layer
- [ ] Web-based algorithm workbench
- [ ] Enhanced visualization components
- [ ] Auto-scaling policies

### Long-term Vision
- Unified data science platform for time series
- AI/ML model marketplace
- Managed SaaS offering
- Enhanced multi-cloud support

---

## Community & Support

### Getting Help
- **Documentation**: Start with [ARCHITECTURE.md](ARCHITECTURE.md), [MODULES.md](MODULES.md), [FEATURES.md](FEATURES.md)
- **Examples**: See `opentsx-app-demos/` directory
- **Issues**: [GitHub Issues](https://github.com/kamir/OpenTSx/issues)

### Contributing
We welcome contributions! See [CONTRIBUTING.md](CONTRIBUTING.md) for:
- Code style guidelines
- Pull request process
- Development setup
- Testing requirements

### Code of Conduct
OpenTSx follows the Apache Code of Conduct. Be respectful and professional.

---

## License

OpenTSx is licensed under the **Apache License 2.0**.

```
Copyright (c) 2013-2025 Mirko Kämpf and contributors

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```

---

## Acknowledgments

OpenTSx builds on many excellent open-source projects:
- Apache Kafka, KStreams, and the Confluent ecosystem
- Apache Cassandra, HBase, Hadoop
- Apache Spark, Flink
- TensorFlow, Smile ML
- JFreeChart, Grafana
- The entire Apache Software Foundation community

**Developed by:** Mirko Kämpf and contributors

**Maintained by:** The OpenTSx Community

---

## Quick Links

- **Documentation**: [ARCHITECTURE.md](ARCHITECTURE.md) | [MODULES.md](MODULES.md) | [FEATURES.md](FEATURES.md)
- **Deployment**: [DEPLOYMENT.md](DEPLOYMENT.md)
- **Security**: [SECURITY.md](SECURITY.md)
- **Code**: [GitHub Repository](https://github.com/kamir/OpenTSx)
- **Issues**: [GitHub Issues](https://github.com/kamir/OpenTSx/issues)

---

**Get Started Today!**

```bash
git clone https://github.com/kamir/OpenTSx.git
cd OpenTSx
./bin/010_build.sh
cd opentsx-lg && mvn exec:java -Dexec.mainClass="org.opentsx.lg.TSDataSineWaveGenerator"
```

**Happy Analyzing!** 📊📈




