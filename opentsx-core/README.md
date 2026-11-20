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

## Read the diagnostics

The library classes in `org.opentsx.data`, `org.opentsx.core` and `org.opentsx.algorithms` write nothing to standard
output. A failing call throws an exception that names the input. Progress and intermediate values go to the SLF4J
logger named after the class, at DEBUG, as one line per event in the form `<what happened> key=value`. The level of
that logger decides which lines appear. The public `debug` fields of `DFACore`, `SingleDFATool`, `ESCalc2`,
`TimeSeriesObjectFFT`, `MessreihenLoader`, `RISTool`, `ReturnIntervallStatistik2` and `TSPropertyTester` and the public
`verbose` fields of `RISTool`, `ReturnIntervallStatistik2` and `TSPropertyTester` are deprecated and have no effect.
`bin/opentsx` puts `slf4j-simple` and `conf/simplelogger.properties` on the class path, so the default level is
`info`. Raise the level of one class with a JVM system property:

```bash
java -Dorg.slf4j.simpleLogger.log.org.opentsx.data.loader.MessreihenLoader=debug -cp "$(bin/opentsx classpath opentsx-core)" <main class>
```

The `main` methods of the demo classes print their results to standard output.

## Normalize finite time series

`TimeSeriesObject.normalizeToStdevIsOne` centers values and divides by their sample standard deviation.
It returns a new series with the input coordinates and axis labels. Input values are unchanged.
Normalization requires at least two finite coordinate-value pairs with matching lengths and a finite,
positive standard deviation whose reciprocal is representable. Invalid inputs raise `IllegalArgumentException`.

`TimeSeriesObjectStatisticsTest` also checks mean recalculation and half-open `cutOut` windows,
including empty and truncated windows.

## Supply bucket analysis inputs

`org.opentsx.app.bucketanalyser.BucketAnalyserTool` accepts four required positional arguments:
input folder, legacy label, raw-DFA boolean and normalized-DFA boolean. The label position is retained
for compatibility and does not change output names. Both booleans accept only `true` or `false`.

An optional fifth argument filters bucket filenames by substring. An omitted filter selects all
`.tsb.vec.seq` files. An optional sixth argument sets a nonnegative record limit. The default is the constant
`BucketLoader.default_LIMIT`, 50. `BucketLoader.setLimit` sets the limit of that loader for data loading and
processing. It changes no other loader and not `TSBucket.default_LIMIT`.
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
`Export code (Java)` and `Export code (Scala)` of the classic tracker (`MacroTrackerFrame`) copy the generated Java
or Scala code to the clipboard and print nothing. Added sources, selected nodes and opened charts of the classic tracker
are DEBUG lines of `MacroTrackerFrame` and `Track`. `MacroTrackerFrame.addTransformation` records an operation
`<name>(<argument>)` as the PFD step `<name>` with the parameter `arg`, the text after the first `(` without `)`; an
operation that ends in `(` gets an empty `arg`. When the PFD recorder cannot start, for example because the
configuration file does not exist, `addTransformation` throws before it adds the node. Without an open tracker,
`MacroTrackerFrame.addSource` and `MacroTrackerFrame.addTransformation` write only their DEBUG line. `show Chart` of the classic tracker shows the chart
of the selected operation node. The chart panel keeps the chart of each operation it records for the node of that
operation; `TEST Normal Distribution` keeps its t-value chart. `MacroRecorder` keeps the charts of its `Components`
and `Shuffled` nodes. `Sigma Band` and `MF-DFA` keep no chart: for their nodes `show Chart` shows
`show Chart: no chart for <node>; select an operation node other than Sigma Band or MF-DFA`, for a node that is not
an operation node, such as a source node, `show Chart: no chart for <node>; select an operation node`, both in a
dialog; without a selection it does nothing.
Swing control tests use a display; the Java CI jobs run them under Xvfb on Ubuntu 24.04.
Opening a chart panel prints nothing and creates no folder; the chart folder and the series colors are DEBUG lines of
`TSStoreDescriptor` and `MultiChart`. `save PN` writes below `opentsx/plots/` in `user.home` and creates the folders
it needs. `MultiChart.store(chart, folder, name)` and
`TSOperationControlerPanel.storeChartImageAsPNG(chart, folder, name)` create `<folder>` when it does not exist and
write the chart they are given as `<folder>/<name>.png` without waiting and print nothing. A folder or an image that
cannot be written throws `UncheckedIOException` with `<file>: cannot write the chart image (<cause>)`; `save PN` shows
that message in a dialog.
`HaeufigkeitsZaehler.store()` writes `<folder><label>`; `folder` defaults to `opentsx/histograms/` below
`user.home`, which `store()` creates.

