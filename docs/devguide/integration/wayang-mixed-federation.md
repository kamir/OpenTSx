# Inspect bounded mixed Java and Rust federation

Inspect the B4 acceptance harness for OpenTSx 3.1.0 and `tsx-runner` 0.1.0;
execution results belong to the retained run evidence.

You need the source-built KafScale v1.6.0 broker, Proxy and isolated cluster
launcher,
JDK 17 or 21, the pinned Rust 1.99.1 toolchain and the actual qualification inputs
used by the [B3 site profile](rust-runner-b3.md). The harness uses disposable
clusters and synthetic windpark data. Producers connect to Proxy endpoints.

The [retained W3 qualification](../../datamodel-2026/measurements/rust-b4-mixed-20261120/REPORT.md)
records the executed cases, source/runtime hashes and raw per-window evidence.
The physical ARM64 board measurement remains open (D-T14: ARM64 only).

## Follow the actual runtime boundary

[`FederatedStatsIntegrationTest.everyActualMixedW3WindowMatchesCentralizedAndAllSixMergeOrders`](../../../opentsx-wayang/src/test/java/org/opentsx/wayang/FederatedStatsIntegrationTest.java#L82)
selects Java sites `s1` and `s2` and a real Rust CLI process at `s3`.
[`FederationFixture`](../../../opentsx-wayang/src/test/java/org/opentsx/wayang/FederationFixture.java#L106)
uses the complete W3 input: 3,600 Episodes and 2,160,000 values from 100 series.
The retained run has 1,260 site partials and 420 final windows across
`PT10M` and `PT1H`, with alpha 0.01. The test asserts this complete inventory.

[`RustMixedSite`](../../../opentsx-wayang/src/test/java/org/opentsx/wayang/RustMixedSite.java)
copies the actual executable, computes its digest and uses the B3 qualification
generator with retained runner/contract logs, protocol checks and producer
placement evidence. Signed job-bound conformance precedes aggregate-inventory
admission. The Rust process executes its signed bounded plan, publishes full
site aggregate records and forwards acknowledged records to the signal cluster.
A repeated completed command must return the retained outcome.

`TsxPlanService.admitAggregateInventory` binds the inventory to the signed
membership and scopes after current conformance. The Rust admission records
site, node, executable version/digest and state ABI in the inventory identity.
This does not permit a JVM executor to execute a Rust assignment. Each
assignment still owns one complete topic with all internal partition positions.
See [`AggregateInventory`](../../../opentsx-wayang/src/main/java/org/opentsx/wayang/federation/AggregateInventory.java#L146)
and [`TsxPlanService`](../../../opentsx-wayang/src/main/java/org/opentsx/wayang/site/TsxPlanService.java#L76).

## Inspect numerical qualification evidence

The mixed W3 method checks exact counts, extrema and last-seen timestamps against
the centralized Java reference. It applies the existing bounded-W3 relative
`3e-12` criterion to mean and M2 without widening it. Six input arrival orders
must produce identical merged SOE bytes because the merge folds sites in
canonical order. This is not six different floating-point reduction orders.

For each final window, exact lower quantiles come from sorted finite raw values.
The q01, q50 and q99 checks use alpha 0.01 only outside the zero bucket and
without bounded-bin collapse. Per-site and final bin inventories identify that
domain. Java-only qualification retains its exact shared sketch bytes.
[SPEC S4.3](../../datamodel-2026/20-WAYANG-TS-OPERATORS-SPEC.md#s43-merge-functions)
defines these separate contracts.

[`MixedSketchQualificationTest`](../../../opentsx-wayang/src/test/java/org/opentsx/wayang/MixedSketchQualificationTest.java)
invokes the separate
[`b4_sketch` example](../../../rust/tsx-runner/examples/b4_sketch.rs).
The helper accepts bounded JSON containing exact binary64 input bits and calls
the existing Rust statistics implementation. The Java cases retain the shared
canonical and empty-window vectors and add signed zeros, values one binary64
step around the zero threshold and selected positive/negative gamma boundaries,
and positive/negative collapsed tails. They compare native quantile
representatives, exact counts, qualified adjacent-bin differences and all six
mixed arrival orders. Collapsed low-magnitude tails are explicit accuracy
exceptions, not an extension of the alpha guarantee.

The existing `KAFSCALE_BOOTSTRAP` gate enables these integration classes.
The mixed fixture also requires system properties `opentsx.rust.b3.runner`,
`opentsx.rust.b3.testLog` and `opentsx.rust.b3.contractsLog` for actual executable
and qualification logs. The numeric helper path uses `opentsx.rust.b4.sketch`,
with the example binary under `rust/tsx-runner/target/debug/examples` as its
default.
The numeric helper does not connect to a broker. Missing required artifacts
fail the enabled acceptance run.

Evidence directories under `opentsx-wayang/target`:

- `rust-b4-mixed`: `execution.json`, `partials.csv`, `windows.csv` and successful
  `result.json`; actual runtime identities, signed-plan hashes, statuses,
  conformance and comparisons.
- `rust-b4-sketch`: `input.json`, `rust-output.json` and successful `result.json`;
  input bits, actual bins, native quantile bits and accuracy-domain exceptions.

Inspect the JUnit result and final acceptance report together. Diagnostic CSV
and process files alone do not establish a successful qualification. This
bounded laboratory harness does not qualify arbitrary mixed data, continuous
fleets, production enrollment or independent remote hardware.

## Prepare physical board evidence

[`wayang_board_evaluation.py`](../../../tools/wayang_board_evaluation.py) prepares
the fixed baseline (c) matrix and validates supplied evidence integrity. It does
not execute or attest physical measurements. Under D-T14 the matrix contains one
ARM64 board, Java 17, Java 21 and Rust, 100/1,000/10,000 series, and three
repetitions: 27 conditions. Each condition retains the protocol's
5-minute warm-up and 30-minute measurement with one-second resource samples.
Read [concept 20, section 14.2](../../datamodel-2026/20-WAYANG-TS-OPERATORS.md#142-compare-track-b-and-track-a-on-small-hardware)
for the measurement requirements.

The physical ARM64 measurement remains open. A generated plan, synthetic
validator test or hosted ARM64 result does not close B4.2, and the validator
refuses ARMv7 and AArch32 boards. The [Jetson runbook](wayang-board-jetson.md)
prepares a Jetson Nano 4 GB. No device performance numbers or hardware
qualification follow from preparation alone.
