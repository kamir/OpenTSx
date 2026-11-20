#!/usr/bin/env bash
# Tests deploy/k3s/70-backup.sh and stop_writers of deploy/k3s/lib.sh: a writer whose pods outlive the wait stops the
# quiesce with exit 1 (and no MANIFEST says quiesced=true), a failed read of a Deployment is not taken as "not
# deployed", a missing namespace, a namespace that cannot be read, a backup directory that is not empty, no PVC, a
# ConfigMap that cannot be read, a failed copy of a PVC and a helper pod that does not get ready stop the backup with
# exit 1, and usage errors exit 2. A stand-in kubectl plays the cluster; needs no kubectl, no cluster, no network.
#
#   bash deploy/k3s/test/test-70-backup.sh
set -euo pipefail
K3S_DIR="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
failures=0
failed() { echo "FAIL $1"; failures=$((failures + 1)); }

mkdir -p "$WORK/bin"
# kubectl: opentsx-stats runs with 1 replica and the PVC opentsx-stats-state exists; nothing else is deployed.
# CLUSTER=down refuses every call as an unreachable API server does; NAMESPACE=missing answers that the namespace does
# not exist, with --ignore-not-found as kubectl does: no output and exit 0. PODS=stay: the pods of opentsx-stats outlive the wait; PODS=none: the wait finds no pod (kubectl reports
# "no matching resources found"); PODS=evicted: an evicted pod (phase Failed) is left, which the ReplicaSet does not
# delete and a field selector on the phase leaves out. EXEC=fails: the copy in the helper pod fails; CONFIGMAP=fails: the
# ConfigMap cannot be read; READY=never: the helper pod does not get ready.
cat > "$WORK/bin/kubectl" <<'EOF'
#!/usr/bin/env bash
printf '%s\n' "$*" >> "$WORK/calls.log"
if [[ "${CLUSTER:-}" == down ]]; then
  echo "The connection to the server 10.0.0.1:6443 was refused" >&2; exit 1
fi
[[ "$1" == -n ]] && shift 2
case "$*" in
  "get namespace "*)
    if [[ "${NAMESPACE:-}" == missing ]]; then
      [[ "$*" == *--ignore-not-found* ]] && exit 0
      echo "Error from server (NotFound): namespaces \"$3\" not found" >&2; exit 1
    fi
    echo "namespace/$3" ;;
  "get pvc opentsx-stats-state") [[ "${STATS_PVC:-}" != missing ]] || { echo "Error from server (NotFound): persistentvolumeclaims \"$3\" not found" >&2; exit 1; } ;;
  "get pvc "*) echo "Error from server (NotFound): persistentvolumeclaims \"$3\" not found" >&2; exit 1 ;;
  "get deployment opentsx-stats "*) echo 1 ;;
  "get deployment "*)
    [[ "$*" == *--ignore-not-found* ]] && exit 0
    echo "Error from server (NotFound): deployments.apps \"$3\" not found" >&2; exit 1 ;;
  "scale "*) exit 0 ;;
  "wait --for=delete "*)
    case "${PODS:-gone}" in
      stay) echo "error: timed out waiting for the condition on pods/opentsx-stats-5d8f9-x2x7q" >&2; exit 1 ;;
      none) echo "error: no matching resources found" >&2; exit 1 ;;
      evicted)
        [[ "$*" == *--field-selector=status.phase!=Succeeded,status.phase!=Failed* ]] \
          && { echo "error: no matching resources found" >&2; exit 1; }
        echo "error: timed out waiting for the condition on pods/opentsx-stats-5d8f9-old" >&2; exit 1 ;;
    esac ;;
  "get pod -l "*)
    case "${PODS:-gone}" in
      stay) echo pod/opentsx-stats-5d8f9-x2x7q ;;
      evicted) [[ "$*" == *--field-selector=status.phase!=Succeeded,status.phase!=Failed* ]] || echo pod/opentsx-stats-5d8f9-old ;;
    esac
    exit 0 ;;
  "delete pod "*) exit 0 ;;
  "apply -f -") cat > /dev/null ;;
  "wait --for=condition=Ready "*)
    [[ "${READY:-}" != never ]] || { echo "error: timed out waiting for the condition on pods/opentsx-backup-helper" >&2; exit 1; } ;;
  "exec "*)
    [[ "${EXEC:-}" != fails ]] || { echo "error: unable to upgrade connection: container not found (\"helper\")" >&2; exit 1; }
    printf '' | gzip ;;
  "get configmap "*)
    [[ "${CONFIGMAP:-}" != fails ]] || { echo "error: You must be logged in to the server (Unauthorized)" >&2; exit 1; }
    printf 'apiVersion: v1\nkind: ConfigMap\n' ;;
  *) echo "kubectl stand-in: unexpected call: $*" >&2; exit 1 ;;
esac
EOF
chmod +x "$WORK/bin/kubectl"

