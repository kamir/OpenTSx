#!/usr/bin/env bash
# Tests the launchers bin/opentsx, bin/000_launch_tsa_workbench.sh and deploy/docker/opentsx-entrypoint.sh: no java start
# after a failed classpath step, one logger configuration in conf/, no test jars and exactly one SLF4J provider on every
# classpath.
#
#   bash deploy/docker/test-launchers.sh                 needs no build: the three launchers with fake mvn and java, and
#                                                        the logger settings under deploy/
#   bash deploy/docker/test-launchers.sh --dev           bin/opentsx classpath of every launcher module, and no log4j 1
#                                                        log4j.properties in the shaded opentsx-flink-core jar (after mvn install)
#   bash deploy/docker/test-launchers.sh --staged DIR    DIR/opt/opentsx/cp/*.txt written by deploy/docker/stage.sh --out DIR
#
# A classpath passes when it holds no junit or opentest4j jar, every entry exists and, when it holds slf4j-api, exactly
# one SLF4J provider (a jar with META-INF/services/org.slf4j.spi.SLF4JServiceProvider); with slf4j-simple a directory on
# it holds simplelogger.properties; with kafka-clients it holds zstd-jni; with iceberg-core it holds sqlite-jdbc. Exit
# codes: 0 every check passed, 1 a check failed, 2 usage error.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
failures=0
fail() { echo "FAIL $*"; failures=$((failures + 1)); }
ok() { echo "ok   $*"; }

