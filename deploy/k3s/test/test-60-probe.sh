#!/usr/bin/env bash
# Tests the usage errors of deploy/k3s/60-probe.sh: each exits 2 with its message before any step runs, --help
# documents the exit codes, the port-forward of the retrieval step ends when the step gives up, and the deploy step
# runs kafscale-check and 30-deploy.sh --restart when a Deployment is not ready. Needs no kubectl, no cluster and no
# network: stand-ins for kubectl, curl, sleep, 30-deploy.sh and 40-demo.sh take their place.
#
#   bash deploy/k3s/test/test-60-probe.sh
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

probe() {   # NAME ARG ...: output in NAME.out, exit code in rc; runs in WORK, so a report would land there
  local name="$1"; shift
  rc=0
  (cd "$WORK" && env -i PATH="$PATH" HOME="$WORK" KUBECTL=false OPENTSX_ENV_FILE="$WORK/none.env" \
    bash "$K3S_DIR/60-probe.sh" "$@") > "$WORK/$name.out" 2>&1 || rc=$?
}

expect() {   # NAME MESSAGE ARG ...
  local name="$1" message="$2"; shift 2
  probe "$name" "$@"
  if (( rc != 2 )); then
    failed "$name: exit $rc, expected 2"; sed 's/^/    /' "$WORK/$name.out"
  elif ! grep -qxF -- "error: $message" "$WORK/$name.out"; then
    failed "$name: expected the line: error: $message"; sed 's/^/    /' "$WORK/$name.out"
  elif compgen -G "$WORK/probe-*" > /dev/null; then
    failed "$name: a report or a step log was written"
  else
    echo "ok   $name"
  fi
}

expect unknown-option "--nosuch: unknown option; see 60-probe.sh --help" --nosuch
expect unknown-step "--only: unknown step nosuch; expected steps from: kafscale-local prereqs build import configure deploy kafscale-check windpark-sim series-stats metadata twin house boat studio-export retrieval studio dwd dwd-forward modbus status" \
  --only prereqs,nosuch
for option in --kafscale-host --only --skip --report; do
  expect "missing-value$option" "$option: missing value; see 60-probe.sh --help" "$option"
done
expect host-without-local "--kafscale-host: only with --local-kafscale; add --local-kafscale or drop --kafscale-host" \
  --kafscale-host 10.1.2.3
probe help --help
if (( rc != 0 )) || ! grep -qF "  2  usage error" "$WORK/help.out"; then
  failed "help: exit $rc, expected 0 and the exit code 2 in the usage"; sed 's/^/    /' "$WORK/help.out"
else
  echo "ok   help"
fi

# retrieval: curl gets no answer, so the step gives up; the stand-in port-forward holds the write end of a fifo until
# it is killed, and the reader of the fifo ends only when that process has ended
mkdir -p "$WORK/bin" "$WORK/run"
printf '#!/bin/sh\n' > "$WORK/bin/sleep"
printf '#!/bin/sh\necho "curl: (7) Failed to connect" >&2\nexit 7\n' > "$WORK/bin/curl"
cat > "$WORK/bin/kubectl" <<'STANDIN'
#!/usr/bin/env bash
[[ "$1" == -n ]] && shift 2
case "$1" in
  port-forward)
    echo "$$" > "$WORK/pf.pid"
    exec 3>"$WORK/pf.fifo"
    exec tail -f /dev/null ;;
  *) echo "kubectl stand-in: unexpected call: $*" >&2; exit 1 ;;
esac
STANDIN
chmod +x "$WORK/bin/sleep" "$WORK/bin/curl" "$WORK/bin/kubectl"
mkfifo "$WORK/pf.fifo"
timeout 20 cat "$WORK/pf.fifo" > /dev/null & reader=$!
rc=0
(cd "$WORK/run" && env -i PATH="$WORK/bin:$PATH" HOME="$WORK" WORK="$WORK" KUBECTL="$WORK/bin/kubectl" \
  OPENTSX_ENV_FILE="$WORK/none.env" bash "$K3S_DIR/60-probe.sh" --only retrieval) > "$WORK/retrieval.out" 2>&1 || rc=$?
waited=0; wait "$reader" || waited=$?
if (( rc != 1 )); then
  failed "retrieval-port-forward: exit $rc, expected 1"; sed 's/^/    /' "$WORK/retrieval.out"
elif [[ ! -s "$WORK/pf.pid" ]]; then
  failed "retrieval-port-forward: no port-forward was started"; sed 's/^/    /' "$WORK/retrieval.out"
elif (( waited != 0 )); then
  failed "retrieval-port-forward: the port-forward (pid $(cat "$WORK/pf.pid")) still runs after the script ended"
else
  echo "ok   retrieval-port-forward"
fi

report="$(compgen -G "$WORK/run/probe-report-*.md" | head -n 1 || true)"
if ! grep -qF "1 step(s) failed; report: " "$WORK/retrieval.out"; then
  failed "retrieval-summary: expected '1 step(s) failed; report: <file>'"; sed 's/^/    /' "$WORK/retrieval.out"
