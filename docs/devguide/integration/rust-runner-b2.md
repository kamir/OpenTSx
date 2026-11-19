# Execute the local Rust catalog operators

Use `tsx-runner` 0.1.0 to execute the Track B catalog operators through the
trusted-local assignment and checkpoint path.

You need the pinned Rust 1.99.1 toolchain, native C/C++ build tools, an existing
Apache Kafka or KafScale Proxy endpoint, preprovisioned topics and a trusted
local request. Keep the replay and HTTP worker interfaces on an isolated test
network. Read the [local request contract](rust-runner-local.md) before adding
the catalog context below.

## Choose the execution profile

The local request opts into B2 by including a `context` object. The request
still names one complete input topic and one distinct output topic on the same
binding. Partition positions describe an internal finite cut; they do not
divide topic ownership. The original DAG text, fragment text, hashes, artifact
digest and fixed `createdAtMicros` retain their existing checks.

| Context field | Meaning |
| --- | --- |
| `seriesByNode` | Node IDs mapped to explicit arrays of canonical series IDs |
| `cleanTargets` | Clean-stage node IDs mapped to the existing sink signal metadata |
| `xcorrProductLimit` | Explicit positive product budget for `pair.xcorr`; no implicit unlimited value |

Pair and alignment need explicit inventories where a decoder does not supply
them. Clean stages that feed another operator need their bound target metadata.

An empty `context` object selects B2 resource admission without adding an
inventory or product budget. All request limits remain explicit. The B2 series
ceiling is 1,024 and its sample ceiling is 1,000,000; a request without
`context` retains the local profile's 64-series and 100,000-sample ceilings.
Neither route silently widens a request's declared limits.

Worker `GET /v1/capabilities` keeps the four legacy operators in its top-level
`operators` array. `catalogExecution.operators` advertises the fifteen B2
operators with their separate state ABI and extended ceilings. Reading that
list does not extend the Java UI driver's legacy admission contract.

The [signed B1 run](rust-runner-b1.md) accepts pass-through only. Its signed
grant and local configuration do not accept B2 context. The Java UI rollout
continues to validate its own smaller clean-stage subset. Local catalog
availability does not establish a UI export, signed fleet enrollment or
production rollout.

## Select the catalog operation

Use the existing processor descriptions for parameter names, defaults and
semantics. The [catalog matrix](../../datamodel-2026/20-WAYANG-TS-OPERATORS-SPEC.md#s17-catalog-1)
distinguishes target membership from qualified execution.

| Operations | Output |
| --- | --- |
| `decode.avro-soe` | Original Episode |
| `dq.checks`, `convert.unit`, `resample` | Derived Episodes |
| `model.wind-shear`, `model.power-curve` | Derived Episodes |
| `feature.lag`, `feature.rolling` | Derived Episodes |
| `stats.welford-ddsketch` | SeriesStats |
| `segment.threshold`, `segment.extremum` | Deterministic segment Episodes |
| `sax.word` | Episode with `EpisodeSummary.sax` |
| `align.vector` | `opentsx.vector.v1` JSON records |
| `pair.xcorr` | Result Episodes within the explicit product budget |
| `dq.result` | `opentsx.signal.v1` JSON records |

`pair.es` is excluded from Track B. For cross-correlation, the configured
budget bounds `(2 * lags + 1) * window / every`. The rejection text is
`node <id>: pair.xcorr exceeds the limit of this site (<n> products per window)`.
Here `<id>` is the DAG node ID and `<n>` is its configured budget.

## Preserve operator state and record identity

The catalog pipeline validates typed DAG edges and the declared sink schema
before execution. Clean stages keep their existing derived-series ownership
checks. Segmentation retains detector state, samples, quality and source
metadata per series; its buffer drops samples that the detector no longer
needs. Statistics, alignment and pairs retain their own keyed state.

An incremental cut preserves open windows. Bounded completion flushes the
operators that require explicit end-of-input. SAX consumes a complete Episode;
splitting its values into separate Episodes changes its normalization and word.
DQ consumes a complete SeriesStats record. Its finding identity includes the
check and both window bounds; its event time is the window end. The local
catalog supplies no freshness evaluation clock, so selecting `FRESHNESS` alone
does not produce a freshness finding.

B2 clean chains can contain multiple resample steps, each with independent
retained windows. The last resample determines the output interval. A feature
target uses grade `features` and the final clean node ID as its `feature` tag.
As in Java `SeriesStream`, incoming quality flags and revisions do not propagate
to newly derived clean Episodes. The no-context local profile retains its
rejection of annotated cleaning input.

Preparation validates output and retained state before publication. Output
acknowledgements precede durable progress. Interrupted publication can repeat
the same deterministic records. Completed replay returns the retained receipt
without broker I/O. Private checkpoint formats are runtime-specific; they are
not interchangeable with Java `TsxState`.

## Run the shared regressions

Run from the OpenTSx root:

```bash
cargo +1.99.1 test --locked --all-targets --manifest-path rust/tsx-runner/Cargo.toml
```

The tests read committed vectors from the repository. The
[cleaning tests](../../../rust/tsx-runner/src/operators.rs),
[statistics and DQ tests](../../../rust/tsx-runner/tests/stats_vectors.rs),
[segment and SAX tests](../../../rust/tsx-runner/tests/retrieval_vectors.rs)
and [alignment and pair tests](../../../rust/tsx-runner/tests/pair_vector_vectors.rs)
retain their reference tolerances and missing-value rules. Chunked tests restore
serialized state between calls and repeat both series orders. The
[catalog flow tests](../../../rust/tsx-runner/tests/catalog_flow.rs) exercise
admission, output publication and acknowledged checkpoints separately from
numerical kernels.
The [clean catalog tests](../../../rust/tsx-runner/tests/clean_catalog.rs)
also check multiple resample states, feature identities, processor defaults,
annotated inputs and transactional rejection of malformed retained queues.

Read the [B2 acceptance criteria](../../../EVOLUTION/TASK-020-wayang-ts-operators.md#b2-edge-operators)
for Track A parity and exact cross-correlation-limit evidence. Passing offline
vectors does not establish physical device qualification.

## Keep ARMv7 hardware acceptance open

Native hosted x86_64 and ARM64 tests and supplementary ARMv7 QEMU tests are
separate evidence. B1.2 requires the shared Avro and identity cases on actual
ARMv7 hardware. The board, operating system, source revision, commands and
results must be recorded before closing B1.2 or B1. The missing hardware
acceptance remains tracked in [issue 294](https://github.com/kamir/OpenTSx/issues/294).
