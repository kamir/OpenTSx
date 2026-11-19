# opentsx-kafka-v2: episode storage and replay on KafScale

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

`TopicLayout.twinEvents(<id>, <run>)` (static) names the event topic of a twin run, `twin.<id>.<run>.events`, and
`isTwinEvents` checks a name. `provisionTwinEvents` creates it with one partition, zstd and the delete policy.
`twinEvents` and `provisionTwinEvents` throw `IllegalArgumentException` for any other name.

`provision`, `provisionDagTopics`, `provisionTopic` and `provisionTwinEvents` create the missing topics. They throw
`IllegalStateException` when the broker does not answer within 30 s, when it refuses a creation, or when the thread is
interrupted. After an interrupt the interrupt flag of the thread stays set. A topic that another client created
meanwhile counts as created.

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

A bucket is **not sealed** (`BucketWriteException`) when an episode was not acknowledged, when its index entry could
not be added, when the episodes are not acknowledged within 5 minutes, or when `seal()` is interrupted before every
episode is acknowledged. When the send or the index entry fails, the future that `append` returned for that episode
completes exceptionally. `BucketWriter.open` and `seal()` wait up to 2 minutes for the acknowledgement of the OPEN and
the SEALED manifest; when it fails, does not arrive or the thread is interrupted, they throw `BucketWriteException`
with the message `cannot write <state> manifest of bucket <bucketId>`. After an interrupt the interrupt flag of the
thread stays set.

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
and `BucketCatalog.load(...)` lists all buckets with their current manifest. The rebuild returns
`Result(indexed, skipped, invalid)`:

* A record that does not decode as an episode is counted in `skipped`. The first one per partition is printed on
  stderr with the message of the decoder; later ones on that partition are only counted:

  ```text
  <topic>-<partition> offset <offset>: undecodable record (<reason>); skipped and counted in skipped, later undecodable records of this partition are only counted
  ```

* An episode that decodes but fails `Episodes.validate`, for example a count that differs from the number of values,
  is counted in `invalid` and not indexed. The first one per partition is printed on stderr; later ones on that
  partition are only counted:

  ```text
  <topic>-<partition> offset <offset>: invalid episode (<reason>); skipped and counted in invalid, later invalid episodes of this partition are only counted
  ```

* A failure of the index stops the rebuild.

`FileEpisodeIndex` reads its file when it is created. A line that does not have 8 tab-separated fields, or whose
times, partition or offset are not whole numbers, fails with `IllegalArgumentException`:

```text
<file>:<line>: malformed index line "<content>"; expected 8 tab-separated fields, see the header line
<file>:<line>: malformed index line "<content>"; tStartMicros, tEndMicros, partition and offset must be whole numbers
```

Move the file away and rebuild the index with `EpisodeIndexer.rebuild`. A file that cannot be read or written fails
with `UncheckedIOException` and the message `cannot open episode index <file>`, `cannot append to episode index <file>`
or `cannot close episode index <file>`.

Legacy TSBucket files (`*.tsb.vec.seq`) are converted with `org.opentsx.bridge.LegacyBucketImport` and then written
with a `BucketWriter`.

## Follow a topic

`LogTail` (records) and `EpisodeLogTail` (decoded episodes) read the whole log on the first `poll` and the records
appended since then on every later one. When retention deleted records after the position of the previous poll, the
tail reads on from the log start, counts them in `lostToRetention()` and prints one line per partition on stderr:

```text
<topic>-<partition>: retention deleted <n> records before they were read (position <previous>, log start <start>); reading on from the log start
```

A log start above 0 on the first poll is not counted.

## Tests

* Unit tests with `MockProducer` and `MockConsumer`, always run.
* `EpisodeLogIntegrationTest` (write → verified replay, chunked replay, range replay, index rebuild,
  duplicates, unsealed buckets, pacing) runs when `KAFSCALE_BOOTSTRAP` is set. It is green on KafScale v1.6.0 and
  Apache Kafka 3.9.1; CI runs it against KafScale built from source.

## Storage cost (measured, `StorageCostTest`)

Episodes of 10 min at 1 Hz, Avro single-object records batched to ~512 KB and compressed with zstd (level 3).
`StorageCostTest` asserts each figure, rounded to two decimals:

| Data | raw | zstd |
|------|-----|------|
| sensor-resolution values (0.01 m/s, 0.1 kW, 0.01 rpm), regular | 8.40 B/value | **2.18 B/value** |
| full-precision doubles, regular | 8.36 B/value | 6.96 B/value |
| sensor resolution with ≤ 2 ms timestamp jitter, `IRREGULAR_DELTA` | 10.32 B/value | 4.07 B/value |

Values at sensor resolution compress well; full-precision derived values barely compress. A quantized integer
value encoding (scale + zig-zag deltas) would bring regular sensor data well below 1 B/value. It is a candidate for a
future schema version if storage cost demands it.
