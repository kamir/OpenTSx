#!/usr/bin/env bash
# Backs up the state of the installation that is neither in Git nor on KafScale (docs/devops/operations.md, Backup).
#
#   deploy/k3s/70-backup.sh [--out DIR] [--quiesce] [--no-data]
#
#   --out DIR    target directory (default ./opentsx-backup-<UTC time>, mode 700)
#   --quiesce    scale opentsx-stats (and with LAKE=true opentsx-iceberg-rest and opentsx-minio) to 0 during the copy
#                and back afterwards. Recommended with LAKE=true: the SQLite catalog and the MinIO tree are then
#                consistent. Without it the copy is taken live; the statistics checkpoint is always consistent (it is
#                written to a temporary file and renamed), the catalog database may not be.
#   --no-data    leave out the PVC opentsx-data (episode index, ground truth, metadata dry run, DWD CSV, Modbus episodes)
#
# Writes DIR/:
#   env                       deploy/k3s/.env - the configuration INCLUDING secrets (S3 keys, OpenMetadata token):
#                             keep the backup private, never commit it
#   opentsx-config.yaml       the ConfigMap as deployed
#   stats-state.tar.gz        PVC opentsx-stats-state: the statistics checkpoint (positions + engine state)
#   data.tar.gz               PVC opentsx-data
#   iceberg-catalog.tar.gz    PVC opentsx-iceberg-catalog (LAKE=true): the SQLite catalog of the Iceberg REST server
#   minio.tar.gz              PVC opentsx-minio (LAKE=true, LAKE_MINIO=true): the warehouse bucket incl. Parquet files
#   MANIFEST                  date, namespace, image tag, git revision, size and sha256 of every file
# How: a short-lived helper pod (tools image, root WITHOUT privileges: only CHOWN, DAC_OVERRIDE, FOWNER) mounts the
# PVCs read-only and streams `tar czf -` through kubectl exec. On a multi-node cluster all PVCs must be on one node.
# NOT backed up: the KafScale topics (they live in KafScale's own S3 bucket - back that up on the KafScale side), an
# external S3 warehouse (LAKE_MINIO=false: use your S3 tooling), the images (rebuild from Git with the same tag).
# Restore: deploy/k3s/71-restore.sh DIR
set -euo pipefail
# shellcheck source=deploy/k3s/lib.sh
source "$(dirname "$0")/lib.sh"

OUT=""; QUIESCE=false; DATA=true
while [[ $# -gt 0 ]]; do
  case "$1" in
    --out) OUT="${2:?--out needs a directory}"; shift 2 ;;
    --quiesce) QUIESCE=true; shift ;;
    --no-data) DATA=false; shift ;;
    -h|--help) usage 0 ;;
    *) die "unknown option $1 (see --help)" ;;
  esac
done
load_env
kc get namespace "$OPENTSX_NS" >/dev/null 2>&1 || die "namespace $OPENTSX_NS not found - nothing to back up"
OUT="${OUT:-$PWD/opentsx-backup-$(date -u +%Y%m%dT%H%M%SZ)}"
[[ ! -e "$OUT" || -z "$(ls -A "$OUT" 2>/dev/null)" ]] || die "$OUT exists and is not empty"
umask 077
mkdir -p "$OUT"

# PVC -> archive name (only PVCs that exist are backed up)
declare -A ARCHIVE=([opentsx-stats-state]=stats-state [opentsx-data]=data [opentsx-iceberg-catalog]=iceberg-catalog
  [opentsx-minio]=minio)
$DATA || unset 'ARCHIVE[opentsx-data]'
PVCS=()
for p in opentsx-stats-state opentsx-data opentsx-iceberg-catalog opentsx-minio; do
  [[ -n "${ARCHIVE[$p]:-}" ]] || continue
  kns get pvc "$p" >/dev/null 2>&1 && PVCS+=("$p")
done
(( ${#PVCS[@]} )) || die "no OpenTSx PVCs found in $OPENTSX_NS"

HELPER=opentsx-backup-helper
finish() { kns delete pod "$HELPER" --ignore-not-found --wait=false >/dev/null 2>&1 || true; start_writers; }
trap finish EXIT

if $QUIESCE; then
  info "quiescing writers"
  stop_writers
fi

info "helper pod $HELPER (PVCs: ${PVCS[*]})"
kns delete pod "$HELPER" --ignore-not-found --wait=true >/dev/null
helper_pod "$HELPER" ro "${PVCS[@]}"

info "configuration"
if [[ -f "$ENV_FILE" ]]; then cp "$ENV_FILE" "$OUT/env"; ok "env (contains secrets - keep $OUT private)"
else warn "$ENV_FILE not found - configuration not backed up"; fi
kns get configmap opentsx-config -o yaml > "$OUT/opentsx-config.yaml" && ok "opentsx-config.yaml"

info "volumes"
for p in "${PVCS[@]}"; do
  f="$OUT/${ARCHIVE[$p]}.tar.gz"
  if kns exec "$HELPER" -- tar czf - --numeric-owner -C "/backup/$p" . > "$f" && gzip -t "$f"; then
    ok "${ARCHIVE[$p]}.tar.gz ($(du -h "$f" | cut -f1)) from PVC $p"
  else
    die "backup of PVC $p failed"
  fi
done

{
  echo "# OpenTSx backup - deploy/k3s/71-restore.sh $OUT"
  echo "created=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "namespace=$OPENTSX_NS"
  echo "domain=$OPENTSX_DOMAIN"
  echo "image=$TOOLS_IMAGE"
  echo "git=$(git -C "$ROOT" rev-parse --short HEAD 2>/dev/null || echo unknown)"
  echo "quiesced=$QUIESCE"
  echo "pvcs=${PVCS[*]}"
  (cd "$OUT" && for f in *; do [[ "$f" == MANIFEST ]] && continue; echo "file=$(sha256sum "$f" | cut -d' ' -f1) $(wc -c < "$f") $f"; done)
} > "$OUT/MANIFEST"
info "backup written to $OUT"
if is_true "$LAKE" && ! $QUIESCE; then
  warn "LAKE=true without --quiesce: the catalog/MinIO copy may be inconsistent if they were written during the backup"
fi
echo "restore: deploy/k3s/71-restore.sh $OUT"
