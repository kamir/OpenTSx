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
| `30-deploy.sh`: `FAIL  deployment.apps/<name>: not ready after 180s; see kubectl -n opentsx describe deployment.apps/<name>` (exit code 1) | the readiness probe of that Deployment does not pass ([which probe checks what](operations.md#read-the-probes)) | `kubectl -n opentsx describe pod -l app.kubernetes.io/name=<name>` shows the probe output under `Events`; fix the cause, then run `30-deploy.sh` again |
| `Readiness probe failed: HTTP probe failed with statuscode: 503` for `opentsx-retrieval` or `opentsx-event-bridge` | the retrieval feed or the bridge does not reach the broker: `GET /v1/ready` of the retrieval server through a port-forward names the reason; the bridge answers 503 when the broker does not answer a topic lookup within 2 s, and its log has `not ready, the broker cannot be reached cause=<cause>` | [R1](operations.md#r1-kafscale-unreachable-or-wrong-advertised-address) |
| `opentsx-saas-backend` not ready; its log repeats `not ready db=<host>:<port>/<db> cause=<cause>` | the database does not answer `SELECT 1` within 2 s | `kubectl -n opentsx logs deploy/opentsx-saas-db`; the backend gets ready again by itself |
| `Liveness probe failed` and a restart of `opentsx-stats` or `opentsx-vectors` | the poll loop did not pass for about 12 minutes: the probe found a progress file older than 10 minutes 3 times in a row, 60 s apart | `kubectl -n opentsx logs deploy/<name> --previous`; [R2](operations.md#r2-statistics-processor-stuck-or-crash-looping) |

## KafScale

| Symptom | Cause | Fix |
|---------|-------|-----|
| `kafscale-check`: bootstrap `NOT reachable` | routing/firewall from the pod network, wrong port | from the node: `nc -vz <host> <port>`; open the firewall for the pod CIDR (`10.42.0.0/16`) |
| bootstrap reachable, **advertised** `localhost:9092` (or another unreachable address) `NOT reachable`; clients time out | the broker advertises an address only valid on its own host | KafScale: set `KAFSCALE_BROKER_HOST` to a reachable address (compose: `KAFSCALE_ADVERTISED_HOST=<LAN IP>`), or `KAFSCALE_NETWORK=hostnetwork` when KafScale runs on the k3s host |
| `advertised …` list empty | no topic with partitions exists yet | run `kafscale-check` (the Job provisions the topics through `OPENTSX_KAFSCALE_PROVISION=true`, deploy/k3s/jobs/kafscale-check.yaml), then check again |
| `opentsx-stats` not ready or in `CrashLoopBackOff` on a new broker; its log ends with `opentsx: tsx.<domain>.episodes.v2: the input topics do not exist or have no partitions; ...` | the topics of the domain do not exist yet | `40-demo.sh kafscale-check` creates them; then `kubectl -n opentsx rollout restart deploy/opentsx-stats` (`40-demo.sh windpark-sim` restarts it too) |
| the stats pod log repeats `WARN org.apache.kafka.clients.NetworkClient - [Producer clientId=producer-1] Connection to node -1 (/<host>:<port>) could not be established. Node may not be available.` and `Bootstrap broker <host>:<port> (id: -1 rack: null) disconnected` | the bootstrap address is not reachable from the pod | as above |
| consumer hangs or rejoins | consumer groups on KafScale | OpenTSx tools use manual assignment; do the same in your own code |
| duplicate episodes after a rerun | expected: no idempotent producer | readers and stores drop duplicates by `episodeId` |
| `offsetsForTimes` returns nothing | not supported by KafScale | use the episode index (`/data/<domain>-index.tsv`) or `ReplayRequest.range` |
| a reader ends with `Unexpected error code 7 while fetching at offset ...`; producers log `WARN org.apache.kafka.clients.producer.internals.Sender` lines with `Got error produce response ... retrying`; for about a minute nothing is read or written | KafScale rates its object store as degraded after one slow write and refuses every produce and fetch with REQUEST_TIMED_OUT until its 60 s average recovers | nothing to repair: the OpenTSx readers wait it out (`org.opentsx.kafka.Patience`, which logs `the broker refuses reads; waiting` at WARN) and producers retry for up to three minutes. In your own code do the same; watch `kafscale_s3_health_state` on the broker when it happens often |
| a producer logs `ERROR org.apache.kafka.clients.producer.internals.Sender - [Producer clientId=<id>] Uncaught error in kafka producer I/O thread:` and, on the next line, `java.lang.RuntimeException: Tag 0 is not valid for version 9`, and then sends nothing any more, to any topic | the KafScale proxy answers a produce request that mixes a partition it knows an owner for with one it does not (a topic nobody has written to yet) with an error entry the Kafka client cannot parse. Part of the request may be written all the same | send the first records to a new topic with a producer that writes nothing else, and treat a send that never completes as the end of that producer: close it and create a new one. The vector processor does both (one producer per sink). A proxy with the fix of kafscale issue 19 makes the answer readable |
| two stats processors | `replicas > 1` or a rolling update | keep `replicas: 1` and `strategy: Recreate` (as shipped) |

## Log lines of the statistics processor and the readers

`kubectl -n opentsx logs deploy/opentsx-stats` shows one line per event: time, thread, level, logger and message, the
message as `<what happened> key=value ...`. The lines below are what follows `<level> <logger> - `.

| Line | Meaning | Fix |
|------|---------|-----|
| `INFO org.opentsx.stats.StatsProcessor - checkpoint restored path=<file> domain=<domain> positions=<topic>-<partition>:<offset>,...` | the processor reads on from these positions | nothing; `no checkpoint; reading the topics from the log start path=<file>` instead means the file was missing and every record is read again |
| `INFO org.opentsx.stats.StatsProcessor - partitions assigned count=<n> start=<topic>-<partition>:<offset>,...` | the partitions it reads and the offset each starts at | nothing |
| `WARN org.opentsx.stats.StatsProcessor - no partitions assigned; the input topics do not exist or have no partitions topics=<topic>,...`, then `opentsx: tsx.<domain>.episodes.v2: the input topics do not exist or have no partitions; check opentsx.domain (--domain, OPENTSX_DOMAIN), or create the topics first` and exit code 2 | the processor started before the topics of the domain existed | `deploy/k3s/40-demo.sh kafscale-check` provisions the topics of `OPENTSX_DOMAIN`; the restarted pod assigns the partitions, `kubectl -n opentsx rollout restart deploy/opentsx-stats` restarts it at once |
| `INFO org.opentsx.stats.StatsProcessor - progress records=<n> undecodable=<n> invalid=<n> lostToRetention=<n> statsWritten=<n> duplicates=<n> lateValues=<n>` | written after every checkpoint, every 30 s; the counts are since the process started, `duplicates` and `lateValues` are carried over in the checkpoint | no new line for more than a minute: the processor waits for the broker (see the `Patience` lines) or hangs; a rising `undecodable` or `invalid`: see the next rows |
| `WARN org.opentsx.stats.StatsProcessor - undecodable record skipped and counted in undecodable; later ones of this partition are only counted topic=<topic> partition=<partition> offset=<offset> cause=<reason>` | a record is not an OpenTSx v2 Avro record, for example one written with a newer schema or by another tool; it gets no statistics | find its producer from topic, partition and offset; the processor goes on |
| `WARN org.opentsx.stats.StatsProcessor - invalid episode skipped and counted in invalid; later ones of this partition are only counted topic=<topic> partition=<partition> offset=<offset> cause=<reason>` | the episode decodes but is inconsistent; `<reason>` names the field, for example `count: "3" differs from the length 2 of values; expected the number of values` | fix the producer; the processor goes on |
| `WARN org.opentsx.stats.StatsProcessor - retention deleted records before they were read; reading on from the log start topic=<topic> partition=<partition> position=<position> logStart=<start> lost=<n>` | the processor was stopped or behind for longer than the retention of the topic; the `<n>` records get no statistics | keep the processor running, or raise the retention of the topic |
| `INFO org.opentsx.stats.StatsProcessorApp - stopping; waiting for the final checkpoint wait=PT1M`, then `INFO org.opentsx.stats.StatsProcessor - final checkpoint written path=<file> positions=...` | a clean stop | nothing |
| `WARN org.opentsx.stats.StatsProcessorApp - the processor did not stop in time; the final checkpoint may be missing wait=PT1M` | the run did not end within 60 s after `stopping` | nothing to repair: the next start reads on from the last checkpoint and may write some statistics again; readers keep the latest record |
| `WARN org.opentsx.kafka.Patience - the broker refuses reads; waiting pause=PT5S limit=PT10M cause=Unexpected error code 7 while fetching ...`, later `INFO org.opentsx.kafka.Patience - the broker answers reads again refusedFor=<duration>` | KafScale refuses fetches, see [KafScale](#kafscale) | nothing; after 10 minutes of refusals in a row the reader fails with `the broker has refused reads for more than PT10M` |
| `WARN org.opentsx.kafka.LogTail - retention deleted records before they were read; reading on from the log start topic=<topic> partition=<partition> position=<position> logStart=<start> lost=<n>` | a tail read too late: one of the retrieval server (episodes, signal, vector, control or status topic), whose answers then miss the records, or the control tail of the vector processor, which then misses the DAG deployments and stops among them | raise the retention of the topic |
| `WARN org.opentsx.kafka.EpisodeLogTail - undecodable record skipped and counted in undecodable; later ones of this partition are only counted topic=<topic> partition=<partition> offset=<offset> cause=<reason>` | a record of an episode topic that the retrieval server follows is not an episode | find its producer from topic, partition and offset |
| `WARN org.opentsx.retrieval.vector.VectorProcessor - undecodable record skipped and counted in undecodable; later ones of this partition are only counted dag=<dag> sink=<vector> topic=<topic> partition=<partition> offset=<offset> cause=<reason>` | a record of the source topic of a sink is not an OpenTSx v2 episode; the sink skips it and counts it in `undecodable` of its status | find its producer from topic, partition and offset; the sink goes on |

`opentsx.log.level` sets the level of the processor, for example `OPENTSX_LOG_LEVEL=warn` keeps the WARN and ERROR
lines only.

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
| `40-demo.sh dwd`: `opentsx: …/share/dwd/…kml: no such file; give an existing MOSMIX file` (exit code 2) | image staged before the DWD fixture was added | `10-build.sh`, re-import, `30-deploy.sh --restart` |
| `dwd-online` / `dwd-forward`: `opentsx: java.io.IOException: cannot fetch https://opendata.dwd.de/…` with a cause such as `UnresolvedAddressException`, or `cannot fetch … after 4 tries` after timeouts (exit code 1) | no internet or DNS from the pods, proxy required | allow egress to `opendata.dwd.de:443`, or `OPENTSX_HTTPS_PROXY` in the Job; offline mode to separate the cases ([R6](operations.md#r6-dwd-fetch-failing)) |
| `dwd-forward`: `station … FAILED` | MOSMIX id wrong (CDC ids differ) | `bin/opentsx dwd stations --near LAT,LON`, `20-configure.sh DWD_STATIONS=…` |
| `40-demo.sh modbus`: `simulator not deployed` | `MODBUS_SIM=false` and `MODBUS_HOST` is the simulator | `20-configure.sh MODBUS_SIM=true`, `30-deploy.sh` |
| `modbus-poll` fails: `Connection refused` / timeouts | simulator not ready, converter unreachable from the pod network | `kubectl -n opentsx logs deploy/opentsx-modbus-sim`; real converter: routing from the pod CIDR ([R7](operations.md#r7-modbus-timeouts-or-exception-responses)) |
| `ILLEGAL_DATA_ADDRESS`, `ILLEGAL_DATA_VALUE` | register map does not match the device | [opentsx-modbus README](../../opentsx-modbus/README.md#bind-a-vendor-register-sheet) |

## Probe, backup, restore

| Symptom | Fix |
|---------|-----|
| probe: `kafscale-local` FAIL, `docker compose … up failed` | docker daemon running and accessible? `docker compose version` (v2 plugin) or `docker-compose` |
| probe: `could not detect this host's IP` | `60-probe.sh --local-kafscale --kafscale-host <LAN IP>` |
| probe: every step after `deploy` is SKIP | a critical step failed; fix it (report) or run with `--keep-going` |
| probe: `series-stats` FAIL with records 0 | the processor exits with 2 until the topics exist and waits in `CrashLoopBackOff`: `40-demo.sh kafscale-check`, then `kubectl -n opentsx rollout restart deploy/opentsx-stats` and `60-probe.sh --skip-build --only series-stats`; longer wait: `PROBE_STATS_WAIT=600` |
| probe: `retrieval`/`studio` port-forward failed | local ports 18080/18088 taken: `PROBE_RETRIEVAL_PORT=…`, `PROBE_STUDIO_PORT=…` |
| `70-backup.sh` / `71-restore.sh`: `helper pod not ready` | tools image not in containerd, or the PVCs are on different nodes (`kubectl -n opentsx describe pod opentsx-backup-helper`) |
| `71-restore.sh`: `checksum mismatch` | the backup directory was changed or damaged; use another backup |

## Scripts

Every script in `deploy/k3s/` exits with 2 on a usage error and with 1 when a check or a step failed
([exit codes](../../deploy/k3s/README.md#read-the-exit-codes)).

| Symptom | Fix |
|---------|-----|
| `no rendered overlay in deploy/k3s/overlays/local; run deploy/k3s/20-configure.sh first` | `20-configure.sh KAFSCALE_BOOTSTRAP=<host:port>` |
| `cluster not reachable` | `export KUBECONFIG=/etc/rancher/k3s/k3s.yaml` (readable by you) or `20-configure.sh KUBECTL="sudo k3s kubectl"` |
| `bash >= 4 required` (macOS) | run the scripts on the Linux host, or install a current bash |
| `20-configure.sh`: `… is loopback` | pods cannot reach `localhost` of the host: use the LAN IP or `KAFSCALE_NETWORK=hostnetwork` |
| any script: `deploy/k3s/.env:<n>: unknown key <KEY>; use a key of deploy/k3s/env.example` (exit code 2) | a misspelled key in `.env`: change it to the key of `env.example` or delete the line; the script left `.env` as it is |
| any script: `deploy/k3s/.env:<n>: not a KEY=VALUE line; write KEY=VALUE, a comment or an empty line` (exit code 2) | the scripts would run that line as a shell command: write it as `KEY=VALUE` or delete it |
| `20-configure.sh`: `<KEY>: "<value>" …; expected …` or `<KEY>: the value holds a control character such as a line break; write it on one line` (exit code 1) | set the value in the form named after `expected`; the script wrote nothing |
| `40-demo.sh`: `<demo>: unknown demo; expected one of …` (exit code 2) | `40-demo.sh --help` describes the demos |
| `40-demo.sh <demo> -- …`: `<demo>: takes no extra arguments; drop the arguments after --` (exit code 2) | `stats`, `retrieval`, `studio`, `dwd-forward` and `all` run without arguments: drop `--` and what follows |
| `40-demo.sh <demo> -- …`: the Job fails with `opentsx: --<option>: set twice on the command line (also by --<option>); set each key once` | the Job command names that option itself; [Pass settings to a command](../../deploy/k3s/README.md#pass-settings-to-a-command) lists the options it names |
| `40-demo.sh metadata-publish`: `OPENMETADATA_JWT: not set; …` (exit code 1, no Job started) | `read -rs OPENMETADATA_JWT && export OPENMETADATA_JWT`, then `20-configure.sh` and `30-deploy.sh` |
| `40-demo.sh retrieval`: `FAIL  GET http://localhost:18080/…: no answer (curl exit <n>); …` or `…: HTTP <status>; …` (exit code 1) | `50-status.sh` and `kubectl -n opentsx logs deploy/opentsx-retrieval`; the port-forward ends with the script |
| `70-backup.sh --quiesce`: `<deployment>: pods still run 150 s after scaling to 0 (<pods>); stop them, then run 70-backup.sh again` (exit code 1, no backup written) | the script scaled the writers back; `kubectl -n opentsx describe pod <pod>` shows why the pod does not end |
| `70-backup.sh --quiesce`: `<deployment>: cannot read the Deployment; check the cluster with …` (exit code 1) | the cluster did not answer: see `cluster not reachable` |
| `90-teardown.sh --images`: `warn  <image>: not removed from k3s containerd: <reason>`, then exit code 1 | run it as a user who may run `sudo k3s ctr`, or remove the image by hand: `sudo k3s ctr images rm <image>` |
