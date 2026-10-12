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
- [x] `opentsx-app-tools` nach `archive/modules/` verschoben (sshd-core 0.8.0, keine Abhängigen).
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
- [ ] P7f (Vorschlag, [`11-SMART-TOPIC-PROZESSOR-DEVOPS.md`](../docs/datamodel-2026/11-SMART-TOPIC-PROZESSOR-DEVOPS.md)): **Go-TSx-Prozessor** auf Basis der KafScale-Processor-Vorlage (S3-Segmente, Lease je Partition) mit deklarativem DAG `opentsx.dag.v1`; Go-Stream-Kern (Dekodieren, Normalisieren, Episoden, Welford/DDSketch, DQ, inkrementelle Segmentierer, Resampling, Scoring reiner Go-Modelle, `train.trigger`) mit Go↔Java-Konformität über gemeinsame Testvektoren; Java-Sidecar für Mustersuche/Zwilling; Replay-Tests in CI; Schattenbetrieb und Umschalten; Rand-Betrieb (Pi, Labor-PC) mit Checkpoint-Datei.
- [x] P7f-Betrieb (DevOps, heutiger Java-Stand): [`docs/devops/`](../docs/devops/README.md) mit Setup-Plan, Sizing-Plan (Formel + Rechenbeispiele, 2,18 B/Wert), Demos und Abgrenzung heute/geplant; Skripte `deploy/k3s/00…90`, Kustomize (Basis, Jobs, Komponenten hostnetwork/lake/ingress), Images `opentsx/tools` (JRE, alle Befehle) und `opentsx/studio` (Seite im Build erzeugt), Hilfswerkzeug `kafscale-check` (advertised addresses, Topic-Anlage); CI rendert die Overlays. Offen: erster Lauf auf echtem k3s gegen KafScale, Lake-Pfad (S3FileIO, REST-Katalog) Ende-zu-Ende, Messungen aus dem Sizing-Plan.
- [ ] P7d: Föderiertes globales Modell je Target (Wayang-Ansatz: lokale Trainings je System zahlen ein, keine Rohdaten bewegen).
- [ ] P7e (Vorschlag): Personal Service Layer auf dem SaaS-Stack – Control Plane für eigene Smart Series (`05-SMART-TOPICS.md` §11).
- [x] P7e Studio-Prototyp ([`09-STUDIO-LABVIEW-STEUERPULT.md`](../docs/datamodel-2026/09-STUDIO-LABVIEW-STEUERPULT.md)): Statistik, Prognose & Was-wäre-wenn, Node-Graph; statische Seite `docs/prototypes/smart-topics-studio.html` aus `UiExport` + `tools/build_studio.py`.
- [ ] P7e Studio produktiv: Live-Daten über KafScale, Statistik aus P4, Prediction-API aus P7, Node-Graph bearbeitbar (Schaltplan mit Validierung), Eingriffe mit Freigabe-Workflow, zusammengesetzte Bausteine.
- [x] Studio allgemein: `StudioScenario` (Schaltplan, Referenz, Alternativen, Prognosen, Smart Metrics, Kennzahlen) + `StudioExport`; Oberfläche mit Szenario-Auswahl ohne Sonderfälle.
- [x] Haus- und Boot-Szenario als `StudioScenario` eingehängt (`HouseStudioScenario`, `BoatStudioScenario`, abgeleitete Kennzahlen über `derivedKpis`).
- [ ] LabVIEW-Brücke (09 §4/§4a): Abbildungsdatei Sensor ↔ Smart Topic ↔ Aktor; JSON-Verträge `opentsx.smartmetric.v1`, `opentsx.command.v1`, `opentsx.command-ack.v1` als JSON-Schema + Normalizer + Tests; Feedback-Topic `tsx.<domain>.commands.v1` mit Quittungen, Spiegelung in infinimesh `desired`; Publisher-/Kommando-VIs (VIPM, BSD/MIT); Test Client-Zertifikat-TLS LabVIEW ↔ infinimesh. *(TDMS-Import zurückgestellt.)*

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
- [x] Generisches **Modbus-Modul** (TCP/RTU), Registerkarten als YAML (Adresse, Typ, Skalierung, Einheit, OBIS), Pollintervall → `RawReading` → Normalizer → Episoden. – *`opentsx-modbus`: Modbus-TCP-Client nur mit JDK (MBAP, RTU über TCP mit CRC16), Registerkarte `opentsx.modbus.registermap.v1`, Blockbildung ≤ 125 Register, Zählerüberlauf/-reset als Qualitätscode, In-JVM-Zählersimulator, `bin/opentsx modbus`. Offen: echte Registerkarten (Beispielkarte ist markiert), serielles RTU direkt, KafScale-Senke im CLI.*
- [ ] **IEC 60870-5-101/-104**-Adapter für die Parkrechner-Schnittstelle (Datenaustausch mit dem Elektrolyseurbetreiber, beide Richtungen).
- [ ] Elektrolyseur-Betriebsdaten (Last, Zustand, Leitungsdruck, Abnahme) über die vereinbarte Schnittstelle (104, OPC UA oder Export) als Quelle `REPORTED`.
- [x] **DWD-Sammler** (`opentsx-dwd`, nur JDK): MOSMIX_L-Prognosen (KMZ/KML: FF, DD, TTT, PPPP, N, Rad1h, RR1c; Ausgabezeit als `forecast.reference_time`, Herkunft FORECAST) und CDC-Messungen (10 min/stündlich, `-999`, UTC, Herkunft MEASURED) → v2-Serien/Episoden; Stationsauswahl je Standort (Haversine, MOSMIX-Katalog in Grad.Minuten, CDC-Stationsliste); Bindung an Wetter-Atom (Haus) und Solar (Boot), CSV für `house --weather` und `boat --data`; `bin/opentsx dwd stations|mosmix|obs|house`. Parser gegen Live-Abruf geprüft. Offen: zeitgesteuerter Abruf (→ KafScale `tsx.weather.*`), ICON-Gitter, deutschlandweite Vorhaltung, Wind → Leistungskurve.
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
- [x] Avro-Schemas je Atomtyp für Zustand und Ports: generiert aus dem Atomkatalog (`AtomSchemas`, `twin/avro/`, `bin/opentsx twin-schemas`), Umschläge `PortMessage`/`Barrier`/`Intervention`, `state_extra` für parameterabhängige Zustände; Rundlauf aller Szenario-Atome in Java, Lesetest in Python (fastavro).
- [x] Schaltplan-YAML (`TwinPlan`, Beispiel `twin/h2-chain.yaml`), Validierung (Ports, Art/Einheit, eine Quelle je Eingang), Rückkopplungen automatisch über die Schrittgrenze; Plan-Hash in der Lauf-Id. *(Lineage nach OpenMetadata offen.)*
- [x] **Variante T (eng, ein Prozess):** `TwinRunner` mit geschichtetem BSP (Phasen Quellen → Policy → Anlagen → Bilanz → Flüsse → Views), Basistakt 1 min, Mehrrate (`every`), Ereignisse mit exaktem Zeitpunkt, Snapshots zum Fortsetzen; bitgleiche Wiederholung über verketteten Zustands-Hash.
- [x] **Variante L (lose, Smart Topics):** *umgesetzt als `LooseRunner` (eigene Uhr je Atom, Kopplung über `PortMessage`, Veraltung `s` von BSP über SSP bis async, simulierte Latenz/Jitter/Ausfälle, Prozessorgruppen und Kolokalisierung von Rückkopplungsschleifen); Messungen in 07 §10a; Bindung an KafScale offen.* Atomtyp als Smart-Topic-Prozessor (Instanzen als Schlüssel), Kopplung über Port-Topics, Ereigniszeit-Watermarks mit einstellbarer Veraltung (BSP ↔ SSP ↔ asynchron), Bilanzknoten als eigener Prozessor mit Ausgleichsschritt; Atome können bei verschiedenen Eigentümern laufen.
- [x] Derselbe Atomcode läuft unverändert in T und L (gemeinsamer Atom-, Schaltplan- und Topic-Vertrag); Konformitätstest: L mit Veraltung 0 = T bitgleich.
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

