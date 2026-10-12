# TASK-011 – Datamodel Refresher 2026

- **Branch:** `opentsx/2026-datamodel-refresher`
- **Status:** 🚧 in Umsetzung – P0–P5 umgesetzt, P6/P7 geplant
- **Priorität:** Hoch
- **Erstellt:** 2026-10-09
- **Dokumente:** [`01-ANALYSE-Datenmodell.md`](../docs/datamodel-2026/01-ANALYSE-Datenmodell.md) · [`02-ZIELMODELL.md`](../docs/datamodel-2026/02-ZIELMODELL.md) · [`03-GAP-ANALYSE.md`](../docs/datamodel-2026/03-GAP-ANALYSE.md)

## Ziel

Ein explizites, versioniertes Zeitreihen-Datenmodell (v2) mit robuster Serialisierung, damit

1. TimeSeriesBuckets **preiswert in KafScale-Topics** abgelegt und **per Replay** wieder eingespielt werden können,
2. Metadaten nach **OpenMetadata** fließen, gespeist von einem **Streaming-Prozessor**, der laufende Statistiken über den Event- bzw. Episoden-Datenstrom berechnet,
3. **OpenTSDB** und **Iceberg** über eine gemeinsame Store-Abstraktion angebunden sind,
4. eine **einfache API für Episoden-Retrieval** und intern **Pattern-Matching / Pattern-based Retrieval** existiert.

## Erfolgskriterien

- Ein Avro-Schema-Satz (`org.opentsx.model.v2`) ist die einzige Modellquelle; CI prüft Kompatibilität gegen die Vorversion.
- Roundtrip-Tests: `TimeSeriesObject → Episode → Kafka (beide Wire-Modi) → Episode → TimeSeriesObject` verlustfrei (Zeitachse inkl.).
- Ein bestehender TSBucket (SequenceFile) wird nach KafScale geschrieben und vollständig per Replay rekonstruiert (Count + Checksum aus Manifest stimmen).
- Bytes pro Messwert im Topic gemessen und dokumentiert (Ziel: ≤ 2 Byte/Wert bei regelmäßigen Reihen mit zstd – Messung entscheidet).
- Dieselbe `EpisodeQuery` liefert gegen OpenTSDB- und Iceberg-Adapter fachlich gleiche Ergebnisse (Contract-Test-Suite).
- Flink-Job publiziert `SeriesStats`; OpenMetadata zeigt Topics, Tabellen, Lineage und Profil-Metriken.
- `similarTo(...)` findet in einem synthetischen Benchmark eingepflanzte Muster (Recall@10 ≥ 0.95, z-ED).

## Nicht-Ziele (für diese Iteration)

- Umbau der Algorithmen-Bibliothek / Zerlegung von `TimeSeriesObject` (nur Brücke).
- Eigene Kompressionscodecs vor einer Messung mit zstd.
- UI.

## Phasen & Aufgaben

