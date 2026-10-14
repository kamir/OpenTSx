# Demos on k3s

Every example from the repository runs in the cluster with one command. `40-demo.sh <demo>` (re)creates a Job from
[`deploy/k3s/jobs/`](../../deploy/k3s/jobs/), follows its log and reports whether it completed. Pods stay for 24 h
(`kubectl -n opentsx logs job/opentsx-<demo>`).

```bash
deploy/k3s/40-demo.sh all     # kafscale-check, windpark-sim, stats, metadata, twin, house, boat, studio-export, dwd,
                              # modbus (when a device is configured), retrieval
```

`deploy/k3s/60-probe.sh` runs the same demos plus the install steps and checks each result (PASS/FAIL report, see
[operations](operations.md#try-it-locally-first-the-probe)).

The outputs below are real outputs of the same commands (taken from the staged image content on a JDK 21 machine).
The twin outputs are deterministic, so you should see the same numbers and run hashes. Lines such as
`Picked up JAVA_TOOL_OPTIONS` and SLF4J logging are left out.

| Demo | Needs | Typical runtime |
|------|-------|-----------------|
| `kafscale-check` | KafScale | seconds |
| `windpark-sim` | KafScale (+ lake) | ~10–30 s |
| `stats` | KafScale, `windpark-sim` before | — (continuous) |
| `metadata` | nothing | ~1 s |
| `metadata-publish` | KafScale, OpenMetadata | seconds |
| `twin`, `house`, `boat` | nothing | 1–20 s |
| `studio-export` | nothing | ~11 s |
| `retrieval`, `studio` | the Deployments | — |
| `dwd` | nothing (fixture in the image) | ~2 s |
| `dwd-online` | HTTPS from the pods to opendata.dwd.de | ~3 s |
| `dwd-forward` | `DWD_FORWARD=true`, internet (or `DWD_MODE=offline`) | ~2 s per station |
| `modbus` | `MODBUS_SIM=true` (or a real `MODBUS_HOST`) | ~35 s (30 cycles at 1 s) |

## KafScale check (do this first)

```bash
deploy/k3s/40-demo.sh kafscale-check
```

Runs `opentsx kafscale-check --kafscale $KAFSCALE_BOOTSTRAP --domain wind --provision true --partitions 6` inside
the cluster: TCP check of the bootstrap, topic provisioning with `TopicLayout.provision` (existing topics stay as they
are), the **advertised** broker addresses with a TCP check each, and the record count per topic. Expected:

```text
bootstrap 192.168.1.50:9092
  bootstrap 192.168.1.50:9092: reachable
provisioned tsx.wind.*.v2 (data partitions 6, existing topics are left unchanged)
topics on the cluster: 5
advertised broker addresses (what clients really connect to):
  advertised 192.168.1.50:9092: reachable
domain wind:
  tsx.wind.episodes.v2                  6 partitions             0 records
  tsx.wind.buckets.v2                   1 partitions             0 records
  tsx.wind.series.v2                    1 partitions             0 records
  tsx.wind.observations.v2              6 partitions             0 records
  tsx.wind.series-stats.v2              6 partitions             0 records
OK
```

`advertised localhost:9092: NOT reachable` means the broker advertises an address the pods cannot use: see
[setup plan, step 3](setup-plan.md#step-3-network-path-to-kafscale). (The success path was run through the image
entrypoint against a live KafScale broker built from source with in-memory S3 - from the host, not from a pod; the
failure path was run earlier against an unreachable address.)

## Wind farm simulator → KafScale

```bash
deploy/k3s/40-demo.sh windpark-sim
```

Runs `opentsx windpark-sim --scenario /opt/opentsx/share/scenarios/ostsee-demo.yaml --kafscale … --domain wind
--partitions 6 --index /data/wind-index.tsv --ground-truth /data/ground-truth.json` (lake profile: plus
`--iceberg … --namespace tsx`): 10 turbines × 10 channels, 6 h at 1 Hz, with curtailment, a fault, maintenance and
five sensor faults. Then it restarts `opentsx-stats` so the processor assigns the partitions. Output:

```text
bucket 01M4QZS8J3CT5Q37CH8B2X56VE sealed: 3600 episodes, checksum 848ac31ffbf595a0
scenario ostsee-demo: 3,600 episodes, 2,160,000 values, 17 ground-truth events
  T01    4538.4 kWh  mean wind  7.51 m/s  states {1=21310, 2=1, 3=289}
  T02    2927.4 kWh  mean wind  6.50 m/s  states {1=21503, 2=97}
  T03    2707.9 kWh  mean wind  6.40 m/s  states {1=19656, 2=144, 4=1800}
  T04    2917.8 kWh  mean wind  6.49 m/s  states {1=21531, 2=69}
  T05    2781.0 kWh  mean wind  6.38 m/s  states {1=21526, 2=74}
  T06    4498.2 kWh  mean wind  7.48 m/s  states {1=21345, 2=3, 3=252}
  T07    2948.2 kWh  mean wind  6.50 m/s  states {1=21512, 2=88}
  T08    2289.8 kWh  mean wind  6.38 m/s  states {1=17925, 2=75, 5=3600}
  T09    2978.3 kWh  mean wind  6.53 m/s  states {1=21508, 2=92}
  T10    2756.4 kWh  mean wind  6.35 m/s  states {1=21495, 2=105}
```

Captured against the live KafScale broker mentioned above (the per-turbine numbers are identical to the earlier
capture with the Iceberg sink). A rerun writes the same episodes again; readers drop the duplicates by `episodeId`.
Afterwards `kafscale-check` shows `tsx.wind.episodes.v2 … 3,600 records` (plus 3,600 per further run) and
`tsx.wind.buckets.v2 … 2 records` (OPEN and SEALED manifest).

## Statistics processor

```bash
deploy/k3s/40-demo.sh stats
```

Shows the Deployment, its last log lines, the checkpoint file on the PVC and runs `kafscale-check` for the record
counts. The processor is continuous (`stats --kafscale … --checkpoint /state/stats.ckpt --windows PT10M,PT1H`) and
prints nothing in normal operation. After the demo run, `tsx.wind.series-stats.v2` holds at least 4,200 closed-window
records (100 series × (36 ten-minute + 6 hourly windows)) plus 100 GLOBAL records per checkpoint (every 30 s;
outputs are at-least-once, readers keep the latest record per key). Measured against the live broker: 4,300 records
after the first checkpoint, and a checkpoint file of 135 KB for the 100 series. Data-quality checks evaluated on these statistics
(e.g. by `metadata-publish`) find the injected stuck anemometer and outage in exactly the affected windows
([chapter 7](../user-manual/07-stats-quality.md)).

## OpenMetadata (optional)

```bash
deploy/k3s/40-demo.sh metadata            # dry run: request JSONs on the data PVC under /data/om-requests
deploy/k3s/40-demo.sh metadata-publish    # real: needs OM_URL + OPENMETADATA_JWT (20-configure.sh)
```

Dry-run output:

```text
published 39 entities for wind farm ost-01
```

and 52 request files (`0001-PUT_v1_classifications.json`, `0002-PUT_v1_tags.json`, …). `metadata-publish` also reads
the latest statistics from KafScale and publishes profiles and data-quality results.

## Digital twin S1: H2 chain

```bash
deploy/k3s/40-demo.sh twin
```

```text
1. Shadow: observed history (valve closed at 01:00)
   run h2-chain-6734f91571b28b83  mode SHADOW  hash f2d8da6decd0a083
   01:00:00  handover   intervention             {author=h2-handover, reason=valve closed, control=valve, value=0.0}
   01:29:00  buffer     time_to_limit_warning    {ttl_min=59.906, p_bar=146.095}
   02:28:54  buffer     pressure_limit           {p_max_bar=200.000}
   02:29:00  ely        protective_shutdown      {p_el_kw=5000.000, p_bar=200.084}
   02:29:00  marketer   schedule_deviation       {deviation_kw=467.897}
   04:00:00  rules      restriction_start        {reason=bat curtailment, limit_kw=6000.000}
   05:00:00  park       intervention             {author=operator, reason=dispatch reduced, control=dispatch_kw, value=5000.0}
   05:00:00  rules      restriction_end          {}
   05:30:00  park       intervention             {author=operator, reason=dispatch released, control=dispatch_kw, value=release}
   max buffer pressure 205.03 bar, electrolyzer trips 1, H2 232.6 kg, extra feed-in 13946 kWh
   not generated: 2000 kWh by the operating rules, 1500 kWh by the dispatch

2. What-if: valve reopened at 02:00
   run h2-chain-9e1f6e72166e2fc1  mode WHAT_IF  hash ee64f62e6e3c754a
   01:00:00  handover   intervention             {author=h2-handover, reason=valve closed, control=valve, value=0.0}
   01:29:00  buffer     time_to_limit_warning    {ttl_min=59.906, p_bar=146.095}
   02:00:00  handover   intervention             {author=h2-handover, reason=valve reopened, control=valve, value=1.0}
   04:00:00  rules      restriction_start        {reason=bat curtailment, limit_kw=6000.000}
   04:00:00  marketer   schedule_deviation       {deviation_kw=-2022.516}
   05:00:00  park       intervention             {author=operator, reason=dispatch reduced, control=dispatch_kw, value=5000.0}
   05:00:00  rules      restriction_end          {}
   05:30:00  park       intervention             {author=operator, reason=dispatch released, control=dispatch_kw, value=release}
   05:30:00  marketer   schedule_back            {deviation_kw=-22.515}
   max buffer pressure 174.01 bar, electrolyzer trips 0, H2 545.5 kg, extra feed-in 1 kWh
   not generated: 2000 kWh by the operating rules, 1500 kWh by the dispatch

3a. Forecast from 01:10, electrolyzer follows its schedule
   run h2-chain-fcf23e8e9fb5fd26  mode FORECAST  hash 913e4cd1f68b5869
   01:29:00  buffer     time_to_limit_warning    {ttl_min=59.906, p_bar=146.095}
   02:28:54  buffer     pressure_limit           {p_max_bar=200.000}
   02:29:00  ely        protective_shutdown      {p_el_kw=5000.000, p_bar=200.084}
   02:29:00  marketer   schedule_deviation       {deviation_kw=467.897}
   04:00:00  rules      restriction_start        {reason=bat curtailment, limit_kw=6000.000}
   05:00:00  rules      restriction_end          {}
   max buffer pressure 205.03 bar, electrolyzer trips 1, H2 232.6 kg, extra feed-in 15446 kWh
   not generated: 2000 kWh by the operating rules, 0 kWh by the dispatch

3b. Forecast from 01:10, electrolyzer policy pressure-aware
   run h2-chain-19f17fe9eb9b3547  mode FORECAST  hash 3561c591c5e6ff06
   01:29:00  buffer     time_to_limit_warning    {ttl_min=59.906, p_bar=146.095}
   01:31:00  marketer   schedule_deviation       {deviation_kw=164.005}
   04:00:00  rules      restriction_start        {reason=bat curtailment, limit_kw=6000.000}
   05:00:00  rules      restriction_end          {}
   max buffer pressure 197.43 bar, electrolyzer trips 0, H2 219.8 kg, extra feed-in 16143 kWh
   not generated: 2000 kWh by the operating rules, 0 kWh by the dispatch
```

Own wiring plan: put it into a ConfigMap, mount it (edit `deploy/k3s/jobs/twin.yaml`) and pass
`40-demo.sh twin -- --plan /plans/my-site.yaml`.

## House

```bash
deploy/k3s/40-demo.sh house
```

```text
House twin 'haus-erzgebirge' - ALL NUMBERS ARE INVENTED EXAMPLE ASSUMPTIONS (not calibrated)

1. Shadow: reference year 2024-12-31T23:00:00Z + 8760 x PT1H
   run haus-erzgebirge-e0b53b1a33881fa8  hash b9e4011a7f20ce3a  origin SYNTHETIC
   2025-04-15T06:00  tank       reorder_warning  {level_l=599.557}
   ...
   electricity          3007 kWh  (heating pump/burner 701, standby 144 = 4.8 %, base load 156 W, peak 2.0 kW)
   heating oil          1916 l    (19165 kWh)
   firewood              4.9 Ster (7410 kWh)
   final heat          26575 kWh = 204 kWh/m2a; G20/15 4567 Kd, normalized to 4300 Kd: 25248 kWh
   ...
   cost 3444 EUR/a, CO2 7773 kg/a

2. What-if: MODELLED potential per lever = alternative - reference (same weather, same plan)
   lever                  oil l   wood St    el kWh  final kWh      EUR   CO2 kg  under h  change
   minus-1K                -151     +0.00       -16      -1513     -157     -478      -23  room set-point 21 -> 20 C
   ...
   combined                -261     +0.00      -914      -2608     -590    -1320      -39  all levers except more-wood
```

**Calibration against bills.** With the template that ships in the image (invented rows):

```bash
deploy/k3s/40-demo.sh house -- --calibrate /opt/opentsx/share/twin/abrechnungen-vorlage.csv
```

```text
0. Calibration against bills (9 twin runs)
   building.lossWPerK          212.704   (rel. std. error 7.2 %)
   dhw.scale                     0.935   (rel. std. error 100.4 %)
   appliances.scale              0.950   (rel. std. error 1.4 %)
   identifiable false (condition 2.77e+04, max correlation 0.996)
   ...
   strom_kwh      CV(RMSE) 1.4 %, NMBE +0.0 %
   heizoel_liter  CV(RMSE) 0.4 %, NMBE -0.1 %
```

With **your** bills (never commit them; locally they belong in `config/private/`):

```bash
kubectl -n opentsx create secret generic opentsx-private \
  --from-file=abrechnungen.csv=config/private/abrechnungen.csv \
  --from-file=tagesmittel.csv=config/private/tagesmittel.csv
deploy/k3s/40-demo.sh house -- --calibrate /private/abrechnungen.csv --weather /private/tagesmittel.csv
```

The Secret `opentsx-private` is mounted read-only at `/private` in the `house` and `boat` Jobs (optional; the Jobs
run without it).

## Boat (Etap 22)

```bash
deploy/k3s/40-demo.sh boat
```

```text
Boat twin boot-etap22: synthetic two-week trip HAXSHSAXXSHSAS from 2026-07-04 (CEST), step 15 min.
1. Shadow (reference): run boot-etap22-e9c037dcea05c21a  hash beec6c32a572b246
   day        modes       PV Wh  PV used    shore  load Wh  SoC min    unmet
   2026-07-04 H             839      616      386      847      96%        0
   ...
   modes: H harbour, S sailing, A anchor, U away
   KPIs:
     unmet_wh                      0.0
     soc_min_pct                  33.5
     solar_share_pct              67.0
     autonomy_h_mean              23.1
     ...
2. What-if (modelled; delta = alternative minus reference, same inputs and horizon)
   alternative                               unmet Wh   SoC min  solar Wh  shore Wh  autonomy load Wh/d dev.empty
   reference (absolute)                             0     33.5%      8214      4050     23.1h       803         0
   +100 Wp solar (300 Wp)                          +0   +37.2pp     +1390     -1455     +2.1h        +0        +0
   ...
   Starlink Standard instead of Mini            +2371   -28.5pp      +674     +4487    -12.4h      +533        +0
```

On operating data (template in the image, or your CSV from the Secret):

```bash
deploy/k3s/40-demo.sh boat -- --data /opt/opentsx/share/twin/bootsdaten-vorlage.csv --calibrate
deploy/k3s/40-demo.sh boat -- --data /private/bootsdaten.csv --calibrate
```

```text
Boat twin boot-etap22 on operating data: 72 rows, 285 steps from 2026-07-03T22:00:00Z
1. Shadow on the data: run boot-etap22-51a1110d71deda54, SoC RMSE model vs. measured 1.9 %-points
   calibrated in 7 runs: RMSE 1.9 -> 1.1 %-points
     laptops.scale    1.236
```

## DWD weather (MOSMIX)

```bash
deploy/k3s/40-demo.sh dwd           # offline: the MOSMIX fixture that ships in the image, no internet needed
deploy/k3s/40-demo.sh dwd-online    # live: first station of DWD_STATIONS from opendata.dwd.de
```

The Job runs `opentsx dwd mosmix --offline /opt/opentsx/share/dwd/MOSMIX_L_2026101209_10578.kml --out
/data/dwd/demo-mosmix.csv --daily /data/dwd/demo-daily.csv` and then `opentsx dwd house` over the forecast horizon
(module [opentsx-dwd](../../opentsx-dwd/README.md)). Offline output (the fixture has 6 hourly steps; taken through the image entrypoint on the staged image tree):

```text
dwd demo: mode offline (--offline /opt/opentsx/share/dwd/MOSMIX_L_2026101209_10578.kml)
MOSMIX mosmix-l station 10578 FICHTELBERG (50.430, 12.950, 1213 m), issued 2026-10-12T09:00:00Z, 6 time steps 2026-10-12T10:00:00Z .. 2026-10-12T15:00:00Z
models: ICON@2026-10-12T00:00:00Z, ECMWF/IFS@2026-10-12T00:00:00Z
metric                       aggregation  unit   source origin         n valid  seriesId          first value
weather.wind_speed           -            m/s    FF     FORECAST       6     5  88adb41a99ae03b0  7.72
weather.air_temperature      -            Cel    TTT    FORECAST       6     6  128dc68ec58237ce  3.9
weather.irradiance_global    mean.PT1H    W/m2   Rad1h  FORECAST       6     6  deea4b8bc2fe3b9b  200
...
6 v2 episodes (forecast: one per run; observations: per UTC day)
wrote /data/dwd/demo-mosmix.csv
house twin haus-erzgebirge, 5 x PT1H from 2026-10-12T10:00:00Z, weather from MOSMIX mosmix-l station 10578 (issued 2026-10-12T09:00:00Z)
day         steps   t_out C  GHI kWh/m2 heat req kWh
2026-10-12      5       4.4        1.04         28.4
origin of weather.t_out_c: FORECAST (FORECAST = bound from DWD; SYNTHETIC = model fallback)
```

`dwd-online` gives the same layout with 247 hourly steps (about ten days). The CSV files stay on the data PVC.

**Daily collection.** `20-configure.sh DWD_FORWARD=true DWD_STATIONS=10578,10579 "DWD_SCHEDULE=17 5 * * *"` adds the
CronJob `opentsx-dwd-forward`. Every run writes `/data/dwd/mosmix-<station>-<UTC time>.csv` (wide CSV, all elements)
and `daily-<station>-<UTC time>.csv` (`datum,t_mittel_c` for `house --weather`), and deletes CSV files older than 30
days. `40-demo.sh dwd-forward` triggers one run. With `DWD_MODE=offline` it reads the fixture (station 10578 only), for
tests without internet. The weather data are not yet written to KafScale (`tsx.weather.*` is planned).

## Modbus meter (simulator and poller)

```bash
deploy/k3s/20-configure.sh MODBUS_SIM=true && deploy/k3s/30-deploy.sh   # Deployment + Service opentsx-modbus-sim:1502
deploy/k3s/40-demo.sh modbus                                            # poller Job, 30 cycles at 1 s
deploy/k3s/40-demo.sh modbus -- --count 300                             # longer run
```

The simulator serves the **example** register map (`registermap.example=true`, made-up addresses) with
`opentsx modbus simulate --map example --port 1502 --bind 0.0.0.0`. The Job polls it with
`opentsx modbus poll --map example --host opentsx-modbus-sim --port 1502 --interval 1s --count 30 --out episodes
--episode PT10S --file /data/modbus/episodes-<UTC time>.jsonl` (module [opentsx-modbus](../../opentsx-modbus/README.md)):

```text
note: register map 'example-grid-meter' is an EXAMPLE, not a real register sheet
episodes of PT10S -> /data/modbus/episodes-20261012T121114Z.jsonl (Avro JSON, one per line)
2026-10-12T12:11:15Z  grid.meter.voltage_l1                MEASURED  n=5    mean=11556.3307
2026-10-12T12:11:15Z  grid.meter.voltage_l2                MEASURED  n=5    mean=11557.323
...
  window done: 70 readings, 70 accepted, 0 missing, 0 implausible, 0 counter wraps, 0 counter resets, 14 episodes
  window done: 140 readings, 140 accepted, 0 missing, 0 implausible, 0 counter wraps, 0 counter resets, 14 episodes
  window done: 140 readings, 140 accepted, 0 missing, 0 implausible, 0 counter wraps, 0 counter resets, 14 episodes
  window done: 70 readings, 70 accepted, 0 missing, 0 implausible, 0 counter wraps, 0 counter resets, 14 episodes
wrote /data/modbus/episodes-20261012T121114Z.jsonl: 56 episodes
```

(14 points of the example map; the windows are aligned to multiples of 10 s, so the first and last are partial. Output
taken from the same command through the image entrypoint on the staged image tree, against `modbus simulate`.) The poller exits with code 1 when a read block failed
(connection refused, timeout, exception response), so the Job fails and the probe reports it. **There is no KafScale
sink for the Modbus episodes yet**: the module stays free of Kafka dependencies, and the JSON-lines file on the data
PVC is the hand-over point. For a real meter set `MODBUS_HOST`, `MODBUS_PORT` and `MODBUS_MAP` (a map file mounted
into the Job) and keep `MODBUS_SIM=false`.

## Smart Topics Studio

The Studio page is generated while building `opentsx/studio` (StudioExport over all three scenarios → 
`tools/build_studio.py`) and served by nginx:

```bash
deploy/k3s/40-demo.sh studio        # port-forward -> http://localhost:8088  (Ctrl-C to stop)
```

You see the three scenarios H2 chain, house and boat in the three views (statistics, process, data flow) described in
[concept 09](../datamodel-2026/09-STUDIO-LABVIEW-STEUERPULT.md). With `INGRESS=true` it is also served at
`http://<node>/` by Traefik.

The export also runs in the cluster as a Job:

```bash
deploy/k3s/40-demo.sh studio-export
```

It prints the file (1,174,215 bytes for this commit) and its SHA-256
(`ffbd57478ce8d2fb5fd689900ca5ed08b7bc2333cc02d18b18891e267b5f94dd` for this commit).

The export is deterministic: the same code gives the same checksum, and the page built from it is byte-identical to
`docs/prototypes/smart-topics-studio.html` (checked for this commit).

## Retrieval server

```bash
deploy/k3s/40-demo.sh retrieval
```

Port-forwards `svc/opentsx-retrieval` to `localhost:18080` and queries it:

```text
{"status":"ok"}
GET /v1/episodes?metric=demo.signal&limit=1
[{"episodeId":"01M3250V00TTNE2QSPSQNG3G4M","seriesId":"0368da1dee9983e1","metric":"demo.signal","tags":{"n":"1"},"unit":"1","tStart":"2026-09-21T14:13:20Z",...
```

With `LAKE=true` the query is `metric=wind.met.wind_speed&tag.turbine=T07` and returns the simulator's episodes
(`"tags":{"park":"ost-01","turbine":"T07"},"unit":"m/s","tStart":"2026-10-01T00:00:00Z"`). For the Python client, keep a
port-forward open and follow [Getting started, step 4](../user-manual/02-getting-started.md#24-first-end-to-end-run--10-minutes):

```bash
kubectl -n opentsx port-forward svc/opentsx-retrieval 8080:8080
```

```python
from opentsx.client import RetrievalClient
df = RetrievalClient("http://localhost:8080").frame(metric="wind.met.wind_speed", tags={"turbine": "T07"}, limit=6)
```