**P9.8 – Boot (Etap 22): Insel-Energiesystem** – Konzept: [`10-BOOT-ZWILLING.md`](../docs/datamodel-2026/10-BOOT-ZWILLING.md)
- [x] Konzept mit Annahmen (Powerstation ~1 kWh, 2 × 100 Wp, Landstrom; Laptops, Pixel 10, Starlink Mini, Raspberry Pi, UKW), Nutzungsmodi, KPIs, Hebel, Abbildung Boot ↔ H2-Kette ↔ Haus.
- [x] Bausteine `boat-mode`, `solar`, `shore-power`, `device` (×6), `power-station` (Paket `org.opentsx.twin.boat`); Schaltplan `twin/boot-etap22.yaml` (15 min, 14 Tage).
- [x] Demo `bin/opentsx boat`: Schatten des synthetischen Törns, Was-wäre-wenn (+100 Wp, Starlink 3 h/Tag, 2 kWh, Landstrom jeden 3. Tag, Laptops mittags laden, Wechselrichter, Starlink Standard, Wochenende) als Δ Alternative − Referenz (modelliert).
- [x] CSV-Import echter Betriebsdaten (`BoatData`, Vorlage `twin/bootsdaten-vorlage.csv`, Herkunft MEASURED/REPORTED) und Kalibrierung per Kleinste-Quadrate (`Calibration`, `--calibrate`); Tests für Bilanz, Grenzen, Determinismus, Was-wäre-wenn, Import, Kalibrierung.
- [x] Simulierte Betriebsdaten (`SimulatedLogs`, `twin/beispiel/boot-logs-simuliert.csv`: verdeckte Parameter, Bewölkung, Starlink-Pause, 5-min-Export, Rauschen, Lücken) und `bin/opentsx boat --simulated`; Kalibrierung findet die verdeckten Werte auf ~1 % wieder.
- [ ] Echte Daten des Eigners (Powerstation-Export, Starlink, Logbuch) unter `config/private/bootsdaten.csv` einspielen; Annahmen aus §2 ersetzen.
- [ ] Raspberry Pi als Edge-Publisher (`opentsx.smartmetric.v1` über MQTT → infinimesh → KafScale); Rückweg nur mit Freigabe.
- [ ] Szenario im Studio (generischer Export über `BoatDemo.plan/observed/reference/alternatives`).

