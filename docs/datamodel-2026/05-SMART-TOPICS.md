# OpenTSx – Smart Topics (Entwurf)

> Status: **Entwurf, Entscheidungen getroffen (2026-10-12, §9)**. Baut auf P2 (Episoden-Log auf KafScale), P4
> (`opentsx-stats`, `opentsx-openmetadata`), P5 (Segmentierung, Retrieval) und P6 (Resampling/Alignment) auf.
> Umsetzung als **P7** nach den Analytik-Lücken.

## 1. Idee

Ein **Smart Topic** ist ein *abgeleitetes Topic plus der Prozessor, der es füllt*:

* In ein Smart Topic laufen als Streams
  * die **Rohdaten** einer Auswahl von Serien (Episoden/Observations),
  * deren **Statistik** (`series-stats.v2`),
  * optional **Kontextdaten** aus beliebigen anderen Topics (Wetterprognose, Sollwerte des Netzbetreibers,
    Nachbarturbinen, Wartungsereignisse, …).
* Eine **deklarative Spezifikation** legt fest,
  * welche Features daraus entstehen,
  * welche Trainingsroutine läuft und wann,
  * welche Vorhersagemodelle (eins oder mehrere) trainiert und verglichen werden.
* Der Prozessor liefert laufend **Smart Metrics**: Prognosen mit Intervall, Residuen, Anomalie-Scores, Modell-Drift,
  Feature-Gesundheit.
* Über eine **Prediction-API** kann man mit dem gelernten Modell jederzeit vorhersagen:
  * *mit* Kontextdaten (mitgegeben oder aus dem Stream),
  * oder *ohne* Kontextdaten (dafür gibt es eine kontextfreie Modellvariante).

```
 tsx.wind.episodes.v2 ──┐
 tsx.wind.series-stats.v2 ─┼──► Smart-Topic-Prozessor ──► tsx.wind.smart.<name>.metrics.v2   (Smart Metrics)
 weather.forecast.v1 ───┘   │  Features · Training · Scoring   └► tsx.wind.smart.<name>.models.v2    (Modell-Registry)
 (Kontext, deklariert)       │
                             └──► Prediction-API: predict(series, horizon[, context])
```

## 2. Begriffe

| Begriff | Bedeutung |
|---------|-----------|
| **Target** | Die zu prognostizierende(n) Serie(n), z.B. `wind.turbine.power_active{park=ostsee-1,turbine=*}`; eine Spezifikation gilt für alle passenden Serien (ein Modell je Serie oder ein globales Modell, §5) |
| **Kontext** | Zusätzliche Eingangsserien/Topics mit Join-Regel (gleiche Turbine, gleicher Park, as-of nach Zeit) |
| **Feature-Pipeline** | Deterministische Ableitung: Resampling → Lags → Rolling-Fenster → Stats-Felder → Kalender → Kontext-Joins |
| **Trainingsroutine** | Wann (Zeitplan, Datenmenge, Drift), auf welchem Fenster, mit welchem Split, welche Modellkandidaten, welche Auswahlregel |
| **Modell** | Trainierter Kandidat mit Version, Trainingsfenster, Feature-Schema-Hash, Gütemaßen; Champion je Target+Horizont |
| **Smart Metric** | Laufend emittierter abgeleiteter Wert (Prognose, Intervall, Residuum, Score, Drift) |

## 3. Spezifikation (deklarativ, versioniert)

YAML, versioniert wie der Datenpunktkatalog. Der Hash der Spezifikation geht in jede Smart Metric und jedes Modell
ein (Lineage, Reproduzierbarkeit).

