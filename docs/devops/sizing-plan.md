# Sizing plan

What each component needs, how much data the scenarios produce, and what still has to be measured. Numbers marked
**measured** come from this repository (`StorageCostTest`, or runs of the commands on a JDK 21 x86 machine); numbers
marked **assumption** are estimates to be replaced by measurements on your hardware.

## 1. Components (requests / limits as shipped)

| Component | CPU request / limit | Memory request / limit | Storage | Basis |
|-----------|--------------------|------------------------|---------|-------|
| `opentsx-stats` | 250m / 1 | 512Mi / 1Gi | PVC `opentsx-stats-state` 1Gi (checkpoint) | **assumption** for ≤ 1,000 series; state grows with series × open windows (see §4) |
| `opentsx-retrieval`, demo store | 100m / 1 | 384Mi / 1Gi | — | demo store: 3 series × 21,600 values in memory |
| `opentsx-retrieval`, Iceberg | 250m / 2 | 768Mi / 2Gi | — | **assumption**; pattern search scans the selected episodes in memory |
| `opentsx-studio` (nginx) | 10m / 200m | 16Mi / 64Mi | — | static page, 1.2 MB |
| Job `windpark-sim` (Ostsee demo) | 500m / 2 | 768Mi / 1.5Gi | PVC `opentsx-data` 2Gi (index, ground truth) | **measured** peak RSS 460–560 MB, ~10 s wall, ~18 s CPU (2.16 M values, Iceberg sink) |
| Job `twin` | 250m / 1 | 256Mi / 512Mi | — | **measured** peak RSS ~120 MB, 1.4 s |
| Job `house` | 250m / 1 | 512Mi / 1Gi | — | **measured**: `--calibrate` needs > 384 MB heap; RSS ~670 MB at 512 MB heap; 9–19 s |
| Job `boat` | 250m / 1 | 384Mi / 768Mi | — | **measured** peak RSS 300–410 MB, 2 s |
| Job `studio-export` | 250m / 1 | 512Mi / 1Gi | — | **measured** peak RSS 400–670 MB, 11 s (all three scenarios) |
| Job `metadata` | 250m / 1 | 256Mi / 512Mi | — | **measured** peak RSS ~100 MB, 1 s |
| Job `kafscale-check` | 250m / 1 | 256Mi / 768Mi | — | Kafka client only |
| `opentsx-iceberg-rest` (lake) | 100m / 1 | 384Mi / 768Mi | PVC 1Gi (SQLite catalog) | **assumption** |
| `opentsx-minio` (lake) | 100m / 1 | 256Mi / 1Gi | PVC 20Gi | size from §3 |
| Job `dwd` | 100m / 1 | 192Mi / 512Mi | PVC `opentsx-data` (a few KB) | **measured** peak RSS ~80 MB, ~1 s (fixture; house twin over 5 h) |
| CronJob `opentsx-dwd-forward` | 100m / 1 | 192Mi / 512Mi | ~25 KB CSV per station and run (live MOSMIX_L, 247 steps) → ~9 MB per station and year, pruned after 30 days | **measured** CSV size; RSS as `dwd` (**assumption** for 247 steps) |
| `opentsx-modbus-sim` | 50m / 500m | 128Mi / 384Mi | — | **measured** peak RSS ~80 MB |
| Job `modbus-poll` | 100m / 500m | 192Mi / 384Mi | ~71 KB JSON per 30 s run (14 points, 10 s episodes) | **measured** peak RSS ~90 MB; a continuous poller at 5 s with 1-min episodes: ~14 points × 1,440 episodes/day ≈ 20,000 lines/day, roughly 25 MB/day as JSON (**assumption**: JSON is ~10× the Avro/zstd size on KafScale) |
| CronJob `opentsx-synthetic-check` | 100m / 500m | 256Mi / 512Mi | — | Kafka client only, ~10 s per run (**assumption**) |

The JVMs use `-XX:MaxRAMPercentage=75`, so the heap follows the container limit. The measured RSS values grow with
the heap the JVM is allowed; they are upper bounds at the given heap, not minimums.

**Images:** `opentsx/tools` ≈ JRE (~200 MB) + 74 jars (123 MB; 185 MB with `--with-s3`); `opentsx/studio` ≈ 50 MB.
Plan ~1 GB of containerd storage per tag on each node.

