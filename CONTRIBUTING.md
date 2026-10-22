# Contributing to OpenTSx

Thank you for your interest in OpenTSx. Bug reports, documentation fixes, tests and code are welcome.
By taking part you agree to follow the [Code of Conduct](CODE_OF_CONDUCT.md). Security issues are not reported in
public issues; see [SECURITY.md](SECURITY.md).

## Before you start

* Open an issue for anything larger than a small fix, so the design can be discussed first.
* The plan, decisions and backlog are in [`EVOLUTION/TASK-011-datamodel-refresher-2026.md`](EVOLUTION/TASK-011-datamodel-refresher-2026.md)
  (German). The normative data-model specification is
  [`docs/datamodel-2026/04-MODELL-V2-SPEC.md`](docs/datamodel-2026/04-MODELL-V2-SPEC.md).
* The v2 modules (`opentsx-model`, `-kafka-v2`, `-store-*`, `-windpark`, `-stats`, `-openmetadata`, `-retrieval`,
  `-twin`) are where new work goes. The 2.x modules (`opentsx-data`, `-core`, `-connectors`, `-lg`, `-flink-core`)
  are maintained, but new features go to v2. The bridge runs one way only (`opentsx-legacy-bridge`, 2.x to v2).

## Build and test

Requirements: JDK 17 or 21, Maven 3.9+, Python 3.10 to 3.13. Docker is optional (KafScale, MinIO).

```bash
mvn -q install -DskipTests                     # build all modules
mvn verify                                     # unit and integration tests (KafScale tests are skipped)

docker compose -f docker-compose.kafscale.yml up -d
KAFSCALE_BOOTSTRAP=localhost:9092 mvn verify   # including the KafScale tests

cd python-package
python -m pip install -e . pytest              # add ".[iceberg]" for the PyIceberg reader
python -m pytest -q

python3 tools/check_links.py                   # relative links in the maintained Markdown (from the repo root)
```

Tests that need an external service run only when its variable is set: `KAFSCALE_BOOTSTRAP` (KafScale/Kafka),
`OPENTSDB_URL` (real OpenTSDB instead of the embedded emulator), `OPENTSX_EXPORT_DIR` (Java to Iceberg to Python
fixture) and `OPENTSX_RETRIEVAL_URL` (Python client against a running retrieval server).

[`.mvn/maven.config`](.mvn/maven.config) makes Maven retry a download up to five times when the repository answers
HTTP 429, 500, 502, 503 or 504.

## Continuous integration

[`.github/workflows/ci.yml`](.github/workflows/ci.yml) runs on every pull request and on pushes to `master` and
`opentsx/**`:

| Job | What it does |
|-----|--------------|
| Java 17, Java 21 | `mvn -B -ntp verify` |
| Python 3.10 to 3.13 | `pip install -e . pytest`, `pytest -q`; on 3.12 also `tools/check_links.py` |
| k3s scripts and manifests | `bash -n` and `shellcheck` on `deploy/k3s/*.sh` and `deploy/docker/*.sh`; renders the kustomize overlays with `deploy/k3s/20-configure.sh` |
| KafScale conformance | builds the KafScale broker from source (in-memory S3) and runs the conformance suite plus the `opentsx-kafka-v2`, `opentsx-windpark` and `opentsx-stats` tests against it |
| Java -> Iceberg -> Python | Java writes an Iceberg fixture that PyIceberg must read byte-identically; the Python client queries a Java retrieval server |

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
* Everything you write is in English. The design documents in `docs/datamodel-2026/` and `EVOLUTION/` are still in
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
