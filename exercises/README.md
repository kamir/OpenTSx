> **Legacy (2.x).** These exercises use the 2.x `TimeSeriesObject` API from `opentsx-core`. For the current data model and tools, see the [user manual](../docs/user-manual/README.md).

# OpenTSx Exercises

Hands-on exercises for the 2.x `TimeSeriesObject` API of `opentsx-core`, ordered by learning track and episode.

1. **SWE track (software engineering):** for developers new to time series analysis.
2. **TSx track (time series experts):** for data scientists who come from R, Python or MATLAB.

## Find an exercise

```text
exercises/
├── README.md
├── swe-track/
│   ├── episode-02/EXERCISES.md    # Creating time series
│   ├── episode-03/EXERCISES.md    # Basic operations
│   └── episode-10/EXERCISES.md    # Production configuration
├── tsx-track/
│   └── episode-09/EXERCISES.md    # Statistical analysis
└── solutions/
    └── swe-track/
        ├── episode-02/            # Exercise1_ManualCreation, Exercise2_SyntheticData,
        │                          # Exercise3_LoadAndTransform, BonusExercise_PatternCreation
        └── episode-03/            # Exercise1_Normalization, Exercise2_MutabilityDemo
```

Solutions exist for episode 2 and for exercises 1 and 2 of episode 3. Episodes 9 and 10 have none.

| Episode | Track | Topics | Demo |
|---------|-------|--------|------|
| [Episode 2: Creating time series](swe-track/episode-02/EXERCISES.md) | SWE | manual construction, synthetic data, loading CSV files, file I/O | `bin/episode_02_create_timeseries.sh` |
| [Episode 3: Basic operations](swe-track/episode-03/EXERCISES.md) | SWE | normalization, in-place and immutable operations, arithmetic, windows and subsets | `bin/episode_03_basic_operations.sh` |
| [Episode 9: Statistical analysis](tsx-track/episode-09/EXERCISES.md) | TSx | moving averages (SMA, WMA, EMA), autocorrelation, trends and detrending, anomaly detection by z-score | `bin/episode_09_analysis.sh` |
| [Episode 10: Production configuration](swe-track/episode-10/EXERCISES.md) | SWE | configuration from several sources, retry with exponential backoff, structured logging, a thread-safe resource pool | `bin/episode_10_production_config.sh` |

Start with episode 2. On the TSx track, also work through SWE episodes 2 and 3 for the Java patterns of OpenTSx.
Each `EXERCISES.md` lists its learning objectives, starter code with `TODO` markers and a validation checklist. Run
the demo of an episode before its exercises; the demo classes are in
`opentsx-core/src/main/java/org/opentsx/demo/onboarding/`. Try an exercise before you open its solution.

## Build the project

You need JDK 17 or 21 and Maven 3.9 or later. Run the commands from the project root.

```bash
./bin/010_build.sh
./bin/000_validate_environment.sh
```

## Compile and run a solution

```bash
CLASSPATH="$(bin/opentsx classpath opentsx-core)"
mkdir -p exercises/build
javac -cp "$CLASSPATH" -d exercises/build exercises/solutions/swe-track/episode-02/Exercise1_ManualCreation.java
java -cp "$CLASSPATH:exercises/build" org.opentsx.exercises.swe.solutions.Exercise1_ManualCreation
```

`exercises/build/` is git-ignored. The solutions write their CSV files, such as `exercise1_output.csv`, into the
current directory, and `Exercise3_LoadAndTransform` reads `sample_data/sensor_data.csv`, so run them from the
project root. Your own exercise compiles the same way with its file path and its package and class name.

## Fix common errors

**`cannot find symbol`:** build the project with `./bin/010_build.sh`, check that `CLASSPATH` holds the output of
`bin/opentsx classpath opentsx-core`, and check the imports. The random number helper is
`org.opentsx.data.generator.RNGWrapper`.

**`incompatible types` when you read a value:** `yValues` is a raw `Vector`. Cast its elements:

```java
double value = (Double) ts.yValues.elementAt(i);
```

**A CSV file is not found:** the exercises open paths relative to the project root, such as
`sample_data/sensor_data.csv`. Run them from there.

## Read more

- [User manual](../docs/user-manual/README.md) and [Getting started](../docs/user-manual/02-getting-started.md)
- [opentsx-core, the 2.x library](../opentsx-core/README.md)
- [Classic analytics](../docs/user-manual/11-classic-analytics.md), with the
  [Java tools on 2.x data](../docs/user-manual/11-classic-analytics.md#115-run-the-java-tools-on-2x-data)
- [Operations](../docs/user-manual/12-operations.md)
- [The scripts in `bin/`](../bin/README.md)
- [Local development](../docs/devguide/infrastructure/local-development.md)
