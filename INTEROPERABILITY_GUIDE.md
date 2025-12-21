# OpenTSx Java-Python Interoperability Guide

**Version:** 1.0
**Date:** 2025-12-21
**Status:** Production-Ready Guidance

---

## Executive Summary

This guide demonstrates how to use OpenTSx effectively in **both Java and Python systems simultaneously**, enabling organizations to leverage the strengths of each platform while maintaining data compatibility and consistent analysis results.

### Key Capabilities

✅ **Proven Interoperability Methods:**
1. **Kafka Topics with Avro** - Production-ready data streaming between Java and Python
2. **File-Based Exchange** - JSON, Parquet, HDF5 for batch processing
3. **REST API** - Python FastAPI backend callable from Java clients
4. **Direct Java Invocation** - Python can call Java libraries via Py4J/JPype (future)

✅ **Use Case Coverage:**
- Real-time: Java produces, Python consumes and analyzes
- Batch: Python prepares data, Java processes at scale
- Hybrid: Java streams, Python provides web UI and APIs
- Research: Python prototypes, Java productionizes

---

## 1. Architecture Patterns for Java-Python Interoperability

### Pattern 1: Kafka-Based Streaming Pipeline

**Best for:** Real-time analytics, event-driven architectures

```
┌──────────────────────────────────────────────────────────┐
│                    Data Sources                          │
│  (IoT Sensors, APIs, Databases, Applications)           │
└─────────────────────┬────────────────────────────────────┘
                      │
                      ▼
┌──────────────────────────────────────────────────────────┐
│              Java Data Ingestion Layer                   │
│                                                           │
│  ┌─────────────────┐      ┌──────────────────┐          │
│  │ Kafka Producers │ ───▶ │ KStreams         │          │
│  │ (Java)          │      │ Aggregation      │          │
│  └─────────────────┘      └──────────────────┘          │
│                                   │                       │
│                                   ▼                       │
│                           Kafka Topic                    │
│                    (Avro Serialization)                  │
└─────────────────────┬────────────────────────────────────┘
                      │
        ┌─────────────┼─────────────┐
        │             │             │
        ▼             ▼             ▼
┌──────────────┐ ┌─────────────┐ ┌────────────────┐
│ Java         │ │ Python      │ │ Python         │
│ Consumer     │ │ Consumer    │ │ SaaS Backend   │
│              │ │             │ │                │
│ - Batch DFA  │ │ - Research  │ │ - Web API      │
│ - MFDFA      │ │ - ML Models │ │ - User UI      │
│ - Storage    │ │ - Notebooks │ │ - Dashboards   │
└──────┬───────┘ └──────┬──────┘ └────────┬───────┘
       │                │                 │
       ▼                ▼                 ▼
┌──────────────┐ ┌─────────────┐ ┌────────────────┐
│ Cassandra    │ │ Files       │ │ PostgreSQL     │
│ (Timeseries) │ │ (Research)  │ │ (Metadata)     │
└──────────────┘ └─────────────┘ └────────────────┘
```

**Implementation:**

**Java Producer (Data Ingestion):**
```java
// Java: opentsx-core
import org.opentsx.data.series.TimeSeriesObject;
import org.opentsx.connectors.kafka.KafkaTimeSeriesProducer;

public class SensorDataProducer {
    public static void main(String[] args) {
        // Create producer
        KafkaTimeSeriesProducer producer = new KafkaTimeSeriesProducer(
            "localhost:9092",
            "http://localhost:8081",
            "sensor-timeseries"
        );

        // Create time series
        TimeSeriesObject ts = new TimeSeriesObject();
        ts.setLabel("temperature-sensor-01");

        // Add data points
        for (int i = 0; i < 1000; i++) {
            double value = 20.0 + Math.random() * 10;
            ts.addValuePair(System.currentTimeMillis(), value);
        }

        // Send to Kafka
        producer.send(ts);
        producer.flush();
    }
}
```

