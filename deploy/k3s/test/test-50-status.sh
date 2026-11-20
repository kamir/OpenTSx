#!/usr/bin/env bash
# Tests the arguments of deploy/k3s/50-status.sh: an unknown option and an argument exit 2 before kubectl runs,
# --help lists the exit codes, a namespace that is not there exits 1 with its message, and a cluster that cannot be
# reached exits 1 with kubectl's error and a message that does not say the namespace is missing; against a cluster
# that answers, no OpenTSx image in k3s containerd, a k3s containerd that cannot list its images (with the error of
# k3s ctr), an old statistics checkpoint and a checkpoint that cannot be read give their warn lines and exit 0.
# Stand-ins for kubectl, k3s and sudo; needs no kubectl, no cluster, no network.
#
#   bash deploy/k3s/test/test-50-status.sh
set -euo pipefail
K3S_DIR="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
failures=0
failed() { echo "FAIL $1"; failures=$((failures + 1)); }

mkdir -p "$WORK/bin"
# kubectl records its call and fails as an unreachable API server does; NAMESPACE=missing answers that the namespace
# does not exist, with --ignore-not-found as kubectl does: no output and exit 0. CLUSTER=up answers as a cluster with
# the namespace, opentsx-stats and its checkpoint CKPT_AGE seconds old (default 10); CKPT=missing: the checkpoint
# cannot be listed.
cat > "$WORK/bin/kubectl" <<'STUB'
#!/usr/bin/env bash
printf '%s\n' "$*" >> "$WORK/calls.log"
if [[ "${NAMESPACE:-}" == missing && "$1 $2" == "get namespace" ]]; then
  [[ "$*" == *--ignore-not-found* ]] && exit 0
  echo "Error from server (NotFound): namespaces \"$3\" not found" >&2; exit 1
fi
if [[ "${CLUSTER:-}" == up ]]; then
  [[ "$1" == -n ]] && shift 2
  case "$*" in
    "get namespace "*) echo "namespace/$3" ;;
    "exec deployment/opentsx-stats -- ls "*)
      [[ "${CKPT:-}" != missing ]] || { echo "ls: cannot access '/state': No such file or directory" >&2; exit 2; }
      echo "-rw-r--r-- 1 1000 1000 4096 2026-01-01T00:00:00 stats.ckpt" ;;
    "exec deployment/opentsx-stats -- sh -c "*) echo "${CKPT_AGE:-10}" ;;
    "get deployment/"*) echo "Error from server (NotFound): deployments.apps \"${1#deployment/}\" not found" >&2; exit 1 ;;
  esac
  exit 0
fi
echo "The connection to the server 10.0.0.1:6443 was refused" >&2
exit 1
STUB
# k3s ctr images ls -q: the OpenTSx tools image; IMAGES=none: only an image of another repository; CTR=fails: refuses as
# containerd does without access to its socket
cat > "$WORK/bin/k3s" <<'STUB'
#!/usr/bin/env bash
if [[ "${CTR:-}" == fails ]]; then
  echo 'ctr: failed to dial "/run/k3s/containerd/containerd.sock": connect: permission denied' >&2; exit 1
fi
if [[ "${IMAGES:-}" == none ]]; then echo "docker.io/library/busybox:1.36"; else echo "docker.io/opentsx/tools:3.0.1-local"; fi
STUB
printf '#!/usr/bin/env bash\nexec "$@"\n' > "$WORK/bin/sudo"
chmod +x "$WORK/bin/kubectl" "$WORK/bin/k3s" "$WORK/bin/sudo"

STUB_ENV=()
status() {   # NAME ARG ...: output in NAME.out, exit code in rc; STUB_ENV goes to the stand-in kubectl
  local name="$1"; shift
  rc=0
  rm -f "$WORK/calls.log"
  env -i PATH="$WORK/bin:$PATH" HOME="$WORK" WORK="$WORK" KUBECTL="$WORK/bin/kubectl" OPENTSX_ENV_FILE="$WORK/none.env" \
    ${STUB_ENV[@]+"${STUB_ENV[@]}"} bash "$K3S_DIR/50-status.sh" "$@" > "$WORK/$name.out" 2>&1 || rc=$?
}

expect() {   # NAME MESSAGE ARG ...: a usage error, exit 2, before kubectl runs
  local name="$1" message="$2"; shift 2
  status "$name" "$@"
  if (( rc != 2 )); then
    failed "$name: exit $rc, expected 2"; sed 's/^/    /' "$WORK/$name.out"
  elif ! grep -qxF -- "$message" "$WORK/$name.out"; then
    failed "$name: expected the message: $message"; sed 's/^/    /' "$WORK/$name.out"
  elif [[ -s "$WORK/calls.log" ]]; then
    failed "$name: kubectl was called"
  else
    echo "ok   $name"
  fi
}
expect unknown-option "error: --log: unknown option; expected --logs or --help" --log
expect extra-argument "error: stats: unexpected argument; 50-status.sh takes options only" --logs stats

