# TASK-013: Web-Workbench (Swing-Bucket-Analyser im Browser)

## Task Metadata
- **Task ID**: TASK-013-web-workbench
- **Created**: 2026-10-12
- **Status**: In Progress
- **Priority**: High
- **Epic**: P7e Personal Service Layer / Studio
- **Delivery-Branch**: `opentsx/2026-task013-processor-resilience` (013.17), Worktree
  `../OpenTSx-worktrees/processor-resilience`
- **Frühere Delivery-Branches** (gemergt): `opentsx/2026-task013-web-workbench` (013.1 bis 013.4, PR #161),
  `opentsx/2026-task013-ts-inventory` (013.8, PR #165), `opentsx/2026-task013-vector-sink` (013.10, PR #166),
  `opentsx/2026-task013-vector-runtime` (013.11, PR #168), `opentsx/2026-task013-secure-dag-runs` (013.12, PR #172),
  `opentsx/2026-task013-ground-truth` (013.13, PR #173), `opentsx/2026-task013-broker-patience` (013.14, PR #175),
  `opentsx/2026-task013-vector-ops` (013.15 und 013.16, PR #177).
  `opentsx/2026-task013-vector-processor` (013.15, PR #176) wurde in seinen Basis-Branch statt nach `master`
  gemergt; sein Inhalt kommt mit PR #177.

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
- [x] 013.8 TS-Inventar: Reihen der Smart Topics nach Namen auswählen (Stand 2026-10-13)
      - Retrieval-Server liest `tsx.<domain>.episodes.v2` von KafScale (`--kafscale`, Klasse `EpisodeLogTail` in
        `opentsx-kafka-v2`, `KafScaleFeed` in `opentsx-retrieval`) und folgt dem Topic
      - `GET /v1/inventory` (`opentsx.ts-inventory.v1`): je Reihe Umfang, Wertebereich, Labels der Episoden
        (Szenario, Seed, Katalog, IEC-Bezug) und Ground Truth (`gt.operation`, `gt.sensor_fault`) als Zeitbereiche;
        dazu Szenariodatei und Kanal-Katalog, wenn der Server sie bekommt (`--scenario`, `--catalog`)
      - Workbench: Dialog "TS Inventory" (`src/workbench/InventoryDialog.tsx`, Logik `inventory.ts`): filtern nach
        Tag, Kanal, Szenario-Ereignis und Text; Zeitfenster und Auflösung wählen; Auswahl wird der Bucket, Domäne
        und Quell-Topic des Flows folgen dem Inventar
      - k3s: `RETRIEVAL_SOURCE=kafscale` (Komponente `retrieval-kafscale`)
- [x] 013.10 Vektor-Messpunkte (Stand 2026-10-13): Operator `align.vector` mit mehreren Eingängen und Nachricht
      `opentsx.vector.v1` (Entwurf in 11 §5a, JSON-Schema unter `docs/datamodel-2026/schemas/`)
      - Flussmodell: ein Knoten kann weitere Eingänge haben (`FlowNode.inputs`); Auswertung, Zyklenprüfung,
        Speichern und Exporte folgen allen Eingängen
      - Workbench: "Align to vector" im Reiter "Filter and conversion"; Eingänge im Reiter Properties oder per
        Verbindung im Graphen (gestrichelt); Vorschau der ersten Messpunkte als JSON; Sink-Topic
        `tsx.<domain>.smart.<name>.vectors.v1`
- [x] 013.11 Vektor-Topics schreiben und zurücklesen (Stand 2026-10-13)
      - Java-Operator `VectorAligner` (`opentsx-retrieval`), dieselben Testvektoren wie die Workbench
        (`docs/datamodel-2026/testvectors/align-vector.v1.json`)
      - Retrieval-Server: `POST /v1/dags/run` (nur mit `--kafscale` und `--dag-runs true`) rechnet die Vektor-Sinks
        eines DAG einmal über die gehaltenen Episoden und schreibt `tsx.<domain>.smart.<name>.vectors.v1`
      - Der Server liest die Vektor-Topics der Domäne; ihre Komponenten stehen als Reihen
        `smart.<name>.<komponente>` mit Tag `vector` im TS-Inventar
      - Workbench: der DAG nennt die geladenen Reihen (`source.series`); Reiter "Smart Topic" hat "Run once and
        write to KafScale" mit Ergebnis oder Ablehnungsgrund
      - k3s: `RETRIEVAL_DAG_RUNS=true`
- [x] 013.12 Schreibweg absichern und Tests in die CI (Stand 2026-10-13)
      - Retrieval-Server: Läufe nur mit Lauf-Token (`OPENTSX_DAG_RUN_TOKEN`, Header `X-OpenTSx-Run-Token`)
      - SaaS-Backend: `POST /api/v1/dags/run` prüft das Konto und reicht den DAG mit dem Token weiter;
        `DAG_RUN_ROLE` legt fest, wer schreiben darf (Standard: nur Administratoren)
      - Frontend-Image: `/retrieval/v1/dags/` ist gesperrt; die Workbench ruft das Backend
      - Ein Lauf schlägt fehl, wenn der Broker nicht jeden Messpunkt bestätigt (vorher blieb das unbemerkt); ein
        nicht lesbares Vektor-Topic hält die übrigen Topics nicht mehr auf
      - CI: Jobs "SaaS frontend" (Typen, Lint, Tests, Build) und "SaaS backend" (pytest)
- [x] 013.14 Ausdauer im Broker-Fenster (Stand 2026-10-13): KafScale weist nach einem langsamen Schreibzugriff etwa
      eine Minute lang jedes Lesen und Schreiben ab. Leser warten das jetzt ab (`org.opentsx.kafka.Patience`, in
      `LogReader`, `StatsProcessor`, `SeriesStatsLog`), und alle Schreiber wiederholen bis zu drei Minuten
      (`KafScaleClients.producerProps`). Vorher endete der Stats-Prozessor daran (15 Neustarts in 17 Stunden auf
      master2).
- [x] 013.13 Ground Truth auf dem Oszilloskop (Stand 2026-10-13)
      - Die Szenario-Ereignisse der geladenen Reihen wandern als Marken mit dem Bucket (`Bucket.marks`); Operatoren,
        die die Zeitachse behalten, reichen sie weiter, Spektren und Histogramme haben keine
      - Der Schirm zeichnet sie als Bänder (Betriebsereignis gelb, Sensorfehler rot) mit Namen am unteren Rand;
        Feld "Ground truth" schaltet sie und springt zu einem Ereignis
      - "Segment at threshold" zählt, wie viele Schwellenübergänge in ein Ereignis fallen
- [x] 013.15 Vektor-Prozessor: fortlaufender Betrieb (Stand 2026-10-13)
      - `VectorStream`: Messpunkte, sobald sie endgültig sind; dasselbe Ergebnis wie der Einmal-Lauf; Zustand für
        den Neustart
      - `VectorProcessor`: Deployments aus dem Steuer-Topic `tsx.<domain>.dags.v1`, je Sink ein Läufer mit
        Checkpoint, Status nach `tsx.<domain>.dag-status.v1`; Kommando `opentsx vectors` (run, deploy, stop)
      - Regeln in 11 §5b, Entscheidung D39
- [x] 013.16 Vektor-Prozessor ausliefern und bedienen (Stand 2026-10-13)
      - k3s-Komponente `vector-processor` (`VECTOR_PROCESSOR=true`): ein Pod `opentsx-vectors`, Zustand auf einem
        Volume
      - Retrieval-Server: `POST /v1/dags/deploy`, `/stop` schreiben ins Steuer-Topic; `GET /v1/dags` zeigt, was
        verlangt ist und was der Prozessor meldet. Der Einmal-Lauf (`/v1/dags/run`) ist entfallen
      - Backend: `POST /api/v1/dags/deploy`, `/stop` (nur Administratoren)
      - Workbench: "Keep running on KafScale" im Reiter "Smart Topic" mit Deploy, Stop und Zustand je Sink;
        Parameter `lateness` am Vektor-Knoten
- [x] 013.17 Verknüpfung Workbench und Prozessor robust und sichtbar (Stand 2026-10-13)
      - Prozessor: ein Producer je Läufer und einer für den Status; das Warten auf die Bestätigung blockiert die
        Schleife nicht; ein Producer ohne Antwort wird verworfen und ersetzt. Vorher hielt das erste Schreiben in
        ein neues Sink-Topic den ganzen Prozessor an (Fehler im KafScale-Proxy, KOI-2026-10-13-02)
      - Workbench: Liste "was ein Deployment braucht" mit bereit oder fehlt je Schritt, und ein Knopf, der aus den
        geladenen Reihen einen veröffentlichten Vektor macht
- [x] 013.18 Testbetrieb für die Anmeldung (Stand 2026-10-13): `SAAS_TEST_LOGIN=true` lässt `admin` mit dem Passwort
      `admin` als ersten Administrator hinein; die Login-Seite zeigt es an. Das gespeicherte Passwort bleibt
      unverändert. Voreinstellung aus; eingeschaltet ist jeder, der die Seite erreicht, Administrator
- [x] 013.19 "OpenTSx Flow Composer": gespeicherte Flows auswählen und verwalten (Stand 2026-10-13)
      - Auswahlfenster nach dem Muster des TS-Inventars (`FlowComposer.tsx`, `flows.ts`): Filter nach Text,
        Domain, Status und Art, Vorschau mit Quelle, Buckets, Smart Topics und Zustand im Prozessor
      - Öffnen im Editor (der Workbench), auch mehrere: der erste Flow hier, die weiteren in eigenen Tabs. Ein
        geöffneter Flow lädt die Reihen, auf denen er gebaut wurde, über ihren ganzen Zeitraum; das Zeitfenster der
        ursprünglichen Auswahl ist nicht gespeichert
      - Anlegen, Umbenennen (Name, Beschreibung), Kopieren, Löschen (mit Rückfrage im Fenster)
      - Aktivieren und Pausieren: setzt den Status des Flows. Kann der Vektor-Prozessor den Flow ausführen, wird
        er dabei bereitgestellt beziehungsweise gestoppt (nur Administratoren); sonst wird er nur markiert, und
        das Fenster sagt, warum
      - Grenze: Der Name der Bereitstellung kommt aus Domain und Track. Zwei Flows mit demselben Track teilen sich
        eine Bereitstellung; die Kopie eines aktiven Flows ersetzt beim Aktivieren die des Originals
- [ ] 013.5 Laufzeit für `opentsx.dag.v1` über Vektoren hinaus (11, D25 bis D27): Operatoren vor dem Vektor
      (`resample`, `dq.checks`), weitere Sink-Arten
- [ ] 013.6 Kontext-Topics (Eingänge aus anderen Topics), `score.onnx`, `train.trigger`; Knoten mit mehreren
      Eingängen gibt es seit 013.10
- [ ] 013.7 MF-DFA und Normalverteilungstest portieren; Paritätsvektoren Java gegen TypeScript
- [ ] 013.9 Ergebnis eines einzelnen Knotens als Episoden in ein
      Smart Topic schreiben (`tsx.<domain>.smart.<name>.metrics.v2`); Asset-Register `opentsx.inventory.v1` (12) über
      `series` an das TS-Inventar binden. Für Vektoren ist die Schleife seit 013.11 geschlossen.

## Grenzen des TS-Inventars

* **Nur lesen.** Die Workbench holt Reihen aus dem Topic. Sie schreibt nichts nach KafScale; "als Smart Topic
  anwenden" bleibt Export und Speichern, bis 013.5 und 013.9 umgesetzt sind.
* **Das Inventar ist abgeleitet.** Das Topic `tsx.<domain>.series.v2` ist im Layout vorgesehen, wird aber von keinem
  Schreiber gefüllt. Das Inventar entsteht deshalb aus den Episoden selbst.
* **Im Speicher.** Der Server hält die Episoden im Heap, höchstens `--max-episodes` (Standard 20000). Bei mehr
  meldet das Inventar `truncated`. Für größere Bestände ist das Lake-Profil (Iceberg) gedacht.
* **Ground Truth in Episodenschritten.** Die Marken kommen aus den Labels der Episoden, also im Raster der
  Episodendauer (im Demo-Szenario 10 Minuten). Ein Ereignis von 15 Minuten erscheint als 20 Minuten.
* **Kein Zugriffsschutz.** `/retrieval/` ist ohne Anmeldung erreichbar, wie bisher.

## Grenzen der Vektor-Messpunkte

* **Ein Prozessor, ein Neustart je Änderung.** Der Vektor-Prozessor hält einen Sink dauerhaft aktuell. Ein
  geänderter DAG beginnt von vorn und hängt alles erneut an; beim Lesen gilt der spätere Messpunkt. Es darf nur ein
  Prozessor je Domäne laufen, und nichts verhindert einen zweiten.
* **Eine verstummte Reihe hält ihren Vektor an.** Ein Tick wird erst geschrieben, wenn jede genannte Reihe ihn
  erreicht hat. Der Zustand zeigt, worauf gewartet wird; einen Leerlauf-Timeout gibt es nicht.
* **Nur Vektoren direkt auf der Quelle.** Steht ein anderer Operator vor dem Vektor (Normierung, Glättung,
  Resampling), wird der DAG abgelehnt und der Knoten genannt. Solche Flüsse rechnet weiterhin nur die Workbench.
* **Deployen nur mit Konto, Lesen ohne.** Seit 013.12 läuft das Schreiben über das Backend und ist auf
  Administratoren beschränkt (`SAAS_DAG_RUN_ROLE=superuser`). Mit `user` darf jedes Konto schreiben; weil sich
  jeder registrieren kann, ist das praktisch offen. Das Lesen über `/retrieval/` braucht weiterhin kein Konto.
  Das Lauf-Token schützt nur, solange der Retrieval-Server nicht auf anderem Weg ohne Token erreichbar gemacht wird
  (im Cluster ist sein Port für alle Pods offen; eine NetworkPolicy gibt es nicht).
* **Kein Alles-oder-nichts.** Bestätigt der Broker nur einen Teil der Messpunkte, schlägt der Lauf fehl, und der
  bestätigte Teil bleibt im Topic. Ein neuer Lauf schreibt alles erneut.
* **Ein Quell-Bucket.** Alle Eingänge stammen aus dem einen geladenen Bucket. Vektoren über mehrere Topics sind
  nicht abgedeckt.
* **Nur Zeitachsen.** FFT, Histogramm, DFA und ähnliche Knoten sind keine Komponenten; der Operator lehnt sie ab.
* **Format ist JSON.** Eine Avro-Fassung und ein Qualitätsbyte je Komponente sind offen (11 §11).

## Prüfung

```bash
cd opentsx-saas-frontend
npm ci
npx tsc --noEmit && npx vitest run && npm run build
npm run dev          # http://localhost:3000/workbench
```

TS-Inventar gegen einen Broker (liest nur, ohne Consumer-Gruppe):

```bash
mvn -q -pl opentsx-kafka-v2,opentsx-retrieval -am test
bin/opentsx retrieval-server --port 8080 --kafscale <host:port> --domain wind \
    --scenario opentsx-windpark/scenarios/ostsee-demo.yaml \
    --catalog opentsx-windpark/src/main/resources/windpark/channel-catalog.yaml
curl -s localhost:8080/v1/inventory | head -c 400
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
* 2026-10-13: TS-Inventar (013.8). Gegen KafScale auf master2 geprüft: 100 Reihen, 3600 Episoden, 2.160.000
  Messwerte aus `tsx.wind.episodes.v2`; die Marken decken sich mit `ostsee-demo.yaml` (zum Beispiel STUCK am
  Windmesser von T05, 01:30 bis 01:50). Im Browser: Ereignis wählen, Reihe laden, die hängende Messung steht als
  flache Linie bei 4,16 m/s auf dem Schirm; zehn Leistungsreihen mit PT1M geladen; keine Konsolenfehler.
  Auf master2 ausgerollt: `opentsx-retrieval` liest jetzt das Topic (Image `opentsx/tools:3.0.0-task013-4`,
  vorher Demo-Store), Frontend `3.0.0-task013-4`. Stand vor der Änderung auf dem Host unter
  `~/opentsx-builds/task013/retrieval-before-task013-4.yaml`.
* 2026-10-13: Vektor-Messpunkte (013.10). Im Browser mit den Daten von master2 geprüft: Windgeschwindigkeit und
  Leistung von T05 geladen, standardisiert, mit `key: turbine` und 60 s Takt zu einem Vektor aus vier Komponenten
  ausgerichtet: 360 Messpunkte, keine fehlenden Werte, Vorschau und DAG-Export stimmen, keine Konsolenfehler.
* 2026-10-13: Schleife für Vektoren geschlossen (013.11). Auf master2 im Browser: vier Kanäle aller zehn Turbinen
  aus dem Inventar geladen, mit `key: turbine` und 60 s Takt ausgerichtet, als
  `tsx.wind.smart.turbine-state.vectors.v1` veröffentlicht und geschrieben: 3600 Messpunkte, 10 Schlüssel, 15
  fehlende Werte (die Lücke von T01). Das Topic erscheint im Inventar, die vier Komponenten von T05 lassen sich
  laden. Der Endoffset des Topics ist 7200, weil der Lauf zweimal lief (der zweite nach der Korrektur der
  Komponentenreihenfolge). Retrieval-Server `opentsx/tools:3.0.0-task013-8` mit `--dag-runs true`, Frontend
  `3.0.0-task013-9`.
* 2026-10-13: Absicherung (013.12) auf master2 ausgerollt und geprüft: anonym und mit normalem Konto wird der Lauf
  abgewiesen, der direkte Weg über `/retrieval/v1/dags/` antwortet mit 403. Beim ersten Lauf als Administrator
  (08:46 UTC) wies der Broker Schreiben und Lesen eine gute Minute lang mit REQUEST_TIMED_OUT ab: KafScale stuft
  seinen Objektspeicher nach einem einzelnen langsamen Schreibzugriff für 60 Sekunden als "degraded" ein und lehnt
  dann alles ab (Befund KOI-2026-10-13-01 in den KafScale-Ops-Insights). Von 3600 Messpunkten kamen 280 an; die
  Partition ist intakt. Daraus: Der Lauf wiederholt Schreibversuche jetzt bis zu drei Minuten mit Pausen und
  schlägt fehl, wenn nicht alles bestätigt ist.
* 2026-10-13: Ground Truth auf dem Schirm (013.13). Im Browser mit den Daten von master2: Windmesser von T05 und
  T07 über sechs Stunden geladen, die Bänder CURTAILMENT (01:00 bis 01:40) und STUCK T05 (01:30 bis 01:50) stehen
  an der richtigen Stelle; nach dem Sprung auf STUCK liegt die flache Strecke von T05 im roten Band, T07 läuft
  weiter. Ein abgeleiteter Bucket (Diffs) behält die Marken, die FFT hat keine. Keine Konsolenfehler.
  Die Marken sind so fein wie die Episoden lang sind (10 Minuten), also Näherungen des Ereignisses.
* 2026-10-13: Vektor-Prozessor (013.15) lokal gegen den Labor-Broker gelaufen: DAG `wind-exp2-smart` über das
  Steuer-Topic deployt, der Läufer schrieb 3600 Messpunkte nach `tsx.wind.smart.turbine-state.vectors.v1`
  (Endoffset 11080 auf 14680), legte seinen Checkpoint am Ende aller sechs Partitionen ab und meldete Status.
  Danach beendet und der DAG per Steuer-Topic gestoppt: Auf dem Broker läuft kein Prozessor, bis 013.16 ihn
  ausliefert. Neue Episoden kamen während des Laufs nicht dazu; das Nachführen ist durch die Tests belegt
  (`VectorProcessorTest`), nicht durch diesen Lauf.
* 2026-10-13: Vektor-Prozessor auf master2 ausgeliefert (013.16): Pod `opentsx-vectors` mit Volume, Images
  `3.0.0-task013-17`. Als Administrator über das Backend deployt: Der Eintrag ging ins Steuer-Topic, der Prozessor
  übernahm ihn nach wenigen Sekunden und schrieb 3600 Messpunkte (Endoffset 14680 auf 18280), `GET /v1/dags` zeigt
  "running, 3600, Daten bis 06:00". Nach einem Neustart des Pods blieb der Endoffset bei 18280: Er setzt am
  Checkpoint fort. Ein normales Konto sieht den Zustand, Deploy und Stop sind gesperrt. Der DAG bleibt deployt.
  Neue Episoden kamen nicht dazu; das Nachführen ist weiterhin nur durch Tests belegt.
* 2026-10-13: Meldung des Nutzers: "Verknüpfung zwischen Workbench und DAG-Flow-Runner klappt nicht." Zwei Befunde.
  Erstens blieb der Deploy-Knopf für naheliegende Flüsse gesperrt (Beispiel-Bucket, veröffentlichter Operator statt
  Vektor), und die Seite nannte nur das erste Hindernis. Zweitens ein echter Fehler: Das erste Deployment in ein
  neues Sink-Topic über die Oberfläche hielt den Prozessor an. Er schrieb 20 Messpunkte, bekam vom KafScale-Proxy
  eine Antwort, die der Client nicht lesen kann, und sendete danach nichts mehr, auch keinen Status; ein Neustart
  des Pods löste es. Ursache gemessen und nachgestellt (KOI-2026-10-13-02): gemischte Produce-Anfrage aus Topic mit
  und ohne Besitzer. Nach der Korrektur (013.17, Images `3.0.0-task013-19`) über die Oberfläche als Administrator
  geprüft: Reihen laden, "Create a vector of the loaded series", Deploy in ein neues Topic; nach 15 s "running",
  360 Messpunkte im Topic, kein Fehler im Prozessor-Log.