## 2. Per scenario

| Scenario | Node | Runs | Memory budget for OpenTSx | Notes |
|----------|------|------|---------------------------|-------|
| **Lab / laptop**, all demos | 1 node, 4 vCPU, 8 GB RAM, 20 GB disk | all Deployments + Jobs one at a time (+ lake) | ~2 GB steady (stats 1 GB, retrieval 1 GB, studio), +1.5 GB peak per Job, +1.5 GB lake | k3s itself ~0.5–1 GB |
| **House** (small box, Pi-class) | Raspberry Pi 5 8 GB or a mini PC, 1 node | stats, studio, the `house` Job periodically (e.g. monthly after new readings) | ~1.5 GB | the house twin is batch (one year in ~0.5 s per run); data volume is negligible (§3.2). On a 4 GB Pi skip the retrieval server or run it with the demo store only |
| **Boat** (Raspberry Pi edge, intermittent uplink) | Raspberry Pi (arm64), 1 node | today: the `boat` Job on uploaded CSV data; no permanent broker connection needed | ~0.5–1 GB | the Java stats processor needs a reachable broker. The edge pattern (local checkpoint file, upload when Starlink/harbour Wi-Fi is up) is the planned Go processor ([today vs. planned](today-vs-planned.md)) |
| **Wind farm** | 3+ nodes or a sized single node; KafScale separate | stats (one instance per domain), retrieval on Iceberg, simulator/backfill Jobs | from §1, stats sized by series count | storage from §3.1; KafScale and S3 sized separately |

## 3. Data volume

### 3.1 Wind farm: formula

```text
values/day   V = N_turbines × C_channels × f_Hz × 86,400
KafScale     B = V × b                      b = 2.18 B/value   (measured: sensor resolution, regular 1 Hz, zstd)
per year       = B × 365 × retention factor
episodes/s     = N × C / episode_seconds   (10-minute episodes: N × C / 600)
```