```yaml
smartTopic: ostsee-power-forecast
version: 1.0.0
domain: wind
description: Leistungsprognose je Turbine, 10 min bis 6 h, mit und ohne Wetterprognose

target:
  metric: wind.turbine.power_active
  tags: { park: ostsee-1 }               # alle Turbinen des Parks
  source: episodes                       # episodes | observations
  resample: { every: PT10M, agg: MEAN }  # Basistakt der Modelle

stats:                                   # aus tsx.wind.series-stats.v2 (opentsx-stats)
  window: PT10M
  fields: [std, q05, q95, nanShare, gapCount]

context:
  - name: wind_speed
    metric: wind.met.wind_speed
    match: { turbine: same }             # gleiche Turbine
    resample: { every: PT10M, agg: MEAN }
  - name: forecast_ws
    topic: tsx.weather.forecast.v1       # beliebiges anderes Topic (Avro/JSON), Feldpfad + Zeitfeld
    key: { park: same }
    value: $.windSpeed100m
    time: $.validTime
    join: as-of                          # letzter bekannter Wert ≤ Zielzeit
    maxAge: PT3H
    optional: true                       # Modell muss auch ohne funktionieren

features:
  lags: [1, 2, 3, 6, 12, 144]            # in Basistakten
  rolling: [ { window: PT1H, fn: [mean, std] }, { window: PT6H, fn: [mean] } ]
  calendar: [hourOfDay, dayOfYear]       # zyklisch kodiert (sin/cos)
  derived:
    - name: ws_cubed                     # Physik-Prior: P ~ v^3
      expr: "pow(wind_speed, 3)"

training:
  schedule: { every: P1D, at: "02:00Z" } # und/oder:
  retrainOn: { driftScore: "> 3.0", minNewSamples: 1000 }
  window: { sliding: P60D }
  split: { holdout: P7D, gap: PT6H }     # zeitlich, kein Leakage
  horizons: [PT10M, PT1H, PT6H]
  models:
    - { name: persistence,    type: PERSISTENCE }                      # Referenz/Baseline
    - { name: seasonal-naive, type: SEASONAL_NAIVE, params: { season: P1D } }
    - { name: ets,            type: HOLT_WINTERS,   params: { season: P1D } }
    - { name: ridge-ar,       type: LINEAR,         params: { alpha: 1.0 }, uses: [lags, rolling, stats, calendar, context] }
    - { name: gbm,            type: EXTERNAL,       runtime: python, entrypoint: "opentsx_models.lightgbm:train" }
  select: { by: MAE, per: [target, horizon], mustBeat: persistence }
  intervals: { method: CONFORMAL, levels: [0.8, 0.95] }   # aus Holdout-Residuen

outputs:
  metrics: [FORECAST, INTERVAL, RESIDUAL, ANOMALY_SCORE, DRIFT, FEATURE_HEALTH]
  emitEvery: PT10M
```

## 4. Smart Metrics (Ausgabe-Topic)

Topic `tsx.<domain>.smart.<name>.metrics.v2`, Key = `seriesId`, neues Avro-Schema `SmartMetric` (Modell v2.1, additiv):

| Feld | Inhalt |
|------|--------|
| `smartTopic`, `specHash` | Herkunft (Spezifikation + Version) |
| `seriesId`, `ts`, `horizon` | Zielserie, Zeitpunkt, für den der Wert gilt, Prognosehorizont (ISO-Dauer; leer für Nicht-Prognosen) |
| `kind` | `FORECAST`, `INTERVAL_LOW/HIGH`, `RESIDUAL`, `ANOMALY_SCORE`, `DRIFT`, `FEATURE_HEALTH`, … |
| `value`, `level` | Wert; Konfidenzniveau bei Intervallen |
| `modelId`, `modelVersion`, `contextUsed` | welches Modell; ob Kontext einging (oder die kontextfreie Variante) |
| `issuedAt` | wann berechnet (für Prognose-Evaluation „as issued“) |

* **Residuum und Anomalie-Score** entstehen, sobald der Istwert eintrifft: `|ist − prognose| / σ_holdout`.
  Daraus lassen sich Ereignisse (z.B. Minderleistung) wieder als Episoden segmentieren (P5, Threshold-Segmenter).
* **Drift** ist der rollierende Fehler im Verhältnis zum Holdout-Fehler. Er kann ein Retraining auslösen
  (`retrainOn`).
* **Feature-Gesundheit**: Anteil fehlender/veralteter Kontext-Features. Fällt ein Kontext aus, schaltet die
  Bewertung automatisch auf die kontextfreie Variante um und vermerkt das in `contextUsed`.

KafScale hat keine Compaction. Leser nehmen deshalb je (seriesId, ts, horizon, kind) den neuesten Datensatz, wie bei
`series-stats.v2`.

## 5. Modelle und Training

