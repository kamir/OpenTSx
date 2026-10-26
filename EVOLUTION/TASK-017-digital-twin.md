# TASK-017: Digital twin of the energy system

## Task Metadata
- **Task ID**: TASK-017-digital-twin
- **Created**: 2026-10-26
- **Status**: In Progress
- **Priority**: High
- **Epic**: P9 Digital twin of the energy system
- **Origin**: split out of [TASK-011](TASK-011-datamodel-refresher-2026.md) on 2026-10-26 (D90, [18-CLOSING-TASK-011](../docs/datamodel-2026/18-CLOSING-TASK-011.md))
- **Concept**: [07-DIGITAL-TWIN-BSP.md](../docs/datamodel-2026/07-DIGITAL-TWIN-BSP.md)

## Objective
Build a digital twin of an energy system made of wind farm, electrolyzer, H2 line, storage, self-consumption/direct
customers and marketing, as a wiring plan of atomic elements (MVC+P) executed as layered BSP, with Smart Topics
supplying learned sub-models and publishing the twin. The twin with its three operating modes (Shadow, What-if,
Forecast/MPC) is the essence of the roadmap, and all three are made possible in both execution variants: tightly
coupled as one process (T) and loosely coupled over Smart Topics (L). The work is staged with value gates (A to D).

## Phase 9 (moved from TASK-011)

Moved from TASK-011 (commit 551815a3), lines 198 to 295.

### Phase 9: Digital twin of the energy system (BSP), concept: [`07-DIGITAL-TWIN-BSP.md`](../docs/datamodel-2026/07-DIGITAL-TWIN-BSP.md)

A combined system of wind farm, electrolyzer, H2 line, storage, self-consumption/direct customers and marketing as a
wiring plan of atomic elements (MVC+P), executed as layered BSP; Smart Topics supply learned sub-models and
publish the twin. **The twin with its three operating modes (Shadow, What-if, Forecast/MPC) is the essence
of the roadmap.** All three are made possible in both execution variants: tightly coupled as one process (T) and loosely
coupled over Smart Topics (L) (D20 decided). **Status: remaining items are proposals (D18, D19, D21 to D24).** Staged with value gates (A to D); P8.3/P8.4 supply
simulation on the reconstructed structure and the optimizer policy.

