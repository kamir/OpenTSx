#!/usr/bin/env bash
# Tests deploy/k3s/40-demo.sh: usage errors exit 2 before the overlay is read, the port-forward of the retrieval demo
# ends with the script and a failed request names its URL, metadata-publish names a missing OPENMETADATA_JWT before
# kubectl runs, an option after -- replaces the Job's default, and dwd / dwd-online select the source through the
# registry variables. A stand-in kubectl renders the Jobs of deploy/k3s/jobs and records what is applied; stand-ins
# for curl and sleep keep the test free of a cluster, the network and waiting.
#
#   bash deploy/k3s/test/test-40-demo.sh
set -euo pipefail
K3S_DIR="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(mktemp -d)"
cleanup() {
  if [[ -s "$WORK/pf.pid" ]]; then kill "$(cat "$WORK/pf.pid")" 2>/dev/null || true; fi
  rm -rf "$WORK"
}
trap cleanup EXIT
failures=0
failed() { echo "FAIL $1"; failures=$((failures + 1)); }

mkdir -p "$WORK/bin" "$WORK/overlay/jobs"
touch "$WORK/overlay/kustomization.yaml"
printf '#!/bin/sh\n' > "$WORK/bin/sleep"
# kubectl: every call goes to calls.log. kustomize prints the Jobs of deploy/k3s/jobs, set env --local edits the env
# of the manifest on stdin (NAME=VALUE replaces or adds, NAME- removes), apply appends to applied.yaml, the Job
# completes at once, and port-forward stays up until it is killed.
cat > "$WORK/bin/kubectl" <<'EOF'
#!/usr/bin/env bash
printf '%s\n' "$*" >> "$WORK/calls.log"
[[ "${KUBECTL_FAILS:-}" == yes ]] && exit 1
[[ "$1" == -n ]] && shift 2
case "$1 ${2:-}" in
  "kustomize "*)
    sep=""
    for f in "$K3S_DIR"/jobs/*.yaml; do
      [[ "$f" == */kustomization.yaml ]] && continue
      printf '%s' "$sep"; cat "$f"; sep=$'---\n'
    done ;;
  "set env")
    shift 2
    specs=()
    for a in "$@"; do [[ "$a" == *=* || "$a" == *- ]] && [[ "$a" != -* ]] && specs+=("$a"); done
    awk -v specs="$(printf '%s\n' "${specs[@]}")" '
      BEGIN {
        n = split(specs, a, "\n")
        for (i = 1; i <= n; i++) {
          if (a[i] == "") continue
          if (a[i] ~ /-$/) { del[substr(a[i], 1, length(a[i]) - 1)] = 1; continue }
          k = a[i]; sub(/=.*/, "", k); v = substr(a[i], length(k) + 2); gsub(/"/, "\\\"", v)
          val[k] = v; order[++m] = k
        }
      }
      drop && /^ *(value|valueFrom):/ { drop = 0; next }
      repl != "" && /^ *(value|valueFrom):/ { out[++o] = ind "  value: \"" val[repl] "\""; repl = ""; next }
      /^ *- name: [A-Za-z_][A-Za-z0-9_]*/ {
        name = $0; sub(/^ *- name: /, "", name); sub(/[ #].*/, "", name)
        ind = $0; sub(/-.*/, "", ind)
        if (name in del) { drop = 1; next }
        if (name in val) { done[name] = 1; repl = name }
      }
      { out[++o] = $0 }
      END {
        for (j = 1; j <= o; j++) {
          print out[j]
          if (out[j] ~ /^ *env:$/ && !added) {
            ei = out[j]; sub(/env:$/, "", ei); added = 1
            for (i = 1; i <= m; i++) if (!(order[i] in done)) { print ei "  - name: " order[i]; print ei "    value: \"" val[order[i]] "\"" }
          }
        }
      }' ;;
  "apply -f") cat >> "$WORK/applied.yaml"; echo --- >> "$WORK/applied.yaml" ;;
  "delete job") ;;
  "get pod")
    case "$*" in *status.phase*) echo Succeeded ;; esac ;;
  "get job") echo Complete ;;
  "logs "*) ;;
  "port-forward "*)
    echo "$$" > "$WORK/pf.pid"
    exec 3>"$WORK/pf.fifo"
    exec tail -f /dev/null ;;
  *) echo "kubectl stand-in: unexpected call: $*" >&2; exit 1 ;;
