# Replay a bounded topic with the Rust runner

Run the `opentsx.local-bounded-run.v1` profile with `tsx-runner` 0.1.0 and three isolated Compose workers.

You need Linux or macOS, Docker Compose 2.24.4 or later, Docker access, JDK 21 and Maven 3.9.8
for the Java fixture checks.
Use a disposable local broker and trusted request files. This profile does not implement signed fleet activation,
the remote Wayang executor, automatic failover or production access controls.
For the separate workbench-to-Wayang-to-HTTP-worker path, use the [bounded rollout guide](wayang-rust-compose.md).

## Preserve the assignment boundary

The request embeds an existing
[`opentsx.execution-assignment.v1`](wayang-shared-contracts.md) document as exact text.
Its source hash, fragment hash, runtime, bounded cut and whole-topic ownership retain their existing meanings.
The runner admits one bounded Rust assignment with one whole input topic and one episode sink.
It checks the executable fragment separately; structural assignment validation alone does not establish operator support.
The Episode codec accepts Avro single-object encoding with fingerprint `8985f02b48c77195`.
It does not resolve a schema registry or decode arbitrary Avro record types; see the
[codec](../../../rust/tsx-runner/src/wire.rs).

The [pipeline](../../../rust/tsx-runner/src/operators.rs) accepts pass-through or one linear cleaning chain:
`dq.checks` with `PLAUSIBLE_RANGE`, `convert.unit`, and an optional trailing `resample`.
Other operators, cycles, missing inputs and unused graph nodes reject.
Cleaning rejects nonzero revisions, per-value quality and infinite input values; NaN remains a gap.
One derived series has exactly one source-series owner, including nonoverlapping episodes and pending resample
windows. A changed source unit under the same identity rejects, even when conversion supplies a fixed output unit.
The bounded profile does not execute statistics, joins, vectors, segmentation or the remaining Java catalog.

Every partition belongs to the same worker. Internal partition positions describe its finite read, never separate
placement units. The three-worker demonstration runs three independent bounded deployments, `rust-compose-1`,
`rust-compose-2` and `rust-compose-3`, on different topics with one, two and three partitions.
An oversized topic fails admission instead of being divided across workers.

The local file is trusted configuration. It is not a signed site plan or an authorization token.
The `generation` and `fence` fields do not enforce ownership against another process or a disconnected owner.
Use one writer and one checkpoint directory per assignment. Do not launch another owner while it runs.

## Bind the local request

The request has the following fields. Unknown fields reject.

| Field | Contract |
| --- | --- |
| `schema` | Exact string `opentsx.local-bounded-run.v1`. |
| `assignmentText` | Exact JSON text of the shared assignment envelope, including its original source and compiled fragment text. |
| `bindings` | Exactly one binding ID mapped to its local broker address and permitted topic names. |
| `bindings.<id>.bootstrap` | Kafka bootstrap address for that binding. Compose workers use `kafscale-broker` and the configured broker port, default `39092`. |
| `bindings.<id>.inputs` | Singleton allowlist containing exactly the assignment's input topic. |
| `bindings.<id>.outputs` | Singleton allowlist containing exactly the selected output topic. |
| `sink` | One output binding for the fragment's selected episode sink. |
| `sink.name` | Name of the existing DAG sink. |
| `sink.binding` | Same binding ID as the input, resolved through `bindings`. |
| `sink.topic` | Output topic allowed by that binding and consistent with the selected sink. |
| `sink.partitionCount` | Canonical decimal string from `1` to `128`, describing the complete output partition inventory. |
| `createdAtMicros` | Canonical signed 64-bit decimal string. Fixed provenance clock reused on retry. |
| `limits` | Required positive integer resource limits below. |

Do not place another DAG, source start policy or replacement input cut in the local configuration.
The assignment remains authoritative for those values. Binding aliases do not establish distinct sources;
prepare the request with one authoritative binding for each broker/topic identity.

| Limit | Maximum | Bounded resource |
| --- | --- | --- |
| `records` | `10000` | Input record count and declared cut span. |
| `payloadBytes` | `1048576` | Bytes of an individual input or output payload; input is checked before decoding. |
| `inputBytes` | `16777216` | Total input payload bytes; at least `payloadBytes`. |
| `samples` | `100000` | Decoded sample count and output value capacity. |
| `outputBytes` | `16777216` | Total encoded output bytes before publication. |
| `series` | `64` | Number of distinct input series and their keyed states. |
| `stateBytes` | `1048576` | Serialized retained operator state. |

