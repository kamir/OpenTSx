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
- [ ] Toolchain: gesamtes Projekt auf `maven.compiler.release=17` (CI: JDK 17 + 21); nicht migrierbare Legacy-Module nach `archive/`. Avro 1.11.x, kafka-clients 3.x, Flink 1.18+/1.20 für neue Module.
- [ ] Sofort-Fixes aus Analyse §3 (Label `"123"`, quadratische Zeitstempel, gemischte Formate auf `OpenTSx_Episodes`), da sie Testdaten verfälschen.

### Phase 1 – Modell v2 & Serde (`opentsx-model`, `opentsx-serde`)
- [ ] Avro-Schemas: `SeriesKey`, `Observation`, `Episode`, `EpisodeSummary`, `Provenance`, `BucketManifest`, `SeriesStats`, `PatternMatch`.
- [ ] Code-Generierung nach `target/generated-sources` (nicht mehr eingecheckt).
- [ ] `SeriesId`-Berechnung (kanonische Tag-Sortierung + Hash) inkl. Testvektoren, identisch in Java & Python.
- [ ] `OpenTsxSerde` mit Modi `REGISTRY` und `SELF_DESCRIBING` (Avro Single-Object-Encoding), Auto-Erkennung beim Lesen; Flink-`DeserializationSchema` und Python-(fastavro-)Gegenstück.
- [ ] Time-Encodings `REGULAR`, `IRREGULAR_DELTA` + Property-basierte Roundtrip-Tests.
- [ ] Schema-Kompatibilitätscheck in CI (`SchemaCompatibility`, BACKWARD_TRANSITIVE).
- [ ] v1-Schemas deprecaten; Konflikt `org.opentsx.data.model.Event` dokumentieren/auflösen.

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
| D2 | Zeitpräzision | `timestamp-micros` (vorgeschlagen) |
| D3 | SeriesId-Hash | xxHash64 über kanonischen String, hex (vorgeschlagen) |
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

- 2026-10-09: Gap-Analyse (Build, Tests, Analytik, Windpark, KafScale v1.6.0, OpenMetadata 2.0.5) → `03-GAP-ANALYSE.md`; Entscheidungen D1/D4/D5/D7 eingearbeitet.
- 2026-10-09: Analyse des Bestandsmodells, Zielmodell-Entwurf und Phasenplan erstellt (dieses Dokument + `docs/datamodel-2026/`).
