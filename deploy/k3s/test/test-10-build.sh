#!/usr/bin/env bash
# Tests the arguments of deploy/k3s/10-build.sh: an unknown option, an option without its value, a builder that is not
# one, and an argument exit 2 before Maven or an image builder runs, --help lists the exit codes, and a --tag that
# differs from OPENTSX_TAG prints a warning. Stand-ins for mvn, java, the builders and deploy/docker/stage.sh record
# any call; needs no JDK, no builder, no network.
#
#   bash deploy/k3s/test/test-10-build.sh
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
for tool in mvn java docker podman nerdctl buildah; do ln -s record "$WORK/bin/$tool"; done

build() {   # NAME ARG ...: output in NAME.out, exit code in rc
  local name="$1"; shift
  rc=0
  rm -f "$WORK/calls.log"
  env -i PATH="$WORK/bin:$PATH" HOME="$WORK" WORK="$WORK" OPENTSX_ENV_FILE="$WORK/none.env" \
    bash "$K3S_DIR/10-build.sh" "$@" > "$WORK/$name.out" 2>&1 || rc=$?
}

expect() {   # NAME MESSAGE ARG ...: a usage error, exit 2, before any tool runs
  local name="$1" message="$2"; shift 2
  build "$name" "$@"
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
expect unknown-option "error: --nosuch: unknown option; expected --skip-maven, --with-s3, --builder, --platform, --tag, --no-images or --help" --nosuch
expect extra-argument "error: images: unexpected argument; 10-build.sh takes options only" images
expect builder-without-value "error: --builder: missing value; expected docker, podman, nerdctl or buildah" --builder
expect builder-unknown "error: --builder: \"kaniko\" is not a builder; expected docker, podman, nerdctl or buildah" --builder kaniko
expect platform-without-value "error: --platform: missing value; expected a platform such as linux/arm64" --skip-maven --platform
expect tag-without-value "error: --tag: missing value; expected an image tag such as 3.0.1-local" --tag

build help --help
if (( rc != 0 )) || ! grep -qF "  2  usage error" "$WORK/help.out"; then
  failed "help: exit $rc, expected 0 and the exit code 2 in the usage"; sed 's/^/    /' "$WORK/help.out"
else
  echo "ok   help"
fi

# a copy of the script in a throw-away tree, so the stand-in stage.sh writes nothing into deploy/docker
mkdir -p "$WORK/repo/deploy/k3s" "$WORK/repo/deploy/docker"
cp "$K3S_DIR/10-build.sh" "$K3S_DIR/lib.sh" "$K3S_DIR/env.example" "$WORK/repo/deploy/k3s/"
ln -s "$WORK/bin/record" "$WORK/repo/deploy/docker/stage.sh"
rc=0
env -i PATH="$WORK/bin:$PATH" HOME="$WORK" WORK="$WORK" OPENTSX_ENV_FILE="$WORK/none.env" \
  bash "$WORK/repo/deploy/k3s/10-build.sh" --skip-maven --builder docker --tag 9.9.9 > "$WORK/tag-differs.out" 2>&1 || rc=$?
message='  warn  --tag: "9.9.9" differs from OPENTSX_TAG "3.0.1-local"; run 20-configure.sh OPENTSX_TAG=9.9.9 so the manifests use it'
if (( rc != 0 )) || ! grep -qxF -- "$message" "$WORK/tag-differs.out"; then
  failed "tag-differs: exit $rc, expected 0 and the message: $message"; sed 's/^/    /' "$WORK/tag-differs.out"
else
  echo "ok   tag-differs"
fi

if (( failures )); then echo "$failures failed"; exit 1; fi
echo "all passed"
