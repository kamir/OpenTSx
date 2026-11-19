#!/usr/bin/env bash
# Writes the local configuration and renders the kustomize overlay for this installation.
#
#   deploy/k3s/20-configure.sh [KEY=VALUE ...] [--print]
#
# Example:
#   deploy/k3s/20-configure.sh KAFSCALE_BOOTSTRAP=192.168.1.50:9092
#   deploy/k3s/20-configure.sh KAFSCALE_BOOTSTRAP=localhost:9092 KAFSCALE_NETWORK=hostnetwork
#   deploy/k3s/20-configure.sh LAKE=true LAKE_MINIO=true          # Iceberg REST + MinIO in the cluster
#   deploy/k3s/20-configure.sh MODBUS_SIM=true DWD_FORWARD=true MONITORING=true   # simulator, daily MOSMIX, checks
#
# Precedence: KEY=VALUE arguments > environment variables > existing deploy/k3s/.env > env.example defaults.
# Keys: see deploy/k3s/env.example. Idempotent: run it again to change a value.
# Secret keys are refused as arguments, because the shell history and the process list keep arguments:
#   S3_ACCESS_KEY S3_SECRET_KEY OPENMETADATA_JWT RETRIEVAL_DAG_RUN_TOKEN SAAS_DB_PASSWORD SAAS_SECRET_KEY SAAS_ADMIN_PASSWORD
# Export them (read -rs OPENMETADATA_JWT && export OPENMETADATA_JWT) or write them into deploy/k3s/.env.
#
# Writes (all git-ignored, secrets with mode 600):
#   deploy/k3s/.env                          the effective configuration (including generated MinIO credentials)
#   deploy/k3s/overlays/local/               kustomization (namespace, config, Secret, images, components)
#   deploy/k3s/overlays/local/jobs/          kustomization for the demo Jobs (used by 40-demo.sh)
# KAFSCALE_NETWORK:
#   direct       pods use KAFSCALE_BOOTSTRAP as is (an IP/DNS name reachable from the pod network); the default
#   hostnetwork  KafScale-facing pods share the node's network (KafScale on the same host, advertising localhost)
#   service      an in-cluster name opentsx-kafscale.<ns>.svc for the bootstrap (selector-less Service +
#                EndpointSlice for an IP, ExternalName for a DNS name). Only the bootstrap goes through it: the
#                broker's ADVERTISED address must still be reachable from the pods.
# --print   show the rendered manifests (kubectl kustomize) after writing, without the Secrets.
#
# Exit codes:
#   0  configuration written
#   1  a value failed its check, or writing or rendering failed
#   2  usage error: an unknown key, an argument that is not KEY=VALUE or an option, a secret key as an argument, or
#      a line of deploy/k3s/.env that is not a comment or KEY=VALUE with a known key
set -euo pipefail
# shellcheck source=deploy/k3s/lib.sh
source "$(dirname "$0")/lib.sh"

SECRET_KEYS=(S3_ACCESS_KEY S3_SECRET_KEY OPENMETADATA_JWT RETRIEVAL_DAG_RUN_TOKEN SAAS_DB_PASSWORD SAAS_SECRET_KEY
  SAAS_ADMIN_PASSWORD)
PRINT=false
declare -A SET=()
for arg in "$@"; do
  case "$arg" in
    -h|--help) usage 0 ;;
    --print) PRINT=true ;;
    *=*)
      key="${arg%%=*}"
      [[ " ${CONFIG_KEYS[*]} " == *" $key "* ]] || usage_error "$key: unknown key; use a key of deploy/k3s/env.example"
      [[ " ${SECRET_KEYS[*]} " != *" $key "* ]] \
        || usage_error "$key: secret keys are not accepted on the command line; export $key or put it in deploy/k3s/.env (mode 600)"
      SET[$key]="${arg#*=}" ;;
    *) usage_error "$arg: unexpected argument; expected 20-configure.sh [KEY=VALUE ...] [--print]" ;;
  esac
