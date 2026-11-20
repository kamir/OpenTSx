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
- `deploy/docker/test-launchers.sh` runs in CI and tests the launchers' failure paths and the logger settings under
  `deploy/`. On Java 21 it also checks the classpaths of `bin/opentsx` and of the staged image for no test jars,
  exactly one SLF4J provider, and zstd-jni wherever kafka-clients is. It also checks that the shaded
  `opentsx-flink-core` jar holds no `log4j.properties`.
- Configuration key `opentsx.log.level` (`error`, `warn`, `info`, `debug`, `trace`, default `info`) for every command
  of `bin/opentsx` except `config`, `version` and `stats-flink`, which logs through log4j2. When a source sets it,
  `OpenTsxConf.load` sets the slf4j-simple level `org.slf4j.simpleLogger.defaultLogLevel` in Java and the level of the
  logger `opentsx` in Python. Without a source the level of `conf/simplelogger.properties` stays.
- `OpenTsxConf.Loaded.startLine()` in Java and Python returns one line `starting <command>: <key>=<value> (<source>),
  ...` with every key the command reads that has a value, secrets as `******`, for the start log of a service.
- Registry keys for the mode rule of TASK-020 D10 and the DAG runs of the retrieval server, marked `unreleased` and
  not yet read by a command: `opentsx.retrieval.source` (`auto`, `demo`, `iceberg`, `kafscale`),
  `opentsx.windpark.sinks` (list of `auto`, `iceberg`, `kafscale`), `opentsx.openmetadata.profiles` and
  `opentsx.market.store` (`auto`, `always`, `never`), each with the default `auto`, which follows only values set on
  the command line; `opentsx.retrieval.openDagRuns` (default `false`) and `opentsx.retrieval.bind` (without it every
  interface, or 127.0.0.1 when `opentsx.retrieval.openDagRuns` is true). The registry holds 134 keys; appendix D and
  the defaults templates are regenerated.
- Shared configuration vectors `conf.v1.json`: 288 cases. A case with `"registry": "shipped"` runs on the shipped
  registry and checks the log level and the mode keys in both runtimes.
- CI checks the nginx site of the frontend image with `nginx -t` and checks that an unreachable backend comes back as
  `{"error": "the backend is not reachable", "type": "unavailable"}`.
- `bin/opentsx metadata --profiles auto|always|never` (`opentsx.openmetadata.profiles`) switches the profiles and
  data-quality results. The default `auto` publishes them when `--kafscale` is on the command line, so a KafScale
  bootstrap from the environment or a file does not switch them on. `always` exits with 2 when no bootstrap is set.
  The k3s Job `metadata-publish` passes `--profiles always`.
- `retrieval-server --bind <address>` chooses the address the server listens on; without it the server listens on
  every interface.
- `bin/opentsx studio-export <out.json>` writes the scenario data of the Smart Topics Studio, as the image already
  did. `deploy/docker/test-launchers.sh` checks that the launcher starts `org.opentsx.twin.studio.StudioExport`.
- `Episodes.of(SeriesKey, long[], double[])` builds a REGULAR episode when at least two timestamps have one positive
  step and an IRREGULAR_DELTA episode otherwise, and refuses an empty series (`values: empty; expected at least one
  value`).
- `Episodes.producer(module)` returns `<module>/<version>` with the Maven project version stamped into
  `org/opentsx/model/version.properties`; `Episodes.PRODUCER` is `producer("opentsx-model")` instead of a typed
  version.
- `org.opentsx.model.Varints`: one public helper for unsigned and zig-zag LEB128 varints that refuses truncated input
  (`<subject>: input ends inside a varint; expected complete varints`) and a varint above 64 bits (`<subject>: varint
  exceeds 64 bits; expected at most 64 bits per varint`). `TimeAxis` uses it.
- SaaS backend: ENABLE_SIGNUP=false makes POST /api/v1/auth/register answer 403 "sign-up is closed; ask an
  administrator of this installation for an account"; the default stays true, and docker-compose.yml passes
  ENABLE_SIGNUP and LOG_LEVEL to the container.
- SaaS backend: LOG_LEVEL and app/logging.conf give one text line per event on stdout, a start line with the settings
  (DAG_RUN_TOKEN only as set or unset), the database host while it waits, refused sign-ins at INFO without the
  password and each test login at WARNING; the 500 line comes from logger app.main.
- The image entrypoint gets `config`, `version`, `--version`, `twin-coupling` and `twin-schemas`. Its `twin`, `house`
  and `boat` switch to the history twins on `--history` or `--weather-year`, as `bin/opentsx` does, also when the
  option comes through `OPENTSX_EXTRA_ARGS`. `bin/opentsx` gets the command `history-twin twin|house|boat`.
- CI fails when `bin/opentsx` and the image entrypoint differ in a command other than `stats-flink` (bin/opentsx only)
  and `kafscale-check` (image only), or when a command of `Registry.LOADS_CONF` has no launcher case
  (`deploy/docker/test-launchers.sh --commands`). The Java 21 job runs the staged `kafscale-check` with a misspelled
  option, a closed port and a broker double, and reads the output with the patterns of `60-probe.sh`.
- Retrieval server: SIGTERM closes the port and the KafScale feed through a shutdown hook and logs stopping
  port=<port> and stopped. The hook is in place before the listening on line.
- Retrieval server with --kafscale: undecodable episodes, records lost to retention and dropped vector, control and
  status records are counted. The counts are in the feed loaded and feed refreshed log lines and in the source of GET
  /v1/inventory (undecodable, lostToRetention, droppedVectorRows, droppedControlRecords, droppedStatusRecords,
  refreshFailures). The first dropped record of a partition is logged at WARN with topic, partition and offset.
- Vector processor: a sink whose checkpoint lies before the log start reports the records it lost in lostToRetention
  of its status, and logs one WARN per partition with saved offset, log start and count. Records on the control topic
  that are not control records are counted in ignoredControlRecords, and the first one is logged. The first status
  record the broker refuses is logged at WARN, its recovery at INFO.
- `stats-flink` logs `submitting the statistics job domain=<domain> topics=<topics> sink=<topic> bootstrap=<address>
  once=<bool> checkpointEvery=<duration> restarts=20 restartDelay=PT15S` before the job runs.
- The market downloads read the shared proxy key `opentsx.https.proxy` (`OPENTSX_HTTPS_PROXY`, legacy `HTTPS_PROXY`
  and `https_proxy`), as the DWD downloads do; the JVM property `https.proxyHost` still wins, and a malformed value
  exits with 2 before the first download.
- `market load --store auto|memory|iceberg` (key `opentsx.market.load.store`, `OPENTSX_MARKET_LOAD_STORE`) lets a Job
  switch to Iceberg from its environment; `auto` keeps the rule that only `--iceberg` on the command line selects
  Iceberg.
- opentsx-model checks the shared refusal vectors `model-refusals.v1.json` in `ModelRefusalVectorsTest`, so Java and
  python-package test the same 21 refusal texts.
- `20-configure.sh` sets the ConfigMap keys `OPENTSX_STATS_WINDOWS`, `OPENTSX_STATS_ALLOWED_LATENESS`,
  `OPENTSX_WINDPARK_SCENARIO`, `OPENTSX_ICEBERG_NAMESPACE` and `DWD_FIXTURE` and checks their form.
- `RNGWrapper.init(seed)` and `RNGWrapper.DEFAULT_SEED` (1), and `DetrendingMethodFactory.getDFAmulti(order)`.
- `Patience` has a public constructor that takes the clock and the sleeper, so tests wait out refused reads on a test
  clock instead of sleeping.
- `TwinPlan.load(InputStream, long maxSteps)` loads a plan for a run that holds at most `maxSteps` steps and names
  that bound in the refusal of every step count outside it.
- Kafka-native streaming acceptance against KafScale: REST deployment, continued vector output, inventory and status,
  stop, rejection before control publication, and a series checkpoint restart. The existing KafScale CI job runs
  retrieval acceptance and retains its reports. See [the operating guide](docs/devops/kafka-native-streaming.md).
- The PFD runtime accepts `--out-of-orderness` in milliseconds with a ten-second default and permits zero delay.
  It validates both runtime options in either order and rejects invalid values and duplicates before graph creation.
- The PFD runtime accepts `--window-size` in milliseconds, matching `TimeSeriesAnalysisJob`, with a five-minute
  default. Invalid window values and unexpected trailing arguments fail before graph construction.
- CI verifies recorder-exported PFDs through Kafka and Flink, including completed event-time window values.
  The opt-in `pfd-acceptance` profile retains the descriptor, Flink log and verified result as artifacts.
- Tests for the weather collection: hourly wind, gusts, pressure, precipitation and solar on excerpts of real DWD
  files; every parameter of `market weather-backfill` against its CDC directory, column, unit and time
  convention; the default stations and the years of the daily jobs.
- CI renders and validates `deploy/k3s/history/` (kustomization and the one-off Jobs) with kubeconform.
- User manual, chapter 16, section 16.8: pair windows of `pair.coverage`, `pair.xcorr` and `pair.es` (pairing, window
  and step, clock, coverage, `maxAge` and `minCoverage` with their defaults, and the refusals), with the figures of
  the operator examples.
- The retrieval server answers `GET /v1/ready`: 200 with the age of the KafScale feed, its failed refreshes in a row
  and whether it is truncated, and 503 `unavailable` naming the reason after 3 failed refreshes in a row or 3 refresh
  periods without one that worked. `GET /v1/health` and `GET /v1/ready` are answered by two threads of their own, so
  they answer also while all four request threads wait for the broker.
- The event bridge of `opentsx-twin-live` answers `GET /v1/ready`: 200 while the broker answers a topic lookup within
  2 s, 503 `unavailable` with `broker: cannot be reached; check the address of the broker and its log` when it does
  not, so the readiness probe (timeout 3 s) sees the 503; the address and the cause go to the log once per outage.
- `opentsx vectors` writes the time its last round ended, in seconds since the epoch, to the file `progress` in its
  state directory (`opentsx.vectors.state`) at most every 5 s, so an exec probe can tell a loop that goes on from one
  that hangs, and removes the file of an earlier run when it starts, so its probes see only the current run. A
  progress file that cannot be written or removed ends the processor with exit code 1 and a message that names the
  file and `opentsx.vectors.state`, for example `<state>/progress: cannot write the progress file; check that the
  state directory (opentsx.vectors.state) exists, is writable and has space`.
- SaaS backend: a DAG deployment that the retrieval server refuses with 409 `conflict` answers 409 `conflict` with the
  reason of the retrieval server instead of 502.
- SaaS backend: `GET /ready` runs `SELECT 1` within 2 s and answers `{"status": "ready"}`, or 503 `{"error":
  "database: no answer to SELECT 1; check that the database runs and accepts connections", "type": "unavailable"}` and
  logs `not ready db=<host>:<port>/<db> cause=<cause>` at WARN. The k3s Deployment `opentsx-saas-backend` uses it as
  its readiness probe.
- Statistics processor: a continuous run with `--checkpoint` writes the progress file `<checkpoint>.progress`, the
  time of the last pass of its poll loop in seconds since the epoch, at most every 10 s, and removes the file of an
  earlier run when it starts. A file it cannot write stops it with exit code 1.
- k3s: `opentsx-stats` and `opentsx-vectors` get exec startup, readiness and liveness probes on their progress files
  (`/state/stats.ckpt.progress`, `/state/progress`). The probes fail on a file older than 2 minutes (readiness) or 10
  minutes (liveness), so a processor whose poll loop stops is not ready about 3 minutes and restarted about 12 minutes
  after its last pass; the first pass may take 10 minutes.
- `Patience.refusing()` in `opentsx-kafka-v2` tells whether the broker refused the last poll.
- A test of the vector processor checks that the writers `VectorProcessor.deployedWrites` names for the server and the
  command line leave out a DAG that `DagPlan.of` refuses, and the sinks of a DAG whose redeploy the processor refused
  because another DAG writes one of its topics.
- tools: `tools/check_tracked_config.py` fails on a gitlink that has no `.gitmodules` entry, with `<path>: gitlink
  without a .gitmodules entry; add it with git submodule add <url> <path> or remove it with git rm --cached <path>`.
  It reads the submodule entries from the `.gitmodules` file at the top level of the repository, also when it checks a
  directory below it.

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
- Flink `StatsJob`: an episode that decodes but fails `Episodes.validate`, for example a count that differs from the
  number of values, is counted in the new metric `invalid` and dropped. It no longer produces statistics or fails the
  task after every restart. Each subtask prints the first one per partition on stderr with topic, partition and
  offset. A failure of the engine still fails the task.
- `EpisodeIndexer.rebuild` returns `Result(indexed, skipped, invalid)`: an episode that fails `Episodes.validate` is
  counted in `invalid` and not indexed, and the first one per partition is printed on stderr with topic, partition and
  offset.
- The `BucketWriter` seal timeout message names the wait, `episodes not acknowledged within PT5M`, and a test covers
  the timeout.
- `StorageCostTest` asserts each published storage figure (raw 8.40, 8.36 and 10.32 B/value, zstd 2.18, 6.96 and 4.07
  B/value) to two decimals and pins the episode ids and creation times it measures, so the figures do not depend on
  the clock. An encoding change that moves a figure in the README, the user manual or the sizing plan fails the build.
- `StatsProcessor.RunResult` has the new count `invalid`, and `bin/opentsx stats --once true` prints it. The
  checkpoint position moves past an invalid episode, so a restart does not count it again.
- The statistics processor, the Flink job `StatsJob` and `EpisodeIndexer.rebuild` print the first record of each
  partition that does not decode on stderr with topic, partition, offset and the decoder message; later ones on that
  partition are only counted.
- The opentsx-tools image no longer sets `JAVA_TOOL_OPTIONS`, so the JVM no longer prints a `Picked up
  JAVA_TOOL_OPTIONS` line. The entrypoint passes `-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError` on the java
  command, followed by `JAVA_OPTS`. The k3s scripts no longer filter that line.
- `bin/opentsx` caches classpaths in `<module>/target/classpath-runtime.txt` instead of `target/classpath.txt`.
  `stats-flink` also uses `classpath-provided.txt`.
- `com.github.luben:zstd-jni` is in runtime scope in `opentsx-stats` and `opentsx-kafka-v2`, and
  `org.xerial:sqlite-jdbc` is in runtime scope in `opentsx-store-iceberg`, `opentsx-windpark` and `opentsx-market`.
  The runtime classpaths of `bin/opentsx` and `deploy/docker/stage.sh` therefore keep the zstd codec and the SQLite
  driver for `type=jdbc,uri=jdbc:sqlite:<file>`, and Maven users of `opentsx-store-iceberg` get the SQLite driver.
  `deploy/docker/test-launchers.sh` fails when a classpath with kafka-clients has no zstd-jni or a classpath with
  iceberg-core has no sqlite-jdbc.
- Every error answer of the retrieval server and the event bridge is `{"error": "<text>", "type": "<type>"}`. The type
  is one of `bad_request`, `not_found`, `method_not_allowed`, `too_large`, `unauthorized`, `forbidden`, `unavailable`
  and `internal` (appendix B).
- The retrieval server answers every known path, `/v1/systems/{id}/...` included, with 405 and an `Allow` header when
  the method is wrong. Before, `GET /v1/systems/{id}/generate` and `POST /v1/systems/{id}/scenario` answered 404. Any
  unknown path, also outside `/v1/`, answers a JSON 404 `no such endpoint: <path>` instead of the HTML page of the
  JDK.
- `toDebugString()` in Java and Python prints `key=value (source)` lines instead of `key=value`.
- User manual chapters 18 and 12.2, appendix D and the defaults templates say that `config list`, `config show`,
  `config get`, `version` and every command of `bin/opentsx` whose `--help` prints a `keys:` section read `--conf`,
  `--properties-file`, `$OPENTSX_CONF_DIR` and the `OPENTSX_` variables, and that every other command parses its own
  flags. Chapter 12 lists the six sources and the variable names, passes the OpenMetadata and DAG-run tokens only
  through `OPENMETADATA_JWT` and `OPENTSX_DAG_RUN_TOKEN`, and says that `OPENTSX_LOG_LEVEL` takes only the registry
  levels. Chapter 12.5 says that `retrieval-server` does not read `opentsx.retrieval.bind` and ignores an unknown
  option such as `--bind`. Chapter 18 gains the sections Choose a mode and Set the log level.
- `opentsx.properties.example` names the commands that read the key registry and says that 2.x `ProductionConfig` does
  not apply `opentsx.log.level` to logging. `bin/episode_10_production_config.sh` says that `OPENTSX_LOG_LEVEL` takes
  `error`, `warn`, `info`, `debug` or `trace`.
- `deploy/k3s/20-configure.sh` exits with 2 on a usage error (an unknown key, or an argument that is neither
  `KEY=VALUE` nor an option) and documents the exit codes 0, 1 and 2 in its header. `deploy/k3s/lib.sh` gets
  `usage_error`.
- The user manual, the devops guide, the k3s README and the READMEs of `opentsx-openmetadata` and `opentsx-retrieval`
  read the OpenMetadata bot token, the run token and the S3 keys with `read -rs <KEY> && export <KEY>` and no longer
  put a secret on a command line. The history archive comment creates `opentsx-market` with `--from-env-file`, and
  `40-demo.sh` names that way in its `--help` and when `OM_URL` is missing for `metadata-publish`.
- The Python `RetrievalError` carries `method`, `url`, `status` and the server's error `type`, and its message starts
  with the method and the URL. It is built as `RetrievalError(method, url, status, message, error_type)`.
- `RetrievalClient.similar()` and `RetrievalClient.segments()` take `series` after `tags`, as `episodes()` and
  `search()` do. `start` and `end` of both methods moved one place to the right; pass them by name. A call in the old
  positional order raises `ValueError` because `series` must be a list.
- `bin/opentsx stats` and `bin/opentsx stats-flink` load their keys with `OpenTsxConf.load`: flags, `--conf`,
  `--properties-file`, the `OPENTSX_` variables and `$OPENTSX_CONF_DIR`. An unknown option, an option without its
  value and a malformed value exit with 2 and one line `opentsx: <message>`, for example `--window: unknown option of
  stats; did you mean --windows?`. Before, the processor accepted them or printed a stack trace. `--help` prints the
  keys with their defaults on stdout and exits with 0.
- `--once` and `--observations` of `stats` and `stats-flink` take only `true` or `false`, and a bare flag means
  `true`. Before, `--once yes` ran the processor forever and a trailing `--once` was dropped.
- `bin/opentsx stats` and `bin/opentsx stats-flink` print a runtime failure as one line with its causes and exit with
  1, for example `opentsx: cannot read checkpoint stats.ckpt; caused by java.io.EOFException`. The stack trace appears
  only with `opentsx.log.level=debug`. At start both log one INFO line `starting <command>: ...` with every value and
  its source.
- `stats-flink --checkpoint` exits with 2 and `--checkpoint: an option of stats, not of stats-flink; the Flink job
  keeps its positions and state in Flink checkpoints (--checkpoint-every)`. A `--checkpoint-every` of zero exits with
  2 before the job is built.
- The k3s Deployment `opentsx-stats` sets `enableServiceLinks: false`. `bin/opentsx stats` reads the `OPENTSX_`
  variable of every registry key. The variable `OPENTSX_RETRIEVAL_PORT=tcp://...` that Kubernetes sets for the Service
  `opentsx-retrieval` is not an int and would stop it with exit 2.
- `bin/opentsx stats` and `stats-flink` are in the list of commands that load the configuration
  (`Registry.LOADS_CONF`, Python `CONF_COMMANDS`). Their help in both runtimes no longer says that the command parses
  its own flags. Appendix D and the defaults templates name `metadata`, `stats`, `stats-flink` and `windpark-sim` as
  the commands that read them. A Python test checks that both lists are equal.
- `bin/opentsx stats`, `stats-flink`, `metadata` and `windpark-sim` take `--version`, print `opentsx <version> (java)`
  on stdout and exit with 0.
- A secret key on the command line names every environment variable of the key in both runtimes, for example `--token:
  secret keys are not accepted on the command line; set OPENTSX_OPENMETADATA_TOKEN or OPENMETADATA_JWT or put
  opentsx.openmetadata.token in a properties file`.
- A domain outside the topic grammar is refused as `domain: "Wind" is not allowed; expected lowercase letters, digits,
  _ and -, starting with a letter or digit, such as wind`.
- `bin/opentsx windpark-sim` loads its configuration with `OpenTsxConf.load`: `--conf`, `--properties-file`,
  `$OPENTSX_CONF_DIR` and the `OPENTSX_` variables work, `--help` prints the keys on stdout with exit 0, and an
  unknown or malformed option, such as `--partitons 12`, exits with 2 and one line (`did you mean --partitions?`)
  before any topic is created. A runtime failure exits with 1 and prints one line `opentsx: windpark-sim: <failure>;
  caused by <cause>` instead of a stack trace; `opentsx.log.level=debug` logs the trace, also for a failure while the
  scenario or catalog loads. The command logs one `starting windpark-sim` line with every value and its source.
- `windpark-sim` reads `opentsx.windpark.sinks` (`--sinks`, default `auto`, or a list of `kafscale` and `iceberg`).
  With `auto` a sink is on only when `--kafscale` or `--iceberg`, or `--conf` with its key, sets it on the command
  line, so `KAFSCALE_BOOTSTRAP` or `OPENTSX_ICEBERG_PROPERTIES` in the environment adds no sink.
- `windpark-sim` checks the keys, the scenario, the catalog, the Iceberg properties (`warehouse` and exactly one of
  `type` and `catalog-impl`) and the `--ground-truth` path before it opens the index, the admin client or the
  producer; `--kafscale ks:9092 --iceberg type=rest,uri=x` exits with 2 and creates no topic.
- `SimulationReport.stateSeconds` holds seconds as `Double`, and the `windpark-sim` summary prints them as `states
  {1=21310.0, 2=1.0, 3=289.0}`.
- The descriptor `gen.windpark` states the scenario rules the simulator enforces: positive durations, required `rows`
  and `columns`, spacing and wake decay of 0 or more, the required parameters of each operation and sensor fault, and
  `--sinks`.
- `bin/opentsx metadata` reads its keys through `OpenTsxConf.load`. An unknown or misspelled option such as
  `--namspace` exits with code 2 and "did you mean --namespace?" instead of writing request files. A flag without a
  value and a malformed value also exit with 2, and `--help` prints the keys on stdout with exit 0. Every value is
  checked before the first request. With profiles on, the statistics are also read before the first request, so a bad
  KafScale bootstrap leaves no request on the server or in the `--dry-run` directory. A runtime failure prints one
  line and exits with 1. The command logs one line `starting metadata: key=value (source), ...` with the token as
  `******`.
- The k3s Jobs `windpark-sim`, `metadata` and `metadata-publish` set `enableServiceLinks: false`. Kubernetes derives
  `OPENTSX_RETRIEVAL_PORT=tcp://<ip>:8080` from the Service `opentsx-retrieval`, and the configuration loader accepts
  only an int for that variable.
- SaaS backend: every error answers {"error": "<text>", "type": "<type>"} with the types of the retrieval server
  (bad_request, not_found, method_not_allowed, too_large, unauthorized, forbidden, unavailable, internal). A refused
  request names the field and the problem and no longer repeats the input, so a password sent to register does not
  come back; an unknown path answers 404 `<method> <path>: no such route; see /docs for the routes`, a wrong method
  405 with the methods the path takes.
- SaaS backend: a fault answers 500 {"error":"internal error","type":"internal"} with the CORS headers and writes one
  line at ERROR with method, path and the exception with its causes; the stack trace is logged at DEBUG only.
- SaaS backend: error texts follow `<subject>: <problem>; <what is allowed>` without a final period. A refused
  binding, system id, flow id, flow status or organization_id names the value it refuses, for example `grade: "x" is
  not allowed; expected one of raw, clean`. A request without a bearer token answers `Authorization: no bearer token;
  sign in and send Authorization: Bearer <access token>`, and a token that is not valid answers `token: not valid or
  expired; sign in again`.
- The workbench reads the reason of a failed request only from `error` of the body `{"error": "<text>", "type":
  "<type>"}`; the backend no longer sends `detail`.
- `retrieval-server` loads its configuration through the key registry: an unknown option such as `--prot` or `--demo`,
  an option without value and a malformed value exit with code 2 and one line that names the nearest option, `--help`
  prints the keys and exits 0 without binding a port, and a failing Iceberg catalog prints its own message with exit
  code 1 instead of an `InvocationTargetException` trace.
- `retrieval-server --source auto|demo|iceberg|kafscale` chooses the store; `auto` follows only `--iceberg` and
  `--kafscale` on the command line, so `KAFSCALE_BOOTSTRAP` or `OPENTSX_KAFSCALE_BOOTSTRAP` in the environment does
  not switch the store, and `--iceberg` together with `--kafscale` exits with code 2 before the catalog opens.
