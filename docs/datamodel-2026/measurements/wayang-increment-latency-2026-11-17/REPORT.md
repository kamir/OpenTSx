# Wayang increment latency

Measured on 2026-11-17T00:09:53Z with openjdk version "21.0.8" 2025-07-15.

Mode: A1.7 baseline. Single-partition conditions run sequentially on a
disposable local broker.

OpenTSx commit: `6b2bfbc5c751a3179145663546b8e38241eaed76`. KafScale v1.6.0:
`2809a3a20bf40ed8a2b82cd93215cb59396efb0a`.

Hardware and OS: macOS-14.6.1-arm64-arm-64bit, arm64, 16 logical CPUs.

Hardware detail: Mac15,9 / Apple M3 Max / 51539607552. Explicit JVM heap:
`-Xms512m -Xmx2g`.

Produce time means arrival of a complete Produce request at the loopback
protocol observer, before forwarding. Only requests whose partition response
succeeded contribute to latency. Separate request and acknowledgement clocks are
retained.

Kafka record timestamps are event times and are preserved. They are not used for
transport latency. The observer rewrites only broker endpoints in Metadata
responses; Produce requests and payloads are forwarded unchanged. Measurements
include the observer's parsing and forwarding overhead.

First fetch means the first Fetch request for the input topic during
TsxIncrement.run, measured from its invocation. Wall time includes source
metadata, planning, reading, processing, acknowledged writes and checkpoint
persistence.

Inputs are the complete ostsee-demo.yaml simulation, fixed seed 42, paced at
uniform phases from 5% to 90% of each interval. Each condition writes every
input episode exactly once. The first increment starts after one full interval.

Clean outputs join raw metric and tags to every input episode whose event-time
range overlaps the output range. Latency starts at the latest contributing input
Produce request; records.csv retains every contributing episode id.

Percentiles use nearest rank. Empty output sets have blank percentiles, with
output count zero. Tables round milliseconds to three decimal places; CSV files
retain every recorded digit.

Case P means passthrough. Case C means dq.checks followed by resample PT1M. The
number is the interval in seconds; r1, r2 and r3 identify repetitions.

## Read counts

| Case   | Inc | In   | Out  |
| ------ | --: | ---: | ---: |
| C30-r1 |  30 | 3600 | 3600 |
| C30-r2 |  30 | 3600 | 3600 |
| C30-r3 |  30 | 3600 | 3600 |
| C5-r1  |  30 | 3600 | 3600 |
| C5-r2  |  30 | 3600 | 3600 |
| C5-r3  |  30 | 3600 | 3600 |
| C60-r1 |  30 | 3600 | 3600 |
| C60-r2 |  30 | 3600 | 3600 |
| C60-r3 |  30 | 3600 | 3600 |
| P30-r1 |  30 | 3600 | 3600 |
| P30-r2 |  30 | 3600 | 3600 |
| P30-r3 |  30 | 3600 | 3600 |
| P5-r1  |  30 | 3600 | 3600 |
| P5-r2  |  30 | 3600 | 3600 |
| P5-r3  |  30 | 3600 | 3600 |
| P60-r1 |  30 | 3600 | 3600 |
| P60-r2 |  30 | 3600 | 3600 |
| P60-r3 |  30 | 3600 | 3600 |

## Read transport latency

