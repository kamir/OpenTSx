# opentsx-twin: digital twin of an energy system (P9, proof of concept)

A twin built from **atoms** (standard parts) and a **wiring plan** (per site). It runs in fixed supersteps, so a
decision changes every downstream number the way it would in the plant. Concept:
[`07-DIGITAL-TWIN-BSP.md`](../docs/datamodel-2026/07-DIGITAL-TWIN-BSP.md) (German).

`TwinRunner` runs a plan tightly coupled in one process (variant **T**). `LooseRunner` runs the same plan, atoms and
message contract as loosely coupled processors (variant **L**).

## Try it

```bash
mvn -q install -DskipTests
bin/opentsx twin                     # scenario S1 in shadow, what-if and forecast mode
bin/opentsx twin --plan my-site.yaml
```

Scenario S1, the H2 chain: the electrolyzer feeds a compressor, a high-pressure buffer, a pipeline and a handover
station (this order is an assumption; no real plant stands behind it). The handover station closes its valve at
01:00. The regulator stops feeding the pipeline, the buffer fills up, and the twin warns when less than an hour of
buffer is left. At 200 bar the electrolyzer shuts down to protect itself. Its schedule is then violated, and the
direct marketer has to sell about 5 MW more. The first form of the scenario, where the line is the only buffer, is
kept for the tests (`src/test/resources/twin/h2-chain-1.yaml`); its atoms `generation`, `h2-line` and `h2-offtake`
stay in the catalog.

The what-if reopens the valve at 02:00, which avoids the shutdown. The forecast from 01:10 compares two policies
for the electrolyzer: following its schedule, and `pressure-aware`. The `pressure-aware` policy avoids the shutdown
and the overpressure, at the cost of an earlier deviation from the schedule.

## Set the options

`twin`, `house`, `boat`, `twin-coupling`, `twin-schemas` and `studio-export` read their keys with
`OpenTsxConf.load` (module `opentsx-conf`). `bin/opentsx <command> --help` lists the keys of a command with type,
default, flag and environment variable. `--conf <key>=<value>`, `--properties-file <path>`, `$OPENTSX_CONF_DIR` and
the `OPENTSX_` variables set them as well ([chapter 18](../docs/user-manual/18-configuration.md)).

| Command | Options |
|---------|---------|
| `twin` | `--plan <file.yaml>` |
| `house` | `--plan <file.yaml>`, `--calibrate <bills.csv>` with `--weather <temps.csv>`, `--simulated`, `--hdd-ref <Kd>` (default 4300) |
| `boat` | `--plan <file.yaml>`, `--data <file.csv>` with `--zone <zone>` (default `Europe/Berlin`) and `--calibrate`, or `--simulated` |
| `twin-coupling` | `--csv <file>`, `--scenario h2-chain\|haus\|boot` |
| `twin-schemas` | `--out <dir>` (default `opentsx-twin/src/main/resources/twin/avro`, relative to the working directory) |
| `studio-export` | `<out.json>`; the image `opentsx-tools` runs it as `opentsx studio-export <out.json>` |

The exit code is 0 on success, 1 on a runtime failure and 2 on a usage or configuration error. A usage error is one
line on stderr, and the command writes nothing:

```text
opentsx: --hdd-ref: "abc" is not a double; expected a finite decimal number such as 0.01 or 1e-3
opentsx: --plna: unknown option of twin; did you mean --plan?
opentsx: my-site.yaml: no such file; give an existing plan file
opentsx: opentsx.boat.zone: "Europe/Berln" is not a time zone; give a region id such as Europe/Berlin or an offset such as +02:00
opentsx: opentsx.boat.calibrate: needs opentsx.boat.data; give --data <file.csv> with --calibrate
opentsx: opentsx.boat.zone: needs opentsx.boat.data; give --data <file.csv> with --zone
opentsx: opentsx.house.weather: needs opentsx.house.bills; give --calibrate <bills.csv> with --weather
opentsx: opentsx.twin.simulated: cannot be combined with opentsx.house.bills; give --calibrate <bills.csv> or --simulated, not both
opentsx: opentsx.twin.scenario: "nosuch" is not a scenario; expected one of h2-chain, haus, boot
opentsx: bad.csv: missing column period_from; expected [period_from, period_to, electricity_kwh, heating_oil_l, tank_start_l, tank_end_l, wood_ster, cost_eur, note]
```

