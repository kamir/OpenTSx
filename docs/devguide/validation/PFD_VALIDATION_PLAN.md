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

The runtime aggregates five-minute event-time windows. Observation timestamps drive watermarks with ten seconds
of out-of-order tolerance. The source and watermark checks build a graph and invoke the generator directly;
they do not contact Kafka or start a Flink cluster.

## Run the regressions

From the repository root, run:

```bash
mvn -B -ntp -pl opentsx-flink-core -am test \
  -Dtest=PFDRecorderContractTest,PFDRuntimeContractTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

`PFDRecorderContractTest` checks explicit topics, schema capability fields and refusal to overwrite a file
for unsupported operations or methods. `PFDRuntimeContractTest` checks parser rejection, a recorder export parsed
by the runtime, the configured broker address, watermark advancement and actual centered values with an updated
cached mean. The tests run in the Java CI matrix.

These are offline contract and graph tests. They do not verify a deployed Kafka-to-Flink job.
