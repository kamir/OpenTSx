# TASK-011 – Datamodel Refresher 2026

- **Branch:** `opentsx/2026-datamodel-refresher`
- **Status:** 📝 Planung
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
- [x] Toolchain: gesamtes Projekt auf `maven.compiler.release=17`, Versionen nur in `dependencyManagement`, Enforcer (JDK ≥ 17, kein log4j 1.x); Kafka 3.9.1 / Confluent 7.9.1 / Avro 1.11.4; `opentsx-core` + `-data` 65 statt 388 Artefakte.
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
- [ ] Topic-Layout `tsx.<domain>.{observations,episodes,series,buckets,series-stats}.v2` + Provisioning-Tool (ersetzt `TopicsManagerTool` mit hartem Pfad).
- [ ] `BucketWriter` (Manifest OPEN → Episoden → SEALED + Checksum), asynchroner idempotenter Producer, zstd, Header, Record-Timestamp = `tStart`.
- [ ] `ReplayService`: nach Bucket, nach Serien + Zeitbereich (`offsetsForTimes`), Pace ASAP/Echtzeit-Faktor, Sinks Topic/Callback/TSBucket; Verifikation gegen Manifest.
- [ ] `LegacyBridge`: SequenceFile-TSBucket → BucketWriter; `EpisodesRecord` v1 → v2 (Migrations-Replay).
- [ ] Kostenmessung (Bytes/Wert, Records/s) gegen KafScale; Ergebnis in die Doku.
- [ ] `TSOWriter.persistBucket_Kafka`-Stub durch Implementierung ersetzen.

### Phase 3 – Store-SPI, OpenTSDB, Iceberg (`opentsx-store-spi`, `opentsx-store-opentsdb`, `opentsx-store-iceberg`)
- [ ] `SeriesStore`, `EpisodeQuery`, `SeriesSelector`, `StoreCapabilities` (ersetzt leere `TSBucketStore`/`TSO*Interface`).
- [ ] Contract-Test-Suite für alle Adapter (Testcontainers: OpenTSDB, Iceberg REST-Catalog + MinIO).
- [ ] OpenTSDB-Adapter: Validierung metric/tags, HTTP `/api/put` gebatcht, Query → Episoden-Rekonstruktion.
- [ ] Iceberg-Adapter: Tabellen `series`, `episodes` (+ optional `observations`), Partitionierung `days(t_start), bucket(32, series_id)`, Sortierung.
- [ ] Kafka → Iceberg Ingest (Flink-Iceberg-Sink oder Kafka-Connect-Iceberg-Sink) aus `episodes.v2`.

### Phase 4 – Streaming-Statistik & OpenMetadata (`opentsx-stats-flink`, `opentsx-openmetadata`)
- [ ] Flink-Job: keyBy(seriesId), Welford + min/max + Quantil-Sketch + Gap-/Rate-Erkennung; global laufend + Tumbling-Fenster; Episoden-Summaries direkt mergen.
- [ ] Output `series-stats.v2` (compacted); Late-Data-Side-Output.
- [ ] OpenMetadata-Modul: Registrierung Topics (mit Avro-Schema), Iceberg-Tabellen, Pipelines + Lineage; rate-limitierte Profil-/Data-Quality-Updates aus `series-stats.v2`.
- [ ] Bestehenden `TimeSeriesAggregateFunction`/`TimeSeriesAnalysisJob` auf v2-Serde umstellen oder deprecaten.

### Phase 5 – Retrieval-API & Pattern-Matching (`opentsx-retrieval`)
- [ ] 5a: Java-Fluent-API `EpisodeService` (`episodes()`, `episode(id)`, `similarTo(...)`), Store-Auswahl nach Capabilities.
- [ ] 5a: Pattern-Engine – PAA/SAX-Berechnung beim Schreiben (`EpisodeSummary.sax`), MASS für z-ED, LB_Kim/LB_Keogh/DTW-Kaskade.
- [ ] 5a: Synthetischer Benchmark (eingepflanzte Muster, Rauschen, Skalierung) + Recall/Latenz-Report.
- [ ] 5b: Dünne REST-Schicht + Python-Client.
- [ ] 5c (optional): Matrix-Profile Motif/Discord über Buckets; Treffer als Annotationen zurückschreiben.

## Vorgeschlagene Modulstruktur (neu)

```
opentsx-model          Avro v2 Schemas + generierte Klassen + SeriesId
opentsx-serde          OpenTsxSerde (Registry / Single-Object), Kafka-/Flink-Serdes
opentsx-legacy-bridge  TimeSeriesObject/TSBucket/EpisodesRecord <-> v2
opentsx-store-spi      SeriesStore, EpisodeQuery, Contract-Tests
opentsx-store-iceberg  Iceberg-Adapter
opentsx-store-opentsdb (refaktoriert auf SPI)
opentsx-kafka-v2       BucketWriter, ReplayService, Topic-Provisioning
opentsx-stats-flink    Streaming-Statistik
opentsx-openmetadata   Metadaten-/Profil-Publisher
opentsx-retrieval      EpisodeService + Pattern-Engine (+ optional REST)
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
| D9 | Werte-Kompression über zstd hinaus | Erst nach Messung |
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

- 2026-10-09: **P1 umgesetzt**: `opentsx-model` (Java) + `opentsx.model` (Python) byte-identisch über gemeinsame Testvektoren; Entscheidungen D2 (`timestamp-micros`) und D3 (xxHash64) bestätigt; Legacy-Brücke; Schema-Historie mit Kompatibilitätsprüfung.
- 2026-10-09: **P0 umgesetzt** (siehe Phase 0). KafScale-Messung: Header & CreateTime bleiben erhalten; `offsetsForTimes`, idempotenter Producer, Standalone-Commits und Gruppen-Wiederbeitritt funktionieren nicht (Ursachen im Conformance-README).
- 2026-10-09: Gap-Analyse (Build, Tests, Analytik, Windpark, KafScale v1.6.0, OpenMetadata 2.0.5) → `03-GAP-ANALYSE.md`; Entscheidungen D1/D4/D5/D7 eingearbeitet.
- 2026-10-09: Analyse des Bestandsmodells, Zielmodell-Entwurf und Phasenplan erstellt (dieses Dokument + `docs/datamodel-2026/`).