**P9.9 – Anlage erkunden: visuelles Inventar & Wartung (Studio-Ansicht 4)** – Konzept: [`12-EXPLORATIVES-UI-INVENTAR.md`](../docs/datamodel-2026/12-EXPLORATIVES-UI-INVENTAR.md)
- [x] E0: Konzept (Ebenen Karte/Grundriss/Decksplan, Level-of-Detail, Hover-Karte, Datenblatt, Bildherkunft, Normbezüge RDS-PP/IEC 61400-25/ZEUS/ISO 14224, Wartungskopplung, infinimesh-Anbindung) und statischer Prototyp `docs/prototypes/anlagen-explorer.html` für Windpark, Haus und Boot mit Atom-Ids der Schaltpläne; Beispielinventar `twin/inventar-beispiel.yaml` (`opentsx.inventory.v1`, erfundene Werte).
- [ ] E1: `opentsx.inventory.v1` als JSON-Schema; Validierung gegen den Schaltplan (Atom existiert, Id-Präfix = Twin-Id); Inventar im `StudioExport`; Reiter „Anlage erkunden“ im Studio; `tools/build_explorer.py` (analog `build_studio.py`).
- [ ] E1: Ablage – Iceberg `inventory.assets/media/documents` (versioniert), Objektspeicher mit SHA-256, Abbildung in OpenMetadata klären (kein Entitätstyp für physische Anlagen bekannt, zu prüfen).
- [ ] E2: Livewerte aus Shadow/KafScale und infinimesh (`reported`, online/offline, Veraltung), Zeitregler über Replay/Iceberg-Zeitreise, Sprünge zu Statistik und Node-Graph.
- [ ] E3: Wartungs-Topic `tsx.<domain>.maintenance.v1` (`opentsx.maintenance.v1`, deterministische `eventId`), Auftragsvorschläge aus Alarmen, Intervallen und Gerätestatus, Aufträge als Eingriffe im Zwilling (Verfügbarkeit), Was-wäre-wenn „Wartung verschieben“, CSV-Import/-Export.
- [ ] E3: Kommando-Dialog nur mit Freigabe (`opentsx.command.v1` → infinimesh `desired`, `commandable: false` für Sicherheitsgeräte).
- [ ] E4: Orthofoto-Grundkarten (Lizenzen je Land prüfen), 360°-Panoramen, glTF-Modelle; CMMS-Anbindung (z. B. openMAINT; SAP PM im Unternehmensumfeld) über API.
- [ ] Recherche-Nacharbeit: RDS-PP-Anwendungsrichtlinie Wind (Dokumentnummer, Codes), FGW TR 7/ZEUS (Schlüsselaufbau, Lizenz), ISO-14224-Klassen für Haus/Boot, Lizenz und API von openMAINT, Namespace-/Gerätehierarchie und Online-Status in `infinimesh/core`.

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
opentsx-modbus                Modbus-TCP/RTU-über-TCP (nur JDK), Registerkarten (YAML), Poller, Normalisierer, Zählersimulator
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
| D25 | P7f: Stream-Laufzeit | **Vorschlag:** Go-TSx-Prozessor (KafScale-Processor-Muster) für die Datenebene; kein Kafka Streams (braucht Compaction/Transaktionen); Flink nur für große Joins |
| D26 | P7f: Algorithmen in Go | **Vorschlag:** nur der Stream-Kern mit gemeinsamen Testvektoren; Mustersuche, Zwilling, Kausalanalyse bleiben Java/Python |
| D27 | P7f: DAG-Spezifikation | **Vorschlag:** `opentsx.dag.v1` (YAML), gehasht, von Go und Java gelesen, im Studio bearbeitbar |
| D28 | P7f: Training | **Vorschlag:** nie im Strom; Trigger → Python/MLflow → `model-events` → Austausch im laufenden Betrieb |
| D29 | P7f: Semantik | **Vorschlag:** at-least-once + deterministische Ids = effektiv genau einmal; Ereigniszeit mit Revisionen |
| D30 | P9.9: Anker des Inventars | **Vorschlag:** Asset-Id = Twin-Id/Atom-Id (+ Unterobjektpfad); keine zweite Anlagenstruktur |
| D31 | P9.9: Bildpolitik | **Vorschlag:** echte Fotos bevorzugt; Herkunft, Lizenz und `reality` je Medium; Illustrationen immer gekennzeichnet |
| D32 | P9.9: Ablage | **Vorschlag:** Inventar-YAML als Eingabe; Iceberg-Tabellen (versioniert) + Objektspeicher mit SHA-256; OpenMetadata als Katalog |
| D33 | P9.9: Wartung | **Vorschlag:** leichtes Tracking als Ereignisprotokoll `tsx.<domain>.maintenance.v1`; CMMS bleibt führend, wo vorhanden; Aufträge = Eingriffe im Zwilling |
| D34 | P9.9: Kennzeichen | **Vorschlag:** RDS-PP, IEC 61400-25, ZEUS optional für Wind; ISO 14224 generisch; keine Pflicht für Haus/Boot |
| D35 | P9.9: infinimesh | **Vorschlag:** Gerätezustand aus infinimesh, Bedeutung aus dem Inventar; Kopplung über `{namespace, device}`; Kommandos nur mit Freigabe |
| D36 | P9.9: Darstellung | **Vorschlag:** 2D (SVG/Canvas, Kacheln) zuerst, 3D erst in E4 |
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
- [ ] D30–D36 (P9.9 Explorer, Inventar, Wartung) bestätigen; echte Fotos/Datenblätter je System liefern (privat: `config/private/`).

