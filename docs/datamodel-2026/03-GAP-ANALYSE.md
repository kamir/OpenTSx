# OpenTSx – Gap-Analyse: Weg zum Top-Time-Series-Toolkit für den Windpark-Betrieb

> Stand 2026-10-09 · Branch `opentsx/2026-datamodel-refresher`
> Grundlage: Code-Inspektion, lokaler Build (JDK 21 / Maven 3.9), Python-Testlauf, Recherche
> KafScale v1.6.0 (github.com/KafScale/platform @ 7aa2a11) und OpenMetadata 2.0.5.

## 0. Kurzfazit

OpenTSx hat einen **wissenschaftlich wertvollen Algorithmen-Kern** (DFA/MFDFA, RIS, Event-Synchronisation, FFT-Surrogate, LRC-Generatoren) und eine **saubere kleine Python-Basis**. Für das Ziel – *Windpark-Zeitreihen über KafScale erfassen, inventarisieren, normalisieren, simulieren, katalogisieren und analysieren* – fehlt aber fast die gesamte **Daten-Plattform-Schicht**: kein belastbares Datenmodell, keine Asset-/Inventar-Semantik, keine Normalisierung, keine Tests/CI, keine KafScale-taugliche Persistenz, kein Katalog-Anschluss, keine Simulation. Der Build ist durch eine monolithische Root-POM mit ~390 transitiven Artefakten (Hadoop CDH 5, Spark 2.10, log4j 1.x, xstream 1.2) belastet, und im Repo liegen (laut Maintainer unkritische) Demo-Zugangsdaten.

Empfehlung: **Nicht den Altbestand umbauen, sondern daneben einen schlanken „OpenTSx Platform Core“ (Java 17 + Python ≥ 3.10) aufbauen**, der den Algorithmen-Kern über eine Brücke nutzt, und den Altbestand schrittweise in `legacy/` bzw. `archive/` überführen.

---

## 1. Sicherheit & Hygiene

| # | Befund | Ort (nur Pfad) | Aktion |
|---|--------|----------------|--------|
| S1 | Confluent-Cloud-Konfiguration eingecheckt, obwohl `.gitignore` den Pfad ausschließt – laut Maintainer **Demo-Daten, unkritisch** | `config/private/ccloud.props` | Hygiene: durch `*.example` ersetzen, damit Scanner/Contributors nicht irritiert werden |
| S2 | Weitere SASL-Konfigurationen | `opentsx-lg/src/main/docker-compose/config/ccloud.props`, `opentsx-lg/src/main/resources/config/ccloud.props` | prüfen, rotieren, durch `*.example` ersetzen |
| S3 | MQTT-Passwortdatei | `opentsx-lg/src/main/mqtt-example/volume1/docker/mqtt/config/passwd` | durch Beispiel ersetzen |
| S4 | Dev-Default-Secrets im SaaS-Backend | `opentsx-saas-backend/app/core/config.py` | nur via Env, keine Defaults |
| S5 | Bekannte CVEs im Klassenpfad: log4j 1.2.17, xstream 1.2.2, jackson-databind 2.8.1 | Root-`pom.xml` | mit POM-Sanierung (§5) erledigt |

---

## 2. Was ist gut (behalten & aufwerten) — [GUT]

