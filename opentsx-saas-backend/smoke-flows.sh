#!/usr/bin/env bash
# Smoke test of the accounts and the stored flows against a running SaaS backend.
#
#   opentsx-saas-backend/smoke-flows.sh [base-url]        # default http://localhost:8000
#   opentsx-saas-backend/smoke-flows.sh http://<node>:30311   # through the frontend image (/api/ proxy)
#
# Registers two accounts (smoke-<time>-a@example.com, ...-b@example.com), stores a flow as the first, reads,
# changes and deletes it, and checks that the second account cannot see it. The two accounts stay in the
# database: the API has no endpoint to delete a user. Needs curl and python3.
set -euo pipefail

BASE="${1:-http://localhost:8000}"
API="${BASE%/}/api/v1"
STAMP="$(date +%s)-$RANDOM"
PASSWORD="smoke-$(LC_ALL=C tr -dc 'A-Za-z0-9' < /dev/urandom | head -c 16 || true)"
OUT="$(mktemp)"
trap 'rm -f "$OUT"' EXIT
FAILED=0

# call METHOD PATH TOKEN [JSON]  -> sets CODE, body in $OUT
call() {
  local method="$1" path="$2" token="$3" body="${4:-}"
  local -a args=(-s -m 20 -o "$OUT" -w '%{http_code}' -X "$method" "$API$path")
  [[ -n "$token" ]] && args+=(-H "Authorization: Bearer $token")
  [[ -n "$body" ]] && args+=(-H 'Content-Type: application/json' -d "$body")
  CODE="$(curl "${args[@]}" || true)"
}
# field EXPR  -> python expression on the parsed body `d`
field() { python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); print(eval(sys.argv[2]))' "$OUT" "$1"; }
check() {   # description expected actual
  if [[ "$2" == "$3" ]]; then echo "  ok    $1"; else echo "  FAIL  $1: expected $2, got $3"; FAILED=1; fi
}

echo "smoke test against $API"
CODE="$(curl -s -m 10 -o "$OUT" -w '%{http_code}' "${BASE%/}/api/v1/openapi.json" || true)"
check "backend answers (openapi.json)" 200 "$CODE"
[[ "$CODE" == 200 ]] || { echo "no backend at $API - stopping"; exit 1; }

login() {   # suffix -> TOKEN
  local email="smoke-$STAMP-$1@example.com"
  call POST /auth/register "" "{\"email\":\"$email\",\"password\":\"$PASSWORD\",\"full_name\":\"Smoke $1\"}"
  check "register $1" 201 "$CODE"
  call POST /auth/login "" "{\"email\":\"$email\",\"password\":\"$PASSWORD\"}"
  check "login $1" 200 "$CODE"
  TOKEN="$(field 'd["access_token"]')"
  REFRESH="$(field 'd["refresh_token"]')"
}
login a; TOKEN_A="$TOKEN"; REFRESH_A="$REFRESH"
login b; TOKEN_B="$TOKEN"

call GET /flows ""
[[ "$CODE" == 401 || "$CODE" == 403 ]] && CODE=denied
check "list without token is refused" denied "$CODE"
call GET /flows "not-a-token"
check "list with a bad token is refused" 401 "$CODE"
call GET /flows "$REFRESH_A"
check "a refresh token is not accepted as access token" 401 "$CODE"

DEFINITION='{"kind":"opentsx.workbench.v1","track":"SMOKE","nodes":[{"id":"source","op":"source","params":{},"input":null,"label":"B"},{"id":"n1","op":"resample","params":{"every":2,"agg":"MEAN"},"input":"source","label":"Resample_B","sinkTopic":"tsx.lab.smart.smoke.metrics.v2"}]}'
call POST /flows "$TOKEN_A" "{\"name\":\"smoke flow\",\"description\":\"created by smoke-flows.sh\",\"definition\":$DEFINITION}"
check "create flow" 201 "$CODE"
ID="$(field 'd["flow"]["id"]')"
check "new flow is a draft" draft "$(field 'd["flow"]["status"]')"

