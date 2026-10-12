# TASK-013: Web-Workbench (Swing-Bucket-Analyser im Browser)

## Task Metadata
- **Task ID**: TASK-013-web-workbench
- **Created**: 2026-10-12
- **Status**: In Progress
- **Priority**: High
- **Epic**: P7e Personal Service Layer / Studio
- **Delivery-Branch**: `opentsx/2026-task013-web-workbench`
- **Delivery-Worktree**: `../OpenTSx-worktrees/web-workbench`

## Ziel
Die Swing-Workbench (`org.opentsx.app.bucketanalyser`: `MacroTrackerFrame`, `TSOperationControlerPanel`,
`MultiChart`, `PFDRecorder`) bekommt eine Web-Variante im React-Frontend (`opentsx-saas-frontend`, Route
`/workbench`). Man lädt einen Bucket, wendet Operationen an, sieht jedes Ergebnis sofort auf einem
Oszilloskop-Schirm und baut dabei den Verarbeitungsgraphen. Der Graph wird als Smart-Topic-Definition
(`opentsx.dag.v1`, siehe [11](../docs/datamodel-2026/11-SMART-TOPIC-PROZESSOR-DEVOPS.md)) übergeben.
Damit ist TASK-005 (Processing Flow Descriptor) im Browser fortgesetzt.

## Analyse der Swing-Workbench

| Swing | Aufgabe | Web |
|-------|---------|-----|
| `MacroTrackerFrame` (JTree, Tabs Properties / Code / Preview / PFD) | Track der Transformationen, Code-Export | Track-Baum links, Inspektor rechts (Properties, Code, Flow (JSON), DAG (YAML), Smart Topic) |
| `Track`, `SourceTSB`, `Transform`, `TSBucketTransformation` | Knoten: Quelle und abgeleitete Buckets, Name = Präfix + Quellname | `workbench/flow.ts` (`FlowNode`, gleiche Namensregel) |
| `TSOperationControlerPanel` (Tabs mit Knöpfen, Pastellfarben je Tab) | Operation auf alle Reihen des Buckets, öffnet neues Chart, meldet die Transformation an den Track | Operationsleiste unter dem Schirm, gleiche Tabs, Farben und Knopftexte |
| `MultiChart` (JFreeChart-Dialog je Ergebnis) | Anzeige | ein Oszilloskop-Schirm für den gewählten Knoten |
| `PFDRecorder` | lineare Schrittliste als JSON | Reiter Flow (JSON) für den Pfad bis zum gewählten Knoten |
| `MacroRecorder` (Sinus-Mix, Track EXP2) | Beispieldaten | Knopf "Load sample bucket" (gleiche Parameter, Rauschen mit festem Startwert) |

Beobachtungen am Swing-Code:
* Jede Operation erzeugt ein neues Fenster. Der Zusammenhang steht nur im Baum.
* Der Track ist ein Baum: jeder abgeleitete Bucket hat genau eine Quelle.
* Die Knöpfe im Reiter "TS-Model" (AR, MA, ARMA, ARIMA, SARIMA, CARIMA) haben keinen Handler, der Reiter
  "Multi-variate Analysis" ist leer.
* Der Java-Export schreibt `parameter` immer als `null`; die Parameter stehen nur im Operationsnamen.

## Umgesetzt (Stand 2026-10-12)

* **Bucket laden:** Beispiel-Bucket, Datei (CSV/TSV breit: Zeitspalte plus Reihen; lang: `series_id,x,y`;
  Episoden-JSON in der Form von `GET /v1/episodes`), Episoden vom Retrieval-Server über den Dev-Proxy
  `/retrieval`. Datei auch per Ablegen auf dem Schirm.
* **Operationen** (Port der Java-Methoden, mit Tests): Shuffle, NORM max=1, NORM std=1 avg=0, Log, Returns,
  Diffs, Profile, FFT, Histogram per series, Histogram cross-cut, CDF, ENTROPY, Auto-Correlation, DFA, Sigma
  Band. Dazu Filter und Konvertierung für den Strom: Select series, Plausible range, Convert unit, Resample,
  Moving average, Window statistics, Segment at threshold.
