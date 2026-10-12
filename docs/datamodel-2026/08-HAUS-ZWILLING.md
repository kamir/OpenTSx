# OpenTSx – Haus-Zwilling: Betriebsführung und Effizienzmessung für Hauseigentümer (Konzept, P9.7)

> Status: **Vorschlag mit lauffähigem Beispiel**, 2026-10-12. Überträgt die Methode des Energieverbund-Zwillings
> ([07-DIGITAL-TWIN-BSP](07-DIGITAL-TWIN-BSP.md)) und das Bewertungsprinzip aus P8 („Zusammenspiel &
> Koordinationspotenzial“, [TASK-011](../../EVOLUTION/TASK-011-datamodel-refresher-2026.md)) auf ein
> Einfamilienhaus. Umsetzung im Modul [`opentsx-twin`](../../opentsx-twin/README.md), Befehl `bin/opentsx house`.
>
> **Alle Zahlen zum Haus sind erfundene Beispielannahmen.** Sie gelten nur so lange, bis sie durch echte
> Abrechnungen ersetzt und kalibriert sind (§7). Einsparungen aus dem Zwilling sind **modelliert**, nicht
> nachgewiesen (§8).

---

## 1. Kurzfassung

Der Terawatt-Fall fragt: *Was verlieren wir durch getrennte Betriebsführung, und was gewinnen wir durch
abgestimmtes Handeln?* Im Haus lautet dieselbe Frage: *Was kostet die heutige Fahrweise von Heizung, Warmwasser und
Geräten, und was bringt eine andere, unter demselben Wetter und denselben Komfortgrenzen?*

Vorschlag in fünf Sätzen:

1. **Derselbe Zwilling, andere Bauteile.** Das Haus ist ein Schaltplan aus Atomen (Wetter, Gebäude, Ölkessel,
   Kaminofen, Warmwasser, Geräte, Zähler, Öltank, Holzlager) mit demselben Atomvertrag MVC+P, demselben Runner und
   denselben drei Betriebsarten wie die H2-Kette.
2. **Abrechnungen sind die Messung.** Jahres- und Teilabrechnungen (Strom, Heizöl, Holz) sind die einzigen
   verlässlichen Daten. Sie werden als `REPORTED` geführt; alles Stündliche dazwischen ist Modell.
3. **Kalibrieren statt schätzen.** Wenige Parameter (Wärmeverlustkoeffizient H, Warmwasser- und Geräteskala) werden
   deterministisch nach kleinsten Quadraten an die Abrechnungszeiträume angepasst. Was sich aus den Daten nicht
   trennen lässt, wird ausgewiesen statt verschwiegen.
4. **Jeder Hebel ist eine Alternative zur Referenz.** −1 K, Nachtabsenkung, ungenutzte Räume, mehr Holz,
   Warmwassertemperatur, Pumpe, Gefriertruhe, Standby, Fernsehzeit: jeweils Alternative minus Referenz, gleiches
   Wetter, gleicher Informationsstand.
5. **Modelliert ≠ nachgewiesen.** Eine Einsparung gilt erst als nachgewiesen, wenn gemessene Zeiträume vor und nach
   der Änderung witterungsbereinigt verglichen sind.

---

## 2. Übertragung der Methode

| Baustein der Methode | Terawatt-Fall (07) | Haus (dieses Dokument) |
|----------------------|--------------------|------------------------|
| Atomvertrag MVC+P | WEA, Elektrolyseur, H2-Leitung, Batterie, Markt … | Wetter, Gebäude, Ölkessel, Kaminofen, Warmwasser, Geräte, Zähler, Öltank, Holzlager |
| Schaltplan | `twin/h2-chain.yaml` | [`twin/haus-erzgebirge.yaml`](../../opentsx-twin/src/main/resources/twin/haus-erzgebirge.yaml) |
| Basistakt | 1 min | 1 h (Gebäude reagiert in Stunden bis Tagen; ein Jahr = 8760 Superschritte) |
| Phasen | SOURCES → POLICY → ASSETS → BALANCE → FLOWS → VIEWS | dieselben: Wetter/Geräte/Warmwasser → (Policy) → Kessel/Ofen → Stromzähler → Gebäude/Tank/Holzlager |
| Rückkopplung | Leitungsdruck → Elektrolyseur (ein Schritt verzögert) | Thermostat-Anforderung und Tankstand → Kessel (ein Schritt verzögert) |
| Shadow | Live-Messung treibt den Zwilling | Abrechnungen, optional Zählerfotos, Peilstab, DWD-Temperaturen |
| What-if | anderes Eingriffslog auf derselben Historie | Stellhebel als Eingriffe auf dasselbe Referenzjahr |
| Prognose | ab Snapshot mit Prognosen, Policy austauschbar | ab heutigem Zustand (Tankstand, Holzvorrat): „Reicht das Öl bis März?“ |
| Herkunftsleiter | `MEASURED > REPORTED > FORECAST > ESTIMATED > SYNTHETIC` | dieselbe; Abrechnung = REPORTED, Stationstemperatur = MEASURED, Stundenaufteilung = SYNTHETIC bzw. nach Kalibrierung ESTIMATED |
| Stufen A–D | Daten & Potenzial → Shadow → Kausalketten → Optimierung | siehe unten |
| Bewertungsprinzip | koordinierte Alternative − Referenzbetrieb | Hebel-Alternative − heutige Fahrweise, gleiches Wetter |