* **Training in Python, Bewertung in Java:**
  * Trainingsroutinen laufen in Python, z.B. statsmodels, LightGBM, sktime/Darts, scikit-learn.
  * Sie laufen als Job (Notebook, PySpark oder ein vom Prozessor angestoßener Container) und lesen die Daten über
    Iceberg/Replay (P2/P3), nicht „was gerade im Speicher ist“.
  * Dafür erzeugt die Python-Seite die Feature-Matrix aus derselben Spezifikation. Eine gemeinsame Feature-Definition
    plus Testvektoren sorgen dafür, dass Java und Python dieselben Features berechnen (wie beim Modell v2).
  * Exportiert wird ein portables Format, bevorzugt **ONNX** (sklearn/LightGBM via `skl2onnx`/`onnxmltools`).
    Statistische Modelle ohne ONNX-Weg (z.B. ETS) exportieren ihre Parameter in ein kleines JSON-Format, das der
    Java-Scorer kennt.
  * Der Prozessor bewertet in Java (ONNX Runtime für Java), also ohne Python im Streaming-Pfad.
* **Varianten je Ziel:**
  * Jedes Ziel × Horizont × Kandidat wird trainiert.
  * Die Auswahl (Champion) ist deklarativ (`select`).
  * Jeder Kandidat mit Kontext-Features wird zusätzlich **ohne Kontext** trainiert („mit oder ohne Kontextdaten“).
* **Granularität – lokal je Serie, global föderiert (Wayang-Ansatz):**
  * Default ist **ein Modell je Serie** (z.B. je Turbine).
  * Zusätzlich gibt es ein **globales Modell je Target**. Es entsteht föderiert, nach dem Muster von Apache Wayang /
    Scalytics Federated:
    * Jedes „System“ (Park, Standort, Betreiber) trainiert lokal auf seinen Daten.
    * Es zahlt nur Modell-Updates (Parameter/Gradienten bzw. Boosting-Statistiken) in eine Aggregationsrunde ein,
      z.B. FedAvg/FedProx; für Bäume histogrammbasiert.
    * Rohdaten verlassen das System nicht.
  * Das globale Modell dient als Fallback und Cold Start für neue Serien. Ein lokales Modell darf daraus feinjustiert
    werden.
  * In der Spezifikation: `training.federation: { scope: system, aggregate: FEDAVG, rounds: 10, minParticipants: 3 }`.
  * Die Zuordnung Serie → System kommt aus den Tags (z.B. `park`, `operator`).
* **Zeitlicher Split mit Lücke:** keine zufälligen Splits, also kein Leakage über Lags.
* **Intervalle:** konform aus Holdout-Residuen (verteilungsfrei, je Horizont).
* **Reproduzierbarkeit:** Ein Modell ist eindeutig durch
  * Spezifikations-Hash,
  * Trainingsfenster,
  * Feature-Schema-Hash,
  * Kandidat,
  * Seed,
  * bei föderierten Modellen: Runde und Teilnehmerliste.
* **Modell-Registry: MLflow.**
  * Jeder Lauf ist ein MLflow-Run mit Parametern, Metriken (MAE je Horizont, Holdout-Residuen-Quantile),
    Spezifikations-Hash und Feature-Schema als Tags.
  * Das Artefakt ist ONNX bzw. Parameter-JSON.
  * Registriert wird als *Registered Model* `<smartTopic>/<target>/<horizon>`, mit Aliases für Champion und
    Kandidaten (MLflow ≥ 2.9: Model Aliases statt Stages).
  * Der Prozessor fragt die Registry nach dem Alias `champion` ab und lädt neue Versionen ohne Neustart.
  * Auf dem KafScale-Topic `…smart.<name>.models.v2` erscheinen nur **Ereignisse** („Version x ist Champion für …“).
    Sie dienen Nachvollziehbarkeit und Replay; die Registry selbst bleibt MLflow.

## 5a. Freigabe eines Modells für einen Zweck (Entwurf)

> Stand: 2026-10-13, aus dem Review-Briefing in [15](15-ENTSCHEIDUNGSKREIS.md), Abschnitt 7. Nicht umgesetzt.

Die Auswahl in Abschnitt 5 (`select: { by: MAE, mustBeat: persistence }`) sagt, welches Modell das beste der
Kandidaten ist. Sie sagt nicht, ob es für eine bestimmte Entscheidung trägt. Dafür deklariert die Entscheidungskarte
(`opentsx.decision.v1`) je Analyse:

* die **Eingänge** als Signale mit Güte, höchstem Alter und zulässiger Herkunft. Die Spezifikation bekommt dafür
  `target.signal` neben der Auswahl nach Metrik und Tags; ihr eigenes `resample` entfällt, wenn das Signal bereinigt
  vorliegt,
