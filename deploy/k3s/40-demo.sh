#!/usr/bin/env bash
# Runs a demo in the cluster and shows its output.
#
#   deploy/k3s/40-demo.sh <demo> [-- extra arguments for the command]
#
# Demos (Jobs are recreated on every run; their pods stay 24 h for `kubectl logs`):
#   kafscale-check     bootstrap + advertised broker addresses from inside the cluster; provisions the topics of
#                      OPENTSX_DOMAIN (TopicLayout.provision) and prints record counts per topic
#   windpark-sim       wind farm Ostsee demo (10 turbines x 10 channels, 6 h at 1 Hz) -> one sealed bucket on KafScale
#                      (+ Iceberg with LAKE=true); restarts the stats processor so it sees new partitions
#   stats              shows the statistics processor's state: logs, checkpoint, record counts (series-stats topic)
#   metadata           OpenMetadata requests as a dry run (JSON files on the data PVC)
#   metadata-publish   publish to OpenMetadata (needs OM_URL, and OPENMETADATA_JWT exported or in deploy/k3s/.env;
#                      see 20-configure.sh --help)
#   twin | house | boat   the three digital-twin scenarios (H2 chain, house Erzgebirge, boat Etap 22)
#   studio-export      runs the Studio export inside the cluster (size + checksum of the scenario JSON)
#   retrieval          queries the retrieval REST API through a temporary port-forward
#   studio             port-forwards the Studio page to http://localhost:8088 (Ctrl-C to stop)
#   dwd                DWD MOSMIX -> v2 series + CSV on the data PVC (/data/dwd), house twin over the forecast;
#                      OFFLINE: reads the MOSMIX fixture DWD_FIXTURE in the image (no internet needed)
#   dwd-online         the same, fetching the first station of DWD_STATIONS from opendata.dwd.de
#   dwd-forward        triggers the CronJob opentsx-dwd-forward once (needs DWD_FORWARD=true)
#   modbus             polls the Modbus device MODBUS_HOST:MODBUS_PORT (default: the simulator, MODBUS_SIM=true) for
#                      30 s and writes 10 s episodes to /data/modbus/episodes-<time>.jsonl (no KafScale sink yet)
#   all                kafscale-check, windpark-sim, stats, metadata, twin, house, boat, studio-export, dwd,
#                      modbus (when a device is configured), retrieval
#
# Extra arguments go to the command via OPENTSX_EXTRA_ARGS, e.g. with private data from the Secret opentsx-private:
#   kubectl -n opentsx create secret generic opentsx-private --from-file=abrechnungen.csv=config/private/abrechnungen.csv
#   deploy/k3s/40-demo.sh house -- --calibrate /private/abrechnungen.csv
#   deploy/k3s/40-demo.sh house -- --calibrate /opt/opentsx/share/twin/abrechnungen-vorlage.csv   # template, fake rows
# An option after -- wins over the variable a Job sets for it, such as 40-demo.sh modbus -- --interval PT2S. An option
# that the Job command itself names cannot be given again (deploy/k3s/README.md, "Pass settings to a command").
#
# Exit codes:
#   0  the demo ran
#   1  the demo failed: a Job did not complete, a request failed, or OM_URL or OPENMETADATA_JWT is missing
#   2  usage error: no demo or an unknown one, an extra argument without --, an extra argument for stats, retrieval,
#      studio, dwd-forward or all, which take none, or a line of deploy/k3s/.env that is not a comment or KEY=VALUE
#      with a known key
set -euo pipefail
# shellcheck source=deploy/k3s/lib.sh
source "$(dirname "$0")/lib.sh"

DEMOS=(kafscale-check windpark-sim stats metadata metadata-publish twin house boat studio-export retrieval studio dwd
  dwd-online dwd-forward modbus modbus-poll all)
