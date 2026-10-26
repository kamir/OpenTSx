# TASK-011: Datamodel refresher 2026

- **Branch:** `opentsx/2026-datamodel-refresher`
- **Status:** In progress. Closing: phases 0 to 3 done; the open items of phases 4 and 5 and the close-out are planned in [18-CLOSING-TASK-011](../docs/datamodel-2026/18-CLOSING-TASK-011.md) (D86 to D91).
- **Priority:** High
- **Created:** 2026-10-09
- **Documents:** [`01-ANALYSE-Datenmodell.md`](../docs/datamodel-2026/01-ANALYSE-Datenmodell.md) · [`02-ZIELMODELL.md`](../docs/datamodel-2026/02-ZIELMODELL.md) · [`03-GAP-ANALYSE.md`](../docs/datamodel-2026/03-GAP-ANALYSE.md)

## Goal

An explicit, versioned time series data model (v2) with reliable serialization, so that

1. TimeSeriesBuckets can be stored **cheaply in KafScale topics** and loaded back **by replay**,
2. metadata flows into **OpenMetadata**, fed by a **streaming processor** that computes running statistics over the event or episode data stream,
3. **OpenTSDB** and **Iceberg** are connected through a common store abstraction,
4. a **simple API for episode retrieval** and, internally, **pattern matching / pattern-based retrieval** exist.

## Success criteria

- One Avro schema set (`org.opentsx.model.v2`) is the only model source; CI checks compatibility against the previous version.
- Roundtrip tests: `TimeSeriesObject → Episode → Kafka (both wire modes) → Episode → TimeSeriesObject` lossless (time axis included).
- An existing TSBucket (SequenceFile) is written to KafScale and fully reconstructed by replay (count + checksum from the manifest match).
- Bytes per value in the topic measured and documented (target: ≤ 2 bytes/value for regular series with zstd; the measurement decides).
- The same `EpisodeQuery` returns semantically equal results against the OpenTSDB and Iceberg adapters (contract test suite).
- A Flink job publishes `SeriesStats`; OpenMetadata shows topics, tables, lineage and profile metrics.
- `similarTo(...)` finds planted patterns in a synthetic benchmark (Recall@10 ≥ 0.95, z-ED).

## Non-goals (for this iteration)

- Rework of the algorithm library / decomposition of `TimeSeriesObject` (bridge only).
- Custom compression codecs before a measurement with zstd.
- UI.

## Phases and tasks

