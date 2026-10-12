# shellcheck shell=bash
# shellcheck disable=SC2034  # ROOT, TOOLS_IMAGE, STUDIO_IMAGE, colours are used by the sourcing scripts
# Shared helpers for the deploy/k3s scripts. Sourced, not executed.

K3S_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$K3S_DIR/../.." && pwd)"
# OPENTSX_ENV_FILE / OPENTSX_OVERLAY: only for 60-probe.sh --dry-run (renders into a throw-away overlay; it must sit
# two levels below deploy/k3s, like overlays/local, because the overlay refers to ../../base)
ENV_FILE="${OPENTSX_ENV_FILE:-$K3S_DIR/.env}"
OVERLAY="${OPENTSX_OVERLAY:-$K3S_DIR/overlays/local}"

if [[ -t 1 ]]; then C_RED=$'\e[31m'; C_GRN=$'\e[32m'; C_YEL=$'\e[33m'; C_BLD=$'\e[1m'; C_OFF=$'\e[0m'
else C_RED=""; C_GRN=""; C_YEL=""; C_BLD=""; C_OFF=""; fi

info() { echo "${C_BLD}==>${C_OFF} $*"; }
ok()   { echo "  ${C_GRN}ok${C_OFF}    $*"; }
warn() { echo "  ${C_YEL}warn${C_OFF}  $*" >&2; }
fail() { echo "  ${C_RED}FAIL${C_OFF}  $*" >&2; }
die()  { echo "${C_RED}error:${C_OFF} $*" >&2; exit 1; }

# Prints the comment header of the calling script (lines 2.. up to the first non-comment line).
usage() { sed -n '2,/^[^#]/{/^#/p}' "$0" | grep -v '^# shellcheck' | sed 's/^# \{0,1\}//'; exit "${1:-0}"; }

have() { command -v "$1" >/dev/null 2>&1; }

# Configuration keys known to the scripts (see env.example).
CONFIG_KEYS=(OPENTSX_NS KUBECTL STORAGE_CLASS KAFSCALE_BOOTSTRAP KAFSCALE_NETWORK OPENTSX_DOMAIN OPENTSX_PARTITIONS
  OPENTSX_IMAGE_REPO OPENTSX_TAG OPENTSX_PLATFORM IMAGE_METHOD LAKE LAKE_MINIO S3_ENDPOINT S3_BUCKET S3_REGION
  S3_ACCESS_KEY S3_SECRET_KEY OM_URL OPENMETADATA_JWT INGRESS
  DWD_FORWARD DWD_STATIONS DWD_SCHEDULE DWD_MODE MODBUS_SIM MODBUS_HOST MODBUS_PORT MODBUS_MAP MONITORING)

# Loads env.example defaults, then .env. Variables already set in the environment win over both.
load_env() {
  local k
  declare -A from_env=()
  for k in "${CONFIG_KEYS[@]}"; do
    if [[ -n "${!k+x}" ]]; then from_env[$k]="${!k}"; fi
  done
  # shellcheck disable=SC1091
  source "$K3S_DIR/env.example"
  if [[ -f "$ENV_FILE" ]]; then
    # shellcheck disable=SC1090
    source "$ENV_FILE"
  fi
  for k in "${!from_env[@]}"; do printf -v "$k" '%s' "${from_env[$k]}"; done
  TOOLS_IMAGE="$OPENTSX_IMAGE_REPO/tools:$OPENTSX_TAG"
  STUDIO_IMAGE="$OPENTSX_IMAGE_REPO/studio:$OPENTSX_TAG"
}

# kubectl as configured (KUBECTL may be "sudo k3s kubectl").
kc() {
  local -a k
  read -r -a k <<< "${KUBECTL:-kubectl}"
  "${k[@]}" "$@"
}
kns() { kc -n "$OPENTSX_NS" "$@"; }

# sudo only when not root (k3s ctr needs root).
as_root() { if [[ $EUID -eq 0 ]]; then "$@"; else sudo "$@"; fi; }

