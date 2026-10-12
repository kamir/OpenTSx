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
#   metadata-publish   publish to OpenMetadata (needs OM_URL and OPENMETADATA_JWT in 20-configure.sh)
#   twin | house | boat   the three digital-twin scenarios (H2 chain, house Erzgebirge, boat Etap 22)
#   studio-export      runs the Studio export inside the cluster (size + checksum of the scenario JSON)
#   retrieval          queries the retrieval REST API through a temporary port-forward
#   studio             port-forwards the Studio page to http://localhost:8088 (Ctrl-C to stop)
#   dwd                DWD MOSMIX -> v2 series + CSV on the data PVC (/data/dwd), house twin over the forecast;
#                      OFFLINE: reads the MOSMIX fixture in the image (no internet needed)
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
set -euo pipefail
# shellcheck source=deploy/k3s/lib.sh
source "$(dirname "$0")/lib.sh"

[[ $# -ge 1 ]] || usage 1
DEMO="$1"; shift
[[ "$DEMO" == "-h" || "$DEMO" == "--help" ]] && usage 0
EXTRA=""
if [[ $# -gt 0 ]]; then
  [[ "$1" == "--" ]] || die "extra arguments must follow --, e.g. 40-demo.sh house -- --calibrate /private/bills.csv"
  shift; EXTRA="$*"
fi
load_env
require_overlay
JOB_TIMEOUT="${JOB_TIMEOUT:-1800}"

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
        die "$job: $reason - image $TOOLS_IMAGE not in k3s' containerd? run 10-build.sh and 11-import-images.sh" ;;
      CreateContainerConfigError) die "$job: $reason - $(kns get pod -l "job-name=$job" -o 'jsonpath={.items[0].status.containerStatuses[0].state.waiting.message}')" ;;
    esac
    [[ "$phase" == "Running" || "$phase" == "Succeeded" || "$phase" == "Failed" ]] && break
    (( SECONDS - start > 300 )) && die "$job: pod not started after 300 s (phase '${phase:-none}', $reason) - see 50-status.sh"
    sleep 2
  done
  kns logs -f "job/$job" --all-containers=true | grep -v '^Picked up JAVA_TOOL_OPTIONS' || true
  local cond=""
  while (( SECONDS - start < JOB_TIMEOUT )); do   # Complete or Failed (a retried pod's logs: kubectl logs job/...)
    cond="$(kns get job "$job" -o 'jsonpath={.status.conditions[?(@.status=="True")].type}' 2>/dev/null || true)"
    [[ "$cond" == *Complete* || "$cond" == *Failed* ]] && break
    sleep 2
  done
  if [[ "$cond" == *Complete* ]]; then
    ok "$job completed"
  else
    fail "$job did not complete (${cond:-timeout}) - ${KUBECTL} -n $OPENTSX_NS describe job $job"
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
  kns logs deployment/opentsx-stats --tail=20 2>/dev/null | grep -v '^Picked up JAVA_TOOL_OPTIONS' || true
  kns exec deployment/opentsx-stats -- ls -l /state 2>/dev/null || warn "no checkpoint yet (written every 30 s)"
  run_job kafscale-check   # record counts, incl. tsx.<domain>.series-stats.v2
}

demo_retrieval() {
  local port=18080 pid q
  info "retrieval API via port-forward localhost:$port"
  kns port-forward svc/opentsx-retrieval "$port:8080" >/dev/null 2>&1 & pid=$!
  # shellcheck disable=SC2064
  trap "kill $pid 2>/dev/null || true" RETURN
  for _ in $(seq 1 30); do curl -sf "localhost:$port/v1/health" >/dev/null 2>&1 && break; sleep 1; done
  curl -sf "localhost:$port/v1/health" || die "retrieval server not answering (50-status.sh)"
  echo
  if is_true "$LAKE"; then q="metric=wind.met.wind_speed&tag.turbine=T07&limit=1"; else q="metric=demo.signal&limit=1"; fi
  echo "GET /v1/episodes?$q"
  curl -sf "localhost:$port/v1/episodes?$q" | head -c 400; echo " ..."
}

modbus_device() {   # 0 when a device is configured: the simulator is deployed, or MODBUS_HOST points elsewhere
  [[ "$MODBUS_HOST" != "opentsx-modbus-sim" ]] && return 0
  kns get deployment/opentsx-modbus-sim >/dev/null 2>&1
}

demo_modbus() {
  if [[ "$MODBUS_HOST" == "opentsx-modbus-sim" ]]; then
    modbus_device || die "simulator not deployed - 20-configure.sh MODBUS_SIM=true && 30-deploy.sh (or MODBUS_HOST=<converter>)"
    info "Modbus simulator opentsx-modbus-sim:$MODBUS_PORT (example register map)"
    kns rollout status deployment/opentsx-modbus-sim --timeout=120s
  fi
  run_job modbus-poll "$EXTRA"
}

demo_dwd_forward() {
  kns get cronjob/opentsx-dwd-forward >/dev/null 2>&1 \
    || die "CronJob opentsx-dwd-forward not deployed - 20-configure.sh DWD_FORWARD=true && 30-deploy.sh"
  local job="opentsx-dwd-forward-manual"
  info "job $job (from cronjob/opentsx-dwd-forward, DWD_MODE=$DWD_MODE)"
  kns delete job "$job" --ignore-not-found --wait=true >/dev/null
  kns create job "$job" --from=cronjob/opentsx-dwd-forward >/dev/null
  if kns wait --for=condition=complete "job/$job" --timeout="${JOB_TIMEOUT}s" >/dev/null 2>&1; then
    kns logs "job/$job" | grep -v '^Picked up JAVA_TOOL_OPTIONS' || true
    ok "$job completed"
  else
    kns logs "job/$job" 2>/dev/null | grep -v '^Picked up JAVA_TOOL_OPTIONS' || true
    fail "$job did not complete - ${KUBECTL} -n $OPENTSX_NS describe job $job"
    return 1
  fi
}

case "$DEMO" in
  kafscale-check|metadata|metadata-publish|twin|house|boat|studio-export)
    if [[ "$DEMO" == "metadata-publish" ]]; then
      [[ -n "$OM_URL" ]] || die "OM_URL not set - 20-configure.sh OM_URL=http://host:8585/api OPENMETADATA_JWT=<bot token>"
    fi
    run_job "$DEMO" "$EXTRA" ;;
  windpark-sim) run_job windpark-sim "$EXTRA"; restart_stats ;;
  stats) demo_stats ;;
  dwd) run_job dwd "$EXTRA" DWD_MODE=offline ;;
  dwd-online) run_job dwd "$EXTRA" DWD_MODE=online ;;
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
    run_job dwd "" DWD_MODE=offline
    if modbus_device; then demo_modbus; else warn "modbus skipped: no device (MODBUS_SIM=true or MODBUS_HOST=<converter>)"; fi
    demo_retrieval
    info "all demos done - Studio: deploy/k3s/40-demo.sh studio" ;;
  *) die "unknown demo '$DEMO' (see --help)" ;;
esac
