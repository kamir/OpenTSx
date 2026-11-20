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
#   env                       deploy/k3s/.env: the configuration INCLUDING secrets (S3 keys, OpenMetadata token):
#                             keep the backup private, never commit it
#   opentsx-config.yaml       the ConfigMap as deployed
#   stats-state.tar.gz        PVC opentsx-stats-state: the statistics checkpoint (positions + engine state)
#   data.tar.gz               PVC opentsx-data
#   iceberg-catalog.tar.gz    PVC opentsx-iceberg-catalog (LAKE=true): the SQLite catalog of the Iceberg REST server
#   minio.tar.gz              PVC opentsx-minio (LAKE=true, LAKE_MINIO=true): the warehouse bucket incl. Parquet files
#   MANIFEST                  date, namespace, image tag, git revision, size and sha256 of every file
# A run that stops before MANIFEST removes the files it wrote, and DIR when it created it, so the rerun with the same
# --out finds DIR empty; DIR must be new or empty at the start.
# How: a short-lived helper pod (tools image, root WITHOUT privileges: only CHOWN, DAC_OVERRIDE, FOWNER) mounts the
# PVCs read-only and streams `tar czf -` through kubectl exec. On a multi-node cluster all PVCs must be on one node.
# NOT backed up: the KafScale topics (they live in KafScale's own S3 bucket; back that up on the KafScale side), an
# external S3 warehouse (LAKE_MINIO=false: use your S3 tooling), the images (rebuild from Git with the same tag).
# Restore: deploy/k3s/71-restore.sh DIR
#
# Exit codes:
#   0  backup written
#   1  the backup failed, for example a writer still ran 150 s after --quiesce scaled it to 0, or OPENTSX_NS set in
#      the shell differs from OPENTSX_NS of deploy/k3s/.env, or of env.example when .env sets none (checked before
#      anything is written)
#   2  usage error: an unknown option, an option without its value, or a line of deploy/k3s/.env that is not a
#      comment or KEY=VALUE with a known key
set -euo pipefail
# shellcheck source=deploy/k3s/lib.sh
source "$(dirname "$0")/lib.sh"

OUT=""; QUIESCE=false; DATA=true
while [[ $# -gt 0 ]]; do
  case "$1" in
    --out) [[ -n "${2:-}" ]] || usage_error "--out: missing value; expected a directory"; OUT="$2"; shift 2 ;;
    --quiesce) QUIESCE=true; shift ;;
    --no-data) DATA=false; shift ;;
    -h|--help) usage 0 ;;
    *) usage_error "$1: unknown option; expected --out DIR, --quiesce, --no-data or --help" ;;
  esac
done
NS_FROM_SHELL=false
[[ -z "${OPENTSX_NS+x}" ]] || NS_FROM_SHELL=true
load_env
# DIR/env is a copy of .env, but namespace= takes OPENTSX_NS from the shell; the sed reads env as 71-restore.sh does
if $NS_FROM_SHELL && [[ -f "$ENV_FILE" ]]; then
  env_ns="$(sed -n -E 's/^[[:space:]]*(export[[:space:]]+)?OPENTSX_NS=([^[:space:]#]*).*/\2/p' \
    "$K3S_DIR/env.example" "$ENV_FILE" | tail -n 1 | tr -d "\"'")"
  ns_from=deploy/k3s/env.example
  grep -qE '^[[:space:]]*(export[[:space:]]+)?OPENTSX_NS=' "$ENV_FILE" && ns_from="${ENV_FILE#"$ROOT"/}"
  [[ "$env_ns" == "$OPENTSX_NS" ]] \
    || die "OPENTSX_NS: the shell sets $OPENTSX_NS, $ns_from names $env_ns; unset it or change .env first"
fi
require_namespace "check OPENTSX_NS in deploy/k3s/.env and the kubectl context, or deploy it first with 30-deploy.sh"
OUT="${OUT:-$PWD/opentsx-backup-$(date -u +%Y%m%dT%H%M%SZ)}"
if [[ -e "$OUT" ]]; then
  listing="$(ls -A "$OUT")" || die "$OUT: cannot list it; see the error above, then give a new or empty directory with --out"
  [[ -z "$listing" ]] || die "$OUT: exists and is not empty; give a new or empty directory with --out"
fi

