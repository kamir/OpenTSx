#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

export OPENTSX_USE_KAFKA=false
export OPENTSX_SHOW_GUI=false
export OPENTSX_NUMBER_OF_ITERATIONS="${OPENTSX_NUMBER_OF_ITERATIONS:-3}"
export OPENTSX_DEMO_OUTPUT_DIR="$ROOT_DIR/data/temp"
export OPENTSX_DEMO_OUTPUT_FORMAT="${OPENTSX_DEMO_OUTPUT_FORMAT:-csv}"
export OPENTSX_TOPIC_MAP_FILE_NAME="$ROOT_DIR/config/topiclist.def"
export OPENTSX_PRIMARY_CLUSTER_CLIENT_CFG_FILE_NAME="$ROOT_DIR/config/cpl.props"

mkdir -p "$OPENTSX_DEMO_OUTPUT_DIR"

mvn -pl opentsx-lg -am -DskipTests clean install

mvn -f "$ROOT_DIR/opentsx-lg/pom.xml" -DskipTests exec:java \
  -Dexec.mainClass="org.opentsx.lg.TSDataSineWaveGenerator" \
  -Dexec.args="off" \
  -Dexec.workingdir="$ROOT_DIR"

echo "Demo complete. Output written to ./data/temp/"
