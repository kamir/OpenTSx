import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { access, mkdir, writeFile } from 'node:fs/promises';
import { createRequire } from 'node:module';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createServer, preview } from 'vite';

const options = new Map();
for (let index = 2; index < process.argv.length; index++) {
  const key = process.argv[index];
  if (
    !['--built', '--playwright-module', '--output'].includes(key) ||
    options.has(key)
  ) {
    throw new Error(
      'Expected unique --built, --playwright-module <module> or --output <directory> arguments.',
    );
  }
  if (key === '--built') options.set(key, true);
  else {
    const value = process.argv[++index];
    if (!value || value.startsWith('--'))
      throw new Error(`Missing value for ${key}.`);
    options.set(key, value);
  }
}

const require = createRequire(import.meta.url);
const moduleName = options.get('--playwright-module') ?? 'playwright';
assert.equal(
  require(path.join(moduleName, 'package.json')).version,
  '1.62.1',
  'Playwright 1.62.1 is required.',
);
const { chromium } = require(moduleName);
const root = fileURLToPath(new URL('../', import.meta.url));
const output = options.has('--output')
  ? path.resolve(options.get('--output'))
  : null;
const timeout = 30_000;
const started = performance.now();
const checks = [];
const forbiddenRequests = [];
const browserErrors = [];
const snapshots = new Map();
let server;
let browser;
let context;
let page;
let failure;

function canonical(value) {
  if (Array.isArray(value)) return `[${value.map(canonical).join(',')}]`;
  if (value && typeof value === 'object') {
    return `{${Object.keys(value)
      .sort()
      .map((key) => `${JSON.stringify(key)}:${canonical(value[key])}`)
      .join(',')}}`;
  }
  return JSON.stringify(value);
}

async function snapshot(name) {
  await page.getByRole('tab', { name: 'Snapshot', exact: true }).click();
  const downloadEvent = page.waitForEvent('download');
  await page
    .getByRole('button', { name: 'Download local snapshot', exact: true })
    .click();
  const download = await downloadEvent;
  assert.equal(
    await download.failure(),
    null,
    'The local snapshot download failed.',
  );
  const stream = await download.createReadStream();
  assert.ok(stream, 'The snapshot download has no readable bytes.');
  const chunks = [];
  for await (const chunk of stream) chunks.push(chunk);
  const bytes = Buffer.concat(chunks);
  const bundle = JSON.parse(bytes.toString('utf8'));
  assert.equal(bundle.schema, 'opentsx.local-analysis.v1');
  assert.equal(bundle.integrity.algorithm, 'SHA-256');
  assert.equal(
    bundle.integrity.value,
    createHash('sha256').update(canonical(bundle.payload)).digest('hex'),
  );
  assert.equal(bundle.payload.mode, 'browser-finite');
  assert.equal(bundle.payload.brokerOffsets, 'unavailable');
  snapshots.set(name, bytes);
  return bundle.payload;
}

function values(series, key = 'y') {
  assert.equal(series[key].$array, 'float64');
  assert.ok(Array.isArray(series[key].values));
  return series[key].values;
}

function checkSource(source) {
  assert.equal(source.name, 'Thermal test / Research 042');
  assert.equal(source.t0Ms, 1795003200000);
  assert.equal(source.series.length, 2);
  const temperature = source.series.find(
    (series) => series.id === 'thermal-temperature',
  );
  const heater = source.series.find((series) => series.id === 'thermal-heater');
  assert.ok(
    temperature && heater,
    'Both thermal source channels must be exported.',
  );
  assert.equal(temperature.unit, '°C');
  assert.equal(heater.unit, '%');
  assert.equal(values(temperature).length, 512);
  assert.deepEqual(
    values(temperature).flatMap((value, index) =>
      value === 'NaN' ? [index] : [],
    ),
    Array.from({ length: 20 }, (_, index) => 290 + index),
  );
  assert.deepEqual(
    temperature.quality.values,
    Array.from({ length: 512 }, (_, index) =>
      index >= 290 && index < 310 ? 1 : 0,
    ),
  );
  assert.deepEqual(
    values(heater),
    [0, 40, 80, 20, 0, 40, 100, 60].flatMap((value) => Array(64).fill(value)),
  );
  assert.deepEqual(
    values(heater, 'x'),
    Array.from({ length: 512 }, (_, index) => index),
  );
  assert.equal(source.episodes.length, 8);
  assert.ok(
    source.episodes.every(
      (episode) =>
        episode.labels.origin === 'SYNTHETIC' &&
        episode.labels.fixture === 'thermal-bench-v1',
    ),
  );
}