# stop NAME [KEY=VALUE ...]: stop_writers of lib.sh with the stand-in; output in NAME.out, exit code in rc
stop() {
  local name="$1"; shift
  rc=0
  rm -f "$WORK/calls.log"
  # shellcheck disable=SC2016  # the inner bash expands $1
  env -i PATH="$WORK/bin:$PATH" HOME="$WORK" WORK="$WORK" KUBECTL="$WORK/bin/kubectl" "$@" \
    bash -c 'set -euo pipefail; source "$1"; OPENTSX_NS=opentsx; stop_writers' _ "$K3S_DIR/lib.sh" \
    > "$WORK/$name.out" 2>&1 || rc=$?
}

stop quiesce-pods-stay PODS=stay
if (( rc == 0 )); then
  failed "quiesce-pods-stay: exit 0 while the pods of opentsx-stats still run"; sed 's/^/    /' "$WORK/quiesce-pods-stay.out"
elif ! grep -qF "error: opentsx-stats: pods still run 150 s after scaling to 0 (pod/opentsx-stats-5d8f9-x2x7q); stop them, then run" "$WORK/quiesce-pods-stay.out"; then
  failed "quiesce-pods-stay: expected the message naming opentsx-stats and its pod"; sed 's/^/    /' "$WORK/quiesce-pods-stay.out"
else
  echo "ok   quiesce-pods-stay"
fi

stop quiesce-cluster-down CLUSTER=down
if (( rc == 0 )); then
  failed "quiesce-cluster-down: exit 0 although no Deployment could be read"; sed 's/^/    /' "$WORK/quiesce-cluster-down.out"
elif ! grep -qF "error: opentsx-stats: cannot read the Deployment; check the cluster with" "$WORK/quiesce-cluster-down.out"; then
  failed "quiesce-cluster-down: expected the message naming opentsx-stats"; sed 's/^/    /' "$WORK/quiesce-cluster-down.out"
else
  echo "ok   quiesce-cluster-down"
fi

for pods in gone none evicted; do
  stop "quiesce-pods-$pods" PODS="$pods"
  if (( rc != 0 )) || ! grep -qF "opentsx-stats scaled to 0 (was 1)" "$WORK/quiesce-pods-$pods.out"; then
    failed "quiesce-pods-$pods: exit $rc, expected 0 and the line 'opentsx-stats scaled to 0 (was 1)'"
    sed 's/^/    /' "$WORK/quiesce-pods-$pods.out"
  else
    echo "ok   quiesce-pods-$pods"
  fi
done

