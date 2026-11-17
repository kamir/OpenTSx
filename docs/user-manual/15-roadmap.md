# 15. Status and roadmap

This chapter shows which phases of OpenTSx are built and which are planned. The plan is tracked in one task file
per topic under [`EVOLUTION/`](../../EVOLUTION/README.md). The roadmap rationale is in the
[gap analysis](../datamodel-2026/03-GAP-ANALYSE.md) (German). [Chapter 17](17-operators.md) gives the status of
every time-series operator per runtime.

| Phase | Content | Status |
|-------|---------|--------|
| P0 | Java 17, dependency cleanup, CI, archive legacy modules, KafScale conformance | done |
| P1 | Data model v2 (Java + Python, identical bytes) | done |
| P2 | Episode storage and replay on KafScale | done |
| P3 | Store SPI, Iceberg, OpenTSDB; wind-farm catalog, normalizer, simulator | done |
| P4 | Streaming statistics, data quality, OpenMetadata | done |
| P5 | Segmentation, pattern search, retrieval API, REST, Python client, power-curve analysis | done |
| P6 | Analytics gaps. Built: alignment of several series to one clock with as-of fill (`align.vector`), resampling (`resample`), the stream features `feature.lag` and `feature.rolling`. Planned: gap filling, change points, Welch PSD and coherence, feature extraction per episode | in progress ([TASK-018](../../EVOLUTION/TASK-018-analytics-storage-extensions.md)) |
| P7 | **Smart Topics**. Built: derived topics in the Java vector processor (`bin/opentsx vectors`): vector topics, clean series and features. Planned: declarative training (Python, MLflow), Java scoring (ONNX), smart metrics, a prediction API with or without context, federated global models | in progress ([TASK-016](../../EVOLUTION/TASK-016-smart-topics.md), [05-SMART-TOPICS](../datamodel-2026/05-SMART-TOPICS.md), German) |
| P7e | Personal service layer on the SaaS stack: control plane for one's own smart series. Built: web workbench, Flow Composer and the Systems shell (`/systems`) ([TASK-013](../../EVOLUTION/TASK-013-web-workbench.md), [TASK-014](../../EVOLUTION/TASK-014-engineering-flow.md)); the Smart Topics Studio prototype ([TASK-016](../../EVOLUTION/TASK-016-smart-topics.md)). Planned: the Studio in production use | experimental ([chapter 14](14-saas-service-layer.md)) |
| P8 | **Interplay and coordination potential.** What does separate operation cost, and what does coordinated operation gain? Coordination potential = result of a coordinated alternative minus the reference operation, under the same constraints and information. Proposed methods: P8.0 edge table + ground-truth benchmark from the simulator; P8.1 Kämpf significance layer and event coincidence analysis (Java, streaming screen); P8.2 causal confirmation (PCMCI+, multivariate transfer entropy, Hawkes; Python); P8.3 simulation on the reconstructed structure; P8.4 classical optimization (MILP/QUBO); P8.5 quantum backends as a measured experiment, federated structure discovery | in progress: pair windows with `pair.coverage`, `pair.xcorr` and `pair.es` in the workbench ([TASK-015](../../EVOLUTION/TASK-015-multivariate-analysis.md), phases 1a to 1d); P8.0 to P8.5 are a concept ([06-SYSTEMREKONSTRUKTION-OPTIMIERUNG](../datamodel-2026/06-SYSTEMREKONSTRUKTION-OPTIMIERUNG.md), German) |
| P9 | **Digital twin of an energy system, the core of the roadmap.** Three modes (shadow, what-if, forecast/MPC), each in two execution variants: tightly coupled in one process and loosely coupled over Smart Topics; comparing the two is a research question (P9.6). Scope: wind farm, electrolyzer, H2 line, storage, self-consumption, marketing. Stage A: data access (Modbus, IEC 60870-5-104, DWD, market and marketer data) and a potential estimate without simulation; then atoms with model/view/controller and a separate policy (MVC+P), a wiring plan per site, layered bulk-synchronous execution, Smart Topics as learned sub-models; shadow, what-if and MPC modes | in progress: in `opentsx-twin` variant T (`TwinRunner`) with shadow, what-if and forecast, and variant L (`LooseRunner`); Modbus (`opentsx-modbus`), DWD (`opentsx-dwd`) and public market data (`opentsx-market`) of stage A. Open: IEC 60870-5-104, marketer data, the three modes in variant L, variant L on KafScale topics ([TASK-017](../../EVOLUTION/TASK-017-digital-twin.md), [07-DIGITAL-TWIN-BSP](../datamodel-2026/07-DIGITAL-TWIN-BSP.md), German) |

Open items are tracked in one task per topic:
[TASK-013](../../EVOLUTION/TASK-013-web-workbench.md) (web workbench, P7e),
[TASK-014](../../EVOLUTION/TASK-014-engineering-flow.md) (engineering method, P7e),
[TASK-015](../../EVOLUTION/TASK-015-multivariate-analysis.md) (multivariate analysis, P8),
[TASK-016](../../EVOLUTION/TASK-016-smart-topics.md) (Smart Topics, P7),
[TASK-017](../../EVOLUTION/TASK-017-digital-twin.md) (digital twin, P9),
[TASK-018](../../EVOLUTION/TASK-018-analytics-storage-extensions.md) (P5 options, P6, storage and data sources),
[TASK-019](../../EVOLUTION/TASK-019-major-upgrades.md) (major upgrades) and
[TASK-010](../../EVOLUTION/TASK-010.md) (repository items and the credential rotation). TASK-011 is completed
([closing](../datamodel-2026/18-CLOSING-TASK-011.md), section 9). The main open items:

* the pair methods `pair.scatter` and `pair.granger`, all pairs with the edge table for P8.0, and the pair methods
  in the processor (TASK-015, phases 1e to 3),
* the stages E1 to E3 of the engineering method: decision card and contracts, one streaming and replay path with
  two variants of `clean`, the loop of action and learning ([chapter 16](16-engineering-method.md), TASK-014),
* Avro 1.12.2+ (`SERIALIZABLE_PACKAGES`) (TASK-019),
* real vendor exports in the catalog (TASK-018),
* the Python package on PyPI (TASK-010).
