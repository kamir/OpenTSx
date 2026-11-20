#!/usr/bin/env bash
# Tests deploy/k3s/90-teardown.sh: an image that k3s ctr cannot remove gives a warn line naming it and exit 1, an image
# that is not there is no failure, --images without k3s exits 1 and deletes nothing, also without --yes, where the
# k3s refusal comes before the question, a declined question and a question at the end of standard input exit 1 with
# their message on stderr before kubectl runs, an answer without a newline at the end counts as given, a namespace
# that cannot be read exits 1 and is not taken as missing, a missing namespace deletes nothing, an existing one is
# deleted, and usage errors exit 2. Stand-ins for kubectl, k3s and sudo; needs no cluster.
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
# kubectl: the namespace does not exist (with --ignore-not-found as kubectl does: no output and exit 0), so nothing is
# deleted in the cluster; NAMESPACE=present: it exists and every deletion succeeds; CLUSTER=down refuses every call as
# an unreachable API server does
cat > "$WORK/bin/kubectl" <<'EOF'
#!/usr/bin/env bash
printf '%s\n' "$*" >> "$WORK/calls.log"
if [[ "${CLUSTER:-}" == down ]]; then
  echo "The connection to the server 10.0.0.1:6443 was refused" >&2; exit 1
fi
[[ "$1" == -n ]] && shift 2
case "$*" in
  "get namespace "*)
    [[ "${NAMESPACE:-}" == present ]] && { echo "namespace/$3"; exit 0; }
    [[ "$*" == *--ignore-not-found* ]] && exit 0
    echo "Error from server (NotFound): namespaces \"$3\" not found" >&2; exit 1 ;;
  "delete "*) [[ "${NAMESPACE:-}" == present ]] || { echo "kubectl stand-in: delete without a namespace" >&2; exit 1; } ;;
  *) echo "kubectl stand-in: unexpected call: $*" >&2; exit 1 ;;
esac
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
  grep -qxF "  warn  $img: not removed from k3s containerd (ctr: failed to dial \"/run/k3s/containerd/containerd.sock\": connect: permission denied); remove it with sudo k3s ctr images rm docker.io/$img $img" \
    "$WORK/images-not-removed.out" || failed "images-not-removed: no warn line names $img"
done
grep -qxF "error: --images: 4 image(s) not removed from k3s containerd; see the warn lines above" "$WORK/images-not-removed.out" \
  || failed "images-not-removed: no error line with the count"
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
message="error: 90-teardown.sh: aborted at the question, nothing was changed; answer y to delete, or pass --yes"
if (( rc != 1 )) || [[ "$(cat "$WORK/declined.err")" != "$message" ]] || [[ -s "$WORK/declined.out" ]]; then
  failed "declined: exit $rc, expected 1, only the message on stderr and nothing on stdout: $message"
  sed 's/^/    stderr: /' "$WORK/declined.err"; sed 's/^/    stdout: /' "$WORK/declined.out"
elif [[ -s "$WORK/calls.log" ]]; then
  failed "declined: kubectl was called"
else
  echo "ok   declined"
fi

rc=0
rm -f "$WORK/calls.log"
env -i PATH="$WORK/bin:$PATH" HOME="$WORK" WORK="$WORK" KUBECTL="$WORK/bin/kubectl" OPENTSX_ENV_FILE="$WORK/none.env" \
  OPENTSX_OVERLAY="$WORK/overlay" bash "$K3S_DIR/90-teardown.sh" > "$WORK/no-answer.out" 2> "$WORK/no-answer.err" < /dev/null \
  || rc=$?
message="error: 90-teardown.sh: no answer on standard input; pass --yes to run without the question"
if (( rc != 1 )) || [[ "$(cat "$WORK/no-answer.err")" != "$message" ]] || [[ -s "$WORK/no-answer.out" ]]; then
  failed "no-answer: exit $rc, expected 1, only the message on stderr and nothing on stdout: $message"
  sed 's/^/    stderr: /' "$WORK/no-answer.err"; sed 's/^/    stdout: /' "$WORK/no-answer.out"
elif [[ -s "$WORK/calls.log" ]]; then
  failed "no-answer: kubectl was called"
else
  echo "ok   no-answer"
fi

answer() {   # NAME INPUT [KEY=VALUE ...]: 90-teardown.sh without --yes, INPUT without a newline at the end
  local name="$1" input="$2"; shift 2
  rc=0
  rm -f "$WORK/calls.log"
  printf '%s' "$input" | env -i PATH="$WORK/bin:$PATH" HOME="$WORK" WORK="$WORK" KUBECTL="$WORK/bin/kubectl" \
    OPENTSX_ENV_FILE="$WORK/none.env" OPENTSX_OVERLAY="$WORK/overlay" "$@" bash "$K3S_DIR/90-teardown.sh" \
    > "$WORK/$name.out" 2> "$WORK/$name.err" || rc=$?
}
answer answered-no-newline y NAMESPACE=present
if (( rc != 0 )) || ! grep -sqxF "delete -k $WORK/overlay --ignore-not-found --wait=true" "$WORK/calls.log"; then
  failed "answered-no-newline: exit $rc, expected 0 and kubectl delete -k of the overlay after y without a newline"
  sed 's/^/    stderr: /' "$WORK/answered-no-newline.err"; sed 's/^/    stdout: /' "$WORK/answered-no-newline.out"
