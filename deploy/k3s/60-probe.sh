#!/usr/bin/env bash
# shellcheck disable=SC2317  # the step_* functions are called indirectly ("step_$name")
# shellcheck disable=SC2015  # ok/warn/fail always return 0, so "check && ok || warn" is a safe if-then-else
# Local trial run ("probe"): installs OpenTSx on k3s, runs every example and writes a PASS/FAIL report.
#
#   deploy/k3s/60-probe.sh [--local-kafscale [--kafscale-host IP]] [--skip-build] [--online] [--keep-going]
#                          [--only s1,s2] [--skip s1,s2] [--report FILE] [--verbose] [--dry-run]
#
#   --local-kafscale   start KafScale on this host (docker compose -f docker-compose.kafscale.yml up -d) so that it
#                      ADVERTISES this host's LAN IP (KAFSCALE_ADVERTISED_HOST), and use <ip>:9092 with
#                      KAFSCALE_NETWORK=direct. Without it the configured KAFSCALE_BOOTSTRAP (deploy/k3s/.env) is used.
#   --kafscale-host IP the address to advertise and to use (default: source IP of this host's default route)
#   --skip-build       no Maven/image build and no image import (the images are already in k3s' containerd)
#   --online           the DWD step fetches live MOSMIX data from opendata.dwd.de (default: the offline fixture in the
#                      image, no internet needed)
#   --keep-going       continue after a failed kafscale-local/prereqs/build/import/configure/deploy step
#                      (default: the remaining steps are reported as SKIP)
#   --only, --skip     run only / leave out these steps (comma-separated names from the list below)
#   --report FILE      report file (default ./probe-report-<UTC time>.md; step logs in ./probe-logs-<UTC time>/)
#   --verbose          also show the step output on the terminal (it always goes to the step log)
#   --dry-run          print the plan, validate the configuration and render the manifests; changes nothing
#
# Steps, in this order:
#   kafscale-local  (only with --local-kafscale) start KafScale, wait for <ip>:9092
#   prereqs         00-prereqs.sh (tools, cluster, KafScale reachable from this host)
#   build, import   10-build.sh (--with-s3 when LAKE=true), 11-import-images.sh   (skipped by --skip-build)
#   configure       20-configure.sh, adding MODBUS_SIM=true (and the KafScale address with --local-kafscale);
#                   these values are written to deploy/k3s/.env like any other configure run
#   deploy          30-deploy.sh
#   kafscale-check  40-demo.sh kafscale-check (bootstrap + advertised addresses from inside the cluster, topics)
#   windpark-sim    40-demo.sh windpark-sim (one sealed bucket on KafScale; restarts the stats processor)
#   series-stats    the stats processor wrote tsx.<domain>.series-stats.v2 records (kafscale-check, up to 5 min)
#   metadata, twin, house, boat, studio-export   the Jobs of 40-demo.sh
#   retrieval       GET /v1/health and /v1/episodes through a port-forward
#   studio          GET / through a port-forward: HTTP 200 and a non-empty page
#   dwd             40-demo.sh dwd (offline fixture) or dwd-online (--online)
#   dwd-forward     one run of the CronJob opentsx-dwd-forward (SKIP unless DWD_FORWARD=true)
#   modbus          simulator opentsx-modbus-sim + 40-demo.sh modbus (poller -> episodes file on the data PVC)
#   status          50-status.sh, for the report (never fails)
#
# Exit code 0 when no step failed, 1 otherwise. Idempotent: every step can be repeated (Jobs are recreated, compose
# and kubectl apply converge). Teardown is separate: 90-teardown.sh (and docker compose ... down for KafScale).
set -euo pipefail
# shellcheck source=deploy/k3s/lib.sh
source "$(dirname "$0")/lib.sh"

ALL_STEPS=(kafscale-local prereqs build import configure deploy kafscale-check windpark-sim series-stats metadata twin
  house boat studio-export retrieval studio dwd dwd-forward modbus status)
CRITICAL=" kafscale-local prereqs build import configure deploy "
declare -A NEEDS=([windpark-sim]=kafscale-check [series-stats]=windpark-sim)

