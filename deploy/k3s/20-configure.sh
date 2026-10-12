#!/usr/bin/env bash
# Writes the local configuration and renders the kustomize overlay for this installation.
#
#   deploy/k3s/20-configure.sh [KEY=VALUE ...] [--print]
#
# Example:
#   deploy/k3s/20-configure.sh KAFSCALE_BOOTSTRAP=192.168.1.50:9092
#   deploy/k3s/20-configure.sh KAFSCALE_BOOTSTRAP=localhost:9092 KAFSCALE_NETWORK=hostnetwork
#   deploy/k3s/20-configure.sh LAKE=true LAKE_MINIO=true          # Iceberg REST + MinIO in the cluster
#
# Precedence: KEY=VALUE arguments > environment variables > existing deploy/k3s/.env > env.example defaults.
# Keys: see deploy/k3s/env.example. Idempotent: run it again to change a value.
#
# Writes (all git-ignored, secrets with mode 600):
#   deploy/k3s/.env                          the effective configuration (including generated MinIO credentials)
#   deploy/k3s/overlays/local/               kustomization (namespace, config, Secret, images, components)
#   deploy/k3s/overlays/local/jobs/          kustomization for the demo Jobs (used by 40-demo.sh)
# KAFSCALE_NETWORK:
#   direct       pods use KAFSCALE_BOOTSTRAP as is (an IP/DNS name reachable from the pod network) - default
#   hostnetwork  KafScale-facing pods share the node's network (KafScale on the same host, advertising localhost)
#   service      an in-cluster name opentsx-kafscale.<ns>.svc for the bootstrap (selector-less Service +
#                EndpointSlice for an IP, ExternalName for a DNS name). Only the bootstrap goes through it: the
#                broker's ADVERTISED address must still be reachable from the pods.
# --print   show the rendered manifests (kubectl kustomize) after writing.
set -euo pipefail
# shellcheck source=deploy/k3s/lib.sh
source "$(dirname "$0")/lib.sh"

PRINT=false
declare -A SET=()
for arg in "$@"; do
  case "$arg" in
    -h|--help) usage 0 ;;
    --print) PRINT=true ;;
    *=*)
      key="${arg%%=*}"
      [[ " ${CONFIG_KEYS[*]} " == *" $key "* ]] || die "unknown key $key (see deploy/k3s/env.example)"
      SET[$key]="${arg#*=}" ;;
    *) die "expected KEY=VALUE, got '$arg' (see --help)" ;;
  esac
done
load_env
for key in "${!SET[@]}"; do printf -v "$key" '%s' "${SET[$key]}"; done

# ---- validate -----------------------------------------------------------------------------------------------------
[[ "$OPENTSX_NS" =~ ^[a-z0-9]([-a-z0-9]*[a-z0-9])?$ ]] || die "OPENTSX_NS=$OPENTSX_NS is not a valid namespace name"
[[ "$OPENTSX_DOMAIN" =~ ^[a-z0-9][a-z0-9_-]*$ ]] || die "OPENTSX_DOMAIN=$OPENTSX_DOMAIN: use lower-case letters, digits, - or _"
[[ "$OPENTSX_PARTITIONS" =~ ^[1-9][0-9]*$ ]] || die "OPENTSX_PARTITIONS must be a positive number"
[[ "$KAFSCALE_BOOTSTRAP" =~ ^[^,:[:space:]]+:[0-9]+(,[^,:[:space:]]+:[0-9]+)*$ ]] \
  || die "KAFSCALE_BOOTSTRAP=$KAFSCALE_BOOTSTRAP: expected host:port[,host:port...]"
case "$KAFSCALE_NETWORK" in direct|hostnetwork|service) ;; *) die "KAFSCALE_NETWORK must be direct, hostnetwork or service" ;; esac
if [[ "$KAFSCALE_BOOTSTRAP" =~ (^|,)(localhost|127\.[0-9.]+): && "$KAFSCALE_NETWORK" != "hostnetwork" ]]; then
  die "KAFSCALE_BOOTSTRAP=$KAFSCALE_BOOTSTRAP is loopback, which pods cannot reach. Use the host's LAN IP or KAFSCALE_NETWORK=hostnetwork"
fi
if [[ "$KAFSCALE_BOOTSTRAP" == "192.168.1.50:9092" ]]; then
  warn "KAFSCALE_BOOTSTRAP is still the example value 192.168.1.50:9092"
fi
for b in LAKE LAKE_MINIO INGRESS; do
  [[ "${!b,,}" =~ ^(true|false|yes|no|1|0|on|off)$ ]] || die "$b must be true or false"
done