* den **Gültigkeitsbereich**: den Bereich der Eingänge, in dem das Modell geprüft ist,
* den **Nachweis**: Zeitraum, Kennzahl, Vergleich mit der Referenz, und ob er auf erzeugten Daten beruht,
* die **Unsicherheit** und die Breite, ab der ein Ergebnis die Entscheidung nicht mehr trägt.

Jedes Ergebnis trägt daraus einen Verwendbarkeitsstatus (verwendbar, eingeschränkt, gesperrt) mit Gründen. Ein
Ergebnis außerhalb des Gültigkeitsbereichs wird gezeigt und als nicht gestützt gekennzeichnet. Vollständige Herkunft
der Eingänge ist Voraussetzung, kein Beleg für Güte.

## 6. Prediction-API

```java
SmartTopic st = SmartTopics.open(spec, stores, registry);

Forecast f = st.predict("wind.turbine.power_active{park=ostsee-1,turbine=T07}", Duration.ofHours(1));   // Kontext aus dem Stream
Forecast g = st.predict(seriesId, Duration.ofHours(6), Context.of("forecast_ws", 11.2));            // Kontext mitgegeben
Forecast h = st.predict(seriesId, Duration.ofHours(6), Context.none());                             // bewusst ohne Kontext
// Forecast: value, Intervalle, modelId/-version, contextUsed, issuedAt, Feature-Gesundheit
```

REST-Spiegel (gemeinsam mit der Retrieval-REST-Schicht aus P5):

```
POST /v1/smart-topics/{name}/predict
{ "seriesId": "…", "horizon": "PT1H", "context": { "forecast_ws": 11.2 }, "useContext": "auto|required|none" }
GET  /v1/smart-topics/{name}/models
GET  /v1/smart-topics/{name}/metrics?seriesId=&from=&to=&kind=
```

Python: derselbe REST-Client. Für Notebooks lädt `opentsx.smart` Smart Metrics als DataFrame, wie `stats_to_frame`.

## 7. Laufzeit

Der Prozessor folgt dem Muster von `StatsProcessor`, ist also KafScale-tauglich:

* manuelle Partitionszuweisung, keine Consumer-Gruppen, keine Commits,
* Checkpoint-Datei mit den Positionen und dem gesamten Zustand: Feature-Puffer, Kontext-As-of-Stände, laufende
  Fehler, aktuelle Champions.

Für die Ereigniszeit gilt:

* Jede Zielserie hat ihre eigene Ereigniszeit.
* Kontext-Joins sind as-of, mit `maxAge`.
* Eine Prognose für `t + h` wird zum Zeitpunkt `t` erzeugt und später gegen den Istwert ausgewertet.

Das Training läuft entweder im Prozessor (Java-Modelle, asynchron im Hintergrund) oder extern (Python, §9). Beide
Wege schreiben `ModelArtifact`-Datensätze, und der Prozessor übernimmt neue Champions ohne Neustart.

## 8. Metadaten (OpenMetadata)

* **Smart-Topic-Eintrag:** Topic plus `MlModel`-Entities (OM 2.0 kennt `MlModel` mit `mlFeatures`,
  `mlHyperParameters`, `target`, `algorithm`).
* **Lineage:** Die Feature-Quellen verweisen auf die Spalten der Signal-View und auf die Kontext-Topics. So läuft die
  Lineage von den Rohdaten über das Modell bis zu den Smart Metrics.
* **Profile und DQ:** Die Modellgüte (MAE je Horizont, Drift) wird als Profil/DQ-Ergebnis geführt, analog zu P4.

## 9. Entscheidungen (2026-10-12)

| # | Frage | Entscheidung |
|---|-------|--------------|
| 1 | Modell-Laufzeit | **Python-Training zuerst**; Java bewertet exportierte Modelle (ONNX bzw. Parameter-JSON) |
| 2 | Modell-Registry | **MLflow** (Registered Models, Aliases); KafScale-Topic nur für Champion-Ereignisse |
| 3 | Granularität | **Modell je Serie**; dazu ein **globales Modell föderiert nach dem Wayang-Ansatz**: lokale Trainings je System zahlen in das globale Modell ein, ohne Rohdaten zu bewegen |
| 4 | Roadmap | **Nach P6** (Analytik-Lücken) → Smart Topics sind **P7** |

