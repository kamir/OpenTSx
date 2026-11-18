# 12. Operations

## 12.1 Local stacks

| File | Starts | Use |
|------|--------|-----|
| `docker-compose.kafscale.yml` | etcd, MinIO (S3, :9000/:9001), KafScale broker v1.6.0 (:9092) | **primary development stack** (v2) |
| `docker-compose.local.yml` | Postgres, Redis, Confluent Platform (Kafka, Schema Registry), HBase + OpenTSDB; profile `full` adds the SaaS backend and frontend | OpenTSDB store tests, 2.x tools, SaaS stack |

Both stacks bind port 9092, so do not run them at the same time.

Running the tools as services on Kubernetes (k3s) against an existing KafScale
cluster is described in the [DevOps guide](../devops/README.md), including images,
manifests, setup, sizing and demos (scripts in
[`deploy/k3s/`](../../deploy/k3s/README.md)).

## 12.2 Configuration

The v2 modules take their configuration from command-line options and constructor parameters. There are no hidden
global settings.

| Component | Settings |
|-----------|----------|
| KafScale/Kafka clients | `KafScaleClients.producerProps/consumerProps(bootstrap)` (idempotence off, `max.in.flight=1`, zstd, no auto-commit) |
| Iceberg | standard Iceberg catalog properties (`type=rest\|jdbc\|hive\|glue`, `uri`, `warehouse`, …); catalog name `opentsx` |
| OpenMetadata | `--om <url>/api`, token via `--token` or `OPENMETADATA_JWT` |
| Statistics processor | `--windows`, `--lateness`, `--alpha`, `--checkpoint` |
| Wind farm | scenario YAML, catalog YAML (`--catalog`) |

The 2.x tools in `opentsx-core`, `opentsx-lg` and `opentsx-connectors` use `ConfigManager`. It resolves each key in
this order, from highest to lowest priority:

1. JVM system property with the literal key.
2. Environment variable with the literal key.
3. Mapped environment variable: uppercase with `Locale.ROOT`, replace dots with underscores, and add `OPENTSX_`
   unless the result already starts with that prefix.
4. Explicit configuration file, or the selected profile when no explicit file is set.
5. `config/opentsx-default.properties` relative to the working directory.
6. Classpath resource `opentsx-default.properties`.
7. The default supplied by the caller.

Select a file with `opentsx.config.file`, or a profile with `opentsx.config.profile`; a profile loads
`config/opentsx-<name>.properties`, where `<name>` is the selected profile name. Each canonical selector uses the
JVM, literal environment and mapped environment lookup above before consulting its uppercase alias
(`OPENTSX_CONFIG_FILE` or `OPENTSX_CONFIG_PROFILE`). A file selector takes precedence over a profile selector.
Environment mapping is independent of the process language.

Files load once during class initialization. JVM system properties and environment variables are checked for
each requested key. Missing optional defaults in the working directory or classpath are allowed. A present default
that cannot be read or parsed, or any I/O or parse failure in an explicitly selected file or profile, stops
initialization. Each source is loaded into fresh `Properties` and merged only after loading and closing succeed;
partial properties from a failed source are not merged. Sources use the `Properties.load(InputStream)` format.

Loading failures raise `IllegalStateException` with `Failed to load configuration file: <path>` or
`Failed to load classpath configuration: <resourcePath>`, identifying the failed source without property values.
The first singleton initialization failure is wrapped in `ExceptionInInitializerError`. Correct the selected
source and restart the process; changing a selector after initialization does not reload files.

You need JDK 17 or later and Maven to run the configuration regression tests. From the repository root, run:

