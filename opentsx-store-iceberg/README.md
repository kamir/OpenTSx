# Iceberg store

Tables in namespace `tsx` (configurable), Parquet + zstd, Iceberg format v2:

| Table | Content | Partitioning |
|-------|---------|--------------|
| `episodes` | one row per v2 episode (series, time axis, `values list<double>`, summary columns `s_*`, segmentation `seg_*`, labels, provenance) | `day(t_start)`, `bucket(32, series_id)`, sorted by `series_id, t_start` |
| `series` | series_id, metric, tags, unit | none |
| `episode_index` | episode_id → KafScale topic/partition/offset (durable index for replay) | `day(t_start)` |

* `IcebergSeriesStore.open(props, "tsx")` takes standard Iceberg catalog properties (`type=rest`, `uri`, `warehouse`, …;
  the SQLite driver `org.xerial:sqlite-jdbc` is a runtime dependency, and the tests use the JDBC catalog on SQLite and a
  local warehouse).
* `IcebergSeriesStore.openExisting(props, "tsx")` loads existing `episodes` and `series` tables without creating
  namespaces or tables. Missing objects fail. This instance rejects all `write` calls, including empty batches,
  and `episodesTable()` cannot expose a mutable Iceberg table. It refuses writes in this API only and is no storage
  permission: give the catalog credentials read privileges only where nothing may write.
* A store opened from properties (`open(props, ns)`, `openExisting(props, ns)`) owns its catalog and closes it on
  `close()` and when opening fails. A store given a `Catalog` (`new IcebergSeriesStore(catalog, ns)`,
  `openExisting(catalog, ns)`) borrows it, and the caller closes it.
* Writes are idempotent (existing episode ids are skipped), so at-least-once input is safe. A write adds the
  `series` row of every series of the batch that has none, also when all its episodes exist, so a retry after a
  failed `series` append adds the row.
* A write that fails before its commit, or whose commit fails with an Iceberg `CleanableFailure` such as
  `CommitFailedException`, removes the data files it wrote. Any other commit failure, `CommitStateUnknownException`
  among them, keeps them, because the snapshot may exist.
* When a scan or the catalog fails to close, the error names the table or the catalog
  (`cannot read table <catalog>.<ns>.episodes`, `cannot close catalog <catalog>`). A data file that does not exist
  fails with `UncheckedIOException: cannot read table <catalog>.<ns>.<table>`, caused by Iceberg's
  `NotFoundException`, which names the file.
* `EpisodeLogSync.sync(consumer, layout, store, positions, …)` copies the KafScale episodes topic into the store and
  returns the next positions. `sync` waits out a refused fetch within `timeout`. A missing topic fails with
  `<topic>: no such topic; check the domain, or create the topic first`. A record that does not decode is counted in
  `undecodable`. An episode that fails `Episodes.validate` is counted in `invalid` and not written. The first record
  of each kind per partition is logged at WARN with topic, partition, offset and cause. Records that retention deleted
  after a given position or during the run are counted in `lostToRetention` and logged at WARN with topic, partition,
  position, log start and count. A failing store write stops the sync.
* `IcebergEpisodeIndex` plugs into `BucketWriter` / `ReplayService` as the durable index.
* Python: `opentsx.store.iceberg.read_episodes / read_frame` (PyIceberg) read the same tables; CI proves Java and
  Python produce byte-identical episodes from them.

Spark SQL example:

```sql
SELECT series_id, t_start, s_mean, sqrt(s_m2 / (s_valid_count - 1)) AS s_std
FROM tsx.episodes
WHERE metric = 'wind.turbine.power_active' AND t_start >= TIMESTAMP '2026-10-01 00:00:00 UTC';
```
