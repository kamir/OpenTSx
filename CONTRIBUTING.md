# Contributing to OpenTSx

Thank you for your interest in OpenTSx. Bug reports, documentation fixes, tests and code are welcome.
By taking part you agree to follow the [Code of Conduct](CODE_OF_CONDUCT.md). Security issues are not reported in
public issues; see [SECURITY.md](SECURITY.md).

## Before you start

* Open an issue for anything larger than a small fix, so the design can be discussed first.
* The tasks, with their plans and backlogs, are linked from [`EVOLUTION/README.md`](EVOLUTION/README.md). The data-model
  decisions are in [`EVOLUTION/TASK-011-datamodel-refresher-2026.md`](EVOLUTION/TASK-011-datamodel-refresher-2026.md).
  The normative data-model specification is
  [`docs/datamodel-2026/04-MODELL-V2-SPEC.md`](docs/datamodel-2026/04-MODELL-V2-SPEC.md).
* The v2 modules (`opentsx-model`, `-kafka-v2`, `-store-*`, `-windpark`, `-stats`, `-openmetadata`, `-retrieval`,
  `-twin`) are where new work goes. The 2.x modules (`opentsx-data`, `-core`, `-connectors`, `-lg`, `-flink-core`)
  are maintained, but new features go to v2. The bridge runs one way only (`opentsx-legacy-bridge`, 2.x to v2).

## Build and test

Requirements: JDK 17 or 21, Maven 3.9+, Python 3.10 to 3.13. Docker is optional (KafScale, MinIO).

```bash
mvn -q install -DskipTests                     # build all modules
xvfb-run --auto-servernum mvn -B -ntp verify   # unit and integration tests as CI runs them (KafScale tests are skipped)

docker compose -f docker-compose.kafscale.yml up -d
KAFSCALE_BOOTSTRAP=localhost:9092 xvfb-run --auto-servernum mvn -B -ntp verify   # including the KafScale tests

cd python-package
python -m pip install -e ".[viz]" pytest       # viz runs the notebook tests; ".[viz,iceberg]" adds the PyIceberg reader
python -m pytest -q

python3 tools/check_links.py                   # relative links in the maintained Markdown (from the repo root)
```

The Swing tests of `opentsx-core` need a display, so every Maven run that tests `opentsx-core` needs one, also
`-pl opentsx-flink-core -am`. On a machine without a display (a server, a container, CI) run Maven under
`xvfb-run --auto-servernum` as above; in a desktop session `mvn -B -ntp verify` is enough.

Tests that need an external service run only when its variable is set: `KAFSCALE_BOOTSTRAP` (KafScale/Kafka),
`OPENTSDB_URL` (real OpenTSDB instead of the embedded emulator), `OPENTSX_EXPORT_DIR` (Java to Iceberg to Python
fixture) and `OPENTSX_RETRIEVAL_URL` (Python client against a running retrieval server).

[`.mvn/maven.config`](.mvn/maven.config) makes Maven retry a download up to five times when the repository answers
HTTP 429, 500, 502, 503 or 504.

