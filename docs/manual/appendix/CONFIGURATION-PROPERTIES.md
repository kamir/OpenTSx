# Configuration Properties

This document lists configuration inputs found across the codebase and scripts. Values can be provided via:

1. Properties file (see `config/opentsx-default.properties`)
2. Environment variables
3. JVM system properties (where noted)

## Default Config Profile

**File:** `config/opentsx-default.properties`

**Override with:**
- `OPENTSX_CONFIG_FILE` or `opentsx.config.file` (absolute or relative path)
- `OPENTSX_CONFIG_PROFILE` or `opentsx.config.profile` (loads `config/opentsx-<profile>.properties`)

## Core OpenTSx Properties (opentsx.*)

| Key | Env Var | Default | Notes |
|---|---|---|---|
| `opentsx.demo.output.dir` | `OPENTSX_DEMO_OUTPUT_DIR` | `./data/temp` | Demo output directory |
| `opentsx.demo.output.format` | `OPENTSX_DEMO_OUTPUT_FORMAT` | `csv` | `csv` or `sequence` |
| `opentsx.number.of.iterations` | `OPENTSX_NUMBER_OF_ITERATIONS` | `3` | Demo iteration count |
| `opentsx.show.gui` | `OPENTSX_SHOW_GUI` | `false` | Enables GUI demos |
| `opentsx.use.kafka` | `OPENTSX_USE_KAFKA` | `false` | Enables Kafka output |
| `opentsx.kafka.topic.map.file` | `OPENTSX_TOPIC_MAP_FILE_NAME` | `config/topiclist.def` | Topic definitions file |
| `opentsx.kafka.client.config.file` | `OPENTSX_PRIMARY_CLUSTER_CLIENT_CFG_FILE_NAME` | `config/cpl.props` | Kafka client config |
| `opentsx.data.directory` | `OPENTSX_DATA_DIRECTORY` | `./data` | Production data root |
| `opentsx.threads.pool.size` | `OPENTSX_THREADS_POOL_SIZE` | `4` | Thread pool size |
| `opentsx.retry.attempts` | `OPENTSX_RETRY_ATTEMPTS` | `3` | Retry count for IO |
| `opentsx.log.level` | `OPENTSX_LOG_LEVEL` | `INFO` | Logging level |

## Connector and Platform Files

| File | Purpose | Referenced In |
|---|---|---|
| `config/cpl.props` | Kafka client config | `OpenTSxClusterLink`, docs |
| `config/topiclist.def` | Topic map definitions | `TopicsUP`, docs |
| `config/private/ccloud.props` | Confluent Cloud credentials | docs, demos |

## System Properties (JVM)

| Key | Default | Notes |
|---|---|---|
| `kuduMaster` | `quickstart.cloudera` | Kudu master host (`KuduTelemetryDataLoader`) |
| `kuduTable` | `telemetry` | Kudu table name (`KuduTelemetryDataLoader`) |
| `user.name` | OS default | Used by SSH tools |

## Other Notable Environment Variables

| Env Var | Used In | Notes |
|---|---|---|
| `OPENTSX_TOPIC_MAP_FILE_NAME` | Kafka demos, Docker/K8s | Topic map location |
| `OPENTSX_PRIMARY_CLUSTER_CLIENT_CFG_FILE_NAME` | Kafka demos, Docker/K8s | Client config location |
| `OPENTSX_SHOW_GUI` | LG demos | GUI toggle |
| `OPENTSX_USE_KAFKA` | LG demos | Kafka output toggle |

## Notes

- Many demo scripts export `OPENTSX_*` variables directly; these override values from profile files.
- The `ConfigManager` in `opentsx-core` applies the same precedence order.
