# OpenTSx data model: analysis and reflection (as of 2026-10)

> Branch: `opentsx/2026-datamodel-refresher`. Companion document to
> [`EVOLUTION/TASK-011-datamodel-refresher-2026.md`](../../EVOLUTION/TASK-011-datamodel-refresher-2026.md)
> (plan) and [`02-ZIELMODELL.md`](02-ZIELMODELL.md) (draft of the target model).
>
> The page records the analysis of 2026-10. Sections 1.2 and 1.8 describe the module `opentsx-data` and the platform
> as they are now, section 1.3 describes each Kafka path it lists as it is now, and section 3 lists the defects that
> are still open.

## 1. Overview: what exists

OpenTSx has **two separate model worlds**, connected only by a few converters:

| World | Location | Purpose | Serialization |
|-------|----------|---------|---------------|
| **A: in-memory analysis model** | `opentsx-core/.../data/series`, `.../core` | Algorithms (FFT, DFA, RIS, event synchronization ...) | Java Serializable, Hadoop SequenceFile (Mahout `VectorWritable`), Gson JSON |
| **B: Avro transport model** | `opentsx-data/src/main/avro` → `org.opentsx.data.model` | Kafka, ksqlDB, Kafka Streams, Flink | Avro 1.12.1 (Confluent Schema Registry *or* raw Avro binary) |

On top of these come the store adapters (OpenTSDB, Cassandra, Hive UDF) and the Python package, each with its **own implicit mapping**.

### 1.1 World A: analysis model

| Class | Role | Observations |
|-------|------|--------------|
| `TimeSeriesObject` (2907 lines) | Central time series: `label`, `Vector xValues`, `Vector yValues` | God class: data, statistics, transformations, I/O, charting and generators. Raw `Vector` means boxing (`Double`), about 4 to 6 times the memory, synchronized. Time is a `double` (no epoch type, no unit, no time zone). |
| `MarkableTimeSeriesObject` | TSO plus `Vector<String> marker` per point | Proto annotation and segmentation; conceptually the forerunner of "episodes". |
| `QualifiedTimeSeriesObject` | TSO with quality information | The transport model has no quality. |
| `TimeSeriesObjectFFT`, `AveragedMessreihe`, `CombinedMessreihe`, `ScaledDataRow`, `Zeitreihe`, `MRT` | Specializations and legacy | Partly German names, overlapping. |
| `core/TSData` (Hadoop `Writable`) | Compact form: `label, t0, tE, dt, double[] dataset` | **`convertMessreihe()` drops the timestamps** ("Timing metadata is ignored"); only values and label survive. |
| `core/TSBucket` (1222 lines) | Container for many TSOs; persisted as SequenceFile `Text → VectorWritable(NamedVector)` | Bucket = *physical file*. Key = label (optionally with the prefix `###`). Time axis, unit and metadata are lost. Hard-wired paths (`/media/esata/...`). |
| `tsbucket/TSBucketStore`, `TSOReaderInterface`, `TSOWriterInterface` | Intended store abstraction | **Empty stubs**: the SPI exists in name only. |
| `tsbucket/metadata/ExperimentDescriptor`, `annotatedvectors/PropertiesVector` | Metadata | Annotation interface without runtime use; metadata is not bound to buckets or episodes. |

### 1.2 World B: Avro transport model (`opentsx-data`)

| Schema file | Record | Fields (short form) | Use |
|-------------|--------|---------------------|-----|
| `event.avsc` | `Event` | `timestamp, uri, value, producer_timestamp, consumer_timestamp` | `TSOProducer`, `TimeSeriesObject` |
| `episode.avsc` | `EpisodesRecord` with inline `Observation{timestamp, uri, value}` | `observationArray[], label?, tStart?, tEnd?, zObservations?, increment?, uri?` | `TSOProducer`, `TimeSeriesObject.getFromEpisode`, `LegacyBridge`; `Observation` in `opentsx-flink-core` (`ObservationSchema`, `TimeSeriesAggregateFunction`, `DynamicTopologyJob`) |
| `event-series.avsc` | `EventSeriesRecord` with items of type `Event` from `event.avsc` | `eventArray[], labels, tStart, tEnd` | none |
| none (no .avsc) | `LatencyTesterEvent` | hand-written class without a schema | Latency tools |

