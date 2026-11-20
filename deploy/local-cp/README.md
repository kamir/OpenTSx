# Deploy the Workbench profiles

Use the [standalone analysis profile](../../docs/devguide/standalone-analysis.md)
for finite fitting without a CP cluster. Select `--profile finite` on the
existing launcher; it uses a separate Compose project, generated env file,
network and runtime volume.

The default connected profile serves the Workbench and Python service on the CP
network. Read the [developer path](../../docs/devguide/local-cp-workbench.md)
for prerequisites, locked build, installed-artifact tests, startup and finite
modelling. Continue with the
[model deployment guide](../../docs/devguide/model-smart-topics.md) to register
reviewed fits and score incoming episodes as Smart Topics.

`settings.json` selects the external network, internal bootstrap addresses,
tutorial topic prefix, pinned container images and loopback port. `workbench.py`
builds content-addressed bundles and operates the Compose service. The
native-wheel hashes in `requirements.lock` are generated from exact
`requirements.in` versions; normal builds use the committed lock without
regenerating it.

The connected profile opens `http://127.0.0.1:18081/workbench`. It does not start
or reconfigure CP brokers. Finite modelling accepts explicit browser samples
through `/analysis/v1/modelling/runs`; that route itself does not read or write
Kafka. Existing topic analysis and seed commands are separate operations.

The frontend test container mounts both shared operator fixtures and
`opentsx-model` identity/wire vectors. Generated bundles under `.build/` do not
enter the source digest or Git.

Both profiles reuse separate verified dependency, wheel and frontend entries
under `.build/cache/`. The manifest records their `build_inputs` keys alongside
current source provenance. The `test` command always executes the suites.
Read the [cache contract](../../docs/devguide/standalone-analysis.md#reuse-artifacts-between-edits)
for invalidation and rejected entries.

The profile includes an internal MLflow registry with a persistent database and
artifact volume. A separate Workbench state volume stores completed runs,
registrations, deployment intent and scoring checkpoints. The registry has no
published host port. The Workbench remains bound to loopback.

Use `demo-seed --scenario sarimax` and `demo-append --scenario sarimax` for the
paired target/context demonstration. `demo-target --scenario sarimax` followed
by `demo-context --scenario sarimax` exercises a visible missing-context wait.
Follow the model deployment guide for capture, review and context binding.

Continuous statistical scoring requires whole input and context topics with
exactly partition 0. Admission and worker topology checks reject multipart
topics and partition expansion.
