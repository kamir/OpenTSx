# OpenTSx developer guide

The developer guide holds notes for contributors. User-facing documentation is in the
[user manual](../user-manual/README.md).

* [Choose a reproducible demo](demos.md): prerequisites, acceptance and captured
  evidence for the local Workbench, finite forecast and Windpark journeys.
* [PDF generation](PDF-GENERATION.md): build the manual as PDF (Honkit / Docker).
* [Local infrastructure](infrastructure/local-development.md): the local Docker stacks.
* [Deploy UI DAGs through Wayang to Java and Rust nodes](integration/wayang-unified-plan.md): unified delivery
  plan, [implemented shared contracts](integration/wayang-shared-contracts.md) and
  [architecture assessment](integration/wayang-orchestration-review.md).
* [Run a workbench DAG on three Rust workers through Wayang](integration/wayang-rust-compose.md): authenticated UI,
  durable PostgreSQL control, one custom Wayang stage, private preparation and bounded acknowledgement/retry checks.
* [Run continuous input on three Rust workers](integration/wayang-rust-continuous.md): persisted increments,
  cumulative ownership and positions, stateful service restart and a durable Stop operation.
* [Replay a bounded topic with Rust](integration/rust-runner-local.md): trusted local configuration, three cleaning
  operations, retained state and a standalone three-worker Compose check against Java.
* [Operate signed bounded Rust statistics sites](integration/rust-runner-b3.md): owner-local qualification,
  TLS 1.3 plan pulls, signed conformance, acknowledged aggregate catch-up and certificate-forced SSH launch.
* [Execute the embedded WASM conversion](integration/rust-runner-b5.md):
  artifact-only `convert.unit`, fixed fuel, memory and stack limits, no host
  imports and unchanged signed admission.
* [Inspect bounded mixed Java and Rust federation](integration/wayang-mixed-federation.md): actual-CLI W3
  acceptance, numerical-domain evidence and board preparation. The physical ARM64 measurement remains open.
* [Prepare the B4.2 run on a Jetson Nano](integration/wayang-board-jetson.md): board setup, identity, one-second
  samples and the 81-condition matrix on one ARM64 board (D-T14, D-T15).
* [Processing-flow descriptor schema](schemas/README.md) and its [validation plan](validation/README.md). The
  descriptor is used by `opentsx-flink-core` (`pfd.DynamicTopologyJob`).
* [Maintain operator lessons](../user-manual/17-learn-operators.md): execute the UI/library workloads and check
  generated student material against its source revision and content hashes.
* Tests and CI: `mvn verify`, `pytest`, and the GitHub Actions workflow `.github/workflows/ci.yml` (see
  [user manual, chapter 12](../user-manual/12-operations.md)).
