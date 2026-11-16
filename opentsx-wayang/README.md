# opentsx-wayang

OpenTSx time-series operators as Apache Wayang 1.1.1 operators on Wayang's Java platform (Track A of
[concept 20](../docs/datamodel-2026/20-WAYANG-TS-OPERATORS.md), specification
[S1 to S7](../docs/datamodel-2026/20-WAYANG-TS-OPERATORS-SPEC.md), tasks in
[TASK-020](../EVOLUTION/TASK-020-wayang-ts-operators.md)). Verified with JDK 21 on 2026-11-16.

The module contains the Wayang dependency setup, `TsxJavaPlugin`, and the pure
Java `TsxPairXcorr` kernel. The kernel has no Wayang types. Its integration into
a Wayang execution operator is outside this module's current execution path.

## Compute pair cross-correlation

`org.opentsx.wayang.ops.TsxPairXcorr` computes lagged cross-correlation, the
signed absolute peak, link strength, and centred pair windows. It follows
`xcorr.ts` and `windowsOf` in `pairs.ts` in the web workbench. Positive output
lag means that B follows A. Non-finite samples contribute zero after centring;
constant inputs produce NaN. Invalid lags are rejected with the workbench's
error message.

`compute` consumes equal-length arrays already aligned to the epoch clock;
the first sample must lie on that clock. Aggregation, filling, and episode
pairing happen before this call. `pairSettings` parses the workbench settings,
including the default coverage `0.8`, episode windows, and width-sized steps.
Off-grid starts fail with
`The first aligned sample must start on the epoch clock.` Align the first
sample to a clock tick before calling `compute`.

The regression tests load the repository's shared
`docs/datamodel-2026/testvectors/pair-xcorr.v1.json` directly. All five cases
use an absolute error below `5e-13`.

## Use the plugin

Register `TsxJavaPlugin`, not `Java.basicPlugin()`:

```java
new WayangContext(new Configuration()).with(new TsxJavaPlugin()).execute(new WayangPlan(sink));
```

`Java.basicPlugin()` also registers Wayang's Amazon S3, Google Cloud Storage and Azure mappings. OpenTSx excludes
those SDKs, so a plan built with `Java.basicPlugin()` fails with
`NoClassDefFoundError: software/amazon/awssdk/core/ResponseInputStream`. `TsxJavaPlugin` registers the Java platform,
its channel conversions and only the mappings OpenTSx uses: collection source, map and local callback sink.

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

`WayangSmokeTest` maps 1,000 integers through a Wayang plan on the Java platform and checks the result.