| Bereich | Warum gut | Weiterverwendung |
|---------|-----------|------------------|
| Algorithmen-Kern (Java `algorithms/*`, Python `opentsx/algorithms`) | DFA/MFDFA, RIS, Event-Sync, FFT-Phase-Randomization, Entropie, Granger, Peak-Detection; Python-DFA verifiziert (Weißes Rauschen α≈0.52, Random Walk α≈1.52) | Über Legacy-Brücke auf v2-Episoden anwenden; Python als Referenz-Implementierung |
| LRC-/Surrogat-Generatoren (`LongTermCorrelationSeriesGenerator`, Sinus-/Bucket-Generatoren) | 1/f^β-Rauschen ≈ Turbulenzanteil der Windgeschwindigkeit | Baustein des Windpark-Simulators |
| `opentsx-flink-core` | Flink 1.18, Event-Time, JUnit-5-Tests grün (21 Tests), Processing-Flow-Descriptor (JSON-Schema) | Basis für Stats-/Normalisierungs-Jobs; Serde austauschen |
| Python-Paket | ~2.3k Zeilen, sauber, `pyproject.toml`, `TSProcessor`-Chaining mit `>>` | Wird zur „Jupyter/Pandas/PySpark“-Seite des Platform Core |
| `ConfigManager` | Layered Config + Env-Overrides | Für alle neuen Module übernehmen |
| `opentsx-lg` Latenz-/Load-Tooling + Grafana/Prometheus | Passt zum KafScale-Benchmarking | KafScale-Durchsatz-/Latenztests |
| `sensor_data.csv` (Long-Format `timestamp,sensor_id,location,measurement_type,value,unit`), MQTT-Generator-Config | Richtige Form für Inventar & Normalisierung | Vorlage für Windpark-Kanalmodell |
| Idee **Episode als Array-Payload**, `TSData(t0, dt, values)`, `MarkableTimeSeriesObject` | Richtige Grundideen für günstige Ablage & Annotation | In v2-Modell überführt (siehe 02-ZIELMODELL) |
| Onboarding-/Exercise-Struktur | Gute didaktische Basis | Nach Test-Fix wiederverwenden |

---

## 3. Was ist Mist (aufräumen/ersetzen) — [MIST]

### 3.1 Build & Abhängigkeiten
* **Root-POM deklariert ~60 Dependencies in `<dependencies>` statt `<dependencyManagement>`** → jedes Modul erbt Hadoop CDH 5.11, HBase 1.2, cassandra-all, Flume, Kudu, Spark 2.2 (Scala 2.10!), Mahout 0.9, TensorFlow 1.15. `opentsx-core` zieht **388 Artefakte**.
* 4 SLF4J-Bindings + `log4j-over-slf4j` *und* `log4j` gleichzeitig → Logging nicht deterministisch.
* Kafka-Clients 2.3 vs. Confluent 7.3; Property `confluent.version=7.3.0-css` existiert nicht; Gson doppelt; `maven-compiler-plugin 2.3.2` ohne `--release` (Java-9+-APIs rutschen in „Java 8“-Code).
* **11 Module außerhalb des Reactors, davon 8 nicht baubar** (Nashorn-Import, http-Repos, fehlende Artefakte, falsche Versionen).

### 3.2 Qualitätssicherung
* **Keine CI** (kein `.github/`).
* `opentsx-core` (234 Klassen, ~52k Zeilen): **0 Tests**, keine JUnit-Dependency, stattdessen **123 `main()`-Methoden**.
* `test-report.txt` leer; `bin/run_all_tests.sh` sucht `opentsx-core-2.3-SNAPSHOT.jar` (Projekt ist 3.0.0).
* Python-Tests „grün“, aber ohne Assertions (Tests geben `bool` zurück) – **Scheinsicherheit**.

### 3.3 Architektur & Code
* `TimeSeriesObject` als 2900-Zeilen-God-Class; Swing/AWT in 48 Core-Dateien (GUI im Library-Kern).
* Logging-Wildwuchs: 2186× `System.out`, 182× `printStackTrace`, JUL, slf4j, log4j2.
* Serialisierungs-Bugs (Label `"123"`, quadratische Zeitstempel, Avro+JSON auf demselben Topic, Flink-Wire-Format-Bruch) – siehe 01-ANALYSE §3.
* `TSBucketStore`/`TSO*Interface` leere Stubs; `TSOWriter.persistBucket_Kafka` „NOT YET DONE“.
* Python `from_pandas` verliert Zeitzone und Zeiteinheit hängt von der pandas-Version ab; `TSBucket.apply(parallel=True)` ist sequentiell.

### 3.4 Repo-Hygiene & Doku
* ~190 MB Jars/Tarballs unter `modules/ingest-2-cassandra`, 40 MB PPTX, 10 MB Binary in `opentsx-clusters/...`, 22 `.DS_Store`, 18 `.idea`-Dateien.
* Leere/tote Module: `opentsx-processors`, `opentsx-cv`, `geoqb`, `opentsx-jetson-nano`; doppelte Demo-Ordner, doppelte Docs, `opentsx-ksql` ≙ `opentsx-ksql-udf`.
* Drei Compose-Dateien mit drei Confluent-Versionen, `latest`-Tags; Spark-Notebook-Image ohne PySpark-Code.
* **README verspricht mehr als vorhanden** (Smile ML, Parquet, „pluggable ML“, Kafka in Python).
* Harte Pfade `/Users/mkampf`, `/Users/mkaempf`, `/media/esata/...`.

