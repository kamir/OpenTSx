#!/usr/bin/env bash
# Tests the arguments of deploy/k3s/50-status.sh: an unknown option and an argument exit 2 before kubectl runs,
# --help lists the exit codes, a namespace that is not there exits 1 with its message, and a cluster that cannot be
# reached exits 1 with kubectl's error and a message that does not say the namespace is missing. A stand-in kubectl
# records any call and fails; needs no kubectl, no cluster, no network.
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
# does not exist, with --ignore-not-found as kubectl does: no output and exit 0
cat > "$WORK/bin/kubectl" <<'STUB'
#!/usr/bin/env bash
printf '%s\n' "$*" >> "$WORK/calls.log"
if [[ "${NAMESPACE:-}" == missing && "$1 $2" == "get namespace" ]]; then
  [[ "$*" == *--ignore-not-found* ]] && exit 0
  echo "Error from server (NotFound): namespaces \"$3\" not found" >&2; exit 1
fi
echo "The connection to the server 10.0.0.1:6443 was refused" >&2
exit 1
STUB
chmod +x "$WORK/bin/kubectl"

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

if (( failures )); then echo "$failures failed"; exit 1; fi
echo "all passed"
