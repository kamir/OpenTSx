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

## Scenario "Boot": Etap 22 as an island energy system

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
a 5-minute app export with logbook, irradiance, whole-percent SoC, noise and gaps. `--simulated` calibrates on it
and recovers the hidden values within about 1 %.

| Type | Phase | Main behaviour |
|------|-------|----------------|
| `boat-mode` | SOURCES | harbour / sailing / anchor / away per step from the logbook (`mode_obs`) or the itinerary |
| `solar` | ASSETS | `wp · ghi · derate · (1 − shade(mode))`; synthetic clear sky × daily clearness, or bound irradiance / measured power |
| `shore-power` | ASSETS | charger power while plugged in (harbour, logbook); controls `enabled`, `every_n_days` |
| `device` | ASSETS | generic consumer: hour windows per mode, standby, burst duty (VHF transmit), own battery with charge window (laptop, phone), path efficiency; measured power replaces the model |
| `power-station` | BALANCE | stock and balance node: direct supply solar → shore → battery, charging, SoC limits, unmet demand by priority, autonomy, residuals against measured SoC/load |

Real data come as CSV ([template](src/main/resources/twin/bootsdaten-vorlage.csv), invented example rows) and are bound
by `BoatData` with origin MEASURED (station app, Starlink, USB-C meter) or REPORTED (logbook). `Calibration` fits
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
* **`TwinPlanTest`**
  * rejected wirings, bindings, policies and interventions
  * feedback delay
  * YAML loading
  * operating rules
* **`boat.BoatTwinTest`**, **`boat.BoatDataTest`**
  * plan validation and port type checks
  * DC energy balance (sources − losses − served load = ΔSoC), SoC limits, unmet demand by priority
  * determinism, forecast from a snapshot, origins
  * what-ifs (+100 Wp, Starlink 3 h/day, shore power every third day) and the scenario API
  * CSV import (resampling, holding, origins) and calibration that recovers known parameters
* **`boat.SimulatedLogsTest`**: the bundled simulated logs equal the generator output; calibration on them recovers
  the hidden parameters, and unmet demand and autonomy on the logs match the hidden true run

## Not yet

* Variant L (Smart Topics, watermarks, bounded staleness) and the conformance test L(0) = T.
* Avro schemas per atom type.
* Binding the message contract to KafScale.
* Atoms for turbine, wake, weather and meter.
* An optimizer as policy (P8.4).
* Real plant data.
