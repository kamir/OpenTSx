#!/usr/bin/env bash

# Builds and installs every module with `mvn clean generate-sources compile package install -DskipTests=true`.
# Needs Java 17 or later and Maven 3.9 or later.
#
# Usage: ./bin/010_build.sh [--help]
#
# Maven runs with the JDK of JAVA_HOME when it is set, otherwise with the java on PATH.
#
# Exit codes: 0 built, 1 Java or pom.xml is missing, 2 usage error, otherwise the exit code of mvn (127 when mvn is
# not on PATH).

set -e

if [ $# -gt 0 ]; then
    if [ "$1" = "--help" ] && [ $# -eq 1 ]; then
        sed -n '3,/^set -e/{/^#/p}' "$0" | sed 's/^# \{0,1\}//'
        exit 0
    fi
    echo "010_build.sh: $1: unknown argument; expected no argument or --help" >&2
    exit 2
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

echo "OpenTSx build"
echo
echo "Project root: $PROJECT_ROOT"
echo

if [ -n "${JAVA_HOME:-}" ]; then
    echo "JAVA_HOME: $JAVA_HOME"
else
    echo "warn  JAVA_HOME is not set; mvn uses the java on PATH, which must be Java 17 or later"
    echo "      Set JAVA_HOME to a JDK 17 or later to build with another JDK"
fi

if ! command -v java &> /dev/null; then
    echo "ERROR: Java not found in PATH" >&2
    echo "Please install Java 17+ and set JAVA_HOME" >&2
    exit 1
fi

echo "Java version:"
java -version
echo

cd "$PROJECT_ROOT"

if [ ! -f "pom.xml" ]; then
    echo "ERROR: pom.xml not found in $PROJECT_ROOT" >&2
    echo "Are you in the correct directory?" >&2
    exit 1
fi

echo "Running: mvn clean generate-sources compile package install -DskipTests=true"
echo

mvn clean generate-sources compile package install -DskipTests=true

echo
echo "ok    Build completed"
echo
echo "Next steps:"
echo "  1. Validate environment: ./bin/000_validate_environment.sh"
echo "  2. Launch GUI: ./bin/000_launch_tsa_workbench.sh"
echo "  3. Run a guided demo: ./bin/episode_02_create_timeseries.sh"
echo
