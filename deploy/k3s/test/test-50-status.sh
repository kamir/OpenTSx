#!/usr/bin/env bash
# Tests the arguments of deploy/k3s/50-status.sh: an unknown option and an argument exit 2 before kubectl runs,
# --help lists the exit codes, a namespace that is not there exits 1 with its message, and a cluster that cannot be
# reached exits 1 with kubectl's error and a message that does not say the namespace is missing; against a cluster
# that answers, no OpenTSx image in k3s containerd, a k3s containerd that cannot list its images (with the error of
# k3s ctr), an old statistics checkpoint (also when kubectl writes to stderr while it reads the age), a checkpoint and
# a checkpoint age that cannot be read (with the error of kubectl exec) and an age that is not a number give their warn
# lines and exit 0; a Deployment that is not there is not deployed, and one that cannot be read exits 1 with kubectl's
# error and its message; --logs shows the logs of every OpenTSx Deployment; Jobs, CronJobs, events or the Deployments
# for --logs that cannot be read exit 1 with kubectl's error and their message; logs of one Deployment that cannot be
# read give a warn line, the logs of the others and exit 1.
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
# the namespace, the Deployments opentsx-stats and opentsx-retrieval with one log line each, the checkpoint of
# opentsx-stats CKPT_AGE seconds old (default 10), and no other Deployment; CKPT=missing: the checkpoint cannot be
# listed; CKPT_AGE=fails: its age cannot be read; STDERR=throttled: kubectl also writes its client-side throttling
# line to stderr when it reads the age; DEPLOY=fails: a Deployment read is refused as by an unreachable API server;
# REFUSE=<call>: the calls that start with <call> are refused as by a missing permission.
cat > "$WORK/bin/kubectl" <<'STUB'
#!/usr/bin/env bash
printf '%s\n' "$*" >> "$WORK/calls.log"
if [[ "${NAMESPACE:-}" == missing && "$1 $2" == "get namespace" ]]; then
  [[ "$*" == *--ignore-not-found* ]] && exit 0
  echo "Error from server (NotFound): namespaces \"$3\" not found" >&2; exit 1
fi
if [[ "${CLUSTER:-}" == up ]]; then
  [[ "$1" == -n ]] && shift 2
  if [[ -n "${REFUSE:-}" && "$*" == "$REFUSE"* ]]; then
    echo "Error from server (Forbidden): $*: forbidden" >&2; exit 1
  fi
  case "$*" in
    "get namespace "*) echo "namespace/$3" ;;
    "exec deployment/opentsx-stats -- ls "*)
      [[ "${CKPT:-}" != missing ]] || { echo "ls: cannot access '/state': No such file or directory" >&2; exit 2; }
      echo "-rw-r--r-- 1 1000 1000 4096 2026-01-01T00:00:00 stats.ckpt" ;;
    "exec deployment/opentsx-stats -- sh -c "*)
      [[ "${CKPT_AGE:-}" != fails ]] || { echo "stat: cannot statx '/state/stats.ckpt': No such file or directory" >&2; exit 2; }
      [[ "${STDERR:-}" != throttled ]] \
        || echo "I1120 10:00:00.000000 1 request.go:697] Waited for 1.04s due to client-side throttling" >&2
      echo "${CKPT_AGE:-10}" ;;
    "get deployment/"*)
      [[ "${DEPLOY:-}" != fails ]] || { echo "The connection to the server 10.0.0.1:6443 was refused" >&2; exit 1; }
      [[ "$*" == *--ignore-not-found* ]] && exit 0
      echo "Error from server (NotFound): deployments.apps \"${2#deployment/}\" not found" >&2; exit 1 ;;
    "get deployment -l app.kubernetes.io/part-of=opentsx -o name")
      printf '%s\n' deployment.apps/opentsx-stats deployment.apps/opentsx-retrieval ;;
    "logs "*) echo "log line of $2" ;;
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
  || ! grep -qxF "  in k3s containerd: docker.io/opentsx/tools:3.0.1-local" "$WORK/up.out" \
  || [[ "$(grep -cxF "  not deployed" "$WORK/up.out")" != 2 ]]; then
  failed "up: exit $rc, expected 0, the image, the checkpoint age, the Modbus simulator and event bridge not deployed and no warn line"
  sed 's/^/    /' "$WORK/up.out"
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
message="  warn  statistics checkpoint: cannot read it (ls: cannot access '/state': No such file or directory); see $WORK/bin/kubectl -n opentsx logs deployment/opentsx-stats"
if (( rc != 0 )) || ! grep -qxF -- "$message" "$WORK/no-checkpoint.out"; then
  failed "no-checkpoint: exit $rc, expected 0 and the message: $message"; sed 's/^/    /' "$WORK/no-checkpoint.out"
else
  echo "ok   no-checkpoint"
fi

STUB_ENV=(CLUSTER=up CKPT_AGE=fails)
status no-checkpoint-age
STUB_ENV=()
message="  warn  statistics checkpoint: cannot read its age (stat: cannot statx '/state/stats.ckpt': No such file or directory); see $WORK/bin/kubectl -n opentsx logs deployment/opentsx-stats"
if (( rc != 0 )) || ! grep -qxF -- "$message" "$WORK/no-checkpoint-age.out"; then
  failed "no-checkpoint-age: exit $rc, expected 0 and the message: $message"; sed 's/^/    /' "$WORK/no-checkpoint-age.out"
