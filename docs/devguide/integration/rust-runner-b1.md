# Execute the isolated signed Rust pass-through profile

Use the B1 profile with `tsx-runner` 0.1.0 for one signed continuous
pass-through assignment.

You need an existing Apache Kafka or KafScale Proxy endpoint, preprovisioned
input, output and status topics, a driver-signed plan, the pinned driver's
public key, local policy and a dedicated checkpoint path. Keep this profile on
an isolated test network. Physical ARMv7 qualification and production fleet
operation are separate acceptance work.

## Prepare trusted inputs

The plan is compact Ed25519 JWS with the exact protected header
`{"alg":"EdDSA"}`. Its payload is `opentsx.runner-plan.v1` with a continuous
Rust `opentsx.execution-assignment.v1` assignment embedded as exact text. The
original source and fragment text retain their hashes. The fragment contains
only `decode.avro-soe` and one Episode sink. Every partition remains part of the
same whole-topic assignment.

The isolated Rust branch admits one input topic and one Episode output in the
same local binding. It has no JVM federation inventory and cannot send raw
episodes to a signal-cluster binding. Input and output writer fingerprints are
exactly `8985f02b48c77195`. Plans contain no bootstrap addresses, credentials or
local paths.

Local configuration uses schema `opentsx.rust-run-config.v1`. It pins the site,
node, driver public-key file, binding, domain and catalog policy, checkpoint
path and explicit limits. The binding's output allowlist includes both the
Episode sink and the derived `tsx.<domain>.dag-status.v1` topic. The existing
local replay and HTTP worker request formats retain their own limits and trust
boundaries.

Unknown or duplicate JSON fields are rejected. The configuration is at most
65,536 bytes. Execution requires catalog `tsx-catalog/1`. Site, node and binding
identities match `[a-z0-9][a-z0-9-]{0,62}`. `payloadBytes` cannot exceed
`inputBytes`. Relative key and checkpoint paths resolve against the canonical
configuration file's directory.

| Field | Type and admitted value |
| --- | --- |
| `schema` | String `opentsx.rust-run-config.v1` |
| `site`, `node` | Local identities matching the signed assignment |
| `driverPublicKey` | Ed25519 SPKI public-key PEM path; 16,384-byte limit |
| `bindings` | Object with exactly one local binding |
| `bindings.<name>.bootstrap` | Kafka or Proxy list of `host:port` endpoints |
| `bindings.<name>.inputs` | Array containing the complete Episode input topic |
| `bindings.<name>.outputs` | Episode sink and derived status topic array |
| `policy.domains` | Nonempty array of unique permitted topic-domain strings |
| `policy.catalogs` | Nonempty array of unique catalog versions |
| `checkpoint` | Dedicated progress-checkpoint path |
| `limits` | Object with every positive integer bound listed below |

| Limit | Maximum per internal cut |
| --- | ---: |
| `records` | 10,000 |
| `payloadBytes` | 1,048,576 bytes |
| `inputBytes` | 16,777,216 bytes |
| `outputBytes` | 16,777,216 bytes |
| `samples` | 1,000,000 |
| `series` | 1,024 retained identities |
| `stateBytes` | 1,048,576 bytes |

These are explicit safety ceilings. They are not measured performance figures.
Configure a record budget that fits the sample and byte bounds of the input
workload. A cut that exceeds a bound fails instead of widening it.

## Reserve the whole topic locally

The runner holds an operating-system file lock for the effective Unix user, job,
configured broker-endpoint set and complete input topic. The lock is independent
of checkpoint path, assignment version and internal partition positions. A
competing local start returns `plan.busy` before broker access. Different jobs
can consume their complete inputs independently.

Reservation files use `/tmp/opentsx-runner-reservations-<uid>` after resolving
the operating system's `/tmp` path. `<uid>` is the effective Unix user ID. The
directory must belong to that user and have mode `0700`. Symlinked, shared or
foreign-owned directories are refused with `plan.policy`. Endpoint order,
duplicates, host case, numeric ports and IPv6 spelling are normalized. Use the
same endpoint set in configurations for the same cluster.

Stop, error return and process death release the kernel lock. The acquiring
process explicitly unlocks when its reservation owner closes, even while an
inherited descriptor remains open. A copied owner in another process cannot
unlock the acquiring process's reservation. Retain the directory and lock files
while processes can be running. Stop the owning process
before retrying a busy grant. This reservation does not establish distributed
ownership or production fencing.

## Preserve authority and acknowledged progress

Signature, runtime, artifact, site, node, policy and the declared whole-topic
assignment are validated before broker access. A fresh activation requires
`issued <= now < expires`. A persisted active grant retains its exact accepted
authority at that checkpoint on restart; changed command bytes or local
configuration do not silently reset it. The configuration identity includes the
actual pinned public key and resolved checkpoint location.

The derived intent file records accepted authority separately from the data
progress checkpoint. A failed first input record can therefore retain its grant
without claiming input progress. Checkpoints advance only after output
acknowledgements and successful status publication. A crash before checkpoint
completion can repeat acknowledged episodes with the same canonical payload
bytes.

SIGINT and SIGTERM request Stop. No new internal cut starts after Stop. Accepted
output is reconciled before successful termination. A stopped saved outcome is
idempotent and does not reactivate the same grant.

Status records use `opentsx.dag-status.v1`, track `B`, the signed job identity
and actual artifact digest. The status worker schedules a heartbeat every 15
seconds. Status failure prevents checkpoint advancement. Unknown fingerprints
and malformed single-object headers stop execution with the exact S3.2 record
context; they are not skipped.

## Distinguish the qualified interfaces

The [trusted-local replay and worker profiles](rust-runner-local.md) also
execute the existing local cleaning subset. Their availability does not extend
the signed B1 profile beyond pass-through.
The separate [local UI worker profile](wayang-rust-compose.md) uses a
publication gateway with captured grants and controller-only replacement.
Its HTTP-worker publication contract is separate from this signed
pass-through grant.
The public `agent` and signed
`conform` interfaces, fleet enrollment, ownership takeover, remote deployment
and production release gates remain outside this isolated profile. The protocol
acceptance example is a test fixture, not the signed S3 conformance handshake.
The separate [B3 site configuration](rust-runner-b3.md) supplies signed bounded
statistics, outbound plan polling and signed job-bound conformance. Its local
qualification and runtime/track pins do not widen this B1 pass-through grant.

CI defines native functional tests on hosted x86_64 and ARM64. The ARMv7 job
runs under QEMU and is explicitly supplemental. It does not satisfy the physical
ARMv7 criterion in [TASK-020
B1.2](../../../EVOLUTION/TASK-020-wayang-ts-operators.md#b1-rust-runner-foundation).

The [runner-plan implementation](../../../rust/tsx-runner/src/run_plan.rs)
enforces admission. The [normative
specification](../../datamodel-2026/20-WAYANG-TS-OPERATORS-SPEC.md#s52-track-b-runner)
defines the general fleet interfaces and their separate qualification
requirements.
