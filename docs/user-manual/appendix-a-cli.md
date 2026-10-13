# Appendix A: Command-line tools

Build once (`mvn -q install -DskipTests`), then run `bin/opentsx <command> [options]`. The launcher computes the
module classpath and caches it in `<module>/target/classpath.txt`.

| Command | Main class | Options |
|---------|-----------|---------|
| `windpark-sim` | `org.opentsx.windpark.WindparkSim` | `--scenario <yaml>` (required), `--kafscale <bootstrap> --domain <d> --index <file> --partitions <n>`, `--iceberg k=v,... --namespace <ns>`, `--catalog <yaml>`, `--ground-truth <json>` |
| `stats` | `org.opentsx.stats.StatsProcessorApp` | `--kafscale <bootstrap>` (required), `--domain`, `--checkpoint <file>`, `--windows PT10M,PT1H`, `--lateness PT0S`, `--alpha 0.01`, `--observations true`, `--once true` |
| `metadata` | `org.opentsx.openmetadata.WindparkMetadata` | `--scenario <yaml>` (required), `--om <url>/api --token <jwt>` or `--dry-run <dir>`, `--catalog`, `--domain`, `--namespace`, `--partitions`, `--kafscale <bootstrap> --profile-window PT1H --dq-window PT10M` |
| `retrieval-server` | `org.opentsx.retrieval.server.RetrievalServer` | `--port <n>`, then `--iceberg k=v,... --namespace <ns>`, `--kafscale <host:port> --domain <d>` (`--refresh`, `--max-episodes`, `--dag-runs true` to write vector topics) or `--demo true`; optional `--scenario <file>`, `--catalog <file>` for `GET /v1/inventory` |
| `twin` | `org.opentsx.twin.demo.H2ChainDemo` | `[--plan file.yaml]`; runs scenario S1 in shadow, what-if and forecast mode and prints events and KPIs |
| `house` | `org.opentsx.twin.demo.HouseDemo` | `[--plan file.yaml] [--calibrate bills.csv [--weather temps.csv]] [--simulated] [--hdd-ref Kd]`; house twin: reference year in shadow mode, KPIs and every efficiency lever as what-if (alternative minus reference); `--calibrate` first fits the plan to bills (template `twin/abrechnungen-vorlage.csv`, keep real bills in `config/private/`); `--simulated` calibrates on the bundled simulated bills in `twin/beispiel/` |
| `boat` | `org.opentsx.twin.demo.BoatDemo` | `[--plan file.yaml] [--data file.csv [--zone Europe/Berlin] [--calibrate] \| --simulated]`; boat twin (Etap 22): shadow of a synthetic two-week trip and what-ifs with KPI deltas; with `--data` on real operating data (CSV, template `twin/bootsdaten-vorlage.csv`), with `--calibrate` fitted to the measurements; `--simulated` runs import and calibration on the bundled SIMULATED logs (`twin/beispiel/boot-logs-simuliert.csv`) and compares with the hidden true parameters |
| `dwd` | `org.opentsx.dwd.DwdCli` | `stations --near LAT,LON [--n 5] [--catalog mosmix\|cdc]`, `mosmix --station ID`, `obs --station CDC_ID [--param wind,air_temperature,solar] [--hourly]`, `house --station ID`; each with `--offline file` instead of a download and `--out/--daily/--boat file.csv`; DWD Open Data collector: MOSMIX_L forecasts and CDC observations as v2 series/episodes, CSV for the house and boat twins (see [`opentsx-dwd`](../../opentsx-dwd/README.md)) |
| `modbus` | `org.opentsx.modbus.ModbusCli` | `check\|read\|poll\|simulate --map <file.yaml\|example>`; `read`/`poll`: `[--host h] [--port p] [--unit u] [--simulate]`, `poll`: `[--count n] [--interval PT5S] [--out csv\|episodes] [--file f] [--episode PT1M]`, `simulate`: `[--port 1502] [--bind 127.0.0.1] [--seed 42] [--seconds n]`; reads meters through a YAML register map ([README](../../opentsx-modbus/README.md)); `--simulate` polls an in-JVM simulated meter and labels the data `SYNTHETIC` |
| `market <command>` | `org.opentsx.market.MarketCli` | electricity-market data and the history archive: `prices --source smard\|energy-charts\|entsoe --from D --to D [--out f.csv] [--offline file] [--archive dir]`, `series --name source/series ...`, `rebap ...`, `marktwert ...`, `import --file f.csv --name n --metric m --unit u [--origin] [--partner]`, `backfill [--archive data/history] [--series market\|all\|list] [--from-year 2019] [--to-year Y] [--delay-ms 500] [--force]`, `forward`, `weather-backfill [--from-year 2019] [--stations id:param,...]`, `weather-forward`, `mosmix-archive [--stations ...]`, `report`, `load [--iceberg k=v,... --namespace tsx]`, `catalog`; credentials only from `ENTSOE_TOKEN`, `NTP_CLIENT_ID`, `NTP_CLIENT_SECRET`; see [opentsx-market](../../opentsx-market/README.md) |
| `twin\|house\|boat --history <dir>` | `org.opentsx.market.twin.HistoryTwins` | the twins on archived history: `twin --year Y [--from D --days N] [--wind smard\|dwd] [--cf 0.30]`, `house --weather-year Y\|Y1-Y2 [--levers] [--station id]`, `boat --weather-year Y [--start D] [--days 14]`, `[--sites file.yaml]`; used only when `--history` or `--weather-year` is given |
| `classpath <module>` | — | prints the runtime classpath, e.g. for your own `java -cp` calls |

The container image `opentsx/tools` (see the [DevOps guide](../devops/README.md)) offers the same commands as
`opentsx <command>` (the history twins as `history-twin twin|house|boat ...`), plus `studio-export <out.json>` (Smart Topics Studio data) and `kafscale-check --kafscale
<bootstrap> [--domain d] [--provision true --partitions n]` (reachability of the bootstrap and the advertised broker
addresses, topic provisioning, record counts per topic).

Other scripts in `bin/`:

* `000_validate_environment.sh`: environment check.
* `010_build.sh`: build.
* `000_launch_tsa_workbench.sh`: the 2.x GUI.
* `episode_*.sh`: 2.x guided demos.
* `build_docs_pdfs.sh`: PDF of this manual, via `mvn -Ppdf-manual`.
