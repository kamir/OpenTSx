# 13. Migration from 2.x and archived components

## 13.1 From the 2.x model to v2

| 2.x | v2 |
|-----|----|
| `TimeSeriesObject` (x/y vectors, label) | `Episode` (time axis in µs, values, quality, summary, labels, provenance) |
| label string `"metric k=v,…"` | `SeriesKey` (`metric`, `tags`, `unit`, xxHash64 `seriesId`) |
| TSBucket (Hadoop SequenceFile) | bucket = episodes + `BucketManifest` on KafScale, or episodes in Iceberg |
| `EpisodesRecord` (`opentsx-data`) | `Episode` (`opentsx-model`) |
| Confluent Schema Registry framing | single-object encoding (self-describing); Confluent framing is still readable |

`opentsx-legacy-bridge` converts the 2.x formats:

```java
SeriesKey key = LegacyBridge.seriesKeyFromLabel("wind.met.wind_speed park=ost-01,turbine=T07", "m/s");
Episode e = LegacyBridge.fromTimeSeriesObject(tso, key, TimeUnitOfX.EPOCH_MILLIS, "import");   // lossless
TimeSeriesObject back = LegacyBridge.toTimeSeriesObject(e, TimeUnitOfX.EPOCH_MILLIS);
Episode fromV1 = LegacyBridge.fromEpisodesRecord(episodesRecord, key);
List<Episode> eps = LegacyBucketImport.read(Path.of("bucket.tsb.vec.seq"), t0Micros, intervalMicros, "m/s", "import");
```

The imported episodes can then be written with a `BucketWriter` or to any store. `opentsx-data` (2.x) is frozen;
see its README.

## 13.2 What was archived

Archived components are not built and not maintained. The 2025 documentation and the demos are kept under
`archive/` for reference. The archived Maven modules were removed from `master` in October 2026 because their
outdated dependencies kept raising security alerts; their last state is commit `315f01d`.

| Archived | Replaced by / reason |
|----------|----------------------|
| `opentsx-store-cassandra`, `opentsx-kstreams-cassandra-state-store` | Iceberg/OpenTSDB stores behind the SPI |
| `opentsx-hive-udf`, `opentsx-ksql-udf`, `opentsx-ksql`, `opentsx-ksql-app` | Iceberg + SQL engines; ksqlDB is not part of the stack |
| `opentsx-kafka-streams-tsa`, `opentsx-spring-example` | KafScale processors (`opentsx-stats`), Flink |
| `opentsx-store-opentsdb` (2.x client) | `opentsx-store-opentsdb-v2` |
| `opentsx-ext-connectors`, `ext-algorithms` | Flume/HBase/Kudu loaders; external corelib wrapper |
| `opentsx-predict` (TensorFlow 1.15 demo), `opentsx-processors` (empty) | Smart Topics (planned, [chapter 15](15-roadmap.md)) |
| `opentsx-app-tools` (SSH console, cluster scan, NetCat recorder; sshd-core 0.8) | no replacement; no module depended on it |
| `opentsx-kstreams` (Kafka Streams/Cassandra project shell) | KafScale processors (`opentsx-stats`) |
| demos and cluster scripts (`opentsx-app-demos`, `-cloud-bridge`, `-cluster-demo0`, `-clusters`, `-cv`, `-jetson-nano`, `scala-scripts`, `modules/`) | historical: Confluent Cloud, Kudu, Spark 2 |
| 2025 documentation (old manual, onboarding tracks, devguide guides, MANUALS) | this manual |

The modules (all rows up to `opentsx-kstreams`) are only in the Git history. To get one back:

```bash
git checkout 315f01d -- archive/modules/opentsx-store-cassandra   # or archive/opentsx-kstreams
```

The module overview is `archive/modules/README.md` at `315f01d`; the archived documentation and demos are described in
`archive/docs/README.md` and `archive/demos/README.md`.
