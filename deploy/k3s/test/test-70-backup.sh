#!/usr/bin/env bash
# Tests deploy/k3s/70-backup.sh and stop_writers of deploy/k3s/lib.sh: a writer whose pods outlive the wait stops the
# quiesce with exit 1 (and no MANIFEST says quiesced=true), a failed read of a Deployment is not taken as "not
# deployed", and usage errors exit 2. A stand-in kubectl plays the cluster; needs no kubectl, no cluster, no network.
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
# CLUSTER=down refuses every call as an unreachable API server does. PODS=stay: the pods of opentsx-stats outlive
# the wait; PODS=none: the wait finds no pod (kubectl reports "no matching resources found"); PODS=evicted: an evicted
# pod (phase Failed) is left, which the ReplicaSet does not delete and a field selector on the phase leaves out.
cat > "$WORK/bin/kubectl" <<'EOF'
#!/usr/bin/env bash
printf '%s\n' "$*" >> "$WORK/calls.log"
if [[ "${CLUSTER:-}" == down ]]; then
  echo "The connection to the server 10.0.0.1:6443 was refused" >&2; exit 1
fi
[[ "$1" == -n ]] && shift 2
case "$*" in
  "get namespace "*|"get pvc opentsx-stats-state") exit 0 ;;
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
  "wait --for=condition=Ready "*) exit 0 ;;
  "exec "*) printf '' | gzip ;;
  "get configmap "*) printf 'apiVersion: v1\nkind: ConfigMap\n' ;;
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