```bash
mvn -B -ntp -pl opentsx-core -am test \
  -Dtest=ConfigManagerTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

[`opentsx.properties.example`](../../opentsx.properties.example) lists the keys the code reads, including how the v2
modules are configured (command-line options and environment variables, no properties file). The 2.x Kafka client
profile is `config/opentsx-local.props` (git-ignored; set `OPENTSX_PRIMARY_CLUSTER_CLIENT_CFG_FILE_NAME` to use
another file). Without it, the template [`config/opentsx-local.props.example`](../../config/opentsx-local.props.example)
(localhost, KafScale-compatible) is used. Profiles with real hosts or credentials belong in `config/private/`
(git-ignored). This Kafka client file is separate from the `ConfigManager` defaults and profiles above.
The `ProductionConfig` onboarding demo also loads its own `./opentsx.properties`; it does not use `ConfigManager`.

## 12.3 State and restarts

| Component | State | On restart |
|-----------|-------|-----------|
| `BucketWriter` | none (an unsealed bucket stays OPEN) | write the bucket again; duplicates are dropped by `episodeId` |
| episode index | `FileEpisodeIndex` / `IcebergEpisodeIndex` | `EpisodeIndexer.rebuild` recreates it from the log |
| statistics processor | checkpoint file (positions + engine state) | resumes; outputs at-least-once, readers keep the latest per key |
| `EpisodeLogSync` | positions returned to the caller | persist them; writes are idempotent |

## 12.4 Testing and CI

```bash
mvn verify                                   # unit and integration tests (KafScale tests skipped)
KAFSCALE_BOOTSTRAP=localhost:9092 mvn verify # including KafScale tests
cd python-package && pytest                  # Python
```

GitHub Actions runs these jobs:

* Java 17 and 21,
* Python 3.10–3.13,
* KafScale conformance (broker built from source) with the wind-farm and statistics integration tests,
* the cross-language job: Java writes Iceberg and PyIceberg must read identical bytes; the Python client queries the
  Java retrieval server.

For a failed bounded JVM federation test, download `kafscale-capabilities` from the KafScale conformance job.
It includes `opentsx-wayang/target/surefire-reports/` and retained
`opentsx-wayang/target/federation-test-logs/**/brokers/*.log`. Fixtures retain their directories when construction,
test execution or broker shutdown fails; successful tests remove them. Only broker `.log` files are uploaded
from the fixture directories.

The separate `kafscale-broker-log` artifact contains `$RUNNER_TEMP/kafscale-broker.log`, the primary broker's
output. Both artifact upload steps use `if: always()`, so they run after test failures.

## 12.5 Security notes

* Never commit credentials. `config/private/` is in `.gitignore`.
* Treat any key that was ever committed as compromised and rotate it.
* Use bot tokens for OpenMetadata.
* The retrieval server has no authentication. Run it behind a gateway, or on localhost only.

## 12.6 Troubleshooting

| Symptom | Cause / fix |
|---------|-------------|
| enforcer error "requires Java 17" | use JDK 17 or 21 (`JAVA_HOME`) |
| KafScale tests skipped | set `KAFSCALE_BOOTSTRAP=localhost:9092` |
| consumer hangs or rejoins on KafScale | do not use consumer groups. OpenTSx tools use manual assignment; do the same in your own code |
| `offsetsForTimes` returns nothing | not supported by KafScale. Use the episode index or `ReplayRequest.range` |
| duplicate episodes after a retry | expected without an idempotent producer. Stores and readers deduplicate by `episodeId` |
| PyIceberg warns "v0 schema" | the catalog was created by the Java JDBC catalog. The warning is harmless |
| `bin/opentsx`: "module … is not built" | run `mvn -q install -DskipTests` first |

## 12.7 Measure Wayang increment latency

You need JDK 17 or 21, Maven 3.9 or later, Go, git, Python 3.10 or later, and
network access for dependencies. Run from a clean tracked and staged repository
checkout. Stop other builds and benchmarks on the host before measuring.
`<jdk-home>` is the selected JDK directory; `<new-result-directory>` is an unused
output directory.

The [harness](../../tools/wayang_increment_latency.py) builds pinned KafScale
v1.6.0 source with Go 1.25.2 and starts a disposable in-memory broker for every
condition. It executes the actual `TsxIncrement` passthrough and `dq.checks`
plus `resample PT1M` plans, with one partition. Run the default matrix:

```bash
python tools/wayang_increment_latency.py \
  --output <new-result-directory> \
  --java-home <jdk-home>
```

The matrix runs both chains at 5, 30 and 60 seconds, with 30 increments and three
repetitions. Its 18 conditions run sequentially and contain 540 increments.
Scheduled intervals alone take 4 hours 45 minutes; builds, startup and processing
add time. Every condition sends the complete fixed-seed `ostsee-demo.yaml`
simulation once, paced from 5% to 90% of each interval. Its first increment starts
after a full interval. Every condition starts a fresh JVM with
`-Xms512m -Xmx2g`; no warm-up samples are discarded.

Read `metadata.json` in the output directory for hardware, OS, JDK, source
hashes, the invocation, build commands and JVM commands. Every condition uses
private copies of the resolved JARs and class directories, verified by a SHA256
manifest. Matching local reactor JARs must equal their resolved OpenTSx
dependencies before copying. On macOS, `/usr/bin/caffeinate -i -w <pid>` prevents
idle sleep for the Python process; cleanup releases the assertion. Other systems
record that no sleep assertion is applied.

Read the generated `REPORT.md` and the case directories after completion:

| File | Evidence |
| --- | --- |
| `increments.csv` | Fetch, wall time, counts, lateness and percentiles |
| `records.csv` | Output/input ids, Produce clocks and acknowledgement clocks |
| `produce-attempts.csv` | Attempts, partitions, offsets and response codes |
| `case.json` | completed condition, clock origin and reference checks |

Produce time is the arrival of a complete Produce request at the loopback
protocol observer, before parsing and forwarding. Only acknowledged successful
partition writes contribute to latency. Kafka record timestamps stay at event
time and are not transport clocks. For a clean output, latency starts at the
latest contributing input Produce request; contributors share raw series identity
and overlap its event-time range. Passthrough matches the same episode id.
The observer rewrites broker endpoints only in Metadata responses and preserves
Produce payloads. Its parsing and forwarding overhead is part of the measurement.

The harness checks output ids, series keys, timestamps and values against
`SeriesStream` before completing a condition; passthrough also checks payload
bytes. A shared host has no reserved CPU or OS scheduling guarantee. These are
local, single-partition, in-memory-broker measurements, without a throughput,
durable-storage, remote-network or Track B comparison. No latency threshold is
applied. `--pilot` explicitly excludes a shortened run from the A1.7 baseline.

Read the [measurement report](../datamodel-2026/measurements/wayang-increment-latency-2026-11-17/REPORT.md)
for the recorded run and [concept 20, section 14](../datamodel-2026/20-WAYANG-TS-OPERATORS.md#14-evaluation-plan)
for the broader evaluation protocol.