---

## 4. Was fehlt — [FEHLT]

### 4.1 Datenplattform

| Fähigkeit | Status | Benötigt für |
|-----------|--------|--------------|
| Versioniertes Datenmodell (SeriesKey/Episode/Bucket) | fehlt (Entwurf in 02) | alles |
| **Asset-/Kanal-Inventar** (Park → Turbine → Komponente → Kanal) | fehlt komplett | Inventarisierung, Katalog |
| **Normalisierung** (Kanalnamen, Einheiten, Zeitzonen, Abtastung, Qualitätsflags) | fehlt (keine ZoneId-, keine Unit-Behandlung) | saubere Erfassung |
| Resampling/Alignment unregelmäßiger Reihen, Interpolation, Gap-Handling | fehlt (nur Blockmittel/Binning) | SCADA-10-min-Aggregate, Multivariate |
| Episoden-Segmentierung (Dauer, Länge, **Feature-basiert**) | fehlt | Retrieval, Pattern-Matching |
| KafScale-taugliche Persistenz + Replay + Episoden-Index | fehlt | Ablage, Experimente |
| Iceberg-Tabellen | fehlt | PySpark, OpenMetadata-Profile |
| OpenMetadata-Publisher | fehlt | Metadata-Pool |
| Streaming-Statistik / Data-Quality-Checks | fehlt (Flink sammelt nur Punkte) | Profile, DQ |
| Python: Avro/Kafka/Iceberg/PySpark | fehlt (Extras deklariert, ungenutzt) | Jupyter/Pandas/PySpark-Nutzer |

### 4.2 Analytik (gegenüber einem Top-Tool)

| Vorhanden | Fehlt (Priorität für Windpark) |
|-----------|-------------------------------|
| DFA/MFDFA, RIS, Event-Sync, FFT, Entropie, Granger, Normalisierung, Peak-Detection | **Hoch:** Resampling/Alignment, Gap-Filling, robuste Ausreißer-/Anomalie-Erkennung, Change-Point-Detection, **Power-Curve-Analyse (IEC 61400-12)**, Welch-PSD/Kohärenz (Schwingungen), Kreuzkorrelation, Feature-Extraktion (tsfresh-artig), Similarity-Search (MASS/SAX/DTW) |
| | **Mittel:** STL/saisonale Zerlegung, Matrix-Profile (Motifs/Discords), Clustering von Episoden, Kalman/Zustandsraum |
| | **Später:** Forecasting (`SingleTsARIMATool` auskommentiert, `opentsx-predict` = TF-Hello-World) |

### 4.3 Windpark-Domäne – komplett neu

* **Asset-Modell** angelehnt an IEC 61400-25 (logische Knoten `WTUR`, `WROT`, `WTRM`, `WGEN`, `WNAC`, `WYAW`, `WMET`, `WCNV` …): `Park → Turbine → LogicalNode → Channel`.
* **Kanal-Katalog**: kanonischer Name, Einheit (UCUM), Abtastrate, Signaltyp (analog/Zähler/Status/Alarm), Aggregationsart (10-min mean/min/max/std nach SCADA-Praxis), Plausibilitätsgrenzen.
* **Simulator** (fehlt vollständig): Windfeld (Weibull-Mittelwert + Turbulenz über vorhandenen LRC-Generator + Tagesgang), Leistungskurve, einfaches Wake-Modell (Jensen), Yaw/Pitch, Verfügbarkeit/Alarme/Curtailment, **Sensorfehler-Injektion** (Stuck, Drift, Spikes, Lücken, Zeitversatz) als Ground-Truth für DQ- und Pattern-Tests.
* **Experiment-Steuerung**: Szenario-Definition (YAML) → Simulator → KafScale-Bucket → Replay in Analysen; reproduzierbar über Seed + Manifest.

---