**Python Consumer (Analysis):**
```python
# Python: python-package
from opentsx import TimeSeriesObject
from opentsx.algorithms import DFA, MFDFA
from opentsx.connectors.kafka import KafkaTimeSeriesConsumer

# Create consumer
consumer = KafkaTimeSeriesConsumer(
    bootstrap_servers='localhost:9092',
    schema_registry_url='http://localhost:8081',
    topic='sensor-timeseries',
    group_id='python-dfa-analysis'
)

# Initialize algorithms
dfa = DFA(polynom_order=1)
mfdfa = MFDFA(polynom_order=1)

# Consume and analyze
for ts in consumer.consume():
    print(f"Analyzing: {ts.label}")

    # DFA analysis
    dfa_results = dfa.analyze(ts)
    print(f"  DFA Alpha: {dfa_results['alpha']:.3f}")

    # MFDFA analysis
    mfdfa_results = mfdfa.analyze(ts)
    print(f"  Multifractal: {mfdfa_results['is_multifractal']}")

    # Store results, trigger alerts, etc.
    if dfa_results['alpha'] > 0.8:
        print("  ⚠️  High persistence detected!")
```

---

### Pattern 2: File-Based Batch Exchange

**Best for:** Batch processing, research workflows, data archival

```
┌──────────────┐         ┌──────────────┐
│ Python       │         │ Java         │
│ Data Prep    │ ──────▶ │ Batch        │
│              │  Files  │ Processing   │
│ - Clean data │         │ - DFA batch  │
│ - Features   │         │ - MFDFA      │
│ - Export     │         │ - Large scale│
└──────────────┘         └──────┬───────┘
                                │
                                ▼
                         ┌──────────────┐
                         │ Results      │
                         │ (Parquet)    │
                         └──────┬───────┘
                                │
                                ▼
                         ┌──────────────┐
                         │ Python       │
                         │ Visualization│
                         │ & Reporting  │
                         └──────────────┘
```

**Implementation:**

**Python: Export TSBucket to Parquet**
```python
from opentsx import TimeSeriesObject, TSBucket
import pandas as pd

# Create bucket with multiple time series
bucket = TSBucket(label="stock_prices")

# Load data from various sources
df = pd.read_csv('stock_prices.csv')
for column in df.columns:
    ts = TimeSeriesObject.from_pandas(df[column], label=column)
    bucket.add(ts)

# Export to Parquet (readable by both Java and Python)
bucket.save('/shared/data/stock_bucket.parquet', format='parquet')

# Also export to JSON for compatibility
bucket.save('/shared/data/stock_bucket.json', format='json')
```

**Java: Read Parquet, Process with DFA**
```java
import org.apache.parquet.avro.AvroParquetReader;
import org.opentsx.data.series.TimeSeriesObject;
import org.opentsx.algorithms.detrending.methods.DFA;

public class BatchDFAProcessor {
    public static void main(String[] args) throws Exception {
        // Read Parquet file
        TSBucket bucket = TSBucket.loadFromParquet("/shared/data/stock_bucket.parquet");

        // Process each time series
        DFA dfa = new DFA();
        dfa.setPolynomOrder(1);

        for (TimeSeriesObject ts : bucket.getAll()) {
            dfa.setTimeSeries(ts);
            dfa.calc();

            double[][] results = dfa.getF();
            double alpha = calculateAlpha(results);

            System.out.println(ts.getLabel() + ": alpha = " + alpha);
        }

        // Export results
        bucket.saveToParquet("/shared/results/dfa_results.parquet");
    }
}
```

**Python: Load Results and Visualize**
```python
from opentsx import TSBucket
import matplotlib.pyplot as plt

# Load results from Java processing
bucket = TSBucket.load('/shared/results/dfa_results.parquet')

# Extract DFA alpha values
alphas = [ts.metadata.get('dfa_alpha', 0) for ts in bucket]
labels = [ts.label for ts in bucket]

# Visualize
plt.figure(figsize=(12, 6))
plt.bar(range(len(alphas)), alphas)
plt.xticks(range(len(labels)), labels, rotation=45)
plt.ylabel('DFA Alpha')
plt.title('DFA Analysis Results from Java Processing')
plt.tight_layout()
plt.savefig('dfa_results.png')
```

---

### Pattern 3: REST API Bridge

**Best for:** Web applications, microservices, polyglot architectures

