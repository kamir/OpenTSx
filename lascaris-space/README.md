# lascaris-space

This directory is the home of the OpenTSx components for in-orbit computation on LASCARIS for Space. Verified
against commit `8fcf386`.

It contains no code yet. The components, their order and their acceptance criteria are defined in
[TASK-021](../EVOLUTION/TASK-021-orbit-testbed.md) (orbit testbed) and
[TASK-022](../EVOLUTION/TASK-022-situation-center.md) (situation center, hub and registry).

## Find what belongs here

Space-specific components that have no home in an existing module are built here:

| Component | Role | Defined in |
|---|---|---|
| Satellite simulator | orbit, attitude, power, thermal, sensors and camera into an onboard ring buffer | TASK-021, P2 and P4 |
| Downlink agent | seals immutable chunks per topic, partition and offset range, sends them during passes, deletes after acknowledgment | TASK-021, P3 |
| Ground ingester | replays chunks from S3 into KafScale and drops duplicates | TASK-021, P3 |
| Link emulator and pass controller | shapes and cuts the space-ground link from computed passes | TASK-021, P3 |
| Registry and presence | signed node descriptors, leases and revocations on append-only topics | TASK-022, L1 |
| Situation service | folds registry, presence and status into component state for the situation center | TASK-022, L2 |
| Compose projects | one for the onboard node, one per ground role | TASK-021, P0 and P1 |

These stay in their modules and are extended there:

| Component | Module |
|---|---|
| `tsx-runner` and its sidecar role, including the orbit profile and the ring-buffer source | [`rust/tsx-runner`](../rust/tsx-runner/README.md) |
| Wayang platforms, plan signing and the plan service | [`opentsx-wayang`](../opentsx-wayang/README.md) |
| The Situation page of the Workbench | [`opentsx-saas-frontend`](../opentsx-saas-frontend/) |
| Episode, provenance and topic contracts | [`opentsx-model`](../opentsx-model/), [`opentsx-kafka-v2`](../opentsx-kafka-v2/) |

## Keep to the existing contracts

- Records are Episodes in Avro single-object encoding on topics named by `TopicLayout` (`tsx.<domain>.<kind>.v2`).
- Clients connect to the KafScale Proxy, never to a broker; producers come from `KafScaleClients.producer`.
- Onboard work runs only as signed plans admitted by `tsx-runner`. FPGA kernels follow owner decision D-T13 in
  [TASK-020](../EVOLUTION/TASK-020-wayang-ts-operators.md): behind the runner, data reduction only, with a CPU fallback.
- This directory is not a LASCARIS distribution. LASCARIS connects to OpenTSx through these contracts.

## Keep private values out

Station coordinates, orbit records, keys, addresses and downloaded telemetry datasets go to `config/private/`, which
is git-ignored. Nothing in this directory may contain them.

## Read the background

- [In-orbit operator study](../docs/research/02-WAYANG-FPGA-OPERATORS-FOR-SPACE.md)
- [Earth and orbit data product architecture](../docs/research/03-EARTH-ORBIT-DATA-PRODUCT-ARCHITECTURE.md)
