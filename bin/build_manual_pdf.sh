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
PDF_ENGINE="xelatex"  # Use xelatex for better Unicode support
OUTPUT_FILE="OpenTSx-Manual.pdf"
VERBOSE=false
CHECK_ONLY=false

# Directories
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
DOCS_DIR="$PROJECT_ROOT/docs"
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
    # Part I: Getting Started
    "USER-GUIDE.md"
    "manual/introduction/installation.md"
    "FAQ.md"
    "TROUBLESHOOTING.md"

    # Part II: Onboarding Paths
    "onboarding/README.md"
    "onboarding/ONBOARDING-PATH-SWE.md"
    "onboarding/ONBOARDING-PATH-TSx.md"
    "onboarding/ONBOARDING-PATH-Python.md"
    "onboarding/ONBOARDING-PATH-Flink.md"

    # Part III: Core Manual
    "manual/introduction/README.md"
    "manual/introduction/what-is-time-series.md"
    "manual/introduction/architecture.md"
    "manual/introduction/when-to-use.md"
    # "manual/introduction/installation.md" # Already included in Part I

    "manual/core-concepts/README.md"
    "manual/core-concepts/timeseries-object.md"
    "manual/core-concepts/data-model.md"
    "manual/core-concepts/labels-metadata.md"
    "manual/core-concepts/vector-storage.md"
    "manual/core-concepts/mutability.md"

    "manual/data-operations/README.md"
    "manual/data-operations/creating-timeseries.md"
    "manual/data-operations/synthetic-data.md"
    "manual/data-operations/loading-data.md"
    "manual/data-operations/exporting-data.md"
    "manual/data-operations/accessing-data.md"
    "manual/data-operations/transformations.md"
    "manual/data-operations/filtering.md"
    "manual/data-operations/combining.md"

    "manual/statistical-analysis/README.md"
    "manual/statistical-analysis/descriptive-stats.md"
    "manual/statistical-analysis/normalization.md"
    "manual/statistical-analysis/moving-averages.md"
    "manual/statistical-analysis/trends.md"
    "manual/statistical-analysis/seasonality.md"
    "manual/statistical-analysis/autocorrelation.md"
    "manual/statistical-analysis/anomaly-detection.md"
    "manual/statistical-analysis/change-points.md"

    # Part IV: Python Implementation
    "manual/python/README.md"
    "manual/python/installation.md"
    "manual/python/api-overview.md"
    "manual/python/timeseries-object.md"
    "manual/python/dfa.md"
    "manual/python/mfdfa.md"
    "manual/python/event-synchronization.md"
    "manual/python/ris.md"
    "manual/python/interoperability.md"
    "manual/python/production.md"

    # Part V: Advanced Topics
    "manual/advanced-topics/README.md"
    "manual/advanced-topics/spark-processing.md"
    "manual/advanced-topics/kafka-streams.md"
    "manual/advanced-topics/storage-backends.md"
    "manual/advanced-topics/performance.md"
    "manual/advanced-topics/custom-operations.md"
    "manual/advanced-topics/interoperability.md"

    # Part VI: Architecture & Design
    "guides/ARCHITECTURE.md"
    "guides/MODULES.md"
    "guides/PYTHON-IMPLEMENTATION-DESIGN.md"
    "guides/SAAS-PLATFORM.md"
    "guides/WEB-UI-VISUAL-FLOW-BUILDER.md"
    "guides/SECURITY.md"
    "guides/FEATURES.md"
    "guides/FEATURE_COMPARISON_JAVA_PYTHON.md"
    "guides/INTEROPERABILITY_GUIDE.md"

    # Part VII: Deployment & Operations
    "guides/DEPLOYMENT.md"
    "guides/RELEASE-SUMMARY.md"

    # Part VIII: Best Practices
    "manual/best-practices/README.md"
    "manual/best-practices/error-handling.md"
    "manual/best-practices/memory-management.md"
    "manual/best-practices/testing.md"
    "manual/best-practices/code-organization.md"
    "manual/best-practices/performance-patterns.md"
    "manual/best-practices/common-pitfalls.md"

    # Part IX: Reference Materials
    "manual/appendix/README.md"
    "manual/appendix/api-reference.md"
    "API-DOCUMENTATION.md"
    "manual/appendix/glossary.md"
    "manual/appendix/migration-guide.md"
    "manual/appendix/further-reading.md"
    "manual/appendix/contributing.md"

    # Part X: Appendices
    "guides/PLAN.md"
    "guides/ROADMAP.md"
    "guides/MARKET_RESEARCH_ANALYSIS.md"
    "guides/IMPLEMENTATION_SUMMARY.md"
    "guides/DEMO_VALIDATION_REPORT.md"
    "PDF-GENERATION.md"
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

# Add Unicode font support for xelatex/lualatex
# Use system default fonts which handle Unicode well
# macOS: Uses system fonts (Helvetica, Times, etc.)
# Linux: Uses liberation fonts or system defaults
if [ "$PDF_ENGINE" = "xelatex" ] || [ "$PDF_ENGINE" = "lualatex" ]; then
    # Detect platform and set appropriate fonts
    if [[ "$OSTYPE" == "darwin"* ]]; then
        # macOS - use system fonts
        PANDOC_ARGS+=(
            "--variable" "mainfont=Helvetica Neue"
            "--variable" "monofont=Menlo"
        )
    elif command -v fc-list &> /dev/null; then
        # Linux with fontconfig - check for DejaVu
        if fc-list | grep -q "DejaVu"; then
            PANDOC_ARGS+=(
                "--variable" "mainfont=DejaVu Serif"
                "--variable" "sansfont=DejaVu Sans"
                "--variable" "monofont=DejaVu Sans Mono"
            )
        fi
        # Otherwise use system defaults (no explicit font setting)
    fi
    # If no fonts specified, xelatex will use Latin Modern (built-in Unicode support)
fi

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