An option that has no effect is refused: `--zone` without `--data`, `--weather` without `--calibrate`, and
`--simulated` together with `--calibrate`.

A plan with an unknown field, a wire to an unknown atom, fewer than 1 step, more than 35136 steps for `twin` and
`boat` or more than 87673 steps for `house` is a usage error as well, and the line names the file. So is a plan that
lacks the atoms the command runs on: `house` checks the atom types, `twin` and `boat` check the ids and types of the
atoms they bind, change or report. So are a bills file without rows, a row that a bills, temperatures or boat data
file cannot use, and data that span more than 3653 days (10 years); the line names the file and the row's line number
in it, comment lines included:

```text
opentsx: h2.yaml: plan h2-chain has no atom of type building, weather, oil-boiler, oil-tank, wood-stove, wood-store, dhw, appliances, meter; expected a house plan such as opentsx-twin/src/main/resources/twin/haus-erzgebirge.yaml
opentsx: haus.yaml: plan haus-erzgebirge has no atom boat (boat-mode), solar (solar), shore (shore-power), laptop1 (device), laptop2 (device), starlink (device), station (power-station); expected a boat plan such as opentsx-twin/src/main/resources/twin/boot-etap22.yaml
opentsx: boat.csv: line 3: time "x" is not a time; expected 2026-07-04T10:00+02:00, 2026-07-04T08:00:00Z or 2026-07-04 10:00
opentsx: long.csv: line 3: time "9999-12-31T00:00Z" lies more than 3653 days after the earliest row (line 2); expected rows that span at most 3653 days (10 years)
```

Data that need more steps of the plan than one run can hold are a usage error too. Boat data span at most 35136 steps
of the plan (366 days of the 15-minute steps of `twin/boot-etap22.yaml`) and have at most 527040 data rows (one row
per minute for 366 days; every line after the header that is neither blank nor a comment counts, also one without a
time), and bills span at most 87673 steps (3653 days of hourly steps from local midnight to local midnight in
Europe/Berlin, with one more clock change in autumn than in spring). `--simulated` checks the bundled data the same
way. A `twin` or `boat` run over 35136 steps and a `house` run over 87673 steps fit 2 GB of Java heap, the default on
a host with 8 GB of memory. A calibrated boat run on 527040 rows over 35136 steps fits a heap of 1152 MiB (measured
peak RSS 1301 MB), and a house calibration over 10 years of hourly steps needs more than 2 GB. Use a plan with a
longer `baseStep`, data that span less time or rows averaged over a longer interval:

```text
opentsx: season.csv: line 3: time "2026-08-31T10:00Z" needs 83521 plan steps of PT1M from the earliest row (line 2); expected at most 35136 plan steps, from a longer baseStep or rows that span less time
opentsx: boat.csv: line 527042: more than 527040 data rows; expected at most 527040 rows, such as one row per minute for 366 days
opentsx: my-site.yaml: steps: 50000000 is not allowed; expected a whole number from 1 to 87673
opentsx: bills10.csv: period_from 2016-01-01 to period_to 2025-12-31 needs 5260320 plan steps of PT1M; expected at most 87673 plan steps, from a longer baseStep or bills that span less time
```

A file that exists but cannot be read is a runtime failure:

```text
opentsx: dir.yaml: cannot read the plan file; caused by java.io.IOException: Is a directory
```

`--conf opentsx.log.level=debug` adds the stack trace of a runtime failure on stderr.

## Concepts

