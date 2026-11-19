# Run the standalone thermal Workbench

Run the frontend package version 1.0.0 with Node 24.22.2 and the committed
dependency lock. This path runs the existing Workbench and operators locally
in the browser.

You need Node 24.22.2, npm 11.19.1 and a browser. The frontend `.nvmrc` pins
the Node version used by its CI job. The initial dependency installation needs
the npm registry or a populated npm cache. Run from the repository root.

## Install and start

```bash
cd opentsx-saas-frontend
npm ci --no-audit --no-fund
npm run demo
```

Open `http://127.0.0.1:3000/workbench?mode=engineer&demo=thermal` if the browser
does not open automatically. The server binds to loopback and fails if port
3000 is occupied. Stop it with Ctrl+C. Keep `node_modules` between runs; repeat
the installation when the dependency lock or Node version changes.

The thermal fixture contains two signals with separate temperature and heater
units, eight episodes, explicit synthetic origin and a missing-temperature
interval. No account, environment file, Python service, Docker, database or
broker is required for this journey.

An explicit demo link preserves an existing workspace and reports that choice.
Open a new browser tab for a separate analysis. Unknown demo names and links
that combine a demo with flow, system or signal imports produce an error.
Ordinary `/workbench` links keep their existing source-selection behavior.

## Inspect and transform the fixture

1. Inspect the temperature and heater traces in Signal Engineer.
2. Open **Episodes and quality** to inspect units, gaps and provenance.
3. Search **Find an operator** for `diff`, choose **Configure Diffs**, then
   select **Add operator** in the inspector.
4. Switch to **Data Worker** and inspect the retained graph and result.
5. Open **Snapshot** and select **Download local snapshot**.

The downloaded `opentsx-local-analysis.json` contains source samples, graph,
computed output and a SHA-256 integrity digest. Reloading the demo URL in a
fresh page reproduces the input. See the
[analysis guide](workbench-analysis.md#preserve-a-finite-local-run) for snapshot
limits and import behavior.

Forecast fitting uses the separate Python numerical service. Inventory access,
saved account flows and streaming deployment need their documented services.
The standalone thermal journey does not call those services or emulate their
results. Use the [local CP workflow](local-cp-workbench.md) for finite model
fitting and the connected model lifecycle.

## Run the local edit checks

Run from `opentsx-saas-frontend` after the one-time installation:

```bash
npm run test:demo
```

This runs the demo boundary and thermal-fixture tests with jsdom. It requires
no services. Use the existing test watcher while editing:

```bash
npm test -- src/workbench/WorkbenchDemo.test.tsx
```

Before pushing frontend changes, run the full frontend checks:

```bash
npx tsc --noEmit
npm run lint
npm test -- --run
npm run build
```

Keep the surrounding repository checkout: existing frontend tests read shared
fixtures from `docs` and `opentsx-model`. No Java build is needed for these
checks. Keep the CP bundle build and distributed runtime suites outside this
browser-only edit loop.

## Verify the real browser journey

You also need Playwright 1.62.1 and its Chromium browser for this check. Install
them once, from `opentsx-saas-frontend`:

```bash
npm install --prefix /tmp/opentsx-demo-browser \
  --no-audit --no-fund --ignore-scripts playwright@1.62.1
node /tmp/opentsx-demo-browser/node_modules/playwright/cli.js install chromium
```

Check the production assets after building:

```bash
npm run test:demo:browser -- --built \
  --playwright-module /tmp/opentsx-demo-browser/node_modules/playwright \
  --output ../target/workbench-demo
```

Omit `--built` to check the development server without rebuilding:

```bash
npm run test:demo:browser -- \
  --playwright-module /tmp/opentsx-demo-browser/node_modules/playwright \
  --output ../target/workbench-demo-dev
```

The check starts and closes its own loopback server on an allocated port. A
fresh browser loads the real scope, checks rendered quality, applies the
existing difference operator and inspects downloaded snapshot bytes. It
checks all heater differences and preserves graph and output across display
changes. External and service requests fail the check.

The selected output directory contains `result.json`, a screenshot and the
downloaded snapshots. Durations are observations, not correctness thresholds.
The existing frontend CI job runs types, lint, all frontend tests, the build
and this built-asset browser acceptance. Java, Rust, broker and distributed
recovery jobs remain separate checks; this change does not filter their
scheduling.
