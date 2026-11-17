# Execute a bounded whole-topic replay

Use `tsx-runner` 0.1.0 to execute a trusted-local bounded Kafka assignment with explicit resource caps
and retained state.

You need the pinned Rust 1.99.1 toolchain and the native C/C++ build tools used by `rdkafka` and zstd.
Broker execution also needs existing input/output topics and a trusted local request.
The [local operating guide](../../docs/devguide/integration/rust-runner-local.md) defines the complete request,
checkpoint, receipt, Compose and failure contracts.

## Check the executable interface

Run from the OpenTSx root:

```bash
cargo +1.99.1 run --locked --manifest-path rust/tsx-runner/Cargo.toml -- --version
cargo +1.99.1 run --locked --manifest-path rust/tsx-runner/Cargo.toml -- --help
```

The command is `tsx-runner replay --request <file> --checkpoint <file>`.
`<file>` identifies a trusted request or its private completed-checkpoint path.
The executable verifies its own artifact digest before reading the broker.
There is no `run --plan`, continuous loop, signed activation or remote Wayang platform in this crate.

## Keep execution within the admitted subset

One assignment owns one complete topic, including every internal partition.
The pipeline accepts pass-through or one linear chain of `dq.checks` (`PLAUSIBLE_RANGE`), `convert.unit`
and an optional trailing `resample` (`MEAN`, `MIN`, `MAX`, `LAST`).
Source selection, payload decoding, memory bounds and every output payload are checked before publication.
Kafka output timestamps must be positive epoch milliseconds.

Cleaning rejects revisions, quality bytes, infinite input values, source metadata changes and multiple sources
collapsing to one derived series. NaN remains a gap. An unfinished resample window remains in state at the cut.
All output acknowledgements precede durable local checkpoint publication. A crash before checkpoint completion
can duplicate records with deterministic identities and the fixed request clock; delivery is at least once.
An identical completed retry returns its saved receipt without broker I/O.

## Run deterministic checks

```bash
cargo +1.99.1 test --locked --manifest-path rust/tsx-runner/Cargo.toml
```

Tests consume shared Avro, identity and cleaning vectors. They check typed admission, all chunk sizes of the
supported cleaning cases, state restoration, immutable derived ownership, acknowledgement failures and retry.
These offline tests use deterministic broker doubles. The separate three-worker Compose check in the operating
guide uses real Kafka protocol I/O and Java reference output on different topics with one, two and three partitions.