LOCAL_KS=false; KS_HOST=""; SKIP_BUILD=false; ONLINE=false; KEEP_GOING=false; VERBOSE=false; DRY=false
ONLY=""; SKIPLIST=""; REPORT=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --local-kafscale) LOCAL_KS=true; shift ;;
    --kafscale-host) KS_HOST="${2:?--kafscale-host needs an IP address}"; shift 2 ;;
    --skip-build) SKIP_BUILD=true; shift ;;
    --online) ONLINE=true; shift ;;
    --keep-going) KEEP_GOING=true; shift ;;
    --only) ONLY="${2:?--only needs step names}"; shift 2 ;;
    --skip) SKIPLIST="${2:?--skip needs step names}"; shift 2 ;;
    --report) REPORT="${2:?--report needs a file name}"; shift 2 ;;
    -v|--verbose) VERBOSE=true; shift ;;
    --dry-run) DRY=true; shift ;;
    -h|--help) usage 0 ;;
    *) die "unknown option $1 (see --help)" ;;
  esac
done
for s in ${ONLY//,/ } ${SKIPLIST//,/ }; do
  [[ " ${ALL_STEPS[*]} " == *" $s "* ]] || die "unknown step '$s' (steps: ${ALL_STEPS[*]})"
done
[[ -z "$KS_HOST" ]] || $LOCAL_KS || die "--kafscale-host only makes sense with --local-kafscale"

STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
REPORT="${REPORT:-$PWD/probe-report-$STAMP.md}"
LOGDIR="${REPORT%.md}"; LOGDIR="$(dirname "$LOGDIR")/probe-logs-$STAMP"
PF_PIDS=()
cleanup() { local p; for p in ${PF_PIDS[@]+"${PF_PIDS[@]}"}; do kill "$p" 2>/dev/null || true; done; }
trap cleanup EXIT

# ---- configuration -------------------------------------------------------------------------------------------------
detect_host_ip() {   # source address of the default route = the address other machines and the pods use for this host
  local ip=""
  if have ip; then
    ip="$(ip -4 route get 1.1.1.1 2>/dev/null | awk '{for (i = 1; i < NF; i++) if ($i == "src") { print $(i + 1); exit }}')"
  fi
  if [[ -z "$ip" ]] && have hostname; then ip="$(hostname -I 2>/dev/null | awk '{print $1}')"; fi
  # macOS/BSD have neither iproute2 nor "hostname -I": take the interface of the default route, then its IPv4 address
  if [[ -z "$ip" ]] && have route; then
    local dev
    dev="$(route -n get 1.1.1.1 2>/dev/null | awk '/interface:/ { print $2; exit }')"
    if [[ -n "$dev" ]]; then
      have ipconfig && ip="$(ipconfig getifaddr "$dev" 2>/dev/null)"
      if [[ -z "$ip" ]] && have ifconfig; then
        ip="$(ifconfig "$dev" 2>/dev/null | awk '/inet /{ print $2; exit }')"
      fi
    fi
  fi
  echo "$ip"
}

compose_cmd() {   # prints the compose command: "docker compose" (v2 plugin) or docker-compose (v1)
  if have docker && docker compose version >/dev/null 2>&1; then echo "docker compose"
  elif have docker-compose; then echo "docker-compose"
  else return 1; fi
}

load_env
# the probe's own settings travel as environment variables: they win over .env in every script it calls (lib.sh)
export MODBUS_SIM=true
if $LOCAL_KS; then
  [[ -n "$KS_HOST" ]] || KS_HOST="$(detect_host_ip)"
  export KAFSCALE_BOOTSTRAP="${KS_HOST:-unknown}:9092" KAFSCALE_NETWORK=direct
fi
load_env   # again, with the exported values
DWD_DEMO=dwd; $ONLINE && DWD_DEMO=dwd-online

selected() {   # step -> 0 if it should run
  local s="$1"
  if [[ "$s" == "kafscale-local" ]] && ! $LOCAL_KS; then return 1; fi
  if [[ "$s" == "build" || "$s" == "import" ]] && $SKIP_BUILD; then return 1; fi
  if [[ -n "$ONLY" && ",$ONLY," != *",$s,"* ]]; then return 1; fi
  if [[ -n "$SKIPLIST" && ",$SKIPLIST," == *",$s,"* ]]; then return 1; fi
  return 0
}

# ---- step implementations (stdout/stderr go to the step log; NOTE file = one-line note for the report) ---------------
note() { echo "$*" > "$LOGDIR/.note"; }
demo() { "$K3S_DIR/40-demo.sh" "$@"; }

step_kafscale-local() {
  [[ -n "$KS_HOST" ]] || { note "could not detect this host's IP - pass --kafscale-host <ip>"; return 1; }
  if [[ "$KS_HOST" =~ ^(127\.|localhost) ]]; then note "$KS_HOST is loopback: pods cannot reach it"; return 1; fi
  local -a compose
  read -r -a compose <<< "$(compose_cmd)" || true
  (( ${#compose[@]} )) || { note "docker compose (or docker-compose) not found"; return 1; }
  echo "advertised host: $KS_HOST (detected from the default route; override with --kafscale-host)"
  echo "+ KAFSCALE_ADVERTISED_HOST=$KS_HOST ${compose[*]} -f docker-compose.kafscale.yml up -d"
  # up -d recreates the broker when KAFSCALE_ADVERTISED_HOST changed (e.g. an earlier start advertising localhost)
  KAFSCALE_ADVERTISED_HOST="$KS_HOST" "${compose[@]}" -f "$ROOT/docker-compose.kafscale.yml" up -d || {
    note "docker compose up failed (docker daemon running? permissions?)"; return 1; }
  "${compose[@]}" -f "$ROOT/docker-compose.kafscale.yml" ps || true
  local i
  for i in $(seq 1 45); do
    if tcp_check "$KS_HOST:9092" 2; then
      echo "KafScale broker $KS_HOST:9092 reachable after ~$((i * 2)) s"
      note "KafScale at $KS_HOST:9092, advertising $KS_HOST"
      return 0
    fi
    sleep 2
  done
  "${compose[@]}" -f "$ROOT/docker-compose.kafscale.yml" logs --tail 40 kafscale-broker || true
  note "broker not reachable on $KS_HOST:9092 after 90 s"
  return 1
}

step_prereqs() {
  local -a a=()
  $SKIP_BUILD && a+=(--no-build)
  "$K3S_DIR/00-prereqs.sh" ${a[@]+"${a[@]}"}
}

step_build() {
  local -a a=()
  is_true "$LAKE" && a+=(--with-s3)
  "$K3S_DIR/10-build.sh" ${a[@]+"${a[@]}"}
}

step_import() { "$K3S_DIR/11-import-images.sh"; }

step_configure() {
  local -a a=(MODBUS_SIM=true)
  $LOCAL_KS && a+=("KAFSCALE_BOOTSTRAP=$KAFSCALE_BOOTSTRAP" KAFSCALE_NETWORK=direct)
  note "${a[*]}"
  "$K3S_DIR/20-configure.sh" "${a[@]}"
}

step_deploy() { "$K3S_DIR/30-deploy.sh"; }

step_kafscale-check() { demo kafscale-check; }

step_windpark-sim() { demo windpark-sim; }

series_stats_count() {   # runs kafscale-check, prints the record count of tsx.<domain>.series-stats.v2
  demo kafscale-check > "$LOGDIR/.kc" 2>&1 || true
  cat "$LOGDIR/.kc"
  awk -v t="tsx.$OPENTSX_DOMAIN.series-stats.v2" '$1 == t { gsub(/,/, "", $4); print $4 }' "$LOGDIR/.kc" | tail -n 1 > "$LOGDIR/.count"
}

step_series-stats() {
  local n="" start=$SECONDS limit="${PROBE_STATS_WAIT:-300}"
  while :; do
    series_stats_count
    n="$(cat "$LOGDIR/.count")"
    echo "series-stats records: ${n:-?} (after $((SECONDS - start)) s)"
    if [[ "$n" =~ ^[0-9]+$ ]] && (( n > 0 )); then break; fi
    (( SECONDS - start >= limit )) && break
    sleep 30
  done
  echo "--- checkpoint"
  kns exec deployment/opentsx-stats -- ls -l /state 2>&1 || true
  if [[ "$n" =~ ^[0-9]+$ ]] && (( n > 0 )); then note "$n records in tsx.$OPENTSX_DOMAIN.series-stats.v2"; return 0; fi
  note "no series-stats records after ${limit} s (count: ${n:-unknown})"
  return 1
}

step_metadata() { demo metadata; }
step_twin() { demo twin; }
step_house() { demo house; }
step_boat() { demo boat; }
step_studio-export() { demo studio-export; }

port_forward() {   # svc local-port remote-port url -> 0 when url answers within 30 s
  local svc="$1" lp="$2" rp="$3" url="$4" pid
  kns port-forward "svc/$svc" "$lp:$rp" > "$LOGDIR/.pf-$svc" 2>&1 & pid=$!
  PF_PIDS+=("$pid")
  for _ in $(seq 1 30); do
    curl -s -o /dev/null --max-time 3 "$url" && return 0
    kill -0 "$pid" 2>/dev/null || break
    sleep 1
  done
  cat "$LOGDIR/.pf-$svc"
  return 1
}

stop_forwards() { cleanup; PF_PIDS=(); }

step_retrieval() {
  local port="${PROBE_RETRIEVAL_PORT:-18080}" q body rc=0
  port_forward opentsx-retrieval "$port" 8080 "http://localhost:$port/v1/health" \
    || { note "port-forward to svc/opentsx-retrieval failed"; stop_forwards; return 1; }
  body="$(curl -s --max-time 10 "http://localhost:$port/v1/health" || true)"
  echo "GET /v1/health -> $body"
  [[ "$body" == *'"ok"'* ]] || { note "/v1/health answered '${body:0:80}'"; rc=1; }
  if is_true "$LAKE"; then q="metric=wind.met.wind_speed&tag.turbine=T07&limit=1"
  elif [[ "$RETRIEVAL_SOURCE" == kafscale ]]; then q="limit=1"; else q="metric=demo.signal&limit=1"; fi
  body="$(curl -s --max-time 30 "http://localhost:$port/v1/episodes?$q" || true)"
  echo "GET /v1/episodes?$q -> ${body:0:400}"
  if (( rc == 0 )); then
    if [[ "$body" == '[{'* ]]; then note "health ok, /v1/episodes returned episodes"
    else note "/v1/episodes?$q returned no episodes ('${body:0:60}')"; rc=1; fi
  fi
  stop_forwards
  return "$rc"
}

step_studio() {
  local port="${PROBE_STUDIO_PORT:-18088}" code size
  port_forward opentsx-studio "$port" 80 "http://localhost:$port/" \
    || { note "port-forward to svc/opentsx-studio failed"; stop_forwards; return 1; }
  code="$(curl -s -o "$LOGDIR/studio.html" -w '%{http_code}' --max-time 30 "http://localhost:$port/" || true)"
  stop_forwards
  size="$(wc -c < "$LOGDIR/studio.html" 2>/dev/null || echo 0)"
  echo "GET / -> HTTP $code, $size bytes"
  if [[ "$code" != "200" ]]; then note "HTTP $code"; return 1; fi
  if (( size < 100000 )) || ! grep -qi '<html' "$LOGDIR/studio.html"; then
    note "HTTP 200 but the page has only $size bytes (expected ~1.2 MB): blank Studio"; return 1
  fi
  note "HTTP 200, $size bytes"
}

step_dwd() {
  demo "$DWD_DEMO" > "$LOGDIR/.dwd" 2>&1; local rc=$?
  cat "$LOGDIR/.dwd"
  (( rc == 0 )) || { note "40-demo.sh $DWD_DEMO failed"; return 1; }
  grep -q 'wrote /data/dwd/demo-mosmix.csv' "$LOGDIR/.dwd" || { note "no CSV written"; return 1; }
  grep -q 'origin of weather.t_out_c: FORECAST' "$LOGDIR/.dwd" || { note "house twin did not bind the forecast"; return 1; }
  if $ONLINE; then note "live MOSMIX fetched, CSV on the data PVC, house twin on FORECAST"
  else note "offline fixture, CSV on the data PVC, house twin on FORECAST"; fi
}

step_dwd-forward() {
  if ! kns get cronjob/opentsx-dwd-forward >/dev/null 2>&1; then
    note "CronJob not deployed (DWD_FORWARD=false)"; return 3
  fi
  demo dwd-forward
}

step_modbus() {
  demo modbus > "$LOGDIR/.modbus" 2>&1; local rc=$?
  cat "$LOGDIR/.modbus"
  (( rc == 0 )) || { note "poller failed (see log: timeouts, connection refused, exception responses?)"; return 1; }
  local windows missing
  windows="$(grep -c 'window done' "$LOGDIR/.modbus" || true)"
  missing="$(grep 'window done' "$LOGDIR/.modbus" | grep -vc ', 0 missing' || true)"
  (( windows > 0 )) || { note "no episode window closed"; return 1; }
  (( missing == 0 )) || { note "$missing of $windows windows had missing values"; return 1; }
  note "$windows episode windows written to /data/modbus (no KafScale sink yet)"
}

step_status() { "$K3S_DIR/50-status.sh" || true; note "see log"; return 0; }

# ---- hints (troubleshooting.md / operations.md) for a failed step ----------------------------------------------------
hints() {   # step log -> markdown bullet list
  local step="$1" log="$2"
  local -a h=()
  grep -qE 'ErrImageNeverPull|ImagePullBackOff|ErrImagePull' "$log" 2>/dev/null \
    && h+=("image not in k3s' containerd: \`11-import-images.sh\` ([troubleshooting](docs/devops/troubleshooting.md#images-and-pods))")
  grep -qE 'advertised .*NOT reachable' "$log" 2>/dev/null \
    && h+=("the broker advertises an address the pods cannot reach: \`--local-kafscale\` (advertises the LAN IP) or \`KAFSCALE_NETWORK=hostnetwork\` ([runbook R1](docs/devops/operations.md#r1-kafscale-unreachable-or-wrong-advertised-address))")
  grep -qE 'bootstrap .*NOT reachable' "$log" 2>/dev/null \
    && h+=("bootstrap not reachable: routing/firewall from the pod CIDR 10.42.0.0/16 to the broker port ([troubleshooting](docs/devops/troubleshooting.md#kafscale))")
  grep -qE 'OOMKilled' "$log" 2>/dev/null && h+=("memory limit too small: see [sizing plan](docs/devops/sizing-plan.md)")
  grep -qE 'cluster not reachable|kubectl not usable' "$log" 2>/dev/null \
    && h+=("\`export KUBECONFIG=/etc/rancher/k3s/k3s.yaml\` or \`20-configure.sh KUBECTL=\"sudo k3s kubectl\"\`")
  grep -qE 'Cannot connect to the Docker daemon|docker.sock' "$log" 2>/dev/null \
    && h+=("docker daemon not running or not accessible (\`sudo systemctl start docker\`, group \`docker\`)")
  grep -qE 'S3FileIO' "$log" 2>/dev/null && h+=("\`10-build.sh --with-s3\` for the lake profile")
  grep -qE 'is loopback' "$log" 2>/dev/null && h+=("use the host's LAN IP (\`--local-kafscale\` does this) or \`KAFSCALE_NETWORK=hostnetwork\`")
  case "$step" in
    kafscale-local) h+=("check \`docker compose -f docker-compose.kafscale.yml ps\` and \`... logs kafscale-broker\`; a host firewall must allow the pod CIDR to port 9092") ;;
    prereqs) h+=("fix the items marked FAIL in the log; \`--skip-build\` on a node without JDK/Maven") ;;
    build) h+=("\`mvn -q install -DskipTests\` must pass first; a missing image builder or daemon is reported by \`00-prereqs.sh\`") ;;
    deploy) h+=("\`50-status.sh\`, \`kubectl -n $OPENTSX_NS describe pod <pod>\` ([troubleshooting](docs/devops/troubleshooting.md#images-and-pods))") ;;
    kafscale-check|windpark-sim) h+=("[setup plan, step 3](docs/devops/setup-plan.md#step-3-network-path-to-kafscale) and [runbook R1](docs/devops/operations.md#r1-kafscale-unreachable-or-wrong-advertised-address)") ;;
    series-stats) h+=("processor started before the topics existed: \`kubectl -n $OPENTSX_NS rollout restart deploy/opentsx-stats\` ([runbook R2](docs/devops/operations.md#r2-statistics-processor-stuck-or-crash-looping))") ;;
    retrieval) h+=("\`kubectl -n $OPENTSX_NS logs deploy/opentsx-retrieval\`; lake profile without data: run windpark-sim with LAKE=true first ([troubleshooting](docs/devops/troubleshooting.md#studio-and-retrieval))") ;;
    studio) h+=("[runbook R8](docs/devops/operations.md#r8-studio-blank-or-not-reachable): rebuild + re-import the studio image, \`30-deploy.sh --restart\`") ;;
    dwd|dwd-forward) h+=("[runbook R6](docs/devops/operations.md#r6-dwd-fetch-failing): offline mode needs the fixture in the image (\`10-build.sh\` re-stages); online mode needs HTTPS from the pods to opendata.dwd.de") ;;
    modbus) h+=("[runbook R7](docs/devops/operations.md#r7-modbus-timeouts-or-exception-responses): \`kubectl -n $OPENTSX_NS logs deploy/opentsx-modbus-sim\`; MODBUS_HOST/MODBUS_PORT in \`deploy/k3s/.env\`") ;;
    metadata|twin|house|boat|studio-export) h+=("\`kubectl -n $OPENTSX_NS logs job/opentsx-$step\`; memory: [sizing plan](docs/devops/sizing-plan.md)") ;;
  esac
  local x; for x in "${h[@]}"; do echo "* $x"; done
}