done
load_env
for key in "${!SET[@]}"; do printf -v "$key" '%s' "${SET[$key]}"; done

# every value ends up in a YAML or env file line
for key in "${CONFIG_KEYS[@]}"; do
  [[ "${!key}" != *[[:cntrl:]]* ]] || die "$key: the value holds a control character such as a line break; write it on one line"
done
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
for b in LAKE LAKE_MINIO INGRESS DWD_FORWARD MODBUS_SIM MONITORING SAAS SAAS_TEST_LOGIN RETRIEVAL_DAG_RUNS VECTOR_PROCESSOR EVENT_BRIDGE; do
  [[ "${!b,,}" =~ ^(true|false|yes|no|1|0|on|off)$ ]] || die "$b must be true or false"
done
[[ "$DWD_MODE" =~ ^(online|offline)$ ]] || die "DWD_MODE must be online or offline"
[[ "$RETRIEVAL_SOURCE" =~ ^(demo|kafscale)$ ]] || die "RETRIEVAL_SOURCE must be demo or kafscale"
if is_true "$LAKE" && [[ "$RETRIEVAL_SOURCE" == kafscale ]]; then
  die "LAKE=true lets the retrieval server read Iceberg; set RETRIEVAL_SOURCE=demo or LAKE=false"
fi
if is_true "$RETRIEVAL_DAG_RUNS" && [[ "$RETRIEVAL_SOURCE" != kafscale ]]; then
  die "RETRIEVAL_DAG_RUNS=true needs RETRIEVAL_SOURCE=kafscale (the runs read and write KafScale)"
