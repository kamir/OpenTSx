import assert from 'node:assert/strict';
import { execFile } from 'node:child_process';
import { createHash } from 'node:crypto';
import { constants } from 'node:fs';
import { link, mkdir, open, rmdir, unlink } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { promisify } from 'node:util';

const HASH = /^[0-9a-f]{64}$/;
const FILES = [
  'workbench.png',
  'input-episode.json',
  'opentsx-modelling-result.json',
  'result.json',
  'workbench.webm',
  'trace.zip',
];
const VIEWPORT = { width: 1600, height: 1100 };

export async function prepareOutput(output) {
  await mkdir(path.dirname(output), { recursive: true });
  await mkdir(output);
}

export async function localProvenance(output) {
  const script = fileURLToPath(
    new URL('./recording_provenance.py', import.meta.url),
  );
  const { stdout } = await promisify(execFile)('python3', [script, output]);
  return sourceMetadata(JSON.parse(stdout));
}

function sourceMetadata(source) {
  assert.ok(source && typeof source === 'object', 'Missing source provenance.');
  assert.ok(
    source.dirty === false,
    'Recording requires clean source provenance.',
  );
  assert.ok(
    typeof source.revision === 'string' &&
      /^[0-9a-f]{40}$/.test(source.revision),
    'Invalid source revision.',
  );
  assert.ok(
    typeof source.source_sha256 === 'string' && HASH.test(source.source_sha256),
    'Invalid source digest.',
  );
  assert.ok(
    typeof source.source_date_epoch === 'string' &&
      /^[0-9]+$/.test(source.source_date_epoch),
    'Invalid source date epoch.',
  );
  assert.ok(
    typeof source.version === 'string' &&
      /^[0-9]+\.[0-9]+\.[0-9]+$/.test(source.version),
    'Invalid package version.',
  );
  const build_inputs = {};
  for (const kind of ['deps', 'wheel', 'frontend']) {
    const value = source.build_inputs?.[kind];
    assert.ok(
      typeof value === 'string' && HASH.test(value),
      `Invalid ${kind} build input.`,
    );
    build_inputs[kind] = value;
  }
  const images = {};
  for (const kind of ['python', 'node']) {
    const value = source.images?.[kind];
    assert.ok(
      typeof value === 'string' &&
        new RegExp(`^${kind}@sha256:[0-9a-f]{64}$`).test(value),
      `Invalid ${kind} image.`,
    );
    images[kind] = value;
  }
  return {
    revision: source.revision,
    source_sha256: source.source_sha256,
    source_date_epoch: source.source_date_epoch,
    build_inputs,
    images,
    dirty: false,
    version: source.version,
  };
}

export function validateProvenance(runtime, local) {
  const expected = sourceMetadata(local);
  const observed = sourceMetadata(runtime);
  assert.ok(runtime.status === 'ready', 'The finite runtime is not ready.');
  assert.ok(
    runtime.profile === 'finite',
    'The runtime does not use the finite profile.',
  );
  assert.ok(
    JSON.stringify(observed) === JSON.stringify(expected),
    'Runtime provenance differs from the local source.',
  );
  for (const key of ['frontend_sha256', 'runtime_lock_sha256'])
    assert.ok(
      typeof runtime[key] === 'string' && HASH.test(runtime[key]),
      `Invalid runtime ${key}.`,
    );
  assert.ok(
    typeof runtime.python === 'string' &&
      /^3\.[0-9]+\.[0-9]+$/.test(runtime.python),
    'Invalid runtime Python version.',
  );
  return {
    status: 'ready',
    profile: 'finite',
    ...observed,
    frontend_sha256: runtime.frontend_sha256,
    runtime_lock_sha256: runtime.runtime_lock_sha256,
    python: runtime.python,
  };
}

export async function runtimeProvenance(origin, local) {
  const response = await fetch(`${origin}/analysis/v1/health`, {
    redirect: 'error',
    signal: AbortSignal.timeout(5000),
  });
  assert.ok(
    response.ok && !response.redirected,
    'The finite runtime health request failed.',
  );
  return validateProvenance(await response.json(), local);
}

export async function finishCapture({
  page,
  context,
  browser,
  output,
  tracingStarted,
}) {
  const errors = [];
  let video;
  if (page) {
    try {
      video = page.video();
      assert.ok(video, 'The finite Workbench video is missing.');
    } catch (error) {
      errors.push(error);
    }
    try {
      assert.ok(
        !page.isClosed(),
        'The finite Workbench page closed before capture.',
      );
      await page.screenshot({
        path: path.join(output, 'workbench.png'),
        fullPage: true,
      });
    } catch (error) {
      errors.push(error);
    }
  }
  if (context && tracingStarted) {
    try {
      await context.tracing.stop({ path: path.join(output, 'trace.zip') });
    } catch (error) {
      errors.push(error);
    }
  }
  let contextClosed = false;
  if (context) {
    try {
      await context.close();
      contextClosed = true;
    } catch (error) {
      errors.push(error);
    }
  }
  if (video && contextClosed) {
    for (const action of [
      () => video.saveAs(path.join(output, 'workbench.webm')),
      () => video.delete(),
      () => rmdir(path.join(output, 'video')),
    ]) {
      try {
        await action();
      } catch (error) {
        errors.push(error);
      }
    }
  }
  if (browser) {
    try {
      await browser.close();
    } catch (error) {
      errors.push(error);
    }
  }
  if (errors.length)
    throw new AggregateError(errors, 'Finite recording capture failed.');
}

