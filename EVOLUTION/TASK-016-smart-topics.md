# TASK-016: Smart Topics

## Task Metadata
- **Task ID**: TASK-016-smart-topics
- **Created**: 2026-10-26
- **Status**: In Progress
- **Priority**: Medium
- **Epic**: P7 Smart Topics
- **Origin**: split out of [TASK-011](TASK-011-datamodel-refresher-2026.md) on 2026-10-26 (D90, [18-CLOSING-TASK-011](../docs/datamodel-2026/18-CLOSING-TASK-011.md))
- **Concept**: [05-SMART-TOPICS.md](../docs/datamodel-2026/05-SMART-TOPICS.md)

## Objective
Build Smart Topics (`opentsx-smart`, Python `opentsx.smart`): specification and feature pipeline (P7a), Python
training (P7b), Java scoring processor with Smart Metrics and Prediction API (P7c), federated global model per target
(P7d), the Studio up to production use (P7e) and its operation with the proposed Go TSx processor (P7f). The LabVIEW
bridge maps sensor ↔ Smart Topic ↔ actuator.

## Phase 7 (moved from TASK-011)

Moved from TASK-011 (commit 551815a3), lines 108 to 131.

### Phase 7: Smart Topics (`opentsx-smart`, Python `opentsx.smart`), draft: [`05-SMART-TOPICS.md`](../docs/datamodel-2026/05-SMART-TOPICS.md)
- [ ] P7a: Specification (YAML, versioned) + feature pipeline Java/Python with test vectors; schema v2.1 `SmartMetric`.
- [ ] P7b: Python training (candidates with/without context, temporal split, conformal intervals) → ONNX/JSON → MLflow (alias `champion`).
- [ ] P7c: Java scoring processor (KafScale, checkpoint, ONNX Runtime) → Smart Metrics + Prediction API (Java/REST).
- [ ] P7f (proposal, [`11-SMART-TOPIC-PROZESSOR-DEVOPS.md`](../docs/datamodel-2026/11-SMART-TOPIC-PROZESSOR-DEVOPS.md)): **Go TSx processor** based on the KafScale processor template (S3 segments, lease per partition) with declarative DAG `opentsx.dag.v1`; Go stream core (decoding, normalizing, episodes, Welford/DDSketch, DQ, incremental segmenters, resampling, scoring of pure Go models, `train.trigger`) with Go↔Java conformance via shared test vectors; Java sidecar for pattern search/twin; replay tests in CI; shadow operation and switchover; edge operation (Pi, lab PC) with checkpoint file.
- [x] P7f operations (DevOps, current Java state): [`docs/devops/`](../docs/devops/README.md) with setup plan, sizing plan (formula + worked examples, 2.18 B/value), demos and the boundary between today and planned; scripts `deploy/k3s/00…90`, Kustomize (base, jobs, components hostnetwork/lake/ingress), images `opentsx/tools` (JRE, all commands) and `opentsx/studio` (page generated in the build), helper utility `kafscale-check` (advertised addresses, topic creation); CI renders the overlays.
  - [x] Operations concept [`docs/devops/operations.md`](../docs/devops/operations.md): service catalog with criticality, environments (local trial run, lab, production-like), monitoring (what to watch, `kubectl` level, proposal kube-state-metrics + VictoriaMetrics/Prometheus + Grafana, alert rules only on existing metrics), logging, backup/restore, update/rollback (incl. Avro v2 compatibility: update readers before writers), secrets, security, runbooks R1 to R8, roles, lab SLOs, capacity check.
  - [x] Local trial run `deploy/k3s/60-probe.sh` (`--local-kafscale` with LAN IP as advertised address, `--skip-build`, `--online`, `--dry-run`): all steps from prerequisites to Modbus with PASS/FAIL report `probe-report-<UTC time>.md` and hints; `70-backup.sh` / `71-restore.sh` (checkpoint, data, catalog and MinIO PVCs, configuration; checksums, confirmation prompt).
  - [x] DWD and Modbus in the cluster: Job `dwd` (offline with fixture in the image, `dwd-online`), CronJob `dwd-forward` (daily MOSMIX fetch as CSV), simulator `modbus-sim` (Deployment + Service :1502) and Job `modbus-poll` (episodes as JSON lines on the data PVC); component `monitoring` (synthetic check + alert rules); CI additionally validates with kubeconform and runs the probe plan.
  - [ ] First real run of `60-probe.sh --local-kafscale` on a machine with Docker and k3s (here, without a container daemon, only the dry run, the stub cluster and the KafScale path via the image entry point were checked); lake path (S3FileIO, REST catalog) end to end; measurements from the sizing plan.
  - [ ] Metrics export from the OpenTSx processes (`/metrics`: lag per partition and checkpoint age of the statistics processor, read/written/undecodable records, DQ violations, retrieval latency, Modbus cycles/timeouts, DWD output time) including dashboards.
  - [ ] KafScale sink for the Modbus poller and the DWD collector (`tsx.weather.*`), permanent poller as a Deployment close to the converter; NetworkPolicies and Pod Security labels as a tested component; scheduled backup as a CronJob with an S3 target.
