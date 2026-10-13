# TASK-014: Engineering-Ablauf vom Szenario zum Modell

## Task Metadata
- **Task ID**: TASK-014-engineering-flow
- **Created**: 2026-10-13
- **Status**: L1 bis L3 umgesetzt; Plan nach dem Review-Briefing entschieden am 2026-10-13; als Nächstes L4
- **Priority**: High
- **Epic**: P7e Personal Service Layer / Studio
- **Delivery-Branch**: `opentsx/2026-task014-dwd-2025` (J1, das Jahr 2025, 014.14), Worktree
  `../OpenTSx-worktrees/dwd-2025`
- **Frühere Delivery-Branches** (gemergt): `opentsx/2026-task014-demo-ui` (Demo zeigbar, Lauf-Dienst, PR #188), `opentsx/2026-task014-system-plan` (S1, Demo-Entwurf, Agenten, PR #187), `opentsx/2026-task014-clean-signals` (L3, L4, L5, Neuplanung, PR #186), `opentsx/2026-task014-engineering-flow` (Konzept, Freigabe, L1, PR #182),
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
- [x] 014.6a Review-Briefing verarbeitet (Stand 2026-10-13): externes Review (Engineering und komplexe Systeme) zum
      Leitfaden und zum Plan. Ergebnis ist
      [`docs/datamodel-2026/15-ENTSCHEIDUNGSKREIS.md`](../docs/datamodel-2026/15-ENTSCHEIDUNGSKREIS.md): die
      Entscheidung als zentrale Einheit, das Signal mit Rolle und zwei Wertebereichen, `clean` als Kennzeichnen statt
      Entfernen, ein Streaming-Vertrag, Modellfreigabe je Zweck, vier Datensätze für den Kreis, ein neuer Plan.
      Befunde und was aus jedem wurde: unten, Abschnitt "Review-Briefing"
      - sofort umgesetzt: Korrektur des H2-Beispiels (tatsächliche Erzeugung und verfügbare Leistung sind zwei
        Signale; Systemdokument Fassung 2 mit zehn Signalen und fünf Flows); `role`, `meaning` und `operating` im
        Schema, im Server und in den Details eines Signals; Kennung für bereinigte Episoden, damit ein Wiederanlauf
        nicht doppelt zählt; Schema-Entwürfe `opentsx.decision.v1` und `opentsx.decision-record.v1` mit Beispiel,
        im Build geprüft; Streaming-Vertrag in 11, Abschnitt 7a, mit "Heute" und "Ziel" je Punkt; Modellfreigabe in
        05, Abschnitt 5a; Leitfaden (Kapitel 16) auf acht Schritte umgeschrieben
- [x] 014.7 Entscheidungen D52 bis D63 (2026-10-13): zehn wie vorgeschlagen. **D57 geändert:** `clean` wird in zwei
      Varianten geschrieben, `marked` und `filtered`, im selben Topic. **D62 geändert:** erst L4 und L5 fertig
      bauen, dann neu planen und mit E1 bis E3 weitermachen
- [x] 014.8 L4 (Stand 2026-10-13)
      - Demo-Generatoren `gen.ou` und `gen.sine` (`opentsx-retrieval`, Paket `generate`): Der Wert eines Zeitpunkts
        hängt nur von Startwert, Parametern und diesem Zeitpunkt ab; Episoden auf festem Raster mit Kennung, sodass
        derselbe Zeitraum, noch einmal erzeugt, sich selbst ersetzt. Labels `origin: SYNTHETIC`, `fidelity: demo`
      - `POST /v1/systems/<id>/generate` im Retrieval-Server (Schreibroute, braucht das Run-Token; schreibt
        ausschließlich nach `tsx.<domain>.synthetic.v2`); der Feed liest das Topic mit
      - Arbeitskopie (D42): Tabelle `signal_bindings` im SaaS-Backend, je Konto und System; Routen
        `GET/PUT/DELETE /api/v1/systems/<id>/bindings` und `POST .../generate` (nur Administratoren, reicht die
        Arbeitskopie mit). Der Retrieval-Server legt Bindungen aus dem Rumpf über das Dokument
        (`POST /v1/systems/<id>/signals`) und kennzeichnet sie
      - Seite Signals: Bind (Vorschläge aus dem TS-Inventar nach Einheit und Wertebereich, Herkunft wählen, Bedeutung
        bestätigen, D53), Simulate (Generator aus der Erwartung vorbelegt, als Demo benannt, D61), Generate
        (Zeitraum vorbelegt mit dem Datenzeitraum des Systems); Hinweis auf die Arbeitskopie mit "Copy as YAML"
      - Reihen, die nach Regel geschrieben werden, tragen jetzt das Tag `grade`
      - Grenzen: Generatoren laufen einmal über einen Zeitraum, nicht fortlaufend; die Arbeitskopie wird nicht in
        die Datei geschrieben; die Sperre "Ergebnisse auf Demo-Generatoren taugen nicht für Strategievergleiche"
        ist als Label da (`fidelity: demo`), geprüft wird sie erst, wenn es Vergleiche gibt (E3); die SQL-Ablage der
        Arbeitskopie hat keinen eigenen Test (die Routen sind mit einer Ablage im Speicher getestet)
- [x] 014.9 L5 Models (2026-10-13, mit Grenzen)
      - Prozessor: Operatoren `model.power-curve` (Leistungskurve als Stützstellen, linear dazwischen),
        `feature.lag` und `feature.rolling` in der Kette für Reihen; eine Senke darf die bereinigte Reihe eines
        Signals lesen und in ein anderes Signal schreiben (`signal.metric`, `origin`, `madeOf`), oder Merkmale in
        `tsx.<domain>.features.v2` (Tag `feature`); gemeinsame Testvektoren für Java und TypeScript (14 Fälle)
      - Workbench: "Publish as another signal" für einen Kontext mit bereinigter Reihe; die drei Operatoren im
        Baukasten
      - Modellfreigabe je Zweck (D59): der Server liest die Entscheidungskarten des Systemdokuments
        (`decisions:`) und gibt je Analyse `usable`, `restricted` oder `blocked` mit Gründen aus
        (`GET` und `POST /v1/systems/<id>/decisions`, Schema `opentsx.decision-status.v1`); geprüft werden Stufe,
        Alter gegen die neuesten Daten des Systems, zugelassene Herkunft, Gültigkeitsbereich und Nachweis
      - Ort Models: die Karten mit benötigten Signalen, Analysen und Alternativen; Leiste und Guide zählen mit
      - Ort System: das Systembild als Graph (reactflow) über der Tabelle; Rückführungen gestrichelt
      - Grenzen: der Vergleich von Alternativen über einen Zeitraum ist nicht gebaut (E3); die Analyse
        `price-following` ist eine Karte ohne ausführbares Modell dahinter; der Gültigkeitsbereich wird gegen
        Minimum und Maximum der ganzen Reihe geprüft, nicht je Zeitpunkt; das Alter gegen die neuesten Daten des
        Systems, nicht gegen die Uhr; die Karte `h2-dispatch` ist ein Beispiel mit erfundenen Nachweisen; eine
        Karte hat kein Schema im Build, nur ihr Entwurf
- [ ] 014.10 Neu planen, dann E1 Entscheidungskarte und fachliche Verträge. Neuplanung als Vorschlag in 15,
      Abschnitt 10.3 (2026-10-13): was L4 und L5 von E1 und E2 schon liefern, sieben Schnitte, drei offene Fragen;
      die Entscheidung des Auftraggebers steht aus
      - Entschieden am 2026-10-13 nach einem zweiten Briefing (15, Abschnitt 13; D64 bis D66): der Durchstich
        bleibt, der Systemplan (S1) und das reale Jahr 2025 als Daten (J1) kommen vor E1; der Optimierer folgt
        nach E3 als Analyse
- [x] 014.13 S1 Systemplan `h2-chain` in neuer Fassung (15, Abschnitt 13.4; 2026-10-13, mit Grenzen)
      - Entschieden: Hochdruckpuffer hinter dem Verdichter als Annahme; Physik einfach und benannt; der bisherige
        Plan bleibt als Testfall (`opentsx-twin/src/test/resources/twin/h2-chain-1.yaml`)
      - Zwilling: Bausteine `wind-park` (drei Leistungen, Abregelung je Ursache gezählt), `h2-compressor`,
        `h2-buffer` (mit Druckregler zur Leitung), `h2-pipeline` (Druck am Anfang und am Ende), `h2-handover`
        (Regelventil, Mindestlieferdruck, Fehlmenge), `h2-consumer` (Bedarf als Information); Plan mit 12 Bausteinen
        und 20 Verbindungen; Tests `H2ChainTest` (Ruhezustand, Kausalkette mit analytischer Zeit, Bilanz für Strom
        und Wasserstoff, Verdichterleistung, Was-wäre-wenn, Fahrweise, Abregelung je Ursache)
      - Studio: Szenario, Erklärtexte (`h2-chain.guide.yaml`) und Seite neu erzeugt
      - Systemdokument Fassung 3: 13 Signale, jeder der neun Eingänge von außen hat ein Signal (drei davon offen);
        `park.p_potential_kw` ersetzt `park.p_avail_kw` als Eingang, dazu `park.dispatch_kw`, `handover.valve`,
        `grid.p_grid_meas_kw`; Entscheidungskarte Fassung 2 mit den Grenzen an Puffer und Übergabe
      - Grenzen: ideales Gas ohne Realgasfaktor; Verdichter einstufig ohne Zwischenkühlung und ohne Kennfeld;
        Druckverlust der Leitung als Quadrat des Durchflusses; die Übergabestation liefert unter dem Mindestdruck
        nichts; Wasseraufbereitung und weitere Nebenaggregate fehlen; Übergaben zwischen Bausteinen derselben Phase
        kommen einen Schritt später an (Masse bleibt erhalten); `opentsx-market` rechnet weiter mit seinem eigenen
        Plan in der ersten Fassung (wird mit J1 umgestellt); die verfügbare Leistung nach Auflagen ist ein Ausgang
        des Zwillings und kein Signal mehr
- [ ] 014.14 J1 Das Jahr 2025 als Daten (15, Abschnitt 13.4)
      - 2026-10-13, erster Schritt: DWD-Beobachtungen 2025 der sechs Stationen der Standorte und der Day-Ahead-Preis
        2025 im Archiv außerhalb des Repositorys; Abdeckung über 99,6 Prozent, Lücken ausgewiesen; ein Jahreslauf
        mit dem Zwilling auf Historie läuft (Plan in der ersten Fassung). Stand und fünf offene Punkte in 15,
        Abschnitt 13.5. Entschieden am selben Tag: Die Sprachen der Oberflächen bleiben, wie sie sind (Web-Anwendung
        Englisch, Studio Deutsch)
- [ ] 014.15 Demo der Wasserstoffkette und Simulations-Experten als Agenten: Entwurf in
      [16](../docs/datamodel-2026/16-DEMO-UND-SIMULATIONSEXPERTEN.md) (2026-10-13): Demo in acht Akten, fünf Rollen,
      Werkzeugvertrag, 15 Lücken. Entschieden (D70 bis D73): Demo A zuerst, fünf Rollen als Agenten im Repository
      (`.claude/agents/sim-*.md`, angelegt), nach S1 erst Demo A ausrollen und durchspielen.
      Erster Durchlauf am 2026-10-13 mit neun Befunden (16, Abschnitt 7); die nächste Baustufe ist danach zu
      entscheiden
- [x] 014.16 Demo A zeigbar machen (2026-10-13): Befunde 1, 2, 4, 5, 6, 8 und 9 aus 16, Abschnitt 7
      - Ort System: Bild in den Phasen eines Zeitschritts, Strom, Wasserstoff und Information in drei Farben,
        Annahmen des Systemdokuments (`assumptions`), Arbeitskopie im Bild, Verweis ins Studio
      - Studio unter `/studio/` am Frontend-Image; Szenario mit Auflage (04:00 bis 05:00) und Fahrbefehl
      - gefunden und behoben: ein Signal aus der Arbeitskopie ließ sich nicht in der Workbench öffnen; eine
        erzeugte Reihe (Topic `synthetic`) ließ sich nicht bereinigen
      - Laborsystem vorbereitet: Preis erzeugt, Bedarf bereinigt, Potenzial veröffentlicht, Ereignisse des neuen
        Laufs im Topic. Beide Analysen stehen weiter auf `blocked`, weil Preis und Bedarf aus Demo-Generatoren
        stammen und die Entscheidung nur gemeldete Werte oder Prognosen annimmt (D61)
- [x] 014.17 Lauf-Dienst und Lauf-Register (16, Abschnitt 4, Punkte 1 und 2; 2026-10-13, mit Grenzen)
      - Server: `Runs` (Szenario lesen, Lauf rechnen, behalten, lesen, vergleichen), Routen unter
        `/v1/systems/<id>/scenario` und `/runs`; Tests `RunsTest`
      - Oberfläche: Ort "Simulate" (Frage links, Lauf neben der Referenz rechts, Ereignisse, Herkunft der Zahlen)
      - Grenzen: ein Lauf rechnet mit den Eingängen des Szenarios, nicht mit den Reihen der Signale, und heißt
        deshalb immer `restricted`; das Register liegt im Speicher (hundert Läufe, weg nach Neustart); Fahrweisen
        gibt es nur für den Elektrolyseur; kein Protokolleintrag; die Agenten nutzen den Dienst über die
        Schnittstelle, ein eigener Werkzeugvertrag mit Rechten fehlt
- [ ] 014.11 E2 Durchgängiger Streaming- und Replay-Pfad (darin die zwei Varianten von `clean`)
- [ ] 014.12 E3 Handlungs- und Lernkreis

## Review-Briefing (extern, 2026-10-13)

Ein Review aus Engineering-Sicht und aus Sicht komplexer Systeme, auf Grundlage des Leitfadens, ohne Einsicht in
den Code. Kernsatz: Die zentrale Einheit sollte eine überprüfbare Entscheidung sein. Die Tabelle steht ausführlich
in [15](../docs/datamodel-2026/15-ENTSCHEIDUNGSKREIS.md), Abschnitt 2; hier die Kurzform.

| Befund | Einschätzung | Was daraus wurde |
|---|---|---|
| Der Entscheidungsbezug fehlt; der Einstieg ist das System | stimmt | Entscheidungskarte, neuer erster Schritt (D52, E1) |
| "Clean meets the expectation" kann Ereignisse wegputzen | stimmt, L3 tut das | `clean` neu definiert, Modus `flag`, zwei Wertebereiche (D54, D57, E2) |
| Ein exklusiver Signalstatus | stimmt | fünf Dimensionen, Verwendbarkeit je Entscheidung (D55, E1) |
| "Worst origin" ist keine Rangfolge | stimmt | Ableitungsweg mit Anteilen (D56, E1) |
| Plausible Einzelwerte ergeben kein plausibles System | stimmt | Demo-Generator und Szenariomodell getrennt (D61, E2) |
| Streaming-Semantik offen | stimmt teilweise; am selben Tag durch eine verlorene Episode bestätigt | Vertrag mit "Heute" und "Ziel" (D58, E2); Kennung für Episoden sofort |
| Saubere Eingänge beweisen keine Modellgüte | stimmt | Freigabe je Analyse (D59, E3) |
| Kein geschlossener Handlungskreis | stimmt | vier Datensätze (D60, E3) |
| H2-Beispiel: gemessene Leistung ist nicht die verfügbare | stimmt, fachlicher Fehler | sofort korrigiert |
| "Ein Writer pro Topic" zu grob | schon anders: ein Signal, ein Schreiber | Prüfung gehört in den Server (E2) |
| Beispiel zeigt Flows, die nicht laufen; Zahlen widersprüchlich | stimmt; die Zahl der Eingänge von außen war falsch (neun, nicht sechs) | Leitfaden nennt je Schritt, was läuft |

Nicht nachgeprüft: die beiden Quellenverweise des Briefings (NIST, Apache Beam).

## Stand der Prüfung
* Die Beispieldokumente sind gegen die beiden Schemas geprüft, dazu die Verweise im Systemdokument: Bausteine im
  Schaltplan, Kanäle im Katalog, Assets im Inventar, Flows in der Liste und mit passendem Ausgang, Generatoren mit
  Beschreibung und Pflichtparametern. Ergebnis seit der Korrektur des Beispiels: 10 Signale, 2 offen, 3 Prozessoren, 5 Flows, 1 Entscheidungskarte mit 4
  Datensätzen, kein Fehler.
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