async function fileBytes(output, name) {
  const file = await open(
    path.join(output, name),
    constants.O_RDONLY | constants.O_NOFOLLOW,
  );
  try {
    const stat = await file.stat();
    assert.ok(
      stat.isFile() && stat.size > 0,
      `Recording file ${name} must be a nonempty regular file.`,
    );
    const bytes = await file.readFile();
    assert.ok(
      bytes.length === stat.size,
      `Recording file ${name} changed while reading.`,
    );
    return bytes;
  } finally {
    await file.close();
  }
}

function toolsMetadata(tools) {
  assert.ok(
    tools && typeof tools === 'object',
    'Missing recording tool versions.',
  );
  const result = {};
  for (const key of ['node', 'playwright', 'chromium']) {
    assert.ok(
      typeof tools[key] === 'string' && tools[key].length > 0,
      `Missing ${key} version.`,
    );
    result[key] = tools[key];
  }
  return result;
}

export async function publishRecording(
  output,
  { sourceBefore, sourceAfter, runtimeBefore, runtimeAfter, tools, sourceHash },
) {
  const source = sourceMetadata(sourceBefore);
  assert.ok(
    JSON.stringify(source) === JSON.stringify(sourceMetadata(sourceAfter)),
    'Source provenance changed during recording.',
  );
  const runtime = validateProvenance(runtimeBefore, source);
  const after = validateProvenance(runtimeAfter, sourceAfter);
  assert.ok(
    JSON.stringify(runtime) === JSON.stringify(after),
    'Runtime provenance changed during recording.',
  );
  assert.ok(
    typeof sourceHash === 'string' && HASH.test(sourceHash),
    'Invalid numerical source hash.',
  );
  const files = {};
  for (const name of FILES) {
    const bytes = await fileBytes(output, name);
    if (name === 'result.json') {
      const result = JSON.parse(bytes.toString('utf8'));
      assert.ok(
        result.status === 'passed',
        'Finite browser acceptance did not pass.',
      );
      assert.ok(
        Array.isArray(result.forbiddenRequests) &&
          result.forbiddenRequests.length === 0,
        'Finite browser acceptance contains forbidden requests.',
      );
      assert.ok(
        Array.isArray(result.browserErrors) &&
          result.browserErrors.length === 0,
        'Finite browser acceptance contains browser errors.',
      );
      assert.ok(
        Array.isArray(result.numericalResponses) &&
          result.numericalResponses.length === 1 &&
          result.numericalResponses[0] === 200,
        'Finite browser acceptance requires one successful numerical response.',
      );
      assert.ok(
        result.sourceHash === sourceHash,
        'Finite browser acceptance source hash differs.',
      );
    }
    files[name] = {
      bytes: bytes.length,
      sha256: createHash('sha256').update(bytes).digest('hex'),
    };
  }
  const manifest = {
    schema: 'opentsx.demo-recording.v1',
    status: 'passed',
    journey: 'finite-arima-random-walk-v1',
    source,
    runtime,
    tools: toolsMetadata(tools),
    viewport: VIEWPORT,
    files,
  };
  const temporary = path.join(output, '.recording.json.tmp');
  const destination = path.join(output, 'recording.json');
  const file = await open(temporary, 'wx');
  let published = false;
  try {
    await file.writeFile(JSON.stringify(manifest, null, 2) + '\n');
    await file.close();
    await link(temporary, destination);
    published = true;
    await unlink(temporary);
  } catch (error) {
    const errors = [error];
    for (const cleanup of [
      () => file.close(),
      ...(published ? [() => unlink(destination)] : []),
      () => unlink(temporary),
    ]) {
      try {
        await cleanup();
      } catch (failure) {
        if (failure.code !== 'ENOENT') errors.push(failure);
      }
    }
    throw new AggregateError(
      errors,
      'Failed to publish the recording manifest.',
    );
  }
  return manifest;
}

export async function verifyRecording(output) {
  const manifest = JSON.parse(
    (await fileBytes(output, 'recording.json')).toString('utf8'),
  );
  assert.ok(
    manifest.schema === 'opentsx.demo-recording.v1' &&
      manifest.status === 'passed' &&
      manifest.journey === 'finite-arima-random-walk-v1',
    'Invalid recording manifest.',
  );
  assert.ok(
    JSON.stringify(manifest.viewport) === JSON.stringify(VIEWPORT),
    'Invalid recording viewport.',
  );
  sourceMetadata(manifest.source);
  validateProvenance(manifest.runtime, manifest.source);
  toolsMetadata(manifest.tools);
  assert.ok(
    manifest.files &&
      JSON.stringify(Object.keys(manifest.files).sort()) ===
        JSON.stringify([...FILES].sort()),
    'Invalid recording file inventory.',
  );
  for (const name of FILES) {
    const expected = manifest.files[name];
    assert.ok(
      expected &&
        Number.isSafeInteger(expected.bytes) &&
        expected.bytes > 0 &&
        typeof expected.sha256 === 'string' &&
        HASH.test(expected.sha256),
      `Invalid recording file metadata for ${name}.`,
    );
    const bytes = await fileBytes(output, name);
    assert.ok(
      bytes.length === expected.bytes &&
        createHash('sha256').update(bytes).digest('hex') === expected.sha256,
      `Recording integrity check failed for ${name}.`,
    );
  }
  return manifest;
}
