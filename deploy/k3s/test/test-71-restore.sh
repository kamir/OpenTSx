#!/usr/bin/env bash
# Tests the arguments of deploy/k3s/71-restore.sh: an unknown option, --only without its value or with an unknown part,
# a second directory and no directory exit 2 before kubectl runs, and --help lists the exit codes; a directory without a
# MANIFEST, an archive that is missing, damaged or not gzip, a missing env and nothing to restore exit 1 before kubectl
# runs; a declined question and a question at the end of standard input exit 1 with their message on stderr before
# kubectl runs; an answer without a newline at the end counts as given; a missing namespace, a namespace that cannot be
# read, a missing PVC, a PVC that cannot be read and a failed step in the helper pod exit 1 with their message; with env
# among the parts a missing namespace or PVC says how to restore env first (env, 20-configure.sh, 10-build.sh,
# 11-import-images.sh, 30-deploy.sh, the volumes), following that hint after 20-configure.sh rewrote .env keeps the
# copy of the .env from before the restore, and restoring env again then stops before it changes .env or that copy,
# with or without volumes; a namespace that cannot be read, a missing namespace or PVC and a failed volume step leave
# .env as it is; env alone needs no cluster; a MANIFEST of file= lines only restores; a current .env that cannot be kept
# exits 1 and stays as it is; the rerun after a failed volume step restores into the same namespace; a .env that
# already equals the backup's env stays, and the message names the kept copy only when there is one; with env and
# volumes a backup whose MANIFEST names another namespace than the current .env, or none, or whose env names another
# namespace than its MANIFEST, exits 1 before the question and before anything changes, naming the env file that sets
# OPENTSX_NS or else env.example, and so does a backup of another namespace than OPENTSX_NS of the shell, naming the
# shell; volumes alone restore into the namespace of the current .env; a .env that cannot be replaced exits 1 and names
# the restored PVCs; a copy of the backup's env that fails halfway leaves .env as it was, and the rerun the message
# names restores it; a .env that is a link stays a link, and one that links to a path that cannot be resolved exits
# 1, stays as it is and the message names that path; git ignores deploy/k3s/.env.before-restore and
# deploy/k3s/.env.restoring. A stand-in kubectl records any call, a stand-in cp fails on request; needs git, no kubectl,
# no cluster, no network.
#
#   bash deploy/k3s/test/test-71-restore.sh
set -euo pipefail
K3S_DIR="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
failures=0
failed() { echo "FAIL $1"; failures=$((failures + 1)); }

mkdir -p "$WORK/bin" "$WORK/backup"
# kubectl records its call and fails; none is expected to run. CLUSTER=up answers as a cluster with the namespace,
# the PVCs and no writer deployed; CLUSTER=down refuses every call as an unreachable API server does;
# NAMESPACE=missing: no namespace, with --ignore-not-found as kubectl does: no output and exit 0; PVC_STATE=missing: no
# PVC, the same way; PVC_STATE=unreadable: the PVC read is refused as by an unreachable API server; EXEC=empty-fails or
# unpack-fails: that step in the helper pod fails.
cat > "$WORK/bin/kubectl" <<'STUB'
#!/usr/bin/env bash
printf '%s\n' "$*" >> "$WORK/calls.log"
if [[ "${CLUSTER:-}" == down ]]; then
  echo "The connection to the server 10.0.0.1:6443 was refused" >&2; exit 1
fi
[[ "${CLUSTER:-}" == up ]] || exit 1
[[ "$1" == -n ]] && shift 2
case "$*" in
  "get namespace "*)
    if [[ "${NAMESPACE:-}" == missing ]]; then
      [[ "$*" == *--ignore-not-found* ]] && exit 0
      echo "Error from server (NotFound): namespaces \"$3\" not found" >&2; exit 1
    fi
    echo "namespace/$3" ;;
  "get deployment "*|"delete pod "*|"wait --for=condition=Ready "*) exit 0 ;;
  "get pvc "*)
    if [[ "${PVC_STATE:-}" == unreadable ]]; then
      echo "The connection to the server 10.0.0.1:6443 was refused" >&2; exit 1
    elif [[ "${PVC_STATE:-}" == missing ]]; then
      [[ "$*" == *--ignore-not-found* ]] && exit 0
      echo "Error from server (NotFound): persistentvolumeclaims \"$3\" not found" >&2; exit 1
    fi
    echo "persistentvolumeclaim/$3" ;;
  "apply -f -") cat > /dev/null ;;
  "exec opentsx-restore-helper -- sh -c "*) [[ "${EXEC:-}" != empty-fails ]] || { echo "find: permission denied" >&2; exit 1; } ;;
  "exec -i opentsx-restore-helper -- tar xzf "*)
    cat > /dev/null
    [[ "${EXEC:-}" != unpack-fails ]] || { echo "tar: write error: no space left on device" >&2; exit 1; } ;;
  *) echo "kubectl stand-in: unexpected call: $*" >&2; exit 1 ;;
esac
STUB
chmod +x "$WORK/bin/kubectl"
printf 'created=2026-01-01T00:00:00Z\nnamespace=opentsx\n' > "$WORK/backup/MANIFEST"

STUB_ENV=()
restore() {   # NAME ARG ...: output in NAME.out, exit code in rc; STUB_ENV goes to the stand-in kubectl
  local name="$1"; shift
  rc=0
  rm -f "$WORK/calls.log"
  env -i PATH="$WORK/bin:$PATH" HOME="$WORK" WORK="$WORK" KUBECTL="$WORK/bin/kubectl" OPENTSX_ENV_FILE="$WORK/none.env" \
    ${STUB_ENV[@]+"${STUB_ENV[@]}"} bash "$K3S_DIR/71-restore.sh" "$@" > "$WORK/$name.out" 2>&1 < /dev/null || rc=$?
}