**Stufen mit Wertschranke für den Hauseigentümer:**

| Stufe | Inhalt | Aufwand | Schranke (weiter nur, wenn …) |
|-------|--------|---------|-------------------------------|
| **A – Abrechnungen & Kennzahlen** | Abrechnungen erfassen, kWh/m²a, witterungsbereinigter Verbrauch, Vergleich mit Kennwerten | 1–2 h, keine Simulation | Verbrauch auffällig hoch oder Entscheidung ansteht (Kesseltausch, Dämmung) |
| **B – Kalibrierter Shadow** | Zwilling an Abrechnungen anpassen, Residuen je Zeitraum, Identifizierbarkeit prüfen | Kalibrierlauf, ggf. DWD-Tagesmittel | Fit-Güte ausreichend (Richtwert CV(RMSE) ≤ 15 %, NMBE ≤ ±5 % bei Monatsdaten) |
| **C – Hebel & Kausalketten** | What-ifs je Hebel, Kette z. B. Absenkung → Aufheizspitze → Kessel taktet → Verluste | Rechenzeit, keine Investition | modelliertes Potenzial lohnt die Maßnahme |
| **D – Betriebsführung** | Fahrplan (Heizzeiten, Holz/Öl-Aufteilung, Bestellzeitpunkt Öl) als Policy, Prognose ab Tankstand | Kalender, Peilstab | Nachweis im Folgejahr: gemessene, witterungsbereinigte Einsparung |

---

## 3. Das Beispielhaus (Annahmen)

**Standort (fiktiv):** mittleres Erzgebirge, etwa **650 m ü. NN**, 50,6° N / 13,0° O.

| Größe | Annahme | Bemerkung |
|-------|---------|-----------|
| Jahresmitteltemperatur | ≈ 7,0 °C | synthetisches Klima des Wetter-Atoms (Jahresgang, Tagesgang, Wetterlagen aus festen Sinus + Zufall mit Seed) |
| Kältester Abschnitt | Mitte Januar, Tagesmittel ≈ −2 °C, Minimum im Beispieljahr ≈ −11 °C | |
| Norm-Außentemperatur | **≈ −14 °C** | Auslegungswert, Annahme; für die Heizlastberechnung, nicht für die Simulation |
| Gradtagzahl G20/15 | Referenz **4.300 Kd** (langjährig, Annahme); synthetisches Beispieljahr 4.567 Kd | für echte Bewertung DWD-/IWU-Klimadaten der nächsten Station |
| Heiztage | ≈ 300 pro Jahr (Heizgrenze 15 °C, 24-h-Mittel) | |
| Globalstrahlung | ≈ 900–1.000 kWh/m²a | synthetisch |

**Gebäude:** Einfamilienhaus, Baujahr 1978, Massivbau, zwei Vollgeschosse, unbeheizter Keller und Dachboden.
Teilsaniert: Fenster 2005 (Zweifach-Wärmeschutzglas), oberste Geschossdecke 2012 gedämmt, Außenwände ungedämmt.

