#!/usr/bin/env bash
# Tests deploy/k3s/70-backup.sh and stop_writers and start_writers of deploy/k3s/lib.sh: a writer whose pods outlive
# the wait stops the quiesce with exit 1 (and no MANIFEST says quiesced=true), a failed read of a Deployment is not
# taken as "not deployed", a writer that cannot be scaled back gives a warn line with kubectl's error and the command to
# run, a missing namespace, a namespace that cannot be read, a backup directory that is not empty or cannot be listed
# (it keeps its files), a PVC that cannot be read (it is not taken as missing), no PVC, a ConfigMap that cannot be
# read, a failed copy of a PVC and a helper pod that does not get ready stop the backup with exit 1, a backup that
# fails after it copied .env leaves its directory empty for the rerun, LAKE=true without --quiesce warns, and usage
# errors exit 2. A stand-in kubectl plays the cluster; needs no kubectl, no cluster, no network.
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
# ConfigMap cannot be read; READY=never: the helper pod does not get ready; SCALE_BACK=fails: scaling a Deployment to
# anything but 0 fails; FOREIGN=<file>: reading the ConfigMap also creates that file, as another writer would;
# PVC_READ=fails:<pvc>: reading that PVC fails as a lost connection to the API server does.
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
  "get pvc "*)
    [[ "${PVC_READ:-}" != "fails:$3" ]] || { echo "The connection to the server 10.0.0.1:6443 was refused" >&2; exit 1; }
    if [[ "$3" == opentsx-stats-state && "${STATS_PVC:-}" != missing ]]; then
      [[ "$*" != *"-o name"* ]] || echo "persistentvolumeclaim/$3"
      exit 0
    fi
    [[ "$*" == *--ignore-not-found* ]] && exit 0
    echo "Error from server (NotFound): persistentvolumeclaims \"$3\" not found" >&2; exit 1 ;;
  "get deployment opentsx-stats "*) echo 1 ;;
  "get deployment "*)
    [[ "$*" == *--ignore-not-found* ]] && exit 0
    echo "Error from server (NotFound): deployments.apps \"$3\" not found" >&2; exit 1 ;;
  "scale "*)
    [[ "${SCALE_BACK:-}" != fails || "$*" == *--replicas=0 ]] \
      || { echo "Error from server (Forbidden): deployments.apps \"${2#deployment/}\" is forbidden" >&2; exit 1; } ;;
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
    [[ -z "${FOREIGN:-}" ]] || touch "$FOREIGN"
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

rc=0
rm -f "$WORK/calls.log"
# shellcheck disable=SC2016  # the inner bash expands $1
env -i PATH="$WORK/bin:$PATH" HOME="$WORK" WORK="$WORK" KUBECTL="$WORK/bin/kubectl" SCALE_BACK=fails \
  bash -c 'set -euo pipefail; source "$1"; OPENTSX_NS=opentsx; stop_writers; start_writers' _ "$K3S_DIR/lib.sh" \
  > "$WORK/scale-back-fails.out" 2>&1 || rc=$?
message="  warn  opentsx-stats: not scaled back to 1 (Error from server (Forbidden): deployments.apps \"opentsx-stats\" is forbidden); run $WORK/bin/kubectl -n opentsx scale deployment/opentsx-stats --replicas=1"
if (( rc != 0 )) || ! grep -qxF -- "$message" "$WORK/scale-back-fails.out"; then
  failed "scale-back-fails: exit $rc, expected 0 and the message: $message"; sed 's/^/    /' "$WORK/scale-back-fails.out"
else
  echo "ok   scale-back-fails"
fi

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
[[ -f "$WORK/backup-not-empty/env" ]] || failed "backup-not-empty: the file env of the directory that was not empty is gone"

# ls: LS=fails refuses to list, as ls does for a directory the user may not read (root may read any); otherwise the
# real ls.
cat > "$WORK/bin/ls" <<EOF
#!/usr/bin/env bash
if [[ "\${LS:-}" == fails ]]; then echo "ls: cannot open directory '\${*: -1}': Permission denied" >&2; exit 2; fi
exec $(command -v ls) "\$@"
EOF
chmod +x "$WORK/bin/ls"
mkdir -p "$WORK/backup-unlisted"
touch "$WORK/backup-unlisted/MANIFEST" "$WORK/backup-unlisted/stats-state.tar.gz"
backup backup-unlisted LS=fails CONFIGMAP=fails -- --out "$WORK/backup-unlisted"
message="error: $WORK/backup-unlisted: cannot list it; see the error above, then give a new or empty directory with --out"
if (( rc != 1 )) || ! grep -qxF -- "$message" "$WORK/backup-unlisted.out"; then
  failed "backup-unlisted: exit $rc, expected 1 and the message: $message"; sed 's/^/    /' "$WORK/backup-unlisted.out"
elif [[ ! -f "$WORK/backup-unlisted/MANIFEST" || ! -f "$WORK/backup-unlisted/stats-state.tar.gz" ]]; then
  failed "backup-unlisted: the files of the directory that could not be listed are gone"
else
  echo "ok   backup-unlisted"
fi
backup backup-pvc-unreadable PVC_READ=fails:opentsx-data -- --out "$WORK/backup-pvc-unreadable"
message="error: PVC opentsx-data: cannot read it; check the cluster with $WORK/bin/kubectl -n opentsx get pvc opentsx-data"
if (( rc != 1 )) || ! grep -qxF -- "$message" "$WORK/backup-pvc-unreadable.out"; then
  failed "backup-pvc-unreadable: exit $rc, expected 1 and the message: $message"
  sed 's/^/    /' "$WORK/backup-pvc-unreadable.out"
