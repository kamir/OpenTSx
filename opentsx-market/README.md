# opentsx-market: market collector, history archive, twins on real history

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
| **ENTSO-E Transparency Platform**, `web-api.tp.entsoe.eu/api` | day-ahead prices A44 (incl. 15-min MTU), actual load A65/A16, day-ahead wind/solar forecast A69/A01 (`psrType` B19/B18/B16) | personal **security token** (free registration, then request Web API access), key `opentsx.market.entsoeToken` (env `OPENTSX_MARKET_ENTSOE_TOKEN` or `ENTSOE_TOKEN`); without it `backfill` and `forward` skip the source with a message | ENTSO-E terms and conditions: reuse with attribution "Source: ENTSO-E Transparency Platform" |
| **netztransparenz.de** (50Hertz, Amprion, TenneT, TransnetBW), `ds.netztransparenz.de/api/v1/data` | reBAP quality-assured (`NrvSaldo/reBAP/Qualitaetsgesichert`, CSV format 9), monthly market values (`marktpraemie`, format 12: MW-EPEX, wind onshore, wind offshore, solar, controllable) | **OAuth 2.0 client credentials** (API user + client in the OAuth manager of the extranet), keys `opentsx.market.ntpClientId` and `opentsx.market.ntpClientSecret` (env `NTP_CLIENT_ID`, `NTP_CLIENT_SECRET` or their `OPENTSX_MARKET_` names); token URL `identity.netztransparenz.de/users/connect/token`; at most 2 requests/s per IP (repeated violations block the IP for 2 h). Without credentials: download the CSV from the web page and import it with `--offline` | terms of use of netztransparenz.de; keep the archive local |
| **EPEX SPOT intraday** | intraday prices | **licensed, not collected.** Partner data (direct-marketer schedules, realised prices) comes in through the file import | per contract |
| **DWD** Climate Data Center and MOSMIX, `opendata.dwd.de` | station observations (hourly / 10-min) and MOSMIX_L forecasts | no registration | **CC BY 4.0** (DWD terms, May 2024), "Deutscher Wetterdienst" |

**Hosts to allow** in a restricted network: `www.smard.de`, `api.energy-charts.info`, `web-api.tp.entsoe.eu`,
`ds.netztransparenz.de`, `identity.netztransparenz.de`, `opendata.dwd.de`.

