# Store SPI — one interface for every time-series backend

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
exactly; point stores must return the same points (as their capabilities allow).
