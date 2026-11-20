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

On a broker without the topics of the domain, `30-deploy.sh` stops with
`FAIL  deployment.apps/opentsx-stats: not ready after 180s; ...` (exit code 1): run `40-demo.sh kafscale-check`, which
creates them, then `30-deploy.sh --restart`.

| Path | Content |
|------|---------|
| `env.example` | all configuration keys with defaults |
| `base/` | ConfigMap, PVCs, stats processor, retrieval server, Studio |
| `jobs/` | demo Jobs (`40-demo.sh`) |
| `components/` | `hostnetwork`, `lake`, `lake-minio`, `lake-jobs`, `ingress`, `modbus-sim`, `dwd-forward`, `monitoring`, `saas`, `event-bridge` |
| `overlays/local/` | rendered by `20-configure.sh` (not in Git) |
| `lib.sh` | shared shell helpers |
| `test/` | shell tests of the scripts, run without a cluster |
| `../docker/` | Dockerfiles, staging script, image entrypoint, deployment helper |

## Read the exit codes

Every script exits with 0 when it did its work, 1 when a check, a step or a request failed, and 2 on a usage error;
`--help` lists the cases of each. Usage errors are an unknown option, an option without its value, an argument the
script does not take, an unknown demo of `40-demo.sh` or extra arguments for one of its demos that take none, a
`--builder` of `10-build.sh` or a method of `11-import-images.sh` (`--method` or `IMAGE_METHOD`) that is not in the
list, a `--timeout` of `30-deploy.sh` that is not a duration, no backup directory or an unknown `--only` part of
`71-restore.sh`, and for `20-configure.sh` an unknown key, an argument that is not `KEY=VALUE` or an option, or a
secret key as an argument. The tests in `deploy/k3s/test/` run the scripts without a cluster, for example
`bash deploy/k3s/test/test-40-demo.sh`.

```text
error: nosuch: unknown demo; expected one of kafscale-check windpark-sim stats metadata metadata-publish twin house boat studio-export retrieval studio dwd dwd-online dwd-forward modbus modbus-poll all
```

Every script reads `deploy/k3s/.env` and refuses a line that is not empty, a comment or `KEY=VALUE` with a key of
`env.example` when it loads the configuration, with exit 2. `.env` stays as it is:

```text
error: deploy/k3s/.env:8: unknown key SAAS_ADMIN_PASWORD; use a key of deploy/k3s/env.example
```

`20-configure.sh` refuses a value with a line break or another control character before it writes, because each
value ends up in a YAML or env file line. It also checks the form of the values that become names, image references,
URLs, durations, paths or the cron schedule in the rendered files. A refused value exits with 1:

```text
error: OPENTSX_STATS_WINDOWS: item "PT5" is not a duration; expected ISO-8601 such as PT10M or a whole number with the unit ms, s, m, min, h or d such as 10m
```

## Set secrets

`20-configure.sh` refuses a secret key as an argument, because the shell history and the process list keep
arguments. The secret keys are `S3_ACCESS_KEY`, `S3_SECRET_KEY`, `OPENMETADATA_JWT`, `RETRIEVAL_DAG_RUN_TOKEN`,
`SAAS_DB_PASSWORD`, `SAAS_SECRET_KEY` and `SAAS_ADMIN_PASSWORD`. Export them before you run the script, or write them
into `deploy/k3s/.env` (mode 600). The environment wins over `.env`, and the script writes the effective values into
`.env`.

```bash
read -rs OPENMETADATA_JWT && export OPENMETADATA_JWT
deploy/k3s/20-configure.sh OM_URL=http://<host>:8585/api
```

`<host>` is your OpenMetadata server. A secret key as an argument, for example `OPENMETADATA_JWT=<token>`, exits
with 2 and writes nothing:

```text
error: OPENMETADATA_JWT: secret keys are not accepted on the command line; export OPENMETADATA_JWT or put it in deploy/k3s/.env (mode 600)
```

With `SAAS=true` the script also refuses the published example values that the SaaS backend refuses at start. It
reads them from `PUBLISHED_EXAMPLES` in `opentsx-saas-backend/app/core/config.py` and exits with 1:

```text
error: SAAS_ADMIN_PASSWORD: published example value; set a random password of at least 12 characters
```

With `LAKE=true` and `LAKE_MINIO=true` the bucket Job hands the S3 keys to `mc` in the URL `MC_HOST_lake`, so the
keys may hold only letters, digits, `.`, `_`, `~` and `-`. The generated keys do.

