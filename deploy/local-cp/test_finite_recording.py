import copy
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
HELPER = Path(__file__).with_name("finite-recording.mjs")
FILES = (
    "workbench.png",
    "input-episode.json",
    "opentsx-modelling-result.json",
    "result.json",
    "workbench.webm",
    "trace.zip",
)


class FiniteRecordingTests(unittest.TestCase):
    def fixture(self):
        source = {
            "revision": "a" * 40,
            "source_sha256": "b" * 64,
            "source_date_epoch": "1795003200",
            "dirty": False,
            "version": "1.0.0",
            "build_inputs": {key: "c" * 64 for key in ("deps", "wheel", "frontend")},
            "images": {
                "python": "python@sha256:" + "d" * 64,
                "node": "node@sha256:" + "e" * 64,
            },
        }
        runtime = {
            **copy.deepcopy(source),
            "status": "ready",
            "profile": "finite",
            "frontend_sha256": "f" * 64,
            "runtime_lock_sha256": "0" * 64,
            "python": "3.12.13",
        }
        return {
            "sourceBefore": source,
            "sourceAfter": copy.deepcopy(source),
            "runtimeBefore": runtime,
            "runtimeAfter": copy.deepcopy(runtime),
            "tools": {
                "node": "v24.22.2",
                "playwright": "1.62.1",
                "chromium": "149.0.7827.6",
            },
            "sourceHash": "1" * 64,
        }

    def output(self, directory):
        output = Path(directory) / "capture"
        output.mkdir()
        for name in FILES:
            (output / name).write_bytes(b"unit-test artifact bytes")
        (output / "result.json").write_text(
            json.dumps(
                {
                    "status": "passed",
                    "forbiddenRequests": [],
                    "browserErrors": [],
                    "numericalResponses": [200],
                    "sourceHash": "1" * 64,
                }
            )
        )
        return output

    def run_node(self, code, *arguments, env=None):
        node = shutil.which("node")
        self.assertIsNotNone(node, "Node is required for recording regression tests")
        prelude = """
import assert from 'node:assert/strict';
import * as fs from 'node:fs/promises';
import path from 'node:path';
const [moduleUrl, raw, output, ...args] = process.argv.slice(1);
const helper = await import(moduleUrl);
const fixture = JSON.parse(raw);
"""
        result = subprocess.run(
            [
                node,
                "--input-type=module",
                "-e",
                prelude + code,
                HELPER.as_uri(),
                json.dumps(self.fixture()),
                *map(str, arguments),
            ],
            cwd=ROOT,
            env=env,
            text=True,
            capture_output=True,
            check=False,
        )
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_output_is_exclusive_and_existing_files_survive(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "nested" / "capture"
            self.run_node(
                """
await helper.prepareOutput(output);
await fs.writeFile(path.join(output, 'keep.txt'), 'keep');
await assert.rejects(helper.prepareOutput(output), {code: 'EEXIST'});
assert.equal(await fs.readFile(path.join(output, 'keep.txt'), 'utf8'), 'keep');
""",
                output,
            )

    def test_local_provenance_uses_the_existing_python_adapter(self):
        with tempfile.TemporaryDirectory() as directory:
            binary = Path(directory) / "python3"
            binary.write_text(
                f"#!{sys.executable}\n"
                "import json, pathlib, sys\n"
                "assert pathlib.Path(sys.argv[1]).name == 'recording_provenance.py'\n"
                "assert sys.argv[2] == 'requested-output'\n"
                f"print({json.dumps(json.dumps(self.fixture()['sourceBefore']))})\n"
            )
            binary.chmod(0o755)
            self.run_node(
                "assert.deepEqual(await helper.localProvenance('requested-output'), fixture.sourceBefore);",
                env={**os.environ, "PATH": directory + os.pathsep + os.environ["PATH"]},
            )

    def test_runtime_provenance_is_curated_and_rejects_invalid_or_stale_fields(self):
        self.run_node(
            """
const runtime = structuredClone(fixture.runtimeBefore);
runtime.unrelated = 'unwanted metadata';
assert.deepEqual(helper.validateProvenance(runtime, fixture.sourceBefore), fixture.runtimeBefore);
const changes = [
  [['status'], 'starting'], [['profile'], 'connected'], [['dirty'], true],
  [['revision'], '2'.repeat(40)], [['source_sha256'], '2'.repeat(64)],
  [['source_date_epoch'], '1795003201'], [['source_date_epoch'], 1795003200],
  [['version'], '2.0.0'], [['python'], 'unknown'], [['python'], null],
  [['frontend_sha256'], 'invalid'], [['runtime_lock_sha256'], 'F'.repeat(64)],
  [['images', 'python'], 'python@sha256:' + '2'.repeat(64)],
  [['images', 'node'], 'node@sha256:' + '2'.repeat(64)],
  ...['deps', 'wheel', 'frontend'].map(key => [['build_inputs', key], '2'.repeat(64)]),
];
for (const [keys, value] of changes) {
  const wrong = structuredClone(runtime);
  let target = wrong;
  for (const key of keys.slice(0, -1)) target = target[key];
  target[keys.at(-1)] = value;
  assert.throws(() => helper.validateProvenance(wrong, fixture.sourceBefore));
}
for (const key of Object.keys(fixture.runtimeBefore)) {
  const missing = structuredClone(runtime);
  delete missing[key];
  assert.throws(() => helper.validateProvenance(missing, fixture.sourceBefore));
}
const secret = 'unexpected-private-value';
assert.throws(() => helper.validateProvenance({...runtime, revision: secret}, fixture.sourceBefore), error => !error.stack.includes(secret));
"""
        )

    def test_health_fetch_rejects_redirects_and_uses_a_bounded_host_request(self):
        self.run_node(
            """
const origin = 'http://127.0.0.1:18081';
let requested = false;
const timeout = AbortSignal.timeout;
AbortSignal.timeout = value => {assert.equal(value, 5000); return timeout(value);};
globalThis.fetch = async (url, options) => {
  assert.equal(url, origin + '/analysis/v1/health');
  assert.equal(options.redirect, 'error');
  assert.ok(options.signal instanceof AbortSignal);
  requested = true;
  return {ok:true, redirected:false, json:async () => fixture.runtimeBefore};
};
assert.deepEqual(await helper.runtimeProvenance(origin, fixture.sourceBefore), fixture.runtimeBefore);
assert.ok(requested);
for (const response of [{ok:false, redirected:false}, {ok:true, redirected:true}]) {
  globalThis.fetch = async () => response;
  await assert.rejects(helper.runtimeProvenance(origin, fixture.sourceBefore));
}
globalThis.fetch = async () => {throw new TypeError('fetch failed');};
await assert.rejects(helper.runtimeProvenance(origin, fixture.sourceBefore));
"""
        )

    def capture_script(self):
        return """
const events = [];
const failure = args[0];
await fs.mkdir(path.join(output, 'video'));
const action = name => {events.push(name); if (failure === name) throw new Error(name);};
const page = {
  video: () => {action('video'); return {
    saveAs: async value => {assert.equal(value, path.join(output, 'workbench.webm')); action('save');},
    delete: async () => action('delete'),
  };},
  isClosed: () => false,
  screenshot: async value => {assert.equal(value.path, path.join(output, 'workbench.png')); assert.equal(value.fullPage, true); action('screenshot');},
};
const context = {
  tracing: {stop: async value => {assert.equal(value.path, path.join(output, 'trace.zip')); action('trace');}},
  close: async () => action('context'),
};
const browser = {close: async () => action('browser')};
const capture = helper.finishCapture({page, context, browser, output, tracingStarted:true});
if (failure) await assert.rejects(capture, AggregateError);
else await capture;
assert.equal(events.at(-1), 'browser');
assert.ok(events.indexOf('trace') < events.indexOf('context'));
if (failure === 'context') assert.ok(!events.includes('save') && !events.includes('delete'));
else {
  assert.ok(events.indexOf('context') < events.indexOf('save'));
  assert.ok(events.indexOf('save') < events.indexOf('delete'));
  assert.ok(events.indexOf('delete') < events.indexOf('browser'));
}
"""

    def test_capture_flushes_trace_then_video_before_browser_close(self):
        with tempfile.TemporaryDirectory() as directory:
            self.run_node(self.capture_script(), directory, "")
            self.assertFalse((Path(directory) / "video").exists())

    def test_capture_failures_propagate_and_remaining_cleanup_runs(self):
        for failure in ("screenshot", "trace", "save", "delete", "context", "browser"):
            with self.subTest(
                failure=failure
            ), tempfile.TemporaryDirectory() as directory:
                self.run_node(self.capture_script(), directory, failure)

    def test_early_failure_without_a_page_still_closes_existing_resources(self):
        self.run_node(
            """
await helper.finishCapture({output});
const events = [];
await helper.finishCapture({output, context:{close:async () => events.push('context')}, browser:{close:async () => events.push('browser')}});
assert.deepEqual(events, ['context', 'browser']);
"""
        )

    def test_publication_records_exact_files_and_verification_checks_every_hash(self):
        with tempfile.TemporaryDirectory() as directory:
            output = self.output(directory)
            self.run_node(
                """
const manifest = await helper.publishRecording(output, fixture);
assert.equal(manifest.schema, 'opentsx.demo-recording.v1');
assert.equal(manifest.status, 'passed');
assert.equal(manifest.journey, 'finite-arima-random-walk-v1');
assert.deepEqual(manifest.viewport, {width:1600, height:1100});
assert.deepEqual(Object.keys(manifest.files).sort(), ['workbench.png','input-episode.json','opentsx-modelling-result.json','result.json','workbench.webm','trace.zip'].sort());
for (const value of Object.values(manifest.files)) {
  assert.ok(value.bytes > 0);
  assert.match(value.sha256, /^[0-9a-f]{64}$/);
}
assert.deepEqual(await helper.verifyRecording(output), manifest);
assert.ok(!(await fs.readdir(output)).includes('.recording.json.tmp'));
""",
                output,
            )

    def test_changed_source_or_runtime_cannot_publish_an_approval(self):
        for change in ("source", "build", "runtime"):
            with self.subTest(
                change=change
            ), tempfile.TemporaryDirectory() as directory:
                output = self.output(directory)
                self.run_node(
                    """
if (args[0] === 'source') fixture.sourceAfter.source_sha256 = '2'.repeat(64);
if (args[0] === 'build') fixture.sourceAfter.build_inputs.frontend = '2'.repeat(64);
if (args[0] === 'runtime') fixture.runtimeAfter.frontend_sha256 = '2'.repeat(64);
await assert.rejects(helper.publishRecording(output, fixture));
await assert.rejects(fs.stat(path.join(output, 'recording.json')), {code:'ENOENT'});
""",
                    output,
                    change,
                )

    def test_failed_or_incomplete_browser_acceptance_cannot_publish_an_approval(self):
        changes = {
            "status": "failed",
            "forbiddenRequests": [{"method": "GET"}],
            "browserErrors": ["browser failed"],
            "numericalResponses": [200, 200],
            "sourceHash": "2" * 64,
        }
        for key, value in changes.items():
            with self.subTest(key=key), tempfile.TemporaryDirectory() as directory:
                output = self.output(directory)
                data = json.loads((output / "result.json").read_text())
                data[key] = value
                (output / "result.json").write_text(json.dumps(data))
                self.run_node(
                    """
await assert.rejects(helper.publishRecording(output, fixture));
await assert.rejects(fs.stat(path.join(output, 'recording.json')), {code:'ENOENT'});
""",
                    output,
                )

    def test_missing_empty_or_symbolic_artifacts_cannot_publish_an_approval(self):
        for change in ("missing", "empty", "symlink"):
            with self.subTest(
                change=change
            ), tempfile.TemporaryDirectory() as directory:
                output = self.output(directory)
                artifact = output / "workbench.webm"
                artifact.unlink()
                if change == "empty":
                    artifact.touch()
                elif change == "symlink":
                    artifact.symlink_to(output / "trace.zip")
                self.run_node(
                    """
await assert.rejects(helper.publishRecording(output, fixture));
await assert.rejects(fs.stat(path.join(output, 'recording.json')), {code:'ENOENT'});
""",
                    output,
                )

    def test_verification_rejects_tampering_missing_files_and_unsafe_inventory(self):
        for change in ("tamper", "missing", "empty", "symlink", "inventory", "schema"):
            with self.subTest(
                change=change
            ), tempfile.TemporaryDirectory() as directory:
                output = self.output(directory)
                self.run_node(
                    """
const manifest = await helper.publishRecording(output, fixture);
if (args[0] === 'tamper') await fs.writeFile(path.join(output, 'workbench.webm'), 'changed artifact');
if (args[0] === 'missing') await fs.unlink(path.join(output, 'workbench.png'));
if (args[0] === 'empty') await fs.writeFile(path.join(output, 'trace.zip'), '');
if (args[0] === 'symlink') {
  await fs.unlink(path.join(output, 'workbench.webm'));
  await fs.symlink(path.join(output, 'trace.zip'), path.join(output, 'workbench.webm'));
}
if (args[0] === 'inventory') {
  delete manifest.files['workbench.webm'];
  manifest.files['../outside.webm'] = {bytes:1, sha256:'1'.repeat(64)};
}
if (args[0] === 'schema') manifest.schema = 'unknown';
if (['inventory', 'schema'].includes(args[0])) await fs.writeFile(path.join(output, 'recording.json'), JSON.stringify(manifest));
await assert.rejects(helper.verifyRecording(output));
""",
                    output,
                    change,
                )

    def test_atomic_publication_failure_preserves_existing_destination(self):
        with tempfile.TemporaryDirectory() as directory:
            output = self.output(directory)
            (output / "recording.json").mkdir()
            self.run_node(
                """
await assert.rejects(helper.publishRecording(output, fixture), AggregateError);
assert.ok((await fs.stat(path.join(output, 'recording.json'))).isDirectory());
await assert.rejects(helper.verifyRecording(output));
await assert.rejects(fs.stat(path.join(output, '.recording.json.tmp')), {code:'ENOENT'});
""",
                output,
            )


if __name__ == "__main__":
    unittest.main()
