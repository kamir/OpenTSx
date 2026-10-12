#!/usr/bin/env bash
# OpenTSx command dispatcher inside the opentsx-tools image (the container counterpart of bin/opentsx).
#
#   opentsx <command> [options]
#
# Commands (options as in docs/user-manual/appendix-a-cli.md):
#   windpark-sim       wind-farm scenario -> KafScale and/or Iceberg   (org.opentsx.windpark.WindparkSim)
#   stats              streaming statistics processor on KafScale      (org.opentsx.stats.StatsProcessorApp)
#   metadata           metadata pool -> OpenMetadata (or --dry-run)    (org.opentsx.openmetadata.WindparkMetadata)
#   retrieval-server   REST API for episodes, patterns, segments       (org.opentsx.retrieval.server.RetrievalServer)
#   twin               digital twin S1 (H2 chain)                      (org.opentsx.twin.demo.H2ChainDemo)
#   house              house twin                                      (org.opentsx.twin.demo.HouseDemo)
#   boat               boat twin (Etap 22)                             (org.opentsx.twin.demo.BoatDemo)
#   modbus             Modbus meters: check|read|poll|simulate         (org.opentsx.modbus.ModbusCli; maps in share/modbus)
#   studio-export      scenario data for the Smart Topics Studio       (org.opentsx.twin.studio.StudioExport <out.json>)
#   kafscale-check     reachability, advertised brokers, topic counts  (org.opentsx.deploy.KafScaleCheck)
#   classpath <module> print the classpath of a module
#   help               this text
#
# Environment:
#   OPENTSX_HOME        installation directory (default: the parent of this script's directory)
#   OPENTSX_EXTRA_ARGS  extra arguments appended to the command (word-split), e.g. "--calibrate /private/bills.csv"
#   JAVA_OPTS           extra JVM options (JAVA_TOOL_OPTIONS is honoured by the JVM itself)
set -euo pipefail
OPENTSX_HOME="${OPENTSX_HOME:-$(cd "$(dirname "$(readlink -f "$0")")/.." && pwd)}"

usage() { sed -n '2,/^set -euo/{/^#/p}' "$(readlink -f "$0")" | sed 's/^# \{0,1\}//'; exit "${1:-0}"; }

classpath() {   # module... -> classpath from the staged cp/<module>.txt files (entries relative to OPENTSX_HOME)
  local cp="" m entry
  for m in "$@"; do
    local f="$OPENTSX_HOME/cp/$m.txt"
    [[ -f "$f" ]] || { echo "opentsx: no classpath for module $m ($f) - image built incompletely?" >&2; exit 1; }
    while IFS= read -r entry || [[ -n "$entry" ]]; do
      [[ -n "$entry" ]] && cp="$cp:$OPENTSX_HOME/$entry"
    done < "$f"
  done
  echo "${cp#:}"
}

run() {   # module-list main-class args...
  local modules="$1" main="$2"; shift 2
  local extra=()
  if [[ -n "${OPENTSX_EXTRA_ARGS:-}" ]]; then
    read -r -a extra <<< "$OPENTSX_EXTRA_ARGS"
  fi
  # shellcheck disable=SC2086  # JAVA_OPTS is a list of options
  exec java ${JAVA_OPTS:-} -cp "$(classpath $modules)" "$main" "$@" ${extra[@]+"${extra[@]}"}
}

[[ $# -ge 1 ]] || usage 1
cmd="$1"; shift
case "$cmd" in
  windpark-sim)     run "opentsx-windpark" org.opentsx.windpark.WindparkSim "$@" ;;
  stats)            run "opentsx-stats" org.opentsx.stats.StatsProcessorApp "$@" ;;
  metadata)         run "opentsx-openmetadata" org.opentsx.openmetadata.WindparkMetadata "$@" ;;
  retrieval-server) run "opentsx-retrieval opentsx-store-iceberg" org.opentsx.retrieval.server.RetrievalServer "$@" ;;
  twin)             run "opentsx-twin" org.opentsx.twin.demo.H2ChainDemo "$@" ;;
  house)            run "opentsx-twin" org.opentsx.twin.demo.HouseDemo "$@" ;;
  boat)             run "opentsx-twin" org.opentsx.twin.demo.BoatDemo "$@" ;;
  modbus)           run "opentsx-modbus" org.opentsx.modbus.ModbusCli "$@" ;;
  studio-export)    run "opentsx-twin" org.opentsx.twin.studio.StudioExport "$@" ;;
  kafscale-check)   run "opentsx-deploy-tools" org.opentsx.deploy.KafScaleCheck "$@" ;;
  classpath)        classpath "$@" ;;
  -h|--help|help)   usage 0 ;;
  *) echo "opentsx: unknown command: $cmd" >&2; usage 1 ;;
esac