- `vectors`, `vectors deploy` and `vectors stop` load through the key registry and check the command, `--name`,
  `--dag` and the DAG file before the first call to the broker: `stop` without `--name` exits with code 2 instead of
  writing `dag: null` and printing `stopped null`, and a DAG the processor would refuse exits with code 2 instead of
  1.
- The retrieval server answers 400 for a query parameter a route does not take, such as `metrc=` or `kyes=`, and for
  `agg` without `resample`, 413 `too_large` for a DAG over 262144 bytes instead of 400, and `the DAG is not valid
  YAML: <parser message>` for a broken DAG.
- `retrieval-server` and `vectors` log one `starting <command>` line with every effective value and its source, the
  run token as `******`, and `opentsx.log.level` sets their log level; the retrieval server logs `listening on
  <address>:<port>, source <source>` and its DAG mode instead of printing them on stdout.
- The k3s retrieval and vector-processor pods set `enableServiceLinks: false`, because the Service `opentsx-retrieval`
  would set `OPENTSX_RETRIEVAL_PORT=tcp://...`, and `deploy/k3s/base/retrieval.yaml` passes `--source demo` instead of
  `--demo true`.
- CI starts the retrieval server with `--source demo`; `--demo` is refused with exit code 2.
- The operations chapter states that the retrieval server needs a run token in `OPENTSX_DAG_RUN_TOKEN` or
  `OPENTSX_RETRIEVAL_DAG_RUN_TOKEN` for `--dag-runs true`, that `--bind` chooses the address and that
  `--open-dag-runs` listens on 127.0.0.1 only.
- `twin`, `house`, `boat`, `twin-coupling`, `twin-schemas` and `studio-export` load their keys with
  `OpenTsxConf.load`. `--help` prints the keys on stdout with exit 0, `--version` prints the version, and `--conf`,
  `--properties-file`, `$OPENTSX_CONF_DIR` and the `OPENTSX_` variables set the keys. Every command exits with 0, 1 or
  2: a usage or configuration error is one line such as `opentsx: --hdd-ref: "abc" is not a double; expected a finite
  decimal number such as 0.01 or 1e-3`, a runtime failure one line with its causes, and `--conf
  opentsx.log.level=debug` adds the stack trace.
- A missing or invalid twin plan, bills file, temperatures file or boat data file is a usage error that names the file
  (exit 2), and a refused row also names its line in the file, comment lines included; a plan that exists but cannot
  be read exits with 1. `boat --zone` with an unknown zone exits with 2 instead of a `ZoneRulesException` trace.
- The k3s Jobs `twin`, `house`, `boat` and `studio-export` set `enableServiceLinks: false`. Kubernetes derives
  `OPENTSX_RETRIEVAL_PORT=tcp://<ip>:8080` from the Service `opentsx-retrieval`, and the configuration loader accepts
  only an int for that variable.
- A refused plan of `twin`, `house` or `boat` names the file and, when the rule does not say what is allowed, the
  example plan of the command: `h2-chain.yaml`, `haus-erzgebirge.yaml` or `boot-etap22.yaml`.
- `dwd stations|mosmix|obs|house` read their options through the configuration registry (`OpenTsxConf.load`). An
  unknown command or option, a malformed value, a missing offline file or a directory in its place, and an offline
  file whose content cannot be parsed exit with code 2 and one line, for example `opentsx: --n: "abc" is not an int;
  expected a whole number from -2147483648 to 2147483647`. `--help` prints the keys of the command and `--version` the
  version, both with exit code 0. `--catalog CDC` is refused with the allowed values `mosmix, cdc` instead of falling
  back to `mosmix`. An option given twice exits with code 2 (`--n: set twice on the command line`); before, the last
  value won.
- The DWD downloads take their proxy from the shared secret key `opentsx.https.proxy` (`OPENTSX_HTTPS_PROXY`, legacy
  `HTTPS_PROXY` and `https_proxy`), which replaces `opentsx.dwd.httpsProxy`. The JVM property `https.proxyHost` still
  wins. A value without a host is refused with exit code 2, and the value is not echoed.
- `DwdClient` tries a download answered with 429 or a 5xx, or one that timed out, up to 4 times. It waits 2, 4 and 8
  seconds, or the `Retry-After` seconds of the answer, at most 60 seconds, and logs one WARN line per retry. An
  unknown host, a TLS error and every other answer fail at once.
- `modbus check|read|poll|simulate` read their options through the configuration registry. `check --map example --prot
  99` exits with code 2 and `did you mean --port?`. `--port abc`, a port outside 1 to 65535, a unit outside 0 to 255,
  a missing map file and an invalid map exit with code 2 and one line, without a stack trace. `simulate --help` shows
  the default `--bind 127.0.0.1`. `--interval` and `--episode` take ISO-8601 or a whole number with a unit (`PT5S`,
  `5s`, `500ms`), and `1.5s` is refused. An option given twice exits with code 2 (`--count: set twice on the command
  line`); before, the last value won.
- `modbus poll` prints the first failed read in full. While reads keep failing it prints one count per minute. When
  the poll ends it prints the failures not counted yet, and it prints one line when reads succeed again. Before, it
  printed one line per failed block and cycle.
- The k3s Jobs `opentsx-modbus-poll` and `opentsx-dwd`, the CronJob `opentsx-dwd-forward` and the Deployment
  `opentsx-modbus-sim` set `enableServiceLinks: false`, so the Service variable `OPENTSX_RETRIEVAL_PORT` does not
  reach the configuration of their commands.
- The k3s Job `opentsx-modbus-poll` takes its 30 cycles from `OPENTSX_MODBUS_COUNT`, so `40-demo.sh modbus -- --count
  300` still overrides them on the command line.
- `twin-live publish`, `twin-live bridge` and `twin-live demo` load their configuration through the key registry. An
  unknown option, an option without a value or a malformed value prints one line on stderr and exits with code 2.
  `--help` and `--version` print on stdout and exit with code 0. The commands take `--kafscale`, `--conf` and
  `--properties-file`, and keep `--bootstrap`, `KAFSCALE_BOOTSTRAP` and `EVENT_BRIDGE_ALLOW_ORIGIN` as legacy names.
- `twin-live` takes its log level from `opentsx.log.level` (`OPENTSX_LOG_LEVEL`) and `conf/simplelogger.properties`
  instead of forcing `warn`, and logs one `starting twin-live <command>` line at INFO with every value it reads.
- The k3s component `event-bridge` sets `enableServiceLinks: false`, so the Service variable `OPENTSX_RETRIEVAL_PORT`
  does not stop the bridge from loading its configuration.
- `Episodes` and `TimeAxis` refuse inconsistent input with messages that name the field, in the form `<field>:
  <problem>; <what is allowed>`, for example `count: "3" differs from the length 2 of values; expected the number of
  values` instead of `count 3 != values 2`, and `tEnd: "20" is not after the last timestamp 20; expected a time after
  the last timestamp` instead of `tEnd cuts off values`.
- `bin/opentsx market <command>` and the history twins (`bin/opentsx twin|house|boat --history <dir>`, registry
  commands `history-twin twin|house|boat`) read their keys through the configuration registry: `--conf`,
  `--properties-file`, `OPENTSX_CONF_DIR` and the `OPENTSX_` variables work, `<command> --help` lists the keys, and an
  unknown option exits with 2 and names the nearest one, for example `--form-year` suggests `--from-year`. The
  hand-written usage of `market` is gone; `market import --help` lists `--partner`.
- Market exit codes follow 0, 1, 2: a backfill year that failed, a failed download and a publish the broker did not
  confirm exit with 1 instead of 3 or 2, and a fetch whose credential is not set exits with 2 and names the key
  instead of 4. Every failure is one line `opentsx: <message>` on stderr instead of `market <command>: <message>`.
- `market prices|series|rebap|marktwert` store into the archive per `--store`: `always`, `never`, or `auto` (default),
  which stores only when `--archive` is on the command line. An archive directory from the environment or a file no
  longer switches storing on. `market load` writes to Iceberg only when `--iceberg` or `--conf
  opentsx.iceberg.properties=<list>` is on the command line; `OPENTSX_ICEBERG_PROPERTIES` or a file does not switch
  it.
- The market credentials `opentsx.market.entsoeToken`, `opentsx.market.ntpClientId` and
  `opentsx.market.ntpClientSecret` also come from `OPENTSX_MARKET_ENTSOE_TOKEN`, `OPENTSX_MARKET_NTP_CLIENT_ID`,
  `OPENTSX_MARKET_NTP_CLIENT_SECRET` or a properties file; they are refused on the command line, and the start line of
  the log shows them as `******`. `market backfill` and `market forward` name the key when they skip a source, for
  example `skip entsoe/load: opentsx.market.entsoeToken not set`.
- `market import --origin` takes only `REPORTED` or `FORECAST`; another value exits with 2 (`opentsx: --origin: "x" is
  not allowed; expected one of REPORTED, FORECAST`).
- `opentsx vectors depoly` (Java and Python) answers `depoly: unknown subcommand of vectors; did you mean deploy?`
  instead of `unexpected argument`; a word after a command that has subcommands is checked against them.
- The credential messages of the ENTSO-E and netztransparenz.de sources name the key and every variable that sets it,
  for example `opentsx.market.entsoeToken: rejected by ENTSO-E (HTTP 401); check the token in
  OPENTSX_MARKET_ENTSOE_TOKEN, ENTSOE_TOKEN or the properties file`.
- `feature.rolling` in the vector processor keeps compensated running sums and monotonic deques, so every sample
  enters and leaves its window once and a value that has left the window leaves no rounding behind. 300000 samples
  with a 6000-value window take 20 to 73 ms per aggregate instead of 4.3 to 5.0 s. The checkpoint of a series sink
  carries the running sums.
- `VectorProcessor` measures its timing with an injectable clock, and its tests advance that clock instead of
  sleeping.
- SaaS backend: a key in .env that is not a setting stops the start and the message lists the settings; delete
  DATABASE_URL, REDIS_*, SMTP_*, EMAILS_FROM_*, STRIPE_*, ENABLE_INVITATIONS, ENABLE_PAYMENTS,
  REFRESH_TOKEN_EXPIRE_DAYS, DEBUG and BACKEND_PORT from an existing .env (set BACKEND_PORT in the shell for docker
  compose).
- SaaS backend: DAG_RUN_ROLE takes superuser or user, RETRIEVAL_URL an http or https address, ALGORITHM HS256, HS384
  or HS512, and BACKEND_CORS_ORIGINS refuses "*"; any other value stops the start and names the variable.
- SaaS backend: the page at / is a plain page that links /docs, the OpenAPI document, /health and the web application;
  the mock login and flow list are gone.
- SaaS backend: register with a known address, a wrong sign-in and a wrong current password answer texts in the
  <subject>: <problem>; <what is allowed> form; a binding signal over 200 characters, an unknown flow status and a
  boolean generator seed are refused without repeating the value.
- SaaS backend: the Dockerfile sets PYTHONUNBUFFERED=1 and python -m app.main runs without reload.
- The DAG and system routes of the SaaS backend log through the loggers `app.api.dags` and `app.api.systems` instead
  of `uvicorn.error.dags` and `uvicorn.error.systems`.
- `kafscale-check` loads its configuration through the registry. An unknown or misspelled option, a missing or
  malformed value, or a bootstrap address that is not host:port exits with code 2 and one line before the first
  connection, for example `opentsx: --kafscle: unknown option of kafscale-check; did you mean --kafscale?`. `--help`
  prints its keys and exits with 0. It reads `OPENTSX_KAFSCALE_BOOTSTRAP` (legacy `KAFSCALE_BOOTSTRAP`),
  `OPENTSX_DOMAIN` and the other variables of its keys. The result lines `reachable`, `NOT reachable`, `FAIL:` and the
  topic table stay on stdout.
- The k3s manifests pass ConfigMap values to their commands as the registry variables of the keys instead of options,
  for example `OPENTSX_STATS_WINDOWS`, `OPENTSX_KAFSCALE_PARTITIONS`, `OPENTSX_OPENMETADATA_URL`,
  `OPENTSX_MODBUS_HOST` and `OPENTSX_ICEBERG_PROPERTIES`. Four ConfigMap keys are renamed: `OPENTSX_SCENARIO`,
  `STATS_WINDOWS`, `STATS_LATENESS` and `ICEBERG_NAMESPACE` become `OPENTSX_WINDPARK_SCENARIO`,
  `OPENTSX_STATS_WINDOWS`, `OPENTSX_STATS_ALLOWED_LATENESS` and `OPENTSX_ICEBERG_NAMESPACE`. The keys of
  `deploy/k3s/.env` keep their names. `windpark-sim` names its sinks and the retrieval server its source explicitly.
  `40-demo.sh <demo> -- <option>` can override the bootstrap, domain, scenario and partitions, `--provision` of
  `kafscale-check` and `--profiles` of `metadata-publish`. CI loads every rendered `opentsx/tools` container through
  `OpenTsxConf`, except the `dwd` Job and the `dwd-forward` CronJob, whose scripts build their options from variables.
- User manual appendix A lists every command of `bin/opentsx` and the image. It names the two differences between the
  launchers and has a table of the common configuration errors with the exact message and the fix.
- `Patience`, `LogTail` and `EpisodeLogTail` of `opentsx-kafka-v2` and the statistics processor of `opentsx-stats` log
  through SLF4J instead of printing to stderr, one line per event in the form `<what happened> key=value ...`.
  `Patience` logs the first refused read of a row at WARN (`the broker refuses reads; waiting pause=PT5S limit=PT10M
  cause=...`) and the next read that is not refused at INFO (`the broker no longer refuses reads
  refusedFor=<duration>`). `LogTail` logs a retention gap and `EpisodeLogTail` the first undecodable record per
  partition at WARN with topic, partition and offset.
- The statistics processor (`bin/opentsx stats`) logs its first undecodable record and first invalid episode per
  partition and every retention gap at WARN with topic, partition, offset and cause. It logs the restored checkpoint
  or `no checkpoint`, the assigned partitions with their start offsets (WARN `no partitions assigned` when there are
  none), one INFO progress line per checkpoint with `records`, `undecodable`, `invalid`, `lostToRetention`,
  `statsWritten`, `duplicates` and `lateValues`, `stopping` when the JVM shuts down during a continuous run (not after
  a failed run), `final checkpoint written` with the positions, and a WARN when the run does not end within 60 s after
  `stopping`.
- `LogReader.readAll` is public, and `SeriesStatsLog.readLatest` reads through it instead of its own loop. A topic
  that does not exist is an error for `SeriesStatsLog.readLatest`, `BucketCatalog.load` and `EpisodeIndexer.rebuild`:
  `IllegalStateException` with `<topic>: no such topic; check the domain, or create the topic first`, instead of an
  empty result. `bin/opentsx metadata` with profiles on therefore exits with 1 before the first request when the
  statistics topic does not exist, instead of publishing 0 profiles.
- Retrieval server and vector processor log through slf4j, one line per event in the form <what happened> key=value,
  so opentsx.log.level controls them. Nothing goes to stdout any more. A failed sink is one ERROR line with the cause
  chain, with the stack trace at DEBUG once per distinct failure. The problems of the system documents are one WARN
  summary, with each problem at DEBUG. On SIGTERM the vector processor logs stopping, final checkpoint written per
  sink and stopped, or a WARN when the 60 s wait runs out.
- The SaaS backend answers 503 `retrieval server: cannot reach the broker; try again` when the retrieval server
  answers 503 to a DAG deploy, a DAG stop or a generate, instead of 502.
- `opentsx-twin-live` keeps slf4j-simple in test scope only; `bin/opentsx` and the image add the binding with
  `conf/simplelogger.properties`, as for the other modules.
- The Flink `StatsFunction` logs its first undecodable record and first invalid episode per partition at WARN instead
  of printing them on stderr, and logs `progress subtask=<n> records=<n> undecodable=<n> invalid=<n> statsEmitted=<n>`
  at INFO once per `--global-every`, also in a subtask whose inputs all fail to decode or are invalid.
- `DynamicTopologyJob` without a broker, or with an empty one, fails with `bootstrap.servers: not set or empty; set
  the variable OPENTSX_BOOTSTRAP_SERVERS to <host:port>, the JVM option -Dbootstrap.servers=<host:port>, or the key
  bootstrap.servers in config/opentsx-default.properties or in the file that OPENTSX_CONFIG_FILE names`; the
  `pfd-acceptance` profile reads `KAFSCALE_BOOTSTRAP`, as the other broker-backed tests do, and passes it to the job
  as `OPENTSX_BOOTSTRAP_SERVERS`.
- The source of the Flink statistics job (`StatsInputDeserializer`) logs the first undecodable record of a partition
  at WARN with topic, partition and offset instead of printing it on stderr.
- Iceberg store: new IcebergSeriesStore(catalog, namespace) borrows the catalog like openExisting(catalog, namespace),
  so closing the store no longer closes the caller's catalog. open(props, ns) and openExisting(props, ns) own their
  catalog and also close it when opening fails.
- EpisodeQuery refuses a limit below 1 with 'limit: <n> is out of range; expected a whole number above 0'.
- Station ids of the history sites file are strings of 5 digits in quotes, and `--stations` of the weather backfill
  takes 5-digit ids; `wind: 1759` or an unquoted `03166` stops the command with exit code 2 and names the field.
- A rejected netztransparenz.de client and a token answer without `access_token` name the keys
  `opentsx.market.ntpClientId` and `opentsx.market.ntpClientSecret` and all their variables.
- Workbench: "Publish as clean signal" and the deploy list check the parameters of every step as the processor does
  and name the step and the parameter, for example `The processor refuses a parameter of Hub_wind: alpha: "0.7" is out
  of range; expected a number from 0 to 0.6`.
- Python package: the extra `all` installs `kafka`, `iceberg`, `viz` and `yaml`. It installed no PyIceberg and pulled
  TensorFlow, Numba and Dask.
- Python: `EventSynchronization.analyze` names a leader only when the magnitude of `delay` is above
  `delay_surrogates`, the largest one of 19 copies of series 2 with shuffled inter-event intervals (new arguments
  `surrogates` and `seed`, new result key `delay_surrogates`), instead of above a fixed 0.05. Of 200 independent
  spike-train pairs 13 name a leader instead of 85.
- Python: the DFA `interpretation` uses bands of 0.05 on both sides of 0.5 and 1, so 0.47 is uncorrelated and 0.97 is
  1/f noise, names an alpha above 1.05 `Non-stationary (fractional Brownian motion)` and raises `ValueError` for an
  alpha that is not finite. This is a breaking change.
- Python: `TimeSeriesObject.from_pandas` takes a `DatetimeIndex` as epoch microseconds with pandas 2 and 3 (pandas 2
  gave nanoseconds) and no longer triggers the pandas 3.1 `Pandas4Warning`. This is a breaking change for pandas 2.
- Python: `encode_deltas`, `decode_deltas`, `regular` and `irregular` raise the messages of Java `opentsx-model` word
  for word, listed in the shared vectors `docs/datamodel-2026/testvectors/model-refusals.v1.json`. `regular` refuses
  an empty episode whose `tEnd` is not after `tStart`, and `decode_deltas` refuses a varint above 64 bits with
  `ValueError` instead of `OverflowError`.
- Python: `QuantileSketch.from_bytes` raises the messages of Java, `sketch bytes: truncated at <k> of <m>` and `sketch
  bytes: <n> trailing bytes; expected exactly <m>`.
- Python: `TimeSeriesObject.normalize`, `resample` and indexing name the value and the allowed values, for example
  `method: 'x' is not one of zscore, minmax, robust`.
- Python: `read_episodes` and `read_frame` check `start` and `end` before they load the table and name the bound that
  has no time zone or is not an ISO 8601 time.
- `deploy/k3s/00-prereqs.sh`, `40-demo.sh`, `60-probe.sh`, `70-backup.sh` and `90-teardown.sh` exit with 2 on a usage
  error, such as an unknown option, an option without its value, an unknown demo or extra arguments for a demo that
  takes none, and their `--help` lists the exit codes 0, 1 and 2. `40-demo.sh` checks the demo name and the extra
  arguments before it reads the overlay.
- Every k3s script refuses a line of `deploy/k3s/.env` that is not a comment or `KEY=VALUE` with a key of
  `env.example` with exit code 2, for example `deploy/k3s/.env:8: unknown key SAAS_ADMIN_PASWORD; use a key of
  deploy/k3s/env.example`, instead of running the line or ignoring a misspelled key that `20-configure.sh` then
  dropped.
- The `modbus-poll` and `dwd` demo Jobs set their defaults as registry variables (`OPENTSX_MODBUS_INTERVAL`,
  `OPENTSX_MODBUS_OUT`, `OPENTSX_MODBUS_EPISODE_LENGTH`, `OPENTSX_DWD_OFFLINE`, `OPENTSX_DWD_OUT`,
  `OPENTSX_DWD_DAILY`), so an option after `40-demo.sh <demo> --` replaces the default instead of failing with `set
  twice on the command line`. `40-demo.sh dwd-online` sets `OPENTSX_DWD_STATION`, and the `dwd-forward` CronJob passes
  the fixture as `OPENTSX_DWD_OFFLINE`.
- The CI manifest check loads every `opentsx/tools` container through the configuration loader, the DWD Job and
  CronJob included, and fails on a command argument that comes from the shell instead of skipping it.
- CI runs the shell tests of `40-demo.sh`, `60-probe.sh`, `70-backup.sh` and `90-teardown.sh` and runs shellcheck on
  `bin/000_validate_environment.sh`, `bin/run_all_tests.sh` and `opentsx-saas-backend/local-setup.sh`.
  `deploy/docker/test-launchers.sh` tests `deploy/docker/stage.sh` with a fake Maven and checks one module's classpath
  with `--classpath MODULE`.
- `bin/000_validate_environment.sh` exits 1 when a check fails and writes failed checks to stderr. It needs Maven 3.9
  or later and exits 2 on an unknown option. A `java -version`, `mvn -version` or `git --version` that fails, for
  example under a wrong `JAVA_HOME`, is a failed check that names the first line of its output. A failing `docker
  --version`, `free` or `df` is a warning. The `--strict` option is removed.
- `bin/run_all_tests.sh` reports the Maven test, the `bash -n` syntax checks and the required files as separate
  counts, in `test-report.txt` and in `test-summary.json` (`tests`, `syntax_checks`, `file_checks`).
- `docker-compose.local.yml` runs Kafka `confluentinc/cp-kafka:7.6.0` in KRaft mode and
  `confluentinc/cp-schema-registry:7.6.0`, replacing the all-in-one image, which does not exist. The HBase and
  OpenTSDB services and their volumes are removed. The OpenTSDB store tests use their embedded emulator. CI checks
  that the file resolves and that each of its images exists, and fails when `docker compose config --images` fails or
  lists no image.
- `opentsx-legacy-bridge` stamps the producer `opentsx-legacy-bridge/<version>` of the build instead of the literal
  `opentsx-legacy-bridge/3.0.1` and picks REGULAR or IRREGULAR_DELTA with `Episodes.of`. An empty series fails with
  `values: empty; expected at least one value` instead of `cannot convert an empty series`.
- `windpark-sim` and `WindparkSimulator.groundTruth()` list only the operations and sensor faults that overlap the
  simulated span, from the scenario start to the end of the last episode, with each event's end clipped to that span.
  A 20-minute run of `ostsee-demo.yaml` lists no event instead of all 17. The full 6-hour run still lists 17.
- A wind-farm scenario is refused when its `episodeDuration` is not a multiple of `sampleInterval` or its `start` is
  off the `episodeDuration` grid. The refusal names the field, both values and what is allowed, for example `start:
  2026-10-01T00:05:00Z is not aligned to episodeDuration PT10M; set start to a whole multiple of episodeDuration since
  1970-01-01T00:00:00Z, such as 2026-10-01T00:00:00Z`.
- `opentsx-data` generates its Avro classes into `target/generated-sources/avro` with the Avro 1.12.1 plugin of the
  parent POM; `src/main/java` holds only `LatencyTesterEvent`. Getters and setters of fields that cannot be null use
  primitive types, for example `long Observation.getTimestamp()`, `double Observation.getValue()` and `long
  Event.getProducerTimestamp()` instead of `Long` and `Double`. This is a breaking change.
- `opentsx-data`: `event-series.avsc` refers to the `Event` record of `event.avsc`, so `org.opentsx.data.model.Event`
  has one definition with 5 fields and the generated classes no longer depend on the order in which the schemas are
  read. The schema of `EventSeriesRecord` now holds the 5-field `Event`. This is a breaking change.
- `Observation` of `opentsx-data` returns `long` from `getTimestamp()` and `double` from `getValue()`, as Avro 1.12.1
  generates it; code that overrides these getters with `Long` or `Double` or calls `longValue()` on them no longer
  compiles. `TimeSeriesAggregateFunction` of `opentsx-flink-core` adds every observation without a null check, because
  an `Observation` cannot hold a null. This is a breaking change.
- `ESMain` and the Swing sigma filter of `opentsx-core` write their figures below the home directory of the user
  (`user.home`) instead of a fixed personal path.