**Proxy.** Downloads use the JVM proxy settings (`https.proxyHost`, `https.proxyPort`) when `https.proxyHost` is set.
Otherwise they use the key `opentsx.https.proxy`, the same key as in [`opentsx-dwd`](../opentsx-dwd/README.md#download-through-a-proxy),
set as `<host>:<port>` or `http://<host>:<port>`:

```bash
export OPENTSX_HTTPS_PROXY=<host>:<port>
```

`<host>:<port>` is the address of your HTTPS proxy. `HTTPS_PROXY` and `https_proxy` are legacy names of the same key.
The key is a secret: the command line refuses `--conf opentsx.https.proxy=...`. A value without a host stops the
command with exit code 2 before the first download, also when `https.proxyHost` is set:

```text
opentsx: opentsx.https.proxy: not a proxy address; expected <host>:<port> or http://<host>:<port>
```

## 2. Time, units, origin

* **UTC internally.** Every value is an interval: start in UTC plus its length. Local times are converted only at
  the edges: CSV downloads with CET/CEST columns, partner files with offsets, market-value months (local midnight to
  local midnight, so March has 31 days minus one hour).
* **DST days** have 23 or 25 hours. Parsers use fixed offsets per row, so the doubled hour in October is
  unambiguous. A local timestamp without an offset inside that hour is rejected, not guessed. The quality report
  checks that each switch day is covered for its real length.
* **15-minute MTU from delivery day 2025-10-01** (2025-09-30T22:00Z). The archive keeps the price in its native
  resolution: hourly before, quarter-hourly after. Before the switch, SMARD's quarter-hour price file repeats each
  hourly price four times; the collector does not read it for that period.
* **Units:** prices EUR/MWh (market values converted from ct/kWh × 10), powers MW. SMARD publishes generation and
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
bin/opentsx market marktwert --offline market-values.csv --from 2019-01-01 --to 2026-01-01 --archive data/history
bin/opentsx market import --file config/private/schedule.csv --name schedule-dm --metric market.schedule \
    --unit kW --origin REPORTED --partner direct-marketer --archive data/history
```

Every command reads its keys through the configuration registry. `bin/opentsx market <command> --help` lists the
keys of the command with their flags, defaults and environment variables. A key can also come from
`--conf <key>=<value>`, `--properties-file <file>` or `OPENTSX_CONF_DIR`. A boolean flag alone means `true`;
`--requality false` and `--force false` switch it off.

`--from`/`--to` take a Berlin market day: a local date (Europe/Berlin, start of day) or an ISO instant. `--to` is
exclusive and must be after `--from`. `--from-day` and `--to-day` of `load` and `publish` take a UTC day instead. `--offline` parses a
downloaded file in the source's format.

`--store` decides whether `prices`, `series`, `rebap` and `marktwert` merge the result into the archive year files:
`always`, `never`, or `auto` (default), which stores when `--archive` is on the command line. An archive directory
from `OPENTSX_ARCHIVE_DIR`, a properties file or a defaults file does not store with `auto`.

Exit codes: 0 success, 1 runtime failure, 2 usage or configuration error. A failure is one line `opentsx: <message>`
on stderr; stdout stays empty for a usage or configuration error. Exit code 2 covers an unknown or malformed option,
a missing input file, a range whose end is not after its start, and a credential that a fetch needs and that is not
set:

```text
opentsx: --form-year: unknown option of market backfill; did you mean --from-year?
opentsx: --from: "2024-13-01" is not a date; expected a local date YYYY-MM-DD (Europe/Berlin) or an ISO instant such as 2024-01-01T00:00:00Z
opentsx: --to: "2025-01-01" is not after --from "2025-02-01"; expected an end after the start
opentsx: opentsx.market.entsoeToken: not set; set OPENTSX_MARKET_ENTSOE_TOKEN or ENTSOE_TOKEN or put opentsx.market.entsoeToken in a properties file, or parse a download with --offline
```

Exit code 1 covers a download that fails after its retries, an HTTP error status, a rejected credential, a backfill
year that failed and a publish the broker did not confirm. A request that the source answers with an HTTP error
status prints `opentsx: HTTP <status> for <URL>`, followed by `: <response text, cut at 300 characters>` when the
response has text. The URL shows the ENTSO-E token as `securityToken=***`. For an ENTSO-E acknowledgement answered
with HTTP 400 or HTTP 200, the response text is the code and text of its reasons. A rejected ENTSO-E token (HTTP 401)
or netztransparenz.de client prints a line that names the keys and variables of the credential to check instead:

```text
opentsx: opentsx.market.ntpClientId, opentsx.market.ntpClientSecret: rejected by netztransparenz.de (HTTP 401); check the client in OPENTSX_MARKET_NTP_CLIENT_ID and OPENTSX_MARKET_NTP_CLIENT_SECRET, NTP_CLIENT_ID and NTP_CLIENT_SECRET or the properties file
```

The token endpoint of netztransparenz.de counts as rejecting the client only with HTTP 400 or 401. Any other error
status, such as a 503 after the retries, prints a line without the response text, which could repeat the client
secret. In a backfill that year fails and the next year is tried:

```text
opentsx: netztransparenz token: HTTP 503 from https://identity.netztransparenz.de/users/connect/token; the service failed, try again later
```

**Partner files** (`import`) have a header and the columns `start` (ISO-8601 with offset or `Z`), optional `end`,
and `value`. They use `,` or `;` as separator; with `;` a decimal comma is allowed. Without `end`, pass
`--resolution PT15M`. Local timestamps need `--zone Europe/Berlin` and are rejected inside the doubled hour.
`--origin` takes `REPORTED` (default) or `FORECAST`; another value exits with 2. `--name` follows the name rule of
the archive (section 4); another name exits with 2. Keep real partner data in the git-ignored `config/private/`.

## 4. History archive

```
data/history/                                   (git-ignored)
  smard/day_ahead_price/2024.csv.gz            one gzip'ed CSV per series and UTC year
  smard/day_ahead_price/manifest.json          what, where from, licence, per year: file, SHA-256, rows, queries, quality
  netztransparenz/rebap_short/...
  dwd-cdc/03166/air_temperature/2023.csv.gz    weather: station sub-directory (filled by the DWD backfill)
  dwd-mosmix/raw/10579/2026-10-12T090000Z.kmz  raw MOSMIX_L forecasts + raw/index.tsv
```

**Name rule.** Every source, series name and station id in an archive path consists of letters, digits, `.`, `_` and
`-`. A series name may have sub-directories separated by `/`; no part is `.` or `..`, and the MOSMIX station ids of
`mosmix-archive` are letters and digits only, so every path stays below the archive directory. A path that breaks the
rule is refused with exit code 2 before anything is written:

```text
opentsx: --name: "../x" is not allowed; expected names of letters, digits, '.', '_' and '-', separated by '/', and none of them '.' or '..'
opentsx: --stations: item "../x" is not a station id; expected letters and digits such as 10579 or E5411
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
  netztransparenz at least 600 ms), and up to 4 retries on network errors, 429 and 5xx, after 2, 4, 8 and 16 s or
  later when a `Retry-After` in seconds asks for it, at most 60 s. A longer `Retry-After` waits 60 s and logs a WARN
  line `Retry-After capped at 60 s url=<URL> status=<status> retryAfterSeconds=<seconds>`; a `Retry-After` date is
  not read. An unknown host and a TLS error are not tried again; the request fails at
  once with `GET <URL> failed after 1 attempt`: on the FAILED line of the year in a backfill
  (`UncheckedIOException: GET <URL> failed after 1 attempt`), and as
  `opentsx: GET <URL> failed after 1 attempt; caused by ...` in `prices`, `series`, `rebap`, `marktwert` and
  `mosmix-archive`. A failing year is reported and the run continues; the command ends with exit code 1 and
  `opentsx: market backfill: <n> of <m> series-years failed; the lines marked FAILED on stdout say why`.
