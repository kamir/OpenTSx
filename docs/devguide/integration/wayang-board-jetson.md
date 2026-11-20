# Prepare the B4.2 run on a Jetson Nano

TASK-020 B4.2 compares Track B (the Rust runner) with Track A (Wayang on the JVM)
on one physical ARM64 board. Decision D-T14 in
[TASK-020](../../../EVOLUTION/TASK-020-wayang-ts-operators.md#decisions-owner)
makes the matrix ARM64-only, and the Jetson Nano 4 GB is that board. This guide
prepares the board and the evidence files. It does not contain measurement
results; B4.2 stays open until a complete evidence manifest passes the validator
and the owner has reviewed it.

## Know what one complete run is

[Concept 20, section 14.2](../../datamodel-2026/20-WAYANG-TS-OPERATORS.md#142-compare-track-b-and-track-a-on-small-hardware)
defines the protocol, and
[`wayang_board_evaluation.py`](../../../tools/wayang_board_evaluation.py) fixes it:

| Item | Value |
|---|---|
| Conditions | 81: chains pass-through, clean and statistics; runtimes Java 17, Java 21 and Rust; 100, 1,000 and 10,000 series; three repetitions each (D-T15) |
| Input | a paced live feed of one sample per series and second from the KafScale host; every chain and runtime of one size reads the same recorded feed |
| One condition | 5 minutes of warm-up and 30 minutes of measurement (`durationSeconds` at least 2,100) |
| Samples | RSS, CPU time and lag once per second, covering every whole second |
| Machine time | 81 × 35 minutes, about 47 hours, plus setup between conditions |
| Output check | both tracks must produce identical output records before any number counts |

Two parts of a run still have to be written before the 81 conditions can be
measured. Without them the validator refuses the evidence:

1. A workload driver that starts Track A (on JDK 17 or 21, with an explicit
   heap) or Track B against the paced feed and reports the process id, the time
   to the first fetch and the time to the first output. Track B at 10,000 series
   needs a worker started with `serve --profile measurement`; see the
   [local runner guide](rust-runner-local.md#raise-the-ceilings-for-a-board-measurement).
2. The output proof: a Java test under `src/test/java/` that compares the
   condition's output records and writes JUnit XML with the properties
   `opentsx.sourceCommit`, `opentsx.conditionId`, `opentsx.artifactSha256` and
   `opentsx.inputSha256`.

The board identity and the one-second samples are already covered by the tools
below.

## Prepare the board

1. Install JetPack 4.6.x, the last release for the Jetson Nano: Ubuntu 18.04,
   Linux 4.9, Python 3.6. Record the release with `cat /etc/nv_tegra_release`.
2. Power the developer kit from a 5 V 4 A barrel-jack supply with jumper J48 set.
   Micro-USB power limits the board to the 5 W mode.
3. Select the 10 W mode and fixed clocks, then set the `performance` governor on
   every CPU. The validator refuses any other governor.

   ```bash
   sudo nvpmodel -m 0
   sudo jetson_clocks
   for governor in /sys/devices/system/cpu/cpu*/cpufreq/scaling_governor; do
     echo performance | sudo tee "$governor"
   done
   ```

4. Mount a fan. The Nano throttles under sustained load, and a throttled run
   measures the heat sink, not the runtime. The identity file records the
   temperatures before the run.
5. Keep memory for the workload. The 4 GB are shared with the GPU. Boot without
   the desktop (`sudo systemctl set-default multi-user.target`) and record
   whether zram swap is enabled.
6. Run KafScale on a separate host and connect the Nano by wired Ethernet. A
   broker on the Nano would compete with the measured process for its 4 GB.
   Record the connection as the board's `network`.
7. Put working data on a USB 3 SSD instead of the microSD card, and record the
   storage.
8. Install the runtimes:
   - Eclipse Temurin JDK 17 and 21 for `aarch64` as tarballs. Each run records
     its exact `runtimeVersion`.
   - The Rust runner from the CI artifact `rust-static-aarch64-unknown-linux-musl`.
     It is statically linked, so the old C library of Ubuntu 18.04 does not
     matter. Check its SHA-256 against the artifact report. The validator
     requires the Rust compiler version pinned in
     `rust/tsx-runner/rust-toolchain.toml`.

## Record the board identity

`board_identity.py` reads the kernel, CPU, memory, CPU governors, device model,
OS release, power mode and temperatures from the board. Storage and network
cannot be detected reliably, so you name them:

```bash
python3 tools/board_identity.py \
  --storage "USB 3 SSD, ext4" \
  --network "1 GbE to the KafScale host" \
  --output evidence/board-identity.json
```

The command exits with an error and lists the problem when the board is not
ready, for example when one CPU does not run the `performance` governor or the
machine is not `aarch64`. The `board` object in the file is the `boards` entry
of the evidence manifest; set its `identityEvidence` to this file and its
SHA-256.

## Sample one condition

Start the workload of the condition, then sample its process for the whole run:

```bash
python3 tools/board_resource_sampler.py \
  --pid "$WORKLOAD_PID" \
  --duration 2100 \
  --output evidence/arm64-s1000-rust-r1/resources.csv \
  --lag-command ./lag-of-run.sh
```

The CSV has exactly the columns the validator accepts: `elapsed_seconds`,
`rss_bytes`, `cpu_seconds` and `lag_records`. `rss_bytes` is `VmRSS` and
`cpu_seconds` is user plus system time, both from `/proc`. The lag command must
print one non-negative integer within 0.5 seconds; without it, lag is recorded
as 0. The sampler stops with an error instead of writing incomplete evidence
when a second is missed, the process ends early or the lag command fails. It
never overwrites an existing file.

## Plan and validate

Create the condition list once, on a workstation with Python 3.11 or later. The
validator reads the Rust toolchain file with `tomllib`, so it does not run on
the Nano's Python 3.6:

```bash
python3 tools/wayang_board_evaluation.py plan --output evidence/plan.json
```

When every condition has its artifact, input, resource CSV and output proof,
check the manifest against a clean checkout of the measured source:

```bash
python3 tools/wayang_board_evaluation.py validate \
  --input evidence/manifest.json --source-root .
```

A passing validation confirms that the evidence is complete and belongs
together. It does not attest the hardware and does not close B4.2; the owner
reviews the report.

## Keep ARMv7 out of B4.2

Under D-T14 the validator accepts exactly one `aarch64` board and refuses
`armv7l`, so a 32-bit userland on an ARMv8 board does not count. A 32-bit run on
a pi-top or a Raspberry Pi 3 or 4 with a 32-bit OS is optional. Report it
separately as AArch32 compatibility mode, never as B1.2 or B4.2 evidence.
