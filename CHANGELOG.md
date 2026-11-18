# Changelog

All notable changes to OpenTSx are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/). Releases are tagged `v<version>`;
entries before 3.0.0 are grouped by date. The detailed progress log is in
[`EVOLUTION/TASK-011-datamodel-refresher-2026.md`](EVOLUTION/TASK-011-datamodel-refresher-2026.md).

## [Unreleased]

### Added

- Operator catalog: user-manual chapter [17](docs/user-manual/17-operators.md) lists 71 time-series operators, 50 with a
  runtime and 21 planned or missing, with their specification, the status per runtime (workbench, java, python,
  processor, go) and an example. `org.opentsx.retrieval.system.OperatorCatalog` generates the chapter from one
  description per operator in `systems/processors/<id>.yaml`. `OperatorCatalogTest` fails when a runtime marked
  `available` names a Java class, Python name or workbench operator that does not exist, when a planned runtime has no
  plan, or when the chapter is out of date. The schema `opentsx.processor.v1` gains the fields `category`, `docs`,
  `plan`, `literature` and `example` and the UI group `multivariate`.
- Operator examples: 46 example tests (7 Python, 27 workbench, 12 Java) feed each operator input with a known property
  and check the result, for example the DFA exponent of white noise, a planted lag for `pair.xcorr` or an injected stuck
  sensor for `dq.stats`. With `OPENTSX_EXAMPLES_DIR` set they write their chart data, and `tools/operator_charts.py`
  draws the charts in `docs/user-manual/img/operators/`.

- `opentsx-conf` (TASK-020, Java foundation): the key registry `opentsx.config.v1` with 126 keys and 48 commands,
  `OpenTsxConf` with six sources (code, command line, environment, `--properties-file`, the defaults file in
  `$OPENTSX_CONF_DIR`, registry default), properties and YAML files, one type grammar and fail-closed errors with exit
  code 2. `bin/opentsx` gets `config list|show|get`, `version` and `--version` (`org.opentsx.conf.ConfCli`); secrets
  are shown as `******` in every output and error message and refused on the command line. A file that is not UTF-8,
  a value that is not valid UTF-8 (Java without a UTF-8 locale), `key:value` in a properties file, a misnamed
  `opentsx-defaults.yml` and YAML nested deeper than 100 levels exit with 2; a result that cannot be written to stdout
  exits with 1. `--help` wins over an unknown command. The module passes all 272 shared vectors
  `docs/datamodel-2026/testvectors/conf.v1.json`. No main class reads the registry yet.

- Python configuration and command line (TASK-020, foundation for Python): the package `opentsx.conf` with
  `OpenTsxConf` (`set`, `setIfMissing`, `setAll`, `get`, `getOption`, typed getters, `contains`, `getAll`, `source`,
  `toDebugString`, `load`, `registry`, `SOURCES`, the same names as in Java) and `OpenTsxConfError`, a byte-identical copy of the registry `opentsx.config.v1.json`, the
  console script `opentsx` with `config list`, `config show`, `config get` and `version`, and the extra
  `opentsx[yaml]` (`PyYAML>=6.0.1`) for YAML files. The tests run every case of the shared vectors
  `docs/datamodel-2026/testvectors/conf.v1.json`; CI installs the extra on Python 3.12 only, so the other versions
  check the message that names the extra.
- Configuration documentation (TASK-020): user-manual chapter [18](docs/user-manual/18-configuration.md) shows how to
  show, set and read the configuration with `bin/opentsx`, the Python console script `opentsx` and the API of both
  runtimes, the precedence of the six sources, defaults files, YAML, secrets and the error messages with their fix.
  Appendix [D](docs/user-manual/appendix-d-configuration.md) lists the 126 keys in 17 areas with type, default, alias,
  environment variables, meaning and first release. `org.opentsx.conf.ConfReference` generates the appendix and the
  defaults-file templates `conf/opentsx-defaults.conf.template` and `conf/opentsx-defaults.yaml.template` from the
  registry; `ConfReferenceTest` fails when one of the three files differs from the rendering and names the command
  that regenerates them.

- `pair.es` in the web workbench (TASK-015 phase 1d, operator "Event synchronisation"): event synchronisation after
  Quiroga, Kreuz and Grassberger per pair window, ported from `event_sync.py`, with the fields `q_total`, `q_ab` (B
  after A), `q_ba` and `q_rand`, the mean `q_total` against copies of B with shuffled intervals between its events.
  The Python package gets the same surrogate (`EventSynchronization.surrogate_sync`, `Mulberry32`, `isi_shuffle`);
  both run the shared vectors `docs/datamodel-2026/testvectors/pair-es.v1.json` (`tools/pair_es_vectors.py`).

- Concept for graph layers on KafScale and Iceberg
  ([`docs/datamodel-2026/19-GRAPH-LAYERS.md`](docs/datamodel-2026/19-GRAPH-LAYERS.md), proposal, not implemented):
  one node table and one edge table for twin wiring, inventory, measured dependencies, expert influence matrices,
  GeoQB location context on H3 cells and software architecture; immutable layer versions sealed with a checksum and
  Iceberg snapshot IDs; serving to QGIS, ArcGIS and graph tools; an eight-step implementation plan.

- `pair.xcorr` in the web workbench (TASK-015 phase 1c, operator "Cross-correlation"): per pair window the lagged
  cross-correlation CC(k) for k = -lags..lags clock ticks, with the fields `max` (peak of |CC|, sign kept), `lag` (in
  seconds, > 0 when B follows A) and `strength` ((|max| - mean) / std of CC). Ported from `KreuzKorrelation` and
  `CCFunction` of kamir/WikiExplorer.NG (Apache License 2.0); `tools/pair_xcorr_vectors.sh` runs the original code
  for the test vectors `docs/datamodel-2026/testvectors/pair-xcorr.v1.json`, which the port matches to 12 decimals.

- Pair windows in the web workbench (TASK-015 phase 1b, `src/workbench/pairs.ts`): a pair method compares two series
  window by window and stamps each result at the window centre. Episodes of A and B are paired by the same range or
  on their intersection; `window: episode` gives one point per pair range, a width and step give a regular series per
  pair range. A and B go onto one clock by the rules of `align.vector`; a window below the minimum coverage keeps its
  place as NaN with quality 1. Results carry the metric `pair.<method>.<field>`, the tags a, b, window and step, the
  series id from xxHash64 in the browser (`seriesKeys.ts`, checked against the shared vectors) and episodes labelled
  with both inputs. First method: "Pair coverage" in the new tab "Multi-variate Analysis".
- The web workbench keeps the episode ranges of loaded episodes (`Bucket.episodes`): series, episode id, [start, end)
  from `tStart`/`tEnd` to the microsecond, and the segmentation strategy. Operators that keep the time axis hand them
  on; the Properties tab shows how many episodes a bucket holds. First step of the pair windows of TASK-015.

- `WireRoundTripTest` (`opentsx-legacy-bridge`) checks success criterion 2 of TASK-011 in one chain:
  `TimeSeriesObject` to `Episode` to Kafka in single-object and in Confluent framing to `Episode` to
  `TimeSeriesObject`, regular and irregular, through the Kafka client records and, with `KAFSCALE_BOOTSTRAP`,
  through KafScale (now part of the KafScale CI job).
- `PatternBenchmarkTest.recallAtTenMeetsTheSuccessCriterion` asserts recall@10 of at least 0.95 for 10 planted gusts
  with z-normalized Euclidean distance (criterion 7, D87). The recall@12 report stays.

