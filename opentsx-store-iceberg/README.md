# Iceberg store

Tables in namespace `tsx` (configurable), Parquet + zstd, Iceberg format v2:

| Table | Content | Partitioning |
|-------|---------|--------------|
| `episodes` | one row per v2 episode (series, time axis, `values list<double>`, summary columns `s_*`, segmentation `seg_*`, labels, provenance) | `day(t_start)`, `bucket(32, series_id)`, sorted by `series_id, t_start` |
| `series` | series_id, metric, tags, unit | — |
| `episode_index` | episode_id → KafScale topic/partition/offset (durable index for replay) | `day(t_start)` |

* `IcebergSeriesStore.open(props, "tsx")` takes standard Iceberg catalog properties (`type=rest`, `uri`, `warehouse`, …;
  tests use the JDBC catalog on SQLite and a local warehouse).
* Writes are idempotent (existing episode ids are skipped) — safe for at-least-once input.
* `EpisodeLogSync.sync(consumer, layout, store, positions, …)` copies the KafScale episodes topic into the store and
  returns the next positions; `IcebergEpisodeIndex` plugs into `BucketWriter` / `ReplayService` as the durable index.
* Python: `opentsx.store.iceberg.read_episodes / read_frame` (PyIceberg) read the same tables; CI proves Java and
  Python produce byte-identical episodes from them.

Spark SQL example:

```sql
SELECT series_id, t_start, s_mean, sqrt(s_m2 / (s_valid_count - 1)) AS s_std
FROM tsx.episodes
WHERE metric = 'wind.turbine.power_active' AND t_start >= TIMESTAMP '2026-10-01 00:00:00 UTC';
```