### Phase 0 – Fundament & Entscheidungen
- [x] Entscheidungen werden in der Tabelle „Entscheidungen“ dieses Dokuments geführt (der alte Ordner `DECISIONS/` liegt in `EVOLUTION/archive/`).
- [x] Toolchain: gesamtes Projekt auf `maven.compiler.release=17`, Versionen nur in `dependencyManagement`, Enforcer (JDK ≥ 17, kein log4j 1.x); Kafka 3.9.1 / Confluent 7.9.1 / Avro 1.12.1 (seit Windpark-PR; 1.12.0 dekodiert double-Arrays mit float-Präzision, 1.12.2 verlangt `org.apache.avro.SERIALIZABLE_PACKAGES` → Folgeaufgabe); `opentsx-core` + `-data` 65 statt 388 Artefakte.
- [x] Legacy-Module nach `archive/modules/` (Cassandra-Store, KStreams-Cassandra-State-Store, Hive-UDF, ksqlDB-UDF).
- [x] CI (GitHub Actions): Java 17 + 21, Python 3.10–3.13, KafScale-Conformance (Broker v1.6.0 aus Quellcode).
- [x] Python-Tests mit echten Assertions (18 Tests + 1 dokumentierte Lücke als strict xfail).
- [x] KafScale-Conformance-Suite + `docker-compose.kafscale.yml`; gemessen gegen KafScale v1.6.0 und Apache Kafka 3.9.1.
- [ ] Flink auf 1.20/2.x heben (aktuell 1.18, läuft auf 17/21).
- [ ] `opentsx-app-tools`: sshd-core 0.8.0 ablösen oder archivieren.
- [x] Issues im KafScale-Fork angelegt: [kamir/kafscale#16](https://github.com/kamir/kafscale/issues/16) ListOffsets-Zeitstempel, [#17](https://github.com/kamir/kafscale/issues/17) LeaveGroup v4 / MEMBER_ID_REQUIRED, [#18](https://github.com/kamir/kafscale/issues/18) Standalone-OffsetCommit.
- [x] Sofort-Fixes `TSOProducer`: Label `"123"`/URI `"URI"`, quadratische Zeitstempel, fehlendes Pflichtfeld `consumer_timestamp`. (Gemischte Formate auf `OpenTSx_Episodes` entfallen mit den v2-Topics in P2.)

### Phase 1 – Modell v2 & Serde (`opentsx-model`, `opentsx-serde`)
- [x] Avro-Schemas: `SeriesKey`, `Observation`, `Episode` (+ `Segmentation`), `EpisodeSummary`, `Provenance`, `BucketManifest`, `SeriesStats`, `PatternMatch` – Spezifikation: [`04-MODELL-V2-SPEC.md`](../docs/datamodel-2026/04-MODELL-V2-SPEC.md).
- [x] Code-Generierung nach `target/generated-sources` (nicht mehr eingecheckt).
- [x] `SeriesId` (xxHash64, kanonische Form) inkl. gemeinsamer Testvektoren, identisch in Java & Python.
- [x] `OpenTsxAvro`: Single-Object-Encoding (Standard), Confluent-Framing lesbar mit Resolver, Kafka-Serializer/-Deserializer; Python-Gegenstück byte-identisch. Flink-`DeserializationSchema` folgt mit der Flink-Umstellung (P4).
- [x] Time-Encodings `REGULAR`, `IRREGULAR_DELTA` + Roundtrip-Tests und Testvektoren.
- [x] Schema-Historie (`schema-history/v2.0`) + BACKWARD_TRANSITIVE-Prüfung im Build; Schemaänderung erzwingt neue Version.
- [x] v1-Modell eingefroren und als veraltet dokumentiert (`opentsx-data/README.md`), `Event`-Konflikt dokumentiert.
- [x] `opentsx-legacy-bridge`: `TimeSeriesObject ⇄ Episode` verlustfrei, `EpisodesRecord v1 → Episode`.
- [x] Pandas-Anbindung mit UTC-`DatetimeIndex` (`opentsx.model.pandas_io`).

### Phase 2 – Kafka/KafScale-Persistenz & Replay (`opentsx-connectors` bzw. `opentsx-kafka-v2`)
- [x] Topic-Layout `tsx.<domain>.{observations,episodes,series,buckets,series-stats}.v2` + Provisioning (`TopicLayout.provision`), Client-Settings für KafScale (`KafScaleClients`).
- [x] `BucketWriter` (Manifest OPEN → Episoden → SEALED + Checksum), asynchron, **nicht** idempotent (KafScale), `max.in.flight=1`, zstd, Header, Record-Timestamp = `tStart`; kein SEAL bei Sendefehlern.
- [x] `ReplayService`: nach Bucket oder Serien + Zeitbereich über den **Episoden-Index** (statt `offsetsForTimes`), manuelle Zuweisung + seek, Ereigniszeit-Reihenfolge, Pacing, Dedup, Verifikation gegen Manifest, begrenzter Speicher. `EpisodeIndexer` baut den Index aus dem Log neu, `BucketCatalog` liest Manifeste. Sink: Callback (Topic-/TSBucket-Sinks bei Bedarf).
- [x] `LegacyBucketImport`: TSBucket-SequenceFile → Episoden (→ `BucketWriter`); `EpisodesRecord` v1 → v2 über `LegacyBridge`.
- [x] Kostenmessung: **2,18 B/Wert** (1 Hz, Sensorauflösung, regulär, zstd), 6,96 B/Wert bei voller Double-Präzision, 4,07 B/Wert mit Zeit-Jitter (`opentsx-kafka-v2/README.md`). Records/s gegen KafScale-S3 noch offen (lokal nur In-Memory-S3).
- [x] `TSOWriter.persistBucket_Kafka`-Stub als veraltet markiert und auf den v2-Pfad verwiesen.

### Phase 3 – Store-SPI, OpenTSDB, Iceberg (`opentsx-store-spi`, `opentsx-store-opentsdb`, `opentsx-store-iceberg`)
- [x] `opentsx-store-spi`: `SeriesStore`, `EpisodeQuery`, `SeriesSelector`, `StoreCapabilities`, `EpisodeOps.clip`, `InMemorySeriesStore` (ersetzt leere `TSBucketStore`/`TSO*Interface`).
- [x] Contract-Test-Suite (`SeriesStoreContract`, test-jar) – grün für In-Memory, Iceberg (JDBC/SQLite-Catalog) und OpenTSDB (Emulator). Offen: echter OpenTSDB-Server und Iceberg-REST-Catalog + MinIO in CI (kein Docker lokal).
- [x] OpenTSDB-Adapter `opentsx-store-opentsdb-v2`: HTTP-API, Validierung metric/tags (≤ 8), Einheit als `_unit`, `/api/put` gebatcht, `/api/query` → Episoden; ms-Präzision, NaN wird gezählt verworfen.
- [x] Iceberg-Adapter `opentsx-store-iceberg` (Iceberg 1.10): Tabellen `episodes` (verlustfrei, `day(t_start)` + `bucket(32, series_id)`), `series`, `episode_index`; idempotente Writes; Time-Travel. Python liest dieselben Tabellen mit PyIceberg byte-identisch (CI-Job `cross-language`).
- [x] KafScale → Iceberg: `EpisodeLogSync` (manuelle Zuweisung, Positionen zurückgegeben, idempotent) + `IcebergEpisodeIndex` als dauerhafter Replay-Index; End-to-End-Test gegen KafScale v1.6.0.

### Windpark-Domäne (Gap-Roadmap P3, `opentsx-windpark`)
- [x] Versionierter Datenpunktkatalog (`channel-catalog.yaml` v1.0.0) mit Zuordnung zu IEC 61400-25-2 (LD/LN/DO/DA, CDC, Edition), Einheit (UCUM), Auflösung, Plausibilität, Qualitätscodes, Herstellererweiterungen (`vendorSpecific`, `notes`).
- [x] Quellsystem-Mappings: Originalname, Quelladresse, Skalierung/Offset/Vorzeichen, Quelleinheit, Zeitzone, Zeitbezug (INSTANT/START/END), Aggregation (`mean.PT10M` …), Status CONFIRMED/PROVISIONAL/UNMAPPED.
- [x] Asset-Modell (`TurbineType`, `Turbine`, `Park`) + Inventar (erwartete Serien) als Eingabe für OpenMetadata.
- [x] Normalisierer: Namen/Einheiten/UTC/Intervallbeginn/Quantisierung/Plausibilität → ausgerichtete 10-min-Episoden + Report (nichts wird geraten).
- [x] Simulator: Weibull + OU-Turbulenz (exakt diskretisiert), Jensen-Wake, Leistungskurve, Pitch/Yaw/Temperatur, Betriebsereignisse, Sensorfehler mit Ground Truth; deterministisch inkl. Episoden-IDs.
- [x] CLI `WindparkSim` → KafScale (versiegelter Bucket) und/oder Iceberg; End-to-End-Tests (Iceberg immer, KafScale mit `KAFSCALE_BOOTSTRAP`, in CI).
- [ ] Folge: Avro 1.12.2+ mit `SERIALIZABLE_PACKAGES`; echte Herstellerexporte als weitere `sources` einpflegen.

### Phase 4 – Streaming-Statistik & OpenMetadata (`opentsx-stats`, `opentsx-openmetadata`)
- [x] `StatsEngine` (laufzeitunabhängig): je `seriesId` Welford + min/max + NaN + **Quantil-Sketch** (DDSketch, 1 % rel. Fehler, Java/Python byte-identisch) + Gap-Erkennung; GLOBAL + epoch-ausgerichtete Tumbling-Fenster; Ereigniszeit je Serie (Episode → `tEnd`); Dedup per `episodeId`; Checkpoint des kompletten Zustands.
- [x] `StatsProcessor` auf KafScale (manuelle Zuweisung, keine Gruppen/Commits, Checkpoint-Datei nach bestätigten Outputs) → `series-stats.v2`. Keine Compaction auf KafScale → `SeriesStatsLog` liest „latest per key“. Late Data: gezählt, nur GLOBAL. Grün gegen KafScale v1.6.0.
- [x] Data Quality auf Statistiken (Vollständigkeit, Plausibilitätsband via Sketch, Flatline, Lücken, Aktualität); findet die Simulator-Fehler exakt in den betroffenen Fenstern.
- [x] `opentsx-openmetadata`: Glossary aus dem Datenpunktkatalog, Klassifikationen (IEC-LN, Mapping-Status), Custom Properties, Topics mit Avro-Schema, Iceberg-Tabellen, Signal-View je Park, Pipelines + Lineage, Profile je Fenster, DQ-Testdefinitionen/-fälle/-ergebnisse. Payloads gegen die JSON-Schemas von `openmetadata-spec 2.0.5` validiert; Dry-Run-Modus.
- [ ] Flink-Wrapper um `StatsEngine` (KafkaSource ohne Offset-Commits) – erst mit Flink-Upgrade.
- [ ] Smoke-Test gegen echten OpenMetadata-2.0.5-Server (docker compose, nightly).
- [ ] Bestehenden `TimeSeriesAggregateFunction`/`TimeSeriesAnalysisJob` auf v2-Serde umstellen oder deprecaten.

### Phase 5 – Segmentierung, Retrieval & Pattern-Matching (`opentsx-retrieval`)
- [x] Segmentierer für alle vier Strategien (FIXED_DURATION, FIXED_COUNT, FEATURE_EXTREMUM, FEATURE_THRESHOLD), inkrementell → Streaming == Batch (getestet), deterministische Segment-IDs, Lineage.
- [x] Pattern-Engine: MASS (zED), PAA/SAX inkl. MINDIST (exakter Prefilter, `EpisodeSummary.sax`), DTW mit Sakoe-Chiba, LB_Kim/LB_Keogh (UCR-Kaskade); exakte Top-k ohne Überlappung über Episodengrenzen; Ganz-Episoden-Ähnlichkeit.
- [x] Benchmark auf simulierten Winddaten (eingepflanzte Böen, Warping, Rauschen) → Recall/Latenz-Report.
- [x] `EpisodeService`: `episodes()` (+ Resampling), `episode(id)` (neu im Store-SPI), `similarTo`, `similarEpisodes`, `segments(...).writeTo(store)`.
- [x] Dünne REST-Schicht (`RetrievalServer`, JSON oder Avro-Stream) + Python-Client `opentsx.client.RetrievalClient`; Live-Test Python ↔ Java in CI.
- [x] Leistungskurven-Analyse (Methode der Bins, Performance-Index, Minderleistungsphasen) in `opentsx-windpark`.
- [ ] 5c (optional): Matrix-Profile Motif/Discord über Buckets; Treffer als Annotationen zurückschreiben.
- [ ] iSAX-Index für heiße Daten (aktuell: SAX-Prefilter je Episode, MASS je Serie).

### Phase 6 – Analytik-Lücken
- [ ] Resampling/Alignment mehrerer Serien (gemeinsames Raster, As-of-Joins) – Grundlage für Smart Topics.
- [ ] Gap-Filling (linear, saisonal, modellbasiert) mit Qualitätscode SUBSTITUTED.
- [ ] Change-Point-Erkennung (CUSUM/PELT) als Segmentierer.
- [ ] Welch-PSD/Kohärenz (Schwingungen), Feature-Extraktion je Episode.

### Phase 7 – Smart Topics (`opentsx-smart`, Python `opentsx.smart`) – Entwurf: [`05-SMART-TOPICS.md`](../docs/datamodel-2026/05-SMART-TOPICS.md)
- [ ] P7a: Spezifikation (YAML, versioniert) + Feature-Pipeline Java/Python mit Testvektoren; Schema v2.1 `SmartMetric`.
- [ ] P7b: Python-Training (Kandidaten mit/ohne Kontext, zeitlicher Split, konforme Intervalle) → ONNX/JSON → MLflow (Alias `champion`).
- [ ] P7c: Java-Scoring-Prozessor (KafScale, Checkpoint, ONNX Runtime) → Smart Metrics + Prediction-API (Java/REST).
- [ ] P7d: Föderiertes globales Modell je Target (Wayang-Ansatz: lokale Trainings je System zahlen ein, keine Rohdaten bewegen).
- [ ] P7e (Vorschlag): Personal Service Layer auf dem SaaS-Stack – Control Plane für eigene Smart Series (`05-SMART-TOPICS.md` §11).

### Phase 8 – Systemrekonstruktion, Simulation & Optimierung – Konzept: [`06-SYSTEMREKONSTRUKTION-OPTIMIERUNG.md`](../docs/datamodel-2026/06-SYSTEMREKONSTRUKTION-OPTIMIERUNG.md)

Kette: *Quellen (Sensoren, Content-Interaktion wie ER1/Wikipedia mit Feedback) → Episoden → Netzwerkrekonstruktion
(Kämpf 2017 + Stand 2026) → Simulation auf rekonstruierter Struktur → Optimierung (klassisch, Quanten nur als
Messobjekt)*, gesteuert über den Personal Service Layer (P7e). Abnahme jeweils gegen die Ground Truth des
Windpark-Simulators.

**Kurztitel: Zusammenspiel & Koordinationspotenzial.** Leitfrage: *„Was verlieren wir durch getrennte
Betriebsführung – und was gewinnen wir durch abgestimmtes Handeln?“* Gemeint ist das gemeinsame Optimum des lokalen
Energieverbunds (Windpark, Batterie, Elektrolyseur, Energieabnehmer, Vermarktung). Einzubeziehen: Wetter und
Erzeugungsprognosen, Strompreise und Vermarktungsfahrpläne, H2-Bedarf und Abnahmeverpflichtungen, Speicherstände,
Kapazitäten und technische Grenzen, Netzrestriktionen, Ackerarbeit, Fledermausabschaltungen und weitere
Betriebsvorgaben.
- **Ergebnis:** überprüfbares Modell der Abhängigkeiten und Handlungsspielräume plus bezifferte Koordinationslücke.
  Lieferobjekte: Abhängigkeitsmodell, Nebenbedingungen, Referenzbetrieb, Vergleichsszenarien, Potenzialbericht mit
  Annahmen, Unsicherheiten und Sensitivitäten.
- **Bewertungsprinzip:** Koordinationspotenzial = Ergebnis der koordinierten Alternative − Ergebnis des
  Referenzbetriebs, unter denselben Randbedingungen und mit den zum Entscheidungszeitpunkt verfügbaren
  Informationen; ein Vergleich mit perfekter Zukunftskenntnis nur als gekennzeichnete theoretische Obergrenze.
  Ergebnisgrößen: Deckungsbeitrag, vermeidbare Abregelung, H2-Bedarfsdeckung, Speicherbeanspruchung, Nutzen und
  Mehrkosten je Beteiligtem. Modelliertes Potenzial und nachgewiesener Mehrerlös bleiben getrennt.
- **Grundsatz:** Statistische Abhängigkeit ist zunächst eine Hypothese, kein Kausalitätsnachweis. Die Verfahren in
  P8.0–P8.5 sind **Methodenvorschläge** und werden erst nach fachlicher Abstimmung verbindlich.
- **Kooperation:** Transparenz darüber, wer welche Daten bereitstellt, wer Änderungen entscheidet und verantwortet,
  wer profitiert und wer Aufwand, Kosten und Risiken trägt.
- **Brücke zu P9:** P8 identifiziert Abhängigkeiten und beziffert das Koordinationspotenzial; P9 macht daraus einen
  ausführbaren Zwilling, in dem Entscheidungen kausal durchschlagen und Gegenmaßnahmen geprüft werden.
- [ ] Betriebsvorgaben (Netzrestriktionen, Ackerarbeit, Fledermausabschaltungen) als Nebenbedingungen erfassen – im Zwilling als Baustein „Betriebsvorgaben“ (Kalender-/Regelquelle für Abregelung).

**P8.0 – Fundament (Vertrag)**
- [ ] *(niedrige Priorität, vorgemerkt)* Ground-Truth-Export: **der vom Simulator zur Datenerzeugung verwendete** gerichtete Wake-Graph je Windrichtungssektor aus `opentsx-windpark` (Jensen, `WakeModel`), gekennzeichnet als Simulationsreferenz (`gt.source=simulator`, `gt.kind=wake`). Festlegungen nach [07 §8](../docs/datamodel-2026/07-DIGITAL-TWIN-BSP.md): meteorologische Konvention, 12 × 30°-Sektoren, Kante `A → B` = B im Wake-Kegel von A für mindestens eine Sektorrichtung, Gewichte (mittleres/maximales Defizit, Abdeckungsanteil, Abstand in D) bei Referenz-Ct (8 m/s) plus Ct-Tabelle 4/8/12 m/s; zusätzlich das **dynamische Kantenlog** des Laufs (aktive Paare je 10-s-Schritt), inkl. injizierter Fehler/Regime. Grenzen (keine Stabilität/Mäander) dokumentieren.
- [ ] Iceberg-Schema der **Kantentabelle** (Multilayer, versioniert): Quelle/Ziel-Serie, Layer/Methode, Lag, Stärke, Signifikanzfelder (l_adj, l_norm, l_trans, p-Wert, Surrogattyp, Konfidenz aus Bagging), Fenster, Spezifikations-Hash, Provenienz (Episoden-Hash, Topic/Offsets).
- [ ] OpenLineage-Facet „Replay-Provenienz“ in `opentsx-openmetadata`; Kantentabelle, Simulationsläufe und Optimierungsinstanzen als Lineage-Knoten in OpenMetadata.

**P8.1 – Klassische Rekonstruktion (Kämpf-Methodik als Apache-lizenzierte Java-Bibliothek, Clean-Room)**
- [ ] Lagged Cross-Correlation mit adjustierter (l_adj), normierter (l_norm) und Peak-Qualitäts-Linkstärke (l_trans).
- [ ] Event Synchronization mit Q_rand-Kalibrierung (behebt zugleich den bekannten Q>1-Fehler) **und** Event Coincidence Analysis (ECA) als robustere Alternative.
- [ ] Surrogate: Shuffling, Phasenrandomisierung, Fourier-Filter + Rank-Reordering (erhalten Langzeitkorrelationen); KS-Test, adaptive Schwelle je Fenster, k-Means in Linkmaß-Ebenen, FDR-Korrektur; MST/PMFG-Filter.
- [ ] Univariate Vorstufe: DFA, RIS, Wochenzyklus-Normalisierung (168 h) für Content-Daten.
- [ ] Kontextmaße für Content-Netze: Context Cohesive Force (CCF), Relevanzindizes (lokal/global TRRI), Structure Induced Stress (SIS) als Struktur-vs.-Funktion-Maß.
- [ ] Streaming-Screening: laufende Fenstersummen (CC/ECA) in `opentsx-stats`, Flink-Variante mit dem Flink-Upgrade; Precision/Recall gegen Ground Truth je Sektor, CC-Baseline immer mitberichten.

**P8.2 – Kausale Bestätigung (Screen-then-Confirm)**
- [ ] Python-Worker (`python-package/opentsx/algorithms`): liest Episoden via PyIceberg, ruft tigramite (PCMCI+, RPCMCI, J-PCMCI+) und IDTxl (multivariate TE) **hinter einer Prozessgrenze** (GPL), schreibt Kanten zurück; Bootstrap-Bagging für Konfidenzen.
- [ ] Hawkes-Granger für Ereignisströme (Content-Interaktion: Zugriffe, Edits, Feedback).
- [ ] Vergleichsbericht im CauseMe-Stil auf Simulator-Ground-Truth; Bestätigung wird nur Standard, wenn sie die CC-Baseline messbar schlägt.

**P8.3 – Simulation auf rekonstruierter Struktur (Zwilling aus Daten)**
- [ ] Netzwerkdynamik-Modelle auf bestätigten Kanten: VAR, Koopman/DMD; multivariater Hawkes auf Graph für Content-Daten; physikbasierter Wake-Pfad (FLORIS-artig) als Referenz.
- [ ] Szenario- und Interventions-Replay gegen zurückgehaltene Episoden; SIS/Schwellwertparameter als Strukturbruch-Detektor; Abnahme: Out-of-sample besser als strukturfreies Modell.
- [ ] Identifizierbarkeit: Regimevielfalt bzw. gezielte Anregung (Simulator-Interventionen, Feedback als quasi-Intervention).

**P8.4 – Optimierung, klassisch**
- [ ] Problem-Builder aus Kantentabelle/Simulation: graph-native Binärprobleme (Partitionierung, Sensor-/Turbinenauswahl, MIS) als QUBO im dimod-Format; Betriebsprobleme (Yaw/Wake-Steering, Wartung, Unit Commitment) als MILP.
- [ ] Backends: HiGHS, OR-Tools CP-SAT (Java), Simulated Annealing/Parallel Tempering; Gurobi/CPLEX optional; Java exportiert, Python-Optimierungsdienst hinter einer Schnittstelle.
- [ ] Ergebnisse als Smart-Topic-Ereignisse; Optimalitätslücke gegen MIP-Schranke; geschlossene Schleife im Simulator verbessert die Zielgröße.

**P8.5 – Quanten- und föderierte Experimente (Messobjekt, kein Versprechen)**
- [ ] Quantenbackends (D-Wave Leap Hybrid, Qiskit Functions, ggf. Neutralatome für MIS) nur über Python-Sidecar; Protokoll: Zeit-bis-Ziel inkl. Embedding und Warteschlange, beste getunte klassische Baseline (PT, GPU-Heuristiken), QOBLIB-Berichtsformat; „Parität/kein Vorteil“ ist ein gültiges Ergebnis.
- [ ] Veröffentlichung versionierter, simulatorerzeugter QUBO/MILP-Instanzen mit bekannten Optima.
- [ ] Föderierte Strukturentdeckung über Betreiber hinweg (Wayang, mergebare Sketches, gemeinsamer IEC-Katalog); vorher semantische Angleichung, da Inter-Farm-Lernen unter Heterogenität leidet.

**P8.x – Content-Interaktion / ER1**
- [ ] Klären, ob ein anonymisierter oder synthetischer Auszug ER1-artiger Daten (Zugriff, Edit, Feedback) als zweiter Prüfstein bereitgestellt werden kann; sonst Validierung mit öffentlichen Wikipedia-Pageviews/Clickstream.
- [ ] Synthetischer Generator für Content-Interaktion mit bekannter Kopplung und Feedback (Gegenstück zum Windpark-Simulator).

### Phase 9 – Digitaler Zwilling Energieverbund (BSP) – Konzept: [`07-DIGITAL-TWIN-BSP.md`](../docs/datamodel-2026/07-DIGITAL-TWIN-BSP.md)

Verbund aus Windpark, Elektrolyseur, H2-Leitung, Speicher, Eigenverbrauch/Direktabnehmer und Vermarktung als
Schaltplan atomarer Elemente (MVC+P), ausgeführt als geschichtetes BSP; Smart Topics liefern gelernte Teilmodelle und
publizieren den Zwilling. **Der Zwilling mit seinen drei Betriebsarten (Shadow, What-if, Prognose/MPC) ist die Essenz
der Roadmap.** Alle drei werden in beiden Ausführungsvarianten ermöglicht: eng gekoppelt als ein Prozess (T) und lose
gekoppelt über Smart Topics (L) (D20 entschieden). **Status: übrige Punkte Vorschlag (D18, D19, D21–D24).** Gestuft mit Wertschranken (A–D); P8.3/P8.4 liefern
Simulation auf rekonstruierter Struktur und Optimierer-Policy.

**P9.0 – Stufe A: Datenzugang & Potenzial (ohne Simulation)**
- [ ] Generisches **Modbus-Modul** (TCP/RTU), Registerkarten als YAML (Adresse, Typ, Skalierung, Einheit, OBIS), Pollintervall → `RawReading` → Normalizer → Episoden.
- [ ] **IEC 60870-5-101/-104**-Adapter für die Parkrechner-Schnittstelle (Datenaustausch mit dem Elektrolyseurbetreiber, beide Richtungen).
- [ ] Elektrolyseur-Betriebsdaten (Last, Zustand, Leitungsdruck, Abnahme) über die vereinbarte Schnittstelle (104, OPC UA oder Export) als Quelle `REPORTED`.
- [ ] **DWD-Sammler**: Open-Data-Prognosen (MOSMIX/ICON) und Messungen, automatischer Abruf, Stationsauswahl je Standort, optional deutschlandweit.
- [ ] Markt- und Partnerdaten: Preise (Day-Ahead/Intraday, 15-min-MTU), Prognosen und Fahrpläne des Direktvermarkters, Leitsystem-API (Schnittstellenbeschreibung ausstehend); zeitgesteuerter Abruf mit Formatkonvertierung, wo keine API existiert.
- [ ] Batch-Import einzelner Datenpakete (historische Reihen) mit Herkunftsmetadaten.
- [ ] *(Vorschlag)* infinimesh als optionale Quelle: Kafka-Addon-Topic `infinimesh.shadows` → Normalizer → `tsx.<domain>.observations.v2` (Modul `opentsx-infinimesh`); lesend zuerst, Rückweg über `desired` erst mit Freigabe-Workflow.
- [ ] Metrikkatalog für neue Kanäle (Zähler, Elektrolyse, Speicher, H2) analog zum IEC-Katalog, Veröffentlichung über den OpenMetadata-Publisher.
- [ ] Kafka-Spiegelung über temporären SSH-Tunnel in eine lokale Umgebung; technische Validierung (Offsets, Anzahl, Bytes, Hash) und fachliche Validierung (je Metrik identische Sketches/Statistiken).
- [ ] OpenTSDB als optionale Projektionsschicht (vorhandener Adapter).
- [ ] Abnahme S2: Potenzialabschätzung (Überlappung Erzeugung/Verbrauch, Direktbelieferungsanteil vs. Einspeisung, Größenordnung €/a) mit Herkunftsanteilen → **Schranke A**.

**P9.1 – Atomvertrag & Runner**
- [ ] Screening Stand der Technik vor dem Bau: mosaik, HELICS, FMI 3.0 (Atome als FMUs?), pandapower, PyPSA, oemof → Entscheidung D24.
- [x] Atomvertrag MVC+P in `opentsx-twin` (`Atom`: Modell = Zustand/Parameter, Dynamik `step`, austauschbare `Policy`, Ports mit Art und Einheit, Sensorsicht über Residuum im Bilanzknoten).
- [ ] Avro-Schemas je Atomtyp für Zustand und Ports (heute Java-Records/Maps; nötig für Variante L und Python).
- [x] Schaltplan-YAML (`TwinPlan`, Beispiel `twin/h2-chain.yaml`), Validierung (Ports, Art/Einheit, eine Quelle je Eingang), Rückkopplungen automatisch über die Schrittgrenze; Plan-Hash in der Lauf-Id. *(Lineage nach OpenMetadata offen.)*
- [x] **Variante T (eng, ein Prozess):** `TwinRunner` mit geschichtetem BSP (Phasen Quellen → Policy → Anlagen → Bilanz → Flüsse → Views), Basistakt 1 min, Mehrrate (`every`), Ereignisse mit exaktem Zeitpunkt, Snapshots zum Fortsetzen; bitgleiche Wiederholung über verketteten Zustands-Hash.
- [ ] **Variante L (lose, Smart Topics):** Atomtyp als Smart-Topic-Prozessor (Instanzen als Schlüssel), Kopplung über Port-Topics, Ereigniszeit-Watermarks mit einstellbarer Veraltung (BSP ↔ SSP ↔ asynchron), Bilanzknoten als eigener Prozessor mit Ausgleichsschritt; Atome können bei verschiedenen Eigentümern laufen.
- [ ] Derselbe Atomcode läuft unverändert in T und L (gemeinsamer Atom-, Schaltplan- und Topic-Vertrag); Konformitätstest: L mit Veraltung 0 = T bitgleich.
- [x] Topic-Vertrag als Code: `PortMessage` (deterministische Id `run/k/phase/atom/port`), `Barrier` (Phasenende mit Anzahl, Superschritt-Commit mit Zustands-Hash), `MessageSink`; Variante T schreibt ihn bereits mit.
- [ ] Binding an KafScale-Topics `twin.<id>.<run>.msgs|barrier|interventions`; Zustandssnapshots in Iceberg (`twin_state`); Zustandsreihen als Episoden (`RunResult.toEpisodes`, umgesetzt) in einen Store schreiben.
- [x] Herkunftsleiter `MEASURED > REPORTED > FORECAST > ESTIMATED > SYNTHETIC` (`Origin`) mit Taint-Propagation entlang des Datenflusses eines Schritts; Rückkopplungskanten färben nicht; Defaults für fehlende Eingänge zählen als SYNTHETIC.

**P9.2 – Atomkatalog**
- [ ] A1 WEA, A2 Wake-Kopplung (Relationsatom, Jensen; später rekonstruierter Graph aus P8), A3 Parkregler.
- [ ] A4 Wetter, A5 Zähler/Messpunkt, A6 Bilanzknoten/Messkonzept (Prioritäten: Eigenverbrauch vor Markt), A7 Eigenverbraucher/Direktabnehmer.
- [x] A8 Batteriespeicher, A9 Elektrolyseur (Zustandsautomat, Rampen, Schutzabregelung über Leitungsdruck), A10 H2-Leitung/Linepack (Zeit-bis-Grenze, Restkapazität), A11 H2-Abnehmer.
- [x] A6 Bilanzknoten (Prioritäten, Direktbelieferung, Residuum zur Zählermessung), A12 Markt (15-min-Takt), A13 Direktvermarkter (Abweichung, Mehr-/Mindermengen, Erlös), A16 Betriebsvorgaben (Zeitfenster mit Leistungsgrenze, z. B. Fledermausabschaltung, Ackerarbeit), vereinfachte Erzeugung (A1/A3 als Park am Anschlusspunkt mit Abregelgrenze).
- [ ] A14 Bediener als Freigabe-Workflow im Service Layer (Eingriffe als `Intervention` umgesetzt), A15 Optimierer als Policy-Atom (heute: Regel `pressure-aware` mit rollierendem Horizont), A13 Ausgleichsenergiepreise.

**P9.3 – Betriebsarten & Abnahmeszenarien**
- [ ] **Alle drei Betriebsarten in beiden Varianten** (Matrix 3 × 2) – *in Variante T umgesetzt (`Twin.shadow`, `whatIf`, `forecast` ab Snapshot mit Policy-Austausch); Variante L offen*: Shadow (Nowcast, Residuen Messung − Sensorsicht), Replay/What-if (Eingriffslog, Verzweigungen), Prognose/MPC – gleiche Atome, andere Bindung.
- [ ] S3 PPA-Schattenbetrieb vor der offiziellen Umstellung des Messkonzepts (Fahrplan/Prognose des Vermarkters gegen Erzeugung) → **Schranke B**.
- [x] S1 H2-Kette auf synthetischen Daten (Schieber zu → Druck → Zeit-bis-Grenze → Abregelung → Fahrplanabweichung → Mehrvermarktung): Reihenfolge, analytischer Grenzzeitpunkt, Bilanzen, Was-wäre-wenn und Prognose getestet; Demo `bin/opentsx twin`.
- [ ] S1 mit echten Betriebsdaten bestätigen → **Schranke C**.
- [ ] S4 Wake-Referenz im Park-Atom reproduziert; rekonstruierter Graph (P8.1) dagegen geprüft.

**P9.4 – Policy, Optimierung & Service Layer**
- [ ] Smart Topics als Prognose-Ports und Schätzer für fehlende Daten; Zwilling als abgeleitetes Smart Topic mit What-if-Endpunkt der Prediction-API.
- [ ] Optimierer als Policy-Atom (P8.4, rollierender Horizont); Vorschläge im Personal Service Layer vor Freigabe im Zwilling durchspielen; Mehrerlös gegen Referenzfahrweise → **Schranke D**.

**P9.6 – Forschung: eng vs. lose gekoppelte Zwillinge (Paper)** – Skizze: [07 §10](../docs/datamodel-2026/07-DIGITAL-TWIN-BSP.md)
- [ ] Literaturstand: BSP/Pregel, SSP/Parameter-Server, PDES (Chandy-Misra-Bryant, Time Warp), HLA-Zeitmanagement, FMI/mosaik/HELICS, Dataflow-Watermarks.
- [ ] Versuchsaufbau RQ1–RQ6: gleicher Schaltplan in T und L, S1/S3 plus synthetische Verbünde mit gesteuerter Kopplungsstärke und Störungen (Verzögerung, Verlust, Duplikate, Atomausfall).
- [ ] Ergebnisse: Fehlerschranken in Abhängigkeit von Veraltung, Kopplungsstärke und Zeitkonstanten; Entwurfsregel „was muss eng gekoppelt sein“; reproduzierbares Artefakt.
- [ ] Manuskript und Zielvenue festlegen.

**P9.5 – Übertragbarkeit & Föderation**
- [ ] Schaltplan-Vorlagen für neue Projekte (Park + Direktleitung + Abnehmerlastgang) zur Bewertung vor dem Bau.
- [ ] Vergleich mehrerer Anlagen über generische Kenngrößen ohne Rohdatenexport (Wayang-Föderation, mergebare Sketches); vertikale Erweiterung (CMS-Daten, PV).
- [ ] Edge-Sammler (Modbus/104/DWD) optional als Go/Rust-Binaries; kein Kern-Neubau vor Schranke B (D23).

**P9.7 – Übertragung auf Hauseigentümer (Haus-Zwilling)** – Konzept: [`08-HAUS-ZWILLING.md`](../docs/datamodel-2026/08-HAUS-ZWILLING.md)
- [x] Methode übertragen: gleicher Atomvertrag, drei Betriebsarten, Herkunftsleiter (Abrechnung = REPORTED), Stufen A–D mit Schranken, Bewertung Alternative − Referenz, modelliert ≠ nachgewiesen; Abbildung Haus ↔ Terawatt-Fall.
- [x] Beispielhaus Erzgebirge (erfundene Annahmen): Räume, Hülle, 2 Bewohner mit Tagesablauf, Inventar (3 TV, Kühlschrank, Gefriertruhe, Herd, Radio, Licht …), Ölkessel + Kaminofen, keine PV/Wärmepumpe.
- [x] Atome `weather`, `building` (1R1C, Thermostat), `oil-boiler`, `wood-stove`, `dhw`, `appliances`, `meter`, `oil-tank`, `wood-store`; Schaltplan `twin/haus-erzgebirge.yaml` (PT1H, ein Jahr).
- [x] Kennzahlen (`HouseKpis`): Strom, Öl, Holz, Endenergie, kWh/m²a, witterungsbereinigt (G20/15), Kesselverluste, Standby, Grundlast, Spitze, Kosten, CO2.
- [x] Kalibrierung an Abrechnungen (`BillCalibration`): CSV-Vorlage, Tankstände, Holz nach Heizgradtagen, DWD-Tagesmittel, LM/Broyden, Residuen, CV(RMSE)/NMBE, Identifizierbarkeit; echte Daten nur in `config/private/`.
- [x] Demo `bin/opentsx house [--calibrate …]` mit neun Hebeln als What-if; Tests für Bilanzen, Determinismus, −1 K, leeren Tank, Identifizierbarkeit.
- [x] Simulierte Beispielabrechnungen 2022–2025 (`SimulatedBills`, verdeckte wahre Parameter, Lücken und Ablesefehler) unter `twin/beispiel/`; `bin/opentsx house --simulated`; Test: H/η und Geräteskala gefunden, Warmwasser als nicht bestimmt gemeldet.
- [ ] Mit echten Abrechnungen kalibrieren (Jahre, Tankstände, Holzmengen, Wohnfläche/Baujahr vom Hauseigentümer) → **Schranke B (Haus)**.
- [ ] Nachweis einer Maßnahme: gemessene Zeiträume vor/nach, witterungsbereinigt, gegen Kalibrierunsicherheit.
- [ ] Varianten: Pufferspeicher/wasserführender Ofen, Mehrzonenmodell, Prognose „Reicht das Öl?“, Optimierer als Policy (Bestellzeitpunkt, Holz/Öl-Aufteilung), DWD-Abruf statt CSV.

## Modulstruktur (umgesetzt, Stand 2026-10-12)

```
opentsx-model                 Avro-v2-Schemas, SeriesId, Episoden, Wire-Format inkl. Kafka-Serdes (statt eigenem opentsx-serde)
opentsx-legacy-bridge         TimeSeriesObject/TSBucket/EpisodesRecord <-> v2
opentsx-store-spi             SeriesStore, EpisodeQuery, Contract-Tests, In-Memory
opentsx-store-iceberg         Iceberg-Store, Episoden-Index, KafScale->Iceberg-Sync
opentsx-store-opentsdb-v2     OpenTSDB-HTTP-Store (der alte Client liegt im Archiv)
opentsx-kafka-v2              TopicLayout, BucketWriter, Index, ReplayService
opentsx-kafscale-conformance  Broker-Fähigkeiten messen
opentsx-windpark              Datenpunktkatalog (IEC 61400-25-2), Normalisierer, Simulator, Leistungskurve
opentsx-stats                 Streaming-Statistik + DQ (laufzeitunabhängig; Flink-Adapter folgt mit Flink-Upgrade)
opentsx-openmetadata          Metadaten-/Profil-/DQ-Publisher
opentsx-retrieval             Segmentierung, Pattern-Engine, EpisodeService, REST
opentsx-twin                  Digitaler Zwilling P9: Atomvertrag MVC+P, Schaltplan, Runner Variante T, 3 Betriebsarten
(geplant) opentsx-smart       Smart Topics (P7)
(geplant) opentsx-network     Kantentabelle, Rekonstruktion (Kämpf-Signifikanzschicht, ECA), Problem-Builder (P8)
```

## Entscheidungen (Stand 2026-10-12)

| # | Frage | Status / Entscheidung |
|---|-------|-----------------------|
| D1 | KafScale-Fähigkeiten | **Geklärt (v1.6.0):** keine Compaction, kein idempotenter Producer/Transaktionen, **ListOffsets ignoriert Zeitstempel**, keine Schema Registry, zstd ok, Header-/CreateTime-Erhalt undokumentiert → Conformance-Test. Konsequenzen: 03-GAP-ANALYSE §5 |
| D2 | Zeitpräzision | **Entschieden:** `timestamp-micros` |
| D3 | SeriesId-Hash | **Entschieden:** xxHash64 (Seed 0) über kanonische Form, 16 Hex-Zeichen |
| D4 | Episoden-Definition | **Entschieden:** feste Dauer, feste Länge **und** Feature-basiert (Extremum ± pre/post, Schwelle→Schwelle) → `Segmentation`-Record, 03-GAP-ANALYSE §7 |
| D5 | Java-Baseline | **Entschieden: kein Java 8.** Baseline wie aktuelle Apache-Projekte: `release=17`, CI zusätzlich auf JDK 21 (Kafka 4.x, Flink 2.x, Spark 4.x, Iceberg 1.x laufen alle auf 17/21). Altbestand wird mit angehoben bzw. archiviert. **Python ≥ 3.10** gleichrangig (Pandas/Jupyter, PySpark) |
| D6 | Iceberg-Catalog & Object Store | REST-Catalog + S3-kompatibel (MinIO lokal) – offen |
| D7 | OpenMetadata | **2.0.5**, Push via SDK/REST (kein Airflow), Bot-JWT; Profile nur auf Tables → Stats auf Iceberg-Tabellen; Inventar über Glossary/Tags/Custom Properties |
| D8 | Umgang mit v1-Topics | Read-only weiter unterstützen, Migration per Replay in v2 |
| D9 | Werte-Kompression über zstd hinaus | **Gemessen:** 2,18 B/Wert reicht vorerst; quantisierte Integer-Kodierung (< 1 B/Wert) als Option für eine spätere Schemaversion |
| D10 | Wire-Format-Default | `SELF_DESCRIBING` (Avro Single-Object-Encoding), da KafScale keine Registry mitbringt |
| D11 | Zeitbasiertes Replay | Eigener Episoden-Index in Iceberg (`tsx.episode_index`) statt `offsetsForTimes` |
| D12 | Smart Topics | **Entschieden (2026-10-12):** Python-Training zuerst (ONNX/JSON, Scoring in Java), MLflow als Registry, Modell je Serie + föderiertes globales Modell (Wayang-Ansatz), Umsetzung als P7 nach P6 |
| D13 | Doku | **Entschieden (2026-10-12):** User-Manual englisch; SaaS-Stack bleibt (als künftiger Personal Service Layer); Module/Doku außerhalb des Builds archiviert |
| D14 | P8: Methodenarchitektur | **Vorschlag:** Screen-then-Confirm – billiges Screening (CC/ECA/Kämpf-Signifikanz) im Stream (Java), kausale Bestätigung (PCMCI+/mTE/Hawkes) im Batch (Python); Kreuzkorrelation immer als Baseline |
| D15 | P8: Lizenzen | **Vorschlag:** GPL-Bibliotheken (tigramite, JIDT, IDTxl) nur hinter Prozessgrenze; Kämpf-Maße und ECA als Clean-Room-Implementierung unter Apache 2.0 |
| D16 | P8: Quantenrechnen | **Vorschlag:** klassische Solver sind Standard; Quantenbackends nur als Messobjekt mit vorab festgelegtem Benchmarkprotokoll (kein belegter Vorteil, Stand 2026) |
| D17 | P8: Reihenfolge | **Vorschlag:** Benchmark mit Ground Truth zuerst (P8.0/P8.1), Quanten zuletzt (P8.5) |
| D18 | P9: Atomvertrag | **Vorschlag:** MVC+P (Dynamik und Policy getrennt), Avro-Schema je Atomtyp, Atomkatalog als Standardbauteile, Schaltplan-YAML je Anlage |
| D19 | P9: Ausführung | **Vorschlag:** geschichtetes BSP, Basistakt 1 min, Mehrrate als ganzzahlige Vielfache, deterministisch, Barriere-Topic |
| D20 | P9: Einbettung | **Entschieden (2026-10-12):** beide Ausführungsvarianten gleichrangig – eng als ein Prozess (T) und lose über Smart Topics (L); alle drei Betriebsarten in beiden; Vergleich als Forschungsfrage (P9.6) |
| D21 | P9: Datenqualität | **Vorschlag:** Herkunftsleiter mit Taint-Propagation; jede KPI zeigt ihren Messanteil |
| D22 | P9: Vorgehen | **Vorschlag:** Stufen A–D mit Wertschranken; Stufe A (Daten & Potenzial) ohne Simulation |
| D23 | P9: Sprache | **Vorschlag:** Kern bleibt Java; Edge-Sammler dürfen Go/Rust sein; kein Kern-Neubau vor Schranke B |
| D24 | P9: Kosimulation | **Vorschlag:** Screening (mosaik, HELICS, FMI 3.0) vor dem Bau eines eigenen Runners |

> **Hinweis:** Die Phasenreihenfolge folgt der Gap-Analyse
> ([`03-GAP-ANALYSE.md` §10](../docs/datamodel-2026/03-GAP-ANALYSE.md)), ergänzt um P7 (Smart Topics),
> P8 (Systemrekonstruktion & Optimierung) und P9 (Digitaler Zwilling Energieverbund).

## Risiken

- **Legacy-Module (2.x)** im Build (`opentsx-core`, `-connectors`, `-lg`, `-flink-core`, `-data`) → strikt getrennt, Brücke nur in eine Richtung (`opentsx-legacy-bridge`).
- **Scheinlinks durch Autokorrelation/Langzeitkorrelation** (P8) → Surrogate, die Langzeitkorrelationen erhalten, FDR, Ground-Truth-Benchmarks, CC-Baseline.
- **GPL-Kontamination** (tigramite, JIDT, IDTxl) → Prozessgrenze, Clean-Room-Implementierungen.
- **Quanten-Hype** → nur Messobjekt mit festem Protokoll; „kein Vorteil“ ist ein gültiges Ergebnis.
- **Identifizierbarkeit/Nicht-Stationarität** bei Rekonstruktion und Zwillingen → Regime-Segmentierung (P5), gezielte Anregung im Simulator.
- **`offsetsForTimes` setzt monotone CreateTime je Partition voraus** – bei Out-of-Order-Produktion ist Zeit-Replay unscharf → Replay liest ab gefundenem Offset und filtert, Iceberg als präziser Index.
- **Zwilling größer als sein Nutzen** (P9) → Stufen mit Wertschranken; Stufe A ohne Simulation; Screening bestehender Kosimulationswerkzeuge vor Eigenbau.
- **Datenzugang hängt von Partnern ab** (Elektrolyseur, Vermarkter, Netzbetreiber-Zähler) (P9) → Herkunftsleiter mit Schätzern/Profilen, damit der Zwilling auch ohne diese Daten plausibel rechnet und den Messanteil ausweist.
- **Algebraische Schleifen** in der Leistungsbilanz (P9) → geschichtetes BSP mit Bilanzknoten im selben Schritt; Zyklen nur über Schrittgrenzen.
- **OpenMetadata-Last** bei Statistik pro Serie → Aggregation & Rate-Limit.
- **Schema-Drift** zwischen Java und Python → gemeinsame `.avsc` + geteilte Testvektoren.

## Offene Punkte / Backlog (vollständig, Stand 2026-10-12)

Alles, was aus den Phasen, dem Doku-Refresh und der Deep Research offen ist – nichts bleibt liegen.

**Entscheidungen beim Owner**
- [ ] **Credentials:** Confluent-Cloud-Key in `config/private/ccloud.props` (trotz `.gitignore` versioniert), zuvor auch in `SECURITY.md` und archivierten Notebook-Ausgaben → Key rotieren; Datei aus dem Index nehmen und ggf. Historie bereinigen.
- [ ] D6 Iceberg-Catalog/Object Store (REST + MinIO) final festlegen.
- [ ] D14–D17 (P8-Vorschläge) bestätigen.
- [ ] ER1: Bereitstellung anonymisierter/synthetischer Content-Interaktionsdaten (P8.x).
- [ ] D18, D19, D21–D24 (P9-Vorschläge) bestätigen.

**Energieverbund / Projektumfeld (P9, organisatorisch – außerhalb des Codes)**
- [ ] Zählerdaten: Umsetzer und Verkabelung am LWL-Abschluss beauftragen (Modbus der Netzbetreiber-Zähler; freie Faserpaare vorhanden), Registerkarten der Zähler beschaffen.
- [ ] Datenaustausch mit dem Elektrolyseurbetreiber vereinbaren: Parkrechner-Daten über IEC 104 gegen Betriebsdaten (Last, Zustand, Leitungsdruck, Abnahme).
- [ ] Prognose- und Fahrplandaten beim Direktvermarkter anfordern (für den PPA-Schattenbetrieb vor der Umstellung des Messkonzepts).
- [ ] Schnittstellenbeschreibung (Swagger/OpenAPI) der Leitsystem-API beschaffen.
- [ ] Abstimmung zum konkreten Aufsatzpunkt (wo werden Daten eingespielt, Hardware/VPN) – geplant für die kommende Woche.
- [ ] Bewertung Direktleitung/Direktbelieferung (technische Machbarkeit hinter getrennten Netzanschlüssen) als Eingang für Schaltplan-Vorlagen (P9.5).

**Repository & Open Source (TASK-010)**
- [ ] LICENSE (Apache 2.0, wie in Build und Python-Paket deklariert), CONTRIBUTING, CODE_OF_CONDUCT, SECURITY.md (Policy), CHANGELOG.
- [ ] Python-Paket auf PyPI veröffentlichen (README nennt bislang nur lokale Installation).
- [ ] `sample_data/` und `data/sample_datasets/` konsolidieren (gleiche Dateinamen, anderer Inhalt; von `opentsx-core`-Demos referenziert).
- [ ] `config/cpl*.props` (private LAN-Hosts) durch eine localhost/KafScale-Vorlage ersetzen; `opentsx.properties.example` an v2-Module anpassen.
- [ ] `tests/validation/*.java` und `bin/run_all_tests.sh` in einen Maven-Testpfad überführen oder archivieren; Java-Beispiele in `exercises/` gegen die echte 2.x-API prüfen.
- [ ] `opentsx-app-tools`: sshd-core 0.8.0 ablösen oder Modul archivieren (siehe Phase 0).

**Fehler & Qualität**
- [ ] Event-Synchronisation (Python, ggf. Java `ESCalc`): Q > 1 durch globales `tau_max` → lokales τ nach Quiroga (vorgeschlagener separater Task; in P8.1 mit Q_rand-Kalibrierung verbunden).
- [ ] Avro 1.12.2+ mit `SERIALIZABLE_PACKAGES`.
- [ ] Smoke-Test gegen echten OpenMetadata-2.0.5-Server (docker compose, nightly) und gegen Iceberg-REST-Catalog + MinIO in CI.
- [ ] Records/s-Messung gegen KafScale mit echtem S3 (bisher nur In-Memory-S3).

**Laufzeit & Integration**
- [ ] Flink 1.20/2.x-Upgrade; danach Flink-Adapter für `StatsEngine` und P8.1-Streaming-Screening; `TimeSeriesAggregateFunction`/`TimeSeriesAnalysisJob` auf v2 umstellen oder deprecaten.
- [ ] Echte Herstellerexporte als weitere `sources` im Datenpunktkatalog.
- [ ] Matrix-Profile (Motif/Discord) und iSAX-Index (P5-Optionen).

**Recherche-Nacharbeit (aus 06-SYSTEMREKONSTRUKTION-OPTIMIERUNG, als ungeprüft markiert)**
- [ ] Lizenzen von IDTxl, pyunicorn, netrd, causal-learn, Tetrad aus den LICENSE-Dateien verifizieren; Maven-Verfügbarkeit von JIDT.
- [ ] CauseMe- und QOBLIB-Leaderboard-Zahlen, CausalTime-Ergebnisse einholen.
- [ ] Offizieller AWS-Status von IoT TwinMaker; Primärdoku InfluxDB/Timescale/QuestDB/Grafana/Prometheus zu Kausal-/Netzwerkfunktionen.
- [ ] Vergleich ES/ECA vs. Hawkes auf Clickstream-artigen Daten; Hawkes-Modell auf dem Wikipedia-Seitennetz (keine Quelle gefunden → eigener Beitrag).
- [ ] Volltext Müller et al. (IEEE TPWRS 2025, Quanten-Unit-Commitment): Instanzgrößen und Laufzeiten.
- [ ] Repository-Handle der Dissertation (MLU, vermutlich 1981185920/8878) verifizieren.

## Progress Log

- 2026-10-12: **P9.7 simulierte Abrechnungen**: Beispieldaten 2022–2025 mit verdeckten Parametern und realistischen Lücken, `--simulated`, Kalibrierung findet H/η und Geräteskala, meldet Warmwasser als nicht bestimmt.

- 2026-10-12: **P9.7 Haus-Zwilling**: Methode auf Hauseigentümer übertragen (`08-HAUS-ZWILLING.md`), 9 Haus-Atome, Schaltplan `haus-erzgebirge.yaml`, Kennzahlen, Kalibrierung an Abrechnungen mit Identifizierbarkeitsbericht, Demo `bin/opentsx house`; alle Hauszahlen sind Beispielannahmen.

- 2026-10-12: **P9-PoC** `opentsx-twin`: Atomvertrag MVC+P, Schaltplan-YAML, geschichteter BSP-Runner (Variante T) mit Topic-Vertrag, Herkunftsleiter, 9 Bausteine (inkl. Betriebsvorgaben), Schatten/Was-wäre-wenn/Prognose; S1 H2-Kette getestet, Demo `bin/opentsx twin`.

- 2026-10-12: **P8 geschärft** (Kurztitel „Zusammenspiel & Koordinationspotenzial“, Leitfrage, Bewertungsprinzip, Betriebsvorgaben, Methoden als Vorschlag); Infografik-Briefing Fassung 2 mit zwei Sichten (Was/Wie, Warum/Für wen).

- 2026-10-12: **Zwilling als Kern der Roadmap festgelegt**: drei Betriebsarten in beiden Varianten (eng/ein Prozess und lose/Smart Topics, D20); Vergleich als Forschungspaper (P9.6); Wake-Graph-Export auf niedrige Priorität.

- 2026-10-12: **Phase 9 vorgeschlagen** (Digitaler Zwilling Energieverbund, Konzept `07-DIGITAL-TWIN-BSP.md`: Atome mit MVC+P, Schaltplan, geschichtetes BSP, Smart Topics als Einbettung, Stufen A–D); Wake-Ground-Truth in P8.0 präzisiert (Simulationsreferenz); Punkte aus dem Projektgespräch in den Backlog.

- 2026-10-12: **Phase 8 aufgenommen** (Systemrekonstruktion → Simulation → Optimierung, Konzept `06-SYSTEMREKONSTRUKTION-OPTIMIERUNG.md` aus Deep Research mit Bezug auf Kämpf 2017); vollständiger Backlog aller offenen Punkte.
- 2026-10-12: **Doku-Refresh**: neues englisches User-Manual (`docs/user-manual/`), Inventur (`docs/DOCUMENTATION-INVENTORY.md`), Archivierung veralteter Doku und Module außerhalb des Builds (`archive/`), Launcher `bin/opentsx`, Link-Check in CI.
- 2026-10-12: **P5 umgesetzt** (`opentsx-retrieval`): Segmentierung, Pattern-Engine, Retrieval-API, REST + Python-Client, Leistungskurven-Analyse.
- 2026-10-12: Smart Topics entworfen (`05-SMART-TOPICS.md`); Entscheidungen: Python-Training zuerst, MLflow, Modell je Serie + föderiertes globales Modell (Wayang), Umsetzung als P7 nach P6.
- 2026-10-11: **P4 umgesetzt**: `opentsx-stats` (Streaming-Statistik, Quantil-Sketch, DQ, KafScale-Prozessor mit Checkpoints) und `opentsx-openmetadata` (Metadata-Pool, gegen OM-2.0.5-Schemas validiert); Python `opentsx.stats`.
- 2026-10-11: **Windpark-Domäne umgesetzt** (`opentsx-windpark`): IEC-61400-25-2-Datenpunktkatalog, Normalisierer, Simulator mit Ground Truth, CLI → KafScale/Iceberg. Avro 1.12.1 zentral (Präzisionsfehler in 1.12.0 per Test abgesichert).
- 2026-10-11: **P3 umgesetzt**: Store-SPI + Contract-Suite, Iceberg-Store (+ Index, Log-Sync), OpenTSDB-Adapter, PyIceberg-Reader mit Byte-Gleichheit Java↔Python.
- 2026-10-11: **P2 umgesetzt**: `opentsx-kafka-v2` (TopicLayout, BucketWriter, Episoden-Index, Replay, Katalog), `LegacyBucketImport`; End-to-End-Tests grün gegen KafScale v1.6.0 und Apache Kafka 3.9.1; Speicherkosten gemessen.
- 2026-10-09: **P1 umgesetzt**: `opentsx-model` (Java) + `opentsx.model` (Python) byte-identisch über gemeinsame Testvektoren; Entscheidungen D2 (`timestamp-micros`) und D3 (xxHash64) bestätigt; Legacy-Brücke; Schema-Historie mit Kompatibilitätsprüfung.
- 2026-10-09: **P0 umgesetzt** (siehe Phase 0). KafScale-Messung: Header & CreateTime bleiben erhalten; `offsetsForTimes`, idempotenter Producer, Standalone-Commits und Gruppen-Wiederbeitritt funktionieren nicht (Ursachen im Conformance-README).
- 2026-10-09: Gap-Analyse (Build, Tests, Analytik, Windpark, KafScale v1.6.0, OpenMetadata 2.0.5) → `03-GAP-ANALYSE.md`; Entscheidungen D1/D4/D5/D7 eingearbeitet.
- 2026-10-09: Analyse des Bestandsmodells, Zielmodell-Entwurf und Phasenplan erstellt (dieses Dokument + `docs/datamodel-2026/`).
