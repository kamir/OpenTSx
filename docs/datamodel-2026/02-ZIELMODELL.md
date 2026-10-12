# OpenTSx Datenmodell v2 – Zielbild (Entwurf)

> Status: **Entwurf**. Das Datenmodell (§2) ist seit 2026-10-09 implementiert – verbindlich ist
> [04-MODELL-V2-SPEC.md](04-MODELL-V2-SPEC.md); die übrigen Abschnitte sind noch Planung. Offene Entscheidungen sind
> mit **[ENTSCHEIDUNG]** markiert und in `EVOLUTION/TASK-011-datamodel-refresher-2026.md` gesammelt.

## 1. Leitprinzipien

1. **Ein Vertrag, viele Projektionen** – Avro-Schemas in einem Modul (`opentsx-model`) sind die einzige Quelle. Java, Python (fastavro), Flink, ksqlDB und die Stores leiten daraus ab.
2. **SeriesKey ist die Identität** – nie wieder freie Label-Strings als Schlüssel.
3. **Episode ist die Austauscheinheit** – Punkt-Events nur für Live-Streams; Archiv, Replay und Retrieval arbeiten auf Episoden.
4. **Unveränderlichkeit** – Episoden werden nicht editiert, sondern neu geschrieben (Revision). Das macht Kafka-Ablage, Iceberg-Snapshots und Pattern-Indizes trivial konsistent.
5. **Kompatibilität ist geprüft, nicht gehofft** – CI prüft jede Schemaänderung gegen die Vorversion (BACKWARD_TRANSITIVE).

## 2. Kernentitäten

```
SeriesKey ──< Observation          (Live-Stream, ein Punkt)
SeriesKey ──< Episode              (Abschnitt; REGULAR | IRREGULAR Encoding; Summary-Stats)
Bucket    ──< BucketManifest ──< EpisodeRef
SeriesKey ──< SeriesStats          (laufend, vom Streaming-Prozessor)
Episode   ──< Annotation / PatternMatch
```

### 2.1 Schema-Entwürfe (Avro IDL-nah, Namespace `org.opentsx.model.v2`)

```avro
record SeriesKey {
  string metric;                       // z.B. "sensor.temperature"
  map<string> tags = {};               // kanonisch: Keys sortiert beim Hashen
  union{null,string} unit = null;      // UCUM, z.B. "Cel", "ms"
  string seriesId;                     // stabil: hex(xxHash64(metric + sortierte tags))  [ENTSCHEIDUNG Hash]
}

record Observation {                   // Live-Punkt
  string seriesId;
  timestamp_micros ts;                 // [ENTSCHEIDUNG Präzision: millis vs micros]
  double value;
  union{null,int} quality = null;      // Bitmaske: 0=ok, 1=interpoliert, 2=geschätzt, ...
}

enum TimeEncoding { REGULAR, IRREGULAR_DELTA }

record Episode {
  string episodeId;                    // ULID; sortierbar nach Erzeugung
  SeriesKey series;
  timestamp_micros tStart;
  timestamp_micros tEnd;               // exklusiv
  int count;
  TimeEncoding timeEncoding;
  union{null,long} intervalMicros = null;      // nur REGULAR
  union{null,bytes} timeDeltas = null;         // nur IRREGULAR: zigzag-varint delta-of-delta
  array<double> values;                // Phase 1 unkomprimiert; Kompression über Kafka/Parquet-Codec
  union{null,bytes} quality = null;            // optional, bitpacked
  EpisodeSummary summary;              // min/max/mean/m2/nanCount -> Prefilter ohne Payload-Scan
  map<string> labels = {};             // fachliche Labels (Experiment, Regime, ...)
  union{null,string} bucketId = null;
  int revision = 0;
  Provenance provenance;
}

record EpisodeSummary { double min; double max; double mean; double m2; long nanCount;
                        union{null,string} sax = null; }   // SAX-Wort für Pattern-Prefilter

record Provenance { string source; string producer; timestamp_millis createdAt;
                    union{null,string} parentEpisodeId = null; union{null,string} pipeline = null; }

record BucketManifest {                // Bucket = logische Sammlung, kein Dateiformat
  string bucketId;
  string name;
  map<string> descriptor = {};         // Nachfolger ExperimentDescriptor (author, topics, tags, ...)
  timestamp_micros tMin; timestamp_micros tMax;
  long episodeCount;
  enum BucketState { OPEN, SEALED } state;
  union{null,string} checksum = null;  // über sortierte episodeIds -> Replay-Verifikation
}

record SeriesStats {                   // Output des Streaming-Prozessors (compacted topic)
  string seriesId; timestamp_micros windowStart; timestamp_micros windowEnd; string windowType;
  long count; double min; double max; double mean; double m2;   // Welford -> var = m2/(n-1)
  union{null,bytes} quantileSketch = null;                       // t-digest / KLL serialisiert
  long gapCount; double meanRatePerSec; timestamp_micros lastSeen;
}

record PatternMatch { string queryId; string episodeId; int offset; int length;
                      double distance; string metric; }   // "zED" | "DTW(w=..)"
```

