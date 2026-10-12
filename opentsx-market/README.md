# opentsx-market — market collector, history archive, twins on real history

This module collects **electricity-market data** (day-ahead prices DE-LU, load, wind and solar generation and
forecasts, imbalance price reBAP, monthly market values). It keeps them in a local **history archive** with
manifests and quality reports and loads them as v2 episodes ([`opentsx-model`](../opentsx-model)) into a store. It
also drives the digital twins of [`opentsx-twin`](../opentsx-twin) with **real history** instead of synthetic
inputs: a real year of prices and wind for the H2 chain, real temperatures for the house, and real irradiance for the boat.

Everything runs on the JDK HTTP client and Jackson. No new heavy dependencies. All tests run offline on small
checked-in fixtures.

## 1. Sources

Checked on 2026-10-12. Re-check the terms before you redistribute an archive.

| Source | What | Access | Licence / attribution |
|--------|------|--------|-----------------------|
| **SMARD** (Bundesnetzagentur), `www.smard.de/app/chart_data` | day-ahead price DE-LU (filter 4169), grid load (410), residual load (4359), generation wind onshore (4067), wind offshore (1225), solar (4068), forecasts (123, 3791, 125, 411); hourly and quarter-hourly | no registration; JSON index of weekly files + data files | **CC BY 4.0**, "Bundesnetzagentur \| SMARD.de" |
| **Energy-Charts** (Fraunhofer ISE), `api.energy-charts.info/price` | day-ahead price, used as an alternative and cross-check | no registration | **CC BY 4.0**; the price data itself is SMARD's (the API says so in `license_info`) |
| **ENTSO-E Transparency Platform**, `web-api.tp.entsoe.eu/api` | day-ahead prices A44 (incl. 15-min MTU), actual load A65/A16, day-ahead wind/solar forecast A69/A01 (`psrType` B19/B18/B16) | personal **security token** (free registration, then request Web API access), env `ENTSOE_TOKEN`; without it the source is skipped with a message | ENTSO-E terms and conditions: reuse with attribution "Source: ENTSO-E Transparency Platform" |
| **netztransparenz.de** (50Hertz, Amprion, TenneT, TransnetBW), `ds.netztransparenz.de/api/v1/data` | reBAP quality-assured (`NrvSaldo/reBAP/Qualitaetsgesichert`, CSV format 9), monthly market values (`marktpraemie`, format 12: MW-EPEX, wind onshore, wind offshore, solar, controllable) | **OAuth 2.0 client credentials** (API user + client in the OAuth manager of the extranet), env `NTP_CLIENT_ID`, `NTP_CLIENT_SECRET`; token URL `identity.netztransparenz.de/users/connect/token`; at most 2 requests/s per IP (repeated violations block the IP for 2 h). Without credentials: download the CSV from the web page and import it with `--offline` | terms of use of netztransparenz.de; keep the archive local |
| **EPEX SPOT intraday** | intraday prices | **licensed, not collected.** Partner data (direct-marketer schedules, realised prices) comes in through the file import | per contract |
| **DWD** Climate Data Center and MOSMIX, `opendata.dwd.de` | station observations (hourly / 10-min) and MOSMIX_L forecasts | no registration | **CC BY 4.0** (DWD terms, May 2024), "Deutscher Wetterdienst" |

**Hosts to allow** in a restricted network: `www.smard.de`, `api.energy-charts.info`, `web-api.tp.entsoe.eu`,
`ds.netztransparenz.de`, `identity.netztransparenz.de`, `opendata.dwd.de`. All of them were reachable from the
build environment through its proxy. The JVM proxy settings (`https.proxyHost` …) are honoured.

## 2. Time, units, origin

* **UTC internally.** Every value is an interval: start in UTC plus its length. Local times are converted only at
  the edges: CSV downloads with CET/CEST columns, partner files with offsets, Marktwerte months (local midnight to
  local midnight, so March has 31 days minus one hour).
* **DST days** have 23 or 25 hours. Parsers use fixed offsets per row, so the doubled hour in October is
  unambiguous. A local timestamp without an offset inside that hour is rejected, not guessed. The quality report
  checks that each switch day is covered for its real length.
