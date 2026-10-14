# OpenTSx: Engineering-Ablauf vom System zum Modell (Konzept und Entwurf)

> Stand: 2026-10-13, dritte Fassung. **Freigegeben am 2026-10-13** mit zwei Änderungen gegenüber dem Vorschlag
> (D43: drei Güten statt zwei; D50: zusätzlich ein geführter Modus). Umgesetzt sind die Lieferstufen L1 und L2
> (Abschnitt 10): Systemdokument mit Prüfung, Zustand der Signale, die Hülle mit ihren vier Orten und dem geführten
> Modus, Sprung auf den Schirm, dazu L3 mit Grenzen. **Mehrere Festlegungen dieses Dokuments sind durch
> [15](15-ENTSCHEIDUNGSKREIS.md) geändert** (Zustand des Signals, Herkunft, Bedeutung von `clean`); dort steht, was
> gilt. L4 und L5 werden wie hier geplant gebaut, danach folgen die Stufen E1 bis E3 aus 15. Arbeitspaket:
> [TASK-014](../../EVOLUTION/TASK-014-engineering-flow.md); dort stehen auch die Befunde der beiden Reviews.

## 1. Kurzfassung

OpenTSx hat heute zwei Enden eines Arbeitsablaufs und keine Mitte. Das **Smart Topics Studio** beschreibt ein
System von oben: Bausteine, Ports, Verbindungen, Ereignisse. Die **Workbench** arbeitet von unten an Reihen:
laden, Operatoren anwenden, auf dem Oszilloskop prüfen, als Smart Topic bereitstellen. Dazwischen fehlt, was die
Ingenieurarbeit ausmacht: festhalten, welche Messgrößen ein System **erwartet**, welche davon **vorhanden** sind,
welche **simuliert** werden müssen, und wie aus Rohdaten **bereinigte Reihen** für Modelle werden.

Der Vorschlag:

1. **Vier Orte und ein Werkzeug** statt einzelner Seiten: System, Signals, Flows, Models; dazu die Workbench
   (Abschnitt 3).
2. Das **Signal** als gemeinsamer Schlüssel: eine erwartete Messgröße mit Rohdaten, bereinigten Daten und
   Merkmalen für Modelle (Abschnitt 4).
3. Zwei neue deklarative Dokumente: `opentsx.system.v1` beschreibt das ganze System, `opentsx.processor.v1` einen
   Zeitreihen-Prozessor, ob Generator, Transformator oder Modell (Abschnitte 5 und 7).
4. Eine **feste Regel, wo die Daten eines Signals liegen**, und ein Übersetzungsschritt vom Flow, wie ihn ein
   Mensch schreibt, zum DAG, wie ihn der Prozessor liest (Abschnitt 6).
5. Eine **gemeinsame Hülle** in der Web-Anwendung (Abschnitt 8).

Bestehende Entwürfe bleiben gültig und werden eingebunden: Schaltplan des Zwillings (`twin/*.yaml`), Kanal-Katalog,
Inventar (`opentsx.inventory.v1`, [12](12-EXPLORATIVES-UI-INVENTAR.md)), DAG (`opentsx.dag.v1`,
[11](11-SMART-TOPIC-PROZESSOR-DEVOPS.md)), Smart-Topic-Spezifikation ([05](05-SMART-TOPICS.md)). Wo sie sich
ändern müssen, steht es ausdrücklich da (Abschnitt 6.3 und 9).

## 2. Begriffe

Ein Wort je Sache. Die Oberfläche und die Dokumente halten sich daran.

| Begriff | Bedeutung | Nicht verwechseln mit |
|---|---|---|
| **System** | die Anlage: Bausteine, Ports, Verbindungen | **Szenario**: ein Fall oder Lauf des Systems (Zeitraum, Eingriffe) |
| **Signal** | eine Messgröße, die das System erwartet, ob Daten da sind oder nicht | **Reihe** (series): die Daten eines Signals; **Kanal** (channel): Eintrag im Kanal-Katalog |
| **Güte** (grade) | `raw` (wie angeliefert oder erzeugt), `clean` (bereinigt, auf dem Takt, in der Einheit des Systems) oder `features` (Merkmale für Modelle, aus `clean` berechnet) | Ansicht der Oberfläche, Lieferstufe der Umsetzung |
| **Herkunft** (origin) | `MEASURED`, `REPORTED`, `FORECAST`, `ESTIMATED`, `SYNTHETIC`, wie `Origin` im Zwilling | Art der Quelle (Topic, Generator, Flow, von Hand) |
| **Flow** | das Rezept: Prozessoren und ihre Verbindung | **laufender Flow**: ein gestarteter Flow; **Smart Topic**: sein veröffentlichtes Ergebnis |
| **Prozessor** | ein Baustein eines Flows oder ein Generator | Vektor-Prozessor: die Laufzeit, die Flows ausführt |

## 3. Ist-Stand und Zielbild

### 3.1 Bausteine und Brüche

