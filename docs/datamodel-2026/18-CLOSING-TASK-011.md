# 18. Closing TASK-011: solution design

This document plans the work that closes [TASK-011](../../EVOLUTION/TASK-011-datamodel-refresher-2026.md). It was
checked against `master` at `551815a3` (version 3.0.1). Decisions D86 to D91 in section 7 are proposals until the
owner confirms them.

TASK-011 holds 117 open and 91 done items. Phases 0 to 3 are done. The goal and the seven success criteria of
TASK-011 (lines 9 to 26) cover phases 0 to 5 only. Phases 6 to 9 and the backlog were added later and are a
program of their own.

The design has five work packages:

| Package | Content | Section |
|---------|---------|---------|
| WP1 | Run `SeriesStats` in Flink: a Flink job around `StatsEngine` | 2 |
| WP2 | Smoke-test the OpenMetadata publisher against a real OpenMetadata 2.0.5 | 3 |
| WP3 | Remove the 2.x Flink example job | 4 |
| WP4 | Move phases 6 to 9 and the backlog into their own tasks, translate TASK-011 | 5 |
| WP5 | Close TASK-011 | 6 |

## 1. Check the success criteria

Each criterion of TASK-011 (lines 18 to 26), translated, with the evidence on `master`:

| # | Criterion | Evidence | Status |
|---|-----------|----------|--------|
| 1 | One Avro schema set `org.opentsx.model.v2` is the only model source. CI checks compatibility with the previous version. | `opentsx-model/src/test/java/org/opentsx/model/SchemaCompatibilityTest.java:19`, run by `mvn -B -ntp verify` in the Java jobs (`.github/workflows/ci.yml:32`) | Met |
| 2 | Round trip `TimeSeriesObject` to `Episode` to Kafka (both wire formats) to `Episode` to `TimeSeriesObject` without loss, time axis included. | `TimeSeriesObject` and `Episode`: `opentsx-legacy-bridge/src/test/java/org/opentsx/bridge/LegacyBridgeTest.java:23,42`. Encoding: `opentsx-model/src/test/java/org/opentsx/model/EpisodesAndWireFormatTest.java:33-71`. Through the broker: `opentsx-kafka-v2/src/test/java/org/opentsx/kafka/EpisodeLogIntegrationTest.java:74`. | Partly evidenced. No single test covers the whole chain with both values of `WireFormat` (`opentsx-model/src/main/java/org/opentsx/model/WireFormat.java:4`). WP5 adds it. |
| 3 | A legacy `TSBucket` is written to KafScale and replayed completely; count and checksum from the manifest match. | `opentsx-legacy-bridge/src/test/java/org/opentsx/bridge/LegacyBucketImportTest.java`, `opentsx-kafka-v2/src/test/java/org/opentsx/kafka/BucketWriterTest.java:53`, `EpisodeLogIntegrationTest.java:74` (runs against KafScale in the KafScale conformance job, `ci.yml:175`) | Met |
| 4 | Bytes per value in the topic measured and documented. Target: at most 2 bytes per value for regular series with zstd; the measurement decides. | `docs/user-manual/04-kafscale-storage.md`, section 4.5: 2.18 bytes per value at sensor resolution, regular | Measured. 0.18 above the target. D86. |
| 5 | The same `EpisodeQuery` gives the same results against the OpenTSDB and the Iceberg adapter (contract test suite). | `opentsx-store-spi/src/test/java/org/opentsx/store/SeriesStoreContract.java:25`, extended by `IcebergSeriesStoreTest.java:26` and `OpenTsdbSeriesStoreTest.java:27` | Met. OpenTSDB runs against the embedded emulator; a real server only with `OPENTSDB_URL`. |
| 6 | A Flink job publishes `SeriesStats`. OpenMetadata shows topics, tables, lineage and profile metrics. | `SeriesStats` are written by the KafScale processor, not by Flink (`opentsx-stats/src/main/java/org/opentsx/stats/StatsProcessor.java:38-47`, `bin/opentsx:49`). The OpenMetadata publisher is validated against the 2.0.5 spec only (`opentsx-openmetadata/README.md:4,68`). | Open. WP1 and WP2. |
| 7 | `similarTo(...)` finds planted patterns in a synthetic benchmark: recall@10 at least 0.95, z-normalized Euclidean distance. | `opentsx-retrieval/src/test/java/org/opentsx/retrieval/pattern/PatternBenchmarkTest.java:81` asserts recall 1.0 for z-ED at noise 0.05. The benchmark plants 12 gusts and searches the top 12 (`:53,63`). | Met with k = 12, not 10. D87. |