`TSOperationControlerPanel` writes nothing to standard output. The fitted alpha of `DFA` and the time slices of
`Histogram cross-cut` and `TEST Normal Distribution` are DEBUG lines of `TSOperationControlerPanel`. Every recorded
operation is a DEBUG line of `MacroTrackerFrame` and, while the macro tracker is open, of `Track` and `PFDRecorder`.
`SigmaFilter` (`Sigma Band`) and `HurstSurface.plot` (`MF-DFA`) print nothing; their progress is a DEBUG line.
The `Sigma Band` chart has three series: the mean of the selected series and the mean plus and minus one standard
deviation.
An operation is recorded in the macro track after it succeeded, so an operation that fails leaves the track unchanged.
The chart comment of `DFA` has one line per series with its label and its fitted alpha. `DFA` uses at most 250
window sizes from 4 to an eighth of the number of values; a series of fewer than 2032 values gets window sizes 1
apart, so a series of 1024 values gets 124 window sizes and a finite alpha.
`TEST Normal Distribution` runs the Shapiro-Wilk test on slices of 20, 40, 60, 80 and 100 values and opens both
charts, with one series per slice length named `l=<length>`. A slice the test cannot evaluate, such as constant
values, counts W = -1; a slice with a NaN or infinite value counts W = NaN.
The operations in this table stop when they fail and show the message in a dialog:

| Button | Message |
|--------|---------|
| `show PN` | `<folder>: no such folder; press save PN to create it` when the folder does not exist, otherwise `<folder>: cannot open the folder (<cause>); open it in a file manager` |
| `FFT` | `FFT of <label>: cannot open the chart (<cause>)`; no chart opens |
| `DFA` | `DFA of <series>: cannot compute the fluctuation function (<cause>)`; no chart opens |
| `Auto-Correlation` | `auto-correlation of <series>: cannot correlate lag <lag> (<cause>)`; no chart opens |
| `NORM std=1, avg=0` | `standardized series of <series>: cannot standardize the values (<cause>)` or `standardized series of <label>: cannot open the chart (<cause>)`; no chart opens |
| `MF-DFA` | `MFDFA of <label>: cannot compute the analysis (<cause>)` |
| `TEST Normal Distribution` | `SW test of <series>: cannot test the values <from> to <to> (<cause>)`; no chart opens |
| `Shuffle` | `shuffle of <label>: cannot shuffle the series (<cause>)` |

Cancelling the `Shuffle` dialog (`Number of shuffles:`), the `ENTROPY` dialog (`Length:`) or the
`Histogram cross-cut` dialog (`Window length l=`) does nothing. `Histogram per series`, `Histogram cross-cut` and
`ENTROPY` check their numbers before they compute. The three number fields of `Univariate Analysis` are `min`, `max`
and `bins`, in this order. A value that is not allowed shows its message in a dialog, the operation stops and no
chart opens:

| Field | Message |
|-------|---------|
| `min`, `max` | `<field>: "<value>" is not allowed; expected a whole number from -2147483648 to 2147483647` |
| `bins`, `window length` (`Histogram cross-cut`) | `<field>: "<value>" is not allowed; expected a whole number from 1 to 2147483647` |
| `length` (`ENTROPY`) | `length: "<value>" is not allowed; expected a whole number from 4 to 2147483647` |

`persistTSB()` throws `UncheckedIOException` with `<track>/<label>: cannot write the bucket (<cause>)`.

## Reload local SequenceFile buckets

The file and string loaders stop at the actual end of a SequenceFile, including empty buckets.
The file loader reads at most `LIMIT` records; a zero limit reads no records.
Readers close after loading or failure. A failing `TSOperation` stops loading with `IOException`
and retains the original exception as its cause. Processing output writers close on failure.
`BucketLoader.loadBucket(path, ids)` and `_processBucket(path, operation, ids)` load and process only the records
whose position in the file, counted from 1, is in `ids`; the other records count toward `LIMIT`. `ids` null loads
every record.

