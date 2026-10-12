#!/usr/bin/env bash
# Stages the image build context for the OpenTSx container images (no Maven or JDK inside the images).
#
#   deploy/docker/stage.sh [--out DIR] [--with-s3]
#
# Needs a prior `mvn install -DskipTests` (JDK 17+, Maven 3.9+). Writes (default DIR = deploy/docker/.stage, git-ignored):
#   DIR/opt/opentsx/bin/opentsx        entrypoint (deploy/docker/opentsx-entrypoint.sh)
#   DIR/opt/opentsx/lib/m2/...         every runtime jar once, in Maven repository layout (no version clashes)
#   DIR/opt/opentsx/cp/<module>.txt    exact runtime classpath per module (as `bin/opentsx classpath` computes it)
#   DIR/opt/opentsx/share/...          wind-farm scenario, twin plans and CSV templates
#   DIR/studio/...                     tools/build_studio.py + the Studio template (for studio.Dockerfile)
# --with-s3 adds iceberg-aws + iceberg-aws-bundle (S3FileIO) to the Iceberg classpaths, needed for an s3:// warehouse.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="$ROOT/deploy/docker/.stage"
WITH_S3=false
while [[ $# -gt 0 ]]; do
  case "$1" in
    --out) OUT="$(mkdir -p "$2" && cd "$2" && pwd)"; shift 2 ;;
    --with-s3) WITH_S3=true; shift ;;
    -h|--help) sed -n '2,/^set -euo/{/^#/p}' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "stage.sh: unknown option $1" >&2; exit 2 ;;
  esac
done

MODULES=(opentsx-kafka-v2 opentsx-windpark opentsx-stats opentsx-openmetadata opentsx-retrieval opentsx-store-iceberg opentsx-twin)
ICEBERG_MODULES=(opentsx-windpark opentsx-retrieval opentsx-store-iceberg)
VERSION="$(cd "$ROOT" && mvn -q -B help:evaluate -Dexpression=project.version -DforceStdout 2>/dev/null | tail -1)"
REPO="$(cd "$ROOT" && mvn -q -B help:evaluate -Dexpression=settings.localRepository -DforceStdout 2>/dev/null | tail -1)"
[[ -n "$VERSION" && -d "$REPO" ]] || { echo "stage.sh: cannot determine project version / local Maven repository" >&2; exit 1; }
ICEBERG_VERSION="$(cd "$ROOT" && mvn -q -B -pl opentsx-store-iceberg help:evaluate -Dexpression=iceberg.version -DforceStdout 2>/dev/null | tail -1)"

H="$OUT/opt/opentsx"
rm -rf "$OUT"
mkdir -p "$H/bin" "$H/lib/m2" "$H/cp" "$H/share/scenarios" "$H/share/twin" "$OUT/studio/tools" "$OUT/studio/docs/prototypes"

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
  [[ -f "$jar" ]] || { echo "stage.sh: $jar missing - run: mvn -q install -DskipTests" >&2; exit 1; }
  raw="$OUT/$m.cp.raw"
  # runtime scope (no test jars); -pl without -am: sibling modules resolve from the local repository (installed jars)
  (cd "$ROOT" && mvn -q -B -pl "$m" dependency:build-classpath -Dmdep.includeScope=runtime -Dmdep.outputFile="$raw" >/dev/null)
  {
    # the module's own jar, placed where `mvn install` puts it
    mkdir -p "$H/lib/m2/org/opentsx/$m/$VERSION"
    cp "$jar" "$H/lib/m2/org/opentsx/$m/$VERSION/"
    echo "lib/m2/org/opentsx/$m/$VERSION/$m-$VERSION.jar"
    tr ':' '\n' < "$raw" | while IFS= read -r dep || [[ -n "$dep" ]]; do [[ -n "$dep" ]] && add_jar "$dep"; done
  } > "$H/cp/$m.txt"
  rm -f "$raw"
done

if $WITH_S3; then
  for a in iceberg-aws iceberg-aws-bundle; do
    (cd "$ROOT" && mvn -q -B dependency:get -Dartifact="org.apache.iceberg:$a:$ICEBERG_VERSION" -Dtransitive=false >/dev/null)
    rel="$(add_jar "$REPO/org/apache/iceberg/$a/$ICEBERG_VERSION/$a-$ICEBERG_VERSION.jar")"
    for m in "${ICEBERG_MODULES[@]}"; do echo "$rel" >> "$H/cp/$m.txt"; done
  done
fi

# deployment helper (KafScaleCheck), compiled against the kafka-v2 classpath
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
kcp="$(sed "s#^#$H/#" "$H/cp/opentsx-kafka-v2.txt" | paste -sd: -)"
javac --release 17 -nowarn -d "$tmp/classes" -cp "$kcp" "$ROOT/deploy/docker/src/org/opentsx/deploy/KafScaleCheck.java"
jar --create --file "$H/lib/opentsx-deploy-tools.jar" -C "$tmp/classes" .
{ echo "lib/opentsx-deploy-tools.jar"; cat "$H/cp/opentsx-kafka-v2.txt"; } > "$H/cp/opentsx-deploy-tools.txt"

cp "$ROOT/deploy/docker/opentsx-entrypoint.sh" "$H/bin/opentsx"
chmod 0755 "$H/bin/opentsx"
cp "$ROOT"/opentsx-windpark/scenarios/*.yaml "$H/share/scenarios/"
cp "$ROOT"/opentsx-twin/src/main/resources/twin/* "$H/share/twin/"
cp "$ROOT/tools/build_studio.py" "$OUT/studio/tools/"
cp "$ROOT/docs/prototypes/smart-topics-studio.template.html" "$OUT/studio/docs/prototypes/"
chmod -R a+rX "$OUT"

echo "staged $(find "$H/lib" -name '*.jar' | wc -l) jars ($(du -sh "$H/lib" | cut -f1)) for OpenTSx $VERSION into $OUT (s3: $WITH_S3)"
