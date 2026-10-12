# Troubleshooting

Start with `deploy/k3s/50-status.sh` (workloads, Jobs, PVCs, images, recent warnings) and
`kubectl -n opentsx describe pod <pod>`.

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

## Scripts

| Symptom | Fix |
|---------|-----|
| `no rendered overlay` | `20-configure.sh KAFSCALE_BOOTSTRAP=<host:port>` |
| `cluster not reachable` | `export KUBECONFIG=/etc/rancher/k3s/k3s.yaml` (readable by you) or `20-configure.sh KUBECTL="sudo k3s kubectl"` |
| `bash >= 4 required` (macOS) | run the scripts on the Linux host, or install a current bash |
| `20-configure.sh`: `… is loopback` | pods cannot reach `localhost` of the host: use the LAN IP or `KAFSCALE_NETWORK=hostnetwork` |
