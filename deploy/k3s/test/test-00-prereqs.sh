#!/usr/bin/env bash
# Tests deploy/k3s/00-prereqs.sh: a staged kafscale-check that refuses its configuration shows its error line under
# "kafscale-check from this host", and the script exits 1; an unknown option exits 2. Copies the script into a
# temporary tree with a stand-in staged launcher and a stand-in java; needs no JDK, no kubectl, no cluster and no
# network.
#
#   bash deploy/k3s/test/test-00-prereqs.sh
set -euo pipefail
K3S_DIR="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
failures=0
MESSAGE='opentsx: opentsx.kafscale.bootstrap: "host:abc" is not host:port; expected host:port with a port from 1 to 65535, several separated by commas, such as kafscale:9092'

mkdir -p "$WORK/repo/deploy/k3s" "$WORK/repo/deploy/docker/.stage/opt/opentsx/bin" "$WORK/bin"
cp "$K3S_DIR/00-prereqs.sh" "$K3S_DIR/lib.sh" "$K3S_DIR/env.example" "$WORK/repo/deploy/k3s/"
printf '#!/usr/bin/env bash\necho %q >&2\nexit 2\n' "$MESSAGE" > "$WORK/repo/deploy/docker/.stage/opt/opentsx/bin/opentsx"
printf '#!/usr/bin/env bash\nexit 0\n' > "$WORK/bin/java"
chmod +x "$WORK/repo/deploy/docker/.stage/opt/opentsx/bin/opentsx" "$WORK/bin/java"

rc=0
env -i PATH="$WORK/bin:$PATH" HOME="$WORK" KAFSCALE_BOOTSTRAP=host:abc \
  bash "$WORK/repo/deploy/k3s/00-prereqs.sh" --no-build --no-cluster > "$WORK/out" 2>&1 || rc=$?
if (( rc != 1 )); then
  echo "FAIL staged-kafscale-check-refuses: exit $rc, expected 1"; sed 's/^/    /' "$WORK/out"; failures=$((failures + 1))
elif ! grep -qxF "    $MESSAGE" "$WORK/out"; then
  echo "FAIL staged-kafscale-check-refuses: the error line of kafscale-check is not shown"; sed 's/^/    /' "$WORK/out"
  failures=$((failures + 1))
else
  echo "ok   staged-kafscale-check-refuses"
fi

rc=0
env -i PATH="$WORK/bin:$PATH" HOME="$WORK" bash "$WORK/repo/deploy/k3s/00-prereqs.sh" --nosuch > "$WORK/out" 2>&1 || rc=$?
if (( rc != 2 )) || ! grep -qxF 'error: --nosuch: unknown option; expected --no-build, --no-cluster or --help' "$WORK/out"; then
  echo "FAIL unknown-option: exit $rc, expected 2 and the line: error: --nosuch: unknown option; expected --no-build, --no-cluster or --help"
  sed 's/^/    /' "$WORK/out"; failures=$((failures + 1))
else
  echo "ok   unknown-option"
fi

if (( failures )); then echo "$failures failed"; exit 1; fi
echo "all passed"
