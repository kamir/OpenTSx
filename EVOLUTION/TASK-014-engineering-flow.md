# TASK-014: Engineering-Ablauf vom Szenario zum Modell

## Task Metadata
- **Task ID**: TASK-014-engineering-flow
- **Created**: 2026-10-13
- **Status**: Freigegeben am 2026-10-13, Umsetzung läuft (L1)
- **Priority**: High
- **Epic**: P7e Personal Service Layer / Studio
- **Delivery-Branch**: `opentsx/2026-task014-clean-signals` (L3 und die Überarbeitung nach dem Review-Briefing), Worktree
  `../OpenTSx-worktrees/engineering-flow`
- **Frühere Delivery-Branches** (gemergt): `opentsx/2026-task014-engineering-flow` (Konzept, Freigabe, L1, PR #182),
  `opentsx/2026-task014-shell` (L2, PR #185)
- **Konzept**: [`docs/datamodel-2026/14-ENGINEERING-FLOW.md`](../docs/datamodel-2026/14-ENGINEERING-FLOW.md)

## Ziel
Studio (System von oben), Workbench (Reihen von unten) und Flow Composer (Betrieb) werden Orte eines Ablaufs:
System definieren, Messgrößen erwarten, anbinden oder simulieren, Rohdaten bereinigen, betreiben, modellieren. Am
Ende steht eine deklarative Darstellung des ganzen Systems (`opentsx.system.v1`) und der Zeitreihen-Prozessoren
(`opentsx.processor.v1`: Generatoren, Transformatoren, Modelle), verbunden mit Oberfläche und Workbench.

## Vorgehen
Erst Konzept und Entwurf, dann Review (Engineering, UX), dann Freigabe durch den Auftraggeber, dann Umsetzung in
den Lieferstufen L1 bis L5 des Konzepts. Die Freigabe liegt seit 2026-10-13 vor.

## Subtasks
- [x] 014.1 Konzept und Entwurf (Stand 2026-10-13): Signal als Schlüssel, zwei Schemas, Beispiele für `h2-chain`
      (System, drei Prozessoren, zwei Flows)
- [x] 014.2 Review Engineering und UX (Stand 2026-10-13), Befunde eingearbeitet: zweite Fassung des Konzepts
- [x] 014.2a Methodik-Leitfaden im Benutzerhandbuch (Stand 2026-10-13):
      [`docs/user-manual/16-engineering-method.md`](../docs/user-manual/16-engineering-method.md), englisch wie das
      Handbuch. Sieben Schritte, Regeln, Beispiel, Checkliste; je Schritt, was heute geht und was geplant ist.
      Kapitel 14 dabei auf den Stand gebracht (Flow Composer, Workbench, CI)
- [x] 014.3 Freigabe der Entscheidungen D40 bis D50 (2026-10-13): neun wie vorgeschlagen; **D43 geändert** auf drei
      Güten (`raw`, `clean`, `features`); **D50 ergänzt** um einen wahlweisen geführten Modus mit nummerierten
      Schritten. Folge: D51, ein Topic für Merkmale je Domain (nicht eigens entschieden). Konzept, Schema, Beispiel
      und Leitfaden sind nachgezogen
- [x] 014.4 L1 (Stand 2026-10-13)
      - Systemdokumente liegen unter `systems/` (`h2-chain.system.yaml`, `flows/`, `processors/`), nicht mehr als
        Beispiele unter `docs/`
      - `opentsx-retrieval`, Paket `system`: `SystemDoc` lädt das Dokument mit Schaltplan, Katalogen und Inventar
        und sammelt Fehler (Port fehlt, Einheit weicht ab, Kanal oder Asset unbekannt, Flow schreibt die Güte nicht,
        Kennung doppelt und weitere); `Systems` berechnet je Signal Quelle, Herkunft, Zustand und nächsten Schritt
        aus dem TS-Inventar
      - Routen `GET /v1/systems` und `/v1/systems/<id>/signals` (`opentsx.signal-status.v1`); Schalter `--systems`
        und `--systems-root`; das Image bringt die Dokumente mit (`deploy/docker/stage.sh`)
      - Prüfung im Maven-Build: beide JSON-Schemas gegen die Dokumente, 15 gezielte Fehlerfälle, Zustände, Routen
      - Oberfläche: Seite "Signals" (`/systems`), gruppiert nach Baustein und Asset, mit Filtern und Details;
        "Prepare" und "Open on scope" öffnen die Workbench mit Kontextzeile und erwartetem Wertebereich auf dem
        Schirm; Nachfrage, bevor ein ungespeicherter Flow ersetzt wird
      - Grenzen: Die Zustände "running" und "stale" gibt es noch nicht (nichts läuft); Bind, Simulate, Generate
        und Start sind sichtbar, aber gesperrt, mit Hinweis auf die Lieferstufe; die Bindung wird von Hand ins
        Dokument geschrieben; Schreibform der Flows (`opentsx.flow.v1`) ohne Schema
- [x] 014.5 L2 (Stand 2026-10-13)
      - Hülle unter `/systems/<id>/<ort>`: Leiste mit den vier Orten und der Workbench als Werkzeug, je Ort eine
        Zahl; Kopfzeile mit Systemwahl; Statuszeile mit Version und Hash
      - Geführter Modus (Schalter "Guide", gemerkt je Browser): die sieben Schritte der Methode mit dem Stand des
        Systems je Schritt, der nächste sinnvolle Schritt hervorgehoben
      - Ort System: Route `GET /v1/systems/<id>/plan`; je Baustein die Eingänge mit dem, was sie speist. Für
        `h2-chain` zeigt das einen echten Befund: Von neun Eingängen von außen haben drei kein Signal
        (`offtake.valve`, `grid.p_self_kw`, `grid.p_grid_meas_kw`)
      - Ort Flows: der Flow Composer, gefiltert auf das System. Ein Flow, der aus einem Signal heraus gebaut wird,
        merkt sich System und Signal; Flows ohne System zählen über ihre Domain dazu
      - Ort Models: Vorschau, welche Signale bereinigt vorliegen und welche fehlen
      - Beschriftungen: "Workbench" und "Flows" statt "Editor" und "Composer page", "steps" statt "buckets", eine
        Zustandsspalte im Composer, "Align series to a common clock"
      - Abweichungen vom Entwurf: Ort System als Tabelle statt eingebettetem Prototyp; "Input bucket" und der
        Reiter "TS-Model" bleiben (Konzept, Abschnitte 8.4 und 8.5)
- [x] 014.6 L3 (Stand 2026-10-13)
      - `TopicLayout`: Topics `clean`, `synthetic`, `features` je Domain
      - `opentsx-retrieval`, Paket `clean`: `CleanChain` (Plausibilitätsbereich als Lücke oder begrenzt,
        Einheitenumrechnung, Resampling auf Fenster am Epochen-Raster) und `SeriesStream` (je Rohreihe eine
        bereinigte Reihe mit den Tags `system` und `signal`, Labels `grade`, `origin`, `flow`, `specHash`;
        Zustand für den Wiederanlauf)
      - `opentsx.dag.v2`: Senke für Reihen (`schema: opentsx.episode.v2`, `signal: {system, id, grade}`).
        `SeriesJob` und `DagPlan` lesen sie; der Prozessor führt Vektor- und Reihen-Senken mit demselben Läufer
        aus (Checkpoint, Status, eigener Producer je Senke)
      - Retrieval-Server liest das Topic der bereinigten Reihen mit; `Systems` findet eine bereinigte Reihe an
        ihren Tags, auch wenn das Systemdokument sie nicht deklariert
      - Workbench: Kontextzeile mit "Publish as clean signal" (ein Schritt statt drei: prüfen, speichern,
        starten), Operatoren aus der Erwartung des Signals vorbelegt, Fenster am Epochen-Raster wie im Prozessor;
        ein gespeicherter Flow bringt sein Signal wieder mit
      - Gemeinsame Testvektoren `docs/datamodel-2026/testvectors/clean-ops.v1.json` für Java und TypeScript, als
        Ganzes und als Strom in beliebigen Stücken
      - **Befund beim Lauf auf master2:** Dem bereinigten Signal fehlte die erste von 36 Episoden. Sehr
        wahrscheinliche Ursache: Verweigert der Broker eine Partition, wirft der Kafka-Client die Ablehnung aus
        der Mitte eines Abrufs und verliert dabei die schon geholten Datensätze der anderen Partitionen, deren
        Lesepositionen bereits weitergerückt sind. Aus dem Quelltext des Clients ist das nicht belegt, nur aus dem
        Verhalten. Behoben in `Patience`: nach einer Ablehnung zurück auf die Positionen vor dem Abruf; das gilt
        für Prozessor, Retrieval-Feed und Statistik. Der Nachweis auf master2 steht aus, weil der Broker danach
        minutenlang Lesen und Schreiben verweigerte
      - Grenzen: Der Prozessor führt vor einer Reihen-Senke genau drei Operatoren aus; eine Kette, kein
        Zusammenführen mehrerer Reihen (die Summe über Turbinen läuft also nicht); "Publish" schreibt nichts ins
        Systemdokument (D42 offen); die Sperre "ein Signal, ein Schreiber" sitzt im Frontend
- [ ] 014.7 L4 Generatoren: Topic, Laufzeit, Bind und Simulate
- [ ] 014.8 L5 Models

## Stand der Prüfung
* Die Beispieldokumente sind gegen die beiden Schemas geprüft, dazu die Verweise im Systemdokument: Bausteine im
  Schaltplan, Kanäle im Katalog, Assets im Inventar, Flows in der Liste und mit passendem Ausgang, Generatoren mit
  Beschreibung und Pflichtparametern. Ergebnis: 8 Signale, 2 offen, 3 Prozessoren, 3 Flows, kein Fehler.
* Ein Hinweis bleibt stehen und ist gewollt: Das Inventar nennt für das Asset `h2-chain/park/T02` die Reihe der
  Windgeschwindigkeit nicht, das Systemdokument bindet sie. Das ist der Fall, den D47 entscheidet.
* 13 Gegenproben (je eine Änderung, die das Schema ablehnen muss) werden abgelehnt.
* Seit L1 läuft diese Prüfung im Maven-Build (`SystemDocTest`); das Skript aus der Entwurfsphase ist damit
  abgelöst. Für `opentsx.flow.v1` gibt es noch kein Schema, nur die drei Flows unter `systems/flows/`.
* Ports und ihre Einheiten prüft `SystemDoc` seit L1 gegen den Schaltplan (`AtomCatalog`).

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