* **ENTSO-E without data:** ENTSO-E answers a month without data with an acknowledgement that says
  `No matching data found`, with HTTP 400 or HTTP 200. That month counts as empty. Any other acknowledgement and any
  other HTTP 400 fail the year; for an acknowledgement, the message shows the code and text of its reasons, for
  example `HTTP 200 for <URL>: 999 The amount of requested data exceeds allowed limit.`
* **Credentials:** a source without credentials is skipped with the reason. The credentials are secret keys: they
  come from the environment or a properties file, never from the command line. They are never written to the
  manifest, and the start line of the log shows them as `******`. In error messages and the log, the value of every
  URL query parameter whose name contains `token`, `secret` or `key` in any letter case shows as `***`, also where
  the response repeats it.
* **Publish:** `publish --series source/name,... --kafscale host:port --domain wind` writes archived series as raw
  episodes into `tsx.<domain>.episodes.v2`, where the retrieval server and the flows of the domain read them. It
  sends a batch (`--batch`, default 100), waits for every confirmation, pauses (`--pause-ms`, default 3000) and
  stops at the first batch the broker does not confirm, with exit code 1. `opentsx.domain` must be set, with
  `--domain` or another source; the registry default `wind` does not count. An episode has an identity that follows
  from its series and the time it begins at, so a repeat writes the same episodes again. `--from-day YYYY-MM-DD`
  skips episodes that begin before that UTC day, `--to-day YYYY-MM-DD` (exclusive) those that begin on or after it.
  A daily job with `--to-day` set to the current day writes complete days only. `--dry-run` only counts.
* **Load:** `load` maps the archive to REGULAR episodes, one per UTC day. Episodes split where the resolution
  changes or data is missing, so a gap is never filled. An episode gets the same identity as in `publish`, so a
  repeated load skips the episodes the table already holds. `--from-day` and `--to-day` limit it as in `publish`.
  `--store` (key `opentsx.market.load.store`, variable `OPENTSX_MARKET_LOAD_STORE`) decides where the episodes go.
  `iceberg` writes them to Iceberg with the catalog properties of `opentsx.iceberg.properties` from any source
  (`--iceberg`, `--conf`, `OPENTSX_ICEBERG_PROPERTIES` or a properties file; same options as
  `bin/opentsx windpark-sim`). `memory` writes them to an in-memory store (dry run with counts). `auto` (default)
  writes to Iceberg only when the command line gives `--iceberg` or `--conf opentsx.iceberg.properties=<list>`; a
  value from `OPENTSX_ICEBERG_PROPERTIES` or a file then does not switch to Iceberg. A Job that takes its keys from a
  ConfigMap switches to Iceberg with `OPENTSX_MARKET_LOAD_STORE=iceberg`. `iceberg` without catalog properties exits with 2:

  ```text
  opentsx: opentsx.iceberg.properties: not set; set it with --iceberg, --conf opentsx.iceberg.properties=<value> or OPENTSX_ICEBERG_PROPERTIES
  ```

