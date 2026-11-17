import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const options = {};
for (let index = 2; index < process.argv.length; index += 2) {
  const key = process.argv[index];
  if (!['--base', '--fixture', '--credentials', '--output', '--phase', '--playwright-module'].includes(key)
    || !process.argv[index + 1] || options[key]) throw new Error('Expected unique named browser arguments.');
  options[key] = process.argv[index + 1];
}
for (const key of ['--base', '--fixture', '--credentials', '--output', '--phase']) {
  if (!options[key]) throw new Error(`Missing ${key}.`);
}
const moduleName = options['--playwright-module'] ?? 'playwright';
if (require(path.join(moduleName, 'package.json')).version !== '1.62.1') throw new Error('Playwright 1.62.1 is required.');
const { chromium } = require(moduleName);
const fixture = JSON.parse(fs.readFileSync(options['--fixture'], 'utf8'));
const credentials = JSON.parse(fs.readFileSync(options['--credentials'], 'utf8'));
const directory = options['--output'];
const phase = options['--phase'];
if (!['initial', 'retry', 'restart'].includes(phase)) throw new Error('Unknown browser phase.');
fs.mkdirSync(directory, { recursive: true });
const initialFile = path.join(directory, 'deployment-initial.json');
const initial = phase === 'initial' ? null : JSON.parse(fs.readFileSync(initialFile, 'utf8'));
const browser = await chromium.launch({ headless: true });
const context = await browser.newContext({ viewport: { width: 1440, height: 1100 } });
const page = await context.newPage();
page.setDefaultTimeout(30_000);
const captured = [];
let awaitedDeployment = initial?.id ?? null;
let observedRows = [];
let resolveCompletion;
const completion = new Promise((resolve) => { resolveCompletion = resolve; });
const observe = () => {
  const row = observedRows.find((value) => value.id === awaitedDeployment && value.state === 'completed');
  if (row) resolveCompletion(row);
};
page.on('response', async (response) => {
  if (response.ok() && response.request().method() === 'GET'
    && new URL(response.url()).pathname === '/api/v1/dags/wayang-rust/deployments') {
    const body = await response.json();
    observedRows = body.deployments;
    observe();
  }
});
page.on('request', (request) => {
  if (request.method() === 'POST' && new URL(request.url()).pathname === '/api/v1/dags/wayang-rust/deploy') {
    const body = JSON.parse(request.postData());
    if (!Number.isInteger(body.flowId) || typeof body.dagText !== 'string') throw new Error('Unexpected deployment body.');
    captured.push(body);
  }
});
try {
  await page.goto(`${options['--base']}/login`);
  await page.getByLabel('Email address', { exact: true }).fill(credentials.email);
  await page.getByLabel('Password', { exact: true }).fill(credentials.password);
  await page.getByRole('button', { name: 'Sign in', exact: true }).click();
  await page.waitForURL('**/dashboard');
  await page.goto(`${options['--base']}/workbench${initial ? `?flow=${initial.flowId}` : ''}`);
  if (phase === 'initial') {
    await page.getByRole('button', { name: 'Load sample bucket', exact: true }).first().click();
    await page.getByRole('tab', { name: 'Filter and conversion', exact: true }).click();
    const range = page.locator('.wb-op').filter({ has: page.getByRole('button', { name: 'Plausible range', exact: true }) });
    await range.getByLabel('min', { exact: true }).fill('0');
    await range.getByLabel('max', { exact: true }).fill('100');
    await range.getByRole('combobox').selectOption('gap');
    await range.getByRole('button', { name: 'Plausible range', exact: true }).click();
    const convert = page.locator('.wb-op').filter({ has: page.getByRole('button', { name: 'Convert unit', exact: true }) });
    await convert.getByLabel('factor', { exact: true }).fill('0.001');
    await convert.getByLabel('offset', { exact: true }).fill('0');
    await convert.getByLabel('unit', { exact: true }).fill('MW');
    await convert.getByRole('button', { name: 'Convert unit', exact: true }).click();
    const resample = page.locator('.wb-op').filter({ has: page.getByRole('button', { name: 'Resample', exact: true }) });
    await resample.getByLabel('every', { exact: true }).fill('20');
    await resample.getByRole('combobox').selectOption('MEAN');
    await resample.getByRole('button', { name: 'Resample', exact: true }).click();
  } else {
    await page.getByRole('button', { name: `Save flow ${initial.flowId}`, exact: true }).waitFor();
  }
  await page.getByRole('tab', { name: 'Smart Topic', exact: true }).click();
  await page.getByLabel('Deployment target', { exact: true }).selectOption('wayang-rust');
  const panel = page.locator('fieldset').filter({ has: page.locator('legend', { hasText: /^Bounded Wayang on Rust$/ }) });
  if (phase === 'initial') {
    await panel.getByLabel('Whole input topics', { exact: true }).fill(fixture.inputTopics.join('\n'));
    await panel.getByLabel('Selected series IDs', { exact: true }).fill(fixture.seriesIds.join('\n'));
    await panel.getByLabel('Rust output step', { exact: true }).selectOption({ index: 3 });
    await panel.getByLabel('Output system', { exact: true }).fill(fixture.system);
    await panel.getByLabel('Output signal', { exact: true }).fill(fixture.signal);
    await panel.getByLabel('Rust output topic', { exact: true }).fill(fixture.outputTopic);
  }
  const responsePromise = page.waitForResponse((response) => response.request().method() === 'POST'
    && new URL(response.url()).pathname === '/api/v1/dags/wayang-rust/deploy');
  await panel.getByRole('button', { name: 'Run bounded with Wayang', exact: true }).click();
  const response = await responsePromise;
  if (response.status() !== 202) throw new Error(`Deployment request returned HTTP ${response.status()}.`);
  let deployment = await response.json();
  awaitedDeployment = deployment.id;
  observe();
  if (captured.length !== 1) throw new Error('Expected exactly one browser deployment submission.');
  if (phase === 'initial') fs.writeFileSync(path.join(directory, 'submitted-dag.txt'), captured[0].dagText);
  else if (captured[0].dagText !== fs.readFileSync(path.join(directory, 'submitted-dag.txt'), 'utf8')) {
    throw new Error('Completed retry changed the original UI graph text.');
  }
  await panel.getByText(`Bounded deployment ${deployment.id}: completed`, { exact: true }).waitFor({ timeout: 600_000 });
  if (deployment.state !== 'completed') deployment = await completion;
  if (deployment.state !== 'completed' || deployment.assignments.length !== 3) throw new Error('Three workers did not complete.');
  const hash = crypto.createHash('sha256').update(captured[0].dagText, 'utf8').digest('hex');
  if (deployment.specHash !== hash) throw new Error('The completion has another original source hash.');
  const actual = deployment.assignments.map((assignment) => [assignment.inputTopic, assignment.partitionCount]);
  const expected = fixture.sources.map((source) => [source.topic, String(source.partitions)]);
  if (JSON.stringify(actual) !== JSON.stringify(expected)) throw new Error('Assignments split or changed a source topic inventory.');
  if (initial && JSON.stringify(deployment) !== JSON.stringify(initial)) throw new Error('Completed retry changed its durable outcome.');
  fs.writeFileSync(path.join(directory, `deployment-${phase}.json`), JSON.stringify(deployment, null, 2) + '\n');
  fs.writeFileSync(path.join(directory, `browser-${phase}.json`), JSON.stringify({ phase, deployment: deployment.id,
    flowId: deployment.flowId, state: deployment.state, specHash: hash, planHash: deployment.planHash,
    createdAtMicros: deployment.createdAtMicros, assignments: deployment.assignments.length }, null, 2) + '\n');
  await page.screenshot({ path: path.join(directory, `browser-${phase}.png`), fullPage: true });
  console.log(`Browser ${phase} verified three completed whole-topic assignments.`);
} catch (error) {
  let screenshotFailure = '';
  try {
    await page.screenshot({ path: path.join(directory, `browser-${phase}-failed.png`), fullPage: true });
  } catch {
    screenshotFailure = '; the failure screenshot could not be captured';
  }
  const detail = String(error.message).replaceAll(credentials.password, '<redacted>')
    .replace(/eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+/g, '<redacted>');
  throw new Error(`Browser ${phase} verification failed: ${detail}${screenshotFailure}`);
} finally {
  await context.close();
  await browser.close();
}
