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
- [ ] ADRs unter `EVOLUTION/DECISIONS/` für die offenen Entscheidungen (s.u.).
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
(geplant) opentsx-smart       Smart Topics (P7)
```

## Entscheidungen (Stand 2026-10-09)

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

> **Hinweis:** Die Phasenreihenfolge wurde durch die Gap-Analyse überarbeitet – maßgeblich ist
> [`docs/datamodel-2026/03-GAP-ANALYSE.md` §10](../docs/datamodel-2026/03-GAP-ANALYSE.md) (P0–P6, inkl. Windpark-Domäne & Simulator).

## Risiken

- **Java-8-Altbestand** vs. moderne Abhängigkeiten → strikt getrennte Module, Brücke nur in eine Richtung.
- **`offsetsForTimes` setzt monotone CreateTime je Partition voraus** – bei Out-of-Order-Produktion ist Zeit-Replay unscharf → Replay liest ab gefundenem Offset und filtert, Iceberg als präziser Index.
- **OpenMetadata-Last** bei Statistik pro Serie → Aggregation & Rate-Limit.
- **Schema-Drift** zwischen Java und Python → gemeinsame `.avsc` + geteilte Testvektoren.

## Progress Log

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