| Raum | Fläche | beheizt | Nutzung |
|------|-------:|---------|---------|
| Wohnzimmer (EG) mit Kaminofen | 35 m² | ja | täglich abends, am Wochenende ganztags |
| Küche (EG) | 15 m² | ja | Kochen, Radio, kleiner Fernseher |
| Flur/Diele/Treppe (EG) | 14 m² | ja | |
| WC/Hauswirtschaft (EG) | 5 m² | ja | Waschmaschine |
| Schlafzimmer (OG) | 17 m² | ja | Fernseher |
| Bad (OG) | 9 m² | ja | |
| Arbeitszimmer (OG) | 13 m² | ja | gelegentlich |
| Gästezimmer, ehem. Kinderzimmer (OG) | 13 m² | ja | selten |
| Flur (OG) | 9 m² | ja | |
| **Wohnfläche beheizt** | **130 m²** | | davon 20 % selten genutzt (Arbeits- und Gästezimmer) |
| Keller: Heizraum, Tankraum, Vorratskeller, Werkstatt | ≈ 65 m² | nein | Kessel, 150-l-Speicher, 2 × 2.000 l Batterietank, Gefriertruhe |
| Dachboden | ≈ 60 m² | nein | oberste Geschossdecke gedämmt |

**Thermische Kennwerte (Annahmen, werden kalibriert):** Wärmeverlustkoeffizient **H = 200 W/K** (Transmission +
Lüftung; ≈ 6,8 kW Heizlast bei −14 °C ohne Gewinne), wirksame Wärmekapazität **14 kWh/K** (schwere Bauweise,
Zeitkonstante C/H ≈ 70 h), solare Apertur 4 m² (Fensterfläche × g-Wert × Verschattung).

**Bewohner und Tagesablauf (2 Personen):**

| | werktags | Wochenende |
|---|----------|------------|
| aufstehen / Warmwasser | 6–7 Uhr | 8–10 Uhr |
| außer Haus | 7–16 Uhr | – |
| kochen (E-Herd) | 18–19 Uhr | 12–13 Uhr |
| Fernsehen Wohnzimmer | 19–23 Uhr | 15–23 Uhr |
| Kaminofen | 18–22 Uhr in der Heizperiode | ebenso |
| Waschen | – | Samstag/Sonntag 10–11 Uhr |
| Raumtemperatur | 21 °C von 6 bis 22 Uhr, nachts 3 K Absenkung | ebenso |

Das Modell ist **eine** thermische Zone. Räume gehen über ihre Fläche ein (z. B. der Anteil selten genutzter Räume);
Raumtemperaturen einzelner Zimmer bildet es nicht ab.

---

## 4. Inventar und Heizung

### 4.1 Geräte (Leistungen sind typische Werte, Annahmen)

| Gerät | Modell im Zwilling | Annahme | Ergebnis Beispieljahr |
|-------|--------------------|---------|----------------------:|
| Kühlschrank (Küche) | Dauerleistung (Kompressor-Mittel) | 25 W | 219 kWh |
| Gefriertruhe (Keller, älter) | Dauerleistung, außerhalb der Hülle | 45 W | 394 kWh |
| E-Herd mit Backofen | 1,3 kW im Kochfenster | s. Tagesablauf | 475 kWh |
| Fernseher Wohnzimmer (LED, 55″) | 100 W, Standby 0,5 W | | 191 kWh |
| Fernseher Schlafzimmer (älter) | 70 W, Standby 3 W | | 58 kWh |
| Fernseher Küche (klein) | 40 W, Standby 2 W | | 45 kWh |
| Radio (Küche) | 6 W, Standby 2 W | | 21 kWh |
| Beleuchtung (gemischt LED/Halogen) | 140 W in den Abendstunden, im Winter +60 % | | 372 kWh |
| Waschmaschine | 0,9 kWh je Wochenendtag | *ergänzt* | 94 kWh |
| Kleingeräte (Kaffee, PC, Ladegeräte) | 150 W in Nutzungszeiten, Standby 12 W | *ergänzt* | 350 kWh |
| Router | 10 W Dauer | *ergänzt* | 88 kWh |
| **Haushaltsstrom** | | | **2.306 kWh** |
| Heizungsumwälzpumpe (ungeregelt) | 65 W in der Heizperiode | | 490 kWh |
| Ölbrenner (Gebläse, Ölpumpe) | 120 W beim Brennen | | 211 kWh |
| **Strom gesamt** | | | **≈ 3.000 kWh** |

*Ergänzt* = nicht in der Vorgabe genannt, aber in jedem Haushalt vorhanden; bei echten Daten anpassen oder
streichen. Elektrische Geräte innerhalb der Hülle zählen als innere Wärmegewinne, ebenso 2 × 80 W je anwesender
Person.

### 4.2 Heizung: gewählte Konfiguration

