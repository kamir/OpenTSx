# OpenTSx Architecture Overview

OpenTSx is a comprehensive framework for time series analysis built on a modular architecture that scales from single-machine processing to distributed big data pipelines.

## Module Structure

The framework consists of three primary modules:

### opentsx-core

The core module contains the essential data structures, algorithms, and processing tools:

**Package Structure:**
```
org.opentsx.
├── core/                    # Fundamental abstractions
│   ├── TSData               # Hadoop-writable time series data
│   ├── TSBucket             # Container for bulk processing
│   ├── TSOperation          # Processing operation interface
│   └── TSProcessor          # Processing framework
├── data/
│   ├── series/              # Time series implementations
│   │   ├── TimeSeriesObject # Main time series class
│   │   ├── ITimeSeriesObject # Interface contract
│   │   └── TimeSeriesObjectFFT # FFT-enabled variant
│   ├── loader/              # Data loading utilities
│   ├── exporter/            # Data export formats
│   └── generator/           # Synthetic data generation
├── algorithms/
│   ├── detrending/          # DFA, MFDFA trend analysis
│   ├── statistics/          # Statistical tools & tests
│   ├── eventsynchronisation/# Event-based analysis
│   ├── ris/                 # Random Interval Sampling
│   └── univariate/          # Single series analysis
├── chart/                   # Visualization components
├── tsbucket/                # Bucket management & storage
└── generators/              # Advanced data generators
```

### opentsx-data

The data module provides Avro-based data models for structured time series:

**Key Models:**
```
org.opentsx.data.model/
├── Observation              # Single (timestamp, value, uri) tuple
├── Event                    # Point event with metadata
├── Episodes                 # Collection of observations
├── EpisodesRecord           # Avro record for episodes
└── EventSeriesRecord        # Event sequence record
```

These models enable:
- **Schema evolution** — Add fields without breaking compatibility
- **Efficient serialization** — Binary encoding for storage/transport
- **Type safety** — Compile-time validation of data structures
- **Cross-language compatibility** — Use from Python, C++, etc.

### opentsx-ext-connectors

Extension module for external integrations:
- Kafka connectors
- JDBC/database adapters
- Custom data source bindings
- Kudu integration (minimal usage)

## Core Abstractions

### TimeSeriesObject

The primary in-memory representation of a time series:

```java
public class TimeSeriesObject implements ITimeSeriesObject {
    public Vector xValues;  // Temporal coordinates
    public Vector yValues;  // Measurements
    public String label;    // Identifier

    // 100+ methods for analysis, transformation, statistics
}
```

**Design Philosophy:**
- **Public fields** for direct access (performance)
- **Vector storage** for thread-safety and dynamic sizing
- **Rich API** with 100+ operations built-in
- **Mutable by default** but provides immutable operations

**Use Cases:**
- Interactive analysis in-memory
- Prototyping and experimentation
- Small to medium datasets (<1M points)
- Single-machine processing

### TSData

A lightweight, Hadoop-writable time series container:

```java
public class TSData implements Writable {
    long t0;              // Start timestamp
    long tE;              // End timestamp
    double dt;            // Sampling interval (ms)
    double[] dataset;     // Values only (no X coordinates)
    String label;         // Identifier
}
```

**Key Differences from TimeSeriesObject:**
- **Primitive array** instead of Vector (memory efficiency)
- **Uniform sampling** assumption (t0 + i*dt)
- **Writable interface** for Hadoop MapReduce
- **No X values** stored explicitly

**Use Cases:**
- MapReduce job inputs/outputs
- Sequence file storage
- Uniform-interval sensor data
- Memory-constrained environments

**Conversion:**
```java
// TimeSeriesObject → TSData
TSData data = TSData.convertMessreihe(timeSeriesObject);

// TSData → TimeSeriesObject
TimeSeriesObject ts = data.getMessreihe();
```

### TSBucket

A container for bulk processing of multiple time series:

```java
public class TSBucket {
    Vector<TimeSeriesObject> bucketData;  // In-memory collection
    int LIMIT = Integer.MAX_VALUE;        // Size limit
    String sourcFolder;                   // Data source path

    // Bulk operations, I/O, Hadoop integration
}
```

**Capabilities:**
- **Batch loading** from directories or Hadoop SequenceFiles
- **Bulk operations** applied to all series in parallel
- **Hadoop integration** via VectorWritable and Mahout
- **Memory management** with configurable limits

**Use Cases:**
- Processing thousands of time series together
- MapReduce-style parallel operations
- Large-scale parameter studies
- Comparative analysis across series

**Storage Formats:**
```java
// Hadoop SequenceFile (key: Text, value: VectorWritable)
writer.append(new Text(label), new VectorWritable(namedVector));

// Folder-based storage with TSBucketStore
// AVRO and Parquet support (via opentsx-data)
```

## Data Flow Architecture

### Single-Series Processing

```
Raw Data → TimeSeriesObject → Analysis → Results
   ↓
  CSV          Operations:       Statistics
  TSV          - normalize()     Plots
  JSON         - calcAverage()   Exports
  Database     - detrend()
               - detectAnomalies()
```

### Bulk Processing

```
Data Source → TSBucket → Parallel Operations → TSBucket → Export
   ↓                           ↓                    ↓
  Directory              DFA Analysis         SequenceFile
  Database               Statistical Tests    CSV Reports
  SequenceFile           Transformations      Visualizations
```

### Distributed Processing (Hadoop)

```
HDFS Input → MapReduce Job → HDFS Output
   ↓              ↓                ↓
TSData in      TSOperation      TSData in
SequenceFile   on each split    SequenceFile
```

## Integration Points

### Apache Hadoop

- **Writable interface** — TSData implements Hadoop serialization
- **SequenceFile I/O** — Native support for time series buckets
- **MapReduce** — TSOperation pattern for distributed analysis