`20-configure.sh --print` shows the rendered manifests without the Secrets. Read one value with
`kubectl -n opentsx get secret opentsx-secrets -o jsonpath='{.data.OPENMETADATA_JWT}' | base64 -d`.

## Pass settings to a command

The containers pass the values of the ConfigMap `opentsx-config` to their command as variables of the
configuration registry, not as options. `opentsx <command> --help` lists the variables a command reads. The keys
that `20-configure.sh` writes keep their names from `deploy/k3s/.env`, and the container maps each onto the
variable of its registry key:

| ConfigMap key | Variable in the container |
|---------------|---------------------------|
| `KAFSCALE_BOOTSTRAP` | `KAFSCALE_BOOTSTRAP` (legacy name of `OPENTSX_KAFSCALE_BOOTSTRAP`) |
| `OPENTSX_DOMAIN` | `OPENTSX_DOMAIN` |
| `OPENTSX_PARTITIONS` | `OPENTSX_KAFSCALE_PARTITIONS` |
| `OM_URL` | `OPENTSX_OPENMETADATA_URL` |
| `ICEBERG_PROPS` | `OPENTSX_ICEBERG_PROPERTIES` |
| `RETRIEVAL_DAG_RUNS` | `OPENTSX_RETRIEVAL_DAG_RUNS` |
| `MODBUS_HOST`, `MODBUS_PORT`, `MODBUS_MAP` | `OPENTSX_MODBUS_HOST`, `OPENTSX_MODBUS_PORT`, `OPENTSX_MODBUS_MAP` |
| `DWD_FIXTURE` | `OPENTSX_DWD_OFFLINE` (Job `dwd`; CronJob `dwd-forward` with `DWD_MODE=offline`) |
| `DWD_STATIONS` | `OPENTSX_DWD_STATION`, the first station (`40-demo.sh dwd-online`); `dwd-forward` runs `dwd mosmix --station <id>` for each station in turn |

`base/kustomization.yaml` holds the defaults of `OPENTSX_WINDPARK_SCENARIO`, `OPENTSX_STATS_WINDOWS`,
`OPENTSX_STATS_ALLOWED_LATENESS` and `OPENTSX_ICEBERG_NAMESPACE` under the names the commands read. `20-configure.sh`
sets them like any other key:

```bash
deploy/k3s/20-configure.sh OPENTSX_STATS_WINDOWS=PT5M,PT1H
deploy/k3s/30-deploy.sh --restart
```

A Job sets its overridable defaults as variables:

| Job | Variables |
|-----|-----------|
| `kafscale-check` | `OPENTSX_KAFSCALE_PROVISION=true` |
| `windpark-sim` | `OPENTSX_WINDPARK_SINKS=kafscale` (the component `lake-jobs` adds `--sinks kafscale,iceberg`) |
| `metadata-publish` | `OPENTSX_OPENMETADATA_PROFILES=always` |
| `dwd` | `OPENTSX_DWD_OFFLINE`, `OPENTSX_DWD_OUT=/data/dwd/demo-mosmix.csv`, `OPENTSX_DWD_DAILY=/data/dwd/demo-daily.csv`; `40-demo.sh dwd-online` removes `OPENTSX_DWD_OFFLINE` and sets `OPENTSX_DWD_STATION` |
| `modbus-poll` | `OPENTSX_MODBUS_COUNT=30`, `OPENTSX_MODBUS_INTERVAL=1s`, `OPENTSX_MODBUS_OUT=episodes`, `OPENTSX_MODBUS_EPISODE_LENGTH=PT10S` |

An option after `40-demo.sh <demo> --` wins over a variable, for example
`deploy/k3s/40-demo.sh modbus -- --interval PT2S`. In the `dwd` Job the extra arguments go to `dwd mosmix` only.
`stats`, `retrieval`, `studio`, `dwd-forward` and `all` take no extra arguments and exit with 2 when given some. An
option that the Job command itself names fails with `set twice on the command line`; these are `--index`,
`--ground-truth` and with `lake-jobs` `--sinks` (`windpark-sim`), `--dry-run` (`metadata`) and `--file`
(`modbus-poll`, which names the file by its start time).

CI renders the overlays and loads every `opentsx/tools` container through the configuration loader with its
variables, its arguments and `OPENTSX_EXTRA_ARGS`. It fails when a container sets a variable that its command does
not read, passes a variable as the value of an option, or gives an `opentsx` command in its shell script an argument
from the shell that is not the value of an option.