- `StatsJob` in `opentsx-flink-core` (`org.opentsx.flink.stats`) runs the v2 statistics engine as a Flink job:
  `KafkaSource` without offset commits, one `StatsEngine` per series in keyed state with its own state serializer,
  GLOBAL on a timer, all windows closed at the end of a bounded run, `KafkaSink` at least once. It writes the same
  `SeriesStats` as `StatsProcessorApp`. Launcher verb `bin/opentsx stats-flink`; main class of the shaded jar.

- Smoke test against a real OpenMetadata 2.0.5: the workflow `openmetadata-smoke.yml` (nightly, by hand, and on pull
  requests that change `opentsx-openmetadata/`) starts the server from the pinned compose file of the release, gets
  the token of `ingestion-bot`, and `OpenMetadataSmokeTest` publishes the simulated wind farm and reads back topics,
  tables, lineage, the latest profile and data-quality results.

- The KafScale conformance suite probes `Admin.listOffsets` with `OffsetSpec.earliest()` and `OffsetSpec.latest()`,
  which the Flink Kafka source uses for end offsets and bounded runs. Both answer correctly on KafScale v1.6.0.
- tools/check_tracked_config.py and a CI step on Python 3.12 fail when a tracked file is under config/private/ or
  .idea/, when a *.props, *.properties or .env file names a host or address that is not localhost or a placeholder, or
  when any tracked file names a Confluent Cloud or *.gce.cloudera.com host. The same step runs the tests in tools/.

### Changed

- User manual 11.3 no longer lists `overall_sync` above 1 as a known issue of `EventSynchronization`: the local
  window of Quiroga et al. fixed it (93499604). It describes the local window and `surrogate_sync` instead.
- TASK-011 (data model v2, KafScale, stores, wind farm, statistics, OpenMetadata, retrieval) is completed: all seven
  success criteria are met, criterion 4 by D86 (`docs/datamodel-2026/18-CLOSING-TASK-011.md`, section 9).
- `EVOLUTION/TASK-011-datamodel-refresher-2026.md` is in English. Its phases 6 to 9 and its backlog moved to the new
  tasks TASK-015 to TASK-019 and to TASK-010 (D90 in `docs/datamodel-2026/18-CLOSING-TASK-011.md`). TASK-015 adds
  the design of pair windows for multivariate analysis.
- Numbers in DAG nodes, demo generators, system documents and retrieval POST bodies are taken as YAML or JSON numbers
  only. A number in quotes, such as `factor: "1.5"` or `"topK": "10"`, was read as that number and is now refused;
  write it without quotes. A deployed DAG that holds one gets the status `failed` from the vector processor.
- The `Character` argument of `TimeSeriesObject.writeToFile(file, separator)` is the column separator; it no longer
  replaces commas in the output.
- `TimeSeriesObject` and `MessreihenLoader` write their comments, messages and diagnostics in English. `shift` throws
  `shift: offset <offset> exceeds the <n> values; expected an offset from -<n> to <n>`, `split` notes `<n> missing
  values replaced with mean=<mean>.`, `cutFromStart` labels `( -<n> values )` and `calcAveragOfRows` labels `mean of
  <n> series`. The label suffixes `_Profil` and ` (stdv=1.0;mw=0.0)` and the statistics key `mw__Y` are unchanged,
  because the workbench copies them.
- `MFDFA.analyze` returns h(2) as `h_2` instead of h(0.5), and `MFDFA` raises `ValueError` for a `q_range` without 2
  (up to 1e-10); add 2 to custom q ranges. `is_multifractal` compares `delta_h` with the largest `delta_h` of 19
  phase-randomized copies, which keep the power spectrum (new key `delta_h_surrogates`, parameters `surrogates` and
  `seed`), instead of the threshold 0.1. Gaussian series with linear correlations pass in about 1 of 20 cases; values
  with a broad distribution pass.
- `opentsx-saas-backend/requirements-dev.txt` pins `PyYAML==6.0.3`, which the compose tests import.

### Removed

- `TimeSeriesAnalysisJob`, the 2.x Flink example job. `StatsJob` is the v2 Flink job. `TimeSeriesAggregateFunction`
  stays, because the PFD runtime `DynamicTopologyJob` uses it.
- `TimeSeriesObject.fillGapWithValue`, which ignored its arguments and did what `replaceZeroWithAverage` does, and
  `TimeSeriesObject.add2`; use `replaceZeroWithAverage` and `add`.
- `RIS.analyze` no longer returns `gamma` and `tau_0` and raises `ValueError` with fewer than 3 events instead of
  returning `Insufficient events`; call `RIS.stretched_exponential_fit(result["return_intervals"])` for the fit.
- `config/private/ccloud.props` from the index. `config/private/` is git-ignored; the file stays in the history, so
  the Confluent Cloud key it holds must be rotated.
- The Redis service of both compose files, which no code used and which took its password on the command line, with
  its volume and the backend's `REDIS_*` entries.
- The commented Confluent Cloud bootstrap hosts in TSOProducer and TSOEventAnalysisProducer, and the commented copy
  commands with a real host and the unreachable failure branch in bin/010_build.sh.

### Fixed
- Streaming deployment rejects unsupported sinks, duplicate node and sink identities, missing inputs, unsupported
  decoders and cycles before publishing control records. Supported sinks are accepted together.
- `market`: error messages, the backfill log and stderr no longer show the ENTSO-E token. The value of every URL query
  parameter whose name contains `token`, `secret` or `key` shows as `***`, also in the response text and when the URL
  cannot be parsed. A request refused with an HTTP status, a rejected ENTSO-E token and a rejected netztransparenz.de
  client end `prices`, `series`, `rebap`, `marktwert` and `mosmix-archive` with one line on stderr and exit code 1
  instead of a stack trace.
- `market weather-backfill`, `weather-forward` and the history twins read the sites file strictly. An unknown or
  missing field, a value of the wrong type, a number that is not finite or a file that is not valid YAML fails with
  exit code 2 and one line that names the file and the field or the line. Latitude and longitude are required. A
  weather backfill without any station fails instead of doing nothing with exit code 0.
- `market`: an ENTSO-E HTTP 400 counts as a month without data only when the response is the `No matching data found`
  acknowledgement. Any other 400 fails the year instead of reporting it empty, and the message shows the code and text
  of the acknowledgement's reasons.
- Statistics: `StatsEngine.Config` refuses an alpha of 1 or more or NaN, a negative lateness or one that is not a
  whole number of microseconds, a window length given twice (which counted every value twice) and a gap factor that is
  not above 1, with a message that names the setting, so `bin/opentsx stats --alpha 1` stops at start instead of
  reading the topic and writing no statistics. `StatsProcessor`, the Flink `StatsFunction` and
  `EpisodeIndexer.rebuild` count only records that do not decode; a failure of the engine or the index now stops them,
  and the processor names the topic, partition and offset and leaves the checkpoint before that record. A record that
  decodes but is not a consistent episode, for example a count that differs from the number of values, is such an
  engine failure and is no longer counted as undecodable.
- Statistics processor and `LogTail`: records that retention deleted after the checkpoint position (processor, when a
  run starts) or after the previous poll (`LogTail`) are counted (`RunResult.lostToRetention`,
  `LogTail.lostToRetention()`, `EpisodeLogTail.lostToRetention()`) and reported with one stderr line per partition
  that names the topic, partition, position and log start; a log start above 0 on the first read is not counted, and
  neither is a gap that opens while the processor runs.