**Ölkessel + Kaminofen im Wohnzimmer** (keine Kombination mit Pufferspeicher). Begründung: Das ist im Erzgebirge
die häufigste „Öl-Holz-Heizung“ im Bestand. Der Ölkessel (Niedertemperaturkessel der 1990er, 18 kW,
einstufiger Brenner) versorgt Heizkörper und den 150-l-Warmwasserspeicher. Der Kaminofen (Scheitholz, Fichte)
heizt abends das Wohnzimmer direkt; seine Wärme senkt die Anforderung an den Kessel. Ein wasserführender Ofen oder
ein Holzvergaserkessel mit Puffer wäre ein eigenes Atom (Pufferspeicher als Bestand) und ist als Variante vorgesehen.

| Annahme | Wert |
|---------|------|
| Kessel: Nennleistung / Wirkungsgrad | 18 kW / 0,90 (oberhalb Mindestlast) |
| Mindestlast, Taktverlust | 35 % der Nennleistung; darunter sinkt der Wirkungsgrad linear um bis zu 15 % |
| Bereitschaftsverlust | 0,2 kW, ganzjährig (Kessel wird warm gehalten) |
| Heizöl EL | 10 kWh/l (Heizwert), 1,00 €/l |
| Kaminofen | 6 kW Brennstoffleistung, Wirkungsgrad 0,70, 4 h je Abend in der Heizperiode |
| Holz | Fichte lufttrocken ≈ 1.500 kWh/Ster (Buche ≈ 2.000 kWh/Ster), 90 €/Ster |
| Warmwasser | 2 × 40 l/Tag bei 45 °C (Kaltwasser 10 °C), Speicher 60 °C, Speicherverlust 60 W |
| Öltank | 4.000 l, Bestellschwelle 600 l |
| Strompreis | 0,36 €/kWh |

Keine PV, keine Wärmepumpe: Die Atome dafür fehlen bewusst; sie wären spätere Varianten (Erzeugung am Zähler wie
im Energieverbund, Wärmepumpe als Konverter mit COP).

---

## 5. Effizienzmessung

### 5.1 Kennzahlen

| Kennzahl | Definition | Beispieljahr (synthetisch) |
|----------|------------|---------------------------:|
| Strom | kWh/a am Zähler | 3.007 kWh |
| davon Heizungsstrom | Pumpe + Brenner | 701 kWh |
| Grundlast | mittlere Leistung 2–5 Uhr | 156 W |
| Standby-Anteil | abschaltbarer Standby / Strom | 4,8 % |
| Spitze | höchster Stundenmittelwert | 2,0 kW |
| Heizöl | Liter/a (Verbrauch, nicht Lieferung) | 1.916 l |
| Holz | Ster/a | 4,9 Ster |
| Endenergie Wärme | Öl-l × 10 kWh/l + Ster × kWh/Ster | 26.575 kWh |
| spezifisch | Endenergie Wärme / beheizte Fläche | 204 kWh/m²a |
| witterungsbereinigt | Heizanteil × G_ref / G + Warmwasser + Bereitschaft | 25.248 kWh (auf 4.300 Kd) |
| Nutzwärme | Kessel- + Ofenwärme | 20.127 kWh |
| Kesselverluste / Jahresnutzungsgrad | Brennstoff − Wärme / Wärme ÷ Brennstoff | 4.225 kWh / 0,78 |
| Komfort | Stunden > 0,5 K unter Sollwert in der Heizperiode | 138 h (Aufheizen nach der Nachtabsenkung) |
| Kosten, CO2 | Tarife; GEG-Anlage-9-Faktoren (Öl 310, Strom 560, Holz 20 g/kWh) | 3.444 €/a, 7,8 t/a |

Die Witterungsbereinigung skaliert nur den wetterabhängigen Teil (Raumwärme). Warmwasser und Bereitschaftsverlust
bleiben unverändert. Das ist genauer als die übliche Bereinigung des Gesamtverbrauchs.

### 5.2 Günstige Messmöglichkeiten (vom billigsten zum aufwendigsten)

