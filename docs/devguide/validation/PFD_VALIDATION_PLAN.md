# Validate processing-flow descriptors

Check recording exports against the executable PFD contract in the current checkout.
You need JDK 17 or later and Maven 3.8 or later.

## Check supported flows

The [JSON schema](../schemas/processing-flow-descriptor-schema.json), recorder file export and dynamic Flink runtime
support Kafka input, zero or more `NORMALIZE` centering steps and `LOG` output.
A flow needs a nonblank name and Kafka input topic; every step needs a nonblank id.
`params.method` can be omitted or set to `center`. Other operation parameters are rejected.
DFA, smoothing, filtering, z-score normalization and Kafka output are rejected.

`PFDRecorder.toJSON()` produces a recording preview. Previews can contain unsupported desktop operations and do not
imply that the runtime can execute them. `saveToFile` checks compatibility before creating or replacing a file.
A missing topic or unsupported step leaves an existing destination unchanged.

## Configure input and brokers

Set `opentsx.pfd.input.topic` through the existing core `ConfigManager` before resetting the recorder.
The recorder uses this topic instead of a placeholder and sets output type `LOG`.
`DynamicTopologyJob` reads its input topic from the descriptor and brokers from `bootstrap.servers`
through the same configuration manager. Blank broker configuration fails instead of selecting localhost.

The runtime defaults to five-minute event-time windows. Append `--window-size <milliseconds>` after the descriptor
filename or after `--json` and its JSON argument to select a positive integer window size in milliseconds.
The default is `300000`. Zero, negative, non-integer,
out-of-range, missing and duplicate values are rejected before graph construction. Unknown trailing arguments
are rejected.

Set `--out-of-orderness <milliseconds>` after the descriptor to configure the watermark tolerance.
It accepts a non-negative integer and defaults to `10000` milliseconds. Zero removes the configured delay.
It can be combined with `--window-size` in either order; each option can occur once. Invalid or missing values
and duplicate options fail before graph construction. Observation timestamps drive watermarks.
Watermarks trail the maximum observed timestamp by the tolerance plus one millisecond, bounded below by the
minimum long value. Older observations do not move them backwards. Before the first observation the watermark
stays at the minimum long value.
This option controls watermark generation; it does not configure Flink allowed lateness.
The source and watermark checks build a graph and invoke the generator directly;
they do not contact Kafka or start a Flink cluster.

## Run the regressions

From the repository root, run:

```bash
mvn -B -ntp -pl opentsx-flink-core -am test \
  -Dtest=PFDRecorderContractTest,PFDRuntimeContractTest,PFDWindowSizeTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

`PFDRecorderContractTest` checks explicit topics, schema capability fields and refusal to overwrite a file
for unsupported operations or methods. `PFDRuntimeContractTest` checks parser rejection, a recorder export parsed
by the runtime, the configured broker address, watermark advancement and actual centered values with an updated
cached mean. The tests run in the Java CI matrix.

`PFDWindowSizeTest` invokes the actual entry point for file and inline JSON descriptors in an offline graph
execution environment. It checks the five-minute default, configured windows, exact boundary membership,
negative timestamps and rejection before execution. It also verifies zero and configured watermark tolerance,
the initial watermark, out-of-order events and advancement to the actual window end boundary.
Both options are checked together in either order. It never starts a Kafka consumer or a Flink cluster.

The JUnit cases are offline contract and graph tests. The log cases exercise zero, one, two and five points
through the actual sink mapping function and verify all coordinates and values.

## Run the broker-backed acceptance

You need a running broker and `KAFSCALE_BOOTSTRAP` set to its host and port.
The CI KafScale conformance job provides KafScale 1.6.0 with in-memory storage and Flink 1.20.5.
From the repository root, run:

```bash
mvn -B -ntp -pl opentsx-flink-core -am verify -Ppfd-acceptance
```

The profile fails if `KAFSCALE_BOOTSTRAP` is missing. It creates a unique single-partition topic in the supplied
broker, publishes three acknowledged observations and exports the flow through `PFDRecorder.saveToFile`.
It starts `DynamicTopologyJob` in a separate process and checks its actual JSON output.
The 90-second deadline bounds failure detection; values and window membership depend only on event timestamps.
A recorder-exported flow reads one Kafka partition, centers each completed window and writes JSON to `LOG`.
Three acknowledged Avro observations for `quality-series` have timestamps 1000, 2000 and 600000 milliseconds
and values 2, 4 and 100. The third observation advances the watermark beyond the first five-minute window.
The completed first window contains timestamps 1000 and 2000 and centered values -1 and 1.
The unbounded job is stopped after verification or failure.
The profile writes `flow.json`, `flink.log` and the verified `result.json` under
`opentsx-flink-core/target/pfd-acceptance/`. CI uploads this directory with its integration artifacts.
Use an isolated broker: the acceptance topic remains until the broker is discarded.

The verified output is:

```json
{"label":"quality-series","timestamps":[1000.0,2000.0],"values":[-1.0,1.0]}
```

This broker-backed acceptance runs in the KafScale CI job and is separate from the offline JUnit suite.
A mismatch, missing output, early process exit or timeout fails the Maven build. Inspect `flink.log` on failure.