- The twin scenarios, the Studio guides, the CSV templates, the bundled example READMEs and the Smart Topics Studio
  page are in English, and the page sets lang="en". File names stay as they are.
- The bills CSV of `house --calibrate` uses the columns period_from, period_to, electricity_kwh, heating_oil_l,
  tank_start_l, tank_end_l, wood_ster, cost_eur and note, and the boat data CSV uses time, mode (harbour, sailing,
  anchor, away), shore, soc_percent, load_w, ghi_wm2, <device>_on and note. The German column names are accepted as
  aliases; a column named twice, also once in English and once in German, is refused in both files.
  `BillCalibration.COLUMNS` and the quantities in the calibration report use the English names.
- The bundled simulated bills, daily temperatures and boat logs have English headers, modes and notes and are marked
  SIMULATED; their numbers are unchanged.
- The design documents 01, 03, 08, 09, 10 and 16 in `docs/datamodel-2026` are in English. 08 and 10 name the English
  CSV columns, their German aliases and the span limits, 09 and 16 the English Studio labels, and 01 the schemas of
  `opentsx-data`, the platform versions and the defects that are still open.
- `TopicsUP` and `TopicsCHECK` run as programs call `TopicsUP.execute()` and `TopicsCHECK.execute()`, so they print
  the same lines as the methods.
- The 2.x library classes of `opentsx-core` in `org.opentsx.data`, `org.opentsx.core` and `org.opentsx.algorithms`,
  `MFDFAAnalyzer.processMFDFA` among them, write nothing to standard output. They log progress through SLF4J at DEBUG
  under the class name, one line per event, and throw the failures they used to print. `opentsx-core` declares
  `slf4j-api`.
- `TimeSeriesObject.getStatisticData(table)` and `getStatisticData(prefix)` throw `IllegalStateException` for a series
  without values and propagate the failure of a value that cannot be read, instead of printing a stack trace or
  returning `!!! NO STATISTICS for time series !!!`. The text of a series without values has no statistics block.
- `ESCalc2.calcES` throws `IllegalArgumentException` when a series has fewer than 3 events instead of printing an
  error and returning -2.
- `ReturnIntervallStatistik2.add` and `TSPropertyTester.add` throw `IllegalArgumentException` for a NaN or infinite
  probability instead of printing `ERROR 3` and adding it. Their `add` and `addDistData` throw `IllegalStateException`
  or `IllegalArgumentException` with English messages instead of a plain `Exception`.
- `ProductionConfig` (episode 10) sets the `java.util.logging` root level from `OPENTSX_LOG_LEVEL`, then from
  `opentsx.log.level` in `./opentsx.properties`, then `info`, and refuses a value other than `error`, `warn`, `info`,
  `debug` or `trace`. An empty `OPENTSX_LOG_LEVEL` counts as unset and blanks around a level are ignored.
  `opentsx.properties.example` sets `info`.
- opentsx-connectors: `TSOProducer.pushTSDataAsEpisodesToKafka_String_Avro` and
  `pushTSDataAsEventsToKafka_String_Avro`, `TSOEventProducer.pushTSOItemsToKafka`,
  `EventFlowStateProducer.pushFlowState` and `LatencyTestEventProducer.pushTestEventToCluster` wait for each record
  and throw `<topic>: could not send the record; <cause>` when the producer refuses the record or the cluster does not
  acknowledge it, instead of printing the stack trace and going on. `TSOWriter.persistBucket_SequenceFile` passes on a
  file system error instead of failing later with a NullPointerException.
- opentsx-connectors: `TSOConsumer` and `TSOEventProducer` take `bootstrap.servers` from the Kafka client profile
  instead of the fixed `localhost:9092`.
- opentsx-connectors: the helpers log through SLF4J, one line per event at DEBUG, instead of printing traces on
  stdout. The module declares `slf4j-api`.
- `TopicsCHECK` exits with status 1 instead of 255 when a listed topic is missing, and its verdict on stdout names the
  topic list and the missing topics: `<topic list>: topics missing on the cluster [<topic>, ...]; create them with
  TopicsUP`.
- `TSOWriter.persistBucket_Kafka`, deprecated and never implemented, throws `UnsupportedOperationException` instead of
  printing that it stored nothing.
- opentsx-connectors: `TSOConsumer.runConsumer` no longer declares `InterruptedException`, which it never threw. Code
  that catches `InterruptedException` around the call no longer compiles. This is a breaking change.
- opentsx-lg: `TSDataSineWaveGenerator`, `EventFlowAnalysisProducer`, `EventFlowAnalysisConsumer`,
  `EventFlowAnalysisPrecheck`, `EventFlowAnalysisSetup`, `RequestResponseAnalysisProducer` and
  `RequestResponseAnalysisConsumer` stop with status 1 at the first failed send, poll or topic setup instead of
  printing it and going on. `EventFlowAnalysisSetup` no longer exits with 0 after a failure.
- opentsx-lg: `FWL_TSA_KStreamsApplication` and `FWL_TSA_KTableApplication` take `bootstrap.servers` and
  `schema.registry.url` from the Kafka client profile instead of fixed private addresses. `lg-demo.yaml` mounts the
  ConfigMap `opentsx-lg-demo-config` instead of a host path and has no host aliases, and the krake-metrics-provider
  compose files take the host addresses from `MDC1`, `MDC2` and `MDC3`.
- opentsx-lg: the scripts use the caller's `JAVA_HOME` or the `java` on `PATH` instead of a fixed macOS JDK path.
  `run_demo.sh` prints its KSQL statements and passes `bash -n`. The ping tool `executor-script.sh` runs
  `TSDataSineWaveGenerator` after the topic setup and stops at the first failing step.
- The four example notebooks in `python-package/notebooks` and their sample file `beispiel-windmessung.csv` are in
  English. The file names stay the same.
- The sample file of notebook 3 has the columns `date;time;wind_kmh`. Notebook 3 also reads a file with the German
  header `Datum;Uhrzeit;Wind_kmh` with the same settings.
- Notebook 3 stops with `<setting>: the file has no column "<name>"; expected one of <columns>` when `TIME_COLUMNS` or
  `VALUE` names a column the file does not have, instead of a `KeyError`. It writes its prepared copy as
  `checked-<signal>.csv`.
- The Python CI job installs the `viz` extra, so the four notebook tests in `test_notebooks.py` run instead of being
  skipped without matplotlib.
- `EVOLUTION/TASK-014-engineering-flow.md` is in English and listed in the EVOLUTION index. It and
  `docs/datamodel-2026/16-DEMO-UND-SIMULATIONSEXPERTEN.md` no longer name a local tool directory for the role
  definitions.
- TASK-019 no longer plans a Plotly or React Router upgrade: the frontend has not depended on Plotly since 2026-11-19,
  so the `maplibre-gl` audit finding no longer applies, and React Router 7 is already in place.
- The `QuantileSketch` refusals in Java and Python follow the form `<subject>: <problem>; <what is allowed>`, for
  example `alpha: 5.0 is out of range; expected a number above 0 and below 1` and `sketch bytes: format version -1 is
  not supported; expected 1`. Python writes alpha and q as Java's `Double.toString` does, for example `NaN`,
  `Infinity`, `1.0E300` and `-4.9E-324`.
- `bin/opentsx metadata` with profiles on logs `reading statistics before the first request
  topic=tsx.<domain>.series-stats.v2 timeout=PT5M` right after the start line, so a slow statistics topic no longer
  looks like a hang.
- The wind-farm scenario and normalizer refusals name the field first: `duration: PT-1H is not positive; ...`, `name:
  missing; ...`, `start: missing; ...`, `duration: missing; ...` and `park: missing; ...` (the first missing field),
  `sourceSystem: <name> is not in catalog <catalog>; use one of its sources: ...` and `episodeDuration: <value> is
  shorter than 1 microsecond; ...`.
- `opentsx-retrieval/README.md` lists every route of the retrieval server with a link to Appendix B, describes
  `--systems` and `--systems-root`, and says when the server writes: with `--dag-runs true` only, to the DAG control
  and status topics and to `tsx.<domain>.synthetic.v2`. It also says that no writer stores the SAX word in
  `EpisodeSummary.sax` and that DTW z-normalizes every window on its own.
- The `house` output and its calibration warnings use a colon, a semicolon or the word minus where they had a spaced
  hyphen, for example `House twin 'haus-erzgebirge': ALL NUMBERS ARE INVENTED EXAMPLE ASSUMPTIONS (not calibrated)`
  and `weather is synthetic; for real bills bind daily mean temperatures of a nearby station`.
- `EpisodeQuery` refuses a window that does not open before it closes with `fromMicros: <from> is not before toMicros
  <to>; expected fromMicros < toMicros` instead of `fromMicros must be before toMicros`.
- `modbus check`, `read`, `poll` and `simulate` print the problems of an invalid register map on one line, separated
  by `; `, for example `opentsx: register map bad.yaml is invalid: device: missing (id, host, port, unitId,
  pollInterval); points: at least one point is required`. `RegisterMapException.getMessage()` returns that one line.
- `modbus read` and `modbus poll` exit with code 2 when an option has no effect: `--seed` without `--simulate`
  (`opentsx.modbus.simulator.seed: needs opentsx.modbus.simulate; give --simulate with --seed`), and `--host` or
  `--port` with `--simulate`, whose meter listens on a free loopback port (`opentsx.modbus.host: cannot be combined
  with opentsx.modbus.simulate; give --host or --simulate, not both`).
- `dwd obs --offline` exits with code 2 when `--station`, `--param` or `--hourly` is set, because these options only
  select a download, for example `opentsx.dwd.station: cannot be combined with opentsx.dwd.obs.offline; give --station
  or --offline, not both`.
- `dwd mosmix` and `dwd obs` write the English column names the twins read: `--daily` writes `date,t_mean_c` and
  `--boat` writes `time,ghi_wm2`. A tool that reads these files by the old names `datum,t_mittel_c` or
  `zeit,einstrahlung_wm2` must switch to the new ones.
- `MeterNormalizer` refuses an episode length below 1 microsecond with `episodeDuration: PT0S is shorter than 1
  microsecond; set episodeDuration to a positive length, such as PT1M`.
- Iceberg-backed Retrieval opens existing episode and series tables without creating missing namespaces or
  tables. Its store rejects writes, including empty batches, and does not expose a mutable episodes table.
  The existing writer path retains provisioning behavior. The new properties-based reader factory closes its
  owned catalog if table loading fails; the catalog-taking overload leaves ownership with the caller.
- Unavailable desktop analysis and modern Java/Scala export buttons are disabled with explanatory tooltips.
  Both trackers label their JSON display action `Show PFD`.
- The modern desktop tracker starts with an empty transformation tree instead of Swing sample items.
- A Modbus TCP answer with a protocol id other than 0 fails with `MBAP protocol id: <n> is not 0; the peer is not a
  Modbus TCP server`.
- opentsx-dwd and opentsx-market read the proxy key `opentsx.https.proxy` through one class,
  `org.opentsx.dwd.HttpsProxy`; the rule and its refusal text are unchanged.
- Python messages in the D11 form: `EventSynchronization.surrogate_sync` raises `shuffles: 0 is below 1; expected at
  least 1 shuffled copy`, and `to_us(t, field)` and `timestamps_us` raise `<field>: <time> has no time zone; expected
  a timezone-aware datetime or epoch microseconds`.
- SaaS backend scripts `local-setup.sh` and `local-run.sh` print `ok`, `warn` and `FAIL` instead of emoji, and the
  backend pages use `docker compose` in the README and no emoji.
- CI: the Python 3.10 job installs the `iceberg` extra, so the Iceberg time parsing tests run on the Python version
  that needs them.
- SaaS backend README: a failed start logs its traceback at ERROR, also below `LOG_LEVEL=DEBUG`, and a stop signal
  while the backend waits for its database ends the process with exit code 3.
- SaaS frontend: the development image `opentsx-saas-frontend/Dockerfile` uses `node:22.14.0-alpine3.21` instead of
  the floating `node:22-alpine` and installs with `npm ci`.
- SaaS frontend: the page title is `OpenTSx`, and the page description names what the application has, without MFDFA
  and real-time streaming. The workbench says that Deploy hands the DAG to the vector processor and points to the
  place Models of the Systems page instead of an MLflow training. The flow builder no longer shows the emoji node
  legend or the paragraph that promised 15 node types.
- SaaS frontend README: the routes come from `src/App.tsx`, the 401 handling from `src/services/api.ts`, the page
  sections from the pages, and the nginx section from `nginx.conf` with `<host>` placeholders. Installs use `npm ci`
  and the full stack starts with `docker compose up -d --build`. The code copies and the sections on project
  structure, performance and browser support are deleted.
- The DevOps guide intro is in English and names the DAGs `opentsx.dag.v1` and `opentsx.dag.v2` that the vector
  processor runs. The descriptors of `pair.coverage` and `pair.xcorr` link section 16.8 of the manual, and
  `CONTRIBUTING.md` links the task list in `EVOLUTION/README.md` instead of naming the completed TASK-011 as the plan.
- The changelog lists the entries of the unreleased version under one heading per section, and the 3.0.0 features that
  stood under Fixed under Added.
- The frontend README now lists its packages in a table. For a taken port 3000 it shows how to find the process and
  how to start the dev server on another port. Before, it said to kill whatever held the port.
- The local test installation guide (`docs/devops/anleitung-lokale-testinstallation.md`) and the Smart Topic processor
  concept (`docs/datamodel-2026/11-SMART-TOPIC-PROZESSOR-DEVOPS.md`) are in English. The concept's status line says
  what runs today (the Java vector processor with `opentsx.dag.v1` and `opentsx.dag.v2`) and what is still a design
  (the Go processor, the sidecar, MLflow training, and running other operators as DAG nodes). Its
  `opentsx.dag-status.v1` row lists every field the processor writes.
- `docs/devops/history-archive.md` has no German summary. `docs/devops/README.md` lists all 12 k3s components with the
  key that turns each one on, and all 5 Dockerfiles. Its diagram and its table of deployed parts include the vector
  processor, the event bridge, the SaaS module and the ingress.
- The registry doc of `opentsx.history.station` and `opentsx.history.radiationStation` says the value is a DWD station
  id of 5 digits, such as 03166. Appendix D and the defaults templates are regenerated.
- Docs: the devops guide, the docs index and the devguide have no em dashes, spaced hyphens, filler or plan wording,
  and empty table cells read `n/a`. The demo table gives `retrieval` a runtime of seconds and `studio` a runtime of
  until Ctrl-C. The sizing and setup plans quote the image content measured with `deploy/docker/stage.sh` on 3.0.1: 67
  jars and 113 MB, or 70 jars and 173 MB with `--with-s3`, which adds iceberg-aws, iceberg-aws-bundle and failsafe.
  The local development page links to the user manual for writing episodes against the KafScale stack, in place of a
  Java example that did not compile. CONTRIBUTING shows the CI command `xvfb-run --auto-servernum mvn -B -ntp verify`.
  The opentsx-modbus README section is now `Bind a vendor register sheet`.
- CI runs `bash -n` and `shellcheck -x -P SCRIPTDIR` on `bin/000_launch_tsa_workbench.sh` and `bin/010_build.sh` as
  well.
- opentsx-core: `MultiDFATool.savePath` defaults to `opentsx/dfa/` and `HaeufigkeitsZaehler.folder` to
  `opentsx/histograms/` below `user.home` instead of a path on a removable drive; `HaeufigkeitsZaehler.store()`
  creates the folder.
- opentsx-core: the level of the SLF4J logger alone decides the DEBUG lines of `DFACore` (and `DFA`, `DFAmulti`,
  `MFDFA`), `MessreihenLoader` and `RISTool`; the public `debug` fields of `DFACore`, `SingleDFATool`, `ESCalc2`,
  `TimeSeriesObjectFFT`, `MessreihenLoader` and `RISTool` are deprecated and have no effect.
- `bin/010_build.sh` refuses an argument other than `--help` with exit code 2, uses `JAVA_HOME` when it is set and
  says what is needed when it is not, instead of a hard-coded Homebrew JDK path.
- `bin/000_launch_tsa_workbench.sh`, `bin/000_validate_environment.sh`, `bin/010_build.sh` and the `bin/episode_*.sh`
  scripts print plain `ok`, `warn` and `FAIL` lines and plain headings; the launcher reports the Java options only
  when it runs java directly, and the episode scripts pass `shellcheck`.
- opentsx-core: the guided demos print PASSED or FAILED instead of glyphs and drop their banner comments and filler
  lists; the remaining German strings and comments of opentsx-core are English.
- opentsx-legacy-bridge: LegacyBridge.seriesKeyFromLabel refuses an empty label with 'label: empty; expected "metric
  k1=v1,k2=v2"' and a tag without = with 'label: tag "<pair>" in "<label>" is not key=value; expected "metric
  k1=v1,k2=v2"'.
- opentsx-connectors: TopicsManagerTool.initTopicDefinitions returns the topic names of the list instead of null and
  declares IOException when the list cannot be read. This is a breaking change.
- opentsx-lg: EventFlowAnalysisConsumer writes its latency table to $HOME/opentsx/plots instead of
  /root/opentsx/plots.
- opentsx-lg: run_demo.sh, run_demo_container.sh, run_demo_docker_compose.sh, bin/build_all.sh, bin/run_cluster.sh and
  src/main/mqtt-example/env.sh (now executable) take CONFLUENT_HOME from the caller and exit 2 with 'CONFLUENT_HOME:
  not set; expected the directory of a Confluent Platform installation' without it. These scripts, build_demo.sh and
  run_ddc.sh have a bash shebang and stop when a cd fails.
- opentsx-lg: src/main/k8s/krake-app/lg-demo.yaml is marked as an example and names
  opentsx/time-series-generator:3.0.1, the image the Docker profile builds; no workflow publishes it.
- A vector topic has one writer. `POST /v1/dags/deploy` reads the control topic first and answers 409 with the new
  error type `conflict` (`<dag>: sink topic <topic> is already written by DAG <other>; stop that DAG first or choose
  another sink`) and writes nothing when another deployed DAG writes one of its vector topics; `opentsx vectors
  deploy` refuses the same case with exit code 1 and the same text, and the vector processor refuses such a DAG with
  the status `failed`. Deploying the same DAG name again is no conflict.
- The SaaS backend answers a registration with an address that is already registered (409) with the type `conflict`
  instead of `bad_request`.
- k3s: the readiness probes of `opentsx-retrieval` and `opentsx-event-bridge` use `GET /v1/ready`; `GET /v1/health`
  stays their liveness probe. The event bridge probe waits 3 s for an answer.
- `deploy/k3s/30-deploy.sh` waits until every Deployment is ready and names each one that is not with `FAIL
  deployment.apps/<name>: not ready after <timeout>; see kubectl -n opentsx describe deployment.apps/<name>`. On a
  broker without the topics of the domain, `opentsx-stats` is not ready until `40-demo.sh kafscale-check` creates
  them; the script then says to run `30-deploy.sh --restart`.
- `deploy/k3s/10-build.sh`, `11-import-images.sh`, `30-deploy.sh`, `50-status.sh` and `71-restore.sh` exit with 2 on a
  usage error (an unknown option, an option without its value, an argument they do not take, a `--builder`, method,
  `--timeout` or `--only` part that is not allowed, no backup directory), with a message in the form `<option>:
  <problem>; <what is allowed>`; `--help` lists the exit codes 0, 1 and 2.
- k3s Jobs: `boat` has a memory limit of 1536Mi and `house` of 4Gi, so that `boat --data` over a year of 15-minute
  rows with `--calibrate` and `house --calibrate` over 10 years of bills fit the heap.
- `deploy/k3s/60-probe.sh`: when a Deployment is not ready, the step `deploy` runs `40-demo.sh kafscale-check` and
  then `30-deploy.sh --restart`.
- CI: the job `k3s scripts and manifests` also runs `bash -n` and `shellcheck` on `bin/episode_*.sh`.
- The public `debug` and `verbose` fields of `ReturnIntervallStatistik2` and `TSPropertyTester` and the public
  `verbose` field of `RISTool` are deprecated and have no effect; the level of the SLF4J logger of each class decides
  which DEBUG lines appear. `BucketAnalyserTool` no longer sets `ReturnIntervallStatistik2.debug`.
- A signal on the clean or features topic of a domain has one writer, like a vector topic: `POST /v1/dags/deploy`
  answers 409 `conflict`, `opentsx vectors deploy` exits with 1 and the vector processor refuses a DAG whose series
  sink writes a signal (system and id) that another deployed DAG writes to the same topic, with `<dag>: sink <topic>
  signal <id> of system <system> is already written by DAG <other>; stop that DAG first or choose another signal`.
- The k3s scripts 10-build.sh, 11-import-images.sh, 50-status.sh and 71-restore.sh write their messages as '<subject>:
  <problem>; <what to do>', for example 'namespace opentsx: not found; deploy it first with 30-deploy.sh', instead of
  using a spaced hyphen as a dash.
- `opentsx-lg/src/main/docker/core-tool-image/README.md` describes the image, its build and its environment variables
  instead of holding a 2020 build log.
- The chart panel operations of the bucket analyser no longer print to standard output when they are recorded:
  MacroTrackerFrame, Track and PFDRecorder log each recorded operation at DEBUG instead of printing `ADDED A NEW
  TRANSFORMATION`, `[OK] ... Node added to tree` and `[PFDRecorder] Added step` lines.
- `KafkaTopics.create` in opentsx-twin-live uses the Admin client that the topic lookups share instead of opening one
  per topic. A create that fails closes that client, and the next create opens a new one. After `close`, every
  create throws `topics: closed; create a new KafkaTopics to create a topic`, also one for a topic it created before.
- The k3s scripts 70-backup.sh, 20-configure.sh and 11-import-images.sh report a missing namespace, an OPENTSX_DOMAIN
  that is not allowed, a missing tool of the import method and a kind cluster that does not exist in the form
  '<subject>: <problem>; <what to do>', for example `namespace opentsx: not found; check OPENTSX_NS in
  deploy/k3s/.env and the kubectl context, or deploy it first with 30-deploy.sh`,
  `OPENTSX_DOMAIN: "Wind" is not allowed; expected lower-case letters, digits, hyphens or underscores, starting with a
  letter or digit, such as wind`, `buildah: not found; install it or choose another method with --method` and `kind
  cluster <name>: not found; list the clusters with kind get clusters`.
- `opentsx-core`: `FFTPhaseRandomizer.nrOfSValues` is deprecated and has no effect.
- CI runs `bash -n` and `shellcheck -x -P SCRIPTDIR` on the `opentsx-lg` shell scripts as well.
  `tools/test_lg_scripts.py` fails on an `opentsx-lg` shell script whose first line is not a shebang and on a `cd` in
  it that is not followed by `|| exit` or `|| return`, and fails when the CI lists miss a tracked `opentsx-lg` shell
  script.
- The statistics processor docs say that a processor that wrote no statistics in the 5 minutes before the broker
  became unreachable may wait up to 60 s for each statistics record instead of the 3-minute delivery timeout, so its
  stop takes about one minute per record. The liveness probe (progress file 600 s old or older, every 60 s, 3 failures
  in a row) restarts it 12 to 13 minutes after the last write of the progress file, before the stop ends only with 12
  or more records.
- The k3s scripts 10-build.sh, 11-import-images.sh, 20-configure.sh, 70-backup.sh and 71-restore.sh write every
  remaining refusal in the form '<subject>: <problem>; <what to do>', for example `OPENTSX_NS: "Open_TSx" is not a
  valid namespace name; expected lower-case letters, digits and hyphens, starting and ending with a letter or digit,
  such as opentsx`, `java: not found; install a JDK 17 or newer, deploy/k3s/00-prereqs.sh checks the build tools` and
  `PVC opentsx-data: not found in namespace opentsx; run 30-deploy.sh first (opentsx-iceberg-catalog needs LAKE=true,
  opentsx-minio LAKE=true and LAKE_MINIO=true)`. 20-configure.sh names the empty key of MODBUS_HOST and MODBUS_MAP and
  of S3_ACCESS_KEY and S3_SECRET_KEY. Exit codes and accepted values are unchanged.
- `opentsx-core`: `FFTPhaseRandomizer.getPhaseRandomizedRow` throws `IllegalArgumentException` with `mode: <mode> is
  not allowed; expected 0 (MODE_shuffle_phase) or 1 (MODE_multiply_phase_with_random_value)` for any other mode. It
  returned null before.
- `opentsx-core`: the `main` methods of `ReturnIntervallStatistik2` and `TSPropertyTester` do not print the minimum of
  each shifted series any more. It is a DEBUG line.
- opentsx-lg: `src/main/connect-cfg/deploy_connector.py` starts with `#!/usr/bin/env python3`.
  `tools/test_lg_python.py` fails on a tracked `opentsx-lg` Python script that does not compile with Python 3 or does
  not start with that line.
