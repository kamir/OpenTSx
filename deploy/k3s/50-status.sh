#!/usr/bin/env bash
# Shows the state of the OpenTSx installation: workloads, Jobs, CronJobs (dwd-forward, synthetic-check), PVCs, images,
# configuration, the statistics checkpoint and its age, recent problems.
#
#   deploy/k3s/50-status.sh [--logs]
#
#   --logs   also print the last 20 log lines of every OpenTSx Deployment
#
# Exit codes:
#   0  the state was shown
#   1  the namespace does not exist, the cluster is not reachable, or the Deployment of the Modbus simulator or the
#      event bridge cannot be read
#   2  usage error: an unknown option, an argument, or a line of deploy/k3s/.env that is not a comment or KEY=VALUE
#      with a known key
set -euo pipefail
# shellcheck source=deploy/k3s/lib.sh
source "$(dirname "$0")/lib.sh"

# show_deployment NAME: the Deployment NAME, or "not deployed" when it does not exist; kubectl's error stays on stderr
show_deployment() {
  local out
  out="$(kns get "deployment/$1" --ignore-not-found)" \
    || die "deployment/$1: cannot read it; check the cluster with ${KUBECTL} -n $OPENTSX_NS get deployment/$1"
  if [[ -n "$out" ]]; then printf '%s\n' "$out"; else echo "  not deployed"; fi
}

LOGS=false
while [[ $# -gt 0 ]]; do
  case "$1" in
    --logs) LOGS=true; shift ;;
    -h|--help) usage 0 ;;
    -*) usage_error "$1: unknown option; expected --logs or --help" ;;
    *) usage_error "$1: unexpected argument; 50-status.sh takes options only" ;;
  esac
done
load_env
require_namespace "deploy it first with 30-deploy.sh"

info "configuration (opentsx-config)"
# shellcheck disable=SC2016  # a Go template, not a shell expansion
kns get configmap opentsx-config -o go-template='{{range $k, $v := .data}}  {{$k}}={{$v}}{{"\n"}}{{end}}'
info "deployments, pods, services"
kns get deployment,pod,service -l app.kubernetes.io/part-of=opentsx -o wide
info "jobs"
kns get job -L opentsx.org/demo 2>/dev/null || true
info "cronjobs (dwd-forward: DWD_FORWARD=true, synthetic-check: MONITORING=true)"
# shellcheck disable=SC2016  # a Go template, not a shell expansion
kns get cronjob -o go-template='{{range .items}}  {{.metadata.name}}  schedule "{{.spec.schedule}}"  last run {{or .status.lastScheduleTime "-"}}  last success {{or .status.lastSuccessfulTime "-"}}{{"\n"}}{{end}}' 2>/dev/null || true
info "persistent volume claims"
kns get pvc
info "images in use"
kns get pod -o 'jsonpath={range .items[*]}{.spec.containers[*].image}{"\n"}{end}' | sort | uniq -c
if have k3s; then
  if ! images="$(as_root k3s ctr images ls -q 2>&1)"; then
    warn "k3s containerd: cannot list the images (${images//$'\n'/ }); check it with sudo k3s ctr images ls"
  elif ! grep -F "$OPENTSX_IMAGE_REPO/" <<< "$images" | sed 's/^/  in k3s containerd: /'; then
    warn "k3s containerd: no $OPENTSX_IMAGE_REPO images; import them with 11-import-images.sh"
  fi
fi
info "statistics checkpoint (written every 30 s; older than a few minutes = processor stuck, see operations.md R2)"
if listing="$(kns exec deployment/opentsx-stats -- ls -l --time-style=+%Y-%m-%dT%H:%M:%S /state 2>&1)"; then
  printf '%s\n' "$listing"
  age_err="$(mktemp)"; trap 'rm -f "$age_err"' EXIT
  # shellcheck disable=SC2016  # expanded by the shell inside the pod
  if ! age="$(kns exec deployment/opentsx-stats -- sh -c 'echo $(( $(date +%s) - $(stat -c %Y /state/stats.ckpt) ))' 2>"$age_err")"; then
    err="$(<"$age_err")"
    warn "statistics checkpoint: cannot read its age (${err//$'\n'/ }); see ${KUBECTL} -n $OPENTSX_NS logs deployment/opentsx-stats"
  elif [[ "$age" =~ ^[0-9]+$ ]]; then
    if (( age > 300 )); then warn "statistics checkpoint: ${age} s old; the processor may be stuck, see docs/devops/operations.md R2"
    else ok "checkpoint age ${age} s"; fi
  else
    warn "statistics checkpoint: cannot read its age (\"${age//$'\n'/ }\" is not a number of seconds); see ${KUBECTL} -n $OPENTSX_NS logs deployment/opentsx-stats"
  fi
else
  warn "statistics checkpoint: cannot read it (${listing//$'\n'/ }); see ${KUBECTL} -n $OPENTSX_NS logs deployment/opentsx-stats"
fi
info "Modbus simulator (MODBUS_SIM=true)"
show_deployment opentsx-modbus-sim
info "event bridge (EVENT_BRIDGE=true)"
show_deployment opentsx-event-bridge
info "warnings (last 10 events)"
kns get events --field-selector type=Warning --sort-by=.lastTimestamp 2>/dev/null | tail -n 10 || true
if $LOGS; then
  for d in $(kns get deployment -l app.kubernetes.io/part-of=opentsx -o name); do
    info "logs $d"
    kns logs "$d" --tail=20 2>/dev/null || true
  done
fi
echo
echo "Studio:    ${KUBECTL} -n $OPENTSX_NS port-forward svc/opentsx-studio 8088:80     -> http://localhost:8088"
echo "Retrieval: ${KUBECTL} -n $OPENTSX_NS port-forward svc/opentsx-retrieval 8080:8080 -> http://localhost:8080/v1/health"