| Schritt der Ingenieurarbeit | Vorhanden | Bruch |
|---|---|---|
| System definieren | Studio, Ansicht "Systembild", aus `twin/*.yaml` | statische HTML-Seite mit Beispieldaten, getrennt von der Web-Anwendung |
| Erwartete Messgrößen festhalten | Kanal-Katalog (Einheit, Wertebereich, Abtastung, IEC-Bezug); Ports im Schaltplan | Ports und Kanäle sind nicht aufeinander bezogen |
| Vorhandene Daten kennen | TS-Inventar (`GET /v1/inventory`): Reihen, Zeitraum, Wertebereich, Lücken, Ground Truth | kein Abgleich "erwartet gegen vorhanden" |
| Messung anbinden oder simulieren | Zwilling bindet Reihen je Lauf an Eingangsports (`SeriesInputs`); Windpark-Simulator; `opentsx-market`; Sinus-Mix der Workbench | nirgends deklariert, welcher Port woher kommt; kein Generator schreibt fortlaufend in ein Topic |
| Rohdaten bereinigen | Workbench: 23 Operatoren, interaktiv | der Prozessor führt nur `align.vector` aus und schreibt nur Vektoren; es gibt kein Topic für bereinigte Reihen |
| Modellieren, trainieren, optimieren | Zwilling (Was-wäre-wenn, Prognose); Smart-Topic-Spezifikation als Entwurf | keine Schleife auf bereinigten Reihen |
| Ergebnis zurück ins System | Studio "Messreihen" und "Vergleich" | gespeist vom Zwillingslauf, nicht von Smart Topics |

### 3.2 Vier Orte und ein Werkzeug

Die erste Fassung hatte sechs nummerierte Stufen. Das Review hat gezeigt: "Anbinden oder simulieren" ist eine
Spalte der Signalliste, "Betrieb" ist der Flow Composer mit Filter, und Nummern behaupten eine Reihenfolge, die es
nicht gibt. Es bleiben vier Orte, ohne Nummern, jeder mit einer Zahl, die sagt, was zu tun ist.

| Ort | Frage | Inhalt | Baut auf |
|---|---|---|---|
| **System** | Woraus besteht die Anlage, was hängt wovon ab? | Schaltplan; ein Klick auf einen Port führt zu seinem Signal | Studio "Systembild" |
| **Signals** | Welche Messgrößen erwartet das System, was ist da, was ist als Nächstes zu tun? | je Signal: Erwartung, Herkunft, Zustand, nächster Schritt; Anbinden, Simulieren und Erwartung ändern sind Aktionen hier | Kanal-Katalog, TS-Inventar |
| **Flows** | Was bereitet die Daten auf, was läuft? | der Flow Composer, gefiltert auf das System; je Flow das Signal, das er bedient | Flow Composer |
| **Models** | Was sagt das System voraus, was wäre wenn? | Modelle auf bereinigten Signalen; fehlt eines, steht es da | Smart-Topic-Spezifikation, Zwilling, Studio "Vergleich" |
| **Workbench** (Werkzeug) | Wie wird aus dieser Rohreihe eine brauchbare? | Oszilloskop und Operatoren, geöffnet für ein Signal | Workbench |

Der glatte Weg:

1. **System** wählen. Der Schaltplan liefert die Ports.
2. **Signals** zeigt alle Signale mit Zustand. Die Zeile sagt den nächsten Schritt.
3. Ein offenes Signal: "Bind" schlägt Reihen aus dem TS-Inventar vor (gleiche Metrik, passende Einheit). Gibt es
   keine, belegt "Simulate" einen Generator aus der Erwartung vor (Einheit, Wertebereich, Abtastung).
4. Ein Signal mit Rohdaten: "Prepare" öffnet die Workbench mit diesen Reihen. Der Schirm zeigt den erwarteten
   Wertebereich. "Publish as clean signal" macht den Flow zur Quelle der bereinigten Daten.
5. **Flows** startet und stoppt, was laufen soll.
6. **Models** nimmt nur bereinigte Signale an.

## 4. Das Signal

* **Kennung**, eindeutig innerhalb des Systems:
  * `<baustein>.<port>` für einen Port des Schaltplans, zum Beispiel `park.p_avail_kw`. Die Kennung **ist** der
    Port; ein zweites Feld dafür gibt es nicht.
  * `<baustein>/<asset>/<kanal>` für einen Sensorkanal unter einem Baustein, zum Beispiel `park/T02/WTUR.W`.
    `<system>/<baustein>/<asset>` ist die Asset-Kennung des Inventars, `<kanal>` die Kanal-Kennung des Katalogs.
  * Nach außen (Adressen, Tags) steht das System davor: `h2-chain/park.p_avail_kw`.
* **Erwartung:** Einheit, Wertebereich, Abtastintervall, Art (`ANALOG`, `STATUS`, `COUNTER` wie im Katalog),
  erwartete Herkunft. Bei einem Sensorkanal kommt sie aus dem Katalog (dort heißt das Intervall `sampling`) und
  kann überschrieben werden.
