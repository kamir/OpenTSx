# OpenTSx gap analysis: the way to a top time series toolkit for wind farm operation

> As of 2026-10-09. Branch `opentsx/2026-datamodel-refresher`.
> Basis: code inspection, local build (JDK 21 / Maven 3.9), Python test run, research on
> KafScale v1.6.0 (github.com/KafScale/platform @ 7aa2a11) and OpenMetadata 2.0.5.

## 0. Short conclusion

OpenTSx has a **scientifically valuable algorithm core** (DFA/MFDFA, RIS, event synchronization, FFT surrogates, LRC generators) and a **clean small Python base**. For the goal, *to capture, inventory, normalize, simulate, catalog and analyze wind farm time series over KafScale*, almost the whole **data platform layer** is missing: no reliable data model, no asset or inventory semantics, no normalization, no tests or CI, no persistence that works with KafScale, no catalog connection, no simulation. The build carries a monolithic root POM with about 390 transitive artifacts (Hadoop CDH 5, Spark 2.10, log4j 1.x, xstream 1.2), and the repository holds demo credentials (not critical according to the maintainer).

Recommendation: **do not rebuild the existing code; build a lean "OpenTSx Platform Core" (Java 17 and Python 3.10 or later) next to it** that uses the algorithm core through a bridge, and move the existing code step by step into `legacy/` or `archive/`.

---

## 1. Security and hygiene

| # | Finding | Location (path only) | Action |
|---|---------|----------------------|--------|
| S1 | Confluent Cloud configuration checked in although `.gitignore` excludes the path; according to the maintainer **demo data, not critical** | `config/private/ccloud.props` | Hygiene: replace with `*.example` so that scanners and contributors are not misled |
| S2 | More SASL configurations | `opentsx-lg/src/main/docker-compose/config/ccloud.props`, `opentsx-lg/src/main/resources/config/ccloud.props` | check, rotate, replace with `*.example` |
| S3 | MQTT password file | `opentsx-lg/src/main/mqtt-example/volume1/docker/mqtt/config/passwd` | replace with an example |
| S4 | Development default secrets in the SaaS backend | `opentsx-saas-backend/app/core/config.py` | only through the environment, no defaults |
| S5 | Known CVEs on the class path: log4j 1.2.17, xstream 1.2.2, jackson-databind 2.8.1 | root `pom.xml` | done with the POM cleanup (section 5) |

---

## 2. What is good (keep and improve): [GOOD]

| Area | Why it is good | Further use |
|------|----------------|-------------|
| Algorithm core (Java `algorithms/*`, Python `opentsx/algorithms`) | DFA/MFDFA, RIS, event synchronization, FFT phase randomization, entropy, Granger, peak detection; Python DFA verified (white noise α≈0.52, random walk α≈1.52) | Apply to v2 episodes through the legacy bridge; Python as the reference implementation |
| LRC and surrogate generators (`LongTermCorrelationSeriesGenerator`, sine and bucket generators) | 1/f^β noise ≈ the turbulence share of wind speed | Building block of the wind farm simulator |
| `opentsx-flink-core` | Flink 1.18, event time, JUnit 5 tests green (21 tests), processing flow descriptor (JSON schema) | Basis for stats and normalization jobs; replace the serde |
| Python package | about 2.3k lines, clean, `pyproject.toml`, `TSProcessor` chaining with `>>` | Becomes the Jupyter, pandas and PySpark side of the Platform Core |
| `ConfigManager` | Layered configuration plus environment overrides | Take over for all new modules |
| `opentsx-lg` latency and load tooling plus Grafana/Prometheus | Fits KafScale benchmarking | KafScale throughput and latency tests |
| `sensor_data.csv` (long format `timestamp,sensor_id,location,measurement_type,value,unit`), MQTT generator configuration | The right shape for inventory and normalization | Template for the wind farm channel model |
| The idea of the **episode as an array payload**, `TSData(t0, dt, values)`, `MarkableTimeSeriesObject` | The right basic ideas for cheap storage and annotation | Carried over into the v2 model (see 02-ZIELMODELL) |
| Onboarding and exercise structure | A good teaching basis | Reuse after the test fix |

---

## 3. What is bad (clean up or replace): [BAD]

