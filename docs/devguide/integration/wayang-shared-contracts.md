# Validate the shared Java and Rust assignment contract

Validate the first shared execution-assignment contract on PR #258 base `dc5f05043158a7ddf80ac156f9690ca92cf93788`;
the implementations use Java 17 or 21 and Rust 1.99.1.

You need the [schema](../../datamodel-2026/schemas/opentsx.execution-assignment.v1.schema.json),
[shared vectors](../../datamodel-2026/testvectors/execution-contracts.v1.json), Maven and the Rust toolchain.
**Implemented scope: offline preflight validation and exact-text hashing.** Validation does not authorize a
deployment, prove live exclusive ownership, validate an entire operator graph or execute a runner.
The JSON schema is a structural check. Java/Rust semantic validation is mandatory for UTF-8 byte ceilings,
integer bounds, hashes, interval order and ownership; schema acceptance alone is insufficient.

## Preserve the original DAG and its fragment separately

The UI keeps its existing `opentsx.dag.v1` or `opentsx.dag.v2` format. A compiler produces complete executable
fragments of that DAG. The assignment envelope describes one fragment on one node; it is not another user DAG.
The [Java library](../../../opentsx-model/src/main/java/org/opentsx/model/contracts/ExecutionAssignments.java)
and [Rust library](../../../rust/tsx-contracts/src/lib.rs) implement the same refusal contract.

| Required field | Type and admitted value |
| --- | --- |
| `schema` | Exact string `opentsx.execution-assignment.v1`. |
| `deployment`, `assignment`, `attempt`, `fragment`, `site`, `node` | Strings matching `[a-z0-9][a-z0-9-]{0,62}`. Site identifies a data/security location; node identifies a runtime instance there. |
| `generation`, `fence` | Canonical positive decimal strings, maximum `9223372036854775807`. These are asserted identities, not proof of authority. |
| `runtime` | `java` or `rust`. |
| `catalog` | Exact string `tsx-catalog/1`; operator availability is a later admission gate. |
| `artifactDigest` | `sha256:` followed by 64 lowercase hexadecimal characters. The offline validator checks its form, not an artifact signature. |
| `sourceDagText` | Exact original UI text, JSON or YAML, at most 32,768 UTF-8 bytes. Its graph semantics are not parsed here. |
| `specHash` | SHA-256 of the exact UTF-8 `sourceDagText`, as 64 lowercase hexadecimal characters. |
| `fragmentText` | Compiler-produced JSON object with a source object and nonempty node and sink arrays; at most 32,768 UTF-8 bytes. |
| `fragmentHash` | SHA-256 of the exact UTF-8 `fragmentText`, as 64 lowercase hexadecimal characters. |
| `mode` | `bounded` or `continuous`. |
| `inputs` | Between 1 and 128 exact Kafka partition bindings described below. This first contract does not describe an Iceberg scan. |

No envelope fields are optional and unknown fields reject. The envelope has at most 65,536 UTF-8 bytes.
Outer and fragment JSON reject duplicate decoded keys, malformed Unicode, trailing documents and container depth
above 64. Parsed JSON number tokens must be finite in binary64; numerical operator admission remains separate.
The original `sourceDagText` stays opaque, so this parser rule does not reformat or interpret that source text.
JSON string escaping may change transport bytes while retaining the exact decoded source text.
Signatures must cover the actual transport bytes; this library does not perform signature verification.