# tcp_check host:port [seconds] - plain TCP connect from this machine.
tcp_check() {
  local hp="$1" t="${2:-5}" host port
  host="${hp%:*}"; port="${hp##*:}"
  [[ -n "$host" && "$port" =~ ^[0-9]+$ ]] || return 2
  timeout "$t" bash -c "exec 3<>/dev/tcp/$host/$port" 2>/dev/null
}

require_overlay() {
  [[ -f "$OVERLAY/kustomization.yaml" ]] || die "no rendered overlay - run deploy/k3s/20-configure.sh first"
}

is_true() { [[ "${1,,}" =~ ^(true|yes|1|on)$ ]]; }

# helper_pod NAME ro|rw PVC... - a short-lived pod (tools image) with the PVCs mounted under /backup/<pvc>, for
# 70-backup.sh / 71-restore.sh. Root without privileges: only CHOWN, DAC_OVERRIDE and FOWNER (to read and restore files
# of other users, e.g. MinIO's). On a multi-node cluster all PVCs must be on the same node.
helper_pod() {
  local name="$1" ro=true p; [[ "$2" == rw ]] && ro=false
  shift 2
  {
    cat <<EOF
apiVersion: v1
kind: Pod
metadata:
  name: $name
  namespace: $OPENTSX_NS
  labels:
    app.kubernetes.io/part-of: opentsx
    app.kubernetes.io/name: $name
spec:
  restartPolicy: Never
  terminationGracePeriodSeconds: 5
  containers:
    - name: helper
      image: $TOOLS_IMAGE
      imagePullPolicy: IfNotPresent
      command: [/bin/sh, -c, "sleep 3600"]
      securityContext:
        runAsUser: 0
        allowPrivilegeEscalation: false
        capabilities: {drop: [ALL], add: [CHOWN, DAC_OVERRIDE, FOWNER]}
      resources:
        requests: {cpu: 50m, memory: 64Mi}
        limits: {cpu: "1", memory: 256Mi}
      volumeMounts:
EOF
    for p in "$@"; do printf '        - {name: %s, mountPath: /backup/%s, readOnly: %s}\n' "$p" "$p" "$ro"; done
    echo "  volumes:"
    for p in "$@"; do printf '    - {name: %s, persistentVolumeClaim: {claimName: %s, readOnly: %s}}\n' "$p" "$p" "$ro"; done
  } | kc apply -f - >/dev/null
  kns wait --for=condition=Ready "pod/$name" --timeout=180s >/dev/null \
    || die "helper pod not ready (image $TOOLS_IMAGE imported? PVCs on different nodes?) - ${KUBECTL} -n $OPENTSX_NS describe pod $name"
}

# stop_writers / start_writers - scale opentsx-stats, opentsx-iceberg-rest and opentsx-minio to 0 (remembering their
# replicas) and back, so that 70-backup.sh / 71-restore.sh see files nobody writes to.
declare -A STOPPED_WRITERS=()
stop_writers() {
  local d r
  for d in opentsx-stats opentsx-iceberg-rest opentsx-minio; do
    r="$(kns get deployment "$d" -o 'jsonpath={.spec.replicas}' 2>/dev/null || true)"
    if ! [[ "$r" =~ ^[0-9]+$ ]] || (( r == 0 )); then continue; fi
    STOPPED_WRITERS[$d]="$r"
    kns scale "deployment/$d" --replicas=0 >/dev/null
    kns wait --for=delete pod -l "app.kubernetes.io/name=$d" --timeout=150s >/dev/null 2>&1 || true
    ok "$d scaled to 0 (was $r)"
  done
}
start_writers() {
  local d
  for d in "${!STOPPED_WRITERS[@]}"; do
    if kns scale "deployment/$d" --replicas="${STOPPED_WRITERS[$d]}" >/dev/null 2>&1; then ok "scaled $d back to ${STOPPED_WRITERS[$d]}"
    else warn "could not scale $d back to ${STOPPED_WRITERS[$d]} - do it by hand"; fi
  done
  STOPPED_WRITERS=()
}
