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
| P7e | Personal service layer on the SaaS stack: control plane for one's own smart series ([chapter 14](14-saas-service-layer.md)) | proposal |
| P8 | **Interplay and coordination potential** — what does separate operation cost, and what does coordinated operation gain? Coordination potential = result of a coordinated alternative minus the reference operation, under the same constraints and information. Proposed methods: P8.0 edge table + ground-truth benchmark from the simulator; P8.1 Kämpf significance layer and event coincidence analysis (Java, streaming screen); P8.2 causal confirmation (PCMCI+, multivariate transfer entropy, Hawkes; Python); P8.3 simulation on the reconstructed structure; P8.4 classical optimization (MILP/QUBO); P8.5 quantum backends as a measured experiment, federated structure discovery | concept ([06-SYSTEMREKONSTRUKTION-OPTIMIERUNG](../datamodel-2026/06-SYSTEMREKONSTRUKTION-OPTIMIERUNG.md), German) |
| P9 | **Digital twin of an energy system — the core of the roadmap.** Three modes (shadow, what-if, forecast/MPC), each in two execution variants: tightly coupled in one process and loosely coupled over Smart Topics; comparing the two is a research question (P9.6). Scope: (wind farm, electrolyzer, H2 line, storage, self-consumption, marketing). Stage A: data access (Modbus, IEC 60870-5-104, DWD, market and marketer data) and a potential estimate without simulation; then atoms with model/view/controller and a separate policy (MVC+P), a wiring plan per site, layered bulk-synchronous execution, Smart Topics as learned sub-models; shadow, what-if and MPC modes | proposal; proof of concept of variant T in `opentsx-twin` ([07-DIGITAL-TWIN-BSP](../datamodel-2026/07-DIGITAL-TWIN-BSP.md), German) |

The complete backlog of open items is maintained in TASK-011 ("Offene Punkte / Backlog"). It includes the
credential rotation and the research follow-ups. The main items:

* a Flink adapter for the statistics engine (with the Flink upgrade),
* a smoke test against a real OpenMetadata server,
* Avro 1.12.2+ (`SERIALIZABLE_PACKAGES`),
* real vendor exports in the catalog,
* the event-synchronization normalization fix ([chapter 11](11-classic-analytics.md)),
* the repository files LICENSE, CONTRIBUTING, CODE_OF_CONDUCT and CHANGELOG.