**Energieverbund / Projektumfeld (P9, organisatorisch – außerhalb des Codes)**
- [ ] Zählerdaten: Umsetzer und Verkabelung am LWL-Abschluss beauftragen (Modbus der Netzbetreiber-Zähler; freie Faserpaare vorhanden), Registerkarten der Zähler beschaffen.
- [ ] Datenaustausch mit dem Elektrolyseurbetreiber vereinbaren: Parkrechner-Daten über IEC 104 gegen Betriebsdaten (Last, Zustand, Leitungsdruck, Abnahme).
- [ ] Prognose- und Fahrplandaten beim Direktvermarkter anfordern (für den PPA-Schattenbetrieb vor der Umstellung des Messkonzepts).
- [ ] Schnittstellenbeschreibung (Swagger/OpenAPI) der Leitsystem-API beschaffen.
- [ ] Abstimmung zum konkreten Aufsatzpunkt (wo werden Daten eingespielt, Hardware/VPN) – geplant für die kommende Woche.
- [ ] Bewertung Direktleitung/Direktbelieferung (technische Machbarkeit hinter getrennten Netzanschlüssen) als Eingang für Schaltplan-Vorlagen (P9.5).

**Repository & Open Source (TASK-010)**
- [x] LICENSE (Apache 2.0, wie in Build und Python-Paket deklariert) + NOTICE, CONTRIBUTING, CODE_OF_CONDUCT (Contributor Covenant 2.1), SECURITY.md (Policy), CHANGELOG.
- [ ] Python-Paket auf PyPI veröffentlichen (README nennt bislang nur lokale Installation).
- [ ] `sample_data/` und `data/sample_datasets/` konsolidieren (gleiche Dateinamen, anderer Inhalt; von `opentsx-core`-Demos referenziert).
- [x] `config/cpl*.props` und `config/mdc_c1.props` (private LAN-Hosts) entfernt, ersetzt durch die localhost/KafScale-Vorlage `config/opentsx-local.props.example` (lokale Kopie `config/opentsx-local.props` git-ignoriert); `opentsx.properties.example` an v2-Module angepasst.
- [ ] Weitere Profile mit privaten Hosts in `opentsx-lg/src/main/resources/config/` (`cpl.props`, `mdc_c1.props`, `mdc_local_link.props`, im Docker-Assembly referenziert), `opentsx-lg/src/main/k8s/krake-app/` und hartkodierte Schema-Registry-Adressen in `FWL_TSA_*Application.java` bereinigen.
- [ ] `tests/validation/*.java` und `bin/run_all_tests.sh` in einen Maven-Testpfad überführen oder archivieren; Java-Beispiele in `exercises/` gegen die echte 2.x-API prüfen.
- [x] `opentsx-app-tools` archiviert (siehe Phase 0).

