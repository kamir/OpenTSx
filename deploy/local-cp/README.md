# Deploy the local CP workbench

Serve the Workbench and local Python analysis service on the existing CP Docker
network. Read the [developer path](../../docs/devguide/local-cp-workbench.md)
for prerequisites, locked build, installed-artifact tests, startup and finite
modelling.

`settings.json` selects the external network, internal bootstrap addresses,
tutorial topic prefix, pinned container images and loopback port. `workbench.py`
builds content-addressed bundles and operates the Compose service. The
native-wheel hashes in `requirements.lock` are generated from exact
`requirements.in` versions; normal builds use the committed lock without
regenerating it.

The profile opens `http://127.0.0.1:18081/workbench`. It does not start or
reconfigure CP brokers. Finite modelling accepts explicit browser samples
through `/analysis/v1/modelling/runs`; that route itself does not read or write
Kafka. Existing topic analysis and seed commands are separate operations.

The frontend test container mounts both shared operator fixtures and
`opentsx-model` identity/wire vectors. Generated bundles under `.build/` do not
enter the source digest or Git.
