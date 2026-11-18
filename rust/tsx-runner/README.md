# Execute whole-topic assignments and increments

Use `tsx-runner` 0.1.0 to execute trusted-local Kafka assignments and isolated
signed continuous pass-through with explicit resource caps and acknowledged
checkpoints.

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
The separate signed `run` profile admits continuous pass-through only. It does
not activate the trusted-local cleaning chain or supply a fleet service.

## Qualify signed continuous pass-through

Read the
[signed runner operating guide](../../docs/devguide/integration/rust-runner-b1.md)
for its local configuration and activation contract. The `run` profile verifies
a compact Ed25519 JWS with a pinned local driver public key before broker
access. It binds the original assignment, DAG text, artifact digest, site, node,
catalog and plan version to one complete input topic and one Episode output
topic on the same locally allowed binding. Broker addresses, policy and
checkpoint paths come from the local configuration. Both publication and status
use Apache Kafka or KafScale Proxy.

Each finite cut has explicit record, byte, sample, series and state ceilings.
All output acknowledgements precede the progress checkpoint. Durable activation
intent binds the exact signed grant and local configuration; it is separate from
the trusted-local replay checkpoint and HTTP worker state. Public `agent` and
`conform` modes, automatic takeover and signed production fleet deployment are
not implemented.

The CI definition runs shared-vector and runner tests on native x86_64 and ARM64
Linux, builds the three B1 musl targets, and executes supplementary ARMv7 tests
under QEMU. Static-artifact reports check the actual ELF, dynamic dependencies,
version and digest. A QEMU result is not physical ARMv7 qualification. TASK-020
B1 and B1.2 remain open until the shared cases run on physical ARMv7 hardware.

## Serve a trusted local worker

The service command is `tsx-runner serve --listen <addr> --node <id> --site <id>`
followed by `--bootstrap <host:port> --state <directory>`.
`<addr>` is the HTTP listening address. The node and site identifiers must match admitted assignments.
`<host:port>` is the fixed permitted Apache Kafka Broker or KafScale Proxy address list. Never publish directly
to KafScale Broker. `<directory>` is the worker-owned state directory.
The service holds one exclusive directory lock and accepts one unfinished whole-topic request at a time.
Worker and checkpoint owners explicitly unlock in the acquiring process when
they close. Inherited descriptors do not postpone that release or authorize
another process to unlock the owner.
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

## Continue one immutable whole-topic chain

The worker advertises `incrementalRequestSchema: opentsx.local-increment.v1` and `limits.incrementalChains: 1`.
Use the existing prepare, activate, status and abort operations with this increment request schema.
Its fields are the six bounded-request fields plus `sequence` and `previousRequestHash`.
`sequence` is a positive canonical signed-64-bit decimal string. The first sequence is `"1"` and its predecessor
is null. Every successor names the previous completed request's lower-case SHA-256 hash.
The inner execution assignment remains bounded and includes every internal partition's finite `from` and `to`.

The first increment starts every partition at zero. A successor uses the previous sequence plus one and starts
every partition at the preceding receipt's `next` offset. Include empty partition ranges when a topic is idle.
Deployment, assignment, attempt, fence, artifact, node, site, source and fragment text, fixed clock, broker,
partition inventory, sink and limits stay unchanged. Stale requests, forks and changed context fail before input
is read. Increment requests require the worker service; the standalone replay command refuses them.

The worker restores pipeline state locally. Callers send no state or filesystem paths.
Cumulative manifests reserve every decoded source identity, including unselected and pass-through series,
and every output identity, including pending windows. A source identity cannot move between internal partitions.
The lifetime identity inventory stays within `limits.series`, at most 64; retained pipeline and ownership data
together stay within `limits.stateBytes`. An unfinished resample window also retains the existing
`limits.samples` valid-sample ceiling, at most 100,000.

Increment status adds `sequence` and `previousRequestHash`; the first predecessor is JSON null.
Receipts retain their schema. Record counts describe the current increment, positions describe its completed
cut, and the state digest describes the retained cumulative pipeline state.
Each new wave must prepare all participating topics and check cumulative manifests before any activation.

`<directory>/increments/checkpoint.json` holds one completed head and one candidate in a checksummed atomic file.
Only acknowledged publication advances the head. Interrupted publication retains identical prepared bytes and
sets `activationRetryable`; restart does not reread input or reset the clock. A latest completed retry returns
its receipt without broker I/O. Older increments become stale after a successor completes.
Completed output bytes are discarded when their head commits, so history does not grow with increment count.

One worker retains one continuous head after Stop; Stop does not reset the pool for another deployment.
Fenced mode provides an explicit controller-only grant installation and reset boundary described below; it never
implicitly resets state. There is no worker migration or automatic failover interface in this local profile.

## Serve behind the local publication gateway

Add both `--publisher <http-origin>` and `--controller <IP>` to `serve`. The publisher is an internal HTTP origin
with an explicit port. The controller is the actual TCP peer allowed to mutate the worker. Supplying only one
option rejects startup. Forwarded headers do not authorize a peer. Read through the fetch-only Proxy and isolate
the worker from broker and write-Proxy networks, as the
[Compose profile](../../docs/devguide/integration/wayang-rust-compose.md) does.

Fenced mode adds `GET /v1/publication` for public installation status and controller-only `POST /v1/publication`
with exactly `grant`, `reset` and `expectedHead`. The private grant binds deployment, epoch, node and whole input/output
topics. It is captured once per prepared journal, never refreshed during activation and omitted from public status.
The output adapter sends those prepared bytes to the gateway and verifies epoch, request hash, digest and count
in its publication receipt before committing the worker head.

Same-DAG resume advances the epoch while preserving the completed head, cursors and open windows. Reset requires
a new deployment/output, matching old completed head and no pending candidate; the controller revokes old grants
before installing it. Changed-DAG replay starts every partition at zero. Repeating an installed operation is
idempotent and does not reset newer work. See [SPEC S1.12](../../docs/datamodel-2026/20-WAYANG-TS-OPERATORS-SPEC.md#s112-fence-local-publication-and-replace-a-continuous-deployment).

A gateway acknowledgement lost only on HTTP return can be recovered without another Kafka send. A genuinely unknown
broker outcome remains quarantined, blocks retransmission and prevents deployment replacement or retirement.
The gateway ledger must survive restart. This local cap has no automatic gateway failover or signed fleet enrollment.

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
cargo +1.99.1 test --locked --all-targets --manifest-path rust/tsx-runner/Cargo.toml
```

Tests consume shared Avro, identity and cleaning vectors. They check typed admission, all chunk sizes of the
supported cleaning cases, state restoration, immutable derived ownership, acknowledgement failures and retry.
Lifecycle tests also exercise private preparation, activation, abort, restart, manifests and HTTP framing.
Increment tests cover predecessor validation, cumulative ownership, pending windows, fixed-size history and
saved-byte retries after acknowledgement or checkpoint failures.
The identity tests also consume the repository's xxHash64, bucket-checksum and
time-delta vectors. The public `model::bucket_checksum` helper sorts unique
episode IDs and hashes their newline-joined UTF-8 bytes with seed zero; it does
not add a DAG operator. Signed-plan tests reject altered signatures, unsupported
graphs and changed local authority before execution. `--all-targets` also
executes the acceptance examples' unit-test harnesses.

These offline tests use deterministic broker doubles. The separate three-worker Compose check in the operating
guide uses real Kafka protocol I/O and Java reference output on different topics with one, two and three partitions.