- `BucketWriter`: when an index entry cannot be added, the append future completes exceptionally and the bucket is not
  sealed; before, the future stayed open and `seal()` wrote SEALED after a 5-minute wait. `seal()` also refuses to
  seal when it is interrupted, keeping the interrupt, or when acknowledgements time out.
- Docs: a window in which a series has no value gives no `SeriesStats` record, so COMPLETENESS cannot flag it; the
  outage shows in GAPS of the window where values resume, when the expected interval is known, and in COMPLETENESS of
  a longer window.
- `windpark-sim` no longer ignores failures at the end of a run: if writing the last Iceberg batch or closing the
  producer, the episode index or the Iceberg store fails, the command exits with code 1 without the summary. If the
  run had already failed, these close failures are attached to that error as suppressed exceptions.
- The `PowerCurveAnalysis` Javadoc and manual chapter 6.5 state which intervals are left out: NaN values are skipped,
  and only an interval without any wind or power value is dropped.
- Text that is no number in a DAG node (`convert.unit`, `dq.checks`, `model.wind-shear`, `model.power-curve`,
  `feature.lag`), a demo generator, a system document (`seed`, `value`, expected min and max) or a retrieval POST body
  (`topK`, `band`, `offset`, `length`, `pattern`, segment parameters) was taken as the default: `factor: "1,5"` gave 1
  and `min: "0 kW"` gave no lower bound. It is now refused with the node or field, for example `node n3: factor: "1,5"
  is not a number; expected a number without quotes, such as 0.01 or 1e-3`, and the REST routes answer 400. A min or
  max of a channel catalog that is no number is reported under the catalog, for example `catalog wind/WTUR.W: min:
  "-100 kW" is not a number; ...`. A manual source with `value: null` and no `table` is reported as `a manual source
  needs value or table` instead of taking 0. `POST /v1/segments` answers 400 without `prominence` or `enter` instead
  of using 0.
- A `feature.rolling` node whose window is no duration was refused with a message about `every`. The message now names
  `window`.
- The Javadoc of the power curve step of `CleanChain` sat on the wind shear step. Each step now carries its own.
- `feature.rolling` in the vector processor dropped the oldest values of a window that held more than 100000 values,
  without a message. The sink now fails with an error that names the node.
- The vector processor started a sink over when its checkpoint could not be read or was empty, so a `latest` sink
  skipped data and overwrote the file. The sink now fails, its status names the file, and the file is kept. An error
  while applying the control topic is no longer reported as an unreadable control topic. Such an error now ends the
  processor; started again, it reads the control topic from the beginning.
- `--systems-root .`, the default of the retrieval server, reported every reference of a system document as missing.
  References now resolve against the absolute root.
- `GET /v1/episodes?resample=` with a step that gives more than 100000 points for one episode allocated memory for
  every point. It now answers 400 naming `resample`. JSON bodies over 262144 bytes to `POST /v1/patterns/search`,
  `/v1/episodes/similar` and `/v1/segments` now answer 413, like `/v1/systems/{id}/...`. All four answer 413 with the
  error `request body: larger than 262144 bytes; send at most 262144 bytes`; `/v1/systems/{id}/...` answered `the
  request is larger than 262144 bytes` before.
- `ConfigManager` stops with `<path>: no such file; give an existing file` when `OPENTSX_CONFIG_FILE` or
  `opentsx.config.file` names a missing file, or, when neither is set, a profile does. It stops with `<name>: empty
  value; set a value or leave the key unset` when the value it uses is empty. It reports files that do not load, and
  `getInt` stops on a value that is not a whole number instead of returning the default.
- Phase-randomized surrogates of `TimeSeriesObjectFFT`, also used by `FFTPhaseRandomizer`, set c_0 of the zero-padded
  values to 0, keep the amplitude of every other Fourier coefficient and the Nyquist coefficient, and open no chart
  windows. A surrogate holds the first n values of the inverse transform, so it has mean 0 and the amplitude spectrum
  of the input without c_0 only when n is a power of two. `getModifiedTimeSeries_PhaseRandomized`,
  `getPhaseRandomizedModifiedFFT_MULTIPLY_RANDOM_INV` and `getPhaseRandomizedModifiedFFT_SHUFFLED_INV` take a
  `java.util.Random` to repeat a surrogate. `FFTPhaseRandomizer` mode 0 permutes the phases and mode 1 adds random
  phases.
- `Statistical.shapiroWilk` no longer changes when a constant is added to the values, and its Javadoc gives the result
  order W, p.
- `GrangerCausality.grangerCausalityTest` no longer fails with a singular design matrix or prints to standard output;
  its p value matches the F test of two least-squares models with intercept.
- `TimeSeriesObject.calcEntropyForWindow` throws `IllegalArgumentException` for a window length below 4 instead of
  looping forever.
- `TimeSeriesObject.writeToFile` writes three `#` header lines and then every pair with the given separator, instead
  of the `toString` text with only the first 5 points, also for `TimeSeriesObjectFFT`. `toString` works for fewer than
  5 points, and a failed write throws `UncheckedIOException` naming the file.
- `TimeSeriesObject.getAvarage` and `TimeSeriesObjectFFT.getAvarage` compute the mean instead of returning 0.0 until
  `calcAverage` was called.
- `MessreihenLoader.loadMessreihe_2` reads comma-separated files with decimal points and stops with the file and line
  on a bad value, a short line or a missing file instead of returning an empty or partial series.
- `TimeSeriesObject.add`, `add(series, length)` and `divide_by` keep the x values instead of using the index. `add`
  throws for series of different lengths and `add(series, length)` throws for a series with fewer than `length`
  values, instead of padding with 0.0. `divide_by` throws for series of different lengths instead of returning an
  empty or shortened series, and gives NaN for a zero divisor instead of 0.
- `RNGWrapper.getStdPoisson(lambda)` uses `lambda` instead of 1.0.
- `DFA`, `MFDFA`, `RIS` and `EventSynchronization` in the Python package raise `ValueError` naming the argument for a
  value that is not finite, a constant series, a series too short for the window sizes, a window size that the trend
  polynomial fits exactly up to rounding, or too few events, instead of returning NaN or 0 with an interpretation.
- `MFDFA` leaves windows that the trend polynomial fits exactly up to rounding out of F_q(s), as it did for exact
  zeros, so their rounding residue no longer sets h(q) for negative q.
- `RIS.stretched_exponential_fit` is a maximum-likelihood fit of the stretched exponential and raises `ValueError`
  when it cannot fit, instead of returning gamma 1.0 for every input.
- `RIS.risk_parameter` raises `ValueError` for intervals whose mean is not positive instead of returning 0.
- `EventSynchronization` with `method="absolute"` raises `ValueError` without a threshold instead of using 0, and the
  message for an unknown `method` lists the allowed values.
- `Summaries.of` in Java and `summarize` in Python count infinite values in `nanCount` like NaN, as `StatsAccumulator`
  does, so an infinite value no longer turns `mean` into NaN or infinity, `m2` into NaN and `min` or `max` into an
  infinity.
- Workbench: Deploy, Start and "Publish and start" refuse to deploy while the list of deployments cannot be read from
  the retrieval server, with the message `the deployments could not be read from <url> (<reason>); try again`. Deploy
  stays disabled while the list is read or cannot be read, and the panel shows the reason. Before, an unreadable list
  counted as empty and a second deployment could write a topic that another one already writes.
- Workbench `acf`: values that are not finite are skipped pairwise, so a series with one gap gives its autocorrelation
  instead of 0 for every lag.
