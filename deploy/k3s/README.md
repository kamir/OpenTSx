# deploy/k3s: OpenTSx on k3s against an existing KafScale cluster

The guide is [docs/devops/](../../docs/devops/README.md) (setup plan, sizing plan, demos, troubleshooting, operations).

```bash
deploy/k3s/00-prereqs.sh                                          # check tools, cluster, KafScale
deploy/k3s/10-build.sh                                            # mvn install + images opentsx/tools, opentsx/studio
deploy/k3s/11-import-images.sh                                    # into k3s' containerd
deploy/k3s/20-configure.sh KAFSCALE_BOOTSTRAP=192.168.1.50:9092   # writes .env + overlays/local (git-ignored)
deploy/k3s/30-deploy.sh                                           # kubectl apply -k overlays/local
deploy/k3s/40-demo.sh all                                         # every example as a Job, with output
deploy/k3s/50-status.sh                                           # state
deploy/k3s/90-teardown.sh                                         # remove (KafScale topics stay)

deploy/k3s/60-probe.sh --local-kafscale                           # all of the above in one trial run -> probe-report-<time>.md
deploy/k3s/70-backup.sh --quiesce                                 # PVCs + configuration -> ./opentsx-backup-<time>/
deploy/k3s/71-restore.sh ./opentsx-backup-<time>                  # back into the PVCs (asks first)
```

| Path | Content |
|------|---------|
| `env.example` | all configuration keys with defaults |
| `base/` | ConfigMap, PVCs, stats processor, retrieval server, Studio |
| `jobs/` | demo Jobs (`40-demo.sh`) |
| `components/` | `hostnetwork`, `lake`, `lake-minio`, `lake-jobs`, `ingress`, `modbus-sim`, `dwd-forward`, `monitoring`, `saas`, `event-bridge` |
| `overlays/local/` | rendered by `20-configure.sh` (not in Git) |
| `lib.sh` | shared shell helpers |
| `../docker/` | Dockerfiles, staging script, image entrypoint, deployment helper |

## Retrieval server on the episodes topic

By default the retrieval server serves a synthetic demo store. `20-configure.sh RETRIEVAL_SOURCE=kafscale` adds the
component `retrieval-kafscale`: the server reads `tsx.<domain>.episodes.v2` from KafScale into memory and follows the
topic, and `GET /v1/inventory` lists the real series with their scenario metadata. The workbench of the SaaS module
uses that in its "TS Inventory" dialog.

```bash
deploy/k3s/40-demo.sh windpark-sim                      # fills the episodes topic with the demo scenario
deploy/k3s/20-configure.sh RETRIEVAL_SOURCE=kafscale SAAS=true
deploy/k3s/30-deploy.sh --restart
```

Limits: at most 20000 episodes in memory (the inventory then says `truncated`), memory limit 2Gi, no
authentication. `LAKE=true` and `RETRIEVAL_SOURCE=kafscale` exclude each other: with the lake profile the server
reads Iceberg.

## Event bridge: twin event topics for the browser

`20-configure.sh EVENT_BRIDGE=true` adds the component `event-bridge`: Deployment and Service `opentsx-event-bridge`
(image `opentsx/tools`, `opentsx twin-live bridge`), NodePort 30312. It reads the topics `twin.<id>.<run>.events`
from the configured KafScale and serves them as server-sent events:

```
curl http://<node>:30312/v1/health
curl -N "http://<node>:30312/v1/topics/<topic>/events?from=earliest"
```

The Smart Topics Studio follows a topic through it: open the page with `?bridge=http://<node>:30312`, tab
"Ereignis-Topic", "Verbinden". Events get into the topics with `opentsx twin-live publish` (see
`opentsx-twin-live/README.md`). The bridge is read-only, serves only event topics of twins and has no authentication;
`EVENT_BRIDGE_ALLOW_ORIGIN` narrows the origin it answers browsers from.

## SaaS module: web workbench, accounts, stored flows

`20-configure.sh SAAS=true` adds the component `saas`:

| Workload | What | Reachable |
|----------|------|-----------|
| `opentsx-saas-frontend` | React frontend as a static build behind nginx; proxies `/api/` and `/retrieval/` | NodePort 30311 |
| `opentsx-saas-backend` | FastAPI: accounts, organizations, stored flows | cluster-internal, through `/api/` |
| `opentsx-saas-db` | PostgreSQL with the PVC `opentsx-saas-db` (1Gi) | cluster-internal |

`10-build.sh` then also builds `<repo>/saas-frontend:<tag>` and `<repo>/saas-backend:<tag>` (both need registry access
during the image build: npm and PyPI), and `11-import-images.sh` imports them. The cluster pulls `postgres` itself.

```bash
deploy/k3s/20-configure.sh SAAS=true
deploy/k3s/10-build.sh --skip-maven && deploy/k3s/11-import-images.sh && deploy/k3s/30-deploy.sh
# http://<node>:30311/workbench   (no account needed)
# http://<node>:30311/register    (account, to save flows; they appear on /dashboard)
opentsx-saas-backend/smoke-flows.sh http://<node>:30311      # register, login, store, read, change, delete
```

`20-configure.sh` generates the database password, the token key and the password of the first administrator
(`SAAS_ADMIN_EMAIL`) when they are empty and keeps them in `.env` and the Secret `opentsx-saas-secrets`:

```bash
kubectl -n opentsx get secret opentsx-saas-secrets -o jsonpath='{.data.SAAS_ADMIN_PASSWORD}' | base64 -d
```

Limits:

* Plain HTTP on a node port: logins and tokens travel unencrypted. Use it on a trusted network only. The same holds
  for `/retrieval/`, which reaches the retrieval server without authentication.
* The database password is fixed once the volume exists; change it in PostgreSQL first, then in `.env`.
* `70-backup.sh` does not cover the PVC `opentsx-saas-db`.
* Saving a flow stores its definition. Nothing in this repository executes `opentsx.dag.v1` yet.
