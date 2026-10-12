# Archived modules

These modules were moved here on 2026-10-09 as part of TASK-011 (`EVOLUTION/TASK-011-datamodel-refresher-2026.md`).
They are **not part of the Maven reactor**, are not built or tested in CI, and are kept for reference only.

| Module | Former path | Why archived |
|--------|-------------|--------------|
| `opentsx-store-cassandra` | `/opentsx-store-cassandra` | Did not build on current JDKs (imports `jdk.nashorn.internal.runtime`); stored whole series as JSON blobs with string-concatenated CQL. Superseded by the planned store SPI (OpenTSDB, Iceberg). |
| `opentsx-kstreams-cassandra-state-store` | `/opentsx-kstreams-cassandra-state-store` | Generic Kafka Streams/Cassandra state-store example, unrelated to the OpenTSx data model; did not compile. |
| `opentsx-hive-udf` | `/opentsx-hive-udf` | Unresolvable dependencies (`pentaho-aggdesigner-algorithm`); only wrote to OpenTSDB. |
| `opentsx-ksql-udf` (`demo-udf`) | `/opentsx-ksql-udf` | Duplicate of `opentsx-ksql`; Confluent repository over plain `http://` is blocked by Maven 3.8+. |
| `opentsx-ksql`, `opentsx-ksql-app` | `/opentsx-ksql*` (archived 2026-10-12) | ksqlDB UDFs and server scripts (Java 8, Kafka 2.4); ksqlDB is not part of the stack |
| `opentsx-kafka-streams-tsa` | `/opentsx-kafka-streams-tsa` (2026-10-12) | Kafka Streams examples (Java 8, Kafka 2.5); replaced by KafScale processors (`opentsx-stats`) |
| `opentsx-spring-example` | `/opentsx-spring-example` (2026-10-12) | Spring Boot 2.2 demo with a broken source layout |
| `opentsx-store-opentsdb` | `/opentsx-store-opentsdb` (2026-10-12) | 2.x OpenTSDB client; replaced by `opentsx-store-opentsdb-v2` |
| `opentsx-ext-connectors` | `/opentsx-ext-connectors` (2026-10-12) | Flume, HBase, Kudu, Wikipedia and Yahoo Finance loaders; depend on the archived OpenTSDB client |
| `ext-algorithms` | `/ext-algorithms` (2026-10-12) | wrapper around an external `corelib` that has to be installed by hand |
| `opentsx-predict` | `/opentsx-predict` (2026-10-12) | TensorFlow 1.15 hello-world; forecasting is planned as Smart Topics (P7) |
| `opentsx-processors` | `/opentsx-processors` (2026-10-12) | empty module (pom only) |
| `opentsx-app-tools` | `/opentsx-app-tools` (2026-10-12) | Swing network utilities (netcat recorder, SSH client, IP scan) on sshd-core 0.8.0; nothing depends on them; the sshd 2.x API is incompatible (Dependabot #85) |

Scripts under `archive/demos/opentsx-app-demos/` and `opentsx-ksql-app/bin/` that referenced the old paths are historical demos
(they also contain hard-coded local paths) and were left unchanged.

To revive a module, move it back to the repository root, add it to the root `pom.xml` `<modules>` section,
and port it to the current baseline (Java 17, current Kafka/Avro versions).