# ---- report --------------------------------------------------------------------------------------------------------
declare -a R_NAME=() R_RESULT=() R_SECS=() R_NOTE=()
fmt_secs() { printf '%d:%02d' $(( $1 / 60 )) $(( $1 % 60 )); }
md_cell() { local s="${1//|/\\|}"; echo "${s//$'\n'/ }"; }

write_report() {
  local passed=0 failed=0 skipped=0 i overall
  for i in "${!R_RESULT[@]}"; do
    case "${R_RESULT[$i]}" in PASS) passed=$((passed + 1)) ;; FAIL) failed=$((failed + 1)) ;; *) skipped=$((skipped + 1)) ;; esac
  done
  overall="PASS"; (( failed )) && overall="FAIL"; $DRY && overall="DRY RUN (nothing executed)"
  local git_rev
  git_rev="$(git -C "$ROOT" rev-parse --abbrev-ref HEAD 2>/dev/null || echo '?')@$(git -C "$ROOT" rev-parse --short HEAD 2>/dev/null || echo '?')"
  [[ -n "$(git -C "$ROOT" status --porcelain 2>/dev/null)" ]] && git_rev="$git_rev (uncommitted changes)"
  {
    echo "# OpenTSx probe report"
    echo
    echo "| | |"
    echo "|---|---|"
    echo "| result | **$overall** - $passed passed, $failed failed, $skipped skipped/planned |"
    echo "| started | $STAMP |"
    echo "| host | $(hostname 2>/dev/null || echo '?') ($(uname -sm 2>/dev/null || echo '?')) |"
    echo "| code | \`$git_rev\` |"
    echo "| KafScale | \`$KAFSCALE_BOOTSTRAP\` ($KAFSCALE_NETWORK$($LOCAL_KS && echo ", started locally, advertising $KS_HOST")) |"
    echo "| namespace / domain | \`$OPENTSX_NS\` / \`$OPENTSX_DOMAIN\` |"
    echo "| images | \`$TOOLS_IMAGE\`, \`$STUDIO_IMAGE\` |"
    echo "| profiles | lake $LAKE, modbus-sim true (probe), dwd $($ONLINE && echo online || echo offline), dwd-forward $DWD_FORWARD, monitoring $MONITORING |"
    echo "| logs | \`$LOGDIR/\` |"
    echo
    echo "## Steps"
    echo
    echo "| # | Step | Result | Duration | Notes |"
    echo "|---|------|--------|----------|-------|"
    for i in "${!R_NAME[@]}"; do
      echo "| $((i + 1)) | \`${R_NAME[$i]}\` | ${R_RESULT[$i]} | $(fmt_secs "${R_SECS[$i]}") | $(md_cell "${R_NOTE[$i]}") |"
    done
    if (( failed )); then
      echo
      echo "## Failures and hints"
      for i in "${!R_NAME[@]}"; do
        [[ "${R_RESULT[$i]}" == "FAIL" ]] || continue
        local log
        log="$LOGDIR/$(printf '%02d' $((i + 1)))-${R_NAME[$i]}.log"
        echo
        echo "### ${R_NAME[$i]}"
        echo
        hints "${R_NAME[$i]}" "$log"
        echo
        echo "Last lines of \`$log\`:"
        echo
        echo '```text'
        tail -n 25 "$log" 2>/dev/null | sed 's/\x1b\[[0-9;]*m//g'
        echo '```'
      done
    fi
    echo
    echo "## Next"
    echo
    if $DRY; then
      echo "Run the same command without \`--dry-run\`."
    elif (( failed )); then
      echo "Fix the first failed step, then repeat only the remaining steps, e.g. \`deploy/k3s/60-probe.sh --skip-build --only <steps>\`."
      echo "Symptoms and fixes: [troubleshooting](docs/devops/troubleshooting.md), runbooks in [operations](docs/devops/operations.md#runbooks)."
    else
      echo "Everything passed. Studio: \`deploy/k3s/40-demo.sh studio\`; state: \`deploy/k3s/50-status.sh\`;"
      echo "operations (monitoring, backup, runbooks): [docs/devops/operations.md](docs/devops/operations.md)."
    fi
  } > "$REPORT"
}