* **Güte:** `raw`, `clean` und `features`. Ein abgeleitetes Signal hat kein `raw`. `features` sind Merkmale für
  Modelle: verzögerte Werte, gleitende Kennwerte, abgeleitete Größen. Sie werden immer per Flow aus `clean`
  berechnet, tragen je Merkmal einen Namen und lassen sich von mehreren Modellen nutzen.
* **Quelle je Güte:**

  | `kind` | Bedeutung | Herkunft |
  |---|---|---|
  | `topic` | Reihen in einem Topic, per Metrik und Tags gewählt | steht an der Quelle; jede ist möglich, auch `SYNTHETIC` (Daten eines Simulators) |
  | `generator` | ein Generator-Prozessor mit Parametern und Startwert | immer `SYNTHETIC` |
  | `flow` | Ausgang eines Flows | die schlechteste Herkunft der Eingänge |
  | `manual` | Konstante oder Tabelle | steht an der Quelle; Vorgabe `REPORTED` (ein Fahrplan ist keine Simulation) |

* **Zustand**, berechnet aus Deklaration, TS-Inventar und Liste der laufenden Flows, genau einer:

  | Zustand | Bedeutung | Nächster Schritt |
  |---|---|---|
  | `open` | keine Quelle | Bind, Simulate |
  | `bound, no data` | Quelle da, keine Reihe gefunden | Quelle prüfen |
  | `raw only` | Rohdaten da, nichts Bereinigtes | Prepare |
  | `clean, not running` | Flow deklariert, läuft nicht | Start |
  | `clean, running` | Flow läuft, Daten aktuell | n/a |
  | `stale` | letzter Wert älter als erwartet | Flow oder Quelle prüfen |
  | `out of range` | Werte außerhalb der Erwartung über einer Schwelle | Prepare oder Erwartung prüfen |
  | `constant` | von Hand gesetzt | n/a |

  Der Zustand beschreibt den Weg bis `clean`. Daneben stehen zwei eigene Angaben: ob Merkmale deklariert sind
  und laufen, und wer das Signal nutzt (Flows, Modelle, Zwilling). Beides ist kein Fortschritt des Signals; ein
  Signal ohne Merkmale ist vollständig.

Simulierte und gemessene Daten dürfen nie gleich aussehen. Es gilt die Regel des Studios, mit seinen
Herkunfts-Marken als einzigem Vokabular: `SYNTHETIC` trägt in Liste und Schirm eine eigene Marke mit Generator und
Startwert, eine gestrichelte Kurve, und die Marke vererbt sich auf jedes abgeleitete Signal und jedes Modell.

## 5. Deklarative Darstellung des Systems: `opentsx.system.v1`

Ein Dokument je System. Schema: [`schemas/opentsx.system.v1.schema.json`](schemas/opentsx.system.v1.schema.json),
Beispiel: [`systems/h2-chain.system.yaml`](../../systems/h2-chain.system.yaml).

```yaml
schema: opentsx.system.v1
system: h2-chain
version: 1
title: Wasserstoffkette am Windpark
domain: wind                               # Namensraum der Topics: tsx.<domain>...
plan: { ref: opentsx-twin/src/main/resources/twin/h2-chain.yaml }
inventory: { ref: opentsx-twin/src/main/resources/twin/inventar-beispiel.yaml, system: h2-chain }
catalogs:
  - { id: wind, ref: opentsx-windpark/src/main/resources/windpark/channel-catalog.yaml, version: 1.0.0 }
signals:
  - id: park/T02/WTUR.W                    # Sensorkanal unter dem Baustein "park"
    channel: wind/WTUR.W                   # Erwartung aus dem Katalog
    raw:
      source: { kind: topic, topic: tsx.wind.episodes.v2, origin: SYNTHETIC,
                select: { metric: wind.turbine.power_active, tags: { turbine: T02 } } }
    clean:
      source: { kind: flow, flow: turbine-power-clean, output: clean }
  - id: park.p_avail_kw                    # Port des Schaltplans, abgeleitet
    expect: { unit: kW, min: 0, max: 12000, interval: PT1M, kind: ANALOG }
    clean:
      source: { kind: flow, flow: park-power, output: sum }
  - id: market.price_eur_mwh               # keine Daten angebunden: simuliert
    expect: { unit: EUR/MWh, min: -500, max: 3000, interval: PT15M, kind: ANALOG }
    raw:
      source: { kind: generator, processor: gen.ou, seed: 7,
                params: { mean: 62.8, reversion: 0.08, sigma: 9.0 } }
  - id: ely.setpoint_kw                    # von Hand
    expect: { unit: kW, min: 0, max: 5000, interval: PT1M, kind: ANALOG }
    raw:
      source: { kind: manual, value: 5000, origin: REPORTED }
flows:
  - { id: turbine-power-clean, ref: systems/flows/turbine-power-clean.flow.yaml }
  - { id: park-power, ref: systems/flows/park-power.flow.yaml }
```