There is no unlimited batch mode. Resource limits are explicit request fields, not deployment defaults.
The local request has at most 65,536 UTF-8 bytes. Input topics have at most 128 internal partitions,
and each input episode has at most 25,000 samples, further restricted by `samples`.
The shared assignment retains its 65,536-byte envelope limit, 32,768-byte source/fragment limits,
64-level JSON depth limit and complete ascending partition inventory.
See the [shared contract reference](wayang-shared-contracts.md#assign-whole-topics-and-preserve-exact-internal-positions).

## Keep input, output and state together

The source starts direct fixed-offset reads for every internal partition, without consumer-group subscription or commits.
The declared end offsets remain fixed. Retention loss, a missing partition or an incompatible partition inventory
fails the run instead of changing its cut. A bounded offset range establishes a finite read, not a durable
Iceberg-style input seal or a cross-topic snapshot guarantee.
Keep the disposable input topics unchanged through replay. This profile does not bind a topic incarnation
or detect replacement data at the same offsets after a topic is recreated.

The runner reads and validates the capped batch, computes its output and retained state, and checks output limits
before the first produce. The Kafka sink uses `acks=all`, disables idempotence and transactions, and permits one
request in flight. Every produce result must succeed before checkpoint publication.
Output records require `tStart.div_euclid(1000) > 0` milliseconds. Zero and negative transport timestamps reject
before any output is enqueued; native timestamp zero means the producer's current clock, not epoch zero.
The Episode payload retains its microsecond timestamps. Check the output window starts when resampling near epoch zero.

One completed checkpoint binds the accepted request, exact next input positions, state ABI, retained operator
state and completion outcome. It is written through a temporary file, flushed and renamed over the target.
An unreadable or incompatible checkpoint rejects. A new source hash, cut, output binding or state ABI does not
silently reset it. Use a separately admitted run and checkpoint location for a different computation.

Before that checkpoint commits, a crash can replay the entire cut and duplicate records already acknowledged
by the broker. Output identities and the fixed provenance clock permit deterministic replay; delivery remains
at least once. An identical completed request returns the saved completion outcome without writing again.
A changed request at the same checkpoint conflicts.
Pass-through preserves the input provenance; derived cleaning output uses the fixed request clock.

Open resample windows remain in retained state. Reaching the finite Kafka cut does not close an unfinished
event-time window or fabricate a trailing aggregate. Output parity and checkpoint compatibility are separate:
this profile does not import a Java checkpoint.
The private `opentsx.rust.clean-state.v1` state includes `outputOwners`, a derived-series-to-source-series map.
Restore recomputes ownership from retained source keys and rejects missing, falsified or colliding entries.

## Read the completion receipt

Successful `replay` writes one JSON receipt to standard output and exits `0`.
Argument or admission errors exit `2`; execution or checkpoint errors exit `3`.
The [receipt and checkpoint implementation](../../../rust/tsx-runner/src/checkpoint.rs) uses these fields:

| Field | Type and meaning |
| --- | --- |
| `schema` | Exact string `opentsx.local-receipt.v1`. |
| `requestHash` | SHA-256 hex digest of the exact UTF-8 local request bytes. |
| `artifactDigest` | `sha256:` digest identifying the actual runner executable. |
| `deployment`, `generation`, `assignment`, `node` | Assignment identity strings; generation remains a canonical decimal string. |
| `inputTopic`, `outputTopic` | Admitted source and destination topic names. |
| `positions` | Complete ordered array of `{partition, next}` canonical decimal strings; each next position equals the bounded cut's `to`. |
| `inputRecords`, `outputRecords` | Integer record counts for this execution, also returned by a completed retry. |
| `outputDigest` | SHA-256 digest of the prepared output records in deterministic emission order. |
| `stateDigest` | SHA-256 digest of the serialized retained Rust state. |

The checkpoint envelope has schema `opentsx.local-checkpoint.v1`, a checksum and a payload containing
`requestHash`, `receipt` and `state`. Floating state values retain their bit patterns in the private state ABI.
The runner locks its checkpoint and rejects a concurrent local owner. This file lock does not enforce ownership
across nodes or separate checkpoint paths.

## Verify three workers against Java

Run from the repository root. Set `JAVA_HOME` to JDK 21 when it is not your default JDK.
The [verification helper](../../../tools/verify_rust_runner_compose.py) builds the Java fixture and Linux runner,
starts its isolated broker project, waits for Kafka metadata, and prepares three independent bounded jobs.
It refuses an existing project or a busy port instead of stopping another service.

```bash
python3 tools/verify_rust_runner_compose.py
```

The existing `KAFSCALE_BROKER_PORT` setting selects the same internal, advertised and loopback-published port.
It defaults to `39092` and must be a canonical integer from `1` to `65535`.
If that port is occupied, select a free port explicitly; the helper does not stop its owner or choose a fallback.

```bash
KAFSCALE_BROKER_PORT=39093 python3 tools/verify_rust_runner_compose.py
```

The helper checks three phases: initial execution, identical completed requests without additional output,
and whole-cut replay after removing the three completed checkpoints.
The fixture expects 12 distinct episodes: two, four and six from workers one, two and three.
Initial execution and completed retries each leave 12 broker records. Whole-cut replay leaves 24 records
with the same 12 distinct identities.
Its [Java fixture](../../../opentsx-wayang/src/test/java/org/opentsx/wayang/RustRunnerComposeFixture.java)
uses the existing `SeriesStream` with range checking, unit conversion and a trailing 20-second mean resample.
Each input partition contains two four-sample episodes from its own series.

Expected records come from the Java operators. The fixture records Java's producer value separately, then
sets only expected provenance `producer` to `tsx-runner/0.1.0` and `createdAt` to the fixed request clock.
It compares the resulting complete canonical Avro bytes with Rust output, including summary fields, maps,
identities and axes. It also verifies Kafka keys, Java Murmur2 partition placement, timestamps and the schema
header. Replay duplicates must carry the same episode IDs and payload bytes.
Returned receipts must equal their durable checkpoint receipts. Completed retries retain the original checkpoint
bytes; whole-cut replay must reproduce its receipt and retained state.
This explicit runtime-provenance normalization is confined to the fixture; the Java operator code is unchanged.

Evidence remains under `target/rust-runner-compose`: `expected.json`, three request files,
`artifact-digest.txt`, `verify-initial.json`, `verify-completed-retry.json`,
`verify-checkpoint-replay.json`, original completed checkpoints and phase logs.
The helper removes only its own Compose containers and volumes after the run.
Use `--keep` to retain that project, or `--skip-build` to reuse the existing Linux binary and Java classes.
Read the supported options before a retained run:

```bash
python3 tools/verify_rust_runner_compose.py --help
```

## Isolate the three Compose workers

The [standalone Compose file](../../../docker-compose.rust-runner.yml) defines project `opentsx-rust-runner`.
Its only published port is the configured loopback broker port, default `127.0.0.1:39092`.
The KafScale v1.6.0 broker uses `KAFSCALE_USE_MEMORY_S3=1`, the same in-memory storage and metadata mode
used by the existing [conformance CI job](../../../.github/workflows/ci.yml).
It serves the real Kafka/KafScale protocol without MinIO or etcd containers.
Worker replay runs while that broker remains alive. Broker restart and S3 durability are outside this test profile.
Project-scoped networks and the builder cache volume keep its lifecycle separate from other Compose applications.

The `runner-build` service compiles a Linux binary in the prebuilt `rust:1.99.1-bookworm` image.
It has no image-build step. The source tree is mounted read-only, the Cargo cache uses a named volume, and
the binary is written to `target/rust-runner-linux/release/tsx-runner`.
The image tag is listed by the [official Rust image](https://hub.docker.com/_/rust).

The `workers` profile contains `runner-1`, `runner-2` and `runner-3`. They run under the host UID/GID supplied
through `RUST_RUNNER_UID` and `RUST_RUNNER_GID`. Their root filesystem, binary and requests are read-only.
Each worker writes only its own state mount and exits after its bounded replay; Compose does not restart it.

| Worker | Request file under `target/rust-runner-compose/requests` | Writable directory under `target/rust-runner-compose/state` |
| --- | --- | --- |
| `runner-1` | `worker-1.json` | `worker-1` |
| `runner-2` | `worker-2.json` | `worker-2` |
| `runner-3` | `worker-3.json` | `worker-3` |

Inside each worker the request directory is `/requests`, the state directory is `/state`, and the checkpoint
is `/state/completed.json`. The binary entry point is `/runner/release/tsx-runner`.
Precreate the fixture directories and Linux binary before starting the workers.

The broker advertises `kafscale-broker` with the configured port to both workers and the host-side Java fixture checks.
For host Java, use a project-local hosts file containing `127.0.0.1 kafscale-broker` through the
[`jdk.net.hosts.file`](https://docs.oracle.com/en/java/javase/21/core/java-networking.html)
system property. This confines the test mapping to that JVM and does not edit the system hosts file.
The JDK property replaces name-service lookups with that file; include every hostname the fixture JVM needs.

## Keep the local profile separate from fleet execution

The production [runner specification](../../datamodel-2026/20-WAYANG-TS-OPERATORS-SPEC.md#s52-track-b-runner)
defines signed `run --plan`, control polling and conformance reports. Local `replay` implements none of those
interfaces. It opens no inbound worker service and does not load request-supplied code.

The [unified delivery plan](wayang-unified-plan.md#preserve-the-local-proof-and-qualify-the-remaining-profiles)
separates this replay fixture from the implemented bounded UI rollout. Production stale-output rejection, signed
fleet control and the sealed bounded storage route remain separate qualification gates.
A successful three-worker replay proves the selected local workload and its tested failure boundaries.
It does not qualify production activation, continuous recovery or transparent cross-runtime state migration.
