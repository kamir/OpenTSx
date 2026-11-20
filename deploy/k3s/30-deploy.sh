#!/usr/bin/env bash
# Applies the rendered overlay (kubectl apply -k) and waits until every Deployment is ready.
#
#   deploy/k3s/30-deploy.sh [--restart] [--dry-run] [--timeout 180s]
#
#   --restart   restart all OpenTSx Deployments afterwards (new image under the same tag, changed configuration)
#   --dry-run   server-side dry run, changes nothing
#   --timeout   how long to wait for each Deployment (kubectl rollout status), a whole number with s, m or h
#
# Deploys: namespace, ConfigMap opentsx-config, Secret opentsx-secrets, PVCs, opentsx-stats (1 replica, checkpoint
# PVC), opentsx-retrieval (demo store or Iceberg), opentsx-studio; with LAKE=true the Iceberg REST catalog (and MinIO).
# Idempotent. Demo Jobs are not part of it: deploy/k3s/40-demo.sh.
# Ready means the readiness probe passes: GET /v1/ready of the retrieval server and the event bridge, GET /ready of
# the SaaS backend, a progress file younger than 2 minutes for opentsx-stats and opentsx-vectors.
#
# Exit codes:
#   0  deployed, every Deployment ready
#   1  the cluster is not reachable, the apply failed, or a Deployment was not ready within the timeout (each one is
#      named)
#   2  usage error: an unknown option, an option without its value, a --timeout that is not a duration, an
#      argument, or a line of deploy/k3s/.env that is not a comment or KEY=VALUE with a known key
set -euo pipefail
# shellcheck source=deploy/k3s/lib.sh
source "$(dirname "$0")/lib.sh"

RESTART=false; DRY=false; TIMEOUT=180s
while [[ $# -gt 0 ]]; do
  case "$1" in
    --restart) RESTART=true; shift ;;
    --dry-run) DRY=true; shift ;;
    --timeout)
      [[ $# -ge 2 ]] || usage_error "--timeout: missing value; expected a duration such as 180s or 5m"
      [[ "$2" =~ ^([0-9]+[smh])+$ ]] \
        || usage_error "--timeout: \"$2\" is not a duration; expected a whole number with the unit s, m or h such as 180s or 5m"
      TIMEOUT="$2"; shift 2 ;;
    -h|--help) usage 0 ;;
    -*) usage_error "$1: unknown option; expected --restart, --dry-run, --timeout DURATION or --help" ;;
    *) usage_error "$1: unexpected argument; 30-deploy.sh takes options only" ;;
  esac
done
load_env
require_overlay
kc get nodes >/dev/null 2>&1 || die "cluster not reachable with '$KUBECTL' (see 00-prereqs.sh)"

if $DRY; then
  info "server-side dry run of overlays/local"
  kc apply -k "$OVERLAY" --dry-run=server
  exit 0
fi

# setup Jobs (MinIO bucket) are immutable: delete them first so a re-apply recreates them (they are idempotent)
if kc get namespace "$OPENTSX_NS" >/dev/null 2>&1; then
  kns delete job -l opentsx.org/setup=true --ignore-not-found >/dev/null
fi

info "kubectl apply -k deploy/k3s/overlays/local (namespace $OPENTSX_NS)"
kc apply -k "$OVERLAY"

if $RESTART; then
  info "restarting deployments"
  kns rollout restart deployment -l app.kubernetes.io/part-of=opentsx
fi

info "waiting until every Deployment is ready (timeout $TIMEOUT each)"
deployments="$(kns get deployment -l app.kubernetes.io/part-of=opentsx -o name)" \
  || die "cannot list the Deployments of namespace $OPENTSX_NS; check the cluster with ${KUBECTL} -n $OPENTSX_NS get deployment"
rc=0
for d in $deployments; do
  if kns rollout status "$d" --timeout="$TIMEOUT"; then ok "$d ready"
  else fail "$d: not ready after $TIMEOUT; see ${KUBECTL} -n $OPENTSX_NS describe $d"; rc=1; fi
done
if (( rc )); then
  echo
  echo "Diagnose with:  deploy/k3s/50-status.sh   and   ${KUBECTL} -n $OPENTSX_NS logs <deployment>"
  echo "Typical causes: ErrImageNeverPull/ImagePullBackOff -> 11-import-images.sh; Pending -> PVC/StorageClass;"
  echo "                opentsx-stats not ready -> its topics do not exist yet on a new broker:"
  echo "                40-demo.sh kafscale-check creates them, then 30-deploy.sh --restart;"
  echo "                or KafScale is not reachable (docs/devops/operations.md, R1 and R2)."
  exit 1
fi
info "deployed"
echo "next: deploy/k3s/40-demo.sh kafscale-check   then   deploy/k3s/40-demo.sh all"
