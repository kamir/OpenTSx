#!/usr/bin/env bash
# Tests deploy/k3s/20-configure.sh: exit codes 0, 1 and 2, secret keys refused as arguments, --print without Secrets,
# the generated secrets, the limits and published example values the SaaS backend refuses
# (opentsx-saas-backend/app/core/config.py), the ConfigMap keys and the form of each value, and a deploy/k3s/.env
# with an unknown key or a line that is not KEY=VALUE. Also checks that the scripts use no spaced hyphen as a dash,
# that docs/, the READMEs and the deploy/k3s scripts put no secret on a command line, and runs the script of the
# MinIO bucket Job (components/lake-minio/minio.yaml) with a stand-in mc. Writes only into a temporary directory and needs no kubectl and no cluster: a stand-in kubectl renders
# the Secret for --print. Needs python3 to read the published example values.
#
#   bash deploy/k3s/test/test-20-configure.sh
set -euo pipefail
K3S_DIR="$(cd "$(dirname "$0")/.." && pwd)"
ROOT="$(cd "$K3S_DIR/../.." && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
failures=0
kubectl=false
SECRET_KEYS=(S3_ACCESS_KEY S3_SECRET_KEY OPENMETADATA_JWT RETRIEVAL_DAG_RUN_TOKEN SAAS_DB_PASSWORD SAAS_SECRET_KEY
  SAAS_ADMIN_PASSWORD)

failed() { echo "FAIL $1"; failures=$((failures + 1)); }

