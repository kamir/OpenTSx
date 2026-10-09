# OpenTSx Datenmodell – Analyse & Reflexion (Stand 2026-10)

> Branch: `opentsx/2026-datamodel-refresher` · Begleitdokument zu
> [`EVOLUTION/TASK-011-datamodel-refresher-2026.md`](../../EVOLUTION/TASK-011-datamodel-refresher-2026.md)
> (Plan) und [`02-ZIELMODELL.md`](02-ZIELMODELL.md) (Entwurf des Zielmodells).

## 1. Überblick: Was heute existiert

OpenTSx hat **zwei voneinander getrennte Modellwelten**, die nur über punktuelle Konverter verbunden sind:

| Welt | Ort | Zweck | Serialisierung |
|------|-----|-------|----------------|
| **A – In-Memory-Analysemodell** | `opentsx-core/.../data/series`, `.../core` | Algorithmen (FFT, DFA, RIS, Event-Sync …) | Java-Serializable, Hadoop SequenceFile (Mahout `VectorWritable`), Gson-JSON |
| **B – Avro-Transportmodell** | `opentsx-data/src/main/avro` → `org.opentsx.data.model` | Kafka, ksqlDB, Kafka Streams, Flink | Avro 1.8.2 (Confluent Schema Registry *oder* rohes Avro-Binär) |

Dazu kommen die Store-Adapter (OpenTSDB, Cassandra, Hive-UDF) und das Python-Paket, die jeweils ein **eigenes implizites Mapping** mitbringen.

### 1.1 Welt A – Analysemodell

| Klasse | Rolle | Beobachtungen |
|--------|-------|---------------|
| `ITimeSeriesObject` / `TimeSeriesObject` (2907 Zeilen) | Zentrale Zeitreihe: `label`, `Vector xValues`, `Vector yValues` | God-Class: Datenhaltung + Statistik + Transformationen + I/O + Charting + Generatoren. Rohe `Vector` → Boxing (`Double`), ~4–6× Speicher, synchronisiert. Zeit ist `double` (kein Epoch-Typ, keine Einheit, keine Zeitzone). |
| `MarkableTimeSeriesObject` | TSO + `Vector<String> marker` pro Punkt | Proto-Annotation/Segmentierung – konzeptionell der Vorläufer von „Episoden“. |
| `QualifiedTimeSeriesObject` | TSO mit Qualitätsinformation | Qualität nicht im Transportmodell vorhanden. |
| `TimeSeriesObjectFFT`, `AveragedMessreihe`, `CombinedMessreihe`, `ScaledDataRow`, `Zeitreihe`, `MRT` | Spezialisierungen / Altlasten | Teilweise deutsch benannt, überlappend. |
| `core/TSData` (Hadoop `Writable`) | Kompaktform: `label, t0, tE, dt, double[] dataset` | **`convertMessreihe()` verwirft die Zeitstempel** („Timing metadata is ignored“) – nur Werte + Label überleben. |
| `core/TSBucket` (1222 Zeilen) | Container für viele TSOs; Persistenz als SequenceFile `Text → VectorWritable(NamedVector)` | Bucket = *physische Datei*. Key = Label (optional mit Präfix `###`). Zeitachse, Einheit, Metadaten gehen verloren. Hart verdrahtete Pfade (`/media/esata/...`). |
| `tsbucket/TSBucketStore`, `TSOReaderInterface`, `TSOWriterInterface` | Gedachte Store-Abstraktion | **Leere Stubs** – die SPI existiert nur dem Namen nach. |
| `tsbucket/metadata/ExperimentDescriptor`, `annotatedvectors/PropertiesVector` | Metadaten | Annotation-Interface ohne Runtime-Nutzung; Metadaten sind nicht an Buckets/Episoden gebunden. |

### 1.2 Welt B – Avro-Transportmodell (`opentsx-data`)