status help --help
if (( rc != 0 )) || ! grep -qF "  2  usage error" "$WORK/help.out"; then
  failed "help: exit $rc, expected 0 and the exit code 2 in the usage"; sed 's/^/    /' "$WORK/help.out"
else
  echo "ok   help"
fi

STUB_ENV=(NAMESPACE=missing)
status no-namespace
STUB_ENV=()
message="error: namespace opentsx: not found; deploy it first with 30-deploy.sh"
if (( rc != 1 )) || ! grep -qxF -- "$message" "$WORK/no-namespace.out"; then
  failed "no-namespace: exit $rc, expected 1 and the message: $message"; sed 's/^/    /' "$WORK/no-namespace.out"
else
  echo "ok   no-namespace"
fi

status cluster-unreachable
message="error: namespace opentsx: cannot read it; check the cluster with $WORK/bin/kubectl get namespace opentsx"
if (( rc != 1 )) || ! grep -qxF -- "$message" "$WORK/cluster-unreachable.out"; then
  failed "cluster-unreachable: exit $rc, expected 1 and the message: $message"
  sed 's/^/    /' "$WORK/cluster-unreachable.out"
elif ! grep -qxF "The connection to the server 10.0.0.1:6443 was refused" "$WORK/cluster-unreachable.out"; then
  failed "cluster-unreachable: the error of kubectl is not shown"; sed 's/^/    /' "$WORK/cluster-unreachable.out"
else
  echo "ok   cluster-unreachable"
fi

STUB_ENV=(CLUSTER=up)
status up
STUB_ENV=()
if (( rc != 0 )) || grep -q '^  warn  ' "$WORK/up.out" || ! grep -qxF "  ok    checkpoint age 10 s" "$WORK/up.out" \
  || ! grep -qxF "  in k3s containerd: docker.io/opentsx/tools:3.0.1-local" "$WORK/up.out"; then
  failed "up: exit $rc, expected 0, the image, the checkpoint age and no warn line"; sed 's/^/    /' "$WORK/up.out"
else
  echo "ok   up"
fi

STUB_ENV=(CLUSTER=up IMAGES=none CKPT_AGE=400)
status old-checkpoint
STUB_ENV=()
before=$failures
if (( rc != 0 )); then failed "old-checkpoint: exit $rc, expected 0"; fi
for message in "  warn  k3s containerd: no opentsx images; import them with 11-import-images.sh" \
  "  warn  statistics checkpoint: 400 s old; the processor may be stuck, see docs/devops/operations.md R2"; do
  grep -qxF -- "$message" "$WORK/old-checkpoint.out" || failed "old-checkpoint: expected the message: $message"
done
if (( failures > before )); then sed 's/^/    /' "$WORK/old-checkpoint.out"; else echo "ok   old-checkpoint"; fi

STUB_ENV=(CLUSTER=up CKPT=missing)
status no-checkpoint
STUB_ENV=()
message="  warn  statistics checkpoint: not available (the pod does not run or has not written one yet); see $WORK/bin/kubectl -n opentsx logs deployment/opentsx-stats"
if (( rc != 0 )) || ! grep -qxF -- "$message" "$WORK/no-checkpoint.out"; then
  failed "no-checkpoint: exit $rc, expected 0 and the message: $message"; sed 's/^/    /' "$WORK/no-checkpoint.out"
else
  echo "ok   no-checkpoint"
fi

STUB_ENV=(CLUSTER=up CTR=fails)
status ctr-fails
STUB_ENV=()
message='  warn  k3s containerd: cannot list the images (ctr: failed to dial "/run/k3s/containerd/containerd.sock": connect: permission denied); check it with sudo k3s ctr images ls'
if (( rc != 0 )) || ! grep -qxF -- "$message" "$WORK/ctr-fails.out"; then
  failed "ctr-fails: exit $rc, expected 0 and the message: $message"; sed 's/^/    /' "$WORK/ctr-fails.out"
elif grep -qF "no opentsx images" "$WORK/ctr-fails.out"; then
  failed "ctr-fails: a failed listing is reported as no images"; sed 's/^/    /' "$WORK/ctr-fails.out"
else
  echo "ok   ctr-fails"
fi

if (( failures )); then echo "$failures failed"; exit 1; fi
echo "all passed"
