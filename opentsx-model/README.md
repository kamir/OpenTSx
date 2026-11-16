# opentsx-model: the OpenTSx data model v2

This module contains the Avro schemas (`src/main/avro`, namespace `org.opentsx.model.v2`) and the Java helpers for
the v2 data model. The Python twin is `opentsx.model` in `python-package`, and it produces byte-identical records.

| Class | Purpose |
|-------|---------|
| `SeriesKeys` | series identity: canonical form, xxHash64 `seriesId` |
| `Episodes` | build episodes (`regular`, `irregular`), values, timestamps, validation |
| `AnalysisResults` | analysis result identity: canonical form, xxHash64 `resultId`, parameter normalization, `verify` |
| `TimeAxis`, `Micros` | REGULAR / IRREGULAR_DELTA time axis; epoch-microsecond helpers |
| `Summaries` | Welford summaries; exact merge |
| `OpenTsxAvro`, `WireFormat` | single-object encoding (default), Confluent framing on read |
| `kafka.OpenTsxSerializer` / `OpenTsxDeserializer` | Kafka value serde |
| `Buckets` | bucket manifest helpers and checksum |
| `Ulid` | sortable ids; `Ulid.deterministic(millis, seed)` for reproducible jobs |
| `SchemaHistory` | frozen schema versions with a BACKWARD_TRANSITIVE check |

* **Specification:** [`docs/datamodel-2026/04-MODELL-V2-SPEC.md`](../docs/datamodel-2026/04-MODELL-V2-SPEC.md).
* **User guide:** [`docs/user-manual/03-data-model.md`](../docs/user-manual/03-data-model.md).
* **Test vectors** shared with Python are in `src/test/resources/testvectors`.

## Build an analysis result

`AnalysisResults` computes the `resultId` of an `AnalysisResult` and checks a record. Java and Python compute the same
id. The canonical form is in the user guide, section
[3.6 Analysis results](../docs/user-manual/03-data-model.md#36-analysis-results).

```java
Map<String, Object> params = AnalysisResults.params(Map.of("order", 1, "scales", List.of(16, 32, 64, 128, 256)));
List<String> inputs = List.of(episode.getEpisodeId());
AnalysisResult result = AnalysisResult.newBuilder()
        .setResultId(AnalysisResults.resultId("dfa", "1.0.0", params, inputs))
        .setMethod("dfa")
        .setMethodVersion("1.0.0")
        .setParams(params)
        .setInputEpisodeIds(inputs)
        .setInputSeriesIds(List.of(episode.getSeries().getSeriesId()))
        .setTStart(episode.getTStart())
        .setTEnd(episode.getTEnd())
        .setScalars(List.of(ResultScalar.newBuilder().setName("alpha").setValue(0.75).build()))
        .setProvenance(Provenance.newBuilder().setSource("notebook:dfa").setProducer(Episodes.PRODUCER)
                .setCreatedAt(Instant.now()).build())
        .build();
AnalysisResults.verify(result);
byte[] payload = new OpenTsxAvro().encode(result);
```

* Put the map that `AnalysisResults.params` returns into the record. It is in UTF-8 key order, `Integer` becomes
  `Long` and a `List` of numbers becomes a `List<Double>`. Avro writes maps in iteration order, so Java and Python
  write the same bytes.
* Parameter values are `Long`, `Integer`, `Double`, `CharSequence` or a `List` of numbers. Any other type throws
  `IllegalArgumentException`. A `Boolean` throws `boolean parameters are not supported; use a long (0 or 1) or a
  string`. A `Float` throws `unsupported parameter type: Float`.
* NaN in a parameter throws `IllegalArgumentException: NaN is not allowed in analysis parameters`.
* `verify` throws `IllegalArgumentException` when `resultId` does not match, when `inputSeriesIds` and
  `inputEpisodeIds` differ in length, when `tStart` is not before `tEnd`, when a curve has `x` and `y` of different
  length, or when a matrix does not have `rowKeys.size() * colKeys.size()` values.
* `OpenTsxAvro` reads and writes `AnalysisResult` like the other top-level records.