| Schema-Datei | Record | Felder (Kurzform) | Nutzung |
|--------------|--------|-------------------|---------|
| `event.avsc` | `Event` | `timestamp, uri, value, producer_timestamp, consumer_timestamp` | 8 Dateien (Producer, Kafka-Streams, `EventTS`) |
| `episode.avsc` | `EpisodesRecord` mit inline `Observation{timestamp, uri, value}` | `observationArray[], label?, tStart?, tEnd?, zObservations?, increment?, uri?` | 5 Dateien (Producer, TSA-Examples 10/11, `TimeSeriesObject.getFromEpisode`) |
| `episode2.avsc` | `EpisodesNEW` | `observationValues: double[]` + Header | **ungenutzt** |
| `episode3.avsc` | `Episodes` | nur Header (`taglabel, tStart, tEnd, …`) | **ungenutzt** |
| `EpisodeMetadata.avsc` | `EpisodeMetadata` | identisch zu `Episodes` | **ungenutzt** |
| `EventSeries.avsc`, `event-series.avsc` | `EventSeries`, `EventSeriesRecord` mit inline `Event{timestamp, uri, value}` | `eventArray[], labels, tStart, tEnd` | 1 Datei |
| – (kein .avsc) | `Episode`, `LatencyTesterEvent` | generierter Code ohne Quelle | Latency-Tooling |

Beobachtungen:

1. **Namenskollision `org.opentsx.data.model.Event`** – dreimal mit unterschiedlicher Struktur definiert (5 Felder in `event.avsc`, 3 Felder inline in beiden EventSeries-Schemas). Generiertes `Event.java` hat 5 Felder, das eingebettete `SCHEMA$` von `EventSeries*` beschreibt 3. Gleicher Full-Name, inkompatible Schemas → Schema-Registry-/Deserialisierungsfehler sind vorprogrammiert.
2. **Vier Varianten von „Episode“** (`EpisodesRecord`, `EpisodesNEW`, `Episodes`, `EpisodeMetadata`), nur eine wird benutzt.
3. **Redundanz in `EpisodesRecord`**: jede Observation trägt `timestamp` *und* der Header trägt `tStart/increment` → zwei Wahrheiten über die Zeitachse. Zudem `uri` als String **pro Messpunkt** – teuerstes Feld, wiederholt in jedem Punkt.
4. **Keine Identität einer Serie**: Identität steckt im freien String `label` (bzw. `uri`, `taglabel`). Für OpenTSDB wird erwartet, dass das Label die Form `"metric k1=v1,k2=v2"` hat (`OpenTSDBConnector.java:635-670`) – ein implizites, nicht validiertes Format.
5. **Keine Einheit, keine Qualität, keine Schema-Version, keine Provenienz/Lineage**, keine logischen Avro-Typen (`timestamp-millis`), Zeitstempel-Präzision undokumentiert.
6. **Generierter Code in `src/main/java`** eingecheckt (`opentsx-data/pom.xml`, `outputDirectory`), Avro 1.8.2 (2017).
7. Felder ohne Defaults (`producer_timestamp`, `consumer_timestamp`) → nicht rückwärtskompatibel evolvierbar.

### 1.3 Serialisierung & Kafka-Pfade (`opentsx-connectors`)

| Pfad | Topic | Key | Value | Probleme |
|------|-------|-----|-------|----------|
| `TSOProducer.pushTSDataAsEpisodesToKafka_String_Avro` | `OpenTSx_Episodes[_suffix]` | Serien-Label | Avro `EpisodesRecord` (SR) | **Label hart `"123"`**, uri `"URI"` (`TSOProducer.java:262`), synthetische Zeit `now + i·200ms`, Record-Objekt wiederverwendet |
| `pushTSDataAsEventsToKafka_String_Avro` | `OpenTSx_Events` | Label | Avro `Event` (SR) | **Zeitstempel wachsen quadratisch** (`t = t + i*200`, `TSOProducer.java:356`), `send().get()` pro Nachricht |
| `pushTSOItemsToKafka` | `OpenTSx_Episodes` | Label | **Gson-JSON** von `TSData` | gleiches Topic wie Avro-Pfad → gemischte Formate in einem Topic |
| `TSOEventProducer` | `refds_events_topic_<TAG>` | `label_<x>` | JSON `{ts,value}` | Key enthält x → Serie über Partitionen verstreut |
| `EventFlowStateProducer` | `OpenTSx_Event_Flow_State` | flowSource | Properties als JSON | – |
| `TSOConsumer` | `refds_small_bucket_topic_<TAG>` | – | – | zufällige `group.id` + `earliest` = implizites Voll-Replay bei jedem Start; keine gezielte Replay-Logik |
| `TSOWriter.persistBucket_Kafka` | – | – | – | **Stub („NOT YET DONE“)** |

