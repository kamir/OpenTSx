#!/usr/bin/env bash
# Restores a backup of 70-backup.sh into the PVCs of the CURRENT installation (docs/devops/operations.md, Backup).
#
#   deploy/k3s/71-restore.sh DIR [--only stats-state,data,iceberg-catalog,minio,env] [--yes]
#
#   DIR       a directory written by 70-backup.sh (checked against its MANIFEST before anything is changed)
#   --only    restore only these parts (default: every archive in DIR; "env" is never restored by default)
#   --yes     do not ask
#
# Order of a full restore on a new cluster:
#   cp DIR/env deploy/k3s/.env      (or: 71-restore.sh DIR --only env)    then 20-configure.sh, 10/11 images, 30-deploy.sh
#   deploy/k3s/71-restore.sh DIR     (the PVCs must exist: run 30-deploy.sh first)
# What it does: stops the writers (opentsx-stats, opentsx-iceberg-rest, opentsx-minio scaled to 0), starts a helper pod
# with the PVCs mounted read-write, REPLACES each PVC's content with the archive (ownership as in the backup), and
# scales the writers back. The KafScale topics are not touched.
# Caution: the statistics checkpoint holds positions (offsets) in tsx.<domain>.episodes.v2. Restore it only against the
# SAME topics; if the topics were deleted and recreated, skip stats-state (the processor then re-reads from the start).
set -euo pipefail
# shellcheck source=deploy/k3s/lib.sh
source "$(dirname "$0")/lib.sh"

DIR=""; ONLY=""; YES=false
while [[ $# -gt 0 ]]; do
  case "$1" in
    --only) ONLY="${2:?--only needs a list}"; shift 2 ;;
    --yes|-y) YES=true; shift ;;
    -h|--help) usage 0 ;;
    -*) die "unknown option $1 (see --help)" ;;
    *) [[ -z "$DIR" ]] || die "only one backup directory"; DIR="$1"; shift ;;
  esac
done
[[ -n "$DIR" ]] || usage 1
[[ -f "$DIR/MANIFEST" ]] || die "$DIR/MANIFEST not found - not a 70-backup.sh directory"
load_env

declare -A PVC=([stats-state]=opentsx-stats-state [data]=opentsx-data [iceberg-catalog]=opentsx-iceberg-catalog
  [minio]=opentsx-minio)
for x in ${ONLY//,/ }; do
  [[ -n "${PVC[$x]:-}" || "$x" == env ]] || die "unknown part '$x' (stats-state, data, iceberg-catalog, minio, env)"
done
want() { [[ -z "$ONLY" && "$1" != env ]] || [[ ",$ONLY," == *",$1,"* ]]; }

info "checking $DIR against its MANIFEST"
while read -r key size name; do
  [[ "$key" == file=* ]] || continue
  sum="${key#file=}"
  [[ -f "$DIR/$name" ]] || die "$name listed in MANIFEST but missing"
  [[ "$(sha256sum "$DIR/$name" | cut -d' ' -f1)" == "$sum" ]] || die "$name: checksum mismatch - backup damaged"
  [[ "$name" == *.tar.gz ]] && { gzip -t "$DIR/$name" || die "$name: not a valid gzip archive"; }
  ok "$name ($size bytes)"
done < "$DIR/MANIFEST"
grep -E '^(created|namespace|image|git)=' "$DIR/MANIFEST" | sed 's/^/  /'

PARTS=()
for part in stats-state data iceberg-catalog minio; do
  want "$part" && [[ -f "$DIR/$part.tar.gz" ]] && PARTS+=("$part")
done
RESTORE_ENV=false
if want env; then [[ -f "$DIR/env" ]] || die "$DIR/env not found"; RESTORE_ENV=true; fi
(( ${#PARTS[@]} )) || $RESTORE_ENV || die "nothing to restore (see the file list in $DIR/MANIFEST)"

if ! $YES; then
  echo "This REPLACES the content of: ${PARTS[*]:-(no volumes)}$($RESTORE_ENV && echo " and deploy/k3s/.env") in namespace $OPENTSX_NS."
  read -r -p "Continue? [y/N] " a
  [[ "$a" =~ ^[yY] ]] || { echo "aborted"; exit 1; }
fi

if $RESTORE_ENV; then
  [[ -f "$ENV_FILE" ]] && cp -p "$ENV_FILE" "$ENV_FILE.before-restore" && warn "previous .env kept as $ENV_FILE.before-restore"
  (umask 077; cp "$DIR/env" "$ENV_FILE"); chmod 600 "$ENV_FILE"
  ok "deploy/k3s/.env restored - run 20-configure.sh (re-renders the overlay) and 30-deploy.sh"
  (( ${#PARTS[@]} )) || exit 0
  load_env
fi

kc get namespace "$OPENTSX_NS" >/dev/null 2>&1 || die "namespace $OPENTSX_NS not found - run 20-configure.sh and 30-deploy.sh first"
PVCS=()
for part in "${PARTS[@]}"; do
  kns get pvc "${PVC[$part]}" >/dev/null 2>&1 || die "PVC ${PVC[$part]} not found (LAKE profile not configured? run 30-deploy.sh)"
  PVCS+=("${PVC[$part]}")
done

HELPER=opentsx-restore-helper
finish() { kns delete pod "$HELPER" --ignore-not-found --wait=false >/dev/null 2>&1 || true; start_writers; }
trap finish EXIT

info "stopping writers"
stop_writers

info "helper pod $HELPER (PVCs: ${PVCS[*]})"
kns delete pod "$HELPER" --ignore-not-found --wait=true >/dev/null
helper_pod "$HELPER" rw "${PVCS[@]}"

info "restoring"
for part in "${PARTS[@]}"; do
  p="${PVC[$part]}"
  kns exec "$HELPER" -- sh -c "find '/backup/$p' -mindepth 1 -delete" || die "could not empty PVC $p"
  kns exec -i "$HELPER" -- tar xzf - --numeric-owner -C "/backup/$p" < "$DIR/$part.tar.gz" || die "unpacking $part.tar.gz into $p failed"
  ok "$part.tar.gz -> PVC $p"
done
info "restore done (writers are scaled back now); check with deploy/k3s/50-status.sh"