### 2.2 Bewusste Designentscheidungen

* **Kein `uri` pro Punkt** – Herkunft steht einmal in `Provenance`, Identität in `SeriesKey`.
* **`SeriesKey` in jeder Episode eingebettet** (statt nur `seriesId`): kostet ein paar Bytes, macht jeden Record selbstbeschreibend – wichtig für Replay ohne Lookup-Service. Für Live-`Observation` nur `seriesId` (Dimension separat als compacted Topic `…series.v2`).
* **Summary in der Episode**: ermöglicht Filter/Prefilter (Retrieval, Pattern-Matching, OpenMetadata-Profil) ohne die Werte zu dekodieren.
* **Mergebare Statistik (count/mean/m2, Sketch)** statt fertiger Varianz: Episoden-, Fenster- und Bucket-Statistiken lassen sich verlustfrei zusammenführen.

## 3. Serialisierung & Ablage in KafScale

> **Korrektur nach KafScale-Recherche (v1.6.0, siehe [03-GAP-ANALYSE §5](03-GAP-ANALYSE.md)):**
> keine Compaction → die als `compact` markierten Topics unten werden reine Append-Logs, der „latest“-Zustand liegt in Iceberg;
> kein idempotenter Producer → `enable.idempotence=false`, Dedup über `episodeId`;
> **ListOffsets ignoriert Zeitstempel** → zeitbasiertes Replay über einen eigenen Episoden-Index statt `offsetsForTimes`;
> keine Schema Registry → Default-Wire-Format `SELF_DESCRIBING`; Header nicht tragend, bis ein Conformance-Test sie bestätigt.

### 3.1 Wire-Format – zweistufig

| Modus | Wann | Format |
|-------|------|--------|
| `REGISTRY` | Schema-Registry verfügbar | Confluent-Wire-Format (Magic 0 + Schema-ID) |
| `SELF_DESCRIBING` | kein Registry (günstigster Betrieb, Archive, Offline-Replay) | **Avro Single-Object-Encoding** (`C3 01` + 8-Byte-Fingerprint); Schemas aus Classpath / Schema-Topic `_opentsx_schemas` |

Eine `OpenTsxSerde`-Fassade erkennt beim Lesen beide Formate am ersten Byte → Consumer (Java, Flink, Python) müssen nicht wissen, wie geschrieben wurde. Das behebt zugleich den heutigen Flink-Bruch.

### 3.2 Topic-Layout

| Topic | Key | Value | Policy |
|-------|-----|-------|--------|
| `tsx.<domain>.observations.v2` | seriesId | `Observation` | delete, kurze Retention (Live) |
| `tsx.<domain>.episodes.v2` | seriesId | `Episode` | delete, lange/unendliche Retention (Archiv, Tiered) |
| `tsx.<domain>.series.v2` | seriesId | `SeriesKey` | compact |
| `tsx.<domain>.buckets.v2` | bucketId | `BucketManifest` | compact |
| `tsx.<domain>.series-stats.v2` | seriesId + windowType | `SeriesStats` | compact |

Header pro Record: `tsx-schema` (Full-Name + Version), `tsx-bucket` (bucketId), `tsx-encoding`. **Record-Timestamp = `tStart` der Episode** (CreateTime), damit `offsetsForTimes()` zeitbasiertes Replay erlaubt.

### 3.3 „Preiswert“ – Kostenhebel

* Episoden statt Punkte: typischerweise 1 Record pro 10³–10⁴ Werte → Overhead pro Record (Key, Header, Batch-Header) amortisiert.
* Producer: `compression.type=zstd`, `linger.ms` 50–200, `batch.size` 256 KB–1 MB, `enable.idempotence=false` (KafScale), **asynchron** (kein `send().get()`).
* Delta-of-Delta für unregelmäßige Zeitachsen, `REGULAR` braucht gar keine Zeitstempel.
* Optional Phase 2: Werte-Kompression (Gorilla-XOR) als `bytes` – erst nach Messung mit zstd, sonst unnötige Komplexität.

### 3.4 Bucket-Write & Replay

