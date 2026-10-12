# Changelog

All notable changes to OpenTSx are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/). There are no tagged releases yet;
entries are grouped by date. The detailed progress log (German) is in
[`EVOLUTION/TASK-011-datamodel-refresher-2026.md`](EVOLUTION/TASK-011-datamodel-refresher-2026.md).

## [Unreleased]

### Added
- Web workbench in `opentsx-saas-frontend` (route `/workbench`): the Swing bucket analyser in the browser. Load a
  bucket (sample, CSV/TSV, episode JSON, retrieval server), apply the time series operations, inspect every result
  on an oscilloscope view (time base, vertical scale, cursors, trigger, measurements), build the processing flow as
  a graph and export it as `opentsx.dag.v1` Smart Topic definition, Processing Flow Descriptor or Java code. See
  [`EVOLUTION/TASK-013-web-workbench.md`](EVOLUTION/TASK-013-web-workbench.md) for the limits: no runtime executes
  the DAG yet.
- SaaS backend: flows are stored per user in the database (`app/api/flows.py`: create, read, update, delete,
  executions), replacing the placeholder routes. The workbench saves a flow to the account and reopens it from the
  dashboard. `opentsx-saas-backend/smoke-flows.sh` checks accounts and flows against a running instance.
- `deploy/k3s`: optional component `saas` (`SAAS=true`): frontend on node port 30311, backend, PostgreSQL; images
  `saas-frontend` and `saas-backend` (`deploy/docker/`).
- `LICENSE` (Apache License 2.0, full text) and `NOTICE`.
- `CONTRIBUTING.md`, `CODE_OF_CONDUCT.md` (Contributor Covenant 2.1), `SECURITY.md` (reporting via GitHub private
  vulnerability reporting), this changelog.
- `config/opentsx-local.props.example`: localhost / KafScale-compatible Kafka client profile for the 2.x tools.
  Without a local `config/opentsx-local.props` (git-ignored), `OpenTSxClusterLink` falls back to this template.

### Fixed
- SaaS backend: registration failed against a real database (`MissingGreenlet`), every authenticated request
  answered 401 (numeric token subject), and a refresh token was accepted as access token.

### Changed
- Default 2.x client profile is now `./config/opentsx-local.props` (was `./config/cpl.props`); scripts, the
  `opentsx-lg` README and `opentsx-default.properties` updated.
- `opentsx.properties.example` lists only keys the code reads and documents how the v2 modules are configured
  (command-line options and environment variables).

### Removed
- Archived Maven modules (`archive/modules/`, `archive/opentsx-kstreams/`) removed from `master`: not built, but
  their outdated dependencies kept raising security alerts and Dependabot PRs. Last state: commit `315f01d`.
- Four dead submodule links in `archive/demos/` (no URL in `.gitmodules`).
- `config/cpl.props`, `config/cpl_iMac.props`, `config/cpl_local.props`, `config/mdc_c1.props` (client profiles with
  private LAN hosts).

## 2026-10-12

### Added
- Digital twin proof of concept `opentsx-twin` (P9): atom contract with model, views, dynamics and policy; wiring
  plan in YAML; layered bulk-synchronous runner; provenance ladder; shadow, what-if and forecast modes. Scenarios:
  energy system / H2 chain (`bin/opentsx twin`), family house with calibration against bills
  (`bin/opentsx house`, `--simulated`), sailing boat Etap 22 with calibration against logger data
  (`bin/opentsx boat`, `--simulated`).
- Smart Topics Studio prototype (`docs/prototypes/smart-topics-studio.html`) and the LabVIEW / command-loop concept.
- Studio guide (`StudioGuide`, `twin/haus-erzgebirge.guide.yaml`, `twin/h2-chain.guide.yaml`): prose for every atom,
  port, parameter, control and wire of the house twin and the H2 chain, where each value comes from and references to compare against; checked against the plan.
  The node graph explains wires and ports on hover, and each node takes own details (nameplate, serial number, own
  parameter values) with export as inventory (`opentsx.inventory.v1`) and as an own wiring plan.
- Smart Topics Studio redesigned as a product component after reviews from four viewpoints (UI design, UX and
  accessibility, electrical and energy engineering, commercial reader): app bar with scenario switch, scenario summary
  with headline figures and the question behind it, views Überblick, Vergleich, Systembild and Messreihen, inspector
  beside the diagram, connections coloured by energy carrier and routed around the blocks, events in plain language,
  larger units from a threshold on, limit lines in charts, a switch for technical details. Engineering corrections
  in both guides.
- Retrieval (`opentsx-retrieval`, P5): segmentation, pattern engine (MASS, SAX, DTW), retrieval API, REST server,
  Python client, power-curve analysis.
- DevOps guide for k3s against an existing KafScale cluster (`docs/devops/`, `deploy/k3s/`, `deploy/docker/`).
- Design documents: Smart Topics (05), interplay & coordination potential (06), digital twin (07), house (08),
  Studio & LabVIEW (09), boat (10), Smart Topic processor & DevOps (11).
- New English user manual (`docs/user-manual/`), documentation inventory, launcher `bin/opentsx`, link check in CI.

### Changed
- 2026 refresh merged into `master` (#102); SaaS frontend/backend dependencies bundled (Vite 7, python-multipart,
  python-socketio).
- README repositioned ("from time series to decisions").

### Removed
- Outdated documentation and modules outside the build moved to `archive/`; `opentsx-app-tools` archived.

## 2026-10-11

### Added
- Streaming statistics and data quality `opentsx-stats` (quantile sketch, DQ, KafScale processor with checkpoints)
  and `opentsx-openmetadata` (metadata pool, validated against the OpenMetadata 2.0.5 schemas); Python
  `opentsx.stats` (P4).
- Wind-farm domain `opentsx-windpark`: IEC 61400-25-2 data-point catalog, normalizer, simulator with ground truth,
  CLI to KafScale / Iceberg.
- Store SPI with contract suite, Iceberg store (index, log sync), OpenTSDB adapter, PyIceberg reader with byte
  equality between Java and Python (P3).
- `opentsx-kafka-v2` (topic layout, bucket writer, episode index, replay, catalog) and `LegacyBucketImport` (P2);
  end-to-end tests against KafScale v1.6.0 and Apache Kafka 3.9.1.

### Changed
- Avro 1.12.1 managed centrally (precision bug in 1.12.0 covered by a test).

## 2026-10-09

### Added
- Data model v2: `opentsx-model` (Java) and `opentsx.model` (Python), byte-identical via shared test vectors;
  `timestamp-micros` (D2) and xxHash64 series identity (D3); legacy bridge; schema history with compatibility check
  (P1).
- KafScale conformance suite (P0): headers and CreateTime are preserved; `offsetsForTimes`, the idempotent producer,
  standalone commits and consumer-group rejoin do not work on KafScale v1.6.0.
- Analysis of the existing model, target model, phase plan and gap analysis (`docs/datamodel-2026/`).

## Before 2026

| Years | Phase |
|-------|-------|
| 2013–2018 | Hadoop.TS: Hadoop, HBase/OpenTSDB, Spark; research on Wikipedia and financial time series |
| 2018–2020 | renamed to OpenTSx; Kafka, Kafka Streams |
| 2020–2025 | Confluent Platform/Cloud, ksqlDB, multi-region demos, SaaS prototype |