| Concept | Class | Notes |
|---------|-------|-------|
| Atom (MVC+P) | `Atom` | model = `state()` + `Params`; dynamics = `step(ctx)`; ports with kind (flow/signal) and unit; controls with defaults |
| Policy | `Policy`, `Policies` | decides controls before each step: `follow` (inputs → controls), `hold`, `pressure-aware`. Interventions override a policy. |
| Intervention | `Intervention` | a human decision on a control, in force until released; appears as an event |
| Wiring plan | `TwinPlan` (YAML) | atoms, params, policies, wires `a.port -> b.port`. Checks ports, kind, unit and one source per input. Has a stable hash. |
| Runner (variant T) | `TwinRunner` | layered bulk-synchronous execution, see below |
| Inputs | `Inputs`, `SeriesInputs` | measurements, schedules and forecasts bound to unwired input ports; the binding decides the mode |
| Origin | `Origin` | `MEASURED > REPORTED > FORECAST > ESTIMATED > SYNTHETIC` (see below) |
| Topic contract | `PortMessage`, `Barrier`, `MessageSink`, `MessageLog` | deterministic ids `run/k/phase/atom/port`; phase barriers with counts; superstep commit with state hash |
| Result | `RunResult`, `Snapshot` | series per `atom.port` / `atom.state.var`, events, KPIs, snapshots, export as v2 episodes |
| Modes | `Twin` | `shadow`, `whatIf` (baseline vs. alternative log), `forecast` (from a snapshot, policies replaceable) |

### Runner (variant T)

Each superstep runs these phases in order:

`SOURCES → POLICY → ASSETS → BALANCE → FLOWS → VIEWS`

* **Wires.** A wire delivers the value of the same superstep when its source phase comes earlier. Otherwise it
  delivers the previous superstep's value, so feedback loops always cross the superstep boundary.
* **Multirate.** An atom can step only every n supersteps (`every`), for example a 15-minute market.
* **Events.** Atoms report events at their exact time, such as the moment the pressure limit is crossed.
* **Determinism.** The same plan, inputs, interventions and code give a bit-identical run, and therefore the same
  `RunResult.hash()`.

### Runner (variant L)

`LooseRunner` runs the same plan, atoms, policies and interventions as loosely coupled processors: each atom has
its own superstep clock and reads upstream values from port messages, without a global barrier. A `Coupling`
sets the staleness bound (`bsp()` = 0, `ssp(s)`, `async()`), transport latency and jitter in simulated ticks,
slow processors and outages, explicit processor groups (`withGroup`) and `colocatingCycles()`, which runs every
feedback cycle of the plan (strongly connected component) in one processor.

* Staleness 0 gives a run that is bit-identical to variant T (same run id, hash, events, messages) for any
  transport; a coupling that can change the result becomes part of the run id.
* `LooseRun.stats()` reports simulated wall time (ticks), wait and down ticks per atom and the staleness used per
  wire.
* `bin/opentsx twin-coupling [--csv out.csv] [--scenario id]` runs the RQ1 grid over all scenarios, or over the one
  scenario `h2-chain`, `haus` or `boot` that `--scenario` names. Results and
  the design rule (cycles in one processor, BSP between processors) are in
  [07 §10a](../docs/datamodel-2026/07-DIGITAL-TWIN-BSP.md).

### Topic contract as Avro

`twin/avro/` holds the Avro schemas of the topic contract, generated from the atom catalog by `AtomSchemas`
(`bin/opentsx twin-schemas` regenerates them; a test fails if the checked-in files are stale):

* envelopes `PortMessage`, `Barrier`, `Intervention` (namespace `org.opentsx.twin.v1`);
* one `<Type>Snapshot` per atom type (`twin/avro/atoms/<type>.avsc`): run, instance key `atom`, `k`, `t`, the state
  variables as a record, `state_extra` for parameter-dependent variables (e.g. one counter per appliance), the
  outputs as `PortValue {value, origin}` and the controls overridden by interventions. Phase and input ports
  (kind, unit) are schema properties.

Every atom of the three scenarios round-trips through these schemas (Java test), and Python reads them with
fastavro (`python-package/tests/test_twin_schemas.py`). They are the basis for running variant L processors in other
languages and for snapshots in Iceberg.

### Origin propagation

* An output gets the worst origin of the inputs it read in the same superstep.
* Feedback wires carry model state and do not pass their origin on.
* A default used for a missing input counts as `SYNTHETIC`.
* Every KPI therefore shows how much of it rests on measurements.

## Atoms

