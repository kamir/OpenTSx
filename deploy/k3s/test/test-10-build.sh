#!/usr/bin/env bash
# Tests the arguments of deploy/k3s/10-build.sh: an unknown option, an option without its value, a builder that is not
# one, an argument and a NERDCTL of blanks exit 2 before Maven or an image builder runs, --help lists the exit codes, a
# --tag that differs from OPENTSX_TAG and LAKE=true without --with-s3 print a warning, a missing java, mvn or image
# builder exits 1 with its message, a builder named with --builder or a NERDCTL command that is not installed before
# Maven runs, and --no-images on a PATH without an image builder builds, stages and exits 0.
# Stand-ins for mvn, java, the builders and deploy/docker/stage.sh record any call; needs no JDK, no builder, no
# network.
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

rc=0
env -i PATH="$WORK/bin:$PATH" HOME="$WORK" WORK="$WORK" OPENTSX_ENV_FILE="$WORK/none.env" LAKE=true \
  bash "$WORK/repo/deploy/k3s/10-build.sh" --skip-maven --no-images > "$WORK/lake-without-s3.out" 2>&1 || rc=$?
message='  warn  --with-s3: not given with LAKE=true; windpark-sim and the retrieval server cannot open an s3:// warehouse without S3FileIO, run 10-build.sh --with-s3'
if (( rc != 0 )) || ! grep -qxF -- "$message" "$WORK/lake-without-s3.out"; then
  failed "lake-without-s3: exit $rc, expected 0 and the message: $message"; sed 's/^/    /' "$WORK/lake-without-s3.out"
else
  echo "ok   lake-without-s3"
fi

# missing NAME MESSAGE TOOL ...: a PATH with bash, dirname, basename and the stand-ins TOOL ... only; exit 1 with
# MESSAGE before Maven or an image builder runs
mkdir -p "$WORK/bare"
for tool in bash dirname basename; do ln -s "$(command -v "$tool")" "$WORK/bare/$tool"; done
missing() {
  local name="$1" message="$2" tool; shift 2
  rm -rf "$WORK/bare-$name"; mkdir -p "$WORK/bare-$name"
  for tool in "$@"; do ln -s "$WORK/bin/record" "$WORK/bare-$name/$tool"; done
  rc=0
  rm -f "$WORK/calls.log"
  env -i PATH="$WORK/bare-$name:$WORK/bare" HOME="$WORK" WORK="$WORK" OPENTSX_ENV_FILE="$WORK/none.env" \
    "$WORK/bare/bash" "$WORK/repo/deploy/k3s/10-build.sh" --skip-maven > "$WORK/$name.out" 2>&1 || rc=$?
  if (( rc != 1 )) || ! grep -qxF -- "$message" "$WORK/$name.out"; then
    failed "$name: exit $rc, expected 1 and the message: $message"; sed 's/^/    /' "$WORK/$name.out"
  elif grep -qvE '^stage.sh $' "$WORK/calls.log" 2>/dev/null; then
    failed "$name: a tool other than stage.sh was called: $(cat "$WORK/calls.log")"
  else
    echo "ok   $name"
  fi
}
missing no-java "error: java: not found; install a JDK 17 or newer, deploy/k3s/00-prereqs.sh checks the build tools"
missing no-maven "error: mvn: not found; install Maven, deploy/k3s/00-prereqs.sh checks the build tools" java
missing no-builder "error: image builder: none of docker, podman, nerdctl or buildah found; install one, or stage only with --no-images" \
  java mvn

# --no-images, as the refusal above recommends, on a PATH with java and mvn and no image builder: Maven and the
# staging run, no builder is looked for
rm -rf "$WORK/bare-no-images"; mkdir -p "$WORK/bare-no-images"
for tool in java mvn; do ln -s "$WORK/bin/record" "$WORK/bare-no-images/$tool"; done
rc=0
rm -f "$WORK/calls.log"
env -i PATH="$WORK/bare-no-images:$WORK/bare" HOME="$WORK" WORK="$WORK" OPENTSX_ENV_FILE="$WORK/none.env" \
  "$WORK/bare/bash" "$WORK/repo/deploy/k3s/10-build.sh" --no-images > "$WORK/no-images.out" 2>&1 || rc=$?
staged="mvn -q -B install -DskipTests
stage.sh "
if (( rc != 0 )) || ! grep -qxF "==> done (no images built)" "$WORK/no-images.out"; then
  failed "no-images: exit $rc, expected 0 and the staging without an image builder"; sed 's/^/    /' "$WORK/no-images.out"
elif [[ "$(cat "$WORK/calls.log" 2>/dev/null)" != "$staged" ]]; then
  failed "no-images: expected the calls of Maven and stage.sh only"; sed 's/^/    call: /' "$WORK/calls.log"
else
  echo "ok   no-images"
fi

# unbuilt NAME EXIT MESSAGE [KEY=VALUE ...] -- ARG ...: a PATH with bash, dirname, basename and the stand-ins java, mvn
# and nerdctl only; exit EXIT with MESSAGE before mvn or stage.sh runs
unbuilt() {
  local name="$1" want="$2" message="$3" tool; shift 3
  local -a envs=()
  while (( $# )) && [[ "$1" != -- ]]; do envs+=("$1"); shift; done
  if (( $# )); then shift; fi
  rm -rf "$WORK/bare-$name"; mkdir -p "$WORK/bare-$name"
  for tool in java mvn nerdctl; do ln -s "$WORK/bin/record" "$WORK/bare-$name/$tool"; done
  rc=0
  rm -f "$WORK/calls.log"
  env -i PATH="$WORK/bare-$name:$WORK/bare" HOME="$WORK" WORK="$WORK" OPENTSX_ENV_FILE="$WORK/none.env" \
    ${envs[@]+"${envs[@]}"} "$WORK/bare/bash" "$WORK/repo/deploy/k3s/10-build.sh" "$@" > "$WORK/$name.out" 2>&1 || rc=$?
  if (( rc != want )) || ! grep -qxF -- "$message" "$WORK/$name.out"; then
    failed "$name: exit $rc, expected $want and the message: $message"; sed 's/^/    /' "$WORK/$name.out"
  elif [[ -s "$WORK/calls.log" ]]; then
    failed "$name: a tool ran before the builder was checked: $(cat "$WORK/calls.log")"
  else
    echo "ok   $name"
  fi
}
unbuilt builder-not-installed 1 "error: podman: not found; install it or choose another builder with --builder" \
  -- --builder podman
unbuilt nerdctl-command-missing 1 "error: $WORK/none/nerdctl: not found; install it, set NERDCTL or choose another builder with --builder" \
  NERDCTL="$WORK/none/nerdctl --namespace k8s.io" -- --builder nerdctl
unbuilt nerdctl-detected-command-missing 1 "error: $WORK/none/nerdctl: not found; install it, set NERDCTL or choose another builder with --builder" \
  NERDCTL="$WORK/none/nerdctl --namespace k8s.io"
unbuilt nerdctl-blank 2 "error: NERDCTL: empty value; set it to the nerdctl command or leave it unset" \
  NERDCTL="   " -- --builder nerdctl
unbuilt nerdctl-detected-blank 2 "error: NERDCTL: empty value; set it to the nerdctl command or leave it unset" \
  NERDCTL="   "

if (( failures )); then echo "$failures failed"; exit 1; fi
echo "all passed"
