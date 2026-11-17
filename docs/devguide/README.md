# OpenTSx developer guide

The developer guide holds notes for contributors. User-facing documentation is in the
[user manual](../user-manual/README.md).

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
* [Processing-flow descriptor schema](schemas/README.md) and its [validation plan](validation/README.md). The
  descriptor is used by `opentsx-flink-core` (`pfd.DynamicTopologyJob`).
* [Maintain operator lessons](../user-manual/17-learn-operators.md): execute the UI/library workloads and check
  generated student material against its source revision and content hashes.
* Tests and CI: `mvn verify`, `pytest`, and the GitHub Actions workflow `.github/workflows/ci.yml` (see
  [user manual, chapter 12](../user-manual/12-operations.md)).
