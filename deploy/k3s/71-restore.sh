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
# What it does: checks the namespace and the PVCs of the current .env (env alone needs no cluster), keeps the current
# .env as .env.before-restore (and stops when an earlier .env.before-restore differs from .env), stops the writers
# (opentsx-stats, opentsx-iceberg-rest, opentsx-minio scaled to 0), starts a helper pod with the PVCs mounted
# read-write, REPLACES each PVC's content with the archive (ownership as in the backup), replaces .env with the
# backup's env last and scales the writers back. With env and volumes, the namespace= line of the MANIFEST and
# OPENTSX_NS of the backup's env must both name OPENTSX_NS as the shell or the current .env sets it. A failed step
# leaves the configuration as it was, and that namespace check keeps the rerun in the same namespace. A .env that
# already equals the backup's env stays, and so does the kept copy.
# The KafScale topics are not touched.
# Caution: the statistics checkpoint holds positions (offsets) in tsx.<domain>.episodes.v2. Restore it only against the
# SAME topics; if the topics were deleted and recreated, skip stats-state (the processor then re-reads from the start).
#
# Exit codes:
#   0  restored
#   1  the backup is damaged or incomplete, with env and volumes its MANIFEST names no namespace or another one than
#      its env or OPENTSX_NS (checked before the question), the namespace or a PVC is missing or cannot be read, with
#      env an earlier .env.before-restore differs from .env, a step failed, or the question was not answered with y
#      (also at the end of standard input)
#   2  usage error: no backup directory or a second one, an unknown option, --only without its value or with an
#      unknown part, or a line of deploy/k3s/.env that is not a comment or KEY=VALUE with a known key
set -euo pipefail
# shellcheck source=deploy/k3s/lib.sh
source "$(dirname "$0")/lib.sh"

DIR=""; ONLY=""; YES=false
while [[ $# -gt 0 ]]; do
  case "$1" in
    --only)
      [[ -n "${2:-}" ]] || usage_error "--only: missing value; expected a list of stats-state, data, iceberg-catalog, minio and env"
      ONLY="$2"; shift 2 ;;
    --yes|-y) YES=true; shift ;;
    -h|--help) usage 0 ;;
    -*) usage_error "$1: unknown option; expected --only LIST, --yes or --help" ;;
    *) [[ -z "$DIR" ]] || usage_error "$1: unexpected argument; 71-restore.sh takes one backup directory"; DIR="$1"; shift ;;
  esac
done
[[ -n "$DIR" ]] || usage_error "DIR: missing; expected 71-restore.sh DIR [--only LIST] [--yes]"
[[ -f "$DIR/MANIFEST" ]] || die "$DIR/MANIFEST: not found; expected a directory written by 70-backup.sh"
NS_FROM_SHELL=false
[[ -z "${OPENTSX_NS+x}" ]] || NS_FROM_SHELL=true
load_env

declare -A PVC=([stats-state]=opentsx-stats-state [data]=opentsx-data [iceberg-catalog]=opentsx-iceberg-catalog
  [minio]=opentsx-minio)
