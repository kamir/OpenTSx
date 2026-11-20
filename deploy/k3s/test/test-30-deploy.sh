#!/usr/bin/env bash
# Tests deploy/k3s/30-deploy.sh: it waits until every Deployment is ready and names each one that is not with exit 1,
# a failed listing of the Deployments is no success, the setup Jobs are deleted in an existing namespace only, a
# namespace that cannot be read stops it with exit 1 before the apply, and usage errors exit 2 before kubectl runs. A
# stand-in kubectl plays the cluster; needs no kubectl, no cluster, no network.
#
#   bash deploy/k3s/test/test-30-deploy.sh
set -euo pipefail
K3S_DIR="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
failures=0
failed() { echo "FAIL $1"; failures=$((failures + 1)); }

mkdir -p "$WORK/bin" "$WORK/overlay"
touch "$WORK/overlay/kustomization.yaml"
# kubectl: the namespace exists, opentsx-retrieval and opentsx-stats are deployed. NOT_READY=<name>: its rollout status
# times out as kubectl does when the pods do not get ready. LIST=fails: listing the Deployments fails.
# NAMESPACE=missing: no namespace, with --ignore-not-found as kubectl does: no output and exit 0; NAMESPACE=unreadable:
# the nodes answer, then reading the namespace fails as an API server that is no longer reachable does.
cat > "$WORK/bin/kubectl" <<'EOF'
#!/usr/bin/env bash
printf '%s\n' "$*" >> "$WORK/calls.log"
[[ "$1" == -n ]] && shift 2
case "$*" in
  "get namespace "*)
    case "${NAMESPACE:-}" in
      missing) [[ "$*" == *--ignore-not-found* ]] && exit 0
        echo "Error from server (NotFound): namespaces \"$3\" not found" >&2; exit 1 ;;
      unreadable) echo "The connection to the server 10.0.0.1:6443 was refused" >&2; exit 1 ;;
    esac
    echo "namespace/$3" ;;
  "get nodes"|"delete job "*|"apply -k "*) exit 0 ;;
  "get deployment -l "*)
    [[ "${LIST:-}" == fails ]] && { echo "The connection to the server 10.0.0.1:6443 was refused" >&2; exit 1; }
    printf 'deployment.apps/opentsx-retrieval\ndeployment.apps/opentsx-stats\n' ;;
  "rollout status "*)
    if [[ "$3" == "deployment.apps/${NOT_READY:-none}" ]]; then
      echo "error: timed out waiting for the condition" >&2; exit 1
    fi
    echo "deployment \"${3#deployment.apps/}\" successfully rolled out" ;;
  *) echo "kubectl stand-in: unexpected call: $*" >&2; exit 1 ;;
esac
EOF
chmod +x "$WORK/bin/kubectl"