| Messung | Kosten | liefert | Herkunft |
|---------|--------|---------|----------|
| Abrechnungen der letzten Jahre | 0 € | Jahres-/Teilsummen Strom, Öllieferungen | REPORTED |
| Zählerfoto Strom am Monatsersten | 0 € | Monatswerte → trennt Grundlast und saisonale Anteile | MEASURED |
| Peilstab/Tankanzeige ablesen, bei jeder Lieferung und monatlich | 0 € | Ölverbrauch statt Lieferung | MEASURED |
| Holzbuch (Körbe je Woche → Ster) | 0 € | Holzanteil je Zeitraum | REPORTED |
| DWD-Tagesmitteltemperatur der nächsten Station (frei) | 0 € | echtes Wetter statt synthetisch | MEASURED |
| Zwischenstecker mit Messung (Gefriertruhe, TV-Ecke) | 10–20 € je Stück | Geräteleistung und Standby | MEASURED |
| Brennerstundenzähler (oft im Kessel vorhanden) | 0–30 € | Brennerlaufzeit × Düsendurchsatz = Öl | MEASURED |
| Funk-Thermometer im Wohnzimmer | 15 € | Innentemperatur → prüft das Thermostat-Modell | MEASURED |

---

## 6. Schaltplan des Haus-Zwillings

```
SOURCES   weather ──t_out,ghi──────────────────────────────┐
          dhw ──q_dhw──┐                                   │
          appliances ──┼─q_gain───────────────────────┐    │
                       │  └─p_el──────────┐           │    │
ASSETS    stove ───────┼─q_heat───────────┼───────────┤    │
          boiler ◄─────┘ (q_req, season, tank_level: ein Schritt verzögert)
             ├─q_space────────────────────┼───────────┤    │
             ├─p_el───────────────────────┤           │    │
             └─fuel_lph──────┐            │           │    │
BALANCE   meter ◄────────────┼────────────┘           │    │
FLOWS     tank ◄─────────────┘   building ◄───────────┴────┘   woodstore ◄── stove.wood_ster_ph
```

| Atom | Typ | Phase | Kernverhalten |
|------|-----|-------|---------------|
| `weather` | `weather` | SOURCES | synthetisches Klima oder gebundene Messung; Gradtagzahl G20/15 im Zustand |
| `dhw` | `dhw` | SOURCES | Zapfprofil werktags/Wochenende + Speicherverlust; Stellgröße `t_store_c` |
| `appliances` | `appliances` | SOURCES | Inventar mit Leistung, Standby, Zeitfenstern; Stellgrößen `standby_off`, `factor.<gerät>` |
| `stove` | `wood-stove` | ASSETS | Abbrand je Abend; oder die Holzmenge aus der Abrechnung (`wood_kwh_per_day`) |
| `boiler` | `oil-boiler` | ASSETS | Warmwasser-Vorrang, Nennleistung, Mindestlast/Takten, Bereitschaft, Pumpe; Leerlauf bei leerem Tank |
| `meter` | `meter` | BALANCE | Strombilanz, Kosten, Grundlast, Spitze; Residuum gegen Zählerwert |
| `building` | `building` | FLOWS | 1R1C-Wärmebilanz, Thermostat mit Tag/Nacht-Sollwert, Heizgrenze; Stellgrößen `setpoint_c`, `setback_k`, `unused_setback_k` |
| `tank` | `oil-tank` | FLOWS | Bestand in Litern, Lieferung als Eingriff, Bestellwarnung, „Tank leer“ |
| `woodstore` | `wood-store` | FLOWS | Holzvorrat in Ster, Lieferung als Eingriff |

Geprüfte Invarianten (Tests): Wärmebilanz des Gebäudes geschlossen, Öl-, Holz- und Strombilanz geschlossen,
bitgleiche Wiederholung, Fortsetzung ab Snapshot exakt, −1 K senkt den Wärmebedarf, leerer Tank stoppt den Kessel.

---

## 7. Kalibrierung mit Abrechnungen

### 7.1 Eingaben

Eine CSV-Zeile je Zeitraum, Vorlage:
[`twin/abrechnungen-vorlage.csv`](../../opentsx-twin/src/main/resources/twin/abrechnungen-vorlage.csv) (nur
erfundene Beispielzeilen).

| Spalte | Inhalt | Pflicht |
|--------|--------|---------|
| `zeitraum_von`, `zeitraum_bis` | Datum, beide einschließlich (`2024-01-31` oder `31.01.2024`) | ja |
| `strom_kwh` | Verbrauch laut Abrechnung oder Zählerablesung | nein |
| `heizoel_liter` | im Zeitraum **gelieferte** Liter (0, wenn nur abgelesen) | nein |
| `tank_start_liter`, `tank_ende_liter` | Tankstand am Anfang/Ende; dann Verbrauch = Lieferung + Start − Ende | nein |
| `holz_ster` | im Zeitraum **verbranntes** Holz | nein |
| `kosten_eur`, `notiz` | nur zur Anzeige | nein |

