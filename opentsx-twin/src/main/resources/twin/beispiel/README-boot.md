# Boat (Etap 22): simulated operating data

**SIMULATED, not measurements.** `boot-logs-simuliert.csv` stands in for real exports until they exist, and shows
import and calibration from start to end: `bin/opentsx boat --simulated`.

* **Written by:** `org.opentsx.twin.boat.SimulatedLogs`, seed `20260704`, deterministic
  (`java -cp "$(bin/opentsx classpath opentsx-twin)" org.opentsx.twin.boat.SimulatedLogs <file.csv> [seed]`).
  The test `SimulatedLogsTest` checks that the file is exactly the output of the generator; regenerate it after model
  changes.
* **Hidden true parameters** (they differ from the plan defaults): `solar.derate` 0.62 (default 0.75), Starlink
  scale 1.25 (default 1.0), laptop scale 1.3 (default 1.0).
* **Trip:** 14 days from Saturday 2026-07-04, itinerary `UHAXSHSAXXSHSU` (U away, H harbour, S sail to a harbour,
  A sail to an anchorage, X at anchor). Overcast and rain on days 8 to 10 (at anchor), Starlink off on day 4 (2026-07-08).
* **Shape:** like an app export of the power station every 5 min, joined with the logbook (`mode`, `shore`,
  `starlink_on`) and hourly values of a weather station (`ghi_wm2`). Local time Europe/Berlin without offset.
* **Imperfections:** SoC in whole percent; noise on solar (3 % + 1 W), load (2 % + 1 W) and irradiance (5 % per
  hour); no app data on days with nobody on board, three nights without connection (0:00 to 6:30), four gaps of 30 to
  120 min (Bluetooth out of range). The column `note` starts with `SIMULATED` at every event.
* **Result of the calibration:** derate 0.614, laptop scale 1.295, Starlink scale 1.249; SoC deviation 0.5
  percentage points. Concept: `docs/datamodel-2026/10-BOOT-ZWILLING.md` (German), section "Simulierte Beispieldaten".
