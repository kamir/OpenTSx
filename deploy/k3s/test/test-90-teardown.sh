#!/usr/bin/env bash
# Tests deploy/k3s/90-teardown.sh: an image that k3s ctr cannot remove gives a warn line naming it and exit 1, an image
# that is not there is no failure, a declined question exits 1 with its message on stderr before kubectl runs, and
# usage errors exit 2. Stand-ins for kubectl, k3s and sudo; needs no cluster.
#
#   bash deploy/k3s/test/test-90-teardown.sh
set -euo pipefail
K3S_DIR="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
failures=0
failed() { echo "FAIL $1"; failures=$((failures + 1)); }

mkdir -p "$WORK/bin" "$WORK/overlay"
touch "$WORK/overlay/kustomization.yaml"
# kubectl: the namespace does not exist, so nothing is deleted in the cluster
cat > "$WORK/bin/kubectl" <<'EOF'
#!/usr/bin/env bash
printf '%s\n' "$*" >> "$WORK/calls.log"
exit 1
EOF
# k3s ctr images rm: CTR=fails refuses as containerd does without access to its socket; otherwise it removes the
# images it has and warns about the others, as ctr does, with exit 0
cat > "$WORK/bin/k3s" <<'EOF'
#!/usr/bin/env bash
printf '%s\n' "$*" >> "$WORK/k3s.log"
if [[ "${CTR:-}" == fails ]]; then
  echo 'ctr: failed to dial "/run/k3s/containerd/containerd.sock": connect: permission denied' >&2; exit 1
fi
shift 3
for ref in "$@"; do
  if [[ "$ref" == docker.io/* ]]; then echo "$ref"; else echo "WARN[0000] $ref: image not found" >&2; fi
done
EOF
printf '#!/usr/bin/env bash\nexec "$@"\n' > "$WORK/bin/sudo"
chmod +x "$WORK/bin/kubectl" "$WORK/bin/k3s" "$WORK/bin/sudo"

teardown() {   # NAME [KEY=VALUE ...] -- ARG ...: output in NAME.out, exit code in rc
  local name="$1"; shift
  local -a envs=()
  while (( $# )) && [[ "$1" != -- ]]; do envs+=("$1"); shift; done
  if (( $# )); then shift; fi
  rc=0
  rm -f "$WORK/calls.log" "$WORK/k3s.log"
  env -i PATH="$WORK/bin:$PATH" HOME="$WORK" WORK="$WORK" KUBECTL="$WORK/bin/kubectl" \
    OPENTSX_ENV_FILE="$WORK/none.env" OPENTSX_OVERLAY="$WORK/overlay" "${envs[@]}" \
    bash "$K3S_DIR/90-teardown.sh" "$@" > "$WORK/$name.out" 2>&1 || rc=$?
}

teardown images-not-removed CTR=fails -- --images --yes
before=$failures
if (( rc != 1 )); then failed "images-not-removed: exit $rc, expected 1"; fi
for img in opentsx/tools:3.0.1-local opentsx/studio:3.0.1-local opentsx/saas-frontend:3.0.1-local \
  opentsx/saas-backend:3.0.1-local; do
  grep -qF "warn  $img: not removed from k3s containerd: ctr: failed to dial" "$WORK/images-not-removed.out" \
    || failed "images-not-removed: no warn line names $img"
done
if (( failures > before )); then sed 's/^/    /' "$WORK/images-not-removed.out"; else echo "ok   images-not-removed"; fi

teardown images-removed -- --images --yes
if (( rc != 0 )) || grep -q 'warn' "$WORK/images-removed.out"; then
  failed "images-removed: exit $rc, expected 0 without a warn line"; sed 's/^/    /' "$WORK/images-removed.out"
else
  echo "ok   images-removed"
fi

rc=0
rm -f "$WORK/calls.log"
env -i PATH="$WORK/bin:$PATH" HOME="$WORK" WORK="$WORK" KUBECTL="$WORK/bin/kubectl" OPENTSX_ENV_FILE="$WORK/none.env" \
  OPENTSX_OVERLAY="$WORK/overlay" bash "$K3S_DIR/90-teardown.sh" > "$WORK/declined.out" 2> "$WORK/declined.err" <<< n \
  || rc=$?
message="error: 90-teardown.sh: aborted at the question; nothing was changed"
if (( rc != 1 )) || [[ "$(cat "$WORK/declined.err")" != "$message" ]] || [[ -s "$WORK/declined.out" ]]; then
  failed "declined: exit $rc, expected 1, only the message on stderr and nothing on stdout: $message"
  sed 's/^/    stderr: /' "$WORK/declined.err"; sed 's/^/    stdout: /' "$WORK/declined.out"
elif [[ -s "$WORK/calls.log" ]]; then
  failed "declined: kubectl was called"
else
  echo "ok   declined"
fi

expect() {   # NAME MESSAGE ARG ...: a usage error, exit 2, before kubectl runs
  local name="$1" message="$2"; shift 2
  teardown "$name" -- "$@"
  if (( rc != 2 )); then
    failed "$name: exit $rc, expected 2"; sed 's/^/    /' "$WORK/$name.out"
  elif ! grep -qF -- "$message" "$WORK/$name.out"; then
    failed "$name: expected the message: $message"; sed 's/^/    /' "$WORK/$name.out"
  elif [[ -s "$WORK/calls.log" ]]; then
    failed "$name: kubectl was called"
  else
    echo "ok   $name"
  fi
}
expect unknown-option "error: --nosuch: unknown option; expected --keep-data, --images, --yes or --help" --nosuch
teardown help -- --help
if (( rc != 0 )) || ! grep -qF "  2  usage error" "$WORK/help.out"; then
  failed "help: exit $rc, expected 0 and the exit code 2 in the usage"; sed 's/^/    /' "$WORK/help.out"
else
  echo "ok   help"
fi

if (( failures )); then echo "$failures failed"; exit 1; fi
echo "all passed"