The SequenceFile writer stores each series label and Y values. Loading reconstructs sequential X values;
arbitrary input X coordinates and other metadata are not persisted by this format.

While `TSBucket.processSeriesLabelOnLoad` is true, the default, `loadFromSequenceFile(path, ids, operation)` and
`BucketLoader` label each series with its position in the file, counted from 1 for every load, so loads that run at the
same time do not change each other's labels. While it is false, the label is the key of the record.
`loadFromSequenceFile(file)` labels a series `<position> <key>`.

## Write a bucket

A `TSBucket` writes through the default Hadoop file system of its configuration. When that file system cannot be
opened, every method that uses it throws `IOException` with
`<default file system URI>: cannot open the file system (<cause>)`. `createBucketWithRandomTS_sinus` writes a
SequenceFile when `SKIP_WRITES` is false and `opentsx.demo.output.format` is not `csv`; in that case it throws and
writes no CSV file instead. `_open`, `putMessreihe` and `close` throw the `IOException` of the writer, so opening a
bucket under a regular file fails with a message that names that file. The `createBucket...` methods close their
SequenceFile or CSV writer when a series cannot be written, so the series written before the failure can be loaded.
`SingleRowTSO` and the `SingleTs...Tool` operations throw the `IOException` of their result writer.

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

## Combine series

`add(other)` sums the y values pointwise and keeps the x values of the series it is called on. On an empty series it
returns the pairs of `other`. Series of different lengths throw `IllegalArgumentException` with
`add: the series have <n> and <m> values; expected series of equal length`.
`add(other, length)` sums the first `length` pairs. When one series is shorter, it throws
`add: the series have <n> and <m> values; expected at least <length> values in each`.
`averageForAll` uses it with the length of the shortest series and keeps the x values of the first series.
`divide_by(other)` keeps the x values and gives NaN where the divisor is 0. Series of different lengths throw
`divide_by: the series have <n> and <m> values; expected series of equal length`.

`getAvarage()` computes the mean of the y values on every call. An empty series gives NaN.
`replaceZeroWithAverage()` replaces every value 0 with the mean of all values, the zeros included.

`calcAveragOfRows(rows)` keeps the x values of the first row. Each point is the mean of the rows that have a value at
that index, so rows `[1, 1]` and `[3, 3]` give `[2, 2]`, and rows `[1, 1, 1]` and `[3]` give `[2, 1, 1]`.

`split(length, count)` cuts the y values into `count` rows of `length` values. A row with fewer values is padded with
its own mean up to `length` and gets the comment `<n> missing values replaced with mean=<mean>.`. A row without values
holds NaN.

`getX_for_Y2(y, epsilon)` returns the x value of the first point whose y value differs from `y` by less than
`epsilon`, or NaN when no point does.

## Take logarithms

`calcLog10_for_Y()` and `calcLn_for_Y()` replace every y value with its logarithm. `calcMR_Ln_for_Y()` returns a new
series with the natural logarithm at the same x values. A value of 0 or below gives NaN, so `[10, 0, -1]` gives
`[1, NaN, NaN]` in base 10, and the result has as many points as the input. The desktop action for the natural
logarithm is labelled `ln` and records the prefix `LN_`.

## Format the text of a series

`toString()` formats x and y with the patterns of the series, `0.00000` by default. `setDecimalFomrmatX(pattern)` and
`setDecimalFomrmatY(pattern)` change only the series they are called on. The constants `TimeSeriesObject.dfx`,
`dfy` and `dfstat` hold the default pattern. The text of a series without values has no statistics block.

`getStatisticData(table)` and `getStatisticData(prefix)` summarize x and y. A series without values throws
`IllegalStateException` with `getStatisticData: the series <label> has no values; expected at least 1 value`. A value
that cannot be read, such as a missing y value, throws the exception of that value.

## Compute the entropy in sliding windows

`calcEntropyForWindow(length)` starts a window every `length / 4` values, rounded down. A length below 4 throws
`IllegalArgumentException` with `window length: <length> is less than 4; expected a window length of at least 4 values`.