Zeilen dürfen sich überlappen. Typisch sind Stromjahresabrechnungen mit eigenem Stichtag und daneben
Öl-Zeiträume von Ablesung zu Ablesung. Trennzeichen `,` oder `;` (Excel-Export mit Dezimalkomma).

### 7.2 Verfahren

1. Der Zwilling läuft stündlich über den gesamten Abrechnungszeitraum. Wetter: synthetisch oder die
   Tagesmitteltemperaturen einer DWD-Station (`--weather datum,t_mittel_c`, Tagesgang wird ergänzt). Holz: die
   Ster aus den Zeilen, verteilt nach Heizgradtagen (REPORTED). Das Modell muss also nur das Öl *zusätzlich* zum
   tatsächlich verbrannten Holz erklären.
2. Je Zeile werden modellierter Strom (kWh) und Ölverbrauch (l) summiert.
3. Angepasst werden **H** (Wärmeverlustkoeffizient), **Warmwasserskala** und **Geräteskala**, mit
   Levenberg–Marquardt auf den normierten Residuen der Zeiträume: Jacobi-Matrix einmal per Vorwärtsdifferenz, danach
   Broyden-Updates, deterministisch, ohne neue Abhängigkeiten. Typisch 7–10 Zwillingsläufe.
4. Ausgabe: Parameter mit relativem Standardfehler, Residuum je Zeitraum, CV(RMSE) und NMBE je Größe,
   Konditionszahl und größte Parameterkorrelation, Warnungen, kalibrierter Schaltplan. Mit diesem werden die Hebel
   (§8) neu gerechnet.

Fest bleiben (Annahmen, nicht angepasst): Kesselwirkungsgrad, Bereitschaftsverlust, kWh je Ster, 10 kWh/l.

### 7.3 Ehrliche Grenzen der Identifizierbarkeit

* **Nur Jahressummen trennen fast nichts.** Eine Strom- und eine Ölsumme je Jahr sind zwei Zahlen für drei
  Parameter. Der Fit meldet „not identifiable“ (Test `flagsAnnualTotalsAsNotIdentifiable`). Mit der Vorlage
  (zwei Jahre, ein Halbjahr mit Tankablesung) bleibt die Warmwasserskala unbestimmt: relativer Standardfehler
  ≈ 100 %, Korrelation mit H 0,996.
* **Sommer trennt Warmwasser vom Gebäude.** Monatliche Tankablesungen von Juli bis Dezember stellen bekannte
  Parameter auf besser als 2 % wieder her (Test `recoversKnownParametersFromMonthlySyntheticBills`). Nur Wintermonate
  lassen H und Warmwasser zu ≈ 0,99 korreliert.
* **Lieferung ≠ Verbrauch.** Ohne Tankstände wird die Lieferung als Verbrauch genommen, und die Zeile wird
  markiert. Abhilfe: mehrere Jahre in eine Zeile zusammenfassen (der Tankstandfehler verteilt sich) oder den
  Peilstab ablesen.
* **Nur H/η ist bestimmt.** Ein schlechterer Kessel und ein schlechteres Gebäude sehen in Summen gleich aus. Erst
  ein Brennerstundenzähler oder eine Abgasmessung (Schornsteinfeger-Protokoll) trennt sie.
* **Synthetisches Wetter verzerrt H.** Ein milder oder kalter Winter landet sonst im Gebäudeparameter. Für echte
  Abrechnungen deshalb DWD-Tagesmittel binden.

### 7.4 Herkunft

| Größe | Herkunft |
|-------|----------|
| Abrechnungssummen, Holzmengen | REPORTED |
| Zählerfoto, Peilstab, DWD-Temperatur | MEASURED |
| stündliche Aufteilung (Geräte, Raumwärme, Warmwasser) | SYNTHETIC; nach Kalibrierung ESTIMATED (Modell an Daten angepasst) |
| Hebel-Ergebnisse | modelliert (What-if), nie MEASURED |

### 7.5 Ablauf mit echten Daten

```bash
mkdir -p config/private                                  # per .gitignore vom Commit ausgeschlossen
cp opentsx-twin/src/main/resources/twin/abrechnungen-vorlage.csv config/private/abrechnungen.csv
# Beispielzeilen löschen, eigene Abrechnungen eintragen; optional DWD-Tagesmittel als datum,t_mittel_c
bin/opentsx house --calibrate config/private/abrechnungen.csv [--weather config/private/tagesmittel.csv]
```

