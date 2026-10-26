# Documentation inventory and refresh (2026-10-12)

This document records the 2026 refresh. Before it, the repository held about 180 documentation files across `docs/`
(three GitBook projects plus duplicates), `MANUALS/`, `EVOLUTION/`, module READMEs, notebooks and exercises. Almost
none of them described the current platform: the v2 data model, KafScale, Iceberg, the wind-farm domain,
statistics, OpenMetadata and retrieval. Many code examples did not compile, and many links were broken.

## 1. New

| Document | Content |
|----------|---------|
| [`docs/user-manual/`](user-manual/README.md) | **User manual** (English, 15 chapters and 3 appendices). Every command and example was checked against the code; the quick start was run end-to-end. |
| `opentsx-model`, `opentsx-core`, `opentsx-connectors`, `opentsx-legacy-bridge` READMEs (`opentsx-app-tools` was archived later) | the modules had no README before |
| [`bin/opentsx`](../bin/opentsx) | launcher for the current CLIs, used by the manual |
| [`tools/check_links.py`](../tools/check_links.py) | checks relative links in the maintained docs; runs in CI |
| `archive/docs/README.md`, `archive/demos/README.md`, `archive/bin/README.md` | archive indexes |
| this file | inventory |
| [`docs/datamodel-2026/06-SYSTEMREKONSTRUKTION-OPTIMIERUNG.md`](datamodel-2026/06-SYSTEMREKONSTRUKTION-OPTIMIERUNG.md) | concept P8: network reconstruction, simulation, optimization (concept map, research, roadmap) |
| [`docs/datamodel-2026/07-DIGITAL-TWIN-BSP.md`](datamodel-2026/07-DIGITAL-TWIN-BSP.md) | proposal P9: digital twin of an energy system (atoms, MVC + policy, BSP, Smart Topics) |
| [`docs/datamodel-2026/09-STUDIO-LABVIEW-STEUERPULT.md`](datamodel-2026/09-STUDIO-LABVIEW-STEUERPULT.md) | Smart Topics Studio (three views), LabVIEW analogy, control-desk loop via KafScale/infinimesh; prototype in `docs/prototypes/` |
| [`docs/datamodel-2026/11-SMART-TOPIC-PROZESSOR-DEVOPS.md`](datamodel-2026/11-SMART-TOPIC-PROZESSOR-DEVOPS.md) | proposal: Go Smart Topic processor (KafScale processor pattern), declarative DAG, DevOps lifecycle |
| [`docs/devops/`](devops/README.md) | DevOps guide (English, German summary): setup plan, sizing plan, demos, today vs. planned, troubleshooting for k3s + an existing KafScale cluster; scripts and manifests in [`deploy/k3s/`](../deploy/k3s/README.md), images in `deploy/docker/` |
| [`docs/roadmap/infographic-briefing.html`](roadmap/infographic-briefing.html) | briefing (German) for an infographic designer who visualizes the roadmap |
| [`docs/business/kampagnen-briefing.html`](business/kampagnen-briefing.html) | campaign briefing (German) as a page: vendor matrix, target groups, message house, claims, 90-day plan |
| [`docs/datamodel-2026/18-GRAPH-LAYERS.md`](datamodel-2026/18-GRAPH-LAYERS.md) | proposal (English): graph layers on KafScale and Iceberg, one node and edge table for wiring, inventory, measured dependencies, expert matrices, GeoQB context and architecture; sealed versions; serving to GIS and graph tools |
| [`docs/datamodel-2026/13-SEMANTIK-ALS-ZEITREIHE.html`](datamodel-2026/13-SEMANTIK-ALS-ZEITREIHE.html) | tutorial (German) as a page: seven method families that turn ER1 semantics into numeric series, the versioning rule they depend on, channel-set choice (PLM projects, lifecycle events, embedding clusters), bucket width, and the mapping onto `opentsx.vector.v1` |

## 2. Updated

