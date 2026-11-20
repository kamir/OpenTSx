# Verify the packaged tools image

Check image identity, startup and Windpark replay with Python 3.12 or newer
and Docker; local execution was verified with Docker 29.3.0.

You need Python for the offline controls and Docker for image execution.
The published artifact is `ghcr.io/kamir/opentsx-tools`, an OCI image.
The [Image workflow](../../.github/workflows/image.yml) builds large images in
CI. It checks a native AMD64 candidate before tag publication. After
publication, fresh native AMD64 and ARM64 jobs download the returned registry
digest and run the same acceptance helper.

## Run the offline edit checks

These checks need no Docker daemon, network, Maven or scientific packages:

```bash
python3 -m unittest discover -s deploy/docker -p 'test_*.py' -v
```

They exercise the packaged dispatcher, publication gates, Docker failure and
cleanup paths, image identity and independently calculated numerical values.
Docker calls use deterministic fixtures in these tests.

## Inspect the actual published release

The published `3.0.1` image identifies source revision
`752786b422e9ef8175d208b74f26edc84100e15a`. It predates the Windpark demo and
reports `unknown command: windpark-demo`. This immutable artifact is an
executed rejection case for the gate, not a passing demo release.

Run from the repository root, using a new output directory:

```bash
tools_image='ghcr.io/kamir/opentsx-tools@sha256:'
tools_image+='d38e91925c326b8d7bb7ca49bf3391885de63d3e4cf2e8c53d72c6d9ada03bfd'
if python3 deploy/docker/check_tools_image.py --image "$tools_image" \
  --platform linux/arm64 --version 3.0.1 \
  --revision 752786b422e9ef8175d208b74f26edc84100e15a \
  --output target/published-tools-3.0.1; then
  echo 'The legacy artifact unexpectedly passed Windpark acceptance.' >&2
  exit 1
fi
python3 - <<'PY'
import json
from pathlib import Path

output = Path('target/published-tools-3.0.1')
image = json.loads((output / 'image.json').read_text())
assert image['version'] == '3.0.1'
assert image['transport'] == 'registry'
assert 'unknown command: windpark-demo' in (output / 'run.log').read_text()
assert not (output / 'verification.json').exists()
print('The downloaded legacy image was rejected without a success receipt.')
PY
```

This command requires native ARM64 Docker support. The registry download is
anonymous, matching the public package's download path. Use the CI matrix for
native execution on both architectures.

## Read the automated acceptance evidence

For each tag, the version must equal the Maven project version and the tag
must equal `v` followed by that stable version. Candidate acceptance runs
before registry login and publication. It uses an immutable local image ID.
The publication step retains provenance and SBOM output.

Published acceptance receives the digest directly from the build step and
pulls it on a fresh native runner. The helper rejects mutable tags. Version
and revision labels, architecture, image ID and default UID `10001:10001`
must match.

Two separate containers invoke the packaged `windpark-demo run` and
`windpark-demo replay` commands. Both use the same unique volume mounted at
`/tmp`, preserving absolute Iceberg warehouse paths between starts. The
image's `/tmp` mode is `1777`, so UID 10001 can write without a privileged
initialization container. Containers have no network, a read-only root and
all Linux capabilities dropped.

Acceptance requires 90 episodes and 900 values, identical report and replay
bytes, 90 payload hashes and the independent healthy, fault and curtailment
results from the [Windpark case](windpark-replay-demo.md). The checker also
verifies the fixed UTC clock, injected operations and power-curve bins outside
the image. Changed receipts or numerical values fail the job.

The workflow retains `candidate-image-acceptance` and, on tags,
`published-image-acceptance-amd64` and `published-image-acceptance-arm64`.
Output directories contain curated `image.json`, Docker logs and copied
numerical receipts. `verification.json` is written only after numerical checks
and cleanup of all owned containers and the volume pass. The image
inspection's environment is excluded from evidence.

## Resolve an acceptance failure

`image version does not match`, `image revision does not match` or
`image platform does not match` rejects the artifact before starting it.
Check the returned publication digest and OCI labels; do not substitute a
mutable tag or override the expected source revision.

`docker start failed with exit 1` retains the container output in `run.log` or
`replay.log`. The old `3.0.1` artifact fails because its packaged dispatcher
lacks the demo. A matching source checkout alone does not update that image.

`run and replay receipts differ` or an unexpected numerical field means the
artifact failed the independent oracle. Keep both receipts and logs. Cleanup
errors also fail acceptance and suppress `verification.json`.

`output already exists` preserves earlier evidence. Select a new output
directory instead of mixing results from different image checks.