- [ ] P7d: Federated global model per target (Wayang approach: local trainings per system contribute, no raw data is moved).
- [ ] P7e (proposal): Personal Service Layer on the SaaS stack: control plane for one's own Smart Series (`05-SMART-TOPICS.md` §11).
- [x] P7e Studio prototype ([`09-STUDIO-LABVIEW-STEUERPULT.md`](../docs/datamodel-2026/09-STUDIO-LABVIEW-STEUERPULT.md)): statistics, forecast & what-if, node graph; static page `docs/prototypes/smart-topics-studio.html` from `UiExport` + `tools/build_studio.py`.
- [x] P7e Studio guide (branch `opentsx/2026-studio-guide`): `StudioGuide` + `twin/haus-erzgebirge.guide.yaml` and `twin/h2-chain.guide.yaml` (prose per block, port, parameter, control variable, wire; provenance of the values; comparison sources), tested against the schematic; hover explanation per wire and port; one's own entries per block, with export as an inventory and as a schematic of one's own. Open: guide for the boat.
- [x] P7e Studio design (branch `opentsx/2026-studio-design`): product interface following four reviews (UI design, UX and accessibility, electrical and energy engineering, commercial view); views Overview, Comparison, System view, Measurement series; inspector next to the system view; connections by energy carrier; `headline()`, `decision()`, `limits()` on the `StudioScenario`; domain corrections in both guides. Open from the reviews: threshold of the protective shutdown below the maximum pressure, burner runtime in the oil boiler model, balancing energy and H₂ revenue in the marketer, PDF export, shared time cursor in all charts.
- [x] P7e Studio shell (branch `opentsx/2026-studio-shell`, worktree `OpenTSx-worktrees/studio-shell`): title bar with menus, collapsible icon bar on the left, scenarios as document tabs, detail panel from the right, status bar; only template and generated page, no Java. Tab per scenario with link to the plant explorer and view Event topic (playback of the reference run). Open: dockable panels, command palette, table view of the charts.
- [x] P7e event topic live (branch `opentsx/2026-twin-event-topic`, worktree `OpenTSx-worktrees/twin-event-topic`, builds on `opentsx/2026-studio-shell`): module `opentsx-twin-live` with `EventPublisher` (events of a run to `twin.<id>.<run>.events`), `EventBridge` (SSE for the browser) and `bin/opentsx twin-live`; the Studio view reads live via the bridge. Addendum (branch `opentsx/2026-event-topic-create`): the publisher creates the topic before the first event. Addendum (branch `opentsx/2026-event-bridge-k3s`, worktree `OpenTSx-worktrees/event-bridge-k3s`): k3s component `event-bridge` (NodePort 30312), rolled out on master2. Open: twin publishes while computing (`MessageSink`), Avro variant of the event schema, authentication of the bridge.
- [ ] P7e Studio in production: live data via KafScale, statistics from P4, Prediction API from P7, editable node graph (schematic with validation), interventions with approval workflow, composite blocks.
- [x] Studio in general: `StudioScenario` (schematic, reference, alternatives, forecasts, Smart Metrics, KPIs) + `StudioExport`; interface with scenario selection without special cases.
- [x] House and boat scenario plugged in as `StudioScenario` (`HouseStudioScenario`, `BoatStudioScenario`, derived KPIs via `derivedKpis`).
- [ ] LabVIEW bridge (09 §4/§4a): mapping file sensor ↔ Smart Topic ↔ actuator; JSON contracts `opentsx.smartmetric.v1`, `opentsx.command.v1`, `opentsx.command-ack.v1` as JSON Schema + normalizer + tests; feedback topic `tsx.<domain>.commands.v1` with acknowledgements, mirroring into infinimesh `desired`; publisher/command VIs (VIPM, BSD/MIT); test of client certificate TLS LabVIEW ↔ infinimesh. *(TDMS import deferred.)*
