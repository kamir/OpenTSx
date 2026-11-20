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
Two or more equally spaced, increasing timestamps give a REGULAR episode, all others an IRREGULAR_DELTA episode.
Every episode carries the producer `opentsx-legacy-bridge/<version>`, `<version>` the Maven version of the build.

## Name the series of an EpisodesRecord

`fromEpisodesRecord(record, null)` parses the series from the label of the record. A record without a label needs a
`SeriesKey` as the second argument. Without one, it throws `IllegalArgumentException`:

```text
label: missing in the EpisodesRecord "opentsx://demo"; expected a label such as "metric k1=v1,k2=v2" or a SeriesKey argument
```

A record without a uri is named `an EpisodesRecord without uri`. A record or a `TimeSeriesObject` without values
throws `values: empty; expected at least one value`.

`seriesKeyFromLabel` throws `IllegalArgumentException` for an empty label and for a tag that is not `key=value`:

```text
label: empty; expected "metric k1=v1,k2=v2"
label: tag "broken" in "m broken" is not key=value; expected "metric k1=v1,k2=v2"
```

See [user manual, chapter 13](../docs/user-manual/13-migration.md).
