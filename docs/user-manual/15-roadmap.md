# 15. Status and roadmap

The detailed plan, with its decision log and progress, is in
[`EVOLUTION/TASK-011-datamodel-refresher-2026.md`](../../EVOLUTION/TASK-011-datamodel-refresher-2026.md). The
roadmap rationale is in the [gap analysis](../datamodel-2026/03-GAP-ANALYSE.md).

| Phase | Content | Status |
|-------|---------|--------|
| P0 | Java 17, dependency cleanup, CI, archive legacy modules, KafScale conformance | done |
| P1 | Data model v2 (Java + Python, identical bytes) | done |
| P2 | Episode storage and replay on KafScale | done |
| P3 | Store SPI, Iceberg, OpenTSDB; wind-farm catalog, normalizer, simulator | done |
| P4 | Streaming statistics, data quality, OpenMetadata | done |
| P5 | Segmentation, pattern search, retrieval API, REST, Python client, power-curve analysis | done |
| P6 | Analytics gaps: multi-series alignment and as-of joins, gap filling, change points, Welch PSD/coherence, feature extraction | planned |
| P7 | **Smart Topics**: derived topics with declarative training (Python, MLflow), Java scoring (ONNX), smart metrics, prediction API with or without context, federated global models | designed ([05-SMART-TOPICS](../datamodel-2026/05-SMART-TOPICS.md)) |
| P7+ | Personal service layer on the SaaS stack ([chapter 14](14-saas-service-layer.md)) | proposal |

Open items:

* a Flink adapter for the statistics engine (with the Flink upgrade),
* a smoke test against a real OpenMetadata server,
* Avro 1.12.2+ (`SERIALIZABLE_PACKAGES`),
* real vendor exports in the catalog,
* the event-synchronization normalization fix ([chapter 11](11-classic-analytics.md)),
* the repository files LICENSE, CONTRIBUTING, CODE_OF_CONDUCT and CHANGELOG.