- opentsx-lg: the core tool image Dockerfile sets its variables as `ENV key=value` and runs `java -jar
  /opentsx-lg/opentsx-lg-3.0.1.jar` in exec form. `DockerImageTest` fails on the legacy `ENV key value` form, on a
  shell-form `CMD` or `ENTRYPOINT`, and on an image from `k8s.gcr.io`.
- The opentsx-twin-live README says that `twin-live publish` creates each topic before the first record of its run and
  exits with code 1 when that create fails, and that a caller of `KafkaTopics.append` that appends again after a
  failed create tries the create again on a new Admin client.
- `deploy/k3s/71-restore.sh` and `90-teardown.sh` write a declined question on standard error as `error:
  71-restore.sh: aborted at the question; nothing was changed` (or `90-teardown.sh`) instead of `aborted` on standard
  output. The exit code stays 1.
- The warnings of `deploy/k3s/10-build.sh` about `LAKE=true` without `--with-s3` and of `deploy/k3s/70-backup.sh`
  about a missing `.env` use the form '<subject>: <problem>; <what to do>': `--with-s3: not given with LAKE=true;
  windpark-sim and the retrieval server cannot open an s3:// warehouse without S3FileIO, run 10-build.sh --with-s3`
  and `<env file>: not found; the backup holds no configuration, run 70-backup.sh in the checkout that configured the
  installation`.
- `opentsx-core`: `ReturnIntervallStatistik2.setLofFileNameKey_1` is deprecated and has no effect; the class writes no
  log files.
- The MQTT example publishes its test message in `02_run_mqtt_broker.sh` after it starts the broker, instead of in
  `01_bootstrap.sh` before any broker runs.
- The remaining warnings and refusals of the k3s scripts use the form '<subject>: <problem>; <what to do>', with
  unchanged exit codes. In `70-backup.sh`: `LAKE=true without --quiesce: the copy of the Iceberg catalog and MinIO may
  be inconsistent if they were written during the backup; run 70-backup.sh --quiesce for a consistent copy`. In
  `90-teardown.sh`: `--images: k3s not found on this host; run 90-teardown.sh --images on the k3s node, or leave out
  --images`, `<image>: not removed from k3s containerd (<reason>); remove it with sudo k3s ctr images rm
  docker.io/<image> <image>` and `--images: <n> image(s) not removed from k3s containerd; see the warn lines above`.
  In `50-status.sh`: `k3s containerd: no <repo> images; import them with 11-import-images.sh`, `statistics checkpoint:
  <n> s old; the processor may be stuck, see docs/devops/operations.md R2` and `statistics checkpoint: not available
  (the pod does not run or has not written one yet); see kubectl -n <namespace> logs deployment/opentsx-stats`. In
  `lib.sh`: `<deployment>: not scaled back to <replicas>; run kubectl -n <namespace> scale deployment/<deployment>
  --replicas=<replicas>`.
- `docs/datamodel-2026/01-ANALYSE-Datenmodell.md` names `TSOProducer.pushTSDataAsEpisodesToKafka_String_Avro`,
  `pushTSDataAsEventsToKafka_String_Avro`, `OpenTSxClusterLink.send` and `LegacyBridge.seriesKeyFromLabel` instead of
  line numbers of code that moved or is not in the repository. It no longer reports a hard-coded label `"123"` or
  quadratic event timestamps, which the producer does not have.
- `opentsx-core`: the `main` methods of `ReturnIntervallStatistik2` and `TSPropertyTester` shift every series they
  move to a minimum of 0 the same way and log a `shifted series` DEBUG line for each, including the Gaussian series.

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
- `BucketWriter.payloadBytes()`, `BucketWriter.valueCount()`, `TopicLayout.provisionSmartVectors` and
  `EpisodeIndex.addAll`, which had no callers. Use `provisionTopic(admin, layout.smartVectors(name),
  replicationFactor)` for a vector topic.
- `StatsAccumulator.emptyLike` and `QuantileSketch.copy`, which had no callers.
- `TimeSeriesObject.calcMR_Log_for_Y` (a copy of `calcMR_Ln_for_Y`), `MessreihenLoader.checkFileAccess`,
  `TSBucket.flush` (it did nothing), `TSBucket.setSourceDataFolder`, the field `TSBucket.sourcFolder` and the
  constructor `TSBucket(String, String, String)`. The constants `DEFAULT_DELIMITER` and `DEFAULT_LIMIT` replace the
  static fields `MessreihenLoader.delim` and `MessreihenLoader.limit`.
- The `JAVA_OPTS` logger settings of the vector processor manifest and the retrieval-kafscale component. Logger levels
  live in `conf/simplelogger.properties`.
- SaaS frontend: the unused declarations `VITE_WS_BASE_URL`, `VITE_ENVIRONMENT`, `VITE_ENABLE_ANALYTICS` and
  `VITE_ENABLE_DEBUG` and the placeholder test `src/App.test.tsx`, replaced by the auth store tests in
  `src/store/authStore.test.ts`.
- SaaS frontend: `organizationsAPI`, `authAPI.updateProfile`, `flowsAPI.execute` and the default export of
  `src/services/api.ts`; nothing imported them.
- SaaS backend: the stub routes that answered fixed sample data without authentication (organizations, teams,
  invitations, billing, GET and PATCH /api/v1/users/me, GET /api/v1/node-types, GET /api/v1/flows/demo), the shadowed
  stub register and login, and the blocks of the page at / that called them.
- SaaS backend: POST /api/v1/flows/{flow_id}/execute and services/flink_service.py; the route always answered 502
  because no stack starts Flink and the JAR it uploaded does not exist. A flow runs when its DAG is deployed through
  POST /api/v1/dags/deploy.
- The SaaS backend documents no longer describe the removed routes for organizations,
  teams, invitations, node types, billing and subscription plans, `GET /api/v1/flows/demo` and `POST
  /api/v1/flows/{id}/execute`.
- The unused file readers `BillCalibration.read(Path)`, `BillCalibration.dailyTemperatures(Path, String, double)` and
  `BoatData.load(Path, TwinPlan, ZoneId)`; the twin commands read these files themselves and name the file in every
  refusal.
- `SerialRtuTransport`, a placeholder that ignored its parameters and was used only by a test. Serial Modbus RTU is
  still not supported; use a converter with `rtu-over-tcp` or Modbus TCP.
- `MosmixRawArchive.DEFAULT_STATIONS`, which nothing used; the stations come from `opentsx.market.mosmix.stations`.
- Unused members of opentsx-retrieval: the six-argument `VectorJob` constructor, `FixedDurationDetector.windowEnd`,
  `PatternSearch.queryId()`, `EpisodeService.similarEpisodes(Episode)` and the series ids of the sinks in
  `VectorProcessor`. `VectorAligner` and `VectorStream` share one cell aggregate.
- The answer of `POST /v1/dags/deploy` no longer has the field `skipped`, which was always empty, and `DagPlan` loses
  its `skipped` component.
- SaaS backend: the login no longer answers a refresh_token, which no route accepted, and expires_in follows
  ACCESS_TOKEN_EXPIRE_MINUTES; the frontend stores only access_token.
- SaaS backend: GET /api/v1/flows/{flow_id}/executions, which nothing filled since the execute route was removed; the
  flow_executions table stays.
- SaaS backend: the unused Redis, SMTP and Stripe settings, ENABLE_INVITATIONS, ENABLE_PAYMENTS, the sync database
  engine, unused schemas and token helpers, and the packages redis, hiredis, python-socketio, prometheus-client,
  emails, alembic, numpy, pandas, python-multipart and psycopg2-binary.
- The backend in `docker-compose.local.yml` no longer gets `KAFKA_BOOTSTRAP_SERVERS`, `OPENTSDB_HOST` and
  `OPENTSDB_PORT`, which it does not read, and no longer waits for a healthy `cp-all-in-one` before it starts.
- The unused IcebergSeriesStore.micros helper.
- Breaking for code that calls them: the constructor `Http(long, int)` and `Collector.online(long)` of opentsx-market.
  Use `Http(long, int, ProxySelector)` and `Collector.online(long, Map, ProxySelector)` with the credentials and the
  proxy from `Http.proxy`, which follows `opentsx.https.proxy`.
- Python: `TSProcessor`, `ProcessorChain` and `ProcessorConfig` (`opentsx.core.processor`), an abstract base without
  an implementation. `from opentsx import TSProcessor` raises `ImportError`. This is a breaking change.
- Python: the `parallel` argument of `TSBucket.apply`, which ran the same sequential code; passing it raises
  `TypeError`. This is a breaking change.
- Python: `risk_interpretation` from the result of `RIS.analyze`; its bands 0.8 and 1.2 had no source. Read
  `risk_parameter`. This is a breaking change.
- Python package: the extras `ml`, `storage` and `performance` and the dev dependencies `pytest-asyncio` and `sphinx`,
  which no code used.
- `opentsx-saas-backend/local-test.sh`, which reported success after failed checks. `smoke-flows.sh` checks a running
  backend and exits 1 on a failed check.
- `bin/120_run_demo.sh`, which started the same GUI as `bin/000_launch_tsa_workbench.sh`. The menus of `010_build.sh`
  and `000_launch_tsa_workbench.sh` point to `bin/episode_02_create_timeseries.sh` and the user manual.
- `Normalizer.zone()` of `opentsx-windpark`, which had no caller. The normalizer picks REGULAR or IRREGULAR_DELTA
  through `Episodes.of`.
- SaaS frontend: the dependencies `@tanstack/react-query`, `plotly.js`, `react-plotly.js` and `socket.io-client`,
  which no code imported, the unused `@/` path alias and Tailwind `primary` palette, the npm scripts `preview:test`
  and `test:coverage` (its coverage provider was never installed), and the favicon link to the missing `/vite.svg`.
- `opentsx-core`: 105 classes that no code, script, launcher or document used, with their NetBeans `.form` files: in
  `org.opentsx`, `algorithms.analysis` (`SingleTSToolPeakDetector`, `SingleTSToolSum`),
  `algorithms.dataqualitytest.InfluenceOfSinglePeakTester`, `algorithms.detrending` (`DFATester`, `MultiDFA4Buckets`,
  `MultiDFATool3`, `MultiDFATool4`, `methods.FitTool`), `algorithms.eventsynchronisation.experiments`
  (`ESMainCreateSketchData73`, `ESPropertiesChart1Tool`, `ESPropertiesChart1Tool1b`, `ESPropertiesChart2Tool`),
  `algorithms.ris.experimental.agh` (`RISAnalyse`, `RISAnalyse3`, `ReturnIntervallStatistik`, `StatistikTest`),
  `algorithms.statistics.experiments.PeaksAndShapiroWilkTest`, `algorithms.statistics.logbinning`
  (`LogBinnedLoaderTest`, `LogBinningToolv2`), `algorithms.univariate` (`SingleTsIntervallCutDetrendetTool2`,
  `SingleTsIntervallCutTool`, `SingleTsMFDFATool`, `SingleTsPeakEntropyTool`, `SingleTsTrendCalculatorTool`,
  `TSCutTool4Buckets`, `TSCutToolDetrendet4Buckets`, `TSGlobalTrendTool4Buckets2`), `analysistools` (`HZDouble`,
  `LogBinningTool`, `MultiSeriesSurface`), `app.agentbasedsimulation.analyser` (`SimResultLoader`,
  `TestResultAvailability`), `app.bucketanalyser.MacroRecorder3`, `app.gui` (`Histogramm2D`, `ScreenUtils`,
  `SixTileChartFrame`), `chart.dynamic` (`DynamicSingleSeriesPlot`, `FloatLineProcessor`, `LineProcessor`,
  `NetCatSingleValueCollector`), `chart.gnuplot.GnuPlotChart`, `chart.panels` (`ChartPanel3`, `MessreihePanel`,
  `MessreiheWindow3Frame`), `chart.simple` (`FileControlerPanel`, `MultiChartDistComp`, `MultiChartManager`,
  `MultiChartTwoAxis`, `MultiPointsChart`), `chart.statistic` (`HistogramChart`, `MyHistogramm2D`),
  `data.exporter.SemanpixProject`, `data.generator.SampleDataGenerator`, `data.loader` (`DataFileTool`,
  `LoadCorpusData`, `LoadImageAsTimeSeriesData`, `LoadStockIndexDataFromGoogleTrendsCSVFiles`,
  `LoadStockIndexDataFromYahoo`, `MSExcelMessreihenLoader`, `TSScanner`, `util.IsNotZeroLineSelector`,
  `util.WochengangFilter`), `data.series.MessIntervall`, `demo` (`Demo1`, `Demo_01_OriginProject`,
  `ExampleBucketCreator`, `ExampleBucketLoader`, `ExampleBucketViewer`, `MessreiheTestApp`, `SampleBucketViewer`,
  `brainwave.BrainSignalCorrelator`, `brainwave.ModelTesterCortanaDBS`), `dsp` (`DSProjectHelper`, `ProjectContext`),
  `experimental` (`DFABucketTool`, `EventGenerationExperiment`, `LongTermCorrelationSeries2`, `SimpleBucketTool`,
  `TSInspection`), `math` (`FunctionFitTest`, `PolyFit`), `pojo.ContEquidistTS`, `report.HTMLReport`, `tsa.dfa`
  (`DFAExperiments`, `DFAModule`, `DFAModuleHTSA1`), `tsa.rng` (`RNGExperiments`, `RNGExperiments2`,
  `RNGExperiments3`, `RNGModule`, `RNGModuleACMR`, `RNGModuleACMS`, `RNGModuleJUR`, `RNGReferenceDSCreator`,
  `RNGRefernceDSLoader`), `tsbucket.BucketCreator2` (it read and wrote under a personal absolute path),
  `tsbucket.folderStore.TSBucketStoreFolderLocations`, `tsbucket.generator` (`TSBucketCreator_FFMLRC`,
  `TSBucketCreator_GrayImageColor`, `TSBucketCreator_Sinus`, `TSBucketCreator_TFIDFRecursive`,
  `TSBucketCreator_Uncorrelated`, `TSBucketCreator_WordLength`, `TSBucketCreator_WordLengthRecursive`) and
  `tsbucket.metadata.annotatedvectors.TestPropertiesVector`. This is a breaking change.
- `opentsx-core`: the package `stdlib` (21 classes, among them `StdRandom`, `StdStats`, `StdOut`, `In`, `Out` and
  `Draw`), copied from Sedgewick and Wayne's Introduction to Programming in Java without a license notice.
  `RNGWrapper` draws from `java.util.Random` instead; call `RNGWrapper.init(seed)` where
  `stdlib.StdRandom.initRandomGen(seed)` was called. This is a breaking change.
- `opentsx-core`: the interface `ITimeSeriesObject`, which no code used as a type, the package-private interface
  `TSProcessor`, whose only implementer was `AbstractTSProcessor`, and the methods `getRunID`, `storeResultset` and
  `initMetaDataStore` of `AbstractTSProcessor`, which did nothing. This is a breaking change.
- `opentsx-core`: `TimeSeriesObject.getTopFrequencies` (it sorted the values of the series in place),
  `TimeSeriesObject.get_Y_StatisticsData_Line` (it passed a String to `DecimalFormat.format`, so every line ended
  after `avg_Y` with `!!! NO STATISTICS for time series !!!`) and `TimeSeriesObject.getStatisticData()` (on any error
  it returned zeros for the values it had not computed). This is a breaking change.
- `opentsx-core`: `TSBucket.createBucketFromLocalFilesInDirectory`. Its file reader never stored the values it read,
  so the bucket held one value 0 per file. This is a breaking change.
- `opentsx-core`: `DetrendingMethodFactory2`. Call `DetrendingMethodFactory.getDFAmulti(order)` where
  `DetrendingMethodFactory2.getDetrendingMethod(int)` was called; it creates the same `DFAmulti`. Call
  `DetrendingMethodFactory.getDetrendingMethod(String)` where `DetrendingMethodFactory2.getDetrendingMethod(String)`
  was called. This is a breaking change.
- `opentsx-core`: 1939 lines of commented-out code, 30 comment lines that belonged only to it, and the empty `if`,
  `else` and `try` blocks it left. No behavior changes.
- `opentsx-data`: the unused records `Episode`, `Episodes`, `EpisodesNEW`, `EpisodeMetadata` and `EventSeries` and the
  schemas `episode2.avsc`, `episode3.avsc`, `EpisodeMetadata.avsc` and `EventSeries.avsc`. This is a breaking change.
- `org.opentsx.connectors.kafka.kping.TSOEventAnalysisProducer`, a copy of `org.opentsx.connectors.kafka.TSOProducer`
  that still sent the label "123", the URI "URI" and growing time gaps. The `opentsx-lg` ping producer
  `EventFlowAnalysisProducer` now sends through `TSOProducer`. This is a breaking change.
- The bundled GhostAWT 0.0.3 copy under `opentsx-lg/src/main/docker-compose/bin` (61 third-party files with native
  libraries) and the commented launch lines that named it. The latency consumer runs headless.
- The tracked latency measurement file under `opentsx-lg/src/main/docker-compose/output/`. The directory is
  git-ignored.
- Commented-out code in `TSOProducer` (`createProducer(Properties)`, `pushTSDataAsEventsToKafka_String_String`,
  `pushTSOItemsToKafka`), `TSOWriter`, `TSOEventProducer` and `EventFlowStateProducer`, and in the `opentsx-lg`
  sources and scripts.
- `org.opentsx.pojo.EventTS`, which no code used and whose `createRecord()` returned null. This is a breaking change.
- `opentsx.log.level=INFO` from `config/opentsx-default.properties` and the `opentsx-default.properties` resource of
  `opentsx-core`. No code read it; `ProductionConfig` reads the level from `./opentsx.properties` and
  `OPENTSX_LOG_LEVEL`.
- `MultiDFATool2.debug`, which nothing read after its lines moved to the SLF4J logger of the class at DEBUG. Set the
  logger level instead. This is a breaking change.
- opentsx-lg: the tracked latency measurement output under `opentsx-lg/opentsx/plots/`. The directory is git-ignored
  and `tools/check_tracked_config.py` fails on a tracked file in it.
- opentsx-lg: the dependencies `log4j-api` and `log4j-core` and `src/main/resources/log4j2.xml`. The tools log through
  SLF4J.
- The SaaS pages `opentsx-saas-backend/docs/` (release summary, user guide, API documentation, platform guide, flow
  builder design) and `opentsx-saas-frontend/HOW_TO_RUN.md`. They described a hosted service, routes, plans, settings
  and an example administrator password that the backend does not have. The backend README points to
  `/api/v1/openapi.json` and `/docs` for the routes.
- `docs/DOCUMENTATION-INVENTORY.md`, which listed LICENSE, CONTRIBUTING, CODE_OF_CONDUCT and CHANGELOG as missing, the
  briefing `EVOLUTION/TASK-012-lokaler-agent.md`, and the metrics and achievements sections of `EVOLUTION/README.md`,
  whose figures had no source.
- The `.dockerignore` of the SaaS backend no longer lists the deleted `docs` directory.
- The opentsx-market README no longer carries the dated status of a private archive and build environment.
- Python package: the `kafka` extra no longer repeats `fastavro`, a core dependency; the README no longer claims a
  Kafka producer or a documentation site, and `pyproject.toml` links the repository and user manual chapter 10.
- SaaS frontend: the route `/flow-builder/:flowId` and the Flow Composer link "Open in the flow builder". The route
  never read the id: it showed three demo nodes, and its save created a new flow. `/flow-builder` creates a new flow
  as before, and any other path opens `/dashboard`.
- SaaS frontend: the npm script `format`, which ran Prettier over the whole `src/` tree.
- SaaS frontend: the unused devDependency `prettier`.
- `config/opentsx-default.properties` and the packaged `opentsx-default.properties` no longer list
  `opentsx.kafka.topic.map.file`, `opentsx.kafka.client.config.file`, `opentsx.data.directory`,
  `opentsx.threads.pool.size` and `opentsx.retry.attempts`; no `ConfigManager` caller read them.
- The opentsx-lg jar no longer carries the unused localhost profiles `config/cpl.props` and `config/mdc_c1.props`, and
  `opentsx-lg/run_ddc.sh`, which ran docker-compose in a directory without a compose file, is deleted.
- `opentsx-core`: `FFTPhaseRandomizer.main`, which opened an empty F(s) chart titled with order -1 and an empty alpha
  versus beta chart and wrote tables of empty series to `./TESTDATA_PhaseRandom`; surrogates of white noise have no
  beta to chart. This is a breaking change.
- `opentsx-core`: `TSBucket.getRecordID(String)` and `TSBucket.resetCounter()`, the static record counter that only
  `loadFromSequenceFile` used. This is a breaking change.
- `opentsx-lg/bin/bootstrap_LatencyProbe_CHECK.sh` is deleted. It held the ids of one real Confluent Cloud
  environment, cluster and service account, and it needed the `ccloud` CLI, which Confluent retired and which can no
  longer log in. The opentsx-lg README lists the ACLs the latency tools need (CREATE, READ, WRITE, ALTER and DELETE on
  the topics with the prefix `OpenTSx_`, READ on the consumer group `KPING-Consumer`) for you to create with the tools
  of your Kafka platform.
- opentsx-lg: the MQTT example scripts `04_run_tsdb.sh` and `influxdb-grafana/run_container_02.sh`, and the gitlink
  `influxdb-grafana/docker-compose-influxdb-grafana`. The gitlink had no `.gitmodules` entry, so a clone got an empty
  directory and the scripts ran `docker-compose up -d` without a compose file.
  `influxdb-grafana/__run_container_01.sh` starts InfluxDB and Grafana.
- `opentsx-core`: the dead EXP3 and EXP4 branches and the private `loadStockDataFromBucket` of `MacroRecorder`, the
  unused shuffled component list of `EntropieTest.main` and the unused `cutOff` field of `TSPropertyTester`, whose
  `filtered intervals` DEBUG line no longer carries a `cutOff` value.

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
  `getInt` stops on a value that is not a whole number instead of returning the default. When `PFDRecorder` is the
  first class that reads the configuration, every call of `PFDRecorder.getInstance()` throws `IllegalStateException`
  with the message of the configuration.
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
  token and that the `admin@localhost`
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
- `Episodes.validate` refuses a REGULAR episode without a positive `intervalMicros`, an IRREGULAR_DELTA episode
  without `timeDeltas` and timestamps that do not increase with an `IllegalArgumentException` that names the field.
  The statistics processor, the Flink job `StatsJob` and `EpisodeIndexer.rebuild` count such episodes in `invalid`
  instead of stopping on a `NullPointerException` or computing statistics from them.
- `TopicLayout.provision`, `provisionDagTopics` and `provisionTopic` and the manifest send of `BucketWriter` keep the
  interrupt flag of the thread when they are interrupted, and they catch only interrupts, execution failures and
  timeouts. The three provisioning methods share one implementation.
- `FileEpisodeIndex` errors name the file, and a malformed line also names its line number, for example `<file>:2:
  malformed index line "..."; expected 8 tab-separated fields, see the header line`. A non-numeric field is reported
  the same way and no longer as a bare `NumberFormatException`.
- The statistics processor (`bin/opentsx stats`) checks every decoded episode with `Episodes.validate`. An episode
  whose count differs from its values or whose `seriesId` does not match its key no longer produces statistics. It is
  counted in `invalid` and skipped, and the first one per partition is printed on stderr with topic, partition, offset
  and reason.
- Records that retention deletes while the statistics processor runs are counted in `lostToRetention` and reported
  with the same stderr line as at the start of a run.
- An unreadable or unwritable statistics checkpoint fails with `cannot read checkpoint <file>` or `cannot write
  checkpoint <file>`, with the I/O error as cause, instead of a bare `java.io.EOFException`.
- A `--once` run of the statistics processor ends when retention deleted records up to and past the end offset it saw
  at the start and the next record it reads on that partition lies at or past that offset. It counts the deleted
  records in `lostToRetention` instead of waiting until its deadline and failing with `timed out computing
  statistics`.
- `TimeSeriesObject.setDecimalFomrmatX` and `setDecimalFomrmatY` change only the series they are called on. Before,
  they changed the static default and with it the format of every new series, including from Flink task threads.
- `TimeSeriesObject.calcLog10_for_Y`, `calcLn_for_Y` and `calcMR_Ln_for_Y` give NaN for a value of 0 or below. Before,
  they gave 0 or the raw value, or dropped the point. The desktop action for the natural logarithm is labelled `ln`
  and records the prefix `LN_`.
- `TimeSeriesObject.split(length, count)` pads every row to `length` values; a later row no longer gets the missing
  values of the rows before it.
- `TimeSeriesObject.calcAveragOfRows` divides each point by the number of rows that have a value there, so rows [1, 1]
  and [3, 3] give [2, 2] instead of [4, 2].
- `TimeSeriesObject.getX_for_Y2(y, epsilon)` returns the x value of the first point within `epsilon` of `y`. Before,
  it returned NaN for every input.
- `MessreihenLoader` takes the delimiter and the row limit per call, so one call no longer changes them for later
  calls in the JVM. `debug` is off by default. `BucketLoader.setLimit` changes only its own loader and no longer
  `TSBucket.default_LIMIT`.
