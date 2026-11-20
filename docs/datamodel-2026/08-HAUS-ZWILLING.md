# OpenTSx house twin: operations management and efficiency measurement for homeowners (concept, P9.7)

> Status: **proposal with a runnable example**, 2026-10-12. Carries the method of the energy system twin
> ([07-DIGITAL-TWIN-BSP](07-DIGITAL-TWIN-BSP.md)) and the assessment principle of P8 ("interplay and coordination
> potential", [TASK-011](../../EVOLUTION/TASK-011-datamodel-refresher-2026.md)) over to a single-family house.
> Implemented in the module [`opentsx-twin`](../../opentsx-twin/README.md), command `bin/opentsx house`.
>
> **All numbers about the house are invented example assumptions.** They hold only until real bills replace them and
> the plan is calibrated (section 7). Savings from the twin are **modeled**, not proven (section 8).

---

## 1. Summary

The Terawatt case asks: *what do we lose through separate operations management, and what do we gain through
coordinated action?* In the house the same question reads: *what does the current way of running heating, hot water
and appliances cost, and what does another way bring, under the same weather and the same comfort limits?*

The proposal in five sentences:

1. **The same twin, other parts.** The house is a wiring plan of atoms (weather, building, oil boiler, wood stove,
   hot water, appliances, meter, oil tank, wood store) with the same atom contract MVC+P, the same runner and the
   same three operating modes as the H2 chain.
2. **The bills are the measurement.** Annual and partial bills (electricity, heating oil, wood) are the only reliable
   data. They are kept as `REPORTED`; everything hourly in between is model.
3. **Calibrate instead of estimate.** A few parameters (heat loss coefficient H, hot water and appliance scale) are
   fitted deterministically by least squares to the billing periods. What the data cannot separate is reported
   instead of hidden.
4. **Each lever is an alternative to the reference.** −1 K, night setback, unused rooms, more wood, hot water
   temperature, pump, chest freezer, standby, TV time: each is alternative minus reference, same weather, same state
   of information.
5. **Modeled ≠ proven.** A saving counts as proven only when measured periods before and after the change are
   compared with weather correction.

---

## 2. Carrying over the method

| Part of the method | Terawatt case (07) | House (this document) |
|--------------------|--------------------|-----------------------|
| Atom contract MVC+P | turbine, electrolyzer, H2 pipeline, battery, market ... | weather, building, oil boiler, wood stove, hot water, appliances, meter, oil tank, wood store |
| Wiring plan | `twin/h2-chain.yaml` | [`twin/haus-erzgebirge.yaml`](../../opentsx-twin/src/main/resources/twin/haus-erzgebirge.yaml) |
| Base step | 1 min | 1 h (the building reacts in hours to days; one year = 8760 supersteps) |
| Phases | SOURCES → POLICY → ASSETS → BALANCE → FLOWS → VIEWS | the same: weather, appliances, hot water → (policy) → boiler, stove → electricity meter → building, tank, wood store |
| Feedback | pipeline pressure → electrolyzer (one step delayed) | thermostat demand and tank level → boiler (one step delayed) |
| Shadow | live measurement drives the twin | bills, optionally meter photos, dipstick, DWD temperatures |
| What-if | another intervention log on the same history | levers as interventions on the same reference year |
| Forecast | from a snapshot with forecasts, policy exchangeable | from today's state (tank level, wood stock): "Does the oil last until March?" |
| Origin ladder | `MEASURED > REPORTED > FORECAST > ESTIMATED > SYNTHETIC` | the same; bill = REPORTED, station temperature = MEASURED, hourly split = SYNTHETIC, or ESTIMATED after calibration |
| Stages A to D | data and potential → shadow → causal chains → optimization | see below |
| Assessment principle | coordinated alternative − reference operation | lever alternative − today's way of running, same weather |

**Stages with a value threshold for the homeowner:**

| Stage | Content | Effort | Threshold (go on only if ...) |
|-------|---------|--------|-------------------------------|
| **A: bills and key figures** | record the bills, kWh/m²a, weather-corrected consumption, comparison with benchmarks | 1 to 2 h, no simulation | consumption is conspicuously high or a decision is due (boiler replacement, insulation) |
| **B: calibrated shadow** | fit the twin to the bills, residuals per period, check identifiability | a calibration run, DWD daily means if needed | good enough fit (guide value CV(RMSE) ≤ 15 %, NMBE ≤ ±5 % for monthly data) |
| **C: levers and causal chains** | what-ifs per lever, a chain such as setback → heat-up peak → boiler cycles → losses | computing time, no investment | the modeled potential is worth the measure |
| **D: operations management** | schedule (heating times, wood and oil split, time to order oil) as a policy, forecast from the tank level | calendar, dipstick | proof in the following year: measured, weather-corrected saving |

---

## 3. The example house (assumptions)

**Location (fictitious):** central Ore Mountains (Erzgebirge), about **650 m above sea level**, 50.6° N / 13.0° E.

| Quantity | Assumption | Remark |
|----------|------------|--------|
| Annual mean temperature | ≈ 7.0 °C | synthetic climate of the weather atom (annual cycle, daily cycle, weather situations from fixed sines plus seeded randomness) |
| Coldest stretch | mid January, daily mean ≈ −2 °C, minimum in the example year ≈ −11 °C | |
| Design outdoor temperature | **≈ −14 °C** | design value, assumption; for the heating load calculation, not for the simulation |
| Heating degree days G20/15 | reference **4,300 Kd** (long-term, assumption); synthetic example year 4,567 Kd | for a real assessment, DWD or IWU climate data of the nearest station |
| Heating days | ≈ 300 per year (heating limit 15 °C, 24 h mean) | |
| Global radiation | ≈ 900 to 1,000 kWh/m²a | synthetic |

**Building:** single-family house, built in 1978, solid construction, two full storeys, unheated cellar and attic.
Partly renovated: windows 2005 (double thermal insulation glazing), top floor ceiling insulated in 2012, outer walls
not insulated.

| Room | Area | Heated | Use |
|------|-----:|--------|-----|
| Living room (ground floor) with wood stove | 35 m² | yes | every evening, all day at weekends |
| Kitchen (ground floor) | 15 m² | yes | cooking, radio, small TV |
| Hall and stairs (ground floor) | 14 m² | yes | |
| WC and utility room (ground floor) | 5 m² | yes | washing machine |
| Bedroom (upper floor) | 17 m² | yes | TV |
| Bathroom (upper floor) | 9 m² | yes | |
| Study (upper floor) | 13 m² | yes | now and then |
| Guest room, former children's room (upper floor) | 13 m² | yes | rarely |
| Hall (upper floor) | 9 m² | yes | |
| **Heated living area** | **130 m²** | | of which 20 % rarely used (study and guest room) |
| Cellar: boiler room, tank room, store room, workshop | ≈ 65 m² | no | boiler, 150 l storage tank, 2 × 2,000 l battery tank, chest freezer |
| Attic | ≈ 60 m² | no | top floor ceiling insulated |

**Thermal figures (assumptions, calibrated):** heat loss coefficient **H = 200 W/K** (transmission plus ventilation;
≈ 6.8 kW heating load at −14 °C without gains), effective heat capacity **14 kWh/K** (heavy construction, time
constant C/H ≈ 70 h), solar aperture 4 m² (window area × g value × shading).

**Residents and daily routine (2 persons):**

| | weekdays | weekend |
|---|----------|---------|
| get up / hot water | 6 to 7 h | 8 to 10 h |
| away from home | 7 to 16 h | none |
| cooking (electric stove) | 18 to 19 h | 12 to 13 h |
| TV in the living room | 19 to 23 h | 15 to 23 h |
| wood stove | 18 to 22 h in the heating season | the same |
| washing | none | Saturday and Sunday 10 to 11 h |
| room temperature | 21 °C from 6 to 22 h, at night 3 K setback | the same |

The model is **one** thermal zone. Rooms enter through their area (for example the share of rarely used rooms); it
does not model the temperatures of single rooms.

---

## 4. Inventory and heating

### 4.1 Appliances (powers are typical values, assumptions)

| Appliance | Model in the twin | Assumption | Result in the example year |
|-----------|-------------------|------------|---------------------------:|
| Fridge (kitchen) | continuous power (compressor mean) | 25 W | 219 kWh |
| Chest freezer (cellar, older) | continuous power, outside the envelope | 45 W | 394 kWh |
| Electric stove with oven | 1.3 kW in the cooking window | see daily routine | 475 kWh |
| TV living room (LED, 55″) | 100 W, standby 0.5 W | | 191 kWh |
| TV bedroom (older) | 70 W, standby 3 W | | 58 kWh |
| TV kitchen (small) | 40 W, standby 2 W | | 45 kWh |
| Radio (kitchen) | 6 W, standby 2 W | | 21 kWh |
| Lighting (mixed LED and halogen) | 140 W in the evening hours, +60 % in winter | | 372 kWh |
| Washing machine | 0.9 kWh per weekend day | *added* | 94 kWh |
| Small appliances (coffee, PC, chargers) | 150 W in times of use, standby 12 W | *added* | 350 kWh |
| Router | 10 W continuous | *added* | 88 kWh |
| **Household electricity** | | | **2,306 kWh** |
| Heating circulation pump (unregulated) | 65 W in the heating season | | 490 kWh |
| Oil burner (fan, oil pump) | 120 W while burning | | 211 kWh |
| **Electricity in total** | | | **≈ 3,000 kWh** |

*Added* = not named in the brief, but present in every household; adjust or delete with real data. Electrical
appliances inside the envelope count as internal heat gains, and so do 2 × 80 W per person at home.

### 4.2 Heating: the chosen configuration

**Oil boiler plus wood stove in the living room** (no combination with a buffer tank). Reason: in the Ore Mountains
this is the most common "oil and wood heating" in existing houses. The oil boiler (low-temperature boiler of the
1990s, 18 kW, single-stage burner) supplies the radiators and the 150 l hot water tank. The wood stove (split logs,
spruce) heats the living room directly in the evening; its heat lowers the demand on the boiler. A stove with a water
jacket or a wood gasification boiler with a buffer would be an atom of its own (buffer tank as a stock) and is
planned as a variant.

| Assumption | Value |
|------------|-------|
| Boiler: rated power / efficiency | 18 kW / 0.90 (above minimum load) |
| Minimum load, cycling loss | 35 % of rated power; below that the efficiency falls linearly by up to 15 % |
| Standby loss | 0.2 kW, all year (the boiler is kept warm) |
| Heating oil EL | 10 kWh/l (calorific value), 1.00 €/l |
| Wood stove | 6 kW fuel power, efficiency 0.70, 4 h per evening in the heating season |
| Wood | spruce air dried ≈ 1,500 kWh/Ster (beech ≈ 2,000 kWh/Ster), 90 €/Ster |
| Hot water | 2 × 40 l per day at 45 °C (cold water 10 °C), tank 60 °C, tank loss 60 W |
| Oil tank | 4,000 l, order threshold 600 l |
| Electricity price | 0.36 €/kWh |

No PV, no heat pump: the atoms for them are left out on purpose; they would be later variants (generation at the
meter as in the energy system, heat pump as a converter with a COP).

---

## 5. Efficiency measurement

### 5.1 Key figures

| Key figure | Definition | Example year (synthetic) |
|------------|------------|-------------------------:|
| Electricity | kWh/a at the meter | 3,007 kWh |
| of which heating electricity | pump + burner | 701 kWh |
| Base load | mean power 2 to 5 h | 156 W |
| Standby share | switchable standby / electricity | 4.8 % |
| Peak | highest hourly mean | 2.0 kW |
| Heating oil | litres per year (consumption, not delivery) | 1,916 l |
| Wood | Ster per year | 4.9 Ster |
| Final energy heat | oil litres × 10 kWh/l + Ster × kWh/Ster | 26,575 kWh |
| Specific | final energy heat / heated area | 204 kWh/m²a |
| Weather corrected | heating share × G_ref / G + hot water + standby | 25,248 kWh (on 4,300 Kd) |
| Useful heat | boiler heat + stove heat | 20,127 kWh |
| Boiler losses / annual efficiency | fuel − heat / heat ÷ fuel | 4,225 kWh / 0.78 |
| Comfort | hours more than 0.5 K below the setpoint in the heating season | 138 h (heating up after the night setback) |
| Cost, CO2 | tariffs; GEG annex 9 factors (oil 310, electricity 560, wood 20 g/kWh) | 3,444 € per year, 7.8 t per year |

The weather correction scales only the weather-dependent part (space heating). Hot water and standby loss stay
unchanged. This is more accurate than the usual correction of the total consumption.

### 5.2 Cheap ways to measure (from the cheapest to the most expensive)

| Measurement | Cost | Gives | Origin |
|-------------|------|-------|--------|
| Bills of the last years | 0 € | annual and partial totals of electricity, oil deliveries | REPORTED |
| Photo of the electricity meter on the first of the month | 0 € | monthly values → separates base load and seasonal shares | MEASURED |
| Read the dipstick or tank gauge at each delivery and monthly | 0 € | oil consumption instead of delivery | MEASURED |
| Wood log (baskets per week → Ster) | 0 € | wood share per period | REPORTED |
| DWD daily mean temperature of the nearest station (free) | 0 € | real weather instead of synthetic | MEASURED |
| Plug-in meter (chest freezer, TV corner) | 10 to 20 € each | appliance power and standby | MEASURED |
| Burner hour counter (often present in the boiler) | 0 to 30 € | burner run time × nozzle throughput = oil | MEASURED |
| Radio thermometer in the living room | 15 € | indoor temperature → checks the thermostat model | MEASURED |

---

## 6. Wiring plan of the house twin

```
SOURCES   weather ──t_out,ghi──────────────────────────────┐
          dhw ──q_dhw──┐                                   │
          appliances ──┼─q_gain───────────────────────┐    │
                       │  └─p_el──────────┐           │    │
ASSETS    stove ───────┼─q_heat───────────┼───────────┤    │
          boiler ◄─────┘ (q_req, season, tank_level: one step delayed)
             ├─q_space────────────────────┼───────────┤    │
             ├─p_el───────────────────────┤           │    │
             └─fuel_lph──────┐            │           │    │
BALANCE   meter ◄────────────┼────────────┘           │    │
FLOWS     tank ◄─────────────┘   building ◄───────────┴────┘   woodstore ◄── stove.wood_ster_ph
```

| Atom | Type | Phase | Core behavior |
|------|------|-------|---------------|
| `weather` | `weather` | SOURCES | synthetic climate or a bound measurement; heating degree days G20/15 in the state |
| `dhw` | `dhw` | SOURCES | draw profile for weekdays and weekends plus tank loss; setpoint `t_store_c` |
| `appliances` | `appliances` | SOURCES | inventory with power, standby, time windows; setpoints `standby_off`, `factor.<appliance>` |
| `stove` | `wood-stove` | ASSETS | burning per evening; or the amount of wood from the bill (`wood_kwh_per_day`) |
| `boiler` | `oil-boiler` | ASSETS | hot water priority, rated power, minimum load and cycling, standby, pump; idle with an empty tank |
| `meter` | `meter` | BALANCE | electricity balance, cost, base load, peak; residual against the meter value |
| `building` | `building` | FLOWS | 1R1C heat balance, thermostat with day and night setpoint, heating limit; setpoints `setpoint_c`, `setback_k`, `unused_setback_k` |
| `tank` | `oil-tank` | FLOWS | stock in litres, delivery as an intervention, order warning, "tank empty" |
| `woodstore` | `wood-store` | FLOWS | wood stock in Ster, delivery as an intervention |

Invariants checked by the tests: the heat balance of the building is closed, the oil, wood and electricity balances
are closed, a repeated run is bit-identical, continuing from a snapshot is exact, −1 K lowers the heat demand, an
empty tank stops the boiler.

---

## 7. Calibration with bills

### 7.1 Inputs

One CSV row per period, template:
[`twin/abrechnungen-vorlage.csv`](../../opentsx-twin/src/main/resources/twin/abrechnungen-vorlage.csv) (only invented
example rows).

| Column | Content | Required |
|--------|---------|----------|
| `period_from`, `period_to` | date, both inclusive (`2024-01-31` or `31.01.2024`) | yes |
| `electricity_kwh` | consumption from the bill or the meter reading | no |
| `heating_oil_l` | litres **delivered** in the period (0 if only read) | no |
| `tank_start_l`, `tank_end_l` | tank level at the start and the end; then consumption = delivery + start − end | no |
| `wood_ster` | wood **burned** in the period | no |
| `cost_eur`, `note` | only for display | no |

The German column names `zeitraum_von`, `zeitraum_bis`, `strom_kwh`, `heizoel_liter`, `tank_start_liter`,
`tank_ende_liter`, `holz_ster`, `kosten_eur` and `notiz` are accepted as aliases. The bills may span at most 3653
days, and a calibration run over them may take at most 87673 steps of the plan (3653 days of the hourly steps of the
bundled plan, counted from local midnight to local midnight, with one more clock change in autumn than in spring).
A bills file has at most 1200 data rows (ten bills a month for 10 years) and 1048576 bytes (1 MiB); a `--weather`
file has at most 87673 data rows (one per step of the longest house plan) and 16777216 bytes (16 MiB). Every line
after the header that is neither blank nor a comment counts as a row; blank and comment lines count for the bytes
only.

Rows may overlap. Typical are annual electricity bills with their own reference date next to oil periods from one
reading to the next. Separator `,` or `;` (spreadsheet export with a decimal comma).

### 7.2 Method

1. The twin runs hourly over the whole billing period. Weather: synthetic or the daily mean temperatures of a DWD
   station (`--weather` with a file `date,t_mean_c`, the daily cycle is added). Wood: the Ster from the rows, spread
   evenly over the heating days (daily mean < 15 °C) of the period (REPORTED). The model therefore only has to
   explain the oil *in addition* to the wood actually burned.
2. Per row, modeled electricity (kWh) and oil consumption (l) are summed. Oil rows without a tank level (delivery as
   consumption) enter only with a weight of 0.2.
3. Fitted are **H** (heat loss coefficient), the **hot water scale** and the **appliance scale**, with
   Levenberg-Marquardt on the normalized residuals of the periods: the Jacobian once by forward differences, then
   Broyden updates, deterministic, without new dependencies. Typically 10 to 25 twin runs. Bounds: H 40 to 1500 W/K,
   scales 0.3 to 3.
4. Output: parameters with relative standard error, residual per period, CV(RMSE) and NMBE per quantity, condition
   number and largest parameter correlation, warnings, calibrated wiring plan. A parameter with a relative standard
   error above 25 % counts as "poorly determined"; then the fit reports "identifiable false". The levers (section 8)
   are recalculated with the calibrated plan.

Fixed (assumptions, not fitted): boiler efficiency, standby loss, kWh per Ster, 10 kWh/l.

### 7.3 Limits of identifiability

* **Annual totals alone separate almost nothing.** One electricity and one oil total per year are two numbers for
  three parameters. The fit reports "not identifiable" (test `flagsAnnualTotalsAsNotIdentifiable`). With the template
  (two years, one half year with tank readings) the hot water scale stays undetermined: relative standard error
  ≈ 100 %, correlation with H 0.996.
* **Summer separates hot water from the building.** Monthly tank readings from July to December recover known
  parameters to better than 2 % (test `recoversKnownParametersFromMonthlySyntheticBills`). Winter months alone leave H
  and hot water correlated at ≈ 0.99.
* **Delivery ≠ consumption.** Without tank levels the delivery is taken as consumption, and the row is marked.
  Remedy: combine several years into one row (the tank level error spreads) or read the dipstick.
* **Only H/η is determined.** A worse boiler and a worse building look the same in totals. Only a burner hour counter
  or a flue gas measurement (chimney sweep protocol) separates them.
* **Synthetic weather distorts H.** A mild or cold winter otherwise ends up in the building parameter. For real bills,
  therefore bind DWD daily means.

### 7.4 Origin

| Quantity | Origin |
|----------|--------|
| Bill totals, amounts of wood | REPORTED |
| Meter photo, dipstick, DWD temperature | MEASURED |
| Hourly split (appliances, space heating, hot water) | SYNTHETIC; ESTIMATED after calibration (model fitted to data) |
| Lever results | modeled (what-if), never MEASURED |

### 7.5 Steps with real data

```bash
mkdir -p config/private                                  # excluded from commits by .gitignore
cp opentsx-twin/src/main/resources/twin/abrechnungen-vorlage.csv config/private/abrechnungen.csv
# delete the example rows, enter your own bills; optionally DWD daily means as date,t_mean_c
bin/opentsx house --calibrate config/private/abrechnungen.csv [--weather config/private/tagesmittel.csv]
```

### 7.6 Simulated example data (until the real bills are there)

So that the chain bill → calibration → levers runs already, **simulated** bills are bundled:
[`twin/beispiel/`](../../opentsx-twin/src/main/resources/twin/beispiel/README.md). Every file and every row is marked
as `SIMULATED`.

**Generation** (`SimulatedBills`, seed 2026, deterministic):
- The house model runs 2022 to 2025 with **hidden true parameters**: H = 230 W/K (+15 %), hot water 0.8×,
  appliances 1.1×, boiler efficiency 0.86 instead of the assumed 0.90.
- Weather: synthetic Ore Mountains climate with differences between the years (winter 2022/23 mild, 2024/25 cold),
  daily means rounded to 0.1 °C.

**Built-in imperfections:**
- Annual electricity bills with moving reading dates; the bill 2023/24 is missing; one meter reading is estimated by
  the supplier (+4 %).
- Oil deliveries at irregular dates (1,500, 2,000 and 2,500 l). Tank levels are not noted until 2023, read by the
  driver in 2024 and read monthly with the dipstick in 2025 (±25 l, rounded to 10 l). The reading on 1 August is
  missing.
- Wood per winter rounded to 0.5 Ster; prices with the energy price crisis of 2022.

`bin/opentsx house --simulated` calibrates on all rows (22 twin runs, ≈ 40 s):

| Parameter | true (hidden) | expected with η = 0.90 assumed | calibrated | rel. standard error |
|-----------|--------------:|-------------------------------:|-----------:|--------------------:|
| H (W/K) | 230 | ≈ 241 (H·0.90/0.86) | 248 | 4.6 % |
| Appliance scale | 1.10 | 1.10 (+ estimated meter reading) | 1.12 | 3.3 % |
| Hot water scale | 0.80 | ≈ 0.84 | 0.30 (lower bound) | 191 % → **not determined** |

**Goodness of fit:**
- Electricity: CV(RMSE) 1.9 %, NMBE −0.2 %.
- Heating oil: CV(RMSE) 19 %, NMBE +0.3 %; the monthly values in summer scatter a lot.

**What this shows:**
- **H and appliances can be determined**, but H only as H/η. With the right η (0.86, for example from the chimney
  sweep protocol) H comes out as 240 W/K instead of 230. The rest compensates for the undetermined hot water.
- **Hot water cannot be determined with dipstick readings.** The reading error (±25 l per reading) is larger than
  the hot water signal of a summer month (≈ 8 l difference between 0.3× and 0.8×). Remedy: a burner hour counter in
  summer or a hot water meter.
- **Deliveries without a tank level** (2022/23) deviate by 10 to 15 % and are weighted only lightly.

The test `SimulatedBillsTest` checks:
- that the files are generated reproducibly and are marked;
- that the calibration on the rows with tank levels (04/2024 to 12/2025) recovers H/η to 6 % and the appliance scale
  to 0.05;
- that hot water is reported as not determined.

---

## 8. Levers (what-if)

Each lever is a set of interventions on the same reference year (same weather, same deliveries). Result =
**alternative − reference**. Example house, **not calibrated, modeled**:

| Lever | Intervention | Oil | Wood | Electricity | Final energy | Cost | CO2 |
|-------|--------------|----:|-----:|------------:|-------------:|-----:|----:|
| −1 K room temperature | `building.setpoint_c` 21 → 20 | −151 l | 0 | −16 kWh | −1,513 kWh | −157 € | −478 kg |
| deeper night setback | `setback_k` 3 → 5 K | ≈ 0 | 0 | ≈ 0 | ≈ 0 | ≈ 0 | ≈ 0 |
| rarely used rooms cooler | `unused_setback_k` 4 K on 20 % of the area | −123 l | 0 | −13 kWh | −1,229 kWh | −128 € | −388 kg |
| more wood | stove 4 → 7 h per evening | −355 l | +3.8 Ster | −46 kWh | +2,104 kWh | −32 € | −1,013 kg |
| hot water 50 °C | `dhw.t_store_c` 60 → 50 | −16 l | 0 | −2 kWh | −162 kWh | −17 € | −52 kg |
| high-efficiency pump | `boiler.pump_w` 65 → 8 W | 0 | 0 | −430 kWh | 0 | −155 € | −241 kg |
| new chest freezer | `factor.gefriertruhe` 0.4 | 0 | 0 | −237 kWh | 0 | −85 € | −132 kg |
| switch off standby | `standby_off` | +13 l | 0 | −142 kWh | +133 kWh | −38 € | −39 kg |
| less TV | TV time −30 % | +8 l | 0 | −88 kWh | +77 kWh | −24 € | −25 kg |
| all except "more wood" | combination | −261 l | 0 | −914 kWh | −2,608 kWh | −590 € | −1.3 t |

What the twin makes visible here that a look at single measures misses:

* **The night setback hardly acts in the heavy house.** With a time constant of ≈ 70 h the house cools by only 1 to
  2 K in eight hours, and the stove keeps heating until 22 h. The deeper setback is therefore never reached. This
  holds for the one-zone model; single rooms with radiators cool faster.
* **Saving electricity means a little more oil in winter.** Standby and TVs heat the inside too (gains). The cost
  advantage stays, but the final energy rises slightly. This coupling is the house counterpart of the "interplay" in
  P8.
* **More wood lowers cost and CO2, but not the final energy.** The stove has a worse efficiency than the boiler.
  Depending on the goal (cost, CO2, kWh) the same measure is good or bad. This is why the report shows all quantities
  separately.
* **The boiler is oversized** (18 kW at ≈ 7 kW heating load). Standby and cycling losses amount to ≈ 4,200 kWh per
  year (annual efficiency 0.78). This is an argument for replacing the boiler or throttling the burner, to be checked
  in stage C.

**Modeled vs. proven.** The table is modeled potential. A saving is proven only when measured periods (meter,
dipstick) before and after the change are compared with weather correction and the difference is larger than the
calibration uncertainty. A comparison with perfect knowledge of the weather is at most a labeled upper bound.

---

## 9. Mapping house ↔ Terawatt case

| House | Terawatt case | Shared pattern |
|-------|---------------|----------------|
| Oil boiler (η, rated power, minimum load, cycling) | electrolyzer (kWh/kg, rated power, minimum load, ramp) | converter with efficiency and operating limits |
| Wood stove | battery or second source | a second, differently efficient way to the same demand |
| Oil tank, wood store, thermal mass of the house | H2 pipeline (linepack), battery SoC | stock with time to limit and a warning |
| "Tank empty" → boiler off | pipeline pressure → protective curtailment | feedback across the step boundary |
| Comfort limit (setpoint − 0.5 K) | operating limits (pressure, schedule tolerance) | constraint, violation as a key figure |
| Heating limit, summer operation | operating rules (grid restriction, bats) | calendar or rule source |
| Electricity and oil tariff | market price | assessment in € |
| Homeowner | operator, marketer | decides, bears the costs, interventions in the log |
| Heating plan (setpoints, stove times, time to order) | schedule | policy, exchangeable |
| Bill | meter data, partner data | REPORTED on the origin ladder |
| Delivery of oil or wood | H2 offtake, delivery | intervention on a stock |
| Lever alternative − reference year | coordinated alternative − reference operation | coordination potential, same boundary conditions |

---

## 10. Data protection

* Real bills stay **local**: `.gitignore` (`**/config/private/**`) excludes `config/private/` from commits, and the
  weather or meter files there too.
* The repository holds only the template with invented rows and the example house.
* Results (calibrated parameters, key figures) contain no raw data. A comparison of several houses over generic
  figures follows the federation idea of P9.5.

---

## 11. State of implementation and open questions

**Implemented (PoC):** the atoms `weather`, `building`, `oil-boiler`, `wood-stove`, `dhw`, `appliances`, `meter`,
`oil-tank`, `wood-store` in the standard catalog. Also the wiring plan `twin/haus-erzgebirge.yaml`, `HouseScenario`
(reference, levers), `HouseKpis`, `BillCalibration` (CSV, DWD daily means, LM/Broyden, identifiability) and the demo
`bin/opentsx house` with `--calibrate` and `--simulated` (simulated example bills, section 7.6). Tests: balances,
determinism, snapshot, −1 K, empty tank, identifiability.

**Open:** buffer tank and a stove with a water jacket as a variant; a multi-zone model (one room per atom); the
forecast "Does the oil last until spring?" as a finished scenario; an optimizer as a policy (time to order by oil
price, wood and oil split); fetching from DWD instead of a CSV.

**Questions to the homeowner (for the calibration):**

1. Which years are there as bills (electricity with reference dates, oil deliveries with date and litres)?
2. Are tank levels known (dipstick, gauge, notes at deliveries)? If not: read them monthly from now on.
3. How much wood is burned per winter (Ster), which species, own felling or bought?
4. Living area, year of construction, renovations already done (windows, roof, facade, boiler)?
5. Boiler data: manufacturer, year, rated power, chimney sweep protocol (flue gas loss)?
6. Actual room temperatures and heating times (thermostat settings, night setback)?
7. Nearest town or DWD station for the temperature series?
