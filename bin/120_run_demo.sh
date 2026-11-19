#!/usr/bin/env bash
# Starts the 2.x GUI org.opentsx.app.bucketanalyser.MacroRecorder2 of the module opentsx-core through mvn exec:java.
#
#   bin/120_run_demo.sh
#
# Needs a prior build (mvn -q install -DskipTests; JDK 17+, Maven 3.9+ on PATH) and a display. Exit codes: the exit
# code of Maven, 2 on a usage error.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

case "${1:-}" in
  "") ;;
  -h|--help) sed -n '2,/^set -euo/{/^#/p}' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
  *) echo "120_run_demo.sh: unknown argument $1; expected no argument or --help" >&2; exit 2 ;;
esac
exec mvn -q -B -f "$ROOT/opentsx-core/pom.xml" exec:java -Dexec.mainClass=org.opentsx.app.bucketanalyser.MacroRecorder2
