# 4. Episode storage and replay on KafScale

Module: `opentsx-kafka-v2` ([README](../../opentsx-kafka-v2/README.md)). The module works with any Kafka-protocol
broker. It is designed for [KafScale](https://kafscale.io) and tested against KafScale v1.6.0 and Apache Kafka 3.9.

## 4.1 Check what KafScale supports

Measured by `opentsx-kafscale-conformance` unless the row says otherwise:

| Feature | KafScale v1.6.0 | How OpenTSx copes |
|---------|-----------------|-------------------|
| produce/fetch, zstd, headers, CreateTime | yes | used |
| timestamp seek (`offsetsForTimes`) | no | episode index (episodeId → topic/partition/offset) |
| idempotent producer | no | `enable.idempotence=false`, `max.in.flight.requests.per.connection=1`, dedup by `episodeId` |
| log compaction | no. KafScale accepts `cleanup.policy=compact` but does not compact. Compaction is not measured. | append-only topics. `BucketCatalog` scans the log: a SEALED manifest wins over OPEN, otherwise the later record wins. The episode index keeps the first position of a duplicate episode id. |
| consumer-group rejoin | no: a restarted member gets no assignment within 20 s | no consumer groups: manual assignment, positions kept by OpenTSx |
| standalone commits | no: a commit without group membership fails with `CommitFailedException` | positions kept by OpenTSx |
| Schema Registry | none (not measured) | self-describing single-object encoding |
| produce and fetch after a slow object-store write | refused for about a minute (not measured) | `KafScaleClients.producer` retries a send until `delivery.timeout.ms` (3 minutes). The readers of `opentsx-kafka-v2` and the stats and vector processors wait with `Patience`. |

## 4.2 Create the topics

`new TopicLayout("wind")` names the topics of the domain `wind`. It creates nothing.
`layout.provision(admin, dataPartitions, replicationFactor)` creates the missing topics
`tsx.wind.{episodes,buckets,series,observations,series-stats}.v2` with zstd compression and the delete cleanup
policy. `buckets` and `series` get one partition. The other three get `dataPartitions`.

The layout names more topics. `provision` does not create them:

| Method | Topic | Content |
|--------|-------|---------|
| `clean()` | `tsx.wind.clean.v2` | v2 episodes written by flows, one series per signal |
| `synthetic()` | `tsx.wind.synthetic.v2` | v2 episodes written by generators |
| `features()` | `tsx.wind.features.v2` | v2 episodes, one series per feature (tag `feature`) |
| `dags()` | `tsx.wind.dags.v1` | control topic of the vector processor: one JSON record per deployment or stop, key = DAG name |
| `dagStatus()` | `tsx.wind.dag-status.v1` | status per sink, reported by the vector processor (JSON) |
| `smartVectors(name)` | `tsx.wind.smart.<name>.vectors.v1` | vectors of a Smart Topic (`opentsx.vector.v1`, JSON) |

`<name>` is the name of the Smart Topic: lowercase letters, digits and hyphens. `provisionTopic`,
`provisionDagTopics` and `provisionSmartVectors` create these topics with one partition, zstd and the delete policy.
`bin/opentsx vectors` creates the control and status topics, and the sink topic of a DAG when the DAG arrives
([appendix A](appendix-a-cli.md)).

Episode records are laid out as follows:

| Part | Content |
|------|---------|
| key | `seriesId` |
| record timestamp | `tStart` |
| headers | `tsx-schema`, `tsx-bucket` |
| value | the single-object encoded episode |

## 4.3 Write a bucket

```java
TopicLayout layout = new TopicLayout("wind");
try (Admin admin = KafScaleClients.admin(bootstrap)) { layout.provision(admin, 6, (short) 1); }
EpisodeIndex index = new FileEpisodeIndex(Path.of("wind-index.tsv"));
try (KafkaProducer<String, byte[]> producer = KafScaleClients.producer(bootstrap);
     BucketWriter w = BucketWriter.open(producer, layout, index, "park-ost-01/2026-10-10", Map.of("scenario", "s1"))) {
    episodes.forEach(w::append);
}   // close() seals: SEALED manifest with count, time range and checksum
```

A bucket is sealed only if every episode was acknowledged. Otherwise the writer throws `BucketWriteException` and the
bucket stays OPEN.

## 4.4 Replay

```java
try (KafkaConsumer<byte[], byte[]> consumer = KafScaleClients.consumer(bootstrap)) {
    ReplayService replay = new ReplayService(consumer, layout, index);
    ReplayResult r = replay.replay(ReplayRequest.bucket(bucketId).withSpeed(60.0), sink);   // 1 h of data per minute
    r.verified();                                    // SEALED, count and checksum match the manifest
    replay.replay(ReplayRequest.range(Set.of(seriesId), fromMicros, toMicros), sink);
}
```

How replay behaves:

* Episodes arrive in event-time order.
* Memory is bounded.
* The index is derived state: `EpisodeIndexer.rebuild(...)` recreates it from the log.
* `BucketCatalog.load(...)` lists the buckets.
* `IcebergEpisodeIndex` keeps the index durably in Iceberg ([chapter 5](05-stores.md)). It buffers entries.
  Call `flush()` or `close()` after the bucket is sealed. `BucketWriter` does not flush the index. Entries that are
  not flushed are lost when the process ends.

## 4.5 Estimate the storage cost

Measured with 10-minute episodes at 1 Hz, zstd, batches of about 512 KB:

| Data | bytes per value |
|------|-----------------|
| values at sensor resolution, regular | **2.18** |
| full-precision doubles | 6.96 |
| sensor resolution with timestamp jitter (`IRREGULAR_DELTA`) | 4.07 |

Quantize at the source (the wind-farm normalizer does this from the catalog's `resolution`).
