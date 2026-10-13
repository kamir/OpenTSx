# Troubleshooting

Start with `deploy/k3s/50-status.sh` (workloads, Jobs, CronJobs, PVCs, images, checkpoint age, recent warnings) and
`kubectl -n opentsx describe pod <pod>`. The probe (`deploy/k3s/60-probe.sh`) puts the matching hints into its report;
step-by-step runbooks for the common incidents are in [operations](operations.md#runbooks).

## Images and pods

| Symptom | Cause | Fix |
|---------|-------|-----|
| `ErrImageNeverPull`, `ErrImagePull`, `ImagePullBackOff` for `opentsx/…` | image not in k3s' containerd (docker's images are invisible to k3s), or tag mismatch | `11-import-images.sh`; check `sudo k3s ctr images ls -q \| grep opentsx` against `OPENTSX_TAG` in `deploy/k3s/.env` |
| old behaviour after a rebuild | same tag, `IfNotPresent` keeps the running image | `30-deploy.sh --restart`, or use a new `OPENTSX_TAG` |
| `exec format error` | amd64 image on an arm64 node (Pi) or vice versa | `20-configure.sh OPENTSX_PLATFORM=linux/arm64`, `10-build.sh`, re-import |
| PVC `Pending` | no default StorageClass, or `WaitForFirstConsumer` (normal until a pod uses it) | k3s: `local-path` must exist; or `20-configure.sh STORAGE_CLASS=<class>` |
| pod `OOMKilled` | limit too small for the run | raise the limit in `deploy/k3s/base` or `jobs/` (see [sizing plan](sizing-plan.md)); `house --calibrate` needs > 384 MB heap |
| `CreateContainerConfigError` | a referenced ConfigMap key or Secret is missing (e.g. lake without S3 keys) | re-run `20-configure.sh` and `30-deploy.sh` |
| `opentsx: no classpath for module …` | image built from an incomplete stage | `10-build.sh` (it re-stages from `mvn install`) |

## KafScale

| Symptom | Cause | Fix |
|---------|-------|-----|
| `kafscale-check`: bootstrap `NOT reachable` | routing/firewall from the pod network, wrong port | from the node: `nc -vz <host> <port>`; open the firewall for the pod CIDR (`10.42.0.0/16`) |
| bootstrap reachable, **advertised** `localhost:9092` (or another unreachable address) `NOT reachable`; clients time out | the broker advertises an address only valid on its own host | KafScale: set `KAFSCALE_BROKER_HOST` to a reachable address (compose: `KAFSCALE_ADVERTISED_HOST=<LAN IP>`), or `KAFSCALE_NETWORK=hostnetwork` when KafScale runs on the k3s host |
| `advertised …` list empty | no topic with partitions exists yet | run `kafscale-check` (it provisions with `--provision true`), then check again |
| stats runs but `series-stats` stays at 0 records | the processor started before the topics existed and assigned nothing | `kubectl -n opentsx rollout restart deploy/opentsx-stats` (`40-demo.sh windpark-sim` does this) |
| stats pod restarts with `topic provisioning failed` / timeouts | broker not reachable from the pod | as above |
| consumer hangs or rejoins | consumer groups on KafScale | OpenTSx tools use manual assignment; do the same in your own code |
| duplicate episodes after a rerun | expected: no idempotent producer | readers and stores drop duplicates by `episodeId` |
| `offsetsForTimes` returns nothing | not supported by KafScale | use the episode index (`/data/<domain>-index.tsv`) or `ReplayRequest.range` |
| a reader ends with `Unexpected error code 7 while fetching at offset ...`; producers log `Got error produce response ... retrying`; for about a minute nothing is read or written | KafScale rates its object store as degraded after one slow write and refuses every produce and fetch with REQUEST_TIMED_OUT until its 60 s average recovers | nothing to repair: the OpenTSx readers wait it out (`org.opentsx.kafka.Patience`) and producers retry for up to three minutes. In your own code do the same; watch `kafscale_s3_health_state` on the broker when it happens often |
| a producer logs `Uncaught error in kafka producer I/O thread: java.lang.RuntimeException: Tag 0 is not valid for version 9` and then sends nothing any more, to any topic | the KafScale proxy answers a produce request that mixes a partition it knows an owner for with one it does not (a topic nobody has written to yet) with an error entry the Kafka client cannot parse. Part of the request may be written all the same | send the first records to a new topic with a producer that writes nothing else, and treat a send that never completes as the end of that producer: close it and create a new one. The vector processor does both (one producer per sink). A proxy with the fix of kafscale issue 19 makes the answer readable |
| two stats processors | `replicas > 1` or a rolling update | keep `replicas: 1` and `strategy: Recreate` (as shipped) |

## Lake profile