- Workbench pair operators: a call without `minCoverage` or `maxAge` uses the defaults the operator panel shows
  (`minCoverage` 0 for `pair.coverage` and 0.8 for `pair.xcorr` and `pair.es`, `maxAge` 60 s). Before, it used
  `minCoverage` 0.8 for `pair.coverage` and `maxAge` 0 for all three.
- Workbench `model.wind-shear`, `model.power-curve` and `feature.lag` refuse the missing and out-of-range parameters
  the processor refuses, with a message such as `alpha: "0.7" is out of range; expected a number from 0 to 0.6`, so a
  flow with these operators that computes in the browser is not refused at deploy for their parameters.
- The Simulate form on the Signals page bound a generator parameter that was cleared and typed again as text, which
  the generator now refuses. It binds numbers, leaves an emptied field out so the generator default applies, and
  refuses text that is no number before binding, for example `sigma: "4,5" is not a number; expected a number such as
  0.1`.
- SaaS backend docs: `README.md`, `.env.example` and `DOCKER-SETUP.md` document the `POSTGRES_*` variables and the
  required secrets that `app/core/config.py` reads, not `DATABASE_URL`, which the backend never read, and say that
  `FIRST_SUPERUSER_PASSWORD` is used only when the first administrator account is created.
- SaaS docs: `DOCKER-SETUP.md` checks the backend at `/health`, the backend README lists the authentication routes
  that exist (there is no `/api/v1/auth/refresh`), and `LOCAL-DEVELOPMENT.md` says that `/api/v1/flows` needs a bearer
  token, that `./local-test.sh` ends with exit code 0 even after a failed check, and that the `admin@localhost`
  account written by `local-setup.sh` cannot sign in. The backend, frontend, local development and user manual pages
  name the three required secrets and no longer show Redis, `admin123` or `dev_password_123`.
- TopicsUP, TopicsDOWN and TopicsCHECK wait for each create, delete and list request and stop with the topic and the
  cause when one fails. An existing topic is kept and a missing topic is skipped when deleting.
- An OPENTSX_PRIMARY_CLUSTER_CLIENT_CFG_FILE_NAME that names a file which cannot be read and has no readable
  <file>.example template, or an OPENTSX_TOPIC_MAP_FILE_NAME that names a file which cannot be read, stops the 2.x
  tools instead of falling back to the default files. The 2.x tools stop with the path when the client profile cannot
  be loaded, instead of using empty properties or exiting the JVM from TSOProducer and TSOEventAnalysisProducer.
- TSOProducer.createProducer, TSOEventAnalysisProducer.createProducer and TopicsManagerTool read the configured client
  profile and config/topiclist.def instead of fixed absolute paths that exist on no other machine.
- opentsx-lg/bin/run_KSAPP_01.sh, run_KTAPP_01.sh and the ping tool executor-script.sh start the existing classes
  org.opentsx.lg.analyser.FWL_TSA_KStreamsApplication, FWL_TSA_KTableApplication and
  org.opentsx.connectors.kafka.topicmanager.TopicsUP, TopicsDOWN and TopicsCHECK.
- bin/episode_02, 03, 09 and 10 take their classpath from bin/opentsx classpath opentsx-core instead of a 2.3-SNAPSHOT
  jar and a lib directory the build does not make.

### Security
- SaaS backend: `SECRET_KEY`, `POSTGRES_PASSWORD` and `FIRST_SUPERUSER_PASSWORD` have no default. The backend does not
  start without them, with a key shorter than 32 characters, a password shorter than 12 characters, a
  `FIRST_SUPERUSER_PASSWORD` longer than 72 bytes, or one of the example values published in this repository; the
  message names the variable and does not show the value. `TEST_LOGIN` stays the only lab switch. Upgrade: set the
  three variables. An existing PostgreSQL volume keeps the password it was created with. An existing administrator
  account keeps its stored password, which is `admin123` if the account was created with the old default; change it
  with `POST /api/v1/auth/change-password`.
- `opentsx-saas-backend/docker-compose.yml` and `docker-compose.local.yml` take the three secrets from the environment
  or `.env` with `${VAR:?}` and no fallback, so every docker compose command stops and names a missing one (for
  `docker-compose.local.yml` also without the `full` profile). PostgreSQL and the backend are published on 127.0.0.1
  only, and the PostgreSQL healthcheck uses the configured `POSTGRES_USER` without a password.
- SaaS backend: register and change-password refuse a password longer than 72 bytes in UTF-8 with 422 and the message
  `at most 72 bytes` for the field, instead of cutting it to the 72 bytes bcrypt reads. A sign-in with a longer
  password fails; an account registered with a longer password signs in with the first 72 bytes that were stored.
- `opentsx-saas-backend/local-setup.sh` generates the database and first administrator passwords, writes them only to
  `.env.local` with mode 600 and prints neither. A rerun keeps them, replaces a password the backend refuses (shorter
  than 12 characters, or `dev_password_123`), sets the database user's password when the database exists, and stops
  when a SQL statement fails.
- `deploy/k3s/20-configure.sh` refuses a `SAAS_ADMIN_PASSWORD` or `SAAS_DB_PASSWORD` shorter than 12 characters and a
  `SAAS_ADMIN_PASSWORD` longer than 72 bytes, the limits the SaaS backend enforces at start, so such a value stops the
  configuration instead of the backend pod. It counts characters as the backend does in any locale, so a
  `SAAS_ADMIN_PASSWORD` or `SAAS_SECRET_KEY` of multi-byte characters below the limit is refused as well. Generated
  values are unaffected.
- The workbench login page shows no administrator address and password in development mode, and the page the SaaS
  backend serves at `/` no longer prefills `admin@localhost` and `admin123`. The backend has no default administrator
  password.
- The repository `.gitignore` ignores `/.env`, from which docker compose reads `POSTGRES_PASSWORD`, `SECRET_KEY` and
  `FIRST_SUPERUSER_PASSWORD` for `docker-compose.local.yml`.
- The 2.x Kafka tools in opentsx-connectors no longer print the client profile. getPropsAsString_NO_SECURITY_PROPS
  also hides every key that contains sasl., ssl., password, secret, token or user.info, and the unused
  OpenTSxClusterLink.getPropsAsString, which returned the credentials, is removed.
- The tracked client profiles and .env files of opentsx-lg and archive/demos/opentsx-clusters hold localhost or
  placeholders instead of private addresses, a personal host name and Confluent Cloud hosts. Unused profiles and the
  IDE files under .idea/ are removed, and .idea/ is git-ignored.

### Added
- Kafka-native streaming acceptance against KafScale: REST deployment, continued vector output, inventory and status,
  stop, rejection before control publication, and a series checkpoint restart. The existing KafScale CI job runs
  retrieval acceptance and retains its reports. See [the operating guide](docs/devops/kafka-native-streaming.md).

- Iceberg-backed Retrieval opens existing episode and series tables without creating missing namespaces or
  tables. Its store rejects writes, including empty batches, and does not expose a mutable episodes table.
  The existing writer path retains provisioning behavior. The new properties-based reader factory closes its
  owned catalog if table loading fails; the catalog-taking overload leaves ownership with the caller.

- The Python topic reader closes consumers on failures, validates time filters before creating a consumer,
  disables automatic offset storage, and shares one finite timeout across metadata, watermarks and polling.
  Assigned reads tolerate at most 32 nonfatal unsupported FindCoordinator notices within that deadline;
  other broker errors propagate. Records outside the initial per-partition offset ranges are not decoded,
  including records arriving on a completed partition while another partition is still being read.

