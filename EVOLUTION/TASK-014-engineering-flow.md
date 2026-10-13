# TASK-014: Engineering-Ablauf vom Szenario zum Modell

## Task Metadata
- **Task ID**: TASK-014-engineering-flow
- **Created**: 2026-10-13
- **Status**: Entwurf, wartet auf Freigabe
- **Priority**: High
- **Epic**: P7e Personal Service Layer / Studio
- **Delivery-Branch**: `opentsx/2026-task014-engineering-flow`, Worktree `../OpenTSx-worktrees/engineering-flow`
- **Konzept**: [`docs/datamodel-2026/14-ENGINEERING-FLOW.md`](../docs/datamodel-2026/14-ENGINEERING-FLOW.md)

## Ziel
Studio (System von oben), Workbench (Reihen von unten) und Flow Composer (Betrieb) werden Orte eines Ablaufs:
System definieren, Messgrößen erwarten, anbinden oder simulieren, Rohdaten bereinigen, betreiben, modellieren. Am
Ende steht eine deklarative Darstellung des ganzen Systems (`opentsx.system.v1`) und der Zeitreihen-Prozessoren
(`opentsx.processor.v1`: Generatoren, Transformatoren, Modelle), verbunden mit Oberfläche und Workbench.

## Vorgehen
Erst Konzept und Entwurf, dann Review (Engineering, UX), dann Freigabe durch den Auftraggeber, dann Umsetzung in
den Lieferstufen L1 bis L5 des Konzepts. **Vor der Freigabe wird nichts umgesetzt.**

## Subtasks
- [x] 014.1 Konzept und Entwurf (Stand 2026-10-13): Signal als Schlüssel, zwei Schemas, Beispiele für `h2-chain`
      (System, drei Prozessoren, zwei Flows)
- [x] 014.2 Review Engineering und UX (Stand 2026-10-13), Befunde eingearbeitet: zweite Fassung des Konzepts
- [ ] 014.3 Freigabe der Entscheidungen D40 bis D50
- [ ] 014.4 L1 Systemdokument, Prüfwerkzeug, Signalzustand, Ort Signals mit Sprung auf den Schirm
- [ ] 014.5 L2 Hülle, Beschriftungen, Orte Flows und System
- [ ] 014.6 L3 Topic für bereinigte Reihen, Senke für Reihen, Operatoren im Prozessor, "Publish as clean signal"
- [ ] 014.7 L4 Generatoren: Topic, Laufzeit, Bind und Simulate
- [ ] 014.8 L5 Models

## Stand der Prüfung
* Die Beispieldokumente sind gegen die beiden Schemas geprüft, dazu die Verweise im Systemdokument: Bausteine im
  Schaltplan, Kanäle im Katalog, Assets im Inventar, Flows in der Liste und mit passendem Ausgang, Generatoren mit
  Beschreibung und Pflichtparametern. Ergebnis: 8 Signale, 2 offen, 3 Prozessoren, kein Fehler.
* Ein Hinweis bleibt stehen und ist gewollt: Das Inventar nennt für das Asset `h2-chain/park/T02` die Reihe der
  Windgeschwindigkeit nicht, das Systemdokument bindet sie. Das ist der Fall, den D47 entscheidet.
* 13 Gegenproben (je eine Änderung, die das Schema ablehnen muss) werden abgelehnt.
* Das Prüfskript liegt nicht im Repository; es wird in L1 Teil der CI. Für `opentsx.flow.v1` gibt es noch kein
  Schema, nur die beiden Beispiele.
* Nicht geprüft: ob der Schaltplan die genannten Ports hat (die Portliste kennt nur der Java-Code; der Engineering-
  Review hat die sechs Eingänge und ihre Einheiten gegen `PortSpec` bestätigt).

## Review

Zwei unabhängige Reviews der ersten Fassung, beide mit dem Ergebnis "Idee trägt, so nicht freigabereif". Die
Tatsachenbehauptungen wurden vor dem Einarbeiten am Code nachgeprüft.

### Engineering