Observations:

1. **`org.opentsx.data.model.Event` has one definition**, in `event.avsc`. `event-series.avsc` refers to it by name, and the build imports `event.avsc` first (`opentsx-data/pom.xml`).
2. **One episode schema**: `EpisodesRecord` in `episode.avsc`.
3. **Redundancy in `EpisodesRecord`**: each observation carries `timestamp` *and* the header carries `tStart/increment`, so there are two truths about the time axis. `uri` is a string **per data point**: the most expensive field, repeated in every point.
4. **No identity of a series**: the identity sits in the free string `label` (or `uri`, `taglabel`). For OpenTSDB the label is expected to have the form `"metric k1=v1,k2=v2"`, an implicit format that the 2.x model does not check. The 2.x class `OpenTSDBConnector` is not in the repository; `LegacyBridge.seriesKeyFromLabel` (`opentsx-legacy-bridge`) parses that form into a `SeriesKey` and refuses a tag that is not `key=value`.
5. **No unit, no quality, no schema version, no provenance or lineage**, no logical Avro types (`timestamp-millis`), timestamp precision undocumented.
6. **Generated code**: the build generates the records into `target/generated-sources/avro` with Avro 1.12.1. `src/main/java` holds only `LatencyTesterEvent`.
7. Fields without defaults (`producer_timestamp`, `consumer_timestamp`) cannot evolve backward compatibly.

### 1.3 Serialization and Kafka paths (`opentsx-connectors`)

| Path | Topic | Key | Value | Problems |
|------|-------|-----|-------|----------|
| `TSOProducer.pushTSDataAsEpisodesToKafka_String_Avro` | `OpenTSx_Episodes[_suffix]` | Series label | Avro `EpisodesRecord` (SR) | **Synthetic time** `now + i·200ms` with increment 200, whatever the x values of the series; every observation repeats the `uri` argument; `OpenTSxClusterLink.send` waits for each record (`send().get()`) |
| `TSOProducer.pushTSDataAsEventsToKafka_String_Avro` | `OpenTSx_Events` | Label | Avro `Event` (SR) | **Synthetic time** `now + i·200ms`, whatever the x values of the series; `OpenTSxClusterLink.send` waits for each message (`send().get()`) |
| `TSOEventProducer.pushTSOItemsToKafka` | `refds_events_topic_<TAG>` | `label_<x>` | JSON `{ts,value}` | The key contains x, so a series is scattered over partitions |
| `EventFlowStateProducer` | `OpenTSx_Event_Flow_State` | flowSource | Properties as JSON | none |
| `TSOConsumer` | `refds_small_bucket_topic_<TAG>` | none | none | Random `group.id` plus `earliest` means an implicit full replay at every start; no targeted replay logic |
| `TSOWriter.persistBucket_Kafka` | none | none | none | **Stub; stores nothing and throws `UnsupportedOperationException`** |

Across all paths: Kafka record timestamps are never set (event time only in the payload, so `offsetsForTimes` is useless), no headers, no notion of a bucket at the Kafka level.

**Wire format break:** Flink `ObservationSchema` (`opentsx-flink-core`) decodes **raw** Avro binary, while the producers write the **Confluent wire format** (magic byte plus schema id). The two sides are not compatible.

### 1.4 Stores

* **OpenTSDB** (`opentsx-store-opentsdb`): `OpenTSDBEvent{metric, timestamp, value, tags}` as strings; writes over a Telnet socket (`put`), reads over `/api/query`. Label is mapped to `metric` and tags by a string split. `OpenTSDBSeries` is an empty placeholder. No downsampling or aggregator abstraction, no tag validation (OpenTSDB: allowed characters, tag limit).
* **Cassandra** (`opentsx-store-cassandra`): `refds_small_bucket_table(id text PK, tsdata text)`, a whole TSO as a JSON blob, CQL by string concatenation.
* **Hive UDF**: writes to OpenTSDB, no table model of its own.
* **Iceberg / Parquet**: only mentioned in the docs, no code. **OpenMetadata**, **KafScale**: not present.

### 1.5 Streaming statistics