---

## 8. Stellhebel (What-if)

Jeder Hebel ist ein Satz Eingriffe auf dasselbe Referenzjahr (gleiches Wetter, gleiche Lieferungen). Ergebnis =
**Alternative − Referenz**. Beispielhaus, **nicht kalibriert, modelliert**:

| Hebel | Eingriff | Öl | Holz | Strom | Endenergie | Kosten | CO2 |
|-------|----------|---:|-----:|------:|-----------:|-------:|----:|
| −1 K Raumtemperatur | `building.setpoint_c` 21 → 20 | −151 l | 0 | −16 kWh | −1.513 kWh | −157 € | −478 kg |
| tiefere Nachtabsenkung | `setback_k` 3 → 5 K | ≈ 0 | 0 | ≈ 0 | ≈ 0 | ≈ 0 | ≈ 0 |
| selten genutzte Räume kühler | `unused_setback_k` 4 K auf 20 % der Fläche | −123 l | 0 | −13 kWh | −1.229 kWh | −128 € | −388 kg |
| mehr Holz | Ofen 4 → 7 h je Abend | −355 l | +3,8 Ster | −46 kWh | +2.104 kWh | −32 € | −1.013 kg |
| Warmwasser 50 °C | `dhw.t_store_c` 60 → 50 | −16 l | 0 | −2 kWh | −162 kWh | −17 € | −52 kg |
| Hocheffizienzpumpe | `boiler.pump_w` 65 → 8 W | 0 | 0 | −430 kWh | 0 | −155 € | −241 kg |
| neue Gefriertruhe | `factor.gefriertruhe` 0,4 | 0 | 0 | −237 kWh | 0 | −85 € | −132 kg |
| Standby abschalten | `standby_off` | +13 l | 0 | −142 kWh | +133 kWh | −38 € | −39 kg |
| weniger Fernsehen | TV-Zeit −30 % | +8 l | 0 | −88 kWh | +77 kWh | −24 € | −25 kg |
| alle außer „mehr Holz“ | Kombination | −261 l | 0 | −914 kWh | −2.608 kWh | −590 € | −1,3 t |

Was der Zwilling dabei sichtbar macht, das eine Einzelbetrachtung übersieht:

* **Die Nachtabsenkung wirkt im schweren Haus kaum.** Bei einer Zeitkonstante von ≈ 70 h kühlt das Haus in acht
  Stunden nur um 1–2 K aus, und der Ofen heizt bis 22 Uhr nach. Die tiefere Absenkung wird also gar nicht erreicht.
  Das gilt für das Ein-Zonen-Modell; einzelne Räume mit Heizkörpern kühlen schneller aus.
* **Strom sparen heißt im Winter ein wenig mehr Öl.** Standby und Fernseher heizen innen mit (Gewinne). Der
  Kostenvorteil bleibt, aber die Endenergie steigt leicht. Diese Kopplung ist das Haus-Gegenstück zum
  „Zusammenspiel“ in P8.
* **Mehr Holz senkt Kosten und CO2, aber nicht die Endenergie.** Der Ofen hat einen schlechteren Wirkungsgrad als
  der Kessel. Je nach Ziel (Kosten, CO2, kWh) ist dieselbe Maßnahme gut oder schlecht. Darum weist der Bericht alle
  Größen getrennt aus.
* **Der Kessel ist überdimensioniert** (18 kW bei ≈ 7 kW Heizlast). Bereitschafts- und Taktverluste machen ≈ 4.200
  kWh/a aus (Jahresnutzungsgrad 0,78). Das ist ein Argument für den Kesseltausch oder eine Brennerdrossel,
  zu prüfen in Stufe C.

**Modelliert vs. nachgewiesen.** Die Tabelle ist modelliertes Potenzial. Nachgewiesen ist eine Einsparung erst,
wenn gemessene Zeiträume (Zähler, Peilstab) vor und nach der Änderung witterungsbereinigt verglichen sind und die
Differenz größer ist als die Kalibrierunsicherheit. Ein Vergleich mit perfekter Kenntnis des Wetters ist höchstens
eine gekennzeichnete Obergrenze.

---

## 9. Abbildung Haus ↔ Terawatt-Fall

