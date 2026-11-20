# SIMULATED example data for the house twin

**These files contain no real data.** They are simulated so that the calibration can be shown before real bills are
available.

| File | Content |
|------|---------|
| `haus-abrechnungen-simuliert.csv` | billing lines 2022 to 2025 in the format of the template `../abrechnungen-vorlage.csv`; every line is marked `SIMULIERT` in `notiz` |
| `haus-tagesmittel-simuliert.csv` | daily mean temperatures 2022 to 2025 (`datum,t_mittel_c`) of a fictitious site in the Erzgebirge (about 650 m) |

## How the files were made

`org.opentsx.twin.house.SimulatedBills` generates them, deterministically with **seed 2026**:

1. **Weather.** The synthetic climate of the plan `twin/haus-erzgebirge.yaml`, with one winter anomaly per heating
   year: 2022/23 mild (+1.8 K), 2024/25 cold (-2.0 K). Daily means are rounded to 0.1 °C.
2. **The "real" house.** The house model runs hourly from 2022-01-01 to 2025-12-31 with hidden parameters that differ
   from the assumptions of the plan:

   | Parameter | Plan (assumption) | hidden "true" value |
   |-----------|------------------:|--------------------:|
   | `building.lossWPerK` | 200 | **230** |
   | `dhw.scale` | 1.0 | **0.8** |
   | `appliances.scale` | 1.0 | **1.1** |
   | `boiler.eta` | 0.90 | **0.86** |

3. **Bills with imperfections:**
   * Electricity: annual bills with shifting reading dates. The 2023/24 bill is missing, one meter reading is
     estimated (+4 %), and there is an interim reading on 2025-12-31.
   * Heating oil: an order as soon as the tank falls below 700 to 1200 l, delivery 4 to 14 days later with 1,500,
     2,000 or 2,500 l. Tank levels are not noted until 2023 and read by the driver in 2024. In 2025 the tank is
     dipped monthly (±25 l, rounded to 10 l); the reading on 1 August is missing.
   * Wood: per winter in stere, rounded to 0.5.
   * Costs: with the 2022 energy price crisis.

## Use and regenerate

```bash
bin/opentsx house --simulated     # calibration on these files, comparison with the hidden parameters, levers
java -cp "$(bin/opentsx classpath opentsx-twin)" org.opentsx.twin.house.SimulatedBills opentsx-twin/src/main/resources
```

The test `SimulatedBillsTest` checks that the files match the generator exactly.
