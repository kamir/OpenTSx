# 7. Streaming statistics and data quality

Module: `opentsx-stats` ([README](../../opentsx-stats/README.md)).

## 7.1 What is computed

Per series and window, a `SeriesStats` record contains:

* `validCount`, `nanCount`, `min`, `max`, `mean`, `m2` (Welford; merges exactly),
* a **quantile sketch** (DDSketch, 1 % relative error, mergeable),
* `gapCount` and `lastSeen`.

Windows are epoch-aligned tumbling windows (`PT10M`, `PT1H`, `P1D`, …) plus `GLOBAL` (since start).

* **Window closing:** each series has its own event time. An episode advances it to `tEnd`, so a 10-minute episode
  closes its 10-minute window immediately.
* **Duplicates:** duplicate episodes are ignored (by `episodeId`).
* **Late values:** they are counted and enter only `GLOBAL`.

## 7.2 Running the processor

```bash
bin/opentsx stats --kafscale localhost:9092 --domain wind --checkpoint stats.ckpt \
    [--windows PT10M,PT1H] [--lateness PT0S] [--alpha 0.01] [--observations true] [--once true]
```

* Input positions and the full engine state are written to the checkpoint file after the outputs are acknowledged.
  After a crash the processor resumes from the checkpoint and is at-least-once.
* `--once true` processes the backlog, closes all windows and exits.
* Because KafScale has no compaction, `SeriesStatsLog.readLatest(...)` reads the topic and keeps the latest record
  per key.

The same statistics run as a Flink job, `StatsJob` in `opentsx-flink-core`. It reads the same topics and writes the
same records:

```bash
bin/opentsx stats-flink --kafscale localhost:9092 --domain wind \
    [--windows PT10M,PT1H] [--lateness PT0S] [--alpha 0.01] [--observations true] \
    [--global-every PT30S] [--checkpoint-every PT30S] [--once true]
```

* `bin/opentsx stats-flink` runs the job in a local mini cluster. On a Flink cluster:
  `flink run -c org.opentsx.flink.stats.StatsJob opentsx-flink-core/target/opentsx-flink-core-<version>.jar ...`.
* One `StatsEngine` per series lives in Flink keyed state. Input positions are in Flink checkpoints, not in a file;
  `--checkpoint` does not apply. The source commits no offsets, because KafScale rejects commits without group
  membership. The output is at least once, as with the processor.
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

## 7.3 Data-quality checks

`DataQuality.evaluate(stats, expectation)` evaluates the checks on statistics only, without access to the raw values:

| Check | Fails when |
|-------|-----------|
| COMPLETENESS | valid values / expected samples < 0.95 |
| PLAUSIBLE_RANGE | share outside [min, max] > 0.1 % (estimated from the sketch) |
| FLATLINE | standard deviation ≈ 0 over ≥ 60 values (stuck sensor) |
| GAPS | timestamp gaps > 0 |
| FRESHNESS | last value older than the maximum age |

For wind-farm data, `WindparkExpectations.of(catalog)` derives the expectations from the catalog: range, sampling,
and a flatline check for meteorological channels. On simulator data the checks find the injected stuck anemometer and
outage in exactly the affected windows.

## 7.4 In Python

```python
from opentsx import model as m
from opentsx.stats import QuantileSketch, stats_to_frame
records = [m.decode(payload)[1] for payload in payloads_from_series_stats_topic]
df = stats_to_frame(records)            # one row per window: counts, min/max/mean/std, gaps, q05..q95
```

The sketch format is identical in Java and Python; a shared test vector checks this.

## 7.5 Check the Java reference vectors

The shared `stats-window.v1.json` file records five synthetic cases from
`StatsEngine`, `StatsAccumulator`, and `QuantileSketch`. Both `PT10M` and
`PT1H` windows use alpha `0.01`. Outputs include complete statistics,
sketch bytes as hex, quantiles, NaN values, and empty windows. An empty
engine stream emits no records; an explicit empty accumulator window
contains NaN moments and an empty sketch.

Quantile representatives use `StrictMath.pow` for repeatable reconstruction
from a sketch bin across Java platforms. Sketch bytes are unchanged.

`dq-result.v1.json` records seven cases from `DataQuality.evaluate`,
covering every check and status. Freshness uses a fixed clock; messages
use `Locale.ROOT`. Non-finite values in both files are strings.

`StatsWindowVectorsTest` and `DqResultVectorsTest` load these repository
files and require exact equality. You need JDK 17 or 21, Maven, and Python
to regenerate them. From the repository root, run:

```bash
python tools/java_operator_vectors.py
```

The command writes all five Java reference files under
`docs/datamodel-2026/testvectors/`. Verified with JDK 21 and Python 3.12
on 2026-11-16. See the [module guide](../../opentsx-stats/README.md).

## 7.6 Run statistics and clean chains on Wayang

You need JDK 17 or 21 and Maven. The `opentsx-wayang` module runs the existing
clean chain and statistics algorithms on Apache Wayang's Java platform.
Register `TsxJavaPlugin` and use `TsxStatsWindow` with `StatsEngine.Config`.
The constructor preserves open windows across increments and accepts episodes
and observations. The `bounded` factory flushes a completed engine input.
The `window` factory requests epoch-aligned windows for an explicit series,
including empty input. It emits NaN moments and an empty sketch for an empty
window; an empty engine stream emits no records.

For clean episode DAGs, `DagToWayang.compile` validates structure and processor
parameters before building a plan. Select a sink from `series()` and pass
`plan(bootstrap, sinkName)` to `TsxIncrement.loop`. Its checkpoint contains
input positions and operator state. Status records include `track: A`, counts,
watermark, waiting series, and skipped blocks. Heartbeats run every 30 seconds
during processing and idle intervals.

Run the module checks from the repository root:

```bash
mvn -B -ntp -pl opentsx-wayang -am verify
```

`TsxStatsVectorsTest` reads all five shared statistics cases directly and
compares every field and sketch byte exactly. `TsxCleanVectorsTest` checks all
15 clean cases. Broker parity and status tests need a disposable KafScale
instance and `KAFSCALE_BOOTSTRAP`; follow the
[module instructions](../../opentsx-wayang/README.md#test).