An operator is described in `systems/processors/<id>.yaml`. When you add or change an operator, update its description
and its example test, then regenerate the charts and the operator chapter as
[chapter 17](docs/user-manual/17-operators.md#run-the-examples-and-draw-the-charts) shows. `OperatorCatalogTest` fails
when a description names code that does not exist or when the chapter is out of date.

## Continuous integration

[`.github/workflows/ci.yml`](.github/workflows/ci.yml) runs on every pull request, on every push to `master` and when
started by hand (`workflow_dispatch`). A new run of the same branch or pull request cancels the one in progress.

| Job | What it does |
|-----|--------------|
| Java 17, Java 21 | `xvfb-run --auto-servernum mvn -B -ntp verify`; on Java 21 also `mvn install -DskipTests`, `deploy/docker/test-launchers.sh --dev` and `deploy/docker/test-launchers.sh --staged` over `deploy/docker/stage.sh --out` |
| Python 3.10 to 3.13 | `pip install -e ".[viz]" pytest` and `pytest -q` in `python-package`; on 3.10 also the `iceberg` extra; on 3.12 also the `yaml` extra, `tools/check_links.py`, `tools/check_tracked_config.py` and `pytest -q tools` |
| SaaS frontend | `npm ci`, `tsc --noEmit`, `npm run lint`, `vitest run` and `npm run build` in `opentsx-saas-frontend`; `nginx -t` on its `nginx.conf`, and a check that nginx answers an unreachable backend and a refused route as JSON |
| SaaS backend | `pip install -r requirements.txt -r requirements-dev.txt` and `pytest -q tests` in `opentsx-saas-backend` on Python 3.12 |
| k3s scripts and manifests | `bash -n` and `shellcheck -x -P SCRIPTDIR` on `deploy/k3s/*.sh`, `deploy/k3s/test/*.sh`, `deploy/docker/*.sh`, `bin/opentsx`, `bin/000_validate_environment.sh`, `bin/run_all_tests.sh`, `bin/000_launch_tsa_workbench.sh`, `bin/010_build.sh`, `bin/episode_*.sh`, `opentsx-saas-backend/local-setup.sh`, `opentsx-saas-backend/local-run.sh` and `tools/pair_xcorr_vectors.sh`; the shell tests in `deploy/k3s/test/`; `deploy/docker/test-launchers.sh` and `deploy/docker/test-launchers.sh --commands`; a check that `docker-compose.local.yml` resolves and that each of its images exists; renders the kustomize overlay and Jobs for four `deploy/k3s/20-configure.sh` configurations and the history archive, validates them with `kubeconform -strict` against the Kubernetes 1.31 schemas and loads every `opentsx/tools` container through `OpenTsxConf`; `deploy/k3s/60-probe.sh --dry-run` with and without `--local-kafscale` |
| KafScale conformance | builds the KafScale broker v1.6.0 from source (in-memory S3) and runs the tests of `opentsx-kafscale-conformance`, `opentsx-kafka-v2`, `opentsx-legacy-bridge`, `opentsx-windpark`, `opentsx-stats`, `opentsx-twin-live` and `opentsx-retrieval` against it; fails unless the three cases of `KafkaStreamingIntegrationTest` ran and passed; runs the `pfd-acceptance` profile of `opentsx-flink-core` against the broker |
| Java -> Iceberg -> Python | Java writes an Iceberg fixture that PyIceberg must read byte-identically; `deploy/docker/test-launchers.sh --classpath opentsx-retrieval`, then the Python client queries a Java retrieval server started on `bin/opentsx classpath opentsx-retrieval` |

Two more workflows run on their own triggers:

| Workflow | Runs on | What it does |
|----------|---------|--------------|
| [`.github/workflows/image.yml`](.github/workflows/image.yml) | a tag `v*`, a pull request that changes `deploy/docker/` or the workflow, a start by hand | builds the `opentsx-tools` image for `linux/amd64` and `linux/arm64`; on a tag it checks that the tag is `v<version>` of `pom.xml` and no prerelease, and pushes `ghcr.io/kamir/opentsx-tools:<version>` |
| [`.github/workflows/openmetadata-smoke.yml`](.github/workflows/openmetadata-smoke.yml) | daily at 03:17 UTC, a pull request that changes `opentsx-openmetadata/` or the workflow, a start by hand | starts OpenMetadata 2.0.5 and runs `OpenMetadataSmokeTest` against it |

Pull requests are squash-merged when every required check is green.

## Branches, commits and pull requests

* Branch from `master`. Name the branch `<type>/<name>` with the type `feat`, `fix`, `chore`, `docs`, `test` or
  `refactor`, for example `feat/publish-from-day`.
* Keep a pull request to one topic. Describe what changed, why, and how you tested it.
* Write the commit subject as `<type>(<scope>): <what changed>`, for example `fix(market): skip empty days`, then a
  few lines of why.
* Add or update tests with every code change. Changes to the Avro schemas or the wire format need shared test vectors
  for Java and Python (see `opentsx-model` and `python-package/tests/test_model_v2.py`).
* Update the documentation that your change affects (module README, `docs/user-manual/`) and add an entry to
  [CHANGELOG.md](CHANGELOG.md) under "Unreleased".
* Everything you write is in English. Some design documents in `docs/datamodel-2026/` and `EVOLUTION/` are in
  German; a German document you change is translated to English in the same pull request.

## Configuration and data

* Never commit real hosts, credentials or tokens. Personal and real-cluster configuration belongs in
  `config/private/`, which is git-ignored. The Kafka client profile of the 2.x tools is `config/opentsx-local.props`
  (git-ignored), created from the localhost template `config/opentsx-local.props.example`.
* Real measurement data (plant, meter, house or boat data) stays local and out of the repository. Commit only
  synthetic or simulated data, and mark it as such.
* `deploy/k3s/.env` and `deploy/k3s/overlays/local/` are generated and git-ignored.

## License

OpenTSx is licensed under the [Apache License 2.0](LICENSE). By contributing, you agree that your contributions are
licensed under the same license (section 5 of the license).
