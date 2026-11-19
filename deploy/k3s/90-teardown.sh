#!/usr/bin/env bash
# Removes the OpenTSx installation from k3s.
#
#   deploy/k3s/90-teardown.sh [--keep-data] [--images] [--yes]
#
#   (default)    delete everything in overlays/local, including the namespace and its PVCs (checkpoint, demo data,
#                MinIO/Iceberg catalog with LAKE=true)
#   --keep-data  delete workloads, Jobs, CronJobs and Services but keep the namespace, PVCs, ConfigMaps and Secret
#   --images     also remove the OpenTSx images from k3s' containerd (local k3s only)
#   --yes        do not ask
#
# NOT touched: the KafScale cluster and its topics tsx.<domain>.*.v2 (delete them with your KafScale tooling if
# wanted), deploy/k3s/.env and overlays/local (delete them by hand to forget the configuration).
#
# Exit codes:
#   0  removed
#   1  a deletion failed, an image could not be removed from k3s' containerd, or the question was not answered with y
#   2  usage error: an unknown option, or a line of deploy/k3s/.env that is not a comment or KEY=VALUE with a
#      known key
set -euo pipefail
# shellcheck source=deploy/k3s/lib.sh
source "$(dirname "$0")/lib.sh"

KEEP=false; IMAGES=false; YES=false
while [[ $# -gt 0 ]]; do
  case "$1" in
    --keep-data) KEEP=true; shift ;;
    --images) IMAGES=true; shift ;;
    --yes|-y) YES=true; shift ;;
    -h|--help) usage 0 ;;
    *) usage_error "$1: unknown option; expected --keep-data, --images, --yes or --help" ;;
  esac
done
load_env
require_overlay

if ! $YES; then
  what="namespace $OPENTSX_NS with all data"; $KEEP && what="workloads in $OPENTSX_NS (data kept)"
  read -r -p "Delete $what? [y/N] " a
  [[ "$a" =~ ^[yY] ]] || { echo "aborted"; exit 1; }
fi

if kc get namespace "$OPENTSX_NS" >/dev/null 2>&1; then
  info "deleting Jobs"
  kns delete job -l app.kubernetes.io/part-of=opentsx --ignore-not-found
  kns delete job -l opentsx.org/setup=true --ignore-not-found
  if $KEEP; then
    info "deleting Deployments, CronJobs, Services, Ingress (keeping PVCs, ConfigMaps, Secret)"
    kns delete deployment,cronjob,service,ingress -l app.kubernetes.io/part-of=opentsx --ignore-not-found
  else
    info "kubectl delete -k overlays/local (namespace $OPENTSX_NS)"
    kc delete -k "$OVERLAY" --ignore-not-found --wait=true
  fi
else
  info "namespace $OPENTSX_NS does not exist; nothing to delete in the cluster"
fi

if $IMAGES; then
  have k3s || die "--images needs k3s on this host"
  info "removing images from k3s containerd"
  kept=0
  for img in "$TOOLS_IMAGE" "$STUDIO_IMAGE" "$SAAS_FRONTEND_IMAGE" "$SAAS_BACKEND_IMAGE"; do
    if ! err="$(as_root k3s ctr images rm "docker.io/$img" "$img" 2>&1 >/dev/null)"; then
      warn "$img: not removed from k3s containerd: ${err//$'\n'/ }"
      kept=$((kept + 1))
    fi
  done
  (( kept == 0 )) || die "$kept image(s) not removed from k3s containerd; see the warn lines above"
fi
info "done (KafScale topics are untouched)"