# ---- dry run -------------------------------------------------------------------------------------------------------
step_command() {   # what a step runs, for the plan
  case "$1" in
    kafscale-local) echo "KAFSCALE_ADVERTISED_HOST=${KS_HOST:-?} $(compose_cmd 2>/dev/null || echo 'docker compose') -f docker-compose.kafscale.yml up -d; wait for ${KS_HOST:-?}:9092" ;;
    prereqs) echo "00-prereqs.sh$($SKIP_BUILD && echo ' --no-build')" ;;
    build) echo "10-build.sh$(is_true "$LAKE" && echo ' --with-s3')" ;;
    import) echo "11-import-images.sh (IMAGE_METHOD=$IMAGE_METHOD)" ;;
    configure) echo "20-configure.sh MODBUS_SIM=true$($LOCAL_KS && echo " KAFSCALE_BOOTSTRAP=$KAFSCALE_BOOTSTRAP KAFSCALE_NETWORK=direct")" ;;
    deploy) echo "30-deploy.sh" ;;
    series-stats) echo "40-demo.sh kafscale-check until tsx.$OPENTSX_DOMAIN.series-stats.v2 > 0 records (max ${PROBE_STATS_WAIT:-300} s)" ;;
    retrieval) echo "port-forward svc/opentsx-retrieval ${PROBE_RETRIEVAL_PORT:-18080}:8080; GET /v1/health, /v1/episodes" ;;
    studio) echo "port-forward svc/opentsx-studio ${PROBE_STUDIO_PORT:-18088}:80; GET / = HTTP 200, > 100 KB" ;;
    dwd) echo "40-demo.sh $DWD_DEMO" ;;
    dwd-forward) echo "40-demo.sh dwd-forward (only if the CronJob exists: DWD_FORWARD=true)" ;;
    modbus) echo "40-demo.sh modbus (simulator opentsx-modbus-sim:1502 -> poller -> /data/modbus/*.jsonl)" ;;
    status) echo "50-status.sh" ;;
    *) echo "40-demo.sh $1" ;;
  esac
}

