# 12. Operations

## 12.1 Local stacks

| File | Starts | Use |
|------|--------|-----|
| `docker-compose.kafscale.yml` | etcd, MinIO (S3, :9000/:9001), KafScale broker v1.6.0 (:9092) | **primary development stack** (v2) |
| `docker-compose.local.yml` | Postgres, Redis, Confluent Platform (Kafka, Schema Registry), HBase + OpenTSDB; profile `full` adds the SaaS backend and frontend | OpenTSDB store tests, 2.x tools, SaaS stack |

Both stacks bind port 9092, so do not run them at the same time.

Running the tools as services on Kubernetes (k3s) against an existing KafScale cluster — images, manifests, setup
and sizing plan, demos — is described in the [DevOps guide](../devops/README.md) (scripts in
[`deploy/k3s/`](../../deploy/k3s/README.md)).

## 12.2 Configuration

The v2 modules take their configuration from command-line options and constructor parameters. There are no hidden
global settings.

| Component | Settings |
|-----------|----------|
| KafScale/Kafka clients | `KafScaleClients.producerProps/consumerProps(bootstrap)` (idempotence off, `max.in.flight=1`, zstd, no auto-commit) |
| Iceberg | standard Iceberg catalog properties (`type=rest\|jdbc\|hive\|glue`, `uri`, `warehouse`, …); catalog name `opentsx` |
| OpenMetadata | `--om <url>/api`, token via `--token` or `OPENMETADATA_JWT` |
| Statistics processor | `--windows`, `--lateness`, `--alpha`, `--checkpoint` |
| Wind farm | scenario YAML, catalog YAML (`--catalog`) |

The 2.x tools in `opentsx-core`, `opentsx-lg` and `opentsx-connectors` use `ConfigManager`, which resolves a key in
this order:

1. JVM system property,
2. environment variable (literal, or mapped to `OPENTSX_…`),
3. profile overrides,
4. `config/opentsx-default.properties`.

`opentsx.properties.example` shows the available keys. The `config/cpl*.props` files are 2.x Kafka client profiles.

## 12.3 State and restarts

| Component | State | On restart |
|-----------|-------|-----------|
| `BucketWriter` | none (an unsealed bucket stays OPEN) | write the bucket again; duplicates are dropped by `episodeId` |
| episode index | `FileEpisodeIndex` / `IcebergEpisodeIndex` | `EpisodeIndexer.rebuild` recreates it from the log |
| statistics processor | checkpoint file (positions + engine state) | resumes; outputs at-least-once, readers keep the latest per key |
| `EpisodeLogSync` | positions returned to the caller | persist them; writes are idempotent |

## 12.4 Testing and CI

```bash
mvn verify                                   # unit and integration tests (KafScale tests skipped)
KAFSCALE_BOOTSTRAP=localhost:9092 mvn verify # including KafScale tests
cd python-package && pytest                  # Python
```

GitHub Actions runs these jobs:

* Java 17 and 21,
* Python 3.10–3.13,
* KafScale conformance (broker built from source) with the wind-farm and statistics integration tests,
* the cross-language job: Java writes Iceberg and PyIceberg must read identical bytes; the Python client queries the
  Java retrieval server.

## 12.5 Security notes

* Never commit credentials. `config/private/` is in `.gitignore`.
* Treat any key that was ever committed as compromised and rotate it.
* Use bot tokens for OpenMetadata.
* The retrieval server has no authentication. Run it behind a gateway, or on localhost only.

## 12.6 Troubleshooting

| Symptom | Cause / fix |
|---------|-------------|
| enforcer error "requires Java 17" | use JDK 17 or 21 (`JAVA_HOME`) |
| KafScale tests skipped | set `KAFSCALE_BOOTSTRAP=localhost:9092` |
| consumer hangs or rejoins on KafScale | do not use consumer groups. OpenTSx tools use manual assignment; do the same in your own code |
| `offsetsForTimes` returns nothing | not supported by KafScale. Use the episode index or `ReplayRequest.range` |
| duplicate episodes after a retry | expected without an idempotent producer. Stores and readers deduplicate by `episodeId` |
| PyIceberg warns "v0 schema" | the catalog was created by the Java JDBC catalog. The warning is harmless |
| `bin/opentsx`: "module … is not built" | run `mvn -q install -DskipTests` first |
