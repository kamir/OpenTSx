# Archived modules

These modules were moved here on 2026-10-09 as part of TASK-011 (`EVOLUTION/TASK-011-datamodel-refresher-2026.md`).
They are **not part of the Maven reactor**, are not built or tested in CI, and are kept for reference only.

| Module | Former path | Why archived |
|--------|-------------|--------------|
| `opentsx-store-cassandra` | `/opentsx-store-cassandra` | Did not build on current JDKs (imports `jdk.nashorn.internal.runtime`); stored whole series as JSON blobs with string-concatenated CQL. Superseded by the planned store SPI (OpenTSDB, Iceberg). |
| `opentsx-kstreams-cassandra-state-store` | `/opentsx-kstreams-cassandra-state-store` | Generic Kafka Streams/Cassandra state-store example, unrelated to the OpenTSx data model; did not compile. |
| `opentsx-hive-udf` | `/opentsx-hive-udf` | Unresolvable dependencies (`pentaho-aggdesigner-algorithm`); only wrote to OpenTSDB. |
| `opentsx-ksql-udf` (`demo-udf`) | `/opentsx-ksql-udf` | Duplicate of `opentsx-ksql`; Confluent repository over plain `http://` is blocked by Maven 3.8+. |

Scripts under `opentsx-app-demos/` and `opentsx-ksql-app/bin/` that referenced the old paths are historical demos
(they also contain hard-coded local paths) and were left unchanged.

To revive a module, move it back to the repository root, add it to the root `pom.xml` `<modules>` section,
and port it to the current baseline (Java 17, current Kafka/Avro versions).
