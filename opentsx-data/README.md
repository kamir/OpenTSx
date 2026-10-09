# opentsx-data — legacy data model (v1, deprecated)

**New code must use the v2 model in [`opentsx-model`](../opentsx-model)** (`org.opentsx.model.v2`), see
[`docs/datamodel-2026/04-MODELL-V2-SPEC.md`](../docs/datamodel-2026/04-MODELL-V2-SPEC.md).

This module is frozen. It stays in the build because existing producers, Kafka Streams examples,
ksqlDB UDFs and the Flink job still read and write these records. Migrate data with
[`opentsx-legacy-bridge`](../opentsx-legacy-bridge) (`LegacyBridge.fromEpisodesRecord`, `fromTimeSeriesObject`).

| v1 record (`org.opentsx.data.model`) | Status | v2 replacement |
|--------------------------------------|--------|----------------|
| `EpisodesRecord` (+ inline `Observation`) | in use, deprecated | `Episode` (+ `SeriesKey`, `EpisodeSummary`) |
| `Event` (`event.avsc`, 5 fields) | in use, deprecated | `Observation`; latency fields belong in Kafka headers or a dedicated probe record |
| `EventSeries`, `EventSeriesRecord` (inline 3-field `Event`) | deprecated | `Episode` (`IRREGULAR_DELTA`) |
| `Episodes`, `EpisodesNEW`, `EpisodeMetadata` | unused, deprecated | `Episode`, `BucketManifest` |
| `Episode`, `LatencyTesterEvent` | generated code without `.avsc`, keep for the latency tools | — |

Known defects that will not be fixed in v1:

- `org.opentsx.data.model.Event` is defined three times with different fields (5 fields in `event.avsc`, 3 fields
  inline in both event-series schemas). Same full name, incompatible schemas.
- `EpisodesRecord` stores the time axis twice (per-observation timestamps and `tStart`/`increment`) and a `uri`
  per observation. The bridge uses the per-observation timestamps.
- Generated classes are checked in under `src/main/java`.
