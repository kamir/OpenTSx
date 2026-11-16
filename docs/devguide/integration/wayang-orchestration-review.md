# Review the multi-node Wayang orchestration design

Use this assessment to decide contracts and acceptance gates for UI DAGs deployed through Wayang to Rust nodes.
Source inspection covers OpenTSx `dc5f05043158a7ddf80ac156f9690ca92cf93788`
and Wayang 1.1.1 at `61deee439e0f2267301dd6dc0ef8cf9952268a74`.

You need the [operator specification](../../datamodel-2026/20-WAYANG-TS-OPERATORS-SPEC.md)
and its [runner-plan schema](../../datamodel-2026/schemas/opentsx.runner-plan.v1.schema.json).
The decisions and gates below are proposed closure conditions, not implemented deployment guarantees.
This review records neither production approval nor executed runtime qualification.

## Establish the inspected boundary

The baseline specification labels itself a draft that is not implemented
([status](https://github.com/kamir/OpenTSx/blob/dc5f05043158a7ddf80ac156f9690ca92cf93788/docs/datamodel-2026/20-WAYANG-TS-OPERATORS-SPEC.md#L3)).
The Java plugin registers collection-source, map and local-callback mappings
([plugin](../../../opentsx-wayang/src/main/java/org/opentsx/wayang/TsxJavaPlugin.java#L25)).
Its smoke-test source builds a collection-to-map-to-callback plan
([test](../../../opentsx-wayang/src/test/java/org/opentsx/wayang/WayangSmokeTest.java#L24)).
Those sources do not establish network deployment, Rust execution, recovery or production capacity.
The translator, site executor and Rust foundation are unchecked tasks in
[A4](https://github.com/kamir/OpenTSx/blob/dc5f05043158a7ddf80ac156f9690ca92cf93788/EVOLUTION/TASK-020-wayang-ts-operators.md#L99) and
[B1](https://github.com/kamir/OpenTSx/blob/dc5f05043158a7ddf80ac156f9690ca92cf93788/EVOLUTION/TASK-020-wayang-ts-operators.md#L120).

The proposed site stage is copied unchanged to every site
([S2.4](https://github.com/kamir/OpenTSx/blob/dc5f05043158a7ddf80ac156f9690ca92cf93788/docs/datamodel-2026/20-WAYANG-TS-OPERATORS-SPEC.md#L173)).
That contract describes site federation, rather than arbitrary subgraph placement across nodes.
The runner schema supplies topic partition counts but no assigned partition sets or node ownership
([input](https://github.com/kamir/OpenTSx/blob/dc5f05043158a7ddf80ac156f9690ca92cf93788/docs/datamodel-2026/schemas/opentsx.runner-plan.v1.schema.json#L40)).
These are the primary gaps between the inspected baseline and the requested orchestration target.

## Separate computation from deployment

Adopt three distinct descriptions as the design boundary:

1. The logical DAG contains admitted domain operations, resolved parameters, typed edges and logical outputs.
2. The physical deployment binds that DAG to execution profiles, stages, channels, capabilities and resources.
3. Each complete node fragment binds its stage work, inputs, outputs, partition ownership and generation.

The node fragment contains enough admitted work for local execution without an RPC for each operator.
Preserve source-node identity through lowering so validation and status can refer to the UI selection.
Pin catalog, operator, wire and state compatibility separately from a human-readable node name.
Keep credentials and executable user code outside these descriptions.
This extends the existing validation and topic boundaries in
[S2](https://github.com/kamir/OpenTSx/blob/dc5f05043158a7ddf80ac156f9690ca92cf93788/docs/datamodel-2026/20-WAYANG-TS-OPERATORS-SPEC.md#L145).
The user DAG language stays unchanged.

Use one Rust execution platform with several node resources.
Wayang defines a platform as an execution engine and creates an executor through its factory
([Platform](https://github.com/apache/wayang/blob/61deee439e0f2267301dd6dc0ef8cf9952268a74/wayang-commons/wayang-core/src/main/java/org/apache/wayang/core/platform/Platform.java#L35)).
Node placement and fragment fanout are additional responsibilities of that platform implementation.
This recommendation does not require a platform instance for every physical node.
It also does not require a new domain logical operator where an existing Wayang operator preserves the contract.

Use a custom executor extending `ExecutorTemplate` to compile and dispatch complete stage fragments.
The executor interface accepts an `ExecutionStage`
([Executor](https://github.com/apache/wayang/blob/61deee439e0f2267301dd6dc0ef8cf9952268a74/wayang-commons/wayang-core/src/main/java/org/apache/wayang/core/platform/Executor.java#L31)).
`PushExecutorTemplate` delegates individual execution tasks to its abstract task method
([task execution](https://github.com/apache/wayang/blob/61deee439e0f2267301dd6dc0ef8cf9952268a74/wayang-commons/wayang-core/src/main/java/org/apache/wayang/core/platform/PushExecutorTemplate.java#L81)).
Stage fanout, bounded dispatch concurrency, cancellation and durable acknowledgement need explicit implementation.
Do not infer them from the superclass or send a separate remote call for every logical operator.

## Resolve the ranked contract gaps

### Block placement until ownership and edges are complete

Define the ownership unit using deployment, stage, source identity and exact partition IDs.
Include node identity and the ownership generation in each assignment.
Prove complete, disjoint coverage against the admitted source inventory before activation.
Do not treat a topic's partition count as an assignment.

Classify every stage edge as local, repartitioned, or cross-node.
Bind its cluster and topic identity, payload schema, key encoding, partitioner and partition set.
Record raw-data residency policy and permitted destination before selecting placement.
Multi-input operators require compatible ownership and ordering on every input.
A key change requires a declared repartition boundary, as the concept already identifies
([flow shapes](https://github.com/kamir/OpenTSx/blob/dc5f05043158a7ddf80ac156f9690ca92cf93788/docs/datamodel-2026/20-WAYANG-TS-OPERATORS.md#L329)).
Treat network buffering, output acknowledgement and outage capacity as bounded resources.
Do not infer a direct node-to-node transport from a logical edge; approved broker channels are a valid first choice.

**Gate:** deterministic fixtures reject duplicate owners, missing partitions, incompatible input partitioners,
unbound edges and prohibited raw movement. A two-node integration run records actual assignments and channels.
The integration gate includes source recreation, partition changes, skew and an unavailable destination.

### Separate bounded completion from continuous acceptance

Define two execution profiles that share operator and wire contracts:

| Profile | Required evidence | Meaning of success |
| --- | --- | --- |
| Bounded | Fixed source cut, verified immutable input, explicit initial state, complete outputs and recoverable publication. | The admitted finite computation has a verified published result. |
| Continuous | Durable starts and checkpoints, acknowledged outputs, bounded state and an explicit at-least-once duplicate policy. | Deployment acceptance produces a durable handle; analytics remains running until stopped or failed. |

The baseline has starts but no fixed bounded ends
([start](https://github.com/kamir/OpenTSx/blob/dc5f05043158a7ddf80ac156f9690ca92cf93788/docs/datamodel-2026/schemas/opentsx.runner-plan.v1.schema.json#L21)).
Its Java proposal repeatedly captures increment ends
([increments](https://github.com/kamir/OpenTSx/blob/dc5f05043158a7ddf80ac156f9690ca92cf93788/docs/datamodel-2026/20-WAYANG-TS-OPERATORS-SPEC.md#L386)).
Resolve a bounded start and end once, persist them and reject unavailable cuts on retry.
A continuous deployment handle is not an aggregate result or a completed data channel.
A bounded successor consuming aggregates requires a separate verified completion boundary.

**Gate:** fixtures distinguish accepted, running, stopped, failed and finite-result completion.
Source growth cannot extend a sealed bounded run. A continuous restart reproduces the declared duplicate behavior.
Neither a network acknowledgement nor a process launch can satisfy the bounded publication gate.

### Fence ownership before claiming failover

Select static partition ownership for the first multi-node slice.
Do not claim automatic reassignment or failover until stale-output enforcement is qualified.
The baseline local busy check
([refusal](https://github.com/kamir/OpenTSx/blob/dc5f05043158a7ddf80ac156f9690ca92cf93788/docs/datamodel-2026/20-WAYANG-TS-OPERATORS-SPEC.md#L447))
does not fence another node, and its readers use manual assignment
([reading](https://github.com/kamir/OpenTSx/blob/dc5f05043158a7ddf80ac156f9690ca92cf93788/docs/datamodel-2026/20-WAYANG-TS-OPERATORS-SPEC.md#L487)).

Use a durable coordinator for prepare, activate and retire decisions.
Preparation checks capabilities, channels, ownership, state compatibility and resource admission without producing output.
Activation binds the complete admitted generation; retirement stops output and records the final checkpoint boundary.
Partial activation needs an explicit failed or degraded outcome, never an implied atomic start across nodes.
Keep old and replacement owners mutually exclusive at the output boundary.

Offline autonomy requires a bounded grant and a declared expiry rule.
Do not reassign while an offline owner still has authority to emit.
Require explicit revocation acknowledgement or an expiry proof that includes the accepted clock assumptions.
A coordinator lease expiry alone is insufficient to prove a disconnected producer has stopped.

**Gate:** barrier-controlled tests cover coordinator restart, partial prepare, partial activation, lost acknowledgements,
offline owners and cancellation. Reassignment remains disabled until a stale node cannot publish accepted output.
Qualify actual clock and storage assumptions separately from deterministic unit fixtures.

### Repair delivery and checkpoint versioning

Use a durable per-site delivery sequence independent of per-job plan versions.
The baseline makes `planVersion` per job
([version](https://github.com/kamir/OpenTSx/blob/dc5f05043158a7ddf80ac156f9690ca92cf93788/docs/datamodel-2026/20-WAYANG-TS-OPERATORS-SPEC.md#L180))
but uses it as the site's polling cursor
([polling](https://github.com/kamir/OpenTSx/blob/dc5f05043158a7ddf80ac156f9690ca92cf93788/docs/datamodel-2026/20-WAYANG-TS-OPERATORS-SPEC.md#L414)).
Define idempotent delivery, acceptance and reactivation of an already persisted active generation separately from rollback.

Reject incompatible state ABI, semantic contract or checkpoint identity by default.
The baseline instruction to restart from plan start after a mismatch
([checkpoint rule](https://github.com/kamir/OpenTSx/blob/dc5f05043158a7ddf80ac156f9690ca92cf93788/docs/datamodel-2026/20-WAYANG-TS-OPERATORS-SPEC.md#L480))
does not establish safe recovery. Require an authorized new generation for reset, or a qualified migration.
Keep private runtime checkpoint formats private unless a cross-runtime migration contract is implemented.

**Gate:** delivery of job A version 10 cannot hide job B version 1.
Retry, reconnect and duplicate delivery select the same active assignment.
Checkpoint mismatch emits no output; reset and migration fixtures name the permitted state transition explicitly.

### Freeze identities without changing scientific meaning

Keep the legacy raw-text `specHash` distinct from canonical scientific and physical deployment identities.
The baseline hashes text before parsing
([hashing](https://github.com/kamir/OpenTSx/blob/dc5f05043158a7ddf80ac156f9690ca92cf93788/docs/datamodel-2026/20-WAYANG-TS-OPERATORS-SPEC.md#L140))
but sends a JSON object and a stage-text hash
([fields](https://github.com/kamir/OpenTSx/blob/dc5f05043158a7ddf80ac156f9690ca92cf93788/docs/datamodel-2026/schemas/opentsx.runner-plan.v1.schema.json#L17)).
Carry and verify exact original text or bytes when validating that legacy hash.
Separately bind each fragment to exact admitted fragment bytes.
Do not claim raw-byte hashing also defines canonical semantic equivalence.

Specify canonical parameters, defaults, ordered inputs, operator versions, initial state and source scope.
Exclude node placement, delivery sequence and retry identity from scientific equivalence.
Keep exact signed-envelope verification separate from those semantic rules.
Represent offsets and other required int64 values without JSON number rounding; decimal strings provide one explicit boundary.

**Gate:** shared vectors cover original-byte changes, altered fragments, object-key order, signed zero, NaN policy,
changed ordered inputs and integers above `2^53` through the supported maximum.
Placement changes preserve the scientific identity while changing the admitted deployment identity.
Unknown identity or canonicalization versions reject rather than fall back.

### Make UI admission authoritative without changing unrelated routes

The UI currently warns about unavailable runtime operators
([validation](../../../opentsx-saas-frontend/src/workbench/flow.ts#L662)).
Its exporter writes the DAG from the selected flow
([export](../../../opentsx-saas-frontend/src/workbench/flow.ts#L685)).
Introduce server-authoritative deployment admission against pinned catalog and capability contracts.
Normalize aliases and parameters before acceptance; the specification identifies the `stats.window` mismatch
([alias](https://github.com/kamir/OpenTSx/blob/dc5f05043158a7ddf80ac156f9690ca92cf93788/docs/datamodel-2026/20-WAYANG-TS-OPERATORS-SPEC.md#L123)).
Return stable error codes with source node or field locations, while retaining required compatibility messages.
Keep deployment submission explicit; do not redirect existing live or batch execution routes implicitly.

**Gate:** the same valid and invalid fixtures are consumed by UI, Java admission and Rust validation.
Unsupported capabilities, port mismatches, missing ownership and policy conflicts block activation.
Exporting a draft remains distinct from obtaining deployment admission.

### Qualify aggregation and numerical parity

Validate mergeability at the actual cross-site boundary, not on every local ancestor.
The blanket upstream rule
([federation validation](https://github.com/kamir/OpenTSx/blob/dc5f05043158a7ddf80ac156f9690ca92cf93788/docs/datamodel-2026/20-WAYANG-TS-OPERATORS-SPEC.md#L157))
needs reconciliation with local cleaning operations marked `n/a`
([catalog](https://github.com/kamir/OpenTSx/blob/dc5f05043158a7ddf80ac156f9690ca92cf93788/docs/datamodel-2026/20-WAYANG-TS-OPERATORS-SPEC.md#L106)).
Accept local cleaning followed by mergeable statistics where ownership permits it.
Reject unsupported cross-site raw pairing or vector alignment.

Require closed-window or bounded-cut evidence before claiming finality.
One partial from each site alone is the baseline finality condition
([completeness](https://github.com/kamir/OpenTSx/blob/dc5f05043158a7ddf80ac156f9690ca92cf93788/docs/datamodel-2026/20-WAYANG-TS-OPERATORS-SPEC.md#L361)).
Define membership per generation, stale-partial rejection and a conflict for different payloads under the same revision.
Require each mandatory conformance check exactly once; the schema's array length alone does not enforce that
([checks](https://github.com/kamir/OpenTSx/blob/dc5f05043158a7ddf80ac156f9690ca92cf93788/docs/datamodel-2026/schemas/opentsx.site-conformance.v1.schema.json#L24)).

Qualify DDSketch accuracy around the zero bucket and collapsed tails.
`QuantileSketch` maps magnitudes below `MIN_INDEXABLE` to zero and collapses bins after its limit
([zero bucket](../../../opentsx-stats/src/main/java/org/opentsx/stats/QuantileSketch.java#L90),
[collapse](../../../opentsx-stats/src/main/java/org/opentsx/stats/QuantileSketch.java#L228)).
The specification's unrestricted relative-error assertion needs that qualification
([accuracy](https://github.com/kamir/OpenTSx/blob/dc5f05043158a7ddf80ac156f9690ca92cf93788/docs/datamodel-2026/20-WAYANG-TS-OPERATORS-SPEC.md#L334)).

**Gate:** shared vectors cover chunk boundaries, replay, empty windows, late partials, conflicting duplicates,
generation changes, bin boundaries, sub-threshold values and bin collapse.
Require exact wire bytes, IDs, counts and gap placement where specified; state explicit numerical tolerances elsewhere.
Run those vectors in Java and Rust before admitting the corresponding capability.

## Apply the conditional recommendation

Proceed with shared schemas and cross-language fixtures, then a nonproduction UI-to-Wayang-to-two-Rust-node slice.
Use static assignments and the smallest supported operator subset for that slice.
Contract validators establish deterministic admission rules; they do not establish authentication, network dispatch,
durable coordination, broker ownership enforcement or production deployment.
Do not wait for the entire Java operator catalog before proving the Rust deployment boundary.
Expand capabilities only with the applicable semantic, recovery and operating evidence above.
Production approval remains conditional on implemented controls, executed integration gates and an explicit owner decision.

## Assess the implemented contract foundation

The source review also covers the new offline assignment validators and their shared synthetic fixture generator.
The Java implementation checks exact source and fragment hashes, canonical decimal bounds, input shape and
duplicate ownership within a deployment context
([admission](../../../opentsx-model/src/main/java/org/opentsx/model/contracts/ExecutionAssignments.java#L106),
[set checks](../../../opentsx-model/src/main/java/org/opentsx/model/contracts/ExecutionAssignments.java#L55)).
The Rust implementation exposes the same individual and set boundaries
([admission](../../../rust/tsx-contracts/src/lib.rs#L83),
[set checks](../../../rust/tsx-contracts/src/lib.rs#L38)).
The generator names valid and invalid assignment, set and exact-text hashing cases
([fixtures](../../../tools/execution_contract_vectors.py#L61)).

Accept the corrected foundation conditionally within that offline scope, subject to the shared regression gates.
The JSON Schema is a structural filter; runtime checks additionally enforce UTF-8 byte limits, numeric maxima,
content hashes, parsed fragment shape and set consistency
([schema](../../datamodel-2026/schemas/opentsx.execution-assignment.v1.schema.json#L23)).
Passing the schema alone does not establish assignment admission.
The fragment check admits shape without validating operators or the relation to the original logical DAG;
the Java test makes that limitation explicit
([scope](../../../opentsx-model/src/test/java/org/opentsx/model/contracts/ExecutionAssignmentsTest.java#L104)).
No sealed Iceberg adapter, authenticated deployment, complete physical-plan coverage or distributed fencing is
established by these functions. Generation and fence fields undergo value validation only.
This additional assessment is based on source inspection, without a separate execution of the test suites.

## Retain the parser correction as a regression gate

The resolved parser finding concerns reserved-key coercion and consistent treatment of JSON numbers.
An object whose key is `$serde_json::private::Number` must remain an ordinary object.
The Rust dependency uses `serde_json` without its `arbitrary_precision` feature
([dependency](../../../rust/tsx-contracts/Cargo.toml#L9)).
Java recursively rejects parsed numeric values that are nonfinite in binary64
([scalar check](../../../opentsx-model/src/main/java/org/opentsx/model/contracts/ExecutionAssignments.java#L221)).
Original source text remains opaque and is hashed without numeric interpretation.

The shared generator contains explicit ordinary-key, preserved-object, outer-number, fragment-number and
opaque-source cases
([parser fixtures](../../../tools/execution_contract_vectors.py#L136)).
Keep those cases in both language gates. Do not accept a dependency-feature change that converts user JSON
objects into numbers or makes the refusal phase depend on the chosen runtime.
This correction supports the narrow offline acceptance recommendation; it does not satisfy network deployment,
operator execution, state migration or production ownership gates.
