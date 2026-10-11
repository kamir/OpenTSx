# opentsx-kafka-v2 — episode storage and replay on KafScale

Stores v2 episodes ([`opentsx-model`](../opentsx-model)) in Kafka-protocol topics and replays them.
Designed for [KafScale](https://kafscale.io), so it relies only on what KafScale supports
(see [`opentsx-kafscale-conformance`](../opentsx-kafscale-conformance/README.md)):

| KafScale limitation | How this module copes |
|---------------------|-----------------------|
| no timestamp seek (`offsetsForTimes`) | **episode index** (episodeId → partition/offset); replay = index lookup + `seek` |
| no idempotent producer | `enable.idempotence=false`, `max.in.flight=1`; duplicates are dropped by `episodeId` |
| no log compaction | all topics are append-only; manifests are resolved by scanning (`SEALED` wins) |
| consumer-group rejoin / standalone commits broken | no consumer groups at all: manual assignment, positions kept by OpenTSx |

## Topics (`TopicLayout`)

`tsx.<domain>.{episodes,buckets,series,observations,series-stats}.v2`, zstd, delete policy.
Episode records: key = `seriesId`, record timestamp = `tStart`, headers `tsx-schema`, `tsx-bucket`,
value = Avro single-object encoding.

## Write a bucket

```java
TopicLayout layout = new TopicLayout("wind");
try (Admin admin = KafScaleClients.admin(bootstrap)) { layout.provision(admin, 6, (short) 1); }

EpisodeIndex index = new FileEpisodeIndex(Path.of("wind-index.tsv"));
try (KafkaProducer<String, byte[]> producer = KafScaleClients.producer(bootstrap);
     BucketWriter w = BucketWriter.open(producer, layout, index, "park-ber-01/2026-10-10", Map.of("scenario", "s1"))) {
    episodes.forEach(w::append);          // async; index entries come from the acknowledgements
}                                          // close() = seal(): SEALED manifest with count, time range, checksum
```

A bucket whose episodes did not all get acknowledged is **not sealed** (`BucketWriteException`).

## Replay

```java
try (KafkaConsumer<byte[], byte[]> consumer = KafScaleClients.consumer(bootstrap)) {
    ReplayService replay = new ReplayService(consumer, layout, index);

    ReplayResult r = replay.replay(ReplayRequest.bucket(bucketId).withSpeed(60.0), sink); // 1 h of data per minute
    r.verified();   // SEALED, and delivered count + checksum match the manifest

    replay.replay(ReplayRequest.range(Set.of(seriesId), fromMicros, toMicros), sink);     // episodes overlapping a window
}
```

Episodes arrive in event-time order (`tStart`, `episodeId`); memory is bounded by `maxEpisodesInMemory`.
The index is derived state: `EpisodeIndexer.rebuild(consumer, layout, index, timeout)` recreates it from the log,
and `BucketCatalog.load(...)` lists all buckets with their current manifest.

Legacy TSBucket files (`*.tsb.vec.seq`) are converted with `org.opentsx.bridge.LegacyBucketImport` and then written
with a `BucketWriter`.

## Tests

* Unit tests with `MockProducer`, always run.
* `EpisodeLogIntegrationTest` (write → verified replay, chunked replay, range replay, index rebuild,
  duplicates, unsealed buckets, pacing) runs when `KAFSCALE_BOOTSTRAP` is set — green on KafScale v1.6.0 and
  Apache Kafka 3.9.1; CI runs it against KafScale built from source.

## Storage cost (measured, `StorageCostTest`)

Episodes of 10 min at 1 Hz, Avro single-object records batched to ~512 KB and compressed with zstd (level 3):

| Data | raw | zstd |
|------|-----|------|
| sensor-resolution values (0.01 m/s, 0.1 kW, 0.01 rpm), regular | 8.40 B/value | **2.18 B/value** |
| full-precision doubles, regular | 8.36 B/value | 6.96 B/value |
| sensor resolution with ≤ 2 ms timestamp jitter, `IRREGULAR_DELTA` | 10.32 B/value | 4.07 B/value |

Values at sensor resolution compress well; full-precision derived values barely compress. A quantized integer
value encoding (scale + zig-zag deltas) would bring regular sensor data well below 1 B/value — a candidate for a
future schema version if storage cost demands it.
