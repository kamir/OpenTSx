# opentsx-data: legacy data model (v1, deprecated)

**New code must use the v2 model in [`opentsx-model`](../opentsx-model)** (`org.opentsx.model.v2`), see
[`docs/datamodel-2026/04-MODELL-V2-SPEC.md`](../docs/datamodel-2026/04-MODELL-V2-SPEC.md).

This module is frozen. It stays in the build because the 2.x producers (`opentsx-connectors`, `opentsx-lg`) and the
Flink job still read and write these records (the Kafka Streams examples and ksqlDB UDFs are archived). Migrate data with
[`opentsx-legacy-bridge`](../opentsx-legacy-bridge) (`LegacyBridge.fromEpisodesRecord`, `fromTimeSeriesObject`).

| v1 record (`org.opentsx.data.model`) | Status | v2 replacement |
|--------------------------------------|--------|----------------|
| `EpisodesRecord` (`episode.avsc`, + inline `Observation`) | in use, deprecated | `Episode` (+ `SeriesKey`, `EpisodeSummary`) |
| `Event` (`event.avsc`, 5 fields) | in use, deprecated | `Observation`; latency fields belong in Kafka headers or a dedicated probe record |
| `EventSeriesRecord` (`event-series.avsc`, an array of `Event`) | deprecated | `Episode` (`IRREGULAR_DELTA`) |
| `LatencyTesterEvent` | hand-written class, not an Avro record; used by the latency tools | none |

## Build the records

The build generates the Avro record classes from `src/main/avro` into `target/generated-sources/avro`.
`src/main/java` holds only `LatencyTesterEvent`. `event.avsc` is the one definition of `Event`;
`event-series.avsc` refers to it. A field that cannot be null has a getter and a setter of the primitive type, for
example `long getTimestamp()` and `double getValue()` in `Observation` and `Event`. A nullable field keeps the boxed
type, for example `Long getTStart()` in `EpisodesRecord`.

`EpisodesRecord` stores the time axis twice (per-observation timestamps and `tStart`/`increment`) and a `uri` per
observation. The bridge uses the per-observation timestamps. This is not fixed in v1.
