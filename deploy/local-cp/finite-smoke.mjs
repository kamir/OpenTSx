import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { createRequire } from 'node:module';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ORIGIN = 1795003200000000;
const SECOND = 1000000;
const NUMERICAL_PATH = '/analysis/v1/modelling/runs';

export function parseOptions(args) {
  const options = new Map();
  for (let index = 0; index < args.length; index++) {
    const key = args[index];
    if (
      !['--help', '--url', '--playwright-module', '--output'].includes(key) ||
      options.has(key)
    )
      throw new Error(
        'Expected unique --help, --url, --playwright-module and --output arguments.',
      );
    if (key === '--help') options.set(key, true);
    else {
      const value = args[++index];
      if (!value || value.startsWith('--'))
        throw new Error(`Missing argument for ${key}.`);
      options.set(key, value);
    }
  }
  const raw = options.get('--url') ?? 'http://127.0.0.1:18081';
  let url;
  try {
    url = new URL(raw);
  } catch {
    throw new Error('A valid loopback URL is required.');
  }
  if (
    !/^http:\/\/127\.0\.0\.1(?::[0-9]+)?\/?$/.test(raw) ||
    url.hostname !== '127.0.0.1' ||
    url.username ||
    url.password ||
    url.search ||
    url.hash ||
    url.pathname !== '/' ||
    (url.port && Number(url.port) < 1)
  )
    throw new Error(
      'The URL must use HTTP loopback 127.0.0.1, a positive port and no credentials, path, query or fragment.',
    );
  return {
    help: options.has('--help'),
    url: url.origin,
    playwrightModule: options.get('--playwright-module') ?? 'playwright',
    output: path.resolve(
      options.get('--output') ?? 'target/standalone-analysis',
    ),
  };
}

export function episodeFixture(example) {
  assert.equal(example.schema, 'opentsx.modelling-request.v1');
  assert.equal(example.target.time_kind, 'relative');
  assert.deepEqual(
    example.target.values,
    [2, 3, 5, 4, 7, 6, 9, 8, 11, 10, 13, 12, 15, 14, 17, 16],
  );
  assert.deepEqual(
    example.target.timestamps_micros,
    Array.from({ length: 16 }, (_, index) => index * SECOND),
  );
  assert.deepEqual(example.target.episode_ids, ['synthetic-episode-1']);
  return {
    seriesId: example.target.series_id,
    metric: example.target.label,
    unit: example.target.unit,
    episodeId: example.target.episode_ids[0],
    timestampsMicros: example.target.timestamps_micros.map(
      (stamp) => ORIGIN + stamp,
    ),
    values: example.target.values,
    intervalMicros: SECOND,
    labels: {
      origin: 'SYNTHETIC',
      fixture: 'finite-random-walk-v1',
      'clock.origin': '2026-11-18T12:00:00Z',
    },
  };
}

export function validateResult(result, example) {
  const episode = episodeFixture(example);
  const bytes = Buffer.alloc(episode.values.length * 8);
  episode.values.forEach((value, index) =>
    bytes.writeDoubleLE(value, index * 8),
  );
  const hash = createHash('sha256').update(bytes).digest('hex');
  assert.equal(result.schema, 'opentsx.modelling-result.v1');
  for (const [key, expected] of Object.entries({
    series_id: episode.seriesId,
    unit: 'V',
    time_kind: 'absolute',
    nobs: 16,
    interval_micros: SECOND,
    t_start_micros: ORIGIN,
    t_end_micros: ORIGIN + 16 * SECOND,
    values_sha256: hash,
  }))
    assert.equal(result.source[key], expected, `Unexpected source ${key}.`);
  assert.deepEqual(result.source.episode_ids, ['synthetic-episode-1']);
  assert.equal(result.forecast.result.mean.length, 4);
  for (const value of result.forecast.result.mean)
    assert.ok(
      Number.isFinite(value) && Math.abs(value - 16) <= 1e-8,
      'The random-walk forecast must equal 16 within absolute tolerance 1e-8.',
    );
  assert.deepEqual(
    result.forecast.result.timestamps_micros,
    [16, 17, 18, 19].map((step) => ORIGIN + step * SECOND),
  );
  assert.deepEqual(result.baselines.naive.result.mean, [16, 16, 16, 16]);
  assert.deepEqual(
    result.backtest.result.folds.map((fold) => [
      fold.training_start,
      fold.training_stop,
      fold.test_start,
      fold.test_stop,
    ]),
    [
      [0, 12, 12, 14],
      [0, 14, 14, 16],
    ],
  );
  assert.ok(
    Number.isFinite(result.backtest.result.metrics.naive.mae) &&
      Math.abs(result.backtest.result.metrics.naive.mae - 2.5) <= 1e-8,
    'The held-out naive MAE must equal 2.5.',
  );
  return hash;
}