| Case   | p50 ms    | p95 ms    | p99 ms    |
| ------ | --------: | --------: | --------: |
| C30-r1 | 16333.326 | 27811.522 | 28678.026 |
| C30-r2 | 16333.146 | 27803.390 | 28670.056 |
| C30-r3 | 16332.490 | 27805.511 | 28668.160 |
| C5-r1  |  2949.204 |  4809.995 |  5013.378 |
| C5-r2  |  2951.741 |  4811.275 |  5013.812 |
| C5-r3  |  2950.326 |  4855.297 |  5012.774 |
| C60-r1 | 32061.925 | 55015.964 | 56860.624 |
| C60-r2 | 32146.826 | 55097.373 | 56863.210 |
| C60-r3 | 32150.019 | 55103.863 | 56858.038 |
| P30-r1 | 16327.593 | 27804.124 | 28681.296 |
| P30-r2 | 16320.409 | 27794.920 | 28668.717 |
| P30-r3 | 16333.947 | 27809.460 | 28678.016 |
| P5-r1  |  2951.886 |  4848.306 |  5012.427 |
| P5-r2  |  2951.445 |  4810.376 |  5013.087 |
| P5-r3  |  2948.809 |  4849.285 |  5012.320 |
| P60-r1 | 31963.172 | 54914.229 | 56859.802 |
| P60-r2 | 31963.854 | 54924.140 | 56852.063 |
| P60-r3 | 31971.266 | 54919.771 | 56857.447 |

## Read increment timings

| Case   | Fetch p50 | Wall p50 | Late max |
| ------ | --------: | -------: | -------: |
| C30-r1 |    10.217 |  548.308 |   10.127 |
| C30-r2 |    10.685 |  545.681 |   10.145 |
| C30-r3 |     9.829 |  541.551 |   10.116 |
| C5-r1  |     9.064 |  340.363 |   10.051 |
| C5-r2  |     9.068 |  342.704 |   10.105 |
| C5-r3  |     8.973 |  343.481 |   10.107 |
| C60-r1 |    10.698 |  554.325 |   10.051 |
| C60-r2 |    11.700 |  551.906 |   10.210 |
| C60-r3 |    10.973 |  555.358 |   10.149 |
| P30-r1 |    10.421 |  546.814 |   11.941 |
| P30-r2 |     9.514 |  544.646 |   10.124 |
| P30-r3 |     9.563 |  545.029 |   10.155 |
| P5-r1  |     8.943 |  343.108 |   13.170 |
| P5-r2  |     8.857 |  343.367 |   10.104 |
| P5-r3  |     8.780 |  344.701 |   10.129 |
| P60-r1 |     9.985 |  555.846 |   10.237 |
| P60-r2 |     9.431 |  544.174 |   10.129 |
| P60-r3 |     9.889 |  550.258 |   10.116 |

Increment timings are milliseconds. Fetch and wall columns show medians across
increments; Late max is the maximum observed delay after a scheduled increment
start.

## Repeat the measurement

You need JDK 17 or 21, Maven 3.9 or later, Go, git, Python 3.10 or later, and
network access for dependencies. The script selects Go 1.25.2 and builds the
pinned KafScale v1.6.0 source. It uses only the existing project dependencies.

Run from the repository root. Read the arguments with this command:

```bash
python tools/wayang_increment_latency.py --help
```

Select the JDK with --java-home and a new result directory with --output. The
default matrix runs both chains at 5, 30 and 60 seconds, 30 increments per
condition, and three repetitions. Normal runs require a clean tracked and staged
checkout. --pilot labels a shorter run as excluded from the baseline.

metadata.json records the top-level invocation arguments, working directory,
interpreter, source hashes, build commands and exact JVM command. Repeat that
invocation with a new --output directory.

Every JVM condition uses a private copy of its class directories and dependency
JARs. metadata.json records the SHA256 manifest. Available reactor JARs must
match their resolved OpenTSx dependencies before copying, and every copied entry
is verified again after copying.

On macOS, the built-in caffeinate tool prevents idle sleep during the
measurement. Its assertion follows the Python process and is released in
cleanup. metadata.json records the actual command and exit status.

Read increments.csv, records.csv and produce-attempts.csv inside each case
directory for raw evidence. Case directory names retain the full chain, interval
and repetition, such as passthrough-5s-r1.

Reference validation checks episode ids, series keys, timestamps and values
against SeriesStream; passthrough also checks payload bytes.

Completed conditions: 18 of 18.