else
  echo "ok   answered-no-newline"
fi
answer declined-no-newline n
message="error: 90-teardown.sh: aborted at the question, nothing was changed; answer y to delete, or pass --yes"
if (( rc != 1 )) || [[ "$(cat "$WORK/declined-no-newline.err")" != "$message" ]] \
  || [[ -s "$WORK/declined-no-newline.out" ]]; then
  failed "declined-no-newline: exit $rc, expected 1, only the message on stderr and nothing on stdout: $message"
  sed 's/^/    stderr: /' "$WORK/declined-no-newline.err"; sed 's/^/    stdout: /' "$WORK/declined-no-newline.out"
elif [[ -s "$WORK/calls.log" ]]; then
  failed "declined-no-newline: kubectl was called"
else
  echo "ok   declined-no-newline"
fi

teardown cluster-down CLUSTER=down -- --yes
message="error: namespace opentsx: cannot read it; check the cluster with $WORK/bin/kubectl get namespace opentsx"
if (( rc != 1 )) || ! grep -qxF -- "$message" "$WORK/cluster-down.out"; then
  failed "cluster-down: exit $rc, expected 1 and the message: $message"; sed 's/^/    /' "$WORK/cluster-down.out"
elif ! grep -qxF "The connection to the server 10.0.0.1:6443 was refused" "$WORK/cluster-down.out"; then
  failed "cluster-down: the error of kubectl is not shown"; sed 's/^/    /' "$WORK/cluster-down.out"
elif grep -qF "does not exist" "$WORK/cluster-down.out"; then
  failed "cluster-down: an unreachable cluster is reported as a missing namespace"; sed 's/^/    /' "$WORK/cluster-down.out"
else
  echo "ok   cluster-down"
fi

teardown no-namespace -- --yes
if (( rc != 0 )) || ! grep -qF "namespace opentsx does not exist; nothing to delete in the cluster" "$WORK/no-namespace.out"; then
  failed "no-namespace: exit $rc, expected 0 and nothing to delete"; sed 's/^/    /' "$WORK/no-namespace.out"
elif grep -q '^delete\|^-n opentsx delete' "$WORK/calls.log"; then
  failed "no-namespace: kubectl delete was called"; sed 's/^/    /' "$WORK/calls.log"
else
  echo "ok   no-namespace"
fi

teardown namespace-present NAMESPACE=present -- --yes
if (( rc != 0 )) || ! grep -qxF "delete -k $WORK/overlay --ignore-not-found --wait=true" "$WORK/calls.log"; then
  failed "namespace-present: exit $rc, expected 0 and kubectl delete -k of the overlay"
  sed 's/^/    /' "$WORK/namespace-present.out"; sed 's/^/    call: /' "$WORK/calls.log"
else
  echo "ok   namespace-present"
fi

mkdir -p "$WORK/no-k3s"
ln -s "$(command -v bash)" "$WORK/no-k3s/bash"
ln -s "$(command -v dirname)" "$WORK/no-k3s/dirname"
teardown images-without-k3s PATH="$WORK/no-k3s" NAMESPACE=present -- --images --yes
message="error: --images: k3s not found on this host; run 90-teardown.sh --images on the k3s node, or leave out --images"
if (( rc != 1 )) || ! grep -qxF -- "$message" "$WORK/images-without-k3s.out"; then
  failed "images-without-k3s: exit $rc, expected 1 and the message: $message"; sed 's/^/    /' "$WORK/images-without-k3s.out"
elif [[ -s "$WORK/calls.log" ]]; then
  failed "images-without-k3s: kubectl was called before k3s was checked"; sed 's/^/    call: /' "$WORK/calls.log"
else
  echo "ok   images-without-k3s"
fi

# without --yes and the answer n: the k3s check comes before the question, so only the k3s refusal is printed
rc=0
rm -f "$WORK/calls.log"
env -i PATH="$WORK/no-k3s" HOME="$WORK" WORK="$WORK" KUBECTL="$WORK/bin/kubectl" OPENTSX_ENV_FILE="$WORK/none.env" \
  OPENTSX_OVERLAY="$WORK/overlay" NAMESPACE=present "$WORK/no-k3s/bash" "$K3S_DIR/90-teardown.sh" --images \
  > "$WORK/images-without-k3s-asked.out" 2> "$WORK/images-without-k3s-asked.err" <<< n || rc=$?
message="error: --images: k3s not found on this host; run 90-teardown.sh --images on the k3s node, or leave out --images"
if (( rc != 1 )) || [[ "$(cat "$WORK/images-without-k3s-asked.err")" != "$message" ]] \
  || [[ -s "$WORK/images-without-k3s-asked.out" ]]; then
  failed "images-without-k3s-asked: exit $rc, expected 1 and only the k3s refusal on stderr, before the question: $message"
  sed 's/^/    stderr: /' "$WORK/images-without-k3s-asked.err"; sed 's/^/    stdout: /' "$WORK/images-without-k3s-asked.out"
elif [[ -s "$WORK/calls.log" ]]; then
  failed "images-without-k3s-asked: kubectl was called"; sed 's/^/    call: /' "$WORK/calls.log"
else
  echo "ok   images-without-k3s-asked"
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