**Fehler & Qualität**
- [ ] Event-Synchronisation (Python, ggf. Java `ESCalc`): Q > 1 durch globales `tau_max` → lokales τ nach Quiroga (vorgeschlagener separater Task; in P8.1 mit Q_rand-Kalibrierung verbunden).
- [ ] Avro 1.12.2+ mit `SERIALIZABLE_PACKAGES`.
- [ ] Smoke-Test gegen echten OpenMetadata-2.0.5-Server (docker compose, nightly) und gegen Iceberg-REST-Catalog + MinIO in CI.
- [ ] Records/s-Messung gegen KafScale mit echtem S3 (bisher nur In-Memory-S3).

**Major-Upgrades (bewusst als Migration; Dependabot ignoriert Majors und die unten genannten Bremsen)**
- [ ] Avro 1.12.2+: Klassen-Freigabe (`ClassSecurityValidator`) für das v2-Modell **und** die Legacy-2.x-Klassen (`org.opentsx.data.model`, schon beim Bauen von Records per Builder nötig); Legacy-Module hängen nicht an `opentsx-model` → Freigabe im Generator-Template oder per Systemeigenschaft in den Startskripten. Befund vom 12.10.: v2 mit `ClassSecurityValidator.setGlobal(composite(...))` lauffähig, Legacy-Bridge-Test scheitert ohne Legacy-Freigabe.
- [ ] Iceberg 1.11/1.12: `GenericAppenderFactory` entfernt → `IcebergIO` auf die neue Writer-API umstellen.
- [ ] JFreeChart 1.5 (Legacy-Charts in `opentsx-core`): `org.jfree.ui` entfernt → Charts migrieren oder Legacy-UI archivieren.
- [ ] Confluent 8 (Kafka-4-Clients, gegen KafScale prüfen), JUnit 6, networknt json-schema-validator 3 (API-Änderungen).
- [ ] Frontend: React 19, TypeScript 7, ESLint 10 (Flat Config), Tailwind 4, Vite 8, React Router 7, Plotly 4 – gemeinsam als Migration (Stand 12.10.: `npm ci` scheitert an Peer-Konflikten, `tsc` an veralteten Optionen).

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

