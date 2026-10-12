# Demos on k3s

Every example from the repository runs in the cluster with one command. `40-demo.sh <demo>` (re)creates a Job from
[`deploy/k3s/jobs/`](../../deploy/k3s/jobs/), follows its log and reports whether it completed. Pods stay for 24 h
(`kubectl -n opentsx logs job/opentsx-<demo>`).

```bash
deploy/k3s/40-demo.sh all     # kafscale-check, windpark-sim, stats, metadata, twin, house, boat, studio-export, retrieval
```

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
[setup plan, step 3](setup-plan.md#step-3-network-path-to-kafscale). (The failure path was run here; the success
output above follows the code, it was not captured against a live broker.)

## Wind farm simulator → KafScale

```bash
deploy/k3s/40-demo.sh windpark-sim
```

Runs `opentsx windpark-sim --scenario /opt/opentsx/share/scenarios/ostsee-demo.yaml --kafscale … --domain wind
--partitions 6 --index /data/wind-index.tsv --ground-truth /data/ground-truth.json` (lake profile: plus
`--iceberg … --namespace tsx`): 10 turbines × 10 channels, 6 h at 1 Hz, with curtailment, a fault, maintenance and
five sensor faults. Then it restarts `opentsx-stats` so the processor assigns the partitions. Output:

```text
bucket ostsee-demo/2026-10-01T00:00:00Z sealed: 3600 episodes, checksum <checksum>
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

The scenario summary was captured with the Iceberg sink (no broker available here); the `bucket … sealed` line is
printed when `--kafscale` is set. A rerun writes the same episodes again; readers drop the duplicates by `episodeId`.
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
outputs are at-least-once, readers keep the latest record per key). Data-quality checks evaluated on these statistics
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
   run h2-chain-32d17d33b237bf6b  mode SHADOW  hash 25903cf900753ca0
   01:00:00  offtake    intervention             {control=valve, reason=valve closed, author=h2-offtake, value=0.0}
   01:28:00  line       time_to_limit_warning    {ttl_min=59.239, p_bar=33.210}
   02:27:14  line       pressure_limit           {p_max_bar=40.000}
   02:28:00  ely        protective_shutdown      {p_el_kw=5000.000, p_bar=40.087}
   02:28:00  marketer   schedule_deviation       {deviation_kw=500.000}
   max line pressure 40.60 bar, electrolyzer trips 1, H2 231.1 kg, extra feed-in 17292 kWh

2. What-if: valve reopened at 02:00
   run h2-chain-7fd4f14f51c0f5ca  mode WHAT_IF  hash 299c21b19951a549
   ...
   max line pressure 36.88 bar, electrolyzer trips 0, H2 545.5 kg, extra feed-in 0 kWh

3a. Forecast from 01:10, electrolyzer follows its schedule
   max line pressure 40.60 bar, electrolyzer trips 1, H2 231.1 kg, extra feed-in 17292 kWh

3b. Forecast from 01:10, electrolyzer policy pressure-aware
   run h2-chain-a8bf2147d88896f7  mode FORECAST  hash 1a3f20d7918c309c
   01:28:00  line       time_to_limit_warning    {ttl_min=59.239, p_bar=33.210}
   01:29:00  marketer   schedule_deviation       {deviation_kw=145.681}
   max line pressure 39.66 bar, electrolyzer trips 0, H2 218.7 kg, extra feed-in 17974 kWh
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