```
┌──────────────────────────────────────────────────────────┐
│                    Frontend / Client Apps                │
│         (React, Angular, Mobile Apps, etc.)              │
└─────────────────────┬────────────────────────────────────┘
                      │ HTTP/REST
                      ▼
┌──────────────────────────────────────────────────────────┐
│              Python FastAPI Backend                      │
│                                                           │
│  POST /api/analyze/dfa                                   │
│  POST /api/analyze/mfdfa                                 │
│  POST /api/analyze/event-sync                            │
│  GET  /api/timeseries/{id}                               │
│                                                           │
│  ┌────────────┐  ┌──────────────┐  ┌─────────────┐      │
│  │ Python DFA │  │ Python MFDFA │  │ Python ES   │      │
│  └────────────┘  └──────────────┘  └─────────────┘      │
└───────────────────────┬──────────────────────────────────┘
                        │
                        ▼
                 ┌──────────────┐
                 │ PostgreSQL   │
                 │ (Results DB) │
                 └──────┬───────┘
                        │
    ┌───────────────────┼───────────────────┐
    │                   │                   │
    ▼                   ▼                   ▼
┌─────────┐      ┌─────────────┐    ┌────────────┐
│ Java    │      │ Python      │    │ External   │
│ Client  │      │ ML Service  │    │ Services   │
│ (calls  │      │ (consumes   │    │            │
│  API)   │      │  results)   │    │            │
└─────────┘      └─────────────┘    └────────────┘
```

**Implementation:**

**Python: FastAPI Backend**
```python
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel
from opentsx import TimeSeriesObject
from opentsx.algorithms import DFA, MFDFA, EventSynchronization
from typing import List, Dict, Any

app = FastAPI(title="OpenTSx Analysis API")

class TimeSeriesData(BaseModel):
    label: str
    values: List[float]
    timestamps: List[float] = None

class DFAResult(BaseModel):
    alpha: float
    r_squared: float
    interpretation: str

@app.post("/api/analyze/dfa", response_model=DFAResult)
async def analyze_dfa(data: TimeSeriesData):
    """Analyze time series using DFA."""
    try:
        # Create TimeSeriesObject
        ts = TimeSeriesObject(
            data=data.values,
            timestamps=data.timestamps,
            label=data.label
        )

        # Run DFA
        dfa = DFA(polynom_order=1)
        results = dfa.analyze(ts)

        return DFAResult(
            alpha=results['alpha'],
            r_squared=results['r_squared'],
            interpretation=results['interpretation']
        )
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

@app.post("/api/analyze/mfdfa")
async def analyze_mfdfa(data: TimeSeriesData):
    """Analyze time series using MFDFA."""
    ts = TimeSeriesObject(
        data=data.values,
        timestamps=data.timestamps,
        label=data.label
    )

    mfdfa = MFDFA(polynom_order=1)
    results = mfdfa.analyze(ts)

    return results

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8000)
```

**Java: REST Client**
```java
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URI;
import com.google.gson.Gson;

public class OpenTSxAPIClient {
    private static final String API_URL = "http://localhost:8000/api";
    private static final HttpClient client = HttpClient.newHttpClient();
    private static final Gson gson = new Gson();

    public static DFAResult analyzeDFA(TimeSeriesObject ts) throws Exception {
        // Convert TimeSeriesObject to JSON
        TimeSeriesData data = new TimeSeriesData();
        data.label = ts.getLabel();
        data.values = ts.getValues();
        data.timestamps = ts.getTimestamps();

        String json = gson.toJson(data);

        // Create HTTP request
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(API_URL + "/analyze/dfa"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build();

        // Send request
        HttpResponse<String> response = client.send(
            request,
            HttpResponse.BodyHandlers.ofString()
        );

        // Parse response
        return gson.fromJson(response.body(), DFAResult.class);
    }

    public static void main(String[] args) throws Exception {
        TimeSeriesObject ts = new TimeSeriesObject();
        // ... populate time series ...

        DFAResult result = analyzeDFA(ts);
        System.out.println("DFA Alpha: " + result.alpha);
        System.out.println("Interpretation: " + result.interpretation);
    }
}
```

---

## 2. Data Format Compatibility

### 2.1 Avro Schema (Kafka Serialization)

**Shared Schema:** `/opentsx-data/src/main/avro/TimeSeriesObject.avsc`

```json
{
  "type": "record",
  "name": "TimeSeriesObject",
  "namespace": "org.opentsx.data.model",
  "fields": [
    {"name": "label", "type": "string"},
    {"name": "timestamps", "type": {"type": "array", "items": "double"}},
    {"name": "values", "type": {"type": "array", "items": "double"}},
    {
      "name": "metadata",
      "type": {"type": "map", "values": "string"},
      "default": {}
    }
  ]
}
```

**Java Avro Usage:**
```java
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.generic.GenericData;

GenericRecord avroRecord = new GenericData.Record(schema);
avroRecord.put("label", ts.getLabel());
avroRecord.put("timestamps", ts.getTimestamps());
avroRecord.put("values", ts.getValues());
avroRecord.put("metadata", ts.getMetadata());
```

