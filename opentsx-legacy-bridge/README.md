# opentsx-legacy-bridge: 2.x → v2

Converts 2.x data to v2 episodes. The conversion is lossless.

```java
SeriesKey key = LegacyBridge.seriesKeyFromLabel("wind.met.wind_speed park=ost-01,turbine=T07", "m/s");
Episode e = LegacyBridge.fromTimeSeriesObject(tso, key, TimeUnitOfX.EPOCH_MILLIS, "import");
TimeSeriesObject back = LegacyBridge.toTimeSeriesObject(e, TimeUnitOfX.EPOCH_MILLIS);
Episode fromV1 = LegacyBridge.fromEpisodesRecord(episodesRecord, key);           // opentsx-data EpisodesRecord
List<Episode> eps = LegacyBucketImport.read(Path.of("bucket.tsb.vec.seq"), t0Micros, intervalMicros, "m/s", "import");
```

`TimeUnitOfX` says how the x values of a `TimeSeriesObject` are interpreted (epoch seconds, millis or micros).

See [user manual, chapter 13](../docs/user-manual/13-migration.md).
