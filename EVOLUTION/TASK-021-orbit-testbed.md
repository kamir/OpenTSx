# TASK-021: Orbit testbed with an onboard node and a ground station

## Task Metadata
- **Task ID**: TASK-021-orbit-testbed
- **Created**: 2026-11-20
- **Status**: Planned
- **Priority**: Medium
- **Epic**: Tier 2 and Tier 3 processing in orbit (D-T13 in [TASK-020](TASK-020-wayang-ts-operators.md))
- **Study**: [`02-WAYANG-FPGA-OPERATORS-FOR-SPACE.md`](../docs/research/02-WAYANG-FPGA-OPERATORS-FOR-SPACE.md)

## Objective

Build a homelab testbed in which an NVIDIA Jetson Nano 4 GB plays a satellite's onboard computer and a Synology NAS
plays the ground station. A satellite simulator writes raw samples into an onboard ring buffer. `tsx-runner` reduces
them under signed plans. Only reduced, sealed records cross an emulated space-ground link during simulated passes
into S3 and KafScale on the ground, where the Workbench and Wayang inspect, plan and sign. The testbed exercises the
Tier 2 runtime and the CPU fallback path that D-T13 requires for every FPGA kernel. The Jetson has no FPGA.

## Requirements

### Nodes

| Node | Role | Hardware and software baseline |
|---|---|---|
| Jetson Nano 4 GB developer kit | onboard computer | L4T R32.7.6 (JetPack 4.6.6, the final release of the line; [NVIDIA](https://forums.developer.nvidia.com/t/announcing-end-of-life-for-nvidia-jetpack-4-with-the-release-of-jetpack-4-6-6/314302)), Ubuntu 18.04, kernel 4.9.337, glibc 2.27; 64 GB A2 or high-endurance microSD; 5 V 4 A barrel supply with jumper J48; fan on J15 |
| Synology NAS, x86 Plus model, at least 8 GB RAM | ground station and link emulator | DSM 7.2 with Container Manager ([release notes](https://www.synology.com/api/releaseNote/findChangeLog?identify=ContainerManager&lang=en-global)); S3 service; NTP server |

The Jetson host stays on stock L4T. The simulator and every tool newer than the host userland run in containers.
The stock L4T kernel has no `netem`, `tbf` or `ifb` ([forum](https://forums.developer.nvidia.com/t/followup-to-tc-enabling-sch-netem/173945)),
so link emulation runs on the NAS, whose DSM 7.2.2 kernel configuration builds `sch_netem` as a module
([Synology GPL source](https://archive.synology.com/download/ToolChain/Synology%20NAS%20GPL%20Source/7.2-72806)).
Whether the module loads on a given NAS is checked in phase P0.

### Onboard ring-buffer content

The simulator follows the ECSS packet utilisation standard: housekeeping reports (ST[3]) and packet stores
(ST[15]) that are circular or bounded and are retrieved openly or by time range
([ECSS-E-ST-70-41C](https://ecss.nl/wp-content/uploads/2016/06/ECSS-E-ST-70-41C15April2016.pdf)).

| Group | Parameters | Rate | Bytes per day |
|---|---|---|---|
| GNSS (orbit) | ECEF position and velocity (float64), time, fix, satellites | 1 Hz | 5.0 MB |
| ADCS estimate (attitude) | quaternion, body rate, mode | 1 Hz | 2.8 MB |
| Star tracker | quaternion and status | 5 Hz | 8.6 MB |
| Gyro | 3 axes | 10 Hz | 10.4 MB |
| Magnetometer | 3 axes | 10 Hz | 10.4 MB |
| Coarse sun sensors | 6 cosine detectors | 1 Hz | 1.0 MB |
| Reaction wheels | 4 speeds | 1 Hz | 1.4 MB |
| Magnetorquers | 3 duty cycles | 1 Hz | 0.5 MB |
| Power (EPS) | bus voltage, battery voltage, current and temperature, state of charge, 6 panel currents, 4 rail currents | 1 Hz | 2.8 MB |
| Thermal | 16 thermistors | 0.1 Hz | 0.3 MB |
| Onboard computer | CPU, memory, EDAC and SEU counters, reboot count, reset cause, uptime, temperature | 0.1 Hz | 0.2 MB |
| Communications | RSSI, frequency offset, frames, CRC errors | 0.1 Hz | 0.1 MB |
| **Housekeeping total** | | | **43.4 MB** |
| Payload camera | frames or pushbroom lines along the ground track | for example 181 lines/s, 11.1 Mbit/s raw | separate bounded store |

Rates follow real CubeSat beacons as decoded by the SatNOGS decoders ([SatNOGS DB](https://db.satnogs.org/about)); byte
counts are chosen for the testbed. The onboard-computer group uses measured values from the Jetson itself: INA3221
power rails, thermal zones, CPU and GPU clocks and the `nvpmodel` power mode
([NVIDIA power management](https://docs.nvidia.com/jetson/archives/l4t-archived/l4t-3275/Tegra%20Linux%20Driver%20Package%20Development%20Guide/power_management_nano.html)).

For a 500 km sun-synchronous orbit and a station at 50 degrees north, skyfield 1.55 and sgp4 2.27 give 4 passes and
24.8 minutes above 10 degrees elevation per day, and eclipses of 34.5 to 35.2 minutes. In that contact time UHF at
9.6 kbit/s carries 1.79 MB and S-band at 1 Mbit/s carries 186 MB. Raw housekeeping alone exceeds the UHF budget, and
the camera exceeds every budget. Onboard reduction is the point of the testbed.

### Simulator

- Orbit from SGP4 on an OMM record pinned in `config/private/`. CelesTrak serves new objects only as OMM and allows
  one download per update ([CelesTrak](https://celestrak.org/NORAD/documentation/gp-data-formats.php)).
- Attitude: nadir pointing in eclipse, sun pointing in sunlight, rate-limited slews, pointing jitter.
- Sensors with bias and noise; magnetic field from IGRF-14 (`ppigrf`); quantisation to ADC bits, so the ring buffer
  sees integer steps.
- Power and thermal: solar input per panel, battery state of charge with eclipse cycling, a lumped thermal model of
  3 to 5 nodes.
- Camera: footprint at the sub-satellite point cropped from NASA GIBS Blue Marble tiles
  ([GIBS](https://nasa-gibs.github.io/gibs-api-docs/access-basics/)). Sentinel-2 cloudless is used only in its 2016
  layer, which is CC BY 4.0; later layers are non-commercial ([EOX](https://cloudless.eox.at/documentation/license)).
- Replay mode for public telemetry: ESA-ADB ([Zenodo](https://zenodo.org/records/12528696)) and OPSSAT-AD
  ([Zenodo](https://zenodo.org/records/15108715)) are downloaded at run time and never committed.

### Space-ground link

- A router container `linkemu` on the NAS has a macvlan leg to the Jetson (uplink) and a bridge leg to the ground
  services (downlink), with egress `netem` on each leg. A pass controller computes passes with skyfield and switches
  the profiles at AOS and LOS: S-band 1 Mbit/s down and 256 kbit/s up, delay from the slant range, jitter, loss and an
  explicit queue limit; 100 percent loss outside passes.
- Compressed time shortens the gaps between passes and keeps passes in real time. Clocks are not faked; onboard time
  is a software CUC counter with injected offset and drift.
- Transport in three stages: file store-and-forward with acknowledgment-gated deletion; CCSDS CFDP class 2 with
  `cfdp-py` ([us-irs](https://github.com/us-irs/cfdp-py)); DTN Bundle Protocol 7 with uD3TN
  ([GitLab](https://gitlab.com/d3tn/ud3tn)).
- The downlink agent seals immutable chunks per topic, partition and offset range with SHA-256 and an Ed25519
  signature. The ground ingester replays them into KafScale and drops records whose (spacecraft, topic, partition,
  offset) it has seen. Producers keep `enable.idempotence=false` (`opentsx-kafka-v2/src/main/java/org/opentsx/kafka/KafScaleClients.java:36-51`).

### Ground station

- Compose projects `ground-link` (`linkemu`, pass controller), `ground-store` (S3, KafScale, landing zone, ingester)
  and `ground-ui` (retrieval server, Workbench, Grafana).
- S3: the MinIO community edition is no longer maintained ([MinIO](https://github.com/minio/minio/blob/master/README.md)).
  The testbed qualifies versitygw ([versity](https://github.com/versity/versitygw)) and Garage with
  `opentsx-kafscale-conformance` and keeps MinIO only as a comparison baseline.
- Station coordinates, orbit records, keys and addresses live in `config/private/`.

## Reused components

| Component | Evidence | Use in the testbed |
|---|---|---|
| `tsx-runner` modes `agent`, `conform`, `run`, `replay`, `serve` | `rust/tsx-runner/src/main.rs:7` | onboard runner; reads Kafka only |
| static `aarch64-unknown-linux-musl` build | `.github/workflows/ci.yml:129-209` | copied to the Jetson, the same board on which B4.2 of TASK-020 is measured (D-T14) |
| Jetson preparation: JetPack 4.6, 10 W mode, `performance` governor, fan, no desktop, broker on another host | `docs/devguide/integration/wayang-board-jetson.md:39-74` | phase P0 follows these steps |
| board identity: kernel, CPU governors, power mode, temperatures | `tools/board_identity.py:60-80` | names the device and its state for every measurement (success criterion 2) |
| one-second RSS, CPU and lag sampler for Python 3.6 | `tools/board_resource_sampler.py:54-79` | resource samples of the runner and the onboard broker in the P2 soak |
| `kafscale-cluster`: one process with embedded etcd, memory S3 broker and Proxy | `tools/kafscale_proxy_cluster.go:84-139` | onboard log in phase P2; ground broker |
| KafScale build | `tools/build_kafscale_proxy.py:15-34` | x86 build for the NAS; arm64 build is new work |
| Episode write path: `TopicLayout`, `Episodes.regular`, `OpenTsxAvro`, `KafScaleClients.producer` | `opentsx-kafka-v2/src/main/java/org/opentsx/kafka/TopicLayout.java:85-106` | simulator output `tsx.sat.episodes.v2` |
| byte-identical Python Episode encoder | `python-package/opentsx/model/serde.py:1-17` | simulator in Python |
| simulator pattern `WindparkSim --kafscale` | `opentsx-windpark/src/main/java/org/opentsx/windpark/WindparkSim.java:66-105` | template for the satellite simulator |
| system documents `opentsx.system.v1` | `systems/h2-chain.system.yaml:18-70` | an `orbit.system.yaml` that describes the satellite signals |
| retrieval server and Workbench | `opentsx-retrieval/src/main/java/org/opentsx/retrieval/server/RetrievalServer.java:535-545` | ground view of downlinked series |

## Sub-Tasks

The order matters.

| Phase | Work | Done when |
|---|---|---|
| P0 Base | Flash the Jetson, upgrade to R32.7.6, prepare it as in the [Jetson runbook](../docs/devguide/integration/wayang-board-jetson.md), Docker with NVIDIA runtime held, chrony against the NAS; on the NAS check `modprobe sch_netem`, create the macvlan network over SSH, enable NTP | `tsx-runner --version` runs on the Jetson; a `netem` qdisc applies on the NAS or on a VMM fallback VM |
| P1 Ground | KafScale, S3, retrieval server and Workbench on the NAS; `WindparkSim --kafscale` as smoke test | the Workbench shows windpark series served from the NAS |
| P2 Satellite | simulator v1 (orbit, attitude, power, thermal, sensors, Jetson housekeeping); `kafscale-cluster` built for arm64 on the Jetson as the ring-buffer stand-in; `tsx-runner` reduces | a 24 hour soak without thermal throttling and with complete reduced topics |
| P3 Link | `linkemu`, pass controller, downlink agent with acknowledgments, ground ingester with duplicate check | one simulated day with 4 passes, injected drops and restarts: zero lost and zero duplicated records on the ground |
| P4 Camera | Blue Marble tile cache on the NAS, frame and pushbroom modes, onboard reduction, separate payload store | frames follow the ground track; only reduced products are downlinked |
| P5 Uplink | deployable plan service and signing tool, contact plan, onboard time model with drift | a new plan is uplinked in one pass and runs in the next cycle |
| P6 D-T13 | shared-memory ring and `ingest.raw-ring`; the first kernel pack (`resample` partials, moment partials) as the Rust CPU fallback | the fallback passes the parent operators' shared vectors; an FPGA development board can then join as Tier 3 |

## Dependencies

- D-T13 in [TASK-020](TASK-020-wayang-ts-operators.md): FPGA kernels behind the Rust runner with a CPU fallback.
- The runner reads Kafka only. A ring-buffer source needs a second reader beside `KafkaBroker`
  (`rust/tsx-runner/src/broker.rs:53-72`), plan admission changes and the D-T13 contract delivery.
- The B3 plan service runs only inside tests today; P5 needs a deployable driver and a signing tool.

## Success Criteria

1. P0 to P6 meet their "done when" column, each with recorded evidence: commands, logs and measured numbers.
2. Every measurement names the device, software versions and run date.
3. No private value (address, coordinate, key, orbit record, downloaded dataset) is committed.

## Timeline

Not scheduled. P0 and P1 come first because the ground side reuses existing components.

## Notes

- The Jetson Nano is a constrained stand-in, not a performance model of the Jetson Orin modules that fly in orbit.
- The Jetson is also the B4.2 board of TASK-020. B4.2 needs KafScale on another host and the board's memory for the
  measured process, so its 27 conditions do not run while the testbed's onboard broker or simulator runs.
- KafScale for arm64 is unbuilt and untested. The fallback onboard is a plain spool file instead of a broker.
- The Synology `netem` module is read from the published kernel configuration and is not yet loaded on a device.