elif ! grep -qxF "The connection to the server 10.0.0.1:6443 was refused" "$WORK/backup-pvc-unreadable.out"; then
  failed "backup-pvc-unreadable: the error of kubectl is not shown"; sed 's/^/    /' "$WORK/backup-pvc-unreadable.out"
elif [[ -e "$WORK/backup-pvc-unreadable" ]]; then
  failed "backup-pvc-unreadable: the backup directory was created"
else
  echo "ok   backup-pvc-unreadable"
fi
stopped backup-no-pvc "error: namespace opentsx: no OpenTSx PVC found, nothing to back up; check OPENTSX_NS in deploy/k3s/.env, or deploy it first with 30-deploy.sh" \
  STATS_PVC=missing -- --out "$WORK/backup-no-pvc"
stopped backup-exec-fails "error: PVC opentsx-stats-state: the backup into stats-state.tar.gz failed; see the error above, then run 70-backup.sh again" \
  EXEC=fails -- --out "$WORK/backup-exec-fails"
stopped backup-configmap-fails "error: ConfigMap opentsx-config: cannot read it in namespace opentsx; see the error above, then run 70-backup.sh again" \
  CONFIGMAP=fails -- --out "$WORK/backup-configmap-fails"
stopped backup-helper-not-ready "error: pod opentsx-backup-helper: not ready after 180 s; check that the image opentsx/tools:0.0.0-test is imported and that the PVCs are on one node, see $WORK/bin/kubectl -n opentsx describe pod opentsx-backup-helper" \
  OPENTSX_TAG=0.0.0-test READY=never -- --out "$WORK/backup-helper-not-ready"

# discarded NAME MESSAGE DIR [KEY=VALUE ...]: a backup into DIR that copies .env, then stops with exit 1 and MESSAGE;
# DIR is empty again when it was given empty and gone when the run created it, and a rerun into DIR writes the backup
printf 'OPENTSX_DOMAIN=wind\n' > "$WORK/current.env"
discarded() {
  local name="$1" message="$2" dir="$3" existed=false; shift 3
  [[ -d "$dir" ]] && existed=true
  backup "$name" OPENTSX_ENV_FILE="$WORK/current.env" "$@" -- --out "$dir"
  if (( rc != 1 )) || ! grep -qxF -- "$message" "$WORK/$name.out"; then
    failed "$name: exit $rc, expected 1 and the message: $message"; sed 's/^/    /' "$WORK/$name.out"
  elif ! grep -qF "ok    env (contains secrets; keep $dir private)" "$WORK/$name.out"; then
    failed "$name: .env was not copied before the failure"; sed 's/^/    /' "$WORK/$name.out"
  elif $existed && [[ ! -d "$dir" ]]; then
    failed "$name: the directory $dir that was given empty is gone"
  elif [[ -e "$dir" ]] && { ! $existed || [[ -n "$(ls -A "$dir")" ]]; }; then
    failed "$name: $dir is left with: $(find "$dir" -mindepth 1 -printf '%f ')"
  else
    backup "$name-rerun" OPENTSX_ENV_FILE="$WORK/current.env" -- --out "$dir"
    if (( rc != 0 )) || [[ ! -f "$dir/MANIFEST" || ! -f "$dir/env" ]]; then
      failed "$name: the rerun into $dir: exit $rc, expected 0, a MANIFEST and env"; sed 's/^/    /' "$WORK/$name-rerun.out"
    else
      echo "ok   $name"
    fi
  fi
}
mkdir -p "$WORK/daily"
discarded discard-configmap-fails "error: ConfigMap opentsx-config: cannot read it in namespace opentsx; see the error above, then run 70-backup.sh again" \
  "$WORK/daily" CONFIGMAP=fails
discarded discard-exec-fails "error: PVC opentsx-stats-state: the backup into stats-state.tar.gz failed; see the error above, then run 70-backup.sh again" \
  "$WORK/created" EXEC=fails

backup discard-foreign OPENTSX_ENV_FILE="$WORK/current.env" CONFIGMAP=fails FOREIGN="$WORK/foreign/other" -- --out "$WORK/foreign"
message="  warn  $WORK/foreign: not removed, it holds files this run did not write; remove them, or give a new or empty directory with --out"
if (( rc != 1 )) || ! grep -qxF -- "$message" "$WORK/discard-foreign.out"; then
  failed "discard-foreign: exit $rc, expected 1 and the message: $message"; sed 's/^/    /' "$WORK/discard-foreign.out"
elif [[ "$(ls -A "$WORK/foreign")" != other ]]; then
  failed "discard-foreign: expected only the foreign file left, found: $(find "$WORK/foreign" -mindepth 1 -printf '%f ')"
else
  echo "ok   discard-foreign"
fi

backup backup-lake-live LAKE=true -- --out "$WORK/backup-lake-live"
message="  warn  LAKE=true without --quiesce: the copy of the Iceberg catalog and MinIO may be inconsistent if they were written during the backup; run 70-backup.sh --quiesce for a consistent copy"
if (( rc != 0 )) || ! grep -qxF -- "$message" "$WORK/backup-lake-live.out"; then
  failed "backup-lake-live: exit $rc, expected 0 and the message: $message"; sed 's/^/    /' "$WORK/backup-lake-live.out"
else
  echo "ok   backup-lake-live"
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