Regeln:

* **Eine Wahrheit je Frage.** Bausteine und Ports stehen im Schaltplan, die Bedeutung eines Kanals im Katalog, das
  Gerät im Inventar. Das Systemdokument sagt, **welches Signal woher kommt**. Das Inventar nennt heute je Asset
  auch Reihen (`series`) und Ports; bis es von Code gelesen wird, prüft das Werkzeug, dass beide Angaben
  übereinstimmen. Danach entfällt `series` im Inventar zugunsten des Systemdokuments (Vorschlag, D47).
* **Generatoren stehen nur als Quelle eines Signals da,** nie als Knoten eines Flows.
* **Prüfung.** Kennung eindeutig; der Baustein und, bei Ports, der Port stehen im Schaltplan; `channel` endet auf
  dieselbe Kanal-Kennung wie die Signalkennung und steht im Katalog; das Asset steht im Inventar; eine Auswahl
  `select` trifft genau die Reihen des Signals; jeder `flow` steht in der Liste, jeder `processor` hat eine
  Beschreibung; ein Flow als Quelle von `clean` liest nur `raw` desselben Signals oder `clean` anderer, ein Flow
  als Quelle von `features` liest nur `clean`; Zyklen sind ein Fehler. Was ein JSON-Schema nicht prüfen kann (Eindeutigkeit, Verweise), prüft das Werkzeug.
* **Hash.** Der Hash eines Systems wird vom Werkzeug berechnet: über das Dokument und den Inhalt aller Dateien,
  auf die es verweist, wie der Plan-Hash des Zwillings. Er geht in die Provenienz der erzeugten Reihen ein.
* **Offene Signale sind erlaubt.** Die Oberfläche zählt sie.

## 6. Wo die Daten liegen, und wie ein Flow zum Prozessor kommt

Das war die größte Lücke der ersten Fassung: Sie sagte nicht, in welchem Topic simulierte und bereinigte Reihen
stehen, und ihre Beispiel-Flows kann der heutige Prozessor nicht lesen.

### 6.1 Regel für Topic und Reihe

| Quelle der Güte | Topic | Format |
|---|---|---|
| `topic` | wie an der Quelle angegeben | wie dort |
| `generator` | `tsx.<domain>.synthetic.v2` | v2-Episoden |
| `flow`, Güte `clean` | `tsx.<domain>.clean.v2` | v2-Episoden |
| `flow`, Güte `features` | `tsx.<domain>.features.v2` | v2-Episoden, eine Reihe je Merkmal |
| `manual` | keines; der Wert steht im Dokument | n/a |

Je Domain gibt es also **ein** Topic für simulierte, **eines** für bereinigte Reihen und **eines** für Merkmale,
nicht eines je Signal.
Die Reihe darin ist bestimmt durch:

* **Metrik:** die Metrik des Kanals, wenn das Signal auf einen verweist, sonst `<system>.<baustein>.<port>`.
* **Tags:** `system`, `signal` (die Kennung) und `grade` (`raw` für Erzeugtes, `clean`, `features`); bei
  Sensorkanälen dazu die Tags der Rohreihe; bei Merkmalen dazu `feature` mit dem Namen des Merkmals. `grade` kam mit
  L4 dazu, weil eine erzeugte Rohreihe und die bereinigte Reihe desselben Signals sonst dieselbe Reihe wären.
  `flow` (der Name der Bereitstellung) kam am 2026-10-14 dazu (D84, 15, Abschnitt 13.5): Schreiben zwei Flows
  nacheinander dasselbe Signal, entstehen zwei Reihen. Die Ansicht eines Signals zeigt die Reihe des Flows, der
  läuft, und nennt die anderen, ohne sie mitzuzählen. Ebenso trägt eine erzeugte Rohreihe den Lauf ihres
  Generators (`run`, D85); gezählt wird der zuletzt erzeugte Lauf.
* **Reihen-Id:** wie überall aus Metrik und Tags (`SeriesKeys`).
* **Labels der Episoden:** `origin`, `grade`, Hash des Systems.

Damit findet das TS-Inventar die Reihen eines Signals ohne weitere Angabe, und der Zustand lässt sich berechnen.
Voraussetzung: `TopicLayout` kennt die drei Topics, und der Retrieval-Server liest sie wie das Episoden-Topic.

### 6.2 Flow, wie ihn ein Mensch schreibt, und DAG, wie ihn der Prozessor liest

Ein Flow im Systemdokument nennt **Signale**, keine Reihen-Ids und keine Topics:

```yaml
schema: opentsx.flow.v1                    # Schreibform; wird zu opentsx.dag übersetzt
flow: turbine-power-clean
reads: ["park/T02/WTUR.W@raw"]
nodes:
  - { id: range, op: dq.plausible-range, in: [source], params: { mode: gap } }   # min, max aus der Erwartung
  - { id: clean, op: resample,           in: [range],  params: { every: PT1M, agg: MEAN } }
writes: [ { from: clean, signal: "park/T02/WTUR.W@clean" } ]
```