- The PFD runtime accepts `--out-of-orderness` in milliseconds with a ten-second default and permits zero delay.
  It validates both runtime options in either order and rejects invalid values and duplicates before graph creation.

- The PFD runtime accepts `--window-size` in milliseconds, matching `TimeSeriesAnalysisJob`, with a five-minute
  default. Invalid window values and unexpected trailing arguments fail before graph construction.

- CI verifies recorder-exported PFDs through Kafka and Flink, including completed event-time window values.
  The opt-in `pfd-acceptance` profile retains the descriptor, Flink log and verified result as artifacts.

- The exercise runner uses the Maven classpath, propagates failed assertions and rejects invalid modes.
  Seeded exercise checks recalculate means and test the actual scaling and daily-window behavior.
- Unavailable desktop analysis and modern Java/Scala export buttons are disabled with explanatory tooltips.
  Both trackers label their JSON display action `Show PFD`.
- Local SequenceFile loaders stop at EOF, handle empty buckets and close readers. The file loader reads
  at most the configured record limit. Failed processing stops loading and retains the original error cause.
- Classic bucket workflow entry points `loadViaContext` and `processBucket` throw `UnsupportedOperationException`
  instead of reporting success with empty results. `MacroRunner` propagates the unsupported-load error.
- Classic FFT conversion preserves the input coordinates and the frequency coordinates of FFT results.
  `TimeSeriesObjectFFT` headers, diagnostics and error messages use English; averaged series use the `Mean_` prefix.
- Desktop FFT derives sampling rates from uniformly spaced coordinates and requests an explicit rate for integer
  index axes. Invalid data and rates produce an error; cancelling the rate dialog leaves the flow unchanged.
- A Maven download that failed with HTTP 502 failed the build, because Maven retries only 429 and 503 by default.
  `.mvn/maven.config` now retries a download up to five times, 3 seconds apart, on 429, 500, 502, 503 and 504.
- The bucket analysis CLI honors its input arguments, accepts an optional filename filter and record limit,
  and rejects invalid input. `BucketLoader.setLimit` applies to all three load and process entry points.
- PFD file export, schema and runtime accept Kafka input, centering and log output. Unsupported operations,
  normalization methods and sinks fail explicitly. Topics and brokers require configuration; event-time
  watermarks advance from observation timestamps. The missing Flink connector-base dependency is included.
- The modern desktop tracker starts with an empty transformation tree instead of Swing sample items.
- The Flink null-field aggregation test runs with nullable getter fixtures and preserves its result assertions.
- Z-score normalization rejects malformed, non-finite and zero-variance inputs before producing invalid values.
- PFD log output writes JSON coordinates and values for every window, including fewer than five points.
- Hourly solar observations (`FG_LBERG`, `FD_LBERG`) sat up to almost an hour early (56 minutes at Arkona in
  October). `MESS_DATUM` of that product is the UTC moment at which the hour in true solar time ends (`hh:mm`), and
  the parser cut off the minutes. It now rounds to the nearest full hour.

### Added
- Tests for the weather collection: hourly wind, gusts, pressure, precipitation and solar on excerpts of real DWD
  files; every parameter of `market weather-backfill` against its CDC directory, column, unit and time
  convention; the default stations and the years of the daily jobs.
- CI renders and validates `deploy/k3s/history/` (kustomization and the one-off Jobs) with kubeconform.

## [3.0.1] - 2026-10-16

### Fixed
- `bin/opentsx market load --series <path>` refused an archived series that is not in the catalog, such as
  `dwd-cdc/<station>/<parameter>`. It now takes a path from the archive when it is archived there.
- The `opentsx-tools` image staged with `--with-s3` lacked `dev.failsafe:failsafe`, a runtime dependency of
  `iceberg-aws`, so reading an `s3://` table failed with `NoClassDefFoundError: dev/failsafe/FailsafeException`.
  `stage.sh` adds it, and the image workflow checks for it.

## [3.0.0] - 2026-10-16

### Added
- `bin/opentsx market publish --from-day D --to-day D` publishes only the episodes that begin in that range of
  UTC days (`--to-day` exclusive). A daily job publishes the last complete days instead of the whole year again.
- `bin/opentsx market load` takes the same `--from-day` and `--to-day`.
- Workflow `.github/workflows/image.yml` builds `opentsx-tools` for `linux/amd64` and `linux/arm64` with SBOM and
  provenance. On a tag `v<version>` it pushes `ghcr.io/kamir/opentsx-tools:<version>`; the tag must match the
  project version and be a stable version. On a pull request that changes `deploy/docker/` it only builds.

### Fixed
- `bin/opentsx market load` wrote every episode again on each run, because each run gave it a new random id. It now
  uses the identity of `publish` (series, start, value count), so the Iceberg store skips what it already holds.
- Web workbench in `opentsx-saas-frontend` (route `/workbench`): the Swing bucket analyser in the browser. Load a
  bucket (sample, CSV/TSV, episode JSON, retrieval server), apply the time series operations, inspect every result
  on an oscilloscope view (time base, vertical scale, cursors, trigger, measurements), build the processing flow as
  a graph and export it as `opentsx.dag.v1` Smart Topic definition, Processing Flow Descriptor or Java code. See
  [`EVOLUTION/TASK-013-web-workbench.md`](EVOLUTION/TASK-013-web-workbench.md) for the editor context. The Java processor executes the supported vector and series subset.
- TS inventory: the retrieval server reads the episodes topic of a domain from KafScale (`--kafscale <host:port>
  --domain <d>`) and lists every series with extent, value range, episode labels and the ground truth of the
  scenario as time ranges (`GET /v1/inventory`, `opentsx.ts-inventory.v1`). The workbench has a "TS Inventory"
  dialog: filter by tag, channel, scenario event or text, choose window and resolution, load the selection as the
  bucket. `deploy/k3s`: `RETRIEVAL_SOURCE=kafscale`.
- Vector measuring points (draft): operator `align.vector` brings the results of several nodes onto one clock and
  gives one record per tick and key with one value per component (`opentsx.vector.v1`, JSON schema in
  `docs/datamodel-2026/schemas/`, rules in `docs/datamodel-2026/11`, section 5a). The workbench has the operator as
  "Align to vector": nodes can have several inputs, the scope shows the components on one time base, and the DAG
  export writes the node with all inputs and a vector sink. The Java processor executes source-vector alignment.
- Vector topics are written and read back. The vector processor (`opentsx vectors`, `deploy/k3s`:
  `VECTOR_PROCESSOR=true`) keeps them up to date: it follows the episodes topic, writes a measuring point to
  `tsx.<domain>.smart.<name>.vectors.v1` when every series has passed its tick, checkpoints per sink and reports
  its status. DAGs are deployed and stopped through the control topic `tsx.<domain>.dags.v1`. The retrieval server
  reads the vector topics, so their components show in the TS inventory as series, and it lists the deployments
  (`GET /v1/dags`). The Java operator and the workbench pass the same test vectors
  (`docs/datamodel-2026/testvectors/`).
- Deploying needs an account: the workbench has "Deploy" and "Stop" in the tab "Smart Topic" with the state the
  processor reports, a list of what a deployment needs (ready or missing, step by step) and a button that turns
  the loaded series into a published vector. The SaaS backend checks the account (`POST /api/v1/dags/deploy`, `/stop`; administrators only
  unless `DAG_RUN_ROLE=user`) and forwards with the run token to the retrieval server (`POST /v1/dags/deploy`,
  `/stop`, only with `--kafscale`, `--dag-runs true` and the token `OPENTSX_DAG_RUN_TOKEN`). The frontend image
  does not proxy those write routes. `deploy/k3s`: `RETRIEVAL_DAG_RUNS`, `RETRIEVAL_DAG_RUN_TOKEN` (generated),
  `SAAS_DAG_RUN_ROLE`.
