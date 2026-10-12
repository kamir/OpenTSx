# OpenTSx User Manual

**Version 3.x (2026 refresh).** This manual describes OpenTSx as it is built and tested today: Java 17, Maven
reactor, Python package, CI on GitHub Actions.

OpenTSx is a toolkit for time-series data on event-streaming infrastructure. It provides:

* **A portable data model** for time series (v2): series identity, *episodes* as the unit of exchange and storage,
  and quality and summary data. Java and Python produce byte-identical records.
* **Cheap storage and replay** of episode buckets on [KafScale](https://kafscale.io), a Kafka-protocol broker
  backed by object storage.
* **One store abstraction** with Apache Iceberg, OpenTSDB and in-memory backends.
* **A wind-farm domain layer.** It contains a versioned data-point catalog mapped to IEC 61400-25-2, a normalizer,
  a simulator with ground truth, and power-curve analysis.
* **Streaming statistics and data quality**, with publishing to **OpenMetadata**.
* **Retrieval.** This covers segmentation, pattern search (MASS, SAX, DTW), a fluent Java API, REST, and a Python
  client.
* **Classic analytics** from the project's research roots: DFA, MFDFA, event synchronization and return-interval
  statistics.

## Who should read what

| You are … | Start with |
|-----------|-----------|
| new to OpenTSx | [Overview](01-overview.md) → [Getting started](02-getting-started.md) |
| a data engineer (ingest, storage, replay) | [Data model](03-data-model.md), [KafScale storage](04-kafscale-storage.md), [Stores](05-stores.md) |
| working with wind-farm data | [Wind farm](06-windpark.md), [Statistics & data quality](07-stats-quality.md), [OpenMetadata](08-openmetadata.md) |
| an analyst or data scientist | [Retrieval & patterns](09-retrieval.md), [Python](10-python.md), [Classic analytics](11-classic-analytics.md) |
| an operator | [Operations](12-operations.md), [DevOps guide: k3s + KafScale](../devops/README.md) |
| coming from OpenTSx 2.x (TimeSeriesObject, TSBucket) | [Migration](13-migration.md) |

## Conventions

* Timestamps are UTC, in **epoch microseconds** (`timestamp-micros`).
* Commands assume the repository root as working directory.
* `bin/opentsx <command>` runs the command-line tools (see [Appendix A](appendix-a-cli.md)).
* Design documents with the full rationale are in German in
  [`docs/datamodel-2026/`](../datamodel-2026/). [04-MODELL-V2-SPEC](../datamodel-2026/04-MODELL-V2-SPEC.md) is the
  normative data-model specification. The manual summarizes them in English.

## Contents

See [SUMMARY.md](SUMMARY.md).
