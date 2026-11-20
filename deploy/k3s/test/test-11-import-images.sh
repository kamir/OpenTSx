#!/usr/bin/env bash
# Tests the arguments of deploy/k3s/11-import-images.sh: an unknown option, an option without its value, an argument,
# a method that is not one (from --method or IMAGE_METHOD) and the methods ssh and kind without their target exit 2
# before an image is saved or imported, --help lists the exit codes, the method registry with a repository without a
# registry host exits 1 before an image is pushed, the tool of a method that is not installed (also nerdctl and ssh
# when the other tools are there), a kind cluster that does not exist, a failing kind get clusters and the methods ssh
# and registry without docker or podman exit 1 before an image is saved, images that k3s' containerd does not list
# after the import exit 1, and the method none imports nothing. Stand-ins for docker, podman, k3s, ssh and kind record
# any call; needs no image, no cluster, no network.
#
#   bash deploy/k3s/test/test-11-import-images.sh
set -euo pipefail
K3S_DIR="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
failures=0
failed() { echo "FAIL $1"; failures=$((failures + 1)); }

mkdir -p "$WORK/bin"
# every tool records its call; none is expected to run
cat > "$WORK/bin/record" <<'STUB'
#!/usr/bin/env bash
printf '%s %s\n' "$(basename "$0")" "$*" >> "$WORK/calls.log"
STUB
chmod +x "$WORK/bin/record"
for tool in docker podman nerdctl buildah k3s ssh kind sudo; do ln -s record "$WORK/bin/$tool"; done