## 5. KafScale – Konsequenzen für das Design (recherchiert, v1.6.0)

| KafScale-Eigenschaft | Auswirkung | Design-Antwort |
|----------------------|------------|----------------|
| **Kein Log-Compaction** | Topics `series.v2`, `buckets.v2`, `series-stats.v2` können nicht „latest-by-key“ sein | Zustand („aktuellster Stand“) liegt in **Iceberg** (und OpenMetadata); Kafka-Topics sind reine Append-Logs; kleine Topics per Full-Scan + Dedup lesbar |
| **ListOffsets ignoriert Zeitstempel** – liefert für alles außer *earliest* das Ende (`cmd/broker/main.go:1696-1704`); `offsetsForTimes()`, Flink `OffsetsInitializer.timestamp()`, Spark `startingTimestamp` springen stillschweigend ans Ende | Zeitbasiertes Replay über Kafka-API unmöglich | **Eigener Episoden-Index**: Indexer-Consumer schreibt `(episodeId, seriesId, tStart, tEnd, bucketId, topic, partition, offset)` nach Iceberg `tsx.episode_index`; Replay = Index-Query → `seek(partition, offset)` |
| **Kein idempotenter Producer, keine Transaktionen** (`enable.idempotence=false` Pflicht; Java-3.x-Default ist `true`!) | At-least-once, Duplikate möglich | `episodeId` (ULID) als Dedup-Schlüssel; Iceberg-Writes per MERGE/Dedup; Manifest-Checksum prüft Vollständigkeit |
| Record-Header: Format (RecordBatch v2) erlaubt sie, **Erhalt nicht dokumentiert**; CreateTime-Erhalt unklar | Header/Timestamps nicht als tragend annehmen | Alles Tragende **im Payload** (bucketId, tStart, Schema-Fingerprint); Conformance-Test prüft Header & CreateTime |
| Kein Schema-Registry | Confluent-Wire-Format nur mit eigenem SR | **Default `SELF_DESCRIBING`** (Avro Single-Object-Encoding + Fingerprint) – keine Infrastruktur nötig; SR optional |
| Compression none/snappy/lz4/zstd | ✓ | `zstd` pro Topic |
| Segmente 4 MB / 500 ms auf S3; wenig Volumen → viele kleine Objekte | Kosten durch kleine Objekte | Episoden statt Punkte, Producer-Batching (`linger.ms`), Live-Punkt-Topics nur wo nötig |
| Latenz-Ziel 200–500 ms, Durchsatz moderat | Kein Echtzeit-Regelkreis | Für Monitoring/Analyse ok; Simulator mit Pace-Steuerung |
| Kein SASL (geplant v2.0), ACL auf `client.id` | Sicherheit über Netzwerk | Netzsegmentierung/TLS-Proxy; Doku |
| Iceberg-Processor-Addon: liest Segmente direkt aus S3, aber **nur JSON-Schemas, kein Avro** | Nicht direkt nutzbar | Eigener Avro-Decoder-Processor (Go-Skeleton vorhanden) **oder** Flink-Iceberg-Sink; Entscheidung nach Benchmark |
| Dev/CI: docker-compose (etcd + MinIO + Broker), Broker-Binary mit embedded etcd + In-Memory-S3; **kein Testcontainers-Modul**; Default-Image zeigt auf private Registry | CI braucht eigenes Setup | `docker-compose.kafscale.yml` mit GHCR-Images, Testcontainers `ComposeContainer`; **OpenTSx-KafScale-Conformance-Suite** |

---

## 6. OpenMetadata – Konsequenzen (recherchiert, 2.0.5)

* Version **2.0.5** (Server, Python-SDK `openmetadata-ingestion==2.0.5.0`, Java-Client `org.open-metadata:openmetadata-java-client:2.0.5`) – **Client und Server gleich pinnen**. Python-SDK braucht **Python ≥ 3.10**.
* Quickstart: MySQL/Postgres + Elasticsearch/OpenSearch + Server (+ optional Airflow-Ingestion, in 2.1 als interner Orchestrator deprecated) – ≥ 6 GB RAM. Für uns reicht **Push via SDK/REST**, Airflow nicht nötig.
* **Kein IoT-/Sensor-/Zeitreihen-Entity** → Windpark-Inventar abbilden über:
  * **Glossary** „Windpark“ (IEC-61400-25-Begriffe: logische Knoten, Kanaltypen),
  * **Classification/Tags** (Park, Turbinentyp, Kanaltyp, Qualitätsstufe),
  * **Custom Properties** (Extension) auf Table/Topic (Turbinen-ID, Hersteller, Nabenhöhe, Koordinaten, Abtastrate, Einheit),
  * **Domain/DataProduct** „Windpark <Name>“.
