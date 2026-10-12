# deploy/k3s: OpenTSx on k3s against an existing KafScale cluster

The guide is [docs/devops/](../../docs/devops/README.md) (setup plan, sizing plan, demos, troubleshooting).

```bash
deploy/k3s/00-prereqs.sh                                          # check tools, cluster, KafScale
deploy/k3s/10-build.sh                                            # mvn install + images opentsx/tools, opentsx/studio
deploy/k3s/11-import-images.sh                                    # into k3s' containerd
deploy/k3s/20-configure.sh KAFSCALE_BOOTSTRAP=192.168.1.50:9092   # writes .env + overlays/local (git-ignored)
deploy/k3s/30-deploy.sh                                           # kubectl apply -k overlays/local
deploy/k3s/40-demo.sh all                                         # every example as a Job, with output
deploy/k3s/50-status.sh                                           # state
deploy/k3s/90-teardown.sh                                         # remove (KafScale topics stay)
```

| Path | Content |
|------|---------|
| `env.example` | all configuration keys with defaults |
| `base/` | ConfigMap, PVCs, stats processor, retrieval server, Studio |
| `jobs/` | demo Jobs (`40-demo.sh`) |
| `components/` | `hostnetwork`, `lake`, `lake-minio`, `lake-jobs`, `ingress` |
| `overlays/local/` | rendered by `20-configure.sh` (not in Git) |
| `lib.sh` | shared shell helpers |
| `../docker/` | Dockerfiles, staging script, image entrypoint, deployment helper |