## Export and load a series

`writeToFile(file, separator)` writes three header lines that start with `#`, then one line per pair: x, the
separator and y in the notation of `Double.toString`, for example `0.5,1.25`. `writeToFile(file)` separates with a
space. Both create missing parent directories. A failed write throws `UncheckedIOException` with
`<file>: cannot write the file (<cause>)`. `toString()` lists at most the first 5 pairs.

`MessreihenLoader.loadMessreihe_2(file, columnX, columnY, delimiter, limit)` reads such a file. Columns count from 1.
Lines that start with `#` and empty lines are skipped. The delimiter is a set of separator characters, as in
`StringTokenizer`. A comma is read as a decimal point unless the delimiter contains a comma, so `0,5;1,25` with `;`
loads as 0.5 and 1.25. At most `limit` pairs are read. The overloads without a delimiter use
`MessreihenLoader.DEFAULT_DELIMITER` (`,`), and those without a limit use `MessreihenLoader.DEFAULT_LIMIT`
(1000000000). The delimiter and the limit apply to that call only.

The other `MessreihenLoader` methods skip the same lines and stop the same way. `loadMessreihe` and `loadMessreihe_1`
split at white space, the methods with a delimiter argument split at that delimiter, and `_loadLogBinnedMessreihe` and
the five-argument `_loadLogBinnedMessreihe_DIV_BY_BINWIDTH` split at `,`. `loadMessreihe_1`, `loadMessreihe_zr_SC`,
both `loadMessreihe_For_LinkStrength` methods and `_loadMessreihe_For_FD` read a comma as a decimal point unless the
delimiter contains a comma. Errors stop the load:

| Problem | Exception and message |
|---------|-----------------------|
| value is not a number | `IllegalArgumentException`: `<file>:<line>: "<token>" is not a number; expected a decimal number such as 1.5` |
| too few columns | `IllegalArgumentException`: `<file>:<line>: the line has <n> columns; expected at least <m> columns separated by "<delimiter>"` |
| column 1 without `_` in `loadMessreihe_zr_SC` | `IllegalArgumentException`: `<file>:<line>: "<token>" has no "_"; expected two labels joined by "_" in column 1` |
| the mapper throws | `IllegalArgumentException`: `<file>:<line>: <mapper name> cannot read the line (<cause>); expected a line the mapper accepts` |
| the mapper returns no date in `_loadMessreihe_For_FD` | `IllegalArgumentException`: `<file>:<line>: <mapper name> returned no date; expected a date for every line` |
| no data line in `_loadLogBinnedMessreihe` or `_loadLogBinnedMessreihe_DIV_BY_BINWIDTH` | `IllegalArgumentException`: `<file>: the file has no data lines; expected at least one line with <n> columns separated by "<delimiter>"` |
| missing or unreadable file | `UncheckedIOException`: `<file>: cannot read the file (<cause>)` |

## Test for normality and Granger causality

`Statistical.shapiroWilk(values)` returns `{W, p}`: W at index `SHAPIRO_WILK_WSTAT` (0), p at index
`SHAPIRO_WILK_PVALUE` (1). Adding a constant to every value does not change them.

`GrangerCausality.grangerCausalityTest(y, x, l)` fits y on its own l past values with an intercept, then on these
and the l past values of x. It returns the p value of the F test with l and n − 2l − 1 degrees of freedom, where n is
the length of y minus l. It writes nothing to standard output.

`l` below 1, `x` and `y` of different length and fewer than 3l + 2 values throw `IllegalArgumentException` with
`l: <l> is out of range; expected a lag of 1 or more`,
`x: <n> values do not match the <m> values of y; expected two series of the same length` and
`y: <n> values are too few for lag <l>; expected at least <3l + 2> values`. A regression that cannot be solved, for
example of constant series, throws `IllegalArgumentException` with
`y, x: the regression of lag <l> over <n> values cannot be solved (<cause>); expected series that are not constant`
and keeps the cause.

## Make a phase-randomized surrogate