**P9.0: Stage A, data access and potential (without simulation)**
- [x] Generic **Modbus module** (TCP/RTU), register maps as YAML (address, type, scaling, unit, OBIS), poll interval → `RawReading` → Normalizer → episodes. *`opentsx-modbus`: Modbus TCP client using only the JDK (MBAP, RTU over TCP with CRC16), register map `opentsx.modbus.registermap.v1`, block forming ≤ 125 registers, meter overflow/reset as a quality code, in-JVM meter simulator, `bin/opentsx modbus`. Open: real register maps (the example map is marked), serial RTU directly, KafScale sink in the CLI.*
- [ ] **IEC 60870-5-101/-104** adapter for the park computer interface (data exchange with the electrolyzer operator, both directions).
- [ ] Electrolyzer operating data (load, state, line pressure, offtake) over the agreed interface (104, OPC UA or export) as source `REPORTED`.
- [x] **DWD collector** (`opentsx-dwd`, JDK only): MOSMIX_L forecasts (KMZ/KML: FF, DD, TTT, PPPP, N, Rad1h, RR1c; issue time as `forecast.reference_time`, origin FORECAST) and CDC measurements (10 min/hourly, `-999`, UTC, origin MEASURED) → v2 series/episodes; station selection per site (Haversine, MOSMIX catalog in degrees.minutes, CDC station list); binding to the weather atom (house) and solar (boat), CSV for `house --weather` and `boat --data`; `bin/opentsx dwd stations|mosmix|obs|house`. Parser checked against a live fetch. Open: scheduled fetch (→ KafScale `tsx.weather.*`), ICON grid, keeping data for all of Germany, wind → power curve. *Also in `opentsx-market`: CDC backfill of the twin sites into the history archive based on `CdcObservations`, and a raw forward archive of MOSMIX_L (`bin/opentsx market mosmix-archive`, k3s CronJob every 6 h) since 2026-10-12. The DWD keeps no MOSMIX history.*
- [x] Market data (public, `opentsx-market`): Day-Ahead DE-LU with 15-min MTU from 2025-10-01 (SMARD, Energy-Charts as a cross-check, ENTSO-E A44 with token), grid load, residual load, generation and forecasts wind/PV (SMARD, ENTSO-E A65/A69), reBAP and monthly market values (netztransparenz.de, OAuth client or CSV import); mapping to v2 episodes with origin REPORTED/FORECAST; scheduled fetch (`market forward`, k3s CronJob daily).
- [ ] Partner data: forecasts and schedules of the direct marketer, realized intraday prices (EPEX licensed, no fetch), control system API (interface description pending). *File import with offsets and a check of the doubled hour exists (`market import`), access still open.*
- [x] Batch import of single data packages (historical series) with origin metadata: local archive `data/history/<quelle>/<reihe>/<jahr>.csv.gz` (not in git) with `manifest.json` (source, queries, license, fetch time, rows, SHA-256, quality report: gaps, daylight saving change days, outliers, statistics via `opentsx-stats`), resumable backfill from 2019, loading as episodes into Iceberg (`market load`). Licenses: SMARD and Energy-Charts CC BY 4.0 (attribution "Bundesnetzagentur | SMARD.de" and "Energy-Charts.info" respectively), DWD CC BY 4.0 ("Deutscher Wetterdienst"), ENTSO-E terms of use with attribution, netztransparenz.de terms of use (keep the archive local).
- [ ] *(Proposal)* infinimesh as an optional source: Kafka add-on topic `infinimesh.shadows` → Normalizer → `tsx.<domain>.observations.v2` (module `opentsx-infinimesh`); read-only first, the return path over `desired` only with an approval workflow.
- [ ] Metric catalog for new channels (meters, electrolysis, storage, H2) analogous to the IEC catalog, publication via the OpenMetadata publisher.
- [ ] Kafka mirroring over a temporary SSH tunnel into a local environment; technical validation (offsets, count, bytes, hash) and domain validation (identical sketches/statistics per metric).
- [ ] OpenTSDB as an optional projection layer (existing adapter).
- [ ] Acceptance S2: potential estimate (overlap of generation/consumption, direct supply share vs. grid feed-in, order of magnitude €/a) with origin shares → **gate A**.

**P9.1: Atom contract and runner**
- [ ] Screening of the state of the art before building: mosaik, HELICS, FMI 3.0 (atoms as FMUs?), pandapower, PyPSA, oemof → decision D24.
- [x] Atom contract MVC+P in `opentsx-twin` (`Atom`: model = state/parameters, dynamics `step`, exchangeable `Policy`, ports with kind and unit, sensor view via the residual in the balance node).
- [x] Avro schemas per atom type for state and ports: generated from the atom catalog (`AtomSchemas`, `twin/avro/`, `bin/opentsx twin-schemas`), envelopes `PortMessage`/`Barrier`/`Intervention`, `state_extra` for parameter-dependent states; round trip of all scenario atoms in Java, read test in Python (fastavro).
- [x] Wiring plan YAML (`TwinPlan`, example `twin/h2-chain.yaml`), validation (ports, kind/unit, one source per input), feedback loops automatically across the step boundary; plan hash in the run id. *(Lineage to OpenMetadata open.)*
- [x] **Variant T (tight, one process):** `TwinRunner` with layered BSP (phases sources → policy → assets → balance → flows → views), base step 1 min, multirate (`every`), events at their exact time, snapshots for continuing; bit-identical repetition via a chained state hash.
- [x] **Variant L (loose, Smart Topics):** *implemented as `LooseRunner` (own clock per atom, coupling over `PortMessage`, staleness `s` from BSP via SSP to async, simulated latency/jitter/outages, processor groups and colocation of feedback loops); measurements in 07 §10a; binding to KafScale open.* Atom type as a Smart Topic processor (instances as keys), coupling over port topics, event-time watermarks with adjustable staleness (BSP ↔ SSP ↔ asynchronous), balance node as its own processor with a balancing step; atoms can run at different owners.
- [x] The same atom code runs unchanged in T and L (shared atom, wiring plan and topic contract); conformance test: L with staleness 0 = T bit-identical.
- [x] Topic contract as code: `PortMessage` (deterministic id `run/k/phase/atom/port`), `Barrier` (phase end with count, superstep commit with state hash), `MessageSink`; variant T already records it.
- [ ] Binding to KafScale topics `twin.<id>.<run>.msgs|barrier|interventions`; state snapshots in Iceberg (`twin_state`); write state series as episodes (`RunResult.toEpisodes`, implemented) into a store.
- [x] Provenance ladder `MEASURED > REPORTED > FORECAST > ESTIMATED > SYNTHETIC` (`Origin`) with taint propagation along the data flow of a step; feedback edges do not taint; defaults for missing inputs count as SYNTHETIC.