# PVC -> archive name (only PVCs that exist are backed up)
declare -A ARCHIVE=([opentsx-stats-state]=stats-state [opentsx-data]=data [opentsx-iceberg-catalog]=iceberg-catalog
  [opentsx-minio]=minio)
$DATA || unset 'ARCHIVE[opentsx-data]'
PVCS=()
for p in opentsx-stats-state opentsx-data opentsx-iceberg-catalog opentsx-minio; do
  [[ -n "${ARCHIVE[$p]:-}" ]] || continue
  found="$(kns get pvc "$p" --ignore-not-found -o name)" \
    || die "PVC $p: cannot read it; check the cluster with ${KUBECTL} -n $OPENTSX_NS get pvc $p"
  [[ -z "$found" ]] || PVCS+=("$p")
done
(( ${#PVCS[@]} )) || die "namespace $OPENTSX_NS: no OpenTSx PVC found, nothing to back up; check OPENTSX_NS in deploy/k3s/.env, or deploy it first with 30-deploy.sh"

CREATED=false; [[ -e "$OUT" ]] || CREATED=true
umask 077
mkdir -p "$OUT"
WRITTEN=false
# OUT was new or empty at the start (checked above), so what it holds without a MANIFEST is what this run wrote
discard() {
  local p
  rm -f "$OUT/env" "$OUT/opentsx-config.yaml" "$OUT/MANIFEST"
  for p in "${PVCS[@]}"; do rm -f "$OUT/${ARCHIVE[$p]}.tar.gz"; done
  if $CREATED; then
    rmdir "$OUT" \
      || warn "$OUT: not removed, it holds files this run did not write; remove them, or give a new or empty directory with --out"
  fi
}

HELPER=opentsx-backup-helper
finish() {
  kns delete pod "$HELPER" --ignore-not-found --wait=false >/dev/null 2>&1 || true
  start_writers
  $WRITTEN || discard
}
trap finish EXIT

if $QUIESCE; then
  info "quiescing writers"
  stop_writers
fi

info "helper pod $HELPER (PVCs: ${PVCS[*]})"
kns delete pod "$HELPER" --ignore-not-found --wait=true >/dev/null
helper_pod "$HELPER" ro "${PVCS[@]}"

info "configuration"
if [[ -f "$ENV_FILE" ]]; then cp "$ENV_FILE" "$OUT/env"; ok "env (contains secrets; keep $OUT private)"
else warn "$ENV_FILE: not found; the backup holds no configuration, run 70-backup.sh in the checkout that configured the installation"; fi
kns get configmap opentsx-config -o yaml > "$OUT/opentsx-config.yaml" \
  || die "ConfigMap opentsx-config: cannot read it in namespace $OPENTSX_NS; see the error above, then run 70-backup.sh again"
ok "opentsx-config.yaml"

info "volumes"
for p in "${PVCS[@]}"; do
  f="$OUT/${ARCHIVE[$p]}.tar.gz"
  if kns exec "$HELPER" -- tar czf - --numeric-owner -C "/backup/$p" . > "$f" && gzip -t "$f"; then
    ok "${ARCHIVE[$p]}.tar.gz ($(du -h "$f" | cut -f1)) from PVC $p"
  else
    die "PVC $p: the backup into ${ARCHIVE[$p]}.tar.gz failed; see the error above, then run 70-backup.sh again"
  fi
done

{
  echo "# OpenTSx backup, restore with deploy/k3s/71-restore.sh $OUT"
  echo "created=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "namespace=$OPENTSX_NS"
  echo "domain=$OPENTSX_DOMAIN"
  echo "image=$TOOLS_IMAGE"
  echo "git=$(git -C "$ROOT" rev-parse --short HEAD 2>/dev/null || echo unknown)"
  echo "quiesced=$QUIESCE"
  echo "pvcs=${PVCS[*]}"
  (cd "$OUT" && for f in *; do [[ "$f" == MANIFEST ]] && continue; echo "file=$(sha256sum "$f" | cut -d' ' -f1) $(wc -c < "$f") $f"; done)
} > "$OUT/MANIFEST"
WRITTEN=true
info "backup written to $OUT"
if is_true "$LAKE" && ! $QUIESCE; then
  warn "LAKE=true without --quiesce: the copy of the Iceberg catalog and MinIO may be inconsistent if they were written during the backup; run 70-backup.sh --quiesce for a consistent copy"
fi
echo "restore: deploy/k3s/71-restore.sh $OUT"
