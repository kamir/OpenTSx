# Execute the embedded WASM conversion

Use `tsx-runner` 0.1.0 with Rust 1.99.1 to execute the existing local
`convert.unit` stage through an artifact-embedded Wasmtime 50.0.1 module.

You need the pinned Rust toolchain and the native C/C++ build tools required
by the runner. Broker execution also needs the
[trusted-local request](rust-runner-local.md), preprovisioned topics and an
Apache Kafka or KafScale Proxy endpoint. The module's sandbox limits do not
replace the request's record, sample, byte, series or state limits.

## Select the existing conversion stage

Keep the existing `convert.unit` operator ID and `factor`, `offset` and `unit`
parameters. The [processor description](../../../systems/processors/convert.unit.yaml)
remains the parameter authority. No backend parameter or new operator ID is
required. The local replay, HTTP worker and B2 cleaning paths use the same
embedded conversion.

The guest accepts three binary64 values: the sample, factor and offset. It
returns one binary64 value, using a separate multiplication and addition.
The host retains Episode metadata, time coordinates, derived identities,
unit labels, gap handling and overflow rejection. The browser lesson still
executes its own workbench implementation.

Read the [host wrapper](../../../rust/tsx-runner/src/wasm.rs) and
[embedded guest](../../../rust/tsx-runner/wasm/convert_unit.wat) with the
[cleaning pipeline](../../../rust/tsx-runner/src/operators.rs) together.
Wasmtime uses the Pulley interpreter for both 64-bit and 32-bit targets. A
native build or supplementary ARMv7 QEMU test does not establish a physical
board measurement.

## Keep the sandbox boundary fixed

Only the reviewed conversion module embedded in the runtime artifact is
loadable. Plans and local requests cannot supply a module path, URL, code,
bytes, compiled native artifact or resource-limit override.
Unknown fields in the DAG, source, node, sink and sink signal are refused.

| Resource | Host-owned limit |
| --- | --- |
| Module bytes | 65,536 |
| Fuel before instantiation | 10,000 units |
| Fuel per scalar invocation | 10,000 units |
| Linear memory | 65,536 bytes |
| Memories | 1 |
| Instances | 1 |
| Tables | 0 |
| Guest stack | 262,144 bytes |
| Host imports | None |

The guest has no WASI, filesystem, socket, environment, clock or random
access. Fuel is installed before instantiation, so a start function is also
bounded. Initial allocation and memory growth must fit the memory cap.
Instantiation or execution failure aborts processing before publication or
checkpoint commit. There is no native conversion fallback.

The [signed B1 profile](rust-runner-b1.md) still admits pass-through only.
The [signed B3 profile](rust-runner-b3.md) still admits bounded
decode-to-statistics only. Embedded WASM availability does not broaden
either signed admission gate or enroll a production fleet.

## Inspect a refused conversion

- `WASM fuel exhausted`: guest execution consumed its fixed budget. Inspect
  the reviewed module and retained input; plans cannot raise the budget.
- `WASM memory limit exceeded`: initial allocation or growth violated the
  fixed memory cap. Correct the reviewed module instead of retrying with a
  larger plan-supplied limit.
- `WASM imports are forbidden`: the module requests a host capability.
  Rebuild the reviewed import-free artifact.
- `WASM convert ABI is invalid`: the `convert` export is missing or has the
  wrong binary64 parameter/result signature. Correct the module ABI.
- `unit conversion overflow`: a result is infinite. Correct the input or
  the existing finite conversion parameters before retrying.

The host returns these failures rather than publishing partial results.
Retained acknowledged state remains the recovery point.

## Run the resource and operator regressions

Run from the OpenTSx root:

```bash
cargo +1.99.1 test --locked --all-targets --manifest-path rust/tsx-runner/Cargo.toml
```

The `wasm_limits` tests require fuel exhaustion in an exported endless loop
and a start function, refusal of initial allocation and memory growth past
the cap, and rejection of malformed modules, imports and ABI mismatches.
They check deterministic failure reasons without elapsed-time thresholds.
The stack regression verifies the configured 262,144-byte limit with a real
recursive trap. Its test-only store receives extra diagnostic fuel so that
fuel exhaustion cannot mask the stack limit. Production calls retain 10,000
fuel units.
A task that returns `memory.grow == -1` and succeeds does not meet the memory
acceptance criterion.

The [operator regressions](../../../rust/tsx-runner/tests/wasm_operator.rs)
read the repository's
[shared cleaning vectors](../../datamodel-2026/testvectors/clean-ops.v1.json)
directly. Both the standalone unit conversion and combined cleaning chain
exercise the real pipeline. Values retain A2.1's tolerance of
`1e-12 * max(1, abs(expected))`; gaps, timestamps and units remain exact.
Chunked runs serialize and restore the existing host state.
The signed CLI regression also checks the actual executable against the
existing 268,435,456-byte identity-read limit. Development and test profiles
omit dependency debug information; runner debug information is retained.

The validation observer accepts a reset before a new Kafka frame only when
no request or only metadata requests remain pending. Frame decoding rejects
resets that interrupt a length or body, and resets with pending data requests.
Response correlations remain pending until forwarding and flushing succeed.
The deterministic
[observer tests](../../../opentsx-wayang/src/test/java/org/opentsx/wayang/LatencyProtocolObserverTest.java)
check these boundaries with streams rather than sockets or waits.

Use the [conversion lesson](../../learning/ui-operators.v1.json) with its
existing Celsius/Fahrenheit starter. Trace its WASM and resource-limit test
references separately from the browser workload.

## Keep physical board acceptance open

Both physical ARM64 and ARMv7 measurements under
[B4.2](../../../EVOLUTION/TASK-020-wayang-ts-operators.md#b4-mixed-federation-and-evaluation-on-small-hardware)
remain open. Physical ARMv7 B1.2 vector acceptance also remains open in
[issue 294](https://github.com/kamir/OpenTSx/issues/294). Hosted CI, emulation
and sandbox limits provide no device RSS, CPU, startup or throughput result.
