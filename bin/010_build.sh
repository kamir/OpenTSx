#!/usr/bin/env bash

###############################################################################
# OpenTSx Build Script
###############################################################################
#
# Script: 010_build.sh
# Purpose: Build the OpenTSx project using Maven
# Episode: E01 - Environment Setup & First Run
#
# Usage:
#   ./bin/010_build.sh [--skip-tests]
#
###############################################################################

set -e  # Exit on error

# Determine script directory and project root
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

echo "=========================================="
echo "  OpenTSx Build Script"
echo "=========================================="
echo
echo "Project root: $PROJECT_ROOT"
echo

# Check if JAVA_HOME is set, if not try to detect it
if [ -z "$JAVA_HOME" ]; then
    echo "JAVA_HOME not set, attempting to detect..."

    # macOS with Homebrew OpenJDK 21
    if [ -d "/opt/homebrew/Cellar/openjdk@21/21.0.8/libexec/openjdk.jdk/Contents/Home" ]; then
        export JAVA_HOME="/opt/homebrew/Cellar/openjdk@21/21.0.8/libexec/openjdk.jdk/Contents/Home"
        echo "Found JAVA_HOME: $JAVA_HOME"
    # macOS with other JDK
    elif [ -d "/Library/Java/JavaVirtualMachines" ]; then
        LATEST_JDK=$(ls -1 /Library/Java/JavaVirtualMachines | grep -E 'jdk|openjdk' | sort -r | head -1)
        if [ -n "$LATEST_JDK" ]; then
            export JAVA_HOME="/Library/Java/JavaVirtualMachines/$LATEST_JDK/Contents/Home"
            echo "Found JAVA_HOME: $JAVA_HOME"
        fi
    fi
fi

# Verify Java is available
if ! command -v java &> /dev/null; then
    echo "ERROR: Java not found in PATH"
    echo "Please install Java 8+ and set JAVA_HOME"
    exit 1
fi

echo "Java version:"
java -version
echo

# Change to project root directory
cd "$PROJECT_ROOT"

# Verify pom.xml exists
if [ ! -f "pom.xml" ]; then
    echo "ERROR: pom.xml not found in $PROJECT_ROOT"
    echo "Are you in the correct directory?"
    exit 1
fi

echo "Building OpenTSx..."
echo "Running: mvn clean generate-sources compile package install -DskipTests=true"
echo

# Run Maven build
mvn clean generate-sources compile package install -DskipTests=true

EXIT_CODE=$?

echo
echo "=========================================="
if [ $EXIT_CODE -eq 0 ]; then
    echo "✓ Build completed successfully!"
    echo "=========================================="
    echo
    echo "Next steps:"
    echo "  1. Validate environment: ./bin/000_validate_environment.sh"
    echo "  2. Launch GUI: ./bin/000_launch_tsa_workbench.sh"
    echo "  3. Run demos: ./bin/120_run_demo.sh"
    echo
else
    echo "✗ Build failed with exit code $EXIT_CODE"
    echo "=========================================="
    echo
    echo "Troubleshooting:"
    echo "  - Check Java version (need 8+)"
    echo "  - Verify Maven is installed"
    echo "  - Check network connectivity for dependencies"
    echo "  - Run with: mvn clean install -X for debug output"
    echo
fi

exit $EXIT_CODE

########################################################################################################################
# The Hadoop.TS.NG library is used to provide some TS-Operations, to simplify the DAL.
#
#mvn install:install-file -Dfile=/Users/kamir/GitHub_TMP/Hadoop.TS.NG/target/hadoop-ts-ng-2.5.0-jar-with-dependencies.jar -DgroupId=org.hadoop-ts-ng -DartifactId=stsx-core -Dversion=2.5.0 -Dpackaging=jar
#mvn install:install-file -Dfile=$(pwd)/target/hadoop-ts-ng-2.5.0-jar-with-dependencies.jar -DgroupId=org.hadoop-ts-ng -DartifactId=stsx-core -Dversion=2.5.0 -Dpackaging=jar

#scp /Users/kamir/GitHub_TMP/Hadoop.TS.NG/target/hadoop-ts-ng-2.5.0-jar-with-dependencies.jar root@cc-poc-mk-1.gce.cloudera.com:/opt/cloudera/hadoop-ts-ng-2.5.0-jar-with-dependencies.jar
#mvn install:install-file -Dfile=/opt/cloudera/hadoop-ts-ng-2.5.0-jar-with-dependencies.jar -DgroupId=org.hadoop-ts-ng -DartifactId=stsx-core -Dversion=2.5.0 -Dpackaging=jar

#        <dependency>
#            <groupId>org.hadoop-ts-ng</groupId>
#            <artifactId>stsx-core</artifactId>
#            <version>2.5.0</version>
#        </dependency>
