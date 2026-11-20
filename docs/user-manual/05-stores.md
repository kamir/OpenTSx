# 5. Stores: Iceberg, OpenTSDB, in-memory

Modules: `opentsx-store-spi`, `opentsx-store-iceberg`, `opentsx-store-opentsdb-v2` (see their READMEs).

## 5.1 Read and write through the SPI

```java
public interface SeriesStore extends AutoCloseable {
    StoreCapabilities capabilities();
    WriteResult write(Collection<Episode> episodes);          // idempotent per episodeId
    List<Episode> read(EpisodeQuery query);                    // overlapping the window, ordered (tStart, seriesId, episodeId)
    List<SeriesKey> listSeries(SeriesSelector selector);
    default Optional<Episode> episode(String episodeId);       // stores with EPISODES
}

List<Episode> eps = store.read(EpisodeQuery.of(SeriesSelector.metric("wind.met.wind_speed").tag("turbine", "T07"),
        fromMicros, toMicros).clipped().limit(100));
```

| Capability | Meaning |
|------------|---------|
| `EPISODES` | episodes come back exactly as written (ids, labels, segmentation) |
| `RAW_POINTS` | points are stored; episodes are reconstructed on read |
| `MICROSECOND_PRECISION`, `NAN_VALUES` | precision and NaN are preserved |
| `DOWNSAMPLING`, `SQL`, `TIME_TRAVEL` | server-side aggregation, SQL access, snapshots |

`SeriesStoreContract` is a JUnit test-jar. Run it against your own store implementation.

## 5.2 Choose a store

| Store | Capabilities | Notes |
|-------|--------------|-------|
| `InMemorySeriesStore` | EPISODES, µs, NaN | reference implementation; tests and notebooks |
| `IcebergSeriesStore` | EPISODES, µs, NaN, SQL, TIME_TRAVEL | tables `<ns>.episodes` (partitioned by `day(t_start)`, `bucket(32, series_id)`) and `<ns>.series` |
| `OpenTsdbSeriesStore` | RAW_POINTS, DOWNSAMPLING | OpenTSDB 2.x HTTP API; ms precision; NaN dropped (counted); unit stored as tag `_unit` |

`<ns>` is the Iceberg namespace. `IcebergEpisodeIndex` keeps the replay index in `<ns>.episode_index`, partitioned by
`day(t_start)` ([chapter 4](04-kafscale-storage.md)).

```java
IcebergSeriesStore store = IcebergSeriesStore.open(Map.of("type", "rest", "uri", "http://localhost:8181",
        "warehouse", "s3://lake/"), "tsx");
```

`open` creates the namespace and the `episodes` and `series` tables when they are missing.
`IcebergSeriesStore.openExisting(props, "tsx")` loads the existing tables and creates nothing. A missing table fails
with `NoSuchTableException`. Its `write` throws `UnsupportedOperationException` ("existing-only store does not accept
writes"). `bin/opentsx retrieval-server --iceberg` opens the store this way.

Both pass the properties to Iceberg's `CatalogUtil`. Check the classpath for your catalog:

| Catalog or warehouse | Needs | Where it is |
|----------------------|-------|-------------|
| REST or Hadoop catalog | `iceberg-core` | `opentsx-store-iceberg` depends on it |
| JDBC catalog | `iceberg-core` and a JDBC driver | `opentsx-store-iceberg` depends on the SQLite driver `org.xerial:sqlite-jdbc` in runtime scope. It is on the classpath of `bin/opentsx windpark-sim`, `bin/opentsx retrieval-server` and `bin/opentsx market` and in the container image. |
| Hive catalog | `iceberg-hive-metastore` | no module and no image |
| Glue catalog | `iceberg-aws` and the AWS SDK | no module. `deploy/docker/stage.sh --with-s3` adds `iceberg-aws` and `iceberg-aws-bundle` to the image classpath. |
| `s3://` warehouse | `iceberg-aws` and `iceberg-aws-bundle` (S3FileIO) | no module. `.github/workflows/image.yml` stages the image with `--with-s3`. |

A missing catalog class fails with `Cannot initialize Catalog implementation <class>`, for example
`org.apache.iceberg.hive.HiveCatalog`. A missing SQLite driver fails with `Failed to connect: jdbc:sqlite:<file>`,
caused by `No suitable driver found`. `<file>` is the catalog database. A missing directory of that file fails with
the same message, caused by `[SQLITE_CANTOPEN] Unable to open the database file`. Create the directory first.

## 5.3 Copy episodes from KafScale to Iceberg

`EpisodeLogSync.sync(consumer, layout, store, fromPositions, batchSize, timeout)` copies episodes from the episodes
topic up to the end offsets at call time. It returns a `Result` with `written`, `skipped`, `undecodable`, `invalid`,
`lostToRetention` and `nextPositions`. Persist `nextPositions`. Writes are idempotent, so at-least-once is safe. A
write that you repeat after a failure adds the `series` rows that the failed write did not commit.

Records that do not decode are counted in `undecodable` and skipped, and `nextPositions` moves past them. A
Confluent-framed record is one of them ([chapter 13](13-migration.md)). An episode that decodes but fails
`Episodes.validate`, for example a `count` that differs from the number of values, is counted in `invalid` and not
written. The first record of each kind per partition is logged at WARN with topic, partition, offset and cause:

```text
WARN org.opentsx.store.iceberg.EpisodeLogSync - undecodable record skipped and counted in undecodable; later ones of this partition are only counted topic=<topic> partition=<n> offset=<offset> cause=<cause>
WARN org.opentsx.store.iceberg.EpisodeLogSync - invalid episode skipped and counted in invalid; later ones of this partition are only counted topic=<topic> partition=<n> offset=<offset> cause=<cause>
```

Check `undecodable` and `invalid` before you persist the positions. A failing store write stops the call with its
error. `timeout` covers the whole call, the metadata requests before the copy included. When it passes first, the call
throws `IllegalStateException` ("timed out syncing <topic>"); when a request got no answer, the client's
`TimeoutException` is its cause.

A refused fetch is waited out within `timeout`. A missing episodes topic fails with `IllegalStateException`
("<topic>: no such topic; check the domain, or create the topic first"). When the log start of a partition lies after
the position you pass, or retention deletes records while `sync` reads them, `sync` reads on from the log start,
counts the deleted records in `lostToRetention` and logs a WARN line with topic, partition, position, log start and
count.

## 5.4 Read the tables from Python

```python
from opentsx.store.iceberg import load_catalog, read_episodes, read_frame
cat = load_catalog(type="rest", uri="http://localhost:8181", warehouse="s3://lake/")
eps = read_episodes(cat, "tsx", metric="wind.met.wind_speed", tags={"turbine": "T07"})   # same records as Java
df = read_frame(cat, "tsx", metric="wind.met.wind_speed")                                # long DataFrame
```

CI checks the bytes: Java writes a warehouse, and PyIceberg must reproduce the same encoded episodes.