import() {   # NAME [KEY=VALUE ...] -- ARG ...: output in NAME.out, exit code in rc
  local name="$1"; shift
  local -a envs=()
  while (( $# )) && [[ "$1" != -- ]]; do envs+=("$1"); shift; done
  if (( $# )); then shift; fi
  rc=0
  rm -f "$WORK/calls.log"
  env -i PATH="$WORK/bin:$PATH" HOME="$WORK" WORK="$WORK" OPENTSX_ENV_FILE="$WORK/none.env" "${envs[@]}" \
    bash "$K3S_DIR/11-import-images.sh" "$@" > "$WORK/$name.out" 2>&1 || rc=$?
}

expect() {   # NAME MESSAGE [KEY=VALUE ...] -- ARG ...: a usage error, exit 2, before any tool runs
  local name="$1" message="$2"; shift 2
  import "$name" "$@"
  if (( rc != 2 )); then
    failed "$name: exit $rc, expected 2"; sed 's/^/    /' "$WORK/$name.out"
  elif ! grep -qxF -- "$message" "$WORK/$name.out"; then
    failed "$name: expected the message: $message"; sed 's/^/    /' "$WORK/$name.out"
  elif [[ -s "$WORK/calls.log" ]]; then
    failed "$name: a tool was called: $(cat "$WORK/calls.log")"
  else
    echo "ok   $name"
  fi
}
METHODS="docker, podman, nerdctl, buildah, kind, registry, ssh or none"
expect unknown-option "error: --nosuch: unknown option; expected --method, --ssh, --kind-cluster or --help" -- --nosuch
expect extra-argument "error: tools: unexpected argument; 11-import-images.sh takes options only" -- tools
expect method-without-value "error: --method: missing value; expected $METHODS" -- --method
expect ssh-without-value "error: --ssh: missing value; expected user@host" -- --ssh
expect kind-cluster-without-value "error: --kind-cluster: missing value; expected the name of a kind cluster" -- --kind-cluster
expect method-unknown "error: --method: \"scp\" is not a method; expected $METHODS" -- --method scp
expect image-method-unknown "error: IMAGE_METHOD: \"scp\" is not a method; expected $METHODS" IMAGE_METHOD=scp --
expect ssh-without-target "error: --ssh: missing; the method ssh needs --ssh user@host or K3S_SSH" -- --method ssh
expect kind-without-cluster "error: --kind-cluster: missing; the method kind needs --kind-cluster <name> or KIND_CLUSTER (kind get clusters lists them)" -- --method kind

import help -- --help
if (( rc != 0 )) || ! grep -qF "  2  usage error" "$WORK/help.out"; then
  failed "help: exit $rc, expected 0 and the exit code 2 in the usage"; sed 's/^/    /' "$WORK/help.out"
else
  echo "ok   help"
fi

import registry-without-host IMAGE_METHOD=registry OPENTSX_IMAGE_REPO=opentsx --
message="error: OPENTSX_IMAGE_REPO: \"opentsx\" has no registry host; set one with 20-configure.sh OPENTSX_IMAGE_REPO=registry.lan:5000/opentsx, then rebuild with 10-build.sh"
if (( rc != 1 )) || ! grep -qxF -- "$message" "$WORK/registry-without-host.out"; then
  failed "registry-without-host: exit $rc, expected 1 and the message: $message"
  sed 's/^/    /' "$WORK/registry-without-host.out"
elif [[ -s "$WORK/calls.log" ]]; then
  failed "registry-without-host: a tool was called: $(cat "$WORK/calls.log")"
else
  echo "ok   registry-without-host"
fi

# a PATH with bash and dirname only: the tool of the method is not installed
mkdir -p "$WORK/bare"
for tool in bash dirname; do ln -s "$(command -v "$tool")" "$WORK/bare/$tool"; done
for method in docker podman nerdctl buildah; do
  rc=0
  rm -f "$WORK/calls.log"
  env -i PATH="$WORK/bare" HOME="$WORK" WORK="$WORK" OPENTSX_ENV_FILE="$WORK/none.env" \
    "$WORK/bare/bash" "$K3S_DIR/11-import-images.sh" --method "$method" > "$WORK/$method-missing.out" 2>&1 || rc=$?
  message="error: $method: not found; install it or choose another method with --method"
  [[ "$method" != nerdctl ]] \
    || message="error: nerdctl: not found; install it, set NERDCTL or choose another method with --method"
  if (( rc != 1 )) || ! grep -qxF -- "$message" "$WORK/$method-missing.out"; then
    failed "$method-missing: exit $rc, expected 1 and the message: $message"; sed 's/^/    /' "$WORK/$method-missing.out"
  elif [[ -s "$WORK/calls.log" ]]; then
    failed "$method-missing: a tool was called: $(cat "$WORK/calls.log")"
  else
    echo "ok   $method-missing"
  fi
done

# docker, k3s and sudo are there, the tool of the method is not: bash would report command not found and run the
# other side of the pipe
mkdir -p "$WORK/partial"
for tool in bash dirname basename; do ln -s "$(command -v "$tool")" "$WORK/partial/$tool"; done
for tool in docker k3s sudo; do ln -s "$WORK/bin/record" "$WORK/partial/$tool"; done
for method in nerdctl ssh; do
  rc=0
  rm -f "$WORK/calls.log"
  args=(--method "$method")
  message="error: nerdctl: not found; install it, set NERDCTL or choose another method with --method"
  if [[ "$method" == ssh ]]; then
    args+=(--ssh user@k3s-node)
    message="error: ssh: not found; install an ssh client or choose another method with --method"
  fi
  env -i PATH="$WORK/partial" HOME="$WORK" WORK="$WORK" OPENTSX_ENV_FILE="$WORK/none.env" \
    "$WORK/partial/bash" "$K3S_DIR/11-import-images.sh" "${args[@]}" > "$WORK/$method-only-missing.out" 2>&1 || rc=$?
  if (( rc != 1 )) || ! grep -qxF -- "$message" "$WORK/$method-only-missing.out"; then
    failed "$method-only-missing: exit $rc, expected 1 and the message: $message"
    sed 's/^/    /' "$WORK/$method-only-missing.out"
  elif [[ -s "$WORK/calls.log" ]]; then
    failed "$method-only-missing: a tool was called: $(cat "$WORK/calls.log")"
  else
    echo "ok   $method-only-missing"
  fi
done

# the stand-in kind lists no cluster
import kind-cluster-missing -- --method kind --kind-cluster lab
message="error: kind cluster lab: not found; list the clusters with kind get clusters"
if (( rc != 1 )) || ! grep -qxF -- "$message" "$WORK/kind-cluster-missing.out"; then
  failed "kind-cluster-missing: exit $rc, expected 1 and the message: $message"; sed 's/^/    /' "$WORK/kind-cluster-missing.out"
elif [[ "$(cat "$WORK/calls.log")" != "kind get clusters" ]]; then
  failed "kind-cluster-missing: expected only the call kind get clusters: $(cat "$WORK/calls.log")"
else
  echo "ok   kind-cluster-missing"
fi

# a kind whose call fails, as it does when docker is stopped
mkdir -p "$WORK/kind-fails"
cat > "$WORK/kind-fails/kind" <<'STUB'
#!/usr/bin/env bash
printf '%s %s\n' "$(basename "$0")" "$*" >> "$WORK/calls.log"
printf 'ERROR: failed to list clusters\nCannot connect to the Docker daemon\n' >&2
exit 1
STUB
chmod +x "$WORK/kind-fails/kind"
rc=0
rm -f "$WORK/calls.log"
env -i PATH="$WORK/kind-fails:$WORK/bin:$PATH" HOME="$WORK" WORK="$WORK" OPENTSX_ENV_FILE="$WORK/none.env" \
  bash "$K3S_DIR/11-import-images.sh" --method kind --kind-cluster lab > "$WORK/kind-fails.out" 2>&1 || rc=$?
message="error: kind get clusters: failed (ERROR: failed to list clusters Cannot connect to the Docker daemon); start docker or check the kind installation"
if (( rc != 1 )) || ! grep -qxF -- "$message" "$WORK/kind-fails.out"; then
  failed "kind-fails: exit $rc, expected 1 and the message: $message"; sed 's/^/    /' "$WORK/kind-fails.out"
elif [[ "$(cat "$WORK/calls.log")" != "kind get clusters" ]]; then
  failed "kind-fails: expected only the call kind get clusters: $(cat "$WORK/calls.log")"
else
  echo "ok   kind-fails"
fi

# the methods ssh and registry save with docker or podman; neither is on the PATH, ssh is
mkdir -p "$WORK/ssh-only"
ln -s "$WORK/bin/record" "$WORK/ssh-only/ssh"
for method in ssh registry; do
  rc=0
  rm -f "$WORK/calls.log"
  env -i PATH="$WORK/ssh-only:$WORK/bare" HOME="$WORK" WORK="$WORK" OPENTSX_ENV_FILE="$WORK/none.env" K3S_SSH=pi@k3s-node \
    OPENTSX_IMAGE_REPO=registry.lan:5000/opentsx \
    "$WORK/bare/bash" "$K3S_DIR/11-import-images.sh" --method "$method" > "$WORK/$method-no-saver.out" 2>&1 || rc=$?
  message="error: docker, podman: no running docker and no podman found; start docker or install podman to save the images"
  if (( rc != 1 )) || ! grep -qxF -- "$message" "$WORK/$method-no-saver.out"; then
    failed "$method-no-saver: exit $rc, expected 1 and the message: $message"; sed 's/^/    /' "$WORK/$method-no-saver.out"
  elif [[ -s "$WORK/calls.log" ]]; then
    failed "$method-no-saver: a tool was called: $(cat "$WORK/calls.log")"
  else
    echo "ok   $method-no-saver"
  fi
done

# the stand-in k3s lists no image after the import
import images-missing -- --method docker
message="error: opentsx/tools:3.0.1-local, opentsx/studio:3.0.1-local: not in k3s containerd after the import; check the import output above and sudo k3s ctr images ls"
if (( rc != 1 )) || ! grep -qxF -- "$message" "$WORK/images-missing.out"; then
  failed "images-missing: exit $rc, expected 1 and the message: $message"; sed 's/^/    /' "$WORK/images-missing.out"
else
  echo "ok   images-missing"
fi

import method-none -- --method none
if (( rc != 0 )) || ! grep -qxF -- "==> --method none: nothing to import" "$WORK/method-none.out" || [[ -s "$WORK/calls.log" ]]; then
  failed "method-none: exit $rc, expected 0, the message ==> --method none: nothing to import and no tool call"
  sed 's/^/    /' "$WORK/method-none.out"
else
  echo "ok   method-none"
fi

if (( failures )); then echo "$failures failed"; exit 1; fi
echo "all passed"