Ein **Übersetzungsschritt** macht daraus den DAG für den Prozessor: Er löst Signale zu Topic und Reihen-Id auf
(Regel 6.1), belegt Parameter aus der Erwartung, ersetzt Prozessor-Kennungen durch die Operatoren des DAG
(Abschnitt 7) und setzt den `decode`-Knoten davor. Der Prozessor sieht nie einen Signalnamen. Die Workbench
erzeugt dieselbe Schreibform, wenn sie mit einem Signal geöffnet wurde.

### 6.3 Was sich am DAG und am Prozessor ändern muss

`opentsx.dag.v1`, wie `VectorJob` ihn heute liest, reicht nicht. Nötig ist eine Fassung `opentsx.dag.v2` mit drei
Erweiterungen:

1. **Senke für Reihen:** ein Sink, der v2-Episoden in das Topic der bereinigten Reihen schreibt. Heute sind nur
   Vektor-Topics erlaubt.
2. **Quelle aus mehr als einem Topic gleichen Formats:** Episoden-Topic, `synthetic` und `clean` sind alle
   v2-Episoden. Ein Verbund über Topics verschiedener Formate bleibt ausgeschlossen
   ([11](11-SMART-TOPIC-PROZESSOR-DEVOPS.md), Abschnitt 5a).
3. **Operatoren vor der Senke:** der Prozessor führt Transformatoren aus, nicht nur `align.vector` (TASK-013,
   013.5).

Bis dahin bleiben Flows mit Transformatoren interaktiv, und die Oberfläche sagt das.

## 7. Deklarative Beschreibung der Prozessoren: `opentsx.processor.v1`

Heute steht die Bedeutung eines Operators an drei Stellen: in `ops.ts`, im Java-Code und in der Tabelle in
[11](11-SMART-TOPIC-PROZESSOR-DEVOPS.md), Abschnitt 6. Schema:
[`schemas/opentsx.processor.v1.schema.json`](schemas/opentsx.processor.v1.schema.json), Beispiele:
[`gen.ou`](../../systems/processors/gen.ou.yaml), [`dq.plausible-range`](../../systems/processors/dq.plausible-range.yaml),
[`align.vector`](../../systems/processors/align.vector.yaml).

| `kind` | Eingänge | Beispiele |
|---|---|---|
| `generator` | keine | Ornstein-Uhlenbeck (Preise, Bedarf), Sinus-Mix, Wiedergabe einer Aufzeichnung |
| `transformer` | eine oder mehrere Reihen | Plausibilitätsprüfung, Resampling, Glättung, FFT, `align.vector` |
| `model` | Reihen und ein trainiertes Artefakt | Prognose, Anomalie-Score, Schritt des Zwillings |

```yaml
schema: opentsx.processor.v1
id: dq.plausible-range
version: 1
kind: transformer
title: Plausible range
description: Values outside the band become gaps or are clipped.
inputs:  [ { name: in,  type: series, cardinality: one } ]
outputs: [ { name: out, type: series } ]
params:
  - { name: min,  type: number, required: true, from: expect.min }
  - { name: max,  type: number, required: true, from: expect.max }
  - { name: mode, type: enum, values: [gap, clip], default: gap }
semantics: { needs: point, deterministic: true, keeps: [time, unit, series], origin: INHERIT }
dag: { op: dq.checks, fixed: { checks: [PLAUSIBLE_RANGE] } }
runtimes:
  workbench: { status: available }
  processor: { status: planned }
ui: { group: stream, label: Plausible range }
```

Was die Beschreibung trägt:

* **`from: expect.<feld>`** belegt einen Parameter aus der Erwartung des Signals, dessen Daten der Prozessor
  erzeugt. So entsteht "simuliert gemäß erwarteten Eigenschaften".
* **`dag`** nennt den Operator des DAG, wenn er anders heißt als der Prozessor (`dq.plausible-range` ist
  `dq.checks` mit der Prüfung `PLAUSIBLE_RANGE`).
* **`semantics.needs`** sagt, wie viel Eingabe ein Ausgabewert braucht: `point`, `window` oder `series` (die ganze
  Reihe). Ein Prozessor mit `series`, etwa Normierung auf das Maximum, kann nicht fortlaufend laufen.
* **Ausgang `xy`** steht für Ergebnisse über einer anderen Achse als der Zeit (Spektrum, Histogramm, Verteilung).
  Sie lassen sich ansehen, aber nicht als Signal veröffentlichen.
* **`runtimes`** sagt, wo ein Prozessor läuft. Daraus leitet die Workbench ab, ob ein Flow gestartet werden kann;
  die Liste "was fehlt" wird nicht mehr von Hand gepflegt.
* **`ui`** nennt Gruppe und Beschriftung in der Operatorleiste. Dauern stehen als ISO-Dauer; `editAs: seconds`
  sagt, dass die Workbench sie in Sekunden eingeben lässt.