[[ $# -ge 1 ]] || usage_error "demo: missing; expected 40-demo.sh <demo> [-- ARG ...], see 40-demo.sh --help"
DEMO="$1"; shift
[[ "$DEMO" == "-h" || "$DEMO" == "--help" ]] && usage 0
[[ "$DEMO" =~ ^[a-z-]+$ && " ${DEMOS[*]} " == *" $DEMO "* ]] \
  || usage_error "$DEMO: unknown demo; expected one of ${DEMOS[*]}"
EXTRA=""
if [[ $# -gt 0 ]]; then
  [[ "$1" == "--" ]] \
    || usage_error "$1: unexpected argument; extra arguments follow --, such as 40-demo.sh house -- --calibrate /private/bills.csv"
  shift; EXTRA="$*"
  if [[ -n "$EXTRA" ]]; then
    case "$DEMO" in
      stats|retrieval|studio|dwd-forward|all) usage_error "$DEMO: takes no extra arguments; drop the arguments after --" ;;
    esac
  fi
fi
load_env
require_overlay
JOB_TIMEOUT="${JOB_TIMEOUT:-1800}"
PF_PIDS=()
stop_forwards() { local p; for p in ${PF_PIDS[@]+"${PF_PIDS[@]}"}; do kill "$p" 2>/dev/null || true; done; }
trap stop_forwards EXIT

render_job() {   # name -> the Job manifest from overlays/local/jobs
  kc kustomize "$OVERLAY/jobs" | awk -v want="  name: opentsx-$1" '
    function flush() { if (index("\n" doc, "\n" want "\n") > 0) { printf "%s", doc; found = 1 } doc = "" }
    /^---$/ { flush(); next }
    { doc = doc $0 "\n" }
    END { flush(); exit found ? 0 : 1 }'
}

run_job() {   # name [extra args] [ENV=value ...]
  local name="$1" extra="${2:-}" job="opentsx-$1" manifest phase reason start
  shift $(( $# >= 2 ? 2 : 1 ))
  manifest="$(render_job "$name")" || die "no Job opentsx-$name in deploy/k3s/jobs"
  if [[ -n "$extra" ]]; then
    manifest="$(kc set env --local -f - -o yaml "OPENTSX_EXTRA_ARGS=$extra" <<< "$manifest")"
  fi
  if [[ $# -gt 0 ]]; then
    manifest="$(kc set env --local -f - -o yaml "$@" <<< "$manifest")"
  fi
  info "job $job${extra:+ (extra args: $extra)}"
  kns delete job "$job" --ignore-not-found --wait=true >/dev/null
  kc apply -f - <<< "$manifest" >/dev/null
  start=$SECONDS
  while :; do   # wait for the pod to start (or fail to)
    phase="$(kns get pod -l "job-name=$job" -o 'jsonpath={.items[0].status.phase}' 2>/dev/null || true)"
    reason="$(kns get pod -l "job-name=$job" -o 'jsonpath={.items[0].status.containerStatuses[0].state.waiting.reason}' 2>/dev/null || true)"
    case "$reason" in
      ErrImageNeverPull|ImagePullBackOff|ErrImagePull|InvalidImageName)
        die "$job: $reason; is the image $TOOLS_IMAGE in k3s' containerd? run 10-build.sh and 11-import-images.sh" ;;
      CreateContainerConfigError) die "$job: $reason: $(kns get pod -l "job-name=$job" -o 'jsonpath={.items[0].status.containerStatuses[0].state.waiting.message}')" ;;
    esac
    [[ "$phase" == "Running" || "$phase" == "Succeeded" || "$phase" == "Failed" ]] && break
    (( SECONDS - start > 300 )) && die "$job: pod not started after 300 s (phase '${phase:-none}', $reason); see 50-status.sh"
    sleep 2
  done
  kns logs -f "job/$job" --all-containers=true || true
  local cond=""
  while (( SECONDS - start < JOB_TIMEOUT )); do   # Complete or Failed (a retried pod's logs: kubectl logs job/...)
    cond="$(kns get job "$job" -o 'jsonpath={.status.conditions[?(@.status=="True")].type}' 2>/dev/null || true)"
    [[ "$cond" == *Complete* || "$cond" == *Failed* ]] && break
    sleep 2
  done
  if [[ "$cond" == *Complete* ]]; then
    ok "$job completed"
  else
    fail "$job did not complete (${cond:-timeout}); see ${KUBECTL} -n $OPENTSX_NS describe job $job"
    return 1
  fi
}

restart_stats() {
  info "restarting opentsx-stats so it assigns the (new) partitions"
  kns rollout restart deployment/opentsx-stats >/dev/null
  kns rollout status deployment/opentsx-stats --timeout=180s
}

demo_stats() {
  info "statistics processor"
  kns get deployment/opentsx-stats
  kns logs deployment/opentsx-stats --tail=20 2>/dev/null || true
  kns exec deployment/opentsx-stats -- ls -l /state 2>/dev/null || warn "no checkpoint yet (written every 30 s)"
  run_job kafscale-check   # record counts, incl. tsx.<domain>.series-stats.v2
}

# fetch URL: the body on stdout; otherwise a FAIL line with the URL and curl's exit code or the HTTP status
fetch() {
  local url="$1" out code
  out="$(curl -sS -w '\n%{http_code}' "$url")" \
    || { fail "GET $url: no answer (curl exit $?); check the retrieval server with deploy/k3s/50-status.sh"; return 1; }
  code="${out##*$'\n'}"
  [[ "$code" == 200 ]] || { fail "GET $url: HTTP $code; check the retrieval server with deploy/k3s/50-status.sh"; return 1; }
  printf '%s\n' "${out%$'\n'*}"
}

demo_retrieval() {
  local base="http://localhost:18080" q body
  info "retrieval API via port-forward $base"
  kns_background port-forward svc/opentsx-retrieval 18080:8080 >/dev/null 2>&1
  PF_PIDS+=("$!")
  for _ in $(seq 1 30); do curl -sf "$base/v1/health" >/dev/null 2>&1 && break; sleep 1; done
  fetch "$base/v1/health" || return 1
  if is_true "$LAKE"; then q="metric=wind.met.wind_speed&tag.turbine=T07&limit=1"; else q="metric=demo.signal&limit=1"; fi
  echo "GET /v1/episodes?$q"
  body="$(fetch "$base/v1/episodes?$q")" || return 1
  echo "${body:0:400} ..."
}

modbus_device() {   # 0 when a device is configured: the simulator is deployed, or MODBUS_HOST points elsewhere
  [[ "$MODBUS_HOST" != "opentsx-modbus-sim" ]] && return 0
  kns get deployment/opentsx-modbus-sim >/dev/null 2>&1
}

demo_modbus() {
  if [[ "$MODBUS_HOST" == "opentsx-modbus-sim" ]]; then
    modbus_device || die "simulator not deployed; run 20-configure.sh MODBUS_SIM=true && 30-deploy.sh (or set MODBUS_HOST=<converter>)"
    info "Modbus simulator opentsx-modbus-sim:$MODBUS_PORT (example register map)"
    kns rollout status deployment/opentsx-modbus-sim --timeout=120s
  fi
  run_job modbus-poll "$EXTRA"
}

demo_dwd_forward() {
  kns get cronjob/opentsx-dwd-forward >/dev/null 2>&1 \
    || die "CronJob opentsx-dwd-forward not deployed; run 20-configure.sh DWD_FORWARD=true && 30-deploy.sh"
  local job="opentsx-dwd-forward-manual"
  info "job $job (from cronjob/opentsx-dwd-forward, DWD_MODE=$DWD_MODE)"
  kns delete job "$job" --ignore-not-found --wait=true >/dev/null
  kns create job "$job" --from=cronjob/opentsx-dwd-forward >/dev/null
  if kns wait --for=condition=complete "job/$job" --timeout="${JOB_TIMEOUT}s" >/dev/null 2>&1; then
    kns logs "job/$job" || true
    ok "$job completed"
  else
    kns logs "job/$job" 2>/dev/null || true
    fail "$job did not complete; see ${KUBECTL} -n $OPENTSX_NS describe job $job"
    return 1
  fi
}

case "$DEMO" in
  kafscale-check|metadata|metadata-publish|twin|house|boat|studio-export)
    if [[ "$DEMO" == "metadata-publish" ]]; then
      [[ -n "$OM_URL" ]] || die "OM_URL: not set; run read -rs OPENMETADATA_JWT && export OPENMETADATA_JWT, then 20-configure.sh OM_URL=http://<host>:8585/api and 30-deploy.sh"
      [[ -n "$OPENMETADATA_JWT" ]] || die "OPENMETADATA_JWT: not set; run read -rs OPENMETADATA_JWT && export OPENMETADATA_JWT, then 20-configure.sh and 30-deploy.sh"
    fi
    run_job "$DEMO" "$EXTRA" ;;
  windpark-sim) run_job windpark-sim "$EXTRA"; restart_stats ;;
  stats) demo_stats ;;
  dwd) run_job dwd "$EXTRA" ;;
  dwd-online) run_job dwd "$EXTRA" OPENTSX_DWD_OFFLINE- "OPENTSX_DWD_STATION=${DWD_STATIONS%%,*}" ;;
  dwd-forward) demo_dwd_forward ;;
  modbus|modbus-poll) demo_modbus ;;
  retrieval) demo_retrieval ;;
  studio)
    info "Studio on http://localhost:8088 (Ctrl-C to stop)"
    kns port-forward svc/opentsx-studio 8088:80 ;;
  all)
    run_job kafscale-check
    run_job windpark-sim; restart_stats
    info "waiting 60 s for the first statistics checkpoint"; sleep 60
    demo_stats
    run_job metadata
    run_job twin; run_job house; run_job boat
    run_job studio-export
    run_job dwd ""
    if modbus_device; then demo_modbus; else warn "modbus skipped: no device (MODBUS_SIM=true or MODBUS_HOST=<converter>)"; fi
    demo_retrieval
    info "all demos done; Studio: deploy/k3s/40-demo.sh studio" ;;
esac
