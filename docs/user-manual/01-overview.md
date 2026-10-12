# 1. Overview and architecture

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
   * Re-running a job deduplicates instead of duplicating.

## 1.2 Architecture

```
 sources / simulator ──► normalizer (catalog) ──► episodes ─┬─► KafScale topics  tsx.<domain>.*.v2   (cheap log, replay)
                                                            │       │
                                                            │       ├─► statistics processor ─► series-stats.v2 ─► OpenMetadata
                                                            │       │                                               (profiles, DQ)
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
| `opentsx-retrieval` | Segmentation, pattern search, `EpisodeService`, REST server | stable |
| `opentsx-legacy-bridge` | Converts 2.x `TimeSeriesObject`/TSBucket/EpisodesRecord to v2 episodes | stable |
| `opentsx-kafscale-conformance` | Measures which Kafka features a broker supports (KafScale) | stable |
| `opentsx-data` | 2.x Avro model (frozen) | legacy |
| `opentsx-core` | Classic analysis library: TimeSeriesObject, TSBucket, DFA/MFDFA/RIS/ES, generators, GUI tools | legacy, maintained |
| `opentsx-connectors`, `opentsx-lg` | 2.x Kafka producers, topic tools, load generator, latency probes | legacy |
| `opentsx-flink-core` | Flink 1.18 jobs (2.x model) and processing-flow descriptors | legacy |
| `opentsx-app-tools` | Network utilities (netcat recorder, SSH, IP scan) | utility |

Outside the Maven build:

* `python-package/`: the Python library (`pip install -e python-package`).
* `opentsx-saas-backend` and `opentsx-saas-frontend`: experimental web stack, see [chapter 14](14-saas-service-layer.md).
* `archive/`: retired modules and demos, see [chapter 13](13-migration.md).

## 1.4 Design principles

* **Use only what the broker supports.**
  * KafScale has no compaction, no idempotent producer, no timestamp seek, and unreliable consumer-group rejoin.
  * OpenTSx therefore uses manual partition assignment, its own episode index and "latest record wins" readers.
* **One specification, two languages.** Schemas, the canonical series form, the sketch format and the wire format
  are shared and checked by test vectors in CI.
* **Statistics instead of raw data for metadata.** Profiles and data quality come from mergeable statistics, so the
  catalog never needs the raw values.