### 3.1 Build and dependencies
* **The root POM declares about 60 dependencies in `<dependencies>` instead of `<dependencyManagement>`**, so every module inherits Hadoop CDH 5.11, HBase 1.2, cassandra-all, Flume, Kudu, Spark 2.2 (Scala 2.10), Mahout 0.9, TensorFlow 1.15. `opentsx-core` pulls in **388 artifacts**.
* 4 SLF4J bindings plus `log4j-over-slf4j` *and* `log4j` at the same time, so logging is not deterministic.
* Kafka clients 2.3 against Confluent 7.3; the property `confluent.version=7.3.0-css` does not exist; Gson twice; `maven-compiler-plugin 2.3.2` without `--release` (Java 9+ APIs slip into "Java 8" code).
* **11 modules outside the reactor, 8 of them not buildable** (Nashorn import, http repositories, missing artifacts, wrong versions).

### 3.2 Quality assurance
* **No CI** (no `.github/`).
* `opentsx-core` (234 classes, about 52k lines): **0 tests**, no JUnit dependency, instead **123 `main()` methods**.
* `test-report.txt` empty; `bin/run_all_tests.sh` looks for `opentsx-core-2.3-SNAPSHOT.jar` (the project is 3.0.0).
* Python tests "green", but without assertions (the tests return `bool`): **false security**.

### 3.3 Architecture and code
* `TimeSeriesObject` as a 2900-line god class; Swing/AWT in 48 core files (GUI in the library core).
* Uncontrolled logging: 2186 times `System.out`, 182 times `printStackTrace`, JUL, slf4j, log4j2.
* Serialization bugs (label `"123"`, quadratic timestamps, Avro and JSON on the same topic, Flink wire format break): see 01-ANALYSE section 3.
* `TSBucketStore`/`TSO*Interface` empty stubs; `TSOWriter.persistBucket_Kafka` "NOT YET DONE".
* Python `from_pandas` loses the time zone, and the time unit depends on the pandas version; `TSBucket.apply(parallel=True)` is sequential.

### 3.4 Repository hygiene and docs
* About 190 MB of jars and tarballs under `modules/ingest-2-cassandra`, 40 MB PPTX, a 10 MB binary in `opentsx-clusters/...`, 22 `.DS_Store`, 18 `.idea` files.
* Empty or dead modules: `opentsx-processors`, `opentsx-cv`, `geoqb`, `opentsx-jetson-nano`; duplicate demo folders, duplicate docs, `opentsx-ksql` ≙ `opentsx-ksql-udf`.
* Three compose files with three Confluent versions, `latest` tags; a Spark notebook image without PySpark code.
* **The README promises more than exists** (Smile ML, Parquet, "pluggable ML", Kafka in Python).
* Hard-coded paths `<home>` (personal home directories), `/media/esata/...`.

---

## 4. What is missing: [MISSING]

### 4.1 Data platform

| Capability | Status | Needed for |
|------------|--------|------------|
| Versioned data model (SeriesKey/Episode/Bucket) | missing (draft in 02) | everything |
| **Asset and channel inventory** (park → turbine → component → channel) | missing completely | inventory, catalog |
| **Normalization** (channel names, units, time zones, sampling, quality flags) | missing (no ZoneId and no unit handling) | clean capture |
| Resampling and alignment of irregular series, interpolation, gap handling | missing (only block means and binning) | SCADA 10-minute aggregates, multivariate analysis |
| Episode segmentation (duration, length, **feature based**) | missing | retrieval, pattern matching |
| Persistence that works with KafScale, plus replay and episode index | missing | storage, experiments |
| Iceberg tables | missing | PySpark, OpenMetadata profiles |
| OpenMetadata publisher | missing | metadata pool |
| Streaming statistics and data quality checks | missing (Flink only collects points) | profiles, DQ |
| Python: Avro, Kafka, Iceberg, PySpark | missing (extras declared, unused) | Jupyter, pandas and PySpark users |

### 4.2 Analytics (compared with a top tool)

| Present | Missing (priority for the wind farm) |
|---------|--------------------------------------|
| DFA/MFDFA, RIS, event synchronization, FFT, entropy, Granger, normalization, peak detection | **High:** resampling and alignment, gap filling, robust outlier and anomaly detection, change point detection, **power curve analysis (IEC 61400-12)**, Welch PSD and coherence (vibrations), cross-correlation, feature extraction (in the style of tsfresh), similarity search (MASS/SAX/DTW) |
| | **Medium:** STL and seasonal decomposition, matrix profile (motifs and discords), clustering of episodes, Kalman and state space |
| | **Later:** forecasting (`SingleTsARIMATool` commented out, `opentsx-predict` = a TF hello world) |

### 4.3 Wind farm domain: entirely new