| Type | Atom (concept no.) | Phase | Main behaviour |
|------|--------------------|-------|----------------|
| `generation` | park at the connection point (A1/A3, simplified) | ASSETS | available power, limited by `limit_kw`; energy and curtailment |
| `operating-rules` | operating constraints (A16) | SOURCES | time windows with a power limit (grid restrictions, field work, bat curtailment) |
| `market` | market (A12) | SOURCES | price, every 15 steps |
| `h2-offtake` | H2 offtake (A11) | SOURCES | demand × valve |
| `h2-consumer` | H2 customer (A21) | SOURCES | passes its demand on as a request; counts the hydrogen it receives, one step late |
| `wind-park` | wind farm (A22) | ASSETS | available power after `limit_kw`, generated power after `dispatch_kw`; energy not generated per cause |
| `h2-compressor` | H2 compressor (A17) | ASSETS | passes the flow on and draws the power of one adiabatic stage; `throughput_limit` event |
| `h2-handover` | handover station (A20) | ASSETS | delivers the request while the valve is open and the pressure is at least `pMinBar`; shortfall; `delivery_interrupted` and `delivery_resumed` events |
| `electrolyzer` | electrolyzer (A9) | ASSETS | setpoint, minimum load, ramp, kWh/kg, protective shutdown and release by line pressure |
| `battery` | battery (A8) | ASSETS | charge/discharge within power and SoC limits, efficiencies |
| `balance` | balance node (A6) | BALANCE | `p_grid = gen − loads`; direct supply by priority; residual against a bound meter |
| `h2-line` | H2 line / linepack (A10) | FLOWS | ideal gas; pressure, time to limit, headroom; warning and limit events |
| `h2-buffer` | high-pressure buffer with regulator (A18) | FLOWS | ideal gas; the regulator feeds the pipeline towards `lineSetBar`; time to limit, headroom; `time_to_limit_warning`, `pressure_limit` and `buffer_low` events |
| `h2-pipeline` | H2 pipeline / linepack (A19) | FLOWS | ideal gas; pressure at both ends, the far end lower by a loss that grows with the square of the flow; `pressure_limit` and `pressure_low` events |
| `direct-marketer` | direct marketer (A13) | FLOWS | deviation from the schedule, extra/short energy, revenue |

The example plan is [`twin/h2-chain.yaml`](src/main/resources/twin/h2-chain.yaml).

## House twin (P9.7)

The same method applied to a homeowner: a single-family house in the Erzgebirge (two residents, oil boiler, wood
stove, no PV, no heat pump). Concept (German): [`08-HAUS-ZWILLING.md`](../docs/datamodel-2026/08-HAUS-ZWILLING.md).
**All house numbers are invented example assumptions** until the plan is calibrated against real bills.

```bash
bin/opentsx house                                          # reference year (shadow) + every lever as what-if
bin/opentsx house --calibrate config/private/abrechnungen.csv [--weather config/private/tagesmittel.csv]
bin/opentsx house --simulated                              # calibration on the bundled SIMULATED bills
```

Until real bills are available, [`twin/beispiel/`](src/main/resources/twin/beispiel/README.md) holds
**simulated** bills and daily temperatures for 2022–2025. `house.SimulatedBills` generates them with a fixed seed,
from hidden "true" parameters and with realistic gaps and noise. The calibration finds H/eta and the appliance
scale, and it reports hot water as not determined (concept §7.6).

| Type | Atom | Phase | Main behaviour |
|------|------|-------|----------------|
| `weather` | weather (A4) | SOURCES | bound station data or a deterministic synthetic climate; heating degree days G20/15 |
| `dhw` | hot water | SOURCES | daily draw profile plus storage loss; control `t_store_c` |
| `appliances` | household electricity | SOURCES | inventory (power, standby, weekday/weekend hours), internal gains; controls `standby_off`, `factor.<item>` |
| `wood-stove` | wood stove | ASSETS | evening batches in the heating season, or the wood from the bills |
| `oil-boiler` | oil boiler | ASSETS | hot-water priority, rated power, minimum load and cycling, standby loss, pump; stops when the tank is empty |
| `meter` | electricity meter (A5/A6) | BALANCE | import, cost, base load, peak; residual against a meter reading |
| `building` | building, one zone (1R1C) | FLOWS | closed heat balance, thermostat with day/night set-point and heating limit |
| `oil-tank` | oil tank | FLOWS | litres, deliveries as interventions, reorder warning |
| `wood-store` | firewood store | FLOWS | Ster, deliveries as interventions |