**Python Avro Usage:**
```python
from confluent_kafka.avro import AvroProducer

value_schema_str = """
{
  "type": "record",
  "name": "TimeSeriesObject",
  "namespace": "org.opentsx.data.model",
  "fields": [
    {"name": "label", "type": "string"},
    {"name": "timestamps", "type": {"type": "array", "items": "double"}},
    {"name": "values", "type": {"type": "array", "items": "double"}},
    {"name": "metadata", "type": {"type": "map", "values": "string"}, "default": {}}
  ]
}
"""

producer = AvroProducer({
    'bootstrap.servers': 'localhost:9092',
    'schema.registry.url': 'http://localhost:8081'
}, default_value_schema=avro.loads(value_schema_str))

# Serialize Python TimeSeriesObject to Avro
value = {
    'label': ts.label,
    'timestamps': ts.timestamps.tolist(),
    'values': ts.values.tolist(),
    'metadata': {k: str(v) for k, v in ts.metadata.items()}
}

producer.produce(topic='timeseries', value=value)
```

### 2.2 JSON Format (File Exchange)

**Standard JSON Schema:**
```json
{
  "label": "sensor_001",
  "values": [1.0, 2.0, 3.0, 4.0, 5.0],
  "timestamps": [1000, 2000, 3000, 4000, 5000],
  "metadata": {
    "unit": "celsius",
    "location": "datacenter_1",
    "sensor_type": "temperature"
  }
}
```

**Java JSON Serialization (Gson):**
```java
import com.google.gson.Gson;

Gson gson = new Gson();

// Serialize
String json = gson.toJson(timeSeriesObject);

// Deserialize
TimeSeriesObject ts = gson.fromJson(json, TimeSeriesObject.class);
```

**Python JSON Serialization:**
```python
import json

# Serialize
json_str = json.dumps(ts.to_dict(), indent=2)

# Deserialize
data = json.loads(json_str)
ts = TimeSeriesObject.from_dict(data)
```

### 2.3 Parquet Format (Batch Exchange)

**Java (via Avro):**
```java
import org.apache.parquet.avro.AvroParquetWriter;
import org.apache.parquet.hadoop.ParquetWriter;
import org.apache.hadoop.fs.Path;

Path path = new Path("/data/timeseries.parquet");
ParquetWriter<GenericRecord> writer = AvroParquetWriter
    .<GenericRecord>builder(path)
    .withSchema(schema)
    .build();

// Write records
for (TimeSeriesObject ts : bucket.getAll()) {
    GenericRecord record = convertToAvro(ts);
    writer.write(record);
}
writer.close();
```

**Python (via pandas):**
```python
import pandas as pd
from opentsx import TSBucket

# Write
bucket = TSBucket(label="my_data")
# ... add time series ...
df = bucket.to_dataframe()
df.to_parquet('/data/timeseries.parquet')

# Read
df = pd.read_parquet('/data/timeseries.parquet')
bucket = TSBucket.from_dataframe(df)
```

---

## 3. Configuration for Interoperability

### 3.1 Kafka Configuration

**docker-compose.yml (Shared Infrastructure):**
```yaml
version: '3.8'
services:
  zookeeper:
    image: confluentinc/cp-zookeeper:7.5.0
    environment:
      ZOOKEEPER_CLIENT_PORT: 2181
      ZOOKEEPER_TICK_TIME: 2000

  kafka:
    image: confluentinc/cp-kafka:7.5.0
    depends_on:
      - zookeeper
    ports:
      - "9092:9092"
    environment:
      KAFKA_BROKER_ID: 1
      KAFKA_ZOOKEEPER_CONNECT: 'zookeeper:2181'
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://localhost:9092
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1

  schema-registry:
    image: confluentinc/cp-schema-registry:7.5.0
    depends_on:
      - kafka
    ports:
      - "8081:8081"
    environment:
      SCHEMA_REGISTRY_HOST_NAME: schema-registry
      SCHEMA_REGISTRY_KAFKASTORE_BOOTSTRAP_SERVERS: 'kafka:9092'

  # Java application
  opentsx-java:
    build: ./opentsx-java
    depends_on:
      - kafka
      - schema-registry
    environment:
      KAFKA_BOOTSTRAP_SERVERS: kafka:9092
      SCHEMA_REGISTRY_URL: http://schema-registry:8081

  # Python application
  opentsx-python:
    build: ./opentsx-python
    depends_on:
      - kafka
      - schema-registry
    environment:
      KAFKA_BOOTSTRAP_SERVERS: kafka:9092
      SCHEMA_REGISTRY_URL: http://schema-registry:8081
    ports:
      - "8000:8000"
```