`TimeSeriesObjectFFT` pads the n values with zeros to the smallest power of two that is at least n. It sets the
coefficient c_0 to 0 and keeps the amplitude of every other Fourier coefficient of the padded values and the
coefficient at the Nyquist frequency. Each other coefficient gets a new phase, and its mirror gets the conjugate, so
the inverse transform is real. The result holds the first n values of the inverse transform. For a length that is a
power of two the result has mean 0 and the amplitude spectrum of the input. For other lengths neither holds. The
methods differ in the new phase:

| Method | New phase |
|--------|-----------|
| `getModifiedTimeSeries_PhaseRandomized(random)` | old phase plus a uniform random phase |
| `getPhaseRandomizedModifiedFFT_MULTIPLY_RANDOM_INV(random)` | old phase plus a uniform random phase |
| `getPhaseRandomizedModifiedFFT_SHUFFLED_INV(random)` | a random permutation of the old phases |

Pass a `java.util.Random` with a seed to get the same surrogate again. Without the argument, each method uses a new
`Random`. `FFTPhaseRandomizer.getPhaseRandomizedRow(ts, showTest, runDFA, testZaehler, mode)` calls the shuffle for
`MODE_shuffle_phase` and the random phases for `MODE_multiply_phase_with_random_value`, both without a seed. It does
not use `showTest`, `runDFA` or `testZaehler`. None of these methods opens a window. `FFTPhaseRandomizer` has no
`main` method; `LongTermCorrelationSeriesGenerator.main` charts alpha against beta. Any other `mode` of
`getPhaseRandomizedRow` throws `IllegalArgumentException` with
`mode: <mode> is not allowed; expected 0 (MODE_shuffle_phase) or 1 (MODE_multiply_phase_with_random_value)`.
`FFTPhaseRandomizer.nrOfSValues` is deprecated and has no effect.

## Generate long-range correlated noise

`LongTermCorrelationSeriesGenerator.getRandomRow(length, beta, random, showTest, runDFA)` draws `length` Gaussian
values from `random`, multiplies the Fourier coefficient at frequency f by f^(-beta/2) with
`TimeSeriesObjectFFT.getModifiedTimeSeries_FourierFiltered(beta)` and divides the result by its standard deviation.
Every beta, 0 included, is filtered, and the result has variance 1. The same seed gives the same series. The calls
without `random` share one `java.util.Random` with the seed `DEFAULT_SEED` (1), so a program gets the same series in
the same order on every run. The generator needs no `RNGWrapper.init()`. A series that cannot be
normalized throws `IllegalArgumentException` with
`getRandomRow: the filtered series of <n> values has standard deviation <s>; expected at least 2 values that are not all equal`.
With `showTest` and `runDFA` true the fitted alpha goes into the table that `writeTables` allocates; before
`writeTables` the call throws `IllegalStateException` with
`showTest: true needs the alpha table of writeTables, which is not allocated; pass showTest false or call writeTables`.
`writeTables` prints nothing; its progress is DEBUG output.

`LongTermCorrelationSeriesGenerator.main` takes two optional arguments: the output directory, `./data/` by default,
and the seed, 1 by default. It runs DFA on 10 series for each of 15 values of beta and on one more series with beta 0,
writes
`random_numbers_CHECK.csv` and `random_numbers_FS.csv` into the output directory and opens two charts. A seed that
is not a whole number throws `IllegalArgumentException` with
`seed: "<value>" is not a whole number; expected a seed such as 1`.

## Collect return intervals

`RISTool.addDistData(intervals)` sorts the intervals into bins scaled with their mean R_q. It opens no window and
writes nothing to standard output. `add(result)` adds the probabilities of a single result to a container instance.
Errors stop the call:

| Problem | Exception and message |
|---------|-----------------------|
| a probability of the result is NaN or infinite | `IllegalArgumentException`: `add: probability <index> of <name> is <value>; expected a finite probability` |
| the receiver is not a container | `IllegalStateException`: `add: <name> is not a container; set isContainerInstanz to true before adding results` |
| the binning differs | `IllegalArgumentException`: `add: <name> has <n> bins and <container> has <m>; expected the same binning and scale` |
| an interval is 0 or negative while `RISTool.offset` is 0 | `IllegalStateException`: `addDistData: <n> intervals are 0 or negative; expected positive intervals while offset is 0` |
| intervals are added to a container | `IllegalStateException`: `addDistData: <name> is a container; add intervals to a result that is not a container` |
| `RISTool.projekt` is not set for a result with 2 to 1024 intervals | `IllegalStateException`: `add: RISTool.projekt is not set; set RISTool.projekt and RISTool.logFileNames and call RISTool.initRqMIN() before adding <name>, which has <n> intervals` |
| `RISTool.logFileNames` holds fewer than 8 names | `IllegalStateException`: `add: RISTool.logFileNames has <n> names; set 8 names, one per interval range, before adding <name>, which has <n> intervals` |
| `RISTool.initRqMIN()` was not called | `IllegalStateException`: `add: RISTool.rqMIN is not set; call RISTool.initRqMIN() before adding <name>, which has <n> intervals` |
| `RISTool.initRqCollectors()` was not called while `RISTool.offset` is not 0 | `IllegalStateException`: `add: RISTool.debug1 is not set while RISTool.offset is <offset>; call RISTool.initRqCollectors() before adding <name>, which has <n> intervals` |
| `RISTool.projekt` has no log of that name | `IllegalStateException`: `logLine: <folder> has no log named <log>; call createLogFile with that name first` |
| a log line cannot be written | `UncheckedIOException`: `<log>: cannot write the log line (<cause>)` |

`add` checks the container, the binning, the probabilities and the setup first, so a rejected result leaves the
container unchanged and writes no log line. A result without intervals adds nothing. For a result with 2 to 1024
filtered intervals, `add` then writes one line per interval through `RISTool.projekt` to the log named in
`RISTool.logFileNames` and compares each interval with `RISTool.rqMIN`. A result whose R_q is larger than 7200 hours
divided by the lower bound of its interval range gets a line in the log `RqTooLarge` as well. Create the logs with
`OriginProject.createLogFile`. When `RISTool.offset` is not 0, `add` counts an interval below `RISTool.rqMIN` in
`RISTool.debug1`, which `initRqCollectors()` creates, and `addDistData` writes the number of intervals below the offset
to the log named by `setLofFileNameKey_1`, which needs `RISTool.projekt` as well. `_addData` writes a series whose
intervals add up to more than 7200 hours to the log `LENGTH`.

