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
Swing control tests use a display; the Java CI jobs run them under Xvfb on Ubuntu 24.04.

## Reload local SequenceFile buckets

The file and string loaders stop at the actual end of a SequenceFile, including empty buckets.
The file loader reads at most `LIMIT` records; a zero limit reads no records.
Readers close after loading or failure. A failing `TSOperation` stops loading with `IOException`
and retains the original exception as its cause. Processing output writers close on failure.

The SequenceFile writer stores each series label and Y values. Loading reconstructs sequential X values;
arbitrary input X coordinates and other metadata are not persisted by this format.

## Write a bucket

A `TSBucket` writes through the default Hadoop file system of its configuration. When that file system cannot be
opened, every method that uses it throws `IOException` with
`<default file system URI>: cannot open the file system (<cause>)`. `createBucketWithRandomTS_sinus` writes a
SequenceFile when `SKIP_WRITES` is false and `opentsx.demo.output.format` is not `csv`; in that case it throws and
writes no CSV file instead. `_open`, `putMessreihe` and `close` throw the `IOException` of the writer, so opening a
bucket under a regular file fails with a message that names that file.

`createBucketFromLocalFilesInDirectory(sourceFolder, groupFolder, limit)` reads the files in
`<sourceFolder>/<groupFolder>`. There is no default source folder. A folder that cannot be listed throws
`IOException` with `<sourceFolder>/<groupFolder>: cannot list the files; expected a readable folder` before the
bucket file is created.

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
`dfy` and `dfstat` hold the default pattern.

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
| no data line in `_loadLogBinnedMessreihe` | `IllegalArgumentException`: `<file>: the file has no data lines; expected at least one line with <n> columns separated by ","` |
| missing or unreadable file | `UncheckedIOException`: `<file>: cannot read the file (<cause>)` |

## Test for normality and Granger causality

`Statistical.shapiroWilk(values)` returns `{W, p}`: W at index `SHAPIRO_WILK_WSTAT` (0), p at index
`SHAPIRO_WILK_PVALUE` (1). Adding a constant to every value does not change them.

`GrangerCausality.grangerCausalityTest(y, x, l)` fits y on its own l past values with an intercept, then on these
and the l past values of x. It returns the p value of the F test with l and n − 2l − 1 degrees of freedom, where n is
the length of y minus l. It writes nothing to standard output.

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
not use `showTest`, `runDFA` or `testZaehler`. None of these methods opens a window.

## Generate long-range correlated noise

`LongTermCorrelationSeriesGenerator.getRandomRow(length, beta, random, showTest, runDFA)` draws `length` Gaussian
values from `random`, multiplies the Fourier coefficient at frequency f by f^(-beta/2) with
`TimeSeriesObjectFFT.getModifiedTimeSeries_FourierFiltered(beta)` and divides the result by its standard deviation.
Every beta, 0 included, is filtered, and the result has variance 1. The same seed gives the same series. The calls
without `random` share one `java.util.Random` with the seed `DEFAULT_SEED` (1), so a program gets the same series in
the same order on every run. The generator needs no `RNGWrapper.init()`. A series that cannot be
normalized throws `IllegalArgumentException` with
`getRandomRow: the filtered series of <n> values has standard deviation <s>; expected at least 2 values that are not all equal`.

`LongTermCorrelationSeriesGenerator.main` takes two optional arguments: the output directory, `./data/` by default,
and the seed, 1 by default. It runs DFA on 10 series for each of 15 values of beta and on one more series with beta 0,
writes
`random_numbers_CHECK.csv` and `random_numbers_FS.csv` into the output directory and opens two charts. A seed that
is not a whole number throws `IllegalArgumentException` with
`seed: "<value>" is not a whole number; expected a seed such as 1`.

## Collect return intervals

`RISTool.addDistData(intervals)` sorts the intervals into bins scaled with their mean R_q. It opens no window and
writes nothing to standard output unless `RISTool.debug` is set. `add(result)` adds the probabilities of a single
result to a container instance. Errors stop the call:

| Problem | Exception and message |
|---------|-----------------------|
| a probability of the result is NaN or infinite | `IllegalArgumentException`: `add: probability <index> of <name> is <value>; expected a finite probability` |
| the receiver is not a container | `IllegalStateException`: `add: <name> is not a container; set isContainerInstanz to true before adding results` |
| the binning differs | `IllegalArgumentException`: `add: <name> has <n> bins and <container> has <m>; expected the same binning and scale` |
| an interval is 0 or negative while `RISTool.offset` is 0 | `IllegalStateException`: `addDistData: <n> intervals are 0 or negative; expected positive intervals while offset is 0` |
| intervals are added to a container | `IllegalStateException`: `addDistData: <name> is a container; add intervals to a result that is not a container` |

`add` checks the container, the binning and the probabilities first, so a rejected result leaves the container
unchanged and writes no log line. A result without intervals adds nothing. For a result with 2 to 1024 filtered
intervals, `add` then writes one line per interval through `RISTool.projekt` to the log named in
`RISTool.logFileNames` and compares each interval with `RISTool.rqMIN`. Set `RISTool.projekt` and
`RISTool.logFileNames` and call `RISTool.initRqMIN()` first. Without them, `add` throws `NullPointerException`. When
`RISTool.offset` is not 0, also call `RISTool.initRqCollectors()`: `add` counts an interval below `RISTool.rqMIN` in
`RISTool.debug1`, which `initRqCollectors()` creates.

## Draw Poisson numbers

`RNGWrapper.getStdPoisson(lambda)` draws a Poisson number with mean `lambda`. Seed the generator first with
`RNGWrapper.init()` (seed 1) or `stdlib.StdRandom.initRandomGen(seed)`.

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