- Every `MessreihenLoader` method stops with `UncheckedIOException` that names a missing or unreadable file. It stops
  with `IllegalArgumentException` that names the file and the line of a value, a column count, a label, a date or a
  mapper call it cannot read, and `_loadLogBinnedMessreihe` names a file without data lines. Before, nine methods
  logged a parse error and returned partial data. For a missing file they returned null or failed with a
  NullPointerException, and a file without data lines failed with a division by zero. `loadMessreihe`,
  `loadMessreihe_1` and `_loadLogBinnedMessreihe` now read files of any name; before, they returned null for a name
  without `.txt` or `.dat`.
- `TSBucket` throws `IOException` that names the default file system when that file system cannot be opened. Before,
  it wrote a CSV file in its place. `putMessreihe`, `_open` and `close` throw instead of logging.
- `RISTool` opens no window and prints no value during the computation, and it stops with English
  `IllegalStateException` and `IllegalArgumentException` messages. `add` rejects a NaN or infinite probability with a
  message that names its index, before it writes a log line or changes the container.
- `LongTermCorrelationSeriesGenerator` draws its noise from a `java.util.Random` and needs no `RNGWrapper.init()`. It
  filters beta 0 like every other beta and returns a series with variance 1. `main` writes its tables into the
  directory named by its first argument, `./data/` by default, instead of a personal thesis directory. The second
  argument is the seed, 1 by default.
- The opentsx-tools image no longer carries JUnit jars. `deploy/docker/stage.sh` passed a scope property that the
  Maven dependency plugin ignores, so it staged the test classpath; it now stages the runtime classpath. `bin/opentsx`
  also resolves the runtime classpath, and `stats-flink` adds the provided scope for Flink.
- The image commands and the `bin/opentsx` commands log through slf4j-simple, configured by
  `conf/simplelogger.properties`, to stderr with ISO time. Kafka, Iceberg, Hadoop, Parquet and the AWS SDK log at
  warn, so the Kafka client no longer prints its INFO configuration dumps. `stats-flink` logs through log4j2, and
  `config` and `version` have no logger. The image classpaths of `twin`, `house`, `boat`, `studio-export`, `dwd` and
  `modbus` had no SLF4J provider and now carry slf4j-simple.
- `bin/opentsx stats-flink` no longer logs to the SLF4J no-operation logger. `opentsx-flink-core` binds SLF4J 2
  through `log4j-slf4j2-impl` and reads `conf/log4j2-stats-flink.properties`. The shaded jar no longer contains the
  log4j 1 `log4j.properties`.
- `bin/opentsx` and the image entrypoint exit with 1 before Java starts when the classpath step fails. `bin/opentsx`
  prints `resolving the classpath of <module> with Maven`, shows Maven's error output, and reports a missing `mvn`.
- `bin/000_launch_tsa_workbench.sh` without Maven takes the classpath from `bin/opentsx classpath opentsx-core` and
  exits with 1 before Java starts when that fails, instead of a wildcard classpath that cannot load the workbench.
- The retrieval server answers a POST body that is not valid JSON with 400 `the body is not valid JSON: <parser
  message>`, and a body that is not a JSON object with 400. Before, it closed the connection without an answer and
  curl reported `Empty reply from server`.
- A fault in the retrieval server answers 500 `{"error":"internal error","type":"internal"}` and logs one ERROR line
  `request failed method=<method> path=<path> status=500 cause=<causes>`, with the stack trace at DEBUG. The answer no
  longer names Java exception classes.
- An unknown episode answers 404 on `POST /v1/episodes/similar` and `POST /v1/patterns/search`, as on `GET
  /v1/episodes/{id}`; before it was a 400. An episode id that holds `/` answers 400.
- Malformed parameters of the retrieval server answer 400 naming the parameter and what it takes, instead of raw JDK
  text. This covers `limit`, `topK`, `agg`, `clip`, `from`, `to`, `resample`, `distance`, `strategy`,
  `params.feature`, `params.direction` and the segment durations, for example `limit: "abc" is not an int; expected a
  whole number from 1 to 20000`. A `to` or `from` given alone names the default it was compared with. A search without
  `pattern` and `episodeId` names `pattern`. Unknown values of `clip` and `distance` are refused instead of ignored.
- The event bridge answers a method other than GET on its two routes with 405 and `Allow: GET`. Every other path
  answers a JSON 404 `no such endpoint`, also outside `/v1/`; `GET /v1/topics/events` answered 500 before. A broker
  failure answers 502 `cannot reach the broker` without the broker address, and the bridge logs the failure once at
  ERROR with its causes.
- SaaS frontend: Logout removes both tokens from the browser also when `POST /api/v1/auth/logout` fails or the backend
  does not answer, so a reload no longer signs the user in again. The login page then says `signed out here; the
  server could not be told: <reason>`.
- SaaS frontend: only a 401 from `GET /api/v1/auth/me` ends the session. Any other failure keeps the user signed in,
  and the Flow Composer, the flow builder (`/flow-builder`) and the systems page say `your account could not be read
  (<reason>); reload the page to try again` until the account is read.
- SaaS frontend: the login page shows `the sign-in mode could not be read from the backend (<reason>); reload the page
  to try again` when `GET /api/v1/auth/mode` fails instead of dropping the error.
- SaaS frontend: a production build without `VITE_API_BASE_URL` calls the backend at the page origin;
  `http://localhost:8000` applies only under `npm run dev`. `.env` is no longer tracked and is git-ignored;
  `.env.example` holds an empty value. The retrieval path `/retrieval` is one constant `RETRIEVAL_BASE`.
- The Python `RetrievalClient` raises `RetrievalError` for every failed request: a refused connection or a timeout
  (`status` is `None`), an error page that is not JSON, and an answer that is not JSON, not the expected JSON object
  or array, or a truncated episode stream. It keeps the cause.
- `RetrievalClient.episode()` returns `None` only when the server answers 404 with the type `not_found`. Any other 404
  and an answer without exactly one episode raise `RetrievalError`.
- `RetrievalClient` checks its arguments before it sends a request: a time must be ISO-8601 with a zone and goes out
  in UTC, on Python 3.10 as on later versions; `timeout` must be a finite number of seconds above 0; an episode,
  system or run id must be non-empty and hold no `/`; `series` must be a list of series ids, not a string. Each
  mistake raises `ValueError` naming the argument.
- `opentsx.client.retrieval.decode_stream` raises `ValueError("truncated episode stream at byte N; ...")` for a cut
  stream instead of `struct.error`.
- `TopicReader.episodes()` names topic, partition and offset of a record it cannot decode, a tombstone included, keeps
  the broker's text for a metadata error other than an unknown topic, rejects a `max_records` below 1 or NaN and
  counts records of another v2 type in `TopicReader.skipped`.
- `QuantileSketch.from_bytes` raises `ValueError` for truncated input and for trailing bytes, `opentsx.model.schema`,
  `fingerprint` and `encode` list the known schemas for an unknown name, and `opentsx.model.decode` raises
  `ValueError` for an unreadable Avro body.
- `opentsx.model` builds its schema cache once, so two threads that encode or decode on first use both succeed.
- The Python `RetrievalClient` refuses a time finer than a microsecond instead of cutting it. With pandas 2, it names
  the range 1677-09-21T00:12:44Z to 2262-04-11T23:47:16Z for a time outside it.
- SaaS frontend: every page shows the reason a server sends. `errorMessage` in `src/services/api.ts` reads `error` of
  the retrieval server, the event bridge and nginx and `detail` of the backend, a field list as `<field>: <msg>`, and
  otherwise gives `HTTP <status> from <url>` or `the backend at <url> cannot be reached`; `fetchJson` does the same
  for fetch requests. It replaces four local copies. Before, a field list of a refused registration crashed the page
  with `Objects are not valid as a React child`, sign-in errors without a `detail` read `Login failed`, and fetch
  requests showed `HTTP <status>` without the reason.
- SaaS frontend: a page that fails to render shows `This page failed: <message>` and a Reload button instead of an
  empty page.
- Workbench: "Load episodes" and the TS inventory show the reason of the retrieval server without the guesses `Is the
  retrieval server running?` and `The window may hold no data.`
- SaaS frontend: a working copy, the decisions, the plan with the working copy, the runs of the twin, the other
  signals of a system, the deploy permission and the flow status after Stop that cannot be read are named on the page,
  for example `your working copy could not be read (<reason>); showing the system document.` Before, they became empty
  values without a message, and "Show the reference" with an unreachable twin was an unhandled rejection.
- Flow Composer: Start, Stop and Delete stay off while `GET /v1/dags` of the retrieval server cannot be read, with
  `Start, Stop and Delete stay off: <reason>.`, and the header says `how many run is not known` instead of `0
  running`; the guide of the Systems page then shows the Run step without a running count. Before, an unreadable list
  counted as empty: Stop said `was not running` and Delete removed a deployed flow.
- Workbench: Deploy, Start and "Publish and start" deploy through one `deployFlow` in `src/workbench/deployments.ts`,
  and `the deployments could not be read from <url> (<reason>); try again` carries the reason the retrieval server
  sends, for example `this server has no DAGs (it needs --kafscale)` instead of `HTTP 404`.
- SaaS frontend: the system id is URL-encoded in the working-copy requests `/api/v1/systems/<id>/bindings` and
  `/api/v1/systems/<id>/generate`.
- SaaS frontend image: nginx answers a 502 for an unreachable backend or retrieval server with `{"error": "the backend
  is not reachable", "type": "unavailable"}` or `{"error": "the retrieval server is not reachable", "type":
  "unavailable"}`, and a 504 for one that does not answer in time with `{"error": "the backend did not answer in
  time", "type": "unavailable"}` or `{"error": "the retrieval server did not answer in time", "type": "unavailable"}`,
  instead of its HTML page.
- A `--once` run of `bin/opentsx stats` now ends when retention deleted every record up to the end offset it saw at
  the start, also while another partition keeps receiving records. It counts the deleted records in `lostToRetention`
  and no longer fails with `timed out computing statistics`. The log start check stays within the deadline of the run.
- `QuantileSketch.fromBytes` refuses trailing bytes and truncated input with an `IllegalArgumentException` (`sketch
  bytes: <n> trailing bytes; expected exactly <m>`, `sketch bytes: truncated at <k> of <m>`). Before, it accepted
  trailing bytes and threw `BufferUnderflowException` on truncated input.
- `windpark-sim --ground-truth` with a directory or a file in a directory that does not exist exits with 2 before it
  opens a client; it wrote every sink, sealed the bucket and then failed with 1.
- A `CURTAILMENT` without `limitKw` no longer curtails to 0 kW: loading the scenario fails with `<file>: CURTAILMENT
  operation at PT1H: limitKw missing; set limitKw to the power limit in kW, 0 or more`. `DRIFT` needs `ratePerHour`,
  `OFFSET` needs `magnitude`, `SPIKES` needs `magnitude` and `probability`, operations and sensor faults need `offset`
  and a positive `duration`, a parameter of another type is refused, and `park.rows` or `park.columns` below 1 is
  refused instead of becoming 1.
- A scenario with a negative or non-finite `park.spacingRotorDiameters`, `park.rowSpacingRotorDiameters` or
  `park.wakeDecay` is refused instead of taking the default; 0 or a missing value still takes 5, 7 and 0.05.
- A scenario whose `duration`, `sampleInterval` or `episodeDuration` is zero or negative is refused with `<name>
  <value> is not positive`; a negative `duration` ran 0 episodes with exit 0, a negative `sampleInterval` failed with
  `NegativeArraySizeException` and a zero one with `/ by zero`.
- `WindparkSimulator` refuses an operation or sensor fault on a turbine that is not in the park and a sensor-fault
  channel that is not a metric of the catalog when it is built; an unknown operation turbine was ignored before.
- The normalizer and the simulator take the quality codes `MISSING` and `IMPLAUSIBLE` from the catalog instead of the
  literals 2 and 1 and refuse a catalog without exactly one code from 1 to 255 for each.
  `Normalizer.QUALITY_IMPLAUSIBLE` and `Normalizer.QUALITY_MISSING` are removed; `ChannelCatalog.qualityCode(name)`
  returns the code.
- The state seconds of the simulator sum to the simulated span at every sample interval; at `PT0.2S` they summed to 0
  and at `PT1.5S` to 4/3 of the span.
- `PowerCurveAnalysis` skips infinite values like NaN, so one infinite wind sample no longer turns the mean wind of
  its bin into NaN.
- `Scenario.load` and `ChannelCatalog.load` report a missing file as `<file>: no such file; give an existing file` and
  a rule violation with the file name, so `windpark-sim` exits with 2 for them.
- `bin/opentsx metadata` checks `--om`: an empty value or a URL without `http` or `https` exits with code 2 and names
  the key, instead of a stack trace with `URI with undefined scheme`. Setting both `--om` and `--dry-run` exits with
  2.
- `--profile-window` and `--dq-window` select the statistics windows by duration, so `PT60M` or `60m` publishes the
  hourly profiles instead of reporting 0 profiles. A zero window exits with code 2. A statistics record whose window
  type is neither GLOBAL nor a duration fails the run with its series id. `WindparkMetadataPublisher.publishProfiles`
  and `publishDataQuality` take a `java.time.Duration`.
- The OpenMetadata client `HttpOmApi` retries I/O errors with the same backoff as the statuses 429, 502, 503 and 504.
  It waits the seconds of a `Retry-After` header, at most 30 s per wait. After the last attempt it fails with
  `<method> <path>: no answer after <n> attempts` and keeps the cause.
- SaaS backend: a token whose account no longer exists answers 401 instead of 404, so the workbench ends the session.
- SaaS backend: an answer of the retrieval server that is 200 but not a JSON object (for example an HTML sign-in page)
  answers 502 `retrieval server: gave an answer the backend cannot read; check that RETRIEVAL_URL names the retrieval
  server` instead of counting as success.
- `retrieval-server --systems <dir>` exits with code 2 and names the directory when it holds no `*.system.yaml` file,
  instead of serving no systems, and when it does not exist or is not a directory, instead of a stack trace with exit
  code 1.
- `VectorProcessor.dagName` reports `the DAG is not valid YAML: <parser message>` and keeps the parser exception as
  the cause.
- `twin-coupling --scenario nosuch` exits with 2 and lists `h2-chain`, `haus` and `boot` instead of printing an empty
  table with exit 0.
- `studio-export --help` and `SimulatedLogs --help` print the usage instead of writing a file named `--help`;
  `SimulatedLogs` refuses a wrong argument or seed with exit 2.
- `house` refuses a plan without the house atoms before it prints or reads anything, naming the plan file and the
  missing atom types; it reported the temperatures file as the fault or failed after the title with exit 1.
- A twin plan with fewer than 1 step, a bills file without rows, and a boat data row with a time that cannot be parsed
  are refused with exit 2 and a line that names the file; they failed with `Illegal Capacity: -5`, `no bills` or `Text
  'x' could not be parsed` and exit 1.
- A daily temperatures CSV with a row that has no temperature or a bad date names the line, for example `line 2: no
  temperature; expected datum,t_mittel_c`, instead of `Index 1 out of bounds for length 1`.
- The house and the boat calibration share one linear solver, `org.opentsx.twin.Gauss`, which returns NaN for a
  singular system; the boat calibration skipped a singular pivot and returned a number.
- A register map that cannot be read reports `cannot read register map <file>: NoSuchFileException` instead of naming
  the path twice.
- `twin-live demo` checks `--scenario`, `--duration`, `--port` and `--max-streams` before the bridge starts, so
  `--scenario nosuch` exits with code 2 instead of hanging with the bridge open, and `--duration 0` is refused instead
  of publishing in a tight loop.
- `twin-live publish --loop false` publishes the runs once; only the presence of `--loop` counted before, so it
  looped.
- `TopicLayout` names and creates the event topic `twin.<id>.<run>.events` (one partition, zstd, delete policy). When
  the topic cannot be created, the next record tries again; before, the topic was marked as known and never created.
- An interrupted topic create or topic lookup in `twin-live` keeps the interrupt flag of the thread.
- An event bridge stream waits out reads that KafScale refuses, for up to 10 minutes, instead of closing at the first
  refused fetch.
- `TimeAxis.decodeDeltas` refuses a varint whose tenth byte carries more than 64 bits. Before, the extra bits were
  dropped and the episode decoded to a wrong timestamp.
- `OpenTsxAvro.decode` throws `IllegalArgumentException` that names the schema, the framing and the payload size when
  a payload is cut or malformed, and keeps the cause. Before, a cut single-object payload gave `AvroRuntimeException:
  Decoding datum failed` and a cut Confluent payload `UncheckedIOException: java.io.EOFException`.
- `EpisodeOps.clip` derives the id of a clipped part from the original id and the bounds of the part and keeps the
  original `createdAt`, so the same clipped read of an episode store returns equal episodes. Before, every read minted
  a random id and the current time.
- `SeriesStoreContract` requires a read with `limit(2)` to return exactly the first two episodes in read order.
  Before, it checked only `size() <= 2`, so an adapter that returned nothing passed.
- `market report --requality false`, `market backfill --force false`, `market publish --dry-run false` and `house
  --history <dir> --levers false` switch the option off; before, the flag alone switched it on, so `--requality false`
  rewrote every manifest.
- `twin --history <dir> --wind` takes only `smard` or `dwd`; `--wind dwdd` exits with 2 instead of running on SMARD.
- Malformed dates, days, numbers and time zones and missing input files of `market` and the history twins exit with 2
  and one line that names the option or the missing file, for example `opentsx: --from: "2024-13-01" is not a date;
  ...`, instead of a stack trace with exit 1.
- The Jobs and CronJobs of `deploy/k3s/history` set `enableServiceLinks: false`, so `OPENTSX_RETRIEVAL_PORT` of the
  Service `opentsx-retrieval` no longer stops the configuration loader.
- The help of `bin/opentsx market <command>` and `history-twin twin|house|boat` no longer says that the command parses
  its own flags. `Registry.LOADS_CONF` and the Python `CONF_COMMANDS` list them, and appendix D and the defaults
  templates are regenerated.
- Chapter 18 no longer names `bin/opentsx kafscale-check`, which the launcher does not have, and no longer says that a
  mode key waits for a command to be migrated.
- Retrieval server: when two system documents declare one system id, later systems keep their own files. The first
  document in file-name order keeps the id and lists `duplicate system id <id>: <file> declares it too` among its
  problems, so it generates nothing.
- `feature.rolling` after a `resample` step refuses a window over more than 100000 resample intervals when the DAG is
  checked. `POST /v1/dags/deploy` answers 400 and `vectors deploy` exits with code 2. Before, the sink failed only
  while running.
- A DAG refuses `every` of `resample` and `align.vector` and `window` of `feature.rolling` outside PT0.000001S to
  PT2562047H47M16.854775807S, and a `maxAge` or `lateness` beyond that, when it is checked, for example `node n1:
  every: "PT0.0000001S" is out of range; expected an ISO-8601 duration from PT0.000001S to PT2562047H47M16.854775807S,
  such as PT60S`. Before, a clock below one microsecond passed the check and then failed the sink with `/ by zero`,
  and a duration over 292 years answered 500.
- `gen.ou` refuses a `reversion` below 0.00007, where the start of a run did not fade within the warm-up, with
  `gen.ou: reversion: <value> is out of range; expected a number from 0.00007 to 1`. The descriptor states the same
  range, and a negative `sigma` is refused with its value.
- `gen.sine` refuses `period` and `sigma` with the field and its value, for example `gen.sine: period: "PT0S" is out
  of range; expected an ISO-8601 duration from PT0.000001S to PT2562047H47M16.854775807S, such as P1D`. A period over
  292 years answers 400 instead of 500.
- Twin runs add the caveat about the sizes, pressures and order on the hydrogen side only for a scenario with an
  electrolyzer, not for the house and the boat.
- Duration errors of a DAG name the node, the field and the value, for example `node n1: every: "10min" is not a
  duration; expected an ISO-8601 duration such as PT60S`. `POST /v1/systems/<id>/generate` names `from` or `to` with
  its value.
- `POST /v1/segments` and `POST /v1/patterns/search` answer a value out of range with a 400 that names
  `params.<field>`, `offset` or `length` and the allowed range, for example `params.overlap: 10 is out of range;
  expected a whole number from 0 to 9, below params.n`. They also refuse an `offset` near the largest int, a
  `params.duration` below PT0.000001S, and a segment duration over about 292000 years, which answered 500 before.
- Appendix B documents the deploy answer `{dag, state, sinks}`, the electrolyzer condition of the hydrogen caveat of a
  run, and the range messages of segment parameters and episode patterns; the `segment.fixed-duration` descriptor
  states the minimum duration PT0.000001S.
- SaaS backend: the first administrator with an address such as admin@localhost signs in and reads GET /api/v1/auth/me
  instead of getting 500.
- SaaS backend: a changed FIRST_SUPERUSER_EMAIL no longer stops the start on an existing database; the first
  administrator is created only when no administrator exists, and the unused address is logged.
- SaaS backend: with TEST_LOGIN=true, admin/admin signs in as the existing first administrator also after
  FIRST_SUPERUSER_EMAIL changed, and never as an account that is not an administrator.
- `opentsx-saas-backend/docker-compose.yml` passes `ENABLE_SIGNUP` and `LOG_LEVEL` to the backend;
  `ENABLE_SIGNUP=false` in `.env` used to leave sign-up open.
- `opentsx-saas-backend/.env.example` no longer holds `BACKEND_PORT` and the unused SMTP lines; a `.env` copied from
  it used to stop the backend with `BACKEND_PORT: not a setting of the backend`.
- `opentsx-saas-backend/smoke-flows.sh` no longer stops after the first login on a missing `refresh_token`; it checks
  that the login answers none.
- The SaaS documents no longer describe refresh tokens, Alembic migrations or the removed executions route, and
  chapter 14 documents `ENABLE_SIGNUP=false` and the administrator `TEST_LOGIN` signs in as.
- The `kafscale-check` Job and the monitoring CronJob set `enableServiceLinks: false`. Without it, `kafscale-check`
  refuses `OPENTSX_RETRIEVAL_PORT=tcp://...` now that it loads the configuration.
- `bin/opentsx` resolves a cached classpath again when the root `pom.xml` changes or when an installed OpenTSx jar on
  the classpath is newer than the cache, for example after a sibling module was installed with a changed dependency
  scope. It renews the cache after every resolution, because `dependency:build-classpath` leaves an unchanged file
  untouched.
- `opentsx kafscale-check --help` no longer says that the command parses its own flags; it loads the configuration
  registry like every other command, and appendix D and the defaults templates name it.
- `deploy/k3s/00-prereqs.sh` shows the error line of the staged `kafscale-check`, for example a bootstrap address
  without a port, instead of only `kafscale-check failed - see above`.
- `PatienceTest`, the new `ReplayServiceTest` and the pacing case of `EpisodeLogIntegrationTest` run on a test clock
  and assert the pauses and sleeps that `Patience` and the replay pacing ask for, instead of sleeping and asserting
  wall time within 0.25 s.
- Retrieval server: a write the broker does not take (POST /v1/dags/deploy, /v1/dags/stop, /v1/systems/{id}/generate)
  answers 503 {"error":"cannot reach the broker; try again","type":"unavailable"} instead of 500 internal. The answer
  names no broker address, and one ERROR line request failed ... status=503 logs the causes.
- Retrieval server: the KafScale feed task catches every Throwable. An Error no longer cancels the refresh for good:
  the failed refresh is logged and counted in refreshFailures of GET /v1/inventory, and the next refresh tries again.
- Vector processor: a record without a value on the control topic tsx.<domain>.dags.v1 is counted in
  ignoredControlRecords and logged once instead of ending the processor with argument "content" is null, which every
  restart read again. The retrieval server with --kafscale counts such a record on the control or status topic in
  droppedControlRecords or droppedStatusRecords instead of reporting the DAG topics as unreadable for that refresh.
- `twin-live bridge`, `demo` and `publish --loop` stop on SIGTERM: a shutdown hook stops the publisher at its next
  wait, closes the bridge and the topics, and waits up to 20 s; the publisher logs `publisher started scenarios=<ids>
  loop=<bool> duration=<duration|none>` and `publisher stopped runs=<n>`, the bridge logs `event bridge stopped
  port=<port>`.
- The event bridge logs a stream that fails after its 200 header as `stream failed topic=<topic> bytes=<bytes written>
  cause=<causes>` instead of `request failed ... status=200`.
- `ObservationSchema` of the PFD runtime counts undecodable records and records without a value in the Flink metric
  `undecodable`, rejects observations with an empty `uri` in the metric `invalid`, and logs the first of each kind at
  WARN and then at most one count line per minute, instead of one stderr line per bad record and keying observations
  without a URI as `unknown`.
- `TimeSeriesObjectSerializer` refuses a null value with its index, for example `yValues[1]: null; expected a number,
  NaN for a missing value`, instead of writing 0.0, and its wire-format documentation lists what it writes.
