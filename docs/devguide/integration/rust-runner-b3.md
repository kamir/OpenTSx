# Operate signed bounded Rust statistics sites

Use `tsx-runner` 0.1.0 with the B3 site configuration to execute signed bounded
statistics and retain aggregate delivery while the signal cluster is offline.

You need Rust 1.99.1, native C/C++ build tools, Unix file ownership and a private
state directory. Broker execution needs distinct site and signal Apache Kafka
or KafScale Proxy endpoints and preprovisioned topics. The application owner
provides the TLS 1.3 plan service, site registration, trust material, separate
Ed25519 role keys, permitted jobs and local topic policy. Keep credentials
outside the checkout and outside test artifacts.

## Select the admitted profile

The B3 profile accepts one signed `opentsx.runner-plan.v1` wrapper containing
one bounded Rust assignment. Its fragment contains `decode.avro-soe` and
`stats.welford-ddsketch`, with alpha 0.01 and tumbling windows. Missing
statistics parameters retain the catalog defaults: alpha 0.01 and windows
`PT10M` and `PT1H`.
One assignment owns one complete Episode topic and one site aggregate output.
Every input partition has an immutable finite cut. Declared membership and
group/window scopes bind each partial and its completion evidence.

The [B2 catalog](rust-runner-b2.md) retains its trusted-local admission.
Its cleaning, model, feature, retrieval, pair, vector and DQ operations are not
admitted by this signed statistics profile. The [B1 configuration](rust-runner-b1.md)
still selects isolated continuous pass-through. The Java executor explicitly
rejects Rust assignments. Production release enrollment, continuous fleet
activation and device measurements remain separate. The
[bounded mixed W3 harness](wayang-mixed-federation.md) selects this existing
statistics profile at one Rust site beside two Java sites. Its retained
acceptance evidence has a separate bounded scope; it does not qualify mixed
continuous fleets. The physical ARM64 B4.2 measurement is open (D-T14: ARM64 only).

## Qualify the exact executable and site endpoint

The owner generates `opentsx.rust-b3-qualification.v1` from actual retained
test logs, a passing B1 protocol report for the site endpoint, and actual Java
and Rust producer-placement evidence. The placement reports cover 10,000 keys
over 12 partitions. The qualification pins the executable digest and version,
catalog, state ABI, endpoint, repository vector hashes, log hashes and an
explicit UTC validity interval. Missing, changed or expired evidence blocks
fresh activation. Changing the executable requires matching new evidence.

Inspect the generator's verified interface from the repository root:

```bash
python3 tools/rust_b3_qualification.py --help
```

`--runner` names the exact executable; repeat `--test-log` for the actual runner
and contract logs. `--protocol-report` and `--placement-report` name the
existing acceptance reports. `--placement-manifest`, when supplied, binds the
Java placement fixture. `--bootstrap` is the site Proxy endpoint, `--expires`
is an explicit future UTC instant ending in `Z`, and `--output` is a new local
evidence file. Existing output is retained rather than overwritten.

The ordinary site process has no admin or probe-topic capability. Its conformance
report identifies prior qualified vector, protocol and placement checks as prior
evidence. Fresh checks validate the authenticated challenge, local policy,
runtime identity, live partition metadata, retained input cuts, input fingerprints
and the complete sorted series inventory. The driver checks cross-site series
disjointness. Output fingerprints identify the configured statistics encoder,
including an empty output topic. This laboratory pin is not a production release
signature.

## Provision one local configuration

`opentsx.rust-site-config.v1` is a local JSON document, limited to 65,536 bytes.
Relative paths resolve against its directory. The driver cannot supply broker
addresses, credential paths, policy or state paths through the signed plan.
Unknown fields and duplicate JSON fields reject.
Identifiers are JSON strings matching `[a-z0-9][a-z0-9-]{0,62}`. File paths
are nonempty strings. Topic arrays contain unique valid `tsx` topic names.