```java
try (BucketWriter w = tsx.buckets().open("exp-2026-10-ffmlrc", descriptor)) {   // schreibt Manifest OPEN
    for (TimeSeriesObject tso : legacyBucket) w.append(Episodes.from(tso, seriesKeyOf(tso)));
}                                                                                  // schreibt Manifest SEALED + checksum

ReplayRequest r = ReplayRequest.bucket("exp-2026-10-ffmlrc")          // oder: .series(selector).between(t0, t1)
                     .pace(Pace.ASAP | Pace.realtime(10.0));            // 10x Echtzeit
tsx.replay(r).into(Sink.topic("tsx.lab.episodes.replay") | Sink.callback(ep -> ...) | Sink.tsBucket());
```

Replay-Strategie: Zeitfenster → `offsetsForTimes` pro Partition; Bucket → Header-Filter `tsx-bucket` (Phase 1) bzw. Iceberg-Index (Phase 3, wenn Episoden auch in Iceberg liegen). Abschluss-Verifikation über `episodeCount` + `checksum` aus dem Manifest.

## 4. Store-Abstraktion (SPI)

```java
public interface SeriesStore extends AutoCloseable {
    StoreCapabilities capabilities();                 // RAW_POINTS, EPISODES, DOWNSAMPLING, TAG_INDEX, SQL, TIME_TRAVEL
    CompletableFuture<WriteResult> write(Collection<Episode> episodes);
    Stream<Episode> read(EpisodeQuery query);         // Range + SeriesSelector, lazy
    Stream<SeriesKey> listSeries(SeriesSelector selector);
}
```

### 4.1 OpenTSDB-Adapter (`opentsx-store-opentsdb`, refaktoriert)

| v2 | OpenTSDB |
|----|----------|
| `SeriesKey.metric` | `metric` (Zeichen-Validierung `[a-zA-Z0-9-_./]`) |
| `SeriesKey.tags` | `tags` (≤ 8 Default-Limit, Validierung, `unit` optional als Tag) |
| `Episode` | aufgelöst in Datenpunkte → HTTP `/api/put?details` gebatcht (statt Telnet) |
| `EpisodeQuery` | `/api/query` mit `start/end` (ms), `m=<agg>:<downsample>:metric{tags}`; Antwort → Episoden (Chunking nach `maxEpisodeLength`) |

Capabilities: `RAW_POINTS, DOWNSAMPLING, TAG_INDEX`; kein `EPISODES` → Episoden-Grenzen werden beim Lesen rekonstruiert, Labels/Provenance gehen verloren (dokumentiert, ggf. OpenTSDB-Annotations-API für Episode-Marker).

### 4.2 Iceberg-Adapter (`opentsx-store-iceberg`, neu)

```sql
-- Dimension
CREATE TABLE tsx.series (series_id string, metric string, tags map<string,string>, unit string,
                         first_seen timestamp, last_seen timestamp);

-- Episoden: Archiv, Replay-Quelle, Retrieval, Pattern-Prefilter
CREATE TABLE tsx.episodes (
  episode_id string, series_id string, t_start timestamp, t_end timestamp, count int,
  time_encoding string, interval_us bigint, time_deltas binary, values array<double>,
  s_min double, s_max double, s_mean double, s_m2 double, nan_count bigint, sax string,
  labels map<string,string>, bucket_id string, revision int, source string, created_at timestamp)
PARTITIONED BY (days(t_start), bucket(32, series_id))
-- WRITE ORDERED BY series_id, t_start

-- Optional: Punkte für SQL-Analytik (aus Episoden abgeleitet, z.B. via Flink/Spark)
CREATE TABLE tsx.observations (series_id string, ts timestamp, value double, quality int)
PARTITIONED BY (days(ts), bucket(32, series_id));
```

Schreibpfade: (a) direkt über den Adapter (Batch, Iceberg Java API), (b) Kafka → Iceberg via Flink-Iceberg-Sink oder Iceberg-Kafka-Connect-Sink aus `episodes.v2`. **[ENTSCHEIDUNG]** Catalog (REST / Nessie / Glue / JDBC / Hadoop) und Object Store.

Capabilities: `EPISODES, SQL, TIME_TRAVEL`; Pattern-Prefilter über `sax`, `s_min/s_max`, Partition-Pruning über Zeit + Serie.

### 4.3 Legacy-Brücke

`LegacyBridge`: `TimeSeriesObject ⇄ Episode`, `TSBucket(SequenceFile) → BucketWriter`, `EpisodesRecord(v1) → Episode(v2)`. Damit laufen alle bestehenden Algorithmen unverändert auf v2-Daten.