* **Oszilloskop:** Raster 10 x 8, Zeitbasis und Vertikalskala in 1-2-5-Schritten, Position, Kanäle
  ein/aus, eine Skala für alle oder je Kanal, Cursor (dX, 1/dX, dY, dY/dX), Trigger mit Pegel und Flanke,
  Sprung zum nächsten Ereignis, Messwerte im sichtbaren Fenster (Pk-Pk, Max, Min, Mean, RMS, Freq), Run/Stop
  als Durchlauf durch die Aufzeichnung, Auto set. Mausrad, Ziehen, Doppelklick und Tastatur.
* **Trigger wird Segmentierer:** "Segment at this trigger" legt einen Knoten `segment.threshold` mit Pegel
  und Flanke des Triggers an.
* **Graph:** React Flow, Knoten in den Tab-Farben. Eine Verbindung auf einen Knoten ziehen ändert dessen
  Eingang (Zyklen werden abgelehnt). Parameter eines Knotens sind nachträglich änderbar, alles dahinter
  wird neu gerechnet.
* **Smart Topic:** je Knoten "Publish as Smart Topic" mit Topic-Namen
  (`tsx.<domain>.smart.<name>.metrics.v2`). Export als `opentsx.dag.v1` (nur die veröffentlichten Zweige),
  Prüfung (kein Sink, doppeltes Topic, Operator nicht im Stream-Katalog), dazu PFD-JSON und Java-Code.

## Grenzen (bewusst, bitte nicht überlesen)

* **Kein Deployment aus der Oberfläche.** Für `opentsx.dag.v1` gibt es im Repository keine Laufzeit: der
  Go-Prozessor aus Dokument 11 ist ein Vorschlag. Die Workbench übergibt deshalb eine Datei bzw. speichert
  den Flow im Konto und behauptet kein Ausrollen. `POST /flows/{id}/execute` reicht die Definition weiter
  an Flink (`DynamicTopologyJob`) und antwortet ohne Flink-Cluster mit 502; ein Workbench-Flow ist kein PFD.
* **Betrieb nur im vertrauten Netz.** Der NodePort spricht HTTP ohne TLS; Anmeldedaten und Tokens gehen im
  Klartext über das Netz. Es gibt keinen Token-Refresh im Frontend: nach 30 Minuten neu anmelden. Die
  Workbench bleibt dabei stehen und verliert den Flow nicht.
* **Backend-Reste:** Die Routen für Organisationen, Teams, Einladungen und Abrechnung in `app/main.py` sind
  weiterhin Platzhalter. Die Plangrenze `max_flows` wird nicht geprüft. Es gibt keine Backend-Tests in der
  CI; geprüft wird mit `opentsx-saas-backend/smoke-flows.sh` gegen eine laufende Instanz.
* **Katalog:** Nur `resample`, `dq.checks` (Plausible range), `stats.welford-ddsketch` (Window statistics)
  und `segment.threshold` stehen im Stream-Katalog (11, Abschnitt 6). Alle Analyse-Operationen (FFT, DFA,
  ...) laufen nur in der Workbench; die Prüfung meldet das am Sink.
* **Ein Eingang je Knoten.** Knoten mit mehreren Eingängen (`score.onnx` mit `in: [ep, stats]`) fehlen.
* **Nicht portiert:** MF-DFA, Test auf Normalverteilung (Shapiro-Wilk), `TSBucket`-SequenceFiles
  (`.tsb.vec.seq`) als Eingabe.
* **Abweichungen vom Java-Code:** Shuffle mit festem Startwert statt `Collections.shuffle`; Entropie mit
  eigener Klasseneinteilung (16 Klassen über den Wertebereich); DFA-Exponent als Steigung über alle Skalen
  statt über `fitMIN`/`fitMAX`; Autokorrelation bis höchstens 1000 Lags.
* Die Route `/workbench` ist ohne Anmeldung erreichbar, weil alles im Browser rechnet. Speichern braucht ein Konto.

## Sub-Tasks

