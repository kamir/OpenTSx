# opentsx-stats: streaming statistics and data quality

This module computes running and windowed statistics over the v2 episode and observation streams. It writes them as
`SeriesStats` to `tsx.<domain>.series-stats.v2` and evaluates data-quality checks on them. The engine has no runtime
dependency. It runs in the bundled KafScale processor and in the Flink job `StatsJob` of
[`opentsx-flink-core`](../opentsx-flink-core/README.md).

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

`StatsEngine.Config` refuses a setting the engine cannot use and names it: an alpha of 1 or more or NaN (0 or less
keeps no sketch), a negative lateness or one that is not a whole number of microseconds, a window length given twice
and a gap factor that is not above 1. For example
`sketchAlpha: "5.0" is not allowed; expected a number above 0 and below 1, or 0 or less for no sketch`.

## Processor on KafScale

```bash
bin/opentsx stats --kafscale localhost:9092 --domain wind --checkpoint stats.ckpt --windows PT10M,PT1H --once true
```

`bin/opentsx stats` loads its keys with `OpenTsxConf.load` of [`opentsx-conf`](../opentsx-conf/README.md). A key comes
from its flag, `--conf <key>=<value>`, `--properties-file`, its environment variable or the defaults file in
`$OPENTSX_CONF_DIR`. `bin/opentsx stats --help` prints each key with its type, default, flags and variables:

```text
  opentsx.domain  string = wind  --domain  OPENTSX_DOMAIN
  opentsx.kafscale.bootstrap  string  --kafscale, --bootstrap  OPENTSX_KAFSCALE_BOOTSTRAP, KAFSCALE_BOOTSTRAP
  opentsx.log.level  enum = info  one of error|warn|info|debug|trace  OPENTSX_LOG_LEVEL
  opentsx.stats.allowedLateness  duration = PT0S  --lateness  OPENTSX_STATS_ALLOWED_LATENESS
  opentsx.stats.checkpoint  path  --checkpoint  OPENTSX_STATS_CHECKPOINT
  opentsx.stats.observations  boolean = false  --observations  OPENTSX_STATS_OBSERVATIONS
  opentsx.stats.once  boolean = false  --once  OPENTSX_STATS_ONCE
  opentsx.stats.sketchAlpha  double = 0.01  --alpha  OPENTSX_STATS_SKETCH_ALPHA
  opentsx.stats.windows  list of duration = PT10M,PT1H  --windows  OPENTSX_STATS_WINDOWS
```

Only `opentsx.kafscale.bootstrap` is required. Without `opentsx.stats.checkpoint` the processor reads the topics from
the log start on every start. `--once` and `--observations` take `true` or `false`; a bare flag means `true`.

* **Start:** the processor logs one INFO line with every value and its source, for example
  `starting stats: opentsx.domain=wind (default), opentsx.kafscale.bootstrap=localhost:9092 (commandLine), ...`.
* **Exit codes:** 0 success, 1 runtime failure, 2 usage or configuration error.
* **Usage and configuration errors:** an unknown option, an option without its value, a malformed value and a setting
  the engine refuses print one line on stderr and exit with 2, before the processor connects:

  ```text
  opentsx: --window: unknown option of stats; did you mean --windows?
  opentsx: --windows: missing value; expected --windows <list>
  opentsx: --windows: item "PT10" is not a duration; expected ISO-8601 such as PT10M or a whole number with the unit ms, s, m, min, h or d such as 10m, at most 999999999 days
  opentsx: yes: unexpected argument; expected opentsx stats
  opentsx: sketchAlpha: "5.0" is not allowed; expected a number above 0 and below 1, or 0 or less for no sketch
  ```

* **Variables of other keys:** the processor reads the `OPENTSX_` variable of every key of the registry, also of keys
  it does not use, and a malformed value exits with 2. Kubernetes sets `OPENTSX_RETRIEVAL_PORT=tcp://<ip>:8080` in
  every pod of a namespace with the Service `opentsx-retrieval`, and the processor refuses it:

  ```text
  opentsx: OPENTSX_RETRIEVAL_PORT: "tcp://10.43.0.10:8080" is not an int; expected a whole number from -2147483648 to 2147483647
  ```

  Set `enableServiceLinks: false` in the pod spec, as [`deploy/k3s/base/stats.yaml`](../deploy/k3s/base/stats.yaml)
  does.
