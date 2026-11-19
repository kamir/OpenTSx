# Appendix A: Command-line tools

This appendix lists every command of `bin/opentsx` and of the container image `opentsx-tools`, the messages of a
misconfigured command and the other scripts in `bin/`. Verified on OpenTSx 3.0.1 (`opentsx version` prints
`opentsx 3.0.1 (java)`).

You need a build: `mvn -q install -DskipTests` (JDK 17+, Maven 3.9+ on `PATH`).

## Run a command

Run `bin/opentsx <command> [options]`. `bin/opentsx --help` lists the commands, and `bin/opentsx <command> --help`
prints the keys of a command with their options, variables and defaults ([appendix D](appendix-d-configuration.md)
lists every key, [chapter 18](18-configuration.md) shows which source wins).

The launcher computes the runtime classpath of a module with Maven (`dependency:build-classpath
-DincludeScope=runtime`) and caches it in `<module>/target/classpath-runtime.txt`. It resolves the classpath again
when the `pom.xml` of the module or of the repository root is newer than the cache, or when an OpenTSx jar on the
cached classpath is newer, which happens when you install a sibling module again. It adds `conf/` and, when the
classpath holds slf4j-api but no slf4j-simple, the slf4j-simple of the same version, so log lines on stderr follow
`conf/simplelogger.properties`. For `stats-flink` the launcher also caches the provided scope in
`opentsx-flink-core/target/classpath-provided.txt`, adds neither `conf/` nor slf4j-simple and logs through log4j2
with `conf/log4j2-stats-flink.properties`. [`bin/README.md`](../../bin/README.md#fix-a-failed-start-of-binopentsx)
lists the messages of a failed classpath step.

Every Java command takes `--conf <key>=<value>` (also `-c`), `--properties-file <path>`, `--help` and `--version`,
refuses an unknown option, a missing value and a malformed value before it does any work, and exits with 0 on
success, 1 on a runtime failure and 2 on a usage or configuration error. A boolean option without a value means
`true`. `--bootstrap` is a legacy name of `--kafscale`, and `KAFSCALE_BOOTSTRAP` of `OPENTSX_KAFSCALE_BOOTSTRAP`, for
every command that takes the bootstrap. A secret key is refused on the command line.

## Commands

| Command | Main class | Options |
|---------|-----------|---------|
| `windpark-sim` | `org.opentsx.windpark.WindparkSim` | Runs a wind-farm scenario and writes its episodes to KafScale, Iceberg or both: `--scenario <yaml>` (required), `--kafscale <bootstrap> --domain <d> --index <file> --partitions <n>`, `--iceberg k=v,... --namespace <ns>`, `--sinks auto\|kafscale\|iceberg\|kafscale,iceberg` (`auto`: the sinks whose option is on the command line), `--catalog <yaml>`, `--ground-truth <json>` |
| `stats` | `org.opentsx.stats.StatsProcessorApp` | Runs the streaming statistics processor on KafScale: `--kafscale <bootstrap>` (required), `--domain`, `--checkpoint <file>`, `--windows PT10M,PT1H`, `--lateness PT0S`, `--alpha 0.01`, `--observations`, `--once` |
| `stats-flink` | `org.opentsx.flink.stats.StatsJob` | The statistics of `stats` as a Flink job on a local mini cluster ([chapter 7](07-stats-quality.md)): `--kafscale <bootstrap>` (required), `--domain`, `--windows PT10M,PT1H`, `--lateness PT0S`, `--alpha 0.01`, `--observations`, `--global-every PT30S`, `--checkpoint-every PT30S`, `--once`; it refuses `--checkpoint`, because positions and state live in Flink checkpoints; `bin/opentsx` only |
| `metadata` | `org.opentsx.openmetadata.WindparkMetadata` | Publishes the wind-farm metadata pool to OpenMetadata: `--scenario <yaml>` (required), `--om <url>/api` with the bot token in `OPENMETADATA_JWT` (`read -rs OPENMETADATA_JWT && export OPENMETADATA_JWT`) or `--dry-run <dir>`, `--catalog`, `--domain`, `--namespace`, `--partitions`, `--kafscale <bootstrap>`, `--profiles auto\|always\|never` (`auto`: profiles when `--kafscale` is on the command line), `--profile-window PT1H`, `--dq-window PT10M` |
| `vectors` | `org.opentsx.retrieval.vector.VectorProcessorApp` | `[run] --kafscale <host:port> --domain <d> --state <dir>` runs the vector processor (one per domain; `--state` defaults to `vector-state` in the working directory); `deploy --kafscale <host:port> --domain <d> --dag <file.yaml> [--by <who>]` and `stop --kafscale <host:port> --domain <d> --name <dag> [--by <who>]` write to the control topic `tsx.<domain>.dags.v1`; `--domain` defaults to `wind`, `--by` to `cli`; the options, the name and the DAG file are checked before the first call to the broker |
| `retrieval-server` | `org.opentsx.retrieval.server.RetrievalServer` | Serves the REST API ([appendix B](appendix-b-rest.md)): `--source auto\|demo\|iceberg\|kafscale` (`auto`: `iceberg` when `--iceberg` is on the command line, `kafscale` when `--kafscale` is, else `demo`; both is an error), `--port <n>` (default 8080), `--bind <address>` (default: every interface); `iceberg`: `--iceberg k=v,... --namespace <ns>`; `kafscale`: `--kafscale <host:port> --domain <d>` with `--refresh`, `--max-episodes` and `--dag-runs` to deploy and stop DAGs for the vector processor, which needs a run token in `OPENTSX_DAG_RUN_TOKEN` (`read -rs OPENTSX_DAG_RUN_TOKEN && export OPENTSX_DAG_RUN_TOKEN`) that callers send in the header `X-OpenTSx-Run-Token`, or `--open-dag-runs`, which accepts runs without a token and listens on 127.0.0.1 only; `demo`: three synthetic series; optional `--scenario <file>`, `--catalog <file>` for `GET /v1/inventory`; `--systems <dir> [--systems-root <dir>]` serves the system documents (`*.system.yaml`, at least one) of a directory and the state of their signals, with references resolved against the root; exit code 0 while it listens |
| `twin` | `org.opentsx.twin.demo.H2ChainDemo` | `[--plan file.yaml]`; runs scenario S1 in shadow, what-if and forecast mode and prints events and KPIs |
| `house` | `org.opentsx.twin.demo.HouseDemo` | `[--plan file.yaml] [--calibrate bills.csv [--weather temps.csv]] [--simulated] [--hdd-ref Kd]`; house twin: reference year in shadow mode, KPIs and every efficiency lever as what-if (alternative minus reference); `--calibrate` first fits the plan to bills (template `opentsx-twin/src/main/resources/twin/abrechnungen-vorlage.csv`, keep real bills in `config/private/`); `--simulated` calibrates on the bundled simulated bills in `opentsx-twin/src/main/resources/twin/beispiel/` |
| `boat` | `org.opentsx.twin.demo.BoatDemo` | `[--plan file.yaml] [--data file.csv [--zone Europe/Berlin] [--calibrate] \| --simulated]`; boat twin (Etap 22): shadow of a synthetic two-week trip and what-ifs with KPI deltas; with `--data` on real operating data (CSV, template `opentsx-twin/src/main/resources/twin/bootsdaten-vorlage.csv`), with `--calibrate` fitted to the measurements; `--simulated` runs import and calibration on the bundled SIMULATED logs (`opentsx-twin/src/main/resources/twin/beispiel/boot-logs-simuliert.csv`) and compares with the hidden true parameters |
| `history-twin twin\|house\|boat` | `org.opentsx.market.twin.HistoryTwins` | The twins on the history archive ([opentsx-market](../../opentsx-market/README.md)): `twin --year Y [--from D --days N] [--wind smard\|dwd] [--cf 0.30]`, `house --weather-year Y\|Y1-Y2 [--levers] [--station id] [--radiation-station id] [--hdd-ref Kd]`, `boat --weather-year Y [--start D] [--days 14] [--station id] [--radiation-station id]`, each with `[--archive <dir>]` (legacy `--history`) and `[--sites file.yaml]`; `twin`, `house` and `boat` run this command when `--history` or `--weather-year` is given |
| `dwd` | `org.opentsx.dwd.DwdCli` | DWD Open Data collector ([opentsx-dwd](../../opentsx-dwd/README.md)): `stations --near LAT,LON [--n 5] [--catalog mosmix\|cdc]`, `mosmix --station ID`, `obs --station CDC_ID [--param wind,air_temperature,solar,precipitation] [--hourly]`, `house --station ID`; each with `--offline <file>` instead of a download (`obs`: a comma-separated list, without `--station`, `--param` and `--hourly`, exit code 2 otherwise); `mosmix` and `obs` also write `--out <file.csv>`, `--daily <file.csv>` and `--boat <file.csv>`; downloads use the JVM proxy settings, else the proxy in `OPENTSX_HTTPS_PROXY` (legacy `HTTPS_PROXY`, `https_proxy`), and try a 429, a 5xx or a timeout up to 4 times; the options and the offline files are checked before the first download |
| `modbus` | `org.opentsx.modbus.ModbusCli` | Modbus meters through a YAML register map ([opentsx-modbus](../../opentsx-modbus/README.md)): `check\|read\|poll\|simulate --map <file.yaml\|example>`; `check`, `read` and `poll`: `[--host h] [--port p] [--unit u]`; `read` and `poll`: `[--simulate [--seed 42]]`; `poll`: `[--count n] [--interval 5s] [--out csv\|episodes] [--file f] [--episode PT1M]`; `simulate`: `[--port 1502] [--bind 127.0.0.1] [--seed 42] [--seconds n] [--unit u]`; `--simulate` polls an in-JVM simulated meter and labels the data `SYNTHETIC`; `--seed` needs `--simulate`, and `--simulate` refuses `--host` and `--port` (exit code 2); the options and the map are checked before the first connection; exit code 1 also when a read failed |
| `twin-coupling` | `org.opentsx.twin.demo.CouplingStudy` | `[--csv <file>] [--scenario h2-chain\|haus\|boot]`; repeats the reference run of each Studio scenario, or of the one `--scenario` names, with loose coupling (variant L) over a grid of staleness bounds and transport latencies and compares it with tight coupling (variant T); prints the grid, `--csv` also writes it |
| `twin-live` | `org.opentsx.twin.live.TwinLive` | `publish --kafscale <host:port> [--scenario h2-chain\|haus\|boot] [--duration <seconds>] [--loop]` writes the events of the reference runs to their topics `twin.<id>.<run>.events`, with `--loop` again and again; `bridge --kafscale <host:port> [--port 8090] [--allow-origin *] [--max-streams 16]` serves event topics to browsers as server-sent events; `demo [--port 8090] [--scenario <id>] [--duration 120] [--allow-origin *] [--max-streams 16]` runs both in one process on topics in memory and writes nothing to KafScale; `EVENT_BRIDGE_ALLOW_ORIGIN` is a legacy name of `OPENTSX_TWIN_LIVE_ALLOW_ORIGIN`; the options and the scenario are checked before the bridge starts ([opentsx-twin-live](../../opentsx-twin-live/README.md)) |
| `twin-schemas` | `org.opentsx.twin.schema.AtomSchemas` | `[--out <dir>]`; writes the Avro topic contract of the twin; the default `opentsx-twin/src/main/resources/twin/avro` is relative to the working directory, so run it from the repository root |
| `studio-export` | `org.opentsx.twin.studio.StudioExport` | `<out.json>`; writes the scenario data of the Smart Topics Studio to a JSON file |
| `market <command>` | `org.opentsx.market.MarketCli` | Electricity-market data and the history archive ([opentsx-market](../../opentsx-market/README.md)): `prices --source smard\|energy-charts\|entsoe --from D --to D [--out f.csv] [--offline file] [--archive dir]`, `series --name source/series ...`, `rebap ...`, `marktwert ...`, `import --file f.csv --name n --metric m --unit u [--origin] [--partner] [--resolution] [--value-column] [--zone]`, `backfill [--archive data/history] [--series market\|all\|<source/series>,...] [--from-year 2019] [--to-year Y] [--delay-ms 500] [--force]`, `forward`, `weather-backfill [--from-year 2019] [--stations id:param,...] [--sites file.yaml]`, `weather-forward`, `mosmix-archive [--stations ...]`, `report [--series <s>] [--requality]`, `load [--store auto\|memory\|iceberg] [--iceberg k=v,... --namespace tsx] [--from-year Y --to-year Y] [--from-day D --to-day D]`, `publish --series <source/series>,... --kafscale <host:port> --domain <d> [--archive data/history] [--from-year Y --to-year Y] [--from-day D --to-day D] [--batch 100] [--pause-ms 3000] [--dry-run]` (archived series as raw episodes into `tsx.<domain>.episodes.v2`; stops at the first refusal of the broker), `catalog`; `--archive` has the legacy name `--history`; the credentials are secret keys from the environment (`OPENTSX_MARKET_ENTSOE_TOKEN` or `ENTSOE_TOKEN`, `OPENTSX_MARKET_NTP_CLIENT_ID` or `NTP_CLIENT_ID`, `OPENTSX_MARKET_NTP_CLIENT_SECRET` or `NTP_CLIENT_SECRET`) or a properties file; `--store always\|never\|auto` decides whether a fetch stores into the archive; `market load` writes to Iceberg with `--store iceberg` (`OPENTSX_MARKET_LOAD_STORE`), and with `--store auto` (default) only when `--iceberg` is on the command line; the downloads honour `OPENTSX_HTTPS_PROXY` when the JVM property `https.proxyHost` is not set |
| `kafscale-check` | `org.opentsx.deploy.KafScaleCheck` | Checks a KafScale bootstrap address and the broker addresses the cluster advertises from where it runs: `--kafscale <host:port>` (required, several separated by commas), `--domain` (default `wind`), `--provision` creates the topics of the domain that do not exist yet with `--partitions <n>` (default 6), `--timeout PT20S` per request; prints one `reachable` or `NOT reachable` line per address, a `FAIL:` line per failed check, the partitions and records of every topic of the domain, and `OK`; exit code 1 when an address is not reachable or a request failed; image only |
| `config list\|show\|get`, `version` | `org.opentsx.conf.ConfCli` | `config list [--output text\|json]` prints the key registry; `config show [--output text\|json]` prints every key with a value and its source, secrets as `******`; `config get <key>` prints one effective value; `config show` and `config get` take `--conf <key>=<value>` for any key; `version` and `--version` print `opentsx 3.0.1 (java)` ([opentsx-conf](../../opentsx-conf/README.md)) |
| `classpath <module>` | none (shell function of the launcher) | prints the runtime classpath, for example for your own `java -cp` calls |

## Run the commands in the container image

The image `opentsx-tools` is published as `ghcr.io/kamir/opentsx-tools:<version>`
([chapter 12](12-operations.md#124-run-the-tests)); the k3s manifests of the [DevOps guide](../devops/README.md)
name it `opentsx/tools`. Its entrypoint `opentsx <command>` has the commands and options of `bin/opentsx`, with two
differences:

* It has no `stats-flink`; the image holds no Flink runtime.
* It adds `kafscale-check`, which has no Maven module; `deploy/docker/stage.sh` compiles it into the image.
  `deploy/k3s/00-prereqs.sh` runs it from the staged directory on the host.

`twin`, `house` and `boat` run `history-twin` on `--history` or `--weather-year`, as `bin/opentsx` does, also when the
option comes through `OPENTSX_EXTRA_ARGS`. `classpath <module>` prints the staged classpath. The entrypoint reads
three variables: `OPENTSX_HOME` (installation directory), `OPENTSX_EXTRA_ARGS` (arguments appended to the command)
and `JAVA_OPTS` (JVM options after `-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError`). CI fails when the two
launchers differ in any other command: `bash deploy/docker/test-launchers.sh --commands`.

## Fix a configuration error

A command that loads the configuration prints one line `opentsx: <message>` on stderr, nothing on stdout, and exits
with 2. The common messages:

| Message | Cause | Fix |
|---------|-------|-----|
| `opentsx: --partitons: unknown option of windpark-sim; did you mean --partitions?` | a misspelled option | use the option the message names |
| `opentsx: --out: unknown option of studio-export; run opentsx studio-export --help for the options` | an option the command does not read | run the command with `--help` |
| `opentsx: --scenario: missing value; expected --scenario <path>` | an option at the end of the line | give the value after it |
| `opentsx: --profile-window: "PT60" is not a duration; expected ISO-8601 such as PT10M or a whole number with the unit ms, s, m, min, h or d such as 10m, at most 999999999 days` | a malformed value | write a value of the type the message names |
| `opentsx: OPENTSX_STATS_ONCE: "yes" is not a boolean; expected true or false` | a malformed variable | set `true` or `false`, or unset the variable |
| `opentsx: yes: unexpected argument; expected opentsx stats` | a word after a boolean option, for example `--once yes` | write `--once` or `--once true` |
| `opentsx: --bootstrap: set twice on the command line (also by --kafscale); set each key once` | a key given twice, also through a legacy option or an option that a k3s Job already sets | give the key once |
| `opentsx: opentsx.kafscale.bootstrap: not set; set it with --kafscale, --conf opentsx.kafscale.bootstrap=<value> or OPENTSX_KAFSCALE_BOOTSTRAP` | a required key without value | set the key |
| `opentsx: --token: secret keys are not accepted on the command line; set OPENTSX_OPENMETADATA_TOKEN or OPENMETADATA_JWT or put opentsx.openmetadata.token in a properties file` | a secret on the command line | export the variable the message names |
| `opentsx: depoly: unknown subcommand of vectors; did you mean deploy?` | a misspelled subcommand | use the subcommand the message names |
| `opentsx: opentsx.kafscale.bootstrap: "host:abc" is not host:port; expected host:port with a port from 1 to 65535, several separated by commas, such as kafscale:9092` | `kafscale-check` with a bootstrap address without a port | give `host:port` |
| `opentsx: OPENTSX_RETRIEVAL_PORT: "tcp://<ip>:8080" is not an int; expected a whole number from -2147483648 to 2147483647` | a pod in the namespace of the Service `opentsx-retrieval`, which makes Kubernetes set `OPENTSX_RETRIEVAL_PORT` | set `enableServiceLinks: false` in the pod |

[Chapter 18](18-configuration.md#fix-a-configuration-error) lists the messages of keys, files and variables.

## Other scripts in bin/

* `000_validate_environment.sh`: environment check; exits 1 when a check fails (failed checks on stderr; a
  `java -version`, `mvn -version` or `git --version` that fails is a failed check), 2 on an unknown option.
* `010_build.sh`: build.
* `000_launch_tsa_workbench.sh`: the 2.x GUI `org.opentsx.app.bucketanalyser.MacroRecorder2` through
  `mvn -f opentsx-core/pom.xml exec:java`; without Maven on `PATH` with the classpath that `bin/opentsx` cached for
  `opentsx-core`.
* `005_minimal_no_kafka_demo.sh`: the 2.x sine-wave generator (`org.opentsx.lg.TSDataSineWaveGenerator`) without
  Kafka; it writes CSV files to `data/temp/`.
* `episode_*.sh`: 2.x guided demos.
* `run_all_tests.sh [--quick|--full|--validation-only|--demo-only]`: `--quick` and `--validation-only` run
  `ExerciseValidationTest` of `opentsx-core`; `--demo-only` checks the syntax of the `episode_*.sh` scripts; `--full`
  (the default) does both and checks that five documentation files exist. It writes `test-report.txt` and
  `test-summary.json` to the repository root. It counts the Maven test, the syntax checks and the required files
  separately.
* `build_docs_pdfs.sh`: PDF of this manual, via `mvn -Ppdf-manual`.
