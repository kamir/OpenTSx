# 12. Operations

Start a local stack, configure the tools, run the tests and secure a deployment of OpenTSx 3.0.1.

## 12.1 Start a local stack

| File | Starts | Use |
|------|--------|-----|
| `docker-compose.kafscale.yml` | etcd, MinIO (S3, :9000/:9001), KafScale broker v1.6.0 (:9092) | **primary development stack** (v2) |
| `docker-compose.local.yml` | Postgres, Confluent Platform (Kafka, Schema Registry), HBase + OpenTSDB; profile `full` adds the SaaS backend and frontend ([chapter 14](14-saas-service-layer.md) says what works in it) | OpenTSDB store tests, 2.x tools, SaaS stack |

Both stacks bind port 9092, so do not run them at the same time.

`docker-compose.local.yml` needs `POSTGRES_PASSWORD`, `SECRET_KEY` and `FIRST_SUPERUSER_PASSWORD` in the environment
or in `.env` next to the file. Without one of them, docker compose stops and names it, for example
`required variable SECRET_KEY is missing a value`.

The [DevOps guide](../devops/README.md) describes how to run the tools as services on Kubernetes (k3s) against an
existing KafScale cluster: images, manifests, setup and sizing plan, demos. The scripts are in
[`deploy/k3s/`](../../deploy/k3s/README.md). The [operations concept](../devops/operations.md) covers the local probe
(`deploy/k3s/60-probe.sh`), backup and restore (`deploy/k3s/70-backup.sh`, `deploy/k3s/71-restore.sh`) and the
runbooks.

## 12.2 Configure the tools

Every setting of a v2 tool is a key of the registry `opentsx.config.v1` ([chapter 18](18-configuration.md); every key
with its flag and variables in [appendix D](appendix-d-configuration.md)). `bin/opentsx config list`, `config show`,
`config get` and `version` read the registry, and so does a command whose `bin/opentsx <command> --help` prints a
`keys:` section. The highest source that sets a key wins. The order matters:

1. code: `set` on `OpenTsxConf`,
2. the command line: `--conf <key>=<value>`, `-c <key>=<value>` or the flag of the key,
3. the environment: `OPENTSX_<NAME>`, then the legacy names of the key,
4. `--properties-file <path>`,
5. the defaults file `opentsx-defaults.conf` or `opentsx-defaults.yaml` in `$OPENTSX_CONF_DIR`,
6. the registry default.

`<NAME>` is the key without `opentsx.`, in upper case, with `_` at every dot and before every upper-case letter:
`opentsx.kafscale.bootstrap` is `OPENTSX_KAFSCALE_BOOTSTRAP`, and its legacy name is `KAFSCALE_BOOTSTRAP`.
`bin/opentsx config list` prints every name in the column `ENV`. `bin/opentsx config show` prints the effective value
of every key with its source.

Any other command takes its configuration from its own command-line options
([appendix A](appendix-a-cli.md)) and from these environment variables. Pass a token only in the environment, never
as a command-line option: a value there shows in `ps`, the shell history and the pod spec.

