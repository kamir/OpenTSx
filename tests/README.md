# Run exercise validation

Validate the legacy `TimeSeriesObject` exercises against the current checkout with the Maven test runner.
You need JDK 17 or later and Maven 3.8 or later.

## Run the checks

From the repository root, run:

```bash
./bin/run_all_tests.sh --full
```

The runner executes `ExerciseValidationTest` through Maven, checks the syntax of the four episode demo scripts,
and checks that five required documentation files exist. Missing scripts and documents fail the run.
Script syntax checks do not execute demos or measure performance.

For the Maven exercise checks alone, use either command:

```bash
./bin/run_all_tests.sh --validation-only
./bin/run_all_tests.sh --quick
```

To check only demo script syntax, run:

```bash
./bin/run_all_tests.sh --demo-only
```

The runner writes console output and check results to `test-report.txt` and a summary to `test-summary.json`.
The summary counts runner checks, not individual assertions. Maven reports the individual test cases separately.
The runner returns zero only when all selected checks pass. Invalid options return status 2.

## Inspect the regression cases

`opentsx-core/src/test/java/org/opentsx/app/bucketanalyser/ExerciseValidationTest.java` compiles the validation
sources from `tests/validation` with the actual Maven test classpath and runs each suite in a separate JVM.
Both episodes must complete all four exercises without failed assertions.

Two additional cases corrupt a fixture in a temporary source copy and verify that the suite returns a nonzero
status. Four runner cases verify Maven invocation, failure propagation through `tee`, invalid options and
missing demo scripts. The runner cases use a local executable fixture instead of starting Maven recursively.
These cases run in the Java CI matrix as part of `mvn verify`.

Episode 2 checks manual creation, seeded synthetic distributions, filtering and a trend with seasonal noise.
The signal's standard deviation includes the trend, seasonality and noise variance.
Episode 3 checks normalization, mutability, arithmetic and three six-hour windows.
`scaleY_2` returns a scaled copy; `add_to_Y` modifies the input.
Means are recalculated before comparison. Each suite initializes the existing random generator with a fixed seed.

## Use the validation helper

`tests/validation/ExerciseValidator.java` provides checks for file existence and line counts, numeric statistics,
ranges, series values and custom boolean conditions. A suite must inspect `allPassed()` and propagate failure
through its process status.

See the [current user manual](../docs/user-manual/README.md), [exercise materials](../exercises/README.md)
and [demo scripts](../bin/README.md).
