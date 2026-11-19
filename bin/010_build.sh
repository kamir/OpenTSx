#!/usr/bin/env bash

# Builds and installs every module with `mvn clean generate-sources compile package install -DskipTests=true`.
# Needs Java 17 or later and Maven 3.9 or later.
#
# Usage: ./bin/010_build.sh
#
# Exit codes: 0 built, 1 Java or pom.xml is missing, otherwise the exit code of mvn (127 when mvn is not on PATH).

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

echo "=========================================="
echo "  OpenTSx Build Script"
echo "=========================================="
echo
echo "Project root: $PROJECT_ROOT"
echo

if [ -z "$JAVA_HOME" ]; then
    echo "JAVA_HOME not set, attempting to detect..."

    if [ -d "/opt/homebrew/Cellar/openjdk@21/21.0.8/libexec/openjdk.jdk/Contents/Home" ]; then
        export JAVA_HOME="/opt/homebrew/Cellar/openjdk@21/21.0.8/libexec/openjdk.jdk/Contents/Home"
        echo "Found JAVA_HOME: $JAVA_HOME"
    elif [ -d "/Library/Java/JavaVirtualMachines" ]; then
        LATEST_JDK=""
        for jdk in /Library/Java/JavaVirtualMachines/*jdk*; do
            if [ -d "$jdk" ]; then LATEST_JDK="${jdk##*/}"; fi
        done
        if [ -n "$LATEST_JDK" ]; then
            export JAVA_HOME="/Library/Java/JavaVirtualMachines/$LATEST_JDK/Contents/Home"
            echo "Found JAVA_HOME: $JAVA_HOME"
        fi
    fi
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

echo "Building OpenTSx..."
echo "Running: mvn clean generate-sources compile package install -DskipTests=true"
echo

mvn clean generate-sources compile package install -DskipTests=true

echo
echo "=========================================="
echo "✓ Build completed successfully!"
echo "=========================================="
echo
echo "Next steps:"
echo "  1. Validate environment: ./bin/000_validate_environment.sh"
echo "  2. Launch GUI: ./bin/000_launch_tsa_workbench.sh"
echo "  3. Run a guided demo: ./bin/episode_02_create_timeseries.sh"
echo
