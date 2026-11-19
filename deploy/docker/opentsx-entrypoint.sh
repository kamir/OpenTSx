#!/usr/bin/env bash
# OpenTSx command dispatcher inside the opentsx-tools image (the container counterpart of bin/opentsx).
#
#   opentsx <command> [options]
#
# Commands (options as in docs/user-manual/appendix-a-cli.md; opentsx <command> --help lists the keys of a command):
#   windpark-sim       wind-farm scenario -> KafScale and/or Iceberg   (org.opentsx.windpark.WindparkSim)
#   stats              streaming statistics processor on KafScale      (org.opentsx.stats.StatsProcessorApp)
#   metadata           metadata pool -> OpenMetadata (or --dry-run)    (org.opentsx.openmetadata.WindparkMetadata)
#   retrieval-server   REST API for episodes, patterns, segments       (org.opentsx.retrieval.server.RetrievalServer)
#   vectors            vector processor: run | deploy | stop           (org.opentsx.retrieval.vector.VectorProcessorApp)
#   twin               digital twin S1 (H2 chain)                      (org.opentsx.twin.demo.H2ChainDemo)
#   house              house twin                                      (org.opentsx.twin.demo.HouseDemo)
#   boat               boat twin (Etap 22)                             (org.opentsx.twin.demo.BoatDemo)
#   dwd                DWD Open Data collector                         (org.opentsx.dwd.DwdCli)
#   modbus             Modbus meters: check|read|poll|simulate         (org.opentsx.modbus.ModbusCli; maps in share/modbus)
#   twin-coupling      variant L vs T: staleness/latency grid          (org.opentsx.twin.demo.CouplingStudy)
#   twin-live          event topic of a twin: publish|bridge|demo      (org.opentsx.twin.live.TwinLive)
#   twin-schemas       the twin's Avro topic contract, --out dir       (org.opentsx.twin.schema.AtomSchemas)
#   studio-export      scenario data for the Smart Topics Studio       (org.opentsx.twin.studio.StudioExport <out.json>)
#   market             market data, history archive, backfill          (org.opentsx.market.MarketCli)
#   history-twin       twin|house|boat on the history archive          (org.opentsx.market.twin.HistoryTwins)
#   twin --history d, house --weather-year Y, boat --weather-year Y   the same as history-twin twin|house|boat
#   kafscale-check     reachability, advertised brokers, topic counts  (org.opentsx.deploy.KafScaleCheck)
#   config             config list|show|get: key registry, effective values  (org.opentsx.conf.ConfCli)
#   version            print the OpenTSx version (also --version)      (org.opentsx.conf.ConfCli)
#   classpath <module> print the classpath of a module
#   help               this text
# Not in the image: stats-flink (bin/opentsx only; the image has no Flink runtime).
#
# Environment:
#   OPENTSX_HOME        installation directory (default: the parent of this script's directory)
#   OPENTSX_EXTRA_ARGS  extra arguments appended to the command (word-split), e.g. "--calibrate /private/bills.csv"
#   JAVA_OPTS           extra JVM options, after -XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError (a later option wins)
#
# Logging: slf4j-simple with $OPENTSX_HOME/conf/simplelogger.properties (both on every classpath), to stderr.
set -euo pipefail
OPENTSX_HOME="${OPENTSX_HOME:-$(cd "$(dirname "$(readlink -f "$0")")/.." && pwd)}"

usage() { sed -n '2,/^set -euo/{/^#/p}' "$(readlink -f "$0")" | sed 's/^# \{0,1\}//'; exit "${1:-0}"; }

classpath() {   # module... -> classpath from the staged cp/<module>.txt files (entries relative to OPENTSX_HOME)
  local cp="" m entry
  for m in "$@"; do
    local f="$OPENTSX_HOME/cp/$m.txt"
    [[ -f "$f" ]] || { echo "opentsx: no classpath for module $m ($f); build the image from a complete stage" >&2; exit 1; }
    while IFS= read -r entry || [[ -n "$entry" ]]; do
      [[ -n "$entry" ]] && cp="$cp:$OPENTSX_HOME/$entry"
    done < "$f"
  done
  echo "${cp#:}"
}

run() {   # module-list main-class args...
  local modules="$1" main="$2" cp; shift 2
  local extra=()
  if [[ -n "${OPENTSX_EXTRA_ARGS:-}" ]]; then
    read -r -a extra <<< "$OPENTSX_EXTRA_ARGS"
  fi
  # shellcheck disable=SC2086  # a list of modules
  cp="$(classpath $modules)" || exit 1
  # shellcheck disable=SC2086  # JAVA_OPTS is a list of options
  exec java -XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError ${JAVA_OPTS:-} -cp "$cp" "$main" "$@" ${extra[@]+"${extra[@]}"}
}

[[ $# -ge 1 ]] || usage 1
cmd="$1"; shift
case "$cmd" in
  windpark-sim)     run "opentsx-windpark" org.opentsx.windpark.WindparkSim "$@" ;;
  stats)            run "opentsx-stats" org.opentsx.stats.StatsProcessorApp "$@" ;;
  metadata)         run "opentsx-openmetadata" org.opentsx.openmetadata.WindparkMetadata "$@" ;;
  retrieval-server) run "opentsx-retrieval opentsx-store-iceberg" org.opentsx.retrieval.server.RetrievalServer "$@" ;;
  vectors)          run "opentsx-retrieval" org.opentsx.retrieval.vector.VectorProcessorApp "$@" ;;
  twin|house|boat)
    # with archived history (--history / --weather-year, also in OPENTSX_EXTRA_ARGS) the run goes through the
    # history adapter of opentsx-market
    if [[ " $* ${OPENTSX_EXTRA_ARGS:-} " == *" --history "* || " $* ${OPENTSX_EXTRA_ARGS:-} " == *" --weather-year "* ]]; then
      run "opentsx-market" org.opentsx.market.twin.HistoryTwins "$cmd" "$@"
    fi
    case "$cmd" in
      twin)  run "opentsx-twin" org.opentsx.twin.demo.H2ChainDemo "$@" ;;
      house) run "opentsx-twin" org.opentsx.twin.demo.HouseDemo "$@" ;;
      boat)  run "opentsx-twin" org.opentsx.twin.demo.BoatDemo "$@" ;;
    esac ;;
  history-twin)     run "opentsx-market" org.opentsx.market.twin.HistoryTwins "$@" ;;
  market)           run "opentsx-market" org.opentsx.market.MarketCli "$@" ;;
  dwd)              run "opentsx-dwd" org.opentsx.dwd.DwdCli "$@" ;;
  modbus)           run "opentsx-modbus" org.opentsx.modbus.ModbusCli "$@" ;;
  twin-coupling)    run "opentsx-twin" org.opentsx.twin.demo.CouplingStudy "$@" ;;
  twin-live)        run "opentsx-twin-live" org.opentsx.twin.live.TwinLive "$@" ;;
  twin-schemas)     run "opentsx-twin" org.opentsx.twin.schema.AtomSchemas "$@" ;;
  studio-export)    run "opentsx-twin" org.opentsx.twin.studio.StudioExport "$@" ;;
  kafscale-check)   run "opentsx-deploy-tools" org.opentsx.deploy.KafScaleCheck "$@" ;;
  config|version)   run "opentsx-conf" org.opentsx.conf.ConfCli "$cmd" "$@" ;;
  --version)        run "opentsx-conf" org.opentsx.conf.ConfCli --version ;;
  classpath)        classpath "$@" ;;
  -h|--help|help)   usage 0 ;;
  *) echo "opentsx: unknown command: $cmd" >&2; usage 1 ;;
esac
