# opentsx-stats — streaming statistics and data quality

This module computes running and windowed statistics over the v2 episode and observation streams. It writes them as
`SeriesStats` to `tsx.<domain>.series-stats.v2` and evaluates data-quality checks on them. The engine has no runtime
dependency. It runs in the bundled KafScale processor and can be wrapped in Flink or Kafka Streams.

## What is computed

Statistics are kept per series (`seriesId`) and per window:

| field | meaning |
|-------|---------|
| `validCount`, `nanCount` | finite values / NaN values (missing or invalid) |
| `min`, `max`, `mean`, `m2` | Welford moments; variance = `m2 / (validCount − 1)`; exact merge (Chan et al.) |
| `quantileSketch` | DDSketch, 1 % relative error, mergeable; format below |
| `gapCount` | steps between timestamps > 1.5 × expected interval |
| `lastSeen` | latest timestamp |

* **Windows:** epoch-aligned tumbling windows (`PT10M`, `PT1H`, `P1D`, …), plus `GLOBAL` ("since start").
* **Closing a window:** each series has its own event time. A window closes once the series reaches
  `windowEnd + allowedLateness`. An episode moves the event time to its `tEnd`, so a 10-minute episode closes its
  10-minute window immediately. Late values count only towards `GLOBAL` and are reported in the metrics.
* **Gaps vs. NaN:** missing *timestamps* count as gaps. Missing *values* inside regular episodes are NaN. The expected
  interval comes from the configuration per series, or is learned from REGULAR episodes.
* **Duplicates:** KafScale has no idempotent producer, so episodes are deduplicated by `episodeId`. Memory for this is
  bounded and covered by the checkpoint.

```java
StatsEngine engine = new StatsEngine(StatsEngine.Config.defaults()          // PT10M + PT1H, alpha 0.01
        .withExpectedInterval(id -> OptionalLong.of(1_000_000)));          // 1 Hz
List<SeriesStats> closed = engine.onEpisode(episode);                       // windows closed by this episode
List<SeriesStats> global = engine.emitGlobal();                             // GLOBAL of changed series
```

## Processor on KafScale

```bash
mvn -q -pl opentsx-stats exec:java -Dexec.mainClass=org.opentsx.stats.StatsProcessorApp -Dexec.args="\
  --kafscale localhost:9092 --domain wind --checkpoint stats.ckpt --windows PT10M,PT1H [--once true]"
```

The processor works within KafScale's limits: manual partition assignment, no consumer group and no offset commits.
* **Checkpoint:** input positions and the complete engine state (open windows, sketches, dedup ids) go into a local
  checkpoint file. The file is written atomically, after the producer has acknowledged all outputs.
* **Restart:** the processor resumes at the checkpoint and is at-least-once for outputs.
* **Reading the output:** `SeriesStatsLog.readLatest` reads the topic and keeps the latest record per
  `(seriesId, windowType, windowStart)`, because the topic has no compaction.
* **`--once true`:** processes the backlog, closes all windows and exits. This is for experiments.

## Data quality

`DataQuality.evaluate(stats, expectation)` evaluates checks on the statistics alone, with no raw values:

| check | value | fails when |
|-------|-------|------------|
| COMPLETENESS | valid / expected samples | < 0.95 |
| PLAUSIBLE_RANGE | share outside [min, max] (sketch) | > 0.1 % |
| FLATLINE | standard deviation | ≤ 1e-9 over ≥ 60 values (stuck sensor) |
| GAPS | gap count | > 0 |
| FRESHNESS | age of `lastSeen` | > max age |

`WindparkDataQualityTest` checks this against the wind-farm simulator's ground truth. A stuck anemometer and a
power outage are found in exactly the affected 10-minute windows, with no false alarms on range or gaps.
[`opentsx-openmetadata`](../opentsx-openmetadata) publishes the results.

## Quantile sketch format (v1)

```
0x01 | alpha (float64 LE) | varint zeroCount | positive store | negative store
store = varint binCount, then per bin (ascending index): zig-zag varint index delta, varint count
```

The encoding is canonical. Java (`QuantileSketch`) and Python (`opentsx.stats.QuantileSketch`) produce identical bytes,
checked by a shared test vector. `opentsx.stats.stats_to_frame(records)` turns decoded records into a pandas frame
with quantile columns.

## Tests

* Sketch: accuracy, exact merge, canonical bytes, collapsing, and the test vector.
* Engine: windows equal episode summaries, gaps, duplicates, late values, observations, and checkpoint restore with
  output identical to an uninterrupted run.
* Data quality against simulator ground truth.
* Processor:
  * With `MockConsumer`: checkpoint and resume, every value counted exactly once.
  * Against KafScale v1.6.0, when `KAFSCALE_BOOTSTRAP` is set; CI runs this.