Die Beschreibungen entstehen nicht alle auf einmal. Jede Lieferstufe beschreibt die Prozessoren, die sie braucht;
ein Test prüft, dass ein beschriebener Prozessor mit `ops.ts` übereinstimmt (Kennung, Parameter, Vorgaben).

## 8. Oberfläche

### 8.1 Die Hülle

Rahmen ist die Web-Anwendung, in englischer Sprache. Sie übernimmt den Aufbau des Studios (Leiste links, Pfad
oben, Statuszeile unten) in React, mit einer gemeinsamen Datei für Farben und Abstände, aus der auch die Workbench
schöpft. Die Workbench behält ihre Dichte.

```
+------------------------------------------------------------------------------------------+
| OpenTSx   h2-chain v    8 signals: 2 from topics, 1 derived, 1 simulated, 2 manual,      |
|                         2 open                                                           |
+------------+-----------------------------------------------------------------------------+
| System     |                                                                             |
|   8 ports  |                                                                             |
| Signals    |   (Inhalt des Orts)                                                         |
|   2 open   |                                                                             |
| Flows      |                                                                             |
|   2, 0 run |                                                                             |
| Models     |                                                                             |
|   1 blocked|                                                                             |
| ---------- |                                                                             |
| Workbench  |                                                                             |
+------------+-----------------------------------------------------------------------------+
| example data | system hash 3f2a | times in UTC                                           |
+------------------------------------------------------------------------------------------+
```

Adressen: `/systems`, `/systems/<id>/system|signals|flows|models`. Die Zahlen der Skizze entsprechen dem Beispiel.

**Geführter Modus (wahlweise).** Die vier Orte sind die Grundlage. Wer neu ist oder ein System von vorn aufbaut,
schaltet "Guide" ein. Dann steht über dem Inhalt die Folge der sieben Schritte der Methode
([Benutzerhandbuch, Kapitel 16](../user-manual/16-engineering-method.md)), nummeriert, mit dem Stand je Schritt
und einem Sprung an den Ort, an dem der Schritt getan wird. Der Modus ändert nichts an den Orten und erzwingt
keine Reihenfolge; er zeigt den nächsten sinnvollen Schritt.

```
 Guide:  1 System ok | 2 Signals ok | 3 Stock ok | 4 Bind 2 open | 5 Prepare 1 of 3 | 6 Run 0 | 7 Model blocked
```

### 8.2 Signals

Eine Zeile sagt, was das Signal ist, woher es kommt, wie es ihm geht und was als Nächstes zu tun ist. Gruppiert
wird nach Baustein und Asset; eine Gruppe lässt sich einklappen, damit ein Park mit vielen Turbinen lesbar bleibt.

```
 park
   Signal              expected             origin      state                    next
   p_avail_kw          kW 0..12000 1 min    synthetic   clean, not running       [Start]
   T02 / WTUR.W        kW -100..10000 1 s   synthetic   raw only                 [Prepare]
   T02 / WMET.HorWdSpd m/s 0..60 1 s        synthetic   raw only                 [Prepare]
 market
   price_eur_mwh       EUR/MWh 15 min       SIMULATED   raw only (gen.ou, seed 7) [Prepare]
 ely
   setpoint_kw         kW 0..5000           reported    constant 5000            n/a
 offtake
   demand_kgph         kg/h 0..120 1 min    n/a         open                     [Bind] [Simulate]
```

Ein Klick auf eine Zeile öffnet rechts: Erwartung (änderbar), Quelle je Güte, Zeitraum, ein kleiner Verlauf mit
dem erwarteten Wertebereich, wer das Signal nutzt, und die Aktionen Bind, Simulate, Replace with measurement,
Prepare, Open on scope.

### 8.3 Übergabe in die Workbench und zurück

Die Workbench hält heute genau einen Flow; ein zweiter ersetzt ihn. Deshalb:

* **Vor der Übergabe** fragt die Hülle nach, wenn die Workbench ungespeicherte Arbeit hält.
* **In der Workbench** steht eine Kontextzeile:

  ```
  < Signals | h2-chain > park/T02/WTUR.W > raw -> clean          [Publish as clean signal]
  expected kW -100..10000, 1 s | synthetic | unsaved changes
  ```

  Der Schirm zeigt den erwarteten Wertebereich als Linien, Parameter mit `from: expect.*` sind vorbelegt.
