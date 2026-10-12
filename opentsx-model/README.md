# opentsx-model: the OpenTSx data model v2

This module contains the Avro schemas (`src/main/avro`, namespace `org.opentsx.model.v2`) and the Java helpers for
the v2 data model. The Python twin is `opentsx.model` in `python-package`, and it produces byte-identical records.

| Class | Purpose |
|-------|---------|
| `SeriesKeys` | series identity: canonical form, xxHash64 `seriesId` |
| `Episodes` | build episodes (`regular`, `irregular`), values, timestamps, validation |
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