Noch offen, zur Umsetzung:
* Welche Federation-Laufzeit: Apache Wayang direkt (Java, plattformübergreifend) oder Scalytics Federated.
* Wie der Trainingsjob angestoßen wird: Prozessor ruft MLflow Projects/Container auf, oder externer Scheduler.

## 10. Ausbaustufen (P7)

* **P7a – Spezifikation und Features:**
  * Spec-Parser und -Validierung in Java und Python.
  * Feature-Pipeline (Resampling, Lags, Rolling, Stats-Felder, Kalender, Kontext-As-of) in beiden Sprachen, mit
    gemeinsamen Testvektoren.
  * Schema v2.1 mit `SmartMetric`, additiv, mit BACKWARD_TRANSITIVE-Prüfung.
* **P7b – Training (Python):**
  * `opentsx.smart.train(spec)` liest aus Iceberg, macht einen zeitlichen Split, trainiert die Kandidaten mit und
    ohne Kontext und berechnet konforme Intervalle.
  * Danach Export nach ONNX bzw. JSON und Logging nach MLflow (Run, Registered Model, Alias `champion`).
  * Baselines Persistence und Seasonal Naive sind immer dabei.
* **P7c – Scoring-Prozessor (Java):**
  * `opentsx-smart`, KafScale-tauglich mit Checkpoint.
  * Lädt den Champion aus MLflow und bewertet mit ONNX Runtime.
  * Liefert die Smart Metrics (Prognose, Intervall, Residuum, Anomalie-Score, Drift, Feature-Gesundheit) und die
    Prediction-API (Java + REST).
* **P7d – Föderation:**
  * Lokale Trainings je System.
  * Aggregation (FedAvg für lineare/NN-Modelle, Histogramm-Aggregation für Bäume) nach dem Wayang-Ansatz.
  * Das globale Modell landet als eigenes Registered Model; Cold-Start-Regeln.
* **Test gegen den Windpark-Simulator:**
  * Leistungsprognose 10 min bis 6 h.
  * Ziel: Persistence mit Kontext (Windgeschwindigkeit) klar schlagen, ohne Kontext mindestens gleichauf.
  * Anomalie-Scores müssen die injizierten Ereignisse treffen.
  * Ein föderiertes globales Modell über zwei simulierte Parks muss für einen neuen Park besser sein als lokale
    Modelle ohne Historie.

Betrieb im Strom (Prozessor, DAG, DevOps): siehe [11-SMART-TOPIC-PROZESSOR-DEVOPS](11-SMART-TOPIC-PROZESSOR-DEVOPS.md).

## 11. Personal Service Layer (Vorschlag, 2026-10-12)

Der vorhandene SaaS-Stack (`opentsx-saas-backend`: FastAPI, JWT, Benutzer/Organisationen/Teams; `opentsx-saas-frontend`:
React) wird zur **Control Plane für die eigenen Smart Series** eines Benutzers/Teams. Er hält keine Zeitreihendaten
(Datenebene bleibt KafScale/Iceberg/Retrieval-REST/MLflow), sondern Referenzen und Rechte:

| Heute | Ziel |
|-------|------|
| `Flow` (JSON-Definition, Eigentümer, Status) | Smart-Series-Spezifikation (YAML, §3), versioniert, je Benutzer/Team |
| `FlowExecution` | Trainingsläufe/Deployments (MLflow-Run-IDs, Champion, Prozessor-Status) |
| `FlinkService` | allgemeiner Runner: Statistik-Prozessor, Smart-Topic-Scoring, Python-Training (MLflow Project) |
| Dashboard | „Meine Serien“: Smart Metrics, Modellgüte/Drift, DQ, Prediction mit/ohne Kontext |
| Benutzer/Teams | Eigentum an Topic-Präfixen, Iceberg-Namespaces, OpenMetadata-Domains |

Eine persönliche Instanz ist zugleich das „System“ der Föderation (§5): lokal trainieren, nur Modell-Updates einzahlen.
Erweiterung um Systemrekonstruktion, Simulation und Optimierung: siehe das Konzept zur Netzwerkrekonstruktion
([`06-SYSTEMREKONSTRUKTION-OPTIMIERUNG.md`](06-SYSTEMREKONSTRUKTION-OPTIMIERUNG.md), Phase P8).