* **15-minute MTU from delivery day 2025-10-01** (2025-09-30T22:00Z). The archive keeps the price in its native
  resolution: hourly before, quarter-hourly after. Before the switch, SMARD's quarter-hour price file repeats each
  hourly price four times; the collector does not read it for that period.
* **Units:** prices EUR/MWh (Marktwerte converted from ct/kWh × 10), powers MW. SMARD publishes generation and
  load as **MWh per interval**; the parser converts them to the average MW of the interval.
* **Origin:** market results and TSO publications are `REPORTED`, published forecasts are `FORECAST`, and station
  measurements are `MEASURED`. The origin goes into the manifest, into each episode's label `origin`, and into the
  twin inputs.

## 3. Command line

```bash
bin/opentsx market catalog                                     # known series (source/name, metric, unit, origin)
bin/opentsx market prices --source smard --from 2025-09-29 --to 2025-10-03 --out /tmp/p.csv
bin/opentsx market prices --source energy-charts --from 2024-03-31 --to 2024-04-01
bin/opentsx market prices --source entsoe --from 2025-10-01 --to 2025-10-02        # needs ENTSOE_TOKEN
bin/opentsx market series --name smard/forecast_wind_onshore --from 2024-01-01 --to 2024-02-01
bin/opentsx market rebap --from 2024-01-01 --to 2024-02-01                         # needs NTP_CLIENT_*
bin/opentsx market rebap --offline reBAP-download.csv --from 2024-01-01 --to 2025-01-01 --archive data/history
bin/opentsx market marktwert --offline marktwerte.csv --from 2019-01-01 --to 2026-01-01 --archive data/history
bin/opentsx market import --file config/private/fahrplan.csv --name schedule-dm --metric market.schedule \
    --unit kW --origin REPORTED --partner direktvermarkter --archive data/history
```

`--from`/`--to` take a local date (Europe/Berlin, start of day) or an ISO instant. `--to` is exclusive. `--offline`
parses a downloaded file in the source's format. `--archive` merges the result into the archive year files.

**Partner files** (`import`) have a header and the columns `start` (ISO-8601 with offset or `Z`), optional `end`,
and `value`. They use `,` or `;` as separator; with `;` a decimal comma is allowed. Without `end`, pass
`--resolution PT15M`. Local timestamps need `--zone Europe/Berlin` and are rejected inside the doubled hour.
Keep real partner data in the git-ignored `config/private/`.

## 4. History archive ("Datenpakete")

```
data/history/                                   (git-ignored)
  smard/day_ahead_price/2024.csv.gz            one gzip'ed CSV per series and UTC year
  smard/day_ahead_price/manifest.json          what, where from, licence, per year: file, SHA-256, rows, queries, quality
  netztransparenz/rebap_short/...
  dwd-cdc/03166/air_temperature/2023.csv.gz    weather: station sub-directory (filled by the DWD backfill)
  dwd-mosmix/raw/10579/2026-10-12T090000Z.kmz  raw MOSMIX_L forecasts + raw/index.tsv
```

Each year file contains `t_utc,dt_s,value` (interval start in UTC, interval length in seconds). The writer goes
through a temporary file and an atomic move, and gzip carries no timestamp, so the SHA-256 depends only on the
content. The reader also accepts plain `<year>.csv` (hand-made slices, test fixtures).

The **manifest** (`opentsx-history/1`, example: [`history-manifest-example.json`](../docs/devops/history-manifest-example.json))
records per year: file, rows, bytes, SHA-256, first/last interval, retrieval time, `complete`, the number of
queries and the first and last query (tokens redacted), and the **quality report**:

* coverage and gaps: up to 50 listed, plus the total count and the missing hours;
* resolutions found (e.g. `PT1H=6550, PT15M=8840` for the 2025 price);
* the two DST days with expected and covered hours, and the number of incomplete local days;
* implausible values outside the metric's range (day-ahead −500…4000 EUR/MWh, powers ≥ 0, …) and robust
  outliers (`|v − median| > 8 · 1.4826 · MAD`); these are flagged, not removed;
