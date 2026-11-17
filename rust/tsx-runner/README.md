# Execute a bounded whole-topic assignment

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

The replay command is `tsx-runner replay --request <file> --checkpoint <file>`.
`<file>` identifies a trusted request or its private completed-checkpoint path.
The executable verifies its own artifact digest before reading the broker.
There is no `run --plan`, continuous execution or signed fleet activation in this crate.

## Serve a trusted local worker

The service command is `tsx-runner serve --listen <addr> --node <id> --site <id>`
followed by `--bootstrap <host:port> --state <directory>`.
`<addr>` is the HTTP listening address. The node and site identifiers must match admitted assignments.
`<host:port>` is the fixed permitted broker address list. `<directory>` is the worker-owned state directory.
The service holds one exclusive directory lock and accepts one unfinished whole-topic request at a time.
Keep this unauthenticated HTTP interface inside a trusted local network.

Use these HTTP operations:

- `GET /v1/capabilities` returns the executable digest, identities, supported operators, state ABI,
  Episode fingerprint and resource ceilings.
- `POST /v1/prepare` accepts the exact `opentsx.local-bounded-run.v1` request text, up to 65,536 bytes.
  It reads the entire admitted cut, validates all input, computes output, and durably saves private output bytes
  and retained state before returning `prepared`. It publishes no Kafka output.
- `POST /v1/activate/<requestHash>` publishes the saved bytes, waits for acknowledgements, and writes the
  authoritative checkpoint before returning `completed` with its receipt.
- `GET /v1/jobs/<requestHash>` returns `prepared`, `completed`, `failed` or `cancelled` status.
- `POST /v1/abort/<requestHash>` cancels a prepared request before publication. It returns HTTP 409 after
  publication may have begun or the request has completed. It does not roll back published records.

`<requestHash>` is SHA-256 of the exact request text. Activation and abort bodies are empty.
Status includes every decoded input series and every output owner, including pending resample windows.
It contains no Episode payloads. Completed preparation and activation retries return saved manifests and receipts
without broker I/O. Identical prepared retries do not reacquire input or change the fixed clock.

Worker state lives under `<directory>/<requestHash>` in checksummed, synchronized files.
Restart reconciles an authoritative checkpoint into completed status. An interrupted activation without that
checkpoint remains failed and retains the same private bytes for an identical activation retry.
Status always includes `activationRetryable`. It is true only when publication may have begun and the saved
preparation remains unfinished. It is false for failed preparation, prepared, completed and cancelled requests.
Such a retry can duplicate output after a lost acknowledgement. A different request remains blocked until the
unfinished activation completes. A failed preparation cannot publish.

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
Lifecycle tests also exercise private preparation, activation, abort, restart, manifests and HTTP framing.
These offline tests use deterministic broker doubles. The separate three-worker Compose check in the operating
guide uses real Kafka protocol I/O and Java reference output on different topics with one, two and three partitions.
