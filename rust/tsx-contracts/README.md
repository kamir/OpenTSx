# Validate shared execution assignments

Validate OpenTSx execution-assignment envelopes offline with the Rust library, verified with Rust 1.99.1.

You need the installed Rust 1.99.1 toolchain and the dependencies recorded in [Cargo.lock](Cargo.lock).
Offline commands require those dependencies in your Cargo cache. Run from the repository root.

## Run the shared contract cases

The integration test reads the repository's shared
[execution contract vectors](../../docs/datamodel-2026/testvectors/execution-contracts.v1.json) directly.
It checks assignment admission, assignment-set consistency, exact UTF-8 bytes and SHA-256 hashes.

```bash
cargo +1.99.1 test --offline --locked --manifest-path rust/tsx-contracts/Cargo.toml -- --nocapture
```

Check that `shared_execution_contract_vectors` ran and printed the assignment, set and hash case counts.
The [toolchain file](rust-toolchain.toml) pins Rust 1.99.1 when working inside this crate.
The package's supported compiler baseline is Rust 1.89.0, also checked locally.

## Call the admission functions

| Function | Input | Output |
| --- | --- | --- |
| `validate(input: &str)` | One raw assignment JSON document. | `Result<(), &'static str>` with the shared refusal code. |
| `validate_set(inputs: &[&str])` | Raw JSON documents for one deployment context. | `Result<(), &'static str>` after individual admission, context, assignment-ID and ownership checks. |
| `sha256(text: &str)` | Exact UTF-8 text. | Lowercase SHA-256 hex, without reformatting or normalization. |
| `sha256_bytes(bytes: &[u8])` | Exact binary artifact bytes. | Lowercase SHA-256 hex. |
| `parse_json(input: &str, max_bytes: usize)` | Local runtime document and its explicit UTF-8 byte ceiling. | Parsed JSON or `json.limit`/`json.invalid`; duplicate keys, invalid numbers/Unicode, trailing documents and depth above 64 reject. |
| `valid_topic(value: &str)` | One input or output topic name. | Whether the name satisfies the shared OpenTSx topic contract. |

The [implementation](src/lib.rs) rejects unknown or missing envelope fields, duplicate JSON keys,
invalid JSON Unicode escapes, trailing documents and container nesting above 64.
Assignment input is limited to 2,162,688 UTF-8 bytes (`MAX_ASSIGNMENT_BYTES`). Each source or fragment text is
limited to 524,288 bytes (`MAX_DAG_BYTES`), enough for a source inventory of 16,384 series ids.
An assignment contains between 1 and 128 whole-topic inputs. A topic is the atomic placement unit.
Identifiers, runtime/catalog identity and artifact/content digests follow the shared contract.
Epochs, fences, partition counts and internal recovery positions use canonical decimal strings with checked bounds.
Each input names `binding`, `topic`, positive `partitionCount` and the complete ordered `positions` array.
Positions must cover every declared partition exactly once, in ascending order from zero.
Bounded positions require exclusive end offsets; continuous positions forbid them.
A positions array that omits, repeats or reorders declared partitions returns `assignment.coverage`.
Positions describe internal recovery state and cannot split a topic between deployment owners.
Ownership is exclusive per `(binding, topic)` within an assignment and per `(fragment, binding, topic)` in a set.
These checks prove consistency with the declared inventory; broker inventory and durable ownership need separate checks.

`sourceDagText` retains the exact original UI text. `fragmentText` carries the separate JSON stage.
Fragment validation checks an object with a source object and nonempty node and sink arrays.
Full graph validation, operator catalog admission, signature checks, durable ownership and execution
belong to their respective runtime components. This library opens no network connections or workers.

## Preserve the dependency boundary

[Cargo.toml](Cargo.toml) pins two direct dependencies. `serde_json` validates JSON grammar and Unicode.
JSON numeric tokens must remain finite in binary64; epochs, partitions and offsets remain exact decimal strings.
The opaque original `sourceDagText` is not parsed, and both text hashes preserve their exact UTF-8 bytes.
`sha2` computes the exact-text SHA-256 identities. The lockfile pins the transitive dependency graph.
The structural pass records decoded keys per object and enforces the container-depth limit before parsing.
