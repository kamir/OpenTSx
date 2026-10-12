# 4. Episode storage and replay on KafScale

Module: `opentsx-kafka-v2` ([README](../../opentsx-kafka-v2/README.md)). The module works with any Kafka-protocol
broker. It is designed for [KafScale](https://kafscale.io) and tested against KafScale v1.6.0 and Apache Kafka 3.9.

## 4.1 What KafScale supports

Measured by `opentsx-kafscale-conformance`:

| Feature | KafScale v1.6.0 | How OpenTSx copes |
|---------|-----------------|-------------------|
| produce/fetch, zstd, headers, CreateTime | yes | used |
| timestamp seek (`offsetsForTimes`) | no | episode index (episodeId → partition/offset) |
| idempotent producer | no | `enable.idempotence=false`, `max.in.flight=1`, dedup by `episodeId` |
| log compaction | no | append-only topics, "latest record wins" readers |
| consumer-group rejoin, standalone commits | unreliable | no consumer groups: manual assignment, positions kept by OpenTSx |
| Schema Registry | none | self-describing single-object encoding |

## 4.2 Topics

`TopicLayout("wind")` creates `tsx.wind.{episodes,buckets,series,observations,series-stats}.v2`, with zstd
compression and the delete cleanup policy.

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
* `IcebergEpisodeIndex` keeps the index durably in Iceberg ([chapter 5](05-stores.md)).

## 4.5 Storage cost (measured)

Measured with 10-minute episodes at 1 Hz, zstd, batches of about 512 KB:

| Data | bytes per value |
|------|-----------------|
| values at sensor resolution, regular | **2.18** |
| full-precision doubles | 6.96 |
| sensor resolution with timestamp jitter (`IRREGULAR_DELTA`) | 4.07 |

Quantize at the source (the wind-farm normalizer does this from the catalog's `resolution`).
