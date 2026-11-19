#!/usr/bin/env bash
# Stages the image build context for the OpenTSx container images (no Maven or JDK inside the images).
#
#   deploy/docker/stage.sh [--out DIR] [--with-s3]
#
# Needs a prior `mvn install -DskipTests` (JDK 17+, Maven 3.9+). Deletes DIR and writes (default DIR =
# deploy/docker/.stage, git-ignored):
#   DIR/.opentsx-stage                 marker: --out refuses a directory that is not empty and has no marker
#   DIR/opt/opentsx/bin/opentsx        entrypoint (deploy/docker/opentsx-entrypoint.sh)
#   DIR/opt/opentsx/lib/m2/...         every runtime jar once, in Maven repository layout (no version clashes)
#   DIR/opt/opentsx/cp/<module>.txt    exact runtime classpath per module (as `bin/opentsx classpath` computes it), with
#                                      slf4j-simple where the module brings no SLF4J provider, and conf/
#   DIR/opt/opentsx/conf/              conf/simplelogger.properties: the logger settings of every command
#   DIR/opt/opentsx/share/...          wind-farm scenario, twin plans and CSV templates, Modbus register maps,
#                                      DWD offline fixtures (MOSMIX KML + station catalog excerpt, for demos without internet)
#   DIR/studio/...                     tools/build_studio.py + the Studio template (for studio.Dockerfile)
# --with-s3 adds iceberg-aws + iceberg-aws-bundle (S3FileIO) to the Iceberg classpaths, needed for an s3:// warehouse.
# Exit codes: 0 staged, 1 a step failed (Maven's output is shown), 2 usage error.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="$ROOT/deploy/docker/.stage"
MARKER=.opentsx-stage
WITH_S3=false
while [[ $# -gt 0 ]]; do
  case "$1" in
    --out)
      [[ $# -ge 2 && -n "$2" ]] || { echo "stage.sh: --out: no directory given; expected --out DIR" >&2; exit 2; }
      OUT="$(mkdir -p "$2" && cd "$2" && pwd)"
      if [[ -n "$(ls -A "$OUT")" && ! -f "$OUT/$MARKER" ]]; then
        echo "stage.sh: --out: $OUT is not empty and has no $MARKER file; expected a new or empty directory or one that stage.sh wrote" >&2
        exit 2
      fi
      shift 2 ;;
    --with-s3) WITH_S3=true; shift ;;
    -h|--help) sed -n '2,/^set -euo/{/^#/p}' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "stage.sh: unknown option $1" >&2; exit 2 ;;
  esac
done

MODULES=(opentsx-conf opentsx-kafka-v2 opentsx-windpark opentsx-stats opentsx-openmetadata opentsx-retrieval opentsx-store-iceberg opentsx-twin opentsx-twin-live opentsx-dwd opentsx-modbus opentsx-market)
ICEBERG_MODULES=(opentsx-windpark opentsx-retrieval opentsx-store-iceberg opentsx-market)
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT

maven() {   # step, Maven arguments: Maven's stdout in $tmp/mvn.out; on failure its output on stderr and exit 1
  local step="$1"; shift
  if ! (cd "$ROOT" && mvn -q -B "$@") > "$tmp/mvn.out" 2> "$tmp/mvn.err"; then
    cat "$tmp/mvn.out" "$tmp/mvn.err" >&2
    echo "stage.sh: $step: Maven failed; see the Maven output above" >&2
    exit 1
  fi
}

evaluate() {   # expression [module] -> its value
  maven "help:evaluate $1" ${2:+-pl "$2"} help:evaluate -Dexpression="$1" -DforceStdout
  tail -1 "$tmp/mvn.out"
}

VERSION="$(evaluate project.version)"
REPO="$(evaluate settings.localRepository)"
[[ -n "$VERSION" && -d "$REPO" ]] || { echo "stage.sh: cannot determine project version / local Maven repository" >&2; exit 1; }
ICEBERG_VERSION="$(evaluate iceberg.version opentsx-store-iceberg)"
SLF4J_VERSION="$(evaluate slf4j.version)"
SIMPLE_JAR="$REPO/org/slf4j/slf4j-simple/$SLF4J_VERSION/slf4j-simple-$SLF4J_VERSION.jar"
[[ -f "$SIMPLE_JAR" ]] || maven "dependency:get slf4j-simple" dependency:get -Dartifact="org.slf4j:slf4j-simple:$SLF4J_VERSION" -Dtransitive=false
[[ -f "$SIMPLE_JAR" ]] || { echo "stage.sh: slf4j-simple $SLF4J_VERSION is not in the local Maven repository ($SIMPLE_JAR)" >&2; exit 1; }

H="$OUT/opt/opentsx"
rm -rf "$OUT"
mkdir -p "$OUT"
touch "$OUT/$MARKER"
mkdir -p "$H/bin" "$H/lib/m2" "$H/cp" "$H/conf" "$H/share/scenarios" "$H/share/windpark" "$H/share/twin" "$H/share/modbus" "$H/share/dwd" "$OUT/studio/tools" "$OUT/studio/docs/prototypes"

add_jar() {   # absolute jar path -> prints the classpath entry relative to OPENTSX_HOME, copies the jar once
  local jar="$1" rel
  case "$jar" in
    "$REPO"/*) rel="lib/m2/${jar#"$REPO"/}" ;;
    *) echo "stage.sh: jar outside the local Maven repository: $jar" >&2; exit 1 ;;
  esac
  if [[ ! -f "$H/$rel" ]]; then
    mkdir -p "$(dirname "$H/$rel")"
    cp "$jar" "$H/$rel"
  fi
  echo "$rel"
}

for m in "${MODULES[@]}"; do
  jar="$ROOT/$m/target/$m-$VERSION.jar"
  [[ -f "$jar" ]] || { echo "stage.sh: $jar: missing; run mvn -q install -DskipTests first" >&2; exit 1; }
  raw="$OUT/$m.cp.raw"
  # runtime scope (no test jars); -pl without -am: sibling modules resolve from the local repository (installed jars)
  maven "dependency:build-classpath $m" -pl "$m" dependency:build-classpath -DincludeScope=runtime -Dmdep.outputFile="$raw"
  {
    # the module's own jar, placed where `mvn install` puts it
    mkdir -p "$H/lib/m2/org/opentsx/$m/$VERSION"
    cp "$jar" "$H/lib/m2/org/opentsx/$m/$VERSION/"
    echo "lib/m2/org/opentsx/$m/$VERSION/$m-$VERSION.jar"
    tr ':' '\n' < "$raw" | while IFS= read -r dep || [[ -n "$dep" ]]; do [[ -n "$dep" ]] && add_jar "$dep"; done
    grep -q '/slf4j-simple-[^/:]*\.jar' "$raw" || add_jar "$SIMPLE_JAR"
    echo "conf"
  } > "$H/cp/$m.txt"
  rm -f "$raw"
done

if $WITH_S3; then
  for a in iceberg-aws iceberg-aws-bundle; do
    maven "dependency:get $a" dependency:get -Dartifact="org.apache.iceberg:$a:$ICEBERG_VERSION" -Dtransitive=false
    rel="$(add_jar "$REPO/org/apache/iceberg/$a/$ICEBERG_VERSION/$a-$ICEBERG_VERSION.jar")"
    for m in "${ICEBERG_MODULES[@]}"; do echo "$rel" >> "$H/cp/$m.txt"; done
  done
  # iceberg-aws reads S3 through failsafe (a runtime dependency in its POM), which -Dtransitive=false leaves out.
  aws_pom="$REPO/org/apache/iceberg/iceberg-aws/$ICEBERG_VERSION/iceberg-aws-$ICEBERG_VERSION.pom"
  fs_version="$(grep -A2 '<artifactId>failsafe</artifactId>' "$aws_pom" | sed -n 's#.*<version>\(.*\)</version>.*#\1#p' | head -1)"
  [[ -n "$fs_version" ]] || { echo "stage.sh: no failsafe version in $aws_pom" >&2; exit 1; }
  maven "dependency:get failsafe" dependency:get -Dartifact="dev.failsafe:failsafe:$fs_version" -Dtransitive=false
  rel="$(add_jar "$REPO/dev/failsafe/failsafe/$fs_version/failsafe-$fs_version.jar")"
  for m in "${ICEBERG_MODULES[@]}"; do echo "$rel" >> "$H/cp/$m.txt"; done
fi

# deployment helper (KafScaleCheck), compiled against the kafka-v2 and the opentsx-conf classpath
cat "$H/cp/opentsx-kafka-v2.txt" "$H/cp/opentsx-conf.txt" | awk '!seen[$0]++' > "$tmp/deploy-tools.txt"
kcp="$(sed "s#^#$H/#" "$tmp/deploy-tools.txt" | paste -sd: -)"
javac --release 17 -nowarn -d "$tmp/classes" -cp "$kcp" "$ROOT/deploy/docker/src/org/opentsx/deploy/KafScaleCheck.java"
jar --create --file "$H/lib/opentsx-deploy-tools.jar" -C "$tmp/classes" .
{ echo "lib/opentsx-deploy-tools.jar"; cat "$tmp/deploy-tools.txt"; } > "$H/cp/opentsx-deploy-tools.txt"

cp "$ROOT/deploy/docker/opentsx-entrypoint.sh" "$H/bin/opentsx"
cp "$ROOT/conf/simplelogger.properties" "$H/conf/"
chmod 0755 "$H/bin/opentsx"
cp "$ROOT"/opentsx-windpark/scenarios/*.yaml "$H/share/scenarios/"
# channel catalog as a file: the retrieval server hands it out in the series inventory (--catalog)
cp "$ROOT"/opentsx-windpark/src/main/resources/windpark/channel-catalog.yaml "$H/share/windpark/"
cp -R "$ROOT"/opentsx-twin/src/main/resources/twin/. "$H/share/twin/"   # including subdirectories (beispiel/)
# system documents (opentsx.system.v1) and what they refer to, under their repository paths: the retrieval server
# reads them with --systems share/repo/systems --systems-root share/repo
R="$H/share/repo"
mkdir -p "$R/systems" "$R/opentsx-twin/src/main/resources/twin" "$R/opentsx-windpark/src/main/resources/windpark"
cp -R "$ROOT"/systems/. "$R/systems/"
cp "$ROOT"/opentsx-twin/src/main/resources/twin/*.yaml "$R/opentsx-twin/src/main/resources/twin/"
cp "$ROOT"/opentsx-windpark/src/main/resources/windpark/channel-catalog.yaml "$R/opentsx-windpark/src/main/resources/windpark/"
cp "$ROOT"/opentsx-modbus/src/main/resources/modbus/*.yaml "$H/share/modbus/"
# small DWD test fixtures (real file layout, a few KB): offline mode of the dwd demo Job and the probe
cp "$ROOT"/opentsx-dwd/src/test/resources/dwd/MOSMIX_L_2026101209_10578.kml \
   "$ROOT"/opentsx-dwd/src/test/resources/dwd/mosmix_stationskatalog.cfg "$H/share/dwd/"
cp "$ROOT/tools/build_studio.py" "$OUT/studio/tools/"
cp "$ROOT/docs/prototypes/smart-topics-studio.template.html" "$OUT/studio/docs/prototypes/"
chmod -R a+rX "$OUT"

echo "staged $(find "$H/lib" -name '*.jar' | wc -l) jars ($(du -sh "$H/lib" | cut -f1)) for OpenTSx $VERSION into $OUT (s3: $WITH_S3)"