dry_run() {
  local rc=0 tmp
  info "dry run - plan (report: $REPORT)"
  for s in "${ALL_STEPS[@]}"; do
    if selected "$s"; then printf '  %-15s %s\n' "$s" "$(step_command "$s")"; else printf '  %-15s %s\n' "$s" "(not selected)"; fi
  done
  info "checks"
  if $LOCAL_KS; then
    if [[ -n "$KS_HOST" && ! "$KS_HOST" =~ ^127\. ]]; then ok "host IP for KafScale: $KS_HOST (pods use $KAFSCALE_BOOTSTRAP)"
    else fail "no usable host IP detected (got '${KS_HOST}') - pass --kafscale-host <LAN IP>"; rc=1; fi
    if compose_cmd >/dev/null; then
      local -a compose; read -r -a compose <<< "$(compose_cmd)"
      if KAFSCALE_ADVERTISED_HOST="$KS_HOST" "${compose[@]}" -f "$ROOT/docker-compose.kafscale.yml" config -q 2>/dev/null; then
        ok "docker-compose.kafscale.yml valid (${compose[*]} config)"
      else warn "could not validate docker-compose.kafscale.yml with '${compose[*]} config'"; fi
    else warn "docker compose / docker-compose not found (needed for --local-kafscale)"; fi
  fi
  for t in kubectl curl; do
    if [[ "$t" == kubectl ]]; then kc version --client >/dev/null 2>&1 && ok "kubectl ($KUBECTL)" || warn "kubectl ($KUBECTL) not usable here"
    else have "$t" && ok "$t" || warn "$t missing (needed for the retrieval/studio checks)"; fi
  done
  if ! $SKIP_BUILD; then
    for t in java mvn; do have "$t" && ok "$t" || warn "$t missing (needed for the build; or use --skip-build)"; done
    if have docker || have podman || have nerdctl || have buildah; then ok "image builder present"; else warn "no image builder"; fi
  fi
  have k3s && ok "k3s binary present" || warn "k3s binary not on this host (fine for a remote cluster)"
  # the configuration exactly as the configure step would write it, rendered into a throw-away overlay
  tmp="$(mktemp -d)"
  [[ -f "$ENV_FILE" ]] && cp "$ENV_FILE" "$tmp/.env"
  local ov="$K3S_DIR/overlays/.probe-dryrun-$$"
  local -a cfg=(MODBUS_SIM=true)
  $LOCAL_KS && cfg+=("KAFSCALE_BOOTSTRAP=$KAFSCALE_BOOTSTRAP" KAFSCALE_NETWORK=direct)
  if OPENTSX_ENV_FILE="$tmp/.env" OPENTSX_OVERLAY="$ov" "$K3S_DIR/20-configure.sh" "${cfg[@]}" > "$tmp/configure.log" 2>&1; then
    ok "configuration valid: $(grep -E 'renders:' "$tmp/configure.log" | sed 's/^ *ok *//' || echo 'rendering not checked (no kubectl)')"
    grep -E '^  (namespace|images|modbus-sim)' "$tmp/configure.log" | sed 's/^/    /'
    grep -E 'warn' "$tmp/configure.log" | sed 's/^/    /' || true
  else
    fail "configuration invalid:"; sed 's/^/    /' "$tmp/configure.log"; rc=1
  fi
  rm -rf "$tmp" "$ov"
  for s in "${ALL_STEPS[@]}"; do
    selected "$s" || continue
    R_NAME+=("$s"); R_RESULT+=("PLANNED"); R_SECS+=(0); R_NOTE+=("$(step_command "$s")")
  done
  R_NAME=("config-validation" "${R_NAME[@]}"); R_RESULT=("$( ((rc)) && echo FAIL || echo PASS)" "${R_RESULT[@]}")
  R_SECS=(0 "${R_SECS[@]}"); R_NOTE=("20-configure.sh into a temporary overlay; tools checked" "${R_NOTE[@]}")
  mkdir -p "$LOGDIR"
  write_report
  rmdir "$LOGDIR" 2>/dev/null || true
  info "plan written to $REPORT"
  return "$rc"
}