* **Asset model** based on IEC 61400-25 (logical nodes `WTUR`, `WROT`, `WTRM`, `WGEN`, `WNAC`, `WYAW`, `WMET`, `WCNV` ...): `Park → Turbine → LogicalNode → Channel`.
* **Channel catalog**: canonical name, unit (UCUM), sampling rate, signal type (analog, counter, status, alarm), aggregation kind (10-minute mean, min, max, std as in SCADA practice), plausibility limits.
* **Simulator** (missing completely): wind field (Weibull mean plus turbulence from the existing LRC generator plus daily cycle), power curve, a simple wake model (Jensen), yaw and pitch, availability, alarms and curtailment, **sensor fault injection** (stuck, drift, spikes, gaps, time offset) as ground truth for DQ and pattern tests.
* **Experiment control**: scenario definition (YAML) → simulator → KafScale bucket → replay in analyses; reproducible through seed and manifest.

> As of 2026-10-11: implemented in [`opentsx-windpark`](../../opentsx-windpark/README.md) (catalog, normalizer, simulator, CLI). Open: time offset faults, daily cycle, more logical nodes (`WTRM`, `WYAW`, `WCNV`).

---

## 5. KafScale: consequences for the design (researched and measured, v1.6.0)

> **Measured 2026-10-09** with `opentsx-kafscale-conformance` against KafScale v1.6.0 and Apache Kafka 3.9.1.
> Results and causes: [`opentsx-kafscale-conformance/README.md`](../../opentsx-kafscale-conformance/README.md).

| KafScale property | Effect | Design answer |
|-------------------|--------|---------------|
| **No log compaction** | The topics `series.v2`, `buckets.v2`, `series-stats.v2` cannot be "latest by key" | State ("the latest version") lives in **Iceberg** (and OpenMetadata); Kafka topics are pure append logs; small topics can be read by a full scan plus dedup |
| **ListOffsets ignores timestamps**: for everything except *earliest* it returns the end (`cmd/broker/main.go:1696-1704`); `offsetsForTimes()`, Flink `OffsetsInitializer.timestamp()` and Spark `startingTimestamp` silently jump to the end | Time-based replay through the Kafka API is impossible | **An episode index of our own**: an indexer consumer writes `(episodeId, seriesId, tStart, tEnd, bucketId, topic, partition, offset)` to Iceberg `tsx.episode_index`; replay = index query → `seek(partition, offset)` |
| **No idempotent producer, no transactions** (`enable.idempotence=false` is required; the Java 3.x default is `true`) | At least once, duplicates possible | `episodeId` (ULID) as the dedup key; Iceberg writes by MERGE or dedup; the manifest checksum checks completeness |
| Record headers and CreateTime: **measured, they are kept** | Headers and timestamps can be used (for example `tsx-bucket`, record timestamp = `tStart`) | Still, everything that matters also goes **into the payload**, so that Iceberg and file paths do without Kafka metadata; the conformance suite watches this for each release |
| **Consumer groups: rejoining breaks** (measured): `LeaveGroup` v4 is ignored, `JoinGroup` answers new members with `REBALANCE_IN_PROGRESS` instead of `MEMBER_ID_REQUIRED`, so the Java client rejoins in an endless loop and the group never becomes stable | Restarting a group consumer hangs | Replay and Flink through **manual assignment plus seek**; group consumers only with a fresh `group.id` per run; upstream issue |
| **Offset commit without group membership is refused** (measured, `CommitFailedException`) | Flink and assign commits fail | Positions in OpenTSx (episode index or job state) instead of Kafka commits; upstream issue |
| No schema registry | Confluent wire format only with a registry of our own | **Default `SELF_DESCRIBING`** (Avro single-object encoding plus fingerprint): no infrastructure needed; a registry is optional |
| Compression none, snappy, lz4, zstd | supported | `zstd` per topic |
| Segments 4 MB / 500 ms on S3; low volume means many small objects | Cost through small objects | Episodes instead of points, producer batching (`linger.ms`), live point topics only where needed |
| Latency target 200 to 500 ms, moderate throughput | No real-time control loop | Fine for monitoring and analysis; simulator with pace control |
| No SASL (planned for v2.0), ACL on `client.id` | Security through the network | Network segmentation or a TLS proxy; documentation |
| Iceberg processor add-on: reads segments directly from S3, but **only JSON schemas, no Avro** | Not usable directly | A processor of our own with an Avro decoder (Go skeleton exists) **or** a Flink Iceberg sink; decision after a benchmark |
| Dev and CI: docker-compose (etcd plus MinIO plus broker), broker binary with in-memory S3; **no Testcontainers module**; the default image points to a private registry | CI needs a setup of its own | **Implemented:** `docker-compose.kafscale.yml` (pinned images); CI builds the broker v1.6.0 from source and runs the conformance suite (`.github/workflows/ci.yml`) |

---

