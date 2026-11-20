#!/usr/bin/env bash

# Starts the TSA Workbench, the 2.x GUI org.opentsx.app.bucketanalyser.MacroRecorder2 of opentsx-core, through
# `mvn -f opentsx-core/pom.xml exec:java`, or without Maven with java and the classpath that bin/opentsx cached.
# Needs Java 17 or later and a build (./bin/010_build.sh).
#
# Usage: ./bin/000_launch_tsa_workbench.sh
#        JAVA_OPTS="-Xmx4g" ./bin/000_launch_tsa_workbench.sh
#
# JAVA_OPTS (default -Xmx2g -Xms512m) goes to java only when mvn is not on PATH. exec:java runs in the JVM of
# Maven, which reads MAVEN_OPTS instead.
#
# Exit codes: the exit code of Maven or java; 1 when Java, the build or the classpath is missing.

set -e

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

echo -e "${BLUE}OpenTSx TSA Workbench launcher${NC}"
echo

if ! command -v java &> /dev/null; then
    echo -e "${RED}FAIL  Java not found${NC}"
    echo "  Install Java 17 or later and set JAVA_HOME"
    exit 1
fi

JAVA_VERSION=$(java -version 2>&1 | head -n 1 | cut -d'"' -f2)
echo -e "${GREEN}ok${NC}    Java version: $JAVA_VERSION"

if [ -z "$JAVA_HOME" ]; then
    echo -e "${YELLOW}warn${NC}  JAVA_HOME not set, looking for a JDK"

    if [ -d "/Library/Java/JavaVirtualMachines" ]; then
        LATEST_JDK=""
        for jdk in /Library/Java/JavaVirtualMachines/*jdk*; do
            if [ -d "$jdk" ]; then LATEST_JDK="${jdk##*/}"; fi
        done
        if [ -n "$LATEST_JDK" ]; then
            export JAVA_HOME="/Library/Java/JavaVirtualMachines/$LATEST_JDK/Contents/Home"
            echo -e "${GREEN}ok${NC}    Found JAVA_HOME: $JAVA_HOME"
        fi
    fi

    if [ -z "$JAVA_HOME" ] && [ -d "/usr/lib/jvm" ]; then
        LATEST_JDK=""
        for jdk in /usr/lib/jvm/*java-*-openjdk*; do
            if [ -d "$jdk" ]; then LATEST_JDK="${jdk##*/}"; fi
        done
        if [ -n "$LATEST_JDK" ]; then
            export JAVA_HOME="/usr/lib/jvm/$LATEST_JDK"
            echo -e "${GREEN}ok${NC}    Found JAVA_HOME: $JAVA_HOME"
        fi
    fi

    if [ -z "$JAVA_HOME" ] && command -v brew &> /dev/null; then
        BREW_JAVA=$(brew --prefix openjdk 2>/dev/null || brew --prefix java 2>/dev/null || echo "")
        if [ -n "$BREW_JAVA" ] && [ -d "$BREW_JAVA" ]; then
            export JAVA_HOME="$BREW_JAVA/libexec/openjdk.jdk/Contents/Home"
            echo -e "${GREEN}ok${NC}    Found JAVA_HOME via Homebrew: $JAVA_HOME"
        fi
    fi
fi

if ! compgen -G "$PROJECT_ROOT/opentsx-core/target/*.jar" > /dev/null; then
    echo -e "${RED}FAIL  OpenTSx is not built${NC}"
    echo "  Run ./bin/010_build.sh first"
    exit 1
fi

echo -e "${GREEN}ok${NC}    OpenTSx build found"
echo

if [ -z "$DISPLAY" ]; then
    echo -e "${YELLOW}warn${NC}  DISPLAY is not set"
    echo "  The GUI needs a display; on a headless system use X11 forwarding"
fi

echo
echo -e "${BLUE}Launching the TSA Workbench${NC}"
echo "Main class: org.opentsx.app.bucketanalyser.MacroRecorder2"
echo "Project: $PROJECT_ROOT"
echo

cd "$PROJECT_ROOT"

EXIT_CODE=0
if command -v mvn &> /dev/null; then
    echo "Launching with Maven"
    mvn -f opentsx-core/pom.xml exec:java \
        -Dexec.mainClass="org.opentsx.app.bucketanalyser.MacroRecorder2" \
        -Dexec.args="" \
        -Dexec.cleanupDaemonThreads=false || EXIT_CODE=$?
else
    if [ -z "$JAVA_OPTS" ]; then
        JAVA_OPTS="-Xmx2g -Xms512m"
        echo "Using default Java options: $JAVA_OPTS"
    else
        echo "Using custom Java options: $JAVA_OPTS"
    fi
    echo "Launching with java"

    CLASSPATH="$("$PROJECT_ROOT/bin/opentsx" classpath opentsx-core)" || exit 1

    read -r -a JAVA_ARGS <<< "$JAVA_OPTS"
    java "${JAVA_ARGS[@]}" \
        -cp "$CLASSPATH" \
        org.opentsx.app.bucketanalyser.MacroRecorder2 || EXIT_CODE=$?
fi

echo
if [ $EXIT_CODE -eq 0 ]; then
    echo -e "${GREEN}ok${NC}    TSA Workbench closed"
else
    echo -e "${RED}FAIL${NC}  TSA Workbench exited with code: $EXIT_CODE"
fi

echo
echo "Next steps:"
echo "  Run a guided demo: ./bin/episode_02_create_timeseries.sh"
echo "  Read docs/user-manual/11-classic-analytics.md"
echo

exit $EXIT_CODE
