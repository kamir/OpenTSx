# Appendix C: Glossary

| Term | Meaning |
|------|---------|
| **Series** | one measured quantity of one asset: metric + tags (for example wind speed of turbine T07) |
| **SeriesKey / seriesId** | identity of a series; the id is the xxHash64 of the canonical form `metric{k=v,…}` |
| **Episode** | immutable piece of one series (time axis, values, quality, summary, labels, provenance); the unit of exchange and storage. Not to be confused with the "episodes" (lessons) of the 2025 onboarding tracks |
| **EpisodeSummary** | mergeable statistics of an episode (count, count of NaN and infinite values, min, max, mean, M2), optional SAX word |
| **Segmentation** | how an episode was cut (fixed duration/count, extremum, threshold, manual) |
| **Bucket / BucketManifest** | group of episodes (one run or import); OPEN → SEALED with checksum |
| **Episode index** | episodeId → topic/partition/offset; replaces timestamp seek on KafScale |
| **KafScale** | Kafka-protocol broker on object storage (S3) and etcd; no compaction, idempotence or timestamp seek |
| **Single-object encoding** | Avro binary with a 10-byte header carrying the schema fingerprint; self-describing |
| **SeriesStats** | windowed or running statistics of a series (`opentsx-stats`) |
| **Quantile sketch** | DDSketch with relative accuracy; mergeable, identical bytes in Java and Python |
| **Channel catalog** (data-point catalog) | versioned YAML (`channel-catalog.yaml`, class `ChannelCatalog`) that defines channels (meaning, IEC mapping, unit, quality) and source mappings. See [chapter 6](06-windpark.md) |
| **IEC 61400-25-2** | information model for wind power plants: logical device / logical node / data object / data attribute |
| **Logical node** | IEC grouping of data, for example WMET (meteorology), WTUR (turbine), WROT (rotor), WGEN (generator) |
| **Normalizer** | converts raw source readings into catalog-conformant episodes |
| **Ground truth** | list of events the simulator injected (operations, sensor faults) |
| **MASS** | FFT-based z-normalized distance profile |
| **SAX / PAA** | symbolic and piecewise-aggregate approximations; MINDIST lower-bounds the Euclidean distance |
| **DTW** | dynamic time warping; with LB_Kim and LB_Keogh lower bounds (UCR suite) |
| **Smart Topic** | a topic that a running flow derives from other topics in the vector processor (`bin/opentsx vectors`), for example the vector topic `tsx.<domain>.smart.<name>.vectors.v1`. Declarative model training, smart metrics and a prediction API are planned (P7, [chapter 15](15-roadmap.md)) |
| **Operator** | a time-series operation with an id such as `resample` or `pair.xcorr`, described in `systems/processors/<id>.yaml` (schema `opentsx.processor.v1`) with the runtimes it runs in. See [chapter 17](17-operators.md) |
| **DFA / MFDFA** | (multifractal) detrended fluctuation analysis: scaling of fluctuations, long-range correlations |
| **ES** | event synchronization between two series |
| **RIS** | return-interval statistics: distribution of times between extreme events |
| **TimeSeriesObject / TSBucket** | 2.x data structures of `opentsx-core` (legacy) |
| **Twin plan / atom** | the wiring plan of a digital twin (`TwinPlan`, YAML under `opentsx-twin/src/main/resources/twin/`): atoms (`Atom`: model, views, dynamics, exchangeable policy) and the wires between their ports |
| **Variant T / variant L** | the two ways the twin runs a plan: all atoms in one process in bulk-synchronous steps (`TwinRunner`), or each atom on its own clock, coupled only through port messages (`LooseRunner`) |
| **Decision card** | a decision written down (`opentsx.decision.v1`): question, aims, levers, limits, the signals it needs, its analyses and alternatives. See [chapter 16](16-engineering-method.md) |
| **System / Signal / Grade / Origin** | terms of the engineering method: the plant; a quantity the system expects; `raw`, `clean` or `features` data of a signal; how data came to be (measured, reported, forecast, estimated, synthetic). See [chapter 16](16-engineering-method.md) |