| Field | Required value or constraint |
| --- | --- |
| `schema` | `opentsx.rust-site-config.v1` |
| `site`, `node` | Registered local identifier strings |
| `driver.url` | One HTTPS origin, at most 1,024 bytes, without credentials, path, query or fragment; an explicit port is canonical 1 to 65,535 |
| `driver.ca` | Explicit PEM trust roots for that origin, at most 65,536 bytes |
| `driver.certificate`, `driver.privateKey` | Local mTLS certificate chain and matching private key, each at most 65,536 bytes |
| `driverPublicKey` | Pinned Ed25519 SPKI PEM for signed plans, at most 16,384 bytes |
| `sitePrivateKey` | Distinct Ed25519 PKCS8 PEM for signed site reports, at most 16,384 bytes |
| `qualification` | Mandatory owner-local evidence file for this executable and site endpoint, at most 2,097,152 bytes |
| `jobs` | Array of 1 to 128 unique locally permitted job identifiers |
| `bindings` | Object with 2 to 16 identifier keys; each binding has string `bootstrap` and topic arrays `inputs`/`outputs` of at most 128 entries each; `site` and `signal` are required and use distinct endpoints |
| `policy.domains` | Nonempty unique array of allowed domain identifiers |
| `policy.inputs` | Binding names mapped to permitted input topics, within each binding's configured inventory |
| `policy.outputs` | `site` and `signal` mapped to permitted kinds: `aggregates`, `signals`, `dag-status` |
| `policy.catalogs` | Nonempty unique array of catalog strings; this profile requires `tsx-catalog/1` |
| `state` | Runtime-owned private directory |
| `limits` | Object containing all seven positive integer bounds below, with no implicit unlimited value |

| Limit | Maximum |
| --- | ---: |
| `records` | 10,000 |
| `payloadBytes` | 1,048,576 |
| `inputBytes` | 16,777,216 |
| `samples` | 1,000,000 |
| `outputBytes` | 16,777,216 |
| `series` | 1,024 |
| `stateBytes` | 1,048,576 |

`payloadBytes` cannot exceed `inputBytes`. A smaller declared bound remains
effective. Configured inputs can exceed the permitted input policy; a challenge
must satisfy both inventories before any broker read. The site aggregate topic
must be allowed on both site and signal bindings. Keep the site aggregate outbox
retained from offset zero and unchanged during its bounded forward operation.
Reserve that outbox for the admitted job/generation. Foreign or earlier-generation
records outside the completed inventory reject; this profile does not qualify
arbitrary shared or continuously growing outboxes.

Configuration and input files require ownership by the effective Unix user
and no group or other write permissions. TLS private keys and site signing
keys require owner-only permissions. Input
files must be regular files; final symbolic links reject. The state directory
must retain its private runtime ownership. The runtime pins local authority
and TLS material; changing configuration does
not authorize resuming an existing grant with different authority.

## Run conformance and receive plans

Use the local configuration for all three B3 entry points. `<site-config>` is the
owner-provisioned JSON path, `<job>` is a permitted job identifier, and
`<signed-plan>` is the exact compact JWS file from the registered driver.

Run one mode at a time against a given state directory. `agent` holds its
private state lock until Stop. A `409` delivery response refreshes conformance
for the locally permitted jobs before retrying the pull. An unpublished job
produces an explicit diagnostic and does not block other published jobs.

```bash
rust/tsx-runner/target/debug/tsx-runner conform --config <site-config> --job <job>
rust/tsx-runner/target/debug/tsx-runner agent --config <site-config>
rust/tsx-runner/target/debug/tsx-runner run --plan <signed-plan> --config <site-config>
```

`conform` obtains a job-bound challenge over mTLS, submits the signed report and
writes the compact report JWS to standard output only after acceptance. The
challenge hash covers its exact contract JSON bytes. The report binds site,
node, job, plan version, generation, catalog, artifact, state ABI and validity.
TLS uses explicit roots and hostname validation, accepts TLS 1.3 only, and
disables redirects and proxy fallback.

