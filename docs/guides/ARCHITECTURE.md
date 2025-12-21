# OpenTSx Architecture Overview

## Table of Contents
- [Introduction](#introduction)
- [High-Level Architecture](#high-level-architecture)
- [Architectural Patterns](#architectural-patterns)
- [System Components](#system-components)
- [Data Flow](#data-flow)
- [Technology Stack](#technology-stack)
- [Design Principles](#design-principles)
- [Scalability & Distribution](#scalability--distribution)

---

## Introduction

OpenTSx is a cloud-native, distributed time series analysis platform built on Apache Kafka and the Confluent ecosystem. It provides sophisticated algorithms for analyzing temporal data patterns, event synchronization, and statistical properties of time series data.

**Version:** 3.0.0
**License:** Apache Commons 2.0
**Primary Language:** Java 1.8+

---

## High-Level Architecture

OpenTSx follows a **layered, event-driven architecture** with clear separation of concerns:

```
┌─────────────────────────────────────────────────────────────┐
│              Applications & Demos Layer                      │
│  (Demo Apps, Custom Applications, Visualization Tools)       │
└─────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────┐
│           Stream Processing & Query Layer                    │
│     (KStreams Topologies, ksqlDB, Apache Flink)             │
└─────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────┐
│          Analysis & Algorithm Layer                          │
│  (DFA/MFDFA, Event Sync, RIS, Statistical Analysis)         │
└─────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────┐
│        Data Model & Serialization Layer                      │
│         (Avro Schemas, TSBucket, TimeSeriesObject)          │
└─────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────┐
│              Connectivity Layer                              │
│    (Kafka Producers/Consumers, Connectors, Topics)          │
└─────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────┐
│              Persistence Layer                               │
│    (Cassandra, OpenTSDB, HDFS, HBase, Kudu)                 │
└─────────────────────────────────────────────────────────────┘
```

---

## Architectural Patterns

### 1. Layered Architecture

OpenTSx implements a strict layered architecture where each layer depends only on layers below it:

- **Application Layer**: User-facing applications and demos
- **Processing Layer**: Stream and batch processing engines
- **Algorithm Layer**: Core time series analysis algorithms
- **Data Model Layer**: Schema definitions and data structures
- **Connectivity Layer**: Message broker integration
- **Persistence Layer**: Pluggable storage backends

**Benefits:**
- Clear separation of concerns
- Easy to test individual layers
- Pluggable components at each layer

### 2. Event-Driven Architecture

The system processes data as streams of events:

```
Events → Event Aggregation → Time Series Objects → Analysis → Storage
```

**Key Characteristics:**
- Asynchronous processing
- Loose coupling between components
- Real-time and batch processing support
- Event sourcing for auditability

### 3. Microservices Pattern

OpenTSx components are designed as independent, deployable services:

- **Data Generators**: Standalone producer services
- **Stream Processors**: Independent KStreams applications
- **UDFs**: Pluggable functions (ksqlDB, Hive)
- **Storage Adapters**: Swappable storage backends

**Benefits:**
- Independent scaling
- Technology diversity
- Fault isolation
- Continuous deployment

### 4. Storage Abstraction Pattern

The platform abstracts storage through common interfaces:

```
Application Code
      ↓
TSBucket Interface
      ↓
   ┌──┴──┬──────┬─────────┐
   ↓     ↓      ↓         ↓
Cassandra OpenTSDB HDFS  HBase
```

**Storage Implementations:**
- `TSBucketLoader4Cassandra`
- `OpenTSDBWriter`
- HDFS SequenceFile writers
- HBase adapters

### 5. Plugin Architecture

Multiple extension points for customization:

- **Algorithms**: Add custom analysis methods
- **Connectors**: Integrate new data sources
- **UDFs**: Custom ksqlDB/Hive functions
- **Storage**: Implement new backends
- **Serialization**: Support new formats

---

## System Components

### Core Data Abstractions

#### TSBucket
**Purpose:** Container for grouped time series data

**Key Features:**
- Stores `Vector<TimeSeriesObject>`
- Supports multiple backends (SequenceFile, Avro, Parquet)
- Operates as (key, VectorWritable) pairs
- Lazy loading capabilities

**Location:** `opentsx-core/src/main/java/org/opentsx/tsbucket/`

#### TimeSeriesObject (TSO)
**Purpose:** Individual time series with metadata

**Structure:**
```java
class TimeSeriesObject {
    String label;              // Series identifier
    double[] timestamps;       // Time points
    double[] values;           // Measurements
    Map<String, String> meta;  // Metadata
}
```

**Location:** `opentsx-core/src/main/java/org/opentsx/data/`

#### Episodes (Avro Schema)
**Purpose:** Schema-based event aggregation

**Schema Elements:**
- `observations[]`: Array of measurements
- `timestamps[]`: Temporal boundaries
- `labels`: Episode classification
- `metadata`: Contextual information

**Location:** `opentsx-data/src/main/avro/`

### Component Diagram

```
┌──────────────────────────────────────────────────────────┐
│                   opentsx-lg                             │
│              (Time Series Generator)                      │
│  - Sine wave generation                                  │
│  - Long-term correlation                                 │
│  - External data sources (Yahoo Finance, Wikipedia)      │
└──────────────────┬───────────────────────────────────────┘
                   ↓ (produces)
┌──────────────────────────────────────────────────────────┐
│                   Apache Kafka                           │
│  Topics: Episodes_A, Episodes_B, Events, Event_Flow      │
└──────────────────┬───────────────────────────────────────┘
                   ↓ (consumes)
┌──────────────────────────────────────────────────────────┐
│            opentsx-kafka-streams-tsa                     │
│           (Event → TSO Aggregation)                       │
│  - Session windowing                                     │
│  - State stores (Cassandra-backed)                       │
│  - TSO generation from events                            │
└──────────────────┬───────────────────────────────────────┘
                   ↓ (produces TSOs)
┌──────────────────────────────────────────────────────────┐
│                 opentsx-ksql                             │
│             (Streaming SQL)                               │
│  - UDFs: EpisodesProcessor, SummaryStatsUdaf             │
│  - Metadata extraction                                   │
│  - Continuous queries                                    │
└──────────────────┬───────────────────────────────────────┘
                   ↓ (analysis)
┌──────────────────────────────────────────────────────────┐
│                opentsx-core                              │
│          (Analysis Algorithms)                            │
│  - DFA/MFDFA detrending                                  │
│  - Event synchronization                                 │
│  - Return interval statistics                            │
│  - Statistical analysis                                  │
│  - Signal processing                                     │
└──────────────────┬───────────────────────────────────────┘
                   ↓ (stores)
┌──────────────────────────────────────────────────────────┐
│           opentsx-store-cassandra                        │
│           opentsx-store-opentsdb                         │
│         (Persistence Layer)                               │
│  - Distributed storage                                   │
│  - Time series optimized                                 │
│  - High availability                                     │
└──────────────────────────────────────────────────────────┘
```

---

## Data Flow

### Real-Time Processing Flow

```
1. Event Generation
   └─> Data Generator (opentsx-lg)
       └─> Produces: Individual events with timestamps

2. Event Publishing
   └─> Kafka Connector
       └─> Topic: OpenTSx_Events
       └─> Format: Avro (schema-validated)

3. Event Aggregation
   └─> KStreams Application
       ├─> Window: Session-based (configurable gap)
       ├─> State Store: Cassandra-backed
       └─> Output: TimeSeriesObjects (TSO)

4. Stream Analysis (Optional)
   └─> ksqlDB Continuous Query
       ├─> UDFs: EpisodesProcessor, SummaryStatsUdaf
       └─> Output: Derived streams

5. Algorithm Processing
   └─> opentsx-core Algorithms
       ├─> DFA/MFDFA for detrending
       ├─> Event synchronization metrics
       ├─> Statistical analysis
       └─> Output: Analysis results

6. Persistence
   └─> Storage Adapter
       ├─> Cassandra: Distributed time series
       └─> OpenTSDB: Metrics and monitoring

7. Visualization
   └─> Grafana Dashboards
       └─> Real-time metrics display
```

### Batch Processing Flow

```
1. Historical Data
   └─> HDFS/S3 Storage
       └─> Format: Avro, Parquet, SequenceFile

2. Batch Job
   └─> Spark Application
       ├─> Reads: TSBucket from HDFS
       ├─> Processes: opentsx-core algorithms
       └─> Writes: Results to storage

3. Analysis & Reporting
   └─> Hive Queries with UDFs
       └─> SQL access to time series data
```

---

## Technology Stack

### Stream Processing Core
| Technology | Version | Purpose |
|------------|---------|---------|
| Apache Kafka | 2.3.0 | Event streaming platform |
| Confluent Platform | 7.3.0 | Enhanced Kafka ecosystem |
| KStreams | 2.3.0 | Stream processing DSL |
| ksqlDB | 5.4.0+ | Streaming SQL engine |
| Apache Flink | (optional) | Advanced stream processing |

### Storage Backends
| Technology | Version | Purpose |
|------------|---------|---------|
| Apache Cassandra | 3.11.4 | Distributed time series storage |
| OpenTSDB | 2.4.0RC1 | Metrics-focused time series DB |
| Apache HBase | 1.2.0 | Distributed key-value store |
| Apache Kudu | 1.4.0 | Columnar storage engine |
| HDFS | 2.6.0 | Distributed file system |

### Batch Processing (Legacy)
| Technology | Version | Purpose |
|------------|---------|---------|
| Apache Spark | 2.2.2 | Batch and stream processing |
| Apache Hadoop | 2.6.0 | Distributed computing framework |
| Apache Hive | 1.1.0 | SQL on Hadoop |

### Analytics & ML
| Technology | Version | Purpose |
|------------|---------|---------|
| TensorFlow | 1.15.0 | Deep learning inference |
| Smile ML | 1.4.0 | Statistical machine learning |
| Apache Mahout | 0.9 | Distributed ML algorithms |
| Apache Commons Math | 3.6.1 | Mathematical algorithms |

### Data Serialization
| Technology | Version | Purpose |
|------------|---------|---------|
| Apache Avro | 1.8.2 | Schema-based serialization |
| Jackson | 2.8.1 | JSON processing |
| Protocol Buffers | (via TF) | Binary serialization |

### Infrastructure
| Technology | Purpose |
|------------|---------|
| Docker | Containerization |
| Kubernetes/Krake | Container orchestration |
| Grafana | Visualization & dashboards |
| Prometheus | Metrics collection |
| Schema Registry | Avro schema management |

---

## Design Principles

### 1. Cloud-Native First
- **Containerization**: All components Dockerized
- **Configuration Externalization**: Environment-driven config
- **Stateless Services**: State in external stores
- **Horizontal Scalability**: No single points of failure

### 2. Event Sourcing
- **Immutable Events**: All data changes as events
- **Replay Capability**: Reprocess historical events
- **Audit Trail**: Complete event history
- **Time Travel**: Reconstruct state at any point

### 3. Polyglot Persistence
- **Right Tool for Job**: Multiple storage backends
- **Storage Abstraction**: Common interface (TSBucket)
- **Format Flexibility**: Avro, Parquet, SequenceFile
- **Query Diversity**: SQL, streaming queries, batch

### 4. Algorithm Modularity
- **Pure Functions**: Algorithms as stateless operations
- **Composability**: Chain multiple analyses
- **Testability**: Unit test individual algorithms
- **Reusability**: Same code for batch and streaming

### 5. Schema Evolution
- **Avro Schemas**: Forward and backward compatibility
- **Schema Registry**: Centralized schema management
- **Versioning**: Multiple schema versions supported
- **Validation**: Type safety at runtime

---

## Scalability & Distribution

### Horizontal Scaling

**Kafka Topics:**
- Partitioned for parallel processing
- Consumer groups for load distribution
- Replication for fault tolerance

**KStreams Applications:**
- Task parallelism based on topic partitions
- State store replication
- Standby replicas for fast recovery

**Storage Layer:**
- Cassandra: Linear scalability with nodes
- OpenTSDB: HBase-backed horizontal scaling
- HDFS: Block replication across cluster

### Multi-Region Deployments

OpenTSx supports sophisticated multi-datacenter setups:

**Deployment Patterns:**
1. **Multi-Region, Single Cluster** (`opentsx-clusters/cp-multiregion/`)
   - Kafka cluster spanning regions
   - Replication across regions
   - Locality-aware consumers

2. **Multi-Datacenter with Replication** (`opentsx-clusters/inhouse-mrc/mdc2/`)
   - Independent clusters per datacenter
   - MirrorMaker for replication
   - Active-active or active-passive

3. **Cloud-Native Multi-Region** (`opentsx-cloud-bridge/`)
   - Confluent Cloud integration
   - Cross-cloud replication
   - Global event mesh

**Configuration Examples:**
- Synchronous replication for critical data
- Asynchronous replication for analytics
- Reverse replication for geo-distributed writes

### State Management

**KStreams State Stores:**
- **RocksDB** (default): Embedded key-value store
- **Cassandra** (custom): Distributed state store via `opentsx-kstreams-cassandra-state-store`
- **Changelog Topics**: State backup in Kafka
- **Standby Replicas**: Fast failover

### Performance Optimization

**Stream Processing:**
- Windowing strategies (session, tumbling, hopping)
- Batch processing within windows
- State store caching
- Record deduplication

**Storage:**
- Time-based partitioning in Cassandra
- Compression (Snappy, LZ4)
- Bloom filters for read optimization
- Compaction strategies

---

## Integration Points

### External Data Sources
- **Yahoo Finance API**: Financial time series
- **Wikipedia Click Data**: Traffic patterns
- **Custom Generators**: Synthetic data for testing

### Query Interfaces
- **ksqlDB**: SQL streaming queries
- **Hive**: Batch SQL queries
- **Spark**: Programmatic batch processing
- **REST APIs**: Custom application integration

### Monitoring & Observability
- **Grafana Dashboards**: Real-time metrics
- **Prometheus**: Metrics collection
- **Kafka Metrics**: Broker and stream metrics
- **Custom Metrics**: Application-level telemetry

### Development Tooling
- **Maven**: Multi-module build system
- **JUnit**: Unit and integration testing
- **Jacoco**: Code coverage analysis
- **Docker Compose**: Local development environment

---

## Security Considerations

(See [SECURITY.md](SECURITY.md) for detailed OWASP analysis)

**Authentication:**
- Kafka SASL/SSL support
- Schema Registry authentication
- Cassandra authentication

**Authorization:**
- Kafka ACLs for topic access
- Role-based access control in storage
- Application-level permissions

**Encryption:**
- TLS for Kafka connections
- SSL for Cassandra
- Encrypted storage at rest

---

## Extension Points

OpenTSx is designed for extensibility:

### 1. Custom Algorithms
Implement `TSProcessor` interface:
```java
public interface TSProcessor {
    TSBucket process(TSBucket input);
}
```

### 2. Custom Storage Backends
Implement storage adapters:
- `TSBucketLoader` for reads
- `TSBucketWriter` for writes

### 3. Custom UDFs
**ksqlDB UDFs:**
```java
@UdfDescription(name = "custom_udf", description = "...")
public class CustomUdf {
    @Udf(description = "...")
    public double process(double value) { ... }
}
```

**Hive UDFs:**
```java
public class CustomHiveUDF extends UDF {
    public Text evaluate(Text input) { ... }
}
```

### 4. Custom Connectors
Extend Kafka connector framework for new data sources.

---

## Deployment Architectures

### Development Environment
```
Docker Compose:
  - Kafka + Zookeeper
  - Schema Registry
  - ksqlDB Server
  - Cassandra (single node)
  - Grafana + Prometheus
```

### Production Single-Region
```
Kubernetes Cluster:
  - Kafka StatefulSet (3+ brokers)
  - KStreams Deployments (auto-scaling)
  - Cassandra StatefulSet (3+ nodes)
  - ksqlDB Headless Service
  - Monitoring Stack
```

### Production Multi-Region
```
Region 1:
  - Full Kafka Cluster
  - KStreams Applications
  - Cassandra Datacenter 1

Region 2:
  - Full Kafka Cluster
  - KStreams Applications
  - Cassandra Datacenter 2

Replication:
  - Kafka MirrorMaker 2.0
  - Cassandra multi-DC replication
```

---

## Conclusion

OpenTSx's architecture balances:
- **Flexibility**: Pluggable components at every layer
- **Scalability**: Horizontal scaling throughout
- **Performance**: Optimized for streaming and batch
- **Reliability**: Distributed, fault-tolerant design
- **Maintainability**: Clear separation of concerns

The event-driven, cloud-native design makes it suitable for modern time series analysis workloads ranging from real-time monitoring to complex batch analytics.

---

**For detailed module documentation, see [MODULES.md](MODULES.md)**
**For feature descriptions, see [FEATURES.md](FEATURES.md)**
**For deployment procedures, see [DEPLOYMENT.md](DEPLOYMENT.md)**