deploy() {   # NAME [KEY=VALUE ...] -- ARG ...: output in NAME.out, exit code in rc
  local name="$1"; shift
  local -a envs=()
  while (( $# )) && [[ "$1" != -- ]]; do envs+=("$1"); shift; done
  if (( $# )); then shift; fi
  rc=0
  rm -f "$WORK/calls.log"
  env -i PATH="$WORK/bin:$PATH" HOME="$WORK" WORK="$WORK" KUBECTL="$WORK/bin/kubectl" \
    OPENTSX_ENV_FILE="$WORK/none.env" OPENTSX_OVERLAY="$WORK/overlay" "${envs[@]}" \
    bash "$K3S_DIR/30-deploy.sh" "$@" > "$WORK/$name.out" 2>&1 || rc=$?
}

deploy ready -- --timeout 90s
before=$failures
if (( rc != 0 )); then failed "ready: exit $rc, expected 0"; fi
for d in opentsx-retrieval opentsx-stats; do
  grep -qxF -- "-n opentsx rollout status deployment.apps/$d --timeout=90s" "$WORK/calls.log" \
    || failed "ready: no rollout status with the timeout for $d"
  grep -qF "ok    deployment.apps/$d ready" "$WORK/ready.out" || failed "ready: no ok line for $d"
done
grep -qxF -- "-n opentsx delete job -l opentsx.org/setup=true --ignore-not-found" "$WORK/calls.log" \
  || failed "ready: the setup Jobs of the existing namespace were not deleted"
if (( failures > before )); then sed 's/^/    /' "$WORK/ready.out"; else echo "ok   ready"; fi

deploy no-namespace NAMESPACE=missing --
if (( rc != 0 )) || grep -qF "delete job" "$WORK/calls.log" || ! grep -qxF "apply -k $WORK/overlay" "$WORK/calls.log"; then
  failed "no-namespace: exit $rc, expected 0, no deletion of setup Jobs and the apply"
  sed 's/^/    /' "$WORK/no-namespace.out"; sed 's/^/    call: /' "$WORK/calls.log"
else
  echo "ok   no-namespace"
fi

deploy namespace-unreadable NAMESPACE=unreadable --
message="error: namespace opentsx: cannot read it; check the cluster with $WORK/bin/kubectl get namespace opentsx"
if (( rc != 1 )) || ! grep -qxF -- "$message" "$WORK/namespace-unreadable.out"; then
  failed "namespace-unreadable: exit $rc, expected 1 and the message: $message"; sed 's/^/    /' "$WORK/namespace-unreadable.out"
elif ! grep -qxF "The connection to the server 10.0.0.1:6443 was refused" "$WORK/namespace-unreadable.out"; then
  failed "namespace-unreadable: the error of kubectl is not shown"; sed 's/^/    /' "$WORK/namespace-unreadable.out"
elif grep -qF "apply -k" "$WORK/calls.log"; then
  failed "namespace-unreadable: the overlay was applied"; sed 's/^/    call: /' "$WORK/calls.log"
else
  echo "ok   namespace-unreadable"
fi

deploy not-ready NOT_READY=opentsx-stats --
before=$failures
if (( rc != 1 )); then failed "not-ready: exit $rc, expected 1"; fi
grep -qF "FAIL  deployment.apps/opentsx-stats: not ready after 180s; see $WORK/bin/kubectl -n opentsx describe deployment.apps/opentsx-stats" \
  "$WORK/not-ready.out" || failed "not-ready: no FAIL line naming opentsx-stats"
grep -qF "ok    deployment.apps/opentsx-retrieval ready" "$WORK/not-ready.out" || failed "not-ready: no ok line for retrieval"
grep -qF "40-demo.sh kafscale-check creates them, then 30-deploy.sh --restart" "$WORK/not-ready.out" \
  || failed "not-ready: no hint how a new broker gets the topics of opentsx-stats"
if grep -qF "deployed" "$WORK/not-ready.out"; then failed "not-ready: says deployed"; fi
if (( failures > before )); then sed 's/^/    /' "$WORK/not-ready.out"; else echo "ok   not-ready"; fi

deploy list-fails LIST=fails --
before=$failures
if (( rc != 1 )); then failed "list-fails: exit $rc, expected 1"; fi
grep -qF "error: cannot list the Deployments of namespace opentsx; check the cluster with $WORK/bin/kubectl -n opentsx get deployment" \
  "$WORK/list-fails.out" || failed "list-fails: no error line"
if grep -qF "deployed" "$WORK/list-fails.out"; then failed "list-fails: says deployed"; fi
if (( failures > before )); then sed 's/^/    /' "$WORK/list-fails.out"; else echo "ok   list-fails"; fi

expect() {   # NAME MESSAGE ARG ...: a usage error, exit 2, before kubectl runs
  local name="$1" message="$2"; shift 2
  deploy "$name" -- "$@"
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
expect unknown-option "error: --nosuch: unknown option; expected --restart, --dry-run, --timeout DURATION or --help" --nosuch
expect extra-argument "error: now: unexpected argument; 30-deploy.sh takes options only" now
expect timeout-without-value "error: --timeout: missing value; expected a duration such as 180s or 5m" --timeout
expect timeout-not-a-duration "error: --timeout: \"3 minutes\" is not a duration; expected a whole number with the unit s, m or h such as 180s or 5m" --timeout "3 minutes"

deploy help -- --help
if (( rc != 0 )) || ! grep -qF "  2  usage error" "$WORK/help.out"; then
  failed "help: exit $rc, expected 0 and the exit code 2 in the usage"; sed 's/^/    /' "$WORK/help.out"
else
  echo "ok   help"
fi

if (( failures )); then echo "$failures failed"; exit 1; fi
echo "all passed"
