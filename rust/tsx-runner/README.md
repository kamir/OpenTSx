# Execute whole-topic assignments and increments

Use `tsx-runner` 0.1.0 to execute trusted-local Kafka assignments, isolated
signed continuous pass-through and qualified signed bounded statistics with
explicit resource caps and acknowledged checkpoints.

You need the pinned Rust 1.99.1 toolchain and the native C/C++ build tools used by `rdkafka` and zstd.
The runner manifest declares Rust 1.97.0 as its minimum for Wasmtime 50.0.1.
Development and test builds omit dependency debug information while retaining
runner debug information. The signed CLI regression records the executable
size and requires it to fit the existing 256 MiB identity-read limit.
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
The B1 configuration selects signed continuous pass-through. The separate B3
site configuration selects signed bounded statistics, outbound plan pulls and
signed conformance. Neither signed profile admits the remaining B2 catalog.

## Operate a qualified bounded statistics site

The [B3 operating guide](../../docs/devguide/integration/rust-runner-b3.md)
defines `agent --config <file>`, `conform --config <file> --job <job>` and
`run --plan <file>|- --config <file>` with `opentsx.rust-site-config.v1`.
Its signed fragment admits only `decode.avro-soe` followed by
`stats.welford-ddsketch`, alpha 0.01 and tumbling windows. A Java
executor cannot execute a Rust assignment.
Before publishing a later generation, the driver requires every previous
site's durable stopped status and accepted delivery acknowledgement.

Local configuration supplies separate plan/report role keys, TLS paths,
permitted jobs, site/signal bindings, topic policy, a private state directory,
all resource limits and mandatory owner-local qualification evidence. The
runtime uses TLS 1.3 mTLS with explicit roots and hostname checks, without
proxy fallback or redirects. It has no broker-admin or probe-topic rights.
Prior executed vectors, broker protocol and producer placement are pinned
separately from fresh job-cut, fingerprint, partition and series checks.

Bounded execution writes complete aggregate records to the site outbox.
The forwarder validates the completed inventory and advances only after target
acknowledgement. Retrying an unknown acknowledgement can repeat a deterministic
key/revision. The [offline regression](../../opentsx-wayang/src/test/java/org/opentsx/wayang/RustOfflineCatchupIntegrationTest.java)
checks the complete W scenario, signal-cluster outage, restart, catch-up and
final revisions. Production continuous or mixed-runtime fleet activation
remains separate. The [bounded mixed W3 harness](../../docs/devguide/integration/wayang-mixed-federation.md)
selects the actual B3 executable for `s3` beside two Java sites and records its
qualified identity, signed statuses and retained completed outcome. The
`b4_sketch` example separately exposes existing statistics through bounded
exact-bit JSON for cross-runtime boundary, zero-bucket and collapsed-tail
checks; it does not add a deployed operator. The physical ARM64 B4.2
measurement remains open; D-T14 requires no ARMv7 hardware.

## Execute the local catalog profile

The [B2 guide](../../docs/devguide/integration/rust-runner-b2.md) defines the
optional local request `context`, explicit input-series inventories and
cross-correlation product budget. The catalog adapter executes clean stages,
statistics, segmentation, SAX, vector alignment, bounded cross-correlation and
data-quality signals through the existing replay and HTTP-worker paths.
Each assignment still owns one complete topic and one typed output.
`pair.es` remains excluded. This local profile does not expand the signed B1
grant or the Java UI rollout's separate operator admission.

Without `context`, local requests retain their 64-series and 100,000-sample
ceilings. B2 requests with `context` allow at most 1,024 series and 1,000,000
samples, subject to every declared byte and state bound. Numerical kernels,
admitted local execution and fleet deployment are separate capabilities.

## Continue B2 statistics in increments