| Variable | Read by |
|----------|---------|
| `KAFSCALE_BOOTSTRAP` | the KafScale tests ([12.4](#124-run-the-tests)); for the commands that load the configuration it is a legacy name of `opentsx.kafscale.bootstrap` ([appendix D](appendix-d-configuration.md)) |
| `ENTSOE_TOKEN` | `market`, for the source `entsoe` |
| `NTP_CLIENT_ID`, `NTP_CLIENT_SECRET` | `market`, for the source `netztransparenz` |

| Component | Settings |
|-----------|----------|
| KafScale/Kafka clients | `KafScaleClients.producerProps/consumerProps(bootstrap)` (idempotence off, `max.in.flight.requests.per.connection=1`, zstd, no auto-commit) |
| Iceberg | catalog properties as Iceberg's `CatalogUtil` reads them (`type`, `uri`, `warehouse`, ...); catalog name `opentsx` unless `name` is set. `type=rest` and `type=hadoop` work. `type=jdbc` needs a JDBC driver: `bin/opentsx windpark-sim`, `retrieval-server`, `market` and the container image have the SQLite driver. `type=glue` needs `iceberg-aws`, which only `deploy/docker/stage.sh --with-s3` adds. `type=hive` is not available. See [chapter 5](05-stores.md#52-choose-a-store). |
| OpenMetadata | `--om <url>/api`, token via `OPENMETADATA_JWT` |
| Statistics processor | `--windows`, `--lateness`, `--alpha`, `--checkpoint`; `stats-flink` takes `--checkpoint-every` instead of `--checkpoint` |
| Wind farm | scenario YAML, catalog YAML (`--catalog`) |

The 2.x tools in `opentsx-core` and `opentsx-lg` use `ConfigManager`, which resolves a key in this order:

1. JVM system property,
2. environment variable (literal, or mapped to `OPENTSX_…`),
3. profile overrides: the file in `OPENTSX_CONFIG_FILE`, or `config/opentsx-<profile>.properties` for
   `OPENTSX_CONFIG_PROFILE=<profile>`,
4. `config/opentsx-default.properties`,
5. the resource `opentsx-default.properties` of `opentsx-core`.

When `OPENTSX_CONFIG_FILE` or `opentsx.config.file` names a file, that file must exist and load. Otherwise, when
`OPENTSX_CONFIG_PROFILE` or `opentsx.config.profile` names a profile, `config/opentsx-<profile>.properties` must exist
and load. The system property wins over the variable. An empty or blank value that wins stops the tool with
`<name>: empty value; set a value or leave the key unset`, where `<name>` is the key or variable that holds it. A
missing file stops it with `<path>: no such file; give an existing file`. A file that cannot be read or has a
malformed `\u` escape stops it with a message that starts with `<path>:`. An integer key whose value is not a whole
number stops it with `<key>: "<value>" is not an int; expected a whole number from -2147483648 to 2147483647`.

`opentsx-connectors` does not use `ConfigManager`. It reads `OPENTSX_TOPIC_MAP_FILE_NAME` and
`OPENTSX_PRIMARY_CLUSTER_CLIENT_CFG_FILE_NAME` from the environment only, so a JVM system property or
`config/opentsx-default.properties` does not set them.

[`opentsx.properties.example`](../../opentsx.properties.example) lists the keys the 2.x tools read from
`./opentsx.properties` and `config/opentsx-default.properties`; the registry does not read these files.
`opentsx.log.level` in that file belongs to the 2.x `ProductionConfig`, not to the registry. The variable
`OPENTSX_LOG_LEVEL` belongs to the registry key `opentsx.log.level` and takes `error`, `warn`, `info`, `debug` or
`trace`. A 2.x level such as `INFO` stops every command that reads the registry with
`OPENTSX_LOG_LEVEL: "INFO" is not allowed; expected one of error, warn, info, debug, trace`. The 2.x Kafka client
profile is `config/opentsx-local.props` (git-ignored; set `OPENTSX_PRIMARY_CLUSTER_CLIENT_CFG_FILE_NAME` to use
another file). Without it, the template [`config/opentsx-local.props.example`](../../config/opentsx-local.props.example)
(localhost, KafScale-compatible) is used. Profiles with real hosts or credentials belong in `config/private/`
(git-ignored).

## 12.3 Restart after a failure

| Component | State | On restart |
|-----------|-------|-----------|
| `BucketWriter` | none (an unsealed bucket stays OPEN) | write the bucket again; `BucketCatalog` takes the SEALED manifest, and readers drop repeated episodes by `episodeId` |
| episode index | `FileEpisodeIndex` / `IcebergEpisodeIndex` | `EpisodeIndexer.rebuild` recreates it from the log |
| statistics processor | checkpoint file (positions + engine state) | resumes; outputs at least once. `SeriesStatsLog.readLatest` keeps one record per series and window ([chapter 7](07-stats-quality.md#72-run-the-processor)) |
| `EpisodeLogSync` | positions returned to the caller | check `undecodable` and `lostToRetention`, then persist them; writes are idempotent ([chapter 5](05-stores.md#53-copy-episodes-from-kafscale-to-iceberg)) |

## 12.4 Run the tests

```bash
mvn verify                                   # unit and integration tests (KafScale tests skipped)
KAFSCALE_BOOTSTRAP=localhost:9092 mvn verify # including KafScale tests
cd python-package && pytest                  # Python
```

GitHub Actions runs these jobs. The names are the check names on a pull request.

The workflow `CI` (`.github/workflows/ci.yml`) runs on every pull request and on every push to `master`:

| Job | What it runs |
|-----|--------------|
| "Java 17", "Java 21" | `mvn -B -ntp verify` under `xvfb-run` |
| "Python 3.10" to "Python 3.13" | `python -m pytest -q` in `python-package/`; on 3.12 also the link check `tools/check_links.py` |
| "SaaS frontend" | `npx tsc --noEmit`, `npm run lint`, `npx vitest run`, `npm run build` |
| "SaaS backend" | `python -m pytest -q tests` |
| "k3s scripts and manifests" | `shellcheck` of `deploy/k3s/*.sh` and `deploy/docker/*.sh`; the kustomize overlays rendered and checked with `kubeconform`; the plan of `deploy/k3s/60-probe.sh --dry-run` |
| "KafScale conformance" | a KafScale broker v1.6.0 built from source; against it the tests of `opentsx-kafscale-conformance`, `opentsx-kafka-v2`, `opentsx-legacy-bridge`, `opentsx-windpark`, `opentsx-stats`, `opentsx-twin-live` and `opentsx-retrieval`; the three streaming acceptance cases of `KafkaStreamingIntegrationTest` must run and pass; the PFD acceptance through Kafka and Flink (`-Ppfd-acceptance`) |
| "Java -> Iceberg -> Python" | Java writes Iceberg and PyIceberg must read identical bytes; the Python client queries the Java retrieval server |

The workflow `OpenMetadata smoke test` (job "OpenMetadata 2.0.5") publishes the simulated wind farm to OpenMetadata
2.0.5 and reads it back. It runs daily at 03:17 UTC, on demand, and on pull requests that change
`opentsx-openmetadata/`.

The workflow `Image` (job "opentsx-tools") builds the image `opentsx-tools` for `linux/amd64` and `linux/arm64`. On a
tag `v<version>` it pushes `ghcr.io/kamir/opentsx-tools:<version>`. The tag must equal the version in `pom.xml` and
must not be a prerelease.

## 12.5 Secure a deployment

* Never commit credentials. `config/private/` is in `.gitignore`.
* Treat any key that was ever committed as compromised and rotate it.
* Use bot tokens for OpenMetadata.
* The read routes of the retrieval server have no authentication. The server listens on every interface;
  `--bind <address>` (`opentsx.retrieval.bind`) chooses one. Restrict the port with a gateway or a firewall.
* With `--kafscale` and `--dag-runs true`, the retrieval server writes to KafScale on `POST /v1/dags/deploy`,
  `POST /v1/dags/stop` and `POST /v1/systems/<id>/generate`. It needs a run token in the environment variable
  `OPENTSX_DAG_RUN_TOKEN` or `OPENTSX_RETRIEVAL_DAG_RUN_TOKEN`; the command line refuses `--dag-run-token`. Callers
  send the token in the header `X-OpenTSx-Run-Token`; a call without it gets HTTP 401.
* Without a run token the server does not start with `--dag-runs true` (exit code 2). `--open-dag-runs` accepts runs
  without a token for a local run; the server then listens on 127.0.0.1 only and logs
  `dag deployments: OPEN on 127.0.0.1:<port>, any process that reaches this address can deploy and stop DAGs (opentsx.retrieval.openDagRuns); set OPENTSX_DAG_RUN_TOKEN to require a token`.

## 12.6 Fix common problems

| Symptom | Cause / fix |
|---------|-------------|
| enforcer error `Detected JDK <path> is version <version> which is not in the allowed range [17,).` | use JDK 17 or newer (`JAVA_HOME`); CI tests 17 and 21 |
| KafScale tests skipped | set `KAFSCALE_BOOTSTRAP=localhost:9092` |
| consumer hangs or rejoins on KafScale | do not use consumer groups. OpenTSx tools use manual assignment; do the same in your own code |
| `offsetsForTimes` returns nothing | not supported by KafScale. Use the episode index or `ReplayRequest.range` |
| your own consumer throws `IllegalStateException: Unexpected error code 7 while fetching ...` | KafScale refuses fetches for about a minute after a slow object-store write. Pause and poll again, as `Patience` does ([chapter 4](04-kafscale-storage.md#41-check-what-kafscale-supports)) |
| duplicate episodes after a retry | expected without an idempotent producer. Stores with the `EPISODES` capability, the episode index and the statistics processor deduplicate by `episodeId`; the OpenTSDB store does not ([chapter 3](03-data-model.md#33-episodes)) |
| PyIceberg warns "v0 schema" | the catalog was created by the Java JDBC catalog. The warning is harmless |
| `bin/opentsx`: "module … is not built" | run `mvn -q install -DskipTests` first |