| Befund | Schwere | Was daraus wurde |
|---|---|---|
| Die Beispiel-Flows kann `VectorJob` nicht lesen: Signalnamen statt Reihen-Ids, Knoten `decode` fehlt, Senken müssen Vektor-Topics sein, nur das Episoden-Topic ist Quelle | Blocker | Abschnitt 6: Schreibform `opentsx.flow.v1` mit Signalen, Übersetzungsschritt zum DAG, drei benannte Erweiterungen als `opentsx.dag.v2` (D49) |
| Wo die Daten einer Güte liegen, war nicht ausdrückbar; der Zustand ließ sich für Generator und Flow nicht berechnen | Blocker | Regel 6.1: ein Topic `synthetic` und ein Topic `clean` je Domain, Reihe aus Metrik und Tags `system`, `signal` (D48) |
| `op` ist nicht die Prozessor-Kennung (`dq.plausible-range` wird `dq.checks`); die Beispielbeschreibung widersprach dem Operator | sollte | Feld `dag` in der Beschreibung; Beispiel an `ops.ts` angeglichen |
| `align.vector`: falsche Werte für `fill`, `names` fehlte, Dauer gegen Sekunden; `ui.group` nicht aus `OpGroup` | sollte | Beispiel korrigiert; Parametertyp `list`, `editAs: seconds`, `ui.group` als Aufzählung |
| Nicht beschreibbar: Operatoren mit anderer Achse als der Zeit (FFT, Histogramm), Operatoren, die die ganze Reihe brauchen | sollte | Ausgang `xy`; `semantics.needs: point, window, series` |
| Vokabular: `STATUS` statt `STATE`, `sampling`, Herkunft `SYNTHETIC` für Simulator-Daten in einem Topic, `manual` ist nicht synthetisch, `_` in der Domain | sollte | Schema und Text angeglichen; die Windpark-Daten des Beispiels tragen jetzt `SYNTHETIC` |
| Zwei Wahrheiten: `id` gegen `port`, Bindung auch im Inventar, Asset T05 nicht im Inventar, Generator als Quelle und als Knoten | sollte | `port` entfällt (die Kennung ist der Port); Beispiel auf T02; Generatoren nur als Quelle; D47 |
| Lücken im Schema (leere Erwartung, Topic ohne Auswahl, `PT`, Aufzählung ohne Werte) | sollte | geschlossen, mit Gegenproben; Eindeutigkeit und Verweise prüft das Werkzeug |
| Reihenfolge: alle Operator-Beschreibungen vor jedem Nutzen | sollte | Lieferstufen neu: L1 ist der kleinste Stand von Anfang bis Ende; Beschreibungen stufenweise |
| Risiken: Hash deckt Verweise nicht, Arbeitskopie ohne Regel, Schreibrecht der Generator-Laufzeit, Smart-Topic-Spezifikation kennt keine Signale | bedenken | Hash über Inhalte der Verweise; Abschnitt 9 nennt die offenen Punkte |

### UX

| Befund | Schwere | Was daraus wurde |
|---|---|---|
| Sechs nummerierte Stufen sind vier Orte und ein Werkzeug; Nummern behaupten eine Reihenfolge | Blocker | Abschnitt 3.2 und 8.1: System, Signals, Flows, Models, Workbench; Bind und Simulate als Aktionen in Signals (D50) |
| Die Fortschrittsleiter ist nicht linear, die Zeile sagt nicht, was zu tun ist | Blocker | Abschnitt 4 und 8.2: ein Zustand je Signal und ein nächster Schritt; Gruppierung nach Baustein und Asset |
| Übergabe in die Workbench: ein globaler Flow wird ersetzt, Veröffentlichen sind heute drei Handgriffe | Blocker | Abschnitt 8.3: Nachfrage bei ungespeicherter Arbeit, Kontextzeile, ein Dialog, Rückweg zur Zeile |
| Simuliert muss unverwechselbar sein; `manual` sah aus wie Simulation; zweites Herkunftsvokabular | sollte | eine Herkunft (`Origin`), `manual` mit Vorgabe `REPORTED`, Marke und gestrichelte Kurve für `SYNTHETIC`, vererbt |
| Begriffe: System gegen Szenario, Signal gegen Reihe gegen Kanal, "Run", "Stufe" dreifach | sollte | Abschnitt 2; "Run" heißt "Flows"; Güte, Ort, Lieferstufe |
| Bestehende Beschriftungen ("Editor", "Composer page", "buckets", "Input bucket", zwei Zustandsspalten, Reiter "TS-Model") | sollte | Abschnitt 8.4, Teil von L2 |
| Hülle: ein Link auf den deutschen Prototyp wirkt wie ein Wechsel des Produkts | sollte | Abschnitt 8.1 und 8.5: Aufbau des Studios in React, Prototyp eingebettet ohne eigene Leisten |
| Erste Lieferung zeigt nichts, womit man etwas tun kann | sollte | L1 enthält "Open on scope" mit Kontextzeile und erwartetem Wertebereich |
| Zahlen im Kopf der Skizze passten nicht zum Beispiel | bedenken | angeglichen |

Nicht übernommen: ein klickbarer statischer Prototyp des ganzen Wegs **vor** der Freigabe (UX). Er wäre schon
Umsetzung; die Freigabe soll vorher stehen. L1 liefert den ersten bedienbaren Stand.
