# Build and run the standalone analysis package

Run the Workbench and finite numerical service as one isolated Compose service.
The pinned package targets `linux/arm64`; these commands use Docker 29.3.0 and
Compose 5.1.0.

You need Git, host Python 3.10 or newer, Docker with `linux/arm64` support and
Compose with `up --wait`. Build registries and pinned images must be reachable
or cached. Host Node and scientific Python packages are unnecessary for the
build and ordinary startup. The profile uses its own network and requires no
existing CP cluster, Kafka bootstrap or MLflow service.

## Build a fixed source tree

The order matters. Run from the repository root:

```bash
python3 deploy/local-cp/workbench.py build --profile finite
OPENTSX_WORKBENCH_PORT=18083 python3 deploy/local-cp/workbench.py up --profile finite
python3 deploy/local-cp/workbench.py status --profile finite
curl --fail http://127.0.0.1:18083/analysis/v1/health
```

Open `http://127.0.0.1:18083/workbench?mode=engineer`.
The existing `port` in `deploy/local-cp/settings.json` supplies the default.
This guide uses the existing `OPENTSX_WORKBENCH_PORT` override to publish on
18083. The launcher reports the actual Docker binding after startup.

The build reuses the pinned Python and Node images, downloads hash-locked
wheels, builds the Python wheel and frontend, and writes a content-addressed
bundle under `deploy/local-cp/.build/bundles/`. It builds no Docker image.
Changing source during the build fails the build.

The manifest records `profile: finite`, source revision and digest, dirty
state, runtime-lock, wheel and frontend hashes, source date epoch and image
digests. Generated `deploy/local-cp/.build/compose.finite.env` selects this
bundle. Keep generated bundles and env files unchanged.

Startup installs from the bundled wheelhouse without a package registry. The
runtime volume is keyed by the runtime-lock hash. The service mounts the
artifacts read-only, has a read-only root, temporary `/tmp`, no added Linux
capabilities, a 2 GiB memory limit and a two-CPU limit.

The Compose project is `opentsx-workbench-finite`. Its analysis network and
runtime volume are scoped to that project. The container explicitly binds to
`0.0.0.0`; the published host port binds to `127.0.0.1`. Existing Host and
same-origin checks still apply.

## Create the synthetic input file

The canonical API example has relative coordinates. This browser example
deliberately anchors those values to the declared synthetic origin
`2026-11-18T12:00:00Z`, because the existing Episode JSON import uses an
absolute clock. Its unit remains V.

```bash
python3 - <<'PY'
import json
from pathlib import Path

request = json.loads(Path('python-package/examples/finite_modelling_request.json').read_text())
target = request['target']
origin = 1795003200000000
episode = {
    'seriesId': target['series_id'],
    'metric': target['label'],
    'unit': target['unit'],
    'episodeId': target['episode_ids'][0],
    'timestampsMicros': [origin + stamp for stamp in target['timestamps_micros']],
    'values': target['values'],
    'intervalMicros': 1000000,
    'labels': {'origin': 'SYNTHETIC', 'clock.origin': '2026-11-18T12:00:00Z'},
}
Path('/tmp/opentsx-finite-episode.json').write_text(json.dumps([episode]) + '\n')
PY
```

Choose **Open bucket file**, load `/tmp/opentsx-finite-episode.json`, then open
**Modelling**. Select the voltage target, ARIMA orders 0, 1, 0 and trend None.
Use the complete range 0 to 16, four forecast steps, initial training 12,
backtest horizon 2, backtest step 2, expanding training, seasonal period 1 and
only the naive baseline. Click **Fit, backtest and forecast**.

The four forecasts equal the last observed value, 16 V, within absolute
tolerance `1e-8`. Their UTC coordinates are the declared origin plus 16 to 19
seconds. Two held-out origins produce naive MAE 2.5. Download the actual result
with **Download modelling result JSON**. Source identities are client-supplied
synthetic references, not broker replay positions.

## Verify the real browser and exported result

For automation, you additionally need Node 24.22.2, Playwright 1.62.1 and
Chromium. Install the browser dependency once:

```bash
npm install --prefix /tmp/opentsx-demo-browser \
  --no-audit --no-fund --ignore-scripts playwright@1.62.1
node /tmp/opentsx-demo-browser/node_modules/playwright/cli.js install chromium
```

With the package running:

```bash
node deploy/local-cp/finite-smoke.mjs \
  --url http://127.0.0.1:18083 \
  --playwright-module /tmp/opentsx-demo-browser/node_modules/playwright \
  --output target/standalone-analysis
```

The check uploads the synthetic Episode JSON, fills the visible controls,
executes the real numerical request, inspects the completed status and chart,
and reads the downloaded bytes. It verifies the independently packed input
SHA-256, units, episode identity, absolute clock, forecasts and held-out folds.
Other APIs, external requests, WebSockets and browser errors fail the check.

The output directory contains `result.json`, `workbench.png`,
`input-episode.json` and `opentsx-modelling-result.json`. CI runs the same
journey against a newly built package on an ARM64 runner with no CP cluster.

## Keep the edit checks local

Run the standard-library launcher and profile checks without starting Docker:

```bash
python3 -m unittest discover -s deploy/local-cp -p 'test_workbench*.py' -v
```

With Node available, check the acceptance helper without a browser or service:

```bash
python3 -m unittest discover -s deploy/local-cp -p test_finite_smoke.py -v
```

Before pushing package changes, test the built artifacts:

```bash
python3 deploy/local-cp/workbench.py test --profile finite
```

That command runs host checks, installs the locked built wheel in the pinned
Python container, runs the Python suite, then runs the frontend suite and build
in the pinned Node container. A source mismatch rejects a stale bundle.
Keep this package check outside the ordinary frontend edit loop. External
integration skips and expected failures remain visible in the test output.

## Read logs and stop the isolated service

```bash
python3 deploy/local-cp/workbench.py logs --profile finite
python3 deploy/local-cp/workbench.py down --profile finite
```

`down` stops this project's service and removes its network. Its runtime volume
remains available for reuse. Connected model registration, catalogue capture
and continuous scoring use the separate
[connected profile](local-cp-workbench.md). Kafka seed and demo commands are
rejected in the finite profile before invoking Docker.

For API errors and numerical limits, use the
[finite-service guide](standalone-numerics.md#resolve-explicit-errors).
