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

## Preview and export a time series

`TimeSeriesObject.toString()` shows up to five coordinate-value pairs and marks truncation only when more
points exist. Empty and short series, including disabled status recording, can be shown in the native preview.

Both `writeToFile(File)` and `writeToFile(File, Character)` export every pair as UTF-8 text without a preview
ellipsis. The base format retains its comment headers, metadata, numeric patterns and space-separated columns.
The character overload selects the decimal separator of numeric columns; commas in labels and comments stay
unchanged. Existing FFT, marked, qualified and dated formats retain their precision and additional columns.
Formatting does not change the input vectors or their numeric formatters.

Relative filenames and missing parent directories are supported. The writer prepares the full text, writes a
sibling temporary file and atomically replaces the destination after closing the file. Existing POSIX access
permissions are retained where supported. Destination symbolic links are rejected. Atomic replacement is required;
unsupported moves fail explicitly.

Malformed coordinate/value vectors and a null decimal separator raise `IllegalArgumentException` before writing.
I/O failures raise `UncheckedIOException` with the original cause. Failed preparation, encoding or replacement
preserves an existing destination. Temporary files are cleaned up; any cleanup failure is attached to the
reported exception.

You need JDK 17 or later, Maven, and a display for the Swing preview checks. From the repository root, run:

```bash
mvn -B -ntp -pl opentsx-core -am test \
  -Dtest=TimeSeriesObjectTextExportTest,TimeSeriesSubclassExportTest \
  -Dsurefire.failIfNoSpecifiedTests=false -Djava.awt.headless=false
```

The tests cover 0, 1, 2, 4, 5 and 8 points, decimal separators, metadata, native preview calls on the event dispatch
thread, subtype formats and deterministic preparation, encoding and replacement failures. The Java CI matrix
runs them under Xvfb.

## Normalize finite time series

`TimeSeriesObject.normalizeToStdevIsOne` centers values and divides by their sample standard deviation.
It returns a new series with the input coordinates and axis labels. Input values are unchanged.
Normalization requires at least two finite coordinate-value pairs with matching lengths and a finite,
positive standard deviation whose reciprocal is representable. Invalid inputs raise `IllegalArgumentException`.

`TimeSeriesObjectStatisticsTest` also checks mean recalculation and half-open `cutOut` windows,
including empty and truncated windows.

## Compute event synchronization

`ESCalc.calc(ti, tj)` and `ESCalc2.calcES(tx, ty)` take sorted event times and return `{sync, delay}` after
Quiroga, Kreuz and Grassberger (2002). Each event pair uses its own window, half the smallest distance to a
neighbouring event. A pair counts 1 when its distance is strictly below that window and 1/2 in both directions when
the events coincide. `sync` is in [0, 1] and `delay` in [-1, 1]. `delay` is negative when events of the second
series follow events of the first. Only events with two neighbours are compared, and the result is divided by
`sqrt((M1 - 1) * (M2 - 1))`, so identical series with M events give `(M - 2) / (M - 1)`, not 1.
`ESCalcTest` checks these values and the bounds on random event series.

## Supply bucket analysis inputs

`org.opentsx.app.bucketanalyser.BucketAnalyserTool` accepts four required positional arguments:
input folder, legacy label, raw-DFA boolean and normalized-DFA boolean. The label position is retained
for compatibility and does not change output names. Both booleans accept only `true` or `false`.

An optional fifth argument filters bucket filenames by substring. An omitted filter selects all
`.tsb.vec.seq` files. An optional sixth argument sets a nonnegative record limit. The default comes from
`BucketLoader.default_LIMIT`, initially 50. `BucketLoader.setLimit` applies to data loading and processing.
Missing folders, unreadable directory listings, invalid booleans and invalid limits stop the invocation.

## Export a supported processing flow

`PFDRecorder.toJSON()` displays a recording preview, which can contain unsupported desktop actions.
`saveToFile` validates the flow before opening the destination. An invalid flow cannot overwrite an export.
Set `opentsx.pfd.input.topic` through the existing `ConfigManager` before resetting the recorder.
The recorder uses that Kafka input topic and `LOG` output; it does not supply placeholder topics.

An executable export supports only `NORMALIZE` with an omitted method or `method: center`.
Other operations, methods and parameters fail with `IllegalArgumentException`.
See [PFD validation](../docs/devguide/validation/PFD_VALIDATION_PLAN.md) for the schema and runtime contract.

## Identify unavailable desktop actions

The AR, MA, ARMA, ARIMA, SARIMA and CARIMA buttons are disabled and show an explanatory tooltip.
The modern tracker disables Java and Scala export. Both trackers use `Show PFD` to display recorded JSON;
these buttons do not write a file. The modern tracker starts with an empty transformation tree.
Swing control tests use a display; the Java CI jobs run them under Xvfb on Ubuntu 24.04.

## Reload local SequenceFile buckets

The file and string loaders stop at the actual end of a SequenceFile, including empty buckets.
The file loader reads at most `LIMIT` records; a zero limit reads no records.
Readers close after loading or failure. A failing `TSOperation` stops loading with `IOException`
and retains the original exception as its cause. Processing output writers close on failure.

The SequenceFile writer stores each series label and Y values. Loading reconstructs sequential X values;
arbitrary input X coordinates and other metadata are not persisted by this format.

## Check bucket workflow support

`TSBucket.loadViaContext` throws `UnsupportedOperationException` with
`Context-based bucket loading is not implemented`. `TSBucket.processBucket` throws the same exception type with
`Named bucket processing is not implemented`. Neither entry point reads input or returns a processed bucket.
`MacroRunner` and the Java/Scala workflow export call these entry points, so executing those workflows stops at
the unsupported call. The separate `BucketLoader` and `loadFromSequenceFile` APIs are not routed through these methods.

## Inspect FFT coordinates

`TimeSeriesObjectFFT.convertToMessreiheFFT` copies both coordinate arrays into independent vectors.
`getFFT(samplingRate)` preserves the frequency coordinates computed by `calcFFT`: bin `k` has coordinate
`k * samplingRate / N`, where `N` is the padded transform length. The result contains the real components of the
coefficients; it does not represent the full complex spectrum. Supply the sampling rate in samples per time unit.
The Swing FFT action derives the sampling rate from increasing, uniformly spaced coordinates. Frequencies use
the reciprocal of the coordinate unit, so coordinates in seconds produce frequencies in hertz.
Integer coordinates with spacing 1 are treated as indices and require an explicit sampling rate in the dialog.
Cancelling the dialog produces no chart or recorded transformation. Empty selections, fewer than three samples,
unequal coordinate and value counts, non-finite data, and non-uniform coordinates produce an error message.

`TimeSeriesObjectFFT` uses English headers, diagnostics and error messages. Averaged series use the `Mean_` label prefix.
