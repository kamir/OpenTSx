# Run finite numerical analyses without a broker

Run the local snapshot-analysis API from this checkout. Verified with Python
3.12.9 and statsmodels 0.15.0.

You need an active Python 3.12 virtual environment and this source checkout.
Run the commands from the repository root. The finite API runs independently
of Kafka, MLflow, Docker and built frontend assets.

## Install the numerical dependencies once

Reuse the existing runtime version constraints and install the forecasting
extra in editable mode:

```bash
python -m pip install --constraint deploy/local-cp/requirements.in setuptools wheel
python -m pip install --no-build-isolation \
  --constraint deploy/local-cp/requirements.in \
  --editable "./python-package[forecasting]" pytest
```

Subsequent source edits use the same environment. The forecasting extra does
not require the Kafka SDK.

## Start the finite API

```bash
python -m opentsx.client.research serve-finite --port 18082
```

The server binds to `127.0.0.1:18082`. In another terminal, check readiness:

```bash
curl --fail http://127.0.0.1:18082/analysis/v1/health
```

`--bind` accepts `127.0.0.1` or `0.0.0.0`. The default finite listener stays on
loopback. The [Compose package](standalone-analysis.md) sets the container
listener explicitly and publishes its host port on loopback.

The finite service constructs no broker, registry, catalogue or lifecycle
clients. It performs no recovery and keeps no lifecycle database. Results are
returned to the requesting client. Stop the foreground service with Ctrl+C.

## Execute the synthetic reference request

The [request](../../python-package/examples/finite_modelling_request.json)
contains 16 synthetic voltage samples on a relative one-second axis. It fits
ARIMA(0,1,0) without drift, forecasts four samples and evaluates two held-out
origins against the naive baseline. The closed-form forecast is the last
observed value, 16 V.

The order matters. Start the service before posting the request:

```bash
curl --fail \
  --header 'Content-Type: application/json' \
  --header 'Origin: http://127.0.0.1:18082' \
  --data-binary @python-package/examples/finite_modelling_request.json \
  --output /tmp/opentsx-finite-result.json \
  http://127.0.0.1:18082/analysis/v1/modelling/runs
```

Check the saved result:

```bash
python - <<'PY'
import json
from pathlib import Path

result = json.loads(Path('/tmp/opentsx-finite-result.json').read_text())
forecast = result['forecast']['result']
assert result['source']['unit'] == 'V'
assert result['source']['time_kind'] == 'relative'
assert len(forecast['mean']) == 4
assert all(abs(value - 16) <= 1e-8 for value in forecast['mean'])
assert forecast['timestamps_micros'] == [16000000, 17000000, 18000000, 19000000]
assert len(result['backtest']['result']['folds']) == 2
print(result['run_id'])
PY
```

The JSON contains the input identity and hash, parameters, runtime versions,
forecast, baselines, backtest and warnings. Its conditional Gaussian intervals
exclude full parameter-estimation uncertainty. Input identities are supplied
by the client; the result does not establish broker replay positions.

## Run the local regression checks

```bash
python -m pytest -q \
  python-package/tests/test_research_startup.py \
  python-package/tests/test_finite_service_http.py
```

These tests require no running services. They verify startup without connected
clients, request boundaries, unavailable functions, connected-mode cleanup and
a real numerical process against the same reference request. The HTTP request
checks run in process without binding a service listener.
Existing numerical, lifecycle and Python package tests remain separate checks.

## Serve the built Workbench from the same address

Build the frontend using the
[standalone Workbench instructions](standalone-workbench.md#run-the-local-edit-checks).
Stop the API process, then start it with the explicit asset directory:

```bash
python -m opentsx.client.research serve-finite --port 18082 \
  --web-root opentsx-saas-frontend/dist
```

Open `http://127.0.0.1:18082/workbench`. Requests use the same local origin.
Use the [modelling workflow](local-cp-workbench.md#fit-and-inspect-a-finite-model)
for the existing fitting controls. The
[connected CP profile](local-cp-workbench.md) supplies model registration,
catalogue capture and continuous scoring.

## Resolve explicit errors

| Error | Action |
| --- | --- |
| HTTP 400 | Supply valid bounded JSON and a supported modelling request. |
| HTTP 403 | Use a local Host and the exact same HTTP Origin for writes. |
| HTTP 422 | Inspect the numerical failure. |
| HTTP 503, numerical run | Wait for the active run to finish before retrying. |
| HTTP 503, broker or lifecycle route | Use the connected profile. |
| HTTP 504 | Reduce the workload; its time limit expired. |
| `web root has no built index.html` | Build the selected frontend. |
| `invalid manifest:` | Correct the supplied manifest file and JSON object. |

Without `--web-root`, UI and asset routes return HTTP 404 and read no files
from the working directory. An explicitly supplied root needs `index.html`.
An optional `--manifest` must contain a readable JSON object without duplicate
keys or nonfinite values. Invalid supplied metadata fails startup.
`serve-finite` rejects `--bootstrap`, `--registry-url` and `--state-dir`.