* **"Publish as clean signal"** ersetzt die drei Handgriffe von heute (Haken "Publish as Smart Topic", "Save
  flow", "Deploy") durch einen Dialog, der sagt: welches Signal, welcher Ausgang des Flows, dass die Änderung in
  der eigenen Arbeitskopie steht und noch nicht im Repository (D42), dass der Flow noch nicht läuft und wo er
  gestartet wird, und welche Schritte der Prozessor nicht ausführen kann.
* **Danach** führt die Workbench zurück zur Zeile des Signals, die hervorgehoben ist.
* Ohne System bleibt die Workbench benutzbar wie heute.

### 8.4 Bestehende Beschriftungen, die sich ändern

| Heute | Künftig | Grund |
|---|---|---|
| Link "Editor" (Composer-Seite), Link "Composer page" (Workbench) | "Workbench", "Flows" | zwei Namen für dieselben Orte |
| Spalte "buckets" im Composer | "steps" | sie zählt Schritte |
| Reiter "Input bucket" | bleibt | der Name ist ein ausdrücklicher Wunsch des Auftraggebers; der Vorschlag "Steps" aus dem Review entfällt |
| "Create a vector of the loaded series" | "Align series to a common clock" | sagt, was geschieht |
| zwei Zustandsspalten im Composer ("status", "Smart Topic") | eine | derselbe Sachverhalt |
| Reiter "TS-Model" in der Workbench | bleibt | er spiegelt die Swing-Workbench und sagt selbst, dass seine Knöpfe dort auch nichts tun |

### 8.5 Die Studio-Ansichten

"Systembild" wird der Ort System, "Messreihen" geht in Signals auf, "Überblick" und "Vergleich" werden Models.

**Stand L2, abweichend vom Entwurf:** Der Ort System bettet den Prototyp nicht ein. Er zeigt den Schaltplan als
Tabelle: je Baustein die Eingänge mit Einheit und dem, was sie speist (Ausgang eines anderen Bausteins, Signal mit
Zustand oder nichts), und die Ausgänge. Der Grund: Der Prototyp ist eine erzeugte HTML-Datei außerhalb der
Web-Anwendung, und von seinen Ports führt kein Weg zu einem Signal. Die Tabelle leistet genau diese Verbindung und
zeigt nebenbei, welche Eingänge von außen noch kein Signal haben. Das Bild mit den Verbindungen bleibt Aufgabe von
L5 (Systembild in React).

## 9. Was der Entwurf nicht löst

* **Kein Generator schreibt heute fortlaufend in ein Topic.** Eine Laufzeit dafür ist neu. Sie schreibt auf einen
  gemeinsam genutzten Broker; sie darf nur in das Topic `synthetic` ihrer Domain schreiben, nie in ein
  Episoden-Topic, und braucht ein eigenes Recht (heute gilt ein Token für alles).
* **Der Prozessor führt nur `align.vector` aus und schreibt nur Vektoren** (Abschnitt 6.3).
* **Die Smart-Topic-Spezifikation ([05](05-SMART-TOPICS.md)) wählt ihr Ziel per Metrik und Tags** und kann kein
  Signal nennen. Für Models braucht sie `target.signal`; ihr eigenes `resample` wird dann überflüssig. Ihr Abschnitt
  `features` überschneidet sich mit der Güte `features` (D43): Merkmale, die mehrere Modelle brauchen, stehen am
  Signal, die eines einzelnen Modells bleiben in dessen Spezifikation. Die Abgrenzung ist mit L5 festzulegen.
* **Prozessoren für Merkmale gibt es nicht.** Das Beispiel nennt `feature.lags` und `feature.rolling`; beide sind
  zu bauen.
* **Der Zwilling rechnet einen Lauf als Ganzes.** Models zeigt zunächst gerechnete Läufe.
* **Viele gleichartige Assets.** Das Beispiel nennt eine Turbine von sechs. Eine Schablone "für jedes Asset dieser
  Art" fehlt im Schema; ohne sie wird ein Dokument für einen großen Park lang.
* **Gleichzeitige Änderungen.** Die Arbeitskopie je Konto (D42) braucht eine Regel: Jede Änderung trägt den Hash
  der Fassung, von der sie ausging; passt er nicht mehr, wird abgelehnt statt überschrieben.
* **Die Portliste des Schaltplans kennt nur der Java-Code** (`AtomCatalog`). Das Prüfwerkzeug braucht Java oder
  den Export des Studios.

## 10. Lieferstufen (nach Freigabe)

Die erste Fassung begann mit Verträgen für alle Operatoren, ohne sichtbares Ergebnis. Die Reihenfolge ist jetzt
vom kleinsten Stand her gebaut, der die Idee von Anfang bis Ende zeigt.

| Stufe | Inhalt | Sichtbar |
|---|---|---|
| L1 (**umgesetzt** 2026-10-13) | Systemdokument mit an Topics gebundenen und offenen Signalen; Prüfung im Build; `GET /v1/systems` und `/v1/systems/<id>/signals` mit berechnetem Zustand; Ort Signals; "Open on scope" mit Kontextzeile und erwartetem Wertebereich | Katalog "erwartet gegen vorhanden" für `h2-chain`, mit Sprung auf den Schirm |
| L2 (**umgesetzt** 2026-10-13) | Hülle: Leiste, Statuszeile, gemeinsame Farben; geführter Modus; Beschriftungen nach 8.4; Ort Flows (Composer mit Filter); Ort System als Tabelle der Bausteine und Ports (statt des eingebetteten Prototyps, siehe 8.5); Ort Models als Vorschau | ein Rahmen statt drei Seiten |
| L3 (**umgesetzt** 2026-10-13, mit Grenzen) | Topic `clean`, Senke für Reihen (`opentsx.dag.v2`), drei Operatoren im Prozessor (Plausibilitätsbereich, Einheit, Resampling), "Publish as clean signal". Nicht umgesetzt: die Schreibform `opentsx.flow.v1` mit Übersetzungsschritt (die Workbench schreibt den DAG direkt), das Zusammenführen mehrerer Reihen, das Zurückschreiben ins Systemdokument | Rohdaten einer Reihe werden fortlaufend zu einer bereinigten Reihe |
| L4 (**umgesetzt** 2026-10-13, mit Grenzen) | Topic `synthetic`; zwei Demo-Generatoren (`gen.ou`, `gen.sine`) als Einmal-Lauf über einen Zeitraum, nur für Administratoren und nur in dieses Topic (D61); Aktionen Bind, Simulate und Generate auf der Seite Signals; Binden erst nach bestätigter Bedeutung (D53); Arbeitskopie je Konto über dem Systemdokument, mit Export der Zeilen für die Datei (D42). Nicht umgesetzt: ein Generator, der fortlaufend schreibt; das Zurückschreiben in die Datei | offene Signale werden gebunden oder simuliert |
| L5 (**umgesetzt** 2026-10-13, mit Grenzen) | Models: Topic `features`, Operatoren für Merkmale (`feature.lag`, `feature.rolling`) und ein erstes Modell (`model.power-curve`), Senke in ein anderes Signal mit `origin` und `madeOf`; Freigabe je Zweck aus der Entscheidungskarte mit Gültigkeitsbereich und Nachweis (D59, `GET /v1/systems/<id>/decisions`); Ort Models; Systembild als Graph in React. Nicht umgesetzt: der Vergleich von Alternativen (folgt mit E3), `target.signal` in der Vektor-Spezifikation | Prognose und Was-wäre-wenn im selben Rahmen |

Prozessor-Beschreibungen kommen mit der Stufe, die sie braucht: L3 die Transformatoren der Bereinigung, L4 die
Generatoren.

## 11. Entscheidungen

Entschieden vom Auftraggeber am 2026-10-13.

| Nr. | Thema | Entscheidung | Stand |
|---|---|---|---|
| D40 | Rahmen | die Web-Anwendung ist die Hülle, im Aufbau des Studios; dessen Ansichten werden eingebettet und später nachgebaut | wie vorgeschlagen |
| D41 | Schlüssel | das Signal verbindet alles; Kennung `<baustein>.<port>` oder `<baustein>/<asset>/<kanal>`, eindeutig im System | wie vorgeschlagen |
| D42 | Ablage | Systemdokumente und Prozessor-Beschreibungen liegen als Dateien im Repository; der Retrieval-Server liefert sie aus; die Oberfläche schreibt in eine Arbeitskopie je Konto, mit Hash der Ausgangsfassung, und erzeugt daraus die Datei | wie vorgeschlagen |
| D43 | Güte | **drei:** `raw`, `clean` und `features`. Merkmale sind eine Güte des Signals und lassen sich zwischen Modellen teilen | **geändert** (Vorschlag war zwei) |
| D44 | Prozessoren | eine Beschreibung je Prozessor, Art Generator, Transformator oder Modell; sie entstehen stufenweise und werden gegen `ops.ts` geprüft | wie vorgeschlagen |
| D45 | Sprache | Englisch; Texte der Szenarien bleiben in ihrer Sprache | wie vorgeschlagen |
| D46 | Beispielsystem | `h2-chain`: Es hat ein Systembild, und sein Windpark steht auf den Daten in `tsx.wind.episodes.v2` | wie vorgeschlagen |
| D47 | Ort der Bindung | welche Reihen zu einem Signal gehören, steht im Systemdokument; `series` im Inventar entfällt, wenn das Inventar von Code gelesen wird | wie vorgeschlagen |
| D48 | Topics | je Domain ein Topic `tsx.<domain>.synthetic.v2` und eines `tsx.<domain>.clean.v2`, beide v2-Episoden; die Reihe folgt der Regel in 6.1 | wie vorgeschlagen |
| D49 | Flow und DAG | Flows nennen Signale (`opentsx.flow.v1`); ein Übersetzungsschritt erzeugt den DAG; der Prozessor bekommt `opentsx.dag.v2` mit Senke für Reihen | wie vorgeschlagen |
| D50 | Orte | vier Orte ohne Nummern (System, Signals, Flows, Models) und die Workbench als Werkzeug als Grundlage; **dazu wahlweise ein geführter Modus** mit den nummerierten Schritten der Methode | **ergänzt** |
| D51 | Topic für Merkmale | `tsx.<domain>.features.v2`, v2-Episoden, eine Reihe je Merkmal mit Tag `feature` | **Folge aus D43 und D48, nicht eigens entschieden**; bei Widerspruch vor L5 ändern |