The default set `market` is: SMARD price, load, residual load, wind onshore/offshore, solar, and their forecasts
(plus load forecast); the Energy-Charts price as a cross-check; the ENTSO-E price, load and forecasts; reBAP
(both sides); market values EPEX, wind onshore, wind offshore and solar.

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
| `relative_humidity` | hourly `RF_TU` (same files as the temperature) | % |
| `wind_direction` | hourly mean `D` (same files as the wind speed) | deg |
| `wind_speed_10min` | 10-minute mean `FF_10` (`10_minutes/wind/...`) | m/s, interval start |
| `wind_gust` | highest gust of the hour `FX_911` (`hourly/extreme_wind/...`) | m/s |
| `pressure` | hourly `P0` at station level (`hourly/pressure/...`) | hPa |
| `precipitation` | hourly sum `R1` (`hourly/precipitation/...`) | mm, interval start |

The sites file names a station for temperature, wind and radiation; the other six parameters are asked for with
`--stations <id>:<parameter>,...`. A station id has 5 digits, in the sites file and in `--stations`.

`--sites <file>` replaces the built-in sites file, here and in `bin/opentsx twin|house|boat --history`. Under
`sites`, the file maps a site id to these fields:

| Field | Type | Required | Meaning |
|-------|------|----------|---------|
| `latitude`, `longitude` | number | yes | position of the site in degrees |
| `temperature`, `wind`, `radiation` | string | no | DWD CDC station id, 5 digits in quotes (`"03166"`; an unquoted `03166` is a YAML number); without it the site has no station for that variable |
| `anemometerHeightM` | number | no, default 10 | height of the station's wind measurement in m |
| `hubHeightM` | number | no, default 120 | hub height of the park's turbines in m |
| `shearExponent` | number | no, default 0.14 | wind shear exponent from the measurement to the hub height |

An unknown or missing field, a value of the wrong type, a station id that is not 5 digits or a number that is not
finite stops the command with exit code 2 and one line that names the file and the field. A file that is not valid YAML stops the same way and names
the line. A weather backfill that finds no station stops the same way:

```text
opentsx: sites.yaml: sites.windpark-ostsee.hubHeight: unknown field; allowed: anemometerHeightM, hubHeightM, latitude, longitude, radiation, shearExponent, temperature, wind
opentsx: sites.yaml: sites.windpark-ostsee.hubHeightM: not finite; expected a finite decimal number such as 0.01 or 1e-3
opentsx: sites.yaml: sites.windpark-ostsee.wind: wrong type; expected a station id of 5 digits in quotes such as "03166"
opentsx: sites.yaml:4: not valid YAML; while parsing a block mapping, expected <block end>, but found '<block mapping start>'
opentsx: nostation.yaml: sites: no site names a temperature, wind or radiation station; name one, or pass --stations <id>:<parameter>,...
```

The zips come from the directory listings. A `historical` file name carries its date range, so only the zips
overlapping the requested years are read, and nothing is downloaded when every year is already complete. `recent`
(about the last 500 days) overrides `historical` where both have a value.

Default stations: house Marienberg 03166 (temperature, wind) and Fichtelberg 01358 (radiation); wind park
Greifswalder Oie 01759 and Arkona 00183; boat Kiel-Holtenau 02564 and Dörnick 06163.

The DWD does **not** keep historical MOSMIX forecasts on opendata.dwd.de, only the latest runs. Forecast history
therefore exists only from the day collecting starts. `bin/opentsx market mosmix-archive` stores the raw
MOSMIX_L KMZ of the sites' MOSMIX stations: 10579 Marienberg, 10097 Greifswalder Oie, 10046 Kiel-Holtenau, E5411
Kieler Bucht. It keeps each issue once, with an index line (issue, file, SHA-256, retrieval time, licence). Run it
every 6 hours; MOSMIX_L is issued at 03, 09, 15 and 21 UTC. Parsing into series is left to `opentsx-dwd`. On k3s:
[History archive](../docs/devops/history-archive.md).

## 5. Twins on real history

