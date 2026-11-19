# Build and run the local CP modelling workbench

Use the local CP profile with the Workbench and its Python analysis service. The
source contains this profile in `deploy/local-cp/`; use the reviewed delivery
checkout when the branch has not been published. Operational evidence belongs to
the exact built manifest, not an assumed upstream tag.

You need Git, host Python 3.10 or newer, Docker Compose with `up --wait`, and
the existing `cp-all-in-one-m4_default` Docker network. The profile uses pinned
Linux ARM64 images for Python, Node and MLflow. It does not require host scientific
packages or host Node for building and testing. Build-time package registries
and the pinned container images must be reachable or cached.

## Build a fixed source tree

The order matters. Run from the source checkout containing this profile:

```bash
python3 deploy/local-cp/workbench.py build
python3 deploy/local-cp/workbench.py test
```

The build verifies separate caches for locked dependencies, the OpenTSx wheel
and the frontend. Cache misses download hash-locked native Python wheels,
build the OpenTSx wheel with the Git commit time as `SOURCE_DATE_EPOCH`, and
build React with `npm ci` and the existing package lock. It does not build a
Docker image. A source change during the build fails the build. The
[artifact cache contract](standalone-analysis.md#reuse-artifacts-between-edits)
lists inputs, invalidation and failure handling for both profiles.

Generated bundles live in `deploy/local-cp/.build/bundles/`; the generated
`compose.env` selects one. Do not edit generated bundles. The manifest records
source revision/digest, dirty state, numerical wheel and runtime-lock hashes,
frontend hash and pinned image references. A bundle identity is
content-addressed; `build_inputs` records its artifact cache keys. `.build/`
is excluded from source hashing and Git.

The test command first runs standard-library launcher checks on the host,
including a real temporary Git repository for source hashing. It then installs
the built wheel from its offline wheelhouse, runs numerical Python tests in the
pinned Python container, and runs frontend tests and production build in the
pinned Node container. The frontend receives shared operator fixtures and model
identity/wire vectors through read-only mounts. External-service skips or
expected failures remain visible in test output. A successful process exit is
not evidence that an optional external-service test executed. Artifact cache
hits do not skip tests.

Run the host launcher checks separately without scientific packages or Docker:

```bash
python3 -m unittest discover -s deploy/local-cp -p 'test_workbench*.py' -v
```

`source differs from the built bundle; run build before test` means that source
files changed after the build. Rebuild the fixed source instead of testing a
stale numerical wheel.

## Start the local profile

```bash
python3 deploy/local-cp/workbench.py up
python3 deploy/local-cp/workbench.py status
curl --fail http://127.0.0.1:18081/analysis/v1/health
```

Open `http://127.0.0.1:18081/workbench`. The current React route is
`/workbench`; `/research` is not its entry point. The service binds only to
loopback. It uses the existing internal CP bootstrap addresses without changing
broker listeners or credentials.

Runtime installation uses the bundled wheelhouse without contacting a package
registry. The runtime volume is keyed by the full numerical lock. The Compose
service has a read-only root, temporary `/tmp`, no added capabilities, a 2 GiB
memory limit and a two-CPU limit. The internal MLflow service has a separate
1 GiB memory limit and one CPU. Its database/artifacts and the Workbench's
lifecycle/checkpoint state use persistent named volumes. This profile is local development
infrastructure, not a production authentication or tenant-isolation deployment.

## Fit and inspect a finite model

Load a sample or an eligible episode/file, select the intended series and range,
then open **Modelling** in the analysis views. The finite workflow configures an
ARIMA-family candidate, baseline comparison and chronological backtest. It
displays predicted values and conditional intervals, fold/origin metrics and
runtime/provenance information when the execution service returns a successful
result.

The fit uses L-BFGS with the Harvey likelihood score and complex-step
derivatives. The requested iteration limit and convergence check apply to every
fit, including each backtest origin. There is no alternate optimizer retry.

Prepare gaps, flagged values or an irregular clock explicitly before fitting. A
relative or index axis stays relative or indexed; a missing absolute origin is
not a date in 1970. Seasonal lengths and horizons are measured in samples of the
selected regular axis.

SARIMAX requires explicit historical/future context and availability coordinates
for the forecast origins. A future realised value is not accepted as known
context merely because it exists in the input file. The modelling route accepts
bounded explicit samples from the browser; it does not read or publish Kafka
records itself.

A rejected request, unavailable runtime, failed convergence or time limit is an
error. The UI must not present partial output as a completed fit. This path fits
and forecasts a finite snapshot. The separate
[review and deployment workflow](model-smart-topics.md) registers a preserved
fit and starts supported continuous scoring after explicit review.

For the forecasting workflow, baselines, diagnostics and chronological
evaluation, read [Forecasting: Principles and Practice, chapter 5](https://otexts.com/fpp3/toolbox.html).

## Preserve the result

Use the modelling result export for the finite execution response and
**Snapshot** for the actual input/graph snapshot. These artifacts have different
purposes. The input snapshot is not a Kafka offset replay; broker positions,
catalog version and build revision are reported as unavailable where the capture
does not supply them.

The **Episodes and quality**, **Pair results** and **Analysis results** views
are described in the [analysis guide](workbench-analysis.md). A typed result
imported from JSON is not an executed modelling job.

## Extend the numerical service

Read `python-package/opentsx/algorithms/episode_rms.py` and
`python-package/tests/test_research_workbench.py` for the included
scalar-operator example. The reviewed registry in
`python-package/opentsx/client/research.py` supplies the existing topic-analysis
operators; finite modelling has its own validated request handler.

Add deterministic numeric and invalid-input fixtures before exposing another
method. Reuse the regular-input and structured-result contracts, declare
parameter/work limits, then rebuild and test the installed artifact. Adding a
numerical function does not automatically add a Workbench control or a
continuous processor implementation. The active UI must state the execution mode
supported by that path.

## Inspect configuration and failures

Change network, bootstrap, prefix, port and registry settings in
`deploy/local-cp/settings.json` before rebuilding. Keep the pinned image and
dependency versions unless intentionally qualifying a different runtime. Do not
create a second configuration path for the same settings.

The profile provides `status`, `logs`, `seed` and `down` commands in the same
launcher. `seed` writes dedicated synthetic tutorial episodes/topics and is
separate from a modelling request. `down` stops this workbench Compose project;
it does not stop the existing CP cluster. A changed port or network requires a
rebuilt selected configuration before startup.

The process prints failures instead of silently selecting another network, model
family or runtime. Preserve the exact manifest and observed test/deployment
output when reporting a result.
