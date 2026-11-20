# 15. Status and roadmap

This chapter lists the phases and their status.

| Phase | Content | Status |
|-------|---------|--------|
| P0 | Java 17, dependency cleanup, CI, archive legacy modules, KafScale conformance | done |
| P1 | Data model v2 (Java + Python, identical bytes) | done |
| P2 | Episode storage and replay on KafScale | done |
| P3 | Store SPI, Iceberg, OpenTSDB; wind-farm catalog, normalizer, simulator | done |
| P4 | Streaming statistics, data quality, OpenMetadata | done |
| P5 | Segmentation, pattern search, retrieval API, REST, Python client, power-curve analysis | done |
| P6 | Analytics gaps: multi-series alignment and as-of joins, gap filling, change points, Welch PSD/coherence, feature extraction | planned |
| P7 | **Smart Topics**: derived topics with declarative training (Python, MLflow), Java scoring (ONNX), smart metrics, prediction API with or without context, federated global models | designed (05-SMART-TOPICS) |
| P7e | Personal service layer on the SaaS stack: control plane for one's own smart series ([chapter 14](14-saas-service-layer.md)) | proposal |
| P8 | **Interplay and coordination potential** — what does separate operation cost, and what does coordinated operation gain? Coordination potential = result of a coordinated alternative minus the reference operation, under the same constraints and information. Proposed methods: P8.0 edge table + ground-truth benchmark from the simulator; P8.1 Kämpf significance layer and event coincidence analysis (Java, streaming screen); P8.2 causal confirmation (PCMCI+, multivariate transfer entropy, Hawkes; Python); P8.3 simulation on the reconstructed structure; P8.4 classical optimization (MILP/QUBO); P8.5 quantum backends as a measured experiment, federated structure discovery | concept (06-SYSTEMREKONSTRUKTION-OPTIMIERUNG, German) |
| P9 | **Digital twin of an energy system — the core of the roadmap.** Three modes (shadow, what-if, forecast/MPC), each in two execution variants: tightly coupled in one process and loosely coupled over Smart Topics; comparing the two is a research question (P9.6). Scope: (wind farm, electrolyzer, H2 line, storage, self-consumption, marketing). Stage A: data access (Modbus, IEC 60870-5-104, DWD, market and marketer data) and a potential estimate without simulation; then atoms with model/view/controller and a separate policy (MVC+P), a wiring plan per site, layered bulk-synchronous execution, Smart Topics as learned sub-models; shadow, what-if and MPC modes | proposal; proof of concept of variant T in `opentsx-twin` ([07-DIGITAL-TWIN-BSP](../datamodel-2026/TWIN-COUPLING-SPEC.md), German) |

On 2026-10-26 the open items of TASK-011 moved into one task per topic:
[TASK-015](../datamodel-2026/PAIR-WINDOWS-SPEC.md) (multivariate analysis, P8),
TASK-016 (Smart Topics, P7),
TASK-017 (digital twin, P9),
TASK-018 (P5 options, P6, storage and data sources),
TASK-019 (major upgrades) and
TASK-010 (repository items and the credential rotation). TASK-011 itself was completed on
2026-10-26 (closing, section 9). The main open items:

* pair windows for cross-correlation and event synchronization in the workbench ([PAIR-WINDOWS-SPEC](../datamodel-2026/PAIR-WINDOWS-SPEC.md)),
* Avro 1.12.2+ (`SERIALIZABLE_PACKAGES`) (TASK-019),
* real vendor exports in the catalog (TASK-018),
* the Python package on PyPI (TASK-010).
