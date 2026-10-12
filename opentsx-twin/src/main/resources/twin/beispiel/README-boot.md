# Boot (Etap 22): simulierte Betriebsdaten

**SIMULIERT – keine Messungen.** `boot-logs-simuliert.csv` ersetzt echte Exporte, bis sie vorliegen, und zeigt
Import und Kalibrierung von Anfang bis Ende: `bin/opentsx boat --simulated`.

* **Erzeugt von:** `org.opentsx.twin.boat.SimulatedLogs`, Seed `20260704`, deterministisch
  (`java -cp "$(bin/opentsx classpath opentsx-twin)" org.opentsx.twin.boat.SimulatedLogs <datei.csv> [seed]`).
  Der Test `SimulatedLogsTest` prüft, dass die Datei genau der Generatorausgabe entspricht; nach Modelländerungen
  neu erzeugen.
* **Verdeckte wahre Parameter** (weichen von den Plan-Standards ab): `solar.derate` 0,62 (Standard 0,75),
  Starlink-Faktor 1,25 (Standard 1,0), Laptop-Faktor 1,3 (Standard 1,0).
* **Törn:** 14 Tage ab Sa 4.7.2026, Reiseplan `UHAXSHSAXXSHSU` (U unbemannt, H Hafen, S Segeln → Hafen, A Segeln →
  Anker, X vor Anker). Bedeckt/Regen an den Tagen 8–10 (vor Anker), Starlink aus an Tag 4 (8.7.).
* **Form:** wie ein App-Export der Powerstation alle 5 min, zusammengeführt mit Logbuch (`modus`, `landstrom`,
  `starlink_an`) und Stundenwerten einer Wetterstation (`einstrahlung_wm2`). Ortszeit Europe/Berlin ohne Offset.
* **Unvollkommenheiten:** SoC in ganzen Prozent; Rauschen auf Solar (3 % + 1 W), Last (2 % + 1 W) und Einstrahlung
  (5 % je Stunde); keine App-Daten an unbemannten Tagen, drei Nächte ohne Verbindung (0–6:30 h), vier Lücken von
  30–120 min (Bluetooth außer Reichweite). Die Spalte `notiz` beginnt bei jedem Ereignis mit `SIMULIERT`.
* **Ergebnis der Kalibrierung:** derate 0,614, Laptop-Faktor 1,295, Starlink-Faktor 1,249; SoC-Abweichung
  0,5 Prozentpunkte. Konzept: `docs/datamodel-2026/10-BOOT-ZWILLING.md`, Abschnitt „Simulierte Beispieldaten“.