| Symptom | Cause | Fix |
|---------|-------|-----|
| `ClassNotFoundException: org.apache.iceberg.aws.s3.S3FileIO` | tools image built without S3 support | `10-build.sh --with-s3`, re-import, `30-deploy.sh --restart` |
| retrieval/windpark-sim: S3 403 or `NoSuchBucket` | wrong keys, bucket missing | `kubectl -n opentsx logs job/opentsx-minio-bucket`; external S3: create the bucket, check `S3_ACCESS_KEY`/`S3_SECRET_KEY` |
| Iceberg REST catalog not ready | MinIO not ready yet, or wrong `S3_ENDPOINT` | `kubectl -n opentsx logs deploy/opentsx-iceberg-rest` |
| catalog empty after a restart | catalog PVC lost | the SQLite catalog lives on the PVC `opentsx-iceberg-catalog`; a full `90-teardown.sh` deletes it (`--keep-data` keeps it) |

## Studio and retrieval

| Symptom | Cause | Fix |
|---------|-------|-----|
| Studio shows an old state | the page is baked into the image | rebuild (`10-build.sh`), re-import, `30-deploy.sh --restart` |
| `port-forward` fails: address in use | local port taken | `kubectl -n opentsx port-forward svc/opentsx-studio 9099:80` |
| retrieval returns `[]` in lake mode | no data in the namespace yet | run `40-demo.sh windpark-sim` with `LAKE=true` first |
| retrieval reachable from the LAN without login | `INGRESS=true` | the server has no authentication: switch the ingress off or put an authenticating proxy in front |

## DWD and Modbus

| Symptom | Cause | Fix |
|---------|-------|-----|
| `40-demo.sh dwd`: `NoSuchFileException …/share/dwd/…kml` | image staged before the DWD fixture was added | `10-build.sh`, re-import, `30-deploy.sh --restart` |
| `dwd-online` / `dwd-forward`: connect timeout, `UnknownHostException` | no internet or DNS from the pods, proxy required | allow egress to `opendata.dwd.de:443`, or `HTTPS_PROXY` in the Job; offline mode to separate the cases ([R6](operations.md#r6-dwd-fetch-failing)) |
| `dwd-forward`: `station … FAILED` | MOSMIX id wrong (CDC ids differ) | `bin/opentsx dwd stations --near LAT,LON`, `20-configure.sh DWD_STATIONS=…` |
| `40-demo.sh modbus`: `simulator not deployed` | `MODBUS_SIM=false` and `MODBUS_HOST` is the simulator | `20-configure.sh MODBUS_SIM=true`, `30-deploy.sh` |
| `modbus-poll` fails: `Connection refused` / timeouts | simulator not ready, converter unreachable from the pod network | `kubectl -n opentsx logs deploy/opentsx-modbus-sim`; real converter: routing from the pod CIDR ([R7](operations.md#r7-modbus-timeouts-or-exception-responses)) |
| `ILLEGAL_DATA_ADDRESS`, `ILLEGAL_DATA_VALUE` | register map does not match the device | [opentsx-modbus README](../../opentsx-modbus/README.md#when-the-real-register-sheets-arrive) |

## Probe, backup, restore

| Symptom | Fix |
|---------|-----|
| probe: `kafscale-local` FAIL, `docker compose … up failed` | docker daemon running and accessible? `docker compose version` (v2 plugin) or `docker-compose` |
| probe: `could not detect this host's IP` | `60-probe.sh --local-kafscale --kafscale-host <LAN IP>` |
| probe: every step after `deploy` is SKIP | a critical step failed; fix it (report) or run with `--keep-going` |
| probe: `series-stats` FAIL with records 0 | processor started before the topics: `kubectl -n opentsx rollout restart deploy/opentsx-stats`, then `60-probe.sh --skip-build --only series-stats`; longer wait: `PROBE_STATS_WAIT=600` |
| probe: `retrieval`/`studio` port-forward failed | local ports 18080/18088 taken: `PROBE_RETRIEVAL_PORT=…`, `PROBE_STUDIO_PORT=…` |
| `70-backup.sh` / `71-restore.sh`: `helper pod not ready` | tools image not in containerd, or the PVCs are on different nodes (`kubectl -n opentsx describe pod opentsx-backup-helper`) |
| `71-restore.sh`: `checksum mismatch` | the backup directory was changed or damaged; use another backup |

## Scripts

| Symptom | Fix |
|---------|-----|
| `no rendered overlay` | `20-configure.sh KAFSCALE_BOOTSTRAP=<host:port>` |
| `cluster not reachable` | `export KUBECONFIG=/etc/rancher/k3s/k3s.yaml` (readable by you) or `20-configure.sh KUBECTL="sudo k3s kubectl"` |
| `bash >= 4 required` (macOS) | run the scripts on the Linux host, or install a current bash |
| `20-configure.sh`: `… is loopback` | pods cannot reach `localhost` of the host: use the LAN IP or `KAFSCALE_NETWORK=hostnetwork` |
