# Scripts

| Script | Purpose |
|--------|---------|
| `opentsx` | **Launcher for the current tools.** It provides `windpark-sim`, `stats`, `metadata`, `retrieval-server` and `classpath <module>`. See [user manual, appendix A](../docs/user-manual/appendix-a-cli.md). |
| `000_validate_environment.sh` | checks the JDK (17+), Maven, memory, ports and Docker |
| `010_build.sh` | builds all modules (`mvn install`) |
| `build_docs_pdfs.sh`, `Dockerfile.docs-pdf` | PDF of the user manual and dev guide (used by `mvn -Ppdf-manual`) |
| `005_minimal_no_kafka_demo.sh`, `120_run_demo.sh` | 2.x demos (sine-wave generator, without/with Kafka) |
| `000_launch_tsa_workbench.sh` | 2.x GUI (MacroRecorder2) |
| `episode_02/03/09/10_*.sh` | 2.x guided demos (`org.opentsx.demo.onboarding.*`), used with `exercises/` |
| `run_all_tests.sh` | compiles and runs the 2.x exercise validations (`tests/`) |

Tests for the current modules run with `mvn verify` and `pytest` (see the [user manual, chapter 12](../docs/user-manual/12-operations.md)).
Obsolete scripts (Kudu, Spark 2, Cloudera deployment) are in `archive/bin/`.