| Document | Change |
|----------|--------|
| `README.md` (root) | rewritten. The old version claimed Java 8/Kafka 2.3, listed archived modules, had 29 outdated statements and ~10 broken links, and its code did not compile. The old version is in `archive/docs/README-2025.md`. |
| `docs/README.md`, `docs/devguide/README.md`, `SUMMARY.md`, `PDF-GENERATION.md` | point to the new manual; the devguide is reduced to contributor notes |
| `docs/devguide/infrastructure/local-development.md` | notes on the KafScale stack and fixed links |
| `docker-compose.local.yml` | `full` profile builds from `opentsx-saas-backend/` and `-frontend/` (it pointed to missing `./backend`, `./frontend`) |
| `python-package/README.md` | Python ≥ 3.10, local install, new subpackages (model, stats, store, client) |
| `bin/README.md`, `000_validate_environment.sh`, `010_build.sh`, `build_docs_pdfs.sh` | Java 17; obsolete scripts removed; PDF build uses the new manual |
| `opentsx-lg`, `opentsx-flink-core`, `opentsx-data` READMEs | legacy status, broken links fixed |
| `EVOLUTION/README.md`, `TASK-011` | current task index; implemented module structure |
| `exercises/README.md`, `tests/README.md` | marked as 2.x material |

## 3. Archived (moved, not deleted)

| From | To | Why |
|------|----|-----|
| `docs/manual/` (GitBook v1) | `archive/docs/manual-v1/` | `TimeSeriesObject`-only; 47 of 61 TOC links pointed to missing files; wrong RIS definition |
| `docs/onboarding/` | `archive/docs/onboarding-2025/` | tracks built on ksqlDB, Cassandra and Java 8 |
| `docs/devguide/guides/*`, FAQ, troubleshooting, reviews | `archive/docs/devguide-2025/` | wrong module list, invented APIs, Cassandra/Confluent Cloud deployment |
| SaaS docs (user guide, API, platform, flow builder, release summary) | `opentsx-saas-backend/docs/` | they belong to the SaaS stack |
| `docs/00_MATERIAL/` | `archive/docs/00_MATERIAL/` | slides, sketches, drafts |
| `MANUALS/` | `archive/docs/MANUALS-2025/` | learning paths with wrong imports and links to non-existent chapters; the theory was carried into manual chapter 11 |
| `docs/*.md` duplicates (7 byte-identical copies), `docs/SUMMARY.md`, `docs/book.json` | removed / `archive/docs/` | duplicates of `devguide/`; dead TOC |
| `NEXT_STEPS_PDF_GENERATION.md`, `test-report.txt` | archive / removed | session note; build artifact |
| EVOLUTION TASK-001…007, `T-B-DECIDED.md`, `DECISIONS/` | `EVOLUTION/archive/` | completed or superseded by TASK-011 |
| out-of-build modules and demos | `archive/demos/`; modules removed from `master` in October 2026 (last state `315f01d`) | see `archive/demos/README.md` and manual chapter 13. ksql, ksql-app, kafka-streams-tsa, spring-example, store-opentsdb (2.x), ext-connectors, ext-algorithms, predict, processors (empty); app-demos, cloud-bridge, cluster-demo0, clusters, cv, jetson-nano, scala-scripts, modules/ |
| `docker-compose.yml`, `docker-compose.onboarding.yml`, onboarding notebooks | `archive/demos/` | broken build contexts; ZooKeeper/Java 11/Spark-only stack |
| obsolete `bin/` scripts (Kudu, Spark 2, Cloudera deploy, Pandoc manual) | `archive/bin/` | referenced tools that no longer exist |

Also removed:

* ~190 MB of vendored Kafka Connect jars and tarballs (third party, re-downloadable),
* the orphaned submodule pointers (`geoqb`, superset),
* `.ipynb_checkpoints`.

## 4. Findings that need a decision or follow-up

| Finding | Status |
|---------|--------|
| **Credentials in the repository:** `config/private/ccloud.props` is tracked despite `.gitignore`. The same Confluent Cloud key appeared in `SECURITY.md` and in archived notebook outputs. | Redacted in `SECURITY.md`. **The owner should rotate or delete the key.** Removing the file from the index or rewriting history is the owner's decision. |
| Event synchronization (Python) can return Q > 1, because the automatic `tau_max` uses mean inter-event intervals | documented as a known issue (manual chapter 11); fix suggested as a separate task |
| LICENSE, CONTRIBUTING, CODE_OF_CONDUCT, CHANGELOG are missing | TASK-010 |
| `sample_data/` and `data/sample_datasets/` overlap (same file names, different content) | to consolidate; code in `opentsx-core` demos references both |
| `config/cpl*.props` contain private LAN hosts | 2.x client profiles; replace with one localhost/KafScale template |
| Design documents are in German, the manual in English | intended: the manual summarizes; the design docs stay normative |
