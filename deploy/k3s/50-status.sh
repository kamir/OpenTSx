#!/usr/bin/env bash
# Shows the state of the OpenTSx installation: workloads, Jobs, PVCs, images, configuration, recent problems.
#
#   deploy/k3s/50-status.sh [--logs]
#
#   --logs   also print the last 20 log lines of every OpenTSx Deployment
set -euo pipefail
# shellcheck source=deploy/k3s/lib.sh
source "$(dirname "$0")/lib.sh"

LOGS=false
while [[ $# -gt 0 ]]; do
  case "$1" in
    --logs) LOGS=true; shift ;;
    -h|--help) usage 0 ;;
    *) die "unknown option $1 (see --help)" ;;
  esac
done
load_env
kc get namespace "$OPENTSX_NS" >/dev/null 2>&1 || die "namespace $OPENTSX_NS not found - not deployed yet (30-deploy.sh)"

info "configuration (opentsx-config)"
# shellcheck disable=SC2016  # a Go template, not a shell expansion
kns get configmap opentsx-config -o go-template='{{range $k, $v := .data}}  {{$k}}={{$v}}{{"\n"}}{{end}}'
info "deployments, pods, services"
kns get deployment,pod,service -l app.kubernetes.io/part-of=opentsx -o wide
info "jobs"
kns get job -L opentsx.org/demo 2>/dev/null || true
info "persistent volume claims"
kns get pvc
info "images in use"
kns get pod -o 'jsonpath={range .items[*]}{.spec.containers[*].image}{"\n"}{end}' | sort | uniq -c
if have k3s; then
  as_root k3s ctr images ls -q 2>/dev/null | grep -F "$OPENTSX_IMAGE_REPO/" | sed 's/^/  in k3s containerd: /' || warn "no $OPENTSX_IMAGE_REPO images in k3s containerd (11-import-images.sh)"
fi
info "statistics checkpoint"
kns exec deployment/opentsx-stats -- ls -l /state 2>/dev/null || warn "not available (pod not running or no checkpoint yet)"
info "warnings (last 10 events)"
kns get events --field-selector type=Warning --sort-by=.lastTimestamp 2>/dev/null | tail -n 10 || true
if $LOGS; then
  for d in $(kns get deployment -l app.kubernetes.io/part-of=opentsx -o name); do
    info "logs $d"
    kns logs "$d" --tail=20 2>/dev/null | grep -v '^Picked up JAVA_TOOL_OPTIONS' || true
  done
fi
echo
echo "Studio:    ${KUBECTL} -n $OPENTSX_NS port-forward svc/opentsx-studio 8088:80     -> http://localhost:8088"
echo "Retrieval: ${KUBECTL} -n $OPENTSX_NS port-forward svc/opentsx-retrieval 8080:8080 -> http://localhost:8080/v1/health"
