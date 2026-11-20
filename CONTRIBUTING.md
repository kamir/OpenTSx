# Contributing to OpenTSx

Thank you for your interest in OpenTSx. OpenTSx is maintainer-led: issues are welcome, and pull requests are reviewed
after the change was agreed in an issue ([GOVERNANCE.md](GOVERNANCE.md)). By taking part you agree to follow the [Code of Conduct](CODE_OF_CONDUCT.md). Security issues are not reported in
public issues; see [SECURITY.md](SECURITY.md).

## Before you start

* Open an issue first and wait for agreement before you write code. A small fix (a typo, a broken link, a one-line
  bug fix with a test) may come without an issue.
* The normative data-model specification is
  [`docs/datamodel-2026/04-MODELL-V2-SPEC.md`](docs/datamodel-2026/04-MODELL-V2-SPEC.md).
* The v2 modules (`opentsx-model`, `-kafka-v2`, `-store-*`, `-windpark`, `-stats`, `-openmetadata`, `-retrieval`,
  `-twin`) are where new work goes. The 2.x modules (`opentsx-data`, `-core`, `-connectors`, `-lg`, `-flink-core`)
  are maintained, but new features go to v2. The bridge runs one way only (`opentsx-legacy-bridge`, 2.x to v2).

## Build and test

Requirements: JDK 17 or 21, Maven 3.9+, Python 3.10 to 3.13. Docker is optional (KafScale, MinIO).
Install the forecasting and Kafka extras for the numerical and Smart Topic tests.
Add `iceberg` to the extras when you need the PyIceberg reader.

```bash
mvn -q install -DskipTests                     # build all modules
mvn verify                                     # unit and integration tests (KafScale tests are skipped)

docker compose -f docker-compose.kafscale.yml up -d
KAFSCALE_BOOTSTRAP=localhost:9092 mvn verify   # including the KafScale tests

cd python-package
python -m pip install -e ".[forecasting,kafka]" pytest
python -m pytest -q

cd ..
python -m unittest discover -s deploy/local-cp -p 'test_workbench*.py' -v
python3 tools/check_links.py                   # relative links in the maintained Markdown (from the repo root)
```

Tests that need an external service run only when its variable is set: `KAFSCALE_BOOTSTRAP` (KafScale/Kafka),
`OPENTSDB_URL` (real OpenTSDB instead of the embedded emulator), `OPENTSX_EXPORT_DIR` (Java to Iceberg to Python
fixture) and `OPENTSX_RETRIEVAL_URL` (Python client against a running retrieval server).

[`.mvn/maven.config`](.mvn/maven.config) makes Maven retry a download up to five times when the repository answers
HTTP 429, 500, 502, 503 or 504. With Maven 3.9.8 and its bundled Resolver 1.9.20, the intervals increase linearly:
3, 6, 9, 12 and 15 seconds. `Retry-After` takes precedence up to five minutes; longer values stop retries.
HTTP 401, 403 and 404 are not retried. Exhausted retries leave the build failed; Maven goals are not rerun.

You need JDK 17 or 21, Maven 3.9.8 and Python 3 to check this policy. From the repository root, run:

```bash
python3 tools/check_maven_downloads.py
```

The check requires the active Maven distribution and tests its native HTTP transport retry strategy without
network requests or sleeps. A different Maven version, missing bundled libraries or a failed policy check returns
an error.

Maven prints execution exception details and the selected Resolver transport.
The check also runs a missing-plugin fixture with an isolated offline
repository and requires a failed exit with its exception and offline reason.
Resolver 1.9.20 discards the original HTTP exception when classifying a 404 as
a missing artifact. A statusless missing-artifact message alone does not
establish rate limiting or cache corruption.

## Check continuous integration

[`.github/workflows/ci.yml`](.github/workflows/ci.yml) starts on every pull request, push to `master` and manual run.
Pull requests select affected jobs. Pushes to `master` and manual runs execute
every workload job plus the selection and result checks. The existing workload
commands and matrix dimensions are retained.
All six Maven job definitions install checksum-verified Maven 3.9.8 and run the download retry check before their
builds. Rust and Wayang Compose artifacts retain `java-build.log` and `java-classpath.log`, including
after a failed build.
Every Maven job uploads `maven-downloads-*` artifacts containing `before.json`
and `after.json` cache reports, including after failures. Reports describe
`.lastUpdated` and `_remote.repositories` files by relative path, size,
modification time and SHA-256. Missing-artifact markers and transfer-error
markers remain distinct; HTTP status codes are included only when explicitly
present in transfer-error text. Existing artifacts associated with
`.lastUpdated` markers receive file fingerprints. Reports omit repository URLs,
credentials and raw error text. They read the cache without deleting files or
changing update policies. These diagnostics do not establish the cause of
historical download failures or prevent new repository failures.

CI also builds and checks a local Maven extension against the pinned runtime.
`http-responses.jsonl` records actual HTTP response statuses for the three
Wayang libraries and two BOM imports from Central, its CI mirror and
Confluent, before Resolver retry handling.
It includes the public host and artifact path, fixed classifications for
cache, server and content type, and SHA-256 fingerprints of selected response
headers. Request headers, cookies, response bodies, URL credentials and query
strings are excluded. Other endpoints and paths are excluded. The extension
preserves the response and the existing retry strategy. A failure to install
or record capture fails the job. The offline checks isolate their simulated
responses from the CI report; an empty report means no matching request was
captured. The generated extension is enabled only in CI through `MAVEN_OPTS`.
Capture is limited to HTTPS GET and HEAD requests for `wayang-core`,
`wayang-basic` and `wayang-java` POMs and JARs, plus the `junit-bom:5.14.4` and
`jackson-bom:2.23.0` POMs. Other BOM versions are excluded.
Numeric age, content length and
Retry-After values are included only for a single header containing at most
nine decimal digits; absent or invalid values are `null`. Header fingerprints
cover age, cache control, CDN cache status and request identifier, content
length and type, date, ETag, Retry-After, server, Via, X-Cache, X-Cache-Hits and
X-Served-By. Free header values are excluded.
The native cookie-parser logger is disabled because malformed-cookie warnings
can include complete cookie values. An in-memory malformed-cookie regression
checks this exclusion against the actual bundled handler. Cookie parsing and
HTTP failure handling remain unchanged.