## 6. OpenMetadata: consequences (researched, 2.0.5)

* Version **2.0.5** (server, Python SDK `openmetadata-ingestion==2.0.5.0`, Java client `org.open-metadata:openmetadata-java-client:2.0.5`): **pin client and server to the same version**. The Python SDK needs **Python 3.10 or later**.
* Quickstart: MySQL or Postgres plus Elasticsearch or OpenSearch plus the server (plus Airflow ingestion as an option, deprecated as the internal orchestrator in 2.1): 6 GB RAM or more. For us **push through the SDK or REST** is enough; Airflow is not needed.
* **No IoT, sensor or time series entity**, so the wind farm inventory is mapped onto:
  * a **glossary** "Wind farm" (IEC 61400-25 terms: logical nodes, channel types),
  * **classifications and tags** (park, turbine type, channel type, quality level),
  * **custom properties** (extension) on table and topic (turbine id, manufacturer, hub height, coordinates, sampling rate, unit),
  * a **domain and data product** "Wind farm <name>".
* **Profiles exist only for tables** (not topics), so statistics go to **Iceberg tables** (for example `tsx.channels_10min` or `tsx.episodes`) through `PUT /v1/tables/{id}/tableProfile` (plus custom metrics); granularity: aggregated per table or channel type and time window, not per event.
* **Data quality**: test definitions of our own (for example *stuck sensor*, *gap rate*, *plausibility band*, *power curve deviation*) → `POST /v1/dataQuality/testCases/testCaseResults/{fqn}` from the streaming processor.
* **Topics** without a registry: push `messageSchema.schemaText` plus `schemaType=Avro` ourselves.
* **The Iceberg connector was removed in 1.13**, so register tables through the SDK (CustomDatabase service) or through the Trino connector.
* **Lineage** `PUT /v1/lineage`: simulator or collector → topic → Flink pipeline → Iceberg table.
* A Flink pipeline connector exists (tested up to Flink 1.19); the docs contradict each other on its maturity, so register pipelines ourselves at first.

---

## 7. Episode segmentation (requirement from the review)

Three families of strategies, with equal standing in the model (`Episode.segmentation`):

| Strategy | Parameters | Wind farm example |
|----------|------------|-------------------|
| `FIXED_DURATION` | `duration`, `alignment` (for example on full 10 min or 1 h) | 10-minute SCADA blocks, daily episodes |
| `FIXED_COUNT` | `n`, `overlap` | 4096 samples for FFT or PSD of vibration sensors |
| `FEATURE_EXTREMUM` | `feature` (MIN, MAX, local extremum, prominence), `pre`, `post` (duration or count) | Windows around power peaks, gusts, torque peaks |
| `FEATURE_THRESHOLD` | `enter` (threshold and direction), `exit` (threshold and direction, hysteresis), `pre`, `post`, `maxDuration` | Strong wind above 20 m/s until below 18 m/s, curtailment phases, vibration alarms |

Schema addition (to 02-ZIELMODELL):

```avro
enum SegmentationStrategy { FIXED_DURATION, FIXED_COUNT, FEATURE_EXTREMUM, FEATURE_THRESHOLD, MANUAL }
record Segmentation {
  SegmentationStrategy strategy;
  map<string> params = {};                    // for example {"pre":"PT5M","post":"PT10M","feature":"MAX"}
  union{null,timestamp_micros} anchorTs = null; // time of the extremum or of the threshold crossing
  union{null,string} sourceEpisodeId = null;    // the base episode the segment comes from
}
```

The segmenters run the same way in **batch** (Python or Java on Iceberg) and in **streaming** (a Flink ProcessFunction with a buffer for `pre` and a timer for `post`). Feature episodes refer to base episodes, so lineage is kept.

---

## 8. Python compatibility (Jupyter/pandas and PySpark)

| User group | Access | To be provided |
|------------|--------|----------------|
| Jupyter/pandas | `opentsx.io.episodes(...)` → `pandas.DataFrame` (long format: `series_id`, tz-aware UTC `ts`, `value`, `quality`) plus a data frame of series and asset metadata; replay and write to KafScale | Python module `opentsx.model` from **the same `.avsc`** (fastavro), single-object encoding, `confluent-kafka` with `enable.idempotence=false`, a PyIceberg reader |
| PySpark | Iceberg tables directly (`spark.table("tsx.episodes")`), `explode(values)` → points; UDFs for segmentation and features (pandas UDF) | Spark helper `opentsx.spark` (schema constants, explode and resample functions), example notebooks |
| Both | Same results as Java | **Shared test vectors** (SeriesId hash, encodings, segmenters, statistics) in `opentsx-model/testvectors/` |