**P9.2: Atom catalog**
- [ ] A1 wind turbine, A2 wake coupling (relation atom, Jensen; later the reconstructed graph from P8), A3 park controller.
- [ ] A4 weather, A5 meter/metering point, A6 balance node/metering concept (priorities: self-consumption before market), A7 self-consumer/direct customer.
- [x] A8 battery storage, A9 electrolyzer (state machine, ramps, protective shutdown by line pressure), A10 H2 line/linepack (time to limit, remaining capacity), A11 H2 offtaker.
- [x] A6 balance node (priorities, direct supply, residual against the meter reading), A12 market (15-min step), A13 direct marketer (deviation, extra/short quantities, revenue), A16 operating constraints (time windows with a power limit, for example bat curtailment, field work), simplified generation (A1/A3 as a park at the connection point with a curtailment limit).
- [ ] A14 operator as an approval workflow in the Service Layer (interventions implemented as `Intervention`), A15 optimizer as a policy atom (today: rule `pressure-aware` with a rolling horizon), A13 balancing energy prices.

**P9.3: Operating modes and acceptance scenarios**
- [ ] **All three operating modes in both variants** (matrix 3 × 2), *implemented in variant T (`Twin.shadow`, `whatIf`, `forecast` from a snapshot with policy exchange); variant L open*: Shadow (nowcast, residuals measurement − sensor view), Replay/What-if (intervention log, branches), Forecast/MPC; same atoms, different binding.
- [ ] S3 PPA shadow operation before the official switch of the metering concept (schedule/forecast of the marketer against generation) → **gate B**.
- [x] S1 H2 chain on synthetic data (valve closed → pressure → time to limit → curtailment → schedule deviation → extra sales): order, analytical limit time, balances, what-if and forecast tested; demo `bin/opentsx twin`.
- [x] S1 on real market and weather history (`bin/opentsx twin --history data/history --year 2024`): day-ahead prices REPORTED, wind scaled from the onshore generation DE or a DWD station via a power curve (ESTIMATED), schedule from the day-ahead forecast (real forecast error), electrolyzer with a price limit rule; what-if price limits (H2 quantity, electricity cost per kg, revenue).
- [ ] Confirm S1 with real operating data → **gate C**.
- [ ] S4 wake reference reproduced in the park atom; reconstructed graph (P8.1) checked against it.

**P9.4: Policy, optimization and Service Layer**
- [ ] Smart Topics as forecast ports and estimators for missing data; the twin as a derived Smart Topic with a what-if endpoint of the Prediction API.
- [ ] Optimizer as a policy atom (P8.4, rolling horizon); play through proposals in the Personal Service Layer in the twin before approval; extra revenue against the reference operation → **gate D**.

**P9.6: Research, tightly vs. loosely coupled twins (paper)**, outline: [07 §10](../docs/datamodel-2026/07-DIGITAL-TWIN-BSP.md)
- [ ] State of the literature: BSP/Pregel, SSP/parameter server, PDES (Chandy-Misra-Bryant, Time Warp), HLA time management, FMI/mosaik/HELICS, dataflow watermarks.
- [ ] Experimental setup RQ1 to RQ6: same wiring plan in T and L, S1/S3 plus synthetic systems with controlled coupling strength and disturbances (delay, loss, duplicates, atom failure).
- [ ] Results: error bounds as a function of staleness, coupling strength and time constants; design rule "what must be tightly coupled"; reproducible artifact.
- [ ] Define the manuscript and the target venue.

