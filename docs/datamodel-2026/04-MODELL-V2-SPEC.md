# OpenTSx Datenmodell v2 – Spezifikation (Version 2.0)

> Status: **implementiert** in `opentsx-model` (Java) und `opentsx.model` (Python), Stand 2026-10-09.
> Entwurf und Begründung: [02-ZIELMODELL.md](02-ZIELMODELL.md). Diese Datei ist die verbindliche Spezifikation;
> Java und Python werden gegen gemeinsame Testvektoren geprüft
> (`opentsx-model/src/test/resources/testvectors`).

## 1. Schemas

Einzige Quelle: `opentsx-model/src/main/avro/*.avsc`, Namespace `org.opentsx.model.v2`.
Python nutzt byte-identische Kopien (`python-package/opentsx/model/schemas`, durch Test erzwungen).

| Record | Zweck |
|--------|-------|
| `SeriesKey` | Identität einer Serie: `metric`, `tags`, `unit`, `seriesId` |
| `Episode` | unveränderlicher Abschnitt einer Serie (Austausch- und Speichereinheit) |
| `EpisodeSummary` | mergebare Kennzahlen (Welford) |
| `Segmentation` | Herkunft des Schnitts (`FIXED_DURATION`, `FIXED_COUNT`, `FEATURE_EXTREMUM`, `FEATURE_THRESHOLD`, `MANUAL`) |
| `Provenance` | Quelle, Erzeuger, Zeitpunkt, Pipeline, Eltern-Episode |
| `Observation` | einzelner Live-Messpunkt |
| `BucketManifest` | logischer Bucket (OPEN → SEALED, Checksumme) |
| `SeriesStats` | laufende/gefensterte Statistik des Streaming-Prozessors |
| `PatternMatch` | Treffer einer Mustersuche |

Alle Zeitstempel: `long` mit `logicalType: timestamp-micros` (Epoch-Mikrosekunden, UTC).
Java: `java.time.Instant`; Python (fastavro): zeitzonenbehaftetes `datetime` beim Lesen, `int` oder `datetime` beim Schreiben.

## 2. Serien-Identität

```
canonical = escape(metric) + "{" + join(",", escape(k) + "=" + escape(v) for k in sort_utf8(tags)) + "}"
seriesId  = lower_hex_16( xxHash64( utf8(canonical), seed = 0 ) )
escape(s) = s mit "\" vor jedem der Zeichen  \ { } , =
```

* Tag-Schlüssel werden nach ihren **UTF-8-Bytes** (vorzeichenlos) sortiert.
* `metric` und Tag-Schlüssel dürfen nicht leer sein; keine Unicode-Normalisierung (Aufrufer liefern NFC).
* `unit` gehört **nicht** zur Identität.
* Beispiel: `wind.turbine.power_active{park=ber-01,turbine=T07}` → `58280afbd12100b7`.

## 3. Zeitachse einer Episode

* `tStart` inklusiv, `tEnd` exklusiv; alle Zeitstempel liegen in `[tStart, tEnd)`.
* `REGULAR`: `t_i = tStart + i · intervalMicros`, `timeDeltas = null`.
  Default `tEnd = tStart + count · intervalMicros`.
* `IRREGULAR_DELTA`: `t_0 = tStart`; `timeDeltas` enthält `count − 1` Zig-Zag-Varints (wie Avro-`long`):
  zuerst `d_1 = t_1 − t_0`, danach `d_i − d_(i−1)`. Zeitstempel streng monoton steigend.
  Default `tEnd = t_last + 1 µs`.
* Bei 1-Hz-Daten mit kleinem Jitter kostet ein Zeitstempel 1–2 Byte.

## 4. Werte, Qualität, Zusammenfassung

* `values`: `array<double>`, `count == len(values)`; NaN ist erlaubt (Lücke/ungültig).
* `quality`: optional, genau ein Byte pro Wert (0 = ok).
* `summary`: über die **endlichen** Werte: `validCount`, `nanCount`, `min`, `max`, `mean`, `m2`;
  Varianz `= m2 / (validCount − 1)`. Zusammenführung nach Chan et al. (exakt, reihenfolgeunabhängig bis auf Rundung).
  Ohne endliche Werte: `min = max = mean = m2 = NaN`.

## 5. Wire-Format

* **Standard: Avro Single-Object-Encoding** – `C3 01` + 8 Byte CRC-64-AVRO-Fingerprint der
  Parsing Canonical Form (little-endian) + Avro-Binary. Selbstbeschreibend, keine Schema Registry nötig.
* **Lesen:** zusätzlich Confluent-Framing (`00` + 4 Byte Schema-ID), wenn ein Resolver (Registry-Client) konfiguriert ist.
* **Deterministisch:** Maps (`tags`, `labels`, `params`) werden in UTF-8-Schlüsselreihenfolge geschrieben →
  Java und Python erzeugen dieselben Bytes (Grundlage für Dedup und Checksummen).
* Fingerprints v2.0: `Episode 8985f02b48c77195`, `Observation ca0e771818e53a71`, `SeriesKey ec4b59937d77bd89`,
  `BucketManifest 68173d1a6b413f3e`, `SeriesStats e51b48188baa6606`, `PatternMatch 057c7af24286a91e`.
* Kafka: `org.opentsx.model.kafka.OpenTsxSerializer` / `OpenTsxDeserializer` (Value), Key = `seriesId`.

## 6. Schema-Evolution

* Jede veröffentlichte Version liegt eingefroren unter `opentsx-model/src/main/resources/schema-history/<version>/`.
* Regel **BACKWARD_TRANSITIVE**: der aktuelle Reader muss Daten aller veröffentlichten Versionen lesen
  (neue Felder nur mit Default). Der Build prüft das und verlangt bei jeder Schemaänderung eine neue Historienversion.
* `OpenTsxAvro` registriert alle Historienversionen automatisch als Writer-Schemas.

## 7. Episoden-IDs

ULID (26 Zeichen Crockford-Base32, 48 Bit Millisekunden + 80 Bit Zufall) – zeitlich sortierbar; Dedup-Schlüssel,
da KafScale keinen idempotenten Producer hat.

## 8. Python / Pandas

```python
from opentsx import model as m
from opentsx.model.pandas_io import series_to_episode, episode_to_series, episodes_to_frame

key = m.series_key("wind.met.wind_speed", {"park": "ber-01", "turbine": "T07"}, "m/s")
ep = series_to_episode(pandas_series_with_tz_aware_index, key, source="scada:ber-01")  # REGULAR erkannt
payload = m.encode("Episode", ep)          # identisch zu Java
name, ep2 = m.decode(payload)
df = episodes_to_frame([ep2])               # series_id, metric, episode_id, ts (UTC), value
```

Naive `DatetimeIndex` wird abgelehnt (außer `assume_utc=True`).

## 9. Legacy

`opentsx-legacy-bridge`: `TimeSeriesObject ⇄ Episode` (x-Werte als Epoch-Sekunden/-Millis/-Mikros, verlustfrei),
`EpisodesRecord (v1) → Episode`, Label `"metric k=v,…"` ⇄ `SeriesKey`. Das v1-Modell (`opentsx-data`) ist eingefroren
und veraltet, siehe `opentsx-data/README.md`.