- Iceberg store: a write that fails before its commit, or whose commit fails with an Iceberg CleanableFailure such as
  CommitFailedException, now aborts the writer and removes the data files it wrote, and a failing cleanup is kept as a
  suppressed exception. Any other commit failure, CommitStateUnknownException and a lost JDBC catalog answer among
  them, keeps the files, because the snapshot may exist.
- Iceberg store: a scan or a catalog that fails to close names the table or the catalog ('cannot read table
  <catalog>.<ns>.<table>', 'cannot close catalog <name>'), and a data file write that fails with an I/O error reads
  'cannot write table <name>', instead of a bare UncheckedIOException.
- EpisodeLogSync waits out a refused fetch through Patience within the timeout, fails on a missing episodes topic with
  '<topic>: no such topic; check the domain, or create the topic first', and counts records that retention deleted
  after a given position in the new Result field lostToRetention, with one WARN line per partition.
- OpenTSDB store: only a 400 whose body says 'No such name' reads as an unknown metric. Every other error answer fails
  with 'OpenTSDB <method> <url> returned <status>: <body>', and connection failures name the URL.
- OpenTSDB store: a selector without a metric fails when /api/suggest returns 10000 metrics, its limit, instead of
  cutting the list silently.
- OpenTSDB store: two identical reads return equal episodes. The id derives from the series and the first and last
  timestamp, createdAt is the last timestamp, and the producer carries the version of the build (Episodes.producer)
  instead of the literal 3.0.1.
- Market downloads no longer retry an unknown host or a TLS error; the request fails after one attempt.
- An ENTSO-E acknowledgement answered with HTTP 200 counts as an empty month only when it says `No matching data
  found`; any other reason fails the month with `HTTP 200 for <URL>: <reasons>`.
- A malformed ENTSO-E answer no longer prints the XML parser's `[Fatal Error]` line on stderr next to the real
  message.
- `market prices`, `series`, `rebap` and `marktwert` refuse a `--to` that is not after `--from` with exit code 2
  instead of printing an empty summary.
- The SHA-256 check of an archive file that cannot be read names the file.
- `MosmixRawArchive.collect` refuses a station id that is not 1 to 10 letters and digits before the first download
  (`stations: item "../x" is not a station id; expected letters and digits such as 10579 or E5411`), so a caller other
  than `bin/opentsx market mosmix-archive` cannot write outside the archive directory.
- Workbench: an emptied number field or a null parameter takes the default in the preview and is left out of the DAG,
  so the processor takes its default too, for example `count` 1 of `model.power-curve` and `window` 0.25 of
  `stats.window`. The preview computed with 0, and the DAG held `factor: ""`, `min: ,` or `count: "null"`, which the
  processor refused or read as no bound.
- Workbench: `dq.plausible-range` refuses min above max with `max: "<max>" is out of range; expected a number of at
  least min <min>`, and `resample` and `feature.rolling` refuse an `every` or `window` of 0 or less with `every: "0"
  is out of range; expected a number above 0`, as the processor does, instead of giving empty or gap-only results.
- Workbench: `resample` hands out a window once the data has passed its end (the end of the last episode of the
  series, or the last sample plus the sampling interval), as the processor does, instead of handing out the open last
  window. As in the processor, an end more than 10000 windows after the open window hands out no window.
- Workbench: the standard deviation of a single value is NaN, as in Java, instead of 0, in "Window statistics" and the
  statistics table of a bucket.
- Workbench: a `select` pattern that is no regular expression fails with `select: pattern "<pattern>" is not a regular
  expression: <reason>; expected a regular expression such as temp.*` instead of selecting nothing.
- Workbench: episodes and resample windows with more than about 100000 values load and aggregate instead of failing
  with `RangeError: Maximum call stack size exceeded`.
- Workbench: the vector key in the DAG and the text values of the working copy on the page "Signals" are quoted where
  YAML would read them as something else, for example `on`, `no`, `1e3` or `a: b`. An emptied vector key is left out,
  so the processor builds one vector per series as the preview does.
- Workbench: a flow that publishes another node beside an "Align to vector" node, for example `segment.threshold` or
  `stats.window`, no longer passes the deploy list and then fails at deploy. The list names the node and says to take
  it off "Publish as Smart Topic", because the processor writes vector topics only.
- Operator catalog: the `observations` parameter of `stats.welford-ddsketch` names both `bin/opentsx stats` and
  `bin/opentsx stats-flink`.
- Python: `TimeSeriesObject.diff(periods=k)` computes the lag difference x[i] - x[i - k]; it computed the k-th order
  difference. `periods` below 1 raises `ValueError`.
- Python: `TimeSeriesObject.normalize` raises `ValueError` for a series whose values are all equal or whose standard
  deviation, range or interquartile range is 0, instead of returning NaN with a warning or, for `zscore`, values of ±1
  from rounding. This is a breaking change.
- `40-demo.sh` refuses a demo name that is not exactly one of its demos, such as `"dwd dwd-online"`, which before ran
  nothing and exited with 0, and refuses arguments after `--` for `stats`, `retrieval`, `studio`, `dwd-forward` and
  `all` instead of dropping them.
- `20-configure.sh` refuses a value with a line break or another control character, and checks the form of
  `STORAGE_CLASS`, `OPENTSX_IMAGE_REPO`, `OPENTSX_TAG`, `OM_URL`, `S3_ENDPOINT`, `S3_BUCKET`, `S3_REGION`,
  `EVENT_BRIDGE_ALLOW_ORIGIN` and the characters of `DWD_SCHEDULE`, so a value can no longer add keys to the rendered
  YAML or the Iceberg properties.
- `70-backup.sh --quiesce` stops with exit code 1 and scales the writers back when their pods have not ended 150 s
  after scaling to 0 or a Deployment cannot be read, instead of writing a backup that records `quiesced=true`. Ended
  pods, such as evicted ones, do not count.
- `40-demo.sh retrieval` ends its port-forward when the script ends, also after a failure, and a failed request prints
  a FAIL line with the URL and the curl exit code or the HTTP status.
- `40-demo.sh metadata-publish` names a missing `OPENMETADATA_JWT` before it starts the Job.
- `90-teardown.sh --images` reports an image that k3s' containerd could not remove with a warn line naming it and
  exits with 1.
- `deploy/k3s/60-probe.sh` ends the kubectl port-forward of the retrieval and studio steps when the step ends; a step
  that gave up left kubectl running.
- `deploy/docker/stage.sh --out DIR` refuses a directory that is not empty and has no `.opentsx-stage` marker with
  exit code 2, so `--out .` no longer deletes the checkout. stage.sh writes the marker into every directory it stages.
- `deploy/docker/stage.sh` shows Maven's output and exits 1 with `stage.sh: <step>: Maven failed; see the Maven output
  above` when a Maven call fails. Before, it exited without a message. A missing module jar reads `stage.sh: <jar>:
  missing; run mvn -q install -DskipTests first`.
- `bin/010_build.sh` writes its errors (no Java on `PATH`, no `pom.xml`) to stderr.
- The OpenMetadata smoke workflow fails when `OpenMetadataSmokeTest` was skipped, missing or failed. Before, a skipped
  test passed green.
- `tools/pair_xcorr_vectors.sh` compiles into a cleared class directory and stops with exit 1 on a compile error.
  Before, it wrote the vectors from old classes.
- `tools/build_studio.py` reads its argument with argparse and exits 2 without one. It exits 1 when the template does
  not hold `/*DATA*/null` exactly once or when the data is not JSON.
- The cross-language CI job starts the retrieval server on `bin/opentsx classpath opentsx-retrieval`, the classpath
  users run, and checks it for test jars and for exactly one SLF4J provider. Before, it ran with junit and the
  test-scope slf4j-simple.
- `opentsx-saas-backend/local-setup.sh` quotes `$USER` in its `psql` calls and passes shellcheck.
- `bin/000_validate_environment.sh --fix` finds java with `command -v`, so on a host without `which` it finishes the
  checks and prints the summary instead of stopping with exit code 127.
- `LegacyBridge.fromEpisodesRecord(record, null)` refuses an `EpisodesRecord` without a label with `label: missing in
  the EpisodesRecord "<uri>"; expected a label such as "metric k1=v1,k2=v2" or a SeriesKey argument`. Before, every
  such record got the metric `legacy.episode`, so distinct series merged into one.
- `QuantileSketch.fromBytes` reads its varints with `org.opentsx.model.Varints`. A count above 9223372036854775807 and
  a varint over 64 bits are refused instead of read as a negative or truncated number. Bytes that end inside a varint
  fail with `sketch bytes: input ends inside a varint; expected complete varints`, and `sketch bytes: truncated at <k>
  of <m>` remains for bytes that end in the version byte or alpha. The copy `org.opentsx.stats.Varints` is deleted.
- `QuantileSketch.fromBytes` refuses a bin index outside the int range with `IllegalArgumentException: sketch bytes:
  bin index <n> out of range; expected an int` instead of `ArithmeticException: integer overflow`.
  `StatsAccumulator.of`, `StatsAccumulator.readFrom` and `DataQuality.evaluate` now fail on a corrupt sketch with the
  exception their callers treat as bad input. Python `opentsx.stats.QuantileSketch.from_bytes` raises the same texts.
- `opentsx.stats.QuantileSketch.from_bytes` refuses the malformed sketches that Java refuses, with the same texts:
  bytes that end inside a varint, a varint over 64 bits or longer than 10 bytes, a count above 9223372036854775807 and
  a bin index outside the int range.
- `windpark-sim` prints its summary with a comma as the thousands separator and a dot as the decimal point in every
  locale, for example `3,600 episodes` and `4538.4 kWh`, also under a German locale.
- A wind-farm scenario `start` is checked against the `episodeDuration` grid to the nanosecond and after the year
  2262. A start of `2026-10-01T00:00:00.000000500Z` is refused, and a start of `2300-01-01T00:00:00Z` is accepted.
- Episodes from `windpark-sim`, `market load` and `market publish` carry the producer `<module>/<version of the
  build>` from `Episodes.producer` instead of a hard-coded `3.0.1`.
- Workbench: the evaluation cache keeps the results of each source bucket apart, so a second bucket no longer gets the
  results of the first when the cache was not cleared.
- Workbench: the FFT, segments and the end of a series without episodes take the exact median spacing of x as the
  sampling interval, the same one the pair methods take; on an irregular series of 1024 samples or more the FFT
  frequency axis was off.
- The nginx site of the frontend image answers its two 403 refusals with `"type": "forbidden"`, and CI checks both
  bodies.
- Simulate: a run id with `/` or `?` is encoded in the paths of the run and of its comparison.
- Workbench: a flow that publishes a clean signal and any other node but an "Align to vector" node, a second clean
  series included, is refused before the deployment, and the deploy list names that node; the processor refused its
  DAG at deploy.
- Workbench: an emptied `maxAge` of an as-of vector is refused in the preview and before the deployment with `maxAge:
  empty value; expected a number of seconds, 0 or more`, instead of previewing as 0 ticks and writing `maxAge: PTS`
  into the DAG.
- Workbench: an emptied or non-numeric number field of a pair method (`step`, `every`, `maxAge`, `minCoverage`), of
  event synchronisation (`sigma`, `tauMax`, `shuffles`, `seed`) or of cross-correlation (`lags`) is refused with its
  name, for example `lags: empty value; expected a whole number of clock ticks, 0 or more`, and so is a negative
  `step`, `every`, `maxAge`, `minCoverage`, `tauMax` or `lags`; most of them silently took 0 or a default before.
  `step` is read only when `window` is a width in seconds and `maxAge` only when `fill` is as-of.
- Workbench: "persist TSB" on a bucket that cannot be computed shows `The bucket <name> could not be saved: <reason>.`
  instead of throwing in the click handler.
- Workbench: resample runs a series episode by episode as the processor does, closes the windows at the end of each
  episode and starts the grid again after 10000 empty windows, so the preview equals the processor across long gaps.
- The workbench notes of `pair.coverage`, `pair.xcorr` and `pair.es` name the number fields that are refused when
  emptied, negative or not a number, and say that `step` is read only with a window width and `maxAge` only with fill
  as-of.
- The workbench README and HOW_TO_RUN no longer name `public/vite.svg`, `npm run test:coverage` or Plotly.js,
  HOW_TO_RUN lists the source map the build writes, and the SaaS platform document and release summary no longer list
  the four removed frontend packages.
- `RNGWrapper` and the generators that use it, such as `TimeSeriesObject.getGaussianDistribution`, draw numbers
  without `RNGWrapper.init()`. Before, the first draw threw `NullPointerException`. The same seed gives the same
  numbers; for a given seed they differ from those of 3.0.1.
- `twin` and `boat` refuse a plan that lacks the atoms they run on with exit 2 and one line that names them, instead
  of failing with exit 1 and `binding to unknown input port`.
- `house` and `boat` refuse data that would run out of memory with exit 2 and one line: bills, daily temperatures or
  boat data that span more than 3653 days (10 years), boat data that span more than 35136 steps of the plan and bills
  that span more than 87673 steps. `--simulated` checks the bundled data against the plan the same way, and
  `BillCalibration.fit` refuses such bills as well.
- `house` refuses `--weather` without `--calibrate` and `--simulated` together with `--calibrate`, and `boat` refuses
  `--zone` without `--data`, with exit 2 instead of ignoring the option.
- `TwinPlan.load(Path)` names the plan file when the file is missing, unreadable or not a valid plan, with one
  'expected' clause.
- The Studio's event topic view names the existing flag `--kafscale` for `twin-live bridge` and `twin-live publish`.
- `SystemDoc` reports a plan file that cannot be loaded once, as `<file>: <problem>; <what is expected>`, instead of
  `plan <file> cannot be read: <file>: ...`.
- `bin/opentsx boat --data <file.csv> --calibrate` on data without a state of charge says `--calibrate needs the
  column soc_percent` and names `soc_percent` in the shadow line, the English column name of the CSV.
- `DFACore.fitn` fits a least-squares polynomial of the given order, so DFA2 and higher detrend the profile. It
  returned zero coefficients before.
- `DFACore.initIntervalSlog` refuses fewer than 22 values with `IllegalArgumentException` instead of failing with
  `ArrayIndexOutOfBoundsException`.
- `MultiDFATool.runDFA` combines the F(s) of all series at the window sizes of the longest series and writes its files
  under the new public field `savePath`. It failed with `ArrayIndexOutOfBoundsException` before.
- `RISTool.add` throws `IllegalStateException` that names the missing `RISTool.projekt`, `logFileNames`, `rqMIN` or
  `debug1` instead of `NullPointerException`, and a log line of `add` or `addDistData` that cannot be written throws
  `UncheckedIOException` that names the log instead of a bare `IOException` or nothing.
- `MeasurementTable.writeToFile` throws `UncheckedIOException` that names the file when the file or its parent
  directory cannot be written, and accepts a file name without a parent directory.
- `TSBucket` closes its SequenceFile and CSV writers when a series cannot be written, and `SingleRowTSO`, the
  `SingleTs...Tool` operations and `SingleTsRISTool.finish` throw the failure of their result writer or chart instead
  of logging it.
- `MessreihenLoader._loadLogBinnedMessreihe_DIV_BY_BINWIDTH` throws `IllegalArgumentException` for a file without data
  lines, and the skipped share of a log-binned load is a percentage.
- `OriginProject.initBaseFolder` throws `UncheckedIOException` when the folder cannot be created, and
  `OriginProject.logLine` throws `IllegalStateException` for a log that was not created.
- `MRT.expand` counts an event in its own field instead of the field before, refuses an end that is not after the
  start and an event before the start with `IllegalArgumentException`, and leaves out events in a last field shorter
  than the resolution. An event in the first field failed with `ArrayIndexOutOfBoundsException` before.
- `bin/episode_10_production_config.sh` shows the level from `opentsx.properties` when `OPENTSX_LOG_LEVEL` is empty or
  blank, as `ProductionConfig` applies it, instead of the blanks.
- opentsx-lg: `TSDataSineWaveGenerator` sends the flow state of all rounds through one `EventFlowStateProducer` and
  closes it, instead of opening a Kafka producer per round that it never closed. An error while the series are created
  stops the run instead of a NullPointerException, and each round prints one line `round done round=<n> series=16
  generateSeconds=<seconds> sendSeconds=<seconds>` on stdout instead of logging its durations at SEVERE.
- The `docker run` example in the SaaS backend README uses `--network host` and uvicorn on 127.0.0.1, so
  `POSTGRES_SERVER=localhost` reaches a PostgreSQL on the host. The old example looked for the database inside the
  container.
- `EpisodeIndexer.rebuild` logs the first undecodable record and the first invalid episode of each partition at WARN
  through SLF4J, logger `org.opentsx.kafka.EpisodeIndexer`, with topic, partition, offset and cause. Before, it
  printed them with `System.err.println`, which `opentsx.log.level` did not control.
- `Patience` gives up with `the broker has refused reads for more than <limit>`, with the limit as an ISO-8601
  duration such as `PT10M`. A limit below one second no longer reads `0 s`.
- `opentsx stats` without `--once` exits with 2 when none of its input topics has a partition, with `<topics>: the
  input topics do not exist or have no partitions; check opentsx.domain (--domain, OPENTSX_DOMAIN), or create the
  topics first`. Before, it failed with a kafka-clients message and exit code 1.
- A continuous `opentsx stats` run removes its JVM shutdown hook when it ends.
- The sketch decoders in Java (`QuantileSketch.fromBytes`) and Python (`QuantileSketch.from_bytes`) refuse bins whose
  index does not rise after the first bin. They also refuse bin counts that add up with zeroCount to more than
  9223372036854775807. Both give the same text for the same bytes.
- The opentsx-kafka-v2 README lists every topic of `TopicLayout` and names `max.in.flight.requests.per.connection`.
  The opentsx-stats README describes which record `SeriesStatsLog.readLatest` keeps: the one with the most values per
  key.
- The troubleshooting guide, the statistics chapter and the sizing plan quote the current texts: exit code 2 and the
  refusal that names the input topics when a continuous statistics processor finds no partitions, `the broker has
  refused reads for more than PT10M` and `max.in.flight.requests.per.connection=1`.
- `bin/opentsx metadata` reads a `Retry-After` header given as an HTTP date (such as `Thu, 19 Nov 2026 12:00:12 GMT`)
  and waits until that time, at most 30 s, counted from the `Date` header of the answer. Before, it ignored the date
  and used its own backoff.
- `bin/opentsx metadata` repeats the POST of a data-quality result only after a failed connection, because the server
  may already have stored a result it received. After any other network error the run stops with exit code 1 and `POST
  <path>: no answer after the request was sent, so it is not repeated (the server may have stored it); check the
  connection to OpenMetadata, then run the command again`; after a 429, 502, 503 or 504 it stops with `POST <path> ->
  HTTP <status>: <body>`. PUT and GET requests are retried as before.
- `bin/opentsx metadata` reads a `Retry-After` HTTP date in all three forms of RFC 9110: IMF-fixdate such as `Thu, 19
  Nov 2026 12:00:12 GMT`, RFC 850 (`Thursday, 19-Nov-26 12:00:12 GMT`, a two-digit year is the latest year with those
  digits at most 50 years ahead) and asctime (`Thu Nov 19 12:00:12 2026`). Before, a date in the RFC 850 or asctime
  form was not read, and the waits of 1, 2, 4 and 8 s applied.
- `align.vector` in a DAG refuses an unknown `agg` with `node <id>: agg: "<value>" is not allowed; expected one of
  MEAN, LAST, FIRST, MIN, MAX, SUM, COUNT`, so `vectors deploy` exits with code 2 and `POST /v1/dags/deploy` answers
  400 with that text instead of the JDK text `No enum constant
  org.opentsx.retrieval.vector.VectorAligner.Agg.<value>`.
- `EpisodeService.similarTo(episodeId, offset, length)` and `POST /v1/patterns/search` with `episodeId` refuse a
  `length` above 100000 (`EpisodeService.MAX_PATTERN_LENGTH`) with `length: <n> is out of range; expected a whole
  number from 4 to 100000, the most values of a pattern`, as a `pattern` of values does.
- The vector processor (`opentsx vectors`) logs the first undecodable record of each partition of a sink at WARN:
  `undecodable record skipped and counted in undecodable; later ones of this partition are only counted dag=<dag>
  sink=<vector> topic=<topic> partition=<n> offset=<offset> cause=<causes>`. Before, it only counted it in
  `undecodable` of the status.
- `feature.rolling` in a series sink adds up the whole window when a value whose square is no finite number (above
  about 1.3e154 in magnitude) leaves it, instead of at every step while such a value is in the window: 30000 samples
  with a 10000-value window and one such value take 20 ms instead of 203 ms. It still adds up at every step while the
  values of the window or their squares add up beyond about 1.8e308. MEAN of such windows can differ in the last
  digits.
- The descriptor of `pattern.search` gives `length` the range 4 to 100000 (`EpisodeService.MAX_PATTERN_LENGTH`), and
  appendix B of the user manual lists the limit with its 400 answer.
- The java note of `pattern.similar-episodes` says that no writer stores the SAX word, so the search computes it for
  every candidate. The processor note of `feature.rolling` says when a step adds up the window again, when STD is
  infinite or NaN, and that MEAN is infinite while the values of the window add up beyond about 1.8e308, even when
  their mean is a finite number.
- The operations and troubleshooting guides list the WARN line `undecodable record skipped` that the vector processor
  logs once per partition of a sink.
- `twin` and `boat` refuse a plan with more than 35136 steps (366 days of 15-minute steps) and `house` a plan with
  more than 87673 steps (3653 days of hourly steps) with exit code 2 and `<plan>: steps: <n> is not allowed; expected
  a whole number from 1 to <bound>`, instead of running out of memory after about a minute. A plan with fewer than 1
  step gets the same text. A `twin` or `boat` run over 35136 steps and a `house` run over 87673 steps fit 2 GB of Java
  heap.
- The step limit of `house --calibrate` and `house --simulated` counts the steps the run takes, from local midnight to
  local midnight in Europe/Berlin, so a span with one more clock change in autumn than in spring reports its true step
  count. The limit is 87673 steps, which still allows 3653 days of hourly steps.
- `history-twin twin` and `history-twin boat` (also `twin --history` and `boat --weather-year`) refuse `--days` above
  366 and `--days 0` with exit code 2 and `--days: "<n>" is not allowed; expected 1 to 366`, instead of running out of
  heap, refusing with the plan's `steps` or overflowing the step count.
- The twin-live event bridge skips a record whose message cannot be written as JSON and keeps the stream open. It logs
  `record skipped, it cannot be written as JSON topic=<topic> offset=<offset> cause=<causes>` at WARN. It used to end
  the stream without a log line, as if the browser had left.
- `KafkaTopics.append` after `close` throws `IllegalStateException: topics: closed; create a new KafkaTopics to append
  again` instead of opening a new producer.
- `EpisodeLogSync` validates every decoded episode with `Episodes.validate`. An invalid one is counted in the new
  `Result` field `invalid` and not written. The first undecodable record and the first invalid episode of each
  partition are logged at WARN with topic, partition, offset and cause. The `Result` constructor takes the new field.
  This is a breaking change.
- `EpisodeLogSync` counts records that retention deletes while it reads in `lostToRetention` and reads on from the new
  log start, instead of waiting until `timed out syncing <topic>` when the remaining records of a partition were
  deleted. When the time runs out during that log start request, the sync fails with `timed out syncing <topic>`, with
  the client's `TimeoutException` as cause when there is one.
- Iceberg store: a write adds the series row of every series in the batch that has none, so a retry after a failed
  series append writes the row that the first attempt did not commit.
- Iceberg store: a data file that does not exist fails with `UncheckedIOException` `cannot read table
  <catalog>.<ns>.<table>` instead of the bare `NotFoundException`, which stays in the cause chain and names the file.
- OpenTSDB store: a request body that cannot be encoded as JSON fails with `OpenTSDB POST <url>: cannot encode the
  request body as JSON` instead of a bare `UncheckedIOException`.
- `LogReader.readAll` computes one deadline: the partition lookup, the log start and end offsets and the read get what
  is left of the timeout in whole milliseconds, so `SeriesStatsLog.readLatest`, `EpisodeIndexer.rebuild` and
  `BucketCatalog.load` give up after the timeout they were given instead of after up to four times as long. With less
  than a millisecond left before the read, `readAll` fails with `timed out reading <topic>`.
- `QuantileSketch.add(value, n)` and `merge` refuse a total count above 9223372036854775807 with
  `IllegalArgumentException` and leave the sketch as it was, instead of wrapping the count. `add` says `count: <count>
  plus n <n> is above 9223372036854775807; expected a total count of at most 9223372036854775807`; `merge` says
  `count: <count> plus the count <other> of the merged sketch is above 9223372036854775807; expected a total count of
  at most 9223372036854775807`.
- A `--once` run of the statistics processor ends with `timed out computing statistics` when less than 1 ms is left
  for the log start check, instead of a `NullPointerException`, and with the client's `TimeoutException` as cause when
  the log start check gets no answer in the time left.
- The wind farm `Normalizer` refuses an `episodeDuration` that is not a whole number of microseconds, such as
  `PT0.0000015S`, with `episodeDuration: PT0.0000015S is not a whole number of microseconds; set episodeDuration to a
  whole number of microseconds, such as PT10M`. It used to cut the window to whole microseconds while the episode kept
  the original length.
- The documentation link check `tools/check_links.py` also covers `opentsx-saas-frontend`, `opentsx-saas-backend`,
  `exercises`, `tests`, `data` and image links, and skips the `external/` Wayang submodule. The exercise pages link to
  the user manual and the `opentsx-core` README instead of 20 pages that do not exist.
- `tools/check_links.py` exits 2 when its root is not a directory or holds no `.md` file to check, instead of passing
  on 0 files, and answers `--help` with its usage.
- The Python topic reader closes consumers on failures, validates time filters before creating a consumer,
  disables automatic offset storage, and shares one finite timeout across metadata, watermarks and polling.
  Assigned reads tolerate at most 32 nonfatal unsupported FindCoordinator notices within that deadline;
  other broker errors propagate. Records outside the initial per-partition offset ranges are not decoded,
  including records arriving on a completed partition while another partition is still being read.
- The exercise runner uses the Maven classpath, propagates failed assertions and rejects invalid modes.
  Seeded exercise checks recalculate means and test the actual scaling and daily-window behavior.
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
- The Flink null-field aggregation test runs with nullable getter fixtures and preserves its result assertions.
- Z-score normalization rejects malformed, non-finite and zero-variance inputs before producing invalid values.
- PFD log output writes JSON coordinates and values for every window, including fewer than five points.
- Hourly solar observations (`FG_LBERG`, `FD_LBERG`) sat up to almost an hour early (56 minutes at Arkona in
  October). `MESS_DATUM` of that product is the UTC moment at which the hour in true solar time ends (`hh:mm`), and
  the parser cut off the minutes. It now rounds to the nearest full hour.
- The market licence texts written into manifests, episode labels and the MOSMIX index are English: `CC BY 4.0;
  Source: Bundesnetzagentur | SMARD.de` and the same `Source:` form for Energy-Charts, netztransparenz.de and the DWD.
  The history manifest example in `docs/devops/` shows the same form.
- A netztransparenz.de token endpoint that answers with a status other than 400 or 401, such as a 503 after the
  retries, no longer counts as a rejected client. The command prints `netztransparenz token: HTTP <status> from <url>;
  the service failed, try again later` without the response text, and a backfill marks that year FAILED and tries the
  next year instead of skipping the source.
- Each retry of the market downloads waits at most 60 s for a `Retry-After` header and logs `Retry-After capped at 60
  s url=<URL> status=<status> retryAfterSeconds=<seconds>` at WARN; a `Retry-After` lengthens only its own pause, and
  the backoff of the next retry stays 2, 4, 8 or 16 s. Before, `Retry-After: 3600` waited an hour.
- `bin/opentsx house` and `boat` with `--history` refuse a `--station` or `--radiation-station` that is not 5 digits
  with exit code 2 and `--station: "<id>" is not a station id; expected a station id of 5 digits such as 03166`.
- `bin/opentsx twin --history` without `--days` refuses a `--from` that leaves no day or more than 366 days to the end
  of `--year` with exit code 2 and `--from: "<day>" is not allowed; expected a UTC day from <first> to <last>, so that
  the run to the end of --year <year> has 1 to 366 days`. Before, the refusal named the plan steps.
- `MeterNormalizer` of opentsx-modbus refuses an episodeDuration that is not a whole number of microseconds with
  `episodeDuration: <d> is not a whole number of microseconds; set episodeDuration to a whole number of microseconds,
  such as PT1M`, as the wind farm Normalizer does.
- SaaS backend: SIGTERM or SIGINT while the backend waits for its database ends the start at once with `database
  <host>:<port>/<db>: not reachable when the stop came; start the backend again once the database answers` (exit code
  3); the backend used to keep retrying for up to about 4.5 minutes. uvicorn still receives the signal.
- SaaS backend: `POST /api/v1/dags/deploy`, `POST /api/v1/dags/stop` and `GET /api/v1/dags/capabilities` check the
  role before `RETRIEVAL_URL`, so an account that may not deploy gets 403 also on an installation without a retrieval
  server. `smoke-flows.sh` expects 503 when the capabilities name `RETRIEVAL_URL`.
- Python `opentsx.store.iceberg`: `read_episodes` and `read_frame` read a time with any number of decimal places or a
  decimal comma, such as `2026-10-01T00:00:00.5Z`, on Python 3.10 as on Python 3.11.
- Python `decode_deltas` refuses a decoded timestamp or delta outside the 64-bit range with `timeDeltas: timestamp <i>
  leaves the 64-bit range; expected timestamps and deltas from -9223372036854775808 to 9223372036854775807` instead of
  raising `OverflowError` or wrapping with a `RuntimeWarning`.
- Python `encode_deltas` and `irregular` refuse a step above 9223372036854775807 microseconds with `timestamps:
  timestamp <i> is <step> after timestamp <i - 1>; expected steps of at most 9223372036854775807 microseconds`; they
  used to write the step wrapped to 64 bits, bytes that `decode_deltas` refuses.
- Python `QuantileSketch.add` and `merge` refuse a total count above 9223372036854775807 with the texts of Java and
  leave the sketch unchanged; `count` no longer sums the bins on every call.
- Python `TimeSeriesObject`: indexing takes numpy integers such as `np.int64`, and `normalize` refuses values that are
  not finite with `values: <n> of <m> values are not finite, the first at index <i> is <value>; expected finite values
  only, drop or fill the gaps first` instead of returning only NaN.
- Notebook 1 stops in its first cell when neither the retrieval server nor a broker can be reached; notebook 3 shifts
  a copy of the file's series when the file stamps the end of an interval and no unit conversion is known.
- Java `TimeAxis.encodeDeltas` and `Episodes.irregular` refuse a step above 9223372036854775807 microseconds with
  IllegalArgumentException and the text of Python instead of ArithmeticException, and `TimeAxis.decodeDeltas` refuses
  a timestamp or delta outside the 64-bit range instead of wrapping it. A falling step below the long range now says
  `not after` as Python does. `model-refusals.v1.json` has four more shared cases (21 refusal texts).
- SaaS backend: `POST /api/v1/systems/<system>/generate` answers 403 to an account that may not run generators before
  it checks `RETRIEVAL_URL` (503), in the same order as the DAG routes.
- The exercise solution `BonusExercise_PatternCreation` and the starter code of SWE episodes 2 and 3 imported
  `org.opentsx.tsa.rng.RNGWrapper`, which does not exist, so they did not compile. They import
  `org.opentsx.data.generator.RNGWrapper`.
- `exercises/README.md` listed solutions for episodes 9 and 10 that do not exist and compiled
  `Exercise1_ManualCreation` from a path and a package that do not exist. It lists the exercises and solutions that
  exist and compiles a solution from `exercises/solutions/` in the package `org.opentsx.exercises.swe.solutions`; the
  filler and the footer are gone.
- `docs/devops/today-vs-planned.md` said that none of the DAG `opentsx.dag.v1` is implemented. It lists the DAGs
  `opentsx.dag.v1` (vector sinks) and `opentsx.dag.v2` (series sinks) and the vector processor as deployable now, and
  the figures of a one-off test session are replaced by what the CI job `k3s scripts and manifests` checks.
- `SECURITY.md` said there are no tagged releases. It lists 3.0.x (`v3.0.0`, `v3.0.1`) and `master` as supported.
- `CONTRIBUTING.md` named a CI trigger (`opentsx/**`) and job descriptions that were out of date. It describes the
  triggers and jobs of `ci.yml`, `image.yml` and `openmetadata-smoke.yml`.
- The Flow Composer page and the place Flows of the Systems page now say when the browser kept flows from opening in
  tabs of their own, and stay on the page: `The browser kept <n> of the flows from opening in tabs of their own, so
  this page stayed and did not open the first one either. Allow pop-ups for this page, or open them one at a time.` On
  the Systems page the notice goes when another place or system opens. Before, both pages went to the workbench
  without a word.
- The workbench now refuses an emptied or missing clock `every` of a published "Align to vector" node before the
  deployment, with `every: empty value; expected a number of seconds above 0` (`every: missing; ...` when the key is
  absent), and leaves it out of the DAG. Before, it wrote `every: PTS` or `every: PTundefinedS`.
- Window statistics in the workbench now start the window grid again after more than 10000 empty windows, as resample
  does. Before, they handed out every empty window of a long gap.
- The clean signal checks of the workbench now name wind shear among the steps the processor runs.
- The workbench preview of an "Align to vector" node refuses an emptied clock with `every: empty value; expected a
  number of seconds above 0` and a missing one with `every: missing; expected a number of seconds above 0`, the same
  texts as the deploy checks.
- `bin/000_validate_environment.sh --fix` prints the line to run, `Run: export JAVA_HOME=<path>`, with the path quoted
  for the shell. Before, it claimed it had set JAVA_HOME for the session, which a script cannot do.
- `bin/000_launch_tsa_workbench.sh` prints `TSA Workbench exited with code: <n>` and exits with that code when Maven
  or java fails; before, `set -e` ended the script before the message. Its header names Java 17 and says that
  JAVA_OPTS reaches java only without Maven, because `exec:java` runs in the Maven JVM, which reads MAVEN_OPTS.
- `tools/pair_xcorr_vectors.sh` shows Maven's output and names the step when `mvn install` or
  `dependency:build-classpath` fails; before, it stopped without a message.
- `deploy/k3s/60-probe.sh` messages and the report result line use a colon or a semicolon instead of a spaced hyphen,
  for example `| result | **FAIL**: 0 passed, 1 failed, 0 skipped/planned |` and `1 step(s) failed; report: <file>`.
- `.gitignore` ignores the CSV files that the episode 2 and 3 exercise solutions write into the project root, such as
  `exercise1_output.csv`.
- opentsx-core: `RISTool`, `ReturnIntervallStatistik2`, `TSPropertyTester` and `HaeufigkeitsZaehler` close their file
  when `store()` fails.
- opentsx-core: `BucketLoader.loadBucket(path, ids)` and `_processBucket(path, operation, ids)` load and process only
  the records whose position in the file, counted from 1, is in `ids`, and every record when `ids` is null, also after
  a call with ids; before, the ids were compared with a string and every record was loaded.
- opentsx-core: `GrangerCausality.grangerCausalityTest(y, x, l)` refuses a lag below 1, series of different length and
  fewer than 3l + 2 values with `IllegalArgumentException` that names the argument, also for a lag too large for an
  int count, and wraps a regression that cannot be solved with a message that names y, x and the lag and keeps the
  cause.
- opentsx-core: `LongTermCorrelationSeriesGenerator.writeTables` prints nothing; `getRandomRow` with `showTest` and
  `runDFA` true before `writeTables` throws `IllegalStateException` (`showTest: true needs the alpha table of
  writeTables, which is not allocated; pass showTest false or call writeTables`) instead of ignoring the failure.
- opentsx-core: opening a chart panel (`MultiChart`, `TSOperationControlerPanel`) prints nothing, the chart folder and
  the series colors are DEBUG lines, and no longer creates `opentsx/plots` below the home directory; `save PN` creates
  the folder when it writes.
- The solution of exercise 3 of episode 2 reads `sample_data/sensor_data.csv` with its header line and ISO-8601 times
  and exits with 1 and one message line on a broken or missing file instead of printing a stack trace and exiting with
  0.
- opentsx-flink-core: TimeSeriesObjectSerializer keeps the statistics DecimalFormat pattern when it reads a series
  back and in copy(); the read and copied series used to fall back to the default pattern.
- opentsx-connectors: TSOConsumer opens one consumer, which runConsumer closes. runConsumer reads until at least the
  requested number of records has arrived, keeping every record of the last poll, and stops with a WARN line after 10
  empty polls of one second in a row when the topic holds fewer records. The constructor used to leak an unused
  consumer, and runConsumer never returned on such a topic. A second runConsumer call on the same TSOConsumer now
  fails because the consumer is closed.
- opentsx-connectors: TSOWriter.persistBucket_SequenceFile closes the SequenceFile when a series cannot be written.
- opentsx-lg: EventFlowAnalysisConsumer and RequestResponseAnalysisConsumer no longer fail with a NullPointerException
  after the measurement when OPENTSX_SHOW_GUI is unset. With OPENTSX_SHOW_GUI=true they open the chart; otherwise they
  write the table file. Their latency series is named after the topic, so an empty topic stops with 'getStatisticData:
  the series <topic> has no values; expected at least 1 value'.
- opentsx-lg: the krake-metrics-provider file docker-compose-witth-prometheus.yml has a services key and no dependency
  on services it does not define, so docker compose config accepts it.
- opentsx-connectors, opentsx-lg: mvn exec:java and the jar of the opentsx-lg SimpleTimeSeriesProducer profile carry
  slf4j-simple with conf/simplelogger.properties, so the tools' log lines reach stderr instead of the SLF4J
  no-operation logger.
- opentsx-connectors: `TSOWriter.persistEvents_Kafka` sends to the topic it is given instead of always to
  `TSOEventProducer.TOPIC`; `TSOEventProducer` has `pushTSOItemsToKafka(series, topic)`.
- `fill` and `emit` of `align.vector` no longer run with their default for an unknown value: `fill: asof` and `emit:
  completed` refuse the DAG with `node <id>: fill: "asof" is not allowed; expected one of none, as-of` (and `any,
  complete` for `emit`), `POST /v1/dags/deploy` answers 400 and `vectors deploy` exits with code 2.
- `feature.rolling` MEAN and STD are finite whenever the mean and the spread of the window are: a window whose sum or
  squares overflow a double is added up scaled by a power of two of its largest magnitude, so 1e308 and 1.5e308 give a
  MEAN of 1.25e308 and a STD of 2.5e307 instead of Infinity and NaN, and 120 values alternating 2e153 and 1e153 give a
  STD of 5e152 instead of Infinity.
- `EpisodeLogSync.sync` gives the metadata requests before the copy what is left of one `timeout` instead of the full
  timeout each, and fails with `timed out syncing <topic>` (the client's `TimeoutException` as cause) within the
  timeout when the broker does not answer.
- `RetrievalClient.episode()` returns `None` only for the server's `unknown episode <id>`; a path the server does not
  know (`no such endpoint: <path>`, for a base URL with a wrong path prefix) raises `RetrievalError`, which gains the
  field `reason`.
- Python `encode_deltas` and `irregular` refuse a timestamp outside the 64-bit range up front with `timestamps:
  timestamp <i> is <t>; expected timestamps from -9223372036854775808 to 9223372036854775807`, also when every step
  fits.
- The event bridge refuses an unknown `from` with `from: "<value>" is not allowed; expected latest, earliest or an
  offset of 0 or more`.
- The workbench keeps the notice that the browser kept flows from opening in tabs of their own after the first flow
  has loaded, until the next action in the Flow Composer; the notice text is held once, and the Flow Composer page and
  the Systems page no longer clear and restore the flow the workbench holds.
- `deploy/k3s/30-deploy.sh` no longer reports `deployed` when it cannot list the Deployments; it exits with 1 and
  names the namespace.
- `tools/pair_xcorr_vectors.sh` passes the Java sources to javac as an array; CI runs `bash -n` and `shellcheck` on it
  and on `opentsx-saas-backend/local-run.sh`.
- `FluctuationFunktion.calcAlpha` prints nothing and logs the fitted alpha at DEBUG through SLF4J. An F(s) without
  values throws `IllegalStateException` with `<label>: alpha cannot be fitted in [<min>, <max>]; expected a
  fluctuation function with values` instead of logging SEVERE and returning NaN; NaN stays when the values of F(s) in
  the fit range have fewer than two different x values.
- `MultiChart.store` and `TSOperationControlerPanel.storeChartImageAsPNG` write the PNG without the fixed waits of up
  to 2 seconds and print nothing. An image that cannot be written throws `UncheckedIOException` with `<file>: cannot
  write the chart image (<cause>)`, and the `save PN` button shows that message in a dialog. The static
  `MultiChart.store(...)` prints nothing, and `save()` closes the caption file when the caption cannot be written.
- `FFTPhaseRandomizer` prints nothing, and the unused private method `debugRows` is removed.
- `TSBucket.processKVToTSO` accepts a Kafka record without a key: the series description shows `[]` for the key, where
  `TSOConsumer.runConsumer` stopped with a `NullPointerException`.
- `bin/episode_02_create_timeseries.sh`, `bin/episode_03_basic_operations.sh` and `bin/episode_09_analysis.sh` no
  longer create an empty `output/` folder; the demos write to `data/demo_output/`.
- The solution of exercise 3 of episode 2 prints `degC` instead of a degree sign, so its output is plain ASCII under
  any default charset.
- opentsx-connectors: `KafkaConnector.close()` closes the consumer that `connect()` opened when `processTopic` never
  ran. `TSOConsumer.close()` closes its consumer once, and a second call does nothing.
- opentsx-lg: `RequestResponseAnalysisConsumer` writes its latency table to `$HOME/opentsx/plots`, as
  `EventFlowAnalysisConsumer` does, instead of `./opentsx/plots` in the working directory.
- opentsx-lg: every script has a shebang, stops when a `cd` fails and passes shellcheck.
- The vector processor decides the records of the control topic in their order, so a restart keeps the DAG that wrote
  each vector topic and signal before it; until now the DAG named first in the control topic won after a restart.
- The event bridge looks up topics for its readiness probe through one Admin client for its whole life instead of a
  new one per probe; a lookup that fails closes the client and the next one opens a new one, and a stream still looks
  its topic up on a client of its own.
- The statistics processor writes its progress file only after a poll the broker did not refuse, so `opentsx-stats` is
  no longer ready about 3 minutes after the broker starts refusing reads; until now the file stayed fresh for up to 10
  minutes of refusals.
- `boat --data` refuses a file with more than 527040 data rows (one row per minute for 366 days; every line after the
  header that is neither blank nor a comment counts) with `line <n>: more than 527040 data rows; expected at most
  527040 rows, such as one row per minute for 366 days`. It stops reading the file at the first data row over the
  bound, so a file far over the bound exits with 2 instead of running out of memory, and a calibrated run at the bound
  fits the 1536Mi boat Job. `BoatData.MAX_ROWS` holds the bound.
- The opentsx-lg Docker profile and `src/main/docker/core-tool-image/Dockerfile` build on the pinned base image
  `eclipse-temurin:21.0.8_9-jre-noble` instead of the unpinned `confluentinc/cp-base`, whose newest tag carries Java 8
  and cannot run the Java 17 jar.
- `opentsx-core`: `MultiChart.store(chart, folder, name)` writes the chart it is given; it wrote the chart of its own
  dialog.
- `opentsx-core`: `TSOperationControlerPanel` writes nothing to standard output and no longer uses java.util.logging;
  the fitted alpha of `DFA` and the time slices of `Histogram cross-cut` and `TEST Normal Distribution` are DEBUG
  lines. A failing `show PN`, `DFA`, `Auto-Correlation`, `NORM std=1, avg=0`, `MF-DFA` or `Shuffle` stops and shows a
  message such as `DFA of <series>: cannot compute the fluctuation function (<cause>)` in a dialog instead of logging
  and going on, `persistTSB()` throws `UncheckedIOException` with `<track>/<label>: cannot write the bucket
  (<cause>)`, and cancelling the `Shuffle` dialog does nothing.
- `opentsx-core`: `ReturnIntervallStatistik2._addData` and `TSPropertyTester._addData` add the intervals between the
  times they are given; they passed an empty list to `addDistData`, so `SingleTsRISTool` collected nothing. A time
  before the time before it throws `IllegalArgumentException` with `_addData: time <k> of <name> is <value>, before
  time <k - 1> at <previous value>; expected times that do not decrease`, and a NaN or infinite time throws it with
  `_addData: time <k> of <name> is <value>; expected a finite time`.
- `opentsx-core`: `TSBucket.loadFromSequenceFile(path, ids, operation)` and `BucketLoader` label each series with its
  position in the file, counted from 1 for every load; loads that ran at the same time shared one static counter and
  mixed their labels.
- `ReturnIntervallStatistik2.addDistData` sorts into its bins only the intervals it keeps (not 0 and not below
  `offset`), scaled with their mean, so the probabilities of a result that keeps an interval add up to 1; the
  intervals 1, 0 and 1 gave probabilities that added up to 1.5.
- `POST /v1/dags/deploy` and `opentsx vectors deploy` now read the control topic and decide its records in log order,
  the same way the vector processor does. A DAG that the processor refused no longer counts as the writer of its
  vector topic or signal. A later DAG is no longer refused because of it, and a refusal names the DAG that actually
  writes the topic or signal.
- `Patience` in opentsx-kafka-v2 now compares clock readings only by their difference while it waits out refused
  reads. With a clock that reads negative values, a run of refusals logs the WARN line once and the wait still ends
  after the limit.
- `boat --data` refuses a file of more than 67108864 bytes (64 MiB, `BoatData.MAX_BYTES`; blank and comment lines
  count) with `<file>: more than 67108864 bytes; expected a file of at most 67108864 bytes` and exit code 2 once it
  reads past the bound, keeps blank and comment lines without their text and keeps of each row only the columns the
  twin reads. A file of 64 MiB of blank lines and one of 64 MiB with 527040 rows of 55 columns, 54 of them not read,
  now fit the 1152 MiB heap of the boat Job (measured peak RSS 968 MB and 903 MB). Before, such files were read into
  memory in full and ran out of memory.
- `opentsx-core`: `MultiChart.store(chart, folder, name)` and `TSOperationControlerPanel.storeChartImageAsPNG(chart,
  folder, name)` create the folder before they write the image. Before, a missing folder stopped
  `MultiChart.store(rows, ...)` and `MultiChart.openAndStore` with `<file>: cannot write the chart image (<cause>)`,
  and with them `TSOWriter.persistBucket_CSV` and the opentsx-lg analysis consumers with `OPENTSX_SHOW_GUI=true`; the
  measurement table was not written.
- `opentsx-core`: `Histogram per series`, `Histogram cross-cut` and `ENTROPY` of the chart panel check their numbers
  before they compute. A cancelled dialog does nothing. A `min` or `max` that is not a whole number from -2147483648
  to 2147483647 shows `<field>: "<value>" is not allowed; expected a whole number from -2147483648 to 2147483647`. A
  `bins` or `window length` that is not a whole number from 1 to 2147483647 shows `<field>: "<value>" is not allowed;
  expected a whole number from 1 to 2147483647`. An `ENTROPY` length that is not a whole number from 4 to 2147483647
  shows `length: "<value>" is not allowed; expected a whole number from 4 to 2147483647`. Each message
  shows in a dialog, the operation stops and no chart opens. Before, they threw `NumberFormatException` or
  `IllegalArgumentException` on the event dispatch thread, and a window length of 0 made `Histogram cross-cut` loop
  until the heap ran out.
- `opentsx-core`: the chart panel records an operation in the macro track only after it succeeded, `FFT` included, so
  an operation that fails leaves the track unchanged. The chart comment of `DFA` has one line per series with its
  label and its own fitted alpha; before, each line showed the alpha of the series before it (0.0 for the first). The
  `Shuffle` dialog asks `Number of shuffles:`.
- `opentsx-core`: `MacroTrackerFrame`, `SigmaFilter.aggregate` and `HurstSurface.plot` print nothing. Added sources,
  selected nodes, opened charts and the progress of `Sigma Band` and `MF-DFA` are DEBUG lines, and `MacroTrackerFrame`
  no longer uses java.util.logging. `Export code (Java)` and `Export code (Scala)` copy the generated code to the
  clipboard without printing it, and `Export code (Scala)` copies the Scala code instead of the Java code. An
  operation that ends in `(`, such as `Shuffle of x(` for a series labelled `x(`, records its node and a PFD step with
  an empty `arg` instead of printing to standard error and recording no step, and a PFD recorder that cannot start
  makes the call throw before the node is added.
- `opentsx-core`: `ReturnIntervallStatistik2` and `TSPropertyTester` fill the counts that `calcMessreihen` reads for a
  result that is not a container. `mrHaeufigkeit` and `mrVerteilung` hold the count and the share of the intervals in
  bins of width 1/`scale` instead of zeros. The shares of the scaled bins added to `mrVerteilungSkaliert` are divided
  as doubles instead of being rounded to 0, and they are placed at the center of the scaled bin, (i + 0.5) * `scale` /
  `binning`, instead of the integer part of i / `scale`.
- `opentsx-core`: `TSPropertyTester.addDistData` scales its bins with the mean of its intervals instead of the value
  of `setRQ` (1.0 by default), and `getRq()` returns that mean. It counts an interval below the first or past the last
  bin in that bin, so its probabilities add up to 1. `setRQ` does not change the bins.
- `opentsx-core`: `TSPropertyTester.addDistData` refuses an interval that is NaN or infinite with
  `IllegalArgumentException` `addDistData: interval <k> of <name> is <value>; expected a finite interval` and adds
  nothing, instead of sorting every interval into the first bin.
- `opentsx-core`: `EntropieTest` prints nothing from its library methods. The DFA status is a DEBUG line, and `main`
  prints the result line that `calcTEandMI` returns. `main` no longer ends with an empty line and `mr and m2`; it logs
  `compared surrogates experiment=<label> series=<n>` at DEBUG instead.
- The opentsx-lg MQTT example pins the connectors and images that three of its scripts install and start:
  `01_bootstrap.sh` installs `confluentinc/kafka-connect-mqtt:1.7.11` and
  `confluentinc/kafka-connect-influxdb:1.2.13`, and `02_run_mqtt_broker.sh` and
  `influxdb-grafana/__run_container_01.sh` start `toke/mosquitto` and `philhawthorne/docker-influxdb-grafana` by the
  digest that `latest` names, in place of `latest` or no tag. `tools/test_lg_scripts.py` fails on an image or
  connector without an exact version in the opentsx-lg scripts and Dockerfiles, and on an option before the image
  that it does not know.
- `opentsx-lg/src/main/docker/core-tool-image/Dockerfile` runs `apt-get update` and `apt-get install -y
  --no-install-recommends` in one `RUN` and removes the package lists, in place of two layers and the deprecated
  `--force-yes`.
- `deploy/k3s/11-import-images.sh --method kind` reports a failing `kind get clusters`, for example with docker
  stopped, as `kind get clusters: failed (<its error output>); start docker or check the kind installation` with exit
  code 1. Before, it reported `kind cluster <name>: not found`.
- `deploy/k3s/70-backup.sh` stops with exit code 1 and `ConfigMap opentsx-config: cannot read it in namespace
  <namespace>; see the error above, then run 70-backup.sh again` when kubectl cannot read the ConfigMap. Before, it
  wrote an empty `opentsx-config.yaml`, recorded it in MANIFEST and exited 0.
- `deploy/k3s/71-restore.sh --only env` stops with exit code 1 and `<env file>.before-restore: cannot keep the current
  .env there; see the error above, nothing was restored` when it cannot copy the current `.env` aside. Before, it
  replaced `.env` without the copy and exited 0.
- `deploy/k3s/71-restore.sh` restores a backup whose MANIFEST has no `created=`, `namespace=`, `image=` or `git=`
  line. Before, it stopped after the checksum check with exit code 1 and no message.
- `house --calibrate` reads the bills file only up to the first data row over 1200 (ten bills a month for 10 years),
  and `--weather` reads the temperatures file only up to the first data row over 87673 (one per step of the longest
  house plan). It refuses a bills file of more than 1048576 bytes (1 MiB) and a temperatures file of more than
  16777216 bytes (16 MiB). Both refusals exit with 2 and use the texts of boat, for example `<file>: line <n>: more
  than 1200 data rows; expected at most 1200 rows, such as ten bills a month for 10 years`. Before, both files were
  read into memory in full.
- `deploy/k3s/70-backup.sh` and `71-restore.sh` stop with `pod <name>: not ready after 180 s; check that the image
  <image> is imported and that the PVCs are on one node, see <kubectl> -n <namespace> describe pod <name>` when their
  helper pod does not get ready.
- `opentsx-core`: `NORM std=1, avg=0` shows `standardized series of <series>: cannot standardize the values (<cause>);
  select series with at least two finite values that are not all equal` in a dialog and opens no chart when a series
  has fewer than two values, a NaN or infinite value or no positive standard deviation; it threw
  `IllegalArgumentException` on the event dispatch thread.
- `opentsx-core`: `show PN` shows `<folder>: no such folder; press save PN to create it` while the folder of the panel
  does not exist yet, and `<folder>: cannot open the folder (<cause>); open it in a file manager` when the desktop
  cannot open it. On a desktop without open support it did nothing.
- `opentsx-core`: `FFT` over two series with the same label shows `FFT of <label>: cannot open the chart (<cause>);
  give each series its own label` in a dialog and records nothing; it threw `IllegalArgumentException` on the event
  dispatch thread.
- `opentsx-core`: the panel `DFA` uses at most as many window sizes as fit 1 apart between 4 and an eighth of the
  number of values, so a series of 1024 values gets 124 window sizes and a finite alpha instead of 250 sizes, almost
  all of them 4, and alpha NaN. Series of 2032 values or more keep their 250 window sizes, and series of 2032 to
  3160031 values their alpha.
- `opentsx-core`: `TEST Normal Distribution` names its series `l=20` to `l=100` and opens both charts; when two slice
  lengths gave the same number of slices, as for a series of 100 values, both W histograms were named `HISTOGRAM/1`
  and the button threw `IllegalArgumentException` on the event dispatch thread.
- `opentsx-core`: `show Chart` of `MacroTrackerFrame` does nothing without a selection, shows `show Chart: no chart
  for <node>; select an operation node` for a node without a chart and opens the chart of every operation node the
  chart panel recorded, for an `MF-DFA` node the F(s,q) chart of the original values of the first series; it threw
  `NullPointerException`.
  `MacroTrackerFrame.addSource` without an open tracker writes only its DEBUG line instead of
  throwing `NullPointerException`.
- `opentsx-core`: `Sigma Band` (`SigmaFilter.open`) charts the mean of the selected series and the mean plus and minus
  one standard deviation as the series `mean`, `mean + sigma` and `mean - sigma`, in a chart with the operation panel
  of the other charts; it charted the static rows that only `SigmaFilter.main` fills, so the chart was empty.
- `opentsx-core`: `SingleTsRISTool.processReihe` writes the R_q of the result it adds to `risCollector`, the mean of
  the intervals between the values of the series that it keeps, those that are not 0 and not below `offset`. It wrote
  the last value divided by the number of values instead, so
  the values 0, 2, 5 and 9 gave 2.25 instead of 3.0. A series that keeps no interval, for example the values 3, 3 and
  3, now writes NaN and adds nothing to `risCollector`.
- `opentsx-core`: `ReturnIntervallStatistik2.addDistData` refuses an interval that is NaN or infinite with
  `addDistData: interval <k> of <name> is <value>; expected a finite interval`, as `TSPropertyTester` does, and a
  refused call adds nothing. An infinite interval made R_q infinite and put every interval into the first scaled bin,
  and a NaN interval was left out without a message.
- `opentsx-core`: for a result that is not a container, `calcMessreihen()` of `ReturnIntervallStatistik2` and
  `TSPropertyTester` replaces the series of an earlier call, so a second call, for example from `showData`, does not
  double every point. `mrVerteilungSkaliert` holds one point per scaled bin at the center of the bin, (i + 0.5) *
  scale / binning in units of R_q, and with an `offset` above 0 it leaves out the bins that start below offset / R_q.
  Before, it held two interleaved point sets, and the scaled one sat at the integer part of i / scale.
- `opentsx-core`: the header that `EntropieTest.main` prints names the two tab-separated columns of its rows:
  `length` and `cc`. The MI column, which was always 0.0, and its series in the chart are removed, and so is the
  column that repeated the 50 bins no computation used.
- The Grafana script of the opentsx-lg MQTT example (`influxdb-grafana/__run_container_01.sh`) sets the admin password
  inside its container `docker-influxdb-grafana` from `GRAFANA_ADMIN_PASSWORD` on standard input (`grafana-cli admin
  reset-admin-password --password-from-stdin`) and exits 2 without the variable, where it opened a shell in a
  hard-coded container id and then ran `grafana-cli admin reset-admin-password admin` on the host.
- opentsx-lg: the MQTT example `01_bootstrap.sh` installs `paho-mqtt==1.6.1` with `pip3 install --user` instead of an
  unpinned `sudo pip3 install paho-mqtt`. It no longer runs `mqtt sub`, which stayed subscribed until interrupted, so
  the script reaches the connector installs. `tools/test_lg_python.py` runs it with stubs and checks both.
- opentsx-lg: `src/main/k8s/krake-app/echo-demo.yaml` pulls `registry.k8s.io/echoserver:1.10` instead of the frozen
  `k8s.gcr.io`, and has no `env` entry without a name, which the Kubernetes API server rejected. `DockerImageTest`
  fails on a manifest under `src/main/k8s` with a `- name:` entry that has no value.
- `deploy/k3s/11-import-images.sh` checks the nerdctl command (`NERDCTL`, default `nerdctl`) and `ssh` before it saves
  an image. A missing tool exits with 1 and `nerdctl: not found; install it, set NERDCTL or choose another method with
  --method` or `ssh: not found; install an ssh client or choose another method with --method`. Before, bash reported
  `command not found`, the other side of the pipe ran anyway and the script exited with 127.
- `deploy/k3s/50-status.sh`, `70-backup.sh` and `71-restore.sh` no longer report a cluster they cannot reach as a
  missing namespace. They show the error of kubectl and exit with 1 and `namespace opentsx: cannot read it; check the
  cluster with kubectl get namespace opentsx`. A namespace that does not exist still gives `namespace opentsx: not
  found; <what to do>`. For `70-backup.sh` that is `check OPENTSX_NS in deploy/k3s/.env and the kubectl context, or
  deploy it first with 30-deploy.sh`.
- `opentsx-core`: `show Chart` of `MacroTrackerFrame` opens the chart of every operation node the chart panel
  recorded; `TEST Normal Distribution` opens its t-value chart. Only `Profile`, `ENTROPY` and `Shuffle` nodes found
  their chart before.
- `opentsx-core`: `ReturnIntervallStatistik2.addDistData` counts a negative interval that it keeps, which happens only
  when `offset` is below 0, in the first bin of its counts, and an interval whose scaled bin falls below 0 in the
  first scaled bin, as `TSPropertyTester` does. It threw `ArrayIndexOutOfBoundsException`.
- `opentsx-core`: `HaeufigkeitsZaehlerDouble.getHistogramNORM()` names its series with the label set by `setLabel`, as
  `getHistogram()` does, so normalized histograms with the same number of values no longer share the name
  `HISTOGRAM/<count>`. Without `setLabel` the name stays `HISTOGRAM/<count>`.
- `tools/test_lg_scripts.py` reads the image or connector of every `docker run`, `docker create`, `docker pull`,
  `docker container run`, `docker container create`, `docker image pull`, `confluent-hub install` and `confluent
  connect plugin install` in the opentsx-lg scripts. This includes a second command after `&&`, `||`, `;` or `|` on
  the same line. It skips only the options it lists. Any other option before the image fails with `<file>:<line>:
  <option>: unknown option before the image; add it to the option lists of tools/test_lg_scripts.py`. Before, the
  check read the value of an unlisted option as the image and passed it, for example `h:127.0.0.1` of `--add-host`.
- `tools/test_lg_scripts.py` removes only real comments from an opentsx-lg shell line, so a `#` inside quotes no
  longer hides a later `cd` without a guard. The check also covers a `cd` after `!`, `command`, `builtin` or `time`.
- opentsx-lg: when `mqtt-generator-gist/mqttgen.py` cannot reach the broker, it exits 1 with `<host>:<port>: cannot
  connect to the MQTT broker (<cause>); start the broker first, for example with 02_run_mqtt_broker.sh`. Before, it
  stopped with `TypeError: not enough arguments for format string`.
- opentsx-lg: `src/main/connect-cfg/deploy_connector.py` sends its POST with urllib from the Python standard library
  instead of the ansible helper that nothing installed. It prints `New connector debug_c1 created.` only after HTTP
  200 or 201. On any other answer it exits 1 with `debug_c1: POST http://127.0.0.1:8083/connectors answered HTTP
  <status> <body>; the connector was not created, check the log of the Kafka Connect worker`. When it cannot connect,
  it exits 1 with `http://127.0.0.1:8083/connectors: cannot connect (<cause>); start Kafka Connect on 127.0.0.1:8083
  first`. When Kafka Connect accepts the connection and sends nothing for 10 s, it exits 1 with
  `http://127.0.0.1:8083/connectors: no answer within 10 s; check the log of the Kafka Connect worker`.
- opentsx-lg: the MQTT example `01_bootstrap.sh` runs with `set -euo pipefail` and stops at the first command that
  fails. A failed `pip3`, `mosquitto_passwd` or `confluent-hub` no longer runs on to the next step.
  `tools/test_lg_python.py` checks each of them with a failing stub.
- The archived echo demos `archive/demos/opentsx-clusters/krake-container/echo-demo.yaml` and the `mmdc-mrc` scenario3
  manifests of mdc1, mdc2 and mdc3 pull `registry.k8s.io/echoserver:1.10` instead of the frozen `k8s.gcr.io`.
- `tools/test_check_tracked_config.py` runs the git commands of its temporary repository without the caller's `GIT_*`
  variables. It now passes when the suite runs with `GIT_INDEX_FILE` or `GIT_DIR` set.
- The statistics processor docs and the comments of `deploy/k3s/base/stats.yaml` name `40-demo.sh kafscale-check` as
  the check that detects an unreachable broker. The CronJob `opentsx-synthetic-check` runs every 10 minutes only when
  `20-configure.sh` was run with `MONITORING=true`, which is off by default. The operations guide says the same in its
  'What to watch' table and in runbook R1.
- `deploy/k3s/70-backup.sh` removes the files of a run that fails before it writes MANIFEST, including the copy of
  `.env` with its secrets, and removes the backup directory when the run created it. A rerun with the same `--out`
  then gets past `<dir>: exists and is not empty`. A directory that was not empty when the run started is still
  refused and keeps its files. When another writer put files into the directory during the run, it stays, with `<dir>:
  not removed, it holds files this run did not write; remove them, or give a new or empty directory with --out`.
  Before, a failed run left `env`, `opentsx-config.yaml` and archives behind.
- `deploy/k3s/71-restore.sh` with `env` leaves `.env` and `.env.before-restore` as they are when `.env` already equals
  the backup's env, and prints `<env file>: already the backup's env; <env file>.before-restore left as it is`.
  Before, a rerun after a failed step copied the restored `.env` over `.env.before-restore`, the only copy of the
  previous configuration.
- `deploy/k3s/30-deploy.sh` and `90-teardown.sh` stop with exit code 1 and `namespace <namespace>: cannot read it;
  check the cluster with kubectl get namespace <namespace>` when kubectl cannot read the namespace. Before,
  `90-teardown.sh` took an unreachable cluster for a missing namespace and exited 0, and `30-deploy.sh` skipped
  deleting the setup Jobs. `deploy/k3s/lib.sh` gets `namespace_exists`.
- `deploy/k3s/71-restore.sh` checks the namespace and the PVCs before it replaces `deploy/k3s/.env`, so an unreachable
  cluster or a missing PVC leaves `.env` as it is. `--only env` alone still needs no cluster.
- `deploy/k3s/71-restore.sh` and `90-teardown.sh` without `--yes` exit with code 1 and `<script>: no answer on
  standard input; pass --yes to run without the question` when standard input ends before an answer. An answer without
  a newline at the end now counts as given. Before, both cases exited 1 without a message, even after the answer `y`.
- `deploy/k3s/70-backup.sh` refuses a `--out` directory that it cannot list with `<dir>: cannot list it; see the error
  above, then give a new or empty directory with --out` and no longer takes it for an empty one, so it no longer
  writes into a directory that holds an older backup and overwrites its files.
- `opentsx-core`: the panel `DFA` stops with `DFA of <series>: <n> values give <k> window sizes in the fit range; use
  from 144 to 3160031 values` in a dialog, opens no chart and records nothing when fewer than two window sizes fall
  between 16 and 3162, the fit range of alpha. That holds for every series of fewer than 144 or more than 3160031
  values. It opened a chart with alpha NaN.
- `opentsx-core`: `Auto-Correlation`, `Profile`, `Diffs`, `Returns (Rel-Diff)`, `Histogram per series`, `NORM max=1`,
  `ENTROPY`, `CDF`, `ln`, `DFA` and `Shuffle` show `<name> of <label>: cannot open the chart (<cause>); give each
  series its own label` in a dialog and record nothing when their chart cannot open, for example for two selected
  series with the same label. `Shuffle` logged the failure and went on; the others threw `IllegalArgumentException` on
  the event dispatch thread. `CDF` names each series `<series>_CDF`. It named them `unnamed - <date time>`, so the CDF
  chart of two series with their own labels did not open when both were computed in the same second.
- docs/datamodel-2026/01-ANALYSE-Datenmodell.md no longer lists a `pushTSOItemsToKafka` path that writes Gson JSON to
  `OpenTSx_Episodes`; that code was commented out in 3.0.1 and is deleted. The table names
  `TSOEventProducer.pushTSOItemsToKafka`, which writes JSON `{ts,value}` to `refds_events_topic_<TAG>`.
- `opentsx-core`: `SingleTsRISTool` and the `_addData` methods of `ReturnIntervallStatistik2`, `TSPropertyTester` and
  `RISTool` treat a series without values like a series with one value: it adds no interval, R_q is NaN,
  `mrVerteilungSkaliert` stays empty and a container skips the result. The DEBUG line `computing intervals` logs
  `intervals=0` for it. They threw `NoSuchElementException` before.
- `opentsx-core`: `ReturnIntervallStatistik2` and `TSPropertyTester` keep every scaled bin of `mrVerteilungSkaliert`
  when `offset` is 0 or below. With `offset` 0 and an R_q of 0, for example `TSPropertyTester` intervals that are all
  0, the series was empty while a container that added the result showed its bins. With `offset` below 0 and a
  negative R_q, the first bins were left out. A result that keeps no interval has an empty `mrVerteilungSkaliert`.
- `opentsx-core`: the `_addData` methods of `ReturnIntervallStatistik2` and `TSPropertyTester` refuse two times more
  than `Double.MAX_VALUE` apart with `_addData: time <k> of <name> is <value>, more than Double.MAX_VALUE after time
  <k - 1> at <previous value>; expected times whose difference is finite`. A refused call, also one on a container,
  keeps the intervals of the call before.
- opentsx-lg: `mqttgen.py` now exits 1 when it cannot read its configuration, with `<file>: cannot read the
  configuration file (<cause>); pass the path of a JSON configuration file such as config.json`. It exits 2 on a usage
  error. Both cases used to exit 0.
- opentsx-lg: `src/main/connect-cfg/deploy_connector.py` posts `{"name": "debug_c1", "config": <config>}` with the
  `config` object of `cfg.json`, not the whole file. When `cfg.json` has no `config` object it exits 1 and sends no
  request. The README says the 10 s timeout applies to the connect and to each read separately.
- opentsx-lg: `DockerImageTest` joins Dockerfile lines that end with a backslash before it checks an instruction, and
  it names the first line of the instruction. It skips comment lines and empty lines inside an instruction as Docker
  does. A multi-line `ENV` with `<key>=<value>` lines or a `CMD` followed by an exec-form array is no longer reported.
  A comment that ends with a backslash no longer hides the instruction after it.
- tools: `tools/test_lg_scripts.py` ignores a `cd` or an image install command inside quotes, also the quoted text
  around a command substitution such as `echo "a; cd b $(pwd)"`; a `cd` inside the substitution is still checked. When
  the arguments of a command do not split into words, it reports a finding instead of stopping with `ValueError`.

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
- The retrieval server caps `topK` at 1000, a search pattern at 100000 values, `limit` at 20000 and the episodes one
  request selects at 20000 (`EpisodeService.MAX_TOP_K`, `MAX_PATTERN_LENGTH`, `MAX_EPISODES`). Beyond a cap it answers
  400 naming the cap. A search compares, and an answer carries, at most 20000 episodes.
- `deploy/k3s/20-configure.sh` refuses `S3_ACCESS_KEY`, `S3_SECRET_KEY`, `OPENMETADATA_JWT`,
  `RETRIEVAL_DAG_RUN_TOKEN`, `SAAS_DB_PASSWORD`, `SAAS_SECRET_KEY` and `SAAS_ADMIN_PASSWORD` as arguments with exit
  code 2 and writes nothing. Export them or write them into `deploy/k3s/.env`.
- `deploy/k3s/20-configure.sh --print` leaves out every `kind: Secret`.
- With `SAAS=true`, `deploy/k3s/20-configure.sh` refuses the published example values that the SaaS backend refuses at
  start. It reads them from `PUBLISHED_EXAMPLES` in `opentsx-saas-backend/app/core/config.py`.
- The MinIO bucket Job `opentsx-minio-bucket` gives `mc` the S3 keys in the environment variable `MC_HOST_lake`
  instead of as arguments. It stops waiting for MinIO after 60 tries 3 s apart with a message that names no namespace,
  and has `activeDeadlineSeconds: 900`. With `LAKE_MINIO=true`, `20-configure.sh` refuses S3 keys that hold characters
  other than letters, digits, `.`, `_`, `~` and `-`.
- The history archive page creates the `opentsx-market` Secret from a mode-600 file with `--from-env-file`, and the
  configuration chapter reads `OPENMETADATA_JWT` with `read -rs`. The secrets check of
  `deploy/k3s/test/test-20-configure.sh` now covers `docs/`, the top-level and module READMEs and the READMEs under
  `deploy/k3s/`.
- `bin/opentsx metadata --token` exits with code 2 instead of sending the token, so the bot token no longer shows in
  the process list or the shell history. Set it in `OPENMETADATA_JWT`, `OPENTSX_OPENMETADATA_TOKEN` or a properties
  file.
- SaaS backend: the text of a retrieval server error other than 400 stays in the backend log and no longer reaches the
  user; a 5xx answers 502 `retrieval server: answered HTTP <status>; see the log of the backend`, a 404 for a
  generator 503 `retrieval server: does not offer this route or system; see the log of the backend`.
- The SaaS backend creates its database engines with `hide_parameters=True`, so a database error and the 500 log line
  no longer contain the bound parameters of the statement, such as an email address or a password hash.
- `retrieval-server --dag-runs true` without a run token in `OPENTSX_DAG_RUN_TOKEN` exits with code 2 instead of
  letting anyone who reaches the port deploy and stop DAGs; `--open-dag-runs` accepts runs without a token and listens
  on 127.0.0.1 only, and `--dag-run-token` on the command line exits with code 2 and names the variables.
- The history archive refuses a source, series name or station id with a `.` or `..` part or a character other than
  letters, digits, `.`, `_` and `-`, and `market mosmix-archive` refuses a MOSMIX station id that is not letters and
  digits, so `market import --name ../x` or `market mosmix-archive --stations ../x` exits with 2 instead of writing
  outside the archive directory.
- `tools/check_tracked_config.py` fails on a tracked file under `opentsx-lg/src/main/docker-compose/output/` and on a
  personal home path such as `/Users/<name>` or `/home/<name>` in any tracked text file. The service account homes
  `Shared`, `jovyan`, `ubuntu`, `ec2-user` and `user` are allowed.
- Personal home paths in the archive and in the opentsx-lg scripts are replaced with a quoted `"$HOME/..."` or a
  placeholder. The TSCache NetBeans project files and a Kafka Connect offset file, which only worked on one machine,
  are deleted.
- opentsx-lg: the image of the `Docker` profile (`opentsx/time-series-generator`) copies no Kafka client profile, and
  neither the image nor the module jar contains `ccloud.props`. The container reads the profile at run time from
  `/etc/opentsx/config/opentsx-local.props` (`OPENTSX_PRIMARY_CLUSTER_CLIENT_CFG_FILE_NAME`) and stops when neither
  that file nor its `.example` template is mounted. The `ccloud.props` templates under `src/main/resources/config/`
  and `src/main/docker-compose/config/` are removed, and `ccloud.props` is git-ignored in opentsx-lg.
- opentsx-lg: the MQTT password file `src/main/mqtt-example/volume1/docker/mqtt/config/passwd` is no longer tracked
  and is git-ignored. `src/main/mqtt-example/01_bootstrap.sh` creates it with `mosquitto_passwd` before
  `02_run_mqtt_broker.sh` mounts it.
- `.gitignore` ignores a file named `ccloud.props` anywhere in the repository, so a filled-in client profile such as
  `config/ccloud.props` is not committed.
- `tools/check_tracked_config.py` fails on a Confluent Cloud resource id in a tracked text file (`lkc-`, `pkc-`,
  `psrc-`, `lsrc-`, `env-` or `sa-` and 5 or more lower-case letters or digits with at least one digit) with
  `<path>:<line>: <id>: looks like a Confluent Cloud resource id; write a placeholder such as lkc-xxxxx`.
- `tools/check_tracked_config.py` fails on a tracked file named `ccloud.props` and on the tracked MQTT password file
  `opentsx-lg/src/main/mqtt-example/volume1/docker/mqtt/config/passwd`, also when one was added with `git add -f`.
- opentsx-lg: `mqtt-generator-gist/mqttgen.py` no longer prints the MQTT password from its configuration. It prints
  `MQTT broker <host>:<port>, user <username>`.

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
- `bin/opentsx market load` wrote every episode again on each run, because each run gave it a new random id. It now
  uses the identity of `publish` (series, start, value count), so the Iceberg store skips what it already holds.
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
