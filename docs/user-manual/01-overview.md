# 1. Find your way around OpenTSx

## 1.1 Core ideas

1. **Series identity is a hash.**
   * A series is `metric` plus tags, for example
     `wind.turbine.power_active{park=ost-01,turbine=T07}`.
   * Its `seriesId` is the xxHash64 of a canonical form, so every system computes the same id without a lookup.
2. **The episode is the unit of exchange.**
   * An episode is an immutable piece of one series: a time axis, values, optional per-value quality, a mergeable
     summary, labels and provenance.
   * Archive, replay, retrieval and statistics all work on episodes. Single observations exist only for live streams.
3. **Buckets group episodes.**
   * A bucket groups the episodes of one experiment, run or import.
   * It moves from OPEN to SEALED and carries a checksum, so a replay can be verified.
4. **Storage is behind one SPI.**
   * Stores declare capabilities (`EPISODES`, `RAW_POINTS`, `SQL`, `TIME_TRAVEL`, …).
   * Callers check capabilities, not store types.
5. **Everything is deterministic where it can be.**
   * Segment ids, simulator output and statistics are reproducible.
   * Re-running a job with deterministic episode ids adds no duplicates to a store with the `EPISODES` capability.

## 1.2 Follow the data flow

```text
 sources ──► normalizer (catalog) ──┐
                                    ├──► episodes ─┬─► KafScale topics  tsx.<domain>.*.v2   (cheap log, replay)
 simulator (catalog) ───────────────┘              │       │
                                                   │       ├─► statistics processor ─► series-stats.v2 ─► OpenMetadata
                                                   │       │                                               (profiles, DQ)
                                                   │       ├─► vector processor ─► clean.v2 · features.v2 · smart.<name>.vectors.v1
                                                   │       │
                                                   │       └─► log sync ─► Iceberg tables (SQL, PyIceberg, time travel)
                                                   └─► stores via SPI: Iceberg · OpenTSDB · in-memory
                                                                 │
                             retrieval: EpisodeService · segmentation · pattern search · REST · Python client
```

## 1.3 Modules

Active Maven modules (root `pom.xml`):

| Module | Purpose | Status |
|--------|---------|--------|
| `opentsx-model` | v2 data model: Avro schemas, series identity, episodes, wire format (Java); Python twin `opentsx.model` | stable |
| `opentsx-kafka-v2` | Topic layout, bucket writer, episode index, replay on KafScale/Kafka | stable |
| `opentsx-store-spi` | `SeriesStore` SPI, queries, capabilities, in-memory store, contract tests | stable |
| `opentsx-store-iceberg` | Iceberg store, episode index table, KafScale → Iceberg sync | stable |
| `opentsx-store-opentsdb-v2` | OpenTSDB 2.x HTTP store | stable |
| `opentsx-windpark` | IEC 61400-25-2 data-point catalog, normalizer, simulator, power-curve analysis, CLI | stable |
| `opentsx-stats` | Streaming statistics, quantile sketch, data-quality checks, KafScale processor | stable |
| `opentsx-openmetadata` | Publishes the metadata pool to OpenMetadata 2.0 | stable (validated against the 2.0.5 schemas) |
| `opentsx-retrieval` | Segmentation, pattern search, `EpisodeService`, REST server, vector processor `bin/opentsx vectors` that runs operator DAGs on episode topics ([chapter 17](17-operators.md)) | stable |
| `opentsx-twin` | Digital twin: atoms, wiring plan, runner, three modes ([README](../../opentsx-twin/README.md)) | proof of concept |
| `opentsx-twin-live` | Events of a twin run as the topic `twin.<id>.<run>.events` (JSON, schema `twin.event.v1`) and an SSE bridge for browsers; `bin/opentsx twin-live` with `publish`, `bridge`, `demo` ([README](../../opentsx-twin-live/README.md)) | JSON contract; no Avro variant |
| `opentsx-dwd` | DWD Open Data collector: MOSMIX forecasts and CDC station observations as v2 series and episodes, bound to the weather inputs of the twins; `bin/opentsx dwd` ([README](../../opentsx-dwd/README.md)) | tests offline on fixtures; parsers checked against live DWD downloads |
| `opentsx-modbus` | Modbus client (JDK only), YAML register maps, poller, normalizer → v2 episodes, meter simulator ([README](../../opentsx-modbus/README.md)) | tested against the simulator; real meter pending |
| `opentsx-market` | Electricity-market collector (SMARD, Energy-Charts, ENTSO-E, netztransparenz.de), local history archive with quality reports, twins on real history; `bin/opentsx market` ([README](../../opentsx-market/README.md)) | SMARD, Energy-Charts and DWD history collected (2019 to 2026-10-12); ENTSO-E and netztransparenz.de tested against fixtures only, no credentials |
| `opentsx-legacy-bridge` | Converts 2.x `TimeSeriesObject`/TSBucket/EpisodesRecord to v2 episodes | stable |
| `opentsx-kafscale-conformance` | Measures which Kafka features a broker supports (KafScale) | stable |
| `opentsx-data` | 2.x Avro model (frozen) | legacy |
| `opentsx-core` | Classic analysis library: TimeSeriesObject, TSBucket, DFA/MFDFA/RIS/ES, generators, GUI tools | legacy, maintained |
| `opentsx-connectors`, `opentsx-lg` | 2.x Kafka producers, topic tools, load generator, latency probes | legacy |
| `opentsx-flink-core` | Flink 1.20.5: v2 statistics job `StatsJob`; processing-flow descriptors (2.x model) | current (`StatsJob`), legacy (PFD) |

Outside the Maven build:

* `python-package/`: the Python library (`pip install -e python-package`).
* `opentsx-saas-backend` and `opentsx-saas-frontend`: experimental web stack, see [chapter 14](14-saas-service-layer.md).
* `archive/`: the 2025 documentation and old demos. Retired modules are not on `master`. See
  [chapter 13](13-migration.md).

## 1.4 Design principles

* **Use only what the broker supports.**
  * KafScale has no compaction, no idempotent producer, no timestamp seek, no consumer-group rejoin and no
    standalone commits.
  * OpenTSx therefore uses manual partition assignment, its own episode index, and readers that pick one record
    among repeated ones ([chapter 4](04-kafscale-storage.md#41-check-what-kafscale-supports)).
* **One specification, two languages.** Schemas, the canonical series form, the sketch format and the wire format
  are shared and checked by test vectors in CI.
* **Statistics instead of raw data for metadata.** Profiles and data quality come from mergeable statistics, so the
  catalog never needs the raw values.