expect() {   # NAME MESSAGE ARG ...: a usage error, exit 2, before kubectl runs
  local name="$1" message="$2"; shift 2
  restore "$name" "$@"
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
expect unknown-option "error: --force: unknown option; expected --only LIST, --yes or --help" "$WORK/backup" --force
expect only-without-value "error: --only: missing value; expected a list of stats-state, data, iceberg-catalog, minio and env" "$WORK/backup" --only
expect only-unknown-part "error: --only: \"stats\" is not a part; expected stats-state, data, iceberg-catalog, minio or env" "$WORK/backup" --only data,stats
expect second-directory "error: $WORK/other: unexpected argument; 71-restore.sh takes one backup directory" "$WORK/backup" "$WORK/other"
expect no-directory "error: DIR: missing; expected 71-restore.sh DIR [--only LIST] [--yes]" --yes

restore help --help
if (( rc != 0 )) || ! grep -qF "  2  usage error" "$WORK/help.out"; then
  failed "help: exit $rc, expected 0 and the exit code 2 in the usage"; sed 's/^/    /' "$WORK/help.out"
else
  echo "ok   help"
fi

refused() {   # NAME MESSAGE ARG ...: a damaged backup, exit 1, before kubectl runs
  local name="$1" message="$2"; shift 2
  restore "$name" "$@"
  if (( rc != 1 )); then
    failed "$name: exit $rc, expected 1"; sed 's/^/    /' "$WORK/$name.out"
  elif ! grep -qxF -- "$message" "$WORK/$name.out"; then
    failed "$name: expected the message: $message"; sed 's/^/    /' "$WORK/$name.out"
  elif [[ -s "$WORK/calls.log" ]]; then
    failed "$name: kubectl was called"
  else
    echo "ok   $name"
  fi
}
mkdir -p "$WORK/empty" "$WORK/damaged"
refused no-manifest "error: $WORK/empty/MANIFEST: not found; expected a directory written by 70-backup.sh" "$WORK/empty" --yes
printf 'data' > "$WORK/damaged/data.tar.gz"
printf 'file=%064d 4 data.tar.gz\n' 0 > "$WORK/damaged/MANIFEST"
refused checksum-mismatch "error: data.tar.gz: checksum mismatch; the backup is damaged, use another backup" \
  "$WORK/damaged" --yes
mkdir -p "$WORK/incomplete" "$WORK/not-gzip" "$WORK/no-env" "$WORK/good"
printf 'file=%064d 4 data.tar.gz\n' 0 > "$WORK/incomplete/MANIFEST"
refused incomplete "error: data.tar.gz: listed in MANIFEST but missing; the backup is incomplete, use another backup" \
  "$WORK/incomplete" --yes
printf 'data' > "$WORK/not-gzip/data.tar.gz"
printf 'file=%s 4 data.tar.gz\n' "$(sha256sum "$WORK/not-gzip/data.tar.gz" | cut -d' ' -f1)" > "$WORK/not-gzip/MANIFEST"
refused not-gzip "error: data.tar.gz: not a valid gzip archive; the backup is damaged, use another backup" \
  "$WORK/not-gzip" --yes
printf 'created=2026-01-01T00:00:00Z\n' > "$WORK/no-env/MANIFEST"
refused env-missing "error: $WORK/no-env/env: not found; the backup holds no configuration, leave env out of --only" \
  "$WORK/no-env" --only env --yes
refused nothing-to-restore "error: $WORK/no-env: nothing to restore; the backup holds none of the parts asked for, see the file list in $WORK/no-env/MANIFEST" \
  "$WORK/no-env" --yes

printf '' | gzip > "$WORK/good/data.tar.gz"
printf 'created=2026-01-01T00:00:00Z\nnamespace=opentsx\nfile=%s %s data.tar.gz\n' \
  "$(sha256sum "$WORK/good/data.tar.gz" | cut -d' ' -f1)" "$(wc -c < "$WORK/good/data.tar.gz")" > "$WORK/good/MANIFEST"
rc=0
rm -f "$WORK/calls.log"
env -i PATH="$WORK/bin:$PATH" HOME="$WORK" WORK="$WORK" KUBECTL="$WORK/bin/kubectl" OPENTSX_ENV_FILE="$WORK/none.env" \
  bash "$K3S_DIR/71-restore.sh" "$WORK/good" > "$WORK/declined.out" 2> "$WORK/declined.err" <<< n || rc=$?
message="error: 71-restore.sh: aborted at the question, nothing was changed; answer y to restore, or pass --yes"
if (( rc != 1 )) || [[ "$(cat "$WORK/declined.err")" != "$message" ]]; then
  failed "declined: exit $rc, expected 1 and only the message on stderr: $message"
  sed 's/^/    stderr: /' "$WORK/declined.err"; sed 's/^/    stdout: /' "$WORK/declined.out"
elif [[ "$(tail -n 1 "$WORK/declined.out")" != "This REPLACES the content of: data in namespace opentsx." ]]; then
  failed "declined: stdout does not end with the question"; sed 's/^/    stdout: /' "$WORK/declined.out"
elif [[ -s "$WORK/calls.log" ]]; then
  failed "declined: kubectl was called"
else
  echo "ok   declined"
fi

rc=0
rm -f "$WORK/calls.log"
env -i PATH="$WORK/bin:$PATH" HOME="$WORK" WORK="$WORK" KUBECTL="$WORK/bin/kubectl" OPENTSX_ENV_FILE="$WORK/none.env" \
  bash "$K3S_DIR/71-restore.sh" "$WORK/good" > "$WORK/no-answer.out" 2> "$WORK/no-answer.err" < /dev/null || rc=$?
message="error: 71-restore.sh: no answer on standard input; pass --yes to run without the question"
if (( rc != 1 )) || [[ "$(cat "$WORK/no-answer.err")" != "$message" ]]; then
  failed "no-answer: exit $rc, expected 1 and only the message on stderr: $message"
  sed 's/^/    stderr: /' "$WORK/no-answer.err"; sed 's/^/    stdout: /' "$WORK/no-answer.out"
elif [[ -s "$WORK/calls.log" ]]; then
  failed "no-answer: kubectl was called"
else
  echo "ok   no-answer"
fi

answer() {   # NAME INPUT [KEY=VALUE ...]: restore of the good backup without --yes, INPUT without a newline at the end
  local name="$1" input="$2"; shift 2
  rc=0
  rm -f "$WORK/calls.log"
  printf '%s' "$input" | env -i PATH="$WORK/bin:$PATH" HOME="$WORK" WORK="$WORK" KUBECTL="$WORK/bin/kubectl" \
    OPENTSX_ENV_FILE="$WORK/none.env" "$@" bash "$K3S_DIR/71-restore.sh" "$WORK/good" \
    > "$WORK/$name.out" 2> "$WORK/$name.err" || rc=$?
}
answer answered-no-newline y CLUSTER=up
if (( rc != 0 )) || ! grep -qF "data.tar.gz -> PVC opentsx-data" "$WORK/answered-no-newline.out"; then
  failed "answered-no-newline: exit $rc, expected 0 and data.tar.gz restored after the answer y without a newline"
  sed 's/^/    stderr: /' "$WORK/answered-no-newline.err"; sed 's/^/    stdout: /' "$WORK/answered-no-newline.out"
else
  echo "ok   answered-no-newline"
fi
answer declined-no-newline n
message="error: 71-restore.sh: aborted at the question, nothing was changed; answer y to restore, or pass --yes"
if (( rc != 1 )) || [[ "$(cat "$WORK/declined-no-newline.err")" != "$message" ]]; then
  failed "declined-no-newline: exit $rc, expected 1 and only the message on stderr: $message"
  sed 's/^/    stderr: /' "$WORK/declined-no-newline.err"; sed 's/^/    stdout: /' "$WORK/declined-no-newline.out"
elif [[ -s "$WORK/calls.log" ]]; then
  failed "declined-no-newline: kubectl was called"
else
  echo "ok   declined-no-newline"
fi

failing() {   # NAME MESSAGE KEY=VALUE ...: restore of the good backup against the stand-in cluster, exit 1 with MESSAGE
  local name="$1" message="$2"; shift 2
  STUB_ENV=(CLUSTER=up "$@")
  restore "$name" "$WORK/good" --yes
  STUB_ENV=()
  if (( rc != 1 )) || ! grep -qxF -- "$message" "$WORK/$name.out"; then
    failed "$name: exit $rc, expected 1 and the message: $message"; sed 's/^/    /' "$WORK/$name.out"
  else
    echo "ok   $name"
  fi
}
failing no-namespace "error: namespace opentsx: not found; run 20-configure.sh and 30-deploy.sh first" NAMESPACE=missing
failing pvc-missing "error: PVC opentsx-data: not found in namespace opentsx; run 30-deploy.sh first (opentsx-iceberg-catalog needs LAKE=true, opentsx-minio LAKE=true and LAKE_MINIO=true)" \
  PVC_STATE=missing
failing pvc-unreadable "error: PVC opentsx-data: cannot read it; check the cluster with $WORK/bin/kubectl -n opentsx get pvc opentsx-data" \
  PVC_STATE=unreadable
if ! grep -qxF "The connection to the server 10.0.0.1:6443 was refused" "$WORK/pvc-unreadable.out"; then
  failed "pvc-unreadable: the error of kubectl is not shown"; sed 's/^/    /' "$WORK/pvc-unreadable.out"
fi
failing empty-fails "error: PVC opentsx-data: emptying it failed; see the error above, then run 71-restore.sh again" \
  EXEC=empty-fails
failing unpack-fails "error: PVC opentsx-data: unpacking data.tar.gz failed; see the error above, then run 71-restore.sh again" \
  EXEC=unpack-fails
STUB_ENV=(CLUSTER=down)
restore cluster-down "$WORK/good" --yes
STUB_ENV=()
message="error: namespace opentsx: cannot read it; check the cluster with $WORK/bin/kubectl get namespace opentsx"
if (( rc != 1 )) || ! grep -qxF -- "$message" "$WORK/cluster-down.out"; then
  failed "cluster-down: exit $rc, expected 1 and the message: $message"; sed 's/^/    /' "$WORK/cluster-down.out"
elif ! grep -qxF "The connection to the server 10.0.0.1:6443 was refused" "$WORK/cluster-down.out"; then
  failed "cluster-down: the error of kubectl is not shown"; sed 's/^/    /' "$WORK/cluster-down.out"
else
  echo "ok   cluster-down"
fi
STUB_ENV=(CLUSTER=up)
restore restored "$WORK/good" --yes
STUB_ENV=()
if (( rc != 0 )) || ! grep -qF "data.tar.gz -> PVC opentsx-data" "$WORK/restored.out"; then
  failed "restored: exit $rc, expected 0 and data.tar.gz restored into PVC opentsx-data"; sed 's/^/    /' "$WORK/restored.out"
else
  echo "ok   restored"
fi

mkdir -p "$WORK/files-only"
cp "$WORK/good/data.tar.gz" "$WORK/files-only/"
grep '^file=' "$WORK/good/MANIFEST" > "$WORK/files-only/MANIFEST"
STUB_ENV=(CLUSTER=up)
restore files-only "$WORK/files-only" --yes
STUB_ENV=()
if (( rc != 0 )) || ! grep -qF "data.tar.gz -> PVC opentsx-data" "$WORK/files-only.out"; then
  failed "files-only: exit $rc, expected 0 and data.tar.gz restored from a MANIFEST of file= lines only"
  sed 's/^/    /' "$WORK/files-only.out"
else
  echo "ok   files-only"
fi

mkdir -p "$WORK/with-env"
printf 'OPENTSX_DOMAIN=backup\n' > "$WORK/with-env/env"
printf 'created=2026-01-01T00:00:00Z\n' > "$WORK/with-env/MANIFEST"
printf 'OPENTSX_DOMAIN=previous\n' > "$WORK/current.env"
ln -s "$WORK/nowhere/env" "$WORK/current.env.before-restore"
STUB_ENV=(OPENTSX_ENV_FILE="$WORK/current.env")
restore env-not-kept "$WORK/with-env" --only env --yes
STUB_ENV=()
message="error: $WORK/current.env.before-restore: cannot keep the current .env there; see the error above, nothing was restored"
if (( rc != 1 )) || ! grep -qxF -- "$message" "$WORK/env-not-kept.out"; then
  failed "env-not-kept: exit $rc, expected 1 and the message: $message"; sed 's/^/    /' "$WORK/env-not-kept.out"
elif [[ "$(cat "$WORK/current.env")" != OPENTSX_DOMAIN=previous ]]; then
  failed "env-not-kept: the current .env was replaced"; sed 's/^/    /' "$WORK/current.env"
else
  echo "ok   env-not-kept"
fi

mkdir -p "$WORK/env-data"
printf 'OPENTSX_DOMAIN=frombackup\n' > "$WORK/env-data/env"
cp "$WORK/good/data.tar.gz" "$WORK/env-data/"
{ echo "namespace=opentsx"
  (cd "$WORK/env-data" && for f in env data.tar.gz; do echo "file=$(sha256sum "$f" | cut -d' ' -f1) $(wc -c < "$f") $f"; done)
} > "$WORK/env-data/MANIFEST"

printf 'OPENTSX_DOMAIN=original\n' > "$WORK/down.env"
STUB_ENV=(CLUSTER=down OPENTSX_ENV_FILE="$WORK/down.env")
restore env-cluster-down "$WORK/env-data" --only env,data --yes
STUB_ENV=()
message="error: namespace opentsx: cannot read it; check the cluster with $WORK/bin/kubectl get namespace opentsx"
if (( rc != 1 )) || ! grep -qxF -- "$message" "$WORK/env-cluster-down.out"; then
  failed "env-cluster-down: exit $rc, expected 1 and the message: $message"; sed 's/^/    /' "$WORK/env-cluster-down.out"
elif [[ "$(cat "$WORK/down.env")" != OPENTSX_DOMAIN=original || -e "$WORK/down.env.before-restore" ]]; then
  failed "env-cluster-down: .env was replaced or kept aside although the namespace could not be read"
  sed 's/^/    /' "$WORK/down.env"
else
  echo "ok   env-cluster-down"
fi
STUB_ENV=(CLUSTER=down OPENTSX_ENV_FILE="$WORK/down.env")
restore env-only-no-cluster "$WORK/env-data" --only env --yes
STUB_ENV=()
if (( rc != 0 )) || [[ "$(cat "$WORK/down.env")" != OPENTSX_DOMAIN=frombackup ]]; then
  failed "env-only-no-cluster: exit $rc, expected 0 and .env restored without the cluster"; sed 's/^/    /' "$WORK/env-only-no-cluster.out"
elif [[ -s "$WORK/calls.log" ]]; then
  failed "env-only-no-cluster: kubectl was called"; sed 's/^/    /' "$WORK/calls.log"
else
  echo "ok   env-only-no-cluster"
fi

hint="nothing was restored; run 71-restore.sh $WORK/env-data --only env, then 20-configure.sh, 10-build.sh, 11-import-images.sh and 30-deploy.sh, then 71-restore.sh $WORK/env-data --only data"
for case in "NAMESPACE=missing|error: namespace opentsx: not found; $hint" \
  "PVC_STATE=missing|error: PVC opentsx-data: not found in namespace opentsx; $hint"; do
  name="env-${case%%=*}"; name="${name,,}"; message="${case#*|}"
  printf 'OPENTSX_DOMAIN=original\n' > "$WORK/missing.env"
  STUB_ENV=(CLUSTER=up "${case%%|*}" OPENTSX_ENV_FILE="$WORK/missing.env")
  restore "$name" "$WORK/env-data" --only env,data --yes
  STUB_ENV=()
  if (( rc != 1 )) || ! grep -qxF -- "$message" "$WORK/$name.out"; then
    failed "$name: exit $rc, expected 1 and the message: $message"; sed 's/^/    /' "$WORK/$name.out"
  elif [[ "$(cat "$WORK/missing.env")" != OPENTSX_DOMAIN=original || -e "$WORK/missing.env.before-restore" ]]; then
    failed "$name: .env was replaced or kept aside although nothing was restored"; sed 's/^/    /' "$WORK/missing.env"
  else
    echo "ok   $name"
  fi
done

printf 'OPENTSX_DOMAIN=original\n' > "$WORK/flow.env"
STUB_ENV=(CLUSTER=up PVC_STATE=missing OPENTSX_ENV_FILE="$WORK/flow.env")
restore flow-hint "$WORK/env-data" --only env,data --yes
STUB_ENV=(CLUSTER=down OPENTSX_ENV_FILE="$WORK/flow.env")
restore flow-env "$WORK/env-data" --only env --yes
STUB_ENV=()
configured="# written by deploy/k3s/20-configure.sh on 2026-01-02T00:00:00Z; contains secrets, never commit
OPENTSX_DOMAIN=frombackup"
printf '%s\n' "$configured" > "$WORK/flow.env"
last="$(sed -n 's/^error: .*, then 71-restore\.sh \(.*\)$/\1/p' "$WORK/flow-hint.out")"
if [[ -z "$last" ]]; then
  failed "hint-flow: the hint does not end with the 71-restore.sh command to run after 30-deploy.sh"
  sed 's/^/    /' "$WORK/flow-hint.out"
else
  read -ra args <<< "$last"
  STUB_ENV=(CLUSTER=up OPENTSX_ENV_FILE="$WORK/flow.env")
  restore flow-volumes "${args[@]}" --yes
  STUB_ENV=()
  if (( rc != 0 )) || ! grep -qF "data.tar.gz -> PVC opentsx-data" "$WORK/flow-volumes.out"; then
    failed "hint-flow: exit $rc, expected 0 and data.tar.gz restored by 71-restore.sh $last"
    sed 's/^/    /' "$WORK/flow-volumes.out"
  elif [[ "$(cat "$WORK/flow.env.before-restore")" != OPENTSX_DOMAIN=original ]]; then
    failed "hint-flow: the kept copy of the .env from before the restore was overwritten"
    sed 's/^/    /' "$WORK/flow.env.before-restore"
  elif [[ "$(cat "$WORK/flow.env")" != "$configured" ]]; then
    failed "hint-flow: the .env written by 20-configure.sh was replaced"; sed 's/^/    /' "$WORK/flow.env"
  else
    echo "ok   hint-flow"
  fi
fi

# restoring env again after 20-configure.sh rewrote the restored .env would overwrite the only copy of the .env from
# before the first restore
for case in "env|CLUSTER=down" "env,data|CLUSTER=up"; do
  parts="${case%%|*}"; name="kept-copy-differs-${parts//,/-}"
  printf '%s\n' "$configured" > "$WORK/kept.env"
  printf 'OPENTSX_DOMAIN=original\n' > "$WORK/kept.env.before-restore"
  STUB_ENV=("${case#*|}" OPENTSX_ENV_FILE="$WORK/kept.env")
  restore "$name" "$WORK/env-data" --only "$parts" --yes
  STUB_ENV=()
  message="error: $WORK/kept.env.before-restore: holds the .env from before an earlier restore; leave env out of --only, or move that file away first"
  if (( rc != 1 )) || ! grep -qxF -- "$message" "$WORK/$name.out"; then
    failed "$name: exit $rc, expected 1 and the message: $message"; sed 's/^/    /' "$WORK/$name.out"
  elif [[ "$(cat "$WORK/kept.env")" != "$configured" || "$(cat "$WORK/kept.env.before-restore")" != OPENTSX_DOMAIN=original ]]; then
    failed "$name: .env or .env.before-restore was changed"
    sed 's/^/    env: /' "$WORK/kept.env"; sed 's/^/    kept: /' "$WORK/kept.env.before-restore"
  elif [[ -e "$WORK/calls.log" ]] && grep -qE '^-n [^ ]+ (scale|exec|apply) |^apply ' "$WORK/calls.log"; then
    failed "$name: a writer was stopped or the helper pod started"; sed 's/^/    call: /' "$WORK/calls.log"
  else
    echo "ok   $name"
  fi
done

mkdir -p "$WORK/other-ns"
printf 'OPENTSX_NS=current\nOPENTSX_DOMAIN=frombackup\n' > "$WORK/other-ns/env"
cp "$WORK/good/data.tar.gz" "$WORK/other-ns/"
{ echo "namespace=current"
  (cd "$WORK/other-ns" && for f in env data.tar.gz; do echo "file=$(sha256sum "$f" | cut -d' ' -f1) $(wc -c < "$f") $f"; done)
} > "$WORK/other-ns/MANIFEST"
printf 'OPENTSX_NS=current\n' > "$WORK/rerun.env"
STUB_ENV=(CLUSTER=up EXEC=unpack-fails OPENTSX_ENV_FILE="$WORK/rerun.env")
restore rerun-first "$WORK/other-ns" --only env,data --yes
first=$rc
first_env="$(cat "$WORK/rerun.env")"
STUB_ENV=(CLUSTER=up OPENTSX_ENV_FILE="$WORK/rerun.env")
restore rerun-second "$WORK/other-ns" --only env,data --yes
STUB_ENV=()
if (( first != 1 || rc != 0 )); then
  failed "rerun: exit $first and $rc, expected 1 for the failed unpack and 0 for the rerun"
  sed 's/^/    first: /' "$WORK/rerun-first.out"; sed 's/^/    second: /' "$WORK/rerun-second.out"
elif [[ "$first_env" != OPENTSX_NS=current ]]; then
  failed "rerun: the failed unpack left .env replaced: $first_env"
elif grep -qvE '^(-n current |get namespace current |apply -f -$)' "$WORK/calls.log" \
  || ! grep -qxF "get namespace current --ignore-not-found -o name" "$WORK/calls.log"; then
  failed "rerun: the rerun did not restore into the namespace current"; sed 's/^/    calls: /' "$WORK/calls.log"
elif ! cmp -s "$WORK/rerun.env" "$WORK/other-ns/env" || [[ "$(cat "$WORK/rerun.env.before-restore")" != OPENTSX_NS=current ]]; then
  failed "rerun: .env is not the backup's, or the kept copy is not the previous .env"; sed 's/^/    /' "$WORK/rerun-second.out"
else
  echo "ok   rerun"
fi

STUB_ENV=(CLUSTER=up OPENTSX_ENV_FILE="$WORK/rerun.env")
restore rerun-third "$WORK/other-ns" --only env,data --yes
STUB_ENV=()
message="  ok    $WORK/rerun.env: already the backup's env; $WORK/rerun.env.before-restore left as it is"
if (( rc != 0 )) || ! grep -qxF -- "$message" "$WORK/rerun-third.out"; then
  failed "already-kept: exit $rc, expected 0 and the message: $message"; sed 's/^/    /' "$WORK/rerun-third.out"
elif [[ "$(cat "$WORK/rerun.env.before-restore")" != OPENTSX_NS=current ]]; then
  failed "already-kept: the kept copy of the previous .env was overwritten"; sed 's/^/    /' "$WORK/rerun.env.before-restore"
else
  echo "ok   already-kept"
fi

cp "$WORK/env-data/env" "$WORK/same.env"
STUB_ENV=(OPENTSX_ENV_FILE="$WORK/same.env")
restore already-no-copy "$WORK/env-data" --only env --yes
STUB_ENV=()
message="  ok    $WORK/same.env: already the backup's env; left as it is"
if (( rc != 0 )) || ! grep -qxF -- "$message" "$WORK/already-no-copy.out"; then
  failed "already-no-copy: exit $rc, expected 0 and the message: $message"; sed 's/^/    /' "$WORK/already-no-copy.out"
elif [[ -e "$WORK/same.env.before-restore" || -L "$WORK/same.env.before-restore" ]]; then
  failed "already-no-copy: a kept copy was created"
else
  echo "ok   already-no-copy"
fi

# a backup of another namespace: with env and volumes refused before the question and before anything changes,
# volumes alone restore into the namespace of the current .env
mkdir -p "$WORK/elsewhere"
printf 'OPENTSX_NS=elsewhere\n' > "$WORK/elsewhere/env"
cp "$WORK/good/data.tar.gz" "$WORK/elsewhere/"
{ echo "namespace=elsewhere"
  (cd "$WORK/elsewhere" && for f in env data.tar.gz; do echo "file=$(sha256sum "$f" | cut -d' ' -f1) $(wc -c < "$f") $f"; done)
} > "$WORK/elsewhere/MANIFEST"
# ns.env sets no OPENTSX_NS, so the namespace comes from env.example
message="error: namespace: the backup is of elsewhere, deploy/k3s/env.example names opentsx; restore env alone first or set OPENTSX_NS to elsewhere"
for name in other-namespace-yes other-namespace-asked; do
  args=(--only "env,data")
  [[ "$name" == *-yes ]] && args+=(--yes)
  printf 'OPENTSX_DOMAIN=original\n' > "$WORK/ns.env"
  STUB_ENV=(CLUSTER=up OPENTSX_ENV_FILE="$WORK/ns.env")
  restore "$name" "$WORK/elsewhere" "${args[@]}"
  STUB_ENV=()
  if (( rc != 1 )) || ! grep -qxF -- "$message" "$WORK/$name.out" || grep -qF "Continue?" "$WORK/$name.out" \
    || grep -qF "no answer" "$WORK/$name.out"; then
    failed "$name: exit $rc, expected 1 and the message before the question: $message"; sed 's/^/    /' "$WORK/$name.out"
  elif [[ "$(cat "$WORK/ns.env")" != OPENTSX_DOMAIN=original || -e "$WORK/ns.env.before-restore" ]]; then
    failed "$name: .env was replaced or kept aside"; sed 's/^/    /' "$WORK/ns.env"
  elif [[ -s "$WORK/calls.log" ]]; then
    failed "$name: kubectl was called"; sed 's/^/    call: /' "$WORK/calls.log"
  else
    echo "ok   $name"
  fi
done
# the refusal names the file OPENTSX_NS came from: the env file that sets it, or env.example without an env file
printf 'OPENTSX_NS=current\n' > "$WORK/ns-current.env"
for case in "other-namespace-env-file|$WORK/ns-current.env|$WORK/ns-current.env names current" \
  "other-namespace-no-env-file|$WORK/none.env|deploy/k3s/env.example names opentsx"; do
  IFS='|' read -r name file names <<< "$case"
  STUB_ENV=(CLUSTER=up OPENTSX_ENV_FILE="$file")
  restore "$name" "$WORK/elsewhere" --only env,data --yes
  STUB_ENV=()
  message="error: namespace: the backup is of elsewhere, $names; restore env alone first or set OPENTSX_NS to elsewhere"
  if (( rc != 1 )) || ! grep -qxF -- "$message" "$WORK/$name.out"; then
    failed "$name: exit $rc, expected 1 and the message: $message"; sed 's/^/    /' "$WORK/$name.out"
  elif [[ -e "$file.before-restore" || -s "$WORK/calls.log" ]] || { [[ -e "$file" ]] && [[ "$(cat "$file")" != OPENTSX_NS=current ]]; }; then
    failed "$name: .env was replaced or kept aside, or kubectl was called"
  elif [[ "$file" == "$WORK/none.env" && -e "$file" ]]; then
    failed "$name: an env file was written"
  else
    echo "ok   $name"
  fi
done

STUB_ENV=(CLUSTER=up OPENTSX_ENV_FILE="$WORK/ns.env")
restore other-namespace-volumes "$WORK/elsewhere" --only data --yes
STUB_ENV=()
if (( rc != 0 )) || ! grep -qF "data.tar.gz -> PVC opentsx-data" "$WORK/other-namespace-volumes.out" \
  || ! grep -qxF "get namespace opentsx --ignore-not-found -o name" "$WORK/calls.log"; then
  failed "other-namespace-volumes: exit $rc, expected 0 and data.tar.gz restored into the namespace opentsx"
  sed 's/^/    /' "$WORK/other-namespace-volumes.out"
else
  echo "ok   other-namespace-volumes"
fi

mkdir -p "$WORK/no-namespace"
cp "$WORK/env-data/env" "$WORK/env-data/data.tar.gz" "$WORK/no-namespace/"
grep '^file=' "$WORK/env-data/MANIFEST" > "$WORK/no-namespace/MANIFEST"
printf 'OPENTSX_DOMAIN=original\n' > "$WORK/ns.env"
STUB_ENV=(CLUSTER=up OPENTSX_ENV_FILE="$WORK/ns.env")
restore manifest-without-namespace "$WORK/no-namespace" --only env,data --yes
STUB_ENV=()
message="error: namespace: missing in $WORK/no-namespace/MANIFEST; restore env alone first, then the volumes"
if (( rc != 1 )) || ! grep -qxF -- "$message" "$WORK/manifest-without-namespace.out"; then
  failed "manifest-without-namespace: exit $rc, expected 1 and the message: $message"
  sed 's/^/    /' "$WORK/manifest-without-namespace.out"
elif [[ "$(cat "$WORK/ns.env")" != OPENTSX_DOMAIN=original || -e "$WORK/ns.env.before-restore" || -s "$WORK/calls.log" ]]; then
  failed "manifest-without-namespace: .env was replaced or kept aside, or kubectl was called"
else
  echo "ok   manifest-without-namespace"
fi

# a backup whose MANIFEST names the current namespace and whose env names another one
mkdir -p "$WORK/env-elsewhere"
printf 'OPENTSX_NS=frombackup\n' > "$WORK/env-elsewhere/env"
cp "$WORK/good/data.tar.gz" "$WORK/env-elsewhere/"
{ echo "namespace=current"
  (cd "$WORK/env-elsewhere" && for f in env data.tar.gz; do echo "file=$(sha256sum "$f" | cut -d' ' -f1) $(wc -c < "$f") $f"; done)
} > "$WORK/env-elsewhere/MANIFEST"
printf 'OPENTSX_NS=current\n' > "$WORK/ns.env"
STUB_ENV=(CLUSTER=up OPENTSX_ENV_FILE="$WORK/ns.env")
restore env-other-namespace "$WORK/env-elsewhere" --only env,data --yes
STUB_ENV=()
message="error: namespace: the backup is of current, $WORK/env-elsewhere/env names frombackup; restore env alone first, then the volumes"
if (( rc != 1 )) || ! grep -qxF -- "$message" "$WORK/env-other-namespace.out"; then
  failed "env-other-namespace: exit $rc, expected 1 and the message: $message"
  sed 's/^/    /' "$WORK/env-other-namespace.out"
elif [[ "$(cat "$WORK/ns.env")" != OPENTSX_NS=current || -e "$WORK/ns.env.before-restore" || -s "$WORK/calls.log" ]]; then
  failed "env-other-namespace: .env was replaced or kept aside, or kubectl was called"
else
  echo "ok   env-other-namespace"
fi

# OPENTSX_NS set in the shell wins over .env, so the refusal names the shell, not a .env that names the backup's namespace
printf 'OPENTSX_NS=elsewhere\n' > "$WORK/ns.env"
STUB_ENV=(CLUSTER=up OPENTSX_ENV_FILE="$WORK/ns.env" OPENTSX_NS=fromshell)
restore other-namespace-shell "$WORK/elsewhere" --only env,data --yes
STUB_ENV=()
message="error: namespace: the backup is of elsewhere, the shell sets OPENTSX_NS to fromshell; set OPENTSX_NS to elsewhere or unset it"
if (( rc != 1 )) || ! grep -qxF -- "$message" "$WORK/other-namespace-shell.out"; then
  failed "other-namespace-shell: exit $rc, expected 1 and the message: $message"
  sed 's/^/    /' "$WORK/other-namespace-shell.out"
elif [[ "$(cat "$WORK/ns.env")" != OPENTSX_NS=elsewhere || -e "$WORK/ns.env.before-restore" || -s "$WORK/calls.log" ]]; then
  failed "other-namespace-shell: .env was replaced or kept aside, or kubectl was called"
else
  echo "ok   other-namespace-shell"
fi

# a .env below a regular file cannot be written, also by root
printf 'not a directory\n' > "$WORK/blocked"
for case in "env,data|after the PVCs opentsx-data were restored" "env|"; do
  parts="${case%%|*}"; name="env-not-replaced-${parts//,/-}"; after="${case#*|}"; after="${after:+ $after}"
  STUB_ENV=(CLUSTER=up OPENTSX_ENV_FILE="$WORK/blocked/current.env")
  restore "$name" "$WORK/env-data" --only "$parts" --yes
  STUB_ENV=()
  message="error: $WORK/blocked/current.env: cannot replace it with $WORK/env-data/env$after; see the error above, then run 71-restore.sh $WORK/env-data --only env"
  if (( rc != 1 )) || ! grep -qxF -- "$message" "$WORK/$name.out"; then
    failed "$name: exit $rc, expected 1 and the message: $message"; sed 's/^/    /' "$WORK/$name.out"
  else
    echo "ok   $name"
  fi
done

# cp with the source CP_FAILS_ON cuts its destination to nothing and fails, as on a full disk; any other copy is real
cat > "$WORK/bin/cp" <<STUB
#!/usr/bin/env bash
if [[ -n "\${CP_FAILS_ON:-}" && "\${*: -2:1}" == "\$CP_FAILS_ON" ]]; then
  : > "\${*: -1}"; echo "cp: error writing '\${*: -1}': No space left on device" >&2; exit 1
fi
exec "$(command -v cp)" "\$@"
STUB
chmod +x "$WORK/bin/cp"
# a copy of the backup's env that fails halfway leaves .env as it was, so the rerun the message names restores env
printf 'OPENTSX_DOMAIN=original\n' > "$WORK/partial.env"
STUB_ENV=(CP_FAILS_ON="$WORK/env-data/env" OPENTSX_ENV_FILE="$WORK/partial.env")
restore env-copy-fails "$WORK/env-data" --only env --yes
first=$rc
first_env="$(cat "$WORK/partial.env")"
left="$(cd "$WORK" && echo partial.env.*)"
STUB_ENV=(OPENTSX_ENV_FILE="$WORK/partial.env")
restore env-copy-rerun "$WORK/env-data" --only env --yes
STUB_ENV=()
message="error: $WORK/partial.env: cannot replace it with $WORK/env-data/env; see the error above, then run 71-restore.sh $WORK/env-data --only env"
if (( first != 1 )) || ! grep -qxF -- "$message" "$WORK/env-copy-fails.out"; then
  failed "env-copy-fails: exit $first, expected 1 and the message: $message"; sed 's/^/    /' "$WORK/env-copy-fails.out"
elif [[ "$first_env" != OPENTSX_DOMAIN=original || "$left" != partial.env.before-restore ]]; then
  failed "env-copy-fails: .env is [$first_env], expected OPENTSX_DOMAIN=original; files left next to it: $left"
elif (( rc != 0 )) || ! cmp -s "$WORK/partial.env" "$WORK/env-data/env" \
  || [[ "$(cat "$WORK/partial.env.before-restore")" != OPENTSX_DOMAIN=original ]]; then
  failed "env-copy-fails: the rerun exit $rc, expected 0, the backup's env in .env and the original in the kept copy"
  sed 's/^/    /' "$WORK/env-copy-rerun.out"
elif [[ "$(stat -c %a "$WORK/partial.env")" != 600 ]]; then
  failed "env-copy-fails: the restored .env has mode $(stat -c %a "$WORK/partial.env"), expected 600"
else
  echo "ok   env-copy-fails"
fi

# a .env that is a link stays a link, and the file it points to holds the backup's env
printf 'OPENTSX_DOMAIN=original\n' > "$WORK/link-target.env"
ln -s "$WORK/link-target.env" "$WORK/link.env"
STUB_ENV=(OPENTSX_ENV_FILE="$WORK/link.env")
restore env-link "$WORK/env-data" --only env --yes
STUB_ENV=()
if (( rc != 0 )) || [[ ! -L "$WORK/link.env" ]] || ! cmp -s "$WORK/link-target.env" "$WORK/env-data/env"; then
  failed "env-link: exit $rc, expected 0, .env still a link and the backup's env in the file it points to"
  sed 's/^/    /' "$WORK/env-link.out"
elif [[ "$(cat "$WORK/link.env.before-restore")" != OPENTSX_DOMAIN=original ]]; then
  failed "env-link: the kept copy is not the original"
else
  echo "ok   env-link"
fi

# a .env that links to a path with a missing directory, or with a file where a directory belongs, stays as it is, and
# the message names the link and why it cannot be resolved
ln -s "$WORK/missing-dir/target.env" "$WORK/dangling.env"
STUB_ENV=(OPENTSX_ENV_FILE="$WORK/dangling.env")
restore env-dangling-link "$WORK/env-data" --only env --yes
STUB_ENV=()
message="error: $WORK/dangling.env: cannot replace it with $WORK/env-data/env; it links to $WORK/missing-dir/target.env, which cannot be resolved (a directory on its path is missing or is a file, or the links form a loop); fix the link, then run 71-restore.sh $WORK/env-data --only env"
if (( rc != 1 )) || ! grep -qxF -- "$message" "$WORK/env-dangling-link.out"; then
  failed "env-dangling-link: exit $rc, expected 1 and the message: $message"; sed 's/^/    /' "$WORK/env-dangling-link.out"
elif [[ "$(readlink -- "$WORK/dangling.env")" != "$WORK/missing-dir/target.env" || -e "$WORK/missing-dir" ]]; then
  failed "env-dangling-link: the link or its missing directory changed"
else
  echo "ok   env-dangling-link"
fi

printf 'OPENTSX_DOMAIN=original\n' > "$WORK/a-file"
ln -s "$WORK/a-file/target.env" "$WORK/through-file.env"
STUB_ENV=(OPENTSX_ENV_FILE="$WORK/through-file.env")
restore env-link-through-file "$WORK/env-data" --only env --yes
STUB_ENV=()
message="error: $WORK/through-file.env: cannot replace it with $WORK/env-data/env; it links to $WORK/a-file/target.env, which cannot be resolved (a directory on its path is missing or is a file, or the links form a loop); fix the link, then run 71-restore.sh $WORK/env-data --only env"
if (( rc != 1 )) || ! grep -qxF -- "$message" "$WORK/env-link-through-file.out"; then
  failed "env-link-through-file: exit $rc, expected 1 and the message: $message"; sed 's/^/    /' "$WORK/env-link-through-file.out"
elif [[ "$(readlink -- "$WORK/through-file.env")" != "$WORK/a-file/target.env" \
        || "$(cat "$WORK/a-file")" != OPENTSX_DOMAIN=original ]]; then
  failed "env-link-through-file: the link or the file on its path changed"
else
  echo "ok   env-link-through-file"
fi

# the kept copy of .env and the copy renamed over it hold its secrets
ignored="$(git -C "$K3S_DIR" check-ignore -- .env.before-restore .env.restoring 2>&1)" || true
if [[ "$ignored" == $'.env.before-restore\n.env.restoring' ]]; then
  echo "ok   env-copies-ignored"
else
  failed "env-copies-ignored: git check-ignore printed [$ignored], expected both .env.before-restore and .env.restoring"
fi

if (( failures )); then echo "$failures failed"; exit 1; fi
echo "all passed"
