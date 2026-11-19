# 12. Operations

## 12.1 Local stacks

| File                          | Starts                                                                                                                            | Use                                         |
| ----------------------------- | --------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------- |
| `docker-compose.kafscale.yml` | etcd, MinIO (S3, :9000/:9001), private broker and KafScale Proxy v1.6.0 (:9092)                                                   | **primary development stack** (v2)          |
| `docker-compose.local.yml`    | Postgres, Redis, Confluent Platform (Kafka, Schema Registry), HBase + OpenTSDB; profile `full` adds the SaaS backend and frontend | OpenTSDB store tests, 2.x tools, SaaS stack |

Both stacks bind port 9092, so do not run them at the same time.
Use the [Proxy setup](../../opentsx-kafscale-conformance/README.md#start-the-local-proxy) to build the pinned
transport bundle and supply private MinIO credentials. OpenTSx producers use Apache Kafka Broker or KafScale Proxy.

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

The Rust CI definition adds native x86_64 and ARM64 jobs and a separate static
musl build matrix for `x86_64-unknown-linux-musl`, `aarch64-unknown-linux-musl`
and `armv7-unknown-linux-musleabihf`. The
[artifact checker](../../tools/rust_runner_artifacts.py) validates ELF
architecture, absence of a dynamic interpreter or needed libraries, executable
version, SHA-256 and actual test summaries. Reports retain source and tool
versions. The `rust-native-<target>` and `rust-static-<target>` artifacts have
seven-day retention. The ARMv7 job executes tests under QEMU and labels its
result as emulated. It does not satisfy the physical ARMv7 requirement in
[TASK-020 B1.2](../../EVOLUTION/TASK-020-wayang-ts-operators.md#b1-rust-runner-foundation).
Job definitions alone do not establish a successful cross build; inspect the
run's artifacts and reports.

The isolated signed Rust profile has a separate
[operating guide](../devguide/integration/rust-runner-b1.md). Its pass-through
loop uses explicit finite-cut caps and acknowledged progress checkpoints.
Rust worker and checkpoint owners explicitly release their file locks when they
close in the acquiring process, even while inherited descriptors remain open.
The existing local replay and HTTP cleaning workers keep their own request limits
and state formats. The disposable B1 KafScale acceptance harness supplies
protocol evidence; the separate [B3 site profile](../devguide/integration/rust-runner-b3.md)
binds that prior evidence to its exact executable and endpoint, performs fresh
job checks and submits signed site conformance.

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
v1.6.0 source with Go 1.25.2 and starts a disposable Proxy, loopback-only in-memory broker and independent etcd
metadata for every condition. Its producers publish through the Proxy. It executes the actual `TsxIncrement`
passthrough and `dq.checks`
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

## 12.8 Evaluate the bounded Track A baselines

Use the [A5 harness](../../tools/wayang_track_a_evaluation.py) to compare
centralized and federated statistics and the bounded Java catch-up paths.
You need JDK 17 or 21, Maven 3.9 or later, Python 3.10 or later, Git and Go
with `go1.25.2` available. Network access is needed for dependencies. Start
from a clean checkout with no untracked runtime, fixture or tool sources.
Stop other builds and benchmarks before measuring; the harness does not
reserve CPU on a shared host.

Run from the repository root. `<jdk-home>` is the selected JDK installation
directory and `<new-result-directory>` is an unused output directory. Read
the arguments, then run the complete matrix:

```bash
python3 tools/wayang_track_a_evaluation.py --help
python3 tools/wayang_track_a_evaluation.py \
  --output <new-result-directory> \
  --java-home <jdk-home>
python3 -m json.tool <new-result-directory>/metadata.json
```

The default matrix contains 63 sequential conditions: three repetitions of
1/3/10-site federation and both runtimes for each decode, clean and statistics
chain at 1/4/12 partitions. Every condition uses the complete fixed-seed
`ostsee-demo.yaml` scenario, a fresh execution JVM with `-Xms512m -Xmx2g`
and disposable in-memory brokers. No warm-up samples are discarded.
The tool builds KafScale v1.6.0 from pinned source with Go 1.25.2, freezes the
Java classpath and checks source hashes and HEAD before and after execution.
The `--pilot` flag labels a reduced matrix as excluded from A5.

Every Kafka client connects to a KafScale Proxy. The shared transport builder
applies guarded metadata and Proxy protocol overlays to the pinned source
and runs the Proxy and metadata package regressions before compilation. Public
metadata reads refresh durable state and propagate read failures. Each cluster
launcher starts one private broker, one Proxy and its own embedded etcd.
`metadata.json` records the transport source and binary hashes. Proxy and
launcher resources are retained as subprocess rows, outside the broker RSS
column. This fixture qualifies a local single-broker transport only.

Federation keeps complete turbines on one site and runs the signed bounded
profile. The central baseline executes statistics over its received raw
records. Streaming reads a complete acknowledged backlog: Track A runs one
`TsxIncrement`, native clean runs `VectorProcessor`, native statistics runs
`StatsProcessor`, and native decode uses the SOE decoder in a plain Kafka
loop with canonical re-encoding and acknowledged writes. Both decode paths
include publication; this is not a decoder-only CPU benchmark. The native
decode baseline is not a `VectorProcessor` binding.

Read the generated `REPORT.md`, top-level `metadata.json` and the condition
directories after the run. Federation directories are
`federation/k<sites>-r<repetition>`; streaming directories are
`streaming/<chain>-p<partitions>-<runtime>-r<repetition>`.
Metadata records the exact commands, hardware, OS, JDK, heap, broker source,
artifact sizes and source/runtime hashes.

| File in a condition directory | Evidence |
| --- | --- |
| Federation `bytes.csv` | Per-site Kafka, HTTP and TLS counters |
| Federation `windows.csv` | Central and federated reference comparisons |
| Federation `run.json` | Correctness, topology and separate central clocks |
| Streaming `measurement.csv` | Wall time, rates and latency percentiles |
| Streaming `records.csv` | Contributor ids and request/ACK clocks |
| Streaming `produce-attempts.csv` | Partitions, offsets and response codes |
| Streaming `case.json` | Runtime, counts, CPU, I/O and checkpoint result |
| `resources.csv` | Once-per-second RSS and cumulative CPU samples |

The directory placeholders identify the matrix dimensions: sites are 1, 3
or 10; repetitions are 1 to 3 by default; chains are `passthrough`, `clean`
or `stats`; partitions are 1, 4 or 12; runtimes are `wayang` or `java`.
Resource samples cover the driver, owned brokers and observed subprocesses
across setup, execution and independent validation. Short-lived processes
between samples can be missed. Sampled driver CPU is the largest observed
cumulative process CPU time, not the exact CPU-at-exit total. Job CPU time
includes the in-JVM observer and harness but excludes Go broker CPU.
Per-core rates divide by the recorded available logical CPUs.
`case.json` records process start, main entry, activation and job-end instants
separately. Its checkpoint field reports the verification result; the file
does not contain the checkpoint's partition cut.

Activation wall time includes runtime construction, planning or deployment,
processing, acknowledged output and checkpoint persistence. Independent
checkpoint inspection, output scans and reference checks are outside this
clock. Latency subtracts successful input Produce ingress from successful
output Produce ingress at the loopback observer. Aggregate outputs use the
latest contributing input. Kafka event and envelope timestamps are not
transport clocks. Clean equivalence checks ids, keys, event times and values,
without requiring equal `createdAt` or Kafka envelope timestamps.

The finite backlog profile records `intervalSeconds: 0`; the paced
5/30/60-second A1.7 measurements remain separate evidence in section 12.7.
Kafka Produce frames, aggregate HTTP bodies and TLS client-stream bytes are
distinct protocol layers, not total network egress. Their route clocks
cover different work. The harness applies no threshold and does not qualify
the 30-kill recovery protocol, durable storage, remote networking or Track B.
Read [concept 20, section 14.4](../datamodel-2026/20-WAYANG-TS-OPERATORS.md#144-evaluate-the-bounded-track-a-baselines)
for the protocol and scope.

The [recorded A5 run](../datamodel-2026/measurements/wayang-track-a-evaluation-2026-11-18/REPORT.md)
completed all 63 conditions on 2026-11-18. Its nine federation and 54
streaming conditions passed the correctness and clock checks. Read the
report with its case CSV files and metadata; the measured local bounded
profile does not replace the separate paced, recovery or Track B protocols.

## 12.9 Execute the local Rust catalog profile

Worker capability discovery reports the legacy profile in `operators` and the
B2 profile in `catalogExecution.operators`. The Java UI driver retains its
four-operator legacy contract.

Use the [B2 operating guide](../devguide/integration/rust-runner-b2.md) for
trusted-local catalog assignments. You need Rust 1.99.1, native build tools,
an existing Apache Kafka or KafScale Proxy endpoint and preprovisioned input
and output topics. Keep the HTTP worker on an isolated test network.

The optional local request `context` supplies explicit input inventories,
clean-stage target metadata and a cross-correlation product budget. One
assignment owns the whole input topic. The catalog adapter executes clean
operations, window statistics, segmentation, SAX, vector alignment, bounded
cross-correlation and DQ findings with typed output and retained-state checks.
Output acknowledgements precede checkpoint advancement. Read the guide for
the limits and the difference between bounded completion and continuing cuts.

The signed B1 profile remains pass-through only. Java UI deployment has its
own smaller admitted subset. Catalog kernels and local parity evidence do not
grant fleet execution or production rollout rights.

Physical ARMv7 qualification remains open in
[issue 294](https://github.com/kamir/OpenTSx/issues/294). Native ARM64 and
supplementary ARMv7 QEMU tests do not satisfy B1.2. Run the shared Avro and
identity cases on an actual ARMv7 board and record its hardware, OS, source
revision and results before closing B1.2 or B1.

## 12.10 Operate signed bounded Rust statistics

Use the [B3 operating guide](../devguide/integration/rust-runner-b3.md) for the
separate `opentsx.rust-site-config.v1` contract. You need a registered TLS 1.3
mTLS plan service, separately pinned Ed25519 driver/site roles, locally allowed
jobs and topic policy, a private state directory, explicit limits and distinct
site/signal Proxy endpoints. Provision all topics and local qualification
before starting the ordinary runtime.

The qualification generator consumes actual runner/contract logs, site-broker
protocol results and Java/Rust producer placement. It binds those checks to
the executable, catalog, state ABI, endpoint, repository vectors and validity.
Do not give the ordinary process admin or arbitrary probe-topic rights.
Fresh `conform` checks the exact authenticated challenge, local policy, live
metadata, retained cuts, fingerprints and complete series inventory before
submitting its signed report. The guide identifies prior and fresh evidence.

`agent` receives signed bounded decode-to-statistics plans. It retains outcomes
before acknowledgement and advances the delivery cursor afterward. Recovery
retries the original acknowledgement; it does not reactivate the command.
Current conformance and qualification still gate new activation. Do not
replace accepted bytes, configuration or private state to bypass a refusal.

Site aggregate publication completes before signal forwarding. The immutable
site outbox and acknowledged forwarder retain complete SOE records, aggregate
headers and closed-cut evidence. Keep its retained log available from offset
zero. A lost target acknowledgement can repeat a key/revision; the receiving
inventory and completeness coordinator validate and deduplicate those records.
Earlier incomplete revisions stay immutable when final revisions arrive.

The SSH certificate fixes the executable and local configuration, uses
`run --plan -`, and accepts only the signed plan on standard input. Pin host
keys and use the non-root restricted account. The guide links the eight-session
test and the full-W offline regression.

The signed profile admits only `decode.avro-soe` and
`stats.welford-ddsketch` with alpha 0.01 and tumbling windows. The local
B2 operator set, continuous/mixed fleet qualification, production rollout and
physical ARMv7 B1.2 acceptance remain separate.
