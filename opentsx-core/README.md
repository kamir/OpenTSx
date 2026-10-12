# opentsx-core: classic analysis library (2.x model)

This is the original OpenTSx library. It works on `TimeSeriesObject` and TSBucket (Hadoop SequenceFile) and is
**legacy, but maintained**: it builds on Java 17 and is used by `opentsx-connectors`, `opentsx-lg`,
`opentsx-flink-core` and `opentsx-legacy-bridge`.

| Package | Content |
|---------|---------|
| `org.opentsx.data.series` | `TimeSeriesObject` |
| `org.opentsx.data.loader`, `data.generator` | `MessreihenLoader` (CSV), `RNGWrapper` |
| `org.opentsx.core` | `TSBucket`, configuration (`core.config.ConfigManager`) |
| `org.opentsx.algorithms.detrending` | DFA (`methods.DFA`), `SingleDFATool`, `MultiDFATool`, `MFDFAAnalyzer` |
| `org.opentsx.algorithms.ris` | return-interval statistics (`RISTool`) |
| `org.opentsx.algorithms.eventsynchronisation` | event synchronization (`ESCalc`) |
| `org.opentsx.algorithms.statistics` | Granger causality, entropy, distribution tests |
| `org.opentsx.generators` | `TSGenerator`, `LongTermCorrelationSeriesGenerator`, `FFTPhaseRandomizer` |
| `org.opentsx.app.bucketanalyser` | `MacroRecorder2` GUI (`bin/000_launch_tsa_workbench.sh`) |
| `org.opentsx.demo.onboarding` | guided demos (`bin/episode_*.sh`) |

* **User guide:** [`docs/user-manual/11-classic-analytics.md`](../docs/user-manual/11-classic-analytics.md). For new
  work, the Python implementations (`opentsx.DFA`, `MFDFA`, `EventSynchronization`, `RIS`) are the recommended
  interface.
* **v2 data:** convert episodes with `opentsx-legacy-bridge`.
