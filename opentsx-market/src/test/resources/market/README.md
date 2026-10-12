# Test fixtures of opentsx-market

Small slices, enough for the parser and archive tests; no network is used in the tests.

| File | Content | Source and licence |
|------|---------|--------------------|
| `smard-4169-*.json` | day-ahead price DE-LU around the 15-minute MTU switch (2025-09-30 20:00 to 2025-10-01 02:00 UTC), index and data files | Bundesnetzagentur, SMARD.de, CC BY 4.0 (excerpt) |
| `smard-4067-*.json` | wind onshore generation, quarter-hours in MWh, with a trailing `null` | Bundesnetzagentur, SMARD.de, CC BY 4.0 (excerpt) |
| `energy-charts-price-2024-03-31.json` | day-ahead price on the spring DST day (23 hours) | Energy-Charts.info (Fraunhofer ISE), data Bundesnetzagentur, SMARD.de, CC BY 4.0 |
| `entsoe-*.xml` | shape of ENTSO-E A44, A65 and acknowledgement documents | written by hand, values invented |
| `rebap-*.csv`, `marktwerte.csv` | reBAP and monthly market values in the CSV formats of the netztransparenz.de WebAPI documentation (Format 9, Format 12) | the documentation's examples, partly modified |
| `partner-schedule.csv` | a direct-marketer schedule across the end of DST | invented |
| `history/` | archive slices (archive format) for the twin adapter tests | invented, shaped like the archive |