elif [[ -z "$report" ]] || ! grep -qxF "| result | **FAIL**: 0 passed, 1 failed, 0 skipped/planned |" "$report"; then
  failed "retrieval-report: expected the result line '| result | **FAIL**: 0 passed, 1 failed, 0 skipped/planned |'"
  sed 's/^/    /' "${report:-/dev/null}"
else
  echo "ok   retrieval-summary and report result line"
fi

# deploy on a new broker: 30-deploy.sh fails because opentsx-stats gets ready only once its topics exist, so the step
# runs kafscale-check, which creates them, and then 30-deploy.sh --restart; the stand-ins record the calls in order
deploy_case() {   # NAME KAFSCALE_CHECK_EXIT: output in NAME.out, calls in NAME.calls, report NAME.md, exit code in rc
  local name="$1" kc_exit="$2"
  rm -rf "$WORK/k3s" "$WORK/topics"
  mkdir -p "$WORK/k3s" "$WORK/run"
  cp "$K3S_DIR/60-probe.sh" "$K3S_DIR/lib.sh" "$K3S_DIR/env.example" "$WORK/k3s/"
  cat > "$WORK/k3s/30-deploy.sh" <<'STANDIN'
#!/usr/bin/env bash
echo "30-deploy.sh $*" >> "$CALLS"
[[ -e "$WORK/topics" ]] && exit 0
echo "  FAIL  deployment.apps/opentsx-stats: not ready after 180s"
exit 1
STANDIN
  cat > "$WORK/k3s/40-demo.sh" <<'STANDIN'
#!/usr/bin/env bash
echo "40-demo.sh $*" >> "$CALLS"
if [[ "$1" == kafscale-check && "$KC_EXIT" == 0 ]]; then touch "$WORK/topics"; fi
exit "$KC_EXIT"
STANDIN
  chmod +x "$WORK/k3s/30-deploy.sh" "$WORK/k3s/40-demo.sh"
  : > "$WORK/$name.calls"
  rc=0
  (cd "$WORK/run" && env -i PATH="$PATH" HOME="$WORK" WORK="$WORK" CALLS="$WORK/$name.calls" KC_EXIT="$kc_exit" \
    KUBECTL=false OPENTSX_ENV_FILE="$WORK/none.env" bash "$WORK/k3s/60-probe.sh" --only deploy \
    --report "$WORK/$name.md") > "$WORK/$name.out" 2>&1 || rc=$?
}

deploy_case deploy-new-broker 0
calls="$(tr '\n' ',' < "$WORK/deploy-new-broker.calls")"
if (( rc != 0 )); then
  failed "deploy-new-broker: exit $rc, expected 0"; sed 's/^/    /' "$WORK/deploy-new-broker.out"
elif [[ "$calls" != "30-deploy.sh ,40-demo.sh kafscale-check,30-deploy.sh --restart," ]]; then
  failed "deploy-new-broker: calls $calls, expected 30-deploy.sh, 40-demo.sh kafscale-check, 30-deploy.sh --restart"
elif ! grep -qF "| 1 | \`deploy\` | PASS |" "$WORK/deploy-new-broker.md"; then
  failed "deploy-new-broker: step deploy is not PASS in the report"; sed 's/^/    /' "$WORK/deploy-new-broker.md"
else
  echo "ok   deploy-new-broker"
fi

deploy_case deploy-check-fails 1
calls="$(tr '\n' ',' < "$WORK/deploy-check-fails.calls")"
if (( rc != 1 )); then
  failed "deploy-check-fails: exit $rc, expected 1"; sed 's/^/    /' "$WORK/deploy-check-fails.out"
elif [[ "$calls" != "30-deploy.sh ,40-demo.sh kafscale-check," ]]; then
  failed "deploy-check-fails: calls $calls, expected 30-deploy.sh, 40-demo.sh kafscale-check"
elif ! grep -qF "| 1 | \`deploy\` | FAIL |" "$WORK/deploy-check-fails.md"; then
  failed "deploy-check-fails: step deploy is not FAIL in the report"; sed 's/^/    /' "$WORK/deploy-check-fails.md"
else
  echo "ok   deploy-check-fails"
fi

# messages and report lines use a colon or a semicolon, not a spaced hyphen as a dash
dashes="$(sed -e 's/[$](([^)]*))//g' "$K3S_DIR/60-probe.sh" \
  | grep -nE '^[^#]*\<(info|ok|warn|fail|note|die|usage_error|echo)\>[^#]*"[^"]* - ' || true)"
if [[ -n "$dashes" ]]; then
  failed "message-dashes: spaced hyphens in 60-probe.sh messages"; printf '%s\n' "$dashes" | sed 's/^/    /'
else
  echo "ok   message-dashes"
fi

if (( failures )); then echo "$failures failed"; exit 1; fi
echo "all passed"
