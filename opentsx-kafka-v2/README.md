# opentsx-kafka-v2: episode storage and replay on KafScale

Stores v2 episodes ([`opentsx-model`](../opentsx-model)) in Kafka-protocol topics and replays them.
Designed for [KafScale](https://kafscale.io), so it relies only on what KafScale supports
(see [`opentsx-kafscale-conformance`](../opentsx-kafscale-conformance/README.md)):

| KafScale limitation | How this module copes |
|---------------------|-----------------------|
| no timestamp seek (`offsetsForTimes`) | **episode index** (episodeId → partition/offset); replay = index lookup + `seek` |
| no idempotent producer | `enable.idempotence=false`, `max.in.flight.requests.per.connection=1`; duplicates are dropped by `episodeId` |
| no log compaction | all topics are append-only; manifests are resolved by scanning (`SEALED` wins) |
| consumer-group rejoin / standalone commits broken | no consumer groups at all: manual assignment, positions kept by OpenTSx |

## Topics (`TopicLayout`)

`TopicLayout` names the topics of a domain:

| Topic | Method |
|-------|--------|
| `tsx.<domain>.episodes.v2` | `episodes()` |
| `tsx.<domain>.buckets.v2` | `buckets()` |
| `tsx.<domain>.series.v2` | `series()` |
| `tsx.<domain>.observations.v2` | `observations()` |
| `tsx.<domain>.series-stats.v2` | `seriesStats()` |
| `tsx.<domain>.clean.v2` | `clean()` |
| `tsx.<domain>.synthetic.v2` | `synthetic()` |
| `tsx.<domain>.features.v2` | `features()` |
| `tsx.<domain>.dags.v1` | `dags()` |
| `tsx.<domain>.dag-status.v1` | `dagStatus()` |
| `tsx.<domain>.smart.<name>.vectors.v1` | `smartVectors(<name>)` |
| `twin.<id>.<run>.events` | `twinEvents(<id>, <run>)` (static) |

`provision` creates the first five, the topics of `all()`: buckets and series with one partition, the other three with
the number of partitions it is given. `provisionDagTopics` creates `dags` and `dag-status`, `provisionTopic` any other
topic of the domain, and `provisionTwinEvents` the event topic of a twin run, each with one partition. Every topic is
created with zstd and the delete policy.

Episode records: key = `seriesId`, record timestamp = `tStart`, headers `tsx-schema`, `tsx-bucket`,
value = Avro single-object encoding.

`isTwinEvents` checks the name of a twin event topic. `twinEvents` and `provisionTwinEvents` throw
`IllegalArgumentException` for any other name.

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
With a speed above 0 the replay sleeps until an episode is due: 10 s of event time between two episodes take 1/6 s at
`withSpeed(60.0)`. An episode that is already due is delivered without sleeping.
The index is derived state: `EpisodeIndexer.rebuild(consumer, layout, index, timeout)` recreates it from the log,
and `BucketCatalog.load(...)` lists all buckets with their current manifest. Both read the whole topic with
`LogReader.readAll` and throw `IllegalStateException` when it does not exist, for example
`tsx.wind.buckets.v2: no such topic; check the domain, or create the topic first`. The rebuild returns
`Result(indexed, skipped, invalid)`:

* A record that does not decode as an episode is counted in `skipped`. The first one per partition is logged at
  WARN with the message of the decoder, logger `org.opentsx.kafka.EpisodeIndexer`; later ones on that partition are
  only counted:

  ```text
  undecodable record skipped and counted in skipped; later ones of this partition are only counted topic=<topic> partition=<partition> offset=<offset> cause=<reason>
  ```

* An episode that decodes but fails `Episodes.validate`, for example a count that differs from the number of values,
  is counted in `invalid` and not indexed. The first one per partition is logged at WARN, logger
  `org.opentsx.kafka.EpisodeIndexer`; later ones on that partition are only counted:

  ```text
  invalid episode skipped and counted in invalid; later ones of this partition are only counted topic=<topic> partition=<partition> offset=<offset> cause=<reason>
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

## Read a whole topic

`LogReader.readAll(consumer, topic, sink, timeout)` hands every record from the log start to the end offsets at the
time of the call to the sink, with manual assignment and seek, and unassigns the consumer afterwards. It waits out
refused fetches (see below) within `timeout`. The timeout covers the whole call: the partition lookup, the log start
and end offsets and the read each get what is left of it. It throws `IllegalStateException` when the topic does not
exist or has no partitions, with the message `<topic>: no such topic; check the domain, or create the topic first`,
and when the time runs out: with `timed out reading <topic>` when less than a millisecond is left before the read,
and with `timed out reading [<topic>-<partition>, ...]` during it. A partition lookup or offset request that gets no
answer within the time left throws the client's `org.apache.kafka.common.errors.TimeoutException`.

## Follow a topic

`LogTail` (records) and `EpisodeLogTail` (decoded episodes) read the whole log on the first `poll` and the records
appended since then on every later one. A topic that does not exist delivers nothing. When retention deleted records
after the position of the previous poll, the tail reads on from the log start, counts them in `lostToRetention()` and
logs one WARN line per partition through SLF4J, logger `org.opentsx.kafka.LogTail`:

```text
retention deleted records before they were read; reading on from the log start topic=<topic> partition=<partition> position=<previous> logStart=<start> lost=<n>
```

A log start above 0 on the first poll is not counted.

`EpisodeLogTail` counts a record that does not decode as an episode in `undecodable()` and skips it. The first one of
each partition is logged at WARN, logger `org.opentsx.kafka.EpisodeLogTail`; later ones on that partition are only
counted:

```text
undecodable record skipped and counted in undecodable; later ones of this partition are only counted topic=<topic> partition=<partition> offset=<offset> cause=<reason>
```

## Wait out refused reads

While KafScale rates its object store as degraded, it refuses every fetch for about a minute, and the consumer throws
`IllegalStateException: Unexpected error code 7 while fetching ...`. `Patience.poll(consumer, timeout)` returns no
records instead, puts every partition back to its position before the poll and pauses. `Patience.standard()` pauses
5 s and gives up after 10 minutes of refusals in a row with `IllegalStateException`
`the broker has refused reads for more than PT10M`, the limit written as an ISO-8601 duration. `LogReader`, `LogTail`,
`EpisodeLogTail`, the statistics processor and the retrieval feed poll through it.

The first refusal of a row is logged at WARN and the next answer at INFO, logger `org.opentsx.kafka.Patience`:

```text
the broker refuses reads; waiting pause=PT5S limit=PT10M cause=Unexpected error code 7 while fetching at offset <offset> from topic-partition <topic>-<partition>
the broker answers reads again refusedFor=<duration>
```

`<duration>` is ISO-8601 with millisecond precision, for example `PT1M4.512S`. `bin/opentsx` and the image write
these SLF4J lines with slf4j-simple to stderr.

## Tests

* Unit tests with `MockProducer` and `MockConsumer`, always run. `PatienceTest` and `ReplayServiceTest` move a test
  clock instead of sleeping and assert the pauses and sleeps that were asked for, and the WARN and INFO lines.
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

Values at sensor resolution compress well; full-precision derived values barely compress.
