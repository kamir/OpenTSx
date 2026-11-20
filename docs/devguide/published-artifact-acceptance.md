# Verify the packaged tools image

Verify download, startup and Windpark replay for released OpenTSx Tools 3.1.0;
local execution was checked with Docker 29.3.0.

You need Bash, curl, Python 3.12 or newer and a running native AMD64 or ARM64
Docker daemon for the release-file path. The helper uses Python's standard
library. Source edit checks need a checkout and Python.
The published artifact is `ghcr.io/kamir/opentsx-tools`, an OCI image.
The [Image workflow](../../.github/workflows/image.yml) builds large images in
CI. It checks a native AMD64 candidate before image publication. After
publication, fresh native AMD64 and ARM64 jobs download the returned registry
digest and run the same acceptance helper.

## Download and accept the released image

Run this Bash block from a directory outside the checkout. It creates a new
`opentsx-tools-3.1.0` directory, downloads the checker and license files from the
[stable release][release], verifies the checker checksum, and pulls the
immutable image digest. The Docker daemon's architecture selects the platform.

```bash
set -euo pipefail

mkdir opentsx-tools-3.1.0
cd opentsx-tools-3.1.0
tools_release='https://github.com/kamir/OpenTSx/releases/download/v3.1.0'
for asset in check_tools_image.py LICENSE NOTICE SHA256SUMS; do
  curl --disable --fail --location --silent --show-error \
    --output "$asset" "$tools_release/$asset"
done
python3 - <<'PY'
import hashlib
from pathlib import Path

expected = '970b4930b1fda713ac7c313ebb115bfdde63ae709b48f34bf776d8153d980333'
actual = hashlib.sha256(Path('check_tools_image.py').read_bytes()).hexdigest()
if actual != expected:
    raise SystemExit('Checker SHA-256 does not match the 3.1.0 release.')
print('Verified the 3.1.0 checker SHA-256.')
PY
tools_platform="linux/$(docker version --format '{{.Server.Arch}}')"
tools_image='ghcr.io/kamir/opentsx-tools@sha256:'
tools_image+='f87293656e730d6a3b15604d44d2f890e7d600d19787fe0e464ee62ea810888a'
python3 check_tools_image.py --image "$tools_image" \
  --platform "$tools_platform" --version 3.1.0 \
  --revision cfd9ab64b7cadf10146edc5f99d3167452289224 \
  --output acceptance
python3 -m json.tool acceptance/verification.json
```

The helper starts two isolated containers, stores episodes in the first and
reopens them in the second. It saves logs, image identity, numerical reports
and `verification.json` under `opentsx-tools-3.1.0/acceptance`, then removes
its containers and volume. Both demo containers run with networking disabled.
This path needs no Maven, JDK or existing CP cluster.

## Inspect the released acceptance evidence

The [full source CI][source-ci] passed all 27 jobs on tagged revision
`cfd9ab64b7cadf10146edc5f99d3167452289224`. The [Image run][image-ci] published
version 3.1.0 and accepted that same digest on both native runner types:

| Platform | Native runner | Retained receipt |
| --- | --- | --- |
| `linux/amd64` | `ubuntu-24.04` | [AMD64 verification][amd64-proof] |
| `linux/arm64` | `ubuntu-24.04-arm` | [ARM64 verification][arm64-proof] |

Both runs and anonymous local ARM64 execution produced identical report bytes:
90 episodes, 900 values and 90 episode payload hashes. The [report][report]
SHA-256 is
`47f0f768c7046e4be2fc8574f935dfb85ce6b6205cc626dd292b7ade2b3872cd`.
The release retains these receipts, CI identities and `SHA256SUMS` as download
assets independently of CI artifact expiration. These checks cover native
Linux runner and Docker execution.

## Run the offline edit checks

Run from the repository root. These checks need no Docker daemon, network,
Maven or scientific packages:

```bash
python3 -m unittest discover -s deploy/docker -p 'test_*.py' -v
```

They exercise the packaged dispatcher, publication gates, Docker failure and
cleanup paths, image identity and independently calculated numerical values.
Docker calls use deterministic fixtures in these tests.

Check the current source release identity without Maven or network access:

```bash
python3 deploy/docker/check_tools_release.py
```

The checker reads the canonical POM version and requires stable, matching
versions for reactor parents, modules and internal dependencies. The image
workflow runs it before the Maven build and checks the staged internal JAR
paths and embedded Maven identities before building the candidate. Staging
uses pinned dependency-plugin 3.7.0 with runtime scope, excluding provided and
test dependencies. The build
uses the verified CI Maven mirror, a workload-specific dependency cache and
retains sanitized download diagnostics in `maven-downloads-opentsx-tools`.

## Inspect the rejected legacy artifact

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

The image supplies the named `opentsx` account for UID and GID 10001 so Hadoop
can resolve the effective user. Account files are prepared on the native build
platform, then copied into the target image. Runtime stages need no emulation.

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

[release]: https://github.com/kamir/OpenTSx/releases/tag/v3.1.0
[source-ci]: https://github.com/kamir/OpenTSx/actions/runs/44793425324
[image-ci]: https://github.com/kamir/OpenTSx/actions/runs/44801869628
[amd64-proof]: https://github.com/kamir/OpenTSx/releases/download/v3.1.0/verification-linux-amd64.json
[arm64-proof]: https://github.com/kamir/OpenTSx/releases/download/v3.1.0/verification-linux-arm64.json
[report]: https://github.com/kamir/OpenTSx/releases/download/v3.1.0/windpark-report.json
