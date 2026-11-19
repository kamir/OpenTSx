# opentsx-store-spi: one interface for every time-series backend

`SeriesStore` stores and reads v2 episodes ([`opentsx-model`](../opentsx-model)):

```java
SeriesStore store = IcebergSeriesStore.open(catalogProps, "tsx");      // or OpenTsdbSeriesStore, InMemorySeriesStore
store.write(episodes);                                                // idempotent per episodeId
List<Episode> eps = store.read(EpisodeQuery.of(
        SeriesSelector.metric("wind.turbine.wind_speed").tag("park", "ber-01"), fromMicros, toMicros).clipped());
List<SeriesKey> series = store.listSeries(SeriesSelector.all().tag("turbine", "T07"));
```

| Implementation | Module | Capabilities |
|----------------|--------|--------------|
| `InMemorySeriesStore` | this module | EPISODES, MICROSECOND_PRECISION, NAN_VALUES |
| `IcebergSeriesStore` | [`opentsx-store-iceberg`](../opentsx-store-iceberg) | EPISODES, MICROSECOND_PRECISION, NAN_VALUES, SQL, TIME_TRAVEL |
| `OpenTsdbSeriesStore` | [`opentsx-store-opentsdb-v2`](../opentsx-store-opentsdb-v2) | RAW_POINTS, DOWNSAMPLING (ms precision, no NaN) |

Every adapter extends `SeriesStoreContract` (published as test-jar). Episode stores must round-trip episodes
exactly; point stores must return the same points (as their capabilities allow). A read with `limit(n)` returns the
first `n` episodes in read order (`EpisodeOps.READ_ORDER`).

A clipped read (`.clipped()`) cuts episodes at the query window with `EpisodeOps.clip`. An episode that lies inside the
window is returned unchanged. A part of an episode gets `parentEpisodeId` set to the original id, the `createdAt` of
the original, and an id derived from the original id and the bounds of the part. In a store with `EPISODES`, the same
clipped read returns equal episodes every time. `OpenTsdbSeriesStore` (`RAW_POINTS`) rebuilds each episode from points.
It derives the id from the series and the first and last timestamp and sets `createdAt` to the last timestamp, so the
same read returns equal episodes there too.