`ReturnIntervallStatistik2` and `TSPropertyTester` refuse a NaN or infinite probability, a receiver that is not a
container, a different binning and intervals added to a container with the same exceptions and messages as `RISTool`.
They write no log files, and `ReturnIntervallStatistik2.setLofFileNameKey_1` is deprecated and has no effect. Their
`_addData(times, rq)` adds the intervals between consecutive times with `addDistData`,
so times 0, 2, 5 and 9 add the intervals 2, 3 and 4. The intervals replace those of an earlier call, and `rq` is not
used. A time before the time before it throws `IllegalArgumentException` with
`_addData: time <k> of <name> is <value>, before time <k - 1> at <previous value>; expected times that do not decrease`.
A time that is NaN or infinite throws `IllegalArgumentException` with
`_addData: time <k> of <name> is <value>; expected a finite time`. `k` counts from 0, and a refused call adds nothing.
`ReturnIntervallStatistik2.addDistData` leaves out intervals that are 0 or below `offset` and sorts the others into bins
scaled with their mean R_q. An interval below the first or past the last scaled bin is counted in that bin, so the
probabilities of a result add up to 1. An interval that is NaN or infinite throws
`IllegalArgumentException` with `addDistData: interval <k> of <name> is <value>; expected a finite interval`, and a
refused call adds nothing. A result that keeps no interval, for example
times 1, 1 and 1, has NaN probabilities and R_q, and `add` skips it. For times 0, 1, 1 and 2 the bins count the
intervals 1 and 1, not the 0.
`TSPropertyTester.addDistData` keeps every interval and sorts it into bins scaled with the mean R_q of its intervals. An
interval below the first or past the last bin is counted in that bin, so the probabilities add up to 1. `setRQ` does not
change the bins, and after `addDistData` `getRq()` returns the mean R_q of the intervals. `setRQ(rq)` after
`addDistData` replaces that mean for `calcMessreihen` and `add`: `mrVerteilungSkaliert` leaves out the bins that start
below `offset` / `rq`, and a container that adds the result records `rq`. An interval that is NaN or
infinite throws `IllegalArgumentException` with
`addDistData: interval <k> of <name> is <value>; expected a finite interval`, and a refused call adds nothing.
For a result that is not a container, `addDistData` also counts the kept intervals as they are, in bins of width
1/`scale`, and `calcMessreihen()` writes these counts: `mrHaeufigkeit` holds the count of bin i at i / `scale`, and
`mrVerteilung` holds its share of all kept intervals. The last bin also counts the longer intervals, and the first bin
also counts the negative ones, which `ReturnIntervallStatistik2` keeps only when `offset` is below 0. With binning 12
and scale 10, the intervals 0.15, 0.25,
0.25, 0.55 and 3.0 give the counts 1 at 0.1, 2 at 0.2, 1 at 0.5 and 1 at 1.2. `mrVerteilungSkaliert` holds the share
of the intervals in scaled bin i at (i + 0.5) * `scale` / `binning`, the center of the bin in units of R_q. With binning
12 and scale 10, the intervals 0.25, 0.25, 0.5 and 3.0 (R_q 1) give the share 0.75 at 5/12, 0.25 at 35/12 and 0 at the
centers of the other bins. When `offset` is above 0, the bins that start below `offset` / R_q are left out: with
`offset` 0.5, the intervals 0.5, 1.0, 1.5 and 3.0 (R_q 1.5) leave out bin 0 and its share 0.5, and the series starts at
1.25. Each call of `calcMessreihen()` replaces the series of the call before.
`SingleTsRISTool` passes the y values of each series as times and adds the result to `risCollector`. For each series
it writes one line: the label, two tabs, the R_q of the result and a tab. R_q is the mean of the kept intervals, so the
values 0, 2, 5 and 9 give 3.0. A series that keeps no interval, for example the values 3, 3 and 3, writes NaN and adds
nothing to `risCollector`. A series whose values go down throws the `_addData` refusal above and writes nothing.
`SingleTsRISTool.finish()` throws `IllegalStateException` with
`finish: the RIS of <name> cannot be shown (<cause>)` when the collected result cannot be shown.

## Run the DFA

`DetrendingMethodFactory.getDetrendingMethod(order)` returns a DFA of that order. It fits a polynomial of that order to
the profile in every window by least squares, so a DFA of order n removes a trend of order n − 1 from the series.
`DFACore.fitn(u, o, n)` returns the coefficients c_0 to c_n of c_0 + c_1 (i − u) + ... + c_n (i − u)^n. Fewer than
n + 1 points throw `IllegalArgumentException` with
`fitn: <points> points cannot determine a polynomial of order <n>; expected at least <n + 1> points`.

`initIntervalSlog()` chooses window sizes from order + 2 to the number of values. A series of fewer than 22 values
throws `IllegalArgumentException` with `initIntervalSlog: <n> values are too few; expected at least 22 values`.

`MultiDFATool.runDFA(series, order)` runs the DFA of every series at the window sizes of the longest one, writes the
F(s) of every series to `<savePath><index>_dfa<order>.txt`, opens three charts and fits alpha to log F(s) of all series
from log s 1 to 3. Set the public field `savePath`, which ends with a separator; the default is `opentsx/dfa/` below
`user.home`. `SingleDFATool` and `MultiDFATool2` throw
`IllegalArgumentException` for an empty list (`calcMultiDFA: vmr has no series; expected at least 1 series`) and for a
missing series (`runDFA: series <index> is null; expected a series at every index`).

`FluctuationFunktion.calcAlpha(range)` fits alpha, the slope of the F(s) series for x from `range.getULog10()` to
`range.getOLog10()`, and prints nothing. It returns NaN when the values of F(s) in that range have fewer than two
different x values. An F(s) without values throws `IllegalStateException` with
`<label>: alpha cannot be fitted in [<min>, <max>]; expected a fluctuation function with values`.

## Write a measurement table

`MeasurementTable.writeToFile(file)` creates the missing parent directories and writes one column pair or column per
series. A shorter series is filled up with `fill_UP_VALUE`, -5.0 by default. A value that cannot be read throws its
exception. Errors stop the write:

