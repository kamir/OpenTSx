# shellcheck shell=bash
# shellcheck disable=SC2034  # ROOT, TOOLS_IMAGE, STUDIO_IMAGE, SAAS_*_IMAGE, colours are used by the sourcing scripts
# Shared helpers for the deploy/k3s scripts. Sourced, not executed.

K3S_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$K3S_DIR/../.." && pwd)"
# OPENTSX_ENV_FILE / OPENTSX_OVERLAY: the env file and the overlay the scripts use instead of deploy/k3s/.env and
# overlays/local. 60-probe.sh --dry-run renders into a throw-away overlay with them, and the tests in deploy/k3s/test
# keep deploy/k3s/.env out of their runs. The overlay must sit two levels below deploy/k3s, like overlays/local,
# because it refers to ../../base. The scripts change the working directory only in subshells, so a relative
# OPENTSX_ENV_FILE stays valid and the messages show it as given.
ENV_FILE="${OPENTSX_ENV_FILE:-$K3S_DIR/.env}"
OVERLAY="${OPENTSX_OVERLAY:-$K3S_DIR/overlays/local}"

if [[ -t 1 ]]; then C_RED=$'\e[31m'; C_GRN=$'\e[32m'; C_YEL=$'\e[33m'; C_BLD=$'\e[1m'; C_OFF=$'\e[0m'
else C_RED=""; C_GRN=""; C_YEL=""; C_BLD=""; C_OFF=""; fi

info() { echo "${C_BLD}==>${C_OFF} $*"; }
ok()   { echo "  ${C_GRN}ok${C_OFF}    $*"; }
warn() { echo "  ${C_YEL}warn${C_OFF}  $*" >&2; }
fail() { echo "  ${C_RED}FAIL${C_OFF}  $*" >&2; }
die()  { echo "${C_RED}error:${C_OFF} $*" >&2; exit 1; }
usage_error() { echo "${C_RED}error:${C_OFF} $*" >&2; exit 2; }

# Prints the comment header of the calling script (lines 2.. up to the first non-comment line).
usage() { sed -n '2,/^[^#]/{/^#/p}' "$0" | grep -v '^# shellcheck' | sed 's/^# \{0,1\}//'; exit "${1:-0}"; }

have() { command -v "$1" >/dev/null 2>&1; }

# Configuration keys known to the scripts (see env.example).
CONFIG_KEYS=(OPENTSX_NS KUBECTL STORAGE_CLASS KAFSCALE_BOOTSTRAP KAFSCALE_NETWORK OPENTSX_DOMAIN OPENTSX_PARTITIONS
  OPENTSX_IMAGE_REPO OPENTSX_TAG OPENTSX_PLATFORM IMAGE_METHOD LAKE LAKE_MINIO S3_ENDPOINT S3_BUCKET S3_REGION
  S3_ACCESS_KEY S3_SECRET_KEY OM_URL OPENMETADATA_JWT INGRESS RETRIEVAL_SOURCE RETRIEVAL_DAG_RUNS RETRIEVAL_DAG_RUN_TOKEN VECTOR_PROCESSOR
  DWD_FORWARD DWD_STATIONS DWD_SCHEDULE DWD_MODE MODBUS_SIM MODBUS_HOST MODBUS_PORT MODBUS_MAP MONITORING
  SAAS SAAS_ADMIN_EMAIL SAAS_DB_PASSWORD SAAS_SECRET_KEY SAAS_ADMIN_PASSWORD SAAS_DAG_RUN_ROLE SAAS_TEST_LOGIN EVENT_BRIDGE
  EVENT_BRIDGE_ALLOW_ORIGIN OPENTSX_WINDPARK_SCENARIO OPENTSX_STATS_WINDOWS OPENTSX_STATS_ALLOWED_LATENESS
  OPENTSX_ICEBERG_NAMESPACE DWD_FIXTURE)