export function allowedRequest(method, target, origin) {
  let url;
  try {
    url = new URL(target);
  } catch {
    return false;
  }
  if (url.origin !== origin) return false;
  if (url.pathname === NUMERICAL_PATH) return method === 'POST' && !url.search;
  let pathname;
  try {
    pathname = decodeURIComponent(url.pathname);
  } catch {
    return false;
  }
  if (/^\/(?:analysis|api|retrieval|v1)(?:\/|$)/.test(pathname)) return false;
  return method === 'GET';
}

async function main(args) {
  const options = parseOptions(args);
  if (options.help) {
    console.log(
      'Usage: node deploy/local-cp/finite-smoke.mjs [--url http://127.0.0.1:18081] [--playwright-module <module>] [--output <directory>]',
    );
    return;
  }
  const require = createRequire(import.meta.url);
  assert.equal(
    require(path.join(options.playwrightModule, 'package.json')).version,
    '1.62.1',
    'Playwright 1.62.1 is required.',
  );
  const { chromium } = require(options.playwrightModule);
  const example = JSON.parse(
    await readFile(
      new URL(
        '../../python-package/examples/finite_modelling_request.json',
        import.meta.url,
      ),
      'utf8',
    ),
  );
  const episode = episodeFixture(example);
  const started = performance.now();
  const checks = [];
  const forbiddenRequests = [];
  const browserErrors = [];
  const numericalResponses = [];
  let browser;
  let context;
  let page;
  let failure;
  let downloaded;
  let sourceHash;
  await mkdir(options.output, { recursive: true });
  try {
    browser = await chromium.launch({ headless: true });
    context = await browser.newContext({
      viewport: { width: 1600, height: 1100 },
      acceptDownloads: true,
      serviceWorkers: 'block',
    });
    context.setDefaultTimeout(15000);
    await context.route('**/*', async (route) => {
      const request = route.request();
      if (allowedRequest(request.method(), request.url(), options.url))
        await route.continue();
      else {
        const url = new URL(request.url());
        forbiddenRequests.push({
          method: request.method(),
          origin: url.origin,
          path: url.pathname,
        });
        await route.abort('blockedbyclient');
      }
    });
    await context.routeWebSocket('**/*', async (socket) => {
      forbiddenRequests.push({ method: 'WEBSOCKET' });
      await socket.close({
        code: 1008,
        reason:
          'This finite analysis journey permits no WebSocket connections.',
      });
    });
    page = await context.newPage();
    page.on('response', (response) => {
      const request = response.request();
      if (
        request.method() === 'POST' &&
        new URL(response.url()).pathname === NUMERICAL_PATH
      )
        numericalResponses.push(response.status());
    });
    page.on('pageerror', (error) => browserErrors.push(error.message));
    page.on('console', (message) => {
      if (message.type() === 'error') browserErrors.push(message.text());
    });
    const response = await page.goto(`${options.url}/workbench?mode=engineer`);
    assert.ok(response?.ok(), 'The packaged Workbench route did not load.');
    const chooser = page.waitForEvent('filechooser');
    await page
      .getByRole('button', { name: 'Open bucket file', exact: true })
      .click();
    await (
      await chooser
    ).setFiles({
      name: 'finite-random-walk-utc.json',
      mimeType: 'application/json',
      buffer: Buffer.from(JSON.stringify([episode])),
    });
    await page
      .getByRole('list', { name: 'Source signals', exact: true })
      .getByText(episode.metric, { exact: true })
      .waitFor();
    checks.push(
      'The real file control imports synthetic voltage episodes anchored at 2026-11-18T12:00:00Z.',
    );
    await page.getByRole('tab', { name: 'Modelling', exact: true }).click();
    const panel = page.getByRole('region', {
      name: 'Modelling and prediction',
      exact: true,
    });
    await panel
      .getByRole('combobox', { name: 'Target series', exact: true })
      .selectOption(episode.seriesId);
    await panel
      .getByRole('combobox', { name: 'Forecast family', exact: true })
      .selectOption('arima');
    await panel
      .getByRole('combobox', { name: 'Trend', exact: true })
      .selectOption('n');
    for (const [name, value] of Object.entries({
      'Range start index': 0,
      'Range end index (exclusive)': 16,
      'Forecast steps': 4,
      p: 0,
      d: 1,
      q: 0,
      'Optimizer iterations': 200,
      'Initial training samples': 12,
      'Backtest horizon': 2,
      'Backtest step': 2,
      'Rolling training window (0 expands)': 0,
      'Baseline seasonal period': 1,
      Alpha: 0.05,
    }))
      await panel.getByLabel(name, { exact: true }).fill(String(value));
    for (const name of [
      'Mean baseline',
      'Drift baseline',
      'Seasonal naive baseline',
    ])
      await panel.getByLabel(name, { exact: true }).uncheck();
    await panel
      .getByRole('button', { name: 'Fit, backtest and forecast', exact: true })
      .click();
    await panel
      .getByRole('status')
      .filter({ hasText: /^Fit and rolling backtest completed\. Run / })
      .waitFor({ timeout: 90000 });
    await panel
      .getByRole('img', {
        name: 'Observed target and forecast with prediction interval',
        exact: true,
      })
      .waitFor({ state: 'visible' });
    checks.push(
      'The real modelling controls execute ARIMA(0,1,0) and render the forecast chart and completed fit status.',
    );
    const downloadEvent = page.waitForEvent('download');
    await panel
      .getByRole('link', {
        name: 'Download modelling result JSON',
        exact: true,
      })
      .click();
    const download = await downloadEvent;
    assert.equal(
      await download.failure(),
      null,
      'The modelling result download failed.',
    );
    const stream = await download.createReadStream();
    assert.ok(stream, 'The modelling result download has no readable bytes.');
    const chunks = [];
    for await (const chunk of stream) chunks.push(chunk);
    downloaded = Buffer.concat(chunks);
    assert.deepEqual(
      numericalResponses,
      [200],
      'The fit must receive one successful numerical service response.',
    );
    sourceHash = validateResult(
      JSON.parse(downloaded.toString('utf8')),
      example,
    );
    checks.push(
      'Downloaded bytes preserve V, the declared UTC clock and the independently calculated source SHA-256.',
    );
    checks.push(
      'Every forecast equals the closed-form random-walk value 16 within absolute tolerance 1e-8; two held-out folds give naive MAE 2.5.',
    );
  } catch (error) {
    failure = error;
  } finally {
    const errors = failure ? [failure] : [];
    if (page && !page.isClosed()) {
      try {
        await page.screenshot({
          path: path.join(options.output, 'workbench.png'),
          fullPage: true,
        });
      } catch (error) {
        errors.push(
          new Error('Failed to capture the finite Workbench screenshot.', {
            cause: error,
          }),
        );
      }
    }
    for (const resource of [context, browser]) {
      if (!resource) continue;
      try {
        await resource.close();
      } catch (error) {
        errors.push(error);
      }
    }
    if (forbiddenRequests.length)
      errors.push(new Error('The browser attempted a forbidden connection.'));
    if (browserErrors.length)
      errors.push(new Error('The browser reported errors.'));
    failure =
      errors.length === 1
        ? errors[0]
        : errors.length
          ? new AggregateError(errors, 'Finite browser acceptance failed.')
          : null;
  }
  const result = {
    status: failure ? 'failed' : 'passed',
    url: options.url,
    playwright: '1.62.1',
    durationMs: Math.round(performance.now() - started),
    sourceHash,
    checks,
    forbiddenRequests,
    browserErrors,
    numericalResponses,
    ...(failure ? { error: failure.stack ?? String(failure) } : {}),
  };
  if (downloaded)
    await writeFile(
      path.join(options.output, 'opentsx-modelling-result.json'),
      downloaded,
    );
  await writeFile(
    path.join(options.output, 'input-episode.json'),
    JSON.stringify([episode], null, 2) + '\n',
  );
  await writeFile(
    path.join(options.output, 'result.json'),
    JSON.stringify(result, null, 2) + '\n',
  );
  console.log(JSON.stringify(result, null, 2));
  if (failure) throw failure;
}

if (
  process.argv[1] &&
  path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)
)
  main(process.argv.slice(2)).catch((error) => {
    console.error(error.stack ?? String(error));
    process.exitCode = 1;
  });