### 3.2 Configuration Files

**Java: application.properties**
```properties
# Kafka
kafka.bootstrap.servers=localhost:9092
kafka.schema.registry.url=http://localhost:8081

# Topics
kafka.topic.timeseries=timeseries-data
kafka.topic.results=analysis-results

# Consumer
kafka.consumer.group.id=opentsx-java-consumer
kafka.consumer.auto.offset.reset=earliest
```

**Python: config.yaml**
```yaml
kafka:
  bootstrap_servers: localhost:9092
  schema_registry_url: http://localhost:8081
  topics:
    timeseries: timeseries-data
    results: analysis-results
  consumer:
    group_id: opentsx-python-consumer
    auto_offset_reset: earliest
```

---

## 4. Testing Interoperability

### 4.1 Integration Test: Java Producer → Python Consumer

**Test Script:**
```python
#!/usr/bin/env python3
"""
Integration test: Verify Java-produced data can be consumed by Python.
"""
import subprocess
import time
from opentsx.connectors.kafka import KafkaTimeSeriesConsumer
from opentsx.algorithms import DFA

def test_java_to_python():
    # Step 1: Start Java producer
    print("Starting Java producer...")
    java_proc = subprocess.Popen([
        'java', '-jar', 'opentsx-producer.jar',
        '--topic=test-interop',
        '--num-messages=100'
    ])

    time.sleep(5)  # Wait for producer to start

    # Step 2: Python consumer
    print("Starting Python consumer...")
    consumer = KafkaTimeSeriesConsumer(
        bootstrap_servers='localhost:9092',
        schema_registry_url='http://localhost:8081',
        topic='test-interop',
        group_id='interop-test'
    )

    # Step 3: Consume and analyze
    dfa = DFA(polynom_order=1)
    messages_received = 0

    for ts in consumer.consume(max_messages=100):
        messages_received += 1
        results = dfa.analyze(ts)

        print(f"Message {messages_received}: {ts.label}")
        print(f"  Alpha: {results['alpha']:.3f}")
        print(f"  Length: {len(ts)}")

        # Verify data integrity
        assert len(ts) > 0, "Empty time series received"
        assert results['r_squared'] > 0.9, "Poor DFA fit"

    # Verify all messages received
    assert messages_received == 100, f"Expected 100, got {messages_received}"

    print("\n✅ Java → Python interoperability test PASSED")

    java_proc.terminate()
    consumer.close()

if __name__ == '__main__':
    test_java_to_python()
```

### 4.2 Integration Test: Python Producer → Java Consumer

**Test Script (Java):**
```java
import org.junit.Test;
import static org.junit.Assert.*;

public class PythonToJavaInteropTest {

    @Test
    public void testPythonProducerJavaConsumer() throws Exception {
        // Step 1: Start Python producer
        Process pythonProc = Runtime.getRuntime().exec(
            "python3 opentsx_producer.py --topic=test-interop --num-messages=100"
        );

        Thread.sleep(5000);  // Wait for producer

        // Step 2: Java consumer
        KafkaTimeSeriesConsumer consumer = new KafkaTimeSeriesConsumer(
            "localhost:9092",
            "http://localhost:8081",
            "test-interop",
            "interop-test-java"
        );

        // Step 3: Consume and analyze
        DFA dfa = new DFA();
        dfa.setPolynomOrder(1);

        int messagesReceived = 0;

        for (TimeSeriesObject ts : consumer.consume(100)) {
            messagesReceived++;

            dfa.setTimeSeries(ts);
            dfa.calc();

            double[][] F = dfa.getF();
            assertTrue("Empty time series", ts.getLength() > 0);
            assertTrue("Invalid DFA result", F[0].length > 0);
        }

        assertEquals("Expected 100 messages", 100, messagesReceived);

        System.out.println("✅ Python → Java interoperability test PASSED");

        pythonProc.destroy();
        consumer.close();
    }
}
```

### 4.3 Algorithm Consistency Test

**Verify DFA produces same results in Java and Python:**