## 2. Run SeriesStats in Flink (WP1)

TASK-011 line 88 asks for a Flink wrapper around `StatsEngine` with a Kafka source that commits no offsets. It waited
for the Flink upgrade. Flink 1.20.5 is on `master` (`opentsx-flink-core/pom.xml`, property `flink.version`, and
`flink-connector-kafka` `3.4.0-1.20`).

`StatsEngine` is built for this. Its comment names a Flink operator as a second runtime
(`opentsx-stats/src/main/java/org/opentsx/stats/StatsEngine.java:25-26`). It keeps event time per series, closes
windows itself, and serializes its state (`writeState` at `:266`, `readState` at `:304`).

### 2.1 Data flow

| Step | What runs | Same as in the KafScale processor |
|------|-----------|-----------------------------------|
| Read | `KafkaSource<byte[]>` on the episodes topic, optionally the observations topic of the domain. No consumer group offsets: `commit.offsets.on.checkpoint` is `false`; positions live in Flink checkpoints. Consumer settings from `KafScaleClients.consumerProps` (`opentsx-kafka-v2/src/main/java/org/opentsx/kafka/KafScaleClients.java:54-59`). | `StatsProcessor.assign` reads the same topics by manual assignment (`StatsProcessor.java:152-161`) |
| Decode | The Avro decoder the processor uses, to `Episode` or `Observation` | `StatsProcessor.java:173-176` |
| Key | `keyBy(seriesId)` | The engine keys by `seriesId` internally (`StatsEngine.java:32`) |
| Compute | `StatsFunction extends KeyedProcessFunction`. One `StatsEngine` per key in keyed `ValueState`. It calls `onEpisode` or `onObservation` (`StatsEngine.java:132,153`) and emits the closed `SeriesStats`. | Same calls |
| Global | A processing-time timer per key emits `emitGlobal()` (`:163`) at a configured interval | The processor emits GLOBAL at the end of a run and periodically (`StatsProcessor.java:122,140,145`) |
| End of a bounded run | The final watermark at end of input fires a timer that calls `flush()` (`:177`) | `--once true` closes all windows (`StatsProcessorApp.java:21`) |
| Write | `KafkaSink` to the series-stats topic with `DeliveryGuarantee.AT_LEAST_ONCE` and the producer settings of `KafScaleClients.producerProps` (`:36-50`): `enable.idempotence=false`, `acks=all`, one request in flight | `StatsProcessor.send` (`:185-187`) |

State serialization: a `TypeSerializer` for `StatsEngine` wraps `writeState` and `readState`. With the heap state
backend the engine stays an object between records and is serialized at checkpoints only. With RocksDB it is
serialized on every access; the job documents the heap backend.

Delivery: the output is at least once, as with the KafScale processor. Readers keep the latest record per
(`seriesId`, window type, window start) (`StatsProcessor.java:41-45`, `SeriesStatsLog`). The Flink job writes the
same records, so the same reader rule applies.

### 2.2 Place and entry point

The job lives in `opentsx-flink-core`, package `org.opentsx.flink.stats`, as `StatsJob` (D88). The module gains
dependencies on `opentsx-stats` and `opentsx-kafka-v2`. The options mirror `StatsProcessorApp`
(`StatsProcessorApp.java:19-21,38-39`): `--kafscale`, `--domain`, `--windows`, `--lateness`, `--alpha`,
`--observations`, `--once`. `--checkpoint` does not apply; Flink checkpoints replace the file.

It runs two ways:

* Locally in a Flink mini cluster, through a new verb in `bin/opentsx`, next to `stats` (`bin/opentsx:49`).
* On a Flink cluster with `flink run -c org.opentsx.flink.stats.StatsJob`.

### 2.3 Risk: KafScale and the Flink Kafka source

The Flink Kafka source discovers partitions and start offsets through the Kafka admin client. The KafScale conformance
suite covers topic creation and listing (`opentsx-kafscale-conformance/src/test/java/org/opentsx/kafscale/KafScaleConformanceTest.java:87-89`),
not describe-topics and list-offsets through the admin client. The plain consumer of the KafScale processor works
against KafScale, and it uses the same metadata and list-offsets requests. The first step of WP1 adds both admin calls
to the conformance suite and its capability report (`Capabilities.java`). If one is missing, WP1 stops and reports it
before any job code is written.

