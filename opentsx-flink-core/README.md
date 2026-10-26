# OpenTSx Flink Integration

Flink 1.20.5 jobs for OpenTSx:

* `stats.StatsJob` runs the v2 statistics engine of [`opentsx-stats`](../opentsx-stats/README.md) on the episode
  and observation topics and writes `SeriesStats`, the same records as the KafScale processor.
* `pfd.DynamicTopologyJob` runs processing-flow descriptors (TASK-005) on the legacy `TimeSeriesObject` API.

## StatsJob

```bash
bin/opentsx stats-flink --kafscale localhost:9092 --domain wind \
    [--windows PT10M,PT1H] [--lateness PT0S] [--alpha 0.01] [--observations true] \
    [--global-every PT30S] [--checkpoint-every PT30S] [--once true]
```

`bin/opentsx stats-flink` runs the job in a local mini cluster. On a Flink cluster, `StatsJob` is the main class of
the shaded jar:

```bash
mvn -q -pl opentsx-flink-core -am package -DskipTests
flink run opentsx-flink-core/target/opentsx-flink-core-<version>.jar --kafscale <bootstrap> --domain wind
```

| Step | What runs |
|------|-----------|
| Read | `KafkaSource` on `tsx.<domain>.episodes.v2` (and `observations.v2` with `--observations true`), from the earliest offset. `--once true` stops at the end offsets seen at start. |
| Key | the record key, which is the series id (`BucketWriter` writes it); a record without key is decoded to find it |
| Compute | `StatsFunction`: one `StatsEngine` per series in keyed state, the same `onEpisode` and `onObservation` calls as the processor |
| GLOBAL | a processing-time timer per series every `--global-every`; `PT0S` emits GLOBAL only at the end of a bounded run |
| End of a bounded run | the final watermark fires a timer that calls `flush()`: all open windows close |
| Write | `KafkaSink` to `tsx.<domain>.series-stats.v2`, key series id, timestamp window end, header `tsx-schema` |

Built for what KafScale supports (see the [conformance results](../opentsx-kafscale-conformance/README.md)):

* The source commits no offsets (`commit.offsets.on.checkpoint=false`). KafScale rejects commits without group
  membership; the positions live in Flink checkpoints, every `--checkpoint-every`.
* The sink writes at least once with the producer settings of `KafScaleClients` (`enable.idempotence=false`,
  `acks=all`, one request in flight). Readers keep the latest record per (seriesId, windowType, windowStart), as
  `SeriesStatsLog` does.
* A failed task restarts from the last checkpoint, up to 20 times with 15 s between attempts. KafScale refuses
  reads for about a minute at a time.
* The job emits no watermarks. The engine keeps event time per series and closes windows itself.

Operation:

* Use the heap state backend. `StatsEngineSerializer` writes the engine at checkpoints only; with RocksDB it would
  write it on every record.
* Keep the windows when restoring from a checkpoint or savepoint. Other windows are incompatible with the stored
  state; the restore fails.
* Stop a running job without `--drain`. Draining sends the final watermark, which closes all open windows.

## PFD runtime

`pfd.DynamicTopologyJob` requires an explicit input topic and the existing `bootstrap.servers` configuration key.
It defaults to five-minute event-time windows with ten seconds of allowed out-of-order arrival.
Append `--window-size <milliseconds>` after the descriptor filename or inline JSON to choose a positive integer
window size. The default is `300000` milliseconds.
Set `--out-of-orderness <milliseconds>` to a non-negative integer for the watermark tolerance; its default is
`10000`. Zero removes the configured delay. Both options can appear once, in either order, after the descriptor.
Invalid values, duplicate options and unknown arguments fail before the Flink graph is built.
`NORMALIZE` centers values and updates the cached mean. Unsupported steps and output types fail before execution.
The Kafka source includes `flink-connector-base` at the same pinned Flink version.
Each `LOG` record is JSON with `label`, `timestamps` and `values`, including windows with fewer than five points.
The `pfd-acceptance` Maven profile verifies a recorder-exported flow through a real Kafka-compatible broker and
Flink in the KafScale CI job. See the [PFD contract](../docs/devguide/validation/PFD_VALIDATION_PLAN.md) for
prerequisites, commands and result artifacts, and the [classic analytics manual](../docs/user-manual/11-classic-analytics.md).

Components of the PFD runtime:

* `functions.TimeSeriesAggregateFunction` collects the observations of a window into a `TimeSeriesObject`.
  The aggregation tests exercise nullable getters with local Observation fixtures. Avro timestamp and value
  fields remain required; the generated setters cannot construct null primitive fields.
* `serdes.ObservationSchema` decodes 2.x Avro `Observation` records from Kafka.
* `serdes.TimeSeriesObjectSerializer` and `TimeSeriesObjectTypeInfo` serialize `TimeSeriesObject` state
  (about 16 bytes per data point).

## Tests

```bash
mvn -pl opentsx-flink-core -am test
KAFSCALE_BOOTSTRAP=localhost:9092 mvn -pl opentsx-flink-core -am test    # also StatsJobKafScaleTest
```

* `StatsJobTest`: the job on a mini cluster gives the same `SeriesStats` as one `StatsEngine` over the same
  episodes; a restore from a checkpoint after half the input counts every value once; GLOBAL timers.
* `StatsEngineSerializerTest`: round trip, independent copies, snapshot and compatibility rules.
* `StatsJobKafScaleTest`: episodes written to KafScale come back as the same `SeriesStats` on the series-stats
  topic. It runs in the KafScale CI job.

## License

Apache License 2.0.

## Related Documentation

- [OpenTSx user manual](../docs/user-manual/README.md), chapter 7 (statistics) and chapter 11 (classic analytics)
- [TASK-003: Flink integration plan (archived)](../EVOLUTION/archive/TASK-003-flink-integration.md)
- [Apache Flink Documentation](https://flink.apache.org/docs/)