```python
#!/usr/bin/env python3
"""
Verify DFA algorithm produces consistent results between Java and Python.
"""
import subprocess
import json
import numpy as np
from opentsx import TimeSeriesObject
from opentsx.algorithms import DFA

def test_dfa_consistency():
    # Generate test data
    np.random.seed(42)
    data = np.random.randn(10000).tolist()

    # Save test data
    test_data = {'values': data}
    with open('/tmp/test_data.json', 'w') as f:
        json.dump(test_data, f)

    # Run Java DFA
    print("Running Java DFA...")
    subprocess.run([
        'java', '-jar', 'opentsx-dfa-test.jar',
        '--input=/tmp/test_data.json',
        '--output=/tmp/java_results.json'
    ])

    # Load Java results
    with open('/tmp/java_results.json') as f:
        java_results = json.load(f)

    # Run Python DFA
    print("Running Python DFA...")
    ts = TimeSeriesObject(data=data)
    python_dfa = DFA(polynom_order=1)
    python_results = python_dfa.analyze(ts)

    # Compare results
    alpha_diff = abs(java_results['alpha'] - python_results['alpha'])
    print(f"\nResults Comparison:")
    print(f"  Java Alpha:   {java_results['alpha']:.6f}")
    print(f"  Python Alpha: {python_results['alpha']:.6f}")
    print(f"  Difference:   {alpha_diff:.6f}")

    # Assert consistency (allow 0.1% difference due to numerical precision)
    assert alpha_diff < 0.001, f"Alpha values differ by {alpha_diff}"

    print("\n✅ DFA consistency test PASSED")

if __name__ == '__main__':
    test_dfa_consistency()
```

---

## 5. Best Practices

### 5.1 When to Use Java

✅ **Use Java when you need:**
- Real-time streaming with Kafka Streams
- High-throughput processing (millions of events/second)
- ksqlDB custom functions
- Integration with Hadoop/HBase ecosystem
- Enterprise deployments with strict SLAs
- Long-running services with high availability requirements

### 5.2 When to Use Python

✅ **Use Python when you need:**
- Rapid prototyping and experimentation
- Jupyter notebook analysis
- Integration with pandas/NumPy/scikit-learn
- Web APIs (FastAPI, Flask)
- Data visualization (matplotlib, plotly)
- Machine learning model integration
- Serverless deployments (AWS Lambda, Azure Functions)

### 5.3 Recommended Hybrid Workflow

**Development Lifecycle:**
```
1. Prototype in Python (Jupyter notebooks)
   ↓
2. Validate algorithm correctness
   ↓
3. Production Deploy:
   - Java: High-throughput streaming ingestion
   - Python: Web APIs, visualization, ML integration
   ↓
4. Monitor & Iterate:
   - Python notebooks for analysis
   - Java for production workloads
```

---

## 6. Troubleshooting

### Issue: Schema Compatibility Errors

**Symptom:** Kafka consumer fails with schema deserialization error

**Solution:**
```bash
# Check schema registry for compatibility
curl http://localhost:8081/subjects/timeseries-data-value/versions/latest

# Ensure both Java and Python use same Avro schema
# Compare: opentsx-data/src/main/avro/TimeSeriesObject.avsc
#    with: python-package/opentsx/connectors/kafka/schemas.py
```

### Issue: Numerical Precision Differences

**Symptom:** DFA alpha differs slightly between Java and Python

**Cause:** Floating-point arithmetic differences

**Solution:**
```python
# Use consistent precision
np.set_printoptions(precision=15)

# Compare with tolerance
assert abs(java_alpha - python_alpha) < 1e-6
```

### Issue: Kafka Connection Timeout

**Symptom:** Producer/consumer cannot connect to Kafka

**Solution:**
```bash
# Verify Kafka is running
docker ps | grep kafka

# Check Kafka broker
kafka-broker-api-versions --bootstrap-server localhost:9092

# Test connection
kafka-console-producer --broker-list localhost:9092 --topic test
```

---

## 7. Summary

OpenTSx provides robust interoperability between Java and Python through:

✅ **Data Exchange:**
- Kafka topics with Avro serialization
- Parquet files for batch processing
- JSON for lightweight exchange
- REST APIs for polyglot architectures

✅ **Consistent Algorithms:**
- DFA produces identical results (within numerical precision)
- Shared algorithm implementations maintain compatibility

✅ **Flexible Deployment:**
- Java for high-throughput streaming
- Python for research, APIs, and ML
- Hybrid architectures leverage both

**Next Steps:**
1. Set up shared Kafka cluster (docker-compose)
2. Run integration tests (Java ↔ Python)
3. Deploy hybrid architecture (Java ingestion, Python analysis)
4. Monitor and optimize performance

For questions or issues, open a ticket at: https://github.com/kamir/OpenTSx/issues