- [x] 013.1 Analyse der Swing-Workbench
- [x] 013.2 Operator-Bibliothek, Loader, Flow-Modell, Exporte, mit Tests (`src/workbench/workbench.test.ts`)
- [x] 013.3 Oszilloskop, Track-Baum, Operationsleiste, Graph, Inspektor
- [x] 013.3a Auslieferung: Images `saas-frontend` und `saas-backend` (`deploy/docker/`), k3s-Komponente `saas`
      (`SAAS=true`: Frontend auf NodePort 30311, Backend, PostgreSQL mit PVC, Secret `opentsx-saas-secrets`)
- [x] 013.4 Backend: Flows je Benutzer in der Datenbank (`app/api/flows.py`: anlegen, lesen, ändern, löschen,
      Ausführungen), Workbench speichert (`Save flow`) und öffnet (`/workbench?flow=<id>`, Dashboard) die
      Definition `opentsx.workbench.v1` (Knotenliste plus gerendertes `opentsx.dag.v1`)
- [ ] 013.5 Laufzeit für `opentsx.dag.v1` (11, D25 bis D27) oder Brücke zum `StatsProcessor`
- [ ] 013.6 Knoten mit mehreren Eingängen, Kontext-Topics, `score.onnx`, `train.trigger`
- [ ] 013.7 MF-DFA und Normalverteilungstest portieren; Paritätsvektoren Java gegen TypeScript

## Prüfung

```bash
cd opentsx-saas-frontend
npm ci
npx tsc --noEmit && npx vitest run && npm run build
npm run dev          # http://localhost:3000/workbench
```

## Notes
* 2026-10-12: Erste Fassung. Im Browser geprüft (Headless Chrome): Bucket laden, Operationen, FFT-Spitzen
  des Beispiel-Buckets bei 2, 6, 12 und 24 Hz, Trigger, Cursor, DAG-Export, keine Konsolenfehler.
* 2026-10-12: Auf master2 ausgerollt (Namespace `opentsx`, Deployment und NodePort-Service
  `opentsx-saas-frontend`, Image `opentsx/saas-frontend:3.0.0-task013`, gebaut aus dem Delivery-Worktree vor
  dem Merge). Nur das Frontend; das SaaS-Backend läuft dort nicht. Geprüft: Seite lädt, "Load episodes" holt
  Episoden über `/retrieval/` vom Retrieval-Server im Cluster, DFA darauf rechnet, keine Konsolenfehler.
  Rückbau: `kubectl -n opentsx delete deploy,svc opentsx-saas-frontend`. Nach dem Merge den regulären Weg
  nehmen (`20-configure.sh SAAS_FRONTEND=true`, Tag `3.0.0-local`).
* 2026-10-12, Backend: Beim ersten echten Lauf gegen PostgreSQL drei alte Fehler gefunden und behoben.
  (1) Registrierung brach mit `MissingGreenlet` ab (`user.organizations.append` im Async-Kontext).
  (2) Jeder Aufruf mit Token gab 401, weil das Token-Subjekt eine Zahl war und python-jose beim Lesen eine
  Zeichenkette verlangt. (3) Ein Refresh-Token wurde als Access-Token angenommen. Dazu: Organisations-Slug
  jetzt eindeutig (zwei Adressen mit gleichem Teil vor dem @ kollidierten).
* 2026-10-12, Rollout auf master2 mit Backend und Datenbank (Images `3.0.0-task013-3`, aus dem
  Delivery-Worktree, Stand dieses Commits). Geprüft: `smoke-flows.sh http://<master2>:30311` (26 Prüfungen
  grün, darunter Fremdzugriff auf Flows abgewiesen) und eine Browser-Sitzung: registrieren, Flow bauen,
  Smart Topic markieren, speichern, im Dashboard öffnen, nach Neuladen wiederherstellen, auf neuem Bucket
  rechnen; keine Konsolenfehler. Die Prüfung hat Testkonten hinterlassen (`smoke-...@example.com`,
  `e2e-...@example.com`). Rückbau (löscht auch die Datenbank):
  `kubectl -n opentsx delete deploy,svc opentsx-saas-frontend opentsx-saas-backend opentsx-saas-db`, danach
  `kubectl -n opentsx delete pvc opentsx-saas-db` und `kubectl -n opentsx delete secret opentsx-saas-secrets`.
  Der frühere Eintrag zum Rollout nur des Frontends ist damit überholt.