* **Runtime failures:** one line on stderr with the message and its causes, exit code 1, for example
  `opentsx: cannot read checkpoint stats.ckpt; caused by java.io.IOException: checkpoint belongs to domain solar, not wind`.
  With `--conf opentsx.log.level=debug` the processor first logs the stack trace at DEBUG.

The processor works within KafScale's limits: manual partition assignment, no consumer group and no offset commits.
* **Checkpoint:** input positions and the complete engine state (open windows, sketches, dedup ids) go into a local
  checkpoint file. The file is written atomically, after the producer has acknowledged all outputs.
* **Restart:** the processor resumes at the checkpoint and is at-least-once for outputs.
* **Bad records:** a record that does not decode is counted in `undecodable` and skipped. An episode that decodes
  but fails `Episodes.validate` is counted in `invalid` and skipped, for example a count that differs from the number
  of values, a `seriesId` that does not match its key, a REGULAR episode without a positive `intervalMicros`, or an
  IRREGULAR_DELTA episode without `timeDeltas` or with timestamps that do not increase. The first undecodable record
  and the first invalid episode per partition are printed on stderr with topic, partition, offset and reason. A
  failure of the engine stops the processor, and the checkpoint stays before that record.
* **Retention:** records that retention deleted before they were read are counted in `lostToRetention`, with one line
  on stderr. The processor counts them when a run starts below the log start, when a poll skips offsets of a
  partition, and in a `--once` run when a poll brings no record below the end offsets seen at the start and the log
  start of a partition lies above its position.
* **Checkpoint errors:** an unreadable checkpoint stops the processor with `cannot read checkpoint <file>`, a failed
  write with `cannot write checkpoint <file>`, each followed by its cause.
* **Reading the output:** `SeriesStatsLog.readLatest` reads the topic and keeps the latest record per
  `(seriesId, windowType, windowStart)`, because the topic has no compaction.
* **`--once true`:** processes the backlog, closes all windows, prints the `RunResult` with the counts `records`,
  `undecodable`, `invalid`, `lostToRetention` and `statsWritten`, and exits. This is for experiments.

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
checked by a shared test vector. `QuantileSketch.fromBytes` reads exactly one sketch and throws
`IllegalArgumentException` for bytes after it (`sketch bytes: <n> trailing bytes; expected exactly <m>`) and for bytes
that end inside it (`sketch bytes: truncated at <k> of <m>`, `<k>` the number of bytes read when the input ran out).
`opentsx.stats.stats_to_frame(records)` turns decoded records into a pandas frame with quantile columns.

## Tests

* Sketch: accuracy, exact merge, canonical bytes, collapsing, the test vector, and refused trailing and truncated
  bytes.
* Engine: windows equal episode summaries, gaps, duplicates, late values, observations, a window without values,
  refused settings, and checkpoint restore with output identical to an uninterrupted run.
* Data quality against simulator ground truth, and FRESHNESS with a given clock.
* Processor:
  * With `MockConsumer`: checkpoint and resume, every value counted exactly once; an engine failure stops the run
    without a checkpoint; undecodable records are counted, reported once per partition and skipped; invalid
    episodes, among them a REGULAR episode with an `intervalMicros` of 0 or none, are counted, reported once per
    partition and skipped, also after a restart from the checkpoint; records lost to retention before a run and
    during a run are counted and reported; a `--once` run ends when retention deletes past the end offset it saw at
    the start, both when the next record it reads lies at or past that offset and when no record is left, also while
    another partition keeps receiving records; checkpoint errors name the file.
  * `StatsProcessorAppTest`: unknown options, missing values, malformed values and settings the engine refuses exit
    with 2 and one line; `--help` and `--version` print on stdout; a runtime failure prints its cause chain and exits
    with 1; the start line names every value and its source; the pod of `deploy/k3s/base/stats.yaml` loads its
    arguments, with the Service variables Kubernetes adds when `enableServiceLinks` is not `false`.
  * Against KafScale v1.6.0, when `KAFSCALE_BOOTSTRAP` is set; CI runs this.
