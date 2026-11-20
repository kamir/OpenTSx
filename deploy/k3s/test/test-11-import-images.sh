#!/usr/bin/env bash
# Tests the arguments of deploy/k3s/11-import-images.sh: an unknown option, an option without its value, an argument,
# a method that is not one (from --method or IMAGE_METHOD) and the methods ssh and kind without their target exit 2
# before an image is saved or imported, --help lists the exit codes, the method registry with a repository without a
# registry host exits 1 before an image is pushed, the tool of a method that is not installed and a kind cluster that
# does not exist exit 1 before an image is saved, and the method none imports nothing. Stand-ins for docker, podman,
# k3s, ssh and kind record any call; needs no image, no cluster, no network.
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
for method in docker podman buildah; do
  rc=0
  rm -f "$WORK/calls.log"
  env -i PATH="$WORK/bare" HOME="$WORK" WORK="$WORK" OPENTSX_ENV_FILE="$WORK/none.env" \
    "$WORK/bare/bash" "$K3S_DIR/11-import-images.sh" --method "$method" > "$WORK/$method-missing.out" 2>&1 || rc=$?
  message="error: $method: not found; install it or choose another method with --method"
  if (( rc != 1 )) || ! grep -qxF -- "$message" "$WORK/$method-missing.out"; then
    failed "$method-missing: exit $rc, expected 1 and the message: $message"; sed 's/^/    /' "$WORK/$method-missing.out"
  elif [[ -s "$WORK/calls.log" ]]; then
    failed "$method-missing: a tool was called: $(cat "$WORK/calls.log")"
  else
    echo "ok   $method-missing"
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

import method-none -- --method none
if (( rc != 0 )) || ! grep -qxF -- "==> --method none: nothing to import" "$WORK/method-none.out" || [[ -s "$WORK/calls.log" ]]; then
  failed "method-none: exit $rc, expected 0, the message ==> --method none: nothing to import and no tool call"
  sed 's/^/    /' "$WORK/method-none.out"
else
  echo "ok   method-none"
fi

if (( failures )); then echo "$failures failed"; exit 1; fi
echo "all passed"
