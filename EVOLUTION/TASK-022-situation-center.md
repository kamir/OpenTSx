# TASK-022: Situation center, hub and registry for in-orbit nodes

## Task Metadata
- **Task ID**: TASK-022-situation-center
- **Created**: 2026-11-20
- **Status**: In progress (L0 done)
- **Priority**: Medium
- **Epic**: OpenTSx in orbit on LASCARIS for Space ([`lascaris-space/`](../lascaris-space/README.md))
- **Builds on**: [TASK-021](TASK-021-orbit-testbed.md) (orbit testbed), D-T13 in [TASK-020](TASK-020-wayang-ts-operators.md)

## Objective

Several in-orbit nodes, the Jetson Nano of TASK-021 first and simulated containers next, each carry the same sidecar:
a local KafScale log (`kafscale-cluster`, one process with embedded etcd, `tools/kafscale_proxy_cluster.go:84-139`)
and `tsx-runner agent`, the endpoint that signed Wayang plans reach (`rust/tsx-runner/src/main.rs:7`). Wayang itself
plans, places and signs on the ground. In phase 1 the ground station is the central hub and discovery point for all
nodes. A situation center on Earth shows the state of every registered component. A decentralized registry and
several cooperating situation centers follow later, each center acting as a set of Wayang sites under its own key.

## Requirements

### What exists and what is missing

| Building block | In the repository | Gap |
|---|---|---|
| Admission | per-job conformance handshake: challenge, signed report with 10 checks, stored admission with expiry (`opentsx-wayang/src/main/java/org/opentsx/wayang/site/ConformanceHandshake.java:15-17`, `:38-144`; `opentsx-wayang/src/main/java/org/opentsx/wayang/site/TsxPlanService.java:154-181`) | nodes are not registered as nodes; only jobs are admitted |
| Hub | `TsxPlanService` knows sites from key and certificate pins set at construction (`TsxPlanService.java:31-33`, `:48-74`); plans are pulled (`TsxPlanService.java:354-356`) | runs only inside tests; no own process, no read API |
| Status | `opentsx.dag-status.v1` per running job (`rust/tsx-runner/src/agent.rs:1107-1109`), every 15 s (`agent.rs:17`) | no status for idle nodes; plan polls are not recorded (`TsxPlanService.java:183-201`) |
| Display | `GET /v1/dags` folds DAG control and status (`opentsx-retrieval/src/main/java/org/opentsx/retrieval/server/KafScaleFeed.java:335-361`) | no page for nodes, admissions, plan delivery, federation or a map; job-keyed Track A and B status is not shown |
| Keys | a site trusts exactly one driver key (`rust/tsx-runner/src/site_plan.rs:151`) | several centers need one key per center |
| Wayang costs | disk and network load converters of the site platform are 0 (`opentsx-wayang/src/main/java/org/opentsx/wayang/TsxSitePlatform.java:37`) | link availability does not influence placement |
| KafScale | topics are append-only, no compaction (`opentsx-kafka-v2/src/main/java/org/opentsx/kafka/TopicLayout.java:18`) | a registry needs a fold over an append-only log |

### Orbit profile first

