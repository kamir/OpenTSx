# Archived demos and infrastructure (moved 2026-10-12)

These are historical demos, cluster setups and experiments. They are not built, not tested, and contain hard-coded
local paths and Confluent Cloud/Platform assumptions.

Four submodule links (`opentsx-cloud-bridge/cp-all-in-one`, `opentsx-clusters/Krake-container/tmp`,
`opentsx-clusters/examples`, `opentsx-clusters/multiregion/examples`) were removed in October 2026: they had no URL in
`.gitmodules`, could not be checked out and caused a warning in every CI run. Their last state is commit `315f01d`.

| Folder | Content |
|--------|---------|
| `opentsx-app-demos/` | 2019–2021 demo scripts and slides (ApacheCon, Confluent Cloud latency, meetups) |
| `opentsx-cloud-bridge/`, `opentsx-cv/` | REST-proxy bridges to Confluent Cloud (incl. webcam capture) |
| `opentsx-cluster-demo0/`, `opentsx-clusters/` | Kubernetes / multi-region / Krake cluster scripts for Confluent Platform |
| `opentsx-jetson-nano/` | idea note for a Jetson Nano image processor |
| `scala-scripts/` | spark-shell demos (RNG, OpenTSDB streaming) |
| `legacy-modules/` | former `modules/`: TSCache (HBase web app), ingest-2-cassandra (vendored Kafka Connect jars removed), KS-test R scripts |
| `notebooks-onboarding/`, `jupyter-notebooks/` | onboarding notebook (Spark/Scala) and Wikipedia page-view producer for Confluent Cloud |
| `docker-compose.saas-old.yml`, `docker-compose.onboarding.yml` | broken SaaS compose (missing build contexts) and the 2025 onboarding stack (ZooKeeper, Flink/Java 11, Spark notebook) |

The current local stacks are `docker-compose.kafscale.yml` and `docker-compose.local.yml` at the repository root.
