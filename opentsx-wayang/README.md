# opentsx-wayang

OpenTSx time-series operators as Apache Wayang 1.1.1 operators on Wayang's Java platform (Track A of
[concept 20](../docs/datamodel-2026/20-WAYANG-TS-OPERATORS.md), specification
[S1 to S7](../docs/datamodel-2026/20-WAYANG-TS-OPERATORS-SPEC.md), tasks in
[TASK-020](../EVOLUTION/TASK-020-wayang-ts-operators.md)). Verified with JDK 21 on 2026-11-16.

The module reads and writes KafScale topics from Wayang plans and runs them
in increments with a checkpoint (phase A1). It also contains the pure Java
`TsxPairEs` kernel, whose API has no Wayang types or `opentsx-core` dependency.
Shared reference vectors are checked by the statistics and retrieval modules.

## Compute pair event synchronisation

`org.opentsx.wayang.ops.TsxPairEs` detects local maxima above a population
standard-deviation threshold, computes directional event synchronisation,
and calculates a surrogate baseline by shuffling inter-event intervals.
`Mulberry32` supplies the repeatable shuffle sequence. The implementation
follows `event_sync.py` and the web workbench's `es.ts`.

The regression tests load the repository's shared
`docs/datamodel-2026/testvectors/pair-es.v1.json` directly. All eight cases
require identical event lists and an absolute error below `5e-13` for
`q_total`, `q_ab`, `q_ba`, and `q_rand`. The random sequence is compared
exactly.

## Use the plugin

Register `TsxJavaPlugin`, not `Java.basicPlugin()`:

```java
new WayangContext(new Configuration()).with(new TsxJavaPlugin()).execute(new WayangPlan(sink));
```

`Java.basicPlugin()` also registers Wayang's Amazon S3, Google Cloud Storage and Azure mappings. OpenTSx excludes
those SDKs, so a plan built with `Java.basicPlugin()` fails with
`NoClassDefFoundError: software/amazon/awssdk/core/ResponseInputStream`. `TsxJavaPlugin` registers the Java platform,
its channel conversions and only the mappings OpenTSx uses: collection source, map, local callback sink,
KafScale topic source and KafScale topic sink.

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

## Check the shared Java references

The shared files in `docs/datamodel-2026/testvectors/` capture existing
statistics and retrieval behavior for TASK-020 A2 and A3:

| Vector file | Owning-module test | Cases |
| --- | --- | ---: |
| `stats-window.v1.json` | `StatsWindowVectorsTest` | 5 |
| `segment-threshold.v1.json` | `SegmentThresholdVectorsTest` | 6 |
| `segment-extremum.v1.json` | `SegmentExtremumVectorsTest` | 6 |
| `sax-word.v1.json` | `SaxWordVectorsTest` | 7 |
| `dq-result.v1.json` | `DqResultVectorsTest` | 7 |

These tests replay every case exactly in `opentsx-stats` and
`opentsx-retrieval`. They read the repository files directly. Run
`python tools/java_operator_vectors.py` from the repository root to
regenerate all five files. You need JDK 17 or 21, Maven, and Python.
The generator is verified with JDK 21 and Python 3.12 on 2026-11-16.