* **Profile gibt es nur für Tables** (nicht Topics) → Statistiken landen auf **Iceberg-Tabellen** (z.B. `tsx.channels_10min` / `tsx.episodes`) via `PUT /v1/tables/{id}/tableProfile` (+ Custom Metrics); Granularität: aggregiert je Tabelle/Kanaltyp und Zeitfenster, nicht je Event.
* **Data Quality**: eigene TestDefinitions (z.B. *Stuck-Sensor*, *Gap-Rate*, *Plausibilitätsband*, *Power-Curve-Abweichung*) → `POST /v1/dataQuality/testCases/testCaseResults/{fqn}` aus dem Streaming-Prozessor.
* **Topics** ohne Registry: `messageSchema.schemaText` + `schemaType=Avro` selbst pushen.
* **Iceberg-Connector wurde in 1.13 entfernt** → Tabellen per SDK selbst registrieren (CustomDatabase-Service) oder über Trino-Connector.
* **Lineage** `PUT /v1/lineage`: Simulator/Collector → Topic → Flink-Pipeline → Iceberg-Table.
* Flink-Pipeline-Connector existiert (bis Flink 1.19 getestet), Reife in Doku widersprüchlich → Pipelines anfangs selbst registrieren.

---

## 7. Episoden-Segmentierung (Anforderung aus Review)

Drei Strategie-Familien, gleichberechtigt im Modell (`Episode.segmentation`):

| Strategie | Parameter | Beispiel Windpark |
|-----------|-----------|-------------------|
| `FIXED_DURATION` | `duration`, `alignment` (z.B. auf volle 10 min/1 h) | 10-min-SCADA-Blöcke, Tages-Episoden |
| `FIXED_COUNT` | `n`, `overlap` | 4096 Samples für FFT/PSD von Schwingungssensoren |
| `FEATURE_EXTREMUM` | `feature` (MIN/MAX/lokales Extremum, Prominenz), `pre`, `post` (Dauer oder Anzahl) | Fenster um Leistungsspitzen, Böen, Drehmoment-Peaks |
| `FEATURE_THRESHOLD` | `enter` (Schwelle/Richtung), `exit` (Schwelle/Richtung, Hysterese), `pre`, `post`, `maxDuration` | Starkwind > 20 m/s bis < 18 m/s, Curtailment-Phasen, Vibrationsalarme |

Schema-Ergänzung (zu 02-ZIELMODELL):

```avro
enum SegmentationStrategy { FIXED_DURATION, FIXED_COUNT, FEATURE_EXTREMUM, FEATURE_THRESHOLD, MANUAL }
record Segmentation {
  SegmentationStrategy strategy;
  map<string> params = {};                    // z.B. {"pre":"PT5M","post":"PT10M","feature":"MAX"}
  union{null,timestamp_micros} anchorTs = null; // Extremum- bzw. Schwellen-Zeitpunkt
  union{null,string} sourceEpisodeId = null;    // Segment aus welcher Basis-Episode
}
```

Die Segmentierer laufen identisch **batch** (Python/Java auf Iceberg) und **streaming** (Flink-ProcessFunction mit Puffer für `pre`, Timer für `post`). Feature-Episoden verweisen auf Basis-Episoden → Lineage bleibt erhalten.

---

## 8. Python-Kompatibilität (Jupyter/Pandas & PySpark)

