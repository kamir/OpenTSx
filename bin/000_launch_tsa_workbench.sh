#!/usr/bin/env bash

###############################################################################
# OpenTSx TSA Workbench (GUI) Launcher
###############################################################################
#
# Script: 000_launch_tsa_workbench.sh
# Purpose: Launch the Time Series Analysis Workbench (MacroRecorder2) GUI
# Episode: E01 - Environment Setup & First Run
#
# Description:
#   This script launches the OpenTSx graphical user interface for interactive
#   time series analysis. The TSA Workbench (MacroRecorder2) provides:
#   - Visual time series exploration
#   - Interactive charting
#   - Statistical analysis tools
#   - Bucket-based data organization
#
# Prerequisites:
#   - OpenTSx built successfully (run 010_build.sh first)
#   - Java 8+ installed and JAVA_HOME set
#   - Maven dependencies resolved
#
# Usage:
#   ./bin/000_launch_tsa_workbench.sh
#   # or with custom Java options:
#   JAVA_OPTS="-Xmx4g" ./bin/000_launch_tsa_workbench.sh
#
# Learning Objectives:
#   - Launch the OpenTSx GUI tool
#   - Explore time series visually
#   - Understand the TSA Workbench interface
#   - Get familiar with interactive analysis
#
###############################################################################

set -e  # Exit on error

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Script directory
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

echo -e "${BLUE}==========================================${NC}"
echo -e "${BLUE}  OpenTSx TSA Workbench Launcher${NC}"
echo -e "${BLUE}  Time Series Analysis GUI Tool${NC}"
echo -e "${BLUE}==========================================${NC}"
echo

# Check if Java is installed
if ! command -v java &> /dev/null; then
    echo -e "${RED}ERROR: Java not found!${NC}"
    echo "Please install Java 8+ and set JAVA_HOME"
    exit 1
fi

# Display Java version
JAVA_VERSION=$(java -version 2>&1 | head -n 1 | cut -d'"' -f2)
echo -e "${GREEN}✓${NC} Java version: $JAVA_VERSION"

# Set JAVA_HOME if not set (try common locations)
if [ -z "$JAVA_HOME" ]; then
    echo -e "${YELLOW}⚠${NC}  JAVA_HOME not set, attempting to find it..."

    # macOS
    if [ -d "/Library/Java/JavaVirtualMachines" ]; then
        # Find latest JDK
        LATEST_JDK=$(ls -1 /Library/Java/JavaVirtualMachines | grep -E 'jdk|openjdk' | sort -r | head -1)
        if [ -n "$LATEST_JDK" ]; then
            export JAVA_HOME="/Library/Java/JavaVirtualMachines/$LATEST_JDK/Contents/Home"
            echo -e "${GREEN}✓${NC} Found JAVA_HOME: $JAVA_HOME"
        fi
    fi

    # Linux (common locations)
    if [ -z "$JAVA_HOME" ] && [ -d "/usr/lib/jvm" ]; then
        LATEST_JDK=$(ls -1 /usr/lib/jvm | grep -E 'java-.*-openjdk' | sort -r | head -1)
        if [ -n "$LATEST_JDK" ]; then
            export JAVA_HOME="/usr/lib/jvm/$LATEST_JDK"
            echo -e "${GREEN}✓${NC} Found JAVA_HOME: $JAVA_HOME"
        fi
    fi

    # Homebrew on macOS
    if [ -z "$JAVA_HOME" ] && command -v brew &> /dev/null; then
        BREW_JAVA=$(brew --prefix openjdk 2>/dev/null || brew --prefix java 2>/dev/null || echo "")
        if [ -n "$BREW_JAVA" ] && [ -d "$BREW_JAVA" ]; then
            export JAVA_HOME="$BREW_JAVA/libexec/openjdk.jdk/Contents/Home"
            echo -e "${GREEN}✓${NC} Found JAVA_HOME via Homebrew: $JAVA_HOME"
        fi
    fi
fi

# Check if project is built
JAR_FILE="$PROJECT_ROOT/opentsx-core/target/*.jar"
if ! ls $JAR_FILE 1> /dev/null 2>&1; then
    echo -e "${RED}ERROR: OpenTSx not built!${NC}"
    echo "Please run: ./bin/010_build.sh first"
    exit 1
fi

echo -e "${GREEN}✓${NC} OpenTSx build found"
echo

# Set default Java options if not provided
if [ -z "$JAVA_OPTS" ]; then
    JAVA_OPTS="-Xmx2g -Xms512m"
    echo -e "${BLUE}Using default Java options: $JAVA_OPTS${NC}"
else
    echo -e "${BLUE}Using custom Java options: $JAVA_OPTS${NC}"
fi

# Set display environment for headless systems (optional)
if [ -z "$DISPLAY" ]; then
    echo -e "${YELLOW}⚠${NC}  DISPLAY not set (headless system?)"
    echo "   GUI may not work on headless systems without X11 forwarding"
fi

echo
echo -e "${BLUE}=========================================${NC}"
echo -e "${BLUE}  Launching TSA Workbench...${NC}"
echo -e "${BLUE}=========================================${NC}"
echo
echo "Main class: org.opentsx.app.bucketanalyser.MacroRecorder2"
echo "Project: $PROJECT_ROOT"
echo

# Change to project root
cd "$PROJECT_ROOT"

# Launch using Maven exec plugin
echo -e "${GREEN}Starting GUI...${NC}"
echo

# Option 1: Using maven exec (recommended)
if command -v mvn &> /dev/null; then
    echo "Launching with Maven..."
    mvn -f opentsx-core/pom.xml exec:java \
        -Dexec.mainClass="org.opentsx.app.bucketanalyser.MacroRecorder2" \
        -Dexec.args="" \
        -Dexec.cleanupDaemonThreads=false
else
    # Option 2: Direct java execution (fallback)
    echo "Launching with java directly..."

    # Build classpath from Maven dependencies
    if [ -f "$PROJECT_ROOT/opentsx-core/target/classpath.txt" ]; then
        CLASSPATH=$(cat "$PROJECT_ROOT/opentsx-core/target/classpath.txt")
    else
        echo -e "${YELLOW}⚠${NC}  Building classpath..."
        CLASSPATH="$PROJECT_ROOT/opentsx-core/target/classes:$PROJECT_ROOT/opentsx-core/target/*:$HOME/.m2/repository/*/*/*.jar"
    fi

    java $JAVA_OPTS \
        -cp "$CLASSPATH" \
        org.opentsx.app.bucketanalyser.MacroRecorder2
fi

EXIT_CODE=$?

echo
if [ $EXIT_CODE -eq 0 ]; then
    echo -e "${GREEN}✓ TSA Workbench closed successfully${NC}"
else
    echo -e "${RED}✗ TSA Workbench exited with code: $EXIT_CODE${NC}"
fi

echo
echo -e "${BLUE}=========================================${NC}"
echo -e "${BLUE}  Session Complete${NC}"
echo -e "${BLUE}=========================================${NC}"
echo
echo "Next Steps:"
echo "  • Explore the GUI and time series visualization"
echo "  • Try loading sample data"
echo "  • Run analysis demos: ./bin/120_run_demo.sh"
echo "  • Learn more: see ONBOARDING-PATH-SWE.md or ONBOARDING-PATH-TSx.md"
echo

exit $EXIT_CODE
