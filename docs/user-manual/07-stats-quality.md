# 7. Streaming statistics and data quality

Module: `opentsx-stats` ([README](../../opentsx-stats/README.md)).

<a id="71-what-is-computed"></a>

## 7.1 Read a statistics record

Per series and window, a `SeriesStats` record contains:

* `validCount`, `nanCount`, `min`, `max`, `mean`, `m2` (Welford; merges exactly),
* a **quantile sketch** (DDSketch, 1 % relative error, mergeable),
* `gapCount` and `lastSeen`.

Windows are epoch-aligned tumbling windows (`PT10M`, `PT1H`, `P1D`, …) plus `GLOBAL` (since start).

* **Window closing:** each series has its own event time. An episode advances it to `tEnd`, so a 10-minute episode
  closes its 10-minute window immediately.
* **Duplicates:** duplicate episodes are ignored (by `episodeId`).
* **Late values:** they are counted and enter only `GLOBAL`.

## 7.2 Run the processor

```bash
bin/opentsx stats --kafscale localhost:9092 --domain wind --checkpoint stats.ckpt \
    [--windows PT10M,PT1H] [--lateness PT0S] [--alpha 0.01] [--observations true] [--once true]
```

* Input positions and the full engine state are written to the checkpoint file after the outputs are acknowledged.
  After a crash the processor resumes from the checkpoint and is at-least-once.
* `--once true` processes the backlog, closes all windows and exits.
* Because KafScale has no compaction, `SeriesStatsLog.readLatest(...)` reads the whole topic. It keeps one record per
  series, window type and window start: the one with the most values, the later one on a tie.

The same statistics run as a Flink job, `StatsJob` in `opentsx-flink-core`. It reads the same topics and writes the
same records:

```bash
bin/opentsx stats-flink --kafscale localhost:9092 --domain wind \
    [--windows PT10M,PT1H] [--lateness PT0S] [--alpha 0.01] [--observations true] \
    [--global-every PT30S] [--checkpoint-every PT30S] [--once true]
```

* `bin/opentsx stats-flink` runs the job in a local mini cluster. On a Flink cluster:
  `flink run -c org.opentsx.flink.stats.StatsJob opentsx-flink-core/target/opentsx-flink-core-<version>.jar ...`.
* One `StatsEngine` per series lives in Flink keyed state. Input positions are in Flink checkpoints, not in a file.
  The source commits no offsets, because KafScale rejects commits without group membership. The output is at least
  once, as with the processor.
* The job rejects `--checkpoint`. It prints this message and the usage line and exits with code 2:

  ```text
  --checkpoint is an option of StatsProcessorApp; the Flink job keeps its positions and state in Flink checkpoints (--checkpoint-every)
  ```

  An option the job does not know ends the same way, with `unknown option --<name>`.
* GLOBAL is emitted every `--global-every` (`PT0S`: only at the end of a bounded run). `--once true` reads up to the
  end offsets seen at start, closes all windows and ends.
* Stop a running job without `--drain`. Draining sends the final watermark, which closes all open windows.
* Use the heap state backend: with RocksDB the engine state is written on every record.

In code (any runtime):

```java
StatsEngine engine = new StatsEngine(StatsEngine.Config.defaults().withExpectedInterval(id -> OptionalLong.of(1_000_000)));
List<SeriesStats> closed = engine.onEpisode(episode);
List<SeriesStats> global = engine.emitGlobal();
```

<a id="73-data-quality-checks"></a>

## 7.3 Check data quality on statistics

`DataQuality.evaluate(stats, expectation)` evaluates the checks on statistics only, without access to the raw values:

| Check | Fails when |
|-------|-----------|
| COMPLETENESS | valid values / expected samples < 0.95 |
| PLAUSIBLE_RANGE | share outside [min, max] > 0.1 % (estimated from the sketch) |
| FLATLINE | standard deviation ≈ 0 over ≥ 60 values (stuck sensor) |
| GAPS | timestamp gaps > 0 |
| FRESHNESS | last value older than the maximum age |

* FRESHNESS runs only when the expectation has a maximum age and you pass the current time:
  `DataQuality.evaluate(stats, expectation.withMaxAge(<maxAge>), OptionalLong.of(<nowMicros>))`. `<maxAge>` is a
  `Duration`, `<nowMicros>` the current time in microseconds since the epoch.
* A record without a sketch (the processor ran with `--alpha 0`) gives PLAUSIBLE_RANGE from min and max only: the
  share is 1 when min or max lies outside the range, else 0.
* The defaults and the parameters of each check are in [chapter 17](17-operators.md).

For wind-farm data, `WindparkExpectations.of(catalog)` derives the expectations from the catalog: range, sampling,
and a flatline check for meteorological channels. It sets no maximum age, so `bin/opentsx metadata` publishes no
FRESHNESS results. On simulator data the checks find the injected stuck anemometer and outage in exactly the affected
windows.

`DataQuality` only measures. The operator `dq.plausible-range` (DAG operator `dq.checks` with the check
`PLAUSIBLE_RANGE`) is a different operator with the same check name: it runs in the vector processor and changes the
series. A value outside the range becomes a gap (`mode: gap`) or the bound (`mode: clip`). See
[chapter 17](17-operators.md).

## 7.4 Read the statistics in Python

```python
from opentsx import model as m
from opentsx.stats import QuantileSketch, stats_to_frame
records = [m.decode(payload)[1] for payload in payloads_from_series_stats_topic]
df = stats_to_frame(records)            # one row per window: counts, min/max/mean/std, gaps, q05..q95
```

The sketch format is identical in Java and Python; a shared test vector checks this.