check_classpath() {   # name expected-provider entry... (expected-provider "" accepts any single provider)
  local name="$1" expected="$2" e base api=false config=false kafka=false zstd=false iceberg=false sqlite=false bad=() missing=() providers=(); shift 2
  for e in "$@"; do
    [[ -n "$e" ]] || continue
    [[ -e "$e" ]] || { missing+=("$e"); continue; }
    base="${e##*/}"
    case "$base" in
      junit-*.jar|opentest4j-*.jar) bad+=("$base") ;;
      slf4j-api-*.jar) api=true ;;
      kafka-clients-*.jar) kafka=true ;;
      zstd-jni-*.jar) zstd=true ;;
      iceberg-core-*.jar) iceberg=true ;;
      sqlite-jdbc-*.jar) sqlite=true ;;
    esac
    [[ -f "$e/simplelogger.properties" ]] && config=true
    if [[ "$e" == *.jar && " ${providers[*]} " != *" $base "* ]] \
        && unzip -Z1 "$e" META-INF/services/org.slf4j.spi.SLF4JServiceProvider >/dev/null 2>&1; then
      providers+=("$base")
    fi
  done
  local problems=()
  (( ${#missing[@]} == 0 )) || problems+=("entries do not exist: ${missing[*]}")
  (( ${#bad[@]} == 0 )) || problems+=("test jars on the classpath: ${bad[*]}")
  if $api && (( ${#providers[@]} != 1 )); then
    problems+=("${#providers[@]} SLF4J providers (${providers[*]:-none}); expected exactly one")
  fi
  if $kafka && ! $zstd; then
    problems+=("kafka-clients without zstd-jni; KafScaleClients producers compress with zstd")
  fi
  if $iceberg && ! $sqlite; then
    problems+=("iceberg-core without sqlite-jdbc; --iceberg type=jdbc,uri=jdbc:sqlite:<file> needs the SQLite driver")
  fi
  if [[ -n "$expected" && "${providers[0]:-}" != "$expected"-* ]]; then
    problems+=("SLF4J provider ${providers[0]:-none}; expected $expected")
  fi
  if [[ "${providers[0]:-}" == slf4j-simple-* ]] && ! $config; then
    problems+=("slf4j-simple without a directory that holds simplelogger.properties")
  fi
  if (( ${#problems[@]} == 0 )); then
    ok "$name: ${providers[0]:-no SLF4J}"
  else
    fail "$name"; printf '    %s\n' "${problems[@]}"
  fi
}

dev() {   # every module list that bin/opentsx starts a main class with
  local modules=() m cp entries
  while IFS= read -r m; do modules+=("$m"); done < <(grep -oE '\b(run|classpath) "?opentsx-[a-z0-9-]+( opentsx-[a-z0-9-]+)*' \
    "$ROOT/bin/opentsx" | sed -E 's/^(run|classpath) "?//' | grep -vx opentsx-flink-core | sort -u)
  (( ${#modules[@]} > 5 )) || { fail "bin/opentsx: found only ${#modules[@]} launcher modules"; return; }
  for m in "${modules[@]}"; do
    # shellcheck disable=SC2086  # a list of modules
    cp="$("$ROOT/bin/opentsx" classpath $m)" || { fail "bin/opentsx classpath $m: exit $?"; continue; }
    IFS=: read -r -a entries <<< "$cp"
    check_classpath "bin/opentsx classpath $m" "" "${entries[@]}"
  done
  cp="$("$ROOT/bin/opentsx" classpath opentsx-flink-core)" || { fail "bin/opentsx classpath opentsx-flink-core: exit $?"; return; }
  IFS=: read -r -a entries <<< "$cp"
  check_classpath "bin/opentsx classpath opentsx-flink-core" log4j-slf4j2-impl "${entries[@]}"
  if printf '%s\n' "${entries[@]}" | grep -q '/flink-streaming-java-[^/]*\.jar$'; then
    ok "opentsx-flink-core: flink-streaming-java is on the classpath"
  else
    fail "opentsx-flink-core: flink-streaming-java is not on the classpath; stats-flink cannot start"
  fi
  local jar shaded=false
  for jar in "$ROOT"/opentsx-flink-core/target/opentsx-flink-core-*.jar; do
    [[ -f "$jar" ]] || continue
    shaded=true
    if unzip -Z1 "$jar" log4j.properties >/dev/null 2>&1; then
      fail "${jar##*/}: holds the log4j 1 log4j.properties; exclude it in the shade filter of opentsx-flink-core/pom.xml"
    else
      ok "${jar##*/}: no log4j.properties"
    fi
  done
  $shaded || fail "opentsx-flink-core/target: no shaded jar; run: mvn -B -ntp -pl opentsx-flink-core install -DskipTests"
}

staged() {
  local home="$1/opt/opentsx" f entry entries
  compgen -G "$home/cp/*.txt" >/dev/null || { fail "$home/cp: no classpath files"; return; }
  for f in "$home"/cp/*.txt; do
    entries=()
    while IFS= read -r entry || [[ -n "$entry" ]]; do
      [[ -n "$entry" ]] && entries+=("$home/$entry")
    done < "$f"
    check_classpath "staged cp/${f##*/}" "" "${entries[@]}"
  done
}

fake_tools() {   # dir: mvn writes $FAKE_CLASSPATH ($FAKE_PROVIDED for provided scope) to -Dmdep.outputFile and logs its
                 # arguments, java logs its arguments
  mkdir -p "$1"
  cat > "$1/mvn" <<'EOF'
#!/usr/bin/env bash
echo "$*" >> "$FAKE_LOG.mvn"
content="$FAKE_CLASSPATH"
for a in "$@"; do [[ "$a" == -DincludeScope=provided ]] && content="$FAKE_PROVIDED"; done
for a in "$@"; do [[ "$a" == -Dmdep.outputFile=* ]] && printf '%s' "$content" > "${a#-Dmdep.outputFile=}"; done
[[ "${FAKE_MVN_FAILS:-false}" == true ]] && { echo "[ERROR] fake resolution failure"; exit 1; }
exit 0
EOF
  cat > "$1/java" <<'EOF'
#!/usr/bin/env bash
printf '%s\n' "$@" > "$FAKE_LOG.java"
EOF
  chmod +x "$1/mvn" "$1/java"
}

offline() {
  local work="$WORK" out rc root repo api simple m
  fake_tools "$work/fake"
  root="$work/root"
  repo="$work/m2"
  api="$repo/org/slf4j/slf4j-api/2.0.20/slf4j-api-2.0.20.jar"
  simple="$repo/org/slf4j/slf4j-simple/2.0.20/slf4j-simple-2.0.20.jar"
  mkdir -p "$root/bin" "$root/conf" "$(dirname "$api")" "$(dirname "$simple")"
  touch "$api" "$simple"
  cp "$ROOT/bin/opentsx" "$root/bin/opentsx"
  for m in opentsx-stats opentsx-flink-core; do
    mkdir -p "$root/$m/target/classes"
    echo '<project/>' > "$root/$m/pom.xml"
  done
  export FAKE_LOG="$work/log" FAKE_CLASSPATH="$api" FAKE_PROVIDED=""

  rm -f "$work/log".*
  rc=0; out="$(PATH="$work/fake:$PATH" FAKE_MVN_FAILS=true bash "$root/bin/opentsx" stats --once true 2>&1)" || rc=$?
  if [[ $rc -ne 1 || -e "$work/log.java" || "$out" != *"[ERROR] fake resolution failure"* \
        || "$out" != *"resolving the classpath of opentsx-stats with Maven"* ]]; then
    fail "bin/opentsx with a failing mvn: exit $rc, java started: $([[ -e "$work/log.java" ]] && echo yes || echo no)"
    printf '%s\n' "$out" | sed 's/^/    /'
  elif [[ -e "$root/opentsx-stats/target/classpath-runtime.txt" ]]; then
    fail "bin/opentsx with a failing mvn: left a classpath cache behind"
  else
    ok "bin/opentsx with a failing mvn: exit 1 before java, Maven's [ERROR] line shown"
  fi

  rm -f "$work/log".*
  mkdir -p "$work/nomvn"
  ln -s "$(command -v dirname)" "$work/nomvn/dirname"
  ln -s "$work/fake/java" "$work/nomvn/java"
  rc=0; out="$(PATH="$work/nomvn" "$BASH" "$root/bin/opentsx" stats 2>&1)" || rc=$?
  if [[ $rc -ne 1 || -e "$work/log.java" || "$out" != *"mvn: not found on PATH; install Maven 3.9 or later"* ]]; then
    fail "bin/opentsx without mvn: exit $rc"; printf '%s\n' "$out" | sed 's/^/    /'
  else
    ok "bin/opentsx without mvn: exit 1 before java"
  fi

  local t workbench="$root/bin/000_launch_tsa_workbench.sh"
  cp "$ROOT/bin/000_launch_tsa_workbench.sh" "$workbench"
  for t in head cut ls cat; do ln -s "$(command -v "$t")" "$work/nomvn/$t"; done
  ln -s "$BASH" "$work/nomvn/bash"
  mkdir -p "$root/opentsx-core/target/classes"
  echo '<project/>' > "$root/opentsx-core/pom.xml"
  touch "$root/opentsx-core/target/opentsx-core-3.0.1.jar"
  rm -f "$work/log".*
  rc=0; out="$(PATH="$work/nomvn" JAVA_HOME=/none JAVA_OPTS="" "$BASH" "$workbench" 2>&1)" || rc=$?
  if [[ $rc -ne 1 || "$(cat "$work/log.java" 2>/dev/null)" != -version || "$out" != *"mvn: not found on PATH"* ]]; then
    fail "000_launch_tsa_workbench.sh without mvn and without a cached classpath: exit $rc"
    printf '%s\n' "$out" | tail -5 | sed 's/^/    /'; sed 's/^/    java: /' "$work/log.java" 2>/dev/null || true
  else
    ok "000_launch_tsa_workbench.sh without mvn and without a cached classpath: exit 1 before the workbench starts"
  fi
  printf '%s' "$api" > "$root/opentsx-core/target/classpath-runtime.txt"
  rm -f "$work/log".*
  rc=0; out="$(PATH="$work/nomvn" JAVA_HOME=/none JAVA_OPTS="" "$BASH" "$workbench" 2>&1)" || rc=$?
  if [[ $rc -ne 0 || "$(cat "$work/log.java" 2>/dev/null)" != "$(printf '%s\n' -Xmx2g -Xms512m \
        -cp "$root/opentsx-core/target/classes:$api:$simple:$root/conf" org.opentsx.app.bucketanalyser.MacroRecorder2)" ]]; then
    fail "000_launch_tsa_workbench.sh without mvn: java arguments"
    printf '%s\n' "$out" | tail -5 | sed 's/^/    /'; sed 's/^/    java: /' "$work/log.java" 2>/dev/null || true
  else
    ok "000_launch_tsa_workbench.sh without mvn: the classpath that bin/opentsx cached"
  fi

  rm -f "$work/log".*
  rc=0; out="$(PATH="$work/fake:$PATH" bash "$root/bin/opentsx" stats --once true 2>&1)" || rc=$?
  local cp="$root/opentsx-stats/target/classes:$api:$simple:$root/conf"
  if [[ $rc -ne 0 ]] || ! grep -q -- '-DincludeScope=runtime' "$work/log.mvn" \
      || [[ "$(cat "$work/log.java" 2>/dev/null)" != "$(printf '%s\n' -cp "$cp" org.opentsx.stats.StatsProcessorApp --once true)" ]]; then
    fail "bin/opentsx stats: java arguments or Maven scope"
    printf '%s\n' "$out" | sed 's/^/    /'; sed 's/^/    mvn: /' "$work/log.mvn" 2>/dev/null; sed 's/^/    java: /' "$work/log.java" 2>/dev/null
  else
    ok "bin/opentsx stats: runtime scope, slf4j-simple of the slf4j-api version, then conf/"
  fi

  rm -f "$work/log".*
  local flink="$repo/org/apache/flink/flink-streaming-java/1.20.5/flink-streaming-java-1.20.5.jar"
  rc=0; out="$(PATH="$work/fake:$PATH" FAKE_PROVIDED="$flink" bash "$root/bin/opentsx" stats-flink --once true 2>&1)" || rc=$?
  if [[ $rc -ne 0 ]] || ! grep -q -- '-DincludeScope=runtime' "$work/log.mvn" || ! grep -q -- '-DincludeScope=provided' "$work/log.mvn" \
      || [[ "$(cat "$work/log.java" 2>/dev/null)" != "$(printf '%s\n' "-Dlog4j2.configurationFile=$root/conf/log4j2-stats-flink.properties" \
              -cp "$root/opentsx-flink-core/target/classes:$api:$flink" org.opentsx.flink.stats.StatsJob --once true)" ]]; then
    fail "bin/opentsx stats-flink: java arguments or Maven scope"
    printf '%s\n' "$out" | sed 's/^/    /'; sed 's/^/    mvn: /' "$work/log.mvn" 2>/dev/null; sed 's/^/    java: /' "$work/log.java" 2>/dev/null
  else
    ok "bin/opentsx stats-flink: runtime and provided scope (Flink), log4j2 configuration, no slf4j-simple"
  fi

  local home="$work/home"
  mkdir -p "$home/bin" "$home/cp"
  cp "$ROOT/deploy/docker/opentsx-entrypoint.sh" "$home/bin/opentsx"
  rm -f "$work/log".*
  rc=0; out="$(PATH="$work/fake:$PATH" OPENTSX_HOME="$home" bash "$home/bin/opentsx" stats 2>&1)" || rc=$?
  if [[ $rc -ne 1 || -e "$work/log.java" ]]; then
    fail "entrypoint without cp/opentsx-stats.txt: exit $rc, java started: $([[ -e "$work/log.java" ]] && echo yes || echo no)"
    printf '%s\n' "$out" | sed 's/^/    /'
  else
    ok "entrypoint without cp/opentsx-stats.txt: exit 1 before java"
  fi

  printf '%s\n' lib/opentsx-stats.jar conf > "$home/cp/opentsx-stats.txt"
  rm -f "$work/log".*
  rc=0; out="$(PATH="$work/fake:$PATH" OPENTSX_HOME="$home" JAVA_OPTS="-Xmx1g" bash "$home/bin/opentsx" stats --once true 2>&1)" || rc=$?
  if [[ $rc -ne 0 || "$(cat "$work/log.java" 2>/dev/null)" != "$(printf '%s\n' -XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -Xmx1g \
        -cp "$home/lib/opentsx-stats.jar:$home/conf" org.opentsx.stats.StatsProcessorApp --once true)" ]]; then
    fail "entrypoint stats: java arguments"; printf '%s\n' "$out" | sed 's/^/    /'; sed 's/^/    java: /' "$work/log.java" 2>/dev/null
  else
    ok "entrypoint stats: memory flags on the java command, then JAVA_OPTS"
  fi
}

expect_none() {   # description hits fix
  if [[ -z "$2" ]]; then ok "$1"; else fail "$3"; printf '%s\n' "$2" | sed 's/^/    /'; fi
}

settings() {
  expect_none "deploy/k3s sets no slf4j-simple property" "$(grep -rn 'org\.slf4j\.simpleLogger' "$ROOT/deploy/k3s" || true)" \
    "deploy/k3s sets slf4j-simple properties; change conf/simplelogger.properties instead"
  expect_none "no JAVA_TOOL_OPTIONS in the Dockerfiles and k3s scripts" \
    "$(grep -n 'JAVA_TOOL_OPTIONS' "$ROOT"/deploy/docker/*.Dockerfile "$ROOT"/deploy/k3s/*.sh || true)" \
    "JAVA_TOOL_OPTIONS makes every JVM print 'Picked up JAVA_TOOL_OPTIONS'; put JVM flags on the java command"
  expect_none "no script filters 'Picked up'" \
    "$(grep -rn --include='*.sh' --exclude=test-launchers.sh 'Picked up' "$ROOT/deploy" || true)" \
    "scripts filter 'Picked up'; the JVM prints no such line any more"
}

case "${1:-}" in
  "")       WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT; offline; settings ;;
  --dev)    [[ $# -eq 1 ]] || { echo "test-launchers.sh: --dev takes no argument" >&2; exit 2; }; dev ;;
  --staged) [[ $# -eq 2 && -d "$2" ]] || { echo "test-launchers.sh: --staged needs the directory of stage.sh --out" >&2; exit 2; }
            staged "$2" ;;
  -h|--help) sed -n '2,/^set -euo/{/^#/p}' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
  *)        echo "test-launchers.sh: unknown option $1; expected --dev or --staged DIR" >&2; exit 2 ;;
esac
(( failures == 0 )) || { echo "$failures failed"; exit 1; }
echo "all passed"
