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
| `components/` | `hostnetwork`, `lake`, `lake-minio`, `lake-jobs`, `ingress`, `modbus-sim`, `dwd-forward`, `monitoring`, `saas-frontend` |
| `overlays/local/` | rendered by `20-configure.sh` (not in Git) |
| `lib.sh` | shared shell helpers |
| `../docker/` | Dockerfiles, staging script, image entrypoint, deployment helper |

## SaaS frontend with the web workbench

`20-configure.sh SAAS_FRONTEND=true` adds the component `saas-frontend`: the React frontend as a static build behind
nginx, Deployment and NodePort Service `opentsx-saas-frontend` on node port 30311. `10-build.sh` then also builds
`<repo>/saas-frontend:<tag>` (needs npm registry access during the image build) and `11-import-images.sh` imports it.

```bash
deploy/k3s/20-configure.sh SAAS_FRONTEND=true
deploy/k3s/10-build.sh --skip-maven && deploy/k3s/11-import-images.sh && deploy/k3s/30-deploy.sh
# http://<node>:30311/workbench
```

The workbench computes in the browser. Its "Load episodes" reaches the retrieval server through `/retrieval/`, which
has no authentication: use this on a trusted network only. The SaaS backend is not deployed by this component, so
`/login` and `/dashboard` have no API behind them.