Moving a fragment from Java to Rust, or to another node, leaves `specHash` unchanged. Changing the source text,
including whitespace, changes this legacy hash. A fragment cut changes `fragmentHash`, not `specHash`.
These hashes do not implement canonical scientific equivalence. A separately versioned semantic hash remains a
delivery requirement; silently replacing legacy `specHash` would alter the
[existing episode identity](../../../opentsx-retrieval/src/main/java/org/opentsx/retrieval/clean/SeriesStream.java#L151).

## Bind exact partitions without numeric rounding

Every input has exactly `binding`, `topic`, `partition`, `from`, and, only for bounded mode, `to`.

| Input field | Rule |
| --- | --- |
| `binding` | Opaque cluster/source binding ID using the identifier grammar above; no credentials or host address. The coordinator must resolve aliases to one authoritative source identity before set validation. |
| `topic` | At most 249 ASCII characters; `tsx.<domain>.<kind>.v<n>`, with a lowercase/digit initial character in each segment. Domain permits underscores and hyphens; kind segments permit hyphens; version is positive decimal. |
| `partition` | Canonical nonnegative decimal string, maximum `2147483647`. |
| `from` | Canonical nonnegative decimal string, maximum `9223372036854775807`. |
| `to` | Same range as `from`; required in bounded mode and forbidden in continuous mode. The interval is `[from,to)`, including an empty interval. |

Leading zeros, signs, fractional values and JSON numbers reject. The shared fixture includes
`9007199254740993`, which a JavaScript number cannot represent exactly. Bounded cuts must be captured and persisted
before these offsets become admitted execution inputs. Continuous mode has a fixed accepted start and no frozen end.

Set validation first validates every individual assignment. It then requires one deployment, generation,
source hash, mode and catalog; each fragment ID has one fragment hash. Assignment IDs must be unique.
Every `(fragment,binding,topic,partition)` belongs to at most one assignment, including across different sites/nodes.
Different fragments may read the same partition when the physical plan explicitly permits it.
The checker detects overlap; it does not prove complete coverage against an external inventory.
It does not check node-to-site membership, operator availability or a partitioner's series-placement contract.

## Interpret refusal codes in a fixed order

| Order | Code | Rejected condition |
| --- | --- | --- |
| 1 | `assignment.limit` | Envelope byte ceiling exceeded. |
| 2 | `assignment.json` | Invalid outer JSON, Unicode, duplicate keys, depth, nonfinite binary64 number token or non-object root. |
| 3 | `assignment.fields` | Unknown or missing top-level fields. |
| 4 | `assignment.version` | Unsupported envelope schema. |
| 5 | `assignment.identity` | Invalid deployment, assignment, attempt, fragment, site or node ID. |
| 6 | `assignment.epoch` | Invalid generation or fence. |
| 7 | `assignment.runtime` | Unsupported runtime/catalog or malformed artifact digest. |
| 8 | `assignment.dag`, then `assignment.hash` | Invalid source/fragment text type or byte size; then malformed or mismatching content hashes. |
| 9 | `assignment.mode` | Unsupported execution mode. |
| 10 | `assignment.dag` | Invalid fragment JSON, nonfinite binary64 number token or missing source/nodes/sinks shape. |
| 11 | `assignment.input` | Invalid input list, fields, binding or topic. Every input shape is checked before offsets. |
| 12 | `assignment.offset` | Invalid partition/start/end decimal or reversed range. |
| 13 | `assignment.ownership` | Duplicate input ownership within one assignment. |

After all individuals pass, a set rejects inconsistent deployment/fragment context with `assignment.context`,
duplicate assignment IDs with `assignment.duplicate`, then duplicate ownership with `assignment.ownership`.
An empty set rejects with `assignment.context`. Codes are exact API results; diagnostic paths and UI messages
remain additional work in the [unified plan](wayang-unified-plan.md).

## Reproduce the shared cases

Run from the repository root. Dependency downloads require access or populated local caches.

```bash
python3 tools/execution_contract_vectors.py --check
mvn -B -ntp -pl opentsx-model verify
cargo +1.99.1 test --offline --locked --manifest-path rust/tsx-contracts/Cargo.toml -- --nocapture
```

The generator's check compares the committed file byte for byte. The Java test creates a named dynamic test for
each shared case. The Rust integration test reads that same file and prints the number of assignment, set and
hash cases checked. The fixture contains valid and invalid assignments, two-node disjoint and overlapping
ownership, mixed Java/Rust placement, byte/hash identity and values at integer limits.

Regenerate the fixture after changing its generator, then check the output:

```bash
python3 tools/execution_contract_vectors.py
python3 tools/execution_contract_vectors.py --check
```

The command writes `docs/datamodel-2026/testvectors/execution-contracts.v1.json`.
Algorithm fixtures such as `pair-xcorr.v1.json` and statistics/segment vectors retain their existing producers.
These assignment cases establish neither numerical operator parity nor deployed Rust execution.
