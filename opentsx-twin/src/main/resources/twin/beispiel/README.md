# SIMULIERTE Beispieldaten für den Haus-Zwilling

**Diese Dateien enthalten keine echten Daten.** Sie sind simuliert, damit die Kalibrierung schon vor den echten
Abrechnungen vorgeführt werden kann. Konzept: [08-HAUS-ZWILLING §7.6](../../../../../../docs/datamodel-2026/08-HAUS-ZWILLING.md).

| Datei | Inhalt |
|-------|--------|
| `haus-abrechnungen-simuliert.csv` | Abrechnungszeilen 2022–2025 im Format der Vorlage `../abrechnungen-vorlage.csv`; jede Zeile ist in `notiz` als `SIMULIERT` markiert |
| `haus-tagesmittel-simuliert.csv` | Tagesmitteltemperaturen 2022–2025 (`datum,t_mittel_c`) eines fiktiven Standorts im Erzgebirge (≈ 650 m) |

## Wie die Dateien entstanden sind

Erzeugt von `org.opentsx.twin.house.SimulatedBills`, deterministisch mit **Seed 2026**:

1. **Wetter.** Das synthetische Klima des Plans `twin/haus-erzgebirge.yaml`, mit einer Winteranomalie je Heizjahr:
   2022/23 mild (+1,8 K), 2024/25 kalt (−2,0 K). Die Tagesmittel sind auf 0,1 °C gerundet.
2. **Das „echte“ Haus.** Das Hausmodell läuft stündlich vom 01.01.2022 bis 31.12.2025 mit verdeckten Parametern,
   die von den Annahmen des Plans abweichen:

   | Parameter | Plan (Annahme) | verdeckt „wahr“ |
   |-----------|---------------:|----------------:|
   | `building.lossWPerK` | 200 | **230** |
   | `dhw.scale` | 1,0 | **0,8** |
   | `appliances.scale` | 1,0 | **1,1** |
   | `boiler.eta` | 0,90 | **0,86** |

3. **Abrechnungen mit Unvollkommenheiten**:
   * Strom: Jahresabrechnungen mit wandernden Ablesedaten. Die Abrechnung 2023/24 fehlt, ein Zählerstand ist
     geschätzt (+4 %), zum 31.12.2025 gibt es eine Zwischenablesung.
   * Heizöl: Bestellung, sobald der Tank unter 700–1200 l fällt, Lieferung 4–14 Tage später mit 1.500, 2.000 oder
     2.500 l. Tankstände sind bis 2023 nicht notiert und 2024 vom Fahrer abgelesen. 2025 wird monatlich gepeilt
     (±25 l, auf 10 l gerundet); die Ablesung am 01.08. fehlt.
   * Holz: je Winter in Ster, auf 0,5 gerundet.
   * Kosten: mit Energiepreiskrise 2022.

## Benutzen und neu erzeugen

```bash
bin/opentsx house --simulated     # Kalibrierung auf diesen Dateien, Vergleich mit den verdeckten Parametern, Hebel
java -cp "$(bin/opentsx classpath opentsx-twin)" org.opentsx.twin.house.SimulatedBills opentsx-twin/src/main/resources
```

Der Test `SimulatedBillsTest` stellt sicher, dass die Dateien genau dem Generator entsprechen.