# Refuses a line of the .env file that is not empty, a comment or KEY=VALUE with a key of CONFIG_KEYS: load_env
# sources the file, and 20-configure.sh rewrites it from CONFIG_KEYS, which would drop a misspelled key.
check_env_file() {
  local file="$1" shown="${1#"$ROOT"/}" line n=0
  while IFS= read -r line || [[ -n "$line" ]]; do
    n=$((n + 1))
    [[ "$line" =~ ^[[:space:]]*(#.*)?$ ]] && continue
    [[ "$line" =~ ^[[:space:]]*(export[[:space:]]+)?([A-Za-z_][A-Za-z0-9_]*)= ]] \
      || usage_error "$shown:$n: not a KEY=VALUE line; write KEY=VALUE, a comment or an empty line"
    [[ " ${CONFIG_KEYS[*]} " == *" ${BASH_REMATCH[2]} "* ]] \
      || usage_error "$shown:$n: unknown key ${BASH_REMATCH[2]}; use a key of deploy/k3s/env.example"
  done < "$file"
}

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
    check_env_file "$ENV_FILE"
    # source looks a name without a slash up on PATH first; the checks and writes use the file in the working directory
    local src="$ENV_FILE"
    [[ "$src" == */* ]] || src="./$src"
    # shellcheck disable=SC1090
    source "$src"
  fi
  for k in "${!from_env[@]}"; do printf -v "$k" '%s' "${from_env[$k]}"; done
  TOOLS_IMAGE="$OPENTSX_IMAGE_REPO/tools:$OPENTSX_TAG"
  STUDIO_IMAGE="$OPENTSX_IMAGE_REPO/studio:$OPENTSX_TAG"
  SAAS_FRONTEND_IMAGE="$OPENTSX_IMAGE_REPO/saas-frontend:$OPENTSX_TAG"
  SAAS_BACKEND_IMAGE="$OPENTSX_IMAGE_REPO/saas-backend:$OPENTSX_TAG"
}

# kubectl as configured (KUBECTL may be "sudo k3s kubectl").
kc() {
  local -a k
  read -r -a k <<< "${KUBECTL:-kubectl}"
  "${k[@]}" "$@"
}
kns() { kc -n "$OPENTSX_NS" "$@"; }
# kns_background ARG...: kns ARG... in the background with $! the pid of kubectl itself, so that killing $! ends it
# (a backgrounded kns is a subshell, and killing that leaves kubectl running).
kns_background() {
  local -a k
  read -r -a k <<< "${KUBECTL:-kubectl}"
  "${k[@]}" -n "$OPENTSX_NS" "$@" &
}

# sudo only when not root (k3s ctr needs root).
as_root() { if [[ $EUID -eq 0 ]]; then "$@"; else sudo "$@"; fi; }

# tcp_check host:port [seconds]: a plain TCP connect from this machine.
tcp_check() {
  local hp="$1" t="${2:-5}" host port
  host="${hp%:*}"; port="${hp##*:}"
  [[ -n "$host" && "$port" =~ ^[0-9]+$ ]] || return 2
  timeout "$t" bash -c "exec 3<>/dev/tcp/$host/$port" 2>/dev/null
}

# namespace_exists: true when the namespace OPENTSX_NS exists, false when it does not; dies when it cannot be read.
# kubectl's error stays on stderr, so an unreachable cluster is not taken for a missing namespace.
namespace_exists() {
  local found
  found="$(kc get namespace "$OPENTSX_NS" --ignore-not-found -o name)" \
    || die "namespace $OPENTSX_NS: cannot read it; check the cluster with ${KUBECTL} get namespace $OPENTSX_NS"
  [[ -n "$found" ]]
}

# require_namespace HINT: dies when the namespace OPENTSX_NS does not exist, with HINT as what to do, or cannot be read.
require_namespace() { namespace_exists || die "namespace $OPENTSX_NS: not found; $1"; }

require_overlay() {
  [[ -f "$OVERLAY/kustomization.yaml" ]] || die "no rendered overlay in ${OVERLAY#"$ROOT"/}; run deploy/k3s/20-configure.sh first"
}

is_true() { [[ "${1,,}" =~ ^(true|yes|1|on)$ ]]; }

# helper_pod NAME ro|rw PVC...: a short-lived pod (tools image) with the PVCs mounted under /backup/<pvc>, for
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
    || die "pod $name: not ready after 180 s; check that the image $TOOLS_IMAGE is imported and that the PVCs are on one node, see ${KUBECTL} -n $OPENTSX_NS describe pod $name"
}

# stop_writers, start_writers: scale opentsx-stats, opentsx-iceberg-rest and opentsx-minio to 0 (remembering their
# replicas) and back, so that 70-backup.sh and 71-restore.sh see files nobody writes to. A Deployment that is not
# there is skipped; any other failure, and pods that have not ended 150 s after scaling, stop the script.
declare -A STOPPED_WRITERS=()
stop_writers() {
  local d r left running='--field-selector=status.phase!=Succeeded,status.phase!=Failed'
  for d in opentsx-stats opentsx-iceberg-rest opentsx-minio; do
    r="$(kns get deployment "$d" --ignore-not-found -o 'jsonpath={.spec.replicas}')" \
      || die "$d: cannot read the Deployment; check the cluster with ${KUBECTL} -n $OPENTSX_NS get deployment $d"
    [[ -n "$r" && "$r" != 0 ]] || continue
    [[ "$r" =~ ^[0-9]+$ ]] || die "$d: replicas \"$r\" is not a number; check ${KUBECTL} -n $OPENTSX_NS get deployment $d"
    STOPPED_WRITERS[$d]="$r"
    kns scale "deployment/$d" --replicas=0 >/dev/null
    # kubectl wait also fails when no pod matches, so the pods left decide; a ReplicaSet leaves its ended pods
    # (Evicted, Completed) to the pod GC, so they do not count
    if ! kns wait --for=delete pod -l "app.kubernetes.io/name=$d" "$running" --timeout=150s >/dev/null 2>&1; then
      left="$(kns get pod -l "app.kubernetes.io/name=$d" "$running" -o name)" \
        || die "$d: cannot list its pods; check the cluster with ${KUBECTL} -n $OPENTSX_NS get pod -l app.kubernetes.io/name=$d"
      [[ -z "$left" ]] \
        || die "$d: pods still run 150 s after scaling to 0 (${left//$'\n'/, }); stop them, then run $(basename "$0") again"
    fi
    ok "$d scaled to 0 (was $r)"
  done
}
start_writers() {
  local d err
  for d in "${!STOPPED_WRITERS[@]}"; do
    if err="$(kns scale "deployment/$d" --replicas="${STOPPED_WRITERS[$d]}" 2>&1 >/dev/null)"; then ok "scaled $d back to ${STOPPED_WRITERS[$d]}"
    else warn "$d: not scaled back to ${STOPPED_WRITERS[$d]} (${err//$'\n'/ }); run ${KUBECTL} -n $OPENTSX_NS scale deployment/$d --replicas=${STOPPED_WRITERS[$d]}"; fi
  done
  STOPPED_WRITERS=()
}
