# Appendix A: Command-line tools

Build once (`mvn -q install -DskipTests`), then run `bin/opentsx <command> [options]`. The launcher computes the
module classpath and caches it in `<module>/target/classpath.txt`.

| Command | Main class | Options |
|---------|-----------|---------|
| `windpark-sim` | `org.opentsx.windpark.WindparkSim` | `--scenario <yaml>` (required), `--kafscale <bootstrap> --domain <d> --index <file> --partitions <n>`, `--iceberg k=v,... --namespace <ns>`, `--catalog <yaml>`, `--ground-truth <json>` |
| `stats` | `org.opentsx.stats.StatsProcessorApp` | `--kafscale <bootstrap>` (required), `--domain`, `--checkpoint <file>`, `--windows PT10M,PT1H`, `--lateness PT0S`, `--alpha 0.01`, `--observations true`, `--once true` |
| `metadata` | `org.opentsx.openmetadata.WindparkMetadata` | `--scenario <yaml>` (required), `--om <url>/api --token <jwt>` or `--dry-run <dir>`, `--catalog`, `--domain`, `--namespace`, `--partitions`, `--kafscale <bootstrap> --profile-window PT1H --dq-window PT10M` |
| `retrieval-server` | `org.opentsx.retrieval.server.RetrievalServer` | `--port <n>`, `--iceberg k=v,... --namespace <ns>` or `--demo true` |
| `classpath <module>` | — | prints the runtime classpath, e.g. for your own `java -cp` calls |

Other scripts in `bin/`:

* `000_validate_environment.sh`: environment check.
* `010_build.sh`: build.
* `000_launch_tsa_workbench.sh`: the 2.x GUI.
* `episode_*.sh`: 2.x guided demos.
* `build_docs_pdfs.sh`: PDF of this manual, via `mvn -Ppdf-manual`.