**P9.5: Transferability and federation**
- [ ] Wiring plan templates for new projects (park + direct line + customer load profile) for assessment before construction.
- [ ] Comparison of several plants via generic indicators without raw data export (Wayang federation, mergeable sketches); vertical extension (CMS data, PV).
- [ ] Edge collectors (Modbus/104/DWD) optionally as Go/Rust binaries; no core rebuild before gate B (D23).

**P9.7: Transfer to homeowners (house twin)**, concept: [`08-HAUS-ZWILLING.md`](../docs/datamodel-2026/08-HAUS-ZWILLING.md)
- [x] Method transferred: same atom contract, three operating modes, provenance ladder (bill = REPORTED), stages A to D with gates, assessment alternative − reference, modelled ≠ proven; mapping house ↔ terawatt case.
- [x] Example house Erzgebirge (invented assumptions): rooms, envelope, 2 residents with a daily routine, inventory (3 TV, fridge, chest freezer, cooker, radio, light …), oil boiler + wood stove, no PV/heat pump.
- [x] Atoms `weather`, `building` (1R1C, thermostat), `oil-boiler`, `wood-stove`, `dhw`, `appliances`, `meter`, `oil-tank`, `wood-store`; wiring plan `twin/haus-erzgebirge.yaml` (PT1H, one year).
- [x] Indicators (`HouseKpis`): electricity, oil, wood, final energy, kWh/m²a, weather-adjusted (G20/15), boiler losses, standby, base load, peak, cost, CO2.
- [x] Calibration against bills (`BillCalibration`): CSV template, tank levels, wood by heating degree days, DWD daily means, LM/Broyden, residuals, CV(RMSE)/NMBE, identifiability; real data only in `config/private/`.
- [x] Demo `bin/opentsx house [--calibrate …]` with nine levers as what-if; tests for balances, determinism, −1 K, empty tank, identifiability.
- [x] Simulated example bills 2022 to 2025 (`SimulatedBills`, hidden true parameters, gaps and reading errors) under `twin/beispiel/`; `bin/opentsx house --simulated`; test: H/η and appliance scale found, hot water reported as not determined.
- [ ] Calibrate with real bills (years, tank levels, wood quantities, living area/year of construction from the homeowner) → **gate B (house)**.
- [ ] Proof of a measure: measured periods before/after, weather-adjusted, against calibration uncertainty.
- [x] House and boat on real weather: `bin/opentsx house --history data/history --weather-year 2022-2025` (hourly air temperature Marienberg 03166, 10-min global radiation Fichtelberg 01358, MEASURED; heating degree days, oil, wood per year, levers per weather year) and `bin/opentsx boat --history … --weather-year 2024` (10-min global radiation Dörnick 06163 instead of synthetic cloud cover); weather archive 2019 to today via `bin/opentsx market weather-backfill` (parser from `opentsx-dwd`).
- [ ] Variants: buffer tank/water-bearing stove, multi-zone model, forecast "Will the oil last?", optimizer as policy (order time, wood/oil split), DWD fetch instead of CSV.

**P9.8: Boat (Etap 22), off-grid energy system**, concept: [`10-BOOT-ZWILLING.md`](../docs/datamodel-2026/10-BOOT-ZWILLING.md)
- [x] Concept with assumptions (power station ~1 kWh, 2 × 100 Wp, shore power; laptops, Pixel 10, Starlink Mini, Raspberry Pi, VHF radio), usage modes, KPIs, levers, mapping boat ↔ H2 chain ↔ house.
- [x] Building blocks `boat-mode`, `solar`, `shore-power`, `device` (×6), `power-station` (package `org.opentsx.twin.boat`); wiring plan `twin/boot-etap22.yaml` (15 min, 14 days).
- [x] Demo `bin/opentsx boat`: shadow of the synthetic cruise, what-if (+100 Wp, Starlink 3 h/day, 2 kWh, shore power every 3rd day, charge laptops at noon, inverter, Starlink Standard, weekend) as Δ alternative − reference (modelled).
- [x] CSV import of real operating data (`BoatData`, template `twin/bootsdaten-vorlage.csv`, origin MEASURED/REPORTED) and least-squares calibration (`Calibration`, `--calibrate`); tests for balance, limits, determinism, what-if, import, calibration.
- [x] Simulated operating data (`SimulatedLogs`, `twin/beispiel/boot-logs-simuliert.csv`: hidden parameters, cloud cover, Starlink pause, 5-min export, noise, gaps) and `bin/opentsx boat --simulated`; calibration recovers the hidden values to within ~1 %.
- [ ] Load the owner's real data (power station export, Starlink, logbook) under `config/private/bootsdaten.csv`; replace the assumptions from §2.
- [ ] Raspberry Pi as an edge publisher (`opentsx.smartmetric.v1` over MQTT → infinimesh → KafScale); return path only with approval.
- [ ] Scenario in the Studio (generic export via `BoatDemo.plan/observed/reference/alternatives`).