# ---- derived values -----------------------------------------------------------------------------------------------
POD_BOOTSTRAP="$KAFSCALE_BOOTSTRAP"
if [[ "$KAFSCALE_NETWORK" == "service" ]]; then
  [[ "$KAFSCALE_BOOTSTRAP" != *,* ]] || die "KAFSCALE_NETWORK=service supports one bootstrap address; use direct for a list"
  KS_HOST="${KAFSCALE_BOOTSTRAP%:*}"; KS_PORT="${KAFSCALE_BOOTSTRAP##*:}"
  POD_BOOTSTRAP="opentsx-kafscale.$OPENTSX_NS.svc.cluster.local:$KS_PORT"
fi
if is_true "$LAKE"; then
  if is_true "$LAKE_MINIO"; then
    S3_ENDPOINT="${S3_ENDPOINT:-http://opentsx-minio:9000}"
    gen() { LC_ALL=C tr -dc 'A-Za-z0-9' < /dev/urandom | head -c "$1" || true; }
    [[ -n "$S3_ACCESS_KEY" ]] || S3_ACCESS_KEY="opentsx-$(gen 8)"
    [[ -n "$S3_SECRET_KEY" ]] || S3_SECRET_KEY="$(gen 32)"
  else
    [[ -n "$S3_ENDPOINT" ]] || die "LAKE=true with LAKE_MINIO=false needs S3_ENDPOINT (your S3 / MinIO URL)"
    [[ -n "$S3_ACCESS_KEY" && -n "$S3_SECRET_KEY" ]] || die "LAKE=true with LAKE_MINIO=false needs S3_ACCESS_KEY and S3_SECRET_KEY"
  fi
  ICEBERG_PROPS="type=rest,uri=http://opentsx-iceberg-rest:8181,warehouse=s3://$S3_BUCKET/,io-impl=org.apache.iceberg.aws.s3.S3FileIO,s3.endpoint=$S3_ENDPOINT,s3.path-style-access=true,client.region=$S3_REGION"
else
  ICEBERG_PROPS=""
fi

# ---- .env ----------------------------------------------------------------------------------------------------------
umask 077
{
  echo "# written by deploy/k3s/20-configure.sh on $(date -u +%Y-%m-%dT%H:%M:%SZ) - contains secrets, never commit"
  for key in "${CONFIG_KEYS[@]}"; do printf '%s=%q\n' "$key" "${!key}"; done
} > "$ENV_FILE"
chmod 600 "$ENV_FILE"

# ---- overlay -------------------------------------------------------------------------------------------------------
mkdir -p "$OVERLAY/jobs"
cat > "$OVERLAY/namespace.yaml" <<EOF
apiVersion: v1
kind: Namespace
metadata:
  name: $OPENTSX_NS
  labels:
    app.kubernetes.io/part-of: opentsx
EOF