The 2.18 B/value holds for values quantized to sensor resolution (the wind-farm normalizer does that). Full-precision
doubles cost 6.96 B/value, timestamp jitter (`IRREGULAR_DELTA`) 4.07 B/value (all **measured**,
[`opentsx-kafka-v2`](../../opentsx-kafka-v2/README.md#storage-cost-measured-storagecosttest)).

### 3.1.1 Worked examples

| Case | N × C × f | values/day | KafScale per day (2.18 B) | per year | raw (8.40 B/value) per day | episodes/s |
|------|-----------|-----------|---------------------------|----------|----------------------------|-----------|
| Ostsee demo run (6 h) | 10 × 10 × 1 Hz | 2.16 M per run | 4.7 MB per run | — | 18 MB per run | 3,600 episodes per run |
| small park | 10 × 10 × 1 Hz | 8.64 M | **18.8 MB** | **6.9 GB** | 73 MB | 0.17 |
| large park | 50 × 100 × 1 Hz | 432 M | **942 MB** | **344 GB** | 3.6 GB | 8.3 |
| large park, full precision | 50 × 100 × 1 Hz | 432 M | 3.0 GB (6.96 B) | 1.1 TB | 3.6 GB | 8.3 |

Large park in detail: 50 × 100 × 1 Hz = 5,000 values/s → 432,000,000 values/day × 2.18 B = 941.8 MB/day ≈ 0.94 GB/day
≈ 344 GB/year. Ingest ≈ 10.9 KB/s compressed. Each 10-minute episode carries 600 values ≈ 1.3 KB compressed, and
5,000 series / 600 s ≈ 8.3 episode records/s. Both are small loads for a broker; the volume is the cost driver.

**On top of the episode log (to measure on your data):**

| Item | Estimate | Status |
|------|----------|--------|
| Iceberg copy (Parquet) of the same episodes | 1.5 B/value: 3.25 MB of Parquet for the 2.16 M demo values (3.34 MB incl. metadata) | **measured once** locally (JDBC catalog, file warehouse); repeat on S3 |
| `series-stats` topic | windows PT10M + PT1H → 168 closed windows per series per day + a GLOBAL record per series and checkpoint (every 30 s, at-least-once) | record size **to measure** (count with `kafscale-check`, bytes in S3) |
| `buckets` topic | 2 manifests per bucket | negligible |
| episode index (`FileEpisodeIndex`) | one line per episode (~100 B) → large park: ~72 MB/day | **assumption**; persist it (PVC) or use `IcebergEpisodeIndex` |
| retention | topics are created with `cleanup.policy=delete` and **no explicit retention** | set retention on the KafScale side to your plan; check KafScale's default |

### 3.2 House

| Source | Example | values/day |
|--------|---------|-----------|
| electricity meter | 1/min | 1,440 |
| 8 smart plugs | 1/min each | 11,520 |
| oil tank level, temperatures (indoor, outdoor, boiler) | 1/10 min × 4 | 576 |
| **total** | | ≈ 13,500 → **~30 KB/day, ~11 MB/year** at 2.18 B/value (**assumption** on channels and rates) |

Data volume is irrelevant; the box is sized by the JVMs (§1). The twin itself runs on a year at `PT1H` (8,760
supersteps) in about 0.5 s per run.

### 3.3 Boat (edge)

| Source | Example | values/day |
|--------|---------|-----------|
| power station (SoC, in/out power, voltage) | 4 channels at 0.1 Hz | 34,560 |
| USB-C meters, Starlink, solar | 6 channels at 0.1 Hz | 51,840 |
| **total** | 10 channels at 0.1 Hz | 86,400 → **~190 KB/day, ~2.6 MB per two-week trip** (**assumption**) |

The uplink budget is dominated by Starlink/Wi-Fi availability, not by volume: a two-week trip fits in a few MB.
Buffering on the Pi (SD card or SSD) is trivial; plan for SD card wear (write episodes, not single values).

## 4. KafScale side

What the repository supports and what it does not tell you:

| Item | OpenTSx default / behaviour | What to decide or measure |
|------|-----------------------------|---------------------------|
| partitions | data topics `OPENTSX_PARTITIONS` (default 6), `buckets` and `series` 1 (`TopicLayout.provision`, replication factor 1) | episodes are keyed by `seriesId`; more partitions parallelize the planned Go processor (one lease per partition) but not the single Java stats processor |
| producer | zstd, batch 512 KB, linger 50 ms, `max.in.flight=1`, no idempotence, `acks=all` (`KafScaleClients`) | throughput against S3-backed KafScale: **open** (the conformance CI uses in-memory S3) |
| consumer | manual assignment, no groups, fetch up to 8 MB/partition, 32 MB/request | — |
| segment size, flush interval, S3 part size | KafScale broker settings, not set by OpenTSx | **measure**: latency of the planned segment-reading Go processor depends on how fast segments close |
| retention | not set by OpenTSx | decide per topic (episodes = system of record?) |
| S3 storage | ≈ §3.1 for episodes + stats + manifests | **measure** the real bytes in the bucket after a known run |

### 4.1 Statistics processor

* One instance per domain; it assigns **all** partitions of `tsx.<domain>.episodes.v2` (and `observations` with
  `--observations true`).
* State per series: Welford moments + one DDSketch per open window (α = 0.01) + GLOBAL. **Assumption:** a few KB per
  series and window → 5,000 series × 3 windows × ~4 KB ≈ 60 MB of heap. Measure the checkpoint size
  (`50-status.sh` shows `/state/stats.ckpt`) after the first hours and size the memory limit at ~3× the checkpoint
  plus 300 MB JVM baseline.
* The checkpoint PVC holds one file (positions + state), written atomically; 1 Gi is ample up to the size above.

## 5. Measure on your installation

`deploy/k3s/60-probe.sh` runs all of the steps below except the byte counts and keeps the logs; the
[operations concept](operations.md#capacity-review) describes the regular capacity review.

1. `40-demo.sh windpark-sim` and note `bucket … sealed: 3600 episodes`.
2. Bytes in the KafScale bucket before and after (S3 console, `mc du`) → B/value on your KafScale.
3. `40-demo.sh stats` → record counts of `series-stats`; the checkpoint size.
4. `kubectl top pod -n opentsx` (metrics-server is part of k3s) during the Jobs → adjust requests/limits.
5. Repeat with your real channel catalog; replace the **assumption** rows above.
