# Build and run the standalone analysis package

Run and record the Workbench and finite numerical service in one isolated Compose service, verified with OpenTSx Python 1.0.0.
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
digests. Its `build_inputs` records the three artifact cache keys. Generated
`deploy/local-cp/.build/compose.finite.env` selects this
bundle. Keep generated bundles and env files unchanged.

Startup installs from the bundled wheelhouse without a package registry. The
runtime volume is keyed by the runtime-lock hash. The service mounts the
artifacts read-only, has a read-only root, temporary `/tmp`, no added Linux
capabilities, a 2 GiB memory limit and a two-CPU limit.

The Compose project is `opentsx-workbench-finite`. Its analysis network and
runtime volume are scoped to that project. The container explicitly binds to
`0.0.0.0`; the published host port binds to `127.0.0.1`. Existing Host and
same-origin checks still apply.

## Reuse artifacts between edits

Builds keep three separate caches under `deploy/local-cp/.build/cache/`.
Each entry records its file names, modes and SHA-256 hashes. The launcher
verifies the complete entry before reuse and publishes new entries only
after the producing command and input checks succeed.

| Cache | Inputs that select its entry |
| --- | --- |
| `deps` | Python image, platform, recipe and dependency lock. |
| `wheel` | Dependency key, Python inputs and commit time. |
| `frontend` | Node image, platform, recipe and frontend inputs. |

The wheel key includes the Python build script, package file names, modes and
contents, and `SOURCE_DATE_EPOCH`. The frontend key includes its file names,
modes and contents, including local environment files.

Generated Python build directories and frontend dependencies, output and
coverage files are excluded from their source keys. Python copying and
fingerprinting use the same exclusions. Ignored Vite environment files still
affect the frontend key; their contents are not printed.

Run another build after an edit:

```bash
python3 deploy/local-cp/workbench.py build --profile finite
```

The log reports `build cache deps: hit`, `build cache wheel: hit` and
`build cache frontend: hit` for reused entries. A package source edit rebuilds
the wheel; a frontend source edit rebuilds the frontend. Changing the locked
dependencies rebuilds both dependency and application wheels. A new commit
time rebuilds the application wheel even when only documentation changed.

Each build assembles its entrypoint and manifest from the current checkout.
Cache hits preserve artifact hashes while source provenance remains current.
An unchanged build selects the same bundle. Cache status and timings are not
part of the bundle identity.

`invalid build cache: <path>` reports missing or altered files, invalid metadata,
permissions or file types in an existing entry. Remove only the reported entry
under `.build/cache/`, then build again.
The launcher stops on invalid entries without retrying a build silently.

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

Use a clean Git checkout and build that exact revision before recording. Commit
your changes locally, rebuild and restart after an edit. The recording checks
the source digest, all three build keys and runtime provenance before and after
the browser journey. Ignored frontend environment files affect the build keys.

With the package running, choose a new output directory:

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
`input-episode.json`, `opentsx-modelling-result.json`, `workbench.webm` and
`trace.zip`. The video records the actual 1600 by 1100 browser viewport.
The trace retains browser actions, DOM snapshots, screenshots and network
evidence without copying source files. No artificial delays are added.

`recording.json` is published only after the numerical checks, capture and
source comparisons pass. It records the revision, source and build hashes,
runtime identity, browser tool versions and every evidence file's size and
SHA-256. CI runs this same journey against a newly built package on an ARM64
runner with no CP cluster and uploads the complete output directory.

## Verify and replay the recording

Check every recorded file before opening it:

```bash
node --input-type=module - <<'JS'
import { verifyRecording } from './deploy/local-cp/finite-recording.mjs';
const recording = await verifyRecording('target/standalone-analysis');
console.log(recording.status, recording.source.revision);
JS
```

Open `target/standalone-analysis/workbench.webm` in a WebM video player.
For the interactive action trace, run the pinned Playwright viewer:

```bash
node /tmp/opentsx-demo-browser/node_modules/playwright/cli.js show-trace \
  --host 127.0.0.1 --port 0 target/standalone-analysis/trace.zip
```

The viewer serves on a free loopback port. Stop it with Ctrl+C. Replay uses
the recorded snapshots; it does not refit the model. To rerun the numerical
journey against the same built source, use a different fresh directory:

```bash
node deploy/local-cp/finite-smoke.mjs \
  --url http://127.0.0.1:18083 \
  --playwright-module /tmp/opentsx-demo-browser/node_modules/playwright \
  --output target/standalone-analysis-repeat
```

The reproducible contract covers source, input, controls and numerical
assertions. Capture timing, video bytes and trace bytes can differ between
runs. Compare each run's manifest and numerical output; do not compare video
hashes across separate runs.

`EEXIST` means the output directory already exists. Choose a fresh directory.
`recording requires a clean Git checkout` means tracked edits or untracked
files are present. Commit the intended source, rebuild and restart.
`Runtime provenance differs from the local source.` means the running package
does not match the checkout or its build inputs. Rebuild and restart before
recording. Output inside the repository must be ignored by Git; use `target/`
or an external directory. Failed runs retain diagnostics without an approved
`recording.json`.

## Keep the edit checks local

Run the standard-library launcher, cache and profile checks without starting
Docker or installing scientific Python packages:

```bash
python3 -m unittest discover -s deploy/local-cp -p 'test_workbench*.py' -v
```

With Node available, check the acceptance helper without a browser or service:

```bash
python3 -m unittest discover -s deploy/local-cp -p 'test_*.py' -v
```

Before pushing package changes, test the built artifacts:

```bash
python3 deploy/local-cp/workbench.py test --profile finite
```

That command runs host checks, installs the locked built wheel in the pinned
Python container, runs the Python suite, then runs the frontend suite and build
in the pinned Node container. It executes the suites even when all build
artifacts are cached. A source mismatch rejects a stale bundle.
Keep this package check outside the ordinary frontend edit loop. External
integration skips and expected failures remain visible in the test output.

`build inputs differ from the built bundle; run build before test` also catches
changed ignored environment files. Rebuild before testing the selected bundle.

Use the existing [frontend edit checks](standalone-workbench.md#run-the-local-edit-checks)
for browser implementation changes. Shared files read from disk require the
full frontend suite because related-test selection follows imports.

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