| Command | What changes compared with the synthetic demo |
|---------|-----------------------------------------------|
| `bin/opentsx twin --history data/history --year 2024 [--from 2024-03-01 --days 31] [--wind smard\|dwd] [--cf 0.30]` | H2 chain in its second form (plan [`h2-chain-history.yaml`](src/main/resources/twin-history/h2-chain-history.yaml), 15-min step) on the real day-ahead price (REPORTED). The wind comes from the national onshore generation scaled to the 12 MW park (mean capacity factor `--cf`), or from the park's DWD station through a power curve (`--wind dwd`); both ESTIMATED. The marketer's schedule is the day-ahead wind forecast minus the electrolyzer plan (FORECAST), so its deviation is the real forecast error. The electrolyzer follows a price-limit rule (SYNTHETIC). What-ifs: run always, or in the cheapest 60/40/20 % of steps. |
| `bin/opentsx house --history data/history --weather-year 2022-2025 [--levers] [--station 03166] [--radiation-station 01358]` | house plan of each year from local 1 January, `weather.t_out_meas_c` (and `ghi_meas_w_m2` if archived) from the DWD stations (MEASURED); heating degree days, oil, wood, cost and CO₂ per year, optional levers on each year's weather |
| `bin/opentsx boat --history data/history --weather-year 2024 [--start 2024-07-06] [--days 14] [--radiation-station 06163]` | the 14-day trip of the example from the first Saturday in July, `solar.ghi_wm2` from the sailing area's station (MEASURED) instead of the synthetic clearness cycle; KPIs with measured vs. synthetic irradiance and the usual what-ifs |

`bin/opentsx` dispatches to `org.opentsx.market.twin.HistoryTwins` only when `--history` or `--weather-year` is
given. Without them the demos of `opentsx-twin` run unchanged on synthetic data. The adapter reads its keys as the
commands `history-twin twin`, `history-twin house` and `history-twin boat`; `bin/opentsx twin --history data/history
--help` lists them. `--wind` takes `smard` or `dwd`, and `--levers false` prints no levers. `--days` of `twin` and
`boat` takes 1 to 366, the days of 15-minute steps that one run holds; another value exits with 2:
`opentsx: --days: "400" is not allowed; expected 1 to 366`. Without `--days`, `twin` runs from `--from` to the end of
`--year`, so `--from` must leave 1 to 366 days; another day exits with 2:
`opentsx: --from: "2025-01-01" is not allowed; expected a UTC day from 2024-01-01 to 2024-12-31, so that the run to the end of --year 2024 has 1 to 366 days`.
`--station` and `--radiation-station` take a station id of 5 digits, as the sites file does; another value exits with 2:
`opentsx: --station: "12ab" is not a station id; expected a station id of 5 digits such as 03166`. A missing archive or a
year without archived data stops the command with exit code 2 and a line that says which backfill fills it. The
adapter (`HistoryInputs`) resamples archive series to the plan's base step as a time-weighted mean: finer data is
averaged, coarser data (hourly prices, monthly values) is held. A step less than half covered becomes NaN, so the twin falls
back to its default for that step and marks it SYNTHETIC instead of using a guess. The core of `opentsx-twin` is
not changed; the adapter only builds `SeriesInputs` and plan variants.

Results are **modelled, not proven**. The dispatch rule, the park scaling and the house and boat parameters are
assumptions; prices, wind situations, temperatures and irradiance are real.

## 6. Tests

`mvn -pl opentsx-market test`, all offline: SMARD (MTU switch, MWh→MW, `null`), Energy-Charts (23-hour DST
day), ENTSO-E (A03 blocks, two resolutions, acknowledgement, no DOCTYPE/XXE), reBAP (API and download formats
across the end of DST), market values (local months), partner CSV (doubled hour), OAuth flow and token reuse,
archive round trip, checksums and the name rule, quality report (gaps, DST days, implausible values), episode mapping, backfill
resume, weather backfill (listing, year filter, recent over historical, resume; every parameter to its CDC
directory, column, unit and time convention; default stations and years of the daily jobs), MOSMIX raw archive, resampling, the three twins on fixture slices, the retries of `Http` against a stub HTTP client,
the stop of `publish` against a mock producer, and the command line (keys, exit codes, messages, the jobs of `deploy/k3s/history`)
([`src/test/resources/market/`](src/test/resources/market/README.md)).
