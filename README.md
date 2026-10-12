# OpenTSx: time-series analysis on event-streaming infrastructure

[![CI](https://github.com/kamir/OpenTSx/actions/workflows/ci.yml/badge.svg)](https://github.com/kamir/OpenTSx/actions/workflows/ci.yml)
![Java](https://img.shields.io/badge/Java-17%20%7C%2021-orange.svg)
![Python](https://img.shields.io/badge/Python-3.10--3.13-blue.svg)
![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)

OpenTSx is an open-source toolkit (Java 17 and Python) for capturing, storing, replaying and analysing time series.
It is built on Kafka-protocol logs and open table formats:

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
| [Design documents](docs/datamodel-2026/) (German) | analysis, target model, gap analysis, Smart Topics, system reconstruction & optimization |
| [Plan and progress](EVOLUTION/TASK-011-datamodel-refresher-2026.md) | phases, decisions, progress log |
| Module READMEs | details per module, e.g. [kafka-v2](opentsx-kafka-v2/README.md), [windpark](opentsx-windpark/README.md), [retrieval](opentsx-retrieval/README.md) |
| [Developer guide](docs/devguide/README.md) | PDF build, processing-flow descriptor schema, local infrastructure |

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
| `opentsx-legacy-bridge` | 2.x → v2 conversion |
| `opentsx-kafscale-conformance` | broker capability tests |
| `opentsx-data`, `-core`, `-connectors`, `-lg`, `-flink-core` | 2.x model and classic analysis (legacy, maintained) |
| `opentsx-app-tools` | network utilities |
| `python-package/` | Python library `opentsx` |
| `opentsx-saas-backend/`, `-frontend/` | experimental web stack (planned personal service layer) |

Retired modules and the 2025 documentation are in [`archive/`](archive/).

## Status

| Phase | Content | Status |
|-------|---------|--------|
| P0–P5 | foundation, data model v2, KafScale storage, stores, wind farm, statistics, OpenMetadata, retrieval | **done** |
| P6 | analytics gaps (alignment, gap filling, change points, spectral analysis) | planned |
| P7 | Smart Topics (declarative model training, smart metrics, prediction API, federated models); personal service layer | designed |
| P8 | System reconstruction from time series (Kämpf 2017 + causal discovery), simulation, optimization (classical; quantum as experiment) | concept ([06](docs/datamodel-2026/06-SYSTEMREKONSTRUKTION-OPTIMIERUNG.md)) |
| P9 | Digital twin of an energy system: atoms (MVC + policy), wiring plan, bulk-synchronous execution, Smart Topics as learned sub-models | proposal ([07](docs/datamodel-2026/07-DIGITAL-TWIN-BSP.md)) |

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
| 2026 | refresh: Java 17, data model v2, KafScale, Iceberg, OpenMetadata, wind-farm domain, retrieval, Python parity |

## License

Apache License 2.0 (as declared in the build and the Python package). Copyright 2013–2026 the OpenTSx contributors.