**P9.9: Explore the plant, visual inventory and maintenance (Studio view 4)**, concept: [`12-EXPLORATIVES-UI-INVENTAR.md`](../docs/datamodel-2026/12-EXPLORATIVES-UI-INVENTAR.md)
- [x] E0: concept (layers map/floor plan/deck plan, level of detail, hover card, data sheet, image origin, references to the standards RDS-PP/IEC 61400-25/ZEUS/ISO 14224, maintenance coupling, infinimesh connection) and static prototype `docs/prototypes/anlagen-explorer.html` for wind farm, house and boat with the atom ids of the wiring plans; example inventory `twin/inventar-beispiel.yaml` (`opentsx.inventory.v1`, invented values).
- [ ] E1: `opentsx.inventory.v1` as JSON Schema; validation against the wiring plan (atom exists, id prefix = twin id); inventory in the `StudioExport`; tab "Explore the plant" in the Studio; `tools/build_explorer.py` (analogous to `build_studio.py`).
- [ ] E1: storage in Iceberg `inventory.assets/media/documents` (versioned), object store with SHA-256; clarify the mapping in OpenMetadata (no entity type for physical assets known, to be checked).
- [ ] E2: live values from Shadow/KafScale and infinimesh (`reported`, online/offline, staleness), time slider via Replay/Iceberg time travel, jumps to statistics and the node graph.
- [ ] E3: maintenance topic `tsx.<domain>.maintenance.v1` (`opentsx.maintenance.v1`, deterministic `eventId`), work order proposals from alarms, intervals and device status, work orders as interventions in the twin (availability), what-if "postpone maintenance", CSV import/export.
- [ ] E3: command dialog only with approval (`opentsx.command.v1` → infinimesh `desired`, `commandable: false` for safety devices).
- [ ] E4: orthophoto base maps (check licenses per country), 360° panoramas, glTF models; CMMS connection (for example openMAINT; SAP PM in an enterprise setting) via API.
- [ ] Research follow-up: RDS-PP application guideline for wind (document number, codes), FGW TR 7/ZEUS (key structure, license), ISO 14224 classes for house/boat, license and API of openMAINT, namespace/device hierarchy and online status in `infinimesh/core`.

## Open decisions (moved from TASK-011)

Moved from TASK-011 (commit 551815a3), lines 384 to 385.

- [ ] Confirm D18, D19, D21 to D24 (P9 proposals).
- [ ] Confirm D30 to D36 (P9.9 explorer, inventory, maintenance); supply real photos/data sheets per system (private: `config/private/`).

## Energy system and project environment (moved from TASK-011)

Moved from TASK-011 (commit 551815a3), lines 387 to 393.

**Energy system and project environment (P9, organisational, outside the code)**
- [ ] Meter data: order the converter and cabling at the fibre-optic termination (Modbus of the grid operator's meters; free fibre pairs available), obtain the register maps of the meters.
- [ ] Agree on the data exchange with the electrolyzer operator: park computer data over IEC 104 in exchange for operating data (load, state, line pressure, offtake).
- [ ] Request forecast and schedule data from the direct marketer (for the PPA shadow operation before the switch of the metering concept).
- [ ] Obtain the interface description (Swagger/OpenAPI) of the control system API.
- [ ] Coordinate the concrete integration point (where data is fed in, hardware/VPN), planned for the coming week.
- [ ] Assessment of direct line/direct supply (technical feasibility behind separate grid connections) as input for wiring plan templates (P9.5).