### 2.4 Tests

| Test | Where | What it proves |
|------|-------|----------------|
| Parity | `opentsx-flink-core`, Java 17 and 21 jobs | The same episodes through `StatsEngine` directly and through `StatsJob` on a mini cluster give the same set of `SeriesStats`. Bounded source from a list, no network. |
| State | same | Stop after half the input, restore from the checkpoint, finish: the same set as an uninterrupted run. |
| KafScale | KafScale conformance job (`ci.yml:175` gains `opentsx-flink-core`) | Episodes written to KafScale come back as `SeriesStats` on the series-stats topic. Runs only with `KAFSCALE_BOOTSTRAP`. |

Documentation in the same pull request: `opentsx-flink-core/README.md`, `opentsx-stats/README.md`,
`docs/user-manual/07-stats-quality.md` section 7.2, `CHANGELOG.md` under Unreleased.

## 3. Smoke-test OpenMetadata (WP2)

TASK-011 lines 89 and 407 ask for a smoke test against a real OpenMetadata 2.0.5 in a nightly job. The publisher
uses plain JDK HTTP and is tested against `FakeOpenMetadata` and the 2.0.5 spec (`opentsx-openmetadata/README.md:42-68`).

The workflow `.github/workflows/openmetadata-smoke.yml` does this:

1. Start OpenMetadata 2.0.5 with its database and search engine from the docker compose file of the 2.0.5 release.
   The file is pinned to that release. The pin is checked to exist before it is committed.
2. Wait until the server answers its health endpoint.
3. Get a token for the ingestion bot.
4. Run the publisher with `OM_URL` and `OPENMETADATA_JWT` (the keys of `deploy/k3s/env.example:31-32`) on the
   simulated wind farm.
5. Read back through the REST API and fail if a topic, a table, a lineage edge or a profile metric is missing.

Triggers: nightly, by hand (`workflow_dispatch`), and on pull requests that change `opentsx-openmetadata/` or the
workflow itself (D91).

Not known yet, to be read from the 2.0.5 API before the workflow is written: the exact release asset of the compose
file, and the calls that return a bot token. Nothing here is guessed into the workflow.

This cloud session has no Docker daemon. The workflow is verified by its own run on the pull request.

TASK-011 line 407 also names a CI test against an Iceberg REST catalog with MinIO. It moves with the backlog (section 5).

## 4. Remove the 2.x Flink example (WP3)

TASK-011 line 90 asks to move `TimeSeriesAggregateFunction` and `TimeSeriesAnalysisJob` to the v2 serde or to
deprecate them. Nothing outside `opentsx-flink-core` uses them: the only references are their own test
(`opentsx-flink-core/src/test/java/org/opentsx/flink/functions/TimeSeriesAggregateFunctionTest.java`) and the module
README.

The design deletes both classes, their test and the README sections in the WP1 pull request (D89). `StatsJob` is the
v2 Flink job. The processing-flow-descriptor job (`DynamicTopologyJob`, TASK-005) stays.

## 5. Move the later phases out (WP4)

The open items of TASK-011 move to the tasks below. Every moved item keeps its text, translated to English, and a
reference to its old place. TASK-011 itself is translated to English in the same pull request, as `CLAUDE.md` and
`DOCS.md` section 2 require for a German document that is changed.