| Nutzergruppe | Zugriff | Bereitzustellen |
|--------------|---------|-----------------|
| Jupyter/Pandas | `opentsx.io.episodes(...)` → `pandas.DataFrame` (Long-Format: `series_id`, tz-aware UTC `ts`, `value`, `quality`) + Serien-/Asset-Metadaten-DF; Replay/Write nach KafScale | Python-Modul `opentsx.model` aus **denselben `.avsc`** (fastavro), Single-Object-Encoding, `confluent-kafka` mit `enable.idempotence=false`, PyIceberg-Reader |
| PySpark | Iceberg-Tabellen direkt (`spark.table("tsx.episodes")`), `explode(values)` → Punkte; UDFs für Segmentierung/Features (pandas UDF) | Spark-Helper `opentsx.spark` (Schema-Konstanten, Explode/Resample-Funktionen), Beispiel-Notebooks |
| Beide | Gleiche Ergebnisse wie Java | **Geteilte Testvektoren** (SeriesId-Hash, Encodings, Segmentierer, Statistik) in `opentsx-model/testvectors/` |

Baseline: **Python ≥ 3.10** (OpenMetadata-SDK), pandas ≥ 2.2, pyarrow, fastavro, pyiceberg, confluent-kafka.

---

## 9. Ziel-Architektur „OpenTSx Platform Core“ (Windpark)

```
 Simulator / Collector (SCADA, CSV, MQTT)
        │  Normalisierung (Kanal-Katalog, Einheiten, UTC, Qualität)
        ▼
 KafScale  tsx.wind.raw.v2 ── tsx.wind.episodes.v2 ── tsx.wind.events.v2 (Alarme/Status)
        │                     │
        │          Indexer ───┴──► Iceberg tsx.episode_index ◄── Replay-Service (seek per Offset)
        ▼
 Flink:  Segmentierer · Streaming-Stats (Welford/Sketch) · DQ-Checks · 10-min-Aggregation
        ▼
 Iceberg (REST-Catalog, S3/MinIO): assets · channels · episodes · channels_10min · series_stats · dq_results
        │                                         │
        ▼                                         ▼
 Retrieval-API (Java/Python) + Pattern-Engine     OpenMetadata 2.0.5 (Glossary, Tags, Custom Props,
 Jupyter/Pandas · PySpark                          Topics, Tables, Lineage, Profile, DQ-Results)
```

---

## 10. Priorisierte Roadmap (ersetzt die Phasenreihenfolge in TASK-011)

| Phase | Inhalt | Ergebnis |
|-------|--------|----------|
| **P0 Hygiene & Fundament** (1–2 Wo.) | Demo-Credentials durch `*.example` ersetzen; neue Module mit `maven.compiler.release=17`, CI-Matrix JDK 17 + 21; `dependencyManagement`-Sanierung für neue Module (Altbestand bleibt isoliert); CI (GitHub Actions: Java + Python); echte Python-Asserts; Altlasten nach `archive/` | grüne CI, schlanker Build für neue Module |
| **P1 Modell v2 + Serde (Java & Python)** | `.avsc` inkl. `Segmentation`, Asset/Channel-Schemas; Single-Object-Encoding; Testvektoren | identische Roundtrips Java ⇄ Python |
| **P2 KafScale-Conformance & Persistenz** | `docker-compose.kafscale.yml`; Conformance-Suite (Produce/Fetch, Gruppen, zstd, Header?, CreateTime?, Duplikate, ListOffsets-Verhalten); BucketWriter, Indexer, Replay | Episoden günstig in KafScale, verifizierbares Replay |
| **P3 Windpark-Domäne** | Asset-/Kanal-Katalog (IEC 61400-25-orientiert), Normalisierer, **Simulator** mit Fehlerinjektion, Szenario-YAML | reproduzierbare Experimente, die KafScale speisen |
| **P4 Iceberg + Stats + DQ + OpenMetadata** | Iceberg-Tabellen; Flink Stats/DQ/10-min; OM-Publisher (Glossary, Tags, Custom Props, Topics, Tables, Lineage, Profile, DQ) | gefüllter Metadata-Pool |
| **P5 Segmentierung, Retrieval & Pattern** | Segmentierer (4 Strategien, batch + streaming), Retrieval-API, MASS/SAX/DTW, Power-Curve-Analyse | Episoden-Suche & Mustersuche |
| **P6 Analytik-Lücken** | Resampling/Alignment, Gap-Filling, Change-Points, Welch-PSD/Kohärenz, Feature-Extraktion | Top-Tool-Niveau für Windpark-Analysen |
