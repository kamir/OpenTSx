# Integration Guide

This guide covers how OpenTSx integrates with your data platform. Use it after completing a quick demo.

## When to use this guide
- You want streaming ingestion with Kafka or ksqlDB.
- You need to connect to storage backends (Cassandra, OpenTSDB, HDFS).
- You plan to deploy processing with Flink or KStreams.

## Integration Paths

### 1) Kafka and ksqlDB
- Produce and consume time series events via OpenTSx connectors.
- Configure clusters via `config/cpl.props`.
- Explore ksqlDB UDFs in `opentsx-ksql-udf/`.

### 2) Stream Processing
- Use KStreams in `opentsx-kafka-streams-tsa/`.
- Run Flink jobs in `opentsx-flink-core/`.

### 3) Storage Backends
- Cassandra integration: `opentsx-store-cassandra/`.
- OpenTSDB integration: `opentsx-store-opentsdb/`.
- HDFS batch workflows in `opentsx-data/`.

### 4) SaaS Stack
- Full stack setup in `opentsx-saas-backend/` and `opentsx-saas-frontend/`.

## Step 2: Go Deeper
- Read [Core Concepts](../core-concepts/README.md) for the data model.
- Review [Best Practices](../best-practices/README.md) for production patterns.
- Follow [Local Development Guide](../../infrastructure/local-development.md) for infrastructure.
