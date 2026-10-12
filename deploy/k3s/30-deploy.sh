#!/usr/bin/env bash
# Applies the rendered overlay (kubectl apply -k) and waits until the services are ready.
#
#   deploy/k3s/30-deploy.sh [--restart] [--dry-run] [--timeout 180s]
#
#   --restart   restart all OpenTSx Deployments afterwards (new image under the same tag, changed configuration)
#   --dry-run   server-side dry run, changes nothing
#
# Deploys: namespace, ConfigMap opentsx-config, Secret opentsx-secrets, PVCs, opentsx-stats (1 replica, checkpoint
# PVC), opentsx-retrieval (demo store or Iceberg), opentsx-studio; with LAKE=true the Iceberg REST catalog (and MinIO).
# Idempotent. Demo Jobs are not part of it: deploy/k3s/40-demo.sh.
set -euo pipefail
# shellcheck source=deploy/k3s/lib.sh
source "$(dirname "$0")/lib.sh"

RESTART=false; DRY=false; TIMEOUT=180s
while [[ $# -gt 0 ]]; do
  case "$1" in
    --restart) RESTART=true; shift ;;
    --dry-run) DRY=true; shift ;;
    --timeout) TIMEOUT="${2:?--timeout needs a value}"; shift 2 ;;
    -h|--help) usage 0 ;;
    *) die "unknown option $1 (see --help)" ;;
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

info "waiting for rollouts (timeout $TIMEOUT each)"
rc=0
for d in $(kns get deployment -l app.kubernetes.io/part-of=opentsx -o name); do
  if kns rollout status "$d" --timeout="$TIMEOUT"; then ok "$d"; else fail "$d not ready"; rc=1; fi
done
if (( rc )); then
  echo
  echo "Diagnose with:  deploy/k3s/50-status.sh   and   ${KUBECTL} -n $OPENTSX_NS describe pod <pod>"
  echo "Typical causes: ErrImageNeverPull/ImagePullBackOff -> 11-import-images.sh; Pending -> PVC/StorageClass;"
  echo "                stats CrashLoopBackOff -> KafScale not reachable (40-demo.sh kafscale-check)."
  exit 1
fi
info "deployed"
echo "next: deploy/k3s/40-demo.sh kafscale-check   then   deploy/k3s/40-demo.sh all"
