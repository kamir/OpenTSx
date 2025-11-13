# OpenTSx Deployment Guide

## Table of Contents
- [Overview](#overview)
- [Prerequisites](#prerequisites)
- [Deployment Options](#deployment-options)
- [Local Development Setup](#local-development-setup)
- [Docker Deployment](#docker-deployment)
- [Multi-Region Deployment](#multi-region-deployment)
- [Kubernetes Deployment](#kubernetes-deployment)
- [Cloud Deployment](#cloud-deployment)
- [Configuration Management](#configuration-management)
- [Build Procedures](#build-procedures)
- [Monitoring & Operations](#monitoring--operations)
- [Troubleshooting](#troubleshooting)
- [Best Practices](#best-practices)

---

## Overview

OpenTSx supports multiple deployment patterns ranging from local development environments to sophisticated multi-region, multi-datacenter production deployments. This guide covers all deployment scenarios with step-by-step instructions and best practices.

---

## Prerequisites

### Software Requirements
- **Java:** 1.8 or higher (Java 11+ recommended for production)
- **Maven:** 3.6+
- **Docker:** 19.03+ (for containerized deployments)
- **Docker Compose:** 1.27+ (for orchestration)
- **Apache Kafka:** 2.3+ (or Confluent Platform 7.3+)
- **Git:** For source code management

### Hardware Requirements

#### Development Environment
- CPU: 4 cores minimum
- RAM: 8 GB minimum
- Disk: 20 GB available space

#### Production Environment
- CPU: 8+ cores per node
- RAM: 32+ GB per node
- Disk: 100+ GB SSD storage
- Network: 1 Gbps+ connectivity

### Network Requirements
- Ports: 2181-2183 (Zookeeper), 9091-9094 (Kafka), 8081 (Schema Registry)
- Firewall rules configured for inter-node communication
- DNS or /etc/hosts entries for cluster nodes

---

## Deployment Options

OpenTSx supports the following deployment patterns:

| Pattern | Use Case | Complexity | Scalability |
|---------|----------|------------|-------------|
| Local Development | Testing, development | Low | Single node |
| Docker Compose | Small deployments, demos | Medium | 1-3 nodes |
| Multi-Region Kafka | Production, HA | High | 3+ datacenters |
| Kubernetes | Cloud-native, auto-scaling | High | Elastic |
| Confluent Cloud | Managed Kafka | Low | Fully managed |

---

## Local Development Setup

### Step 1: Clone and Build

```bash
# Clone repository
git clone https://github.com/kamir/OpenTSx.git
cd OpenTSx

# Build all modules
./bin/010_build.sh

# Or use Maven directly
mvn clean install -DskipTests
```

### Step 2: Start Local Kafka

**Option A: Using Docker Compose**
```bash
# Start Kafka and Zookeeper
docker-compose -f docker/local-dev.yml up -d

# Verify services
docker-compose ps
```

**Option B: Using Confluent CLI**
```bash
# Start Confluent Platform locally
confluent local services start
```

### Step 3: Configure Connection

```bash
# Copy configuration template
cp config/cpl.props config/local.props

# Edit configuration
vim config/local.props
```

**Update `local.props`:**
```properties
bootstrap.servers=localhost:9092
schema.registry.url=http://localhost:8081
key.serializer=org.apache.kafka.common.serialization.StringSerializer
value.serializer=io.confluent.kafka.serializers.KafkaAvroSerializer
key.deserializer=org.apache.kafka.common.serialization.StringDeserializer
value.deserializer=io.confluent.kafka.serializers.KafkaAvroDeserializer
```

### Step 4: Run Data Generator

```bash
cd opentsx-lg

# Set environment variables
export OPENTSX_TOPIC_MAP_FILE_NAME=../config/topiclist.def
export OPENTSX_PRIMARY_CLUSTER_CLIENT_CFG_FILE_NAME=../config/local.props
export OPENTSX_SHOW_GUI=true
export OPENTSX_USE_KAFKA=true

# Run generator
mvn exec:java -Dexec.mainClass="org.opentsx.lg.TSDataSineWaveGenerator"
```

### Step 5: Verify Data Flow

```bash
# List topics
kafka-topics --bootstrap-server localhost:9092 --list

# Consume messages
kafka-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic OpenTSx_Events \
  --from-beginning
```

---

## Docker Deployment

### Single-Node Docker Deployment

#### Step 1: Build Docker Images

```bash
# Build time series generator image
cd opentsx-lg
mvn clean package -PSimpleTimeSeriesProducer,Docker

# Verify image
docker images | grep opentsx
```

#### Step 2: Create Docker Network

```bash
docker network create opentsx-network
```

#### Step 3: Start Services

```bash
# Start Zookeeper
docker run -d \
  --name zookeeper \
  --network opentsx-network \
  -p 2181:2181 \
  confluentinc/cp-zookeeper:7.3.0 \
  bash -c "export ZOOKEEPER_CLIENT_PORT=2181 && \
           export ZOOKEEPER_TICK_TIME=2000 && \
           /etc/confluent/docker/run"

# Start Kafka
docker run -d \
  --name kafka \
  --network opentsx-network \
  -p 9092:9092 \
  -e KAFKA_BROKER_ID=1 \
  -e KAFKA_ZOOKEEPER_CONNECT=zookeeper:2181 \
  -e KAFKA_ADVERTISED_LISTENERS=PLAINTEXT://localhost:9092 \
  -e KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=1 \
  confluentinc/cp-kafka:7.3.0

# Start Schema Registry
docker run -d \
  --name schema-registry \
  --network opentsx-network \
  -p 8081:8081 \
  -e SCHEMA_REGISTRY_HOST_NAME=schema-registry \
  -e SCHEMA_REGISTRY_KAFKASTORE_BOOTSTRAP_SERVERS=kafka:9092 \
  confluentinc/cp-schema-registry:7.3.0

# Start OpenTSx Generator
docker run -d \
  --name opentsx-generator \
  --network opentsx-network \
  -e OPENTSX_SHOW_GUI=false \
  -e OPENTSX_USE_KAFKA=true \
  -v $(pwd)/config:/config \
  opentsx/time-series-generator:3.0.0
```

### Docker Compose Deployment

**Create `docker-compose.yml`:**
```yaml
version: '3.8'

services:
  zookeeper:
    image: confluentinc/cp-zookeeper:7.3.0
    hostname: zookeeper
    container_name: zookeeper
    ports:
      - "2181:2181"
    environment:
      ZOOKEEPER_CLIENT_PORT: 2181
      ZOOKEEPER_TICK_TIME: 2000
    volumes:
      - zookeeper-data:/var/lib/zookeeper/data
      - zookeeper-logs:/var/lib/zookeeper/log

  kafka:
    image: confluentinc/cp-kafka:7.3.0
    hostname: kafka
    container_name: kafka
    depends_on:
      - zookeeper
    ports:
      - "9092:9092"
    environment:
      KAFKA_BROKER_ID: 1
      KAFKA_ZOOKEEPER_CONNECT: 'zookeeper:2181'
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://localhost:9092
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1
      KAFKA_TRANSACTION_STATE_LOG_MIN_ISR: 1
      KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR: 1
      KAFKA_AUTO_CREATE_TOPICS_ENABLE: 'true'
    volumes:
      - kafka-data:/var/lib/kafka/data

  schema-registry:
    image: confluentinc/cp-schema-registry:7.3.0
    hostname: schema-registry
    container_name: schema-registry
    depends_on:
      - kafka
    ports:
      - "8081:8081"
    environment:
      SCHEMA_REGISTRY_HOST_NAME: schema-registry
      SCHEMA_REGISTRY_KAFKASTORE_BOOTSTRAP_SERVERS: 'kafka:9092'
      SCHEMA_REGISTRY_LISTENERS: http://0.0.0.0:8081

  cassandra:
    image: cassandra:3.11.4
    hostname: cassandra
    container_name: cassandra
    ports:
      - "9042:9042"
    environment:
      CASSANDRA_CLUSTER_NAME: opentsx-cluster
      CASSANDRA_DC: dc1
      CASSANDRA_ENDPOINT_SNITCH: GossipingPropertyFileSnitch
    volumes:
      - cassandra-data:/var/lib/cassandra

  opentsx-generator:
    image: opentsx/time-series-generator:3.0.0
    hostname: opentsx-generator
    container_name: opentsx-generator
    depends_on:
      - kafka
      - schema-registry
    environment:
      OPENTSX_SHOW_GUI: 'false'
      OPENTSX_USE_KAFKA: 'true'
      OPENTSX_TOPIC_MAP_FILE_NAME: /config/topiclist.def
      OPENTSX_PRIMARY_CLUSTER_CLIENT_CFG_FILE_NAME: /config/cpl.props
    volumes:
      - ./config:/config

volumes:
  zookeeper-data:
  zookeeper-logs:
  kafka-data:
  cassandra-data:
```

**Start the stack:**
```bash
docker-compose up -d
```

**Monitor logs:**
```bash
docker-compose logs -f opentsx-generator
```

**Stop the stack:**
```bash
docker-compose down
```

---

## Multi-Region Deployment

OpenTSx supports sophisticated multi-region deployments for high availability and disaster recovery.

### Architecture: Three-Datacenter Setup

```
┌─────────────────────────────────────────────────────────────┐
│                    Multi-Region Architecture                 │
├─────────────────────────────────────────────────────────────┤
│                                                               │
│  Region West (MDC1)      Region Central (MDC2)    Region East│
│  ┌─────────────┐        ┌─────────────┐         ┌──────────┐│
│  │ Kafka       │◄──────►│ Kafka       │◄───────►│ Kafka    ││
│  │ Cluster 1   │ Replic │ Cluster 2   │ Replic  │ Cluster 3││
│  │             │  ation │             │  ation  │          ││
│  │ Cassandra   │        │ Cassandra   │         │ Cassandra││
│  │ DC1         │        │ DC2         │         │ DC3      ││
│  └─────────────┘        └─────────────┘         └──────────┘│
│                                                               │
└─────────────────────────────────────────────────────────────┘
```

### Configuration: Multi-Region Kafka

**Location:** `opentsx-clusters/cp-multiregion/`

**Docker Compose Setup:**
```bash
cd opentsx-clusters/cp-multiregion

# Review configuration
cat docker-compose.yml

# Start multi-region cluster
docker-compose up -d

# Verify all brokers
docker-compose ps
```

**Key Configuration Elements:**

```yaml
# Broker 1 (West Region)
broker-1:
  environment:
    KAFKA_BROKER_ID: 1
    KAFKA_BROKER_RACK: 'west'
    KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://broker-1:9091
    KAFKA_ZOOKEEPER_CONNECT: 'zookeeper-1:2181,zookeeper-2:2182,zookeeper-3:2183'

# Broker 2 (Central Region)
broker-2:
  environment:
    KAFKA_BROKER_ID: 2
    KAFKA_BROKER_RACK: 'central'
    KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://broker-2:9092

# Broker 3 (East Region)
broker-3:
  environment:
    KAFKA_BROKER_ID: 3
    KAFKA_BROKER_RACK: 'east'
    KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://broker-3:9093
```

### Multi-Datacenter Cassandra

**Cassandra Configuration:**

```yaml
cassandra-dc1:
  image: cassandra:3.11.4
  environment:
    CASSANDRA_CLUSTER_NAME: 'opentsx-cluster'
    CASSANDRA_DC: 'dc1'
    CASSANDRA_RACK: 'rack1'
    CASSANDRA_SEEDS: 'cassandra-dc1,cassandra-dc2,cassandra-dc3'
    CASSANDRA_ENDPOINT_SNITCH: 'GossipingPropertyFileSnitch'

cassandra-dc2:
  environment:
    CASSANDRA_DC: 'dc2'
    CASSANDRA_RACK: 'rack1'
    CASSANDRA_SEEDS: 'cassandra-dc1,cassandra-dc2,cassandra-dc3'

cassandra-dc3:
  environment:
    CASSANDRA_DC: 'dc3'
    CASSANDRA_RACK: 'rack1'
    CASSANDRA_SEEDS: 'cassandra-dc1,cassandra-dc2,cassandra-dc3'
```

**Replication Strategy:**

```sql
-- Create keyspace with multi-DC replication
CREATE KEYSPACE opentsx
WITH REPLICATION = {
  'class': 'NetworkTopologyStrategy',
  'dc1': 3,
  'dc2': 3,
  'dc3': 3
};

-- Create table
USE opentsx;
CREATE TABLE time_series (
  series_id text,
  bucket timestamp,
  timestamp bigint,
  value double,
  metadata map<text, text>,
  PRIMARY KEY ((series_id, bucket), timestamp)
) WITH CLUSTERING ORDER BY (timestamp DESC);
```

### Monitoring Multi-Region Setup

**Grafana Dashboards:**

```bash
# Access Grafana
open http://localhost:3000

# Dashboards located at:
# opentsx-clusters/cp-multiregion/grafana/dashboards/
```

**Available Dashboards:**
- Kafka Cluster Metrics
- Consumer Lag Monitoring
- Producer Performance
- Zookeeper Health
- Cassandra Datacenter Status

---

## Kubernetes Deployment

### Prerequisites
- Kubernetes 1.19+
- kubectl configured
- Helm 3.0+ (optional)

### Step 1: Create Namespace

```bash
kubectl create namespace opentsx
```

### Step 2: Deploy Kafka (using Strimzi Operator)

```bash
# Install Strimzi operator
kubectl create -f 'https://strimzi.io/install/latest?namespace=opentsx' -n opentsx

# Create Kafka cluster
cat <<EOF | kubectl apply -n opentsx -f -
apiVersion: kafka.strimzi.io/v1beta2
kind: Kafka
metadata:
  name: opentsx-cluster
spec:
  kafka:
    version: 3.4.0
    replicas: 3
    listeners:
      - name: plain
        port: 9092
        type: internal
        tls: false
      - name: tls
        port: 9093
        type: internal
        tls: true
    config:
      offsets.topic.replication.factor: 3
      transaction.state.log.replication.factor: 3
      transaction.state.log.min.isr: 2
    storage:
      type: persistent-claim
      size: 100Gi
  zookeeper:
    replicas: 3
    storage:
      type: persistent-claim
      size: 10Gi
  entityOperator:
    topicOperator: {}
    userOperator: {}
EOF
```

### Step 3: Deploy Cassandra

```bash
# Using Cassandra Operator
kubectl apply -f https://raw.githubusercontent.com/k8ssandra/k8ssandra-operator/main/config/crd/bases/k8ssandra.io_k8ssandraclusters.yaml

# Create Cassandra cluster
cat <<EOF | kubectl apply -n opentsx -f -
apiVersion: k8ssandra.io/v1alpha1
kind: K8ssandraCluster
metadata:
  name: opentsx-cassandra
spec:
  cassandra:
    serverVersion: 3.11.14
    datacenters:
      - metadata:
          name: dc1
        size: 3
        storageConfig:
          cassandraDataVolumeClaimSpec:
            accessModes:
              - ReadWriteOnce
            resources:
              requests:
                storage: 100Gi
EOF
```

### Step 4: Deploy OpenTSx Applications

**Create Deployment:**

```yaml
# opentsx-generator-deployment.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: opentsx-generator
  namespace: opentsx
spec:
  replicas: 2
  selector:
    matchLabels:
      app: opentsx-generator
  template:
    metadata:
      labels:
        app: opentsx-generator
    spec:
      containers:
      - name: generator
        image: opentsx/time-series-generator:3.0.0
        env:
        - name: OPENTSX_USE_KAFKA
          value: "true"
        - name: OPENTSX_SHOW_GUI
          value: "false"
        - name: OPENTSX_TOPIC_MAP_FILE_NAME
          value: "/config/topiclist.def"
        - name: OPENTSX_PRIMARY_CLUSTER_CLIENT_CFG_FILE_NAME
          value: "/config/cpl.props"
        volumeMounts:
        - name: config
          mountPath: /config
      volumes:
      - name: config
        configMap:
          name: opentsx-config
```

**Create ConfigMap:**

```bash
kubectl create configmap opentsx-config \
  --from-file=config/topiclist.def \
  --from-file=config/cpl.props \
  -n opentsx
```

**Deploy:**

```bash
kubectl apply -f opentsx-generator-deployment.yaml
```

### Step 5: Verify Deployment

```bash
# Check pods
kubectl get pods -n opentsx

# Check logs
kubectl logs -n opentsx deployment/opentsx-generator -f

# Port forward for testing
kubectl port-forward -n opentsx svc/opentsx-cluster-kafka-bootstrap 9092:9092
```

---

## Cloud Deployment

### Confluent Cloud Deployment

#### Step 1: Create Confluent Cloud Cluster

```bash
# Install Confluent CLI
curl -sL --http1.1 https://cnfl.io/cli | sh -s -- latest

# Login
confluent login

# Create cluster
confluent kafka cluster create opentsx-cluster \
  --cloud aws \
  --region us-east-1 \
  --type basic
```

#### Step 2: Configure API Keys

```bash
# Create API key
confluent api-key create --resource <cluster-id>

# Create Schema Registry API key
confluent schema-registry cluster describe
confluent api-key create --resource <sr-cluster-id>
```

#### Step 3: Update Configuration

**Create `config/ccloud.props`:**

**⚠️ SECURITY WARNING: Never commit this file to Git!**

```properties
# Kafka Cluster
bootstrap.servers=pkc-xxxxx.us-east-1.aws.confluent.cloud:9092
security.protocol=SASL_SSL
sasl.mechanisms=PLAIN
sasl.username=<API_KEY>
sasl.password=<API_SECRET>

# Schema Registry
schema.registry.url=https://psrc-xxxxx.us-east-1.aws.confluent.cloud
basic.auth.credentials.source=USER_INFO
basic.auth.user.info=<SR_API_KEY>:<SR_API_SECRET>

# Serializers
key.serializer=org.apache.kafka.common.serialization.StringSerializer
value.serializer=io.confluent.kafka.serializers.KafkaAvroSerializer
key.deserializer=org.apache.kafka.common.serialization.StringDeserializer
value.deserializer=io.confluent.kafka.serializers.KafkaAvroDeserializer
```

#### Step 4: Deploy Application

```bash
# Set environment
export OPENTSX_PRIMARY_CLUSTER_CLIENT_CFG_FILE_NAME=config/ccloud.props

# Run locally pointing to cloud
cd opentsx-lg
mvn exec:java -Dexec.mainClass="org.opentsx.lg.TSDataSineWaveGenerator"

# Or deploy to cloud VM/container
```

### AWS Deployment

#### Using AWS MSK (Managed Streaming for Kafka)

```bash
# Create MSK cluster (via AWS Console or CLI)
aws kafka create-cluster \
  --cluster-name opentsx-cluster \
  --broker-node-group-info file://broker-config.json \
  --kafka-version 2.8.1 \
  --number-of-broker-nodes 3

# Get bootstrap servers
aws kafka get-bootstrap-brokers --cluster-arn <cluster-arn>
```

#### Deploy on EC2

```bash
# Launch EC2 instance with appropriate security groups
# SSH into instance
ssh -i key.pem ec2-user@<instance-ip>

# Install Java and Maven
sudo yum install -y java-11-amazon-corretto maven git

# Clone and build
git clone https://github.com/kamir/OpenTSx.git
cd OpenTSx
./bin/010_build.sh

# Configure MSK connection
vim config/aws-msk.props

# Run application
cd opentsx-lg
./bin/run_cluster.sh
```

---

## Configuration Management

### Configuration Files

OpenTSx uses property files for configuration:

**File Structure:**
```
config/
├── cpl.props             # Default configuration
├── cpl_local.props       # Local development
├── cpl_iMac.props        # Mac-specific
├── topiclist.def         # Topic definitions
└── private/              # ⚠️ NEVER commit to Git
    └── ccloud.props      # Cloud credentials
```

### Environment Variables

OpenTSx supports configuration via environment variables:

| Variable | Purpose | Example |
|----------|---------|---------|
| `OPENTSX_TOPIC_MAP_FILE_NAME` | Topic list file path | `../config/topiclist.def` |
| `OPENTSX_PRIMARY_CLUSTER_CLIENT_CFG_FILE_NAME` | Kafka config | `../config/cpl.props` |
| `OPENTSX_SHOW_GUI` | Enable GUI | `true` / `false` |
| `OPENTSX_USE_KAFKA` | Enable Kafka output | `true` / `false` |

### Secrets Management

**Best Practices:**

1. **Use Environment Variables:**
```bash
export KAFKA_BOOTSTRAP_SERVERS="..."
export KAFKA_SASL_USERNAME="..."
export KAFKA_SASL_PASSWORD="..."
```

2. **Use External Secrets Management:**
```bash
# AWS Secrets Manager
aws secretsmanager get-secret-value --secret-id opentsx/kafka

# HashiCorp Vault
vault kv get secret/opentsx/kafka
```

3. **Use Kubernetes Secrets:**
```bash
kubectl create secret generic kafka-credentials \
  --from-literal=username=<key> \
  --from-literal=password=<secret> \
  -n opentsx
```

### Topic Configuration

**File:** `config/topiclist.def`

```
OpenTSx_Episodes_A, OpenTSx_Episodes_B
OpenTSx_Events, OpenTSx_Event_Flow_State
latency_benchmark_request, latency_benchmark_response
```

**Create Topics:**
```bash
# Manual creation
kafka-topics --create \
  --bootstrap-server localhost:9092 \
  --topic OpenTSx_Events \
  --partitions 10 \
  --replication-factor 3

# Or use TopicsManagerTool
java -cp opentsx-connectors.jar \
  org.opentsx.connectors.topicmanager.TopicsManagerTool \
  --config config/cpl.props \
  --create --topic-file config/topiclist.def
```

---

## Build Procedures

### Maven Build Profiles

OpenTSx supports multiple build profiles:

```bash
# Default build (active modules only)
mvn clean install

# Build with specific profile
mvn clean package -PSimpleTimeSeriesProducer

# Build Docker images
mvn clean package -PDocker

# Build both
mvn clean package -PSimpleTimeSeriesProducer,Docker

# Skip tests (faster)
mvn clean install -DskipTests

# Parallel build
mvn clean install -T 4  # 4 threads
```

### Building Individual Modules

```bash
# Core module
cd opentsx-core
mvn clean install

# Data generator
cd opentsx-lg
mvn clean package

# Connectors
cd opentsx-connectors
mvn clean install
```

### Creating Uber JAR

```bash
cd opentsx-lg
mvn clean package -PSimpleTimeSeriesProducer

# Output: opentsx-lg/target/opentsx-lg-3.0.0-jar-with-dependencies.jar

# Run standalone
java -jar target/opentsx-lg-3.0.0-jar-with-dependencies.jar
```

### Building Container Images

```bash
# Build generator image
cd opentsx-lg
mvn clean package -PDocker

# Verify
docker images | grep opentsx

# Tag for registry
docker tag opentsx/time-series-generator:3.0.0 \
  myregistry.com/opentsx/time-series-generator:3.0.0

# Push to registry
docker push myregistry.com/opentsx/time-series-generator:3.0.0
```

---

## Monitoring & Operations

### Grafana Dashboards

OpenTSx includes pre-built Grafana dashboards:

**Location:** `opentsx-clusters/*/grafana/dashboards/`

**Available Dashboards:**
- `kafka_consumer_metrics.json` - Consumer lag, throughput
- `kafka_producer_metrics.json` - Producer performance
- `kafka_broker_metrics.json` - Broker health
- `zookeeper_metrics.json` - Zookeeper status
- `cassandra_metrics.json` - Cassandra performance

**Setup:**

```bash
# Start Grafana
docker run -d \
  --name grafana \
  -p 3000:3000 \
  grafana/grafana:latest

# Import dashboards
# 1. Login to http://localhost:3000 (admin/admin)
# 2. Configuration → Data Sources → Add Prometheus
# 3. Import JSON dashboards from grafana/dashboards/
```

### Prometheus Metrics

**JMX Exporter Configuration:**

```yaml
# jmx-exporter-config.yml
lowercaseOutputName: true
rules:
  - pattern: kafka.server<type=(.+), name=(.+)><>(.+)
    name: kafka_server_$1_$2_$3
  - pattern: kafka.network<type=(.+), name=(.+)><>(.+)
    name: kafka_network_$1_$2_$3
```

**Start JMX Exporter:**

```bash
java -javaagent:jmx_prometheus_javaagent.jar=7071:jmx-exporter-config.yml \
  -jar opentsx-lg.jar
```

### Health Checks

**Kafka Health:**
```bash
# Broker health
kafka-broker-api-versions --bootstrap-server localhost:9092

# Topic health
kafka-topics --describe --bootstrap-server localhost:9092
```

**Cassandra Health:**
```bash
# Node status
nodetool status

# Cluster health
nodetool info

# Ring status
nodetool ring
```

**Application Health:**
```bash
# Check processes
ps aux | grep opentsx

# Check logs
tail -f logs/opentsx.log

# Check Kafka consumption
kafka-consumer-groups --bootstrap-server localhost:9092 --group opentsx-group --describe
```

---

## Troubleshooting

### Common Issues

#### Issue 1: Schema Registry Connection Failure

**Symptoms:**
```
Error: Failed to connect to Schema Registry at http://localhost:8081
```

**Solution:**
```bash
# Check if Schema Registry is running
curl http://localhost:8081/subjects

# Verify configuration
grep schema.registry.url config/cpl.props

# Restart Schema Registry
docker restart schema-registry
```

#### Issue 2: Kafka Connection Timeout

**Symptoms:**
```
TimeoutException: Failed to update metadata after 60000 ms
```

**Solution:**
```bash
# Verify Kafka is accessible
telnet localhost 9092

# Check firewall rules
sudo iptables -L -n | grep 9092

# Verify advertised.listeners
docker exec kafka kafka-configs --describe --bootstrap-server localhost:9092 --entity-type brokers --entity-name 1
```

#### Issue 3: Cassandra Write Failure

**Symptoms:**
```
NoHostAvailableException: All host(s) tried for query failed
```

**Solution:**
```bash
# Check Cassandra status
docker exec cassandra nodetool status

# Verify keyspace exists
docker exec -it cassandra cqlsh -e "DESCRIBE KEYSPACES;"

# Check consistency level
# Reduce from QUORUM to ONE for testing
```

#### Issue 4: Out of Memory Errors

**Symptoms:**
```
java.lang.OutOfMemoryError: Java heap space
```

**Solution:**
```bash
# Increase heap size
export JAVA_OPTS="-Xmx4g -Xms2g"

# Or in Maven
mvn exec:java -Dexec.args="-Xmx4g -Xms2g"

# For Docker
docker run -e JAVA_OPTS="-Xmx4g" opentsx/time-series-generator:3.0.0
```

### Logging and Debugging

**Enable Debug Logging:**

```bash
# Edit log4j.properties
vim opentsx-core/src/main/resources/log4j.properties

# Set debug level
log4j.rootLogger=DEBUG, stdout, file
log4j.logger.org.opentsx=DEBUG
log4j.logger.org.apache.kafka=DEBUG
```

**View Logs:**

```bash
# Application logs
tail -f logs/opentsx.log

# Kafka logs
docker logs -f kafka

# Cassandra logs
docker logs -f cassandra
```

---

## Best Practices

### 1. Security Best Practices

**See [SECURITY.md](SECURITY.md) for comprehensive security guidelines.**

- ✅ Enable TLS/SSL for all Kafka connections
- ✅ Use SASL authentication
- ✅ Store credentials in secrets management system
- ✅ Implement network segmentation
- ✅ Regular security audits

### 2. Performance Best Practices

- Configure appropriate partition counts (rule: 3x broker count)
- Use compression (snappy or lz4)
- Tune consumer `fetch.min.bytes` and `fetch.max.wait.ms`
- Enable producer `acks=all` for critical data
- Use batch processing for Cassandra writes

### 3. Operational Best Practices

- Implement comprehensive monitoring
- Set up alerting for critical metrics
- Regular backups of Cassandra data
- Documented runbooks for common issues
- Capacity planning and load testing

### 4. Development Best Practices

- Use local development with Docker Compose
- Test with representative data volumes
- Performance profiling before production
- Code reviews for all changes
- Continuous integration testing

---

## Deployment Checklist

Before deploying to production, verify:

- [ ] All security vulnerabilities addressed (see [SECURITY.md](SECURITY.md))
- [ ] TLS/SSL enabled for all components
- [ ] Credentials stored in secrets management
- [ ] Monitoring and alerting configured
- [ ] Backup procedures in place
- [ ] Disaster recovery plan documented
- [ ] Capacity planning completed
- [ ] Load testing performed
- [ ] Runbooks created for operations
- [ ] Change management process followed

---

## Additional Resources

- **Architecture:** [ARCHITECTURE.md](ARCHITECTURE.md)
- **Modules:** [MODULES.md](MODULES.md)
- **Features:** [FEATURES.md](FEATURES.md)
- **Security:** [SECURITY.md](SECURITY.md)
- **Confluent Docs:** https://docs.confluent.io
- **Kafka Docs:** https://kafka.apache.org/documentation/
- **Cassandra Docs:** https://cassandra.apache.org/doc/

---

## Support

For deployment assistance:
- **Issues:** [GitHub Issues](https://github.com/kamir/OpenTSx/issues)
- **Documentation:** [docs/](docs/)
- **Examples:** [opentsx-app-demos/](opentsx-app-demos/)

---

**Successful deployment requires careful planning and adherence to best practices. Always test in non-production environments first!**
