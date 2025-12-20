#!/bin/bash

###############################################################################
# OpenTSx Documentation PDF Builder
###############################################################################
#
# This script converts the GitBook-style markdown documentation into a
# comprehensive PDF manual using Pandoc.
#
# Requirements:
#   - pandoc (>= 2.0)
#   - pdflatex or xelatex (TeXLive recommended)
#   - Linux/macOS or WSL on Windows
#
# Usage:
#   ./bin/build_manual_pdf.sh [OPTIONS]
#
# Options:
#   --engine <engine>   PDF engine: pdflatex (default), xelatex, lualatex
#   --output <file>     Output PDF filename (default: OpenTSx-Manual.pdf)
#   --verbose           Show detailed pandoc output
#   --check-only        Only check if tools are available
#
###############################################################################

set -e  # Exit on error

# Color output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Default settings
PDF_ENGINE="pdflatex"
OUTPUT_FILE="OpenTSx-Manual.pdf"
VERBOSE=false
CHECK_ONLY=false

# Directories
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
DOCS_DIR="$PROJECT_ROOT/docs/manual"
BUILD_DIR="$PROJECT_ROOT/target/documentation"
OUTPUT_DIR="$PROJECT_ROOT/target"

# Parse command line arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        --engine)
            PDF_ENGINE="$2"
            shift 2
            ;;
        --output)
            OUTPUT_FILE="$2"
            shift 2
            ;;
        --verbose)
            VERBOSE=true
            shift
            ;;
        --check-only)
            CHECK_ONLY=true
            shift
            ;;
        *)
            echo -e "${RED}Unknown option: $1${NC}"
            exit 1
            ;;
    esac
done

###############################################################################
# Helper Functions
###############################################################################

log_info() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

log_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

log_warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

check_command() {
    if command -v "$1" &> /dev/null; then
        log_success "$1 is installed ($(command -v $1))"
        return 0
    else
        log_error "$1 is not installed"
        return 1
    fi
}

###############################################################################
# Dependency Checking
###############################################################################

log_info "Checking dependencies..."

ALL_DEPS_OK=true

# Check pandoc
if ! check_command pandoc; then
    ALL_DEPS_OK=false
    log_error "Install pandoc: https://pandoc.org/installing.html"
    echo "       Ubuntu/Debian: sudo apt-get install pandoc"
    echo "       macOS: brew install pandoc"
    echo "       Windows: choco install pandoc"
fi

# Check PDF engine
if ! check_command "$PDF_ENGINE"; then
    ALL_DEPS_OK=false
    log_error "Install TeXLive for $PDF_ENGINE:"
    echo "       Ubuntu/Debian: sudo apt-get install texlive-full"
    echo "       macOS: brew install --cask mactex"
    echo "       Windows: https://www.tug.org/texlive/windows.html"
fi

if [ "$CHECK_ONLY" = true ]; then
    if [ "$ALL_DEPS_OK" = true ]; then
        log_success "All dependencies are installed"
        exit 0
    else
        log_error "Some dependencies are missing"
        exit 1
    fi
fi

if [ "$ALL_DEPS_OK" = false ]; then
    log_error "Missing dependencies. Run with --check-only for details."
    exit 1
fi

###############################################################################
# Build Process
###############################################################################

log_info "Building OpenTSx PDF Manual..."
log_info "  Docs directory: $DOCS_DIR"
log_info "  Build directory: $BUILD_DIR"
log_info "  Output: $OUTPUT_DIR/$OUTPUT_FILE"
log_info "  PDF Engine: $PDF_ENGINE"

# Create build directory
mkdir -p "$BUILD_DIR"
mkdir -p "$OUTPUT_DIR"

# Create temporary merged markdown file
TEMP_MD="$BUILD_DIR/manual-merged.md"

log_info "Merging markdown files..."

# Start with title page
cat > "$TEMP_MD" <<'EOF'
---
title: "OpenTSx Manual"
subtitle: "Comprehensive Guide to Time Series Analysis in Java"
author: "OpenTSx Project"
date: "Version 3.0.0"
documentclass: book
geometry: margin=1in
fontsize: 11pt
toc: true
toc-depth: 3
numbersections: true
colorlinks: true
linkcolor: blue
urlcolor: blue
citecolor: blue
---

\newpage

EOF

# Define chapter order based on SUMMARY.md
CHAPTERS=(
    "introduction/README.md"
    "introduction/what-is-opentsx.md"
    "introduction/why-opentsx.md"
    "introduction/installation.md"
    "core-concepts/README.md"
    "core-concepts/architecture-overview.md"
    "core-concepts/timeseries-object.md"
    "core-concepts/data-model.md"
    "core-concepts/core-procedures.md"
    "data-operations/README.md"
    "data-operations/creating-timeseries.md"
    "data-operations/loading-data.md"
    "data-operations/transformations.md"
    "data-operations/exporting-data.md"
    "statistical-analysis/README.md"
    "statistical-analysis/descriptive-stats.md"
    "statistical-analysis/distribution-testing.md"
    "statistical-analysis/correlation.md"
    "advanced-topics/README.md"
    "advanced-topics/dfa-analysis.md"
    "advanced-topics/hadoop-integration.md"
    "best-practices/README.md"
    "best-practices/performance.md"
    "best-practices/memory-management.md"
    "best-practices/production.md"
    "appendix/README.md"
    "appendix/api-reference.md"
    "appendix/glossary.md"
    "appendix/troubleshooting.md"
)

# Append each chapter
for chapter in "${CHAPTERS[@]}"; do
    CHAPTER_FILE="$DOCS_DIR/$chapter"
    if [ -f "$CHAPTER_FILE" ]; then
        log_info "  Adding: $chapter"
        echo "" >> "$TEMP_MD"
        echo "\newpage" >> "$TEMP_MD"
        echo "" >> "$TEMP_MD"
        cat "$CHAPTER_FILE" >> "$TEMP_MD"
    else
        log_warning "  Skipping missing file: $chapter"
    fi
done

log_success "Markdown files merged: $TEMP_MD"

# Build PDF with pandoc
log_info "Generating PDF with pandoc..."

PANDOC_ARGS=(
    "$TEMP_MD"
    "-o" "$OUTPUT_DIR/$OUTPUT_FILE"
    "--pdf-engine=$PDF_ENGINE"
    "--toc"
    "--toc-depth=3"
    "--number-sections"
    "--highlight-style=tango"
    "--variable" "geometry:margin=1in"
    "--variable" "fontsize=11pt"
    "--variable" "documentclass=book"
    "--variable" "colorlinks=true"
    "--variable" "linkcolor=blue"
    "--variable" "urlcolor=blue"
    "--variable" "citecolor=blue"
)

if [ "$VERBOSE" = true ]; then
    PANDOC_ARGS+=("--verbose")
fi

# Execute pandoc
if pandoc "${PANDOC_ARGS[@]}"; then
    log_success "PDF generated successfully!"
    log_info "Output: $OUTPUT_DIR/$OUTPUT_FILE"

    # Show file size
    if [ -f "$OUTPUT_DIR/$OUTPUT_FILE" ]; then
        FILE_SIZE=$(du -h "$OUTPUT_DIR/$OUTPUT_FILE" | cut -f1)
        log_info "File size: $FILE_SIZE"
    fi

    # Cleanup temporary files (optional)
    # rm -f "$TEMP_MD"

    exit 0
else
    log_error "PDF generation failed"
    log_info "Check the merged markdown file: $TEMP_MD"
    exit 1
fi