Baseline: **Python 3.10 or later** (OpenMetadata SDK), pandas 2.2 or later, pyarrow, fastavro, pyiceberg, confluent-kafka.

---

## 9. Target architecture "OpenTSx Platform Core" (wind farm)

```
 Simulator / Collector (SCADA, CSV, MQTT)
        │  Normalization (channel catalog, units, UTC, quality)
        ▼
 KafScale  tsx.wind.raw.v2 ── tsx.wind.episodes.v2 ── tsx.wind.events.v2 (alarms/status)
        │                     │
        │          Indexer ───┴──► Iceberg tsx.episode_index ◄── Replay service (seek per offset)
        ▼
 Flink:  Segmenter · Streaming stats (Welford/Sketch) · DQ checks · 10-min aggregation
        ▼
 Iceberg (REST catalog, S3/MinIO): assets · channels · episodes · channels_10min · series_stats · dq_results
        │                                         │
        ▼                                         ▼
 Retrieval API (Java/Python) + pattern engine     OpenMetadata 2.0.5 (glossary, tags, custom props,
 Jupyter/pandas · PySpark                          topics, tables, lineage, profile, DQ results)
```

---

## 10. Prioritized roadmap (replaces the phase order in TASK-011)

| Phase | Content | Result |
|-------|---------|--------|
| **P0 Hygiene and foundation** (1 to 2 weeks) | Replace demo credentials with `*.example`; new modules with `maven.compiler.release=17`, CI matrix JDK 17 and 21; `dependencyManagement` cleanup for new modules (the existing code stays isolated); CI (GitHub Actions: Java and Python); real Python asserts; legacy code to `archive/` | green CI, lean build for new modules |
| **P1 Model v2 plus serde (Java and Python)** | `.avsc` including `Segmentation`, asset and channel schemas; single-object encoding; test vectors | identical round trips Java ⇄ Python |
| **P2 KafScale conformance and persistence** | `docker-compose.kafscale.yml`; conformance suite (produce and fetch, groups, zstd, headers, CreateTime, duplicates, ListOffsets behavior); BucketWriter, indexer, replay | episodes stored cheaply in KafScale, verifiable replay |
| **P3 Wind farm domain**, done (`opentsx-windpark`) | Asset and channel catalog (oriented on IEC 61400-25), normalizer, **simulator** with fault injection, scenario YAML | reproducible experiments that feed KafScale |
| **P4 Iceberg plus stats plus DQ plus OpenMetadata**, done (`opentsx-store-iceberg`, `opentsx-stats`, `opentsx-openmetadata`) | Iceberg tables; Flink stats, DQ and 10-minute aggregation; OM publisher (glossary, tags, custom props, topics, tables, lineage, profile, DQ) | a filled metadata pool |
| **P5 Segmentation, retrieval and pattern**, done (`opentsx-retrieval`) | Segmenters (4 strategies, batch and streaming), retrieval API, MASS/SAX/DTW, power curve analysis | episode search and pattern search |
| **P6 Analytics gaps** | Resampling and alignment, gap filling, change points, Welch PSD and coherence, feature extraction | top tool level for wind farm analyses |
| **P7 Smart Topics** ([05-SMART-TOPICS.md](05-SMART-TOPICS.md)) | Derived topics from raw, stats and context streams; declarative training routines (Python, MLflow), scoring in Java (ONNX), smart metrics, prediction API with and without context; a federated global model (the Wayang approach) | learning topics with forecasts and anomaly scores |
| **P8 System reconstruction, simulation and optimization** ([06-SYSTEMREKONSTRUKTION-OPTIMIERUNG.md](06-SYSTEMREKONSTRUKTION-OPTIMIERUNG.md)) | Edge table plus a ground truth benchmark from the simulator; Kämpf significance layer and ECA (Java, streaming screening); causal confirmation (PCMCI+, mTE, Hawkes, Python); simulation on the reconstructed structure; optimization (MILP, QUBO, classical), quantum only as an object of measurement; federated structure discovery | sources → structure → simulation → decision with provenance throughout |
| **P9 Digital twin of an energy system** ([07-DIGITAL-TWIN-BSP.md](07-DIGITAL-TWIN-BSP.md), proposal) | Data access (Modbus, IEC 104, DWD, market and marketer) and potential estimate without simulation; atom contract MVC+P, wiring plan, layered BSP; atom catalog (turbine, wake, balance node, electrolyzer, H2 pipeline, storage, market, marketer, operator, optimizer); shadow, what-if, MPC | a plant model in which decisions take causal effect; transferable through the wiring plan |
