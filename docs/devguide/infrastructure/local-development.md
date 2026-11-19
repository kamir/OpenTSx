# Local Development Environment

> **Two stacks.**
>
> * For the v2 modules (KafScale storage, statistics, wind farm, retrieval), use
>   **`docker-compose.kafscale.yml`** (etcd, MinIO, KafScale broker on :9092). It is described in the
>   [user manual, chapter 12](../../user-manual/12-operations.md).
> * The stack documented below (`docker-compose.local.yml`) serves the 2.x tools and the experimental SaaS stack.
>   Its `full` profile builds the backend from `opentsx-saas-backend/` and the frontend from `opentsx-saas-frontend/`.
>
> Both stacks bind port 9092, so do not run them at the same time.

This document describes the simplified local development setup for OpenTSx using Docker Compose.

## Overview

The local development environment (`docker-compose.local.yml`) provides a complete infrastructure stack for developing and testing OpenTSx applications:

- **Kafka** (`confluentinc/cp-kafka:7.6.0`): one broker in KRaft mode, without ZooKeeper
- **Schema Registry** (`confluentinc/cp-schema-registry:7.6.0`): Avro schema management
- **PostgreSQL**: relational database storage
- **Backend API** (optional): the SaaS backend
- **Frontend UI** (optional): the SaaS frontend

The tests of the OpenTSDB store do not need this stack: they run against an embedded emulator, and against a real
OpenTSDB only when `OPENTSDB_URL` is set.

PostgreSQL (5432) and the backend (8000) are published on 127.0.0.1 only, so other hosts cannot reach them.

## Quick Start

Every command on `docker-compose.local.yml` needs `POSTGRES_PASSWORD`, `SECRET_KEY` and `FIRST_SUPERUSER_PASSWORD`
in the environment or in `.env` next to the file, also without the `full` profile. Without one of them, docker
compose stops and names it:

```text
error while interpolating services.postgres.environment.POSTGRES_PASSWORD: required variable POSTGRES_PASSWORD is missing a value: set POSTGRES_PASSWORD, see opentsx-saas-backend/DOCKER-SETUP.md
```

With the `full` profile the backend also refuses a `SECRET_KEY` shorter than 32 characters and a password shorter
than 12 characters. `.env` in the repository root is git-ignored. Generate each value with:

```bash
python3 -c "import secrets; print(secrets.token_urlsafe(32))"
```

### Start Core Services Only

For PostgreSQL, Kafka and Schema Registry:

```bash
docker compose -f docker-compose.local.yml up -d
```

This starts:
- PostgreSQL
- Kafka
- Schema Registry

### Start Full Stack

To include backend and frontend services:

```bash
docker compose -f docker-compose.local.yml --profile full up -d
```

The backend container mounts `opentsx-saas-backend/` as `/app` and reads `opentsx-saas-backend/.env` when that file
exists. A key in that file that is not a setting of the backend stops its start, and the log names the key:

```bash
docker compose -f docker-compose.local.yml logs backend
```

The settings are listed in
[opentsx-saas-backend/README.md](../../../opentsx-saas-backend/README.md#environment-variables).

### Stop All Services

```bash
docker compose -f docker-compose.local.yml down
```

### Remove All Data Volumes

**WARNING:** This deletes all data!

```bash
docker compose -f docker-compose.local.yml down -v
```

## Service Details

### PostgreSQL (Port 5432)

**Purpose:** Relational database for application metadata and configuration

**Connection:**
```java
String url = "jdbc:postgresql://localhost:5432/opentsx";
String user = "opentsx";
String password = System.getenv("POSTGRES_PASSWORD");
```

**Health Check:**
```bash
docker exec opentsx-postgres pg_isready -U opentsx
```

### Kafka (Port 9092)

**Purpose:** one Kafka broker of Confluent Platform 7.6.0 in KRaft mode, broker and controller in one node.
Clients on the host connect to `localhost:9092`, containers in `opentsx-network` to `kafka:29092`.

**Kafka Connection:**
```java
Properties props = new Properties();
props.put("bootstrap.servers", "localhost:9092");
props.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
props.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer");
```

**Create Topic:**
```bash
docker exec opentsx-kafka kafka-topics \
  --create \
  --topic opentsx-timeseries \
  --bootstrap-server localhost:9092 \
  --partitions 3 \
  --replication-factor 1
```

**List Topics:**
```bash
docker exec opentsx-kafka kafka-topics \
  --list \
  --bootstrap-server localhost:9092
```

**Produce Messages:**
```bash
docker exec -it opentsx-kafka kafka-console-producer \
  --topic opentsx-timeseries \
  --bootstrap-server localhost:9092
```

**Consume Messages:**
```bash
docker exec -it opentsx-kafka kafka-console-consumer \
  --topic opentsx-timeseries \
  --from-beginning \
  --bootstrap-server localhost:9092
```

### Schema Registry (Port 8081)

**Purpose:** Avro schema management. Schema Registry stores the schemas in the Kafka topic `_schemas`.

**Register a schema and list the subjects:**
```bash
curl -X POST -H 'Content-Type: application/vnd.schemaregistry.v1+json' \
  --data '{"schema": "{\"type\": \"string\"}"}' \
  http://localhost:8081/subjects/opentsx-timeseries-value/versions
curl http://localhost:8081/subjects
```

## Development Workflow

### 1. Start Infrastructure

```bash
# Start all services
docker compose -f docker-compose.local.yml up -d

# Wait for services to be healthy
docker compose -f docker-compose.local.yml ps

# Check logs
docker compose -f docker-compose.local.yml logs -f
```

### 2. Run OpenTSx Demo Scripts

```bash
# Validate environment: exits 1 when a check fails, failed checks are on stderr
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

## Troubleshooting

### Kafka Not Starting

**Symptom:** `opentsx-kafka` container exits immediately

**Solution:**
```bash
# Check logs
docker logs opentsx-kafka

# Remove volume and restart
docker compose -f docker-compose.local.yml down -v
docker compose -f docker-compose.local.yml up -d
```

### Port Already in Use

**Symptom:** `Error starting userland proxy: listen tcp4 0.0.0.0:9092: bind: address already in use`

**Solution:**
```bash
# Find process using port
lsof -i :9092

# Stop that process, for example the stack of docker-compose.kafscale.yml, which also binds 9092
```

### Out of Memory

**Symptom:** Services crash or become unresponsive

**Solution:**
```bash
# Check Docker resources
docker stats

# Increase Docker Desktop memory limit to at least 8GB
# Settings → Resources → Memory

# Or start only the services you need
docker compose -f docker-compose.local.yml up -d kafka
```

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

Measured with `docker stats --no-stream` after `up -d`, one topic and one schema, on a host with 16 GB:

| Service         | Memory Usage |
|-----------------|--------------|
| PostgreSQL      | 30 MiB       |
| Kafka           | 378 MiB      |
| Schema Registry | 265 MiB      |
| **Total**       | **673 MiB**  |

## Next Steps

1. **Validate Setup:**
   ```bash
   ./bin/000_validate_environment.sh
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

## See Also

- [OpenTSx Manual](../../user-manual/README.md): user manual
- [Demo Scripts](../../../bin/README.md): scripts
- [Confluent Platform Documentation](https://docs.confluent.io/platform/current/overview.html)
