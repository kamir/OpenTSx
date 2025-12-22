# Local Development Environment

This document describes the simplified local development setup for OpenTSx using Docker Compose.

## Overview

The local development environment (`docker-compose.local.yml`) provides a complete infrastructure stack for developing and testing OpenTSx applications:

- **Kafka** (via CP-ALL-IN-ONE) — Stream processing without Zookeeper complexity
- **PostgreSQL** — Relational database storage
- **Redis** — Caching and session management
- **HBase** — Distributed column-oriented storage
- **OpenTSDB** — Time series database built on HBase
- **Backend API** (optional) — Application backend
- **Frontend UI** (optional) — Web interface

## Quick Start

### Start Core Services Only

For basic time series testing (Kafka + OpenTSDB + databases):

```bash
docker-compose -f docker-compose.local.yml up -d
```

This starts:
- PostgreSQL
- Redis
- CP-ALL-IN-ONE (Kafka, Schema Registry, Control Center)
- HBase
- OpenTSDB

### Start Full Stack

To include backend and frontend services:

```bash
docker-compose -f docker-compose.local.yml --profile full up -d
```

### Stop All Services

```bash
docker-compose -f docker-compose.local.yml down
```

### Remove All Data Volumes

**WARNING:** This deletes all data!

```bash
docker-compose -f docker-compose.local.yml down -v
```

## Service Details

### PostgreSQL (Port 5432)

**Purpose:** Relational database for application metadata and configuration

**Connection:**
```java
String url = "jdbc:postgresql://localhost:5432/opentsx";
String user = "opentsx";
String password = "opentsx";
```

**Health Check:**
```bash
docker exec opentsx-postgres pg_isready -U opentsx
```

### Redis (Port 6379)

**Purpose:** Caching and fast key-value storage

**Connection:**
```bash
redis-cli -h localhost -p 6379
```

**Health Check:**
```bash
docker exec opentsx-redis redis-cli ping
```

### CP-ALL-IN-ONE (Ports 9092, 8081, 9021)

**Purpose:** Complete Kafka platform without Zookeeper complexity

**Key Components:**
- **Kafka Broker** (9092) — Message streaming
- **Schema Registry** (8081) — Avro schema management
- **Control Center** (9021) — Web UI for monitoring

**Kafka Connection:**
```java
Properties props = new Properties();
props.put("bootstrap.servers", "localhost:9092");
props.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
props.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer");
```

**Control Center UI:**
- URL: http://localhost:9021
- No authentication required (local development)

**Create Topic:**
```bash
docker exec opentsx-cp-all-in-one kafka-topics \
  --create \
  --topic opentsx-timeseries \
  --bootstrap-server localhost:9092 \
  --partitions 3 \
  --replication-factor 1
```

**List Topics:**
```bash
docker exec opentsx-cp-all-in-one kafka-topics \
  --list \
  --bootstrap-server localhost:9092
```

**Produce Messages:**
```bash
docker exec -it opentsx-cp-all-in-one kafka-console-producer \
  --topic opentsx-timeseries \
  --bootstrap-server localhost:9092
```

**Consume Messages:**
```bash
docker exec -it opentsx-cp-all-in-one kafka-console-consumer \
  --topic opentsx-timeseries \
  --from-beginning \
  --bootstrap-server localhost:9092
```

### HBase (Ports 16000, 16010)

**Purpose:** Distributed column-oriented storage for OpenTSDB

**Web UI:**
- Master: http://localhost:16010

**HBase Shell:**
```bash
docker exec -it opentsx-hbase hbase shell
```

**Health Check:**
```bash
docker exec opentsx-hbase bash -c "echo 'status' | hbase shell -n"
```

### OpenTSDB (Port 4242)

**Purpose:** Scalable time series database

**Web UI:**
- URL: http://localhost:4242

**REST API:**
```bash
# Check version
curl http://localhost:4242/api/version

# Write data point
curl -X POST http://localhost:4242/api/put \
  -H "Content-Type: application/json" \
  -d '{
    "metric": "sensor.temperature",
    "timestamp": 1700000000,
    "value": 23.5,
    "tags": {
      "location": "warehouse",
      "sensor_id": "temp_01"
    }
  }'

# Query data
curl "http://localhost:4242/api/query?start=1h-ago&m=avg:sensor.temperature{location=warehouse}"
```

**Java Connection:**
```java
import net.opentsdb.core.TSDB;
import net.opentsdb.utils.Config;

Config config = new Config(false);
config.overrideConfig("tsd.storage.hbase.zk_quorum", "localhost:2181");
config.overrideConfig("tsd.storage.hbase.zk_basedir", "/hbase");

TSDB tsdb = new TSDB(config);
```

## Development Workflow

### 1. Start Infrastructure

```bash
# Start all services
docker-compose -f docker-compose.local.yml up -d

# Wait for services to be healthy
docker-compose -f docker-compose.local.yml ps

# Check logs
docker-compose -f docker-compose.local.yml logs -f
```

### 2. Run OpenTSx Demo Scripts

```bash
# Validate environment
./bin/000_validate_environment.sh

# Build project
./bin/010_build.sh

# Run Episode 2 demo (basic time series creation)
./bin/episode_02_create_timeseries.sh
```

### 3. Test Kafka Integration

