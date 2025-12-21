#!/usr/bin/env bash

# Determine script directory and project root
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

echo "Running MacroRecorder Demo..."
echo "Project Root: $PROJECT_ROOT"

if [ -n "$JAVA_HOME" ]; then
    echo "Using JAVA_HOME: $JAVA_HOME"
fi

cd "$PROJECT_ROOT"
mvn exec:java -Dexec.mainClass="org.opentsx.app.bucketanalyser.MacroRecorder2"