else
  echo "ok   no-checkpoint-age"
fi

STUB_ENV=(CLUSTER=up CKPT_AGE=900 STDERR=throttled)
status old-checkpoint-stderr
STUB_ENV=()
message="  warn  statistics checkpoint: 900 s old; the processor may be stuck, see docs/devops/operations.md R2"
if (( rc != 0 )) || ! grep -qxF -- "$message" "$WORK/old-checkpoint-stderr.out"; then
  failed "old-checkpoint-stderr: exit $rc, expected 0 and the message: $message"
  sed 's/^/    /' "$WORK/old-checkpoint-stderr.out"
else
  echo "ok   old-checkpoint-stderr"
fi

STUB_ENV=(CLUSTER=up CKPT_AGE=abc)
status checkpoint-age-not-a-number
STUB_ENV=()
message="  warn  statistics checkpoint: cannot read its age (\"abc\" is not a number of seconds); see $WORK/bin/kubectl -n opentsx logs deployment/opentsx-stats"
if (( rc != 0 )) || ! grep -qxF -- "$message" "$WORK/checkpoint-age-not-a-number.out"; then
  failed "checkpoint-age-not-a-number: exit $rc, expected 0 and the message: $message"
  sed 's/^/    /' "$WORK/checkpoint-age-not-a-number.out"
else
  echo "ok   checkpoint-age-not-a-number"
fi

STUB_ENV=(CLUSTER=up DEPLOY=fails)
status deployment-unreadable
STUB_ENV=()
message="error: deployment/opentsx-modbus-sim: cannot read it; check the cluster with $WORK/bin/kubectl -n opentsx get deployment/opentsx-modbus-sim"
if (( rc != 1 )) || ! grep -qxF -- "$message" "$WORK/deployment-unreadable.out"; then
  failed "deployment-unreadable: exit $rc, expected 1 and the message: $message"; sed 's/^/    /' "$WORK/deployment-unreadable.out"
elif ! grep -qxF "The connection to the server 10.0.0.1:6443 was refused" "$WORK/deployment-unreadable.out" \
  || grep -qxF "  not deployed" "$WORK/deployment-unreadable.out"; then
  failed "deployment-unreadable: the error of kubectl is not shown, or a failed read is reported as not deployed"
  sed 's/^/    /' "$WORK/deployment-unreadable.out"
else
  echo "ok   deployment-unreadable"
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

STUB_ENV=(CLUSTER=up)
status logs --logs
STUB_ENV=()
if (( rc != 0 )) || ! grep -qxF "log line of deployment.apps/opentsx-stats" "$WORK/logs.out" \
  || ! grep -qxF "log line of deployment.apps/opentsx-retrieval" "$WORK/logs.out"; then
  failed "logs: exit $rc, expected 0 and the log lines of opentsx-stats and opentsx-retrieval"; sed 's/^/    /' "$WORK/logs.out"
else
  echo "ok   logs"
fi

# refused NAME MESSAGE CALL ARG ...: against the cluster that answers, the reads that start with CALL are refused;
# exit 1 with the error of kubectl and MESSAGE
refused() {
  local name="$1" message="$2" call="$3"; shift 3
  STUB_ENV=(CLUSTER=up REFUSE="$call")
  status "$name" "$@"
  STUB_ENV=()
  if (( rc != 1 )) || ! grep -qxF -- "$message" "$WORK/$name.out"; then
    failed "$name: exit $rc, expected 1 and the message: $message"; sed 's/^/    /' "$WORK/$name.out"
  elif ! grep -qF "Error from server (Forbidden): $call" "$WORK/$name.out"; then
    failed "$name: the error of kubectl is not shown"; sed 's/^/    /' "$WORK/$name.out"
  else
    echo "ok   $name"
  fi
}
refused jobs-refused "error: jobs: cannot read them; check the cluster with $WORK/bin/kubectl -n opentsx get job" "get job "
refused cronjobs-refused "error: cronjobs: cannot read them; check the cluster with $WORK/bin/kubectl -n opentsx get cronjob" \
  "get cronjob "
refused events-refused "error: events: cannot read them; check the cluster with $WORK/bin/kubectl -n opentsx get events" \
  "get events "
refused logs-list-refused "error: --logs: cannot list the Deployments; check the cluster with $WORK/bin/kubectl -n opentsx get deployment -l app.kubernetes.io/part-of=opentsx" \
  "get deployment -l " --logs

STUB_ENV=(CLUSTER=up REFUSE="logs deployment.apps/opentsx-stats")
status logs-refused --logs
STUB_ENV=()
before=$failures
if (( rc != 1 )); then failed "logs-refused: exit $rc, expected 1"; fi
for message in "Error from server (Forbidden): logs deployment.apps/opentsx-stats --tail=20: forbidden" \
  "  warn  deployment.apps/opentsx-stats: cannot read its logs; check them with $WORK/bin/kubectl -n opentsx logs deployment.apps/opentsx-stats" \
  "log line of deployment.apps/opentsx-retrieval" \
  "error: --logs: the logs of 1 Deployment(s) cannot be read; see the warn lines above"; do
  grep -qxF -- "$message" "$WORK/logs-refused.out" || failed "logs-refused: expected the line: $message"
done
if (( failures > before )); then sed 's/^/    /' "$WORK/logs-refused.out"; else echo "ok   logs-refused"; fi

if (( failures )); then echo "$failures failed"; exit 1; fi
echo "all passed"
