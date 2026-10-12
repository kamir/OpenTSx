# Setup plan

The steps in order, each with what it does, the command and how to verify it. All scripts live in
[`deploy/k3s/`](../../deploy/k3s/); run them from the repository root. Every script has `--help`.
`deploy/k3s/60-probe.sh` runs steps 0–5 in one go and writes a PASS/FAIL report
([operations](operations.md#try-it-locally-first-the-probe)); operating the installation afterwards (monitoring,
backup, updates, runbooks) is described in the [operations concept](operations.md).

| Step | Script | Result |
|------|--------|--------|
| 0 | `00-prereqs.sh` | tools, cluster and KafScale checked |
| 1 | `10-build.sh` | `opentsx/tools:<tag>` and `opentsx/studio:<tag>` built |
| 2 | `11-import-images.sh` | images in k3s' containerd (or in a registry) |
| 3 | `20-configure.sh KEY=VALUE…` | `deploy/k3s/.env` and the overlay `deploy/k3s/overlays/local/` |
| 4 | `30-deploy.sh` | namespace, config, PVCs, stats, retrieval, Studio (+ lake) running |
| 5 | `40-demo.sh <demo>` | examples run as Jobs, output in the terminal |
| — | `50-status.sh`, `90-teardown.sh` | state; removal |
| — | `60-probe.sh` | all of the above plus checks, as one trial run with a report |
| — | `70-backup.sh`, `71-restore.sh` | backup and restore of PVCs and configuration |

`20-configure.sh` can run at any time and is idempotent. Run it **before** step 1 when you change the image
repository, tag or platform (`OPENTSX_IMAGE_REPO`, `OPENTSX_TAG`, `OPENTSX_PLATFORM`), because `10-build.sh` reads them.

## Prerequisites

| Where | Tool | Version | Why |
|-------|------|---------|-----|
| build machine | JDK | 17 or 21 (with `javac`) | `mvn install`, compiling the deployment helper |
| build machine | Maven | 3.9+ | build, classpath per module |
| build machine | Python | 3.10+ | only to build the Studio page outside the image build |
| build machine | docker (with buildx for cross-builds), podman, nerdctl or buildah | any current | images |
| k3s host | k3s | a current release (manifests validated against the Kubernetes 1.31 schemas) | cluster with containerd, local-path storage, Traefik |
| k3s host | `kubectl` | matching the cluster | `k3s kubectl` works too: `KUBECTL="sudo k3s kubectl"` |
| both | bash 4+, coreutils, `timeout`, `curl` | | scripts |
| network | route from the k3s pods to the KafScale broker(s) | | see step 3 |
| optional | S3 endpoint (MinIO or other) and an Iceberg REST catalog | | lake profile: retrieval on Iceberg |
| optional | OpenMetadata 2.x with a bot token | | `metadata-publish` |

Build machine and k3s host can be the same machine (typical lab setup) or different (e.g. build on a laptop, run on a
Raspberry Pi: `IMAGE_METHOD=ssh`, `OPENTSX_PLATFORM=linux/arm64`).

## Step 0: check prerequisites

```bash
KAFSCALE_BOOTSTRAP=192.168.1.50:9092 deploy/k3s/00-prereqs.sh
```

Checks the tools, `kubectl get nodes`, node architectures, the default StorageClass, TCP reachability of the
bootstrap address and, once the image context is staged (after step 1), the **advertised broker addresses** with
`kafscale-check`. Options: `--no-build` on a k3s node that only imports images, `--no-cluster` on a build machine.

**Verify:** the script ends with `all required checks passed`. Warnings (e.g. Python missing) are allowed.

## Step 1: build

```bash
deploy/k3s/10-build.sh                       # mvn -q install -DskipTests, stage, build both images
deploy/k3s/10-build.sh --with-s3             # lake profile: adds S3FileIO (iceberg-aws + AWS SDK bundle)
deploy/k3s/10-build.sh --platform linux/arm64 --tag 3.0.0-pi   # for a Raspberry Pi
```

What happens:

1. `mvn -q -B install -DskipTests` builds all modules (skip with `--skip-maven`).
2. [`deploy/docker/stage.sh`](../../deploy/docker/stage.sh) copies every runtime jar of the v2 modules once into
   `deploy/docker/.stage/opt/opentsx/lib/m2/` (Maven repository layout, so no version clashes), writes the exact
   classpath per module to `cp/<module>.txt` (the same resolution `bin/opentsx` uses), compiles the deployment helper
   `KafScaleCheck`, and copies the wind-farm scenario, the twin plans and CSV templates, the Modbus register maps and the
   DWD offline fixture (a MOSMIX KML and a station-catalog excerpt) to `share/`.
3. `opentsx-tools.Dockerfile` copies that tree onto `eclipse-temurin:21.0.8_9-jre-noble` (non-root user 10001,
   `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError`).
4. `studio.Dockerfile` runs `StudioExport` and `tools/build_studio.py` in build stages and puts the page into
   `nginxinc/nginx-unprivileged:1.27.4-alpine`. The page is byte-identical to the committed
   `docs/prototypes/smart-topics-studio.html` when built from the same code.

**Verify:**

```bash
docker run --rm opentsx/tools:3.0.0-local help          # list of commands
docker run --rm opentsx/tools:3.0.0-local twin | head    # H2 chain demo output
docker images | grep opentsx                            # tools ~ 0.4-0.5 GB (JRE + ~120 MB jars), studio ~ 50 MB
```

Without a container runtime you can still check the staged commands:
`OPENTSX_HOME=deploy/docker/.stage/opt/opentsx deploy/docker/.stage/opt/opentsx/bin/opentsx twin`.

**Multi-arch.** The jars are platform-independent and `eclipse-temurin`, `nginx-unprivileged`, `minio` and
`iceberg-rest-fixture` publish amd64 and arm64 images. Neither Dockerfile has a `RUN` step for the target platform,
so `docker buildx build --platform linux/arm64 --load` works on an x86 machine without QEMU. A multi-platform manifest
(`--platform linux/amd64,linux/arm64`) needs `--push` to a registry instead of `--load`.

## Step 2: make the images available to k3s

k3s runs its own containerd; images in the docker daemon are not visible to it. The manifests use fixed tags and
`imagePullPolicy: IfNotPresent`.

```bash
deploy/k3s/11-import-images.sh                                   # docker save | sudo k3s ctr images import -
deploy/k3s/11-import-images.sh --method podman                   # podman save | ...
deploy/k3s/11-import-images.sh --method buildah                  # via docker-archive
deploy/k3s/11-import-images.sh --method ssh --ssh pi@boat-pi     # k3s on another machine (e.g. the Pi)
deploy/k3s/11-import-images.sh --method registry                 # push to OPENTSX_IMAGE_REPO=registry.lan:5000/opentsx
```

Other paths: build directly into k3s' containerd with
`NERDCTL="nerdctl --address /run/k3s/containerd/containerd.sock --namespace k8s.io" 10-build.sh --builder nerdctl`
(then `IMAGE_METHOD=none`), or copy a `docker save` tarball to `/var/lib/rancher/k3s/agent/images/` (air-gapped
install; k3s imports it at start). A plain-HTTP registry must be listed in `/etc/rancher/k3s/registries.yaml`.

**Verify:** `sudo k3s ctr images ls -q | grep opentsx` shows `docker.io/opentsx/tools:<tag>` and
`docker.io/opentsx/studio:<tag>`. After re-importing the **same** tag, run `30-deploy.sh --restart`.

## Step 3: network path to KafScale

This is the step that decides whether everything else works. A Kafka-protocol client (KafScale is one) connects to
the **bootstrap** address, receives the cluster metadata and then connects to the **advertised** address of each
broker. Both must be reachable from the pods.

| KafScale runs … | Advertised address must be … | `KAFSCALE_NETWORK` | `KAFSCALE_BOOTSTRAP` |
|-----------------|------------------------------|--------------------|----------------------|
| on another machine in the LAN | that machine's LAN IP or DNS name | `direct` (default) | `192.168.1.50:9092` |
| on the k3s host (docker compose), advertising the host's LAN IP | the host's LAN IP (`KAFSCALE_ADVERTISED_HOST=192.168.1.20 docker compose -f docker-compose.kafscale.yml up -d`) | `direct` | `192.168.1.20:9092` |
| on the k3s host, advertising `localhost` (compose default) | — (only reachable from the host's network namespace) | `hostnetwork` | `localhost:9092` |
| outside, and you want a stable in-cluster name | reachable from the pods anyway | `service` | the external `host:port`; pods use `opentsx-kafscale.<ns>.svc.cluster.local:<port>` |
| inside the same k3s cluster | the broker's Service DNS name | `direct` | `<svc>.<ns>.svc.cluster.local:9092` |

* **`hostnetwork`** puts only the KafScale-facing workloads (stats, kafscale-check, windpark-sim, metadata-publish;
  label `opentsx.org/kafka=true`) into the node's network namespace. Single node, lab use.
* **`service`** renders a selector-less Service with an EndpointSlice (IP address) or an ExternalName Service (DNS
  name). It gives the bootstrap a cluster-internal name and nothing more; it does not rewrite advertised addresses.
* Pods reach LAN addresses through the node (flannel masquerades pod traffic); a host firewall (ufw, firewalld) must
  allow the k3s pod CIDR (default `10.42.0.0/16`) to reach the broker port.

```bash
deploy/k3s/20-configure.sh KAFSCALE_BOOTSTRAP=192.168.1.50:9092              # direct
deploy/k3s/20-configure.sh KAFSCALE_BOOTSTRAP=localhost:9092 KAFSCALE_NETWORK=hostnetwork
```

**Verify** (after step 4): `deploy/k3s/40-demo.sh kafscale-check` prints `reachable` for the bootstrap **and** every
advertised address, then the topics of the domain with record counts, and `OK`.

## Step 3 (cont.): configuration

`20-configure.sh` takes `KEY=VALUE` arguments (precedence: arguments > environment > existing `.env` >
[`env.example`](../../deploy/k3s/env.example)), writes `deploy/k3s/.env` (mode 600) and renders
`deploy/k3s/overlays/local/` (git-ignored). It validates the values and renders the overlay with `kubectl kustomize`.

| Key | Default | Meaning |
|-----|---------|---------|
| `OPENTSX_NS` | `opentsx` | namespace |
| `KUBECTL` | `kubectl` | e.g. `sudo k3s kubectl` |
| `STORAGE_CLASS` | cluster default (`local-path`) | for all PVCs |
| `KAFSCALE_BOOTSTRAP` | — (**required**) | `host:port[,host:port]` reachable from the pods |
| `KAFSCALE_NETWORK` | `direct` | `direct`, `hostnetwork`, `service` |
| `OPENTSX_DOMAIN` | `wind` | topics `tsx.<domain>.{episodes,buckets,series,observations,series-stats}.v2` |
| `OPENTSX_PARTITIONS` | `6` | data partitions when OpenTSx creates the topics |
| `OPENTSX_IMAGE_REPO`, `OPENTSX_TAG` | `opentsx`, `3.0.0-local` | image names `<repo>/tools:<tag>`, `<repo>/studio:<tag>` |
| `OPENTSX_PLATFORM` | native | `linux/arm64` for a Pi |
| `IMAGE_METHOD` | `docker` | see step 2 |
| `LAKE` | `false` | Iceberg REST catalog; retrieval server and windpark-sim on Iceberg |
| `LAKE_MINIO` | `true` | MinIO in the cluster; `false` = use `S3_ENDPOINT` |
| `S3_ENDPOINT`, `S3_BUCKET`, `S3_REGION` | `http://opentsx-minio:9000`, `lake`, `us-east-1` | S3 for the warehouse |
| `S3_ACCESS_KEY`, `S3_SECRET_KEY` | generated for in-cluster MinIO | Secret only, never in Git |
| `OM_URL`, `OPENMETADATA_JWT` | empty | OpenMetadata `http://host:8585/api` and bot token (Secret) |
| `INGRESS` | `false` | Traefik Ingress: `/` → Studio, `/v1` → retrieval (**no authentication**) |
| `DWD_FORWARD` | `false` | CronJob `opentsx-dwd-forward`: daily MOSMIX per station → CSV on the data PVC |
| `DWD_STATIONS`, `DWD_SCHEDULE`, `DWD_MODE` | `10578,10579`, `17 5 * * *`, `online` | MOSMIX ids, cron schedule (cluster time, usually UTC), `offline` = fixture in the image |
| `MODBUS_SIM` | `false` | Deployment + Service `opentsx-modbus-sim:1502` (simulated meter, example map) |
| `MODBUS_HOST`, `MODBUS_PORT`, `MODBUS_MAP` | `opentsx-modbus-sim`, `1502`, `example` | device the `modbus-poll` Job reads |
| `MONITORING` | `false` | CronJob `opentsx-synthetic-check` + ConfigMap `opentsx-prometheus-rules` ([operations](operations.md#monitoring-and-alerting)) |

Rendered objects: Namespace, ConfigMap `opentsx-config` (non-secret values), Secret `opentsx-secrets` (S3 keys as
`S3_*` and `AWS_*`, `OPENMETADATA_JWT`), the base and the selected components. Private twin data (bills, boat logs)
go into a separate Secret `opentsx-private` that you create by hand (see [Demos](demos.md#house)).

**Verify:** `kubectl kustomize deploy/k3s/overlays/local` renders; the script prints the object count.

## Step 4: deploy

```bash
deploy/k3s/30-deploy.sh             # kubectl apply -k deploy/k3s/overlays/local, wait for rollouts
deploy/k3s/30-deploy.sh --dry-run   # server-side dry run
deploy/k3s/30-deploy.sh --restart   # after a config change or a re-imported image with the same tag
```

**Verify:**

```bash
deploy/k3s/50-status.sh
kubectl -n opentsx get deploy        # opentsx-stats 1/1, opentsx-retrieval 1/1, opentsx-studio 1/1 (+ iceberg-rest, minio)
kubectl -n opentsx port-forward svc/opentsx-retrieval 8080:8080 &  curl localhost:8080/v1/health   # {"status":"ok"}
```

The statistics processor discovers partitions **when it starts**. If the topics did not exist yet, it runs idle
until it is restarted; `40-demo.sh windpark-sim` restarts it automatically, and so does `kafscale-check` followed by
`kubectl -n opentsx rollout restart deploy/opentsx-stats`.

## Step 5: demos

```bash
deploy/k3s/40-demo.sh kafscale-check     # first: from inside the cluster; also provisions tsx.wind.*.v2
deploy/k3s/40-demo.sh all                # every example; details and expected output in demos.md
```

See [Demos](demos.md).

## Optional: lake profile (Iceberg + S3)

```bash
deploy/k3s/20-configure.sh LAKE=true                     # + MinIO in the cluster (credentials generated)
deploy/k3s/20-configure.sh LAKE=true LAKE_MINIO=false S3_ENDPOINT=http://nas.lan:9000 S3_ACCESS_KEY=… S3_SECRET_KEY=…
deploy/k3s/10-build.sh --with-s3 && deploy/k3s/11-import-images.sh && deploy/k3s/30-deploy.sh --restart
```

* Adds `opentsx-iceberg-rest` (Apache reference REST catalog, JDBC/SQLite metadata on a PVC, data on
  `s3://<bucket>/`), optionally `opentsx-minio` plus a Job that creates the bucket.
* Switches the retrieval server to `--iceberg type=rest,uri=http://opentsx-iceberg-rest:8181,…` and adds
  `--iceberg` to `windpark-sim`. The S3 credentials reach the JVMs as `AWS_ACCESS_KEY_ID`/`AWS_SECRET_ACCESS_KEY`.
* **Why `--with-s3`:** `opentsx-store-iceberg` ships the Hadoop client, but not `S3FileIO`. Without
  `iceberg-aws` and `iceberg-aws-bundle` on the classpath an `s3://` warehouse fails with a class-not-found error.
  The build adds both jars (~65 MB) only when asked. Verified here: the classpath builds; the S3 path itself is
  **not yet tested end-to-end** (see the [test status](today-vs-planned.md#what-was-tested)).
* The REST catalog image is a reference implementation. For anything beyond a lab use a hardened catalog
  (Apache Polaris, Lakekeeper, Nessie); only the `ICEBERG_PROPS` change.

**Verify:** `40-demo.sh windpark-sim` then `40-demo.sh retrieval` returns `wind.met.wind_speed` episodes of T07.

## Optional: OpenMetadata

```bash
deploy/k3s/20-configure.sh OM_URL=http://openmetadata.lan:8585/api OPENMETADATA_JWT=<bot token>
deploy/k3s/30-deploy.sh && deploy/k3s/40-demo.sh metadata-publish
```

`40-demo.sh metadata` (dry run, no server) works without this.

## Upgrade

```bash
git pull
deploy/k3s/20-configure.sh OPENTSX_TAG=3.0.1-local      # new tag: no restart tricks needed
deploy/k3s/10-build.sh && deploy/k3s/11-import-images.sh && deploy/k3s/30-deploy.sh
```

The statistics checkpoint survives on its PVC; the processor resumes where it stopped. If a new version cannot read
an old checkpoint, the pod fails at start; then reset the state:
`kubectl -n opentsx scale deploy/opentsx-stats --replicas=0 && kubectl -n opentsx delete pvc opentsx-stats-state &&
deploy/k3s/30-deploy.sh`. The processor then re-reads the topic from the beginning; outputs are keyed and readers keep
the latest record per key.

## Rollback and teardown

| Goal | Command |
|------|---------|
| previous version | `20-configure.sh OPENTSX_TAG=<old tag>` and `30-deploy.sh` (old image still in containerd) |
| stop workloads, keep data | `90-teardown.sh --keep-data` |
| remove everything in the cluster | `90-teardown.sh` (namespace incl. PVCs; asks first, `--yes` to skip) |
| also remove the images | `90-teardown.sh --images` |
| forget the configuration | `rm -r deploy/k3s/.env deploy/k3s/overlays/local` |

The KafScale topics `tsx.<domain>.*.v2` are never deleted by these scripts. Remove them with your KafScale
tooling if needed; a new run of the same scenario writes duplicate episodes, which readers drop by `episodeId`.