- 2026-10-12: **Abhängigkeiten aufgeräumt**: Dependabot-Flut gebündelt (GitHub Actions auf aktuelle Majors, Backend-Pins, Backend auf Python 3.12), Maven-Minor/Patch-Gruppe ohne die vier Bremsen (Avro, Iceberg, JFreeChart, zstd-jni); Majors nur noch als geplante Migration (Backlog „Major-Upgrades“).
- 2026-10-12: **DWD-Sammler** `opentsx-dwd`: MOSMIX_L und CDC-Stationsdaten als v2-Serien/Episoden mit Einheit und Herkunft, nächstgelegene Stationen (Haus Erzgebirge: Marienberg/Fichtelberg, Ostsee: Greifswalder Oie/Arkona), Bindung an Haus- und Boot-Zwilling, CLI `bin/opentsx dwd`; Tests offline mit handgebauten Fixtures, Parser zusätzlich gegen opendata.dwd.de geprüft.
- 2026-10-12: **P9.0 Modbus-Modul** `opentsx-modbus`: Modbus-TCP-Client ohne Fremdbibliothek (MBAP, Transaktions-IDs, Timeouts, Reconnect, Exception-Antworten mit Klartext; RTU über TCP mit CRC16), Registerkarten als YAML mit Validierung (Überlappungen, Typen, OBIS, Herkunft), Blockbildung, Normalisierer → v2-Episoden mit Zählerüberlauf/-reset-Flags, In-JVM-Simulator und `bin/opentsx modbus check|read|poll|simulate`; Beispielkarte eines Netzanschlusszählers bis zum Eintreffen der echten Registerkarten.
- 2026-10-12: **Repository-Hygiene**: LICENSE (Apache 2.0, Volltext) + NOTICE, CONTRIBUTING, CODE_OF_CONDUCT, SECURITY.md (Meldeweg über GitHub Private Vulnerability Reporting), CHANGELOG (Keep a Changelog); private Client-Profile `config/cpl*.props`/`mdc_c1.props` durch `config/opentsx-local.props.example` ersetzt (Fallback in `OpenTSxClusterLink`), Referenzen in Code, Skripten und Doku angepasst; `opentsx.properties.example` auf tatsächlich gelesene Schlüssel und v2-Konfiguration reduziert. Keine Historienbereinigung.
- 2026-10-12: **P9 Variante L** (`LooseRunner`, gemeinsamer Ausführungskern `RunSetup`): Konformität L(`s = 0`) = T bitgleich für alle drei Szenarien und Betriebsarten; Messprogramm `bin/opentsx twin-coupling`; Avro-Schemas je Atomtyp. Befund: Durchsatz begrenzen nur Rückkopplungsschleifen (Formel in 07 §10a), Veraltung in schnellen Regelkreisen verfälscht stark; Entwurfsregel „Schleifen in einen Prozessor, dazwischen BSP“ liefert T-Ergebnis bei voller Fließband-Leistung (H2-Kette ×5,9, Haus ×6).
- 2026-10-12: **Anlage erkunden (P9.9, E0)** (`12-EXPLORATIVES-UI-INVENTAR.md`): vierte Studio-Ansicht als visuelles Inventar auf Karte (Windpark), Grundriss (Haus) und Decksplan (Boot) mit Level-of-Detail, Hover-Karte und Datenblatt; Inventarmodell `opentsx.inventory.v1` am Atom-Anker, Bildherkunft, Normbezüge, Wartungs-Topic `opentsx.maintenance.v1`, infinimesh als Geräteebene; statischer Prototyp `docs/prototypes/anlagen-explorer.html` und Beispielinventar; D30–D36 als Vorschläge.
- 2026-10-12: **Aufräumen**: Sammel-PR #102 nach `master`; SaaS-Abhängigkeiten gebündelt (Vite 7, python-multipart, python-socketio); überholte Dependabot-PRs geschlossen; `opentsx-app-tools` archiviert; README neu positioniert ("from time series to decisions").
- 2026-10-12: **Smart-Topic-Prozessor und DevOps-Konzept** (`11-SMART-TOPIC-PROZESSOR-DEVOPS.md`): Go-Prozessor nach dem KafScale-Processor-Muster mit YAML-DAG, Stream-Kern in Go, Sidecar für Java/Python, Training außerhalb des Stroms, Lebenszyklus (CI-Replay, Schattenbetrieb, Modelltausch, Rand-Betrieb); D25–D29 als Vorschläge.
- 2026-10-12: **P9.8 simulierte Bootsdaten**: Generator `SimulatedLogs`, Beispiel-Logs unter `twin/beispiel/`, Kalibrierung gegen SoC, Ausgangsleistung und Solar (mit Einstrahlung), `bin/opentsx boat --simulated`.
- 2026-10-12: **P9.7 simulierte Abrechnungen**: Beispieldaten 2022–2025 mit verdeckten Parametern und realistischen Lücken, `--simulated`, Kalibrierung findet H/η und Geräteskala, meldet Warmwasser als nicht bestimmt.
- 2026-10-12: **P9.8 Boot-Zwilling** (`10-BOOT-ZWILLING.md`): Etap 22 als Insel-Energiesystem mit 5 neuen Bausteinen, Schaltplan, Demo `bin/opentsx boat` mit Was-wäre-wenn, CSV-Import echter Daten und Kalibrierung gegen gemessenen SoC.
- 2026-10-12: **DevOps-Leitfaden k3s + KafScale** (`docs/devops/`, `deploy/k3s/`, `deploy/docker/`): Setup- und Sizing-Plan, alle Beispiele (Windpark → KafScale, Statistik, Retrieval, OpenMetadata, H2-Kette, Haus, Boot, Studio) als Deployments/Jobs mit Skripten 00–90; Befehle im Image lokal geprüft, Manifeste mit kubeconform validiert; Docker-Build und k3s-Lauf noch nicht ausgeführt.
- 2026-10-12: **Studio-Prototyp und LabVIEW-Konzept** (`09-STUDIO-LABVIEW-STEUERPULT.md`): drei Ansichten mit Zwillingsdaten, LabVIEW/Node-RED/NiFi-Analogie, Kreislauf LabVIEW → MQTT → infinimesh → KafScale → OpenTSx → Freigabe → `desired`, JSON-Vertrag für Edge Smart Metrics.
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