try {
  if (output) await mkdir(output, { recursive: true });
  const configuration = {
    root,
    configFile: path.join(root, 'vite.config.ts'),
    clearScreen: false,
  };
  const endpoint = {
    host: '127.0.0.1',
    port: 0,
    strictPort: true,
    open: false,
  };
  if (options.has('--built')) {
    await access(path.join(root, 'dist/index.html'));
    server = await preview({ ...configuration, preview: endpoint });
  } else {
    server = await createServer({ ...configuration, server: endpoint });
    await server.listen();
  }
  const address = server.httpServer.address();
  assert.ok(address && typeof address === 'object' && address.port > 0);
  const origin = `http://127.0.0.1:${address.port}`;
  browser = await chromium.launch({ headless: true, timeout });
  context = await browser.newContext({
    viewport: { width: 1600, height: 1100 },
    serviceWorkers: 'block',
  });
  context.setDefaultTimeout(timeout);
  context.setDefaultNavigationTimeout(timeout);
  await context.route('**/*', async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    if (
      url.origin !== origin ||
      /^\/(?:api|retrieval|analysis|v1)(?:\/|$)/.test(url.pathname)
    ) {
      forbiddenRequests.push({
        method: request.method(),
        origin: url.origin,
        path: url.pathname,
      });
      await route.abort('blockedbyclient');
    } else await route.continue();
  });
  await context.routeWebSocket('**/*', async (socket) => {
    const url = new URL(socket.url());
    const httpOrigin = `${url.protocol === 'ws:' ? 'http:' : 'https:'}//${url.host}`;
    if (
      httpOrigin !== origin ||
      /^\/(?:api|retrieval|analysis|v1)(?:\/|$)/.test(url.pathname)
    ) {
      forbiddenRequests.push({
        method: 'WEBSOCKET',
        origin: url.origin,
        path: url.pathname,
      });
      await socket.close({
        code: 1008,
        reason: 'The standalone demo permits no external or API connection.',
      });
    } else socket.connectToServer();
  });
  page = await context.newPage();
  page.on('pageerror', (error) => browserErrors.push(error.message));
  page.on('console', (message) => {
    if (message.type() === 'error') browserErrors.push(message.text());
  });
  const response = await page.goto(
    `${origin}/workbench?mode=engineer&demo=thermal`,
  );
  assert.ok(response?.ok(), 'The standalone Workbench route did not load.');
  await page
    .getByRole('button', { name: 'Signal Engineer', exact: true })
    .waitFor();
  const sources = page.getByRole('complementary', {
    name: 'Sources and operators',
    exact: true,
  });
  await sources.getByText('Synthetic data', { exact: true }).waitFor();
  const signals = sources.getByRole('list', {
    name: 'Source signals',
    exact: true,
  });
  assert.deepEqual(await signals.locator('li').allTextContents(), [
    'Panel temperature°C',
    'Heater schedule%',
  ]);
  assert.equal(
    await page
      .getByRole('button', { name: 'Signal Engineer', exact: true })
      .getAttribute('aria-pressed'),
    'true',
  );
  const canvas = page.locator('canvas[aria-label^="Oscilloscope view"]');
  await canvas.waitFor({ state: 'visible' });
  assert.ok(
    await canvas.evaluate((element) => element.width > 0 && element.height > 0),
    'The real scope canvas is empty.',
  );
  checks.push(
    'The seeded public route renders the real scope and synthetic source units without an account.',
  );

  await page
    .getByRole('tab', { name: 'Episodes and quality', exact: true })
    .click();
  const evidence = page.getByRole('region', {
    name: 'Episode quality and provenance',
    exact: true,
  });
  await evidence.waitFor();
  const rows = await evidence
    .getByRole('table')
    .first()
    .locator('tbody tr')
    .evaluateAll((elements) =>
      elements.map((row) =>
        [...row.cells].map((cell) => cell.textContent.trim()),
      ),
    );
  assert.deepEqual(rows, [
    ['thermal-temperature', '°C', '512', '20', '20', '0'],
    ['thermal-heater', '%', '512', '0', '0', '0'],
  ]);
  const initial = await snapshot('source-snapshot.json');
  checkSource(initial.source);
  assert.deepEqual(
    initial.doc.nodes.map((node) => node.op),
    ['source'],
  );
  checks.push(
    'Rendered quality and downloaded source bytes preserve the exact thermal gap, flags and heater schedule.',
  );

  await sources.getByLabel('Find an operator', { exact: true }).fill('diff');
  await sources
    .getByRole('button', { name: 'Configure Diffs', exact: true })
    .click();
  const inspector = page.getByRole('complementary', {
    name: 'Operator inspector',
    exact: true,
  });
  await inspector
    .getByRole('heading', { name: 'Difference', exact: true })
    .waitFor();
  await inspector
    .getByRole('button', { name: 'Add operator', exact: true })
    .click();
  await page
    .getByRole('region', { name: 'Processing flow graph', exact: true })
    .locator('.wb-node-op')
    .filter({ hasText: /^Diffs$/ })
    .waitFor();
  const computed = await snapshot('difference-snapshot.json');
  checkSource(computed.source);
  assert.deepEqual(
    computed.doc.nodes.map((node) => node.op),
    ['source', 'diff'],
  );
  assert.equal(computed.doc.nodes[1].input, computed.doc.nodes[0].id);
  const heater = computed.result.series.find(
    (series) => series.id === 'thermal-heater',
  );
  assert.ok(heater, 'The computed heater channel is absent from the export.');
  const transitions = new Map([
    [64, 40],
    [128, 40],
    [192, -60],
    [256, -20],
    [320, 40],
    [384, 60],
    [448, -40],
  ]);
  assert.deepEqual(
    values(heater),
    Array.from({ length: 512 }, (_, index) => transitions.get(index) ?? 0),
  );
  assert.deepEqual(
    values(heater, 'x'),
    values(
      initial.source.series.find((series) => series.id === 'thermal-heater'),
      'x',
    ),
  );
  checks.push(
    'The real Difference operator exports every heater delta, including all seven independently specified transitions.',
  );

  await page.getByRole('button', { name: 'Data Worker', exact: true }).click();
  await page
    .getByLabel('Workspace layout', { exact: true })
    .selectOption('notebook');
  const switched = await snapshot('layout-snapshot.json');
  assert.deepEqual(
    switched.doc,
    computed.doc,
    'Presentation switching changed the processing graph.',
  );
  assert.deepEqual(
    switched.source,
    computed.source,
    'Presentation switching changed source evidence.',
  );
  assert.deepEqual(
    switched.result,
    computed.result,
    'Presentation switching changed numerical results.',
  );
  await page
    .getByRole('button', { name: 'Signal Engineer', exact: true })
    .click();
  await page.getByRole('tab', { name: 'Signals', exact: true }).click();
  await canvas.waitFor({ state: 'visible' });
  checks.push(
    'Switching presentation preserves the exported graph, original source and computed values.',
  );
  assert.deepEqual(
    forbiddenRequests,
    [],
    'The local demo attempted an external or API request.',
  );
  assert.deepEqual(browserErrors, [], 'The browser reported errors.');
} catch (error) {
  failure = error;
} finally {
  const errors = failure ? [failure] : [];
  if (output && page && !page.isClosed()) {
    try {
      await page.screenshot({
        path: path.join(output, 'workbench.png'),
        fullPage: true,
        timeout,
      });
    } catch (error) {
      errors.push(
        new Error('Failed to capture the Workbench screenshot.', {
          cause: error,
        }),
      );
    }
  }
  for (const [name, resource] of [
    ['context', context],
    ['browser', browser],
    ['server', server],
  ]) {
    if (!resource) continue;
    try {
      await resource.close();
    } catch (error) {
      errors.push(new Error(`Failed to close ${name}.`, { cause: error }));
    }
  }
  if (forbiddenRequests.length && !errors.length)
    errors.push(
      new Error('The local demo attempted an external or API request.'),
    );
  if (browserErrors.length && !errors.length)
    errors.push(new Error('The browser reported errors.'));
  failure =
    errors.length === 1
      ? errors[0]
      : errors.length
        ? new AggregateError(errors, 'Standalone demo acceptance failed.')
        : null;
}

const result = {
  status: failure ? 'failed' : 'passed',
  serving: options.has('--built') ? 'built' : 'development',
  playwright: '1.62.1',
  durationMs: Math.round(performance.now() - started),
  checks,
  forbiddenRequests,
  browserErrors,
  ...(failure ? { error: failure.stack ?? String(failure) } : {}),
};
try {
  if (output) {
    for (const [name, bytes] of snapshots)
      await writeFile(path.join(output, name), bytes);
    await writeFile(
      path.join(output, 'result.json'),
      `${JSON.stringify(result, null, 2)}\n`,
    );
  }
} catch (error) {
  failure = failure
    ? new AggregateError(
        [failure, error],
        'Acceptance and artifact export failed.',
      )
    : error;
  result.status = 'failed';
  result.error = failure.stack ?? String(failure);
}
console.log(JSON.stringify(result, null, 2));
if (failure) throw failure;
