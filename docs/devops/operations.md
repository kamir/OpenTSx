# Operations concept

> **Kurzfassung (Deutsch).** Dieses Betriebskonzept beschreibt, wie OpenTSx auf k3s gegen ein bestehendes
> KafScale-Cluster betrieben wird – vom **lokalen Probelauf** bis zu einer produktionsnahen Laborumgebung.
> **Lokal verproben:** `deploy/k3s/60-probe.sh --local-kafscale` startet KafScale per Docker Compose (mit der
> LAN-IP als *advertised address*), baut und installiert alles, führt jedes Beispiel aus (Windpark → KafScale,
> Statistik, Retrieval, Studio, Zwillinge, DWD offline, Modbus-Simulator + Poller) und schreibt einen Bericht
> `probe-report-<Zeit>.md` mit PASS/FAIL je Schritt, Dauer und Hinweisen. `--dry-run` zeigt nur den Plan.
> Das Konzept enthält: Dienstkatalog mit Kritikalität, drei Umgebungen, Überwachung (heute `kubectl`-basiert plus
> optionaler synthetischer Check; Vorschlag kube-state-metrics + VictoriaMetrics/Prometheus + Grafana, Alarmregeln
> nur auf Metriken, die es wirklich gibt – **OpenTSx selbst exportiert noch keine Metriken**), Logging, Sicherung und
> Wiederherstellung (`70-backup.sh`, `71-restore.sh`), Update und Rollback, Secrets, Sicherheit, acht Runbooks,
> Rollen, einfache SLOs und die Kapazitätsprüfung. Geprüft wurde hier ohne Docker-Daemon und ohne k3s (siehe
> [Validierung](#what-was-validated-here)); der erste echte Probelauf steht noch aus.

This page is the operating model for OpenTSx on k3s as described in this guide: what runs, how to watch it, how to
back it up, update it and react to incidents. It covers today's Java components (see
[today vs. planned](today-vs-planned.md)); the planned Go processor will add its own metrics and runbooks.

## Try it locally first: the probe

One command installs everything on a local k3s and runs every example:

```bash
deploy/k3s/60-probe.sh --local-kafscale            # KafScale via docker compose on this host, then all steps
deploy/k3s/60-probe.sh --dry-run --local-kafscale  # only the plan and a validation of the configuration
deploy/k3s/60-probe.sh --skip-build                # images already imported; KafScale from deploy/k3s/.env
deploy/k3s/60-probe.sh --skip-build --only modbus,dwd   # repeat single steps
```

| Step | What it does | PASS when |
|------|--------------|-----------|
| `kafscale-local` | (only `--local-kafscale`) `KAFSCALE_ADVERTISED_HOST=<LAN IP> docker compose -f docker-compose.kafscale.yml up -d` | the broker answers on `<LAN IP>:9092` within 90 s |
| `prereqs` | `00-prereqs.sh` (`--no-build` with `--skip-build`) | no FAIL item |
| `build`, `import` | `10-build.sh` (`--with-s3` when `LAKE=true`), `11-import-images.sh` | exit 0 |
| `configure` | `20-configure.sh MODBUS_SIM=true` (+ `KAFSCALE_BOOTSTRAP=<ip>:9092 KAFSCALE_NETWORK=direct`) | renders |
| `deploy` | `30-deploy.sh` | every Deployment rolled out |
| `kafscale-check` | `40-demo.sh kafscale-check` from inside the cluster | bootstrap **and** advertised addresses reachable |
| `windpark-sim` | `40-demo.sh windpark-sim` (restarts the stats processor) | Job complete |
| `series-stats` | `kafscale-check` every 30 s for up to 5 min (`PROBE_STATS_WAIT`) | `tsx.<domain>.series-stats.v2` > 0 records |
| `metadata`, `twin`, `house`, `boat`, `studio-export` | the demo Jobs | Job complete |
| `retrieval` | port-forward, `GET /v1/health`, `GET /v1/episodes?...&limit=1` | `{"status":"ok"}` and at least one episode |
| `studio` | port-forward, `GET /` | HTTP 200 and > 100 KB (the page is ~1.2 MB; less = blank Studio) |
| `dwd` | `40-demo.sh dwd` (fixture in the image) or `dwd-online` (`--online`) | CSV written, house twin bound to the forecast (`origin FORECAST`) |
| `dwd-forward` | one run of the CronJob, only when `DWD_FORWARD=true` | Job complete (else SKIP) |
| `modbus` | simulator `opentsx-modbus-sim` + `40-demo.sh modbus` | Job complete, every window `0 missing` |
| `status` | `50-status.sh` into the log | never fails |

The report (`./probe-report-<UTC time>.md`) lists every step with result, duration and a note; for each failure it
adds hints (from [troubleshooting](troubleshooting.md) and the [runbooks](#runbooks) below) and the last log lines.
All step logs are kept in `./probe-logs-<UTC time>/`. The exit code is 1 if any step failed. A failed `prereqs`,
`build`, `import`, `configure` or `deploy` step stops the cluster steps (they are reported as SKIP) unless
`--keep-going` is given. The probe writes `MODBUS_SIM=true` (and with `--local-kafscale` the KafScale address) into
`deploy/k3s/.env`, like any `20-configure.sh` call.

**The advertised-address pitfall.** A Kafka-protocol client connects to the bootstrap address, then to the address
the broker *advertises*. `docker-compose.kafscale.yml` advertises `KAFSCALE_ADVERTISED_HOST` (default `localhost`),
which is only valid on the docker host itself; inside a pod `localhost` is the pod. `--local-kafscale` therefore
starts the broker with the host's LAN IP (source address of the default route, override with `--kafscale-host`) and
points OpenTSx at `<ip>:9092`. Compose recreates the broker container when the advertised host changes. If the
broker was started by hand with `KAFSCALE_BROKER_ADDR=<host>:<port>`, note that a host in `KAFSCALE_BROKER_ADDR`
takes precedence over `KAFSCALE_BROKER_HOST` (KafScale's `buildBrokerInfo`). A host firewall must allow the k3s pod
CIDR (`10.42.0.0/16`) to reach port 9092. The alternative for a single host is `KAFSCALE_NETWORK=hostnetwork` with
the broker advertising `localhost`.

## Service catalogue

Criticality is for a lab installation: **high** = data is lost or results go stale without anyone noticing;
**medium** = a function is unavailable, nothing is lost; **low** = demo or convenience.

| Service | Kind | Purpose | State | Owner role | Criticality | If it fails |
|---------|------|---------|-------|------------|-------------|-------------|
| KafScale (existing cluster) | external | episode log `tsx.<domain>.*.v2` | KafScale's S3 bucket + etcd | KafScale operator | **high** | nothing is written; producers (Jobs, pollers) fail; stats idle |
| `opentsx-stats` | Deployment, 1 replica, `Recreate` | streaming statistics → `series-stats` | PVC `opentsx-stats-state` (checkpoint) | OpenTSx service owner | **high** | statistics and DQ results go stale; resumes from the checkpoint |
| `opentsx-retrieval` | Deployment + Service :8080 | REST API (episodes, patterns, segments) | none (demo store) or Iceberg | OpenTSx service owner | medium | API and Python client unavailable |
| `opentsx-studio` | Deployment + Service :80 | static Smart Topics Studio page | none (baked into the image) | OpenTSx service owner | low | page unavailable |
| `opentsx-iceberg-rest` (lake) | Deployment + Service :8181 | Iceberg REST catalog | PVC `opentsx-iceberg-catalog` (SQLite) | platform operator | **high** (lake) | lake reads/writes fail; catalog loss = tables lost |
| `opentsx-minio` (lake, in-cluster) | Deployment + Service :9000 | S3 for the warehouse | PVC `opentsx-minio` | platform operator | **high** (lake) | as above |
| `opentsx-dwd-forward` (`DWD_FORWARD=true`) | CronJob, daily | MOSMIX forecast → CSV on `opentsx-data` | PVC `opentsx-data` | data owner | medium | twins run on older forecasts |
| `opentsx-modbus-sim` (`MODBUS_SIM=true`) | Deployment + Service :1502 | simulated meter (example map) | none | OpenTSx service owner | low | demo only |
| `opentsx-modbus-poll` | Job (demo) | Modbus poller → episodes file on `opentsx-data` | PVC `opentsx-data` | data owner | low today; **high** once it feeds real meters | gaps in meter data (no buffering on the device side) |
| demo Jobs (`windpark-sim`, `metadata`, `twin`, `house`, `boat`, `studio-export`, `dwd`, `kafscale-check`) | Jobs, run by hand | examples, checks | `opentsx-data` (some) | OpenTSx service owner | low | rerun |
| `opentsx-synthetic-check` (`MONITORING=true`) | CronJob, every 10 min | KafScale, checkpoint age, disk usage | none | platform operator | medium | blind spot in monitoring |
| OpenMetadata | external | catalog for metadata, profiles, DQ | its own | data owner | low | `metadata-publish` fails |

## Environments

| | Local probe | Lab | Production-like |
|---|---|---|---|
| purpose | try it once, reproduce bugs | shared installation for the project, real data | rehearsal of a real operation |
| hosts | one machine (laptop, mini PC) with k3s and docker | one node (mini PC, Raspberry Pi 5) | 3+ nodes, or one well-sized node; KafScale separate |
| KafScale | `docker-compose.kafscale.yml` on the same host (`--local-kafscale`) | existing cluster in the LAN (`direct`) | managed cluster, own S3 bucket with versioning |
| images | built locally, imported (`IMAGE_METHOD=docker`) | built on a build machine, `ssh` or `registry` | registry, one immutable tag per build (`3.0.1-<git sha>`) |
| DWD | offline fixture (`dwd`) | `DWD_FORWARD=true`, online | online, with a proxy if required |
| Modbus | simulator | converter at the meter (`MODBUS_HOST`, real register map) | poller close to the converter, isolated network |
| lake | off (or `LAKE=true` with in-cluster MinIO) | `LAKE=true`, MinIO in the cluster | external S3, hardened Iceberg catalog (Polaris, Lakekeeper, Nessie) |
| monitoring | the probe report, `50-status.sh` | `MONITORING=true` + `50-status.sh` | kube-state-metrics + VictoriaMetrics/Prometheus + Grafana, alerts to a channel |
| backup | none needed | `70-backup.sh --quiesce` daily (cron on the admin host) | as lab + off-site copy, restore test every quarter |
| who changes | anyone | service owner, announced | change with review (overlay diff), rollback plan |
| data | synthetic only | may include private data (house bills, boat logs) **in Secrets only** | real data, access restricted |

Keep one configuration per environment: one checkout (or one `deploy/k3s/.env`) per environment, and review a change
with `kubectl diff -k deploy/k3s/overlays/local` before `30-deploy.sh`.

## Monitoring and alerting

### What to watch

| Signal | Why | Today, without a metrics stack | As a metric (kube-state-metrics / kubelet) |
|--------|-----|-------------------------------|--------------------------------------------|
| KafScale reachable, **advertised address** reachable from the pods | most common failure; everything depends on it | `40-demo.sh kafscale-check`; `opentsx-synthetic-check` every 10 min | indirectly: `kube_cronjob_status_last_successful_time{cronjob="opentsx-synthetic-check"}` |
| statistics processor alive and progressing | stale statistics are silent | checkpoint age in `50-status.sh` (written every 30 s; > 5 min = stuck), synthetic check | `kube_deployment_status_replicas_available{deployment="opentsx-stats"}`, restarts `kube_pod_container_status_restarts_total{container="stats"}` |
| consumer lag of the processor (end offset − checkpoint position) | backlog after an outage | **not available**: the positions are only in the checkpoint file; compare the `series-stats` record count over time (`kafscale-check`) | **not exported** (backlog) |
| Job and CronJob failures | demo Jobs, DWD fetch, synthetic check | `kubectl -n opentsx get jobs,cronjobs`, `50-status.sh` | `kube_job_status_failed`, `kube_cronjob_status_last_successful_time` |
| pods waiting (CrashLoopBackOff, ImagePullBackOff, CreateContainerConfigError) | broken rollout | `50-status.sh` (warnings) | `kube_pod_container_status_waiting_reason` |
| PVC usage | a full checkpoint or data volume stops the writers | `df` in the synthetic check (alarm > 85 %) | `kubelet_volume_stats_used_bytes / kubelet_volume_stats_capacity_bytes` — **not reported for k3s' `local-path` volumes** (they are host paths); available with a CSI driver such as Longhorn |
| Iceberg REST catalog and MinIO (lake) | lake reads/writes | readiness probes (`/v1/config`, `/minio/health/ready`), `kubectl get deploy` | `kube_deployment_status_replicas_available`; MinIO's own `/minio/v2/metrics/cluster` needs `MINIO_PROMETHEUS_AUTH_TYPE=public` or a token (not configured) |
| retrieval API | user-facing | readiness probe `/v1/health` | `kube_deployment_status_replicas_available{deployment="opentsx-retrieval"}` |
| node resources | sizing | `kubectl top pod -n opentsx` (metrics-server ships with k3s) | `container_memory_working_set_bytes` (cAdvisor via the kubelet) |

### Monitoring stack: two levels

**Level 0 (shipped):** `50-status.sh` (now with CronJobs and checkpoint age) and the optional component
`deploy/k3s/components/monitoring/` (`20-configure.sh MONITORING=true`):

* CronJob `opentsx-synthetic-check` every 10 minutes: `kafscale-check` without provisioning, statistics checkpoint
  younger than 5 minutes, disk usage of the PVC mounts below 85 %. A failed run is a failed Job (`kubectl get jobs`)
  and moves `kube_cronjob_status_last_successful_time`. It mounts the checkpoint and data PVCs read-only (fine on a
  single node with `local-path`; on several nodes the scheduler places it next to the volumes).
* ConfigMap `opentsx-prometheus-rules` with the alert rules below, for a Prometheus or vmalert you run yourself.

**Level 1 (proposal, not shipped):** kube-state-metrics + VictoriaMetrics single-node (or Prometheus) + vmalert +
Grafana in a namespace `monitoring`, installed from their Helm charts. Rough footprint (**assumption**): kube-state-metrics
~50 MB, VictoriaMetrics single ~150 MB, vmalert ~30 MB, Grafana ~150 MB, i.e. about 0.4 GB RAM and a few GB of disk for
30 days of k3s metrics. Scrape kube-state-metrics and the kubelet/cAdvisor; load
`deploy/k3s/components/monitoring/opentsx-rules.yaml` as a rule file. k3s itself only ships metrics-server (for
`kubectl top`), not Prometheus.

### Alert rules (all on metrics that exist today)

The file [`opentsx-rules.yaml`](../../deploy/k3s/components/monitoring/opentsx-rules.yaml) contains:

| Alert | Expression (namespace `opentsx`) | Severity |
|-------|----------------------------------|----------|
| `OpenTSxDeploymentUnavailable` | `kube_deployment_status_replicas_available < kube_deployment_spec_replicas` for 10 min | warning |
| `OpenTSxStatsCrashLooping` | `increase(kube_pod_container_status_restarts_total{container="stats"}[30m]) > 3` | critical |
| `OpenTSxSyntheticCheckFailing` | `time() - kube_cronjob_status_last_successful_time{cronjob="opentsx-synthetic-check"} > 1800` | critical |
| `OpenTSxJobFailed` | `kube_job_status_failed > 0` for 5 min (failed demo Jobs stay 24 h, then the alert clears) | warning |
| `OpenTSxDwdForwardStale` | `time() - kube_cronjob_status_last_successful_time{cronjob="opentsx-dwd-forward"} > 2 * 86400` | warning |
| `OpenTSxPodWaiting` | `kube_pod_container_status_waiting_reason{reason=~"CrashLoopBackOff\|ImagePullBackOff\|…"} > 0` for 10 min | warning |

With a CSI driver that reports volume statistics, add:

```promql
kubelet_volume_stats_used_bytes{namespace="opentsx"} / kubelet_volume_stats_capacity_bytes{namespace="opentsx"} > 0.85
```

The rules were checked for YAML structure and metric names against kube-state-metrics v2.x; they were **not** loaded
into a running Prometheus here.

### What OpenTSx does not export (yet)

None of the OpenTSx processes (stats, retrieval, Jobs, pollers) has a `/metrics` endpoint. Missing in particular:

* consumer lag per partition of the statistics processor (end offset − position), and the checkpoint age as a metric;
* counters: episodes read, statistics written, **undecodable records** (the processor skips records it cannot decode,
  e.g. written with a newer schema, and only counts them internally), DQ rule violations;
* retrieval request latency and error rate;
* Modbus poller: cycle duration, timeouts, exception responses, counter wraps/resets (today only in the log line
  `window done: …`);
* DWD fetch: issue time of the latest forecast, HTTP errors;
* KafScale broker metrics: depend on the KafScale release; not checked here.

These are backlog items (see [below](#backlog-operations)). Until then, the synthetic check and the logs are the
signals.

## Logging

* All OpenTSx processes log to stdout/stderr; containerd keeps the logs on the node under `/var/log/pods/`, rotated
  by the kubelet (default 10 MiB × 5 files per container). `kubectl -n opentsx logs deploy/opentsx-stats`,
  `kubectl -n opentsx logs job/opentsx-<demo>`.
* Job pods stay 24 h after completion (`ttlSecondsAfterFinished`), CronJob runs keep 1–3 finished Jobs.
* The JVM line `Picked up JAVA_TOOL_OPTIONS` and the Kafka client's INFO configuration dumps are noise; the scripts
  filter the first. Lines to search for: `FAIL`, `NOT reachable`, `checkpoint`, `read failed`, `error:`.
* Central logging is optional: Grafana Loki with Grafana Alloy (or Vector) as a DaemonSet; 14–30 days retention is
  enough for a lab.
* No script prints secrets; `20-configure.sh` writes them only to `deploy/k3s/.env` (mode 600) and the Secret. Do not
  paste `kubectl get secret -o yaml` output into tickets.

## Backup and restore

| Item | Where it lives | Backup | Restore | Lab RPO |
|------|----------------|--------|---------|---------|
| statistics checkpoint (positions + engine state) | PVC `opentsx-stats-state` | `70-backup.sh` | `71-restore.sh DIR --only stats-state` | 24 h; losing it only costs a re-read of the topics |
| demo data, DWD CSV, Modbus episode files | PVC `opentsx-data` | `70-backup.sh` (`--no-data` to skip) | `--only data` | 24 h |
| Iceberg catalog (lake) | PVC `opentsx-iceberg-catalog` (SQLite) | `70-backup.sh --quiesce` | `--only iceberg-catalog` | 24 h; **without it the Parquet files are orphaned** |
| warehouse bucket (lake, in-cluster MinIO) | PVC `opentsx-minio` | `70-backup.sh --quiesce` | `--only minio` | 24 h |
| warehouse on external S3 (`LAKE_MINIO=false`) | your S3 | S3 tooling (versioning, replication, `mc mirror`) | S3 tooling | as your S3 |
| configuration incl. secrets | `deploy/k3s/.env` | `70-backup.sh` copies it (`env`) | `--only env`, then `20-configure.sh` | every change |
| manifests, code | Git | Git | `git checkout <rev>` | — |
| KafScale topics | KafScale's S3 bucket (segments) + etcd | **KafScale side**: S3 versioning/replication of its bucket, etcd snapshots | KafScale tooling | KafScale's policy |
| images | containerd / registry | rebuild from Git with the same tag, or keep the registry | `10-build.sh`, `11-import-images.sh` | — |
| OpenMetadata | OpenMetadata's database | OpenMetadata's backup | or re-publish: `40-demo.sh metadata-publish` | — |

**KafScale data** is append-only: the topics are created with `cleanup.policy=delete` and no compaction (KafScale
does not compact; latest record per key wins on read). What is not kept by the retention is gone; the statistics can
always be rebuilt from the episodes that are still there (delete the checkpoint PVC and restart `opentsx-stats`).
Decide the retention per topic on the KafScale side ([sizing plan §4](sizing-plan.md#4-kafscale-side)).

```bash
deploy/k3s/70-backup.sh                       # live copy into ./opentsx-backup-<UTC time>/ (mode 700)
deploy/k3s/70-backup.sh --quiesce --out /srv/backup/opentsx/$(date +%F)   # stats, catalog and MinIO stopped meanwhile
deploy/k3s/71-restore.sh /srv/backup/opentsx/2026-10-12                    # asks; --yes to skip
deploy/k3s/71-restore.sh DIR --only stats-state --yes
```

* `70-backup.sh` starts a short-lived helper pod (tools image; root but unprivileged, only `CHOWN`, `DAC_OVERRIDE`,
  `FOWNER`) with the PVCs mounted read-only and streams `tar czf -` through `kubectl exec`. It writes a `MANIFEST`
  with the sha256 of every file. The backup contains `deploy/k3s/.env` **with secrets**: keep it private.
* `--quiesce` scales `opentsx-stats`, `opentsx-iceberg-rest` and `opentsx-minio` to 0 during the copy and back
  afterwards (a few seconds to minutes of statistics delay; the processor resumes from its checkpoint). Use it with
  the lake profile: a live copy of SQLite/MinIO files can be inconsistent. The checkpoint itself is always consistent
  (written to a temporary file and renamed).
* `71-restore.sh` verifies the checksums first, asks for confirmation (unless `--yes`), stops the writers, **replaces**
  the PVC contents and scales the writers back. Restore the statistics checkpoint only against the **same** topics: it
  stores offsets. After topics were deleted and recreated, skip `stats-state`.
* Schedule (lab): a daily cron entry on the admin host, e.g. `17 2 * * * cd /opt/opentsx && deploy/k3s/70-backup.sh
  --quiesce --out /srv/backup/opentsx/$(date +\%F)`, plus deleting copies older than 14 days. Test a restore every
  quarter into a second namespace (`20-configure.sh OPENTSX_NS=opentsx-restore …`).
* On a multi-node cluster all PVCs of one backup must be on the same node (the helper pod mounts all of them).

## Update and rollback

* **Images:** one tag per build. `20-configure.sh OPENTSX_TAG=3.0.1-local`, `10-build.sh`, `11-import-images.sh`,
  `30-deploy.sh`. A new tag needs no restart tricks; re-importing the **same** tag needs `30-deploy.sh --restart`.
  For production-like environments use `3.0.1-<git sha>` and a registry.
* **Configuration:** `20-configure.sh KEY=VALUE` re-renders the overlay; check with
  `kubectl diff -k deploy/k3s/overlays/local`, apply with `30-deploy.sh --restart` (the Deployments read the
  ConfigMap at start).
* **Rollback, declarative (preferred):** `20-configure.sh OPENTSX_TAG=<previous tag>` and `30-deploy.sh`. The old
  image must still be in containerd or the registry, so keep the previous tag.
* **Rollback, quick:** `kubectl -n opentsx rollout undo deployment/opentsx-retrieval` (or `opentsx-stats`). This
  diverges from the rendered overlay; the next `30-deploy.sh` applies the overlay again, so fix `.env` afterwards.
* **Statistics processor:** one replica, strategy `Recreate`: an update stops the old pod (it writes a final
  checkpoint within the 90 s grace period) before the new one starts. The checkpoint has a format marker
  (`TSXSTATS1`); a version that cannot read it fails at start. Then either roll back, or reset the state (setup plan,
  [Upgrade](setup-plan.md#upgrade)): the processor re-reads the topics, outputs are keyed and readers keep the latest
  record per key. Take a `70-backup.sh` before an upgrade so that a rollback can restore the old checkpoint.
* **Avro v2 schema compatibility:** records carry the Avro single-object encoding (schema fingerprint). Every
  released schema version is frozen in `opentsx-model/src/main/resources/schema-history/` and the build checks
  BACKWARD_TRANSITIVE compatibility, so a **newer reader reads older records**. The reverse does not hold: an older
  statistics processor cannot decode records written with a newer schema and skips them (counted only internally). So:
  **update readers first** (stats, retrieval, metadata), then writers (windpark-sim, pollers, collectors). A schema
  change without a new `schema-history` version fails the build.
* **Topics:** never deleted by the scripts; new partitions are only picked up after a restart of `opentsx-stats`.

## Secrets

| Secret | Used by | Stored in | Never in |
|--------|---------|-----------|----------|
| S3 access/secret key (lake) | MinIO, Iceberg REST, windpark-sim, retrieval | `deploy/k3s/.env` (mode 600) → Secret `opentsx-secrets` (`S3_*`, `AWS_*`) | Git, ConfigMap, images, logs |
| OpenMetadata bot token (`OPENMETADATA_JWT`) | `metadata-publish` | `.env` → Secret `opentsx-secrets` | as above |
| ENTSO-E API token | planned ENTSO-E collector (none in the code yet) | the same way: a key in `.env` → `opentsx-secrets` | as above |
| private twin data (house bills, boat logs) | `house`, `boat` Jobs | locally in `config/private/` (git-ignored), in the cluster only as Secret `opentsx-private` (created by hand, mounted read-only at `/private`) | Git, images, the Studio page, probe reports |
| MinIO root credentials (in-cluster) | MinIO | generated by `20-configure.sh` when empty | as above |
| KafScale credentials | — | KafScale has no authentication in this setup; protect it by network | — |

* Git ignores `deploy/k3s/.env`, `deploy/k3s/overlays/local/`, `config/private/**`, backups
  (`opentsx-backup-*`) and probe output (`probe-report-*.md`, `probe-logs-*`).
* Rotation: `20-configure.sh OPENMETADATA_JWT=<new>` (or `S3_ACCESS_KEY=… S3_SECRET_KEY=…`), then
  `30-deploy.sh --restart`. For in-cluster MinIO, changing the root credentials needs the data re-keyed; in a lab,
  create a new MinIO user instead.
* Backups made by `70-backup.sh` contain `.env` and therefore the secrets.
* The Studio image is built from the scenario templates only; no private data goes into an image.

## Security

* **Pods:** every OpenTSx pod runs as non-root (uid 10001; nginx uid 101), `allowPrivilegeEscalation: false`, all
  capabilities dropped, read-only root file system for the Jobs and the retrieval server. No privileged pods. The
  exceptions are third-party images (MinIO and the Iceberg REST fixture run with their image defaults) and the backup
  helper pod (root with `CHOWN`, `DAC_OVERRIDE`, `FOWNER`, only while a backup or restore runs).
* **Pod Security Admission (proposal):** label the namespace `pod-security.kubernetes.io/enforce=baseline` and
  `pod-security.kubernetes.io/warn=restricted`. `hostNetwork` (component `hostnetwork`) violates `baseline`: use it
  only for a single-host lab where KafScale advertises `localhost`, and prefer a reachable advertised address.
  `restricted` would additionally need `seccompProfile: RuntimeDefault` on every pod (not set yet; backlog).
* **Network policies (proposal, not shipped or tested):** k3s enforces NetworkPolicies out of the box (embedded
  controller). A sensible start: deny all ingress in `opentsx`, then allow the ingress controller (Traefik in
  `kube-system`) to reach `opentsx-studio` and `opentsx-retrieval`, `opentsx-modbus-poll` → `opentsx-modbus-sim:1502`,
  the lake components among themselves. Egress: DNS, the KafScale broker address(es), S3, `opendata.dwd.de:443` for
  the DWD CronJob, the Modbus converter. `kubectl port-forward` is not affected by NetworkPolicies.

  ```yaml
  apiVersion: networking.k8s.io/v1
  kind: NetworkPolicy
  metadata: {name: default-deny-ingress, namespace: opentsx}
  spec:
    podSelector: {}
    policyTypes: [Ingress]
  ```
* **APIs without authentication:** the retrieval server has none; keep its Service cluster-internal or put an
  authenticating proxy in front (`INGRESS=true` exposes it). The KafScale broker in this setup has none either.
* **Modbus:** Modbus TCP has no authentication or encryption. Put converter and poller on an isolated network
  segment. The OpenTSx client implements only the read function codes (0x01–0x04), so it cannot write to a meter.
* **Supply chain:** base images are pinned by tag (`eclipse-temurin:21.0.8_9-jre-noble`,
  `nginxinc/nginx-unprivileged:1.27.4-alpine`, MinIO, Iceberg REST); the tools image has no `RUN` step. Pin by digest
  for production-like environments.

## Runbooks

Each runbook: symptom → check → cause → fix → verify. Start every investigation with `deploy/k3s/50-status.sh`.

### R1: KafScale unreachable or wrong advertised address

* **Symptom:** `kafscale-check` prints `NOT reachable`; `windpark-sim` times out; `opentsx-stats` restarts; the
  synthetic check fails.
* **Check:** `deploy/k3s/40-demo.sh kafscale-check` (from inside the cluster). Read both lines: `bootstrap …` and
  `advertised …`. From the node: `nc -vz <host> <port>`.
* **Cause:** bootstrap unreachable (routing, firewall for `10.42.0.0/16`, wrong port), or the broker advertises an
  address only valid on its own host (`localhost`, a docker-internal IP).
* **Fix:** local compose: `deploy/k3s/60-probe.sh --local-kafscale --only kafscale-local,configure,deploy,kafscale-check`
  (restarts the broker advertising the LAN IP), or by hand
  `KAFSCALE_ADVERTISED_HOST=<LAN IP> docker compose -f docker-compose.kafscale.yml up -d`. Other KafScale: set
  `KAFSCALE_BROKER_HOST` (and no host in `KAFSCALE_BROKER_ADDR`) to a reachable address. Single host without that
  option: `20-configure.sh KAFSCALE_BOOTSTRAP=localhost:9092 KAFSCALE_NETWORK=hostnetwork`, `30-deploy.sh`.
* **Verify:** `kafscale-check` ends with `OK`; then `kubectl -n opentsx rollout restart deploy/opentsx-stats`.

### R2: Statistics processor stuck or crash-looping

* **Symptom:** checkpoint older than 5 minutes (`50-status.sh`, synthetic check), `series-stats` record count does not
  grow, or `CrashLoopBackOff`.
* **Check:** `kubectl -n opentsx logs deploy/opentsx-stats --previous`; `kubectl -n opentsx describe pod -l
  app.kubernetes.io/name=opentsx-stats` (OOMKilled?); `40-demo.sh kafscale-check` (are there new episodes?).
* **Causes and fixes:**
  * started before the topics existed → assigned nothing: `kubectl -n opentsx rollout restart deploy/opentsx-stats`;
  * KafScale unreachable → [R1](#r1-kafscale-unreachable-or-wrong-advertised-address);
  * `OOMKilled` → raise the memory limit in `deploy/k3s/base/stats.yaml` (~3× checkpoint size + 300 MB,
    [sizing plan §4.1](sizing-plan.md#41-statistics-processor));
  * checkpoint unreadable after an upgrade → roll back, or reset (setup plan, [Upgrade](setup-plan.md#upgrade));
  * PVC full → [R3](#r3-pvc-full);
  * two processors (replicas > 1) → keep `replicas: 1`, `Recreate`.
* **Verify:** checkpoint age < 60 s twice in a row; `series-stats` count grows after the next `windpark-sim`.

### R3: PVC full

* **Symptom:** synthetic check `a volume is more than 85 % full`; write errors (`No space left on device`) in the
  stats processor or Jobs; MinIO returns errors.
* **Check:** `kubectl -n opentsx get pvc`; on the node `df -h /var/lib/rancher/k3s/storage` (local-path); inside:
  `kubectl -n opentsx exec deploy/opentsx-stats -- df -h /state`.
* **Cause:** with `local-path` the "size" of a PVC is not enforced: all volumes share the node's disk, so a full disk
  is the real limit. Growth sources: DWD/Modbus files on `opentsx-data` (the CronJob deletes CSV older than 30 days;
  Modbus episode files are not pruned), MinIO warehouse, container logs and images.
* **Fix:** remove old files (`kubectl -n opentsx run` a helper or use `70-backup.sh` then prune), prune images
  (`sudo k3s crictl rmi --prune`), grow the disk; with a CSI driver expand the PVC
  (`kubectl patch pvc … -p '{"spec":{"resources":{"requests":{"storage":"5Gi"}}}}'`).
* **Verify:** synthetic check green; writers running again (R2 verify).

### R4: Iceberg REST catalog down

* **Symptom:** retrieval (lake) errors or not ready; `windpark-sim` fails at the Iceberg sink; `opentsx-iceberg-rest`
  not ready.
* **Check:** `kubectl -n opentsx logs deploy/opentsx-iceberg-rest`; `kubectl -n opentsx logs deploy/opentsx-minio`;
  `kubectl -n opentsx logs job/opentsx-minio-bucket`.
* **Cause:** MinIO not ready or bucket missing, wrong `S3_ENDPOINT`/keys, catalog PVC lost or corrupt (SQLite).
* **Fix:** wait for MinIO, `30-deploy.sh` (recreates the bucket Job); fix S3 settings via `20-configure.sh`; catalog
  lost → `71-restore.sh DIR --only iceberg-catalog,minio` (restore both from the same backup).
* **Verify:** `curl` via port-forward `http://localhost:8181/v1/config` answers; `40-demo.sh retrieval` returns
  `wind.met.wind_speed` episodes.

### R5: OpenMetadata 401

* **Symptom:** `metadata-publish` fails with HTTP 401 (or 403).
* **Check:** `kubectl -n opentsx logs job/opentsx-metadata-publish`; is `OM_URL` the API base (`…:8585/api`)?
* **Cause:** bot token expired, revoked or for another OpenMetadata instance; token missing in the Secret.
* **Fix:** create a new token for the bot in OpenMetadata (Settings → Bots), `20-configure.sh OPENMETADATA_JWT=<token>`,
  `30-deploy.sh` (updates the Secret).
* **Verify:** `40-demo.sh metadata-publish` completes; `40-demo.sh metadata` (dry run) always works without a server.

### R6: DWD fetch failing

* **Symptom:** `opentsx-dwd-forward` Job failed (`station … FAILED`), `OpenTSxDwdForwardStale`, `40-demo.sh
  dwd-online` fails.
* **Check:** `kubectl -n opentsx logs job/<latest dwd-forward job>`; from a pod:
  `kubectl -n opentsx run dwdtest --rm -i --image=<tools image> --restart=Never -- dwd stations --near 50.6,13.0`.
* **Cause:** no internet/DNS from the pods, a required HTTPS proxy (the collector honours `HTTPS_PROXY`), a wrong
  station id (MOSMIX ids such as `10578` differ from CDC ids such as `01358`), a DWD outage or file layout change.
* **Fix:** allow egress to `opendata.dwd.de:443` (or set `HTTPS_PROXY` in the CronJob), correct `DWD_STATIONS`
  (`bin/opentsx dwd stations --near LAT,LON`), retry later. The offline mode (`40-demo.sh dwd`, `DWD_MODE=offline`)
  separates OpenTSx problems from network problems: if it passes, the image and PVC are fine.
* **Verify:** `40-demo.sh dwd-forward` completes; new `mosmix-<station>-<time>.csv` files on the data PVC.

### R7: Modbus timeouts or exception responses

* **Symptom:** `modbus-poll` fails (exit 1), log lines `read failed: … Connection refused`, `timeout`,
  `ILLEGAL_DATA_ADDRESS`, `ILLEGAL_DATA_VALUE`; windows with `missing` values.
* **Check:** simulator: `kubectl -n opentsx get deploy opentsx-modbus-sim`, `kubectl -n opentsx logs
  deploy/opentsx-modbus-sim`; real device: `MODBUS_HOST`/`MODBUS_PORT` in `.env`, reachability from the node
  (`nc -vz <converter> 502`); one cycle: `40-demo.sh modbus -- --count 1`.
* **Cause:** simulator not deployed (`MODBUS_SIM=false`), converter unreachable from the pod network (meter network
  only on the node: use the `hostnetwork` pattern for the poller), wrong unit id or framing (`tcp` vs. `rtu-over-tcp`),
  wrong register addresses (0- vs. 1-based) or merged reads across gaps.
* **Fix:** `20-configure.sh MODBUS_SIM=true` + `30-deploy.sh`; for real meters follow
  [opentsx-modbus, "When the real register sheets arrive"](../../opentsx-modbus/README.md#when-the-real-register-sheets-arrive):
  `modbus check`, `addressBase`, `maxRegistersPerRequest`, `maxGap: 0`.
* **Verify:** `40-demo.sh modbus` completes and every window reports `0 missing`.

### R8: Studio blank or not reachable

* **Symptom:** empty page, 404 or connection refused at `http://localhost:8088` (port-forward) or via the ingress.
* **Check:** `kubectl -n opentsx get deploy opentsx-studio`; `curl -s -o /dev/null -w '%{http_code} %{size_download}'
  http://localhost:8088/` (expect `200` and ~1.2 MB); the browser console for script errors.
* **Cause:** image built from an incomplete stage (the page generation failed), old image under the same tag, local
  port taken, ingress disabled.
* **Fix:** `10-build.sh` (re-stages and rebuilds; the studio build stage fails loudly if the export fails),
  `11-import-images.sh`, `30-deploy.sh --restart`; another local port:
  `kubectl -n opentsx port-forward svc/opentsx-studio 9099:80`.
* **Verify:** the probe's `studio` step (`60-probe.sh --skip-build --only studio`).

## Roles and responsibilities

| Role | Responsible for |
|------|-----------------|
| **Platform operator** | k3s nodes, storage class, network and firewall, image import/registry, backups (running and testing `70-backup.sh`/`71-restore.sh`), the monitoring stack |
| **KafScale operator** | broker(s), advertised addresses, KafScale's S3 bucket and etcd, retention per topic, KafScale upgrades |
| **OpenTSx service owner** | images and tags, configuration (`.env`), deployments and upgrades, the statistics processor, runbooks R1–R2, R4, R8, the probe |
| **Data / domain owner** | scenarios, station lists (`DWD_STATIONS`), Modbus register maps, OpenMetadata content, private data and who may see it (R5–R7 together with the service owner) |
| **Security role** | secrets handling and rotation, network policies, access to the namespace, review of exposure (`INGRESS`) |

| Activity | Platform | KafScale | Service owner | Data owner | Security |
|----------|:--------:|:--------:|:-------------:|:----------:|:--------:|
| install / probe | C | C | **R** | I | I |
| upgrade OpenTSx | C | I | **R** | I | I |
| change retention | I | **R** | C | C | I |
| backup and restore test | **R** | C (KafScale side) | C | I | I |
| new meter / register map | C | I | C | **R** | C |
| secret rotation | C | I | C | I | **R** |
| incident triage | **R** (infrastructure) | **R** (broker) | **R** (OpenTSx) | C | C |

R = responsible, C = consulted, I = informed. In a small lab one person holds several roles; the split still tells
you which hat you are wearing.

## Service levels for a lab

Simple targets, measured with the signals above, reviewed monthly:

| Objective | Target (lab) | Measured by |
|-----------|--------------|-------------|
| synthetic check passes (KafScale reachable, checkpoint fresh, disks < 85 %) | ≥ 95 % of runs per month | CronJob history / `kube_cronjob_status_last_successful_time` |
| statistics freshness | checkpoint younger than 5 min, ≥ 99 % of the time while episodes arrive | synthetic check |
| retrieval API available | `/v1/health` ready ≥ 99 % during working hours | readiness / `kube_deployment_status_replicas_available` |
| DWD forecast | a successful fetch on ≥ 95 % of days, never two days in a row missed | `OpenTSxDwdForwardStale` |
| backup | daily, restore tested once per quarter | backup directory, test log |
| recovery (lab) | RPO 24 h for PVC state, RTO 2 h after a node loss (rebuild + restore) | restore test |

If a target is missed twice in a row, the next change slot goes to the cause (no new features first).

## Capacity review

Monthly in the lab (and after every new data source), compare the installation with the
[sizing plan](sizing-plan.md):

1. `kubectl top pod -n opentsx` during a demo run and the steady state → requests/limits ([§1](sizing-plan.md#1-components-requests--limits-as-shipped)).
2. Checkpoint size (`50-status.sh`) → stats memory limit ≈ 3 × checkpoint + 300 MB ([§4.1](sizing-plan.md#41-statistics-processor)).
3. Disk usage from the synthetic check (`df`) → act at 70 %, alarm at 85 %.
4. Bytes in KafScale's bucket and in the warehouse after a known run → B/value, compare with 2.18 B/value
   ([§3](sizing-plan.md#3-data-volume)); project with the formula for the planned channels.
5. Record the measured values in the sizing plan and replace its **assumption** rows.

Triggers for an earlier review: a new park, more Modbus points or a shorter poll interval, a new DWD station list, the
lake profile switched on.

## Backlog (operations)

* `/metrics` endpoints (Prometheus format) in the Java processes: consumer lag per partition and checkpoint age
  (stats), records read/written/undecodable, DQ violations, retrieval latency/errors, Modbus cycle time/timeouts/
  exceptions, DWD issue time and fetch errors; then ServiceMonitor/annotations and Grafana dashboards.
* KafScale sink for the Modbus poller and the DWD collector (`tsx.<domain>.*`, `tsx.weather.*`) instead of files.
* A long-running poller Deployment (instead of the demo Job) close to the converter, with buffering across outages.
* NetworkPolicies and Pod Security labels as a tested component; `seccompProfile: RuntimeDefault` on all pods.
* Scheduled backups inside the cluster (CronJob with an S3 target) instead of a cron entry on the admin host.
* Image digests and signatures; an SBOM per image.

## What was validated here

| Check | Result |
|-------|--------|
| `bash -n` and `shellcheck` (0.10) on all scripts, including `60-probe.sh`, `70-backup.sh`, `71-restore.sh` | clean |
| `kubectl kustomize` for 6 configurations (default, hostnetwork + modbus-sim + dwd-forward + monitoring, service by IP and by DNS, lake + MinIO + ingress + all new components + storage class, lake with external S3 + OpenMetadata) and the backup helper pod | renders; `kubeconform -strict` against Kubernetes 1.31 schemas: 145 resources valid |
| the CI job "k3s scripts and manifests" (extended: new components, kubeconform, probe dry run) | its commands run locally and pass |
| `60-probe.sh --dry-run` (with and without `--local-kafscale`, invalid configuration, `--only`) | plan, validation and report as expected; nothing written to `.env` or the overlay |
| `60-probe.sh` full run against a **stub** `kubectl` (simulated cluster; port-forwards served locally) | all steps, SKIP/blocking logic, failure hints and the report work |
| `70-backup.sh` / `71-restore.sh` against the stub | archives, MANIFEST, checksum check before restore, confirmation, writers scaled down and back |
| Job and CronJob scripts of `dwd`, `dwd-forward`, `modbus-poll`, `synthetic-check`, run with `/bin/sh` (dash) through the image's entrypoint on the staged image tree | offline and online (live MOSMIX from opendata.dwd.de) DWD paths, Modbus poller against `modbus simulate` (56 episodes in 30 s, failing exit code when nothing listens), synthetic check failure path |
| KafScale path through the image entrypoint against a live KafScale broker (built from source, in-memory S3) | `kafscale-check --provision` (bootstrap and advertised address reachable), `windpark-sim --kafscale` (bucket sealed, 3,600 episodes), `stats` → **4,300 `series-stats` records** (4,200 closed windows + 100 GLOBAL) and a 135 KB checkpoint; `00-prereqs.sh` KafScale section passes |
| **not run here** | `docker build`, `docker compose up` (no container daemon available), anything on a real k3s (`kubectl apply`, Jobs, port-forwards, NetworkPolicies), the advertised-address failure as seen from a pod, the lake path on S3, the alert rules in a running Prometheus |

The first real run is `deploy/k3s/60-probe.sh --local-kafscale` on a machine with docker and k3s; its report shows
exactly which of the untested parts work.