- Workbench: the ground truth of the scenario shows on the scope. Series loaded from the TS inventory bring their
  events (operations, sensor faults) along as bands on the time axis; operators that keep the time axis hand them
  on, and "Segment at threshold" says how many crossings lie inside an event.
- SaaS module, test operation: `TEST_LOGIN=true` (`deploy/k3s`: `SAAS_TEST_LOGIN=true`) lets `admin` with the
  password `admin` sign in as the first administrator; the login page shows it. Off by default.
- Workbench: the "OpenTSx Flow Composer" lists the flows stored in the account in a selection window like the TS
  inventory: filter by text, domain, status or kind, see what a flow reads, computes and publishes and what the
  processor does with it. From there a flow is opened in the editor (several: the first on the workbench, the
  others in tabs of their own), created, renamed, copied, deleted, activated and paused. Activating a flow the
  vector processor can run deploys it, pausing stops it; any other flow is only marked. An opened flow loads the
  series it was built on from the TS inventory, over their whole range.
- Workbench: a deployment belongs to its flow. It is named `<domain>-flow-<id>` (was `<domain>-<track>-smart`),
  so flows of one track no longer replace each other. "Deploy" saves the flow first and marks it active, "Stop"
  marks it paused. A deployment is refused while another running one writes the same topic. Deployments under
  the old name keep running and belong to no flow.
- Workbench: the left side has two tabs. "Input bucket" is the tree of the loaded bucket and the buckets made
  of it; "Smart Topic" (moved there from the inspector) holds publishing, the DAG download and the deployments.
- SaaS frontend: the page after sign-in (`/dashboard`) is the "OpenTSx Flow Composer". It replaces the cards of
  the dashboard by the composer as a whole page: select flows, open them in the editor, create, rename, copy and
  delete them. "Start" and "Stop" (in each row, in the details and for a selection; they replace "Activate" and
  "Pause") run a flow as a Smart Topic on the vector processor. A flow the processor cannot run shows what it
  needs and is no longer marked active.
- Concept and draft for the engineering workflow from system to model
  ([`docs/datamodel-2026/14-ENGINEERING-FLOW.md`](docs/datamodel-2026/14-ENGINEERING-FLOW.md), German): the
  signal as the key between Studio, workbench and Flow Composer, and two draft schemas, `opentsx.system.v1` (a
  whole system: which signal comes from where) and `opentsx.processor.v1` (generators, transformers, models),
  with examples for the system `h2-chain`. Signed off on 2026-10-13 with three grades of a signal (raw, clean,
  features) and an optional guided mode; the implementation follows in delivery stages.
- Signals of a system (TASK-014, stage L1). System documents (`opentsx.system.v1`) live in `systems/`; the
  retrieval server reads them (`--systems`), checks every reference against the twin plan, the channel catalog and
  the inventory, and hands out the state of each signal computed from the TS inventory (`GET /v1/systems`,
  `/v1/systems/<id>/signals`). The web application has the page "Signals" (`/systems`): expectation, origin, state
  and next step per signal. "Prepare" opens the raw series of a signal in the workbench, with a context line and
  the expected range on the scope. `deploy/k3s`: the retrieval server on KafScale serves the documents of the image.
- Shell around a system (TASK-014, stage L2): `/systems/<id>/<place>` with a rail for the four places System,
  Signals, Flows and Models and the workbench as the tool. "System" shows the atoms of the plan with what feeds each
  input (`GET /v1/systems/<id>/plan`); "Flows" is the Flow Composer for the flows of the system; "Models" previews
  which signals are ready for a model. The optional guide shows the seven steps of the engineering method with the
  state of the system in each. A flow built from a signal remembers its system and signal.
- Clean signals (TASK-014, stage L3). The vector processor also runs series sinks (`opentsx.dag.v2`): a chain of
  plausible range, unit conversion and resampling from a raw series to the clean grade of a signal, written as v2
  episodes to `tsx.<domain>.clean.v2` with the tags `system` and `signal`. The workbench has "Publish as clean
  signal" (check, save and start in one step) when it was opened for a signal; the page "Signals" then shows the
  signal as clean. Shared test vectors for the cleaning operators in Java and TypeScript
  (`docs/datamodel-2026/testvectors/clean-ops.v1.json`).
- Plan revised after an external review ([`docs/datamodel-2026/15-ENTSCHEIDUNGSKREIS.md`](docs/datamodel-2026/15-ENTSCHEIDUNGSKREIS.md),
  German, decided on 2026-10-13: stages L4 and L5 are built first, then E1 to E3): the decision as the central unit, with draft schemas `opentsx.decision.v1` (decision
  card) and `opentsx.decision-record.v1` (recommendation, approval, action, outcome) and an example; a streaming
  contract with what holds today and what is the aim (11, section 7a); release of a model for a purpose (05, section
  5a). Signals carry a role, a meaning and an operating range next to the plausible range. Clean episodes carry an
  identity, so a restart is not counted twice. The user manual's chapter 16 is rewritten as eight steps from a
  decision to its effect.
- Binding and simulating signals (TASK-014, stage L4). On the page "Signals" a signal is bound to a series of the TS
  inventory after its meaning was confirmed, or to a demo generator preset from its expectation (`gen.ou`,
  `gen.sine`), which an administrator runs over a period. Generated series go to `tsx.<domain>.synthetic.v2` and
  carry `origin: SYNTHETIC` and `fidelity: demo`. What is set there is a working copy per account
  (`/api/v1/systems/<id>/bindings`) laid over the system document; the page gives the lines for the file.
- Models (TASK-014, stage L5). The series chain of the vector processor has a first model and features:
  `model.power-curve` (wind speed to power by a curve of points), `feature.lag` and `feature.rolling`. A sink may
  read the clean series of one signal and write another signal, with `origin` and `madeOf` in its labels, or
  features to `tsx.<domain>.features.v2`; the workbench has "Publish as another signal" for that. The retrieval
  server reads the decision cards a system document lists and says per analysis whether it is usable, restricted
  or blocked today, with the reasons (`GET /v1/systems/<id>/decisions`): grade, age against the newest data,
  accepted origin, the range the analysis was checked in, and its evidence. The place "Models" shows the cards; the
  place "System" shows the plan as a graph above the table. Comparing alternatives over a period is not built.
- System plan of the H2 chain in a second form (TASK-014, stage S1, after a second briefing; decisions D64 to D66 in
  [`docs/datamodel-2026/15-ENTSCHEIDUNGSKREIS.md`](docs/datamodel-2026/15-ENTSCHEIDUNGSKREIS.md), section 13). The
  twin has six new atoms: `wind-park` (potential, available and generated power, with the energy not generated
  counted per cause), `h2-compressor`, `h2-buffer` with the regulator that feeds the pipeline, `h2-pipeline` with a
  pressure at each end, `h2-handover` (regulating valve, lowest delivery pressure, shortfall) and `h2-consumer`
  (the demand is information, the delivery is gas). The order compressor, buffer, pipeline is an assumption and
  says so in the plan; every simplification is named in its atom. The system document has a signal for every input
  from outside. The scenario now tells: the handover station closes, the buffer fills, the electrolyzer trips at
  200 bar. The first form of the plan is kept for the tests of the twin, and its atoms stay in the catalog.