if $DRY; then dry_run; exit $?; fi

# ---- run -----------------------------------------------------------------------------------------------------------
mkdir -p "$LOGDIR"
total=0; for s in "${ALL_STEPS[@]}"; do selected "$s" && total=$((total + 1)); done
info "OpenTSx probe: $total steps, report $REPORT"
BLOCKED=""
declare -A RESULT=()
n=0
for s in "${ALL_STEPS[@]}"; do
  selected "$s" || continue
  n=$((n + 1))
  log="$LOGDIR/$(printf '%02d' "$n")-$s.log"
  rm -f "$LOGDIR/.note"
  start=$SECONDS
  need="${NEEDS[$s]:-}"
  started=false
  if [[ -n "$BLOCKED" && "$s" != "status" ]]; then
    res=SKIP; echo "blocked by failed step $BLOCKED" > "$LOGDIR/.note"; : > "$log"
  elif [[ -n "$need" && "${RESULT[$need]:-}" == "FAIL" ]] && ! $KEEP_GOING; then
    res=SKIP; echo "needs $need, which failed (--keep-going runs it anyway)" > "$LOGDIR/.note"; : > "$log"
  else
    printf '%s==>%s [%d/%d] %-15s ' "$C_BLD" "$C_OFF" "$n" "$total" "$s"
    started=true
    $VERBOSE && echo
    if $VERBOSE; then
      if "step_$s" > >(tee "$log") 2>&1; then rc=0; else rc=$?; fi
    else
      if "step_$s" > "$log" 2>&1; then rc=0; else rc=$?; fi
    fi
    case "$rc" in 0) res=PASS ;; 3) res=SKIP ;; *) res=FAIL ;; esac
  fi
  secs=$((SECONDS - start))
  RESULT[$s]="$res"
  noteline="$(cat "$LOGDIR/.note" 2>/dev/null || true)"
  if ! $started; then   # skipped without running: print the step name first
    printf '%s==>%s [%d/%d] %-15s ' "$C_BLD" "$C_OFF" "$n" "$total" "$s"
  fi
  case "$res" in
    PASS) echo "${C_GRN}PASS${C_OFF} $(fmt_secs "$secs")${noteline:+  $noteline}" ;;
    SKIP) echo "${C_YEL}SKIP${C_OFF} $noteline" ;;
    FAIL)
      echo "${C_RED}FAIL${C_OFF} $(fmt_secs "$secs")${noteline:+  $noteline}"
      $VERBOSE || tail -n 8 "$log" | sed 's/^/      /'
      if [[ "$CRITICAL" == *" $s "* ]] && ! $KEEP_GOING; then BLOCKED="$s"; fi ;;
  esac
  R_NAME+=("$s"); R_RESULT+=("$res"); R_SECS+=("$secs")
  R_NOTE+=("${noteline:-$(grep -v '^\s*$' "$log" 2>/dev/null | tail -n 1 | sed -e 's/\x1b\[[0-9;]*m//g' -e 's/^ *\(ok\|warn\|FAIL\|==>\) *//' | cut -c1-120)}")
done
rm -f "$LOGDIR"/.note "$LOGDIR"/.kc "$LOGDIR"/.count "$LOGDIR"/.dwd "$LOGDIR"/.modbus "$LOGDIR"/.pf-*
write_report
echo
failed=0; for r in "${R_RESULT[@]}"; do [[ "$r" == FAIL ]] && failed=$((failed + 1)); done
if (( failed )); then
  fail "$failed step(s) failed - report: $REPORT"
  exit 1
fi
info "all steps passed - report: $REPORT"