Querschnitt: Kafka-Record-Timestamps werden nie gesetzt (Ereigniszeit nur im Payload → `offsetsForTimes` unbrauchbar), keine Header, `localhost:9092` hart kodiert, kein Bucket-Begriff auf Kafka-Ebene.

**Wire-Format-Bruch:** Flink `ObservationSchema` (`opentsx-flink-core`) dekodiert **rohes** Avro-Binär, die Producer schreiben im **Confluent-Wire-Format** (Magic-Byte + Schema-ID). Beide Seiten sind nicht kompatibel.

### 1.4 Stores

* **OpenTSDB** (`opentsx-store-opentsdb`): `OpenTSDBEvent{metric, timestamp, value, tags}` als Strings; Schreiben über Telnet-Socket (`put`), Lesen über `/api/query`. Mapping Label → `metric` + Tags per String-Split. `OpenTSDBSeries` ist ein leerer Platzhalter. Keine Downsampling-/Aggregator-Abstraktion, keine Tag-Validierung (OpenTSDB: erlaubte Zeichen, Tag-Limit).
* **Cassandra** (`opentsx-store-cassandra`): `refds_small_bucket_table(id text PK, tsdata text)` – ein ganzer TSO als JSON-Blob, CQL per String-Konkatenation.
* **Hive-UDF**: schreibt nach OpenTSDB, kein eigenes Tabellenmodell.
* **Iceberg / Parquet**: nur in Doku erwähnt, kein Code. **OpenMetadata**, **KafScale**: nicht vorhanden.

### 1.5 Streaming-Statistik

* Flink `TimeSeriesAnalysisJob`: Topic `observations`, Event-Time-Watermarks, 5-min-Tumbling-Windows, `TimeSeriesAggregateFunction` **sammelt nur** Observations in ein TSO (keine laufende Statistik, O(n) Speicher je Fenster).
* Kafka Streams `TSAExample10/11`: zustandslose `mapValues` über Episoden (Stats/FFT als JSON).
* ksqlDB: `SummaryStatsUdaf` (laufender Mittelwert), `EpisodesProcessor`-UDF; Module `opentsx-ksql` und `opentsx-ksql-udf` duplizieren sich.
* `opentsx-processors`: nur `pom.xml`.

### 1.6 Pattern Matching

Kein Code für Similarity-Search, Motif-Discovery, SAX/PAA, DTW oder Matrix Profile vorhanden. Wiederverwendbare Bausteine: FFT (`TimeSeriesObjectFFT`), Normalisierung, Event-Synchronisation, RIS, Detrending.

### 1.7 Python

`python-package/opentsx/core/time_series.py`: numpy-basiertes `TimeSeriesObject(values, timestamps, label, metadata)` und `TSBucket` (Liste). Kein Avro/Episode-Modell, obwohl `fastavro`/`confluent-kafka[avro]` als Extra deklariert sind – eine **gemeinsame Schema-Quelle** würde Python sofort anschlussfähig machen.

### 1.8 Plattform

Java 8 (Root-POM), Flink 1.18 (braucht ≥ 11), Kafka-Clients 2.3 vs. Confluent 7.3, Avro 1.8.2, Gson 2.2.4. Für Iceberg 1.x (Java ≥ 11) und aktuelle Kafka-/OpenMetadata-Clients ist ein Toolchain-Update zwingend.

---

## 2. Reflexion: Was das Modell eigentlich ausdrücken will

Liest man Code, Schemas und Doku zusammen, steckt ein recht klares fachliches Modell darin – es ist nur nie explizit gemacht worden:

```
Serie (Identität: Metrik + Tags + Einheit)
  └── Observation / Event      (ein Punkt: Zeit, Wert, [Qualität])
  └── Episode                  (zusammenhängender, unveränderlicher Abschnitt EINER Serie
                                 mit Zeitbereich, Sampling-Art, Werten, Zusammenfassung)
Bucket                         (benannte Sammlung von Episoden vieler Serien –
                                 Experiment, Datensatz, Snapshot; mit Manifest + Metadaten)
Abgeleitet: SerienStatistik, Annotation/Marker, PatternMatch (Episode-Ref + Offset + Score)
```