# backup NAME [KEY=VALUE ...] -- ARG ...: 70-backup.sh with the stand-in; output in NAME.out, exit code in rc
backup() {
  local name="$1"; shift
  local -a envs=()
  while (( $# )) && [[ "$1" != -- ]]; do envs+=("$1"); shift; done
  if (( $# )); then shift; fi
  rc=0
  rm -f "$WORK/calls.log"
  env -i PATH="$WORK/bin:$PATH" HOME="$WORK" WORK="$WORK" KUBECTL="$WORK/bin/kubectl" \
    OPENTSX_ENV_FILE="$WORK/none.env" "${envs[@]}" \
    bash "$K3S_DIR/70-backup.sh" "$@" > "$WORK/$name.out" 2>&1 || rc=$?
}

backup backup-quiesce-pods-stay PODS=stay -- --quiesce --out "$WORK/backup"
if (( rc != 1 )); then
  failed "backup-quiesce-pods-stay: exit $rc, expected 1"; sed 's/^/    /' "$WORK/backup-quiesce-pods-stay.out"
elif ! grep -qxF "error: opentsx-stats: pods still run 150 s after scaling to 0 (pod/opentsx-stats-5d8f9-x2x7q); stop them, then run 70-backup.sh again" "$WORK/backup-quiesce-pods-stay.out"; then
  failed "backup-quiesce-pods-stay: expected the message naming opentsx-stats and 70-backup.sh"
  sed 's/^/    /' "$WORK/backup-quiesce-pods-stay.out"
elif [[ -e "$WORK/backup/MANIFEST" ]]; then
  failed "backup-quiesce-pods-stay: MANIFEST written"; sed 's/^/    /' "$WORK/backup/MANIFEST"
elif ! grep -qx 'scale deployment/opentsx-stats --replicas=1' <(sed 's/^-n opentsx //' "$WORK/calls.log"); then
  failed "backup-quiesce-pods-stay: opentsx-stats was not scaled back to 1"; sed 's/^/    /' "$WORK/calls.log"
else
  echo "ok   backup-quiesce-pods-stay"
fi
backup backup-quiesce -- --quiesce --out "$WORK/backup-ok"
if (( rc != 0 )) || ! grep -qx 'quiesced=true' "$WORK/backup-ok/MANIFEST" 2>/dev/null; then
  failed "backup-quiesce: exit $rc, expected 0 and quiesced=true in MANIFEST"; sed 's/^/    /' "$WORK/backup-quiesce.out"
else
  echo "ok   backup-quiesce"
fi

backup backup-no-env -- --out "$WORK/backup-no-env"
message="  warn  $WORK/none.env: not found; the backup holds no configuration, run 70-backup.sh in the checkout that configured the installation"
if (( rc != 0 )) || ! grep -qxF -- "$message" "$WORK/backup-no-env.out"; then
  failed "backup-no-env: exit $rc, expected 0 and the message: $message"; sed 's/^/    /' "$WORK/backup-no-env.out"
elif [[ -e "$WORK/backup-no-env/env" ]]; then
  failed "backup-no-env: an env file was written"
else
  echo "ok   backup-no-env"
fi

backup backup-no-namespace NAMESPACE=missing -- --out "$WORK/backup-no-namespace"
message="error: namespace opentsx: not found; check OPENTSX_NS in deploy/k3s/.env and the kubectl context, or deploy it first with 30-deploy.sh"
if (( rc != 1 )) || ! grep -qxF -- "$message" "$WORK/backup-no-namespace.out"; then
  failed "backup-no-namespace: exit $rc, expected 1 and the message: $message"
  sed 's/^/    /' "$WORK/backup-no-namespace.out"
elif [[ -e "$WORK/backup-no-namespace" ]]; then
  failed "backup-no-namespace: the backup directory was created"
else
  echo "ok   backup-no-namespace"
fi

backup backup-cluster-down CLUSTER=down -- --out "$WORK/backup-cluster-down"
message="error: namespace opentsx: cannot read it; check the cluster with $WORK/bin/kubectl get namespace opentsx"
if (( rc != 1 )) || ! grep -qxF -- "$message" "$WORK/backup-cluster-down.out"; then
  failed "backup-cluster-down: exit $rc, expected 1 and the message: $message"
  sed 's/^/    /' "$WORK/backup-cluster-down.out"
elif ! grep -qxF "The connection to the server 10.0.0.1:6443 was refused" "$WORK/backup-cluster-down.out"; then
  failed "backup-cluster-down: the error of kubectl is not shown"; sed 's/^/    /' "$WORK/backup-cluster-down.out"
elif [[ -e "$WORK/backup-cluster-down" ]]; then
  failed "backup-cluster-down: the backup directory was created"
else
  echo "ok   backup-cluster-down"
fi

# stopped NAME MESSAGE [KEY=VALUE ...] -- ARG ...: exit 1 with MESSAGE and no MANIFEST in the backup directory
stopped() {
  local name="$1" message="$2"; shift 2
  backup "$name" "$@"
  if (( rc != 1 )) || ! grep -qxF -- "$message" "$WORK/$name.out"; then
    failed "$name: exit $rc, expected 1 and the message: $message"; sed 's/^/    /' "$WORK/$name.out"
  elif [[ -e "$WORK/$name/MANIFEST" ]]; then
    failed "$name: MANIFEST written"
  else
    echo "ok   $name"
  fi
}
mkdir -p "$WORK/backup-not-empty"
touch "$WORK/backup-not-empty/env"
stopped backup-not-empty "error: $WORK/backup-not-empty: exists and is not empty; give a new or empty directory with --out" \
  -- --out "$WORK/backup-not-empty"
stopped backup-no-pvc "error: namespace opentsx: no OpenTSx PVC found; nothing to back up" \
  STATS_PVC=missing -- --out "$WORK/backup-no-pvc"
stopped backup-exec-fails "error: PVC opentsx-stats-state: the backup into stats-state.tar.gz failed; see the error above, then run 70-backup.sh again" \
  EXEC=fails -- --out "$WORK/backup-exec-fails"
stopped backup-configmap-fails "error: ConfigMap opentsx-config: cannot read it in namespace opentsx; see the error above, then run 70-backup.sh again" \
  CONFIGMAP=fails -- --out "$WORK/backup-configmap-fails"
stopped backup-helper-not-ready "error: pod opentsx-backup-helper: not ready after 180 s; check that the image opentsx/tools:0.0.0-test is imported and that the PVCs are on one node, see $WORK/bin/kubectl -n opentsx describe pod opentsx-backup-helper" \
  OPENTSX_TAG=0.0.0-test READY=never -- --out "$WORK/backup-helper-not-ready"

# expect NAME MESSAGE ARG ...: a usage error, exit 2, before kubectl runs
expect() {
  local name="$1" message="$2"; shift 2
  backup "$name" -- "$@"
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
expect unknown-option "error: --nosuch: unknown option; expected --out DIR, --quiesce, --no-data or --help" --nosuch
expect out-without-value "error: --out: missing value; expected a directory" --out
backup help -- --help
if (( rc != 0 )) || ! grep -qF "  2  usage error" "$WORK/help.out"; then
  failed "help: exit $rc, expected 0 and the exit code 2 in the usage"; sed 's/^/    /' "$WORK/help.out"
else
  echo "ok   help"
fi

if (( failures )); then echo "$failures failed"; exit 1; fi
echo "all passed"
