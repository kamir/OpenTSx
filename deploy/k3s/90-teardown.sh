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
    *) die "unknown option $1 (see --help)" ;;
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
  info "namespace $OPENTSX_NS does not exist - nothing to delete in the cluster"
fi

if $IMAGES; then
  have k3s || die "--images needs k3s on this host"
  info "removing images from k3s containerd"
  for img in "$TOOLS_IMAGE" "$STUDIO_IMAGE" "$SAAS_FRONTEND_IMAGE"; do
    as_root k3s ctr images rm "docker.io/$img" "$img" 2>/dev/null || true
  done
fi
info "done (KafScale topics are untouched)"
