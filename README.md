# OpenTSx: where complex dependencies become tangible and decidable

[![CI](https://github.com/kamir/OpenTSx/actions/workflows/ci.yml/badge.svg)](https://github.com/kamir/OpenTSx/actions/workflows/ci.yml)
![Java](https://img.shields.io/badge/Java-17%20%7C%2021-orange.svg)
![Python](https://img.shields.io/badge/Python-3.10--3.13-blue.svg)
![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)

OpenTSx started as a toolkit for time-series computation. It is becoming more: **the place where the dependencies of
a real system become visible, testable and decidable.**

Wind farms, electrolyzers, storage, buyers, markets, grid rules, a family house or a sailing boat: in each of them
many parts react to each other, often with a delay and under constraints that belong to different people. Looking
at single time series rarely answers the questions that matter:

* What depends on what, and how long does the effect take?
* What do we lose by operating each part on its own, and what would coordination gain?
* What happens if I decide this, now, before I actually do it?

OpenTSx answers them in five steps, each building on the one before:

| Step | What it gives you | Building blocks |
|------|-------------------|-----------------|
| **1. Keep the facts** | every measurement, schedule and decision with time, unit and origin; exact replay | data model v2, KafScale storage and replay, Iceberg / OpenTSDB stores |
| **2. Understand the signals** | statistics, data quality, episodes, patterns, forecasts | streaming statistics, segmentation and pattern search, Smart Topics |
| **3. Make dependencies tangible** | which parts influence each other, with which delay, under which constraints; the coordination gap between separate and coordinated operation | P8 "interplay & coordination potential" (statistical dependence is treated as a hypothesis until checked against physics and balances) |
| **4. Make them decidable** | an executable digital twin in which decisions propagate causally: shadow mode, what-if, forecast | `opentsx-twin` (atoms with model, views, dynamics and an exchangeable policy; wiring plan; bulk-synchronous execution) |
| **5. Close the loop** | one view on the modelled system, approved commands back to the plant or the lab | Smart Topics Studio, command/feedback topic, edge publishers (e.g. LabVIEW, a Raspberry Pi on board) |

Principles that hold throughout:

* **Every number shows its origin:** measured, reported, forecast, estimated or synthetic.
* **Alternatives are compared fairly.** Each one is measured against the reference operation, with the same
  information.
* **Modelled is not proven.** A modelled potential and a proven saving stay separate.
* **Safety stays local.** Local safety logic always wins over a twin's suggestion.
* **Open and vendor-neutral.** Apache 2.0; Kafka-protocol logs and open table formats.

### Three systems, one set of building blocks

The same atoms and modes, wired differently. All three run today on synthetic or simulated data:

| System | What it shows | Try it |
|--------|---------------|--------|
| **Energy system** (wind farm, electrolyzer, H₂ line, storage, marketing) | the offtake closes a valve. Pressure rises, the electrolyzer shuts down, the schedule breaks, and the marketer must sell more. The what-if and the forecast show the countermeasures. | `bin/opentsx twin` |
| **Family house** in the Erzgebirge (oil and wood heating) | a reference year; levers such as −1 K, standby or a new freezer; calibration against bills | `bin/opentsx house --simulated` |
| **Sailing boat** Etap 22 (power station, solar, shore power, Starlink) | autonomy and unmet demand on a two-week trip; panel size, station size and Starlink usage as levers; calibration against logger data | `bin/opentsx boat --simulated` |

All three appear in the [Smart Topics Studio](docs/prototypes/smart-topics-studio.html) prototype. It shows
statistics, prediction and what-if, and the node graph of each system's wiring plan.

### Foundation

* **Data model v2.** Series identity is an xxHash64 of a canonical form. **Episodes** are the unit of exchange and
  storage. Java and Python produce byte-identical Avro records.
* **Storage and replay on [KafScale](https://kafscale.io).** Episodes are kept in buckets with checksums, and replay
  is verifiable. The design works within KafScale's limits (no compaction, no idempotent producer, no timestamp seek).
* **Stores behind one SPI.** Apache Iceberg (SQL, time travel, PyIceberg), OpenTSDB, and in-memory.
* **Wind-farm domain.** A versioned data-point catalog mapped to IEC 61400-25-2, a normalizer, a simulator with
  ground truth, and power-curve analysis.
* **Streaming statistics and data quality.** Mergeable sketches; profiles and DQ results are published to
  **OpenMetadata**.
* **Retrieval.** Segmentation, pattern search (MASS, SAX, DTW), a fluent Java API, REST, and a Python client.
* **Classic analytics.** DFA, MFDFA, event synchronization, return-interval statistics.

## Quick start

```bash
mvn -q install -DskipTests                          # JDK 17+
pip install -e "python-package[iceberg]"            # Python 3.10+
docker compose -f docker-compose.kafscale.yml up -d # KafScale + MinIO + etcd (optional)

bin/opentsx windpark-sim --scenario opentsx-windpark/scenarios/ostsee-demo.yaml \
    --iceberg type=jdbc,uri=jdbc:sqlite:/tmp/tsx/catalog.db,warehouse=file:/tmp/tsx/warehouse --namespace tsx
bin/opentsx retrieval-server --port 8080 \
    --iceberg type=jdbc,uri=jdbc:sqlite:/tmp/tsx/catalog.db,warehouse=file:/tmp/tsx/warehouse --namespace tsx
```

```python
from opentsx.client import RetrievalClient
df = RetrievalClient("http://localhost:8080").frame(metric="wind.met.wind_speed", tags={"turbine": "T07"}, limit=6)
```

The full walk-through, including KafScale, statistics and OpenMetadata, is in
[Getting started](docs/user-manual/02-getting-started.md).

## Documentation

| Document | Content |
|----------|---------|
| [User manual](docs/user-manual/README.md) | concepts, every module, Python, operations, migration, CLI and REST reference |
| [Data model v2 specification](docs/datamodel-2026/04-MODELL-V2-SPEC.md) (German) | normative spec |
| [Design documents](docs/datamodel-2026/) (German) | analysis, target model, gap analysis, Smart Topics (05), interplay & coordination potential (06), digital twin (07), house (08), Studio & LabVIEW (09), boat (10), Smart Topic processor & DevOps (11) |
| [Plan and progress](EVOLUTION/TASK-011-datamodel-refresher-2026.md) | phases, decisions, progress log |
| Module READMEs | details per module, e.g. [kafka-v2](opentsx-kafka-v2/README.md), [windpark](opentsx-windpark/README.md), [retrieval](opentsx-retrieval/README.md) |
| [Developer guide](docs/devguide/README.md) | PDF build, processing-flow descriptor schema, local infrastructure |
| [DevOps guide](docs/devops/README.md) | run OpenTSx on k3s against an existing KafScale cluster: setup plan, sizing plan, demos, scripts in `deploy/k3s/` |

## Modules

| Module | Purpose |
|--------|---------|
| `opentsx-model` | data model v2 (Avro, series identity, episodes, wire format) |
| `opentsx-kafka-v2` | episode storage, index and replay on KafScale/Kafka |
| `opentsx-store-spi`, `-store-iceberg`, `-store-opentsdb-v2` | store SPI and backends |
| `opentsx-windpark` | IEC 61400-25-2 catalog, normalizer, simulator, power curve |
| `opentsx-stats` | streaming statistics, data quality |
| `opentsx-openmetadata` | metadata pool in OpenMetadata 2.0 |
| `opentsx-retrieval` | segmentation, pattern search, retrieval API, REST |
| `opentsx-twin` | digital twin: atoms, wiring plan, bulk-synchronous runner, shadow / what-if / forecast; scenarios energy system, house, boat; Studio export |
| `opentsx-dwd` | DWD Open Data collector: MOSMIX forecasts and CDC observations as v2 series/episodes, nearest-station search, weather inputs for the twins |
| `opentsx-modbus` | Modbus TCP / RTU-over-TCP client (JDK only), register maps in YAML (type, word order, scaling, unit, OBIS, origin), block-merging poller, counter wrap/reset detection → v2 episodes; in-JVM meter simulator |
| `opentsx-legacy-bridge` | 2.x → v2 conversion |
| `opentsx-kafscale-conformance` | broker capability tests |
| `opentsx-data`, `-core`, `-connectors`, `-lg`, `-flink-core` | 2.x model and classic analysis (legacy, maintained) |
| `python-package/` | Python library `opentsx` |
| `opentsx-saas-backend/`, `-frontend/` | experimental web stack (planned personal service layer) |

Retired modules and the 2025 documentation are in [`archive/`](archive/).

## Status

| Phase | Content | Status |
|-------|---------|--------|
| P0–P5 | foundation, data model v2, KafScale storage, stores, wind farm, statistics, OpenMetadata, retrieval | **done** |
| P6 | analytics gaps (alignment, gap filling, change points, spectral analysis) | planned |
| P7 | Smart Topics (declarative model training, smart metrics, prediction API, federated models); personal service layer; Go stream processor with a declarative DAG ([11](docs/datamodel-2026/11-SMART-TOPIC-PROZESSOR-DEVOPS.md)) | designed; Studio prototype |
| P8 | Interplay & coordination potential of the local energy system: dependencies, constraints, reference vs. coordinated operation. Methods (proposal): system reconstruction from time series (Kämpf 2017 + causal discovery), simulation, optimization (classical; quantum as experiment) | concept ([06](docs/datamodel-2026/06-SYSTEMREKONSTRUKTION-OPTIMIERUNG.md)) |
| P9 | Digital twin: atoms (MVC + policy), wiring plan, bulk-synchronous execution (variant T), three modes; energy system, house ([08](docs/datamodel-2026/08-HAUS-ZWILLING.md)) and boat ([10](docs/datamodel-2026/10-BOOT-ZWILLING.md)) with calibration on simulated data | **proof of concept** ([07](docs/datamodel-2026/07-DIGITAL-TWIN-BSP.md)); loosely coupled variant L and real data next |

## Related work

* *Hadoop.TS*, the initial paper: <https://www.ijcaonline.org/archives/volume74/number17/12974-0233>
* Kämpf, Tessenow, Kenett, Kantelhardt: *The Detection of Emerging Trends Using Wikipedia Traffic Data and Context
  Networks*, PLOS ONE (2015): <https://journals.plos.org/plosone/article?id=10.1371/journal.pone.0141892>
* *Context Sensitive and Time Dependent Relevance of Wikipedia Articles*, DPG 2014:
  <https://www.slideshare.net/mirkokaempf/dpg-2014-time-05-1>
* *Comparing the usage of global and local Wikipedias with focus on Swedish Wikipedia*:
  <https://www.researchgate.net/publication/255704719_Comparing_the_usage_of_global_and_local_Wikipedias_with_focus_on_Swedish_Wikipedia>
* Old wiki: <https://github.com/kamir/Hadoop.TS.NG/wiki>

## History

| Years | Phase |
|-------|-------|
| 2013–2018 | Hadoop.TS: Hadoop, HBase/OpenTSDB, Spark; research on Wikipedia and financial time series |
| 2018–2020 | renamed to OpenTSx; Kafka, Kafka Streams |
| 2020–2025 | Confluent Platform/Cloud, ksqlDB, multi-region demos, SaaS prototype |
| 2026 | refresh: Java 17, data model v2, KafScale, Iceberg, OpenMetadata, wind-farm domain, retrieval, Python parity; digital twin and Studio: from time series to decisions |

## License

Apache License 2.0 (as declared in the build and the Python package). Copyright 2013–2026 the OpenTSx contributors.