Die heutigen Schwächen sind fast alle Folgen davon, dass diese Ebenen vermischt sind:

| Konzept | Heute | Konsequenz |
|---------|-------|------------|
| **Serien-Identität** | freier String `label` | Keine stabilen Kafka-Keys, kein sauberes OpenTSDB-/Iceberg-Mapping, keine Joins mit Metadaten. |
| **Episode** | 4 Schemas, Redundanz Zeitachse, `uri` pro Punkt | Teuer, mehrdeutig, nicht evolvierbar. |
| **Bucket** | physische SequenceFile, Label → Vektor | Zeitachse geht verloren; Bucket ist nicht replaybar, nicht katalogisierbar. |
| **Metadaten** | Annotation-Interface, `PropertiesVector`, `EpisodeMetadata` ungenutzt | Kein Pfad nach OpenMetadata, keine Lineage. |
| **Zeit** | `double` in TSO, `long` ohne Einheit in Avro, `dt` hart 200 ms | Präzision/Einheit nicht vertraglich festgelegt. |
| **Format** | SR-Avro, rohes Avro, JSON gemischt | Consumer müssen raten; Flink bricht. |

**Kernaussage:** Der wertvollste Schritt ist nicht ein neues Speicherformat, sondern ein **expliziter, versionierter Modellvertrag** mit **SeriesKey** als zentraler Identität und **Episode** als Austausch- und Speichereinheit. Alle Zielsysteme (KafScale, OpenTSDB, Iceberg, OpenMetadata, Retrieval/Pattern-Index) werden dann zu *Projektionen* dieses Vertrags.

### 2.1 Was erhaltenswert ist

* Die Idee **Episode als Array-Payload** (spaltenartig, ein Record pro Abschnitt) ist für günstige Kafka-Ablage genau richtig – deutlich billiger als ein Record pro Punkt.
* `TSData` (`t0, dt, double[]`) ist bereits die effiziente Form für **regelmäßig abgetastete** Reihen – sie muss nur die Zeitachse behalten.
* `MarkableTimeSeriesObject` (Marker pro Punkt) ist der natürliche Einstieg für Annotationen und Pattern-Treffer.
* Die Algorithmen-Bibliothek in Welt A bleibt unangetastet; sie braucht nur verlustfreie Konverter `Episode ⇄ TimeSeriesObject`.

### 2.2 Was ersetzt/abgelöst werden sollte

* `Episodes`, `EpisodesNEW`, `EpisodeMetadata`, `EventSeries*`, `Episode` (orphan) → deprecaten, durch v2 ersetzen.
* Mehrdeutiges `Event` → v2 trennt `Observation` (Punkt) und Transport-/Latenz-Metadaten (Kafka-Header oder eigenes `LatencyProbe`-Schema).
* `TSBucket`-SequenceFile-Persistenz → Bucket als logisches Konstrukt (Manifest + Episoden) über austauschbare Stores.
* Leere `TSBucketStore`/`TSO*Interface`-Stubs → echte Store-SPI.

## 3. Konkrete Defekte, die unabhängig vom Refresh behoben werden sollten

1. `TSOProducer.java:262` – Episoden-Label hart `"123"`, `uri` hart `"URI"`.
2. `TSOProducer.java:356` – quadratisch wachsende Zeitstempel (`t = t + i*200`).
3. `OpenTSx_Episodes` wird sowohl mit Avro als auch mit JSON beschrieben.
4. Flink `ObservationSchema` vs. Confluent-Wire-Format.
5. Avro-Full-Name `org.opentsx.data.model.Event` mehrfach mit unterschiedlichen Feldern.
6. `TSData.convertMessreihe` verwirft Zeitstempel → `TSBucket`-Dateien sind nicht zeittreu.
7. `CassandraConnector` – CQL per String-Konkatenation.
8. Hart kodierte Hosts/Pfade (`localhost:9092`, `/Users/mkampf/...`, `/media/esata/...`).