fi
[[ "$DWD_STATIONS" =~ ^[A-Za-z0-9]+(,[A-Za-z0-9]+)*$ ]] || die "DWD_STATIONS=$DWD_STATIONS: expected MOSMIX ids like 10578,10579"
read -r -a cron_fields <<< "$DWD_SCHEDULE"
(( ${#cron_fields[@]} == 5 )) || die "DWD_SCHEDULE='$DWD_SCHEDULE': expected 5 cron fields, e.g. '17 5 * * *'"
cron_chars='^[0-9A-Za-z*/,? -]+$'
[[ "$DWD_SCHEDULE" =~ $cron_chars ]] \
  || die "DWD_SCHEDULE: \"$DWD_SCHEDULE\" holds a character that is not part of a cron field; expected digits, letters and * / , ? -, such as 17 5 * * *"
if ! [[ "$MODBUS_PORT" =~ ^[0-9]+$ ]] || (( MODBUS_PORT < 1 || MODBUS_PORT > 65535 )); then die "MODBUS_PORT must be a TCP port"; fi
[[ -n "$MODBUS_HOST" && -n "$MODBUS_MAP" ]] || die "MODBUS_HOST and MODBUS_MAP must not be empty"
# the duration form of the configuration registry (python-package/opentsx/conf/conf.py, _ISO and _UNIT)
is_duration() {
  local n='(0|[1-9][0-9]{0,19})' iso unit
  iso="^P(${n}D)?(T(${n}H)?(${n}M)?(${n}([.][0-9]{1,3})?S)?)?\$"; unit="^${n}(ms|s|min|m|h|d)\$"
  [[ "$1" =~ $iso && "$1" =~ [0-9] && "$1" != *T ]] || [[ "$1" =~ $unit ]]
}
duration_expected='expected ISO-8601 such as PT10M or a whole number with the unit ms, s, m, min, h or d such as 10m'
[[ -n "$OPENTSX_STATS_WINDOWS" ]] || die "OPENTSX_STATS_WINDOWS: empty value; set durations such as PT10M,PT1H"
rest="$OPENTSX_STATS_WINDOWS,"
while [[ -n "$rest" ]]; do
  w="${rest%%,*}"; rest="${rest#*,}"
  is_duration "$w" || die "OPENTSX_STATS_WINDOWS: item \"$w\" is not a duration; $duration_expected"
done
is_duration "$OPENTSX_STATS_ALLOWED_LATENESS" \
  || die "OPENTSX_STATS_ALLOWED_LATENESS: \"$OPENTSX_STATS_ALLOWED_LATENESS\" is not a duration; $duration_expected"
image_path='^/[A-Za-z0-9._/-]+$'
[[ "$OPENTSX_WINDPARK_SCENARIO" =~ $image_path ]] \
  || die "OPENTSX_WINDPARK_SCENARIO: \"$OPENTSX_WINDPARK_SCENARIO\" is not an absolute path; expected a file in the image or on a mounted volume, such as /opt/opentsx/share/scenarios/ostsee-demo.yaml"
[[ "$DWD_FIXTURE" =~ $image_path ]] \
  || die "DWD_FIXTURE: \"$DWD_FIXTURE\" is not an absolute path; expected a file in the image or on a mounted volume, such as /opt/opentsx/share/dwd/MOSMIX_L_2026101209_10578.kml"
[[ "$OPENTSX_ICEBERG_NAMESPACE" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]] \
  || die "OPENTSX_ICEBERG_NAMESPACE: \"$OPENTSX_ICEBERG_NAMESPACE\" is not an Iceberg namespace; expected letters, digits and _, starting with a letter or _, such as tsx"
[[ "$STORAGE_CLASS" =~ ^([a-z0-9]([-a-z0-9.]*[a-z0-9])?)?$ ]] \
  || die "STORAGE_CLASS: \"$STORAGE_CLASS\" is not a StorageClass name; expected lower-case letters, digits, - and ., or empty for the default class"
image_repo='^([A-Za-z0-9.-]+(:[0-9]+)?/)?[a-z0-9]+([._-]+[a-z0-9]+)*(/[a-z0-9]+([._-]+[a-z0-9]+)*)*$'
[[ "$OPENTSX_IMAGE_REPO" =~ $image_repo ]] \
  || die "OPENTSX_IMAGE_REPO: \"$OPENTSX_IMAGE_REPO\" is not an image repository; expected [host[:port]/]path in lower case, such as opentsx or registry.local:5000/opentsx"
[[ "$OPENTSX_TAG" =~ ^[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}$ ]] \
  || die "OPENTSX_TAG: \"$OPENTSX_TAG\" is not an image tag; expected up to 128 letters, digits, _, . and -, starting with a letter, digit or _"
host='([A-Za-z0-9.-]+|\[[0-9A-Fa-f:.]+\])(:[0-9]{1,5})?'
http_url="^https?://$host(/[^[:space:]\"'\\,]*)?\$"
[[ -z "$OM_URL" || "$OM_URL" =~ $http_url ]] \
  || die "OM_URL: \"$OM_URL\" is not an http(s) URL; expected http://<host>:<port>/api, or empty without OpenMetadata"
[[ -z "$S3_ENDPOINT" || "$S3_ENDPOINT" =~ $http_url ]] \
  || die "S3_ENDPOINT: \"$S3_ENDPOINT\" is not an http(s) URL; expected http://<host>:<port> without spaces, commas or quotes"
[[ "$S3_BUCKET" =~ ^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$ ]] \
  || die "S3_BUCKET: \"$S3_BUCKET\" is not a bucket name; expected 3 to 63 lower-case letters, digits, . and -, such as lake"
[[ "$S3_REGION" =~ ^[A-Za-z0-9_-]+$ ]] \
  || die "S3_REGION: \"$S3_REGION\" is not a region; expected letters, digits, - and _, such as us-east-1"
origin="^([*]|https?://$host)\$"
[[ "$EVENT_BRIDGE_ALLOW_ORIGIN" =~ $origin ]] \
  || die "EVENT_BRIDGE_ALLOW_ORIGIN: \"$EVENT_BRIDGE_ALLOW_ORIGIN\" is not an origin; expected * or <scheme>://<host>[:<port>] such as http://studio.local:8088"

POD_BOOTSTRAP="$KAFSCALE_BOOTSTRAP"
if [[ "$KAFSCALE_NETWORK" == "service" ]]; then
  [[ "$KAFSCALE_BOOTSTRAP" != *,* ]] || die "KAFSCALE_NETWORK=service supports one bootstrap address; use direct for a list"
  KS_HOST="${KAFSCALE_BOOTSTRAP%:*}"; KS_PORT="${KAFSCALE_BOOTSTRAP##*:}"
  POD_BOOTSTRAP="opentsx-kafscale.$OPENTSX_NS.svc.cluster.local:$KS_PORT"
fi
gen_secret() { LC_ALL=C tr -dc 'A-Za-z0-9' < /dev/urandom | head -c "$1" || true; }
if is_true "$LAKE"; then
  if is_true "$LAKE_MINIO"; then
    S3_ENDPOINT="${S3_ENDPOINT:-http://opentsx-minio:9000}"
    [[ -n "$S3_ACCESS_KEY" ]] || S3_ACCESS_KEY="opentsx-$(gen_secret 8)"
    [[ -n "$S3_SECRET_KEY" ]] || S3_SECRET_KEY="$(gen_secret 32)"
    for key in S3_ACCESS_KEY S3_SECRET_KEY; do
      [[ "${!key}" =~ ^[A-Za-z0-9._~-]+$ ]] || die "$key: holds a character that does not fit into the URL MC_HOST_lake of the bucket Job; with LAKE_MINIO=true use letters, digits, '.', '_', '~' and '-'"
    done
  else
    [[ -n "$S3_ENDPOINT" ]] || die "LAKE=true with LAKE_MINIO=false needs S3_ENDPOINT (your S3 / MinIO URL)"
    [[ -n "$S3_ACCESS_KEY" && -n "$S3_SECRET_KEY" ]] || die "LAKE=true with LAKE_MINIO=false needs S3_ACCESS_KEY and S3_SECRET_KEY"
  fi
  ICEBERG_PROPS="type=rest,uri=http://opentsx-iceberg-rest:8181,warehouse=s3://$S3_BUCKET/,io-impl=org.apache.iceberg.aws.s3.S3FileIO,s3.endpoint=$S3_ENDPOINT,s3.path-style-access=true,client.region=$S3_REGION"
else
  ICEBERG_PROPS=""
fi

# characters as the SaaS backend counts them (UTF-8 lead bytes), in any locale
utf8_chars() { printf %s "$1" | LC_ALL=C tr -d '\200-\277' | LC_ALL=C wc -c; }
if is_true "$RETRIEVAL_DAG_RUNS"; then
  # the retrieval server runs DAGs only for callers that know this token; the SaaS backend is the caller
  [[ -n "$RETRIEVAL_DAG_RUN_TOKEN" ]] || RETRIEVAL_DAG_RUN_TOKEN="$(gen_secret 40)"
  [[ "$RETRIEVAL_DAG_RUN_TOKEN" =~ ^[A-Za-z0-9]{24,}$ ]] || die "RETRIEVAL_DAG_RUN_TOKEN needs at least 24 letters and digits"
fi
[[ "$SAAS_DAG_RUN_ROLE" =~ ^(superuser|user)$ ]] || die "SAAS_DAG_RUN_ROLE must be superuser or user"
if is_true "$SAAS" && is_true "$SAAS_TEST_LOGIN"; then
  warn "SAAS_TEST_LOGIN=true: admin/admin signs in as administrator. Everyone who reaches the page can deploy DAGs. Lab networks only."
fi
# the values the SaaS backend refuses at start, read from its source so that both checks use one list
PUBLISHED_EXAMPLES=()
read_published_examples() {
  local file="$ROOT/opentsx-saas-backend/app/core/config.py" line inside=false
  [[ -f "$file" ]] || die "$file: no such file; SAAS=true reads PUBLISHED_EXAMPLES from it"
  while IFS= read -r line; do
    if ! $inside; then [[ "$line" == "PUBLISHED_EXAMPLES = frozenset({" ]] && inside=true; continue; fi
    if [[ "$line" == "})" ]]; then (( ${#PUBLISHED_EXAMPLES[@]} )) && return 0; break; fi
    [[ "$line" =~ ^\ +\"([^\"\\]+)\",$ ]] \
      || die "$file: PUBLISHED_EXAMPLES holds the line '$line'; keep one double-quoted value with a comma per line"
    PUBLISHED_EXAMPLES+=("${BASH_REMATCH[1]}")
  done < "$file"
  die "$file: no values in PUBLISHED_EXAMPLES = frozenset({ ... }); keep one double-quoted value with a comma per line"
}
is_published_example() { local v; for v in "${PUBLISHED_EXAMPLES[@]}"; do [[ "$1" == "$v" ]] && return 0; done; return 1; }
if is_true "$SAAS"; then
  [[ "$SAAS_ADMIN_EMAIL" =~ ^[^@[:space:]]+@[^@[:space:]]+\.[^@[:space:]]+$ ]] || die "SAAS_ADMIN_EMAIL=$SAAS_ADMIN_EMAIL is not an e-mail address"
  [[ -n "$SAAS_DB_PASSWORD" ]] || SAAS_DB_PASSWORD="$(gen_secret 32)"
  [[ -n "$SAAS_SECRET_KEY" ]] || SAAS_SECRET_KEY="$(gen_secret 48)"
  [[ -n "$SAAS_ADMIN_PASSWORD" ]] || SAAS_ADMIN_PASSWORD="$(gen_secret 20)"
  read_published_examples
  ! is_published_example "$SAAS_ADMIN_PASSWORD" \
    || die "SAAS_ADMIN_PASSWORD: published example value; set a random password of at least 12 characters"
  ! is_published_example "$SAAS_DB_PASSWORD" \
    || die "SAAS_DB_PASSWORD: published example value; set at least 12 random letters and digits"
  ! is_published_example "$SAAS_SECRET_KEY" \
    || die "SAAS_SECRET_KEY: published example value; generate a random key of at least 32 characters"
  # both end up in a URL (database) and in an env file: keep them to letters and digits
  [[ "$SAAS_DB_PASSWORD" =~ ^[A-Za-z0-9]+$ ]] || die "SAAS_DB_PASSWORD may only hold letters and digits"
  (( ${#SAAS_DB_PASSWORD} >= 12 )) || die "SAAS_DB_PASSWORD: shorter than 12 characters; set at least 12 letters and digits"
  (( $(utf8_chars "$SAAS_SECRET_KEY") >= 32 )) || die "SAAS_SECRET_KEY needs at least 32 characters"
  # the backend refuses these at start (opentsx-saas-backend/app/core/config.py); bcrypt reads at most 72 bytes
  (( $(utf8_chars "$SAAS_ADMIN_PASSWORD") >= 12 )) || die "SAAS_ADMIN_PASSWORD: shorter than 12 characters; set at least 12 characters"
  (( $(printf %s "$SAAS_ADMIN_PASSWORD" | LC_ALL=C wc -c) <= 72 )) \
    || die "SAAS_ADMIN_PASSWORD: longer than 72 bytes; set at most 72 bytes in UTF-8"
fi

umask 077
{
  echo "# written by deploy/k3s/20-configure.sh on $(date -u +%Y-%m-%dT%H:%M:%SZ); contains secrets, never commit"
  for key in "${CONFIG_KEYS[@]}"; do printf '%s=%q\n' "$key" "${!key}"; done
} > "$ENV_FILE"
chmod 600 "$ENV_FILE"

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
DWD_STATIONS=$DWD_STATIONS
DWD_MODE=$DWD_MODE
MODBUS_HOST=$MODBUS_HOST
MODBUS_PORT=$MODBUS_PORT
MODBUS_MAP=$MODBUS_MAP
EVENT_BRIDGE_ALLOW_ORIGIN=$EVENT_BRIDGE_ALLOW_ORIGIN
OPENTSX_WINDPARK_SCENARIO=$OPENTSX_WINDPARK_SCENARIO
OPENTSX_STATS_WINDOWS=$OPENTSX_STATS_WINDOWS
OPENTSX_STATS_ALLOWED_LATENESS=$OPENTSX_STATS_ALLOWED_LATENESS
OPENTSX_ICEBERG_NAMESPACE=$OPENTSX_ICEBERG_NAMESPACE
DWD_FIXTURE=$DWD_FIXTURE
RETRIEVAL_DAG_RUNS=$(is_true "$RETRIEVAL_DAG_RUNS" && echo true || echo false)
SAAS_DAG_RUN_ROLE=$SAAS_DAG_RUN_ROLE
SAAS_TEST_LOGIN=$(is_true "$SAAS_TEST_LOGIN" && echo true || echo false)
EOF

{
  [[ -n "$S3_ACCESS_KEY" ]] && printf 'S3_ACCESS_KEY=%s\nAWS_ACCESS_KEY_ID=%s\n' "$S3_ACCESS_KEY" "$S3_ACCESS_KEY"
  [[ -n "$S3_SECRET_KEY" ]] && printf 'S3_SECRET_KEY=%s\nAWS_SECRET_ACCESS_KEY=%s\n' "$S3_SECRET_KEY" "$S3_SECRET_KEY"
  [[ -n "$S3_ACCESS_KEY" ]] && printf 'AWS_REGION=%s\n' "$S3_REGION"
  [[ -n "$OPENMETADATA_JWT" ]] && printf 'OPENMETADATA_JWT=%s\n' "$OPENMETADATA_JWT"
  is_true "$RETRIEVAL_DAG_RUNS" && printf 'DAG_RUN_TOKEN=%s\n' "$RETRIEVAL_DAG_RUN_TOKEN"
  true
} > "$OVERLAY/secrets.env"
chmod 600 "$OVERLAY/secrets.env"
if is_true "$SAAS"; then
  printf 'SAAS_ADMIN_EMAIL=%s\nSAAS_ADMIN_PASSWORD=%s\nSAAS_DB_PASSWORD=%s\nSAAS_SECRET_KEY=%s\n' \
    "$SAAS_ADMIN_EMAIL" "$SAAS_ADMIN_PASSWORD" "$SAAS_DB_PASSWORD" "$SAAS_SECRET_KEY" > "$OVERLAY/saas-secrets.env"
  chmod 600 "$OVERLAY/saas-secrets.env"
else
  rm -f "$OVERLAY/saas-secrets.env"
fi

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
if is_true "$LAKE"; then
  components+=("../../components/lake"); jobs_components+=("../../../components/lake-jobs")
  is_true "$LAKE_MINIO" && components+=("../../components/lake-minio")
fi
[[ "$RETRIEVAL_SOURCE" == kafscale ]] && components+=("../../components/retrieval-kafscale")
is_true "$MODBUS_SIM" && components+=("../../components/modbus-sim")
is_true "$DWD_FORWARD" && components+=("../../components/dwd-forward")
is_true "$MONITORING" && components+=("../../components/monitoring")
is_true "$SAAS" && components+=("../../components/saas")
is_true "$EVENT_BRIDGE" && components+=("../../components/event-bridge")
is_true "$VECTOR_PROCESSOR" && components+=("../../components/vector-processor")
is_true "$INGRESS" && components+=("../../components/ingress")
# last: a component's patches only see the workloads added before it (base and the components above)
if [[ "$KAFSCALE_NETWORK" == "hostnetwork" ]]; then
  components+=("../../components/hostnetwork"); jobs_components+=("../../../components/hostnetwork")
fi

yaml_list() { local i; for i in "$@"; do echo "  - $i"; done; }
overlay_patches() {   # storage class for every PVC; schedule of the DWD CronJob
  if [[ -z "$STORAGE_CLASS" ]] && ! is_true "$DWD_FORWARD"; then return 0; fi
  echo "patches:"
  if [[ -n "$STORAGE_CLASS" ]]; then
    cat <<EOF
  - target:
      kind: PersistentVolumeClaim
    patch: |-
      - op: add
        path: /spec/storageClassName
        value: $STORAGE_CLASS
EOF
  fi
  if is_true "$DWD_FORWARD"; then
    cat <<EOF
  - target:
      kind: CronJob
      name: opentsx-dwd-forward
    patch: |-
      - op: replace
        path: /spec/schedule
        value: "$DWD_SCHEDULE"
EOF
  fi
}

{
  echo "# rendered by deploy/k3s/20-configure.sh; do not edit, run the script again"
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
EOF
  if is_true "$SAAS"; then printf '  - name: opentsx-saas-secrets\n    envs: [saas-secrets.env]\n'; fi
  cat <<EOF
generatorOptions:
  disableNameSuffixHash: true
images:
  - name: opentsx/tools
    newName: $OPENTSX_IMAGE_REPO/tools
    newTag: "$OPENTSX_TAG"
  - name: opentsx/studio
    newName: $OPENTSX_IMAGE_REPO/studio
    newTag: "$OPENTSX_TAG"
  - name: opentsx/saas-frontend
    newName: $OPENTSX_IMAGE_REPO/saas-frontend
    newTag: "$OPENTSX_TAG"
  - name: opentsx/saas-backend
    newName: $OPENTSX_IMAGE_REPO/saas-backend
    newTag: "$OPENTSX_TAG"
EOF
  overlay_patches
} > "$OVERLAY/kustomization.yaml"

{
  echo "# rendered by deploy/k3s/20-configure.sh: demo Jobs, used by 40-demo.sh"
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

info "configuration written: $ENV_FILE, $OVERLAY/"
echo "  namespace $OPENTSX_NS | KafScale $KAFSCALE_BOOTSTRAP ($KAFSCALE_NETWORK; pods use $POD_BOOTSTRAP) | domain $OPENTSX_DOMAIN"
echo "  images $OPENTSX_IMAGE_REPO/{tools,studio}:$OPENTSX_TAG | lake $LAKE (minio $LAKE_MINIO) | retrieval $RETRIEVAL_SOURCE (dag runs $RETRIEVAL_DAG_RUNS) | vector processor $VECTOR_PROCESSOR | ingress $INGRESS | OpenMetadata ${OM_URL:-off}"
echo "  modbus-sim $MODBUS_SIM (poll $MODBUS_HOST:$MODBUS_PORT, map $MODBUS_MAP) | dwd-forward $DWD_FORWARD ($DWD_MODE, $DWD_STATIONS, '$DWD_SCHEDULE') | monitoring $MONITORING | saas $SAAS | event-bridge $EVENT_BRIDGE"
if kc version --client >/dev/null 2>&1; then
  n="$(kc kustomize "$OVERLAY" | grep -c '^kind:')" || die "kubectl kustomize $OVERLAY failed"
  m="$(kc kustomize "$OVERLAY/jobs" | grep -c '^kind:')" || die "kubectl kustomize $OVERLAY/jobs failed"
  ok "renders: $n objects, $m demo Jobs"
  $PRINT && kc kustomize "$OVERLAY" | awk '
    function flush() { if (doc != "" && !secret) { printf "%s%s", sep, doc; sep = "---\n" } doc = ""; secret = 0 }
    /^---$/ { flush(); next }
    /^kind: Secret$/ { secret = 1 }
    { doc = doc $0 "\n" }
    END { flush() }'
else
  warn "kubectl not available; rendering not checked"
fi
echo "next: deploy/k3s/30-deploy.sh   (already deployed? 30-deploy.sh --restart applies a changed configuration)"