# configure NAME [KEY=VALUE ...] [-- ARG ...]: the KEY=VALUE pairs go into the environment, the ARGs to the script
configure() {
  local name="$1" rc=0; shift
  local -a envs=()
  while (( $# )) && [[ "$1" != -- ]]; do envs+=("$1"); shift; done
  if (( $# )); then shift; fi
  env -i PATH="$PATH" HOME="$WORK" KUBECTL="$kubectl" \
    OPENTSX_ENV_FILE="$WORK/$name.env" OPENTSX_OVERLAY="$WORK/$name/overlays/local" "${envs[@]}" \
    bash "$K3S_DIR/20-configure.sh" KAFSCALE_BOOTSTRAP=10.0.0.5:9092 SAAS=true "$@" > "$WORK/$name.out" 2>&1 || rc=$?
  return "$rc"
}

# expect NAME CODE MESSAGE [KEY=VALUE ...] [-- ARG ...]
expect() {
  local name="$1" code="$2" message="$3" rc=0; shift 3
  configure "$name" "$@" || rc=$?
  if (( rc != code )); then
    failed "$name: exit $rc, expected $code"; sed 's/^/    /' "$WORK/$name.out"
  elif ! grep -qF -- "$message" "$WORK/$name.out"; then
    failed "$name: expected the message: $message"; sed 's/^/    /' "$WORK/$name.out"
  else
    echo "ok   $name"
  fi
}

accepted() {
  local name="$1" key="$2" value="$3"
  if ! configure "$name" "$key=$value"; then
    failed "$name: refused"; sed 's/^/    /' "$WORK/$name.out"
  elif ! grep -qxF "$key=$value" "$WORK/$name/overlays/local/saas-secrets.env"; then
    failed "$name: $key is not in saas-secrets.env"
  else
    echo "ok   $name"
  fi
}

expect admin-11-characters 1 "SAAS_ADMIN_PASSWORD: shorter than 12 characters; set at least 12 characters" \
  SAAS_ADMIN_PASSWORD=abcdefghijk
accepted admin-12-characters SAAS_ADMIN_PASSWORD abcdefghijkl
expect admin-6-two-byte-characters 1 "SAAS_ADMIN_PASSWORD: shorter than 12 characters; set at least 12 characters" \
  SAAS_ADMIN_PASSWORD="$(printf 'ä%.0s' {1..6})"
expect admin-73-bytes 1 "SAAS_ADMIN_PASSWORD: longer than 72 bytes; set at most 72 bytes in UTF-8" \
  SAAS_ADMIN_PASSWORD="$(printf 'ä%.0s' {1..36})a"
accepted admin-72-bytes SAAS_ADMIN_PASSWORD "$(printf 'ä%.0s' {1..36})"
expect db-11-characters 1 "SAAS_DB_PASSWORD: shorter than 12 characters; set at least 12 letters and digits" \
  SAAS_DB_PASSWORD=abcdefghijk
accepted db-12-characters SAAS_DB_PASSWORD abcdefghijkl
expect secret-16-two-byte-characters 1 "SAAS_SECRET_KEY needs at least 32 characters" \
  SAAS_SECRET_KEY="$(printf 'ä%.0s' {1..16})"

expect help 0 "  2  usage error" -- --help
expect unknown-key 2 "NOSUCH: unknown key; use a key of deploy/k3s/env.example" -- NOSUCH=1
expect not-key-value 2 "print: unexpected argument; expected 20-configure.sh [KEY=VALUE ...] [--print]" -- print

for key in "${SECRET_KEYS[@]}"; do
  expect "secret-argument-$key" 2 \
    "$key: secret keys are not accepted on the command line; export $key or put it in deploy/k3s/.env (mode 600)" \
    -- "$key=SENTINEL-not-to-be-echoed"
  if grep -qF SENTINEL-not-to-be-echoed "$WORK/secret-argument-$key.out"; then failed "secret-argument-$key: value echoed"; fi
  if [[ -e "$WORK/secret-argument-$key.env" ]]; then failed "secret-argument-$key: .env written"; fi
done
if configure secret-from-environment OPENMETADATA_JWT=from-the-environment \
  && grep -qxF OPENMETADATA_JWT=from-the-environment "$WORK/secret-from-environment/overlays/local/secrets.env"; then
  echo "ok   secret-from-environment"
else
  failed "secret-from-environment: OPENMETADATA_JWT from the environment is not in secrets.env"
  sed 's/^/    /' "$WORK/secret-from-environment.out"
fi

# A stand-in for kubectl: "version --client" succeeds, "kustomize <dir>" prints a ConfigMap, the Secret of
# <dir>/secrets.env and a Service, laid out as kubectl kustomize lays them out.
cat > "$WORK/kubectl" <<'EOF'
#!/usr/bin/env bash
case "$1" in
  version) exit 0 ;;
  kustomize)
    printf 'apiVersion: v1\ndata:\n  OPENTSX_DOMAIN: wind\nkind: ConfigMap\nmetadata:\n  name: opentsx-config\n'
    if [[ -f "$2/secrets.env" ]]; then
      printf -- '---\napiVersion: v1\ndata:\n'
      while IFS='=' read -r k v; do printf '  %s: %s\n' "$k" "$(printf %s "$v" | base64 -w0)"; done < "$2/secrets.env"
      printf 'kind: Secret\nmetadata:\n  name: opentsx-secrets\ntype: Opaque\n'
    fi
    printf -- '---\napiVersion: v1\nkind: Service\nmetadata:\n  name: opentsx-retrieval\n' ;;
  *) exit 1 ;;
esac
EOF
chmod +x "$WORK/kubectl"
kubectl="$WORK/kubectl"
if ! configure print OPENMETADATA_JWT=print-sentinel -- --print; then
  failed "print: refused"; sed 's/^/    /' "$WORK/print.out"
elif grep -qx 'kind: Secret' "$WORK/print.out" || grep -qF "$(printf print-sentinel | base64 -w0)" "$WORK/print.out"; then
  failed "print: --print shows a Secret"; grep -n -B4 -A3 'kind: Secret' "$WORK/print.out" | sed 's/^/    /'
elif ! grep -qx 'kind: ConfigMap' "$WORK/print.out" || ! grep -qx 'kind: Service' "$WORK/print.out"; then
  failed "print: --print left out more than the Secrets"; sed 's/^/    /' "$WORK/print.out"
else
  echo "ok   print"
fi
kubectl=false

generated() {   # NAME KEY REGEX: the generated value of KEY in .env matches REGEX
  if grep -qE "^$2=$3\$" "$WORK/$1.env"; then echo "ok   $1 $2"; else failed "$1: $2 is not $3"; grep "^$2=" "$WORK/$1.env" | sed 's/^/    /'; fi
}
if configure generated -- LAKE=true; then
  generated generated S3_ACCESS_KEY 'opentsx-[A-Za-z0-9]{8}'
  generated generated S3_SECRET_KEY '[A-Za-z0-9]{32}'
  generated generated SAAS_DB_PASSWORD '[A-Za-z0-9]{32}'
  generated generated SAAS_SECRET_KEY '[A-Za-z0-9]{48}'
  generated generated SAAS_ADMIN_PASSWORD '[A-Za-z0-9]{20}'
else
  failed "generated: refused"; sed 's/^/    /' "$WORK/generated.out"
fi
if configure generated-run-token -- RETRIEVAL_SOURCE=kafscale RETRIEVAL_DAG_RUNS=true; then
  generated generated-run-token RETRIEVAL_DAG_RUN_TOKEN '[A-Za-z0-9]{40}'
else
  failed "generated-run-token: refused"; sed 's/^/    /' "$WORK/generated-run-token.out"
fi
expect minio-key-not-for-a-url 1 \
  "S3_SECRET_KEY: holds a character that does not fit into the URL MC_HOST_lake of the bucket Job" \
  S3_SECRET_KEY='abc/def@ghi' -- LAKE=true

expect admin-published-example 1 \
  "SAAS_ADMIN_PASSWORD: published example value; set a random password of at least 12 characters" \
  SAAS_ADMIN_PASSWORD='SecurePassword123!'
expect db-published-example 1 "SAAS_DB_PASSWORD: published example value; set at least 12 random letters and digits" \
  SAAS_DB_PASSWORD=dev_password_123
expect secret-published-example 1 \
  "SAAS_SECRET_KEY: published example value; generate a random key of at least 32 characters" \
  SAAS_SECRET_KEY=your-secret-key-change-in-production-min-32-chars-long
# every value of the backend's list, read with Python's own parser, is refused
mapfile -t examples < <(python3 -I - "$ROOT/opentsx-saas-backend/app/core/config.py" <<'EOF'
import ast, sys
for node in ast.parse(open(sys.argv[1], encoding="utf-8").read()).body:
    if isinstance(node, ast.Assign) and any(getattr(t, "id", "") == "PUBLISHED_EXAMPLES" for t in node.targets):
        for value in sorted(ast.literal_eval(node.value.args[0])):
            print(value)
EOF
)
(( ${#examples[@]} >= 10 )) || failed "published examples: read ${#examples[@]} values from config.py"
n=0
for value in "${examples[@]}"; do
  n=$((n + 1))
  expect "admin-published-example-$n" 1 "SAAS_ADMIN_PASSWORD: published example value" SAAS_ADMIN_PASSWORD="$value"
done

# the ConfigMap keys of base/kustomization.yaml: set like any other key, written into the overlay's config.env
if configure configmap-keys -- OPENTSX_STATS_WINDOWS=PT5M,1h OPENTSX_STATS_ALLOWED_LATENESS=30s \
  OPENTSX_WINDPARK_SCENARIO=/private/park.yaml OPENTSX_ICEBERG_NAMESPACE=lab DWD_FIXTURE=/private/mosmix.kml; then
  before=$failures
  for line in OPENTSX_STATS_WINDOWS=PT5M,1h OPENTSX_STATS_ALLOWED_LATENESS=30s OPENTSX_WINDPARK_SCENARIO=/private/park.yaml \
    OPENTSX_ICEBERG_NAMESPACE=lab DWD_FIXTURE=/private/mosmix.kml; do
    grep -qxF "$line" "$WORK/configmap-keys/overlays/local/config.env" || failed "configmap-keys: $line is not in config.env"
    grep -qxF "${line%%=*}=$(printf %q "${line#*=}")" "$WORK/configmap-keys.env" || failed "configmap-keys: $line is not in .env"
  done
  (( failures > before )) || echo "ok   configmap-keys"
else
  failed "configmap-keys: refused"; sed 's/^/    /' "$WORK/configmap-keys.out"
fi
duration='expected ISO-8601 such as PT10M or a whole number with the unit ms, s, m, min, h or d such as 10m'
expect windows-not-durations 1 "error: OPENTSX_STATS_WINDOWS: item \"PT5\" is not a duration; $duration" \
  -- OPENTSX_STATS_WINDOWS=PT10M,PT5
expect lateness-negative 1 "error: OPENTSX_STATS_ALLOWED_LATENESS: \"-PT1S\" is not a duration; $duration" \
  -- OPENTSX_STATS_ALLOWED_LATENESS=-PT1S
expect scenario-relative 1 "error: OPENTSX_WINDPARK_SCENARIO: \"park.yaml\" is not an absolute path; expected a file in the image or on a mounted volume, such as /opt/opentsx/share/scenarios/ostsee-demo.yaml" \
  -- OPENTSX_WINDPARK_SCENARIO=park.yaml
expect fixture-relative 1 "error: DWD_FIXTURE: \"mosmix.kml\" is not an absolute path; expected a file in the image or on a mounted volume, such as /opt/opentsx/share/dwd/MOSMIX_L_2026101209_10578.kml" \
  -- DWD_FIXTURE=mosmix.kml
expect namespace-dotted 1 "error: OPENTSX_ICEBERG_NAMESPACE: \"a.b\" is not an Iceberg namespace; expected letters, digits and _, starting with a letter or _, such as tsx" \
  -- OPENTSX_ICEBERG_NAMESPACE=a.b

# a value goes into YAML and env files: one line, and the form each key needs
expect tag-line-break 1 "error: OPENTSX_TAG: the value holds a control character such as a line break; write it on one line" \
  -- OPENTSX_TAG=$'1\nx: y'
if [[ -e "$WORK/tag-line-break/overlays/local/kustomization.yaml" ]]; then
  failed "tag-line-break: kustomization.yaml written"; grep -n 'x: y' "$WORK/tag-line-break/overlays/local/kustomization.yaml" | sed 's/^/    /'
fi
expect map-line-break 1 "error: MODBUS_MAP: the value holds a control character such as a line break; write it on one line" \
  MODBUS_MAP=$'example\nOPENTSX_DOMAIN=other'
expect tag 1 "error: OPENTSX_TAG: \"1:2\" is not an image tag; expected up to 128 letters, digits, _, . and -, starting with a letter, digit or _" \
  -- OPENTSX_TAG=1:2
expect storage-class 1 "error: STORAGE_CLASS: \"Fast SSD\" is not a StorageClass name; expected lower-case letters, digits, - and ., or empty for the default class" \
  -- 'STORAGE_CLASS=Fast SSD'
expect image-repo 1 "error: OPENTSX_IMAGE_REPO: \"Registry/OpenTSx\" is not an image repository; expected [host[:port]/]path in lower case, such as opentsx or registry.local:5000/opentsx" \
  -- OPENTSX_IMAGE_REPO=Registry/OpenTSx
expect om-url 1 "error: OM_URL: \"om.example:8585/api\" is not an http(s) URL; expected http://<host>:<port>/api, or empty without OpenMetadata" \
  -- OM_URL=om.example:8585/api
expect s3-endpoint 1 "error: S3_ENDPOINT: \"http://minio:9000,s3.path-style-access=false\" is not an http(s) URL; expected http://<host>:<port> without spaces, commas or quotes" \
  S3_ACCESS_KEY=access S3_SECRET_KEY=secret -- LAKE=true LAKE_MINIO=false S3_ENDPOINT=http://minio:9000,s3.path-style-access=false
expect s3-bucket 1 "error: S3_BUCKET: \"lake,x=y\" is not a bucket name; expected 3 to 63 lower-case letters, digits, . and -, such as lake" \
  -- S3_BUCKET=lake,x=y
expect s3-region 1 "error: S3_REGION: \"us east\" is not a region; expected letters, digits, - and _, such as us-east-1" \
  -- 'S3_REGION=us east'
expect schedule-quote 1 "error: DWD_SCHEDULE: \"17 5 * * \"\" holds a character that is not part of a cron field; expected digits, letters and * / , ? -, such as 17 5 * * *" \
  -- 'DWD_SCHEDULE=17 5 * * "'
expect schedule-semicolon 1 "error: DWD_SCHEDULE: \"17;5 * * * *\" holds a character that is not part of a cron field; expected digits, letters and * / , ? -, such as 17 5 * * *" \
  -- 'DWD_SCHEDULE=17;5 * * * *'
expect schedule-four-fields 1 "error: DWD_SCHEDULE='17 5 * *': expected 5 cron fields, e.g. '17 5 * * *'" \
  -- 'DWD_SCHEDULE=17 5 * *'
expect allow-origin 1 "error: EVENT_BRIDGE_ALLOW_ORIGIN: \"http://studio.local:8088/\" is not an origin; expected * or <scheme>://<host>[:<port>] such as http://studio.local:8088" \
  -- EVENT_BRIDGE_ALLOW_ORIGIN=http://studio.local:8088/
if configure values-accepted -- STORAGE_CLASS=local-path OPENTSX_IMAGE_REPO=registry.local:5000/opentsx OPENTSX_TAG=3.0.1_rc.1 \
  OM_URL=http://om.example:8585/api EVENT_BRIDGE_ALLOW_ORIGIN=http://studio.local:8088; then
  if grep -qxF '    newName: registry.local:5000/opentsx/tools' "$WORK/values-accepted/overlays/local/kustomization.yaml" \
    && grep -qxF 'OM_URL=http://om.example:8585/api' "$WORK/values-accepted/overlays/local/config.env"; then
    echo "ok   values-accepted"
  else
    failed "values-accepted: a value is missing in the overlay"
  fi
else
  failed "values-accepted: refused"; sed 's/^/    /' "$WORK/values-accepted.out"
fi

# deploy/k3s/.env: an unknown key or a line that is not KEY=VALUE is refused, and the file stays as it is
printf '# hand-edited\nSAAS_ADMIN_PASWORD=x\n' > "$WORK/env-typo.env"
cp "$WORK/env-typo.env" "$WORK/env-typo.before"
expect env-typo 2 "error: $WORK/env-typo.env:2: unknown key SAAS_ADMIN_PASWORD; use a key of deploy/k3s/env.example"
cmp -s "$WORK/env-typo.env" "$WORK/env-typo.before" || failed "env-typo: .env changed"
printf 'OPENTSX_NS=opentsx\ntouch %q\n' "$WORK/env-command-ran" > "$WORK/env-command.env"
expect env-command 2 "error: $WORK/env-command.env:2: not a KEY=VALUE line; write KEY=VALUE, a comment or an empty line"
[[ ! -e "$WORK/env-command-ran" ]] || failed "env-command: the line of .env was run"

# no spaced hyphen as a dash in the messages and comments of the scripts (arithmetic and "-f -" are not dashes)
dashes="$(cd "$K3S_DIR"; grep -nE "[[:alnum:])'?.] - [[:alnum:](]" ./*.sh ../docker/*.sh env.example base/kustomization.yaml \
  | grep -vE '\(\(.* - .*\)\)' || (( $? == 1 )))"
if [[ -n "$dashes" ]]; then failed "dashes: a spaced hyphen used as a dash"; printf '%s\n' "$dashes" | sed 's/^/    /'
else echo "ok   dashes"; fi

# no page teaches a secret on a command line
secret_pattern="20-configure\.sh.*\<($(IFS='|'; echo "${SECRET_KEYS[*]}"))=|--(token|dag-run-token)[= ]\"?\\$"
mapfile -t pages < <(find "$ROOT/docs" -name '*.md'; ls "$ROOT"/README.md "$ROOT"/*/README.md "$K3S_DIR"/README.md \
  "$K3S_DIR"/*/README.md "$K3S_DIR"/*.sh)
hits="$(grep -nE -- "$secret_pattern" "${pages[@]}" || (( $? == 1 )))"
hits+="$(echo; grep -nE -- '--from-literal=' "${pages[@]}" "$K3S_DIR"/*/*.yaml || (( $? == 1 )))"
hits+="$(echo; grep -nE -- "\<($(IFS='|'; echo "${SECRET_KEYS[*]}")) (in |\()20-configure\.sh" \
  "${pages[@]}" || (( $? == 1 )))"
if [[ -n "$hits" ]]; then
  failed "docs: a secret on a command line (use read -rs <KEY> && export <KEY>, or deploy/k3s/.env)"
  printf "%s\n" "${hits//"$ROOT"\//    }"
else
  echo "ok   docs"
fi

# the MinIO bucket Job: keys only in the environment, a bounded wait, a deadline
minio="$K3S_DIR/components/lake-minio/minio.yaml"
job="$(awk 'function flush() { if (doc ~ /\nkind: Job\n/) printf "%s", doc; doc = "\n" }
  BEGIN { doc = "\n" } /^---$/ { flush(); next } { doc = doc $0 "\n" } END { flush() }' "$minio")"
script="$(awk '/^ *args:$/ { a = 1; next }
  a && !s && /^ *- [|>]-?$/ { s = 1; ind = index($0, "-") + 2; fold = ($NF ~ /^>/); next }
  s { if (length($0) && index($0, sprintf("%" (ind - 1) "s", "")) != 1) exit
      line = substr($0, ind); out = out (fold && out != "" ? " " : (out != "" ? "\n" : "")) line }
  END { print out }' <<< "$job")"
grep -qE '^  activeDeadlineSeconds: [0-9]+$' <<< "$job" || failed "minio-job: no activeDeadlineSeconds"
grep -qE '^ +- name: MC_HOST_lake$' <<< "$job" || failed "minio-job: no MC_HOST_lake in the environment"
[[ -n "$script" ]] || failed "minio-job: no script in args"
if grep -qE -- '(-n|--namespace)[ =]opentsx\>' <<< "$job"; then
  failed "minio-job: names the namespace opentsx, but the Job runs in OPENTSX_NS"
  grep -nE -- '(-n|--namespace)[ =]opentsx\>' <<< "$job" | sed 's/^/    /'
fi
mkdir -p "$WORK/minio-bin"
cat > "$WORK/minio-bin/mc" <<'EOF'
#!/usr/bin/env bash
printf '%s\n' "$*" >> "$MC_CALLS"
n="$(wc -l < "$MC_CALLS")"
if (( n > 200 )); then touch "$MC_CALLS.unbounded"; kill "$PPID"; exit 1; fi
[[ "$MC_ANSWERS" == yes ]]
EOF
printf '#!/bin/sh\n' > "$WORK/minio-bin/sleep"
chmod +x "$WORK/minio-bin/mc" "$WORK/minio-bin/sleep"
run_minio_job() {   # NAME yes|no: runs the Job's script with an mc that answers or never answers
  local rc=0
  env -i PATH="$WORK/minio-bin:$PATH" MC_CALLS="$WORK/$1.calls" MC_ANSWERS="$2" S3_BUCKET=lake \
    S3_ACCESS_KEY=ACCESS-SENTINEL S3_SECRET_KEY=SECRET-SENTINEL \
    MC_HOST_lake=http://ACCESS-SENTINEL:SECRET-SENTINEL@opentsx-minio:9000 \
    sh -c "$script" > "$WORK/$1.out" 2>&1 || rc=$?
  return "$rc"
}
if run_minio_job minio-job-answers yes && grep -qx 'mb --ignore-existing lake/lake' "$WORK/minio-job-answers.calls"; then
  echo "ok   minio-job-answers"
else
  failed "minio-job-answers: no bucket made"; sed 's/^/    /' "$WORK/minio-job-answers.out"
fi
if run_minio_job minio-job-no-answer no; then
  failed "minio-job-no-answer: exit 0 without MinIO"
elif [[ -e "$WORK/minio-job-no-answer.calls.unbounded" ]]; then
  failed "minio-job-no-answer: the wait has no bound (stopped after 200 calls of mc)"
elif ! grep -qF "opentsx-minio:9000: no answer" "$WORK/minio-job-no-answer.out"; then
  failed "minio-job-no-answer: expected the message: opentsx-minio:9000: no answer"
  sed 's/^/    /' "$WORK/minio-job-no-answer.out"
else
  echo "ok   minio-job-no-answer ($(wc -l < "$WORK/minio-job-no-answer.calls") calls of mc)"
fi
if cat "$WORK"/minio-job-*.calls | grep -qE 'SENTINEL'; then
  failed "minio-job: an S3 key is an argument of mc"; grep -h SENTINEL "$WORK"/minio-job-*.calls | sort -u | sed 's/^/    /'
fi

(( failures == 0 )) || { echo "$failures failed"; exit 1; }
echo "all passed"