## 5. Metadaten → OpenMetadata

| OpenTSx | OpenMetadata-Entity |
|---------|---------------------|
| `tsx.<domain>.*.v2` Topics | `Topic` inkl. `messageSchema` (Avro) |
| Iceberg-Tabellen | `Table` (über Iceberg/Lakehouse-Connector oder per API) |
| Flink-Stats-Job, Replay-Jobs | `Pipeline` + Lineage-Kanten Topic → Pipeline → Topic/Table |
| `BucketManifest` | `Container` bzw. Custom Properties auf Table/Topic (Experiment-Deskriptor) |
| `SeriesStats` (aggregiert) | Profiler-/Custom-Metrics bzw. Data-Quality `TestCaseResult` (Gaps, NaN-Rate, Wertebereich) |

Ingestion: kleines Java-Modul `opentsx-openmetadata` (REST-Client), das (1) Schemas/Topics/Tabellen einmalig registriert und (2) aus `series-stats.v2` **rate-limitiert** (z.B. stündlich je Dataset, nicht je Event) Profile aktualisiert. **[ENTSCHEIDUNG]** OpenMetadata-Version & Auth (JWT/Bot-Token); Granularität: Statistik pro Topic/Tabelle (empfohlen) vs. pro Serie (nur Top-N / Custom-Entity).

## 6. Streaming-Statistikprozessor (Flink)

* Quelle: `observations.v2` und/oder `episodes.v2` (Episode = vorverdichteter Batch → `EpisodeSummary` direkt mergen).
* `keyBy(seriesId)`; Zustand: Welford (count/mean/m2), min/max, t-digest oder KLL-Sketch, letzter Zeitstempel, Gap-Zähler (Δt > k·erwartetes Intervall).
* Ausgaben: (a) laufend (global, „seit Start“) mit Emission alle N Sekunden, (b) Tumbling-Fenster (1 min / 1 h / 1 d, konfigurierbar) → `series-stats.v2`; (c) Aggregat je Topic/Bucket für OpenMetadata.
* Event-Time mit Watermarks + erlaubter Verspätung; Late Data → Side Output.
* Ersetzt den heutigen `TimeSeriesAggregateFunction`-Ansatz (der alle Punkte puffert) durch O(1)-Zustand je Serie.

## 7. Retrieval-API & Pattern Matching

### 7.1 Öffentliche, einfache API

```java
EpisodeService svc = OpenTsx.connect(config);           // wählt Store(s) nach Capabilities

List<Episode> eps = svc.episodes()
    .metric("sensor.temperature").tag("site", "ber-01")
    .between(Instant.parse("2026-09-01T00:00:00Z"), Instant.parse("2026-09-02T00:00:00Z"))
    .resample(Duration.ofSeconds(10), Agg.MEAN)          // optional
    .limit(100)
    .fetch();

Optional<Episode> one = svc.episode("01JA...ULID");

List<PatternMatch> hits = svc.similarTo(queryEpisode)    // oder double[] / episodeId + Offset/Länge
    .in(SeriesSelector.metric("sensor.temperature"))
    .distance(Distance.zNormalizedEuclidean())           // oder Distance.dtw(band = 0.1)
    .topK(20)
    .fetch();
```

REST-Spiegel (dünn, Phase 5b): `GET /v1/episodes?metric=&tag.site=&from=&to=`, `GET /v1/episodes/{id}`, `POST /v1/patterns/search`. Python-Client über dieselben Avro-Schemas.

### 7.2 Interne Pattern-Engine (zweistufig)

1. **Kandidaten (Index)**: PAA → SAX-Wort je Episode bzw. je gleitendem Fenster fester Länge; gespeichert in `EpisodeSummary.sax` (Iceberg-Spalte) bzw. In-Memory-Index (iSAX-Baum) für heiße Daten. Zusätzlich Prefilter über `s_min/s_max/s_mean`.
2. **Exakte Verifikation**: MASS (FFT-basierte z-normalisierte Distanzprofile; nutzt die vorhandene FFT-Basis) für Euklid; für DTW Kaskade LB_Kim → LB_Keogh → DTW mit Sakoe-Chiba-Band (UCR-Suite-Prinzip).
3. **Ergebnis** als `PatternMatch` (Episode + Offset + Länge + Distanz); optional als Annotation zurück nach Kafka/Iceberg – die Fortsetzung von `MarkableTimeSeriesObject`.

Später möglich: Matrix-Profile für Motif-/Discord-Discovery über Buckets; Embedding-basierte ANN-Suche als weitere `Distance`-Implementierung.