call GET /flows "$TOKEN_A"
check "list as owner" 200 "$CODE"
check "list holds the flow" True "$(field "any(f['id'] == $ID for f in d['flows'])")"

call GET "/flows/$ID" "$TOKEN_A"
check "read flow" 200 "$CODE"
check "definition is stored unchanged" tsx.lab.smart.smoke.metrics.v2 "$(field 'd["definition"]["nodes"][1]["sinkTopic"]')"

call PUT "/flows/$ID" "$TOKEN_A" '{"name":"smoke flow v2","status":"active"}'
check "update flow" 200 "$CODE"
call GET "/flows/$ID" "$TOKEN_A"
check "update is stored" "smoke flow v2 active" "$(field 'd["name"] + " " + d["status"]')"
check "update keeps the definition" SMOKE "$(field 'd["definition"]["track"]')"

call PUT "/flows/$ID" "$TOKEN_A" '{"status":"nonsense"}'
check "unknown status is rejected" 422 "$CODE"

call GET "/flows/$ID" "$TOKEN_B"
check "another account cannot read it" 404 "$CODE"
call PUT "/flows/$ID" "$TOKEN_B" '{"name":"taken"}'
check "another account cannot change it" 404 "$CODE"
call DELETE "/flows/$ID" "$TOKEN_B"
check "another account cannot delete it" 404 "$CODE"
call GET /flows "$TOKEN_B"
check "the other account sees no flows" 0 "$(field 'len(d["flows"])')"

call POST /flows "$TOKEN_A" "{\"name\":\"foreign org\",\"definition\":{},\"organization_id\":999999}"
check "foreign organization is refused" 403 "$CODE"

call DELETE "/flows/$ID" "$TOKEN_A"
check "delete flow" 200 "$CODE"
call GET "/flows/$ID" "$TOKEN_A"
check "deleted flow is gone" 404 "$CODE"

# DAG runs write topics: only with an account, and for a plain account only where the installation allows it.
# Nothing is run here: the checks stop at the refusal or use a document the retrieval server rejects.
CODE="$(curl -s -m 20 -o "$OUT" -w '%{http_code}' -X POST -H 'Content-Type: application/yaml' --data-binary 'dag: smoke' "$API/dags/run" || true)"
[[ "$CODE" == 401 || "$CODE" == 403 ]] && CODE=denied
check "dag run without token is refused" denied "$CODE"
call GET /dags/capabilities "$TOKEN_A"
check "capabilities answer" 200 "$CODE"
CAN_RUN="$(field 'd["canRun"]')"
CODE="$(curl -s -m 30 -o "$OUT" -w '%{http_code}' -X POST -H "Authorization: Bearer $TOKEN_A" -H 'Content-Type: application/yaml' \
  --data-binary 'dag: smoke' "$API/dags/run" || true)"
if [[ "$CAN_RUN" == True ]]; then
  # allowed to run: the document is no DAG, so the retrieval server must refuse it (400) and write nothing;
  # 503 means this installation has no retrieval server that runs DAGs
  [[ "$CODE" == 400 || "$CODE" == 503 ]] && CODE=refused
  check "a document that is no DAG is refused" refused "$CODE"
else
  check "a plain account may not run DAGs here" 403 "$CODE"
fi
# through the frontend image the write path of the retrieval server itself is closed
CODE="$(curl -s -m 10 -o "$OUT" -w '%{http_code}' "${BASE%/}/retrieval/v1/health" || true)"
if [[ "$CODE" == 200 ]]; then
  CODE="$(curl -s -m 10 -o "$OUT" -w '%{http_code}' -X POST --data-binary 'dag: smoke' "${BASE%/}/retrieval/v1/dags/run" || true)"
  check "the retrieval write path is closed at the frontend" 403 "$CODE"
fi

if (( FAILED )); then echo "FAILED"; exit 1; fi
echo "all checks passed (accounts smoke-$STAMP-a/-b@example.com remain)"
