#!/usr/bin/env bash
# Tests the arguments of deploy/k3s/71-restore.sh: an unknown option, --only without its value or with an unknown
# part, a second directory and no directory exit 2 before kubectl runs, and --help lists the exit codes; a directory
# without a MANIFEST, an archive that is missing, damaged or not gzip, a missing env and nothing to restore exit 1
# before kubectl runs; a declined question exits 1 with its message on stderr before kubectl runs; a missing namespace,
# a namespace that cannot be read, a missing PVC and a failed step in the helper pod exit 1 with their message; a
# MANIFEST of file= lines only restores; a current .env that cannot be kept exits 1 and stays as it is. A stand-in kubectl records any call; needs no kubectl, no cluster, no network.
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
# PVC; EXEC=empty-fails or unpack-fails: that step in the helper pod fails.
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
  "get pvc "*) [[ "${PVC_STATE:-}" != missing ]] || { echo "Error from server (NotFound): persistentvolumeclaims \"$3\" not found" >&2; exit 1; } ;;
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
message="error: 71-restore.sh: aborted at the question; nothing was changed"
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

if (( failures )); then echo "$failures failed"; exit 1; fi
echo "all passed"