### Apache Mahout

- **NamedVector conversion** — Time series as feature vectors
- **VectorWritable** — Integration with Mahout pipelines
- **Clustering support** — Group similar time series patterns

### Apache Spark

- **RDD compatibility** — Load buckets as RDDs
- **Broadcast variables** — Share reference series
- **Custom transformations** — Apply TSOperation in map/reduce

### Apache Kafka

- **Stream processing** — Real-time time series ingestion
- **Observation model** — Avro schemas for events
- **Episodes** — Windowed accumulation of observations

### Storage Systems

- **OpenTSDB** — Time series database backend
- **HBase** — Large-scale storage via OpenTSDB
- **PostgreSQL** — Relational metadata and small series
- **Redis** — Caching and session state
- **Parquet/AVRO** — Columnar storage for analytics

## Algorithm Categories

### Detrending & DFA

**Detrended Fluctuation Analysis** for long-range correlation:

```
DFA                    # Standard DFA
MFDFA                  # Multi-fractal DFA
MFDFAAnalyzer          # Analysis framework
DetrendingMethodFactory # Pluggable methods
```

### Statistical Analysis

```
Statistical            # Comprehensive stats toolkit
DistributionTester     # Test against known distributions
HaeufigkeitsZaehler    # Frequency counting
EntropyTool            # Shannon entropy
GrangerCausality       # Causality testing
```

### Event Synchronisation

Measure synchronization between event sequences:

```
ESCalc                 # Event synchronization calculator
ESMain                 # Experiment framework
```

### Random Interval Sampling (RIS)

Uncertainty quantification via random sampling:

```
RISTool                # RIS implementation
TSPropertyTester       # Property validation
```

### Univariate Tools

Single time series operations:

```
SingleTsARIMATool      # ARIMA modeling
SingleTsDFATool        # DFA on single series
SingleTsRISTool        # RIS analysis
TSCutTool              # Window extraction
```

## Processing Patterns

### Pattern 1: Direct Manipulation

For small datasets and interactive analysis:

```java
TimeSeriesObject ts = new TimeSeriesObject();
ts.addValue(100.5);
ts.addValue(101.2);
ts.calcAverage();
double mean = ts.getAvarage();
```

### Pattern 2: Functional Transformation

Create new series from existing:

```java
TimeSeriesObject normalized = ts.normalize_zScore();
TimeSeriesObject smoothed = ts.movingAverage(10);
TimeSeriesObject detrended = ts.detrend();
```

### Pattern 3: Bulk Processing

Process many series together:

```java
TSBucket bucket = new TSBucket();
bucket.setSourceDataFolder("/data/sensors/");
bucket.createBucketFromLocalFilesInDirectory("temp", 1000);
TSBucket results = bucket.processBucket("DFA", parameters);
```

### Pattern 4: MapReduce

For distributed processing:

```java
// Mapper
TSData input = /* read from SequenceFile */;
TimeSeriesObject ts = input.getMessreihe();
ts.normalize();
TSData output = TSData.convertMessreihe(ts);
context.write(key, new VectorWritable(output));
```

## Design Principles

### 1. Pragmatism Over Purity

- Public fields when beneficial (performance, simplicity)
- Mutable operations for efficiency
- Trade-offs documented clearly

### 2. Big Data Readiness

- Hadoop-native data structures
- Scalable processing patterns
- Memory-conscious designs

### 3. Batteries Included

- 100+ operations built into TimeSeriesObject
- Rich algorithm library
- Minimal external dependencies for core features

### 4. Interoperability

- Standard interfaces (Writable, Serializable)
- Multiple data formats (CSV, Avro, Parquet)
- Language bridges (via Avro schemas)

### 5. Research Friendly

- Latest algorithms (DFA, MFDFA, RIS)
- Extensible architecture
- Experiment reproduction support

## Memory Model

### Small Scale (< 10K series)

```
TimeSeriesObject → Vector storage → Heap
                 ↓
            JVM Memory
```

**Characteristics:**
- Convenient, rich API
- Garbage collection overhead
- Single-machine limits

### Medium Scale (10K - 1M series)

```
TSBucket → SequenceFile → Local filesystem
         ↓
    Paged into memory as needed
```

**Characteristics:**
- Streaming processing
- Configurable memory limits
- Sequential access patterns

### Large Scale (> 1M series)

```
HDFS → MapReduce → HDFS
     ↓          ↓        ↓
  TSData    TSOperation  Results
```

**Characteristics:**
- Distributed storage
- Parallel computation
- Horizontal scaling

## Performance Characteristics

### TimeSeriesObject Operations

| Operation | Complexity | Notes |
|-----------|-----------|-------|
| addValue() | O(1) amortized | Vector auto-resize |
| getAvarage() | O(n) | Iterates all values |
| normalize() | O(n) | In-place modification |
| getData() | O(n) | Copies to 2D array |

### TSBucket Operations

| Operation | Complexity | Notes |
|-----------|-----------|-------|
| Load from disk | O(n * m) | n = series, m = avg length |
| Process all | O(n * c) | c = operation cost |
| Write to SequenceFile | O(n * m) | Hadoop I/O overhead |

## Next Steps

Dive deeper into specific components:

- **[TimeSeriesObject](timeseries-object.md)** — The primary data structure
- **[Data Model](data-model.md)** — Understanding X-Y relationships
- **[TSBucket Processing](tsbucket-processing.md)** — Bulk operations
- **[Algorithm Guide](../algorithms/)** — Available analysis methods

---

**Related Topics:**
- [Deployment Patterns](../best-practices/deployment.md)
- [Performance Tuning](../best-practices/performance.md)
- [Big Data Integration](../advanced-topics/hadoop-integration.md)