esac
EOF
# curl: /v1/health answers 200, every other path 500; CURL_MODE=refused answers nothing (exit 7 as curl does)
cat > "$WORK/bin/curl" <<'EOF'
#!/usr/bin/env bash
url="" fmt="" fail=false
while (( $# )); do
  case "$1" in
    -w) fmt="$2"; shift 2 ;;
    -o) shift 2 ;;
    --*) shift ;;
    -*) [[ "$1" == *f* ]] && fail=true; shift ;;
    *) url="$1"; shift ;;
  esac
done
if [[ "${CURL_MODE:-}" == refused ]]; then echo "curl: (7) Failed to connect to localhost port 18080" >&2; exit 7; fi
case "$url" in */v1/health) code=200 body='{"status":"UP"}' ;; *) code=500 body='{"error":"store failed","type":"internal"}' ;; esac
if $fail && (( code >= 400 )); then echo "curl: (22) The requested URL returned error: $code" >&2; exit 22; fi
printf '%s' "$body"
if [[ -n "$fmt" ]]; then printf '%b' "${fmt//%\{http_code\}/$code}"; fi
EOF
chmod +x "$WORK/bin/kubectl" "$WORK/bin/curl" "$WORK/bin/sleep"

# demo NAME [KEY=VALUE ...] -- ARG ...: runs 40-demo.sh with the stand-ins; output in NAME.out, exit code in rc
demo() {
  local name="$1"; shift
  local -a envs=()
  while (( $# )) && [[ "$1" != -- ]]; do envs+=("$1"); shift; done
  if (( $# )); then shift; fi
  rm -f "$WORK/calls.log" "$WORK/applied.yaml"
  rc=0
  env -i PATH="$WORK/bin:$PATH" HOME="$WORK" WORK="$WORK" K3S_DIR="$K3S_DIR" KUBECTL="$WORK/bin/kubectl" \
    OPENTSX_ENV_FILE="$WORK/none.env" OPENTSX_OVERLAY="$WORK/overlay" "${envs[@]}" \
    bash "$K3S_DIR/40-demo.sh" "$@" > "$WORK/$name.out" 2>&1 || rc=$?
}

# expect NAME CODE MESSAGE [KEY=VALUE ...] -- ARG ...
expect() {
  local name="$1" code="$2" message="$3"; shift 3
  demo "$name" "$@"
  if (( rc != code )); then
    failed "$name: exit $rc, expected $code"; sed 's/^/    /' "$WORK/$name.out"
  elif ! grep -qF -- "$message" "$WORK/$name.out"; then
    failed "$name: expected the message: $message"; sed 's/^/    /' "$WORK/$name.out"
  else
    echo "ok   $name"
  fi
}

expect unknown-demo 2 "error: nosuch: unknown demo; expected one of kafscale-check windpark-sim stats metadata" \
  OPENTSX_OVERLAY="$WORK/no-overlay" -- nosuch
expect unknown-demo-two-words 2 "error: dwd dwd-online: unknown demo; expected one of kafscale-check windpark-sim" \
  OPENTSX_OVERLAY="$WORK/no-overlay" -- "dwd dwd-online"
expect no-demo 2 "error: demo: missing; expected 40-demo.sh <demo> [-- ARG ...], see 40-demo.sh --help" --
expect extra-not-taken 2 "error: retrieval: takes no extra arguments; drop the arguments after --" \
  OPENTSX_OVERLAY="$WORK/no-overlay" -- retrieval -- --limit 5
expect extra-without-dashes 2 \
  "error: --calibrate: unexpected argument; extra arguments follow --, such as 40-demo.sh house -- --calibrate /private/bills.csv" \
  -- house --calibrate
expect help 0 "  2  usage error" -- --help

# metadata-publish: the token is checked with OM_URL, before kubectl runs
expect metadata-publish-no-token 1 "error: OPENMETADATA_JWT: not set; run read -rs OPENMETADATA_JWT && export OPENMETADATA_JWT" \
  OM_URL=http://om.example:8585/api KUBECTL_FAILS=yes -- metadata-publish
if [[ -s "$WORK/calls.log" ]]; then
  failed "metadata-publish-no-token: kubectl was called"; sed 's/^/    /' "$WORK/calls.log"
fi

# retrieval: a refused request names the URL, and the port-forward ends with the script
retrieval() {   # NAME CURL_MODE URL MESSAGE
  local name="$1" mode="$2" url="$3" message="$4" waited=0
  rm -f "$WORK/pf.pid" "$WORK/pf.fifo"
  mkfifo "$WORK/pf.fifo"
  # cat ends when the last process holding the write end (the stand-in port-forward) has ended
  timeout 20 cat "$WORK/pf.fifo" > /dev/null & local reader=$!
  demo "$name" CURL_MODE="$mode" -- retrieval
  wait "$reader" || waited=$?
  local before=$failures
  if (( rc == 0 )); then failed "$name: exit 0"; fi
  if ! grep -qF -- "FAIL  GET $url: $message" "$WORK/$name.out"; then
    failed "$name: expected the line: FAIL  GET $url: $message"; sed 's/^/    /' "$WORK/$name.out"
  fi
  if [[ ! -s "$WORK/pf.pid" ]]; then
    failed "$name: no port-forward was started"
  elif (( waited != 0 )); then
    failed "$name: the port-forward (pid $(cat "$WORK/pf.pid")) still runs after the script ended"
  fi
  if (( failures == before )); then echo "ok   $name"; fi
  if [[ -s "$WORK/pf.pid" ]]; then kill "$(cat "$WORK/pf.pid")" 2>/dev/null || true; rm -f "$WORK/pf.pid"; fi
}
retrieval retrieval-refused refused "http://localhost:18080/v1/health" \
  "no answer (curl exit 7); check the retrieval server with deploy/k3s/50-status.sh"
retrieval retrieval-status answers "http://localhost:18080/v1/episodes?metric=demo.signal&limit=1" \
  "HTTP 500; check the retrieval server with deploy/k3s/50-status.sh"

# an option after -- replaces the default of the Job instead of being set twice
demo modbus-interval MODBUS_HOST=meter.example -- modbus-poll -- --interval PT2S
n="$(sed -e '/^ *#/d' -e 's/ #.*//' "$WORK/applied.yaml" 2>/dev/null | grep -oE -- '--interval\>' | wc -l)"
if (( rc != 0 )); then
  failed "modbus-interval: exit $rc"; sed 's/^/    /' "$WORK/modbus-interval.out"
elif (( n != 1 )); then
  failed "modbus-interval: the applied Job holds --interval $n times, expected once"
  grep -nE -- '--interval|value: "--' "$WORK/applied.yaml" | sed 's/^/    /'
else
  echo "ok   modbus-interval"
fi

# dwd reads the fixture through OPENTSX_DWD_OFFLINE, dwd-online the first station through OPENTSX_DWD_STATION
env_of() {   # NAME: the env entry NAME of the applied manifest and the line after it
  grep -A1 -E "^ *- name: $1\>" "$WORK/applied.yaml" | tr -s ' ' | tr '\n' ' '
}
demo dwd -- dwd
if (( rc != 0 )); then
  failed "dwd: exit $rc"; sed 's/^/    /' "$WORK/dwd.out"
elif [[ "$(env_of OPENTSX_DWD_OFFLINE)" != *"configMapKeyRef: {name: opentsx-config, key: DWD_FIXTURE}"* ]]; then
  failed "dwd: OPENTSX_DWD_OFFLINE is not the ConfigMap value DWD_FIXTURE: '$(env_of OPENTSX_DWD_OFFLINE)'"
elif [[ -n "$(env_of OPENTSX_DWD_STATION)" ]]; then
  failed "dwd: OPENTSX_DWD_STATION is set: '$(env_of OPENTSX_DWD_STATION)'"
else
  echo "ok   dwd"
fi
demo dwd-online DWD_STATIONS=10579,10578 -- dwd-online
if (( rc != 0 )); then
  failed "dwd-online: exit $rc"; sed 's/^/    /' "$WORK/dwd-online.out"
elif [[ -n "$(env_of OPENTSX_DWD_OFFLINE)" ]]; then
  failed "dwd-online: OPENTSX_DWD_OFFLINE is still set: '$(env_of OPENTSX_DWD_OFFLINE)'"
elif [[ "$(env_of OPENTSX_DWD_STATION)" != *'value: "10579"'* ]]; then
  failed "dwd-online: OPENTSX_DWD_STATION is not the first station 10579: '$(env_of OPENTSX_DWD_STATION)'"
else
  echo "ok   dwd-online"
fi

if (( failures )); then echo "$failures failed"; exit 1; fi
echo "all passed"