- Demo of the H2 chain made showable (TASK-014, after the first walk-through in
  [`docs/datamodel-2026/16-DEMO-UND-SIMULATIONSEXPERTEN.md`](docs/datamodel-2026/16-DEMO-UND-SIMULATIONSEXPERTEN.md),
  section 7). The place "System" draws the plan in the phases of a time step, with power, hydrogen and information in
  three colours and late wires dashed, shows what the system document assumes (`assumptions`), follows the working
  copy (`POST /v1/systems/<id>/plan`) and links to the Smart Topics Studio, which the frontend image now serves
  under `/studio/`. The scenario has a rule window (04:00 to 05:00) and a dispatch (05:00 to 05:30), so potential,
  available and generated power differ and the energy not generated shows per cause.
- Runs of the twin on request (TASK-014, run service and run register). The retrieval server computes a run of the
  scenario of a system for the decisions a person asks for (`POST /v1/systems/<id>/runs`: controls set at a time, a
  policy, optionally a forecast that continues the reference), keeps the last hundred runs in memory and hands out
  a run, its series and a comparison of two runs by their identity. Every run names the origin of its inputs and
  its status, which is `restricted` as long as the inputs are those of the scenario. The web application has the
  place "Simulate": a question on the left, the run next to the reference on the right. Also: a signal bound in the
  working copy opens in the workbench, and a flow may read a generated series as the raw grade of its signal.
- `opentsx-market`, weather backfill: six more DWD quantities per station (relative humidity, wind direction,
  10-minute wind speed, the highest gust of the hour, pressure, precipitation) next to temperature, hourly wind
  speed and global radiation. `opentsx-dwd` knows the variable `weather.wind_gust`.
- The year 2025 in the system (TASK-014, stage J1). `bin/opentsx market publish` writes archived series as raw
  episodes into the episodes topic of a domain, in batches with a pause, and stops at the first refusal of the
  broker. The system document of the H2 chain (version 4) binds the day-ahead price to the published series
  (`REPORTED`) and has three signals for the weather of the site from a DWD station (10-minute wind, temperature,
  pressure); a series below an atom may state its own unit and kind without a channel of a catalog. The demand of
  the customer is declared as a demo generator. The night of the scenario is 2025-10-01. A decision measures the
  age of an input against the newest data among the signals it needs, not against every series of the system.
- Draft: notebooks as a further application
  ([`docs/datamodel-2026/17-NOTEBOOK-ARBEITSPLATZ.md`](docs/datamodel-2026/17-NOTEBOOK-ARBEITSPLATZ.md), German):
  load the series directly, look at them, test a new series against what the system expects. Not built.