for x in ${ONLY//,/ }; do
  [[ -n "${PVC[$x]:-}" || "$x" == env ]] \
    || usage_error "--only: \"$x\" is not a part; expected stats-state, data, iceberg-catalog, minio or env"
done
want() { [[ -z "$ONLY" && "$1" != env ]] || [[ ",$ONLY," == *",$1,"* ]]; }

info "checking $DIR against its MANIFEST"
while read -r key size name; do
  [[ "$key" == file=* ]] || continue
  sum="${key#file=}"
  [[ -f "$DIR/$name" ]] || die "$name: listed in MANIFEST but missing; the backup is incomplete, use another backup"
  [[ "$(sha256sum "$DIR/$name" | cut -d' ' -f1)" == "$sum" ]] || die "$name: checksum mismatch; the backup is damaged, use another backup"
  [[ "$name" == *.tar.gz ]] \
    && { gzip -t "$DIR/$name" || die "$name: not a valid gzip archive; the backup is damaged, use another backup"; }
  ok "$name ($size bytes)"
done < "$DIR/MANIFEST"
sed -n -E 's/^(created|namespace|image|git)=/  &/p' "$DIR/MANIFEST"

PARTS=()
for part in stats-state data iceberg-catalog minio; do
  want "$part" && [[ -f "$DIR/$part.tar.gz" ]] && PARTS+=("$part")
done
RESTORE_ENV=false
if want env; then
  [[ -f "$DIR/env" ]] || die "$DIR/env: not found; the backup holds no configuration, leave env out of --only"
  RESTORE_ENV=true
fi
(( ${#PARTS[@]} )) || $RESTORE_ENV \
  || die "$DIR: nothing to restore; the backup holds none of the parts asked for, see the file list in $DIR/MANIFEST"
# the volumes go into the current OPENTSX_NS, the next 20-configure.sh and 30-deploy.sh into that of the backup
if $RESTORE_ENV && (( ${#PARTS[@]} )); then
  backup_ns="$(sed -n '/^namespace=/{s///p;q;}' "$DIR/MANIFEST")"
  [[ -n "$backup_ns" ]] || die "namespace: missing in $DIR/MANIFEST; restore env alone first, then the volumes"
  # as load_env reads it: env.example first, the backup's env last; 70-backup.sh takes namespace= from the shell too
  env_ns="$(sed -n -E 's/^[[:space:]]*(export[[:space:]]+)?OPENTSX_NS=([^[:space:]#]*).*/\2/p' \
    "$K3S_DIR/env.example" "$DIR/env" | tail -n 1 | tr -d "\"'")"
  [[ "$env_ns" == "$backup_ns" ]] \
    || die "namespace: the backup is of $backup_ns, $DIR/env names $env_ns; restore env alone first, then the volumes"
  # load_env lets the shell win over .env, and restoring env does not change the shell
  [[ "$backup_ns" == "$OPENTSX_NS" ]] || ! $NS_FROM_SHELL \
    || die "namespace: the backup is of $backup_ns, the shell sets OPENTSX_NS to $OPENTSX_NS; set OPENTSX_NS to $backup_ns or unset it"
  ns_from=deploy/k3s/env.example
  [[ -f "$ENV_FILE" ]] && grep -qE '^[[:space:]]*(export[[:space:]]+)?OPENTSX_NS=' "$ENV_FILE" && ns_from="${ENV_FILE#"$ROOT"/}"
  [[ "$backup_ns" == "$OPENTSX_NS" ]] \
    || die "namespace: the backup is of $backup_ns, $ns_from names $OPENTSX_NS; restore env alone first or set OPENTSX_NS to $backup_ns"
fi

if ! $YES; then
  echo "This REPLACES the content of: ${PARTS[*]:-(no volumes)}$($RESTORE_ENV && echo " and deploy/k3s/.env") in namespace $OPENTSX_NS."
  read -r -p "Continue? [y/N] " a || [[ -n "${a:-}" ]] \
    || die "71-restore.sh: no answer on standard input; pass --yes to run without the question"
  [[ "$a" =~ ^[yY] ]] || die "71-restore.sh: aborted at the question, nothing was changed; answer y to restore, or pass --yes"
fi

PVCS=()
if (( ${#PARTS[@]} )); then
  ns_hint="run 20-configure.sh and 30-deploy.sh first"
  pvc_hint="run 30-deploy.sh first (opentsx-iceberg-catalog needs LAKE=true, opentsx-minio LAKE=true and LAKE_MINIO=true)"
  if $RESTORE_ENV; then
    ns_hint="nothing was restored; run 71-restore.sh $DIR --only env, then 20-configure.sh, 10-build.sh, 11-import-images.sh and 30-deploy.sh, then 71-restore.sh $DIR --only $(IFS=,; echo "${PARTS[*]}")"
    pvc_hint="$ns_hint"
  fi
  require_namespace "$ns_hint"
  for part in "${PARTS[@]}"; do
    p="${PVC[$part]}"
    found="$(kns get pvc "$p" --ignore-not-found -o name)" \
      || die "PVC $p: cannot read it; check the cluster with ${KUBECTL} -n $OPENTSX_NS get pvc $p"
    [[ -n "$found" ]] || die "PVC $p: not found in namespace $OPENTSX_NS; $pvc_hint"
    PVCS+=("$p")
  done
fi

REPLACE_ENV=false
if $RESTORE_ENV; then
  if [[ -f "$ENV_FILE" ]] && cmp -s "$ENV_FILE" "$DIR/env"; then
    if [[ -e "$ENV_FILE.before-restore" || -L "$ENV_FILE.before-restore" ]]; then
      ok "$ENV_FILE: already the backup's env; $ENV_FILE.before-restore left as it is"
    else
      ok "$ENV_FILE: already the backup's env; left as it is"
    fi
  else
    if [[ -f "$ENV_FILE" ]]; then
      [[ ! -e "$ENV_FILE.before-restore" ]] || cmp -s "$ENV_FILE" "$ENV_FILE.before-restore" \
        || die "$ENV_FILE.before-restore: holds the .env from before an earlier restore; leave env out of --only, or move that file away first"
      cp -p "$ENV_FILE" "$ENV_FILE.before-restore" \
        || die "$ENV_FILE.before-restore: cannot keep the current .env there; see the error above, nothing was restored"
      warn "previous .env kept as $ENV_FILE.before-restore"
    fi
    REPLACE_ENV=true
  fi
fi
# .env goes last: a failed volume step leaves the configuration as it was
replace_env() {
  $REPLACE_ENV || return 0
  local after="" target="$ENV_FILE"
  (( ${#PVCS[@]} )) && after=" after the PVCs ${PVCS[*]} were restored"
  local msg="$ENV_FILE: cannot replace it with $DIR/env$after; see the error above, then run 71-restore.sh $DIR --only env"
  # a copy renamed over the file: a failed copy leaves .env as it was, so the rerun passes the kept-copy check; a .env
  # that is a link stays one
  if [[ -L "$ENV_FILE" ]]; then
    target="$(readlink -f -- "$ENV_FILE")" \
      || die "$ENV_FILE: cannot replace it with $DIR/env$after; it links to $(readlink -- "$ENV_FILE"), which cannot be resolved (a directory on its path is missing or is a file, or the links form a loop); fix the link, then run 71-restore.sh $DIR --only env"
  fi
  { (umask 077; cp "$DIR/env" "$target.restoring") && chmod 600 "$target.restoring" \
    && mv -f "$target.restoring" "$target"; } \
    || { rm -f -- "$target.restoring" || true; die "$msg"; }
  ok "deploy/k3s/.env: restored; run 20-configure.sh (re-renders the overlay) and 30-deploy.sh"
}
if (( ! ${#PARTS[@]} )); then replace_env; exit 0; fi

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
  kns exec "$HELPER" -- sh -c "find '/backup/$p' -mindepth 1 -delete" \
    || die "PVC $p: emptying it failed; see the error above, then run 71-restore.sh again"
  kns exec -i "$HELPER" -- tar xzf - --numeric-owner -C "/backup/$p" < "$DIR/$part.tar.gz" \
    || die "PVC $p: unpacking $part.tar.gz failed; see the error above, then run 71-restore.sh again"
  ok "$part.tar.gz -> PVC $p"
done
replace_env
info "restore done (writers are scaled back now); check with deploy/k3s/50-status.sh"