### Phase 0: foundation and decisions
- [x] Decisions are kept in the "Decisions" table of this document (the old folder `DECISIONS/` is in `EVOLUTION/archive/`).
- [x] Toolchain: whole project on `maven.compiler.release=17`, versions only in `dependencyManagement`, Enforcer (JDK ≥ 17, no log4j 1.x); Kafka 3.9.1 / Confluent 7.9.1 / Avro 1.12.1 (since the wind farm PR; 1.12.0 decodes double arrays with float precision, 1.12.2 requires `org.apache.avro.SERIALIZABLE_PACKAGES` → follow-up task); `opentsx-core` + `-data`: 65 instead of 388 artifacts.
- [x] Legacy modules moved to `archive/modules/` (Cassandra store, KStreams Cassandra state store, Hive UDF, ksqlDB UDF).
- [x] CI (GitHub Actions): Java 17 + 21, Python 3.10 to 3.13, KafScale conformance (broker v1.6.0 built from source).
- [x] Python tests with real assertions (18 tests + 1 documented gap as strict xfail).
- [x] KafScale conformance suite + `docker-compose.kafscale.yml`; measured against KafScale v1.6.0 and Apache Kafka 3.9.1.
- [x] Flink raised to 1.20.5 (#147), runs on 17/21. The Flink adapters remain open (see P4).
- [x] `opentsx-app-tools` moved to `archive/modules/` (sshd-core 0.8.0, no dependents).
- [x] Issues filed in the KafScale fork: [kamir/kafscale#16](https://github.com/kamir/kafscale/issues/16) ListOffsets timestamps, [#17](https://github.com/kamir/kafscale/issues/17) LeaveGroup v4 / MEMBER_ID_REQUIRED, [#18](https://github.com/kamir/kafscale/issues/18) standalone OffsetCommit.
- [x] Immediate fixes in `TSOProducer`: label `"123"`/URI `"URI"`, timestamps that grew quadratically (`t = t + i * 200`), missing required field `consumer_timestamp`. (Mixed formats on `OpenTSx_Episodes` go away with the v2 topics in P2.)

### Phase 1: model v2 and serde (`opentsx-model`, `opentsx-serde`)
- [x] Avro schemas: `SeriesKey`, `Observation`, `Episode` (+ `Segmentation`), `EpisodeSummary`, `Provenance`, `BucketManifest`, `SeriesStats`, `PatternMatch`. Specification: [`04-MODELL-V2-SPEC.md`](../docs/datamodel-2026/04-MODELL-V2-SPEC.md).
- [x] Code generation into `target/generated-sources` (no longer checked in).
- [x] `SeriesId` (xxHash64, canonical form) including shared test vectors, identical in Java and Python.
- [x] `OpenTsxAvro`: single-object encoding (default), Confluent framing readable with a resolver, Kafka serializer/deserializer; Python counterpart byte-identical. The Flink `DeserializationSchema` follows with the Flink migration (P4).
- [x] Time encodings `REGULAR`, `IRREGULAR_DELTA` + roundtrip tests and test vectors.
- [x] Schema history (`schema-history/v2.0`) + BACKWARD_TRANSITIVE check in the build; a schema change forces a new version.
- [x] v1 model frozen and documented as deprecated (`opentsx-data/README.md`), `Event` conflict documented.
- [x] `opentsx-legacy-bridge`: `TimeSeriesObject ⇄ Episode` lossless, `EpisodesRecord v1 → Episode`.
- [x] Pandas integration with a UTC `DatetimeIndex` (`opentsx.model.pandas_io`).

### Phase 2: Kafka/KafScale persistence and replay (`opentsx-connectors` or rather `opentsx-kafka-v2`)
- [x] Topic layout `tsx.<domain>.{observations,episodes,series,buckets,series-stats}.v2` + provisioning (`TopicLayout.provision`), client settings for KafScale (`KafScaleClients`).
- [x] `BucketWriter` (manifest OPEN → episodes → SEALED + checksum), asynchronous, **not** idempotent (KafScale), `max.in.flight=1`, zstd, headers, record timestamp = `tStart`; no SEAL on send errors.
- [x] `ReplayService`: by bucket, or by series + time range via the **episode index** (instead of `offsetsForTimes`), manual assignment + seek, event-time order, pacing, dedup, verification against the manifest, bounded memory. `EpisodeIndexer` rebuilds the index from the log, `BucketCatalog` reads manifests. Sink: callback (topic/TSBucket sinks when needed).
- [x] `LegacyBucketImport`: TSBucket SequenceFile → episodes (→ `BucketWriter`); `EpisodesRecord` v1 → v2 via `LegacyBridge`.
- [x] Cost measurement: **2.18 B/value** (1 Hz, sensor resolution, regular, zstd), 6.96 B/value at full double precision, 4.07 B/value with time jitter (`opentsx-kafka-v2/README.md`). Records/s against KafScale S3 still open (locally only in-memory S3).
- [x] `TSOWriter.persistBucket_Kafka` stub marked as deprecated and pointed to the v2 path.

### Phase 3: store SPI, OpenTSDB, Iceberg (`opentsx-store-spi`, `opentsx-store-opentsdb`, `opentsx-store-iceberg`)
- [x] `opentsx-store-spi`: `SeriesStore`, `EpisodeQuery`, `SeriesSelector`, `StoreCapabilities`, `EpisodeOps.clip`, `InMemorySeriesStore` (replaces the empty `TSBucketStore`/`TSO*Interface`).
- [x] Contract test suite (`SeriesStoreContract`, test-jar), green for in-memory, Iceberg (JDBC/SQLite catalog) and OpenTSDB (emulator). Open: real OpenTSDB server and Iceberg REST catalog + MinIO in CI (no Docker locally).
- [x] OpenTSDB adapter `opentsx-store-opentsdb-v2`: HTTP API, validation of metric/tags (≤ 8), unit as `_unit`, `/api/put` batched, `/api/query` → episodes; ms precision, NaN is dropped and counted.
- [x] Iceberg adapter `opentsx-store-iceberg` (Iceberg 1.10): tables `episodes` (lossless, `day(t_start)` + `bucket(32, series_id)`), `series`, `episode_index`; idempotent writes; time travel. Python reads the same tables with PyIceberg byte-identically (CI job `cross-language`).
- [x] KafScale → Iceberg: `EpisodeLogSync` (manual assignment, positions returned, idempotent) + `IcebergEpisodeIndex` as a durable replay index; end-to-end test against KafScale v1.6.0.

### Wind farm domain (gap roadmap P3, `opentsx-windpark`)
- [x] Versioned data point catalog (`channel-catalog.yaml` v1.0.0) with mapping to IEC 61400-25-2 (LD/LN/DO/DA, CDC, edition), unit (UCUM), resolution, plausibility, quality codes, vendor extensions (`vendorSpecific`, `notes`).
- [x] Source system mappings: original name, source address, scaling/offset/sign, source unit, time zone, time reference (INSTANT/START/END), aggregation (`mean.PT10M` …), status CONFIRMED/PROVISIONAL/UNMAPPED.
- [x] Asset model (`TurbineType`, `Turbine`, `Park`) + inventory (expected series) as input for OpenMetadata.
- [x] Normalizer: names/units/UTC/interval start/quantization/plausibility → aligned 10-min episodes + report (nothing is guessed).
- [x] Simulator: Weibull + OU turbulence (exactly discretized), Jensen wake, power curve, pitch/yaw/temperature, operating events, sensor faults with ground truth; deterministic including episode IDs.
- [x] CLI `WindparkSim` → KafScale (sealed bucket) and/or Iceberg; end-to-end tests (Iceberg always, KafScale with `KAFSCALE_BOOTSTRAP`, in CI).
- Follow-up moved on 2026-10-26 (D90): Avro 1.12.2+ with `SERIALIZABLE_PACKAGES` to [TASK-019](TASK-019-major-upgrades.md), real vendor exports as further `sources` to [TASK-018](TASK-018-analytics-storage-extensions.md).

### Phase 4: streaming statistics and OpenMetadata (`opentsx-stats`, `opentsx-openmetadata`)

The three open items are WP1 to WP3 of [18-CLOSING-TASK-011](../docs/datamodel-2026/18-CLOSING-TASK-011.md).

- [x] `StatsEngine` (runtime-independent): per `seriesId` Welford + min/max + NaN + **quantile sketch** (DDSketch, 1 % relative error, Java/Python byte-identical) + gap detection; GLOBAL + epoch-aligned tumbling windows; event time per series (episode → `tEnd`); dedup by `episodeId`; checkpoint of the complete state.
- [x] `StatsProcessor` on KafScale (manual assignment, no groups/commits, checkpoint file after confirmed outputs) → `series-stats.v2`. No compaction on KafScale → `SeriesStatsLog` reads "latest per key". Late data: counted, GLOBAL only. Green against KafScale v1.6.0.
- [x] Data quality on statistics (completeness, plausibility band via sketch, flatline, gaps, freshness); finds the simulator faults exactly in the affected windows.
- [x] `opentsx-openmetadata`: glossary from the data point catalog, classifications (IEC LN, mapping status), custom properties, topics with Avro schema, Iceberg tables, signal view per park, pipelines + lineage, profiles per window, DQ test definitions/cases/results. Payloads validated against the JSON schemas of `openmetadata-spec 2.0.5`; dry-run mode.
- [ ] Flink wrapper around `StatsEngine` (KafkaSource without offset commits), not before the Flink upgrade.
- [x] Smoke test against a real OpenMetadata 2.0.5 server (docker compose, nightly). *Done 2026-10-26 (WP2): workflow `openmetadata-smoke.yml` and `OpenMetadataSmokeTest`.*
- [ ] Migrate the existing `TimeSeriesAggregateFunction`/`TimeSeriesAnalysisJob` to the v2 serde or deprecate them.

### Phase 5: segmentation, retrieval and pattern matching (`opentsx-retrieval`)
- [x] Segmenters for all four strategies (FIXED_DURATION, FIXED_COUNT, FEATURE_EXTREMUM, FEATURE_THRESHOLD), incremental → streaming == batch (tested), deterministic segment IDs, lineage.
- [x] Pattern engine: MASS (zED), PAA/SAX including MINDIST (exact prefilter, `EpisodeSummary.sax`), DTW with Sakoe-Chiba, LB_Kim/LB_Keogh (UCR cascade); exact top-k without overlap across episode boundaries; whole-episode similarity.
- [x] Benchmark on simulated wind data (planted gusts, warping, noise) → recall/latency report.
- [x] `EpisodeService`: `episodes()` (+ resampling), `episode(id)` (new in the store SPI), `similarTo`, `similarEpisodes`, `segments(...).writeTo(store)`.
- [x] Thin REST layer (`RetrievalServer`, JSON or Avro stream) + Python client `opentsx.client.RetrievalClient`; live test Python ↔ Java in CI.
- [x] Power curve analysis (method of bins, performance index, underperformance phases) in `opentsx-windpark`.
- The options 5c (Matrix Profile motif/discord) and the iSAX index moved on 2026-10-26 (D90) to [TASK-018](TASK-018-analytics-storage-extensions.md).

### Phases 6 to 9: moved

Phases 6 to 9 moved on 2026-10-26 (D90) to their own tasks (line numbers refer to this file at commit 551815a3).

| Phase | Moved to |
|-------|----------|
| Phase 6: analytics gaps (lines 102 to 107) | [TASK-018](TASK-018-analytics-storage-extensions.md) |
| Phase 7: Smart Topics (lines 108 to 131) | [TASK-016](TASK-016-smart-topics.md) |
| Phase 8: system reconstruction, simulation and optimization (lines 132 to 197) | [TASK-015](TASK-015-multivariate-analysis.md) |
| Phase 9: digital twin of the energy system (lines 198 to 295) | [TASK-017](TASK-017-digital-twin.md) |

## Module structure (implemented, as of 2026-10-12)

```
opentsx-model                 Avro v2 schemas, SeriesId, episodes, wire format including Kafka serdes (instead of a separate opentsx-serde)
opentsx-legacy-bridge         TimeSeriesObject/TSBucket/EpisodesRecord <-> v2
opentsx-store-spi             SeriesStore, EpisodeQuery, contract tests, in-memory
opentsx-store-iceberg         Iceberg store, episode index, KafScale->Iceberg sync
opentsx-store-opentsdb-v2     OpenTSDB HTTP store (the old client is in the archive)
opentsx-kafka-v2              TopicLayout, BucketWriter, index, ReplayService
opentsx-kafscale-conformance  Measures broker capabilities
opentsx-windpark              Data point catalog (IEC 61400-25-2), normalizer, simulator, power curve
opentsx-stats                 Streaming statistics + DQ (runtime-independent; Flink adapter follows with the Flink upgrade)
opentsx-openmetadata          Metadata/profile/DQ publisher
opentsx-retrieval             Segmentation, pattern engine, EpisodeService, REST
opentsx-twin                  Digital twin P9: atom contract MVC+P, wiring plan, runner variant T, 3 modes
opentsx-modbus                Modbus TCP/RTU over TCP (JDK only), register maps (YAML), poller, normalizer, meter simulator
(planned) opentsx-smart       Smart Topics (P7)
(planned) opentsx-network     Edge table, reconstruction (Kämpf significance layer, ECA), problem builder (P8)
```

## Decisions (as of 2026-10-12)

| # | Question | Status / decision |
|---|----------|-------------------|
| D1 | KafScale capabilities | **Clarified (v1.6.0):** no compaction, no idempotent producer/transactions, **ListOffsets ignores timestamps**, no Schema Registry, zstd ok, preservation of headers/CreateTime undocumented → conformance test. Consequences: 03-GAP-ANALYSE §5 |
| D2 | Time precision | **Decided:** `timestamp-micros` |
| D3 | SeriesId hash | **Decided:** xxHash64 (seed 0) over the canonical form, 16 hex characters |
| D4 | Episode definition | **Decided:** fixed duration, fixed length **and** feature-based (extremum ± pre/post, threshold→threshold) → `Segmentation` record, 03-GAP-ANALYSE §7 |
| D5 | Java baseline | **Decided: no Java 8.** Baseline as in current Apache projects: `release=17`, CI additionally on JDK 21 (Kafka 4.x, Flink 2.x, Spark 4.x, Iceberg 1.x all run on 17/21). Existing code is raised along with it or archived. **Python ≥ 3.10** on equal footing (Pandas/Jupyter, PySpark) |
| D6 | Iceberg catalog and object store | REST catalog + S3-compatible (MinIO locally); open |
| D7 | OpenMetadata | **2.0.5**, push via SDK/REST (no Airflow), bot JWT; profiles only on tables → stats on Iceberg tables; inventory via glossary/tags/custom properties |
| D8 | Handling of v1 topics | Keep supporting them read-only, migration to v2 by replay |
| D9 | Value compression beyond zstd | **Measured:** 2.18 B/value is enough for now; quantized integer encoding (< 1 B/value) as an option for a later schema version |
| D10 | Wire format default | `SELF_DESCRIBING` (Avro single-object encoding), since KafScale does not ship a registry |
| D11 | Time-based replay | Own episode index in Iceberg (`tsx.episode_index`) instead of `offsetsForTimes` |
| D12 | Smart Topics | **Decided (2026-10-12):** Python training first (ONNX/JSON, scoring in Java), MLflow as registry, model per series + federated global model (Wayang approach), implementation as P7 after P6 |
| D13 | Documentation | **Decided (2026-10-12):** user manual in English; the SaaS stack stays (as a future Personal Service Layer); modules/docs outside the build archived |
| D14 | P8: method architecture | **Proposal:** screen-then-confirm: cheap screening (CC/ECA/Kämpf significance) in the stream (Java), causal confirmation (PCMCI+/mTE/Hawkes) in batch (Python); cross-correlation always as baseline |
| D15 | P8: licenses | **Proposal:** GPL libraries (tigramite, JIDT, IDTxl) only behind a process boundary; Kämpf measures and ECA as clean-room implementations under Apache 2.0 |
| D16 | P8: quantum computing | **Proposal:** classical solvers are the default; quantum backends only as an object of measurement with a benchmark protocol fixed in advance (no proven advantage, as of 2026) |
| D17 | P8: order | **Proposal:** benchmark with ground truth first (P8.0/P8.1), quantum last (P8.5) |
| D18 | P9: atom contract | **Proposal:** MVC+P (dynamics and policy separated), Avro schema per atom type, atom catalog as standard components, wiring plan YAML per plant |
| D19 | P9: execution | **Proposal:** layered BSP, base tick 1 min, multirate as integer multiples, deterministic, barrier topic |
| D20 | P9: embedding | **Decided (2026-10-12):** both execution variants on equal footing: tightly coupled as one process (T) and loosely coupled via Smart Topics (L); all three modes in both; comparison as a research question (P9.6) |
| D21 | P9: data quality | **Proposal:** provenance ladder with taint propagation; every KPI shows its measured share |
| D22 | P9: approach | **Proposal:** stages A to D with value gates; stage A (data and potential) without simulation |
| D23 | P9: language | **Proposal:** the core stays Java; edge collectors may be Go/Rust; no rebuild of the core before gate B |
| D25 | P7f: stream runtime | **Proposal:** Go TSx processor (KafScale processor pattern) for the data plane; no Kafka Streams (needs compaction/transactions); Flink only for large joins |
| D26 | P7f: algorithms in Go | **Proposal:** only the stream core, with shared test vectors; pattern search, twin and causal analysis stay Java/Python |
| D27 | P7f: DAG specification | **Proposal:** `opentsx.dag.v1` (YAML), hashed, read by Go and Java, editable in the Studio |
| D28 | P7f: training | **Proposal:** never in the stream; trigger → Python/MLflow → `model-events` → swap during operation |
| D29 | P7f: semantics | **Proposal:** at-least-once + deterministic ids = effectively exactly once; event time with revisions |
| D30 | P9.9: anchor of the inventory | **Proposal:** asset id = twin id/atom id (+ sub-object path); no second plant structure |
| D31 | P9.9: image policy | **Proposal:** real photos preferred; provenance, license and `reality` per medium; illustrations always labeled |
| D32 | P9.9: storage | **Proposal:** inventory YAML as input; Iceberg tables (versioned) + object storage with SHA-256; OpenMetadata as catalog |
| D33 | P9.9: maintenance | **Proposal:** lightweight tracking as an event log `tsx.<domain>.maintenance.v1`; the CMMS stays the leading system where one exists; work orders = interventions in the twin |
| D34 | P9.9: designations | **Proposal:** RDS-PP, IEC 61400-25, ZEUS optional for wind; ISO 14224 generic; not mandatory for house/boat |
| D35 | P9.9: infinimesh | **Proposal:** device state from infinimesh, meaning from the inventory; coupling via `{namespace, device}`; commands only with approval |
| D36 | P9.9: presentation | **Proposal:** 2D (SVG/Canvas, tiles) first, 3D only in E4 |
| D24 | P9: co-simulation | **Proposal:** screening (mosaik, HELICS, FMI 3.0) before building a runner of our own |

> **Note:** The phase order follows the gap analysis
> ([`03-GAP-ANALYSE.md` §10](../docs/datamodel-2026/03-GAP-ANALYSE.md)), extended by P7 (Smart Topics),
> P8 (system reconstruction and optimization) and P9 (digital twin of the energy system).

## Risks

- **Legacy modules (2.x)** in the build (`opentsx-core`, `-connectors`, `-lg`, `-flink-core`, `-data`) → strictly separated, bridge in one direction only (`opentsx-legacy-bridge`).
- **Spurious links from autocorrelation/long-range correlation** (P8) → surrogates that preserve long-range correlations, FDR, ground truth benchmarks, CC baseline.
- **GPL contamination** (tigramite, JIDT, IDTxl) → process boundary, clean-room implementations.
- **Quantum hype** → only an object of measurement with a fixed protocol; "no advantage" is a valid result.
- **Identifiability/non-stationarity** in reconstruction and twins → regime segmentation (P5), targeted excitation in the simulator.
- **`offsetsForTimes` assumes a monotonic CreateTime per partition**: with out-of-order production, time replay is imprecise → replay reads from the found offset and filters, Iceberg as the precise index.
- **Twin larger than its benefit** (P9) → stages with value gates; stage A without simulation; screening of existing co-simulation tools before building our own.
- **Data access depends on partners** (electrolyzer, marketer, grid operator meters) (P9) → provenance ladder with estimators/profiles, so that the twin computes plausibly even without these data and reports the measured share.
- **Algebraic loops** in the power balance (P9) → layered BSP with balance nodes in the same step; cycles only across step boundaries.
- **OpenMetadata load** with statistics per series → aggregation and rate limit.
- **Schema drift** between Java and Python → shared `.avsc` + shared test vectors.

## Open items and backlog: moved

The backlog moved on 2026-10-26 (D90) to the tasks in this table (line numbers refer to this file at commit 551815a3); the open items of phase 4 stay above; the open items of phase 5 and of the wind farm domain moved with them.

| Backlog block | Source lines | Open items moved to |
|---------------|--------------|---------------------|
| Owner decisions | 379 to 385 | Credentials (line 380): [TASK-010](TASK-010.md). D6 (line 381): [TASK-018](TASK-018-analytics-storage-extensions.md). D14 to D17 and ER1 (lines 382 to 383): [TASK-015](TASK-015-multivariate-analysis.md). D18, D19, D21 to D24 and D30 to D36 (lines 384 to 385): [TASK-017](TASK-017-digital-twin.md). |
| Energy system and project environment | 387 to 393 | [TASK-017](TASK-017-digital-twin.md) |
| Repository and open source | 395 to 402 | Open items (lines 397, 398, 400, 401): [TASK-010](TASK-010.md). Lines 396, 399 and 402 were already done. |
| Errors and quality | 404 to 408 | Line 405 was already done. Avro 1.12.2+ (line 406): [TASK-019](TASK-019-major-upgrades.md). Line 407: the OpenMetadata smoke test stays in phase 4 here; its Iceberg REST catalog and MinIO part: [TASK-018](TASK-018-analytics-storage-extensions.md). Line 408: [TASK-018](TASK-018-analytics-storage-extensions.md). |
| Major upgrades | 410 to 415 | [TASK-019](TASK-019-major-upgrades.md) |
| Runtime and integration | 417 to 420 | Line 418: the Flink adapter for `StatsEngine` and the migration of the 2.x job `TimeSeriesAggregateFunction`/`TimeSeriesAnalysisJob` stay in phase 4 here; its P8.1 streaming screening: [TASK-015](TASK-015-multivariate-analysis.md). Its remark that Flink 2.x is a separate decision: [TASK-019](TASK-019-major-upgrades.md). Lines 419 and 420: [TASK-018](TASK-018-analytics-storage-extensions.md). |
| Research follow-up | 422 to 428 | [TASK-015](TASK-015-multivariate-analysis.md) |

## Progress Log

- 2026-10-26: **Split and translation** (D90 in [18-CLOSING-TASK-011](../docs/datamodel-2026/18-CLOSING-TASK-011.md)): this document is in English; phases 6 to 9 and the backlog moved to TASK-015 to TASK-019 and TASK-010. TASK-015 adds the design of pair windows. Open here: the three items of phase 4 (WP1 to WP3).
- 2026-10-12: **Archive slimmed down**: archived Maven modules (`archive/modules/`, `archive/opentsx-kstreams/`, 15 `pom.xml`) removed from `master`, because their old versions (sshd-core 0.8, Cassandra 3.11.4, Gson 2.7 …) kept producing security warnings and Dependabot PRs; also four dead submodule links in `archive/demos/`. Last state: commit `315f01d`; docs (manual chapters 1 and 13, README, inventory, CHANGELOG) updated.
- 2026-10-12: **Market and weather history** (`opentsx-market`): collectors for SMARD, Energy-Charts, ENTSO-E (token) and netztransparenz.de (OAuth/CSV import), partner file import; local archive with manifest and quality report, resumable backfill (SMARD archived from 2019 to today; ENTSO-E and netztransparenz skipped without credentials), raw forward archive for MOSMIX, k3s CronJobs (`deploy/k3s/history/`); H2 chain, house and boat with `--history` on real years, standard demos unchanged and synthetic.
- 2026-10-12: **Market research, customers and campaign** (`docs/business/`): vendors of digital twins checked for coupling of live and simulation data (criteria a to h, comparison matrix with sources, retrieved 2026-10-12; the coupling itself is common, the OpenTSx combination of provenance per value, bit-identical replay, federation and openness was not found); customers and benefits with sourced market figures and prioritization (wind/H2/marketing cluster, municipal utilities, research); campaign "Compute first, then switch" with a 90-day plan, a claims guide, three sample posts and an outline of the landing page.
- 2026-10-12: **Dependencies cleaned up**: Dependabot flood bundled (GitHub Actions to current majors, backend pins, backend on Python 3.12), Maven minor/patch group without the four blockers (Avro, Iceberg, JFreeChart, zstd-jni); majors only as a planned migration (backlog "Major upgrades").
- 2026-10-12: **DWD collector** `opentsx-dwd`: MOSMIX_L and CDC station data as v2 series/episodes with unit and provenance, nearest stations (house in the Erzgebirge: Marienberg/Fichtelberg, Baltic Sea: Greifswalder Oie/Arkona), binding to the house and boat twins, CLI `bin/opentsx dwd`; tests offline with hand-built fixtures, parser additionally checked against opendata.dwd.de.
- 2026-10-12: **P9.0 Modbus module** `opentsx-modbus`: Modbus TCP client without a third-party library (MBAP, transaction IDs, timeouts, reconnect, exception responses in plain text; RTU over TCP with CRC16), register maps as YAML with validation (overlaps, types, OBIS, provenance), block grouping, normalizer → v2 episodes with meter overflow/reset flags, in-JVM simulator and `bin/opentsx modbus check|read|poll|simulate`; sample map of a grid connection meter until the real register maps arrive.
- 2026-10-12: **Repository hygiene**: LICENSE (Apache 2.0, full text) + NOTICE, CONTRIBUTING, CODE_OF_CONDUCT, SECURITY.md (reporting via GitHub Private Vulnerability Reporting), CHANGELOG (Keep a Changelog); private client profiles `config/cpl*.props`/`mdc_c1.props` replaced by `config/opentsx-local.props.example` (fallback in `OpenTSxClusterLink`), references in code, scripts and docs updated; `opentsx.properties.example` reduced to the keys actually read and the v2 configuration. No history rewrite.
- 2026-10-12: **P9 variant L** (`LooseRunner`, shared execution core `RunSetup`): conformance L(`s = 0`) = T bit-identical for all three scenarios and modes; measurement program `bin/opentsx twin-coupling`; Avro schemas per atom type. Finding: only feedback loops limit throughput (formula in 07 §10a), staleness in fast control loops distorts strongly; the design rule "loops into one processor, BSP in between" delivers the T result at full pipeline throughput (H2 chain ×5.9, house ×6).
- 2026-10-12: **Explore the plant (P9.9, E0)** (`12-EXPLORATIVES-UI-INVENTAR.md`): fourth Studio view as a visual inventory on a map (wind farm), floor plan (house) and deck plan (boat) with level of detail, hover card and data sheet; inventory model `opentsx.inventory.v1` at the atom anchor, image provenance, references to standards, maintenance topic `opentsx.maintenance.v1`, infinimesh as the device layer; static prototype `docs/prototypes/anlagen-explorer.html` and sample inventory; D30 to D36 as proposals.
- 2026-10-12: **Cleanup**: collective PR #102 to `master`; SaaS dependencies bundled (Vite 7, python-multipart, python-socketio); obsolete Dependabot PRs closed; `opentsx-app-tools` archived; README repositioned ("from time series to decisions").
- 2026-10-12: **Smart Topic processor and DevOps concept** (`11-SMART-TOPIC-PROZESSOR-DEVOPS.md`): Go processor following the KafScale processor pattern with a YAML DAG, stream core in Go, sidecar for Java/Python, training outside the stream, lifecycle (CI replay, shadow operation, model swap, edge operation); D25 to D29 as proposals.
- 2026-10-12: **P9.8 simulated boat data**: generator `SimulatedLogs`, sample logs under `twin/beispiel/`, calibration against SoC, output power and solar (with irradiance), `bin/opentsx boat --simulated`.
- 2026-10-12: **P9.7 simulated utility bills**: sample data 2022 to 2025 with hidden parameters and realistic gaps, `--simulated`, calibration finds H/η and the appliance scale, reports hot water as not determined.
- 2026-10-12: **P9.8 boat twin** (`10-BOOT-ZWILLING.md`): Etap 22 as an island energy system with 5 new building blocks, wiring plan, demo `bin/opentsx boat` with what-if, CSV import of real data and calibration against measured SoC.
- 2026-10-12: **Operations concept and local probe run** (`docs/devops/operations.md`, `deploy/k3s/60-probe.sh`, `70-backup.sh`, `71-restore.sh`): probe run with a PASS/FAIL report, DWD (job + CronJob) and Modbus (simulator + poller) in the cluster, monitoring component, eight runbooks; checked with shellcheck, kubeconform (145 resources), a stub cluster and a real KafScale broker via the image entry point (4,300 `series-stats` records); a run on real k3s with Docker is still pending.
- 2026-10-12: **DevOps guide k3s + KafScale** (`docs/devops/`, `deploy/k3s/`, `deploy/docker/`): setup and sizing plan, all examples (wind farm → KafScale, statistics, retrieval, OpenMetadata, H2 chain, house, boat, Studio) as deployments/jobs with scripts 00 to 90; commands checked locally in the image, manifests validated with kubeconform; Docker build and k3s run not yet executed.
- 2026-10-12: **Studio prototype and LabVIEW concept** (`09-STUDIO-LABVIEW-STEUERPULT.md`): three views with twin data, LabVIEW/Node-RED/NiFi analogy, loop LabVIEW → MQTT → infinimesh → KafScale → OpenTSx → approval → `desired`, JSON contract for Edge Smart Metrics.
- 2026-10-12: **P9.7 house twin**: method transferred to homeowners (`08-HAUS-ZWILLING.md`), 9 house atoms, wiring plan `haus-erzgebirge.yaml`, key figures, calibration on utility bills with an identifiability report, demo `bin/opentsx house`; all house figures are example assumptions.
- 2026-10-12: **P9 PoC** `opentsx-twin`: atom contract MVC+P, wiring plan YAML, layered BSP runner (variant T) with topic contract, provenance ladder, 9 building blocks (including operating rules), shadow/what-if/forecast; S1 H2 chain tested, demo `bin/opentsx twin`.
- 2026-10-12: **P8 sharpened** (short title "Interplay and coordination potential", guiding question, evaluation principle, operating rules, methods as a proposal); infographic briefing version 2 with two views (What/How, Why/For whom).
- 2026-10-12: **Twin set as the core of the roadmap**: three modes in both variants (tightly coupled/one process and loosely coupled/Smart Topics, D20); comparison as a research paper (P9.6); wake graph export at low priority.
- 2026-10-12: **Phase 9 proposed** (digital twin of the energy system, concept `07-DIGITAL-TWIN-BSP.md`: atoms with MVC+P, wiring plan, layered BSP, Smart Topics as embedding, stages A to D); wake ground truth in P8.0 made more precise (simulation reference); points from the project meeting added to the backlog.
- 2026-10-12: **Phase 8 added** (system reconstruction → simulation → optimization, concept `06-SYSTEMREKONSTRUKTION-OPTIMIERUNG.md` from in-depth research with reference to Kämpf 2017); complete backlog of all open items.
- 2026-10-12: **Docs refresh**: new English user manual (`docs/user-manual/`), inventory (`docs/DOCUMENTATION-INVENTORY.md`), archiving of outdated docs and of modules outside the build (`archive/`), launcher `bin/opentsx`, link check in CI.
- 2026-10-12: **P5 implemented** (`opentsx-retrieval`): segmentation, pattern engine, retrieval API, REST + Python client, power curve analysis.
- 2026-10-12: Smart Topics designed (`05-SMART-TOPICS.md`); decisions: Python training first, MLflow, model per series + federated global model (Wayang), implementation as P7 after P6.
- 2026-10-11: **P4 implemented**: `opentsx-stats` (streaming statistics, quantile sketch, DQ, KafScale processor with checkpoints) and `opentsx-openmetadata` (metadata pool, validated against the OM 2.0.5 schemas); Python `opentsx.stats`.
- 2026-10-11: **Wind farm domain implemented** (`opentsx-windpark`): IEC 61400-25-2 data point catalog, normalizer, simulator with ground truth, CLI → KafScale/Iceberg. Avro 1.12.1 set centrally (precision bug in 1.12.0 covered by a test).
- 2026-10-11: **P3 implemented**: store SPI + contract suite, Iceberg store (+ index, log sync), OpenTSDB adapter, PyIceberg reader with byte equality Java↔Python.
- 2026-10-11: **P2 implemented**: `opentsx-kafka-v2` (TopicLayout, BucketWriter, episode index, replay, catalog), `LegacyBucketImport`; end-to-end tests green against KafScale v1.6.0 and Apache Kafka 3.9.1; storage costs measured.
- 2026-10-09: **P1 implemented**: `opentsx-model` (Java) + `opentsx.model` (Python) byte-identical via shared test vectors; decisions D2 (`timestamp-micros`) and D3 (xxHash64) confirmed; legacy bridge; schema history with compatibility check.
- 2026-10-09: **P0 implemented** (see phase 0). KafScale measurement: headers and CreateTime are preserved; `offsetsForTimes`, the idempotent producer, standalone commits and group rejoin do not work (causes in the conformance README).
- 2026-10-09: Gap analysis (build, tests, analytics, wind farm, KafScale v1.6.0, OpenMetadata 2.0.5) → `03-GAP-ANALYSE.md`; decisions D1/D4/D5/D7 incorporated.
- 2026-10-09: Analysis of the existing model, draft of the target model and phase plan created (this document + `docs/datamodel-2026/`).
