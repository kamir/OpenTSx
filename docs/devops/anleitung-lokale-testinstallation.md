# Try OpenTSx locally: a test installation with k3s and KafScale

*Printable guide for `master`.*

> **What you get.** One Linux machine runs a local KafScale (log storage, Kafka protocol, through docker compose) and
> a k3s cluster with the statistics processor, the retrieval server and Smart Topics Studio. The wind farm simulator
> and the three twins (H2 chain, house, boat) run as Jobs.
>
> **Time:** 60 to 90 minutes, of which 10 to 20 minutes are build time. **Machine:** 4 CPU cores, 8 GB RAM (16 GB is
> more comfortable), 20 GB free disk, Ubuntu 24.04 LTS (other Linux systems work in the same way), internet access for
> the downloads.
>
> **What is verified:** CI runs shellcheck and the shell tests on the scripts, validates the rendered manifests against
> the Kubernetes 1.31 schemas, checks that the image offers the commands of `bin/opentsx` and loads the configuration
> of every rendered `opentsx/tools` container. CI does not run a complete installation on a real k3s with KafScale
> ([What CI checks](today-vs-planned.md#what-ci-checks)). Fill in the checklist (part E) and note anything unexpected.

| Notes | |
|---|---|
| Date | &nbsp; |
| Machine / operating system | &nbsp; |
| LAN IP of the machine (`HOST_IP`, step A5) | &nbsp; |
| Result (all green / stopped at step ...) | &nbsp; |

## See what runs where

```text
+---------------------- one Linux machine ----------------------+
|                                                                |
|  docker compose (part B)            k3s (part C)               |
|  +----------------------+  9092    +------------------------+  |
|  | KafScale broker      |<-------->| namespace opentsx      |  |
|  | etcd, MinIO (S3)     | HOST_IP  | statistics, retrieval, |  |
|  +----------------------+          | Studio, demo Jobs      |  |
|                                    +------------------------+  |
|                                                                |
+----------------------------------------------------------------+
```

The most important point: KafScale must announce itself with the **LAN IP** of the machine (not with `localhost`).
The pods in k3s first connect to the bootstrap address and then to the address that the broker advertises. Both must
be reachable from the pods.

## Part A: Prepare the machine (once)

**A1: Base packages** (Java 21, Python, Docker with compose):

```bash
sudo apt-get update
sudo apt-get install -y git curl netcat-openbsd openjdk-21-jdk python3 docker.io docker-compose-v2
sudo usermod -aG docker "$USER"
```

Then **log out and log in again**, so that the group `docker` takes effect. Check: `docker run --rm hello-world`.

**A2: Maven 3.9** (Ubuntu ships 3.8; `deploy/k3s/00-prereqs.sh` expects 3.9 or newer):

```bash
MVN=3.9.9
URL=https://archive.apache.org/dist/maven/maven-3/$MVN/binaries
curl -fsSL $URL/apache-maven-$MVN-bin.tar.gz | sudo tar -xz -C /opt
echo "export PATH=/opt/apache-maven-$MVN/bin:\$PATH" >> ~/.bashrc
source ~/.bashrc
mvn -v            # expected: Apache Maven 3.9.9, Java version: 21
```

**A3: k3s** (Kubernetes for one machine):

```bash
curl -sfL https://get.k3s.io | sh -
mkdir -p ~/.kube
sudo k3s kubectl config view --raw > ~/.kube/config
chmod 600 ~/.kube/config
echo 'export KUBECONFIG=$HOME/.kube/config' >> ~/.bashrc
source ~/.bashrc
kubectl get nodes   # expected: one line with STATUS Ready
```

**A4: Get OpenTSx:**

```bash
git clone https://github.com/kamir/OpenTSx.git
cd OpenTSx
```

Run all further commands in this directory.

**A5: Find the LAN IP** and write it into the notes above:

```bash
HOST_IP=$(hostname -I | awk '{print $1}')
echo "$HOST_IP"     # for example 192.168.1.20; not 127.0.0.1 and not 172.17.x.x (Docker)
```

If the first address is not the LAN address, look at `ip -4 addr` and set `HOST_IP=<address>` by hand. Set
`HOST_IP` again in every new terminal.

## Shortcut: parts B to E with one command

After part A, one script does the rest: it starts KafScale locally (with the LAN IP as advertised address), builds,
imports, configures, deploys, runs all demos and checks every step. At the end it writes a report with PASS, FAIL or
SKIP per step and hints for each failure.

```bash
deploy/k3s/60-probe.sh --dry-run --local-kafscale   # show the plan only
deploy/k3s/60-probe.sh --local-kafscale             # trial run, 30 to 60 minutes
```

| Option | Effect |
|---|---|
| `--skip-build` | skip the build and the image import (the images are already in k3s) |
| `--online` | the DWD step fetches live forecasts (default: the fixture file in the image, no internet needed) |
| `--keep-going` | continue after a failed `kafscale-local`, `prereqs`, `build`, `import`, `configure` or `deploy` step |
| `--only kafscale-check,twin` | run only these steps |
| `--kafscale-host 192.168.1.20` | with `--local-kafscale`: the IP to advertise and use, when the automatic choice is wrong |

Result: `probe-report-<UTC time>.md` (summary) and `probe-logs-<UTC time>/` (output per step). Exit code 0 means
that no step failed. To go step by step or to narrow down a failure, follow parts B to E.

## Part B: Start a local KafScale

```bash
KAFSCALE_ADVERTISED_HOST=$HOST_IP docker compose -f docker-compose.kafscale.yml up -d
docker compose -f docker-compose.kafscale.yml ps   # kafscale-broker, etcd and minio are running
nc -vz "$HOST_IP" 9092                              # expected: succeeded / open
```

Only when a firewall (ufw) is active, allow the pods to reach the broker:

```bash
sudo ufw allow from 10.42.0.0/16 to any port 9092 proto tcp
```

## Part C: Install OpenTSx in k3s

| Step | Command | Expected |
|---|---|---|
| C1 check the prerequisites | `KAFSCALE_BOOTSTRAP=$HOST_IP:9092 deploy/k3s/00-prereqs.sh` | last line: `all required checks passed` (warnings are allowed) |
| C2 build (10 to 20 minutes) | `deploy/k3s/10-build.sh` | two images `opentsx/tools` and `opentsx/studio` |
| C3 give the images to k3s | `deploy/k3s/11-import-images.sh` | asks for the sudo password, imports both images |
| C4 configure | `deploy/k3s/20-configure.sh KAFSCALE_BOOTSTRAP=$HOST_IP:9092` | writes `deploy/k3s/.env` |
| C5 deploy | `deploy/k3s/30-deploy.sh` | namespace `opentsx`, three Deployments; on this new broker it stops with `FAIL  deployment.apps/opentsx-stats: not ready after 180s; ...` (exit code 1), because the topics of the domain do not exist yet: run C7, then `deploy/k3s/30-deploy.sh --restart` |
| C6 look at the state | `deploy/k3s/50-status.sh` | all pods `Running`, no errors |
| C7 check the connection | `deploy/k3s/40-demo.sh kafscale-check` | see below |

Expected output of C7 (trimmed):

```text
bootstrap 192.168.1.20:9092
  bootstrap 192.168.1.20:9092: reachable
advertised broker addresses (what clients really connect to):
  advertised 192.168.1.20:9092: reachable
domain wind:
  tsx.wind.episodes.v2                  6 partitions             0 records
  ...
OK
```

If it shows `advertised localhost:9092: NOT reachable`, KafScale advertises the wrong address: repeat part B with
`KAFSCALE_ADVERTISED_HOST` set (`docker compose -f docker-compose.kafscale.yml down`, then `up -d`).

## Part D: Run the demos

All in one pass (about 5 minutes):

```bash
deploy/k3s/40-demo.sh all
```

Or one at a time:

| Command | What happens | How you see that it worked |
|---|---|---|
| `deploy/k3s/40-demo.sh windpark-sim` | the wind farm simulator writes episodes to KafScale | Job `Complete`; `kafscale-check` then shows records in `tsx.wind.episodes.v2` |
| `deploy/k3s/40-demo.sh stats` | the statistics processor summarizes the episodes | records in `tsx.wind.series-stats.v2` |
| `deploy/k3s/40-demo.sh twin` | H2 chain: shadow, what-if, forecast | events such as `protective_shutdown`, a run id and a hash per run |
| `deploy/k3s/40-demo.sh house` | single-family house: reference year and levers | KPIs (heating oil, firewood, electricity) and the differences per lever |
| `deploy/k3s/40-demo.sh boat` | Etap 22: a two-week trip, levers solar, storage and Starlink | autonomy and unmet demand per variant |
| `deploy/k3s/40-demo.sh retrieval` | the retrieval API answers | `/v1/health` and an episode as JSON |
| `deploy/k3s/40-demo.sh studio` | the Studio in the browser | **http://localhost:8088** shows the H2 chain, the house and the boat in three views (Ctrl-C stops it) |

The results are reproducible: the same code and the same inputs give the same run id and the same hash.

## Part E: Tick off the checklist

| Done | Check | Note |
|---|---|---|
| [ ] | A: `mvn -v` shows Maven 3.9 and Java 21 | |
| [ ] | A: `kubectl get nodes` shows `Ready` | |
| [ ] | B: `nc -vz $HOST_IP 9092` succeeds | |
| [ ] | C1: `all required checks passed` | |
| [ ] | C2 and C3: both images built and imported | |
| [ ] | C6: all pods `Running` | |
| [ ] | C7: bootstrap **and** advertised `reachable`, `OK` | |
| [ ] | D: wind farm episodes and statistics in KafScale | |
| [ ] | D: twin, house and boat run to the end | |
| [ ] | D: retrieval answers, Studio visible in the browser | |
| [ ] | Shortcut: `60-probe.sh` without FAIL, report attached | |

## Part F: Fix common problems

| Symptom | Cause | Fix |
|---|---|---|
| `ErrImagePull` / `ImagePullBackOff` | the images are not in k3s (k3s does not see Docker's images) | `deploy/k3s/11-import-images.sh`, then `deploy/k3s/30-deploy.sh --restart` |
| `bootstrap ... NOT reachable` | firewall or wrong IP | `nc -vz $HOST_IP 9092`; the ufw rule from part B; check the IP in C4 |
| `advertised localhost:9092: NOT reachable` | KafScale advertises `localhost` | restart part B with `KAFSCALE_ADVERTISED_HOST=$HOST_IP` |
| statistics stay at 0 records | the processor exits with 2 until the topics exist and waits in `CrashLoopBackOff` | `deploy/k3s/40-demo.sh kafscale-check`, then `kubectl -n opentsx rollout restart deploy/opentsx-stats` |
| pod `OOMKilled` | not enough memory | close other programs; limits in `docs/devops/sizing-plan.md` |
| `port-forward`: address already in use | port 8088 is taken | `kubectl -n opentsx port-forward svc/opentsx-studio 9099:80`, then open http://localhost:9099 |
| old version after a rebuild | same image tag | `deploy/k3s/30-deploy.sh --restart` |

More: `docs/devops/troubleshooting.md`. State and logs at a glance: `deploy/k3s/50-status.sh --logs`.

## Part G: Clean up

```bash
deploy/k3s/90-teardown.sh                              # remove OpenTSx from k3s (asks first)
docker compose -f docker-compose.kafscale.yml down -v  # remove the local KafScale and its data
/usr/local/bin/k3s-uninstall.sh                        # only if k3s should go completely
```

## Plan B: run only the twins, without a cluster

To see only the models, you need neither Docker nor k3s (only parts A1, A2 and A4):

```bash
mvn -q install -DskipTests
bin/opentsx twin                 # H2 chain in three modes
bin/opentsx house --simulated    # house with simulated bills and calibration
bin/opentsx boat --simulated     # boat with simulated logger data
```

Open the Studio directly in the browser: `docs/prototypes/smart-topics-studio.html`.

## Operate it after the test

The [operations concept](operations.md) describes monitoring, backup, updates and incident procedures. Backup and
restore: `deploy/k3s/70-backup.sh` and `deploy/k3s/71-restore.sh`.

*Further reading:* [DevOps guide](README.md) · [Operations](operations.md) · [Setup plan](setup-plan.md) ·
[Sizing plan](sizing-plan.md) · [Demos](demos.md) · [Troubleshooting](troubleshooting.md)