* Flink `TimeSeriesAnalysisJob`: topic `observations`, event-time watermarks, 5-minute tumbling windows; `TimeSeriesAggregateFunction` **only collects** observations into a TSO (no running statistics, O(n) memory per window).
* Kafka Streams `TSAExample10/11`: stateless `mapValues` over episodes (stats and FFT as JSON).
* ksqlDB: `SummaryStatsUdaf` (running mean), `EpisodesProcessor` UDF; the modules `opentsx-ksql` and `opentsx-ksql-udf` duplicate each other.
* `opentsx-processors`: only a `pom.xml`.

### 1.6 Pattern matching

No code for similarity search, motif discovery, SAX/PAA, DTW or matrix profile. Reusable building blocks: FFT (`TimeSeriesObjectFFT`), normalization, event synchronization, RIS, detrending.

### 1.7 Python

`python-package/opentsx/core/time_series.py`: a numpy-based `TimeSeriesObject(values, timestamps, label, metadata)` and `TSBucket` (a list). No Avro or episode model, although `fastavro` and `confluent-kafka[avro]` are declared as extras. A **shared schema source** would connect Python at once.

### 1.8 Platform

Java 17 (root `pom.xml`, `maven.compiler.release`), Flink 1.20.5 (`opentsx-flink-core/pom.xml`), Kafka clients 3.9.2 with the Confluent serializers 7.9.10, Avro 1.12.1 and Gson 2.14.0 (root `pom.xml`).

---

## 2. Reflection: what the model is meant to express

Read together, the code, the schemas and the docs contain a fairly clear domain model; it was just never made explicit:

```
Series (identity: metric + tags + unit)
  └── Observation / Event      (one point: time, value, [quality])
  └── Episode                  (contiguous, immutable section of ONE series
                                 with time range, sampling kind, values, summary)
Bucket                         (named collection of episodes of many series:
                                 experiment, data set, snapshot; with manifest and metadata)
Derived: SeriesStatistics, Annotation/Marker, PatternMatch (episode ref + offset + score)
```

Almost all weaknesses found in 2026-10 follow from these levels being mixed:

| Concept | In 2026-10 | Consequence |
|---------|------------|-------------|
| **Series identity** | free string `label` | No stable Kafka keys, no clean OpenTSDB or Iceberg mapping, no joins with metadata. |
| **Episode** | 4 schemas, redundant time axis, `uri` per point | Expensive, ambiguous, cannot evolve. |
| **Bucket** | physical SequenceFile, label → vector | The time axis is lost; a bucket cannot be replayed or cataloged. |
| **Metadata** | annotation interface, `PropertiesVector`, `EpisodeMetadata` unused | No path to OpenMetadata, no lineage. |
| **Time** | `double` in the TSO, `long` without unit in Avro, `dt` hard-coded to 200 ms | Precision and unit are not fixed by contract. |
| **Format** | SR Avro, raw Avro and JSON mixed | Consumers have to guess; Flink breaks. |

**Core statement:** the most valuable step is not a new storage format but an **explicit, versioned model contract** with **SeriesKey** as the central identity and **Episode** as the unit of exchange and storage. All target systems (KafScale, OpenTSDB, Iceberg, OpenMetadata, retrieval and pattern index) then become *projections* of that contract.

### 2.1 What is worth keeping

* The idea of the **episode as an array payload** (column-like, one record per section) is right for cheap Kafka storage: much cheaper than one record per point.
* `TSData` (`t0, dt, double[]`) is already the efficient form for **regularly sampled** series; it only has to keep the time axis.
* `MarkableTimeSeriesObject` (a marker per point) is the natural entry point for annotations and pattern hits.
* The algorithm library of world A stays untouched; it only needs lossless converters `Episode ⇄ TimeSeriesObject`.

### 2.2 What should be replaced

* The ambiguous `Event` → v2 separates `Observation` (a point) from transport and latency metadata (Kafka headers or a `LatencyProbe` schema of its own).
* `TSBucket` SequenceFile persistence → a bucket as a logical construct (manifest plus episodes) over exchangeable stores.
* Empty `TSBucketStore` and `TSO*Interface` stubs → a real store SPI.

## 3. Concrete defects to fix independently of the refresh

1. Flink `ObservationSchema` against the Confluent wire format.
2. `TSData.convertMessreihe` drops timestamps, so `TSBucket` files do not keep the time.
3. Hard-coded paths (figure folders below the home directory in the `initBaseFolder` calls of `ESMain.main` and `SigmaFilter.main`).
