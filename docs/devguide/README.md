# OpenTSx developer guide

The developer guide holds notes for contributors. User-facing documentation is in the
[user manual](../user-manual/README.md).

* [PDF generation](PDF-GENERATION.md): build the manual as PDF (Honkit / Docker).
* [Local infrastructure](infrastructure/local-development.md): the local Docker stacks.
* [Processing-flow descriptor schema](schemas/README.md) and its [validation plan](validation/README.md). The
  descriptor is used by `opentsx-flink-core` (`pfd.DynamicTopologyJob`).
* Tests and CI: `mvn verify`, `pytest`, and the GitHub Actions workflow `.github/workflows/ci.yml` (see
  [user manual, chapter 12](../user-manual/12-operations.md)).