A local worker also runs a B2 request with `context` and `stats.welford-ddsketch`
as `opentsx.local-increment.v1` increments. Each increment emits only the windows
it closes and keeps open windows in the worker state, so the increments after a
restart equal one bounded run without its final flush of the open and `GLOBAL`
windows (`tests/stats_increments.rs`). The B2 ceilings of 1,024 series and 1 MiB
of state per request still apply.

## Execute conversion inside WASM

The existing local `convert.unit` stage runs the artifact-embedded module
through Wasmtime 50.0.1 and its Pulley interpreter. Its import-free binary64
ABI preserves separate multiplication and addition; host code retains units,
timestamps, gaps, identities and overflow rejection. The operator parameters
retain their existing contract. Undeclared cleaning DAG fields are refused.

The [B5 operating guide](../../docs/devguide/integration/rust-runner-b5.md)
defines fixed module, fuel, memory and stack bounds, executable regression
cases and failure handling. Plans cannot supply code or enlarge these bounds.
There is no WASI or native conversion fallback. This local implementation
does not broaden signed B1 pass-through or signed B3 statistics admission.

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
the trusted-local replay checkpoint and HTTP worker state. The B3 site profile
has its own bounded `agent` and `conform` interfaces. Automatic takeover and
signed production fleet deployment remain outside both isolated profiles.

The CI definition runs shared-vector and runner tests on native x86_64 and ARM64
Linux, builds the three B1 musl targets, and executes supplementary ARMv7 tests
under QEMU. Static-artifact reports check the actual ELF, dynamic dependencies,
version and digest. A QEMU result is not physical ARMv7 qualification; under
D-T14 TASK-020 B1.2 is met by the native x86_64 and ARM64 jobs, and ARMv7 stays
a supplementary QEMU check.

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

- `GET /v1/capabilities` returns the executable digest, identities, legacy operators, state ABI,
  Episode fingerprint and resource ceilings. `catalogExecution.operators` lists the separate B2 catalog.
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
Without B2 context, the pipeline accepts pass-through or a linear chain of
`dq.checks` (`PLAUSIBLE_RANGE`), `convert.unit` and an optional trailing
`resample` (`MEAN`, `MIN`, `MAX`, `LAST`). B2 context also admits
`model.wind-shear`, `model.power-curve`, `feature.lag` and `feature.rolling`.
It keeps an independent window for each resample step and uses the final
step's interval. The B2 catalog adapter additionally accepts
typed statistics, retrieval, vector, pair and DQ stages with their declared
output schemas and state bounds.
Source selection, payload decoding, memory bounds and every output payload are checked before publication.
Kafka output timestamps must be positive epoch milliseconds.

No-context cleaning rejects revisions and quality bytes. B2 clean execution
accepts those inputs like Java `SeriesStream` and writes newly derived Episodes
without copying the flags or revision. Both profiles reject infinite values,
source metadata changes and multiple sources collapsing to one derived series.
NaN remains a gap. An unfinished resample window remains in state at the cut.
All output acknowledgements precede durable local checkpoint publication. A crash before checkpoint completion
can duplicate records with deterministic identities and the fixed request clock; delivery is at least once.
An identical completed retry returns its saved receipt without broker I/O.

## Run deterministic checks

```bash
cargo +1.99.1 test --locked --all-targets --manifest-path rust/tsx-runner/Cargo.toml
```

The [CLI prefix fixture](tests/common/runner.rs) pipes its program into `/bin/sh` without creating temporary files.
It checks prefix ordering and argument boundaries, including paths containing spaces.
A regression reruns that exact test with `TMPDIR` pointing to the test executable, a regular file.
The child retains the configured ARMv7 runner prefix and must report one matching test passed.

Tests consume shared Avro, identity, all fifteen cleaning cases, statistics,
segment, SAX, alignment, cross-correlation and DQ vectors. They check typed
admission, chunked state restoration, immutable derived ownership,
acknowledgement failures and retry. Retrieval and pair tests preserve complete
Episode inputs where their semantics require them.
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
