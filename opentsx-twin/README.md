# opentsx-twin: digital twin of an energy system (P9, proof of concept)

A twin built from **atoms** (standard parts) and a **wiring plan** (per site). It runs in fixed supersteps, so a
decision changes every downstream number the way it would in the plant. Concept:
[`07-DIGITAL-TWIN-BSP.md`](../docs/datamodel-2026/07-DIGITAL-TWIN-BSP.md) (German).

This module contains the tightly coupled execution variant **T** (one process). The loosely coupled variant **L**
(atoms as Smart Topics) is planned and will use the same atom code and the same message contract.

## Try it

```bash
mvn -q install -DskipTests
bin/opentsx twin                     # scenario S1 in shadow, what-if and forecast mode
bin/opentsx twin --plan my-site.yaml
```

Scenario S1, the H2 chain: the hydrogen offtake closes its valve at 01:00. The line fills up, and the twin warns
when less than an hour of buffer is left. At 40 bar the electrolyzer shuts down to protect itself. Its schedule is
then violated, and the direct marketer has to sell about 5 MW more.

The what-if reopens the valve at 02:00, which avoids the shutdown. The forecast from 01:10 compares two policies
for the electrolyzer: following its schedule, and `pressure-aware`. The `pressure-aware` policy avoids the shutdown
and the overpressure, at the cost of an earlier deviation from the schedule.

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
| `electrolyzer` | electrolyzer (A9) | ASSETS | setpoint, minimum load, ramp, kWh/kg, protective shutdown and release by line pressure |
| `battery` | battery (A8) | ASSETS | charge/discharge within power and SoC limits, efficiencies |
| `balance` | balance node (A6) | BALANCE | `p_grid = gen − loads`; direct supply by priority; residual against a bound meter |
| `h2-line` | H2 line / linepack (A10) | FLOWS | ideal gas; pressure, time to limit, headroom; warning and limit events |
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
* Real bills belong in `config/private/`, which is git-ignored.
* `demo.HouseDemo` also exposes the scenario to generic views: `plan()`, `winterWeekPlan()` (168 steps),
  `observed()`, `reference()`, `alternatives()` and `smartMetrics()`.

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
  * the CSV format, including German spreadsheet exports
  * known parameters recovered from synthetic monthly bills
  * annual totals flagged as not identifiable
* **`TwinPlanTest`**
  * rejected wirings, bindings, policies and interventions
  * feedback delay
  * YAML loading
  * operating rules

## Not yet

* Variant L (Smart Topics, watermarks, bounded staleness) and the conformance test L(0) = T.
* Avro schemas per atom type.
* Binding the message contract to KafScale.
* Atoms for turbine and wake (weather and meter exist for the house twin).
* An optimizer as policy (P8.4).
* Real plant data.
