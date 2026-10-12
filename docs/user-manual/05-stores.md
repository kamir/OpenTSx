# 5. Stores: Iceberg, OpenTSDB, in-memory

Modules: `opentsx-store-spi`, `opentsx-store-iceberg`, `opentsx-store-opentsdb-v2` (see their READMEs).

## 5.1 The SPI

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

## 5.2 Stores

| Store | Capabilities | Notes |
|-------|--------------|-------|
| `InMemorySeriesStore` | EPISODES, µs, NaN | reference implementation; tests and notebooks |
| `IcebergSeriesStore` | EPISODES, µs, NaN, SQL, TIME_TRAVEL | tables `<ns>.episodes` (partitioned by `day(t_start)`, `bucket(32, series_id)`), `<ns>.series`, `<ns>.episode_index` |
| `OpenTsdbSeriesStore` | RAW_POINTS, DOWNSAMPLING | OpenTSDB 2.x HTTP API; ms precision; NaN dropped (counted); unit stored as tag `_unit` |

```java
IcebergSeriesStore store = IcebergSeriesStore.open(Map.of("type", "rest", "uri", "http://localhost:8181",
        "warehouse", "s3://lake/"), "tsx");
```

Any Iceberg catalog works: REST, JDBC (SQLite for local use), Hive or Glue. Pass the properties as with Spark.

## 5.3 From KafScale to Iceberg

`EpisodeLogSync.sync(consumer, layout, store, fromPositions, batchSize, timeout)` copies episodes from the episodes
topic and returns the next positions for you to persist. Writes are idempotent, so at-least-once is safe.

## 5.4 Python reads the same tables

```python
from opentsx.store.iceberg import load_catalog, read_episodes, read_frame
cat = load_catalog(type="rest", uri="http://localhost:8181", warehouse="s3://lake/")
eps = read_episodes(cat, "tsx", metric="wind.met.wind_speed", tags={"turbine": "T07"})   # same records as Java
df = read_frame(cat, "tsx", metric="wind.met.wind_speed")                                # long DataFrame
```

CI checks the bytes: Java writes a warehouse, and PyIceberg must reproduce the same encoded episodes.