* Plan: [`twin/haus-erzgebirge.yaml`](src/main/resources/twin/haus-erzgebirge.yaml), one year at `PT1H`
  (8760 supersteps, about 0.5 s per run).
* `house.HouseScenario`: reference interventions (deliveries) and the levers.
* `house.HouseKpis`: kWh, litres, Ster, kWh/m²a, weather-normalized final energy, boiler losses, standby, base
  load, cost, CO2.
* `house.BillCalibration`: reads bills
  ([`twin/abrechnungen-vorlage.csv`](src/main/resources/twin/abrechnungen-vorlage.csv), fake rows only) and fits
  H, the hot-water scale and the appliance scale by deterministic least squares (Levenberg–Marquardt with Broyden
  updates). It reports residuals per period, CV(RMSE)/NMBE, the condition number and the parameter correlation.
* Bill columns: `period_from`, `period_to`, `electricity_kwh`, `heating_oil_l`, `tank_start_l`, `tank_end_l`,
  `wood_ster`, `cost_eur`, `note`. The German column names are accepted as aliases: `zeitraum_von`,
  `zeitraum_bis`, `strom_kwh`, `heizoel_liter`, `tank_start_liter`, `tank_ende_liter`, `holz_ster`, `kosten_eur`,
  `notiz`. A column named twice, also once in English and once in German, is refused. `--weather` reads
  `date,t_mean_c`; the header row is not checked, so `datum,t_mittel_c` works too.
* Real bills belong in `config/private/`, which is git-ignored.
* `demo.HouseDemo` also exposes the scenario to generic views: `plan()`, `winterWeekPlan()` (168 steps),
  `observed()`, `reference()`, `alternatives()` and `smartMetrics()`.

## Scenario boot: Etap 22 as an island energy system

Concept (German): [`10-BOOT-ZWILLING.md`](../docs/datamodel-2026/10-BOOT-ZWILLING.md). A small sailing yacht with a
portable power station (~1 kWh LiFePO4), 2 × 100 Wp solar and shore power, feeding two laptops, a phone, Starlink,
a Raspberry Pi and a VHF radio. Plan: [`twin/boot-etap22.yaml`](src/main/resources/twin/boot-etap22.yaml), 15-minute
steps over a 14-day summer trip. All numbers are assumptions until real data replace them.

```bash
bin/opentsx boat                                             # shadow of the synthetic trip + what-ifs
bin/opentsx boat --data config/private/bootsdaten.csv --calibrate   # real data, fitted to the measurements
bin/opentsx boat --simulated                                 # the same on bundled SIMULATED logs
```

Until real exports exist, [`twin/beispiel/boot-logs-simuliert.csv`](src/main/resources/twin/beispiel/boot-logs-simuliert.csv)
holds SIMULATED logs ([README](src/main/resources/twin/beispiel/README-boot.md)): `SimulatedLogs` runs the model with
hidden parameters (derate 0.62, Starlink ×1.25, laptops ×1.3), a cloudy spell and a day without Starlink, and writes
a 5-minute app export with logbook, irradiance, whole-percent SoC, noise and gaps. `--simulated` calibrates on it;
`SimulatedLogsTest` checks that the fit recovers the derate within 0.02, the Starlink scale within 0.03 and the laptop
scale within 0.06 (2.4 to 4.6 % of the hidden values). `java -cp "$(bin/opentsx classpath opentsx-twin)"
org.opentsx.twin.boat.SimulatedLogs <out.csv> [seed]` writes them again (seed 20260704 by default); `--help` prints
the usage, and a wrong argument exits with 2.

| Type | Phase | Main behaviour |
|------|-------|----------------|
| `boat-mode` | SOURCES | harbour / sailing / anchor / away per step from the logbook (`mode_obs`) or the itinerary |
| `solar` | ASSETS | `wp · ghi · derate · (1 − shade(mode))`; synthetic clear sky × daily clearness, or bound irradiance / measured power |
| `shore-power` | ASSETS | charger power while plugged in (harbour, logbook); controls `enabled`, `every_n_days` |
| `device` | ASSETS | generic consumer: hour windows per mode, standby, burst duty (VHF transmit), own battery with charge window (laptop, phone), path efficiency; measured power replaces the model |
| `power-station` | BALANCE | stock and balance node: direct supply solar → shore → battery, charging, SoC limits, unmet demand by priority, autonomy, residuals against measured SoC/load |