| Haus | Terawatt-Fall | Gemeinsames Muster |
|------|---------------|--------------------|
| Ölkessel (η, Nennleistung, Mindestlast, Takten) | Elektrolyseur (kWh/kg, Nennleistung, Mindestlast, Rampe) | Konverter mit Wirkungsgrad und Betriebsgrenzen |
| Kaminofen | Batterie/zweite Quelle | zweiter, anders effizienter Weg zum selben Bedarf |
| Öltank, Holzlager, Speichermasse des Hauses | H2-Leitung (Linepack), Batterie-SoC | Bestand mit Zeit-bis-Grenze und Warnung |
| „Tank leer“ → Kessel aus | Leitungsdruck → Schutzabregelung | Rückkopplung über die Schrittgrenze |
| Komfortgrenze (Sollwert − 0,5 K) | Betriebsgrenzen (Druck, Fahrplantoleranz) | Nebenbedingung, Verletzung als Kennzahl |
| Heizgrenze, Sommerbetrieb | Betriebsvorgaben (Netzrestriktion, Fledermaus) | Kalender-/Regelquelle |
| Strom- und Öltarif | Marktpreis | Bewertung in € |
| Hauseigentümer | Betreiber, Vermarkter | entscheidet, trägt Kosten, Eingriffe im Log |
| Heizplan (Sollwerte, Ofenzeiten, Bestellzeitpunkt) | Fahrplan | Policy, austauschbar |
| Abrechnung | Zählerdaten/Partnerdaten | REPORTED in der Herkunftsleiter |
| Lieferung von Öl/Holz | H2-Abnahme, Belieferung | Eingriff auf einen Bestand |
| Hebel-Alternative − Referenzjahr | koordinierte Alternative − Referenzbetrieb | Koordinationspotenzial, gleiche Randbedingungen |

---

## 10. Datenschutz

* Echte Abrechnungen bleiben **lokal**: `config/private/` ist per `.gitignore` (`**/config/private/**`) vom
  Commit ausgeschlossen, ebenso Wetter- oder Zählerdateien dort.
* Im Repository liegen nur die Vorlage mit erfundenen Zeilen und das Beispielhaus.
* Ergebnisse (kalibrierte Parameter, Kennzahlen) enthalten keine Rohdaten. Ein Vergleich mehrerer Häuser über
  generische Kenngrößen folgt dem Föderationsgedanken aus P9.5.

---

## 11. Umsetzungsstand und offene Fragen

**Umgesetzt (PoC):** Atome `weather`, `building`, `oil-boiler`, `wood-stove`, `dhw`, `appliances`, `meter`,
`oil-tank`, `wood-store` im Standardkatalog. Dazu der Schaltplan `twin/haus-erzgebirge.yaml`, `HouseScenario`
(Referenz, Hebel), `HouseKpis`, `BillCalibration` (CSV, DWD-Tagesmittel, LM/Broyden, Identifizierbarkeit) und
die Demo `bin/opentsx house` mit `--calibrate`. Tests: Bilanzen, Determinismus, Snapshot, −1 K, leerer Tank,
Identifizierbarkeit.

**Offen:** Pufferspeicher und wasserführender Ofen als Variante; Mehrzonenmodell (Raum je Atom); Prognose „Reicht
das Öl bis zum Frühjahr?“ als fertiges Szenario; Optimierer als Policy (Bestellzeitpunkt nach Ölpreis, Holz/Öl-
Aufteilung); DWD-Abruf statt CSV.

**Fragen an den Hauseigentümer (für die Kalibrierung):**

1. Welche Jahre liegen als Abrechnungen vor (Strom mit Stichtagen, Öllieferungen mit Datum und Litern)?
2. Sind Tankstände bekannt (Peilstab, Anzeige, Notizen bei Lieferungen)? Wenn nicht: ab jetzt monatlich ablesen.
3. Wie viel Holz wird pro Winter verbrannt (Ster), welche Holzart, eigener Einschlag oder gekauft?
4. Wohnfläche, Baujahr, bereits durchgeführte Sanierungen (Fenster, Dach, Fassade, Kessel)?
5. Kesseldaten: Hersteller, Baujahr, Nennleistung, Schornsteinfeger-Protokoll (Abgasverlust)?
6. Raumtemperaturen und Heizzeiten tatsächlich (Thermostat-Einstellungen, Nachtabsenkung)?
7. Nächstgelegener Ort oder DWD-Station für die Temperaturreihe?
