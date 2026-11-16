# opentsx-wayang

OpenTSx time-series operators as Apache Wayang 1.1.1 operators on Wayang's Java platform (Track A of
[concept 20](../docs/datamodel-2026/20-WAYANG-TS-OPERATORS.md), specification
[S1 to S7](../docs/datamodel-2026/20-WAYANG-TS-OPERATORS-SPEC.md), tasks in
[TASK-020](../EVOLUTION/TASK-020-wayang-ts-operators.md)). Verified with JDK 21 on 2026-11-16.

Status: phase A1. The module reads and writes KafScale topics from Wayang plans and runs them in increments with a
checkpoint. No OpenTSx analysis operator runs on Wayang yet; they follow in phase A2.

## Use the plugin

Register `TsxJavaPlugin`, not `Java.basicPlugin()`:

```java
new WayangContext(new Configuration()).with(new TsxJavaPlugin()).execute(new WayangPlan(sink));
```

`Java.basicPlugin()` also registers Wayang's Amazon S3, Google Cloud Storage and Azure mappings. OpenTSx excludes
those SDKs, so a plan built with `Java.basicPlugin()` fails with
`NoClassDefFoundError: software/amazon/awssdk/core/ResponseInputStream`. `TsxJavaPlugin` registers the Java platform,
its channel conversions and only the mappings OpenTSx uses: collection source, map and local callback sink.

## Run a plan in increments

`TsxIncrement.run` reads the end offsets of the input topic, runs the plan over `[checkpoint, end)` and writes the new
checkpoint only after every sink record was acknowledged. A failed plan leaves the checkpoint as it was and throws
`TsxIncrementException` with the root cause as message.

```java
TsxIncrement.Config config = new TsxIncrement.Config(bootstrap, "job", 1, specHash, layout.episodes(),
        Set.of("8985f02b48c77195"), "earliest", Path.of("state/job.checkpoint.json"));
TsxIncrement.run(config, source -> {
    TsxTopicSink sink = new TsxTopicSink(bootstrap, layout.clean());
    source.connectTo(0, sink, 0);
    return sink;
});
```

| Class | Role |
|---|---|
| `TsxTopicSource`, `JavaTsxTopicSource` | reads fixed offset ranges with manual assignment (no consumer group), checks the schema fingerprint of every record against the plan, decodes Avro single-object records |
| `TsxTopicSink`, `JavaTsxTopicSink` | writes with `KafScaleClients.producer` (no idempotence, `acks=all`), key `seriesId`, record time `tStart`, header `tsx-schema`; maps in UTF-8 key order; returns after every record is acknowledged |
| `TsxCheckpoint` | next offset per partition, job, plan version and `specHash` in a JSON file, written to a temporary file and renamed; a checkpoint of another `specHash` is ignored, an unreadable one fails |
| `TsxIncrement` | one increment as above |

The source holds the records of one increment in memory. Choose the increment so that one range fits.

The checkpoint file is JSON with these fields (`TsxCheckpoint.java`):

| Field | Value |
|---|---|
| `schema` | `opentsx.wayang.checkpoint.v1` |
| `job`, `planVersion` | job id and plan version of the increment |
| `specHash` | SHA-256 of the DAG document; a checkpoint with another value is ignored |
| `topic` | the input topic |
| `next` | partition number (as a string) to the first offset not yet processed |

## Dependencies

The root `pom.xml` pins `wayang.version` and manages `wayang-core`, `wayang-basic` and `wayang-java` with these
exclusions (decision D-T9 in TASK-020):

| Excluded | Pulled in by |
|---|---|
| `com.amazonaws:aws-java-sdk-s3` | `wayang-core` |
| `com.google.cloud:google-cloud-storage`, `google-cloud-storage-control` | `wayang-basic` |
| `software.amazon.awssdk:s3` | `wayang-basic` |
| `com.azure:azure-storage-blob`, `azure-security-keyvault-secrets`, `azure-identity` | `wayang-basic` |
| `org.apache.logging.log4j:log4j-slf4j-impl` | `wayang-java` |

Wayang declares Hadoop as `provided`, but its Java platform loads `org.apache.hadoop.io.Writable` at run time. The
module adds the shaded `hadoop-client-api` and `hadoop-client-runtime` in the version the root `pom.xml` manages.

## Test

You need JDK 17 or 21 and Maven 3.8 or later.

```bash
mvn -B -ntp -pl opentsx-wayang -am verify
```

`WayangSmokeTest` maps 1,000 integers through a Wayang plan on the Java platform. `TsxWireVectorsTest`,
`TsxClientSettingsTest` and `TsxCheckpointTest` run without a broker.

The integration tests need a KafScale broker and run only when `KAFSCALE_BOOTSTRAP` is set. They create random test
domains and do not remove their topics; use a disposable broker.

```bash
KAFSCALE_BOOTSTRAP=127.0.0.1:39092 mvn -B -ntp -pl opentsx-wayang -am test
```

Read `opentsx-wayang/target/surefire-reports/`: `TsxPassThroughIntegrationTest`, `TsxCrashRecoveryIntegrationTest`
and `TsxFingerprintIntegrationTest` must each show one executed test with zero failures, errors and skips.