`agent` pulls S5.3 deliveries and verifies the original signed wrapper before
activation. If a pull requires conformance, it qualifies the locally provisioned
jobs and retries the pull; a manual `conform` call is not a startup prerequisite.
Its durable outcome precedes acknowledgement; its cursor advances
after acknowledgement. A retained acknowledgement can recover after conformance
expires. Fresh activation still requires current qualification and job-bound
conformance. Keep accepted command bytes and state together for recovery.
An exact already stored terminal status for the current job/version is also
idempotent after admission expires; changed status bodies still require current
admission. Current version, qualified identity and certificate-site checks
remain mandatory, and the retained retry does not repeat the callback.
Replacement waits for both the previous site's durable `stopped` status and
its accepted delivery acknowledgement, across every previous site in the job.
A stopped status alone does not retire the delivery. This keeps the old
version current while a lost terminal-status response is retried. Once the
delivery acknowledgement is durable, its exact retry remains valid after a
later generation is published; stale status requests still receive 409.

The [seven service endpoints and response codes](../../datamodel-2026/20-WAYANG-TS-OPERATORS-SPEC.md#s53-plan-service-api)
are shared with the Java plan service. A trusted but unregistered client
certificate receives 401; a certificate for another site receives 403; an unknown
job receives 404; stale status or aggregate versions receive 409. Malformed
requests use `400` with `error: bad-request`.

## Retain and forward bounded statistics

Execution publishes full `SeriesStats` SOE records and their aggregate headers
to the site outbox. Output acknowledgements precede durable completion. The
forwarder validates the exact completed record inventory and immutable retained
outbox cut before copying full records to the signal cluster. Its checkpoint
advances only after target acknowledgements. An unknown acknowledgement can
repeat a record; consumers deduplicate its deterministic key and revision.

The forwarder does not copy raw Episodes or arbitrary signal topics. The Java
inventory accepts Rust partials only with an explicit qualified runtime and
matching signed membership, scopes, assignment, artifact and closed-cut evidence.
The completeness coordinator appends final revisions after every expected partial
arrives; it does not replace earlier records.

## Restrict SSH launch to the fixed configuration

An owner-issued OpenSSH user certificate binds a non-root account to a forced
command containing the exact executable, `run --plan -` and fixed `--config`
path. Send only the compact signed plan on standard input. Pin the server host
key. Disable forwarding, interactive authentication, user startup files and
environment overrides. Client-requested commands and extra arguments do not
change the forced executable or configuration.

The fixture creates credentials outside the repository, uses private verified
copies of both executables and removes credentials when it exits. Inspect its
interface without starting a session:

```bash
python3 tools/rust_ssh_launch.py --help
```

The eight real SSH cases cover stop, refusal, runtime failure, command override,
extra arguments, wrong principal, untrusted certificate and host-key mismatch.
Exit 0 denotes a stopped run, exit 2 a refused plan with its message on standard
error, and exit 3 a runtime failure. A transport or certificate rejection is an
SSH failure, not a successful runner refusal.

An unexpected server termination reports its actual exit status. Inspect that
status together with `sshd.log` and the client refusal before classifying a
failed acceptance run.

OpenSSH 10.2 uses server exit 5 for an authentication attempt that ends in
preauthentication cleanup. The fixture admits it only for wrong principals
and untrusted certificates, with client exit 255, the required denial text,
empty standard output and no runtime identity. Other cases reject server
exit 5; signals, timeouts, child crashes and configuration refusals also fail.
See the [pinned exit definitions](https://github.com/openssh/openssh-portable/blob/V_10_2_P1/srclimit.h#L29-L32)
and [cleanup path](https://github.com/openssh/openssh-portable/blob/V_10_2_P1/sshd-session.c#L1310-L1313).

## Resolve admission and retained-log failures

| Message | Action |
| --- | --- |
| `driver and site signing keys must be distinct` | Provision different Ed25519 keys for the two roles. Preserve the existing accepted state when reviewing changed local authority. |
| `local runtime qualification is expired or not yet active` | Inspect the evidence interval and actual clock. Generate reviewed evidence from actual matching runs before a fresh activation. |
| `conformance contract hash differs from its exact bytes` | Check that the driver hashes the exact contract JSON bytes. Reformatting and then hashing a parsed tree changes the contract. |
| `conformance input cut is no longer retained` | Restore the required retained data or obtain a valid new signed job version. Do not skip missing offsets or edit accepted bytes. |
| `forwarder source outbox has lost its admitted retained log` | Preserve the original outbox and investigate retention before retrying. The forwarder cannot reconstruct deleted acknowledged partials from its cursor. |

## Inspect the executable regression evidence

| Boundary | Executable evidence |
| --- | --- |
| TLS, seven endpoints and 401/403/404/409 | [`agent_api`](../../../rust/tsx-runner/tests/agent_api.rs), [`RustSiteAdmissionTest`](../../../opentsx-wayang/src/test/java/org/opentsx/wayang/site/RustSiteAdmissionTest.java) |
| Actual CLI cold start, mTLS, cursor, SIGTERM and signed file/stdin replay | [`RustAgentDaemonIntegrationTest`](../../../opentsx-wayang/src/test/java/org/opentsx/wayang/RustAgentDaemonIntegrationTest.java) |
| Durable exact stopped/failed retries after admission expiry | [`TsxPlanServiceStatusReplayTest`](../../../opentsx-wayang/src/test/java/org/opentsx/wayang/site/TsxPlanServiceStatusReplayTest.java) |
| Replacement waits for every previous delivery acknowledgement | [`TsxPlanServiceRetirementTest`](../../../opentsx-wayang/src/test/java/org/opentsx/wayang/site/TsxPlanServiceRetirementTest.java) |
| Exact challenge hash, prior evidence and fresh cuts | [`conformance` tests](../../../rust/tsx-runner/src/conformance.rs) |
| Full W while signal delivery is unavailable, then catch-up and deduplication | [`RustOfflineCatchupIntegrationTest`](../../../opentsx-wayang/src/test/java/org/opentsx/wayang/RustOfflineCatchupIntegrationTest.java) |
| Forced-command and credential isolation | [`ssh_launch`](../../../rust/tsx-runner/tests/ssh_launch.rs), [`SSH helper regressions`](../../../tools/test_rust_ssh_launch.py) |
| Owner-local qualification evidence | [`qualification generator regressions`](../../../tools/test_rust_b3_qualification.py) |

Read [TASK-020 B3](../../../EVOLUTION/TASK-020-wayang-ts-operators.md#b3-agent-offline-operation-ssh-launch)
for the acceptance results. Under D-T14 no ARMv7 hardware is required; ARMv7
QEMU results stay supplementary.

## Read connection ends in the validation harnesses

The
[validation observer](../../../opentsx-wayang/src/test/java/org/opentsx/wayang/LatencyProtocolObserver.java)
relays Kafka traffic between test clients and the KafScale Proxy. It
classifies the end of a connection by side.

An end of the client stream is abandonment by the client. This covers a clean
close and a reset, between frames and inside a frame. It also covers a socket
error while the observer writes a response to the client. Abandonment does not
fail the check.

The observer records a response, such as a Produce acknowledgement or the
topic IDs of a Metadata response, only after it has written and flushed that
response to the client. A response whose write fails is not recorded.

The observer reads the client stream to its end and validates every complete
frame. At that end it closes the client side, even while a response write to
that client is blocked. It shuts down its output toward the broker and drops
the pending requests of the connection. Broker responses that arrive after
that end, or after a failed write to the client, are read and dropped without
being recorded.

Before the observer reaches the end of the client stream, these errors fail
the check:

- a complete client frame that fails validation: an invalid length, an
  unparsable header or request, or a broken measurement requirement
- an unknown response correlation
- a close, reset or other I/O error on the broker side, with or without
  pending requests

A response correlation remains pending until its response is forwarded and
flushed or its client ends the connection. When the broker and the client
close independently at nearly the same time, thread timing decides which end
the observer sees first.

The signal link of the
[offline test](../../../opentsx-wayang/src/test/java/org/opentsx/wayang/RustOfflineCatchupIntegrationTest.java)
ends a connection without a failure when its runner side closes or resets. On a
clean end of the runner stream it shuts down its output toward the observer and
still relays responses to the runner. Errors while both sides are open fail
the check.

The deterministic
[observer tests](../../../opentsx-wayang/src/test/java/org/opentsx/wayang/LatencyProtocolObserverTest.java),
[connection tests](../../../opentsx-wayang/src/test/java/org/opentsx/wayang/LatencyProtocolObserverConnectionTest.java)
and [signal link tests](../../../opentsx-wayang/src/test/java/org/opentsx/wayang/SignalLinkTest.java)
check these boundaries with streams and loopback sockets, without sleeps.