Each Maven job scopes its dependency-cache key with its workload identity in
`cache-context`. The key also hashes POMs, Maven wrapper and extension inputs,
`.mvn/maven.config`, `.mvn/ci-settings.xml` and the pinned-runtime checker.
Matrix variants of the same
job share that workload's cache. A smaller workload cannot reserve the same
immutable key and prevent a larger workload from retaining its dependencies.
The context is created before Java setup; an empty identity fails the step.

The transport check enables [CI settings](.mvn/ci-settings.xml) through
`MAVEN_ARGS` after its offline fixtures. Maven mirrors only `central` to the
[Google Cloud-hosted copy](https://docs.cloud.google.com/docs/buildpacks/java).
This applies to dependencies and plugins. Confluent keeps its own endpoint;
local Maven invocations keep their normal settings. The runner's `-ntp`
argument is retained. Other existing `MAVEN_ARGS` or `MAVEN_OPTS` fail the CI
setup instead of being overwritten or logged.
The mirror can lag Central, so pinned artifacts must resolve there in CI.
The native 401, 403 and 404 failure policy remains in effect. HTTP 404 with a
`Retry-After` header is still not retried. A native fixture reproduces that
response and verifies resolution through the configured mirror without
requests or sleeps. Run the offline transport and routing checks locally:

```bash
python3 tools/check_maven_http_capture.py
```

The selector verifies the checked-out pull request merge commit and both event parents. A depth-two checkout
supplies these commits. It compares the base parent with the tested merge tree using NUL-delimited paths and
disabled rename detection, so renames select both source and destination inputs and deletions remain visible.

[`tools/ci_job_selection.py`](tools/ci_job_selection.py) reads the Maven reactor and follows internal dependencies,
including test and profile dependencies. Jobs select their transitive module inputs. Shared schemas, vectors,
learning metadata and referenced files also select their language or integration checks. Build configuration,
workflow and tooling changes, shared contract changes, user manuals and unmapped paths select the full workflow.
Invalid event, Git, dependency or selection data fail the selection and final result.

Every selection includes `Operator learning synchronization`, which also checks maintained documentation links.
Ordinary documentation under `docs/` and root Markdown files need only this job unless a language test reads the
changed reference. Shared contracts, user manuals and root governance files use full validation; unmapped nested
Markdown files also select the full workflow. Frontend changes select the Python matrix as well, because the
local CP launcher checks the frontend entry point and linked assets.
The combined KafScale job remains selected for core changes because `opentsx-legacy-bridge` depends on core.
Rust runner and contract changes also select KafScale conformance, which builds and executes the B2 parity harness.

The standalone analysis job builds and starts the finite Compose package on an
ARM64 runner, fits through the real Workbench controls and checks downloaded
results. It needs no CP cluster. Python and frontend consumers, including the
local launcher, select this job; shared and unknown inputs retain full checks.

`CI result` validates the GitHub-provided job results, including matrix-job results. Every selected job result
must be successful; a skipped job is accepted only when selection explicitly excluded it. Selection failure,
missing results, cancellation and unexpected skips fail the result. Full validation events also require every job
to be selected. Before merging, check each individual workflow job conclusion as well as `CI result`.

You need Python 3.12 and Git. From the repository root, run the selection and workflow regressions:

```bash
python3 -B -m unittest discover -s tools -p 'test_ci*selection.py' -v
```

| Job | What it does |
|-----|--------------|
| Select affected jobs | validates the tested merge and selects jobs from module dependencies and shared inputs |
| Java 17, Java 21 | `mvn -B -ntp verify` |
| Operator learning synchronization | repository gitlink URL checks, learning synchronization tests, generated student lessons, source-content hashes and maintained documentation links |
| Shared contract vectors and Rust | generated assignment fixture check and the pinned Rust consumer of the shared cases |
| Rust runner tests and lint | all-target tests, formatting and Clippy |
| Rust native x86_64 and ARM64 | native vectors and runner regressions |
| Rust static musl targets | three static artifacts and ARMv7 QEMU tests |
| Rust B1 Proxy acceptance | protocol, producer placement and signed recovery |
| Python 3.10 to 3.13 | `pip install -e ".[forecasting,kafka]" pytest`, `pytest -q`; on 3.12 also local CP launcher tests and `tools/check_links.py` |
| k3s scripts and manifests | `bash -n` and `shellcheck` on `deploy/k3s/*.sh` and `deploy/docker/*.sh`; renders the kustomize overlays with `deploy/k3s/20-configure.sh` |
| KafScale conformance | source-built KafScale, Proxy I/O and module tests |
| Java -> Iceberg -> Python | Java writes an Iceberg fixture that PyIceberg must read byte-identically; the Python client queries a Java retrieval server |
| CI result | requires the selector and every selected job to succeed; verifies explicit exclusions and the complete job inventory |
| Standalone analysis package | isolated bundle and browser fit/export |

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