## Size the twin Jobs

The Job `boat` (limit 1536Mi, heap 1152 MiB) has the memory for `--data` over a year of 15-minute steps (35136
steps) and `--calibrate`: measured peak RSS 1312 MB with one row per step and 1301 MB with 527040 rows, the most rows
`boat` reads from one file. The Job reads your `--data` file from the Secret `opentsx-private`, mounted at `/private`,
and Kubernetes refuses a Secret larger than 1 MiB
([Secret size limit](https://kubernetes.io/docs/concepts/configuration/secret/#restriction-data-size)). A year at one
row per 15-minute step (35136 rows of 10 columns, 2.3 MB) does not fit into it; run
`bin/opentsx boat --data <file> --calibrate` on a host for such a file, where `<file>` is your CSV file. The Job
`house` (limit 4Gi, heap 3 GiB) holds `--calibrate` with bills over 10 years (87673 steps); measured peak RSS
3260 MB.

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

### Vector Smart Topics

`VECTOR_PROCESSOR=true` adds the component `vector-processor`: one pod `opentsx-vectors` that keeps the vector
topics of the domain (`tsx.<domain>.smart.<name>.vectors.v1`) up to date, with its checkpoints on the PVC
`opentsx-vectors-state`. It takes its DAGs from the control topic `tsx.<domain>.dags.v1`.

With `RETRIEVAL_DAG_RUNS=true` the retrieval server writes to that control topic for callers with the run token
(`POST /v1/dags/deploy`, `/stop`); the workbench uses that for "Deploy" and "Stop" in the tab "Smart Topic".
`GET /v1/dags` shows what is deployed and what the processor reports.

```bash
deploy/k3s/20-configure.sh RETRIEVAL_SOURCE=kafscale RETRIEVAL_DAG_RUNS=true VECTOR_PROCESSOR=true SAAS=true
deploy/k3s/30-deploy.sh --restart
kubectl -n opentsx logs deploy/opentsx-vectors          # deployed, refused, failed and why
```

Keep `opentsx-vectors` at one replica (as shipped): two processors would write every measuring point twice.

Who may write:

* `20-configure.sh` generates `RETRIEVAL_DAG_RUN_TOKEN` (kept in `.env` and the Secret `opentsx-secrets`). The
  retrieval server deploys and stops DAGs only for callers that send it.
* The SaaS backend knows the token and forwards for signed-in accounts (`POST /api/v1/dags/deploy`, `/stop`).
  `SAAS_DAG_RUN_ROLE=superuser` (default) allows administrators only. `user` allows every account; anyone can
  register, so that opens writing to everyone who reaches the page.
* The frontend image answers `/retrieval/v1/dags/` with 403, so the write path of the retrieval server is not
  reachable through the node port. Reading through `/retrieval/` needs no account.
* Inside the cluster every pod can reach the retrieval Service; the token is what keeps them from deploying. Whoever
  reaches the broker itself can write the control topic directly; KafScale has no access control here. There is no
  NetworkPolicy.

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

The Smart Topics Studio follows a topic through it: open the page with `?bridge=http://<node>:30312`, then use
the event topic tab (`#view-topic`) and the connect button (`#tp-connect`). Events get into the topics with
`opentsx twin-live publish` (see `opentsx-twin-live/README.md`). The bridge is read-only, serves only event topics
of twins and has no authentication; `EVENT_BRIDGE_ALLOW_ORIGIN` narrows the origin it answers browsers from.

## SaaS module: web workbench, accounts, stored flows

`20-configure.sh SAAS=true` adds the component `saas`:

| Workload | What | Reachable |
|----------|------|-----------|
| `opentsx-saas-frontend` | React frontend as a static build behind nginx; proxies `/api/` and `/retrieval/` | NodePort 30311 |
| `opentsx-saas-backend` | FastAPI: accounts, stored flows, working copies of systems; forwards DAG deployments to the retrieval server | cluster-internal, through `/api/` |
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

**Test operation.** `SAAS_TEST_LOGIN=true` lets the name `admin` with the password `admin` sign in as the first
administrator; the login page says so. The generated password keeps working and is not changed. Everyone who reaches
the page is an administrator then and can deploy and stop DAGs: use it on a lab network only, and switch it off
again with `SAAS_TEST_LOGIN=false`.

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
* Saving a flow stores its definition and does not run it. A flow runs when its DAG is deployed (see
  [Vector Smart Topics](#vector-smart-topics)).