| Open items in TASK-011 | Count | Target |
|------------------------|-------|--------|
| Phase 4, lines 88 to 90 | 3 | Stay. WP1 to WP3. |
| Phase 5 options: matrix profile, iSAX (lines 99 to 100, again at line 420) | 2 | TASK-018 |
| Wind farm follow-up: Avro 1.12.2 and manufacturer exports (line 81) | 1 | Avro to TASK-019, exports to TASK-018 |
| Phase 6, analytics gaps (lines 102 to 107) | 4 | TASK-018 |
| Phase 7, Smart Topics (lines 108 to 131) | 8 | TASK-016 |
| Phase 8, reconstruction and coupling (lines 132 to 197) | 24 | TASK-015 |
| Phase 9, digital twin (lines 198 to 295) | 39 | TASK-017 |
| Backlog, owner decisions (lines 379 to 386) | 6 | Credentials to TASK-010; D6 to TASK-018; D14 to D17 and ER1 to TASK-015; D18 to D24 and D30 to D36 to TASK-017 |
| Backlog, project environment (lines 387 to 394) | 6 | TASK-017 |
| Backlog, repository and open source (lines 395 to 402) | 4 | TASK-010 |
| Backlog, errors and quality (lines 404 to 408) | 3 | Avro to TASK-019. The OpenMetadata part of line 407 stays (WP2); its Iceberg REST and MinIO part and the records per second of line 408 go to TASK-018. |
| Backlog, major upgrades (lines 410 to 415) | 5 | TASK-019 |
| Backlog, runtime and integration (lines 417 to 420) | 3 | Line 418: the Flink adapter and the 2.x job stay (WP1, WP3); its P8.1 streaming screening goes to TASK-015. Line 419, manufacturer exports, and line 420 go to TASK-018. |
| Backlog, research follow-up (lines 422 to 428) | 6 | TASK-015 |

The new tasks:

| Task | Title | Basis |
|------|-------|-------|
| TASK-015 | Multivariate analysis: pair windows and system reconstruction | Phase 8 and the design of pair windows (one result per episode pair and window, stamped at the window center) agreed on 2026-10-26 |
| TASK-016 | Smart Topics | Phase 7, [05-SMART-TOPICS.md](05-SMART-TOPICS.md) |
| TASK-017 | Digital twin of the energy system | Phase 9, [07-DIGITAL-TWIN-BSP.md](07-DIGITAL-TWIN-BSP.md) |
| TASK-018 | Analytics, retrieval, storage and data-source extensions | Phase 5 options, phase 6, storage measurements, manufacturer exports |
| TASK-019 | Planned major upgrades | The major-upgrade block and Avro 1.12.2 |

The counts come from the checkboxes of TASK-011 on `551815a3`. The pull request lists every moved line with its
target, so nothing is lost.

## 6. Close TASK-011 (WP5)

TASK-011 is closed when all of this holds:

* Criteria 1, 3, 5 and 7 have the evidence of section 1, and D87 is applied.
* Criterion 2 has one test for the whole chain with both wire formats.
* Criterion 4 is accepted by D86.
* Criterion 6 is met by WP1 and WP2.
* WP3 and WP4 are merged.

Then the status in TASK-011 and in [EVOLUTION/README.md](../../EVOLUTION/README.md) becomes Completed.

## 7. Decisions

| D | Proposal | Alternative |
|---|----------|-------------|
| D86 | Accept 2.18 bytes per value as meeting criterion 4. The criterion says the measurement decides, and the number is measured and documented. | Keep the criterion open until a codec change reaches 2.0. |
| D87 | Add a recall@10 assertion to `PatternBenchmarkTest` with 10 planted patterns and keep the recall@12 report. | Change the wording of criterion 7 to recall@k with k equal to the planted count. |
| D88 | `StatsJob` lives in `opentsx-flink-core`. | A new module `opentsx-flink-stats` without the 2.x dependencies of `opentsx-flink-core`. |
| D89 | Delete `TimeSeriesAggregateFunction` and `TimeSeriesAnalysisJob`. | Mark them deprecated and keep them one release. |
| D90 | Split the open items as in section 5. | Keep phases 6 to 9 in TASK-011 and close only the phases 0 to 5 part. |
| D91 | The OpenMetadata smoke test runs nightly, by hand and on pull requests that change the publisher. | Nightly and by hand only. |

## 8. Order of the pull requests

The order matters. A page that links to another is merged after its target (`DOCS.md` section 8).

1. `docs/task-011-split`: WP4. TASK-011 in English, TASK-015 to TASK-019 created, `EVOLUTION/README.md` updated.
   Docs only.
2. `test/kafscale-admin-probe`: the admin-client probe of section 2.3 in the conformance suite.
3. `feat/flink-stats-job`: WP1 and WP3.
4. `test/openmetadata-smoke`: WP2.
5. `test/episode-wire-roundtrip`: the round-trip test of criterion 2 and the recall@10 assertion of D87.
6. `docs/task-011-close`: WP5.

Every pull request runs `mvn -B -ntp verify`. Pull requests that change documentation also run
`python3 tools/check_links.py`. Each one is squash-merged when every check is green.