- Notebooks as an OpenTSx application, stage N1 (TASK-014). The Python package reads the inventory, systems,
  signals, decisions and runs of the twin from the retrieval server, and `TopicReader` reads the episodes of a
  topic directly from the broker, once, without a consumer group and with a limit it does not pass silently.
  Notebook 1 shows the time series inventory, a series with its coverage and gaps, and the price of 2025. The image
  `opentsx/notebook-content` carries package and notebooks into the notebook of a stack (`deploy/k3s/notebook/`:
  a patch for the notebook's Deployment and a network rule for the retrieval server).
- The operator `model.wind-shear` (TASK-014, J1): the wind of a measuring height carried to the hub height by the
  power law, in the processor and the workbench, checked by the shared test vectors. The potential of the park in
  the system document of the H2 chain is now made of the measured wind of the DWD station: cleaned, carried from
  10 m to 120 m, then the power curve of four turbines.
- A derived series carries the flow that writes it (tag `flow`, decision D84). Two flows that write the same signal
  one after the other write two series; the state of the signal counts the series of the flow that runs and names
  the others. Before, a stopped flow and its successor wrote into one series and mixed two periods.
- The year run on history (`bin/opentsx twin --history ... --year 2025`) computes the H2 chain in its second form
  (compressor, buffer, pipeline, handover station) at a 15-minute step, with the potential of the park as its wind
  input and the compressor in the schedule. It prints the pressures of buffer and pipeline, the energy of the
  compressor and the hydrogen not delivered.
- A generated series carries the run of its generator (tag `run`, decision D85): the same generator over the same
  period replaces itself, another period gives another series. The state of a signal counts the run generated
  last (label `generatedAt`) and names the others.
- Runs on the signals of a system (TASK-014, J1). A run of the twin may take its inputs from the series of the
  signals instead of the invented inputs of the scenario (`inputs: "signals"`): each input says where its values
  came from, its origin and how much of the run it covers, and the run is compared with the decisions of the
  scenario on the same inputs. The place "Simulate" lets a person choose.
- Notebooks 2 and 4 for the notebook of a stack (TASK-014, N2): "Ein Signal" puts the raw and clean series of a
  signal next to its expectation and operating range, and shows what the decision says about its inputs; "Den
  Zwilling fragen" asks the twin a question on the signals of the system and then the same question over many
  times of day. The Python client asks for runs on the signals and gives the operating range of a signal.
- Notebook 3 "Verproben" (TASK-014, N2): tests a CSV file of an operator against a signal (unit, step, time zone,
  meaning of the time stamp, duplicates, plausible and operating range, gaps), puts it next to the series of the
  signal, gives a verdict, writes a prepared copy in UTC and the unit of the signal, and names the commands and
  binding lines. An example file derived from DWD data ships with it.
- User manual, chapter 16: the engineering method from the system to the model in seven steps (define the system,
  list the expected signals, take stock, bind or simulate, prepare, run, model), with the rules that hold across
  them, a worked example, a checklist, and for each step what OpenTSx supports today and what is planned.
- CI: jobs "SaaS frontend" (types, lint, tests, build) and "SaaS backend" (pytest).
- SaaS backend: flows are stored per user in the database (`app/api/flows.py`: create, read, update, delete,
  executions), replacing the placeholder routes. The workbench saves a flow to the account and reopens it from the
  dashboard. `opentsx-saas-backend/smoke-flows.sh` checks accounts and flows against a running instance.
- `deploy/k3s`: optional component `saas` (`SAAS=true`): frontend on node port 30311, backend, PostgreSQL; images
  `saas-frontend` and `saas-backend` (`deploy/docker/`).
- `LICENSE` (Apache License 2.0, full text) and `NOTICE`.
- `CONTRIBUTING.md`, `CODE_OF_CONDUCT.md` (Contributor Covenant 2.1), `SECURITY.md` (reporting via GitHub private
  vulnerability reporting), this changelog.
- `config/opentsx-local.props.example`: localhost / KafScale-compatible Kafka client profile for the 2.x tools.
  Without a local `config/opentsx-local.props` (git-ignored), `OpenTSxClusterLink` falls back to this template.

### Fixed
- Example system `h2-chain`: the available power of the wind farm was bound to the sum of the measured turbine power.
  Under curtailment that is the actual generation, not what the wind offers. They are two signals now: an
  observation (`park.p_gen_kw`) and an estimate from the wind speed (`park.p_avail_kw`).
- Readers on KafScale could skip records when the broker refused a fetch: the Kafka client throws the refusal of one
  partition out of the middle of a poll and drops what it had already taken from the others. `Patience` now puts
  the consumer back to where it stood before the refused poll. Seen as a clean series that lacked its first episode.
- Vector processor: the first write to a new sink topic stopped the whole processor. The KafScale proxy answers a
  produce request that mixes a topic nobody has written to yet with another one in a way the Kafka client cannot
  parse, and the producer then sent nothing any more, not even status records. Every runner now has its own
  producer and the status records have another; waiting for the broker's confirmation no longer blocks the loop,
  and a producer that gets no answer is closed and replaced.
- Readers no longer end when KafScale refuses fetches for a minute (`Unexpected error code 7 while fetching`): they
  wait it out (`org.opentsx.kafka.Patience`, used by `LogReader`, the statistics processor and `SeriesStatsLog`).
  Producers retry with pauses for up to three minutes instead of five quick attempts
  (`KafScaleClients.producerProps`). The statistics processor used to restart on every such window.
- Retrieval server: a vector topic the broker does not hand out no longer keeps the feed from reading the other
  topics.
- SaaS frontend: the login page showed the default administrator password of a local backend on every
  installation, also where the deployment generates another one. The hint now appears in the dev server only.
  A wrong password reloaded the page instead of showing the error; the form now reports it.
- SaaS backend: registration failed against a real database (`MissingGreenlet`), every authenticated request
  answered 401 (numeric token subject), and a refresh token was accepted as access token.

### Changed
- Default 2.x client profile is now `./config/opentsx-local.props` (was `./config/cpl.props`); scripts, the
  `opentsx-lg` README and `opentsx-default.properties` updated.
- `opentsx.properties.example` lists only keys the code reads and documents how the v2 modules are configured
  (command-line options and environment variables).

### Removed
- Archived Maven modules (`archive/modules/`, `archive/opentsx-kstreams/`) removed from `master`: not built, but
  their outdated dependencies kept raising security alerts and Dependabot PRs. Last state: commit `315f01d`.
- Four dead submodule links in `archive/demos/` (no URL in `.gitmodules`).
- `config/cpl.props`, `config/cpl_iMac.props`, `config/cpl_local.props`, `config/mdc_c1.props` (client profiles with
  private LAN hosts).

## 2026-10-12

### Added
- Digital twin proof of concept `opentsx-twin` (P9): atom contract with model, views, dynamics and policy; wiring
  plan in YAML; layered bulk-synchronous runner; provenance ladder; shadow, what-if and forecast modes. Scenarios:
  energy system / H2 chain (`bin/opentsx twin`), family house with calibration against bills
  (`bin/opentsx house`, `--simulated`), sailing boat Etap 22 with calibration against logger data
  (`bin/opentsx boat`, `--simulated`).
- Smart Topics Studio prototype (`docs/prototypes/smart-topics-studio.html`) and the LabVIEW / command-loop concept.
- Studio guide (`StudioGuide`, `twin/haus-erzgebirge.guide.yaml`, `twin/h2-chain.guide.yaml`): prose for every atom,
  port, parameter, control and wire of the house twin and the H2 chain, where each value comes from and references to compare against; checked against the plan.
  The node graph explains wires and ports on hover, and each node takes own details (nameplate, serial number, own
  parameter values) with export as inventory (`opentsx.inventory.v1`) and as an own wiring plan.
- Smart Topics Studio redesigned as a product component after reviews from four viewpoints (UI design, UX and
  accessibility, electrical and energy engineering, commercial reader): app bar with scenario switch, scenario summary
  with headline figures and the question behind it, views Überblick, Vergleich, Systembild and Messreihen, inspector
  beside the diagram, connections coloured by energy carrier and routed around the blocks, events in plain language,
  larger units from a threshold on, limit lines in charts, a switch for technical details. Engineering corrections
  in both guides.
- Smart Topics Studio as a tool shell: title bar with menus (Datei, Ansicht, Szenario, Hilfe), icon rail on the left
  that expands and collapses, scenarios as document tabs, detail panel for blocks and connections sliding in from the
  right, status bar. Keyboard: Alt+1 to Alt+4 for the views, Ctrl+B for the rail, Esc closes the detail panel.
  The document tabs belong to the current scenario: the scenario itself, a link to its page in the Anlagen-Explorer,
  and an event topic view.
- Event topic of a twin run (`opentsx-twin-live`): `EventPublisher` writes the events of a run to
  `twin.<id>.<run>.events` (key: atom, value: JSON `twin.event.v1`), at once or played over a given duration;
  `EventBridge` serves such a topic to browsers as server-sent events; `bin/opentsx twin-live publish|bridge|demo`.
  The Studio follows the topic live through the bridge and keeps a replay that needs no broker. The publisher creates
  the topic before the first event, so a reader can connect while the run has not reported anything yet.
- k3s component `event-bridge` (`EVENT_BRIDGE=true`): the event bridge as Deployment and Service on NodePort 30312;
  `twin-live` is part of the tools image and takes the broker from `KAFSCALE_BOOTSTRAP`.
- Retrieval (`opentsx-retrieval`, P5): segmentation, pattern engine (MASS, SAX, DTW), retrieval API, REST server,
  Python client, power-curve analysis.
- DevOps guide for k3s against an existing KafScale cluster (`docs/devops/`, `deploy/k3s/`, `deploy/docker/`).
- Design documents: Smart Topics (05), interplay & coordination potential (06), digital twin (07), house (08),
  Studio & LabVIEW (09), boat (10), Smart Topic processor & DevOps (11).
- New English user manual (`docs/user-manual/`), documentation inventory, launcher `bin/opentsx`, link check in CI.

### Changed
- 2026 refresh merged into `master` (#102); SaaS frontend/backend dependencies bundled (Vite 7, python-multipart,
  python-socketio).
- README repositioned ("from time series to decisions").

### Removed
- Outdated documentation and modules outside the build moved to `archive/`; `opentsx-app-tools` archived.

## 2026-10-11

### Added
- Streaming statistics and data quality `opentsx-stats` (quantile sketch, DQ, KafScale processor with checkpoints)
  and `opentsx-openmetadata` (metadata pool, validated against the OpenMetadata 2.0.5 schemas); Python
  `opentsx.stats` (P4).
- Wind-farm domain `opentsx-windpark`: IEC 61400-25-2 data-point catalog, normalizer, simulator with ground truth,
  CLI to KafScale / Iceberg.
- Store SPI with contract suite, Iceberg store (index, log sync), OpenTSDB adapter, PyIceberg reader with byte
  equality between Java and Python (P3).
- `opentsx-kafka-v2` (topic layout, bucket writer, episode index, replay, catalog) and `LegacyBucketImport` (P2);
  end-to-end tests against KafScale v1.6.0 and Apache Kafka 3.9.1.

### Changed
- Avro 1.12.1 managed centrally (precision bug in 1.12.0 covered by a test).

## 2026-10-09

### Added
- Data model v2: `opentsx-model` (Java) and `opentsx.model` (Python), byte-identical via shared test vectors;
  `timestamp-micros` (D2) and xxHash64 series identity (D3); legacy bridge; schema history with compatibility check
  (P1).
- KafScale conformance suite (P0): headers and CreateTime are preserved; `offsetsForTimes`, the idempotent producer,
  standalone commits and consumer-group rejoin do not work on KafScale v1.6.0.
- Analysis of the existing model, target model, phase plan and gap analysis (`docs/datamodel-2026/`).

## Before 2026

| Years | Phase |
|-------|-------|
| 2013–2018 | Hadoop.TS: Hadoop, HBase/OpenTSDB, Spark; research on Wikipedia and financial time series |
| 2018–2020 | renamed to OpenTSx; Kafka, Kafka Streams |
| 2020–2025 | Confluent Platform/Cloud, ksqlDB, multi-region demos, SaaS prototype |