cat > "$OVERLAY/config.env" <<EOF
KAFSCALE_BOOTSTRAP=$POD_BOOTSTRAP
OPENTSX_DOMAIN=$OPENTSX_DOMAIN
OPENTSX_PARTITIONS=$OPENTSX_PARTITIONS
ICEBERG_PROPS=$ICEBERG_PROPS
OM_URL=$OM_URL
S3_ENDPOINT=${S3_ENDPOINT:-http://opentsx-minio:9000}
S3_BUCKET=$S3_BUCKET
S3_WAREHOUSE=s3://$S3_BUCKET/
S3_REGION=$S3_REGION
EOF

{
  [[ -n "$S3_ACCESS_KEY" ]] && printf 'S3_ACCESS_KEY=%s\nAWS_ACCESS_KEY_ID=%s\n' "$S3_ACCESS_KEY" "$S3_ACCESS_KEY"
  [[ -n "$S3_SECRET_KEY" ]] && printf 'S3_SECRET_KEY=%s\nAWS_SECRET_ACCESS_KEY=%s\n' "$S3_SECRET_KEY" "$S3_SECRET_KEY"
  [[ -n "$S3_ACCESS_KEY" ]] && printf 'AWS_REGION=%s\n' "$S3_REGION"
  [[ -n "$OPENMETADATA_JWT" ]] && printf 'OPENMETADATA_JWT=%s\n' "$OPENMETADATA_JWT"
  true
} > "$OVERLAY/secrets.env"
chmod 600 "$OVERLAY/secrets.env"

resources=("namespace.yaml" "../../base")
components=()
jobs_components=()
if [[ "$KAFSCALE_NETWORK" == "service" ]]; then
  resources+=("kafscale-service.yaml")
  if [[ "$KS_HOST" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    cat > "$OVERLAY/kafscale-service.yaml" <<EOF
# KafScale outside the cluster, by IP: selector-less Service + EndpointSlice (in-cluster name for the bootstrap).
apiVersion: v1
kind: Service
metadata:
  name: opentsx-kafscale
spec:
  ports:
    - name: kafka
      port: $KS_PORT
      targetPort: $KS_PORT
---
apiVersion: discovery.k8s.io/v1
kind: EndpointSlice
metadata:
  name: opentsx-kafscale-1
  labels:
    kubernetes.io/service-name: opentsx-kafscale
addressType: IPv4
ports:
  - name: kafka
    port: $KS_PORT
    protocol: TCP
endpoints:
  - addresses: ["$KS_HOST"]
EOF
  else
    cat > "$OVERLAY/kafscale-service.yaml" <<EOF
# KafScale outside the cluster, by DNS name: ExternalName Service (a CNAME; the port stays $KS_PORT).
apiVersion: v1
kind: Service
metadata:
  name: opentsx-kafscale
spec:
  type: ExternalName
  externalName: $KS_HOST
EOF
  fi
else
  rm -f "$OVERLAY/kafscale-service.yaml"
fi
if [[ "$KAFSCALE_NETWORK" == "hostnetwork" ]]; then
  components+=("../../components/hostnetwork"); jobs_components+=("../../../components/hostnetwork")
fi
if is_true "$LAKE"; then
  components+=("../../components/lake"); jobs_components+=("../../../components/lake-jobs")
  is_true "$LAKE_MINIO" && components+=("../../components/lake-minio")
fi
is_true "$INGRESS" && components+=("../../components/ingress")

yaml_list() { local i; for i in "$@"; do echo "  - $i"; done; }
storage_patch() {
  [[ -n "$STORAGE_CLASS" ]] || return 0
  cat <<EOF
patches:
  - target:
      kind: PersistentVolumeClaim
    patch: |-
      - op: add
        path: /spec/storageClassName
        value: $STORAGE_CLASS
EOF
}

{
  echo "# rendered by deploy/k3s/20-configure.sh - do not edit, re-run the script"
  echo "apiVersion: kustomize.config.k8s.io/v1beta1"
  echo "kind: Kustomization"
  echo "namespace: $OPENTSX_NS"
  printf 'labels:\n  - pairs:\n      app.kubernetes.io/part-of: opentsx\n    includeTemplates: true\n'
  echo "resources:"; yaml_list "${resources[@]}"
  if (( ${#components[@]} )); then echo "components:"; yaml_list "${components[@]}"; fi
  cat <<EOF
configMapGenerator:
  - name: opentsx-config
    behavior: merge
    envs: [config.env]
secretGenerator:
  - name: opentsx-secrets
    envs: [secrets.env]
generatorOptions:
  disableNameSuffixHash: true
images:
  - name: opentsx/tools
    newName: $OPENTSX_IMAGE_REPO/tools
    newTag: "$OPENTSX_TAG"
  - name: opentsx/studio
    newName: $OPENTSX_IMAGE_REPO/studio
    newTag: "$OPENTSX_TAG"
EOF
  storage_patch
} > "$OVERLAY/kustomization.yaml"

{
  echo "# rendered by deploy/k3s/20-configure.sh - demo Jobs, used by 40-demo.sh"
  echo "apiVersion: kustomize.config.k8s.io/v1beta1"
  echo "kind: Kustomization"
  echo "namespace: $OPENTSX_NS"
  echo "resources:"; yaml_list "../../../jobs"
  if (( ${#jobs_components[@]} )); then echo "components:"; yaml_list "${jobs_components[@]}"; fi
  cat <<EOF
images:
  - name: opentsx/tools
    newName: $OPENTSX_IMAGE_REPO/tools
    newTag: "$OPENTSX_TAG"
EOF
} > "$OVERLAY/jobs/kustomization.yaml"

# ---- check the rendering -------------------------------------------------------------------------------------------
info "configuration written: $ENV_FILE, $OVERLAY/"
echo "  namespace $OPENTSX_NS | KafScale $KAFSCALE_BOOTSTRAP ($KAFSCALE_NETWORK; pods use $POD_BOOTSTRAP) | domain $OPENTSX_DOMAIN"
echo "  images $OPENTSX_IMAGE_REPO/{tools,studio}:$OPENTSX_TAG | lake $LAKE (minio $LAKE_MINIO) | ingress $INGRESS | OpenMetadata ${OM_URL:-off}"
if kc version --client >/dev/null 2>&1; then
  n="$(kc kustomize "$OVERLAY" | grep -c '^kind:')" || die "kubectl kustomize $OVERLAY failed"
  m="$(kc kustomize "$OVERLAY/jobs" | grep -c '^kind:')" || die "kubectl kustomize $OVERLAY/jobs failed"
  ok "renders: $n objects, $m demo Jobs"
  $PRINT && kc kustomize "$OVERLAY"
else
  warn "kubectl not available - rendering not checked"
fi
echo "next: deploy/k3s/30-deploy.sh   (already deployed? 30-deploy.sh --restart applies a changed configuration)"