Real data come as CSV ([template](src/main/resources/twin/bootsdaten-vorlage.csv), invented example rows) and are bound
by `BoatData` with origin MEASURED (station app, Starlink, USB-C meter) or REPORTED (logbook). The columns are `time`,
`mode` (`harbour`, `sailing`, `anchor`, `away`), `shore`, `soc_percent`, `solar_w`, `load_w`, `ghi_wm2`,
`<device>_on`, `<device>_w` and `note`. The German column names and modes are accepted as aliases: `zeit`, `modus`
(`hafen`, `segeln`, `anker`, `unbemannt`), `landstrom`, `soc_prozent`, `last_w`, `einstrahlung_wm2`, `<device>_an`
and `notiz`. A column named twice, also once in English and once in German, is refused. `Calibration` fits
model parameters such as `solar.derate` and usage scales by bounded Levenberg-Marquardt least squares against the
measured SoC, the measured output power and (with irradiance) the measured solar power. Model parameters
(`scale`, `derate`) are kept apart from system levers for what-ifs (`wp`, `power_factor`, `on`, `max_on_h`, ...); only
the levers act on measured values. For front ends, `BoatDemo` exposes `plan()`, `observed()`, `reference()`, `alternatives()`, `KEY_SERIES`,
`KEY_KPIS` and `kpis(RunResult)`.

## Tests

* **`TwinShadowTest`**
  * the order of the causal chain
  * the limit time against the analytic value
  * closed power and hydrogen balances
  * origin propagation
  * bit-identical repetition
  * the message contract
  * the episode export
* **`TwinModesTest`**
  * two what-ifs
  * a forecast from a snapshot that reproduces the shadow run exactly
  * the `pressure-aware` policy against schedule following
* **`HouseTwinTest`**
  * closed heat, oil, wood and electricity balances
  * determinism, and an exact continuation from a snapshot
  * −1 K lowers the heat demand
  * an empty tank stops the boiler
  * measured weather is used and labelled
* **`BillCalibrationTest`**
  * the CSV format, including German spreadsheet exports and the German column names
  * the limits of 3653 days and of 87673 run steps, counted between local midnights across clock changes
  * known parameters recovered from synthetic monthly bills
  * annual totals flagged as not identifiable, hardly separable parameters, a parameter at its bound
  * one solver (`Gauss`) for the house and the boat calibration, NaN for a singular system
* **`demo.TwinCliTest`**, **`studio.StudioExportTest`**, **`schema.AtomSchemasTest`**, **`boat.SimulatedLogsTest`**
  * exit codes 0, 1 and 2 and the one-line messages of every command
  * a plan with more than 35136 steps is a usage error of `twin` and `boat`, one with more than 87673 steps of `house`
  * the header lines of `house` and the calibration warnings it prints, word for word
  * `--help` prints the keys or the usage and writes no file
  * the k3s Jobs `twin`, `house`, `boat` and `studio-export` load their configuration in the namespace of the base
* **`TwinPlanTest`**
  * rejected wirings, bindings, policies and interventions
  * feedback delay
  * YAML loading
  * 1 to 87673 steps, or a lower bound the load is given, named in the refusal
  * operating rules
* **`boat.BoatTwinTest`**, **`boat.BoatDataTest`**
  * plan validation and port type checks
  * DC energy balance (sources − losses − served load = ΔSoC), SoC limits, unmet demand by priority
  * determinism, forecast from a snapshot, origins
  * what-ifs (+100 Wp, Starlink 3 h/day, shore power every third day) and the scenario API
  * CSV import (resampling, holding, origins) and calibration that recovers known parameters
  * a column named twice, the limits of 3653 days, of 35136 plan steps and of 527040 data rows
* **`boat.SimulatedLogsTest`**: the bundled simulated logs equal the generator output; calibration on them recovers
  the hidden parameters, and unmet demand and autonomy on the logs match the hidden true run
* **`studio.EnglishTextsTest`**: the scenario texts, the guides, the bundled READMEs, templates and plans are English