* min/max/mean/std and p01/p50/p99 from `opentsx-stats` (`StatsAccumulator` with quantile sketch).

### Backfill

```bash
bin/opentsx market backfill --archive data/history --series market --from-year 2019     # default set, resumable
bin/opentsx market backfill --series smard/day_ahead_price,smard/wind_onshore --from-year 2019 --to-year 2025
bin/opentsx market forward  --archive data/history                                      # daily: running + previous year
bin/opentsx market report   --archive data/history                                      # manifests, checksums
bin/opentsx market load     --archive data/history --iceberg type=jdbc,uri=jdbc:sqlite:/tmp/tsx/catalog.db,warehouse=file:/tmp/tsx/warehouse --namespace tsx
```

* **Resume:** a year marked `complete` whose file still has the recorded SHA-256 is skipped. The running year is
  fetched again, and so is any year fetched before it settled (3 days after year end, 60 days for netztransparenz,
  whose quality-assured data comes late). A changed or missing file is fetched again.
* **Politeness:** a minimum pause between requests per host (`--delay-ms`, default 500 ms; ENTSO-E at least 1 s,
  netztransparenz at least 600 ms), and retries with exponential backoff on network errors, 429 and 5xx, honouring
  `Retry-After`. A failing year is reported and the run continues (exit code 3 if any year failed).
* **Credentials:** a source without credentials is skipped with the reason. Tokens come only from the environment
  and are never written to the manifest.
* **Load:** `load` maps the archive to REGULAR episodes, one per UTC day. Episodes split where the resolution
  changes or data is missing, so a gap is never filled. They are written to Iceberg (same `--iceberg` options as
  `bin/opentsx windpark-sim`) or to an in-memory store (dry run with counts).

The default set `market` is: SMARD price, load, residual load, wind onshore/offshore, solar, and their forecasts
(plus load forecast); the Energy-Charts price as a cross-check; the ENTSO-E price, load and forecasts; reBAP
(both sides); Marktwerte EPEX, wind onshore, wind offshore and solar.

### Weather and forward archiving

```bash
bin/opentsx market weather-backfill --archive data/history --from-year 2019     # all stations of the sites file
bin/opentsx market weather-backfill --stations 03166:air_temperature,01358:global_radiation --from-year 2022
bin/opentsx market weather-forward  --archive data/history                      # daily: running + previous year
```

The weather backfill reads the DWD CDC observations of the twin sites' stations
([`history-sites.yaml`](src/main/resources/market/history-sites.yaml)) with the parser of
[`opentsx-dwd`](../opentsx-dwd/README.md) (`CdcObservations`). It writes them into the same layout
(`dwd-cdc/<station>/<parameter>/`, origin `MEASURED`, licence CC BY 4.0 DWD):

| Parameter | Product | Unit |
|-----------|---------|------|
| `air_temperature` | hourly `TT_TU` (`hourly/air_temperature/{historical,recent}`) | °C, stored as the hour starting at the measurement |
| `wind_speed` | hourly mean `F` (`hourly/wind/...`) | m/s, interval start |
| `global_radiation` | 10-minute `GS_10` (`10_minutes/solar/...`), J/cm² converted to the mean W/m² | W/m², interval start |

The zips come from the directory listings. A `historical` file name carries its date range, so only the zips
overlapping the requested years are read, and nothing is downloaded when every year is already complete. `recent`
(about the last 500 days) overrides `historical` where both have a value.

Default stations: house Marienberg 03166 (temperature, wind) and Fichtelberg 01358 (radiation); wind park
Greifswalder Oie 01759 and Arkona 00183; boat Kiel-Holtenau 02564 and Dörnick 06163. Two nearer candidates were
checked and dropped: Chemnitz 00853 has no radiation from April 2025 to March 2026, and Leuchtturm Kiel 02961
measures only sunshine duration.

