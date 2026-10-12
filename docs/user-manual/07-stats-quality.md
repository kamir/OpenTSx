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
