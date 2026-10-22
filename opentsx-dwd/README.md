# opentsx-dwd: DWD Open Data collector

This module collects weather data from the Deutscher Wetterdienst (DWD) open data server
([opendata.dwd.de](https://opendata.dwd.de)). It reads forecasts (MOSMIX) and station measurements (CDC), converts
them to v2 series and episodes ([`opentsx-model`](../opentsx-model)) with a unit and an origin, and binds them to
the weather inputs of the twins ([`opentsx-twin`](../opentsx-twin)): house, boat and energy system. It is the
"DWD-Sammler" (weather atom A4) from [`07-DIGITAL-TWIN-BSP.md`](../docs/datamodel-2026/07-DIGITAL-TWIN-BSP.md)
(German).

It uses only the JDK (`java.net.http`, `java.util.zip`, StAX) and has no third-party dependencies.

## Try it

```bash
mvn -q install -pl opentsx-dwd -am -DskipTests
bin/opentsx dwd stations --near 50.6,13.0                 # MOSMIX stations near the house (Erzgebirge)
bin/opentsx dwd stations --near 54.45,13.95 --catalog cdc # observation stations near the Baltic wind farm
bin/opentsx dwd mosmix --station 10578 --out fichtelberg.csv --daily fichtelberg-daily.csv
bin/opentsx dwd obs --station 01358 --param wind,air_temperature,solar --out fichtelberg-obs.csv
bin/opentsx dwd obs --station 01358 --hourly --daily temps.csv   # ~500 days, for: bin/opentsx house --calibrate bills.csv --weather temps.csv
bin/opentsx dwd house --station 10579                     # house twin over the 10-day forecast, weather from DWD
```

Every command takes `--offline <file>` in place of the download, for example a saved `MOSMIX_L_LATEST_10578.kmz`, a
CDC zip or a station list. `obs --offline` takes a comma-separated list of files. Downloads use the JVM proxy
settings, or `HTTPS_PROXY` if those are not set.

| Command | Reads | Prints / writes |
|---------|-------|-----------------|
| `stations --near LAT,LON [--n 5] [--catalog mosmix\|cdc]` | MOSMIX station catalog, or the CDC 10-minute wind station list | the nearest stations by haversine distance |
| `mosmix --station ID` | `MOSMIX_L_LATEST_<id>.kmz` | the series (metric, aggregation, unit, origin, seriesId); with `--out`, `--daily` and `--boat`, CSV files |
| `obs --station CDC_ID [--param ...] [--hourly]` | CDC `10_minutes/<param>/now` zips (default), or `hourly/<param>/recent` zips | as above |
| `house --station ID` | MOSMIX | a shadow run of `haus-erzgebirge` over the forecast horizon: daily outdoor temperature, irradiation, heat request and origin |

MOSMIX ids (`10578` Fichtelberg, `10579` Marienberg, `10091` Arkona) are not the same as CDC ids (`01358`
Fichtelberg, `03166` Marienberg, `00183` Arkona). Look them up with `stations --catalog mosmix|cdc`.

## Sources and conversion

| Source | Format | Elements / columns → variable (unit) | Origin |
|--------|--------|--------------------------------------|--------|
| MOSMIX_L, `weather/local_forecasts/mos/MOSMIX_L/single_stations/<id>/kml/MOSMIX_L_LATEST_<id>.kmz` | KMZ (zip) containing a KML. `dwd:IssueTime`, `dwd:ForecastTimeSteps` (UTC), one `dwd:Forecast` per element with one value per step (`-` = undefined) | `FF` wind speed (m/s), `DD` direction (deg), `TTT` K → °C, `PPPP` Pa → hPa, `N` cloud cover (%), `Rad1h` kJ/m² per hour → mean W/m², `RR1c` kg/m² per hour → mm | FORECAST |
| CDC 10 minutes, `climate_environment/CDC/observations_germany/climate/10_minutes/{wind,air_temperature,solar,precipitation}/now/` | zip with `produkt_*.txt`: `STATIONS_ID;MESS_DATUM;QN;...;eor`, `MESS_DATUM` = `yyyyMMddHHmm` UTC (MEZ before 2000), `-999` = missing | `FF_10`, `DD_10`, `TT_10`, `RF_10`, `PP_10`, `GS_10`/`DS_10` J/cm² → W/m², `RWS_10` | MEASURED |
| CDC hourly, `.../hourly/{air_temperature,wind,extreme_wind,pressure,precipitation}/{historical,recent}/`, `.../hourly/solar/` | the same, `MESS_DATUM` = `yyyyMMddHH`; the solar product measures in true solar time and stamps the UTC moment its hour ends, `yyyyMMddHH:mm` (rounded to the nearest full hour, so up to 30 minutes off) | `TT_TU`, `RF_TU`, `F`, `D`, `FX_911` (gust, max), `P0`; `FG_LBERG`/`FD_LBERG` (solar) J/cm² → W/m², `R1` | MEASURED |
| MOSMIX station catalog (`mosmix_stationskatalog.cfg`) | fixed-width `ID ICAO NAME LAT LON ELEV`; LAT/LON in **degrees.minutes** (50.26 = 50°26′), converted to decimal degrees | – | – |
| CDC station list (`*_Beschreibung_Stationen.txt`) | ISO-8859-1, `Stations_id von_datum bis_datum Stationshoehe geoBreite geoLaenge Stationsname Bundesland Abgabe` | – | – |

Time handling follows the wind-farm normalizer ([`opentsx-windpark`](../opentsx-windpark)):

* All times are UTC.
* Values that cover an interval before their time stamp are moved to the **interval start** and their series gets
  an `aggregation` tag. Examples: `Rad1h` and `RR1c` (`mean.PT1H`, `sum.PT1H`), 10-minute means and sums
  (`mean.PT10M`), hourly wind (`mean.PT1H`).
* Temperature, humidity and pressure are instantaneous values.
* Missing values become `NaN`, and the episode marks them with quality byte 2 (MISSING). Rows missing from a CDC
  file are filled the same way, so every series is regular.

## v2 model

| Item | Value |
|------|-------|
| Series key | `weather.<variable>{source=dwd, product=mosmix-l\|cdc-10min\|cdc-hourly, station=<id>[, aggregation=..]}`, unit as UCUM (`m/s`, `deg`, `Cel`, `hPa`, `%`, `W/m2`, `mm`) |
| Forecast episodes | one episode per run and series (segmentation MANUAL `{unit: forecast-run}`); label `forecast.reference_time` = issue time; lead time = valid time − reference time |
| Observation episodes | windows of fixed length aligned to the epoch (`WeatherSeries.toEpisodes(Duration)`, CLI: one UTC day), FIXED_DURATION |
| Labels | `dwd.origin` (FORECAST / MEASURED), `dwd.element` (`FF`, `TT_10`, ...); `source = dwd-opendata`, `pipeline = dwd-collector:<product>` |
| Episode ids | deterministic (series, start, reference time), so collecting the same data twice gives the same ids |

Forecast runs of the same series overlap in time. Because the reference time is a label and not a tag, all runs
stay in one series. A consumer picks a run, for example the newest one, or the newest one issued before a decision
time ("as issued").

## Feeding the twins

`TwinWeather` resamples a series onto the plan grid and binds it to input ports. Within a step it takes the mean;
it holds a value for at most one source interval. The binding carries the series' origin, so the twin's origin
propagation shows which outputs rest on forecasts and which on measurements.

```java
List<WeatherSeries> fc = Mosmix.read(Path.of("MOSMIX_L_LATEST_10579.kmz")).series("10579");
SeriesInputs in = TwinWeather.bind(plan, fc, TwinWeather.housePorts("weather"));   // t_out_meas_c, ghi_meas_w_m2
SeriesInputs boat = TwinWeather.bind(boatPlan, obs, TwinWeather.boatSolarPorts("solar")); // ghi_wm2
```

The CSV exports match the import formats the twin demos already have:

| Option | Columns | Read by |
|--------|---------|---------|
| `--out` | `time_utc,station,product,origin,reference_time,lead_h,<variable columns>` | any tool; one row per station, run and time |
| `--daily` | `datum,t_mittel_c` (local days, Europe/Berlin; a day needs 80 % of its samples) | `bin/opentsx house --calibrate bills.csv --weather file.csv` |
| `--boat` | `zeit,einstrahlung_wm2` | `bin/opentsx boat --data file.csv`. The boat import treats the column as measured, so use observations here |

## Tests

The tests run offline against small hand-made fixtures in `src/test/resources/dwd/`. These fixtures follow the real
file layouts: a MOSMIX KML with 6 steps (also zipped into a KMZ in the test), 10-minute wind, temperature and solar
files with `-999` values and a missing row, two days of hourly temperatures, and excerpts of both station lists.
Unchanged excerpts of real files (CC BY 4.0, Deutscher Wetterdienst) cover the other hourly products: wind
(`F`, `D`), gusts (`FX_911`), pressure (`P0`) and precipitation (`R1`) of 01759 on 2025-10-01, and global and
diffuse radiation in true solar time (`FG_LBERG`, `FD_LBERG`) of 00183 on 2025-07-01.
They cover parsing, unit and time conversion, nearest-station search, series identity, episodes, the twin bindings
(including a shadow run of the house twin with origin FORECAST) and the CLI.

The parsers were also checked against live downloads from opendata.dwd.de in October 2026: MOSMIX_L for 10578 and
10579 (247 hourly steps), the MOSMIX catalog (5649 stations; it contains one entry whose minutes are 60 or more,
which is accepted), the CDC 10-minute wind, temperature and solar files and the hourly temperature and wind files
for 01358, and the CDC station list.

## Not yet

* A scheduled collector (routine or KafScale producer to `tsx.weather.*` topics) and a Germany-wide archive.
* ICON grid forecasts; MOSMIX_S (the parser reads it, but there are no tests for it); ensemble or probability
  elements.
* A wind power path (wind speed → power curve → `generation.p_avail_kw`); today wind speed is a series only.
* Bias correction per lead time, and a forecast-quality KPI against observations.