The DWD does **not** keep historical MOSMIX forecasts on opendata.dwd.de, only the latest runs. Forecast history
therefore exists only from the day collecting starts. `bin/opentsx market mosmix-archive` stores the raw
MOSMIX_L KMZ of the sites' MOSMIX stations: 10579 Marienberg, 10097 Greifswalder Oie, 10046 Kiel-Holtenau, E5411
Kieler Bucht. It keeps each issue once, with an index line (issue, file, SHA-256, retrieval time, licence). Run it
every 6 hours; MOSMIX_L is issued at 03, 09, 15 and 21 UTC. Parsing into series is left to `opentsx-dwd`. On k3s:
[History archive](../docs/devops/history-archive.md).

## 5. Twins on real history

| Command | What changes compared with the synthetic demo |
|---------|-----------------------------------------------|
| `bin/opentsx twin --history data/history --year 2024 [--from 2024-03-01 --days 31] [--wind smard\|dwd] [--cf 0.30]` | H2 chain (plan [`h2-chain-history.yaml`](src/main/resources/twin-history/h2-chain-history.yaml), 15-min step) on the real day-ahead price (REPORTED). The wind comes from the national onshore generation scaled to the 12 MW park (mean capacity factor `--cf`), or from the park's DWD station through a power curve (`--wind dwd`); both ESTIMATED. The marketer's schedule is the day-ahead wind forecast minus the electrolyzer plan (FORECAST), so its deviation is the real forecast error. The electrolyzer follows a price-limit rule (SYNTHETIC). What-ifs: run always, or in the cheapest 60/40/20 % of steps. |
| `bin/opentsx house --history data/history --weather-year 2022-2025 [--levers] [--station 03166] [--radiation-station 01358]` | house plan of each year from local 1 January, `weather.t_out_meas_c` (and `ghi_meas_w_m2` if archived) from the DWD stations (MEASURED); heating degree days, oil, wood, cost and CO₂ per year, optional levers on each year's weather |
| `bin/opentsx boat --history data/history --weather-year 2024 [--start 2024-07-06] [--days 14] [--radiation-station 06163]` | the 14-day trip of the example from the first Saturday in July, `solar.ghi_wm2` from the sailing area's station (MEASURED) instead of the synthetic clearness cycle; KPIs with measured vs. synthetic irradiance and the usual what-ifs |

`bin/opentsx` dispatches to `org.opentsx.market.twin.HistoryTwins` only when `--history` or `--weather-year` is
given. Without them the demos of `opentsx-twin` run unchanged on synthetic data. The adapter
(`HistoryInputs`) resamples archive series to the plan's base step as a time-weighted mean: finer data is averaged,
coarser data (hourly prices, monthly values) is held. A step less than half covered becomes NaN, so the twin falls
back to its default for that step and marks it SYNTHETIC instead of using a guess. The core of `opentsx-twin` is
not changed; the adapter only builds `SeriesInputs` and plan variants.

Results are **modelled, not proven**. The dispatch rule, the park scaling and the house and boat parameters are
assumptions; prices, wind situations, temperatures and irradiance are real.

## 6. Status (2026-10-12)

* Collected into the local archive (not in git): SMARD 2019 to today for every series of the default set, the
  Energy-Charts price as a cross-check, and the DWD observations of all site stations 2019 to today (see
  `bin/opentsx market report`).
* Blocked: ENTSO-E (no `ENTSOE_TOKEN` in the build environment) and netztransparenz reBAP/Marktwerte (no OAuth
  client). Both clients are implemented and tested against fixtures; with credentials, `backfill` fills them, and
  without them the CSV downloads can be imported with `--offline`.
* MOSMIX: the raw forward archive runs from 2026-10-12. Earlier forecasts cannot be recovered from the DWD.

## 7. Tests

`mvn -pl opentsx-market test`, all offline: SMARD (MTU switch, MWh→MW, `null`), Energy-Charts (23-hour DST
day), ENTSO-E (A03 blocks, two resolutions, acknowledgement, no DOCTYPE/XXE), reBAP (API and download formats
across the end of DST), Marktwerte (local months), partner CSV (doubled hour), OAuth flow and token reuse,
archive round trip and checksums, quality report (gaps, DST days, implausible values), episode mapping, backfill
resume, weather backfill (listing, year filter, recent over historical, resume), MOSMIX raw archive, resampling, and the three twins on fixture slices
([`src/test/resources/market/`](src/test/resources/market/README.md)).