```java
// Producer example
Properties props = new Properties();
props.put("bootstrap.servers", "localhost:9092");
props.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
props.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer");

KafkaProducer<String, String> producer = new KafkaProducer<>(props);

TimeSeriesObject ts = TimeSeriesObject.getGaussianDistribution(100);
String json = convertToJson(ts);  // Your serialization logic

ProducerRecord<String, String> record =
    new ProducerRecord<>("opentsx-timeseries", ts.getLabel(), json);
producer.send(record);
producer.close();
```

### 4. Monitor with Control Center

1. Open http://localhost:9021
2. Navigate to "Topics" to see message throughput
3. Navigate to "Consumers" to monitor consumption lag
4. Use "Connect" tab for Kafka Connect integration

## Troubleshooting

### Kafka Not Starting

**Symptom:** `opentsx-cp-all-in-one` container exits immediately

**Solution:**
```bash
# Check logs
docker logs opentsx-cp-all-in-one

# Remove volume and restart
docker-compose -f docker-compose.local.yml down -v
docker-compose -f docker-compose.local.yml up -d
```

### HBase Not Responding

**Symptom:** OpenTSDB cannot connect to HBase

**Solution:**
```bash
# HBase takes 1-2 minutes to start fully
docker logs opentsx-hbase

# Wait for "Master has completed initialization"
# Then restart OpenTSDB
docker restart opentsx-opentsdb
```

### OpenTSDB Table Creation Failed

**Symptom:** OpenTSDB API returns errors

**Solution:**
```bash
# Manually create OpenTSDB tables
docker exec -it opentsx-hbase bash

# In container:
export HBASE_HOME=/opt/hbase
export COMPRESSION=NONE
/opt/opentsdb/tools/create_table.sh
```

### Port Already in Use

**Symptom:** `Error starting userland proxy: listen tcp4 0.0.0.0:9092: bind: address already in use`

**Solution:**
```bash
# Find process using port
lsof -i :9092

# Kill process or change port in docker-compose.local.yml
# Example: Change "9092:9092" to "9093:9092"
```

### Out of Memory

**Symptom:** Services crash or become unresponsive

**Solution:**
```bash
# Check Docker resources
docker stats

# Increase Docker Desktop memory limit to at least 8GB
# Settings → Resources → Memory

# Or reduce services by commenting out in docker-compose.local.yml
```

## Architecture Notes

### Why CP-ALL-IN-ONE Instead of Separate Kafka?

**Benefits:**
1. **No Zookeeper** — Uses KRaft mode (Kafka 3.0+)
2. **All-in-one** — Schema Registry, Connect, Control Center included
3. **Simplified** — Single container for complete Kafka platform
4. **Local Testing** — Perfect for development, not production

**Trade-offs:**
- Larger image size (~2GB)
- More resource usage (minimum 4GB RAM recommended)
- Not suitable for production deployments

### Why HBase + OpenTSDB?

**Rationale:**
- **Proven** — Battle-tested for massive time series workloads
- **Scalable** — Handles billions of data points
- **Flexible** — Schema-less tag-based queries
- **Compatible** — Direct integration with existing OpenTSx infrastructure

**Note:** HBase includes its own internal Zookeeper (port 2181) which is separate from the removed Kafka Zookeeper. This is required by HBase and cannot be eliminated.

### Kudu Exclusion

Apache Kudu was evaluated and found to have minimal usage in the codebase:
- Only used in `opentsx-ext-connectors/src/main/java/kudu/KuduTelemetryDataLoader.java`
- Not required for core OpenTSx functionality
- Can be added separately if needed for specific IoT telemetry use cases

## Resource Requirements

### Minimum

- **CPU:** 4 cores
- **RAM:** 8 GB
- **Disk:** 10 GB free

### Recommended

- **CPU:** 8 cores
- **RAM:** 16 GB
- **Disk:** 20 GB free
- **Docker Desktop:** Latest version with increased resource limits

### Per-Service Memory Usage

| Service       | Memory Usage | Notes                        |
|---------------|--------------|------------------------------|
| PostgreSQL    | ~100 MB      | Lightweight                  |
| Redis         | ~10 MB       | Minimal without data         |
| CP-ALL-IN-ONE | ~2-3 GB      | Includes entire Kafka stack  |
| HBase         | ~1-2 GB      | Requires Java heap space     |
| OpenTSDB      | ~500 MB      | Java-based API server        |
| **Total**     | **~4-6 GB**  | Without backend/frontend     |

## Next Steps

1. **Validate Setup:**
   ```bash
   ./bin/000_validate_environment.sh --strict
   ```

2. **Build Project:**
   ```bash
   ./bin/010_build.sh
   ```

3. **Run Demo Scripts:**
   ```bash
   # Episode 2: Creating Time Series
   ./bin/episode_02_create_timeseries.sh

   # Episode 3: Basic Operations
   ./bin/episode_03_basic_operations.sh
   ```

4. **Explore Control Center:**
   - Open http://localhost:9021
   - Create a test topic
   - Produce and consume messages

5. **Test OpenTSDB:**
   - Open http://localhost:4242
   - Try the API examples above
   - Integrate with your OpenTSx code

## See Also

- [OpenTSx Manual](../manual/README.md) — Conceptual documentation
- [Demo Scripts](../../bin/README.md) — Executable examples
- [Confluent Platform Documentation](https://docs.confluent.io/platform/current/overview.html)
- [OpenTSDB Documentation](http://opentsdb.net/docs/build/html/)
- [HBase Reference Guide](https://hbase.apache.org/book.html)