During a run the agent reports status every 15 s. In the `ground` profile it stops the plan when one report fails
(`rust/tsx-runner/src/agent.rs:1181-1187`). In orbit that would happen at every loss of signal. The site configuration
field `profile` `orbit` (`rust/tsx-runner/src/site_plan.rs:188-193`) makes the agent append the status to a durable
outbox in its state directory and send it, oldest first, at the next acquisition of signal (AOS), before the terminal
status (`agent.rs:101-150`). Ground sites keep the fail-closed behavior. The
[B3 operating guide](../docs/devguide/integration/rust-runner-b3.md#keep-plans-running-through-loss-of-signal)
lists the bounds and messages.

### Component state model

One record per component, one Kafka key per component:

| Field | Content |
|---|---|
| `kind`, `id`, `generation` | `node`, `sidecar`, `link`, `groundStation`, `plan`, `topic` |
| `resource` | service name and version, instance, architecture, device model (OpenTelemetry resource names) |
| `conditions[]` | type, status `True`, `False` or `Unknown`, reason, message, last transition time (Kubernetes convention) |
| `contact` | state, last contact and its station, next AOS and LOS, contact plan version, grace, missed passes |
| `reportedAt`, `receivedAt` | onboard time and ground time, so clock drift is visible |

Per kind:

- **Node**: mode (`nominal`, `safe`, `maintenance`), power (battery voltage, state of charge, budget), position (OMM
  epoch or last onboard fix), onboard time offset, key id and expiry, boot count, last reset reason.
- **Sidecar**: runner version and digest (already in the status document, `agent.rs:1107-1109`), operator catalog
  version, log size, free storage, oldest unsent offset per topic, downlink backlog and passes to drain.
- **Link**: AOS, LOS, maximum elevation, planned and achieved bytes, loss, round-trip time, convergence layer.
- **Ground station**: tracking state, upcoming passes, uplink and downlink queues.
- **Plan**: job, plan version, hash, delivery state (`queued`, `uplinked`, `acknowledged`, `running`, `stopped`,
  `failed`, `refused`), records, watermark, signing key id.
- **Topic**: offset on board and on the ground, lag in records and seconds, time left before the ring overwrites.

Liveness follows the predicted next contact, never a fixed heartbeat period:

| Contact state | Rule | Display |
|---|---|---|
| `inContact` | within AOS and LOS and a report arrived in this pass | green |
| `expectedSilent` | after LOS and before the next AOS plus grace; conditions keep their last value, marked stale | grey, countdown to AOS |
| `overdue` | next AOS plus grace passed without a report; conditions become `Unknown`, reason `MissedPass` | amber |
| `lost` | three missed passes in a row (configurable) | red, alarm |
| `revoked`, `retired` | terminal, from the registry | struck out |

The next AOS has two sources, the ground prediction (OMM plus station mask) and the node's own report. A difference
above a threshold is itself a condition, `ContactPlanDivergent`.

### Phase 1 hub and discovery point

- Registry topic `tsx.<domain>.registry.v2` from `TopicLayout.topic` (`TopicLayout.java:204-205`): one partition,
  unlimited retention, read as "latest record per key" as `dags()` already does (`TopicLayout.java:132-134`).
  Presence topic `tsx.<domain>.presence.v2` for contact reports.
- Node descriptor as JWS EdDSA with the existing `SignedJws` (`opentsx-wayang/src/main/java/org/opentsx/wayang/site/SignedJws.java:12`, `:21`):
  `nodeId`, `kind`, monotonic `seq`, `prevHash`, public key with key id, runner version and digest, catalog version,
  sidecar endpoints, capabilities, contact plan reference, issue and expiry times.
- Admission on first contact over the existing mTLS channel when the certificate is pre-provisioned
  (`TsxPlanService.java:48`); the hub appends a countersigned admission record. Unknown nodes stay a manual decision.
- Lease until the predicted next AOS plus grace, renewed at each contact. Expiry means `overdue`, not deletion.
- Revocation as a hub-signed record; the hub stops delivering plans and the next uplinked plan bundle carries it.
- Not used: Consul, which is BUSL-1.1 ([LICENSE](https://github.com/hashicorp/consul/blob/v2.1.0/LICENSE)) and
  whose TTL checks turn critical when updates stop
  ([health checks](https://developer.hashicorp.com/consul/docs/reference/service/health-check)); etcd leases as the
  registry (expiry deletes a node during a pass gap); the EDC federated catalog crawler, whose standalone repository is
  archived ([repository](https://github.com/eclipse-edc/FederatedCatalog)) and whose successor module in Connector
  v0.18.2 is marked as not stable
  ([module](https://github.com/eclipse-edc/Connector/tree/v0.18.2/core/catalog-crawler/catalog-crawler-core)).

### Situation center on Earth, phase 1

- A "Situation" page in the React Workbench (`opentsx-saas-frontend/package.json`): component table with conditions
  and contact state, contact timeline with AOS and LOS bars per node and station, a 2D map with ground tracks from
  satellite.js on Leaflet ([npm](https://www.npmjs.com/package/leaflet)), station visibility circles and the
  terminator, plan state per node, downlink backlog with passes to drain, and a history view per component.
  react-leaflet is not used: its license is not OSI-approved and version 5 requires React 19
  ([npm](https://www.npmjs.com/package/react-leaflet)).
- A situation service on the NAS reads registry, presence and status topics through the KafScale Proxy, keeps the
  latest state per key, computes contact windows and serves a REST snapshot plus WebSocket deltas.
- Grafana beside it for history and alerts, as a separate service
  ([license](https://github.com/grafana/grafana/blob/v13.3.1/LICENSE)).
- Later: CesiumJS for 3D with Cesium ion switched off ([CesiumGS](https://github.com/CesiumGS/cesium)), Open MCT for
  operator layouts, Yamcs once CFDP and PUS commanding are tested.

### Decentralized registry, later

- Mirror the same signed append-only log from hub to hub by offset cursor and merge by the highest `seq` per node.
  Signatures verify offline, so long disconnections are tolerated.
- W3C Verifiable Credentials 2.0 for trust across organizations ([W3C](https://www.w3.org/TR/vc-data-model-2.0/));
  DID 1.1 is a Candidate Recommendation ([W3C](https://www.w3.org/TR/did-1.1/)).
- Dataspace Protocol catalogs with ODRL policies only between always-online ground centers, for data products.
- DTN Bundle Protocol 7 as transport for descriptors and revocations.
- Not used: libp2p or IPFS DHTs, whose provider records expire after 48 hours
  ([ProbeLab](https://probelab.io/blog/libp2p-kademlia-dht-configuration-parameters/)).

### Several situation centers and federation

- Exactly one center holds command authority over a node at a time, recorded as an `authorityLease` in the registry
  log and signed by the releasing and the receiving center. The handover follows documented practice such as ESOC
  handing MetOp-C to EUMETSAT ([ESA](https://www.esa.int/Enabling_Support/Operations/Handing_over_MetOp-C)): confirm
  health, sign, uplink at the next pass, node acknowledges.
- Every center mirrors the other centers' registry and presence logs read-only.
- In Wayang a center is a set of sites under its own driver key, not a new platform. A center plans only for nodes it
  holds authority over; cross-center work is two plans joined by a mirrored topic.
- Needed in code: several driver keys per site instead of one (`site_plan.rs:151`), the authority lease in plan
  admission, and link availability as network cost in the Wayang cost model (`TsxSitePlatform.java:37`).

## Sub-Tasks

| Phase | Work | Done when |
|---|---|---|
| L0 Orbit profile | runner buffers status in a durable outbox instead of stopping the plan; regression test | a plan keeps running through a simulated loss of signal and its status arrives complete at the next AOS; done, `agent::tests::orbit_plan_keeps_running_through_loss_of_signal_and_every_status_arrives_at_aos` |
| L1 Registry and presence | topics, descriptor schema, admission, lease, revocation; hub as its own process on the NAS | two nodes appear, renew their leases, and a revocation takes effect |
| L2 State model and situation service | component record schema, contact state machine, REST and WebSocket | every contact state including `expectedSilent`, `overdue` and `lost` is reproduced in tests |
| L3 Situation page | table, timeline, map, plans and backlog in the Workbench; Grafana beside it | one simulated day with passes is shown correctly live and in retrospect |
| L4 More nodes | more in-orbit containers with their own sidecar and orbit | N nodes with different passes and no false alarms in pass gaps |
| L5 Second center | mirroring, authority lease, keys per center, handover of one node | handover in a pass, node acknowledgment, and no plan of the old center accepted afterwards |

## Dependencies

- TASK-021 phases P0 to P3 for the nodes, the link and the ground stack.
- L0 is also needed by TASK-021 P3, since passes end in a loss of signal.

## Success Criteria

1. L0 to L5 meet their "done when" column with recorded evidence.
2. No silence between predicted passes is shown as a failure.
3. No private value (address, coordinate, key, orbit record) is committed.

## Timeline

Not scheduled. L0 comes first.

## Notes

- The fail-closed status behavior is correct for ground sites; the orbit profile must not weaken it there.
- Without compaction the registry log grows without bound; its volume is small, but it is measured in L1.
- No standard field for "expected next contact" was found; the `contact` block is a project schema.