| Problem | Exception and message |
|---------|-----------------------|
| the parent directory cannot be created | `UncheckedIOException`: `<file>: cannot create the parent directory (<cause>)` |
| the file cannot be written | `UncheckedIOException`: `<file>: cannot write the file (<cause>)` |

`OriginProject.initBaseFolder(folder)` creates the folder and throws `UncheckedIOException` with
`<folder>: cannot create the project folder (<cause>)` when it cannot.

## Expand event times and synchronize events

`MRT.expand(series, start, end, resolution, onePerField)` counts the events per field between start and end. Field k
holds the events from start + k · resolution up to start + (k + 1) · resolution, and with `onePerField` it holds 1 for
any number of events. An end that is not after the start throws `IllegalArgumentException` with
`expand: the end <end> ms is not after the start <start> ms; expected an end after the start`. An event before the
start throws `IllegalArgumentException` with
`expand: the event at <t> ms of <label> is before the start <start> ms; expected events at or after the start`. Events
at or after the end and events in a last field that is shorter than the resolution are left out.

`ESCalc2.calcES(tx, ty)` needs at least 3 events in each series. Fewer throw `IllegalArgumentException` with
`calcES: tx has <n> events and ty has <m>; expected at least 3 events in each`.

## Set the log level of the production demo

`org.opentsx.demo.onboarding.ProductionConfig` (`bin/episode_10_production_config.sh`) sets the level of the
`java.util.logging` root logger from `OPENTSX_LOG_LEVEL`, then from `opentsx.log.level` in `./opentsx.properties`, then
`info`. It maps `error`, `warn`, `info`, `debug` and `trace` to `SEVERE`, `WARNING`, `INFO`, `FINE` and `FINEST`. The
console shows INFO and above; `opentsx.log` in the working directory gets every line the root level lets through.
Another value stops the constructor with `IllegalArgumentException`, for example
`OPENTSX_LOG_LEVEL: "verbose" is not allowed; expected one of error, warn, info, debug, trace`. The names are case
sensitive, so `INFO` is refused with `opentsx.log.level: "INFO" is not allowed; expected one of error, warn, info, debug, trace`.
An empty or blank `OPENTSX_LOG_LEVEL` counts as unset, and blanks around a level are ignored.

## Draw random numbers

`RNGWrapper` draws from one `java.util.Random` with the seed `RNGWrapper.DEFAULT_SEED` (1), so a program gets the
same numbers in the same order on every run. It needs no init call. `RNGWrapper.init(seed)` starts the sequence again
with that seed, and `RNGWrapper.init()` with seed 1. `TimeSeriesObject.getGaussianDistribution(n, mean, sd)` and the
other random series of `TimeSeriesObject` draw from it. `RNGWrapper.getStdPoisson(lambda)` draws a Poisson number with
mean `lambda`.

## Configure the 2.x tools

`ConfigManager.getInstance()` reads the resource `opentsx-default.properties`, then
`config/opentsx-default.properties` when that file exists. A file named by `opentsx.config.file` or
`OPENTSX_CONFIG_FILE` must exist. Without such a file, `config/opentsx-<profile>.properties` must exist for a profile
set by `opentsx.config.profile` or `OPENTSX_CONFIG_PROFILE`; with one, the profile file is not read. The system
property wins over the variable, and only the value that wins is checked. When that value is empty, or a file is
missing or does not load, the first call of `getInstance()` fails with `ExceptionInInitializerError` and later calls
with `NoClassDefFoundError`. The cause names the key, the variable or the file:

| Problem | Cause and message |
|---------|-------------------|
| empty or blank value | `IllegalArgumentException`: `<name>: empty value; set a value or leave the key unset`, where `<name>` is the key or variable that holds the value |
| missing file | `IllegalArgumentException`: `<path>: no such file; give an existing file` |
| unreadable file | `UncheckedIOException`: `<path>: cannot read the file (<cause>)` |
| malformed `\u` escape | `IllegalArgumentException`: `<path>: malformed \uxxxx escape; write \u followed by four hexadecimal digits` |

`getInt(key, default)` returns the default for an unset key. A value that is not a whole number throws
`IllegalArgumentException` with `<key>: "<value>" is not an int; expected a whole number from -2147483648 to 2147483647`.
