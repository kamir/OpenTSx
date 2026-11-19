# opentsx-model: the OpenTSx data model v2

This module contains the Avro schemas (`src/main/avro`, namespace `org.opentsx.model.v2`) and the Java helpers for
the v2 data model. The Python twin is `opentsx.model` in `python-package`, and it produces byte-identical records.

| Class | Purpose |
|-------|---------|
| `SeriesKeys` | series identity: canonical form, xxHash64 `seriesId` |
| `Episodes` | build episodes (`regular`, `irregular`, `of`), values, timestamps, validation, `producer(module)` |
| `TimeAxis`, `Micros` | REGULAR / IRREGULAR_DELTA time axis; epoch-microsecond helpers |
| `Varints` | unsigned and zig-zag LEB128 varints as Avro writes them; refuses truncated input and varints above 64 bits |
| `Summaries` | Welford summaries; exact merge |
| `OpenTsxAvro`, `WireFormat` | single-object encoding (default), Confluent framing on read |
| `kafka.OpenTsxSerializer` / `OpenTsxDeserializer` | Kafka value serde |
| `Buckets` | bucket manifest helpers and checksum |
| `Ulid` | sortable ids; `Ulid.deterministic(millis, seed)` for reproducible jobs |
| `SchemaHistory` | frozen schema versions with a BACKWARD_TRANSITIVE check |

`Episodes.of(key, timestampsMicros, values)` builds a REGULAR episode when there are at least two timestamps with one
positive step, and an IRREGULAR_DELTA episode otherwise. It refuses an empty series
(`values: empty; expected at least one value`).

Set the source of every episode with `.source(...)`, for example `scada:ost-01`. A draft without a source builds an
episode with the source `unknown`.

The producer defaults to `Episodes.PRODUCER`, which is `opentsx-model/<version>`. Another module passes
`Episodes.producer("<module>")`, which gives `<module>/<version>`. `<version>` is the Maven project version, stamped
into `org/opentsx/model/version.properties` at build time.

* **Specification:** [`docs/datamodel-2026/04-MODELL-V2-SPEC.md`](../docs/datamodel-2026/04-MODELL-V2-SPEC.md).
* **User guide:** [`docs/user-manual/03-data-model.md`](../docs/user-manual/03-data-model.md).
* **Test vectors** shared with Python are in `src/test/resources/testvectors`.
