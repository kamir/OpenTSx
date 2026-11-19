#!/usr/bin/env bash
# Tests the usage errors of deploy/k3s/60-probe.sh: each exits 2 with its message before any step runs, --help
# documents the exit codes, and the port-forward of the retrieval step ends when the step gives up. Needs no kubectl,
# no cluster and no network: stand-ins for kubectl, curl and sleep take their place.
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

if (( failures )); then echo "$failures failed"; exit 1; fi
echo "all passed"
