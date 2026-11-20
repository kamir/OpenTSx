#!/usr/bin/env bash
# Tests the arguments of deploy/k3s/71-restore.sh: an unknown option, --only without its value or with an unknown
# part, a second directory and no directory exit 2 before kubectl runs, and --help lists the exit codes. A stand-in
# kubectl records any call; needs no kubectl, no cluster, no network.
#
#   bash deploy/k3s/test/test-71-restore.sh
set -euo pipefail
K3S_DIR="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
failures=0
failed() { echo "FAIL $1"; failures=$((failures + 1)); }

mkdir -p "$WORK/bin" "$WORK/backup"
# kubectl records its call; none is expected to run
cat > "$WORK/bin/kubectl" <<'STUB'
#!/usr/bin/env bash
printf '%s\n' "$*" >> "$WORK/calls.log"
exit 1
STUB
chmod +x "$WORK/bin/kubectl"
printf 'created=2026-01-01T00:00:00Z\nnamespace=opentsx\n' > "$WORK/backup/MANIFEST"

restore() {   # NAME ARG ...: output in NAME.out, exit code in rc
  local name="$1"; shift
  rc=0
  rm -f "$WORK/calls.log"
  env -i PATH="$WORK/bin:$PATH" HOME="$WORK" WORK="$WORK" KUBECTL="$WORK/bin/kubectl" OPENTSX_ENV_FILE="$WORK/none.env" \
    bash "$K3S_DIR/71-restore.sh" "$@" > "$WORK/$name.out" 2>&1 || rc=$?
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

if (( failures )); then echo "$failures failed"; exit 1; fi
echo "all passed"
